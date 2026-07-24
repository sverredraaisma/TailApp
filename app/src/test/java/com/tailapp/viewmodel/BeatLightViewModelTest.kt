package com.tailapp.viewmodel

import com.tailapp.composer.CompositionLibrary
import com.tailapp.effects.BeatDecoderKind
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
        engine: LightingEngine = newEngine(),
        library: CompositionLibrary = CompositionLibrary(FakeSharedPreferences())
    ): BeatLightViewModel = BeatLightViewModel(
        deviceRepository = newRepository(),
        engine = engine,
        preview = PreviewLightingOutput(),
        library = library,
        session = null,
        prefs = prefs
    )

    @Test
    fun `the octave bias is off by default and does not touch the engine`() {
        val engine = newEngine()
        newViewModel(engine = engine)

        assertFalse(engine.octaveBias.enabled)
    }

    @Test
    fun `enabling the octave bias applies its settings to the engine`() {
        val engine = newEngine()
        val viewModel = newViewModel(engine = engine)

        viewModel.setOctaveTargetBpm(175f)
        viewModel.setOctaveStrength(0.8f)
        viewModel.setOctaveBiasEnabled(true)

        assertTrue(engine.octaveBias.enabled)
        assertEquals(175f, engine.octaveBias.targetBpm, 0f)
        assertEquals(0.8f, engine.octaveBias.strength, 0f)
    }

    @Test
    fun `the octave bias settings persist across view model instances`() {
        val prefs = com.tailapp.testutil.FakeSharedPreferences()
        newViewModel(prefs = prefs).apply {
            setOctaveTargetBpm(150f)
            setOctaveStrength(0.6f)
            setOctaveBiasEnabled(true)
        }

        val engine = newEngine()
        newViewModel(prefs = prefs, engine = engine)

        assertTrue(engine.octaveBias.enabled)
        assertEquals(150f, engine.octaveBias.targetBpm, 0f)
        assertEquals(0.6f, engine.octaveBias.strength, 0f)
    }

    @Test
    fun `the octave target is clamped to the allowed range`() {
        val engine = newEngine()
        val viewModel = newViewModel(engine = engine)
        viewModel.setOctaveBiasEnabled(true)

        viewModel.setOctaveTargetBpm(5000f)
        assertEquals(com.tailapp.beat.OctaveBias.MAX_TARGET_BPM, engine.octaveBias.targetBpm, 0f)
    }

    @Test
    fun `defaults to no calibration and the first built-in stack`() {
        val engine = newEngine()
        val viewModel = newViewModel(engine = engine)

        assertEquals(0f, viewModel.triggerOffsetMillis.value, 0f)
        assertEquals(CompositionLibrary.BUILT_INS.first().id, viewModel.activeCompositionId.value)
        // The engine must actually be holding it, not just the UI-facing flow —
        // otherwise a session started straight away would render black.
        assertEquals(CompositionLibrary.BUILT_INS.first().id, engine.composition.id)
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
    fun `selecting a composition applies it to the engine and reports it`() {
        val engine = newEngine()
        val viewModel = newViewModel(engine = engine)
        val target = CompositionLibrary.BUILT_INS[1]

        viewModel.setActiveComposition(target.id)

        assertEquals(target.id, viewModel.activeCompositionId.value)
        assertEquals(target.id, engine.composition.id)
        assertEquals(target.id, viewModel.state.value.compositionId)
        assertEquals(target.name, viewModel.state.value.compositionName)
    }

    @Test
    fun `the selected composition persists and reaches a fresh engine`() {
        val libraryPrefs = FakeSharedPreferences()
        val target = CompositionLibrary.BUILT_INS[2]

        newViewModel(library = CompositionLibrary(libraryPrefs)).setActiveComposition(target.id)

        val restoredEngine = newEngine()
        val restored = newViewModel(
            engine = restoredEngine,
            library = CompositionLibrary(libraryPrefs)
        )

        assertEquals(target.id, restored.activeCompositionId.value)
        assertEquals(target.id, restoredEngine.composition.id)
    }

    @Test
    fun `a saved composition id that no longer resolves falls back to a built-in`() {
        // A stack removed by an app update must not leave the session rendering
        // nothing.
        val libraryPrefs = FakeSharedPreferences()
        libraryPrefs.edit().putString(CompositionLibrary.KEY_ACTIVE, "not-a-real-stack").apply()

        val engine = newEngine()
        val viewModel = newViewModel(engine = engine, library = CompositionLibrary(libraryPrefs))

        assertEquals(CompositionLibrary.BUILT_INS.first().id, viewModel.activeCompositionId.value)
        assertEquals(CompositionLibrary.BUILT_INS.first().id, engine.composition.id)
    }

    @Test
    fun `a user edit of a built-in is the version applied`() {
        val libraryPrefs = FakeSharedPreferences()
        val builtIn = CompositionLibrary.BUILT_INS.first()
        CompositionLibrary(libraryPrefs).save(builtIn.copy(name = "My Pulse"))

        val engine = newEngine()
        newViewModel(engine = engine, library = CompositionLibrary(libraryPrefs))

        assertEquals(builtIn.id, engine.composition.id)
        assertEquals("My Pulse", engine.composition.name)
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

    @Test
    fun `defaults to the phase-locked decoder`() {
        val engine = newEngine()
        val viewModel = newViewModel(engine = engine)

        assertEquals(BeatDecoderKind.PHASE_LOCKED, viewModel.decoderKind.value)
        assertEquals(BeatDecoderKind.PHASE_LOCKED, engine.decoderKind)
    }

    @Test
    fun `setDecoder applies to the engine`() {
        val engine = newEngine()
        val viewModel = newViewModel(engine = engine)

        viewModel.setDecoder(BeatDecoderKind.PARTICLE_FILTER)

        assertEquals(BeatDecoderKind.PARTICLE_FILTER, viewModel.decoderKind.value)
        assertEquals(BeatDecoderKind.PARTICLE_FILTER, engine.decoderKind)
    }

    @Test
    fun `the decoder choice persists across view model instances`() {
        val prefs = FakeSharedPreferences()
        newViewModel(prefs = prefs).setDecoder(BeatDecoderKind.PARTICLE_FILTER)

        val engine = newEngine()
        val restored = newViewModel(prefs = prefs, engine = engine)

        assertEquals(BeatDecoderKind.PARTICLE_FILTER, restored.decoderKind.value)
        assertEquals(BeatDecoderKind.PARTICLE_FILTER, engine.decoderKind)
    }

    @Test
    fun `an unknown saved decoder falls back to the default`() {
        // A decoder removed by an app update must not leave the app unable to start.
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(BeatLightViewModel.KEY_DECODER, "GRADIENT_DESCENT_ORACLE").apply()

        assertEquals(BeatDecoderKind.PHASE_LOCKED, newViewModel(prefs = prefs).decoderKind.value)
    }

}
