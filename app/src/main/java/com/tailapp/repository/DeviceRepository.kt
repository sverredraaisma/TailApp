package com.tailapp.repository

import android.util.Log
import com.tailapp.ble.BleTransport
import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResult
import com.tailapp.ble.protocol.CommandResultParser
import com.tailapp.ble.protocol.Crc32
import com.tailapp.ble.protocol.FftFrameBuilder
import com.tailapp.ble.protocol.LedCommands
import com.tailapp.ble.protocol.LedStateParser
import com.tailapp.ble.protocol.MotionCommands
import com.tailapp.ble.protocol.MotionStateParser
import com.tailapp.ble.protocol.ProfileCommands
import com.tailapp.ble.protocol.ProfileListParser
import com.tailapp.ble.protocol.Protocol
import com.tailapp.ble.protocol.SystemCommands
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.ble.protocol.SystemEventParser
import com.tailapp.ble.protocol.SystemInfoParser
import com.tailapp.model.DeviceState
import com.tailapp.model.LayerConfig
import com.tailapp.model.LedState
import com.tailapp.model.ProfileSlot
import com.tailapp.model.ServoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceRepository(
    private val transport: BleTransport,
    private val scope: CoroutineScope
) {
    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    /** FF07 events (taps, config-changed) for one-shot UI reactions. */
    private val _systemEvents = MutableSharedFlow<SystemEvent>(extraBufferCapacity = 16)
    val systemEvents: SharedFlow<SystemEvent> = _systemEvents.asSharedFlow()

    /** FF09 acknowledgements. Every non-FFT write produces exactly one. */
    private val _commandResults = MutableSharedFlow<CommandResult>(extraBufferCapacity = 32)
    val commandResults: SharedFlow<CommandResult> = _commandResults.asSharedFlow()

    val negotiatedMtu: StateFlow<Int> get() = transport.negotiatedMtu

    private var notificationJob: Job? = null
    private var setupJob: Job? = null

    init {
        scope.launch {
            transport.connectionState.collect { state ->
                _deviceState.update { it.copy(connectionState = state) }
                when (state) {
                    // Run setup in its own job: it takes seconds (MTU + discovery +
                    // four reads) and must not block this collector, or a disconnect
                    // during setup would not reach the UI until setup finished.
                    ConnectionState.CONNECTED -> {
                        setupJob?.cancel()
                        setupJob = scope.launch { onConnected() }
                    }
                    ConnectionState.DISCONNECTED -> onDisconnected()
                    ConnectionState.CONNECTING -> {}
                }
            }
        }
    }

    private suspend fun onConnected() {
        // Start collecting before subscribing, so notifications that arrive during
        // setup aren't dropped (characteristicUpdate has no replay buffer).
        notificationJob?.cancel()
        notificationJob = scope.launch { collectNotifications() }

        Log.d(TAG, "onConnected: requesting MTU")
        val mtu = transport.requestMtu(REQUESTED_MTU)
        Log.d(TAG, "onConnected: MTU negotiated = $mtu")

        Log.d(TAG, "onConnected: discovering services")
        val discovered = transport.discoverServices()
        Log.d(TAG, "onConnected: services discovered = $discovered")
        if (!discovered) {
            Log.e(TAG, "onConnected: service discovery failed, aborting")
            return
        }

        Log.d(TAG, "onConnected: enabling notifications")
        transport.enableNotifications(CharacteristicUuids.MOTION_STATE)
        transport.enableNotifications(CharacteristicUuids.LED_STATE)
        transport.enableNotifications(CharacteristicUuids.SYSTEM_EVENTS)
        // FF09 carries the accept/reject result for every command we send.
        transport.enableNotifications(CharacteristicUuids.CMD_RESULT)

        Log.d(TAG, "onConnected: reading initial state")
        refreshAll()
        Log.d(TAG, "onConnected: setup complete")
    }

    private suspend fun collectNotifications() {
        transport.characteristicUpdate.collect { update ->
            when (update.uuid) {
                CharacteristicUuids.MOTION_STATE ->
                    MotionStateParser.parse(update.value)?.let { ms ->
                        _deviceState.update { it.copy(motionState = ms) }
                    }

                CharacteristicUuids.LED_STATE ->
                    LedStateParser.parse(update.value)?.let { ls ->
                        _deviceState.update { it.copy(ledState = ls) }
                    }

                CharacteristicUuids.SYSTEM_EVENTS ->
                    SystemEventParser.parse(update.value)?.let { event ->
                        Log.d(TAG, "system event: $event")
                        _systemEvents.tryEmit(event)
                        // A profile load replaces the whole config on the device.
                        if (event == SystemEvent.CONFIG_CHANGED) refreshAll()
                    }

                CharacteristicUuids.CMD_RESULT ->
                    CommandResultParser.parse(update.value)?.let { result ->
                        if (!result.isSuccess) {
                            Log.w(
                                TAG,
                                "command rejected: ${result.characteristicName} " +
                                    "cmd=0x%02X -> ${result.result}".format(result.commandId)
                            )
                        }
                        _commandResults.tryEmit(result)
                        _deviceState.update { it.copy(lastCommandResult = result) }
                    }
            }
        }
    }

    /** Re-reads every readable characteristic. Used on connect and after a profile load. */
    private suspend fun refreshAll() {
        refreshMotionState()
        refreshLedState()
        refreshSystemInfo()
        refreshProfiles()
    }

    private suspend fun refreshMotionState() {
        val data = transport.readCharacteristic(CharacteristicUuids.MOTION_STATE)
        if (data == null) {
            Log.w(TAG, "FF02 read returned null")
            return
        }
        val parsed = MotionStateParser.parse(data)
        if (parsed == null) {
            Log.w(TAG, "FF02 parse failed (${data.size} bytes)")
            return
        }
        _deviceState.update { it.copy(motionState = parsed) }
    }

    private suspend fun refreshLedState() {
        val data = transport.readCharacteristic(CharacteristicUuids.LED_STATE)
        if (data == null) {
            Log.w(TAG, "FF04 read returned null")
            return
        }
        val parsed = LedStateParser.parse(data)
        if (parsed == null) {
            Log.w(TAG, "FF04 parse failed (${data.size} bytes)")
            return
        }
        _deviceState.update { it.copy(ledState = parsed) }
    }

    private suspend fun refreshSystemInfo() {
        val data = transport.readCharacteristic(CharacteristicUuids.SYSTEM_CONFIG)
        if (data == null) {
            Log.w(TAG, "FF06 read returned null")
            return
        }
        val parsed = SystemInfoParser.parse(data)
        if (parsed == null) {
            Log.w(TAG, "FF06 parse failed (${data.size} bytes)")
            return
        }
        if (!parsed.isProtocolSupported) {
            Log.w(
                TAG,
                "protocol version mismatch: device=${parsed.protocolVersion} " +
                    "app=${Protocol.SUPPORTED_PROTOCOL_VERSION}"
            )
        }
        _deviceState.update { it.copy(systemInfo = parsed) }
    }

    /** Reads the FF08 profile list (occupancy + names). */
    suspend fun refreshProfiles() {
        val data = transport.readCharacteristic(CharacteristicUuids.PROFILE_MGMT)
        if (data == null) {
            Log.w(TAG, "FF08 read returned null")
            return
        }
        val slots = ProfileListParser.parse(data)
        _deviceState.update { it.copy(profiles = slots) }
    }

    private fun onDisconnected() {
        setupJob?.cancel()
        setupJob = null
        notificationJob?.cancel()
        notificationJob = null
        _deviceState.value = DeviceState(connectionState = ConnectionState.DISCONNECTED)
    }

    // --- Motion commands ---

    suspend fun selectPattern(patternId: Byte) {
        transport.writeCharacteristic(CharacteristicUuids.MOTION_CMD, MotionCommands.selectPattern(patternId))
        _deviceState.update { state ->
            val ms = state.motionState ?: return@update state
            // The firmware zeroes the pattern params when the pattern changes.
            state.copy(motionState = ms.copy(activePatternId = patternId, params = List(8) { 0f }))
        }
    }

    suspend fun setPatternParam(paramId: Byte, value: Float) {
        transport.writeCharacteristic(CharacteristicUuids.MOTION_CMD, MotionCommands.setPatternParam(paramId, value))
        _deviceState.update { state ->
            val ms = state.motionState ?: return@update state
            val params = ms.params.toMutableList()
            val idx = paramId.toInt()
            if (idx in params.indices) params[idx] = value
            state.copy(motionState = ms.copy(params = params))
        }
    }

    suspend fun setServoConfig(
        servoId: Byte,
        axis: Byte,
        half: Byte,
        invert: Byte,
        muxChannel: Byte? = null
    ) {
        transport.writeCharacteristic(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.setServoConfig(servoId, axis, half, invert, muxChannel)
        )
        updateServo(servoId.toInt()) { servo ->
            servo.copy(
                axis = axis.toInt(),
                half = half.toInt(),
                invert = invert.toInt() != 0,
                muxChannel = muxChannel?.toInt() ?: servo.muxChannel
            )
        }
    }

    suspend fun setPidGains(servoId: Byte, kp: Float, ki: Float, kd: Float) {
        transport.writeCharacteristic(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.setPidGains(servoId, kp, ki, kd)
        )
        updateServo(servoId.toInt()) { it.copy(pid = it.pid.copy(kp = kp, ki = ki, kd = kd)) }
    }

    suspend fun calibrateZero() {
        transport.writeCharacteristic(CharacteristicUuids.MOTION_CMD, MotionCommands.calibrateZero())
    }

    suspend fun setAxisLimits(axis: Byte, min: Float, max: Float) {
        transport.writeCharacteristic(CharacteristicUuids.MOTION_CMD, MotionCommands.setAxisLimits(axis, min, max))
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
        transport.writeCharacteristic(CharacteristicUuids.MOTION_CMD, MotionCommands.setImuTap(imuId, enabled))
        _deviceState.update { state ->
            val si = state.systemInfo ?: return@update state
            val idx = imuId.toInt()
            if (idx !in si.imus.indices) return@update state
            val imus = si.imus.toMutableList()
            imus[idx] = imus[idx].copy(tapEnabled = enabled)
            state.copy(systemInfo = si.copy(imus = imus))
        }
    }

    private fun updateServo(index: Int, transform: (ServoConfig) -> ServoConfig) {
        _deviceState.update { state ->
            val si = state.systemInfo ?: return@update state
            if (index !in si.servos.indices) return@update state
            val servos = si.servos.toMutableList()
            servos[index] = transform(servos[index])
            state.copy(systemInfo = si.copy(servos = servos))
        }
    }

    // --- LED commands ---

    suspend fun setLayerEffect(layer: Byte, effectId: Byte, blendMode: Byte) {
        transport.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.setLayerEffect(layer, effectId, blendMode))
        _deviceState.update { state ->
            val ls = state.ledState ?: return@update state
            val idx = layer.toInt()
            if (idx < 0) return@update state
            val layers = ls.layers.toMutableList()
            // The firmware resets a layer's params when its effect is (re)assigned
            // and grows num_layers to cover the index, filling gaps with empties.
            while (layers.size <= idx) layers.add(LayerConfig.empty())
            layers[idx] = LayerConfig(
                effectId = effectId,
                blendMode = blendMode,
                enabled = true,
                flipX = false, flipY = false,
                mirrorX = false, mirrorY = false,
                params = List(8) { 0f }
            )
            state.copy(ledState = ls.copy(layers = layers))
        }
    }

    suspend fun setEffectParam(layer: Byte, paramId: Byte, value: Float) {
        transport.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.setEffectParam(layer, paramId, value))
        updateLayer(layer.toInt()) { config ->
            val params = config.params.toMutableList()
            val pIdx = paramId.toInt()
            if (pIdx in params.indices) params[pIdx] = value
            config.copy(params = params)
        }
    }

    suspend fun removeLayer(layer: Byte) {
        transport.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.removeLayer(layer))
        // The firmware clears the slot in place (effect_id = 0xFF) and leaves
        // num_layers alone — removing the entry here instead would shift every
        // higher layer's index and silently retarget subsequent edits.
        updateLayer(layer.toInt()) { LayerConfig.empty() }
    }

    suspend fun setLayerTransform(layer: Byte, flipX: Boolean, flipY: Boolean, mirrorX: Boolean, mirrorY: Boolean) {
        transport.writeCharacteristic(
            CharacteristicUuids.LED_CMD,
            LedCommands.setLayerTransform(layer, flipX, flipY, mirrorX, mirrorY)
        )
        updateLayer(layer.toInt()) {
            it.copy(flipX = flipX, flipY = flipY, mirrorX = mirrorX, mirrorY = mirrorY)
        }
    }

    suspend fun setLayerEnabled(layer: Byte, enabled: Boolean) {
        transport.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.setLayerEnabled(layer, enabled))
        updateLayer(layer.toInt()) { it.copy(enabled = enabled) }
    }

    private fun updateLayer(index: Int, transform: (LayerConfig) -> LayerConfig) {
        _deviceState.update { state ->
            val ls = state.ledState ?: return@update state
            if (index !in ls.layers.indices) return@update state
            val layers = ls.layers.toMutableList()
            layers[index] = transform(layers[index])
            state.copy(ledState = ls.copy(layers = layers))
        }
    }

    /** `0x08` BEGIN — clears the staging buffer and arms the length + CRC check. */
    suspend fun beginImage(totalLength: Int, crc32: Int): Boolean =
        transport.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.beginImage(totalLength, crc32))

    suspend fun uploadImageChunk(offset: Int, data: ByteArray): Boolean =
        transport.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.uploadImageChunk(offset, data))

    suspend fun finalizeImage(width: Byte, height: Byte, layer: Byte): Boolean =
        transport.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.finalizeImage(width, height, layer))

    /**
     * Uploads [rgb] using the integrity-checked flow: BEGIN(len, crc32) → chunks → FINALIZE.
     * The firmware verifies length and CRC-32 at finalize and rejects a corrupt image.
     *
     * @param onProgress called with 0f..1f after each chunk.
     * @return false if any write failed to reach the device.
     */
    suspend fun uploadImage(
        rgb: ByteArray,
        width: Int,
        height: Int,
        layer: Byte,
        chunkSize: Int,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        require(chunkSize > 0) { "chunkSize must be positive" }
        // The firmware rejects a zero-length BEGIN with OUT_OF_RANGE.
        require(rgb.isNotEmpty()) { "image data must not be empty" }
        if (!beginImage(rgb.size, Crc32.compute(rgb))) {
            Log.w(TAG, "uploadImage: BEGIN write failed")
            return false
        }
        var offset = 0
        while (offset < rgb.size) {
            val end = minOf(offset + chunkSize, rgb.size)
            if (!uploadImageChunk(offset, rgb.copyOfRange(offset, end))) {
                Log.w(TAG, "uploadImage: chunk at $offset failed")
                return false
            }
            offset = end
            onProgress(offset.toFloat() / rgb.size)
        }
        return finalizeImage(width.toByte(), height.toByte(), layer)
    }

    // --- System commands ---

    suspend fun setLedMatrix(ledsPerRing: List<Byte>) {
        val maxRings = _deviceState.value.capabilities.maxLedRings
        val rings = ledsPerRing.take(maxRings)
        transport.writeCharacteristic(
            CharacteristicUuids.SYSTEM_CONFIG,
            SystemCommands.setLedMatrix(rings, maxRings)
        )
        _deviceState.update { state ->
            val ls = state.ledState ?: LedState(0, emptyList(), emptyList())
            state.copy(
                ledState = ls.copy(
                    numRings = rings.size,
                    ledsPerRing = rings.map { it.toInt() and 0xFF }
                )
            )
        }
    }

    // --- FFT stream ---

    fun sendFftFrame(loudness: Byte, bins: ByteArray) {
        transport.writeWithoutResponse(
            CharacteristicUuids.FFT_STREAM,
            FftFrameBuilder.build(loudness, bins)
        )
    }

    fun setFftStreamActive(active: Boolean) {
        _deviceState.update { it.copy(fftStreamActive = active) }
    }

    // --- Profile commands ---

    suspend fun saveProfile(slot: Byte) {
        transport.writeCharacteristic(CharacteristicUuids.PROFILE_MGMT, ProfileCommands.saveProfile(slot))
        updateProfile(slot.toInt()) { it.copy(occupied = true) }
        scheduleProfileReconcile()
    }

    suspend fun loadProfile(slot: Byte) {
        transport.writeCharacteristic(CharacteristicUuids.PROFILE_MGMT, ProfileCommands.loadProfile(slot))
        // The device also raises SYS_EVENT_CONFIG_CHANGED on success; re-read here
        // too so a load still resyncs if the FF07 subscription didn't take.
        refreshAll()
    }

    suspend fun deleteProfile(slot: Byte) {
        transport.writeCharacteristic(CharacteristicUuids.PROFILE_MGMT, ProfileCommands.deleteProfile(slot))
        updateProfile(slot.toInt()) { it.copy(occupied = false, name = null) }
        scheduleProfileReconcile()
    }

    /** `0x05` RENAME — sets the display name stored alongside a profile slot. */
    suspend fun renameProfile(slot: Byte, name: String) {
        transport.writeCharacteristic(
            CharacteristicUuids.PROFILE_MGMT,
            ProfileCommands.renameProfile(slot, name)
        )
        updateProfile(slot.toInt()) { it.copy(name = name.ifBlank { null }) }
        scheduleProfileReconcile()
    }

    private fun updateProfile(index: Int, transform: (ProfileSlot) -> ProfileSlot) {
        _deviceState.update { state ->
            if (index !in state.profiles.indices) return@update state
            val profiles = state.profiles.toMutableList()
            profiles[index] = transform(profiles[index])
            state.copy(profiles = profiles)
        }
    }

    /**
     * The device rebuilds its FF08 read buffer once per second, so an immediate
     * re-read still returns the pre-command list. Reconcile the optimistic
     * update after that refresh has had time to land.
     */
    private fun scheduleProfileReconcile() {
        scope.launch {
            delay(PROFILE_RECONCILE_DELAY_MS)
            refreshProfiles()
        }
    }

    // --- Connection ---

    fun connect(address: String) {
        transport.connect(address)
    }

    fun disconnect() {
        transport.disconnect()
    }

    companion object {
        private const val TAG = "DeviceRepository"

        /**
         * Android's ceiling. The device advertises a preferred ATT MTU of 512, so
         * asking for the maximum lets it settle there — bigger image-upload chunks
         * and headroom for the FF0A pixel stream. The negotiated value is whatever
         * comes back on [BleTransport.negotiatedMtu]; callers must not assume 517.
         */
        private const val REQUESTED_MTU = 517

        /** Slightly longer than the device's 1 Hz state-buffer refresh. */
        internal const val PROFILE_RECONCILE_DELAY_MS = 1200L
    }
}
