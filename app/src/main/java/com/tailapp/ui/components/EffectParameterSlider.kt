package com.tailapp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
 *
 * **The pending write survives leaving the screen.** The debounce lives in a
 * `LaunchedEffect`, so navigating back inside the debounce window would cancel
 * it: the UI had shown the new value, the device never got it, and nothing said
 * so. [DisposableEffect] flushes whatever is still pending on the way out, so
 * the last edit is always the one that lands.
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

    val range = remember(valueRange, value) { safeRange(valueRange, value) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    // Adopt the device's value only when the user isn't mid-interaction.
    LaunchedEffect(value, isEditing) {
        if (!isEditing) localValue = value
    }

    LaunchedEffect(pendingValue) {
        val pending = pendingValue
        if (!pending.isNaN()) {
            delay(debounceMs)
            currentOnValueChange(pending)
            pendingValue = Float.NaN
            isEditing = false
        }
    }

    // Last edit wins even if the screen is gone before the debounce elapses.
    DisposableEffect(Unit) {
        onDispose {
            val pending = pendingValue
            if (!pending.isNaN()) currentOnValueChange(pending)
        }
    }

    val readout = if (unit.isNotEmpty()) "${valueFormat.format(localValue)} $unit"
    else valueFormat.format(localValue)

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(text = readout, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = localValue.coerceIn(range.start, range.endInclusive),
            onValueChange = {
                isEditing = true
                localValue = it
                pendingValue = it
            },
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = readout
                }
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

/**
 * A range a slider can actually place a thumb in.
 *
 * A schema (or a device) that reports `min == max` — or reports them inverted —
 * yields a zero-width range, and the thumb fraction is then `0/0`: NaN, which
 * Compose draws as a thumb pinned at the left edge of a control that cannot
 * move. Widening by [DEGENERATE_EPSILON] keeps the control inert-looking but
 * finite. The current [value] is folded in for the same reason the axis-limit
 * sliders need it: a value outside the declared range must still be
 * *representable*, or the thumb shows one number while the label shows another
 * and a stray touch writes the wrong one.
 */
fun safeRange(
    range: ClosedFloatingPointRange<Float>,
    value: Float = Float.NaN
): ClosedFloatingPointRange<Float> {
    var lo = minOf(range.start, range.endInclusive)
    var hi = maxOf(range.start, range.endInclusive)
    if (value.isFinite()) {
        lo = minOf(lo, value)
        hi = maxOf(hi, value)
    }
    if (!lo.isFinite() || !hi.isFinite()) return 0f..1f
    if (hi - lo < DEGENERATE_EPSILON) hi = lo + DEGENERATE_EPSILON
    return lo..hi
}

private const val DEGENERATE_EPSILON = 1e-3f

private const val DEFAULT_DEBOUNCE_MS = 250L
