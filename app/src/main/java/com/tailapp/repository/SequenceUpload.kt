package com.tailapp.repository

import com.tailapp.ble.protocol.CommandResultCode

/** Which step of BEGIN → chunks → FINALIZE an upload stopped at. */
enum class SequenceUploadStep {
    BEGIN,
    CHUNK,
    FINALIZE;

    val label: String
        get() = when (this) {
            BEGIN -> "begin"
            CHUNK -> "transfer"
            FINALIZE -> "finalize"
        }
}

/**
 * How a keyframe-sequence upload ended.
 *
 * Deliberately not a boolean. The handshake exists so the device can refuse a
 * blob whose length or CRC does not match what BEGIN armed, and that refusal
 * arrives as `BAD_STATE` at FINALIZE — collapsing it into "it failed" would
 * throw away the one piece of information the whole protocol was built to
 * deliver. The three failures are kept apart because they mean different things
 * to whoever retries: a rejection is about the data, a missing answer is about
 * the air, and a write that never left is about the connection.
 */
sealed class SequenceUploadResult {

    data object Success : SequenceUploadResult()

    /** The device answered, and said no. */
    data class Rejected(
        val step: SequenceUploadStep,
        val code: CommandResultCode
    ) : SequenceUploadResult()

    /** The write left the phone; no acknowledgement came back for it. */
    data class Unanswered(val step: SequenceUploadStep) : SequenceUploadResult()

    /** The write never left the phone — the connection is gone. */
    data class NotWritten(val step: SequenceUploadStep) : SequenceUploadResult()

    val succeeded: Boolean get() = this is Success

    val message: String
        get() = when (this) {
            is Success -> "Sequence stored on the tail."
            is Rejected -> when {
                step == SequenceUploadStep.FINALIZE && code == CommandResultCode.BAD_STATE ->
                    "The tail refused the sequence at finalize: the bytes it received do not " +
                        "match the length and checksum the upload announced. Try again."
                code == CommandResultCode.OUT_OF_RANGE && step == SequenceUploadStep.BEGIN ->
                    "The tail refused the upload: that slot or sequence size is out of range."
                else -> "The tail refused the upload at ${step.label}: ${code.message}."
            }
            is Unanswered -> "The tail never answered the ${step.label} step — upload abandoned."
            is NotWritten -> "The ${step.label} step could not be sent; the tail is not connected."
        }
}
