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
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) : BleTransport {

    companion object {
        private const val TAG = "BleConnMgr"
        private const val GATT_TIMEOUT_MS = 5000L
        private const val DEFAULT_MTU = 23
        /** How long a graceful disconnect may take before the client is force-closed. */
        private const val DISCONNECT_TIMEOUT_MS = 3000L
    }

    /**
     * A GATT operation waiting for its callback, tagged with the characteristic
     * it belongs to.
     *
     * The tag is load-bearing: a timed-out operation releases the mutex while the
     * stack still owes a response, so without it the *next* operation's
     * continuation would be resumed by the *previous* characteristic's callback —
     * an FF06 read that timed out handing its bytes to the profile parser.
     *
     * A null [uuid] is a connection-scoped operation (MTU, discovery) that has
     * no characteristic to key on and only wants the one-shot guarantee.
     */
    private class Pending<T>(val uuid: UUID?, private val resume: (T) -> Unit) {
        private val done = AtomicBoolean(false)

        /** One-shot: a stale callback and the failure path can both fire. */
        fun complete(value: T) {
            if (done.compareAndSet(false, true)) resume(value)
        }
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private var forceClose: Runnable? = null

    /**
     * True once the active client has discovered its services. CONNECTED is not
     * published before that — [findCharacteristic] returns null until then, so a
     * UI that trusted the state would show "Connected" over an unusable link.
     */
    @Volatile private var servicesReady = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Motion state notifies at ~20 Hz; keep enough slack that a briefly-busy
    // collector doesn't drop LED/system/ACK notifications behind it.
    private val _characteristicUpdate = MutableSharedFlow<CharacteristicUpdate>(extraBufferCapacity = 64)
    override val characteristicUpdate: SharedFlow<CharacteristicUpdate> = _characteristicUpdate.asSharedFlow()

    private val _negotiatedMtu = MutableStateFlow(DEFAULT_MTU)
    override val negotiatedMtu: StateFlow<Int> = _negotiatedMtu.asStateFlow()

    @Volatile private var writeCompletion: Pending<Boolean>? = null
    @Volatile private var readCompletion: Pending<ByteArray?>? = null
    @Volatile private var descriptorWriteCompletion: Pending<Boolean>? = null
    @Volatile private var mtuCompletion: Pending<Int>? = null
    @Volatile private var servicesDiscoveredCompletion: Pending<Boolean>? = null

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
                    // Set gatt BEFORE anything else so it's available when onConnected() runs.
                    this@BleConnectionManager.gatt = gatt
                    servicesReady = false
                    // CONNECTED waits for discovery: until the service table is
                    // populated every findCharacteristic returns null.
                    if (!gatt.discoverServices()) {
                        Log.e(TAG, "onConnectionStateChange: discoverServices() refused to start")
                        teardown(gatt)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> teardown(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            val ok = status == BluetoothGatt.GATT_SUCCESS
            val completion = servicesDiscoveredCompletion
            servicesDiscoveredCompletion = null
            if (completion != null) {
                servicesReady = ok
                completion.complete(ok)
                return
            }
            // No waiter: this is the discovery kicked off by the connection itself.
            if (this@BleConnectionManager.gatt !== gatt) return
            servicesReady = ok
            if (ok) {
                _connectionState.value = ConnectionState.CONNECTED
            } else {
                Log.e(TAG, "onServicesDiscovered: discovery failed, tearing down")
                teardown(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) _negotiatedMtu.value = mtu
            val completion = mtuCompletion
            mtuCompletion = null
            completion?.complete(_negotiatedMtu.value)
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
            completeRead(characteristic.uuid, if (status == BluetoothGatt.GATT_SUCCESS) value?.copyOf() else null)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead: uuid=${characteristic.uuid} status=$status len=${value.size}")
            completeRead(characteristic.uuid, if (status == BluetoothGatt.GATT_SUCCESS) value.copyOf() else null)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite: uuid=${characteristic.uuid} status=$status")
            val pending = writeCompletion
            if (pending == null || pending.uuid != characteristic.uuid) {
                Log.w(TAG, "onCharacteristicWrite: stale response for ${characteristic.uuid}, ignoring")
                return
            }
            writeCompletion = null
            pending.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite: uuid=${descriptor.characteristic.uuid} status=$status")
            val pending = descriptorWriteCompletion
            if (pending == null || pending.uuid != descriptor.characteristic.uuid) {
                Log.w(TAG, "onDescriptorWrite: stale response for ${descriptor.characteristic.uuid}, ignoring")
                return
            }
            descriptorWriteCompletion = null
            pending.complete(status == BluetoothGatt.GATT_SUCCESS)
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

    /** Hands a read response to its waiter, or drops it if it belongs to a timed-out read. */
    private fun completeRead(uuid: UUID, value: ByteArray?) {
        val pending = readCompletion
        if (pending == null || pending.uuid != uuid) {
            Log.w(TAG, "onCharacteristicRead: stale response for $uuid, ignoring")
            return
        }
        readCompletion = null
        pending.complete(value)
    }

    /** Clears a pending slot only when it is still the one this operation installed. */
    private fun clearRead(uuid: UUID) {
        if (readCompletion?.uuid == uuid) readCompletion = null
    }

    private fun clearWrite(uuid: UUID) {
        if (writeCompletion?.uuid == uuid) writeCompletion = null
    }

    private fun clearDescriptorWrite(uuid: UUID) {
        if (descriptorWriteCompletion?.uuid == uuid) descriptorWriteCompletion = null
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
        // A superseded client still reports STATE_DISCONNECTED. Closing it is
        // always right, but publishing DISCONNECTED and failing pending
        // operations is not: those belong to whatever client is current, and
        // doing it here would kill a *newer* connection's setup mid-flight.
        val isCurrent = this.gatt === gatt
        cancelForceClose()
        gatt.close()
        if (!isCurrent) return
        this.gatt = null
        servicesReady = false
        _connectionState.value = ConnectionState.DISCONNECTED
        _negotiatedMtu.value = DEFAULT_MTU
        failPendingOperations()
    }

    private fun failPendingOperations() {
        readCompletion?.also { readCompletion = null }?.complete(null)
        writeCompletion?.also { writeCompletion = null }?.complete(false)
        descriptorWriteCompletion?.also { descriptorWriteCompletion = null }?.complete(false)
        servicesDiscoveredCompletion?.also { servicesDiscoveredCompletion = null }?.complete(false)
        mtuCompletion?.also { mtuCompletion = null }?.complete(_negotiatedMtu.value)
    }

    private fun cancelForceClose() {
        forceClose?.let { handler.removeCallbacks(it) }
        forceClose = null
    }

    override fun connect(address: String) {
        // Android registers a GATT client per connectGatt and only allows ~32 of
        // them, after which connectGatt returns null; overwriting the handle
        // without closing leaks one every reconnect.
        closeCurrentClient()
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
        servicesReady = false
        // Keep the handle so disconnect() works while the link is still being set up.
        val client = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (client == null) {
            Log.e(TAG, "connect: connectGatt returned null (client registration exhausted?)")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        gatt = client
    }

    /** Disconnects and closes the current client synchronously, without touching connection state. */
    private fun closeCurrentClient() {
        val current = gatt ?: return
        Log.d(TAG, "closing previous GATT client")
        gatt = null
        servicesReady = false
        cancelForceClose()
        try {
            current.disconnect()
        } catch (e: SecurityException) {
            Log.w(TAG, "closeCurrentClient: disconnect denied", e)
        }
        current.close()
        _negotiatedMtu.value = DEFAULT_MTU
        failPendingOperations()
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
        // The callback is the only thing that closes the client, and it is not
        // guaranteed to arrive (a wedged stack, an adapter switched off). Close
        // it ourselves if it doesn't; teardown() cancels this on the happy path.
        cancelForceClose()
        val force = Runnable {
            forceClose = null
            if (gatt === current) {
                Log.w(TAG, "disconnect: no STATE_DISCONNECTED in ${DISCONNECT_TIMEOUT_MS}ms, forcing close")
                teardown(current)
            }
        }
        forceClose = force
        handler.postDelayed(force, DISCONNECT_TIMEOUT_MS)
    }

    // Both of these are ordinary GATT requests: the stack allows exactly one in
    // flight, so they queue behind reads and writes like everything else.
    override suspend fun requestMtu(mtu: Int): Int = mutex.withLock {
        withTimeoutOrNull(GATT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val pending = Pending<Int>(null) { if (cont.isActive) cont.resume(it) }
                mtuCompletion = pending
                cont.invokeOnCancellation { mtuCompletion = null }
                val started = gatt?.requestMtu(mtu) ?: false
                if (!started) {
                    mtuCompletion = null
                    pending.complete(_negotiatedMtu.value)
                }
            }
        } ?: run {
            Log.w(TAG, "requestMtu timed out")
            mtuCompletion = null
            _negotiatedMtu.value
        }
    }

    override suspend fun discoverServices(): Boolean = mutex.withLock {
        // The connection already discovered before publishing CONNECTED; a second
        // sweep would only cost a round trip and blank the service table meanwhile.
        if (servicesReady) return@withLock true
        withTimeoutOrNull(GATT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val pending = Pending<Boolean>(null) { if (cont.isActive) cont.resume(it) }
                servicesDiscoveredCompletion = pending
                cont.invokeOnCancellation { servicesDiscoveredCompletion = null }
                val started = gatt?.discoverServices() ?: false
                if (!started) {
                    servicesDiscoveredCompletion = null
                    pending.complete(false)
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
                // Installed before the request goes out, so a callback landing in
                // that window must not be able to resume the continuation twice.
                val pending = Pending<ByteArray?>(uuid) { if (cont.isActive) cont.resume(it) }
                readCompletion = pending
                cont.invokeOnCancellation { clearRead(uuid) }
                val started = gatt?.readCharacteristic(characteristic) ?: false
                if (!started) {
                    Log.w(TAG, "readCharacteristic: gatt.readCharacteristic returned false for $uuid")
                    clearRead(uuid)
                    pending.complete(null)
                }
            }
        } ?: run {
            Log.w(TAG, "readCharacteristic timed out for $uuid")
            clearRead(uuid)
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
                val pending = Pending<Boolean>(uuid) { if (cont.isActive) cont.resume(it) }
                writeCompletion = pending
                cont.invokeOnCancellation { clearWrite(uuid) }
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
                    clearWrite(uuid)
                    pending.complete(false)
                }
            }
        } ?: run {
            Log.w(TAG, "writeCharacteristic timed out for $uuid")
            clearWrite(uuid)
            false
        }
    }

    /**
     * Write Without Response — bypasses the mutex since it doesn't require a GATT response.
     * Used for FFT streaming (FF05) at 30fps without blocking command writes.
     */
    override fun writeWithoutResponse(uuid: UUID, data: ByteArray) {
        streamPacket(uuid, data)
    }

    /**
     * The checked form of [writeWithoutResponse]: false means the packet never
     * left the phone.
     *
     * Worth distinguishing, because the failures here are routine rather than
     * exceptional — `ERROR_GATT_WRITE_REQUEST_BUSY` is what bursting a stream
     * faster than the stack drains it looks like, and silently dropping those
     * makes a stuttering stream indistinguishable from a healthy one.
     */
    fun streamPacket(uuid: UUID, data: ByteArray): Boolean {
        val characteristic = findCharacteristic(uuid)
        if (characteristic == null) {
            Log.w(TAG, "writeWithoutResponse: characteristic $uuid not found")
            return false
        }
        val connection = gatt
        if (connection == null) {
            Log.w(TAG, "writeWithoutResponse: no GATT client for $uuid")
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = connection.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            if (status != BluetoothStatusCodes.SUCCESS) {
                Log.w(TAG, "writeWithoutResponse: $uuid rejected with status=$status")
            }
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            val ok = connection.writeCharacteristic(characteristic)
            if (!ok) Log.w(TAG, "writeWithoutResponse: $uuid refused by the stack")
            ok
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
                val pending = Pending<Boolean>(uuid) { if (cont.isActive) cont.resume(it) }
                descriptorWriteCompletion = pending
                cont.invokeOnCancellation { clearDescriptorWrite(uuid) }
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
                    clearDescriptorWrite(uuid)
                    pending.complete(false)
                }
            }
        } ?: run {
            Log.w(TAG, "enableNotifications timed out for $uuid")
            clearDescriptorWrite(uuid)
            false
        }
    }

    /**
     * Resolves a characteristic by UUID, the tail's own service first.
     *
     * The fallback sweep is what makes the standard services reachable — Battery
     * Service and Device Information live outside FF00, so a lookup scoped to
     * FF00 alone reports them as absent on a device that is publishing them. The
     * tail's service is still searched first: it is where everything hot lives,
     * and a same-UUID collision there should resolve to ours.
     */
    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val connection = gatt ?: return null
        connection.getService(CharacteristicUuids.SERVICE)?.getCharacteristic(uuid)?.let { return it }
        return connection.services?.firstNotNullOfOrNull { it.getCharacteristic(uuid) }
    }
}
