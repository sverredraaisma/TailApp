package com.tailapp.ble.protocol

/**
 * Parses the FF09 payload: `[char_uuid_lo][command_id][result]`, plus a
 * `[seq]` byte on protocol v5 and later.
 *
 * The sequence byte is read when present rather than required, so the parser
 * works against both older firmware and any future firmware that appends more.
 */
object CommandResultParser {

    fun parse(data: ByteArray): CommandResult? {
        if (data.size < 3) return null
        return CommandResult(
            characteristicId = data[0],
            commandId = data[1],
            result = CommandResultCode.fromCode(data[2]),
            sequence = if (data.size >= 4) data[3].toInt() and 0xFF else null
        )
    }
}

/**
 * Parses FF07. A notification carries a single event byte; a *read* returns the
 * device's recent-event ring as `[count][event]...` (protocol v5), which is how
 * an app recovers events that arrived before it subscribed or that were dropped.
 */
object SystemEventParser {

    fun parse(data: ByteArray): SystemEvent? {
        if (data.isEmpty()) return null
        return SystemEvent.fromCode(data[0])
    }

    /**
     * Parses the readable event ring, oldest first. Returns an empty list for a
     * payload that is not a ring — including a bare 1-byte notification, whose
     * leading byte is an event code and not a count.
     */
    fun parseLog(data: ByteArray): List<SystemEvent> {
        if (data.size < 2) return emptyList()
        val count = data[0].toInt() and 0xFF
        if (count == 0 || data.size < 1 + count) return emptyList()
        return (0 until count).mapNotNull { SystemEvent.fromCode(data[1 + it]) }
    }
}
