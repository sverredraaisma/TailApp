package com.tailapp.ble.protocol

object SystemCommands {

    fun setLedMatrix(ledsPerRing: List<Byte>): ByteArray {
        val numRings = ledsPerRing.size
        val data = ByteArray(2 + numRings)
        data[0] = 0x01
        data[1] = numRings.toByte()
        ledsPerRing.forEachIndexed { i, count -> data[2 + i] = count }
        return data
    }
}
