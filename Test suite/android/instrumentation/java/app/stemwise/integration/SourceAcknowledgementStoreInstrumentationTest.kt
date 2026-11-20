package app.stemwise.integration

import androidx.test.core.app.ApplicationProvider
import app.stemwise.data.RootAuthority
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAcknowledgementStoreInstrumentationTest {
    @Test
    fun interruptedWorkerReadsTheRetainedReceiptWithoutChangingItsInterruptOrBytes() = runBlocking {
        val directory = directory()
        val reader = Executors.newSingleThreadExecutor { command ->
            Thread(command, "source-acknowledgement-reader-test").apply { isDaemon = true }
        }
        try {
            val started = started()
            val acknowledged = transitionPortableSourceJob(started, SourceJobTransition.ACKNOWLEDGE_REMOTE, NOW, "interrupted-reader-remote")
            val store = SourceAcknowledgementStore(directory)
            assertTrue(runCatching { store.settle(ROOT, acknowledged) { throw IOException("provider unavailable") } }
                .exceptionOrNull() is IOException)
            val receipt = directory.listFiles()!!.single()
            val retained = receipt.readBytes()
            val observed = reader.submit(Callable {
                Thread.currentThread().interrupt()
                store.read(ROOT, started) to Thread.currentThread().isInterrupted
            }).get(5L, TimeUnit.SECONDS)
            assertEquals(acknowledged, observed.first)
            assertTrue("Receipt recovery must not consume a worker interrupt", observed.second)
            assertArrayEquals(retained, receipt.readBytes())
        } finally {
            reader.shutdownNow()
            assertTrue("Receipt reader did not retire", reader.awaitTermination(5L, TimeUnit.SECONDS))
            check(directory.deleteRecursively())
        }
    }

    @Test
    fun emptyOversizedAndMalformedReceiptsRemainRejectedAndRetained() = runBlocking {
        val directory = directory()
        try {
            val started = started()
            val acknowledged = transitionPortableSourceJob(started, SourceJobTransition.ACKNOWLEDGE_REMOTE, NOW, "invalid-receipt-remote")
            val store = SourceAcknowledgementStore(directory)
            assertTrue(runCatching { store.settle(ROOT, acknowledged) { throw IOException("provider unavailable") } }
                .exceptionOrNull() is IOException)
            val receipt = directory.listFiles()!!.single()
            for (invalid in listOf(ByteArray(0), ByteArray((PORTABLE_SOURCE_JOB_MAX_BYTES + 8193L).toInt()),
                    "{".toByteArray(Charsets.UTF_8))) {
                receipt.writeBytes(invalid)
                assertTrue("Invalid receipt must withhold recovery", runCatching { store.read(ROOT, started) }.isFailure)
                assertArrayEquals(invalid, receipt.readBytes())
            }
        } finally { check(directory.deleteRecursively()) }
    }

    @Test
    fun publicationDeadlineRetainsTheReceiptAndCanReplayWithoutCancellingItsCaller() = runBlocking {
        val directory = directory()
        try {
            val started = started()
            val acknowledged = transitionPortableSourceJob(started, SourceJobTransition.ACKNOWLEDGE_REMOTE, NOW, "deadline-remote")
            val store = SourceAcknowledgementStore(directory, publicationTimeoutMs = 50L)
            val failed = runCatching {
                withTimeout(15_000L) { store.settle(ROOT, acknowledged) { CompletableDeferred<Unit>().await() } }
            }
            assertTrue("A publication deadline must be retryable, not caller cancellation", failed.exceptionOrNull() is IOException)
            assertEquals(acknowledged, store.read(ROOT, started))
            assertEquals("deadline-remote", store.settle(ROOT, checkNotNull(store.read(ROOT, started))) {
                acknowledged.currentRemoteJobId
            })
            assertNull(store.read(ROOT, started))
        } finally { check(directory.deleteRecursively()) }
    }

    @Test
    fun providerFailureAndRootReplacementRetainTheExactReceivedIdentityForRestart() = runBlocking {
        val directory = directory()
        try {
            val started = started()
            val acknowledged = transitionPortableSourceJob(started, SourceJobTransition.ACKNOWLEDGE_REMOTE, NOW, "accepted-remote")
            val store = SourceAcknowledgementStore(directory)
            val failed = runCatching { store.settle(ROOT, acknowledged) { throw IOException("provider unavailable") } }
            assertTrue(failed.exceptionOrNull() is IOException)
            val retained = directory.listFiles()!!.single().readBytes()
            val restarted = SourceAcknowledgementStore(directory)
            val recovered = restarted.read(ROOT.copy(epoch = ROOT.epoch + 1), started)
            assertNotNull(recovered)
            assertEquals("accepted-remote", recovered!!.currentRemoteJobId)
            assertTrue(!recovered.requiresSubmission)
            assertNull(restarted.read(ROOT.copy(treeUri = "content://fixture/tree/replacement"), started))
            assertArrayEquals(retained, directory.listFiles()!!.single().readBytes())
            assertEquals(acknowledged, restarted.read(ROOT, started))
            restarted.remove(ROOT, started.jobId)
            assertNull(restarted.read(ROOT, started))
        } finally { check(directory.deleteRecursively()) }
    }

    @Test
    fun orphanedReceiptIsIsolatedByLibraryAndExactTreeButSurvivesSessionReconnect() = runBlocking {
        val directory = directory()
        try {
            val started = started()
            val acknowledged = transitionPortableSourceJob(started, SourceJobTransition.ACKNOWLEDGE_REMOTE, NOW, "orphaned-remote")
            val store = SourceAcknowledgementStore(directory)
            assertTrue(runCatching { store.settle(ROOT, acknowledged) { throw IOException("publication interrupted") } }.isFailure)
            val originalReceipt = directory.listFiles()!!.single()
            val retained = originalReceipt.readBytes()
            for (otherRoot in listOf(ROOT.copy(libraryId = UUID.randomUUID().toString()),
                    ROOT.copy(treeUri = "content://fixture/tree/replacement"))) {
                assertNull(store.read(otherRoot, started))
                val otherStarted = started.copy(libraryId = otherRoot.libraryId)
                val otherAcknowledged = acknowledged.copy(libraryId = otherRoot.libraryId)
                assertTrue(runCatching { store.settle(otherRoot, otherAcknowledged) { throw IOException("other publication interrupted") } }.isFailure)
                assertEquals(otherAcknowledged, store.read(otherRoot, otherStarted))
                store.remove(otherRoot, started.jobId)
                assertNull(store.read(otherRoot, otherStarted))
                assertArrayEquals(retained, originalReceipt.readBytes())
            }
            val reconnected = ROOT.copy(epoch = ROOT.epoch + 1L)
            assertEquals(acknowledged, SourceAcknowledgementStore(directory).read(reconnected, started))
            store.settle(reconnected, acknowledged) { Unit }
            assertNull(store.read(ROOT, started))
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { check(directory.deleteRecursively()) }
    }

    @Test
    fun ownerCancellationCannotEraseAReceivedAcknowledgementBeforeItsPublicationFinishes() = runBlocking {
        val directory = directory()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val acknowledged = transitionPortableSourceJob(started(), SourceJobTransition.ACKNOWLEDGE_REMOTE, NOW, "cancelled-owner-remote")
        val store = SourceAcknowledgementStore(directory)
        var published: String? = null
        val owner = launch {
            store.settle(ROOT, acknowledged) {
                entered.complete(Unit)
                release.await()
                published = acknowledged.currentRemoteJobId
            }
        }
        try {
            withTimeout(5_000L) { entered.await() }
            assertEquals("cancelled-owner-remote", store.read(ROOT, started())!!.currentRemoteJobId)
            owner.cancel()
            release.complete(Unit)
            withTimeout(5_000L) { owner.join() }
            assertEquals("cancelled-owner-remote", published)
            assertNull(store.read(ROOT, started()))
        } finally {
            release.complete(Unit)
            owner.cancelAndJoin()
            check(directory.deleteRecursively())
        }
    }

    private fun directory(): File = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
        "source-acknowledgement-${UUID.randomUUID()}").also { check(it.mkdir()) }

    private fun started(): PortableSourceJobAuthority {
        val source = createPortableSourceJobAuthority(ROOT.libraryId, "AbCdEf_12-3", "AbCdEf_12-3:original:1111111111111111",
            "1".repeat(64), MvsepSourcePlan(listOf("bass"), listOf(MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), mapOf("bass" to "bass")))), NOW)
        return transitionPortableSourceJob(source, SourceJobTransition.ENTER_SUBMISSION, NOW)
    }

    private companion object {
        const val NOW = 1_780_000_000_000L
        val ROOT = RootAuthority("1e9167b7-fdb3-4a3c-92d4-d23433b4f29e", "content://fixture/tree/original", 1)
    }
}
