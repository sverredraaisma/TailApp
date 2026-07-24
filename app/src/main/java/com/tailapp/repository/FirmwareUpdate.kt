package com.tailapp.repository

import com.tailapp.ble.protocol.CommandResultCode
import com.tailapp.ble.protocol.Protocol
import com.tailapp.model.OtaTransferState

/** Which step of BEGIN → chunks → FINALIZE a firmware update stopped at. */
enum class OtaStep {
    BEGIN,
    TRANSFER,
    FINALIZE;

    val label: String
        get() = when (this) {
            BEGIN -> "arming the update"
            TRANSFER -> "the transfer"
            FINALIZE -> "installing"
        }
}

/**
 * How a firmware update ended.
 *
 * The failures are kept apart because they mean different things to whoever
 * decides what happens next. [resumable] is the load-bearing distinction: the
 * device keeps an interrupted transfer armed across a disconnect — nothing on
 * either side ages it out — so a short or stalled transfer can be carried on
 * from the offset the device echoes, while a rejection that put the device in
 * `ERROR` has already discarded everything and has to start again. Either way
 * the app owes the user a decision, because a tail left armed indefinitely is
 * this feature's one way to make things worse than it found them.
 */
sealed class FirmwareUpdateResult {

    /** Verified, marked bootable, and it runs at the next reset. */
    data object Installed : FirmwareUpdateResult()

    /** The device answered, and said no. */
    data class Rejected(
        val step: OtaStep,
        val code: CommandResultCode,
        /** The FF0E transfer state at the moment of the rejection, when it was read. */
        val transferState: OtaTransferState? = null
    ) : FirmwareUpdateResult()

    /**
     * The echoed `accepted` offset stopped moving: every chunk since is being
     * discarded, and resending from the echo did not recover it.
     */
    data class Stalled(val accepted: Int, val total: Int) : FirmwareUpdateResult()

    /** The device stopped echoing altogether — notify and read both went quiet. */
    data class Unanswered(val step: OtaStep) : FirmwareUpdateResult()

    /** The write never left the phone; the tail is not connected. */
    data class NotWritten(val step: OtaStep) : FirmwareUpdateResult()

    /** The user called it off. The abort has been sent; nothing is armed. */
    data class Aborted(val accepted: Int, val total: Int) : FirmwareUpdateResult()

    val succeeded: Boolean get() = this is Installed

    /**
     * True when the device is still holding this transfer and streaming can pick
     * up from its echoed offset.
     */
    val resumable: Boolean
        get() = when (this) {
            is Stalled -> true
            is Unanswered -> step == OtaStep.TRANSFER || step == OtaStep.FINALIZE
            is NotWritten -> step == OtaStep.TRANSFER || step == OtaStep.FINALIZE
            // A short finalize leaves every byte in the slot a byte the app sent,
            // so the transfer stays armed. A CRC mismatch does not: those bytes
            // are wrong and sending more cannot repair them.
            is Rejected -> step == OtaStep.FINALIZE &&
                code == CommandResultCode.BAD_STATE &&
                transferState == OtaTransferState.RECEIVING
            else -> false
        }

    val message: String
        get() = when (this) {
            is Installed ->
                "Installed. Restart the tail to run it."

            is Aborted ->
                "Update cancelled. The tail is still running its old firmware."

            is Stalled ->
                "The tail stopped accepting bytes at ${accepted} of ${total}. " +
                    "Nothing is lost — the update can be resumed from there."

            is Unanswered ->
                "The tail went quiet during ${step.label}. It is still holding the " +
                    "transfer, so the update can be resumed or cancelled."

            is NotWritten ->
                "The tail disconnected during ${step.label}."

            is Rejected -> rejectionMessage()
        }

    private fun Rejected.rejectionMessage(): String = when {
        // Every code below is refused *before* anything is erased, which is the
        // point of checking them at all: the previously installed image in the
        // other slot is untouched, and the user gets an answer rather than a
        // silent failure.
        code == CommandResultCode.OTA_BAD_IMAGE && step == OtaStep.BEGIN ->
            "That file is too small to be firmware — under the " +
                "${Protocol.OTA_IMAGE_HEADER_BYTES}-byte header the tail decides from. " +
                "Pick the tail's .bin firmware image."

        code == CommandResultCode.OTA_BAD_IMAGE ->
            "The tail read the file's header and it is not an application image: the " +
                "magic bytes are wrong. The download is damaged, or it is the wrong file."

        code == CommandResultCode.OTA_WRONG_PROJECT ->
            "That firmware was built for a different project — it is somebody else's, " +
                "not this tail's. Nothing was written."

        code == CommandResultCode.OTA_SAME_VERSION ->
            "The tail is already running that version. There is nothing to install."

        code == CommandResultCode.OTA_FLASH_ERROR ->
            "The tail could not write it: an erase, a write or the activation failed on " +
                "the device. The image is fine; try again, and if it keeps failing the " +
                "tail needs a cable."

        code == CommandResultCode.OUT_OF_RANGE && step == OtaStep.BEGIN ->
            "The image is larger than the tail's ${Protocol.OTA_SLOT_BYTES / 1024} KB " +
                "update slot, so it cannot be installed over the air at all."

        code == CommandResultCode.BAD_STATE && step == OtaStep.BEGIN ->
            "This tail has no second firmware slot — it was flashed from a single-app " +
                "partition table and can only be updated over a cable."

        code == CommandResultCode.BAD_STATE && step == OtaStep.FINALIZE &&
            transferState == OtaTransferState.RECEIVING ->
            "The tail received fewer bytes than the update announced. Every byte it did " +
                "get is still good, so this can be resumed."

        code == CommandResultCode.BAD_STATE && step == OtaStep.FINALIZE ->
            "Checksum mismatch: what arrived on the tail is not the file that was sent. " +
                "The transfer has been discarded and has to start again."

        else -> "The tail refused ${step.label}: ${code.message}."
    }
}

/**
 * The transfer's flow-control settings.
 *
 * A data class rather than constants so a test can drive the timing rather than
 * wait on it, the same reason [AckRetryPolicy] is one.
 */
data class OtaTransferPolicy(
    /**
     * How far ahead of the last echoed offset the app may run.
     *
     * [Protocol.OTA_WINDOW_BYTES], and it must not be raised: the window is
     * sized to the device's eight-deep command queue, the real buffer between
     * the radio and the task that writes flash. Past it, chunks are dropped
     * silently — FF0E is unacknowledged — and every dropped chunk costs a rewind
     * to the echoed offset, so a wider window transfers *slower*.
     */
    val windowBytes: Int = Protocol.OTA_WINDOW_BYTES,

    /**
     * How long to wait for an echo before reading FF0E instead. The device
     * echoes every [Protocol.OTA_ECHO_BYTES] and the window is roughly a
     * round trip's worth of bytes, so silence this long means the notify was
     * dropped rather than that the device is busy.
     */
    val echoTimeoutMs: Long = 3_000L,

    /**
     * Consecutive echoes showing no progress before the transfer is abandoned.
     * One is normal — it is how a dropped chunk announces itself, and the answer
     * is to resume from the echo. Several in a row is a device that is not
     * taking the bytes at all.
     */
    val stallAttempts: Int = 4
) {
    init {
        require(windowBytes > 0) { "windowBytes must be positive" }
        require(echoTimeoutMs > 0) { "echoTimeoutMs must be positive" }
        require(stallAttempts >= 1) { "stallAttempts must be at least 1" }
    }
}
