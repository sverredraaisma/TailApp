package com.tailapp.lighting

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.led.PixelBuffer
import com.tailapp.repository.DeviceRepository
import com.tailapp.testutil.FakeBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direct-mode liveness contract (roadmap A6-3).
 *
 * Frame suppression and the device's stale-frame timeout are two halves of one
 * agreement held in two repositories: the app stops sending frames that haven't
 * changed, and the device stops trusting a stream that has gone quiet. Get the
 * numbers the wrong way round and an idle-but-live composition silently hands
 * rendering back to the device's own effect stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TailDirectLedOutputTest {

    private lateinit var dispatcher: TestDispatcher
    private var repositoryScope: CoroutineScope? = null

    @After
    fun cancelRepositoryScope() {
        repositoryScope?.cancel()
        repositoryScope = null
    }

    private fun newRepository(transport: FakeBleTransport): DeviceRepository {
        dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(dispatcher)
        repositoryScope = scope
        transport.setConnectionState(ConnectionState.CONNECTED)
        return DeviceRepository(transport, scope)
    }

    private fun frame(vararg bytes: Byte): PixelBuffer {
        val buffer = PixelBuffer(bytes.size / 3)
        bytes.copyInto(buffer.bytes)
        return buffer
    }

    private fun FakeBleTransport.directFrames() =
        writesWithoutResponse.filter { it.uuid == CharacteristicUuids.LED_DIRECT }

    @Test
    fun `the keepalive stays under the device's stale-frame timeout`() {
        // The whole point of the pairing. Stated as an assertion because the
        // other number lives in a different repository and nothing else would
        // notice if either moved.
        assertTrue(
            TailDirectLedOutput.DEFAULT_KEEPALIVE_MILLIS <
                TailDirectLedOutput.FIRMWARE_STALE_TIMEOUT_MILLIS
        )
    }

    @Test
    fun `a keepalive at or past the device's timeout is rejected at construction`() {
        val transport = FakeBleTransport()
        val repository = newRepository(transport)

        // Failing loudly here beats a session that renders correctly for two
        // seconds and then quietly stops being ours.
        assertThrows(IllegalArgumentException::class.java) {
            TailDirectLedOutput(
                repository,
                keepaliveMillis = TailDirectLedOutput.FIRMWARE_STALE_TIMEOUT_MILLIS
            )
        }
    }

    @Test
    fun `an unchanged frame is resent before the device would time the stream out`() =
        runTest(StandardTestDispatcher()) {
            val transport = FakeBleTransport()
            val output = TailDirectLedOutput(newRepository(transport))
            output.open(ledCount = 1)
            advanceUntilIdle()

            val still = frame(10, 20, 30)
            var now = 0L
            // One frame per 100 ms of a completely static composition, run out
            // past the device's timeout.
            while (now <= TailDirectLedOutput.FIRMWARE_STALE_TIMEOUT_MILLIS) {
                output.onFrame(still, now * 1_000_000L)
                now += 100
            }

            // Not every frame - suppression has to be doing its job - but at
            // least one inside every keepalive window, so the device never sees
            // a gap long enough to call the stream abandoned.
            val sent = transport.directFrames().size
            assertTrue("expected suppression, got $sent writes", sent < 10)
            assertTrue("expected at least one keepalive per window, got $sent", sent >= 3)
        }

    @Test
    fun `identical consecutive frames are suppressed inside the keepalive window`() =
        runTest(StandardTestDispatcher()) {
            val transport = FakeBleTransport()
            val output = TailDirectLedOutput(newRepository(transport))
            output.open(ledCount = 1)
            advanceUntilIdle()

            val still = frame(1, 2, 3)
            output.onFrame(still, 0L)
            output.onFrame(still, 100_000_000L)
            output.onFrame(still, 200_000_000L)

            // FF0A writes are unacknowledged, so a suppressed frame is real BLE
            // airtime saved rather than only CPU.
            assertEquals(1, transport.directFrames().size)
        }

    @Test
    fun `a changed frame goes out immediately regardless of the keepalive`() =
        runTest(StandardTestDispatcher()) {
            val transport = FakeBleTransport()
            val output = TailDirectLedOutput(newRepository(transport))
            output.open(ledCount = 1)
            advanceUntilIdle()

            output.onFrame(frame(1, 2, 3), 0L)
            output.onFrame(frame(4, 5, 6), 1_000_000L)

            assertEquals(2, transport.directFrames().size)
        }

    @Test
    fun `a frame mutated in place is still detected as a change`() =
        runTest(StandardTestDispatcher()) {
            val transport = FakeBleTransport()
            val output = TailDirectLedOutput(newRepository(transport))
            output.open(ledCount = 1)
            advanceUntilIdle()

            // The renderer reuses one buffer, so holding the reference would
            // compare every frame against itself and suppress the whole stream.
            val reused = frame(1, 2, 3)
            output.onFrame(reused, 0L)
            reused.bytes[0] = 9
            output.onFrame(reused, 1_000_000L)

            assertEquals(2, transport.directFrames().size)
        }
}
