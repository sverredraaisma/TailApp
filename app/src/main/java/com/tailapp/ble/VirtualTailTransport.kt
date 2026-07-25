package com.tailapp.ble

import android.util.Log
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.FirmwareImage
import com.tailapp.ble.protocol.Protocol
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.model.Capabilities
import com.tailapp.model.FirmwareVersion
import com.tailapp.model.LayerConfig
import com.tailapp.model.MotionLimits
import com.tailapp.model.MotionPattern
import com.tailapp.model.OtaTransferState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * A [BleTransport] that pretends to be a tail, for testing the app without one.
 *
 * It holds the state a real device would — the LED matrix, the layer stack,
 * motion, profile slots — serialises it into the exact wire format the firmware
 * publishes when the app reads a characteristic, and applies the app's writes
 * back into that state so a subsequent read (a profile-load resync, a
 * reconnect) is consistent. Every write is acknowledged on FF09 the way the
 * firmware does, so nothing reads as rejected.
 *
 * The point is the previews: with a virtual tail "connected", the LED-config
 * preview and the BeatLight monitor both have a real layout to render, and the
 * whole analysis pipeline runs against the phone's own microphone. The rendered
 * frames go nowhere (there is no strip), which is exactly what makes this a
 * testing aid rather than a device — the on-screen preview *is* the output.
 *
 * Wire formats mirror `main/app_bridge.cpp::app_update_ble_state`; the test
 * `testutil/FirmwarePayloads` builds the same bytes, and the parser tests pin
 * them, so a firmware layout change surfaces there rather than here.
 */
class VirtualTailTransport : BleTransport {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _characteristicUpdate = MutableSharedFlow<CharacteristicUpdate>(extraBufferCapacity = 64)
    override val characteristicUpdate: SharedFlow<CharacteristicUpdate> = _characteristicUpdate.asSharedFlow()

    private val _negotiatedMtu = MutableStateFlow(DEFAULT_MTU)
    override val negotiatedMtu: StateFlow<Int> = _negotiatedMtu.asStateFlow()

    // --- simulated device state ---

    private var ledsPerRing: MutableList<Int> = DEFAULT_MATRIX.toMutableList()
    private val layers: MutableList<LayerConfig> = mutableListOf(defaultRainbowLayer())
    private var directMode = false

    private var patternId = 0x01 // wagging, so the motion screen shows something moving
    private val motionParams = FloatArray(8)

    /** Per-motor open-loop limits, seeded with the firmware profile's defaults. */
    private val motionLimits = MutableList(4) { MotionLimits.FIRMWARE_DEFAULT }
    private var motorsEnabled = true
    private var ackSequence = 0

    /**
     * A plausible mid-charge level. Fixed rather than draining: a simulator that
     * discharged would make the low-battery policy fire during unrelated tests.
     */
    private var batteryPercent = 78

    private var outputBrightness = 255
    private var outputGamma = true
    private var outputLimitMa = 0

    /** Advertised name (SYS-6). Empty means "advertise the firmware default". */
    private var deviceName = ""

    /**
     * Bonded peers, as the FF06 identity block reports them: `[addr_type][addr]`,
     * least-significant byte first the way NimBLE hands them out.
     */
    private val bonds: MutableList<Pair<Int, ByteArray>> = mutableListOf(
        1 to byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
        0 to byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)
    )

    /** The FF07 readable ring, oldest first. */
    private val eventLog: MutableList<Byte> = mutableListOf()

    // --- simulated OTA transfer (mirrors OtaManager) ---
    //
    // A firmware update never reaches a real slot here — there is none — but the
    // whole accept/refuse path is modelled so the app streams against the same
    // rules a device enforces: the length/CRC/version check at BEGIN, the offset
    // echo as the only flow control, and a FINALIZE that refuses a transfer that
    // never completed or whose bytes do not sum to the armed CRC.
    private var otaState = OtaTransferState.IDLE
    private var otaTotal = 0
    private var otaExpectedCrc = 0
    private var otaAccepted = 0
    private var otaLastEcho = 0
    private var otaLastResult = RESULT_OK
    private val otaCrc = CRC32()
    private val otaHeader = ByteArrayOutputStream()

    /** Running image's own version, as the FF06 OTA block reports it. */
    private val otaRunning = intArrayOf(1, 0, 0)

    /** The other slot's version once a finalize stages one, else null. */
    private var otaOther: FirmwareVersion? = null

    /** The staged image's descriptor version, captured when its header validates. */
    private var otaStagedVersion: FirmwareVersion? = null

    private val profiles = arrayOfNulls<ProfileSnapshot>(Protocol.MAX_PROFILE_SLOTS)

    private class ProfileSnapshot(
        val name: String?,
        val ledsPerRing: List<Int>,
        val layers: List<LayerConfig>,
        val patternId: Int,
        val motionParams: FloatArray
    )

    override fun connect(address: String) {
        Log.i(TAG, "virtual tail connecting ($address)")
        _connectionState.value = ConnectionState.CONNECTING
        _connectionState.value = ConnectionState.CONNECTED
    }

    override fun disconnect() {
        // The firmware auto-reverts direct mode on disconnect; mirror that so a
        // reconnect starts from the effect stack, not a frozen direct frame.
        directMode = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun requestMtu(mtu: Int): Int {
        _negotiatedMtu.value = mtu.coerceIn(DEFAULT_MTU, Protocol.MAX_ATT_MTU)
        return _negotiatedMtu.value
    }

    override suspend fun discoverServices(): Boolean = true

    override suspend fun enableNotifications(uuid: UUID): Boolean = true

    override suspend fun readCharacteristic(uuid: UUID): ByteArray? = when (uuid) {
        CharacteristicUuids.MOTION_STATE -> motionStateBytes()
        CharacteristicUuids.LED_STATE -> ledStateBytes()
        CharacteristicUuids.SYSTEM_CONFIG -> systemInfoBytes()
        CharacteristicUuids.PROFILE_MGMT -> profileListBytes()
        CharacteristicUuids.SYSTEM_EVENTS -> eventLogBytes()
        // FF0E is readable as well as notified, for the same reason FF09 is: a
        // dropped echo would strand an update, so the app can read the offset
        // it should resume from.
        CharacteristicUuids.OTA_DATA -> otaStatusBytes()
        // FF0C diagnostics: a fixed, healthy snapshot so the diagnostics screen
        // has something to render against the virtual tail. Fixed, not drifting —
        // a live-changing snapshot would perturb tests that never touch it.
        CharacteristicUuids.DIAGNOSTICS -> diagnosticsBytes()
        // The standard services, so a simulated tail exercises the same code
        // path a real one does rather than only the FF00 custom service.
        CharacteristicUuids.BATTERY_LEVEL -> byteArrayOf(batteryPercent.toByte())
        CharacteristicUuids.DIS_MANUFACTURER -> "TailApp".toByteArray()
        CharacteristicUuids.DIS_MODEL_NUMBER -> "Virtual Tail".toByteArray()
        CharacteristicUuids.DIS_FIRMWARE_REV ->
            "v${Protocol.SUPPORTED_PROTOCOL_VERSION}.0.0-sim".toByteArray()
        CharacteristicUuids.DIS_HARDWARE_REV -> "sim".toByteArray()
        else -> null
    }

    override suspend fun writeCharacteristic(uuid: UUID, data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val result = when (uuid) {
            CharacteristicUuids.MOTION_CMD -> applyMotion(data)
            CharacteristicUuids.LED_CMD -> applyLed(data)
            CharacteristicUuids.SYSTEM_CONFIG -> applySystem(data)
            CharacteristicUuids.PROFILE_MGMT -> applyProfile(data)
            else -> RESULT_OK
        }
        ack(uuid, data[0], result)
        return true
    }

    override fun writeWithoutResponse(uuid: UUID, data: ByteArray) {
        // FF0E carries the image itself, unacknowledged like the streams below;
        // its only answer is the offset echo, emitted from here.
        if (uuid == CharacteristicUuids.OTA_DATA) {
            applyOtaData(data)
            return
        }
        // FF05 (FFT frames) and FF0A (direct pixels) are fire-and-forget with no
        // ACK. There is no strip to push to; the on-screen preview is the output.
    }

    // --- command application ---

    private fun applyMotion(data: ByteArray): Int { val cmd = data[0].toInt() and 0xFF; return when (cmd) {
        0x01 -> { // select pattern — adopts the pattern's own defaults
            if (data.size < 2) return RESULT_BAD_LENGTH
            // An id the device cannot build is rejected, not silently accepted:
            // the firmware answers UNKNOWN_ID and leaves the pattern untouched.
            val pattern = MotionPattern.fromId(data[1]) ?: return RESULT_UNKNOWN_ID
            patternId = data[1].toInt() and 0xFF
            motionParams.fill(0f)
            pattern.params.map { it.default }
                .forEachIndexed { i, v -> if (i in motionParams.indices) motionParams[i] = v }
            // Selecting a pattern is an explicit run intent, so it clears a
            // stall latch — same as the firmware.
            motorsEnabled = true
            RESULT_OK
        }
        0x02 -> { // set pattern parameter
            if (data.size < 6) return RESULT_BAD_LENGTH
            val id = data[1].toInt() and 0xFF
            if (id !in motionParams.indices) return RESULT_OUT_OF_RANGE
            motionParams[id] = data.f32(2)
            RESULT_OK
        }
        0x04 -> RESULT_UNKNOWN_CMD // set PID retired in v6; the id answers UNKNOWN_CMD and is never reused
        0x06 -> { // set axis limits — an inverted window pins the axis to one end
            if (data.size < 10) return RESULT_BAD_LENGTH
            if ((data[1].toInt() and 0xFF) >= MAX_AXES) return RESULT_OUT_OF_RANGE
            if (data.f32(2) >= data.f32(6)) RESULT_OUT_OF_RANGE else RESULT_OK
        }
        0x08 -> { // set motion limits
            if (data.size < 15) return RESULT_BAD_LENGTH
            val id = data[1].toInt() and 0xFF
            if (id !in motionLimits.indices) return RESULT_OUT_OF_RANGE
            motionLimits[id] = MotionLimits(
                maxVelocity = data.f32(2),
                maxAcceleration = data.f32(6),
                maxJerk = data.f32(10),
                stallThreshold = data[14].toInt() and 0xFF
            )
            RESULT_OK
        }
        0x09 -> { // enable/disable motors (clears a stall latch)
            if (data.size < 2) return RESULT_BAD_LENGTH
            motorsEnabled = data[1].toInt() != 0
            RESULT_OK
        }
        0x0A -> { // set motor scale — zero would round every command to a dead motor
            if (data.size < 6) return RESULT_BAD_LENGTH
            if ((data[1].toInt() and 0xFF) >= MAX_SERVOS) return RESULT_OUT_OF_RANGE
            val scale = data.f32(2)
            if (scale < MOTOR_SCALE_MIN || scale > MOTOR_SCALE_MAX) RESULT_OUT_OF_RANGE else RESULT_OK
        }
        0x0B -> { // set gentle scale
            if (data.size < 5) return RESULT_BAD_LENGTH
            val scale = data.f32(1)
            if (scale < GENTLE_SCALE_MIN || scale > GENTLE_SCALE_MAX) RESULT_OUT_OF_RANGE else RESULT_OK
        }
        0x15 -> { // set axis mix — a zero gain makes the mix non-invertible
            if (data.size < 15) return RESULT_BAD_LENGTH
            val rotation = data.f32(1)
            val gainX = data.f32(5)
            val gainY = data.f32(9)
            if (kotlin.math.abs(rotation) > AXIS_MIX_ROTATION_MAX) return RESULT_OUT_OF_RANGE
            if (gainX < AXIS_MIX_GAIN_MIN || gainX > AXIS_MIX_GAIN_MAX ||
                gainY < AXIS_MIX_GAIN_MIN || gainY > AXIS_MIX_GAIN_MAX
            ) RESULT_OUT_OF_RANGE else RESULT_OK
        }
        0x05 -> { motorsEnabled = true; RESULT_OK } // calibrate also clears the latch
        // Known-but-unmodelled commands (servo cfg, tap enable/config, sequence
        // upload, behavior, encoder cfg) are still accepted; an id outside the
        // allocated motion range is UNKNOWN_CMD, the way the firmware answers one.
        else -> if (cmd in MOTION_CMD_RANGE) RESULT_OK else RESULT_UNKNOWN_CMD
    } }

    /**
     * Simulates a stall: motors freewheel and latch off until re-enabled.
     * Exposed so the stall banner and recovery flow can be exercised without
     * physically jamming a tail.
     */
    fun simulateStall() {
        motorsEnabled = false
        emitEvent(SystemEvent.STALL)
    }

    /**
     * Simulates the low-battery policy crossing into [event], the way the
     * firmware announces it — on the crossing only, and without moving the
     * reported percentage.
     *
     * A hook rather than a discharge model on purpose: the pack level here is
     * fixed, because a simulator that drained would engage the policy in the
     * middle of tests that have nothing to do with the battery.
     */
    fun simulateBatteryPolicy(event: SystemEvent) {
        require(event.isBatteryPolicy) { "$event is not a battery policy event" }
        emitEvent(event)
    }

    private fun applyLed(data: ByteArray): Int { val cmd = data[0].toInt() and 0xFF; return when (cmd) {
        0x01 -> { // set layer effect
            if (data.size < 4) return RESULT_BAD_LENGTH
            val index = data[1].toInt() and 0xFF
            if (index >= MAX_LED_LAYERS) return RESULT_OUT_OF_RANGE
            if ((data[2].toInt() and 0xFF) > MAX_EFFECT_ID) return RESULT_UNKNOWN_ID
            if ((data[3].toInt() and 0xFF) > MAX_BLEND_ID) return RESULT_UNKNOWN_ID
            while (layers.size <= index) layers.add(LayerConfig.empty())
            layers[index] = LayerConfig(
                effectId = data[2], blendMode = data[3], enabled = true,
                flipX = false, flipY = false, mirrorX = false, mirrorY = false,
                params = List(8) { 0f }
            )
            RESULT_OK
        }
        0x02 -> { // set effect parameter
            if (data.size < 7) return RESULT_BAD_LENGTH
            val index = data[1].toInt() and 0xFF
            if (index >= MAX_LED_LAYERS) return RESULT_OUT_OF_RANGE
            val paramId = data[2].toInt() and 0xFF
            if (paramId >= 8) return RESULT_OUT_OF_RANGE
            // A param write to an in-range layer that holds no effect is BAD_STATE,
            // not OUT_OF_RANGE: the index is valid, the layer is simply empty. The
            // growable layer list conflated the two before this.
            val layer = layers.getOrNull(index)
            if (layer == null || layer.isEmpty) return RESULT_BAD_STATE
            val params = layer.params.toMutableList()
            params[paramId] = data.f32(3)
            layers[index] = layer.copy(params = params)
            RESULT_OK
        }
        0x03 -> { // remove layer — stamp empty in place, don't shift indices
            if (data.size < 2) return RESULT_BAD_LENGTH
            val index = data[1].toInt() and 0xFF
            if (index >= MAX_LED_LAYERS) return RESULT_OUT_OF_RANGE
            if (index < layers.size) {
                layers[index] = LayerConfig.empty()
                // Trailing empty slots stop being reported, mirroring the
                // firmware's num_layers trim; a populated layer keeps its index.
                while (layers.isNotEmpty() && layers.last().isEmpty) layers.removeAt(layers.size - 1)
            }
            RESULT_OK
        }
        0x04 -> { // set transform
            if (data.size < 6) return RESULT_BAD_LENGTH
            val index = data[1].toInt() and 0xFF
            layers.getOrNull(index)?.let {
                layers[index] = it.copy(
                    flipX = data[2].toInt() != 0, flipY = data[3].toInt() != 0,
                    mirrorX = data[4].toInt() != 0, mirrorY = data[5].toInt() != 0
                )
            } ?: return RESULT_OUT_OF_RANGE
            RESULT_OK
        }
        0x06 -> { // finalize image — creates/updates an Image effect on the target
            if (data.size < 4) return RESULT_BAD_LENGTH
            val index = data[3].toInt() and 0xFF
            while (layers.size <= index) layers.add(LayerConfig.empty())
            layers[index] = LayerConfig(
                effectId = 0x02, blendMode = 0x05, enabled = true,
                flipX = false, flipY = false, mirrorX = false, mirrorY = false,
                params = List(8) { 0f }
            )
            RESULT_OK
        }
        0x07 -> { // set layer enabled
            if (data.size < 3) return RESULT_BAD_LENGTH
            val index = data[1].toInt() and 0xFF
            layers.getOrNull(index)?.let { layers[index] = it.copy(enabled = data[2].toInt() != 0) }
                ?: return RESULT_OUT_OF_RANGE
            RESULT_OK
        }
        0x09 -> { // set direct mode
            if (data.size < 2) return RESULT_BAD_LENGTH
            directMode = data[1].toInt() != 0
            RESULT_OK
        }
        0x0A -> { // per-layer opacity
            if (data.size < 3) return RESULT_BAD_LENGTH
            val index = data[1].toInt() and 0xFF
            layers.getOrNull(index)?.let {
                layers[index] = it.copy(opacity = data[2].toInt() and 0xFF)
            } ?: return RESULT_OUT_OF_RANGE
            RESULT_OK
        }
        0x0B -> { // output stage
            if (data.size < 5) return RESULT_BAD_LENGTH
            outputBrightness = data[1].toInt() and 0xFF
            outputGamma = data[2].toInt() != 0
            outputLimitMa = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8)
            RESULT_OK
        }
        0x0C -> { // set frame rate — out of range is rejected, not clamped
            if (data.size < 2) return RESULT_BAD_LENGTH
            val fps = data[1].toInt() and 0xFF
            if (fps < LED_FRAME_RATE_MIN || fps > LED_FRAME_RATE_MAX) RESULT_OUT_OF_RANGE else RESULT_OK
        }
        0x05, 0x08 -> RESULT_OK // image chunk / begin: accepted, no CRC to verify here
        // Known-but-unmodelled commands (animation upload/finalize) are accepted;
        // an id outside the allocated LED range is UNKNOWN_CMD.
        else -> if (cmd in LED_CMD_RANGE) RESULT_OK else RESULT_UNKNOWN_CMD
    } }

    private fun applySystem(data: ByteArray): Int { val cmd = data[0].toInt() and 0xFF; return when (cmd) {
        0x01 -> { // set LED matrix
            val count = data[1].toInt() and 0xFF
            if (data.size < 2 + count) return RESULT_BAD_LENGTH
            ledsPerRing = (0 until count).map { data[2 + it].toInt() and 0xFF }.toMutableList()
            RESULT_OK
        }
        0x04 -> { // set device name — refused, never truncated, like the firmware
            if (data.size < 2) return RESULT_BAD_LENGTH
            if (data.size - 1 > Protocol.MAX_DEVICE_NAME_LEN) return RESULT_OUT_OF_RANGE
            deviceName = String(data, 1, data.size - 1, Charsets.UTF_8)
            RESULT_OK
        }
        0x05 -> { // forget bond
            if (data.size < 2) return RESULT_BAD_LENGTH
            val index = data[1]
            if (index == Protocol.BOND_INDEX_ALL) {
                bonds.clear()
                return RESULT_OK
            }
            // An index the device does not have is a rejection, not a no-op: the
            // app's copy of the list is up to a second old, and a false success
            // would report a phone as unpaired while it is still bonded.
            val slot = index.toInt() and 0xFF
            if (slot !in bonds.indices) return RESULT_OUT_OF_RANGE
            bonds.removeAt(slot)
            RESULT_OK
        }
        // OTA control (SYS-2). The image data rides FF0E; these three arm, install
        // and cancel it, and are acknowledged on FF09 like every other command.
        0x08 -> applyOtaBegin(data)
        0x09 -> applyOtaFinalize()
        0x0A -> applyOtaAbort()
        // get-info / get-caps / list-bonds / select-descriptors are no-ops (their
        // blocks are always in the FF06 read); an id outside the allocated system
        // range is UNKNOWN_CMD.
        else -> if (cmd in SYSTEM_CMD_RANGE) RESULT_OK else RESULT_UNKNOWN_CMD
    } }

    private fun applyProfile(data: ByteArray): Int {
        val slot = if (data.size >= 2) data[1].toInt() and 0xFF else -1
        return when (data[0].toInt()) {
            0x01 -> { // save
                if (slot !in profiles.indices) return RESULT_OUT_OF_RANGE
                profiles[slot] = ProfileSnapshot(
                    name = profiles[slot]?.name,
                    ledsPerRing = ledsPerRing.toList(),
                    layers = layers.toList(),
                    patternId = patternId,
                    motionParams = motionParams.copyOf()
                )
                RESULT_OK
            }
            0x02 -> { // load — replaces the whole config, like the firmware
                val snapshot = profiles.getOrNull(slot) ?: return RESULT_BAD_STATE
                ledsPerRing = snapshot.ledsPerRing.toMutableList()
                layers.clear(); layers.addAll(snapshot.layers)
                patternId = snapshot.patternId
                snapshot.motionParams.copyInto(motionParams)
                // The device raises CONFIG_CHANGED after a load; the app re-reads
                // FF02/FF04/FF06 on it, so the whole UI resyncs to the new config.
                emitEvent(SystemEvent.CONFIG_CHANGED)
                RESULT_OK
            }
            0x04 -> { // delete
                if (slot !in profiles.indices) return RESULT_OUT_OF_RANGE
                profiles[slot] = null
                RESULT_OK
            }
            0x05 -> { // rename
                if (slot !in profiles.indices) return RESULT_OUT_OF_RANGE
                val name = String(data, 2, data.size - 2, Charsets.UTF_8).ifBlank { null }
                val existing = profiles[slot]
                profiles[slot] = ProfileSnapshot(
                    name = name,
                    ledsPerRing = existing?.ledsPerRing ?: ledsPerRing.toList(),
                    layers = existing?.layers ?: layers.toList(),
                    patternId = existing?.patternId ?: patternId,
                    motionParams = existing?.motionParams ?: motionParams.copyOf()
                )
                RESULT_OK
            }
            // list-profiles is a no-op (the list is always in the FF08 read); an
            // id outside the allocated profile range is UNKNOWN_CMD.
            else -> if ((data[0].toInt() and 0xFF) in PROFILE_CMD_RANGE) RESULT_OK else RESULT_UNKNOWN_CMD
        }
    }

    // --- OTA transfer (mirrors OtaManager) ---

    /**
     * `0x08` BEGIN. The length/CRC/version claim is checked in the firmware's
     * order, before any slot is opened, so a doomed image is refused for the
     * first rule it breaks rather than after a transfer.
     */
    private fun applyOtaBegin(data: ByteArray): Int {
        if (data.size < 12) return RESULT_BAD_LENGTH
        val total = readU32(data, 1)
        val expectedCrc = readU32(data, 5)
        val major = data[9].toInt() and 0xFF
        val minor = data[10].toInt() and 0xFF
        val patch = data[11].toInt() and 0xFF

        if (total < Protocol.OTA_IMAGE_HEADER_BYTES) return otaFail(RESULT_OTA_BAD_IMAGE)
        if (total > Protocol.OTA_SLOT_BYTES) return otaFail(RESULT_OUT_OF_RANGE)
        // The claimed version is checked before the slot is erased so a pointless
        // reinstall costs one packet; the descriptor is checked again once the
        // header lands, which is what catches a claim that was wrong.
        if (major == otaRunning[0] && minor == otaRunning[1] && patch == otaRunning[2]) {
            return otaFail(RESULT_OTA_SAME_VERSION)
        }

        otaState = OtaTransferState.RECEIVING
        otaTotal = total
        otaExpectedCrc = expectedCrc
        otaAccepted = 0
        otaLastEcho = 0
        otaLastResult = RESULT_OK
        otaCrc.reset()
        otaHeader.reset()
        otaStagedVersion = null
        emitOtaStatus()
        return RESULT_OK
    }

    /**
     * FF0E image data: `[offset u32 LE][bytes]`. A chunk at any offset other than
     * the one echoed, or one that overruns the declared length, is discarded and
     * answered with the resume point — the offset echo is the whole flow control.
     */
    private fun applyOtaData(data: ByteArray) {
        if (data.size < 4) return
        val offset = readU32(data, 0)
        val len = data.size - 4

        if (otaState != OtaTransferState.RECEIVING) {
            // The tail of an aborted or finished transfer still in flight. Echo so
            // the sender stops rather than emptying its queue into a dead transfer.
            emitOtaStatus()
            return
        }
        if (len == 0) return
        if (offset != otaAccepted || otaAccepted.toLong() + len > otaTotal) {
            emitOtaStatus()
            return
        }

        // Buffer the header until the accept/refuse decision can be made from it.
        // Nothing is "written" here, but the CRC covers every byte including the
        // header, exactly as the firmware sums it.
        if (otaHeader.size() < Protocol.OTA_IMAGE_HEADER_BYTES) {
            val room = Protocol.OTA_IMAGE_HEADER_BYTES - otaHeader.size()
            otaHeader.write(data, 4, minOf(room, len))
            if (otaHeader.size() >= Protocol.OTA_IMAGE_HEADER_BYTES) {
                val verdict = validateOtaHeader()
                if (verdict != RESULT_OK) {
                    otaFail(verdict)
                    return
                }
            }
        }

        otaCrc.update(data, 4, len)
        otaAccepted += len
        if (otaAccepted - otaLastEcho >= Protocol.OTA_ECHO_BYTES || otaAccepted == otaTotal) {
            emitOtaStatus()
        }
    }

    /**
     * `0x09` FINALIZE. Refuses a transfer that never completed (short, and left
     * armed so it can resume) or whose bytes do not sum to the armed CRC (corrupt,
     * and destroyed). Only a full, matching image is marked bootable.
     */
    private fun applyOtaFinalize(): Int {
        if (otaState != OtaTransferState.RECEIVING) {
            otaLastResult = RESULT_BAD_STATE
            emitOtaStatus()
            return RESULT_BAD_STATE
        }
        if (otaAccepted != otaTotal) {
            // Short, not corrupt: every byte is one the app sent, so the transfer
            // stays RECEIVING and resumable and the echo names where to carry on.
            otaLastResult = RESULT_BAD_STATE
            emitOtaStatus()
            return RESULT_BAD_STATE
        }
        if (otaCrc.value.toInt() != otaExpectedCrc) {
            return otaFail(RESULT_BAD_STATE)
        }

        otaState = OtaTransferState.READY
        otaLastResult = RESULT_OK
        // The staged image now occupies the other slot; a reset would run it. The
        // running version does not change until that reset, which the simulator
        // cannot perform — so pending_verify stays 0 here.
        otaOther = otaStagedVersion ?: FirmwareVersion(otaRunning[0], otaRunning[1], otaRunning[2])
        emitOtaStatus()
        return RESULT_OK
    }

    /** `0x0A` ABORT. Discards an in-flight transfer; always `OK`, even with nothing armed. */
    private fun applyOtaAbort(): Int {
        otaState = OtaTransferState.IDLE
        otaTotal = 0
        otaAccepted = 0
        otaLastEcho = 0
        otaLastResult = RESULT_OK
        otaCrc.reset()
        otaHeader.reset()
        otaStagedVersion = null
        emitOtaStatus()
        return RESULT_OK
    }

    /**
     * Reads the buffered header with the same offsets the device uses, and turns
     * the three checks it makes into their result codes: not an application
     * image, somebody else's project, the version already running.
     */
    private fun validateOtaHeader(): Int {
        val info = FirmwareImage.describe(otaHeader.toByteArray()) ?: return RESULT_OTA_BAD_IMAGE
        if (info.projectName.isNotEmpty() && info.projectName != FirmwareImage.EXPECTED_PROJECT) {
            return RESULT_OTA_WRONG_PROJECT
        }
        val version = info.version
        if (version != null &&
            version.major == otaRunning[0] &&
            version.minor == otaRunning[1] &&
            version.patch == otaRunning[2]
        ) {
            return RESULT_OTA_SAME_VERSION
        }
        otaStagedVersion = version
        return RESULT_OK
    }

    private fun otaFail(result: Int): Int {
        otaState = OtaTransferState.ERROR
        otaLastResult = result
        otaAccepted = 0
        otaCrc.reset()
        otaHeader.reset()
        otaStagedVersion = null
        emitOtaStatus()
        return result
    }

    private fun emitOtaStatus() {
        otaLastEcho = otaAccepted
        emit(CharacteristicUuids.OTA_DATA, otaStatusBytes())
    }

    /** FF0E status echo: `[accepted u32 LE][state u8][result u8]`. */
    private fun otaStatusBytes(): ByteArray = Writer().apply {
        u8(otaAccepted and 0xFF)
        u8((otaAccepted shr 8) and 0xFF)
        u8((otaAccepted shr 16) and 0xFF)
        u8((otaAccepted shr 24) and 0xFF)
        u8(otaState.code)
        u8(otaLastResult)
    }.toByteArray()

    private fun readU32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    // --- serialisation (mirrors FirmwarePayloads) ---

    private fun motionStateBytes(): ByteArray = Writer().apply {
        u8(patternId)
        repeat(8) { f32(motionParams[it]) }
        repeat(4) { f32(0f) }          // encoder positions
        f32(0f).f32(0f).f32(1f)        // gravity, resting flat
        f32(-90f).f32(90f)             // x limits
        f32(-45f).f32(45f)             // y limits
        // Behavior block (MOT-6) then logical block (MOT-0), appended so the
        // simulator emits the current firmware's 97-byte FF02 rather than the
        // pre-behavior 77-byte layout. The behavior engine is not modelled:
        // engine off, no reason, no flags, not the pattern on the motors. Logical
        // positions equal the physical ones under the identity mix, which is what
        // the simulator reports.
        u8(0xFF).u8(0x00).u8(0x00).u8(0xFF)
        repeat(4) { f32(0f) }          // logical positions
    }.toByteArray()

    private fun ledStateBytes(): ByteArray = Writer().apply {
        u8(ledsPerRing.size)
        ledsPerRing.forEach { u8(it) }
        u8(layers.size)
        layers.forEach { layer ->
            u8(layer.effectId.toInt())
            u8(layer.blendMode.toInt())
            bool(layer.enabled)
            bool(layer.flipX); bool(layer.flipY)
            bool(layer.mirrorX); bool(layer.mirrorY)
            u8(layer.opacity)
            repeat(8) { f32(layer.params.getOrElse(it) { 0f }) }
        }
        // Output stage (protocol v5). No strip to drive, so the power limiter
        // never engages: last_power_scale is always 255 here.
        u8(outputBrightness)
        bool(outputGamma)
        u8(outputLimitMa and 0xFF)
        u8((outputLimitMa shr 8) and 0xFF)
        u8(255)
    }.toByteArray()

    private fun systemInfoBytes(): ByteArray = Writer().apply {
        val caps = Capabilities.DEFAULT
        // Preamble (never framed). The servo record is the assignment only since
        // v6 — the PID gains that used to follow it are retired.
        u8(Protocol.SUPPORTED_PROTOCOL_VERSION)
        u8(1).u8(0).u8(0)              // firmware version, arbitrary
        u8(4)                          // num servos
        repeat(4) { i ->
            u8(if (i < 2) 0 else 1)    // axis
            u8(i % 2)                  // half
            bool(false)               // invert
            u8(i)                      // mux channel
        }
        u8(2)                          // num IMUs
        u8(4).bool(false)
        u8(5).bool(false)
        // Framed blocks: each is [tag][len u16 LE][payload]. Order is not part of
        // the contract, so the natural declaration order is emitted here and the
        // app finds each block by tag; an app that skips one it does not model
        // still lands on the next.
        block(FF06_BLK_CAPABILITIES) {
            u8(caps.patternIds.size); caps.patternIds.forEach { u8(it.toInt()) }
            u8(caps.effectIds.size); caps.effectIds.forEach { u8(it.toInt()) }
            u8(caps.blendModeIds.size); caps.blendModeIds.forEach { u8(it.toInt()) }
            u8(caps.maxLayers)
            u8(caps.maxServos)
            u8(caps.maxImus)
            u8(caps.maxLedRings)
            u8(caps.imageMaxDim)
        }
        // Motion block: live motor state + per-motor open-loop limits. Reporting
        // the profile's real defaults rather than zeros, the way the firmware does.
        block(FF06_BLK_MOTION) {
            bool(motorsEnabled)
            motionLimits.forEach { lim ->
                f32(lim.maxVelocity).f32(lim.maxAcceleration).f32(lim.maxJerk)
                u8(lim.stallThreshold)
            }
        }
        block(FF06_BLK_TUNING) {
            repeat(4) { f32(1f) }      // units per deg/s
            f32(0.5f)                  // gentle scale
            u8(0).u8(0)                // keyframe slot, sequence occupancy
        }
        // OTA version/rollback (SYS-2). The other slot fills in once a finalize
        // stages an image, which is how "installed — restart to apply" is shown
        // without the app having to remember it just uploaded something.
        block(FF06_BLK_OTA) {
            u8(otaRunning[0]).u8(otaRunning[1]).u8(otaRunning[2])
            u8(0)                      // pending verify: no reboot in the simulator
            val other = otaOther
            bool(other != null)
            u8(other?.major ?: 0).u8(other?.minor ?: 0).u8(other?.patch ?: 0)
        }
        block(FF06_BLK_TAP) {
            repeat(2) { u8(1).u8(40).u8(2).u8(50).u8(0) } // engine, thresh, sens, quiet ms
        }
        block(FF06_BLK_AXIS_MIX) {
            f32(0f).f32(1f).f32(1f).u8(0).u8(0)           // identity mix
        }
        // Identity block (SYS-6): the advertised name, then the bond list.
        block(FF06_BLK_IDENTITY) {
            val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
            u8(nameBytes.size)
            bytes(nameBytes)
            u8(bonds.size)
            bonds.forEach { (type, address) -> u8(type).bytes(address) }
        }
    }.toByteArray()

    /**
     * FF07 read: `[count][event]...`, oldest first. A notify is best-effort, so
     * the device also keeps a small readable ring — which is how an app that
     * connects after the fact finds out the pack went critical.
     */
    private fun eventLogBytes(): ByteArray = Writer().apply {
        u8(eventLog.size)
        eventLog.forEach { u8(it.toInt()) }
    }.toByteArray()

    /**
     * FF0C diagnostics (format_version 3, 96 bytes), mirroring
     * `app_bridge.cpp::build_diagnostics`. A fixed, healthy device: no stalls, no
     * overruns, every sensor answering, driver faults clear. The battery figures
     * match the FF06/0x2A19 reads so the simulated tail tells one story.
     */
    private fun diagnosticsBytes(): ByteArray = Writer().apply {
        u8(DIAG_FORMAT_VERSION)
        u32(3661L)                     // uptime: 1h 1m 1s
        u32(120_000L)                  // free heap
        u32(90_000L)                   // min free heap
        u32(0L)                        // stall count
        u32(0L)                        // command queue dropped
        u32(0L)                        // motion overruns
        u32(0L)                        // render overruns
        u32(0L)                        // frames skipped
        u32(8_000L)                    // last frame us
        u32(8_200L)                    // mean frame us
        u32(15_000L)                   // max frame us
        u8(30)                         // frame rate hz
        val stacks = listOf(512, 480, 600, 320, 700)
        u8(stacks.size)
        stacks.forEach { u16(it) }
        u8(2)                          // num IMUs, base then tip
        repeat(2) { u16(0).u8(0) }     // healthy: no failures, not disabled
        u8(batteryPercent)
        u16(3900)                      // pack millivolts
        u8(1)                          // battery level: normal
        // format_version 2: encoder health.
        u8(4)                          // num encoders
        repeat(4) { u16(0).u8(0) }     // healthy
        u16(0)                         // rehome count
        // format_version 3: TMC2209 driver health.
        u8(4)                          // num motors
        repeat(4) { u8(0) }            // no DRV_STATUS faults
        u32(0L)                        // driver lost writes
        u32(0L)                        // driver read failures
    }.toByteArray()

    private fun emitEvent(event: SystemEvent) {
        if (eventLog.size >= EVENT_LOG_MAX) eventLog.removeAt(0)
        eventLog.add(event.code)
        emit(CharacteristicUuids.SYSTEM_EVENTS, byteArrayOf(event.code))
    }

    private fun profileListBytes(): ByteArray = Writer().apply {
        profiles.forEach { snapshot ->
            val nameBytes = snapshot?.name?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            bool(snapshot != null)
            u8(nameBytes.size)
            bytes(nameBytes)
        }
    }.toByteArray()

    // Protocol v5 acknowledgement: the trailing sequence byte is what lets the
    // app tell two identical in-flight commands apart.
    private fun ack(uuid: UUID, commandId: Byte, result: Int) {
        val seq = ackSequence
        ackSequence = (ackSequence + 1) and 0xFF
        emit(
            CharacteristicUuids.CMD_RESULT,
            byteArrayOf(
                CharacteristicUuids.shortId(uuid), commandId, result.toByte(), seq.toByte()
            )
        )
    }

    private fun emit(uuid: UUID, value: ByteArray) {
        _characteristicUpdate.tryEmit(CharacteristicUpdate(uuid, value))
    }

    private fun ByteArray.f32(offset: Int): Float =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float

    private class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(value: Int) = apply { out.write(value and 0xFF) }
        fun u16(value: Int) = apply { u8(value); u8(value shr 8) }
        fun u32(value: Long) = apply { u8((value and 0xFF).toInt()); u8((value shr 8).toInt()); u8((value shr 16).toInt()); u8((value shr 24).toInt()) }
        fun bool(value: Boolean) = u8(if (value) 1 else 0)
        fun f32(value: Float) = apply {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        }
        fun bytes(value: ByteArray) = apply { out.write(value) }

        /**
         * Writes a v6 FF06 framed block: `[tag][len u16 LE][payload]`. The
         * payload is built into its own buffer so its length is known before the
         * prefix is written — the reader locates the block by tag and skips one
         * it does not know by this length.
         */
        fun block(tag: Int, build: Writer.() -> Unit) = apply {
            val payload = Writer().apply(build).toByteArray()
            u8(tag).u16(payload.size).bytes(payload)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    companion object {
        /** Address the router recognises as "use the virtual tail". */
        const val ADDRESS = "virtual:tail"

        /** Name shown for it in the scan list. */
        const val DISPLAY_NAME = "Virtual tail (testing)"

        fun isVirtualAddress(address: String): Boolean = address == ADDRESS

        private const val TAG = "VirtualTail"
        private const val DEFAULT_MTU = 247

        /** `EVENT_LOG_MAX` — how many events the firmware's readable ring holds. */
        private const val EVENT_LOG_MAX = 16

        /** `DIAGNOSTICS_VERSION` — the FF0C format the simulated tail publishes. */
        private const val DIAG_FORMAT_VERSION = 3

        // FF06_BLK_* tags — the v6 framing prefix for each trailing block, from
        // TailFirmware `main/ble/ble_protocol.h`.
        private const val FF06_BLK_CAPABILITIES = 0x01
        private const val FF06_BLK_MOTION = 0x02
        private const val FF06_BLK_TUNING = 0x03
        private const val FF06_BLK_OTA = 0x04
        private const val FF06_BLK_TAP = 0x05
        private const val FF06_BLK_AXIS_MIX = 0x06
        private const val FF06_BLK_IDENTITY = 0x07

        /** 48 LEDs across five rings — a plausible tail, and what the tests use. */
        private val DEFAULT_MATRIX = listOf(8, 10, 12, 10, 8)

        private const val RESULT_OK = 0x00
        private const val RESULT_BAD_LENGTH = 0x01
        private const val RESULT_UNKNOWN_CMD = 0x02
        private const val RESULT_UNKNOWN_ID = 0x03
        private const val RESULT_OUT_OF_RANGE = 0x04
        private const val RESULT_BAD_STATE = 0x05

        // The four OTA rejections (FF09 0x07-0x0A). Distinct codes because each is
        // a different cause with a different thing for the user to do about it.
        private const val RESULT_OTA_BAD_IMAGE = 0x07
        private const val RESULT_OTA_WRONG_PROJECT = 0x08
        private const val RESULT_OTA_SAME_VERSION = 0x09

        // Bounds the simulator validates a command against, so an ACK reports the
        // same accept/reject the firmware would. All mirror the firmware's own
        // limits (config_types.h / ble_protocol.h); a value here that disagreed
        // would be a conformance divergence rather than a fix for one.
        private const val MAX_SERVOS = 4
        private const val MAX_AXES = 2
        private const val MAX_LED_LAYERS = 8

        // The device's effect/blend catalogue range. Effects are checked against
        // the *firmware's* range (0..EFFECT_ANIMATION), not the app's LedEffect
        // enum, which still lags the firmware's LED-3 additions - validating
        // against the enum would reject effect ids a real device accepts.
        private const val MAX_EFFECT_ID = 0x11
        private const val MAX_BLEND_ID = 0x06 // BLEND_NORMAL

        private const val LED_FRAME_RATE_MIN = 5
        private const val LED_FRAME_RATE_MAX = 60
        private const val MOTOR_SCALE_MIN = 0.001f
        private const val MOTOR_SCALE_MAX = 100.0f
        private const val GENTLE_SCALE_MIN = 0.05f
        private const val GENTLE_SCALE_MAX = 1.0f
        private const val AXIS_MIX_ROTATION_MAX = 180.0f
        private const val AXIS_MIX_GAIN_MIN = 0.05f
        private const val AXIS_MIX_GAIN_MAX = 20.0f

        // Allocated command-id ranges per write characteristic. An id inside the
        // range that the simulator does not model is still a real command and is
        // accepted; an id outside it is answered UNKNOWN_CMD, as the firmware does.
        private val MOTION_CMD_RANGE = 0x01..0x16
        private val LED_CMD_RANGE = 0x01..0x0F
        private val SYSTEM_CMD_RANGE = 0x01..0x0A
        private val PROFILE_CMD_RANGE = 0x01..0x05

        private fun defaultRainbowLayer() = LayerConfig(
            effectId = 0x00, // Rainbow
            blendMode = 0x05, // Overwrite
            enabled = true,
            flipX = false, flipY = false, mirrorX = false, mirrorY = false,
            // direction 0, speed 60 deg/s, scale 1.0 — the firmware defaults
            params = listOf(0f, 60f, 1f, 0f, 0f, 0f, 0f, 0f)
        )
    }
}
