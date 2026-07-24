package com.tailapp.led

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with the firmware's palette table.
 *
 * Every expectation here is derived from `PaletteTable::sample` by hand — the
 * stop tables, the integer interpolation, the clamped edges — rather than
 * recorded from this implementation. A test that asserted whatever this code
 * already does could not catch the two drifting apart, and a drifting palette
 * is a preview showing colours the tail will not.
 */
class PalettesTest {

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    @Test
    fun `a stop samples exactly its own colour`() {
        // Fire's stops: 0 -> 0x000000, 96 -> 0xA01400, 176 -> 0xFF7800, 255 -> 0xFFF0B4.
        assertEquals(0x000000, Palettes.sample(Palettes.FIRE, 0))
        assertEquals(0xA01400, Palettes.sample(Palettes.FIRE, 96))
        assertEquals(0xFF7800, Palettes.sample(Palettes.FIRE, 176))
        assertEquals(0xFFF0B4, Palettes.sample(Palettes.FIRE, 255))
    }

    @Test
    fun `interpolation uses the firmware's integer arithmetic`() {
        // Halfway between stop 0 (position 0) and stop 1 (position 96) is
        // position 48, so local = (48 - 0) * 255 / 96 = 127.
        // r = (0x00 * 128 + 0xA0 * 127) / 255 = 20320 / 255 = 79.
        val mid = Palettes.sample(Palettes.FIRE, 48)
        assertEquals(79, r(mid))
        assertEquals((0x00 * 128 + 0x14 * 127) / 255, g(mid))
        assertEquals(0, b(mid))
    }

    @Test
    fun `a ramp clamps at both ends rather than wrapping`() {
        // A ramp is not a cycle. Rainbow only looks cyclic because its first and
        // last stop are deliberately the same colour.
        assertEquals(Palettes.sample(Palettes.ICE, 0), Palettes.sample(Palettes.ICE, 0))
        assertEquals(0x000028, Palettes.sample(Palettes.ICE, 0))
        assertEquals(0xE6FFFF, Palettes.sample(Palettes.ICE, 255))

        assertEquals(
            "rainbow's ends match so it reads as a cycle",
            Palettes.sample(Palettes.RAINBOW, 0),
            Palettes.sample(Palettes.RAINBOW, 255)
        )
    }

    @Test
    fun `an unknown id samples black rather than another palette`() {
        // Silently substituting a different ramp would make a typo'd id look
        // like a design choice.
        assertEquals(0x000000, Palettes.sample(99, 128))
        assertEquals(0x000000, Palettes.sample(-1, 128))
        // A user slot the app has not been told about is equally unknown.
        assertEquals(0x000000, Palettes.sample(Palettes.BUILTIN_COUNT, 128))
    }

    @Test
    fun `mono is a straight black-to-white ramp`() {
        val half = Palettes.sample(Palettes.MONO, 128)
        assertEquals(r(half), g(half))
        assertEquals(g(half), b(half))
        assertTrue(r(half) in 120..136)
    }

    @Test
    fun `every builtin spans a real range`() {
        // A palette whose ends match would be a solid colour dressed as a ramp.
        for (id in 0 until Palettes.BUILTIN_COUNT) {
            if (id == Palettes.RAINBOW) continue // deliberately cyclic
            assertNotEquals(
                "palette $id has identical ends",
                Palettes.sample(id, 0),
                Palettes.sample(id, 255)
            )
        }
    }

    @Test
    fun `sampling is monotonic in position within a segment`() {
        // Fire's first segment goes black to dark red, so red must never dip.
        var previous = -1
        for (t in 0..96) {
            val red = r(Palettes.sample(Palettes.FIRE, t))
            assertTrue("red went backwards at $t", red >= previous)
            previous = red
        }
    }

    @Test
    fun `positions outside 0-255 are clamped, not wrapped`() {
        assertEquals(Palettes.sample(Palettes.FIRE, 0), Palettes.sample(Palettes.FIRE, -50))
        assertEquals(Palettes.sample(Palettes.FIRE, 255), Palettes.sample(Palettes.FIRE, 400))
    }
}
