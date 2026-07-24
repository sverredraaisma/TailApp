package com.tailapp.ble.protocol

import com.tailapp.model.LayerConfig
import com.tailapp.model.LedOutputState
import com.tailapp.model.LedState
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LedStateParser {

    private const val LAYER_SIZE = Protocol.LED_LAYER_SIZE // 8 header bytes + 8 * 4 param bytes
    private const val OUTPUT_BLOCK_SIZE = 5 // brightness, gamma, limit u16, power scale

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
            val opacity = buf.get().toInt() and 0xFF
            val params = List(8) { buf.float }

            LayerConfig(
                effectId = effectId,
                blendMode = blendMode,
                enabled = enabled,
                flipX = flipX, flipY = flipY,
                mirrorX = mirrorX, mirrorY = mirrorY,
                params = params,
                opacity = opacity
            )
        }

        // Output stage (protocol v5), appended after the layers. Optional so a
        // truncated or older payload still yields usable layer state.
        val output = if (buf.remaining() >= OUTPUT_BLOCK_SIZE) {
            LedOutputState(
                brightness = buf.get().toInt() and 0xFF,
                gammaEnabled = buf.get().toInt() != 0,
                currentLimitMa = buf.short.toInt() and 0xFFFF,
                lastPowerScale = buf.get().toInt() and 0xFF
            )
        } else {
            null
        }

        return LedState(
            numRings = numRings,
            ledsPerRing = ledsPerRing,
            layers = layers,
            output = output
        )
    }
}
