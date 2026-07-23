package com.tailapp.effects

import com.tailapp.genre.GenreState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenreDebouncerTest {

    private val config = EffectControllerConfig(
        genreWindowSeconds = 12f,
        minGenreAgreement = 3,
        genreMajorityFraction = 0.6f,
        minGenreConfidence = 0.35f
    )

    private var clock = 0L

    private fun predict(
        debouncer: GenreDebouncer,
        label: String,
        confidence: Float = 0.8f,
        afterSeconds: Float = 1f
    ): String? {
        clock += (afterSeconds * 1e9f).toLong()
        return debouncer.offer(GenreState(label, confidence, clock), clock)
    }

    @Test
    fun `a single prediction is not enough to switch`() {
        val debouncer = GenreDebouncer(config)

        assertNull(predict(debouncer, "trance"))
        assertNull(predict(debouncer, "trance"))
        assertNull(debouncer.current)
    }

    @Test
    fun `agreement over the window switches once`() {
        val debouncer = GenreDebouncer(config)

        predict(debouncer, "trance")
        predict(debouncer, "trance")
        assertEquals("trance", predict(debouncer, "trance"))
        assertEquals("trance", debouncer.current)

        // Further agreement is not a change and must not re-announce.
        assertNull(predict(debouncer, "trance"))
    }

    @Test
    fun `a minority of disagreeing predictions cannot flip the profile`() {
        val debouncer = GenreDebouncer(config)
        repeat(4) { predict(debouncer, "trance") }
        assertEquals("trance", debouncer.current)

        // Two stray hardstyle windows inside a run of trance: exactly the
        // ambiguity around a transition that must not flicker the lighting.
        assertNull(predict(debouncer, "hardstyle"))
        assertNull(predict(debouncer, "trance"))
        assertNull(predict(debouncer, "hardstyle"))
        assertEquals("trance", debouncer.current)
    }

    @Test
    fun `a sustained change does switch`() {
        val debouncer = GenreDebouncer(config)
        repeat(4) { predict(debouncer, "trance") }

        val switched = (1..8).firstNotNullOfOrNull { predict(debouncer, "hardstyle") }

        assertEquals("hardstyle", switched)
        assertEquals("hardstyle", debouncer.current)
    }

    @Test
    fun `low-confidence predictions are ignored entirely`() {
        val debouncer = GenreDebouncer(config)

        repeat(10) { predict(debouncer, "hardstyle", confidence = 0.2f) }

        assertNull(debouncer.current)
    }

    @Test
    fun `unknown labels are ignored`() {
        val debouncer = GenreDebouncer(config)

        repeat(10) { predict(debouncer, GenreState.UNKNOWN_LABEL, confidence = 0.9f) }

        assertNull(debouncer.current)
    }

    @Test
    fun `predictions older than the window stop counting`() {
        val debouncer = GenreDebouncer(config)
        repeat(4) { predict(debouncer, "trance") }
        assertEquals("trance", debouncer.current)

        // Long gap: everything above has aged out, so the next run of three
        // stands alone and wins on its own merits.
        val switched = (1..3).firstNotNullOfOrNull {
            predict(debouncer, "house", afterSeconds = if (it == 1) 30f else 1f)
        }

        assertEquals("house", switched)
    }

    @Test
    fun `reset forgets the accepted label and the window`() {
        val debouncer = GenreDebouncer(config)
        repeat(4) { predict(debouncer, "trance") }

        debouncer.reset()

        assertNull(debouncer.current)
        assertNull(predict(debouncer, "trance"))
    }
}
