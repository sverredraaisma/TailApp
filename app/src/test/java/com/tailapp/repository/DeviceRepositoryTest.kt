package com.tailapp.repository

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResultCode
import com.tailapp.ble.protocol.Crc32
import com.tailapp.ble.protocol.Protocol
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.model.BatteryPolicy
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
                CharacteristicUuids.CMD_RESULT,
                CharacteristicUuids.BATTERY_LEVEL,
                // FF0E notifies the OTA offset echo, the transfer's only flow
                // control, so the subscription is live for the whole session
                // rather than opened per update.
                CharacteristicUuids.OTA_DATA
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

    // ── Direct pixel streaming (FF0A) ──────────────────────────────

    @Test
    fun `setDirectMode writes FF03 0x09 and flips the state flag`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.clearTraffic()
        assertFalse(repository.deviceState.value.directModeActive)

        val ok = repository.setDirectMode(true)
        advanceUntilIdle()

        assertTrue(ok)
        assertArrayEquals(byteArrayOf(0x09, 0x01), transport.writesTo(CharacteristicUuids.LED_CMD).single())
        assertTrue(repository.deviceState.value.directModeActive)

        transport.clearTraffic()
        repository.setDirectMode(false)
        advanceUntilIdle()

        assertArrayEquals(byteArrayOf(0x09, 0x00), transport.writesTo(CharacteristicUuids.LED_CMD).single())
        assertFalse(repository.deviceState.value.directModeActive)
    }

    @Test
    fun `setDirectMode does not flip the flag when the write fails`() = runTest {
        val transport = FakeBleTransport().apply { writeResult = false }
        val repository = connected(transport)

        val ok = repository.setDirectMode(true)
        advanceUntilIdle()

        assertFalse(ok)
        assertFalse(repository.deviceState.value.directModeActive)
    }

    @Test
    fun `disconnect clears directModeActive`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        repository.setDirectMode(true)
        advanceUntilIdle()
        assertTrue(repository.deviceState.value.directModeActive)

        transport.setConnectionState(ConnectionState.DISCONNECTED)
        advanceUntilIdle()

        assertFalse(repository.deviceState.value.directModeActive)
    }

    @Test
    fun `streamDirectFrame sends a single write when the frame fits one packet`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.setMtu(23) // maxLedsPerPacket(23) == 6
        transport.clearTraffic()

        val rgb = ByteArray(6 * 3) { it.toByte() }
        repository.streamDirectFrame(rgb, ledCount = 6, startIndex = 10)
        advanceUntilIdle()

        val writes = transport.writesWithoutResponse.filter { it.uuid == CharacteristicUuids.LED_DIRECT }
        assertEquals(1, writes.size)
        val packet = writes.single().data
        assertEquals(10, ByteBuffer.wrap(packet, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF)
        assertArrayEquals(rgb, packet.copyOfRange(2, packet.size))
        // Never on the mutex-guarded write path — that would stall the hot path.
        assertTrue(transport.writesTo(CharacteristicUuids.LED_DIRECT).isEmpty())
    }

    @Test
    fun `streamDirectFrame splits a larger frame with increasing start indices and no lost or duplicated pixels`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.setMtu(23) // maxLedsPerPacket(23) == 6
        transport.clearTraffic()

        val ledCount = 14 // 6 + 6 + 2 across three packets at this MTU
        val rgb = ByteArray(ledCount * 3) { it.toByte() }
        repository.streamDirectFrame(rgb, ledCount = ledCount, startIndex = 100)
        advanceUntilIdle()

        val writes = transport.writesWithoutResponse.filter { it.uuid == CharacteristicUuids.LED_DIRECT }
        assertEquals(3, writes.size)

        val expectedStartsAndCounts = listOf(100 to 6, 106 to 6, 112 to 2)
        val reassembled = ByteArray(rgb.size)
        writes.forEachIndexed { i, write ->
            val (expectedStart, expectedCount) = expectedStartsAndCounts[i]
            val start = ByteBuffer.wrap(write.data, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            assertEquals(expectedStart, start)
            assertEquals(2 + expectedCount * 3, write.data.size)

            val pixelOffset = (start - 100) * 3
            write.data.copyOfRange(2, write.data.size).copyInto(reassembled, pixelOffset)
        }
        assertArrayEquals(rgb, reassembled)
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
        assertEquals(5, Protocol.SUPPORTED_PROTOCOL_VERSION)
    }

    // ── Stall handling ─────────────────────────────────────────────

    @Test
    fun `a stall event marks the motors off without waiting for a refresh`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        assertFalse(requireNotNull(repository.deviceState.value.systemInfo).motorsStalled)

        transport.notify(CharacteristicUuids.SYSTEM_EVENTS, byteArrayOf(SystemEvent.STALL.code))
        advanceUntilIdle()

        // The FF06 read only refreshes about once a second. Waiting for it would
        // leave the tail visibly dead with the UI still claiming all is well.
        assertTrue(requireNotNull(repository.deviceState.value.systemInfo).motorsStalled)
    }

    @Test
    fun `re-enabling the motors clears the stall immediately`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        transport.notify(CharacteristicUuids.SYSTEM_EVENTS, byteArrayOf(SystemEvent.STALL.code))
        advanceUntilIdle()

        repository.setMotorsEnabled(true)
        advanceUntilIdle()

        val write = transport.writes.last { it.uuid == CharacteristicUuids.MOTION_CMD }
        assertArrayEquals(byteArrayOf(0x09, 0x01), write.data)
        // Optimistic: the banner has to go away when the user acts on it, not a
        // second later when the device gets around to saying so.
        assertFalse(requireNotNull(repository.deviceState.value.systemInfo).motorsStalled)
    }

    @Test
    fun `setting motion limits writes the command and updates the cached block`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        repository.setMotionLimits(1, 480f, 2400f, 24000f, 75)
        advanceUntilIdle()

        val write = transport.writes.last { it.uuid == CharacteristicUuids.MOTION_CMD }
        assertEquals(0x08.toByte(), write.data[0])
        assertEquals(0x01.toByte(), write.data[1])

        val limits = requireNotNull(repository.deviceState.value.systemInfo?.motion).limits
        assertEquals(480f, limits[1].maxVelocity, 0f)
        assertEquals(75, limits[1].stallThreshold)
        // Only the addressed motor moves.
        assertEquals(720f, limits[0].maxVelocity, 0f)
    }

    @Test
    fun `a stall against firmware without a motion block does not fabricate one`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport) {
            seedDefaultReads(systemInfo = FirmwarePayloads.systemInfo(motion = null))
        }

        transport.notify(CharacteristicUuids.SYSTEM_EVENTS, byteArrayOf(SystemEvent.STALL.code))
        advanceUntilIdle()

        // Nothing to update, and inventing a motion block would claim limits the
        // device never reported.
        assertNull(repository.deviceState.value.systemInfo?.motion)
    }

    // ── Battery, identity and bonds (A5-2) ─────────────────────────

    @Test
    fun `connect reads the battery level and the device information strings`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport) {
            seedDefaultReads()
            readResponses[CharacteristicUuids.BATTERY_LEVEL] = FirmwarePayloads.batteryLevel(78)
            readResponses[CharacteristicUuids.DIS_MANUFACTURER] = "TailWorks".toByteArray()
            readResponses[CharacteristicUuids.DIS_MODEL_NUMBER] = "TC-1".toByteArray()
            readResponses[CharacteristicUuids.DIS_FIRMWARE_REV] = "1.0.0".toByteArray()
            readResponses[CharacteristicUuids.DIS_HARDWARE_REV] = "revB".toByteArray()
        }

        val state = repository.deviceState.value
        assertEquals(78, state.battery.percent)
        assertTrue(state.battery.isKnown)
        val info = requireNotNull(state.deviceInformation)
        assertEquals("TailWorks", info.manufacturer)
        assertEquals("TC-1", info.modelNumber)
        assertEquals("1.0.0", info.firmwareRevision)
        assertEquals("revB", info.hardwareRevision)
    }

    @Test
    fun `an unknown battery level stays distinguishable from a flat pack`() = runTest {
        val transport = FakeBleTransport()
        val unknown = connected(transport) {
            seedDefaultReads()
            readResponses[CharacteristicUuids.BATTERY_LEVEL] = FirmwarePayloads.batteryLevel(null)
        }
        assertNull(unknown.deviceState.value.battery.percent)
        assertFalse(unknown.deviceState.value.battery.isKnown)
        cancelRepositoryScope()

        val flatTransport = FakeBleTransport()
        val flat = connected(flatTransport) {
            seedDefaultReads()
            readResponses[CharacteristicUuids.BATTERY_LEVEL] = FirmwarePayloads.batteryLevel(0)
        }
        // A board with no sense divider and a pack about to die must never render
        // the same way, which is the whole reason 0xFF exists.
        assertEquals(0, flat.deviceState.value.battery.percent)
        assertTrue(flat.deviceState.value.battery.isKnown)
    }

    @Test
    fun `battery level notifications update the cached percentage`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport) {
            seedDefaultReads()
            readResponses[CharacteristicUuids.BATTERY_LEVEL] = FirmwarePayloads.batteryLevel(78)
        }

        transport.notify(CharacteristicUuids.BATTERY_LEVEL, FirmwarePayloads.batteryLevel(41))
        advanceUntilIdle()
        assertEquals(41, repository.deviceState.value.battery.percent)

        transport.notify(CharacteristicUuids.BATTERY_LEVEL, FirmwarePayloads.batteryLevel(null))
        advanceUntilIdle()
        assertNull(repository.deviceState.value.battery.percent)
    }

    @Test
    fun `the battery policy events drive the policy state`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        assertNull(repository.deviceState.value.battery.policy)

        transport.notify(
            CharacteristicUuids.SYSTEM_EVENTS,
            byteArrayOf(SystemEvent.BATTERY_LOW.code)
        )
        advanceUntilIdle()
        assertEquals(BatteryPolicy.LOW, repository.deviceState.value.battery.policy)

        transport.notify(
            CharacteristicUuids.SYSTEM_EVENTS,
            byteArrayOf(SystemEvent.BATTERY_CRITICAL.code)
        )
        advanceUntilIdle()
        assertEquals(BatteryPolicy.CRITICAL, repository.deviceState.value.battery.policy)
        assertTrue(repository.deviceState.value.battery.isDerated)

        transport.notify(
            CharacteristicUuids.SYSTEM_EVENTS,
            byteArrayOf(SystemEvent.BATTERY_NORMAL.code)
        )
        advanceUntilIdle()
        assertEquals(BatteryPolicy.NORMAL, repository.deviceState.value.battery.policy)
        assertFalse(repository.deviceState.value.battery.isDerated)
    }

    @Test
    fun `the policy is recovered from the event ring on connect`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport) {
            seedDefaultReads()
            // The crossing happened before this connection existed. Nothing
            // repeats it, so the ring is the only way to find out.
            readResponses[CharacteristicUuids.SYSTEM_EVENTS] =
                FirmwarePayloads.eventLog(listOf(0x01, 0x0D, 0x04, 0x0E))
        }

        assertEquals(BatteryPolicy.CRITICAL, repository.deviceState.value.battery.policy)
    }

    @Test
    fun `the event ring is not replayed as live events`() = runTest {
        val transport = FakeBleTransport()
        val events = mutableListOf<SystemEvent>()
        val repository = connected(transport) {
            seedDefaultReads()
            readResponses[CharacteristicUuids.SYSTEM_EVENTS] =
                FirmwarePayloads.eventLog(listOf(0x01, 0x02, 0x0D))
        }
        val collector = repositoryScope!!.launch { repository.systemEvents.collect(events::add) }
        advanceUntilIdle()

        // Taps and stalls in the ring already happened. Re-emitting them would
        // fire ripples and banners for history.
        assertTrue(events.isEmpty())
        assertEquals(BatteryPolicy.LOW, repository.deviceState.value.battery.policy)
        collector.cancel()
    }

    @Test
    fun `the bond list and device name arrive with the FF06 read`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport) {
            seedDefaultReads(
                systemInfo = FirmwarePayloads.systemInfo(
                    deviceName = "Foxtail",
                    bonds = listOf(FirmwarePayloads.BondRecord())
                )
            )
        }

        val info = requireNotNull(repository.deviceState.value.systemInfo)
        assertEquals("Foxtail", info.deviceName)
        assertEquals(1, requireNotNull(info.bonds).size)
    }

    @Test
    fun `renaming the device writes the command and reports acceptance`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        val outcome = repository.setDeviceName("Foxtail")
        advanceUntilIdle()

        val write = transport.writes.last { it.uuid == CharacteristicUuids.SYSTEM_CONFIG }
        assertArrayEquals(byteArrayOf(0x04) + "Foxtail".toByteArray(Charsets.UTF_8), write.data)
        assertTrue(outcome.accepted)
    }

    @Test
    fun `a forget-bond rejection is reported instead of assumed successful`() = runTest {
        val transport = FakeBleTransport().apply { ackResults.addLast(0x04) } // OUT_OF_RANGE
        val repository = connected(transport)

        val outcome = repository.forgetBond(3)
        advanceUntilIdle()

        val write = transport.writes.last { it.uuid == CharacteristicUuids.SYSTEM_CONFIG }
        assertArrayEquals(byteArrayOf(0x05, 0x03), write.data)
        // The app's copy of the bond list can be a second old, so an index the
        // device no longer has is refused — and reporting success would tell the
        // user they unpaired a phone that can still drive the tail.
        assertTrue(outcome.rejected)
        assertFalse(outcome.accepted)
        assertEquals(CommandResultCode.OUT_OF_RANGE, outcome.result?.result)
    }

    @Test
    fun `forgetting all bonds uses the device's own all-bonds index`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        repository.forgetAllBonds()
        advanceUntilIdle()

        val write = transport.writes.last { it.uuid == CharacteristicUuids.SYSTEM_CONFIG }
        assertArrayEquals(byteArrayOf(0x05, Protocol.BOND_INDEX_ALL), write.data)
    }

    @Test
    fun `an accepted bond change re-reads FF06 after the device rebuilds it`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.clearTraffic()

        assertTrue(repository.forgetBond(0).accepted)

        // The device rebuilds its read buffers once a second; an immediate
        // re-read would return the bond list exactly as it was.
        assertEquals(0, transport.readCountFor(CharacteristicUuids.SYSTEM_CONFIG))

        advanceTimeBy(DeviceRepository.PROFILE_RECONCILE_DELAY_MS + 1)
        advanceUntilIdle()
        assertEquals(1, transport.readCountFor(CharacteristicUuids.SYSTEM_CONFIG))
    }
}
