package app.stemwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationOwnerArbitrationTest {
    private val root = RootAuthority("library-a", "content://provider/tree/a", 7L)

    private fun arbitratePreparationOwner(root: RootAuthority, jobs: List<PreparationJobFact>) =
        app.stemwise.data.arbitratePreparationOwner(root, jobs, jobs.associate { it.jobId to 1L })

    @Test fun independentlyVerifiedAcceptedOwnersSerializeButUncertainOrConflictingIdsStayBlocked() {
        val a = separation("tWEd6wxP6eM").copy(createdAtEpochMs = 1L, remoteJobId = "accepted-a")
        val b = separation("F84DytTL_j0").copy(createdAtEpochMs = 2L, remoteJobId = "accepted-b")
        val generations = mapOf(a.jobId to 1L, b.jobId to 1L)
        val accepted = mapOf(a.jobId to "accepted-a", b.jobId to "accepted-b")
        assertEquals(a.jobId, (app.stemwise.data.arbitratePreparationOwner(root, listOf(b, a), generations,
            accepted) as PreparationOwnerDecision.Separation).jobId)
        assertEquals(b.jobId, (app.stemwise.data.arbitratePreparationOwner(root, listOf(b), generations,
            accepted) as PreparationOwnerDecision.Separation).jobId)
        for (state in listOf("DOWNLOADING", "ASSEMBLING", "PUBLISHING", "VERIFIED", "PACKAGE_COMMITTED", "PROJECTING")) {
            assertEquals(a.jobId, (app.stemwise.data.arbitratePreparationOwner(root,
                listOf(b, a.copy(state = state)), generations, accepted) as PreparationOwnerDecision.Separation).jobId)
        }
        for (proof in listOf(emptyMap(), accepted + (a.jobId to "unmatched"))) {
            assertTrue(app.stemwise.data.arbitratePreparationOwner(root, listOf(a, b), generations, proof)
                is PreparationOwnerDecision.Blocked)
        }
        assertTrue(app.stemwise.data.arbitratePreparationOwner(root,
            listOf(a, b.copy(remoteJobId = "accepted-a")), generations,
            mapOf(a.jobId to "accepted-a", b.jobId to "accepted-a")) is PreparationOwnerDecision.Blocked)
        assertTrue(app.stemwise.data.arbitratePreparationOwner(root,
            listOf(a, b.copy(state = "SUBMISSION_UNKNOWN", remoteJobId = null)), generations,
            accepted) is PreparationOwnerDecision.Blocked)
    }

    @Test
    fun replacementGenerationsUseVerifiedPortableAuthorityAndMissingAuthorityFailsClosed() {
        for (generation in listOf(2L, 3L)) {
            val base = separation("tWEd6wxP6eM")
            val job = base.copy(jobId = app.stemwise.integration.sourceSeparationJobId(
                base.sourceVersionId!!, base.sourceSha256!!, "b".repeat(64), generation))
            for (state in listOf("CREATED", "PROCESSING", "PUBLISHING")) {
                val current = job.copy(state = state, remoteJobId = job.remoteJobId.takeUnless { state == "CREATED" })
                assertTrue(app.stemwise.data.arbitratePreparationOwner(root, listOf(current),
                    mapOf(job.jobId to generation)) is PreparationOwnerDecision.Separation)
            }
            for (generations in listOf(emptyMap(), mapOf(job.jobId to 1L), mapOf(job.jobId to 0L))) {
                assertTrue(app.stemwise.data.arbitratePreparationOwner(root, listOf(job), generations)
                    is PreparationOwnerDecision.Blocked)
            }
        }
    }

    @Test
    fun unknownSubmissionExposesOnlyItsExactRecoveryOwnerAndConflictsCannotRecover() {
        for (state in listOf("SUBMISSION_STARTED", "SUBMISSION_UNKNOWN")) {
            val unknown = separation("tWEd6wxP6eM").copy(state = state, remoteJobId = null)
            val decision = arbitratePreparationOwner(root, listOf(queued("F84DytTL_j0", 10), unknown))
                as PreparationOwnerDecision.Blocked
            assertEquals(unknown.jobId, decision.recoverableJobId)
            org.junit.Assert.assertFalse(preparationBlocksRoot(decision))
            val conflict = arbitratePreparationOwner(root, listOf(separation("F84DytTL_j0"), unknown))
                as PreparationOwnerDecision.Blocked
            assertEquals("PREPARATION_OWNER_CONFLICT", conflict.code)
            assertTrue(preparationBlocksRoot(conflict))
            assertEquals(null, conflict.recoverableJobId)
        }
    }

    private fun queued(videoId: String, created: Long) = separation(videoId).let { base ->
        val planHash = "b".repeat(64)
        base.copy(jobId = app.stemwise.integration.sourceSeparationJobId(
            base.sourceVersionId!!, base.sourceSha256!!, planHash), kind = "SOURCE_SELECTION",
            modelId = "source-plan:$planHash", state = "CREATED", remoteJobId = null,
            createdAtEpochMs = created)
    }

    @Test
    fun queuedSelectionsWaitBehindTheExactActiveOwnerThenAdvanceInOrder() {
        val active = separation("tWEd6wxP6eM")
        val first = queued("F84DytTL_j0", 10L)
        val second = queued("abcdefghijk", 20L)
        val running = arbitratePreparationOwner(root, listOf(second, active, first))
        assertEquals(active.jobId, (running as PreparationOwnerDecision.Separation).jobId)
        val advanced = arbitratePreparationOwner(root, listOf(second, first))
        assertEquals(first.jobId, (advanced as PreparationOwnerDecision.Separation).jobId)
        assertEquals(second.jobId, (arbitratePreparationOwner(root, listOf(second)) as
            PreparationOwnerDecision.Separation).jobId)
    }

    @Test
    fun pendingQueueNeverHidesUnknownSubmissionOrInvalidIdentity() {
        val pending = queued("F84DytTL_j0", 10L)
        val unknown = separation("tWEd6wxP6eM").copy(state = "SUBMISSION_UNKNOWN", remoteJobId = null)
        assertEquals("PREPARATION_SUBMISSION_UNKNOWN", (arbitratePreparationOwner(root,
            listOf(pending, unknown)) as PreparationOwnerDecision.Blocked).code)
        assertTrue(arbitratePreparationOwner(root, listOf(pending,
            queued("abcdefghijk", 20L).copy(jobId = "forged"))) is PreparationOwnerDecision.Blocked)
        assertTrue(arbitratePreparationOwner(root, listOf(pending, pending)) is PreparationOwnerDecision.Blocked)
    }

    @Test
    fun selectedSourcePlanOwnsPreparationAndRejectsForgedIdentity() {
        val base = separation("tWEd6wxP6eM")
        val planHash = "b".repeat(64)
        val job = base.copy(
            jobId = app.stemwise.integration.sourceSeparationJobId(
                base.sourceVersionId!!, base.sourceSha256!!, planHash
            ),
            kind = "SOURCE_SELECTION", modelId = "source-plan:$planHash"
        )
        assertTrue(arbitratePreparationOwner(root, listOf(job)) is PreparationOwnerDecision.Separation)
        listOf(job.copy(jobId = "forged"), job.copy(modelId = "source-plan:${"c".repeat(64)}"),
            job.copy(remoteJobId = null), job.copy(state = "MADE_UP")).forEach {
            assertEquals("PREPARATION_OWNER_IDENTITY_CONFLICT",
                (arbitratePreparationOwner(root, listOf(it)) as PreparationOwnerDecision.Blocked).code)
        }
        assertEquals("PREPARATION_SUBMISSION_UNKNOWN", (arbitratePreparationOwner(root,
            listOf(job.copy(state = "SUBMISSION_UNKNOWN", remoteJobId = null))) as
            PreparationOwnerDecision.Blocked).code)
    }
    @Test
    fun noDurableJobMeansNoAutomaticPreparationOwner() {
        assertEquals(PreparationOwnerDecision.None(root), arbitratePreparationOwner(root, emptyList()))
    }

    @Test
    fun unsupportedJobCannotAcquirePreparationOwnership() {
        val job = unsupportedJob("tWEd6wxP6eM")

        val decision = arbitratePreparationOwner(root, listOf(job))

        assertTrue(decision is PreparationOwnerDecision.Blocked)
        assertEquals("PREPARATION_OWNER_IDENTITY_CONFLICT",
            (decision as PreparationOwnerDecision.Blocked).code)
    }

    @Test
    fun oneRemoteSeparationOwnerWinsAndPreservesExactRemoteJob() {
        val job = separation("tWEd6wxP6eM")

        val decision = arbitratePreparationOwner(root, listOf(job))

        assertTrue(decision is PreparationOwnerDecision.Separation)
        decision as PreparationOwnerDecision.Separation
        assertEquals(job.jobId, decision.jobId)
        assertEquals(job.songId, decision.songId)
        assertEquals("PROCESSING", decision.state)
    }

    @Test
    fun exactCreatedSeparationOwnerRemainsResumableWithoutRemoteIdentity() {
        val job = separation("tWEd6wxP6eM").copy(
            state = "CREATED",
            remoteJobId = null
        )

        val decision = arbitratePreparationOwner(root, listOf(job))

        assertTrue(decision is PreparationOwnerDecision.Separation)
        assertEquals("CREATED", (decision as PreparationOwnerDecision.Separation).state)
    }

    @Test
    fun foreignSeparationJobSourceAndPlanIdentitiesFailClosed() {
        val valid = separation("tWEd6wxP6eM")
        listOf(
            valid.copy(jobId = "foreign-job"),
            valid.copy(sourceSha256 = "f".repeat(64)),
            valid.copy(currentSourceSha256 = "f".repeat(64)),
            valid.copy(sourceSongId = "F84DytTL_j0"),
            valid.copy(modelId = "64"),
            valid.copy(remoteJobId = null),
            valid.copy(state = "UNRECOGNIZED")
        ).forEach { malformed ->
            val decision = arbitratePreparationOwner(root, listOf(malformed))
            assertEquals(
                "PREPARATION_OWNER_IDENTITY_CONFLICT",
                (decision as PreparationOwnerDecision.Blocked).code
            )
        }
    }

    @Test
    fun multipleOwnersAndUnknownSubmissionFailClosedDeterministically() {
        val conflict = arbitratePreparationOwner(
            root,
            listOf(separation("F84DytTL_j0"), separation("tWEd6wxP6eM"))
        )
        val unknown = arbitratePreparationOwner(
            root,
            listOf(
                separation("tWEd6wxP6eM").copy(
                    state = "SUBMISSION_UNKNOWN",
                    remoteJobId = null
                )
            )
        )

        assertEquals("PREPARATION_OWNER_CONFLICT", (conflict as PreparationOwnerDecision.Blocked).code)
        assertEquals(
            listOf("F84DytTL_j0", "tWEd6wxP6eM"),
            conflict.ownerVideoIds
        )
        assertEquals(
            "PREPARATION_SUBMISSION_UNKNOWN",
            (unknown as PreparationOwnerDecision.Blocked).code
        )
    }

    @Test
    fun repeatedWakeIsStableAndRootSwitchTreatsOldOwnerAsForeign() {
        val job = unsupportedJob("tWEd6wxP6eM")
        val first = arbitratePreparationOwner(root, listOf(job))
        val second = arbitratePreparationOwner(root, listOf(job))
        val otherRoot = root.copy(libraryId = "library-b", epoch = 8L)
        val switched = arbitratePreparationOwner(otherRoot, listOf(job))

        assertEquals(first, second)
        assertEquals(
            "PREPARATION_OWNER_LIBRARY_CONFLICT",
            (switched as PreparationOwnerDecision.Blocked).code
        )
    }

    private fun unsupportedJob(videoId: String) = PreparationJobFact(
        jobId = "source-$videoId",
        songId = videoId,
        videoId = videoId,
        sourceVersionId = null,
        sourceSongId = null,
        sourceSha256 = null,
        currentSourceVersionId = null,
        currentSourceSha256 = null,
        kind = "UNSUPPORTED_KIND",
        provider = "UNSUPPORTED_PROVIDER",
        modelId = null,
        state = "CREATED",
        libraryId = root.libraryId,
        remoteJobId = null,
        nextAttemptAtEpochMs = 1234L,
        lastErrorCode = null,
        createdAtEpochMs = 30L
    )

    private fun separation(videoId: String): PreparationJobFact {
        val sourceId = "source-$videoId"
        val sourceSha256 = "a".repeat(64)
        return PreparationJobFact(
        jobId = app.stemwise.integration.sourceSeparationJobId(sourceId, sourceSha256, "b".repeat(64)),
        songId = videoId,
        videoId = videoId,
        sourceVersionId = sourceId,
        sourceSongId = videoId,
        sourceSha256 = sourceSha256,
        currentSourceVersionId = sourceId,
        currentSourceSha256 = sourceSha256,
        kind = "SOURCE_SELECTION",
        provider = "MVSEP",
        modelId = "source-plan:${"b".repeat(64)}",
        state = "PROCESSING",
        libraryId = root.libraryId,
        remoteJobId = "remote-$videoId",
        nextAttemptAtEpochMs = 5678L,
        lastErrorCode = null,
        createdAtEpochMs = 40L
    )
    }
}
