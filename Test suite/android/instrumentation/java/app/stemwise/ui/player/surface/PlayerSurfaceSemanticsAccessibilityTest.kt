package app.stemwise.ui.player.surface

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.input.key.Key

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import kotlin.math.floor
import kotlin.math.ceil
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.stemwise.ui.createStemwiseComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Accessibility acceptance for the semantics projection mounted over [PlayerSurfaceView]. */
class PlayerSurfaceSemanticsAccessibilityTest {
    @get:Rule
    val composeRule = createStemwiseComposeRule()

    @Test fun expandedSourceSwitchKeepsSongInformationAccessibleWhileWithdrawingArtworkActions() {
        val seed = connectedOriginalLoopSnapshot()
        val source = mutableStateOf(seed.sourceMixer.source.copy(stemsAvailable = true,
            stemsAvailabilitySettled = true, phase = PlayerSurfaceSourcePhase.SETTLED))
        val song = app.stemwise.model.Song(id = seed.owner.songId, videoId = "source-accessibility",
            title = "Song", artist = "Artist", durationMs = 120_000,
            state = app.stemwise.model.SongState.READY, accent = androidx.compose.ui.graphics.Color.Cyan,
            artworkGlyph = "SW", sourceCodec = "flac", mediaRevision = seed.owner.mediaRevision)
        val sink = object : PlayerSurfaceSemanticsActionSink by RecordingSink() {
            override fun onSource(generation: Long, target: PlayerSurfaceSourceMode): Boolean {
                if (generation != seed.owner.generation) return false
                source.value = source.value.copy(revision = source.value.revision + 1,
                    presentedMode = target, audibleMode = target)
                return true
            }
        }
        composeRule.setContent {
            app.stemwise.ui.PortraitNativePlayerSemantics(
                generation = seed.owner.generation, activeSong = song,
                semanticSongIdState = remember { mutableStateOf(song.id) }, orderedSongs = listOf(song),
                positionState = remember { mutableStateOf(0L) }, durationMs = song.durationMs,
                loopA = null, loopB = null, sections = emptyList(), sourcePacket = source.value,
                mixerSnapshots = emptyList(), transportPacket = seed.transport.copy(playWhenReady = false),
                toolDetail = seed.toolDetail, compact = false, controlsInteractive = true,
                bridge = remember { PlayerSurfaceHostBridge() },
                geometryState = remember { mutableStateOf(semanticsGeometry()) },
                lowerControlsInteractive = true, actionSink = sink, modifier = Modifier.fillMaxSize())
        }
        for (mode in listOf(PlayerSurfaceSourceMode.ORIGINAL, PlayerSurfaceSourceMode.STEMS,
                PlayerSurfaceSourceMode.ORIGINAL)) {
            val label = if (mode == PlayerSurfaceSourceMode.ORIGINAL) "Original" else "Stems ready"
            composeRule.onNodeWithText(label)
                .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            composeRule.onNodeWithText(label)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            composeRule.onNodeWithText("Song")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "FLAC"))
            composeRule.onNodeWithText("Artist").assertExists()
            val artwork = composeRule.onNodeWithContentDescription("Song artwork")
            if (mode == PlayerSurfaceSourceMode.ORIGINAL) {
                artwork.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            } else {
                artwork.assertDoesNotExist()
            }
        }
    }

    @Test fun scrollableMixerExportsVisibleLaneControlsAlongsideItsScrollContainer() {
        composeRule.setContent {
            PlayerSurfaceSemanticsOverlay(
                model = semanticsModel().copy(
                    source = PlayerSurfaceSourceSemantics(PlayerSurfaceSourceMode.STEMS, stemsEnabled = true),
                    mixer = PlayerSurfaceMixerSemantics(List(9) {
                        PlayerSurfaceMixerLaneSemantics("Part ${it + 1}", .8f, false, false)
                    }),
                    tools = semanticsModel().tools.copy(selectedTool = PlayerSurfaceTool.LOOP)
                ),
                timelineState = remember { mutableStateOf(PlayerSurfaceTimelineSemantics(120_000L, 0L)) },
                geometry = semanticsGeometry(scrollingMixer = true), actionSink = RecordingSink(),
                modifier = Modifier.fillMaxSize()
            )
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun exportedBounds(): Map<String, Rect> {
            val bounds = mutableMapOf<String, Rect>()
            fun visit(node: AccessibilityNodeInfo) {
                node.contentDescription?.toString()?.let { label ->
                    bounds[label] = Rect().also(node::getBoundsInScreen)
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
            }
            automation.rootInActiveWindow?.let(::visit)
            return bounds
        }
        val visibleControls = (1..4).flatMap { part ->
            listOf("Part $part volume", "Mute Part $part: Off", "Solo Part $part: Off")
        }
        composeRule.waitUntil(5_000L) {
            val exported = exportedBounds()
            exported["Song parts"]?.height()?.let { it > 1 } == true &&
                visibleControls.all { (exported[it]?.height() ?: 0) > 1 }
        }
        composeRule.onNodeWithContentDescription("Mute Part 1: Off").assertHasClickAction()
    }

    @Test fun externalTransportWithdrawsOnlyTheNativeTimelineAndTransportSemantics() {
        val external = mutableStateOf(false)
        composeRule.setContent {
            PlayerSurfaceSemanticsOverlay(model = semanticsModel(),
                timelineState = remember { mutableStateOf(PlayerSurfaceTimelineSemantics(120_000L, 0L)) },
                geometry = semanticsGeometry(), transportVisible = !external.value,
                actionSink = RecordingSink(), modifier = Modifier.fillMaxSize())
        }
        composeRule.onNodeWithContentDescription("Playback position").assertExists()
        composeRule.onNodeWithContentDescription("Next track").assertHasClickAction()
        composeRule.runOnIdle { external.value = true }
        composeRule.onNodeWithContentDescription("Playback position").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Next track").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Key").assertHasClickAction()
    }

    @Test fun aLyricsOnlyHydrationUpdatesTheRealRememberedPlayerSemantics() {
        val seed = connectedOriginalLoopSnapshot()
        val tools = mutableStateOf(seed.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
            activity = seed.toolDetail.activity.copy(lyricsAvailable = false)))
        val song = app.stemwise.model.Song(id = seed.owner.songId, videoId = "lyrics-only",
            title = "Song", artist = "Artist", durationMs = 120_000,
            state = app.stemwise.model.SongState.READY, accent = androidx.compose.ui.graphics.Color.Cyan,
            artworkGlyph = "SW", mediaRevision = seed.owner.mediaRevision)
        val sink = RecordingSink()
        composeRule.setContent {
            app.stemwise.ui.PortraitNativePlayerSemantics(
                generation = seed.owner.generation, activeSong = song,
                semanticSongIdState = remember { mutableStateOf(song.id) }, orderedSongs = listOf(song),
                positionState = remember { mutableStateOf(0L) }, durationMs = song.durationMs,
                loopA = null, loopB = null, sections = emptyList(), sourcePacket = seed.sourceMixer.source,
                mixerSnapshots = emptyList(), transportPacket = seed.transport.copy(playWhenReady = false),
                toolDetail = tools.value, compact = false, controlsInteractive = true,
                bridge = remember { PlayerSurfaceHostBridge() },
                geometryState = remember { mutableStateOf(semanticsGeometry()) },
                lowerControlsInteractive = true, actionSink = sink, modifier = Modifier.fillMaxSize())
        }
        composeRule.onNodeWithContentDescription("Lyrics").assertIsNotEnabled()
        composeRule.runOnIdle {
            tools.value = tools.value.copy(revision = tools.value.revision + 1,
                activity = tools.value.activity.copy(lyricsAvailable = true))
        }
        composeRule.onNodeWithContentDescription("Lyrics").assertIsEnabled()
        composeRule.runOnIdle {
            tools.value = tools.value.copy(revision = tools.value.revision + 1,
                activity = tools.value.activity.copy(lyricsAvailable = false))
        }
        composeRule.onNodeWithContentDescription("Lyrics").assertIsNotEnabled()
    }

    @Test fun compactIdentityOwnsKeyboardActivationAndWithdrawsItWithControls() {
        val interactive = mutableStateOf(true)
        val sink = RecordingSink()
        composeRule.setContent {
            val base = semanticsModel(compact = true)
            PlayerSurfaceSemanticsOverlay(model = base.copy(pager = base.pager.copy(controlsInteractive = interactive.value)),
                timelineState = remember { mutableStateOf(PlayerSurfaceTimelineSemantics(120_000L, 0L)) },
                geometry = semanticsGeometry(), actionSink = sink, modifier = Modifier.fillMaxSize())
        }
        val identity = composeRule.onNodeWithTag("player_identity_song-id", useUnmergedTree = true)
        identity.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        for (key in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)) {
            identity.performKeyInput { keyDown(key); keyUp(key) }
        }
        composeRule.runOnIdle { assertEquals(listOf(7L, 7L, 7L, 7L), sink.openGenerations); interactive.value = false }
        identity.assertHasNoClickAction()
        identity.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
    }

    @Test fun keyboardAndAccessibilityToggleTheMuteBitAtZeroAndNonzeroGain() {
        val volume = mutableStateOf(0f)
        val muted = mutableStateOf(false)
        val sink = RecordingSink()
        composeRule.setContent {
            val lane = PlayerSurfaceMixerLaneSemantics("Vocals", volume.value, muted.value, false)
            PlayerSurfaceSemanticsOverlay(
                model = semanticsModel().copy(
                    source = PlayerSurfaceSourceSemantics(PlayerSurfaceSourceMode.STEMS, stemsEnabled = true),
                    mixer = PlayerSurfaceMixerSemantics(listOf(lane))
                ),
                timelineState = remember { mutableStateOf(PlayerSurfaceTimelineSemantics(120_000L, 0L)) },
                geometry = semanticsGeometry(withMixer = true), actionSink = sink,
                modifier = Modifier.fillMaxSize()
            )
        }
        for ((gain, explicitMute) in listOf(0f to false, .65f to true, .65f to false)) {
            composeRule.runOnIdle { volume.value = gain; muted.value = explicitMute }
            val effectivelyMuted = explicitMute || gain == 0f
            val fader = composeRule.onNodeWithContentDescription("Vocals volume")
            fader.assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(if (effectivelyMuted) 0f else gain, 0f..1f)))
            if (explicitMute) {
                fader.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                fader.performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
                composeRule.runOnIdle { assertEquals(.05f, sink.volumeCommands.last(), 0f) }
            }
            val mute = composeRule.onNodeWithContentDescription("Mute Vocals: ${if (effectivelyMuted) "On" else "Off"}")
            mute.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            mute.performKeyInput { keyDown(Key.Spacebar); keyUp(Key.Spacebar) }
            mute.performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        composeRule.runOnIdle { assertEquals(listOf(false, false, false, false, true, true), sink.muteCommands) }
    }

    @Test fun keyboardSeekMovesForwardWithTheRenderedTimelineInBothLayoutDirections() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val sink = RecordingSink(positionMs = 30_000L)
        val timeline = mutableStateOf(PlayerSurfaceTimelineSemantics(120_000L, 30_000L))
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                PlayerSurfaceSemanticsOverlay(model = semanticsModel(), timelineState = timeline,
                    geometry = semanticsGeometry(), actionSink = sink, modifier = Modifier.fillMaxSize())
            }
        }
        for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            composeRule.runOnIdle { direction.value = layout }
            val seeker = composeRule.onNodeWithContentDescription("Playback position")
            seeker.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            composeRule.runOnIdle { sink.onSeekTo(7L, 30_000L); sink.seekPositions.clear() }
            seeker.performKeyInput {
                keyDown(Key.DirectionRight); keyUp(Key.DirectionRight)
                keyDown(Key.DirectionRight); keyUp(Key.DirectionRight)
                keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft)
            }
            composeRule.runOnIdle { assertEquals(listOf(35_000L, 40_000L, 35_000L), sink.seekPositions) }
        }
    }

    @Test
    fun measuredNativeGeometryOwnsExpandedRtlAndCompactSemanticBounds() {
        val configuration = mutableStateOf(false to LayoutDirection.Ltr)
        val geometry = mutableStateOf<PlayerSurfaceSemanticsGeometry?>(null)
        val snapshot = connectedOriginalLoopSnapshot()
        val nativeSink = NativePlayerSurfaceFixture.geometryActionSink()
        composeRule.setContent {
            val density = LocalDensity.current.density
            val (compact, direction) = configuration.value
            val applied = remember { arrayOf<String?>(null) }
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.size(360.dp, 700.dp).testTag("native_semantics_host")) {
                    AndroidView(factory = { PlayerSurfaceView(it) }, modifier = Modifier.fillMaxSize(), update = { view ->
                        view.doOnLayout {
                            val key = "${view.width}:${view.height}:$compact:$direction"
                            if (applied[0] != key) {
                                applied[0] = key
                                view.updateRouteBaseMetrics(if (compact) 0f else 1f, 1f,
                                    60f * density, 18f, 72f, 18f, false, 12f, 1f,
                                    12f * density, 120f * density, 96f, 68f)
                                view.bind(snapshot, nativeSink, PlayerSurfacePositionProvider { 10_000L },
                                    view.width, view.height, density,
                                    if (direction == LayoutDirection.Rtl) PlayerSurfaceLayoutDirection.RIGHT_TO_LEFT
                                    else PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
                                view.setControlBounds(20f * density, view.height * .58f,
                                    view.width - 20f * density, view.height - 20f * density)
                                geometry.value = requireNotNull(view.semanticsGeometry())
                            }
                        }
                    })
                    geometry.value?.let { measured ->
                        PlayerSurfaceSemanticsOverlay(
                            model = semanticsModel(compact = compact).copy(generation = snapshot.owner.generation,
                                pager = semanticsModel(compact = compact).pager.copy(songId = snapshot.owner.songId)),
                            timelineState = remember { mutableStateOf(PlayerSurfaceTimelineSemantics(60_000L, 10_000L)) },
                            geometry = measured, actionSink = RecordingSink(), modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        for ((compact, direction) in listOf(false to LayoutDirection.Ltr, false to LayoutDirection.Rtl,
                true to LayoutDirection.Rtl)) {
            composeRule.runOnIdle { configuration.value = compact to direction }
            composeRule.waitForIdle()
            val measured = requireNotNull(geometry.value)
            val origin = composeRule.onNodeWithTag("native_semantics_host").fetchSemanticsNode().boundsInRoot.topLeft
            fun bounds(tag: String? = null, description: String? = null, expected: PlayerSurfaceBounds) {
                val node = if (tag != null) composeRule.onNodeWithTag(tag, useUnmergedTree = true)
                    else composeRule.onNodeWithContentDescription(checkNotNull(description), useUnmergedTree = true)
                val actual = node.fetchSemanticsNode().boundsInRoot
                assertEquals("left $tag $description", origin.x + floor(expected.left), actual.left, 1f)
                assertEquals("top $tag $description", origin.y + floor(expected.top), actual.top, 1f)
                assertEquals("right $tag $description", origin.x + ceil(expected.right), actual.right, 1f)
                assertEquals("bottom $tag $description", origin.y + ceil(expected.bottom), actual.bottom, 1f)
            }
            if (compact) {
                bounds(tag = "player_identity_${snapshot.owner.songId}", expected = measured.identity)
                composeRule.onNodeWithContentDescription("Playback position").assertDoesNotExist()
            } else {
                assertTrue("Native control fixtures collapsed to one placeholder", measured.seeker != measured.next)
                bounds(description = "Playback position", expected = measured.seeker)
                bounds(description = "Next track", expected = measured.next)
                bounds(description = "A-B loop", expected = measured.loopTool)
                bounds(description = "Collapse player", expected = measured.collapse)
            }
        }
    }

    @Test
    fun movingLowerControlsWithdrawWithoutHidingStationarySemantics() {
        val timeline = mutableStateOf(
            PlayerSurfaceTimelineSemantics(durationMs = 120_000L, positionMs = 30_000L)
        )
        composeRule.setContent {
            PlayerSurfaceSemanticsOverlay(
                model = semanticsModel(),
                timelineState = timeline,
                geometry = semanticsGeometry(),
                lowerControlsInteractive = false,
                actionSink = RecordingSink(),
                modifier = Modifier.fillMaxSize()
            )
        }

        composeRule.onNodeWithContentDescription("Next track").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("A-B loop").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Playback position").assertExists()
        composeRule.onNodeWithContentDescription("Collapse player").assertExists()
        composeRule.onNodeWithTag("player_source_mode_group", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
    }

    @Test
    fun compactPlayerHasOneSemanticOpenOwner() {
        val timeline = mutableStateOf(
            PlayerSurfaceTimelineSemantics(durationMs = 120_000L, positionMs = 30_000L)
        )
        composeRule.setContent {
            PlayerSurfaceSemanticsOverlay(
                model = semanticsModel(compact = true),
                timelineState = timeline,
                geometry = semanticsGeometry(),
                actionSink = RecordingSink(),
                modifier = Modifier.fillMaxSize()
            )
        }

        composeRule.onNodeWithTag("player_artwork_song-id", useUnmergedTree = true)
            .assertHasNoClickAction()
        composeRule.onNodeWithTag("player_identity_song-id", useUnmergedTree = true)
            .assertHasClickAction()
    }

    @Test
    fun nativeTimelineProjectionExposesDurableProgressAndState() {
        val timeline = mutableStateOf(
            PlayerSurfaceTimelineSemantics(
                durationMs = 120_000L,
                positionMs = 30_000L
            )
        )
        composeRule.setContent {
            PlayerSurfaceSemanticsOverlay(
                model = semanticsModel(),
                timelineState = timeline,
                geometry = semanticsGeometry(),
                actionSink = RecordingSink(),
                modifier = Modifier.fillMaxSize()
            )
        }

        val seeker = composeRule.onNodeWithContentDescription("Playback position")
        seeker
            .assertContentDescriptionEquals("Playback position")
            .assert(
                hasProgressBarRangeInfo(
                    ProgressBarRangeInfo(
                        current = 30_000f,
                        range = 0f..120_000f
                    )
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "30 seconds of 2 minutes 0 seconds"
                )
            )
    }

    @Test
    fun nativeTimelineProjectionRoutesFixedAndExactSeeksThroughGenerationSink() {
        val sink = RecordingSink(positionMs = 30_750L)
        val timeline = mutableStateOf(
            PlayerSurfaceTimelineSemantics(
                durationMs = 120_000L,
                positionMs = 30_000L
            )
        )
        composeRule.setContent {
            PlayerSurfaceSemanticsOverlay(
                model = semanticsModel(),
                timelineState = timeline,
                geometry = semanticsGeometry(),
                actionSink = sink,
                modifier = Modifier.fillMaxSize()
            )
        }

        val seeker = composeRule.onNodeWithContentDescription("Playback position")
        val actions = seeker.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnIdle {
            check(actions.single { it.label == "Seek backward 5 seconds" }.action())
            check(actions.single { it.label == "Seek forward 5 seconds" }.action())
            check(actions.single { it.label == "Nudge backward 5 milliseconds" }.action())
            check(actions.single { it.label == "Nudge forward 5 milliseconds" }.action())
            check(actions.single { it.label == "Nudge forward 5 milliseconds" }.action())
        }
        seeker.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            check(setProgress(30_003f))
        }

        composeRule.runOnIdle {
            assertEquals(
                listOf(25_750L, 30_750L, 30_745L, 30_750L, 30_755L, 30_003L),
                sink.seekPositions
            )
            assertEquals(List(6) { 7L }, sink.generations)
        }
    }

    @Test
    fun unknownDurationTimelineIsDescriptiveButNotSeekable() {
        val timeline = mutableStateOf(
            PlayerSurfaceTimelineSemantics(durationMs = 0L, positionMs = 0L)
        )
        composeRule.setContent {
            PlayerSurfaceSemanticsOverlay(
                model = semanticsModel(),
                timelineState = timeline,
                geometry = semanticsGeometry(),
                actionSink = RecordingSink(),
                modifier = Modifier.fillMaxSize()
            )
        }

        composeRule.onNodeWithContentDescription("Playback position")
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
    }

    @Test
    fun unavailableNextIsDisabledAndCannotDispatch() {
        val timeline = mutableStateOf(
            PlayerSurfaceTimelineSemantics(durationMs = 120_000L, positionMs = 30_000L)
        )
        composeRule.setContent {
            PlayerSurfaceSemanticsOverlay(
                model = semanticsModel(nextEnabled = false),
                timelineState = timeline,
                geometry = semanticsGeometry(),
                actionSink = RecordingSink(),
                modifier = Modifier.fillMaxSize()
            )
        }

        composeRule.onNodeWithContentDescription("Next track")
            .assertIsNotEnabled()
            .assertHasNoClickAction()
    }

    private fun semanticsModel(
        compact: Boolean = false,
        nextEnabled: Boolean = true
    ) = PlayerSurfaceStaticSemanticsModel(
        generation = 7L,
        pager = PlayerSurfacePagerSemantics(
            songId = "song-id",
            title = "Song",
            artist = "Artist",
            compact = compact
        ),
        source = PlayerSurfaceSourceSemantics(
            selectedMode = PlayerSurfaceSourceMode.ORIGINAL,
            stemsEnabled = false
        ),
        mixer = PlayerSurfaceMixerSemantics(emptyList()),
        transport = PlayerSurfaceTransportSemantics(
            playWhenReady = false,
            shuffleEnabled = false,
            repeatMode = PlayerSurfaceRepeatMode.OFF,
            nextEnabled = nextEnabled
        ),
        tools = PlayerSurfaceToolSemantics(
            selectedTool = PlayerSurfaceTool.NONE,
            lyricsAvailable = false,
            metronomeAvailable = false
        )
    )

    private fun semanticsGeometry(withMixer: Boolean = false, scrollingMixer: Boolean = false): PlayerSurfaceSemanticsGeometry {
        val target = PlayerSurfaceBounds(0f, 0f, 48f, 48f)
        val viewport = PlayerSurfaceBounds(20f, 80f, 340f, 280f)
        val mixerLanes = if (scrollingMixer) List(9) { lane ->
            val top = 80f + lane * 50f
            fun clipped(left: Float, right: Float) = playerSurfaceMixerScrolledBounds(
                PlayerSurfaceBounds(left, top, right, top + 48f), viewport, 0f)
            PlayerSurfaceMixerLaneSemanticsBounds(clipped(80f, 280f), clipped(20f, 68f), clipped(292f, 340f))
        } else if (withMixer) listOf(PlayerSurfaceMixerLaneSemanticsBounds(target, target, target)) else emptyList()
        return requireNotNull(
            PlayerSurfaceSemanticsGeometry.createOrNull(
                widthPx = 360,
                heightPx = 772,
                page = target,
                artwork = target,
                identity = target,
                title = target,
                artist = target,
                original = target,
                stems = target,
                seeker = target,

                shuffle = target,
                previous = target,
                playPause = target,
                miniPlayPause = target,
                next = target,
                repeat = target,
                collapse = target,
                info = target,
                loopTool = target,
                tuneTool = target,
                lyricsTool = target,
                metronomeTool = target,
                mixerLanes = mixerLanes,
                mixerViewport = if (scrollingMixer) viewport else if (withMixer) target else PlayerSurfaceBounds(0f, 0f, 0f, 0f),
                mixerScrollMaximumPx = if (scrollingMixer) 248f else 0f
            )
        )
    }

    private class RecordingSink(private var positionMs: Long = 0L) : PlayerSurfaceSemanticsActionSink {
        val generations = mutableListOf<Long>()
        val seekPositions = mutableListOf<Long>()
        val muteCommands = mutableListOf<Boolean>()
        val volumeCommands = mutableListOf<Float>()
        val openGenerations = mutableListOf<Long>()

        override fun onSeekTo(generation: Long, positionMs: Long): Boolean {
            this.positionMs = positionMs
            generations += generation
            seekPositions += positionMs
            return true
        }

        override fun onSeekBy(generation: Long, deltaMs: Long): Boolean =
            onSeekTo(generation, positionMs + deltaMs)

        override fun onOpenPlayer(generation: Long): Boolean { openGenerations += generation; return true }
        override fun onCollapsePlayer(generation: Long) = true
        override fun onShowSongInfo(generation: Long) = true
        override fun onTool(generation: Long, tool: PlayerSurfaceTool) = true
        override fun onPagerStep(generation: Long, delta: Int) = true
        override fun onArtworkSkip(generation: Long, deltaMs: Long) = true
        override fun onSource(generation: Long, target: PlayerSurfaceSourceMode) = true
        override fun onMixerVolume(generation: Long, laneIndex: Int, volume: Float): Boolean {
            volumeCommands += volume
            return true
        }
        override fun onMixerMute(generation: Long, laneIndex: Int, muted: Boolean): Boolean {
            muteCommands += muted
            return true
        }
        override fun onMixerSolo(generation: Long, laneIndex: Int, solo: Boolean) = true
        override fun onTransport(
            generation: Long,
            command: PlayerSurfaceTransportCommand
        ) = true
    }
}
