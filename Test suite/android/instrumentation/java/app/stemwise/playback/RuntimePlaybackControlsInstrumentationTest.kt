package app.stemwise.playback

import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.stemwise.StemwiseApplication
import app.stemwise.analysis.projectImportedMetronomeTimeline
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.data.StemAssetEntity
import app.stemwise.data.SeparationSetEntity
import app.stemwise.enrichment.EnrichmentContractValidator
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.engine.NativeRuntimeMetronomeMode
import app.stemwise.playback.engine.NativeRuntimeSourceMode
import app.stemwise.playback.engine.EngineSourceMode
import app.stemwise.playback.engine.PlaybackStemPackagePhase
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class RuntimePlaybackControlsInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application =
        instrumentation.targetContext.applicationContext as StemwiseApplication
    private val ownedSettingsSongs = linkedMapOf<SongPlaybackSettingsIdentity, Song>()
    private val playback
        get() = application.services.audioPlayback

    @get:Rule
    val disposablePlaybackTarget: org.junit.rules.TestRule = DisposablePlaybackTargetRule()

    @get:Rule
    val audioHealthAssertions = ErrorCollector()

    @Before
    fun isolatePlaybackBeforeTest() {
        application.admitCurrentPlaybackLibrarySnapshot()
        isolateAndCleanOwnedState()
    }

    @After
    fun isolatePlaybackAfterTest() {
        if (isNativeFatalQualification() && playback.processRestartRequiredAfterTaskRemoval()) return
        isolateAndCleanOwnedState()
    }

    @Test
    fun neverPlayedFrameZeroSourceWaveformUsesThePreparedMixAcrossTheWholeFineWindow() = runBlocking {
        val duration = 12_000L
        val directory = File(application.cacheDir, "source-waveform-paused-zero").apply { check(mkdirs()) }
        val original = File(directory, "original.wav").also { writeSineWave(it, duration, 220.0) }
        val vocals = File(directory, "vocals.wav").also { writeSineWave(it, duration, 330.0) }
        val fixture = song("source-waveform-paused-zero", original, duration, hasStems = true)
        val parts = listOf(stemAsset("vocals", vocals, duration), remainingAsset(vocals, duration, 440.0))
        onMain { playback.selectSong(fixture, autoplay = false, positionMs = 0L) }
        waitUntil(8_000L) { playback.state.value.songId == fixture.id && !playback.state.value.loading }
        val command = withContext(Dispatchers.Main) { checkNotNull(playback.acquireControlLease(fixture.id)) }
        assertTrue(withContext(Dispatchers.Main) { playback.activateStems(parts, command) }.isSuccess)
        var prepared: app.stemwise.playback.engine.PausedPreparedStemMixLease? = null
        withTimeout(8_000L) {
            while (prepared == null) {
                prepared = withContext(Dispatchers.Main) { playback.acquireControlLease(fixture.id)?.pausedPreparedStemMix }
                if (prepared == null) delay(20L)
            }
        }
        withContext(Dispatchers.Main) {
            assertTrue(playback.commitPausedPreparedStemMix(checkNotNull(prepared), "vocals", .5f, false, true))
        }
        suspend fun window(): app.stemwise.playback.engine.SourceWaveformWindow {
            var result: app.stemwise.playback.engine.SourceWaveformWindow? = null
            withTimeout(10_000L) {
                while (result == null) {
                    val key = withContext(Dispatchers.Main) { playback.sourceWaveformIdentity() }
                    if (key != null) result = playback.readSourceWaveformWindow(key, 3_000L)?.also {
                        assertSourceWaveformCoverage(it, key, 0L, 8_000L, 1_600)
                    }
                    if (result == null) delay(20L)
                }
            }
            return checkNotNull(result)
        }
        val audible = window()
        assertTrue(audible.peaks.any { it > 0f })
        assertFalse(playback.state.value.playWhenReady)
        assertFalse(playback.state.value.playing)
        assertTrue(playback.nativeDiagnosticsSnapshotForTest()?.runtime?.audibleControl?.actualSourceMode !=
            app.stemwise.playback.engine.NativeRuntimeSourceMode.STEMS)
        withContext(Dispatchers.Main) {
            assertTrue(playback.commitPausedPreparedStemMix(checkNotNull(prepared), "vocals", .5f, true, true))
        }
        assertNull(playback.readSourceWaveformWindow(audible.identity, 3_000L))
        assertTrue(window().peaks.all { it == 0f })
    }

    @Test
    fun sourceWaveformsReadTheActualPhaseAwareMixAndRejectRetiredOwnersAndGains() = runBlocking {
        val duration = 12_000L
        val directory = File(application.cacheDir, "source-waveform-known-pcm").apply { mkdirs() }
        val original = File(directory, "original.wav")
        writeSineWave(original, duration, 220.0)
        fun constant(name: String, amplitude: Float): File {
            val file = File(directory, name)
            val frames = (duration * 48L).toInt()
            if (file.extension == "wv") {
                PlaybackContentFixture.writeFloat32WavPack(application, file, frames) {
                    repeat(frames) {
                        writeInt(Integer.reverseBytes(amplitude.toRawBits()))
                        writeInt(Integer.reverseBytes((-amplitude).toRawBits()))
                    }
                }
            } else {
                app.stemwise.integration.MergerOracleFixture.writeWave(file,
                    IntArray(frames * 2) { if (it and 1 == 0) (amplitude * 32768).toInt()
                        else (-amplitude * 32768).toInt() }, 16)
            }
            return file
        }
        val vocals = stemAsset("vocals", constant("vocals.wav", .25f), duration)
        val remaining = stemAsset("remaining", constant("remaining.wv", -.25f), duration)
        val fixture = song("source-waveform-known-pcm", original, duration, hasStems = true)
        suspend fun waveform(gains: List<Float>): app.stemwise.playback.engine.SourceWaveformWindow {
            var result: app.stemwise.playback.engine.SourceWaveformWindow? = null
            withTimeout(10_000L) {
                while (result == null) {
                    val key = withContext(Dispatchers.Main) { playback.sourceWaveformIdentity() }
                    if (key?.sourceMode == app.stemwise.playback.engine.NativeRuntimeSourceMode.STEMS &&
                        key.stemGains == gains) result = playback.readSourceWaveformWindow(key, 2_000L, 100L)?.also {
                            assertSourceWaveformCoverage(it, key, 1_900L, 2_100L, 40)
                        }
                    if (result == null) delay(20L)
                }
            }
            return requireNotNull(result)
        }
        onMain { playback.startQueue(listOf(fixture), shuffle = false, autoplay = true) }
        waitUntil(8_000L) { playback.state.value.playing && playback.state.value.songId == fixture.id }
        val lease = withContext(Dispatchers.Main) { checkNotNull(playback.acquireControlLease(fixture.id)) }
        assertTrue(withContext(Dispatchers.Main) { playback.activateStems(listOf(vocals, remaining), lease) }.isSuccess)
        val mixed = waveform(listOf(1f, 1f))
        assertTrue("Opposite samples must cancel before RMS", mixed.peaks.all { it == 0f })
        withContext(Dispatchers.Main) { assertTrue(playback.commitStemMix("vocals", 1f, false, true, lease)) }
        assertNull(playback.readSourceWaveformWindow(mixed.identity, 2_000L, 100L))
        val solo = waveform(listOf(1f, 0f))
        assertTrue("Solo waveform must come from the actual vocals samples", solo.peaks.all { abs(it - .25f) < .000001f })
        withContext(Dispatchers.Main) { assertTrue(playback.commitStemMix("vocals", .5f, false, true, lease)) }
        assertNull(playback.readSourceWaveformWindow(solo.identity, 2_000L, 100L))
        val quieter = waveform(listOf(.5f, 0f))
        assertTrue(quieter.peaks.all { abs(it - .125f) < .000001f })
        onMain { playback.pause() }
        waitUntil(3_000L) { !playback.state.value.playing }
        assertEquals(quieter.peaks, waveform(listOf(.5f, 0f)).peaks)
        onMain { playback.play() }
        withContext(Dispatchers.Main) { assertTrue(playback.commitStemMix("vocals", .5f, true, true, lease)) }
        assertTrue(waveform(listOf(0f, 0f)).peaks.all { it == 0f })
        onMain { playback.selectSong(fixture.copy(id = "source-waveform-next-owner"), autoplay = false) }
        assertNull(playback.readSourceWaveformWindow(quieter.identity, 2_000L, 100L))
    }

    @Test
    fun instrumentationIsolationRestoresRepeatAndShuffleBaselines() {
        for (mode in listOf("One", "All", "All", "One")) {
            onMain {
                assertTrue(playback.setRepeatMode(mode))
                assertTrue(playback.setShuffle(true))
            }
            playback.resetForInstrumentationIsolation(instrumentation)
            assertEquals("Off", playback.state.value.repeatMode)
            assertFalse(playback.state.value.shuffleEnabled)
        }
    }

    @Test
    fun partsDeletionRetiresRealConsumersAndRestoresOriginalPositionAndTransportOnSuccessAndFailure() = runBlocking {
        val authority = partsDeletionRootAuthority()
        for ((stemsSelected, playing, failMutation) in listOf(
            Triple(false, false, false), Triple(true, false, false), Triple(true, true, true)
        )) {
            resetPlayback()
            val directory = File(application.cacheDir, "runtime-delete-parts-$stemsSelected-$playing").apply { mkdirs() }
            val original = File(directory, "original.wav")
            val part = File(directory, "vocals.wav")
            writeSineWave(original, 16_000, 220.0); writeSineWave(part, 16_000, 440.0)
            val song = song("delete-parts-$stemsSelected-$playing", original, 16_000, hasStems = true)
            val stems = listOf(stemAsset("vocals", part, 16_000), remainingAsset(part, 16_000, 330.0))
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
            waitUntil(8_000) { playback.state.value.songId == song.id && !playback.state.value.loading }
            if (stemsSelected) {
                val lease = checkNotNull(playback.acquireControlLease(song.id))
                assertTrue(withTimeout(20_000) { withContext(Dispatchers.Main) { playback.activateStems(stems, lease) } }.isSuccess)
            }
            onMain { assertTrue(playback.seekTo(4_321)) }
            waitUntil(5_000) { abs(playback.state.value.positionMs - 4_321) <= 50 }
            if (playing) { onMain { playback.play() }; waitUntil(5_000) { playback.state.value.playing } }
            val position = currentPlayerPosition()
            val oldToken = checkNotNull(playback.acquireControlLease(song.id)).ownerEpoch
            var mutated = false
            val failure = IllegalStateException("CONTROLLED_PART_WRITE_FAILURE")
            val result = withContext(Dispatchers.Default) {
                playback.deletePartsPreservingPlayback(authority, song.id) { admitted ->
                    admitted()
                    val resources = playback.playbackResourceDiagnosticsForTest()
                    assertEquals("Provider mutation began with live consumers", 0, resources.totalDecoderLeaseCount)
                    mutated = true
                    if (failMutation) Result.failure<Unit>(failure) else {
                        stems.map { it.localPath }.distinct().forEach { uri ->
                            check(android.provider.DocumentsContract.deleteDocument(application.contentResolver, android.net.Uri.parse(uri)))
                        }
                        Result.success(Unit)
                    }
                }
            }
            assertTrue(mutated)
            if (failMutation) assertSame(failure, result.exceptionOrNull()) else assertTrue(result.isSuccess)
            waitUntil(8_000) {
                val state = playback.state.value
                state.songId == song.id && !state.loading && state.sourceMode == PlaybackSourceMode.ORIGINAL &&
                    state.playWhenReady == playing && (!playing || state.playing)
            }
            val lease = checkNotNull(playback.acquireControlLease(song.id))
            assertTrue("Original must reopen with a new exact owner", lease.ownerEpoch != oldToken)
            if (!playing) assertTrue("Paused deletion lost position: ${playback.state.value}",
                abs(playback.state.value.positionMs - position) <= 50)
            assertTrue("Original was removed", checkNotNull(application.contentResolver.openInputStream(android.net.Uri.parse(song.sourcePath))).use { it.read() >= 0 })
            if (failMutation) stems.forEach { stem ->
                assertTrue("Failed deletion removed prior parts", checkNotNull(application.contentResolver.openInputStream(android.net.Uri.parse(stem.localPath))).use { it.read() >= 0 })
            }
            if (playing) waitUntil(3_000) {
                val native = playback.nativeDiagnosticsSnapshotForTest()
                native?.runtime?.ownership?.committed?.selectionToken == lease.ownerEpoch &&
                    native.output?.pcmLevelSelectionToken == lease.ownerEpoch &&
                    (native.output?.pcmLevelFrames ?: 0L) > 0L && (native.output?.pcmLevelMeanSquare ?: 0f) > 0f
            }
            onMain { playback.pause(); playback.unload(song.id) }
        }
    }

    @Test
    fun selectingAnotherSongDuringPartsDeletionCannotBeOverriddenByItsLateRestoration() = runBlocking {
        val authority = partsDeletionRootAuthority()
        val directory = File(application.cacheDir, "runtime-delete-parts-replacement").apply { mkdirs() }
        val original = File(directory, "original.wav"); writeSineWave(original, 16_000, 220.0)
        val a = song("delete-parts-A", original, 16_000)
        val b = song("delete-parts-B", original, 16_000)
        onMain { playback.startQueue(listOf(b, a), startId = a.id, shuffle = false, autoplay = false) }
        waitUntil(8_000) { playback.state.value.songId == a.id && !playback.state.value.loading }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val deletion = async(Dispatchers.Default) {
            playback.deletePartsPreservingPlayback(authority, a.id) { admitted ->
                admitted(); entered.complete(Unit)
                withTimeout(10_000) { release.await() }
                Result.success(Unit)
            }
        }
        try {
            withTimeout(10_000) { entered.await() }
            onMain { assertTrue(playback.load(b, autoplay = false)) }
            waitUntil(8_000) { playback.state.value.songId == b.id && !playback.state.value.loading }
            val tokenB = checkNotNull(playback.acquireControlLease(b.id)).ownerEpoch
            release.complete(Unit)
            assertTrue(withTimeout(10_000) { deletion.await() }.isSuccess)
            assertEquals(b.id, playback.state.value.songId)
            assertEquals(tokenB, playback.acquireControlLease(b.id)?.ownerEpoch)
            assertFalse(playback.state.value.playWhenReady)
            waitUntil(8_000) { a.id in playback.preparedQueueMediaIds() }
            assertEquals("Replanning after deletion replaced the newer selection", tokenB,
                playback.acquireControlLease(b.id)?.ownerEpoch)
        } finally {
            release.complete(Unit)
            deletion.cancelAndJoin()
        }
    }

    @Test
    fun partsDeletionCannotCancelANewerSelectionAlreadyResolvingBeforeAdmission() = runBlocking {
        val authority = partsDeletionRootAuthority()
        val directory = File(application.cacheDir, "runtime-delete-parts-resolving").apply { mkdirs() }
        val original = File(directory, "original.wav"); writeSineWave(original, 16_000, 220.0)
        val a = song("delete-parts-resolving-A", original, 16_000)
        val b = song("delete-parts-resolving-B", original, 16_000)
        onMain { playback.startQueue(listOf(a), shuffle = false, autoplay = false) }
        waitUntil(8_000) { playback.state.value.songId == a.id && !playback.state.value.loading }
        val verify = checkNotNull(playback.debug.verifyPlaybackSong)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            playback.debug.verifyPlaybackSong = { requested ->
                if (requested.id == b.id) {
                    entered.complete(Unit)
                    try { withTimeout(10_000) { release.await() } }
                    catch (error: kotlinx.coroutines.CancellationException) { cancelled.complete(Unit); throw error }
                }
                verify(requested)
            }
            onMain { assertTrue(playback.load(b, autoplay = false)) }
            withTimeout(10_000) { entered.await() }
            val result = withContext(Dispatchers.Default) {
                playback.deletePartsPreservingPlayback(authority, a.id) { admitted ->
                    admitted()
                    assertFalse("Quiescing outgoing A cancelled the newer accepted B resolution", cancelled.isCompleted)
                    Result.success(Unit)
                }
            }
            assertTrue(result.isSuccess)
            assertFalse("Deletion completion cancelled the newer accepted B resolution", cancelled.isCompleted)
            release.complete(Unit)
            withTimeout(10_000) { playback.awaitManualSelectionResolutionForTest() }
            waitUntil(8_000) { playback.state.value.songId == b.id && !playback.state.value.loading }
            assertFalse(playback.state.value.playWhenReady)
        } finally {
            release.complete(Unit)
            playback.debug.verifyPlaybackSong = verify
        }
    }

    private suspend fun partsDeletionRootAuthority(): app.stemwise.data.RootAuthority = withContext(Dispatchers.IO) {
        val roots = application.services.libraryRootManager
        if (roots.captureHandle() == null) {
            val tree = app.stemwise.qa.GoldenQaDocumentsProvider.treeUri
            application.grantUriPermission(application.packageName, tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
        }
        checkNotNull(roots.captureHandle()).let { app.stemwise.data.RootAuthority(it.libraryId, it.treeUri.toString(), it.epoch) }
    }

    @Test
    fun localPlaybackSnapshotExportsActualPauseAndEosOwnershipAndNativeOrdering() = runBlocking {
        val authority = partsDeletionRootAuthority()
        val directory = File(application.cacheDir, "runtime-playback-snapshot").apply { mkdirs() }
        val original = File(directory, "original.wav"); writeSineWave(original, 1_400, 220.0)
        val song = song("playback-snapshot", original, 1_400)
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
        waitUntil(8_000) { playback.state.value.playing && playback.state.value.positionMs > 100 }
        val token = checkNotNull(playback.acquireControlLease(song.id)).ownerEpoch
        onMain { playback.pause() }
        waitUntil(5_000) { playback.nativeDiagnosticsSnapshotForTest()?.output?.playing == false }
        val paused = JSONObject(playback.exportPlaybackSnapshot())
        assertEquals(authority.libraryId, paused.getJSONObject("root").getString("libraryId"))
        assertEquals(authority.epoch, paused.getJSONObject("root").getLong("epoch"))
        assertEquals(token, paused.getJSONObject("committed").getLong("selectionToken"))
        assertFalse(paused.getJSONObject("requested").getBoolean("playWhenReady"))
        assertTrue(paused.getBoolean("engineEvidenceAvailable"))
        onMain { playback.play() }
        waitUntil(8_000) { playback.state.value.ended }
        val snapshot = playback.exportPlaybackSnapshot()
        val ended = JSONObject(snapshot)
        val ordering = ended.getJSONObject("nativeEndOrdering")
        assertFalse(ordering.getBoolean("completeHistory"))
        assertEquals(0, ordering.getJSONArray("missingKinds").length())
        val events = ordering.getJSONArray("events")
        var previous = 0L
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            assertTrue(event.getLong("sequence") > previous)
            previous = event.getLong("sequence")
            assertEquals(token, event.getJSONObject("ownership").getLong("selectionToken"))
        }
        assertTrue(ended.getJSONArray("eventTail").length() <= 64)
        val provider = app.stemwise.qa.GoldenQaDocumentsProvider.AUTHORITY
        val parent = android.provider.DocumentsContract.buildDocumentUri(provider, "root")
        val destination = checkNotNull(android.provider.DocumentsContract.createDocument(application.contentResolver,
            parent, "application/json", "playback-fixture-snapshot.json"))
        try {
            assertTrue(app.stemwise.ui.writePlaybackSnapshot(application.contentResolver, destination, snapshot).isSuccess)
            val readback = checkNotNull(application.contentResolver.openInputStream(destination)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertEquals(snapshot, readback)
            assertEquals(token, JSONObject(readback).getJSONObject("committed").getLong("selectionToken"))
        } finally { check(android.provider.DocumentsContract.deleteDocument(application.contentResolver, destination)) }
    }

    @Test
    fun tempoAndKeyWorkWithoutEnrichmentMetadata() {
        resetPlayback()
        val directory = File(application.cacheDir, "runtime-tuning-no-enrichment").apply { mkdirs() }
        val original = File(directory, "original.wav")
        writeSineWave(original, durationMs = 20_000L, frequency = 220.0)
        val song = song("runtime-tuning-no-enrichment", original, durationMs = 20_000L)
        var retainedLease: PlaybackControlLease? = null

        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
            waitUntil(8_000L) { playback.state.value.playing && playback.state.value.positionMs > 100L }
            onMain { retainedLease = playback.acquireControlLease(song.id) }
            val controlLease = requireNotNull(retainedLease)
            val baselineUnderruns = requireNotNull(
                playback.nativeDiagnosticsSnapshotForTest()?.output
            ).processUnderrunCallbackCount

            onMain {
                playback.setTempoAndPitch(2f, 0, controlLease)
                playback.setTempoAndPitch(1f, 0, controlLease)
            }
            SystemClock.sleep(250L)
            assertEquals(1f, playback.graphDiagnostics().requestedTempo, .001f)
            assertEquals(1f, (playback.graphDiagnostics().appliedTempo ?: Float.NaN), .001f)

            onMain { playback.setTempoAndPitch(2f, 5, controlLease) }
            waitUntil(2_000L) {
                val diagnostic = playback.graphDiagnostics()
                diagnostic.requestedTempo == 2f &&
                    diagnostic.requestedSemitoneShift == 5 &&
                    abs((diagnostic.appliedTempo ?: Float.NaN) - 2f) < .001f &&
                    abs(
                        (diagnostic.appliedPitchRatio ?: Float.NaN) -
                            2.0.pow(5.0 / 12.0).toFloat()
                    ) < .001f
            }
            SystemClock.sleep(250L)
            val fastStartPosition = currentPlayerPosition()
            val fastStartTime = SystemClock.elapsedRealtime()
            SystemClock.sleep(700L)
            val fastElapsed = SystemClock.elapsedRealtime() - fastStartTime
            val fastMediaAdvance = currentPlayerPosition() - fastStartPosition
            assertTrue(
                "2x sink did not advance media time at 2x: advance=$fastMediaAdvance, " +
                    "elapsed=$fastElapsed, diagnostics=${playback.graphDiagnostics()}; " +
                    "native=${playback.nativeDiagnosticsSnapshotForTest()}",
                fastMediaAdvance >= (fastElapsed * 1.5f).toLong()
            )
            assertEquals(
                "2x produced an output underrun",
                baselineUnderruns,
                requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
                    .processUnderrunCallbackCount
            )

            onMain { playback.setTempoAndPitch(.5f, -3, controlLease) }
            waitUntil(2_000L) {
                val diagnostic = playback.graphDiagnostics()
                abs((diagnostic.appliedTempo ?: Float.NaN) - .5f) < .001f &&
                    diagnostic.requestedSemitoneShift == -3
            }
            SystemClock.sleep(250L)
            val slowStartPosition = currentPlayerPosition()
            val slowStartTime = SystemClock.elapsedRealtime()
            SystemClock.sleep(800L)
            val slowElapsed = SystemClock.elapsedRealtime() - slowStartTime
            val slowMediaAdvance = currentPlayerPosition() - slowStartPosition
            assertTrue(
                "0.5x sink did not advance media time at 0.5x: advance=$slowMediaAdvance, " +
                    "elapsed=$slowElapsed, diagnostics=${playback.graphDiagnostics()}; " +
                    "native=${playback.nativeDiagnosticsSnapshotForTest()}",
                slowMediaAdvance in (slowElapsed * .3f).toLong()..(slowElapsed * .7f).toLong()
            )
            assertEquals(
                "0.5x produced an output underrun",
                baselineUnderruns,
                requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
                    .processUnderrunCallbackCount
            )
        } finally {
            onMain {
                retainedLease?.let { playback.setTempoAndPitch(1f, 0, it) }
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun completeImportedRhythmDrivesLateSongNativeMetronomeOutput() {
        resetPlayback()
        val payload = JSONObject(
            application.assets.open("qa/enrichment/full-song-enrichment-v2.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        )
        EnrichmentContractValidator.validate(payload, hasCover = true)
        val timeline = projectImportedMetronomeTimeline(
            payload.getJSONObject("analysis").getJSONObject("rhythm")
        )
        assertEquals(25, timeline.beats.size)
        assertEquals(11_841L, timeline.beats.last().timeMs)
        assertEquals(10_889L, timeline.beats.last { it.isDownbeat }.timeMs)

        val directory = File(application.cacheDir, "runtime-complete-json-metronome")
            .apply { mkdirs() }
        val original = File(directory, "silent-original.wav")
        writeSineWave(original, durationMs = 12_000L, frequency = 0.0)
        val song = song("runtime-complete-json-metronome", original, 12_000L).copy(
            bpm = 128f,
            meter = "4/4"
        )
        var retainedLease: PlaybackControlLease? = null

        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
            waitUntil(8_000L) {
                val native = playback.nativeDiagnosticsSnapshotForTest()
                val owner = native?.runtime?.ownership?.committed
                playback.state.value.playing && owner != null &&
                    native.runtime.ownership.requested == owner &&
                    native.runtime.ownership.accepted == owner &&
                    native.output?.playing == true &&
                    native.output.appliedGeneration == owner.generation &&
                    native.output.lastPresentedGeneration == owner.generation
            }
            onMain { retainedLease = playback.acquireControlLease(song.id) }
            val controlLease = requireNotNull(retainedLease)
            val before = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
            var accepted = false
            onMain {
                accepted = playback.setMetronome(
                    enabled = true,
                    bpm = 128f,
                    songSynced = true,
                    beatEvents = timeline.beats,
                    meterSegments = timeline.meterSegments,
                    subdivision = 1,
                    numerator = 4,
                    denominator = 4,
                    controlLease = controlLease
                )
            }
            assertTrue("Imported metronome profile was rejected", accepted)
            waitUntil(2_000L) {
                val current = playback.nativeDiagnosticsSnapshotForTest()
                playback.state.value.metronomeEnabled &&
                    playback.state.value.metronomeSongSynced &&
                    current != null &&
                    current.runtime.counters.beatGridUpdatesPublished >
                        before.runtime.counters.beatGridUpdatesPublished &&
                    current.runtime.counters.rejectedBeatGridUpdates ==
                        before.runtime.counters.rejectedBeatGridUpdates
            }

            onMain { playback.seekTo(10_600L) }
            waitUntil(2_000L) { currentPlayerPosition() >= 10_550L }
            val deadline = SystemClock.elapsedRealtime() + 900L
            var maximumOutputRms = 0f
            while (SystemClock.elapsedRealtime() < deadline) {
                maximumOutputRms = maxOf(maximumOutputRms, playback.graphDiagnostics().outputRms)
                SystemClock.sleep(4L)
            }
            assertTrue(
                "The late imported downbeat produced no native click output: rms=$maximumOutputRms",
                maximumOutputRms > .002f
            )
            assertTrue(currentPlayerPosition() >= 10_889L)
        } finally {
            onMain {
                retainedLease?.let {
                    playback.setMetronome(
                        false,
                        120f,
                        songSynced = false,
                        controlLease = it
                    )
                }
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun repeatedSourceRequestsSettleAndReuseThePreparedGraph() {
        val directory = File(application.cacheDir, "runtime-source-repeats").apply { mkdirs() }
        val audio = File(directory, "tone.wav")
        val durationMs = 30_000L
        writeSineWave(audio, durationMs, 220.0)
        val song = song("runtime-source-repeats", audio, durationMs, hasStems = true)
        val stems = listOf("vocals", "drums", "bass", "guitar", "piano", "remaining")
            .map { id ->
                if (id == "remaining") remainingAsset(audio, durationMs, 220.0)
                else stemAsset(id, audio, durationMs)
            }
        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
            waitUntil(8_000L) {
                playback.state.value.songId == song.id && !playback.state.value.loading &&
                    playback.acquireControlLease(song.id) != null
            }
            val lease = requireNotNull(playback.acquireControlLease(song.id))
            fun activate() = runBlocking {
                withTimeout(12_000L) {
                    withContext(Dispatchers.Main) { playback.activateStems(stems, lease) }
                }
            }
            for (playing in listOf(false, true)) {
                if (playing) onMain { playback.play() }
                val first = activate()
                assertTrue("Initial source activation, playing=$playing: $first", first.isSuccess)
                waitUntil(2_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
                val builds = playback.stemPreparationDiagnostics().foregroundBuildCount
                repeat(3) {
                    assertTrue(activate().isSuccess)
                    assertTrue("Duplicate Stems overwrote its settled state",
                        playback.state.value.sourceSwitchState is SourceSwitchState.Stems)
                }
                repeat(3) {
                    onMain { repeat(8) { assertTrue(playback.switchToOriginal(lease)) } }
                    waitUntil(2_000L) {
                        playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL &&
                            playback.state.value.sourceSwitchState is SourceSwitchState.Original
                    }
                    assertTrue(activate().isSuccess)
                    waitUntil(2_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
                }
                assertEquals("Hot source switches rebuilt the stem graph", builds,
                    playback.stemPreparationDiagnostics().foregroundBuildCount)
                onMain { playback.switchToOriginal(lease) }
                waitUntil(2_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Original }
            }
        } finally {
            resetPlayback()
            directory.deleteRecursively()
        }
    }

    @Test
    fun staleSeekCannotRetireTheReplacementOwnersScrub() {
        val directory = File(application.cacheDir, "runtime-stale-seek-scrub").apply { mkdirs() }
        val original = File(directory, "original.wav")
        writeSineWave(original, 12_000L, 220.0)
        val first = song("stale-seek-first", original, 12_000L)
        val second = first.copy(id = "stale-seek-second", videoId = "stale-seek-second")
        try {
            onMain { playback.startQueue(listOf(first, second), shuffle = false, autoplay = false) }
            waitUntil(8_000L) { playback.state.value.songId == first.id && !playback.state.value.loading }
            val stale = requireNotNull(playback.acquireControlLease(first.id))
            onMain { playback.selectSong(second, autoplay = false) }
            waitUntil(8_000L) { playback.state.value.songId == second.id && !playback.state.value.loading }
            val current = requireNotNull(playback.acquireControlLease(second.id))
            onMain {
                assertTrue(playback.beginScrub(current))
                playback.previewScrub(3_000L)
                assertFalse(playback.seekTo(1_000L, stale))
                playback.previewScrub(4_000L)
                playback.commitScrub(5_000L)
            }
            waitUntil(5_000L) { playback.state.value.positionMs == 5_000L }
            assertEquals(second.id, playback.state.value.songId)
            assertFalse(playback.state.value.playing)
            onMain {
                val receipt = requireNotNull(playback.seekToWithReceipt(7_000L, current))
                assertEquals(7_000L, receipt.positionMs)
                assertTrue(receipt.seekRevision > 0L)
                assertEquals(7_000L, playback.state.value.positionMs)
                assertNull(playback.seekToWithReceipt(8_000L, stale))
                assertEquals(7_000L, playback.state.value.positionMs)
            }
        } finally { resetPlayback() }
    }

    @Test
    fun repeatChangesPreserveMidSongPositionAndScrubsRetainDormantLoops() {
        val directory = File(application.cacheDir, "runtime-repeat-loop-seek").apply { mkdirs() }
        val original = File(directory, "original.wav")
        writeSineWave(original, durationMs = 40_000L, frequency = 220.0)
        val song = song("runtime-repeat-loop-seek", original, 40_000L)
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
        waitUntil(8_000L) { playback.state.value.songId == song.id && playback.state.value.playing }
        waitUntil(12_000L) { playback.state.value.loopAvailable }
        var retainedLease: PlaybackControlLease? = null
        onMain { retainedLease = playback.acquireControlLease(song.id) }
        val lease = requireNotNull(retainedLease)
        onMain { playback.seekTo(10_000L) }
        waitUntil(5_000L) { currentPlayerPosition() in 10_000L..12_000L }
        for (mode in listOf("All", "One", "Off", "One", "Off")) {
            val before = currentPlayerPosition()
            onMain { assertTrue(playback.setRepeatMode(mode)) }
            SystemClock.sleep(600L)
            assertTrue("Repeat $mode reset the position", currentPlayerPosition() >= before - 60L)
            assertTrue(playback.state.value.playing)
        }
        onMain { assertTrue(playback.setLoop(8_000L, 10_000L, lease)) }
        for (target in listOf(6_000L, 12_000L)) {
            onMain {
                assertTrue(playback.beginScrub(lease))
                playback.previewScrub(target)
                playback.commitScrub(target)
            }
            waitUntil(5_000L) { currentPlayerPosition() in target..target + 1_000L }
            val before = currentPlayerPosition()
            SystemClock.sleep(400L)
            assertTrue("Scrub outside A/B stopped the head", currentPlayerPosition() > before + 100L)
            assertEquals(8_000L, playback.state.value.loopA)
            assertEquals(10_000L, playback.state.value.loopB)
        }
        onMain {
            assertTrue(playback.beginScrub(lease))
            playback.previewScrub(9_500L)
            playback.commitScrub(9_500L)
        }
        waitUntil(5_000L) { currentPlayerPosition() in 8_000L..9_000L }
        assertTrue(playback.state.value.playing)
        assertEquals(8_000L, playback.state.value.loopA)
        assertEquals(10_000L, playback.state.value.loopB)
        onMain { assertTrue(playback.seekTo(39_000L)) }
        waitUntil(5_000L) { playback.state.value.ended }
        assertEquals(null, playback.state.value.loopA)
        assertEquals(null, playback.state.value.loopB)
    }

    @Test
    fun mediaSessionRelativeSeekUsesSuccessorWhileFacadeCollectorIsBlocked() {
        val directory = File(application.cacheDir, "session-relative-handoff").apply { mkdirs() }
        val firstAudio = File(directory, "first.wav")
        val secondAudio = File(directory, "second.wav")
        writeSineWave(firstAudio, durationMs = 10_000L, frequency = 220.0)
        writeSineWave(secondAudio, durationMs = 30_000L, frequency = 330.0)
        val first = song("session-relative-first", firstAudio, 10_000L)
        val second = song("session-relative-second", secondAudio, 30_000L)
        val held = java.util.concurrent.atomic.AtomicBoolean(false)
        val resumeCollector = kotlinx.coroutines.CompletableDeferred<Unit>()
        var session: app.stemwise.playback.session.AudioPlaybackSessionTransport? = null
        try {
            val priorPreparation = playback.nativeDiagnosticsSnapshotForTest()?.controllerPreparationStages
                ?.maxOfOrNull { it.sequence } ?: 0L
            onMain {
                playback.startQueue(listOf(first, second), shuffle = false, autoplay = false)
                // Start A at the required cursor. A separate early Seek would borrow B's spare
                // decoder bank for a frame-zero builder and turn this into a cold manual fallback.
                assertTrue(playback.selectSong(first, autoplay = false, positionMs = 9_000L))
            }
            waitUntil(8_000L) {
                val committed = playback.adapter.snapshot().publication.committed
                committed?.selection?.songId == first.id && committed.selection.startPositionMs == 9_000L &&
                    committed.transport.presentedPositionMs == 9_000L &&
                    playback.state.value.songId == first.id && !playback.state.value.loading &&
                    playback.nativeDiagnosticsSnapshotForTest()?.runtime?.ownership?.committed == committed.owner.ownership
            }
            waitUntil(8_000L) {
                playback.nativeDiagnosticsSnapshotForTest()?.controllerPreparationStages.orEmpty().any {
                    it.sequence > priorPreparation && it.operation ==
                        app.stemwise.playback.engine.NativeControllerPreparationOperation.SUCCESSOR_PREWARM &&
                        it.stage == "original-primed"
                }
            }
            onMain { assertTrue(playback.play()) }
            var preparedSuccessor: app.stemwise.playback.engine.NativeRuntimeOwnership? = null
            // A likely-next hint and a pre-seek priming record do not establish a physical drain.
            // Freeze the facade only after actual successor PCM is staged for A's current owner.
            waitUntil(8_000L) {
                val committed = playback.adapter.snapshot().publication.committed
                val automatic = playback.nativeDiagnosticsSnapshotForTest()?.runtime?.automaticSuccessor
                val successor = automatic?.successorOwnership
                val ready = committed?.selection?.songId == first.id && automatic != null &&
                    automatic.phase != app.stemwise.playback.engine.NativeRuntimeAutomaticSuccessorPhase.IDLE &&
                    automatic.outgoingOwnership == committed.owner.ownership && successor != null &&
                    successor != committed.owner.ownership && automatic.successorQueuedFrames > 0L &&
                    automatic.firstFingerprintSequence > 0L && !automatic.cancellationDesired
                if (ready) preparedSuccessor = successor
                ready
            }
            onMain {
                assertEquals(first.id, playback.state.value.songId)
                assertTrue(playback.state.value.positionMs >= 9_000L)
                // The real adapter/native owner continues while the public facade retains A9s.
                playback.debug.beforeState = {
                    if (held.compareAndSet(false, true)) resumeCollector.await()
                }
                session = app.stemwise.playback.session.AudioPlaybackSessionTransport(playback)
            }
            waitUntil(12_000L) { held.get() &&
                playback.adapter.snapshot().publication.committed?.let {
                    it.selection.songId == second.id && it.owner.ownership == preparedSuccessor
                } == true }
            onMain {
                assertEquals(first.id, playback.state.value.songId)
                assertTrue(playback.state.value.positionMs >= 9_000L)
                val lease = requireNotNull(playback.adapter.acquireCommandLease(second.id))
                // Establish B's pending cursor without publishing anything through the held facade.
                assertTrue(playback.adapter.pause())
                assertTrue(playback.adapter.seekToIfCurrent(lease, 0L) != null)
                for ((delta, target) in listOf(5_000L to 5_000L, 5_000L to 10_000L, -5_000L to 5_000L)) {
                    session!!.seekBy(delta)
                    assertEquals(target, playback.latestRequestedControlsForTest()?.requestedPositionMs)
                    assertEquals(second.id, playback.selectionPressureDiagnosticsForTest().committedSongId)
                }
                playback.debug.beforeState = null
                resumeCollector.complete(Unit)
            }
            waitUntil(8_000L) { playback.state.value.songId == second.id &&
                abs(playback.state.value.positionMs - 5_000L) <= 50L && !playback.state.value.playing }
        } finally {
            resumeCollector.complete(Unit)
            onMain { playback.debug.beforeState = null; session?.close() }
            resetPlayback()
        }
    }

    @Test
    fun mediaSessionRelativeSeekUsesRestartedPositionForOneAndSingleItemAll() {
        val directory = File(application.cacheDir, "session-relative-restart").apply { mkdirs() }
        val original = File(directory, "original.wav")
        writeSineWave(original, durationMs = 60_000L, frequency = 220.0)
        val song = song("session-relative-restart", original, 60_000L)
        var session: app.stemwise.playback.session.AudioPlaybackSessionTransport? = null
        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
            waitUntil(8_000L) { playback.state.value.songId == song.id && !playback.state.value.loading }
            onMain { session = app.stemwise.playback.session.AudioPlaybackSessionTransport(playback) }
            for (mode in listOf("One", "All")) {
                onMain {
                    assertTrue(playback.setRepeatMode(mode))
                    assertTrue(playback.seekTo(58_000L))
                }
                waitUntil(8_000L) { playback.state.value.positionMs == 58_000L }
                onMain {
                    session!!.seekBy(5_000L)
                    assertEquals(0L, playback.state.value.positionMs)
                    session!!.seekBy(5_000L)
                    assertEquals(5_000L, playback.state.value.positionMs)
                    session!!.seekBy(-5_000L)
                    assertEquals(0L, playback.state.value.positionMs)
                }
            }
        } finally {
            onMain { session?.close() }
            resetPlayback()
        }
    }

    @Test
    fun heldScrubbingInsideALoopKeepsAudiblePcmMoving() {
        val underrunsBefore = checkNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
            .processUnderrunCallbackCount
        fun assertHealthy(stage: String, native: app.stemwise.playback.engine.NativeEngineDiagnosticsSnapshot) {
            val output = checkNotNull(native.output)
            assertEquals("$stage underrun: generation=${output.lastUnderrunGenerationAtRenderDecision}; " +
                "enqueueAgeUs=${output.lastUnderrunSuccessfulEnqueueAgeMicros}; " +
                "reserve=${output.lastUnderrunSuccessfulEnqueueQueueAfterFrames}; " +
                "lowWater=${native.runtime.currentOutputQueueLowWater}; " +
                "Original=${native.runtime.needOriginalData}; Stems=${native.runtime.needStemData}; " +
                "boundary=${native.runtime.loopBoundaryPending}; " +
                "cacheMask=${native.nativeRenderReadiness?.currentSongCacheCompleteLaneMask}",
                underrunsBefore, output.processUnderrunCallbackCount)
        }
        val directory = File(application.cacheDir, "runtime-repeat-loop-seek").apply { mkdirs() }
        val original = File(directory, "held-scrub.wav")
        writeSineWave(original, durationMs = 30_000L, frequency = 220.0)
        val song = song("held-loop-scrub", original, 30_000L, hasStems = true)
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
        waitUntil(12_000L) { playback.state.value.songId == song.id && playback.state.value.loopAvailable }
        val lease = checkNotNull(playback.acquireControlLease(song.id))
        val stems = listOf("bass", "drums", "guitar", "remaining", "piano", "vocals")
            .map { id ->
                if (id == "remaining") remainingAsset(original, 30_000L, 220.0)
                else stemAsset(id, original, 30_000L)
            }
        for ((source, tempo) in listOf(
            PlaybackSourceMode.ORIGINAL to 1f, PlaybackSourceMode.ORIGINAL to 1.35f,
            PlaybackSourceMode.STEMS to 1f, PlaybackSourceMode.STEMS to 1.35f
        )) {
            if (source == PlaybackSourceMode.STEMS && playback.state.value.sourceMode != source) {
                onMain { assertTrue(playback.setLoop(null, null, lease)) }
                val activation = runBlocking {
                    try {
                        withTimeout(12_000L) {
                            withContext(Dispatchers.Main) { playback.activateStems(stems, lease) }
                        }
                    } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                        val snapshot = playback.nativeDiagnosticsSnapshotForTest()
                        throw AssertionError("Activation timed out: ${snapshot?.nativeRenderReadiness}; " +
                            "${snapshot?.controllerStemReadiness}", failure)
                    }
                }
                assertTrue(activation.isSuccess)
                waitUntil(12_000L) {
                    playback.state.value.sourceMode == source && playback.state.value.loopAvailable
                }
            }
            val beforeVariantSeek = playback.nativeDiagnosticsSnapshotForTest()
            onMain {
                assertTrue(playback.setTempoAndPitch(tempo, 0, lease))
                assertTrue(playback.seekTo(4_000L, lease))
            }
            try {
                waitUntil(5_000L) { playback.state.value.loopAvailable }
            } catch (failure: AssertionError) {
                throw AssertionError("$source/$tempo loop readiness failed after seek to 4000ms; " +
                    "beforeOwner=${beforeVariantSeek?.runtime?.ownership}; " +
                    "beforeLane0=${beforeVariantSeek?.nativeRenderReadiness?.lanes?.firstOrNull { it.laneIndex == 0 }}; " +
                    "beforeCacheMask=${beforeVariantSeek?.nativeRenderReadiness?.currentSongCacheCompleteLaneMask}; " +
                    "beforeCacheIo=${beforeVariantSeek?.runtime?.canonicalPcmIo}", failure)
            }
            onMain {
                assertTrue(playback.setLoop(2_000L, 10_000L, lease, 4_000L))
                playback.play()
            }
            waitUntil(5_000L) {
                playback.nativeDiagnosticsSnapshotForTest()?.output?.effectiveRenderPlaying == true
            }
            SystemClock.sleep(500L)
            val beforeScrub = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
            assertHealthy("$source/$tempo before scrub", beforeScrub)
            assertTrue("$source silent before scrubbing: ${beforeScrub.output?.pcmLevelMeanSquare}; " +
                "${beforeScrub.runtime.audibleControl}",
                requireNotNull(beforeScrub.output).pcmLevelMeanSquare > .00001f)
            onMain { assertTrue(playback.beginScrub(lease)) }
            var lastPcmSequence = -1L
            var lastAudibleAt = SystemClock.elapsedRealtime()
            var maximumSilentGapMs = 0L
            var lastPreviewAt = 0L
            val evidence = mutableListOf<String>()
            val scrubStartedAt = SystemClock.elapsedRealtime()
            repeat(180) { step ->
                if (step < 120) {
                    val phase = step % 40
                    val target = 2_500L + (if (phase <= 20) phase else 40 - phase) * 340L
                    onMain { playback.previewScrub(target) }
                    lastPreviewAt = SystemClock.elapsedRealtime()
                }
                // Keep the intended 16 ms input/observation cadence. Relative sleeps add every
                // diagnostics read to the coast after the final target, inflating its position.
                val remaining = scrubStartedAt + (step + 1L) * 16L - SystemClock.elapsedRealtime()
                if (remaining > 0L) SystemClock.sleep(remaining)
                val native = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
                val output = requireNotNull(native.output)
                assertHealthy("$source/$tempo scrub $step", native)
                val now = SystemClock.elapsedRealtime()
                maximumSilentGapMs = maxOf(maximumSilentGapMs, now - lastAudibleAt)
                if (output.pcmLevelSequence != lastPcmSequence && output.pcmLevelMeanSquare > .00001f) {
                    lastPcmSequence = output.pcmLevelSequence
                    lastAudibleAt = now
                }
                if (step % 10 == 0) {
                    val owners = native.runtime.ownership
                    val counters = native.runtime.counters
                    val seek = native.controllerPendingSeekOwnership
                    evidence += "$step:gate=${output.manualSelectionGateState}/authGen=${output.manualSelectionAuthorizedGeneration}" +
                        "/arms=${output.manualSelectionGateArmCount}/auths=${output.manualSelectionGateAuthorizationCount}" +
                        "/opens=${output.manualSelectionGateOpenCount}; " +
                        "req=${owners.requested?.selectionToken ?: 0}/${owners.requested?.generation ?: 0}" +
                        "/acc=${owners.accepted?.selectionToken ?: 0}/${owners.accepted?.generation ?: 0}" +
                        "/comm=${owners.committed?.selectionToken ?: 0}/${owners.committed?.generation ?: 0}" +
                        "/fp=${owners.acceptedFirstFingerprintSequence}; " +
                        "presented=${output.lastPresentedSelectionToken}/${output.lastPresentedGeneration}@${output.lastPresentedTimelineFrame / 48}" +
                        "/stale=${now - lastAudibleAt}ms/rms2=${output.pcmLevelMeanSquare}/seq=${output.pcmLevelSequence}; " +
                        "seek=${seek?.selectionToken ?: 0}/${seek?.generation ?: 0}/applied=${output.appliedGeneration}" +
                        "/queued=${output.queuedFrames}/quiesced=${native.controllerOutputPhysicallyQuiesced}" +
                        "/cmd=${counters.commandSnapshotsPublished}/${counters.commandSnapshotsConsumed}" +
                        "/handoff=${counters.outputHandoffBegins}/${counters.outputHandoffCommits}/${counters.outputHandoffCommitRetries}" +
                        "/flush=${counters.outputFlushes}/${counters.outputFlushRetries}; " +
                        "source=${native.runtime.audibleControl?.actualSourceMode}/" +
                        "cache=${native.nativeRenderReadiness?.currentSongCacheCompleteLaneMask}"
                }
            }
            val heldPosition = requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
                .lastPresentedTimelineFrame / 48L
            assertTrue("$source/$tempo dropped the last held target: $heldPosition; " +
                "sinceLastPreviewMs=${SystemClock.elapsedRealtime() - lastPreviewAt}; $evidence",
                heldPosition in 2_700L..5_000L)
            onMain { playback.commitScrub(2_840L) }
            assertTrue("$source/$tempo held scrub stalled for ${maximumSilentGapMs}ms; $evidence",
                maximumSilentGapMs < 400L)
            assertEquals(2_000L, playback.state.value.loopA)
            assertEquals(10_000L, playback.state.value.loopB)
            waitUntil(5_000L) { playback.state.value.loopAvailable }
            onMain { assertTrue(playback.seekTo(5_000L, lease)) }
            waitUntil(5_000L) {
                playback.state.value.loopAvailable &&
                    requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
                        .lastPresentedTimelineFrame / 48L in 5_000L..6_000L
            }
            val gestureId = SystemClock.uptimeMillis()
            val beforeB = requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
                .lastPresentedTimelineFrame / 48L
            onMain {
                assertTrue(playback.setLoop(2_000L, 10_000L, lease, beforeB,
                    heldBGestureId = gestureId))
            }
            val newB = beforeB - 40L
            val movedAt = SystemClock.elapsedRealtime()
            onMain {
                assertTrue(playback.setLoop(2_000L, newB, lease, beforeB,
                    inputEventAtMonotonicMicros = SystemClock.uptimeMillis() * 1_000L,
                    heldBGestureId = gestureId, capturedBMs = newB))
            }
            var responsive = false
            while (SystemClock.elapsedRealtime() - movedAt < 250L) {
                val physicalPosition = requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
                    .lastPresentedTimelineFrame / 48L
                if (physicalPosition in 2_000L..3_000L && playback.state.value.positionMs in 2_000L..3_000L) {
                    responsive = true
                    break
                }
                SystemClock.sleep(5L)
            }
            assertTrue("$source/$tempo B contraction did not move audio and cursor promptly: " +
                "ui=${playback.state.value.positionMs}, " +
                "physical=${playback.nativeDiagnosticsSnapshotForTest()?.output?.lastPresentedTimelineFrame?.div(48)}, " +
                "loop=${playback.nativeDiagnosticsSnapshotForTest()?.output?.loop}, " +
                "readiness=${playback.nativeDiagnosticsSnapshotForTest()?.nativeRenderReadiness}", responsive)
        }
    }

    @Test
    fun stemsEndContinuesWithExactlyTheNextSongInOriginal() {
        val directory = File(application.cacheDir, "runtime-stems-end").apply { mkdirs() }
        val audio = File(directory, "tone.wav")
        writeSineWave(audio, 12_000L, 220.0)
        val first = song("runtime-stems-end-first", audio, 12_000L, hasStems = true)
        val next = song("runtime-stems-end-next", audio, 12_000L)
        val last = song("runtime-stems-end-last", audio, 12_000L)
        val stems = listOf("vocals", "drums", "bass", "guitar", "piano", "remaining")
            .map { id ->
                if (id == "remaining") remainingAsset(audio, 12_000L, 220.0)
                else stemAsset(id, audio, 12_000L)
            }
        try {
            onMain { playback.startQueue(listOf(first, next, last), shuffle = false, autoplay = false) }
            waitUntil(8_000L) { playback.state.value.songId == first.id && !playback.state.value.loading }
            val lease = requireNotNull(playback.acquireControlLease(first.id))
            val activated = runBlocking {
                withTimeout(12_000L) {
                    withContext(Dispatchers.Main) { playback.activateStems(stems, lease) }
                }
            }
            assertTrue(activated.isSuccess)
            onMain { playback.seekTo(10_500L); playback.play() }
            waitUntil(8_000L) { playback.state.value.songId == next.id && playback.state.value.playing }
            assertEquals(PlaybackSourceMode.ORIGINAL, playback.state.value.sourceMode)
            assertTrue(playback.state.value.sourceSwitchState is SourceSwitchState.Original)
            assertEquals(next.id, playback.currentQueueSongId())
            assertEquals(null, playback.state.value.loopA)
        } finally {
            resetPlayback()
            directory.deleteRecursively()
        }
    }

    @Test
    fun localStemsBecomeReadyBeforeTheSourceButtonIsPressed() {
        val directory = File(application.cacheDir, "runtime-auto-stems").apply { mkdirs() }
        val audio = File(directory, "tone.wav")
        val durationMs = 8_000L
        writeSineWave(audio, durationMs, 220.0)
        val song = song("runtime-auto-stems", audio, durationMs, hasStems = true)
        val stems = listOf("vocals", "drums", "bass", "guitar", "piano", "remaining")
            .map { id ->
                if (id == "remaining") remainingAsset(audio, durationMs, 220.0)
                else stemAsset(id, audio, durationMs)
            }
        val set = SeparationSetEntity(
            id = stems.first().separationSetId, songId = song.id, sourceVersionId = "source",
            sourceSha256 = checkNotNull(song.sourceHash), provider = "fixture", modelId = "six", modelName = "Six",
            remoteJobId = null, state = app.stemwise.integration.SeparationRepository.STATE_COMPLETE,
            isActive = true, createdAtEpochMs = 0L
        )
        val repository = application.services.externalLibraryRepository
        // Seed its accepted in-memory inventory; no selected Library or durable record is changed.
        @Suppress("UNCHECKED_CAST")
        fun <T> inventory(name: String): MutableStateFlow<T> = repository.javaClass
            .getDeclaredField(name).apply { isAccessible = true }.get(repository) as MutableStateFlow<T>
        val sets = inventory<Map<String, List<SeparationSetEntity>>>("mutableStemSets")
        val assets = inventory<Map<String, List<StemAssetEntity>>>("mutableStems")
        val previousSets = sets.value
        val previousAssets = assets.value
        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
            waitUntil(8_000L) { playback.acquireControlLease(song.id) != null }
            assets.value = previousAssets + (set.id to stems)
            sets.value = previousSets + (song.id to listOf(set))
            waitUntil(12_000L) {
                playback.nativeDiagnosticsSnapshotForTest()?.nativeRenderReadiness
                    ?.currentSongCacheReadyForSelectedOwner == true
            }
            assertEquals(PlaybackSourceMode.ORIGINAL, playback.state.value.sourceMode)
            assertFalse(playback.state.value.playWhenReady)
            val readiness = checkNotNull(playback.nativeDiagnosticsSnapshotForTest()?.nativeRenderReadiness)
            assertEquals("Original plus the six declared stems must be required", 0x7f, readiness.requiredSongLaneMask)
            assertEquals("Every declared stem must be cached without unused capacity", 0x7f,
                readiness.currentSongCacheCompleteLaneMask)
            assertTrue(playback.stemPreparationDiagnostics().currentPrewarmBuildCount > 0L)
        } finally {
            sets.value = previousSets
            assets.value = previousAssets
            resetPlayback()
            directory.deleteRecursively()
        }
    }

    @Test fun runtimeMixRegressionRepeatsWithoutErasingUnrelatedSettings() {
        val root = checkNotNull(application.services.libraryRootManager.captureHandle())
        val identity = SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(),
            "unrelated-settings-${java.util.UUID.randomUUID()}", "f".repeat(64))
        val store = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        val expected = store.update(identity, 8_000L) { it.copy(tempo = .75f, semitones = 2) }
        try {
            repeat(2) {
                stemMixControlsReachTheNativeRuntimeWithoutReplacingTheSessionFacade()
                isolateAndCleanOwnedState()
                assertEquals(expected, store.read(identity, 8_000L))
            }
        } finally { store.clear(identity) }
    }

    @Test
    fun stemMixControlsReachTheNativeRuntimeWithoutReplacingTheSessionFacade() {
        resetPlayback()
        val settingsRoot = runBlocking { partsDeletionRootAuthority() }
        fun requireZeroXRuns(phase: String) {
            val output = requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
            audioHealthAssertions.checkSucceeds {
                assertEquals("$phase xRuns; output=$output", 0L, output.processXRunCount)
            }
        }
        val directory = File(application.cacheDir, "runtime-stem-controls").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        val durationMs = 8_000L
        writeSineWave(original, durationMs, 220.0)
        writeSineWave(stemOne, durationMs, 330.0)
        writeSineWave(stemTwo, durationMs, 440.0)
        val song = song("runtime-stem-controls", original, durationMs, hasStems = true)
        val stems = listOf(
            stemAsset("bass", stemOne, durationMs),
            stemAsset("drums", stemTwo, durationMs),
            stemAsset("guitar", stemOne, durationMs),
            remainingAsset(stemTwo, durationMs, 440.0),
            stemAsset("piano", stemOne, durationMs),
            stemAsset("vocals", stemTwo, durationMs)
        )
        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
            waitUntil(8_000L) {
                playback.state.value.playing && PlaybackService.isActiveForTest()
            }
            requireZeroXRuns("Initial Original playing")
            var retainedLease: PlaybackControlLease? = null
            onMain { retainedLease = playback.acquireControlLease(song.id) }
            val controlLease = requireNotNull(retainedLease)
            val activation = runBlocking {
                withTimeout(8_000L) {
                    withContext(Dispatchers.Main) {
                        playback.activateStems(stems, controlLease)
                    }
                }
            }
            assertTrue("Stem activation failed: $activation", activation.isSuccess)
            waitUntil(8_000L) { playback.state.value.sourceMode == PlaybackSourceMode.STEMS }
            requireZeroXRuns("First Stems activation")
            var stalePreviewLease: app.stemwise.playback.engine.EngineStemMixPreviewLease? = null
            val previewGains = FloatArray(app.stemwise.playback.engine.ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { .37f }
            var currentPreviewLease: app.stemwise.playback.engine.EngineStemMixPreviewLease? = null
            fun awaitAudiblePreviewAfter(previousOffer: Long, previousPublication: Long) {
                val owner = checkNotNull(currentPreviewLease).authority.ownership
                waitUntil(1_000L) {
                    playback.nativeDiagnosticsSnapshotForTest()?.stemGainCallbackSettlement?.let { gain ->
                        gain.coherent && gain.ownerGeneration == owner.generation &&
                            gain.ownerToken == owner.selectionToken && gain.offeredSequence > previousOffer &&
                            gain.publishedSequence > previousPublication && gain.activeMask == 1 &&
                            gain.gainBits[0] == .37f.toRawBits() && gain.taggedFrameCount > 0L &&
                            gain.nonZeroPcmFrameCount > 0L
                    } == true
                }
            }
            val beforePreview = playback.nativeDiagnosticsSnapshotForTest()?.stemGainCallbackSettlement
            onMain {
                val first = checkNotNull(playback.beginStemMixPreviewBatchLane(0, controlLease))
                stalePreviewLease = first
                val replacement = checkNotNull(playback.beginStemMixPreviewBatchLane(0, controlLease))
                currentPreviewLease = replacement
                assertFalse(playback.offerStemMixPreviewBatchFrameBounded(first, 1, previewGains))
                assertFalse(playback.endStemMixPreviewBatchLane(first))
                assertTrue(playback.offerStemMixPreviewBatchFrameBounded(replacement, 1, previewGains))
            }
            awaitAudiblePreviewAfter(beforePreview?.offeredSequence ?: 0L, beforePreview?.publishedSequence ?: 0L)
            assertEquals(1f, playback.graphDiagnostics().appliedStemGains["vocals"] ?: error("Missing committed vocals"), .001f)
            onMain { assertTrue(playback.endStemMixPreviewBatchLane(checkNotNull(currentPreviewLease))) }

            onMain { playback.commitStemMix("vocals", 1f, muted = false, solo = true, controlLease = controlLease) }
            waitUntil(1_000L) {
                val diagnostic = playback.graphDiagnostics()
                diagnostic.appliedStemGains["vocals"] == 1f &&
                    diagnostic.appliedStemGains.filterKeys { it != "vocals" }.values.all { it == 0f } &&
                    diagnostic.outputRms > .02f
            }
            requireZeroXRuns("Vocals solo applied")
            val soloRms = playback.graphDiagnostics().outputRms
            val livePlayer = playback.media3Player()
            val before = playback.graphDiagnostics().stemControlRevision
            val requestedAt = SystemClock.elapsedRealtime()
            onMain {
                playback.commitStemMix(
                    "vocals", .82f, muted = true, solo = true, controlLease = controlLease
                )
            }
            waitUntil(300L) {
                val diagnostic = playback.graphDiagnostics()
                diagnostic.stemControlRevision > before &&
                    diagnostic.appliedStemGains.values.all { it == 0f } &&
                    diagnostic.requestedStemGains.values.all { it == 0f } &&
                    diagnostic.appliedControlRevision > 0L && diagnostic.outputPeak < .0001f
            }
            assertTrue(
                "Mute did not reach the native runtime inside the local-control budget",
                SystemClock.elapsedRealtime() - requestedAt <= 300L
            )
            assertSame("Stem control replaced the stable session facade", livePlayer, playback.media3Player())
            assertTrue(requireNotNull(playback.state.value.stemMix["vocals"]).muted)
            requireZeroXRuns("Muted solo applied")

            onMain {
                playback.commitStemMix(
                    "vocals", .25f, muted = false, solo = true, controlLease = controlLease
                )
            }
            waitUntil(300L) {
                val diagnostic = playback.graphDiagnostics()
                diagnostic.appliedStemGains["vocals"]?.let { abs(it - .25f) < .001f } == true &&
                    diagnostic.outputRms in (soloRms * .21f)..(soloRms * .29f)
            }
            assertSame("Volume control replaced the stable session facade", livePlayer, playback.media3Player())
            requireZeroXRuns("Solo volume restored")

            onMain { playback.switchToOriginal(controlLease) }
            waitUntil(300L) {
                playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL &&
                    playback.state.value.sourceSwitchState is SourceSwitchState.Original
            }
            requireZeroXRuns("Original restored")
            val reactivationStartedAt = SystemClock.elapsedRealtime()
            val reactivation = runBlocking {
                withTimeout(8_000L) {
                    withContext(Dispatchers.Main) {
                        playback.activateStems(stems, controlLease)
                    }
                }
            }
            assertTrue("Loaded native stem package did not reactivate: $reactivation", reactivation.isSuccess)
            assertTrue(
                "Loaded native stem package showed decoder-preparation latency",
                SystemClock.elapsedRealtime() - reactivationStartedAt <= 150L
            )
            waitUntil(300L) {
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.state.value.sourceSwitchState is SourceSwitchState.Stems
            }
            assertSame("Loaded source switch replaced the stable session facade", livePlayer, playback.media3Player())
            requireZeroXRuns("Loaded Stems reactivated")

            runBlocking {
                withContext(Dispatchers.Main) { playback.clearLibrarySession() }
            }
            requireZeroXRuns("Library session cleared")
            val rootAfterClear = requireNotNull(application.services.libraryRootManager.captureHandle())
            assertEquals(settingsRoot, app.stemwise.data.RootAuthority(
                rootAfterClear.libraryId, rootAfterClear.treeUri.toString(), rootAfterClear.epoch))
            onMain {
                playback.setPlaybackQueue(listOf(song), shuffle = false, snapshotReady = true)
                playback.startQueue(listOf(song), shuffle = false, autoplay = true)
            }
            waitUntil(8_000L) {
                playback.state.value.playing && PlaybackService.isActiveForTest()
            }
            requireZeroXRuns("Original restarted after Library clear")
            var restoredLease: PlaybackControlLease? = null
            onMain { restoredLease = playback.acquireControlLease(song.id) }
            val restoredControlLease = requireNotNull(restoredLease)
            val afterLibrarySwitch = runBlocking {
                withTimeout(8_000L) {
                    withContext(Dispatchers.Main) {
                        playback.activateStems(stems, restoredControlLease)
                    }
                }
            }
            assertTrue(
                "Stem activation after Library switch failed: $afterLibrarySwitch",
                afterLibrarySwitch.isSuccess
            )
            waitUntil(8_000L) { playback.state.value.sourceMode == PlaybackSourceMode.STEMS }
            requireZeroXRuns("Stems activated after Library clear")
            val beforeRestoredPreview = playback.nativeDiagnosticsSnapshotForTest()?.stemGainCallbackSettlement
            onMain {
                val replacement = checkNotNull(playback.beginStemMixPreviewBatchLane(0, restoredControlLease))
                currentPreviewLease = replacement
                assertFalse(playback.offerStemMixPreviewBatchFrameBounded(checkNotNull(stalePreviewLease), 1, previewGains))
                assertFalse(playback.endStemMixPreviewBatchLane(checkNotNull(stalePreviewLease)))
                assertTrue(playback.offerStemMixPreviewBatchFrameBounded(replacement, 1, previewGains))
            }
            awaitAudiblePreviewAfter(beforeRestoredPreview?.offeredSequence ?: 0L,
                beforeRestoredPreview?.publishedSequence ?: 0L)
            val restoredPreviewOwner = checkNotNull(currentPreviewLease).authority.ownership
            val heldPreviewCallback = requireNotNull(
                playback.nativeDiagnosticsSnapshotForTest()?.stemGainCallbackSettlement).callbackSequence
            onMain { assertTrue(playback.endStemMixPreviewBatchLane(checkNotNull(currentPreviewLease))) }
            waitUntil(1_000L) {
                val gain = playback.nativeDiagnosticsSnapshotForTest()?.stemGainCallbackSettlement
                    ?: return@waitUntil false
                val diagnostic = playback.graphDiagnostics()
                gain.coherent && gain.ownerGeneration == restoredPreviewOwner.generation &&
                    gain.ownerToken == restoredPreviewOwner.selectionToken &&
                    gain.callbackSequence > heldPreviewCallback && gain.activeMask == 0 &&
                    gain.nonZeroPcmFrameCount > 0L && diagnostic.appliedControlRevision > 0L &&
                    diagnostic.appliedStemGains.keys == stems.map { it.id }.toSet() &&
                    diagnostic.appliedStemGains["vocals"] == .25f &&
                    diagnostic.appliedStemGains.filterKeys { it != "vocals" }.values.all { it == 0f }
            }
            val restoredMix = requireNotNull(playback.state.value.stemMix["vocals"])
            assertEquals(.25f, restoredMix.volume, .001f)
            assertFalse(restoredMix.muted)
            assertTrue(restoredMix.solo)
            assertSame("Library restart replaced the stable session facade", livePlayer, playback.media3Player())
            requireZeroXRuns("Saved solo mix restored after preview")
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun liveControlsSurviveSamePlayerLateStemRebuild() {
        resetPlayback()
        val directory = File(application.cacheDir, "runtime-handoff-controls").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        val durationMs = 12_000L
        writeSineWave(original, durationMs, 220.0)
        writeSineWave(stemOne, durationMs, 330.0)
        writeSineWave(stemTwo, durationMs, 440.0)
        val song = song("runtime-handoff-controls", original, durationMs, hasStems = true)
        val stems = listOf(
            stemAsset("bass", stemOne, durationMs),
            stemAsset("drums", stemTwo, durationMs),
            stemAsset("guitar", stemOne, durationMs),
            remainingAsset(stemTwo, durationMs, 440.0),
            stemAsset("piano", stemOne, durationMs),
            stemAsset("vocals", stemTwo, durationMs)
        )
        var retainedLease: PlaybackControlLease? = null

        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
            waitUntil(8_000L) {
                playback.state.value.playing && PlaybackService.isActiveForTest()
            }
            onMain { retainedLease = playback.acquireControlLease(song.id) }
            val controlLease = requireNotNull(retainedLease)
            val originalPlayer = playback.media3Player()
            val originalEpoch = playback.primaryAudibleOwnershipEpochForTest()
            onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
            val activation = try { runBlocking {
                withTimeout(8_000L) {
                    coroutineScope {
                        val request = async(Dispatchers.Main) {
                            playback.activateStems(stems, controlLease)
                        }
                        while (
                            !request.isCompleted &&
                            playback.state.value.sourceSwitchState !is
                                SourceSwitchState.PreparingStems
                        ) delay(4L)
                        assertFalse("Activation completed before the overlap controls entered", request.isCompleted)
                        withContext(Dispatchers.Main) {
                            playback.seekTo(3_000L)
                            playback.setTempoAndPitch(1.35f, 4, controlLease)
                            playback.setLoop(3_000L, 5_000L, controlLease)
                            playback.setMetronome(
                                enabled = true,
                            bpm = 137f,
                            songSynced = false,
                            subdivision = 2,
                            controlLease = controlLease
                            )
                        }
                        onMain { playback.debug.releaseNativeActorForSelectionBurst() }
                        request.await()
                    }
                }
            } } finally { onMain { playback.debug.releaseNativeActorForSelectionBurst() } }
            assertTrue("Stem activation failed after live controls: $activation", activation.isSuccess)
            waitUntil(8_000L) {
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.state.value.sourceSwitchState is SourceSwitchState.Stems
            }
            waitUntil(300L) {
                val controls = playback.graphDiagnostics()
                kotlin.math.abs(controls.requestedTempo - 1.35f) < .001f &&
                    controls.requestedSemitoneShift == 4 &&
                    controls.requestedLoopStartMs == 3_000L &&
                    controls.requestedLoopEndMs == 5_000L &&
                    playback.state.value.metronomeEnabled &&
                    kotlin.math.abs(playback.state.value.metronomeBpm - 137f) < .001f
            }

            fun rebuiltControlsAreAudible(): Boolean {
                val session = playback.adapter.snapshot()
                val owner = session.publication.committed?.owner?.ownership ?: return false
                val native = playback.nativeDiagnosticsSnapshotForTest() ?: return false
                val audible = native.runtime.audibleControl ?: return false
                val publication = native.controllerSourceControlPublications.lastOrNull {
                    it.ownership == owner && it.nativeControlRevision == audible.controlRevision
                } ?: return false
                val controls = publication.nativeControls ?: return false
                return session.latestRequest?.exactOwnership == owner &&
                    native.runtime.ownership.committed == owner && audible.ownership == owner &&
                    audible.actualSourceMode == NativeRuntimeSourceMode.STEMS &&
                    abs(audible.actualTempo - 1.35f) < .001f &&
                    abs(audible.actualPitchSemitones - 4f) < .001f &&
                    controls.metronome.mode == NativeRuntimeMetronomeMode.FREE_BPM &&
                    abs(controls.metronome.bpm - 137f) < .001f &&
                    native.output?.lastPresentedGeneration == owner.generation &&
                    native.output.lastPresentedSelectionToken == owner.selectionToken
            }
            // Requested controls do not prove that the rebuilt owner's PCM applied them.
            waitUntil(2_000L) { rebuiltControlsAreAudible() }
            assertSame(originalPlayer, playback.media3Player())
            val settledEpoch = playback.primaryAudibleOwnershipEpochForTest()
            assertTrue(
                "Stem rebuild plus concurrent Seek advanced ownership implausibly: " +
                    "$originalEpoch -> $settledEpoch",
                settledEpoch in (originalEpoch + 1L)..(originalEpoch + 3L)
            )
            SystemClock.sleep(250L)
            assertEquals(settledEpoch, playback.primaryAudibleOwnershipEpochForTest())
            assertTrue("Rebuilt owner lost its applied tuning or metronome", rebuiltControlsAreAudible())
            val diagnostic = playback.graphDiagnostics()
            assertEquals(1.35f, diagnostic.requestedTempo, .001f)
            assertEquals(4, diagnostic.requestedSemitoneShift)
            assertEquals(1.35f, diagnostic.appliedTempo ?: Float.NaN, .001f)
            assertEquals(2.0.pow(4.0 / 12.0).toFloat(), diagnostic.appliedPitchRatio ?: Float.NaN, .001f)
            assertTrue(diagnostic.appliedControlRevision > 0L)
            assertEquals(3_000L, diagnostic.requestedLoopStartMs)
            assertEquals(5_000L, diagnostic.requestedLoopEndMs)
            assertTrue(playback.state.value.metronomeEnabled)
            assertEquals(137f, playback.state.value.metronomeBpm, .001f)
            val installedPosition = currentPlayerPosition()
            assertTrue(
                "Installed native owner lost the transfer seek: position=$installedPosition, " +
                    "native=${playback.nativeDiagnosticsSnapshotForTest()}",
                installedPosition in 2_975L..5_025L
            )
            onMain {
                playback.setMetronome(
                    enabled = true,
                    bpm = 137f,
                    songSynced = false,
                    subdivision = 3,
                    denominator = 3,
                    controlLease = controlLease
                )
            }
            assertEquals(1, playback.state.value.metronomeSubdivision)
            assertEquals(4, playback.state.value.metronomeDenominator)
        } finally {
            onMain {
                retainedLease?.let { playback.setLoop(null, null, it) }
                retainedLease?.let {
                    playback.setMetronome(
                        false,
                        120f,
                        songSynced = false,
                        controlLease = it
                    )
                }
                retainedLease?.let { playback.setTempoAndPitch(1f, 0, it) }
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun unreadableStemPackageFailsFinitelyAndRestoresTheAudibleOriginalOwner() {
        resetPlayback()
        val directory = File(application.cacheDir, "runtime-unreadable-stem").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val validStem = File(directory, "valid-stem.wav")
        val missingStem = File(directory, "missing-vocals.wav")
        val durationMs = 12_000L
        writeSineWave(original, durationMs, 220.0)
        writeSineWave(validStem, durationMs, 330.0)
        writeSineWave(missingStem, durationMs, 440.0)
        val song = song("runtime-unreadable-stem", original, durationMs, hasStems = true)
        val stems = listOf(
            stemAsset("bass", validStem, durationMs),
            stemAsset("drums", validStem, durationMs),
            stemAsset("guitar", validStem, durationMs),
            remainingAsset(validStem, durationMs, 330.0),
            stemAsset("piano", validStem, durationMs),
            stemAsset("vocals", missingStem, durationMs)
        )

        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
            waitUntil(8_000L) { playback.state.value.playing }
            var retainedLease: PlaybackControlLease? = null
            onMain { retainedLease = playback.acquireControlLease(song.id) }
            val controlLease = requireNotNull(retainedLease)
            val audioHealthBaseline = unreadableStemAudioHealth()
            audioHealthAssertions.checkSucceeds {
                assertEquals("Original start introduced platform xruns; native=" +
                    playback.nativeDiagnosticsSnapshotForTest(), 0L, audioHealthBaseline.xRunCount)
            }
            val initialNative = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
            val originalOwner = requireNotNull(initialNative.runtime.ownership.committed)
            val originalToken = originalOwner.selectionToken
            val originalBank = initialNative.runtime.banks.indexOfFirst { it.assigned }
            assertTrue(originalBank >= 0)
            val originalBankRevision = initialNative.runtime.banks[originalBank].appliedIntentRevision
            val initialPresentedFrame = initialNative.runtime.presentedPosition
                ?.takeIf { it.ownership == originalOwner }
                ?.lastTimelineFrame ?: 0L
            var maximumPresentedFrame = initialPresentedFrame
            val verifiedParts = checkNotNull(playback.debug.verifyPlaybackStems)
            playback.debug.verifyPlaybackStems = { songId, requested ->
                val admitted = verifiedParts(songId, requested)
                if (songId == song.id && requested == stems) {
                    playback.debug.verifyPlaybackStems = verifiedParts
                    check(android.provider.DocumentsContract.deleteDocument(application.contentResolver,
                        android.net.Uri.parse(stems.last().localPath)))
                }
                admitted
            }
            val startedAt = SystemClock.elapsedRealtime()
            val activation = runBlocking {
                withTimeout(4_000L) {
                    withContext(Dispatchers.Main) {
                        playback.activateStems(stems, controlLease)
                    }
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            assertTrue("Unreadable package unexpectedly activated: $activation", activation.isFailure)
            val exportedFailure = JSONObject(runBlocking { playback.exportPlaybackSnapshot() })
            val stages = exportedFailure.getJSONArray("preparationStages")
            val failedStage = (0 until stages.length()).map(stages::getJSONObject).lastOrNull {
                it.getString("operation") == "STEM_PACKAGE" && !it.isNull("cause") &&
                    it.optJSONObject("ownership")?.optLong("selectionToken") == originalToken
            }
            assertTrue("Export lost the actual failed preparation cause: $exportedFailure", failedStage != null)
            assertTrue(checkNotNull(failedStage).getString("cause").isNotBlank())
            assertTrue(failedStage.getLong("elapsedMs") >= 0L)
            assertTrue("Unreadable package did not settle finitely: ${elapsed}ms", elapsed <= 3_500L)
            assertUnreadableStemAudioHealthUnchanged(
                label = "failed package transaction",
                baseline = audioHealthBaseline
            )
            waitUntil(4_000L, "failed package Original fallback") {
                val state = playback.state.value
                val native = playback.nativeDiagnosticsSnapshotForTest()
                val output = native?.output
                val owner = native?.runtime?.ownership?.committed
                val resources = playback.playbackResourceDiagnosticsForTest()
                val readiness = native?.nativeRenderReadiness
                val presented = native?.runtime?.presentedPosition
                presented
                    ?.takeIf { it.ownership.selectionToken == originalToken }
                    ?.lastTimelineFrame
                    ?.let { currentFrame ->
                        assertTrue(
                            "Unreadable-stem recovery rewound the live Original timeline " +
                                "$maximumPresentedFrame->$currentFrame; native=$native",
                            currentFrame + (output?.framesPerBurst?.coerceAtLeast(0) ?: 0) >=
                                maximumPresentedFrame
                        )
                        maximumPresentedFrame = maxOf(maximumPresentedFrame, currentFrame)
                    }
                state.songId == song.id &&
                    state.sourceMode == PlaybackSourceMode.ORIGINAL &&
                    state.sourceSwitchState is SourceSwitchState.Original &&
                    state.playing && state.error == null &&
                    native != null &&
                    owner != null &&
                    owner == originalOwner &&
                    native.runtime.ownership.requested == owner &&
                    native.runtime.ownership.accepted == owner &&
                    native.controllerPublicOwnership == owner &&
                    presented != null && presented.ownership == originalOwner &&
                    presented.lastTimelineFrame > initialPresentedFrame &&
                    output?.playing == true &&
                    output.appliedGeneration == owner.generation &&
                    output.lastPresentedGeneration == owner.generation &&
                    output.lastPresentedSelectionToken == owner.selectionToken &&
                    native.runtime.banks[originalBank].assigned &&
                    native.runtime.banks[originalBank].appliedIntentRevision > originalBankRevision &&
                    readiness != null && readiness.ownership == owner &&
                    readiness.ingressBankConfiguredLaneCount == 1L &&
                    readiness.requiredSongLaneMask == 1 &&
                    resources.assignedNativeBankCount == 1 &&
                    resources.activeDecoderLeaseCount == 1 &&
                    resources.candidateDecoderLeaseCount == 0 &&
                    resources.totalDecoderLeaseCount == 1
            }
            assertUnreadableStemAudioHealthUnchanged(
                label = "Original fallback publication",
                baseline = audioHealthBaseline
            )
            SystemClock.sleep(350L)
            assertTrue(
                "Failed package returned to a perpetual preparation state: ${playback.state.value}",
                playback.state.value.sourceSwitchState !is SourceSwitchState.PreparingStems
            )
            assertUnreadableStemAudioHealthUnchanged(
                label = "Original fallback stability",
                baseline = audioHealthBaseline
            )
            waitUntil(4_000L) {
                val resources = playback.playbackResourceDiagnosticsForTest()
                resources.assignedNativeBankCount == 1 &&
                    resources.activeDecoderLeaseCount == 1 &&
                    resources.candidateDecoderLeaseCount == 0 &&
                    resources.totalDecoderLeaseCount == 1
            }
            writeSineWave(missingStem, durationMs, 440.0)
            assertEquals(stems.last().localPath, PlaybackContentFixture.publish(application, missingStem))
            val failedPackage = requireNotNull(playback.adapter.snapshot().publication.committed?.stems?.desired)
            val beforeRetry = requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.runtime?.presentedPosition)
            assertEquals(originalOwner, beforeRetry.ownership)
            maximumPresentedFrame = maxOf(maximumPresentedFrame, beforeRetry.lastTimelineFrame)
            val retry = runBlocking {
                withTimeout(4_000L) {
                    withContext(Dispatchers.Main) { playback.activateStems(stems, controlLease) }
                }
            }
            assertTrue("Repaired same-selection stem package failed: $retry", retry.isSuccess)
            val repaired = requireNotNull(playback.adapter.snapshot().publication.committed)
            val repairedOwner = repaired.owner.ownership
            val repairedPackage = requireNotNull(repaired.stems.desired)
            assertEquals(originalToken, repairedOwner.selectionToken)
            assertTrue("Repaired parts must retire the failed generation", repairedOwner.generation > originalOwner.generation)
            assertEquals(failedPackage.packageSignature, repairedPackage.packageSignature)
            assertEquals(failedPackage.stemRevision, repairedPackage.stemRevision)
            assertTrue(repairedPackage.requestRevision > failedPackage.requestRevision)
            waitUntil(4_000L, "repaired package acknowledged owner") {
                val current = playback.adapter.snapshot().publication.committed
                val native = playback.nativeDiagnosticsSnapshotForTest()
                val audible = native?.runtime?.audibleControl
                val publication = native?.controllerSourceControlPublications?.lastOrNull {
                    it.ownership == repairedOwner && it.nativeControlRevision == audible?.controlRevision
                }
                val presented = native?.runtime?.presentedPosition
                presented?.takeIf { it.ownership.selectionToken == originalToken }?.lastTimelineFrame?.let { frame ->
                    assertTrue("Repaired-stem handoff rewound the source cursor $maximumPresentedFrame->$frame; native=$native",
                        frame + (native?.output?.framesPerBurst?.coerceAtLeast(0) ?: 0) >= maximumPresentedFrame)
                    maximumPresentedFrame = maxOf(maximumPresentedFrame, frame)
                }
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.state.value.playing && playback.state.value.error == null &&
                    current?.owner?.ownership == repairedOwner && current.stems.desired == repairedPackage &&
                    current.stems.phase == PlaybackStemPackagePhase.INSTALLED &&
                    current.stems.installed?.let { it.packageSignature == repairedPackage.packageSignature &&
                        it.stemRevision == repairedPackage.stemRevision &&
                        it.requestRevision == repairedPackage.requestRevision && it.callbackSequence > 0L } == true &&
                    current.source.appliedRevision == repaired.source.desiredRevision &&
                    current.source.appliedMode == EngineSourceMode.STEMS &&
                    native?.runtime?.ownership?.committed == repairedOwner &&
                    native.runtime.ownership.requested == repairedOwner &&
                    native.runtime.ownership.accepted == repairedOwner && native.controllerPublicOwnership == repairedOwner &&
                    audible?.ownership == repairedOwner && audible.actualSourceMode == NativeRuntimeSourceMode.STEMS &&
                    publication?.desiredRevision == repaired.source.desiredRevision &&
                    publication.requestedMode == EngineSourceMode.STEMS && publication.nativeMode == NativeRuntimeSourceMode.STEMS &&
                    publication.stemPackageSignature == repairedPackage.packageSignature &&
                    publication.stemRevision == repairedPackage.stemRevision &&
                    publication.stemRequestRevision == repairedPackage.requestRevision &&
                    presented?.ownership == repairedOwner && presented.lastTimelineFrame > beforeRetry.lastTimelineFrame &&
                    native.output?.appliedGeneration == repairedOwner.generation &&
                    native.output.lastPresentedGeneration == repairedOwner.generation &&
                    native.output.lastPresentedSelectionToken == originalToken
            }
            assertUnreadableStemAudioHealthUnchanged(
                label = "same-selection stem retry",
                baseline = audioHealthBaseline
            )
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun rapidOriginalStemReversalsPublishOnlyTheLatestAudibleSourceOwner() {
        resetPlayback()
        val directory = File(application.cacheDir, "runtime-rapid-source-owner").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        val durationMs = 16_000L
        writeSineWave(original, durationMs, 220.0)
        writeSineWave(stemOne, durationMs, 330.0)
        writeSineWave(stemTwo, durationMs, 440.0)
        val song = song("runtime-rapid-source-owner", original, durationMs, hasStems = true)
        val stems = listOf(
            stemAsset("bass", stemOne, durationMs),
            stemAsset("drums", stemTwo, durationMs),
            stemAsset("guitar", stemOne, durationMs),
            remainingAsset(stemTwo, durationMs, 440.0),
            stemAsset("piano", stemOne, durationMs),
            stemAsset("vocals", stemTwo, durationMs)
        )

        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
            waitUntil(8_000L) { playback.state.value.playing }
            var retainedSourceLease: PlaybackControlLease? = null
            onMain { retainedSourceLease = playback.acquireControlLease(song.id) }
            val sourceLease = requireNotNull(retainedSourceLease)
            // First activation builds the entire six-lane cache under ARM emulator translation.
            // Later reversals below keep their shorter command-settlement deadlines.
            val initialActivation = try {
                runBlocking {
                    withTimeout(20_000L) {
                        withContext(Dispatchers.Main) {
                            playback.activateStems(stems, sourceLease)
                        }
                    }
                }
            } catch (failure: Throwable) {
                throw AssertionError(
                    "Initial stem activation did not settle: state=${playback.state.value}; " +
                        "native=${playback.nativeDiagnosticsSnapshotForTest()}; " +
                        "resources=${playback.playbackResourceDiagnosticsForTest()}; " +
                        "graph=${playback.graphDiagnostics()}",
                    failure
                )
            }
            assertTrue("Initial package activation failed: $initialActivation", initialActivation.isSuccess)
            waitUntil(2_000L) {
                playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.nativeDiagnosticsSnapshotForTest()
                        ?.runtime?.audibleControl?.actualSourceMode?.name == "STEMS"
            }
            val stableOwnerEpoch = playback.primaryAudibleOwnershipEpochForTest()

            runBlocking { withTimeout(20_000L) {
                val reversalDelays = longArrayOf(1L, 4L, 9L, 2L, 7L)
                repeat(30) { index ->
                    withContext(Dispatchers.Main) {
                        assertTrue(playback.switchToOriginal(sourceLease))
                    }
                    val activation = async(Dispatchers.Main) {
                        playback.activateStems(stems, sourceLease)
                    }
                    delay(reversalDelays[index % reversalDelays.size])
                    withContext(Dispatchers.Main) {
                        assertTrue(playback.switchToOriginal(sourceLease))
                    }
                    // It may have become audible before the newer Original request or may have
                    // been superseded; either outcome is valid. Only the final request may own.
                    try { withTimeout(4_000L) { activation.await() } }
                    finally { activation.cancelAndJoin() }
                }
            } }
            waitUntil(2_000L) {
                val state = playback.state.value
                state.sourceMode == PlaybackSourceMode.ORIGINAL &&
                    state.sourceSwitchState is SourceSwitchState.Original &&
                    state.playing &&
                    playback.nativeDiagnosticsSnapshotForTest()
                        ?.runtime?.audibleControl?.actualSourceMode?.name == "ORIGINAL"
            }
            assertEquals(stableOwnerEpoch, playback.primaryAudibleOwnershipEpochForTest())

            val finalActivation = runBlocking {
                withTimeout(4_000L) {
                    withContext(Dispatchers.Main) {
                        playback.activateStems(stems, sourceLease)
                    }
                }
            }
            assertTrue("Final latest Stems request failed: $finalActivation", finalActivation.isSuccess)
            waitUntil(2_000L) {
                val state = playback.state.value
                state.sourceMode == PlaybackSourceMode.STEMS &&
                    state.sourceSwitchState is SourceSwitchState.Stems &&
                    state.playing &&
                    playback.nativeDiagnosticsSnapshotForTest()
                        ?.runtime?.audibleControl?.actualSourceMode?.name == "STEMS"
            }
            assertEquals(stableOwnerEpoch, playback.primaryAudibleOwnershipEpochForTest())
            assertEquals(song.id, playback.graphMediaId())
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    @Test
    fun nativeActorFatalFaultImmediatelySilencesAndQuarantinesWithoutPollSpin() {
        assumeTrue(
            "Destructive native quarantine qualification requires its own disposable process",
            isNativeFatalQualification()
        )
        resetPlayback()
        val directory = File(application.cacheDir, "runtime-native-fatal-fault").apply { mkdirs() }
        val original = File(directory, "original.wav")
        writeSineWave(original, durationMs = 4_000L, frequency = 220.0)
        val song = song("runtime-native-fatal-fault", original, durationMs = 4_000L)
        val diagnostic = "Injected native actor fatal fault"

        try {
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
            waitUntil(8_000L) {
                playback.state.value.playing &&
                    playback.nativeDiagnosticsSnapshotForTest()?.output?.playing == true
            }
            var injectionAccepted = false
            onMain { injectionAccepted = playback.debug.injectNativeActorFatalFault(diagnostic) }
            assertTrue(injectionAccepted)
            waitUntil(8_000L) {
                val state = playback.state.value
                val native = playback.nativeDiagnosticsSnapshotForTest()
                val output = native?.output
                state.error == diagnostic &&
                    !state.playing &&
                    !state.playWhenReady &&
                    native != null &&
                    output != null &&
                    !output.playing &&
                    !output.effectiveRenderPlaying &&
                    playback.processRestartRequiredAfterTaskRemoval() &&
                    native.controllerFatalQuarantined &&
                    native.controllerFatalTransitionCount == 1L
            }

            val settled = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
            assertTrue(!playback.debug.injectNativeActorFatalFault("duplicate fatal fault"))
            SystemClock.sleep(350L)
            val afterQuietPeriod = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
            assertEquals(1L, afterQuietPeriod.controllerFatalTransitionCount)
            assertEquals(settled.controllerActorTurnCount, afterQuietPeriod.controllerActorTurnCount)
            assertTrue(afterQuietPeriod.controllerFatalQuarantined)
            assertTrue(requireNotNull(afterQuietPeriod.output).playing.not())
        } finally {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }
    }

    private fun isolateAndCleanOwnedState() {
        resetPlayback()
        val store = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        runBlocking { withTimeout(12_000L) { ownedSettingsSongs.values.forEach { playback.savedSongTrim(it) } } }
        ownedSettingsSongs.keys.forEach(store::clear)
        ownedSettingsSongs.clear()
        PlaybackContentFixture.clear(application)
    }

    private fun isNativeFatalQualification(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString("stemwiseNativeFatalQualification") == "true" &&
            InstrumentationRegistry.getArguments()
                .getString("stemwiseDisposableTarget") == "true"

    private fun resetPlayback() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
        }
        playback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
    }

    private fun song(
        id: String,
        original: File,
        durationMs: Long,
        hasStems: Boolean = false
    ) = Song(
        id = id,
        videoId = id,
        title = id,
        artist = "Stemwise",
        durationMs = durationMs,
        state = SongState.READY,
        accent = Color.Cyan,
        hasStems = hasStems,
        artworkGlyph = "T",
        sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
        sourceCodec = "pcm_s16le",
        sourceContainer = "wav"
    ).also { song ->
        val root = checkNotNull(application.services.libraryRootManager.captureHandle())
        val identity = SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), song.id,
            checkNotNull(song.sourceHash))
        if (identity !in ownedSettingsSongs) {
            ownedSettingsSongs[identity] = song
            runBlocking { withTimeout(8_000L) { playback.savedSongTrim(song) } }
            SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).clear(identity)
            runBlocking { withTimeout(8_000L) { playback.savedSongTrim(song) } }
        }
    }

    private fun stemAsset(id: String, file: File, durationMs: Long) = PlaybackContentFixture.stem(application, id, file, durationMs, "runtime-stem-controls-set")

    private fun remainingAsset(tone: File, durationMs: Long, frequency: Double): StemAssetEntity {
        val remaining = File(tone.parentFile, "${tone.nameWithoutExtension}-remaining.wv")
        PlaybackContentFixture.writeRemainingTone(application, remaining, durationMs, frequency, 2_000.0 / 32_768.0)
        return stemAsset("remaining", remaining, durationMs).copy(
            codec = "wavpack", sampleRateHz = 48_000, channelCount = 2
        )
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun currentPlayerPosition(): Long {
        var position = 0L
        onMain { position = playback.transportPositionMs() }
        return position
    }

    private data class UnreadableStemAudioHealth(
        val underrunCallbackCount: Long,
        val underrunFrames: Long,
        val xRunCount: Long
    )

    private fun unreadableStemAudioHealth(): UnreadableStemAudioHealth {
        val output = requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output) {
            "Unreadable-stem regression exposed no native output diagnostics"
        }
        return UnreadableStemAudioHealth(
            underrunCallbackCount = output.processUnderrunCallbackCount,
            underrunFrames = output.processUnderrunFrames,
            xRunCount = output.processXRunCount
        )
    }

    private fun assertUnreadableStemAudioHealthUnchanged(
        label: String,
        baseline: UnreadableStemAudioHealth
    ) {
        val current = unreadableStemAudioHealth()
        assertEquals(
            "$label introduced platform xruns; native=" +
                playback.nativeDiagnosticsSnapshotForTest(),
            baseline.xRunCount,
            current.xRunCount
        )
        assertEquals(
            "$label introduced callback underruns; native=" +
                playback.nativeDiagnosticsSnapshotForTest(),
            baseline.underrunCallbackCount,
            current.underrunCallbackCount
        )
        assertEquals(
            "$label introduced underrun frames; native=" +
                playback.nativeDiagnosticsSnapshotForTest(),
            baseline.underrunFrames,
            current.underrunFrames
        )
    }

    private fun waitUntil(timeoutMs: Long, phase: String = "playback state", predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(8L)
        }
        assertTrue(
            "Timed out waiting for $phase: ${playback.state.value}; " +
                "stemPreparation=${playback.stemPreparationDiagnostics()}; " +
                "resources=${playback.playbackResourceDiagnosticsForTest()}; " +
                "selection=${playback.selectionPressureDiagnosticsForTest()}; " +
                "native=${playback.nativeDiagnosticsSnapshotForTest()}",
            predicate()
        )
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

}
