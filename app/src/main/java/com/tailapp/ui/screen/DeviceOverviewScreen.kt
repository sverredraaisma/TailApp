package com.tailapp.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.ble.protocol.Protocol
import com.tailapp.ble.protocol.SystemCommands
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.model.BatteryPolicy
import com.tailapp.model.BatteryStatus
import com.tailapp.model.BondedPeer
import com.tailapp.model.DeviceInformation
import com.tailapp.model.MotionPattern
import com.tailapp.model.ProfileSlot
import com.tailapp.ui.components.ProfileSlotRow
import com.tailapp.ui.components.SubsystemStatusCard
import com.tailapp.ui.theme.StatusGreen
import com.tailapp.ui.theme.StatusRed
import com.tailapp.viewmodel.DeviceOverviewViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceOverviewScreen(
    viewModel: DeviceOverviewViewModel,
    onNavigateToLed: () -> Unit,
    onNavigateToMotion: () -> Unit,
    onNavigateToAudio: () -> Unit,
    onNavigateToBeatLight: () -> Unit,
    onNavigateToFirmware: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onDisconnected: () -> Unit
) {
    val state by viewModel.deviceState.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val adminStatus by viewModel.adminStatus.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    /**
     * Replaces whatever is on screen instead of queueing behind it.
     *
     * `showSnackbar` suspends for the whole display duration, so a sequential
     * collector turns a handful of taps into a minute of replay while the events
     * that matter are dropped. Only the newest message is ever worth showing.
     */
    suspend fun showLatest(message: String) {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
    }

    // A rename or a forget-bond that the device refused. These are the two
    // commands whose answer changes what the user should believe, so the verdict
    // is shown either way rather than only on failure.
    LaunchedEffect(adminStatus) {
        adminStatus?.let {
            showLatest(it.message)
            viewModel.clearAdminStatus()
        }
    }

    // Surface rejected commands (FF09) instead of silently assuming success.
    // One delivery per rejection: `lastCommandResult` is sticky, so observing it
    // would collapse two identical failures into one and re-show a stale one
    // after a rotation.
    LaunchedEffect(Unit) {
        viewModel.commandFailures.collectLatest { result ->
            showLatest(
                "${result.characteristicName} command 0x%02X rejected: ${result.result.message}"
                    .format(result.commandId)
            )
        }
    }

    // Taps are momentary events, so they belong in a snackbar rather than in
    // state. `collectLatest` cancels the message in flight when a newer event
    // arrives, which is the conflation this needs.
    LaunchedEffect(Unit) {
        viewModel.systemEvents.collectLatest { event ->
            when (event) {
                SystemEvent.TAP_BASE -> showLatest("Tap detected (base)")
                SystemEvent.TAP_TIP -> showLatest("Tap detected (tip)")
                SystemEvent.CONFIG_CHANGED -> showLatest("Device config reloaded")
                // Deliberately not a snackbar: a stall latches every motor off
                // until the user acts, so it needs a banner that persists (see
                // below) rather than a message that disappears on its own.
                SystemEvent.STALL -> Unit
                // The behavior screen shows the engine's state and the reason it
                // changed, live; a mood change is not news on the overview.
                SystemEvent.BEHAVIOR_STATE -> Unit
                // Same reasoning as a stall: the device derates itself until the
                // pack recovers, so the battery card carries it rather than a
                // message that scrolls away.
                SystemEvent.BATTERY_LOW,
                SystemEvent.BATTERY_CRITICAL,
                SystemEvent.BATTERY_NORMAL -> Unit
                // TMC2209 driver health (overtemp / short / open load). The
                // diagnostics screen owns these: FF0C carries the live fault
                // masks, so the overview would only be duplicating a state the
                // user can already see, one edge at a time.
                else -> Unit
            }
        }
    }

    // Losing the tail is detected above the NavHost, not here: this screen is
    // disposed the moment a config screen is pushed, and a detector that only
    // exists on the overview cannot see a disconnect that happens on any other
    // destination. See TailAppNavHost.

    /** Tear down and leave. The toolbar and the system back gesture must agree. */
    fun leaveDevice() {
        viewModel.disconnect()
        onDisconnected()
    }

    // Without this, a back gesture pops the entry and leaves GATT open and FF05
    // streaming while the scan screen starts scanning over a live connection.
    BackHandler { leaveDevice() }

    // The slot *index*, not the slot: a ProfileSlot is not Parcelable, and the
    // list it came from is rebuilt from the device anyway.
    var renameSlotIndex by rememberSaveable { mutableIntStateOf(-1) }
    if (renameSlotIndex >= 0) {
        val target = state.profiles.firstOrNull { it.index == renameSlotIndex }
            ?: ProfileSlot(renameSlotIndex, occupied = false, name = null)
        RenameProfileDialog(
            slot = target,
            onDismiss = { renameSlotIndex = -1 },
            onConfirm = { name ->
                viewModel.renameProfile(target.index.toByte(), name)
                renameSlotIndex = -1
            }
        )
    }

    var renamingDevice by rememberSaveable { mutableStateOf(false) }
    if (renamingDevice) {
        RenameDeviceDialog(
            current = state.systemInfo?.deviceName.orEmpty(),
            onDismiss = { renamingDevice = false },
            onConfirm = { name ->
                viewModel.renameDevice(name)
                renamingDevice = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tail Controller") },
                navigationIcon = {
                    IconButton(onClick = { leaveDevice() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Disconnect and return to the device list"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.toggleFftStream() }) {
                        Text(if (isStreaming) "FFT ON" else "FFT OFF")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            val si = state.systemInfo

            // Opened without a live link — a deep link straight to this route, or
            // a launch that outlived the connection. The repository is a
            // singleton with one connection, so everything below would otherwise
            // render whatever it happens to hold (or nothing) while the route
            // claims to be showing this address.
            if (!isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Not connected", style = MaterialTheme.typography.titleMedium)
                        Text(
                            viewModel.routeAddress?.let {
                                "Nothing is connected. This screen was opened for $it — " +
                                    "the values below are stale until it connects."
                            } ?: "Nothing is connected; the values below are stale.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (viewModel.routeAddress != null) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { viewModel.connect() }) {
                                Text("Connect to ${viewModel.routeAddress}")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Protocol compatibility warning — the FF06 layout shifts between
            // protocol versions, so a mismatch means parsed values may be wrong.
            if (si != null && !si.isProtocolSupported) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Protocol mismatch", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Device speaks protocol v${si.protocolVersion}, this app targets " +
                                "v${Protocol.SUPPORTED_PROTOCOL_VERSION}. Some readings may be wrong.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            BatteryCard(state.battery)
            Spacer(Modifier.height(16.dp))

            // A freshly installed image is on probation: the device confirms it
            // only after holding a connection for the rollback dwell, and rolls
            // back silently otherwise. This is where the user lands after
            // reconnecting post-update, so the "stay connected" contract has to be
            // visible here and not only on the firmware screen.
            if (si?.ota?.pendingVerify == true) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Confirming new firmware", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The tail is running a new image that is not yet confirmed. Stay " +
                                "connected for about ${Protocol.OTA_CONFIRM_DWELL_MS / 1000} seconds — " +
                                "disconnecting now rolls it back to the old firmware, with nothing " +
                                "reporting an error.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // A stall drops every motor to freewheel and latches them off until
            // explicitly re-enabled. Without this the tail simply stops and the
            // app says nothing about why, or how to recover.
            //
            // A critical battery also releases the motors, and reports it the
            // same way in the FF06 motion block. Blaming that on StallGuard would
            // send the user looking for an obstruction that isn't there, so the
            // battery card owns the explanation while the policy is engaged.
            if (si != null && si.motorsStalled && state.battery.policy != BatteryPolicy.CRITICAL) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Motors stopped", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A motor stalled, so all motors were released and will stay off " +
                                "until re-enabled. Clear whatever is blocking the tail first.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.setMotorsEnabled(true) }) {
                            Text("Re-enable motors")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Subsystem Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Subsystems", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    SubsystemStatusCard(
                        "Bluetooth",
                        if (isConnected) "Connected" else "Disconnected",
                        if (isConnected) StatusGreen else StatusRed
                    )
                    SubsystemStatusCard(
                        "Servos",
                        when {
                            si == null -> "Unknown"
                            si.motorsStalled && state.battery.policy == BatteryPolicy.CRITICAL ->
                                "${si.servos.size} configured — parked, flat pack"
                            si.motorsStalled -> "${si.servos.size} configured — stalled, off"
                            else -> "${si.servos.size} configured"
                        },
                        when {
                            si == null -> StatusRed
                            si.motorsStalled -> StatusRed
                            else -> StatusGreen
                        }
                    )
                    SubsystemStatusCard(
                        "LEDs",
                        state.ledState?.let {
                            "${it.totalLeds} LEDs, ${it.occupiedLayerIndices.size} layers"
                        } ?: "Unknown",
                        if (state.ledState != null) StatusGreen else StatusRed
                    )
                    SubsystemStatusCard(
                        "IMUs",
                        if (si != null) "${si.imus.size} sensors" else "Unknown",
                        if (si != null) StatusGreen else StatusRed
                    )
                    SubsystemStatusCard(
                        "I2C",
                        if (si != null) "OK" else "Unknown",
                        if (si != null) StatusGreen else StatusRed
                    )

                    if (si != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Firmware ${si.firmwareVersion} · protocol v${si.protocolVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val caps = si.capabilities
                        Text(
                            if (caps != null) {
                                "${caps.patternIds.size} patterns · ${caps.effectIds.size} effects · " +
                                    "up to ${caps.maxLayers} layers"
                            } else {
                                "Capabilities not reported — using built-in defaults"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Motion Summary
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Motion", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val ms = state.motionState
                    if (ms != null) {
                        val pattern = MotionPattern.fromId(ms.activePatternId)
                        Text("Pattern: ${pattern?.displayName ?: "Unknown"}")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Encoders: ${ms.encoderPositions.joinToString { "%.1f".format(it) + "°" }}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Gravity: %.2f, %.2f, %.2f g".format(ms.gravityX, ms.gravityY, ms.gravityZ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("No data", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // LED Summary
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LEDs", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val ls = state.ledState
                    if (ls != null) {
                        Text("${ls.totalLeds} LEDs across ${ls.numRings} rings")
                        // Cleared slots read back as effect 0xFF; don't list them as layers.
                        ls.occupiedLayerIndices.forEach { i ->
                            val layer = ls.layers[i]
                            val effectName = layer.effect?.displayName ?: "Effect ${layer.effectId}"
                            val blendName = layer.blend?.displayName ?: "Blend ${layer.blendMode}"
                            val enabled = if (layer.enabled) "" else " (disabled)"
                            Text(
                                "  Layer $i: $effectName / $blendName$enabled",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (ls.occupiedLayerIndices.isEmpty()) {
                            Text("No layers configured", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("No data", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Profile Management
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Profiles", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    val profiles = state.profiles.ifEmpty {
                        // Before the first FF08 read lands, still offer the slots.
                        List(Protocol.MAX_PROFILE_SLOTS) { ProfileSlot(it, occupied = false, name = null) }
                    }
                    profiles.forEachIndexed { i, slot ->
                        ProfileSlotRow(
                            slot = slot,
                            onSave = { viewModel.saveProfile(slot.index.toByte()) },
                            onLoad = { viewModel.loadProfile(slot.index.toByte()) },
                            onDelete = { viewModel.deleteProfile(slot.index.toByte()) },
                            onRename = { renameSlotIndex = slot.index }
                        )
                        if (i < profiles.lastIndex) HorizontalDivider()
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            DeviceIdentityCard(
                deviceName = state.systemInfo?.deviceName,
                information = state.deviceInformation,
                bonds = state.systemInfo?.bonds,
                onRename = { renamingDevice = true },
                onForgetBond = { viewModel.forgetBond(it) },
                onForgetAllBonds = { viewModel.forgetAllBonds() }
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Navigation buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onNavigateToMotion) { Text("Motion Config") }
                Button(onClick = onNavigateToLed) { Text("LED Config") }
                Button(onClick = onNavigateToAudio) { Text("Audio Config") }
                Button(onClick = onNavigateToBeatLight) { Text("BeatLight") }
                Button(onClick = onNavigateToDiagnostics) { Text("Diagnostics") }
                // Only offered when the device published an OTA block — firmware
                // that cannot be updated over the air shows no entry point at all.
                if (si?.ota != null) {
                    Button(onClick = onNavigateToFirmware) { Text("Firmware") }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { viewModel.refreshProfiles() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Refresh profile list") }
        }
    }
}

/**
 * Pack level, and — while the device is derating itself — what it has actually
 * done about it.
 *
 * The consequence text is the whole reason the firmware reports a policy state:
 * a user who finds the tail dim and slow otherwise cannot tell a working
 * low-battery policy from a broken device.
 */
@Composable
private fun BatteryCard(battery: BatteryStatus) {
    val derated = battery.isDerated
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (derated) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Battery", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val percent = battery.percent
            Text(
                if (percent != null) "$percent%" else "--",
                style = MaterialTheme.typography.headlineSmall
            )
            if (percent == null) {
                // Not the same as 0 %: this device cannot measure its pack at
                // all, and the firmware treats an unmeasurable pack as healthy
                // rather than as empty.
                Text(
                    "This device reports no pack level. Nothing is derated — an unknown " +
                        "level is treated as normal, not as flat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (battery.policy) {
                BatteryPolicy.LOW -> Text(
                    "Low battery — the device has derated itself",
                    style = MaterialTheme.typography.titleSmall
                )
                BatteryPolicy.CRITICAL -> Text(
                    "Critical battery — the tail is parked",
                    style = MaterialTheme.typography.titleSmall
                )
                // Normal is not worth a line of its own, and null means the
                // device has said nothing: the policy events fire on a crossing
                // only, so silence is the usual case, not an error.
                BatteryPolicy.NORMAL, null -> Unit
            }
            battery.policy?.consequence?.let { consequence ->
                Spacer(Modifier.height(4.dp))
                Text(consequence, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Device name, the 0x180A identity strings, and the bonds the device holds. */
@Composable
private fun DeviceIdentityCard(
    deviceName: String?,
    information: DeviceInformation?,
    bonds: List<BondedPeer>?,
    onRename: () -> Unit,
    onForgetBond: (Int) -> Unit,
    onForgetAllBonds: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Text(
                when {
                    deviceName == null -> "Name not reported"
                    deviceName.isBlank() -> "Unnamed — advertising the firmware default"
                    else -> deviceName
                }
            )
            information?.let { info ->
                listOfNotNull(
                    info.manufacturer?.let { "Manufacturer: $it" },
                    info.modelNumber?.let { "Model: $it" },
                    info.firmwareRevision?.let { "Firmware: $it" },
                    info.hardwareRevision?.let { "Hardware: $it" }
                ).forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRename) { Text("Rename device") }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text("Paired devices", style = MaterialTheme.typography.titleSmall)
            when {
                // Null is "this firmware does not publish a bond list", which is
                // not the same fact as "no phone is paired" and must not read
                // like it.
                bonds == null -> Text(
                    "Not reported by this firmware.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                bonds.isEmpty() -> Text(
                    "None. The next phone to connect will pair.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> bonds.forEach { bond ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(bond.address, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "index ${bond.index} · ${bond.addressTypeName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onForgetBond(bond.index) }) { Text("Forget") }
                    }
                }
            }

            if (!bonds.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onForgetAllBonds) { Text("Forget all bonds") }
            }
        }
    }
}

@Composable
private fun RenameDeviceDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // Saveable: a rotation with this dialog open must not throw the typed name away.
    var name by rememberSaveable { mutableStateOf(current) }
    val trimmed = name.trim()
    // The device refuses an over-long or empty name rather than repairing it, so
    // the confirm button is gated here: a rejection a second later could not say
    // which rule was broken.
    val error = SystemCommands.deviceNameError(trimmed)
    val byteLength = trimmed.toByteArray(Charsets.UTF_8).size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename device") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Advertised name") },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Text(
                    error?.message ?: "$byteLength / ${Protocol.MAX_DEVICE_NAME_LEN} bytes",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(trimmed) }, enabled = error == null) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RenameProfileDialog(
    slot: ProfileSlot,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(slot.name.orEmpty()) }
    val byteLength = name.toByteArray(Charsets.UTF_8).size
    val tooLong = byteLength > Protocol.MAX_PROFILE_NAME_LEN

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename slot ${slot.index}") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Profile name") },
                    isError = tooLong,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Text(
                    "$byteLength / ${Protocol.MAX_PROFILE_NAME_LEN} bytes" +
                        if (tooLong) " — will be truncated" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tooLong) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
