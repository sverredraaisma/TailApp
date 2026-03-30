package com.tailapp.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.viewmodel.AudioConfigViewModel
import kotlin.math.ln
import kotlin.math.exp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioConfigScreen(
    viewModel: AudioConfigViewModel,
    onBack: () -> Unit
) {
    val numBins by viewModel.numBins.collectAsStateWithLifecycle()
    val normSpeed by viewModel.normalizationSpeed.collectAsStateWithLifecycle()
    val freqStart by viewModel.freqStart.collectAsStateWithLifecycle()
    val freqEnd by viewModel.freqEnd.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.toggleStream()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Config") },
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
            // Stream toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("FFT Stream", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(if (isStreaming) "Streaming at 30fps" else "Not streaming")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (!isStreaming) {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            viewModel.toggleStream()
                        }
                    }) {
                        Text(if (isStreaming) "Stop Stream" else "Start Stream")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // FFT Settings
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("FFT Settings", style = MaterialTheme.typography.titleMedium)

                    Spacer(Modifier.height(16.dp))

                    // Bin count
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bins: $numBins", modifier = Modifier.weight(1f))
                    }
                    Slider(
                        value = numBins.toFloat(),
                        onValueChange = { viewModel.setNumBins(it.toInt()) },
                        valueRange = 1f..128f,
                        steps = 126,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    // Normalization speed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Normalization Speed: ${"%.2f".format(normSpeed)}", modifier = Modifier.weight(1f))
                    }
                    Slider(
                        value = normSpeed,
                        onValueChange = { viewModel.setNormalizationSpeed(it) },
                        valueRange = 0.01f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    // Frequency range (log scale)
                    val logMin = ln(20f)
                    val logMax = ln(20000f)

                    Text("Freq Start: ${"%.0f".format(freqStart)} Hz")
                    Slider(
                        value = ln(freqStart),
                        onValueChange = { viewModel.setFreqStart(exp(it)) },
                        valueRange = logMin..logMax,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Freq End: ${"%.0f".format(freqEnd)} Hz")
                    Slider(
                        value = ln(freqEnd),
                        onValueChange = { viewModel.setFreqEnd(exp(it)) },
                        valueRange = logMin..logMax,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
