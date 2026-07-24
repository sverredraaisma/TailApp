package com.tailapp.viewmodel

import com.tailapp.audio.FftResult
import com.tailapp.model.MotionState
import com.tailapp.repository.DeviceRepository
import com.tailapp.testutil.FakeBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [LedConfigViewModel.onFftResult] and [LedConfigViewModel.onMotionState] are
 * the glue that feeds the live LED preview's
 * [com.tailapp.led.AudioLevelSource] and [com.tailapp.led.MotionStateSource]
 * from the app's own FFT pipeline and the FF02 stream. They're exercised
 * directly here rather than through [com.tailapp.audio.FftStreamManager]'s
 * `latestResult` flow, since building a real `FftStreamManager` needs an
 * Android `Context` a JVM unit test can't construct - the mapping logic itself
 * doesn't depend on it either way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedConfigViewModelTest {

    private var repositoryScope: CoroutineScope? = null

    // The view model subscribes the tail's own state on construction, so
    // `viewModelScope` needs a Main dispatcher before one can be built at all.
    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun cancelRepositoryScope() {
        repositoryScope?.cancel()
        repositoryScope = null
        Dispatchers.resetMain()
    }

    private fun newViewModel(): LedConfigViewModel {
        val scope = CoroutineScope(StandardTestDispatcher())
        repositoryScope = scope
        val repository = DeviceRepository(FakeBleTransport(), scope)
        return LedConfigViewModel(repository)
    }

    @Test
    fun `onFftResult writes the frame into the preview's audio source`() {
        val viewModel = newViewModel()
        assertFalse(viewModel.previewClock.audio.isFresh)

        viewModel.onFftResult(FftResult(loudness = 200.toByte(), bins = byteArrayOf(1, 2, 3)))

        assertTrue(viewModel.previewClock.audio.isFresh)
        assertEquals(200, viewModel.previewClock.audio.loudness)
        assertEquals(3, viewModel.previewClock.audio.numBins)
    }

    @Test
    fun `onFftResult treats loudness as an unsigned byte, matching the wire format`() {
        val viewModel = newViewModel()

        // 220 doesn't fit in a signed Byte (bit pattern -36); AudioLevelSource
        // must recover the unsigned value, same as the firmware's uint8_t.
        viewModel.onFftResult(FftResult(loudness = 220.toByte(), bins = ByteArray(0)))

        assertEquals(220, viewModel.previewClock.audio.loudness)
    }

    @Test
    fun `onFftResult bin values are readable through the preview's audio source`() {
        val viewModel = newViewModel()

        viewModel.onFftResult(FftResult(loudness = 0, bins = byteArrayOf(5, 0xFF.toByte())))

        assertEquals(5, viewModel.previewClock.audio.bin(0))
        assertEquals(255, viewModel.previewClock.audio.bin(1))
    }

    @Test
    fun `onMotionState publishes raw degrees and gravity into the preview's motion source`() {
        val viewModel = newViewModel()

        viewModel.onMotionState(motionState(listOf(30f, 60f, 5f, -5f)), nowNanos = 0L)

        // Degrees, not normalised deflection: motion_glow_effect.cpp maps them
        // across a fixed +/-90 window, so normalising here would shift the hue.
        assertEquals(30f, viewModel.previewClock.motion.position(0), 0f)
        assertEquals(60f, viewModel.previewClock.motion.position(1), 0f)
        assertEquals(0.25f, viewModel.previewClock.motion.gravityX, 0f)
        assertEquals(-0.5f, viewModel.previewClock.motion.gravityY, 0f)
    }

    @Test
    fun `onMotionState derives the motion energy FF02 does not carry`() {
        val viewModel = newViewModel()

        viewModel.onMotionState(motionState(listOf(0f, 0f, 0f, 0f)), nowNanos = 0L)
        assertEquals(0f, viewModel.previewClock.motion.motionEnergy, 0f)

        // A half-scale swing in 100 ms is fast; energy comes from the change,
        // because the payload has no velocity field of its own.
        viewModel.onMotionState(motionState(listOf(45f, 45f, 0f, 0f)), nowNanos = 100_000_000L)
        assertTrue(viewModel.previewClock.motion.motionEnergy > 0f)
    }

    private fun motionState(positions: List<Float>) = MotionState(
        activePatternId = 0,
        params = List(8) { 0f },
        encoderPositions = positions,
        gravityX = 0.25f,
        gravityY = -0.5f,
        gravityZ = 0.8f,
        xAxisMin = -90f,
        xAxisMax = 90f,
        yAxisMin = -45f,
        yAxisMax = 45f
    )
}
