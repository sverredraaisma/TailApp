package com.tailapp.genre

/**
 * Recognises the genre of a window of audio.
 *
 * The context tier runs far slower than the others — one window every one to
 * three seconds — because that is the timescale a genre is even meaningful over,
 * and because it is the tier that will run a neural model.
 *
 * The intended implementation wraps a frozen Discogs-EffNet embedding model plus
 * its published 400-class genre head, both via ONNX Runtime, entirely on-device.
 * That is a generalised classifier, not one trained on the owner's library —
 * personally-trained models are deliberately out of scope.
 */
interface GenreClassifier {
    /** Audio window the classifier wants, in seconds. */
    val windowSeconds: Float

    /** Sample rate the window must be supplied at. */
    val sampleRate: Int

    /**
     * Classifies one window.
     *
     * @param samples mono audio at [sampleRate], `windowSeconds` long.
     * @param timestampNanos wall-clock time of the end of the window.
     * @return the prediction, or null when the classifier has nothing useful to
     *   say about this window (too quiet, not music, model not ready).
     */
    fun classify(samples: FloatArray, timestampNanos: Long): GenreState?

    /** Releases any native resources. */
    fun close() {}
}

/**
 * Stand-in used until the ONNX classifier lands.
 *
 * Deliberately inert rather than guessing: a wrong genre picks a wrong lighting
 * profile, and the effect controller's debounce is designed to resist exactly
 * that. With this in place the pipeline runs end to end on the default profile,
 * and a manual override remains available.
 */
object NoGenreClassifier : GenreClassifier {
    override val windowSeconds: Float = 3f
    override val sampleRate: Int = 16000
    override fun classify(samples: FloatArray, timestampNanos: Long): GenreState? = null
}
