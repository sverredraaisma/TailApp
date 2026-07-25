package com.tailapp.ble.protocol

import com.tailapp.model.Diagnostics
import com.tailapp.model.DiagnosticsBatteryLevel
import com.tailapp.model.DriverFault
import com.tailapp.testutil.FirmwarePayloads
import com.tailapp.testutil.FirmwarePayloads.DiagMotor
import com.tailapp.testutil.FirmwarePayloads.DiagSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsParserTest {

    @Test
    fun `parses a full format-version 3 payload field by field`() {
        val payload = FirmwarePayloads.diagnostics(
            formatVersion = 3,
            uptimeSeconds = 3_600_000L,
            freeHeapBytes = 123_456L,
            minFreeHeapBytes = 65_432L,
            stallCount = 7,
            commandQueueDropped = 3,
            motionOverruns = 11,
            renderOverruns = 5,
            framesSkipped = 9,
            lastFrameMicros = 8_123,
            meanFrameMicros = 8_456,
            maxFrameMicros = 20_000,
            frameRateHz = 45,
            taskStacks = listOf(512, 480, 600, 320, 700),
            imus = listOf(DiagSensor(failures = 0), DiagSensor(failures = 2, disabled = true)),
            batteryPercent = 55,
            batteryMillivolts = 3_777,
            batteryLevel = 2,
            encoders = listOf(
                DiagSensor(failures = 0),
                DiagSensor(failures = 1),
                DiagSensor(failures = 0),
                DiagSensor(failures = 0, disabled = true)
            ),
            rehomeCount = 4,
            motors = listOf(DiagMotor(0x01), DiagMotor(0x00), DiagMotor(0x14), DiagMotor(0x02)),
            driverLostWrites = 6,
            driverReadFailures = 2
        )
        // The current firmware publishes exactly 96 bytes at format_version 3.
        assertEquals(96, payload.size)

        val d = requireNotNull(DiagnosticsParser.parse(payload))

        assertEquals(3, d.formatVersion)
        assertEquals(3_600_000L, d.uptimeSeconds)
        assertEquals(123_456L, d.freeHeapBytes)
        assertEquals(65_432L, d.minFreeHeapBytes)
        assertEquals(7L, d.stallCount)
        assertEquals(3L, d.commandQueueDropped)
        assertEquals(11L, d.motionOverruns)
        assertEquals(5L, d.renderOverruns)
        assertEquals(9L, d.framesSkipped)
        assertEquals(8_123L, d.lastFrameMicros)
        assertEquals(8_456L, d.meanFrameMicros)
        assertEquals(20_000L, d.maxFrameMicros)
        assertEquals(45, d.frameRateHz)

        assertEquals(5, d.tasks.size)
        assertEquals(listOf("motion", "led_rend", "config", "nimble_host", "led_stat"), d.tasks.map { it.name })
        assertEquals(listOf(512, 480, 600, 320, 700), d.tasks.map { it.freeWords })

        assertEquals(2, d.imus.size)
        assertEquals(0, d.imus[0].failures)
        assertFalse(d.imus[0].disabled)
        assertEquals(2, d.imus[1].failures)
        assertTrue(d.imus[1].disabled)

        assertEquals(55, d.batteryPercent)
        assertEquals(3_777, d.batteryMillivolts)
        assertEquals(DiagnosticsBatteryLevel.LOW, d.batteryLevel)

        assertTrue(d.hasEncoderHealth)
        val encoders = requireNotNull(d.encoders)
        assertEquals(4, encoders.size)
        assertEquals(1, encoders[1].failures)
        assertTrue(encoders[3].disabled)
        assertEquals(4, d.rehomeCount)

        assertTrue(d.hasDriverHealth)
        val motors = requireNotNull(d.motors)
        assertEquals(4, motors.size)
        assertEquals(0x01, motors[0].faults)
        assertTrue(motors[1].isHealthy)
        // 0x14 = OLA (0x10) | S2GA (0x04), in bit order.
        assertEquals(
            listOf(DriverFault.SHORT_TO_GROUND_A, DriverFault.OPEN_LOAD_A),
            motors[2].decoded
        )
        assertEquals(listOf(DriverFault.OVERTEMP_SHUTDOWN), motors[3].decoded)
        assertEquals(6L, d.driverLostWrites)
        assertEquals(2L, d.driverReadFailures)
    }

    @Test
    fun `battery percent 0xFF parses as unknown, distinct from zero`() {
        val unknown = requireNotNull(
            DiagnosticsParser.parse(FirmwarePayloads.diagnostics(batteryPercent = null, batteryLevel = 0))
        )
        assertNull(unknown.batteryPercent)
        assertEquals(DiagnosticsBatteryLevel.UNKNOWN, unknown.batteryLevel)

        val flat = requireNotNull(
            DiagnosticsParser.parse(FirmwarePayloads.diagnostics(batteryPercent = 0))
        )
        assertEquals(0, flat.batteryPercent)
    }

    @Test
    fun `a format-version 1 core payload parses with the later blocks null`() {
        val payload = FirmwarePayloads.diagnostics(formatVersion = 1, batteryPercent = 40, batteryLevel = 1)
        // Version 1 is the first 68 bytes: the encoder and driver blocks are absent.
        assertEquals(68, payload.size)

        val d = requireNotNull(DiagnosticsParser.parse(payload))
        assertEquals(1, d.formatVersion)
        // The core still parses in full.
        assertEquals(5, d.tasks.size)
        assertEquals(40, d.batteryPercent)

        // Absent, not zero: a firmware that never reported encoder or driver health
        // must not read as one whose sensors are all fine.
        assertFalse(d.hasEncoderHealth)
        assertNull(d.encoders)
        assertNull(d.rehomeCount)
        assertFalse(d.hasDriverHealth)
        assertNull(d.motors)
        assertNull(d.driverLostWrites)
        assertNull(d.driverReadFailures)
    }

    @Test
    fun `a format-version 2 payload has encoder health but null driver health`() {
        val payload = FirmwarePayloads.diagnostics(formatVersion = 2, rehomeCount = 3)
        // Version 2 is 83 bytes: encoder block present, driver block absent.
        assertEquals(83, payload.size)

        val d = requireNotNull(DiagnosticsParser.parse(payload))
        assertTrue(d.hasEncoderHealth)
        assertEquals(3, d.rehomeCount)
        assertNull(d.motors)
        assertNull(d.driverLostWrites)
    }

    @Test
    fun `a payload too short for the version-1 core is rejected`() {
        val full = FirmwarePayloads.diagnostics(formatVersion = 1)
        assertEquals(68, full.size)

        // One byte short of the core is not a shorter version — it is unreadable.
        assertNull(DiagnosticsParser.parse(full.copyOf(67)))
        assertNull(DiagnosticsParser.parse(full.copyOf(40)))
        assertNull(DiagnosticsParser.parse(ByteArray(0)))
    }

    @Test
    fun `each driver-fault bit decodes to its own condition`() {
        val expected = mapOf(
            0x01 to DriverFault.OVERTEMP_WARNING,
            0x02 to DriverFault.OVERTEMP_SHUTDOWN,
            0x04 to DriverFault.SHORT_TO_GROUND_A,
            0x08 to DriverFault.SHORT_TO_GROUND_B,
            0x10 to DriverFault.OPEN_LOAD_A,
            0x20 to DriverFault.OPEN_LOAD_B
        )
        expected.forEach { (bit, fault) ->
            assertEquals(listOf(fault), DriverFault.decode(bit))
        }

        assertEquals(
            listOf(
                "Overtemperature warning",
                "Overtemperature shutdown",
                "Short to ground (phase A)",
                "Short to ground (phase B)",
                "Open load (phase A)",
                "Open load (phase B)"
            ),
            expected.values.map { it.label }
        )

        // A healthy driver decodes to nothing; every bit set decodes to all six.
        assertTrue(DriverFault.decode(0x00).isEmpty())
        assertEquals(6, DriverFault.decode(0x3F).size)
    }

    @Test
    fun `a task reporting zero stack words is flagged as not registered`() {
        val payload = FirmwarePayloads.diagnostics(taskStacks = listOf(0, 480, 600, 320, 700))
        val d = requireNotNull(DiagnosticsParser.parse(payload))
        assertTrue(d.tasks[0].notRegistered)
        assertFalse(d.tasks[1].notRegistered)
    }
}
