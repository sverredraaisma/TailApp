package com.tailapp.ui.screen

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailapp.audio.FftProcessor
import com.tailapp.ui.components.rememberMicPermissionRequester
import com.tailapp.viewmodel.AudioConfigViewModel
import kotlin.math.exp
import kotlin.math.ln

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
    val streamError by viewModel.streamError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Asks for RECORD_AUDIO *and* (API 33+) POST_NOTIFICATIONS: the stream runs
    // in a foreground service, and without the notification grant it holds the
    // microphone with nothing on screen to show for it. A permanent denial is
    // recoverable here rather than a permanently inert button — see
    // rememberMicPermissionRequester.
    val micPermission = rememberMicPermissionRequester(
        snackbarHostState = snackbarHostState,
        purpose = "stream the spectrum to the tail",
        onGranted = viewModel::toggleStream
    )

    LaunchedEffect(streamError) {
        streamError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStreamError()
        }
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
            // Stream toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("FFT Stream", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(if (isStreaming) "Streaming at 30fps" else "Not streaming")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (!isStreaming) micPermission.request() else viewModel.toggleStream()
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

                    // Bin count. No tick marks: 128 of them on a phone-width
                    // slider is a grey smear, not a scale.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bins: $numBins", modifier = Modifier.weight(1f))
                    }
                    Slider(
                        value = numBins.toFloat(),
                        onValueChange = { viewModel.setNumBins(it.toInt()) },
                        valueRange = FftProcessor.MIN_BINS.toFloat()..FftProcessor.MAX_BINS.toFloat(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Number of FFT bins"
                                stateDescription = "$numBins bins"
                            }
                    )

                    Spacer(Modifier.height(8.dp))

                    // Normalization speed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Normalization Speed: ${"%.2f".format(normSpeed)}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Slider(
                        value = normSpeed.coerceIn(
                            AudioConfigViewModel.MIN_NORM_SPEED,
                            AudioConfigViewModel.MAX_NORM_SPEED
                        ),
                        onValueChange = { viewModel.setNormalizationSpeed(it) },
                        valueRange = AudioConfigViewModel.MIN_NORM_SPEED..
                            AudioConfigViewModel.MAX_NORM_SPEED,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Normalization speed"
                                stateDescription = "%.2f".format(normSpeed)
                            }
                    )

                    Spacer(Modifier.height(8.dp))

                    // Frequency range, on a log scale because pitch is. The view
                    // model keeps start below end and both inside the audible
                    // band, so `ln` here can never see 0 — the thumb positions
                    // are always finite.
                    val logMin = ln(AudioConfigViewModel.MIN_FREQ_HZ)
                    val logMax = ln(AudioConfigViewModel.MAX_FREQ_HZ)

                    Text("Freq Start: ${"%.0f".format(freqStart)} Hz")
                    Slider(
                        value = ln(freqStart).coerceIn(logMin, logMax),
                        onValueChange = { viewModel.setFreqStart(exp(it)) },
                        valueRange = logMin..logMax,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Lowest analysed frequency"
                                stateDescription = "${"%.0f".format(freqStart)} hertz"
                            }
                    )

                    Text("Freq End: ${"%.0f".format(freqEnd)} Hz")
                    Slider(
                        value = ln(freqEnd).coerceIn(logMin, logMax),
                        onValueChange = { viewModel.setFreqEnd(exp(it)) },
                        valueRange = logMin..logMax,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Highest analysed frequency"
                                stateDescription = "${"%.0f".format(freqEnd)} hertz"
                            }
                    )
                    Text(
                        "The two ends push each other apart rather than crossing — an " +
                            "inverted range would analyse nothing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
