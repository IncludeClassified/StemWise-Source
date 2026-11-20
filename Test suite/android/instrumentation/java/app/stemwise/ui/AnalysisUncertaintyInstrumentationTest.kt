package app.stemwise.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import app.stemwise.StemwiseServices
import app.stemwise.data.LibraryDatabase
import app.stemwise.enrichment.*
import app.stemwise.model.Song
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.ui.theme.StemwiseTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal class AnalysisUncertaintyInstrumentationTest : GoldenEnrichmentFixture() {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun importedFeatureUncertaintySurvivesRefreshStorageReopenAndTransposition() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val databasePath = File(context.filesDir, "analysis.db").absolutePath
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, databasePath)
            .allowMainThreadQueries().build()
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(false, false, true))
            override fun currentState() = state.value
        }
        lateinit var services: StemwiseServices
        compose.runOnUiThread { services = StemwiseServices(context, network, database) }
        val displayed = mutableStateOf<Song?>(null)
        val showInfo = mutableStateOf(true)
        val pitch = mutableStateOf(0)
        compose.setContent { StemwiseTheme { displayed.value?.let { song ->
            if (showInfo.value) LibrarySongInfoDialog(song, loadDetail = { owner ->
                services.analysisRepository.songDetail(owner.songId, owner.mediaRevision, owner.sourceHash.orEmpty())
            }, loadSummary = { owner ->
                services.analysisRepository.summary(owner.songId, owner.mediaRevision, owner.sourceHash.orEmpty())
            }, onDismiss = {}, onCopyVideoId = {}, onDeleteSong = {}, onDeleteStems = {})
            else KeyPanel(song, pitch.value, { pitch.value = it; true })
        } } }
        try {
            val original = File(providerRoot(), "$VIDEO_ID/original.opus")
            val originalFacts = app.stemwise.data.inspectOriginalAudio(original)
            val durationMs = originalFacts.audibleFramesNative * 1_000L / originalFacts.sampleRateHz
            for (status in listOf("complete", "uncertain")) {
                val keyReason = "The two strongest keys have similar support. ".repeat(8).trim()
                val tuningReason = "The reference pitch varies across the recording. ".repeat(8).trim()
                val incoming = payload("2026-08-09T12:00:00Z", "Feature $status", true)
                val analysis = unavailableAnalysis().put("status", "partial").put("durationMs", durationMs)
                analysis.put("key", JSONObject().put("status", status).put("tonic", "C").put("mode", "minor"))
                analysis.put("tuning", JSONObject().put("status", status).put("frequencyHz", 440.2).put("centsFromA440", .79))
                if (status == "uncertain") {
                    analysis.getJSONObject("key").put("reason", keyReason)
                    analysis.getJSONObject("tuning").put("reason", tuningReason)
                }
                incoming.put("analysis", analysis)
                validateEnrichmentContract(incoming, hasCover = true)
                val cover = File(original.parentFile, "cover.png")
                val installed = createInstalledEnrichmentDocument(incoming, sha256(original),
                    mapOf("original.opus" to InstalledMediaIdentity(sha256(original), original.length())), 1L,
                    enrichmentRevisionIdentity(incoming, sha256(cover)), incoming.getJSONObject("artwork"),
                    sha256(cover), cover.length().toInt())
                File(original.parentFile, "song.json").writeText(installed.toString(), Charsets.UTF_8)
                val song = runBlocking { withTimeout(20_000L) {
                    val roots = services.libraryRootManager
                    if (!roots.state.value.ready) roots.commitValidatedCandidateForDevelopment(
                        roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                    services.externalLibraryRepository.reconcile()
                    services.playlistRepository.songs.first { songs -> songs.any { it.id == VIDEO_ID && it.keyStatus == status } }
                        .single { it.id == VIDEO_ID }
                } }
                // A fresh Room consumer must see the committed feature state, not an in-memory echo.
                val reopened = Room.databaseBuilder(context, LibraryDatabase::class.java, databasePath)
                    .allowMainThreadQueries().build()
                try { runBlocking {
                    val saved = reopened.libraryDao().analysesForSongs(listOf(VIDEO_ID)).single()
                    assertEquals(status, saved.keyStatus); assertEquals(status, saved.tuningStatus)
                    if (status == "uncertain") {
                        assertTrue(checkNotNull(saved.keyReason).length <= 256)
                        assertTrue(checkNotNull(saved.tuningReason).length <= 256)
                        assertEquals(saved.keyReason, song.keyReason)
                    } else {
                        assertNull(saved.keyReason); assertNull(saved.tuningReason)
                    }
                    assertEquals(status, reopened.libraryDao().observeSongSnapshots().first().single().keyStatus)
                } } finally { reopened.close() }
                val summary = runBlocking { services.analysisRepository.summary(song.id, song.mediaRevision, song.sourceHash!!) }!!
                assertEquals(status, summary.keyStatus); assertEquals(status, summary.tuningStatus)
                assertEquals(if (status == "uncertain") keyReason else null, summary.keyReason)
                assertEquals(if (status == "uncertain") tuningReason else null, summary.tuningReason)
                compose.runOnIdle { displayed.value = song; showInfo.value = true }
                val keyLabel = if (status == "uncertain") "C Minor (uncertain)" else "C Minor"
                compose.waitUntil(5_000L) { compose.onAllNodesWithText(keyLabel).fetchSemanticsNodes().isNotEmpty() }
                if (status == "uncertain") {
                    compose.onNodeWithText(keyReason).performScrollTo().assertExists()
                    compose.onNodeWithText(tuningReason).performScrollTo().assertExists()
                    assertTrue(songInfoMetadataRows(song, summary).single { it.first == "Tuning" }.second.endsWith("(uncertain)"))
                } else assertTrue(songInfoMetadataRows(song, summary).none { "uncertain" in it.second })
                compose.runOnIdle { showInfo.value = false; pitch.value = 2 }
                compose.onNodeWithText("D minor").assertExists()
                if (status == "uncertain") {
                    compose.onNodeWithText("Uncertain · ${song.keyReason}").assertExists()
                    compose.onNodeWithContentDescription("Key adjustment").assert(SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription, "D minor (uncertain) · ${song.keyReason}"))
                }
                else compose.onAllNodesWithText("Uncertain", substring = true).fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
            }
        } finally {
            compose.runOnIdle { displayed.value = null }
            runBlocking { withTimeout(10_000L) {
                services.externalLibraryRepository.retireScansForLibrarySwitch()
                services.audioPlayback.clearLibrarySession()
            } }
            compose.runOnUiThread { services.audioPlayback.release() }
            database.close()
        }
    }
}
