package com.tailapp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tailapp.model.ParamMetadata
import kotlinx.coroutines.delay

/**
 * A slider that rate-limits BLE writes and does not fight the device.
 *
 * Motion state notifies at ~20 Hz, so naively driving the thumb straight from
 * [value] makes it jump back mid-drag. While the user is dragging, the thumb
 * follows local input; incoming device values are adopted again once the
 * debounced write has been sent.
 */
@Composable
fun DebouncedSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    unit: String = "",
    valueFormat: String = "%.1f",
    debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {
    var isEditing by remember { mutableStateOf(false) }
    var localValue by remember { mutableFloatStateOf(value) }
    var pendingValue by remember { mutableFloatStateOf(Float.NaN) }

    // Adopt the device's value only when the user isn't mid-interaction.
    LaunchedEffect(value, isEditing) {
        if (!isEditing) localValue = value
    }

    LaunchedEffect(pendingValue) {
        val pending = pendingValue
        if (!pending.isNaN()) {
            delay(debounceMs)
            onValueChange(pending)
            pendingValue = Float.NaN
            isEditing = false
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (unit.isNotEmpty()) "${valueFormat.format(localValue)} $unit"
                else valueFormat.format(localValue),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = localValue.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = {
                isEditing = true
                localValue = it
                pendingValue = it
            },
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun EffectParameterSlider(
    param: ParamMetadata,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    DebouncedSlider(
        label = param.name,
        value = value,
        onValueChange = onValueChange,
        valueRange = param.min..param.max,
        modifier = modifier,
        unit = param.unit
    )
}

private const val DEFAULT_DEBOUNCE_MS = 250L
