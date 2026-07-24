package com.tailapp.audio

import com.tailapp.ble.protocol.FftFrameBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deriving the device's FF05 frame from the analysis a BeatLight session already
 * runs — the change that lets one microphone serve both.
 *
 * Before this, the FF05 visualiser stream and a BeatLight session each opened
 * their own capture and were mutually exclusive, so starting a session silently
 * killed the device's own audio effects. Nothing here opens a capture.
 */
class FeatureFrameFftEncoderTest {

    private val config = FeatureConfig()

    private fun frame(bands: FloatArray, rms: Float = 0.1f) = FeatureFrame(
        index = 0,
        timestampNanos = 0L,
        bands = bands,
        flux = 0f,
        rms = rms,
        bassEnergy = 0f,
        midEnergy = 0f,
        highEnergy = 0f,
        spectralCentroidHz = 0f
    )

    /** The analysis filterbank's real width, so the test uses realistic shapes. */
    private fun bandCount(): Int =
        com.tailapp.audio.dsp.LogFilterbank(
            sampleRate = config.sampleRate,
            frameSize = config.frameSize,
            bandsPerOctave = config.bandsPerOctave,
            fMin = config.fMin,
            fMax = config.fMax
        ).bandCount

    @Test
    fun `produces exactly the configured number of bins`() {
        val encoder = FeatureFrameFftEncoder(config, FftSettings(binCount = 64))
        val result = encoder.encode(frame(FloatArray(bandCount()) { 0.5f }))
        assertEquals(64, result.bins.size)
    }

    @Test
    fun `honours a changed bin count`() {
        val encoder = FeatureFrameFftEncoder(config, FftSettings(binCount = 64))
        encoder.encode(frame(FloatArray(bandCount()) { 0.5f }))

        encoder.settings = FftSettings(binCount = 16)
        assertEquals(16, encoder.encode(frame(FloatArray(bandCount()) { 0.5f })).bins.size)
    }

    @Test
    fun `energy lands in the bin covering its frequency`() {
        val bands = FloatArray(bandCount())
        // Energy only in the lowest tenth of the spectrum.
        for (i in 0 until bandCount() / 10) bands[i] = 1f

        val encoder = FeatureFrameFftEncoder(config, FftSettings(binCount = 10))
        // Let the adaptive normaliser settle on this level.
        repeat(20) { encoder.encode(frame(bands)) }
        val bins = encoder.encode(frame(bands)).bins

        assertTrue("the low bin must be lit", (bins[0].toInt() and 0xFF) > 128)
        assertEquals("the high bins must be dark", 0, bins[9].toInt() and 0xFF)
    }

    @Test
    fun `groups take their peak rather than their mean`() {
        val bands = FloatArray(bandCount())
        // One loud band in the middle of the spectrum, among silent neighbours.
        // Each of 8 bins covers ~25 bands here, so a mean would report about
        // 255/25 = 10 while a peak reports 255 — and the device's bar effects
        // are drawing peaks.
        bands[bandCount() / 2] = 1f

        val encoder = FeatureFrameFftEncoder(config, FftSettings(binCount = 8))
        repeat(20) { encoder.encode(frame(bands)) }
        val bins = encoder.encode(frame(bands)).bins

        val loudest = bins.maxOf { it.toInt() and 0xFF }
        assertTrue("a narrow peak must survive grouping, got $loudest", loudest > 200)
        // ...and it must not smear across the whole spectrum either.
        assertEquals(1, bins.count { (it.toInt() and 0xFF) > 200 })
    }

    @Test
    fun `content below the configured window is excluded`() {
        val bands = FloatArray(bandCount())
        bands[0] = 1f // 30 Hz, below the default 40 Hz start

        val encoder = FeatureFrameFftEncoder(config, FftSettings(binCount = 8))
        repeat(20) { encoder.encode(frame(bands)) }
        val bins = encoder.encode(frame(bands)).bins

        assertTrue("the window has to actually exclude", bins.all { (it.toInt() and 0xFF) == 0 })
    }

    @Test
    fun `silence encodes as silence`() {
        val encoder = FeatureFrameFftEncoder(config)
        val result = encoder.encode(frame(FloatArray(bandCount()), rms = 0f))

        assertEquals(0, result.loudness.toInt() and 0xFF)
        assertTrue(result.bins.all { it.toInt() == 0 })
    }

    @Test
    fun `loudness is normalised so a quiet mic still reaches full scale`() {
        val encoder = FeatureFrameFftEncoder(config)
        val bands = FloatArray(bandCount()) { 0.01f }

        // A very quiet but *consistent* signal. Passing raw RMS through would
        // leave the device's bars permanently at zero on a quiet phone mic.
        var last = 0
        repeat(60) { last = encoder.encode(frame(bands, rms = 0.01f)).loudness.toInt() and 0xFF }
        assertTrue("adaptive gain must bring a quiet source up, got $last", last > 128)
    }

    @Test
    fun `every bin covers at least one band`() {
        // More bins than the window has bands: without a floor, the surplus bins
        // would be permanently zero and the device would show gaps.
        val encoder = FeatureFrameFftEncoder(
            config,
            FftSettings(binCount = 128, frequencyStartHz = 1000f, frequencyEndHz = 1200f)
        )
        val bands = FloatArray(bandCount()) { 1f }
        repeat(20) { encoder.encode(frame(bands)) }
        val bins = encoder.encode(frame(bands)).bins

        assertTrue("a narrow window must not yield empty bins", bins.all { (it.toInt() and 0xFF) > 0 })
    }

    @Test
    fun `a narrowed frequency window changes what is reported`() {
        val bands = FloatArray(bandCount())
        for (i in 0 until bandCount() / 4) bands[i] = 1f // low-frequency content only

        val wide = FeatureFrameFftEncoder(config, FftSettings(binCount = 8))
        repeat(20) { wide.encode(frame(bands)) }
        val wideBins = wide.encode(frame(bands)).bins

        // Looking only at high frequencies, the same audio is nearly empty.
        val high = FeatureFrameFftEncoder(
            config,
            FftSettings(binCount = 8, frequencyStartHz = 8000f, frequencyEndHz = 16000f)
        )
        repeat(20) { high.encode(frame(bands)) }
        val highBins = high.encode(frame(bands)).bins

        assertNotEquals(wideBins.toList(), highBins.toList())
        assertTrue((wideBins[0].toInt() and 0xFF) > (highBins[0].toInt() and 0xFF))
    }
}

/** The wire format of the beat trailer the device reads. */
class FftFrameBuilderTest {

    @Test
    fun `a plain frame is unchanged`() {
        val frame = FftFrameBuilder.build(200.toByte(), byteArrayOf(1, 2, 3))
        assertEquals(5, frame.size)
        assertEquals(200, frame[0].toInt() and 0xFF)
        assertEquals(3, frame[1].toInt())
    }

    @Test
    fun `the trailer sits after the bins so older firmware ignores it`() {
        val bins = byteArrayOf(10, 20, 30, 40)
        val frame = FftFrameBuilder.buildWithBeat(
            loudness = 128.toByte(),
            bins = bins,
            beatPhase = 0.5f,
            bpm = 128f,
            flags = FftFrameBuilder.FLAG_BEAT or FftFrameBuilder.FLAG_DOWNBEAT
        )

        // Header and bins are byte-for-byte what a plain frame would carry: the
        // firmware reads exactly num_bins of bin data and ignores the rest, so
        // this is safe against firmware that predates the trailer.
        val plain = FftFrameBuilder.build(128.toByte(), bins)
        assertEquals(plain.size + 3, frame.size)
        for (i in plain.indices) assertEquals(plain[i], frame[i])

        assertEquals(127, frame[6].toInt() and 0xFF) // phase 0.5 -> 127
        assertEquals(128, frame[7].toInt() and 0xFF) // bpm
        assertEquals(0x03, frame[8].toInt() and 0xFF) // beat | downbeat
    }

    @Test
    fun `an out-of-range tempo clamps rather than wrapping`() {
        // 300 BPM truncating to 44 would be worse than reporting the ceiling.
        val frame = FftFrameBuilder.buildWithBeat(0, ByteArray(0), 0f, 300f, 0)
        assertEquals(255, frame[3].toInt() and 0xFF)

        val negative = FftFrameBuilder.buildWithBeat(0, ByteArray(0), -1f, -5f, 0)
        assertEquals(0, negative[2].toInt() and 0xFF) // phase clamped
        assertEquals(0, negative[3].toInt() and 0xFF) // bpm clamped
    }
}
