package app.stemwise.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdOpenCoordinatorPolicyTest {
    @Test
    fun damagedSongRecoveryCanBeReadyForPlaybackWhileEveryProducerRemainsClosed() {
        val authority = ColdOpenSessionAuthority()
        val root = RootAuthority("library", "content://provider/tree/library", 1L)
        val session = authority.begin("playback-only") { }
        val ready = session.ready(root).copy(preparationBlocked = true)
        assertFalse(authority.admits(ready, root))
        assertEquals(ColdOpenProducerAdmission.RETRY, authority.producerAdmission({ ready }, root))
        assertEquals(ColdOpenProducerAdmission.COMPLETE,
            authority.producerAdmission({ ready }, root.copy(epoch = 2L)))
        assertTrue(authority.admits(ready.copy(preparationBlocked = false), root))
    }

    @Test
    fun `cold open session is local only`() {
        val session = coldOpenSession("test-session")

        assertEquals("test-session", session.id)
        assertTrue(session.localStarted)
        assertFalse(session.remoteStarted)
    }

    @Test
    fun `cancelAndJoin synchronously closes isProducerAdmissionReady across root replacement`() {
        val authority = ColdOpenSessionAuthority()
        var state: ColdOpenState = ColdOpenState.Idle
        val rootA = RootAuthority("library-a", "content://provider/tree/a", 1L)
        val rootB = RootAuthority("library-b", "content://provider/tree/b", 2L)

        val sessionA = authority.begin("session-a") {
            state = ColdOpenState.Recovering("Opening Library")
        }
        assertTrue(authority.publishIfActive(sessionA) {
            state = sessionA.ready(rootA)
        })
        assertTrue(authority.admits(state, rootA))

        // This is the non-suspending boundary used before the real cancellation join.
        authority.invalidate { state = ColdOpenState.Idle }
        assertFalse(authority.admits(state, rootA))

        val sessionB = authority.begin("session-b") {
            state = ColdOpenState.Recovering("Opening Library")
        }
        assertFalse(authority.admits(state, rootB))
        assertFalse(authority.publishIfActive(sessionA) {
            state = ColdOpenState.Blocked("STALE", "obsolete cancellation")
        })
        assertTrue(authority.publishIfActive(sessionB) {
            state = sessionB.ready(rootB)
        })
        assertFalse(authority.admits(state, rootA))
        assertTrue(authority.admits(state, rootB))
    }

    @Test
    fun cleanupRefusalBlocksAdmissionAndOnlyALaterExactRetryCanPublishReady() {
        val authority = ColdOpenSessionAuthority()
        var state: ColdOpenState = ColdOpenState.Idle
        val root = RootAuthority("library-b", "content://provider/tree/b", 2L)
        val refused = authority.begin("refused") {
            state = ColdOpenState.Recovering("Opening Library")
        }
        assertTrue(authority.publishIfActive(refused) {
            state = ColdOpenState.Blocked("SCRATCH_CLEANUP_FAILED", "cleanup refused")
        })
        assertFalse(authority.admits(state, root))

        val retry = authority.begin("retry") {
            state = ColdOpenState.Recovering("Opening Library")
        }
        assertFalse(authority.admits(state, root))
        assertTrue(authority.publishIfActive(retry) { state = retry.ready(root) })
        assertTrue(authority.admits(state, root))
    }

    @Test
    fun producerAdmissionCannotMixReadyStateWithALaterInvalidation() {
        val authority = ColdOpenSessionAuthority()
        val root = RootAuthority("library-a", "content://provider/tree/a", 1L)
        val session = authority.begin("session-a") { Unit }
        var state: ColdOpenState = session.ready(root)
        val stateReadStarted = CountDownLatch(1)
        val invalidationAttempted = CountDownLatch(1)
        var admission: ColdOpenProducerAdmission? = null

        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val reader = Thread {
            admission = authority.producerAdmission(
                state = {
                    stateReadStarted.countDown()
                    assertTrue(invalidationAttempted.await(5, TimeUnit.SECONDS))
                    state
                }, expectedRoot = root)
        }.apply { isDaemon = true; setUncaughtExceptionHandler { _, error -> failure.set(error) } }
        val invalidator = Thread {
            invalidationAttempted.countDown()
            authority.invalidate { state = ColdOpenState.Idle }
        }.apply { isDaemon = true; setUncaughtExceptionHandler { _, error -> failure.set(error) } }
        try {
            reader.start()
            assertTrue("Reader did not enter admission", stateReadStarted.await(5, TimeUnit.SECONDS))
            invalidator.start()
            reader.join(5_000L)
            invalidator.join(5_000L)
            assertFalse(reader.isAlive)
            assertFalse(invalidator.isAlive)
            failure.get()?.let { throw AssertionError("Admission worker failed", it) }
            assertEquals(ColdOpenProducerAdmission.RUN, admission)
            assertEquals(ColdOpenProducerAdmission.RETRY,
                authority.producerAdmission(state = { state }, expectedRoot = root))
        } finally {
            invalidationAttempted.countDown()
            reader.interrupt()
            invalidator.interrupt()
            reader.join(1_000L)
            invalidator.join(1_000L)
            assertFalse("Reader survived cleanup", reader.isAlive)
            assertFalse("Invalidator survived cleanup", invalidator.isAlive)
        }
    }

    @Test
    fun producerAdmissionCompletesBlockedAndStaleReadyStates() {
        val authority = ColdOpenSessionAuthority()
        val root = RootAuthority("library-a", "content://provider/tree/a", 1L)
        val session = authority.begin("session-a") { Unit }

        assertEquals(
            ColdOpenProducerAdmission.COMPLETE,
            authority.producerAdmission(
                state = { ColdOpenState.Blocked("ROOT_PERMISSION_LOST", "Reconnect") },
                expectedRoot = root
            )
        )
        assertEquals(
            ColdOpenProducerAdmission.COMPLETE,
            authority.producerAdmission(
                state = { session.ready(RootAuthority("library-b", "content://b", 2L)) },
                expectedRoot = root
            )
        )
    }

    @Test
    fun synchronousStartupFailureBlocksAndInvalidatesTheRegisteredSession() {
        val authority = ColdOpenSessionAuthority()
        var state: ColdOpenState = ColdOpenState.Idle
        val root = RootAuthority("library-a", "content://provider/tree/a", 1L)
        val registered = authority.begin("startup") {
            state = ColdOpenState.Recovering("Opening Library")
        }
        val diagnostic = coldOpenFailureDiagnostic(
            ColdOpenStage.ROOT_SESSION,
            IllegalStateException("unexpected startup failure")
        )

        authority.invalidate {
            state = ColdOpenState.Blocked(diagnostic.code, diagnostic.message)
        }

        assertTrue(state is ColdOpenState.Blocked)
        assertFalse(authority.admits(state, root))
        assertFalse(authority.publishIfActive(registered) { state = registered.ready(root) })
    }

    @Test
    fun oldNullCaptureCannotClearAReplacementRegistration() {
        val lock = Any()
        val captured = CountDownLatch(1)
        val replacementRegistered = CountDownLatch(1)
        var owner: ColdOpenSessionAuthority.Token? = null
        var job: Job? = null
        var started = false
        var capturedOwner: ColdOpenSessionAuthority.Token? = null
        var capturedJob: Job? = null
        val replacementOwner = ColdOpenSessionAuthority.Token("replacement", 2L)
        val replacementJob = Job()

        val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val obsoleteCleanup = Thread {
            synchronized(lock) {
                capturedOwner = owner
                capturedJob = job
            }
            captured.countDown()
            assertTrue(replacementRegistered.await(5, TimeUnit.SECONDS))
            synchronized(lock) {
                val released = releaseColdOpenOwnership(
                    owner,
                    job,
                    started,
                    capturedOwner,
                    capturedJob
                )
                owner = released.owner
                job = released.job
                started = released.started
            }
        }
        obsoleteCleanup.setUncaughtExceptionHandler { _, failure -> workerFailure.set(failure) }
        obsoleteCleanup.isDaemon = true
        try {
        obsoleteCleanup.start()
        assertTrue(captured.await(5, TimeUnit.SECONDS))
        synchronized(lock) {
            owner = replacementOwner
            job = replacementJob
            started = true
        }
        replacementRegistered.countDown()
        obsoleteCleanup.join(5_000L)
        assertFalse("Cleanup worker did not finish", obsoleteCleanup.isAlive)
        workerFailure.get()?.let { throw AssertionError("Cleanup worker failed", it) }

        assertEquals(replacementOwner, owner)
        assertSame(replacementJob, job)
        assertTrue(started)
        } finally {
            replacementRegistered.countDown()
            obsoleteCleanup.interrupt()
            obsoleteCleanup.join(1_000L)
            replacementJob.cancel()
            assertFalse("Cleanup worker survived cleanup", obsoleteCleanup.isAlive)
        }
    }

    @Test
    fun oldLiveCompletionAndOverlappingCleanupPreserveTheNewOwner() {
        val oldOwner = ColdOpenSessionAuthority.Token("old", 1L)
        val newOwner = ColdOpenSessionAuthority.Token("new", 2L)
        val oldJob = Job()
        val newJob = Job()
        var owner: ColdOpenSessionAuthority.Token? = oldOwner
        var job: Job? = oldJob
        var started = true
        val oldBoundaryOwner = owner
        val oldBoundaryJob = job

        val completed = releaseColdOpenOwnership(
            owner,
            job,
            started,
            oldOwner,
            oldJob
        )
        owner = completed.owner
        job = completed.job
        started = completed.started
        assertFalse(started)

        owner = newOwner
        job = newJob
        started = true
        repeat(2) {
            val obsolete = releaseColdOpenOwnership(
                owner,
                job,
                started,
                oldBoundaryOwner,
                oldBoundaryJob
            )
            owner = obsolete.owner
            job = obsolete.job
            started = obsolete.started
        }

        assertEquals(newOwner, owner)
        assertSame(newJob, job)
        assertTrue(started)
        oldJob.cancel()
        newJob.cancel()
    }

    private fun ColdOpenSessionAuthority.Token.ready(root: RootAuthority) =
        ColdOpenState.Ready(
            sessionId = sessionId,
            generation = generation,
            libraryId = root.libraryId,
            treeUri = root.treeUri,
            rootEpoch = root.epoch
        )
}
