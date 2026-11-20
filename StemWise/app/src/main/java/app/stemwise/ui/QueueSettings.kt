package app.stemwise.ui

import android.net.Uri
import android.content.Intent
import android.provider.DocumentsContract
import androidx.core.net.toUri

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.stemwise.model.StemRoleContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stemwise.BuildConfig
import app.stemwise.bridge.BridgeModelCatalog
import app.stemwise.bridge.BridgeSongSubmission
import app.stemwise.bridge.BridgeSongTask
import app.stemwise.bridge.BridgeUiState
import app.stemwise.enrichment.EnrichmentImportResult
import app.stemwise.enrichment.SharedEnrichmentImportState
import app.stemwise.data.PlaylistSettings
import app.stemwise.data.LibraryRootState
import app.stemwise.data.LibraryInventory
import app.stemwise.data.LibraryInventoryAvailability
import app.stemwise.data.LibraryStorageRefreshState
import app.stemwise.data.PlaylistSyncStatus
import app.stemwise.data.PLAYLIST_ITEM_UNAVAILABLE_CODE
import app.stemwise.integration.EngineStatus
import app.stemwise.integration.MvsepConnectionState
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.model.JobKind
import app.stemwise.model.JobState
import app.stemwise.model.QueueJobAction
import app.stemwise.model.QueueOperationPhase
import app.stemwise.model.QueueJob
import app.stemwise.model.Song
import app.stemwise.network.WAITING_FOR_UNMETERED_NETWORK
import app.stemwise.ui.theme.Amber
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.PrimaryBright
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.Outline
import app.stemwise.ui.theme.OutlineStrong
import app.stemwise.ui.theme.Sky
import app.stemwise.ui.theme.Surface
import app.stemwise.ui.theme.SurfaceLow
import app.stemwise.ui.theme.SurfaceRaised
import app.stemwise.ui.theme.StemwisePalette
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary
import app.stemwise.ui.theme.Violet
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.AuraAnchor
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.StemwiseButton
import app.stemwise.ui.design.StemwiseIconButton
import app.stemwise.ui.design.StemwiseRadii
import app.stemwise.ui.design.StemwiseSpacing
import app.stemwise.ui.design.StemwiseTextButton
import app.stemwise.ui.design.stemwiseAuraClickable
import app.stemwise.ui.design.stemwiseAuraClickableAction
import app.stemwise.ui.design.stemWiseInteractionAura
import app.stemwise.ui.design.emitAcceptedAuraFeedback
import app.stemwise.ui.design.semanticSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private enum class ImportDialogAction { IMPORT, DONE }

internal data class MvsepInformationalAction(val label: String, val url: String)

internal val mvsepInformationalActions = listOf(
    MvsepInformationalAction(
        label = "MVSEP details & get token",
        url = "https://mvsep.com/en/full_api"
    )
)

internal const val MVSEP_TOKEN_PRIVACY_COPY =
    "StemWise verifies the token, encrypts it on this device, and blocks premium spending. " +
        "MVSEP handles uploads under its retention and privacy terms."
internal const val MVSEP_HELP_OPEN_ERROR = "Could not open MVSEP details on this device"
internal enum class SystemSurfaceSection(val label: String) {
    CONNECTIONS("Connections"),
    LIBRARY_STORAGE("Library & Storage"),
    TROUBLESHOOTING("Troubleshooting")
}

internal enum class ActivitySection(val label: String) {
    NEEDS_ATTENTION("Needs attention"), IN_PROGRESS("In progress"), WAITING("Waiting"), RECENT("Recent")
}

internal fun activitySection(needsAttention: Boolean, running: Boolean, waiting: Boolean): ActivitySection = when {
    needsAttention -> ActivitySection.NEEDS_ATTENTION
    running -> ActivitySection.IN_PROGRESS
    waiting -> ActivitySection.WAITING
    else -> ActivitySection.RECENT
}

internal fun bridgeActivitySection(task: BridgeSongTask): ActivitySection = activitySection(
    needsAttention = task.isDraft || task.phase in setOf(app.stemwise.bridge.BridgeSongPhase.FAILED,
        app.stemwise.bridge.BridgeSongPhase.EXPIRED, app.stemwise.bridge.BridgeSongPhase.REMOVED, app.stemwise.bridge.BridgeSongPhase.OUTBOX_DAMAGED,
        app.stemwise.bridge.BridgeSongPhase.OUTBOX_UNKNOWN, app.stemwise.bridge.BridgeSongPhase.REVIEW_REQUIRED,
        app.stemwise.bridge.BridgeSongPhase.WAITING) ||
        task.canRetry && task.phase != app.stemwise.bridge.BridgeSongPhase.WAITING_PC,
    running = task.phase in setOf(app.stemwise.bridge.BridgeSongPhase.WAKING, app.stemwise.bridge.BridgeSongPhase.SUBMITTING,
        app.stemwise.bridge.BridgeSongPhase.RUNNING, app.stemwise.bridge.BridgeSongPhase.RECEIVING,
        app.stemwise.bridge.BridgeSongPhase.IMPORTING, app.stemwise.bridge.BridgeSongPhase.ACKNOWLEDGING,
        app.stemwise.bridge.BridgeSongPhase.CANCELLING, app.stemwise.bridge.BridgeSongPhase.RETIRING),
    waiting = !task.terminal)

/** Lazy item placement also animates polling-driven height changes; only section changes own motion. */
private class BridgeTaskRelocation(private val scope: CoroutineScope) {
    var translationY by mutableFloatStateOf(0f)
        private set
    var alpha by mutableFloatStateOf(1f)
        private set
    var relocating by mutableStateOf(false)
        private set
    private var section: ActivitySection? = null
    private var placedY = 0f
    private var paused = false
    private var motionEnabled = true
    private var revision = 0L
    private var animation: Job? = null

    fun configure(paused: Boolean, motionEnabled: Boolean) {
        val resume = this.paused && !paused
        val policyChanged = this.motionEnabled != motionEnabled
        if (paused && !this.paused || policyChanged) {
            revision++
            animation?.cancel()
        }
        if (policyChanged && !motionEnabled) translationY = 0f
        this.paused = paused
        this.motionEnabled = motionEnabled
        if (translationY == 0f && alpha == 1f) relocating = false
        else if (!paused && (resume || policyChanged)) start()
    }

    fun place(nextSection: ActivitySection, y: Float, isVisible: Boolean, wasVisible: Boolean) {
        val sectionChanged = section != null && section != nextSection
        val moved = sectionChanged && wasVisible && isVisible
        val oldY = placedY
        section = nextSection
        placedY = y
        if (!sectionChanged) return
        revision++
        animation?.cancel()
        if (!moved) {
            translationY = 0f
            alpha = 1f
            relocating = false
            return
        }
        if (motionEnabled) {
            translationY += oldY - y
            alpha = 1f
        } else {
            translationY = 0f
            alpha = 0f
        }
        relocating = true
        if (!paused) start()
    }

    private fun start() {
        val owner = ++revision
        animation?.cancel()
        val startY = translationY
        val startAlpha = alpha
        val spec = app.stemwise.ui.design.StemwiseMotionSpec(motionEnabled)
            .semanticSpec<Float>(StemwiseMotionEvent.LIST_REORDER)
        animation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                animate(0f, 1f, animationSpec = spec) { progress, _ ->
                    if (revision == owner) {
                        translationY = startY * (1f - progress)
                        alpha = startAlpha + (1f - startAlpha) * progress
                    }
                }
            } finally {
                if (revision == owner) relocating = false
            }
        }
    }
}

@Composable
private fun RelocatingBridgeTaskRow(
    taskId: String,
    section: ActivitySection,
    wasVisible: Boolean,
    paused: Boolean,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val relocation = remember(scope) { BridgeTaskRelocation(scope) }
    val motion = LocalStemwiseMotion.current
    SideEffect { relocation.configure(paused, motion.enabled) }
    Column(Modifier.fillMaxWidth()
        .onPlaced { coordinates ->
            val y = coordinates.positionInParent().y
            val viewportHeight = coordinates.parentLayoutCoordinates?.size?.height ?: 0
            relocation.place(section, y, y < viewportHeight && y + coordinates.size.height > 0f, wasVisible)
        }
        .zIndex(if (relocation.relocating) 1f else 0f)
        .graphicsLayer {
            translationY = relocation.translationY
            alpha = relocation.alpha
        }
        .testTag("activity-bridge-task-$taskId")) {
        content()
    }
}

internal fun queueActivitySection(job: QueueJob): ActivitySection = activitySection(
    job.state == JobState.NEEDS_ATTENTION, job.state == JobState.RUNNING, job.state == JobState.WAITING)

internal fun sharedImportActivitySection(state: SharedEnrichmentImportState): ActivitySection? = when (state) {
    SharedEnrichmentImportState.Idle -> null
    is SharedEnrichmentImportState.AwaitingConfirmation, is SharedEnrichmentImportState.Failed -> ActivitySection.NEEDS_ATTENTION
    is SharedEnrichmentImportState.Running -> activitySection(state.retirementPending, !state.retirementPending, false)
    is SharedEnrichmentImportState.Finished -> activitySection(state.result.failedCount > 0, false, false)
    is SharedEnrichmentImportState.Cancelled -> ActivitySection.RECENT
}

internal data class SystemStorageSummary(
    val libraryBytes: Long,
    val appBytes: Long,
    val artworkAndJsonCount: Int,
    val verifiedStems: Int,
    val verifiedSongs: Int
)

/**
 * A private/deleted YouTube playlist entry is not actionable work. The repository keeps its
 * terminal fact for diagnostics and idempotence, but Tasks must not keep presenting it as a
 * failed job that the owner can retry.
 */
internal fun ownerVisibleQueueJobs(jobs: List<QueueJob>): List<QueueJob> =
    jobs.filterNot { job ->
        job.stableCode == PLAYLIST_ITEM_UNAVAILABLE_CODE
    }

internal fun canRetryQueueJob(job: QueueJob): Boolean =
    job.action != null && job.state == JobState.NEEDS_ATTENTION

internal fun systemStorageSummary(inventory: LibraryInventory): SystemStorageSummary =
    SystemStorageSummary(
        libraryBytes = inventory.actualLibraryBytes,
        appBytes = inventory.appPrivateTotalBytes,
        artworkAndJsonCount = inventory.verifiedArtworkFiles + inventory.verifiedJsonFiles,
        verifiedStems = inventory.verifiedStemFiles,
        verifiedSongs = inventory.verifiedSongPackages
    )

@Composable
internal fun QueueScreen(
    onImportEnrichment: (List<Uri>) -> Unit,
    playlistSettings: PlaylistSettings,
    syncStatus: PlaylistSyncStatus,
    onSync: () -> Boolean,
    bridgeState: BridgeUiState,
    bridgeTasks: List<BridgeSongTask>,
    onRetryBridgeTask: suspend (String) -> Result<Unit>,
    onForgetBridgeTask: suspend (String) -> Result<Unit>,
    jobs: List<QueueJob> = emptyList(),
    onRetryJob: (QueueJob) -> Unit,
    sharedImportState: SharedEnrichmentImportState = SharedEnrichmentImportState.Idle,
    onConfirmSharedImport: () -> Unit = {},
    onDismissSharedImport: () -> Unit = {},
    onDiscardSharedImport: (String) -> Unit = {},
    onCancelImport: () -> Unit = {},
    onCancelBridgeTask: suspend (String) -> Result<Unit> = { Result.failure(IllegalStateException("Cancellation is unavailable")) },
    onBridgeTaskDiagnostics: suspend (String) -> Result<String> = { Result.failure(IllegalStateException("Diagnostics are unavailable")) },
    onOpenAddSong: () -> Unit,
    onReviewDraft: (BridgeSongTask) -> Unit,
    onRetryBridgeWake: suspend (String) -> Result<Unit> = { Result.failure(IllegalStateException("Wake retry is unavailable")) },
    onOpenSettings: ((SystemSurfaceSection) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val motion = LocalStemwiseMotion.current
    val context = LocalContext.current
    val visibleJobs = ownerVisibleQueueJobs(jobs).sortedBy { it.id }
    val orderedBridgeTasks = bridgeTasks.sortedWith(compareByDescending<BridgeSongTask> { it.createdAtEpochMs }.thenBy { it.id })
    var pointerDown by remember { mutableStateOf(false) }
    val placementPaused = pointerDown || listState.isScrollInProgress
    val requestedSections = bridgeTasks.associate { it.id to bridgeActivitySection(it) }
    var publishedSections by remember { mutableStateOf(requestedSections) }
    val displayedSections = if (placementPaused) requestedSections.mapValues { (id, section) ->
        publishedSections[id] ?: section
    } else requestedSections
    val previousVisibleKeys = remember(displayedSections) {
        Snapshot.withoutReadObservation { listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
    }
    SideEffect { publishedSections = displayedSections }
    val syncActive = playlistSyncActive(syncStatus)
    val syncSection = playlistActivitySection(syncStatus)
    val importSection = sharedImportActivitySection(sharedImportState)
    val importOwner: Any = when (sharedImportState) {
        is SharedEnrichmentImportState.AwaitingConfirmation -> sharedImportState.operationId
        is SharedEnrichmentImportState.Running -> sharedImportState.operationId
        else -> sharedImportState
    }
    var showImportDetails by remember(importOwner) { mutableStateOf(false) }
    val connectionIssues = listOfNotNull(bridgeState.error, bridgeState.connectionIssue).filter(String::isNotBlank).distinct()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.takeIf(List<Uri>::isNotEmpty)?.let(onImportEnrichment)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val contentWidth = minOf(maxWidth, 720.dp)
    val horizontalMargin = if (contentWidth < 392.dp) 16.dp else 20.dp
    LazyColumn(
        modifier = Modifier.width(contentWidth).align(Alignment.TopCenter)
            .pointerInput(Unit) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            pointerDown = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                        }
                    }
                } finally { pointerDown = false }
            },
        state = listState,
        contentPadding = PaddingValues(
            start = horizontalMargin,
            end = horizontalMargin,
            top = 12.dp,
            bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item(key = "activity-heading") {
            Text("Activity", color = TextPrimary, fontSize = 30.sp, lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            val summary = tasksSummary(visibleJobs, bridgeTasks, syncStatus, sharedImportState, connectionIssues.isNotEmpty())
            if (summary.isNotBlank()) Text(summary, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            ActivityEntryActions(
                onImport = { importLauncher.launch(app.stemwise.SONG_ZIP_MIME_TYPES.toTypedArray()) },
                onSendToPc = onOpenAddSong
            )
        }
        for (section in ActivitySection.entries) {
            val sectionBridge = orderedBridgeTasks.filter { displayedSections[it.id] == section }
            val sectionJobs = visibleJobs.filter { queueActivitySection(it) == section }
            val hasConnectionIssue = section == ActivitySection.NEEDS_ATTENTION && connectionIssues.isNotEmpty()
            if (sectionBridge.isEmpty() && sectionJobs.isEmpty() && syncSection != section &&
                importSection != section && !hasConnectionIssue) continue
            item(key = "section:${section.name}") {
                Spacer(Modifier.height(24.dp))
                Text(section.label, style = MaterialTheme.typography.titleMedium, color = TextPrimary,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(10.dp))
            }
            if (hasConnectionIssue) item(key = "bridge-connection-issues") {
                connectionIssues.forEach { BridgeError(it) }
                onOpenSettings?.let { open ->
                    StemwiseTextButton(onClick = { open(SystemSurfaceSection.CONNECTIONS) }) { Text("Open connection settings") }
                }
            }
            items(sectionBridge, key = { "bridge:${it.id}" }) { task ->
                RelocatingBridgeTaskRow(task.id, section, "bridge:${task.id}" in previousVisibleKeys, placementPaused) {
                    Spacer(Modifier.height(12.dp))
                    BridgeTaskCard(task = task, onRetry = onRetryBridgeTask, onForget = onForgetBridgeTask,
                        onCancel = onCancelBridgeTask, diagnostics = onBridgeTaskDiagnostics,
                        onRetryWake = onRetryBridgeWake,
                        onReview = { onReviewDraft(task) })
                }
            }
            if (importSection == section) {
                (sharedImportState as? SharedEnrichmentImportState.Running)?.let { progress ->
                    item(key = "song-import") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (progress.retirementPending) "Import needs attention" else "Importing songs",
                                color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(progress.phase, color = TextSecondary)
                            if (progress.progressKind == app.stemwise.enrichment.ImportProgressKind.FILES) {
                                Text(androidx.compose.ui.res.pluralStringResource(app.stemwise.R.plurals.import_files_progress,
                                    progress.total, progress.completed, progress.total,
                                    android.text.format.Formatter.formatShortFileSize(context, progress.copiedBytes)),
                                    color = TextSecondary)
                            }
                            if (!progress.retirementPending) {
                                val byteTotal = progress.totalBytes?.takeIf { it > 0L }
                                if (byteTotal != null || progress.total > 0) ProgressTrack(
                                    progress = if (byteTotal != null) progress.copiedBytes.toFloat() / byteTotal
                                        else progress.completed.toFloat() / progress.total,
                                    color = PrimaryPurple, height = 4.dp
                                ) else LinearProgressIndicator(Modifier.fillMaxWidth(), color = PrimaryPurple)
                                StemwiseTextButton(onClick = onCancelImport) { Text("Cancel import") }
                            }
                        }
                    }
                }
                if (sharedImportState !is SharedEnrichmentImportState.Running) item(key = "shared-import") {
                    val waiting = sharedImportState is SharedEnrichmentImportState.AwaitingConfirmation
                    Text(when {
                        waiting -> "Shared songs need review"
                        sharedImportState is SharedEnrichmentImportState.Failed -> "Import needs attention"
                        sharedImportState is SharedEnrichmentImportState.Cancelled -> "Import cancelled"
                        sharedImportState is SharedEnrichmentImportState.Finished && sharedImportState.result.failedCount > 0 -> "Some songs were not imported"
                        else -> "Import complete"
                    }, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    StemwiseTextButton(onClick = { showImportDetails = true }) {
                        Text(if (waiting) "Review import" else if (section == ActivitySection.NEEDS_ATTENTION) "View issue" else "View import")
                    }
                }
            }
            items(sectionJobs, key = { "job:${it.id}" }) { job ->
                TaskCard(job = job, onRetry = if (canRetryQueueJob(job)) ({ onRetryJob(job) }) else null)
            }
            if (syncSection == section) item(key = "playlist-sync") {
                if (syncActive) CurrentOperationBand(syncing = syncStatus.running, syncQueued = syncStatus.queued,
                    waitingForNetwork = syncStatus.waitingForNetwork)
                syncStatus.error?.let { BridgeError(it) }
                if (syncStatus.lastSummary?.projectionRefreshPending == true)
                    BridgeError("Playlist saved. Library refresh is pending; retry Sync or reopen the Library.")
                if (section == ActivitySection.NEEDS_ATTENTION) {
                    StemwiseTextButton(enabled = !syncActive && playlistSettings.configured, onClick = { onSync() }) { Text("Retry sync") }
                    onOpenSettings?.let { open ->
                        StemwiseTextButton(onClick = { open(SystemSurfaceSection.LIBRARY_STORAGE) }) { Text("Open library settings") }
                    }
                }
            }
        }
        if (visibleJobs.isEmpty() && bridgeTasks.isEmpty() && syncSection == null &&
            importSection == null && connectionIssues.isEmpty()) item(key = "activity-empty") {
            Text("No activity yet.", color = TextSecondary,
                modifier = Modifier.padding(vertical = 32.dp))
        }

        item(key = "library-controls") {
            Spacer(Modifier.height(24.dp))
            PlaylistSyncRow(playlistSettings = playlistSettings, syncStatus = syncStatus, onSync = onSync)
        }
    }
    }

    if (showImportDetails && sharedImportState !is SharedEnrichmentImportState.Idle &&
        sharedImportState !is SharedEnrichmentImportState.Running) {
        val awaitingState = sharedImportState as? SharedEnrichmentImportState.AwaitingConfirmation
        val awaiting = awaitingState != null
        val offered = awaitingState?.sourceNames?.isEmpty() == true
        val awaitingCount = awaitingState?.sources?.size ?: 0
        val result = (sharedImportState as? SharedEnrichmentImportState.Finished)?.result
        val resultIssues = result?.items.orEmpty().filterNot { it.isSuccess }
        val failure = awaitingState?.message ?: (sharedImportState as? SharedEnrichmentImportState.Failed)?.message
            ?: (sharedImportState as? SharedEnrichmentImportState.Cancelled)?.message
        val rejectedOffers = when (val state = sharedImportState) {
            is SharedEnrichmentImportState.AwaitingConfirmation -> state.rejectedOffers
            is SharedEnrichmentImportState.Running -> state.rejectedOffers
            is SharedEnrichmentImportState.Finished -> state.rejectedOffers
            is SharedEnrichmentImportState.Failed -> state.rejectedOffers
            is SharedEnrichmentImportState.Cancelled -> state.rejectedOffers
            SharedEnrichmentImportState.Idle -> 0
        }
        val importTitle = when {
            offered -> "Import shared files?"
            awaiting -> "Review import"
            else -> "Song import"
        }
        val importAction = "Import"
        val finishImportReview: () -> Unit = { showImportDetails = false; onDismissSharedImport() }
        val importBody = if (offered) {
            "${androidx.compose.ui.res.pluralStringResource(app.stemwise.R.plurals.file_count, awaitingCount, awaitingCount)} were shared with " +
                "StemWise. Import copies and validates them, then adds or updates matching Library songs. " +
                "No file contents have been read yet. Existing Original audio and stems are preserved."
        } else if (awaiting) {
            awaitingState.message ?: "${androidx.compose.ui.res.pluralStringResource(app.stemwise.R.plurals.file_count, awaitingCount, awaitingCount)} can add PC-prepared songs or update matching songs. " +
                "Existing Original audio and stems are preserved."
        } else {
            failure ?: result?.let(::importResultSummary).orEmpty().ifBlank {
                "Opening and validating song package…"
            }
        }
        val importBodyColor by animateColorAsState(
            targetValue = if (failure == null) TextSecondary else Coral,
            animationSpec = motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE),
            label = "shared-import-body-color"
        )
        StemwiseAlertDialog(
            onDismissRequest = { showImportDetails = false },
            title = {
                AnimatedContent(
                    targetState = importTitle,
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    },
                    label = "shared-import-title"
                ) { title ->
                    Text(title, modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                }
            },
            text = {
                Column(
                    Modifier.animateContentSize(
                        animationSpec = motion.semanticSpec(
                            StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                            spatial = true
                        )
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AnimatedContent(
                        targetState = importBody,
                        transitionSpec = {
                            fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                                fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                        },
                        label = "shared-import-body"
                    ) { body ->
                        Text(
                            body,
                            color = importBodyColor,
                            modifier = Modifier
                                .heightIn(max = 410.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                    if (awaitingState != null) {
                        StemwiseTextButton(enabled = actionsEnabled, onClick = {
                            closeAfterStarting(
                                startAction = { onDiscardSharedImport(awaitingState.operationId); true },
                                afterClose = { showImportDetails = false }
                            )
                        }) {
                            Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.import_discard), color = Coral)
                        }
                    }
                    AnimatedVisibility(
                        visible = rejectedOffers > 0,
                        enter = fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                            expandVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)),
                        exit = fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                            shrinkVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW))
                    ) {
                        Text(
                            if (rejectedOffers == 1) {
                                "One other import was not added. Choose it again after this finishes."
                            } else {
                                "$rejectedOffers other imports were not added. Choose them again after this finishes."
                            },
                            color = Amber,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    AnimatedVisibility(
                        visible = resultIssues.isNotEmpty(),
                        enter = fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                            expandVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)),
                        exit = fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                            shrinkVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW))
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(resultIssues) { item ->
                                Column {
                                    Text(
                                        item.sourceName ?: item.videoId ?: item.packageId ?: "Unknown song",
                                        modifier = Modifier.stemwiseMarquee(),
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        item.errorCode?.let { code ->
                                            "${friendlyImportError(code)}  ·  $code"
                                        } ?: "This song was not imported.",
                                        color = Coral,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                }
            },
            confirmButton = {
                AnimatedContent(
                    targetState = when {
                        awaiting -> ImportDialogAction.IMPORT
                        else -> ImportDialogAction.DONE
                    },
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    },
                    label = "shared-import-action"
                ) { action ->
                    val label = androidx.compose.ui.res.stringResource(when (action) {
                        ImportDialogAction.IMPORT -> app.stemwise.R.string.import_confirm
                        ImportDialogAction.DONE -> app.stemwise.R.string.ui_done
                    })
                    StemwiseTextButton(enabled = actionsEnabled, onClick = {
                        when (action) {
                            ImportDialogAction.IMPORT -> closeAfterStarting(
                                startAction = { onConfirmSharedImport(); true },
                                afterClose = { showImportDetails = false }
                            )
                            ImportDialogAction.DONE -> closeThen(finishImportReview)
                        }
                    }) { Text(label, modifier = Modifier.stemwiseMarquee(), maxLines = 1) }
                }
            },
            dismissButton = {
                AnimatedVisibility(
                    visible = awaiting,
                    enter = fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)),
                    exit = fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                ) {
                    StemwiseTextButton(enabled = actionsEnabled, onClick = { dismiss() }) {
                        Text("Not now", modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                    }
                }
            },
            containerColor = SurfaceRaised
        )
    }

}

private fun importResultSummary(result: EnrichmentImportResult): String = buildString {
    val newlyImported = (result.importedCount - result.updatedCount).coerceAtLeast(0)
    append(
        listOfNotNull(
            "$newlyImported imported",
            result.updatedCount.takeIf { it > 0 }?.let { "$it updated" },
            result.supersededCount.takeIf { it > 0 }?.let { "$it superseded" },
            result.unchangedCount.takeIf { it > 0 }?.let { "$it unchanged" },
            result.failedCount.takeIf { it > 0 }?.let { "$it need attention" }
        ).joinToString(" · ")
    )
}

private fun friendlyImportError(code: String): String = when (code) {
    "COMPLETE_SONG_REQUIRED" -> "Choose a complete song ZIP with Original and all song data."
    "SONG_NOT_FOUND" -> "No matching song is loaded in this library."
    "SOURCE_NOT_BOUND" -> "Add or sync the song, then import this package again."
    "SOURCE_HASH_MISMATCH" -> "This package belongs to a different original audio file."
    "ORIGINAL_CONFLICT", "CANONICAL_ASSET_CONFLICT" ->
        "Different Original audio is installed. Deliberately replace its Library song folder on the PC, refresh the Library, then import."
    "ENRICHMENT_UPDATE_STALE" -> "A newer song package is already installed."
    "ENRICHMENT_REVISION_CONFLICT" -> "This update conflicts with the installed revision."
    "ROOT_UNAVAILABLE", "SOURCE_URI_PERMISSION_LOST" ->
        "Reconnect the Library or select the ZIP again."
    "STORAGE_RESERVE_LOW", "LIBRARY_STORAGE_LOW" ->
        "Free some space, then import again."
    "RECOVERY_PENDING", "EXTERNAL_COMMIT_FAILED" ->
        "The Library needs recovery before this update can finish."
    "ZIP_TOO_LARGE", "ZIP_ENTRY_LIMIT" -> "The selected import is too large."
    "ZIP_FORMAT_UNRECOGNIZED", "ZIP_UNEXPECTED_ENTRY", "ZIP_DUPLICATE_ENTRY",
    "ZIP_TRAVERSAL", "BULK_ENTRY_INVALID", "INNER_ZIP_INVALID",
    "AUDIO_ENTRY_FORBIDDEN" -> "Choose a complete StemWise song ZIP."
    "PNG_INVALID", "PNG_MISSING", "PNG_TOO_LARGE", "PNG_ANIMATED",
    "PNG_DIMENSIONS_INVALID", "ARTWORK_MISSING", "ARTWORK_PATH_INVALID",
    "ARTWORK_LENGTH_MISMATCH", "ARTWORK_HASH_MISMATCH" ->
        "The artwork in this package is invalid."
    "MANIFEST_JSON_INVALID", "MANIFEST_TOO_LARGE", "MANIFEST_UTF8_INVALID",
    "JSON_FIELD_UNKNOWN", "SCHEMA_UNSUPPORTED", "YOUTUBE_VIDEO_ID_INVALID",
    "YOUTUBE_VIDEO_ID_MISSING" -> "This package has an unsupported or invalid song manifest."
    "ANALYSIS_STATUS_INVALID", "TIMELINE_INVALID", "TOOL_RUN_INVALID",
    "WAVEFORM_INVALID" -> "The analysis data in this package is invalid."
    "LYRICS_INVALID" -> "The lyrics data in this package is invalid."
    "ENRICHMENT_UPDATE_EMPTY" -> "This package contains no song updates."
    else -> "Import issue ($code)."
}


internal fun queueSummary(jobs: List<QueueJob>): String {
    val running = jobs.count { it.state == JobState.RUNNING }
    val attention = jobs.count { it.state == JobState.NEEDS_ATTENTION }
    val queued = jobs.count { it.state == JobState.WAITING }
    return buildList {
        if (running > 0) add("$running running")
        if (attention > 0) {
            add(if (attention == 1) "1 needs attention" else "$attention need attention")
        }
        if (queued > 0) add("$queued queued")
    }.joinToString(" · ").ifBlank { "0 queued" }
}

internal fun playlistActivitySection(status: PlaylistSyncStatus): ActivitySection? =
    if (playlistSyncActive(status) || status.error != null || status.lastSummary?.projectionRefreshPending == true)
        activitySection(status.error != null || status.lastSummary?.projectionRefreshPending == true,
            status.running && !status.waitingForNetwork, !status.running || status.waitingForNetwork) else null

internal fun tasksSummary(jobs: List<QueueJob>, tasks: List<BridgeSongTask>, sync: PlaylistSyncStatus,
    imports: SharedEnrichmentImportState, connectionIssue: Boolean = false): String {
    val sections = jobs.map(::queueActivitySection) + tasks.map(::bridgeActivitySection) +
        listOfNotNull(playlistActivitySection(sync), sharedImportActivitySection(imports)) +
        if (connectionIssue) listOf(ActivitySection.NEEDS_ATTENTION) else emptyList()
    return ActivitySection.entries.mapNotNull { section ->
        val count = sections.count { it == section }
        if (count == 0) null else when (section) {
            ActivitySection.NEEDS_ATTENTION -> if (count == 1) "1 needs attention" else "$count need attention"
            ActivitySection.IN_PROGRESS -> "$count in progress"
            ActivitySection.WAITING -> "$count waiting"
            ActivitySection.RECENT -> "$count recent"
        }
    }.joinToString(" · ")
}

/** Reports user-triggered failures while preserving structured cancellation. */
internal suspend fun <T> captureUserOperation(
    block: suspend () -> T
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

internal fun operationFailureMessage(error: Throwable, fallback: String): String {
    val detail = error.message?.trim()?.takeIf(String::isNotBlank)?.take(180)
    return if (detail == null || detail.equals(fallback, ignoreCase = true)) {
        fallback
    } else {
        "$fallback: $detail"
    }
}

internal data class TasksActionDashboardPolicy(
    val stacked: Boolean,
    val tileHeightDp: Float,
    val gapDp: Float
)

internal const val TASKS_ACTION_LABEL_FONT_SIZE_SP = 11.5f
internal const val TASKS_CURRENT_BAND_HEIGHT_DP = 108f
internal const val TASKS_CURRENT_ICON_START_DP = 18f
internal const val TASKS_CURRENT_ICON_TOP_DP = 22f
internal const val TASKS_CURRENT_TRAILING_END_INSET_DP = 24f
internal const val TASKS_CURRENT_PROGRESS_TOP_DP = 84f
internal const val TASKS_QUEUED_ROW_HEIGHT_DP = 74f
internal const val TASKS_QUEUED_ICON_START_DP = 8f
internal const val TASKS_QUEUED_ICON_TOP_DP = 10f
internal const val TASKS_QUEUED_TEXT_START_DP = 48f
internal const val TASKS_QUEUED_END_INSET_DP = 8f
internal const val TASKS_QUEUED_STATUS_RESERVATION_DP = 68f
internal const val TASKS_QUEUED_DIVIDER_TOP_DP = 62f

internal fun tasksActionDashboardPolicy(
    availableWidthDp: Float,
    fontScale: Float
): TasksActionDashboardPolicy {
    require(availableWidthDp >= 0f)
    require(fontScale > 0f)
    val stacked = fontScale >= 1.3f || availableWidthDp < 260f
    return TasksActionDashboardPolicy(
        stacked = stacked,
        tileHeightDp = when {
            fontScale >= 2f -> 88f
            stacked -> 72f
            else -> 64f
        },
        gapDp = 12f
    )
}

internal fun systemMvsepStatusAccent(status: MvsepConnectionStatus): Color = when (status) {
    MvsepConnectionStatus.ERROR -> Coral
    MvsepConnectionStatus.READY -> SYSTEM_CONNECTION_SECONDARY_ACCENT
    MvsepConnectionStatus.CHECKING -> PrimaryPurple
    MvsepConnectionStatus.SAVED_UNVERIFIED,
    MvsepConnectionStatus.NOT_CONFIGURED -> TextSecondary
}

@Composable
internal fun PreparationRecoveryDialog(damagedSongIds: List<String>, onDismiss: () -> Unit, onRetry: () -> Unit) {
    StemwiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.preparation_recovery_title)) },
        text = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.preparation_recovery_description,
            damagedSongIds.joinToString())) },
        confirmButton = {
            StemwiseTextButton(onClick = onRetry) {
                Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.preparation_retry_recovery))
            }
        }, containerColor = SurfaceRaised
    )
}

@Composable
private fun PlaylistSyncRow(
    playlistSettings: PlaylistSettings,
    syncStatus: PlaylistSyncStatus,
    onSync: () -> Boolean
) {
    val checked = playlistSettings.lastSyncEpochMs?.takeIf { it > 0L }
    val checkedText = checked?.let {
        "Last checked successfully: " + DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(it))
    } ?: "Not checked yet"
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Playlist sync", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(checkedText, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        StemwiseIconButton(enabled = playlistSettings.configured && !playlistSyncActive(syncStatus),
            onClick = { onSync() }, modifier = Modifier.size(48.dp).semantics {
                contentDescription = if (playlistSyncActive(syncStatus)) "${playlistSyncActionLabel(syncStatus)} playlist sync" else "Sync playlist"
            }) {
            if (syncStatus.running && !syncStatus.waitingForNetwork) {
                CircularProgressIndicator(Modifier.size(22.dp), color = PrimaryPurple, strokeWidth = 2.dp)
            } else Icon(StemwiseIcons.Sync, contentDescription = null, tint = if (playlistSettings.configured) PrimaryPurple else TextSecondary)
        }
    }
}

@Composable
private fun ActivityEntryActions(onImport: () -> Unit, onSendToPc: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val dashboard = tasksActionDashboardPolicy(maxWidth.value, fontScale)
        val tileHeight = dashboard.tileHeightDp.dp
        val actions: @Composable (Modifier) -> Unit = { actionModifier ->
            ActivityActionPill(StemwiseIcons.ImportZip, "Import ZIP", tileHeight, actionModifier) {
                onImport(); true
            }
            ActivityActionPill(StemwiseIcons.CloudUpload, "Send to PC", tileHeight, actionModifier) {
                onSendToPc(); true
            }
        }
        if (dashboard.stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(dashboard.gapDp.dp)) {
                actions(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(dashboard.gapDp.dp)) {
                actions(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActivityActionPill(
    icon: ImageVector,
    label: String,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val motion = LocalStemwiseMotion.current
    val borderColor by animateColorAsState(
        targetValue = if (enabled) OutlineStrong else StemwisePalette.disabledControlOutline,
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE),
        label = "tasks-action-border"
    )
    val iconColor by animateColorAsState(
        targetValue = if (enabled) PrimaryPurple else TextSecondary,
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE),
        label = "tasks-action-icon"
    )
    val labelColor by animateColorAsState(
        targetValue = if (enabled) TextPrimary else TextSecondary,
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE),
        label = "tasks-action-label-color"
    )
    StemwiseButton(
        onClick = {
            if (onClick()) interactionSource.emitAcceptedAuraFeedback(true)
        },
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Surface,
            contentColor = TextPrimary,
            disabledContainerColor = StemwisePalette.disabledTaskTileFill,
            disabledContentColor = TextSecondary
        ),
        border = BorderStroke(1.dp, borderColor),
        contentPadding = PaddingValues(horizontal = 12.dp),
        interactionSource = interactionSource,
        emitAcceptedFeedback = false
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(22.dp).offset(y = (-1).dp)
            )
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                        fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                },
                label = "tasks-action-label"
            ) { visibleLabel ->
                Text(
                    visibleLabel,
                    color = labelColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = TASKS_ACTION_LABEL_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
internal fun PlaylistToolRow(
    icon: ImageVector,
    title: String,
    detail: String,
    accent: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember(title) { MutableInteractionSource() }
    val motion = LocalStemwiseMotion.current
    val rowModifier = Modifier
            .fillMaxWidth()
            .stemWiseInteractionAura(
                enabled = true,
                selected = false,
                interactionSource = interactionSource,
                anchor = AuraAnchor.VisibleControl,
                reducedMotion = !motion.enabled,
            )
            .stemwiseAuraClickableAction(
                interactionSource = interactionSource,
                role = Role.Button,
                onClick = onClick
            )
            .background(SurfaceLow, RoundedCornerShape(18.dp))
            .border(1.dp, OutlineStrong, RoundedCornerShape(18.dp))
    BoxWithConstraints(
        rowModifier.heightIn(min = SYSTEM_FOLDER_ROW_HEIGHT_DP.dp)
    ) {
        val rowWidth = maxWidth
        if (LocalDensity.current.fontScale >= 1.3f) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SYSTEM_FOLDER_ROW_HEIGHT_DP.dp)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .graphicsLayer { clip = false },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accent,
                        modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp)) {
                    Text(
                        title,
                        modifier = Modifier.stemwiseMarquee(),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        detail,
                        modifier = Modifier.stemwiseMarquee(),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    StemwiseIcons.Disclosure,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(SYSTEM_FOLDER_ROW_HEIGHT_DP.dp)
            ) {
                Box(
                    Modifier
                        .offset(x = 18.dp, y = 24.dp)
                        .size(24.dp)
                        .graphicsLayer { clip = false },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accent,
                        modifier = Modifier.size(24.dp))
                }
                Column(
                    Modifier
                        .offset(x = 58.dp, y = 14.dp)
                        .width(
                            (rowWidth - SYSTEM_FOLDER_TEXT_START_DP.dp -
                                SYSTEM_FOLDER_TEXT_END_INSET_DP.dp).coerceAtLeast(0.dp)
                        )
                ) {
                    Text(
                        title,
                        modifier = Modifier.stemwiseMarquee(),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        detail,
                        modifier = Modifier.stemwiseMarquee(),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    StemwiseIcons.Disclosure,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .offset(x = rowWidth - 42.dp, y = 24.dp)
                        .size(24.dp)
                )
            }
        }
    }
}

internal const val SYSTEM_FOLDER_ROW_HEIGHT_DP = 72f
internal const val SYSTEM_FOLDER_TEXT_START_DP = 58f
internal const val SYSTEM_FOLDER_TEXT_END_INSET_DP = 52f

@Composable
private fun TaskCard(
    job: QueueJob,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val motion = LocalStemwiseMotion.current
    var showDetails by remember(job.id) { mutableStateOf(false) }
    val interactionSource = remember(job.id) { MutableInteractionSource() }
    val detailsModifier = Modifier
        .stemWiseInteractionAura(enabled = true, selected = false,
            interactionSource = interactionSource, reducedMotion = !motion.enabled)
        .stemwiseAuraClickableAction(interactionSource = interactionSource,
            onClickLabel = androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_view_task_details),
            onClick = { showDetails = true })
    AnimatedContent(
        targetState = job.state == JobState.WAITING,
        modifier = modifier,
        transitionSpec = {
            fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
        },
        label = "task-row-kind"
    ) { waiting ->
        if (waiting) {
            CanonicalQueuedTaskRow(job = job, modifier = detailsModifier)
        } else {
            ActiveTaskCard(job = job, onRetry = onRetry, modifier = detailsModifier)
        }
    }
    if (showDetails) StemwiseAlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_task_details)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(job.songTitle, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                if (job.detail.isNotBlank()) Text(job.detail, color = TextSecondary)
            }
        },
        confirmButton = {
            StemwiseTextButton(onClick = { dismiss() }) {
                Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_close))
            }
        }
    )
}

@Composable
private fun ActiveTaskCard(
    job: QueueJob,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val motion = LocalStemwiseMotion.current
    val stateLabel = when {
        job.state == JobState.RUNNING -> when (job.kind) {
            JobKind.SEPARATION -> "Processing"
            JobKind.LIBRARY -> "Checking"
        }
        job.state == JobState.WAITING -> "Queued"
        job.state == JobState.COMPLETE -> "Complete"
        else -> "Needs attention"
    }
    val stateColor = when {
        job.state == JobState.NEEDS_ATTENTION -> Coral
        job.state == JobState.RUNNING -> PrimaryPurple
        else -> TextSecondary
    }
    val animatedStateColor by animateColorAsState(
        targetValue = stateColor,
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE),
        label = "task-state-color"
    )
    val icon = when (job.kind) {
        JobKind.SEPARATION -> StemwiseIcons.Stems
        JobKind.LIBRARY -> StemwiseIcons.Error
    }
    val separationRunning = job.kind == JobKind.SEPARATION && job.state == JobState.RUNNING
    val attentionAction = job.state == JobState.NEEDS_ATTENTION &&
        onRetry != null

    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = motion.semanticSpec(
                    StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                    spatial = true
                )
            )
            .semantics(mergeDescendants = true) {
                stateDescription = stateLabel
            }
            .padding(horizontal = StemwiseSpacing.xs, vertical = StemwiseSpacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = job.accent, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    job.songTitle,
                    modifier = Modifier.stemwiseMarquee(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    job.detail,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

            }
            Column(horizontalAlignment = Alignment.End) {
                AnimatedContent(
                    targetState = stateLabel,
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    },
                    label = "task-state"
                ) { label ->
                    Text(
                        label,
                        color = animatedStateColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.stemwiseMarquee().clearAndSetSemantics { }
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = separationRunning,
            enter = fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                expandVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)),
            exit = fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                shrinkVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW))
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = job.accent)
            }
        }
        AnimatedVisibility(
            visible = attentionAction,
            enter = fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                expandVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)),
            exit = fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                shrinkVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW))
        ) {
            Column {
                Spacer(Modifier.height(11.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .stemwiseAuraClickable(
                            onClick = {
                                onRetry?.invoke()
                            }
                        )
                        .clip(StemwiseRadii.control)
                        .background(StemwisePalette.destructiveActionFill)
                        .semantics {
                            contentDescription = (job.action ?: QueueJobAction.RETRY)
                                .accessibilityDescription(job.songTitle)
                        }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        job.actionLabel ?: "TRY AGAIN",
                        color = Coral,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = .7.sp,
                        maxLines = 1,
                        modifier = Modifier.stemwiseMarquee()
                    )
                }
                }
            }
        }
        ThinDivider(Modifier.padding(start = 40.dp))
    }
}

@Composable
private fun CanonicalQueuedTaskRow(
    job: QueueJob,
    modifier: Modifier = Modifier
) {
    val icon = when (job.kind) {
        JobKind.SEPARATION -> StemwiseIcons.Stems
        JobKind.LIBRARY -> StemwiseIcons.Error
    }
    val fontScale = LocalDensity.current.fontScale
    if (fontScale >= 1.3f) {
        Column(
            modifier
                .fillMaxWidth()
                .heightIn(min = TASKS_QUEUED_ROW_HEIGHT_DP.dp)
                .semantics(mergeDescendants = true) {
                    stateDescription = "Queued"
                }
                .padding(horizontal = TASKS_QUEUED_ICON_START_DP.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = job.accent,
                    modifier = Modifier.size(24.dp)
                )
                TaskSummaryCopy(job.songTitle, job.detail, expanded = true,
                    modifier = Modifier.weight(1f).padding(start = 16.dp, end = 12.dp))
                Text(
                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_queued),
                    color = job.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clearAndSetSemantics { }
                )
            }
            ThinDivider(Modifier.padding(start = 40.dp))
        }
    } else {
        BoxWithConstraints(
            modifier
                .fillMaxWidth()
                .height(TASKS_QUEUED_ROW_HEIGHT_DP.dp)
                .semantics(mergeDescendants = true) {
                    stateDescription = "Queued"
                }
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = job.accent,
                modifier = Modifier
                    .offset(
                        x = TASKS_QUEUED_ICON_START_DP.dp,
                        y = TASKS_QUEUED_ICON_TOP_DP.dp
                    )
                    .size(24.dp)
            )
            TaskSummaryCopy(job.songTitle, job.detail, expanded = false, modifier =
                Modifier
                    .offset(
                        x = TASKS_QUEUED_TEXT_START_DP.dp,
                        y = TASKS_QUEUED_ICON_TOP_DP.dp
                    )
                    .width(
                        (maxWidth - TASKS_QUEUED_TEXT_START_DP.dp -
                            TASKS_QUEUED_STATUS_RESERVATION_DP.dp).coerceAtLeast(0.dp)
                    )
            )
            Text(
                androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_queued),
                color = job.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = TASKS_QUEUED_END_INSET_DP.dp)
                    .offset(y = 12.dp)
                    .clearAndSetSemantics { }
            )
            Box(
                Modifier
                    .offset(
                        x = TASKS_QUEUED_TEXT_START_DP.dp,
                        y = TASKS_QUEUED_DIVIDER_TOP_DP.dp
                    )
                    .fillMaxWidth()
                    .padding(end = (
                        TASKS_QUEUED_TEXT_START_DP + TASKS_QUEUED_END_INSET_DP
                    ).dp)
                    .height(1.dp)
                    .background(StemwisePalette.subtleDivider)
            )
        }
    }
}

@Composable
private fun CurrentOperationBand(
    syncing: Boolean,
    syncQueued: Boolean,
    waitingForNetwork: Boolean,
    modifier: Modifier = Modifier
) {
    val motion = LocalStemwiseMotion.current
    val stateLabel = when {
        syncQueued && waitingForNetwork -> "Waiting"
        syncing -> "Syncing"
        else -> "Queued"
    }
    val title = "Playlist sync"
    val detail = when {
        syncQueued && waitingForNetwork -> "Waiting for an unmetered network"
        syncQueued -> "Waiting for Android to run the sync"
        else -> "Updating the local library"
    }
    val icon = StemwiseIcons.Sync
    val animatedAccent = Violet
    val progressMode = if (syncing) 1 else 0
    val bandModifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(1.dp, OutlineStrong, RoundedCornerShape(18.dp))
            .semantics(mergeDescendants = true) {
                stateDescription = stateLabel
            }
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(
            bandModifier
                .heightIn(min = TASKS_CURRENT_BAND_HEIGHT_DP.dp)
                .animateContentSize(
                    animationSpec = motion.semanticSpec(
                        StemwiseMotionEvent.PANEL_CONTENT_REFLOW,
                        spatial = true
                    )
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = icon,
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    },
                    label = "current-operation-icon"
                ) { visibleIcon ->
                    Icon(
                        visibleIcon,
                        contentDescription = null,
                        tint = animatedAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }
                TaskSummaryCopy(title, detail, expanded = true, operation = true,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
                AnimatedContent(
                    targetState = stateLabel,
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    },
                    label = "current-operation-status"
                ) { visibleStatus ->
                    Text(
                        visibleStatus,
                        color = animatedAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                }
            }
            AnimatedVisibility(
                visible = progressMode != 0,
                enter = fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                    expandVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW)),
                exit = fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) +
                    shrinkVertically(motion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW))
            ) {
                AnimatedContent(
                    targetState = progressMode,
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    },
                    label = "current-operation-progress-kind"
                ) { mode ->
                    when (mode) {
                        1 -> LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = animatedAccent
                        )
                        else -> Unit
                    }
                }
            }
        }
    } else {
        BoxWithConstraints(
            bandModifier.height(TASKS_CURRENT_BAND_HEIGHT_DP.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = TASKS_CURRENT_ICON_START_DP.dp,
                        end = TASKS_CURRENT_TRAILING_END_INSET_DP.dp,
                        top = 18.dp
                    ),
                verticalAlignment = Alignment.Top
            ) {
                AnimatedContent(
                    targetState = icon,
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    },
                    label = "current-operation-icon-compact",
                    modifier = Modifier
                        .padding(top = (TASKS_CURRENT_ICON_TOP_DP - 18f).dp)
                        .size(28.dp)
                ) { visibleIcon ->
                    Icon(
                        visibleIcon,
                        contentDescription = null,
                        tint = animatedAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                TaskSummaryCopy(title, detail, expanded = false, operation = true,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState = stateLabel,
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                    },
                    label = "current-operation-status-compact"
                ) { visibleStatus ->
                    Text(
                        visibleStatus,
                        color = animatedAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.stemwiseMarquee().clearAndSetSemantics { }
                    )
                }
            }
            val progressWidth = maxWidth -
                (TASKS_CURRENT_ICON_START_DP + TASKS_CURRENT_TRAILING_END_INSET_DP).dp
            AnimatedContent(
                targetState = progressMode,
                transitionSpec = {
                    fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                        fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                },
                label = "current-operation-progress-kind-compact"
            ) { mode ->
                when (mode) {
                    1 -> {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .offset(
                                    x = TASKS_CURRENT_ICON_START_DP.dp,
                                    y = TASKS_CURRENT_PROGRESS_TOP_DP.dp
                                )
                                .width(progressWidth)
                                .height(5.dp),
                            color = animatedAccent
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** The same task copy is used at normal and accessibility font sizes; only layout changes. */
@Composable
private fun TaskSummaryCopy(
    title: String, detail: String, expanded: Boolean, modifier: Modifier = Modifier,
    operation: Boolean = false
) {
    val motion = LocalStemwiseMotion.current
    val textModifier = if (expanded) Modifier else Modifier.stemwiseMarquee()
    val titleContent: @Composable (String) -> Unit = { value ->
        Text(value, modifier = textModifier, color = TextPrimary,
            fontSize = if (operation) 16.sp else 14.sp,
            lineHeight = if (operation) 22.sp else 20.sp,
            fontWeight = FontWeight.SemiBold, maxLines = if (expanded) 2 else 1,
            overflow = TextOverflow.Ellipsis)
    }
    val detailContent: @Composable (String) -> Unit = { value ->
        Text(value, modifier = textModifier, color = TextSecondary,
            fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
            maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis)
    }
    Column(modifier) {
        if (operation) AnimatedContent(title, transitionSpec = {
            fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
        }, label = "task-title") { titleContent(it) } else titleContent(title)
        if (!operation && !expanded) Spacer(Modifier.height(2.dp))
        if (operation) AnimatedContent(detail, transitionSpec = {
            fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)) togetherWith
                fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
        }, label = "task-detail") { detailContent(it) } else detailContent(detail)
    }
}
