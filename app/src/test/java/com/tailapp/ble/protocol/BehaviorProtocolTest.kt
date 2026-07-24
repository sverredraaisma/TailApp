package com.tailapp.ble.protocol

import com.tailapp.model.BehaviorReason
import com.tailapp.model.BehaviorStateConfig
import com.tailapp.model.BehaviorTriggerConfig
import com.tailapp.model.BehaviorTriggerSource
import com.tailapp.model.MotionPattern
import com.tailapp.testutil.FirmwarePayloads
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behavior engine's wire surface (MOT-6), against the layouts in TailFirmware
 * `main/ble/ble_protocol.h` and the structs in `main/config/config_types.h`.
 *
 * Every expected byte here is worked out by hand from those structs. A record
 * recorded from a run would agree with whatever this app happens to emit, which
 * is exactly the question these tests exist to answer.
 */
class BehaviorRecordsTest {

    /**
     * `behavior_state_config_t`: pattern, flags, param mask, fallback, two u16s,
     * then eight little-endian floats. 40 bytes, no padding to reason about.
     */
    @Test
    fun `state record is forty bytes in struct order`() {
        val state = BehaviorStateConfig(
            patternId = MotionPattern.WAGGING.id,
            enabled = true,
            paramMask = 0x03,
            fallbackState = 2,
            minDwellMs = 1500,
            timeoutMs = 12000,
            params = listOf(1.5f, 32f, 0f, 0f, 0f, 0f, 0f, 0f)
        )

        val expected = byteArrayOf(
            0x01,                                     // pattern_id = PATTERN_WAGGING
            0x01,                                     // flags = BEH_STATE_FLAG_ENABLED
            0x03,                                     // param_mask = params 0 and 1
            0x02,                                     // fallback_state
            0xDC.toByte(), 0x05,                      // min_dwell_ms = 1500
            0xE0.toByte(), 0x2E,                      // timeout_ms = 12000
            0x00, 0x00, 0xC0.toByte(), 0x3F,          // params[0] = 1.5f
            0x00, 0x00, 0x00, 0x42                    // params[1] = 32f
        ) + ByteArray(24)                             // params[2..7] = 0f

        assertEquals(Protocol.BEHAVIOR_STATE_RECORD_SIZE, expected.size)
        assertArrayEquals(expected, BehaviorRecords.encodeState(state))
    }

    @Test
    fun `a disabled state clears the enabled flag rather than the pattern`() {
        val record = BehaviorRecords.encodeState(
            BehaviorStateConfig(patternId = MotionPattern.SHIVER.id, enabled = false)
        )
        assertEquals(MotionPattern.SHIVER.id, record[0])
        assertEquals(0x00.toByte(), record[1])
    }

    /**
     * `behavior_trigger_config_t`: five bytes, three reserved, a float, a u16 and
     * two more reserved. The reserved bytes are written as zero — the firmware
     * reads the struct back verbatim, so anything else is a value it did not
     * write and cannot interpret.
     */
    @Test
    fun `trigger record is sixteen bytes with zeroed reserved fields`() {
        val trigger = BehaviorTriggerConfig(
            source = BehaviorTriggerSource.HANDLING,
            fromMask = 0x13,
            toState = 2,
            count = 0,
            fireBelowThreshold = false,
            threshold = 0.25f,
            windowMs = 300
        )

        val expected = byteArrayOf(
            0x07,                              // source = BEH_TRIG_HANDLING
            0x13,                              // from_mask = IDLE | ALERT | SLEEPY
            0x02,                              // to_state
            0x00,                              // count (levels ignore it)
            0x00,                              // flags
            0x00, 0x00, 0x00,                  // _reserved[3]
            0x00, 0x00, 0x80.toByte(), 0x3E,   // threshold = 0.25f
            0x2C, 0x01,                        // window_ms = 300
            0x00, 0x00                         // _reserved2
        )

        assertEquals(Protocol.BEHAVIOR_TRIGGER_RECORD_SIZE, expected.size)
        assertArrayEquals(expected, BehaviorRecords.encodeTrigger(trigger))
    }

    @Test
    fun `firing below the threshold sets BEH_TRIG_FLAG_BELOW`() {
        val record = BehaviorRecords.encodeTrigger(
            BehaviorTriggerConfig(
                source = BehaviorTriggerSource.LOUDNESS,
                toState = 0,
                fireBelowThreshold = true,
                threshold = 0.1f
            )
        )
        assertEquals(0x01.toByte(), record[4])
    }

    @Test
    fun `a tap row carries its count and window, not a threshold`() {
        val record = BehaviorRecords.encodeTrigger(
            BehaviorTriggerConfig(
                source = BehaviorTriggerSource.TAP_BASE,
                toState = 2,
                count = 2,
                windowMs = 1500
            )
        )
        assertEquals(0x01.toByte(), record[0])
        assertEquals(2.toByte(), record[3])
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x00), record.copyOfRange(8, 12))
        assertArrayEquals(byteArrayOf(0xDC.toByte(), 0x05), record.copyOfRange(12, 14))
    }

    @Test
    fun `records survive a round trip through their own bytes`() {
        val state = BehaviorStateConfig(
            patternId = MotionPattern.EXCITED_WAG.id,
            enabled = true,
            paramMask = 0x0F,
            fallbackState = 1,
            minDwellMs = 2000,
            timeoutMs = 0,
            params = listOf(4.5f, 45f, 1.2f, 0.5f, 0f, 0f, 0f, 0f)
        )
        assertEquals(state, BehaviorRecords.decodeState(BehaviorRecords.encodeState(state)))

        val trigger = BehaviorTriggerConfig(
            source = BehaviorTriggerSource.QUIET_TIME,
            fromMask = 0x2E,
            toState = 0,
            threshold = 8f,
            windowMs = 0
        )
        assertEquals(trigger, BehaviorRecords.decodeTrigger(BehaviorRecords.encodeTrigger(trigger)))
    }

    @Test
    fun `decoding refuses a short buffer instead of reading past it`() {
        assertNull(BehaviorRecords.decodeState(ByteArray(39)))
        assertNull(BehaviorRecords.decodeTrigger(ByteArray(15)))
        assertNull(BehaviorRecords.decodeState(ByteArray(60), offset = 21))
    }

    @Test
    fun `decoding refuses a source this build does not know`() {
        // Better a missing row than a row silently shown as some other rule.
        val record = ByteArray(Protocol.BEHAVIOR_TRIGGER_RECORD_SIZE).also { it[0] = 0x7F }
        assertNull(BehaviorRecords.decodeTrigger(record))
    }

    @Test
    fun `encoding refuses a record the device would repair behind our back`() {
        assertThrows(IllegalArgumentException::class.java) {
            BehaviorRecords.encodeState(
                BehaviorStateConfig(patternId = 0x01, fallbackState = 9)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BehaviorRecords.encodeTrigger(
                BehaviorTriggerConfig(source = BehaviorTriggerSource.LOUDNESS, threshold = -1f)
            )
        }
    }
}

/** `MCMD_SET_BEHAVIOR_*` — the index byte, then the record verbatim. */
class BehaviorCommandTest {

    @Test
    fun `setBehaviorEnabled is two bytes`() {
        assertArrayEquals(byteArrayOf(0x10, 0x01), MotionCommands.setBehaviorEnabled(true))
        assertArrayEquals(byteArrayOf(0x10, 0x00), MotionCommands.setBehaviorEnabled(false))
    }

    @Test
    fun `setBehaviorState is two bytes`() {
        assertArrayEquals(byteArrayOf(0x11, 0x03), MotionCommands.setBehaviorState(3))
    }

    @Test
    fun `setBehaviorConfig is the index then the forty-byte record`() {
        val state = BehaviorStateConfig(
            patternId = MotionPattern.IDLE_SWAY.id,
            minDwellMs = 900
        )
        val cmd = MotionCommands.setBehaviorConfig(2, state)

        val expected = byteArrayOf(
            0x12, 0x02,
            0x03,                     // pattern_id = PATTERN_IDLE_SWAY
            0x01,                     // flags = enabled
            0x00,                     // param_mask: keep every pattern default
            0x00,                     // fallback_state
            0x84.toByte(), 0x03,      // min_dwell_ms = 900
            0x00, 0x00                // timeout_ms = 0, "stay until a trigger says otherwise"
        ) + ByteArray(32)

        assertEquals(2 + Protocol.BEHAVIOR_STATE_RECORD_SIZE, cmd.size)
        assertArrayEquals(expected, cmd)
    }

    @Test
    fun `setBehaviorTrigger is the index then the sixteen-byte record`() {
        val cmd = MotionCommands.setBehaviorTrigger(
            4,
            BehaviorTriggerConfig(
                source = BehaviorTriggerSource.DROP,
                toState = 3
            )
        )

        val expected = byteArrayOf(
            0x13, 0x04,
            0x06,                    // source = BEH_TRIG_DROP
            0x00,                    // from_mask = any state
            0x03,                    // to_state
            0x00, 0x00,              // count, flags
            0x00, 0x00, 0x00,        // _reserved[3]
            0x00, 0x00, 0x00, 0x00,  // threshold (an event ignores it)
            0x00, 0x00,              // window_ms
            0x00, 0x00               // _reserved2
        )

        assertEquals(2 + Protocol.BEHAVIOR_TRIGGER_RECORD_SIZE, cmd.size)
        assertArrayEquals(expected, cmd)
    }
}

/** The 4-byte behavior block appended to the FF02 motion state. */
class BehaviorBlockParseTest {

    @Test
    fun `parses the appended block`() {
        val payload = FirmwarePayloads.motionState(
            behavior = FirmwarePayloads.BehaviorBlock(
                stateIndex = 3,
                reason = 0x03,   // the BEH_TRIG_LOUDNESS row fired
                flags = 0x01,    // BEH_FLAG_ENABLED
                drivingPatternId = MotionPattern.EXCITED_WAG.id.toInt()
            )
        )
        assertEquals(Protocol.MOTION_STATE_WITH_BEHAVIOR_SIZE, payload.size)

        val behavior = requireNotNull(MotionStateParser.parse(payload)?.behavior)
        assertEquals(3, behavior.stateIndex)
        assertEquals(BehaviorTriggerSource.LOUDNESS, behavior.reason.triggerSource)
        assertEquals("trigger: Loudness", behavior.reason.description)
        assertTrue(behavior.engineEnabled)
        assertFalse(behavior.suspended)
        assertEquals(MotionPattern.EXCITED_WAG, behavior.drivingPattern)
    }

    @Test
    fun `firmware without the block leaves the engine unknown, not off`() {
        // "This device cannot tell me" and "the engine is switched off" are
        // different answers, and only the second one is a fact about the tail.
        val payload = FirmwarePayloads.motionState()
        assertEquals(Protocol.MOTION_STATE_SIZE, payload.size)
        assertNotNull(MotionStateParser.parse(payload))
        assertNull(MotionStateParser.parse(payload)?.behavior)
    }

    @Test
    fun `0xFF reads as engine off and not driving`() {
        val payload = FirmwarePayloads.motionState(
            behavior = FirmwarePayloads.BehaviorBlock(
                stateIndex = 0xFF,
                reason = BehaviorReason.NONE,
                flags = 0x00,
                drivingPatternId = 0xFF
            )
        )
        val behavior = requireNotNull(MotionStateParser.parse(payload)?.behavior)
        assertNull(behavior.stateIndex)
        assertNull(behavior.drivingPatternId)
        assertFalse(behavior.engineEnabled)
        assertEquals("nothing has caused a transition yet", behavior.reason.description)
    }

    @Test
    fun `the flags byte reports who is holding the tail`() {
        fun flags(value: Int) = requireNotNull(
            MotionStateParser.parse(
                FirmwarePayloads.motionState(
                    behavior = FirmwarePayloads.BehaviorBlock(stateIndex = 0, flags = value)
                )
            )?.behavior
        )

        val streaming = flags(0x03)
        assertTrue(streaming.streamHeld)
        assertFalse(streaming.stallHeld)
        assertTrue(streaming.suspended)

        val stalled = flags(0x05)
        assertTrue(stalled.stallHeld)
        assertFalse(stalled.streamHeld)
        assertTrue(stalled.suspended)
    }

    @Test
    fun `the named reasons keep their meaning apart from the trigger codes`() {
        assertEquals("the state's own timeout elapsed", BehaviorReason(0x80).description)
        assertEquals("forced from this app", BehaviorReason(0x81).description)
        assertEquals(
            "the app disconnected — fell back to idle",
            BehaviorReason(0x82).description
        )
        assertEquals("the engine was switched on", BehaviorReason(0x83).description)
        // A reason from a firmware newer than this build must not be read as a
        // trigger source it happens to numerically collide with.
        assertNull(BehaviorReason(0x84).triggerSource)
        assertEquals("unknown reason (0x84)", BehaviorReason(0x84).description)
    }

    @Test
    fun `the selected pattern is not the pattern the engine is running`() {
        // Byte 0 stays what MCMD_SELECT_PATTERN set — what runs the moment the
        // engine is switched off — while the block says what is on the motors.
        val state = requireNotNull(
            MotionStateParser.parse(
                FirmwarePayloads.motionState(
                    patternId = MotionPattern.STATIC.id,
                    behavior = FirmwarePayloads.BehaviorBlock(
                        stateIndex = 2,
                        flags = 0x01,
                        drivingPatternId = MotionPattern.WAGGING.id.toInt()
                    )
                )
            )
        )
        assertEquals(MotionPattern.STATIC, state.activePattern)
        assertEquals(MotionPattern.WAGGING, state.behavior?.drivingPattern)
    }
}
