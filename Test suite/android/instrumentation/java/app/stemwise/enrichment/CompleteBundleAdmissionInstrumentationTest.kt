package app.stemwise.enrichment

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stemwise.data.*
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.ui.librarySongSearchKey
import app.stemwise.ui.librarySearchQueryKey
import app.stemwise.ui.librarySearchContext
import app.stemwise.ui.matches
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class CompleteBundleAdmissionInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun importedCreditSearchFlowsThroughRoomAndReplacesThePriorTerm() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val background = BackgroundWorkCoordinator()
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
            val network = object : app.stemwise.network.AppNetworkAccess {
                override val state = kotlinx.coroutines.flow.MutableStateFlow(app.stemwise.network.AppNetworkState(false, false, true))
                override fun currentState() = state.value
            }
            val playlist = PlaylistRepository(context, database, dao, StemwisePreferences(context, database = database),
                app.stemwise.integration.YoutubeDlEngine(context, network, roots), SongWorkCoordinator(), roots, external, background, network)
            val importer = songImporter(context, roots, mutations, bindings, background, database)
            val original = File(context.cacheDir, "original.opus").apply {
                writeBytes(GoldenSongMediaFixture.originalOpus(targetContext, 12_000L))
            }
            val terms = listOf("UniqueProducerOrion", "UniqueProducerVega")
            for ((index, term) in terms.withIndex()) {
                val document = payload("2026-08-${10 + index}T12:40:00Z", "Searchable song", true)
                document.getJSONObject("metadata").put("credits", org.json.JSONArray().put(JSONObject()
                    .put("name", term).put("roles", org.json.JSONArray().put("producer"))))
                val archive = writeArchive(context, "search-$index.zip", document, COVER_PNG, original)
                assertTrue(importer.importPcSong(archive, VIDEO_ID, root.authority()).getOrThrow().items.single().isSuccess)
                external.reconcile()
                val songs = playlist.songs.first()
                val song = songs.single { it.id == VIDEO_ID }
                assertTrue(librarySongSearchKey(song).matches(librarySearchQueryKey(term)))
                val highlight = checkNotNull(librarySearchContext(song, term))
                assertEquals("Credits", highlight.label)
                assertTrue(highlight.displayRanges.isNotEmpty())
                assertTrue(external.songs.value.single().additionalInfo.getValue("Credits").contains(term))
                external.restoreProjectedLibrary(root)
                assertTrue(external.songs.value.single().additionalInfo.getValue("Credits").contains(term))
                if (index > 0) {
                    assertFalse(librarySongSearchKey(song).matches(librarySearchQueryKey(terms.first())))
                    assertNull(librarySearchContext(song, terms.first()))
                }
            }
        } finally {
            database.close()
            context.destroy()
            isolatedContext = null
        }
    }

    @Test fun equalDurationOriginalReplacementBetweenPublicationAndCommitCannotRebindMetadata() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val background = BackgroundWorkCoordinator()
            val original = File(context.cacheDir, "original.opus").apply {
                writeBytes(GoldenSongMediaFixture.originalOpus(targetContext, 12_000L))
            }
            val initial = writeArchive(context, "initial.zip", payload("2026-08-09T12:40:00Z", "Prior valid metadata", true), COVER_PNG, original)
            assertTrue(songImporter(context, roots, mutations, bindings, background, database)
                .importPcSong(initial, VIDEO_ID, root.authority()).getOrThrow().items.single().isSuccess)
            val folder = File(providerRoot(), VIDEO_ID)
            val prior = listOf("song.json", "cover.png").associateWith { File(folder, it).readBytes() }
            val replacement = GoldenSongMediaFixture.originalOpus(targetContext, 12_000L, 660.0)
            val update = writeArchive(context, "update.zip", payload("2026-08-10T12:40:00Z", "Wrong recording update", true), COVER_PNG, original)
            var replaced = false
            val importer = songImporter(context, roots, mutations, bindings, background, database) { phase ->
                if (phase == "BUNDLE_ORIGINAL_PUBLISHED") {
                    File(folder, "original.opus").writeBytes(replacement)
                    replaced = true
                }
            }
            val item = importer.importPcSong(update, VIDEO_ID, root.authority()).getOrThrow().items.single()
            assertTrue(replaced)
            assertEquals("SOURCE_BINDING_CONFLICT", item.errorCode)
            prior.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(folder, name).readBytes()) }
            assertArrayEquals(replacement, File(folder, "original.opus").readBytes())
        } finally {
            database.close()
            context.destroy()
            isolatedContext = null
        }
    }

    @Test fun scratchCleanupWaitsForAnActiveImportThroughSuccessAndCancellation() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (cancel in listOf(false, true)) {
            resetProvider()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            grantProviderTree()
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var importing: kotlinx.coroutines.Deferred<*>? = null
            var cleanup: kotlinx.coroutines.Deferred<*>? = null
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val background = BackgroundWorkCoordinator()
                val importer = songImporter(context, roots, mutations, bindings, background, database)
                val original = File(context.cacheDir, "original.opus").apply {
                    writeBytes(GoldenSongMediaFixture.originalOpus(targetContext, 12_000L))
                }
                val archive = writeArchive(context, "complete.zip",
                    payload("2026-08-09T12:40:00Z", "Scratch lifetime", true), COVER_PNG, original)
                val attempt = async(Dispatchers.IO) {
                    importer.importPcSong(archive, VIDEO_ID, root.authority()) { completed, _, phase ->
                        if (completed == 0 && phase == "Validating") {
                            reached.complete(Unit)
                            release.await()
                        }
                    }
                }
                importing = attempt
                withTimeout(10_000L) { reached.await() }
                val scratchRoot = File(context.cacheDir, "enrichment")
                val ownedScratch = scratchRoot.listFiles()!!.single()
                assertTrue(ownedScratch.isDirectory)

                // Same dispatcher + UNDISTPATCHED reaches the real cleanup lock before returning.
                val waiting = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    runCatching { importer.cleanupOrphanScratch() }
                }
                cleanup = waiting
                assertFalse("Cleanup retired while the import still owned its scratch", waiting.isCompleted)
                assertTrue("Cleanup deleted live import scratch", ownedScratch.isDirectory)

                if (cancel) {
                    attempt.cancel()
                    val failure = runCatching { withTimeout(10_000L) { attempt.await() } }.exceptionOrNull()
                    assertTrue(failure is kotlinx.coroutines.CancellationException)
                } else {
                    release.complete(Unit)
                    val result = withTimeout(30_000L) { attempt.await() }.getOrThrow()
                    assertTrue(result.items.single().isSuccess)
                    val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
                    assertEquals(64, external.verifyPlaybackSong(root, VIDEO_ID).mediaRevision.length)
                }
                withTimeout(10_000L) { waiting.await() }.getOrThrow()
                assertFalse(scratchRoot.exists())
                assertTrue(archive.isFile)
            } finally {
                release.complete(Unit)
                importing?.cancel()
                cleanup?.cancel()
                withTimeout(10_000L) { importing?.join(); cleanup?.join() }
                database.close()
                context.destroy()
                isolatedContext = null
            }
        }
    }

    @Test fun firstImportRetainsCompleteInputAcrossEveryPublicationInterruption() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val phases = listOf("BUNDLE_ORIGINAL_PUBLISHED", ENRICHMENT_COMMIT_BEFORE_TARGET_WRITE,
            ENRICHMENT_COMMIT_AFTER_COVER_WRITE, ENRICHMENT_COMMIT_AFTER_TARGET_WRITE)
        for (lossless in listOf(false, true)) for (phase in phases) {
            resetProvider()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            grantProviderTree()
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val background = BackgroundWorkCoordinator()
                val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
                val original = File(context.cacheDir, if (lossless) "original.flac" else "original.opus")
                val document = if (lossless) {
                    testContext.assets.open("encoder/flac24-stereo-96000.flac").use { input -> original.outputStream().use(input::copyTo) }
                    targetContext.assets.open("qa/enrichment/native-flac-song-enrichment-v2.json").bufferedReader().use { JSONObject(it.readText()) }
                } else {
                    original.writeBytes(GoldenSongMediaFixture.originalOpus(targetContext, 12_000L))
                    payload("2026-08-09T12:40:00Z", "Complete recovered song", true)
                }
                val expectedOriginal = sha256(original)
                val archive = writeArchive(context, "complete.zip", document, COVER_PNG, original)
                var interrupted = false
                val importer = songImporter(context, roots, mutations, bindings, background, database) { reached ->
                    if (!interrupted && reached == phase) {
                        interrupted = true
                        throw EnrichmentCommitInterruption(phase)
                    }
                }
                val result = importer.importPcSong(archive, VIDEO_ID, root.authority()).getOrThrow()
                assertTrue("$lossless/$phase: ${result.items}", interrupted)
                assertFalse(result.items.single().isSuccess)
                assertTrue(File(context.filesDir, "song-bundle-intake").listFiles().orEmpty().any { it.extension == "zip" })
                check(archive.delete())
                importer.cleanupOrphanScratch()
                if (phase == "BUNDLE_ORIGINAL_PUBLISHED") {
                    external.reconcile()
                    assertTrue(external.songs.value.isEmpty())
                    assertTrue(dao.observeSongSnapshots().first().isEmpty())
                }
                val restarted = songImporter(context, roots, mutations, bindings, background, database)
                withTimeout(30_000L) {
                    external.recoverForColdOpen(root)
                    restarted.recoverIncompleteBundles(root)
                    restarted.recoverIncompleteBundles(root)
                }
                val song = external.verifyPlaybackSong(root, VIDEO_ID)
                assertEquals(64, song.mediaRevision.length)
                assertEquals(expectedOriginal, song.sourceHash)
                assertFalse(File(context.filesDir, "song-bundle-intake").listFiles().orEmpty().any { it.extension == "zip" })
                assertTrue(File(context.filesDir, "operation-journals").listFiles().orEmpty().none {
                    it.readText(Charsets.UTF_8).contains("SONG_BUNDLE_IMPORT_V1")
                })
            } finally {
                database.close()
                context.destroy()
                isolatedContext = null
            }
        }
    }

    @Test fun permanentDeletionSettlesCommittedBundleReceiptsBeforeColdRecovery() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (phase in listOf("BUNDLE_BEFORE_VERIFICATION", "BUNDLE_VERIFIED")) {
            resetProvider()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            grantProviderTree()
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val background = BackgroundWorkCoordinator()
                val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
                val original = File(context.cacheDir, "original.opus").apply {
                    writeBytes(GoldenSongMediaFixture.originalOpus(targetContext, 12_000L))
                }
                val archive = writeArchive(context, "committed.zip",
                    payload("2026-08-09T12:40:00Z", "Committed song", true), COVER_PNG, original)
                var interrupted = false
                val importer = songImporter(context, roots, mutations, bindings, background, database) { reached ->
                    if (!interrupted && reached == phase) { interrupted = true; throw EnrichmentCommitInterruption(phase) }
                }
                assertFalse(importer.importPcSong(archive, VIDEO_ID, root.authority()).getOrThrow().items.single().isSuccess)
                assertTrue(interrupted)
                external.verifyPlaybackSong(root, VIDEO_ID)
                assertTrue(checkedAtomicJournalRecords(File(context.filesDir, "operation-journals"), "JOURNAL_CORRUPT")
                    .any { it.value.optString("operation") == "SONG_BUNDLE_IMPORT_V1" })
                val deletion = PermanentDeletionCoordinator(
                    ExternalPackageTrash(context, roots, mutations, bindings, dao), external, SongWorkCoordinator())
                    .deletePermanently(root, VIDEO_ID).getOrThrow()
                assertFalse(deletion.projectionRefreshPending)
                assertTrue(external.songs.value.isEmpty())
                assertEquals(0, dao.canonicalStorageTotals().packages)
                assertFalse(File(providerRoot(), VIDEO_ID).exists())
                val restartedRoots = LibraryRootManager(context, mutations, dao)
                restartedRoots.reconnect()
                val restartedRoot = checkNotNull(restartedRoots.captureHandle())
                val restartedBindings = SourceBindingRegistry(context, restartedRoots, mutations, dao)
                val restartedExternal = ExternalLibraryRepository(context, restartedRoots, dao, restartedBindings, mutations, background)
                val restarted = songImporter(context, restartedRoots, mutations, restartedBindings, background, database)
                withTimeout(15_000L) {
                    restarted.prepareBundleRecovery(restartedRoot)
                    restartedExternal.recoverForColdOpen(restartedRoot)
                    restarted.recoverIncompleteBundles(restartedRoot)
                    restartedExternal.restoreProjectedLibrary(restartedRoot)
                }
                assertTrue(restartedExternal.songs.value.isEmpty())
                assertFalse(File(providerRoot(), VIDEO_ID).exists())
                assertTrue(archive.isFile)
            } finally { database.close(); context.destroy(); isolatedContext = null }
        }
    }

    @Test fun missingOrInvalidRequiredMetadataRevokesWarmAndColdPlaybackWithoutDeletingFiles() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (fault in listOf("missing-json", "missing-png", "bad-json", "bad-png", "different-original")) {
            resetProvider()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            grantProviderTree()
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                seedDirectMedia(withEnrichment = true)
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, BackgroundWorkCoordinator())
                assertEquals(64, external.verifyPlaybackSong(root, VIDEO_ID).mediaRevision.length)
                val folder = File(providerRoot(), VIDEO_ID)
                when (fault) {
                    "missing-json" -> check(File(folder, "song.json").delete())
                    "missing-png" -> check(File(folder, "cover.png").delete())
                    "bad-json" -> File(folder, "song.json").writeText("broken", Charsets.UTF_8)
                    "bad-png" -> File(folder, "cover.png").writeBytes(byteArrayOf(1))
                    "different-original" -> File(folder, "original.opus").writeBytes(GoldenSongMediaFixture.originalOpus(targetContext, 10_000L))
                }
                val preserved = folder.listFiles()!!.associate { it.name to sha256(it) }
                assertTrue(fault, runCatching { external.verifyOriginalPlaybackSong(root, VIDEO_ID) }.isFailure)
                assertTrue(fault, external.songs.value.isEmpty())
                assertTrue(fault, dao.observeSongSnapshots().first().isEmpty())
                external.restoreProjectedLibrary(root)
                external.validateProjectedLibrary(root)
                assertTrue(fault, external.songs.value.isEmpty())
                assertEquals(preserved, folder.listFiles()!!.associate { it.name to sha256(it) })
            } finally {
                database.close()
                context.destroy()
                isolatedContext = null
            }
        }
    }
    @Test fun originalSelectionDefersOptionalBytesAndStemAdmissionRejectsChangedLanes() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            seedDirectMedia(withEnrichment = true)
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, BackgroundWorkCoordinator())
            external.reconcile()
            val expectedStems = external.observeActiveStems(VIDEO_ID).first()
            assertTrue(expectedStems.isNotEmpty())
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            repeat(3) {
                val original = external.verifyOriginalPlaybackSong(root, VIDEO_ID)
                assertEquals(64, original.mediaRevision.length)
                assertTrue("Deferred validation must retain selectable part inventory", original.hasStems)
            }
            var metrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            assertEquals("Original selection opened unused part bytes", 0L, metrics.getLong("openedOptionalMediaLengthBytes"))
            assertTrue("Required audio was not verified", metrics.getLong("openedDocumentLengthBytes") > 0L)
            assertEquals(expectedStems, external.observeActiveStems(VIDEO_ID).first())
            assertEquals(expectedStems.sortedBy { it.id }, external.verifyPlaybackStems(root, VIDEO_ID, expectedStems).sortedBy { it.id })
            metrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            assertTrue("Stem admission did not open its actual lanes", metrics.getLong("openedOptionalMediaLengthBytes") > 0L)
            val lane = File(providerRoot(), "$VIDEO_ID/part-1.flac")
            val changed = lane.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            lane.writeBytes(changed)
            val preserved = sha256(lane)
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            assertEquals(64, external.verifyOriginalPlaybackSong(root, VIDEO_ID).mediaRevision.length)
            assertEquals(0L, providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("openedOptionalMediaLengthBytes"))
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            assertTrue("Changed lane bytes entered playback", runCatching { external.verifyPlaybackStems(root, VIDEO_ID, expectedStems) }.isFailure)
            assertEquals("Optional rejection repeated Original verification", 1,
                providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getStringArrayList("openedDocumentIds")!!.count {
                    it.endsWith("/original.opus")
                })
            val fallback = external.songs.value.single()
            assertEquals(64, fallback.mediaRevision.length)
            assertFalse(fallback.hasStems)
            assertTrue(external.observeActiveStems(VIDEO_ID).first().isEmpty())
            assertEquals(preserved, sha256(lane))
        } finally {
            database.close()
            context.destroy()
            isolatedContext = null
        }
    }

}
