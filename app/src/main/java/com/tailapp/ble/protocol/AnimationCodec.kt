package com.tailapp.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the animation file `LCMD_UPLOAD_ANIM_CHUNK` carries and
 * `AnimationClip::validate` parses (LED-5).
 *
 * What is uploaded is the whole *stored file*, header included, so a blob that
 * reaches flash is self-describing and nothing about playback depends on the
 * command that carried it. That is also why the header is built here rather than
 * assembled inside the upload flow: the device cross-checks the geometry in
 * `LCMD_BEGIN_ANIMATION` against the byte count, and one place computing both is
 * what keeps them from disagreeing.
 *
 * File layout (TailFirmware `main/ble/ble_protocol.h`), little-endian:
 * ```
 * header (16 bytes)  [0-3]   u32 ANIMATION_MAGIC ("TANM")
 *                    [4]     u8  format version
 *                    [5]     u8  width in pixels
 *                    [6]     u8  height in pixels
 *                    [7]     u8  frame count
 *                    [8-9]   u16 frame duration in ms, >= 1
 *                    [10-11] u16 reserved, must be 0
 *                    [12-15] u32 CRC-32 of the frame data that follows
 * frames             frame count x (width * height * 3) bytes, RGB, row-major,
 *                    row 0 at y = 0 — the base of the tail, the same orientation
 *                    an FF03 image uses, so a one-frame animation renders
 *                    identically to one.
 * ```
 *
 * One duration for the whole animation rather than a per-frame table: in the
 * mode this effect exists for — locked to the beat the app streams in the FF05
 * trailer — the frame index is a function of beat phase and any authored
 * duration is discarded. An author who wants to hold a frame longer emits it
 * twice.
 */
object AnimationCodec {

    /**
     * Bytes one animation of this geometry occupies, header included. The same
     * arithmetic `LCMD_BEGIN_ANIMATION` does device-side to cross-check the
     * declared length, so a disagreement is impossible rather than merely
     * unlikely.
     */
    fun blobSize(width: Int, height: Int, frames: Int): Int =
        Protocol.ANIMATION_HEADER_SIZE + width * height * 3 * frames

    /**
     * @param frames one RGB buffer per frame, each `width * height * 3` bytes,
     *   row-major with row 0 at the base of the tail.
     * @param frameDurationMs how long each frame is held when free-running.
     *   Ignored while the effect is locked to the beat, but the device still
     *   requires it to be at least 1.
     *
     * @throws IllegalArgumentException for anything the device would refuse.
     *   Encoding a blob the tail will reject would spend the whole transfer to
     *   be told `BAD_STATE` at finalize, so it is caught here instead.
     */
    fun encode(
        width: Int,
        height: Int,
        frames: List<ByteArray>,
        frameDurationMs: Int
    ): ByteArray {
        LedCommands.animationError(width, height, frames.size)?.let {
            throw IllegalArgumentException(it)
        }
        require(frameDurationMs in 1..0xFFFF) {
            "frame duration must be 1..65535 ms, was $frameDurationMs"
        }
        val frameBytes = width * height * 3
        frames.forEachIndexed { index, frame ->
            require(frame.size == frameBytes) {
                "frame $index is ${frame.size} bytes; a ${width}x$height frame is $frameBytes"
            }
        }

        val pixels = ByteArray(frameBytes * frames.size)
        var offset = 0
        for (frame in frames) {
            frame.copyInto(pixels, offset)
            offset += frameBytes
        }

        val buffer = ByteBuffer.allocate(blobSize(width, height, frames.size))
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(Protocol.ANIMATION_MAGIC)
        buffer.put(Protocol.ANIMATION_FORMAT_VERSION.toByte())
        buffer.put(width.toByte())
        buffer.put(height.toByte())
        buffer.put(frames.size.toByte())
        buffer.putShort(frameDurationMs.toShort())
        // Reserved: the device refuses a blob with bits set here, because a
        // future revision will mean something by them.
        buffer.putShort(0)
        // CRC of the frame data only — the header carries it, so it cannot cover
        // itself. The *upload* is checked separately by the CRC armed in BEGIN,
        // which covers the whole file including this header.
        buffer.putInt(Crc32.compute(pixels))
        buffer.put(pixels)
        return buffer.array()
    }

    /**
     * Payload bytes one `LCMD_UPLOAD_ANIM_CHUNK` can carry at [mtu]: the ATT
     * payload is `mtu - 3`, and the command spends 3 of that on its id and the
     * u16 offset — the same budget a sequence chunk has.
     */
    fun chunkSizeForMtu(mtu: Int): Int = (mtu - 6).coerceAtLeast(MIN_CHUNK_BYTES)

    /**
     * What to assume before the connection reports a negotiated MTU. The floor
     * is the default 23-byte MTU's payload; it makes an upload slow, never wrong.
     */
    private const val MIN_CHUNK_BYTES = 17
}
