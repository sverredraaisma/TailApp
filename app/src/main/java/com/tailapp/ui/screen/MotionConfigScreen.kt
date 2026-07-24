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
import com.tailapp.model.MotionLimits
import com.tailapp.model.MotionPattern
import com.tailapp.model.ServoConfig
import com.tailapp.ui.components.DebouncedSlider
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
    val capabilities = state.capabilities
    // Offer only the patterns the connected firmware reports.
    val availablePatterns = capabilities.patterns.ifEmpty { MotionPattern.entries }

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
                        availablePatterns.forEach { pattern ->
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
                            Text(
                                "${activePattern.displayName} Parameters",
                                style = MaterialTheme.typography.titleMedium
                            )
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

                // Live telemetry (FF02 notifies at ~20 Hz)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Current Positions", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        motionState.encoderPositions.forEachIndexed { i, pos ->
                            Text("Encoder $i: ${"%.1f".format(pos)}°")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Gravity", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "X %.2f · Y %.2f · Z %.2f g".format(
                                motionState.gravityX, motionState.gravityY, motionState.gravityZ
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Axis Limits — debounced so dragging doesn't flood the GATT queue.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Axis Limits", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        Text("X Axis", style = MaterialTheme.typography.bodyMedium)
                        DebouncedSlider(
                            label = "Min",
                            value = motionState.xAxisMin,
                            onValueChange = { viewModel.setAxisLimits(0, it, motionState.xAxisMax) },
                            valueRange = -180f..0f,
                            unit = "°",
                            valueFormat = "%.0f"
                        )
                        DebouncedSlider(
                            label = "Max",
                            value = motionState.xAxisMax,
                            onValueChange = { viewModel.setAxisLimits(0, motionState.xAxisMin, it) },
                            valueRange = 0f..180f,
                            unit = "°",
                            valueFormat = "%.0f"
                        )

                        Spacer(Modifier.height(8.dp))

                        Text("Y Axis", style = MaterialTheme.typography.bodyMedium)
                        DebouncedSlider(
                            label = "Min",
                            value = motionState.yAxisMin,
                            onValueChange = { viewModel.setAxisLimits(1, it, motionState.yAxisMax) },
                            valueRange = -180f..0f,
                            unit = "°",
                            valueFormat = "%.0f"
                        )
                        DebouncedSlider(
                            label = "Max",
                            value = motionState.yAxisMax,
                            onValueChange = { viewModel.setAxisLimits(1, motionState.yAxisMin, it) },
                            valueRange = 0f..180f,
                            unit = "°",
                            valueFormat = "%.0f"
                        )
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
                        Text(
                            "Changes apply immediately — no reboot needed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))

                        systemInfo.servos.forEachIndexed { i, servo ->
                            ServoConfigRow(
                                index = i,
                                servo = servo,
                                // Null on firmware that predates the FF06 motion
                                // block; the limit controls are hidden rather
                                // than shown against invented values.
                                limits = systemInfo.motion?.limits?.getOrNull(i),
                                viewModel = viewModel
                            )
                            if (i < systemInfo.servos.lastIndex) HorizontalDivider()
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // IMU tap detection
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Tap Detection", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        systemInfo.imus.forEachIndexed { i, imu ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(if (i == 0) "Base IMU" else "Tip IMU")
                                    Text(
                                        "Mux channel ${imu.muxChannel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = imu.tapEnabled,
                                    onCheckedChange = { viewModel.setImuTap(i.toByte(), it) }
                                )
                            }
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

@Composable
private fun ServoConfigRow(
    index: Int,
    servo: ServoConfig,
    limits: MotionLimits?,
    viewModel: MotionConfigViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    val invertByte: Byte = if (servo.invert) 1 else 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Servo $index",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${if (servo.axis == 0) "X" else "Y"}-${if (servo.half == 0) "First" else "Second"}" +
                (if (servo.invert) " (inv)" else "") + " · mux ${servo.muxChannel}",
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
                        viewModel.setServoConfig(index.toByte(), 0, servo.half.toByte(), invertByte)
                    },
                    label = { Text("X") },
                    modifier = Modifier.padding(end = 4.dp)
                )
                FilterChip(
                    selected = servo.axis == 1,
                    onClick = {
                        viewModel.setServoConfig(index.toByte(), 1, servo.half.toByte(), invertByte)
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
                        viewModel.setServoConfig(index.toByte(), servo.axis.toByte(), 0, invertByte)
                    },
                    label = { Text("First") },
                    modifier = Modifier.padding(end = 4.dp)
                )
                FilterChip(
                    selected = servo.half == 1,
                    onClick = {
                        viewModel.setServoConfig(index.toByte(), servo.axis.toByte(), 1, invertByte)
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
                            index.toByte(), servo.axis.toByte(), servo.half.toByte(),
                            if (it) 1 else 0
                        )
                    }
                )
            }
            // Encoder mux channel — protocol v1 added this as the optional 6th byte
            // of MCMD_SET_SERVO_CFG; before that it could not be set over BLE at all.
            DebouncedSlider(
                label = "Encoder mux channel",
                value = servo.muxChannel.toFloat(),
                onValueChange = {
                    viewModel.setServoConfig(
                        index.toByte(), servo.axis.toByte(), servo.half.toByte(),
                        invertByte, it.toInt().toByte()
                    )
                },
                valueRange = 0f..7f,
                valueFormat = "%.0f"
            )

            // Motion limits — what actually shapes movement on this firmware.
            // The steppers run open-loop through a jerk-limited profile, so
            // these are the real controls; PID below is vestigial.
            if (limits != null) {
                Spacer(Modifier.height(8.dp))
                Text("Motion limits", style = MaterialTheme.typography.bodyMedium)
                DebouncedSlider(
                    label = "Max velocity (deg/s)",
                    value = limits.maxVelocity,
                    onValueChange = {
                        viewModel.setMotionLimits(
                            index.toByte(), it, limits.maxAcceleration, limits.maxJerk,
                            limits.stallThreshold.toByte()
                        )
                    },
                    valueRange = 0f..1440f,
                    valueFormat = "%.0f"
                )
                DebouncedSlider(
                    label = "Max acceleration (deg/s²)",
                    value = limits.maxAcceleration,
                    onValueChange = {
                        viewModel.setMotionLimits(
                            index.toByte(), limits.maxVelocity, it, limits.maxJerk,
                            limits.stallThreshold.toByte()
                        )
                    },
                    valueRange = 0f..10000f,
                    valueFormat = "%.0f"
                )
                DebouncedSlider(
                    label = "Max jerk (deg/s³)",
                    value = limits.maxJerk,
                    onValueChange = {
                        viewModel.setMotionLimits(
                            index.toByte(), limits.maxVelocity, limits.maxAcceleration, it,
                            limits.stallThreshold.toByte()
                        )
                    },
                    valueRange = 0f..100000f,
                    valueFormat = "%.0f"
                )
                DebouncedSlider(
                    label = if (limits.stallDetectionEnabled) {
                        "Stall sensitivity (SGTHRS)"
                    } else {
                        "Stall sensitivity (off)"
                    },
                    value = limits.stallThreshold.toFloat(),
                    onValueChange = {
                        viewModel.setMotionLimits(
                            index.toByte(), limits.maxVelocity, limits.maxAcceleration,
                            limits.maxJerk, it.toInt().toByte()
                        )
                    },
                    valueRange = 0f..255f,
                    valueFormat = "%.0f"
                )
                Text(
                    "0 disables stall detection. Too high freewheels the tail at rest; " +
                        "too low never catches a real jam — tune against your mechanics.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Vestigial: the motors are TMC2209 steppers driven open-loop as of
            // firmware d4973bf, so these gains are stored and reported but no
            // longer affect motion. Collapsed rather than removed because the
            // firmware still accepts them and old profiles carry values.
            var showLegacy by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showLegacy = !showLegacy }) {
                Text(if (showLegacy) "Hide legacy PID gains" else "Show legacy PID gains")
            }
            AnimatedVisibility(visible = showLegacy) {
                Column {
                    Text(
                        "Not used for control — the motors run open-loop. Kept for " +
                            "compatibility with the FF06 layout.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    DebouncedSlider(
                        label = "Kp",
                        value = servo.pid.kp,
                        onValueChange = { viewModel.setPidGains(index.toByte(), it, servo.pid.ki, servo.pid.kd) },
                        valueRange = 0f..10f,
                        valueFormat = "%.2f"
                    )
                    DebouncedSlider(
                        label = "Ki",
                        value = servo.pid.ki,
                        onValueChange = { viewModel.setPidGains(index.toByte(), servo.pid.kp, it, servo.pid.kd) },
                        valueRange = 0f..1f,
                        valueFormat = "%.3f"
                    )
                    DebouncedSlider(
                        label = "Kd",
                        value = servo.pid.kd,
                        onValueChange = { viewModel.setPidGains(index.toByte(), servo.pid.kp, servo.pid.ki, it) },
                        valueRange = 0f..5f,
                        valueFormat = "%.2f"
                    )
                }
            }
        }
    }
}
