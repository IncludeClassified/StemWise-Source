package app.stemwise.ui

import app.stemwise.data.PreparationOwnerDecision
import app.stemwise.data.RootAuthority
import app.stemwise.model.JobKind
import app.stemwise.model.JobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationOwnerQueueProjectionTest {
    @Test fun retirementRecoveryIsVisibleAndActionableOnlyForItsExactReadyRoot() {
        val ready = app.stemwise.data.ColdOpenState.Ready("session", 1L, root.libraryId, root.treeUri,
            root.epoch, preparationBlocked = true,
            preparationRecovery = app.stemwise.data.ColdOpenState.Blocked("TOOL_PROCESS_RETIREMENT_UNCONFIRMED", "Worker has not retired"))
        val row = checkNotNull(projectPreparationRecoveryQueueJob(ready, root))
        assertTrue(canRetryQueueJob(row))
        assertEquals(JobKind.LIBRARY, row.kind)
        assertEquals(ready.preparationRecovery?.code, row.stableCode)
        assertNull(projectPreparationRecoveryQueueJob(ready, root.copy(epoch = root.epoch + 1L)))
        assertNull(projectPreparationRecoveryQueueJob(ready.copy(preparationBlocked = false), root))
        assertNull(projectPreparationRecoveryQueueJob(ready.copy(preparationRecovery = null), root))
    }

    @Test
    fun admittedDownloadAndAssemblyAreRunningWithStageProgress() {
        for ((phase, detail) in listOf("DOWNLOADING" to "Downloading", "ASSEMBLING" to "Assembling")) {
            val owner = PreparationOwnerDecision.Separation(root, "job", "song", "F84DytTL_j0", phase, null, null)
            val row = checkNotNull(projectPreparationOwnerQueueJob(owner, root, emptyMap()))
            assertEquals(JobState.RUNNING, row.state)
            assertTrue(row.progress > 0f)
            assertTrue(row.detail.contains(detail))
            assertNull(projectPreparationOwnerQueueJob(owner, root.copy(epoch = 10), emptyMap()))
            assertNull(projectPreparationOwnerQueueJob(owner, null, emptyMap()))
            val persisted = app.stemwise.data.ProcessingJobEntity("job", "song", "source", "SOURCE_SELECTION",
                "MVSEP", "plan", phase, "remote", 1, null, null, null, 2, 1, root.libraryId)
            val fallback = checkNotNull(projectPersistedPreparationQueueJob(persisted, emptyMap(), null, false))
            assertEquals(row.state, fallback.state)
            assertEquals(row.progress, fallback.progress)
            assertEquals(row.detail, fallback.detail)
            assertNull(projectPersistedPreparationQueueJob(persisted, emptyMap(), "job", false))
            assertNull(projectPersistedPreparationQueueJob(persisted, emptyMap(), null, true))
            assertNull(projectPersistedPreparationQueueJob(
                persisted.copy(kind = "BASELINE_SEPARATION"), emptyMap(), null, false))
        }
        assertEquals(JobState.WAITING, preparationQueuePhase("CREATED").state)
        for (phase in listOf("SUBMISSION_STARTED", "REMOTE_JOB_CREATED", "PROCESSING", "PUBLISHING")) {
            assertEquals(JobState.RUNNING, preparationQueuePhase(phase).state)
        }
    }

    @Test
    fun onlyExplicitRecoveryActionsAreInteractive() {
        val job = app.stemwise.model.QueueJob(
            id = "library-problem", songTitle = "Song", artist = "Artist",
            kind = JobKind.LIBRARY, state = JobState.NEEDS_ATTENTION,
            progress = 0f, detail = "PACKAGE_INCOMPLETE", accent = androidx.compose.ui.graphics.Color.Red
        )
        assertTrue(!canRetryQueueJob(job))
        assertTrue(!canRetryQueueJob(job.copy(kind = JobKind.SEPARATION)))
        for (kind in listOf(JobKind.LIBRARY, JobKind.SEPARATION)) {
            val recovery = job.copy(kind = kind, action = app.stemwise.model.QueueJobAction.VIEW_DETAILS)
            assertTrue(canRetryQueueJob(recovery))
            assertTrue(!canRetryQueueJob(recovery.copy(state = JobState.RUNNING)))
        }
        val blocked = PreparationOwnerDecision.Blocked(root, "SOURCE_JOB_RECOVERY_PENDING",
            listOf("F84DytTL_j0"), recoverySongIds = listOf("F84DytTL_j0"))
        val projected = checkNotNull(projectPreparationOwnerQueueJob(blocked, root, emptyMap()))
        assertEquals(JobKind.LIBRARY, projected.kind)
        assertEquals(app.stemwise.model.QueueJobAction.VIEW_DETAILS, projected.action)
        assertTrue(canRetryQueueJob(projected))
    }

    @Test
    fun onlyProvenTerminalRemoteFailureOffersDiscardEvenWithoutSongOrOutputs() {
        val action = app.stemwise.model.QueueJobAction.DISCARD_FAILED_PREPARATION
        val persisted = app.stemwise.data.ProcessingJobEntity("failed", "song", "source", "SOURCE_SELECTION",
            "MVSEP", "plan", "FAILED", "remote", 1, null, "MVSEP_REMOTE_FAILED", "Remote failed", 2, 1, root.libraryId)
        val row = checkNotNull(projectPersistedPreparationQueueJob(persisted, emptyMap(), null, false))
        assertEquals(action, row.action)
        assertEquals("Discard failed preparation", row.actionLabel)
        assertTrue(canRetryQueueJob(row))
        assertNull(projectPersistedPreparationQueueJob(persisted.copy(state = "PROCESSING"), emptyMap(), null, false)?.action)
        assertEquals(app.stemwise.model.QueueJobAction.RETRY,
            projectPersistedPreparationQueueJob(persisted.copy(lastErrorCode = "MVSEP_NETWORK_FAILED"),
                emptyMap(), null, false)?.action)
        assertNull(projectPersistedPreparationQueueJob(persisted.copy(kind = "OTHER"), emptyMap(), null, false))
    }

    private val root = RootAuthority("library-a", "content://provider/tree/a", 9L)

    @Test
    fun ownerWithoutPlayableSongRowUsesMaskedStableFallback() {
        val job = projectPreparationOwnerQueueJob(
            PreparationOwnerDecision.Separation(
                root,
                "source-F84DytTL_j0",
                "F84DytTL_j0",
                "F84DytTL_j0",
                "CREATED",
                123L,
                null
            ),
            root,
            emptyMap()
        )

        checkNotNull(job)
        assertEquals("Song F84DytTL", job.songTitle)
        assertTrue(job.artist.contains("F84DytTL"))
        assertEquals(JobKind.SEPARATION, job.kind)
        assertEquals(JobState.WAITING, job.state)
        assertTrue(job.actionLabel == null)
    }

    @Test
    fun ambiguityListsEveryMaskedOwnerWithoutRetryAffordance() {
        val owner = PreparationOwnerDecision.Blocked(
            root,
            "PREPARATION_OWNER_CONFLICT",
            listOf("F84DytTL_j0", "tWEd6wxP6eM")
        )
        val job = projectPreparationOwnerQueueJob(
            owner,
            root,
            emptyMap()
        )

        checkNotNull(job)
        assertEquals(JobState.NEEDS_ATTENTION, job.state)
        assertTrue(job.artist.contains("F84DytTL"))
        assertTrue(job.artist.contains("tWEd6wxP"))
        assertNull(job.actionLabel)
        assertEquals("PREPARATION_OWNER_CONFLICT", job.stableCode)
        assertTrue(suppressUnderlyingAutomaticPreparationRows(owner, root))
        assertTrue(!suppressUnderlyingAutomaticPreparationRows(owner, root.copy(epoch = 10L)))
    }

    @Test
    fun exactUnknownSubmissionHasRecoveryActionUnderItsPersistedJobId() {
        val owner = PreparationOwnerDecision.Blocked(root, "PREPARATION_SUBMISSION_UNKNOWN",
            listOf("F84DytTL_j0"), recoverableJobId = "exact-job")
        val projected = checkNotNull(projectPreparationOwnerQueueJob(owner, root, emptyMap()))
        assertEquals("exact-job", projected.id)
        assertEquals(JobKind.SEPARATION, projected.kind)
        assertEquals(app.stemwise.model.QueueJobAction.VIEW_DETAILS, projected.action)
        assertNull(projectPreparationOwnerQueueJob(owner, root.copy(epoch = root.epoch + 1), emptyMap()))
    }

}
