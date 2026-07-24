package com.tailapp.ble.protocol

/**
 * Builds the FF05 audio frame — loudness, bin count, then one byte per bin —
 * optionally followed by a three-byte beat trailer of phase, BPM and flags.
 *
 * The device has no microphone, so this is the only thing it can know about the
 * music. The trailer hands it the phone's own beat tracker's output — a better
 * beat than the ESP32-C3 could compute, for three bytes a frame.
 *
 * The trailer is additive: the firmware's parser reads exactly `num_bins` of
 * bin data and ignores anything after it, so sending one is safe against
 * firmware that predates it.
 */
object FftFrameBuilder {

    const val FLAG_BEAT: Int = 0x01
    const val FLAG_DOWNBEAT: Int = 0x02
    const val FLAG_DROP: Int = 0x04

    fun build(loudness: Byte, bins: ByteArray): ByteArray {
        val frame = ByteArray(2 + bins.size)
        frame[0] = loudness
        frame[1] = bins.size.toByte()
        bins.copyInto(frame, 2)
        return frame
    }

    /**
     * @param beatPhase `0..1` sawtooth across one beat, `0` at the beat instant.
     * @param bpm current tempo; `0` when unknown, which the device reads as
     *   "no beat information" rather than as a stopped tempo.
     * @param flags any of [FLAG_BEAT], [FLAG_DOWNBEAT], [FLAG_DROP].
     */
    fun buildWithBeat(
        loudness: Byte,
        bins: ByteArray,
        beatPhase: Float,
        bpm: Float,
        flags: Int
    ): ByteArray {
        val frame = ByteArray(2 + bins.size + TRAILER_SIZE)
        frame[0] = loudness
        frame[1] = bins.size.toByte()
        bins.copyInto(frame, 2)

        val trailer = 2 + bins.size
        frame[trailer] = (beatPhase.coerceIn(0f, 1f) * 255f).toInt().toByte()
        // Clamped rather than wrapped: a 300 BPM estimate truncating to 44
        // would be worse than reporting the ceiling.
        frame[trailer + 1] = bpm.toInt().coerceIn(0, 255).toByte()
        frame[trailer + 2] = flags.toByte()
        return frame
    }

    private const val TRAILER_SIZE = 3
}
