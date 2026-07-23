package com.tailapp.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.model.DeviceState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LedConfigViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    private val _uploadProgress = MutableStateFlow<Float?>(null)
    val uploadProgress: StateFlow<Float?> = _uploadProgress.asStateFlow()

    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError: StateFlow<String?> = _uploadError.asStateFlow()

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
                if (!ok) {
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
