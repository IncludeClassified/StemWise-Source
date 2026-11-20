package app.stemwise.debug

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import app.stemwise.bridge.BridgeConnectionProfile
import app.stemwise.bridge.BridgeSongPhase
import app.stemwise.bridge.BridgeSongTask
import app.stemwise.bridge.BridgeUiState
import app.stemwise.data.LibraryInventory
import app.stemwise.data.LibraryInventoryAvailability
import app.stemwise.data.LibraryRootState
import app.stemwise.data.LibraryStorageRefreshState
import app.stemwise.data.PlaylistSettings
import app.stemwise.data.PlaylistSyncStatus
import app.stemwise.enrichment.ImportProgressKind
import app.stemwise.enrichment.SharedEnrichmentImportState
import app.stemwise.integration.MvsepConnectionState
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.integration.PlaylistLinks
import app.stemwise.model.JobKind
import app.stemwise.model.JobState
import app.stemwise.model.QueueJob
import app.stemwise.model.QueueJobAction
import app.stemwise.model.QueueOperationPhase
import app.stemwise.ui.QueueScreen
import app.stemwise.ui.SettingsScreen
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.PrimaryPurple

@Composable
internal fun UiStressActivitySurface(mode: UiStressDataMode) {
    val data = remember(mode) { activityFixture(mode) }
    key(mode) {
        QueueScreen(
            onImportEnrichment = {}, playlistSettings = data.playlist, syncStatus = data.sync,
            onSync = { false }, bridgeState = data.bridge, bridgeTasks = data.tasks,
            onRetryBridgeTask = { previewFailure() }, onForgetBridgeTask = { previewFailure() },
            jobs = data.jobs, onRetryJob = {}, sharedImportState = data.imports,
            onCancelBridgeTask = { previewFailure() }, onBridgeTaskDiagnostics = { previewFailure() },
            onOpenAddSong = {}, onReviewDraft = {}, onRetryBridgeWake = { previewFailure() },
            onOpenSettings = {}
        )
    }
}

@Composable
internal fun UiStressSettingsSurface(mode: UiStressDataMode) {
    val data = remember(mode) { settingsFixture(mode) }
    key(mode) {
        SettingsScreen(
            playlistSettings = data.playlist, mvsepState = data.mvsep, bridgeState = data.bridge,
            onSaveBridgeConnection = { previewFailure() }, onEnrollBridge = { previewFailure() },
            onRefreshBridge = { previewFailure() }, onWakeBridgePc = { previewFailure() },
            onPairBridgeAgain = { previewFailure() }, libraryRootState = data.root,
            storageRefreshState = data.storage, onChooseLibrary = {}, onRefreshStorage = {},
            onUpdatePlaylistTools = { previewFailure() }, onSaveMvsepToken = { previewFailure() },
            onRetryMvsep = { previewFailure() }, onRemoveMvsepToken = { previewFailure() }
        )
    }
}

private data class ActivityFixture(
    val playlist: PlaylistSettings,
    val sync: PlaylistSyncStatus,
    val bridge: BridgeUiState,
    val tasks: List<BridgeSongTask>,
    val jobs: List<QueueJob>,
    val imports: SharedEnrichmentImportState
)

private fun activityFixture(mode: UiStressDataMode): ActivityFixture {
    val count = when (mode) {
        UiStressDataMode.EMPTY -> 0
        UiStressDataMode.ONE -> 1
        UiStressDataMode.LARGE -> 1_000
        UiStressDataMode.WORST -> BridgeSongPhase.entries.size
        UiStressDataMode.DEMO -> 2
    }
    val worst = mode == UiStressDataMode.WORST || mode == UiStressDataMode.LARGE
    val tasks = List(count) { index ->
        val phase = if (worst) BridgeSongPhase.entries[index % BridgeSongPhase.entries.size]
            else if (index == 0) BridgeSongPhase.RUNNING else BridgeSongPhase.QUEUED
        val id = (index + 1).toString(16).padStart(32, '0')
        val videoId = (index + 1).toString().padStart(11, '0')
        BridgeSongTask(
            id = if (phase == BridgeSongPhase.OUTBOX_DAMAGED) "damaged:$id.json" else id,
            videoId = videoId,
            title = if (worst) stressTitles[index % stressTitles.size] else "Rehearsal ${index + 1}",
            phase = phase, stage = phase.name.lowercase().replace('_', ' '),
            message = stressFailure.takeIf { worst && phase in setOf(BridgeSongPhase.FAILED,
                BridgeSongPhase.EXPIRED, BridgeSongPhase.OUTBOX_DAMAGED, BridgeSongPhase.OUTBOX_UNKNOWN) },
            createdAtEpochMs = STRESS_CHECKED_AT - index * 1_000L,
            retryable = phase in setOf(BridgeSongPhase.WAITING, BridgeSongPhase.WAITING_PC,
                BridgeSongPhase.RETRYING, BridgeSongPhase.RETIRING, BridgeSongPhase.CANCELLING,
                BridgeSongPhase.ACKNOWLEDGING),
            sourceUrl = if (worst && index % 5 == 0) "" else "https://www.youtube.com/watch?v=$videoId",
            locallyImported = phase in setOf(BridgeSongPhase.IMPORTED, BridgeSongPhase.ACKNOWLEDGING,
                BridgeSongPhase.EXPIRED),
            localImportPending = phase == BridgeSongPhase.WAITING,
            canRetryWake = phase == BridgeSongPhase.WAITING_PC
        )
    }
    val jobs = when (mode) {
        UiStressDataMode.WORST -> JobKind.entries.flatMap { kind -> JobState.entries.map { state ->
            val index = kind.ordinal * JobState.entries.size + state.ordinal
            QueueJob(
                id = "stress-${kind.name}-${state.name}",
                songTitle = stressTitles[index % stressTitles.size].ifBlank { "Song 00000000" },
                artist = if (index % 2 == 0) "Unknown artist" else "Đặng Thị Ngọc Hân · نور الهدى", kind = kind, state = state,
                progress = if (state == JobState.COMPLETE) 1f else 0f,
                detail = if (state == JobState.NEEDS_ATTENTION) stressFailure else stressDetails[index % stressDetails.size],
                accent = if (state == JobState.NEEDS_ATTENTION) Coral else PrimaryPurple,
                action = if (state == JobState.NEEDS_ATTENTION) {
                    if (kind == JobKind.SEPARATION) QueueJobAction.DISCARD_FAILED_PREPARATION else QueueJobAction.VIEW_DETAILS
                } else null,
                phase = QueueOperationPhase.entries[index % QueueOperationPhase.entries.size]
            )
        } }
        UiStressDataMode.DEMO -> listOf(QueueJob("stress-waiting", "Northbound", "StemWise",
            JobKind.SEPARATION, JobState.WAITING, 0f, "Waiting for MVSEP", PrimaryPurple))
        else -> emptyList()
    }
    val imports = when (mode) {
        UiStressDataMode.DEMO -> SharedEnrichmentImportState.Running(completed = 0, total = 1,
            progressKind = ImportProgressKind.FILES, copiedBytes = 0L, totalBytes = 3_000_000L,
            operationId = "ui-stress-import-zero")
        UiStressDataMode.WORST -> SharedEnrichmentImportState.Running(completed = 1, total = 1,
            phase = "Verifying Original — حفل مباشر / Đêm nhạc tại Hà Nội / ライブ録音",
            progressKind = ImportProgressKind.FILES, copiedBytes = 3_000_000L, totalBytes = 3_000_000L,
            operationId = "ui-stress-import-complete")
        else -> SharedEnrichmentImportState.Idle
    }
    return ActivityFixture(playlistFixture(mode),
        if (mode == UiStressDataMode.WORST) PlaylistSyncStatus(error = stressFailure) else PlaylistSyncStatus(),
        if (mode == UiStressDataMode.WORST) bridgeFixture(mode) else BridgeUiState(), tasks, jobs, imports)
}

private data class SettingsFixture(
    val playlist: PlaylistSettings,
    val mvsep: MvsepConnectionState,
    val bridge: BridgeUiState,
    val root: LibraryRootState,
    val storage: LibraryStorageRefreshState
)

private fun settingsFixture(mode: UiStressDataMode): SettingsFixture {
    val empty = mode == UiStressDataMode.EMPTY
    val worst = mode == UiStressDataMode.WORST
    val count = when (mode) {
        UiStressDataMode.EMPTY -> 0
        UiStressDataMode.ONE -> 1
        UiStressDataMode.WORST, UiStressDataMode.LARGE -> 1_000
        UiStressDataMode.DEMO -> 12
    }
    val bytes = count * 536_870_912L
    val inventory = LibraryInventory(
        songsFound = count, originalFilesFound = count, stemFilesFound = count * 6,
        jsonFilesFound = count, artworkFilesFound = count, verifiedSongPackages = count,
        verifiedOriginalFiles = count, verifiedStemFiles = count * 6, verifiedJsonFiles = count,
        pcEnrichedSongManifests = count, verifiedArtworkFiles = count,
        verifiedTotalBytes = bytes, actualLibraryBytes = bytes,
        appPrivateTotalBytes = if (empty) 0L else 1_073_741_824L,
        appDatabaseBytes = if (empty) 0L else 16_777_216L,
        appCacheBytes = if (empty) 0L else 1_056_964_608L,
        lastCheckedEpochMs = if (empty) 0L else STRESS_CHECKED_AT
    )
    val root = if (empty) LibraryRootState() else LibraryRootState(
        treeUri = Uri.parse("content://ui-stress.example.test/tree/" + Uri.encode(
            if (worst) "primary:Music/Session recordings/إيقاعات العالم/Originals/Q3 Session — FINAL (revised) v12 [approved]"
            else "primary:Music/StemWise")),
        libraryId = "8b10f6f6-9939-47b7-b76e-34c713128cba",
        displayName = if (worst) "إيقاعات العالم — Archives de répétition et enregistrements originaux" else "StemWise",
        writable = !worst, errorCode = if (worst) "ROOT_PERMISSION_LOST" else null, epoch = 1L
    )
    val mvsep = when (mode) {
        UiStressDataMode.EMPTY -> MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED)
        UiStressDataMode.WORST -> MvsepConnectionState(MvsepConnectionStatus.ERROR,
            accountName = "bartholomew.fitzgerald+studio-accounts@northwind-industries-holdings.example.test",
            tokenStored = true, error = stressFailure.take(120))
        UiStressDataMode.LARGE -> MvsepConnectionState(MvsepConnectionStatus.CHECKING, tokenStored = true,
            verifying = true)
        else -> MvsepConnectionState(MvsepConnectionStatus.READY,
            accountName = if (mode == UiStressDataMode.ONE) "J" else "Rehearsal studio", tokenStored = true)
    }
    return SettingsFixture(playlistFixture(mode), mvsep, bridgeFixture(mode), root,
        LibraryStorageRefreshState(inventory = inventory,
            availability = when {
                empty -> LibraryInventoryAvailability.RESTORING
                worst -> LibraryInventoryAvailability.UNAVAILABLE
                else -> LibraryInventoryAvailability.VERIFIED
            },
            errorCode = if (worst) "ROOT_PERMISSION_LOST" else null,
            errorMessage = stressFailure.takeIf { worst }))
}

private fun playlistFixture(mode: UiStressDataMode) = PlaylistSettings(
    link = checkNotNull(PlaylistLinks.parse("https://music.youtube.com/playlist?list=PL1234567890")),
    displayName = if (mode == UiStressDataMode.WORST) stressTitles.first() else "Rehearsal playlist",
    lastSyncEpochMs = STRESS_CHECKED_AT.takeUnless { mode == UiStressDataMode.EMPTY },
    lastSyncError = null, configured = mode != UiStressDataMode.EMPTY
)

private fun bridgeFixture(mode: UiStressDataMode): BridgeUiState {
    if (mode == UiStressDataMode.EMPTY) return BridgeUiState()
    val worst = mode == UiStressDataMode.WORST
    val profile = BridgeConnectionProfile(
        host = if (worst) "stemwise-rehearsal-room-workstation-east.example-test-tailnet.ts.net" else "studio-pc.fixture-tailnet.ts.net",
        bridgeId = if (worst) "bridge_example_test_rehearsal_archive_east_".repeat(4).take(128) else "B",
        pcId = if (worst) "pc_example_test_high_resolution_audio_workstation_".repeat(3).take(128) else "P"
    )
    return BridgeUiState(connection = profile, piStatus = "Running", pcStatus = "reachable",
        observedAtElapsedMs = SystemClock.elapsedRealtime(), validForMs = 60_000L,
        error = stressFailure.takeIf { worst },
        connectionIssue = "Could not read https://bridge.example.test/v2/jobs/rehearsal-session-east/output/Original-and-stems.zip".takeIf { worst })
}

private fun <T> previewFailure(): Result<T> = Result.failure(IllegalStateException(
    "UI stress preview only. No service request was made. $stressFailure"))

private const val STRESS_CHECKED_AT = 1_700_000_000_000L
private val stressTitles = listOf(
    "Aleksandra Wiśniewska-Kowalczyk & the Northwind Chamber Orchestra — Complete rehearsal, alternate take and director’s commentary (Warsaw, 2026)",
    "J", "Đêm nhạc tại Hà Nội — Đặng Thị Ngọc Hân / نور الهدى عبد الرحمن / 東京ライブ録音",
    "👩🏽‍💻 Studio session — <b>Live</b> &amp; **Take 2**", "", "  Sam   Lee — Original mix  ",
    "Benachrichtigungseinstellungen — Klangarchiv der internationalen Kammermusikaufnahmen"
)
private val stressDetails = listOf(
    "Originals/Session recordings/Q3 Board Deck — FINAL (revised) v12 [approved by conductor].wav",
    "https://archive.example.test/workspaces/chamber-orchestra/projects/world-tour/session-recordings/Original-2026-11-06-take-12.wav",
    "العربية / 日本語 / Tiếng Việt — Original & Remaining", "", "One line\nA second retained line"
)
private const val stressFailure = "Provider could not read content://documents.example.test/tree/primary%3AMusic%2FRehearsal-recordings%2FOriginal-and-stems-final-take-12.wav. " +
    "The previous verified song is retained. Reference: session-east-2026-11-06-rehearsal-archive-output-Original-and-stems-final-take-12."
