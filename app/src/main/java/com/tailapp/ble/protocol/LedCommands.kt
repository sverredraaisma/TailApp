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
}
