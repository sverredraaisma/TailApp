package com.tailapp.ui.screen

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.ble.ConnectionState
import com.tailapp.model.MotionPattern
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
    onDisconnected: () -> Unit
) {
    val state by viewModel.deviceState.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()

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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Subsystem Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Subsystems", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    val si = state.systemInfo
                    SubsystemStatusCard("Bluetooth", "Connected", StatusGreen)
                    SubsystemStatusCard(
                        "Servos",
                        if (si != null) "${si.servos.size} configured" else "Unknown",
                        if (si != null) StatusGreen else StatusRed
                    )
                    SubsystemStatusCard(
                        "LEDs",
                        state.ledState?.let { "${it.totalLeds} LEDs, ${it.layers.size} layers" } ?: "Unknown",
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
                            "Firmware ${si.firmwareVersion}",
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
                            "Encoders: ${ms.encoderPositions.joinToString { "%.1f".format(it) + "\u00B0" }}",
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
                        ls.layers.forEachIndexed { i, layer ->
                            val effectName = layer.effect?.displayName ?: "Effect ${layer.effectId}"
                            val blendName = layer.blend?.displayName ?: "Blend ${layer.blendMode}"
                            val enabled = if (layer.enabled) "" else " (disabled)"
                            Text(
                                "  Layer $i: $effectName / $blendName$enabled",
                                style = MaterialTheme.typography.bodySmall
                            )
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (slot in 0..3) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Slot $slot", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = { viewModel.saveProfile(slot.toByte()) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Save") }
                                OutlinedButton(
                                    onClick = { viewModel.loadProfile(slot.toByte()) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Load") }
                            }
                        }
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
            }
        }
    }
}
