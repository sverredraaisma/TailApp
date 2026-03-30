package com.tailapp.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.model.DeviceState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class LedConfigViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    private val _uploadProgress = MutableStateFlow<Float?>(null)
    val uploadProgress: StateFlow<Float?> = _uploadProgress.asStateFlow()

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

    fun addLayer(effectId: Byte, blendMode: Byte) {
        val currentLayers = deviceState.value.ledState?.layers?.size ?: 0
        if (currentLayers < 8) {
            setLayerEffect(currentLayers.toByte(), effectId, blendMode)
        }
    }

    fun setLedMatrix(ledsPerRing: List<Byte>) {
        viewModelScope.launch { deviceRepository.setLedMatrix(ledsPerRing) }
    }

    fun uploadImage(context: Context, uri: Uri, layer: Byte) {
        viewModelScope.launch {
            _uploadProgress.value = 0f

            val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) {
                _uploadProgress.value = null
                return@launch
            }

            val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            if (scaled != bitmap) bitmap.recycle()
            val rgbData = ByteArray(32 * 32 * 3)
            for (y in 0 until 32) {
                for (x in 0 until 32) {
                    val pixel = scaled.getPixel(x, y)
                    val idx = (y * 32 + x) * 3
                    rgbData[idx] = ((pixel shr 16) and 0xFF).toByte()
                    rgbData[idx + 1] = ((pixel shr 8) and 0xFF).toByte()
                    rgbData[idx + 2] = (pixel and 0xFF).toByte()
                }
            }
            scaled.recycle()

            // MTU - 3 (ATT overhead) - 3 (command header: cmd + u16 offset)
            val negotiatedMtu = deviceRepository.bleManager.negotiatedMtu.value
            val mtu = if (negotiatedMtu > 23) negotiatedMtu else 247
            val chunkSize = (mtu - 6).coerceAtLeast(20)
            val totalChunks = (rgbData.size + chunkSize - 1) / chunkSize

            for (i in 0 until totalChunks) {
                val offset = i * chunkSize
                val end = minOf(offset + chunkSize, rgbData.size)
                val chunk = rgbData.copyOfRange(offset, end)
                deviceRepository.uploadImageChunk(offset, chunk)
                _uploadProgress.value = (i + 1).toFloat() / totalChunks
                delay(50) // BLE pacing
            }

            deviceRepository.finalizeImage(32, 32, layer)
            _uploadProgress.value = null
        }
    }
}
