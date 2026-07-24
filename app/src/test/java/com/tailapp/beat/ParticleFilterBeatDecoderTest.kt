package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import kotlin.math.abs

/**
 * [ParticleFilterBeatDecoder] on its own terms.
 *
 * Accuracy against the MVP is `BeatDecoderComparisonTest`'s job; this suite pins
 * the properties that are specific to a stochastic decoder — that it is
 * reproducible, that it does not hallucinate structure in silence, that `reset`
 * really returns it to its initial state, and that the cost of a frame does not
 * scale with the particle count.
 */
class ParticleFilterBeatDecoderTest {

    private val config = FeatureConfig()
    private val toleranceNanos = 70_000_000L

    private fun decoder(seed: Long = 0x5EEDL, particles: Int? = null) =
        if (particles == null) {
            ParticleFilterBeatDecoder(config, seed = seed)
        } else {
            ParticleFilterBeatDecoder(config, beatParticleCount = particles, seed = seed)
        }

    /** Runs [frames] through [decoder], returning every beat it emitted. */
    private fun run(decoder: BeatDecoder, frames: List<FeatureFrame>): List<BeatEvent> =
        frames.flatMap { decoder.process(it) }

    private fun hitRate(beats: List<BeatEvent>, truth: List<Long>): Float {
        if (beats.isEmpty()) return 0f
        return beats.count { beat -> truth.any { abs(it - beat.timestampNanos) <= toleranceNanos } }
            .toFloat() / beats.size
    }

    // --- determinism ---------------------------------------------------------

    @Test
    fun `the same seed and the same frames give the same beats`() {
        val signal = BeatTestSignals(config).grid(128f, 25f)

        val first = run(decoder(seed = 4242L), signal.frames)
        val second = run(decoder(seed = 4242L), signal.frames)

        assertTrue("no beats to compare", first.isNotEmpty())
        assertEquals(first, second)
    }

    @Test
    fun `reset re-seeds, so a reused decoder repeats itself exactly`() {
        val signal = BeatTestSignals(config).grid(128f, 25f)
        val decoder = decoder()

        val first = run(decoder, signal.frames)
        decoder.reset()
        val second = run(decoder, signal.frames)

        assertEquals(first, second)
    }

    @Test
    fun `a different seed gives different particles but the same answer`() {
        val signal = BeatTestSignals(config).grid(128f, 25f)

        val a = decoder(seed = 1L)
        val b = decoder(seed = 987_654L)
        val beatsA = run(a, signal.frames)
        val beatsB = run(b, signal.frames)

        // Not identical: the cloud really is stochastic.
        assertNotEquals(beatsA, beatsB)
        // But the conclusion is the same, which is the point of a particle filter.
        assertEquals("seed 1 tempo", 128f, a.bpm, 2f)
        assertEquals("seed 987654 tempo", 128f, b.bpm, 2f)
        assertTrue(hitRate(beatsA.filter { it.timestampNanos > 10_000_000_000L }, signal.beatNanos) >= 0.9f)
        assertTrue(hitRate(beatsB.filter { it.timestampNanos > 10_000_000_000L }, signal.beatNanos) >= 0.9f)
    }

    @Test
    fun `the answer does not depend on a lucky seed`() {
        val signal = BeatTestSignals(config).grid(128f, 25f)
        for (seed in listOf(0L, 3L, 17L, 512L, 65_537L, 1_234_567L)) {
            val decoder = decoder(seed = seed)
            run(decoder, signal.frames)
            assertEquals("seed $seed", 128f, decoder.bpm, 2f)
        }
    }

    // --- silence -------------------------------------------------------------

    @Test
    fun `locks promptly on a peaky activation over continuous audio - the CRNN case`() {
        // A neural activation (BeatNet's CRNN) sits at zero between beats, where
        // the spectral flux this filter was first tuned on is dense. Silence must
        // therefore be judged from audio energy, not the beat activation — with
        // continuous audio the filter has to lock in a couple of seconds even
        // though the activation is quiet most of the time. Before that fix this
        // signal took ~12 s to report any BPM, which read on-device as "no BPM".
        val fps = config.framesPerSecond
        val hopNanos = (1e9 / fps).toLong()
        val period = fps * 60f / 128f
        val decoder = ParticleFilterBeatDecoder(config)

        var firstBpmSeconds = -1f
        for (i in 0 until (20f * fps).toInt()) {
            val nearest = Math.round(i / period)
            val dist = abs(i - nearest * period)
            // Continuous energy; activation is exactly zero except at a beat, and
            // never crosses the old 0.05 activation floor between beats.
            val frame = FeatureFrame(
                i.toLong(), (i + 1) * hopNanos, FloatArray(0),
                flux = 0f, rms = 0.2f, bassEnergy = 0.1f, midEnergy = 0.1f, highEnergy = 0.1f,
                spectralCentroidHz = 1500f
            )
            val beatAct = if (dist < 1f) 1f else 0f
            val downAct = if (dist < 1f && Math.floorMod(nearest, 4) == 0) 1f else 0f
            decoder.process(frame, BeatActivation(beatAct, downAct))
            if (firstBpmSeconds < 0f && decoder.bpm > 0f) firstBpmSeconds = frame.timestampNanos / 1e9f
        }

        assertTrue("never reported a BPM", firstBpmSeconds >= 0f)
        assertTrue("first BPM at ${firstBpmSeconds}s — too slow", firstBpmSeconds <= 6f)
        assertEquals(128f, decoder.bpm, 2f)
    }

    @Test
    fun `silence produces no beats`() {
        val signals = BeatTestSignals(config)

        val decoder = decoder()
        val beats = run(decoder, signals.silence(20f))

        assertTrue("silence produced ${beats.size} beats", beats.isEmpty())
        assertEquals(0f, decoder.confidence, 0f)
        assertEquals(0f, decoder.bpm, 0f)
        assertNull(decoder.nextBeatTimestampNanos)
    }

    @Test
    fun `the lock is dropped after a long silence`() {
        val signals = BeatTestSignals(config)
        val decoder = decoder()
        val music = signals.grid(128f, 25f)

        run(decoder, music.frames)
        assertTrue("never locked on", decoder.confidence > 0f)

        val quiet = signals.silence(6f, startFrame = music.frames.size.toLong())
        // It may keep predicting briefly — a gap is not obviously the end of the
        // music — but it must give up rather than run on forever.
        val heldOver = quiet[(4 * config.framesPerSecond).toInt()]
        val beatsInSilence = run(decoder, quiet)

        assertTrue(
            "still emitting four seconds into silence",
            beatsInSilence.none { it.timestampNanos >= heldOver.timestampNanos }
        )
        assertEquals(0f, decoder.confidence, 0f)
    }

    // --- state ---------------------------------------------------------------

    @Test
    fun `reset clears the lock`() {
        val decoder = decoder()
        run(decoder, BeatTestSignals(config).grid(128f, 25f).frames)
        assertTrue(decoder.bpm > 0f)

        decoder.reset()

        assertEquals(0f, decoder.bpm, 0f)
        assertEquals(0f, decoder.confidence, 0f)
        assertNull(decoder.nextBeatTimestampNanos)
    }

    @Test
    fun `reports a next-beat prediction while locked`() {
        val signal = BeatTestSignals(config).grid(128f, 25f)
        val decoder = decoder()
        run(decoder, signal.frames)

        val next = decoder.nextBeatTimestampNanos
        assertTrue("no prediction while locked", next != null)
        val lastFrameTime = signal.frames.last().timestampNanos
        val periodNanos = (60f / 128f * 1e9f).toLong()
        assertTrue(
            "prediction $next is not within a beat of the last frame $lastFrameTime",
            next!! > lastFrameTime && next - lastFrameTime <= periodNanos + 20_000_000L
        )
    }

    @Test
    fun `beats are emitted slightly ahead of the instant they describe`() {
        val signal = BeatTestSignals(config).grid(128f, 20f)
        val decoder = decoder()

        var ahead = 0
        var total = 0
        signal.frames.forEach { frame ->
            decoder.process(frame).forEach { beat ->
                if (frame.timestampNanos >= 10_000_000_000L) {
                    total++
                    if (beat.timestampNanos > frame.timestampNanos) ahead++
                }
            }
        }

        assertTrue("no beats after the lock", total > 0)
        assertTrue("no beats were emitted ahead of time", ahead > 0)
    }

    @Test
    fun `bar positions cycle and downbeats land on the accent`() {
        val signal = BeatTestSignals(config).grid(128f, 40f, accentEvery = 4)
        val decoder = decoder()

        val beats = run(decoder, signal.frames).filter { it.timestampNanos >= 20_000_000_000L }

        assertEquals(listOf(0, 1, 2, 3), beats.map { it.beatInBar }.distinct().sorted())
        val downbeats = beats.filter { it.isDownbeat }
        assertTrue("no downbeats", downbeats.isNotEmpty())
        assertEquals(beats.size / 4f, downbeats.size.toFloat(), beats.size / 8f)
        // The same 0.8 bar BeatTrackerTest holds itself to, and for the same
        // reason: on this grid both decoders land at 0.82, so the residue is the
        // signal and the +-70 ms window rather than either decoder's bar filter.
        val rate = hitRate(downbeats, signal.downbeatNanos)
        assertTrue("only ${(rate * 100).toInt()}% of downbeats hit an accent", rate >= 0.8f)
    }

    // --- adaptation ----------------------------------------------------------

    @Test
    fun `follows a tempo change`() {
        val signals = BeatTestSignals(config)
        val decoder = decoder()
        val first = signals.grid(120f, 25f)
        val second = signals.grid(150f, 25f, startFrame = first.frames.size.toLong())

        run(decoder, first.frames)
        assertEquals("did not lock on to the first tempo", 120f, decoder.bpm, 2f)

        val after = run(decoder, second.frames)

        assertEquals("did not follow the tempo change", 150f, decoder.bpm, 3f)
        val settled = after.filter {
            it.timestampNanos >= second.frames[second.frames.size / 2].timestampNanos
        }
        assertTrue(
            "beats did not re-lock after the change: ${hitRate(settled, second.beatNanos)}",
            hitRate(settled, second.beatNanos) >= 0.8f
        )
    }

    // --- cost ----------------------------------------------------------------

    /**
     * Nothing in the per-frame path may allocate per particle.
     *
     * Measured with `ThreadMXBean.getThreadAllocatedBytes`, which is a HotSpot
     * extension — the test skips where it is unavailable rather than pretending
     * to check. The bound is set well below what a single per-particle
     * allocation would cost (two floats per particle would be ~16 KB a frame at
     * the default cloud size) and well above what the decoder legitimately
     * allocates, which is one [BeatEvent] and its singleton list per beat.
     */
    @Test
    fun `a frame does not allocate per particle`() {
        val allocated = threadAllocatedBytes()
        assumeNotNull(allocated)

        val signal = BeatTestSignals(config).grid(128f, 30f)
        val source = SpectralFluxActivationSource(config)
        val activations = signal.frames.map { source.activation(it) }

        // Warm up: JIT compilation and first-touch class loading allocate, and
        // that has nothing to do with the steady-state cost of a frame.
        val warmup = decoder()
        for (i in signal.frames.indices) warmup.process(signal.frames[i], activations[i])

        val measured = decoder()
        for (i in signal.frames.indices) measured.process(signal.frames[i], activations[i])

        val subject = decoder()
        val before = threadAllocatedBytes()!!
        for (i in signal.frames.indices) subject.process(signal.frames[i], activations[i])
        val after = threadAllocatedBytes()!!

        val perFrame = (after - before).toDouble() / signal.frames.size
        assertTrue("$perFrame bytes allocated per frame", perFrame < MAX_BYTES_PER_FRAME)
        assertTrue("the measurement run produced no beats", measured.bpm > 0f)
    }

    @Test
    fun `the cost of a frame is linear in the particle count, not worse`() {
        val signal = BeatTestSignals(config).grid(128f, 20f)
        val source = SpectralFluxActivationSource(config)
        val activations = signal.frames.map { source.activation(it) }

        fun timeNanos(particles: Int): Long {
            // Two passes: the first warms the JIT, the second is measured.
            repeat(2) { pass ->
                val decoder = ParticleFilterBeatDecoder(config, beatParticleCount = particles)
                val start = System.nanoTime()
                for (i in signal.frames.indices) decoder.process(signal.frames[i], activations[i])
                if (pass == 1) return System.nanoTime() - start
            }
            error("unreachable")
        }

        val small = timeNanos(512)
        val standard = timeNanos(ParticleFilterBeatDecoder.DEFAULT_BEAT_PARTICLES)
        val large = timeNanos(4096)
        val frames = signal.frames.size

        // Printed rather than asserted: an absolute budget would be a machine
        // benchmark, not a property of the decoder. What it is good for is
        // knowing the order of magnitude — at the default cloud size this runs
        // in single-digit microseconds per frame, against a 20 ms frame budget.
        println(
            "particle filter: %.1f us/frame at 512, %.1f at %d (the default), %.1f at 4096".format(
                small / 1000.0 / frames,
                standard / 1000.0 / frames,
                ParticleFilterBeatDecoder.DEFAULT_BEAT_PARTICLES,
                large / 1000.0 / frames
            )
        )

        // Eight times the particles must not cost more than sixteen times the
        // time; anything worse means something in the loop is super-linear.
        assertTrue(
            "512 particles took ${small / 1_000_000} ms, 4096 took ${large / 1_000_000} ms",
            large < small * 16
        )
    }

    /**
     * `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`, reached entirely
     * by reflection: `java.lang.management` is not on the Android unit-test
     * compile classpath even though it is present in the JVM that runs the tests.
     * Returns null wherever it is genuinely unavailable, so the caller can skip.
     */
    private fun threadAllocatedBytes(): Long? = try {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val bean = factory.getMethod("getThreadMXBean").invoke(null)!!
        // Resolved on the *interface*, not on `bean.javaClass`: the implementation
        // lives in the non-exported `sun.management`, so reflecting on it throws
        // InaccessibleObjectException and the test would silently skip.
        val extension = Class.forName("com.sun.management.ThreadMXBean")
        val method = extension.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        @Suppress("DEPRECATION")
        method.invoke(bean, Thread.currentThread().id) as Long
    } catch (e: Throwable) {
        null
    }

    private companion object {
        /**
         * A frame's allocation budget. The decoder emits ~2 beats a second at 50
         * frames a second, so ~0.04 [BeatEvent]s plus singleton lists per frame —
         * a couple of bytes. A per-particle allocation would be three orders of
         * magnitude above this.
         */
        const val MAX_BYTES_PER_FRAME = 128.0
    }
}
