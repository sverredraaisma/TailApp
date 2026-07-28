package com.tailapp.ble.protocol

import com.tailapp.model.ParamDescriptor
import com.tailapp.model.ParamDescriptorKind
import com.tailapp.model.ParamDescriptorSet
import com.tailapp.model.ParamDescriptorStatus
import com.tailapp.model.ParamUnit
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the FF0D parameter-descriptor read.
 *
 * ```
 * [kind u8][id u8][status u8][count u8]
 * then `count` fixed 26-byte records:
 *   [param_id u8][unit_code u8][name char[12], NUL-padded]
 *   [min f32 LE][max f32 LE][default f32 LE]
 * ```
 *
 * A characteristic of its own rather than more bytes on FF06 because the whole
 * set — eleven patterns and eighteen effects at up to eight parameters each — is
 * several kilobytes, past both the FF06 buffer and any single MTU. Which entity
 * it describes is chosen by writing [SystemCommands.selectDescriptors] to FF06,
 * so the app asks and then reads (or waits for the notify).
 *
 * **These descriptors carry each parameter's declared min/max, and that range is
 * currently the only guard on the value.** The firmware's `set_param` does not
 * clamp: an `LCMD_SET_EFFECT_PARAM` outside the declared bounds — BeatPulse's
 * `downbeat` is the standing example — is accepted and rendered as whatever it
 * produces, with no `OUT_OF_RANGE` to notice. So a UI that builds its controls
 * from these bounds is not being tidy, it is the enforcement.
 *
 * Fixed-size records rather than length-prefixed ones so a reader can seek to
 * descriptor N without walking the ones in front of it; a name longer than the
 * field is truncated device-side, not rejected.
 */
object ParamDescriptorParser {

    fun parse(data: ByteArray): ParamDescriptorSet? {
        if (data.size < Protocol.PARAM_DESC_HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val kind = ParamDescriptorKind.fromCode(buf.u8()) ?: return null
        val entityId = buf.u8()
        val status = ParamDescriptorStatus.fromCode(buf.u8()) ?: return null
        val count = buf.u8()

        // The device sends no records at all unless the status is OK, and caps
        // the count at PARAM_DESC_MAX_PARAMS. A payload claiming more records
        // than it carries is dropped rather than half-read: a truncated record
        // would read a range out of whatever follows it, and a wrong range is
        // worse than no range at the one place the range is the only guard.
        if (count > Protocol.PARAM_DESC_MAX_PARAMS) return null
        if (buf.remaining() < count * Protocol.PARAM_DESC_RECORD_SIZE) return null

        val params = List(count) {
            val paramId = buf.u8()
            val unit = ParamUnit.fromCode(buf.u8())
            val name = buf.fixedString(Protocol.PARAM_NAME_MAX)
            val min = buf.float
            val max = buf.float
            val default = buf.float
            ParamDescriptor(paramId, unit, name, min, max, default)
        }

        return ParamDescriptorSet(kind, entityId, status, params)
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xFF

    /**
     * Reads a fixed-width, NUL-padded name field and always advances by the full
     * width — the padding is what makes the record a constant size, so it has to
     * be consumed whether or not the name filled it.
     */
    private fun ByteBuffer.fixedString(width: Int): String {
        val raw = ByteArray(width).also { get(it) }
        val end = raw.indexOfFirst { it == 0.toByte() }.let { if (it < 0) width else it }
        return String(raw, 0, end, Charsets.UTF_8)
    }
}
