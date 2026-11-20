package app.stemwise.data

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConfirmedDeletionCancellationTest {
    @Test
    fun cancellationAfterDurableDeletionStillSettlesTheCommittedProjection() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val projectionRemoved = AtomicBoolean()
        val owner = launch {
            completePermanentDeletion(
                songWorkCoordinator = SongWorkCoordinator(),
                packageId = "AbCdEf_12-3",
                deleteCanonicalPackage = { onCommitted ->
                    withContext(Dispatchers.IO) {
                        withContext(NonCancellable) {
                            onCommitted()
                            committed.complete(Unit)
                            release.await()
                            Result.success(Unit)
                        }
                    }
                },
                removeCommittedProjection = { projectionRemoved.set(true) }
            )
        }
        try {
            withTimeout(5_000L) { committed.await() }
            owner.cancel(CancellationException("Activity recreated after durable deletion"))
            release.complete(Unit)
            withTimeout(5_000L) { owner.join() }
            org.junit.Assert.assertTrue(projectionRemoved.get())
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) {
                withTimeout(5_000L) { owner.cancelAndJoin() }
            }
        }
    }

    @Test
    fun cancellationBeforeDurableIntentRetiresWhileWaitingForSong() =
        exerciseCancellation(blockSameSong = true)

    @Test
    fun cancellationBeforeDurableIntentRetiresWhileWaitingForPreparation() =
        exerciseCancellation(blockSameSong = false)

    @Test
    fun changedRootCannotReviveCancelledUncommittedDeletion() =
        exerciseCancellation(blockSameSong = false, changeRoot = true)

    private fun exerciseCancellation(blockSameSong: Boolean, changeRoot: Boolean = false) = runBlocking {
        withTimeout(5_000L) {
            val coordinator = SongWorkCoordinator()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val epoch = AtomicLong(1L)
            val deleted = AtomicBoolean()
            val projectionChanged = AtomicBoolean()
            val rejected = AtomicReference<Throwable?>()
            val publisher = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withSong(if (blockSameSong) "AbCdEf_12-3" else "ZYX987abc_-") {
                    coordinator.preparationLane.withLock {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            }
            entered.await()
            val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                completePermanentDeletion(
                    songWorkCoordinator = coordinator,
                    packageId = "AbCdEf_12-3",
                    deleteCanonicalPackage = { onCommitted ->
                        runCatching {
                            requireRootAuthority(
                                currentReady = true,
                                currentLibraryId = "library",
                                currentTreeUri = "content://provider/tree/library",
                                currentEpoch = epoch.get(),
                                expectedLibraryId = "library",
                                expectedTreeUri = "content://provider/tree/library",
                                expectedEpoch = 1L
                            )
                            deleted.set(true)
                            onCommitted()
                        }.onFailure(rejected::set)
                    },
                    removeCommittedProjection = { projectionChanged.set(true) }
                )
            }
            try {
                owner.cancel(CancellationException("Activity recreated"))
                if (changeRoot) epoch.set(2L)
                withTimeout(1_000L) { owner.join() }
                assertFalse(deleted.get())
                assertFalse(projectionChanged.get())
                assertEquals(null, rejected.get())
                release.complete(Unit)
                publisher.await()
            } finally {
                release.complete(Unit)
                owner.cancelAndJoin()
                publisher.cancelAndJoin()
            }
        }
    }
}
