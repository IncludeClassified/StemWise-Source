package app.stemwise.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stemwise.analysis.ImportedAnalysisSummary
import app.stemwise.analysis.ImportedSongDetail
import app.stemwise.model.Song
import app.stemwise.model.SongState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LibrarySongInfoInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun loopFailureShowsItsReasonAndAnEffectiveRetry() {
        val phase = mutableStateOf(app.stemwise.playback.LoopPreparationState(
            phase = app.stemwise.playback.LoopPreparationPhase.FAILED,
            reason = "Could not read loop audio", retryable = true))
        val attempts = AtomicInteger()
        compose.setContent {
            LoopPreparationNotice(phase.value) {
                attempts.incrementAndGet()
                phase.value = phase.value.copy(phase = app.stemwise.playback.LoopPreparationPhase.PREPARING)
                true
            }
        }
        compose.onNodeWithText("Could not read loop audio").assertExists()
        compose.onNodeWithText("Retry loop preparation").performClick()
        compose.onNodeWithText("Could not read loop audio").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, attempts.get()) }
    }

    @Test fun detailAndAnalysisFailuresRetryIndependentlyWithoutLosingAvailableMetadata() {
        val detailReads = AtomicInteger()
        val summaryReads = AtomicInteger()
        compose.setContent {
            LibrarySongInfoDialog(song("first"), loadDetail = {
                if (detailReads.incrementAndGet() == 1) error("Storage unavailable")
                ImportedSongDetail(additionalInfo = mapOf("Album" to "Retained album"))
            }, loadSummary = {
                if (summaryReads.incrementAndGet() == 1) error("Storage unavailable")
                ImportedAnalysisSummary("complete", integratedLufs = -12.0)
            }, onDismiss = {}, onCopyVideoId = {}, onDeleteSong = {}, onDeleteStems = {})
        }
        compose.onNodeWithText("Could not read details").assertExists()
        compose.onNodeWithText("Could not read analysis").assertExists()
        compose.onNodeWithText("Retry details").performClick()
        compose.onNodeWithText("Retained album").assertExists()
        compose.onNodeWithText("Could not read details").assertDoesNotExist()
        compose.onNodeWithText("Could not read analysis").assertExists()
        compose.runOnIdle { assertEquals(2, detailReads.get()); assertEquals(1, summaryReads.get()) }
        compose.onNodeWithText("Retry analysis").performClick()
        compose.onNodeWithText("Could not read analysis").assertDoesNotExist()
        compose.onNodeWithText("Retained album").assertExists()
        compose.runOnIdle { assertEquals(2, detailReads.get()); assertEquals(2, summaryReads.get()) }
    }

    @Test fun aLateReadCannotPublishMetadataForThePreviousSong() {
        val selected = mutableStateOf(song("old"))
        val releaseOld = CompletableDeferred<Unit>()
        val oldEntered = CompletableDeferred<Unit>()
        compose.setContent {
            LibrarySongInfoDialog(selected.value, loadDetail = { owner ->
                if (owner.songId == "old") withContext(NonCancellable) {
                    oldEntered.complete(Unit); releaseOld.await()
                }
                ImportedSongDetail(additionalInfo = mapOf("Album" to "Album ${owner.songId}"))
            }, loadSummary = { null }, onDismiss = {}, onCopyVideoId = {}, onDeleteSong = {}, onDeleteStems = {})
        }
        try {
            compose.waitUntil(5_000) { oldEntered.isCompleted }
            compose.runOnIdle { selected.value = song("new") }
            compose.onNodeWithText("Album new").assertExists()
            compose.runOnIdle { releaseOld.complete(Unit) }
            compose.onNodeWithText("Album old").assertDoesNotExist()
            compose.onNodeWithText("Album new").assertExists()
        } finally { releaseOld.complete(Unit) }
    }

    private fun song(id: String) = Song(id = id, videoId = id, title = "Song $id", artist = "StemWise",
        durationMs = 60_000, state = SongState.READY, accent = Color.Cyan, artworkGlyph = "SW",
        sourceHash = id, mediaRevision = "revision-$id", audioRevision = id)
}
