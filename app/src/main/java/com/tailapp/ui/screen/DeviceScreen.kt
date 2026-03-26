package com.tailapp.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.ble.ConnectionState
import com.tailapp.ui.components.CharacteristicCard
import com.tailapp.viewmodel.DeviceViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    viewModel: DeviceViewModel,
    address: String,
    onNavigateBack: () -> Unit,
    onNavigateToJoystick: () -> Unit = {}
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val services by viewModel.discoveredServices.collectAsStateWithLifecycle()
    val characteristicValues = remember { mutableStateMapOf<UUID, ByteArray>() }

    LaunchedEffect(address) {
        viewModel.connect(address)
    }

    LaunchedEffect(Unit) {
        viewModel.characteristicUpdate.collect { update ->
            characteristicValues[update.uuid] = update.value
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Address: $address",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Status: ${connectionState.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (connectionState) {
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                    }
                )

                if (connectionState == ConnectionState.DISCONNECTED) {
                    Button(
                        onClick = { viewModel.connect(address) },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Reconnect")
                    }
                }

                if (connectionState == ConnectionState.CONNECTED) {
                    Button(
                        onClick = onNavigateToJoystick,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Joystick Control")
                    }
                }
            }

            services.forEach { service ->
                item {
                    Text(
                        text = "Service: ${service.uuid}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                items(service.characteristics, key = { it.uuid }) { characteristic ->
                    CharacteristicCard(
                        serviceUuid = service.uuid,
                        characteristic = characteristic,
                        lastValue = characteristicValues[characteristic.uuid],
                        onRead = {
                            viewModel.readCharacteristic(service.uuid, characteristic.uuid)
                        },
                        onWrite = { value ->
                            viewModel.writeCharacteristic(service.uuid, characteristic.uuid, value)
                        },
                        onNotify = {
                            viewModel.enableNotifications(service.uuid, characteristic.uuid)
                        }
                    )
                }
            }
        }
    }
}
