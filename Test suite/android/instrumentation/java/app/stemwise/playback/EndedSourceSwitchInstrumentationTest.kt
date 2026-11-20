package app.stemwise.playback

import android.content.Intent
import android.provider.DocumentsContract
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.stemwise.StemwiseApplication
import app.stemwise.data.StemAssetEntity
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.engine.NativeRuntimeSourceMode
import app.stemwise.qa.GoldenQaDocumentsProvider
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class EndedSourceSwitchInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application =
        instrumentation.targetContext.applicationContext as StemwiseApplication
    private val playback
        get() = application.services.audioPlayback
    private val ownedSettingsSongs = linkedMapOf<SongPlaybackSettingsIdentity, Song>()

    @get:Rule
    val disposablePlaybackTarget: org.junit.rules.TestRule = DisposablePlaybackTargetRule()

    @Before
    fun isolatePlaybackBeforeTest() {
        isolateAndCleanOwnedState()
    }

    @After
    fun isolatePlaybackAfterTest() = isolateAndCleanOwnedState()

    @Test
    fun samePackageSourceRoundTripsAndDuplicateActivationRetainVisibleAndNativeMix() {
        val (song, stems) = sourceSwitchFixture("retained-package-mix", 30_000L)
        isolateFixtureSettings(song)
        onMain { playback.selectSong(song, autoplay = true) }
        waitUntil(8_000L) { playback.state.value.songId == song.id && playback.state.value.playing }
        var lease: PlaybackControlLease? = null
        onMain { lease = playback.acquireControlLease(song.id) }
        val controlLease = requireNotNull(lease)
        activateFixtureStems(stems, controlLease)
        val expected = mapOf(
            "vocals" to StemMixState(volume = .32f, muted = false, solo = true),
            "bass" to StemMixState(volume = .64f, muted = true, solo = false),
            "remaining" to StemMixState(volume = .46f, muted = false, solo = false)
        )
        onMain {
            expected.forEach { (id, mix) -> playback.commitStemMix(id, mix.volume, mix.muted,
                mix.solo, controlLease = controlLease) }
        }
        fun assertAppliedRetainedMix() {
            val requested = requireNotNull(playback.latestRequestedControlsForTest())
            expected.forEach { (id, mix) ->
                val control = requireNotNull(requested.stemControls[id])
                assertEquals(mix.volume, control.gain, 0f)
                assertEquals(mix.muted, control.muted)
                assertEquals(mix.solo, control.solo)
            }
            waitUntil(4_000L) {
                val graph = playback.graphDiagnostics()
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    graph.appliedControlRevision > 0L &&
                    graph.appliedStemGains == mapOf("vocals" to .32f, "bass" to 0f, "remaining" to 0f)
            }
            assertEquals(expected, playback.state.value.stemMix)
            val native = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
            val acknowledged = requireNotNull(native.runtime.audibleControl)
            assertEquals(NativeRuntimeSourceMode.STEMS, acknowledged.actualSourceMode)
            assertEquals(playback.graphDiagnostics().appliedControlRevision, acknowledged.controlRevision)
        }
        assertAppliedRetainedMix()
        repeat(2) {
            onMain { playback.switchToOriginal(controlLease) }
            waitUntil(4_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Original }
            activateFixtureStems(stems, controlLease)
            assertAppliedRetainedMix()
            activateFixtureStems(stems, controlLease)
            assertAppliedRetainedMix()
        }
    }

    @Test
    fun pausedNearEndAndSub250msSourceSwitchesRetainCursorAndPlayIntent() {
        for (durationMs in listOf(1_600L, 200L)) {
            val targetMs = durationMs - 100L
            val (song, stems) = sourceSwitchFixture("paused-near-end-$durationMs", durationMs)
            onMain { playback.selectSong(song, autoplay = false, positionMs = targetMs) }
            waitUntil(8_000L) { playback.state.value.songId == song.id && !playback.state.value.loading }
            var lease: PlaybackControlLease? = null
            onMain { lease = playback.acquireControlLease(song.id) }
            val controlLease = requireNotNull(lease)
            fun assertPreserved() {
                val state = playback.state.value
                assertTrue("Paused source switch changed exact cursor: $state",
                    state.positionMs in (targetMs - 1L)..(targetMs + 1L))
                assertFalse("Paused source switch became ended: $state", state.ended)
                assertFalse(state.playing)
                assertFalse(state.playWhenReady)
            }
            assertPreserved()
            activateFixtureStems(stems, controlLease)
            waitUntil(8_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
            assertPreserved()
            onMain { playback.switchToOriginal(controlLease) }
            waitUntil(4_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Original }
            assertPreserved()
            onMain { playback.unload(song.id) }
        }
    }

    private fun sourceSwitchFixture(id: String, durationMs: Long): Pair<Song, List<StemAssetEntity>> {
        val directory = File(application.cacheDir, id).apply { mkdirs() }
        val original = File(directory, "original.wav")
        val vocals = File(directory, "vocals.wav")
        val bass = File(directory, "bass.wav")
        writeSineWave(original, durationMs, 220.0)
        writeSineWave(vocals, durationMs, 330.0)
        writeSineWave(bass, durationMs, 440.0)
        val song = Song(id = id, videoId = id, title = id, artist = "Stemwise",
            durationMs = durationMs, state = SongState.READY, accent = Color.Cyan,
            hasStems = true, artworkGlyph = "S", sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceCodec = "pcm_s16le", sourceContainer = "wav", audioRevision = id)
        return song to listOf(stemAsset("vocals", vocals, durationMs, "$id-set"),
            stemAsset("bass", bass, durationMs, "$id-set"), remainingAsset(bass, durationMs, 440.0, "$id-set"))
    }

    private fun activateFixtureStems(stems: List<StemAssetEntity>, lease: PlaybackControlLease) {
        val outcome = runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
            playback.activateStems(stems, lease)
        } } }
        assertTrue("Source-switch fixture activation failed: $outcome", outcome.isSuccess)
    }

    @Test
    fun naturalOpusEndAdvancesToStemsAndReplaysAudiblePcm() {
        val directory = File(application.cacheDir, "natural-opus-end").apply { mkdirs() }
        val trace = StringBuilder()
        fun capture(label: String) {
            trace.appendLine("$label public=${playback.state.value}")
            trace.appendLine("facade=${playback.selectionPressureDiagnosticsForTest()}")
            trace.appendLine("session=${playback.mediaSessionLifecycleDiagnosticsForTest()}")
            trace.appendLine("native=${playback.nativeDiagnosticsSnapshotForTest()}")
        }
        fun awaitState(label: String, timeoutMs: Long = 8_000L, predicate: () -> Boolean) {
            capture("before $label")
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(20L)
            }
            capture("after $label")
            assertTrue("Timed out: $label", predicate())
        }
        fun fixture(template: String, name: String): String {
            val file = File(directory, "$template-$name")
            application.assets.open("demo_library/$template/$name").use { input ->
                file.outputStream().use(input::copyTo)
            }
            return PlaybackContentFixture.publish(application, file)
        }
        fun audible(songId: String, mode: NativeRuntimeSourceMode, afterSequence: Long): Boolean {
            val native = playback.nativeDiagnosticsSnapshotForTest() ?: return false
            val owner = native.runtime.ownership.committed ?: return false
            val control = native.runtime.audibleControl ?: return false
            val output = native.output ?: return false
            return playback.state.value.songId == songId && playback.state.value.playing &&
                playback.graphMediaId() == songId &&
                control.ownership == owner && control.actualSourceMode == mode &&
                output.pcmLevelCallbackSequence >= control.callbackSequence &&
                output.pcmLevelSequence > afterSequence && output.pcmLevelFrames > 0L &&
                output.pcmLevelSelectionToken == owner.selectionToken &&
                output.pcmLevelGeneration == owner.generation && output.pcmLevelPeak > 0.001f
        }
        val firstLocation = fixture("DEMOUI00001", "original.opus")
        val nextLocation = fixture("DEMOUI00002", "original.opus")
        val lastLocation = fixture("DEMOUI00003", "original.opus")
        val first = Song(
            id = "natural-opus-first", videoId = "natural-opus-first",
            title = "Natural Opus first", artist = "Stemwise", durationMs = 12_000L,
            state = SongState.READY, accent = Color.Cyan, artworkGlyph = "1",
            sourcePath = firstLocation,
            sourceHash = PlaybackContentFixture.sourceHash(application, firstLocation),
            sourceCodec = "opus", sourceContainer = "ogg", audioRevision = "natural-opus-first"
        )
        val next = first.copy(
            id = "natural-opus-next", videoId = "natural-opus-next", hasStems = true,
            sourcePath = nextLocation,
            sourceHash = PlaybackContentFixture.sourceHash(application, nextLocation),
            audioRevision = "natural-opus-next"
        )
        val last = next.copy(
            id = "natural-opus-last", videoId = "natural-opus-last",
            sourcePath = lastLocation,
            sourceHash = PlaybackContentFixture.sourceHash(application, lastLocation),
            audioRevision = "natural-opus-last"
        )
        fun stems(template: String): List<StemAssetEntity> {
            val manifest = org.json.JSONObject(application.assets.open("demo_library/$template/sources.json")
                .bufferedReader().use { it.readText() })
            val lanes = manifest.getJSONArray("lanes")
            return (0 until lanes.length()).map { index ->
                val lane = lanes.getJSONObject(index)
                val role = lane.getString("sourceId")
                val name = lane.getString("fileName")
                StemAssetEntity(id = "$template-set:$role", separationSetId = "$template-set",
                    providerLabel = lane.getString("label"), normalizedCapability = role,
                    localPath = fixture(template, name), sha256 = lane.getString("sha256"),
                    durationMs = 12_000L, codec = if (role == "remaining") "wavpack" else "flac",
                    sampleRateHz = 48_000, channelCount = 2, validated = true)
            }
        }
        val nextStems = stems("DEMOUI00002")
        val lastStems = stems("DEMOUI00003")
        fun activatePausedStems(songId: String, assets: List<StemAssetEntity>) {
            var lease: PlaybackControlLease? = null
            onMain {
                playback.pause()
                lease = playback.acquireControlLease(songId)
            }
            val activation = runBlocking {
                withTimeout(8_000L) {
                    withContext(Dispatchers.Main) { playback.activateStems(assets, requireNotNull(lease)) }
                }
            }
            assertTrue("Opus Stems activation failed: $activation", activation.isSuccess)
        }
        try {
            onMain {
                playback.setRepeatMode("Off")
                playback.startQueue(listOf(first, next, last), shuffle = false, autoplay = false)
            }
            awaitState("prepared successor") { next.id in playback.preparedQueueMediaIds() }
            onMain { playback.play() }
            awaitState("first Original PCM and advancing position") {
                audible(first.id, NativeRuntimeSourceMode.ORIGINAL, 0L) &&
                    playback.state.value.positionMs >= 250L
            }
            // Do not seek to duration: that bypasses decoder EOS and the native audible-end path.
            awaitState("natural Original EOF and automatic successor PCM", 20_000L) {
                audible(next.id, NativeRuntimeSourceMode.ORIGINAL, 0L) &&
                    playback.sessionMediaItem()?.mediaId == next.id
            }
            val beforeStems = playback.nativeDiagnosticsSnapshotForTest()?.output?.pcmLevelSequence ?: 0L
            activatePausedStems(next.id, nextStems)
            onMain { playback.play() }
            awaitState("successor Stems PCM") {
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.state.value.positionMs >= 250L &&
                    audible(next.id, NativeRuntimeSourceMode.STEMS, beforeStems)
            }
            awaitState("natural Stems EOF and automatic Original successor", 20_000L) {
                playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL &&
                    audible(last.id, NativeRuntimeSourceMode.ORIGINAL, beforeStems) &&
                    playback.sessionMediaItem()?.mediaId == last.id
            }
            awaitState("Original after Stems advances beyond one second") {
                audible(last.id, NativeRuntimeSourceMode.ORIGINAL, beforeStems) &&
                    playback.state.value.positionMs >= 1_000L
            }
            awaitState("natural Original EOF after Stems successor", 20_000L) {
                val state = playback.state.value
                state.songId == last.id && state.ended && !state.playing && !state.playWhenReady
            }
            val beforeOriginalReplay = playback.nativeDiagnosticsSnapshotForTest()?.output?.pcmLevelSequence ?: 0L
            onMain { playback.play() }
            awaitState("Original replay PCM after Stems successor") {
                !playback.state.value.ended && playback.state.value.positionMs in 250L..3_000L &&
                    audible(last.id, NativeRuntimeSourceMode.ORIGINAL, beforeOriginalReplay)
            }
            val beforeFinalStems = playback.nativeDiagnosticsSnapshotForTest()?.output?.pcmLevelSequence ?: 0L
            activatePausedStems(last.id, lastStems)
            onMain { playback.play() }
            awaitState("final cold Stems PCM") {
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    audible(last.id, NativeRuntimeSourceMode.STEMS, beforeFinalStems)
            }
            awaitState("natural Stems EOF", 20_000L) {
                val state = playback.state.value
                state.songId == last.id && state.ended && !state.playing && !state.playWhenReady
            }
            val beforeReplay = playback.nativeDiagnosticsSnapshotForTest()?.output?.pcmLevelSequence ?: 0L
            onMain { playback.play() }
            awaitState("Stems replay PCM") {
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    !playback.state.value.ended && playback.state.value.positionMs in 1L..3_000L &&
                    audible(last.id, NativeRuntimeSourceMode.STEMS, beforeReplay)
            }
        } catch (failure: Throwable) {
            capture("FAILURE")
            val report = File(application.filesDir, "natural-opus-end-failure.txt")
            report.writeText(trace.toString())
            trace.lines().forEach { line ->
                line.chunked(3_000).forEach { android.util.Log.e("NaturalOpusEnd", it) }
            }
            throw AssertionError("Natural Opus EOF regression failed; diagnostics=${report.absolutePath}", failure)
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun automaticNextAdoptsPreparedOwnerAndKeepsStemsOnThatSong() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
            playback.state.value.songId?.let(playback::unload)
        }
        val directory = File(application.cacheDir, "automatic-next-stems").apply { mkdirs() }
        val firstOriginal = File(directory, "first.wav")
        val nextOriginal = File(directory, "next.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        writeSineWave(firstOriginal, 700L, 220.0)
        writeSineWave(nextOriginal, 8_000L, 240.0)
        writeSineWave(stemOne, 8_000L, 330.0)
        writeSineWave(stemTwo, 8_000L, 440.0)
        val first = Song(
            id = "automatic-next-first",
            videoId = "automatic-next-first",
            title = "Automatic next first",
            artist = "Stemwise",
            durationMs = 700L,
            state = SongState.READY,
            accent = Color.Cyan,
            artworkGlyph = "1",
            sourceHash = PlaybackContentFixture.sourceHash(firstOriginal),
            sourcePath = PlaybackContentFixture.publish(application, firstOriginal),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav",
            audioRevision = "automatic-next-first"
        )
        val next = first.copy(
            id = "automatic-next-second",
            videoId = "automatic-next-second",
            title = "Automatic next second",
            durationMs = 8_000L,
            hasStems = true,
            artworkGlyph = "2",
            sourceHash = PlaybackContentFixture.sourceHash(nextOriginal),
            sourcePath = PlaybackContentFixture.publish(application, nextOriginal),
            audioRevision = "automatic-next-second"
        )
        val stems = listOf(
            stemAsset("bass", stemOne, 8_000L, "automatic-next-set"),
            stemAsset("drums", stemTwo, 8_000L, "automatic-next-set"),
            stemAsset("guitar", stemOne, 8_000L, "automatic-next-set"),
            remainingAsset(stemTwo, 8_000L, 440.0, "automatic-next-set"),
            stemAsset("piano", stemOne, 8_000L, "automatic-next-set"),
            stemAsset("vocals", stemTwo, 8_000L, "automatic-next-set")
        )

        try {
            onMain {
                // Repeat mode is a durable user preference and intentionally survives unload. This
                // automatic-next scenario therefore declares its own non-repeating precondition
                // instead of depending on JUnit method order or another test's cleanup policy.
                playback.setRepeatMode("Off")
                playback.startQueue(listOf(first, next), shuffle = false, autoplay = false)
            }
            waitUntil(8_000L) {
                playback.state.value.songId == first.id &&
                    !playback.state.value.loading &&
                    playback.graphMediaId() == first.id
            }
            waitUntil(8_000L) { next.id in playback.preparedQueueMediaIds() }
            onMain { playback.play() }
            waitUntil(8_000L) {
                playback.state.value.songId == next.id &&
                    playback.state.value.playing &&
                    playback.sessionMediaItem()?.mediaId == next.id &&
                    playback.graphMediaId() == next.id
            }
            assertTrue(playback.sessionMediaItem()?.mediaId == next.id)
            assertTrue(playback.graphMediaId() == next.id)
            var retainedLease: PlaybackControlLease? = null
            onMain { retainedLease = playback.acquireControlLease(next.id) }
            val controlLease = requireNotNull(retainedLease)

            val activation = runBlocking {
                withTimeout(8_000L) {
                    withContext(Dispatchers.Main) {
                        playback.activateStems(stems, controlLease)
                    }
                }
            }
            assertTrue("Next-song stem activation failed: $activation", activation.isSuccess)
            waitUntil(8_000L) {
                playback.state.value.songId == next.id &&
                    playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.state.value.playing
            }
            assertTrue(playback.sessionMediaItem()?.mediaId == next.id)
            assertTrue(playback.graphMediaId() == next.id)
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun automaticSuccessorThenOwnerlessEarlierSongReselectionRebindsOnePlayAction() {
        val providerTree = GoldenQaDocumentsProvider.treeUri
        val providerFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        application.grantUriPermission(application.packageName, providerTree, providerFlags)
        val providerRoot = DocumentsContract.buildDocumentUriUsingTree(
            providerTree,
            DocumentsContract.getTreeDocumentId(providerTree)
        )
        val providerDirectoryName = "successor-ownerless-rebind-${System.nanoTime()}"
        val directory = File(application.cacheDir, providerDirectoryName).apply { check(mkdirs()) }
        val firstOriginal = File(directory, "first.wav")
        val nextOriginal = File(directory, "next.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        writeSineWave(firstOriginal, 1_200L, 220.0)
        writeSineWave(nextOriginal, 5_000L, 240.0)
        writeSineWave(stemOne, 1_200L, 330.0)
        writeSineWave(stemTwo, 1_200L, 440.0)
        val providerDirectory = checkNotNull(DocumentsContract.createDocument(
            application.contentResolver,
            providerRoot,
            DocumentsContract.Document.MIME_TYPE_DIR,
            providerDirectoryName
        ))
        val firstOriginalUri = checkNotNull(DocumentsContract.createDocument(
            application.contentResolver,
            providerDirectory,
            "audio/wav",
            "first.wav"
        ))
        val nextOriginalUri = checkNotNull(DocumentsContract.createDocument(
            application.contentResolver,
            providerDirectory,
            "audio/wav",
            "next.wav"
        ))
        application.contentResolver.openOutputStream(firstOriginalUri, "w").use { output ->
            checkNotNull(output)
            firstOriginal.inputStream().use { it.copyTo(output) }
        }
        application.contentResolver.openOutputStream(nextOriginalUri, "w").use { output ->
            checkNotNull(output)
            nextOriginal.inputStream().use { it.copyTo(output) }
        }
        val first = Song(
            id = "ownerless-rebind-first",
            videoId = "ownerless-rebind-first",
            title = "Ownerless rebind first",
            artist = "Stemwise",
            durationMs = 1_200L,
            state = SongState.READY,
            accent = Color.Cyan,
            artworkGlyph = "1",
            hasStems = true,
            sourceHash = PlaybackContentFixture.sourceHash(application, firstOriginalUri.toString()),
            sourcePath = PlaybackContentFixture.register(application, firstOriginalUri.toString()),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav",
            audioRevision = "ownerless-rebind-first"
        )
        isolateFixtureSettings(first)
        val next = first.copy(
            id = "ownerless-rebind-next",
            videoId = "ownerless-rebind-next",
            title = "Ownerless rebind next",
            durationMs = 5_000L,
            hasStems = false,
            sourceHash = PlaybackContentFixture.sourceHash(application, nextOriginalUri.toString()),
            sourcePath = PlaybackContentFixture.register(application, nextOriginalUri.toString()),
            audioRevision = "ownerless-rebind-next"
        )
        val stems = listOf(
            stemAsset("bass", stemOne, 1_200L, "ownerless-rebind-set"),
            stemAsset("drums", stemTwo, 1_200L, "ownerless-rebind-set"),
            stemAsset("guitar", stemOne, 1_200L, "ownerless-rebind-set"),
            remainingAsset(stemTwo, 1_200L, 440.0, "ownerless-rebind-set"),
            stemAsset("piano", stemOne, 1_200L, "ownerless-rebind-set"),
            stemAsset("vocals", stemTwo, 1_200L, "ownerless-rebind-set")
        )

        try {
            onMain { playback.startQueue(listOf(first, next), shuffle = false, autoplay = true) }
            waitUntil(8_000L) {
                playback.state.value.songId == next.id && playback.state.value.playing
            }
            // Model a recreated UI retaining its exact selected card while Media3/native request
            // ownership has been released. This is the exact branch that used to make Play a no-op.
            onMain { playback.unload(next.id) }
            waitUntil(4_000L) { playback.state.value.songId == null }

            onMain { playback.toggleCurrentOrRebind(first, 0L) }
            waitForOwnerlessRebindWithTrace(
                label = "zero-position rebind",
                songId = first.id,
                minimumPositionMs = 100L
            )
            onMain {
                playback.pause()
                playback.seekTo(650L)
            }
            waitUntil(4_000L) {
                !playback.state.value.playing && playback.state.value.positionMs >= 600L
            }
            onMain { playback.unload(first.id) }
            waitUntil(4_000L) { playback.state.value.songId == null }

            onMain { playback.toggleCurrentOrRebind(first, 650L) }
            waitForOwnerlessRebindWithTrace(
                label = "nonzero-position rebind",
                songId = first.id,
                minimumPositionMs = 700L
            )
            var retainedLease: PlaybackControlLease? = null
            onMain { retainedLease = playback.acquireControlLease(first.id) }
            val controlLease = requireNotNull(retainedLease)
            onMain {
                playback.pause()
                assertTrue(playback.setLoop(750L, 1_150L, controlLease))
                playback.setRepeatMode("One")
            }
            val activation = try {
                runBlocking {
                    withTimeout(8_000L) {
                        withContext(Dispatchers.Main) {
                            playback.activateStems(stems, controlLease)
                        }
                    }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                val native = playback.nativeDiagnosticsSnapshotForTest()
                val committed = playback.adapter.snapshot().publication.committed
                throw AssertionError(
                    "Rebound Stems activation timed out: state=${playback.state.value}; " +
                        "source=${committed?.source}; stems=${committed?.stems}; " +
                        "selection=${playback.selectionPressureDiagnosticsForTest()}; " +
                        "readiness=${native?.nativeRenderReadiness}; " +
                        "decoders=${native?.controllerSelectedDecoders}; " +
                        "candidate=${native?.controllerCandidateDecoders}; " +
                        "preparation=${native?.controllerPreparationStages}; output=${native?.output}",
                    timeout
                )
            }
            assertTrue("Rebound owner could not activate Stems: $activation", activation.isSuccess)
            waitUntil(8_000L) {
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.state.value.songId == first.id
            }
            onMain {
                assertTrue(playback.switchToOriginal(controlLease))
            }
            waitUntil(8_000L) {
                playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL &&
                    playback.state.value.songId == first.id
            }
            onMain { playback.play() }
            waitUntil(8_000L) {
                playback.state.value.songId == first.id && playback.state.value.playing
            }
            SystemClock.sleep(900L)
            assertTrue("Loop/repeat escaped the rebound owner", playback.state.value.songId == first.id)
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
            DocumentsContract.deleteDocument(application.contentResolver, providerDirectory)
            application.revokeUriPermission(
                application.packageName,
                providerTree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            directory.deleteRecursively()
        }
    }

    private fun waitForOwnerlessRebindWithTrace(
        label: String,
        songId: String,
        minimumPositionMs: Long
    ) {
        val trace = ArrayDeque<String>()
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        fun capturePoint(stage: String) {
            val state = playback.state.value
            val native = playback.nativeDiagnosticsSnapshotForTest()
            val point = buildString {
                append(stage)
                append(" public=")
                append(
                    "${state.songId}/${state.loading}/${state.playing}/" +
                        "${state.playWhenReady}/${state.positionMs}/${state.durationMs}"
                )
                append(" facade=")
                append(playback.selectionPressureDiagnosticsForTest())
                append(" lifecycle=")
                append(playback.mediaSessionLifecycleDiagnosticsForTest())
                append(" controllerPublic=")
                append(native?.controllerPublicOwnership)
                append(" runtimeStatus=")
                append(native?.runtime?.status)
                append(" commandCounts=")
                append(native?.runtime?.counters?.let {
                    "${it.commandSnapshotsPublished}/${it.commandSnapshotsConsumed}/" +
                        "${it.commandSnapshotsSuperseded}/${it.rejectedCommandSnapshots}"
                })
                append(" banks=")
                append(native?.runtime?.banks)
                append(" pendingSeek=")
                append(native?.controllerPendingSeekOwnership)
                append(" ingressEpoch=")
                append(native?.controllerSelectedIngressEpoch)
                append(" decoders=")
                append(native?.controllerSelectedDecoders?.map { decoder ->
                    "${decoder.laneIndex}:${decoder.head.configured}/" +
                        "${decoder.head.nativeGeneration}/" +
                        "${decoder.head.acceptedTimelineFrames48k}/" +
                        "${decoder.head.endOfStreamTimelineFrameExclusive48k}"
                })
                append(" renderReady=")
                append(native?.nativeRenderReadiness)
                append(" manualGate=")
                append(native?.output?.manualSelectionGateState)
                append('/')
                append(native?.output?.manualSelectionAuthorizedGeneration)
            }
            trace.addLast(point)
        }
        capturePoint("after-action")
        var earlyCaptured = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = playback.state.value
            if (state.songId == songId && state.playing &&
                state.positionMs > minimumPositionMs
            ) return
            if (!earlyCaptured && SystemClock.elapsedRealtime() + 7_800L >= deadline) {
                capturePoint("after-200ms")
                earlyCaptured = true
            }
            SystemClock.sleep(25L)
        }
        capturePoint("timeout")
        fail("$label did not commit. Bounded trace:\n${trace.joinToString("\n")}")
    }

    @Test
    fun rapidNextThenPreviousCannotInstallAStaleStemOwner() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
            playback.state.value.songId?.let(playback::unload)
        }
        val directory = File(application.cacheDir, "cross-song-handoff").apply { mkdirs() }
        val firstOriginal = File(directory, "first.wav")
        val secondOriginal = File(directory, "second.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        writeSineWave(firstOriginal, 20_000L, 220.0)
        writeSineWave(secondOriginal, 8_000L, 240.0)
        writeSineWave(stemOne, 20_000L, 330.0)
        writeSineWave(stemTwo, 20_000L, 440.0)
        val first = Song(
            id = "cross-song-first",
            videoId = "cross-song-first",
            title = "Cross song first",
            artist = "Stemwise",
            durationMs = 20_000L,
            state = SongState.READY,
            accent = Color.Cyan,
            hasStems = true,
            artworkGlyph = "1",
            sourceHash = PlaybackContentFixture.sourceHash(firstOriginal),
            sourcePath = PlaybackContentFixture.publish(application, firstOriginal),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav",
            audioRevision = "cross-song-first"
        )
        val second = first.copy(
            id = "cross-song-second",
            videoId = "cross-song-second",
            title = "Cross song second",
            durationMs = 8_000L,
            hasStems = false,
            artworkGlyph = "2",
            sourceHash = PlaybackContentFixture.sourceHash(secondOriginal),
            sourcePath = PlaybackContentFixture.publish(application, secondOriginal),
            audioRevision = "cross-song-second"
        )
        val stems = listOf(
            stemAsset("bass", stemOne, 20_000L, "cross-song-set"),
            stemAsset("drums", stemTwo, 20_000L, "cross-song-set"),
            stemAsset("guitar", stemOne, 20_000L, "cross-song-set"),
            remainingAsset(stemTwo, 20_000L, 440.0, "cross-song-set"),
            stemAsset("piano", stemOne, 20_000L, "cross-song-set"),
            stemAsset("vocals", stemTwo, 20_000L, "cross-song-set")
        )

        try {
            onMain { playback.startQueue(listOf(first, second), shuffle = false, autoplay = true) }
            waitUntil(8_000L) { playback.state.value.songId == first.id && playback.state.value.playing }
            var retainedLease: PlaybackControlLease? = null
            onMain { retainedLease = playback.acquireControlLease(first.id) }
            val controlLease = requireNotNull(retainedLease)
            onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
            val activation = try { runBlocking {
                withTimeout(8_000L) {
                    coroutineScope {
                        val request = async(Dispatchers.Main) {
                            playback.activateStems(stems, controlLease)
                        }
                        // Same-token Original -> Stems is a native package/source transaction, not
                        // a queue-owner handoff. Supersede only after the production UI has entered
                        // that exact preparation window; waiting for token transfer can never
                        // complete and would make this regression test time out by construction.
                        while (playback.state.value.sourceSwitchState !is
                            SourceSwitchState.PreparingStems
                        ) delay(1L)
                        assertFalse("Activation completed before the overlap controls entered", request.isCompleted)
                        withContext(Dispatchers.Main) {
                            playback.selectSong(second, autoplay = true)
                        }
                        onMain { playback.debug.releaseNativeActorForSelectionBurst() }
                        request.await()
                    }
                }
            } } finally { onMain { playback.debug.releaseNativeActorForSelectionBurst() } }
            val supersededCleanly = activation.isSuccess ||
                activation.exceptionOrNull()?.message ==
                "The current song changed before Stems could open"
            assertTrue(
                "Superseded request should settle as benign owner loss: $activation",
                supersededCleanly
            )
            waitUntil(8_000L) {
                playback.state.value.songId == second.id && playback.state.value.playing
            }
            assertTrue(playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL)
            assertTrue(playback.state.value.error == null)
            assertTrue(playback.sessionMediaItem()?.mediaId == second.id)
            assertTrue(playback.graphMediaId() == second.id)

            onMain { playback.selectSong(first, autoplay = true) }
            waitUntil(8_000L) {
                playback.state.value.songId == first.id &&
                    playback.state.value.playing &&
                    playback.sessionMediaItem()?.mediaId == first.id &&
                    playback.graphMediaId() == first.id
            }
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun stale242147msOwnerPositionResetsBeforeShortSongStemHandoff() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
            playback.state.value.songId?.let(playback::unload)
        }

        val directory = File(application.cacheDir, "stale-owner-position").apply { mkdirs() }
        val longOriginal = File(directory, "long-original.wav")
        writeSilentWave(longOriginal, 250_000L)
        val longSong = Song(
            id = "stale-owner-position",
            videoId = "stale-owner-position",
            title = "Long owner",
            artist = "Stemwise",
            durationMs = 250_000L,
            state = SongState.READY,
            accent = Color.Cyan,
            hasStems = false,
            artworkGlyph = "L",
            sourceHash = PlaybackContentFixture.sourceHash(longOriginal),
            sourcePath = PlaybackContentFixture.publish(application, longOriginal),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav",
            audioRevision = "long-owner"
        )
        onMain { playback.startQueue(listOf(longSong), shuffle = false, autoplay = true) }
        waitUntil(8_000L) { playback.state.value.playing }
        onMain { playback.seekTo(242_147L) }
        waitUntil(4_000L) { playback.state.value.positionMs >= 242_147L }

        val shortDurationMs = 1_200L
        val shortOriginal = File(directory, "short-original.wav")
        val stemOne = File(directory, "short-stem-one.wav")
        val stemTwo = File(directory, "short-stem-two.wav")
        writeSineWave(shortOriginal, shortDurationMs, 220.0)
        writeSineWave(stemOne, shortDurationMs, 330.0)
        writeSineWave(stemTwo, shortDurationMs, 440.0)
        val shortSong = longSong.copy(
            title = "Short active song",
            durationMs = shortDurationMs,
            hasStems = true,
            sourceHash = PlaybackContentFixture.sourceHash(shortOriginal),
            sourcePath = PlaybackContentFixture.publish(application, shortOriginal),
            audioRevision = "short-active"
        )
        val stems = listOf(
            stemAsset("bass", stemOne, shortDurationMs, "stale-owner-position-set"),
            stemAsset("drums", stemTwo, shortDurationMs, "stale-owner-position-set"),
            stemAsset("guitar", stemOne, shortDurationMs, "stale-owner-position-set"),
            remainingAsset(stemTwo, shortDurationMs, 440.0, "stale-owner-position-set"),
            stemAsset("piano", stemOne, shortDurationMs, "stale-owner-position-set"),
            stemAsset("vocals", stemTwo, shortDurationMs, "stale-owner-position-set")
        )

        try {
            onMain { playback.selectSong(shortSong, autoplay = false, positionMs = 242_147L) }
            waitUntil(8_000L) { playback.state.value.songId == shortSong.id && !playback.state.value.loading }
            val lease = requireNotNull(playback.acquireControlLease(shortSong.id))
            val activation = runBlocking {
                withTimeoutOrNull(8_000L) {
                    withContext(Dispatchers.Main) {
                        playback.activateStemsForCurrentSong(shortSong, stems, lease)
                    }
                }
            }
            if (activation == null) {
                throw AssertionError(
                    "Stale-owner recovery did not settle within 8000ms: " +
                        playback.instrumentationIsolationDiagnostics()
                )
            }
            assertTrue("Stale-owner recovery failed: $activation", activation.isSuccess)
            waitUntil(8_000L) {
                playback.state.value.songId == shortSong.id &&
                    playback.state.value.sourceMode == PlaybackSourceMode.STEMS
            }
            val state = playback.state.value
            assertTrue("Stale owner position survived: $state", state.positionMs <= 50L)
            assertFalse("Stale owner recovery started sound: $state", state.playWhenReady)
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun longRunningOriginalHandsOffAtItsLivePosition() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
            playback.state.value.songId?.let(playback::unload)
        }

        val directory = File(application.cacheDir, "long-position-source-switch").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        val durationMs = 250_000L
        writeSilentWave(original, durationMs)
        writeSilentWave(stemOne, durationMs)
        writeSilentWave(stemTwo, durationMs)
        val song = Song(
            id = "long-position-source-switch",
            videoId = "long-position-source-switch",
            title = "Long position source switch",
            artist = "Stemwise",
            durationMs = durationMs,
            state = SongState.READY,
            accent = Color.Cyan,
            hasStems = true,
            artworkGlyph = "L",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav"
        )
        val stems = listOf(
            stemAsset("bass", stemOne, durationMs, "long-position-source-switch-set"),
            stemAsset("drums", stemTwo, durationMs, "long-position-source-switch-set"),
            stemAsset("guitar", stemOne, durationMs, "long-position-source-switch-set"),
            remainingAsset(stemTwo, durationMs, 0.0, "long-position-source-switch-set"),
            stemAsset("piano", stemOne, durationMs, "long-position-source-switch-set"),
            stemAsset("vocals", stemTwo, durationMs, "long-position-source-switch-set")
        )

        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
            waitUntil(8_000L) { playback.state.value.playing }
            onMain { playback.seekTo(242_147L) }
            waitUntil(4_000L) { playback.state.value.positionMs >= 242_147L }
            var retainedLease: PlaybackControlLease? = null
            onMain { retainedLease = playback.acquireControlLease(song.id) }
            val controlLease = requireNotNull(retainedLease)

            val activation = runBlocking {
                withTimeoutOrNull(8_000L) {
                    withContext(Dispatchers.Main) {
                        playback.activateStems(stems, controlLease)
                    }
                }
            }
            assertTrue(
                "Long-position stem activation failed: $activation; " +
                    playback.instrumentationIsolationDiagnostics(),
                activation?.isSuccess == true
            )
            try {
                waitUntil(8_000L) {
                    playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                        playback.state.value.sourceSwitchState is SourceSwitchState.Stems
                }
            } catch (failure: AssertionError) {
                throw AssertionError("Live stem handoff failed: " +
                    playback.instrumentationIsolationDiagnostics(), failure)
            }
            assertTrue(
                "Replacement native owner used a stale transport: ${playback.state.value}",
                playback.state.value.positionMs >= 242_000L
            )
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun stemsCanBeSelectedBeforeTheFirstOriginalPlay() = assertPausedStemActivation(afterPlayback = false)

    @Test
    fun coldStemsCanBeSelectedAfterOriginalOutputPhysicallyPauses() =
        assertPausedStemActivation(afterPlayback = true)

    private fun assertPausedStemActivation(afterPlayback: Boolean) {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
            playback.state.value.songId?.let(playback::unload)
        }

        val directory = File(application.cacheDir, "first-action-stems").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        val durationMs = 1_600L
        writeSineWave(original, durationMs, 220.0)
        writeSineWave(stemOne, durationMs, 330.0)
        writeSineWave(stemTwo, durationMs, 440.0)
        val song = Song(
            id = "first-action-stems",
            videoId = "first-action-stems",
            title = "First action stems",
            artist = "Stemwise",
            durationMs = durationMs,
            state = SongState.READY,
            accent = Color.Cyan,
            hasStems = true,
            artworkGlyph = "F",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav"
        )
        val stems = listOf(
            stemAsset("bass", stemOne, durationMs, "first-action-stems-set"),
            stemAsset("drums", stemTwo, durationMs, "first-action-stems-set"),
            stemAsset("guitar", stemOne, durationMs, "first-action-stems-set"),
            remainingAsset(stemTwo, durationMs, 440.0, "first-action-stems-set"),
            stemAsset("piano", stemOne, durationMs, "first-action-stems-set"),
            stemAsset("vocals", stemTwo, durationMs, "first-action-stems-set")
        )

        try {
            var expectedPosition = 420L
            var lease: PlaybackControlLease? = null
            if (afterPlayback) {
                onMain { playback.selectSong(song, autoplay = true) }
                waitUntil(8_000L) { playback.state.value.playing && playback.state.value.positionMs >= 200L }
                onMain { playback.pause() }
                waitUntil(4_000L) { !playback.state.value.playing }
                SystemClock.sleep(2_500L)
                onMain {
                    expectedPosition = playback.state.value.positionMs
                    lease = requireNotNull(playback.acquireControlLease(song.id))
                }
            } else {
                onMain { playback.selectSong(song, autoplay = false, positionMs = expectedPosition) }
                waitUntil(8_000L) { playback.state.value.songId == song.id && !playback.state.value.loading }
                onMain { lease = requireNotNull(playback.acquireControlLease(song.id)) }
            }
            val activation = runBlocking {
                withTimeoutOrNull(8_000L) {
                    withContext(Dispatchers.Main) {
                        playback.activateStemsForCurrentSong(song, stems, requireNotNull(lease))
                    }
                }
            }
            if (activation == null) {
                throw AssertionError(
                    "First-action Stems activation did not return within 8000ms: " +
                        playback.instrumentationIsolationDiagnostics()
                )
            }
            assertTrue("First-action Stems activation failed: $activation", activation.isSuccess)
            waitUntil(8_000L) {
                playback.state.value.songId == song.id &&
                    playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.state.value.sourceSwitchState is SourceSwitchState.Stems
            }
            val state = playback.state.value
            assertFalse("Selecting Stems unexpectedly started playback: $state", state.playing)
            assertFalse("Selecting Stems unexpectedly set play intent: $state", state.playWhenReady)
            assertTrue("Requested position was not retained: $state",
                state.positionMs in (expectedPosition - 60L)..(expectedPosition + 80L))
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun rejectedInvalidAndCancelledStemsLeaveEndedOriginalUntouched() {
        val (song, stems) = sourceSwitchFixture("ended-rejected-stems", 1_200L)
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
        waitUntil(8_000L) { playback.state.value.songId == song.id && !playback.state.value.loading }
        onMain { playback.seekTo(song.durationMs) }
        waitUntil(4_000L) { playback.state.value.ended }
        var lease: PlaybackControlLease? = null
        onMain { lease = playback.acquireControlLease(song.id) }
        val verify = playback.debug.verifyPlaybackStems
        try {
            for (fault in listOf("unreadable", "invalid", "cancelled")) {
                playback.debug.verifyPlaybackStems = { _, _ ->
                    when (fault) {
                        "unreadable" -> error("Stem bytes unavailable")
                        "cancelled" -> throw kotlinx.coroutines.CancellationException("Switch cancelled")
                        else -> emptyList()
                    }
                }
                val result = runBlocking { runCatching {
                    withContext(Dispatchers.Main) { playback.activateStems(stems, requireNotNull(lease)) }.getOrThrow()
                } }
                assertTrue("$fault unexpectedly accepted", result.isFailure)
                val state = playback.state.value
                assertTrue("$fault rewound Original: $state", state.ended)
                assertEquals(song.durationMs, state.positionMs)
                assertEquals(PlaybackSourceMode.ORIGINAL, state.sourceMode)
                assertFalse(state.playWhenReady)
            }
        } finally { playback.debug.verifyPlaybackStems = verify }
    }

    @Test
    fun sourceSwitchesFromEndedTransportRewindPausedAndSettle() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
            playback.state.value.songId?.let(playback::unload)
        }

        val directory = File(application.cacheDir, "ended-source-switch").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        val durationMs = 1_200L
        writeSineWave(original, durationMs, 220.0)
        writeSineWave(stemOne, durationMs, 330.0)
        writeSineWave(stemTwo, durationMs, 440.0)
        val song = Song(
            id = "ended-source-switch",
            videoId = "ended-source-switch",
            title = "Ended source switch",
            artist = "Stemwise",
            durationMs = durationMs,
            state = SongState.READY,
            accent = Color.Cyan,
            hasStems = true,
            artworkGlyph = "E",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav"
        )
        val stems = listOf(
            stemAsset("bass", stemOne, durationMs),
            stemAsset("drums", stemTwo, durationMs),
            stemAsset("guitar", stemOne, durationMs),
            remainingAsset(stemTwo, durationMs, 440.0),
            stemAsset("piano", stemOne, durationMs),
            stemAsset("vocals", stemTwo, durationMs)
        )

        try {
            onMain {
                playback.setRepeatMode("Off")
                playback.startQueue(listOf(song), shuffle = false, autoplay = true)
            }
            waitUntil(8_000L) {
                playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL &&
                    playback.state.value.playing
            }
            onMain { playback.seekTo(durationMs) }
            waitUntil(4_000L) { playback.state.value.ended }
            var retainedLease: PlaybackControlLease? = null
            onMain { retainedLease = playback.acquireControlLease(song.id) }
            val controlLease = requireNotNull(retainedLease)

            val stemActivation = runBlocking {
                withTimeout(8_000L) {
                    withContext(Dispatchers.Main) {
                        playback.activateStems(stems, controlLease)
                    }
                }
            }
            assertTrue("Stem activation failed: $stemActivation", stemActivation.isSuccess)
            waitUntil(8_000L) {
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.state.value.sourceSwitchState is SourceSwitchState.Stems
            }
            assertRewoundAndPaused()

            onMain { playback.play() }
            waitUntil(4_000L) { playback.state.value.playing }
            onMain { playback.seekTo(durationMs) }
            waitUntil(4_000L) {
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.state.value.ended
            }

            onMain {
                playback.switchToOriginal(controlLease)
            }
            waitUntil(4_000L) {
                playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL &&
                    playback.state.value.sourceSwitchState is SourceSwitchState.Original
            }
            assertRewoundAndPaused()
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    private fun isolateFixtureSettings(song: Song) {
        val root = application.services.libraryRootManager.captureHandle() ?: return
        val identity = SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), song.id,
            checkNotNull(song.sourceHash))
        if (identity in ownedSettingsSongs) return
        ownedSettingsSongs[identity] = song
        // Drain accepted writes before clearing disk, then replace the facade's cached row too.
        runBlocking { withTimeout(8_000L) { playback.savedSongTrim(song) } }
        SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).clear(identity)
        runBlocking { withTimeout(8_000L) { playback.savedSongTrim(song) } }
    }

    private fun isolateAndCleanOwnedState() {
        playback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
        val store = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        runBlocking { withTimeout(12_000L) {
            ownedSettingsSongs.values.forEach { playback.savedSongTrim(it) }
        } }
        ownedSettingsSongs.keys.forEach(store::clear)
        ownedSettingsSongs.clear()
        PlaybackContentFixture.clear(application)
    }

    private fun assertRewoundAndPaused() {
        val state = playback.state.value
        assertTrue("Expected rewind after ended source switch: $state", state.positionMs <= 50L)
        assertFalse("Ended source switch unexpectedly kept play intent: $state", state.playWhenReady)
        assertFalse("Ended source switch unexpectedly started playback: $state", state.playing)
        assertFalse("Ended flag survived source switch: $state", state.ended)
        assertFalse(
            "Source switch remained transitional: $state",
            state.sourceSwitchState is SourceSwitchState.PreparingStems ||
                state.sourceSwitchState is SourceSwitchState.ReturningOriginal
        )
    }

    private fun stemAsset(
        id: String,
        file: File,
        durationMs: Long,
        setId: String = "ended-source-switch-set"
    ) = PlaybackContentFixture.stem(application, id, file, durationMs, setId)

    private fun remainingAsset(
        tone: File, durationMs: Long, frequency: Double, setId: String = "ended-source-switch-set"
    ): StemAssetEntity {
        val remaining = File(tone.parentFile, "${tone.nameWithoutExtension}-remaining.wv")
        PlaybackContentFixture.writeRemainingTone(application, remaining, durationMs, frequency, 2_000.0 / 32_768.0)
        return stemAsset("remaining", remaining, durationMs, setId).copy(
            codec = "wavpack", sampleRateHz = 48_000, channelCount = 2
        )
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L)
        }
        assertTrue("Timed out waiting for playback state: ${playback.state.value}", predicate())
    }

    private fun writeSineWave(file: File, durationMs: Long, frequency: Double) {
        PlaybackContentFixture.own(application, file)
        val sampleRate = 44_100
        val sampleCount = (durationMs * sampleRate / 1_000L).toInt()
        val dataSize = sampleCount * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { output ->
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + dataSize))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeInt(Integer.reverseBytes(sampleRate))
            output.writeInt(Integer.reverseBytes(sampleRate * 2))
            output.writeShort(java.lang.Short.reverseBytes(2).toInt())
            output.writeShort(java.lang.Short.reverseBytes(16).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(dataSize))
            repeat(sampleCount) { index ->
                val sample =
                    (kotlin.math.sin(2.0 * Math.PI * frequency * index / sampleRate) * 2_000.0)
                        .toInt()
                        .toShort()
                output.writeShort(java.lang.Short.reverseBytes(sample).toInt())
            }
        }
    }

    private fun writeSilentWave(file: File, durationMs: Long) {
        PlaybackContentFixture.own(application, file)
        val sampleRate = 44_100
        val dataSize = (durationMs * sampleRate / 1_000L * 2L).toInt()
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0L)
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + dataSize))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeInt(Integer.reverseBytes(sampleRate))
            output.writeInt(Integer.reverseBytes(sampleRate * 2))
            output.writeShort(java.lang.Short.reverseBytes(2).toInt())
            output.writeShort(java.lang.Short.reverseBytes(16).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(dataSize))
            output.setLength(44L + dataSize)
        }
    }

}
