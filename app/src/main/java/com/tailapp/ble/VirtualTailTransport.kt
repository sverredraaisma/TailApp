package com.tailapp.ble

import android.util.Log
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.Protocol
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.model.Capabilities
import com.tailapp.model.LayerConfig
import com.tailapp.model.MotionLimits
import com.tailapp.model.MotionPattern
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
        // FF05 (FFT frames) and FF0A (direct pixels) are fire-and-forget with no
        // ACK. There is no strip to push to; the on-screen preview is the output.
    }

    // --- command application ---

    private fun applyMotion(data: ByteArray): Int { return when (data[0].toInt()) {
        0x01 -> { // select pattern — adopts the pattern's own defaults
            if (data.size < 2) return RESULT_BAD_LENGTH
            patternId = data[1].toInt() and 0xFF
            val defaults = MotionPattern.fromId(data[1])?.params?.map { it.default }
            motionParams.fill(0f)
            defaults?.forEachIndexed { i, v -> if (i in motionParams.indices) motionParams[i] = v }
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
        0x06 -> { // set axis limits — an inverted window pins the axis to one end
            if (data.size < 10) return RESULT_BAD_LENGTH
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
        0x05 -> { motorsEnabled = true; RESULT_OK } // calibrate also clears the latch
        else -> RESULT_OK // PID, tap: accepted, not modelled
    } }

    /**
     * Simulates a stall: motors freewheel and latch off until re-enabled.
     * Exposed so the stall banner and recovery flow can be exercised without
     * physically jamming a tail.
     */
    fun simulateStall() {
        motorsEnabled = false
        emit(CharacteristicUuids.SYSTEM_EVENTS, byteArrayOf(SystemEvent.STALL.code))
    }

    private fun applyLed(data: ByteArray): Int { return when (data[0].toInt()) {
        0x01 -> { // set layer effect
            if (data.size < 4) return RESULT_BAD_LENGTH
            val index = data[1].toInt() and 0xFF
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
            val paramId = data[2].toInt() and 0xFF
            layers.getOrNull(index)?.let { layer ->
                val params = layer.params.toMutableList()
                if (paramId in params.indices) params[paramId] = data.f32(3)
                layers[index] = layer.copy(params = params)
            } ?: return RESULT_OUT_OF_RANGE
            RESULT_OK
        }
        0x03 -> { // remove layer — stamp empty in place, don't shift indices
            val index = data[1].toInt() and 0xFF
            if (index !in layers.indices) return RESULT_OUT_OF_RANGE
            layers[index] = LayerConfig.empty()
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
        0x05, 0x08 -> RESULT_OK // image chunk / begin: accepted, no CRC to verify here
        else -> RESULT_OK
    } }

    private fun applySystem(data: ByteArray): Int { return when (data[0].toInt()) {
        0x01 -> { // set LED matrix
            val count = data[1].toInt() and 0xFF
            if (data.size < 2 + count) return RESULT_BAD_LENGTH
            ledsPerRing = (0 until count).map { data[2 + it].toInt() and 0xFF }.toMutableList()
            RESULT_OK
        }
        else -> RESULT_OK // get-info / get-caps are no-ops; the block is always in the read
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
                emit(CharacteristicUuids.SYSTEM_EVENTS, byteArrayOf(0x03))
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
            else -> RESULT_OK
        }
    }

    // --- serialisation (mirrors FirmwarePayloads) ---

    private fun motionStateBytes(): ByteArray = Writer().apply {
        u8(patternId)
        repeat(8) { f32(motionParams[it]) }
        repeat(4) { f32(0f) }          // encoder positions
        f32(0f).f32(0f).f32(1f)        // gravity, resting flat
        f32(-90f).f32(90f)             // x limits
        f32(-45f).f32(45f)             // y limits
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
            repeat(8) { f32(layer.params.getOrElse(it) { 0f }) }
        }
    }.toByteArray()

    private fun systemInfoBytes(): ByteArray = Writer().apply {
        val caps = Capabilities.DEFAULT
        u8(Protocol.SUPPORTED_PROTOCOL_VERSION)
        u8(1).u8(0).u8(0)              // firmware version, arbitrary
        u8(4)                          // num servos
        repeat(4) { i ->
            u8(if (i < 2) 0 else 1)    // axis
            u8(i % 2)                  // half
            bool(false)               // invert
            u8(i)                      // mux channel
            f32(1f).f32(0f).f32(0f)    // PID
        }
        u8(2)                          // num IMUs
        u8(4).bool(false)
        u8(5).bool(false)
        u8(caps.patternIds.size); caps.patternIds.forEach { u8(it.toInt()) }
        u8(caps.effectIds.size); caps.effectIds.forEach { u8(it.toInt()) }
        u8(caps.blendModeIds.size); caps.blendModeIds.forEach { u8(it.toInt()) }
        u8(caps.maxLayers)
        u8(caps.maxServos)
        u8(caps.maxImus)
        u8(caps.maxLedRings)
        u8(caps.imageMaxDim)
        // Motion block (protocol v4): live motor state + per-motor open-loop
        // limits. Reporting the profile's real defaults rather than zeros, the
        // way the firmware does.
        bool(motorsEnabled)
        motionLimits.forEach { lim ->
            f32(lim.maxVelocity).f32(lim.maxAcceleration).f32(lim.maxJerk)
            u8(lim.stallThreshold)
        }
    }.toByteArray()

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
        fun bool(value: Boolean) = u8(if (value) 1 else 0)
        fun f32(value: Float) = apply {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        }
        fun bytes(value: ByteArray) = apply { out.write(value) }
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

        /** 48 LEDs across five rings — a plausible tail, and what the tests use. */
        private val DEFAULT_MATRIX = listOf(8, 10, 12, 10, 8)

        private const val RESULT_OK = 0x00
        private const val RESULT_BAD_LENGTH = 0x01
        private const val RESULT_OUT_OF_RANGE = 0x04
        private const val RESULT_BAD_STATE = 0x05

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
