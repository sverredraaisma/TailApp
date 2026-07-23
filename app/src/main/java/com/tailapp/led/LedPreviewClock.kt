package com.tailapp.led

import com.tailapp.model.LedState

/**
 * Turns wall-clock timestamps into rendered frames for the live LED preview.
 *
 * [LedStackRenderer] only knows about per-frame `dt`; this is the thin timing
 * layer on top that converts absolute instants (as `withFrameNanos` on the
 * Compose side hands them out) into that `dt`, plus the two guards a UI-driven
 * clock needs that a fixed-tick firmware loop doesn't:
 *
 * - the very first frame has no previous timestamp to diff against, so `dt`
 *   is `0` rather than some huge or undefined value:
 * - a gap longer than [MAX_DT_SECONDS] - e.g. the app was backgrounded and the
 *   preview's `LaunchedEffect` didn't run for a while - is clamped instead of
 *   being handed to the renderer whole, so a running effect (Rainbow's hue
 *   phase, an audio decay) doesn't visibly jump to "catch up" the instant the
 *   screen comes back.
 *
 * Pure JVM - no `android.*` imports - so this is exercised directly in unit
 * tests; [com.tailapp.ui.components.LedPreview] is the only caller that deals
 * with actual frame timing (`withFrameNanos`).
 *
 * [audio] is exposed so callers (the LED config view model) can push FFT
 * frames into it - see [AudioLevelSource.write] - without needing a separate
 * reference to whatever [AudioLevelSource] this preview's renderer happens to
 * be using internally.
 */
class LedPreviewClock(
    val audio: AudioLevelSource = AudioLevelSource(),
    imageSupplier: () -> ImageData? = { null },
) {
    private val renderer = LedStackRenderer(audio, imageSupplier)

    private var lastFrameNanos: Long = NO_FRAME_YET

    /** Forwards to [LedStackRenderer.setState] - see its KDoc for what does and doesn't reset running effect state. */
    fun setState(state: LedState) {
        renderer.setState(state)
    }

    /**
     * Renders the frame for wall-clock instant [nowNanos]. See the class KDoc
     * for the first-frame and clamped-gap rules.
     */
    fun frameAt(nowNanos: Long): PixelBuffer {
        val dtSeconds = if (lastFrameNanos == NO_FRAME_YET) {
            0f
        } else {
            ((nowNanos - lastFrameNanos).coerceAtLeast(0L) / 1_000_000_000f)
                .coerceAtMost(MAX_DT_SECONDS)
        }
        lastFrameNanos = nowNanos
        return renderer.renderFrame(dtSeconds)
    }

    /**
     * Forgets the last frame's timestamp, so the *next* [frameAt] call is
     * treated as a first frame (`dt == 0`) regardless of how long ago the
     * previous one was. This does not touch the renderer's own running effect
     * state (e.g. Rainbow's hue phase) - only the timing bookkeeping here.
     */
    fun reset() {
        lastFrameNanos = NO_FRAME_YET
    }

    companion object {
        /** Longer real-world gaps between frames are clamped to this many seconds of animation. */
        const val MAX_DT_SECONDS: Float = 0.1f

        private const val NO_FRAME_YET = Long.MIN_VALUE
    }
}
