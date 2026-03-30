package com.tailapp.repository

import android.util.Log
import com.tailapp.ble.BleConnectionManager
import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.*
import com.tailapp.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceRepository(
    internal val bleManager: BleConnectionManager,
    private val scope: CoroutineScope
) {
    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    private var notificationJob: Job? = null

    init {
        scope.launch {
            bleManager.connectionState.collect { state ->
                _deviceState.update { it.copy(connectionState = state) }
                when (state) {
                    ConnectionState.CONNECTED -> onConnected()
                    ConnectionState.DISCONNECTED -> onDisconnected()
                    ConnectionState.CONNECTING -> {}
                }
            }
        }
    }

    private suspend fun onConnected() {
        Log.d(TAG, "onConnected: requesting MTU")
        val mtu = bleManager.requestMtu(256)
        Log.d(TAG, "onConnected: MTU negotiated = $mtu")

        Log.d(TAG, "onConnected: discovering services")
        val discovered = bleManager.discoverServices()
        Log.d(TAG, "onConnected: services discovered = $discovered")
        if (!discovered) {
            Log.e(TAG, "onConnected: service discovery failed, aborting")
            return
        }

        Log.d(TAG, "onConnected: enabling notifications")
        bleManager.enableNotifications(CharacteristicUuids.MOTION_STATE)
        bleManager.enableNotifications(CharacteristicUuids.LED_STATE)
        bleManager.enableNotifications(CharacteristicUuids.SYSTEM_EVENTS)

        // Initial state sync
        Log.d(TAG, "onConnected: reading initial state")
        bleManager.readCharacteristic(CharacteristicUuids.MOTION_STATE)?.let { data ->
            Log.d(TAG, "onConnected: FF02 motion state read, ${data.size} bytes")
            MotionStateParser.parse(data)?.let { ms ->
                _deviceState.update { it.copy(motionState = ms) }
            } ?: Log.w(TAG, "onConnected: FF02 parse returned null")
        } ?: Log.w(TAG, "onConnected: FF02 read returned null")

        bleManager.readCharacteristic(CharacteristicUuids.LED_STATE)?.let { data ->
            Log.d(TAG, "onConnected: FF04 LED state read, ${data.size} bytes")
            LedStateParser.parse(data)?.let { ls ->
                _deviceState.update { it.copy(ledState = ls) }
            } ?: Log.w(TAG, "onConnected: FF04 parse returned null")
        } ?: Log.w(TAG, "onConnected: FF04 read returned null")

        bleManager.readCharacteristic(CharacteristicUuids.SYSTEM_CONFIG)?.let { data ->
            Log.d(TAG, "onConnected: FF06 system info read, ${data.size} bytes")
            SystemInfoParser.parse(data)?.let { si ->
                _deviceState.update { it.copy(systemInfo = si) }
            } ?: Log.w(TAG, "onConnected: FF06 parse returned null")
        } ?: Log.w(TAG, "onConnected: FF06 read returned null")

        Log.d(TAG, "onConnected: setup complete, starting notification listener")
        notificationJob = scope.launch {
            bleManager.characteristicUpdate.collect { update ->
                when (update.uuid) {
                    CharacteristicUuids.MOTION_STATE ->
                        MotionStateParser.parse(update.value)?.let { ms ->
                            _deviceState.update { it.copy(motionState = ms) }
                        }
                    CharacteristicUuids.LED_STATE ->
                        LedStateParser.parse(update.value)?.let { ls ->
                            _deviceState.update { it.copy(ledState = ls) }
                        }
                }
            }
        }
    }

    companion object {
        private const val TAG = "DeviceRepository"
    }

    private fun onDisconnected() {
        notificationJob?.cancel()
        notificationJob = null
        _deviceState.value = DeviceState(connectionState = ConnectionState.DISCONNECTED)
    }

    // --- Motion commands ---

    suspend fun selectPattern(patternId: Byte) {
        bleManager.writeCharacteristic(CharacteristicUuids.MOTION_CMD, MotionCommands.selectPattern(patternId))
        _deviceState.update { state ->
            state.copy(motionState = state.motionState?.copy(activePatternId = patternId))
        }
    }

    suspend fun setPatternParam(paramId: Byte, value: Float) {
        bleManager.writeCharacteristic(CharacteristicUuids.MOTION_CMD, MotionCommands.setPatternParam(paramId, value))
        _deviceState.update { state ->
            val ms = state.motionState ?: return@update state
            val params = ms.params.toMutableList()
            val idx = paramId.toInt()
            if (idx in params.indices) params[idx] = value
            state.copy(motionState = ms.copy(params = params))
        }
    }

    suspend fun setServoConfig(servoId: Byte, axis: Byte, half: Byte, invert: Byte) {
        bleManager.writeCharacteristic(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.setServoConfig(servoId, axis, half, invert)
        )
    }

    suspend fun setPidGains(servoId: Byte, kp: Float, ki: Float, kd: Float) {
        bleManager.writeCharacteristic(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.setPidGains(servoId, kp, ki, kd)
        )
    }

    suspend fun calibrateZero() {
        bleManager.writeCharacteristic(CharacteristicUuids.MOTION_CMD, MotionCommands.calibrateZero())
    }

    suspend fun setAxisLimits(axis: Byte, min: Float, max: Float) {
        bleManager.writeCharacteristic(CharacteristicUuids.MOTION_CMD, MotionCommands.setAxisLimits(axis, min, max))
        _deviceState.update { state ->
            val ms = state.motionState ?: return@update state
            if (axis.toInt() == 0) {
                state.copy(motionState = ms.copy(xAxisMin = min, xAxisMax = max))
            } else {
                state.copy(motionState = ms.copy(yAxisMin = min, yAxisMax = max))
            }
        }
    }

    suspend fun setImuTap(imuId: Byte, enabled: Boolean) {
        bleManager.writeCharacteristic(CharacteristicUuids.MOTION_CMD, MotionCommands.setImuTap(imuId, enabled))
    }

    // --- LED commands ---

    suspend fun setLayerEffect(layer: Byte, effectId: Byte, blendMode: Byte) {
        bleManager.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.setLayerEffect(layer, effectId, blendMode))
        _deviceState.update { state ->
            val ls = state.ledState ?: return@update state
            val layers = ls.layers.toMutableList()
            val idx = layer.toInt()
            if (idx < layers.size) {
                layers[idx] = layers[idx].copy(effectId = effectId, blendMode = blendMode)
            } else if (idx == layers.size) {
                layers.add(LayerConfig(effectId, blendMode, true, false, false, false, false, List(8) { 0f }))
            }
            state.copy(ledState = ls.copy(layers = layers))
        }
    }

    suspend fun setEffectParam(layer: Byte, paramId: Byte, value: Float) {
        bleManager.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.setEffectParam(layer, paramId, value))
        _deviceState.update { state ->
            val ls = state.ledState ?: return@update state
            val layers = ls.layers.toMutableList()
            val idx = layer.toInt()
            if (idx in layers.indices) {
                val params = layers[idx].params.toMutableList()
                val pIdx = paramId.toInt()
                if (pIdx in params.indices) params[pIdx] = value
                layers[idx] = layers[idx].copy(params = params)
            }
            state.copy(ledState = ls.copy(layers = layers))
        }
    }

    suspend fun removeLayer(layer: Byte) {
        bleManager.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.removeLayer(layer))
        _deviceState.update { state ->
            val ls = state.ledState ?: return@update state
            val layers = ls.layers.toMutableList()
            val idx = layer.toInt()
            if (idx in layers.indices) layers.removeAt(idx)
            state.copy(ledState = ls.copy(layers = layers))
        }
    }

    suspend fun setLayerTransform(layer: Byte, flipX: Boolean, flipY: Boolean, mirrorX: Boolean, mirrorY: Boolean) {
        bleManager.writeCharacteristic(
            CharacteristicUuids.LED_CMD,
            LedCommands.setLayerTransform(layer, flipX, flipY, mirrorX, mirrorY)
        )
        _deviceState.update { state ->
            val ls = state.ledState ?: return@update state
            val layers = ls.layers.toMutableList()
            val idx = layer.toInt()
            if (idx in layers.indices) {
                layers[idx] = layers[idx].copy(flipX = flipX, flipY = flipY, mirrorX = mirrorX, mirrorY = mirrorY)
            }
            state.copy(ledState = ls.copy(layers = layers))
        }
    }

    suspend fun setLayerEnabled(layer: Byte, enabled: Boolean) {
        bleManager.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.setLayerEnabled(layer, enabled))
        _deviceState.update { state ->
            val ls = state.ledState ?: return@update state
            val layers = ls.layers.toMutableList()
            val idx = layer.toInt()
            if (idx in layers.indices) layers[idx] = layers[idx].copy(enabled = enabled)
            state.copy(ledState = ls.copy(layers = layers))
        }
    }

    suspend fun uploadImageChunk(offset: Int, data: ByteArray) {
        bleManager.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.uploadImageChunk(offset, data))
    }

    suspend fun finalizeImage(width: Byte, height: Byte, layer: Byte) {
        bleManager.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.finalizeImage(width, height, layer))
    }

    // --- System commands ---

    suspend fun setLedMatrix(ledsPerRing: List<Byte>) {
        bleManager.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, SystemCommands.setLedMatrix(ledsPerRing))
        _deviceState.update { state ->
            val ls = state.ledState ?: LedState(0, emptyList(), emptyList())
            state.copy(ledState = ls.copy(numRings = ledsPerRing.size, ledsPerRing = ledsPerRing.map { it.toInt() and 0xFF }))
        }
    }

    // --- FFT stream ---

    fun sendFftFrame(loudness: Byte, bins: ByteArray) {
        bleManager.writeWithoutResponse(
            CharacteristicUuids.FFT_STREAM,
            FftFrameBuilder.build(loudness, bins)
        )
    }

    fun setFftStreamActive(active: Boolean) {
        _deviceState.update { it.copy(fftStreamActive = active) }
    }

    // --- Profile commands ---

    suspend fun saveProfile(slot: Byte) {
        bleManager.writeCharacteristic(CharacteristicUuids.PROFILE_MGMT, ProfileCommands.saveProfile(slot))
    }

    suspend fun loadProfile(slot: Byte) {
        bleManager.writeCharacteristic(CharacteristicUuids.PROFILE_MGMT, ProfileCommands.loadProfile(slot))
        // Re-read state after profile load
        bleManager.readCharacteristic(CharacteristicUuids.MOTION_STATE)?.let { data ->
            MotionStateParser.parse(data)?.let { ms ->
                _deviceState.update { it.copy(motionState = ms) }
            }
        }
        bleManager.readCharacteristic(CharacteristicUuids.LED_STATE)?.let { data ->
            LedStateParser.parse(data)?.let { ls ->
                _deviceState.update { it.copy(ledState = ls) }
            }
        }
        bleManager.readCharacteristic(CharacteristicUuids.SYSTEM_CONFIG)?.let { data ->
            SystemInfoParser.parse(data)?.let { si ->
                _deviceState.update { it.copy(systemInfo = si) }
            }
        }
    }

    suspend fun deleteProfile(slot: Byte) {
        bleManager.writeCharacteristic(CharacteristicUuids.PROFILE_MGMT, ProfileCommands.deleteProfile(slot))
    }

    // --- Connection ---

    fun connect(address: String) {
        bleManager.connect(address)
    }

    fun disconnect() {
        bleManager.disconnect()
    }
}
