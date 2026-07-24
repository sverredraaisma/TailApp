package com.tailapp.ble.protocol

import com.tailapp.model.FirmwareVersion

/**
 * What an ESP application image says about itself.
 *
 * [version] is null when the descriptor's version string has no leading
 * `major.minor.patch`. That is "unknown", never 0.0.0 — the device makes the
 * same distinction, and refusing an image over an unparsable version string
 * would be worse than installing it.
 */
data class FirmwareImageInfo(
    val projectName: String,
    val versionText: String,
    val version: FirmwareVersion?,
    val sizeBytes: Int
)

/**
 * Reads the header an ESP application image starts with, so the app can say what
 * a picked file *is* before spending a transfer on it.
 *
 * A mirror of `OtaManager::validate_header` in TailFirmware
 * `main/system/ota_manager.cpp`, including the offsets: the device buffers
 * exactly [Protocol.OTA_IMAGE_HEADER_BYTES] bytes in RAM and makes its
 * accept/refuse decision from them before it erases anything. Checking the same
 * bytes here turns three of the device's rejections — not an image, somebody
 * else's firmware, the version already running — into an answer the user gets
 * immediately instead of one that arrives 288 bytes into an upload.
 *
 * The offsets are fixed by the bootloader's on-flash format rather than by an
 * SDK version, which is what makes mirroring them safe.
 */
object FirmwareImage {

    /** `esp_image_header_t.magic`. */
    private const val IMAGE_MAGIC = 0xE9

    /** Image header (24) + first segment header (8). */
    private const val APP_DESC_OFFSET = 32

    private const val APP_DESC_MAGIC = 0xABCD5432L

    /** `char version[32]` inside the descriptor. */
    private const val DESC_VERSION_OFFSET = APP_DESC_OFFSET + 16

    /** `char project_name[32]`, immediately after the 32-byte time and date fields. */
    private const val DESC_PROJECT_OFFSET = APP_DESC_OFFSET + 48

    private const val DESC_FIELD_LEN = 32

    /**
     * The project name this app's tails are built under (`project()` in
     * TailFirmware's top-level `CMakeLists.txt`). Used only to warn before an
     * upload; the device compares against its *own* running image's descriptor,
     * which is the check that actually decides.
     */
    const val EXPECTED_PROJECT = "TailFirmware"

    /** Null when [bytes] is not an ESP application image at all. */
    fun describe(bytes: ByteArray): FirmwareImageInfo? {
        if (bytes.size < Protocol.OTA_IMAGE_HEADER_BYTES) return null
        if ((bytes[0].toInt() and 0xFF) != IMAGE_MAGIC) return null
        if (readU32(bytes, APP_DESC_OFFSET) != APP_DESC_MAGIC) return null

        val versionText = readFixedString(bytes, DESC_VERSION_OFFSET)
        return FirmwareImageInfo(
            projectName = readFixedString(bytes, DESC_PROJECT_OFFSET),
            versionText = versionText,
            version = FirmwareVersion.parse(versionText),
            sizeBytes = bytes.size
        )
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    /**
     * The descriptor's strings are fixed-width and only NUL-terminated when they
     * are shorter than the field, so a full-width field has no terminator to
     * stop at.
     */
    private fun readFixedString(bytes: ByteArray, offset: Int): String {
        var end = offset
        val limit = offset + DESC_FIELD_LEN
        while (end < limit && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }
}
