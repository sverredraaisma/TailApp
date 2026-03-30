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

    companion object {
        fun decodeAddress(encoded: String?): String? =
            encoded?.let { URLDecoder.decode(it, "UTF-8") }
    }
}
