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
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.SongEntity
import app.stemwise.data.seedVerifiedBinding
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
internal class EnrichmentPublicationRecoveryInstrumentationTest : GoldenEnrichmentFixture() {
    @Test
    fun staleFirstEnrichmentMarkerPreservesADifferentValidCanonicalPairAndItsRecoveryEvidence() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (replacementSurvives in listOf(false, true)) {
            resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            try {
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, database.libraryDao())
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val folder = File(providerRoot(), VIDEO_ID)
                val json = File(folder, SelectedLibraryContract.ENRICHMENT)
                val cover = File(folder, SelectedLibraryContract.COVER)
                val intended = json.readBytes()
                val marker = File(folder, SelectedLibraryContract.ENRICHMENT_TRANSACTION)
                marker.writeBytes(PortableEnrichmentTransaction(root.libraryId, VIDEO_ID, UUID.randomUUID().toString(),
                    false, "", 0L, "", 0L, sha256(json), json.length(), sha256(cover), cover.length()).toPortableJsonBytes())
                val restored = parseStrictManifestJson(intended)
                restored.getJSONObject("metadata").put("title", "Owner restored a different valid generation")
                restored.getJSONObject("install").put("generatedDigest", installedEnrichmentDigest(restored))
                validateInstalledEnrichmentDocument(restored, VIDEO_ID, sha256(File(folder, SelectedLibraryContract.ORIGINAL)))
                json.writeText(restored.toString(), Charsets.UTF_8)
                if (replacementSurvives) {
                    File(folder, SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT).writeBytes(intended)
                    File(folder, SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT).writeBytes(cover.readBytes())
                }
                val before = folder.listFiles().orEmpty().filter(File::isFile).associate { it.name to it.readBytes() }
                val recovery = app.stemwise.data.EnrichmentTransactionRecovery(context, roots, BackgroundWorkCoordinator(), mutations)
                val failure = runCatching { mutations.withRootMutation { recovery.recover(root) } }.exceptionOrNull()
                assertEquals("RECOVERY_PENDING", failure?.message)
                assertEquals(before.keys, folder.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet())
                before.forEach { (name, bytes) -> assertTrue(name, bytes.contentEquals(File(folder, name).readBytes())) }
            } finally {
                database.close()
                context.destroy()
                isolatedContext = null
            }
        }
    }

    @Test
    fun markerOnlyFirstEnrichmentPreservesCanonicalPairAcrossUnavailableCanonicalAndMediaReads() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (fault in listOf(GoldenQaDocumentsProvider.FAULT_MANIFEST_READ_ONCE,
                GoldenQaDocumentsProvider.FAULT_ORIGINAL_SECOND_READ_ONCE)) {
            resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            try {
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, database.libraryDao())
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val folder = File(providerRoot(), VIDEO_ID)
                val json = File(folder, SelectedLibraryContract.ENRICHMENT)
                val cover = File(folder, SelectedLibraryContract.COVER)
                val marker = File(folder, SelectedLibraryContract.ENRICHMENT_TRANSACTION)
                marker.writeBytes(PortableEnrichmentTransaction(root.libraryId, VIDEO_ID, UUID.randomUUID().toString(),
                    false, "", 0L, "", 0L, sha256(json), json.length(), sha256(cover), cover.length()).toPortableJsonBytes())
                val before = listOf(json, cover, marker).associateWith(File::readBytes)
                val recovery = app.stemwise.data.EnrichmentTransactionRecovery(context, roots, BackgroundWorkCoordinator(), mutations)
                setFault(fault)
                val failure = runCatching { mutations.withRootMutation { recovery.recover(root) } }.exceptionOrNull()
                assertEquals("$fault did not preserve a retryable recovery", "RECOVERY_PENDING", (failure as? LibraryRootException)?.code)
                before.forEach { (file, bytes) -> assertTrue(file.name, bytes.contentEquals(file.readBytes())) }
                assertFalse(File(context.filesDir, "operation-journals").listFiles().orEmpty().any(File::isFile))
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
                mutations.withRootMutation { recovery.recover(root) }
                assertFalse(marker.exists())
                assertTrue(before.getValue(json).contentEquals(json.readBytes()))
                assertTrue(before.getValue(cover).contentEquals(cover.readBytes()))
            } finally {
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
                database.close()
                context.destroy()
                isolatedContext = null
            }
        }
    }

    @Test
    fun directDirectoryLookupMissRetainsTheReceiptUntilTheListedIdentityReopens() = runBlocking {
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
            val returned = roots.createSongDirectoryDocument(root, VIDEO_ID)
            val transaction = "asset-listed-directory-recovery"
            runCatching {
                mutations.withPackageMutation(root, VIDEO_ID, "FLAT_ASSET_PUBLISH", transaction,
                    requireExactRoot = { roots.requireSameLibrary(root) }) { journal ->
                    journal.write("DIRECTORY_CREATED", JSONObject().put("fileName", SelectedLibraryContract.ORIGINAL)
                        .put("createdDirectoryUri", returned.toString()))
                    error("INJECT_DIRECTORY_CREATED")
                }
            }.onSuccess { error("Fixture did not retain a receipt") }
            val receipt = File(context.filesDir, "operation-journals/$transaction.json")
            val governor = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
            val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
            val original = File(context.cacheDir, "original.opus").apply {
                writeBytes(GoldenSongMediaFixture.originalOpus(context, 240L))
            }
            setFault(GoldenQaDocumentsProvider.FAULT_DIRECTORY_DIRECT_LOOKUP_MISSING)
            val failure = publisher.publishOriginal(root, VIDEO_ID, original, sha256(original)).exceptionOrNull()
            assertEquals("PACKAGE_DIRECTORY_RECOVERY_PENDING", (failure as? LibraryRootException)?.code)
            assertTrue(receipt.isFile)
            assertTrue(File(providerRoot(), VIDEO_ID).isDirectory)
            assertTrue(original.isFile)
            setFault(GoldenQaDocumentsProvider.FAULT_NONE)
            publisher.publishOriginal(root, VIDEO_ID, original, sha256(original)).getOrThrow()
            assertEquals(returned.toString(), dao.findSongById(VIDEO_ID)?.packageDocumentUri)
            assertFalse(receipt.exists())
            assertEquals(1, providerRoot().listFiles().orEmpty().count(File::isDirectory))
        } finally {
            setFault(GoldenQaDocumentsProvider.FAULT_NONE)
            database.close()
        }
    }
    @Test
    fun canonicalPublisherCreatesExactDirectChildrenAndProviderFaultsLeaveNoEntry() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            val handle = checkNotNull(roots.captureHandle())
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            seedVerifiedBinding(bindings, mutations, handle, SOURCE_ID, VIDEO_ID)
            val sourceFixture = app.stemwise.data.CurrentSourcePublicationFixture.load(targetContext, VIDEO_ID)
            val original = sourceFixture.original
            val durationMs = mediaDurationMs(original, ".opus")
            val expectedBytes = linkedMapOf(SelectedLibraryContract.ORIGINAL to original).apply {
                putAll(sourceFixture.parts)
            }
            database.libraryDao().insertSong(
                SongEntity(
                    id = VIDEO_ID,
                    youtubeVideoId = VIDEO_ID,
                    playlistItemId = null,
                    canonicalUrl = "https://music.youtube.com/watch?v=$VIDEO_ID",
                    sourceTitle = "Canonical publisher fixture",
                    sourceArtist = "StemWise",
                    displayTitle = null,
                    displayArtist = null,
                    durationMs = mediaDurationMs(original, ".opus"),
                    artworkPath = null,
                    currentlyInSourcePlaylist = false,
                    highLevelState = "PREPARING",
                    acquiredAtEpochMs = 1L,
                    createdAtEpochMs = 1L
                )
            )
            val external = ExternalLibraryRepository(
                context,
                roots,
                database.libraryDao(),
                bindings,
                mutations,
                BackgroundWorkCoordinator()
            )
            val publisher = CanonicalPackagePublisher(
                context,
                roots,
                database.libraryDao(),
                mutations,
                bindings,
                external,
                BackgroundWorkCoordinator()
            )
            setFault(GoldenQaDocumentsProvider.FAULT_UNKNOWN_SIZE)
            val originalPrivate = File(context.cacheDir, "publisher-original.opus").apply {
                writeBytes(original)
            }
            publisher.publishOriginal(
                handle,
                VIDEO_ID,
                originalPrivate,
                sha256(originalPrivate)
            ).getOrThrow()
            assertFalse(originalPrivate.exists())
            app.stemwise.data.seedCompleteOpusMetadata(File(providerRoot(), VIDEO_ID), VIDEO_ID, original)
            sourceFixture.publishParts(context, publisher, external, handle,
                "c586a7e1-387e-469d-a316-fd1dbf6bb77c")

            val folder = File(providerRoot(), VIDEO_ID)
            assertEquals(
                sourceFixture.packageFiles + SelectedLibraryContract.enrichmentFiles,
                folder.list().orEmpty().toSet()
            )
            expectedBytes.forEach { (name, bytes) ->
                assertEquals(
                    name,
                    MessageDigest.getInstance("SHA-256").digest(bytes).toList(),
                    MessageDigest.getInstance("SHA-256")
                        .digest(File(folder, name).readBytes()).toList()
                )
            }
            val positiveMetrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            assertEquals(12L, positiveMetrics.getLong("createDocumentCalls"))
            assertEquals(0L, positiveMetrics.getLong("deleteDocumentCalls"))

            // The positive publication schedules a deferred scan. Finish that fixture's
            // background work before replacing its provider contents with fault scenarios.
            external.clearForLibrarySwitch(clearProjection = false)
            resetProvider()
            check(File(providerRoot(), VIDEO_ID).mkdir())
            val exactParent = checkNotNull(roots.checkedSongSnapshot(handle, VIDEO_ID))
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            linkedMapOf(
                GoldenQaDocumentsProvider.FAULT_CREATE_REFUSAL to "ROOT_UNAVAILABLE",
                GoldenQaDocumentsProvider.FAULT_CREATE_SECURITY to "ROOT_PERMISSION_LOST",
                GoldenQaDocumentsProvider.FAULT_CREATE_DISAPPEAR to
                    "PACKAGE_DIRECTORY_CLEANUP_PENDING"
            ).forEach { (fault, expectedCode) ->
                setFault(fault)
                val failure = runCatching {
                    roots.createExactSongChild(
                        handle,
                        exactParent,
                        "application/json",
                        SelectedLibraryContract.ENRICHMENT
                    )
                }.exceptionOrNull()
                assertEquals(
                    fault + ":" + failure?.javaClass?.name + ":" + failure?.message,
                    expectedCode,
                    (failure as? LibraryRootException)?.code
                )
                assertFalse(File(providerRoot(), VIDEO_ID + "/song.json").exists())
            }
            val negativeMetrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            assertEquals(3L, negativeMetrics.getLong("createDocumentCalls"))
            assertEquals(0L, negativeMetrics.getLong("deleteDocumentCalls"))

            resetProvider()
            database.libraryDao().insertSong(
                SongEntity(
                    id = CAPACITY_ID,
                    youtubeVideoId = CAPACITY_ID,
                    playlistItemId = null,
                    canonicalUrl = "https://music.youtube.com/watch?v=$CAPACITY_ID",
                    sourceTitle = "Capacity race fixture",
                    sourceArtist = "StemWise",
                    displayTitle = null,
                    displayArtist = null,
                    durationMs = mediaDurationMs(original, ".opus"),
                    artworkPath = null,
                    currentlyInSourcePlaylist = false,
                    highLevelState = "PREPARING",
                    acquiredAtEpochMs = 1L,
                    createdAtEpochMs = 1L
                )
            )
            val capacityOriginal = File(context.cacheDir, "capacity-original.opus").apply {
                writeBytes(original)
            }
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            setFault(
                GoldenQaDocumentsProvider.FAULT_CREATE_ADDS_SIBLING_AND_HIDES_CREATED
            )
            val capacityFailure = publisher.publishOriginal(
                handle,
                CAPACITY_ID,
                capacityOriginal,
                sha256(capacityOriginal)
            ).exceptionOrNull()
            assertEquals(
                "PACKAGE_DIRECTORY_IDENTITY_UNCERTAIN",
                (capacityFailure as? LibraryRootException)?.code
            )
            assertTrue(capacityOriginal.exists())
            assertFalse(File(providerRoot(), CAPACITY_ID).exists())
            assertTrue(
                File(
                    providerRoot(),
                    GoldenQaDocumentsProvider.CAPACITY_RACE_SIBLING
                ).isDirectory
            )
            assertEquals(
                listOf(GoldenQaDocumentsProvider.CAPACITY_RACE_SIBLING),
                providerRoot().listFiles().orEmpty().filter(File::isDirectory).map(File::getName)
            )
            val capacityMetrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            assertEquals(1L, capacityMetrics.getLong("createDocumentCalls"))
            assertEquals(1L, capacityMetrics.getLong("deleteDocumentCalls"))

            resetProvider()
            assertTrue(roots.initialize().ready)
            val recoveryHandle = checkNotNull(roots.captureHandle())
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            setFault(
                GoldenQaDocumentsProvider.FAULT_CREATE_ADDS_SIBLING_AND_HIDES_CREATED
            )
            val recoveryUri = roots.createSongDirectoryDocument(recoveryHandle, CAPACITY_ID)
            val seededFailure = runCatching {
                mutations.withPackageMutation(
                    recoveryHandle,
                    CAPACITY_ID,
                    "FLAT_ASSET_PUBLISH",
                    "asset-capacity-recovery-seed",
                    requireExactRoot = { roots.requireSameLibrary(recoveryHandle) }
                ) { journal ->
                    journal.write(
                        "DIRECTORY_CREATED",
                        JSONObject()
                            .put("fileName", SelectedLibraryContract.ORIGINAL)
                            .put("createdDirectoryUri", recoveryUri.toString())
                    )
                    error("INJECT_AFTER_DIRECTORY_CREATED")
                }
            }.exceptionOrNull()
            assertEquals("INJECT_AFTER_DIRECTORY_CREATED", seededFailure?.message)

            val recoveryFailure = publisher.publishOriginal(
                recoveryHandle,
                CAPACITY_ID,
                capacityOriginal,
                sha256(capacityOriginal)
            ).exceptionOrNull()
            assertEquals(
                "PACKAGE_DIRECTORY_IDENTITY_UNCERTAIN",
                (recoveryFailure as? LibraryRootException)?.code
            )
            assertTrue(capacityOriginal.exists())
            assertFalse(File(providerRoot(), CAPACITY_ID).exists())
            assertTrue(
                File(
                    providerRoot(),
                    GoldenQaDocumentsProvider.CAPACITY_RACE_SIBLING
                ).isDirectory
            )
            assertEquals(
                1,
                providerRoot().listFiles().orEmpty().count(File::isDirectory)
            )
            assertEquals(null, database.libraryDao().findSongById(CAPACITY_ID)?.packageDocumentUri)
            assertTrue(
                File(context.filesDir, "operation-journals").listFiles().orEmpty().isEmpty()
            )
            val recoveryMetrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            assertEquals(1L, recoveryMetrics.getLong("createDocumentCalls"))
            assertEquals(1L, recoveryMetrics.getLong("deleteDocumentCalls"))
        } finally {
            database.close()
        }
    }

    @Test
    fun failedExactChildVerificationDeletesOnlyTheReturnedCreatedDocument() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            val folder = File(providerRoot(), VIDEO_ID).apply { check(mkdir()) }
            val original = File(folder, SelectedLibraryContract.ORIGINAL).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val parent = checkNotNull(roots.checkedSongSnapshot(handle, VIDEO_ID))
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            setFault(GoldenQaDocumentsProvider.FAULT_HIDE_DIRECT_CHILDREN_AND_AUTORENAME)

            val failure = runCatching {
                roots.createExactSongChild(
                    handle,
                    parent,
                    "audio/ogg",
                    SelectedLibraryContract.ORIGINAL
                )
            }.exceptionOrNull()

            assertEquals(
                generateSequence(failure) { it.cause }
                    .joinToString(" <- ") { "${it::class.java.name}:${it.message}" },
                "ROOT_UNAVAILABLE",
                (failure as? LibraryRootException)?.code
            )
            assertTrue(original.isFile)
            assertFalse(File(folder, "${SelectedLibraryContract.ORIGINAL} (1)").exists())
            providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).also { metrics ->
                assertEquals(1L, metrics.getLong("createDocumentCalls"))
                assertEquals(1L, metrics.getLong("deleteDocumentCalls"))
            }

            setFault(GoldenQaDocumentsProvider.FAULT_HIDE_DIRECT_CHILDREN_AUTORENAME_DELETE_REFUSAL)
            val cleanupPending = runCatching {
                roots.createExactSongChild(
                    handle,
                    parent,
                    "audio/ogg",
                    SelectedLibraryContract.ORIGINAL
                )
            }.exceptionOrNull()
            assertEquals(
                "PACKAGE_DIRECTORY_CLEANUP_PENDING",
                (cleanupPending as? LibraryRootException)?.code
            )
            assertEquals(1, cleanupPending?.suppressed?.size)
            assertTrue(File(folder, "${SelectedLibraryContract.ORIGINAL} (1)").isFile)
        } finally { database.close() }
    }

    @Test
    fun committingBeforeAndAfterTargetWriteRecoversWithAllDeclaredMediaHashesUnchanged() =
        runBlocking {
            StandardInstrumentationTargetGuard.requireCurrentTarget()
            resetProvider()
            val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
            grantProviderTree()
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            seedDirectMedia()
            val folder = File(providerRoot(), VIDEO_ID)
            val mediaBefore = currentSourcePackageFiles.associateWith { name ->
                sha256(File(folder, name))
            }
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            seedVerifiedBinding(bindings, mutations, handle, SOURCE_ID, VIDEO_ID)
            try {
                val repository = ExternalLibraryRepository(
                    context,
                    roots,
                    database.libraryDao(),
                    bindings,
                    mutations,
                    BackgroundWorkCoordinator()
                )
                repository.reconcile()
                listOf(
                    ENRICHMENT_COMMIT_BEFORE_TARGET_WRITE to
                        ("2026-08-09T12:10:00Z" to "Recovered before write"),
                    ENRICHMENT_COMMIT_AFTER_COVER_WRITE to
                        ("2026-08-09T12:10:30.000Z" to "Recovered after cover"),
                    ENRICHMENT_COMMIT_AFTER_TARGET_WRITE to
                        ("2026-08-09T12:11:00.000Z" to "Recovered after write")
                ).forEachIndexed { index, (phase, fixture) ->
                    val interrupted = AtomicBoolean(false)
                    val importer = songImporter(
                        context,
                        roots,
                        mutations,
                        bindings,
                        BackgroundWorkCoordinator(), database = database) { reached ->
                        if (reached == phase && interrupted.compareAndSet(false, true)) {
                            throw EnrichmentCommitInterruption(reached)
                        }
                    }
                    val archive = writeArchive(
                        context,
                        "commit-recovery-$index.stemwise.zip",
                        payload(fixture.first, fixture.second, includeArtwork = true),
                        COVER_PNG
                    )
                    val interruptedItem = importer.importStagedFiles(
                        listOf(archive),
                        listOf(archive.name)
                    ).getOrThrow().items.single()
                    assertTrue(
                        "checkpoint $phase was not reached; outcome=" +
                            interruptedItem.outcome + "; code=" + interruptedItem.errorCode,
                        interrupted.get()
                    )
                    assertEquals(EnrichmentImportOutcome.INVALID, interruptedItem.outcome)
                    assertEquals("EXTERNAL_COMMIT_FAILED", interruptedItem.errorCode)
                    assertTrue(
                        File(context.filesDir, "operation-journals").listFiles().orEmpty()
                            .mapNotNull { file ->
                                runCatching { JSONObject(file.readText()) }.getOrNull()
                            }
                            .any {
                                it.optString("operation") == "ENRICHMENT_INSTALL_V2" &&
                                    it.optString("state") == "COMMITTING"
                            }
                    )
                    if (phase == ENRICHMENT_COMMIT_BEFORE_TARGET_WRITE) {
                        val createdButUncommitted = File(
                            folder,
                            SelectedLibraryContract.COVER
                        )
                        assertTrue(
                            "before-target checkpoint did not create its exact target",
                            createdButUncommitted.isFile
                        )
                    }
                    // Model process loss after the provider's wt truncation and before its first byte.
                    if (phase == ENRICHMENT_COMMIT_AFTER_COVER_WRITE || phase == ENRICHMENT_COMMIT_AFTER_TARGET_WRITE) {
                        File(folder, if (phase == ENRICHMENT_COMMIT_AFTER_COVER_WRITE)
                            SelectedLibraryContract.COVER else SelectedLibraryContract.ENRICHMENT).outputStream().use { }
                    }
                    if (phase == ENRICHMENT_COMMIT_AFTER_COVER_WRITE) {
                        File(
                            folder,
                            SelectedLibraryContract.ENRICHMENT_TRANSACTION
                        ).writeText("malformed canonical marker")
                        database.libraryDao().deleteSongRow(VIDEO_ID)
                    }

                    repository.reconcile()
                    songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database)
                        .recoverIncompleteBundles(handle)
                    val installed = parseStrictManifestJson(
                        File(folder, SelectedLibraryContract.ENRICHMENT).readBytes()
                    )
                    validateInstalledEnrichmentDocument(
                        installed,
                        VIDEO_ID,
                        mediaBefore.getValue(SelectedLibraryContract.ORIGINAL)
                    )
                    assertEquals(
                        installed.getJSONObject("artwork").getString("sha256"),
                        sha256(File(folder, SelectedLibraryContract.COVER))
                    )
                    assertEquals(
                        currentSourcePackageFiles.toSet() + setOf(
                            SelectedLibraryContract.COVER, SelectedLibraryContract.ENRICHMENT
                        ),
                        folder.list().orEmpty().toSet()
                    )
                    assertEquals(fixture.second, installed.getJSONObject("metadata").getString("title"))
                    assertEquals(
                        mediaBefore,
                        currentSourcePackageFiles.associateWith { name ->
                            sha256(File(folder, name))
                        }
                    )
                    assertFalse(
                        File(context.filesDir, "enrichment-recovery").listFiles().orEmpty()
                            .any(File::isFile)
                    )
                }
                val settledManifest = File(folder, SelectedLibraryContract.ENRICHMENT)
                    .readBytes()
                val settledCover = File(folder, SelectedLibraryContract.COVER).readBytes()
                listOf(
                    ENRICHMENT_COMMIT_BEFORE_TARGET_WRITE,
                    ENRICHMENT_COMMIT_AFTER_COVER_WRITE,
                    ENRICHMENT_COMMIT_AFTER_TARGET_WRITE
                ).forEachIndexed { index, cancelledPhase ->
                    val importer = songImporter(
                        context,
                        roots,
                        mutations,
                        bindings,
                        BackgroundWorkCoordinator(), database = database) { reached ->
                        if (reached == cancelledPhase) {
                            throw CancellationException("cancel-$cancelledPhase")
                        }
                    }
                    val update = writeArchive(
                        context,
                        "cancel-settlement-$index.zip",
                        payload(
                            "2026-08-09T12:${20 + index}:00.000Z",
                            "Must not apply after cancellation $index",
                            includeArtwork = true
                        ), COVER_PNG)
                    val cancellation = runCatching {
                        importer.importStagedFiles(listOf(update), listOf(update.name))
                    }.exceptionOrNull()
                    assertTrue(cancellation is CancellationException)
                    assertTrue(
                        settledManifest.contentEquals(
                            File(folder, SelectedLibraryContract.ENRICHMENT).readBytes()
                        )
                    )
                    assertTrue(
                        settledCover.contentEquals(
                            File(folder, SelectedLibraryContract.COVER).readBytes()
                        )
                    )
                    assertTrue(
                        SelectedLibraryContract.internalPackageFiles
                            .filter {
                                it.startsWith(".stemwise-enrichment") ||
                                    it.startsWith(".stemwise-cover")
                            }
                            .none { File(folder, it).exists() }
                    )
                    assertFalse(
                        File(context.filesDir, "operation-journals").listFiles().orEmpty()
                            .any(File::isFile)
                    )
                }
            } finally {
                database.close()
            }
        }

    @Test
    fun selectedRootTransactionRecoversAfterAllPrivateStateIsLost() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        var database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            var mutations = PackageMutationCoordinator(context)
            var roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val originalHandle = checkNotNull(roots.captureHandle())
            val originalLibraryId = originalHandle.libraryId
            seedDirectMedia()
            var bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            seedVerifiedBinding(bindings, mutations, originalHandle, SOURCE_ID, VIDEO_ID)
            var repository = ExternalLibraryRepository(
                context,
                roots,
                database.libraryDao(),
                bindings,
                mutations,
                BackgroundWorkCoordinator()
            )
            repository.reconcile()
            val interrupted = AtomicBoolean(false)
            val importer = songImporter(
                context,
                roots,
                mutations,
                bindings,
                BackgroundWorkCoordinator(), database = database) { phase ->
                if (
                    phase == ENRICHMENT_COMMIT_AFTER_COVER_WRITE &&
                    interrupted.compareAndSet(false, true)
                ) {
                    throw EnrichmentCommitInterruption(phase)
                }
            }
            val archive = writeArchive(
                context,
                "private-loss-recovery.stemwise.zip",
                payload(
                    "2026-08-09T12:20:00.000Z",
                    "Recovered without private state",
                    includeArtwork = true
                ),
                COVER_PNG
            )
            val result = importer.importStagedFiles(
                listOf(archive),
                listOf(archive.name)
            ).getOrThrow().items.single()
            assertTrue(interrupted.get())
            assertEquals(EnrichmentImportOutcome.INVALID, result.outcome)
            val folder = File(providerRoot(), VIDEO_ID)
            assertTrue(File(folder, SelectedLibraryContract.ENRICHMENT_TRANSACTION).isFile)
            File(folder, SelectedLibraryContract.COVER).outputStream().use { }

            database.close()
            context.eraseAllPrivateState()

            database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            mutations = PackageMutationCoordinator(context)
            roots = LibraryRootManager(context, mutations, database.libraryDao())
            val recoveredCandidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                .getOrThrow()
            assertEquals(originalLibraryId, recoveredCandidate.libraryId)
            roots.commitValidatedCandidateForDevelopment(recoveredCandidate).getOrThrow()
            bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            repository = ExternalLibraryRepository(
                context,
                roots,
                database.libraryDao(),
                bindings,
                mutations,
                BackgroundWorkCoordinator()
            )
            repository.reconcile()

            val installed = parseStrictManifestJson(
                File(folder, SelectedLibraryContract.ENRICHMENT).readBytes()
            )
            assertEquals(
                "Recovered without private state",
                installed.getJSONObject("metadata").getString("title")
            )
            val pendingInterrupted = AtomicBoolean(false)
            val committedBeforeTempOnly =
                File(folder, SelectedLibraryContract.ENRICHMENT).readBytes()
            val pendingImporter = songImporter(
                context,
                roots,
                mutations,
                bindings,
                BackgroundWorkCoordinator(), database = database) { phase ->
                if (
                    phase == ENRICHMENT_COMMIT_BEFORE_TARGET_WRITE &&
                    pendingInterrupted.compareAndSet(false, true)
                ) {
                    throw EnrichmentCommitInterruption(phase)
                }
            }
            val pendingUpdate = writeArchive(
                context,
                "pending-authority.zip",
                payload(
                    "2026-08-09T12:25:00.000Z",
                    "Recovered without private state",
                    includeArtwork = true
                ), COVER_PNG)
            val pendingResult = pendingImporter.importStagedFiles(
                listOf(pendingUpdate),
                listOf(pendingUpdate.name)
            ).getOrThrow().items.single()
            assertTrue(pendingInterrupted.get())
            assertEquals(EnrichmentImportOutcome.INVALID, pendingResult.outcome)
            fun portableEnrichmentArtifacts(): Map<String, ByteArray> =
                SelectedLibraryContract.internalPackageFiles
                    .filter {
                        it.startsWith(".stemwise-enrichment") ||
                            it.startsWith(".stemwise-cover")
                    }
                    .mapNotNull { name ->
                        File(folder, name).takeIf(File::isFile)?.let { name to it.readBytes() }
                    }
                    .toMap()
            val portableBeforeRefusedImport = portableEnrichmentArtifacts()
            val refusedUpdate = writeArchive(
                context,
                "refused-over-recovery.zip",
                payload(
                    "2026-08-09T12:25:30.000Z",
                    "Must not replace pending authority",
                    includeArtwork = true
                ), COVER_PNG)
            val refusedImporter = songImporter(
                context,
                roots,
                mutations,
                bindings,
                BackgroundWorkCoordinator(), database = database)
            val refused = refusedImporter.importStagedFiles(
                listOf(refusedUpdate),
                listOf(refusedUpdate.name)
            ).getOrThrow().items.single()
            assertEquals(EnrichmentImportOutcome.INVALID, refused.outcome)
            assertEquals("RECOVERY_PENDING", refused.errorCode)
            val portableAfterRefusedImport = portableEnrichmentArtifacts()
            assertEquals(portableBeforeRefusedImport.keys, portableAfterRefusedImport.keys)
            portableBeforeRefusedImport.forEach { (name, bytes) ->
                assertTrue(bytes.contentEquals(checkNotNull(portableAfterRefusedImport[name])))
            }
            File(folder, SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP).writeBytes(
                File(folder, SelectedLibraryContract.ENRICHMENT_TRANSACTION).readBytes()
            )
            assertTrue(File(folder, SelectedLibraryContract.ENRICHMENT_TRANSACTION).delete())
            File(context.filesDir, "operation-journals").deleteRecursively()
            File(context.filesDir, "enrichment-recovery").deleteRecursively()
            repository.reconcile()
            assertTrue(
                committedBeforeTempOnly.contentEquals(
                    File(folder, SelectedLibraryContract.ENRICHMENT).readBytes()
                )
            )
            assertTrue(portableEnrichmentArtifacts().isEmpty())
            val rollbackInterrupted = AtomicBoolean(false)
            val rollbackImporter = songImporter(
                context,
                roots,
                mutations,
                bindings,
                BackgroundWorkCoordinator(), database = database) { phase ->
                if (
                    phase == ENRICHMENT_COMMIT_AFTER_COVER_WRITE &&
                    rollbackInterrupted.compareAndSet(false, true)
                ) {
                    throw EnrichmentCommitInterruption(phase)
                }
            }
            val rollbackUpdate = writeArchive(
                context,
                "private-loss-rollback.zip",
                payload(
                    "2026-08-09T12:30:00.000Z",
                    "Replacement must not survive corruption",
                    includeArtwork = true
                ), COVER_PNG)
            val rollbackResult = rollbackImporter.importStagedFiles(
                listOf(rollbackUpdate),
                listOf(rollbackUpdate.name)
            ).getOrThrow().items.single()
            assertTrue(rollbackInterrupted.get())
            assertEquals(EnrichmentImportOutcome.INVALID, rollbackResult.outcome)
            listOf(
                SelectedLibraryContract.ENRICHMENT,
                SelectedLibraryContract.COVER,
                SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT,
                SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT
            ).forEach { name ->
                File(folder, name).writeBytes(if (name == SelectedLibraryContract.ENRICHMENT)
                    byteArrayOf() else "corrupt-$name".toByteArray(Charsets.UTF_8))
            }
            assertTrue(File(folder, SelectedLibraryContract.ENRICHMENT_JSON_BACKUP).isFile)
            assertTrue(File(folder, SelectedLibraryContract.ENRICHMENT_COVER_BACKUP).isFile)

            database.close()
            context.eraseAllPrivateState()
            database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            mutations = PackageMutationCoordinator(context)
            roots = LibraryRootManager(context, mutations, database.libraryDao())
            val rollbackCandidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                .getOrThrow()
            assertEquals(originalLibraryId, rollbackCandidate.libraryId)
            roots.commitValidatedCandidateForDevelopment(rollbackCandidate).getOrThrow()
            bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            repository = ExternalLibraryRepository(
                context,
                roots,
                database.libraryDao(),
                bindings,
                mutations,
                BackgroundWorkCoordinator()
            )
            repository.reconcile()
            val rolledBack = parseStrictManifestJson(
                File(folder, SelectedLibraryContract.ENRICHMENT).readBytes()
            )
            assertEquals(
                "Recovered without private state",
                rolledBack.getJSONObject("metadata").getString("title")
            )
            assertTrue(COVER_PNG.contentEquals(File(folder, SelectedLibraryContract.COVER).readBytes()))
            assertTrue(
                SelectedLibraryContract.internalPackageFiles
                    .filter { it.startsWith(".stemwise-enrichment") || it.startsWith(".stemwise-cover") }
                    .none { File(folder, it).exists() }
            )
            assertFalse(File(context.filesDir, "operation-journals").exists())
            assertFalse(File(context.filesDir, "enrichment-recovery").exists())
        } finally {
            database.close()
        }
    }

    @Test
    fun firstEnrichmentWithUnusableReplacementRollsBackToNoPairAfterPrivateLoss() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        var database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            var mutations = PackageMutationCoordinator(context)
            var roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val originalHandle = checkNotNull(roots.captureHandle())
            val originalLibraryId = originalHandle.libraryId
            seedDirectMedia()
            var bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            seedVerifiedBinding(bindings, mutations, originalHandle, SOURCE_ID, VIDEO_ID)
            var repository = ExternalLibraryRepository(
                context,
                roots,
                database.libraryDao(),
                bindings,
                mutations,
                BackgroundWorkCoordinator()
            )
            repository.reconcile()
            val folder = File(providerRoot(), VIDEO_ID)
            val mediaBefore = currentSourcePackageFiles.associateWith { name ->
                sha256(File(folder, name))
            }
            val interrupted = AtomicBoolean(false)
            val importer = songImporter(
                context,
                roots,
                mutations,
                bindings,
                BackgroundWorkCoordinator(), database = database) { phase ->
                if (
                    phase == ENRICHMENT_COMMIT_AFTER_COVER_WRITE &&
                    interrupted.compareAndSet(false, true)
                ) {
                    throw EnrichmentCommitInterruption(phase)
                }
            }
            val archive = writeArchive(
                context,
                "first-enrichment-private-loss.stemwise.zip",
                payload(
                    "2026-08-09T12:40:00.000Z",
                    "Unusable first enrichment",
                    includeArtwork = true
                ),
                COVER_PNG
            )
            val result = importer.importStagedFiles(
                listOf(archive),
                listOf(archive.name)
            ).getOrThrow().items.single()
            assertTrue(interrupted.get())
            assertEquals(EnrichmentImportOutcome.INVALID, result.outcome)
            assertFalse(File(folder, SelectedLibraryContract.ENRICHMENT).exists())
            assertTrue(File(folder, SelectedLibraryContract.COVER).isFile)
            assertTrue(File(folder, SelectedLibraryContract.ENRICHMENT_TRANSACTION).isFile)
            listOf(
                SelectedLibraryContract.COVER,
                SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT,
                SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT
            ).forEach { name ->
                File(folder, name).writeBytes("corrupt-$name".toByteArray())
            }

            database.close()
            context.eraseAllPrivateState()
            database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            mutations = PackageMutationCoordinator(context)
            roots = LibraryRootManager(context, mutations, database.libraryDao())
            val recoveredCandidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                .getOrThrow()
            assertEquals(originalLibraryId, recoveredCandidate.libraryId)
            roots.commitValidatedCandidateForDevelopment(recoveredCandidate).getOrThrow()
            bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            repository = ExternalLibraryRepository(
                context,
                roots,
                database.libraryDao(),
                bindings,
                mutations,
                BackgroundWorkCoordinator()
            )
            repository.reconcile()

            assertFalse(File(folder, SelectedLibraryContract.ENRICHMENT).exists())
            assertFalse(File(folder, SelectedLibraryContract.COVER).exists())
            assertEquals(
                mediaBefore,
                currentSourcePackageFiles.associateWith { name ->
                    sha256(File(folder, name))
                }
            )
            assertTrue(
                SelectedLibraryContract.internalPackageFiles
                    .filter {
                        it.startsWith(".stemwise-enrichment") ||
                            it.startsWith(".stemwise-cover")
                    }
                    .none { File(folder, it).exists() }
            )
            assertFalse(File(context.filesDir, "operation-journals").exists())
            assertFalse(File(context.filesDir, "enrichment-recovery").exists())
        } finally {
            database.close()
        }
    }

}
