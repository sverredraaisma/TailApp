package com.tailapp.ble

import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.LedCommands
import com.tailapp.ble.protocol.LedStateParser
import com.tailapp.ble.protocol.MotionStateParser
import com.tailapp.ble.protocol.ProfileListParser
import com.tailapp.ble.protocol.SystemCommands
import com.tailapp.ble.protocol.SystemInfoParser
import com.tailapp.model.Capabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The virtual tail has to satisfy the same parsers a real device's reads do — a
 * byte out of place here would be a byte out of place against real hardware too.
 * These drive it directly through [BleTransport], the way
 * [com.tailapp.repository.DeviceRepository] would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualTailTransportTest {

    private fun connected(): VirtualTailTransport =
        VirtualTailTransport().apply { connect(VirtualTailTransport.ADDRESS) }

    private fun matrix(vararg rings: Int): ByteArray =
        SystemCommands.setLedMatrix(rings.map { it.toByte() }, 20)

    @Test
    fun `connecting reports CONNECTED`() {
        assertEquals(ConnectionState.CONNECTED, connected().connectionState.value)
    }

    @Test
    fun `the initial reads parse`() = runTest {
        val tail = connected()

        val motion = MotionStateParser.parse(tail.readCharacteristic(CharacteristicUuids.MOTION_STATE)!!)
        val led = LedStateParser.parse(tail.readCharacteristic(CharacteristicUuids.LED_STATE)!!)
        val system = SystemInfoParser.parse(tail.readCharacteristic(CharacteristicUuids.SYSTEM_CONFIG)!!)
        val profiles = ProfileListParser.parse(tail.readCharacteristic(CharacteristicUuids.PROFILE_MGMT)!!)

        assertNotNull("motion state should parse", motion)
        assertNotNull("system info should parse", system)
        assertNotNull("led state should parse", led)

        assertEquals("a 48-LED, five-ring matrix", listOf(8, 10, 12, 10, 8), led!!.ledsPerRing)
        assertEquals("one default layer", 1, led.layers.size)
        assertEquals("that layer is a rainbow", 0.toByte(), led.layers.first().effectId)
        assertEquals(
            "the shipped capabilities",
            Capabilities.DEFAULT.maxLayers, system!!.effectiveCapabilities.maxLayers
        )
        assertEquals("four profile slots", 4, profiles.size)
        assertTrue("none occupied at start", profiles.none { it.occupied })
    }

    @Test
    fun `a written layer survives a re-read`() = runTest {
        val tail = connected()

        tail.writeCharacteristic(
            CharacteristicUuids.LED_CMD,
            LedCommands.setLayerEffect(layer = 1, effectId = 0x01, blendMode = 0x01)
        )
        tail.writeCharacteristic(
            CharacteristicUuids.LED_CMD,
            LedCommands.setEffectParam(layer = 1, paramId = 1, value = 200f)
        )

        val led = LedStateParser.parse(tail.readCharacteristic(CharacteristicUuids.LED_STATE)!!)!!
        assertEquals(2, led.layers.size)
        assertEquals(0x01.toByte(), led.layers[1].effectId)
        assertEquals(200f, led.layers[1].params[1], 0.001f)
    }

    @Test
    fun `every write is acknowledged OK on FF09`() = runTest {
        val tail = connected()
        var lastAck: ByteArray? = null
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            tail.characteristicUpdate.collect {
                if (it.uuid == CharacteristicUuids.CMD_RESULT) lastAck = it.value
            }
        }

        tail.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, matrix(6, 6, 6))

        assertNotNull("expected an FF09 ACK", lastAck)
        assertEquals("FF06 source", 0x06.toByte(), lastAck!![0])
        assertEquals("OK result", 0x00.toByte(), lastAck!![2])
        job.cancel()
    }

    @Test
    fun `set matrix changes the layout`() = runTest {
        val tail = connected()

        tail.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, matrix(4, 6, 4))

        val led = LedStateParser.parse(tail.readCharacteristic(CharacteristicUuids.LED_STATE)!!)!!
        assertEquals(listOf(4, 6, 4), led.ledsPerRing)
    }

    @Test
    fun `saving then loading a profile restores the config and signals a change`() = runTest {
        val tail = connected()
        var configChanged = false
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            tail.characteristicUpdate.collect {
                if (it.uuid == CharacteristicUuids.SYSTEM_EVENTS && it.value.firstOrNull() == 0x03.toByte()) {
                    configChanged = true
                }
            }
        }

        tail.writeCharacteristic(CharacteristicUuids.PROFILE_MGMT, byteArrayOf(0x01, 0x00)) // save slot 0
        tail.writeCharacteristic(CharacteristicUuids.SYSTEM_CONFIG, matrix(3, 3))            // change live
        tail.writeCharacteristic(CharacteristicUuids.PROFILE_MGMT, byteArrayOf(0x02, 0x00)) // load slot 0

        val led = LedStateParser.parse(tail.readCharacteristic(CharacteristicUuids.LED_STATE)!!)!!
        assertEquals("the saved matrix should be restored", listOf(8, 10, 12, 10, 8), led.ledsPerRing)
        assertTrue("a load must raise CONFIG_CHANGED", configChanged)

        val profiles = ProfileListParser.parse(tail.readCharacteristic(CharacteristicUuids.PROFILE_MGMT)!!)
        assertTrue("slot 0 should be occupied", profiles[0].occupied)
        job.cancel()
    }

    @Test
    fun `a reconnect after direct mode still parses`() = runTest {
        val tail = connected()
        tail.writeCharacteristic(CharacteristicUuids.LED_CMD, LedCommands.setDirectMode(true))
        tail.disconnect()
        tail.connect(VirtualTailTransport.ADDRESS)
        assertNotNull(LedStateParser.parse(tail.readCharacteristic(CharacteristicUuids.LED_STATE)!!))
    }
}
