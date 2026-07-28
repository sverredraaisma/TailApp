package com.tailapp.repository

import com.tailapp.ble.protocol.CommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pairs FF09 acknowledgements with the writes that caused them.
 *
 * The firmware answers every non-stream write on FF09 with
 * `[char][cmd][result][seq]`, in the order it processed the commands. Before the
 * sequence byte (protocol v5) that ordering was all there was, so two identical
 * commands in flight — two `SET_EFFECT_PARAM` writes from one slider drag — were
 * indistinguishable, and a dropped notification silently shifted every later
 * answer onto the previous command. The sequence byte is a device-side counter
 * incremented once per command processed (`app_bridge.cpp::publish_result`), so a
 * small hole in the numbering is the number of answers that never arrived — but
 * only a small one: the firmware stamps acks from two independent counters, and
 * [onResult] says what that costs.
 *
 * Registration order is the correlation key, which is why [send] holds its lock
 * across the write itself rather than around the bookkeeping alone.
 */
class CommandAckTracker {

    private class Waiter(
        val characteristicId: Byte,
        val commandId: Byte,
        val ack: CompletableDeferred<CommandResult?> = CompletableDeferred()
    )

    private val mutex = Mutex()
    private val waiting = ArrayDeque<Waiter>()

    /**
     * Sequence byte of the last result seen. Null before the first result of a
     * connection: the device's counter keeps running across connections, so the
     * baseline has to be re-established rather than compared against a stale one.
     */
    private var lastSequence: Int? = null

    /** One registered command and the answer it is still owed. */
    class Pending internal constructor(
        /** What the GATT write reported. False means the command never left the phone. */
        val written: Boolean,
        private val ack: CompletableDeferred<CommandResult?>
    ) {
        /**
         * Suspends for the device's verdict.
         *
         * Null covers every way there isn't one: the acknowledgement was lost,
         * the connection dropped, or [timeoutMs] elapsed first. A timeout leaves
         * the registration in place deliberately — the device may still answer,
         * and dropping the waiter would shift the answers behind it onto the
         * wrong commands.
         */
        suspend fun await(timeoutMs: Long): CommandResult? =
            withTimeoutOrNull(timeoutMs) { ack.await() }
    }

    /**
     * Registers the acknowledgement for one command, runs [write] with the
     * registration held, and yields a handle the caller may await or ignore.
     *
     * The lock spans the write on purpose. The device acknowledges in the order
     * it accepted the commands, so registration order is only meaningful if it
     * cannot interleave with the writes it describes. (`BleConnectionManager`
     * serialises GATT operations behind a mutex of its own, but that one is not
     * visible here and says nothing about the order registrations were taken in.)
     *
     * A write that never left the phone takes its registration back out inside
     * the same critical section: a waiter for a command the device never saw
     * would shift every later acknowledgement onto the wrong command, and no
     * sequence gap would ever reveal it, because the device never counted that
     * command at all.
     */
    suspend fun send(
        characteristicId: Byte,
        commandId: Byte,
        write: suspend () -> Boolean
    ): Pending {
        val waiter = Waiter(characteristicId, commandId)
        val written = mutex.withLock {
            waiting.addLast(waiter)
            // A registration only outlives its answer when the device is not
            // answering at all. Its command queue is eight deep, so this bound is
            // far above any real burst and exists only to stop a silent device
            // leaking one waiter per command for the life of the connection —
            // past this point correlation is lost anyway.
            while (waiting.size > MAX_OUTSTANDING) waiting.removeFirst().ack.complete(null)
            val ok = write()
            if (!ok) {
                waiting.remove(waiter)
                waiter.ack.complete(null)
            }
            ok
        }
        return Pending(written, waiter.ack)
    }

    /**
     * Correlates one incoming FF09 result with a registered write.
     *
     * @return true if the result was credited to a registered command.
     */
    suspend fun onResult(result: CommandResult): Boolean = mutex.withLock {
        val previous = lastSequence
        val sequence = result.sequence
        if (sequence != null) lastSequence = sequence

        if (sequence != null && previous != null) {
            // The whole point of the sequence byte: a gap is the count of
            // acknowledgements that never arrived, which means this result
            // belongs to the command *after* them and not to the oldest one still
            // waiting.
            //
            // Only within a plausible gap, though, because the firmware does not
            // have one counter. `app_bridge.cpp::publish_result` stamps executed
            // commands from `g_ack_seq`; `ble_service.c::ack_bad_write` answers a
            // write rejected before dispatch (zero-length, over the wire cap) from
            // its own `bad_write_ack_seq`, deliberately separate. One ack from the
            // other source therefore shows up here as an arbitrary jump — and
            // reading that as "N answers were lost" would abandon commands the
            // device is still perfectly well going to answer. Anything past
            // [MAX_SEQUENCE_GAP] is treated as a counter discontinuity: the
            // baseline moves (above) and nobody waiting is given up on.
            val gap = (sequence - previous - 1) and 0xFF
            if (gap <= MAX_SEQUENCE_GAP) {
                repeat(gap.coerceAtMost(waiting.size)) { waiting.removeFirst().ack.complete(null) }
            }
        }
        // Firmware that sends no sequence byte falls through to plain FIFO
        // matching below. What is given up is precisely the loss detection above:
        // there, a dropped notification shifts every later answer onto the
        // previous command with nothing left to notice it.

        val head = waiting.firstOrNull() ?: return@withLock false
        if (head.characteristicId != result.characteristicId || head.commandId != result.commandId) {
            // An acknowledgement for something this app did not register — a
            // read-back of the device's last result, or a command from a previous
            // session. Consuming a waiter would answer the wrong command. The
            // sequence accounting above still ran, so the next real match is not
            // mistaken for a gap.
            return@withLock false
        }
        waiting.removeFirst()
        head.ack.complete(result)
        true
    }

    /**
     * Abandons every outstanding registration and forgets the sequence baseline.
     *
     * Called on disconnect: a caller waiting on an answer from a device that is
     * gone must not hang, and the device's counter carries on across connections,
     * so the first result of the next one has to set the baseline instead of being
     * read as a gap of however many commands happened in between.
     */
    suspend fun abandonAll() {
        mutex.withLock {
            while (waiting.isNotEmpty()) waiting.removeFirst().ack.complete(null)
            lastSequence = null
        }
    }

    /** Registrations still owed an answer. Diagnostics and tests only. */
    suspend fun outstanding(): Int = mutex.withLock { waiting.size }

    companion object {
        /** See the bound in [send]. */
        internal const val MAX_OUTSTANDING = 64

        /**
         * The largest sequence gap still read as lost acknowledgements.
         *
         * The device's command queue is eight deep, so no more than that many
         * answers can be in flight to lose at once. A bigger jump is not a burst
         * of losses at all — it is the *other* counter (see [onResult]), or a
         * device that rebooted — and abandoning waiters on it would be the one
         * failure the sequence byte was added to prevent.
         */
        internal const val MAX_SEQUENCE_GAP = 8
    }
}

/**
 * The outcome of a write that waited for its acknowledgement.
 *
 * [written] and [result] are kept apart because they mean different things to a
 * caller: a write that never left the phone is a dead connection and the next one
 * will fail the same way, while a missing verdict on a write that did leave says
 * only that a best-effort notification went astray.
 */
data class AckedWrite(val written: Boolean, val result: CommandResult?) {

    /** True only when the device said it accepted the command. */
    val accepted: Boolean get() = result?.isSuccess == true

    /** True when the device explicitly refused it, as opposed to not answering. */
    val rejected: Boolean get() = result != null && !result.isSuccess
}

/**
 * What a waited-on command does about `RESULT_BUSY`.
 *
 * BUSY is the one rejection the same bytes can survive: it means the device's
 * eight-deep command queue was full when the write arrived
 * (`command_queue.h::push` returning false), and that queue is drained once per
 * frame, so a burst needs a frame or two of patience rather than a different
 * command. Spelled out as values instead of a sleep inside the retry loop so a
 * test can assert how many attempts a give-up actually took.
 */
data class AckRetryPolicy(
    /** How long one answer is waited for before it counts as missing. */
    val timeoutMs: Long = 1_000L,

    /** Attempts in total, including the first. */
    val maxAttempts: Int = 3,

    /** Pause between attempts, comfortably past one drain interval. */
    val retryDelayMs: Long = 40L
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        require(retryDelayMs >= 0) { "retryDelayMs must not be negative" }
    }
}
