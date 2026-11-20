package app.stemwise.work

import app.stemwise.data.RootAuthority
import app.stemwise.integration.SeparationRecoveryReview
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTaskCoordinatorTest {
    @Test fun adoptedIdSurvivesTransientOwnerRefreshAndWakesWithoutAnotherUserAction() = runBlocking {
        withFixture { fixture ->
            fixture.actions.refreshFailures = 1
            fixture.coordinator.recoverSeparationSubmission(recoveryReview(ROOT_A, "job", "accepted"), true).getOrThrow()
            assertEquals(1, fixture.actions.recoveries.size)
            assertEquals(0, fixture.actions.separationEnqueues)
            kotlinx.coroutines.withTimeout(3_000L) {
                while (fixture.actions.separationEnqueues == 0) kotlinx.coroutines.delay(10L)
            }
            assertEquals(2, fixture.actions.refreshes)
            assertEquals(listOf(ROOT_A), fixture.actions.enqueuedRoots)
            assertEquals(listOf(true), fixture.actions.successorRequests)
            assertEquals(1, fixture.actions.recoveries.size)
        }
    }

    @Test fun persistentOwnerReadFailureAndRootSwitchCannotEnqueueAnOldAdoptedId() = runBlocking {
        withFixture { fixture ->
            fixture.actions.refreshFailures = Int.MAX_VALUE
            fixture.coordinator.recoverSeparationSubmission(recoveryReview(ROOT_A, "job", "accepted"), true).getOrThrow()
            assertEquals(0, fixture.actions.separationEnqueues)
            fixture.coordinator.onLibrarySwitchStarted()
            fixture.currentRoot = ROOT_B
            kotlinx.coroutines.delay(1_100L)
            assertEquals(1, fixture.actions.refreshes)
            assertEquals(0, fixture.actions.separationEnqueues)
            assertEquals(1, fixture.actions.recoveries.size)
        }
    }

    @Test fun reopeningTheTaskRefreshesItsRetainedAdoptionEvenWhenOldOwnerAdmissionIsBlocked() = runBlocking {
        withFixture { fixture ->
            fixture.admissionReady = false
            fixture.actions.refreshFailures = Int.MAX_VALUE
            fixture.coordinator.recoverSeparationSubmission(recoveryReview(ROOT_A, "job", "accepted"), true).getOrThrow()
            fixture.coordinator.onTaskRemoved()
            fixture.actions.refreshFailures = 0
            fixture.actions.afterRefresh = { fixture.admissionReady = true }
            fixture.coordinator.onTaskAvailable()
            assertEquals(1, fixture.actions.recoveries.size)
            assertEquals(2, fixture.actions.refreshes)
            assertEquals(listOf(ROOT_A), fixture.actions.enqueuedRoots)
            assertEquals(listOf(true), fixture.actions.successorRequests)
        }
    }

    @Test fun recoveredAcceptedIdRetainsSuccessorWakeUntilOwnerAdmissionSucceeds() = runBlocking {
        withFixture { fixture ->
            fixture.coordinator.onColdOpenFinished()
            fixture.actions.allowEnqueue = false
            fixture.coordinator.recoverSeparationSubmission(recoveryReview(ROOT_A, "same-job", "accepted-remote"), true).getOrThrow()
            fixture.actions.allowEnqueue = true
            fixture.coordinator.onColdOpenFinished()
            fixture.coordinator.onColdOpenFinished()
            assertEquals(listOf(false, true), fixture.actions.successorRequests)
            assertEquals(listOf(Triple(ROOT_A, "same-job", "accepted-remote")), fixture.actions.recoveries)
        }
    }
    @Test fun rejectedOwnerEnqueueDoesNotConsumeReadyWake() = runBlocking {
        withFixture { fixture ->
            fixture.actions.allowEnqueue = false
            fixture.coordinator.onColdOpenFinished()
            assertEquals(0, fixture.actions.separationEnqueues)
            fixture.actions.allowEnqueue = true
            fixture.coordinator.onColdOpenFinished()
            fixture.coordinator.onColdOpenFinished()
            assertEquals(1, fixture.actions.separationEnqueues)
        }
    }
    @Test fun retriesAndRecoveredSubmissionsAppendASuccessorWhileReadinessWakesCoalesce() = runBlocking {
        withFixture { fixture ->
            assertTrue(fixture.coordinator.retrySeparation("same-job"))
            fixture.actions.completeSeparation(true)
            assertEquals(listOf(true), fixture.actions.successorRequests)
            fixture.coordinator.recoverSeparationSubmission(recoveryReview(ROOT_A, "same-job", "accepted-remote"), true).getOrThrow()
            assertEquals(listOf(true, true), fixture.actions.successorRequests)
            fixture.coordinator.onColdOpenFinished()
            assertEquals(listOf(true, true, false), fixture.actions.successorRequests)
            assertEquals(listOf(Triple(ROOT_A, "same-job", "accepted-remote")), fixture.actions.recoveries)
        }
    }

    @Test
    fun acceptedRemoteIdCanBeRepairedWhileNewPreparationAdmissionStaysClosed() = runBlocking {
        withFixture { fixture ->
            fixture.admissionReady = false
            assertFalse(fixture.coordinator.retrySeparation("job"))
            assertTrue(fixture.coordinator.requestSources("song", listOf("bass")).isFailure)
            assertTrue(fixture.coordinator.recoverSeparationSubmission(recoveryReview(ROOT_A, "job", "already-accepted"), true).isSuccess)
            assertEquals(listOf(Triple(ROOT_A, "job", "already-accepted")), fixture.actions.recoveries)
            assertEquals(0, fixture.actions.separationEnqueues)
            assertTrue(fixture.actions.sourceRequests.isEmpty())
            fixture.currentRoot = ROOT_B
            assertTrue(fixture.coordinator.recoverSeparationSubmission(recoveryReview(ROOT_A, "job", "already-accepted"), true).isFailure)
            assertEquals(1, fixture.actions.recoveries.size)
        }
    }

    @Test fun reviewingAnUncertainRemoteJobDoesNotAdoptItUntilItsExactSourceIsConfirmed() = runBlocking {
        withFixture { fixture ->
            val review = fixture.coordinator.reviewSeparationSubmission("job", "remote", ROOT_A).getOrThrow()
            assertEquals("a".repeat(64), review.sourceSha256)
            assertEquals("Frozen model", review.configuration)
            assertTrue(fixture.actions.recoveries.isEmpty())
            assertEquals(0, fixture.actions.separationEnqueues)
            assertTrue(fixture.coordinator.recoverSeparationSubmission(review, false).isFailure)
            assertTrue(fixture.actions.recoveries.isEmpty())
            assertTrue(fixture.coordinator.recoverSeparationSubmission(review, true).isSuccess)
            assertEquals(listOf(Triple(ROOT_A, "job", "remote")), fixture.actions.recoveries)
        }
    }

    @Test
    fun exactRemoteRecoverySurfacesFailureAndSchedulesOnlyTheCurrentRoot() = runBlocking {
        withFixture { fixture ->
            val refused = IllegalStateException("Unknown remote ID")
            fixture.actions.recoveryResult = Result.failure(refused)
            val failure = runBlocking { fixture.coordinator.recoverSeparationSubmission(recoveryReview(ROOT_A, "job", "exact-id"), true) }
            assertSame(refused, failure.exceptionOrNull())
            assertEquals(0, fixture.actions.separationEnqueues)
            fixture.actions.recoveryResult = Result.success(Unit)
            assertTrue(runBlocking { fixture.coordinator.recoverSeparationSubmission(recoveryReview(ROOT_A, "job", "exact-id"), true) }.isSuccess)
            assertEquals(listOf(ROOT_A), fixture.actions.enqueuedRoots)
            fixture.currentRoot = ROOT_B
            assertTrue(runBlocking { fixture.coordinator.recoverSeparationSubmission(recoveryReview(ROOT_A, "job", "exact-id"), true) }.isFailure)
            assertEquals(listOf(ROOT_A), fixture.actions.enqueuedRoots)
        }
    }

    @Test
    fun storageCleanupResumesOnlyTheAcceptedRootAndOnlyAfterSuccessfulCleanup() = runBlocking {
        withFixture { fixture ->
            var cleaned = false
            val resume = launch(start = CoroutineStart.UNDISPATCHED) {
                assertTrue(fixture.coordinator.retrySeparationAfterCleanup("job-1") { cleaned = true })
            }
            assertTrue(cleaned)
            assertTrue(fixture.actions.separationPending)
            assertEquals(0, fixture.actions.separationEnqueues)
            fixture.actions.completeSeparation(true)
            resume.join()
            assertEquals(1, fixture.actions.separationEnqueues)
            assertEquals(listOf(ROOT_A), fixture.actions.acceptedRoots)
        }
        withFixture { fixture ->
            assertFalse(fixture.coordinator.retrySeparationAfterCleanup("job-1") {
                fixture.currentRoot = ROOT_B
            })
            assertTrue(fixture.actions.acceptedRoots.isEmpty())
            assertEquals(0, fixture.actions.separationEnqueues)
            try {
                fixture.coordinator.retrySeparationAfterCleanup("job-1") { error("cleanup refused") }
                error("cleanup failure was ignored")
            } catch (expected: IllegalStateException) {
                assertEquals("cleanup refused", expected.message)
            }
            assertTrue(fixture.actions.acceptedRoots.isEmpty())
        }
    }
    @Test
    fun acceptedSelectedPartsSurviveUiCancellationBeforeDurableHandoff() = runBlocking {
        withFixture { fixture ->
            val release = CompletableDeferred<Result<Boolean>>()
            fixture.actions.sourceQueueWait = release
            val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.requestSources("song-1", listOf("bass"))
            }
            assertEquals(1, fixture.actions.sourceRequests.size)
            owner.cancelAndJoin()
            release.complete(Result.success(true))
            assertEquals(1, fixture.actions.sourceQueueCompletions)
            assertEquals(1, fixture.actions.separationEnqueues)
        }
    }

    @Test
    fun taskRemovalStillCancelsAcceptedPreparationBeforeDurableHandoff() = runBlocking {
        withFixture { fixture ->
            val release = CompletableDeferred<Result<Boolean>>()
            fixture.actions.sourceQueueWait = release
            val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.requestSources("song-1", listOf("bass"))
            }
            fixture.coordinator.onTaskRemoved()
            release.complete(Result.success(true))
            owner.join()
            assertEquals(0, fixture.actions.sourceQueueCompletions)
            assertEquals(0, fixture.actions.separationEnqueues)
        }
    }

    @Test
    fun replacementRootRejectsCancellationIgnoringPreparationHandoff() = runBlocking {
        withFixture { fixture ->
            fixture.actions.suspendSourceQueueIgnoringCancellation = true
            val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.requestSources("song-1", listOf("bass"))
            }
            fixture.coordinator.onLibrarySwitchStarted()
            fixture.currentRoot = ROOT_B
            fixture.coordinator.onLibrarySwitchFinished()
            assertEquals(listOf(ROOT_B), fixture.actions.enqueuedRoots)
            fixture.actions.completeIgnoredSourceQueue()
            owner.join()
            assertEquals("The cancelled old-root request must not add a handoff", listOf(ROOT_B),
                fixture.actions.enqueuedRoots)
            assertEquals(listOf(ROOT_A), fixture.actions.acceptedRoots)
        }
    }

    @Test fun selectedPartsAreHandedOffExactlyAndFailureDoesNotStartWorker() {
        withFixture { fixture ->
            val parts = listOf("lead-vocals", "bass")
            assertTrue(runBlocking { fixture.coordinator.requestSources("song-1", parts) }.isSuccess)
            assertEquals(listOf("song-1" to parts), fixture.actions.sourceRequests)
            assertEquals(1, fixture.actions.separationEnqueues)
            fixture.actions.sourceQueueResult = Result.failure(IllegalStateException("storage low"))
            assertTrue(runBlocking { fixture.coordinator.requestSources("song-2", listOf("kick")) }.isFailure)
            assertEquals(1, fixture.actions.separationEnqueues)
        }
    }
    @Test
    fun lateSeparationRetryCannotReenqueueAfterTaskRemoval() {
        withFixture { fixture ->
            assertTrue(fixture.coordinator.retrySeparation("job-1"))
            assertTrue(fixture.actions.separationPending)

            fixture.coordinator.onTaskRemoved()
            assertFalse(fixture.coordinator.retrySeparation("job-2"))
            fixture.actions.completeSeparation(true)

            assertEquals(0, fixture.actions.separationEnqueues)
        }
    }

    @Test fun librarySwitchRejectsALateSeparationRetryAndAdmitsANewRootRequest() {
        withFixture { fixture ->
            fixture.coordinator.retrySeparation("job")
            fixture.coordinator.onLibrarySwitchStarted()
            fixture.currentRoot = ROOT_B
            fixture.coordinator.onLibrarySwitchFinished()
            assertEquals(listOf(ROOT_B), fixture.actions.enqueuedRoots)
            fixture.actions.completeSeparation(true)
            assertEquals("Late old-root retry must not enqueue", listOf(ROOT_B), fixture.actions.enqueuedRoots)
            fixture.coordinator.retrySeparation("job")
            fixture.actions.completeSeparation(true)
            assertEquals(ROOT_B, fixture.actions.acceptedRoots.last())
            assertEquals(listOf(ROOT_B, ROOT_B), fixture.actions.enqueuedRoots)
        }
    }

    @Test
    fun manualSyncIsRejectedUntilANewTaskIsAvailable() {
        withFixture { fixture ->
            fixture.coordinator.onTaskRemoved()
            assertFalse(fixture.coordinator.sync())
            assertEquals(0, fixture.actions.syncEnqueues)

            fixture.coordinator.onTaskAvailable()
            assertTrue(fixture.coordinator.sync())
            assertEquals(1, fixture.actions.syncEnqueues)
        }
    }

    @Test
    fun stemsAreQueuedOnlyByAnExplicitSongRequest() {
        withFixture { fixture ->
            val result = runBlocking { fixture.coordinator.requestSources("song-1", listOf("vocals")) }

            assertTrue(result.isSuccess)
            assertEquals(1, fixture.actions.separationEnqueues)
            assertEquals(listOf(ROOT_A), fixture.actions.acceptedRoots)
        }
    }

    @Test
    fun sameProcessTaskReopenRearmsOneExactEligibleOwnerAndPeriodicSchedule() {
        withFixture { fixture ->
            fixture.playlistConfigured = true
            fixture.coordinator.onTaskRemoved()

            fixture.coordinator.onTaskAvailable()
            fixture.coordinator.onTaskAvailable()
            fixture.coordinator.onColdOpenFinished()

            assertEquals(listOf(true), fixture.actions.periodicUpdates)
            assertEquals(1, fixture.actions.separationEnqueues)
        }
    }

    @Test
    fun importedLibraryReopenResumesOnlyExistingStemWorkWithoutPlaylistConsent() {
        withFixture { fixture ->
            fixture.coordinator.onTaskRemoved()
            fixture.coordinator.onTaskAvailable()
            fixture.coordinator.onColdOpenFinished()

            assertEquals(listOf(false), fixture.actions.periodicUpdates)
            assertEquals(1, fixture.actions.separationEnqueues)
            assertTrue(fixture.actions.acceptedRoots.isEmpty()) // No queueBaseline/retry request.

            fixture.coordinator.onTaskRemoved()
            fixture.admissionReady = false
            fixture.coordinator.onTaskAvailable()
            assertEquals(1, fixture.actions.separationEnqueues)
        }
    }

    @Test
    fun librarySwitchRearmsExactlyOnceForEitherColdOpenCallbackOrder() {
        listOf(true, false).forEach { coldOpenFirst ->
            withFixture { fixture ->
                fixture.playlistConfigured = true
                fixture.coordinator.onLibrarySwitchStarted()
                if (coldOpenFirst) fixture.coordinator.onColdOpenFinished()
                assertTrue(fixture.actions.periodicUpdates.isEmpty())
                assertEquals(0, fixture.actions.separationEnqueues)
                fixture.coordinator.onLibrarySwitchFinished()
                fixture.coordinator.onColdOpenFinished()
                fixture.coordinator.onColdOpenFinished()
                assertEquals(listOf(true), fixture.actions.periodicUpdates)
                assertEquals(1, fixture.actions.separationEnqueues)
            }
        }
    }

    @Test
    fun nonReadyRootNeverRearmsSeparation() {
        withFixture { fixture ->
            fixture.playlistConfigured = true
            fixture.coordinator.onColdOpenFinished()
            assertEquals(listOf(true), fixture.actions.periodicUpdates)
            assertEquals(1, fixture.actions.separationEnqueues)

            fixture.coordinator.onTaskRemoved()
            fixture.admissionReady = false
            fixture.coordinator.onTaskAvailable()
            assertEquals(1, fixture.actions.separationEnqueues)
        }
    }

    @Test fun retryPersistenceFailureIsPublishedAndLateOldRootFailureCannotReplaceIt() {
        withFixture { fixture ->
            assertTrue(fixture.coordinator.retrySeparation("job-a"))
            fixture.actions.completeSeparation(false)
            val failure = checkNotNull(fixture.coordinator.retryFailure.value)
            assertEquals("job-a", failure.jobId)
            assertEquals("Retry refused", failure.cause.message)
            assertEquals(0, fixture.actions.separationEnqueues)
            fixture.coordinator.retrySeparation("job-a")
            fixture.actions.completeSeparationFailure(failure.cause)
            val newer = checkNotNull(fixture.coordinator.retryFailure.value)
            assertFalse(failure === newer)
            fixture.coordinator.consumeRetryFailure(failure)
            assertSame("An old notification cannot consume a later retry of the same failure", newer,
                fixture.coordinator.retryFailure.value)
            fixture.coordinator.consumeRetryFailure(newer)
            assertEquals(null, fixture.coordinator.retryFailure.value)
            fixture.coordinator.retrySeparation("old-job")
            fixture.coordinator.onLibrarySwitchStarted()
            fixture.currentRoot = ROOT_B
            fixture.coordinator.onLibrarySwitchFinished()
            fixture.actions.completeSeparation(false)
            assertEquals(null, fixture.coordinator.retryFailure.value)
            assertEquals(listOf(ROOT_B), fixture.actions.enqueuedRoots)
        }
    }

    private inline fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val rootJob = SupervisorJob()
        val actions = FakeManualTaskActions()
        var currentRoot = ROOT_A
        var admissionReady = true
        var playlistConfigured = false
        val coordinator = ManualTaskCoordinator(
            scope = CoroutineScope(rootJob + Dispatchers.Unconfined),
            taskGate = TaskWorkGate(),
            actions = actions,
            captureReadyRoot = { currentRoot.takeIf { admissionReady } },
            isReadyRoot = { expected -> admissionReady && expected == currentRoot },
            playlistConfigured = { playlistConfigured },
            captureRecoveryRoot = { currentRoot }
        )

        fun close() {
            // Complete cancellation-ignoring fakes before dropping the owning scope.
            actions.completePendingAsFalse()
            rootJob.cancel()
        }
    }

    private class FakeManualTaskActions : ManualTaskActions {
        var refreshFailures = 0
        var refreshes = 0
        var afterRefresh: () -> Unit = {}
        override suspend fun refreshPreparationOwner(expectedRoot: RootAuthority): Result<Unit> {
            refreshes++
            afterRefresh()
            return if (refreshFailures-- > 0) Result.failure(java.io.IOException("authority read unavailable"))
                else Result.success(Unit)
        }
        var recoveryResult: Result<Unit> = Result.success(Unit)
        val recoveries = mutableListOf<Triple<RootAuthority, String, String>>()
        override suspend fun reviewRecovery(expectedRoot: RootAuthority, jobId: String, remoteJobId: String) =
            Result.success(recoveryReview(expectedRoot, jobId, remoteJobId))
        override suspend fun recoverSubmission(review: SeparationRecoveryReview, sourceConfirmed: Boolean): Result<Unit> {
            check(sourceConfirmed)
            recoveries += Triple(review.root, review.jobId, review.remoteJobId)
            return recoveryResult
        }
        var sourceQueueWait: CompletableDeferred<Result<Boolean>>? = null
        var sourceQueueCompletions = 0
        var suspendSourceQueueIgnoringCancellation = false
        private var ignoredSourceQueue: Continuation<Result<Boolean>>? = null
        val sourceRequests = mutableListOf<Pair<String, List<String>>>()
        var sourceQueueResult: Result<Boolean> = Result.success(true)
        override suspend fun queueSources(expectedRoot: RootAuthority, songId: String,
            selectedIds: List<String>): Result<Boolean> {
            acceptedRoots += expectedRoot
            sourceRequests += songId to selectedIds
            val result = if (suspendSourceQueueIgnoringCancellation) {
                suspendCoroutine { ignoredSourceQueue = it }
            } else sourceQueueWait?.await() ?: sourceQueueResult
            sourceQueueCompletions++
            return result
        }
        var syncEnqueues = 0
        var separationEnqueues = 0
        var allowEnqueue = true
        val enqueuedRoots = mutableListOf<RootAuthority>()
        val successorRequests = mutableListOf<Boolean>()
        val periodicUpdates = mutableListOf<Boolean>()
        private var separationContinuation: Continuation<Result<Unit>>? = null
        val separationPending: Boolean get() = separationContinuation != null
        val acceptedRoots = mutableListOf<RootAuthority>()

        override fun enqueueSync(): Boolean {
            syncEnqueues += 1
            return true
        }

        override fun updatePeriodicSync(configured: Boolean) {
            periodicUpdates += configured
        }

        override suspend fun retrySeparation(
            expectedRoot: RootAuthority,
            jobId: String
        ): Result<Unit> {
            acceptedRoots += expectedRoot
            return suspendCoroutine { continuation ->
                separationContinuation = continuation
            }
        }

        fun completeIgnoredSourceQueue() {
            checkNotNull(ignoredSourceQueue).also {
                ignoredSourceQueue = null
                it.resume(Result.success(true))
            }
        }

        fun completeSeparation(result: Boolean) {
            checkNotNull(separationContinuation).also {
                separationContinuation = null
                it.resume(if (result) Result.success(Unit) else Result.failure(IllegalStateException("Retry refused")))
            }
        }

        fun completeSeparationFailure(error: Throwable) {
            checkNotNull(separationContinuation).also {
                separationContinuation = null
                it.resume(Result.failure(error))
            }
        }

        fun completePendingAsFalse() {
            sourceQueueWait?.cancel()
            ignoredSourceQueue?.also {
                ignoredSourceQueue = null
                it.resume(Result.success(false))
            }
            separationContinuation?.also {
                separationContinuation = null
                it.resume(Result.failure(IllegalStateException("Retry refused")))
            }
        }

        override fun enqueueSeparation(expectedRoot: RootAuthority, afterCurrent: Boolean): Boolean {
            if (!allowEnqueue) return false
            enqueuedRoots += expectedRoot
            successorRequests += afterCurrent
            separationEnqueues += 1
            return true
        }
    }

    companion object {
        private fun recoveryReview(root: RootAuthority, job: String, remote: String) = SeparationRecoveryReview(
            root, job, remote, "source-v1", "a".repeat(64), 1L, 2L, 0, "b".repeat(64),
            "Known song", "Original", "Frozen model", null, null)

        private val ROOT_A = RootAuthority("library-a", "content://provider/tree/a", 1L)
        private val ROOT_B = RootAuthority("library-b", "content://provider/tree/b", 2L)
    }
}
