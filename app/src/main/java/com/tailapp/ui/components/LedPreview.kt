package com.tailapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.tailapp.led.LedCoord
import com.tailapp.led.LedLayout
import com.tailapp.led.LedPreviewClock
import com.tailapp.led.PixelBuffer
import com.tailapp.model.LedState
import kotlin.math.max
import kotlin.math.min

/**
 * Live, locally-computed preview of the tail's LED strip.
 *
 * The device never streams its frame buffer back, so this draws whatever
 * [previewClock] (wrapping [com.tailapp.led.LedStackRenderer]) renders from
 * [ledState] on this device instead - the same effect-stack math the firmware
 * runs, just replayed here. It is a *fidelity* preview: geometry comes
 * straight from [LedLayout.coordsFor], the same normalised coordinate map the
 * renderer itself feeds every effect, so LED `i` here is exactly LED `i` in
 * [PixelBuffer].
 */
@Composable
fun LedPreview(
    ledState: LedState?,
    previewClock: LedPreviewClock,
    modifier: Modifier = Modifier,
) {
    val ledsPerRing = ledState?.ledsPerRing.orEmpty()
    val totalLeds = ledsPerRing.sum()

    // Push every LedState edit into the renderer as soon as recomposition
    // sees it, so a layer edit shows up on the very next drawn frame instead
    // of waiting for some separate poll.
    LaunchedEffect(previewClock, ledState) {
        ledState?.let(previewClock::setState)
    }

    if (ledState == null || totalLeds == 0) {
        LedPreviewPlaceholder(modifier)
        return
    }

    var frame by remember(previewClock) { mutableStateOf<PixelBuffer?>(null) }

    // Drives the animation from the Compose frame clock - no background
    // thread, no polling. Leaving the screen cancels this LaunchedEffect,
    // which is what stops the animation.
    LaunchedEffect(previewClock) {
        while (true) {
            withFrameNanos { nowNanos ->
                frame = previewClock.frameAt(nowNanos)
            }
        }
    }

    // Forget the wall-clock bookkeeping when the preview leaves composition,
    // so coming back to it later starts a clean dt=0 frame instead of a
    // (clamped, but still arbitrary) gap since it was last drawn.
    DisposableEffect(previewClock) {
        onDispose { previewClock.reset() }
    }

    LedStrip(pixels = frame, ledsPerRing = ledsPerRing, modifier = modifier)
}

/**
 * Draws one rendered frame of the strip, positioned by [ledsPerRing]'s ring
 * layout - the same drawing code [LedPreview] uses for its live replay of the
 * device's own effect stack, extracted so any other screen showing a
 * [PixelBuffer] (e.g. the BeatLight monitor) doesn't reimplement it.
 *
 * [pixels] is nullable so a caller can show the strip's frame (background,
 * sizing) before the first frame has rendered, exactly as [LedPreview] did
 * before this was split out: no dots draw until pixels arrive, but the canvas
 * itself doesn't flash a placeholder in the meantime.
 */
@Composable
fun LedStrip(
    pixels: PixelBuffer?,
    ledsPerRing: List<Int>,
    modifier: Modifier = Modifier,
) {
    // Same normalised coordinate map LedStackRenderer feeds every effect, so
    // coords[i] lines up with frame.packed(i) below.
    val coords = remember(ledsPerRing) { LedLayout.coordsFor(ledsPerRing) }
    val numRings = ledsPerRing.size
    val maxRowCount = ledsPerRing.maxOrNull() ?: 1

    val unlitColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UNLIT_ALPHA)
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(max(1.6f, maxRowCount.toFloat() / max(numRings, 1)))
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
    ) {
        val f = pixels ?: return@Canvas
        drawLedStrip(coords, f, maxRowCount, numRings, unlitColor)
    }
}

/**
 * Draws one dot per LED at its normalised position, filled with the rendered
 * colour. Circles (rather than a raw pixel grid) plus a soft glow on bright
 * pixels are the only things added on top of what [LedStackRenderer] computes
 * - purely so individual LEDs read as small lights on a screen instead of
 * flat colour swatches; none of it changes what colour any given LED shows.
 */
private fun DrawScope.drawLedStrip(
    coords: List<LedCoord>,
    frame: PixelBuffer,
    maxRowCount: Int,
    numRings: Int,
    unlitColor: Color,
) {
    val marginFraction = 0.1f
    val insetX = size.width * marginFraction
    val insetY = size.height * marginFraction
    val drawWidth = size.width - insetX * 2f
    val drawHeight = size.height - insetY * 2f

    // Spacing between neighbouring LEDs along each axis, so the dot radius
    // scales down automatically as rows/columns get denser instead of
    // overlapping.
    val colSpacing = if (maxRowCount > 1) drawWidth / (maxRowCount - 1) else drawWidth
    val rowSpacing = if (numRings > 1) drawHeight / (numRings - 1) else drawHeight
    val radius = (min(colSpacing, rowSpacing) * 0.4f).coerceIn(3.dp.toPx(), 26.dp.toPx())

    for (i in coords.indices) {
        val coord = coords[i]
        val center = Offset(insetX + coord.x * drawWidth, insetY + coord.y * drawHeight)

        val r = frame.red(i)
        val g = frame.green(i)
        val b = frame.blue(i)
        val brightness = max(r, max(g, b)) / 255f

        if (brightness <= DARK_THRESHOLD) {
            // Dark pixel: still draw a faint dot so the (physically present,
            // just unlit) LED doesn't read as a hole in the strip.
            drawCircle(color = unlitColor, radius = radius, center = center)
            continue
        }

        val color = Color(r / 255f, g / 255f, b / 255f)

        // Soft glow behind the LED core so a bright pixel reads as a light
        // source rather than a flat swatch.
        val glowRadius = radius * (1.8f + brightness * 1.6f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = brightness * 0.5f), color.copy(alpha = 0f)),
                center = center,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = center,
        )
        drawCircle(color = color, radius = radius, center = center)
    }
}

/** Shown in place of the strip when there is no layout (or no data) to draw yet. */
@Composable
fun LedPreviewPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No LED layout to preview yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val DARK_THRESHOLD = 0.02f
private const val UNLIT_ALPHA = 0.25f
