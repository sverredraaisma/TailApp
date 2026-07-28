package com.tailapp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the editor refuses before the device has to.
 *
 * Every rejection here corresponds to something `BehaviorEngine::normalize`
 * would quietly repair — a fallback pointing past the table, a negative
 * threshold, a row aimed at a state that does not exist. Repaired silently, the
 * screen keeps showing what the user typed while the tail runs something else,
 * which is worse than a refusal.
 */
class BehaviorValidationTest {

    private val state = BehaviorStateConfig(patternId = MotionPattern.WAGGING.id)
    private val trigger = BehaviorTriggerConfig(
        source = BehaviorTriggerSource.LOUDNESS,
        toState = 1,
        threshold = 0.5f
    )

    @Test
    fun `a valid record has nothing to say`() {
        assertEquals(emptyList<String>(), state.validate())
        assertEquals(emptyList<String>(), trigger.validate())
    }

    @Test
    fun `a fallback past the end of the table is rejected`() {
        assertEquals(
            listOf("the fallback state must be 0-7"),
            state.copy(fallbackState = 8).validate()
        )
        assertEquals(
            listOf("the fallback state must be 0-7"),
            state.copy(fallbackState = -1).validate()
        )
    }

    @Test
    fun `dwell and timeout are u16 fields`() {
        assertEquals(
            listOf("the minimum dwell is a u16 (0-65535 ms)"),
            state.copy(minDwellMs = 65536).validate()
        )
        assertEquals(
            listOf("the timeout is a u16 (0-65535 ms)"),
            state.copy(timeoutMs = 65536).validate()
        )
        assertTrue(state.copy(minDwellMs = 65535, timeoutMs = 65535).validate().isEmpty())
    }

    @Test
    fun `a state carries exactly eight parameters`() {
        assertEquals(
            listOf("a state carries exactly 8 parameters"),
            state.copy(params = List(4) { 0f }).validate()
        )
    }

    @Test
    fun `a non-finite parameter is rejected rather than written to the wire`() {
        // NaN encodes perfectly well and arrives as a pattern parameter, where
        // it propagates into an angle nothing recovers from.
        assertEquals(
            listOf("parameter 1 is not a finite number"),
            state.copy(params = listOf(0f, Float.NaN, 0f, 0f, 0f, 0f, 0f, 0f)).validate()
        )
    }

    @Test
    fun `the parameter mask is one byte`() {
        assertEquals(
            listOf("the parameter mask is one byte (0-255)"),
            state.copy(paramMask = 256).validate()
        )
    }

    @Test
    fun `a trigger's target must exist in the device's table`() {
        assertEquals(
            listOf("the target state must be 0-7"),
            trigger.copy(toState = 8).validate()
        )
    }

    @Test
    fun `a negative or non-finite threshold is rejected`() {
        // The device clamps a negative threshold to zero, which turns "never"
        // into "always" — a level compared >= 0 is satisfied by silence.
        assertEquals(
            listOf("the threshold must be zero or more"),
            trigger.copy(threshold = -0.1f).validate()
        )
        assertEquals(
            listOf("the threshold must be zero or more"),
            trigger.copy(threshold = Float.NaN).validate()
        )
    }

    @Test
    fun `count, mask and window are byte and u16 fields`() {
        assertEquals(listOf("the tap count is one byte (0-255)"), trigger.copy(count = 256).validate())
        assertEquals(
            listOf("the from-state mask is one byte (0-255)"),
            trigger.copy(fromMask = 256).validate()
        )
        assertEquals(
            listOf("the window is a u16 (0-65535 ms)"),
            trigger.copy(windowMs = 65536).validate()
        )
    }

    @Test
    fun `a mask of zero fires from every state`() {
        assertTrue(trigger.copy(fromMask = 0).firesFrom(0))
        assertTrue(trigger.copy(fromMask = 0).firesFrom(7))
        assertTrue(trigger.copy(fromMask = 0x11).firesFrom(4))
        assertFalse(trigger.copy(fromMask = 0x11).firesFrom(1))
    }

    @Test
    fun `an override bit is what distinguishes zero from the pattern default`() {
        val overridden = state.withOverride(2, true).withParam(2, 0f)
        assertTrue(overridden.overrides(2))
        assertFalse(overridden.overrides(3))
        assertFalse(overridden.withOverride(2, false).overrides(2))
    }
}

/** Cross-row checks a single record cannot make. */
class BehaviorTableProblemsTest {

    private val table = BehaviorTable(
        states = listOf(
            BehaviorStateConfig(patternId = MotionPattern.IDLE_SWAY.id, name = "Idle"),
            BehaviorStateConfig(patternId = MotionPattern.WAGGING.id, name = "Happy")
        ),
        triggers = listOf(
            BehaviorTriggerConfig(source = BehaviorTriggerSource.TAP_BASE, toState = 1, count = 1)
        )
    )

    @Test
    fun `a well-formed table has no problems`() {
        assertEquals(emptyList<String>(), table.problems())
        assertEquals(emptyList<String>(), BehaviorTable.FIRMWARE_DEFAULT.problems())
    }

    @Test
    fun `a row aimed at a state that does not exist is called out`() {
        val broken = table.withTrigger(0, table.triggers[0].copy(toState = 5))
        assertEquals(listOf("Trigger 0: it goes to a state that does not exist"), broken.problems())
    }

    @Test
    fun `a row aimed at a disabled state can never fire`() {
        val broken = table.withState(1, table.states[1].copy(enabled = false))
        assertEquals(
            listOf("Trigger 0: its target state is disabled, so the row never fires"),
            broken.problems()
        )
    }

    @Test
    fun `a state that times out before its own dwell can only end one way`() {
        // The engine checks the timeout before the dwell floor, so no trigger
        // ever gets to leave this state.
        val broken = table.withState(0, table.states[0].copy(minDwellMs = 2000, timeoutMs = 500))
        assertEquals(
            listOf("State 0: it times out before its minimum dwell, so no trigger can leave it"),
            broken.problems()
        )
    }

    @Test
    fun `an idle state outside the table is called out`() {
        assertEquals(listOf("the idle state does not exist"), table.copy(idleState = 4).problems())
    }

    @Test
    fun `a fallback outside the table is called out separately from the record check`() {
        // 3 is a legal field value (the device holds eight states) but not a
        // state this table has, so only the table-level check sees it.
        val broken = table.withState(0, table.states[0].copy(fallbackState = 3))
        assertTrue(broken.states[0].validate().isEmpty())
        assertEquals(listOf("State 0: its fallback state does not exist"), broken.problems())
    }
}

/**
 * The factory table, as data.
 *
 * The device publishes no read for its own table, so this mirror is the only
 * thing the editor can start from — a value that drifted from
 * `BehaviorEngine::default_config` would present the user with a table the tail
 * is not running.
 */
class FirmwareDefaultTableTest {

    private val table = BehaviorTable.FIRMWARE_DEFAULT

    @Test
    fun `six moods and nine rules, engine off out of the box`() {
        assertEquals(6, table.states.size)
        assertEquals(9, table.triggers.size)
        assertEquals(0, table.idleState)
        assertFalse(table.engineEnabled)
    }

    @Test
    fun `every state names a pattern this app knows`() {
        table.states.forEachIndexed { i, state ->
            assertTrue("state $i has an unknown pattern", state.pattern != null)
            assertTrue("state $i is disabled", state.enabled)
        }
    }

    @Test
    fun `every overridden parameter is inside its pattern's declared range`() {
        // The same claim CompositionLibraryTest makes about the built-in stacks:
        // a value outside the range would show the user a slider pinned at an
        // end while the device ran something else.
        table.states.forEachIndexed { i, state ->
            val pattern = requireNotNull(state.pattern)
            // Labelled because the two loops are both forEachIndexed, so a bare
            // return@forEachIndexed is ambiguous to a reader even where the
            // compiler picks the inner one.
            state.params.forEachIndexed param@{ p, value ->
                if (!state.overrides(p)) return@param
                val meta = pattern.params.find { it.id == p }
                requireNotNull(meta) { "state $i overrides parameter $p, which ${pattern.displayName} does not have" }
                assertTrue(
                    "state $i parameter $p = $value is outside ${meta.min}..${meta.max}",
                    value >= meta.min && value <= meta.max
                )
            }
        }
    }

    @Test
    fun `startled falls back to alert rather than idle`() {
        // Whatever grabbed the tail is still there; dropping straight to idle
        // would read as the tail having forgotten about it.
        assertEquals(1, table.states[5].fallbackState)
        assertEquals(MotionPattern.SHIVER.id, table.states[5].patternId)
    }

    @Test
    fun `the sharp reactions sit above the slow ones`() {
        // Rows are evaluated in index order and the first satisfied one wins, so
        // the ordering is the priority. A grab and a drop must outrank a
        // two-second loudness rule.
        assertEquals(BehaviorTriggerSource.TAP_TIP, table.triggers[0].source)
        assertEquals(BehaviorTriggerSource.DROP, table.triggers[1].source)
        // Two pats above the single pat, or the single one would always win and
        // the second pat would mean nothing.
        assertEquals(2, table.triggers[2].count)
        assertEquals(1, table.triggers[3].count)
        assertEquals(BehaviorTriggerSource.QUIET_TIME, table.triggers.last().source)
        assertEquals(120f, table.triggers.last().threshold, 0f)
    }
}
