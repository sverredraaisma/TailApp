package com.tailapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tailapp.ble.DiscoveredCharacteristic
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CharacteristicCard(
    serviceUuid: UUID,
    characteristic: DiscoveredCharacteristic,
    lastValue: ByteArray?,
    onRead: () -> Unit,
    onWrite: (ByteArray) -> Unit,
    onNotify: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = characteristic.uuid.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (lastValue != null) {
                Text(
                    text = "Value: ${lastValue.joinToString(" ") { "%02X".format(it) }}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (characteristic.isReadable) {
                    OutlinedButton(onClick = onRead) {
                        Text("Read")
                    }
                }
                if (characteristic.isWritable) {
                    Button(onClick = { onWrite(byteArrayOf(0x01)) }) {
                        Text("Write")
                    }
                }
                if (characteristic.isNotifiable) {
                    OutlinedButton(onClick = onNotify) {
                        Text("Notify")
                    }
                }
            }
        }
    }
}
