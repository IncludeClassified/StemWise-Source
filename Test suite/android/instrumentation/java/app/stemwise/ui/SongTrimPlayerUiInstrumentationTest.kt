package app.stemwise.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongTrimRange
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.data.SongPlaybackSettings
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.LoopPlaybackFixture
import app.stemwise.playback.PlaybackContentFixture
import app.stemwise.playback.StemMixState
import app.stemwise.playback.debug
import app.stemwise.playback.resetForInstrumentationIsolation
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.ui.player.surface.FullPlayerLoopBoundary
import app.stemwise.ui.player.surface.PlayerSurfaceView
import app.stemwise.ui.theme.StemwiseTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real full-player entry, native handle motor, facade and durable trim store on a disposable root. */
class SongTrimPlayerUiInstrumentationTest : LoopPlaybackFixture() {
    @get:Rule(order = 1) val compose = createStemwiseComposeRule()
    private lateinit var song: Song
    private lateinit var identity: SongPlaybackSettingsIdentity

    @Before fun prepareSongAndRoot() = runBlocking {
        val roots = application.services.libraryRootManager
        if (!roots.state.value.ready) roots.initialize(application.services.preferences)
        if (!roots.state.value.ready) {
            val tree = GoldenQaDocumentsProvider.treeUri
            application.grantUriPermission(application.packageName, tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
        }
        val id = "trim-ui-${UUID.randomUUID()}"
        val directory = File(application.cacheDir, id).apply { check(mkdir()) }
        val original = File(directory, "original.wav")
        writeSineWave(original, 60_000L, 220.0)
        song = Song(id, id, "Trim practice", "StemWise", 60_000L, SongState.READY, Color.Cyan,
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceHash = PlaybackContentFixture.sourceHash(original), sourceCodec = "pcm_s16le",
            sourceContainer = "wav", audioRevision = id, mediaRevision = id, artworkGlyph = "T")
        val root = checkNotNull(roots.captureHandle())
        identity = SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), song.id, checkNotNull(song.sourceHash))
    }

    @After fun clearOwnedTrim() {
        if (::identity.isInitialized) store().clear(identity)
    }

    @Test fun rapidNextRendersCachedProfilesBeforeSourceCommitAndRejectsLateEarlierVerification() {
        val root = checkNotNull(application.services.libraryRootManager.captureHandle())
        val profiles = listOf(220.0, 330.0, 440.0).mapIndexed { index, frequency ->
            val original = File(application.cacheDir, "${song.id}/profile-$index.wav")
            writeSineWave(original, 6_000L, frequency)
            val hash = PlaybackContentFixture.sourceHash(original)
            song.copy(id = "${song.id}-$index", videoId = "${song.id}-$index", title = "Cached profile $index",
                durationMs = 6_000L, sourcePath = PlaybackContentFixture.publish(application, original),
                sourceHash = hash, audioRevision = hash, mediaRevision = "profile-$index")
        }
        val settings = listOf(
            SongPlaybackSettings(trim = SongTrimRange(200L, 5_800L), tempo = .75f, semitones = -4,
                stemMix = mapOf("vocals" to StemMixState(.2f, false, true))),
            SongPlaybackSettings(trim = SongTrimRange(500L, 4_500L), tempo = 1.5f, semitones = 5,
                stemMix = mapOf("vocals" to StemMixState(.4f, true, false))),
            SongPlaybackSettings(trim = SongTrimRange(1_200L, 5_700L), tempo = .5f, semitones = -7,
                stemMix = mapOf("vocals" to StemMixState(.7f, false, false)))
        )
        val identities = profiles.map { profile -> SongPlaybackSettingsIdentity(root.libraryId,
            root.treeUri.toString(), profile.id, checkNotNull(profile.sourceHash)) }
        val enteredB = CompletableDeferred<Unit>()
        val enteredC = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val releaseC = CompletableDeferred<Unit>()
        val earlierVerifications = ConcurrentLinkedQueue<Job>()
        val heldVerifications = ConcurrentLinkedQueue<Job>()
        val verifier = checkNotNull(playback.debug.verifyPlaybackSong)
        compose.mainClock.autoAdvance = false
        try {
            identities.forEachIndexed { index, row -> store().update(row, profiles[index].durationMs) { settings[index] } }
            onMain {
                playback.debug.verifyPlaybackSong = { candidate ->
                    val gate = when (candidate.id) {
                        profiles[1].id -> { enteredB.complete(Unit); releaseB }
                        profiles[2].id -> { enteredC.complete(Unit); releaseC }
                        else -> null
                    }
                    if (gate == null) verifier(candidate) else {
                        val job = checkNotNull(currentCoroutineContext()[Job])
                        heldVerifications.add(job)
                        if (candidate.id == profiles[1].id) earlierVerifications.add(job)
                        // Supersession cannot turn this into an unexercised cancellation path:
                        // the actual earlier verifier returns after the later target is visible.
                        withContext(NonCancellable) { gate.await(); verifier(candidate) }
                    }
                }
                playback.startQueue(profiles, shuffle = false, autoplay = false)
            }
            waitUntil(8_000L) {
                playback.state.value.songId == profiles[0].id && !playback.state.value.loading &&
                    profiles.indices.all { playback.cachedSongPlaybackSettings(profiles[it]) == settings[it] }
            }
            val outgoing = checkNotNull(playback.adapter.snapshot().publication.committed).owner.ownership
            val selected = androidx.compose.runtime.mutableStateOf(profiles[0])
            compose.setContent {
                StemwiseTheme {
                    PlayerScreen(selected.value, onBack = {}, library = profiles, onSongChanged = { selected.value = it },
                        audioPlayback = playback, separationRepository = application.services.separationRepository,
                        analysisRepository = application.services.analysisRepository,
                        onDeleteSong = { Result.failure(IllegalStateException("Deletion is outside this fixture")) },
                        modifier = Modifier.fillMaxSize())
                }
            }
            assertRenderedSettings(profiles[0], settings[0])
            clickPlayerControl("Next track")
            runBlocking { withTimeout(8_000L) { enteredB.await() } }
            assertRenderedSettings(profiles[1], settings[1])
            assertEquals("B committed while its real source verifier is held", outgoing,
                playback.adapter.snapshot().publication.committed?.owner?.ownership)
            clickPlayerControl("Next track")
            runBlocking { withTimeout(8_000L) { enteredC.await() } }
            assertRenderedSettings(profiles[2], settings[2])
            assertEquals("C committed while its real source verifier is held", outgoing,
                playback.adapter.snapshot().publication.committed?.owner?.ownership)
            releaseB.complete(Unit)
            runBlocking { withTimeout(8_000L) { earlierVerifications.toList().forEach { it.join() } } }
            compose.runOnIdle { }
            assertRenderedSettings(profiles[2], settings[2])
            assertEquals("Late B source work replaced the outgoing owner while C remained held", outgoing,
                playback.adapter.snapshot().publication.committed?.owner?.ownership)
            releaseC.complete(Unit)
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            waitUntil(8_000L) {
                val committed = playback.adapter.snapshot().publication.committed
                playback.state.value.songId == profiles[2].id && !playback.state.value.loading &&
                    committed?.selection?.songId == profiles[2].id &&
                    committed.selection.originalContentSha256 == profiles[2].sourceHash
            }
            assertRenderedSettings(profiles[2], settings[2])
            assertFalse(playback.state.value.playWhenReady)
            profiles.indices.forEach { index -> assertEquals(settings[index], store().read(identities[index], 6_000L)) }
        } finally {
            releaseB.complete(Unit)
            releaseC.complete(Unit)
            onMain { playback.debug.verifyPlaybackSong = verifier }
            compose.mainClock.autoAdvance = true
            runBlocking { withTimeout(8_000L) { heldVerifications.toList().forEach { it.join() } } }
            playback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
            identities.forEach(store()::clear)
        }
    }

    @Test fun fullPlayerTrimCancelSaveReopenAndResetUseTheSameNonDestructiveSource() {
        onMain { assertTrue(playback.selectSong(song, autoplay = false)) }
        waitUntil(8_000L) { playback.state.value.songId == song.id && !playback.state.value.loading &&
            playback.controlLease.value != null }
        compose.setContent {
            StemwiseTheme {
                PlayerScreen(song, onBack = {}, library = listOf(song), audioPlayback = playback,
                    separationRepository = application.services.separationRepository,
                    analysisRepository = application.services.analysisRepository,
                    onDeleteSong = { Result.failure(IllegalStateException("Deletion is outside this fixture")) },
                    modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithContentDescription("Song details", useUnmergedTree = true).assertIsDisplayed()
        // Drive UI transitions explicitly instead of idling until the real song reaches EOF.
        compose.mainClock.autoAdvance = false
        try {
            onMain {
                assertTrue(playback.seekTo(10_000L, checkNotNull(playback.acquireControlLease(song.id))))
                assertTrue(playback.play())
            }
            waitUntil(8_000L) { playback.state.value.playing && playback.state.value.positionMs >= 10_000L }
            val before = checkNotNull(playback.adapter.snapshot().latestRequest)
            val beforePosition = playback.state.value.positionMs
            openTrim()
            exerciseNativeHandleAndRejectNonHandleSeek()
            val range = SongTrimRange(2_317L, 47_891L)
            setBoundary("A", range.startMs)
            assertEquals(range.startMs, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
            setBoundary("B", range.endMs)
            assertEquals(range.endMs - 1_000L, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
            waitUntil(8_000L) {
                (playback.adapter.snapshot().publication.committed?.transport?.presentedPositionMs ?: -1L) >= range.endMs - 1_000L
            }
            assertDraft(range)
            capturePopupIfRequested()
            compose.onNodeWithText("Cancel").performClick()
            awaitClosed()
            waitUntil(8_000L) { playback.state.value.playing && !playback.state.value.loading &&
                playback.adapter.snapshot().publication.committed?.selection?.token != before.selection.token }
            val afterCancel = checkNotNull(playback.adapter.snapshot().latestRequest)
            assertEquals(0L, afterCancel.selection.playbackStartMs)
            assertEquals(song.durationMs, afterCancel.selection.playbackEndMs)
            assertTrue("Cancel did not restore the pre-editor position", playback.state.value.positionMs >= beforePosition)
            assertNull(store().read(identity, song.durationMs)?.trim)
            assertNull(playback.state.value.loopA)
            assertNull(playback.state.value.loopB)

            onMain { assertTrue(playback.pause()) }
            waitUntil(8_000L) { !playback.state.value.playing }
            openTrim()
            assertDraft(SongTrimRange(0L, song.durationMs))
            setBoundary("A", range.startMs)
            setBoundary("B", range.endMs)
            compose.onNodeWithText("Save").performClick()
            awaitClosed()
            waitUntil(8_000L) { playback.state.value.trimStartMs == range.startMs &&
                playback.state.value.durationMs == range.endMs - range.startMs && !playback.state.value.loading &&
                playback.controlLease.value != null }
            assertEquals(range, store().read(identity, song.durationMs)?.trim)
            openTrim()
            assertDraft(range)
            compose.onNodeWithText("Reset").performClick()
            assertDraft(SongTrimRange(0L, song.durationMs))
            compose.onNodeWithText("Save").performClick()
            awaitClosed()
            waitUntil(8_000L) { playback.state.value.trimStartMs == 0L &&
                playback.state.value.durationMs == song.durationMs && !playback.state.value.loading }
            assertNull(store().read(identity, song.durationMs)?.trim)
            assertEquals(song.sourceHash, PlaybackContentFixture.sourceHash(application, checkNotNull(song.sourcePath)))
        } finally {
            onMain { playback.pause() }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun assertRenderedSettings(profile: Song, settings: SongPlaybackSettings) {
        val trim = checkNotNull(settings.trim)
        compose.waitUntil(8_000L) {
            compose.mainClock.advanceTimeByFrame()
            val identity = compose.onAllNodesWithTag("player_identity_${profile.id}", useUnmergedTree = true)
                .fetchSemanticsNodes().singleOrNull()
            val timeline = compose.onAllNodesWithContentDescription("Playback position", useUnmergedTree = true)
                .fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
            identity != null && identity.boundsInRoot.width > 0f && identity.boundsInRoot.height > 0f &&
                timeline?.range?.endInclusive == (trim.endMs - trim.startMs).toFloat()
        }
        compose.onNodeWithTag("player_identity_${profile.id}", useUnmergedTree = true).assertIsDisplayed()
        clickPlayerControl(PLAYER_DETAIL_TUNE)
        awaitRenderedProgress("Key adjustment", settings.semitones.toFloat())
        clickPlayerControl(PLAYER_DETAIL_METRONOME)
        awaitRenderedProgress("Tempo adjustment", settings.tempo)
    }

    private fun clickPlayerControl(description: String) {
        compose.waitUntil(8_000L) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithContentDescription(description, useUnmergedTree = true)
                .fetchSemanticsNodes().singleOrNull()?.let { node ->
                    node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f &&
                        node.config.contains(SemanticsActions.OnClick) && !node.config.contains(SemanticsProperties.Disabled)
                } == true
        }
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(500L)
    }

    private fun awaitRenderedProgress(description: String, expected: Float) {
        compose.waitUntil(8_000L) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes()
                .singleOrNull()?.config?.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == expected
        }
        assertEquals(expected, compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
    }

    private fun openTrim() {
        val expectedDuration = playback.state.value.durationMs.toFloat()
        // Playback commitment can precede the new native geometry and its Compose projection.
        // Wait for the visible menu and the same owner's applied timeline before activating it.
        try {
            compose.waitUntil(8_000L) {
                compose.mainClock.advanceTimeByFrame()
                val details = compose.onAllNodesWithContentDescription("Song details", useUnmergedTree = true)
                    .fetchSemanticsNodes().singleOrNull()
                val timeline = compose.onAllNodesWithContentDescription("Playback position", useUnmergedTree = true)
                    .fetchSemanticsNodes().singleOrNull()
                details != null && details.boundsInRoot.width > 0f && details.boundsInRoot.height > 0f &&
                    details.config.contains(SemanticsActions.OnClick) && !details.config.contains(SemanticsProperties.Disabled) &&
                    timeline?.config?.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range?.endInclusive == expectedDuration
            }
        } catch (failure: Exception) {
            throw AssertionError("The committed playback owner did not publish the visible full-player menu and " +
                "its applied ${expectedDuration.toLong()} ms timeline within 8 seconds.", failure)
        }
        compose.onNodeWithContentDescription("Song details", useUnmergedTree = true)
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Trim song").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(8_000L) {
            compose.mainClock.advanceTimeByFrame()
            runCatching {
                compose.onNodeWithContentDescription("Trim point A").assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
            }.isSuccess
        }
        compose.onNodeWithContentDescription("Trim point A").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
        val sheet = compose.onNodeWithTag("song_trim_sheet").fetchSemanticsNode().boundsInRoot
        val windowHeight = compose.activity.windowManager.currentWindowMetrics.bounds.height()
        assertTrue("Trim must leave the upper player visible", sheet.top >= windowHeight * .5f)
        assertTrue("Trim must be anchored at the bottom, not centered", sheet.bottom >= windowHeight * .8f)
    }

    private fun awaitClosed() {
        compose.waitUntil(8_000L) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithContentDescription("Trim point A").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun setBoundary(label: String, value: Long) {
        compose.onNodeWithContentDescription("Trim point $label")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value.toFloat())) }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun assertDraft(range: SongTrimRange) {
        compose.mainClock.advanceTimeByFrame()
        for ((label, value) in listOf("A" to range.startMs, "B" to range.endMs)) {
            val info = compose.onNodeWithContentDescription("Trim point $label").fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals("Trim $label lost free millisecond placement", value.toFloat(), info.current, 0f)
        }
    }

    private fun exerciseNativeHandleAndRejectNonHandleSeek() {
        onMain {
            val surfaces = WindowInspector.getGlobalWindowViews().flatMap(::playerSurfaces)
            assertTrue("Both production player and popup native seeker must be mounted", surfaces.size >= 2)
            val surface = surfaces.minBy { it.height }
            assertTrue("Popup reused a full-screen surface", surface.height < compose.activity.window.decorView.height / 2)
            val density = surface.resources.displayMetrics.density
            val step = (4f * density).toInt().coerceAtLeast(1)
            val point = (0 until surface.height step step).asSequence().flatMap { y ->
                (0 until surface.width step step).asSequence().map { x -> x.toFloat() to y.toFloat() }
            }.first { (x, y) -> surface.loopBoundaryGestureTargetForQualification(x, y) == FullPlayerLoopBoundary.START }
            val downTime = SystemClock.uptimeMillis()
            fun dispatch(action: Int, x: Float, y: Float, elapsed: Long): Boolean {
                val event = MotionEvent.obtain(downTime, downTime + elapsed, action, x, y, 0)
                return try { surface.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            assertTrue(dispatch(MotionEvent.ACTION_DOWN, point.first, point.second, 0L))
            assertTrue(dispatch(MotionEvent.ACTION_MOVE, point.first + 3f * density, point.second - 60f * density, 16L))
            assertTrue(dispatch(MotionEvent.ACTION_UP, point.first + 4f * density, point.second - 60f * density, 32L))
            // The full-source popup must never route a rail tap into trim-relative playback seek.
            assertFalse(dispatch(MotionEvent.ACTION_DOWN, surface.width * .5f, 2f * density, 48L))
            dispatch(MotionEvent.ACTION_UP, surface.width * .5f, 2f * density, 64L)
        }
        compose.mainClock.advanceTimeBy(400)
    }

    private fun playerSurfaces(view: View): List<PlayerSurfaceView> = buildList {
        if (view is PlayerSurfaceView) add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(playerSurfaces(view.getChildAt(it))) }
    }

    private fun store() = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))

    private fun capturePopupIfRequested() {
        if (InstrumentationRegistry.getArguments().getString("stemwise.trimPopupScreenshot") != "true") return
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(application.getExternalFilesDir(null), "${song.id}-popup.png")
        val pending = File(file.parentFile, "${file.name}.tmp")
        try {
            pending.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(pending.renameTo(file))
            instrumentation.sendStatus(0, Bundle().apply { putString("stemwise.trimPopupScreenshot", file.absolutePath) })
        } finally { bitmap.recycle(); pending.delete() }
    }
}
