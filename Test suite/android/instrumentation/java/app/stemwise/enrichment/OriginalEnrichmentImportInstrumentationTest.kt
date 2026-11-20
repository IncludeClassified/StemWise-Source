package app.stemwise.enrichment

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.analysis.ImportedAnalysisRepository
import app.stemwise.data.ExternalLibraryRepository
import app.stemwise.data.CanonicalPackagePublisher
import app.stemwise.data.CanonicalJson
import app.stemwise.data.CustomSourceAsset
import app.stemwise.data.CustomSourceManifest
import app.stemwise.data.ExternalPackageTrash
import app.stemwise.data.NATIVE_ORIGINAL_48K_REMAINING_PRODUCER
import app.stemwise.data.SOURCE_REMAINING_REPREPARE_REQUIRED
import app.stemwise.data.inspectOriginalAudio
import app.stemwise.data.inspectPreparedSourceAudio
import app.stemwise.data.parseCustomSourceManifest
import app.stemwise.data.validateCustomSourceAsset
import app.stemwise.data.validateCustomSourceAudioAsset
import app.stemwise.integration.MvsepSourceOperation
import app.stemwise.integration.MvsepSourcePlan
import app.stemwise.integration.SourceJobTransition
import app.stemwise.integration.YoutubeDlEngine
import app.stemwise.integration.createPortableSourceJobAuthority
import app.stemwise.integration.recordPortableSourceAsset
import app.stemwise.integration.transitionPortableSourceJob
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryRootException
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.authority
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.SongEntity
import app.stemwise.data.SourceBindingRegistry
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class OriginalEnrichmentImportInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun rejectedCoverManifestAndArchiveRootDoNotAbortIndependentValidPackages() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val importer = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database)
            val valid = writeArchive(context, "valid-neighbor.zip", payload("2026-10-30T00:00:00Z", "Valid neighbor", true), COVER_PNG)
            val badCover = writeArchive(context, "invalid-cover.zip", payload("2026-10-29T00:00:00Z", "Invalid cover", true), byteArrayOf(1, 2, 3))
            val badManifest = writeArchive(context, "invalid-manifest.zip", payload("2026-10-29T00:00:00Z", "Invalid manifest", true).put("schemaVersion", -1), COVER_PNG)
            val badRoot = File(context.cacheDir, "invalid-root.zip").apply {
                ZipOutputStream(outputStream()).use { zip -> zip.putNextEntry(ZipEntry("unrecognized.txt")); zip.write(1); zip.closeEntry() }
            }
            val inputs = listOf(badCover, valid, badManifest, badRoot)
            val result = importer.importStagedFiles(inputs, inputs.map { it.name }).getOrThrow()
            assertEquals(4, result.items.size)
            assertEquals(1, result.items.count { it.isSuccess })
            assertTrue(result.items.single { it.sourceName == valid.name }.isSuccess)
            assertTrue(result.items.filter { it.sourceName != valid.name }.all { !it.isSuccess && it.errorCode != null })
            assertEquals("Valid neighbor", JSONObject(File(providerRoot(), "$VIDEO_ID/song.json").readText(Charsets.UTF_8))
                .getJSONObject("metadata").getString("title"))
        } finally { database.close() }
    }

    @Test fun failedFinalAndRollbackJournalWritesRetainReplacementForFreshOwnerRecovery() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            var mutations = PackageMutationCoordinator(context)
            var roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val selected = checkNotNull(roots.captureHandle()).authority()
            var bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val importer = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database)
            mutations.journalWriteForTest = { operation, state -> if (operation == "ENRICHMENT_INSTALL_V2" && state in setOf("COMPLETE", "ROLLED_BACK")) throw java.io.IOException("Final journal unavailable") }
            val archive = writeArchive(context, "recover-final-journal.zip", payload("2026-10-30T00:00:00Z", "Recovered replacement", true), COVER_PNG)
            val rejected = importer.importStagedFiles(listOf(archive), listOf(archive.name)).getOrThrow().items.single()
            assertEquals("RECOVERY_PENDING", rejected.errorCode)
            assertTrue(File(context.filesDir, "enrichment-recovery").listFiles().orEmpty().any { it.name.endsWith("replacement") })
            mutations.journalWriteForTest = null
            mutations = PackageMutationCoordinator(context)
            roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.initialize()
            assertEquals(selected, checkNotNull(roots.captureHandle()).authority())
            bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, BackgroundWorkCoordinator())
            external.reconcile()
            assertEquals("Recovered replacement", external.songs.value.single().title)
            assertEquals(selected, checkNotNull(roots.captureHandle()).authority())
            assertTrue(File(context.filesDir, "enrichment-recovery").listFiles().orEmpty().isEmpty())
        } finally { database.close() }
    }

    @Test fun permanentDeleteRetiresStoppedEnrichmentAndOwnedBundleBeforeFreshRecovery() = runBlocking {
        deleteStoppedEnrichment(interruptRetirement = false, deleteParts = false)
    }

    @Test fun interruptedPermanentDeleteReplaysEnrichmentRetirementWithoutLosingNeighbor() = runBlocking {
        deleteStoppedEnrichment(interruptRetirement = true, deleteParts = false)
    }

    @Test fun deletePartsPreservesStoppedEnrichmentRecoveryAndBundleInputs() = runBlocking {
        deleteStoppedEnrichment(interruptRetirement = false, deleteParts = true)
    }

    private suspend fun deleteStoppedEnrichment(interruptRetirement: Boolean, deleteParts: Boolean) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val neighborId = "ZZZZZZZZZZZ"
        val neighbor = File(providerRoot(), neighborId).apply { check(mkdir()) }
        val neighborOriginal = File(neighbor, SelectedLibraryContract.ORIGINAL)
        neighborOriginal.writeBytes(File(providerRoot(), "$VIDEO_ID/${SelectedLibraryContract.ORIGINAL}").readBytes())
        app.stemwise.data.seedCompleteOpusMetadata(neighbor, neighborId, neighborOriginal.readBytes())
        val neighborBefore = neighbor.listFiles().orEmpty().associate { it.name to it.readBytes() }
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            var mutations = PackageMutationCoordinator(context)
            var roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            var bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val importer = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database)
            mutations.journalWriteForTest = { operation, state ->
                if (operation == "ENRICHMENT_INSTALL_V2" && state in setOf("COMPLETE", "ROLLED_BACK"))
                    throw java.io.IOException("Final journal unavailable")
            }
            val archive = writeArchive(context, "delete-stopped-enrichment.zip",
                payload("2026-10-30T00:00:00Z", "Stopped replacement", true), COVER_PNG)
            assertEquals("RECOVERY_PENDING", importer.importStagedFiles(listOf(archive), listOf(archive.name))
                .getOrThrow().items.single().errorCode)
            mutations.journalWriteForTest = null
            val recovery = File(context.filesDir, "enrichment-recovery")
            val intake = File(context.filesDir, "song-bundle-intake")
            val ownedBefore = (recovery.listFiles().orEmpty().asList() + intake.listFiles().orEmpty().asList())
                .associateWith { it.readBytes() }
            assertTrue(ownedBefore.keys.any { it.name.endsWith("replacement") })
            assertTrue(ownedBefore.keys.any { it.parentFile == intake && it.extension == "zip" })
            val trash = ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao())
            if (deleteParts) {
                trash.deleteStems(handle, VIDEO_ID).getOrThrow()
                ownedBefore.forEach { (file, bytes) -> assertTrue(bytes.contentEquals(file.readBytes())) }
                assertTrue(File(providerRoot(), "$VIDEO_ID/${SelectedLibraryContract.ORIGINAL}").isFile)
                assertTrue(app.stemwise.data.checkedAtomicJournalRecords(File(context.filesDir, "operation-journals"), "RECOVERY_PENDING")
                    .any { app.stemwise.data.requireAtomicJournalEnvelope(it, "RECOVERY_PENDING").operation == "ENRICHMENT_INSTALL_V2" })
            } else {
                if (interruptRetirement) trash.beforePreparationRetirementForTest = { throw java.io.IOException("Retirement interrupted") }
                val deleted = trash.deletePermanently(handle, VIDEO_ID)
                if (interruptRetirement) {
                    assertTrue(deleted.isFailure)
                    assertFalse(File(providerRoot(), VIDEO_ID).exists())
                    assertTrue(ownedBefore.keys.all(File::exists))
                } else deleted.getOrThrow()
                mutations = PackageMutationCoordinator(context)
                roots = LibraryRootManager(context, mutations, database.libraryDao())
                roots.initialize()
                bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
                ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao()).recoverInterruptedDeletes()
                val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, BackgroundWorkCoordinator())
                external.reconcile()
                assertFalse(File(providerRoot(), VIDEO_ID).exists())
                assertTrue(ownedBefore.keys.none(File::exists))
                assertTrue(app.stemwise.data.checkedAtomicJournalRecords(File(context.filesDir, "operation-journals"), "RECOVERY_PENDING")
                    .none { app.stemwise.data.requireAtomicJournalEnvelope(it, "RECOVERY_PENDING").packageId == VIDEO_ID })
                assertEquals(listOf(neighborId), external.songs.value.map { it.id })
            }
            neighborBefore.forEach { (name, bytes) -> assertTrue(bytes.contentEquals(File(neighbor, name).readBytes())) }
        } finally { database.close() }
    }

    @Test
    fun pcValidationRetainsRejectionCodeAndOriginalCause() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            seedDirectMedia(withEnrichment = true)
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val importer = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database)
            val archive = writeArchive(context, "rejected-pc.zip",
                payload("2026-08-09T12:40:00Z", "Rejected PC input", true), COVER_PNG)
            val archiveHash = sha256(archive)
            val directory = File(providerRoot(), VIDEO_ID)
            val installedBefore = directory.listFiles()!!.associate { it.name to sha256(it) }
            val original = IllegalStateException("SOURCE_TIMELINE_MISMATCH",
                IllegalStateException("Original PCM EOS=true, written=48, expected=49"))
            importer.extractionProgressForTest = { throw original }
            val failure = importer.importPcSong(archive, VIDEO_ID, root.authority()).exceptionOrNull()
            assertTrue("$failure", failure is EnrichmentImportException)
            assertEquals("SOURCE_TIMELINE_MISMATCH", (failure as EnrichmentImportException).code)
            assertTrue(failure.cause === original)
            assertEquals("Original PCM EOS=true, written=48, expected=49", failure.cause?.cause?.message)
            assertEquals(installedBefore, directory.listFiles()!!.associate { it.name to sha256(it) })
            assertEquals(archiveHash, sha256(archive))
        } finally { database.close() }
    }

    @Test
    fun capacityFailureBeforeExtractionPreservesInstalledSongAndVerifiedArchiveForRetry() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            seedDirectMedia(withEnrichment = true)
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val importer = songImporter(context, roots, mutations, bindings, background, database)
            val archive = writeArchive(context, "capacity-retry.zip",
                payload("2026-08-09T12:40:00Z", "Capacity retry", true), COVER_PNG)
            val archiveHash = sha256(archive)
            val directory = File(providerRoot(), VIDEO_ID)
            val installedBefore = directory.listFiles()!!.associate { it.name to sha256(it) }
            var copiedPayload = false
            importer.extractionProgressForTest = { copiedPayload = true }
            importer.privateStorageAvailableForTest = { app.stemwise.data.IMPORT_STORAGE_RESERVE_BYTES + archive.length() }
            val failure = importer.importPcSong(archive, VIDEO_ID, root.authority()).exceptionOrNull()
            assertEquals("STORAGE_RESERVE_LOW", failure?.message)
            assertFalse("Capacity admission happened after extracting payload bytes", copiedPayload)
            assertEquals(installedBefore, directory.listFiles()!!.associate { it.name to sha256(it) })
            assertEquals(archiveHash, sha256(archive))
            importer.privateStorageAvailableForTest = null
            assertEquals("SONG_ID_MISMATCH", importer.importPcSong(archive, "ZyXwVu_98-7", root.authority())
                .exceptionOrNull()?.message)
            assertEquals(installedBefore, directory.listFiles()!!.associate { it.name to sha256(it) })
            assertEquals(archiveHash, sha256(archive))
            importer.privateStorageAvailableForTest = { app.stemwise.data.IMPORT_STORAGE_RESERVE_BYTES + archive.length() }
            val staging = File(context.noBackupFilesDir, "import-staging").also { check(it.mkdir()) }
            val staged = archive.copyTo(File(staging, "${UUID.randomUUID()}-0.zip"))
            val journal = File(staging, ENRICHMENT_STAGING_JOURNAL_NAME)
            journal.writeText(JSONObject().put("schemaVersion", 3L).put("libraryId", root.libraryId)
                .put("treeUri", root.treeUri.toString()).put("rootEpoch", root.epoch)
                .put("stagingComplete", true).put("phase", "awaiting_confirmation")
                .put("completed", 0L).put("total", 0L)
                .put("stagedFiles", JSONArray().put(JSONObject().put("path", staged.name)
                    .put("sourceName", archive.name))).toString(), Charsets.UTF_8)
            val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, background)
            val coordinator = EnrichmentImportCoordinator(context, importer, external, background, roots)
            withTimeout(5_000L) { coordinator.awaitStartupScratchReady() }
            coordinator.confirm()
            val retry = withTimeout(10_000L) { coordinator.state.first {
                it is SharedEnrichmentImportState.AwaitingConfirmation && it.message != null
            } } as SharedEnrichmentImportState.AwaitingConfirmation
            assertTrue(retry.message!!.contains("Not enough storage"))
            assertEquals(listOf(Uri.fromFile(staged)), retry.sources)
            assertEquals(archiveHash, sha256(staged))
            assertTrue(journal.isFile)
            assertEquals(installedBefore, directory.listFiles()!!.associate { it.name to sha256(it) })
            importer.privateStorageAvailableForTest = null
            importer.extractionProgressForTest = null
            val restarted = EnrichmentImportCoordinator(context, importer, external, background, roots)
            withTimeout(5_000L) { restarted.awaitStartupScratchReady() }
            assertEquals(listOf(Uri.fromFile(staged)),
                (restarted.state.value as SharedEnrichmentImportState.AwaitingConfirmation).sources)
            restarted.confirm()
            val terminal = withTimeout(30_000L) { restarted.state.first {
                it is SharedEnrichmentImportState.Finished || it is SharedEnrichmentImportState.Failed
            } }
            assertTrue(terminal.toString(), terminal is SharedEnrichmentImportState.Finished)
            val retried = (terminal as SharedEnrichmentImportState.Finished).result.items.single()
            assertTrue(retried.errorCode, retried.isSuccess)
            assertEquals(installedBefore.getValue(SelectedLibraryContract.ORIGINAL),
                sha256(File(directory, SelectedLibraryContract.ORIGINAL)))
            assertTrue(staging.listFiles()!!.isEmpty())
        } finally {
            database.close()
            context.destroy()
            isolatedContext = null
        }
    }

    @Test
    fun restartAfterFirstBatchCommitResumesBothStagedSelectionsWithoutChangingFirstSong() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, background)
            val importer = songImporter(context, roots, mutations, bindings, background, database)
            val original = File(context.cacheDir, "original.opus").also {
                it.writeBytes(app.stemwise.data.CurrentSourcePublicationFixture.load(context, VIDEO_ID).original)
            }
            val secondId = "ZyXwVu_98-7"
            val firstArchive = writeArchive(context, "first.zip",
                payload("2026-08-09T12:40:00Z", "First", true), COVER_PNG, original)
            val secondDocument = payload("2026-08-09T12:40:00Z", "Second", true).also {
                it.getJSONObject("source").put("videoId", secondId)
            }
            val secondArchive = writeArchive(context, "second.zip", secondDocument, COVER_PNG, original)
            importer.importPcSong(firstArchive, VIDEO_ID, root.authority()).getOrThrow().also {
                assertTrue(it.items.single().errorCode, it.items.single().isSuccess)
            }
            val firstDirectory = File(providerRoot(), VIDEO_ID)
            val committedFirst = firstDirectory.listFiles()!!.associate { it.name to sha256(it) }
            val staging = File(context.noBackupFilesDir, "import-staging").also { check(it.mkdir()) }
            val session = UUID.randomUUID()
            val staged = listOf(firstArchive, secondArchive).mapIndexed { index, archive ->
                archive.copyTo(File(staging, "$session-$index.zip"))
            }
            File(staging, ENRICHMENT_STAGING_JOURNAL_NAME).writeText(JSONObject()
                .put("schemaVersion", 3L).put("libraryId", root.libraryId)
                .put("treeUri", root.treeUri.toString()).put("rootEpoch", root.epoch)
                .put("stagingComplete", true).put("phase", "Applying enrichment")
                .put("completed", 1L).put("total", 2L)
                .put("stagedFiles", JSONArray().also { entries -> staged.forEach { file ->
                    entries.put(JSONObject().put("path", file.name).put("sourceName", file.name))
                } }).toString(), Charsets.UTF_8)
            val coordinator = EnrichmentImportCoordinator(context, importer, external, background, roots)
            withTimeout(5_000L) { coordinator.awaitStartupScratchReady() }
            assertEquals(staged.map(Uri::fromFile),
                (coordinator.state.value as SharedEnrichmentImportState.AwaitingConfirmation).sources)
            coordinator.confirm()
            val terminal = withTimeout(30_000L) { coordinator.state.first {
                it is SharedEnrichmentImportState.Finished || it is SharedEnrichmentImportState.Failed
            } }
            assertTrue(terminal.toString(), terminal is SharedEnrichmentImportState.Finished)
            assertEquals(committedFirst, firstDirectory.listFiles()!!.associate { it.name to sha256(it) })
            assertTrue(File(providerRoot(), "$secondId/song.json").isFile)
            assertTrue(staging.listFiles()!!.isEmpty())
        } finally {
            database.close()
            context.destroy()
            isolatedContext = null
        }
    }

    @Test
    fun demoProviderAcceptsActualBundleImportAndPreparedPartPublication() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val demo = app.stemwise.demo.UiDemoLibraryDocumentsProvider
        demo.initialize(targetContext)
        val tree = demo.treeUri
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        targetContext.grantUriPermission(targetContext.packageName, tree, flags)
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        var created: Uri? = null
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            val committed = roots.commitValidatedCandidate(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
            roots.publishCommittedRoot(committed)
            val root = checkNotNull(roots.captureHandle())
            assertEquals(null, roots.checkedSongSnapshot(root, VIDEO_ID))
            val governor = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
            val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
            val fixture = app.stemwise.data.CurrentSourcePublicationFixture.load(context, VIDEO_ID)
            val original = File(context.cacheDir, "original.opus").apply { writeBytes(fixture.original) }
            val archive = writeArchive(context, "demo-import.zip", payload("2026-08-09T12:40:00.000Z", "Demo import", true), COVER_PNG, original)
            val importer = SongEnrichmentImporter(context, roots, mutations, bindings, governor, originalPublisher = { publisher })
            val item = importer.importPcSong(archive, VIDEO_ID, root.authority()).getOrThrow().items.single()
            created = roots.checkedSongSnapshot(root, VIDEO_ID)?.directory?.uri
            assertTrue(item.errorCode, item.isSuccess)
            assertEquals(64, external.verifyPlaybackSong(root, VIDEO_ID).mediaRevision.length)
            fixture.publishParts(context, publisher, external, root, "demo-actual-publication")
            assertTrue(external.verifyPlaybackSong(root, VIDEO_ID).hasStems)
            assertTrue(roots.checkedAuthoritativeSongSnapshot(root, VIDEO_ID)!!.files.keys.none(SelectedLibraryContract::isPublicationStagingName))
        } finally {
            created?.let { check(DocumentsContract.deleteDocument(targetContext.contentResolver, it)) }
            database.close()
            context.destroy()
            isolatedContext = null
            targetContext.revokeUriPermission(tree, flags)
        }
    }
    @Test
    fun retainedBundleReturnsToItsDurableLibraryAndItsPayloadBytesAreAccounted() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val granted = mutableListOf<Uri>()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            fun tree(name: String): Uri {
                check(File(providerRoot(), name).mkdir())
                return DocumentsContract.buildTreeDocumentUri(GoldenQaDocumentsProvider.AUTHORITY, "root/$name").also {
                    grantProviderTree(it)
                    granted += it
                }
            }
            val treeA = tree("bundle-library-a")
            val treeB = tree("bundle-library-b")
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(treeA).getOrThrow()).getOrThrow()
            val first = checkNotNull(roots.captureHandle())
            val governor = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
            val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
            val original = File(context.cacheDir, "original.opus").apply {
                writeBytes(GoldenSongMediaFixture.originalOpus(context, 12_000L))
            }
            val staging = File(context.noBackupFilesDir, "import-staging").apply { check(mkdirs()) }
            val archive = File(staging, "${UUID.randomUUID()}-0.zip")
            check(writeArchive(context, "retained.zip", payload("2026-08-09T12:40:00.000Z", "Retained", true),
                COVER_PNG, original).renameTo(archive))
            val retainedBytes = archive.readBytes()
            val interrupted = SongEnrichmentImporter(context, roots, mutations, bindings, governor,
                originalPublisher = { publisher }, commitCheckpoint = { phase ->
                    if (phase == "BUNDLE_ORIGINAL_PUBLISHED") throw EnrichmentCommitInterruption(phase)
                })
            assertFalse(interrupted.importPcSong(archive, VIDEO_ID, first.authority()).getOrThrow().items.single().isSuccess)
            val intake = File(context.filesDir, "song-bundle-intake")
            assertTrue("Durable intake duplicated the full ZIP", intake.listFiles().orEmpty().isEmpty())
            assertTrue(retainedBytes.contentEquals(archive.readBytes()))
            // Normal startup cleanup must transfer the accepted input's ownership before retiring its caller.
            val coordinator = EnrichmentImportCoordinator(context, interrupted, external, governor, roots)
            withTimeout(5_000L) { coordinator.awaitStartupScratchReady() }
            assertFalse(archive.exists())
            val retained = intake.listFiles().orEmpty().single()
            assertTrue(retainedBytes.contentEquals(retained.readBytes()))
            roots.reportRuntimeAccessFailure(first, SecurityException("Close disposable activation for Library B"))
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(treeB).getOrThrow()).getOrThrow()
            val importer = SongEnrichmentImporter(context, roots, mutations, bindings, governor, originalPublisher = { publisher })
            importer.recoverIncompleteBundles(checkNotNull(roots.captureHandle()))
            assertTrue(retainedBytes.contentEquals(retained.readBytes()))
            roots.reportRuntimeAccessFailure(checkNotNull(roots.captureHandle()), SecurityException("Return to Library A"))
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(treeA).getOrThrow()).getOrThrow()
            val returned = checkNotNull(roots.captureHandle())
            assertEquals(first.libraryId, returned.libraryId)
            assertTrue(returned.epoch > first.epoch)
            importer.prepareBundleRecovery(returned)
            external.recoverForColdOpen(returned)
            importer.recoverIncompleteBundles(returned)
            assertEquals(64, external.verifyPlaybackSong(returned, VIDEO_ID).mediaRevision.length)
            assertTrue(intake.listFiles().orEmpty().isEmpty())
            assertTrue(File(File(File(providerRoot(), "bundle-library-a"), VIDEO_ID), SelectedLibraryContract.ORIGINAL).readBytes().contentEquals(original.readBytes()))
            assertFalse(File(File(providerRoot(), "bundle-library-b"), VIDEO_ID).exists())
        } finally {
            granted.forEach { targetContext.revokeUriPermission(it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            database.close()
            context.destroy()
            isolatedContext = null
        }
    }
    @Test
    fun verifiedReimportRepairsBrokenSidecarsAndRollsForwardAnInterruptedRepair() = runBlocking {
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
            val governor = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
            val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
            val original = File(context.cacheDir, "original.opus").apply {
                writeBytes(GoldenSongMediaFixture.originalOpus(context, 12_000L))
            }
            val archive = writeArchive(context, "repair.zip", payload("2026-08-09T12:40:00.000Z", "Repair", true), COVER_PNG, original)
            var interrupt = false
            val importer = SongEnrichmentImporter(context, roots, mutations, bindings, governor,
                originalPublisher = { publisher }, commitCheckpoint = { phase ->
                    if (interrupt && phase == ENRICHMENT_COMMIT_AFTER_COVER_WRITE) {
                        interrupt = false
                        throw EnrichmentCommitInterruption(phase)
                    }
                })
            assertTrue(importer.importPcSong(archive, VIDEO_ID, root.authority()).getOrThrow().items.single().isSuccess)
            val folder = File(providerRoot(), VIDEO_ID)
            val originalBefore = File(folder, SelectedLibraryContract.ORIGINAL).readBytes()
            for (damage in listOf("empty-cover", "empty-json", "oversized-cover", "oversized-json",
                "missing-cover", "truncated-cover", "malformed-json")) {
                for (interrupted in listOf(false, true)) {
                    when (damage) {
                        "empty-cover" -> File(folder, SelectedLibraryContract.COVER).writeBytes(byteArrayOf())
                        "empty-json" -> File(folder, SelectedLibraryContract.ENRICHMENT).writeBytes(byteArrayOf())
                        "oversized-cover", "oversized-json" -> {
                            val file = File(folder, if (damage == "oversized-cover") SelectedLibraryContract.COVER
                                else SelectedLibraryContract.ENRICHMENT)
                            java.io.RandomAccessFile(file, "rw").use { it.setLength(16L * 1024L * 1024L + 1L) }
                        }
                        "missing-cover" -> check(File(folder, SelectedLibraryContract.COVER).delete())
                        "truncated-cover" -> File(folder, SelectedLibraryContract.COVER).writeBytes(COVER_PNG.copyOf(12))
                        else -> File(folder, SelectedLibraryContract.ENRICHMENT).writeText("{broken", Charsets.UTF_8)
                    }
                    interrupt = interrupted
                    val item = importer.importPcSong(archive, VIDEO_ID, root.authority()).getOrThrow().items.single()
                    if (interrupted) {
                        assertFalse("Repair interruption was not reached: $damage", interrupt)
                        assertFalse(item.isSuccess)
                        external.recoverForColdOpen(root)
                        importer.recoverIncompleteBundles(root)
                    } else assertTrue("$damage: ${item.errorCode}", item.isSuccess)
                    val verified = external.verifyPlaybackSong(root, VIDEO_ID)
                    assertEquals(64, verified.mediaRevision.length)
                    assertTrue(originalBefore.contentEquals(File(folder, SelectedLibraryContract.ORIGINAL).readBytes()))
                    validateEnrichmentCoverPng(File(folder, SelectedLibraryContract.COVER).readBytes())
                    assertTrue(File(context.filesDir, "song-bundle-intake").listFiles().orEmpty().isEmpty())
                }
            }
        } finally {
            database.close()
            context.destroy()
            isolatedContext = null
        }
    }
    @Test
    fun pcOriginalCreatesANewSongWithoutPlaylistAndConflictingOriginalPreservesIt() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for ((native, lossless) in listOf(false to false, false to true, true to true)) {
            resetProvider()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            grantProviderTree()
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(
                    roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val background = BackgroundWorkCoordinator()
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val repository = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
                repository.reconcile()
                assertEquals(null, dao.findSongById(VIDEO_ID))
                val original = File(context.cacheDir, if (lossless) "original.flac" else "original.opus")
                if (lossless) {
                    testContext.assets.open("encoder/flac24-stereo-96000.flac").use { input ->
                        original.outputStream().use(input::copyTo)
                    }
                } else original.writeBytes(GoldenSongMediaFixture.originalOpus(targetContext, 12_000L))
                val document = if (lossless) targetContext.assets.open(
                    "qa/enrichment/native-flac-song-enrichment-v2.json").bufferedReader().use { JSONObject(it.readText()) }
                        .also { if (!native) it.getJSONObject("source").remove("recording") }
                    else payload("2026-08-09T12:40:00.000Z", "PC Original", true)
                val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, repository, background)
                val importer = SongEnrichmentImporter(context, roots, mutations, bindings, background,
                    originalPublisher = { publisher })
                val archive = writeArchive(context, "with-original.zip", document, COVER_PNG, original)
                val expectedRoot = checkNotNull(roots.captureHandle()).authority()
                val publicBefore = providerRoot().walkTopDown().filter(File::isFile)
                    .associate { it.relativeTo(providerRoot()).path to it.readBytes().toList() }
                val missingOriginal = writeArchive(context, "no-original.zip", document, COVER_PNG)
                val missingId = writeArchive(context, "no-id.zip", JSONObject(document.toString()).also {
                    it.getJSONObject("source").remove("videoId")
                }, COVER_PNG, original)
                val rawJson = File(context.cacheDir, "metadata.json").also {
                    it.writeText(document.toString(), Charsets.UTF_8)
                }
                val multiple = File(context.cacheDir, "multiple.zip").also { bulk ->
                    ZipOutputStream(bulk.outputStream()).use { zip ->
                        listOf("one.zip", "two.zip").forEach { name ->
                            zip.putNextEntry(ZipEntry(name))
                            archive.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                for ((input, expectedId) in listOf(archive to "tWEd6wxP6eM",
                    missingOriginal to VIDEO_ID, missingId to VIDEO_ID, rawJson to VIDEO_ID,
                    multiple to VIDEO_ID)) {
                    assertTrue(importer.importPcSong(input, expectedId, expectedRoot).isFailure)
                    assertEquals(publicBefore, providerRoot().walkTopDown().filter(File::isFile)
                        .associate { it.relativeTo(providerRoot()).path to it.readBytes().toList() })
                    assertTrue(dao.allSongs().isEmpty())
                }
                val result = importer.importPcSong(archive, VIDEO_ID, expectedRoot)
                    .getOrThrow().items.single()
                assertEquals(result.errorCode, EnrichmentImportOutcome.IMPORTED, result.outcome)
                repository.reconcile()
                assertEquals(1, repository.songs.value.size)
                assertEquals(0, dao.allRemotePlaylists().size)
                assertTrue(checkNotNull(dao.findSongById(VIDEO_ID)).packageDocumentUri != null)
                val folder = File(providerRoot(), VIDEO_ID)
                val installedOriginal = File(folder, original.name)
                val originalHash = sha256(installedOriginal)
                val manifest = File(folder, SelectedLibraryContract.ENRICHMENT)
                val manifestBytes = manifest.readBytes()
                assertEquals(originalHash, checkNotNull(dao.currentSource(VIDEO_ID)).sha256)
                assertEquals(EnrichmentImportOutcome.UNCHANGED,
                    importer.importStagedFiles(listOf(archive)).getOrThrow().items.single().outcome)
                if (lossless) {
                    val conflict = File(context.cacheDir, "original.opus").also {
                        it.writeBytes(GoldenSongMediaFixture.originalOpus(targetContext, 12_000L))
                    }
                    val other = writeArchive(context, "different-original.zip",
                        payload("2026-08-09T12:50:00.000Z", "Replacement", true), COVER_PNG, conflict)
                    assertEquals("ORIGINAL_CONFLICT",
                        importer.importStagedFiles(listOf(other)).getOrThrow().items.single().errorCode)
                    assertEquals(originalHash, sha256(installedOriginal))
                    assertTrue(manifestBytes.contentEquals(manifest.readBytes()))
                    assertFalse(File(folder, "original.opus").exists())
                }
            } finally {
                database.close()
                context.destroy()
                isolatedContext = null
            }
        }
    }

    @Test
    fun ownerRecordingReplacementWithdrawsUntilItsCompleteBundleArrives() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            seedDirectMedia(withEnrichment = true)
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val repository = ExternalLibraryRepository(context, roots, dao, bindings, mutations, BackgroundWorkCoordinator())
            repository.reconcile()
            val previous = repository.songs.value.single()
            assertEquals("opus", previous.sourceCodec)
            assertTrue(previous.hasStems)
            val folder = File(providerRoot(), VIDEO_ID)
            check(File(folder, SelectedLibraryContract.ORIGINAL).delete())
            val original = File(folder, SelectedLibraryContract.ORIGINAL_FLAC)
            testContext.assets.open("encoder/flac24-stereo-96000.flac").use { input ->
                original.outputStream().use(input::copyTo)
            }
            repository.reconcile()
            assertTrue(repository.problems.value.toString(), repository.songs.value.isEmpty())
            check(File(folder, SelectedLibraryContract.ENRICHMENT).delete())
            check(File(folder, SelectedLibraryContract.COVER).delete())
            val nativeDocument = targetContext.assets.open("qa/enrichment/native-flac-song-enrichment-v2.json")
                .bufferedReader().use { JSONObject(it.readText()) }
            val archive = writeArchive(context, "owner-native-replacement.zip", nativeDocument, COVER_PNG, original)
            val imported = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database)
                .importStagedFiles(listOf(archive)).getOrThrow().items.single()
            assertTrue(imported.toString(), imported.isSuccess)
            repository.reconcile()
            assertEquals(repository.problems.value.toString(), 1, repository.songs.value.size)
            val replacement = repository.songs.value.single()
            assertEquals(previous.id, replacement.id)
            assertEquals("flac", replacement.sourceCodec)
            assertEquals("flac", replacement.sourceContainer)
            assertEquals(1_000L, replacement.durationMs)
            assertTrue(replacement.sourceHash != previous.sourceHash)
            assertTrue(replacement.sourcePath!!.endsWith("original.flac"))
            assertFalse(replacement.hasStems)
            assertEquals(64, replacement.mediaRevision.length)
            assertTrue(replacement.lyricTracks.isEmpty())
            assertEquals(1, dao.allSongs().size)
            assertEquals(96_000, dao.currentSource(VIDEO_ID)?.sampleRateHz)
            assertEquals(replacement.sourcePath, roots.resolveAuthoritativeSongAsset(handle, VIDEO_ID,
                SelectedLibraryContract.ORIGINAL)?.toString())
            repository.reconcile()
            assertEquals(replacement.sourceHash, repository.songs.value.single().sourceHash)
        } finally { database.close() }
    }

    @Test
    fun nativeFlacEnrichmentImportsReopensAndRejectsWrongRecordingWithoutMutation() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            seedDirectMedia()
            val folder = File(providerRoot(), VIDEO_ID)
            check(File(folder, SelectedLibraryContract.ORIGINAL).delete())
            val original = File(folder, SelectedLibraryContract.ORIGINAL_FLAC)
            testContext.assets.open("encoder/flac24-stereo-96000.flac").use { input ->
                original.outputStream().use(input::copyTo)
            }
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val repository = ExternalLibraryRepository(context, roots, dao, bindings, mutations,
                BackgroundWorkCoordinator())
            repository.reconcile()
            assertTrue(repository.songs.value.isEmpty())
            val importer = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database = database)
            val document = targetContext.assets.open("qa/enrichment/native-flac-song-enrichment-v2.json")
                .bufferedReader().use { JSONObject(it.readText()) }
            assertEquals(sha256(original), document.getJSONObject("source").getJSONObject("recording").getString("sha256"))
            assertEquals(original.length(), document.getJSONObject("source").getJSONObject("recording").getLong("byteCount"))
            suspend fun assertNativeArchiveRejected(name: String, json: JSONObject) {
                val archive = writeArchive(context, name, json, COVER_PNG, original)
                val archiveHash = sha256(archive)
                val rejected = importer.importStagedFiles(listOf(archive), listOf(name))
                    .getOrThrow().items.single()
                assertFalse(name, rejected.isSuccess)
                assertEquals(name, "SOURCE_BINDING_CONFLICT", rejected.errorCode)
                assertEquals(name, rejected.sourceName)
                assertEquals(archiveHash, sha256(archive))
            }
            val beforeRejectedImport = folder.listFiles()!!.associate { it.name to sha256(it) }
            val malformedMarker = JSONObject(document.toString()).also {
                it.getJSONObject("source").put("recording", JSONObject.NULL)
            }
            assertNativeArchiveRejected("malformed-recording.zip", malformedMarker)
            assertEquals(beforeRejectedImport, folder.listFiles()!!.associate { it.name to sha256(it) })
            assertFalse(File(folder, SelectedLibraryContract.ENRICHMENT).exists())
            val nativeArchive = File(context.cacheDir, "pc-native.zip")
            testContext.assets.open("qa/pc-builder-enrichment/native-flac-song-enrichment-v2.stemwise.zip").use { input ->
                nativeArchive.outputStream().use(input::copyTo)
            }
            val imported = importer.importStagedFiles(listOf(nativeArchive), listOf(nativeArchive.name)).getOrThrow().items.single()
            assertEquals(imported.errorCode, EnrichmentImportOutcome.IMPORTED, imported.outcome)
            val manifestFile = File(folder, SelectedLibraryContract.ENRICHMENT)
            val installedBytes = manifestFile.readBytes()
            val installed = parseStrictManifestJson(installedBytes)
            assertEquals(setOf(SelectedLibraryContract.ORIGINAL_FLAC), installedMediaIdentities(installed).keys)
            assertEquals(document.getJSONObject("source").getJSONObject("recording").toString(),
                installed.getJSONObject("source").getJSONObject("recording").toString())
            repository.reconcile()
            assertEquals(repository.problems.value.toString(), 1, repository.songs.value.size)
            assertEquals(64, repository.songs.value.single().mediaRevision.length)
            assertEquals("Native FLAC enriched", repository.songs.value.single().title)
            val reopened = ExternalLibraryRepository(context, roots, dao, bindings, mutations,
                BackgroundWorkCoordinator())
            reopened.reconcile()
            assertEquals(64, reopened.songs.value.single().mediaRevision.length)
            assertEquals("flac", reopened.songs.value.single().sourceCodec)
            val coverBytes = File(folder, SelectedLibraryContract.COVER).readBytes()
            val installedFiles = folder.listFiles()!!.associate { it.name to sha256(it) }
            val installedSource = dao.currentSource(VIDEO_ID)
            val installedSongs = dao.allSongs()
            val installedBindings = dao.sourceBindingsForLibrary(checkNotNull(roots.captureHandle()).libraryId)
            listOf("sha256" to "b".repeat(64), "byteCount" to (original.length() + 1), "durationMs" to 1020L)
                .forEachIndexed { index, (field, wrong) ->
                    val bad = JSONObject(document.toString()).put("generatedAt", "2026-08-09T12:01:00Z")
                    bad.getJSONObject("source").getJSONObject("recording").put(field, wrong)
                    assertNativeArchiveRejected("wrong-$field-$index.zip", bad)
                    assertTrue(installedBytes.contentEquals(manifestFile.readBytes()))
                    assertTrue(coverBytes.contentEquals(File(folder, SelectedLibraryContract.COVER).readBytes()))
                    assertEquals(installedFiles, folder.listFiles()!!.associate { it.name to sha256(it) })
                    assertEquals(installedSource, dao.currentSource(VIDEO_ID))
                    assertEquals(installedSongs, dao.allSongs())
                    assertEquals(installedBindings,
                        dao.sourceBindingsForLibrary(checkNotNull(roots.captureHandle()).libraryId))
                }
            val repeat = writeArchive(context, "repeat-native.zip", document, coverBytes, original)
            assertEquals(EnrichmentImportOutcome.UNCHANGED,
                importer.importStagedFiles(listOf(repeat), listOf(repeat.name)).getOrThrow().items.single().outcome)
            val preserved = parseStrictManifestJson(manifestFile.readBytes())
            val malformed = JSONObject(preserved.toString())
            malformed.getJSONObject("metadata").put("artists", "Wrong scalar type")
            malformed.getJSONObject("install").put("generatedDigest", installedEnrichmentDigest(malformed))
            val malformedBytes = malformed.toString().toByteArray(Charsets.UTF_8)
            manifestFile.writeBytes(malformedBytes)
            val invalidReopen = ExternalLibraryRepository(context, roots, dao, bindings, mutations,
                BackgroundWorkCoordinator())
            invalidReopen.reconcile()
            val invalidProblems = invalidReopen.problems.value.joinToString { "${it.code}:${it.message}" }
            assertTrue(invalidProblems, invalidReopen.songs.value.isEmpty())
            assertTrue(invalidProblems, invalidReopen.problems.value.any { it.code == "MANIFEST_JSON_INVALID" })
            assertEquals(installed.getJSONObject("install").getString("originalSha256"), sha256(original))
            assertTrue(malformedBytes.contentEquals(manifestFile.readBytes()))
            assertTrue(coverBytes.contentEquals(File(folder, SelectedLibraryContract.COVER).readBytes()))
        } finally { database.close() }
    }

    @Test
    fun nativeRemainingRequiresCurrentProducerAndPreservesRejectedOwnerBytes() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val engine = YoutubeDlEngine(targetContext, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null)
        for (native96k in listOf(true, false)) {
            resetProvider()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            grantProviderTree()
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(
                    roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val handle = checkNotNull(roots.captureHandle())
                val fixture = app.stemwise.data.CurrentSourcePublicationFixture.load(targetContext, VIDEO_ID)
                val folder = File(providerRoot(), VIDEO_ID).apply { check(mkdir()) }
                File(folder, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original)
                if (native96k) {
                    assertTrue(File(folder, SelectedLibraryContract.ORIGINAL).delete())
                    testContext.assets.open("encoder/flac24-stereo-96000.flac").use { input ->
                        File(folder, SelectedLibraryContract.ORIGINAL_FLAC).outputStream().use(input::copyTo)
                    }
                }
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val repository = ExternalLibraryRepository(context, roots, dao, bindings, mutations,
                    BackgroundWorkCoordinator())
                repository.reconcile()
                if (native96k) {
                    val archive = File(context.cacheDir, "pc-native.zip")
                    testContext.assets.open("qa/pc-builder-enrichment/native-flac-song-enrichment-v2.stemwise.zip").use { input ->
                        archive.outputStream().use(input::copyTo)
                    }
                    val item = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database = database)
                        .importStagedFiles(listOf(archive), listOf(archive.name)).getOrThrow().items.single()
                    assertEquals(item.errorCode, EnrichmentImportOutcome.IMPORTED, item.outcome)
                    repository.reconcile()
                } else {
                    val archive = writeArchive(context, "pc-opus.zip",
                        payload("2026-08-09T12:00:00Z", "Opus source preparation", true), COVER_PNG)
                    val item = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database)
                        .importPcSong(archive, VIDEO_ID, handle.authority()).getOrThrow().items.single()
                    assertEquals(item.errorCode, EnrichmentImportOutcome.IMPORTED, item.outcome)
                    repository.reconcile()
                }
                val original = File(folder, if (native96k) SelectedLibraryContract.ORIGINAL_FLAC else SelectedLibraryContract.ORIGINAL)
                val frames = inspectOriginalAudio(original).audibleFrames48k
                val target = File(folder, "part-1.flac")
                val remaining = File(folder, "remaining.wv")
                engine.encodeLosslessPart(original, target, 16L * 1024 * 1024, 2)
                engine.renderRemaining(original, listOf(target), remaining, 16L * 1024 * 1024, 2)
                val targetAsset = CustomSourceAsset("bass", "Bass", target.name, sha256(target), target.length(), frames)
                val remainingAsset = CustomSourceAsset("remaining", "Remaining", remaining.name, sha256(remaining),
                    remaining.length(), frames, NATIVE_ORIGINAL_48K_REMAINING_PRODUCER)
                val plan = MvsepSourcePlan(listOf("bass"), listOf(MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                    setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), mapOf("bass" to "bass"))))
                var authority = createPortableSourceJobAuthority(handle.libraryId, VIDEO_ID,
                    checkNotNull(dao.currentSource(VIDEO_ID)).id, sha256(original), plan, 1_780_000_000_000L)
                fun step(transition: SourceJobTransition, remoteId: String? = null) {
                    authority = transitionPortableSourceJob(authority, transition, authority.updatedAtEpochMs + 1, remoteId)
                }
                step(SourceJobTransition.ENTER_SUBMISSION)
                step(SourceJobTransition.ACKNOWLEDGE_REMOTE, "owned-remote")
                step(SourceJobTransition.BEGIN_DOWNLOAD)
                authority = recordPortableSourceAsset(authority, targetAsset, authority.updatedAtEpochMs + 1)
                step(SourceJobTransition.BEGIN_ASSEMBLY)
                authority = recordPortableSourceAsset(authority, remainingAsset, authority.updatedAtEpochMs + 1)
                step(SourceJobTransition.BEGIN_PUBLICATION)
                step(SourceJobTransition.COMPLETE)
                val sourceManifest = File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST)
                val current = CustomSourceManifest(VIDEO_ID, sha256(original), authority.planSha256,
                    listOf("bass"), frames, listOf(targetAsset, remainingAsset))
                sourceManifest.writeBytes(current.toBytes())
                assertEquals(current, parseCustomSourceManifest(sourceManifest.readBytes(), VIDEO_ID, sha256(original)))
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(target.absolutePath)
                    val format = extractor.getTrackFormat(0)
                    val facts = inspectPreparedSourceAudio(target)
                    try {
                        validateCustomSourceAsset(targetAsset, sha256(target), target.length(), facts,
                            format.getString(MediaFormat.KEY_MIME).orEmpty(),
                            format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    } catch (error: IllegalStateException) {
                        throw AssertionError("Published FLAC admission failed: header=$facts extractor=$format", error)
                    }
                } finally { extractor.release() }
                validateCustomSourceAudioAsset(remainingAsset, sha256(remaining), remaining.length(),
                    inspectPreparedSourceAudio(remaining))
                val jobFile = File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY)
                jobFile.writeBytes(authority.toBytes())
                repository.reconcile()
                assertEquals(repository.problems.value.toString(), 1, repository.songs.value.size)
                assertTrue(repository.songs.value.single().hasStems)
                assertTrue(repository.problems.value.none { it.code == SOURCE_REMAINING_REPREPARE_REQUIRED })
                val reopened = ExternalLibraryRepository(context, roots, dao, bindings, mutations, BackgroundWorkCoordinator())
                for (producer in listOf<String?>(null, "unknown-remaining-producer")) {
                    val malformed = JSONObject(current.toBytes().toString(Charsets.UTF_8))
                    val residual = malformed.getJSONArray("lanes").getJSONObject(1)
                    if (producer == null) residual.remove("remainingProducer") else residual.put("remainingProducer", producer)
                    malformed.remove("integritySha256")
                    malformed.put("integritySha256", app.stemwise.data.CanonicalJson.sha256(malformed))
                    sourceManifest.writeBytes(app.stemwise.data.CanonicalJson.encode(malformed))
                    val rejectedBytes = folder.listFiles().orEmpty().associate { it.name to it.readBytes() }
                    reopened.reconcile()
                    assertTrue("An invalid Remaining producer was admitted", reopened.songs.value.none { it.hasStems })
                    assertTrue(reopened.problems.value.any { it.code == "SOURCE_MANIFEST_INVALID" })
                    rejectedBytes.forEach { (name, bytes) ->
                        assertTrue(bytes.contentEquals(File(folder, name).readBytes()))
                    }
                }
                sourceManifest.writeBytes(current.toBytes())
                reopened.reconcile()
                assertTrue(reopened.songs.value.single().hasStems)
                val restoredSet = reopened.observeStemSets(VIDEO_ID).first().single()
                val expectedSetId = "$VIDEO_ID:sources:${current.revisionSha256}"
                assertEquals(expectedSetId, restoredSet.id)
                assertEquals(VIDEO_ID, restoredSet.songId)
                assertEquals(checkNotNull(dao.currentSource(VIDEO_ID)).id, restoredSet.sourceVersionId)
                assertEquals(current.sourceSha256, restoredSet.sourceSha256)
                assertEquals("source-plan:${current.planSha256}", restoredSet.modelId)
                assertEquals("COMPLETE", restoredSet.state)
                assertTrue(restoredSet.isActive)
                val restoredLanes = reopened.observeActiveStems(VIDEO_ID).first()
                    .associateBy { it.normalizedCapability }
                assertEquals(setOf("bass", "remaining"), restoredLanes.keys)
                current.lanes.forEach { lane ->
                    val restored = restoredLanes.getValue(lane.sourceId)
                    assertEquals("$expectedSetId:${lane.sourceId}", restored.id)
                    assertEquals(expectedSetId, restored.separationSetId)
                    assertEquals(lane.sha256, restored.sha256)
                    assertEquals(if (lane.sourceId == "remaining") "wavpack" else "flac", restored.codec)
                    assertEquals(48_000, restored.sampleRateHz)
                    assertEquals(2, restored.channelCount)
                    assertTrue(restored.validated)
                }
                val before = folder.listFiles().orEmpty().associate { it.name to it.readBytes() }
                if (native96k) {
                    assertEquals(64, reopened.songs.value.single().mediaRevision.length)
                    ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao()).deleteStems(handle, VIDEO_ID).getOrThrow()
                    val retained = setOf(SelectedLibraryContract.ORIGINAL_FLAC, SelectedLibraryContract.ENRICHMENT,
                        SelectedLibraryContract.COVER)
                    assertEquals(retained, folder.listFiles().orEmpty().map { it.name }.toSet())
                    retained.forEach { name -> assertTrue(before.getValue(name).contentEquals(File(folder, name).readBytes())) }
                    reopened.reconcile()
                    assertEquals(1, reopened.songs.value.size)
                    assertFalse(reopened.songs.value.single().hasStems)
                    assertEquals(64, reopened.songs.value.single().mediaRevision.length)
                    assertTrue(reopened.problems.value.none { it.code == SOURCE_REMAINING_REPREPARE_REQUIRED })
                }
            } finally { database.close(); context.destroy(); isolatedContext = null }
        }
    }

}
