package com.tailapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tailapp.model.MotionState

/**
 * Where the tail is, drawn from the live FF02 motion state.
 *
 * Four numbers in degrees are close to unreadable while something is moving —
 * you cannot tell a wag from a jitter, or which axis is doing what. A plot of
 * the two axes against their configured travel makes both obvious at a glance,
 * which is what the motion screen actually needs while tuning limits or
 * watching a streamed choreography.
 *
 * Positions are dead-reckoned by the device's motion profiles rather than read
 * from encoders, so this shows what the firmware *believes*. That is the right
 * thing here: it is the same number every limit and every effect is working
 * from, and a divergence from the physical tail is exactly the kind of drift
 * worth being able to see.
 */
@Composable
fun TailPositionView(
    motionState: MotionState?,
    modifier: Modifier = Modifier,
    /**
     * Plot the logical (pre-mix) segment positions rather than the physical
     * (post-mix) ones. Only differs under a non-identity axis mix; ignored, and
     * the physical positions shown, when the device reports no logical block.
     */
    showLogical: Boolean = false
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val first = MaterialTheme.colorScheme.primary
    val second = MaterialTheme.colorScheme.tertiary

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val radius = minOf(w, h) / 2f * 0.85f

            // Travel envelope and centre lines, so a deflection is readable as a
            // fraction of what the axis can actually do.
            drawCircle(color = surface, radius = radius, center = Offset(cx, cy))
            drawCircle(
                color = outline,
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )
            drawLine(outline, Offset(cx - radius, cy), Offset(cx + radius, cy), strokeWidth = 1f)
            drawLine(outline, Offset(cx, cy - radius), Offset(cx, cy + radius), strokeWidth = 1f)

            if (motionState == null) return@Canvas

            fun normalised(value: Float, min: Float, max: Float): Float {
                val half = (max - min) / 2f
                // A degenerate window has no meaningful centre; drawing it as
                // full deflection would be a lie.
                if (half <= 0.5f) return 0f
                return ((value - (max + min) / 2f) / half).coerceIn(-1f, 1f)
            }

            // Logical positions have no axis limits of their own on the wire, so
            // they are normalised against the same window; under an identity mix
            // the two spaces coincide and the plot is unchanged.
            val positions = motionState.logicalPositions?.takeIf { showLogical }
                ?: motionState.encoderPositions
            val x1 = normalised(positions.getOrElse(0) { 0f }, motionState.xAxisMin, motionState.xAxisMax)
            val y1 = normalised(positions.getOrElse(2) { 0f }, motionState.yAxisMin, motionState.yAxisMax)
            val x2 = normalised(positions.getOrElse(1) { 0f }, motionState.xAxisMin, motionState.xAxisMax)
            val y2 = normalised(positions.getOrElse(3) { 0f }, motionState.yAxisMin, motionState.yAxisMax)

            // A segment from the base through the first half to the second: the
            // shape reads as a tail rather than as two unrelated dots.
            val p1 = Offset(cx + x1 * radius, cy - y1 * radius)
            val p2 = Offset(cx + x2 * radius, cy - y2 * radius)

            drawLine(first, Offset(cx, cy), p1, strokeWidth = 6f)
            drawLine(second, p1, p2, strokeWidth = 6f)
            drawCircle(first, radius = 8f, center = p1)
            drawCircle(second, radius = 10f, center = p2)
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "First half ●  second half ●  — plotted against each axis's configured travel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
