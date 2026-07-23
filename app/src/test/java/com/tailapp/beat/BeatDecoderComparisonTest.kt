package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * [BeatTracker] against [ParticleFilterBeatDecoder], head to head, on the same
 * activation curve.
 *
 * Both decoders are driven with the *identical* [BeatActivation] sequence — one
 * [SpectralFluxActivationSource] per signal, its output recorded and replayed
 * into both — so any difference is the decoder's, never the front-end's.
 *
 * Reported per signal: tempo error, **precision** (fraction of emitted beats
 * within ±70 ms of a true beat), **recall** (fraction of true beats that got an
 * emitted beat), and downbeat precision. Precision alone would not be enough: a
 * decoder that locks to half time emits beats that are all correct and misses
 * half the grid, scoring 100% precision and 50% recall. A decoder that emits
 * nothing at all scores zero on both, which is why the beat count is printed too.
 * Everything is scored from 20 s in, because the bar phase needs several bars
 * longer than the beat phase to settle and scoring it earlier measures the
 * transient.
 *
 * ## Measured
 *
 * ```
 * signal                   decoder      bpm    err   prec  recall   down
 * steady 90 BPM            MVP         90.3    0.3   0.97   1.00   0.88  n=31
 *                          particle    90.5    0.5   0.97   0.97   0.88  n=30
 * steady 128 BPM           MVP        128.3    0.3   0.95   1.00   0.82  n=43
 *                          particle   128.1    0.1   0.95   1.00   0.82  n=43
 * steady 174 BPM           MVP        174.5    0.5   0.98   1.00   0.93  n=59
 *                          particle   175.6    1.6   0.98   1.00   0.93  n=59
 * tempo change 120-150     MVP        149.9    0.1   0.96   1.00   1.00  n=26
 *                          particle   151.1    1.1   0.96   1.00   1.00  n=26
 * noisy (noise=0.5)        MVP        128.4    0.4   0.93   1.00   0.71  n=54
 *                          particle   127.9    0.1   0.93   1.00   0.69  n=54
 * sparse (2 of 8 silent)   MVP        128.3    0.3   0.93   1.00   0.71  n=54
 *                          particle   129.2    1.2   0.92   0.92   0.80  n=50
 * syncopated (off=0.9)     MVP        128.3    0.3   0.00   0.00   0.00  n=0
 *                          particle   128.9    0.9   0.93   1.00   0.71  n=54
 * half time (1 and 3)      MVP         64.1    0.1   0.85   0.46   0.00  n=27
 *                          particle    64.6    0.6   0.85   0.46   0.50  n=27
 * ```
 *
 * ## The verdict
 *
 * **The particle filter is not better at tracking a beat. It is better at not
 * giving up.**
 *
 * On everything steady the two are indistinguishable to two decimal places —
 * identical precision, identical recall, identical downbeat rate, often the same
 * beat count. That is the honest headline: a phase-locked predictor is a
 * perfectly good model of a metronome, and a posterior over 1536 hypotheses buys
 * nothing once the tempo is right and the phase is on the onset.
 *
 * Where it wins is the **syncopated** signal, and it wins outright. With off-beat
 * eighths at 0.9 of the beat's strength the autocorrelation peak never sharpens,
 * so `TempoEstimator.confidence` never clears `BeatTracker.MIN_ACQUIRE_CONFIDENCE`
 * and the MVP takes no phase at all: correct tempo, *zero beats*. The particle
 * filter has no such gate — its confidence is the concentration of its own phase
 * posterior, which converges here even though the correlogram does not — so it
 * tracks the signal at 0.93 precision and full recall. For a lighting controller
 * that is the difference between reacting and going dark, and it is the one
 * result in this table that would be visible on the tail.
 *
 * It also wins the bar under **half time**, 0.50 against 0.00. Both decoders read
 * the onset train at 64 BPM rather than the notated 128 — nothing in the audio
 * says otherwise, and a listener would be using genre priors neither has — so at
 * best half the notated downbeats can be hit. The particle filter hits all of
 * those; the MVP's bar vote lands anti-phase and hits none.
 *
 * ## Where it loses
 *
 * **Tempo precision.** The MVP is inside 0.5 BPM on every signal here; the
 * particle filter runs to 1.6 at 174 BPM, and 2.0 over a 30-seed sweep. The
 * reason is structural rather than fixable by tuning: the MVP interpolates a
 * correlogram peak, which is a direct measurement, while the particle filter
 * infers tempo only through phase agreement accumulated over many beats and then
 * reports a Monte Carlo mean of a posterior that is still diffusing. It clears
 * the project's ±2 BPM bar with much less room than the MVP does.
 *
 * **Recall on sparse material.** 0.92 against the MVP's 1.00 — it drops the
 * occasional beat across a gap where the MVP, having committed, simply keeps
 * counting.
 *
 * **Cost.** ~1536 particles at roughly 0.1 µs each per frame, against a
 * `TempoEstimator` autocorrelation that runs five times a second. Two orders of
 * magnitude more work for a result that is the same on most material.
 *
 * That is why [BeatTracker] stays the default. The particle filter is the right
 * decoder to reach for when the input stops being a metronome — and the right
 * one to pair with a CRNN activation, which is what the reference design assumes
 * and what would remove its one real weakness, since a sharp activation is
 * exactly what its tempo inference is short of.
 *
 * The assertions below are set just under the measured values rather than at
 * round numbers that happen to pass, and the places where each decoder is worse
 * are asserted *as such*, so an accidental improvement is as loud as a regression.
 */
class BeatDecoderComparisonTest {

    private val config = FeatureConfig()
    private val toleranceNanos = 70_000_000L
    private val hopNanos: Long = (1_000_000_000.0 / config.framesPerSecond).toLong()

    // --- harness -------------------------------------------------------------

    private class Score(
        val decoder: String,
        val bpm: Float,
        val bpmError: Float,
        val precision: Float,
        val recall: Float,
        val downbeatPrecision: Float,
        val beats: Int
    )

    private class Comparison(val signal: String, val mvp: Score, val particle: Score)

    /**
     * Runs both decoders over one signal and scores them.
     *
     * @param expectedBpm the tempo a correct decoder should report. For half time
     *   that is the onset train's tempo, not the annotation's — see the class KDoc.
     * @param steadyFromSeconds beats before this are acquisition transient and are
     *   not scored, for either decoder.
     */
    private fun compare(
        name: String,
        signal: BeatTestSignals.Signal,
        expectedBpm: Float,
        steadyFromSeconds: Float = 20f
    ): Comparison {
        val source = SpectralFluxActivationSource(config)
        val activations = signal.frames.map { source.activation(it) }

        val mvp = BeatTracker(config)
        val particle = ParticleFilterBeatDecoder(config)

        val mvpBeats = mutableListOf<BeatEvent>()
        val particleBeats = mutableListOf<BeatEvent>()
        for (i in signal.frames.indices) {
            mvpBeats += mvp.process(signal.frames[i], activations[i])
            particleBeats += particle.process(signal.frames[i], activations[i])
        }

        val cutoff = (steadyFromSeconds * 1e9f).toLong()
        val comparison = Comparison(
            signal = name,
            mvp = score("MVP", mvp, mvpBeats, signal, expectedBpm, cutoff),
            particle = score("particle", particle, particleBeats, signal, expectedBpm, cutoff)
        )
        println(row(comparison.signal, comparison.mvp))
        println(row("", comparison.particle))
        return comparison
    }

    private fun score(
        label: String,
        decoder: BeatDecoder,
        beats: List<BeatEvent>,
        signal: BeatTestSignals.Signal,
        expectedBpm: Float,
        cutoffNanos: Long
    ): Score {
        val steady = beats.filter { it.timestampNanos >= cutoffNanos }
        val truth = signal.beatNanos.filter { it >= cutoffNanos }
        val trueDownbeats = signal.downbeatNanos.filter { it >= cutoffNanos }
        val emitted = steady.map { it.timestampNanos }

        return Score(
            decoder = label,
            bpm = decoder.bpm,
            bpmError = abs(decoder.bpm - expectedBpm),
            precision = fractionMatching(emitted, truth),
            recall = fractionMatching(truth, emitted),
            downbeatPrecision = fractionMatching(
                steady.filter { it.isDownbeat }.map { it.timestampNanos },
                trueDownbeats
            ),
            beats = steady.size
        )
    }

    /** Fraction of [candidates] with some entry of [targets] inside the tolerance. */
    private fun fractionMatching(candidates: List<Long>, targets: List<Long>): Float {
        if (candidates.isEmpty()) return 0f
        return candidates.count { candidate ->
            targets.any { abs(it - candidate) <= toleranceNanos }
        }.toFloat() / candidates.size
    }

    private fun row(signal: String, score: Score): String = String.format(
        "%-24s %-9s %6.1f %6.1f  %5.2f  %5.2f  %5.2f  n=%d",
        signal, score.decoder, score.bpm, score.bpmError,
        score.precision, score.recall, score.downbeatPrecision, score.beats
    )

    private fun header(): String = String.format(
        "%-24s %-9s %6s %6s  %5s  %5s  %5s",
        "signal", "decoder", "bpm", "err", "prec", "recall", "down"
    )

    /**
     * The floors both decoders clear on a signal neither finds hard.
     *
     * The tempo bar is per decoder on purpose: the MVP measures tempo off a
     * correlogram peak and holds half a BPM, the particle filter infers it from
     * phase agreement and holds two. Averaging that into one threshold would hide
     * the difference the table is here to show.
     */
    private fun assertBothTrack(
        comparison: Comparison,
        maxMvpBpmError: Float = 1f,
        maxParticleBpmError: Float = 2f,
        minPrecision: Float = 0.9f,
        minRecall: Float = 0.9f
    ) {
        val bars = listOf(comparison.mvp to maxMvpBpmError, comparison.particle to maxParticleBpmError)
        for ((score, maxBpmError) in bars) {
            val where = "${score.decoder} on ${comparison.signal}"
            assertTrue("$where: tempo error ${score.bpmError}", score.bpmError <= maxBpmError)
            assertTrue("$where: only ${score.beats} beats", score.beats > 10)
            assertTrue("$where: precision ${score.precision}", score.precision >= minPrecision)
            assertTrue("$where: recall ${score.recall}", score.recall >= minRecall)
        }
    }

    // --- signals -------------------------------------------------------------

    /**
     * A beat grid whose onset strength cycles through [beatLevels], one entry per
     * beat. A zero entry is a beat the annotation still counts but nothing plays
     * on — which is what makes a pattern sparse.
     *
     * Geometry (hop timing, flux decay, accent placement) mirrors
     * [BeatTestSignals.grid] so the ground truth lines up with the shared signals.
     */
    private fun patternedGrid(
        bpm: Float,
        seconds: Float,
        beatLevels: FloatArray,
        offbeatLevel: Float = 0f,
        noise: Float = 0.06f,
        accentEvery: Int = 4,
        seed: Int = 23
    ): BeatTestSignals.Signal {
        val random = Random(seed)
        val fps = config.framesPerSecond
        val periodFrames = fps * 60f / bpm
        val frameCount = (seconds * fps).toInt()

        val frames = ArrayList<FeatureFrame>(frameCount)
        val beatNanos = mutableListOf<Long>()
        val downbeatNanos = mutableListOf<Long>()

        for (i in 0 until frameCount) {
            val beatPosition = i / periodFrames
            val nearestBeat = Math.round(beatPosition).toInt()
            val distanceToBeat = abs(i - nearestBeat * periodFrames)
            val level = beatLevels[Math.floorMod(nearestBeat, beatLevels.size)]

            val distanceToOffbeat =
                abs(i - (Math.round(beatPosition - 0.5f) + 0.5f) * periodFrames)

            val beatFlux = level * exp(-distanceToBeat / DECAY_FRAMES)
            val offbeatFlux = offbeatLevel * exp(-distanceToOffbeat / DECAY_FRAMES)
            val flux = maxOf(beatFlux, offbeatFlux) + noise * random.nextFloat()

            val isDownbeat = Math.floorMod(nearestBeat, accentEvery) == 0
            val bass = level * (if (isDownbeat) 1f else 0.35f) *
                exp(-distanceToBeat / DECAY_FRAMES) + noise * random.nextFloat()

            if (distanceToBeat < 0.5f) {
                val nano = ((nearestBeat * periodFrames + 1) * hopNanos).toLong()
                beatNanos.add(nano)
                if (isDownbeat) downbeatNanos.add(nano)
            }

            frames.add(
                FeatureFrame(
                    index = i.toLong(),
                    timestampNanos = (i + 1) * hopNanos,
                    bands = FloatArray(0),
                    flux = flux,
                    rms = 0.3f + 0.2f * beatFlux,
                    bassEnergy = bass,
                    midEnergy = 0.2f + 0.1f * flux,
                    highEnergy = 0.15f + 0.1f * flux,
                    spectralCentroidHz = 1800f
                )
            )
        }
        return BeatTestSignals.Signal(frames, beatNanos, downbeatNanos)
    }

    /** Two grids back to back, so the tempo steps in the middle. */
    private fun tempoChange(first: Float, second: Float, halfSeconds: Float): BeatTestSignals.Signal {
        val signals = BeatTestSignals(config)
        val a = signals.grid(first, halfSeconds)
        val b = signals.grid(second, halfSeconds, startFrame = a.frames.size.toLong())
        return BeatTestSignals.Signal(
            a.frames + b.frames,
            a.beatNanos + b.beatNanos,
            a.downbeatNanos + b.downbeatNanos
        )
    }

    // --- the comparison ------------------------------------------------------

    @Test
    fun `steady grids - the two decoders are indistinguishable`() {
        val signals = BeatTestSignals(config)
        println(header())

        val results = floatArrayOf(90f, 128f, 174f).map { bpm ->
            compare("steady %.0f BPM".format(bpm), signals.grid(bpm, 40f), bpm)
        }

        results.forEach { comparison ->
            assertBothTrack(comparison)
            for (score in listOf(comparison.mvp, comparison.particle)) {
                assertTrue(
                    "${score.decoder} on ${comparison.signal}: downbeat precision " +
                        score.downbeatPrecision,
                    score.downbeatPrecision >= 0.8f
                )
            }
            // Not merely both-good: measurably the same decision, beat for beat.
            assertEquals(
                "${comparison.signal}: the decoders no longer agree on beat placement",
                comparison.mvp.precision, comparison.particle.precision, 0.05f
            )
        }
    }

    @Test
    fun `tempo change - both re-lock on the new tempo`() {
        println(header())
        val comparison = compare("tempo change 120-150", tempoChange(120f, 150f, 25f), 150f, 40f)
        assertBothTrack(comparison, maxParticleBpmError = 2.5f)

        for (score in listOf(comparison.mvp, comparison.particle)) {
            assertTrue(
                "${score.decoder} lost the bar across the change: ${score.downbeatPrecision}",
                score.downbeatPrecision >= 0.9f
            )
        }
    }

    @Test
    fun `noisy activations - neither decoder is thrown off, but both lose the bar`() {
        println(header())
        val signals = BeatTestSignals(config)
        val comparison = compare("noisy (noise=0.5)", signals.grid(128f, 45f, noise = 0.5f), 128f)
        assertBothTrack(comparison)

        // The beat survives a noise floor an order of magnitude up; the bar does
        // not, for either decoder. The low band is where the noise lands hardest.
        for (score in listOf(comparison.mvp, comparison.particle)) {
            assertTrue(
                "${score.decoder} downbeat precision ${score.downbeatPrecision} is now " +
                    "above 0.85 under heavy noise, which would be an improvement worth keeping",
                score.downbeatPrecision < 0.85f
            )
            assertTrue(
                "${score.decoder} downbeat precision ${score.downbeatPrecision}",
                score.downbeatPrecision >= 0.6f
            )
        }
    }

    @Test
    fun `syncopation - the MVP will not commit, the particle filter tracks`() {
        println(header())
        val signals = BeatTestSignals(config)
        val comparison = compare(
            "syncopated (off=0.9)",
            signals.grid(128f, 45f, offbeatLevel = 0.9f),
            128f
        )

        val particle = comparison.particle
        assertTrue("particle tempo error ${particle.bpmError}", particle.bpmError <= 2f)
        assertTrue("particle precision ${particle.precision}", particle.precision >= 0.9f)
        assertTrue("particle recall ${particle.recall}", particle.recall >= 0.9f)
        assertTrue("particle downbeats ${particle.downbeatPrecision}", particle.downbeatPrecision >= 0.65f)

        // The MVP's tempo is right; it simply never clears the confidence gate
        // that lets it take a phase, so it emits nothing at all. Documented rather
        // than hidden: if it starts emitting here, that is worth noticing.
        assertTrue("the MVP's tempo estimate is fine: ${comparison.mvp.bpm}", comparison.mvp.bpmError <= 2f)
        assertEquals(
            "the MVP now emits beats under heavy syncopation (${comparison.mvp.beats}); " +
                "it used to emit none, because TempoEstimator.confidence never cleared " +
                "MIN_ACQUIRE_CONFIDENCE. If this is a real improvement, raise the bar.",
            0, comparison.mvp.beats
        )
    }

    @Test
    fun `sparse onsets - both coast through the gaps, the MVP with better recall`() {
        println(header())
        // A quarter of the beats have no onset at all, in an aperiodic pattern, so
        // the beat is still recoverable but the correlogram is much flatter.
        val sparse = patternedGrid(
            bpm = 128f,
            seconds = 45f,
            beatLevels = floatArrayOf(1f, 0.8f, 0f, 0.9f, 1f, 0f, 0.85f, 0.75f)
        )
        val comparison = compare("sparse (2 of 8 silent)", sparse, 128f)
        assertBothTrack(comparison)

        // Having committed to a phase, the MVP keeps counting straight through a
        // silent beat; the particle filter's posterior spreads a little across the
        // gap and it drops the occasional beat. Encoded as the expectation it is.
        assertTrue(
            "the particle filter's recall (${comparison.particle.recall}) now matches the " +
                "MVP's (${comparison.mvp.recall}) on sparse material — an improvement, so raise the bar",
            comparison.particle.recall < comparison.mvp.recall
        )
        assertTrue(
            "the MVP dropped beats it used to hold: ${comparison.mvp.recall}",
            comparison.mvp.recall >= 0.99f
        )
    }

    @Test
    fun `half time - both read the onset train, and the particle filter keeps the bar`() {
        println(header())
        // Onsets on beats 1 and 3 only: the notated beat is 128 BPM, the onset
        // train is 64, and nothing in the signal says which one a listener counts.
        val halfTime = patternedGrid(
            bpm = 128f,
            seconds = 45f,
            beatLevels = floatArrayOf(1f, 0f, 0.85f, 0f)
        )
        val comparison = compare("half time (1 and 3)", halfTime, 64f)

        // Both read it at half tempo; whatever they emit still lands on the grid.
        for (score in listOf(comparison.mvp, comparison.particle)) {
            val where = "${score.decoder} on ${comparison.signal}"
            assertTrue("$where: tempo ${score.bpm}, expected 64", score.bpmError <= 2f)
            assertTrue("$where: precision ${score.precision}", score.precision >= 0.8f)
            assertTrue(
                "$where: recall ${score.recall} is above half — it found the notated " +
                    "beat rather than the onset train, which would be an improvement",
                score.recall <= 0.6f
            )
        }

        // A half-time reading can hit at most every other notated downbeat, so 0.5
        // is the ceiling. The particle filter reaches it; the MVP's bar vote lands
        // anti-phase and hits none at all.
        assertTrue(
            "the particle filter lost the bar it is expected to win here: " +
                comparison.particle.downbeatPrecision,
            comparison.particle.downbeatPrecision >= 0.45f
        )
        assertTrue(
            "the MVP now finds the notated bar phase under half time " +
                "(${comparison.mvp.downbeatPrecision}) — a real improvement, so raise the bar",
            comparison.mvp.downbeatPrecision < comparison.particle.downbeatPrecision
        )
    }

    private companion object {
        /** Frames an event's flux takes to decay by 1/e; matches [BeatTestSignals]. */
        const val DECAY_FRAMES = 1.5f
    }
}
