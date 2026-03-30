package com.tailapp.ble.protocol

import com.tailapp.model.LayerConfig
import com.tailapp.model.LedState
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LedStateParser {

    private const val LAYER_SIZE = 39 // 7 bytes header + 8 * 4 bytes params

    fun parse(data: ByteArray): LedState? {
        if (data.isEmpty()) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val numRings = buf.get().toInt() and 0xFF
        if (buf.remaining() < numRings + 1) return null

        val ledsPerRing = List(numRings) { buf.get().toInt() and 0xFF }
        val numLayers = buf.get().toInt() and 0xFF

        if (buf.remaining() < numLayers * LAYER_SIZE) return null

        val layers = List(numLayers) {
            val effectId = buf.get()
            val blendMode = buf.get()
            val enabled = buf.get().toInt() != 0
            val flipX = buf.get().toInt() != 0
            val flipY = buf.get().toInt() != 0
            val mirrorX = buf.get().toInt() != 0
            val mirrorY = buf.get().toInt() != 0
            val params = List(8) { buf.float }

            LayerConfig(
                effectId = effectId,
                blendMode = blendMode,
                enabled = enabled,
                flipX = flipX, flipY = flipY,
                mirrorX = mirrorX, mirrorY = mirrorY,
                params = params
            )
        }

        return LedState(numRings = numRings, ledsPerRing = ledsPerRing, layers = layers)
    }
}
