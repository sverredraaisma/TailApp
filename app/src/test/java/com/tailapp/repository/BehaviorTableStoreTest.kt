package com.tailapp.repository

import com.tailapp.model.BehaviorStateConfig
import com.tailapp.model.BehaviorTable
import com.tailapp.model.BehaviorTriggerConfig
import com.tailapp.model.BehaviorTriggerSource
import com.tailapp.model.MotionPattern
import com.tailapp.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app's copy of a write-only table. Everything here is a round trip, because
 * a store that loses a field is indistinguishable from a device that never got
 * it — and there is no read to settle the argument.
 */
class BehaviorTableStoreTest {

    private val prefs = FakeSharedPreferences()
    private val store = BehaviorTableStore(prefs)

    @Test
    fun `an empty store reports the firmware's factory table`() {
        assertEquals(BehaviorTable.FIRMWARE_DEFAULT, store.load())
    }

    @Test
    fun `a saved table comes back field for field, names included`() {
        val table = BehaviorTable(
            states = listOf(
                BehaviorStateConfig(
                    patternId = MotionPattern.CIRCLE.id,
                    enabled = false,
                    paramMask = 0x05,
                    fallbackState = 1,
                    minDwellMs = 1234,
                    timeoutMs = 5678,
                    params = listOf(0.5f, 1f, 2f, 3f, 4f, 5f, 6f, 7f),
                    name = "Spin"
                ),
                BehaviorStateConfig(patternId = MotionPattern.LOOSE.id, name = "Flop")
            ),
            triggers = listOf(
                BehaviorTriggerConfig(
                    source = BehaviorTriggerSource.TEMPO,
                    fromMask = 0x02,
                    toState = 0,
                    fireBelowThreshold = true,
                    threshold = 128f,
                    windowMs = 2500
                )
            ),
            idleState = 1,
            engineEnabled = true
        )

        store.save(table)
        assertEquals(table, store.load())
    }

    @Test
    fun `clearing returns the editor to the factory table`() {
        store.save(BehaviorTable.FIRMWARE_DEFAULT.copy(engineEnabled = true))
        store.clear()
        assertEquals(BehaviorTable.FIRMWARE_DEFAULT, store.load())
    }

    @Test
    fun `an unreadable blob falls back instead of throwing`() {
        // Prefs survive an app downgrade; a table this build cannot parse must
        // not take the screen down with it.
        prefs.edit().putString("behavior_table", "not base64 at all!!").apply()
        assertEquals(BehaviorTable.FIRMWARE_DEFAULT, store.load())
    }

    @Test
    fun `a truncated blob falls back instead of returning half a table`() {
        store.save(BehaviorTable.FIRMWARE_DEFAULT)
        val saved = requireNotNull(prefs.getString("behavior_table", null))
        prefs.edit().putString("behavior_table", saved.substring(0, saved.length / 2)).apply()
        assertEquals(BehaviorTable.FIRMWARE_DEFAULT, store.load())
    }
}
