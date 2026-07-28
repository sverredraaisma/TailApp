package com.tailapp.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-written reader's promise is narrow but absolute: **a malformed
 * document throws [JsonException]**, and [CompositionSerializer] turns that into
 * "fall back to a built-in" rather than a crash. Anything that escapes as a
 * different `Throwable` — a `StackOverflowError`, a `NumberFormatException` —
 * breaks that promise, and anything a malformed document is allowed to *become*
 * silently is worse still.
 */
class JsonTest {

    private fun failureOf(text: String): Throwable? =
        runCatching { Json.parse(text) }.exceptionOrNull()

    private fun assertRejected(text: String) {
        val failure = failureOf(text)
        assertTrue(
            "'$text' was accepted, or failed as ${failure?.let { it::class.java.simpleName }}",
            failure is JsonException
        )
    }

    // --- depth ---

    @Test
    fun `nesting a realistic composition deep is still fine`() {
        // Real trees are a handful of folders deep; the cap must be nowhere near.
        val text = "[".repeat(60) + "1" + "]".repeat(60)

        assertTrue(Json.parse(text) is List<*>)
    }

    @Test
    fun `pathological nesting fails as a parse error, not as a stack overflow`() {
        // 10k deep overflows the stack, and a StackOverflowError is an Error: it
        // escapes every `catch (e: JsonException)` the documentation promises is
        // enough. Only `runCatching`'s catch-Throwable made it survivable.
        assertRejected("[".repeat(10_000) + "1" + "]".repeat(10_000))
        assertRejected("{\"a\":".repeat(10_000) + "1" + "}".repeat(10_000))
    }

    // --- \u escapes ---

    @Test
    fun `a well-formed escape decodes`() {
        assertEquals("A", Json.parse("\"\\u0041\""))
    }

    @Test
    fun `a non-hex escape fails as a parse error`() {
        // `toInt(16)` threw NumberFormatException here, which is not a
        // JsonException and so is not what the caller is told to expect.
        assertRejected("\"\\uZZZZ\"")
        assertRejected("\"\\u00 1\"")
    }

    @Test
    fun `a signed escape is rejected rather than silently decoded`() {
        // `"-12F".toInt(16)` is -303, and (-303).toChar() is U+FED1: a malformed
        // document quietly becoming a perfectly valid, completely different
        // character.
        assertRejected("\"\\u-12F\"")
        assertRejected("\"\\u+041\"")
    }

    @Test
    fun `a surrogate pair decodes to the character it spells`() {
        val parsed = Json.parse("\"\\uD83D\\uDE00\"") as String

        assertEquals("\uD83D\uDE00", parsed)
        assertEquals(1, parsed.codePointCount(0, parsed.length))
    }

    @Test
    fun `an unpaired surrogate is rejected instead of round-tripping as broken UTF-16`() {
        assertRejected("\"\\uD800\"")
        assertRejected("\"\\uDC00\"")
        assertRejected("\"\\uD800\\u0041\"")
    }

    @Test
    fun `an astral character survives a write and read`() {
        val original = mapOf("name" to "tail \uD83D\uDE00 stack")

        assertEquals(original, Json.parse(Json.write(original)))
    }

    // --- numbers ---

    @Test
    fun `the numbers this writer emits all read back`() {
        for (value in listOf(0.0, -0.5, 1.0, 1e3, 1.5e-7, -12345.678, Double.MAX_VALUE)) {
            val text = Json.write(mapOf("v" to value))
            val parsed = (Json.parse(text) as Map<*, *>)["v"] as Number

            assertEquals(text, value, parsed.toDouble(), kotlin.math.abs(value) * 1e-12)
        }
    }

    @Test
    fun `numbers JSON does not have are rejected`() {
        // Accepting these is how a corrupt file reads as valid data.
        assertRejected("00123")
        assertRejected("{\"a\":01}")
        assertRejected("1.")
        assertRejected("1e")
        assertRejected("1e+")
        assertRejected("--1")
        assertRejected("-")
        assertRejected(".5")
    }

    @Test
    fun `a non-finite number is reported rather than written as zero`() {
        // A NaN brightness written as 0 saves as black and reloads as a stack the
        // user never built, with nothing anywhere saying so.
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val failure = runCatching { Json.write(mapOf("brightness" to value)) }.exceptionOrNull()
            assertTrue("$value was written silently", failure is JsonException)
        }
    }

    // --- the promise the serializer relies on ---

    @Test
    fun `every malformed document the serializer can be handed yields null`() {
        val documents = listOf(
            "[".repeat(10_000),
            "{\"layers\":[{\"type\":\"effect\",\"effect\":\"solid\",\"name\":\"\\uD800\"}]}",
            "{\"brightness\":00.5}",
            "{\"name\":\"\\uZZZZ\"}"
        )

        for (document in documents) {
            assertEquals(document, null, CompositionSerializer.fromJson(document))
        }
    }
}
