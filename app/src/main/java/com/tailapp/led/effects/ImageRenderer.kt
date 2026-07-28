package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.PixelBuffer

/**
 * Mirrors `main/led/effects/image_effect.cpp`.
 *
 * The device never reports uploaded image pixels back over BLE (FF03 upload
 * is write-only), so the app has to hold on to the bytes it sent and feed
 * them back in via [setImage] itself - see `LedStackRenderer`, which does this
 * every frame from its image supplier.
 */
class ImageRenderer : LedEffectRenderer() {
    private var orientation = 0.0f // 0=0deg, 1=90deg, 2=180deg, 3=270deg

    private var imageData: ByteArray? = null
    private var width = 0
    private var height = 0

    /**
     * Loads a flat RGB byte array (`r,g,b,r,g,b,...`). Mirrors
     * `ImageEffect::set_image`.
     *
     * An image whose `width * height * 3` exceeds [srcLen] is **refused**, not
     * truncated: the firmware clears the image and renders black rather than
     * copying past the end of the source buffer, and a preview that instead
     * drew the in-range prefix would show a picture the device never displays.
     *
     * The dimensions are `uint8_t` on the wire, so they are masked here too - a
     * 256-wide image is width 0 on the device (and therefore blank), and the
     * preview has to agree.
     */
    fun setImage(rgb: ByteArray, width: Int, height: Int, srcLen: Int = rgb.size) {
        val w = width and 0xFF
        val h = height and 0xFF
        val size = w * h * 3
        if (size == 0 || size > srcLen) {
            this.imageData = null
            this.width = 0
            this.height = 0
            return
        }
        this.imageData = rgb
        this.width = w
        this.height = h
    }

    /** Nearest-neighbor sample at normalized coordinates. Mirrors `ImageEffect::sample`. */
    private fun sample(x: Float, y: Float): Int {
        val data = imageData
        if (data == null || width == 0 || height == 0) return 0x000000

        // `static_cast<int>(x * (width_ - 1) + 0.5f)` truncates toward zero -
        // round-to-nearest for non-negative inputs, same effect as toInt() here.
        val px = (x * (width - 1) + 0.5f).toInt().coerceIn(0, width - 1)
        val py = (y * (height - 1) + 0.5f).toInt().coerceIn(0, height - 1)

        val idx = (py * width + px) * 3
        if (idx + 2 >= data.size) return 0x000000

        val r = data[idx].toInt() and 0xFF
        val g = data[idx + 1].toInt() and 0xFF
        val b = data[idx + 2].toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        val orient = (orientation + 0.5f).toInt() and 3

        for (i in coords.indices) {
            val c = transformCoord(coords[i])

            // Rotate around the center (0.5, 0.5) before sampling.
            var x = c.x
            var y = c.y
            when (orient) {
                1 -> { // 90 degrees CW
                    val tmp = x
                    x = 1.0f - y
                    y = tmp
                }
                2 -> { // 180 degrees
                    x = 1.0f - x
                    y = 1.0f - y
                }
                3 -> { // 270 degrees CW
                    val tmp = x
                    x = y
                    y = 1.0f - tmp
                }
                // 0 degrees: no rotation
            }

            out.setPacked(i, sample(x, y))
        }
    }

    override fun setParam(id: Int, value: Float) {
        if (id == 0) orientation = value
    }

    override fun getParam(id: Int): Float = if (id == 0) orientation else 0.0f
}
