package app.stemwise.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import app.stemwise.StemwiseServices
import app.stemwise.analysis.ImportedSongDetail
import app.stemwise.data.PreparationOwnerDecision
import app.stemwise.data.ColdOpenState
import app.stemwise.data.PlaylistCandidate
import app.stemwise.data.projectPlaylistSyncStatusForNetwork
import app.stemwise.data.ValidatedRootCandidate
import app.stemwise.data.requiresExplicitLibrarySelectionConfirmation
import app.stemwise.data.selectsDifferentLibraryIdentity
import app.stemwise.data.authority
import app.stemwise.model.Song
import app.stemwise.model.JobKind
import app.stemwise.model.JobState
import app.stemwise.model.QueueJobAction
import app.stemwise.model.QueueJob
import app.stemwise.ui.player.surface.PlayerSurfaceHostBridge
import app.stemwise.network.WAITING_FOR_UNMETERED_NETWORK
import app.stemwise.network.AppOwnedNetworkPolicy
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.SurfaceRaised
import app.stemwise.ui.theme.StemwisePalette
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.AuraAnchor
import app.stemwise.ui.design.StemwiseSpacing
import app.stemwise.ui.design.StemwiseTextButton
import app.stemwise.ui.design.stemWiseInteractionAura
import app.stemwise.ui.design.stemwiseAuraSelectableAction
import app.stemwise.ui.design.semanticSpec
import app.stemwise.work.MvsepPreparationWorker
import app.stemwise.work.PlaylistSyncWorker
import app.stemwise.work.ManualPlaylistSyncWorkState
import app.stemwise.work.projectManualPlaylistSyncStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first

private data class OfferedPlaylistCandidate(
    val offerId: Long,
    val candidate: PlaylistCandidate
)

internal enum class StemwiseShellStage {
    STARTUP,
    ROOT_RECOVERY,
    ROOT_RECONNECT,
    ROOT_SELECTION,
    OPENING,
    COLD_OPEN_RECOVERY,
    COLD_OPEN_RECONNECT,
    READY
}

internal fun stemwiseShellStage(
    rootReady: Boolean,
    rootErrorCode: String?,
    rootCandidateErrorPresent: Boolean,
    hasRememberedRoot: Boolean,
    switchingLibrary: Boolean,
    openingLibrary: Boolean,
    coldOpenBlockedCode: String?,
    applicationStartupComplete: Boolean = true
): StemwiseShellStage = when {
    !applicationStartupComplete -> StemwiseShellStage.STARTUP
    coldOpenBlockedCode == app.stemwise.data.LIBRARY_OWNER_RETIREMENT_PENDING ->
        StemwiseShellStage.COLD_OPEN_RECOVERY
    !rootReady && rootErrorCode == "ROOT_CHECKING" && !rootCandidateErrorPresent ->
        StemwiseShellStage.STARTUP
    !rootReady && rootErrorCode == "PLAYLIST_SELECTION_RECOVERY_REQUIRED" ->
        StemwiseShellStage.ROOT_RECOVERY
    !rootReady && libraryRootFailureNeedsReconnect(hasRememberedRoot, rootErrorCode) ->
        StemwiseShellStage.ROOT_RECONNECT
    !rootReady -> StemwiseShellStage.ROOT_SELECTION
    switchingLibrary -> StemwiseShellStage.OPENING
    openingLibrary -> StemwiseShellStage.STARTUP
    coldOpenBlockedCode != null && libraryRootFailureNeedsReconnect(
        hasRememberedRoot,
        coldOpenBlockedCode
    ) -> StemwiseShellStage.COLD_OPEN_RECONNECT
    coldOpenBlockedCode != null -> StemwiseShellStage.COLD_OPEN_RECOVERY
    else -> StemwiseShellStage.READY
}

internal suspend fun removeMvsepConnection(
    removeToken: suspend () -> Result<Unit>,
    cancelPreparation: () -> Unit
): Result<Unit> = removeToken().onSuccess { cancelPreparation() }

/** A cancelled composition cannot restore authority for a preview coroutine that no longer exists. */
@Composable
internal fun rememberPlaylistPreviewBusyOfferId(): MutableState<Long?> =
    remember { mutableStateOf(null) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StemwiseApp(
    services: StemwiseServices,
    applicationStartupComplete: Boolean = true,
    onOpeningContentReady: () -> Unit = {}
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val rootState by services.libraryRootManager.state.collectAsStateWithLifecycle()
    val switchingLibrary by services.librarySessionCoordinator.switching
        .collectAsStateWithLifecycle()
    val coldOpenState by services.coldOpenCoordinator.state.collectAsStateWithLifecycle()
    val openingLibrary = coldOpenState is ColdOpenState.Idle ||
        coldOpenState is ColdOpenState.Recovering
    val rootScope = rememberCoroutineScope()
    val detailedCaptureActive by services.audioPlayback.detailedCaptureActive.collectAsStateWithLifecycle()
    val playbackSnapshotExport = rememberPlaybackSnapshotExport(services.audioPlayback::exportPlaybackSnapshot,
        detailedCaptureActive, services.audioPlayback::setDetailedPlaybackCapture)
    val snackbarHostState = remember { SnackbarHostState() }
    val retryFailure by services.manualTasks.retryFailure.collectAsStateWithLifecycle()
    LaunchedEffect(retryFailure) {
        retryFailure?.let { failure ->
            snackbarHostState.showSnackbar(operationFailureMessage(failure.cause,
                "Could not retry preparation"), duration = SnackbarDuration.Long)
            services.manualTasks.consumeRetryFailure(failure)
        }
    }

    val playerSurfaceBridge = remember { PlayerSurfaceHostBridge() }
    DisposableEffect(services.audioPlayback, playerSurfaceBridge) {
        services.audioPlayback.setSelectionArtworkTargetListener { target ->
            val prioritized = target?.let { song ->
                playerSurfaceBridge.prioritizeSelectionArtwork(
                    songId = song.id,
                    mediaRevision = song.mediaRevision,
                    artworkSource = song.artworkSource
                )
            } == true
            if (!prioritized) playerSurfaceBridge.restoreSelectionArtworkPriority()
        }
        onDispose {
            services.audioPlayback.setSelectionArtworkTargetListener(null)
            playerSurfaceBridge.restoreSelectionArtworkPriority()
        }
    }
    val shellMotion = LocalStemwiseMotion.current
    val motionDensity = LocalDensity.current
    val shellShiftPx = with(motionDensity) { 18.dp.roundToPx() }
    val playerSheetMotion = remember(shellMotion, motionDensity.density) {
        playerSheetMotionSpecs(shellMotion.enabled, with(motionDensity) { .5.dp.toPx() })
    }
    var rootCandidateError by rememberSaveable { mutableStateOf<String?>(null) }
    val rootSelectionRequests = remember { LibraryRootSelectionRequests() }
    var pendingRootCandidate by remember { mutableStateOf<Pair<Long, ValidatedRootCandidate>?>(null) }
    val rootSelectionCommitted: () -> Unit = {
        rootCandidateError = null
        val blocked = services.coldOpenCoordinator.state.value as? ColdOpenState.Blocked
        if (blocked?.code in setOf("ROOT_UNAVAILABLE", "ROOT_PERMISSION_LOST")) {
            services.coldOpenCoordinator.retry()
        }
    }
    // Player visibility is process-local. Activity saved state must never reopen a song or sheet.
    val playerSheetState = remember(motionDensity) {
        SheetState(
            skipPartiallyExpanded = false,
            positionalThreshold = { with(motionDensity) { 56.dp.toPx() } },
            velocityThreshold = { with(motionDensity) { 125.dp.toPx() } },
            initialValue = SheetValue.PartiallyExpanded,
            confirmValueChange = { true },
            skipHiddenState = true
        )
    }
    var expandedLyricsVisible by remember { mutableStateOf(false) }
    var predictivePlayerBack by remember { mutableStateOf(false) }
    var predictivePlayerGesture by remember { mutableStateOf(false) }
    val playerScaffoldState = rememberBottomSheetScaffoldState(playerSheetState)
    val rootPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val request = rootSelectionRequests.begin()
        pendingRootCandidate = null
        if (uri == null) {
            rootCandidateError = if (rootState.treeUri == null) {
                "Folder access was not granted. Choose a folder to continue."
            } else {
                "Folder access was not granted. Reconnect the same folder to continue."
            }
        } else rootScope.launch {
            rootCandidateError = null
            val candidateResult = services.libraryRootManager.validateCandidate(uri)
            if (!rootSelectionRequests.isCurrent(request)) return@launch
            val candidate = candidateResult.getOrNull()
            if (candidate == null) {
                rootCandidateError = candidateResult.exceptionOrNull()?.message
                    ?: "The selected folder could not be used"
            } else if (
                requiresExplicitLibrarySelectionConfirmation(
                    rememberedLibraryId = rootState.libraryId,
                    candidateLibraryId = candidate.libraryId,
                    candidateRequiresNonEmptyFolderConfirmation =
                        candidate.requiresNonEmptyFolderConfirmation
                )
            ) {
                pendingRootCandidate = request to candidate
            } else {
                services.librarySessionCoordinator.switchTo(candidate).onSuccess {
                    rootSelectionCommitted()
                }.onFailure {
                    rootCandidateError = it.message ?: "The selected folder could not be used"
                }
            }
        }
    }
    pendingRootCandidate?.let { (request, candidate) ->
        val changesLibraryIdentity = selectsDifferentLibraryIdentity(
            rememberedLibraryId = rootState.libraryId,
            candidateLibraryId = candidate.libraryId
        )
        StemwiseAlertDialog(
            onDismissRequest = { pendingRootCandidate = null },
            title = {
                Text(
                    if (changesLibraryIdentity) {
                        "Change StemWise Library?"
                    } else if (candidate.requiresNonEmptyFolderConfirmation) {
                        "Use this non-empty folder?"
                    } else {
                        "Change StemWise Library?"
                    }
                )
            },
            text = {
                Text(
                    buildString {
                        append("Folder: ${candidate.treeUri}\n\n")
                        if (changesLibraryIdentity) {
                            append("This selects a different Library on this device. ")
                            append("Files in the current Library stay in its existing folder. ")
                            if (rootState.ready) {
                                append("Playback and preparation will stop before the change. ")
                            }
                        }
                        if (candidate.requiresNonEmptyFolderConfirmation) {
                            append("This folder has ${candidate.existingEntryCount} existing ")
                            append(if (candidate.existingEntryCount == 1) "item. " else "items. ")
                            append("StemWise will add its Library structure without deleting those items. ")
                            append("A dedicated StemWiseLibrary folder is recommended.")
                        } else {
                            append("The selected Library will be rebuilt only from verified video-ID song folders.")
                        }
                    }
                )
            },
            confirmButton = {
                StemwiseTextButton(onClick = {
                    closeAfterStarting(
                        startAction = confirm@{
                            if (!rootSelectionRequests.isCurrent(request) || pendingRootCandidate?.second !== candidate) return@confirm false
                            rootScope.launch {
                                if (!rootSelectionRequests.isCurrent(request)) return@launch
                                services.librarySessionCoordinator.switchTo(candidate).onSuccess {
                                    rootSelectionCommitted()
                                }.onFailure {
                                    rootCandidateError =
                                        it.message ?: "The selected folder could not be used"
                                }
                            }
                            true
                        },
                        afterClose = { pendingRootCandidate = null }
                    )
                }) { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_use_folder), modifier = Modifier.stemwiseMarquee(), maxLines = 1) }
            },
            dismissButton = {
                StemwiseTextButton(onClick = { dismiss() }) {
                    Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_cancel_2), modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                }
            }
        )
    }
    val blockedOpen = coldOpenState as? ColdOpenState.Blocked
    val hasRememberedRoot = rootState.treeUri != null
    val shellStage = stemwiseShellStage(
        rootReady = rootState.ready,
        rootErrorCode = rootState.errorCode,
        rootCandidateErrorPresent = rootCandidateError != null,
        hasRememberedRoot = hasRememberedRoot,
        switchingLibrary = switchingLibrary,
        openingLibrary = openingLibrary,
        coldOpenBlockedCode = blockedOpen?.code,
        applicationStartupComplete = applicationStartupComplete
    )
    LaunchedEffect(shellStage) {
        if (
            shellStage != StemwiseShellStage.READY &&
            shellStage != StemwiseShellStage.STARTUP &&
            shellStage != StemwiseShellStage.OPENING
        ) {
            onOpeningContentReady()
        }
    }
    AnimatedContent(
        targetState = shellStage,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            fadeIn(shellMotion.semanticSpec(StemwiseMotionEvent.SCREEN_PUSH)).togetherWith(
                fadeOut(shellMotion.semanticSpec(StemwiseMotionEvent.SCREEN_POP))
            )
        },
        label = "library-gate-shell"
    ) { visibleStage ->
        when (visibleStage) {
            StemwiseShellStage.STARTUP -> LibraryStartupGate()
            StemwiseShellStage.OPENING -> LibraryOpeningGate("Changing the selected Library")
            StemwiseShellStage.ROOT_RECOVERY -> LibraryRecoveryGate(
                code = rootState.errorCode ?: "PLAYLIST_SELECTION_RECOVERY_REQUIRED",
                message = rootCandidateError.orEmpty(),
                onPrimaryAction = {
                    rootCandidateError = null
                    // Recovery remains bound to the exact persisted root.
                    services.coldOpenCoordinator.retry()
                },
                onNotNow = { (context as? Activity)?.finish() }
            )
            StemwiseShellStage.ROOT_RECONNECT -> LibraryRecoveryGate(
                code = rootState.errorCode ?: "LIBRARY_RECONNECT_REQUIRED",
                message = rootCandidateError.orEmpty(),
                onPrimaryAction = {
                    rootCandidateError = null
                    rootPicker.launch(rootState.treeUri)
                },
                onNotNow = { (context as? Activity)?.finish() }
            )
            StemwiseShellStage.ROOT_SELECTION -> LibraryRootGate(
                errorCode = rootState.errorCode,
                operationalError = rootCandidateError,
                onChoose = {
                    rootCandidateError = null
                    rootPicker.launch(rootState.treeUri)
                }
            )
            StemwiseShellStage.COLD_OPEN_RECOVERY,
            StemwiseShellStage.COLD_OPEN_RECONNECT -> LibraryRecoveryGate(
                code = blockedOpen?.code ?: "LIBRARY_RECOVERY_REQUIRED",
                message = rootCandidateError?.takeIf(String::isNotBlank) ?: blockedOpen?.message.orEmpty(),
                onPrimaryAction = if (blockedOpen?.code == app.stemwise.data.LIBRARY_OWNER_RETIREMENT_PENDING) {
                    null
                } else if (visibleStage == StemwiseShellStage.COLD_OPEN_RECONNECT) {
                    {
                        rootCandidateError = null
                        rootPicker.launch(rootState.treeUri)
                    }
                } else {
                    { services.coldOpenCoordinator.retry() }
                },
                onNotNow = { (context as? Activity)?.finish() }
            )
            StemwiseShellStage.READY -> {
    rootCandidateError?.let { message ->
        StemwiseAlertDialog(
            onDismissRequest = { rootCandidateError = null },
            title = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_could_not_use_this_folder)) },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_your_current_stemwise_library_is_still_connected,
                        message.takeIf { it.isNotBlank() } ?: "Try another folder.")
                )
            },
            confirmButton = {
                StemwiseTextButton(onClick = { dismiss() }) {
                    Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_done), modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                }
            },
            containerColor = SurfaceRaised
        )
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var requestedSettingsSection by remember { mutableStateOf<SystemSurfaceSection?>(null) }
    var showAddSong by remember { mutableStateOf(false) }
    var reviewedDraftId by remember { mutableStateOf<String?>(null) }
    var reviewedBridgeProfile by remember { mutableStateOf<app.stemwise.bridge.BridgeConnectionProfile?>(null) }
    var libraryPartsSongId by rememberSaveable(rootState.libraryId) { mutableStateOf<String?>(null) }
    var libraryPartsAttempt by remember { mutableIntStateOf(0) }
    var libraryPartsSources by remember { mutableStateOf<List<app.stemwise.model.StemSource>>(emptyList()) }
    var libraryPartsSelection by remember { mutableStateOf<List<String>>(emptyList()) }
    var libraryPartsLoaded by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var libraryPartsError by remember { mutableStateOf<String?>(null) }
    var libraryPartsPending by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        if (tab != 0) onOpeningContentReady()
    }
    var openSongId by remember { mutableStateOf<String?>(null) }
    var libraryInfoSongId by rememberSaveable(rootState.libraryId) {
        mutableStateOf<String?>(null)
    }
    var showPreparationRecovery by remember(rootState.libraryId, rootState.epoch) { mutableStateOf(false) }
    var libraryDeletionSongId by rememberSaveable(
        rootState.libraryId,
        rootState.treeUri,
        rootState.epoch
    ) {
        mutableStateOf<String?>(null)
    }
    var libraryStemDeletionSongId by rememberSaveable(
        rootState.libraryId,
        rootState.treeUri,
        rootState.epoch
    ) {
        mutableStateOf<String?>(null)
    }
    var pendingUnknownSeparationRetryJobId by rememberSaveable(rootState.libraryId) {
        mutableStateOf<String?>(null)
    }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    val persistedSongsSnapshot by key(rootState.libraryId, rootState.treeUri, rootState.epoch) {
        remember(services.playlistRepository) {
            services.playlistRepository.songs.map<List<Song>, List<Song>?> { it }
        }.collectAsStateWithLifecycle(initialValue = null)
    }
    val persistedSongs = persistedSongsSnapshot.orEmpty()
    val canonicalSongs by services.externalLibraryRepository.songs
        .collectAsStateWithLifecycle()
    val playlistSettings by services.preferences.playlist.collectAsStateWithLifecycle()
    val syncStatus by services.playlistRepository.syncStatus.collectAsStateWithLifecycle()
    val networkState by services.networkAccess.state.collectAsStateWithLifecycle()
    val manualSyncWork = remember(applicationContext, rootState.libraryId) {
        rootState.libraryId?.let { libraryId ->
            PlaylistSyncWorker.observeManualSync(applicationContext, libraryId)
        } ?: flowOf(ManualPlaylistSyncWorkState.IDLE)
    }
    val manualSyncWorkState by manualSyncWork.collectAsStateWithLifecycle(
        initialValue = ManualPlaylistSyncWorkState.IDLE
    )
    val visibleSyncStatus = remember(syncStatus, networkState, manualSyncWorkState) {
        projectManualPlaylistSyncStatus(
            status = projectPlaylistSyncStatusForNetwork(syncStatus, networkState),
            workState = manualSyncWorkState,
            networkAllowed = AppOwnedNetworkPolicy.allowsNetwork(networkState)
        )
    }
    val mvsepState by services.mvsepAccount.state.collectAsStateWithLifecycle()
    val audioSession by services.audioPlayback.sessionState.collectAsStateWithLifecycle()
    val backgroundWorkState by
        services.backgroundWorkCoordinator.state.collectAsStateWithLifecycle()
    val preparationOwner by services.playlistRepository.preparationOwner
        .collectAsStateWithLifecycle()
    val processingJobs by services.separationRepository.activeJobs
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val externalPackageProblems by services.externalLibraryRepository.problems
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val packageProblems = externalPackageProblems
    val libraryStorageMeasurement by services.externalLibraryRepository.storageRefreshState
        .collectAsStateWithLifecycle()
    val canonicalLibraryRefresh by services.externalLibraryRepository.canonicalRefreshState
        .collectAsStateWithLifecycle()
    val libraryStorageRefresh = libraryStorageMeasurement.copy(
        refreshing = libraryStorageMeasurement.refreshing || canonicalLibraryRefresh.refreshing
    )
    val libraryInventory = libraryStorageRefresh.inventory
    val sharedImportState by services.enrichmentImports.state.collectAsStateWithLifecycle()
    val bridgeState by services.songBridge.state.collectAsStateWithLifecycle()
    val bridgeTasks by services.songBridge.tasks.collectAsStateWithLifecycle()
    val sharedPlaylistState by services.shareIntents.playlist.collectAsStateWithLifecycle()
    val currentPlaylistOfferId = sharedPlaylistState.offerId
    var playlistOfferError by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistOfferErrorId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingPlaylistCandidateState by remember {
        mutableStateOf<OfferedPlaylistCandidate?>(null)
    }
    var playlistPreviewBusyOfferId by rememberPlaylistPreviewBusyOfferId()
    var playlistPreviewAttempt by rememberSaveable { mutableIntStateOf(0) }
    val pendingPlaylistCandidate = pendingPlaylistCandidateState
        ?.takeIf { it.offerId == currentPlaylistOfferId }
    val sharedPlaylistError = playlistOfferError
        ?.takeIf { playlistOfferErrorId == currentPlaylistOfferId }
    val playlistPreviewBusy = playlistPreviewBusyOfferId == currentPlaylistOfferId &&
        currentPlaylistOfferId != null
    val renderedRootHandle = remember(rootState.libraryId, rootState.treeUri, rootState.epoch) {
        services.libraryRootManager.captureHandle()
    }
    val explainPreparationNotifications = rememberPreparationNotifications {
        rootScope.launch {
            snackbarHostState.showSnackbar("Preparation continues with notifications turned off.")
        }
    }
    val tabStateHolder = rememberSaveableStateHolder()
    // Room rows are intentionally not playable during RESTORING. Schema 7 records the active
    // root identity, but historical song rows are not independently epoch-bound; exposing their
    // sourcePath before a direct-root scan could therefore revive a private or stale content URI.
    val orderedCanonicalSongs = remember(persistedSongs, canonicalSongs) {
        orderedLibrarySongs(persistedSongs, canonicalSongs)
    }
    val songs = orderedCanonicalSongs
    val persistedById = remember(persistedSongs, canonicalSongs) {
        // Keep playlist identities that still need preparation, but never reuse a cached source
        // URI after the canonical projection became unavailable or verified empty.
        val ownerHistory = persistedSongs.filter { it.sourcePath.isNullOrBlank() }
        (ownerHistory + canonicalSongs).associateBy { it.id }
    }
    val openSong = openSongId?.let(persistedById::get)
    val activeSession = activeSessionId?.let(persistedById::get)
    val playerModal = activeSession != null && (predictivePlayerBack || playerModalActive(
        hasActiveSession = true,
        currentExpanded = playerSheetState.currentValue == SheetValue.Expanded,
        targetExpanded = playerSheetState.targetValue == SheetValue.Expanded
    ))
    var pendingPlayerOpenId by remember { mutableStateOf<String?>(null) }
    val openPlayer: (String) -> Unit = { songId ->
        openSongId = songId
        pendingPlayerOpenId = songId
    }
    // A library selection can create the sheet and request expansion in the same event. Wait
    // until the selected session has composed, then let Material animate between real anchors.
    // This prevents the first expansion from being lost or appearing fully formed.
    LaunchedEffect(activeSessionId, pendingPlayerOpenId, audioSession.songId) {
        val requestedSongId = pendingPlayerOpenId
        if (requestedSongId != null && activeSessionId == requestedSongId &&
            audioSession.songId == requestedSongId
        ) {
            withFrameNanos { }
            playerSheetState.expandWithStemwiseMotion(playerSheetMotion)
            pendingPlayerOpenId = null
        }
    }
    val closePlayer: () -> Unit = {
        rootScope.launch {
            playerSheetState.collapseWithStemwiseMotion(playerSheetMotion)
        }
    }

    LaunchedEffect(audioSession.songId) {
        if (activeSessionId == null) {
            activeSessionId = audioSession.songId
        }
    }
    val preparationOwnerJob = remember(
        preparationOwner,
        renderedRootHandle,
        persistedById
    ) {
        projectPreparationOwnerQueueJob(
            owner = preparationOwner,
            currentAuthority = renderedRootHandle?.authority(),
            songsById = persistedById
        )
    }
    val exactPreparationJobId = when (preparationOwner) {
        is PreparationOwnerDecision.Separation ->
            (preparationOwner as PreparationOwnerDecision.Separation).jobId
        else -> null
    }
    val suppressAutomaticPreparationRows = suppressUnderlyingAutomaticPreparationRows(
        preparationOwner,
        renderedRootHandle?.authority()
    )
    val durableJobs = remember(
        processingJobs,
        persistedById,
        exactPreparationJobId,
        suppressAutomaticPreparationRows
    ) {
        if (suppressAutomaticPreparationRows) return@remember emptyList()
        processingJobs.mapNotNull { job ->
            projectPersistedPreparationQueueJob(job, persistedById, exactPreparationJobId, suppressAutomaticPreparationRows)
        }
    }
    val packageIssueJobs = remember(packageProblems) {
        packageProblems.map { problem ->
            QueueJob(
                id = "package-problem:${problem.packageId}:${problem.code}",
                songTitle = problem.packageId.takeIf(String::isNotBlank)
                    ?.let { "Package ${it.take(8)}" }
                    ?: "StemWise library",
                artist = "Library validation",
                kind = JobKind.LIBRARY,
                state = JobState.NEEDS_ATTENTION,
                progress = 0f,
                detail = problem.message,
                stableCode = problem.code,
                accent = Coral
            )
        }
    }
    val resourceWaitJob = backgroundWorkState.waitingWork?.let { label ->
        QueueJob(
            id = "background-work-wait",
            songTitle = "Background preparation",
            artist = "Queued operation",
            kind = JobKind.LIBRARY,
            state = JobState.WAITING,
            progress = 0f,
            detail = "Waiting for another background operation · $label",
            accent = PrimaryPurple
        )
    }
    val preparationRecoveryJob = projectPreparationRecoveryQueueJob(coldOpenState, renderedRootHandle?.authority())
    val queueJobs = listOfNotNull(resourceWaitJob, preparationRecoveryJob, preparationOwnerJob) +
        durableJobs +
        packageIssueJobs
    PlayerPredictiveBackHandler(
        enabled = playerModal && !expandedLyricsVisible,
        state = playerSheetState,
        motion = playerSheetMotion,
        onPreviewChanged = { predictivePlayerBack = it; predictivePlayerGesture = it },
        onGestureReleased = { predictivePlayerGesture = false }
    )

    LaunchedEffect(playerSheetState.currentValue, activeSessionId) {
        if (
            playerSheetState.currentValue == SheetValue.Expanded &&
            openSongId == null
        ) {
            openSongId = activeSessionId
        }
    }

    val importNoticeKey = when (val state = sharedImportState) {
        is app.stemwise.enrichment.SharedEnrichmentImportState.AwaitingConfirmation -> state.operationId
        is app.stemwise.enrichment.SharedEnrichmentImportState.Failed -> state.message
        else -> null
    }
    LaunchedEffect(importNoticeKey) {
        if (importNoticeKey != null && snackbarHostState.showSnackbar(
                "Song import needs your attention", actionLabel = "View activity",
                duration = SnackbarDuration.Long) == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            playerSheetState.collapseWithStemwiseMotion(playerSheetMotion)
            openSongId = null
            tab = 1
        }
    }

    LaunchedEffect(currentPlaylistOfferId) {
        if (pendingPlaylistCandidateState?.offerId != currentPlaylistOfferId) {
            pendingPlaylistCandidateState = null
        }
        if (playlistOfferErrorId != currentPlaylistOfferId) {
            playlistOfferError = null
            playlistOfferErrorId = null
        }
    }

    LaunchedEffect(sharedPlaylistState) {
        when (val event = sharedPlaylistState) {
            is app.stemwise.integration.SharedPlaylistState.Syncing -> {
                snackbarHostState.showSnackbar("Syncing playlist…", duration = SnackbarDuration.Short)
            }
            is app.stemwise.integration.SharedPlaylistState.Notice -> {
                snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Long)
                services.shareIntents.dismiss(event.offerId)
            }
            else -> Unit
        }
    }

    // A shared URL is read-only previewed before the confirmation surface is mounted. Every
    // candidate, including a larger or first playlist, must reach the same truthful dialog;
    // none may auto-commit from preview success.
    LaunchedEffect(sharedPlaylistState, playlistPreviewAttempt) {
        val shared = sharedPlaylistState as?
            app.stemwise.integration.SharedPlaylistState.Confirm ?: return@LaunchedEffect
        val offerId = shared.offerId
        if (
            pendingPlaylistCandidateState?.offerId == offerId ||
            playlistPreviewBusyOfferId == offerId ||
            sharedPlaylistError != null ||
            !services.shareIntents.isCurrent(offerId)
        ) return@LaunchedEffect

        playlistPreviewBusyOfferId = offerId
        playlistOfferError = null
        playlistOfferErrorId = offerId
        try {
            services.playlistRepository.previewPlaylist(shared.link)
                .onSuccess { candidate ->
                    if (services.shareIntents.isCurrent(offerId)) {
                        pendingPlaylistCandidateState = OfferedPlaylistCandidate(offerId, candidate)
                    }
                }
                .onFailure { error ->
                    if (services.shareIntents.isCurrent(offerId)) {
                        playlistOfferErrorId = offerId
                        playlistOfferError = error.message
                            ?: "Could not validate this playlist. Nothing was changed."
                    }
                }
        } finally {
            if (playlistPreviewBusyOfferId == offerId) {
                playlistPreviewBusyOfferId = null
            }
        }
    }

    val sharedPlaylistDialogState = sharedPlaylistState
    (sharedPlaylistDialogState as? app.stemwise.integration.SharedPlaylistState.AddSong)?.let { offer ->
        key(offer.offerId) {
            val profile = remember { bridgeState.connection }
            BridgeSongDialog(if (bridgeState.connection == profile) bridgeState else app.stemwise.bridge.BridgeUiState(connection = profile), initialText = offer.originalText,
                onDismiss = { services.shareIntents.dismiss(offer.offerId) },
                onLoadModels = {
                    if (profile == null) Result.failure(IllegalStateException("Connect Bridge in Settings first."))
                    else services.songBridge.loadModelCatalog(profile)
                }, onWakeForOptions = {
                    if (profile == null) Result.failure(IllegalStateException("Connect Bridge in Settings first."))
                    else services.songBridge.wakePc(profile)
                }, onSubmit = { submission ->
                    if (!services.shareIntents.isCurrent(offer.offerId)) {
                        Result.failure(IllegalStateException("Shared song changed"))
                    } else if (profile == null) Result.failure(IllegalStateException("Connect Bridge in Settings first."))
                    else services.songBridge.submitSong(submission, profile)
                })
        }
    }
    if (showAddSong) {
        val profile = reviewedBridgeProfile
        val draftId = reviewedDraftId
        val draft = bridgeTasks.firstOrNull { it.id == draftId }
        key(draftId, profile) {
            BridgeSongDialog(state = if (bridgeState.connection == profile) bridgeState else app.stemwise.bridge.BridgeUiState(connection = profile),
                initialText = draft?.sourceUrl.orEmpty(),
                initialSubmission = draft?.approvedSubmission,
                canReplaceSubmission = draft?.canReplaceReviewedRequest == true,
                title = if (draftId == null) "Send to PC" else "Review song request",
                onDismiss = { showAddSong = false; reviewedDraftId = null },
                onLoadModels = {
                    if (profile == null) Result.failure(IllegalStateException("Connect Bridge in Settings first."))
                    else services.songBridge.loadModelCatalog(profile)
                },
                onWakeForOptions = {
                    if (profile == null) Result.failure(IllegalStateException("Connect Bridge in Settings first."))
                    else services.songBridge.wakePc(profile)
                },
                onSubmit = { submission ->
                    when {
                        profile == null -> Result.failure(IllegalStateException("Connect Bridge in Settings first."))
                        draftId != null -> services.songBridge.authorizeDraft(draftId, submission, profile)
                        else -> services.songBridge.submitSong(submission, profile)
                    }
                })
        }
    }
    val offeredCandidate = pendingPlaylistCandidate
    val candidatePresentation = offeredCandidate?.candidate?.let { candidate ->
        playlistConfirmationPresentation(
            playlistName = candidate.playlistName,
            candidateSongCount = candidate.songCount,
            currentPlaylistCount = candidate.currentPlaylistCount,
            existingLibraryCount = candidate.existingLibraryCount,
            localOnlyCount = candidate.localOnlyCount
        )
    }
    val previewVisible = sharedPlaylistDialogState is
        app.stemwise.integration.SharedPlaylistState.Confirm ||
        sharedPlaylistDialogState is app.stemwise.integration.SharedPlaylistState.Error
    if (candidatePresentation != null || previewVisible) {
        val sharedConfirm = sharedPlaylistDialogState as?
            app.stemwise.integration.SharedPlaylistState.Confirm
        val sharedError = sharedPlaylistDialogState as?
            app.stemwise.integration.SharedPlaylistState.Error
        key(currentPlaylistOfferId) {
            PlaylistOfferDialog(
                presentation = candidatePresentation,
                checking = candidatePresentation == null && playlistPreviewBusy,
                error = if (candidatePresentation == null) {
                    sharedError?.message ?: sharedPlaylistError
                } else null,
                initialEditableText = sharedError?.editableText,
                onDismissRequest = {
                    val offerId = offeredCandidate?.offerId
                        ?: sharedConfirm?.offerId
                        ?: sharedError?.offerId
                    if (offerId != null && services.shareIntents.isCurrent(offerId)) {
                        pendingPlaylistCandidateState = null
                        playlistOfferError = null
                        playlistOfferErrorId = null
                        services.shareIntents.dismiss(offerId)
                    }
                },
                onRetry = sharedConfirm?.let {
                    {
                        playlistOfferError = null
                        playlistOfferErrorId = null
                        playlistPreviewAttempt++
                    }
                },
                onCheckEdited = sharedError?.let { errorState ->
                    { editedText -> services.shareIntents.recheck(errorState.offerId, editedText) }
                },
                keepAndAddEnabled = rootState.ready && !switchingLibrary,
                onKeepAndAdd = commit@{
                    val currentOffer = offeredCandidate ?: return@commit false
                    val candidate = currentOffer.candidate
                    if (
                        !rootState.ready ||
                        switchingLibrary ||
                        pendingPlaylistCandidateState?.offerId != currentOffer.offerId ||
                        !services.shareIntents.isCurrent(currentOffer.offerId)
                    ) return@commit false
                    val authorization = services.shareIntents.claimPlaylistCommit(
                        offerId = currentOffer.offerId,
                        link = candidate.link
                    ) ?: return@commit false
                    services.submitClaimedPlaylistCommit(candidate, authorization)
                },
                onKeepAndAddClosed = {
                    if (pendingPlaylistCandidateState?.offerId == offeredCandidate?.offerId) {
                        pendingPlaylistCandidateState = null
                    }
                    if (playlistOfferErrorId == offeredCandidate?.offerId) {
                        playlistOfferError = null
                        playlistOfferErrorId = null
                    }
                }
            )
        }
    }

    val projectionAvailability by services.externalLibraryRepository.projectionAvailability
        .collectAsStateWithLifecycle()
    val playerOwnerRemoved = playerOwnerRemoved(
        ownerId = activeSessionId,
        visibleOwnerIds = persistedById.keys,
        settled = rootState.ready && !switchingLibrary && persistedSongsSnapshot != null &&
            projectionAvailability == app.stemwise.data.LibraryProjectionAvailability.VERIFIED &&
            !libraryStorageRefresh.refreshing,
        audioOwnerId = audioSession.songId,
        pendingOpenId = pendingPlayerOpenId
    )
    LaunchedEffect(playerOwnerRemoved) {
        if (playerOwnerRemoved) {
            activeSessionId = null
            openSongId = null
            expandedLyricsVisible = false
            predictivePlayerBack = false
            predictivePlayerGesture = false
            playerSheetState.collapseWithStemwiseMotion(playerSheetMotion)
        }
    }
    LaunchedEffect(persistedSongsSnapshot, canonicalSongs, projectionAvailability, rootState.libraryId, rootState.epoch) {
        val ordering = persistedSongsSnapshot ?: return@LaunchedEffect
        val snapshot = services.externalLibraryRepository.playbackSnapshot()
        val ordered = orderedLibrarySongs(ordering, snapshot.songs)
        services.audioPlayback.setPlaybackQueue(ordered, snapshotReady = snapshot.snapshotReady)
    }

    LaunchedEffect(audioSession) {
        val playingSong = audioSession.songId?.let(persistedById::get)
        if (playingSong != null) {
            activeSessionId = playingSong.id
            openSongId = playingSong.id
        }
    }
    val shellDensity = LocalDensity.current
    val navigationBarInsetDp = with(shellDensity) {
        WindowInsets.navigationBars.getBottom(this).toDp().value
    }
    val libraryImeActive = tab == 0 && WindowInsets.ime.getBottom(shellDensity) > 0
    val shellLayout = playerShellLayout(
        hasActiveSession = activeSession != null,
        bottomGestureInsetDp = navigationBarInsetDp
    )
    val shellImeAlpha by animateFloatAsState(
        targetValue = if (libraryImeActive) 0f else 1f,
        animationSpec = shellMotion.semanticSpec(StemwiseMotionEvent.PANEL_CONTENT_REFLOW),
        label = "shell-ime-alpha"
    )
    // The sheet mounts at its full peek height immediately. Reserve the same footprint in
    // this frame so the last Library row cannot sit underneath it while padding animates.
    val pageBottomReserve = if (libraryImeActive) 0.dp else shellLayout.pageBottomReserveDp.dp
    val playerRouteExpansion = remember(activeSession?.id) {
        mutableFloatStateOf(if (playerModal) 1f else 0f)
    }

    PageBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize()) {
        // PageBackground already owns the status-bar and horizontal safe insets. This full-size
        // box leaves the bottom edge to the sheet/player while shell navigation is overlaid.
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = pageBottomReserve)
                        .hideDestinationSemantics(playerModal),
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (fadeIn(shellMotion.semanticSpec(StemwiseMotionEvent.DESTINATION_CHANGE)) +
                            slideInHorizontally(
                                shellMotion.semanticSpec(
                                    StemwiseMotionEvent.DESTINATION_CHANGE,
                                    spatial = true
                                )
                            ) { width ->
                                if (shellMotion.enabled) direction * minOf(width, shellShiftPx) else 0
                            }).togetherWith(
                            fadeOut(shellMotion.semanticSpec(StemwiseMotionEvent.DESTINATION_CHANGE)) +
                                slideOutHorizontally(
                                    shellMotion.semanticSpec(
                                        StemwiseMotionEvent.DESTINATION_CHANGE,
                                        spatial = true
                                    )
                                ) { width ->
                                    if (shellMotion.enabled) -direction * minOf(width, shellShiftPx) else 0
                                }
                        )
                    },
                    label = "main-destination"
                ) { visibleTab ->
                tabStateHolder.SaveableStateProvider(
                    "${rootState.libraryId.orEmpty()}::$visibleTab"
                ) {
                when (visibleTab) {
                    0 -> LibraryScreen(
                        songs = songs,
                        searchBackEnabled = !playerModal,
                        onOpeningArtworkSettled = onOpeningContentReady,
                        currentSongId = activeSessionId,
                        currentSongPlaying = audioSession.songId == activeSessionId &&
                            audioSession.playing,
                        onSetupAction = { requestedSettingsSection = SystemSurfaceSection.CONNECTIONS; tab = 2 },
                        onOpenActivity = {
                            tab = 1
                        },
                        onSongPrepareParts = { selected -> libraryPartsSongId = selected.id; libraryPartsAttempt++ },
                        onSongDelete = { selected -> libraryDeletionSongId = selected.id },
                        onSongDeleteStems = { selected -> libraryStemDeletionSongId = selected.id },
                        songSettingsNeedReset = services.audioPlayback::songSettingsNeedReset,
                        onResetSongSettings = { selected ->
                            rootScope.launch {
                                services.audioPlayback.resetMalformedSongSettings(selected).fold(
                                    onSuccess = { snackbarHostState.showSnackbar("Playback settings reset. Select the song to open it.") },
                                    onFailure = { snackbarHostState.showSnackbar("Could not reset playback settings. Check storage and retry.") })
                            }
                        },
                        onSongClick = { selected ->
                            val accepted = services.audioPlayback.selectSong(
                                selected,
                                autoplay = true,
                                positionMs = 0L
                            )
                            if (accepted) {
                                activeSessionId = selected.id
                                openPlayer(selected.id)
                            }
                            accepted
                        },
                        onSongManage = { selected ->
                            // Information is a library action. It must not select, play,
                            // navigate, or compose the full player as a side effect.
                            libraryInfoSongId = selected.id
                        },
                        playlistName = playlistSettings.displayName,
                        playlistConfigured = playlistSettings.configured,
                        syncing = visibleSyncStatus.running,
                        waitingForNetwork = visibleSyncStatus.waitingForNetwork,
                        syncError = visibleSyncStatus.error ?: playlistSettings.lastSyncError,
                        onPlayAll = {
                            services.audioPlayback.startQueue(
                                songs = songs,
                                shuffle = false,
                                autoplay = true
                            )?.let { first ->
                                activeSessionId = first.id
                                openPlayer(first.id)
                            }
                        },
                        onShuffle = {
                            services.audioPlayback.startQueue(
                                songs = songs,
                                shuffle = true,
                                autoplay = true
                            )?.let { first ->
                                activeSessionId = first.id
                                openPlayer(first.id)
                            }
                        },
                        // The expanded sheet covers this destination. Its ambient aura and
                        // now-playing clocks must not keep invalidating hidden library pixels.
                        ambientMotionEnabled = !playerModal && !expandedLyricsVisible
                    )
                    1 -> QueueScreen(
                        bridgeState = bridgeState,
                        bridgeTasks = bridgeTasks,
                        onOpenAddSong = {
                            reviewedBridgeProfile = bridgeState.connection
                            reviewedDraftId = null
                            showAddSong = true
                        },
                        onReviewDraft = { draft ->
                            reviewedBridgeProfile = draft.connection ?: bridgeState.connection
                            reviewedDraftId = draft.id
                            showAddSong = true
                        },
                        onRetryBridgeWake = services.songBridge::retryWake,
                        onOpenSettings = { section -> requestedSettingsSection = section; tab = 2 },
                        onRetryBridgeTask = services.songBridge::retry,
                        onForgetBridgeTask = services.songBridge::forget,
                        onCancelBridgeTask = services.songBridge::cancel,
                        onBridgeTaskDiagnostics = services.songBridge::taskDiagnostics,
                        onCancelImport = services.enrichmentImports::requestCancel,
                        onImportEnrichment = { uris ->
                            services.enrichmentImports.start(uris)
                        },
                        playlistSettings = playlistSettings,
                        syncStatus = visibleSyncStatus,
                        onSync = services.manualTasks::sync,
                        jobs = queueJobs,
                        onRetryJob = { job ->
                            when (job.kind) {
                                JobKind.SEPARATION -> {
                                    val stored = processingJobs.firstOrNull { it.id == job.id }
                                    if (job.action == QueueJobAction.DISCARD_FAILED_PREPARATION && stored != null) {
                                        val authority = renderedRootHandle?.authority()
                                        rootScope.launch {
                                            val result = authority?.let { expected ->
                                                services.separationRepository.discardFailedPreparation(expected, stored.id)
                                            } ?: Result.failure(IllegalStateException("LIBRARY_CHANGED"))
                                            result.onFailure { error ->
                                                snackbarHostState.showSnackbar(
                                                    operationFailureMessage(error, "Could not discard failed preparation"),
                                                    duration = SnackbarDuration.Long
                                                )
                                            }
                                        }
                                    } else if (job.action == QueueJobAction.CLEAN_AND_RETRY && stored != null) {
                                        rootScope.launch {
                                            captureUserOperation {
                                                check(services.manualTasks.retrySeparationAfterCleanup(job.id) {
                                                    services.separationRepository.cleanScratch()
                                                    services.externalLibraryRepository.reconcile()
                                                }) { "Retry could not be scheduled for the selected library" }
                                            }.onFailure { error ->
                                                snackbarHostState.showSnackbar(
                                                    operationFailureMessage(error, "Could not clean working files and retry"),
                                                    duration = SnackbarDuration.Long
                                                )
                                            }.onSuccess {
                                                explainPreparationNotifications()
                                                snackbarHostState.showSnackbar(
                                                    "Working files cleaned; retry queued",
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                        }
                                    } else if (stored != null && stored.state in setOf("SUBMISSION_STARTED", "SUBMISSION_UNKNOWN") &&
                                        (preparationOwner as? PreparationOwnerDecision.Blocked)?.recoverableJobId == stored.id) {
                                        pendingUnknownSeparationRetryJobId = stored.id
                                    } else {
                                        services.manualTasks.retrySeparation(job.id)
                                        explainPreparationNotifications()
                                    }
                                }
                                JobKind.LIBRARY -> {
                                    if (job.id == preparationRecoveryJob?.id) {
                                        val expected = renderedRootHandle?.authority()
                                        rootScope.launch {
                                            captureUserOperation {
                                                services.coldOpenCoordinator.recheckPreparation(checkNotNull(expected) { "LIBRARY_CHANGED" })
                                            }.onFailure { error ->
                                                snackbarHostState.showSnackbar(operationFailureMessage(error,
                                                    "Preparation recovery is still blocked"), duration = SnackbarDuration.Long)
                                            }
                                        }
                                    } else if ((preparationOwner as? PreparationOwnerDecision.Blocked)?.recoverySongIds?.isNotEmpty() == true) {
                                        showPreparationRecovery = true
                                    }
                                }
                            }
                        },
                        sharedImportState = sharedImportState,
                        onConfirmSharedImport = services.enrichmentImports::confirm,
                        onDiscardSharedImport = services.enrichmentImports::discardPendingImport,
                        onDismissSharedImport = services.enrichmentImports::dismiss
                    )
                    else -> SettingsScreen(
                        initialSection = requestedSettingsSection,
                        bridgeState = bridgeState,
                        onSaveBridgeConnection = services.songBridge::saveConnection,
                        onEnrollBridge = services.songBridge::enroll,
                        onRefreshBridge = services.songBridge::refreshConnection,
                        onWakeBridgePc = { services.songBridge.wakePc() },
                        onPairBridgeAgain = services.songBridge::pairAgain,
                        onUpdatePlaylistTools = { services.engine.checkForUpdates() },
                        playbackSnapshotExport = playbackSnapshotExport,
                        playlistSettings = playlistSettings,
                        mvsepState = mvsepState,
                        libraryRootState = rootState,
                        storageRefreshState = libraryStorageRefresh,
                        onChooseLibrary = { rootPicker.launch(rootState.treeUri) },
                        onRefreshStorage = {
                            services.externalLibraryRepository.requestCanonicalStorageRefresh()
                        },
                        onSaveMvsepToken = { token ->
                            services.mvsepAccount.saveToken(token).onSuccess {
                                MvsepPreparationWorker.enqueue(applicationContext, afterCurrent = true)
                            }
                        },
                        onRetryMvsep = {
                            if (services.mvsepAccount.state.value.localLoadFailed) {
                                services.mvsepAccount.reloadSavedToken()
                            } else services.mvsepAccount.validate().onSuccess {
                                MvsepPreparationWorker.enqueue(applicationContext, afterCurrent = true)
                            }
                        },
                        onRemoveMvsepToken = {
                            removeMvsepConnection(
                                removeToken = services.mvsepAccount::removeToken,
                                cancelPreparation = {
                                    MvsepPreparationWorker.cancel(applicationContext)
                                }
                            )
                        }
                    )
                }
                }
                }
                activeSession?.let { sessionSong ->
                    BottomSheetScaffold(
                        scaffoldState = playerScaffoldState,
                        modifier = Modifier.fillMaxSize(),
                        // Keep one stable anchored-drag owner for the complete route. Switching
                        // this modifier off from current/target anchor state disabled FULL -> MINI
                        // entirely and cancelled MINI -> FULL as soon as the target flipped.
                        // AnchoredDraggable clamps an upward-only pull at FULL; a downward pull can
                        // now continuously resize the same native artwork back to the mini anchor.
                        sheetSwipeEnabled = !predictivePlayerGesture && playerSheetSwipeEnabled(
                            hasActiveSession = true,
                            libraryImeActive = libraryImeActive
                        ) && !expandedLyricsVisible,
                        sheetPeekHeight = shellLayout.sheetPeekHeightDp.dp,
                        sheetShape = RectangleShape,
                        // PlayerScreen paints the accepted rounded compact body inside this
                        // transparent 68dp route envelope; the sheet must not add a full-width
                        // rectangular plate behind its 64dp visible surface.
                        sheetContainerColor = Color.Transparent,
                        sheetTonalElevation = 0.dp,
                        sheetShadowElevation = 0.dp,
                        sheetDragHandle = null,
                        containerColor = Color.Transparent,
                        contentColor = TextPrimary,
                        snackbarHost = { },
                        sheetContent = {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                // Material installs its defaults during StandardBottomSheet
                                // composition. Reinstall after that SideEffect so drag settling
                                // cannot silently drift from the accepted shared-element spring.
                                SideEffect {
                                    playerSheetMotion.install(playerSheetState)
                                }
                                val density = LocalDensity.current
                                val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                                val peekPx = with(density) {
                                    shellLayout.sheetPeekHeightDp.dp.toPx()
                                }
                                val collapsedOffset = (heightPx - peekPx).coerceAtLeast(1f)
                                val sheetOffset = runCatching {
                                    playerSheetState.requireOffset()
                                }.getOrElse { collapsedOffset }
                                val expansion =
                                    (1f - sheetOffset / collapsedOffset).coerceIn(0f, 1f)
                                SideEffect {
                                    playerRouteExpansion.floatValue = expansion
                                }
                                // The actual audio transition wins immediately, even before the
                                // synchronization effect above receives its next composition.
                                val sheetSong = audioSession.songId
                                    ?.let(persistedById::get)
                                    ?: openSong
                                    ?: sessionSong
                                PlayerScreen(
                                    song = sheetSong,
                                    onBack = closePlayer,
                                    library = songs,
                                    onSongChanged = { changed ->
                                        activeSessionId = changed.id
                                        openSongId = changed.id
                                    },
                                    audioPlayback = services.audioPlayback,
                                    surfaceBridge = playerSurfaceBridge,
                                    separationRepository = services.separationRepository,
                                    analysisRepository = services.analysisRepository,
                                    stemJobState = processingJobs.lastOrNull {
                                        it.songId == sheetSong.id &&
                                            it.kind == app.stemwise.integration.SeparationRepository.KIND_SOURCES &&
                                            it.libraryId == renderedRootHandle?.libraryId
                                    }?.state,
                                    onOpenTasks = {
                                        closePlayer()
                                        tab = 1
                                    },
                                    onDeleteSong = { sourceSong ->
                                        val handle = renderedRootHandle
                                            ?: return@PlayerScreen Result.failure(
                                                IllegalStateException("LIBRARY_CHANGED")
                                            )
                                        rootScope.launch {
                                            playerSheetState.collapseWithStemwiseMotion(
                                                playerSheetMotion
                                            )
                                        }
                                        services.audioPlayback.withPermanentSongDeletion(handle.authority(), sourceSong.id) { admitted ->
                                            services.permanentDeletion.deletePermanently(handle, sourceSong.id,
                                                onExactRootAdmitted = admitted)
                                        }
                                            .onSuccess {
                                                if (activeSessionId == sourceSong.id) {
                                                    activeSessionId = null
                                                }
                                                if (openSongId == sourceSong.id) {
                                                    openSongId = null
                                                }
                                            }
                                    },
                                    onRequestStems = { sourceSong, selectedIds ->
                                        services.manualTasks.requestSources(sourceSong.id, selectedIds).also { result ->
                                            if (result.isSuccess) explainPreparationNotifications()
                                        }
                                    },
                                    onLoadStemSources = {
                                        services.separationRepository.availableSources()
                                    },
                                    onDeleteStems = { sourceSong ->
                                        val handle = renderedRootHandle
                                            ?: return@PlayerScreen Result.failure(
                                                IllegalStateException("LIBRARY_CHANGED")
                                            )
                                        services.audioPlayback.deletePartsPreservingPlayback(
                                            handle.authority(), sourceSong.id
                                        ) { quiesce ->
                                            services.permanentDeletion.deleteStems(handle, sourceSong.id, quiesce)
                                        }
                                    },
                                    onShuffleChange = {},
                                    onMessage = { message ->
                                        rootScope.launch {
                                            snackbarHostState.showSnackbar(
                                                message,
                                                duration = SnackbarDuration.Short
                                            )
                                        }
                                    },
                                    onExpandedLyricsVisibilityChanged = {
                                        expandedLyricsVisible = it
                                    },
                                    expansionProgressState = playerRouteExpansion,
                                    onExpand = {
                                        openSongId = sheetSong.id
                                        rootScope.launch {
                                            playerSheetState.expandWithStemwiseMotion(
                                                playerSheetMotion
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = shellImeAlpha }
                                        .then(
                                            if (libraryImeActive) {
                                                Modifier
                                                    .clearAndSetSemantics { }
                                                    // stemwise-aura-exclusion: IME input shield;
                                                    // it consumes touches but owns no action.
                                                    .pointerInput(Unit) {
                                                        awaitPointerEventScope {
                                                            while (true) {
                                                                awaitPointerEvent(
                                                                    PointerEventPass.Initial
                                                                ).changes.forEach { it.consume() }
                                                            }
                                                        }
                                                    }
                                            } else {
                                                Modifier
                                            }
                                        )
                                )
                            }
                        }
                    ) { }
                }
            }
            // Keep the shell geometry fixed throughout player expansion/collapse. Removing
            // this row changed the sheet's measured height mid-flight, which made artwork jump;
            // hide pixels/input in place so the expanded player never exposes shell actions.
            SignalNavigation(
                selected = tab,
                // Read the per-frame route scalar only from the RenderNode. Mirroring it through
                // root composition made every sheet frame recompose the entire app and Library.
                visibleAlpha = {
                    playerNavigationAlpha(
                        hasActiveSession = activeSession != null,
                        expansionProgress = playerRouteExpansion.floatValue
                    ) * shellImeAlpha
                },
                interactive = !playerModal && !libraryImeActive && shellImeAlpha >= .999f,
                bottomInsetDp = navigationBarInsetDp,
                modifier = Modifier.align(Alignment.BottomCenter),
                onSelected = { tab = it }
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = StemwiseSpacing.md,
                    end = StemwiseSpacing.md,
                    bottom = playerSnackbarBottomOffsetDp(
                        playerModal = playerModal,
                        hasActiveSession = activeSession != null,
                        bottomGestureInsetDp = navigationBarInsetDp
                    ).dp
                )
        )
        }
    }

    pendingUnknownSeparationRetryJobId?.let { jobId ->
        val expectedRoot = remember(jobId) { renderedRootHandle?.authority() }
        UnknownSeparationRecoveryDialog(
            jobId = jobId,
            expectedRoot = expectedRoot,
            scope = rootScope,
            onDismiss = { pendingUnknownSeparationRetryJobId = null },
            onReview = { remoteJobId ->
                services.manualTasks.reviewSeparationSubmission(jobId, remoteJobId, checkNotNull(expectedRoot))
            },
            onRecover = { services.manualTasks.recoverSeparationSubmission(it, sourceConfirmed = true) },
            onRecovered = {
                pendingUnknownSeparationRetryJobId = null
                explainPreparationNotifications()
            }
        )
    }

    if (showPreparationRecovery) {
        PreparationRecoveryDialog(
            damagedSongIds = (preparationOwner as? PreparationOwnerDecision.Blocked)?.recoverySongIds.orEmpty(),
            onDismiss = { showPreparationRecovery = false },
            onRetry = {
                showPreparationRecovery = false
                services.coldOpenCoordinator.retry()
            }
        )
    }
    val libraryPartsSong = libraryPartsSongId?.let(persistedById::get)
    LaunchedEffect(libraryPartsSong?.id, libraryPartsAttempt) {
        val target = libraryPartsSong ?: return@LaunchedEffect
        libraryPartsLoaded = null
        libraryPartsError = null
        libraryPartsSelection = emptyList()
        captureUserOperation {
            val current = services.separationRepository.observeActiveStems(target.id).first()
            libraryPartsSelection = if (current.isEmpty()) app.stemwise.model.StemSourceCatalogue.defaultSelection
                else current.mapNotNull { it.normalizedCapability }.filterNot { it == "remaining" }.distinct()
            services.separationRepository.availableSources()
        }.onSuccess { libraryPartsSources = it }
            .onFailure { libraryPartsError = operationFailureMessage(it, "Could not load song parts") }
        libraryPartsLoaded = target.id to libraryPartsAttempt
    }
    libraryPartsSong?.let { target ->
        SourceSelectionDialog(songId = target.id, songTitle = target.title,
            initialSelection = libraryPartsSelection,
            availableSources = libraryPartsSources,
            loading = libraryPartsLoaded != (target.id to libraryPartsAttempt), error = libraryPartsError,
            onRetry = { libraryPartsAttempt++ }, onDismiss = { libraryPartsSongId = null },
            onConfirm = { selected ->
                if (libraryPartsPending || persistedById[target.id] != target) false else {
                    libraryPartsPending = true
                    rootScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            services.manualTasks.requestSources(target.id, selected)
                                .onSuccess {
                                    explainPreparationNotifications()
                                    snackbarHostState.showSnackbar("Preparing parts. View progress in Activity.")
                                }
                                .onFailure { snackbarHostState.showSnackbar(operationFailureMessage(it, "Could not prepare parts")) }
                        } finally { libraryPartsPending = false }
                    }
                    true
                }
            })
    }
    libraryInfoSongId?.let(persistedById::get)?.let { infoSong ->
        LibrarySongInfoDialog(
            song = infoSong,
            loadDetail = { owner -> services.analysisRepository.songDetail(owner.songId, owner.mediaRevision, owner.sourceHash.orEmpty()) },
            loadSummary = { owner -> services.analysisRepository.summary(owner.songId, owner.mediaRevision, owner.sourceHash.orEmpty()) },
            onDismiss = { libraryInfoSongId = null },
            onCopyVideoId = {
                copyYouTubeVideoId(context, infoSong)
            },
            onDeleteSong = {
                libraryInfoSongId = null
                libraryDeletionSongId = infoSong.id
            },
            onDeleteStems = {
                libraryInfoSongId = null
                libraryStemDeletionSongId = infoSong.id
            }
        )
    }

    libraryStemDeletionSongId?.let(persistedById::get)?.let { deletionSong ->
        StemwiseAlertDialog(
            onDismissRequest = { libraryStemDeletionSongId = null },
            dismissEnabled = false,
            title = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_delete_this_song_s_stems)) },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_this_removes_only_the_prepared_song_parts)
                )
            },
            confirmButton = {
                StemwisePermanentDeleteActions(
                    onCancel = { dismiss() },
                    onDelete = {
                        closeAfterStarting(
                            startAction = {
                                val deletionHandle = renderedRootHandle
                                rootScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    val deletion = deletionHandle?.let { handle ->
                                        services.audioPlayback.deletePartsPreservingPlayback(
                                            handle.authority(), deletionSong.id
                                        ) { quiesce ->
                                            services.permanentDeletion.deleteStems(handle, deletionSong.id, quiesce)
                                        }
                                    } ?: Result.failure(
                                        IllegalStateException("LIBRARY_CHANGED")
                                    )
                                    deletion
                                        .onSuccess { outcome ->
                                            snackbarHostState.showSnackbar(
                                                stemDeletionSuccessMessage(outcome),
                                                duration = if (outcome.projectionRefreshPending) {
                                                    SnackbarDuration.Long
                                                } else {
                                                    SnackbarDuration.Short
                                                }
                                            )
                                        }
                                        .onFailure {
                                            snackbarHostState.showSnackbar(
                                                it.message ?: "Could not delete the stems",
                                                duration = SnackbarDuration.Long
                                            )
                                        }
                                }
                                true
                            },
                            afterClose = { libraryStemDeletionSongId = null }
                        )
                    }
                )
            },
            containerColor = SurfaceRaised
        )
    }

    libraryDeletionSongId?.let(persistedById::get)?.let { deletionSong ->
        StemwiseAlertDialog(
            onDismissRequest = { libraryDeletionSongId = null },
            title = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_delete_this_song_permanently)) },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_this_permanently_removes_the_original_all_stems)
                )
            },
            confirmButton = {
                StemwisePermanentDeleteActions(
                    onCancel = { dismiss() },
                    onDelete = {
                        closeAfterStarting(
                            startAction = {
                                val deletionHandle = renderedRootHandle
                                rootScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    val deletion = deletionHandle?.let { handle ->
                                        services.audioPlayback.withPermanentSongDeletion(handle.authority(), deletionSong.id) { admitted ->
                                            services.permanentDeletion.deletePermanently(handle, deletionSong.id,
                                                onExactRootAdmitted = admitted)
                                        }
                                    } ?: Result.failure(IllegalStateException("LIBRARY_CHANGED"))
                                    deletion
                                        .onSuccess { outcome ->
                                            if (activeSessionId == deletionSong.id) {
                                                activeSessionId = null
                                            }
                                            if (openSongId == deletionSong.id) {
                                                openSongId = null
                                            }
                                            snackbarHostState.showSnackbar(
                                                permanentDeletionSuccessMessage(outcome),
                                                duration = if (outcome.projectionRefreshPending) {
                                                    SnackbarDuration.Long
                                                } else {
                                                    SnackbarDuration.Short
                                                }
                                            )
                                        }
                                        .onFailure {
                                            snackbarHostState.showSnackbar(
                                                it.message ?: "Could not delete these files",
                                                duration = SnackbarDuration.Long
                                            )
                                        }
                                }
                                true
                            },
                            afterClose = {
                                libraryDeletionSongId = null
                                if (
                                    activeSessionId == deletionSong.id ||
                                    openSongId == deletionSong.id
                                ) {
                                    rootScope.launch {
                                        playerSheetState.collapseWithStemwiseMotion(
                                            playerSheetMotion
                                        )
                                    }
                                }
                            }
                        )
                    }
                )
            },
            containerColor = SurfaceRaised
        )
    }
            }
        }
    }
}

internal enum class SongInfoReadPhase { LOADING, READY, FAILED }

internal data class LibrarySongHydration(
    val detail: ImportedSongDetail? = null,
    val analysisSummary: app.stemwise.analysis.ImportedAnalysisSummary? = null,
    val detailPhase: SongInfoReadPhase = SongInfoReadPhase.LOADING,
    val summaryPhase: SongInfoReadPhase = SongInfoReadPhase.LOADING
)

@Composable
internal fun LibrarySongInfoDialog(
    song: Song,
    loadDetail: suspend (SongPresentationOwner) -> ImportedSongDetail,
    loadSummary: suspend (SongPresentationOwner) -> app.stemwise.analysis.ImportedAnalysisSummary?,
    onDismiss: () -> Unit,
    onCopyVideoId: () -> Unit,
    onDeleteSong: () -> Unit,
    onDeleteStems: () -> Unit
) {
    val owner = SongPresentationOwner.from(song)
    var hydration by remember(owner) { mutableStateOf(LibrarySongHydration()) }
    var detailRetry by remember(owner) { mutableIntStateOf(0) }
    var summaryRetry by remember(owner) { mutableIntStateOf(0) }
    LaunchedEffect(owner, detailRetry) {
        hydration = hydration.copy(detailPhase = SongInfoReadPhase.LOADING)
        try {
            val detail = loadDetail(owner)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            hydration = hydration.copy(detail = detail, detailPhase = SongInfoReadPhase.READY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            hydration = hydration.copy(detailPhase = SongInfoReadPhase.FAILED)
        }
    }
    LaunchedEffect(owner, summaryRetry) {
        hydration = hydration.copy(summaryPhase = SongInfoReadPhase.LOADING)
        try {
            val summary = loadSummary(owner)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            hydration = hydration.copy(analysisSummary = summary, summaryPhase = SongInfoReadPhase.READY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            hydration = hydration.copy(summaryPhase = SongInfoReadPhase.FAILED)
        }
    }
    val hydrated = remember(song, hydration.detail) { hydration.detail?.let(song::withImportedSongDetail) ?: song }
    SongInfoDialog(
        song = hydrated,
        analysisSummary = hydration.analysisSummary,
        hydration = hydration,
        onRetryDetail = { detailRetry++ },
        onRetrySummary = { summaryRetry++ },
        onDismiss = onDismiss,
        onCopyVideoId = onCopyVideoId,
        onDeleteSong = onDeleteSong,
        onDeleteStems = onDeleteStems
    )
}

internal fun Modifier.hideDestinationSemantics(playerModal: Boolean): Modifier = then(
    if (playerModal) Modifier.clearAndSetSemantics { } else Modifier
)

internal fun orderedLibrarySongs(persisted: List<Song>, canonical: List<Song>): List<Song> {
    val byId = canonical.associateBy { it.id }
    val persistedIds = persisted.mapTo(hashSetOf()) { it.id }
    return persisted.mapNotNull { byId[it.id] } + canonical.filterNot { it.id in persistedIds }
}

@Composable
internal fun rememberPreparationNotifications(onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current
    val denied = rememberUpdatedState(onDenied)
    var showNotificationExplanation by rememberSaveable { mutableStateOf(false) }
    var notificationExplanationShown by rememberSaveable { mutableStateOf(false) }
    val request: () -> Unit = {
        if (!notificationExplanationShown && !hasNotificationPermission(context)) {
            notificationExplanationShown = true
            showNotificationExplanation = true
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        showNotificationExplanation = false
        if (!granted) denied.value()
    }
    if (showNotificationExplanation) {
        StemwiseAlertDialog(
            onDismissRequest = {},
            dismissEnabled = false,
            title = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_show_preparation_progress)) },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_android_uses_a_notification_while_stemwise_prepares)
                )
            },
            confirmButton = {
                StemwiseTextButton(onClick = {
                    closeThen {
                        showNotificationExplanation = false
                        if (
                            activity != null &&
                            !activity.isFinishing &&
                            !activity.isDestroyed
                        ) {
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                    }
                }) { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_continue), modifier = Modifier.stemwiseMarquee(), maxLines = 1) }
            },
            containerColor = SurfaceRaised
        )
    }
    return request
}

private fun hasNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

@Composable
internal fun SignalNavigation(
    selected: Int,
    visibleAlpha: () -> Float,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    bottomInsetDp: Float = 0f,
    onSelected: (Int) -> Unit
) {
    val motion = LocalStemwiseMotion.current
    val contentLayoutDirection = LocalLayoutDirection.current
    val items = listOf(
        StemwiseIcons.Library to "Library",
        StemwiseIcons.Tasks to "Activity",
        StemwiseIcons.System to "Settings"
    )
    // The app fixes the physical destination order to Library, Tasks, System in every locale. Keep
    // each label under the application's direction so its own glyph shaping remains correct.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .height((SIGNAL_NAVIGATION_HEIGHT_DP + bottomInsetDp.coerceAtLeast(0f)).dp)
                .graphicsLayer { alpha = visibleAlpha().coerceIn(0f, 1f) }
                .background(StemwisePalette.navigationSurface)
                .drawBehind {
                    drawRect(
                        color = StemwisePalette.navigationDivider,
                        size = Size(size.width, 1.dp.toPx())
                    )
                }
                .then(
                    if (interactive) Modifier.selectableGroup()
                    else Modifier.clearAndSetSemantics { }
                )
        ) {
            val navigationGeometry = signalNavigationGeometry(maxWidth.value)
            Row(Modifier.fillMaxWidth().height(SIGNAL_NAVIGATION_ITEM_HEIGHT_DP.dp)) {
                items.forEachIndexed { index, item ->
                    SignalNavigationItem(
                        icon = item.first,
                        label = item.second,
                        selected = selected == index,
                        interactive = interactive,
                        geometry = navigationGeometry,
                        contentLayoutDirection = contentLayoutDirection,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelected(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalNavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    interactive: Boolean,
    geometry: SignalNavigationGeometry,
    contentLayoutDirection: LayoutDirection,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val motion = LocalStemwiseMotion.current
    val selectionInteractionSource = remember { MutableInteractionSource() }
    val iconForeground by animateColorAsState(
        if (selected) PrimaryPurple else StemwisePalette.navigationInactiveIcon,
        motion.semanticSpec(StemwiseMotionEvent.DESTINATION_CHANGE),
        label = "navigation-icon-foreground"
    )
    val labelForeground by animateColorAsState(
        if (selected) StemwisePalette.navigationActiveLabel else TextSecondary,
        motion.semanticSpec(StemwiseMotionEvent.DESTINATION_CHANGE),
        label = "navigation-label-foreground"
    )
    Layout(
        content = {
            Box(
                Modifier
                    .size(24.dp)
                    .stemWiseInteractionAura(
                        enabled = interactive,
                        selected = selected,
                        interactionSource = selectionInteractionSource,
                        anchor = AuraAnchor.GlyphLocalized,
                        reducedMotion = !motion.enabled,
                        paintTransientPress = false,
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = iconForeground
                )
            }
            CompositionLocalProvider(LocalLayoutDirection provides contentLayoutDirection) {
                Text(
                    label,
                    color = labelForeground,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        },
        modifier = modifier
            .height(geometry.itemHeightDp.dp)
            .then(
                if (interactive) {
                    Modifier
                        .stemWiseInteractionAura(
                            enabled = true,
                            selected = false,
                            interactionSource = selectionInteractionSource,
                            anchor = AuraAnchor.VisibleControl,
                            reducedMotion = !motion.enabled,
                        )
                        .stemwiseAuraSelectableAction(
                            selected = selected,
                            interactionSource = selectionInteractionSource,
                            enabled = true,
                            role = Role.Tab,
                            onClick = onClick
                        )
                } else {
                    // A disabled selectable still participates in pointer hit-testing. The row
                    // stays measured above FULL, but invisible navigation must not block player.
                    Modifier
                }
            )
    ) { measurables, constraints ->
        val iconSizePx = 24.dp.roundToPx()
        val itemHeightPx = geometry.itemHeightDp.dp.roundToPx()
        val iconTopPx = geometry.iconTopDp.dp.roundToPx()
        val labelBaselinePx = geometry.labelBaselineDp.dp.roundToPx()
        val iconPlaceable = measurables[0].measure(Constraints.fixed(iconSizePx, iconSizePx))
        val labelPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = itemHeightPx)
        )
        val labelBaseline = labelPlaceable[FirstBaseline]
            .takeUnless { it == AlignmentLine.Unspecified }
            ?: labelPlaceable.height
        layout(constraints.maxWidth, itemHeightPx) {
            iconPlaceable.place(
                x = (constraints.maxWidth - iconPlaceable.width) / 2,
                y = iconTopPx
            )
            labelPlaceable.place(
                x = (constraints.maxWidth - labelPlaceable.width) / 2,
                y = labelBaselinePx - labelBaseline
            )
        }
    }
}
