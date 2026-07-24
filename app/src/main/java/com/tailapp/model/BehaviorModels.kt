package com.tailapp.model

import com.tailapp.ble.protocol.Protocol

/**
 * The behavior engine (firmware MOT-6): a state machine that sits above the
 * pattern layer. Each state is a mood naming a pattern and the parameters it
 * runs with; trigger rows connect them.
 *
 * These types mirror `behavior_state_config_t` / `behavior_trigger_config_t` in
 * TailFirmware `main/config/config_types.h` field for field, because the FF01
 * payloads of `MCMD_SET_BEHAVIOR_CFG` / `MCMD_SET_BEHAVIOR_TRIGGER` are those
 * structs byte-for-byte.
 */

/**
 * What a trigger row watches (`BEH_TRIG_*`).
 *
 * [isEvent] rows fire on the event itself, so their threshold means nothing and
 * their window is the tap-counting window instead of a hold time. Level rows
 * fire at `>= threshold`, or `< threshold` with
 * [BehaviorTriggerConfig.fireBelowThreshold], once the condition has held
 * continuously for `windowMs`.
 */
enum class BehaviorTriggerSource(
    val code: Byte,
    val displayName: String,
    val isEvent: Boolean = false,
    /** What [BehaviorTriggerConfig.threshold] is measured in, for the editor. */
    val thresholdUnit: String = "",
    val thresholdRange: ClosedFloatingPointRange<Float> = 0f..1f,
    /**
     * True when the device only has this signal while the phone is streaming
     * FF05 — it has no microphone, so these rows go dead the moment the app
     * stops streaming rather than reading as zero.
     */
    val needsAudioStream: Boolean = false
) {
    NONE(0x00, "Disabled"),
    TAP_BASE(0x01, "Tap on base", isEvent = true),
    TAP_TIP(0x02, "Tap on tip", isEvent = true),
    LOUDNESS(0x03, "Loudness", thresholdUnit = "", needsAudioStream = true),
    BEAT_ACTIVITY(0x04, "Beat activity", thresholdUnit = "", needsAudioStream = true),
    TEMPO(0x05, "Tempo", thresholdUnit = "BPM", thresholdRange = 0f..220f, needsAudioStream = true),
    DROP(0x06, "Drop", isEvent = true, needsAudioStream = true),
    HANDLING(0x07, "Being handled"),
    QUIET_TIME(0x08, "Quiet time", thresholdUnit = "s", thresholdRange = 0f..300f),
    STATE_ELAPSED(0x09, "Time in state", thresholdUnit = "s", thresholdRange = 0f..300f);

    companion object {
        /** `BEH_TRIG_MAX` — a reason code at or below this is the source that fired. */
        const val MAX_CODE = 0x09

        fun fromCode(code: Byte): BehaviorTriggerSource? = entries.find { it.code == code }
    }
}

/**
 * Why the engine last changed state, from the FF02 behavior block.
 *
 * The named `BEH_REASON_*` codes start at `0x80` precisely so that everything
 * below them can be the [BehaviorTriggerSource] that fired — one byte answers
 * both "was this a rule or the machinery" and "which rule".
 */
data class BehaviorReason(val code: Int) {
    /** The rule that fired, or null when the transition came from the machinery. */
    val triggerSource: BehaviorTriggerSource?
        get() = if (code in 1..BehaviorTriggerSource.MAX_CODE) {
            BehaviorTriggerSource.fromCode(code.toByte())
        } else {
            null
        }

    val description: String
        get() = when (code) {
            NONE -> "nothing has caused a transition yet"
            TIMEOUT -> "the state's own timeout elapsed"
            FORCED -> "forced from this app"
            DISCONNECT -> "the app disconnected — fell back to idle"
            ENABLED -> "the engine was switched on"
            else -> triggerSource?.let { "trigger: ${it.displayName}" }
                ?: "unknown reason (0x%02X)".format(code)
        }

    companion object {
        const val NONE = 0x00
        const val TIMEOUT = 0x80
        const val FORCED = 0x81
        const val DISCONNECT = 0x82
        const val ENABLED = 0x83
    }
}

/**
 * The live behavior block appended to the FF02 motion state (4 bytes).
 *
 * Appended, so firmware that predates MOT-6 simply sends the 77-byte payload it
 * always did and this stays null. Note that [MotionState.activePatternId] is
 * still the *selected* pattern — what runs the moment the engine is switched off
 * — while [drivingPatternId] is what is actually on the motors.
 */
data class BehaviorRuntime(
    /** The active state, or null when the engine is off (`0xFF`). */
    val stateIndex: Int?,
    val reason: BehaviorReason,
    val flags: Int,
    /** The pattern the engine installed, or null when it is not driving (`0xFF`). */
    val drivingPatternId: Byte?
) {
    val engineEnabled: Boolean get() = flags and FLAG_ENABLED != 0

    /** FF0B target streaming outranks the engine for as long as frames keep coming. */
    val streamHeld: Boolean get() = flags and FLAG_STREAM_HELD != 0

    /** A stall latched every motor off; nothing drives until they are re-enabled. */
    val stallHeld: Boolean get() = flags and FLAG_STALL_HELD != 0

    /**
     * True while a higher precedence level holds the tail. The engine keeps its
     * state but observes nothing, so it neither moves the tail nor builds up a
     * mood about a room it is not performing in.
     */
    val suspended: Boolean get() = streamHeld || stallHeld

    val drivingPattern: MotionPattern? get() = drivingPatternId?.let { MotionPattern.fromId(it) }

    companion object {
        const val FLAG_ENABLED = 0x01
        const val FLAG_STREAM_HELD = 0x02
        const val FLAG_STALL_HELD = 0x04

        /** `0xFF` in the state and pattern bytes: "off" and "not driving". */
        const val ABSENT = 0xFF
    }
}

/**
 * One mood: the pattern it runs, the parameters it runs with, and the floor and
 * ceiling on how long it lasts.
 *
 * [paramMask] bit *i* means "apply [params]`[i]`". A clear bit leaves the
 * pattern's own default alone, which is not the same as writing 0 — a zeroed
 * Wagging is a tail that does not wag.
 */
data class BehaviorStateConfig(
    val patternId: Byte,
    /** A cleared state is never entered by a trigger. */
    val enabled: Boolean = true,
    val paramMask: Int = 0,
    /** Entered when [timeoutMs] elapses. */
    val fallbackState: Int = 0,
    /** No trigger may leave the state before this. */
    val minDwellMs: Int = 0,
    /** 0 = stay until a trigger says otherwise. */
    val timeoutMs: Int = 0,
    val params: List<Float> = List(PARAM_COUNT) { 0f },
    /**
     * A label for the editor only. The device stores states by index and has no
     * room for a name, so this never reaches the wire.
     */
    val name: String = ""
) {
    val pattern: MotionPattern? get() = MotionPattern.fromId(patternId)

    fun overrides(index: Int): Boolean = paramMask and (1 shl index) != 0

    fun withOverride(index: Int, on: Boolean): BehaviorStateConfig {
        val bit = 1 shl index
        return copy(paramMask = if (on) paramMask or bit else paramMask and bit.inv())
    }

    fun withParam(index: Int, value: Float): BehaviorStateConfig {
        if (index !in params.indices) return this
        return copy(params = params.toMutableList().also { it[index] = value })
    }

    /**
     * Everything the device would refuse, or silently repair, on arrival —
     * caught here so the editor can say what is wrong instead of the tail
     * quietly behaving differently from what the screen shows.
     */
    fun validate(): List<String> = buildList {
        if (params.size != PARAM_COUNT) add("a state carries exactly $PARAM_COUNT parameters")
        params.forEachIndexed { i, v ->
            if (!v.isFinite()) add("parameter $i is not a finite number")
        }
        if (paramMask !in 0..0xFF) add("the parameter mask is one byte (0-255)")
        if (fallbackState !in 0 until Protocol.MAX_BEHAVIOR_STATES) {
            add("the fallback state must be 0-${Protocol.MAX_BEHAVIOR_STATES - 1}")
        }
        if (minDwellMs !in 0..U16_MAX) add("the minimum dwell is a u16 (0-$U16_MAX ms)")
        if (timeoutMs !in 0..U16_MAX) add("the timeout is a u16 (0-$U16_MAX ms)")
    }

    companion object {
        /** `float params[8]` in `behavior_state_config_t`. */
        const val PARAM_COUNT = 8
        const val U16_MAX = 65535
    }
}

/** One rule: what to watch, from where, and where it leads. */
data class BehaviorTriggerConfig(
    val source: BehaviorTriggerSource = BehaviorTriggerSource.NONE,
    /** Bit *i* = may fire while in state *i*; 0 = any state. */
    val fromMask: Int = 0,
    val toState: Int = 0,
    /** Tap sources: how many taps are needed inside [windowMs]. */
    val count: Int = 0,
    val fireBelowThreshold: Boolean = false,
    val threshold: Float = 0f,
    /** The tap-count window, or how long a level must hold before it fires. */
    val windowMs: Int = 0
) {
    val isEnabled: Boolean get() = source != BehaviorTriggerSource.NONE

    fun firesFrom(stateIndex: Int): Boolean =
        fromMask == 0 || fromMask and (1 shl stateIndex) != 0

    fun withFromState(stateIndex: Int, on: Boolean): BehaviorTriggerConfig {
        val bit = 1 shl stateIndex
        return copy(fromMask = if (on) fromMask or bit else fromMask and bit.inv())
    }

    fun validate(): List<String> = buildList {
        if (toState !in 0 until Protocol.MAX_BEHAVIOR_STATES) {
            add("the target state must be 0-${Protocol.MAX_BEHAVIOR_STATES - 1}")
        }
        if (fromMask !in 0..0xFF) add("the from-state mask is one byte (0-255)")
        if (count !in 0..0xFF) add("the tap count is one byte (0-255)")
        // The device clamps a negative threshold to zero rather than rejecting
        // it, which is worse than a refusal: the row then fires on everything.
        if (!threshold.isFinite() || threshold < 0f) add("the threshold must be zero or more")
        if (windowMs !in 0..BehaviorStateConfig.U16_MAX) {
            add("the window is a u16 (0-${BehaviorStateConfig.U16_MAX} ms)")
        }
    }
}

/**
 * The whole table the engine runs on: the moods, the rules, and which mood every
 * decay path leads back to.
 *
 * The device does not publish its table — there is no read for it — so this is
 * the app's own copy of what it last wrote, seeded from the firmware's factory
 * table. [problems] covers what a single record's `validate()` cannot see: rows
 * that point at states which do not exist, or at a state nothing can enter.
 */
data class BehaviorTable(
    val states: List<BehaviorStateConfig>,
    val triggers: List<BehaviorTriggerConfig>,
    /** The declared fallback: a timeout decay and a disconnect both land here. */
    val idleState: Int = 0,
    val engineEnabled: Boolean = false
) {
    fun withState(index: Int, state: BehaviorStateConfig): BehaviorTable {
        if (index !in states.indices) return this
        return copy(states = states.toMutableList().also { it[index] = state })
    }

    fun withTrigger(index: Int, trigger: BehaviorTriggerConfig): BehaviorTable {
        if (index !in triggers.indices) return this
        return copy(triggers = triggers.toMutableList().also { it[index] = trigger })
    }

    fun stateName(index: Int?): String {
        if (index == null) return "—"
        val state = states.getOrNull(index) ?: return "State $index"
        return if (state.name.isBlank()) "State $index" else "$index · ${state.name}"
    }

    fun problems(): List<String> = buildList {
        if (states.size > Protocol.MAX_BEHAVIOR_STATES) {
            add("the device holds at most ${Protocol.MAX_BEHAVIOR_STATES} states")
        }
        if (triggers.size > Protocol.MAX_BEHAVIOR_TRIGGERS) {
            add("the device holds at most ${Protocol.MAX_BEHAVIOR_TRIGGERS} triggers")
        }
        if (idleState !in states.indices) add("the idle state does not exist")
        states.forEachIndexed { i, state ->
            state.validate().forEach { add("State $i: $it") }
            if (state.fallbackState !in states.indices) {
                add("State $i: its fallback state does not exist")
            }
            // The timeout is checked before the dwell floor, so a state that
            // times out sooner than it may be left can only ever end one way.
            if (state.timeoutMs in 1 until state.minDwellMs) {
                add("State $i: it times out before its minimum dwell, so no trigger can leave it")
            }
        }
        triggers.forEachIndexed { i, trigger ->
            trigger.validate().forEach { add("Trigger $i: $it") }
            if (!trigger.isEnabled) return@forEachIndexed
            if (trigger.toState !in states.indices) {
                add("Trigger $i: it goes to a state that does not exist")
            } else if (!states[trigger.toState].enabled) {
                add("Trigger $i: its target state is disabled, so the row never fires")
            }
        }
    }

    companion object {
        /**
         * The firmware's factory table (`BehaviorEngine::default_config`), mirrored
         * here because the device cannot be asked what it is running. Six moods,
         * each reachable by something the device can actually sense, and nine rules
         * in priority order — the first satisfied row wins, so the sharp reactions
         * sit above the slow ones.
         *
         * Off out of the box, exactly as the firmware ships it: a tail that starts
         * moving on its own the first time it is powered up is a surprise.
         */
        val FIRMWARE_DEFAULT = BehaviorTable(
            states = listOf(
                BehaviorStateConfig(
                    patternId = MotionPattern.IDLE_SWAY.id,
                    paramMask = 0x00,
                    minDwellMs = 500,
                    timeoutMs = 0,
                    fallbackState = 0,
                    name = "Idle"
                ),
                BehaviorStateConfig(
                    patternId = MotionPattern.STATIC.id,
                    paramMask = 0x0F,
                    minDwellMs = 400,
                    timeoutMs = 4000,
                    fallbackState = 0,
                    params = paramsOf(0f, 0f, 18f, 18f),
                    name = "Alert"
                ),
                BehaviorStateConfig(
                    patternId = MotionPattern.WAGGING.id,
                    paramMask = 0x03,
                    minDwellMs = 1500,
                    timeoutMs = 12000,
                    fallbackState = 0,
                    params = paramsOf(1.8f, 32f),
                    name = "Happy"
                ),
                BehaviorStateConfig(
                    patternId = MotionPattern.EXCITED_WAG.id,
                    paramMask = 0x0F,
                    minDwellMs = 2000,
                    timeoutMs = 0,
                    fallbackState = 0,
                    params = paramsOf(4.5f, 45f, 1.2f, 0.5f),
                    name = "Excited"
                ),
                BehaviorStateConfig(
                    patternId = MotionPattern.LOOSE.id,
                    paramMask = 0x03,
                    minDwellMs = 3000,
                    timeoutMs = 0,
                    fallbackState = 0,
                    params = paramsOf(0.92f, 0.8f),
                    name = "Sleepy"
                ),
                BehaviorStateConfig(
                    patternId = MotionPattern.SHIVER.id,
                    paramMask = 0x07,
                    minDwellMs = 300,
                    timeoutMs = 1200,
                    // Back to Alert, not Idle: whatever grabbed the tail is still there.
                    fallbackState = 1,
                    params = paramsOf(24f, 4f, 0.9f),
                    name = "Startled"
                )
            ),
            triggers = listOf(
                BehaviorTriggerConfig(BehaviorTriggerSource.TAP_TIP, toState = 5, count = 1),
                BehaviorTriggerConfig(BehaviorTriggerSource.DROP, toState = 3),
                BehaviorTriggerConfig(
                    BehaviorTriggerSource.TAP_BASE, toState = 2, count = 2, windowMs = 1500
                ),
                BehaviorTriggerConfig(
                    BehaviorTriggerSource.TAP_BASE, fromMask = 0x11, toState = 1, count = 1
                ),
                BehaviorTriggerConfig(
                    BehaviorTriggerSource.HANDLING, fromMask = 0x13, toState = 2,
                    threshold = 0.35f, windowMs = 300
                ),
                BehaviorTriggerConfig(
                    BehaviorTriggerSource.LOUDNESS, fromMask = 0x17, toState = 3,
                    threshold = 0.55f, windowMs = 1000
                ),
                BehaviorTriggerConfig(
                    BehaviorTriggerSource.BEAT_ACTIVITY, fromMask = 0x13, toState = 2,
                    threshold = 0.5f, windowMs = 2000
                ),
                BehaviorTriggerConfig(
                    BehaviorTriggerSource.QUIET_TIME, fromMask = 0x2E, toState = 0, threshold = 8f
                ),
                BehaviorTriggerConfig(
                    BehaviorTriggerSource.QUIET_TIME, fromMask = 0x01, toState = 4, threshold = 120f
                )
            ),
            idleState = 0,
            engineEnabled = false
        )

        private fun paramsOf(vararg values: Float): List<Float> =
            List(BehaviorStateConfig.PARAM_COUNT) { values.getOrElse(it) { 0f } }
    }
}
