package app.stemwise.data

import app.stemwise.playback.debug

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.media.MediaExtractor
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.enrichment.EnrichmentImportOutcome
import app.stemwise.enrichment.ENRICHMENT_GENERATOR_NAME
import app.stemwise.enrichment.ENRICHMENT_GENERATOR_VERSION
import app.stemwise.enrichment.GoldenSongMediaFixture
import app.stemwise.enrichment.INSTALLED_ENRICHMENT_CONTRACT
import app.stemwise.enrichment.INSTALLED_ENRICHMENT_KIND
import app.stemwise.enrichment.INSTALLED_ENRICHMENT_SCHEMA_VERSION
import app.stemwise.enrichment.SongEnrichmentImporter
import app.stemwise.enrichment.installedMediaIdentities
import app.stemwise.enrichment.validateInstalledEnrichmentDocument
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.playback.PlaybackControlLease
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.PlaybackService
import app.stemwise.playback.admitCurrentPlaybackLibrarySnapshot
import app.stemwise.playback.resetForInstrumentationIsolation
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.qualification.withDurableTestWrites
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tester-owned reusable physical lifecycle probe.
 *
 * The two methods are deliberately invoked in separate instrumentation processes. Phase one leaves
 * only its bounded disposable root and isolated durable authority. The host force-stops the exact
 * rehearsal package before phase two, which reconstructs every storage object from those durable
 * facts and then removes all Tester-owned state. Enrichment preserves the current direct selected-root package: Original, declared lossless
 * parts and native Remaining, the source manifest, cover.png, and song.json.
 */
private val PRIVATE_MEDIA_EXTENSIONS = setOf("opus", "mp3", "zip", "png", "wav", "ogg", "flac", "wv", "f32")

@RunWith(AndroidJUnit4::class)
class TesterPhysicalLibraryLifecycleInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context
        get() = instrumentation.targetContext
    private val testContext: Context
        get() = instrumentation.context
    private val application: StemwiseApplication
        get() = targetContext.applicationContext as StemwiseApplication

    private fun privateMediaInventoryDetectsCurrentNativeFormatsAndCleanConvergence() {
        val namespace = "media-format-inventory"
        PersistentPhysicalContext.clear(targetContext, namespace)
        val context = PersistentPhysicalContext(targetContext, namespace)
        val baseline = privateMediaHashes(context)
        try {
            for (extension in listOf("flac", "wv", "f32")) {
                File(context.cacheDir, "leaked.$extension").writeBytes(byteArrayOf(1, 2, 3))
            }
            val observed = privateMediaHashes(context)
            for (extension in listOf("flac", "wv", "f32")) {
                assertTrue("Current format .$extension escaped the leak inventory",
                    "cache/leaked.$extension" in observed)
            }
            for (extension in listOf("flac", "wv", "f32")) {
                check(File(context.cacheDir, "leaked.$extension").delete())
            }
            assertEquals(baseline, privateMediaHashes(context))
        } finally {
            PersistentPhysicalContext.clear(targetContext, namespace)
        }
    }

    @Test
    fun phaseOnePublishesEnrichesAndPlaysDirectPhysicalPackage() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        application.admitCurrentPlaybackLibrarySnapshot()
        resetPlayback()
        resetProvider()
        PersistentPhysicalContext.clear(targetContext, ROOT_A_NAMESPACE)
        PersistentPhysicalContext.clear(targetContext, ROOT_B_NAMESPACE)
        val privateMediaBaseline = privateMediaHashes(targetContext)
        val context = PersistentPhysicalContext(targetContext, ROOT_A_NAMESPACE)
        val rootA = File(providerRoot(), ROOT_A_NAME).apply { requireDirectory(this) }
        File(providerRoot(), ROOT_B_NAME).apply { requireDirectory(this) }
        val treeA = treeUri(ROOT_A_NAME)
        val treeB = treeUri(ROOT_B_NAME)
        grantTrees(treeA, treeB)

        val database = LibraryDatabase.buildFreshProjectionDatabase(context, PHYSICAL_DATABASE_NAME)
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            val candidate = roots.validateCandidate(treeA).getOrThrow()
            assertEquals(0, candidate.existingEntryCount)
            val committed = roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            assertTrue(committed.ready)
            val handle = checkNotNull(roots.captureHandle())
            assertEquals(treeA, handle.treeUri)

            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            seedVerifiedBinding(bindings, mutations, handle, SOURCE_ID, VIDEO_ID)
            val governor = BackgroundWorkCoordinator()
            val external = ExternalLibraryRepository(
                context,
                roots,
                dao,
                bindings,
                mutations,
                governor
            )
            val publisher = CanonicalPackagePublisher(
                context,
                roots,
                dao,
                mutations,
                bindings,
                external,
                governor
            )
            val importer = SongEnrichmentImporter(
                context,
                roots,
                mutations,
                bindings,
                governor,
                originalPublisher = { publisher }
            )

            val sourceFixture = CurrentSourcePublicationFixture.load(targetContext, VIDEO_ID)
            val original = sourceFixture.original
            val durationMs = mediaDurationMs(context, original, ".opus")
            assertEquals("audio/opus", mediaMime(context, original, ".opus"))

            dao.insertSong(
                SongEntity(
                    id = VIDEO_ID,
                    youtubeVideoId = VIDEO_ID,
                    playlistItemId = null,
                    canonicalUrl = "https://music.youtube.com/watch?v=$VIDEO_ID",
                    sourceTitle = "Tester physical lifecycle",
                    sourceArtist = "StemWise Tester",
                    displayTitle = null,
                    displayArtist = null,
                    durationMs = durationMs,
                    artworkPath = null,
                    currentlyInSourcePlaylist = false,
                    highLevelState = "PREPARING",
                    acquiredAtEpochMs = 1L,
                    createdAtEpochMs = 1L
                )
            )

            val expectedHashes = linkedMapOf<String, String>()

            val privateOriginal = File(context.cacheDir, "source-original.opus").apply {
                writeBytes(original)
            }
            expectedHashes[SelectedLibraryContract.ORIGINAL] = sha256(privateOriginal)
            publisher.publishOriginal(
                handle,
                VIDEO_ID,
                privateOriginal,
                expectedHashes.getValue(SelectedLibraryContract.ORIGINAL)
            ).getOrThrow()
            assertFalse(privateOriginal.exists())
            assertEquals(
                setOf(SelectedLibraryContract.ORIGINAL),
                File(rootA, VIDEO_ID).list().orEmpty().toSet()
            )

            val archive = writeArchive(context)
            val imported = importer.importStagedFiles(
                listOf(archive),
                listOf(archive.name)
            ).getOrThrow().items.single()
            assertEquals(imported.errorCode, EnrichmentImportOutcome.IMPORTED, imported.outcome)
            assertEquals(VIDEO_ID, imported.packageId)
            sourceFixture.parts.forEach { (name, bytes) ->
                expectedHashes[name] = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            }
            sourceFixture.publishParts(context, publisher, external, handle, STEM_PUBLICATION_OWNER)

            val packageDirectory = File(rootA, VIDEO_ID)
            assertExactLayout(packageDirectory, EXPECTED_ENRICHED_FILES)
            assertMediaHashes(packageDirectory, expectedHashes)
            val publishedMediaHashes = expectedHashes.toMap()

            val installedFile = File(packageDirectory, SelectedLibraryContract.ENRICHMENT)
            val installed = JSONObject(installedFile.readText(Charsets.UTF_8))
            assertEquals(INSTALLED_ENRICHMENT_SCHEMA_VERSION, installed.getInt("schemaVersion"))
            assertEquals(INSTALLED_ENRICHMENT_KIND, installed.getString("kind"))
            assertEquals(
                INSTALLED_ENRICHMENT_CONTRACT,
                installed.getJSONObject("install").getString("contractVersion")
            )
            validateInstalledEnrichmentDocument(
                installed,
                VIDEO_ID,
                publishedMediaHashes.getValue(SelectedLibraryContract.ORIGINAL)
            )
            val installedMedia = installedMediaIdentities(installed)
            assertEquals(listOf(SelectedLibraryContract.ORIGINAL), installedMedia.keys.toList())
            installedMedia.forEach { (name, identity) ->
                assertEquals(name, publishedMediaHashes.getValue(name), identity.sha256)
                assertEquals(name, File(packageDirectory, name).length(), identity.byteLength)
            }
            val artwork = installed.getJSONObject("artwork")
            val artworkFile = File(packageDirectory, SelectedLibraryContract.COVER)
            assertTrue(artworkFile.isFile)
            assertEquals(COVER_PNG.size.toLong(), artworkFile.length())
            assertEquals("selected-root", artwork.getString("delivery"))
            assertEquals("cover.png", artwork.getString("file"))
            assertEquals(sha256(artworkFile), artwork.getString("sha256"))

            external.reconcile()
            assertTrue(
                external.problems.value.joinToString { "${it.code}:${it.message}" },
                external.problems.value.isEmpty()
            )
            val song = external.songs.value.single()
            assertEquals(VIDEO_ID, song.id)
            assertEquals(VIDEO_ID, song.videoId)
            assertTrue(song.hasStems)
            assertEquals(64, song.mediaRevision.length)
            assertEquals("content", Uri.parse(song.sourcePath).scheme)
            val activeSet = dao.separationSetsForSong(VIDEO_ID).single { it.isActive }
            val stems = dao.stemsForSet(activeSet.id).sortedBy { it.normalizedCapability }
            assertEquals(EXPECTED_SOURCE_IDS.size, stems.size)
            assertEquals(
                EXPECTED_SOURCE_IDS.sorted(),
                stems.mapNotNull { it.normalizedCapability }.sorted()
            )
            assertTrue(stems.all { Uri.parse(it.localPath).scheme == "content" && it.validated })

            val playback = application.services.audioPlayback
            check(playback.debug.verifyPlaybackSong == null && playback.debug.verifyPlaybackStems == null)
            // The fixture owns an isolated durable root; validate its real provider package.
            playback.debug.verifyPlaybackSong = { external.verifyOriginalPlaybackSong(handle, it.id) }
            playback.debug.verifyPlaybackStems = { songId, requested -> external.verifyPlaybackStems(handle, songId, requested) }
            val playbackEvidence = try {
                exerciseRealPlayback(song, stems)
            } finally {
                resetPlayback()
                playback.debug.verifyPlaybackSong = null
                playback.debug.verifyPlaybackStems = null
            }
            val state = JSONObject()
                .put("schemaVersion", 1)
                .put("fixtureId", "physical-library-lifecycle-v1")
                .put("videoId", VIDEO_ID)
                .put("sourceId", SOURCE_ID)
                .put("treeA", treeA.toString())
                .put("treeB", treeB.toString())
                .put("libraryId", handle.libraryId)
                .put("rootEpoch", handle.epoch)
                .put("durationMs", durationMs)
                .put("mediaHashes", JSONObject(publishedMediaHashes))
                .put("privateMediaBaseline", JSONObject(privateMediaBaseline))
                .put("installedSha256", sha256(installedFile))
                .put("artworkSha256", sha256(artworkFile))
                .put("playback", playbackEvidence)
            stateFile(context).writeText(state.toString(), Charsets.UTF_8)
            assertTrue(stateFile(context).isFile)

            val event = JSONObject(state.toString())
                .put("phase", "PHASE_ONE_COMPLETE")
                .put("selectedLayout", JSONArray(packageDirectory.list().orEmpty().sorted()))
                .put("rootBLayout", JSONArray(File(providerRoot(), ROOT_B_NAME).list().orEmpty()))
                .put("operationJournals", JSONArray(privateChildren(context, "operation-journals")))
                .put("enrichmentRecovery", JSONArray(privateChildren(context, "enrichment-recovery")))
            assertEquals(0, event.getJSONArray("operationJournals").length())
            assertEquals(0, event.getJSONArray("enrichmentRecovery").length())
            emitEvent("PHASE1", event)
        } finally {
            resetPlayback()
            database.close()
        }
    }

    @Test
    fun phaseTwoRestartsRecoversIsolatesDeletesAndCleans() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        privateMediaInventoryDetectsCurrentNativeFormatsAndCleanConvergence()
        resetPlayback()
        val context = PersistentPhysicalContext(targetContext, ROOT_A_NAMESPACE)
        val treeA = treeUri(ROOT_A_NAME)
        val treeB = treeUri(ROOT_B_NAME)
        grantTrees(treeA, treeB)
        val durable = JSONObject(stateFile(context).readText(Charsets.UTF_8))
        val privateMediaBaseline = durable.getJSONObject("privateMediaBaseline").let { baseline ->
            baseline.keys().asSequence().associateWith(baseline::getString)
        }
        assertEquals("physical-library-lifecycle-v1", durable.getString("fixtureId"))
        assertEquals(VIDEO_ID, durable.getString("videoId"))
        assertEquals(treeA.toString(), durable.getString("treeA"))
        val expectedHashes = durable.getJSONObject("mediaHashes").keys().asSequence()
            .associateWith { durable.getJSONObject("mediaHashes").getString(it) }
        val packageDirectory = File(File(providerRoot(), ROOT_A_NAME), VIDEO_ID)

        val database = LibraryDatabase.buildFreshProjectionDatabase(context, PHYSICAL_DATABASE_NAME)
        var contextB: PersistentPhysicalContext? = null
        var databaseB: LibraryDatabase? = null
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            val restarted = roots.initialize()
            assertTrue("restart state=$restarted", restarted.ready)
            assertEquals(treeA, restarted.treeUri)
            assertEquals(durable.getString("libraryId"), restarted.libraryId)
            val handleA = checkNotNull(roots.captureHandle())
            assertExactLayout(packageDirectory, EXPECTED_ENRICHED_FILES)
            assertMediaHashes(packageDirectory, expectedHashes)
            assertEquals(
                durable.getString("installedSha256"),
                sha256(File(packageDirectory, SelectedLibraryContract.ENRICHMENT))
            )
            assertEquals(
                durable.getString("artworkSha256"),
                sha256(File(packageDirectory, SelectedLibraryContract.COVER))
            )

            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val governor = BackgroundWorkCoordinator()
            val external = ExternalLibraryRepository(
                context,
                roots,
                dao,
                bindings,
                mutations,
                governor
            )
            val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
            val importer = SongEnrichmentImporter(
                context,
                roots,
                mutations,
                bindings,
                governor,
                originalPublisher = { publisher }
            )
            external.reconcile()
            assertEquals(VIDEO_ID, external.songs.value.single().videoId)
            assertEquals(mapOf(SOURCE_ID to VIDEO_ID), bindings.activePackageIdsBySource(handleA))

            setProviderFault(GoldenQaDocumentsProvider.FAULT_THROW)
            val refused = roots.reconnect()
            assertFalse(refused.ready)
            assertEquals("ROOT_UNAVAILABLE", refused.errorCode)
            assertNull(roots.captureHandle())
            assertMediaHashes(packageDirectory, expectedHashes)
            assertEquals(0, privateChildren(context, "operation-journals").size)

            setProviderFault(GoldenQaDocumentsProvider.FAULT_NONE)
            checkNotNull(
                targetContext.contentResolver.call(
                    Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                    GoldenQaDocumentsProvider.METHOD_RESET_METRICS,
                    null,
                    null
                )
            )
            val retried = roots.reconnect()
            assertTrue("retry state=$retried", retried.ready)
            assertEquals(handleA.libraryId, retried.libraryId)
            assertEquals(handleA.treeUri, retried.treeUri)
            val reconnectMetrics = checkNotNull(
                    targetContext.contentResolver.call(
                        Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                        GoldenQaDocumentsProvider.METHOD_METRICS,
                        null,
                        null
                    )
                )
            val rootDocumentId = DocumentsContract.getTreeDocumentId(treeA)
            assertEquals(
                "Reconnect must enumerate only the selected root",
                listOf(rootDocumentId),
                reconnectMetrics.getStringArrayList("queryChildParents")
            )
            assertEquals("Reconnect queryChildCalls", 1L, reconnectMetrics.getLong("queryChildCalls"))
            val recoveredHandle = checkNotNull(roots.captureHandle())
            external.reconcile()
            val reconciledParents = checkNotNull(
                targetContext.contentResolver.call(
                    Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                    GoldenQaDocumentsProvider.METHOD_METRICS,
                    null,
                    null
                )
            ).getStringArrayList("queryChildParents")
            assertTrue(
                "Reconciliation must inspect the recovered song package",
                checkNotNull(reconciledParents).contains("$rootDocumentId/$VIDEO_ID")
            )
            assertEquals(VIDEO_ID, external.songs.value.single().videoId)
            assertMediaHashes(packageDirectory, expectedHashes)

            contextB = PersistentPhysicalContext(targetContext, ROOT_B_NAMESPACE, clearFirst = true)
            databaseB = LibraryDatabase.buildFreshProjectionDatabase(
                contextB,
                PHYSICAL_DATABASE_NAME
            )
            val daoB = databaseB.libraryDao()
            val mutationsB = PackageMutationCoordinator(contextB)
            val rootsB = LibraryRootManager(contextB, mutationsB, daoB)
            val candidateB = rootsB.validateCandidate(treeB).getOrThrow()
            assertEquals(0, candidateB.existingEntryCount)
            rootsB.commitValidatedCandidateForDevelopment(candidateB).getOrThrow()
            val handleB = checkNotNull(rootsB.captureHandle())
            assertEquals(treeB, handleB.treeUri)
            val bindingsB = SourceBindingRegistry(
                contextB,
                rootsB,
                mutationsB,
                databaseB.libraryDao()
            )
            val externalB = ExternalLibraryRepository(
                contextB,
                rootsB,
                daoB,
                bindingsB,
                mutationsB,
                BackgroundWorkCoordinator()
            )
            val publisherB = CanonicalPackagePublisher(
                contextB,
                rootsB,
                daoB,
                mutationsB,
                bindingsB,
                externalB,
                BackgroundWorkCoordinator()
            )
            val sourceFixture = CurrentSourcePublicationFixture.load(targetContext, VIDEO_ID)
            val staleSource = File(contextB.cacheDir, "stale-part.flac").apply {
                writeBytes(sourceFixture.parts.getValue("part-1.flac"))
            }
            val stalePublish = publisherB.publishSourcePart(
                recoveredHandle, VIDEO_ID, "part-1.flac", staleSource, sha256(staleSource),
                STEM_PUBLICATION_OWNER, sourceFixture.manifest.sourceSha256,
                sourceFixture.manifest.audibleFrames)
            assertTrue(stalePublish.isFailure)
            assertEquals("LIBRARY_CHANGED", stalePublish.exceptionOrNull()?.message)
            assertTrue(staleSource.isFile)
            assertTrue(staleSource.delete())

            val trashB = ExternalPackageTrash(contextB, rootsB, mutationsB, bindingsB, daoB)
            val staleDelete = trashB.deletePermanently(recoveredHandle, VIDEO_ID)
            assertTrue(staleDelete.isFailure)
            assertEquals("LIBRARY_CHANGED", staleDelete.exceptionOrNull()?.message)
            val stalePrivateJournals = privateChildren(contextB, "operation-journals")
            assertTrue(
                "A stale A-to-B cleanup attempt left private journal authority: " +
                    stalePrivateJournals,
                stalePrivateJournals.isEmpty()
            )
            assertEquals(
                setOf(
                    SelectedLibraryContract.ROOT_MANIFEST,
                    SelectedLibraryContract.ROOT_MANIFEST_BACKUP
                ),
                File(providerRoot(), ROOT_B_NAME).list().orEmpty().toSet()
            )
            assertExactLayout(packageDirectory, EXPECTED_ENRICHED_FILES)
            assertMediaHashes(packageDirectory, expectedHashes)

            val unknownDirectory = File(File(providerRoot(), ROOT_A_NAME), UNKNOWN_VIDEO_ID).apply {
                requireDirectory(this)
            }
            val unknownEvidence = File(unknownDirectory, "owner-unknown.bin").apply {
                writeBytes(byteArrayOf(7, 8, 9, 10))
            }
            val unknownHash = sha256(unknownEvidence)
            val trash = ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao())
            val invalidCleanup = trash.deletePermanently(recoveredHandle, "not-a-video-id")
            assertTrue(invalidCleanup.isFailure)
            assertEquals("PACKAGE_ID_INVALID", invalidCleanup.exceptionOrNull()?.message)
            assertEquals(unknownHash, sha256(unknownEvidence))

            val deletion = PermanentDeletionCoordinator(
                trash,
                external,
                SongWorkCoordinator()
            ).deletePermanently(recoveredHandle, VIDEO_ID).getOrThrow()
            assertFalse(deletion.projectionRefreshPending)
            assertFalse(packageDirectory.exists())
            assertTrue(unknownEvidence.isFile)
            assertEquals(unknownHash, sha256(unknownEvidence))
            assertTrue(external.songs.value.none { it.id == VIDEO_ID })
            assertNull(dao.findSongById(VIDEO_ID))
            assertTrue(dao.sourcePathsForSong(VIDEO_ID).isEmpty())
            assertTrue(dao.stemAssetsForSongs(listOf(VIDEO_ID)).isEmpty())
            assertTrue(bindings.activePackageIdsBySource(recoveredHandle).isEmpty())
            assertTrue(SOURCE_ID in bindings.blockedSourceIds(recoveredHandle))
            importer.cleanupOrphanScratch()
            assertTrue(privateChildren(context, "operation-journals").isEmpty())
            assertTrue(privateChildren(context, "enrichment-recovery").isEmpty())
            assertTrue(privateChildren(context, "cache/enrichment").isEmpty())

            val beforeCleanup = JSONObject()
                .put("phase", "PHASE_TWO_DELETE_COMPLETE")
                .put("restartTree", retried.treeUri.toString())
                .put("restartLibraryId", retried.libraryId)
                .put("recoveryRefusal", refused.errorCode)
                .put("recoveryRetryReady", retried.ready)
                .put("stalePublishError", stalePublish.exceptionOrNull()?.message)
                .put("staleDeleteError", staleDelete.exceptionOrNull()?.message)
                .put("stalePrivateJournals", JSONArray(stalePrivateJournals))
                .put("rootBEntries", JSONArray(File(providerRoot(), ROOT_B_NAME).list().orEmpty()))
                .put("targetAbsent", !packageDirectory.exists())
                .put("unknownPreservedSha256", sha256(unknownEvidence))
                .put("sourceBlocked", SOURCE_ID in bindings.blockedSourceIds(recoveredHandle))
                .put("journals", JSONArray(privateChildren(context, "operation-journals")))
                .put("recoveryFiles", JSONArray(privateChildren(context, "enrichment-recovery")))
            emitEvent("PHASE2_DELETE", beforeCleanup)

            databaseB.close()
            databaseB = null
            contextB.destroy()
            contextB = null
            assertTrue(stateFile(context).delete())
            database.close()
            PersistentPhysicalContext.clear(targetContext, ROOT_A_NAMESPACE)
            resetProvider()
            revokeTrees(treeA, treeB)
            resetPlayback()

            val privateMedia = privateMediaHashes(targetContext)
            assertEquals(
                "Idle rehearsal private media changed",
                privateMediaBaseline,
                privateMedia
            )
            assertTrue(providerRoot().list().orEmpty().isEmpty())
            val cleanup = JSONObject()
                .put("phase", "PHASE_TWO_CLEANUP_COMPLETE")
                .put("providerEntries", JSONArray(providerRoot().list().orEmpty()))
                .put("privateMedia", JSONArray(privateMedia.keys.toList()))
                .put("rootAPreferences", JSONArray(PersistentPhysicalContext.preferenceFiles(
                    targetContext,
                    ROOT_A_NAMESPACE
                )))
                .put("rootBPreferences", JSONArray(PersistentPhysicalContext.preferenceFiles(
                    targetContext,
                    ROOT_B_NAMESPACE
                )))
            assertEquals(0, cleanup.getJSONArray("rootAPreferences").length())
            assertEquals(0, cleanup.getJSONArray("rootBPreferences").length())
            emitEvent("PHASE2_CLEANUP", cleanup)
        } finally {
            setProviderFault(GoldenQaDocumentsProvider.FAULT_NONE)
            resetPlayback()
            runCatching { databaseB?.close() }
            runCatching { contextB?.destroy() }
            runCatching { database.close() }
        }
    }

    private suspend fun exerciseRealPlayback(
        song: app.stemwise.model.Song,
        stems: List<StemAssetEntity>
    ): JSONObject {
        val playback = application.services.audioPlayback
        onMain { assertEquals(song, playback.startQueue(listOf(song), shuffle = false, autoplay = false)) }
        waitUntil(12_000L) {
            val state = playback.state.value
            state.songId == VIDEO_ID &&
                !state.loading &&
                !state.playing &&
                state.error == null &&
                PlaybackService.isActiveForTest()
        }
        val mediaSessionMediaId = withContext(Dispatchers.Main) {
            playback.media3Player().currentMediaItem?.mediaId
        }
        assertEquals(VIDEO_ID, mediaSessionMediaId)
        var retainedLease: PlaybackControlLease? = null
        onMain { retainedLease = playback.acquireControlLease(VIDEO_ID) }
        val controlLease = requireNotNull(retainedLease)
        val duration = song.durationMs
        val loopA = (duration / 8L).coerceAtLeast(1L)
        val loopB = (duration * 7L / 8L).coerceAtLeast(loopA + 100L)
            .coerceAtMost(duration - 1L)
        val seekTarget = (duration / 3L).coerceIn(loopA, loopB - 1L)
        onMain {
            assertTrue(playback.setLoop(loopA, loopB, controlLease))
            assertTrue(playback.seekTo(seekTarget))
            playback.play()
        }
        waitUntil(5_000L) {
            val state = playback.state.value
            state.playing && state.positionMs >= loopA &&
                state.loopA == loopA && state.loopB == loopB && state.error == null
        }
        waitUntil(5_000L) {
            val diagnostics = playback.nativeDiagnosticsSnapshotForTest()
            val output = diagnostics?.output
            val token = output?.lastPresentedSelectionToken ?: 0L
            output != null && token > 0L && output.effectiveRenderPlaying &&
                output.callbackCount > 0L && output.pcmLevelFrames > 0L &&
                diagnostics.runtime.ownership.committed?.selectionToken == token
        }
        val originalNative = checkNotNull(playback.nativeDiagnosticsSnapshotForTest())
        val originalOutput = checkNotNull(originalNative.output)
        val ownerToken = originalOutput.lastPresentedSelectionToken
        assertTrue(ownerToken > 0L)
        assertEquals(ownerToken, originalNative.runtime.ownership.committed?.selectionToken)
        assertTrue(originalOutput.effectiveRenderPlaying)
        assertTrue(originalOutput.callbackCount > 0L)
        assertTrue(originalOutput.pcmLevelFrames > 0L)
        assertEquals(ownerToken, originalOutput.pcmLevelSelectionToken)
        assertEquals(0L, originalOutput.invalidOwnershipTagFrames)
        assertEquals(0L, originalOutput.processInvalidOwnershipTagFrames)

        onMain { playback.pause() }
        waitUntil(2_000L) {
            !playback.state.value.playing &&
                playback.nativeDiagnosticsSnapshotForTest()?.output?.effectiveRenderPlaying == false
        }
        onMain {
            assertTrue("Paused loop seek rejected: state=${playback.state.value}; " +
                "owners=${playback.nativeDiagnosticsSnapshotForTest()?.runtime?.ownership}", playback.seekTo(loopA))
            playback.play()
        }
        waitUntil(5_000L) {
            val state = playback.state.value
            state.playing && state.loopA == loopA && state.loopB == loopB && state.error == null
        }
        val activation = try {
            withTimeout(20_000L) {
                withContext(Dispatchers.Main) { playback.activateStems(stems, controlLease) }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            val diagnostics = playback.nativeDiagnosticsSnapshotForTest()
            throw AssertionError("Physical loop stem activation timed out: " +
                "source=${playback.state.value.sourceSwitchState}, error=${playback.state.value.error}; " +
                "reasons=${diagnostics?.controllerStemReadiness?.failureReasons}; " +
                "cacheMask=${diagnostics?.nativeRenderReadiness?.currentSongCacheCompleteLaneMask}; " +
                "requiredMask=${diagnostics?.nativeRenderReadiness?.requiredSongLaneMask}", timeout)
        }
        assertTrue("Physical selected-source activation failed: $activation", activation.isSuccess)
        waitUntil(12_000L) {
            val state = playback.state.value
            state.songId == VIDEO_ID && state.playing &&
                state.sourceMode == PlaybackSourceMode.STEMS &&
                state.loadedStemAssets.size == EXPECTED_SOURCE_IDS.size && state.error == null
        }
        waitUntil(5_000L) {
            val output = playback.nativeDiagnosticsSnapshotForTest()?.output
            output != null && output.effectiveRenderPlaying &&
                output.lastPresentedSelectionToken == ownerToken &&
                output.pcmLevelSelectionToken == ownerToken && output.pcmLevelFrames > 0L
        }
        val stemsState = playback.state.value
        assertEquals(
            EXPECTED_SOURCE_IDS.toSet(),
            stemsState.loadedStemAssets.mapNotNull { it.normalizedCapability }.toSet()
        )
        assertEquals(
            stems.map { it.id }.toSet(),
            stemsState.stemMix.keys
        )
        val stemNative = checkNotNull(playback.nativeDiagnosticsSnapshotForTest())
        val stemOutput = checkNotNull(stemNative.output)
        assertEquals(ownerToken, stemNative.runtime.ownership.committed?.selectionToken)
        assertEquals(0L, stemOutput.invalidOwnershipTagFrames)
        assertEquals(0L, stemOutput.processInvalidOwnershipTagFrames)
        assertEquals(0, stemOutput.lastErrorCode)
        assertFalse(stemOutput.recoveryRequired)
        assertTrue(stemNative.controllerSelectedDecoders.size >= EXPECTED_SOURCE_IDS.size)

        onMain {
            assertTrue(playback.switchToOriginal(controlLease))
        }
        waitUntil(3_000L) {
            playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL &&
                playback.state.value.playing && playback.state.value.error == null
        }
        val finalPosition = playback.state.value.positionMs
        onMain {
            playback.pause()
            playback.setLoop(null, null, controlLease)
            playback.unload(VIDEO_ID)
        }
        return JSONObject()
            .put("songId", VIDEO_ID)
            .put("mediaSessionMediaId", VIDEO_ID)
            .put("sourcePath", song.sourcePath)
            .put("stemSetId", stems.first().separationSetId)
            .put("stemRoles", JSONArray(stems.mapNotNull { it.normalizedCapability }.sorted()))
            .put("ownerToken", ownerToken)
            .put("originalCallbackCount", originalOutput.callbackCount)
            .put("stemCallbackCount", stemOutput.callbackCount)
            .put("pcmLevelFrames", stemOutput.pcmLevelFrames)
            .put("pcmLevelPeak", stemOutput.pcmLevelPeak.toDouble())
            .put("invalidOwnershipTagFrames", stemOutput.invalidOwnershipTagFrames)
            .put("processInvalidOwnershipTagFrames", stemOutput.processInvalidOwnershipTagFrames)
            .put("loopA", loopA)
            .put("loopB", loopB)
            .put("seekTarget", seekTarget)
            .put("finalPosition", finalPosition)
            .put("nativeSelectedDecoderCount", stemNative.controllerSelectedDecoders.size)
            .put("outputBackend", stemOutput.backend.name)
            .put("outputSampleRateHz", stemOutput.sampleRateHz)
            .put("outputChannels", stemOutput.channelCount)
    }

    private fun resetPlayback() {
        application.services.audioPlayback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(8L)
        }
        assertTrue(
            "Timed out waiting for physical playback state: " +
                "state=${application.services.audioPlayback.state.value}; " +
                application.services.audioPlayback.mediaSessionLifecycleDiagnosticsForTest(),
            predicate()
        )
    }

    private fun writeArchive(context: Context): File =
        File(context.cacheDir, "tester-physical-enrichment.stemwise.zip").also { archive ->
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("song.json").apply { time = 0L })
                zip.write(enrichmentPayload().toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("cover.png").apply { time = 0L })
                zip.write(COVER_PNG)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(SelectedLibraryContract.ORIGINAL).apply { time = 0L })
                zip.write(CurrentSourcePublicationFixture.load(targetContext, VIDEO_ID).original)
                zip.closeEntry()
            }
        }

    private fun enrichmentPayload(): JSONObject = JSONObject()
        .put("formatVersion", 2)
        .put("kind", "stemwise.song-enrichment")
        .put("source", JSONObject().put("provider", "youtube").put("videoId", VIDEO_ID))
        .put("generatedAt", "2026-08-10T00:19:00Z")
        .put(
            "generator",
            JSONObject()
                .put("name", ENRICHMENT_GENERATOR_NAME)
                .put("version", ENRICHMENT_GENERATOR_VERSION)
        )
        .put(
            "metadata",
            JSONObject()
                .put("matchStatus", "youtube-fallback")
                .put("title", "Tester physical lifecycle")
                .put("artists", JSONArray().put("StemWise Tester"))
                .put(
                    "sources",
                    JSONArray().put(
                        JSONObject()
                            .put("name", "YouTube Music")
                            .put("url", "https://music.youtube.com/watch?v=$VIDEO_ID")
                    )
                )
        )
        .put(
            "artwork",
            JSONObject()
                .put("file", "cover.png")
                .put("kind", "youtube-thumbnail")
                .put(
                    "source",
                    JSONObject()
                        .put("name", "YouTube Music")
                        .put("url", "https://music.youtube.com/watch?v=$VIDEO_ID")
                )
        )
        .put(
            "lyrics",
            JSONObject()
                .put("status", "unavailable")
                .put("reason", "No licensed lyrics are used by this Tester fixture.")
                .put("tracks", JSONArray())
        )
        .put("analysis", unavailableAnalysis())

    private fun unavailableAnalysis(): JSONObject {
        fun unavailable() = JSONObject()
            .put("status", "unavailable")
            .put("reason", "Not required for the bounded physical Tester fixture.")
        val runs = JSONArray()
        listOf(
            "beat-this-full",
            "stemwise-rhythm-normalizer",
            "s-key",
            "all-in-one",
            "essentia",
            "stemwise-waveform"
        ).forEach { name ->
            runs.put(
                JSONObject()
                    .put("name", name)
                    .put("status", "skipped")
                    .put("reason", "Not required for the bounded physical Tester fixture.")
            )
        }
        return JSONObject()
            .put("status", "unavailable")
            .put("rhythm", unavailable())
            .put("key", unavailable())
            .put("structure", unavailable())
            .put("tuning", unavailable())
            .put("loudness", unavailable())
            .put("waveform", unavailable())
            .put("toolRuns", runs)
    }

    private fun assertExactLayout(directory: File, expected: Set<String>) {
        assertTrue("Missing package directory ${directory.absolutePath}", directory.isDirectory)
        val children = directory.listFiles().orEmpty()
        assertTrue("Nested directory in direct package: ${children.toList()}", children.none { it.isDirectory })
        assertEquals(expected, children.map(File::getName).toSet())
    }

    private fun assertMediaHashes(directory: File, expected: Map<String, String>) {
        assertEquals(EXPECTED_MEDIA_FILES, expected.keys)
        EXPECTED_MEDIA_FILES.forEach { name ->
            val file = File(directory, name)
            assertTrue("Missing direct media $name", file.isFile)
            assertEquals(name, expected.getValue(name), sha256(file))
        }
    }

    private fun decodeAsset(name: String): ByteArray = testContext.assets.open(name)
        .bufferedReader().use { Base64.decode(it.readText(), Base64.DEFAULT) }

    private fun mediaMime(context: Context, bytes: ByteArray, suffix: String): String {
        val file = File.createTempFile("tester-physical-codec-", suffix, context.cacheDir)
        return try {
            file.writeBytes(bytes)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                extractor.getTrackFormat(0).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
            } finally {
                extractor.release()
            }
        } finally {
            assertTrue(!file.exists() || file.delete())
        }
    }

    private fun mediaDurationMs(context: Context, bytes: ByteArray, suffix: String): Long {
        val file = File.createTempFile("tester-physical-duration-", suffix, context.cacheDir)
        return try {
            file.writeBytes(bytes)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                extractor.getTrackFormat(0)
                    .getLong(android.media.MediaFormat.KEY_DURATION)
                    .div(1_000L)
                    .coerceAtLeast(1L)
            } finally {
                extractor.release()
            }
        } finally {
            assertTrue(!file.exists() || file.delete())
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun providerRoot(): File = File(
        targetContext.noBackupFilesDir,
        "golden-enrichment-document-provider"
    )

    private fun treeUri(name: String): Uri = DocumentsContract.buildTreeDocumentUri(
        GoldenQaDocumentsProvider.AUTHORITY,
        "root/$name"
    )

    private fun grantTrees(vararg trees: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        targetContext.grantUriPermission(
            targetContext.packageName,
            GoldenQaDocumentsProvider.treeUri,
            flags
        )
        trees.forEach { tree ->
            targetContext.grantUriPermission(targetContext.packageName, tree, flags)
        }
    }

    private fun revokeTrees(vararg trees: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        trees.forEach { tree ->
            targetContext.revokeUriPermission(targetContext.packageName, tree, flags)
        }
        targetContext.revokeUriPermission(
            targetContext.packageName,
            GoldenQaDocumentsProvider.treeUri,
            flags
        )
    }

    private fun resetProvider() {
        checkNotNull(
            targetContext.contentResolver.call(
                Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                GoldenQaDocumentsProvider.METHOD_RESET,
                null,
                null
            )
        )
    }

    private fun setProviderFault(fault: String) {
        checkNotNull(
            targetContext.contentResolver.call(
                Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                GoldenQaDocumentsProvider.METHOD_SET_FAULT,
                fault,
                null
            )
        )
    }

    private fun stateFile(context: Context): File = File(context.filesDir, "physical-state.json")

    private fun privateChildren(context: Context, relative: String): List<String> {
        val normalized = relative.replace('/', File.separatorChar)
        val root = if (normalized.startsWith("cache${File.separator}")) {
            File(context.cacheDir, normalized.removePrefix("cache${File.separator}"))
        } else {
            File(context.filesDir, normalized)
        }
        return root.listFiles().orEmpty().map(File::getName).sorted()
    }

    private fun privateMediaHashes(context: Context): Map<String, String> {
        return listOf(
            "files" to context.filesDir,
            "cache" to context.cacheDir,
            "no-backup" to context.noBackupFilesDir
        ).distinctBy { it.second.absolutePath }
            .flatMap { (area, root) ->
                if (!root.exists()) emptyList() else root.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in PRIVATE_MEDIA_EXTENSIONS }
                    .map { "$area/${it.relativeTo(root).invariantSeparatorsPath}" to sha256(it) }
                    .toList()
            }
            .toMap()
            .toSortedMap()
    }

    private fun emitEvent(label: String, value: JSONObject) {
        println("TEST30_PHYSICAL_${label}=${value}")
    }

    private class PersistentPhysicalContext(
        base: Context,
        private val namespace: String,
        clearFirst: Boolean = false
    ) : ContextWrapper(base) {
        private val prefix = preferencePrefix(namespace)
        private val root = File(base.cacheDir, "tester-physical/$namespace").canonicalFile
        private val isolatedFiles: File
        private val isolatedCache: File
        private val isolatedNoBackup: File
        private val isolatedDatabases: File

        init {
            if (clearFirst) clear(base, namespace)
            isolatedFiles = File(root, "files").apply(::requireDirectory)
            isolatedCache = File(root, "cache").apply(::requireDirectory)
            isolatedNoBackup = File(root, "no-backup").apply(::requireDirectory)
            isolatedDatabases = File(root, "databases").apply(::requireDirectory)
        }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
        override fun getCacheDir(): File = isolatedCache
        override fun getNoBackupFilesDir(): File = isolatedNoBackup
        override fun getDatabasePath(name: String): File {
            check(name == File(name).name) { "TEST_DATABASE_NAME_INVALID" }
            return File(isolatedDatabases, name)
        }
        override fun deleteDatabase(name: String): Boolean =
            SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$prefix$name", mode).withDurableTestWrites()

        fun destroy() = clear(baseContext, namespace)

        companion object {
            fun clear(base: Context, namespace: String) {
                preferenceFiles(base, namespace).forEach { name ->
                    val preferenceName = name.removeSuffix(".bak").removeSuffix(".xml")
                    base.deleteSharedPreferences(preferenceName)
                    val directory = File(base.applicationInfo.dataDir, "shared_prefs")
                    listOf(
                        File(directory, "$preferenceName.xml"),
                        File(directory, "$preferenceName.xml.bak")
                    ).forEach { file -> check(!file.exists() || file.delete()) }
                }
                val root = File(base.cacheDir, "tester-physical/$namespace").canonicalFile
                check(!root.exists() || root.deleteRecursively())
            }

            fun preferenceFiles(base: Context, namespace: String): List<String> {
                val prefix = preferencePrefix(namespace)
                return File(base.applicationInfo.dataDir, "shared_prefs")
                    .listFiles().orEmpty()
                    .map(File::getName)
                    .filter { it.startsWith(prefix) }
                    .sorted()
            }

            private fun preferencePrefix(namespace: String) = "tester-physical-$namespace-"
        }
    }

    private companion object {
        val EXPECTED_SOURCE_IDS = listOf("lead-vocals", "backing-vocals", "drums", "bass",
            "acoustic-guitar", "electric-guitar", "harp", "mandolin", "remaining")
        val EXPECTED_MEDIA_FILES = setOf("original.opus", "remaining.wv") +
            (1..8).map { "part-$it.flac" }
        val EXPECTED_ENRICHED_FILES = EXPECTED_MEDIA_FILES +
            setOf(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST, "cover.png", "song.json")
        const val VIDEO_ID = "TSTPHY00001"
        const val UNKNOWN_VIDEO_ID = "TSTUNK00001"
        const val SOURCE_ID = "youtube-video:$VIDEO_ID"
        const val STEM_PUBLICATION_OWNER = "2eaa5dbf-dfe0-46a5-a23c-da3a3da42a60"
        const val ROOT_A_NAME = "tester-root-a"
        const val ROOT_B_NAME = "tester-root-b"
        const val ROOT_A_NAMESPACE = "root-a"
        const val ROOT_B_NAMESPACE = "root-b"
        const val PHYSICAL_DATABASE_NAME = "tester-physical-library.db"
        val COVER_PNG: ByteArray = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            Base64.DEFAULT
        )

        fun requireDirectory(directory: File) {
            check(directory.isDirectory || directory.mkdirs()) {
                "Unable to create ${directory.absolutePath}"
            }
        }
    }
}
