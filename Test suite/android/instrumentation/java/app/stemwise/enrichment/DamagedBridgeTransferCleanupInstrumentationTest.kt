package app.stemwise.enrichment

import androidx.room.Room
import app.stemwise.bridge.*
import app.stemwise.data.*
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal class DamagedBridgeTransferCleanupInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun discardedDamagedRecordRetainsAnIncompleteBundleArchiveAndRemovesUnreferencedCopies() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(app.stemwise.qa.GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val background = BackgroundWorkCoordinator()
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
            external.reconcile()
            var interrupt = true
            val importer = songImporter(context, roots, mutations, bindings, background, database) { phase ->
                if (interrupt && phase == "BUNDLE_ORIGINAL_PUBLISHED") throw EnrichmentCommitInterruption(phase)
            }
            val store = BridgeSongStore(File(context.noBackupFilesDir, "pc-song-jobs").apply { mkdirs() })
            val id = "b".repeat(32)
            val archive = writeArchive(context, "accepted.zip", payload("2026-08-09T12:00:00Z", "Accepted", true), COVER_PNG)
                .copyTo(store.file(id, "zip"))
            val before = archive.readBytes()
            assertFalse(importer.importPcSong(archive, VIDEO_ID, root.authority()).getOrThrow().items.single().isSuccess)
            File(store.directory, "$id.json").writeText("{damaged")
            store.file(id, "md").writeText("guide")
            store.file(id, "input").writeText("input")
            val transport = object : SongBridgeTransport {
                override suspend fun prepareInput(source: File, name: String): BridgeFile = error("No remote work")
                override suspend fun ensureInput(file: BridgeFile, source: File) = error("No remote work")
                override suspend fun job(id: String): JSONObject? = error("No remote work")
                override suspend fun submit(request: JSONObject): JSONObject = error("No remote work")
                override suspend fun receive(file: BridgeFile, destination: File) = error("No remote work")
                override suspend fun acknowledge(id: String, file: BridgeFile, destination: File) = error("No remote work")
                override suspend fun deleteJob(id: String) = error("No remote work")
                override suspend fun discardTransfers(record: BridgeSongRecord) = error("No remote work")
            }
            val repository = SongBridgeRepository(context, roots, importer, external, dao, background,
                { true }, { emptySet() }, { _, _ -> }, store = store, transportFor = { transport }, scope = scope, schedule = { _, _ -> })
            repository.forget("damaged:$id.json").getOrThrow()
            assertTrue(store.damagedTasks().isEmpty())
            assertTrue(store.directory.listFiles().orEmpty().isEmpty())
            val retained = File(context.filesDir, "song-bundle-intake").listFiles().orEmpty().single()
            assertArrayEquals(before, retained.readBytes())
            interrupt = false
            importer.recoverIncompleteBundles(root)
            assertEquals("2026-08-09T12:00:00Z", JSONObject(File(providerRoot(), "$VIDEO_ID/song.json").readText()).getString("generatedAt"))
            assertFalse(retained.exists())
        } finally { scope.cancel(); database.close() }
    }
}
