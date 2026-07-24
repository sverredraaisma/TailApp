package com.tailapp.composer.effects

import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffects
import com.tailapp.composer.testContext
import com.tailapp.drop.SectionState
import com.tailapp.led.LedLayout
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loudness- and spectrum-driven effects, plus the section modulator.
 *
 * Layout is two rings of four: LEDs 0-3 at `y = 0`, 4-7 at `y = 1`, with `x`
 * running 0, 1/3, 2/3, 1 across each ring.
 */
class AudioEffectsTest {

    private val coords = LedLayout.coordsFor(listOf(4, 4))

    private fun render(effectId: String, params: Map<String, Float>, ctx: ReactiveContext): PixelBuffer {
        val buffer = PixelBuffer(coords.size)
        ReactiveEffects.create(effectId, params)!!.render(buffer, coords, ctx)
        return buffer
    }

    // --- spectrum bars ---

    private val spectrumParams = mapOf(
        "bars" to 2f, "barAxis" to 0f,
        "colorLow" to 0xFF0000.toFloat(), "colorHigh" to 0x0000FF.toFloat(),
        "gain" to 1f, "fadeRate" to 0f, "brightness" to 1f
    )

    @Test
    fun `spectrum bars map the low half of the spectrum to the first bar`() {
        // Bands [1,1,0,0] over two bars: bar 0 (the low half) is full, bar 1 empty.
        // Bars run around the ring, so x picks the bar and y is its height.
        val frame = render(
            "spectrum_bars",
            spectrumParams,
            testContext(bands = floatArrayOf(1f, 1f, 0f, 0f))
        )

        // x = 0 and 1/3 fall in bar 0: lit at both heights, in the low colour.
        assertEquals(0xFF0000, frame.packed(0))
        assertEquals(0xFF0000, frame.packed(4))
        // x = 2/3 and 1 fall in bar 1: only the y = 0 row satisfies `y <= 0`.
        assertEquals(0x0000FF, frame.packed(2))
        assertEquals(0, frame.packed(6))
    }

    @Test
    fun `spectrum bars colour by frequency across the bank`() {
        val frame = render(
            "spectrum_bars",
            spectrumParams,
            testContext(bands = floatArrayOf(1f, 1f, 1f, 1f))
        )

        assertEquals(0xFF0000, frame.packed(0))
        assertEquals(0x0000FF, frame.packed(2))
    }

    @Test
    fun `each bar falls at its own rate once the band goes quiet`() {
        val effect = ReactiveEffects.create("spectrum_bars", spectrumParams + ("fadeRate" to 1f))!!
        val buffer = PixelBuffer(coords.size)

        // Charge both bars, then run half a second of silence: level 1 - 1*0.5.
        effect.render(buffer, coords, testContext(bands = floatArrayOf(1f, 1f, 1f, 1f), dtSeconds = 0f))
        buffer.clear()
        effect.render(buffer, coords, testContext(bands = FloatArray(4), dtSeconds = 0.5f))

        // 0.5 still covers y = 0 but not y = 1.
        assertEquals(0xFF0000, buffer.packed(0))
        assertEquals(0, buffer.packed(4))
    }

    @Test
    fun `spectrum bars draw nothing with no spectrum at all`() {
        val frame = render("spectrum_bars", spectrumParams, testContext(bands = FloatArray(0)))

        // Every bar level is zero, so only the `y <= 0` row lights.
        assertEquals(0, frame.packed(4))
        assertEquals(0, frame.packed(6))
    }

    // --- VU meter ---

    private val meterParams = mapOf(
        "source" to 0f, "colorLow" to 0x102030.toFloat(), "colorHigh" to 0xFFFFFF.toFloat(),
        "axis" to 0f, "inverted" to 0f, "gain" to 1f,
        "peakDot" to 0f, "peakFall" to 1f, "dotWidth" to 0.04f, "brightness" to 1f
    )

    @Test
    fun `the VU meter fills up to the current level`() {
        val frame = render("vu_meter", meterParams, testContext(level = 0.5f, dtSeconds = 0f))

        // y = 0 is under the level and takes the low colour; y = 1 is above it.
        assertEquals(0x102030, frame.packed(0))
        assertEquals(0, frame.packed(4))
    }

    @Test
    fun `a full level reaches the tip in the high colour`() {
        val frame = render("vu_meter", meterParams, testContext(level = 1f, dtSeconds = 0f))

        assertEquals(0x102030, frame.packed(0))
        assertEquals(0xFFFFFF, frame.packed(4))
    }

    @Test
    fun `the meter can be driven by one band instead of the whole mix`() {
        val bassOnly = meterParams + ("source" to 1f)
        val frame = render("vu_meter", bassOnly, testContext(level = 0f, bass = 1f, dtSeconds = 0f))

        assertEquals(0xFFFFFF, frame.packed(4))
    }

    @Test
    fun `gain scales the fill`() {
        val frame = render(
            "vu_meter",
            meterParams + ("gain" to 4f),
            testContext(level = 0.25f, dtSeconds = 0f)
        )

        // 0.25 * 4 clamps to a full meter.
        assertEquals(0xFFFFFF, frame.packed(4))
    }

    @Test
    fun `the peak dot holds above the fill and falls at its own rate`() {
        val effect = ReactiveEffects.create(
            "vu_meter",
            meterParams + mapOf("peakDot" to 1f, "peakColor" to 0x00FF00.toFloat())
        )!!
        val buffer = PixelBuffer(coords.size)

        effect.render(buffer, coords, testContext(level = 1f, dtSeconds = 0f))
        buffer.clear()
        // Silence, but no time has passed, so the peak cannot have fallen.
        effect.render(buffer, coords, testContext(level = 0f, dtSeconds = 0f))

        assertEquals(0x00FF00, buffer.packed(4))
    }

    @Test
    fun `the peak falls away once time passes`() {
        val effect = ReactiveEffects.create(
            "vu_meter",
            meterParams + mapOf("peakDot" to 1f, "peakColor" to 0x00FF00.toFloat(), "peakFall" to 1f)
        )!!
        val buffer = PixelBuffer(coords.size)

        effect.render(buffer, coords, testContext(level = 1f, dtSeconds = 0f))
        buffer.clear()
        effect.render(buffer, coords, testContext(level = 0f, dtSeconds = 1f))

        // A full second at 1/s drops the peak to 0, so the tip is dark again.
        assertEquals(0, buffer.packed(4))
    }

    // --- bass pulse ---

    @Test
    fun `the bass pulse stays dark below its threshold`() {
        val frame = render(
            "bass_pulse",
            mapOf(
                "source" to 1f, "color" to 0xFFFFFF.toFloat(), "gain" to 1f,
                "threshold" to 0.5f, "fall" to 1f, "brightness" to 1f
            ),
            testContext(bass = 0.4f, dtSeconds = 0f)
        )

        assertEquals(0, frame.packed(0))
    }

    @Test
    fun `the bass pulse rescales above its threshold rather than merely gating`() {
        val frame = render(
            "bass_pulse",
            mapOf(
                "source" to 1f, "color" to 0xFFFFFF.toFloat(), "gain" to 1f,
                "threshold" to 0.5f, "fall" to 1f, "brightness" to 1f
            ),
            testContext(bass = 1f, dtSeconds = 0f)
        )

        // (1 - 0.5) / (1 - 0.5) = 1: full output at a full band.
        assertEquals(0xFFFFFF, frame.packed(0))
    }

    // --- section modulator ---

    private val sectionParams = mapOf(
        "buildupStartHz" to 2f, "buildupEndHz" to 12f,
        "strobeDuty" to 0.45f, "strobeFloor" to 0.12f,
        "breakdown" to 0.4f, "intro" to 0.6f
    )

    @Test
    fun `the section dimmer is transparent outside the sections it cares about`() {
        val frame = render("section_dimmer", sectionParams, testContext(section = SectionState.DROP))

        // White multiplies to a no-op.
        assertEquals(0xFFFFFF, frame.packed(0))
    }

    @Test
    fun `a breakdown dims and an intro dims less`() {
        val breakdown = render("section_dimmer", sectionParams, testContext(section = SectionState.BREAKDOWN))
        val intro = render("section_dimmer", sectionParams, testContext(section = SectionState.INTRO))
        val outro = render("section_dimmer", sectionParams, testContext(section = SectionState.OUTRO))

        assertEquals(0x666666, breakdown.packed(0)) // 0.40 * 255 = 102
        assertEquals(0x999999, intro.packed(0)) // 0.60 * 255 = 153
        assertEquals(0x515151, outro.packed(0)) // 0.40 * 0.8 * 255 = 81
    }

    @Test
    fun `a build-up strobes, and its rate ramps toward the drop`() {
        // At ramp 0 the rate is 2 Hz: a quarter second in is half a period, past
        // the duty, so it is in the dark half.
        val litEarly = render(
            "section_dimmer", sectionParams,
            testContext(section = SectionState.BUILDUP, sectionRamp = 0f, timeSeconds = 0f)
        )
        val darkEarly = render(
            "section_dimmer", sectionParams,
            testContext(section = SectionState.BUILDUP, sectionRamp = 0f, timeSeconds = 0.25f)
        )

        assertEquals(0xFFFFFF, litEarly.packed(0))
        assertEquals(0x1E1E1E, darkEarly.packed(0)) // 0.12 * 255 = 30

        // At ramp 1 the rate is 12 Hz, so that same instant lands elsewhere in
        // the cycle — the ramp is what makes a build-up accelerate.
        val atTop = render(
            "section_dimmer", sectionParams,
            testContext(section = SectionState.BUILDUP, sectionRamp = 1f, timeSeconds = 0.25f)
        )
        assertEquals(0xFFFFFF, atTop.packed(0))
    }

    @Test
    fun `the section dimmer never blacks the tail out completely`() {
        // A strobe that reached zero would read as the tail switching off.
        val frame = render(
            "section_dimmer", sectionParams,
            testContext(section = SectionState.BUILDUP, sectionRamp = 0f, timeSeconds = 0.25f)
        )

        assertTrue(frame.packed(0) > 0)
    }

    // --- volume dimmer ---

    @Test
    fun `the volume dimmer floors at its floor and reaches white at full level`() {
        val quiet = render(
            "volume_dimmer",
            mapOf("source" to 0f, "gain" to 1f, "floor" to 0.25f, "invert" to 0f),
            testContext(level = 0f)
        )
        val loud = render(
            "volume_dimmer",
            mapOf("source" to 0f, "gain" to 1f, "floor" to 0.25f, "invert" to 0f),
            testContext(level = 1f)
        )

        assertEquals(0x3F3F3F, quiet.packed(0)) // 0.25 * 255 = 63
        assertEquals(0xFFFFFF, loud.packed(0))
    }

    @Test
    fun `inverting the dimmer keeps the floor as a floor, not a ceiling`() {
        val loud = render(
            "volume_dimmer",
            mapOf("source" to 0f, "gain" to 1f, "floor" to 0.25f, "invert" to 1f),
            testContext(level = 1f)
        )
        val quiet = render(
            "volume_dimmer",
            mapOf("source" to 0f, "gain" to 1f, "floor" to 0.25f, "invert" to 1f),
            testContext(level = 0f)
        )

        assertEquals(0x3F3F3F, loud.packed(0))
        assertEquals(0xFFFFFF, quiet.packed(0))
    }
}
