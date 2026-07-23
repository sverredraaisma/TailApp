package com.tailapp.lighting

import com.tailapp.beat.BeatEvent
import com.tailapp.drop.DropEvent
import com.tailapp.effects.EffectProfile
import com.tailapp.led.PixelBuffer

/**
 * Where rendered lighting goes.
 *
 * Kept interface-first so the analysis pipeline never learns about BLE: the tail
 * ([com.tailapp.lighting.TailDirectLedOutput], FF0A direct pixel streaming), the
 * on-screen preview and the test doubles are all just implementations.
 *
 * Every method is called from the render loop's single thread and must return
 * promptly — implementations that talk to hardware queue rather than block.
 */
interface LightingOutput {
    /** Called before the first frame of a session. */
    suspend fun open(ledCount: Int) {}

    /** Called when the session ends; implementations release their hardware here. */
    suspend fun close() {}

    /**
     * A fully rendered frame.
     *
     * The buffer is reused between frames — implementations that keep it must
     * copy. [timestampNanos] is the [System.nanoTime] the frame is meant to be
     * *seen*, already including the configured trigger offset.
     */
    fun onFrame(frame: PixelBuffer, timestampNanos: Long)

    /** A tracked beat, for outputs that react to events rather than pixels. */
    fun onBeat(event: BeatEvent) {}

    /** A detected drop. */
    fun onDrop(event: DropEvent) {}

    /** The active effect profile changed (debounced genre switch or manual override). */
    fun onProfileChange(profile: EffectProfile) {}
}
