package com.tailapp.composer

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import com.tailapp.beat.AdaptivePeakNormalizer
import com.tailapp.beat.BeatEvent
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import com.tailapp.drop.SectionStateUpdate
import com.tailapp.genre.GenreState
import com.tailapp.led.PixelBuffer
import com.tailapp.lighting.LightingOutput
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns the analysis tiers into a [ReactiveContext] and renders the active
 * [Composition] from it — the piece that replaces both `ReactiveRenderer` (which
 * drew one hard-coded look) and `EffectController` (which chose that look from
 * the genre).
 *
 * Three jobs:
 * - **Accumulate.** Beats, drops, sections, genre and audio arrive on the
 *   analysis loop at three different rates; the scene holds the latest of each.
 * - **Normalise.** Raw `rms` and band energies are tiny, level-dependent numbers.
 *   Effects need a usable `0..1`, so each is smoothed and then divided by a
 *   slow-decaying recent peak ([AdaptivePeakNormalizer], reused from the beat
 *   tier). That is what makes a threshold like "fire above 0.15" mean the same
 *   thing on a quiet phone mic and a loud line input.
 * - **Render.** Build the context for the frame's instant and hand it to
 *   [CompositionRenderer], then publish the frame to the [output].
 *
 * ## Threading
 * Every method except [setComposition] must be called from the single render/
 * analysis thread `LightingEngine` confines its loops to. [setComposition] is
 * the one cross-thread entry point — the editor calls it from the main thread —
 * so the incoming tree is parked in a `@Volatile` field and swapped in at the
 * top of the next [render]. Compositions are immutable, so publishing one is
 * safe; applying it on the render thread is what keeps the renderer's live
 * effect instances single-threaded.
 *
 * ## Calibration
 * [triggerOffsetMillis] shifts every beat and drop timestamp before it reaches
 * the context, exactly as `EffectController` did. Sections are deliberately not
 * shifted: they drive slow modulation where tens of milliseconds are invisible,
 * and offsetting them as well would apply the calibration twice to the beats
 * inside a build-up.
 */
class CompositionScene(
    private val output: LightingOutput,
    featureConfig: FeatureConfig = FeatureConfig()
) {
    private val renderer = CompositionRenderer()

    /** User calibration in milliseconds; negative fires earlier. */
    var triggerOffsetMillis: Float = 0f

    /**
     * The tree waiting to be swapped in, published from whichever thread edited
     * it and claimed by the render thread with a single [AtomicReference.getAndSet].
     *
     * A plain `@Volatile` field read-then-nulled would lose an edit that landed
     * between the two operations: the render loop would null out a composition it
     * never applied, and the tail would sit on a stale tree until the next edit
     * happened to arrive.
     */
    private val pending = AtomicReference<Composition?>(Composition.EMPTY)

    private var lastBeat: BeatEvent? = null
    private var beatCount = 0
    private var lastDrop: DropEvent? = null
    private var section = SectionStateUpdate(SectionState.UNKNOWN, 0f, 0L, 0f)
    private var genre = GenreState.unknown()

    /**
     * Tail state arrives from the BLE collector on a different thread than the
     * render loop, so it crosses the same way a composition does: published into
     * an atomic and claimed on the render thread.
     *
     * A tap is a one-shot, so it queues (and coalesces to the most recent — at
     * ~20 taps a second nobody can distinguish two of them anyway, and a queue
     * that grows while the renderer is paused would replay a burst on resume).
     * Telemetry is level state, so the newest simply wins.
     */
    private val pendingTap = AtomicReference<TapEvent?>(null)
    private val pendingTelemetry = AtomicReference<TailTelemetry?>(null)

    private var lastTap: TapEvent? = null
    private var tapCount = 0
    private var tail = TailTelemetry.AT_REST

    private var originNanos = Long.MIN_VALUE
    private var lastFrameNanos = Long.MIN_VALUE

    // --- audio, smoothed then adaptively normalised ---

    private var bands = FloatArray(0)
    private var rawRms = 0f
    private var levelEnvelope = 0f
    private var bassEnvelope = 0f
    private var midEnvelope = 0f
    private var highEnvelope = 0f

    private val framesPerSecond = featureConfig.framesPerSecond
    private val levelNormalizer = AdaptivePeakNormalizer(framesPerSecond = framesPerSecond)
    private val bassNormalizer = AdaptivePeakNormalizer(framesPerSecond = framesPerSecond)
    private val midNormalizer = AdaptivePeakNormalizer(framesPerSecond = framesPerSecond)
    private val highNormalizer = AdaptivePeakNormalizer(framesPerSecond = framesPerSecond)
    private val spectrumNormalizer = AdaptivePeakNormalizer(framesPerSecond = framesPerSecond)

    private var level = 0f
    private var bass = 0f
    private var mid = 0f
    private var high = 0f

    /** LEDs the current layout holds. */
    val ledCount: Int get() = renderer.ledCount

    /** The composition being rendered, or the one queued to be. */
    val composition: Composition get() = pending.get() ?: renderer.currentComposition

    /** Re-reads the strip layout, e.g. after the device reports a new ring config. */
    fun setLayout(ledsPerRing: List<Int>) = renderer.setLayout(ledsPerRing)

    /**
     * Queues [composition] to be rendered from the next frame on. Safe to call
     * from any thread; see the class doc.
     */
    fun setComposition(composition: Composition) {
        pending.set(composition)
    }

    fun onBeat(event: BeatEvent) {
        val shifted = event.copy(timestampNanos = event.timestampNanos + offsetNanos())
        lastBeat = shifted
        beatCount++
        output.onBeat(shifted)
    }

    fun onDrop(event: DropEvent) {
        val shifted = event.copy(timestampNanos = event.timestampNanos + offsetNanos())
        lastDrop = shifted
        output.onDrop(shifted)
    }

    fun onSection(update: SectionStateUpdate) {
        section = update
    }

    fun onGenre(state: GenreState) {
        genre = state
    }

    /**
     * Records a tap from one of the tail's IMUs. Safe to call from any thread.
     *
     * Deliberately *not* shifted by [triggerOffsetMillis]: that calibration
     * exists to make a predicted beat land on the ear despite the BLE round
     * trip, whereas a tap is already a past event by the time it reaches us.
     * Delaying it further would only make the tail feel unresponsive.
     */
    fun onTap(end: TailEnd, timestampNanos: Long) {
        pendingTap.set(TapEvent(end, timestampNanos))
    }

    /** Publishes the latest physical tail state. Safe to call from any thread. */
    fun onTailTelemetry(telemetry: TailTelemetry) {
        pendingTelemetry.set(telemetry)
    }

    /**
     * Folds one analysis frame into the smoothed, normalised audio the context
     * exposes.
     *
     * Attack is fast and release is slow, so a transient reaches full level on
     * the frame it happens and then falls away — an effect following `level` sees
     * a musical envelope rather than the waveform's own jitter.
     */
    fun onAudioFrame(frame: FeatureFrame) {
        rawRms = frame.rms
        levelEnvelope = smooth(levelEnvelope, frame.rms)
        bassEnvelope = smooth(bassEnvelope, frame.bassEnergy)
        midEnvelope = smooth(midEnvelope, frame.midEnergy)
        highEnvelope = smooth(highEnvelope, frame.highEnergy)

        level = levelNormalizer.normalize(levelEnvelope)
        bass = bassNormalizer.normalize(bassEnvelope)
        mid = midNormalizer.normalize(midEnvelope)
        high = highNormalizer.normalize(highEnvelope)

        updateSpectrum(frame.bands)
    }

    /**
     * Rescales the whole spectrum by one shared peak rather than per band.
     *
     * Normalising each band against its own peak would flatten the spectrum into
     * noise — every band, however quiet, would reach 1 eventually, and a spectrum
     * analyser would show a full-height wall on silence. One shared reference
     * keeps the *relative* shape of the spectrum, which is the only thing a
     * spectrum effect is trying to draw.
     */
    private fun updateSpectrum(source: FloatArray) {
        if (bands.size != source.size) bands = FloatArray(source.size)

        var max = 0f
        for (v in source) if (v > max) max = v

        spectrumNormalizer.normalize(max)
        val peak = spectrumNormalizer.currentPeak
        if (peak <= 0f) {
            bands.fill(0f)
            return
        }
        for (i in source.indices) bands[i] = (source[i] / peak).coerceIn(0f, 1f)
    }

    /**
     * Renders and publishes the frame for [nowNanos].
     *
     * @return the renderer's own buffer — valid until the next call.
     */
    fun render(nowNanos: Long): PixelBuffer {
        pending.getAndSet(null)?.let(renderer::setComposition)
        pendingTap.getAndSet(null)?.let { tap ->
            lastTap = tap
            tapCount++
        }
        pendingTelemetry.getAndSet(null)?.let { tail = it }

        if (originNanos == Long.MIN_VALUE) originNanos = nowNanos
        val dtSeconds =
            if (lastFrameNanos == Long.MIN_VALUE) 0f
            else (nowNanos - lastFrameNanos) / NANOS_PER_SECOND
        lastFrameNanos = nowNanos

        val frame = renderer.render(buildContext(nowNanos, dtSeconds))
        output.onFrame(frame, nowNanos)
        return frame
    }

    /** The context for one frame; visible for tests that assert on it directly. */
    internal fun buildContext(nowNanos: Long, dtSeconds: Float): ReactiveContext {
        val beat = lastBeat
        val bpm = beat?.bpm ?: 0f

        val secondsSinceBeat =
            if (beat == null) ReactiveContext.NO_EVENT_SECONDS
            else (nowNanos - beat.timestampNanos) / NANOS_PER_SECOND

        // Phase keeps cycling at the last known tempo even if a beat is missed,
        // so anything driven by it coasts through a dropout instead of freezing.
        val beatPhase =
            if (beat != null && bpm > 0f && secondsSinceBeat >= 0f) {
                frac(secondsSinceBeat / (SECONDS_PER_MINUTE / bpm))
            } else {
                0f
            }

        val barPhase =
            if (beat != null && bpm > 0f && secondsSinceBeat >= 0f) {
                frac((beat.beatInBar + beatPhase) / BEATS_PER_BAR)
            } else {
                0f
            }

        val drop = lastDrop
        val secondsSinceDrop =
            if (drop == null) ReactiveContext.NO_EVENT_SECONDS
            else (nowNanos - drop.timestampNanos) / NANOS_PER_SECOND

        val tap = lastTap
        val secondsSinceTap =
            if (tap == null) ReactiveContext.NO_EVENT_SECONDS
            else (nowNanos - tap.timestampNanos) / NANOS_PER_SECOND

        return ReactiveContext(
            nowNanos = nowNanos,
            timeSeconds = (nowNanos - originNanos) / NANOS_PER_SECOND,
            dtSeconds = dtSeconds,
            bpm = bpm,
            lastBeat = beat,
            beatCount = beatCount,
            secondsSinceBeat = secondsSinceBeat,
            beatPhase = beatPhase,
            barPhase = barPhase,
            onDownbeat = beat?.isDownbeat == true,
            level = level,
            rms = rawRms,
            bass = bass,
            mid = mid,
            high = high,
            bands = bands,
            lastDrop = drop,
            secondsSinceDrop = secondsSinceDrop,
            section = section.state,
            sectionRamp = section.ramp,
            genre = genre,
            lastTap = tap,
            secondsSinceTap = secondsSinceTap,
            tapCount = tapCount,
            tail = tail
        )
    }

    fun reset() {
        lastBeat = null
        beatCount = 0
        lastDrop = null
        lastTap = null
        tapCount = 0
        tail = TailTelemetry.AT_REST
        pendingTap.set(null)
        pendingTelemetry.set(null)
        section = SectionStateUpdate(SectionState.UNKNOWN, 0f, 0L, 0f)
        genre = GenreState.unknown()
        originNanos = Long.MIN_VALUE
        lastFrameNanos = Long.MIN_VALUE

        rawRms = 0f
        levelEnvelope = 0f
        bassEnvelope = 0f
        midEnvelope = 0f
        highEnvelope = 0f
        level = 0f
        bass = 0f
        mid = 0f
        high = 0f
        bands = FloatArray(0)

        levelNormalizer.reset()
        bassNormalizer.reset()
        midNormalizer.reset()
        highNormalizer.reset()
        spectrumNormalizer.reset()

        renderer.reset()
    }

    private fun smooth(current: Float, target: Float): Float {
        val coefficient = if (target > current) ATTACK else RELEASE
        return current + (target - current) * coefficient
    }

    private fun offsetNanos(): Long = (triggerOffsetMillis * NANOS_PER_MILLI).toLong()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val NANOS_PER_MILLI = 1_000_000f
        const val SECONDS_PER_MINUTE = 60f

        /** The metre the beat tracker assumes when it labels a downbeat. */
        const val BEATS_PER_BAR = 4f

        // Per analysis frame (~20 ms). Fast enough to catch a kick on the frame
        // it lands, slow enough that the release reads as a decay rather than a
        // flicker.
        const val ATTACK = 0.5f
        const val RELEASE = 0.06f
    }
}
