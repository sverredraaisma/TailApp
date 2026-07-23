package com.tailapp.repository

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResultCode
import com.tailapp.ble.protocol.Crc32
import com.tailapp.ble.protocol.Protocol
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.model.Capabilities
import com.tailapp.model.LedEffect
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FirmwarePayloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRepositoryTest {

    /**
     * The repository's long-lived collectors live here rather than in `runTest`'s
     * `backgroundScope`: `advanceUntilIdle()` stops as soon as no *foreground*
     * work remains, so background-only coroutines would never be dispatched.
     */
    private var repositoryScope: CoroutineScope? = null

    @After
    fun cancelRepositoryScope() {
        repositoryScope?.cancel()
        repositoryScope = null
    }

    private fun FakeBleTransport.seedDefaultReads(
        ledLayers: List<com.tailapp.model.LayerConfig> = listOf(
            FirmwarePayloads.layer(LedEffect.RAINBOW.id)
        ),
        profiles: List<Pair<Boolean, String?>> = listOf(
            true to "Wag", false to null, false to null, false to null
        ),
        systemInfo: ByteArray = FirmwarePayloads.systemInfo()
    ) {
        readResponses[CharacteristicUuids.MOTION_STATE] = FirmwarePayloads.motionState()
        readResponses[CharacteristicUuids.LED_STATE] = FirmwarePayloads.ledState(layers = ledLayers)
        readResponses[CharacteristicUuids.SYSTEM_CONFIG] = systemInfo
        readResponses[CharacteristicUuids.PROFILE_MGMT] = FirmwarePayloads.profileList(profiles)
    }

    private fun TestScope.newRepository(transport: FakeBleTransport): DeviceRepository {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        repositoryScope = scope
        return DeviceRepository(transport, scope)
    }

    /** Brings a repository to the fully-synced CONNECTED state. */
    private fun TestScope.connected(
        transport: FakeBleTransport,
        configure: FakeBleTransport.() -> Unit = { seedDefaultReads() }
    ): DeviceRepository {
        transport.configure()
        val repository = newRepository(transport)
        advanceUntilIdle()
        transport.setConnectionState(ConnectionState.CONNECTED)
        advanceUntilIdle()
        return repository
    }

    // ── Connection setup ───────────────────────────────────────────

    @Test
    fun `connect negotiates MTU discovers services and subscribes to every notify characteristic`() = runTest {
        val transport = FakeBleTransport()
        connected(transport)

        assertEquals(517, transport.requestedMtu)
        assertEquals(
            listOf(
                CharacteristicUuids.MOTION_STATE,
                CharacteristicUuids.LED_STATE,
                CharacteristicUuids.SYSTEM_EVENTS,
                CharacteristicUuids.CMD_RESULT
            ),
            transport.enabledNotifications
        )
    }

    @Test
    fun `connect reads every readable characteristic including the profile list`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        assertEquals(1, transport.readCountFor(CharacteristicUuids.MOTION_STATE))
        assertEquals(1, transport.readCountFor(CharacteristicUuids.LED_STATE))
        assertEquals(1, transport.readCountFor(CharacteristicUuids.SYSTEM_CONFIG))
        assertEquals(1, transport.readCountFor(CharacteristicUuids.PROFILE_MGMT))

        val state = repository.deviceState.value
        assertNotNull(state.motionState)
        assertNotNull(state.ledState)
        assertNotNull(state.systemInfo)
        assertEquals(4, state.profiles.size)
        assertEquals("Wag", state.profiles[0].name)
    }

    @Test
    fun `system info parses with the protocol version byte in place`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        val info = requireNotNull(repository.deviceState.value.systemInfo)
        assertEquals(Protocol.SUPPORTED_PROTOCOL_VERSION, info.protocolVersion)
        assertEquals("1.0.0", info.firmwareVersion)
        assertEquals(4, info.servos.size)
        assertEquals(2, info.imus.size)
        assertTrue(info.isProtocolSupported)
    }

    @Test
    fun `failed service discovery aborts setup without reads`() = runTest {
        val transport = FakeBleTransport().apply { discoverServicesResult = false }
        connected(transport)

        assertTrue(transport.readLog.isEmpty())
        assertTrue(transport.enabledNotifications.isEmpty())
    }

    @Test
    fun `a disconnect during setup reaches the state flow immediately`() = runTest {
        // Regression: setup used to run inline in the connection-state collector,
        // so a drop mid-setup stayed invisible to the UI until the four initial
        // reads had all timed out.
        val transport = FakeBleTransport().apply {
            seedDefaultReads()
            readDelayMs = 5_000
        }
        val repository = newRepository(transport)
        advanceUntilIdle()

        transport.setConnectionState(ConnectionState.CONNECTED)
        advanceTimeBy(1) // let setup start and block on the first slow read
        assertEquals(ConnectionState.CONNECTED, repository.deviceState.value.connectionState)

        transport.setConnectionState(ConnectionState.DISCONNECTED)
        advanceTimeBy(10) // far less than one read; setup is still in flight

        assertEquals(ConnectionState.DISCONNECTED, repository.deviceState.value.connectionState)
        assertNull(repository.deviceState.value.systemInfo)

        // The abandoned setup must not resurrect state after the drop.
        advanceUntilIdle()
        assertNull(repository.deviceState.value.systemInfo)
    }

    @Test
    fun `disconnect clears cached device state`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        assertNotNull(repository.deviceState.value.ledState)

        transport.setConnectionState(ConnectionState.DISCONNECTED)
        advanceUntilIdle()

        val state = repository.deviceState.value
        assertEquals(ConnectionState.DISCONNECTED, state.connectionState)
        assertNull(state.ledState)
        assertNull(state.motionState)
        assertNull(state.systemInfo)
        assertTrue(state.profiles.isEmpty())
    }

    // ── Notifications ──────────────────────────────────────────────

    @Test
    fun `motion state notifications update the cached state`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        transport.notify(
            CharacteristicUuids.MOTION_STATE,
            FirmwarePayloads.motionState(patternId = 0x02, encoders = listOf(11f, 12f, 13f, 14f))
        )
        advanceUntilIdle()

        val motion = requireNotNull(repository.deviceState.value.motionState)
        assertEquals(0x02.toByte(), motion.activePatternId)
        assertEquals(listOf(11f, 12f, 13f, 14f), motion.encoderPositions)
    }

    @Test
    fun `a rejected command surfaces on the result flow and in state`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        val received = mutableListOf<com.tailapp.ble.protocol.CommandResult>()
        requireNotNull(repositoryScope).launch { repository.commandResults.collect { received.add(it) } }
        advanceUntilIdle()

        transport.notify(
            CharacteristicUuids.CMD_RESULT,
            FirmwarePayloads.commandResult(0x03, 0x01, 0x03) // FF03 set-layer, UNKNOWN_ID
        )
        advanceUntilIdle()

        val last = requireNotNull(repository.deviceState.value.lastCommandResult)
        assertEquals(CommandResultCode.UNKNOWN_ID, last.result)
        assertEquals("FF03", last.characteristicName)
        assertFalse(last.isSuccess)
        assertEquals(1, received.size)
    }

    @Test
    fun `tap events reach the event flow without touching device state`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        val events = mutableListOf<SystemEvent>()
        requireNotNull(repositoryScope).launch { repository.systemEvents.collect { events.add(it) } }
        advanceUntilIdle()
        transport.clearTraffic()

        transport.notify(CharacteristicUuids.SYSTEM_EVENTS, byteArrayOf(0x01))
        transport.notify(CharacteristicUuids.SYSTEM_EVENTS, byteArrayOf(0x02))
        advanceUntilIdle()

        assertEquals(listOf(SystemEvent.TAP_BASE, SystemEvent.TAP_TIP), events)
        assertTrue(transport.readLog.isEmpty())
    }

    @Test
    fun `a config-changed event re-reads every characteristic`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.clearTraffic()

        // A profile load on the device replaces the whole config.
        transport.readResponses[CharacteristicUuids.SYSTEM_CONFIG] =
            FirmwarePayloads.systemInfo(firmwareMinor = 4)
        transport.notify(CharacteristicUuids.SYSTEM_EVENTS, byteArrayOf(0x03))
        advanceUntilIdle()

        assertEquals(1, transport.readCountFor(CharacteristicUuids.MOTION_STATE))
        assertEquals(1, transport.readCountFor(CharacteristicUuids.LED_STATE))
        assertEquals(1, transport.readCountFor(CharacteristicUuids.SYSTEM_CONFIG))
        assertEquals(1, transport.readCountFor(CharacteristicUuids.PROFILE_MGMT))
        assertEquals("1.4.0", requireNotNull(repository.deviceState.value.systemInfo).firmwareVersion)
    }

    @Test
    fun `malformed notifications are ignored`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        val before = repository.deviceState.value

        transport.notify(CharacteristicUuids.MOTION_STATE, ByteArray(10))
        transport.notify(CharacteristicUuids.LED_STATE, byteArrayOf(9, 1))
        transport.notify(CharacteristicUuids.CMD_RESULT, byteArrayOf(1))
        transport.notify(CharacteristicUuids.SYSTEM_EVENTS, ByteArray(0))
        advanceUntilIdle()

        assertEquals(before, repository.deviceState.value)
    }

    // ── Motion commands ────────────────────────────────────────────

    @Test
    fun `setServoConfig sends the mux channel and updates the cached servo`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.clearTraffic()

        repository.setServoConfig(servoId = 1, axis = 1, half = 0, invert = 1, muxChannel = 6)
        advanceUntilIdle()

        assertArrayEquals(
            byteArrayOf(0x03, 0x01, 0x01, 0x00, 0x01, 0x06),
            transport.writesTo(CharacteristicUuids.MOTION_CMD).single()
        )
        val servo = requireNotNull(repository.deviceState.value.systemInfo).servos[1]
        assertEquals(1, servo.axis)
        assertEquals(0, servo.half)
        assertTrue(servo.invert)
        assertEquals(6, servo.muxChannel)
    }

    @Test
    fun `setServoConfig without a mux channel keeps the cached one`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.clearTraffic()

        repository.setServoConfig(servoId = 1, axis = 0, half = 0, invert = 0)
        advanceUntilIdle()

        assertEquals(5, transport.writesTo(CharacteristicUuids.MOTION_CMD).single().size)
        assertEquals(1, requireNotNull(repository.deviceState.value.systemInfo).servos[1].muxChannel)
    }

    @Test
    fun `setPidGains updates the cached gains so sliders do not snap back`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        repository.setPidGains(servoId = 2, kp = 5f, ki = 0.4f, kd = 1f)
        advanceUntilIdle()

        val pid = requireNotNull(repository.deviceState.value.systemInfo).servos[2].pid
        assertEquals(5f, pid.kp, 0f)
        assertEquals(0.4f, pid.ki, 1e-6f)
        assertEquals(1f, pid.kd, 0f)
    }

    @Test
    fun `selecting a pattern clears the cached params like the firmware does`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        assertTrue(requireNotNull(repository.deviceState.value.motionState).params.any { it != 0f })

        repository.selectPattern(0x02)
        advanceUntilIdle()

        val motion = requireNotNull(repository.deviceState.value.motionState)
        assertEquals(0x02.toByte(), motion.activePatternId)
        assertTrue(motion.params.all { it == 0f })
    }

    @Test
    fun `setAxisLimits updates only the addressed axis`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        repository.setAxisLimits(axis = 1, min = -10f, max = 20f)
        advanceUntilIdle()

        val motion = requireNotNull(repository.deviceState.value.motionState)
        assertEquals(-10f, motion.yAxisMin, 0f)
        assertEquals(20f, motion.yAxisMax, 0f)
        assertEquals(-90f, motion.xAxisMin, 0f)
        assertEquals(90f, motion.xAxisMax, 0f)
    }

    @Test
    fun `setImuTap updates the cached imu`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        repository.setImuTap(imuId = 1, enabled = true)
        advanceUntilIdle()

        assertTrue(requireNotNull(repository.deviceState.value.systemInfo).imus[1].tapEnabled)
    }

    @Test
    fun `commands for an out-of-range index do not corrupt the cache`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        val before = repository.deviceState.value.systemInfo

        repository.setPidGains(servoId = 9, kp = 1f, ki = 1f, kd = 1f)
        repository.setImuTap(imuId = 9, enabled = true)
        advanceUntilIdle()

        assertEquals(before, repository.deviceState.value.systemInfo)
    }

    // ── LED commands ───────────────────────────────────────────────

    @Test
    fun `removeLayer clears the slot in place instead of shifting indices`() = runTest {
        // The firmware stamps effect_id = 0xFF and keeps num_layers; shifting here
        // would silently retarget every later edit at the wrong layer.
        val transport = FakeBleTransport()
        val repository = connected(transport) {
            seedDefaultReads(
                ledLayers = listOf(
                    FirmwarePayloads.layer(LedEffect.RAINBOW.id),
                    FirmwarePayloads.layer(LedEffect.STATIC_COLOR.id),
                    FirmwarePayloads.layer(LedEffect.IMAGE.id)
                )
            )
        }

        repository.removeLayer(0)
        advanceUntilIdle()

        val layers = requireNotNull(repository.deviceState.value.ledState).layers
        assertEquals(3, layers.size)
        assertTrue(layers[0].isEmpty)
        assertEquals(LedEffect.STATIC_COLOR, layers[1].effect)
        assertEquals(LedEffect.IMAGE, layers[2].effect)
        assertEquals(listOf(1, 2), requireNotNull(repository.deviceState.value.ledState).occupiedLayerIndices)
    }

    @Test
    fun `setLayerEffect resets params and grows the stack with empty slots`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport) {
            seedDefaultReads(
                ledLayers = listOf(
                    FirmwarePayloads.layer(LedEffect.RAINBOW.id, params = List(8) { 5f })
                )
            )
        }

        repository.setLayerEffect(layer = 2, effectId = LedEffect.IMAGE.id, blendMode = 0x05)
        advanceUntilIdle()

        val layers = requireNotNull(repository.deviceState.value.ledState).layers
        assertEquals(3, layers.size)
        assertTrue(layers[1].isEmpty)
        assertEquals(LedEffect.IMAGE, layers[2].effect)
        assertTrue(layers[2].params.all { it == 0f })
        assertTrue(layers[2].enabled)
    }

    @Test
    fun `setEffectParam updates only the addressed param`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        repository.setEffectParam(layer = 0, paramId = 1, value = 120f)
        advanceUntilIdle()

        val params = requireNotNull(repository.deviceState.value.ledState).layers[0].params
        assertEquals(120f, params[1], 0f)
        assertEquals(0f, params[0], 0f)
    }

    @Test
    fun `setLedMatrix clamps to the device ring limit`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport) {
            seedDefaultReads(
                systemInfo = FirmwarePayloads.systemInfo(
                    capabilities = Capabilities.DEFAULT.copy(maxLedRings = 4)
                )
            )
        }
        transport.clearTraffic()

        repository.setLedMatrix(List(10) { 6.toByte() })
        advanceUntilIdle()

        val written = transport.writesTo(CharacteristicUuids.SYSTEM_CONFIG).single()
        assertEquals(0x01.toByte(), written[0])
        assertEquals(4, written[1].toInt())
        assertEquals(6, written.size)
        assertEquals(listOf(6, 6, 6, 6), requireNotNull(repository.deviceState.value.ledState).ledsPerRing)
    }

    // ── Image upload ───────────────────────────────────────────────

    @Test
    fun `uploadImage sends BEGIN with the CRC then chunks then FINALIZE`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.clearTraffic()

        val rgb = ByteArray(3 * 4 * 4) { (it * 3).toByte() } // 4x4 RGB
        val progress = mutableListOf<Float>()
        val ok = repository.uploadImage(
            rgb = rgb, width = 4, height = 4, layer = 1, chunkSize = 20,
            onProgress = { progress.add(it) }
        )
        advanceUntilIdle()

        assertTrue(ok)
        val writes = transport.writesTo(CharacteristicUuids.LED_CMD)

        // BEGIN
        val begin = writes.first()
        assertEquals(0x08.toByte(), begin[0])
        assertEquals(rgb.size, ByteBuffer.wrap(begin, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF)
        assertEquals(
            Crc32.compute(rgb),
            ByteBuffer.wrap(begin, 3, 4).order(ByteOrder.LITTLE_ENDIAN).int
        )

        // FINALIZE
        assertArrayEquals(byteArrayOf(0x06, 4, 4, 1), writes.last())

        // Chunks reassemble to the original bytes, in order, with correct offsets.
        val chunks = writes.subList(1, writes.size - 1)
        assertEquals(3, chunks.size) // 48 bytes / 20 per chunk
        val reassembled = ByteArray(rgb.size)
        chunks.forEach { chunk ->
            assertEquals(0x05.toByte(), chunk[0])
            val offset = ByteBuffer.wrap(chunk, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            chunk.copyOfRange(3, chunk.size).copyInto(reassembled, offset)
        }
        assertArrayEquals(rgb, reassembled)

        assertEquals(listOf(1f), progress.takeLast(1))
    }

    @Test
    fun `uploadImage stops and reports failure when a write is rejected`() = runTest {
        val transport = FakeBleTransport().apply { writeResult = false }
        val repository = connected(transport)
        transport.clearTraffic()

        val ok = repository.uploadImage(
            rgb = ByteArray(48), width = 4, height = 4, layer = 0, chunkSize = 20
        )
        advanceUntilIdle()

        assertFalse(ok)
        // Only the BEGIN was attempted; no point streaming chunks the device drops.
        assertEquals(1, transport.writesTo(CharacteristicUuids.LED_CMD).size)
    }

    @Test
    fun `uploadImage rejects a non-positive chunk size`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        var threw = false
        try {
            repository.uploadImage(ByteArray(3), 1, 1, 0, chunkSize = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `uploadImage sends a single chunk when the image fits`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.clearTraffic()

        repository.uploadImage(ByteArray(12), 2, 2, 0, chunkSize = 200)
        advanceUntilIdle()

        assertEquals(3, transport.writesTo(CharacteristicUuids.LED_CMD).size) // begin + 1 chunk + finalize
    }

    // ── Profiles ───────────────────────────────────────────────────

    @Test
    fun `renameProfile sends the command and updates the slot optimistically`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.clearTraffic()

        repository.renameProfile(slot = 1, name = "Idle")
        // Deliberately no advanceUntilIdle: that would also run the delayed
        // reconcile read and overwrite the optimistic value under test.

        assertArrayEquals(
            byteArrayOf(0x05, 0x01) + "Idle".toByteArray(Charsets.UTF_8),
            transport.writesTo(CharacteristicUuids.PROFILE_MGMT).single()
        )
        assertEquals("Idle", repository.deviceState.value.profiles[1].name)
    }

    @Test
    fun `saveProfile marks the slot occupied then reconciles from the device`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.clearTraffic()

        repository.saveProfile(2)

        // Optimistic: the device only rebuilds its FF08 buffer once per second.
        assertTrue(repository.deviceState.value.profiles[2].occupied)
        assertEquals(0, transport.readCountFor(CharacteristicUuids.PROFILE_MGMT))

        transport.readResponses[CharacteristicUuids.PROFILE_MGMT] = FirmwarePayloads.profileList(
            listOf(true to "Wag", false to null, true to "Saved", false to null)
        )
        advanceTimeBy(DeviceRepository.PROFILE_RECONCILE_DELAY_MS + 1)
        advanceUntilIdle()

        assertEquals(1, transport.readCountFor(CharacteristicUuids.PROFILE_MGMT))
        assertEquals("Saved", repository.deviceState.value.profiles[2].name)
    }

    @Test
    fun `deleteProfile clears the slot optimistically`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        assertTrue(repository.deviceState.value.profiles[0].occupied)

        repository.deleteProfile(0)

        assertFalse(repository.deviceState.value.profiles[0].occupied)
        assertNull(repository.deviceState.value.profiles[0].name)
    }

    @Test
    fun `loadProfile re-reads everything`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.clearTraffic()

        repository.loadProfile(1)
        advanceUntilIdle()

        assertArrayEquals(byteArrayOf(0x02, 0x01), transport.writesTo(CharacteristicUuids.PROFILE_MGMT).single())
        assertEquals(1, transport.readCountFor(CharacteristicUuids.MOTION_STATE))
        assertEquals(1, transport.readCountFor(CharacteristicUuids.LED_STATE))
        assertEquals(1, transport.readCountFor(CharacteristicUuids.SYSTEM_CONFIG))
        assertEquals(1, transport.readCountFor(CharacteristicUuids.PROFILE_MGMT))
    }

    // ── FFT stream ─────────────────────────────────────────────────

    @Test
    fun `fft frames go out write-without-response on FF05`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        repository.sendFftFrame(200.toByte(), ByteArray(64) { 1 })
        advanceUntilIdle()

        val frame = transport.writesWithoutResponse.single()
        assertEquals(CharacteristicUuids.FFT_STREAM, frame.uuid)
        assertEquals(66, frame.data.size)
        // Never on the mutex-guarded write path — that would stall command writes.
        assertTrue(transport.writesTo(CharacteristicUuids.FFT_STREAM).isEmpty())
    }

    @Test
    fun `fft stream active flag is reflected in state`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        repository.setFftStreamActive(true)
        assertTrue(repository.deviceState.value.fftStreamActive)
        repository.setFftStreamActive(false)
        assertFalse(repository.deviceState.value.fftStreamActive)
    }

    // ── Protocol version ───────────────────────────────────────────

    @Test
    fun `a future protocol version is still parsed but flagged incompatible`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport) {
            seedDefaultReads(systemInfo = FirmwarePayloads.systemInfo(protocolVersion = 99))
        }

        val info = requireNotNull(repository.deviceState.value.systemInfo)
        assertEquals(99, info.protocolVersion)
        assertFalse(info.isProtocolSupported)
        assertEquals(3, Protocol.SUPPORTED_PROTOCOL_VERSION)
    }
}
