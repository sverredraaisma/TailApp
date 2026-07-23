package com.tailapp.beat

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Where the BeatNet CRNN lives on the device, and whether it is there yet.
 *
 * Deliberately the same shape as [com.tailapp.genre.GenreModelStore] — same
 * `filesDir` subdirectory pattern, same `.part`-then-rename install, same
 * "absent is the normal case" contract — but a separate class rather than a
 * shared base. The two stores agree on about fifteen lines of file copying and
 * disagree on everything that matters: the genre tier needs three artifacts
 * (two graphs and the label list) and is only usable when all three are present,
 * this one needs a single `.onnx` and nothing else. A common superclass would
 * have to be parameterised on exactly the part that differs, and `beat`
 * depending on `genre` (or a third package existing to hold forty lines) is a
 * worse trade than the duplication.
 *
 * **Why not `assets/`.** BeatNet's weights are CC BY 4.0, so unlike the genre
 * models there is no licence reason they could not ship in the APK. The reason
 * they do not is different and simpler: the `.onnx` is a *build output*. It does
 * not exist upstream — `tools/export_beatnet.py` rewraps BeatNet's CRNN so the
 * LSTM state crosses the graph boundary — so it would have to be committed to be
 * packaged, and a rebuildable 1.6 MB binary in a source tree earns nothing.
 * Installing it is therefore an explicit act: `adb push` during development (see
 * `tools/README.md`), or [install] from a document picker or a host you control.
 *
 * @param directory where the model lives; created lazily by [install].
 */
class BeatModelStore(val directory: File) {

    /** The streaming CRNN: `(features, h0, c0) -> (probs, hn, cn)`. */
    val crnn: File get() = File(directory, CRNN_MODEL)

    /** True when the model is present and non-empty. */
    val isInstalled: Boolean get() = crnn.isFile && crnn.length() > 0

    /** Names of the artifacts still missing — what a "model not installed" UI shows. */
    val missing: List<String> get() = if (isInstalled) emptyList() else listOf(CRNN_MODEL)

    /**
     * Installs the model, atomically.
     *
     * Writes to a sibling `.part` and renames, so a stream that dies halfway
     * cannot leave a truncated `.onnx` that [isInstalled] would then accept.
     *
     * @param name must be [CRNN_MODEL].
     * @throws IllegalArgumentException for any other name.
     */
    @Throws(IOException::class)
    fun install(name: String, source: InputStream) {
        require(name in ALL) { "unknown beat model artifact: $name" }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("could not create $directory")
        }

        val target = File(directory, name)
        val partial = File(directory, "$name.part")
        try {
            partial.outputStream().use { source.copyTo(it) }
            if (partial.length() == 0L) throw IOException("$name arrived empty")
            if (target.exists() && !target.delete()) throw IOException("could not replace $target")
            if (!partial.renameTo(target)) throw IOException("could not move $partial into place")
        } finally {
            partial.delete()
        }
    }

    /** Removes everything this store owns. Used when a model turns out to be corrupt. */
    fun clear() {
        crnn.delete()
    }

    companion object {
        /**
         * Produced by `tools/export_beatnet.py --model 1` — BeatNet's
         * GTZAN-trained checkpoint, the most general of the three.
         */
        const val CRNN_MODEL = "beatnet-crnn-model1.onnx"

        val ALL = listOf(CRNN_MODEL)

        /** Subdirectory of `filesDir` the model is installed into. */
        const val DIRECTORY_NAME = "beat-models"
    }
}
