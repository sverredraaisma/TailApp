package com.tailapp.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Single-producer / single-consumer lock-free float ring buffer.
 *
 * Mirrors `app/src/main/cpp/ring_buffer.h`, the buffer the Oboe callback writes
 * into — this Kotlin copy backs the `AudioRecord` source and is what the JVM
 * tests exercise, so the wraparound and overrun semantics stay pinned down in
 * one place.
 *
 * The producer calls [write] from exactly one thread, the consumer [read] from
 * exactly one other. Writes never block: when the reader falls behind, the
 * oldest samples are dropped and counted in [overrunCount], because for live
 * analysis fresh audio matters more than complete audio.
 *
 * @param requestedCapacity number of samples the buffer must hold. Rounded up to
 *   a power of two so index wrapping is a mask rather than a modulo.
 */
class FloatRingBuffer(requestedCapacity: Int) {
    init {
        require(requestedCapacity > 0) { "capacity must be positive" }
    }

    private val size: Int = nextPowerOfTwo(requestedCapacity)
    private val mask: Long = (size - 1).toLong()
    private val buffer = FloatArray(size)

    /** Total samples ever written, including those later overwritten. */
    private val writeIndex = AtomicLong(0)

    /** Total samples ever handed to the consumer, including those skipped on overrun. */
    private val readIndex = AtomicLong(0)

    private val overruns = AtomicLong(0)

    /** Sample slots in the buffer (a power of two, >= the requested capacity). */
    val capacity: Int get() = size

    /** Samples written but not yet read. Never exceeds [capacity]. */
    val available: Int
        get() = (writeIndex.get() - readIndex.get()).coerceAtMost(size.toLong()).toInt()

    /** Samples dropped because the consumer fell behind. */
    val overrunCount: Long get() = overruns.get()

    /** Drops all pending samples and zeroes the overrun counter. */
    fun clear() {
        readIndex.set(writeIndex.get())
        overruns.set(0)
    }

    /**
     * Producer side: appends [count] samples from [src] starting at [offset].
     * Always accepts everything; if that overruns the reader, the reader's cursor
     * is advanced past the samples it lost.
     */
    fun write(src: FloatArray, offset: Int = 0, count: Int = src.size - offset) {
        require(offset >= 0 && count >= 0 && offset + count <= src.size) {
            "write range out of bounds: offset=$offset count=$count src=${src.size}"
        }
        if (count == 0) return

        // A single write bigger than the buffer can only leave its newest `size`
        // samples behind; the rest are dropped before they are ever stored.
        val skipped = (count - size).coerceAtLeast(0)
        val stored = count - skipped
        val w = writeIndex.get()

        for (i in 0 until stored) {
            buffer[((w + i) and mask).toInt()] = src[offset + skipped + i]
        }
        // Publish the samples before the cursor: a concurrent reader must never
        // see a write index pointing at a slot that has not been filled yet.
        writeIndex.set(w + stored)

        val r = readIndex.get()
        val lost = (w + stored - r) - size
        if (lost > 0) readIndex.set(r + lost)

        val dropped = skipped + lost.coerceAtLeast(0L)
        if (dropped > 0) overruns.addAndGet(dropped)
    }

    /**
     * Consumer side: copies up to [count] pending samples into [dest] starting at
     * [offset], returning how many were copied. Returns 0 when nothing is pending.
     */
    fun read(dest: FloatArray, offset: Int = 0, count: Int = dest.size - offset): Int {
        require(offset >= 0 && count >= 0 && offset + count <= dest.size) {
            "read range out of bounds: offset=$offset count=$count dest=${dest.size}"
        }
        if (count == 0) return 0

        val r = readIndex.get()
        val ready = (writeIndex.get() - r).coerceAtMost(size.toLong()).toInt()
        val n = minOf(ready, count)
        if (n <= 0) return 0

        for (i in 0 until n) {
            dest[offset + i] = buffer[((r + i) and mask).toInt()]
        }
        readIndex.set(r + n)
        return n
    }

    private companion object {
        fun nextPowerOfTwo(value: Int): Int {
            if (value <= 1) return 1
            return Integer.highestOneBit(value - 1) shl 1
        }
    }
}
