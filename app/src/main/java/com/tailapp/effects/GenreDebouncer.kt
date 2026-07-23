package com.tailapp.effects

import com.tailapp.genre.GenreState

/**
 * Decides when a stream of genre predictions has actually changed its mind.
 *
 * The classifier runs every 1-3 seconds and is genuinely uncertain across track
 * transitions, intros and breakdowns, where consecutive windows can disagree
 * wildly. Switching the lighting profile on every prediction would make the tail
 * flicker between looks in exactly the moments the music is most interesting, so
 * a change has to win a rolling majority over [EffectControllerConfig.genreWindowSeconds]
 * before it is allowed through.
 *
 * @param config supplies the window length, the agreement floor and the
 *   confidence gate.
 */
class GenreDebouncer(private val config: EffectControllerConfig) {

    private val timestamps = ArrayDeque<Long>()
    private val labels = ArrayDeque<String>()

    private var accepted: String? = null

    /** The label currently in force, or null before anything has been accepted. */
    val current: String? get() = accepted

    /**
     * Offers one prediction.
     *
     * @return the new label when this prediction tips the majority, otherwise
     *   null — including when it merely reinforces the current one.
     */
    fun offer(state: GenreState, nowNanos: Long = state.timestampNanos): String? {
        // A low-confidence prediction is not evidence for anything; letting it
        // into the window would let a genuinely ambiguous stretch outvote the
        // confident predictions on either side of it.
        if (state.confidence < config.minGenreConfidence) return null
        if (state.label == GenreState.UNKNOWN_LABEL) return null

        timestamps.addLast(nowNanos)
        labels.addLast(state.label)
        prune(nowNanos)

        val counts = labels.groupingBy { it }.eachCount()
        val (winner, votes) = counts.maxByOrNull { it.value } ?: return null

        if (winner == accepted) return null
        if (votes < config.minGenreAgreement) return null
        if (votes.toFloat() / labels.size < config.genreMajorityFraction) return null

        accepted = winner
        return winner
    }

    fun reset() {
        timestamps.clear()
        labels.clear()
        accepted = null
    }

    private fun prune(nowNanos: Long) {
        val cutoff = nowNanos - (config.genreWindowSeconds * NANOS_PER_SECOND).toLong()
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeFirst()
            labels.removeFirst()
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
