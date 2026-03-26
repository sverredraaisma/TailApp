package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.ble.BleConnectionManager
import com.tailapp.ble.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class JoystickViewModel(
    private val connectionManager: BleConnectionManager
) : ViewModel() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
        val SERVO_CMD_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
    }

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    private val _servoAngles = MutableStateFlow(listOf(90, 90, 90, 90))
    val servoAngles: StateFlow<List<Int>> = _servoAngles.asStateFlow()

    init {
        viewModelScope.launch {
            var lastWritten = listOf(90, 90, 90, 90)
            _servoAngles.collect { angles ->
                for (i in angles.indices) {
                    if (angles[i] != lastWritten[i]) {
                        connectionManager.writeCharacteristic(
                            SERVICE_UUID,
                            SERVO_CMD_UUID,
                            byteArrayOf(i.toByte(), angles[i].toByte()),
                            noResponse = true
                        )
                    }
                }
                lastWritten = angles
            }
        }
    }

    fun onLeftJoystickChanged(x: Float, y: Float) {
        _servoAngles.value = _servoAngles.value.toMutableList().also {
            it[0] = ((x + 1f) / 2f * 180f).toInt().coerceIn(0, 180)
            it[1] = ((y + 1f) / 2f * 180f).toInt().coerceIn(0, 180)
        }
    }

    fun onRightJoystickChanged(x: Float, y: Float) {
        _servoAngles.value = _servoAngles.value.toMutableList().also {
            it[2] = ((x + 1f) / 2f * 180f).toInt().coerceIn(0, 180)
            it[3] = ((y + 1f) / 2f * 180f).toInt().coerceIn(0, 180)
        }
    }
}
