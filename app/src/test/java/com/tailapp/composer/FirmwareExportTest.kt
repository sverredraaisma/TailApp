package com.tailapp.composer

import com.tailapp.model.BlendMode
import com.tailapp.model.LedEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translating a composer look into something the device can render alone.
 *
 * The composer renders on the phone and streams pixels; the device has its own,
 * much smaller effect stack that keeps running when the phone walks away. This
 * is the bridge, and the property that matters most is honesty: a user who
 * exports a stack and gets something unlike the preview, with no explanation,
 * is worse off than one who is told which layers could not come along.
 */
class FirmwareExportTest {

    private fun effect(
        id: String,
        effectId: String,
        params: Map<String, Float> = emptyMap(),
        blend: BlendMode = BlendMode.OVERWRITE,
        opacity: Float = 1f,
        enabled: Boolean = true
    ) = EffectLayer(
        id = id,
        name = id,
        effectId = effectId,
        params = params,
        blendMode = blend,
        opacity = opacity,
        enabled = enabled
    )

    private fun composition(vararg layers: LayerNode) =
        Composition(id = "test", name = "Test", layers = layers.toList())

    @Test
    fun `a solid colour maps onto the device's static colour`() {
        val result = FirmwareExport.export(
            composition(effect("base", "solid", mapOf("color" to 0xFF8000.toFloat(), "brightness" to 1f)))
        )

        assertEquals(1, result.layers.size)
        val layer = result.layers[0]
        assertEquals(LedEffect.STATIC_COLOR.id, layer.effectId)
        assertEquals(255f, layer.params[0], 0.5f) // R
        assertEquals(128f, layer.params[1], 0.5f) // G
        assertEquals(0f, layer.params[2], 0.5f)   // B
        assertTrue(result.unmapped.isEmpty())
    }

    @Test
    fun `a tail-reactive effect has no device counterpart and is reported`() {
        // The device cannot do these at all: it has no notion of its own
        // deflection, and no wavefront effect. Saying so is the point.
        val result = FirmwareExport.export(
            composition(
                effect("glow", "motion_glow"),
                effect("level", "gravity_level")
            )
        )

        assertTrue(result.layers.isEmpty())
        assertEquals(listOf("glow", "level"), result.unmapped)
        assertTrue(FirmwareExport.describe(result).contains("Nothing in this stack"))
    }

    @Test
    fun `a lossy mapping is carried over but flagged`() {
        val result = FirmwareExport.export(
            composition(effect("bass", "bass_pulse", mapOf("color" to 0x0000FF.toFloat())))
        )

        assertEquals(1, result.layers.size)
        assertEquals(LedEffect.AUDIO_POWER.id, result.layers[0].effectId)
        assertFalse("a lossy mapping must not claim to be exact", result.isExact)
        assertTrue(result.notes.any { it.contains("overall loudness") })
    }

    @Test
    fun `folders are flattened and the difference is called out`() {
        // A folder composites twice on the phone: a modulator inside it reaches
        // only its siblings. Flattening changes that, so it cannot pass silently.
        val result = FirmwareExport.export(
            composition(
                effect("base", "solid"),
                GroupLayer(
                    id = "group",
                    name = "Analyser",
                    children = listOf(effect("bars", "spectrum_bars"))
                )
            )
        )

        assertEquals(2, result.layers.size)
        assertTrue(result.notes.any { it.contains("folder") })
    }

    @Test
    fun `disabled layers are left out entirely`() {
        val result = FirmwareExport.export(
            composition(
                effect("base", "solid"),
                effect("off", "rainbow", enabled = false)
            )
        )

        assertEquals(1, result.layers.size)
        // Not "unmapped" either: the user turned it off, so its absence needs no
        // explanation.
        assertTrue(result.unmapped.isEmpty())
    }

    @Test
    fun `opacity and transforms survive the trip`() {
        val result = FirmwareExport.export(
            composition(
                EffectLayer(
                    id = "l", name = "l", effectId = "solid",
                    params = mapOf("color" to 0xFFFFFF.toFloat()),
                    blendMode = BlendMode.ADD,
                    opacity = 0.5f,
                    flipY = true
                )
            )
        )

        val layer = result.layers[0]
        assertEquals(BlendMode.ADD.id, layer.blendMode)
        assertEquals(127, layer.opacity)
        assertTrue(layer.flipY)
    }

    @Test
    fun `a stack deeper than the device reports what would not fit`() {
        // Nine mappable layers against eight device slots.
        val layers = (0 until 9).map { effect("layer$it", "solid") }
        val result = FirmwareExport.export(composition(*layers.toTypedArray()))

        assertEquals(FirmwareExport.MAX_LAYERS, result.layers.size)
        assertEquals(1, result.unmapped.size)
        assertTrue(result.unmapped[0].contains("no free device layer"))
    }

    @Test
    fun `beat effects map onto beat pulse, which only works because we stream the beat`() {
        val result = FirmwareExport.export(
            composition(effect("flash", "beat_flash", mapOf("color" to 0xFFFFFF.toFloat(), "decay" to 0.25f)))
        )

        assertEquals(LedEffect.BEAT_PULSE.id, result.layers[0].effectId)
        // decay 0.25 s becomes a decay *rate* of 4 per second.
        assertEquals(4f, result.layers[0].params[3], 0.01f)
    }

    @Test
    fun `every built-in stack exports without throwing`() {
        // The built-ins are the stacks a user is most likely to try to install,
        // and several are made entirely of effects the device does not have.
        for (built in CompositionLibrary.BUILT_INS) {
            val result = FirmwareExport.export(built)
            assertTrue(
                "describe() must say something useful for ${built.name}",
                FirmwareExport.describe(result).isNotBlank()
            )
        }
    }
}
