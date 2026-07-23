package com.tailapp.effects

import com.tailapp.beat.BeatEvent
import com.tailapp.beat.BeatType
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import com.tailapp.drop.SectionStateUpdate
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renderer, driven with an explicit clock.
 *
 * Because a frame is rebuilt from event timestamps rather than accumulated, every
 * one of these assertions is exact — there is no "let it settle for a few frames"
 * anywhere.
 */
class ReactiveRendererTest {

    private val layout = listOf(8, 10, 12, 10, 8)
    private val second = 1_000_000_000L

    private fun renderer(profile: EffectProfile = EffectProfiles.DEFAULT): ReactiveRenderer =
        ReactiveRenderer().apply {
            setLayout(layout)
            this.profile = profile
        }

    private fun PixelBuffer.totalBrightness(): Long {
        var sum = 0L
        for (i in 0 until ledCount) sum += red(i) + green(i) + blue(i)
        return sum
    }

    private fun beat(atNanos: Long, type: BeatType = BeatType.BEAT, beatInBar: Int = 1) =
        BeatEvent(type, atNanos, 128f, beatInBar, 0.9f)

    @Test
    fun `an unconfigured layout renders nothing and does not crash`() {
        val renderer = ReactiveRenderer()

        val frame = renderer.render(second)

        assertEquals(0, frame.ledCount)
    }

    @Test
    fun `layout matches the ring configuration`() {
        assertEquals(layout.sum(), renderer().ledCount)
    }

    @Test
    fun `a beat lights the strip and decays`() {
        val renderer = renderer(EffectProfiles.HARDSTYLE)
        renderer.onBeat(beat(second))

        val atBeat = renderer.render(second).totalBrightness()
        val shortlyAfter = renderer.render(second + 60_000_000L).totalBrightness()
        val wellAfter = renderer.render(second + 800_000_000L).totalBrightness()

        assertTrue("beat should light the strip", atBeat > 0)
        assertTrue("brightness should decay: $atBeat -> $shortlyAfter", shortlyAfter < atBeat)
        assertTrue("should be dark again: $wellAfter", wellAfter < atBeat / 10)
    }

    @Test
    fun `a beat still in the future contributes nothing yet`() {
        // The tracker predicts beats ahead so the BLE round trip can be absorbed;
        // rendering them early would throw that away.
        val renderer = renderer(EffectProfiles.HARDSTYLE)
        renderer.onBeat(beat(second * 2))

        val beforeBeat = renderer.render(second).totalBrightness()
        val atBeat = renderer.render(second * 2).totalBrightness()

        assertEquals(0L, beforeBeat)
        assertTrue(atBeat > 0)
    }

    @Test
    fun `a downbeat is brighter than a plain beat`() {
        fun brightnessFor(type: BeatType): Long {
            val renderer = renderer(EffectProfiles.HARDSTYLE)
            renderer.onBeat(beat(second, type, beatInBar = if (type == BeatType.DOWNBEAT) 0 else 1))
            return renderer.render(second).totalBrightness()
        }

        assertTrue(brightnessFor(BeatType.DOWNBEAT) > brightnessFor(BeatType.BEAT))
    }

    @Test
    fun `an idle animation moves between frames and a static profile does not`() {
        val drifting = renderer(EffectProfiles.TRANCE)
        val first = drifting.render(second).bytes.copyOf()
        val later = drifting.render(second + 2 * second).bytes.copyOf()
        assertTrue("gradient drift should move", !first.contentEquals(later))

        val dark = renderer(EffectProfiles.HARDSTYLE)
        val darkFirst = dark.render(second).bytes.copyOf()
        val darkLater = dark.render(second + 2 * second).bytes.copyOf()
        assertTrue("an OFF idle should stay dark between beats", darkFirst.contentEquals(darkLater))
    }

    @Test
    fun `a drop brightens the frame for its window and then stops`() {
        val renderer = renderer(EffectProfiles.HARDSTYLE)
        val baseline = renderer.render(second).totalBrightness()

        renderer.onDrop(DropEvent(second, 1f, 3f, 3f, SectionState.BUILDUP))
        val during = renderer.render(second).totalBrightness()
        val after = renderer.render(second + (EffectProfiles.HARDSTYLE.dropSeconds * 1e9f).toLong() + second)
            .totalBrightness()

        assertTrue("drop should light the strip: $baseline -> $during", during > baseline)
        assertEquals("the drop window should have expired", baseline, after)
    }

    @Test
    fun `a profile that ignores drops stays unchanged`() {
        val renderer = renderer(EffectProfiles.AMBIENT)
        val before = renderer.render(second).bytes.copyOf()

        renderer.onDrop(DropEvent(second, 1f, 3f, 3f, SectionState.BUILDUP))
        val after = renderer.render(second).bytes.copyOf()

        assertTrue(before.contentEquals(after))
    }

    @Test
    fun `a breakdown dims the frame`() {
        val renderer = renderer(EffectProfiles.TRANCE)
        val normal = renderer.render(second).totalBrightness()

        renderer.onSection(SectionStateUpdate(SectionState.BREAKDOWN, 1f, second))
        val dimmed = renderer.render(second).totalBrightness()

        assertTrue("breakdown should dim: $normal -> $dimmed", dimmed < normal)
    }

    @Test
    fun `a build-up strobes, and faster as its ramp climbs`() {
        fun transitionsOverASecond(ramp: Float): Int {
            val renderer = renderer(EffectProfiles.HARDSTYLE)
            renderer.onSection(SectionStateUpdate(SectionState.BUILDUP, 1f, 0L, ramp))
            // A beat gives the strobe something to gate; an OFF idle would be dark
            // either way.
            renderer.onBeat(beat(0L))

            var transitions = 0
            var wasLit = false
            // Sample well above the fastest strobe rate so no flash is missed.
            for (step in 0 until 500) {
                val nanos = step * 2_000_000L
                val lit = renderer.render(nanos).totalBrightness() > 0
                if (step > 0 && lit != wasLit) transitions++
                wasLit = lit
            }
            return transitions
        }

        val slow = transitionsOverASecond(0f)
        val fast = transitionsOverASecond(1f)

        assertTrue("a build-up should strobe at all: $slow", slow > 0)
        assertTrue("the strobe should speed up: $slow -> $fast", fast > slow)
    }

    @Test
    fun `ring chase moves along the tail on successive beats`() {
        val renderer = renderer(EffectProfiles.TRANCE)

        fun brightestRing(): Int {
            val frame = renderer.render(second)
            var index = 0
            var best = -1
            var bestRing = 0
            layout.forEachIndexed { ring, count ->
                var sum = 0
                repeat(count) {
                    sum += frame.red(index) + frame.green(index) + frame.blue(index)
                    index++
                }
                if (sum > best) {
                    best = sum
                    bestRing = ring
                }
            }
            return bestRing
        }

        renderer.onBeat(beat(second))
        val first = brightestRing()
        renderer.onBeat(beat(second))
        val next = brightestRing()

        assertTrue("the chase should advance: $first -> $next", first != next)
    }

    @Test
    fun `sparkle is stable within a beat`() {
        val renderer = renderer(EffectProfiles.AMBIENT)
        renderer.onBeat(beat(second))

        // Same beat, two frames a few milliseconds apart: the *set* of lit LEDs
        // must not change, or a sparkle reads as noise instead of as a texture.
        val first = renderer.render(second).let { frame ->
            (0 until frame.ledCount).filter { frame.packed(it) != 0 }
        }
        val second2 = renderer.render(second + 5_000_000L).let { frame ->
            (0 until frame.ledCount).filter { frame.packed(it) != 0 }
        }

        assertEquals(first, second2)
    }

    @Test
    fun `reset clears events`() {
        val renderer = renderer(EffectProfiles.HARDSTYLE)
        renderer.onBeat(beat(second))
        assertTrue(renderer.render(second).totalBrightness() > 0)

        renderer.reset()

        assertEquals(0L, renderer.render(second).totalBrightness())
    }
}
