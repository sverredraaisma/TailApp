package com.tailapp.navigation

sealed class NavRoutes(val route: String) {
    data object Scan : NavRoutes("scan")
    data object Device : NavRoutes("device/{address}") {
        fun createRoute(address: String) = "device/$address"
    }
    data object Joystick : NavRoutes("joystick")
}
