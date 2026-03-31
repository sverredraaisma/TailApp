package com.tailapp.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class FftResult(
    val loudness: Byte,
    val bins: ByteArray
)

class FftProcessor {
    var numBins: Int = 64
    var normalizationSpeed: Float = 0.1f
    var freqRangeStart: Float = 20f
    var freqRangeEnd: Float = 20000f

    private var runningPeakAmplitude: Float = 1f

    fun process(samples: ShortArray, sampleRate: Int): FftResult {
        // Find a power-of-2 FFT size
        val fftSize = Integer.highestOneBit(samples.size)
        if (fftSize < 2) return FftResult(0, ByteArray(numBins))

        // Apply Hann window and convert to float
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        for (i in 0 until fftSize) {
            val window = 0.5f * (1f - cos(2.0 * PI * i / (fftSize - 1)).toFloat())
            real[i] = samples[i] * window
        }

        // In-place Cooley-Tukey FFT
        fft(real, imag)

        // Compute magnitudes for positive frequencies
        val halfSize = fftSize / 2
        val magnitudes = FloatArray(halfSize) {
            sqrt(real[it] * real[it] + imag[it] * imag[it])
        }

        // Resolve selected frequency range to FFT bin indices
        val freqPerBin = sampleRate.toFloat() / fftSize
        val startBin = (freqRangeStart / freqPerBin).toInt().coerceIn(0, halfSize - 1)
        val endBin = (freqRangeEnd / freqPerBin).toInt().coerceIn(startBin + 1, halfSize)

        // Compute loudness (RMS) over the selected frequency range only
        var sumSquares = 0.0
        for (i in startBin until endBin) sumSquares += magnitudes[i].toDouble() * magnitudes[i]
        val rms = sqrt(sumSquares / (endBin - startBin)).toFloat()

        // Adaptive normalization
        runningPeakAmplitude += (rms - (runningPeakAmplitude / 3)) * normalizationSpeed
        if (runningPeakAmplitude < 1f) runningPeakAmplitude = 1f
        val normalizedLoudness = (rms / runningPeakAmplitude * 255f).toInt().coerceIn(0, 255)

        // Map output bins to FFT bins on a logarithmic frequency scale
        val outputBins = ByteArray(numBins)
        val logStart = ln(freqRangeStart.toDouble())
        val logEnd = ln(freqRangeEnd.toDouble())

        for (i in 0 until numBins) {
            val freqFrom = exp(logStart + (logEnd - logStart) * i / numBins)
            val freqTo = exp(logStart + (logEnd - logStart) * (i + 1) / numBins)
            val from = (freqFrom / freqPerBin).toInt().coerceIn(startBin, endBin - 1)
            val to = (freqTo / freqPerBin).toInt().coerceIn(from + 1, endBin)
            var sum = 0f
            for (j in from until to) sum += magnitudes[j]
            val avg = sum / (to - from)
            val normalized = (avg / runningPeakAmplitude).coerceIn(0f, 1f)
            outputBins[i] = (normalized * 255f).toInt().toByte()
        }

        return FftResult(normalizedLoudness.toByte(), outputBins)
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var temp = real[i]; real[i] = real[j]; real[j] = temp
                temp = imag[i]; imag[i] = imag[j]; imag[j] = temp
            }
        }
        // Cooley-Tukey
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2.0 * PI / len
            val wReal = cos(angle).toFloat()
            val wImag = kotlin.math.sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curReal = 1f
                var curImag = 0f
                for (k in 0 until halfLen) {
                    val tReal = curReal * real[i + k + halfLen] - curImag * imag[i + k + halfLen]
                    val tImag = curReal * imag[i + k + halfLen] + curImag * real[i + k + halfLen]
                    real[i + k + halfLen] = real[i + k] - tReal
                    imag[i + k + halfLen] = imag[i + k] - tImag
                    real[i + k] += tReal
                    imag[i + k] += tImag
                    val newReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = newReal
                }
                i += len
            }
            len = len shl 1
        }
    }
}
