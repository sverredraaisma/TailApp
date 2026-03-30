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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.tailapp.ui.components.EffectParameterSlider
import com.tailapp.viewmodel.LedConfigViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedConfigScreen(
    viewModel: LedConfigViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.deviceState.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ledState = state.ledState

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
        floatingActionButton = {
            if ((ledState?.layers?.size ?: 0) < 8) {
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

                    val defaultLeds = ledState?.ledsPerRing?.joinToString(",") ?: "8,10,12,10,8"
                    var ledsInput by remember(defaultLeds) { mutableStateOf(defaultLeds) }

                    TextField(
                        value = ledsInput,
                        onValueChange = { ledsInput = it },
                        label = { Text("LEDs per ring (comma-separated)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val leds = ledsInput.split(",").mapNotNull { it.trim().toIntOrNull() }
                        if (leds.isNotEmpty()) {
                            viewModel.setLedMatrix(leds.map { it.toByte() })
                        }
                    }) { Text("Apply Matrix") }
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

            // Layers
            if (ledState != null) {
                ledState.layers.forEachIndexed { layerIdx, layer ->
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
                                    LedEffect.entries.forEach { effect ->
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
                                    BlendMode.entries.forEach { mode ->
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
                                Button(onClick = { imagePicker.launch("image/*") }) {
                                    Text("Upload Image")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
