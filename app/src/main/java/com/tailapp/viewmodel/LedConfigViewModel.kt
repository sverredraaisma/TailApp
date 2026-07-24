package com.tailapp.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.audio.FftResult
import com.tailapp.audio.FftStreamManager
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.composer.TailTelemetryTracker
import com.tailapp.led.ImageData
import com.tailapp.led.LedPreviewClock
import com.tailapp.model.DeviceState
import com.tailapp.model.MotionState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LedConfigViewModel(
    private val deviceRepository: DeviceRepository,
    private val fftStreamManager: FftStreamManager? = null
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    private val _uploadProgress = MutableStateFlow<Float?>(null)
    val uploadProgress: StateFlow<Float?> = _uploadProgress.asStateFlow()

    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError: StateFlow<String?> = _uploadError.asStateFlow()

    /**
     * Bytes of the last image this app successfully uploaded, kept purely so
     * the live preview's Image layer has something to render - the firmware
     * never reports uploaded image bytes back (FF03 upload is write-only; see
     * [com.tailapp.led.effects.ImageRenderer]). This only covers images
     * uploaded *this* session: an Image layer restored from a device that
     * already had one stored (or uploaded in a previous app session) has no
     * bytes here and can't be previewed - the device has no way to hand them
     * back either way.
     */
    private var lastUploadedImage: ImageData? = null

    /** Drives [com.tailapp.ui.components.LedPreview]; see its and [LedPreviewClock]'s KDoc. */
    val previewClock = LedPreviewClock(imageSupplier = { lastUploadedImage })

    /**
     * Derives the `motion_energy` the firmware's motion task publishes but FF02
     * does not carry, from how fast the reported deflection is changing.
     */
    private val telemetryTracker = TailTelemetryTracker()

    init {
        // Feed the same FFT frames the app streams to the device (FF05) into
        // the preview's audio source, so Audio Power/Bar/Freq Bars layers
        // animate here too. `filterNotNull` also does the "don't write while
        // not streaming" job: FftStreamManager.stop() nulls latestResult out,
        // so AudioLevelSource is simply left alone to go stale on its own
        // 200ms clock - the same as it would if the device stopped receiving
        // FF05 frames.
        fftStreamManager?.let { manager ->
            viewModelScope.launch {
                manager.latestResult.filterNotNull().collect { onFftResult(it) }
            }
        }

        // The tail-reactive layers (Motion Glow, Tap Ripple, Gravity Level)
        // read the device's own body on hardware. Feeding the preview the same
        // FF02/FF07 inputs is what keeps it from showing a permanently
        // at-rest tail while the real one is being waved around.
        viewModelScope.launch {
            deviceRepository.systemEvents.collect { event ->
                when (event) {
                    SystemEvent.TAP_BASE -> previewClock.motion.tapBase()
                    SystemEvent.TAP_TIP -> previewClock.motion.tapTip()
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            deviceRepository.deviceState
                .map { it.motionState }
                .filterNotNull()
                // FF02 notifies at ~20 Hz whether or not anything moved.
                .distinctUntilChanged()
                .collect { onMotionState(it, System.nanoTime()) }
        }
    }

    /**
     * Maps one FF02 motion state into [previewClock]'s
     * [com.tailapp.led.MotionStateSource], the way the firmware's motion task
     * fills `motion_snapshot_t`. Positions stay in raw degrees because
     * `motion_glow_effect.cpp` maps degrees across a fixed window — normalising
     * them here would make the preview's hue disagree with the tail's.
     * Internal so it is unit-testable without a live BLE stack.
     */
    internal fun onMotionState(state: MotionState, nowNanos: Long) {
        val telemetry = telemetryTracker.update(state, nowNanos)
        previewClock.motion.publish(
            positions = state.encoderPositions.toFloatArray(),
            gravityX = state.gravityX,
            gravityY = state.gravityY,
            gravityZ = state.gravityZ,
            motionEnergy = telemetry.wagSpeed
        )
    }

    /**
     * Maps one FF05-shaped [FftResult] into [previewClock]'s [com.tailapp.led.AudioLevelSource],
     * the same way the firmware's own `FftBuffer::write` would. Internal (rather
     * than private) so it's directly unit-testable without needing a real
     * [FftStreamManager], which requires an Android `Context` this doesn't.
     */
    internal fun onFftResult(result: FftResult) {
        previewClock.audio.write(result.loudness.toInt() and 0xFF, result.bins)
    }

    fun setLayerEffect(layer: Byte, effectId: Byte, blendMode: Byte) {
        viewModelScope.launch { deviceRepository.setLayerEffect(layer, effectId, blendMode) }
    }

    fun setEffectParam(layer: Byte, paramId: Byte, value: Float) {
        viewModelScope.launch { deviceRepository.setEffectParam(layer, paramId, value) }
    }

    fun removeLayer(layer: Byte) {
        viewModelScope.launch { deviceRepository.removeLayer(layer) }
    }

    fun setLayerTransform(layer: Byte, flipX: Boolean, flipY: Boolean, mirrorX: Boolean, mirrorY: Boolean) {
        viewModelScope.launch { deviceRepository.setLayerTransform(layer, flipX, flipY, mirrorX, mirrorY) }
    }

    fun setLayerEnabled(layer: Byte, enabled: Boolean) {
        viewModelScope.launch { deviceRepository.setLayerEnabled(layer, enabled) }
    }

    /**
     * Writes a new effect into the first free layer slot. A slot cleared with
     * `LCMD_REMOVE_LAYER` is reused before the stack is extended, matching the
     * firmware's in-place removal semantics.
     */
    fun addLayer(effectId: Byte, blendMode: Byte) {
        val state = deviceState.value
        val maxLayers = state.capabilities.maxLayers
        val target = state.ledState?.firstFreeLayerIndex(maxLayers) ?: 0
        if (target >= maxLayers) return
        setLayerEffect(target.toByte(), effectId, blendMode)
    }

    fun setLedMatrix(ledsPerRing: List<Byte>) {
        viewModelScope.launch { deviceRepository.setLedMatrix(ledsPerRing) }
    }

    /**
     * Sets the device's output stage: master brightness, gamma, and the
     * current budget.
     *
     * The budget is the one worth explaining to the user: a wearable's
     * regulator cannot deliver what a full-white frame asks for, and without a
     * limit the failure is the rail browning out mid-frame rather than the
     * picture dimming.
     */
    fun setOutputConfig(brightness: Int, gammaEnabled: Boolean, currentLimitMa: Int) {
        viewModelScope.launch {
            deviceRepository.setOutputConfig(brightness, gammaEnabled, currentLimitMa)
        }
    }

    fun clearUploadError() {
        _uploadError.value = null
    }

    /**
     * Scales the picked image to the device's advertised max dimension and sends
     * it with the integrity-checked BEGIN/chunk/FINALIZE flow.
     */
    fun uploadImage(context: Context, uri: Uri, layer: Byte) {
        viewModelScope.launch {
            _uploadError.value = null
            _uploadProgress.value = 0f
            try {
                val dim = deviceState.value.capabilities.imageMaxDim.coerceIn(1, MAX_IMAGE_DIM)
                val rgb = withContext(Dispatchers.IO) { decodeToRgb(context, uri, dim) }
                if (rgb == null) {
                    _uploadError.value = "Could not read the selected image"
                    return@launch
                }

                // ATT payload is MTU-3; the chunk command adds cmd + u16 offset.
                val mtu = deviceRepository.negotiatedMtu.value.let { if (it > 23) it else DEFAULT_MTU }
                val chunkSize = (mtu - 6).coerceAtLeast(MIN_CHUNK_SIZE)

                val ok = deviceRepository.uploadImage(
                    rgb = rgb,
                    width = dim,
                    height = dim,
                    layer = layer,
                    chunkSize = chunkSize,
                    onProgress = { _uploadProgress.value = it }
                )
                if (ok) {
                    // Only cache on a confirmed accept - the preview should
                    // reflect what the device actually has, not what we merely
                    // attempted to send.
                    lastUploadedImage = ImageData(rgb, dim, dim)
                } else {
                    _uploadError.value = "Image upload failed — the device did not accept all writes"
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadImage failed", e)
                _uploadError.value = "Image upload failed: ${e.message ?: e::class.java.simpleName}"
            } finally {
                _uploadProgress.value = null
            }
        }
    }

    private fun decodeToRgb(context: Context, uri: Uri, dim: Int): ByteArray? {
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null

        val scaled = Bitmap.createScaledBitmap(bitmap, dim, dim, true)
        if (scaled != bitmap) bitmap.recycle()

        val pixels = IntArray(dim * dim)
        scaled.getPixels(pixels, 0, dim, 0, 0, dim, dim)
        scaled.recycle()

        val rgb = ByteArray(dim * dim * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            rgb[i * 3] = ((pixel shr 16) and 0xFF).toByte()
            rgb[i * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()
            rgb[i * 3 + 2] = (pixel and 0xFF).toByte()
        }
        return rgb
    }

    companion object {
        private const val TAG = "LedConfigViewModel"
        private const val DEFAULT_MTU = 247
        private const val MIN_CHUNK_SIZE = 20
        private const val MAX_IMAGE_DIM = 32
    }
}
