package com.tailapp.ui.screen

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.model.Diagnostics
import com.tailapp.model.DiagnosticsBatteryLevel
import com.tailapp.model.MotorHealth
import com.tailapp.model.SensorHealth
import com.tailapp.model.TaskStackHealth
import com.tailapp.viewmodel.DiagnosticsViewModel

/**
 * The device-health screen (SYS-3), read from the FF0C diagnostics snapshot.
 *
 * It is a screen for a device that is already misbehaving, so it shows whatever
 * the device reported the moment it opens — the repository reads FF0C on connect
 * and keeps it live off the notify — and it degrades honestly: a firmware that
 * publishes only the version-1 core renders the health it does report and says
 * "not reported by this firmware" for the encoder and driver blocks rather than
 * inventing healthy zeros.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.deviceState.collectAsStateWithLifecycle()
    val diagnostics = state.diagnostics

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.refresh() }) { Text("Refresh") }
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
            if (diagnostics == null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No diagnostics yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Waiting for the first FF0C snapshot. The device publishes one a " +
                                "second while connected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@Column
            }

            HealthCard(diagnostics)
            Spacer(Modifier.height(16.dp))
            RenderCard(diagnostics)
            Spacer(Modifier.height(16.dp))
            TasksCard(diagnostics.tasks)
            Spacer(Modifier.height(16.dp))
            SensorsCard(diagnostics)
            Spacer(Modifier.height(16.dp))
            DriversCard(diagnostics)
        }
    }
}

@Composable
private fun HealthCard(d: Diagnostics) {
    DiagnosticsCard("Health") {
        StatRow("Uptime", formatUptime(d.uptimeSeconds))
        StatRow("Free heap", formatBytes(d.freeHeapBytes))
        // The min is the number that matters: the instantaneous figure never shows
        // how close to a reset a fragmenting device has already come.
        StatRow("Free heap (min)", formatBytes(d.minFreeHeapBytes))
        StatRow("Battery", formatBattery(d))
        StatRow("Stalls", d.stallCount.toString(), warn = d.stallCount > 0)
        StatRow("Command drops", d.commandQueueDropped.toString(), warn = d.commandQueueDropped > 0)
        StatRow("Motion overruns", d.motionOverruns.toString(), warn = d.motionOverruns > 0)
        StatRow("Render overruns", d.renderOverruns.toString(), warn = d.renderOverruns > 0)
    }
}

@Composable
private fun RenderCard(d: Diagnostics) {
    DiagnosticsCard("Render") {
        StatRow("Frame rate", "${d.frameRateHz} Hz")
        // The budget is what says whether there is headroom to raise the rate; a
        // mean pressing against it, or a rising skip count, says there is not.
        val budgetMicros = if (d.frameRateHz > 0) 1_000_000L / d.frameRateHz else 0L
        StatRow("Frame budget", formatMicros(budgetMicros))
        StatRow("Last frame", formatMicros(d.lastFrameMicros))
        StatRow(
            "Mean frame",
            formatMicros(d.meanFrameMicros),
            warn = budgetMicros > 0 && d.meanFrameMicros > budgetMicros
        )
        StatRow("Max frame", formatMicros(d.maxFrameMicros))
        StatRow("Frames skipped", d.framesSkipped.toString(), warn = d.framesSkipped > 0)
    }
}

@Composable
private fun TasksCard(tasks: List<TaskStackHealth>) {
    DiagnosticsCard("Task stacks") {
        Text(
            "Stack headroom left, in words. A 0 means the task has not registered " +
                "itself yet, not that it ran out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        tasks.forEach { task ->
            StatRow(
                task.name,
                if (task.notRegistered) "0 (not registered)" else "${task.freeWords} words"
            )
        }
    }
}

@Composable
private fun SensorsCard(d: Diagnostics) {
    DiagnosticsCard("Sensors") {
        Text("IMUs", style = MaterialTheme.typography.titleSmall)
        d.imus.forEachIndexed { i, imu ->
            SensorRow(imuName(i, d.imus.size), imu)
        }

        Spacer(Modifier.height(12.dp))
        Text("Encoders", style = MaterialTheme.typography.titleSmall)
        val encoders = d.encoders
        if (encoders == null) {
            // Null is "this firmware predates encoder health", not "every encoder
            // is fine" — and it must not read like the latter.
            NotReported()
        } else {
            encoders.forEachIndexed { i, enc -> SensorRow("Encoder $i", enc) }
            d.rehomeCount?.let { StatRow("Re-homes", it.toString(), warn = it > 0) }
        }
    }
}

@Composable
private fun DriversCard(d: Diagnostics) {
    DiagnosticsCard("Drivers") {
        val motors = d.motors
        if (motors == null) {
            NotReported()
            return@DiagnosticsCard
        }
        motors.forEachIndexed { i, motor -> MotorRow("Motor $i", motor) }
        Spacer(Modifier.height(8.dp))
        d.driverLostWrites?.let {
            StatRow("Lost writes", it.toString(), warn = it > 0)
        }
        d.driverReadFailures?.let {
            StatRow("Read failures", it.toString(), warn = it > 0)
        }
    }
}

@Composable
private fun SensorRow(label: String, sensor: SensorHealth) {
    val value = when {
        sensor.disabled -> "disabled after ${sensor.failures} failures"
        sensor.failures > 0 -> "${sensor.failures} failures"
        else -> "OK"
    }
    StatRow(label, value, warn = sensor.disabled || sensor.failures > 0)
}

@Composable
private fun MotorRow(label: String, motor: MotorHealth) {
    if (motor.isHealthy) {
        StatRow(label, "OK")
    } else {
        // The decoded labels are the point: a raw DRV_STATUS mask tells a user
        // nothing about whether a motor is cooking or miswired.
        StatRow(label, motor.decoded.joinToString(", ") { it.label }, warn = true)
    }
}

@Composable
private fun StatRow(label: String, value: String, warn: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun NotReported() {
    Text(
        "Not reported by this firmware.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DiagnosticsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private fun imuName(index: Int, count: Int): String = when {
    count == 2 && index == 0 -> "Base"
    count == 2 && index == 1 -> "Tip"
    else -> "IMU $index"
}

private fun formatUptime(seconds: Long): String {
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3_600
    val m = (seconds % 3_600) / 60
    val s = seconds % 60
    return buildString {
        if (d > 0) append("${d}d ")
        if (d > 0 || h > 0) append("${h}h ")
        if (d > 0 || h > 0 || m > 0) append("${m}m ")
        append("${s}s")
    }
}

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1024) "%.1f KB".format(bytes / 1024.0) else "$bytes B"

private fun formatMicros(micros: Long): String =
    if (micros >= 1000) "%.2f ms".format(micros / 1000.0) else "$micros µs"

private fun formatBattery(d: Diagnostics): String {
    val level = when (d.batteryLevel) {
        DiagnosticsBatteryLevel.UNKNOWN -> "unknown"
        DiagnosticsBatteryLevel.NORMAL -> "normal"
        DiagnosticsBatteryLevel.LOW -> "low"
        DiagnosticsBatteryLevel.CRITICAL -> "critical"
    }
    val percent = d.batteryPercent?.let { "$it%" } ?: "--"
    val volts = if (d.batteryMillivolts > 0) " · %.2f V".format(d.batteryMillivolts / 1000.0) else ""
    return "$percent ($level)$volts"
}
