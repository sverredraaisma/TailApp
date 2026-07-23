package com.tailapp.ble.protocol

object ProfileCommands {
    fun saveProfile(slot: Byte): ByteArray = byteArrayOf(0x01, slot)
    fun loadProfile(slot: Byte): ByteArray = byteArrayOf(0x02, slot)
    fun listProfiles(): ByteArray = byteArrayOf(0x03)
    fun deleteProfile(slot: Byte): ByteArray = byteArrayOf(0x04, slot)

    /**
     * `0x05` Rename a profile slot (protocol v1).
     *
     * The name is sent as raw UTF-8 with no null terminator, truncated to
     * [Protocol.MAX_PROFILE_NAME_LEN] bytes without splitting a multi-byte
     * character.
     */
    fun renameProfile(slot: Byte, name: String): ByteArray {
        val bytes = truncateUtf8(name, Protocol.MAX_PROFILE_NAME_LEN)
        val data = ByteArray(2 + bytes.size)
        data[0] = 0x05
        data[1] = slot
        bytes.copyInto(data, 2)
        return data
    }

    /** Encodes [text] as UTF-8, dropping trailing characters until it fits [maxBytes]. */
    internal fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        var candidate = text
        while (true) {
            val bytes = candidate.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return bytes
            // Drop a whole code point so we never emit a split surrogate pair.
            val lastCodePointStart = candidate.offsetByCodePoints(candidate.length, -1)
            candidate = candidate.substring(0, lastCodePointStart)
        }
    }
}
