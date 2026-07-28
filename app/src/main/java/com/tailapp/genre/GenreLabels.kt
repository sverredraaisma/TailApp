package com.tailapp.genre

/**
 * The 400 class names the genre head predicts, read from the model's own
 * metadata file.
 *
 * The labels ship *with* the weights rather than being hard-coded here for two
 * reasons: a label list that can drift out of order relative to the weights is a
 * silent mislabelling bug, and copying 400 strings out of a CC BY-NC-ND
 * distribution into the source tree is exactly the kind of thing the licence is
 * about. `genre_discogs400-discogs-effnet-1.json` is installed alongside the two
 * `.onnx` files; see `GenreModelStore`.
 *
 * Parsed by hand rather than with `org.json`: this runs inside unit tests, where
 * `unitTests.isReturnDefaultValues = true` turns every `org.json` call into a
 * silent zero. A 40-line scanner for one known-shaped array is cheaper than a
 * JSON dependency and cannot be defeated by the test stubs.
 */
object GenreLabels {

    /**
     * Number of classes `genre_discogs400` predicts.
     *
     * Not merely a capacity hint for the parser: [OnnxGenreClassifier.create]
     * rejects a metadata file that does not hold exactly this many labels,
     * because a short list does not fail at inference — the head still returns
     * its own 400 scores and the argmax is silently taken over a prefix of them.
     * The parser itself stays lenient, so it can be tested on small fixtures.
     */
    const val EXPECTED_COUNT = 400

    /**
     * Extracts the `"classes"` array from the model metadata.
     *
     * @throws IllegalArgumentException when the key is absent or malformed —
     *   a truncated download should fail loudly here, not produce 12 labels.
     */
    fun parse(json: String): List<String> {
        val key = "\"classes\""
        val keyAt = json.indexOf(key)
        require(keyAt >= 0) { "no \"classes\" key in the model metadata" }

        var i = keyAt + key.length
        while (i < json.length && json[i].isWhitespace()) i++
        require(i < json.length && json[i] == ':') { "\"classes\" is not followed by a value" }
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        require(i < json.length && json[i] == '[') { "\"classes\" is not an array" }
        i++

        val labels = ArrayList<String>(EXPECTED_COUNT)
        val builder = StringBuilder()
        while (true) {
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            require(i < json.length) { "unterminated \"classes\" array" }
            if (json[i] == ']') break
            require(json[i] == '"') { "\"classes\" holds a non-string element at offset $i" }

            i++
            builder.setLength(0)
            while (true) {
                require(i < json.length) { "unterminated string in \"classes\"" }
                when (val c = json[i]) {
                    '"' -> { i++; break }
                    '\\' -> {
                        i++
                        require(i < json.length) { "dangling escape in \"classes\"" }
                        when (val escape = json[i]) {
                            '"', '\\', '/' -> builder.append(escape)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                require(i + 4 < json.length) { "truncated \\u escape in \"classes\"" }
                                builder.append(json.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$escape in \"classes\"")
                        }
                        i++
                    }
                    else -> { builder.append(c); i++ }
                }
            }
            labels.add(builder.toString())
        }

        require(labels.isNotEmpty()) { "\"classes\" is empty" }
        return labels
    }
}
