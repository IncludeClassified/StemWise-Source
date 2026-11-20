package app.stemwise.ui

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.analysis.effectiveGridBpm
import app.stemwise.analysis.metronomeAvailableFor
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.DisposablePlaybackTargetRule
import app.stemwise.playback.PlaybackContentFixture
import app.stemwise.playback.PlaybackControlLease
import app.stemwise.playback.admitCurrentPlaybackLibrarySnapshot
import app.stemwise.playback.instrumentationIsolationDiagnostics
import app.stemwise.playback.resetForInstrumentationIsolation
import app.stemwise.ui.theme.StemwiseTheme
import app.stemwise.qa.GoldenQaDocumentsProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.pow
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real tuning panels send their accessible controls to the current facade/native lease. */
@app.stemwise.qualification.ManagedAudioTest
class TuningSpeedAvailabilityInstrumentationTest {
    @get:Rule(order = 0) val target: org.junit.rules.TestRule = DisposablePlaybackTargetRule()
    @get:Rule(order = 1) val compose = createStemwiseComposeRule()
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StemwiseApplication
    private val playback get() = application.services.audioPlayback
    private lateinit var song: Song
    private lateinit var identity: SongPlaybackSettingsIdentity
    private val previewCommands = AtomicInteger()
    private val tuningCommits = AtomicInteger()
    private val beats = mutableStateOf<List<Long>?>(null)

    @Before fun preparePlayback() = runBlocking {
        application.admitCurrentPlaybackLibrarySnapshot()
        playback.resetForInstrumentationIsolation(InstrumentationRegistry.getInstrumentation())
        val roots = application.services.libraryRootManager
        if (!roots.state.value.ready) roots.initialize(application.services.preferences)
        if (!roots.state.value.ready) {
            val tree = GoldenQaDocumentsProvider.treeUri
            application.grantUriPermission(application.packageName, tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
        }
        val original = File(application.cacheDir, "tuning-speed-ui/original.opus").apply {
            parentFile!!.mkdirs()
            PlaybackContentFixture.own(application, this)
            writeBytes(app.stemwise.enrichment.GoldenSongMediaFixture.originalOpus(application, 60_000L))
        }
        song = Song(id = "tuning-speed-ui-${UUID.randomUUID()}", videoId = "TuneSpeed01", title = "Tuning fixture",
            artist = "StemWise", durationMs = 60_000, state = SongState.READY, accent = Color.Cyan,
            artworkGlyph = "TS", key = "C Major", bpm = 96f,
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceHash = PlaybackContentFixture.sourceHash(original), sourceCodec = "opus",
            sourceContainer = "ogg", audioRevision = "tuning-speed-audio", mediaRevision = "tuning-speed-enrichment")
        val root = checkNotNull(roots.captureHandle())
        identity = SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), song.id, checkNotNull(song.sourceHash))
        compose.runOnIdle { assertNotNull(playback.startQueue(listOf(song), shuffle = false, autoplay = true)) }
        awaitPlayback("startup song=${song.id}", 8_000L) {
            playback.acquireControlLease(song.id) != null && playback.state.value.playing
        }
    }

    @After fun releasePlayback() {
        if (::song.isInitialized) runBlocking { withTimeout(5_000L) { playback.savedSongTrim(song) } }
        playback.resetForInstrumentationIsolation(InstrumentationRegistry.getInstrumentation())
        if (::identity.isInitialized) store().clear(identity)
        PlaybackContentFixture.clear(application)
    }

    @Test fun accessibleTuningAndResetCommitEachAcceptedChange() {
        mount()
        setProgress("Key adjustment", 5f)
        awaitSaved(1f, 5)
        setProgress("Tempo adjustment", 1.5f)
        awaitSaved(1.5f, 5)
        compose.onNodeWithContentDescription("Reset tempo").performClick()
        awaitSaved(1f, 5)
        compose.onNodeWithContentDescription("Reset key").performClick()
        awaitSaved(1f, 0)
        assertEquals(4, tuningCommits.get())
    }

    @Test fun pointerMovesPreviewWithoutDurableWritesUntilExactGestureFinishes() {
        mount()
        val speed = compose.onNodeWithContentDescription("Tempo adjustment")
        speed.performTouchInput {
            down(center)
            moveTo(center.copy(x = width * .7f), delayMillis = 16L)
            moveTo(center.copy(x = width * .8f), delayMillis = 16L)
        }
        try {
            compose.waitUntil(5_000L) { previewCommands.get() > 0 && playback.state.value.tempo != 1f }
            assertEquals(0, tuningCommits.get())
            assertEquals(1f, store().read(identity, song.durationMs)?.tempo ?: 1f, 0f)
        } finally { speed.performTouchInput { up() } }
        compose.waitUntil(5_000L) { tuningCommits.get() == 1 }
        awaitSaved(playback.state.value.tempo, playback.state.value.semitoneShift)
    }

    @Test fun absentZeroAndOneBeatTimingPermitEverySpeedAndResetWithoutChangingPitch() {
        mount()
        setProgress("Key adjustment", 5f)
        for (grid in listOf(null, emptyList(), listOf(0L))) {
            compose.runOnIdle { beats.value = grid }
            compose.onNodeWithContentDescription("Metronome BPM").assertDoesNotExist()
            for (speed in listOf(.5f, 1f, 2f)) {
                setProgress("Tempo adjustment", speed)
                awaitApplied(speed, 5)
            }
            compose.onNodeWithContentDescription("Reset tempo").performClick()
            awaitApplied(1f, 5)
            compose.onNodeWithText("1×").assertDoesNotExist()
        }
    }

    @Test fun usableGridBpmControlsNativeTempoAndPreservesPitchWithoutADuplicateSpeedSlider() {
        beats.value = listOf(0, 500, 1_000, 1_500).map(Int::toLong)
        mount()
        setProgress("Key adjustment", 5f)
        compose.onNodeWithContentDescription("Tempo adjustment").assertDoesNotExist()
        setProgress("Metronome BPM", 240f)
        awaitApplied(2f, 5)
        assertBpm(240)
        setProgress("Metronome BPM", 180f)
        awaitApplied(1.5f, 5)
        compose.onNodeWithText("1.5×").assertDoesNotExist()
        assertBpm(180)
        setProgress("Metronome BPM", 120f)
        awaitApplied(1f, 5)
        assertBpm(120)
    }

    @Test fun hydrationAndSameAudioRefreshPreserveMetronomeWhileExplicitOffStillWins() {
        val grid = listOf(app.stemwise.analysis.BeatEvent(0L, true),
            app.stemwise.analysis.BeatEvent(500L, false), app.stemwise.analysis.BeatEvent(1_000L, false))
        val lease = checkNotNull(playback.acquireControlLease(song.id))
        assertTrue(playback.setMetronome(true, 120f, true, grid, controlLease = lease))
        val loaded = mutableStateOf(false)
        val recreation = mutableStateOf(0)
        compose.setContent {
            androidx.compose.runtime.key(recreation.value) {
                PlayerMetronomeProfile(playback, true, lease, loaded.value,
                    metronomeAvailable = loaded.value,
                    effectiveMetronomeBpm = 120f, metronomeSubdivision = 1,
                    metronomeNumerator = 4, metronomeDenominator = 4,
                    beatEvents = if (loaded.value) grid else emptyList(), meterSegments = emptyList())
            }
        }
        compose.runOnIdle {
            assertTrue("Hydrating entry erased enabled engine intent", playback.state.value.metronomeEnabled)
            assertTrue(playback.state.value.metronomeSongSynced)
            loaded.value = true
        }
        compose.runOnIdle {
            assertTrue(playback.state.value.metronomeEnabled)
            loaded.value = false
            recreation.value++
        }
        compose.runOnIdle {
            assertTrue("Recreated same-audio profile disabled the metronome", playback.state.value.metronomeEnabled)
            assertTrue(playback.setMetronome(false, 120f, true, grid, controlLease = lease))
            loaded.value = true
        }
        compose.runOnIdle {
            assertFalse("Late hydration overrode explicit Off", playback.state.value.metronomeEnabled)
        }
    }

    private fun mount() {
        compose.setContent {
            val state by playback.state.collectAsState()
            val lease by playback.controlLease.collectAsState()
            val grid = beats.value.orEmpty()
            val gridBpm = effectiveGridBpm(song.bpm, grid)
            StemwiseTheme {
                Column {
                    KeyPanel(song, state.semitoneShift,
                        onPitch = { pitch -> command(state.tempo, pitch, lease, persist = false) },
                        onValueChangeFinished = { commit(lease) })
                    if (metronomeAvailableFor(grid.size)) MetronomePanel(
                        enabled = false, bpm = gridBpm * state.tempo, bpmRange = reachableMetronomeBpmRange(gridBpm),
                        subdivision = 1, numerator = 4, denominator = 4, volume = .6f,
                        onToggle = { true }, onBpm = { command(it / gridBpm, state.semitoneShift, lease) },
                        onSubdivision = { true }, onVolume = { true })
                    else SpeedPanel(state.tempo,
                        onTempo = { speed -> command(speed, state.semitoneShift, lease, persist = false) },
                        onValueChangeFinished = { commit(lease) })
                }
            }
        }
    }
    private fun command(speed: Float, pitch: Int, lease: PlaybackControlLease?, persist: Boolean = true): Boolean = lease?.let {
        playback.setTempoAndPitch(speed, pitch, it, persist = persist).also { accepted ->
            if (accepted && !persist) previewCommands.incrementAndGet()
        }
    } == true
    private fun commit(lease: PlaybackControlLease?): Boolean = playback.commitTuningSettings(lease).also {
        if (it) tuningCommits.incrementAndGet()
    }
    private fun store() = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
    private fun awaitSaved(speed: Float, pitch: Int) {
        runBlocking { withTimeout(5_000L) { playback.savedSongTrim(song) } }
        val saved = checkNotNull(store().read(identity, song.durationMs))
        assertEquals(speed, saved.tempo, 0f)
        assertEquals(pitch, saved.semitones)
    }

    private fun assertBpm(bpm: Int) {
        compose.onNodeWithContentDescription("Metronome BPM").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$bpm BPM"))
        compose.onNodeWithText(bpm.toString()).assertExists()
    }
    private fun setProgress(description: String, value: Float) {
        compose.onNodeWithContentDescription(description).performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue("$description did not admit $value", action(value))
        }
    }
    private fun awaitApplied(speed: Float, pitch: Int) {
        awaitPlayback("tuning speed=$speed pitch=$pitch beatCount=${beats.value?.size}", 5_000L) {
            val actual = playback.graphDiagnostics()
            abs((actual.appliedTempo ?: Float.NaN) - speed) < .001f &&
                actual.requestedSemitoneShift == pitch &&
                abs((actual.appliedPitchRatio ?: Float.NaN) - 2.0.pow(pitch / 12.0).toFloat()) < .001f
        }
        assertEquals(pitch, playback.state.value.semitoneShift)
    }

    private fun awaitPlayback(description: String, timeoutMillis: Long, condition: () -> Boolean) {
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        try {
            compose.waitUntil(timeoutMillis, condition)
        } catch (failure: ComposeTimeoutException) {
            val diagnostics = runCatching {
                val snapshotAtNanos = SystemClock.elapsedRealtimeNanos()
                """
                    |$description startedAtNanos=$startedAtNanos snapshotAtNanos=$snapshotAtNanos
                    |${playback.instrumentationIsolationDiagnostics()}
                    |events=${playback.playbackEventHistoryForTest().takeLast(24)}
                """.trimMargin()
            }
            failure.addSuppressed(AssertionError(diagnostics.getOrElse { "Diagnostic capture failed: $it" }))
            throw failure
        }
    }
}
