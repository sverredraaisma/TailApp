package com.tailapp.led

/**
 * Mirrors what `LedMatrix::push` does to a composited frame on its way to the
 * strip: master brightness, then the power limiter, then gamma.
 *
 * The preview has to apply this or it shows a picture the device will never
 * display — the whole reason `com.tailapp.led` is a port rather than a
 * lookalike. It matters most for the limiter: a look that browns out on
 * hardware would otherwise look perfect on screen, which is precisely the
 * failure the limiter exists to make visible.
 *
 * Order is load-bearing and matches the firmware: the limiter has to judge what
 * will actually be driven (so brightness comes first), and gamma has to be last
 * or its curve is no longer the curve the eye sees.
 */
class LedOutputStage(
    var brightness: Int = 255,
    var gammaEnabled: Boolean = true,
    /** Estimated current cap in milliamps; `0` disables limiting. */
    var currentLimitMa: Int = 0
) {

    /** The scale the limiter applied to the last frame, 0-255. */
    var lastPowerScale: Int = 255
        private set

    /** Applies the stage in place. */
    fun apply(buffer: PixelBuffer) {
        lastPowerScale = powerScaleFor(buffer)

        for (i in 0 until buffer.ledCount) {
            var c = ColorMath.scale(buffer.packed(i), brightness)
            if (lastPowerScale != 255) c = ColorMath.scale(c, lastPowerScale)
            if (gammaEnabled) c = ColorMath.gamma(c)
            buffer.setPacked(i, c)
        }
    }

    /** Estimated draw of [buffer] at the current brightness, in milliamps. */
    fun estimateCurrentMa(buffer: PixelBuffer): Int {
        var channelSum = 0L
        for (i in 0 until buffer.ledCount) {
            channelSum += buffer.red(i) + buffer.green(i) + buffer.blue(i)
        }
        val scaled = channelSum * brightness / 255
        return (scaled * MA_PER_CHANNEL / 255 + buffer.ledCount.toLong() * QUIESCENT_MA).toInt()
    }

    private fun powerScaleFor(buffer: PixelBuffer): Int {
        if (currentLimitMa <= 0) return 255
        val estimate = estimateCurrentMa(buffer)
        if (estimate <= currentLimitMa) return 255

        val quiescent = buffer.ledCount * QUIESCENT_MA
        // Quiescent draw is what the controllers take regardless of colour, so
        // a budget below it cannot be met by dimming at all.
        if (currentLimitMa <= quiescent) return 0
        val headroom = currentLimitMa - quiescent
        val needed = estimate - quiescent
        return (headroom * 255 / needed).coerceIn(0, 255)
    }

    companion object {
        /** WS2812B: ~20 mA per colour channel at full duty. */
        const val MA_PER_CHANNEL = 20

        /** ~1 mA per LED for its controller, which dimming does not remove. */
        const val QUIESCENT_MA = 1
    }
}
