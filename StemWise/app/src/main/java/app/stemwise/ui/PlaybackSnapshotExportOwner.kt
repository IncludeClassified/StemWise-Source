package app.stemwise.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

class PlaybackSnapshotExportUi internal constructor(
    val busy: Boolean,
    val message: String?,
    val export: () -> Unit,
    val cancel: (() -> Unit)?,
    val detailedCaptureActive: Boolean = false,
    val toggleDetailedCapture: (() -> Unit)? = null
)

/** Cancellation and the actual platform launch share one non-suspending boundary. */
internal class PlaybackSnapshotPickerLaunch {
    private var cancelled = false

    @Synchronized fun cancel() { cancelled = true }

    @Synchronized fun launch(block: () -> Unit): Boolean {
        if (cancelled) return false
        block()
        return true
    }
}

/** Register at the app root, so navigating away from System cannot retire a pending picker. */
@Composable
internal fun rememberPlaybackSnapshotExport(capture: suspend () -> String,
    detailedCaptureActive: Boolean = false, setDetailedCapture: (Boolean) -> Unit = {}): PlaybackSnapshotExportUi {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { PlaybackSnapshotExportStore(context) }
    val scope = rememberCoroutineScope()
    val currentCapture by rememberUpdatedState(capture)
    var operationId by rememberSaveable { mutableStateOf<String?>(null) }
    var record by remember { mutableStateOf<PlaybackSnapshotExportRecord?>(null) }
    var restoring by remember { mutableStateOf(true) }
    var capturing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        try {
            record = store.current()
            operationId = record?.id
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            message = "Could not restore the playback snapshot export."
        } finally { restoring = false }
    }
    val id = operationId
    val pickerLaunch = remember(id) { PlaybackSnapshotPickerLaunch() }
    key(id) {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (id != null) scope.launch {
                try {
                    if (store.acceptResult(id, uri)) record = store.finish(id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Could not save the playback snapshot. Try another location." }
            }
        }
        LaunchedEffect(id, restoring) {
            if (id == null || restoring) return@LaunchedEffect
            try {
                record = store.current()?.takeIf { it.id == id }
                when (record?.phase) {
                    PlaybackSnapshotExportPhase.CAPTURED -> if (store.markPickerLaunched(id)) {
                        record = store.current()
                        try { pickerLaunch.launch { picker.launch("StemWise-playback-$id.json") } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { store.pickerFailed(id); record = store.current() }
                    }
                    PlaybackSnapshotExportPhase.WRITING -> record = store.finish(id)
                    else -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not restore the playback snapshot export." }
        }
    }
    return PlaybackSnapshotExportUi(
        busy = restoring || capturing || record?.busy == true,
        message = if (capturing) null else message ?: record?.message,
        export = {
            if (!restoring && !capturing && record?.busy != true) {
                capturing = true
                message = null
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val preparedId = store.prepare(currentCapture())
                        record = store.current()
                        operationId = preparedId
                    } catch (cancelled: CancellationException) {
                        message = "Playback snapshot export cancelled"
                        throw cancelled
                    } catch (_: Exception) {
                        message = "Could not capture the playback snapshot. Try again."
                    } finally { capturing = false }
                }
            }
        },
        cancel = if (record?.canCancel == true && !capturing) ({
            pickerLaunch.cancel()
            scope.launch {
                try { if (store.cancel(checkNotNull(id))) record = store.current() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Could not cancel the playback snapshot export." }
            }
            Unit
        }) else null,
        detailedCaptureActive = detailedCaptureActive,
        toggleDetailedCapture = { setDetailedCapture(!detailedCaptureActive) }
    )
}
