package com.tailapp.ble.protocol

import com.tailapp.model.KeyframeSequence
import com.tailapp.model.TailPose
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns an authored [KeyframeSequence] into the blob `MCMD_UPLOAD_SEQ_CHUNK`
 * carries and `KeyframeSequence::load` parses.
 *
 * Fixed-size, little-endian records — the firmware reads them with a struct
 * write and no parser, and checks the byte length against the keyframe count
 * *exactly*, so a record this side is one byte wrong is a rejected upload rather
 * than a half-played animation.
 *
 * Blob layout (TailFirmware `main/ble/ble_protocol.h`):
 * ```
 * header (8 bytes)   [0] u8 format version  [1] u8 flags (bit0 = loop)
 *                    [2-3] u16 LE count     [4-7] u32 LE reserved, must be 0
 * keyframe (12 each) [0-3]  u32 LE time_ms from the start, strictly increasing
 *                    [4-5]  i16 LE segment 0 (base) X, degrees x 100
 *                    [6-7]  i16 LE segment 0 Y
 *                    [8-9]  i16 LE segment 1 (tip) X
 *                    [10-11] i16 LE segment 1 Y
 * ```
 */
object KeyframeSequenceCodec {

    /**
     * @throws IllegalArgumentException if [sequence] is one the device would
     *   refuse. Encoding an invalid sequence would spend the whole transfer to
     *   be told `BAD_STATE` at finalize, so the editor validates first and this
     *   is the guard that it did.
     */
    fun encode(sequence: KeyframeSequence): ByteArray {
        val problems = sequence.validate()
        require(problems.isEmpty()) {
            "sequence cannot be encoded: " + problems.joinToString("; ") { it.message }
        }

        val buffer = ByteBuffer.allocate(sequence.encodedSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(Protocol.SEQUENCE_FORMAT_VERSION)
        buffer.put(if (sequence.loop) Protocol.SEQUENCE_FLAG_LOOP else 0)
        buffer.putShort(sequence.keyframes.size.toShort())
        // Reserved: the device refuses a blob with bits set here, because a
        // future revision will mean something by them.
        buffer.putInt(0)

        for (keyframe in sequence.keyframes) {
            buffer.putInt(keyframe.timeMs)
            buffer.putShort(TailPose.quantiseDegrees(keyframe.pose.baseX).toShort())
            buffer.putShort(TailPose.quantiseDegrees(keyframe.pose.baseY).toShort())
            buffer.putShort(TailPose.quantiseDegrees(keyframe.pose.tipX).toShort())
            buffer.putShort(TailPose.quantiseDegrees(keyframe.pose.tipY).toShort())
        }
        return buffer.array()
    }

    /**
     * Payload bytes one `MCMD_UPLOAD_SEQ_CHUNK` can carry at [mtu]: the ATT
     * payload is `mtu - 3`, and the command spends 3 of that on its id and the
     * u16 offset.
     */
    fun chunkSizeForMtu(mtu: Int): Int = (mtu - 6).coerceAtLeast(MIN_CHUNK_BYTES)

    /**
     * What to assume before the connection reports a negotiated MTU. The floor
     * is the default 23-byte MTU's payload; it makes an upload slow, never wrong.
     */
    private const val MIN_CHUNK_BYTES = 17
}
