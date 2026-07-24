package com.tailapp.ble.protocol

import com.tailapp.model.BehaviorStateConfig
import com.tailapp.model.BehaviorTriggerConfig
import com.tailapp.model.BehaviorTriggerSource
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The two fixed-size, little-endian behavior records the firmware stores and the
 * FF01 behavior commands carry verbatim (`behavior_state_config_t` and
 * `behavior_trigger_config_t` in TailFirmware `main/config/config_types.h`).
 *
 * One authority for the layout, because it is written in two directions: the
 * commands encode it, and the app's own copy of the table is persisted as the
 * same bytes — a saved table is a copy of this layout, exactly as a saved
 * firmware profile is.
 *
 * The reserved bytes are written as zero and ignored on the way back. They are
 * not padding the compiler chose: the structs are laid out so that every field
 * lands where an app can write it with no packing rules to reason about.
 */
object BehaviorRecords {

    /** `BEH_STATE_FLAG_ENABLED`. */
    const val STATE_FLAG_ENABLED = 0x01

    /** `BEH_TRIG_FLAG_BELOW` — fire when the level is *below* the threshold. */
    const val TRIGGER_FLAG_BELOW = 0x01

    /**
     * 40 bytes: `[pattern][flags][param_mask][fallback][min_dwell u16][timeout u16][params f32 x8]`.
     *
     * Requires a record the device would accept — [BehaviorStateConfig.validate]
     * is what the editor calls first, so anything reaching here is a bug rather
     * than user input.
     */
    fun encodeState(state: BehaviorStateConfig): ByteArray {
        val problems = state.validate()
        require(problems.isEmpty()) { "invalid behavior state: ${problems.joinToString("; ")}" }

        val buf = ByteBuffer.allocate(Protocol.BEHAVIOR_STATE_RECORD_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(state.patternId)
        buf.put(if (state.enabled) STATE_FLAG_ENABLED.toByte() else 0)
        buf.put(state.paramMask.toByte())
        buf.put(state.fallbackState.toByte())
        buf.putShort(state.minDwellMs.toShort())
        buf.putShort(state.timeoutMs.toShort())
        state.params.forEach { buf.putFloat(it) }
        return buf.array()
    }

    /** Reads a state record back. Null when there aren't 40 bytes at [offset]. */
    fun decodeState(data: ByteArray, offset: Int = 0): BehaviorStateConfig? {
        if (offset < 0 || data.size - offset < Protocol.BEHAVIOR_STATE_RECORD_SIZE) return null
        val buf = ByteBuffer.wrap(data, offset, Protocol.BEHAVIOR_STATE_RECORD_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        val patternId = buf.get()
        val flags = buf.get().toInt() and 0xFF
        val paramMask = buf.get().toInt() and 0xFF
        val fallback = buf.get().toInt() and 0xFF
        val minDwell = buf.short.toInt() and 0xFFFF
        val timeout = buf.short.toInt() and 0xFFFF
        val params = List(BehaviorStateConfig.PARAM_COUNT) { buf.float }
        return BehaviorStateConfig(
            patternId = patternId,
            enabled = flags and STATE_FLAG_ENABLED != 0,
            paramMask = paramMask,
            fallbackState = fallback,
            minDwellMs = minDwell,
            timeoutMs = timeout,
            params = params
        )
    }

    /**
     * 16 bytes: `[source][from_mask][to_state][count][flags][3 reserved]`
     * `[threshold f32][window u16][2 reserved]`.
     */
    fun encodeTrigger(trigger: BehaviorTriggerConfig): ByteArray {
        val problems = trigger.validate()
        require(problems.isEmpty()) { "invalid behavior trigger: ${problems.joinToString("; ")}" }

        val buf = ByteBuffer.allocate(Protocol.BEHAVIOR_TRIGGER_RECORD_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(trigger.source.code)
        buf.put(trigger.fromMask.toByte())
        buf.put(trigger.toState.toByte())
        buf.put(trigger.count.toByte())
        buf.put(if (trigger.fireBelowThreshold) TRIGGER_FLAG_BELOW.toByte() else 0)
        repeat(3) { buf.put(0) }
        buf.putFloat(trigger.threshold)
        buf.putShort(trigger.windowMs.toShort())
        buf.putShort(0)
        return buf.array()
    }

    /**
     * Reads a trigger record back. Null when there aren't 16 bytes at [offset],
     * or when the source byte names a rule this build does not know — a row
     * whose meaning is unknown must not be shown as some other rule.
     */
    fun decodeTrigger(data: ByteArray, offset: Int = 0): BehaviorTriggerConfig? {
        if (offset < 0 || data.size - offset < Protocol.BEHAVIOR_TRIGGER_RECORD_SIZE) return null
        val buf = ByteBuffer.wrap(data, offset, Protocol.BEHAVIOR_TRIGGER_RECORD_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        val source = BehaviorTriggerSource.fromCode(buf.get()) ?: return null
        val fromMask = buf.get().toInt() and 0xFF
        val toState = buf.get().toInt() and 0xFF
        val count = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        repeat(3) { buf.get() }
        val threshold = buf.float
        val windowMs = buf.short.toInt() and 0xFFFF
        return BehaviorTriggerConfig(
            source = source,
            fromMask = fromMask,
            toState = toState,
            count = count,
            fireBelowThreshold = flags and TRIGGER_FLAG_BELOW != 0,
            threshold = threshold,
            windowMs = windowMs
        )
    }
}
