package app.stemwise.ui.player.surface

import app.stemwise.ui.stemSliderFractionFromPointer
import app.stemwise.ui.stemSliderEffectiveFraction
import app.stemwise.ui.stemSliderStoredVolume
import app.stemwise.ui.design.StemwiseSliderFeedback

internal interface PlayerMixerGestureHost {
    val profile: PlayerSurfaceProfilePacket?
    val sink: FullPlayerActionSink?
    val liveSourceMode: PlayerSurfaceSourceMode
    val laneSliderBounds: FloatArray
    val thumbRadiusPx: Float
    fun clearPendingArtworkTap()
    fun scheduleFrame()
    fun post(action: Runnable): Boolean
    fun hold(lane: Int, down: Boolean)
    fun tick()
    fun disallowParentInterception()
    fun releaseParentInterceptionIfIdle()
    fun invalidateMixerRegion()
    fun invalidateMixerRegionFromInput()
}

internal class PlayerMixerGestureOwner(private val host: PlayerMixerGestureHost) {
    private val feedback = Array(PLAYER_SURFACE_MAX_MIXER_LANES) { StemwiseSliderFeedback() }

    /** Live mixer state; committed values come from profile publications. */
    val liveMixerVolumes = FloatArray(PLAYER_SURFACE_MAX_MIXER_LANES)
    val committedMixerVolumes = FloatArray(PLAYER_SURFACE_MAX_MIXER_LANES)
    val committedMixerMuted = BooleanArray(PLAYER_SURFACE_MAX_MIXER_LANES)
    val mixerMuted = BooleanArray(PLAYER_SURFACE_MAX_MIXER_LANES)
    val mixerSolo = BooleanArray(PLAYER_SURFACE_MAX_MIXER_LANES)
    val mixerPointerIds = IntArray(PLAYER_SURFACE_MAX_MIXER_LANES) {
        -1
    }
    /** Exact gesture authority captured once at ACTION_DOWN; MOVE never rebuilds it. */
    val mixerGestureOwners =
        arrayOfNulls<PlayerSurfaceOwnerKey>(PLAYER_SURFACE_MAX_MIXER_LANES)
    val mixerGestureSinks =
        arrayOfNulls<FullPlayerActionSink>(PLAYER_SURFACE_MAX_MIXER_LANES)
    val mixerGestureHandles = arrayOfNulls<PlayerSurfaceVolumeGesture>(PLAYER_SURFACE_MAX_MIXER_LANES)
    val mixerDrainGestureHandles = arrayOfNulls<PlayerSurfaceVolumeGesture>(PLAYER_SURFACE_MAX_MIXER_LANES)
    val mixerGesturePackageRevisions = LongArray(PLAYER_SURFACE_MAX_MIXER_LANES) {
        Long.MIN_VALUE
    }
    val mixerGestureSourceModes =
        arrayOfNulls<PlayerSurfaceSourceMode>(PLAYER_SURFACE_MAX_MIXER_LANES)
    val mixerGestureLaneKeys = IntArray(PLAYER_SURFACE_MAX_MIXER_LANES)

    /**
     * Pointer-rate intent stays in fixed-capacity primitive lanes. The first buffer is written by
     * input; Choreographer snapshots it once per VSYNC into the post-frame drain buffer. This
     * keeps owner/map construction and audio admission out of ACTION_MOVE and out of traversal.
     */
    val mixerPendingPreviewVolumes = FloatArray(PLAYER_SURFACE_MAX_MIXER_LANES)
    var mixerPendingPreviewMask = 0
    val mixerDrainPreviewVolumes = FloatArray(PLAYER_SURFACE_MAX_MIXER_LANES)
    var mixerDrainPreviewMask = 0
    var mixerPreviewDrainPosted = false
    val mixerPreviewDrainRunnable = Runnable(::drainMixerPreviewsAfterFrame)

    /** Snapshots pointer-rate mixer intent once for this VSYNC before downstream admission. */
    fun stageMixerPreviewsForPostFrameDrain() {
        val pendingMask = mixerPendingPreviewMask
        if (pendingMask == 0) return
        var lane = 0
        while (lane < PLAYER_SURFACE_MAX_MIXER_LANES) {
            val bit = 1 shl lane
            if (pendingMask and bit != 0) {
                mixerDrainPreviewVolumes[lane] = mixerPendingPreviewVolumes[lane]
                mixerDrainGestureHandles[lane] = mixerGestureHandles[lane]
                mixerDrainPreviewMask = mixerDrainPreviewMask or bit
            }
            lane++
        }
        mixerPendingPreviewMask = mixerPendingPreviewMask and pendingMask.inv()
        if (!mixerPreviewDrainPosted) {
            mixerPreviewDrainPosted = true
            if (!host.post(mixerPreviewDrainRunnable)) {
                mixerPreviewDrainPosted = false
                mixerDrainPreviewMask = 0
            }
        }
    }

    /** Drains each staged lane once after traversal; terminal input supersedes both buffers. */
    fun drainMixerPreviewsAfterFrame() {
        mixerPreviewDrainPosted = false
        var remainingMask = mixerDrainPreviewMask
        mixerDrainPreviewMask = 0
        while (remainingMask != 0) {
            var lane = 0
            var acceptedMask = 0
            var batchGeneration = Long.MIN_VALUE
            var batchSink: FullPlayerActionSink? = null
            while (lane < PLAYER_SURFACE_MAX_MIXER_LANES) {
                val bit = 1 shl lane
                if (remainingMask and bit != 0) {
                    if (mixerPointerIds[lane] == -1) {
                        remainingMask = remainingMask and bit.inv()
                    } else if (!mixerGestureIsCurrent(lane)) {
                        remainingMask = remainingMask and bit.inv()
                        cancelStaleMixerGesture(lane)
                    } else {
                        val owner = mixerGestureOwners[lane]
                        val capturedSink = mixerGestureSinks[lane]
                        if (owner == null || capturedSink == null) {
                            remainingMask = remainingMask and bit.inv()
                        } else {
                            if (batchSink == null) {
                                batchGeneration = owner.generation
                                batchSink = capturedSink
                            }
                            if (batchGeneration == owner.generation && batchSink === capturedSink) {
                                remainingMask = remainingMask and bit.inv()
                                acceptedMask = acceptedMask or bit
                            }
                        }
                    }
                }
                lane++
            }
            if (acceptedMask != 0) {
                // Rebinding may give new pointers a different host.sink while an older lease lives.
                // Keep each captured sink's lanes in its own allocation-free frame batch.
                if (batchSink?.onPreviewVolumeBatch(batchGeneration, acceptedMask,
                        mixerDrainGestureHandles, mixerDrainPreviewVolumes) != true) {
                    for (rejectedLane in 0 until PLAYER_SURFACE_MAX_MIXER_LANES)
                        if (acceptedMask and (1 shl rejectedLane) != 0) cancelStaleMixerGesture(rejectedLane)
                }
            }
        }
        // A MOVE arriving after this frame was staged owns the next frame, not this handler turn.
        if (mixerPendingPreviewMask != 0) host.scheduleFrame()
    }

    fun beginMixerPointer(
        generation: Long,
        pointerId: Int,
        lane: Int,
        x: Float
    ): Boolean {
        host.clearPendingArtworkTap()
        if (lane !in mixerPointerIds.indices || mixerPointerIds[lane] != -1) {
            return false
        }
        val current = host.profile ?: return false
        if (
            current.owner.generation != generation ||
            current.source.presentedMode != PlayerSurfaceSourceMode.STEMS ||
            host.liveSourceMode != PlayerSurfaceSourceMode.STEMS ||
            lane !in 0 until current.mixer.laneCount
        ) return false
        val capturedSink = host.sink ?: return false
        val gesture = capturedSink.onBeginVolume(generation, lane) ?: return false
        mixerGestureHandles[lane] = gesture
        // Capture the complete command/topology authority once. Ordinary mixer revisions are
        // allowed during a drag; owner, package, lane identity, or presented-source changes are not.
        mixerGestureOwners[lane] = current.owner
        mixerGestureSinks[lane] = capturedSink
        mixerGesturePackageRevisions[lane] = current.mixer.packageRevision
        mixerGestureSourceModes[lane] = current.source.presentedMode
        mixerGestureLaneKeys[lane] = current.mixer.laneKeyAt(lane)
        mixerPointerIds[lane] = pointerId
        mixerPendingPreviewVolumes[lane] = liveMixerVolumes[lane]
        feedback[lane].finish(accepted = false, deferred = true)
        feedback[lane].change(liveMixerVolumes[lane], liveMixerVolumes[lane], true, null, true)
        host.tick()
        host.hold(lane, true)
        host.scheduleFrame()
        host.disallowParentInterception()
        updateMixerPointer(lane, x)
        return mixerPointerIds[lane] == pointerId && mixerGestureIsCurrent(lane)
    }

    fun updateMixerPointer(lane: Int, x: Float) {
        if (lane !in mixerPointerIds.indices ||
            mixerPointerIds[lane] == -1
        ) return
        if (!mixerGestureIsCurrent(lane)) {
            cancelStaleMixerGesture(lane)
            return
        }
        val current = host.profile ?: return
        val base = lane * 4
        val left = host.laneSliderBounds[base]
        val right = host.laneSliderBounds[base + 2]
        val value = stemSliderFractionFromPointer(
            pointerX = x - left,
            widthPx = right - left,
            thumbRadiusPx = host.thumbRadiusPx,
            rightToLeft = current.layout.direction == PlayerSurfaceLayoutDirection.RIGHT_TO_LEFT
        )
        if (mixerPendingPreviewVolumes[lane].toRawBits() == value.toRawBits()) return
        // Pixels are the first responsibility of ACTION_MOVE. Playback admission is coalesced in
        // the fixed-capacity lane buffer and cannot delay this invalidation.
        feedback[lane].change(liveMixerVolumes[lane], value, true, null, true)
        liveMixerVolumes[lane] = value
        mixerMuted[lane] = value <= 0f
        // ACTION_MOVE already runs on the UI thread. Marking this retained child dirty now lets
        // a late input join an imminent traversal; posting another animation callback here can
        // miss that traversal and add a full display period. Drawing remains VSYNC-bound.
        host.invalidateMixerRegionFromInput()
        mixerPendingPreviewVolumes[lane] = value
        mixerPendingPreviewMask = mixerPendingPreviewMask or (1 shl lane)
        host.scheduleFrame()
    }

    fun finishMixerPointer(lane: Int, x: Float, cancelled: Boolean) {
        if (lane !in mixerPointerIds.indices) return
        if (!cancelled && mixerGestureIsCurrent(lane)) updateMixerPointer(lane, x)
        val owner = mixerGestureOwners[lane]
        val capturedSink = mixerGestureSinks[lane]
        val current = host.profile
        val gestureCurrent = mixerGestureIsCurrent(lane)
        // Terminal input supersedes either pending buffer; ACTION_UP is exact and never conflated.
        clearPendingMixerPreview(lane)
        if (owner != null) {
            val accepted = if (!cancelled && gestureCurrent) {
                mixerGestureHandles[lane]?.let { capturedSink?.onFinishVolume(it, liveMixerVolumes[lane]) } == true
            } else {
                false
            }
            if (feedback[lane].finish(accepted, deferred = true)) host.tick()
            if (accepted) {
                acceptMixerVolume(lane)
            } else {
                restoreMixerVolume(lane)
                mixerGestureHandles[lane]?.let { capturedSink?.onCancelVolume(it) }
            }
        }
        clearMixerPointer(lane)
        host.scheduleFrame()
        host.invalidateMixerRegion()
    }

    fun clearMixerPointer(lane: Int) {
        feedback[lane].finish(accepted = false, deferred = true)
        host.hold(lane, false)
        clearPendingMixerPreview(lane)
        mixerPointerIds[lane] = -1
        mixerGestureOwners[lane] = null
        mixerGestureSinks[lane] = null
        mixerGestureHandles[lane] = null
        mixerDrainGestureHandles[lane] = null
        mixerGesturePackageRevisions[lane] = Long.MIN_VALUE
        mixerGestureSourceModes[lane] = null
        mixerGestureLaneKeys[lane] = 0
    }

    fun clearPendingMixerPreview(lane: Int) {
        if (lane !in mixerPointerIds.indices) return
        val bit = 1 shl lane
        mixerPendingPreviewMask = mixerPendingPreviewMask and bit.inv()
        mixerDrainPreviewMask = mixerDrainPreviewMask and bit.inv()
    }

    /** Accepted controls supersede queued fader input while keeping the pointer's authority. */
    fun acceptMixerFlags(lane: Int, volume: Float, muted: Boolean, solo: Boolean) {
        clearPendingMixerPreview(lane)
        committedMixerVolumes[lane] = volume
        committedMixerMuted[lane] = muted || volume <= 0f
        mixerMuted[lane] = committedMixerMuted[lane]
        mixerSolo[lane] = solo
        liveMixerVolumes[lane] = stemSliderEffectiveFraction(committedMixerVolumes[lane], mixerMuted[lane])
        host.invalidateMixerRegion()
    }

    private fun acceptMixerVolume(lane: Int) {
        committedMixerVolumes[lane] = stemSliderStoredVolume(liveMixerVolumes[lane], committedMixerVolumes[lane])
        committedMixerMuted[lane] = liveMixerVolumes[lane] <= 0f
        mixerMuted[lane] = committedMixerMuted[lane]
    }

    private fun restoreMixerVolume(lane: Int) {
        mixerMuted[lane] = committedMixerMuted[lane]
        liveMixerVolumes[lane] = stemSliderEffectiveFraction(committedMixerVolumes[lane], committedMixerMuted[lane])
    }

    fun mixerGestureIsCurrent(lane: Int): Boolean {
        if (lane !in mixerPointerIds.indices) return false
        val current = host.profile ?: return false
        val owner = mixerGestureOwners[lane] ?: return false
        val sourceMode = mixerGestureSourceModes[lane] ?: return false
        return current.owner == owner &&
            current.mixer.packageRevision == mixerGesturePackageRevisions[lane] &&
            current.source.presentedMode == sourceMode &&
            host.liveSourceMode == sourceMode &&
            lane in 0 until current.mixer.laneCount &&
            current.mixer.laneKeyAt(lane) == mixerGestureLaneKeys[lane]
    }

    fun cancelStaleMixerGesture(lane: Int) {
        if (lane !in mixerPointerIds.indices) return
        val owner = mixerGestureOwners[lane]
        val capturedSink = mixerGestureSinks[lane]
        restoreMixerVolume(lane)
        val gesture = mixerGestureHandles[lane]
        clearMixerPointer(lane)
        if (owner != null && gesture != null) capturedSink?.onCancelVolume(gesture)
        host.invalidateMixerRegion()
        host.releaseParentInterceptionIfIdle()
    }

    fun cancelMixerGestures(seal: Boolean) {
        val current = host.profile
        var lane = 0
        while (lane < mixerPointerIds.size) {
            if (mixerPointerIds[lane] != -1) {
                val owner = mixerGestureOwners[lane]
                val capturedSink = mixerGestureSinks[lane]
                val accepted = current != null && owner != null && seal &&
                    mixerGestureIsCurrent(lane) &&
                    mixerGestureHandles[lane]?.let { capturedSink?.onFinishVolume(it, liveMixerVolumes[lane]) } == true
                if (feedback[lane].finish(accepted, deferred = true)) host.tick()
                if (accepted) {
                    acceptMixerVolume(lane)
                } else {
                    restoreMixerVolume(lane)
                    if (owner != null) mixerGestureHandles[lane]?.let { capturedSink?.onCancelVolume(it) }
                }
                clearMixerPointer(lane)
            }
            lane++
        }
        host.invalidateMixerRegion()
    }

}
