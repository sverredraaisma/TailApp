package com.tailapp.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tailapp.ble.ConnectionState
import com.tailapp.di.AppContainer
import com.tailapp.ui.screen.AudioConfigScreen
import com.tailapp.ui.screen.BeatLightScreen
import com.tailapp.ui.screen.BehaviorConfigScreen
import com.tailapp.ui.screen.DeviceOverviewScreen
import com.tailapp.ui.screen.DiagnosticsScreen
import com.tailapp.ui.screen.EffectComposerScreen
import com.tailapp.ui.screen.FirmwareUpdateScreen
import com.tailapp.ui.screen.KeyframeEditorScreen
import com.tailapp.ui.screen.LedConfigScreen
import com.tailapp.ui.screen.MotionConfigScreen
import com.tailapp.ui.screen.ScanScreen
import com.tailapp.viewmodel.AudioConfigViewModel
import com.tailapp.viewmodel.BeatLightViewModel
import com.tailapp.viewmodel.BehaviorConfigViewModel
import com.tailapp.viewmodel.DeviceOverviewViewModel
import com.tailapp.viewmodel.DiagnosticsViewModel
import com.tailapp.viewmodel.EffectComposerViewModel
import com.tailapp.viewmodel.FirmwareUpdateViewModel
import com.tailapp.viewmodel.KeyframeEditorViewModel
import com.tailapp.viewmodel.LedConfigViewModel
import com.tailapp.viewmodel.MotionConfigViewModel
import com.tailapp.viewmodel.ScanViewModel

@Composable
fun TailAppNavHost(
    navController: NavHostController,
    container: AppContainer
) {
    // Losing the tail is an app-level event, not an overview-screen event.
    //
    // The detection used to live inside DeviceOverviewScreen, which Navigation
    // disposes the moment a config screen is pushed: a disconnect while on
    // MotionConfig went unnoticed, and popping back recomposed with a fresh
    // "never was connected" so the dialog never appeared at all. Hoisted here it
    // outlives every destination.
    val deviceState by container.deviceRepository.deviceState.collectAsStateWithLifecycle()
    val connectionState = deviceState.connectionState
    var wasConnected by rememberSaveable { mutableStateOf(false) }
    var showDisconnected by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(connectionState) {
        when (connectionState) {
            ConnectionState.CONNECTED -> {
                wasConnected = true
                showDisconnected = false
            }
            // Only a *lost* link is news. A disconnect the user asked for clears
            // `wasConnected` first (see leaveDevice), so it lands here silently.
            ConnectionState.DISCONNECTED -> if (wasConnected) {
                wasConnected = false
                showDisconnected = true
            }
            ConnectionState.CONNECTING -> Unit
        }
    }

    /** Deliberate exit: tear the connection down, and do not call it a loss. */
    val leaveDevice: () -> Unit = {
        wasConnected = false
        showDisconnected = false
        navController.popBackStack(NavRoutes.Scan.route, inclusive = false)
    }

    if (showDisconnected) {
        AlertDialog(
            onDismissRequest = { showDisconnected = false; leaveDevice() },
            title = { Text("Disconnected") },
            text = { Text("The device has been disconnected.") },
            confirmButton = {
                TextButton(onClick = { showDisconnected = false; leaveDevice() }) {
                    Text("Return to Scan")
                }
            }
        )
    }

    NavHost(navController = navController, startDestination = NavRoutes.Scan.route) {

        composable(NavRoutes.Scan.route) {
            val vm: ScanViewModel = viewModel(factory = rememberFactory {
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
            val vm: DeviceOverviewViewModel = viewModel(factory = rememberFactory(address) {
                DeviceOverviewViewModel(
                    container.deviceRepository,
                    container.fftStreamManager,
                    routeAddress = address
                )
            })
            DeviceOverviewScreen(
                viewModel = vm,
                onNavigateToLed = { navController.navigate(NavRoutes.LedConfig.route) },
                onNavigateToMotion = { navController.navigate(NavRoutes.MotionConfig.route) },
                onNavigateToAudio = { navController.navigate(NavRoutes.AudioConfig.route) },
                onNavigateToBeatLight = { navController.navigate(NavRoutes.BeatLight.route) },
                onNavigateToFirmware = { navController.navigate(NavRoutes.FirmwareUpdate.route) },
                onNavigateToDiagnostics = { navController.navigate(NavRoutes.Diagnostics.route) },
                // The screen has already disconnected by the time this runs.
                onDisconnected = leaveDevice
            )
        }

        composable(NavRoutes.MotionConfig.route) {
            val vm: MotionConfigViewModel = viewModel(factory = rememberFactory {
                MotionConfigViewModel(container.deviceRepository)
            })
            MotionConfigScreen(
                viewModel = vm,
                onEditKeyframes = { navController.navigate(NavRoutes.KeyframeEditor.route) },
                onEditBehavior = { navController.navigate(NavRoutes.BehaviorConfig.route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.BehaviorConfig.route) {
            val vm: BehaviorConfigViewModel = viewModel(factory = rememberFactory {
                BehaviorConfigViewModel(container.deviceRepository, container.behaviorTableStore)
            })
            BehaviorConfigScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.KeyframeEditor.route) {
            val vm: KeyframeEditorViewModel = viewModel(factory = rememberFactory {
                KeyframeEditorViewModel(container.deviceRepository)
            })
            KeyframeEditorScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.LedConfig.route) {
            val vm: LedConfigViewModel = viewModel(factory = rememberFactory {
                LedConfigViewModel(container.deviceRepository, container.fftStreamManager)
            })
            LedConfigScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.AudioConfig.route) {
            val vm: AudioConfigViewModel = viewModel(factory = rememberFactory {
                AudioConfigViewModel(
                    container.deviceRepository,
                    container.fftStreamManager,
                    container.audioPrefs,
                    container.lightingEngine
                )
            })
            AudioConfigScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.BeatLight.route) {
            val vm: BeatLightViewModel = viewModel(factory = rememberFactory {
                BeatLightViewModel(
                    container.deviceRepository,
                    container.lightingEngine,
                    container.lightingPreview,
                    container.compositionLibrary,
                    container.beatLightSession,
                    container.beatLightPrefs
                )
            })
            BeatLightScreen(
                viewModel = vm,
                onEditStack = { navController.navigate(NavRoutes.EffectComposer.route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.EffectComposer.route) {
            val vm: EffectComposerViewModel = viewModel(factory = rememberFactory {
                EffectComposerViewModel(
                    container.lightingEngine,
                    container.compositionLibrary,
                    container.lightingPreview,
                    container.deviceRepository
                )
            })
            EffectComposerScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.FirmwareUpdate.route) {
            val vm: FirmwareUpdateViewModel = viewModel(factory = rememberFactory {
                FirmwareUpdateViewModel(container.deviceRepository)
            })
            FirmwareUpdateScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.Diagnostics.route) {
            val vm: DiagnosticsViewModel = viewModel(factory = rememberFactory {
                DiagnosticsViewModel(container.deviceRepository)
            })
            DiagnosticsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}

/**
 * A `ViewModelProvider.Factory` from a lambda, allocated once per destination
 * rather than once per recomposition.
 *
 * `viewModel()` only consults the factory on a cache miss, so a fresh instance
 * every frame was not creating ViewModels — but it was allocating an object per
 * frame per destination, and it made the call look as though it might.
 */
@Composable
private fun <T : ViewModel> rememberFactory(
    vararg keys: Any?,
    creator: () -> T
): ViewModelProvider.Factory = remember(*keys) {
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <U : ViewModel> create(modelClass: Class<U>): U = creator() as U
    }
}
