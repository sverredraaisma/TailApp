package com.tailapp.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.Protocol
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.model.MotionPattern
import com.tailapp.model.ProfileSlot
import com.tailapp.ui.components.ProfileSlotRow
import com.tailapp.ui.components.SubsystemStatusCard
import com.tailapp.ui.theme.StatusGreen
import com.tailapp.ui.theme.StatusRed
import com.tailapp.viewmodel.DeviceOverviewViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceOverviewScreen(
    viewModel: DeviceOverviewViewModel,
    onNavigateToLed: () -> Unit,
    onNavigateToMotion: () -> Unit,
    onNavigateToAudio: () -> Unit,
    onNavigateToBeatLight: () -> Unit,
    onDisconnected: () -> Unit
) {
    val state by viewModel.deviceState.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface rejected commands (FF09) instead of silently assuming success.
    val lastResult = state.lastCommandResult
    LaunchedEffect(lastResult) {
        if (lastResult != null && !lastResult.isSuccess) {
            snackbarHostState.showSnackbar(
                "${lastResult.characteristicName} command 0x%02X rejected: ${lastResult.result.message}"
                    .format(lastResult.commandId)
            )
        }
    }

    // Taps are momentary events, so they belong in a snackbar rather than in state.
    LaunchedEffect(Unit) {
        viewModel.systemEvents.collect { event ->
            when (event) {
                SystemEvent.TAP_BASE -> snackbarHostState.showSnackbar("Tap detected (base)")
                SystemEvent.TAP_TIP -> snackbarHostState.showSnackbar("Tap detected (tip)")
                SystemEvent.CONFIG_CHANGED -> snackbarHostState.showSnackbar("Device config reloaded")
                // Deliberately not a snackbar: a stall latches every motor off
                // until the user acts, so it needs a banner that persists (see
                // below) rather than a message that disappears on its own.
                SystemEvent.STALL -> Unit
            }
        }
    }

    // Track whether we've ever been connected to avoid showing disconnect dialog on initial state
    var wasConnected by remember { mutableStateOf(false) }
    if (state.connectionState == ConnectionState.CONNECTED) {
        wasConnected = true
    }

    if (wasConnected && state.connectionState == ConnectionState.DISCONNECTED) {
        var showDialog by remember { mutableStateOf(true) }
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false; onDisconnected() },
                title = { Text("Disconnected") },
                text = { Text("The device has been disconnected.") },
                confirmButton = {
                    TextButton(onClick = { showDialog = false; onDisconnected() }) {
                        Text("Return to Scan")
                    }
                }
            )
        }
    }

    var renameTarget by remember { mutableStateOf<ProfileSlot?>(null) }
    renameTarget?.let { target ->
        RenameProfileDialog(
            slot = target,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renameProfile(target.index.toByte(), name)
                renameTarget = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tail Controller") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.disconnect(); onDisconnected() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.toggleFftStream() }) {
                        Text(if (isStreaming) "FFT ON" else "FFT OFF")
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
            val si = state.systemInfo

            // Protocol compatibility warning — the FF06 layout shifts between
            // protocol versions, so a mismatch means parsed values may be wrong.
            if (si != null && !si.isProtocolSupported) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Protocol mismatch", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Device speaks protocol v${si.protocolVersion}, this app targets " +
                                "v${Protocol.SUPPORTED_PROTOCOL_VERSION}. Some readings may be wrong.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // A stall drops every motor to freewheel and latches them off until
            // explicitly re-enabled. Without this the tail simply stops and the
            // app says nothing about why, or how to recover.
            if (si != null && si.motorsStalled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Motors stopped", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A motor stalled, so all motors were released and will stay off " +
                                "until re-enabled. Clear whatever is blocking the tail first.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.setMotorsEnabled(true) }) {
                            Text("Re-enable motors")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Subsystem Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Subsystems", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    SubsystemStatusCard("Bluetooth", "Connected", StatusGreen)
                    SubsystemStatusCard(
                        "Servos",
                        when {
                            si == null -> "Unknown"
                            si.motorsStalled -> "${si.servos.size} configured — stalled, off"
                            else -> "${si.servos.size} configured"
                        },
                        when {
                            si == null -> StatusRed
                            si.motorsStalled -> StatusRed
                            else -> StatusGreen
                        }
                    )
                    SubsystemStatusCard(
                        "LEDs",
                        state.ledState?.let {
                            "${it.totalLeds} LEDs, ${it.occupiedLayerIndices.size} layers"
                        } ?: "Unknown",
                        if (state.ledState != null) StatusGreen else StatusRed
                    )
                    SubsystemStatusCard(
                        "IMUs",
                        if (si != null) "${si.imus.size} sensors" else "Unknown",
                        if (si != null) StatusGreen else StatusRed
                    )
                    SubsystemStatusCard(
                        "I2C",
                        if (si != null) "OK" else "Unknown",
                        if (si != null) StatusGreen else StatusRed
                    )

                    if (si != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Firmware ${si.firmwareVersion} · protocol v${si.protocolVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val caps = si.capabilities
                        Text(
                            if (caps != null) {
                                "${caps.patternIds.size} patterns · ${caps.effectIds.size} effects · " +
                                    "up to ${caps.maxLayers} layers"
                            } else {
                                "Capabilities not reported — using built-in defaults"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Motion Summary
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Motion", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val ms = state.motionState
                    if (ms != null) {
                        val pattern = MotionPattern.fromId(ms.activePatternId)
                        Text("Pattern: ${pattern?.displayName ?: "Unknown"}")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Encoders: ${ms.encoderPositions.joinToString { "%.1f".format(it) + "°" }}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Gravity: %.2f, %.2f, %.2f g".format(ms.gravityX, ms.gravityY, ms.gravityZ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("No data", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // LED Summary
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LEDs", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val ls = state.ledState
                    if (ls != null) {
                        Text("${ls.totalLeds} LEDs across ${ls.numRings} rings")
                        // Cleared slots read back as effect 0xFF; don't list them as layers.
                        ls.occupiedLayerIndices.forEach { i ->
                            val layer = ls.layers[i]
                            val effectName = layer.effect?.displayName ?: "Effect ${layer.effectId}"
                            val blendName = layer.blend?.displayName ?: "Blend ${layer.blendMode}"
                            val enabled = if (layer.enabled) "" else " (disabled)"
                            Text(
                                "  Layer $i: $effectName / $blendName$enabled",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (ls.occupiedLayerIndices.isEmpty()) {
                            Text("No layers configured", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("No data", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Profile Management
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Profiles", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    val profiles = state.profiles.ifEmpty {
                        // Before the first FF08 read lands, still offer the slots.
                        List(Protocol.MAX_PROFILE_SLOTS) { ProfileSlot(it, occupied = false, name = null) }
                    }
                    profiles.forEachIndexed { i, slot ->
                        ProfileSlotRow(
                            slot = slot,
                            onSave = { viewModel.saveProfile(slot.index.toByte()) },
                            onLoad = { viewModel.loadProfile(slot.index.toByte()) },
                            onDelete = { viewModel.deleteProfile(slot.index.toByte()) },
                            onRename = { renameTarget = slot }
                        )
                        if (i < profiles.lastIndex) HorizontalDivider()
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Navigation buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onNavigateToMotion) { Text("Motion Config") }
                Button(onClick = onNavigateToLed) { Text("LED Config") }
                Button(onClick = onNavigateToAudio) { Text("Audio Config") }
                Button(onClick = onNavigateToBeatLight) { Text("BeatLight") }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { viewModel.refreshProfiles() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Refresh profile list") }
        }
    }
}

@Composable
private fun RenameProfileDialog(
    slot: ProfileSlot,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(slot.name.orEmpty()) }
    val byteLength = name.toByteArray(Charsets.UTF_8).size
    val tooLong = byteLength > Protocol.MAX_PROFILE_NAME_LEN

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename slot ${slot.index}") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Profile name") },
                    isError = tooLong,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Text(
                    "$byteLength / ${Protocol.MAX_PROFILE_NAME_LEN} bytes" +
                        if (tooLong) " — will be truncated" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tooLong) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
