package com.tailapp.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.ui.components.DeviceListItem
import com.tailapp.viewmodel.ScanViewModel

/**
 * The runtime permissions a BLE scan needs on this API level.
 *
 * API 31+ splits Bluetooth into two runtime permissions and the manifest carries
 * `neverForLocation` on the scan permission, so no location grant is involved.
 * On API 26-30 `BLUETOOTH`/`BLUETOOTH_ADMIN` are install-time permissions —
 * nothing to request at runtime — but the platform refuses to *deliver scan
 * results* without a location grant, which is why the legacy branch asks for
 * `ACCESS_FINE_LOCATION` and nothing else. (Coarse would do on 28 and below;
 * fine is required from 29, so fine covers the whole legacy range.)
 */
private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun Context.hasAll(permissions: Array<String>): Boolean = permissions.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * True while the system is still willing to show the dialog — i.e. the user has
 * denied once but not permanently. Evaluated only *after* a request has come
 * back denied, because before the first request it is false for a reason that
 * has nothing to do with a permanent denial.
 */
private fun Activity?.canAskAgain(permissions: Array<String>): Boolean {
    val activity = this ?: return false
    return permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onDeviceSelected: (String) -> Unit
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanError by viewModel.scanError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val permissions = remember { requiredBlePermissions() }
    val snackbarHostState = remember { SnackbarHostState() }

    // Seeded from the real grant state rather than from `false`: a returning user
    // who already granted must not be shown the wall, and a process death must
    // not resurrect a stale "denied".
    var permissionsGranted by rememberSaveable { mutableStateOf(context.hasAll(permissions)) }
    var hasRequested by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        permissionsGranted = granted
        // After the second denial the system stops showing the dialog and the
        // launcher returns denied instantly, forever. Detect that here — it is
        // the only moment the answer is meaningful — so the UI can offer the
        // app-settings route instead of a button that silently does nothing.
        permanentlyDenied = !granted && !context.findActivity().canAskAgain(permissions)
        if (granted) viewModel.startScan()
    }

    /** The only place a scan is started: no BLE call happens without a grant. */
    fun requestOrScan() {
        if (permissionsGranted) {
            viewModel.startScan()
        } else {
            hasRequested = true
            permissionLauncher.launch(permissions)
        }
    }

    LaunchedEffect(Unit) {
        if (permissionsGranted) {
            viewModel.startScan()
        } else if (!hasRequested) {
            hasRequested = true
            permissionLauncher.launch(permissions)
        }
    }

    // A grant made in the system settings screen only reaches us on resume —
    // nothing calls the launcher's callback in that path.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = context.hasAll(permissions)
                if (granted && !permissionsGranted) {
                    permanentlyDenied = false
                    viewModel.startScan()
                }
                permissionsGranted = granted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    LaunchedEffect(permanentlyDenied) {
        if (permanentlyDenied) {
            val action = snackbarHostState.showSnackbar(
                message = "Bluetooth permission is permanently denied.",
                actionLabel = "Settings",
                withDismissAction = true
            )
            if (action == SnackbarResult.ActionPerformed) openAppSettings()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TailApp") },
                actions = {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .semantics { contentDescription = "Scanning for devices" },
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = { requestOrScan() }) {
                            Text("Scan")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Always reachable, whatever the scan/permission state — it needs
            // neither Bluetooth nor a device. Connects to the in-app simulator so
            // the previews and the analysis pipeline can be tried on their own.
            OutlinedButton(
                onClick = { onDeviceSelected(viewModel.connectVirtual()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Null on purpose: the button's own text is the accessible label,
                // and describing the icon as well makes a screen reader say the
                // control twice.
                Icon(Icons.Filled.Science, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Use virtual tail (testing)")
            }
        }
    ) { padding ->
        if (!permissionsGranted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Bluetooth permissions required",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            permanentlyDenied ->
                                "Android will not ask again. Enable the Nearby devices " +
                                    "permission in app settings, then come back — the scan " +
                                    "starts on its own."
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                                "TailApp needs the Nearby devices permission to find and " +
                                    "connect to your tail. It is not used for location."
                            else ->
                                "On this Android version, scanning for Bluetooth devices " +
                                    "requires the location permission. Your location is never " +
                                    "read or sent anywhere."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (permanentlyDenied) {
                        Button(onClick = { openAppSettings() }) { Text("Open app settings") }
                    } else {
                        Button(onClick = {
                            hasRequested = true
                            permissionLauncher.launch(permissions)
                        }) { Text("Grant permissions") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "You can still try the virtual tail below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            scanError != null -> scanError.orEmpty()
                            isScanning -> "Scanning for devices..."
                            else -> "No devices found"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (scanError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!isScanning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // Goes through the same gate as every other entry point:
                        // the grant can have been revoked while the app was away.
                        TextButton(onClick = { requestOrScan() }) { Text("Scan again") }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(devices, key = { it.address }) { device ->
                    DeviceListItem(
                        device = device,
                        onClick = {
                            viewModel.connect(device.address)
                            onDeviceSelected(device.address)
                        }
                    )
                }
            }
        }
    }
}
