package app.stemwise.playback

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import app.stemwise.data.SeparationSetEntity
import app.stemwise.data.StemAssetEntity
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.integration.SeparationRepository
import app.stemwise.ui.theme.StemwiseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.model.Stem
import app.stemwise.playback.engine.NativeRuntimeSourceMode
import app.stemwise.ui.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlayerMixResetInstrumentationTest : LoopPlaybackFixture() {
    @get:Rule(order = 1) val compose = createStemwiseComposeRule()

    private fun assertFaderVolume(label: String, lane: Int, expected: Float) {
        compose.waitUntil(5_000L) {
            compose.onNodeWithContentDescription("$label volume").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current == expected
        }
        compose.onNodeWithContentDescription("$label volume").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                "${(expected * 100).toInt()} percent"))
        onMain {
            fun find(view: android.view.View): app.stemwise.ui.player.surface.PlayerSurfaceView? {
                if (view is app.stemwise.ui.player.surface.PlayerSurfaceView) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount)
                    find(view.getChildAt(index))?.let { return it }
                return null
            }
            val surface = checkNotNull(find(compose.activity.window.decorView))
            val owner = surface.javaClass.getDeclaredField("mixerGestures").apply { isAccessible = true }
                .get(surface) as app.stemwise.ui.player.surface.PlayerMixerGestureOwner
            assertEquals("The actual drawn fader must match accessibility", expected, owner.liveMixerVolumes[lane], 0f)
        }
    }

    @Test fun openLoopToolKeepsTheNineLaneMixersPhysicalTouchOwner() {
        val directory = File(application.cacheDir, "open-tool-mixer-touch").apply { check(mkdirs()) }
        val original = File(directory, "original.wav").also { writeSineWave(it, 20_000L, 220.0) }
        val tone = File(directory, "part.wav").also { writeSineWave(it, 20_000L, 330.0) }
        val songId = "open-tool-mixer-${UUID.randomUUID()}"
        val song = Song(songId, songId, "Open tool mixer", "Fixture",
            20_000L, SongState.READY, Color.Cyan, hasStems = true, artworkGlyph = "M",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original), sourceCodec = "pcm_s16le",
            sourceContainer = "wav", audioRevision = "open-tool-mixer-audio")
        val root = application.services.libraryRootManager.state.value
        check(root.ready)
        val settingsIdentity = SongPlaybackSettingsIdentity(checkNotNull(root.libraryId),
            checkNotNull(root.treeUri).toString(), song.id, checkNotNull(song.sourceHash))
        val settingsStore = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        val parts = listOf("lead-vocals", "backing-vocals", "drums", "bass", "acoustic-guitar",
            "electric-guitar", "harp", "mandolin").map {
                stemAsset(it, tone, 20_000L).let { asset ->
                    if (it == "lead-vocals") asset.copy(providerLabel = "Lead vocals") else asset
                }
            } +
            remainingAsset(tone, 20_000L, 330.0)
        val set = SeparationSetEntity(parts.first().separationSetId, song.id, "fixture-source", checkNotNull(song.sourceHash),
            "fixture", "fixture", "Fixture", null, SeparationRepository.STATE_COMPLETE, true, 0L)
        val repository = application.services.externalLibraryRepository
        @Suppress("UNCHECKED_CAST")
        fun <T> inventory(name: String) = repository.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(repository) as MutableStateFlow<T>
        val sets = inventory<Map<String, List<SeparationSetEntity>>>("mutableStemSets")
        val assets = inventory<Map<String, List<StemAssetEntity>>>("mutableStems")
        val previousSets = sets.value
        val previousAssets = assets.value
        try {
            onMain { assertTrue(playback.selectSong(song, autoplay = false, positionMs = 3_000L)) }
            waitUntil(8_000L) { playback.acquireControlLease(song.id) != null }
            sets.value = previousSets + (song.id to listOf(set))
            assets.value = previousAssets + (set.id to parts)
            compose.setContent { StemwiseTheme {
                PlayerScreen(song, {}, audioPlayback = playback, library = listOf(song),
                    separationRepository = application.services.separationRepository,
                    analysisRepository = application.services.analysisRepository,
                    onDeleteSong = { Result.failure(IllegalStateException("Outside fixture")) }, modifier = Modifier.fillMaxSize())
            } }
            runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
                playback.activateStems(parts, checkNotNull(playback.acquireControlLease(song.id))).getOrThrow()
            } } }
            try {
                compose.waitUntil(8_000L) {
                    val lease = playback.controlLease.value
                    lease != null && lease == playback.acquireControlLease(song.id) &&
                    playback.state.value.sourceSwitchState is SourceSwitchState.Stems &&
                    playback.state.value.loadedStemAssets.map { it.id }.toSet() == parts.map { it.id }.toSet() &&
                    compose.onAllNodesWithContentDescription("Lead vocals volume").fetchSemanticsNodes().size == 1 }
            } catch (failure: ComposeTimeoutException) {
                val state = playback.state.value
                val native = playback.nativeDiagnosticsSnapshotForTest()
                fun leaseSummary(lease: PlaybackControlLease?) =
                    "${lease?.native}; prepared=${lease?.pausedPreparedStemMix?.ownership}/" +
                        "${lease?.pausedPreparedStemMix?.sourceRevision}/${lease?.pausedPreparedStemMix?.packageRequestRevision}"
                failure.addSuppressed(AssertionError("Mixer setup song=${state.songId} loading=${state.loading} " +
                    "source=${state.sourceSwitchState} parts=${state.loadedStemAssets.map { it.id }} " +
                    "publishedLease=${leaseSummary(playback.controlLease.value)} currentLease=${leaseSummary(playback.acquireControlLease(song.id))} " +
                    "runtime=${native?.runtime?.status}/${native?.runtime?.ownership} " +
                    "ready=${native?.controllerStemReadiness?.ready}/${native?.controllerStemReadiness?.failureReasons}"))
                throw failure
            }
            compose.waitUntil(5_000L) {
                compose.onAllNodes(hasContentDescription("Mute Lead vocals: Off") and isEnabled())
                    .fetchSemanticsNodes().size == 1
            }
            assertFaderVolume("Lead vocals", 0, 1f)
            assertFalse(playback.state.value.stemMix["lead-vocals"]?.muted == true)
            fun find(view: android.view.View): app.stemwise.ui.player.surface.PlayerSurfaceView? {
                if (view is app.stemwise.ui.player.surface.PlayerSurfaceView) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount)
                    find(view.getChildAt(index))?.let { return it }
                return null
            }
            onMain { assertTrue(playback.setLoop(3_000L, 7_000L, checkNotNull(playback.acquireControlLease(song.id)))) }
            val loopTool = compose.onNodeWithContentDescription("A-B loop")
            try {
                compose.waitUntil(5_000L) {
                    val node = compose.onAllNodes(hasContentDescription("A-B loop") and isEnabled())
                        .fetchSemanticsNodes().singleOrNull()
                    node != null && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f
                }
                loopTool.assertIsDisplayed().assertIsEnabled()
            } catch (failure: ComposeTimeoutException) {
                var geometry = ""
                onMain {
                    val surface = checkNotNull(find(compose.activity.window.decorView))
                    val clearance = surface.javaClass.getDeclaredField("loopClearanceMotionActive")
                        .apply { isAccessible = true }.get(surface)
                    geometry = "view=${surface.width}x${surface.height} tool=${surface.semanticsGeometry()?.loopTool} " +
                        "clearanceActive=$clearance"
                }
                failure.addSuppressed(AssertionError("Loop toolbar projection missing: $geometry " +
                    "lease=${playback.controlLease.value?.native} preparation=${playback.state.value.loopPreparation} " +
                    "loop=${playback.state.value.loopA}..${playback.state.value.loopB}"))
                throw failure
            }
            loopTool.performTouchInput { click(center) }
            compose.waitUntil(5_000L) {
                var settled = false
                onMain {
                    val surface = checkNotNull(find(compose.activity.window.decorView))
                    val motion = surface.javaClass.getDeclaredField("toolMotion").apply { isAccessible = true }
                        .get(surface) as app.stemwise.ui.player.surface.PlayerToolMotionOwner
                    settled = !motion.toolMotionActive &&
                        motion.toolMotionSelectedTool == app.stemwise.ui.player.surface.PlayerSurfaceTool.LOOP
                }
                settled
            }
            compose.onNodeWithText("Clear loop").assertIsDisplayed()
            val mute = compose.onNodeWithContentDescription("Mute Lead vocals: Off")
            try {
                compose.waitUntil(5_000L) {
                    val node = compose.onAllNodes(hasContentDescription("Mute Lead vocals: Off") and isEnabled())
                        .fetchSemanticsNodes().singleOrNull()
                    node != null && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f
                }
                mute.assertIsDisplayed().assertIsEnabled()
            } catch (failure: ComposeTimeoutException) {
                var native = ""
                onMain {
                    val surface = checkNotNull(find(compose.activity.window.decorView))
                    val motion = surface.javaClass.getDeclaredField("toolMotion").apply { isAccessible = true }
                        .get(surface) as app.stemwise.ui.player.surface.PlayerToolMotionOwner
                    native = "view=${surface.width}x${surface.height} lane=${surface.semanticsGeometry()?.mixerLaneAt(0)} " +
                        "tool=${motion.toolMotionSelectedTool} active=${motion.toolMotionActive}"
                }
                val nodes = compose.onAllNodes(hasContentDescription("Mute ", substring = true) or
                    hasContentDescription(" volume", substring = true)).fetchSemanticsNodes().take(18).joinToString { node ->
                    "${node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)} " +
                        "boundsInRoot=${node.boundsInRoot} enabled=${!node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}"
                }
                failure.addSuppressed(AssertionError("Settled mixer projection missing: $native exported=[$nodes]"))
                throw failure
            }
            mute.performTouchInput { down(center) }
            try {
                onMain {
                    val surface = checkNotNull(find(compose.activity.window.decorView))
                    fun field(name: String) = surface.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(surface)
                    val touches = field("mixerListTouches") as android.util.SparseArray<*>
                    if (touches.size() != 1) {
                        throw AssertionError("Physical DOWN did not reach the native mixer: " +
                            "sourcePending=${field("sourceMotionPending")} sourceActive=${field("sourceMotionActive")} " +
                            "controlsValid=${field("controlsValid")} headerHeight=${field("expandedHeaderHeightPx")} " +
                            "mute=${surface.semanticsGeometry()?.mixerLaneAt(0)?.mute} " +
                            "source=${playback.state.value.sourceSwitchState} lease=${playback.controlLease.value}")
                    }
                }
            } finally { mute.performTouchInput { up() } }
            assertFaderVolume("Lead vocals", 0, 0f)
            assertTrue(playback.state.value.stemMix.getValue("lead-vocals").muted)
        } finally {
            sets.value = previousSets
            assets.value = previousAssets
            runBlocking { withTimeout(8_000L) { playback.savedSongTrim(song) } }
            settingsStore.clear(settingsIdentity)
        }
    }

    @Test fun freshNeverPlayedPausedMixUsesThePublishedLeaseThroughTheRealPlayer() {
        val directory = File(application.cacheDir, "fresh-paused-mix").apply { check(mkdirs()) }
        val original = File(directory, "original.wav").also { writeSineWave(it, 10_000L, 220.0) }
        val vocals = File(directory, "vocals.wav").also { writeSineWave(it, 10_000L, 330.0) }
        val bass = File(directory, "bass.wav").also { writeSineWave(it, 10_000L, 440.0) }
        val song = Song("fresh-paused-mix", "fresh-paused-mix", "Fresh paused mix", "Fixture",
            10_000L, SongState.READY, Color.Cyan, hasStems = true, artworkGlyph = "P",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original), sourceCodec = "pcm_s16le",
            sourceContainer = "wav", audioRevision = "fresh-paused-audio")
        val parts = listOf(stemAsset("vocals", vocals, 10_000L), stemAsset("bass", bass, 10_000L),
            remainingAsset(bass, 10_000L, 440.0))
        val set = SeparationSetEntity(parts.first().separationSetId, song.id, "fixture-source", checkNotNull(song.sourceHash),
            "fixture", "fixture", "Fixture", null, SeparationRepository.STATE_COMPLETE, true, 0L)
        val repository = application.services.externalLibraryRepository
        @Suppress("UNCHECKED_CAST")
        fun <T> inventory(name: String) = repository.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(repository) as MutableStateFlow<T>
        val sets = inventory<Map<String, List<SeparationSetEntity>>>("mutableStemSets")
        val assets = inventory<Map<String, List<StemAssetEntity>>>("mutableStems")
        val previousSets = sets.value
        val previousAssets = assets.value
        try {
            onMain { assertTrue(playback.selectSong(song, autoplay = false, positionMs = 3_000L)) }
            waitUntil(8_000L) { playback.state.value.songId == song.id && !playback.state.value.loading }
            val initialLease = checkNotNull(playback.acquireControlLease(song.id))
            assertNull(initialLease.pausedPreparedStemMix)
            sets.value = previousSets + (song.id to listOf(set))
            assets.value = previousAssets + (set.id to parts)
            compose.setContent { StemwiseTheme {
                PlayerScreen(song, {}, audioPlayback = playback, library = listOf(song),
                    separationRepository = application.services.separationRepository,
                    analysisRepository = application.services.analysisRepository,
                    onDeleteSong = { Result.failure(IllegalStateException("Outside fixture")) }, modifier = Modifier.fillMaxSize())
            } }
            onMain {
                assertTrue(playback.setTempoAndPitch(1.25f, 3, initialLease))
                assertTrue(playback.setLoop(2_000L, 5_000L, initialLease))
            }
            val activation = runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
                playback.activateStems(parts, initialLease)
            } } }
            assertTrue(activation.isSuccess)
            try {
                compose.waitUntil(8_000L) { playback.controlLease.value?.pausedPreparedStemMix != null &&
                    compose.onAllNodesWithContentDescription("Vocals volume").fetchSemanticsNodes().size == 1 }
            } catch (timeout: ComposeTimeoutException) {
                var currentLease: PlaybackControlLease? = null
                onMain { currentLease = playback.acquireControlLease(song.id) }
                val publishedLease = playback.controlLease.value
                val nodes = compose.onAllNodesWithContentDescription("Vocals volume").fetchSemanticsNodes().size
                val state = playback.state.value
                throw AssertionError("Paused mixer readiness missing: publishedPrepared=${publishedLease?.pausedPreparedStemMix != null}, " +
                    "currentPrepared=${currentLease?.pausedPreparedStemMix != null}, vocalsNodes=$nodes, " +
                    "publishedLease=$publishedLease, currentLease=$currentLease, " +
                    "source=${state.sourceSwitchState}, loadedParts=${state.loadedStemAssets.map { it.id }}, " +
                    "songSha=${song.sourceHash}, setSha=${set.sourceSha256}\n" +
                    compose.onRoot(useUnmergedTree = true).printToString(), timeout)
            }
            val published = checkNotNull(playback.controlLease.value)
            assertEquals(initialLease.ownerEpoch, published.ownerEpoch)
            assertNotEquals(initialLease, published)
            val paused = checkNotNull(published.pausedPreparedStemMix)
            onMain {
                assertFalse(playback.commitPausedPreparedStemMix(paused.copy(packageRequestRevision = paused.packageRequestRevision + 1L),
                    "vocals", .1f, false, false))
            }
            compose.onNodeWithContentDescription("Vocals volume")
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.4f)) }
            assertEquals(.4f, playback.state.value.stemMix.getValue("vocals").volume, 0f)
            val fader = compose.onNodeWithContentDescription("Vocals volume")
            fader.performTouchInput { down(center); moveTo(androidx.compose.ui.geometry.Offset(width - 2f, centerY)) }
            assertEquals(.4f, playback.state.value.stemMix.getValue("vocals").volume, 0f)
            fader.performTouchInput { cancel() }
            assertEquals(.4f, playback.state.value.stemMix.getValue("vocals").volume, 0f)
            assertEquals(.4f, fader.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current, 0f)
            // This fixture intentionally lacks imported presentation metadata. Its real Retry
            // remains reachable without covering the native workspace, identity, or tool motors.
            val retry = compose.onNodeWithText("Retry song details").assertIsDisplayed()
            val retryBounds = retry.fetchSemanticsNode().boundsInWindow
            onMain {
                fun find(view: android.view.View): app.stemwise.ui.player.surface.PlayerSurfaceView? {
                    if (view is app.stemwise.ui.player.surface.PlayerSurfaceView) return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount)
                        find(view.getChildAt(index))?.let { return it }
                    return null
                }
                val surface = checkNotNull(find(compose.activity.window.decorView))
                val geometry = checkNotNull(surface.semanticsGeometry())
                val origin = IntArray(2).also(surface::getLocationInWindow)
                mapOf("mixer" to geometry.mixerViewport, "identity" to geometry.identity,
                    "loop" to geometry.loopTool, "key" to geometry.tuneTool,
                    "lyrics" to geometry.lyricsTool, "metronome" to geometry.metronomeTool).forEach { (name, native) ->
                    val nativeBounds = androidx.compose.ui.geometry.Rect(native.left + origin[0], native.top + origin[1],
                        native.right + origin[0], native.bottom + origin[1])
                    assertFalse("Retry $retryBounds covers native $name $nativeBounds", retryBounds.overlaps(nativeBounds))
                }
            }
            // Inject actual glyph touches, independently of the semantic toggle dispatcher.
            compose.onNodeWithContentDescription("Mute Vocals: Off").performTouchInput { click(center) }
            assertFaderVolume("Vocals", 0, 0f)
            assertTrue(playback.state.value.stemMix.getValue("vocals").muted)
            assertEquals(.4f, playback.state.value.stemMix.getValue("vocals").volume, 0f)
            compose.onNodeWithContentDescription("Mute Vocals: On").performTouchInput { click(center) }
            assertFaderVolume("Vocals", 0, .4f)
            compose.onNodeWithContentDescription("Mute Vocals: Off").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("Vocals volume")
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.3f)) }
            assertFaderVolume("Vocals", 0, .3f)
            assertFalse(playback.state.value.stemMix.getValue("vocals").muted)
            compose.onNodeWithContentDescription("Vocals volume")
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
            assertFaderVolume("Vocals", 0, 0f)
            assertTrue(playback.state.value.stemMix.getValue("vocals").muted)
            assertEquals(.3f, playback.state.value.stemMix.getValue("vocals").volume, 0f)
            compose.onNodeWithContentDescription("Mute Vocals: On").performTouchInput { click(center) }
            assertFaderVolume("Vocals", 0, .3f)
            compose.onNodeWithContentDescription("Vocals volume")
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.4f)) }
            compose.onNodeWithContentDescription("Mute Vocals: Off").performClick()
            compose.onNodeWithContentDescription("Solo Bass: Off").performClick()
            assertTrue(playback.state.value.stemMix.getValue("vocals").muted)
            assertTrue(playback.state.value.stemMix.getValue("bass").solo)
            // Source tabs expose visible text, unlike the mixer button descriptions above.
            try {
                compose.waitUntil(5_000L) {
                    playback.state.value.sourceSwitchState is SourceSwitchState.Stems &&
                        compose.onAllNodesWithText("Stems ready").fetchSemanticsNodes().size == 1
                }
            } catch (timeout: ComposeTimeoutException) {
                val current = playback.state.value
                throw AssertionError("Ready Stems tab missing: source=${current.sourceMode}, " +
                    "switch=${current.sourceSwitchState}, loading=${current.loading}, " +
                    "pausedLeaseCurrent=${playback.pausedPreparedStemMixLeaseIsCurrent(paused)}\n" +
                    compose.onRoot(useUnmergedTree = true).printToString(), timeout)
            }
            compose.onNodeWithText("Stems ready").assertIsEnabled().assertIsSelected().performClick()
            fun findSurface(view: android.view.View): app.stemwise.ui.player.surface.PlayerSurfaceView? {
                if (view is app.stemwise.ui.player.surface.PlayerSurfaceView) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount)
                    findSurface(view.getChildAt(index))?.let { return it }
                return null
            }
            fun readSurface(view: app.stemwise.ui.player.surface.PlayerSurfaceView) =
                (view.javaClass.getDeclaredField("fullPlayerState").apply { isAccessible = true }
                    .get(view) as app.stemwise.ui.player.nativeplayer.FullPlayerStateStore).snapshot
            fun surfaceSnapshot(): app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot? {
                var value: app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot? = null
                onMain {
                    val view = checkNotNull(findSurface(compose.activity.window.decorView))
                    value = readSurface(view)
                }
                return value
            }
            fun fine(active: Boolean) = onMain {
                val view = checkNotNull(findSurface(compose.activity.window.decorView))
                val actions = view.javaClass.getDeclaredField("sink").apply { isAccessible = true }
                    .get(view) as app.stemwise.ui.player.surface.FullPlayerActionSink
                actions.onFineModeChanged(checkNotNull(readSurface(view)).owner.generation, active, 3_000L)
            }
            fine(true)
            try {
                try {
                    compose.waitUntil(8_000L) { surfaceSnapshot()?.fineWindow?.let { window ->
                        window.peakCount > 0 && (0 until window.peakCount).any { window.peakAt(it) > 0f }
                    } == true }
                } catch (timeout: ComposeTimeoutException) {
                    var details = ""
                    onMain {
                        val view = checkNotNull(findSurface(compose.activity.window.decorView))
                        val actions = view.javaClass.getDeclaredField("sink").apply { isAccessible = true }.get(view)
                        @Suppress("UNCHECKED_CAST")
                        val key = actions.javaClass.getDeclaredField("readFineWindowKey").apply { isAccessible = true }
                            .get(actions) as () -> app.stemwise.ui.PlayerFineWaveformKey
                        details = "key=${key()}; facade=${playback.sourceWaveformIdentity()}; " +
                            "audio=${playback.state.value}; native=${playback.nativeDiagnosticsSnapshotForTest()?.runtime}"
                    }
                    throw AssertionError("Prepared stem waveform missing: $details", timeout)
                }
                compose.onNodeWithContentDescription("Mute Bass: Off").performClick()
                compose.waitUntil(8_000L) { surfaceSnapshot()?.let { snapshot ->
                    snapshot.fineWindow?.let { window -> window.peakCount > 0 &&
                        window.sourceRevision == snapshot.sourceMixer.source.revision &&
                        window.mixerRevision == snapshot.sourceMixer.mixer.revision &&
                        (0 until window.peakCount).all { window.peakAt(it) == 0f }
                    }
                } == true }
            } finally { fine(false) }
            compose.onNodeWithText("Reset mix").performClick()
            compose.onNodeWithText("Reset mix?").assertExists()
            compose.onNodeWithText("Reset mix").performClick()
            compose.waitUntil(5_000L) { playback.state.value.stemMix.values.all { it.volume == 1f && !it.muted && !it.solo } }
            val reset = playback.state.value
            assertFalse(reset.playWhenReady)
            assertFalse(reset.playing)
            assertEquals(1.25f, reset.tempo, 0f)
            assertEquals(3, reset.semitoneShift)
            assertEquals(2_000L, reset.loopA)
            assertEquals(5_000L, reset.loopB)
            assertTrue(playback.nativeDiagnosticsSnapshotForTest()?.runtime?.audibleControl?.actualSourceMode != NativeRuntimeSourceMode.STEMS)
            assertTrue(playback.pausedPreparedStemMixLeaseIsCurrent(paused))
            onMain { assertTrue(playback.play()) }
            compose.waitUntil(5_000L) { playback.controlLease.value?.pausedPreparedStemMix == null }
            onMain { assertFalse(playback.commitPausedPreparedStemMix(paused, "vocals", .2f, false, false)) }
        } finally {
            compose.runOnIdle { compose.activity.setContent {} }
            sets.value = previousSets
            assets.value = previousAssets
            isolateAndCleanOwnedState()
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    @Test fun resetChangesOnlyTheCapturedMixAndRejectsAReplacementOwner() {
        val directory = File(application.cacheDir, "player-mix-reset").apply { check(mkdirs()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val original = File(directory, "original.wav").also { writeSineWave(it, 10_000L, 220.0) }
            val vocals = File(directory, "vocals.wav").also { writeSineWave(it, 10_000L, 330.0) }
            val bass = File(directory, "bass.wav").also { writeSineWave(it, 10_000L, 440.0) }
            val song = Song(id = "player-mix-reset", videoId = "player-mix-reset", title = "Mix reset",
                artist = "Fixture", durationMs = 10_000L, state = SongState.READY, accent = Color.Cyan,
                hasStems = true, artworkGlyph = "M", sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
                sourceCodec = "pcm_s16le", sourceContainer = "wav", audioRevision = "mix-reset")
            val assets = listOf(stemAsset("vocals", vocals, 10_000L), stemAsset("bass", bass, 10_000L),
                remainingAsset(bass, 10_000L, 440.0))
            onMain { playback.selectSong(song, autoplay = true, positionMs = 3_000L) }
            waitUntil(8_000L) { playback.state.value.songId == song.id && playback.state.value.playing }
            var capturedLease: PlaybackControlLease? = null
            onMain { capturedLease = playback.acquireControlLease(song.id) }
            val lease = checkNotNull(capturedLease)
            val activation = runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
                playback.activateStems(assets, lease)
            } } }
            assertTrue(activation.isSuccess)
            waitUntil(8_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
            val packageSignature = checkNotNull(playback.state.value.loadedStemSignature)
            onMain {
                assertTrue(playback.setTempoAndPitch(1.25f, 3, lease))
                assertTrue(playback.setLoop(2_000L, 5_000L, lease))
            }
            // A paused-prepared package is not installed until its exact callback acknowledgement.
            waitUntil(8_000L) {
                val native = playback.nativeDiagnosticsSnapshotForTest() ?: return@waitUntil false
                val audible = native.runtime.audibleControl ?: return@waitUntil false
                val ownership = native.runtime.ownership
                val source = native.controllerSourceControlPublications.lastOrNull {
                    it.ownership == audible.ownership && it.nativeControlRevision == audible.controlRevision
                }
                audible.ownership.selectionToken == lease.ownerEpoch &&
                    audible.ownership == ownership.committed && ownership.requested == ownership.committed &&
                    ownership.accepted == ownership.committed && native.controllerPublicOwnership == ownership.committed &&
                    audible.actualSourceMode == NativeRuntimeSourceMode.STEMS &&
                    audible.actualTempo == 1.25f && audible.actualPitchSemitones == 3f &&
                    source?.stemPackageSignature == packageSignature &&
                    playback.state.value.sourceSwitchState is SourceSwitchState.Stems
            }
            lateinit var sink: OwnedPlayerActionSink
            lateinit var slots: Array<StemUiSlot>
            onMain {
                assertTrue(playback.commitStemMix("vocals", .4f, true, false, lease))
                assertTrue(playback.commitStemMix("bass", .5f, false, true, lease))
                slots = assets.map { asset ->
                    val mix = checkNotNull(playback.state.value.stemMix[asset.id])
                    StemUiSlot.from(Stem(asset.id, asset.id, Color.Cyan, mix.volume, mix.muted, mix.solo))
                }.toTypedArray()
                sink = OwnedPlayerActionSink(7L, lease, slots, Array<String?>(assets.size) { assets[it].id },
                    IntArray(assets.size) { it }, playback, song, 10_000L,
                    PlayerQueueProjection(listOf(song), listOf(song.id)), mutableLongStateOf(3_000L),
                    mutableStateOf(false), NativeFineWaveformOwner(), mutableStateOf(null),
                    NativeMixerGestureOwner(), NativeLoopBoundaryGestureOwner(), scope,
                    readFineWindowKey = { app.stemwise.ui.PlayerFineWaveformKey(
                        app.stemwise.ui.PlayerWaveformBinding(app.stemwise.ui.SongPresentationOwner.from(song),
                            PlaybackSourceMode.ORIGINAL, true, emptyList(), emptyMap()), null) },
                    readFineWindow = { _, _ -> null }, expandPlayer = {}, collapsePlayer = {}, showSongInfo = {},
                    selectPagerSong = { false }, changeTrack = { 0 }, skipArtwork = { false },
                    changeShuffle = {}, selectSource = { false }, selectTool = { false }, onControlFailure = {})
                assertTrue(sink.onSetSolo(7L, 0, true))
                assertTrue(sink.onSetSolo(7L, 1, false))
            }
            var heldCallback = 0L
            fun awaitHeldPcm(gain: Float?, audible: Boolean) {
                var observedCallback = heldCallback
                waitUntil(5_000L) {
                    val native = playback.nativeDiagnosticsSnapshotForTest() ?: return@waitUntil false
                    val owner = native.runtime.ownership.committed ?: return@waitUntil false
                    if (owner.selectionToken != lease.ownerEpoch) return@waitUntil false
                    if (!audible) {
                        // Silence has no audible-gain receipt; require later, fully owned final PCM.
                        val output = native.output ?: return@waitUntil false
                        val journal = playback.presentationJournalForTest(0L)
                        val span = journal.entries.lastOrNull {
                            it.generation == owner.generation && it.selectionToken == owner.selectionToken &&
                                it.callbackSequence == output.pcmLevelCallbackSequence &&
                                it.callbackPcmFrames == it.pcmFrames && it.continuityViolationCount == 0L
                        }
                        val silent = journal.coherent && span != null && output.playing &&
                            output.pcmLevelSelectionToken == owner.selectionToken &&
                            output.pcmLevelGeneration == owner.generation && output.pcmLevelFrames > 0L &&
                            output.pcmLevelCallbackSequence > heldCallback &&
                            output.pcmLevelPeak == 0f && output.pcmLevelMeanSquare == 0f
                        if (silent) observedCallback = output.pcmLevelCallbackSequence
                        return@waitUntil silent
                    }
                    val gainEvidence = native.stemGainCallbackSettlement ?: return@waitUntil false
                    val settled = gainEvidence.coherent && gainEvidence.ownerToken == owner.selectionToken &&
                        gainEvidence.ownerGeneration == owner.generation &&
                        gainEvidence.callbackSequence > heldCallback &&
                        (if (gain == null) gainEvidence.activeMask == 0 else
                            gainEvidence.activeMask and 1 != 0 &&
                                gainEvidence.gainBits[0] == java.lang.Float.floatToRawIntBits(gain)) &&
                        gainEvidence.nonZeroPcmFrameCount > 0L
                    if (settled) observedCallback = gainEvidence.callbackSequence
                    settled
                }
                heldCallback = observedCallback
            }
            lateinit var heldSingle: app.stemwise.ui.player.surface.PlayerSurfaceVolumeGesture
            onMain {
                heldSingle = checkNotNull(sink.onBeginVolume(7L, 0))
                assertTrue(sink.onPreviewVolume(heldSingle, .6f))
            }
            awaitHeldPcm(.6f, audible = true)
            onMain { assertTrue(sink.onPreviewVolume(heldSingle, 0f)) }
            awaitHeldPcm(0f, audible = false)
            onMain { assertTrue(sink.onPreviewVolume(heldSingle, .7f)) }
            awaitHeldPcm(.7f, audible = true)
            onMain {
                sink.onCancelVolume(heldSingle)
                assertEquals(.4f, slots[0].snapshot().volume, 0f)
                assertTrue(slots[0].snapshot().muted)
            }
            awaitHeldPcm(null, audible = false)
            val heldGestures = arrayOfNulls<app.stemwise.ui.player.surface.PlayerSurfaceVolumeGesture>(
                app.stemwise.ui.player.surface.PLAYER_SURFACE_MAX_MIXER_LANES)
            val heldVolumes = FloatArray(heldGestures.size)
            onMain {
                heldGestures[0] = checkNotNull(sink.onBeginVolume(7L, 0))
                heldGestures[1] = checkNotNull(sink.onBeginVolume(7L, 1))
                heldVolumes[0] = .6f; heldVolumes[1] = .25f
                assertTrue(sink.onPreviewVolumeBatch(7L, 3, heldGestures, heldVolumes))
            }
            awaitHeldPcm(.6f, audible = true)
            onMain {
                heldVolumes[0] = 0f
                assertTrue(sink.onPreviewVolumeBatch(7L, 3, heldGestures, heldVolumes))
            }
            // Bass has a positive preview but canonical Solo still excludes it.
            awaitHeldPcm(0f, audible = false)
            onMain {
                heldVolumes[0] = .8f
                assertTrue(sink.onPreviewVolumeBatch(7L, 3, heldGestures, heldVolumes))
            }
            awaitHeldPcm(.8f, audible = true)
            onMain { assertTrue(sink.onSetMuted(7L, 0, true)) }
            awaitHeldPcm(0f, audible = false)
            onMain { assertTrue(sink.onSetMuted(7L, 0, false)) }
            awaitHeldPcm(.8f, audible = true)
            onMain {
                assertTrue(sink.onSetMuted(7L, 0, true))
                sink.onCancelVolume(checkNotNull(heldGestures[0]))
                sink.onCancelVolume(checkNotNull(heldGestures[1]))
                assertTrue(slots[0].snapshot().muted)
                assertEquals(.8f, slots[0].snapshot().volume, 0f)
                assertTrue(sink.onSetSolo(7L, 0, false))
                assertTrue(sink.onSetSolo(7L, 1, true))
                assertTrue(playback.state.value.playing)
                val volume = checkNotNull(sink.onBeginVolume(7L, 0))
                assertTrue(sink.onPreviewVolume(volume, .6f))
                assertTrue(sink.onFinishVolume(volume, .6f))
                assertEquals(.6f, playback.state.value.stemMix.getValue("vocals").volume, 0f)
                assertFalse(playback.state.value.stemMix.getValue("vocals").muted)
                val zero = checkNotNull(sink.onBeginVolume(7L, 0))
                val bassGesture = checkNotNull(sink.onBeginVolume(7L, 1))
                val gestures = arrayOfNulls<app.stemwise.ui.player.surface.PlayerSurfaceVolumeGesture>(
                    app.stemwise.ui.player.surface.PLAYER_SURFACE_MAX_MIXER_LANES)
                val volumes = FloatArray(gestures.size)
                gestures[0] = zero; gestures[1] = bassGesture
                volumes[0] = 0f; volumes[1] = .25f
                assertTrue(sink.onPreviewVolumeBatch(7L, 3, gestures, volumes))
                assertTrue(sink.onFinishVolume(zero, 0f))
                assertTrue(sink.onFinishVolume(bassGesture, .25f))
                assertTrue(playback.state.value.stemMix.getValue("vocals").muted)
                assertEquals(.6f, playback.state.value.stemMix.getValue("vocals").volume, 0f)
                assertTrue(playback.state.value.stemMix.getValue("bass").solo)
                assertTrue(sink.onSetMuted(7L, 0, false))
                assertEquals(.6f, playback.state.value.stemMix.getValue("vocals").volume, 0f)
                val cancelled = checkNotNull(sink.onBeginVolume(7L, 0))
                assertTrue(sink.onPreviewVolume(cancelled, .2f))
                assertTrue(sink.onSetMuted(7L, 0, true))
                assertTrue(sink.onSetSolo(7L, 1, false))
                sink.onCancelVolume(cancelled)
                assertTrue(slots[0].snapshot().muted)
                assertEquals(.2f, slots[0].snapshot().volume, 0f)
                assertFalse(playback.state.value.stemMix.getValue("bass").solo)
                playback.pause()
            }
            waitUntil(5_000L) {
                val state = playback.state.value
                !state.playWhenReady && !state.playing &&
                    playback.nativeDiagnosticsSnapshotForTest()?.output?.playing == false
            }
            onMain {
                val before = playback.state.value
                assertFalse(before.playWhenReady)
                assertEquals(2_000L, before.loopA)
                assertEquals(5_000L, before.loopB)
                val controlsBefore = checkNotNull(playback.latestRequestedControlsForTest())
                assertFalse(sink.resetMix(8L))
                assertEquals(before.stemMix, playback.state.value.stemMix)
                assertEquals(controlsBefore, playback.latestRequestedControlsForTest())
                assertTrue(sink.resetMix(7L))
                assertEquals("Reset accepts exactly one complete control command", controlsBefore.revision + 1L,
                    playback.latestRequestedControlsForTest()?.revision)
                val reset = playback.state.value
                assertTrue(reset.stemMix.values.all { it.volume == 1f && !it.muted && !it.solo })
                assertEquals(1.25f, reset.tempo, 0f)
                assertEquals(3, reset.semitoneShift)
                assertEquals(before.loopA, reset.loopA)
                assertEquals(before.loopB, reset.loopB)
                assertEquals(before.playWhenReady, reset.playWhenReady)
                assertTrue(slots.all { it.snapshot().let { lane -> lane.volume == 1f && !lane.muted && !lane.solo } })
            }
            var stale: app.stemwise.ui.player.surface.PlayerSurfaceVolumeGesture? = null
            onMain {
                stale = checkNotNull(sink.onBeginVolume(7L, 0))
                assertTrue(sink.onPreviewVolume(checkNotNull(stale), .2f))
            }
            val replacement = song.copy(id = "mix-reset-replacement")
            onMain { assertTrue(playback.selectSong(replacement, autoplay = false)) }
            waitUntil(8_000L) {
                val owner = playback.selectionPressureDiagnosticsForTest()
                owner.latestRequestedSongId == replacement.id && owner.committedSongId == replacement.id &&
                    owner.latestRequestedToken != null && owner.latestRequestedToken != lease.ownerEpoch &&
                    owner.committedToken == owner.latestRequestedToken
            }
            onMain {
                assertFalse(sink.onFinishVolume(checkNotNull(stale), .2f))
                sink.onCancelVolume(checkNotNull(stale))
                assertEquals(1f, slots[0].snapshot().volume, 0f)
                assertFalse(slots[0].snapshot().muted)
                assertFalse(sink.resetMix(7L))
            }
        } finally {
            scope.cancel()
            isolateAndCleanOwnedState()
            check(!directory.exists() || directory.deleteRecursively())
        }
    }
}
