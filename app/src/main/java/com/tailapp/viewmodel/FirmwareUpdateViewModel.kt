package com.tailapp.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.FirmwareImage
import com.tailapp.ble.protocol.FirmwareImageInfo
import com.tailapp.ble.protocol.Protocol
import com.tailapp.model.DeviceState
import com.tailapp.model.FirmwareVersion
import com.tailapp.model.OtaInfo
import com.tailapp.repository.DeviceRepository
import com.tailapp.repository.FirmwareUpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A firmware file the user picked, checked here against the very header the
 * device decides from ([FirmwareImage]) so a doomed transfer is refused before
 * a byte is sent rather than 288 bytes into it.
 *
 * [blocker] is a client-side certainty, not the device's verdict: it names the
 * reason the tail is bound to refuse this file. The device re-checks its own
 * descriptor once the header lands, so a null blocker is "nothing here can fault
 * it", never "the tail will accept it".
 */
data class FirmwareSelection(
    val fileName: String,
    val sizeBytes: Int,
    /** Null when the file is not an ESP application image at all. */
    val info: FirmwareImageInfo?,
    val blocker: String?
) {
    val installable: Boolean get() = blocker == null

    /**
     * The version BEGIN carries as the app's *claim*. Unknown parses as 0.0.0,
     * which the device treats as "not the running version" and re-derives from
     * the image's own descriptor anyway — so a missing version costs 288 bytes,
     * never a wrong install.
     */
    val claimedVersion: FirmwareVersion get() = info?.version ?: FirmwareVersion(0, 0, 0)
}

/** Where a transfer is: not started, in flight, or finished with a verdict. */
sealed interface FirmwareTransfer {

    data object Idle : FirmwareTransfer

    /**
     * [accepted] is the device's echoed offset — bytes safely in flash — not the
     * app's write cursor, which runs ahead of what the tail has actually taken.
     */
    data class Active(val accepted: Int, val total: Int, val elapsedMs: Long) : FirmwareTransfer {
        val fraction: Float get() = if (total > 0) (accepted.toFloat() / total).coerceIn(0f, 1f) else 0f

        /**
         * Time left from the rate seen so far, or null until there is enough of a
         * transfer to extrapolate from. Deliberately derived from the *device's*
         * accepted bytes, so a stall shows up as an estimate that stops falling
         * rather than a bar that keeps moving on bytes the tail never took.
         */
        val remainingMs: Long?
            get() = FirmwareUpdateViewModel.estimateRemainingMs(accepted, total, elapsedMs)
    }

    data class Finished(val result: FirmwareUpdateResult) : FirmwareTransfer {
        val message: String get() = result.message
        val succeeded: Boolean get() = result.succeeded
        val resumable: Boolean get() = result.resumable
    }
}

/**
 * Drives arm → stream → finalize for an over-the-air firmware update (A5-1).
 *
 * Two things the repository owns are surfaced here without being re-decided:
 * progress is the device's echoed offset, and every rejection carries the
 * repository's per-code message ([FirmwareUpdateResult.message]) rather than a
 * second copy of that reasoning. What this adds is the pre-flight check on a
 * picked file, the elapsed-time estimate, and the rollback contract the UI has
 * to make visible — the tail confirms a new image only after it has held a
 * connection for [Protocol.OTA_CONFIRM_DWELL_MS], so a user who walks away right
 * after the reboot silently gets the old firmware back.
 */
class FirmwareUpdateViewModel(
    private val deviceRepository: DeviceRepository,
    private val now: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    /** The FF06 version/rollback block, or null on firmware that cannot be updated over the air. */
    val otaInfo: StateFlow<OtaInfo?> =
        deviceState
            .map { it.systemInfo?.ota }
            .stateIn(viewModelScope, SharingStarted.Eagerly, deviceState.value.systemInfo?.ota)

    private val _selection = MutableStateFlow<FirmwareSelection?>(null)
    val selection: StateFlow<FirmwareSelection?> = _selection.asStateFlow()

    private val _transfer = MutableStateFlow<FirmwareTransfer>(FirmwareTransfer.Idle)
    val transfer: StateFlow<FirmwareTransfer> = _transfer.asStateFlow()

    // The image is held whole: the app streams a file it possesses in full, and
    // the offset echo can ask it to resume from any point already sent.
    private var imageBytes: ByteArray? = null
    private var installJob: Job? = null
    private var startedAtMs: Long = 0L

    /**
     * Reads a picked document and evaluates it.
     *
     * The read is on [Dispatchers.IO], not in the picker's result callback: that
     * callback is the main thread, the image is around a megabyte, and a
     * document from a network-backed provider downloads the whole file inside
     * `openInputStream`. Doing it inline is an ANR waiting for a slow link.
     */
    fun onImageUriPicked(context: Context, uri: Uri) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                bytes?.let { displayName(context, uri) to it }
            }
            if (picked == null) {
                _pickError.value = "Could not read that file."
            } else {
                onImagePicked(picked.first, picked.second)
            }
        }
    }

    private val _pickError = MutableStateFlow<String?>(null)

    /** Why the last pick could not even be read. Distinct from a file that read but is wrong. */
    val pickError: StateFlow<String?> = _pickError.asStateFlow()

    fun clearPickError() {
        _pickError.value = null
    }

    /**
     * Reads a picked firmware file and checks it against the device's own header
     * rules, clearing any previous verdict so the screen shows the new file.
     */
    fun onImagePicked(fileName: String, bytes: ByteArray) {
        _pickError.value = null
        imageBytes = bytes
        _selection.value = evaluate(fileName, bytes, otaInfo.value?.running)
        if (_transfer.value is FirmwareTransfer.Finished) _transfer.value = FirmwareTransfer.Idle
    }

    fun clearSelection() {
        imageBytes = null
        _selection.value = null
        if (_transfer.value is FirmwareTransfer.Finished) _transfer.value = FirmwareTransfer.Idle
    }

    /** BEGIN, stream on FF0E, FINALIZE. Ignored unless a good file is picked and the tail is connected. */
    fun install() {
        val bytes = imageBytes ?: return
        val selection = _selection.value ?: return
        if (!selection.installable) return
        run(selection) { onProgress ->
            deviceRepository.uploadFirmware(bytes, selection.claimedVersion, onProgress)
        }
    }

    /**
     * Carries a transfer the tail is still holding on from the offset it echoes.
     *
     * The device never ages out an interrupted transfer, so this is the recovery
     * for a short or stalled one — and it resumes from the *device's* echoed
     * offset, never from wherever this app thought it had got to.
     */
    fun resume() {
        val bytes = imageBytes ?: return
        val selection = _selection.value ?: return
        run(selection) { onProgress ->
            deviceRepository.resumeFirmwareUpdate(bytes, onProgress)
            // Nothing was armed after all: fall back to a fresh install rather
            // than leaving the user with a resume that quietly did nothing.
                ?: deviceRepository.uploadFirmware(bytes, selection.claimedVersion, onProgress)
        }
    }

    private fun run(
        selection: FirmwareSelection,
        transfer: suspend ((Int, Int) -> Unit) -> FirmwareUpdateResult
    ) {
        if (installJob?.isActive == true) return
        if (deviceState.value.connectionState != ConnectionState.CONNECTED) return
        val total = selection.sizeBytes
        startedAtMs = now()
        _transfer.value = FirmwareTransfer.Active(0, total, 0L)
        installJob = viewModelScope.launch {
            val result = transfer { accepted, t ->
                _transfer.value = FirmwareTransfer.Active(accepted, t, now() - startedAtMs)
            }
            _transfer.value = FirmwareTransfer.Finished(result)
        }
    }

    /**
     * Calls the update off. Cancelling the job is what sends ABORT — the
     * repository's transfer loop aborts on cancellation so a tail is never left
     * armed with a partial image — and the UI is moved to a cancelled verdict
     * from the last progress seen.
     */
    fun cancel() {
        val active = _transfer.value as? FirmwareTransfer.Active
        installJob?.cancel()
        installJob = null
        if (active != null) {
            _transfer.value =
                FirmwareTransfer.Finished(FirmwareUpdateResult.Aborted(active.accepted, active.total))
        }
    }

    companion object {

        /**
         * Faults a picked file against the same checks the device makes before it
         * erases anything, in the device's own order so the message names the
         * first rule it would break. Every branch here mirrors a
         * [FirmwareUpdateResult] rejection, turning an answer that would otherwise
         * arrive mid-transfer into one the user gets on the file picker.
         */
        internal fun evaluate(
            fileName: String,
            bytes: ByteArray,
            running: FirmwareVersion?
        ): FirmwareSelection {
            val info = FirmwareImage.describe(bytes)
            val blocker = when {
                bytes.size < Protocol.OTA_IMAGE_HEADER_BYTES ->
                    "This file is ${bytes.size} bytes — smaller than the " +
                        "${Protocol.OTA_IMAGE_HEADER_BYTES}-byte header the tail decides from. " +
                        "Pick the tail's .bin firmware image."

                info == null ->
                    "This is not an application image — its header does not start like firmware. " +
                        "The download may be damaged, or it is the wrong file."

                bytes.size > Protocol.OTA_SLOT_BYTES ->
                    "This image is ${bytes.size / 1024} KB; the tail's update slot holds " +
                        "${Protocol.OTA_SLOT_BYTES / 1024} KB, so it cannot be installed over the air."

                info.projectName.isNotEmpty() && info.projectName != FirmwareImage.EXPECTED_PROJECT ->
                    "This firmware was built for \"${info.projectName}\", not " +
                        "${FirmwareImage.EXPECTED_PROJECT} — it is for a different device."

                running != null && info.version != null && info.version == running ->
                    "The tail is already running $running. There is nothing to install."

                else -> null
            }
            return FirmwareSelection(fileName, bytes.size, info, blocker)
        }

        /**
         * Milliseconds of transfer left, extrapolated from the rate so far, or
         * null when there is not yet enough of one. Kept pure so the estimate can
         * be asserted rather than watched.
         */
        fun estimateRemainingMs(accepted: Int, total: Int, elapsedMs: Long): Long? {
            if (accepted <= 0 || elapsedMs <= 0L || accepted >= total) return null
            return elapsedMs * (total - accepted).toLong() / accepted
        }

        /** The document's display name, falling back to the last path segment. */
        private fun displayName(context: Context, uri: Uri): String {
            val fromProvider = runCatching {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
            return fromProvider ?: uri.lastPathSegment ?: "firmware.bin"
        }
    }
}
