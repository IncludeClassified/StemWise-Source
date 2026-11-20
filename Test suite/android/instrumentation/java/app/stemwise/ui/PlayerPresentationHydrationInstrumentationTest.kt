package app.stemwise.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.analysis.ImportedAnalysisRepository
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.authority
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.enrichment.InstalledMediaIdentity
import app.stemwise.enrichment.createInstalledEnrichmentDocument
import app.stemwise.enrichment.enrichmentRevisionIdentity
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal class PlayerPresentationHydrationInstrumentationTest : GoldenEnrichmentFixture() {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun librarySongInfoPropagatesARealManifestReadFailureAndRetries() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            seedDirectMedia()
            val folder = File(providerRoot(), VIDEO_ID)
            val incoming = JSONObject(targetContext.assets.open("qa/enrichment/full-song-enrichment-v2.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() })
            incoming.getJSONObject("source").put("videoId", VIDEO_ID)
            val original = File(folder, SelectedLibraryContract.ORIGINAL)
            val media = InstalledMediaIdentity(sha256(original), original.length())
            val cover = File(folder, SelectedLibraryContract.COVER).apply { writeBytes(COVER_PNG) }
            val installed = createInstalledEnrichmentDocument(incoming, media.sha256,
                mapOf(SelectedLibraryContract.ORIGINAL to media), 1L,
                enrichmentRevisionIdentity(incoming, sha256(cover)), incoming.getJSONObject("artwork"), sha256(cover), COVER_PNG.size)
            val manifest = File(folder, SelectedLibraryContract.ENRICHMENT).apply { writeText(installed.toString(), Charsets.UTF_8) }
            val bytesBefore = manifest.readBytes()
            val song = Song(id = VIDEO_ID, videoId = VIDEO_ID, title = "Hydration fixture", artist = "StemWise", durationMs = 60_000L,
                state = SongState.READY, accent = Color.Cyan, artworkGlyph = "HF", sourceHash = media.sha256,
                audioRevision = media.sha256, mediaRevision = sha256(manifest))
            val analysis = ImportedAnalysisRepository(context, roots)
            // Summary fails first without warming the exact manifest cache.
            setFault(GoldenQaDocumentsProvider.FAULT_MANIFEST_READ_ONCE)
            assertTrue(runCatching { analysis.summary(song.id, song.mediaRevision, checkNotNull(song.sourceHash)) }.isFailure)
            setFault(GoldenQaDocumentsProvider.FAULT_MANIFEST_READ_ONCE)
            val displayed = song.copy(additionalInfo = mapOf("Album" to "Retained album"))
            compose.setContent {
                app.stemwise.ui.theme.StemwiseTheme {
                    LibrarySongInfoDialog(displayed,
                        loadDetail = { owner -> analysis.songDetail(owner.songId, owner.mediaRevision, checkNotNull(owner.sourceHash)) },
                        loadSummary = { null }, onDismiss = {}, onCopyVideoId = {}, onDeleteSong = {}, onDeleteStems = {})
                }
            }
            compose.onNodeWithText("Could not read details").assertExists()
            compose.onNodeWithText("Retained album").assertExists()
            compose.onNodeWithText("Retry details").performClick()
            compose.onNodeWithText("Could not read details").assertDoesNotExist()
            compose.runOnIdle { assertArrayEquals(bytesBefore, manifest.readBytes()) }
            assertNotNull(analysis.summary(song.id, song.mediaRevision, checkNotNull(song.sourceHash)))
        } finally { database.close() }
    }

    @Test fun failedFirstManifestReadRetriesTheUnchangedOwnerAndPublishesOneCoherentPresentation() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val replacementTree = android.provider.DocumentsContract.buildTreeDocumentUri(
            GoldenQaDocumentsProvider.AUTHORITY, "root/presentation-library")
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            seedDirectMedia()
            val folder = File(providerRoot(), VIDEO_ID)
            val incoming = JSONObject(targetContext.assets.open("qa/enrichment/full-song-enrichment-v2.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() })
            incoming.getJSONObject("source").put("videoId", VIDEO_ID)
            val original = File(folder, SelectedLibraryContract.ORIGINAL)
            val media = InstalledMediaIdentity(sha256(original), original.length())
            val cover = File(folder, SelectedLibraryContract.COVER).apply { writeBytes(COVER_PNG) }
            val installed = createInstalledEnrichmentDocument(incoming, media.sha256,
                mapOf(SelectedLibraryContract.ORIGINAL to media), 1L,
                enrichmentRevisionIdentity(incoming, sha256(cover)), incoming.getJSONObject("artwork"), sha256(cover), COVER_PNG.size)
            val manifest = File(folder, SelectedLibraryContract.ENRICHMENT).apply { writeText(installed.toString(), Charsets.UTF_8) }
            val bytesBefore = manifest.readBytes()
            val song = Song(id = VIDEO_ID, videoId = VIDEO_ID, title = "Hydration fixture", artist = "StemWise", durationMs = 60_000L,
                state = SongState.READY, accent = Color.Cyan, artworkGlyph = "HF", sourceHash = media.sha256,
                audioRevision = media.sha256, mediaRevision = sha256(manifest))
            val analysis = ImportedAnalysisRepository(context, roots)
            val services = (targetContext.applicationContext as StemwiseApplication).services
            val selectedSong = mutableStateOf(song)
            val retry = mutableIntStateOf(0)
            val observed = java.util.concurrent.atomic.AtomicReference<androidx.compose.runtime.State<SongPresentationSnapshot>?>()
            fun latest(): SongPresentationSnapshot? = observed.get()?.value
            targetContext.contentResolver.call(GoldenQaDocumentsProvider.treeUri,
                GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_MANIFEST_READ_ONCE, null)
            compose.setContent {
                androidx.compose.foundation.layout.Column {
                val holder = rememberSongPresentationSnapshot(selectedSong.value, services.separationRepository, analysis, retry.intValue)
                androidx.compose.runtime.SideEffect { observed.set(holder) }
                PlayerPresentationRetry(holder.value.contentReadFailed) { retry.intValue++ }
                androidx.compose.material3.Text("${holder.value.contentLoaded}/${holder.value.contentReadFailed}")
                }
            }
            compose.waitUntil(5_000) { latest()?.let { it.contentLoaded || it.contentReadFailed } == true }
            compose.runOnIdle {
                assertTrue("Transient read became authoritative absence", checkNotNull(latest()).contentReadFailed)
                assertFalse(checkNotNull(latest()).contentLoaded)
            }
            compose.onNodeWithText("Retry song details").performClick()
            compose.waitUntil(5_000) { latest()?.contentLoaded == true && latest()?.contentReadFailed == false }
            compose.runOnIdle {
                val loaded = checkNotNull(latest())
                assertEquals(SongPresentationOwner.from(song), loaded.owner)
                assertTrue(loaded.analysis.beatEvents.size > 2)
                assertTrue(loaded.analysis.waveformPeaks.isNotEmpty())
                assertTrue(loaded.analysis.songSections.isNotEmpty())
                assertNotNull(loaded.analysis.summary)
                assertTrue(loaded.song.lyricTracks.isNotEmpty())
                assertEquals(loaded.detail.lyricTracks, loaded.song.lyricTracks)
                assertArrayEquals(bytesBefore, manifest.readBytes())
            }
            val fine = checkNotNull(analysis.fineWindow5ms(song.id, 500L, 250L, song.mediaRevision, media.sha256))
            assertTrue(fine.peaks.isNotEmpty())
            assertNull(analysis.fineWindow5ms(song.id, 500L, 250L, song.mediaRevision, "0".repeat(64)))
            targetContext.contentResolver.call(GoldenQaDocumentsProvider.treeUri,
                GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_MANIFEST_READ_ONCE, null)
            // A warm exact-owner read does not touch the provider or consume its one-shot fault.
            assertEquals(fine, analysis.fineWindow5ms(song.id, 500L, 250L, song.mediaRevision, media.sha256))
            val previousRoot = checkNotNull(roots.captureHandle())
            setFault(GoldenQaDocumentsProvider.FAULT_NONE)
            val replacementDirectory = File(providerRoot(), "presentation-library").apply { check(mkdir()) }
            val activeFolder = File(replacementDirectory, VIDEO_ID)
            check(folder.copyRecursively(activeFolder))
            grantProviderTree(replacementTree)
            roots.reportRuntimeAccessFailure(previousRoot, SecurityException("isolated cache-owner replacement"))
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(replacementTree).getOrThrow()).getOrThrow()
            val activeRoot = checkNotNull(roots.captureHandle())
            assertNotEquals(previousRoot.authority(), activeRoot.authority())
            assertEquals(replacementTree, activeRoot.treeUri)
            val activeManifest = File(activeFolder, SelectedLibraryContract.ENRICHMENT)
            assertArrayEquals(bytesBefore, activeManifest.readBytes())
            setFault(GoldenQaDocumentsProvider.FAULT_MANIFEST_READ_ONCE)
            assertTrue("A departed root's cache bypassed the new root's provider read",
                runCatching { analysis.presentation(song.id, song.mediaRevision, media.sha256) }.isFailure)
            val recovered = analysis.presentation(song.id, song.mediaRevision, media.sha256)
            assertEquals(checkNotNull(latest()).analysis.beatEvents, recovered.timeline.beats)
            assertEquals(fine, analysis.fineWindow5ms(song.id, 500L, 250L, song.mediaRevision, media.sha256))
            val unavailable = payload("2026-10-24T18:00:00Z", "Timing unavailable", includeArtwork = true)
            val replacement = createInstalledEnrichmentDocument(unavailable, media.sha256,
                mapOf(SelectedLibraryContract.ORIGINAL to media), 2L,
                enrichmentRevisionIdentity(unavailable, sha256(cover)), unavailable.getJSONObject("artwork"), sha256(cover), COVER_PNG.size)
            activeManifest.writeText(replacement.toString(), Charsets.UTF_8)
            val nextSong = song.copy(mediaRevision = sha256(activeManifest))
            compose.runOnIdle { selectedSong.value = nextSong }
            compose.waitUntil(5_000) { latest()?.let { it.owner == SongPresentationOwner.from(nextSong) && it.contentLoaded } == true }
            compose.runOnIdle {
                val absent = checkNotNull(latest())
                assertFalse(absent.contentReadFailed)
                assertTrue(absent.analysis.beatEvents.isEmpty())
                assertTrue(absent.analysis.waveformPeaks.isEmpty())
                assertTrue(absent.analysis.songSections.isEmpty())
                assertTrue(absent.detail.lyricTracks.isEmpty())
                assertEquals("unavailable", absent.analysis.summary?.status)
                assertEquals(media.sha256, sha256(original))
                assertEquals(media.sha256, sha256(File(activeFolder, SelectedLibraryContract.ORIGINAL)))
                assertArrayEquals(bytesBefore, manifest.readBytes())
            }
        } finally {
            try { database.close() } finally {
                targetContext.revokeUriPermission(targetContext.packageName, replacementTree,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }
}
