package app.stemwise.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.playback.PlaybackQueueSnapshot
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.RepeatMode
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LastListeningSessionStoreInstrumentationTest {
    private val session = LastListeningSession(
        SongPlaybackSettingsIdentity("library-a", "content://fixture/root-a", "song-a", "a".repeat(64)),
        PlaybackQueueSnapshot(listOf("song-b", "song-a", "song-c"), 1, true, 736L, RepeatMode.ONE),
        15_234L, PlaybackSourceMode.STEMS
    )

    @Test fun fullLongShuffleSeedsSurviveTheActualAtomicStoreAndStrictParser() = withStore { file, store ->
        for (seed in listOf(Long.MIN_VALUE, -9_007_199_254_740_992L, 0L, 9_007_199_254_740_992L, Long.MAX_VALUE)) {
            val expected = session.copy(queue = session.queue.copy(shuffleSeed = seed))
            store.write(expected)
            assertEquals(expected, LastListeningSessionStore(file).read())
        }
    }

    @Test fun restartPreservesExactRootAudioQueueAndPositionAndExplicitClearRemovesIt() = withStore { file, store ->
        assertNull(store.read())
        store.write(session)
        assertEquals(session, LastListeningSessionStore(file).read())
        val replacement = session.copy(identity = session.identity.copy(libraryId = "library-b",
            treeUri = "content://fixture/root-b", originalSha256 = "b".repeat(64)), sourcePositionMs = 0L)
        store.write(replacement)
        assertEquals(replacement, LastListeningSessionStore(file).read())
        store.clear()
        assertNull(LastListeningSessionStore(file).read())
    }

    @Test fun failedReplacementPreservesThePreviousDurableSession() = withStore { file, store ->
        store.write(session)
        val priorBytes = file.readBytes()
        val pending = File(file.parentFile, "session-position.json.new").apply { check(mkdir()) }
        val blocker = File(pending, "block").apply { writeText("block", Charsets.UTF_8) }
        try {
            assertThrows(Exception::class.java) { store.write(session.copy(sourcePositionMs = 20_000L)) }
            org.junit.Assert.assertArrayEquals(priorBytes, file.readBytes())
            assertThrows(Exception::class.java) { LastListeningSessionStore(file).read() }
        } finally {
            check(blocker.delete())
            check(pending.delete())
        }
        assertEquals(session, LastListeningSessionStore(file).read())
    }

    @Test fun interruptedReplacementRecoversPriorCommittedRowAndDropsOnlyUncommittedBytes() = withStore { file, store ->
        store.write(session)
        val backup = File(file.path + ".bak")
        check(file.renameTo(backup))
        file.writeText("partial", Charsets.UTF_8)
        assertEquals(session, LastListeningSessionStore(file).read())
        assertTrue(!backup.exists())
        store.clear()
        File(file.path + ".new").writeText("partial", Charsets.UTF_8)
        assertNull(LastListeningSessionStore(file).read())
        assertTrue(!File(file.path + ".new").exists())
    }

    @Test fun malformedRetainedRowsAreQuarantinedAndTwoLaunchesCanSaveFreshSessions() = withStore { file, store ->
        for (invalid in listOf("{", JSONObject().put("storageVersion", 1).put("revision", "a".repeat(32))
            .put("session", lastListeningSessionJson(session).put("sourcePositionMs", "123")).toString())) {
            file.writeText(invalid, Charsets.UTF_8)
            assertNull(LastListeningSessionStore(file).read())
            assertTrue(file.parentFile!!.listFiles()!!.any { it.name.startsWith("session.json.invalid-") &&
                it.readText(Charsets.UTF_8) == invalid })
            LastListeningSessionStore(file).write(session)
            assertEquals(session, LastListeningSessionStore(file).read())
        }
    }

    @Test fun positionTicksDoNotRewriteQueueAndObsoleteRevisionCannotMoveANewerQueue() = withStore { file, store ->
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val order = object : AbstractList<String>() {
            override val size: Int get() = 10_000
            override fun get(index: Int): String { reads.incrementAndGet(); return if (index == 0) "song-a" else "song-$index" }
        }
        val large = session.copy(queue = PlaybackQueueSnapshot(order, 0))
        store.write(large)
        val baseline = file.readBytes()
        reads.set(0)
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        var sampledPeakHeap = heapBefore
        repeat(3) {
            store.write(large.copy(sourcePositionMs = 20_000L + it))
            sampledPeakHeap = maxOf(sampledPeakHeap, runtime.totalMemory() - runtime.freeMemory())
        }
        val tickReads = reads.get()
        assertTrue("Position tick walked the retained queue: $tickReads", tickReads <= 6)
        org.junit.Assert.assertArrayEquals(baseline, file.readBytes())
        val checkpoint = File(file.parentFile, "session-position.json")
        val oldPosition = checkpoint.readBytes()
        assertTrue("Position checkpoint grew with the 10,000-song queue", oldPosition.size in 1..512)
        android.util.Log.i("StemwiseSessionScale", "queue=10000 ticks=3 queueReads=$tickReads checkpointBytes=${oldPosition.size}" +
            " queueBytes=${baseline.size} heapBefore=$heapBefore sampledPeakHeapBytes=$sampledPeakHeap")
        assertEquals(20_002L, LastListeningSessionStore(file).read()?.sourcePositionMs)
        val replacement = session.copy(sourcePositionMs = 42L)
        store.write(replacement)
        checkpoint.writeBytes(oldPosition)
        assertEquals(replacement, LastListeningSessionStore(file).read())
    }

    private fun withStore(block: (File, LastListeningSessionStore) -> Unit) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "listening-session-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { block(File(directory, "session.json"), LastListeningSessionStore(File(directory, "session.json"))) }
        finally { check(directory.deleteRecursively()) }
    }
}
