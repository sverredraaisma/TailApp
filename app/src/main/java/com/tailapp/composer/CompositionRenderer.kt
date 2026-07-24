package com.tailapp.composer

import com.tailapp.led.ColorMath
import com.tailapp.led.LedCoord
import com.tailapp.led.LedLayout
import com.tailapp.led.PixelBuffer
import com.tailapp.model.BlendMode

/**
 * Renders a [Composition]'s layer/folder tree into a single frame.
 *
 * The generalisation of [com.tailapp.led.LayerCompositor] from a flat list to a
 * tree, and it keeps that class's two load-bearing properties:
 *
 * - **Same blend math.** Layers combine through [ColorMath.blend], the
 *   integer-exact transcription of the firmware's blend helpers, so a stack
 *   built here reads the way a firmware stack does.
 * - **Same buffer discipline.** Scratch buffers are pooled per tree depth and
 *   reused across frames rather than allocated per layer, mirroring
 *   `temp_buffer_`'s reuse. Nothing on the render path touches the heap.
 *
 * **Folders composite twice.** A [GroupLayer]'s children are blended among
 * themselves into the folder's own buffer, and only that finished result is
 * blended into the parent — which is what lets one modulator layer gate an
 * entire folder, and what makes deep stacks composable at all.
 *
 * **Running state survives edits.** [setComposition] diffs the incoming tree
 * against the live effect instances by [LayerNode.id]: a layer whose effect id
 * is unchanged keeps its instance (and therefore its decay envelopes, phase and
 * counters) and merely takes the new parameters, exactly as
 * `LedStackRenderer.setState` updates a firmware layer in place. Only a changed
 * effect id builds a new instance. Without that, every slider drag would restart
 * every animation in the stack.
 *
 * Not thread-safe: both [setComposition] and [render] must be called from the
 * one render thread. [CompositionScene] guarantees that.
 */
class CompositionRenderer {

    private var coords: List<LedCoord> = emptyList()
    private var currentLedsPerRing: List<Int>? = null
    private var out = PixelBuffer(0)

    /** One reusable buffer per tree depth; index 0 is the top level's layers. */
    private val scratch = ArrayList<PixelBuffer>()

    private var composition: Composition = Composition.EMPTY
    private var instances: Map<String, ReactiveEffect> = emptyMap()

    /** LEDs the current layout holds. */
    val ledCount: Int get() = coords.size

    /** The tree currently being rendered. */
    val currentComposition: Composition get() = composition

    /**
     * Rebuilds the coordinate map. Cheap to call with an unchanged layout — the
     * device re-reports its ring configuration once a second and most of those
     * reports say nothing new.
     */
    fun setLayout(ledsPerRing: List<Int>) {
        if (ledsPerRing == currentLedsPerRing) return
        currentLedsPerRing = ledsPerRing
        coords = LedLayout.coordsFor(ledsPerRing)
        out = PixelBuffer(coords.size)
        // Sizes are per-layout; drop the pool rather than resizing entry by entry.
        scratch.clear()
    }

    /** Swaps in a new tree, preserving the running state of unchanged layers. */
    fun setComposition(composition: Composition) {
        val rebuilt = HashMap<String, ReactiveEffect>()
        buildInstances(composition.layers, rebuilt)
        instances = rebuilt
        this.composition = composition
    }

    /** Drops every effect's running state, keeping the tree. */
    fun reset() {
        instances = emptyMap()
        setComposition(composition)
    }

    private fun buildInstances(nodes: List<LayerNode>, into: HashMap<String, ReactiveEffect>) {
        for (node in nodes) {
            when (node) {
                is GroupLayer -> buildInstances(node.children, into)

                is EffectLayer -> {
                    val previous = instances[node.id]
                    // Reuse only when the slot still renders the same effect —
                    // the firmware's rule for when a layer is rebuilt.
                    val effect = if (previous != null && previous.spec.id == node.effectId) {
                        previous
                    } else {
                        // An effect id this build does not know renders nothing,
                        // rather than failing the whole composition.
                        ReactiveEffects.create(node.effectId) ?: continue
                    }
                    effect.applyParams(node.params)
                    effect.flipX = node.flipX
                    effect.flipY = node.flipY
                    effect.mirrorX = node.mirrorX
                    effect.mirrorY = node.mirrorY
                    into[node.id] = effect
                }
            }
        }
    }

    /**
     * Renders the frame for [ctx].
     *
     * @return the renderer's own buffer — valid until the next call. Outputs that
     *   keep a frame must copy it.
     */
    fun render(ctx: ReactiveContext): PixelBuffer {
        out.clear()
        if (coords.isEmpty()) return out

        renderInto(composition.layers, ctx, out, depth = 0)

        val brightness = composition.brightness
        if (brightness < 1f) applyBrightness(out, brightness.coerceAtLeast(0f))
        return out
    }

    private fun renderInto(
        nodes: List<LayerNode>,
        ctx: ReactiveContext,
        dest: PixelBuffer,
        depth: Int
    ) {
        for (node in nodes) {
            // A disabled or fully transparent node contributes nothing — not even
            // black — matching the firmware skipping `!layer.enabled`.
            if (!node.enabled || node.opacity <= 0f) continue

            val layer = scratchAt(depth)
            layer.clear()

            when (node) {
                is EffectLayer -> {
                    val effect = instances[node.id] ?: continue
                    effect.render(layer, coords, ctx)
                }

                is GroupLayer -> {
                    if (node.children.isEmpty()) continue
                    renderInto(node.children, ctx, layer, depth + 1)
                }
            }

            blendInto(dest, layer, node.blendMode, node.opacity)
        }
    }

    private fun blendInto(dest: PixelBuffer, src: PixelBuffer, mode: BlendMode, opacity: Float) {
        val full = opacity >= 1f
        for (i in 0 until dest.ledCount) {
            val base = dest.packed(i)
            val blended = ColorMath.blend(base, src.packed(i), mode)
            dest.setPacked(i, if (full) blended else mix(base, blended, opacity))
        }
    }

    /** Cross-fades [from] toward [to] by [t], per channel. */
    private fun mix(from: Int, to: Int, t: Float): Int {
        val r = channel(from, 16) + ((channel(to, 16) - channel(from, 16)) * t).toInt()
        val g = channel(from, 8) + ((channel(to, 8) - channel(from, 8)) * t).toInt()
        val b = channel(from, 0) + ((channel(to, 0) - channel(from, 0)) * t).toInt()
        return (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun applyBrightness(buffer: PixelBuffer, brightness: Float) {
        for (i in 0 until buffer.ledCount) {
            buffer.set(
                i,
                (buffer.red(i) * brightness).toInt(),
                (buffer.green(i) * brightness).toInt(),
                (buffer.blue(i) * brightness).toInt()
            )
        }
    }

    private fun channel(colour: Int, shift: Int): Int = (colour shr shift) and 0xFF

    private fun scratchAt(depth: Int): PixelBuffer {
        while (scratch.size <= depth) scratch.add(PixelBuffer(coords.size))
        val buffer = scratch[depth]
        if (buffer.ledCount == coords.size) return buffer
        return PixelBuffer(coords.size).also { scratch[depth] = it }
    }
}
