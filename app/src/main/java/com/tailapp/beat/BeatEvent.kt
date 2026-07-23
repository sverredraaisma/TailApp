package com.tailapp.beat

/** Whether a beat also starts a bar. */
enum class BeatType { BEAT, DOWNBEAT }

/**
 * A tracked beat.
 *
 * @param type beat or downbeat.
 * @param timestampNanos [System.nanoTime] the beat falls on. May be slightly in
 *   the future: the tracker predicts the next beat from the current tempo so
 *   lighting can be scheduled ahead of the BLE round trip.
 * @param bpm the tempo estimate in force at this beat.
 * @param beatInBar 0-based position in the bar; 0 for a downbeat.
 * @param confidence `0..1` — how strongly the onset function supported this beat.
 */
data class BeatEvent(
    val type: BeatType,
    val timestampNanos: Long,
    val bpm: Float,
    val beatInBar: Int,
    val confidence: Float
) {
    val isDownbeat: Boolean get() = type == BeatType.DOWNBEAT
}
