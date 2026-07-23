package com.tailapp.genre

/**
 * The genre currently believed to be playing.
 *
 * Produced by a generalised (not user-trained) classifier on the context tier,
 * every 1-3 seconds, and debounced by `EffectController` before it is allowed to
 * switch the active effect profile.
 *
 * @param label classifier label, e.g. `"Electronic---Trance"`.
 * @param confidence `0..1` softmax/sigmoid score for [label].
 * @param timestampNanos [System.nanoTime] of the window this describes.
 * @param alternatives runner-up labels with their scores, best first. Useful for
 *   the monitoring screen and for diagnosing profile flapping.
 */
data class GenreState(
    val label: String,
    val confidence: Float,
    val timestampNanos: Long,
    val alternatives: List<Pair<String, Float>> = emptyList()
) {
    companion object {
        /** Label used before the classifier has produced a prediction. */
        const val UNKNOWN_LABEL = "unknown"

        fun unknown(timestampNanos: Long = 0L): GenreState =
            GenreState(UNKNOWN_LABEL, 0f, timestampNanos)
    }
}
