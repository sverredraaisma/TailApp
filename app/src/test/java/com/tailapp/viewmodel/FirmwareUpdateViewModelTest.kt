package com.tailapp.viewmodel

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResultCode
import com.tailapp.ble.protocol.FirmwareImage
import com.tailapp.model.FirmwareVersion
import com.tailapp.model.OtaTransferState
import com.tailapp.repository.DeviceRepository
import com.tailapp.repository.FirmwareUpdateResult
import com.tailapp.repository.OtaStep
import com.tailapp.repository.OtaTransferPolicy
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FirmwarePayloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareUpdateViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private var repositoryScope: CoroutineScope? = null

    @Before
    fun installMainDispatcher() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        repositoryScope?.cancel()
        repositoryScope = null
        Dispatchers.resetMain()
    }

    private fun TestScope.newRepository(
        transport: FakeBleTransport,
        systemInfo: ByteArray = FirmwarePayloads.systemInfo(),
        policy: OtaTransferPolicy = OtaTransferPolicy()
    ): DeviceRepository {
        repositoryScope?.cancel()
        transport.readResponses[CharacteristicUuids.MOTION_STATE] = FirmwarePayloads.motionState()
        transport.readResponses[CharacteristicUuids.LED_STATE] = FirmwarePayloads.ledState()
        transport.readResponses[CharacteristicUuids.SYSTEM_CONFIG] = systemInfo
        transport.readResponses[CharacteristicUuids.PROFILE_MGMT] =
            FirmwarePayloads.profileList(List(4) { false to null })
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        repositoryScope = scope
        val repository = DeviceRepository(transport, scope, otaPolicy = policy)
        advanceUntilIdle()
        transport.setConnectionState(ConnectionState.CONNECTED)
        advanceUntilIdle()
        transport.clearTraffic()
        return repository
    }

    /** A file that passes every client-side check, so an install always reaches the device. */
    private fun goodImage(size: Int = 1400) =
        FirmwarePayloads.firmwareImage(version = "2.0.0", sizeBytes = size)

    // ── Rejection-code-to-message mapping ──────────────────────────────

    @Test
    fun `every OTA rejection code is surfaced as its own message`() = runTest {
        // The codes a real BEGIN/FINALIZE can return. Each has a distinct,
        // actionable message; forcing it as the BEGIN verdict is the smallest way
        // to drive the whole mapping through the view model.
        val codes = listOf(
            CommandResultCode.BAD_STATE,
            CommandResultCode.OUT_OF_RANGE,
            CommandResultCode.OTA_BAD_IMAGE,
            CommandResultCode.OTA_WRONG_PROJECT,
            CommandResultCode.OTA_SAME_VERSION,
            CommandResultCode.OTA_FLASH_ERROR
        )

        val messages = codes.map { code ->
            val transport = FakeBleTransport()
            val repository = newRepository(transport)
            val viewModel = FirmwareUpdateViewModel(repository)
            viewModel.onImagePicked("firmware.bin", goodImage())
            transport.ackResults.addLast(code.code.toInt() and 0xFF)

            viewModel.install()
            advanceUntilIdle()

            val finished = viewModel.transfer.value as FirmwareTransfer.Finished
            // The view model shows exactly the repository's per-code verdict, so
            // the mapping stays in one place rather than being re-derived here.
            assertEquals(
                FirmwareUpdateResult.Rejected(OtaStep.BEGIN, code).message,
                finished.message
            )
            assertFalse(finished.succeeded)
            assertTrue(finished.message.isNotBlank())
            finished.message
        }

        assertEquals("each code must read differently", codes.size, messages.toSet().size)
    }

    // ── Versions and pending-verify ────────────────────────────────────

    @Test
    fun `the running, other-slot and pending-verify state are exposed from FF06`() = runTest {
        val transport = FakeBleTransport()
        val repository = newRepository(
            transport,
            systemInfo = FirmwarePayloads.systemInfo(
                deviceName = "Tail",
                ota = FirmwarePayloads.OtaBlock(
                    running = Triple(1, 4, 2),
                    pendingVerify = true,
                    other = Triple(1, 5, 0)
                )
            )
        )
        val viewModel = FirmwareUpdateViewModel(repository)
        advanceUntilIdle()

        val ota = requireNotNull(viewModel.otaInfo.value)
        assertEquals(FirmwareVersion(1, 4, 2), ota.running)
        assertEquals(FirmwareVersion(1, 5, 0), ota.other)
        assertTrue(ota.pendingVerify)
    }

    // ── Progress ───────────────────────────────────────────────────────

    @Test
    fun `install reports the device's echoed progress and then installs`() = runTest {
        val transport = FakeBleTransport()
        val repository = newRepository(transport)
        // A clock that ticks a fixed step per read, so the elapsed figure is fixed.
        var clock = 0L
        val viewModel = FirmwareUpdateViewModel(repository) { clock += 1000; clock }
        viewModel.onImagePicked("firmware.bin", goodImage(1400))

        viewModel.install()
        // install() sets the initial Active state synchronously, before launching.
        assertEquals(FirmwareTransfer.Active(0, 1400, 0L), viewModel.transfer.value)
        // runCurrent, not advanceUntilIdle: the transfer waits on an echo behind a
        // timeout, and advancing time would fire it before the echo is fed.
        runCurrent()

        transport.notify(
            CharacteristicUuids.OTA_DATA,
            FirmwarePayloads.otaStatus(700, OtaTransferState.RECEIVING.code)
        )
        runCurrent()
        val mid = viewModel.transfer.value as FirmwareTransfer.Active
        assertEquals(700, mid.accepted)
        assertEquals(1400, mid.total)

        transport.notify(
            CharacteristicUuids.OTA_DATA,
            FirmwarePayloads.otaStatus(1400, OtaTransferState.READY.code)
        )
        runCurrent()

        assertEquals(
            FirmwareTransfer.Finished(FirmwareUpdateResult.Installed),
            viewModel.transfer.value
        )
    }

    // ── Abort ──────────────────────────────────────────────────────────

    @Test
    fun `cancel moves to a cancelled verdict and aborts on the device`() = runTest {
        val transport = FakeBleTransport()
        // A narrow window so the transfer is still in flight when cancelled.
        val repository = newRepository(transport, policy = OtaTransferPolicy(windowBytes = 1024))
        val viewModel = FirmwareUpdateViewModel(repository)
        viewModel.onImagePicked("firmware.bin", goodImage(3000))

        viewModel.install()
        runCurrent()
        assertTrue(viewModel.transfer.value is FirmwareTransfer.Active)

        viewModel.cancel()
        advanceUntilIdle()

        val finished = viewModel.transfer.value as FirmwareTransfer.Finished
        assertTrue(finished.result is FirmwareUpdateResult.Aborted)
        assertTrue(finished.message.contains("cancelled"))
        // Cancelling has to un-arm the tail: ABORT is 0x0A on FF06.
        assertTrue(
            transport.writesTo(CharacteristicUuids.SYSTEM_CONFIG).any { it.contentEquals(byteArrayOf(0x0A)) }
        )
    }

    // ── Pre-flight file check ──────────────────────────────────────────

    @Test
    fun `a file the device would reject is blocked before any byte is sent`() = runTest {
        val transport = FakeBleTransport()
        val repository = newRepository(transport)
        val viewModel = FirmwareUpdateViewModel(repository)
        // Right shape, wrong project — the device would answer OTA_WRONG_PROJECT.
        viewModel.onImagePicked(
            "someone-else.bin",
            FirmwarePayloads.firmwareImage(version = "2.0.0", projectName = "SomeoneElse")
        )

        val selection = requireNotNull(viewModel.selection.value)
        assertFalse(selection.installable)
        assertTrue(selection.blocker!!.contains("SomeoneElse"))

        viewModel.install()
        advanceUntilIdle()

        assertEquals(FirmwareTransfer.Idle, viewModel.transfer.value)
        assertTrue(
            "nothing may go to FF06 for a blocked file",
            transport.writesTo(CharacteristicUuids.SYSTEM_CONFIG).isEmpty()
        )
    }

    // ── Pure helpers ───────────────────────────────────────────────────

    @Test
    fun `the pre-flight check names each rejection the device would make`() {
        val running = FirmwareVersion(1, 0, 0)

        assertTrue(
            FirmwareUpdateViewModel.evaluate("tiny", ByteArray(10), running).blocker!!.contains("smaller")
        )
        assertTrue(
            FirmwareUpdateViewModel.evaluate("garbage", ByteArray(400), running).blocker!!.contains("not an application image")
        )
        val wrongProject = FirmwareImage.describe(
            FirmwarePayloads.firmwareImage(version = "2.0.0", projectName = "Nope")
        )
        assertEquals("Nope", wrongProject?.projectName)
        assertTrue(
            FirmwareUpdateViewModel.evaluate(
                "x", FirmwarePayloads.firmwareImage(version = "2.0.0", projectName = "Nope"), running
            ).blocker!!.contains("Nope")
        )
        assertTrue(
            FirmwareUpdateViewModel.evaluate(
                "same", FirmwarePayloads.firmwareImage(version = "1.0.0"), running
            ).blocker!!.contains("already running")
        )
        // A well-formed, newer image for this project has nothing to fault.
        assertNull(
            FirmwareUpdateViewModel.evaluate(
                "good", FirmwarePayloads.firmwareImage(version = "2.0.0"), running
            ).blocker
        )
    }

    @Test
    fun `the time estimate extrapolates from the rate so far`() {
        // No data yet: nothing to extrapolate from.
        assertNull(FirmwareUpdateViewModel.estimateRemainingMs(accepted = 0, total = 1000, elapsedMs = 500))
        // Half sent in 500 ms → about 500 ms to go.
        assertEquals(500L, FirmwareUpdateViewModel.estimateRemainingMs(500, 1000, 500))
        // A quarter sent in 500 ms → three more quarters, ~1500 ms.
        assertEquals(1500L, FirmwareUpdateViewModel.estimateRemainingMs(250, 1000, 500))
        // Finished: no time remains.
        assertNull(FirmwareUpdateViewModel.estimateRemainingMs(1000, 1000, 500))
    }
}
