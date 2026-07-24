package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The beat decoder on synthetic grids.
 *
 * Accuracy is asserted the way beat trackers are normally evaluated: each
 * emitted beat is matched to the nearest true beat, and the run passes if
 * enough of them land inside a ±70 ms window — the standard tolerance, and about
 * as tight as a human perceives as "on the beat".
 *
 * Measured on these signals at the time of writing: 90/128/174 BPM all track to
 * within 1 BPM, with over 95% of steady-state beats inside 70 ms.
 */
class BeatTrackerTest {

    private val config = FeatureConfig()
    private val toleranceNanos = 70_000_000L

    private class Run(val beats: List<BeatEvent>, val tracker: BeatTracker)

    private fun run(frames: List<FeatureFrame>, tracker: BeatTracker = BeatTracker(config)): Run {
        val beats = frames.flatMap { tracker.process(it) }
        return Run(beats, tracker)
    }

    /** Fraction of [beats] landing within the tolerance of some true beat. */
    private fun hitRate(beats: List<BeatEvent>, truth: List<Long>): Float {
        if (beats.isEmpty()) return 0f
        val hits = beats.count { beat ->
            truth.any { abs(it - beat.timestampNanos) <= toleranceNanos }
        }
        return hits.toFloat() / beats.size
    }

    /** Beats emitted after the tracker has had time to lock on. */
    private fun steadyState(beats: List<BeatEvent>, afterSeconds: Float = 8f): List<BeatEvent> {
        val cutoff = (afterSeconds * 1e9f).toLong()
        return beats.filter { it.timestampNanos >= cutoff }
    }

    @Test
    fun `silence produces no beats`() {
        val signals = BeatTestSignals(config)

        val result = run(signals.silence(20f))

        assertTrue("silence produced ${result.beats.size} beats", result.beats.isEmpty())
        assertEquals(0f, result.tracker.confidence, 0f)
    }

    @Test
    fun `tracks a 128 BPM grid`() {
        val signals = BeatTestSignals(config)
        val signal = signals.grid(128f, 30f)

        val result = run(signal.frames)
        val steady = steadyState(result.beats)

        assertEquals("tempo", 128f, result.tracker.bpm, 2f)
        assertTrue("only ${steady.size} beats emitted", steady.size > 40)
        val rate = hitRate(steady, signal.beatNanos)
        assertTrue("only ${(rate * 100).toInt()}% of beats landed within 70 ms", rate >= 0.9f)
    }

    @Test
    fun `tracks a slow 90 BPM grid`() {
        val signals = BeatTestSignals(config)
        val signal = signals.grid(90f, 30f)

        val result = run(signal.frames)

        assertEquals(90f, result.tracker.bpm, 2f)
        assertTrue(hitRate(steadyState(result.beats), signal.beatNanos) >= 0.9f)
    }

    @Test
    fun `tracks a fast 174 BPM grid`() {
        val signals = BeatTestSignals(config)
        val signal = signals.grid(174f, 30f)

        val result = run(signal.frames)

        assertEquals(174f, result.tracker.bpm, 2f)
        assertTrue(hitRate(steadyState(result.beats), signal.beatNanos) >= 0.9f)
    }

    @Test
    fun `emits roughly the right number of beats`() {
        val signals = BeatTestSignals(config)
        val signal = signals.grid(120f, 30f)

        val result = run(signal.frames)
        val steady = steadyState(result.beats)
        val expected = 120f / 60f * 22f // 22 s of steady state at 2 beats/sec

        assertEquals(expected, steady.size.toFloat(), expected * 0.1f)
    }

    @Test
    fun `beats are emitted slightly ahead of the instant they describe`() {
        // The tracker must predict, not report: a beat discovered as it happens
        // has already missed the BLE round trip.
        val signals = BeatTestSignals(config)
        val signal = signals.grid(128f, 20f)
        val tracker = BeatTracker(config)

        var emittedAheadCount = 0
        var totalAfterLock = 0
        signal.frames.forEach { frame ->
            tracker.process(frame).forEach { beat ->
                if (frame.timestampNanos >= 8_000_000_000L) {
                    totalAfterLock++
                    if (beat.timestampNanos > frame.timestampNanos) emittedAheadCount++
                }
            }
        }

        assertTrue("no beats were emitted ahead of time", emittedAheadCount > 0)
        assertTrue(totalAfterLock > 0)
    }

    @Test
    fun `downbeats land on the accented beat`() {
        val signals = BeatTestSignals(config)
        val signal = signals.grid(128f, 40f, accentEvery = 4)

        val result = run(signal.frames)
        // Give the bar-phase evidence time to accumulate as well as the tempo.
        val downbeats = steadyState(result.beats, afterSeconds = 20f).filter { it.isDownbeat }

        assertTrue("no downbeats emitted", downbeats.isNotEmpty())
        val rate = hitRate(downbeats, signal.downbeatNanos)
        assertTrue("only ${(rate * 100).toInt()}% of downbeats hit an accent", rate >= 0.8f)
    }

    @Test
    fun `one beat in four is a downbeat`() {
        val signals = BeatTestSignals(config)
        val signal = signals.grid(128f, 40f)

        val beats = steadyState(run(signal.frames).beats, afterSeconds = 20f)
        val downbeats = beats.count { it.isDownbeat }

        assertEquals(beats.size / 4f, downbeats.toFloat(), beats.size / 8f)
        assertEquals(listOf(0, 1, 2, 3), beats.map { it.beatInBar }.distinct().sorted())
    }

    @Test
    fun `a downbeat is bar position zero`() {
        // BeatEvent's contract: beatInBar 0 is the downbeat. The tracker's raw
        // phase counter starts on an arbitrary onset, so the emitted position has
        // to be re-based to the detected downbeat — position 0 must line up with
        // type DOWNBEAT, matching ParticleFilterBeatDecoder.
        val signals = BeatTestSignals(config)
        val signal = signals.grid(128f, 40f, accentEvery = 4)

        val beats = steadyState(run(signal.frames).beats, afterSeconds = 20f)
        val downbeats = beats.filter { it.isDownbeat }

        assertTrue("no downbeats emitted", downbeats.isNotEmpty())
        downbeats.forEach { assertEquals("a downbeat must be bar position 0", 0, it.beatInBar) }
        beats.filter { !it.isDownbeat }.forEach {
            assertTrue("a non-downbeat must not be bar position 0", it.beatInBar != 0)
        }
    }

    @Test
    fun `follows a tempo change`() {
        val signals = BeatTestSignals(config)
        val tracker = BeatTracker(config)
        val first = signals.grid(120f, 25f)
        val frameCount = first.frames.size.toLong()
        val second = signals.grid(150f, 25f, startFrame = frameCount)

        run(first.frames, tracker)
        assertEquals(120f, tracker.bpm, 2f)

        val after = run(second.frames, tracker)

        assertEquals("did not follow the tempo change", 150f, tracker.bpm, 3f)
        // Only the tail is judged: the transition itself is expected to be rough.
        val settled = after.beats.filter { it.timestampNanos >= second.frames[second.frames.size / 2].timestampNanos }
        assertTrue(
            "beats did not re-lock after the change",
            hitRate(settled, second.beatNanos) >= 0.8f
        )
    }

    @Test
    fun `the lock is dropped after a long silence`() {
        val signals = BeatTestSignals(config)
        val tracker = BeatTracker(config)
        val music = signals.grid(128f, 25f)

        run(music.frames, tracker)
        assertTrue(tracker.confidence > 0f)

        val silenceStart = music.frames.size.toLong()
        val quiet = signals.silence(6f, startFrame = silenceStart)
        // The tracker is expected to keep predicting briefly — it cannot know a
        // gap is not just a bar's rest — but it must give up rather than run on.
        val heldOverFrame = quiet[(4 * config.framesPerSecond).toInt()]
        val beatsInSilence = run(quiet, tracker).beats

        assertTrue(
            "still emitting beats ${beatsInSilence.count { it.timestampNanos >= heldOverFrame.timestampNanos }} " +
                "seconds into silence",
            beatsInSilence.none { it.timestampNanos >= heldOverFrame.timestampNanos }
        )
        assertEquals(0f, tracker.confidence, 0f)
    }

    @Test
    fun `reset clears the lock`() {
        val signals = BeatTestSignals(config)
        val tracker = BeatTracker(config)
        run(signals.grid(128f, 25f).frames, tracker)
        assertTrue(tracker.bpm > 0f)

        tracker.reset()

        assertEquals(0f, tracker.bpm, 0f)
        assertEquals(0f, tracker.confidence, 0f)
        assertEquals(null, tracker.nextBeatTimestampNanos)
    }

    @Test
    fun `reports a next-beat prediction while locked`() {
        val signals = BeatTestSignals(config)
        val tracker = BeatTracker(config)
        val signal = signals.grid(128f, 25f)
        run(signal.frames, tracker)

        val next = tracker.nextBeatTimestampNanos
        assertTrue("no prediction while locked", next != null)
        val lastFrameTime = signal.frames.last().timestampNanos
        val periodNanos = (60f / 128f * 1e9f).toLong()
        assertTrue(
            "prediction $next is not within a beat of the last frame $lastFrameTime",
            next!! > lastFrameTime && next - lastFrameTime <= periodNanos + 20_000_000L
        )
    }
}
