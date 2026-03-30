package com.tailapp.ui.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.ui.components.DeviceListItem
import com.tailapp.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onDeviceSelected: (String) -> Unit
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    var permissionsGranted by remember { mutableStateOf(false) }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) viewModel.startScan()
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissions)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TailApp") },
                actions = {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = {
                            if (permissionsGranted) {
                                viewModel.startScan()
                            } else {
                                permissionLauncher.launch(permissions)
                            }
                        }) {
                            Text("Scan")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!permissionsGranted && !isScanning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Bluetooth permissions required",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { permissionLauncher.launch(permissions) }) {
                        Text("Grant Permissions")
                    }
                }
            }
        } else if (devices.isEmpty() && isScanning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Scanning for devices...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
