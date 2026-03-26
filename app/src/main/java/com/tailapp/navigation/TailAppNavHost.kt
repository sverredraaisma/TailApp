package com.tailapp.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tailapp.TailApp
import com.tailapp.ui.screen.DeviceScreen
import com.tailapp.ui.screen.JoystickScreen
import com.tailapp.ui.screen.ScanScreen
import com.tailapp.viewmodel.DeviceViewModel
import com.tailapp.viewmodel.JoystickViewModel
import com.tailapp.viewmodel.ScanViewModel

@Composable
fun TailAppNavHost() {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as TailApp

    NavHost(navController = navController, startDestination = NavRoutes.Scan.route) {
        composable(NavRoutes.Scan.route) {
            val viewModel = ScanViewModel(app.container.bleScanner)
            ScanScreen(
                viewModel = viewModel,
                onDeviceSelected = { address ->
                    navController.navigate(NavRoutes.Device.createRoute(address))
                }
            )
        }
        composable(
            route = NavRoutes.Device.route,
            arguments = listOf(navArgument("address") { type = NavType.StringType })
        ) { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address") ?: return@composable
            val viewModel = DeviceViewModel(app.container.bleConnectionManager)
            DeviceScreen(
                viewModel = viewModel,
                address = address,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToJoystick = {
                    navController.navigate(NavRoutes.Joystick.route)
                }
            )
        }
        composable(NavRoutes.Joystick.route) {
            val viewModel = JoystickViewModel(app.container.bleConnectionManager)
            JoystickScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
