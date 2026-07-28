package com.tailapp.genre

/**
 * Turns the head's 400 raw scores into a [GenreState].
 *
 * Split out of [OnnxGenreClassifier] so it can be tested without ONNX Runtime,
 * which needs a device.
 */
internal object GenrePrediction {

    /**
     * Picks the winner and its runners-up.
     *
     * `genre_discogs400` ends in a **sigmoid**, not a softmax: it is a
     * multi-label head, the 400 scores do not sum to 1, and several sibling
     * styles ("Techno", "Minimal Techno", "Deep Techno") legitimately fire
     * together. [GenreState.confidence] is therefore the winner's raw sigmoid
     * activation — the calibrated "how sure is it", which is the number any
     * downstream threshold should compare against. (It named
     * `EffectControllerConfig.minGenreConfidence` here until that type was
     * removed; see [OnnxGenreClassifier]'s `minConfidence`, which defaults to 0.)
     * Renormalising to sum 1 would divide by a number that grows with how many
     * styles fired, i.e. push confidence *down* exactly when the model is most
     * certain.
     *
     * @param scores one score per class, parallel to [labels].
     * @param minConfidence below this the window is reported as "nothing useful
     *   to say" (null) rather than as a low-confidence guess.
     * @param alternativeCount runners-up to attach, best first, excluding the winner.
     * @return the prediction, or null when [scores] is unusable or too weak.
     */
    fun toState(
        scores: FloatArray,
        labels: List<String>,
        timestampNanos: Long,
        minConfidence: Float = 0f,
        alternativeCount: Int = 4
    ): GenreState? {
        if (scores.isEmpty() || labels.isEmpty()) return null
        // A label list that does not match the head is a mis-installed model, and
        // guessing at the overlap would mislabel silently.
        if (scores.size != labels.size) return null

        var bestIndex = 0
        for (i in scores.indices) {
            if (scores[i] > scores[bestIndex]) bestIndex = i
        }
        val best = scores[bestIndex]
        if (!best.isFinite() || best < minConfidence) return null

        val alternatives = if (alternativeCount <= 0) {
            emptyList()
        } else {
            scores.indices
                .asSequence()
                .filter { it != bestIndex }
                .sortedByDescending { scores[it] }
                .take(alternativeCount)
                .map { labels[it] to scores[it] }
                .toList()
        }

        return GenreState(
            label = labels[bestIndex],
            confidence = best,
            timestampNanos = timestampNanos,
            alternatives = alternatives
        )
    }

    /**
     * Averages per-patch scores into one score vector.
     *
     * Averaging the *probabilities* rather than the embeddings is what Essentia
     * does, and it is the right call for a sigmoid head: a style that fires on
     * only half the patches should end up at half confidence, not be averaged
     * into some point in embedding space that the head never saw.
     *
     * @param scores row-major `[patches * classCount]`.
     */
    fun poolPatches(scores: FloatArray, patches: Int, classCount: Int): FloatArray {
        require(patches > 0 && classCount > 0) { "need at least one patch and one class" }
        require(scores.size >= patches * classCount) { "score buffer is too small" }
        if (patches == 1) return scores.copyOf(classCount)

        val pooled = FloatArray(classCount)
        for (patch in 0 until patches) {
            val offset = patch * classCount
            for (i in 0 until classCount) pooled[i] += scores[offset + i]
        }
        val scale = 1f / patches
        for (i in 0 until classCount) pooled[i] = pooled[i] * scale
        return pooled
    }
}
