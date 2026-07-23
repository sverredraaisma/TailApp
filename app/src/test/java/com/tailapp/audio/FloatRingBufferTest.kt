package com.tailapp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Pins down the ring-buffer contract shared by the Kotlin [FloatRingBuffer] and
 * its C++ mirror in `app/src/main/cpp/ring_buffer.h`: wraparound is lossless
 * while the consumer keeps up, and lossy — but never corrupt — when it does not.
 */
class FloatRingBufferTest {

    @Test
    fun `capacity rounds up to a power of two`() {
        assertEquals(1, FloatRingBuffer(1).capacity)
        assertEquals(8, FloatRingBuffer(5).capacity)
        assertEquals(1024, FloatRingBuffer(1024).capacity)
        assertEquals(2048, FloatRingBuffer(1025).capacity)
    }

    @Test
    fun `read returns nothing when empty`() {
        val ring = FloatRingBuffer(16)
        assertEquals(0, ring.read(FloatArray(4)))
        assertEquals(0, ring.available)
    }

    @Test
    fun `write then read round-trips the samples`() {
        val ring = FloatRingBuffer(16)
        ring.write(floatArrayOf(1f, 2f, 3f))

        val out = FloatArray(4)
        assertEquals(3, ring.read(out))
        assertEquals(listOf(1f, 2f, 3f, 0f), out.toList())
        assertEquals(0, ring.available)
        assertEquals(0L, ring.overrunCount)
    }

    @Test
    fun `partial reads leave the remainder pending`() {
        val ring = FloatRingBuffer(16)
        ring.write(FloatArray(6) { it.toFloat() })

        val first = FloatArray(2)
        assertEquals(2, ring.read(first))
        assertEquals(listOf(0f, 1f), first.toList())
        assertEquals(4, ring.available)

        val rest = FloatArray(8)
        assertEquals(4, ring.read(rest))
        assertEquals(listOf(2f, 3f, 4f, 5f), rest.take(4))
    }

    @Test
    fun `writes wrap around the end of the buffer without loss`() {
        val ring = FloatRingBuffer(8)
        val out = FloatArray(8)

        // Cycle several times through the buffer, staying inside its capacity so
        // nothing should ever be dropped.
        var next = 0f
        repeat(20) {
            val chunk = FloatArray(5) { next + it }
            ring.write(chunk)
            assertEquals(5, ring.read(out, 0, 5))
            assertEquals(chunk.toList(), out.take(5))
            next += 5
        }
        assertEquals(0L, ring.overrunCount)
    }

    @Test
    fun `offset and count select a sub-range`() {
        val ring = FloatRingBuffer(16)
        ring.write(floatArrayOf(9f, 8f, 7f, 6f, 5f), offset = 1, count = 3)

        val out = FloatArray(5) { -1f }
        assertEquals(3, ring.read(out, offset = 1, count = 4))
        assertEquals(listOf(-1f, 8f, 7f, 6f, -1f), out.toList())
    }

    @Test
    fun `a slow consumer loses the oldest samples and they are counted`() {
        val ring = FloatRingBuffer(8)
        // 12 samples into an 8-slot buffer: the first 4 are gone.
        ring.write(FloatArray(12) { it.toFloat() })

        val out = FloatArray(12)
        assertEquals(8, ring.read(out))
        assertEquals((4..11).map { it.toFloat() }, out.take(8))
        assertEquals(4L, ring.overrunCount)
    }

    @Test
    fun `a single write larger than the buffer keeps only the newest samples`() {
        val ring = FloatRingBuffer(8)
        ring.write(FloatArray(20) { it.toFloat() })

        val out = FloatArray(8)
        assertEquals(8, ring.read(out))
        assertEquals((12..19).map { it.toFloat() }, out.toList())
        assertEquals(12L, ring.overrunCount)
    }

    @Test
    fun `clear drops pending samples and resets the counter`() {
        val ring = FloatRingBuffer(8)
        ring.write(FloatArray(12) { it.toFloat() })
        ring.read(FloatArray(8))
        assertEquals(4L, ring.overrunCount)

        ring.clear()
        assertEquals(0, ring.available)
        assertEquals(0L, ring.overrunCount)
        assertEquals(0, ring.read(FloatArray(4)))
    }

    /**
     * The real usage: an audio callback writing on one thread while the analysis
     * loop drains on another.
     *
     * Delivery is deliberately *not* asserted to be complete — on a loaded
     * machine the consumer thread can be starved long enough to lose samples,
     * and that is the buffer working as designed. What must hold regardless is
     * that every sample is either delivered exactly once or counted as an
     * overrun, and that what is delivered arrives in order.
     */
    @Test
    fun `concurrent producer and consumer never duplicate or reorder samples`() {
        val ring = FloatRingBuffer(4096)
        val total = 200_000
        val burst = 128
        val started = CountDownLatch(1)

        val producer = thread(name = "ring-producer") {
            started.countDown()
            val chunk = FloatArray(burst)
            var next = 0
            while (next < total) {
                val n = minOf(burst, total - next)
                for (i in 0 until n) chunk[i] = (next + i).toFloat()
                ring.write(chunk, 0, n)
                next += n
                if (next % (burst * 8) == 0) Thread.yield()
            }
        }

        var received = 0L
        var outOfOrder = 0
        var gaps = 0
        var lastSeen = -1f
        val out = FloatArray(512)

        fun drainOnce() {
            val n = ring.read(out)
            for (i in 0 until n) {
                if (i > 0 && out[i] != out[i - 1] + 1f) gaps++
                if (out[i] <= lastSeen) outOfOrder++
                lastSeen = out[i]
            }
            received += n
        }

        started.await(5, TimeUnit.SECONDS)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (producer.isAlive && System.nanoTime() < deadline) drainOnce()
        producer.join(TimeUnit.SECONDS.toMillis(5))
        while (ring.available > 0) drainOnce()

        assertEquals("samples arrived out of order or were replayed", 0, outOfOrder)
        assertEquals("a single read must never straddle a dropped region", 0, gaps)
        assertEquals(
            "every produced sample must be either delivered or counted as an overrun",
            total.toLong(), received + ring.overrunCount
        )
    }

    /**
     * The failure mode that matters when the consumer cannot keep up: samples may
     * be dropped, but what does come back must still be a clean window of audio.
     * A read that silently stitched together two points in time would put a phantom
     * transient in front of the onset detector.
     */
    @Test
    fun `an overrunning producer still hands the consumer contiguous runs`() {
        // Capacity well above the burst size: the half-buffer of runway the design
        // relies on is what keeps a read from being overwritten mid-copy.
        val ring = FloatRingBuffer(4096)
        val total = 400_000
        val producer = thread(name = "ring-producer-fast") {
            val chunk = FloatArray(64)
            var next = 0
            while (next < total) {
                for (i in chunk.indices) chunk[i] = (next + i).toFloat()
                ring.write(chunk)
                next += chunk.size
            }
        }

        val out = FloatArray(32)
        var received = 0L
        var gaps = 0
        var wentBackwards = 0
        var lastSeen = -1f

        fun drainOnce() {
            val n = ring.read(out)
            for (i in 0 until n) {
                if (i > 0 && out[i] != out[i - 1] + 1f) gaps++
                if (out[i] <= lastSeen) wentBackwards++
                lastSeen = out[i]
            }
            received += n
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var iterations = 0
        while (producer.isAlive && System.nanoTime() < deadline) {
            drainOnce()
            // Stall periodically so the consumer is reliably the slower side —
            // otherwise this test only exercises the lossless path on fast machines.
            if (++iterations % 64 == 0) Thread.sleep(1)
        }
        producer.join(TimeUnit.SECONDS.toMillis(5))
        // Drain what is left so the accounting below is complete.
        while (ring.available > 0) drainOnce()

        assertEquals("a single read must never straddle a dropped region", 0, gaps)
        assertEquals("samples must never be replayed or reordered", 0, wentBackwards)
        assertEquals(
            "every produced sample must be either delivered or counted as an overrun",
            total.toLong(), received + ring.overrunCount
        )
        assertTrue("expected the deliberately slow consumer to lose samples", ring.overrunCount > 0)
    }
}
