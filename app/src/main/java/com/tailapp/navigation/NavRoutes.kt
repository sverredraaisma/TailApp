package com.tailapp.navigation

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The app's destinations.
 *
 * Only [DeviceOverview] carries the device address, and only because that is the
 * screen you arrive at from a scan result — it is the one destination that has to
 * say *which* tail it was opened for. Every other destination drives the
 * singleton `DeviceRepository`, which holds one connection at a time, so an
 * address in those routes named a device that nothing downstream ever read: it
 * looked like per-device scoping while providing none. Rather than keep a
 * decorative argument, they are addressless.
 *
 * A real per-device scope (a repository per address, ViewModels scoped to it)
 * would be a different design and is deliberately not attempted here.
 *
 * The config routes live under their own `config/` prefix, not under
 * `device/...`, so none of them can be matched against the `device/{address}`
 * pattern.
 */
sealed class NavRoutes(val route: String) {
    data object Scan : NavRoutes("scan")
    data object DeviceOverview : NavRoutes("device/{address}") {
        fun create(address: String) = "device/${URLEncoder.encode(address, "UTF-8")}"
    }
    data object LedConfig : NavRoutes("config/led")
    data object MotionConfig : NavRoutes("config/motion")
    data object AudioConfig : NavRoutes("config/audio")
    data object BeatLight : NavRoutes("config/beatlight")
    data object EffectComposer : NavRoutes("config/composer")
    data object KeyframeEditor : NavRoutes("config/keyframes")
    data object BehaviorConfig : NavRoutes("config/behavior")
    data object FirmwareUpdate : NavRoutes("config/firmware")
    data object Diagnostics : NavRoutes("config/diagnostics")

    companion object {
        fun decodeAddress(encoded: String?): String? =
            encoded?.let { URLDecoder.decode(it, "UTF-8") }
    }
}
