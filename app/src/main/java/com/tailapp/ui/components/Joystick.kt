package com.tailapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
fun Joystick(
    modifier: Modifier = Modifier,
    onPositionChanged: (x: Float, y: Float) -> Unit
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }

    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline
    val knobColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .size(150.dp)
            .pointerInput(Unit) {
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val outerRadius = min(centerX, centerY)
                val knobRadius = outerRadius * 0.3f
                val maxTravel = outerRadius - knobRadius

                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pointerId = down.id

                    fun updateKnob(position: Offset) {
                        val offset = Offset(position.x - centerX, position.y - centerY)
                        val distance = offset.getDistance()
                        knobOffset = if (distance > maxTravel) {
                            offset * (maxTravel / distance)
                        } else {
                            offset
                        }
                        onPositionChanged(knobOffset.x / maxTravel, knobOffset.y / maxTravel)
                    }

                    updateKnob(down.position)
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.find { it.id == pointerId } ?: break
                        updateKnob(change.position)
                        change.consume()
                    } while (change.pressed)

                    knobOffset = Offset.Zero
                    onPositionChanged(0f, 0f)
                }
            }
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2
        val knobR = radius * 0.3f

        // Background circle
        drawCircle(color = bgColor, radius = radius, center = center)

        // Border
        drawCircle(
            color = borderColor,
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // Crosshair
        drawLine(
            color = borderColor.copy(alpha = 0.3f),
            start = Offset(center.x - radius, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = borderColor.copy(alpha = 0.3f),
            start = Offset(center.x, center.y - radius),
            end = Offset(center.x, center.y + radius),
            strokeWidth = 1.dp.toPx()
        )

        // Knob
        drawCircle(color = knobColor, radius = knobR, center = center + knobOffset)
        drawCircle(
            color = knobColor.copy(alpha = 0.7f),
            radius = knobR,
            center = center + knobOffset,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
