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
     */
    private fun formatNumber(value: Number): String {
        val d = value.toDouble()
        if (!d.isFinite()) return "0"
        if (d == d.toLong().toDouble()) return d.toLong().toString()
        return d.toString()
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                FORM_FEED -> sb.append("\\f")
                else ->
                    if (c < ' ') sb.append("\\u").append("%04x".format(c.code))
                    else sb.append(c)
            }
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

        fun readValue(): Any? {
            if (atEnd) throw JsonException("unexpected end of input")
            return when (val c = text[offset]) {
                '{' -> readObject()
                '[' -> readArray()
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
                            'u' -> {
                                if (offset + 4 > text.length) throw JsonException("truncated \\u escape")
                                sb.append(text.substring(offset, offset + 4).toInt(16).toChar())
                                offset += 4
                            }
                            else -> throw JsonException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = offset
            if (peek() == '-') offset++
            while (!atEnd && (text[offset].isDigit() || text[offset] in ".eE+-")) offset++
            val slice = text.substring(start, offset)
            return slice.toDoubleOrNull() ?: throw JsonException("bad number '$slice'")
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
