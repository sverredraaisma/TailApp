package com.tailapp.ui.screen

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.Protocol
import com.tailapp.model.OtaInfo
import com.tailapp.viewmodel.FirmwareSelection
import com.tailapp.viewmodel.FirmwareTransfer
import com.tailapp.viewmodel.FirmwareUpdateViewModel

/**
 * The OTA / DFU delivery screen (A5-1).
 *
 * Everything the user must act on is made visible: which version is running and
 * which is staged, why a picked file would be refused before a byte is sent, the
 * transfer's real progress from the device's echoed offset, and — the one thing
 * silence would get wrong — that the tail rolls a new image back unless the phone
 * stays connected for the rollback dwell after it reboots.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirmwareUpdateScreen(
    viewModel: FirmwareUpdateViewModel,
    onBack: () -> Unit
) {
    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()
    val ota by viewModel.otaInfo.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val connected = deviceState.connectionState == ConnectionState.CONNECTED
    val busy = transfer is FirmwareTransfer.Active

    // The image can be a megabyte, so it is read as raw bytes; the view model is
    // where the accept/refuse decision belongs, so it just gets the bytes.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes != null) {
            viewModel.onImagePicked(displayName(context, uri), bytes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Firmware update") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            VersionsCard(ota)
            Spacer(Modifier.height(16.dp))

            if (ota?.pendingVerify == true) {
                PendingVerifyCard()
                Spacer(Modifier.height(16.dp))
            }

            SelectionCard(
                selection = selection,
                onPick = {
                    // No reliable MIME type for a raw firmware image, so every
                    // document is offered and the header check does the filtering.
                    picker.launch(arrayOf("application/octet-stream", "*/*"))
                },
                pickEnabled = !busy
            )
            Spacer(Modifier.height(16.dp))

            TransferCard(
                transfer = transfer,
                selection = selection,
                connected = connected,
                onInstall = viewModel::install,
                onResume = viewModel::resume,
                onCancel = viewModel::cancel
            )

            Spacer(Modifier.height(16.dp))
            RollbackReminderCard()
        }
    }
}

@Composable
private fun VersionsCard(ota: OtaInfo?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Installed firmware", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (ota == null) {
                Text(
                    "This tail does not report an update slot, so it cannot be updated over the " +
                        "air — only over a cable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            Text("Running: ${ota.running}")
            Text(
                // The other slot becomes valid the moment a finalize succeeds,
                // which is how "installed — restart to apply" is shown.
                if (ota.other != null) {
                    "Other slot: ${ota.other} — restart the tail to run it"
                } else {
                    "Other slot: empty"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PendingVerifyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Confirming new firmware", style = MaterialTheme.typography.titleMedium)
            Text(
                "The running image is not yet confirmed. Stay connected for about " +
                    "${Protocol.OTA_CONFIRM_DWELL_MS / 1000} seconds — disconnect now and the tail " +
                    "rolls back to the old firmware at the next restart, with nothing reporting an error.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SelectionCard(
    selection: FirmwareSelection?,
    onPick: () -> Unit,
    pickEnabled: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Firmware file", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (selection == null) {
                Text(
                    "Pick the tail's .bin firmware image to check it before sending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    selection.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val info = selection.info
                Text(
                    buildString {
                        append("${selection.sizeBytes / 1024} KB")
                        if (info != null) {
                            append(" · ${info.projectName.ifEmpty { "unnamed project" }}")
                            append(" · ${info.version?.toString() ?: "version unknown"}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                selection.blocker?.let { blocker ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        blocker,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onPick, enabled = pickEnabled) {
                Text(if (selection == null) "Choose file" else "Choose a different file")
            }
        }
    }
}

@Composable
private fun TransferCard(
    transfer: FirmwareTransfer,
    selection: FirmwareSelection?,
    connected: Boolean,
    onInstall: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Transfer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (!connected) {
                Text(
                    "The tail is not connected. Reconnect to install firmware.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
            }

            when (val t = transfer) {
                is FirmwareTransfer.Active -> {
                    LinearProgressIndicator(
                        progress = { t.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${t.accepted / 1024} / ${t.total / 1024} KB" +
                            (t.remainingMs?.let { " · ${formatDuration(it)} left" } ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Keep the tail close and the app open until this finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }

                is FirmwareTransfer.Finished -> {
                    Text(t.message, style = MaterialTheme.typography.bodyMedium)
                    if (t.succeeded) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Restart the tail, then stay connected for about " +
                                "${Protocol.OTA_CONFIRM_DWELL_MS / 1000} seconds so it confirms the " +
                                "new image instead of rolling back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        // A short or stalled transfer is still armed on the device
                        // and can carry on from its echoed offset.
                        if (t.resumable) {
                            Button(onClick = onResume, enabled = connected) { Text("Resume") }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (!t.succeeded) {
                            OutlinedButton(
                                onClick = onInstall,
                                enabled = connected && selection?.installable == true
                            ) { Text("Start over") }
                        }
                    }
                }

                FirmwareTransfer.Idle -> {
                    Button(
                        onClick = onInstall,
                        enabled = connected && selection?.installable == true
                    ) { Text("Install") }
                }
            }
        }
    }
}

@Composable
private fun RollbackReminderCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("After installing", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Installing only stages the image. It runs when you restart the tail, and the " +
                    "tail keeps it only after holding a connection for about " +
                    "${Protocol.OTA_CONFIRM_DWELL_MS / 1000} seconds. Walk away in the first few " +
                    "seconds and it quietly reverts to the firmware it had before.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "45 s", "2 min" — a coarse figure for an estimate that is itself coarse. */
private fun formatDuration(millis: Long): String {
    val seconds = (millis + 999) / 1000
    return if (seconds < 90) "${seconds} s" else "${(seconds + 59) / 60} min"
}

/** The document's display name, falling back to the last path segment. */
private fun displayName(context: android.content.Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment ?: "firmware.bin"
}
