package com.tailapp.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tailapp.di.AppContainer
import com.tailapp.ui.screen.AudioConfigScreen
import com.tailapp.ui.screen.DeviceOverviewScreen
import com.tailapp.ui.screen.LedConfigScreen
import com.tailapp.ui.screen.MotionConfigScreen
import com.tailapp.ui.screen.ScanScreen
import com.tailapp.viewmodel.AudioConfigViewModel
import com.tailapp.viewmodel.DeviceOverviewViewModel
import com.tailapp.viewmodel.LedConfigViewModel
import com.tailapp.viewmodel.MotionConfigViewModel
import com.tailapp.viewmodel.ScanViewModel

@Composable
fun TailAppNavHost(
    navController: NavHostController,
    container: AppContainer
) {
    NavHost(navController = navController, startDestination = NavRoutes.Scan.route) {

        composable(NavRoutes.Scan.route) {
            val vm: ScanViewModel = viewModel(factory = factory {
                ScanViewModel(container.bleScanner, container.deviceRepository)
            })
            ScanScreen(
                viewModel = vm,
                onDeviceSelected = { address ->
                    navController.navigate(NavRoutes.DeviceOverview.create(address))
                }
            )
        }

        composable(
            route = NavRoutes.DeviceOverview.route,
            arguments = listOf(navArgument("address") { type = NavType.StringType })
        ) { backStackEntry ->
            val address = NavRoutes.decodeAddress(
                backStackEntry.arguments?.getString("address")
            ) ?: return@composable
            val vm: DeviceOverviewViewModel = viewModel(factory = factory {
                DeviceOverviewViewModel(container.deviceRepository, container.fftStreamManager)
            })
            DeviceOverviewScreen(
                viewModel = vm,
                onNavigateToLed = { navController.navigate(NavRoutes.LedConfig.create(address)) },
                onNavigateToMotion = { navController.navigate(NavRoutes.MotionConfig.create(address)) },
                onNavigateToAudio = { navController.navigate(NavRoutes.AudioConfig.create(address)) },
                onDisconnected = {
                    navController.popBackStack(NavRoutes.Scan.route, inclusive = false)
                }
            )
        }

        composable(
            route = NavRoutes.MotionConfig.route,
            arguments = listOf(navArgument("address") { type = NavType.StringType })
        ) {
            val vm: MotionConfigViewModel = viewModel(factory = factory {
                MotionConfigViewModel(container.deviceRepository)
            })
            MotionConfigScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(
            route = NavRoutes.LedConfig.route,
            arguments = listOf(navArgument("address") { type = NavType.StringType })
        ) {
            val vm: LedConfigViewModel = viewModel(factory = factory {
                LedConfigViewModel(container.deviceRepository)
            })
            LedConfigScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(
            route = NavRoutes.AudioConfig.route,
            arguments = listOf(navArgument("address") { type = NavType.StringType })
        ) {
            val vm: AudioConfigViewModel = viewModel(factory = factory {
                AudioConfigViewModel(container.deviceRepository, container.fftStreamManager, container.audioPrefs)
            })
            AudioConfigScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}

/** Helper to create a simple ViewModelProvider.Factory from a lambda. */
private fun <T : ViewModel> factory(creator: () -> T): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <U : ViewModel> create(modelClass: Class<U>): U = creator() as U
    }
}
