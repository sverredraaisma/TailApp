package com.tailapp.viewmodel

import com.tailapp.effects.EffectProfiles
import com.tailapp.effects.LightingEngine
import com.tailapp.lighting.PreviewLightingOutput
import com.tailapp.repository.DeviceRepository
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FakeSharedPreferences
import com.tailapp.testutil.RecordingLightingOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [BeatLightViewModel] against a real [LightingEngine] and a fake
 * [android.content.SharedPreferences] — no `BeatLightSession` (it needs a real
 * `Context` to start a foreground service) and no Android runtime, matching
 * how [LedConfigViewModelTest] drives its view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BeatLightViewModelTest {

    /**
     * [DeviceRepository]'s connection-state collector lives on this scope
     * rather than a `runTest` `backgroundScope` — see the `runTest` gotcha in
     * CLAUDE.md — and is cancelled after every test so nothing leaks between
     * them.
     */
    private var repositoryScope: CoroutineScope? = null

    @After
    fun cancelRepositoryScope() {
        repositoryScope?.cancel()
        repositoryScope = null
    }

    private fun newRepository(): DeviceRepository {
        val scope = CoroutineScope(StandardTestDispatcher())
        repositoryScope = scope
        return DeviceRepository(FakeBleTransport(), scope)
    }

    private fun newEngine(): LightingEngine = LightingEngine(
        output = RecordingLightingOutput(),
        ledLayout = MutableStateFlow(emptyList<Int>()),
        scope = CoroutineScope(Job())
    )

    private fun newViewModel(
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
        engine: LightingEngine = newEngine()
    ): BeatLightViewModel = BeatLightViewModel(
        deviceRepository = newRepository(),
        engine = engine,
        preview = PreviewLightingOutput(),
        session = null,
        prefs = prefs
    )

    @Test
    fun `defaults to no calibration and an automatic profile`() {
        val viewModel = newViewModel()

        assertEquals(0f, viewModel.triggerOffsetMillis.value, 0f)
        assertNull(viewModel.manualProfileId.value)
        assertFalse(viewModel.state.value.isProfileOverridden)
    }

    @Test
    fun `setTriggerOffset applies to the engine immediately`() {
        val engine = newEngine()
        val viewModel = newViewModel(engine = engine)

        viewModel.setTriggerOffset(-40f)

        assertEquals(-40f, viewModel.triggerOffsetMillis.value, 0f)
        assertEquals(-40f, engine.triggerOffsetMillis, 0f)
    }

    @Test
    fun `setTriggerOffset persists across view model instances`() {
        val prefs = FakeSharedPreferences()
        newViewModel(prefs = prefs).setTriggerOffset(75f)

        val restoredEngine = newEngine()
        val restored = newViewModel(prefs = prefs, engine = restoredEngine)

        assertEquals(75f, restored.triggerOffsetMillis.value, 0f)
        // Restoration has to reach the engine too, not just the UI-facing flow -
        // a fresh LightingEngine otherwise starts back at zero offset.
        assertEquals(75f, restoredEngine.triggerOffsetMillis, 0f)
    }

    @Test
    fun `setTriggerOffset clamps values above the calibration range`() {
        val viewModel = newViewModel()

        viewModel.setTriggerOffset(9000f)

        assertEquals(BeatLightViewModel.MAX_OFFSET_MS, viewModel.triggerOffsetMillis.value, 0f)
    }

    @Test
    fun `setTriggerOffset clamps values below the calibration range`() {
        val viewModel = newViewModel()

        viewModel.setTriggerOffset(-9000f)

        assertEquals(BeatLightViewModel.MIN_OFFSET_MS, viewModel.triggerOffsetMillis.value, 0f)
    }

    @Test
    fun `a persisted offset outside the current range is restored clamped`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putFloat(BeatLightViewModel.KEY_TRIGGER_OFFSET, 5000f).apply()

        val viewModel = newViewModel(prefs = prefs)

        assertEquals(BeatLightViewModel.MAX_OFFSET_MS, viewModel.triggerOffsetMillis.value, 0f)
    }

    @Test
    fun `setManualProfile reaches the engine and marks the profile overridden`() {
        val engine = newEngine()
        val viewModel = newViewModel(engine = engine)

        viewModel.setManualProfile(EffectProfiles.HARDSTYLE)

        assertEquals(EffectProfiles.HARDSTYLE.id, viewModel.manualProfileId.value)
        assertEquals(EffectProfiles.HARDSTYLE.id, engine.manualProfile?.id)
        assertEquals(EffectProfiles.HARDSTYLE.id, viewModel.state.value.profileId)
        assertTrue(viewModel.state.value.isProfileOverridden)
    }

    @Test
    fun `setManualProfile null clears the override and returns to automatic`() {
        val engine = newEngine()
        val viewModel = newViewModel(engine = engine)
        viewModel.setManualProfile(EffectProfiles.TRANCE)

        viewModel.setManualProfile(null)

        assertNull(viewModel.manualProfileId.value)
        assertNull(engine.manualProfile)
        assertFalse(viewModel.state.value.isProfileOverridden)
        assertEquals(EffectProfiles.DEFAULT.id, viewModel.state.value.profileId)
    }

    @Test
    fun `manual profile selection persists and is restored, reaching a fresh engine`() {
        val prefs = FakeSharedPreferences()
        newViewModel(prefs = prefs).setManualProfile(EffectProfiles.BASS)

        val restoredEngine = newEngine()
        val restored = newViewModel(prefs = prefs, engine = restoredEngine)

        assertEquals(EffectProfiles.BASS.id, restored.manualProfileId.value)
        assertEquals(EffectProfiles.BASS.id, restoredEngine.manualProfile?.id)
        assertTrue(restored.state.value.isProfileOverridden)
    }

    @Test
    fun `clearing the manual profile removes it from persistence`() {
        val prefs = FakeSharedPreferences()
        val viewModel = newViewModel(prefs = prefs)
        viewModel.setManualProfile(EffectProfiles.AMBIENT)

        viewModel.setManualProfile(null)

        val restored = newViewModel(prefs = prefs, engine = newEngine())
        assertNull(restored.manualProfileId.value)
        assertFalse(restored.state.value.isProfileOverridden)
    }

    @Test
    fun `a persisted profile id that no longer resolves falls back to automatic`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(BeatLightViewModel.KEY_MANUAL_PROFILE, "not-a-real-profile").apply()

        val viewModel = newViewModel(prefs = prefs)

        assertNull(viewModel.manualProfileId.value)
        assertFalse(viewModel.state.value.isProfileOverridden)
    }

    @Test
    fun `start, stop and toggle are safe with no session`() {
        // A null session (the JVM-test stand-in for "no Context available")
        // must not crash the calls the screen's start/stop button makes.
        val viewModel = newViewModel()

        viewModel.start()
        viewModel.stop()
        viewModel.toggle()
        viewModel.clearError()

        assertFalse(viewModel.isActive.value)
        assertNull(viewModel.error.value)
    }
}
