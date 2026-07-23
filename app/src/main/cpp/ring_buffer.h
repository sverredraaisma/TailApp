#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

/**
 * Single-producer / single-consumer lock-free float ring buffer.
 *
 * The producer is an Oboe audio callback: it must never allocate, lock, or
 * block, so `write` always succeeds and simply lets the oldest samples fall off
 * the back when the consumer is behind. For live analysis, fresh audio is worth
 * more than complete audio.
 *
 * Each index is written by exactly one thread — the producer owns `write_index_`
 * and the consumer owns `read_index_` and `overruns_`. Overrun is therefore
 * detected and accounted on the *read* side; a consumer that never reads sees
 * `overruns()` stay at zero until it does.
 *
 * The backing array is twice `capacity()`: dropping the oldest samples means the
 * producer could otherwise overwrite a region the consumer is mid-copy and hand
 * back a block stitched from two points in time. Capping the consumer's lag at
 * half the array leaves the producer half a buffer of runway — tens of audio
 * callbacks — before it could reach what is being read.
 *
 * Mirrored in Kotlin as `com.tailapp.audio.FloatRingBuffer`, which is where the
 * JVM tests pin these semantics down.
 */
class FloatRingBuffer {
public:
    explicit FloatRingBuffer(int32_t requested_capacity)
        : size_(next_pow2(requested_capacity * 2)),
          mask_(static_cast<int64_t>(size_) - 1),
          max_lag_(size_ / 2 > 0 ? size_ / 2 : 1),
          buffer_(static_cast<size_t>(size_), 0.0f) {}

    /** Producer side. Called from the audio callback — allocation-free. */
    void write(const float *src, int32_t count) {
        if (src == nullptr || count <= 0) return;

        // A burst larger than the whole buffer can only leave its newest samples.
        const int32_t skipped = count > size_ ? count - size_ : 0;
        const int32_t stored = count - skipped;
        const int64_t w = write_index_.load(std::memory_order_relaxed);

        for (int32_t i = 0; i < stored; i++) {
            buffer_[static_cast<size_t>((w + i) & mask_)] = src[skipped + i];
        }
        // Release: publish the samples before the cursor that exposes them.
        write_index_.store(w + stored, std::memory_order_release);
    }

    /**
     * Consumer side. Returns the number of samples copied into `dest`. A read
     * either returns a contiguous run or nothing; it never straddles a region the
     * producer dropped.
     */
    int32_t read(float *dest, int32_t count) {
        if (dest == nullptr || count <= 0) return 0;

        for (int attempt = 0; attempt < kMaxReadAttempts; attempt++) {
            const int64_t w = write_index_.load(std::memory_order_acquire);
            int64_t r = read_index_.load(std::memory_order_relaxed);

            // Resync to the newest max_lag_ samples, keeping the producer half a
            // buffer away from anything we are about to copy.
            const int64_t lost = (w - r) - max_lag_ > 0 ? (w - r) - max_lag_ : 0;
            r += lost;

            const int64_t ready = w - r;
            const int32_t n = static_cast<int32_t>(ready < count ? ready : count);
            if (n <= 0) {
                read_index_.store(r, std::memory_order_release);
                if (lost > 0) overruns_.fetch_add(lost, std::memory_order_relaxed);
                return 0;
            }

            for (int32_t i = 0; i < n; i++) {
                dest[i] = buffer_[static_cast<size_t>((r + i) & mask_)];
            }

            // Full fence before validating: an acquire load alone would let the
            // plain reads above be reordered after it, validating a copy that had
            // not happened yet. Rare — the runway above usually prevents it — but
            // a consumer descheduled mid-copy is exactly when it matters.
            std::atomic_thread_fence(std::memory_order_seq_cst);
            if (write_index_.load(std::memory_order_acquire) - r <= size_) {
                read_index_.store(r + n, std::memory_order_release);
                if (lost > 0) overruns_.fetch_add(lost, std::memory_order_relaxed);
                return n;
            }
            // Torn: leave read_index_ and the counter alone so the retry (or the
            // next call) accounts for the loss exactly once.
        }
        return 0;
    }

    /** Samples pending, clamped to `capacity()`. */
    int32_t available() const {
        const int64_t pending =
            write_index_.load(std::memory_order_acquire) - read_index_.load(std::memory_order_relaxed);
        return static_cast<int32_t>(pending < max_lag_ ? pending : max_lag_);
    }

    int64_t overruns() const { return overruns_.load(std::memory_order_relaxed); }

    /** Consumer side only: drops everything pending and resets the counter. */
    void clear() {
        read_index_.store(write_index_.load(std::memory_order_acquire), std::memory_order_release);
        overruns_.store(0, std::memory_order_relaxed);
    }

    /** Samples the buffer holds. The backing array is twice this — see the class doc. */
    int32_t capacity() const { return static_cast<int32_t>(max_lag_); }

private:
    /** See `read`: bounded so a permanently faster producer cannot spin us forever. */
    static constexpr int kMaxReadAttempts = 3;

    static int32_t next_pow2(int32_t value) {
        int32_t v = value < 1 ? 1 : value;
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        return v + 1;
    }

    const int32_t size_;
    const int64_t mask_;
    const int64_t max_lag_;
    std::vector<float> buffer_;

    std::atomic<int64_t> write_index_{0};
    std::atomic<int64_t> read_index_{0};
    std::atomic<int64_t> overruns_{0};
};
