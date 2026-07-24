package com.tailapp.model

import com.tailapp.ble.protocol.Protocol
import com.tailapp.testutil.FirmwarePayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enum ids here are the wire contract — they must line up with the `#define`
 * blocks in TailFirmware `main/ble/ble_protocol.h`.
 */
class ProtocolEnumTest {

    @Test
    fun `effect ids match the firmware`() {
        assertEquals(0x00.toByte(), LedEffect.RAINBOW.id)
        assertEquals(0x01.toByte(), LedEffect.STATIC_COLOR.id)
        assertEquals(0x02.toByte(), LedEffect.IMAGE.id)
        assertEquals(0x03.toByte(), LedEffect.AUDIO_POWER.id)
        assertEquals(0x04.toByte(), LedEffect.AUDIO_BAR.id)
        assertEquals(0x05.toByte(), LedEffect.AUDIO_FREQ_BARS.id)
    }

    @Test
    fun `blend mode ids match the firmware`() {
        assertEquals(0x00.toByte(), BlendMode.MULTIPLY.id)
        assertEquals(0x05.toByte(), BlendMode.OVERWRITE.id)
        // Normal was appended rather than inserted: the ids are persisted in
        // saved device configs, so renumbering would silently repaint every
        // stored layer with a different blend.
        assertEquals(0x06.toByte(), BlendMode.NORMAL.id)
        assertEquals(7, BlendMode.entries.size)
    }

    @Test
    fun `pattern ids match the firmware`() {
        assertEquals(0x00.toByte(), MotionPattern.STATIC.id)
        assertEquals(0x01.toByte(), MotionPattern.WAGGING.id)
        assertEquals(0x02.toByte(), MotionPattern.LOOSE.id)
    }

    @Test
    fun `ids are unique within each enum`() {
        assertEquals(LedEffect.entries.size, LedEffect.entries.map { it.id }.distinct().size)
        assertEquals(BlendMode.entries.size, BlendMode.entries.map { it.id }.distinct().size)
        assertEquals(MotionPattern.entries.size, MotionPattern.entries.map { it.id }.distinct().size)
    }

    @Test
    fun `fromId returns null for unknown ids instead of throwing`() {
        assertNull(LedEffect.fromId(0x42))
        assertNull(BlendMode.fromId(0x42))
        assertNull(MotionPattern.fromId(0x42))
        assertNull(LedEffect.fromId(Protocol.EMPTY_EFFECT_ID))
    }

    @Test
    fun `param ids are contiguous from zero`() {
        (LedEffect.entries.map { it.params } + MotionPattern.entries.map { it.params }).forEach { params ->
            assertEquals(params.indices.toList(), params.map { it.id })
        }
    }

    @Test
    fun `param defaults sit inside their declared range`() {
        (LedEffect.entries.flatMap { it.params } + MotionPattern.entries.flatMap { it.params }).forEach { param ->
            assertTrue(
                "${param.name} default ${param.default} outside ${param.min}..${param.max}",
                param.default in param.min..param.max
            )
        }
    }
}

class LedStateModelTest {

    private fun state(vararg effectIds: Byte) = LedState(
        numRings = 1,
        ledsPerRing = listOf(8),
        layers = effectIds.map { FirmwarePayloads.layer(it) }
    )

    @Test
    fun `empty detection follows the firmware sentinel`() {
        assertTrue(LayerConfig.empty().isEmpty)
        assertFalse(FirmwarePayloads.layer(LedEffect.RAINBOW.id).isEmpty)
    }

    @Test
    fun `occupied indices skip cleared slots but keep real indices`() {
        val ledState = state(LedEffect.RAINBOW.id, Protocol.EMPTY_EFFECT_ID, LedEffect.IMAGE.id)
        assertEquals(listOf(0, 2), ledState.occupiedLayerIndices)
    }

    @Test
    fun `first free layer reuses a cleared slot before extending`() {
        val ledState = state(LedEffect.RAINBOW.id, Protocol.EMPTY_EFFECT_ID, LedEffect.IMAGE.id)
        assertEquals(1, ledState.firstFreeLayerIndex(maxLayers = 8))
    }

    @Test
    fun `first free layer extends the stack when nothing is cleared`() {
        val ledState = state(LedEffect.RAINBOW.id, LedEffect.IMAGE.id)
        assertEquals(2, ledState.firstFreeLayerIndex(maxLayers = 8))
    }

    @Test
    fun `first free layer is null when the stack is full`() {
        val ledState = state(*ByteArray(8) { LedEffect.RAINBOW.id })
        assertNull(ledState.firstFreeLayerIndex(maxLayers = 8))
    }

    @Test
    fun `first free layer respects a lower device limit`() {
        val ledState = state(LedEffect.RAINBOW.id, LedEffect.IMAGE.id)
        assertNull(ledState.firstFreeLayerIndex(maxLayers = 2))
    }

    @Test
    fun `total leds sums the ring sizes`() {
        assertEquals(48, LedState(5, listOf(8, 10, 12, 10, 8), emptyList()).totalLeds)
    }
}

class ProfileSlotTest {

    @Test
    fun `display name falls back to the slot number`() {
        assertEquals("Slot 2", ProfileSlot(2, occupied = true, name = null).displayName)
        assertEquals("Slot 2", ProfileSlot(2, occupied = true, name = "   ").displayName)
        assertEquals("Idle", ProfileSlot(2, occupied = true, name = "Idle").displayName)
    }
}

class DeviceStateTest {

    @Test
    fun `capabilities fall back to defaults before system info arrives`() {
        assertEquals(Capabilities.DEFAULT, DeviceState().capabilities)
    }

    @Test
    fun `capabilities come from system info when present`() {
        val caps = Capabilities.DEFAULT.copy(maxLayers = 4, imageMaxDim = 16)
        val state = DeviceState(
            systemInfo = SystemInfo(1, 1, 0, 0, emptyList(), emptyList(), caps)
        )
        assertEquals(4, state.capabilities.maxLayers)
        assertEquals(16, state.capabilities.imageMaxDim)
    }
}
