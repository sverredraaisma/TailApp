package com.tailapp.viewmodel

import com.tailapp.audio.FftResult
import com.tailapp.repository.DeviceRepository
import com.tailapp.testutil.FakeBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LedConfigViewModel.onFftResult] is the glue that feeds the live LED
 * preview's [com.tailapp.led.AudioLevelSource] from the app's own FFT
 * pipeline. It's exercised directly here rather than through
 * [com.tailapp.audio.FftStreamManager]'s `latestResult` flow, since building
 * a real `FftStreamManager` needs an Android `Context` a JVM unit test can't
 * construct - the mapping logic itself doesn't depend on it either way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedConfigViewModelTest {

    private var repositoryScope: CoroutineScope? = null

    @After
    fun cancelRepositoryScope() {
        repositoryScope?.cancel()
        repositoryScope = null
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
}
