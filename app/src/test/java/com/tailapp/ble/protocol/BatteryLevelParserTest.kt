package com.tailapp.ble.protocol

import com.tailapp.testutil.FirmwarePayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The standard Battery Level read (0x2A19).
 *
 * The one thing this parser exists to get right is that unknown and zero are
 * different answers. A board whose sense divider is not populated knows nothing
 * about the pack; showing that as 0 % tells the user their tail is about to die.
 */
class BatteryLevelParserTest {

    @Test
    fun `an empty pack is zero percent and stays a number`() {
        assertEquals(0, BatteryLevelParser.parse(FirmwarePayloads.batteryLevel(0)))
        assertEquals(1, BatteryLevelParser.parse(FirmwarePayloads.batteryLevel(1)))
        assertEquals(78, BatteryLevelParser.parse(FirmwarePayloads.batteryLevel(78)))
        assertEquals(100, BatteryLevelParser.parse(FirmwarePayloads.batteryLevel(100)))
    }

    @Test
    fun `the unknown sentinel is null and not zero`() {
        assertNull(BatteryLevelParser.parse(FirmwarePayloads.batteryLevel(null)))
        assertEquals(
            Protocol.BATTERY_PERCENT_UNKNOWN.toByte(),
            FirmwarePayloads.batteryLevel(null)[0]
        )
    }

    @Test
    fun `anything past a hundred percent is unknown rather than clamped`() {
        // The adopted characteristic only defines 0-100. Clamping 0xFF to 100
        // would render a device that cannot measure its pack as fully charged.
        assertNull(BatteryLevelParser.parse(byteArrayOf(101.toByte())))
        assertNull(BatteryLevelParser.parse(byteArrayOf(200.toByte())))
        assertNull(BatteryLevelParser.parse(byteArrayOf(0xFE.toByte())))
    }

    @Test
    fun `an empty payload is unknown`() {
        assertNull(BatteryLevelParser.parse(ByteArray(0)))
    }
}
