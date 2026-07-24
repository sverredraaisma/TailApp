package com.tailapp.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.model.BlendMode
import com.tailapp.model.LedEffect
import com.tailapp.ui.components.DebouncedSlider
import com.tailapp.ui.components.EffectParameterSlider
import com.tailapp.ui.components.LedPreview
import com.tailapp.viewmodel.LedConfigViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedConfigScreen(
    viewModel: LedConfigViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.deviceState.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val uploadError by viewModel.uploadError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ledState = state.ledState
    val capabilities = state.capabilities
    val snackbarHostState = remember { SnackbarHostState() }

    // Only offer effects/blends the connected firmware advertises.
    val availableEffects = capabilities.effects.ifEmpty { LedEffect.entries }
    val availableBlends = capabilities.blendModes.ifEmpty { BlendMode.entries }
    val canAddLayer = ledState?.firstFreeLayerIndex(capabilities.maxLayers) != null

    LaunchedEffect(uploadError) {
        uploadError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUploadError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LED Config") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (canAddLayer) {
                FloatingActionButton(onClick = {
                    viewModel.addLayer(LedEffect.RAINBOW.id, BlendMode.OVERWRITE.id)
                }) {
                    Icon(Icons.Default.Add, "Add Layer")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Output stage (protocol v5). Absent on older firmware, and the
            // controls are hidden rather than shown against invented values.
            ledState?.output?.let { output ->
                OutputStageCard(
                    output = output,
                    totalLeds = ledState.totalLeds,
                    onChange = viewModel::setOutputConfig
                )
                Spacer(Modifier.height(16.dp))
            }

            // Matrix Config
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LED Matrix", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (ledState != null) {
                        Text("${ledState.numRings} rings, ${ledState.totalLeds} total LEDs")
                        Text(
                            "LEDs per ring: ${ledState.ledsPerRing.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("No data")
                    }

                    Spacer(Modifier.height(8.dp))

                    val defaultLeds = ledState?.ledsPerRing
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(",")
                        ?: "8,10,12,10,8"
                    var ledsInput by remember(defaultLeds) { mutableStateOf(defaultLeds) }

                    val parsedRings = ledsInput.split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .filter { it in 1..255 }
                    val ringsValid = parsedRings.isNotEmpty() &&
                        parsedRings.size <= capabilities.maxLedRings

                    TextField(
                        value = ledsInput,
                        onValueChange = { ledsInput = it },
                        label = { Text("LEDs per ring (comma-separated)") },
                        isError = !ringsValid,
                        supportingText = {
                            Text(
                                if (ringsValid) {
                                    "${parsedRings.size} rings, ${parsedRings.sum()} LEDs"
                                } else {
                                    "Enter 1–${capabilities.maxLedRings} counts between 1 and 255"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = ringsValid,
                        onClick = { viewModel.setLedMatrix(parsedRings.map { it.toByte() }) }
                    ) { Text("Apply Matrix") }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Upload progress
            uploadProgress?.let { progress ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Uploading Image...")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${"%.0f".format(progress * 100)}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Live preview - computed locally from the same effect stack the
            // firmware runs, since the device never streams its frame buffer
            // back. Sits above the layer list so an edit's effect is visible
            // right next to the controls that caused it.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Live Preview", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LedPreview(
                        ledState = ledState,
                        previewClock = viewModel.previewClock,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    // Direct mode bypasses the effect stack entirely, so while
                    // BeatLight is streaming this preview describes a stack the
                    // device is not currently rendering. Saying so beats showing
                    // a confident picture of the wrong thing.
                    if (state.directModeActive) {
                        Text(
                            "BeatLight is streaming frames directly — the device is not " +
                                "showing this effect stack right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else {
                        Text(
                            "Local approximation of what the tail should be showing right now — " +
                                "not a live feed from the device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Layers. Slots cleared with LCMD_REMOVE_LAYER keep their index on the
            // device, so render by real index and skip the empty ones.
            if (ledState != null) {
                val occupied = ledState.occupiedLayerIndices
                if (occupied.isEmpty()) {
                    Text(
                        "No layers configured. Use + to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                occupied.forEach { layerIdx ->
                    val layer = ledState.layers[layerIdx]
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Layer $layerIdx", style = MaterialTheme.typography.titleSmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = layer.enabled,
                                        onCheckedChange = {
                                            viewModel.setLayerEnabled(layerIdx.toByte(), it)
                                        }
                                    )
                                    IconButton(onClick = { viewModel.removeLayer(layerIdx.toByte()) }) {
                                        Icon(Icons.Default.Delete, "Remove")
                                    }
                                }
                            }

                            // Effect dropdown
                            var effectExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = effectExpanded,
                                onExpandedChange = { effectExpanded = it }
                            ) {
                                TextField(
                                    value = layer.effect?.displayName ?: "Unknown",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Effect") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(effectExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = effectExpanded,
                                    onDismissRequest = { effectExpanded = false }
                                ) {
                                    availableEffects.forEach { effect ->
                                        DropdownMenuItem(
                                            text = { Text(effect.displayName) },
                                            onClick = {
                                                viewModel.setLayerEffect(layerIdx.toByte(), effect.id, layer.blendMode)
                                                effectExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Blend mode dropdown
                            var blendExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = blendExpanded,
                                onExpandedChange = { blendExpanded = it }
                            ) {
                                TextField(
                                    value = layer.blend?.displayName ?: "Unknown",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Blend Mode") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(blendExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = blendExpanded,
                                    onDismissRequest = { blendExpanded = false }
                                ) {
                                    availableBlends.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.displayName) },
                                            onClick = {
                                                viewModel.setLayerEffect(layerIdx.toByte(), layer.effectId, mode.id)
                                                blendExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Transform toggles
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Flip X", style = MaterialTheme.typography.bodySmall)
                                    Switch(
                                        checked = layer.flipX,
                                        onCheckedChange = {
                                            viewModel.setLayerTransform(
                                                layerIdx.toByte(), it, layer.flipY, layer.mirrorX, layer.mirrorY
                                            )
                                        }
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Flip Y", style = MaterialTheme.typography.bodySmall)
                                    Switch(
                                        checked = layer.flipY,
                                        onCheckedChange = {
                                            viewModel.setLayerTransform(
                                                layerIdx.toByte(), layer.flipX, it, layer.mirrorX, layer.mirrorY
                                            )
                                        }
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Mirror X", style = MaterialTheme.typography.bodySmall)
                                    Switch(
                                        checked = layer.mirrorX,
                                        onCheckedChange = {
                                            viewModel.setLayerTransform(
                                                layerIdx.toByte(), layer.flipX, layer.flipY, it, layer.mirrorY
                                            )
                                        }
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Mirror Y", style = MaterialTheme.typography.bodySmall)
                                    Switch(
                                        checked = layer.mirrorY,
                                        onCheckedChange = {
                                            viewModel.setLayerTransform(
                                                layerIdx.toByte(), layer.flipX, layer.flipY, layer.mirrorX, it
                                            )
                                        }
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))

                            // Effect Parameters
                            val effect = layer.effect
                            if (effect != null) {
                                effect.params.forEach { param ->
                                    EffectParameterSlider(
                                        param = param,
                                        value = layer.params.getOrElse(param.id) { param.default },
                                        onValueChange = {
                                            viewModel.setEffectParam(layerIdx.toByte(), param.id.toByte(), it)
                                        }
                                    )
                                }
                            }

                            // Image upload button
                            if (layer.effectId == LedEffect.IMAGE.id) {
                                Spacer(Modifier.height(8.dp))
                                val imagePicker = rememberLauncherForActivityResult(
                                    ActivityResultContracts.GetContent()
                                ) { uri: Uri? ->
                                    uri?.let { viewModel.uploadImage(context, it, layerIdx.toByte()) }
                                }
                                Button(
                                    enabled = uploadProgress == null,
                                    onClick = { imagePicker.launch("image/*") }
                                ) {
                                    Text("Upload Image (${capabilities.imageMaxDim}×${capabilities.imageMaxDim})")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Master brightness, gamma, and the current budget the device enforces.
 *
 * The budget is the control that matters: a WS2812B draws roughly 60 mA at full
 * white, so a full-white frame on a long strip asks for amps no wearable
 * regulator will supply. Without a limit the failure mode is the rail browning
 * out and resetting the device mid-frame; with one, the picture dims to fit.
 * The card shows the worst case for the strip actually attached so the number
 * is a decision rather than a guess, and says when the limiter is engaging —
 * otherwise "my look is dimmer than the preview" has no visible cause.
 */
@Composable
private fun OutputStageCard(
    output: com.tailapp.model.LedOutputState,
    totalLeds: Int,
    onChange: (Int, Boolean, Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Output", style = MaterialTheme.typography.titleMedium)

            if (output.isPowerLimited) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Limiting to fit the current budget — the tail is showing about " +
                        "${output.lastPowerScale * 100 / 255}% of what this look asks for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            DebouncedSlider(
                label = "Master brightness",
                value = output.brightness.toFloat(),
                onValueChange = {
                    onChange(it.toInt(), output.gammaEnabled, output.currentLimitMa)
                },
                valueRange = 1f..255f,
                valueFormat = "%.0f"
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gamma correction", modifier = Modifier.weight(1f))
                Switch(
                    checked = output.gammaEnabled,
                    onCheckedChange = {
                        onChange(output.brightness, it, output.currentLimitMa)
                    }
                )
            }
            Text(
                "Off looks brighter but crushes every dim shade into the first few steps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            DebouncedSlider(
                label = if (output.currentLimitMa == 0) "Current budget (off)" else "Current budget",
                value = output.currentLimitMa.toFloat(),
                onValueChange = {
                    onChange(output.brightness, output.gammaEnabled, it.toInt())
                },
                valueRange = 0f..5000f,
                unit = " mA",
                valueFormat = "%.0f"
            )
            // ~60 mA per LED at full white, plus ~1 mA of controller draw.
            Text(
                "0 disables the limit. This strip draws about ${totalLeds * 61} mA " +
                    "at full white.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
