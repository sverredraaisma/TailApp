package com.tailapp.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.model.BehaviorRuntime
import com.tailapp.model.BehaviorStateConfig
import com.tailapp.model.BehaviorTable
import com.tailapp.model.BehaviorTriggerConfig
import com.tailapp.model.BehaviorTriggerSource
import com.tailapp.model.MotionPattern
import com.tailapp.ui.components.DebouncedSlider
import com.tailapp.viewmodel.BehaviorConfigViewModel
import com.tailapp.viewmodel.BehaviorTransition

/**
 * The behavior-engine editor (MOT-6): the moods the tail can be in, the rules
 * that move it between them, and what it is doing right now.
 *
 * Two things this screen has to say out loud, because neither is guessable from
 * a tail that is not moving. The engine is *suspended* while the app streams
 * motion targets or while a stall is latched — that is precedence working, not a
 * fault — and the device publishes no read of its table, so what is listed here
 * is what this phone last sent rather than what the tail is certainly holding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BehaviorConfigScreen(
    viewModel: BehaviorConfigViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.deviceState.collectAsStateWithLifecycle()
    val table by viewModel.table.collectAsStateWithLifecycle()
    val transitions by viewModel.transitions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val runtime = state.motionState?.behavior
    val patterns = state.capabilities.patterns.ifEmpty { MotionPattern.entries }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Behavior Engine") },
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
            LiveEngineCard(
                runtime = runtime,
                table = table,
                onEnabledChange = viewModel::setEngineEnabled
            )

            Spacer(Modifier.height(16.dp))

            TransitionsCard(transitions = transitions, table = table)

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("States", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Each state names a pattern and the parameters it runs with. A " +
                            "parameter that is not overridden keeps the pattern's own " +
                            "default, which is not the same as setting it to zero.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    table.states.forEachIndexed { index, stateConfig ->
                        BehaviorStateRow(
                            index = index,
                            state = stateConfig,
                            table = table,
                            patterns = patterns,
                            isActive = runtime?.stateIndex == index,
                            onEdit = { transform -> viewModel.editState(index, transform) },
                            onPreview = { viewModel.previewState(index) }
                        )
                        if (index < table.states.lastIndex) HorizontalDivider()
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Triggers", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Rows are evaluated in order and the first satisfied one wins, so " +
                            "a rule that should outrank another belongs higher up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    table.triggers.forEachIndexed { index, trigger ->
                        BehaviorTriggerRow(
                            index = index,
                            trigger = trigger,
                            table = table,
                            onEdit = { transform -> viewModel.editTrigger(index, transform) },
                            onDisable = { viewModel.disableTrigger(index) }
                        )
                        if (index < table.triggers.lastIndex) HorizontalDivider()
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            val problems = table.problems()
            if (problems.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Problems", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        problems.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                "The device has no read for its behavior table, so this is what this " +
                    "phone last sent it — starting from the firmware's factory table. " +
                    "Send it again after pairing with a different tail.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::pushTable,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Send whole table to tail") }
            Spacer(Modifier.height(8.dp))
            var confirmReset by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    if (confirmReset) {
                        viewModel.resetToFirmwareDefaults()
                        confirmReset = false
                    } else {
                        confirmReset = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (confirmReset) "Tap again to discard your table" else "Reset to firmware defaults")
            }
        }
    }
}

/**
 * The engine switch, what the tail is doing, and why.
 *
 * The reason byte is the whole point of the block: a tail that suddenly gets
 * excited and cannot say what excited it is not debuggable, and the firmware
 * went to the trouble of reporting it.
 */
@Composable
private fun LiveEngineCard(
    runtime: BehaviorRuntime?,
    table: BehaviorTable,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Behavior engine", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A mood above the pattern: the tail chooses its own pattern from " +
                            "taps, handling and the music the phone streams.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = runtime?.engineEnabled ?: table.engineEnabled,
                    onCheckedChange = onEnabledChange
                )
            }

            Spacer(Modifier.height(12.dp))

            if (runtime == null) {
                Text(
                    "This firmware does not report the engine's state — it predates the " +
                        "behavior block on FF02. Edits are still sent; the tail simply " +
                        "cannot say what it is doing with them.",
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }

            Text(
                "Active state: ${table.stateName(runtime.stateIndex)}",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "Changed because: ${runtime.reason.description}",
                style = MaterialTheme.typography.bodyMedium
            )
            val driving = runtime.drivingPattern?.displayName
                ?: runtime.drivingPatternId?.let { "unknown pattern 0x%02X".format(it) }
            Text(
                if (driving != null) "Driving: $driving" else "Not driving the motors",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (runtime.suspended) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (runtime.stallHeld) "Suspended: motors latched off" else "Suspended: the app is driving",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            if (runtime.stallHeld) {
                                "A stall latched every motor off. Nothing moves — engine " +
                                    "included — until the motors are re-enabled from the " +
                                    "motion screen."
                            } else {
                                "Streamed motion targets outrank the engine, the same way " +
                                    "direct pixel mode outranks the LED compositor. The " +
                                    "manual-drive pad and the composer's motion " +
                                    "choreography both stream; the engine resumes on its " +
                                    "own once they stop."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransitionsCard(transitions: List<BehaviorTransition>, table: BehaviorTable) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Recent transitions", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (transitions.isEmpty()) {
                Text(
                    "Nothing yet. Transitions appear here as the tail makes them, newest " +
                        "first, with the rule that caused each one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            transitions.forEach { transition ->
                Text(
                    "${table.stateName(transition.fromState)} → " +
                        "${table.stateName(transition.toState)} · ${transition.reason.description}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BehaviorStateRow(
    index: Int,
    state: BehaviorStateConfig,
    table: BehaviorTable,
    patterns: List<MotionPattern>,
    isActive: Boolean,
    onEdit: ((BehaviorStateConfig) -> BehaviorStateConfig) -> Unit,
    onPreview: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                table.stateName(index) + if (isActive) " · active" else "",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                (state.pattern?.displayName ?: "unknown pattern") +
                    if (state.enabled) "" else " · disabled",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onPreview) { Text("Preview") }
        IconButton(onClick = { expanded = !expanded }) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Expand")
        }
    }

    AnimatedVisibility(visible = expanded) {
        Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { on -> onEdit { it.copy(enabled = on) } }
                )
            }

            Text("Pattern", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                patterns.forEach { pattern ->
                    FilterChip(
                        selected = state.patternId == pattern.id,
                        onClick = { onEdit { it.copy(patternId = pattern.id) } },
                        label = { Text(pattern.displayName) }
                    )
                }
            }

            DebouncedSlider(
                label = "Minimum dwell",
                value = state.minDwellMs.toFloat(),
                onValueChange = { ms -> onEdit { it.copy(minDwellMs = ms.toInt()) } },
                valueRange = 0f..10000f,
                unit = "ms",
                valueFormat = "%.0f"
            )
            Text(
                "No trigger may leave the state before this — it is what stops a " +
                    "flickering rule from strobing the tail between two moods.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DebouncedSlider(
                label = "Timeout (0 = never)",
                value = state.timeoutMs.toFloat(),
                onValueChange = { ms -> onEdit { it.copy(timeoutMs = ms.toInt()) } },
                valueRange = 0f..30000f,
                unit = "ms",
                valueFormat = "%.0f"
            )

            Text("Falls back to", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                table.states.indices.forEach { target ->
                    FilterChip(
                        selected = state.fallbackState == target,
                        onClick = { onEdit { it.copy(fallbackState = target) } },
                        label = { Text(table.stateName(target)) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Parameter overrides", style = MaterialTheme.typography.bodyMedium)
            val params = state.pattern?.params.orEmpty()
            if (params.isEmpty()) {
                Text(
                    "This pattern takes no parameters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            params.forEach { param ->
                val overridden = state.overrides(param.id)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = overridden,
                        onCheckedChange = { on -> onEdit { it.withOverride(param.id, on) } }
                    )
                    Text(
                        if (overridden) param.name else "${param.name} · pattern default",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (overridden) {
                    DebouncedSlider(
                        label = param.name,
                        value = state.params.getOrElse(param.id) { param.default },
                        onValueChange = { v -> onEdit { it.withParam(param.id, v) } },
                        valueRange = param.min..param.max,
                        unit = param.unit
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BehaviorTriggerRow(
    index: Int,
    trigger: BehaviorTriggerConfig,
    table: BehaviorTable,
    onEdit: ((BehaviorTriggerConfig) -> BehaviorTriggerConfig) -> Unit,
    onDisable: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("$index · ${trigger.source.displayName}", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (trigger.isEnabled) {
                    "→ ${table.stateName(trigger.toState)}" +
                        if (trigger.fromMask == 0) " · from any state" else ""
                } else {
                    "off"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { expanded = !expanded }) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Expand")
        }
    }

    AnimatedVisibility(visible = expanded) {
        Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
            Text("Watches", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BehaviorTriggerSource.entries.forEach { source ->
                    FilterChip(
                        selected = trigger.source == source,
                        onClick = { onEdit { it.copy(source = source) } },
                        label = { Text(source.displayName) }
                    )
                }
            }
            if (trigger.source.needsAudioStream) {
                Text(
                    "The device has no microphone: this row only ever sees what the phone " +
                        "streams on FF05, and reads as nothing while it is not streaming.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("Goes to", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                table.states.indices.forEach { target ->
                    FilterChip(
                        selected = trigger.toState == target,
                        onClick = { onEdit { it.copy(toState = target) } },
                        label = { Text(table.stateName(target)) }
                    )
                }
            }

            Text("Only from", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                table.states.indices.forEach { from ->
                    FilterChip(
                        // An empty mask means "any state", so the first tap has
                        // to narrow to that one state rather than clear its bit
                        // out of a mask that does not have bits yet.
                        selected = trigger.fromMask != 0 && trigger.firesFrom(from),
                        onClick = {
                            onEdit { current ->
                                if (current.fromMask == 0) {
                                    current.copy(fromMask = 1 shl from)
                                } else {
                                    current.withFromState(from, !current.firesFrom(from))
                                }
                            }
                        },
                        label = { Text(table.stateName(from)) }
                    )
                }
            }
            if (trigger.fromMask == 0) {
                Text(
                    "Nothing selected — the row fires from any state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (trigger.source.isEvent) {
                DebouncedSlider(
                    label = "Taps required",
                    value = trigger.count.toFloat(),
                    onValueChange = { c -> onEdit { it.copy(count = c.toInt()) } },
                    valueRange = 0f..4f,
                    valueFormat = "%.0f"
                )
                DebouncedSlider(
                    label = "Within",
                    value = trigger.windowMs.toFloat(),
                    onValueChange = { ms -> onEdit { it.copy(windowMs = ms.toInt()) } },
                    valueRange = 0f..3000f,
                    unit = "ms",
                    valueFormat = "%.0f"
                )
            } else if (trigger.isEnabled) {
                DebouncedSlider(
                    label = "Threshold",
                    value = trigger.threshold,
                    onValueChange = { v -> onEdit { it.copy(threshold = v) } },
                    valueRange = trigger.source.thresholdRange,
                    unit = trigger.source.thresholdUnit,
                    valueFormat = "%.2f"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fire below the threshold", modifier = Modifier.weight(1f))
                    Switch(
                        checked = trigger.fireBelowThreshold,
                        onCheckedChange = { on -> onEdit { it.copy(fireBelowThreshold = on) } }
                    )
                }
                DebouncedSlider(
                    label = "Must hold for",
                    value = trigger.windowMs.toFloat(),
                    onValueChange = { ms -> onEdit { it.copy(windowMs = ms.toInt()) } },
                    valueRange = 0f..10000f,
                    unit = "ms",
                    valueFormat = "%.0f"
                )
                Text(
                    "A level that keeps crossing its threshold never accumulates this much " +
                        "continuous truth, so it never fires at all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (trigger.isEnabled) {
                TextButton(onClick = onDisable) { Text("Turn this rule off") }
            }
        }
    }
}
