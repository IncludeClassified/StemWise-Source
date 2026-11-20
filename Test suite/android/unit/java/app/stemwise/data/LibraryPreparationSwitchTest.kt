package app.stemwise.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class LibraryPreparationSwitchTest {
    @Test fun remoteBlockerRefusesBeforeWaitingForAnUnrelatedHeldPreparationLane() = runBlocking {
        val lane = Mutex()
        val entered = CompletableDeferred<Unit>()
        val worker = launch { lane.withLock { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        var stopped = false
        val switch = async {
            runCatching { retirePreparationForLibrarySwitch(lane, true, { true }, { stopped = true }) { error("Unexpected publication") } }
        }
        try {
            val failure = withTimeout(500L) { switch.await() }.exceptionOrNull()
            assertEquals(LIBRARY_SWITCH_REMOTE_SEPARATION_PENDING, (failure as? LibraryRootException)?.code)
            assertFalse(stopped)
            assertTrue(worker.isActive)
        } finally { switch.cancelAndJoin(); worker.cancelAndJoin() }
    }

    @Test fun localPreparationRetiresBeforeWaitingForItsPublicationLane() = runBlocking {
        val lane = Mutex()
        val entered = CompletableDeferred<Unit>()
        val worker = launch { lane.withLock { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        var published = false
        val switch = async {
            retirePreparationForLibrarySwitch(lane, true, { false }, { worker.cancelAndJoin() }) {
                assertFalse(worker.isActive)
                published = true
                LibraryRootState()
            }
        }
        try { withTimeout(500L) { switch.await() }; assertTrue(published) }
        finally { switch.cancelAndJoin(); worker.cancelAndJoin() }
    }

    @Test fun aRemoteAcknowledgementDuringRetirementRefusesPublicationWithoutDiscardingTheFact() = runBlocking {
        var acceptedRemote = false
        var published = false
        val failure = runCatching {
            retirePreparationForLibrarySwitch(Mutex(), true, { acceptedRemote }, { acceptedRemote = true }) {
                published = true
                LibraryRootState()
            }
        }.exceptionOrNull()
        assertEquals(LIBRARY_SWITCH_REMOTE_SEPARATION_PENDING, (failure as? LibraryRootException)?.code)
        assertTrue(acceptedRemote)
        assertFalse(published)
    }
    @Test fun aCreatedWriterHoldingTheLaneTimesOutWithoutPublishingAndTheRetainedStopCanFinish() = runBlocking {
        withTimeout(5_000L) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val retirement = LibraryOwnerRetirement(scope, 40L)
            val lane = Mutex()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = scope.launch {
                lane.withLock {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            var published = false
            var stopRequests = 0
            try {
                entered.await()
                val failure = runCatching {
                    retirePreparationForLibrarySwitch(lane, false, { false }, {
                        stopRequests++ // Scheduling cancellation does not release the running writer's lane.
                        worker.cancel()
                    }, retirement) { published = true; LibraryRootState() }
                }.exceptionOrNull()
                assertEquals(LIBRARY_OWNER_RETIREMENT_PENDING, (failure as? LibraryRootException)?.code)
                assertTrue(retirement.pending)
                assertFalse(worker.isCompleted)
                assertFalse(published)
                release.complete(Unit)
                worker.join()
                retirePreparationForLibrarySwitch(lane, false, { false }, {
                    error("The retained stop was restarted")
                }, retirement) { published = true; LibraryRootState() }
                assertTrue(published)
                assertEquals(1, stopRequests)
                assertFalse(retirement.pending)
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) { withTimeout(2_000L) { worker.join() } }
                scope.cancel()
            }
        }
    }

    @Test fun timeoutBeforeTheStopDispatcherRunsStillClosesAdmissionAndRetainsTheExactStop() = runBlocking {
        withTimeout(5_000L) {
            val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
            }
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val retirement = LibraryOwnerRetirement(scope, 40L)
            var admissionClosed = false
            var stopStarted = false
            var published = false
            try {
                val failure = runCatching {
                    retirePreparationForLibrarySwitch(Mutex(), false, { false }, { stopStarted = true },
                        retirement, closeAdmission = { admissionClosed = true }) {
                        published = true; LibraryRootState()
                    }
                }.exceptionOrNull()
                assertEquals(LIBRARY_OWNER_RETIREMENT_PENDING, (failure as? LibraryRootException)?.code)
                assertTrue(admissionClosed)
                assertTrue(retirement.pending)
                assertFalse(stopStarted)
                assertFalse(published)
                checkNotNull(queued.poll()).run()
                assertTrue(stopStarted)
                retirePreparationForLibrarySwitch(Mutex(), false, { false }, {
                    error("The exact retained stop was replaced")
                }, retirement) { published = true; LibraryRootState() }
                assertTrue(published)
                assertFalse(retirement.pending)
            } finally { scope.cancel(); queued.clear() }
        }
    }

}
