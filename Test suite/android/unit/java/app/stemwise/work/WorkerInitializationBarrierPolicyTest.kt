package app.stemwise.work

import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerInitializationBarrierPolicyTest {
    @Test
    fun nullRootRetriesOnlyWhileRootRecoveryCanStillProgress() {
        val expected = WorkerRootExpectation(
            libraryId = "library-a",
            treeUriIdentity = workRootTreeUriIdentity("content://provider/tree/a"),
            epoch = 7L
        )

        assertEquals(
            WorkerRootAdmission.RETRY,
            admission(expected, "library-a", "content://provider/tree/a", 7L, "ROOT_RESTORING")
        )
        assertEquals(
            WorkerRootAdmission.COMPLETE,
            admission(expected, null, null, 0L, "ROOT_UNAVAILABLE")
        )
        assertEquals(
            WorkerRootAdmission.COMPLETE,
            admission(expected, "library-a", null, 7L, "ROOT_IDENTITY_UNVERIFIED")
        )
        assertEquals(
            WorkerRootAdmission.COMPLETE,
            admission(expected, "library-a", "content://provider/tree/a", 7L,
                "ROOT_PERMISSION_LOST")
        )
    }

    @Test
    fun nullRootCompletesOnlyForDifferentOrProvenAbsentAuthority() {
        val expected = WorkerRootExpectation("library-a")

        assertEquals(
            WorkerRootAdmission.COMPLETE,
            admission(expected, "library-b", "content://provider/tree/b", 9L, "ROOT_RESTORING")
        )
        assertEquals(
            WorkerRootAdmission.COMPLETE,
            admission(expected, null, null, 0L, null)
        )
        assertEquals(
            WorkerRootAdmission.RUN,
            workerRootAdmission(
                expected = expected,
                liveRoot = RootWorkIdentity("library-a", "content://provider/tree/a", 7L),
                configuredLibraryId = "library-a",
                configuredTreeUri = "content://provider/tree/a",
                configuredEpoch = 7L,
                configuredErrorCode = null
            )
        )
    }

    @Test
    fun malformedWorkIdentityIsTerminalBeforeRootClassification() {
        listOf(
            WorkerRootExpectation(""),
            WorkerRootExpectation("library-a", "", 7L),
            WorkerRootExpectation("library-a", workRootTreeUriIdentity("content://provider/tree/a"), -1L),
            WorkerRootExpectation("library-a", workRootTreeUriIdentity("content://provider/tree/a"), null)
        ).forEach { malformed ->
            assertFalse(malformed.valid)
            assertEquals(
                WorkerRootAdmission.COMPLETE,
                admission(
                    malformed,
                    "library-a",
                    null,
                    0L,
                    "ROOT_IDENTITY_UNVERIFIED"
                )
            )
        }
        assertTrue(WorkerRootExpectation("library-a").valid)
        assertTrue(
            WorkerRootExpectation(
                "library-a",
                workRootTreeUriIdentity("content://provider/tree/a"),
                7L
            ).valid
        )
    }

    @Test
    fun exactTreeIdentityRejectsAnotherUriAtTheSameLibraryAndEpoch() {
        val expected = WorkerRootExpectation(
            "library-a",
            workRootTreeUriIdentity("content://provider/tree/a"),
            7L
        )

        assertEquals(
            WorkerRootAdmission.COMPLETE,
            workerRootAdmission(
                expected = expected,
                liveRoot = RootWorkIdentity("library-a", "content://provider/tree/b", 7L),
                configuredLibraryId = "library-a",
                configuredTreeUri = "content://provider/tree/b",
                configuredEpoch = 7L,
                configuredErrorCode = null
            )
        )
    }

    @Test fun initializationWaitersSuspendUntilCompletionAndCancellationRetiresIndependently() = kotlinx.coroutines.runBlocking {
        val barrier = WorkerInitializationBarrier()
        val cancelled = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { barrier.await() }
        val waiting = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { barrier.await() }
        assertFalse(waiting.isCompleted)
        cancelled.cancel()
        kotlinx.coroutines.withTimeout(1_000L) { cancelled.join() }
        assertFalse(waiting.isCompleted)
        barrier.complete()
        kotlinx.coroutines.withTimeout(1_000L) { waiting.await() }
    }

    private fun admission(
        expected: WorkerRootExpectation,
        libraryId: String?,
        treeUri: String?,
        epoch: Long,
        errorCode: String?
    ): WorkerRootAdmission = workerRootAdmission(
        expected = expected,
        liveRoot = null,
        configuredLibraryId = libraryId,
        configuredTreeUri = treeUri,
        configuredEpoch = epoch,
        configuredErrorCode = errorCode
    )

}
