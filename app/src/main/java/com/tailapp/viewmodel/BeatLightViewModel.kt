package com.tailapp.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.tailapp.beat.OctaveBias
import com.tailapp.effects.BeatDecoderKind
import com.tailapp.effects.BeatLightSession
import com.tailapp.effects.BeatLightState
import com.tailapp.effects.EffectProfile
import com.tailapp.effects.EffectProfiles
import com.tailapp.effects.LightingEngine
import com.tailapp.led.PixelBuffer
import com.tailapp.lighting.PreviewLightingOutput
import com.tailapp.model.DeviceState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backs the BeatLight screen: monitoring, calibration and manual profile
 * selection for the beat/drop-reactive lighting session.
 *
 * Deliberately thin — every flow here is a straight pass-through of state the
 * pipeline already publishes. The only state this class owns outright is the
 * calibration the user dials in, because that has to survive process death: a
 * trigger offset the user has to re-find every launch defeats the point of
 * calibrating it.
 *
 * [session] and [prefs] are nullable, like [AudioConfigViewModel]'s
 * `fftStreamManager` - both need a real `Context` to construct
 * ([BeatLightSession] starts a foreground service; `SharedPreferences` comes
 * from one), which a JVM unit test can't provide. [engine] and [preview] carry
 * no such dependency, so tests exercise calibration and profile selection
 * against the real [LightingEngine] without needing either.
 */
class BeatLightViewModel(
    private val deviceRepository: DeviceRepository,
    private val engine: LightingEngine,
    preview: PreviewLightingOutput,
    private val session: BeatLightSession? = null,
    private val prefs: SharedPreferences? = null
) : ViewModel() {

    /** BPM, confidence, section, genre, active profile - everything the monitor shows. */
    val state: StateFlow<BeatLightState> = engine.state

    /** Whether a session is meant to be running (see [BeatLightSession.isActive]). */
    val isActive: StateFlow<Boolean> = session?.isActive ?: MutableStateFlow(false)

    /** Set when the session could not be started. */
    val error: StateFlow<String?> = session?.error ?: MutableStateFlow(null)

    /** The frame actually being sent to the tail (and, with no tail, just rendered). */
    val frame: StateFlow<PixelBuffer?> = preview.frame

    /** Connection state + LED layout live here; the screen reads what it needs. */
    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    /** Every built-in look, most specific first — see [EffectProfiles.ALL]. */
    val profiles: List<EffectProfile> = EffectProfiles.ALL

    private val _triggerOffsetMillis = MutableStateFlow(
        (prefs?.getFloat(KEY_TRIGGER_OFFSET, 0f) ?: 0f).coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
    )

    /** User calibration in milliseconds, restored from [prefs]; negative fires earlier. */
    val triggerOffsetMillis: StateFlow<Float> = _triggerOffsetMillis.asStateFlow()

    private val _decoderKind = MutableStateFlow(
        prefs?.getString(KEY_DECODER, null)
            ?.let { saved -> BeatDecoderKind.entries.firstOrNull { it.name == saved } }
            ?: BeatDecoderKind.PHASE_LOCKED
    )

    /**
     * Which beat decoder the next session runs. Neither is strictly better —
     * see [BeatDecoderKind] — so this is a real choice, and the only way to
     * settle it is to hear both against the same music.
     */
    val decoderKind: StateFlow<BeatDecoderKind> = _decoderKind.asStateFlow()

    private val _manualProfileId = MutableStateFlow<String?>(null)

    /** Id of the pinned profile, or null while automatic (classifier-driven). */
    val manualProfileId: StateFlow<String?> = _manualProfileId.asStateFlow()

    private val _octaveBiasEnabled =
        MutableStateFlow(prefs?.getBoolean(KEY_OCTAVE_ENABLED, false) ?: false)
    private val _octaveTargetBpm = MutableStateFlow(
        (prefs?.getFloat(KEY_OCTAVE_TARGET, OctaveBias.DEFAULT_TARGET_BPM) ?: OctaveBias.DEFAULT_TARGET_BPM)
            .coerceIn(OctaveBias.MIN_TARGET_BPM, OctaveBias.MAX_TARGET_BPM)
    )
    private val _octaveStrength = MutableStateFlow(
        (prefs?.getFloat(KEY_OCTAVE_STRENGTH, OctaveBias.DEFAULT_STRENGTH) ?: OctaveBias.DEFAULT_STRENGTH)
            .coerceIn(0f, 1f)
    )

    /**
     * Tempo-octave preference — the fix for half/double drift. When on, the
     * decoders lean toward [octaveTargetBpm] when the audio is octave-ambiguous,
     * so a fast set stays fast; genuinely slow music still wins on its own
     * evidence. Applied live to whichever decoder is running.
     */
    val octaveBiasEnabled: StateFlow<Boolean> = _octaveBiasEnabled.asStateFlow()
    val octaveTargetBpm: StateFlow<Float> = _octaveTargetBpm.asStateFlow()
    val octaveStrength: StateFlow<Float> = _octaveStrength.asStateFlow()

    init {
        // Apply the restored calibration to the engine immediately - a fresh
        // LightingEngine starts at triggerOffsetMillis = 0, so without this the
        // slider would show the saved value while the pipeline ignored it.
        engine.triggerOffsetMillis = _triggerOffsetMillis.value

        // A profile id saved by an older build (or one an app update removed)
        // has nowhere to go — fall back to automatic rather than crash or pin
        // a null-named profile.
        val restored = prefs?.getString(KEY_MANUAL_PROFILE, null)?.let(EffectProfiles::byId)
        _manualProfileId.value = restored?.id
        engine.manualProfile = restored

        engine.decoderKind = _decoderKind.value
        applyOctaveBias()
    }

    private fun applyOctaveBias() {
        engine.setOctaveBias(_octaveBiasEnabled.value, _octaveTargetBpm.value, _octaveStrength.value)
    }

    fun start() {
        session?.start()
    }

    fun stop() {
        session?.stop()
    }

    fun toggle() {
        session?.toggle()
    }

    fun clearError() {
        session?.clearError()
    }

    /** Applies and persists a new trigger offset, clamped to the calibration range. */
    fun setTriggerOffset(ms: Float) {
        val clamped = ms.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
        _triggerOffsetMillis.value = clamped
        engine.triggerOffsetMillis = clamped
        prefs?.edit()?.putFloat(KEY_TRIGGER_OFFSET, clamped)?.apply()
    }

    /** Turns the octave preference on or off, live and persisted. */
    fun setOctaveBiasEnabled(enabled: Boolean) {
        _octaveBiasEnabled.value = enabled
        applyOctaveBias()
        prefs?.edit()?.putBoolean(KEY_OCTAVE_ENABLED, enabled)?.apply()
    }

    /** Sets the preferred tempo the octave preference leans toward, live and persisted. */
    fun setOctaveTargetBpm(bpm: Float) {
        val clamped = bpm.coerceIn(OctaveBias.MIN_TARGET_BPM, OctaveBias.MAX_TARGET_BPM)
        _octaveTargetBpm.value = clamped
        applyOctaveBias()
        prefs?.edit()?.putFloat(KEY_OCTAVE_TARGET, clamped)?.apply()
    }

    /** Sets how firmly the octave preference pulls, live and persisted. */
    fun setOctaveStrength(strength: Float) {
        val clamped = strength.coerceIn(0f, 1f)
        _octaveStrength.value = clamped
        applyOctaveBias()
        prefs?.edit()?.putFloat(KEY_OCTAVE_STRENGTH, clamped)?.apply()
    }

    /**
     * Chooses the decoder for the next session.
     *
     * A running session is restarted, because swapping a decoder mid-flight
     * would race the analysis thread against whatever state it carries. The
     * restart is a second of darkness, which is the honest cost of the change.
     */
    fun setDecoder(kind: BeatDecoderKind) {
        if (_decoderKind.value == kind) return
        _decoderKind.value = kind
        engine.decoderKind = kind
        prefs?.edit()?.putString(KEY_DECODER, kind.name)?.apply()

        if (session?.isActive?.value == true) {
            session.stop()
            session.start()
        }
    }

    /** Pins [profile], or returns to the classifier when it is null. */
    fun setManualProfile(profile: EffectProfile?) {
        _manualProfileId.value = profile?.id
        engine.manualProfile = profile
        val editor = prefs?.edit() ?: return
        if (profile != null) editor.putString(KEY_MANUAL_PROFILE, profile.id) else editor.remove(KEY_MANUAL_PROFILE)
        editor.apply()
    }

    companion object {
        /** Roughly what a Bluetooth round trip plus a render frame can account for. */
        const val MIN_OFFSET_MS = -200f
        const val MAX_OFFSET_MS = 200f

        // Visible to tests so persistence can be exercised through the same
        // SharedPreferences keys the view model itself reads and writes.
        internal const val KEY_TRIGGER_OFFSET = "beatlight_trigger_offset_ms"
        internal const val KEY_MANUAL_PROFILE = "beatlight_manual_profile_id"
        internal const val KEY_DECODER = "beatlight_decoder"
        internal const val KEY_OCTAVE_ENABLED = "beatlight_octave_enabled"
        internal const val KEY_OCTAVE_TARGET = "beatlight_octave_target_bpm"
        internal const val KEY_OCTAVE_STRENGTH = "beatlight_octave_strength"
    }
}
