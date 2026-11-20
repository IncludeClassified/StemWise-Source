package app.stemwise.enrichment

import app.stemwise.data.seedVerifiedBinding

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class GoldenEnrichmentImportInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun newestCompleteBundleSupersedesOldConflictsAndPreservesMatchingInstalledLanes() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            seedVerifiedBinding(bindings, mutations, root, SOURCE_ID, VIDEO_ID)
            val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, BackgroundWorkCoordinator())
            external.reconcile()
            val folder = File(providerRoot(), VIDEO_ID)
            val lanesBefore = currentSourcePackageFiles.associateWith { File(folder, it).readBytes() }
            val importer = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database)
            var publications = 0
            importer.bundleCommittedForTest = { publications++ }
            val oldA = writeArchive(context, "old-a.zip", payload("2026-08-09T12:00:00Z", "Old A", true), COVER_PNG)
            val oldB = writeArchive(context, "old-b.zip", payload("2026-08-09T12:00:00Z", "Old B", true), COVER_PNG)
            val newest = writeArchive(context, "newest.zip", payload("2026-08-09T12:30:00Z", "Newest complete song", true), COVER_PNG)
            val result = importer.importStagedFiles(listOf(oldB, newest, oldA)).getOrThrow()
            assertEquals(1, result.importedCount)
            assertEquals(2, result.supersededCount)
            assertEquals(0, result.failedCount)
            assertEquals(1, publications)
            val installed = File(folder, SelectedLibraryContract.ENRICHMENT)
            assertEquals("Newest complete song", JSONObject(installed.readText(Charsets.UTF_8)).getJSONObject("metadata").getString("title"))
            lanesBefore.forEach { (name, bytes) -> assertTrue(name, bytes.contentEquals(File(folder, name).readBytes())) }
            val committed = installed.readBytes()
            val conflictA = writeArchive(context, "conflict-a.zip", payload("2026-08-09T13:00:00Z", "Conflict A", true), COVER_PNG)
            val conflictB = writeArchive(context, "conflict-b.zip", payload("2026-08-09T13:00:00Z", "Conflict B", true), COVER_PNG)
            val conflict = importer.importStagedFiles(listOf(conflictA, oldA, conflictB)).getOrThrow()
            assertEquals(3, conflict.failedCount)
            assertEquals(0, conflict.supersededCount)
            assertEquals(listOf("ENRICHMENT_REVISION_CONFLICT", "ENRICHMENT_REPLACEMENT_UNAVAILABLE",
                "ENRICHMENT_REVISION_CONFLICT"), conflict.items.map { it.errorCode })
            assertTrue(committed.contentEquals(installed.readBytes()))
            val duplicate = importer.importStagedFiles(listOf(newest, newest)).getOrThrow()
            assertEquals(2, duplicate.unchangedCount)
            assertEquals(0, duplicate.failedCount)
            assertEquals(1, publications)
            assertTrue(committed.contentEquals(installed.readBytes()))
            lanesBefore.forEach { (name, bytes) -> assertTrue(name, bytes.contentEquals(File(folder, name).readBytes())) }
        } finally { database.close() }
    }

    @Test fun currentStagingRestoresThroughStartupAndFutureSchemaPreservesEveryOwnedByte() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for ((future, phase, completed) in listOf(Triple(false, "Validating import", 0L),
            Triple(false, "Applying enrichment", 1L), Triple(true, "awaiting_confirmation", 0L))) {
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
                val handle = checkNotNull(roots.captureHandle())
                val staging = File(context.noBackupFilesDir, "import-staging").also { check(it.mkdir()) }
                // stageAll owns only session-id/index filenames; a journal cannot adopt arbitrary names.
                val staged = File(staging, "${UUID.randomUUID()}-0.json").also { it.writeBytes("retained-input".toByteArray()) }
                val journal = File(staging, app.stemwise.enrichment.ENRICHMENT_STAGING_JOURNAL_NAME)
                journal.writeBytes(JSONObject().put("schemaVersion", if (future) 4L else 3L)
                    .put("libraryId", handle.libraryId).put("treeUri", handle.treeUri.toString())
                    .put("rootEpoch", handle.epoch).put("phase", phase).put("stagingComplete", true)
                    .put("completed", completed).put("total", 2L)
                    .put("stagedFiles", JSONArray().put(JSONObject().put("path", staged.name)
                        .put("sourceName", "owner-input.json"))).toString().toByteArray())
                val before = staging.listFiles()!!.associate { it.name to it.readBytes().toList() }
                val background = BackgroundWorkCoordinator()
                val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
                val repository = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, background)
                val coordinator = EnrichmentImportCoordinator(context,
                    songImporter(context, roots, mutations, bindings, background, database = database), repository, background, roots)
                val restored = withTimeout(5_000L) { runCatching { coordinator.awaitStartupScratchReady() } }
                if (future) {
                    assertEquals("IMPORT_STAGING_FUTURE_SCHEMA", restored.exceptionOrNull()?.message)
                    assertTrue(coordinator.state.value is SharedEnrichmentImportState.Failed)
                    val cleanup = withTimeout(5_000L) { runCatching { coordinator.cancelForLibraryChange() } }
                    assertEquals("IMPORT_STAGING_FUTURE_SCHEMA", cleanup.exceptionOrNull()?.message)
                } else {
                    restored.getOrThrow()
                    val confirmation = coordinator.state.value as SharedEnrichmentImportState.AwaitingConfirmation
                    assertEquals(listOf(Uri.fromFile(staged)), confirmation.sources)
                    assertEquals(listOf("owner-input.json"), confirmation.sourceNames)
                }
                assertEquals(before, staging.listFiles()!!.associate { it.name to it.readBytes().toList() })
                coordinator.onTaskRemoved()
                val removed = withTimeout(5_000L) { runCatching { coordinator.cancelForLibraryChange() } }
                if (future) {
                    assertEquals("IMPORT_STAGING_FUTURE_SCHEMA", removed.exceptionOrNull()?.message)
                    assertEquals(before, staging.listFiles()!!.associate { it.name to it.readBytes().toList() })
                } else {
                    removed.getOrThrow()
                    assertTrue(coordinator.state.value is SharedEnrichmentImportState.Idle)
                    assertTrue(staging.listFiles()!!.isEmpty())
                }
            } finally {
                database.close()
                context.destroy()
                isolatedContext = null
            }
        }
    }

    @Test
    fun productionImporterAndRepositoryProjectOneCurrentInstalledSong() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
        val mutations = PackageMutationCoordinator(context)
        val roots = LibraryRootManager(context, mutations, database.libraryDao())
        val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
        roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
        val handle = checkNotNull(roots.captureHandle())
        seedDirectMedia()
        setFault(GoldenQaDocumentsProvider.FAULT_UNKNOWN_SIZE)

        val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
        seedVerifiedBinding(bindings, mutations, handle, SOURCE_ID, VIDEO_ID)
        val repository = ExternalLibraryRepository(
            context,
            roots,
            database.libraryDao(),
            bindings,
            mutations,
            BackgroundWorkCoordinator()
        )
        repository.reconcile()
        val importer = songImporter(
            context,
            roots,
            mutations,
            bindings,
            BackgroundWorkCoordinator(), database = database)
        val initialArchive = writeArchive(
            context,
            "initial.stemwise.zip",
            payload("2026-08-09T12:00:00Z", "Golden direct title", includeArtwork = true),
            COVER_PNG
        )
        val imported = importer.importStagedFiles(
            listOf(initialArchive),
            listOf(initialArchive.name)
        ).getOrThrow()
        assertEquals(
            imported.items.single().errorCode,
            EnrichmentImportOutcome.IMPORTED,
            imported.items.single().outcome
        )
        assertEquals(VIDEO_ID, imported.items.single().packageId)

        val folder = File(providerRoot(), VIDEO_ID)
        assertEquals((currentSourcePackageFiles + listOf("cover.png", "song.json")).toSet(),
            folder.list().orEmpty().toSet())
        assertTrue(File(folder, SelectedLibraryContract.COVER).isFile)
        val installed = parseStrictManifestJson(
            File(folder, SelectedLibraryContract.ENRICHMENT).readBytes()
        )
        val originalHash = installed.getJSONObject("install").getString("originalSha256")
        validateInstalledEnrichmentDocument(installed, VIDEO_ID, originalHash)
        assertEquals(
            listOf(SelectedLibraryContract.ORIGINAL),
            installedMediaIdentities(installed).keys.toList()
        )
        val artworkKey = installed.getJSONObject("artwork").getString("sha256")
        assertEquals("selected-root", installed.getJSONObject("artwork").getString("delivery"))
        assertEquals("cover.png", installed.getJSONObject("artwork").getString("file"))
        assertEquals(artworkKey, sha256(File(folder, SelectedLibraryContract.COVER)))

        val updateArchive = writeArchive(
            context,
            "song-update.zip",
            payload("2026-08-09T12:01:00Z", "Updated direct title", includeArtwork = true), COVER_PNG)
        val updated = importer.importStagedFiles(
            listOf(updateArchive),
            listOf(updateArchive.name)
        ).getOrThrow()
        assertEquals(EnrichmentImportOutcome.UPDATED, updated.items.single().outcome)
        val afterUpdate = parseStrictManifestJson(
            File(folder, SelectedLibraryContract.ENRICHMENT).readBytes()
        )
        assertEquals(artworkKey, afterUpdate.getJSONObject("artwork").getString("sha256"))
        assertEquals(artworkKey, sha256(File(folder, SelectedLibraryContract.COVER)))

        val timestampCorpus = InstrumentationRegistry.getInstrumentation().context.assets.open(TIMESTAMP_CORPUS).bufferedReader().use {
            JSONObject(it.readText())
        }
        val positiveTimestamps = timestampCorpus.getJSONArray("positive")
        var installedTimestamp = afterUpdate.getString("generatedAt")
        repeat(positiveTimestamps.length()) { index ->
            val timestamp = positiveTimestamps.getString(index)
            val archive = writeArchive(
                context,
                "timestamp-positive-$index.zip",
                payload(timestamp, "Updated direct title", includeArtwork = true), COVER_PNG)
            val timestampImport = importer.importStagedFiles(
                listOf(archive),
                listOf(archive.name)
            ).getOrThrow().items.single()
            if (Instant.parse(timestamp).isAfter(Instant.parse(installedTimestamp))) {
                assertEquals(timestampImport.errorCode, EnrichmentImportOutcome.UPDATED, timestampImport.outcome)
                installedTimestamp = timestamp
            } else {
                assertEquals(EnrichmentImportOutcome.INVALID, timestampImport.outcome)
                assertEquals("ENRICHMENT_UPDATE_STALE", timestampImport.errorCode)
            }
            val timestampInstalled = parseStrictManifestJson(
                File(folder, SelectedLibraryContract.ENRICHMENT).readBytes()
            )
            assertEquals(installedTimestamp, timestampInstalled.getString("generatedAt"))
            validateInstalledEnrichmentDocument(timestampInstalled, VIDEO_ID, originalHash)
        }

            repository.reconcile()
            assertTrue(
                repository.problems.value.joinToString { "${it.code}:${it.message}" },
                repository.problems.value.isEmpty()
            )
            assertEquals(1, repository.songs.value.size)
            assertEquals("Updated direct title", repository.songs.value.single().title)
            assertEquals(VIDEO_ID, repository.songs.value.single().videoId)
            assertEquals(64, repository.songs.value.single().mediaRevision.length)
            assertEquals(1, repository.storageRefreshState.value.inventory.verifiedSongPackages)
            assertTrue(File(folder, SelectedLibraryContract.COVER).isFile)
            val activeManifest = File(folder, SelectedLibraryContract.ENRICHMENT)
            assertEquals(
                "unavailable",
                ImportedAnalysisRepository(context, roots).summary(
                    VIDEO_ID,
                    sha256(activeManifest),
                    originalHash
                )?.status
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun sharedInstalledV2NegativeCorpusFailsClosed() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val corpus = InstrumentationRegistry.getInstrumentation().context.assets.open(NEGATIVE_CORPUS).bufferedReader().use {
            JSONObject(it.readText())
        }
        val rawCases = corpus.getJSONArray("rawCases")
        repeat(rawCases.length()) { index ->
            val testCase = rawCases.getJSONObject(index)
            var bytes = if (testCase.has("hex")) {
                testCase.getString("hex").chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
            } else {
                testCase.getString("text").toByteArray(Charsets.UTF_8)
            }
            if (testCase.optBoolean("prefixBom")) {
                bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + bytes
            }
            assertTrue(testCase.getString("name"), runCatching {
                parseStrictManifestJson(bytes)
            }.isFailure)
        }

        val valid = validInstalledDocument()
        val mutations = corpus.getJSONArray("mutationCases")
        repeat(mutations.length()) { index ->
            val name = mutations.getJSONObject(index).getString("name")
            val changed = JSONObject(valid.toString())
            when (name) {
                "missing-generated-at" -> changed.remove("generatedAt")
                "wrong-generator-type" -> changed.put("generator", JSONArray())
                "fractional-content-revision" ->
                    changed.getJSONObject("install").put("contentRevision", 1.5)
                "string-byte-length" -> changed.getJSONObject("media")
                    .getJSONArray("files").getJSONObject(0).put("byteLength", "1000")
                "non-object-optional-group" -> changed.put("lyrics", JSONArray())
                "non-canonical-generated-at" ->
                    changed.put("generatedAt", "2026-08-09T12:00:00.0Z")
                "open-root" -> changed.put("extra", true)
                "open-source" -> changed.getJSONObject("source").put("extra", true)
                "open-generator" -> changed.getJSONObject("generator").put("extra", true)
                "open-media" -> changed.getJSONObject("media").put("extra", true)
                "open-media-entry" -> changed.getJSONObject("media")
                    .getJSONArray("files").getJSONObject(0).put("extra", true)
                "open-install" -> changed.getJSONObject("install").put("extra", true)
                "open-artwork" -> changed.getJSONObject("artwork").put("extra", true)
                "open-artwork-source" -> changed.getJSONObject("artwork")
                    .getJSONObject("source").put("extra", true)
                "missing-artwork" -> changed.remove("artwork")
                "missing-artwork-file" -> changed.getJSONObject("artwork").remove("file")
                "wrong-artwork-file" -> changed.getJSONObject("artwork")
                    .put("file", "Cover.png")
                "uppercase-artwork-hash" -> changed.getJSONObject("artwork")
                    .put("sha256", "A".repeat(64))
                "extra-media" -> changed.getJSONObject("media")
                    .getJSONArray("files").let { files ->
                        val first = JSONObject(files.getJSONObject(0).toString())
                        files.put(JSONObject(first.toString()))
                    }
                "case-changed-path" -> changed.getJSONObject("media")
                    .getJSONArray("files").getJSONObject(0).put("path", "Original.opus")
                "wrong-source-id" -> changed.getJSONObject("source")
                    .put("videoId", "ZbCdEf_12-3")
                "stale-original-hash" -> changed.getJSONObject("install")
                    .put("originalSha256", "b".repeat(64))
                "uppercase-media-hash" -> changed.getJSONObject("media")
                    .getJSONArray("files").getJSONObject(0).put("sha256", "A".repeat(64))
                "wrong-artwork-delivery" -> changed.getJSONObject("artwork")
                    .put("delivery", "app-private-cache")
                "wrong-artwork-kind" -> changed.getJSONObject("artwork")
                    .put("kind", "cover")
                "digest-mismatch" -> changed.getJSONObject("install")
                    .put("generatedDigest", "0".repeat(64))
                else -> error("Unknown shared negative case $name")
            }
            if (name != "digest-mismatch") {
                changed.getJSONObject("install").put("generatedDigest", installedEnrichmentDigest(changed))
            }
            assertTrue(name, runCatching {
                validateInstalledEnrichmentDocument(
                    changed,
                    VIDEO_ID,
                    valid.getJSONObject("install").getString("originalSha256")
                )
            }.isFailure)
        }
    }

    @Test
    fun sharedOfferReadsNoProviderBytesUntilUserConfirms() = runBlocking<Unit> {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            check(File(providerRoot(), "selected-library").mkdir())
            val libraryTree = DocumentsContract.buildTreeDocumentUri(
                GoldenQaDocumentsProvider.AUTHORITY,
                "root/selected-library"
            )
            grantProviderTree(libraryTree)
            val candidate = roots.validateCandidate(libraryTree).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val sourceBytes = "{}".toByteArray(Charsets.UTF_8)
            val sourceName = "shared-confirmation.json"
            File(providerRoot(), sourceName).writeBytes(sourceBytes)
            val source = DocumentsContract.buildDocumentUriUsingTree(
                GoldenQaDocumentsProvider.treeUri,
                "root/$sourceName"
            )
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val repository = ExternalLibraryRepository(
                context,
                roots,
                database.libraryDao(),
                bindings,
                mutations,
                background
            )
            val coordinator = EnrichmentImportCoordinator(
                context,
                songImporter(context, roots, mutations, bindings, background, database = database),
                repository,
                background,
                roots
            )
            coordinator.awaitStartupScratchReady()
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)

            coordinator.offer(listOf(source))

            assertTrue(coordinator.state.value is SharedEnrichmentImportState.AwaitingConfirmation)
            providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).also { before ->
                assertEquals(0L, before.getLong("openDocumentCalls"))
                assertEquals(0L, before.getLong("openedDocumentLengthBytes"))
            }

            coordinator.dismiss()
            assertTrue(coordinator.state.value is SharedEnrichmentImportState.Idle)
            providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).also { cancelled ->
                assertEquals(0L, cancelled.getLong("openDocumentCalls"))
                assertEquals(0L, cancelled.getLong("openedDocumentLengthBytes"))
            }

            coordinator.offer(listOf(source))
            coordinator.confirm()
            val settled = withTimeout(10_000L) {
                coordinator.state.first { it is SharedEnrichmentImportState.Finished || it is SharedEnrichmentImportState.Failed }
            }
            assertTrue(settled !is SharedEnrichmentImportState.AwaitingConfirmation)
            providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).also { imported ->
                assertEquals(1L, imported.getLong("openDocumentCalls"))
                assertEquals(sourceBytes.size.toLong(), imported.getLong("openedDocumentLengthBytes"))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun renamedWrongOriginalIsRejectedAndWrongStemsPreserveOriginalOnly() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (wrongOriginal in listOf(true, false)) {
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
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val repository = ExternalLibraryRepository(context, roots, dao, bindings, mutations,
                    BackgroundWorkCoordinator())
                seedDirectMedia(wrongOriginal = wrongOriginal, wrongStem = !wrongOriginal, withEnrichment = true)
                val folder = File(providerRoot(), VIDEO_ID)
                val before = folder.listFiles()!!.associate { it.name to sha256(it) }
                repository.reconcile()
                val diagnostic = repository.problems.value.joinToString { "${it.code}:${it.message}" }
                val expectedCode = if (wrongOriginal) "ORIGINAL_CODEC_INVALID" else "SOURCE_AUDIO_FORMAT_INVALID"
                assertTrue(diagnostic, repository.problems.value.any { it.code == expectedCode })
                if (wrongOriginal) {
                    assertTrue(diagnostic, repository.songs.value.isEmpty())
                } else {
                    assertEquals(diagnostic, 1, repository.songs.value.size)
                    val song = repository.verifyPlaybackSong(checkNotNull(roots.captureHandle()), VIDEO_ID)
                    assertEquals(before.getValue(SelectedLibraryContract.ORIGINAL), song.sourceHash)
                    assertNotNull(song.sourcePath)
                    assertFalse(song.hasStems)
                    assertEquals(64, song.mediaRevision.length)
                }
                assertEquals(currentSourcePackageFiles.toSet() + SelectedLibraryContract.enrichmentFiles,
                    folder.list().orEmpty().toSet())
                assertEquals(before, folder.listFiles()!!.associate { it.name to sha256(it) })
            } finally {
                database.close()
                context.destroy()
                isolatedContext = null
            }
        }
    }

}
