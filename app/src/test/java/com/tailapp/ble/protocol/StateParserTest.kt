package com.tailapp.ble.protocol

import com.tailapp.model.LedEffect
import com.tailapp.model.MotionPattern
import com.tailapp.testutil.FirmwarePayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStateParserTest {

    @Test
    fun `parses all 77 bytes in order`() {
        val payload = FirmwarePayloads.motionState(
            patternId = 0x01,
            params = listOf(1.5f, 45f, 0f, 0f, 0f, 0f, 0f, 0f),
            encoders = listOf(1f, -2f, 3f, -4f),
            gravity = Triple(0.1f, -0.2f, 0.97f),
            xLimits = -90f to 80f,
            yLimits = -30f to 40f
        )
        assertEquals(Protocol.MOTION_STATE_SIZE, payload.size)

        val state = requireNotNull(MotionStateParser.parse(payload))
        assertEquals(MotionPattern.WAGGING, state.activePattern)
        assertEquals(listOf(1.5f, 45f, 0f, 0f, 0f, 0f, 0f, 0f), state.params)
        assertEquals(listOf(1f, -2f, 3f, -4f), state.encoderPositions)
        assertEquals(0.1f, state.gravityX, 1e-6f)
        assertEquals(-0.2f, state.gravityY, 1e-6f)
        assertEquals(0.97f, state.gravityZ, 1e-6f)
        assertEquals(-90f, state.xAxisMin, 0f)
        assertEquals(80f, state.xAxisMax, 0f)
        assertEquals(-30f, state.yAxisMin, 0f)
        assertEquals(40f, state.yAxisMax, 0f)
    }

    @Test
    fun `short payload is rejected`() {
        assertNull(MotionStateParser.parse(ByteArray(76)))
        assertNull(MotionStateParser.parse(ByteArray(0)))
    }

    @Test
    fun `longer payload is tolerated for forward compatibility`() {
        val padded = FirmwarePayloads.motionState() + ByteArray(8)
        assertNotNull(MotionStateParser.parse(padded))
    }

    @Test
    fun `unknown pattern id yields a null pattern rather than throwing`() {
        val state = requireNotNull(MotionStateParser.parse(FirmwarePayloads.motionState(patternId = 0x7F)))
        assertEquals(0x7F.toByte(), state.activePatternId)
        assertNull(state.activePattern)
    }
}

class LedStateParserTest {

    @Test
    fun `parses matrix header and layer entries`() {
        val payload = FirmwarePayloads.ledState(
            ledsPerRing = listOf(8, 10, 12),
            layers = listOf(
                FirmwarePayloads.layer(
                    effectId = LedEffect.RAINBOW.id,
                    blendMode = 0x05,
                    enabled = true,
                    flipX = true,
                    mirrorY = true,
                    params = listOf(0f, 60f, 1f, 0f, 0f, 0f, 0f, 0f)
                ),
                FirmwarePayloads.layer(effectId = LedEffect.AUDIO_BAR.id, blendMode = 0x01, enabled = false)
            )
        )

        val state = requireNotNull(LedStateParser.parse(payload))
        assertEquals(3, state.numRings)
        assertEquals(listOf(8, 10, 12), state.ledsPerRing)
        assertEquals(30, state.totalLeds)
        assertEquals(2, state.layers.size)

        val first = state.layers[0]
        assertEquals(LedEffect.RAINBOW, first.effect)
        assertTrue(first.enabled)
        assertTrue(first.flipX)
        assertFalse(first.flipY)
        assertFalse(first.mirrorX)
        assertTrue(first.mirrorY)
        assertEquals(60f, first.params[1], 0f)

        val second = state.layers[1]
        assertEquals(LedEffect.AUDIO_BAR, second.effect)
        assertFalse(second.enabled)
    }

    @Test
    fun `each layer entry is 39 bytes`() {
        val one = FirmwarePayloads.ledState(listOf(8), listOf(FirmwarePayloads.layer(0)))
        val two = FirmwarePayloads.ledState(listOf(8), List(2) { FirmwarePayloads.layer(0) })
        assertEquals(Protocol.LED_LAYER_SIZE, two.size - one.size)
    }

    @Test
    fun `a removed slot reads back as empty`() {
        // The firmware stamps effect_id = 0xFF and leaves num_layers alone.
        val payload = FirmwarePayloads.ledState(
            ledsPerRing = listOf(8),
            layers = listOf(
                FirmwarePayloads.layer(LedEffect.RAINBOW.id),
                FirmwarePayloads.layer(Protocol.EMPTY_EFFECT_ID, blendMode = 0, enabled = false),
                FirmwarePayloads.layer(LedEffect.IMAGE.id)
            )
        )

        val state = requireNotNull(LedStateParser.parse(payload))
        assertEquals(3, state.layers.size)
        assertFalse(state.layers[0].isEmpty)
        assertTrue(state.layers[1].isEmpty)
        assertFalse(state.layers[2].isEmpty)
        assertEquals(listOf(0, 2), state.occupiedLayerIndices)
    }

    @Test
    fun `matrix with no layers parses`() {
        val state = requireNotNull(LedStateParser.parse(FirmwarePayloads.ledState(listOf(8, 8), emptyList())))
        assertEquals(2, state.numRings)
        assertTrue(state.layers.isEmpty())
        assertTrue(state.occupiedLayerIndices.isEmpty())
    }

    @Test
    fun `truncated payloads are rejected`() {
        assertNull(LedStateParser.parse(ByteArray(0)))
        // Declares 5 rings but carries none.
        assertNull(LedStateParser.parse(byteArrayOf(5, 8, 10)))
        // Declares 2 layers but carries one.
        val short = FirmwarePayloads.ledState(listOf(8), listOf(FirmwarePayloads.layer(0)))
        short[1 + 1] = 2
        assertNull(LedStateParser.parse(short))
    }

    @Test
    fun `ring counts above 127 are read unsigned`() {
        val state = requireNotNull(LedStateParser.parse(FirmwarePayloads.ledState(listOf(200, 8))))
        assertEquals(listOf(200, 8), state.ledsPerRing)
        assertEquals(208, state.totalLeds)
    }
}

class ProfileListParserTest {

    @Test
    fun `parses variable-length entries sequentially`() {
        val payload = FirmwarePayloads.profileList(
            listOf(
                true to "Wag+Rainbow",
                false to null,
                true to null,
                true to "Idle"
            )
        )

        val slots = ProfileListParser.parse(payload)
        assertEquals(4, slots.size)

        assertEquals(0, slots[0].index)
        assertTrue(slots[0].occupied)
        assertEquals("Wag+Rainbow", slots[0].name)
        assertEquals("Wag+Rainbow", slots[0].displayName)

        assertFalse(slots[1].occupied)
        assertNull(slots[1].name)
        assertEquals("Slot 1", slots[1].displayName)

        assertTrue(slots[2].occupied)
        assertNull(slots[2].name)
        assertEquals("Slot 2", slots[2].displayName)

        assertEquals("Idle", slots[3].name)
    }

    @Test
    fun `handles multi-byte names`() {
        val slots = ProfileListParser.parse(FirmwarePayloads.profileList(listOf(true to "Fox 🦊", false to null, false to null, false to null)))
        assertEquals("Fox 🦊", slots[0].name)
    }

    @Test
    fun `truncated payload yields placeholder slots`() {
        val slots = ProfileListParser.parse(byteArrayOf(1, 0))
        assertEquals(Protocol.MAX_PROFILE_SLOTS, slots.size)
        assertTrue(slots[0].occupied)
        assertFalse(slots[1].occupied)
        assertFalse(slots[3].occupied)
    }

    @Test
    fun `name longer than the payload does not overrun`() {
        // Claims a 10-byte name but only 3 bytes follow.
        val slots = ProfileListParser.parse(byteArrayOf(1, 10, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()))
        assertEquals("abc", slots[0].name)
        assertEquals(Protocol.MAX_PROFILE_SLOTS, slots.size)
    }

    @Test
    fun `empty payload yields four empty slots`() {
        val slots = ProfileListParser.parse(ByteArray(0))
        assertEquals(4, slots.size)
        assertTrue(slots.none { it.occupied })
    }
}

class CommandResultParserTest {

    @Test
    fun `parses a success ack`() {
        val result = requireNotNull(CommandResultParser.parse(FirmwarePayloads.commandResult(0x01, 0x03, 0x00)))
        assertEquals(0x01.toByte(), result.characteristicId)
        assertEquals(0x03.toByte(), result.commandId)
        assertEquals(CommandResultCode.OK, result.result)
        assertTrue(result.isSuccess)
        assertEquals("FF01", result.characteristicName)
    }

    @Test
    fun `parses every documented failure code`() {
        val expected = mapOf(
            0x01 to CommandResultCode.BAD_LENGTH,
            0x02 to CommandResultCode.UNKNOWN_CMD,
            0x03 to CommandResultCode.UNKNOWN_ID,
            0x04 to CommandResultCode.OUT_OF_RANGE,
            0x05 to CommandResultCode.BAD_STATE
        )
        expected.forEach { (code, enum) ->
            val result = requireNotNull(CommandResultParser.parse(FirmwarePayloads.commandResult(0x03, 0x01, code)))
            assertEquals(enum, result.result)
            assertFalse(result.isSuccess)
        }
    }

    @Test
    fun `unrecognised codes map to UNKNOWN rather than throwing`() {
        val result = requireNotNull(CommandResultParser.parse(FirmwarePayloads.commandResult(0x08, 0x02, 0x42)))
        assertEquals(CommandResultCode.UNKNOWN, result.result)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `characteristic name covers every acking characteristic`() {
        listOf(0x01 to "FF01", 0x03 to "FF03", 0x06 to "FF06", 0x08 to "FF08").forEach { (id, name) ->
            val result = requireNotNull(CommandResultParser.parse(FirmwarePayloads.commandResult(id, 0x01, 0x00)))
            assertEquals(name, result.characteristicName)
        }
    }

    @Test
    fun `short payload is rejected`() {
        assertNull(CommandResultParser.parse(byteArrayOf(0x01, 0x02)))
        assertNull(CommandResultParser.parse(ByteArray(0)))
    }
}

class SystemEventParserTest {

    @Test
    fun `parses the documented events`() {
        assertEquals(SystemEvent.TAP_BASE, SystemEventParser.parse(byteArrayOf(0x01)))
        assertEquals(SystemEvent.TAP_TIP, SystemEventParser.parse(byteArrayOf(0x02)))
        assertEquals(SystemEvent.CONFIG_CHANGED, SystemEventParser.parse(byteArrayOf(0x03)))
    }

    @Test
    fun `unknown and empty events yield null`() {
        assertNull(SystemEventParser.parse(byteArrayOf(0x09)))
        assertNull(SystemEventParser.parse(ByteArray(0)))
    }
}
