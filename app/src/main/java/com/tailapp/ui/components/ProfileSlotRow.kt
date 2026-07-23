package com.tailapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tailapp.model.ProfileSlot

/**
 * One profile slot: name (or "Slot N" when unnamed), occupancy, and the actions
 * the firmware supports. Load and Delete are disabled for an empty slot — the
 * device answers those with `BAD_STATE` / a no-op.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSlotRow(
    slot: ProfileSlot,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = slot.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (slot.occupied) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = if (slot.occupied) "Slot ${slot.index} · saved" else "Slot ${slot.index} · empty",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSave) { Text("Save") }
            TextButton(onClick = onLoad, enabled = slot.occupied) { Text("Load") }
            TextButton(onClick = onRename) { Text("Rename") }
            TextButton(onClick = onDelete, enabled = slot.occupied) { Text("Delete") }
        }
    }
}
