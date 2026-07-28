package com.tailapp.ble.protocol

import com.tailapp.testutil.FirmwarePayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FF09 acknowledgement channel and the FF07 event stream — the two places
 * where the device tells the app something went wrong. Both gained payload in
 * protocol v5, and both have to keep working against firmware that has not.
 */
class AckAndEventTest {

    @Test
    fun `parses the v5 acknowledgement including its sequence byte`() {
        val result = requireNotNull(
            CommandResultParser.parse(
                FirmwarePayloads.commandResult(0x01, 0x08, 0x00, sequence = 42)
            )
        )

        assertEquals(0x01.toByte(), result.characteristicId)
        assertEquals("FF01", result.characteristicName)
        assertEquals(0x08.toByte(), result.commandId)
        assertEquals(CommandResultCode.OK, result.result)
        assertEquals(42, result.sequence)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `a three-byte acknowledgement from older firmware still parses`() {
        // The sequence byte is read when present rather than required, so an
        // older device does not lose its acknowledgements entirely.
        val result = requireNotNull(
            CommandResultParser.parse(
                FirmwarePayloads.commandResult(0x03, 0x01, 0x04, sequence = null)
            )
        )

        assertEquals(CommandResultCode.OUT_OF_RANGE, result.result)
        assertNull(result.sequence)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `a busy result is recognised as worth retrying`() {
        val result = requireNotNull(
            CommandResultParser.parse(FirmwarePayloads.commandResult(0x01, 0x01, 0x06))
        )

        // The device's command queue was full. Unlike every other rejection,
        // the same command can succeed unchanged a moment later.
        assertEquals(CommandResultCode.BUSY, result.result)
        assertTrue(result.result.isRetryable)
        assertFalse(result.result.isSuccess)
        assertFalse(CommandResultCode.OUT_OF_RANGE.isRetryable)
    }

    @Test
    fun `an unknown result code degrades instead of failing to parse`() {
        val result = requireNotNull(
            CommandResultParser.parse(FirmwarePayloads.commandResult(0x01, 0x01, 0x7E))
        )
        assertEquals(CommandResultCode.UNKNOWN, result.result)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `a payload shorter than an acknowledgement is rejected`() {
        assertNull(CommandResultParser.parse(byteArrayOf(0x01, 0x02)))
        assertNull(CommandResultParser.parse(ByteArray(0)))
    }

    @Test
    fun `a stall event is recognised`() {
        // Until this existed the app dropped the event entirely, and a stalled
        // tail just stopped moving with nothing said about why.
        assertEquals(SystemEvent.STALL, SystemEventParser.parse(byteArrayOf(0x04)))
        assertEquals(SystemEvent.TAP_BASE, SystemEventParser.parse(byteArrayOf(0x01)))
        assertEquals(SystemEvent.TAP_TIP, SystemEventParser.parse(byteArrayOf(0x02)))
        assertEquals(SystemEvent.CONFIG_CHANGED, SystemEventParser.parse(byteArrayOf(0x03)))
        assertNull(SystemEventParser.parse(byteArrayOf(0x7F)))
    }

    @Test
    fun `the readable event ring recovers events the notify missed`() {
        val log = SystemEventParser.parseLog(FirmwarePayloads.eventLog(listOf(0x01, 0x04, 0x02)))

        assertEquals(
            listOf(SystemEvent.TAP_BASE, SystemEvent.STALL, SystemEvent.TAP_TIP),
            log
        )
    }

    @Test
    fun `each battery policy event maps to its own code`() {
        // The three crossings, at the codes ble_protocol.h assigns them. They are
        // sent once, on the crossing, so an app that mapped two of them together
        // would silently stop distinguishing "dimmed" from "parked".
        assertEquals(SystemEvent.BATTERY_LOW, SystemEventParser.parse(byteArrayOf(0x0D)))
        assertEquals(SystemEvent.BATTERY_CRITICAL, SystemEventParser.parse(byteArrayOf(0x0E)))
        assertEquals(SystemEvent.BATTERY_NORMAL, SystemEventParser.parse(byteArrayOf(0x0F)))

        assertEquals(0x0D.toByte(), SystemEvent.BATTERY_LOW.code)
        assertEquals(0x0E.toByte(), SystemEvent.BATTERY_CRITICAL.code)
        assertEquals(0x0F.toByte(), SystemEvent.BATTERY_NORMAL.code)

        assertTrue(SystemEvent.BATTERY_CRITICAL.isBatteryPolicy)
        assertFalse(SystemEvent.STALL.isBatteryPolicy)
    }

    @Test
    fun `the event ring carries the battery crossings too`() {
        // Which is how an app that connects afterwards learns the pack went flat
        // — nothing repeats the event while the level sits where it is.
        val log = SystemEventParser.parseLog(FirmwarePayloads.eventLog(listOf(0x0D, 0x0E, 0x0F)))
        assertEquals(
            listOf(
                SystemEvent.BATTERY_LOW,
                SystemEvent.BATTERY_CRITICAL,
                SystemEvent.BATTERY_NORMAL
            ),
            log
        )
    }

    @Test
    fun `a pending-verify OTA rejection is a named, retryable result`() {
        // 0x0B is what a second update in a row hits: the running image is still
        // on probation. It fell through to UNKNOWN before, which rendered as
        // "Unrecognised result code" and reported itself as not worth retrying —
        // the opposite of what the firmware means by it.
        val result = requireNotNull(
            CommandResultParser.parse(
                FirmwarePayloads.commandResult(0x06, 0x08, 0x0B, sequence = 7)
            )
        )

        assertEquals(CommandResultCode.OTA_VERIFY_PENDING, result.result)
        assertFalse(result.isSuccess)
        assertTrue(result.result.isRetryable)
        // The message has to tell the user what to do, not just what happened.
        assertTrue(result.result.message.contains("10 s"))
        assertEquals(0x0B.toByte(), CommandResultCode.OTA_VERIFY_PENDING.code)
    }

    @Test
    fun `only the two 'not yet' results are retryable`() {
        val retryable = CommandResultCode.entries.filter { it.isRetryable }
        assertEquals(
            listOf(CommandResultCode.BUSY, CommandResultCode.OTA_VERIFY_PENDING),
            retryable
        )
    }

    @Test
    fun `every driver-health event maps to its own code`() {
        // Four appear/clear pairs, 0x05-0x0C. The parser dropped all eight, so a
        // tail that had cooked itself was indistinguishable from one that had
        // simply stopped.
        val expected = mapOf(
            0x05 to SystemEvent.DRV_OVERTEMP_WARN,
            0x06 to SystemEvent.DRV_OVERTEMP_WARN_CLEAR,
            0x07 to SystemEvent.DRV_OVERTEMP,
            0x08 to SystemEvent.DRV_OVERTEMP_CLEAR,
            0x09 to SystemEvent.DRV_SHORT,
            0x0A to SystemEvent.DRV_SHORT_CLEAR,
            0x0B to SystemEvent.DRV_OPEN_LOAD,
            0x0C to SystemEvent.DRV_OPEN_LOAD_CLEAR
        )
        for ((code, event) in expected) {
            assertEquals(event, SystemEventParser.parse(byteArrayOf(code.toByte())))
            assertEquals(code.toByte(), event.code)
            assertTrue(event.isDriverHealth)
            assertFalse(event.isBatteryPolicy)
            assertTrue(event.description.isNotBlank())
        }
    }

    @Test
    fun `a driver fault is distinguished from its recovery`() {
        // parseLog uses mapNotNull, so a pair that both mapped to "fault" would
        // count every fault twice and never show one ending.
        assertTrue(SystemEvent.DRV_OVERTEMP.isDriverFault)
        assertFalse(SystemEvent.DRV_OVERTEMP_CLEAR.isDriverFault)
        assertTrue(SystemEvent.DRV_OPEN_LOAD.isDriverFault)
        assertFalse(SystemEvent.DRV_OPEN_LOAD_CLEAR.isDriverFault)
        assertFalse(SystemEvent.STALL.isDriverHealth)
        assertFalse(SystemEvent.BATTERY_LOW.isDriverHealth)
    }

    @Test
    fun `the event ring keeps driver-health events the app was disconnected for`() {
        // These fire whether or not a phone is listening, which is the whole
        // point of the readable ring. mapNotNull used to silently drop them, so
        // the ring came back shorter than the device wrote it.
        val log = SystemEventParser.parseLog(
            FirmwarePayloads.eventLog(listOf(0x05, 0x07, 0x08, 0x06, 0x04))
        )
        assertEquals(
            listOf(
                SystemEvent.DRV_OVERTEMP_WARN,
                SystemEvent.DRV_OVERTEMP,
                SystemEvent.DRV_OVERTEMP_CLEAR,
                SystemEvent.DRV_OVERTEMP_WARN_CLEAR,
                SystemEvent.STALL
            ),
            log
        )
    }

    @Test
    fun `an unparseable event ring yields nothing rather than guessing`() {
        assertEquals(emptyList<SystemEvent>(), SystemEventParser.parseLog(ByteArray(0)))
        assertEquals(emptyList<SystemEvent>(), SystemEventParser.parseLog(byteArrayOf(0x04)))
        // Claims three events, carries one.
        assertEquals(emptyList<SystemEvent>(), SystemEventParser.parseLog(byteArrayOf(0x03, 0x01)))
    }
}
