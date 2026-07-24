package com.tailapp.ble.protocol

import com.tailapp.model.OtaStatus
import com.tailapp.model.OtaTransferState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the FF0E status echo: `[accepted u32 LE][state u8][result u8]`.
 *
 * Readable as well as notified, for the same reason FF09 is — a notify is
 * best-effort and dropped silently when the device runs out of mbufs, and losing
 * one here would strand a firmware update rather than cost a packet. Every
 * caller that waits on a notify should be prepared to read instead.
 */
object OtaStatusParser {

    fun parse(data: ByteArray): OtaStatus? {
        if (data.size < Protocol.OTA_STATUS_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val accepted = buf.int
        // An unknown state byte is not a state: guessing at it would let a
        // future firmware's "erasing" read as ERROR and abandon a healthy
        // transfer.
        val state = OtaTransferState.fromCode(buf.get().toInt() and 0xFF) ?: return null
        return OtaStatus(
            accepted = accepted,
            state = state,
            result = CommandResultCode.fromCode(buf.get())
        )
    }
}
