package app.stemwise.playback

import app.stemwise.data.SongPlaybackSettings
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.data.SongTrimRange
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

internal class PlaybackPreferencesPersistenceInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun committedTrimRetiresFailedSettingsBeforeSameProcessCacheInvalidation() = runBlocking {
        withTimeout(10_000L) {
            StandardInstrumentationTargetGuard.requireCurrentTarget()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val identity = SongPlaybackSettingsIdentity("library", "content://fixture/root", "song", "a".repeat(64))
            val failures = mutableListOf<Throwable>()
            val owner = PlaybackPreferencesPersistence(context.filesDir, scope, failures::add, { throw it })
            val directory = File(context.filesDir, "song-playback-settings")
            val retained = File(context.filesDir, "retained-settings")
            val store = SongPlaybackSettingsStore(directory)
            val oldTrim = SongTrimRange(1_000L, 9_000L)
            val newTrim = SongTrimRange(2_000L, 8_000L)
            try {
                store.update(identity, 10_000L) { SongPlaybackSettings(trim = oldTrim) }
                assertEquals(oldTrim, owner.readSettings(identity, 10_000L, false, {}).trim)
                assertTrue(directory.renameTo(retained))
                directory.writeText("storage unavailable", Charsets.UTF_8)
                owner.persistSettings(identity, 10_000L, SongPlaybackSettings(trim = oldTrim, tempo = 1.25f))
                writer(owner).join()
                assertEquals(1, failures.size)
                assertTrue(runCatching { owner.writeTrim(identity, 10_000L, newTrim, {}, {}) }.isFailure)
                assertEquals(oldTrim, owner.cachedSettings(identity)?.getOrThrow()?.trim)
                assertEquals(oldTrim, SongPlaybackSettingsStore(retained).read(identity, 10_000L)?.trim)
                assertTrue(directory.delete())
                assertTrue(retained.renameTo(directory))
                owner.writeTrim(identity, 10_000L, newTrim, {}, {})
                assertEquals(newTrim, store.read(identity, 10_000L)?.trim)
                owner.savedSettings.clear() // The same invalidation used by library away/back.
                assertEquals(newTrim, owner.readSettings(identity, 10_000L, false, {}).trim)
                assertEquals(1.25f, owner.cachedSettings(identity)?.getOrThrow()?.tempo)
                assertEquals(newTrim, PlaybackPreferencesPersistence(context.filesDir, scope, { throw it }, { throw it })
                    .readSettings(identity, 10_000L, false, {}).trim)
                assertEquals(newTrim, owner.readTrim(identity, 10_000L))
                owner.persistSettings(identity, 10_000L, SongPlaybackSettings(tempo = 1.5f))
                writer(owner).join()
                owner.savedSettings.clear()
                assertEquals(SongPlaybackSettings(trim = newTrim, tempo = 1.5f),
                    owner.readSettings(identity, 10_000L, false, {}))
            } finally { scope.cancel(); scope.coroutineContext[Job]?.join() }
        }
    }

    @Test fun trimCommitRetiresOnlyItsConsumedPendingRevision() = overlappingTrim(newerWriteFails = false)

    @Test fun failedNewerSettingsCannotRestoreAnOldTrimAfterCacheInvalidation() = overlappingTrim(newerWriteFails = true)

    private fun overlappingTrim(newerWriteFails: Boolean) = runBlocking {
        withTimeout(10_000L) {
            StandardInstrumentationTargetGuard.requireCurrentTarget()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val failures = mutableListOf<Throwable>()
            val owner = PlaybackPreferencesPersistence(context.filesDir, scope, failures::add, { throw it })
            val identity = SongPlaybackSettingsIdentity("library", "content://fixture/root", "song", "b".repeat(64))
            val directory = File(context.filesDir, "song-playback-settings")
            val retained = File(context.filesDir, "retained-settings")
            val store = SongPlaybackSettingsStore(directory)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val futureWrite = CompletableDeferred<Unit>()
            val trim = SongTrimRange(2_000L, 8_000L)
            val oldTrim = SongTrimRange(1_000L, 9_000L)
            try {
                store.update(identity, 10_000L) { SongPlaybackSettings(trim = oldTrim) }
                owner.readSettings(identity, 10_000L, false, {})
                // Preserve a failed pending edit, then pause the trim after it captures that revision.
                owner.persistSettings(identity, 0L, SongPlaybackSettings(trim = oldTrim, tempo = 1.25f))
                writer(owner).join()
                val edit = async(Dispatchers.Default) {
                    owner.writeTrim(identity, 10_000L, trim, {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }, {})
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // Keep the newer persistence queued so its in-memory survival is directly observable.
                writerField(owner).set(owner, futureWrite)
                owner.persistSettings(identity, 10_000L, SongPlaybackSettings(trim = oldTrim, tempo = 1.5f))
                release.countDown()
                edit.await()
                assertEquals(1.5f, owner.cachedSettings(identity)?.getOrThrow()?.tempo)
                assertEquals(trim, owner.cachedSettings(identity)?.getOrThrow()?.trim)
                if (newerWriteFails) {
                    assertTrue(directory.renameTo(retained))
                    directory.writeText("storage unavailable", Charsets.UTF_8)
                }
                futureWrite.complete(Unit)
                writer(owner).join()
                owner.savedSettings.clear()
                if (newerWriteFails) {
                    assertEquals(2, failures.size)
                    assertEquals(trim, SongPlaybackSettingsStore(retained).read(identity, 10_000L)?.trim)
                    assertNull("Pending controls cannot establish a durable trim", owner.cachedSettings(identity))
                    assertTrue(directory.delete())
                    assertTrue(retained.renameTo(directory))
                    val row = directory.listFiles().orEmpty().single { it.extension == "json" }
                    val durableBytes = row.readBytes()
                    try {
                        row.writeText("{", Charsets.UTF_8)
                        assertTrue(runCatching { owner.readSettings(identity, 10_000L, false, {}) }
                            .exceptionOrNull() is SongPlaybackSettingsException)
                        assertTrue(owner.cachedSettings(identity)?.isFailure == true)
                    } finally { row.writeBytes(durableBytes) }
                    assertTrue(runCatching { owner.readSettings(identity, 10_000L, false, {}) }.isFailure)
                    assertEquals(SongPlaybackSettings(trim = trim, tempo = 1.5f),
                        owner.readSettings(identity, 10_000L, true, {}))
                    assertEquals(SongPlaybackSettings(trim = trim, tempo = 1.25f),
                        PlaybackPreferencesPersistence(context.filesDir, scope, { throw it }, { throw it })
                            .readSettings(identity, 10_000L, false, {}))
                    owner.persistSettings(identity, 10_000L, checkNotNull(owner.cachedSettings(identity)).getOrThrow())
                    writer(owner).join()
                    owner.savedSettings.clear()
                }
                assertEquals(SongPlaybackSettings(trim = trim, tempo = 1.5f),
                    owner.readSettings(identity, 10_000L, false, {}))
                assertEquals(SongPlaybackSettings(trim = trim, tempo = 1.5f),
                    PlaybackPreferencesPersistence(context.filesDir, scope, { throw it }, { throw it })
                        .readSettings(identity, 10_000L, false, {}))
            } finally {
                release.countDown(); futureWrite.complete(Unit)
                scope.cancel(); scope.coroutineContext[Job]?.join()
            }
        }
    }

    private fun writerField(owner: PlaybackPreferencesPersistence) = owner.javaClass
        .getDeclaredField("settingsWriter").apply { isAccessible = true }
    private fun writer(owner: PlaybackPreferencesPersistence) = writerField(owner).get(owner) as Job
}
