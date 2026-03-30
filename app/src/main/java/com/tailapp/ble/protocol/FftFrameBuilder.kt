package com.tailapp.ble.protocol

object FftFrameBuilder {

    fun build(loudness: Byte, bins: ByteArray): ByteArray {
        val frame = ByteArray(2 + bins.size)
        frame[0] = loudness
        frame[1] = bins.size.toByte()
        bins.copyInto(frame, 2)
        return frame
    }
}
