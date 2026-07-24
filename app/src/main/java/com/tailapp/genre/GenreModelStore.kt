package com.tailapp.genre

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Where the genre models live on the device, and whether they are there yet.
 *
 * **Why they are not in `assets/`.** The weights are Essentia's, published under
 * CC BY-NC-ND 4.0. Downloading and running the original, unmodified artifacts is
 * fine; baking them into every APK we build is redistribution, and the ND clause
 * makes the derived ONNX conversion an even worse thing to ship. So the app
 * carries the *code* and the models arrive separately, into
 * `filesDir/genre-models/`, exactly like a downloadable asset pack.
 *
 * **How they get there.** There is no upstream URL to fetch from: the two `.onnx`
 * files do not exist upstream in the form this app needs — `tools/convert_to_onnx.py`
 * produces them locally, and rewrites the embedding model's baked-in batch of 64
 * to a dynamic one. So installation is an explicit act: `adb push` during
 * development (see docs/genre-model.md), or [install] from any stream — a
 * document picker, or a download from a host you control. Nothing is fetched
 * automatically and no audio ever leaves the device.
 *
 * Until all three files are present [isInstalled] is false, `AppContainer` keeps
 * [NoGenreClassifier], and the pipeline runs on the default lighting profile.
 *
 * @param directory where the three artifacts live; created lazily by [install].
 */
class GenreModelStore(val directory: File) {

    /** Mel patches -> 1280-d embedding. Converted from `discogs-effnet-bs64-1.pb`. */
    val embeddingModel: File get() = File(directory, EMBEDDING_MODEL)

    /** 1280-d embedding -> 400 sigmoid scores. */
    val genreHead: File get() = File(directory, GENRE_HEAD)

    /** Essentia's model metadata; the class labels are read out of it. */
    val labels: File get() = File(directory, LABELS)

    private val required: List<File> get() = listOf(embeddingModel, genreHead, labels)

    /** True when all three artifacts are present and non-empty. */
    val isInstalled: Boolean get() = required.all { it.isFile && it.length() > 0 }

    /** Names of the artifacts still missing — what a "models not installed" UI shows. */
    val missing: List<String> get() = required.filter { !it.isFile || it.length() == 0L }.map { it.name }

    /**
     * Installs one artifact, atomically.
     *
     * Writes to a sibling `.part` and renames, so a stream that dies halfway
     * cannot leave a truncated `.onnx` that [isInstalled] would then accept.
     *
     * @param name one of [EMBEDDING_MODEL], [GENRE_HEAD], [LABELS].
     * @throws IllegalArgumentException for any other name.
     */
    @Throws(IOException::class)
    fun install(name: String, source: InputStream) {
        require(name in ALL) { "unknown genre model artifact: $name" }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("could not create $directory")
        }

        val target = File(directory, name)
        val partial = File(directory, "$name.part")
        val backup = File(directory, "$name.bak")
        try {
            partial.outputStream().use { source.copyTo(it) }
            if (partial.length() == 0L) throw IOException("$name arrived empty")

            backup.delete() // clear any leftover from a prior aborted install
            // Move the current artifact aside rather than deleting it up front, so
            // a rename that then fails cannot leave the store with neither the old
            // file nor the new one — a failed *replace* must keep what worked.
            val hadTarget = target.exists()
            if (hadTarget && !target.renameTo(backup)) {
                throw IOException("could not set aside the existing $target")
            }
            if (!partial.renameTo(target)) {
                if (hadTarget) backup.renameTo(target) // put the working file back
                throw IOException("could not move $partial into place")
            }
        } finally {
            partial.delete()
            backup.delete()
        }
    }

    /** Removes everything this store owns. Used when a model turns out to be corrupt. */
    fun clear() {
        required.forEach { it.delete() }
    }

    companion object {
        const val EMBEDDING_MODEL = "discogs-effnet-bs64-1.onnx"
        const val GENRE_HEAD = "genre_discogs400-discogs-effnet-1.onnx"
        const val LABELS = "genre_discogs400-discogs-effnet-1.json"

        val ALL = listOf(EMBEDDING_MODEL, GENRE_HEAD, LABELS)

        /** Subdirectory of `filesDir` the artifacts are installed into. */
        const val DIRECTORY_NAME = "genre-models"
    }
}
