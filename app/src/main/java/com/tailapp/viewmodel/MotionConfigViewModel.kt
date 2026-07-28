package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.model.DeviceState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MotionConfigViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    fun selectPattern(patternId: Byte) {
        viewModelScope.launch { deviceRepository.selectPattern(patternId) }
    }

    fun setPatternParam(paramId: Byte, value: Float) {
        viewModelScope.launch { deviceRepository.setPatternParam(paramId, value) }
    }

    /** [muxChannel] is optional — pass null to leave the encoder channel untouched. */
    fun setServoConfig(servoId: Byte, axis: Byte, half: Byte, invert: Byte, muxChannel: Byte? = null) {
        viewModelScope.launch { deviceRepository.setServoConfig(servoId, axis, half, invert, muxChannel) }
    }

    /**
     * Sets the open-loop motion limits and stall sensitivity for one motor.
     * These shape motion on the current firmware; the PID gains they replaced
     * were retired in protocol v6.
     */
    fun setMotionLimits(
        servoId: Byte,
        maxVelocity: Float,
        maxAcceleration: Float,
        maxJerk: Float,
        stallThreshold: Byte
    ) {
        viewModelScope.launch {
            deviceRepository.setMotionLimits(
                servoId, maxVelocity, maxAcceleration, maxJerk, stallThreshold
            )
        }
    }

    private var puppetJob: Job? = null

    /**
     * The pose the resend loop is currently holding. Written from the pointer
     * thread, read from the loop, hence `@Volatile` — the loop must see the
     * latest sample, never a torn or stale one.
     */
    @Volatile
    private var puppetTarget: FloatArray? = null

    private val _puppetError = MutableStateFlow<String?>(null)

    /** Non-null when a drag could not be turned into a target. One-shot; cleared on read. */
    val puppetError: StateFlow<String?> = _puppetError.asStateFlow()

    fun clearPuppetError() {
        _puppetError.value = null
    }

    /**
     * Drives the tail directly from a drag, as a fraction of each axis's travel.
     *
     * Keeps re-sending while held rather than sending once: the device ages
     * streamed targets out after 500 ms and hands control back to its own
     * pattern, which is what stops an abandoned stream leaving the tail holding
     * a pose. A single write would therefore be a twitch, not a hold.
     *
     * **The resend loop is started once and then only re-aimed.** A pointer
     * emits 60-120 samples a second; restarting the loop per sample issued an
     * FF0B write per sample, which saturates the connection interval and starves
     * FF0A pixels and FF09 acknowledgements. So a sample updates
     * [puppetTarget] and returns, and the one long-lived loop keeps writing at
     * [PUPPET_RESEND_MILLIS] whatever the finger is doing.
     *
     * @param x left/right, `-1..1`. @param y up/down, `-1..1`.
     */
    fun startPuppet(x: Float, y: Float) {
        val state = deviceRepository.deviceState.value.motionState
        if (state == null) {
            // Silence here reads as a broken pad: the tail has simply not sent
            // its first FF02 notify yet, so there are no travel limits to scale
            // the drag against.
            _puppetError.value =
                "The tail has not reported its position yet, so there is nothing to steer. " +
                    "Wait for it to connect and try again."
            return
        }
        // Fractions of the *configured* travel, so the pad means the same thing
        // whatever limits the user has set.
        fun scale(f: Float, min: Float, max: Float): Float {
            val centre = (max + min) / 2f
            val half = (max - min) / 2f
            return centre + f.coerceIn(-1f, 1f) * half
        }
        val tx = scale(x, state.xAxisMin, state.xAxisMax)
        val ty = scale(y, state.yAxisMin, state.yAxisMax)
        puppetTarget = floatArrayOf(tx, tx, ty, ty)

        if (puppetJob?.isActive == true) return
        puppetJob = viewModelScope.launch {
            while (isActive) {
                puppetTarget?.let { deviceRepository.streamMotionTargets(it) }
                delay(PUPPET_RESEND_MILLIS)
            }
        }
    }

    /**
     * Stops driving. The device's own timeout then hands the tail back to its
     * pattern — deliberately not an explicit "release" command, because the
     * timeout has to work for the disconnect case anyway and one mechanism is
     * better than two.
     */
    fun stopPuppet() {
        puppetJob?.cancel()
        puppetJob = null
        puppetTarget = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPuppet()
    }

    fun calibrateZero() {
        viewModelScope.launch { deviceRepository.calibrateZero() }
    }

    fun setAxisLimits(axis: Byte, min: Float, max: Float) {
        viewModelScope.launch { deviceRepository.setAxisLimits(axis, min, max) }
    }

    fun setImuTap(imuId: Byte, enabled: Boolean) {
        viewModelScope.launch { deviceRepository.setImuTap(imuId, enabled) }
    }

    companion object {
        /**
         * Comfortably inside the device's 500 ms target timeout, so a held pose
         * stays held even if a couple of writes are lost.
         */
        private const val PUPPET_RESEND_MILLIS = 100L

        /**
         * The mechanism's full angular span either side of zero, and therefore
         * the span an axis-limit slider has to cover.
         *
         * Both ends of a limit run over the *whole* span rather than min being
         * pinned to the negative half and max to the positive one: an
         * offset-mounted tail can have its entire travel on one side of zero,
         * and a slider that cannot represent that clamps the thumb to 0 while
         * the label reads the real value — so any touch writes a wrong limit.
         *
         * A constant because the FF06 capability block does not report a travel
         * range; if one is ever added, this is the single place to read it from.
         */
        const val AXIS_TRAVEL_DEG = 180f
    }
}
