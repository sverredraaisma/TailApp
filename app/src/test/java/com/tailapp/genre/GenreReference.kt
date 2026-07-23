package com.tailapp.genre

import java.io.File
import java.util.Base64

/**
 * The Python-side reference dump, as read by the tests that diff against it.
 *
 * `tools/dump_reference.py` writes `testdata/genre_reference.json`: the exact
 * signal it fed through the pipeline, the mel spectrogram it computed, and the
 * embedding and class probabilities the two ONNX models produced from it.
 *
 * Arrays travel as base64 little-endian float32 rather than JSON numbers, so the
 * bytes the Kotlin side reads are bit-for-bit the ones numpy wrote. Decimal text
 * would introduce a rounding step of its own, right where the point of the
 * exercise is to measure a rounding difference.
 *
 * The file is absent unless someone has run the (multi-hundred-megabyte)
 * TensorFlow toolchain, so [load] returns null rather than failing, and the
 * tests that need it skip with a reason.
 */
internal class GenreReference private constructor(private val json: String) {

    val sampleRate: Int get() = int("sampleRate")
    val numBands: Int get() = int("numBands")
    val melFrames: Int get() = int("melFrames")
    val patchCount: Int get() = int("patchCount")

    /** The synthetic test signal, float32 at [sampleRate]. */
    val signal: FloatArray get() = floats("signal")

    /** Row-major `[melFrames * numBands]` log-mel bands. */
    val mel: FloatArray get() = floats("mel")

    /** The 1280-d embedding of the first patch. */
    val embedding: FloatArray get() = floats("embedding")

    /** The 400 sigmoid scores, averaged over patches. */
    val probabilities: FloatArray get() = floats("probabilities")

    private fun int(key: String): Int {
        val at = json.indexOf("\"$key\"")
        require(at >= 0) { "no \"$key\" in the reference dump" }
        val colon = json.indexOf(':', at)
        var end = colon + 1
        while (end < json.length && json[end] != ',' && json[end] != '\n' && json[end] != '}') end++
        return json.substring(colon + 1, end).trim().toInt()
    }

    private fun floats(key: String): FloatArray {
        val at = json.indexOf("\"$key\"")
        require(at >= 0) { "no \"$key\" in the reference dump" }
        val open = json.indexOf('"', json.indexOf(':', at))
        val close = json.indexOf('"', open + 1)
        val bytes = Base64.getDecoder().decode(json.substring(open + 1, close))
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) {
            // Little-endian, matching numpy's tobytes() on x86/ARM.
            val bits = (bytes[i * 4].toInt() and 0xFF) or
                ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((bytes[i * 4 + 3].toInt() and 0xFF) shl 24)
            out[i] = Float.fromBits(bits)
        }
        return out
    }

    companion object {
        const val FILE_NAME = "genre_reference.json"

        const val SKIP_REASON =
            "testdata/$FILE_NAME is missing — run tools/dump_reference.py (see tools/README.md) " +
                "to regenerate it; skipping the numeric parity check"

        /** @return the dump, or null when it has not been generated. */
        fun load(): GenreReference? = file()?.let { GenreReference(it.readText()) }

        fun file(): File? {
            // Gradle runs unit tests with the module directory as the working
            // directory, but IDE runners vary, so walk up looking for the repo's
            // testdata/ rather than hard-coding "../".
            var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            for (level in 0 until 5) {
                val here = directory ?: break
                val candidate = File(File(here, "testdata"), FILE_NAME)
                if (candidate.isFile) return candidate
                directory = here.parentFile
            }
            return null
        }
    }
}
