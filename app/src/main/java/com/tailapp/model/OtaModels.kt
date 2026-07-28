package com.tailapp.model

import com.tailapp.ble.protocol.CommandResultCode

/**
 * A `major.minor.patch` triplet, as the FF06 OTA block and an ESP application
 * image's app descriptor both carry it.
 */
data class FirmwareVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<FirmwareVersion> {

    override fun toString(): String = "$major.$minor.$patch"

    override fun compareTo(other: FirmwareVersion): Int = when {
        major != other.major -> major - other.major
        minor != other.minor -> minor - other.minor
        else -> patch - other.patch
    }

    companion object {
        /**
         * Parses the leading triplet of an app-descriptor version string, a port
         * of `ota_version_parse` in TailFirmware `main/system/ota_flash.h`.
         *
         * The two have to agree exactly: the device parses the *image's* string
         * with that function and refuses an image whose triplet matches the
         * running one, so a stricter reader here would offer an update the tail
         * then rejects, and a looser one would declare a version the tail never
         * derives. Anything `git describe` produces is accepted — `1.2.3`,
         * `v1.2.3`, `1.2.3-4-gabc1234` — and a field wider than the wire format
         * saturates at 255 rather than wrapping.
         *
         * Null means "no leading triplet", which is not 0.0.0: the firmware
         * treats an unparsable version as unknown rather than as a version, and
         * refusing an image over its version string would be worse than
         * installing it.
         */
        fun parse(text: String): FirmwareVersion? {
            var i = 0
            if (i < text.length && (text[i] == 'v' || text[i] == 'V')) i++
            val parts = IntArray(3)
            for (part in 0 until 3) {
                if (i >= text.length || text[i] !in '0'..'9') return null
                var value = 0
                while (i < text.length && text[i] in '0'..'9') {
                    value = value * 10 + (text[i] - '0')
                    if (value > 255) value = 255
                    i++
                }
                parts[part] = value
                if (part < 2) {
                    if (i >= text.length || text[i] != '.') return null
                    i++
                }
            }
            return FirmwareVersion(parts[0], parts[1], parts[2])
        }
    }
}

/**
 * The FF06 OTA version/rollback block (SYS-2) — what an update screen is built on.
 *
 * [running] comes from the running image's own app descriptor rather than the
 * build-time firmware version at the front of the FF06 read. After an update the
 * two must agree, and this is the one that cannot be stale, so an offered image
 * is compared against this.
 */
data class OtaInfo(
    val running: FirmwareVersion,

    /**
     * True while the running image has not been confirmed: a reset right now
     * boots the *previous* one. The device confirms by itself after it has held
     * a connection long enough — see
     * [com.tailapp.ble.protocol.Protocol.OTA_CONFIRM_DWELL_MS] — so the app's
     * only job is to stay connected and say so.
     */
    val pendingVerify: Boolean,

    /**
     * The other slot's version, or null when it holds nothing describable. It
     * becomes valid the moment a finalize succeeds, which is how "installed —
     * restart to apply" is shown without the app having to remember that it just
     * uploaded something.
     */
    val other: FirmwareVersion?,

    /**
     * True when bit 1 of the block's flags byte is set: the device could not read
     * [running] from a real app descriptor, so the 0.0.0 it published is a
     * *placeholder*, not a claim about what is installed.
     *
     * It has to be distinguishable, because the two look identical on the wire
     * and lead opposite ways. A genuine 0.0.0 makes any offered image an upgrade;
     * an unreadable one means the app knows nothing and should say so rather than
     * comparing against a number the device never derived. In practice this is
     * never seen for a running image — a device always knows its own build's
     * version string — which is exactly why a placeholder rendered as a version
     * would be believed.
     */
    val runningVersionUnknown: Boolean = false
) {
    val otherValid: Boolean get() = other != null

    /**
     * The running version, or null when the device flagged it as a placeholder.
     * What a version comparison should use — [running] is what to show only once
     * it is known to be real.
     */
    val knownRunning: FirmwareVersion? get() = running.takeUnless { runningVersionUnknown }
}

/** Transfer state, the fifth byte of the FF0E status echo. */
enum class OtaTransferState(val code: Int) {
    IDLE(0x00),
    RECEIVING(0x01),
    READY(0x02),
    ERROR(0x03);

    companion object {
        fun fromCode(code: Int): OtaTransferState? = entries.find { it.code == code }
    }
}

/**
 * The FF0E status echo: `[accepted u32 LE][state u8][result u8]`.
 *
 * [accepted] is both the progress figure and the only offset the device will
 * take next — flash is written forward and never rewound, so a chunk at any
 * other offset is discarded and answered with one of these.
 */
data class OtaStatus(
    val accepted: Int,
    val state: OtaTransferState,
    val result: CommandResultCode
) {
    /** True while a transfer is armed and the device is expecting more bytes. */
    val isReceiving: Boolean get() = state == OtaTransferState.RECEIVING

    /** True once an image is verified and marked bootable — it runs at the next reset. */
    val isInstalled: Boolean get() = state == OtaTransferState.READY
}
