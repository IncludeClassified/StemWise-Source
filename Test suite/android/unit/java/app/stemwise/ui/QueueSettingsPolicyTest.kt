package app.stemwise.ui

import app.stemwise.data.LibraryInventory
import app.stemwise.data.PlaylistSyncStatus
import app.stemwise.model.JobKind
import app.stemwise.model.JobState
import app.stemwise.model.QueueOperationPhase
import app.stemwise.model.QueueJobAction
import app.stemwise.model.QueueJob
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.ui.theme.Cyan
import app.stemwise.ui.theme.Lime
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.Sky
import app.stemwise.ui.theme.TextSecondary
import androidx.compose.ui.graphics.Color
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSettingsPolicyTest {
    @Test fun `activity summaries follow the same authoritative section projection`() {
        fun task(phase: app.stemwise.bridge.BridgeSongPhase) = app.stemwise.bridge.BridgeSongTask(
            phase.name, "abcdefghijk", "Song", phase, "")
        val idle = app.stemwise.enrichment.SharedEnrichmentImportState.Idle
        assertEquals("2 need attention · 3 in progress · 1 recent", tasksSummary(emptyList(),
            listOf(task(app.stemwise.bridge.BridgeSongPhase.RUNNING), task(app.stemwise.bridge.BridgeSongPhase.DRAFT),
                task(app.stemwise.bridge.BridgeSongPhase.FAILED), task(app.stemwise.bridge.BridgeSongPhase.IMPORTED)),
            PlaylistSyncStatus(running = true), app.stemwise.enrichment.SharedEnrichmentImportState.Running()))
        assertEquals("1 recent", tasksSummary(emptyList(), listOf(task(app.stemwise.bridge.BridgeSongPhase.IMPORTED)), PlaylistSyncStatus(), idle))
        assertEquals("1 in progress", tasksSummary(listOf(queueJob(JobKind.SEPARATION)), emptyList(), PlaylistSyncStatus(), idle))
        assertEquals(ActivitySection.NEEDS_ATTENTION, activitySection(true, true, true))
        assertEquals(ActivitySection.IN_PROGRESS, activitySection(false, true, true))
        assertEquals(ActivitySection.WAITING, activitySection(false, false, true))
        assertEquals(ActivitySection.RECENT, activitySection(false, false, false))
        assertEquals(ActivitySection.NEEDS_ATTENTION, bridgeActivitySection(task(app.stemwise.bridge.BridgeSongPhase.REVIEW_REQUIRED)))
        assertEquals(ActivitySection.NEEDS_ATTENTION, bridgeActivitySection(task(app.stemwise.bridge.BridgeSongPhase.WAITING)))
        assertEquals(ActivitySection.WAITING, bridgeActivitySection(task(app.stemwise.bridge.BridgeSongPhase.WAITING_PC)))
        assertEquals(ActivitySection.WAITING, bridgeActivitySection(task(app.stemwise.bridge.BridgeSongPhase.PREPARING)))
        assertEquals(ActivitySection.NEEDS_ATTENTION, sharedImportActivitySection(
            app.stemwise.enrichment.SharedEnrichmentImportState.AwaitingConfirmation(emptyList())))
        assertEquals(ActivitySection.NEEDS_ATTENTION, sharedImportActivitySection(
            app.stemwise.enrichment.SharedEnrichmentImportState.Running(retirementPending = true)))
    }

    @Test
    fun taskPresentationContainsOnlyRetainedJobKinds() {
        assertEquals(listOf(JobKind.SEPARATION, JobKind.LIBRARY), JobKind.entries)
    }

    @Test
    fun `sync activity includes queued and running work`() {
        assertFalse(playlistSyncActive(PlaylistSyncStatus()))
        assertTrue(playlistSyncActive(PlaylistSyncStatus(running = true)))
        assertTrue(playlistSyncActive(PlaylistSyncStatus(queued = true)))
    }

    @Test
    fun `tasks dashboard matches the accepted 432dp geometry`() {
        val policy = tasksActionDashboardPolicy(availableWidthDp = 392f, fontScale = 1f)
        assertFalse(policy.stacked)
        assertEquals(64f, policy.tileHeightDp)
        assertEquals(12f, policy.gapDp)
    }

    @Test
    fun `task label configuration retains its accepted font size`() {
        assertEquals(11.5f, TASKS_ACTION_LABEL_FONT_SIZE_SP, 0f)
    }

    @Test
    fun `current and queued task internals match accepted 432dp columns`() {
        assertEquals(8f, TASKS_QUEUED_ICON_START_DP, 0f)
        assertEquals(48f, TASKS_QUEUED_TEXT_START_DP, 0f)
        assertEquals(8f, TASKS_QUEUED_END_INSET_DP, 0f)
        assertEquals(108f, TASKS_CURRENT_BAND_HEIGHT_DP, 0f)
        assertEquals(84f, TASKS_CURRENT_PROGRESS_TOP_DP, 0f)
        assertEquals(74f, TASKS_QUEUED_ROW_HEIGHT_DP, 0f)
        assertEquals(62f, TASKS_QUEUED_DIVIDER_TOP_DP, 0f)
    }

    @Test
    fun `tasks dashboard reflows at compact width and large font scales`() {
        val compact = tasksActionDashboardPolicy(availableWidthDp = 360f, fontScale = 1f)
        assertFalse(compact.stacked)
        assertEquals(12f, compact.gapDp)

        val justBelowIntrinsicWidth = tasksActionDashboardPolicy(
            availableWidthDp = 259f,
            fontScale = 1f
        )
        assertTrue(justBelowIntrinsicWidth.stacked)
        assertEquals(12f, justBelowIntrinsicWidth.gapDp)

        val exactIntrinsicWidth = tasksActionDashboardPolicy(
            availableWidthDp = 260f,
            fontScale = 1f
        )
        assertFalse(exactIntrinsicWidth.stacked)

        val large = tasksActionDashboardPolicy(availableWidthDp = 392f, fontScale = 1.3f)
        assertTrue(large.stacked)
        assertEquals(72f, large.tileHeightDp)

        val largest = tasksActionDashboardPolicy(availableWidthDp = 392f, fontScale = 2f)
        assertTrue(largest.stacked)
        assertEquals(88f, largest.tileHeightDp)
    }

    @Test
    fun `tasks geometry covers 360 432 and 599 widths at every required font scale`() {
        listOf(360f, 432f, 599f).forEach { screenWidth ->
            val contentWidth = minOf(screenWidth, 480f)
            listOf(1f, 1.3f, 2f).forEach { fontScale ->
                val policy = tasksActionDashboardPolicy(contentWidth, fontScale)
                assertEquals(fontScale >= 1.3f || contentWidth < 260f, policy.stacked)
                assertEquals(12f, policy.gapDp)
                assertTrue(policy.tileHeightDp >= 64f)
                assertTrue(contentWidth <= 480f)
            }
        }
    }

    @Test
    fun `task summary uses accepted running and queued vocabulary`() {
        assertEquals(
            "1 running · 1 needs attention · 2 queued",
            queueSummary(
                listOf(
                    queueJob(JobKind.SEPARATION, JobState.RUNNING),
                    queueJob(JobKind.SEPARATION, JobState.NEEDS_ATTENTION),
                    queueJob(JobKind.SEPARATION, JobState.WAITING),
                    queueJob(JobKind.LIBRARY, JobState.WAITING)
                )
            )
        )
        assertEquals("0 queued", queueSummary(emptyList()))
    }

    @Test
    fun `terminal unavailable playlist placeholders stay out of owner tasks`() {
        val valid = queueJob(JobKind.SEPARATION, JobState.WAITING)
        val unavailable = queueJob(JobKind.LIBRARY, JobState.NEEDS_ATTENTION).copy(
            id = "source-private-video",
            songTitle = "Unavailable playlist item · private"
        )
        val unavailableByCode = unavailable.copy(
            songTitle = "Persisted private item",
            stableCode = "PLAYLIST_ITEM_UNAVAILABLE"
        )

        assertEquals(
            // A title cannot hide owed work. Only the stable unavailable-item code can.
            listOf(unavailable, valid),
            ownerVisibleQueueJobs(listOf(unavailable, unavailableByCode, valid))
        )
    }

    @Test
    fun `mvsep dialog exposes one merged information and token-help action`() {
        assertEquals(1, mvsepInformationalActions.size)
        assertEquals("MVSEP details & get token", mvsepInformationalActions.single().label)
        assertEquals(
            "https://mvsep.com/en/full_api",
            mvsepInformationalActions.single().url
        )
        assertTrue("encrypts it on this device" in MVSEP_TOKEN_PRIVACY_COPY)
        assertTrue("blocks premium spending" in MVSEP_TOKEN_PRIVACY_COPY)
        assertEquals("Could not open MVSEP details on this device", MVSEP_HELP_OPEN_ERROR)
    }

    @Test
    fun `system hierarchy exposes only accepted connections and storage sections`() {
        assertEquals(
            listOf(SystemSurfaceSection.CONNECTIONS, SystemSurfaceSection.LIBRARY_STORAGE, SystemSurfaceSection.TROUBLESHOOTING),
            SystemSurfaceSection.entries
        )
        assertEquals("Connections", SystemSurfaceSection.CONNECTIONS.label)
        assertEquals("Library & Storage", SystemSurfaceSection.LIBRARY_STORAGE.label)
    }

    @Test
    fun `system status typography uses only approved violet lavender or neutral accents`() {
        assertEquals(PrimaryPurple, systemMvsepStatusAccent(MvsepConnectionStatus.CHECKING))
        assertEquals(
            SYSTEM_CONNECTION_SECONDARY_ACCENT,
            systemMvsepStatusAccent(MvsepConnectionStatus.READY)
        )
        assertEquals(TextSecondary, systemMvsepStatusAccent(MvsepConnectionStatus.SAVED_UNVERIFIED))

        listOf(
            MvsepConnectionStatus.CHECKING,
            MvsepConnectionStatus.SAVED_UNVERIFIED
        ).forEach { status ->
            val accent = systemMvsepStatusAccent(status)
            assertFalse(accent == Sky)
            assertFalse(accent == Cyan)
            assertFalse(accent == Lime)
        }
    }

    @Test
    fun `system connection statuses retain their minimum badge widths`() {
        assertEquals(52f, systemConnectionStatusBadgeMinimumWidthDp(SystemConnectionStatusVisualKind.READY), 0f)
        assertEquals(52f, systemConnectionStatusBadgeMinimumWidthDp(SystemConnectionStatusVisualKind.ERROR), 0f)
        assertEquals(72f, systemConnectionStatusBadgeMinimumWidthDp(SystemConnectionStatusVisualKind.PENDING), 0f)
        assertEquals(92f, systemConnectionStatusBadgeMinimumWidthDp(SystemConnectionStatusVisualKind.NEUTRAL), 0f)
    }

    @Test
    fun `storage timestamp keeps the last success visible through refresh and failure`() {
        val updatedAt = Instant.parse("2026-08-16T12:34:00Z").toEpochMilli()
        val locale = Locale.US
        val utc = TimeZone.getTimeZone("UTC")

        val success = storageLastUpdatedPresentation(
            updatedAt,
            refreshing = false,
            latestRefreshFailed = false,
            locale = locale,
            timeZone = utc
        )
        assertEquals("Last updated: Aug 16, 2026, 12:34 PM", success.visibleText)
        assertEquals(
            "Storage totals last updated Aug 16, 2026, 12:34 PM",
            success.accessibilityText
        )

        val refreshing = storageLastUpdatedPresentation(
            updatedAt,
            refreshing = true,
            latestRefreshFailed = false,
            locale = locale,
            timeZone = utc
        )
        assertEquals(success.visibleText, refreshing.visibleText)
        assertEquals(
            "Storage totals last updated Aug 16, 2026, 12:34 PM; refresh in progress",
            refreshing.accessibilityText
        )

        val failed = storageLastUpdatedPresentation(
            updatedAt,
            refreshing = false,
            latestRefreshFailed = true,
            locale = locale,
            timeZone = utc
        )
        assertEquals(success.visibleText, failed.visibleText)
        assertEquals(
            "Storage totals last updated Aug 16, 2026, 12:34 PM; latest refresh failed",
            failed.accessibilityText
        )
    }

    @Test
    fun `storage timestamp distinguishes no success idle refresh and failure semantics`() {
        val locale = Locale.US
        val utc = TimeZone.getTimeZone("UTC")
        val idle = storageLastUpdatedPresentation(
            0L,
            refreshing = false,
            latestRefreshFailed = false,
            locale = locale,
            timeZone = utc
        )
        val refreshing = storageLastUpdatedPresentation(
            0L,
            refreshing = true,
            latestRefreshFailed = false,
            locale = locale,
            timeZone = utc
        )
        val failed = storageLastUpdatedPresentation(
            0L,
            refreshing = false,
            latestRefreshFailed = true,
            locale = locale,
            timeZone = utc
        )

        assertEquals("Last updated: Never", idle.visibleText)
        assertEquals("Storage totals have not updated yet", idle.accessibilityText)
        assertEquals(
            "Storage totals have not completed an update; refresh in progress",
            refreshing.accessibilityText
        )
        assertEquals(
            "Storage totals have never updated successfully; latest refresh failed",
            failed.accessibilityText
        )
    }

    @Test
    fun `android system manifests never inflate the imported json count`() {
        val summary = systemStorageSummary(
            LibraryInventory(
                songsFound = 40,
                jsonFilesFound = 40,
                verifiedSongPackages = 40,
                verifiedJsonFiles = 40,
                pcEnrichedSongManifests = 0
            )
        )

        assertEquals(40, summary.artworkAndJsonCount)
    }

    @Test
    fun `storage presentation preserves required local inventory and ownership totals`() {
        val inventory = LibraryInventory(
                songsFound = 40,
                originalFilesFound = 40,
                stemFilesFound = 240,
                jsonFilesFound = 40,
                artworkFilesFound = 12,
                verifiedSongPackages = 40,
                verifiedOriginalFiles = 40,
                verifiedStemFiles = 240,
                verifiedJsonFiles = 40,
                pcEnrichedSongManifests = 3,
                verifiedArtworkFiles = 12,
                actualLibraryBytes = 2_300_000_000L,
                appPrivateTotalBytes = 436_000_000L
        )
        val summary = systemStorageSummary(inventory)

        assertEquals(2_300_000_000L, summary.libraryBytes)
        assertEquals(436_000_000L, summary.appBytes)
        assertEquals(52, summary.artworkAndJsonCount)
        assertEquals(240, summary.verifiedStems)
        assertEquals(40, summary.verifiedSongs)
    }

    @Test
    fun `normal startup presents the folder choice`() {
        val presentation = libraryRootGatePresentation(null, null)
        assertEquals("Choose your Library folder", presentation.heading)
        assertEquals("Choose folder", presentation.actionLabel)
    }

    @Test
    fun `user operation reports ordinary failures`() = runBlocking {
        val error = IllegalStateException("provider unavailable")

        val result = captureUserOperation<Unit> { throw error }

        assertSame(error, result.exceptionOrNull())
        assertEquals(
            "Could not refresh storage totals: provider unavailable",
            operationFailureMessage(error, "Could not refresh storage totals")
        )
    }

    @Test
    fun `repository storage failure detail receives one stable user-facing prefix`() {
        assertEquals(
            "Could not refresh storage totals",
            storageRefreshFailureMessage("STORAGE_REFRESH_FAILED", "provider unavailable")
        )
        assertEquals(
            "Could not refresh storage totals",
            storageRefreshFailureMessage("STORAGE_REFRESH_FAILED", "detail")
        )
        assertNull(storageRefreshFailureMessage(null, null))
    }

    @Test
    fun `unavailable storage root uses explicit reconnect message`() {
        assertEquals(
            "Reconnect the Library folder to refresh storage totals",
            storageRefreshFailureMessage("ROOT_UNAVAILABLE", "detail")
        )
        assertEquals(
            "Reconnect the Library folder to refresh storage totals",
            storageRefreshFailureMessage("ROOT_PERMISSION_LOST", "detail")
        )
        assertEquals(
            "Reconnect the Library folder to refresh storage totals",
            storageRefreshFailureMessage("ROOT_UNAVAILABLE", "any localized detail")
        )

    }

    @Test
    fun `user operation does not turn cancellation into an error message`() = runBlocking {
        val cancellation = CancellationException("screen left")

        val thrown = runCatching {
            captureUserOperation<Unit> { throw cancellation }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `mvsep disconnect cancels work only after durable token removal`() = runBlocking {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("disk unavailable")

        val failed = removeMvsepConnection(
            removeToken = {
                events += "remove"
                Result.failure(failure)
            },
            cancelPreparation = { events += "cancel" }
        )
        assertSame(failure, failed.exceptionOrNull())
        assertEquals(listOf("remove"), events)

        events.clear()
        assertTrue(removeMvsepConnection(
            removeToken = {
                events += "remove"
                Result.success(Unit)
            },
            cancelPreparation = { events += "cancel" }
        ).isSuccess)
        assertEquals(listOf("remove", "cancel"), events)
    }

    private fun queueJob(
        kind: JobKind,
        state: JobState = JobState.RUNNING,
        detail: String = "Working"
    ) = QueueJob(
        id = "fixture",
        songTitle = "Fixture song",
        artist = "Fixture artist",
        kind = kind,
        state = state,
        progress = 0.5f,
        detail = detail,
        accent = Color.Blue
    )

}
