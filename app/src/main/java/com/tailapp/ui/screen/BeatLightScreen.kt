package com.tailapp.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.beat.BeatEvent
import com.tailapp.beat.OctaveBias
import com.tailapp.ble.ConnectionState
import com.tailapp.effects.BeatDecoderKind
import com.tailapp.effects.BeatLightState
import com.tailapp.composer.Composition
import com.tailapp.composer.EffectLayer
import com.tailapp.composer.GroupLayer
import com.tailapp.composer.LayerNode
import com.tailapp.composer.TailTelemetry
import com.tailapp.genre.GenreState
import com.tailapp.led.PixelBuffer
import com.tailapp.ui.components.LedPreviewPlaceholder
import com.tailapp.ui.components.LedStrip
import com.tailapp.viewmodel.BeatLightViewModel

/**
 * Monitoring, calibration and manual profile selection for the beat/drop
 * reactive lighting session.
 *
 * The session runs (and the preview animates) with no tail connected — the
 * profiles are meant to be tunable at a desk — so this screen never gates
 * start/stop or calibration behind a connection; it only says so when there
 * isn't one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeatLightScreen(
    viewModel: BeatLightViewModel,
    onEditStack: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isActive by viewModel.isActive.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()
    val triggerOffsetMillis by viewModel.triggerOffsetMillis.collectAsStateWithLifecycle()
    val compositions by viewModel.compositions.collectAsStateWithLifecycle()
    val activeCompositionId by viewModel.activeCompositionId.collectAsStateWithLifecycle()
    val decoderKind by viewModel.decoderKind.collectAsStateWithLifecycle()
    val octaveBiasEnabled by viewModel.octaveBiasEnabled.collectAsStateWithLifecycle()
    val octaveTargetBpm by viewModel.octaveTargetBpm.collectAsStateWithLifecycle()
    val octaveStrength by viewModel.octaveStrength.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.start()
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BeatLight") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (deviceState.connectionState != ConnectionState.CONNECTED) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        "No tail connected — the session still runs and the preview below " +
                            "still animates, so profiles can be tuned at a desk.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // Start / stop
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("BeatLight session", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (isActive) "Listening" else "Stopped",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = {
                        if (!isActive) {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            viewModel.stop()
                        }
                    }) {
                        Text(if (isActive) "Stop" else "Start")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            MonitorSection(state = state, frame = frame, ledsPerRing = deviceState.ledState?.ledsPerRing.orEmpty())

            Spacer(Modifier.height(16.dp))
            CalibrationSection(
                triggerOffsetMillis = triggerOffsetMillis,
                onOffsetChange = viewModel::setTriggerOffset,
                decoderKind = decoderKind,
                onDecoderChange = viewModel::setDecoder,
                octaveBiasEnabled = octaveBiasEnabled,
                octaveTargetBpm = octaveTargetBpm,
                octaveStrength = octaveStrength,
                onOctaveEnabledChange = viewModel::setOctaveBiasEnabled,
                onOctaveTargetChange = viewModel::setOctaveTargetBpm,
                onOctaveStrengthChange = viewModel::setOctaveStrength
            )

            Spacer(Modifier.height(16.dp))
            CompositionsSection(
                compositions = compositions,
                activeId = activeCompositionId,
                onSelect = viewModel::setActiveComposition,
                onEditStack = onEditStack
            )
        }
    }
}

@Composable
private fun MonitorSection(
    state: BeatLightState,
    frame: PixelBuffer?,
    ledsPerRing: List<Int>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Monitor", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                BeatPulse(lastBeat = state.lastBeat)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = if (state.bpm > 0f) "%.0f".format(state.bpm) else "--",
                        style = MaterialTheme.typography.displayMedium
                    )
                    Text("BPM", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Confidence ${"%.0f".format(state.beatConfidence * 100)}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress = { state.beatConfidence.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            val sectionLabel = state.section.name.lowercase().replaceFirstChar { it.uppercase() }
            Text("Section: $sectionLabel", style = MaterialTheme.typography.bodyMedium)
            if (state.section.isTransitional) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { state.sectionRamp.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))
            val genreLabel = if (state.genre.label == GenreState.UNKNOWN_LABEL) {
                "Genre: unknown"
            } else {
                "Genre: ${state.genre.label} (${"%.0f".format(state.genre.confidence * 100)}%)"
            }
            Text(genreLabel, style = MaterialTheme.typography.bodyMedium)

            // The tail's own state, so a look that reacts to the body can be
            // debugged the same way a beat-reactive one can. Only shown once
            // something has actually arrived: with no tail connected these
            // would be a row of confident-looking zeros.
            if (state.tapCount > 0 || state.tail != TailTelemetry.AT_REST) {
                Spacer(Modifier.height(8.dp))
                val tapLabel = state.lastTapEnd?.let { end ->
                    "Taps ${state.tapCount} (last: ${end.name.lowercase()})"
                } ?: "Taps ${state.tapCount}"
                Text(
                    "$tapLabel · Deflection " +
                        "${"%+.2f".format(state.tail.deflectionX)}, " +
                        "${"%+.2f".format(state.tail.deflectionY)} · " +
                        "Wag ${"%.0f".format(state.tail.wagSpeed * 100)}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(16.dp))
            if (ledsPerRing.isNotEmpty()) {
                LedStrip(pixels = frame, ledsPerRing = ledsPerRing, modifier = Modifier.fillMaxWidth())
            } else {
                LedPreviewPlaceholder(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Latency ${"%.0f".format(state.inputLatencyMillis)} ms · " +
                    "Dropped samples ${state.droppedSamples} · " +
                    "Activation ${state.activationSource.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A dot that flashes on every beat, driven by the Compose frame clock rather
 * than a fixed animation — [BeatEvent.timestampNanos] can be slightly ahead of
 * now (the tracker predicts the next beat so lighting can be scheduled ahead
 * of the BLE round trip), so the flash is held back until the wall clock
 * actually reaches it, then decays over [PULSE_DURATION_MILLIS].
 */
@Composable
private fun BeatPulse(lastBeat: BeatEvent?, modifier: Modifier = Modifier) {
    var pulse by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(lastBeat) {
        val beat = lastBeat
        if (beat == null) {
            pulse = 0f
            return@LaunchedEffect
        }
        while (true) {
            // withFrameNanos returns its lambda's value; stop once the pulse has
            // fully decayed so the frame clock can idle between beats instead of
            // spinning forever. The next beat re-arms this effect via lastBeat.
            val decayed = withFrameNanos { nowNanos ->
                val sinceMillis = (nowNanos - beat.timestampNanos) / 1_000_000f
                pulse = when {
                    sinceMillis < 0f -> 0f
                    sinceMillis > PULSE_DURATION_MILLIS -> 0f
                    else -> 1f - (sinceMillis / PULSE_DURATION_MILLIS)
                }
                // A beat still in the future has not started; keep waiting for it.
                sinceMillis > PULSE_DURATION_MILLIS
            }
            if (decayed) break
        }
    }

    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(40.dp)
            .scale(1f + pulse * 0.35f)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.25f + pulse * 0.75f))
    )
}

@Composable
private fun CalibrationSection(
    triggerOffsetMillis: Float,
    onOffsetChange: (Float) -> Unit,
    decoderKind: BeatDecoderKind,
    onDecoderChange: (BeatDecoderKind) -> Unit,
    octaveBiasEnabled: Boolean,
    octaveTargetBpm: Float,
    octaveStrength: Float,
    onOctaveEnabledChange: (Boolean) -> Unit,
    onOctaveTargetChange: (Float) -> Unit,
    onOctaveStrengthChange: (Float) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Calibration", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Trigger offset: ${"%+.0f".format(triggerOffsetMillis)} ms",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Negative fires the light earlier — raise this if the flash lands late, " +
                    "lower it if the flash lands early.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = triggerOffsetMillis,
                onValueChange = onOffsetChange,
                valueRange = BeatLightViewModel.MIN_OFFSET_MS..BeatLightViewModel.MAX_OFFSET_MS,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${BeatLightViewModel.MIN_OFFSET_MS.toInt()} ms",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    "${BeatLightViewModel.MAX_OFFSET_MS.toInt()} ms",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("Beat decoder", style = MaterialTheme.typography.titleSmall)
            Text(
                "Neither is strictly better — the only way to settle it is to hear both " +
                    "against the same music. Switching restarts the session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            BeatDecoderKind.entries.forEach { kind ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = decoderKind == kind,
                        onClick = { onDecoderChange(kind) }
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(kind.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            kind.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tempo octave lock", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Beat tracking can't tell a tempo from its half or double. Turn this " +
                            "on and set the tempo your music sits around — ambiguous tracks lean " +
                            "toward it, but clearly slower or faster songs still win on their own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = octaveBiasEnabled, onCheckedChange = onOctaveEnabledChange)
            }

            if (octaveBiasEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Target tempo: ${"%.0f".format(octaveTargetBpm)} BPM",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = octaveTargetBpm,
                    onValueChange = onOctaveTargetChange,
                    valueRange = OctaveBias.MIN_TARGET_BPM..OctaveBias.MAX_TARGET_BPM,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Strength: ${"%.0f".format(octaveStrength * 100)}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Higher pulls harder toward the target — raise it only if a set keeps " +
                        "landing on the wrong octave.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = octaveStrength,
                    onValueChange = onOctaveStrengthChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CompositionsSection(
    compositions: List<Composition>,
    activeId: String,
    onSelect: (String) -> Unit,
    onEditStack: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Effect stack",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onEditStack) { Text("Edit") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Layers and folders, composed on the phone and streamed to the tail.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            compositions.forEach { composition ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = composition.id == activeId,
                        onClick = { onSelect(composition.id) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(composition.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            composition.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** "3 layers · 1 folder" — enough to tell two stacks apart in the picker. */
private fun Composition.summary(): String {
    var effects = 0
    var folders = 0

    fun walk(nodes: List<LayerNode>) {
        nodes.forEach { node ->
            when (node) {
                is GroupLayer -> {
                    folders++
                    walk(node.children)
                }
                is EffectLayer -> effects++
            }
        }
    }
    walk(layers)

    val layerText = "$effects layer" + if (effects == 1) "" else "s"
    val folderText = if (folders == 0) "" else " · $folders folder" + if (folders == 1) "" else "s"
    return layerText + folderText
}

private const val PULSE_DURATION_MILLIS = 250f
