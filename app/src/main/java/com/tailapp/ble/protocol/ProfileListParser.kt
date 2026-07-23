package com.tailapp.ble.protocol

import com.tailapp.model.ProfileSlot

/**
 * Parses the FF08 read payload — one variable-length entry per profile slot:
 *
 * ```
 * [occupied u8][name_len u8][name: name_len bytes UTF-8]
 * ```
 *
 * Entries must be walked sequentially; the stride is not fixed. A truncated
 * payload yields the slots that could be read plus empty placeholders, so the
 * UI always has [Protocol.MAX_PROFILE_SLOTS] entries to render.
 */
object ProfileListParser {

    fun parse(data: ByteArray, slotCount: Int = Protocol.MAX_PROFILE_SLOTS): List<ProfileSlot> {
        val slots = ArrayList<ProfileSlot>(slotCount)
        var offset = 0
        for (index in 0 until slotCount) {
            if (offset + 2 > data.size) {
                slots.add(ProfileSlot(index, occupied = false, name = null))
                continue
            }
            val occupied = data[offset].toInt() != 0
            val nameLen = data[offset + 1].toInt() and 0xFF
            offset += 2
            val available = (data.size - offset).coerceAtLeast(0)
            val readable = minOf(nameLen, available)
            val name = if (readable > 0) {
                String(data, offset, readable, Charsets.UTF_8)
            } else {
                null
            }
            offset += readable
            slots.add(ProfileSlot(index, occupied, name))
        }
        return slots
    }
}
