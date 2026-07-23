package com.tailapp.testutil

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic synthetic PCM generators shared by the audio front-end tests
 * and (eventually) the beat/drop tiers built on top of it. "Deterministic"
 * is load-bearing, not a nicety: a flaky click track would make onset/flux
 * assertions flaky too, so every source of randomness below takes an
 * explicit, defaulted seed rather than reaching for the platform RNG.
 */
object SyntheticAudio {

    /** Pure sine wave at [freqHz], [amplitude] in `[-1, 1]`. */
    fun sine(freqHz: Float, seconds: Float, sampleRate: Int, amplitude: Float = 0.5f): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { i ->
            (amplitude * sin(2.0 * PI * freqHz * i / sampleRate)).toFloat()
        }
    }

    /** Seeded white noise, uniform in `[-amplitude, amplitude]`. */
    fun whiteNoise(seconds: Float, sampleRate: Int, amplitude: Float = 0.2f, seed: Int = 0): FloatArray {
        val n = (seconds * sampleRate).toInt()
        val random = Random(seed)
        return FloatArray(n) { (random.nextFloat() * 2f - 1f) * amplitude }
    }

    /** All-zero PCM. */
    fun silence(seconds: Float, sampleRate: Int): FloatArray = FloatArray((seconds * sampleRate).toInt())

    /** Concatenates PCM buffers end to end. */
    fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, offset, p.size)
            offset += p.size
        }
        return out
    }

    /**
     * A metronome-like click track: each beat sums a fast-decaying low sine
     * ("kick") with a short seeded noise burst ("transient"), so it has both
     * the low-frequency step and the broadband attack a real percussive onset
     * has — enough for onset/flux/drop tests without needing a real audio
     * fixture. Every [accentEveryNBeats]th beat, starting with beat 0, is
     * louder, mimicking a downbeat accent.
     *
     * @param seed seeds only the noise transient; fixed by default so repeated
     *   calls with the same parameters are byte-identical.
     */
    fun clickTrack(
        bpm: Float,
        seconds: Float,
        sampleRate: Int,
        accentEveryNBeats: Int = 4,
        seed: Int = 42
    ): FloatArray {
        val n = (seconds * sampleRate).toInt()
        val out = FloatArray(n)
        val random = Random(seed)

        val beatPeriodSamples = 60f / bpm * sampleRate
        val kickFreqHz = 60f
        val decaySamples = 0.08f * sampleRate // ~80 ms decay envelope (kick "boom")
        val transientSamples = (0.01f * sampleRate).toInt().coerceAtLeast(1) // ~10 ms noise burst (kick "click")

        var beatIndex = 0
        var beatStart = 0f
        while (beatStart.toInt() < n) {
            val accented = beatIndex % accentEveryNBeats == 0
            val amplitude = if (accented) 0.9f else 0.5f
            val start = beatStart.toInt()

            val kickLenSamples = (decaySamples * 6).toInt() // envelope is negligible well before this
            for (i in 0 until kickLenSamples) {
                val idx = start + i
                if (idx >= n) break
                val envelope = exp(-i / decaySamples)
                out[idx] += (amplitude * envelope * sin(2.0 * PI * kickFreqHz * i / sampleRate)).toFloat()
            }

            for (i in 0 until transientSamples) {
                val idx = start + i
                if (idx >= n) break
                val envelope = 1f - i.toFloat() / transientSamples
                out[idx] += amplitude * envelope * (random.nextFloat() * 2f - 1f) * 0.6f
            }

            beatIndex++
            beatStart += beatPeriodSamples
        }

        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        return out
    }

    /**
     * A quiet stretch followed by a loud broadband+bass stretch — the
     * simplest possible "something happened" signal, for tests that need a
     * detector to react to an energy step without a full click track.
     */
    fun energyStep(quietSeconds: Float, loudSeconds: Float, sampleRate: Int, seed: Int = 7): FloatArray {
        val quiet = whiteNoise(quietSeconds, sampleRate, amplitude = 0.02f, seed = seed)
        val loudNoise = whiteNoise(loudSeconds, sampleRate, amplitude = 0.5f, seed = seed + 1)
        val loudBass = sine(80f, loudSeconds, sampleRate, amplitude = 0.6f)
        val loud = FloatArray(loudNoise.size) { (loudNoise[it] + loudBass[it]).coerceIn(-1f, 1f) }
        return concat(quiet, loud)
    }
}
