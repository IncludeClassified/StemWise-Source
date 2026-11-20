package app.stemwise.analysis

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.room.Room
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.authority
import app.stemwise.data.readBoundedProviderDocument
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.enrichment.InstalledMediaIdentity
import app.stemwise.enrichment.createInstalledEnrichmentDocument
import app.stemwise.enrichment.enrichmentRevisionIdentity
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal class ImportedSongPreparationInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun preparedMainThreadLookupsAndQueueRetentionDoNotClassifySafProviders() = runBlocking {
        withSource { context, roots, song ->
            val guarded = context as PackageManagerGuardContext
            val repository = ImportedAnalysisRepository(context, roots)
            val queue = listOf(song) + List(32) { song.copy(id = "prepared-queue-$it") }
            repository.retainQueuePresentations(queue)
            val prepared = repository.presentation(song.id, song.mediaRevision, checkNotNull(song.sourceHash))
            val root = checkNotNull(roots.captureHandle())
            val before = guarded.packageManagerAccesses.get()
            assertTrue("Real root construction must exercise the guarded boundary", before > 0)
            withContext(Dispatchers.Main) {
                guarded.allowPackageManagerAccess = false
                try {
                    repeat(3) {
                        assertEquals(root.authority(), roots.captureAuthority())
                        roots.requireSameLibrary(checkNotNull(roots.captureAuthority()))
                        repository.retainQueuePresentations(queue)
                        repository.retainSelectionPresentation(song)
                        val identity = checkNotNull(repository.presentationIdentity(song))
                        assertEquals(root.libraryId, identity.root.libraryId)
                        assertEquals(root.treeUri.toString(), identity.root.treeUri)
                        assertEquals(root.epoch, identity.root.epoch)
                        assertSame(prepared, repository.preparedPresentation(song)?.getOrThrow())
                        assertSame(prepared, repository.presentation(song.id, song.mediaRevision, checkNotNull(song.sourceHash)))
                        assertNull(repository.preparedPresentation(song.copy(sourceHash = "f".repeat(64))))
                    }
                } finally { guarded.allowPackageManagerAccess = true }
            }
            assertEquals("Prepared lookups touched Android provider classification", before, guarded.packageManagerAccesses.get())
            roots.reportRuntimeAccessFailure(root, SecurityException("isolated prepared-root loss"))
            withContext(Dispatchers.Main) {
                guarded.allowPackageManagerAccess = false
                try {
                    assertNull(roots.captureAuthority())
                    assertTrue(runCatching { roots.requireSameLibrary(root.authority()) }.isFailure)
                    assertNull(repository.presentationIdentity(song))
                    assertNull(repository.preparedPresentation(song))
                    repository.retainQueuePresentations(queue)
                    repository.retainSelectionPresentation(song)
                    assertTrue(runCatching {
                        repository.presentation(song.id, song.mediaRevision, checkNotNull(song.sourceHash))
                    }.isFailure)
                } finally { guarded.allowPackageManagerAccess = true }
            }
            val replacementTree = android.provider.DocumentsContract.buildTreeDocumentUri(
                GoldenQaDocumentsProvider.AUTHORITY, "root/prepared-authority-replacement")
            check(File(providerRoot(), "prepared-authority-replacement").mkdir())
            grantProviderTree(replacementTree)
            try {
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(replacementTree).getOrThrow()).getOrThrow()
                val replacement = checkNotNull(roots.captureHandle())
                assertNotEquals(root.epoch, replacement.epoch)
                withContext(Dispatchers.Main) {
                    guarded.allowPackageManagerAccess = false
                    try {
                        assertEquals(replacement.authority(), roots.captureAuthority())
                        assertTrue(runCatching { roots.requireSameLibrary(root.authority()) }.isFailure)
                        assertTrue(runCatching {
                            roots.requireSameLibrary(replacement.authority().copy(epoch = root.epoch))
                        }.isFailure)
                        roots.requireSameLibrary(checkNotNull(roots.captureAuthority()))
                        assertNull(repository.preparedPresentation(song))
                        repository.retainQueuePresentations(queue)
                        repository.retainSelectionPresentation(song)
                        assertNull(repository.preparedPresentation(song))
                    } finally { guarded.allowPackageManagerAccess = true }
                }
            } finally {
                targetContext.revokeUriPermission(targetContext.packageName, replacementTree,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    @Test fun preparedQueueDoesNotReadAgainAndRejectsWrongOriginalOrManifest() = runBlocking {
        withSource { context, roots, song ->
            var permitReads = true
            val reads = AtomicInteger()
            val repository = ImportedAnalysisRepository(context, roots) { owner, uri, size, maximum ->
                check(permitReads) { "Prepared presentation reopened provider bytes" }
                reads.incrementAndGet()
                readBoundedProviderDocument(owner, uri, size, maximum)
            }
            repository.retainQueuePresentations(listOf(song))
            val prepared = repository.presentation(song.id, song.mediaRevision, checkNotNull(song.sourceHash))
            assertTrue(reads.get() > 0)
            permitReads = false
            assertSame(prepared, repository.presentation(song.id, song.mediaRevision, checkNotNull(song.sourceHash)))
            assertSame(prepared.timeline, repository.metronomeTimeline(song.id, song.mediaRevision, checkNotNull(song.sourceHash)))
            assertSame(prepared, repository.preparedPresentation(song)?.getOrThrow())
            assertNull(repository.preparedPresentation(song.copy(sourceHash = "f".repeat(64))))
            assertNull(repository.preparedPresentation(song.copy(mediaRevision = "e".repeat(64))))
            repository.retainQueuePresentations(emptyList())
            assertNull(repository.preparedPresentation(song))
            // Explicit cold selection has its own bounded slot before queue insertion.
            repository.retainSelectionPresentation(song)
            permitReads = true
            val selected = repository.presentation(song.id, song.mediaRevision, checkNotNull(song.sourceHash))
            assertSame(selected, repository.preparedPresentation(song)?.getOrThrow())
            repository.retainQueuePresentations(listOf(song))
            assertSame(selected, repository.preparedPresentation(song)?.getOrThrow())
            repository.retainQueuePresentations(emptyList())
            assertSame(selected, repository.preparedPresentation(song)?.getOrThrow())
            assertNull(repository.preparedPresentation(song.copy(sourceHash = "f".repeat(64))))
            assertNull(repository.preparedPresentation(song.copy(mediaRevision = "e".repeat(64))))
            repository.retainSelectionPresentation(null)
            assertNull(repository.preparedPresentation(song))
            repository.retainSelectionPresentation(song)
            repository.presentation(song.id, song.mediaRevision, checkNotNull(song.sourceHash))
            repository.retainSelectionPresentation(song.copy(mediaRevision = "e".repeat(64)))
            assertNull(repository.preparedPresentation(song))
            repository.retainSelectionPresentation(null)
        }
    }

    @Test fun replacingCancelledReaderRetriesTheSameLiveQueueAndFailuresRemainRetryable() = runBlocking {
        withSource { context, roots, song ->
            val entered = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            var failRead = true
            val repository = ImportedAnalysisRepository(context, roots) { owner, uri, size, maximum ->
                if (reads.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                if (failRead) throw IOException("Provider temporarily unavailable")
                readBoundedProviderDocument(owner, uri, size, maximum)
            }
            repository.retainQueuePresentations(listOf(song))
            val first = async(Dispatchers.IO) { repository.presentation(song.id, song.mediaRevision, checkNotNull(song.sourceHash)) }
            withTimeout(5_000L) { entered.await() }
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.presentation(song.id, song.mediaRevision, checkNotNull(song.sourceHash)) }
            }
            try {
                first.cancelAndJoin()
                assertTrue(withTimeout(5_000L) { replacement.await() }.isFailure)
                assertTrue(checkNotNull(repository.preparedPresentation(song)).isFailure)
                assertEquals(2, reads.get())
                failRead = false
                val retried = withTimeout(5_000L) {
                    repository.presentation(song.id, song.mediaRevision, checkNotNull(song.sourceHash), retryFailure = true)
                }
                assertSame(retried, repository.preparedPresentation(song)?.getOrThrow())
                assertEquals(3, reads.get())
            } finally { first.cancelAndJoin(); replacement.cancelAndJoin() }
        }
    }

    private suspend fun withSource(block: suspend (android.content.Context, LibraryRootManager, Song) -> Unit) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val context = PackageManagerGuardContext(IsolatedQaContext(targetContext).also { isolatedContext = it })
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), database.libraryDao())
            val original = File(providerRoot(), "$VIDEO_ID/${SelectedLibraryContract.ORIGINAL}")
            val hash = sha256(original)
            val coverHash = sha256(File(original.parentFile, "cover.png"))
            val incoming = payload("2026-08-09T12:00:00Z", UUID.randomUUID().toString(), includeArtwork = true)
            val document = createInstalledEnrichmentDocument(incoming, hash,
                mapOf(SelectedLibraryContract.ORIGINAL to InstalledMediaIdentity(hash, original.length())), 1L,
                enrichmentRevisionIdentity(incoming, coverHash), incoming.getJSONObject("artwork"), coverHash, COVER_PNG.size)
            val manifest = File(original.parentFile, "song.json").apply { writeText(document.toString(), Charsets.UTF_8) }
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val song = Song(VIDEO_ID, VIDEO_ID, "Prepared fixture", "Fixture", 10_000L, SongState.READY,
                Color.Cyan, artworkGlyph = "P", sourceHash = hash, mediaRevision = sha256(manifest))
            block(context, roots, song)
        } finally { database.close() }
    }

    private class PackageManagerGuardContext(base: Context) : ContextWrapper(base) {
        @Volatile var allowPackageManagerAccess = true
        val packageManagerAccesses = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun getPackageManager(): PackageManager {
            check(allowPackageManagerAccess) { "Prepared cache lookup requested Android PackageManager" }
            packageManagerAccesses.incrementAndGet()
            return super.getPackageManager()
        }
    }
}
