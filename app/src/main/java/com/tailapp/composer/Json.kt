package com.tailapp.composer

/**
 * A minimal JSON reader/writer for saved compositions.
 *
 * Hand-written for the same reason `GenreLabels` and the test-side
 * `BeatReference` are: `unitTests.isReturnDefaultValues = true` turns every
 * `org.json` call into a stub that returns zeros, so a composition round-trip
 * could not be tested through the platform library at all. No third-party JSON
 * dependency exists in this project either, and adding one to persist a handful
 * of numbers would be the larger cost.
 *
 * Values map to plain Kotlin types: object → `Map<String, Any?>`, array →
 * `List<Any?>`, string → `String`, number → `Double`, plus `Boolean` and `null`.
 * Deliberately strict — a malformed document throws [JsonException] rather than
 * guessing, and [CompositionSerializer] turns that into "fall back to a built-in
 * composition" rather than a crash.
 */
internal class JsonException(message: String) : Exception(message)

internal object Json {

    /**
     * Form feed (`0x0C`), JSON's `\f`. Built from its code point rather than
     * written as a character literal so this file stays pure ASCII — a literal
     * control character in source is invisible in a diff and trivially lost by a
     * tool that reformats it, which would silently change what the parser
     * accepts.
     */
    private val FORM_FEED = 12.toChar()

    /** `U+FFFD`, substituted for text that is already not valid UTF-16. */
    private val REPLACEMENT = 0xFFFD.toChar()

    /**
     * How deeply [parse] will nest before giving up.
     *
     * Both readers recurse, so nesting depth is stack depth: a document of ten
     * thousand `[`s overflows the stack. A `StackOverflowError` is an `Error`,
     * not an `Exception` — it escapes every `catch (e: JsonException)` this
     * file's documentation promises is enough, and today it is survivable only
     * because [CompositionSerializer] happens to use `runCatching`, which catches
     * `Throwable`. A depth no real composition approaches (the editor's own trees
     * are a handful of folders deep) turns that into the ordinary parse failure
     * every other malformed document produces.
     */
    private const val MAX_DEPTH = 64

    private val HIGH_SURROGATES = 0xD800..0xDBFF
    private val LOW_SURROGATES = 0xDC00..0xDFFF

    // --- writing ---

    fun write(value: Any?): String = StringBuilder().also { writeInto(it, value) }.toString()

    private fun writeInto(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(formatNumber(value))
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeInto(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeInto(sb, v)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    /**
     * Numbers are written without an exponent and without a trailing `.0` for
     * whole values, so a saved file stays readable and diffable by hand.
     *
     * `NaN` and the infinities have no JSON spelling at all. Writing them as `0`
     * is the worst available option: a `NaN` brightness would save as *black* and
     * reload as a stack the user never built, with nothing anywhere saying so.
     * They are a bug at the source, so they are reported as one.
     */
    private fun formatNumber(value: Number): String {
        val d = value.toDouble()
        if (!d.isFinite()) throw JsonException("cannot write a non-finite number ($d)")
        if (d == d.toLong().toDouble()) return d.toLong().toString()
        return d.toString()
    }

    /**
     * Surrogates are written as the pairs they are, never singly: a lone
     * surrogate is not valid UTF-16 and emitting one produces a document that no
     * reader — including this one — can take back. An unpaired half is replaced
     * with `U+FFFD`, which is what every other encoder in the platform does with
     * text that is already broken.
     */
    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        var i = 0
        while (i < value.length) {
            when (val c = value[i]) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                FORM_FEED -> sb.append("\\f")
                else -> when {
                    c.isHighSurrogate() -> {
                        val low = value.getOrNull(i + 1)
                        if (low != null && low.isLowSurrogate()) {
                            sb.append(c).append(low)
                            i++
                        } else {
                            sb.append(REPLACEMENT)
                        }
                    }
                    c.isLowSurrogate() -> sb.append(REPLACEMENT)
                    c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
                    else -> sb.append(c)
                }
            }
            i++
        }
        sb.append('"')
    }

    // --- reading ---

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd) throw JsonException("trailing content at offset ${reader.offset}")
        return value
    }

    // --- typed accessors, all null-tolerant ---

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    fun arr(value: Any?): List<Any?>? = value as? List<Any?>

    fun str(value: Any?): String? = value as? String

    fun float(value: Any?, fallback: Float): Float = (value as? Number)?.toFloat() ?: fallback

    fun int(value: Any?, fallback: Int): Int = (value as? Number)?.toInt() ?: fallback

    fun bool(value: Any?, fallback: Boolean): Boolean = (value as? Boolean) ?: fallback

    private class Reader(private val text: String) {
        var offset = 0
            private set

        val atEnd: Boolean get() = offset >= text.length

        fun skipWhitespace() {
            while (offset < text.length && text[offset].isWhitespace()) offset++
        }

        /** Nesting currently open; see [MAX_DEPTH]. */
        private var depth = 0

        private inline fun <T> nested(body: () -> T): T {
            if (++depth > MAX_DEPTH) {
                throw JsonException("nested deeper than $MAX_DEPTH at offset $offset")
            }
            try {
                return body()
            } finally {
                depth--
            }
        }

        fun readValue(): Any? {
            if (atEnd) throw JsonException("unexpected end of input")
            return when (val c = text[offset]) {
                '{' -> nested { readObject() }
                '[' -> nested { readArray() }
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else ->
                    if (c == '-' || c.isDigit()) readNumber()
                    else throw JsonException("unexpected '$c' at offset $offset")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                offset++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> offset++
                    '}' -> {
                        offset++
                        return map
                    }
                    else -> throw JsonException("expected ',' or '}' at offset $offset")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                offset++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> offset++
                    ']' -> {
                        offset++
                        return list
                    }
                    else -> throw JsonException("expected ',' or ']' at offset $offset")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd) throw JsonException("unterminated string")
                when (val c = text[offset++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd) throw JsonException("dangling escape")
                        when (val e = text[offset++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append(FORM_FEED)
                            'u' -> readUnicodeEscape(sb)
                            else -> throw JsonException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        /**
         * Reads the four hex digits after a `\u`, and the second half of a
         * surrogate pair when the first half calls for one.
         *
         * `substring(...).toInt(16)` was neither: it throws
         * `NumberFormatException` (not a [JsonException]) on `\uZZZZ`, and it
         * *accepts a sign*, so `\u-12F` silently decoded to `U+FED1` — a
         * malformed document quietly becoming a different character. And a lone
         * `\uD800` produced an unpaired surrogate that the writer then re-emitted
         * raw, so a round trip turned a bad document into invalid UTF-16.
         */
        private fun readUnicodeEscape(sb: StringBuilder) {
            val code = readHex4()
            when {
                code in HIGH_SURROGATES -> {
                    if (!text.startsWith("\\u", offset)) {
                        throw JsonException("unpaired high surrogate at offset $offset")
                    }
                    offset += 2
                    val low = readHex4()
                    if (low !in LOW_SURROGATES) {
                        throw JsonException("high surrogate not followed by a low one at offset $offset")
                    }
                    sb.append(code.toChar()).append(low.toChar())
                }

                code in LOW_SURROGATES ->
                    throw JsonException("unpaired low surrogate at offset $offset")

                else -> sb.append(code.toChar())
            }
        }

        private fun readHex4(): Int {
            if (offset + 4 > text.length) throw JsonException("truncated \\u escape")
            var value = 0
            for (i in 0 until 4) {
                val c = text[offset + i]
                val digit = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> throw JsonException("bad \\u escape at offset $offset")
                }
                value = value * 16 + digit
            }
            offset += 4
            return value
        }

        /**
         * JSON's own number grammar: an optional `-`, then either `0` or a
         * non-zero digit run, then an optional fraction with at least one digit,
         * then an optional exponent with at least one digit.
         *
         * Scanning "digits and anything in `.eE+-`" and handing the slice to
         * `toDoubleOrNull` accepted things JSON does not — `00123`, `1.`, `1e`,
         * `--1` — which is how a corrupt file reads as valid data. Nothing this
         * writer emits is affected: whole values go out as a `Long` and the rest
         * through `Double.toString`, neither of which can produce a leading zero
         * or a bare trailing point.
         */
        private fun readNumber(): Double {
            val start = offset
            if (!atEnd && text[offset] == '-') offset++

            when {
                atEnd -> throw JsonException("unexpected end of input in a number")
                text[offset] == '0' -> offset++
                text[offset] in '1'..'9' -> skipDigits()
                else -> throw JsonException("bad number at offset $offset")
            }

            if (!atEnd && text[offset] == '.') {
                offset++
                requireDigit()
                skipDigits()
            }

            if (!atEnd && (text[offset] == 'e' || text[offset] == 'E')) {
                offset++
                if (!atEnd && (text[offset] == '+' || text[offset] == '-')) offset++
                requireDigit()
                skipDigits()
            }

            val slice = text.substring(start, offset)
            return slice.toDoubleOrNull() ?: throw JsonException("bad number '$slice'")
        }

        private fun skipDigits() {
            while (!atEnd && text[offset] in '0'..'9') offset++
        }

        private fun requireDigit() {
            if (atEnd || text[offset] !in '0'..'9') {
                throw JsonException("expected a digit at offset $offset")
            }
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, offset)) {
                throw JsonException("expected '$literal' at offset $offset")
            }
            offset += literal.length
            return value
        }

        private fun peek(): Char =
            if (atEnd) throw JsonException("unexpected end of input") else text[offset]

        private fun expect(c: Char) {
            if (peek() != c) throw JsonException("expected '$c' at offset $offset")
            offset++
        }
    }
}
