package com.tailapp.viewmodel

import com.tailapp.audio.FftProcessor
import com.tailapp.repository.DeviceRepository
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FakeSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The FFT settings a user tunes and the app persists.
 *
 * Everything asserted here is about the *resolution and sanitising* the view
 * model does on the way in and out — the three-way prefs → processor → defaults
 * fallback in `init`, and the clamps that stop a stored value reaching the
 * screen as `ln(0)` or as an inverted band. The processor leg of that fallback
 * is not exercised: `FftStreamManager` needs an Android `Context`, so a JVM test
 * constructs the view model without one and the fallback collapses to
 * prefs → defaults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioConfigViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
    }

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun newViewModel(prefs: FakeSharedPreferences?): AudioConfigViewModel {
        val s = CoroutineScope(dispatcher)
        scope = s
        return AudioConfigViewModel(DeviceRepository(FakeBleTransport(), s), prefs = prefs)
    }

    @Test
    fun `with nothing stored it starts on the documented defaults`() {
        val viewModel = newViewModel(null)

        assertEquals(AudioConfigViewModel.DEFAULT_BINS, viewModel.numBins.value)
        assertEquals(AudioConfigViewModel.DEFAULT_NORM_SPEED, viewModel.normalizationSpeed.value, 1e-6f)
        assertEquals(AudioConfigViewModel.MIN_FREQ_HZ, viewModel.freqStart.value, 1e-6f)
        assertEquals(AudioConfigViewModel.MAX_FREQ_HZ, viewModel.freqEnd.value, 1e-6f)
    }

    @Test
    fun `stored settings are restored`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putInt("fft_num_bins", 32)
            .putFloat("fft_norm_speed", 0.4f)
            .putFloat("fft_freq_start", 60f)
            .putFloat("fft_freq_end", 8000f)
            .apply()

        val viewModel = newViewModel(prefs)

        assertEquals(32, viewModel.numBins.value)
        assertEquals(0.4f, viewModel.normalizationSpeed.value, 1e-6f)
        assertEquals(60f, viewModel.freqStart.value, 1e-6f)
        assertEquals(8000f, viewModel.freqEnd.value, 1e-6f)
    }

    @Test
    fun `a bin count stored by another build is clamped, not trusted`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putInt("fft_num_bins", 4096).apply()

        assertEquals(FftProcessor.MAX_BINS, newViewModel(prefs).numBins.value)
    }

    @Test
    fun `a stored frequency of zero falls back instead of reaching the log scale`() {
        // The screen positions the thumb with ln(freq); 0 Hz would be -Infinity
        // and a NaN thumb fraction, which draws as a control pinned at the edge.
        val prefs = FakeSharedPreferences()
        prefs.edit().putFloat("fft_freq_start", 0f).apply()

        val start = newViewModel(prefs).freqStart.value

        assertTrue("expected a positive start frequency, got $start", start > 0f)
        assertEquals(AudioConfigViewModel.MIN_FREQ_HZ, start, 1e-6f)
    }

    @Test
    fun `an inverted stored range is corrected on the way in`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putFloat("fft_freq_start", 9000f).putFloat("fft_freq_end", 100f).apply()

        val viewModel = newViewModel(prefs)

        // An inverted band analyses nothing at all, and the only symptom would
        // be a spectrum that is silently empty.
        assertTrue(
            "start ${viewModel.freqStart.value} must sit below end ${viewModel.freqEnd.value}",
            viewModel.freqStart.value < viewModel.freqEnd.value
        )
    }

    @Test
    fun `raising the start pushes the end up rather than crossing it`() {
        val viewModel = newViewModel(FakeSharedPreferences())
        viewModel.setFreqEnd(1000f)

        viewModel.setFreqStart(1500f)

        assertEquals(1500f, viewModel.freqStart.value, 1e-3f)
        assertEquals(
            1500f + AudioConfigViewModel.MIN_FREQ_SPAN_HZ,
            viewModel.freqEnd.value,
            1e-3f
        )
    }

    @Test
    fun `lowering the end pushes the start down rather than crossing it`() {
        val viewModel = newViewModel(FakeSharedPreferences())
        viewModel.setFreqStart(5000f)

        viewModel.setFreqEnd(2000f)

        assertEquals(2000f, viewModel.freqEnd.value, 1e-3f)
        assertEquals(
            2000f - AudioConfigViewModel.MIN_FREQ_SPAN_HZ,
            viewModel.freqStart.value,
            1e-3f
        )
    }

    @Test
    fun `settings survive a restart because every setter persists its clamped value`() {
        val prefs = FakeSharedPreferences()
        val first = newViewModel(prefs)
        first.setNumBins(4096)
        first.setNormalizationSpeed(9f)
        first.setFreqStart(120f)

        val second = newViewModel(prefs)

        // The *clamped* value is what was written, so a reload cannot resurrect
        // the out-of-range one.
        assertEquals(FftProcessor.MAX_BINS, second.numBins.value)
        assertEquals(AudioConfigViewModel.MAX_NORM_SPEED, second.normalizationSpeed.value, 1e-6f)
        assertEquals(120f, second.freqStart.value, 1e-3f)
    }

    @Test
    fun `a non-finite normalization speed is replaced rather than propagated`() {
        val viewModel = newViewModel(FakeSharedPreferences())

        viewModel.setNormalizationSpeed(Float.NaN)

        assertEquals(AudioConfigViewModel.DEFAULT_NORM_SPEED, viewModel.normalizationSpeed.value, 1e-6f)
    }
}
