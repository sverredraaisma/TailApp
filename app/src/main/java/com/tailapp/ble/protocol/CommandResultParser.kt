package com.tailapp.ble.protocol

/** Parses the 3-byte FF09 notification: `[char_uuid_lo][command_id][result]`. */
object CommandResultParser {

    fun parse(data: ByteArray): CommandResult? {
        if (data.size < 3) return null
        return CommandResult(
            characteristicId = data[0],
            commandId = data[1],
            result = CommandResultCode.fromCode(data[2])
        )
    }
}

/** Parses the 1-byte FF07 notification. */
object SystemEventParser {

    fun parse(data: ByteArray): SystemEvent? {
        if (data.isEmpty()) return null
        return SystemEvent.fromCode(data[0])
    }
}
