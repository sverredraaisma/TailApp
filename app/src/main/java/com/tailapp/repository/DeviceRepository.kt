package com.tailapp.repository

import android.util.Log
import com.tailapp.ble.BleTransport
import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.BatteryLevelParser
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResult
import com.tailapp.ble.protocol.CommandResultParser
import com.tailapp.ble.protocol.Crc32
import com.tailapp.ble.protocol.DiagnosticsParser
import com.tailapp.ble.protocol.DirectPixelFrame
import com.tailapp.ble.protocol.FftFrameBuilder
import com.tailapp.ble.protocol.LedCommands
import com.tailapp.ble.protocol.LedStateParser
import com.tailapp.ble.protocol.MotionCommands
import com.tailapp.ble.protocol.MotionTargetFrame
import com.tailapp.ble.protocol.MotionStateParser
import com.tailapp.ble.protocol.OtaCommands
import com.tailapp.ble.protocol.OtaDataFrame
import com.tailapp.ble.protocol.OtaStatusParser
import com.tailapp.ble.protocol.ProfileCommands
import com.tailapp.ble.protocol.ProfileListParser
import com.tailapp.ble.protocol.Protocol
import com.tailapp.ble.protocol.SystemCommands
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.ble.protocol.SystemEventParser
import com.tailapp.ble.protocol.SystemInfoParser
import com.tailapp.led.PixelBuffer
import com.tailapp.effects.DeviceAudioStream
import com.tailapp.effects.DeviceMotionStream
import com.tailapp.model.BatteryPolicy
import com.tailapp.model.BehaviorStateConfig
import com.tailapp.model.BehaviorTriggerConfig
import com.tailapp.model.DeviceInformation
import com.tailapp.model.DeviceState
import com.tailapp.model.FirmwareVersion
import com.tailapp.model.LayerConfig
import com.tailapp.model.LedState
import com.tailapp.model.MotionLimits
import com.tailapp.model.OtaStatus
import com.tailapp.model.OtaTransferState
import com.tailapp.model.ProfileSlot
import com.tailapp.model.ServoConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class DeviceRepository(
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    private val ackRetryPolicy: AckRetryPolicy = AckRetryPolicy(),
    private val otaPolicy: OtaTransferPolicy = OtaTransferPolicy()
) : DeviceAudioStream, DeviceMotionStream {
    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    /** FF07 events (taps, config-changed) for one-shot UI reactions. */
    private val _systemEvents = MutableSharedFlow<SystemEvent>(extraBufferCapacity = 16)
    val systemEvents: SharedFlow<SystemEvent> = _systemEvents.asSharedFlow()

    /** FF09 acknowledgements. Every non-FFT write produces exactly one. */
    private val _commandResults = MutableSharedFlow<CommandResult>(extraBufferCapacity = 32)
    val commandResults: SharedFlow<CommandResult> = _commandResults.asSharedFlow()

    val negotiatedMtu: StateFlow<Int> get() = transport.negotiatedMtu

    /** Pairs each FF09 result with the write that caused it. */
    private val ackTracker = CommandAckTracker()

    /**
     * FF09 results on their way to [ackTracker].
     *
     * Correlating takes the tracker's registration lock, which a write in flight
     * holds, so it cannot happen on the notification collector: a write during an
     * upload burst would otherwise hold up the 20 Hz FF02 stream the composer
     * reads. Unbounded and never blocking, with a single consumer, so results
     * still reach the tracker in arrival order — which is the whole basis of the
     * correlation.
     */
    private val ackInbox = Channel<CommandResult>(Channel.UNLIMITED)

    /**
     * Last FF0E status echo. Session state rather than part of [DeviceState]:
     * it describes a transfer this app is running, not a fact about the device's
     * configuration, and it changes hundreds of times during one.
     */
    private val _otaStatus = MutableStateFlow<OtaStatus?>(null)
    val otaStatus: StateFlow<OtaStatus?> = _otaStatus.asStateFlow()

    /**
     * FF0E echoes on their way to the transfer loop.
     *
     * Unbounded and never dropping, because the *absence* of movement between
     * two consecutive echoes is the signal that chunks are being discarded —
     * conflating them would hide exactly the case the echo exists to report.
     */
    private val otaEchoes = Channel<OtaStatus>(Channel.UNLIMITED)

    private var notificationJob: Job? = null
    private var setupJob: Job? = null

    init {
        scope.launch {
            for (result in ackInbox) ackTracker.onResult(result)
        }
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
        // 0x2A19 notifies on a change of percentage, not on a timer.
        transport.enableNotifications(CharacteristicUuids.BATTERY_LEVEL)
        // FF0E's offset echo is the only flow control a firmware transfer has.
        // Subscribed on connect rather than when an update starts, because an
        // interrupted transfer stays armed on the device across the reconnect
        // that follows it.
        transport.enableNotifications(CharacteristicUuids.OTA_DATA)
        // FF0C re-publishes once a second (uptime moves every second), so the
        // subscription keeps a diagnostics screen live rather than needing a poll.
        transport.enableNotifications(CharacteristicUuids.DIAGNOSTICS)

        Log.d(TAG, "onConnected: reading initial state")
        refreshAll()
        refreshBatteryLevel()
        refreshDeviceInformation()
        refreshDiagnostics()
        seedBatteryPolicyFromEventLog()
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
                        when (event) {
                            // A profile load replaces the whole config on the device.
                            SystemEvent.CONFIG_CHANGED -> refreshAll()
                            // Every motor is now latched off. Reflect that
                            // immediately rather than waiting up to a second for
                            // the next FF06 refresh to say so.
                            SystemEvent.STALL -> _deviceState.update { state ->
                                val motion = state.systemInfo?.motion ?: return@update state
                                state.copy(
                                    systemInfo = state.systemInfo.copy(
                                        motion = motion.copy(motorsEnabled = false)
                                    )
                                )
                            }
                            // The policy fires on a crossing only, so this is the
                            // one chance to learn it — nothing repeats it while
                            // the pack sits at a level.
                            SystemEvent.BATTERY_LOW -> setBatteryPolicy(BatteryPolicy.LOW)
                            SystemEvent.BATTERY_CRITICAL -> setBatteryPolicy(BatteryPolicy.CRITICAL)
                            SystemEvent.BATTERY_NORMAL -> setBatteryPolicy(BatteryPolicy.NORMAL)
                            else -> Unit
                        }
                    }

                CharacteristicUuids.BATTERY_LEVEL ->
                    setBatteryPercent(BatteryLevelParser.parse(update.value))

                CharacteristicUuids.OTA_DATA ->
                    OtaStatusParser.parse(update.value)?.let { status ->
                        _otaStatus.value = status
                        otaEchoes.trySend(status)
                    }

                CharacteristicUuids.DIAGNOSTICS ->
                    DiagnosticsParser.parse(update.value)?.let { diagnostics ->
                        _deviceState.update { it.copy(diagnostics = diagnostics) }
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
                        ackInbox.trySend(result)
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

    /**
     * Reads the standard Battery Level characteristic (0x2A19).
     *
     * A byte outside 0-100 is the device's unknown sentinel and lands as a null
     * percentage — which must stay distinguishable from 0 %, because a board with
     * no sense divider knows nothing about the pack and is not flat.
     */
    private suspend fun refreshBatteryLevel() {
        val data = transport.readCharacteristic(CharacteristicUuids.BATTERY_LEVEL)
        if (data == null) {
            Log.w(TAG, "0x2A19 read returned null")
            return
        }
        setBatteryPercent(BatteryLevelParser.parse(data))
    }

    /**
     * Recovers the current low-power policy from the FF07 event ring.
     *
     * The policy events fire on a threshold crossing only, so a tail that went
     * critical before this connection existed would otherwise look healthy while
     * it sat parked and dimmed. The ring's newest battery event is the answer.
     *
     * Deliberately *not* re-emitted on [systemEvents]: the ring also holds taps
     * and stalls from before the app arrived, and replaying those would fire
     * effects and banners for things that already happened.
     */
    private suspend fun seedBatteryPolicyFromEventLog() {
        val data = transport.readCharacteristic(CharacteristicUuids.SYSTEM_EVENTS) ?: return
        val policy = SystemEventParser.parseLog(data).mapNotNull(::batteryPolicyOf).lastOrNull()
        if (policy != null) setBatteryPolicy(policy)
    }

    /**
     * Reads the 0x180A Device Information strings. Every field is optional.
     *
     * Each is trimmed at the control-character boundary: they carry no length of
     * their own, so a device that NUL-pads its buffer would otherwise show the
     * padding as part of its model number.
     */
    private suspend fun refreshDeviceInformation() {
        suspend fun read(uuid: UUID): String? {
            val raw = transport.readCharacteristic(uuid) ?: return null
            return raw.toString(Charsets.UTF_8).trim { it <= ' ' }.takeIf { it.isNotEmpty() }
        }

        val info = DeviceInformation(
            manufacturer = read(CharacteristicUuids.DIS_MANUFACTURER),
            modelNumber = read(CharacteristicUuids.DIS_MODEL_NUMBER),
            firmwareRevision = read(CharacteristicUuids.DIS_FIRMWARE_REV),
            hardwareRevision = read(CharacteristicUuids.DIS_HARDWARE_REV)
        )
        if (info.isEmpty) {
            Log.w(TAG, "0x180A published nothing readable")
            return
        }
        _deviceState.update { it.copy(deviceInformation = info) }
    }

    /**
     * Reads the FF0C diagnostics snapshot (SYS-3).
     *
     * Public and read-on-open: the diagnostics screen must show something the
     * moment it opens, so it reads directly rather than waiting for the first
     * once-a-second notify. A payload too short for even the version-1 core parses
     * to null and is dropped rather than shown as zeros.
     */
    suspend fun refreshDiagnostics() {
        val data = transport.readCharacteristic(CharacteristicUuids.DIAGNOSTICS)
        if (data == null) {
            Log.w(TAG, "FF0C read returned null")
            return
        }
        val parsed = DiagnosticsParser.parse(data)
        if (parsed == null) {
            Log.w(TAG, "FF0C parse failed (${data.size} bytes)")
            return
        }
        _deviceState.update { it.copy(diagnostics = parsed) }
    }

    private fun setBatteryPercent(percent: Int?) {
        _deviceState.update { it.copy(battery = it.battery.copy(percent = percent)) }
    }

    private fun setBatteryPolicy(policy: BatteryPolicy) {
        _deviceState.update { it.copy(battery = it.battery.copy(policy = policy)) }
    }

    private fun batteryPolicyOf(event: SystemEvent): BatteryPolicy? = when (event) {
        SystemEvent.BATTERY_LOW -> BatteryPolicy.LOW
        SystemEvent.BATTERY_CRITICAL -> BatteryPolicy.CRITICAL
        SystemEvent.BATTERY_NORMAL -> BatteryPolicy.NORMAL
        else -> null
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
        // In its own job: abandoning takes the tracker's registration lock, which
        // a write in flight can hold for as long as the GATT stack takes to give
        // up on it, and this runs on the connection-state collector.
        scope.launch { ackTracker.abandonAll() }
        // An echo from the connection that just ended says nothing about the
        // next one, and a transfer resumed after a reconnect has to establish
        // where the device is from a fresh echo — not from one that arrived
        // before it went away.
        _otaStatus.value = null
        drainOtaEchoes()
        // Rebuilding from a fresh DeviceState() also resets directModeActive to
        // false, matching the firmware auto-reverting direct mode on disconnect
        // (app_bridge.cpp::app_led_render checks connection state every frame).
        _deviceState.value = DeviceState(connectionState = ConnectionState.DISCONNECTED)
    }

    // --- Acknowledged writes ---

    /**
     * Writes a command and returns as soon as the write is away, without waiting
     * for the device's verdict. This is what the many deliberately optimistic
     * call sites use: a slider drag cannot afford a round trip, and a rejection
     * there self-corrects at the next refresh of the characteristic it touched.
     *
     * The acknowledgement is still *registered*. FF09's sequence byte counts
     * every command the device processed, so a write that skipped registration
     * would leave a hole in the numbering, and the next command that does wait
     * would read its own answer as one that had been lost.
     *
     * FF05, FF0A and FF0B never come through here — the firmware acknowledges
     * none of them by design (see [streamDirectFrame]).
     */
    private suspend fun sendCommand(uuid: UUID, data: ByteArray): Boolean =
        register(uuid, data).written

    /**
     * Writes a command and waits for the device to answer it, retrying a `BUSY`
     * rejection per [ackRetryPolicy]. For the call sites where the answer changes
     * what the app does next.
     */
    private suspend fun sendCommandAwaitingAck(uuid: UUID, data: ByteArray): AckedWrite {
        var attempt = 1
        while (true) {
            val pending = register(uuid, data)
            if (!pending.written) return AckedWrite(written = false, result = null)

            val result = pending.await(ackRetryPolicy.timeoutMs)
            if (result == null || !result.result.isRetryable) {
                return AckedWrite(written = true, result = result)
            }
            if (attempt >= ackRetryPolicy.maxAttempts) {
                Log.w(
                    TAG,
                    "still busy after $attempt attempts: ${result.characteristicName} " +
                        "cmd=0x%02X".format(result.commandId)
                )
                return AckedWrite(written = true, result = result)
            }
            attempt++
            delay(ackRetryPolicy.retryDelayMs)
        }
    }

    private suspend fun register(uuid: UUID, data: ByteArray): CommandAckTracker.Pending =
        ackTracker.send(CharacteristicUuids.shortId(uuid), data.firstOrNull() ?: 0) {
            transport.writeCharacteristic(uuid, data)
        }

    // --- Motion commands ---

    suspend fun selectPattern(patternId: Byte) {
        sendCommand(CharacteristicUuids.MOTION_CMD, MotionCommands.selectPattern(patternId))
        _deviceState.update { state ->
            val ms = state.motionState ?: return@update state
            // The firmware zeroes the pattern params when the pattern changes.
            state.copy(motionState = ms.copy(activePatternId = patternId, params = List(8) { 0f }))
        }
    }

    suspend fun setPatternParam(paramId: Byte, value: Float) {
        sendCommand(CharacteristicUuids.MOTION_CMD, MotionCommands.setPatternParam(paramId, value))
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
        sendCommand(
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
        sendCommand(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.setPidGains(servoId, kp, ki, kd)
        )
        updateServo(servoId.toInt()) { it.copy(pid = it.pid.copy(kp = kp, ki = ki, kd = kd)) }
    }

    suspend fun calibrateZero() {
        sendCommand(CharacteristicUuids.MOTION_CMD, MotionCommands.calibrateZero())
    }

    suspend fun setAxisLimits(axis: Byte, min: Float, max: Float) {
        sendCommand(CharacteristicUuids.MOTION_CMD, MotionCommands.setAxisLimits(axis, min, max))
        _deviceState.update { state ->
            val ms = state.motionState ?: return@update state
            if (axis.toInt() == 0) {
                state.copy(motionState = ms.copy(xAxisMin = min, xAxisMax = max))
            } else {
                state.copy(motionState = ms.copy(yAxisMin = min, yAxisMax = max))
            }
        }
    }

    /**
     * Sets the open-loop motion limits and stall sensitivity for one motor.
     *
     * These are what shape motion on the current firmware; [setPidGains] is kept
     * only for wire compatibility with the vestigial FF06 fields.
     */
    suspend fun setMotionLimits(
        servoId: Byte,
        maxVelocity: Float,
        maxAcceleration: Float,
        maxJerk: Float,
        stallThreshold: Byte
    ) {
        sendCommand(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.setMotionLimits(servoId, maxVelocity, maxAcceleration, maxJerk, stallThreshold)
        )
        _deviceState.update { state ->
            val motion = state.systemInfo?.motion ?: return@update state
            val idx = servoId.toInt()
            if (idx !in motion.limits.indices) return@update state
            val limits = motion.limits.toMutableList()
            limits[idx] = MotionLimits(
                maxVelocity, maxAcceleration, maxJerk, stallThreshold.toInt() and 0xFF
            )
            state.copy(systemInfo = state.systemInfo.copy(motion = motion.copy(limits = limits)))
        }
    }

    /**
     * Re-energizes the motors and clears a stall latch, or forces freewheel.
     *
     * After a stall the device latches every motor off and nothing moves until
     * this arrives, so the optimistic update matters: the UI has to stop showing
     * a stall the moment the user acts on it.
     */
    suspend fun setMotorsEnabled(enabled: Boolean) {
        sendCommand(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.enableMotors(enabled)
        )
        _deviceState.update { state ->
            val motion = state.systemInfo?.motion ?: return@update state
            state.copy(systemInfo = state.systemInfo.copy(motion = motion.copy(motorsEnabled = enabled)))
        }
    }

    suspend fun setImuTap(imuId: Byte, enabled: Boolean) {
        sendCommand(CharacteristicUuids.MOTION_CMD, MotionCommands.setImuTap(imuId, enabled))
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

    // --- Behavior engine (MOT-6) ---

    // Every one of these waits for its answer, and none of them updates the
    // cached state optimistically. There is nothing to guess: the device has no
    // read for its behavior table, and everything the app could observe about
    // the engine — active state, why it changed, whether it is being outranked —
    // comes back in the FF02 behavior block within a notify period anyway.

    /**
     * Arms or disarms the engine. Enabling enters the table's idle state, so the
     * tail lands somewhere known rather than resuming a mood from before.
     */
    suspend fun setBehaviorEnabled(enabled: Boolean): AckedWrite =
        sendCommandAwaitingAck(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.setBehaviorEnabled(enabled)
        )

    /**
     * Forces a state for previewing it, ignoring the current state's minimum
     * dwell. The machine keeps running from there — this is not a hold.
     */
    suspend fun forceBehaviorState(stateIndex: Int): AckedWrite =
        sendCommandAwaitingAck(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.setBehaviorState(stateIndex.toByte())
        )

    /** Writes one 40-byte state record into the device's table. */
    suspend fun setBehaviorStateConfig(stateIndex: Int, state: BehaviorStateConfig): AckedWrite =
        sendCommandAwaitingAck(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.setBehaviorConfig(stateIndex.toByte(), state)
        )

    /** Writes one 16-byte trigger row into the device's table. */
    suspend fun setBehaviorTrigger(index: Int, trigger: BehaviorTriggerConfig): AckedWrite =
        sendCommandAwaitingAck(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.setBehaviorTrigger(index.toByte(), trigger)
        )

    // --- Keyframe sequences (MOT-8) ---

    /**
     * Uploads an encoded keyframe sequence to [slot] with the integrity-checked
     * flow BEGIN(slot, len, crc32) → chunks → FINALIZE(slot), the same shape
     * [uploadImage] uses on FF03.
     *
     * Every step waits for its acknowledgement, and the verdict is carried back
     * rather than reduced to a boolean: the device checks length, CRC-32 *and*
     * that the blob parses before it commits, and reports all three as
     * `BAD_STATE` at FINALIZE. That answer is the reason the handshake exists —
     * a caller that discarded it would leave the user with a slot that silently
     * did not change.
     *
     * @param onProgress called with 0f..1f after each chunk.
     */
    suspend fun uploadSequence(
        blob: ByteArray,
        slot: Byte,
        chunkSize: Int,
        onProgress: (Float) -> Unit = {}
    ): SequenceUploadResult {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(blob.isNotEmpty()) { "sequence blob must not be empty" }

        suspend fun step(step: SequenceUploadStep, data: ByteArray): SequenceUploadResult {
            val outcome = sendCommandAwaitingAck(CharacteristicUuids.MOTION_CMD, data)
            val result = when {
                !outcome.written -> SequenceUploadResult.NotWritten(step)
                outcome.result == null -> SequenceUploadResult.Unanswered(step)
                !outcome.result.isSuccess ->
                    SequenceUploadResult.Rejected(step, outcome.result.result)
                else -> SequenceUploadResult.Success
            }
            if (!result.succeeded) Log.w(TAG, "uploadSequence: ${result.message}")
            return result
        }

        val begin = step(
            SequenceUploadStep.BEGIN,
            MotionCommands.beginSequence(slot, blob.size, Crc32.compute(blob))
        )
        if (!begin.succeeded) return begin

        var offset = 0
        while (offset < blob.size) {
            val end = minOf(offset + chunkSize, blob.size)
            val chunk = step(
                SequenceUploadStep.CHUNK,
                MotionCommands.uploadSequenceChunk(offset, blob.copyOfRange(offset, end))
            )
            if (!chunk.succeeded) return chunk
            offset = end
            onProgress(offset.toFloat() / blob.size)
        }

        return step(SequenceUploadStep.FINALIZE, MotionCommands.finalizeSequence(slot))
    }

    /**
     * Points `PATTERN_KEYFRAME` at a stored sequence slot.
     *
     * Waits for the answer because an empty slot is refused with `BAD_STATE`,
     * and the alternative to hearing that is a tail that holds neutral while the
     * UI claims a sequence is playing.
     */
    suspend fun selectSequence(slot: Byte): AckedWrite =
        sendCommandAwaitingAck(
            CharacteristicUuids.MOTION_CMD,
            MotionCommands.selectSequence(slot)
        )

    // --- LED commands ---

    suspend fun setLayerEffect(layer: Byte, effectId: Byte, blendMode: Byte) {
        sendCommand(CharacteristicUuids.LED_CMD, LedCommands.setLayerEffect(layer, effectId, blendMode))
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
        sendCommand(CharacteristicUuids.LED_CMD, LedCommands.setEffectParam(layer, paramId, value))
        updateLayer(layer.toInt()) { config ->
            val params = config.params.toMutableList()
            val pIdx = paramId.toInt()
            if (pIdx in params.indices) params[pIdx] = value
            config.copy(params = params)
        }
    }

    suspend fun removeLayer(layer: Byte) {
        sendCommand(CharacteristicUuids.LED_CMD, LedCommands.removeLayer(layer))
        // The firmware clears the slot in place (effect_id = 0xFF) and leaves
        // num_layers alone — removing the entry here instead would shift every
        // higher layer's index and silently retarget subsequent edits.
        updateLayer(layer.toInt()) { LayerConfig.empty() }
    }

    suspend fun setLayerTransform(layer: Byte, flipX: Boolean, flipY: Boolean, mirrorX: Boolean, mirrorY: Boolean) {
        sendCommand(
            CharacteristicUuids.LED_CMD,
            LedCommands.setLayerTransform(layer, flipX, flipY, mirrorX, mirrorY)
        )
        updateLayer(layer.toInt()) {
            it.copy(flipX = flipX, flipY = flipY, mirrorX = mirrorX, mirrorY = mirrorY)
        }
    }

    suspend fun setLayerEnabled(layer: Byte, enabled: Boolean) {
        sendCommand(CharacteristicUuids.LED_CMD, LedCommands.setLayerEnabled(layer, enabled))
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

    /**
     * `0x08` BEGIN — clears the staging buffer and arms the length + CRC check.
     *
     * Every step of the upload waits for its acknowledgement. The handshake only
     * means anything if the answers are read: a rejected BEGIN leaves the staging
     * buffer unarmed, and pushing three kilobytes of chunks at it afterwards
     * spends the airtime for a FINALIZE that cannot succeed. A missing answer
     * counts as failure here for the same reason — the upload is a one-shot the
     * user can retry, and the log says which step went quiet.
     *
     * @return true only if the device acknowledged it.
     */
    suspend fun beginImage(totalLength: Int, crc32: Int): Boolean =
        sendCommandAwaitingAck(
            CharacteristicUuids.LED_CMD,
            LedCommands.beginImage(totalLength, crc32)
        ).accepted

    suspend fun uploadImageChunk(offset: Int, data: ByteArray): Boolean =
        sendCommandAwaitingAck(
            CharacteristicUuids.LED_CMD,
            LedCommands.uploadImageChunk(offset, data)
        ).accepted

    suspend fun finalizeImage(width: Byte, height: Byte, layer: Byte): Boolean =
        sendCommandAwaitingAck(
            CharacteristicUuids.LED_CMD,
            LedCommands.finalizeImage(width, height, layer)
        ).accepted

    /**
     * Uploads [rgb] using the integrity-checked flow: BEGIN(len, crc32) → chunks → FINALIZE.
     * The firmware verifies length and CRC-32 at finalize and rejects a corrupt
     * image with `BAD_STATE`, which is the answer this waits for.
     *
     * @param onProgress called with 0f..1f after each chunk.
     * @return false if any step failed to reach the device or was not acknowledged.
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
            Log.w(TAG, "uploadImage: BEGIN not acknowledged")
            return false
        }
        var offset = 0
        while (offset < rgb.size) {
            val end = minOf(offset + chunkSize, rgb.size)
            if (!uploadImageChunk(offset, rgb.copyOfRange(offset, end))) {
                Log.w(TAG, "uploadImage: chunk at $offset not acknowledged")
                return false
            }
            offset = end
            onProgress(offset.toFloat() / rgb.size)
        }
        val finalized = finalizeImage(width.toByte(), height.toByte(), layer)
        if (!finalized) Log.w(TAG, "uploadImage: FINALIZE rejected (length or CRC mismatch)")
        return finalized
    }

    // --- System commands ---

    suspend fun setLedMatrix(ledsPerRing: List<Byte>) {
        val maxRings = _deviceState.value.capabilities.maxLedRings
        val rings = ledsPerRing.take(maxRings)
        sendCommand(
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

    /**
     * `0x04` Set the advertised device name (SYS-6).
     *
     * Waits for the verdict rather than updating optimistically: the device
     * refuses an empty or over-long name instead of repairing it, and a name the
     * tail is not actually advertising is worse than no change at all. The
     * caller is expected to have checked the length with
     * [SystemCommands.deviceNameError] first, so a rejection here is news.
     */
    suspend fun setDeviceName(name: String): AckedWrite {
        val outcome = sendCommandAwaitingAck(
            CharacteristicUuids.SYSTEM_CONFIG,
            SystemCommands.setDeviceName(name)
        )
        if (outcome.accepted) scheduleSystemInfoReconcile()
        return outcome
    }

    /**
     * `0x05` Forget one bonded peer by its index in the FF06 bond list, or every
     * bond at [Protocol.BOND_INDEX_ALL].
     *
     * The answer is the point. This app's copy of the list is up to a second old,
     * so an index the device no longer has comes back `OUT_OF_RANGE` — and a
     * caller that assumed success would tell the user they unpaired a phone that
     * is still bonded and can still drive the tail.
     */
    suspend fun forgetBond(index: Byte): AckedWrite {
        val outcome = sendCommandAwaitingAck(
            CharacteristicUuids.SYSTEM_CONFIG,
            SystemCommands.forgetBond(index)
        )
        if (outcome.accepted) scheduleSystemInfoReconcile()
        return outcome
    }

    /** [forgetBond] with [Protocol.BOND_INDEX_ALL] — drops every bond at once. */
    suspend fun forgetAllBonds(): AckedWrite = forgetBond(Protocol.BOND_INDEX_ALL)

    /**
     * The device rebuilds its FF06 read buffer once per second, so an immediate
     * re-read still returns the pre-command name and bond list.
     */
    private fun scheduleSystemInfoReconcile() {
        scope.launch {
            delay(PROFILE_RECONCILE_DELAY_MS)
            refreshSystemInfo()
        }
    }

    // --- Firmware update (SYS-2: FF06 control + FF0E data) ---

    /**
     * Reads the FF0E status echo.
     *
     * The echo is readable as well as notified, for the same reason FF09 is: a
     * notify is best-effort and dropped silently when the device runs out of
     * mbufs, and losing one here would strand a firmware update rather than cost
     * a packet. This is also how the app finds a transfer left armed by a
     * previous session — nothing on either side ages one out.
     */
    suspend fun readOtaStatus(): OtaStatus? {
        val data = transport.readCharacteristic(CharacteristicUuids.OTA_DATA)
        if (data == null) {
            Log.w(TAG, "FF0E read returned null")
            return null
        }
        val parsed = OtaStatusParser.parse(data)
        if (parsed == null) {
            Log.w(TAG, "FF0E parse failed (${data.size} bytes)")
            return null
        }
        _otaStatus.value = parsed
        return parsed
    }

    /** `0x08` BEGIN — arms the transfer. Nothing is erased until the header arrives. */
    suspend fun beginFirmwareUpdate(
        totalLength: Int,
        crc32: Int,
        version: FirmwareVersion
    ): AckedWrite = sendCommandAwaitingAck(
        CharacteristicUuids.SYSTEM_CONFIG,
        OtaCommands.beginUpdate(totalLength, crc32, version)
    )

    /** `0x09` FINALIZE — verify, validate and point the bootloader at the new slot. */
    suspend fun finalizeFirmwareUpdate(): AckedWrite = sendCommandAwaitingAck(
        CharacteristicUuids.SYSTEM_CONFIG,
        OtaCommands.finalizeUpdate()
    )

    /**
     * `0x0A` ABORT — discards an armed transfer, and after a finalize points the
     * bootloader back at the running image.
     *
     * Always answered `OK`, including when nothing is armed, so this is safe to
     * send on any give-up path — and it is the only thing that un-arms a device
     * whose transfer was interrupted.
     */
    suspend fun abortFirmwareUpdate(): AckedWrite = sendCommandAwaitingAck(
        CharacteristicUuids.SYSTEM_CONFIG,
        OtaCommands.abortUpdate()
    )

    /**
     * Installs [image] on the tail: BEGIN, stream on FF0E, FINALIZE.
     *
     * [version] is the app's claim about what it is sending, checked by the
     * device before the slot is erased so that reinstalling the running build
     * costs one packet. Pass the version read out of the image's own descriptor
     * ([com.tailapp.ble.protocol.FirmwareImage]); the device checks that
     * descriptor itself once the header lands, so a wrong claim buys 288 bytes
     * and not an install.
     *
     * @param onProgress called with `(accepted, total)` on every echo that moves
     *   — the *device's* figure, never the app's write cursor, which runs ahead
     *   of what is actually in flash.
     */
    suspend fun uploadFirmware(
        image: ByteArray,
        version: FirmwareVersion,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): FirmwareUpdateResult {
        require(image.isNotEmpty()) { "firmware image must not be empty" }

        // Anything left from a previous transfer would be read as this one's
        // first echo, and its `accepted` would be a resume point into the wrong
        // image.
        drainOtaEchoes()

        val begin = beginFirmwareUpdate(image.size, Crc32.compute(image), version)
        when {
            !begin.written -> return FirmwareUpdateResult.NotWritten(OtaStep.BEGIN)
            begin.result == null -> return FirmwareUpdateResult.Unanswered(OtaStep.BEGIN)
            !begin.result.isSuccess ->
                return FirmwareUpdateResult.Rejected(OtaStep.BEGIN, begin.result.result)
        }

        return streamFirmware(image, startAt = 0, onProgress = onProgress)
    }

    /**
     * Carries on a transfer the device is still holding, from the offset it
     * echoes rather than from wherever this app thought it had got to.
     *
     * That distinction is the entire point of the echo. `accepted` is the number
     * of bytes safely in flash *and* the only offset the device will take next,
     * so a resume that trusted the app's own cursor would send bytes into a gap
     * and have every one of them discarded.
     *
     * @return null when there is nothing to resume — no armed transfer, or one
     *   whose declared length does not match [image].
     */
    suspend fun resumeFirmwareUpdate(
        image: ByteArray,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): FirmwareUpdateResult? {
        drainOtaEchoes()
        val status = readOtaStatus() ?: return null
        if (status.state != OtaTransferState.RECEIVING) return null
        if (status.accepted < 0 || status.accepted > image.size) return null
        onProgress(status.accepted, image.size)
        return streamFirmware(image, startAt = status.accepted, onProgress = onProgress)
    }

    /**
     * Streams FF0E chunks and finalizes, running at most
     * [OtaTransferPolicy.windowBytes] ahead of the last echoed offset.
     *
     * There is no per-chunk acknowledgement to lose and no `BUSY` to hear — FF0E
     * is write-without-response — so the offset echo is the whole recovery
     * mechanism: a chunk at any offset other than `accepted` is discarded and
     * answered with a resume point. Two things follow, and both are load-bearing
     * below. An echo whose `accepted` is unchanged since the previous one means
     * everything sent since was thrown away, so the write cursor rewinds to it;
     * and an echo whose `accepted` has run *past* the cursor means those bytes
     * are already burned into flash, so the cursor jumps forward rather than
     * resending them into a rejection.
     */
    private suspend fun streamFirmware(
        image: ByteArray,
        startAt: Int,
        onProgress: (Int, Int) -> Unit
    ): FirmwareUpdateResult {
        val total = image.size
        val maxPayload = OtaDataFrame.maxPayload(negotiatedMtu.value)

        var accepted = startAt
        var cursor = startAt
        var noProgressEchoes = 0

        // Applies one echo. Returns a result to stop on, or null to carry on.
        fun applyEcho(echo: OtaStatus): FirmwareUpdateResult? {
            if (echo.state == OtaTransferState.ERROR) {
                val rejected =
                    FirmwareUpdateResult.Rejected(OtaStep.TRANSFER, echo.result, echo.state)
                Log.w(TAG, "uploadFirmware: ${rejected.message}")
                return rejected
            }
            val previous = accepted
            accepted = echo.accepted
            if (accepted > cursor || accepted < previous) cursor = accepted
            if (accepted != previous) onProgress(accepted, total)
            return null
        }

        try {
            while (accepted < total) {
                if (_deviceState.value.connectionState != ConnectionState.CONNECTED) {
                    return FirmwareUpdateResult.NotWritten(OtaStep.TRANSFER)
                }

                val pending = otaEchoes.tryReceive().getOrNull()
                if (pending != null) {
                    applyEcho(pending)?.let { return it }
                    continue
                }

                if (cursor < total && cursor - accepted < otaPolicy.windowBytes) {
                    val length = minOf(maxPayload, total - cursor)
                    transport.writeWithoutResponse(
                        CharacteristicUuids.OTA_DATA,
                        OtaDataFrame.build(cursor, image, cursor, length)
                    )
                    cursor += length
                    continue
                }

                // Nothing may be sent until the device speaks: either the window
                // is full or every byte is already on the air.
                val before = accepted
                val echo = withTimeoutOrNull(otaPolicy.echoTimeoutMs) { otaEchoes.receive() }
                    ?: readOtaStatus()
                    ?: return FirmwareUpdateResult.Unanswered(OtaStep.TRANSFER)
                applyEcho(echo)?.let { return it }

                if (accepted == before) {
                    // The device did not move, so everything sent past `accepted`
                    // was discarded. Resume from what it echoed.
                    cursor = accepted
                    if (++noProgressEchoes >= otaPolicy.stallAttempts) {
                        return FirmwareUpdateResult.Stalled(accepted, total)
                    }
                } else {
                    noProgressEchoes = 0
                }
            }

            val finalize = finalizeFirmwareUpdate()
            return when {
                !finalize.written -> FirmwareUpdateResult.NotWritten(OtaStep.FINALIZE)
                finalize.result == null -> FirmwareUpdateResult.Unanswered(OtaStep.FINALIZE)
                finalize.result.isSuccess -> FirmwareUpdateResult.Installed
                // BAD_STATE is two different failures — short, which stays armed
                // and resumable, and a checksum mismatch, which does not. Only
                // the FF0E state tells them apart.
                else -> FirmwareUpdateResult.Rejected(
                    OtaStep.FINALIZE,
                    finalize.result.result,
                    readOtaStatus()?.state
                ).also { Log.w(TAG, "uploadFirmware: ${it.message}") }
            }
        } catch (cancellation: CancellationException) {
            // A cancelled transfer that said nothing would leave the tail armed
            // with a partial image for the rest of its uptime.
            withContext(NonCancellable) { abortFirmwareUpdate() }
            throw cancellation
        }
    }

    private fun drainOtaEchoes() {
        while (otaEchoes.tryReceive().isSuccess) { /* discard */ }
    }

    // --- FFT stream ---

    fun sendFftFrame(loudness: Byte, bins: ByteArray) {
        transport.writeWithoutResponse(
            CharacteristicUuids.FFT_STREAM,
            FftFrameBuilder.build(loudness, bins)
        )
    }

    /**
     * Streams an audio frame with the beat trailer, so the device's own effects
     * and motion patterns can react to the beat.
     *
     * Three extra bytes per frame buy the device something it cannot compute:
     * it has no microphone, so without this the tempo is simply unknown to it.
     * Additive on the wire, so this is safe against firmware that ignores it.
     */
    override fun sendFftFrameWithBeat(
        loudness: Byte,
        bins: ByteArray,
        beatPhase: Float,
        bpm: Float,
        flags: Int
    ) {
        transport.writeWithoutResponse(
            CharacteristicUuids.FFT_STREAM,
            FftFrameBuilder.buildWithBeat(loudness, bins, beatPhase, bpm, flags)
        )
    }

    /**
     * Writes a whole layer stack to the device and optionally saves it to a
     * profile slot, so it runs with nothing connected.
     *
     * Clears the stack first: leaving old layers above the new ones would
     * composite a look nobody designed. `LCMD_REMOVE_LAYER` stamps a slot empty
     * rather than shifting indices, so clearing is per-slot by definition.
     *
     * Every write here waits for its acknowledgement, unlike the single-setting
     * commands. This is the burst the device's eight-deep command queue exists to
     * absorb, and an unacknowledged burst that overruns it loses layers with
     * nothing said; waiting is also the back-pressure that stops it overrunning
     * in the first place. Only an explicit rejection aborts — across ~80 writes a
     * single lost notification is likelier than a real failure.
     *
     * @return true if the stack fits and no write was refused or lost the connection.
     */
    suspend fun installLayerStack(layers: List<LayerConfig>, saveToSlot: Byte? = null): Boolean {
        val maxLayers = _deviceState.value.capabilities.maxLayers
        if (layers.size > maxLayers) return false

        suspend fun install(data: ByteArray): Boolean {
            val outcome = sendCommandAwaitingAck(CharacteristicUuids.LED_CMD, data)
            if (!outcome.written || outcome.rejected) {
                Log.w(
                    TAG,
                    "installLayerStack: cmd 0x%02X ".format(data[0]) +
                        (outcome.result?.result?.name ?: "write failed")
                )
                return false
            }
            return true
        }

        for (slot in 0 until maxLayers) {
            if (!install(LedCommands.removeLayer(slot.toByte()))) return false
        }

        for ((index, layer) in layers.withIndex()) {
            val i = index.toByte()
            if (!install(LedCommands.setLayerEffect(i, layer.effectId, layer.blendMode))) return false
            // Parameters after the effect: the firmware rejects a param write
            // for a slot with no effect, which is the whole point of that check.
            for ((paramId, value) in layer.params.withIndex()) {
                if (!install(LedCommands.setEffectParam(i, paramId.toByte(), value))) return false
            }
            if (layer.flipX || layer.flipY || layer.mirrorX || layer.mirrorY) {
                val transform = LedCommands.setLayerTransform(
                    i, layer.flipX, layer.flipY, layer.mirrorX, layer.mirrorY
                )
                if (!install(transform)) return false
            }
            if (layer.opacity != 255) {
                if (!install(LedCommands.setLayerOpacity(i, layer.opacity))) return false
            }
        }

        refreshLedState()
        if (saveToSlot != null) {
            saveProfile(saveToSlot)
            refreshProfiles()
        }
        return true
    }

    /** Sets the device's output stage: master brightness, gamma, current budget. */
    suspend fun setOutputConfig(brightness: Int, gammaEnabled: Boolean, currentLimitMa: Int) {
        sendCommand(
            CharacteristicUuids.LED_CMD,
            LedCommands.setOutputConfig(brightness, gammaEnabled, currentLimitMa)
        )
        _deviceState.update { state ->
            val led = state.ledState ?: return@update state
            val output = led.output ?: return@update state
            state.copy(
                ledState = led.copy(
                    output = output.copy(
                        brightness = brightness,
                        gammaEnabled = gammaEnabled,
                        currentLimitMa = currentLimitMa
                    )
                )
            )
        }
    }

    /**
     * Streams live motion targets to the device (FF0B).
     *
     * Fire-and-forget like the pixel and audio streams: no acknowledgement, and
     * the device ages the targets out after half a second, so a dropped frame
     * costs nothing and a stopped stream hands the tail back to its own pattern
     * rather than leaving it holding a pose.
     */
    override fun streamMotionTargets(targets: FloatArray) {
        transport.writeWithoutResponse(
            CharacteristicUuids.MOTION_TARGET,
            MotionTargetFrame.build(targets)
        )
    }

    fun setFftStreamActive(active: Boolean) {
        _deviceState.update { it.copy(fftStreamActive = active) }
    }

    // --- Direct pixel streaming (FF0A) ---

    /**
     * `0x09` Set Direct Mode on FF03. Enabled, this bypasses the layer/effect/
     * compositor stack so frames pushed via [streamDirectFrame] are shown as-is;
     * disabled, it resumes normal effect rendering.
     *
     * This is transient session state, not persisted on the device — and the
     * firmware auto-reverts it on disconnect (`app_bridge.cpp::app_led_render`
     * checks the connection state every frame and clears its own direct-mode
     * flag if the app is gone, so the tail never gets stuck on a stale frame).
     * [onDisconnected] mirrors that on this side by resetting
     * [DeviceState.directModeActive] to false too.
     *
     * @return true when the *device* accepted the change, not merely that the
     *   write was queued — every frame streamed afterwards is composited away
     *   unless it did, so "the write went out" was never the useful answer.
     */
    suspend fun setDirectMode(enabled: Boolean): Boolean {
        val outcome = sendCommandAwaitingAck(CharacteristicUuids.LED_CMD, LedCommands.setDirectMode(enabled))
        if (outcome.accepted) {
            _deviceState.update { it.copy(directModeActive = enabled) }
        } else {
            Log.w(TAG, "direct mode $enabled not accepted: ${outcome.result?.result ?: "no answer"}")
        }
        return outcome.accepted
    }

    /**
     * Streams one rendered frame to FF0A, split into
     * [DirectPixelFrame.maxLedsPerPacket]-sized packets at increasing
     * [startIndex] values and sent with [BleTransport.writeWithoutResponse].
     *
     * No suspension and no ACK by design — this is the hot path a beat-reactive
     * renderer calls ~30x/second, and awaiting the mutex-guarded write path
     * (or FF09) here would risk stalling behind command traffic. The firmware
     * doesn't wrap or clamp indices at the strip boundary either
     * (`LedMatrix::write_pixels` simply stops once an index reaches the
     * configured LED count), so a [ledCount] longer than the physical strip is
     * safe to send — the tail is just dropped on the device, not wrapped.
     *
     * @param rgb packed `r,g,b` triplets, e.g. [PixelBuffer.bytes].
     * @param ledCount number of LEDs to stream from [rgb]; defaults to the whole buffer.
     * @param startIndex index of the first LED in the strip that [rgb] represents.
     */
    fun streamDirectFrame(rgb: ByteArray, ledCount: Int = rgb.size / 3, startIndex: Int = 0) {
        val maxPerPacket = DirectPixelFrame.maxLedsPerPacket(negotiatedMtu.value)
        var sent = 0
        while (sent < ledCount) {
            val count = minOf(maxPerPacket, ledCount - sent)
            val packet = DirectPixelFrame.build(
                startIndex = startIndex + sent,
                rgb = rgb,
                offset = sent * 3,
                ledCount = count
            )
            transport.writeWithoutResponse(CharacteristicUuids.LED_DIRECT, packet)
            sent += count
        }
    }

    /** Convenience overload streaming an already-rendered [PixelBuffer]. */
    fun streamDirectFrame(buffer: PixelBuffer, startIndex: Int = 0) =
        streamDirectFrame(buffer.bytes, buffer.ledCount, startIndex)

    // --- Profile commands ---

    // Profile commands stay optimistic: each one reconciles from the device's own
    // FF08 list a second later (or, for a load, from a full re-read), so a
    // rejection corrects itself in the UI without a round trip in the way.

    suspend fun saveProfile(slot: Byte) {
        sendCommand(CharacteristicUuids.PROFILE_MGMT, ProfileCommands.saveProfile(slot))
        updateProfile(slot.toInt()) { it.copy(occupied = true) }
        scheduleProfileReconcile()
    }

    suspend fun loadProfile(slot: Byte) {
        sendCommand(CharacteristicUuids.PROFILE_MGMT, ProfileCommands.loadProfile(slot))
        // The device also raises SYS_EVENT_CONFIG_CHANGED on success; re-read here
        // too so a load still resyncs if the FF07 subscription didn't take.
        refreshAll()
    }

    suspend fun deleteProfile(slot: Byte) {
        sendCommand(CharacteristicUuids.PROFILE_MGMT, ProfileCommands.deleteProfile(slot))
        updateProfile(slot.toInt()) { it.copy(occupied = false, name = null) }
        scheduleProfileReconcile()
    }

    /** `0x05` RENAME — sets the display name stored alongside a profile slot. */
    suspend fun renameProfile(slot: Byte, name: String) {
        sendCommand(
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
