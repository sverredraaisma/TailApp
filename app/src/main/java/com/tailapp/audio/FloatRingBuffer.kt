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
 * **Why the backing array is twice [capacity].** Dropping the oldest samples
 * means the producer can in principle overwrite a region the consumer is
 * mid-copy, handing back a block that stitches together two different points in
 * time — a phantom transient, exactly what an onset detector must never see.
 * Validating the copy afterwards (a seqlock) is not sound on the JVM: a volatile
 * read has acquire semantics, so the plain array reads before it may legally be
 * reordered *after* the check. So safety is bought with space instead: the
 * consumer is never allowed to lag by more than half the array, leaving the
 * producer half a buffer of runway before it could touch the region being read.
 * With the default sizes that runway is tens of audio callbacks.
 *
 * @param requestedCapacity samples the buffer must hold. The backing array is
 *   rounded up to a power of two of twice this, so index wrapping is a mask.
 */
class FloatRingBuffer(requestedCapacity: Int) {
    init {
        require(requestedCapacity > 0) { "capacity must be positive" }
    }

    private val size: Int = nextPowerOfTwo(requestedCapacity * 2)
    private val mask: Long = (size - 1).toLong()
    private val buffer = FloatArray(size)

    /** How far the consumer may fall behind before samples are dropped. */
    private val maxLag: Long = (size / 2).coerceAtLeast(1).toLong()

    /** Total samples ever written, including those later overwritten. */
    private val writeIndex = AtomicLong(0)

    /** Total samples ever handed to the consumer, including those skipped on overrun. */
    private val readIndex = AtomicLong(0)

    private val overruns = AtomicLong(0)

    /** Samples the buffer can hold; the backing array is twice this (see the class doc). */
    val capacity: Int get() = maxLag.toInt()

    /** Samples written but not yet read. Never exceeds [capacity]. */
    val available: Int
        get() = (writeIndex.get() - readIndex.get()).coerceAtMost(maxLag).toInt()

    /** Samples dropped because the consumer fell behind. */
    val overrunCount: Long get() = overruns.get()

    /** Drops all pending samples and zeroes the overrun counter. */
    fun clear() {
        readIndex.set(writeIndex.get())
        overruns.set(0)
    }

    /**
     * Producer side: appends [count] samples from [src] starting at [offset].
     * Never blocks and never rejects; samples the consumer failed to collect in
     * time are simply overwritten and accounted for on the next [read].
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
        if (skipped > 0) overruns.addAndGet(skipped.toLong())
        // Publish the samples before the cursor: a concurrent reader must never
        // see a write index pointing at a slot that has not been filled yet.
        writeIndex.set(w + stored)
    }

    /**
     * Consumer side: copies up to [count] pending samples into [dest] starting at
     * [offset], returning how many were copied. Returns 0 when nothing is pending.
     *
     * Overruns are detected here rather than in [write] so that each cursor has
     * exactly one writing thread — a buffer that is written but never read
     * reports no overruns until the consumer comes back. A read either returns a
     * contiguous run of samples or nothing; it never straddles a dropped region,
     * even when the consumer is descheduled mid-copy.
     */
    fun read(dest: FloatArray, offset: Int = 0, count: Int = dest.size - offset): Int {
        require(offset >= 0 && count >= 0 && offset + count <= dest.size) {
            "read range out of bounds: offset=$offset count=$count dest=${dest.size}"
        }
        if (count == 0) return 0

        repeat(MAX_READ_ATTEMPTS) {
            val w = writeIndex.get()
            var r = readIndex.get()

            // Resync to the newest `maxLag` samples, keeping the producer half a
            // buffer away from anything we are about to copy.
            val lost = ((w - r) - maxLag).coerceAtLeast(0)
            r += lost

            val n = minOf((w - r).toInt(), count)
            if (n <= 0) {
                readIndex.set(r)
                if (lost > 0) overruns.addAndGet(lost)
                return 0
            }

            for (i in 0 until n) {
                dest[offset + i] = buffer[((r + i) and mask).toInt()]
            }

            // `getAndAdd(0)` rather than `get()`: this has to be a *full* barrier.
            // A volatile read only stops later accesses from moving earlier, so
            // the plain array loads above would be free to sink past a plain
            // `get()` and the check would validate a copy that had not happened
            // yet. The half-buffer runway makes this path rare; a descheduled
            // consumer on a loaded machine is what makes it necessary.
            if (writeIndex.getAndAdd(0) - r <= size) {
                readIndex.set(r + n)
                if (lost > 0) overruns.addAndGet(lost)
                return n
            }
            // Torn: leave readIndex untouched and count nothing, so the retry
            // (or the next call) accounts for the loss exactly once.
        }
        return 0
    }

    private companion object {
        /**
         * A read whose region keeps being overwritten is retried this many times
         * before giving up and returning nothing. The caller comes back for
         * fresher audio anyway, and an unbounded retry could spin forever against
         * a producer that is permanently faster.
         */
        const val MAX_READ_ATTEMPTS = 3

        fun nextPowerOfTwo(value: Int): Int {
            if (value <= 1) return 1
            return Integer.highestOneBit(value - 1) shl 1
        }
    }
}
