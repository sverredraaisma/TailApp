package com.tailapp.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card

import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.model.MotionPattern
import com.tailapp.ui.components.EffectParameterSlider
import com.tailapp.viewmodel.MotionConfigViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionConfigScreen(
    viewModel: MotionConfigViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.deviceState.collectAsStateWithLifecycle()
    val motionState = state.motionState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Motion Config") },
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
            // Pattern Selection
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Motion Pattern", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        MotionPattern.entries.forEach { pattern ->
                            FilterChip(
                                selected = motionState?.activePatternId == pattern.id,
                                onClick = { viewModel.selectPattern(pattern.id) },
                                label = { Text(pattern.displayName) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Pattern Parameters
            if (motionState != null) {
                val activePattern = MotionPattern.fromId(motionState.activePatternId)
                if (activePattern != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("${activePattern.displayName} Parameters", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            activePattern.params.forEach { param ->
                                EffectParameterSlider(
                                    param = param,
                                    value = motionState.params.getOrElse(param.id) { param.default },
                                    onValueChange = { viewModel.setPatternParam(param.id.toByte(), it) }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Encoder Positions
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Current Positions", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        motionState.encoderPositions.forEachIndexed { i, pos ->
                            Text("Encoder $i: ${"%.1f".format(pos)}\u00B0")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Axis Limits
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Axis Limits", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        Text("X Axis: ${"%.0f".format(motionState.xAxisMin)}\u00B0 to ${"%.0f".format(motionState.xAxisMax)}\u00B0")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Min", modifier = Modifier.padding(end = 8.dp))
                            Slider(
                                value = motionState.xAxisMin,
                                onValueChange = { viewModel.setAxisLimits(0, it, motionState.xAxisMax) },
                                valueRange = -180f..0f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Max", modifier = Modifier.padding(end = 8.dp))
                            Slider(
                                value = motionState.xAxisMax,
                                onValueChange = { viewModel.setAxisLimits(0, motionState.xAxisMin, it) },
                                valueRange = 0f..180f,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Text("Y Axis: ${"%.0f".format(motionState.yAxisMin)}\u00B0 to ${"%.0f".format(motionState.yAxisMax)}\u00B0")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Min", modifier = Modifier.padding(end = 8.dp))
                            Slider(
                                value = motionState.yAxisMin,
                                onValueChange = { viewModel.setAxisLimits(1, it, motionState.yAxisMax) },
                                valueRange = -180f..0f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Max", modifier = Modifier.padding(end = 8.dp))
                            Slider(
                                value = motionState.yAxisMax,
                                onValueChange = { viewModel.setAxisLimits(1, motionState.yAxisMin, it) },
                                valueRange = 0f..180f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Servo Configuration
            val systemInfo = state.systemInfo
            if (systemInfo != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Servo Configuration", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        systemInfo.servos.forEachIndexed { i, servo ->
                            var expanded by remember { mutableStateOf(false) }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Servo $i",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${if (servo.axis == 0) "X" else "Y"}-${if (servo.half == 0) "First" else "Second"}${if (servo.invert) " (inv)" else ""}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                IconButton(onClick = { expanded = !expanded }) {
                                    Icon(
                                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        "Expand"
                                    )
                                }
                            }

                            AnimatedVisibility(visible = expanded) {
                                Column(modifier = Modifier.padding(start = 16.dp)) {
                                    // Axis
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Axis: ", modifier = Modifier.padding(end = 8.dp))
                                        FilterChip(
                                            selected = servo.axis == 0,
                                            onClick = {
                                                viewModel.setServoConfig(
                                                    i.toByte(), 0, servo.half.toByte(),
                                                    if (servo.invert) 1 else 0
                                                )
                                            },
                                            label = { Text("X") },
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                        FilterChip(
                                            selected = servo.axis == 1,
                                            onClick = {
                                                viewModel.setServoConfig(
                                                    i.toByte(), 1, servo.half.toByte(),
                                                    if (servo.invert) 1 else 0
                                                )
                                            },
                                            label = { Text("Y") }
                                        )
                                    }
                                    // Half
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Half: ", modifier = Modifier.padding(end = 8.dp))
                                        FilterChip(
                                            selected = servo.half == 0,
                                            onClick = {
                                                viewModel.setServoConfig(
                                                    i.toByte(), servo.axis.toByte(), 0,
                                                    if (servo.invert) 1 else 0
                                                )
                                            },
                                            label = { Text("First") },
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                        FilterChip(
                                            selected = servo.half == 1,
                                            onClick = {
                                                viewModel.setServoConfig(
                                                    i.toByte(), servo.axis.toByte(), 1,
                                                    if (servo.invert) 1 else 0
                                                )
                                            },
                                            label = { Text("Second") }
                                        )
                                    }
                                    // Invert
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Invert: ", modifier = Modifier.padding(end = 8.dp))
                                        Switch(
                                            checked = servo.invert,
                                            onCheckedChange = {
                                                viewModel.setServoConfig(
                                                    i.toByte(), servo.axis.toByte(),
                                                    servo.half.toByte(), if (it) 1 else 0
                                                )
                                            }
                                        )
                                    }
                                    // PID Gains
                                    Text("PID Gains", style = MaterialTheme.typography.bodyMedium)
                                    Text("Kp: ${"%.2f".format(servo.pid.kp)}")
                                    Slider(
                                        value = servo.pid.kp,
                                        onValueChange = {
                                            viewModel.setPidGains(i.toByte(), it, servo.pid.ki, servo.pid.kd)
                                        },
                                        valueRange = 0f..10f
                                    )
                                    Text("Ki: ${"%.3f".format(servo.pid.ki)}")
                                    Slider(
                                        value = servo.pid.ki,
                                        onValueChange = {
                                            viewModel.setPidGains(i.toByte(), servo.pid.kp, it, servo.pid.kd)
                                        },
                                        valueRange = 0f..1f
                                    )
                                    Text("Kd: ${"%.2f".format(servo.pid.kd)}")
                                    Slider(
                                        value = servo.pid.kd,
                                        onValueChange = {
                                            viewModel.setPidGains(i.toByte(), servo.pid.kp, servo.pid.ki, it)
                                        },
                                        valueRange = 0f..5f
                                    )
                                }
                            }
                            if (i < systemInfo.servos.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Calibrate Zero
            var showCalibDialog by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showCalibDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Calibrate Zero Position") }

            if (showCalibDialog) {
                AlertDialog(
                    onDismissRequest = { showCalibDialog = false },
                    title = { Text("Calibrate Zero") },
                    text = { Text("Position the tail at its desired neutral position before calibrating. This sets the current encoder positions as the zero reference.") },
                    confirmButton = {
                        Button(onClick = {
                            viewModel.calibrateZero()
                            showCalibDialog = false
                        }) { Text("Calibrate") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCalibDialog = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}
