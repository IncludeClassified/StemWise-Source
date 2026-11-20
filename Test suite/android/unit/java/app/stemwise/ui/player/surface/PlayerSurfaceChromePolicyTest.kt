package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.*

import android.view.MotionEvent
import app.stemwise.ui.design.generated.StemwiseIconRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfaceChromePolicyTest {
    @Test fun repeatPresentationDistinguishesOneAndSharesTheCurrentMode() {
        assertEquals(PlayerSurfaceRepeatMode.OFF, playerSurfaceRepeatMode("Off"))
        assertEquals(PlayerSurfaceRepeatMode.ALL, playerSurfaceRepeatMode("All"))
        assertEquals(PlayerSurfaceRepeatMode.ONE, playerSurfaceRepeatMode("One"))
        assertEquals(StemwiseIconRole.PLAYER_REPEAT_ALL, playerSurfaceRepeatMode("All").transportIcon().catalogRole())
        assertEquals(StemwiseIconRole.PLAYER_REPEAT_ONE, playerSurfaceRepeatMode("One").transportIcon().catalogRole())
        assertNotEquals(playerSurfaceRepeatMode("All").transportIcon(), playerSurfaceRepeatMode("One").transportIcon())
    }

    // These adapters only expose retained production output as readable expected bounds.
    private fun playerSurfacePartitionedRowTarget(index: Int, centers: FloatArray,
        left: Float, right: Float, top: Float, bottom: Float): PlayerSurfaceBounds {
        val visual = FloatArray(centers.size * 4)
        val hits = FloatArray(visual.size)
        centers.forEachIndexed { i, center -> visual[i * 4] = center; visual[i * 4 + 2] = center }
        fillPlayerSurfacePartitionedRowTargets(visual, hits, centers.size, left, right, top, bottom)
        return bounds(hits, index * 4)
    }
    private fun playerSurfaceMixerToggleTargets(cardTop: Float, cardRight: Float,
        cardBottom: Float, density: Float, cardLeft: Float = 0f): ToggleTargets {
        val mute = FloatArray(4)
        val solo = FloatArray(4)
        fillPlayerSurfaceMixerToggleTargets(cardTop, cardRight, cardBottom, density,
            cardLeft, mute, solo, 0)
        return ToggleTargets(bounds(mute), bounds(solo))
    }
    private data class ToggleTargets(val mute: PlayerSurfaceBounds, val solo: PlayerSurfaceBounds)
    private fun playerSurfaceMixerToggleVisualBounds(target: PlayerSurfaceBounds, density: Float,
        mixerCardHeightPx: Float): PlayerSurfaceBounds {
        val result = FloatArray(4)
        fillPlayerSurfaceMixerToggleVisualBounds(
            floatArrayOf(target.left, target.top, target.right, target.bottom), result, 0,
            density, mixerCardHeightPx)
        return bounds(result)
    }
    private fun bounds(values: FloatArray, offset: Int = 0) = PlayerSurfaceBounds(
        values[offset], values[offset + 1], values[offset + 2], values[offset + 3])

    @Test fun retainedMixerOutputsCoverAllNineLanesAcrossScrollingWithoutChangingEarlierSlots() {
        val mute = FloatArray(9 * 4)
        val solo = FloatArray(9 * 4)
        val visual = FloatArray(9 * 4)
        for (scroll in listOf(0f, 80f)) for (lane in 0 until 9) {
            val top = 100f + lane * 70f - scroll
            fillPlayerSurfaceMixerToggleTargets(top, 412f, top + 64f, 1f, 20f, mute, solo, lane * 4)
            fillPlayerSurfaceMixerToggleVisualBounds(mute, visual, lane * 4, 1f, 64f)
            assertEquals(26f, mute[lane * 4], .001f)
            assertEquals(top + 4f, mute[lane * 4 + 1], .001f)
            assertEquals(76f, mute[lane * 4 + 2], .001f)
            assertEquals(356f, solo[lane * 4], .001f)
            assertEquals(42f, visual[lane * 4 + 2] - visual[lane * 4], .001f)
        }
        assertEquals(24f, mute[1], .001f)
        assertEquals(584f, mute[8 * 4 + 1], .001f)
    }

    @Test
    fun `transport availability invalidates retained chrome independently of other state`() {
        val baseline = PlayerSurfaceTransportPacket(
            revision = 1L,
            playWhenReady = false,
            repeatMode = PlayerSurfaceRepeatMode.OFF,
            shuffleEnabled = false,
            canGoPrevious = false,
            canGoNext = false
        )

        assertFalse(
            playerSurfaceTransportAvailabilityChanged(
                baseline,
                baseline.copy(revision = 2L)
            )
        )
        assertTrue(
            playerSurfaceTransportAvailabilityChanged(
                baseline,
                baseline.copy(revision = 2L, canGoPrevious = true)
            )
        )
        assertTrue(
            playerSurfaceTransportAvailabilityChanged(
                baseline,
                baseline.copy(revision = 2L, canGoNext = true)
            )
        )
    }

    @Test
    fun sourceIconAndMeasuredLabelStayCenteredForEveryDisplayedLabel() {
        listOf(
            Triple(21f, 55f, "ORIGINAL"),
            Triple(23f, 39f, "STEMS"),
            Triple(23f, 72f, "UNAVAILABLE")
        ).forEach { (iconWidth, measuredLabelWidth, label) ->
            val start = playerSurfaceSourceContentStartX(
                left = 0f,
                right = 124f,
                iconWidth = iconWidth,
                labelWidth = measuredLabelWidth,
                gap = 8f
            )
            assertEquals(label, 62f, start + (iconWidth + 8f + measuredLabelWidth) * .5f, .001f)
        }
    }

    @Test
    fun transportCentersKeepAuthoredSpacingAndCompactOnlyAtTheViewportEdges() {
        val master = (0..4).map { playerSurfaceTransportCenterXDp(it, 432f) }
        listOf(56f, 134f, 216f, 298f, 376f).zip(master).forEach { (expected, actual) ->
            assertEquals(expected, actual, .001f)
        }

        val compact = (0..4).map { playerSurfaceTransportCenterXDp(it, 320f) }
        assertEquals(24f, compact.first(), .001f)
        assertEquals(160f, compact[2], .001f)
        assertEquals(296f, compact.last(), .001f)
        assertThrows(IllegalArgumentException::class.java) {
            playerSurfaceTransportCenterXDp(2, 0f)
        }
    }

    @Test
    fun rowTargetsMeetAtNeighbourMidpointsWithoutOverlap() {
        val centers = floatArrayOf(66f, 166f, 266f, 366f)
        val targets = centers.indices.map { index ->
            playerSurfacePartitionedRowTarget(index, centers, 20f, 412f, 100f, 168f)
        }

        assertEquals(20f, targets.first().left, .001f)
        assertEquals(412f, targets.last().right, .001f)
        targets.zipWithNext().forEach { (left, right) ->
            assertEquals(left.right, right.left, .001f)
        }
        assertTrue(targets.all { it.height == 68f })
    }

    @Test
    fun timelineAndVolumeUpdatesReuseMixerChrome() {
        val visuals = visuals()
        val first = profile(
            timelineRevision = 1L,
            positionMs = 100L,
            mixerRevision = 1L,
            volumes = floatArrayOf(.2f, .8f)
        )
        val updated = profile(
            timelineRevision = 9L,
            positionMs = 900L,
            mixerRevision = 7L,
            volumes = floatArrayOf(.7f, .1f)
        )

        assertEquals(
            playerSurfaceMixerChromeContentKey(first, visuals, PlayerSurfaceSourceMode.STEMS),
            playerSurfaceMixerChromeContentKey(updated, visuals, PlayerSurfaceSourceMode.STEMS)
        )
    }

    @Test
    fun topologyVisualStateAndSourceChangesInvalidateMixerChrome() {
        val base = profile()
        val visuals = visuals()
        assertNotEquals(
            playerSurfaceMixerChromeContentKey(base, visuals, PlayerSurfaceSourceMode.STEMS),
            playerSurfaceMixerChromeContentKey(
                profile(packageRevision = 2L),
                visuals,
                PlayerSurfaceSourceMode.STEMS
            )
        )
        assertNotEquals(
            playerSurfaceMixerChromeContentKey(base, visuals, PlayerSurfaceSourceMode.STEMS),
            playerSurfaceMixerChromeContentKey(
                base,
                visuals(label = "Lead voice"),
                PlayerSurfaceSourceMode.STEMS
            )
        )
        assertNotEquals(
            playerSurfaceMixerChromeContentKey(base, visuals, PlayerSurfaceSourceMode.STEMS),
            playerSurfaceMixerChromeContentKey(base, visuals, PlayerSurfaceSourceMode.ORIGINAL)
        )
    }

    @Test
    fun retainedChromeRequiresHardwareAndASettledFullRoute() {
        assertTrue(playerSurfaceRetainedChromeEligible(true, true, 1f))
        assertFalse(playerSurfaceRetainedChromeEligible(false, true, 1f))
        assertFalse(playerSurfaceRetainedChromeEligible(true, false, 1f))
        assertFalse(playerSurfaceRetainedChromeEligible(true, true, .98f))
    }

    @Test
    fun mixerMuteAndSoloOwnSlightlyExpandedNonOverlappingTargets() {
        val targets = playerSurfaceMixerToggleTargets(
            cardTop = 10f,
            cardLeft = 20f,
            cardRight = 170f,
            cardBottom = 51f,
            density = 1f
        )
        assertEquals(51f, (targets.mute.left + targets.mute.right) * .5f, .001f)
        assertEquals(139f, (targets.solo.left + targets.solo.right) * .5f, .001f)
        assertEquals(50f, targets.mute.width, .001f)
        assertEquals(41f, targets.mute.height, .001f)
        assertEquals(50f, targets.solo.width, .001f)
        assertEquals(41f, targets.solo.height, .001f)
        assertTrue(targets.mute.right <= targets.solo.left)

        val movingTargetTops = listOf(64f, 56f, 48f).map { cardHeight ->
            playerSurfaceMixerToggleTargets(
                cardTop = 10f,
                cardRight = 170f,
                cardBottom = 10f + cardHeight,
                density = 1f
            ).mute.top
        }
        assertEquals(listOf(14f, 13f, 10f), movingTargetTops)

        listOf(targets.mute, targets.solo).forEach { target ->
            val closed = playerSurfaceMixerToggleVisualBounds(
                target,
                density = 1f,
                mixerCardHeightPx = 64f
            )
            val midpoint = playerSurfaceMixerToggleVisualBounds(
                target,
                density = 1f,
                mixerCardHeightPx = 56f
            )
            val open = playerSurfaceMixerToggleVisualBounds(
                target,
                density = 1f,
                mixerCardHeightPx = 48f
            )
            assertEquals(42f, closed.width, .001f)
            assertEquals(44f, closed.height, .001f)
            assertEquals(41f, midpoint.width, .001f)
            assertEquals(42f, midpoint.height, .001f)
            assertEquals(40f, open.width, .001f)
            assertEquals(40f, open.height, .001f)
            listOf(closed, midpoint, open).forEach { visible ->
                assertEquals(
                    (target.left + target.right) * .5f,
                    (visible.left + visible.right) * .5f,
                    .001f
                )
                assertEquals(
                    (target.top + target.bottom) * .5f,
                    (visible.top + visible.bottom) * .5f,
                    .001f
                )
            }
        }
    }

    @Test
    fun mixerAndNativeTextUseTheirVisibleCentersAtBothEndpoints() {
        listOf(48f to 34f, 56f to 38f, 64f to 39f, 80f to 47f).forEach {
                (height, expectedCenter) ->
            val center = playerSurfaceMixerVisibleRowCenterY(10f, 10f + height, 1f)
            val mute = playerSurfaceMixerToggleTargets(10f, 170f, 10f + height, 1f).mute
            assertEquals(expectedCenter, center, .001f)
            assertEquals(center, (mute.top + mute.bottom) * .5f, .001f)
        }
        assertEquals(53f, playerSurfaceCenteredTextBaselineY(50f, -10f, 4f), .001f)
        assertEquals(51f, playerSurfaceBottomSafeTextBaselineY(54f, 58f, 3f, 4f), .001f)
        assertEquals(54f, playerSurfaceBottomSafeTextBaselineY(54f, 68f, 3f, 4f), .001f)
    }

    @Test
    fun movingActionRemainsOwnedByItsOriginalPointer() {
        val captured = 7
        assertEquals(
            PlayerSurfaceActionPointerDisposition.KEEP,
            playerSurfaceActionPointerDisposition(
                captured,
                MotionEvent.ACTION_MOVE,
                MotionEvent.INVALID_POINTER_ID,
                capturedPointerIndex = 1
            )
        )
        assertEquals(
            PlayerSurfaceActionPointerDisposition.CANCEL,
            playerSurfaceActionPointerDisposition(
                captured,
                MotionEvent.ACTION_MOVE,
                MotionEvent.INVALID_POINTER_ID,
                capturedPointerIndex = -1
            )
        )
        assertEquals(
            PlayerSurfaceActionPointerDisposition.KEEP,
            playerSurfaceActionPointerDisposition(
                captured,
                MotionEvent.ACTION_POINTER_UP,
                actionPointerId = 3,
                capturedPointerIndex = 0
            )
        )
        assertEquals(
            PlayerSurfaceActionPointerDisposition.FINISH,
            playerSurfaceActionPointerDisposition(
                captured,
                MotionEvent.ACTION_POINTER_UP,
                actionPointerId = captured,
                capturedPointerIndex = 1
            )
        )
        assertEquals(
            PlayerSurfaceActionPointerDisposition.CANCEL,
            playerSurfaceActionPointerDisposition(
                captured,
                MotionEvent.ACTION_UP,
                actionPointerId = 3,
                capturedPointerIndex = -1
            )
        )
        assertEquals(
            PlayerSurfaceActionPointerDisposition.CANCEL,
            playerSurfaceActionPointerDisposition(
                captured,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.INVALID_POINTER_ID,
                capturedPointerIndex = 0
            )
        )
    }

    @Test
    fun disabledActionCancelsOnlyThePointerItAlreadyOwns() {
        var cancellations = 0
        assertFalse(
            playerSurfaceCancelDisabledAction(MotionEvent.INVALID_POINTER_ID) {
                cancellations++
            }
        )
        assertEquals(0, cancellations)

        assertTrue(
            playerSurfaceCancelDisabledAction(capturedPointerId = 7) {
                cancellations++
            }
        )
        assertEquals(1, cancellations)
    }

    @Test
    fun compactSemanticFeedbackOwnsOnlyItsVisiblePlayButton() {
        assertEquals(
            PlayerSurfaceSemanticFeedbackRoute(PlayerSurfaceSemanticFeedbackChannel.MINI_PLAY),
            playerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackTarget.Transport(
                    PlayerSurfaceTransportCommand.PLAY_PAUSE
                ),
                compact = true
            )
        )
        assertNull(
            playerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackTarget.Transport(PlayerSurfaceTransportCommand.NEXT),
                compact = true
            )
        )
        assertNull(
            playerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackTarget.SongInfo,
                compact = true
            )
        )
    }

    @Test
    fun fullSemanticFeedbackMapsToTheExistingNativeBloomOwners() {
        val transportCommands = listOf(
            PlayerSurfaceTransportCommand.SHUFFLE_TOGGLE,
            PlayerSurfaceTransportCommand.PREVIOUS,
            PlayerSurfaceTransportCommand.PLAY_PAUSE,
            PlayerSurfaceTransportCommand.NEXT,
            PlayerSurfaceTransportCommand.REPEAT_CYCLE
        )
        transportCommands.forEachIndexed { index, command ->
            assertEquals(
                PlayerSurfaceSemanticFeedbackRoute(
                    PlayerSurfaceSemanticFeedbackChannel.TRANSPORT,
                    index
                ),
                playerSurfaceSemanticFeedbackRoute(
                    PlayerSurfaceSemanticFeedbackTarget.Transport(command),
                    compact = false
                )
            )
        }

        listOf(
            PlayerSurfaceTool.LOOP,
            PlayerSurfaceTool.TUNE,
            PlayerSurfaceTool.LYRICS,
            PlayerSurfaceTool.METRONOME
        ).forEachIndexed { index, tool ->
            assertEquals(
                PlayerSurfaceSemanticFeedbackRoute(
                    PlayerSurfaceSemanticFeedbackChannel.TOOL,
                    index
                ),
                playerSurfaceSemanticFeedbackRoute(
                    PlayerSurfaceSemanticFeedbackTarget.Tool(tool),
                    compact = false
                )
            )
        }
        assertNull(
            playerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackTarget.Tool(PlayerSurfaceTool.NONE),
                compact = false
            )
        )
    }

    @Test
    fun fullSemanticFeedbackPreservesSourceMixerAndHeaderIdentity() {
        assertEquals(
            PlayerSurfaceSemanticFeedbackChannel.COLLAPSE,
            playerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackTarget.Collapse,
                compact = false
            )?.channel
        )
        assertEquals(
            PlayerSurfaceSemanticFeedbackChannel.SONG_INFO,
            playerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackTarget.SongInfo,
                compact = false
            )?.channel
        )
        assertEquals(
            PlayerSurfaceSemanticFeedbackChannel.SOURCE_ORIGINAL,
            playerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackTarget.Source(PlayerSurfaceSourceMode.ORIGINAL),
                compact = false
            )?.channel
        )
        assertEquals(
            PlayerSurfaceSemanticFeedbackChannel.SOURCE_STEMS,
            playerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackTarget.Source(PlayerSurfaceSourceMode.STEMS),
                compact = false
            )?.channel
        )
        assertEquals(
            PlayerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackChannel.MIXER_MUTE,
                index = 4
            ),
            playerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackTarget.MixerToggle(laneIndex = 4, mute = true),
                compact = false
            )
        )
        assertEquals(
            PlayerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackChannel.MIXER_SOLO,
                index = 2
            ),
            playerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackTarget.MixerToggle(laneIndex = 2, mute = false),
                compact = false
            )
        )
    }

    @Test
    fun nativePlayerActionsMapDirectlyToDistinctCanonicalCatalogRoles() {
        // Key intentionally reuses the Piano glyph; the remaining semantic roles stay distinct.
        assertEquals(PlayerSurfaceIcon.STEM_PIANO.catalogRole(), PlayerSurfaceIcon.TUNE.catalogRole())
        val roles = PlayerSurfaceIcon.values().filterNot { it == PlayerSurfaceIcon.TUNE }
            .map(PlayerSurfaceIcon::catalogRole)
        assertEquals(roles.size, roles.distinct().size)
        assertEquals(StemwiseIconRole.PLAYER_SHUFFLE, PlayerSurfaceIcon.SHUFFLE.catalogRole())
        assertEquals(StemwiseIconRole.PLAYER_REPEAT_ALL, PlayerSurfaceIcon.REPEAT.catalogRole())
        assertEquals(StemwiseIconRole.PLAYER_TOOL_LOOP_AB, PlayerSurfaceIcon.LOOP_AB.catalogRole())
        assertNotEquals(PlayerSurfaceIcon.LOOP_AB.catalogRole(), PlayerSurfaceIcon.REPEAT.catalogRole())
    }

    private fun profile(
        timelineRevision: Long = 1L,
        positionMs: Long = 100L,
        mixerRevision: Long = 1L,
        packageRevision: Long = 1L,
        volumes: FloatArray = floatArrayOf(.2f, .8f),
        flags: IntArray = intArrayOf(0, 0)
    ): PlayerSurfaceProfilePacket {
        return PlayerSurfaceProfilePacket(
            profileRevision = maxOf(timelineRevision, mixerRevision),
            owner = PlayerSurfaceOwnerKey(1L, "song", "media", "audio", "stems"),
            queue = PlayerSurfaceQueuePacket(1L, longArrayOf(1L), 0),
            timeline = PlayerSurfaceTimelinePacket(
                revision = timelineRevision,
                durationMs = 1_000L,
                positionMs = positionMs
            ),
            source = PlayerSurfaceSourcePacket(
                revision = 1L,
                presentedMode = PlayerSurfaceSourceMode.STEMS,
                audibleMode = PlayerSurfaceSourceMode.STEMS,
                phase = PlayerSurfaceSourcePhase.SETTLED,
                stemsAvailable = true
            ),
            mixer = PlayerSurfaceMixerPacket(
                revision = mixerRevision,
                packageRevision = packageRevision,
                laneKeys = intArrayOf(1, 2),
                volumes = volumes,
                laneFlags = flags
            ),
            transport = PlayerSurfaceTransportPacket(
                revision = 1L,
                playWhenReady = true,
                repeatMode = PlayerSurfaceRepeatMode.OFF,
                shuffleEnabled = false,
                canGoPrevious = true,
                canGoNext = true
            ),
            layout = PlayerSurfaceLayoutPacket(
                revision = 1L,
                widthPx = 100,
                heightPx = 100,
                density = 1f,
                direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            )
        )
    }

    private fun visuals(label: String = "Vocals"): FullPlayerSnapshot {
        val snapshot = FullPlayerTestFixtures.snapshot(sourceMode = PlayerSurfaceSourceMode.STEMS)
        val lanes = snapshot.sourceMixer.lanes().mapIndexed { index, lane ->
            if (index == 0) lane.copy(label = label) else lane
        }
        return snapshot.copy(sourceMixer = FullPlayerSourceMixerPacket(snapshot.sourceMixer.revision,
            snapshot.sourceMixer.source, snapshot.sourceMixer.mixer, lanes))
    }
}
