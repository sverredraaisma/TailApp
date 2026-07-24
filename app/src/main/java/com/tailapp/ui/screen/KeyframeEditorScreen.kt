package com.tailapp.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.ble.protocol.Protocol
import com.tailapp.model.Keyframe
import com.tailapp.model.MotionPattern
import com.tailapp.model.MotionState
import com.tailapp.model.TailPose
import com.tailapp.ui.components.TailPositionView
import com.tailapp.viewmodel.KeyframeEditorViewModel

/**
 * Authoring for `PATTERN_KEYFRAME`: a list of timed poses, a scrubbable preview
 * of what the device will do with them, and the upload to one of its four
 * sequence slots.
 *
 * The preview draws through [TailPositionView] — the same widget the motion
 * screen plots the live tail with — because an authored pose and a reported one
 * have to be comparable at a glance to be worth anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyframeEditorScreen(
    viewModel: KeyframeEditorViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.deviceState.collectAsStateWithLifecycle()
    val sequence by viewModel.sequence.collectAsStateWithLifecycle()
    val problems by viewModel.problems.collectAsStateWithLifecycle()
    val selectedIndex by viewModel.selectedIndex.collectAsStateWithLifecycle()
    val previewTimeMs by viewModel.previewTimeMs.collectAsStateWithLifecycle()
    val previewPose by viewModel.previewPose.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val driveTail by viewModel.driveTail.collectAsStateWithLifecycle()
    val slot by viewModel.slot.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    val motionState = state.motionState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keyframe Sequence") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Preview — the same interpolation the device runs, scrubbed.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Preview", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Linear between keyframes, exactly as the tail plays it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    TailPositionView(
                        motionState = previewMotionState(previewPose, motionState),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "%.2f s of %.2f s".format(previewTimeMs / 1000f, sequence.durationMs / 1000f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = previewTimeMs.toFloat(),
                        onValueChange = { viewModel.scrubTo(it.toInt()) },
                        valueRange = 0f..sequence.durationMs.coerceAtLeast(1).toFloat(),
                        enabled = sequence.durationMs > 0
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = viewModel::togglePlay,
                            enabled = sequence.durationMs > 0
                        ) { Text(if (playing) "Pause" else "Play") }
                        Spacer(Modifier.width(16.dp))
                        Text("Loop", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = sequence.loop,
                            onCheckedChange = viewModel::setLoop
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Move the real tail", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Streams the previewed pose live. It suspends the active " +
                                    "pattern, and the tail is handed back when you switch this off.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = driveTail, onCheckedChange = viewModel::setDriveTail)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Keyframe list
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Keyframes (${sequence.keyframes.size}/${Protocol.MAX_SEQUENCE_KEYFRAMES})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::addKeyframe) { Text("Add") }
                    }
                    Text(
                        "Add captures the pose on screen at the scrubbed time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    sequence.keyframes.forEachIndexed { index, keyframe ->
                        KeyframeRow(
                            index = index,
                            keyframe = keyframe,
                            selected = index == selectedIndex,
                            onSelect = { viewModel.selectKeyframe(index) },
                            onMove = { offset -> viewModel.moveKeyframe(index, offset) },
                            onDelete = { viewModel.deleteKeyframe(index) }
                        )
                        if (index < sequence.keyframes.lastIndex) HorizontalDivider()
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Pose editor for the selected keyframe
            val selected = sequence.keyframes.getOrNull(selectedIndex)
            if (selected != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Keyframe $selectedIndex",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))

                        Text(
                            "Time  %.2f s".format(selected.timeMs / 1000f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = selected.timeMs.toFloat(),
                            onValueChange = {
                                // Snapped to 10 ms: the wire carries whole
                                // milliseconds, and a drag that lands on 1 ms
                                // steps makes two keyframes collide by accident.
                                viewModel.setKeyframeTime(selectedIndex, (it / 10f).toInt() * 10)
                            },
                            valueRange = 0f..timeSliderMaxMs(selected.timeMs, sequence.durationMs),
                            // The device rejects a sequence whose first keyframe
                            // is not at zero, so that one is not draggable.
                            enabled = selectedIndex > 0
                        )
                        if (selectedIndex == 0) {
                            Text(
                                "The first keyframe is the start of the sequence and stays at 0 s.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("Pose", style = MaterialTheme.typography.bodyMedium)
                        val xRange = axisRange(motionState?.xAxisMin, motionState?.xAxisMax)
                        val yRange = axisRange(motionState?.yAxisMin, motionState?.yAxisMax)
                        PoseSlider("Base X", selected.pose.baseX, xRange) {
                            viewModel.setPose(selectedIndex, selected.pose.copy(baseX = it))
                        }
                        PoseSlider("Base Y", selected.pose.baseY, yRange) {
                            viewModel.setPose(selectedIndex, selected.pose.copy(baseY = it))
                        }
                        PoseSlider("Tip X", selected.pose.tipX, xRange) {
                            viewModel.setPose(selectedIndex, selected.pose.copy(tipX = it))
                        }
                        PoseSlider("Tip Y", selected.pose.tipY, yRange) {
                            viewModel.setPose(selectedIndex, selected.pose.copy(tipY = it))
                        }
                        if (motionState == null) {
                            Text(
                                "Angles are shown against ±$FALLBACK_TRAVEL_DEGREES° until the " +
                                    "tail reports its own axis limits.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            // Anything the device would refuse, named before the transfer.
            if (problems.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "The tail would refuse this sequence",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        problems.forEach { problem ->
                            Text(
                                "• ${problem.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            // Upload
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Store on the tail", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${sequence.encodedSize} bytes, verified by checksum on arrival.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        repeat(Protocol.MAX_SEQUENCE_SLOTS) { index ->
                            FilterChip(
                                selected = slot == index,
                                onClick = { viewModel.selectSlot(index) },
                                label = { Text("Slot $index") },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val progress = uploadProgress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row {
                        Button(
                            onClick = viewModel::upload,
                            enabled = progress == null && problems.isEmpty()
                        ) { Text("Upload") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = viewModel::playOnTail,
                            enabled = progress == null
                        ) { Text("Play on tail") }
                    }
                    val message = status
                    if (message != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::clearStatus) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyframeRow(
    index: Int,
    keyframe: Keyframe,
    selected: Boolean,
    onSelect: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onSelect, modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "%.2f s".format(keyframe.timeMs / 1000f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    "base %.0f°/%.0f°  tip %.0f°/%.0f°".format(
                        keyframe.pose.baseX, keyframe.pose.baseY,
                        keyframe.pose.tipX, keyframe.pose.tipY
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { onMove(-1) }) {
            Icon(Icons.Default.KeyboardArrowUp, "Move earlier")
        }
        IconButton(onClick = { onMove(1) }) {
            Icon(Icons.Default.KeyboardArrowDown, "Move later")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, "Delete keyframe $index")
        }
    }
}

@Composable
private fun PoseSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
        Slider(
            value = value.coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(
            "%.1f°".format(value),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp)
        )
    }
}

/**
 * The previewed pose dressed as a [MotionState] so [TailPositionView] can draw
 * it against the same travel it draws the live tail against. The device's own
 * limits are used when it has reported them; without a connection the editor
 * still has to show *something*, so it falls back to a stated ±90°.
 */
private fun previewMotionState(pose: TailPose, live: MotionState?): MotionState = MotionState(
    activePatternId = MotionPattern.KEYFRAME.id,
    params = emptyList(),
    encoderPositions = pose.motorTargets.toList(),
    gravityX = 0f,
    gravityY = 0f,
    gravityZ = -1f,
    xAxisMin = live?.xAxisMin ?: -FALLBACK_TRAVEL_DEGREES,
    xAxisMax = live?.xAxisMax ?: FALLBACK_TRAVEL_DEGREES,
    yAxisMin = live?.yAxisMin ?: -FALLBACK_TRAVEL_DEGREES,
    yAxisMax = live?.yAxisMax ?: FALLBACK_TRAVEL_DEGREES
)

/** A pose slider spans the axis's configured travel — what the device will allow. */
private fun axisRange(min: Float?, max: Float?): ClosedFloatingPointRange<Float> {
    val low = min ?: -FALLBACK_TRAVEL_DEGREES
    val high = max ?: FALLBACK_TRAVEL_DEGREES
    return if (high > low) low..high else -FALLBACK_TRAVEL_DEGREES..FALLBACK_TRAVEL_DEGREES
}

/**
 * Room to drag a keyframe past the current end of the sequence, without the
 * slider's scale jumping every time one is moved.
 */
private fun timeSliderMaxMs(timeMs: Int, durationMs: Int): Float =
    (maxOf(timeMs, durationMs) + TIME_SLIDER_HEADROOM_MS).toFloat()

private const val FALLBACK_TRAVEL_DEGREES = 90f
private const val TIME_SLIDER_HEADROOM_MS = 2000
