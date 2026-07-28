package com.tailapp.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Asks for everything a microphone-holding foreground session needs, and makes a
 * permanent denial recoverable instead of a dead end.
 *
 * Two permissions, not one. `RECORD_AUDIO` is the obvious half; on API 33+
 * `POST_NOTIFICATIONS` is the other, because both sessions run as a foreground
 * service and without the grant its notification is silently suppressed — which
 * leaves a wake-locked microphone the user can neither see nor stop. It is
 * requested *alongside* the mic rather than after, so there is one system prompt
 * and no window in which the service is running invisibly.
 *
 * The denial path is the point of this being shared code:
 *
 * - first denial → a rationale dialog saying what the mic is for, with a retry;
 * - second (permanent) denial → a snackbar whose action opens this app's
 *   settings page, because the system will never show the prompt again;
 * - returning from those settings → the grant is re-read on `ON_RESUME`, so the
 *   Start button comes back to life without the user having to guess.
 *
 * Notifications being refused is deliberately *not* blocking: the session is
 * still worth starting, the user is just told the running indicator will be
 * missing.
 *
 * @return a [MicPermissionRequester] whose [MicPermissionRequester.request]
 *   grants-or-asks and calls [onGranted] once `RECORD_AUDIO` is held.
 */
@Composable
fun rememberMicPermissionRequester(
    snackbarHostState: SnackbarHostState,
    purpose: String,
    onGranted: () -> Unit
): MicPermissionRequester {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val currentOnGranted by rememberUpdatedState(onGranted)

    var showRationale by remember { mutableStateOf(false) }
    var deniedPermanently by remember { mutableStateOf(false) }
    var notificationsRefused by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            context.hasPermission(Manifest.permission.RECORD_AUDIO)
        if (micGranted) {
            deniedPermanently = false
            // Refused notifications is survivable; refused mic is not.
            notificationsRefused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                results[Manifest.permission.POST_NOTIFICATIONS] == false
            currentOnGranted()
        } else {
            // The system stops offering the prompt once it stops offering a
            // rationale — that is the only signal Android gives for "permanent".
            deniedPermanently = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.RECORD_AUDIO
                )
            showRationale = !deniedPermanently
        }
    }

    // Coming back from the settings page must re-enable the button; nothing else
    // tells this screen the grant changed underneath it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                context.hasPermission(Manifest.permission.RECORD_AUDIO)
            ) {
                deniedPermanently = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(deniedPermanently) {
        if (!deniedPermanently) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Microphone access is off for TailApp, and Android will not ask again. " +
                "Turn it on in Settings to $purpose.",
            actionLabel = "Settings",
            withDismissAction = true
        )
        if (result == SnackbarResult.ActionPerformed) {
            context.openAppSettings()
        }
    }

    LaunchedEffect(notificationsRefused) {
        if (!notificationsRefused) return@LaunchedEffect
        snackbarHostState.showSnackbar(
            "Notifications are off, so the running-session notification will not appear. " +
                "The session still runs — stop it from this screen."
        )
        notificationsRefused = false
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Microphone access") },
            text = {
                Text(
                    "TailApp listens to the music around you to $purpose. Audio is analysed " +
                        "on this phone and never recorded or sent anywhere.\n\n" +
                        "Android also needs permission to show the notification for the " +
                        "running session, so you can always see and stop it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    launcher.launch(micPermissions())
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text("Not now") }
            }
        )
    }

    return remember(launcher, activity) {
        MicPermissionRequester(
            requestImpl = {
                if (context.hasPermission(Manifest.permission.RECORD_AUDIO) &&
                    context.hasPermission(notificationPermissionOrNull())
                ) {
                    currentOnGranted()
                } else if (
                    activity != null &&
                    !context.hasPermission(Manifest.permission.RECORD_AUDIO) &&
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.RECORD_AUDIO
                    )
                ) {
                    // Already refused once: say why before asking again, or the
                    // second refusal is permanent and unexplained.
                    showRationale = true
                } else {
                    launcher.launch(micPermissions())
                }
            }
        )
    }
}

/** Handle returned by [rememberMicPermissionRequester]. */
class MicPermissionRequester internal constructor(
    private val requestImpl: () -> Unit
) {
    /** Runs the granted action, or asks — including the rationale/settings path. */
    fun request() = requestImpl()
}

/**
 * `RECORD_AUDIO`, plus `POST_NOTIFICATIONS` on API 33+ where a foreground
 * service's notification is otherwise dropped without a word.
 */
private fun micPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

private fun notificationPermissionOrNull(): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }

private fun Context.hasPermission(permission: String?): Boolean =
    permission == null ||
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
