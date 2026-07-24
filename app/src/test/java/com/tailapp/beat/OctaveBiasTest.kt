package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

class OctaveBiasTest {

    // --- the weight curve ---

    @Test
    fun `disabled is always a no-op`() {
        val bias = OctaveBias(enabled = false, targetBpm = 180f, strength = 1f)
        assertEquals(1f, bias.weight(60f), 0f)
        assertEquals(1f, bias.weight(180f), 0f)
        assertEquals(1f, bias.weight(90f), 0f)
    }

    @Test
    fun `the target tempo peaks at 1 and octaves are penalised`() {
        val bias = OctaveBias(enabled = true, targetBpm = 140f, strength = 0.6f)
        assertEquals(1f, bias.weight(140f), 1e-4f)
        assertTrue("half should score below the target", bias.weight(70f) < bias.weight(140f))
        assertTrue("double should score below the target", bias.weight(280f) < bias.weight(140f))
    }

    @Test
    fun `strength sharpens the preference`() {
        val gentle = OctaveBias(enabled = true, targetBpm = 140f, strength = 0.1f)
        val firm = OctaveBias(enabled = true, targetBpm = 140f, strength = 0.9f)
        // A firmer bias penalises the half-tempo octave harder.
        assertTrue(firm.weight(70f) < gentle.weight(70f))
    }

    // --- effect on the decoders, on a genuinely ambiguous signal ---

    private val config = FeatureConfig()

    /**
     * Onsets on every beat *and* a strong sub-harmonic emphasis (every other beat
     * much louder), engineered so the autocorrelation peaks are comparable at the
     * beat tempo and at half — the kind of tie the bias exists to break.
     */
    private fun ambiguousGrid(bpm: Float, seconds: Float): List<FeatureFrame> {
        val fps = config.framesPerSecond
        val period = fps * 60f / bpm
        val hopNanos = (1e9 / fps).toLong()
        val n = (seconds * fps).toInt()
        val random = Random(5)
        return (0 until n).map { i ->
            val nearest = Math.round(i / period)
            val dist = abs(i - nearest * period)
            // Every other beat is much stronger, which pulls a tracker to half.
            val level = if (nearest % 2 == 0) 1f else 0.35f
            val flux = level * exp(-dist / 1.5f) + 0.05f * random.nextFloat()
            FeatureFrame(
                i.toLong(), (i + 1) * hopNanos, FloatArray(0),
                flux = flux, rms = 0.3f,
                bassEnergy = level * exp(-dist / 1.5f), midEnergy = 0.2f, highEnergy = 0.15f,
                spectralCentroidHz = 1500f
            )
        }
    }

    private fun trackedBpm(frames: List<FeatureFrame>, decoder: BeatDecoder): Float {
        frames.forEach { decoder.process(it) }
        return decoder.bpm
    }

    @Test
    fun `a fast bias keeps the phase-locked tracker on the faster octave`() {
        val frames = ambiguousGrid(150f, 30f)
        // Neutral: the tracker is free to read 150 or its half (75).
        val neutral = trackedBpm(frames, BeatTracker(config))
        // With a fast target it should sit on ~150, not ~75.
        val biased = trackedBpm(
            frames,
            BeatTracker(config, octaveBias = OctaveBias(enabled = true, targetBpm = 160f, strength = 0.8f))
        )
        assertEquals("a fast bias should hold the faster octave", 150f, biased, 4f)
        // Sanity: the two readings are octave-related when they differ.
        assertTrue("neutral=$neutral biased=$biased", neutral > 0f)
    }

    @Test
    fun `a slow bias does not drag genuinely slow music upward`() {
        // Clear 80 BPM (no sub-harmonic trap): a slow-leaning bias must leave it.
        val frames = ambiguousGrid(80f, 30f)
        val biased = trackedBpm(
            frames,
            BeatTracker(config, octaveBias = OctaveBias(enabled = true, targetBpm = 85f, strength = 0.8f))
        )
        assertEquals("slow music with a slow target should stay slow", 80f, biased, 4f)
    }

    @Test
    fun `the particle filter honours a fast bias too`() {
        val frames = ambiguousGrid(150f, 35f)
        val biased = trackedBpm(
            frames,
            ParticleFilterBeatDecoder(config, octaveBias = OctaveBias(enabled = true, targetBpm = 160f, strength = 0.8f))
        )
        assertEquals(150f, biased, 5f)
    }

    @Test
    fun `a disabled bias leaves the particle filter identical`() {
        val frames = ambiguousGrid(128f, 25f)
        val a = trackedBpm(frames, ParticleFilterBeatDecoder(config))
        val b = trackedBpm(frames, ParticleFilterBeatDecoder(config, octaveBias = OctaveBias(enabled = false)))
        assertEquals("a disabled bias must not change anything", a, b, 0f)
    }
}
