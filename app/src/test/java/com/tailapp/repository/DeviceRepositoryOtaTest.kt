package com.tailapp.repository

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResultCode
import com.tailapp.model.FirmwareVersion
import com.tailapp.model.OtaTransferState
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FirmwarePayloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The firmware transfer's flow control (A5-1): the offset echo is the only thing
 * a write-without-response stream has, so these assert the exact write offsets the
 * loop produces when the device's echoed offset jumps around it, that progress is
 * reported from that echo rather than the app's cursor, and that a cancelled or
 * stalled transfer ends the way it must.
 *
 * These drive the transfer with `runCurrent()` rather than `advanceUntilIdle()`:
 * the loop waits on an echo behind a timeout, and advancing virtual time would
 * fire that timeout before a test could feed the echo it is exercising.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRepositoryOtaTest {

    private val version = FirmwareVersion(2, 0, 0)
    private var repositoryScope: CoroutineScope? = null

    @After
    fun cancelScope() {
        repositoryScope?.cancel()
        repositoryScope = null
    }

    private fun TestScope.connected(
        transport: FakeBleTransport,
        policy: OtaTransferPolicy = OtaTransferPolicy()
    ): DeviceRepository {
        transport.readResponses[CharacteristicUuids.MOTION_STATE] = FirmwarePayloads.motionState()
        transport.readResponses[CharacteristicUuids.LED_STATE] = FirmwarePayloads.ledState()
        transport.readResponses[CharacteristicUuids.SYSTEM_CONFIG] = FirmwarePayloads.systemInfo()
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

    /** FF0E write offsets in order, decoded from the u32 LE header of each packet. */
    private fun FakeBleTransport.otaWriteOffsets(): List<Int> =
        writesWithoutResponse
            .filter { it.uuid == CharacteristicUuids.OTA_DATA }
            .map { ByteBuffer.wrap(it.data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int }

    private suspend fun TestScope.feedEcho(transport: FakeBleTransport, accepted: Int, total: Int) {
        val state = if (accepted >= total) OtaTransferState.READY.code else OtaTransferState.RECEIVING.code
        transport.notify(CharacteristicUuids.OTA_DATA, FirmwarePayloads.otaStatus(accepted, state))
        runCurrent()
    }

    @Test
    fun `a full transfer installs and reports progress from the device's echo`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        val image = ByteArray(1400) { it.toByte() }
        val progress = mutableListOf<Pair<Int, Int>>()

        var result: FirmwareUpdateResult? = null
        launch { result = repository.uploadFirmware(image, version) { a, t -> progress += a to t } }
        runCurrent()

        // BEGIN went out, and with a 1400-byte image inside the 4096 window every
        // chunk is on the air before the first echo. maxPayload at MTU 517 is 510.
        assertEquals(listOf(0, 510, 1020), transport.otaWriteOffsets())
        assertTrue(progress.isEmpty())

        feedEcho(transport, accepted = 510, total = 1400)
        feedEcho(transport, accepted = 1400, total = 1400)

        assertEquals(FirmwareUpdateResult.Installed, result)
        // Progress is the device's accepted offset, never the app's write cursor.
        assertEquals(listOf(510 to 1400, 1400 to 1400), progress)
        // FINALIZE (0x09) followed the last byte.
        assertTrue(transport.writesTo(CharacteristicUuids.SYSTEM_CONFIG).any { it.contentEquals(byteArrayOf(0x09)) })
    }

    @Test
    fun `an echo that jumps backward rewinds the write cursor to it`() = runTest {
        val transport = FakeBleTransport()
        // A window narrower than the image so not everything is written up front.
        val repository = connected(transport, OtaTransferPolicy(windowBytes = 1024))
        val image = ByteArray(3000) { it.toByte() }

        val job = launch { repository.uploadFirmware(image, version) { _, _ -> } }
        runCurrent()
        assertEquals(listOf(0, 510, 1020), transport.otaWriteOffsets())

        feedEcho(transport, accepted = 1020, total = 3000)
        val afterForward = transport.otaWriteOffsets().size

        // The device now asks to go back to 510: everything sent past it was
        // dropped, so the cursor rewinds and the next packet starts there again.
        feedEcho(transport, accepted = 510, total = 3000)
        val resent = transport.otaWriteOffsets().drop(afterForward)
        assertEquals(510, resent.first())

        job.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `an echo past the cursor jumps forward without resending burned bytes`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport, OtaTransferPolicy(windowBytes = 1024))
        val image = ByteArray(3000) { it.toByte() }

        val job = launch { repository.uploadFirmware(image, version) { _, _ -> } }
        runCurrent()
        val beforeJump = transport.otaWriteOffsets().size

        // The device reports 2000 bytes already in flash — further than the app had
        // sent. Those are burned; resending them would only be rejected, so the
        // cursor jumps forward and the next packet starts at 2000.
        feedEcho(transport, accepted = 2000, total = 3000)
        val afterJump = transport.otaWriteOffsets().drop(beforeJump)
        assertTrue("no packet may re-send below the echoed offset", afterJump.all { it >= 2000 })
        assertEquals(2000, afterJump.first())

        job.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `repeated echoes with no progress abandon the transfer as stalled`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport, OtaTransferPolicy(stallAttempts = 2))
        val image = ByteArray(1400) { it.toByte() }

        var result: FirmwareUpdateResult? = null
        launch { result = repository.uploadFirmware(image, version) { _, _ -> } }
        runCurrent()

        // The device keeps echoing 0: every chunk since is being discarded.
        feedEcho(transport, accepted = 0, total = 1400)
        feedEcho(transport, accepted = 0, total = 1400)

        assertTrue(result is FirmwareUpdateResult.Stalled)
        assertTrue(result!!.resumable)
    }

    @Test
    fun `cancelling a transfer aborts it on the device`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport, OtaTransferPolicy(windowBytes = 1024))
        val image = ByteArray(3000) { it.toByte() }

        val job = launch { repository.uploadFirmware(image, version) { _, _ -> } }
        runCurrent()

        job.cancel()
        advanceUntilIdle()

        // A cancelled transfer must un-arm the tail, or it sits armed with a
        // partial image for the rest of its uptime — ABORT is 0x0A on FF06.
        assertTrue(
            "cancel must send ABORT (0x0A)",
            transport.writesTo(CharacteristicUuids.SYSTEM_CONFIG).any { it.contentEquals(byteArrayOf(0x0A)) }
        )
    }

    @Test
    fun `a short finalize stays resumable`() = runTest {
        // FINALIZE answers BAD_STATE while the device stays in RECEIVING: fewer
        // bytes arrived than announced, every one still good, so it can resume.
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.ackResults.addLast(0x00) // BEGIN
        transport.ackResults.addLast(CommandResultCode.BAD_STATE.code.toInt() and 0xFF) // FINALIZE
        transport.readResponses[CharacteristicUuids.OTA_DATA] =
            FirmwarePayloads.otaStatus(1400, OtaTransferState.RECEIVING.code)

        val result = runToFinalize(repository, transport)
        assertTrue(result is FirmwareUpdateResult.Rejected)
        assertTrue("a short transfer is resumable", result.resumable)
    }

    @Test
    fun `a checksum mismatch at finalize is not resumable`() = runTest {
        // FINALIZE answers BAD_STATE with the device in ERROR: the bytes are wrong
        // and sending more cannot repair them, so the transfer is destroyed.
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.ackResults.addLast(0x00)
        transport.ackResults.addLast(CommandResultCode.BAD_STATE.code.toInt() and 0xFF)
        transport.readResponses[CharacteristicUuids.OTA_DATA] =
            FirmwarePayloads.otaStatus(1400, OtaTransferState.ERROR.code)

        val result = runToFinalize(repository, transport)
        assertTrue(result is FirmwareUpdateResult.Rejected)
        assertFalse("a checksum mismatch is not resumable", result.resumable)
    }

    /** Uploads a whole image so the outcome is decided at FINALIZE, and returns it. */
    private suspend fun TestScope.runToFinalize(
        repository: DeviceRepository,
        transport: FakeBleTransport
    ): FirmwareUpdateResult {
        val image = ByteArray(1400) { it.toByte() }
        var result: FirmwareUpdateResult? = null
        launch { result = repository.uploadFirmware(image, version) { _, _ -> } }
        runCurrent()
        // All chunks are on the air; the last-byte echo drives the loop to FINALIZE.
        feedEcho(transport, accepted = 1400, total = 1400)
        return result!!
    }
}
