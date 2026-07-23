package com.tailapp.ble.protocol

object SystemCommands {

    /**
     * `0x01` Set LED matrix. The firmware clamps `num_rings` to `MAX_LED_RINGS`
     * and then requires `len >= 2 + num_rings`, so sending more rings than the
     * device supports would be rejected as BAD_LENGTH — clamp here instead.
     */
    fun setLedMatrix(ledsPerRing: List<Byte>, maxRings: Int = 20): ByteArray {
        val rings = ledsPerRing.take(maxRings)
        val data = ByteArray(2 + rings.size)
        data[0] = 0x01
        data[1] = rings.size.toByte()
        rings.forEachIndexed { i, count -> data[2 + i] = count }
        return data
    }

    /** `0x02` Explicit system-info refresh request (payload is returned on the FF06 read). */
    fun getSystemInfo(): ByteArray = byteArrayOf(0x02)

    /** `0x03` Explicit capability refresh request (appended to the FF06 read). */
    fun getCapabilities(): ByteArray = byteArrayOf(0x03)
}
