package com.tailapp.testutil

import java.io.File
import java.util.Base64

/**
 * The BeatNet reference dump, as read by the tests that diff against it.
 *
 * `tools/dump_beat_reference.py` writes `testdata/beat_reference.json`: the exact
 * signal it fed through BeatNet's *own* feature extractor (madmom's
 * `LogarithmicFilteredSpectrogram`, unmodified), the 272-wide feature matrix that
 * produced, the activations the exported CRNN returned for it, and the resolved
 * geometry of madmom's filterbank.
 *
 * Arrays travel as base64 little-endian float32 rather than JSON numbers, so the
 * bytes the Kotlin side reads are bit-for-bit the ones numpy wrote — decimal text
 * would introduce a rounding step of its own, right where the point of the
 * exercise is to measure a difference.
 *
 * The file is committed, but regenerating it needs PyTorch and madmom's sources,
 * so [load] returns null rather than failing and the tests that need it skip with
 * a reason — the same contract `GenreReference` has in the genre tier.
 *
 * Parsed by hand, like the genre dump: `org.json` is stubbed to return zeros
 * under `unitTests.isReturnDefaultValues = true`, so a JSON library would be
 * untestable here.
 */
internal class BeatReference private constructor(private val json: String) {

    // --- front-end geometry BeatNet actually uses --------------------------------

    val sampleRate: Int get() = int("sampleRate")

    /** 1411 — 64 ms, and deliberately not a power of two. */
    val winLength: Int get() = int("winLength")

    val hopSize: Int get() = int("hopSize")
    val bandsPerOctave: Int get() = int("bandsPerOctave")
    val fMin: Float get() = number("fMin")
    val fMax: Float get() = number("fMax")
    val logMultiplier: Float get() = number("logMultiplier")
    val logAdd: Float get() = number("logAdd")

    /** `norm_filters=True`: every filter sums to 1 over its bins. */
    val normFilters: Boolean get() = boolean("normFilters")

    /** madmom frames are centred on `t * hop`; ours end at `frameSize + t * hop`. */
    val framesCentred: Boolean get() = boolean("framesCentred")

    /** How many frames back the positive difference is taken. */
    val diffFrames: Int get() = int("diffFrames")

    val numBins: Int get() = int("numBins")

    /** 136 — madmom drops log frequencies that would land on a bin it already has. */
    val numFilters: Int get() = int("numFilters")

    val hzPerBin: Float get() = number("hzPerBin")

    /** 272 = `numFilters` bands followed by their positive difference. */
    val featureDim: Int get() = int("featureDim")

    val frames: Int get() = int("frames")
    val signalSamples: Int get() = int("signalSamples")

    // --- the model ---------------------------------------------------------------

    val hiddenLayers: Int get() = int("hiddenLayers")
    val hiddenSize: Int get() = int("hiddenSize")

    /** Every `"name"` in `onnxInputs` then `onnxOutputs`, in graph order. */
    val onnxTensorNames: List<String>
        get() = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()

    // --- arrays ------------------------------------------------------------------

    /** The synthetic test signal, float32 at [sampleRate]. */
    val signal: FloatArray get() = floats("signal")

    /** Row-major `[frames * featureDim]` — the CRNN's input. */
    val features: FloatArray get() = floats("features")

    /** Row-major `[frames * 3]` softmax probabilities: beat, downbeat, non-beat. */
    val activations: FloatArray get() = floats("activations")

    val filterCenterHz: FloatArray get() = floats("filterCenterHz")
    val filterCornerLowHz: FloatArray get() = floats("filterCornerLowHz")
    val filterCornerHighHz: FloatArray get() = floats("filterCornerHighHz")
    val filterSums: FloatArray get() = floats("filterSums")
    val filterPeaks: FloatArray get() = floats("filterPeaks")

    /** The log-filtered spectrogram half of [features]: `[frames * numFilters]`. */
    fun bands(): FloatArray {
        val all = features
        val width = featureDim
        val bandWidth = numFilters
        val out = FloatArray(frames * bandWidth)
        for (t in 0 until frames) {
            System.arraycopy(all, t * width, out, t * bandWidth, bandWidth)
        }
        return out
    }

    // --- parsing -----------------------------------------------------------------

    private fun valueStart(key: String): Int {
        // Requires the colon: "frames" is also a dimension *name* inside the
        // onnxInputs shapes, and a bare indexOf would find whichever came first.
        val match = Regex("\"$key\"\\s*:").find(json)
        requireNotNull(match) { "no \"$key\" in the reference dump" }
        return match.range.last + 1
    }

    private fun scalar(key: String): String {
        val start = valueStart(key)
        var end = start
        while (end < json.length && json[end] != ',' && json[end] != '\n' && json[end] != '}') end++
        return json.substring(start, end).trim()
    }

    private fun int(key: String): Int = scalar(key).toInt()

    private fun number(key: String): Float = scalar(key).toFloat()

    private fun boolean(key: String): Boolean = scalar(key).toBooleanStrict()

    private fun floats(key: String): FloatArray {
        val open = json.indexOf('"', valueStart(key))
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
        const val FILE_NAME = "beat_reference.json"

        const val SKIP_REASON =
            "testdata/$FILE_NAME is missing — run tools/download_beatnet.py, tools/export_beatnet.py " +
                "then tools/dump_beat_reference.py (see tools/README.md); skipping the parity check"

        /** @return the dump, or null when it has not been generated. */
        fun load(): BeatReference? = file()?.let { BeatReference(it.readText()) }

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
