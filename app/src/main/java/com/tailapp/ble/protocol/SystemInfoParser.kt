package com.tailapp.ble.protocol

import com.tailapp.model.ImuConfig
import com.tailapp.model.PidGains
import com.tailapp.model.ServoConfig
import com.tailapp.model.SystemInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SystemInfoParser {

    fun parse(data: ByteArray): SystemInfo? {
        if (data.size < 4) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val major = buf.get().toInt() and 0xFF
        val minor = buf.get().toInt() and 0xFF
        val patch = buf.get().toInt() and 0xFF
        val numServos = buf.get().toInt() and 0xFF

        if (buf.remaining() < numServos * 16) return null

        val servos = List(numServos) {
            val axis = buf.get().toInt() and 0xFF
            val half = buf.get().toInt() and 0xFF
            val invert = (buf.get().toInt() and 0xFF) != 0
            val muxChannel = buf.get().toInt() and 0xFF
            val kp = buf.float
            val ki = buf.float
            val kd = buf.float
            ServoConfig(axis, half, invert, muxChannel, PidGains(kp, ki, kd))
        }

        if (buf.remaining() < 1) return SystemInfo(major, minor, patch, servos, emptyList())
        val numImus = buf.get().toInt() and 0xFF

        if (buf.remaining() < numImus * 2) return SystemInfo(major, minor, patch, servos, emptyList())

        val imus = List(numImus) {
            val muxChannel = buf.get().toInt() and 0xFF
            val tapEnabled = (buf.get().toInt() and 0xFF) != 0
            ImuConfig(muxChannel, tapEnabled)
        }

        return SystemInfo(major, minor, patch, servos, imus)
    }
}
