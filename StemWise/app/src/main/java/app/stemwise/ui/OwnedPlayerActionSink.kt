package app.stemwise.ui

import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import app.stemwise.analysis.WaveformWindow
import app.stemwise.model.Song
import app.stemwise.playback.AudioPlayback
import app.stemwise.playback.PlaybackControlLease
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.ui.player.surface.*
import app.stemwise.ui.player.nativeplayer.*
import kotlinx.coroutines.CoroutineScope

/** One captured presentation generation and native control lease for physical and semantic input. */
internal class OwnedPlayerActionSink(
    private val expectedGeneration: Long,
    private val callbackLease: PlaybackControlLease?,
    private val callbackSlots: Array<StemUiSlot>,
    private val callbackAssetIds: Array<String?>,
    private val callbackCanonicalLaneIndices: IntArray,
    private val audioPlayback: AudioPlayback,
    private val activeSong: Song,
    private val timelineDuration: Long,
    private val queueProjection: PlayerQueueProjection,
    private val positionState: MutableLongState,
    private val isScrubbingState: MutableState<Boolean>,
    private val nativeFineOwner: NativeFineWaveformOwner,
    private val nativeFineWindowState: MutableState<PlayerFineWaveformWindow?>,
    private val nativeMixerGestureOwner: NativeMixerGestureOwner,
    private val nativeLoopBoundaryOwner: NativeLoopBoundaryGestureOwner,
    private val scope: CoroutineScope,
    private val readFineWindowKey: () -> PlayerFineWaveformKey,
    private val readFineWindow: suspend (Long, PlayerFineWaveformKey) -> WaveformWindow?,
    private val expandPlayer: () -> Unit,
    private val collapsePlayer: () -> Unit,
    private val showSongInfo: () -> Unit,
    private val selectPagerSong: (String) -> Boolean,
    private val changeTrack: (TrackChangeDirection) -> Int,
    private val skipArtwork: (Long) -> Boolean,
    private val changeShuffle: (Boolean) -> Unit,
    private val selectSource: (PlaybackSourceMode) -> Boolean,
    private val selectTool: (PlayerSurfaceTool) -> Boolean,
    private val onControlFailure: (PlayerControlFailure?) -> Unit,
    private val playerSurfaceBridge: PlayerSurfaceHostBridge? = null
) : FullPlayerActionSink {
    private val callbackBatchGains = FloatArray(app.stemwise.playback.engine.ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { 1f }
    private val callbackBatchUiVolumes = FloatArray(PLAYER_SURFACE_MAX_MIXER_LANES)

    private val pausedMixLease = callbackLease?.pausedPreparedStemMix

    private fun commitMix(assetId: String, stem: app.stemwise.model.Stem): Boolean =
        if (pausedMixLease != null) audioPlayback.commitPausedPreparedStemMix(
            pausedMixLease, assetId, stem.volume, stem.muted, stem.solo)
        else callbackLease != null && audioPlayback.commitStemMix(
            assetId, stem.volume, stem.muted, stem.solo, callbackLease)

    private var scrubSessionId: Long? = null

    private fun publishAcceptedMixer(lane: Int, stem: app.stemwise.model.Stem) {
        playerSurfaceBridge?.acceptMixerFlags(expectedGeneration, lane, stem.volume, stem.muted, stem.solo)
    }

    internal fun retireScrub(): Boolean {
        val receipt = scrubSessionId
        scrubSessionId = null
        val current = audioPlayback.activeScrubSessionId
        if (current != null && current != receipt) return false
        receipt?.let(audioPlayback::cancelScrub)
        isScrubbingState.value = false
        return true
    }

    private fun ownsScrub(): Boolean = scrubSessionId?.let { it == audioPlayback.activeScrubSessionId } == true

    private fun owns(generation: Long): Boolean = generation == expectedGeneration

    override fun onOpenPlayer(generation: Long): Boolean {
        if (!owns(generation)) return false
        expandPlayer()
        return true
    }

    override fun onCollapsePlayer(generation: Long): Boolean {
        if (!owns(generation)) return false
        collapsePlayer()
        return true
    }

    override fun onShowSongInfo(generation: Long): Boolean {
        if (!owns(generation)) return false
        showSongInfo()
        return true
    }

    override fun onPagerPreview(
        generation: Long,
        queueIndex: Int,
        offsetFraction: Float
    ): Boolean {
        if (!owns(generation) || !offsetFraction.isFinite() ||
            offsetFraction == 0f
        ) return false
        val target = queueProjection.orderedSongIds.getOrNull(queueIndex)
            ?.let(queueProjection.orderedSongById::get)
        return audioPlayback.previewSelection(target)
    }

    override fun onPagerCommit(generation: Long, queueIndex: Int): Boolean {
        if (!owns(generation)) return false
        val selectedId = queueProjection.orderedSongIds.getOrNull(queueIndex) ?: return false
        return selectPagerSong(selectedId)
    }

    override fun onPagerPreview(
        generation: Long,
        queueIndex: Int,
        offsetFraction: Float,
        songId: String
    ): Boolean {
        if (!owns(generation) || !offsetFraction.isFinite() || offsetFraction == 0f) return false
        return audioPlayback.previewSelection(queueProjection.orderedSongById[songId])
    }

    override fun onPagerCommit(generation: Long, queueIndex: Int, songId: String): Boolean {
        if (!owns(generation)) return false
        return selectPagerSong(songId)
    }

    override fun onPagerCancel(generation: Long) {
        if (owns(generation)) audioPlayback.previewSelection(null)
    }

    override fun onNavigate(
        generation: Long,
        direction: PlayerSurfaceTransportCommand
    ): Int {
        if (!owns(generation)) return -1
        return when (direction) {
            PlayerSurfaceTransportCommand.PREVIOUS ->
                changeTrack(TrackChangeDirection.PREVIOUS)
            PlayerSurfaceTransportCommand.NEXT ->
                changeTrack(TrackChangeDirection.NEXT)
            else -> -1
        }
    }

    override fun onArtworkSkip(generation: Long, deltaMs: Long): Boolean {
        if (!owns(generation) || callbackLease == null) return false
        return skipArtwork(deltaMs)
    }

    override fun onFineModeChanged(
        generation: Long,
        active: Boolean,
        centerPositionMs: Long
    ) {
        if (!owns(generation)) return
        if (!active) {
            nativeFineOwner.cancel()
            nativeFineWindowState.value = null
            return
        }
        val bucket = centerPositionMs.coerceAtLeast(0L) / 2_500L
        nativeFineOwner.request(scope, bucket, key = readFineWindowKey, read = { key ->
            readFineWindow(centerPositionMs, key)
        }) { window -> nativeFineWindowState.value = window }
    }



    override fun onBeginVolume(generation: Long, laneIndex: Int): PlayerSurfaceVolumeGesture? {
        if (!owns(generation) || callbackLease == null || nativeMixerGestureOwner.handle(laneIndex) != null) return null
        callbackSlots.getOrNull(laneIndex) ?: return null
        if (callbackAssetIds.getOrNull(laneIndex) == null) return null
        val canonicalLane = callbackCanonicalLaneIndices.getOrNull(laneIndex) ?: return null
        if (canonicalLane !in 0 until PLAYER_SURFACE_MAX_MIXER_LANES) return null
        val previewLease = if (pausedMixLease != null) {
            if (callbackAssetIds[laneIndex] !in pausedMixLease.laneIds ||
                !audioPlayback.pausedPreparedStemMixLeaseIsCurrent(pausedMixLease)) return null
            null
        } else audioPlayback.beginStemMixPreviewBatchLane(canonicalLane, callbackLease) ?: return null
        val gesture = PlayerSurfaceVolumeGesture(generation, laneIndex)
        if (!nativeMixerGestureOwner.begin(gesture, canonicalLane, previewLease)) {
            previewLease?.let(audioPlayback::endStemMixPreviewBatchLane)
            return null
        }
        return gesture
    }

    override fun onPreviewVolumeBatch(
        generation: Long,
        changedMask: Int,
        gestures: Array<PlayerSurfaceVolumeGesture?>,
        volumes: FloatArray
    ): Boolean {
        if (!owns(generation)) return false
        if (pausedMixLease != null && !audioPlayback.pausedPreparedStemMixLeaseIsCurrent(pausedMixLease)) return false
        var batchLease: app.stemwise.playback.engine.EngineStemMixPreviewLease? = null
        var canonicalChangedMask = 0
        var lane = 0
        while (lane < callbackSlots.size && lane < PLAYER_SURFACE_MAX_MIXER_LANES) {
            val bit = 1 shl lane
            if (changedMask and bit != 0) {
                val gesture = gestures.getOrNull(lane) ?: return false
                if (gesture.laneIndex != lane || gesture.generation != generation || !nativeMixerGestureOwner.owns(gesture)) return false
                val previewLease = nativeMixerGestureOwner.previewLease(gesture)
                if (pausedMixLease == null) {
                    if (previewLease == null) return false
                    if (batchLease == null) batchLease = previewLease
                    else if (batchLease.authority !== previewLease.authority) return false
                } else if (previewLease != null) return false
                val canonicalLane = nativeMixerGestureOwner.canonicalLaneIndex(gesture)
                if (canonicalLane !in 0 until PLAYER_SURFACE_MAX_MIXER_LANES || !volumes[lane].isFinite()) return false
                val canonicalBit = 1 shl canonicalLane
                val normalized = stemSliderActiveFraction(volumes[lane])
                callbackBatchGains[canonicalLane] = normalized
                callbackBatchUiVolumes[lane] = normalized
                canonicalChangedMask = canonicalChangedMask or canonicalBit
            }
            lane++
        }
        if (canonicalChangedMask == 0) return false
        if (pausedMixLease == null && !audioPlayback.offerStemMixPreviewBatchFrameBounded(
                checkNotNull(batchLease), canonicalChangedMask, callbackBatchGains)) return false
        lane = 0
        while (lane < callbackSlots.size && lane < PLAYER_SURFACE_MAX_MIXER_LANES) {
            if (changedMask and (1 shl lane) != 0) callbackSlots[lane].previewSliderLogical(callbackBatchUiVolumes[lane])
            lane++
        }
        return true
    }

    override fun onScrubBegin(generation: Long): Boolean {
        if (!owns(generation) || callbackLease == null) return false
        val accepted = audioPlayback.beginScrub(callbackLease)
        if (accepted) {
            scrubSessionId = audioPlayback.activeScrubSessionId
            isScrubbingState.value = true
        }
        return accepted
    }

    override fun onScrubPreview(generation: Long, positionMs: Long): Boolean {
        if (!owns(generation) || !ownsScrub()) return false
        // The native surface already owns the live thumb, clocks, loop detent, and Fine
        // waveform. Publishing every MOVE through Snapshot state needlessly recomposed
        // the high-level player once per injected sample and coincided with the only
        // failing phase of the 120 Hz owner-device gate. Keep previews inside the
        // native/audio path; ACTION_UP remains the one committed Compose publication.
        audioPlayback.previewScrub(positionMs.coerceIn(0L, timelineDuration))
        return true
    }

    override fun onScrubCommit(generation: Long, positionMs: Long): Boolean {
        if (!owns(generation) || !ownsScrub()) return false
        positionState.longValue = positionMs.coerceIn(0L, timelineDuration)
        scrubSessionId = null
        isScrubbingState.value = false
        audioPlayback.commitScrub(positionState.longValue)
        return true
    }

    override fun onScrubCancel(generation: Long) {
        if (!owns(generation)) return
        if (!retireScrub()) return
        positionState.longValue = audioPlayback.currentStateSnapshot().positionMs
            .coerceIn(0L, timelineDuration)
    }

    override fun onLoopBoundaryBegin(
        generation: Long,
        boundary: FullPlayerLoopBoundary,
        originalStartMs: Long,
        originalEndMs: Long
    ): Boolean {
        if (!owns(generation) || callbackLease == null) return false
        val accepted = nativeLoopBoundaryOwner.begin(
            boundary,
            originalStartMs,
            originalEndMs,
            timelineDuration
        )
        return accepted
    }

    override fun onLoopBoundaryMove(
        generation: Long,
        boundary: FullPlayerLoopBoundary,
        startMs: Long,
        endMs: Long,
        transportPositionMs: Long,
        inputEventAtMonotonicMicros: Long,
        capturedBMs: Long
    ): Boolean {
        val accepted = owns(generation) &&
            callbackLease != null &&
            nativeLoopBoundaryOwner.owns(boundary) &&
            loopBoundaryGestureRangeIsValid(
                boundary,
                startMs,
                endMs,
                timelineDuration
            ) &&
            audioPlayback.setLoop(
                startMs,
                endMs.takeIf { it >= 0L },
                callbackLease,
                transportPositionMs,
                publishPresentation = false,
                inputEventAtMonotonicMicros = inputEventAtMonotonicMicros,
                heldBGestureId = nativeLoopBoundaryOwner.gestureId(boundary),
                capturedBMs = capturedBMs
            )
        if (accepted) nativeLoopBoundaryOwner.accept(boundary, startMs, endMs)
        return accepted
    }

    override fun onLoopBoundaryEnd(
        generation: Long,
        boundary: FullPlayerLoopBoundary,
        startMs: Long,
        endMs: Long,
        transportPositionMs: Long,
        inputEventAtMonotonicMicros: Long
    ): Boolean {
        if (!owns(generation) || callbackLease == null ||
            !nativeLoopBoundaryOwner.owns(boundary)
        ) return false
        val requested = startMs to endMs
        val previouslyAccepted = nativeLoopBoundaryOwner.accepted(boundary)
            ?: return false
        if (requested != previouslyAccepted) {
            val terminalAccepted = loopBoundaryGestureRangeIsValid(
                boundary,
                startMs,
                endMs,
                timelineDuration
            ) && audioPlayback.setLoop(
                startMs,
                endMs.takeIf { it >= 0L },
                callbackLease,
                transportPositionMs,
                publishPresentation = false,
                inputEventAtMonotonicMicros = inputEventAtMonotonicMicros,
                heldBGestureId = nativeLoopBoundaryOwner.gestureId(boundary)
            )
            if (terminalAccepted) {
                nativeLoopBoundaryOwner.accept(boundary, startMs, endMs)
            }
        }
        val accepted = nativeLoopBoundaryOwner.end(boundary) ?: return false
        return audioPlayback.publishLoopPresentation(
            accepted.first,
            accepted.second.takeIf { it >= 0L },
            callbackLease
        )
    }

    override fun onPreviewVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float): Boolean {
        if (!owns(gesture.generation) || !nativeMixerGestureOwner.owns(gesture) || callbackLease == null || !volume.isFinite()) return false
        val lane = gesture.laneIndex
        val slot = callbackSlots.getOrNull(lane) ?: return false
        if (pausedMixLease != null) {
            if (!audioPlayback.pausedPreparedStemMixLeaseIsCurrent(pausedMixLease)) return false
            slot.previewSliderLogical(stemSliderActiveFraction(volume))
            return true
        }
        val canonicalLane = nativeMixerGestureOwner.canonicalLaneIndex(gesture)
        val previewLease = nativeMixerGestureOwner.previewLease(gesture) ?: return false
        val bit = 1 shl canonicalLane
        val normalized = stemSliderActiveFraction(volume)
        callbackBatchGains[canonicalLane] = normalized
        val accepted = audioPlayback.offerStemMixPreviewBatchFrameBounded(previewLease, bit, callbackBatchGains)
        if (accepted) slot.previewSliderLogical(normalized)
        return accepted
    }

    override fun onFinishVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float): Boolean {
        if (!owns(gesture.generation) || !nativeMixerGestureOwner.owns(gesture) || callbackLease == null || !volume.isFinite()) return false
        val lane = gesture.laneIndex
        val slot = callbackSlots.getOrNull(lane) ?: return false
        val assetId = callbackAssetIds.getOrNull(lane) ?: return false
        if (pausedMixLease != null) {
            val committed = slot.sliderSnapshot(stemSliderActiveFraction(volume))
            if (!commitMix(assetId, committed)) return false
            nativeMixerGestureOwner.finish(gesture)
            slot.commitNative(committed)
            publishAcceptedMixer(lane, committed)
            onControlFailure(null)
            return true
        }
        val canonicalLane = nativeMixerGestureOwner.canonicalLaneIndex(gesture)
        val previewLease = nativeMixerGestureOwner.previewLease(gesture) ?: return false
        val committed = slot.sliderSnapshot(stemSliderActiveFraction(volume))
        val bit = 1 shl canonicalLane
        callbackBatchGains[canonicalLane] = stemSliderEffectiveFraction(committed.volume, committed.muted)
        if (!audioPlayback.offerStemMixPreviewBatchFrameBounded(previewLease, bit, callbackBatchGains)) return false
        val accepted = audioPlayback.commitStemMix(assetId, committed.volume, committed.muted, committed.solo, callbackLease)
        if (accepted) {
            audioPlayback.endStemMixPreviewBatchLane(previewLease)
            nativeMixerGestureOwner.finish(gesture)
            slot.commitNative(committed)
            publishAcceptedMixer(lane, committed)
            onControlFailure(null)
        } else onControlFailure(PlayerControlFailure.control("Stem controls are not ready yet"))
        return accepted
    }

    override fun onCancelVolume(gesture: PlayerSurfaceVolumeGesture) {
        if (!owns(gesture.generation) || !nativeMixerGestureOwner.owns(gesture)) return
        val lane = gesture.laneIndex
        val slot = callbackSlots.getOrNull(lane) ?: return
        if (pausedMixLease != null) {
            if (!nativeMixerGestureOwner.finish(gesture)) return
            slot.rollbackVolumePreview()
            if (audioPlayback.pausedPreparedStemMixLeaseIsCurrent(pausedMixLease)) publishAcceptedMixer(lane, slot.snapshot())
            return
        }
        val canonicalLane = nativeMixerGestureOwner.canonicalLaneIndex(gesture)
        val previewLease = nativeMixerGestureOwner.previewLease(gesture) ?: return
        if (!nativeMixerGestureOwner.finish(gesture)) return
        val restored = slot.snapshot().copy(volume = slot.committedVolume.floatValue, muted = slot.muted.value)
        val bit = 1 shl canonicalLane
        callbackBatchGains[canonicalLane] = stemSliderEffectiveFraction(restored.volume, restored.muted)
        val previewAccepted = audioPlayback.offerStemMixPreviewBatchFrameBounded(previewLease, bit, callbackBatchGains)
        val assetId = callbackAssetIds.getOrNull(lane)
        val accepted = previewAccepted && callbackLease != null && assetId != null && audioPlayback.commitStemMix(
            assetId, restored.volume, restored.muted, restored.solo, callbackLease)
        audioPlayback.endStemMixPreviewBatchLane(previewLease)
        if (accepted) slot.commitNative(restored) else slot.rollbackVolumePreview()
        if (accepted) publishAcceptedMixer(lane, restored)
    }

    override fun onSetMuted(generation: Long, laneIndex: Int, muted: Boolean): Boolean =
        setMixerFlags(generation, laneIndex, muted, null)

    override fun onSetSolo(generation: Long, laneIndex: Int, solo: Boolean): Boolean =
        setMixerFlags(generation, laneIndex, null, solo)

    private fun setMixerFlags(generation: Long, lane: Int, muted: Boolean?, solo: Boolean?): Boolean {
        if (!owns(generation) || callbackLease == null) return false
        val slot = callbackSlots.getOrNull(lane) ?: return false
        val assetId = callbackAssetIds.getOrNull(lane) ?: return false
        val requested = slot.snapshot().let { it.copy(
            volume = stemSliderStoredVolume(it.volume, 1f),
            muted = muted ?: (it.muted || it.volume <= 0f), solo = solo ?: it.solo) }
        val accepted = commitMix(assetId, requested)
        if (accepted) {
            slot.commitNative(requested)
            publishAcceptedMixer(lane, requested)
            nativeMixerGestureOwner.handle(lane)?.let { gesture ->
                val canonicalLane = nativeMixerGestureOwner.canonicalLaneIndex(gesture)
                val previewLease = nativeMixerGestureOwner.previewLease(gesture) ?: return@let
                val bit = 1 shl canonicalLane
                callbackBatchGains[canonicalLane] = stemSliderEffectiveFraction(requested.volume, requested.muted)
                audioPlayback.offerStemMixPreviewBatchFrameBounded(previewLease, bit, callbackBatchGains)
            }
            onControlFailure(null)
        } else onControlFailure(PlayerControlFailure.control("Stem controls are not ready yet"))
        return accepted
    }

    internal fun resetMix(generation: Long): Boolean {
        if (!owns(generation) || callbackLease == null || callbackSlots.isEmpty() ||
            callbackAssetIds.any { it == null } ||
            callbackSlots.indices.any { nativeMixerGestureOwner.handle(it) != null }) return false
        if (!audioPlayback.resetStemMix(callbackLease)) {
            onControlFailure(PlayerControlFailure.control("Could not reset this mix"))
            return false
        }
        for (lane in callbackSlots.indices) {
            val slot = callbackSlots[lane]
            val reset = slot.snapshot().copy(volume = 1f, muted = false, solo = false)
            slot.commitNative(reset)
            publishAcceptedMixer(lane, reset)
        }
        onControlFailure(null)
        return true
    }

    override fun onTransport(
        generation: Long,
        command: PlayerSurfaceTransportCommand
    ): Boolean {
        if (!owns(generation)) return false
        return when (command) {
            PlayerSurfaceTransportCommand.PREVIOUS ->
                changeTrack(TrackChangeDirection.PREVIOUS) >= 0
            PlayerSurfaceTransportCommand.NEXT ->
                changeTrack(TrackChangeDirection.NEXT) >= 0
            PlayerSurfaceTransportCommand.PLAY_PAUSE ->
                audioPlayback.toggleCurrentOrRebind(
                    selectedSong = activeSong,
                    positionMs = positionState.longValue
                )
            PlayerSurfaceTransportCommand.SHUFFLE_TOGGLE -> {
                val enabled = !audioPlayback.currentStateSnapshot().shuffleEnabled
                val accepted = audioPlayback.setShuffle(enabled)
                if (accepted) changeShuffle(enabled)
                accepted
            }
            PlayerSurfaceTransportCommand.REPEAT_CYCLE -> {
                val currentMode = audioPlayback.currentStateSnapshot().repeatMode
                audioPlayback.setRepeatMode(
                    when (currentMode) {
                        "Off" -> "All"
                        "All" -> "One"
                        else -> "Off"
                    }
                )
            }
        }
    }

    override fun onSource(
        generation: Long,
        target: PlayerSurfaceSourceMode
    ): Boolean {
        // Download/Tasks navigation does not need an engine lease; switching audio does,
        // and is validated by the shared source-selection callback below.
        if (!owns(generation)) return false
        val targetMode = if (target == PlayerSurfaceSourceMode.STEMS) {
            PlaybackSourceMode.STEMS
        } else {
            PlaybackSourceMode.ORIGINAL
        }
        return selectSource(targetMode)
    }

    override fun onTool(generation: Long, tool: PlayerSurfaceTool): Boolean =
        owns(generation) && selectTool(tool)
}

/** Accessibility adapter: every mutation goes through the exact physical action owner above. */
internal class OwnedPlayerSemanticsSink(
    private val expectedGeneration: Long,
    private val fullPlayerActionSink: FullPlayerActionSink,
    private val playerSurfaceBridge: PlayerSurfaceHostBridge,
    private val queueProjection: PlayerQueueProjection,
    private val nativeSemanticSongIdState: MutableState<String>,
    private val positionState: MutableLongState,
    private val timelineDuration: Long,
    private val stems: List<StemUiSlot>
) : PlayerSurfaceSemanticsActionSink {
    private fun owns(generation: Long): Boolean = generation == expectedGeneration

    private fun acceptedAction(
        generation: Long,
        feedback: () -> Boolean,
        action: () -> Boolean
    ): Boolean {
        if (!owns(generation)) return false
        val accepted = action()
        if (!accepted) return false
        feedback()
        return true
    }

    private fun acceptedAction(
        generation: Long,
        feedbackTarget: PlayerSurfaceSemanticFeedbackTarget,
        action: () -> Boolean
    ): Boolean = acceptedAction(
        generation = generation,
        feedback = {
            playerSurfaceBridge.pulseSemanticFeedback(generation, feedbackTarget)
        },
        action = action
    )

    override fun onOpenPlayer(generation: Long): Boolean =
        acceptedAction(
            generation = generation,
            feedback = { playerSurfaceBridge.pulseCompactOpenFeedback(generation) }
        ) { fullPlayerActionSink.onOpenPlayer(generation) }

    override fun onCollapsePlayer(generation: Long): Boolean =
        acceptedAction(
            generation,
            PlayerSurfaceSemanticFeedbackTarget.Collapse
        ) { fullPlayerActionSink.onCollapsePlayer(generation) }

    override fun onShowSongInfo(generation: Long): Boolean =
        acceptedAction(
            generation,
            PlayerSurfaceSemanticFeedbackTarget.SongInfo
        ) { fullPlayerActionSink.onShowSongInfo(generation) }

    override fun onTool(
        generation: Long,
        tool: PlayerSurfaceTool
    ): Boolean = acceptedAction(
        generation,
        PlayerSurfaceSemanticFeedbackTarget.Tool(tool)
    ) { fullPlayerActionSink.onTool(generation, tool) }

    override fun onPagerStep(generation: Long, delta: Int): Boolean {
        if (!owns(generation) || delta == 0) return false
        val currentIndex = queueProjection.orderedSongPageById[
            nativeSemanticSongIdState.value
        ] ?: return false
        val targetIndex = currentIndex + delta
        return playerSurfaceBridge.performSemanticNavigation(
            generation = generation,
            targetIndex = targetIndex,
            offsetFraction = delta.coerceIn(-1, 1).toFloat()
        ) { fullPlayerActionSink.onPagerCommit(generation, targetIndex) }
    }

    override fun onArtworkSkip(generation: Long, deltaMs: Long): Boolean =
        fullPlayerActionSink.onArtworkSkip(generation, deltaMs)

    override fun onSeekTo(generation: Long, positionMs: Long): Boolean {
        if (!fullPlayerActionSink.onScrubBegin(generation)) return false
        if (!fullPlayerActionSink.onScrubPreview(generation, positionMs)) {
            fullPlayerActionSink.onScrubCancel(generation)
            return false
        }
        return fullPlayerActionSink.onScrubCommit(generation, positionMs)
    }

    override fun onSeekBy(generation: Long, deltaMs: Long): Boolean = onSeekTo(
        generation,
        (positionState.longValue + deltaMs).coerceIn(0L, timelineDuration)
    )

    override fun onSource(
        generation: Long,
        target: PlayerSurfaceSourceMode
    ): Boolean = acceptedAction(
        generation,
        PlayerSurfaceSemanticFeedbackTarget.Source(target)
    ) { fullPlayerActionSink.onSource(generation, target) }

    override fun onMixerScroll(generation: Long, deltaPx: Float): Boolean =
        owns(generation) && playerSurfaceBridge.scrollMixerBy(generation, deltaPx)

    override fun onMixerVolume(
        generation: Long,
        laneIndex: Int,
        volume: Float
    ): Boolean {
        val gesture = fullPlayerActionSink.onBeginVolume(generation, laneIndex) ?: return false
        if (!fullPlayerActionSink.onPreviewVolume(gesture, volume)) {
            fullPlayerActionSink.onCancelVolume(gesture)
            return false
        }
        val accepted = fullPlayerActionSink.onFinishVolume(gesture, volume)
        if (!accepted) fullPlayerActionSink.onCancelVolume(gesture)
        return accepted
    }

    override fun onMixerMute(generation: Long, laneIndex: Int, muted: Boolean): Boolean = acceptedAction(
        generation, PlayerSurfaceSemanticFeedbackTarget.MixerToggle(laneIndex, mute = true)
    ) { fullPlayerActionSink.onSetMuted(generation, laneIndex, muted) }

    override fun onMixerSolo(generation: Long, laneIndex: Int, solo: Boolean): Boolean = acceptedAction(
        generation, PlayerSurfaceSemanticFeedbackTarget.MixerToggle(laneIndex, mute = false)
    ) { fullPlayerActionSink.onSetSolo(generation, laneIndex, solo) }

    override fun onTransport(
        generation: Long,
        command: PlayerSurfaceTransportCommand
    ): Boolean = acceptedAction(
        generation,
        PlayerSurfaceSemanticFeedbackTarget.Transport(command)
    ) {
        val offset = playerSurfaceTransportPreviewOffset(command, positionState.longValue)
        if (offset == null) {
            fullPlayerActionSink.onTransport(generation, command)
        } else {
            playerSurfaceBridge.performSemanticNavigation(
                generation = generation,
                targetIndex = null,
                offsetFraction = offset
            ) { fullPlayerActionSink.onTransport(generation, command) }
        }
    }
}
