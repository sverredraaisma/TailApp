package com.tailapp.model

data class PidGains(
    val kp: Float,
    val ki: Float,
    val kd: Float
)

data class ServoConfig(
    val axis: Int,
    val half: Int,
    val invert: Boolean,
    val muxChannel: Int,
    val pid: PidGains
)

data class ImuConfig(
    val muxChannel: Int,
    val tapEnabled: Boolean
)

data class SystemInfo(
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val firmwarePatch: Int,
    val servos: List<ServoConfig>,
    val imus: List<ImuConfig>
) {
    val firmwareVersion: String get() = "$firmwareMajor.$firmwareMinor.$firmwarePatch"
}

data class DeviceState(
    val connectionState: com.tailapp.ble.ConnectionState = com.tailapp.ble.ConnectionState.DISCONNECTED,
    val systemInfo: SystemInfo? = null,
    val motionState: MotionState? = null,
    val ledState: LedState? = null,
    val fftStreamActive: Boolean = false
)
