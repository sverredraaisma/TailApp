package com.tailapp.repository

import android.content.SharedPreferences
import com.tailapp.ble.protocol.BehaviorRecords
import com.tailapp.ble.protocol.Protocol
import com.tailapp.model.BehaviorStateConfig
import com.tailapp.model.BehaviorTable
import com.tailapp.model.BehaviorTriggerConfig
import java.util.Base64

/**
 * The app's copy of the behavior table it last wrote to a device.
 *
 * The firmware exposes no read for its table — states and triggers are
 * write-only over BLE — so an editor that rebuilt its view from the device would
 * have nothing to rebuild it from, and one that forgot on every screen close
 * would show the factory defaults while the tail ran something else. This keeps
 * the last-written table so the two agree.
 *
 * It is stored as the same fixed-size records the wire carries, for the reason
 * `config_types.h` gives for not reordering those structs: a saved table is a
 * copy of that layout, and one encoder is easier to keep honest than two. State
 * names ride alongside as plain text, because the device has nowhere to put them.
 */
class BehaviorTableStore(private val prefs: SharedPreferences) {

    fun load(): BehaviorTable {
        val encoded = prefs.getString(KEY_TABLE, null) ?: return BehaviorTable.FIRMWARE_DEFAULT
        val blob = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return BehaviorTable.FIRMWARE_DEFAULT
        }
        return decode(blob, prefs.getString(KEY_NAMES, null).orEmpty())
            ?: BehaviorTable.FIRMWARE_DEFAULT
    }

    fun save(table: BehaviorTable) {
        prefs.edit()
            .putString(KEY_TABLE, Base64.getEncoder().encodeToString(encode(table)))
            .putString(KEY_NAMES, table.states.joinToString(NAME_SEPARATOR) { it.name })
            .apply()
    }

    /** Drops the stored table; [load] then reports the firmware's factory one. */
    fun clear() {
        prefs.edit().remove(KEY_TABLE).remove(KEY_NAMES).apply()
    }

    private fun encode(table: BehaviorTable): ByteArray {
        val header = byteArrayOf(
            FORMAT_VERSION,
            table.states.size.toByte(),
            table.triggers.size.toByte(),
            table.idleState.toByte(),
            if (table.engineEnabled) 1 else 0
        )
        val states = table.states.fold(ByteArray(0)) { acc, s -> acc + BehaviorRecords.encodeState(s) }
        val triggers = table.triggers.fold(ByteArray(0)) { acc, t ->
            acc + BehaviorRecords.encodeTrigger(t)
        }
        return header + states + triggers
    }

    private fun decode(blob: ByteArray, names: String): BehaviorTable? {
        if (blob.size < HEADER_SIZE || blob[0] != FORMAT_VERSION) return null
        val stateCount = blob[1].toInt() and 0xFF
        val triggerCount = blob[2].toInt() and 0xFF
        if (stateCount > Protocol.MAX_BEHAVIOR_STATES) return null
        if (triggerCount > Protocol.MAX_BEHAVIOR_TRIGGERS) return null

        val nameList = if (names.isEmpty()) emptyList() else names.split(NAME_SEPARATOR)
        var offset = HEADER_SIZE
        val states = ArrayList<BehaviorStateConfig>(stateCount)
        repeat(stateCount) { i ->
            val state = BehaviorRecords.decodeState(blob, offset) ?: return null
            states.add(state.copy(name = nameList.getOrElse(i) { "" }))
            offset += Protocol.BEHAVIOR_STATE_RECORD_SIZE
        }
        val triggers = ArrayList<BehaviorTriggerConfig>(triggerCount)
        repeat(triggerCount) {
            val trigger = BehaviorRecords.decodeTrigger(blob, offset) ?: return null
            triggers.add(trigger)
            offset += Protocol.BEHAVIOR_TRIGGER_RECORD_SIZE
        }
        return BehaviorTable(
            states = states,
            triggers = triggers,
            idleState = blob[3].toInt() and 0xFF,
            engineEnabled = blob[4].toInt() != 0
        )
    }

    private companion object {
        const val KEY_TABLE = "behavior_table"
        const val KEY_NAMES = "behavior_state_names"

        /** Bumped only if the *container* changes; the records are the wire's. */
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_SIZE = 5

        /** Not legal inside a name typed into the editor, unlike a comma. */
        const val NAME_SEPARATOR = "\n"
    }
}
