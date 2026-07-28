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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.led.LedCoord
import com.tailapp.led.LedLayout
import com.tailapp.led.LedPreviewClock
import com.tailapp.led.PixelBuffer
import com.tailapp.model.LedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

    // Every LedState edit is queued for the render loop rather than pushed into
    // the renderer here. The renderer is not thread-safe and the loop below owns
    // it, so composition publishes and the loop applies — a layer edit still
    // shows up on the very next drawn frame, without two threads in the stack.
    val pendingState = remember(previewClock) { MutableStateFlow<LedState?>(null) }
    LaunchedEffect(previewClock, ledState) {
        pendingState.value = ledState
    }

    if (ledState == null || totalLeds == 0) {
        LedPreviewPlaceholder(modifier)
        return
    }

    // The renderer is the firmware's whole effect stack, per LED, per frame.
    // Running it inside `withFrameNanos` put that on the UI thread at display
    // refresh rate, where a dense matrix competes with layout and input for the
    // same 16 ms. It now runs on Dispatchers.Default and publishes finished
    // frames; the composable only reads them.
    //
    // Each published frame is a *copy*: the renderer reuses one buffer, so
    // handing the live one across threads would both tear mid-draw and compare
    // equal to the previous value, which is a StateFlow that never emits.
    val frames = remember(previewClock) { MutableStateFlow<PixelBuffer?>(null) }
    LaunchedEffect(previewClock) {
        withContext(Dispatchers.Default) {
            var applied: LedState? = null
            while (isActive) {
                val next = pendingState.value
                if (next !== applied) {
                    next?.let(previewClock::setState)
                    applied = next
                }
                frames.value = previewClock.frameAt(System.nanoTime()).copy()
                delay(PREVIEW_FRAME_INTERVAL_MS)
            }
        }
    }
    val frame by frames.collectAsStateWithLifecycle()

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
    contentDescription: String? = null,
) {
    // Same normalised coordinate map LedStackRenderer feeds every effect, so
    // coords[i] lines up with frame.packed(i) below.
    val coords = remember(ledsPerRing) { LedLayout.coordsFor(ledsPerRing) }
    val numRings = ledsPerRing.size
    val maxRowCount = ledsPerRing.maxOrNull() ?: 1

    val unlitColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UNLIT_ALPHA)
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant

    // A canvas of coloured dots is nothing at all to a screen reader, so it
    // announces what it is and how big the strip is rather than staying silent.
    val description = contentDescription
        ?: "Live LED preview, ${coords.size} lights across $numRings rings"

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(max(1.6f, maxRowCount.toFloat() / max(numRings, 1)))
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .semantics { this.contentDescription = description }
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

        // The coordinate map (from the device's ledsPerRing) and the frame (sized
        // by the engine's own layout StateFlow) update independently, so a matrix
        // that just grew can leave coords longer than the frame. frame.red/green/
        // blue are unchecked, so read only where a pixel exists and show the rest
        // as unlit rather than indexing past the buffer.
        if (i >= frame.ledCount) {
            drawCircle(color = unlitColor, radius = radius, center = center)
            continue
        }

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
        // source rather than a flat swatch. Drawn as a few nested translucent
        // circles rather than a radial-gradient brush: the brush had to be
        // built per LED per frame (its centre and radius are per LED), which on
        // a dense matrix is hundreds of allocations every frame for an effect
        // that is meant to be decoration.
        val glowRadius = radius * (1.8f + brightness * 1.6f)
        for (ring in GLOW_RINGS downTo 1) {
            val t = ring / GLOW_RINGS.toFloat()
            drawCircle(
                color = color.copy(alpha = brightness * 0.5f * (1f - t) * (1f - t)),
                radius = radius + (glowRadius - radius) * t,
                center = center,
            )
        }
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

/** Concentric circles standing in for one radial-gradient glow. */
private const val GLOW_RINGS = 3

/**
 * ~60 fps of preview. The renderer runs off the UI thread, so this paces the
 * work rather than racing the display; [LedPreviewClock] clamps any longer gap
 * anyway, so a slow frame slows the animation instead of jumping it.
 */
private const val PREVIEW_FRAME_INTERVAL_MS = 16L
