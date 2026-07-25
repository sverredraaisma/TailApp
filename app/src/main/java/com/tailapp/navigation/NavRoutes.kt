package com.tailapp.navigation

import java.net.URLDecoder
import java.net.URLEncoder

sealed class NavRoutes(val route: String) {
    data object Scan : NavRoutes("scan")
    data object DeviceOverview : NavRoutes("device/{address}") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}"
    }
    data object LedConfig : NavRoutes("device/{address}/led") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}/led"
    }
    data object MotionConfig : NavRoutes("device/{address}/motion") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}/motion"
    }
    data object AudioConfig : NavRoutes("device/{address}/audio") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}/audio"
    }
    data object BeatLight : NavRoutes("device/{address}/beatlight") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}/beatlight"
    }
    data object EffectComposer : NavRoutes("device/{address}/composer") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}/composer"
    }
    data object KeyframeEditor : NavRoutes("device/{address}/keyframes") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}/keyframes"
    }
    data object BehaviorConfig : NavRoutes("device/{address}/behavior") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}/behavior"
    }
    data object FirmwareUpdate : NavRoutes("device/{address}/firmware") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}/firmware"
    }
    data object Diagnostics : NavRoutes("device/{address}/diagnostics") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}/diagnostics"
    }

    companion object {
        fun decodeAddress(encoded: String?): String? =
            encoded?.let { URLDecoder.decode(it, "UTF-8") }
    }
}
