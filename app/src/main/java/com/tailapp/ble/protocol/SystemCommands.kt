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

    /**
     * `0x04` Set the advertised device name (SYS-6).
     *
     * The firmware rejects an empty name and one over
     * [Protocol.MAX_DEVICE_NAME_LEN] bytes rather than repairing either: an empty
     * name advertises nothing at all, and a truncated one advertises a name the
     * user did not choose. This mirrors that — [deviceNameError] is what the UI
     * calls first, so the limit is explained where it can be, instead of coming
     * back as an FF09 `OUT_OF_RANGE` a second later.
     */
    fun setDeviceName(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(deviceNameError(name) == null) {
            "device name must be 1..${Protocol.MAX_DEVICE_NAME_LEN} UTF-8 bytes, was ${bytes.size}"
        }
        return byteArrayOf(0x04) + bytes
    }

    /** Why the device would refuse [name], or null if it would accept it. */
    fun deviceNameError(name: String): DeviceNameError? {
        val length = name.toByteArray(Charsets.UTF_8).size
        return when {
            length == 0 -> DeviceNameError.EMPTY
            length > Protocol.MAX_DEVICE_NAME_LEN -> DeviceNameError.TOO_LONG
            else -> null
        }
    }

    /**
     * `0x05` Forget one bonded peer, by its index in the FF06 bond list, or
     * every bond at [Protocol.BOND_INDEX_ALL].
     *
     * An index past the end of the device's list is answered `OUT_OF_RANGE`, not
     * silently accepted — the app's copy of the list can be stale, and a false
     * success would leave the user believing they unpaired a phone that is still
     * bonded.
     */
    fun forgetBond(index: Byte): ByteArray = byteArrayOf(0x05, index)

    /** `0x05` with [Protocol.BOND_INDEX_ALL] — drops every bond the device holds. */
    fun forgetAllBonds(): ByteArray = forgetBond(Protocol.BOND_INDEX_ALL)

    /** `0x06` Explicit bond-list refresh request (appended to the FF06 read). */
    fun listBonds(): ByteArray = byteArrayOf(0x06)
}

/** What is wrong with a proposed device name, in the device's own terms. */
enum class DeviceNameError {
    EMPTY,
    TOO_LONG;

    val message: String
        get() = when (this) {
            EMPTY -> "A name is required — an empty one would advertise nothing at all."
            TOO_LONG -> "At most ${Protocol.MAX_DEVICE_NAME_LEN} bytes; longer names are refused, not shortened."
        }
}
