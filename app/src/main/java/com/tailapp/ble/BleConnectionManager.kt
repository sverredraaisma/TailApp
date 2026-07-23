package com.tailapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.tailapp.ble.protocol.CharacteristicUuids
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) : BleTransport {

    companion object {
        private const val TAG = "BleConnMgr"
        private const val GATT_TIMEOUT_MS = 5000L
        private const val DEFAULT_MTU = 23
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private val mutex = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Motion state notifies at ~20 Hz; keep enough slack that a briefly-busy
    // collector doesn't drop LED/system/ACK notifications behind it.
    private val _characteristicUpdate = MutableSharedFlow<CharacteristicUpdate>(extraBufferCapacity = 64)
    override val characteristicUpdate: SharedFlow<CharacteristicUpdate> = _characteristicUpdate.asSharedFlow()

    private val _negotiatedMtu = MutableStateFlow(DEFAULT_MTU)
    override val negotiatedMtu: StateFlow<Int> = _negotiatedMtu.asStateFlow()

    @Volatile private var writeCompletion: ((Boolean) -> Unit)? = null
    @Volatile private var readCompletion: ((ByteArray?) -> Unit)? = null
    @Volatile private var descriptorWriteCompletion: ((Boolean) -> Unit)? = null
    @Volatile private var mtuCompletion: ((Int) -> Unit)? = null
    @Volatile private var servicesDiscoveredCompletion: ((Boolean) -> Unit)? = null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
            // A non-success status means the link failed (e.g. 133 GATT_ERROR); the
            // stack may still report STATE_CONNECTED, so treat it as a teardown.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onConnectionStateChange: GATT error status=$status, tearing down")
                teardown(gatt)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Set gatt BEFORE emitting CONNECTED so it's available when onConnected() runs
                    this@BleConnectionManager.gatt = gatt
                    _connectionState.value = ConnectionState.CONNECTED
                }
                BluetoothProfile.STATE_DISCONNECTED -> teardown(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            val completion = servicesDiscoveredCompletion
            servicesDiscoveredCompletion = null
            completion?.invoke(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) _negotiatedMtu.value = mtu
            val completion = mtuCompletion
            mtuCompletion = null
            completion?.invoke(_negotiatedMtu.value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value
            Log.d(TAG, "onCharacteristicRead(deprecated): uuid=${characteristic.uuid} status=$status len=${value?.size}")
            val completion = readCompletion
            readCompletion = null
            completion?.invoke(if (status == BluetoothGatt.GATT_SUCCESS) value?.copyOf() else null)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead: uuid=${characteristic.uuid} status=$status len=${value.size}")
            val completion = readCompletion
            readCompletion = null
            completion?.invoke(if (status == BluetoothGatt.GATT_SUCCESS) value.copyOf() else null)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite: uuid=${characteristic.uuid} status=$status")
            val completion = writeCompletion
            writeCompletion = null
            completion?.invoke(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite: uuid=${descriptor.characteristic.uuid} status=$status")
            val completion = descriptorWriteCompletion
            descriptorWriteCompletion = null
            completion?.invoke(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            emitUpdate(characteristic.uuid, value.copyOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            emitUpdate(characteristic.uuid, value.copyOf())
        }
    }

    private fun emitUpdate(uuid: UUID, value: ByteArray) {
        if (!_characteristicUpdate.tryEmit(CharacteristicUpdate(uuid, value))) {
            Log.w(TAG, "emitUpdate: dropped notification for $uuid (buffer full)")
        }
    }

    /**
     * Release the GATT client and unblock anything waiting on a callback.
     * Without this, an in-flight read/write would sit for the full 5 s timeout
     * after the link drops.
     */
    private fun teardown(gatt: BluetoothGatt) {
        _connectionState.value = ConnectionState.DISCONNECTED
        gatt.close()
        if (this.gatt === gatt) this.gatt = null
        _negotiatedMtu.value = DEFAULT_MTU
        failPendingOperations()
    }

    private fun failPendingOperations() {
        readCompletion?.also { readCompletion = null }?.invoke(null)
        writeCompletion?.also { writeCompletion = null }?.invoke(false)
        descriptorWriteCompletion?.also { descriptorWriteCompletion = null }?.invoke(false)
        servicesDiscoveredCompletion?.also { servicesDiscoveredCompletion = null }?.invoke(false)
        mtuCompletion?.also { mtuCompletion = null }?.invoke(_negotiatedMtu.value)
    }

    override fun connect(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e(TAG, "connect: no Bluetooth adapter")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        // getRemoteDevice throws on a malformed address rather than returning null.
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "connect: invalid address '$address'", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        // Keep the handle so disconnect() works while the link is still being set up.
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        val current = gatt
        if (current == null) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        if (_connectionState.value == ConnectionState.CONNECTING) {
            // No STATE_DISCONNECTED callback arrives for a connection that never
            // completed, so close here instead of leaking the client.
            teardown(current)
            return
        }
        current.disconnect()
    }

    override suspend fun requestMtu(mtu: Int): Int {
        return withTimeoutOrNull(GATT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                mtuCompletion = { cont.resume(it) }
                cont.invokeOnCancellation { mtuCompletion = null }
                val started = gatt?.requestMtu(mtu) ?: false
                if (!started) {
                    mtuCompletion = null
                    cont.resume(_negotiatedMtu.value)
                }
            }
        } ?: run {
            Log.w(TAG, "requestMtu timed out")
            mtuCompletion = null
            _negotiatedMtu.value
        }
    }

    override suspend fun discoverServices(): Boolean {
        return withTimeoutOrNull(GATT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                servicesDiscoveredCompletion = { cont.resume(it) }
                cont.invokeOnCancellation { servicesDiscoveredCompletion = null }
                val started = gatt?.discoverServices() ?: false
                if (!started) {
                    servicesDiscoveredCompletion = null
                    cont.resume(false)
                }
            }
        } ?: run {
            Log.w(TAG, "discoverServices timed out")
            servicesDiscoveredCompletion = null
            false
        }
    }

    override suspend fun readCharacteristic(uuid: UUID): ByteArray? = mutex.withLock {
        withTimeoutOrNull(GATT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val characteristic = findCharacteristic(uuid)
                if (characteristic == null) {
                    Log.w(TAG, "readCharacteristic: characteristic $uuid not found")
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                readCompletion = { cont.resume(it) }
                cont.invokeOnCancellation { readCompletion = null }
                val started = gatt?.readCharacteristic(characteristic) ?: false
                if (!started) {
                    Log.w(TAG, "readCharacteristic: gatt.readCharacteristic returned false for $uuid")
                    readCompletion = null
                    cont.resume(null)
                }
            }
        } ?: run {
            Log.w(TAG, "readCharacteristic timed out for $uuid")
            readCompletion = null
            null
        }
    }

    override suspend fun writeCharacteristic(uuid: UUID, data: ByteArray): Boolean = mutex.withLock {
        withTimeoutOrNull(GATT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val characteristic = findCharacteristic(uuid)
                if (characteristic == null) {
                    Log.w(TAG, "writeCharacteristic: characteristic $uuid not found")
                    cont.resume(false)
                    return@suspendCancellableCoroutine
                }
                writeCompletion = { cont.resume(it) }
                cont.invokeOnCancellation { writeCompletion = null }
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt?.writeCharacteristic(
                        characteristic,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    characteristic.value = data
                    @Suppress("DEPRECATION")
                    gatt?.writeCharacteristic(characteristic) ?: false
                }
                if (!started) {
                    Log.w(TAG, "writeCharacteristic: gatt.writeCharacteristic returned false for $uuid")
                    writeCompletion = null
                    cont.resume(false)
                }
            }
        } ?: run {
            Log.w(TAG, "writeCharacteristic timed out for $uuid")
            writeCompletion = null
            false
        }
    }

    /**
     * Write Without Response — bypasses the mutex since it doesn't require a GATT response.
     * Used for FFT streaming (FF05) at 30fps without blocking command writes.
     */
    override fun writeWithoutResponse(uuid: UUID, data: ByteArray) {
        val characteristic = findCharacteristic(uuid) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(characteristic)
        }
    }

    override suspend fun enableNotifications(uuid: UUID): Boolean = mutex.withLock {
        val characteristic = findCharacteristic(uuid)
        if (characteristic == null) {
            Log.w(TAG, "enableNotifications: characteristic $uuid not found")
            return@withLock false
        }
        gatt?.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(CharacteristicUuids.CCCD)
        if (descriptor == null) {
            Log.w(TAG, "enableNotifications: CCCD descriptor not found for $uuid")
            return@withLock false
        }

        withTimeoutOrNull(GATT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                descriptorWriteCompletion = { cont.resume(it) }
                cont.invokeOnCancellation { descriptorWriteCompletion = null }
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val result = gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    result == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt?.writeDescriptor(descriptor) ?: false
                }
                if (!started) {
                    Log.w(TAG, "enableNotifications: writeDescriptor returned false for $uuid")
                    descriptorWriteCompletion = null
                    cont.resume(false)
                }
            }
        } ?: run {
            Log.w(TAG, "enableNotifications timed out for $uuid")
            descriptorWriteCompletion = null
            false
        }
    }

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        return gatt?.getService(CharacteristicUuids.SERVICE)?.getCharacteristic(uuid)
    }
}
