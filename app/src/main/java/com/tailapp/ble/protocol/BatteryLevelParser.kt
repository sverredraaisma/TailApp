package com.tailapp.ble.protocol

/**
 * Parses the standard Battery Level characteristic (0x2A19): one byte, 0-100.
 *
 * Anything outside that range is the device saying it does not know — the
 * firmware's `BATTERY_PERCENT_UNKNOWN` is `0xFF` for exactly that reason, chosen
 * to sit outside the adopted characteristic's range. Null therefore means
 * "unknown", never "empty": a board whose sense divider is not populated must
 * not render as a flat pack.
 */
object BatteryLevelParser {

    /** @return 0..100, or null when the device reports no usable level. */
    fun parse(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val percent = data[0].toInt() and 0xFF
        return percent.takeIf { it <= 100 }
    }
}
