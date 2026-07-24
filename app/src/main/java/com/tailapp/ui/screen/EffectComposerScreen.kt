package com.tailapp.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.Protocol
import com.tailapp.composer.Composition
import com.tailapp.composer.FirmwareExport
import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectLayer
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.GroupLayer
import com.tailapp.composer.LayerNode
import com.tailapp.composer.ReactiveEffects
import com.tailapp.model.BlendMode
import com.tailapp.model.ProfileSlot
import com.tailapp.ui.components.LedPreviewPlaceholder
import com.tailapp.ui.components.LedStrip
import com.tailapp.viewmodel.EffectComposerViewModel

/**
 * The effect composer: builds the layer/folder stack that the lighting session
 * renders and streams to the tail over direct drive.
 *
 * Layers are listed **bottom to top**, matching the firmware's layer indices —
 * the first row is drawn first and every row below it blends over the rows
 * above. Folders indent their contents, and a folder's blend mode applies to
 * everything inside it at once.
 *
 * Every control edits the running session immediately, which is why the live
 * strip sits at the top of the screen: the point is to build a stack by watching
 * it react, not by editing blind. Nothing here needs a device — with no tail
 * connected the same frames drive the preview alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectComposerScreen(
    viewModel: EffectComposerViewModel,
    onBack: () -> Unit
) {
    val composition by viewModel.composition.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()
    val installResult by viewModel.installResult.collectAsStateWithLifecycle()
    val expandedLayerId by viewModel.expandedLayerId.collectAsStateWithLifecycle()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsStateWithLifecycle()
    val compositions by viewModel.compositions.collectAsStateWithLifecycle()

    var addTargetParentId by remember { mutableStateOf<String?>(null) }
    var showEffectPicker by remember { mutableStateOf(false) }
    var showStackPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    // Reads whatever the user picked and hands the text to the view model,
    // which is where the decision to accept or reject it belongs.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        importError = if (text == null) {
            "Could not read that file."
        } else {
            viewModel.importJson(text)
        }
    }

    val ledsPerRing = deviceState.ledState?.ledsPerRing.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        composition.name + if (hasUnsavedChanges) " •" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = hasUnsavedChanges) {
                        Text("Save")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Switch stack") },
                                onClick = { showMenu = false; showStackPicker = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { showMenu = false; showRenameDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("New stack") },
                                onClick = { showMenu = false; viewModel.newComposition() }
                            )
                            DropdownMenuItem(
                                text = { Text("Share stack…") },
                                onClick = {
                                    showMenu = false
                                    shareComposition(
                                        context,
                                        viewModel.exportFileName(),
                                        viewModel.exportJson()
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import stack…") },
                                onClick = { showMenu = false; importLauncher.launch(arrayOf("*/*")) }
                            )
                            DropdownMenuItem(
                                text = { Text("Install on tail…") },
                                // Needs a device: this writes the device's own
                                // effect layers and a profile slot.
                                enabled = deviceState.connectionState == ConnectionState.CONNECTED,
                                onClick = { showMenu = false; showInstallDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Discard changes") },
                                enabled = hasUnsavedChanges,
                                onClick = { showMenu = false; viewModel.revert() }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (viewModel.isBuiltIn(composition.id)) "Reset to built-in"
                                        else "Delete stack"
                                    )
                                },
                                onClick = { showMenu = false; viewModel.deleteOrReset() }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            PreviewCard(
                frame = frame,
                ledsPerRing = ledsPerRing,
                brightness = composition.brightness,
                onBrightnessChange = viewModel::setBrightness
            )

            Spacer(Modifier.height(16.dp))
            Text("Layers", style = MaterialTheme.typography.titleMedium)
            Text(
                "Drawn bottom to top — each layer blends over the ones above it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (composition.layers.isEmpty()) {
                Text(
                    "No layers yet. Add one to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            flattenTree(composition.layers).forEach { row ->
                LayerCard(
                    row = row,
                    expanded = expandedLayerId == row.node.id,
                    viewModel = viewModel,
                    onAddInside = { addTargetParentId = row.node.id; showEffectPicker = true }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { addTargetParentId = null; showEffectPicker = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add layer")
                }
                OutlinedButton(
                    onClick = { viewModel.addFolder(null) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Add folder")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showEffectPicker) {
        EffectPickerDialog(
            effectsByCategory = viewModel.effectsByCategory,
            onPick = { spec ->
                viewModel.addEffect(spec.id, addTargetParentId)
                showEffectPicker = false
            },
            onDismiss = { showEffectPicker = false }
        )
    }

    if (showStackPicker) {
        StackPickerDialog(
            compositions = compositions,
            activeId = composition.id,
            onPick = { viewModel.selectComposition(it); showStackPicker = false },
            onDismiss = { showStackPicker = false }
        )
    }

    if (showRenameDialog) {
        RenameDialog(
            initial = composition.name,
            onConfirm = { viewModel.setCompositionName(it); showRenameDialog = false },
            onDismiss = { showRenameDialog = false }
        )
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } }
        )
    }

    if (showInstallDialog) {
        InstallOnTailDialog(
            summary = remember(composition) { FirmwareExport.describe(viewModel.previewInstall()) },
            profiles = deviceState.profiles,
            onConfirm = { slot -> viewModel.installOnTail(slot); showInstallDialog = false },
            onDismiss = { showInstallDialog = false }
        )
    }

    // The install summary is long and worth reading, so it gets a dialog rather
    // than a snackbar that slides away mid-sentence.
    installResult?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearInstallResult,
            title = { Text("Install on tail") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearInstallResult) { Text("OK") }
            }
        )
    }
}

/**
 * Confirms writing the current stack to the device's own layers and a profile
 * slot.
 *
 * Leads with what will be lost. The device's effect set is much smaller than the
 * composer's, so an install is an approximation — and the user is about to
 * overwrite a profile slot on the strength of it.
 */
@Composable
private fun InstallOnTailDialog(
    summary: String,
    profiles: List<ProfileSlot>,
    onConfirm: (Byte) -> Unit,
    onDismiss: () -> Unit
) {
    var slot by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install on tail") },
        text = {
            Column {
                Text(
                    "The tail runs this by itself, with the phone disconnected. " +
                        "It has a smaller effect set, so this is an approximation:",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Text("Save to profile slot:", style = MaterialTheme.typography.labelLarge)
                for (i in 0 until Protocol.MAX_PROFILE_SLOTS) {
                    val existing = profiles.getOrNull(i)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = slot == i, onClick = { slot = i })
                        Text(
                            if (existing?.occupied == true) {
                                "${existing.displayName} (will be overwritten)"
                            } else {
                                "Slot $i (empty)"
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(slot.toByte()) }) { Text("Install") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// --- preview ---

@Composable
private fun PreviewCard(
    frame: com.tailapp.led.PixelBuffer?,
    ledsPerRing: List<Int>,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (frame != null && ledsPerRing.isNotEmpty()) {
                LedStrip(pixels = frame, ledsPerRing = ledsPerRing, modifier = Modifier.fillMaxWidth())
            } else {
                LedPreviewPlaceholder(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "Start the lighting session to see the stack render.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            LabelledSlider(
                label = "Master brightness",
                value = brightness,
                min = 0f,
                max = 1f,
                onChange = onBrightnessChange
            )
        }
    }
}

// --- the tree ---

/** One rendered row: a node plus where it sits, so the row can draw its controls. */
private data class TreeRow(
    val node: LayerNode,
    val depth: Int,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

/**
 * Flattens the tree into display rows, skipping the contents of collapsed
 * folders. `canMoveUp`/`canMoveDown` are relative to a node's own siblings,
 * because moving is confined to a folder — see `Composition.moveNode`.
 */
private fun flattenTree(
    nodes: List<LayerNode>,
    depth: Int = 0,
    out: MutableList<TreeRow> = mutableListOf()
): List<TreeRow> {
    nodes.forEachIndexed { index, node ->
        out.add(TreeRow(node, depth, index > 0, index < nodes.size - 1))
        if (node is GroupLayer && !node.collapsed) flattenTree(node.children, depth + 1, out)
    }
    return out
}

@Composable
private fun LayerCard(
    row: TreeRow,
    expanded: Boolean,
    viewModel: EffectComposerViewModel,
    onAddInside: () -> Unit
) {
    val node = row.node
    val isFolder = node is GroupLayer
    var showMenu by remember(node.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 16).dp),
        colors = CardDefaults.cardColors(
            containerColor =
                if (isFolder) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = node.enabled,
                    onCheckedChange = { viewModel.setEnabled(node.id, it) }
                )
                Spacer(Modifier.width(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.toggleExpanded(node.id) }
                ) {
                    Text(
                        node.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (isFolder) "Folder · ${node.blendMode.displayName}"
                        else effectLabel(node) + " · " + node.blendMode.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { viewModel.moveLayer(node.id, -1) },
                    enabled = row.canMoveUp
                ) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(
                    onClick = { viewModel.moveLayer(node.id, 1) },
                    enabled = row.canMoveDown
                ) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Layer options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (isFolder) {
                            DropdownMenuItem(
                                text = { Text("Add layer inside") },
                                onClick = { showMenu = false; onAddInside() }
                            )
                            DropdownMenuItem(
                                text = { Text(if ((node as GroupLayer).collapsed) "Expand" else "Collapse") },
                                onClick = { showMenu = false; viewModel.toggleFolderCollapsed(node.id) }
                            )
                            DropdownMenuItem(
                                text = { Text("Ungroup") },
                                onClick = { showMenu = false; viewModel.ungroupFolder(node.id) }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Wrap in folder") },
                                onClick = { showMenu = false; viewModel.groupLayer(node.id) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = { showMenu = false; viewModel.duplicateLayer(node.id) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { showMenu = false; viewModel.deleteLayer(node.id) }
                        )
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                BlendRow(
                    blendMode = node.blendMode,
                    onChange = { viewModel.setBlend(node.id, it) }
                )
                Spacer(Modifier.height(8.dp))
                LabelledSlider(
                    label = "Opacity",
                    value = node.opacity,
                    min = 0f,
                    max = 1f,
                    onChange = { viewModel.setOpacity(node.id, it) }
                )

                if (node is EffectLayer) {
                    Spacer(Modifier.height(8.dp))
                    EffectParams(layer = node, viewModel = viewModel)
                }

                Spacer(Modifier.height(8.dp))
                TransformRow(node = node, viewModel = viewModel)
            }
        }
    }
}

private fun effectLabel(node: LayerNode): String {
    val layer = node as? EffectLayer ?: return "Layer"
    return ReactiveEffects.spec(layer.effectId)?.displayName ?: "Unknown (${layer.effectId})"
}

@Composable
private fun EffectParams(layer: EffectLayer, viewModel: EffectComposerViewModel) {
    val spec = ReactiveEffects.spec(layer.effectId)
    if (spec == null) {
        Text(
            "This layer uses an effect this version does not have, so it renders nothing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        return
    }

    spec.schema.forEach { param ->
        val value = layer.params[param.key] ?: param.default
        val onChange: (Float) -> Unit = { viewModel.setParam(layer.id, param.key, it) }

        when (param) {
            is EffectParam.Scalar -> LabelledSlider(
                label = param.label,
                value = value,
                min = param.min,
                max = param.max,
                step = param.step,
                unit = param.unit,
                onChange = onChange
            )

            is EffectParam.Color -> ColorParamEditor(
                label = param.label,
                packed = value.toInt() and 0xFFFFFF,
                onChange = { onChange(it.toFloat()) }
            )

            is EffectParam.Choice -> ChoiceParamEditor(
                label = param.label,
                options = param.options,
                selectedIndex = value.toInt().coerceIn(0, param.options.lastIndex),
                onChange = { onChange(it.toFloat()) }
            )

            is EffectParam.Toggle -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(param.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = value != 0f, onCheckedChange = { onChange(if (it) 1f else 0f) })
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun TransformRow(node: LayerNode, viewModel: EffectComposerViewModel) {
    Column {
        Text("Transform", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TransformToggle("Flip X", node.flipX) {
                viewModel.setTransform(node.id, it, node.flipY, node.mirrorX, node.mirrorY)
            }
            TransformToggle("Flip Y", node.flipY) {
                viewModel.setTransform(node.id, node.flipX, it, node.mirrorX, node.mirrorY)
            }
            TransformToggle("Mirror X", node.mirrorX) {
                viewModel.setTransform(node.id, node.flipX, node.flipY, it, node.mirrorY)
            }
            TransformToggle("Mirror Y", node.mirrorY) {
                viewModel.setTransform(node.id, node.flipX, node.flipY, node.mirrorX, it)
            }
        }
    }
}

@Composable
private fun TransformToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    OutlinedButton(
        onClick = { onChange(!checked) },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- generic parameter controls ---

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float = 0f,
    unit: String = "",
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                formatValue(value, step) + unit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = { onChange(if (step > 0f) snap(it, min, step) else it) },
            valueRange = min..max,
            // `steps` counts the divisions *between* endpoints, hence the -1.
            steps = if (step > 0f) (((max - min) / step).toInt() - 1).coerceAtLeast(0) else 0
        )
    }
}

private fun snap(value: Float, min: Float, step: Float): Float =
    min + Math.round((value - min) / step) * step

private fun formatValue(value: Float, step: Float): String =
    if (step >= 1f) value.toInt().toString() else "%.2f".format(value)

@Composable
private fun ChoiceParamEditor(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(options.getOrElse(selectedIndex) { options.first() })
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onChange(index); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun BlendRow(blendMode: BlendMode, onChange: (BlendMode) -> Unit) {
    ChoiceParamEditor(
        label = "Blend",
        options = BlendMode.entries.map { it.displayName },
        selectedIndex = BlendMode.entries.indexOf(blendMode),
        onChange = { onChange(BlendMode.entries[it]) }
    )
}

@Composable
private fun ColorParamEditor(label: String, packed: Int, onChange: (Int) -> Unit) {
    var custom by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(OPAQUE or packed))
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { custom = !custom }) { Text(if (custom) "Presets" else "Custom") }
        }

        if (custom) {
            ChannelSlider("R", (packed shr 16) and 0xFF) {
                onChange((packed and 0x00FFFF) or (it shl 16))
            }
            ChannelSlider("G", (packed shr 8) and 0xFF) {
                onChange((packed and 0xFF00FF) or (it shl 8))
            }
            ChannelSlider("B", packed and 0xFF) {
                onChange((packed and 0xFFFF00) or it)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SWATCHES.forEach { swatch ->
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(OPAQUE or swatch))
                            .clickable { onChange(swatch) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(20.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f)
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(32.dp)
        )
    }
}

// --- dialogs ---

@Composable
private fun EffectPickerDialog(
    effectsByCategory: Map<EffectCategory, List<EffectSpec>>,
    onPick: (EffectSpec) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add layer") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                EffectCategory.entries.forEach { category ->
                    val specs = effectsByCategory[category].orEmpty()
                    if (specs.isEmpty()) return@forEach

                    Text(
                        category.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    specs.forEach { spec ->
                        Text(
                            spec.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(spec) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun StackPickerDialog(
    compositions: List<Composition>,
    activeId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Switch stack") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                compositions.forEach { composition ->
                    Text(
                        composition.name + if (composition.id == activeId) "  (open)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(composition.id) }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename stack") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.ifBlank { initial }) },
                enabled = text.isNotBlank()
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Packed colours carry no alpha byte; OR in an opaque one rather than writing a
 * bare `0xFF000000` Int literal, which Kotlin rejects as out of range.
 */
private const val OPAQUE = 0xFF shl 24

private val SWATCHES = listOf(
    0xFFFFFF, 0xFF2000, 0xFF6000, 0xFFD040,
    0x40FF00, 0x00FFA0, 0x00D0FF, 0x1040FF,
    0x8020FF, 0xFF00C8, 0x808080, 0x000000
)

/**
 * Hands the stack to the system share sheet as a JSON document.
 *
 * Written to the cache directory and shared by content URI rather than as an
 * extra string: a stack of any size exceeds what an Intent extra can safely
 * carry, and a file is what the receiving app almost always wants anyway.
 */
private fun shareComposition(context: android.content.Context, fileName: String, json: String) {
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(dir, fileName)
    file.writeText(json)

    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share effect stack"))
}
