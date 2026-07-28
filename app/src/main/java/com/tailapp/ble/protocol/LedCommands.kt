package com.tailapp.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object LedCommands {

    fun setLayerEffect(layer: Byte, effectId: Byte, blendMode: Byte): ByteArray =
        byteArrayOf(0x01, layer, effectId, blendMode)

    fun setEffectParam(layer: Byte, paramId: Byte, value: Float): ByteArray {
        val buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x02)
        buf.put(layer)
        buf.put(paramId)
        buf.putFloat(value)
        return buf.array()
    }

    fun removeLayer(layer: Byte): ByteArray =
        byteArrayOf(0x03, layer)

    fun setLayerTransform(
        layer: Byte,
        flipX: Boolean,
        flipY: Boolean,
        mirrorX: Boolean,
        mirrorY: Boolean
    ): ByteArray = byteArrayOf(
        0x04, layer,
        if (flipX) 1 else 0,
        if (flipY) 1 else 0,
        if (mirrorX) 1 else 0,
        if (mirrorY) 1 else 0
    )

    fun uploadImageChunk(offset: Int, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(3 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x05)
        buf.putShort(offset.toShort())
        buf.put(data)
        return buf.array()
    }

    fun finalizeImage(width: Byte, height: Byte, layer: Byte): ByteArray =
        byteArrayOf(0x06, width, height, layer)

    fun setLayerEnabled(layer: Byte, enabled: Boolean): ByteArray =
        byteArrayOf(0x07, layer, if (enabled) 1 else 0)

    /**
     * `0x08` Begin image upload (protocol v1).
     *
     * Clears the firmware staging buffer and arms a length + CRC-32 check that is
     * verified at [finalizeImage]. On mismatch the image is rejected and FF09
     * reports `BAD_STATE`.
     */
    fun beginImage(totalLength: Int, crc32: Int): ByteArray {
        val buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x08)
        buf.putShort(totalLength.toShort())
        buf.putInt(crc32)
        return buf.array()
    }

    /**
     * `0x09` Set Direct Mode. Enabling bypasses the layer/effect/compositor stack
     * so pixels streamed on FF0A ([DirectPixelFrame]) are shown as-is; disabling
     * resumes normal effect rendering. Not persisted — a transient session flag
     * that the firmware also reverts on its own if the app disconnects while
     * direct mode is on (see `app_bridge.cpp::app_led_render`).
     */
    fun setDirectMode(enabled: Boolean): ByteArray =
        byteArrayOf(0x09, if (enabled) 1 else 0)

    /**
     * `0x0A` Per-layer opacity, 0-255 (protocol v5).
     *
     * Applies to every blend mode, not just Normal: a half-opacity Add is a
     * weaker glow, which is what makes deep stacks tractable rather than
     * saturating to white.
     */
    fun setLayerOpacity(layer: Byte, opacity: Int): ByteArray =
        byteArrayOf(0x0A, layer, opacity.coerceIn(0, 255).toByte())

    /**
     * `0x0B` Output stage: master brightness, gamma, and the current budget
     * (protocol v5).
     *
     * [currentLimitMa] of 0 disables limiting. It exists because a wearable's
     * regulator cannot deliver what a full-white frame asks for — roughly 60 mA
     * per LED — and the failure mode without it is the rail browning out
     * mid-frame rather than the picture dimming.
     */
    fun setOutputConfig(
        brightness: Int,
        gammaEnabled: Boolean,
        currentLimitMa: Int
    ): ByteArray {
        val limit = currentLimitMa.coerceIn(0, 65535)
        return byteArrayOf(
            0x0B,
            brightness.coerceIn(0, 255).toByte(),
            if (gammaEnabled) 1 else 0,
            (limit and 0xFF).toByte(),
            ((limit shr 8) and 0xFF).toByte()
        )
    }

    /**
     * `0x0C` Set the LED render frame rate, in frames per second.
     *
     * Deliberately **not** clamped here. The firmware rejects anything outside
     * [Protocol.LED_FRAME_RATE_MIN]..[Protocol.LED_FRAME_RATE_MAX] with
     * `OUT_OF_RANGE` rather than clamping, precisely so an app that asked for
     * 120 fps is told it did not get it; clamping on this side would recreate
     * the confusion the firmware went out of its way to avoid. Below the floor
     * the FF0A stale-frame timeout starts to be measured in frames rather than
     * seconds; above the ceiling the MCU cannot composite a non-trivial stack
     * anyway.
     *
     * @throws IllegalArgumentException if [fps] is one the device would refuse.
     */
    fun setFrameRate(fps: Int): ByteArray {
        require(frameRateError(fps) == null) {
            "frame rate must be ${Protocol.LED_FRAME_RATE_MIN}-${Protocol.LED_FRAME_RATE_MAX} fps, was $fps"
        }
        return byteArrayOf(0x0C, fps.toByte())
    }

    /** Why the device would refuse [fps], or null if it would accept it. */
    fun frameRateError(fps: Int): String? =
        if (fps < Protocol.LED_FRAME_RATE_MIN || fps > Protocol.LED_FRAME_RATE_MAX) {
            "Frame rate must be ${Protocol.LED_FRAME_RATE_MIN}-${Protocol.LED_FRAME_RATE_MAX} fps; " +
                "the device refuses anything else rather than clamping it."
        } else {
            null
        }

    /**
     * `0x0D` Begin an animation upload (LED-5):
     * `[slot][width][height][frames][total_len u16 LE][crc32 u32 LE]`.
     *
     * The same BEGIN → chunks → FINALIZE shape as an image or a keyframe
     * sequence. What is transferred is the whole stored file, header included
     * ([AnimationCodec]), so a blob that reaches flash is self-describing.
     *
     * The geometry here is a *cross-check*, not metadata: the device recomputes
     * `ANIMATION_HEADER_SIZE + width*height*3*frames` and refuses a BEGIN whose
     * declared [totalLength] disagrees, so an app that miscomputed its own header
     * is told before it spends thirty packets. [AnimationCodec.blobSize] is the
     * same arithmetic, which is why the two cannot drift.
     *
     * Unlike an image chunk there is no legacy chunks-only flow: a chunk with
     * nothing armed is refused `BAD_STATE`. A BEGIN can also come back `BUSY`
     * (the device stages the whole transfer in RAM and may not have it right
     * now) or `BAD_STATE` (its animation filesystem did not mount) — both are
     * answered before a single byte is sent.
     *
     * @throws IllegalArgumentException if the geometry is one the device would refuse.
     */
    fun beginAnimation(
        slot: Byte,
        width: Int,
        height: Int,
        frames: Int,
        totalLength: Int,
        crc32: Int
    ): ByteArray {
        require(animationError(width, height, frames) == null) {
            "animation geometry rejected: ${animationError(width, height, frames)}"
        }
        require(totalLength == AnimationCodec.blobSize(width, height, frames)) {
            "total length $totalLength does not match the ${width}x${height} x$frames " +
                "geometry (${AnimationCodec.blobSize(width, height, frames)} bytes)"
        }
        val buf = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x0D)
        buf.put(slot)
        buf.put(width.toByte())
        buf.put(height.toByte())
        buf.put(frames.toByte())
        buf.putShort(totalLength.toShort())
        buf.putInt(crc32)
        return buf.array()
    }

    /** Why the device would refuse this animation's geometry, or null if it would take it. */
    fun animationError(width: Int, height: Int, frames: Int): String? = when {
        width !in 1..Protocol.ANIMATION_MAX_DIM || height !in 1..Protocol.ANIMATION_MAX_DIM ->
            "Animation size must be 1-${Protocol.ANIMATION_MAX_DIM} pixels on each side."
        frames !in 1..Protocol.ANIMATION_MAX_FRAMES ->
            "An animation must have 1-${Protocol.ANIMATION_MAX_FRAMES} frames."
        AnimationCodec.blobSize(width, height, frames) > Protocol.ANIMATION_MAX_BYTES ->
            "That animation is ${AnimationCodec.blobSize(width, height, frames)} bytes; the " +
                "device stages the whole upload in RAM and accepts at most " +
                "${Protocol.ANIMATION_MAX_BYTES}."
        else -> null
    }

    /** `0x0E` One slice of the animation blob at a u16 offset from its start. */
    fun uploadAnimationChunk(offset: Int, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(3 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x0E)
        buf.putShort(offset.toShort())
        buf.put(data)
        return buf.array()
    }

    /**
     * `0x0F` Verify the uploaded blob against what [beginAnimation] armed and
     * commit it to the animation filesystem.
     *
     * [slot] has to be the slot BEGIN armed: finalizing a different one means the
     * app lost track of its own upload, and committing to either would be a
     * guess. The device checks the byte count as well as the CRC — a transfer
     * that stopped early would otherwise CRC perfectly well against the zeroed
     * padding BEGIN left — then validates the blob structurally before storing
     * it, so it never keeps a file it would refuse to render. Every one of those
     * rejections arrives on FF09 as `BAD_STATE`, and the slot's previous contents
     * survive.
     */
    fun finalizeAnimation(slot: Byte): ByteArray = byteArrayOf(0x0F, slot)
}
