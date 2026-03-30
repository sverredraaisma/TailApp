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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tailapp.model.ParamMetadata
import kotlinx.coroutines.delay

@Composable
fun EffectParameterSlider(
    param: ParamMetadata,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var localValue by remember(value) { mutableFloatStateOf(value) }
    var pendingValue by remember { mutableFloatStateOf(Float.NaN) }

    // 300ms debounce for BLE writes
    LaunchedEffect(pendingValue) {
        if (!pendingValue.isNaN()) {
            delay(300)
            onValueChange(pendingValue)
            pendingValue = Float.NaN
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = param.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (param.unit.isNotEmpty()) "${"%.1f".format(localValue)} ${param.unit}"
                else "%.1f".format(localValue),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = localValue,
            onValueChange = {
                localValue = it
                pendingValue = it
            },
            valueRange = param.min..param.max,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
