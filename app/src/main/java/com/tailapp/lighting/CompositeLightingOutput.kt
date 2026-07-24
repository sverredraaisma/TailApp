package com.tailapp.lighting

import com.tailapp.beat.BeatEvent
import com.tailapp.drop.DropEvent
import com.tailapp.led.PixelBuffer

/**
 * Fans one render loop out to several outputs — typically the tail and the
 * on-screen preview at once, so what the user sees on the phone is the same
 * frame the LEDs got rather than a second, subtly different render.
 *
 * Order is preserved: outputs earlier in the list see each callback first, so
 * the hardware output can be put first and not wait behind UI work.
 */
class CompositeLightingOutput(
    private val outputs: List<LightingOutput>
) : LightingOutput {

    constructor(vararg outputs: LightingOutput) : this(outputs.toList())

    override suspend fun open(ledCount: Int) = outputs.forEach { it.open(ledCount) }

    override suspend fun close() = outputs.forEach { it.close() }

    override fun onFrame(frame: PixelBuffer, timestampNanos: Long) =
        outputs.forEach { it.onFrame(frame, timestampNanos) }

    override fun onBeat(event: BeatEvent) = outputs.forEach { it.onBeat(event) }

    override fun onDrop(event: DropEvent) = outputs.forEach { it.onDrop(event) }
}
