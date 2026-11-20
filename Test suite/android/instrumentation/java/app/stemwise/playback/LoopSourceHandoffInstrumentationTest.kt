package app.stemwise.playback

import android.media.AudioManager
import android.os.SystemClock
import android.os.PowerManager
import androidx.compose.ui.graphics.Color
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.stemwise.StemwiseApplication
import app.stemwise.data.StemAssetEntity
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.parseCustomSourceManifest
import app.stemwise.demo.UiDemoLibraryDocumentsProvider
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.engine.NativeAudioPlaybackSeekReceipt
import app.stemwise.playback.engine.EngineSourceMode
import app.stemwise.playback.engine.NativeRuntimeOwnership
import app.stemwise.playback.engine.NativeRuntimeSourceMode
import app.stemwise.qa.DirectLoopEnduranceOracle
import app.stemwise.qa.loopClearHasReachedLinearPresentation
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class LoopSourceHandoffInstrumentationTest : LoopPlaybackFixture() {
    private var ownedMatrixSettings: Pair<SongPlaybackSettingsIdentity, Song>? = null

    @After
    fun clearOwnedMatrixSettings() {
        val (identity, song) = ownedMatrixSettings ?: return
        playback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
        runBlocking { withTimeout(8_000L) { playback.savedSongTrim(song) } }
        SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).clear(identity)
        ownedMatrixSettings = null
    }

    @get:Rule
    val audioHealthAssertions = ErrorCollector()

    @Test
    fun nineDemoStemsKeepLoopingAtEverySpeedAndPitchExtreme() {
        runBlocking { UiDemoLibraryDocumentsProvider.initialize(application) }
        val tree = UiDemoLibraryDocumentsProvider.treeUri
        application.grantUriPermission(application.packageName, tree,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        val folder = checkNotNull(DocumentFile.fromTreeUri(application, tree)?.findFile("DEMOUI00001"))
        val documents = folder.listFiles().associateBy { checkNotNull(it.name) }
        assertEquals(UiDemoLibraryDocumentsProvider.FIXTURE_FILES.toSet(), documents.keys)
        val manifestBytes = checkNotNull(application.contentResolver.openInputStream(
            documents.getValue(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).uri
        )).use { it.readBytes() }
        val sourceHash = JSONObject(manifestBytes.toString(Charsets.UTF_8)).getString("sourceSha256")
        val manifest = parseCustomSourceManifest(manifestBytes, "DEMOUI00001", sourceHash)
        val duration = manifest.audibleFrames / 48L
        val song = Song(
            id = "nine-demo-stems", videoId = "DEMOUI00001", title = "9 stem pressure",
            artist = "StemWise", durationMs = duration, state = SongState.READY,
            accent = Color.Cyan, hasStems = true, artworkGlyph = "S",
            sourceHash = sourceHash,
            sourcePath = PlaybackContentFixture.register(application,
                documents.getValue(SelectedLibraryContract.ORIGINAL).uri.toString()),
            sourceCodec = "opus", sourceContainer = "ogg"
        )
        val stems = manifest.lanes.map { lane ->
            StemAssetEntity(id = lane.sourceId, separationSetId = "nine-demo-stems",
                providerLabel = lane.label, normalizedCapability = lane.sourceId,
                localPath = PlaybackContentFixture.register(application,
                    documents.getValue(lane.fileName).uri.toString()), sha256 = lane.sha256,
                durationMs = duration, codec = if (lane.sourceId == "remaining") "wavpack" else "flac",
                sampleRateHz = 48_000, channelCount = 2, validated = true)
        }
        assertEquals(9, stems.size)
        val baseline = checkNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
        fun assertHealthy(stage: String) {
            val native = checkNotNull(playback.nativeDiagnosticsSnapshotForTest())
            val output = checkNotNull(native.output)
            assertEquals("9 stems $stage: generation=${output.lastUnderrunGenerationAtRenderDecision}; " +
                "enqueueAgeUs=${output.lastUnderrunSuccessfulEnqueueAgeMicros}; " +
                "reserve=${output.lastUnderrunSuccessfulEnqueueQueueAfterFrames}; " +
                "lowWater=${native.runtime.currentOutputQueueLowWater}; " +
                "waits=${native.runtime.needOriginalData}/${native.runtime.needStemData}; " +
                "cacheMask=${native.nativeRenderReadiness?.currentSongCacheCompleteLaneMask}",
                baseline.processUnderrunCallbackCount, output.processUnderrunCallbackCount)
        }
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
        waitUntil(8_000L) { playback.state.value.songId == song.id && playback.state.value.loopAvailable }
        val lease = checkNotNull(playback.acquireControlLease(song.id))
        onMain {
            assertTrue(playback.setLoop(1_000L, 3_000L, lease))
            assertTrue(playback.seekTo(1_000L))
            playback.play()
        }
        awaitDirectWraps(1_000L, 3_000L, minimumWraps = 1, timeoutMs = 5_000L)
        assertHealthy("Original loop")
        val activation = runBlocking {
            withTimeout(12_000L) {
                withContext(Dispatchers.Main) { playback.activateStems(stems, lease) }
            }
        }
        assertTrue("9 stems did not activate inside the running loop: $activation", activation.isSuccess)
        waitUntil(8_000L) {
            val native = playback.nativeDiagnosticsSnapshotForTest() ?: return@waitUntil false
            val session = playback.adapter.snapshot()
            val committed = session.publication.committed ?: return@waitUntil false
            val desired = committed.stems.desired ?: return@waitUntil false
            val installed = committed.stems.installed ?: return@waitUntil false
            val owner = committed.owner.ownership
            val readiness = native.nativeRenderReadiness ?: return@waitUntil false
            val audible = native.runtime.audibleControl ?: return@waitUntil false
            val source = native.controllerSourceControlPublications.lastOrNull {
                it.ownership == owner && it.nativeControlRevision == audible.controlRevision
            } ?: return@waitUntil false
            playback.state.value.loadedStemAssets.size == 9 && committed.selection.songId == song.id &&
                session.latestRequest?.exactOwnership == owner &&
                native.controllerPublicOwnership == owner &&
                native.runtime.ownership.requested == owner && native.runtime.ownership.accepted == owner &&
                native.runtime.ownership.committed == owner &&
                readiness.ownership == owner && readiness.requiredSongLaneMask == 0x3ff &&
                audible.ownership == owner && audible.actualSourceMode == NativeRuntimeSourceMode.STEMS &&
                committed.source.desiredMode == EngineSourceMode.STEMS &&
                committed.source.appliedMode == EngineSourceMode.STEMS &&
                committed.source.appliedRevision == committed.source.desiredRevision &&
                source.desiredRevision == committed.source.desiredRevision &&
                source.requestedMode == EngineSourceMode.STEMS && source.nativeMode == NativeRuntimeSourceMode.STEMS &&
                source.stemPackageSignature == desired.packageSignature &&
                source.stemRevision == desired.stemRevision && source.stemRequestRevision == desired.requestRevision &&
                source.stemLaneIds.size == 9 && source.stemLaneIds.toSet() == stems.map { it.id }.toSet() &&
                installed.packageSignature == desired.packageSignature && installed.stemRevision == desired.stemRevision &&
                installed.requestRevision == desired.requestRevision &&
                session.latestRequest?.controls?.loop?.let { it.startMs == 1_000L && it.endMs == 3_000L } == true &&
                session.structuralRevision == playback.adapter.snapshot().structuralRevision
        }
        assertHealthy("activation")
        for ((tempo, pitch) in listOf(.5f to -12, .5f to 12, 2f to -12, 2f to 12, 1.35f to 7, 1f to 0)) {
            onMain { assertTrue(playback.setTempoAndPitch(tempo, pitch, lease)) }
            waitUntil(3_000L) {
                val controls = playback.graphDiagnostics()
                abs((controls.appliedTempo ?: Float.NaN) - tempo) < .001f &&
                    abs((controls.appliedPitchRatio ?: Float.NaN) - 2.0.pow(pitch / 12.0).toFloat()) < .001f
            }
            val admissionStartedAtMs = SystemClock.elapsedRealtime()
            var adoptedAtMs = 0L
            awaitDirectWraps(1_000L, 3_000L, minimumWraps = 3,
                timeoutMs = (6_000L / tempo).toLong() + 2_000L,
                onExactAdoption = { adoptedAtMs = it })
            check(adoptedAtMs > 0L) { "No exact loop adoption was observed" }
            val elapsed = SystemClock.elapsedRealtime() - adoptedAtMs
            assertTrue("$tempo x / $pitch semitones: admission=${adoptedAtMs - admissionStartedAtMs}ms; " +
                "three-wrap endurance=${elapsed}ms",
                elapsed in (4_000L / tempo * .85f).toLong()..(6_000L / tempo * 1.15f).toLong())
            assertHealthy("$tempo x / $pitch semitones")
            val native = checkNotNull(playback.nativeDiagnosticsSnapshotForTest())
            val output = checkNotNull(native.output)
            assertEquals("Unexpected Original fallback", "STEMS", native.runtime.audibleControl?.actualSourceMode?.name)
            assertEquals(9, playback.state.value.stemMix.size)
            assertTrue(playback.state.value.stemMix.values.all { it.volume == 1f && !it.muted && !it.solo })
            assertEquals(baseline.processUnderrunCallbackCount, output.processUnderrunCallbackCount)
            assertEquals(baseline.processUnderrunFrames, output.processUnderrunFrames)
            assertEquals(baseline.processCallbackDeadlineMissCount, output.processCallbackDeadlineMissCount)
            assertEquals(baseline.processXRunCount, output.processXRunCount)
            assertEquals(baseline.processInvalidOwnershipTagFrames, output.processInvalidOwnershipTagFrames)
            assertEquals(baseline.loop.processBoundaryMissCount, output.loop.processBoundaryMissCount)
        }
    }

    @Test
    fun clearingAnEditedLoopResumesLinearAudioInBothSources() {
        val initialXRuns = checkNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output).processXRunCount
        fun assertNoXRuns(stage: String) {
            val output = checkNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
            // Preserve every failure while allowing the later source and route checks to run.
            audioHealthAssertions.checkSucceeds {
                assertEquals("$stage: callback=${output.callbackCount}; buffer=${output.outputBufferSizeFrames}; " +
                    "maxStartGapUs=${output.maximumCallbackStartGapMicros}", initialXRuns, output.processXRunCount)
            }
        }
        val directory = File(application.cacheDir, "edited-loop-clear").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val duration = 12_000L
        writeSineWave(original, duration, 247.0)
        val song = Song(
            id = "edited-loop-clear", videoId = "edited-loop-clear",
            title = "Loop clear regression", artist = "StemWise", durationMs = duration,
            state = SongState.READY, accent = Color.Cyan, hasStems = true, artworkGlyph = "L",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceCodec = "pcm_s16le", sourceContainer = "wav"
        )
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
        waitUntil(8_000L) { playback.state.value.songId == song.id && playback.state.value.loopAvailable }
        val lease = checkNotNull(playback.acquireControlLease(song.id))
        val stems = listOf("bass", "drums", "guitar", "remaining", "piano", "vocals")
            .map { id ->
                if (id == "remaining") remainingAsset(original, duration, 247.0)
                else stemAsset(id, original, duration)
            }
        onMain { playback.pause() }
        val prepared = try {
            runBlocking {
                withTimeout(12_000L) {
                    withContext(Dispatchers.Main) { playback.activateStems(stems, lease) }
                }
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            val engineThreads = Thread.getAllStackTraces().filterKeys { it.name == "StemWise-NativeEngine" }
                .entries.joinToString { (thread, stack) -> "${thread.state}: ${stack.joinToString()}" }
            throw AssertionError("Initial stem activation timed out: state=${playback.state.value}; " +
                "native=${playback.nativeDiagnosticsSnapshotForTest()}; engineThreads=$engineThreads", failure)
        }
        assertTrue(prepared.toString(), prepared.isSuccess)
        assertNoXRuns("Initial stem preparation")
        onMain { assertTrue(playback.switchToOriginal(lease)) }
        fun seekAndAwaitPausedOwner(positionMs: Long, pauseBeforeSeek: Boolean = true) {
            lateinit var receipt: NativeAudioPlaybackSeekReceipt
            onMain {
                if (pauseBeforeSeek) playback.pause()
                receipt = checkNotNull(playback.seekToWithReceipt(positionMs, lease))
            }
            // UI position is optimistic; only this receipt's completed transport and exact
            // installed native owner can establish the baseline for the following loop oracle.
            waitUntil(8_000L) {
                val session = playback.adapter.snapshot()
                val latest = session.latestRequest
                val committed = session.publication.committed
                if (!seekReceiptIsCompleted(receipt, session) || latest == null || committed == null) false else {
                    val owner = committed.owner.ownership
                    val native = playback.nativeDiagnosticsSnapshotForTest()
                    val ownership = native?.runtime?.ownership
                    native != null && ownership != null && latest.exactOwnership == owner && ownership.requested == owner &&
                        ownership.accepted == owner && ownership.committed == owner &&
                        ownership.acceptedFirstFingerprintSequence > 0L &&
                        native.controllerPublicOwnership == owner &&
                        native.nativeRenderReadiness?.ownership == owner &&
                        native.controllerPendingSeekOwnership == null &&
                        native.output?.playing == false && !playback.state.value.playing &&
                        playback.state.value.loopAvailable &&
                        playback.state.value.positionMs in (positionMs - 100L)..(positionMs + 200L)
                }
            }
        }
        fun awaitLinearContinuation(source: PlaybackSourceMode, maxOutgoingEndMs: Long) {
            val owner = checkNotNull(playback.adapter.snapshot().publication.committed).owner.ownership
            val accepted = checkNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output?.loop)
            assertEquals(0L, accepted.latestDemandAFrame)
            assertEquals(0L, accepted.latestDemandBFrameExclusive)
            val clearRevision = accepted.latestDemandRevision
            var previousFrame = -1L
            // Old accepted PCM can still wrap after Clear admission; crossing every outgoing B proves linear output.
            waitUntil(2_000L) {
                val native = playback.nativeDiagnosticsSnapshotForTest()
                val position = native?.runtime?.presentedPosition
                if (loopClearHasReachedLinearPresentation(owner, clearRevision, maxOutgoingEndMs * 48L,
                        position, native?.nativeRenderReadiness)) {
                    previousFrame = checkNotNull(position).lastTimelineFrame
                    true
                } else false
            }
            waitUntil(5_000L) {
                val position = playback.nativeDiagnosticsSnapshotForTest()?.runtime?.presentedPosition
                if (position == null) false else {
                    assertEquals(owner, position.ownership)
                    val frame = position.lastTimelineFrame
                    assertTrue("$source repeated cleared audio: $previousFrame -> $frame; " +
                        "physical=${position.loopPolicyRevision}, adopted=${position.loopPolicyAdoptedRevision}, clear=$clearRevision",
                        previousFrame < 0L || frame + 960L >= previousFrame)
                    previousFrame = frame
                    frame > 4_500L * 48L
                }
            }
        }
        for (source in listOf(PlaybackSourceMode.ORIGINAL, PlaybackSourceMode.STEMS)) {
            android.util.Log.i("StemwiseLoopClear", "$source settled edit then clear")
            seekAndAwaitPausedOwner(2_000L)
            if (source == PlaybackSourceMode.STEMS) {
                val result = runBlocking {
                    withTimeout(12_000L) {
                        withContext(Dispatchers.Main) { playback.activateStems(stems, lease) }
                    }
                }
                assertTrue(result.toString(), result.isSuccess)
            }
            seekAndAwaitPausedOwner(2_000L, pauseBeforeSeek = false)
            waitUntil(8_000L) {
                playback.state.value.sourceMode == source && playback.state.value.loopAvailable &&
                    playback.state.value.positionMs in 1_900L..2_200L
            }
            onMain {
                assertTrue(playback.setRepeatMode("Off"))
                assertTrue(playback.setLoop(2_003L, 3_007L, lease))
                assertEquals(2_003L, playback.state.value.loopA)
                assertEquals(3_007L, playback.state.value.loopB)
                playback.play()
            }
            waitUntil(8_000L) { directLoopAdoptedForCurrentOwner() }
            try {
                awaitDirectWraps(2_003L, 3_007L, minimumWraps = 2, timeoutMs = 6_000L)
            } catch (failure: Throwable) {
                val session = playback.adapter.snapshot()
                val latest = session.latestRequest
                val committed = session.publication.committed
                val native = playback.nativeDiagnosticsSnapshotForTest()
                throw AssertionError(
                    "First settled-loop wraps failed for source=$source: ${failure.message}; " +
                        "latestOwner=${latest?.exactOwnership}, desiredRevision=${latest?.controls?.revision}, " +
                        "seekRevision=${latest?.controls?.seekRevision}, " +
                        "requestedPositionMs=${latest?.controls?.requestedPositionMs}, " +
                        "requestedSource=${latest?.controls?.sourceMode}, " +
                        "committedOwner=${committed?.owner?.ownership}, " +
                        "completedSeekRevision=${committed?.transport?.completedSeekRevision}; " +
                        "pendingSeek=${native?.controllerPendingSeekOwnership}, " +
                        "selectedIngress=${native?.controllerSelectedIngressEpoch}, " +
                        "runtimeOwnership=${native?.runtime?.ownership}; " +
                        "preparation=${native?.controllerPreparationStages}; " +
                        "candidateDecoders=${native?.controllerCandidateDecoders}; " +
                        "sourcePublications=${native?.controllerSourceControlPublications}; " +
                        "playbackEvents=${playback.playbackEventHistoryForTest()}; " +
                        "state=${playback.state.value}; native=$native",
                    failure
                )
            }
            assertNoXRuns("$source before focus loss")
            // A call can leave the output callback physically paused while the loop owns
            // a prepared continuation. Gain must resume that exact loop without a new Play.
            onMain { playback.debug.dispatchAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) }
            waitUntil(6_000L) {
                !playback.state.value.playing &&
                    playback.nativeDiagnosticsSnapshotForTest()?.controllerOutputPhysicallyQuiesced == true
            }
            assertNoXRuns("$source focus loss")
            onMain { playback.debug.dispatchAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN) }
            waitUntil(6_000L) { playback.state.value.playing }
            awaitDirectWraps(2_003L, 3_007L, minimumWraps = 2, timeoutMs = 6_000L)
            assertNoXRuns("$source focus gain")
            val beforeRoute = checkNotNull(playback.nativeDiagnosticsSnapshotForTest())
                .runtime.presentedPosition?.ownership
            onMain {
                playback.adapter.requestRouteRecovery()
            }
            waitUntil(8_000L) {
                playback.state.value.playing && playback.state.value.loopAvailable &&
                    playback.nativeDiagnosticsSnapshotForTest()?.runtime?.presentedPosition
                        ?.ownership?.let { it != beforeRoute } == true
            }
            awaitDirectWraps(2_003L, 3_007L, minimumWraps = 2, timeoutMs = 6_000L)
            assertNoXRuns("$source route recovery")
            // Playback and its loop keep running while the UI/device sleeps.
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            val power = requireNotNull(application.getSystemService(PowerManager::class.java))
            val wasInteractive = power.isInteractive
            var screenFailure: Throwable? = null
            try {
                ui.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
                waitUntil(4_000L) { !power.isInteractive }
                awaitDirectWraps(2_003L, 3_007L, minimumWraps = 3, timeoutMs = 7_000L)
                assertTrue("Device woke during screen-off loop observation", !power.isInteractive)
            } catch (failure: Throwable) {
                screenFailure = failure
                throw failure
            } finally {
                try {
                    if (wasInteractive) {
                        ui.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
                        waitUntil(4_000L) { power.isInteractive }
                    }
                } catch (restoreFailure: Throwable) {
                    val originalFailure = screenFailure
                    if (originalFailure == null) throw restoreFailure
                    originalFailure.addSuppressed(restoreFailure)
                }
            }
            val oldPhysicalRevision = playback.nativeDiagnosticsSnapshotForTest()
                ?.runtime?.presentedPosition?.loopPolicyRevision
            onMain { assertTrue(playback.setLoop(2_107L, 3_409L, lease,
                transportPositionMs = playback.state.value.positionMs, publishPresentation = false)) }
            waitUntil(8_000L) {
                val position = playback.nativeDiagnosticsSnapshotForTest()?.runtime?.presentedPosition
                directLoopAdoptedForCurrentOwner() && position != null &&
                    position.loopPolicyRevision != oldPhysicalRevision &&
                    position.lastTimelineFrame >= 2_100L * 48L
            }
            onMain { assertTrue(playback.publishLoopPresentation(2_107L, 3_409L, lease)) }
            awaitDirectWraps(2_107L, 3_409L, minimumWraps = 2, timeoutMs = 6_000L)
            onMain { assertTrue(playback.setLoop(null, null, lease)) }
            assertTrue(playback.state.value.loopA == null && playback.state.value.loopB == null)
            awaitLinearContinuation(source, 3_409L)
            assertTrue("$source clear must keep audio playing", playback.state.value.playing)
            android.util.Log.i("StemwiseLoopClear", "$source immediate edit then clear")
            seekAndAwaitPausedOwner(2_000L)
            onMain {
                assertTrue(playback.setLoop(2_003L, 3_007L, lease))
                assertEquals(2_003L, playback.state.value.loopA)
                assertEquals(3_007L, playback.state.value.loopB)
                playback.play()
            }
            waitUntil(8_000L) { directLoopAdoptedForCurrentOwner() }
            awaitDirectWraps(2_003L, 3_007L, minimumWraps = 2, timeoutMs = 6_000L)
            waitUntil(2_000L) {
                playback.state.value.positionMs in 2_650L..2_850L
            }
            onMain {
                // A held-B crossing keeps the installed A head; moving A is a separate edit.
                assertTrue(playback.setLoop(2_003L, 2_500L, lease,
                    transportPositionMs = playback.state.value.positionMs, publishPresentation = false,
                    heldBGestureId = 9L, capturedBMs = 2_500L))
                assertTrue(playback.setLoop(null, null, lease))
            }
            awaitLinearContinuation(source, 3_007L)

            // A loop ending at song EOS has no following PCM with which to prepare a clear.
            seekAndAwaitPausedOwner(duration - 1_000L)
            onMain {
                assertTrue(playback.setLoop(duration - 1_000L, duration, lease))
                playback.play()
            }
            awaitDirectWraps(duration - 1_000L, duration, minimumWraps = 2, timeoutMs = 6_000L)
            onMain { assertTrue(playback.setLoop(null, null, lease)) }
            waitUntil(5_000L) { !playback.state.value.playing && playback.state.value.ended }
        }
    }

    @Test
    fun originalOnlySongPublishesLoopAvailabilityAfterItsCacheSeals() {
        val directory = File(application.cacheDir, "loop-original-only").apply { mkdirs() }
        val original = File(directory, "original.opus")
        application.assets.open("demo_library/DEMOUI00001/original.opus").use { input ->
            original.outputStream().use(input::copyTo)
        }
        val song = Song(
            id = "original-only-loop",
            videoId = "original-only-loop",
            title = "Original-only loop",
            artist = "Stemwise",
            durationMs = 12_000L,
            state = SongState.READY,
            accent = Color.Cyan,
            // The library may advertise downloadable Stems while only Original is installed.
            hasStems = true,
            artworkGlyph = "O",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceCodec = "opus",
            sourceContainer = "ogg"
        )

        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
        waitUntil(8_000L) {
            playback.state.value.songId == song.id && playback.state.value.playing
        }
        waitUntil(8_000L) { playback.state.value.loopAvailable }
        assertTrue(
            "Original-only cache did not make Loop available: " +
                "state=${playback.state.value}; native=${playback.nativeDiagnosticsSnapshotForTest()}",
            playback.state.value.loopAvailable
        )
    }

    @Test
    fun loopMatrixAndHundredMultiStemWrapsRemainCoordinated() {
        loopFailurePhase = "matrix/setup"
        // The endurance player owns the process-wide playback singleton. Close a
        // manually opened MainActivity so its real-library queue collector cannot
        // replace this synthetic qualification queue midway through the run.
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
            playback.state.value.songId?.let(playback::unload)
        }
        val directory = File(application.cacheDir, "loop-qualification").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        writeSineWave(original, qualificationDurationMs, 220.0)
        writeSineWave(stemOne, qualificationDurationMs, 330.0)
        writeSineWave(stemTwo, qualificationDurationMs, 440.0)
        val song = Song(
            id = "loop-qualification",
            videoId = "loop-qualification",
            title = "Loop qualification",
            artist = "Stemwise",
            durationMs = qualificationDurationMs,
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
            stemAsset("bass", stemOne),
            stemAsset("drums", stemTwo),
            stemAsset("guitar", stemOne),
            remainingAsset(stemTwo, qualificationDurationMs, 440.0),
            stemAsset("piano", stemOne),
            stemAsset("vocals", stemTwo)
        )

        application.services.libraryRootManager.captureHandle()?.let { root ->
            val identity = SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), song.id,
                checkNotNull(song.sourceHash))
            ownedMatrixSettings = identity to song
            runBlocking { withTimeout(8_000L) { playback.savedSongTrim(song) } }
            SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).clear(identity)
            runBlocking { withTimeout(8_000L) { playback.savedSongTrim(song) } }
        }

        loopFailurePhase = "matrix/initial-original"
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
        waitUntil(8_000L) {
            !playback.state.value.loading &&
                playback.state.value.durationMs >= 5_900L &&
                playback.state.value.error == null
        }
        waitUntil(8_000L) {
            playback.state.value.playWhenReady &&
                playback.state.value.positionMs > 50L
        }
        val loopGestureLease = checkNotNull(playback.acquireControlLease(song.id))
        val positionBeforeLoopMove = playback.state.value.positionMs
        onMain {
            assertTrue(
                playback.setLoop(
                    10_000L,
                    15_000L,
                    loopGestureLease,
                    positionBeforeLoopMove
                )
            )
        }
        waitUntil(4_000L) {
            playback.state.value.loopA == 10_000L &&
                playback.state.value.loopB == 15_000L &&
                playback.state.value.playing &&
                playback.state.value.positionMs > positionBeforeLoopMove
        }
        assertTrue(playback.state.value.loopA == 10_000L)
        assertTrue(playback.state.value.loopB == 15_000L)
        assertTrue(playback.state.value.playing)
        var outgoingStayedAudible = true
        // First activation builds the whole six-lane cache. ARM emulator decoding of this
        // 30-second fixture takes longer than an ordinary already-prepared source command.
        val preparationStartedAtMs = SystemClock.elapsedRealtime()
        loopFailurePhase = "matrix/initial-stems-with-loop-edit"
        val activation = try {
            runBlocking {
                withTimeout(60_000L) {
                    coroutineScope {
                        val request = async(Dispatchers.Main) {
                            playback.activateStems(stems, loopGestureLease)
                        }
                        while (!request.isCompleted &&
                            playback.state.value.sourceSwitchState !is
                                SourceSwitchState.PreparingStems
                        ) {
                            delay(4L)
                        }
                        if (playback.state.value.sourceSwitchState !is SourceSwitchState.PreparingStems) {
                            val completed = request.await()
                            throw AssertionError(
                                "Stem activation completed before preparation was observed: $completed; " +
                                    "state=${playback.state.value}; " +
                                    "native=${playback.nativeDiagnosticsSnapshotForTest()}; " +
                                    "graph=${playback.graphDiagnostics()}",
                                completed.exceptionOrNull()
                            )
                        }
                        // Let the replacement capture its initial loop, then change the live
                        // loop while its seven decoders are still preparing. The installed
                        // output processor must receive this later range, not the old one.
                        delay(64L)
                        withContext(Dispatchers.Main) {
                            playback.setLoop(20_000L, 25_000L, loopGestureLease)
                        }
                        while (!request.isCompleted) {
                            val transitionState = playback.state.value
                            if (!transitionState.playWhenReady || !transitionState.playing) {
                                outgoingStayedAudible = false
                            }
                            delay(4L)
                        }
                        request.await()
                    }
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Initial loop-matrix stem activation failed or timed out; " +
                    "state=${playback.state.value}; " +
                    "native=${playback.nativeDiagnosticsSnapshotForTest()}; " +
                    "resources=${playback.playbackResourceDiagnosticsForTest()}; " +
                    "graph=${playback.graphDiagnostics()}",
                failure
            )
        }
        println("Loop-matrix initial preparation completed in " +
            "${SystemClock.elapsedRealtime() - preparationStartedAtMs} ms: $activation")
        assertTrue(
            "Stem activation failed: ${activation.exceptionOrNull()?.message}; " +
                "diagnostics=${playback.graphDiagnostics()}",
            activation.isSuccess
        )
        assertTrue("Outgoing native owner stopped during handoff", outgoingStayedAudible)
        waitUntil(8_000L) {
            playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                playback.state.value.sourceSwitchState is SourceSwitchState.Stems &&
                playback.state.value.playing
        }
        val installedControls = playback.graphDiagnostics()
        assertTrue(installedControls.requestedLoopStartMs == 20_000L)
        assertTrue(installedControls.requestedLoopEndMs == 25_000L)
        waitUntil(8_000L) {
            playback.state.value.loopAvailable && directLoopAdoptedForCurrentOwner()
        }
        onMain { playback.setLoop(null, null, loopGestureLease) }

        // Exercise the owner's highest-risk gesture pattern: reverse the
        // Original/Stems envelope before it settles while also changing tuning
        // and transport. The last request must win without invalid PCM samples
        // or a stranded transitional state.
        repeat(8) { index ->
            loopFailurePhase = "matrix/source-reversal-$index"
            onMain {
                playback.switchToOriginal(loopGestureLease)
                playback.setTempoAndPitch(
                    tempo = listOf(.5f, 1f, 1.35f, 2f)[index % 4],
                    semitones = (index % 5) - 2,
                    controlLease = loopGestureLease
                )
                playback.seekTo((index * 431L) % 4_500L)
            }
            SystemClock.sleep(24L)
            val reversal = try {
                runBlocking {
                    withTimeout(8_000L) {
                        withContext(Dispatchers.Main) {
                            playback.activateStems(stems, loopGestureLease)
                        }
                    }
                }
            } catch (failure: Throwable) {
                throw AssertionError(
                    "Rapid source reversal $index timed out; state=${playback.state.value}; " +
                        "native=${playback.nativeDiagnosticsSnapshotForTest()}; " +
                        "graph=${playback.graphDiagnostics()}",
                    failure
                )
            }
            assertTrue(
                "Rapid source reversal $index failed: ${reversal.exceptionOrNull()}; " +
                    "state=${playback.state.value}; " +
                    "native=${playback.nativeDiagnosticsSnapshotForTest()}; " +
                    "graph=${playback.graphDiagnostics()}",
                reversal.isSuccess
            )
        }
        waitUntil(8_000L) {
            playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                playback.state.value.sourceSwitchState is SourceSwitchState.Stems &&
                playback.state.value.playing
        }
        val stressDiagnostics = playback.graphDiagnostics()
        assertTrue(stressDiagnostics.renderedFrames > 0L)
        assertTrue("Invalid PCM samples: $stressDiagnostics", stressDiagnostics.invalidSamples == 0L)
        android.util.Log.i(
            "StemwiseControlStress",
            JSONObject()
                .put("rapidSourceReversals", 8)
                .put("renderedFrames", stressDiagnostics.renderedFrames)
                .put("transportDiscontinuities", stressDiagnostics.discontinuities)
                .put("invalidSamples", stressDiagnostics.invalidSamples)
                .toString()
        )
        onMain { playback.setTempoAndPitch(1f, 0, loopGestureLease) }

        fun installDirectRange(speed: Float, range: Pair<Long, Long>): NativeRuntimeOwnership {
            loopFailurePhase = "matrix/direct-range=$range/speed=$speed/paused-seek"
            lateinit var receipt: NativeAudioPlaybackSeekReceipt
            onMain {
                playback.pause()
                assertTrue(playback.setTempoAndPitch(speed, 0, loopGestureLease))
                receipt = checkNotNull(playback.seekToWithReceipt(range.first, loopGestureLease))
            }
            waitUntil(4_000L) {
                val state = playback.state.value
                val session = playback.adapter.snapshot()
                val committed = session.publication.committed
                val native = playback.nativeDiagnosticsSnapshotForTest()
                val owner = committed?.owner?.ownership
                !state.playing && state.loopAvailable && seekReceiptIsCompleted(receipt, session) &&
                    owner != null && native != null && session.latestRequest?.exactOwnership == owner &&
                    native?.runtime?.ownership?.let {
                        it.requested == owner && it.accepted == owner && it.committed == owner &&
                            it.acceptedFirstFingerprintSequence > 0L
                    } == true && native.controllerPublicOwnership == owner &&
                    native.nativeRenderReadiness?.ownership == owner &&
                    native.controllerPendingSeekOwnership == null && native.output?.playing == false &&
                    state.positionMs in range.first..(range.first + 250L)
            }
            var accepted = false
            val acceptanceDeadline = SystemClock.elapsedRealtime() + 4_000L
            while (!accepted && SystemClock.elapsedRealtime() < acceptanceDeadline) {
                val rangeLease = checkNotNull(playback.acquireControlLease(song.id))
                onMain {
                    accepted = playback.setLoop(
                        range.first,
                        range.second,
                        rangeLease,
                        transportPositionMs = range.first
                    )
                }
                if (!accepted) SystemClock.sleep(5L)
            }
            assertTrue(
                "Committed owner rejected loop $range: state=${playback.state.value}, " +
                    "native=${playback.nativeDiagnosticsSnapshotForTest()}",
                accepted
            )
            loopFailurePhase = "matrix/direct-range=$range/speed=$speed/adoption"
            onMain { playback.play() }
            var appliedOwner: NativeRuntimeOwnership? = null
            waitUntil(8_000L) {
                val owner = playback.adapter.snapshot().publication.committed?.owner?.ownership
                val runtime = playback.adapter.sourceWaveformDiagnosticsSnapshot()?.runtime
                val position = runtime?.presentedPosition
                val audible = runtime?.audibleControl
                val applied = owner != null && position?.ownership == owner && audible?.ownership == owner &&
                    audible.actualTempo == speed && audible.actualPitchSemitones == 0f &&
                    position.loopPolicyRevision > 0L && directLoopAdoptedForCurrentOwner()
                if (applied) appliedOwner = owner
                applied
            }
            return checkNotNull(appliedOwner)
        }

        // Clear an already queued B, then pause before changing source. Resume must retire the
        // old loop continuation and make audible progress with the final source and play intent.
        for (expectedSource in listOf(PlaybackSourceMode.ORIGINAL, PlaybackSourceMode.STEMS)) {
            android.util.Log.i("StemwiseLoopMatrix", "clear-pause-source=$expectedSource")
            val boundaryOwner = installDirectRange(1f, 1_000L to 2_000L)
            loopFailurePhase = "matrix/clear-pause-source=$expectedSource/queued-boundary"
            val boundaryDeadline = SystemClock.elapsedRealtime() + 6_000L
            var boundaryQueued = false
            val physicalRevision = checkNotNull(playback.debug.loopBoundaryObservation()?.first
                ?.takeIf { it.ownership == boundaryOwner }).loopPolicyRevision
            fun boundaryIsQueued(): Boolean {
                val started = System.nanoTime()
                val observation = playback.debug.loopBoundaryObservation() ?: return false
                val position = observation.first ?: return false
                val output = observation.second ?: return false
                val owner = boundaryOwner
                if (playback.adapter.snapshot().publication.committed?.owner?.ownership != owner) return false
                if (position.ownership != owner || output.lastPresentedGeneration != owner.generation ||
                    output.lastPresentedSelectionToken != owner.selectionToken ||
                    position.loopPolicyRevision != physicalRevision || !output.effectiveRenderPlaying) return false
                val ageFrames = ((System.nanoTime() - started) * 48L + 999_999L) / 1_000_000L
                val window = queuedLoopBoundaryWindow(
                    output.backend == app.stemwise.playback.engine.NativeAudioBackend.AUDIO_TRACK,
                    output.callbackFrames - position.lastOutputFrame - 1L, output.queuedFrames,
                    output.outputBufferSizeFrames.toLong(), output.framesPerBurst.toLong(),
                    app.stemwise.playback.engine.NativeAudioTrackFeeder.BLOCK_FRAMES.toLong(), ageFrames)
                return window.contains(2_000L * 48L - position.lastTimelineFrame)
            }
            val raceLease = checkNotNull(playback.acquireControlLease(song.id))
            var clearAccepted = false
            while (!boundaryQueued && SystemClock.elapsedRealtime() < boundaryDeadline) {
                if (boundaryIsQueued()) onMain {
                    if (boundaryIsQueued()) {
                        boundaryQueued = true
                        clearAccepted = playback.setLoop(null, null, raceLease)
                        playback.pause()
                        if (expectedSource == PlaybackSourceMode.ORIGINAL) playback.switchToOriginal(raceLease)
                    }
                }
                if (!boundaryQueued) SystemClock.sleep(1L)
            }
            assertTrue("No queued loop boundary before $expectedSource pause", boundaryQueued)
            assertTrue("Boundary clear rejected before $expectedSource pause", clearAccepted)
            if (expectedSource == PlaybackSourceMode.STEMS) {
                loopFailurePhase = "matrix/clear-pause-source=STEMS/paused-preparation"
                val prepared = try {
                    runBlocking {
                        withTimeout(8_000L) {
                            withContext(Dispatchers.Main) {
                                playback.activateStems(stems, raceLease)
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    throw AssertionError(
                        "Paused stem preparation timed out; state=${playback.state.value}; " +
                            "native=${playback.nativeDiagnosticsSnapshotForTest()}; " +
                            "resources=${playback.playbackResourceDiagnosticsForTest()}; " +
                            "graph=${playback.graphDiagnostics()}",
                        failure
                    )
                }
                assertTrue("Paused stem preparation failed: ${prepared.exceptionOrNull()}", prepared.isSuccess)
            }
            waitUntil(4_000L) {
                playback.state.value.let { !it.playing && !it.playWhenReady }
            }
            val paused = checkNotNull(playback.nativeDiagnosticsSnapshotForTest())
            val pausedPosition = checkNotNull(paused.runtime.presentedPosition)
            val renderedBeforeResume = playback.graphDiagnostics().renderedFrames
            loopFailurePhase = "matrix/clear-pause-source=$expectedSource/linear-resume"
            onMain { playback.play() }
            waitUntil(8_000L) {
                val state = playback.state.value
                val position = playback.nativeDiagnosticsSnapshotForTest()?.runtime?.presentedPosition
                val sourceSettled = when (expectedSource) {
                    PlaybackSourceMode.ORIGINAL -> state.sourceSwitchState is SourceSwitchState.Original
                    PlaybackSourceMode.STEMS -> state.sourceSwitchState is SourceSwitchState.Stems
                }
                state.playWhenReady && state.playing && !state.ended && state.error == null &&
                    state.sourceMode == expectedSource && sourceSettled &&
                    position != null && position.ownership == pausedPosition.ownership &&
                    position.callbackSequence > pausedPosition.callbackSequence &&
                    position.lastTimelineFrame > maxOf(pausedPosition.lastTimelineFrame, 2_000L * 48L) +
                        4_800L && playback.graphDiagnostics().renderedFrames > renderedBeforeResume
            }
            assertTrue(
                "Cleared loop returned after $expectedSource resume",
                playback.graphDiagnostics().requestedLoopStartMs == null &&
                    playback.graphDiagnostics().requestedLoopEndMs == null
            )
            android.util.Log.i("StemwiseLoopMatrix", "clear-pause-source-complete=$expectedSource")
        }

        val matrix = mutableListOf<JSONObject>()
        for (speed in listOf(.5f, 1f, 2f)) {
            for (range in listOf(0L to 100L, 1_000L to 2_000L)) {
                installDirectRange(speed, range)
                val wraps = awaitDirectWraps(
                    range.first,
                    range.second,
                    minimumWraps = 2,
                    timeoutMs = 8_000L
                )
                matrix += JSONObject()
                    .put("speed", speed)
                    .put("startMs", range.first)
                    .put("endMs", range.second)
                    .put("observedDirectWraps", wraps)
            }
        }

        installDirectRange(2f, 5_900L to 6_000L)
        awaitDirectWraps(5_900L, 6_000L, minimumWraps = 2, timeoutMs = 8_000L)

        val hundredWrapStartRealtimeMs = SystemClock.elapsedRealtime()
        installDirectRange(2f, 1_000L to 1_100L)
        val hundredWraps = awaitDirectWraps(
            1_000L,
            1_100L,
            minimumWraps = 100,
            timeoutMs = 45_000L
        )
        val hundredWrapElapsedMs =
            SystemClock.elapsedRealtime() - hundredWrapStartRealtimeMs
        android.util.Log.i(
            "StemwiseLoopQualification",
            "100-wrap diagnostics before assertions: $hundredWraps, " +
                "elapsedMs=$hundredWrapElapsedMs"
        )
        assertTrue(hundredWraps >= 100)
        assertTrue(
            "100 wraps completed before 100 real loop traversals were possible: " +
                "elapsedMs=$hundredWrapElapsedMs, diagnostics=$hundredWraps",
            hundredWrapElapsedMs >= 3_500L
        )

        val report = JSONObject()
            .put("device", android.os.Build.MODEL)
            .put("matrix", matrix)
            .put(
                "hundredWraps",
                JSONObject()
                    .put("wraps", hundredWraps)
                    .put("elapsedMs", hundredWrapElapsedMs)
            )
        android.util.Log.i("StemwiseLoopQualification", report.toString())
        File(
            requireNotNull(application.getExternalFilesDir(null)),
            "loop-qualification.json"
        ).writeText(report.toString(2))

        onMain {
            playback.setLoop(null, null, loopGestureLease)
            playback.setTempoAndPitch(1f, 0, loopGestureLease)
            playback.switchToOriginal(loopGestureLease)
            playback.pause()
            playback.state.value.songId?.let(playback::unload)
        }
    }
}
