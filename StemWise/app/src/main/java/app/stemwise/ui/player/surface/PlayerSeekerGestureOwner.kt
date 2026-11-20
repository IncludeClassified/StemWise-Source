package app.stemwise.ui.player.surface

import app.stemwise.playback.AudioPlayback
import app.stemwise.ui.PrecisionScrubMode
import app.stemwise.ui.design.StemwiseSliderFeedback
import app.stemwise.ui.loopStartDetentStep
import app.stemwise.ui.quantizeSeekPosition
import kotlin.math.abs

private const val NO_SCRUB_OWNER_GENERATION = -1L
private const val FINE_WINDOW_BUCKET_MS = 2_500L

internal interface PlayerSeekerGestureHost {
    val profile: PlayerSurfaceProfilePacket?
    val sink: FullPlayerActionSink?
    val density: Float
    val fineEntryThreshold: Float
    val fineExitThreshold: Float
    val seekerTrackLeft: Float
    val seekerTrackRight: Float
    val touchSlop: Float
    val minimumBoundaryDurationMs: Long get() = AudioPlayback.MINIMUM_LOOP_DURATION_MS
    var scrubFineReleaseActive: Boolean
    var scrubFineReleaseBoundary: FullPlayerLoopBoundary?
    fun frameNanos(): Long
    fun uptimeMillis(): Long
    fun clearPendingArtworkTap()
    fun renderedPositionMs(current: PlayerSurfaceProfilePacket): Long
    fun clearFineReleaseVisual()
    fun animateScrubGrowthTo(target: Float, nowMs: Long, boundary: FullPlayerLoopBoundary?)
    fun scheduleFrame()
    fun animateWaveformMorphTo(target: Float)
    fun invalidateTimelineRegion()
    fun currentWaveformMorph(nowNanos: Long): Float
    fun hardResetWaveformMorph()
    fun tick()
    fun holdLoopHandle(boundary: FullPlayerLoopBoundary?)
    fun releaseLoopHandle()
    fun holdScrub(down: Boolean)
    fun disallowParentInterception()
}

internal data class PendingLoopBoundaryMove(
        val boundary: FullPlayerLoopBoundary,
        val startMs: Long,
        val endMs: Long,
        val transportPositionMs: Long,
        val inputEventAtMonotonicMicros: Long,
        val capturedBMs: Long
    )

internal class PlayerSeekerGestureOwner(private val host: PlayerSeekerGestureHost) {
    private val feedback = StemwiseSliderFeedback()
    private fun beginFeedback() {
        feedback.finish(accepted = false, deferred = true)
        feedback.change(scrubStartPositionMs.toFloat(), scrubStartPositionMs.toFloat(), true, null, true)
        host.tick()
    }
    private fun finishFeedback(accepted: Boolean) {
        if (feedback.finish(accepted, deferred = true)) host.tick()
    }
    private fun ownerIsCurrent(): Boolean = host.profile?.owner?.generation == scrubOwnerGeneration

    /** Seeker gesture state. */
    var scrubPointerId = -1
    var scrubMode = PrecisionScrubMode.READY
    var scrubDragging = false
    var scrubSessionStarted = false
    var scrubSessionSink: FullPlayerActionSink? = null
    var scrubPreviewPositionMs = 0L
    var scrubAccumulatorMs = 0.0
    var scrubStartPositionMs = 0L
    var scrubStartX = 0f
    var scrubLastX = 0f
    var scrubLastY = 0f
    var scrubTotalX = 0f
    var scrubTotalY = 0f
    var scrubVerticalWon = false
    var scrubFineEngaged = false
    var scrubFineIntentState = PlayerSurfaceFineIntentState()
    var scrubStartEventTimeMs = 0L
    var scrubLastEventTimeMs = 0L
    var scrubSpeedDpPerSecond = 0.0
    var scrubSensitivityMsPerPx = 0.0
    var scrubLoopBoundary: FullPlayerLoopBoundary? = null
    var scrubPreviewLoopStartMs = -1L
    var scrubPreviewLoopEndMs = -1L
    var scrubPendingLoopMove: PendingLoopBoundaryMove? = null
    var scrubOwnerGeneration = NO_SCRUB_OWNER_GENERATION
    var fineWindowRequestBucket = Long.MIN_VALUE
    var loopDetentState = app.stemwise.ui.LoopStartDetentState()

    fun beginScrubPointer(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMs: Long,
        acquiredLoopBoundary: FullPlayerLoopBoundary?
    ): Boolean {
        if (scrubPointerId != -1) return false
        host.clearPendingArtworkTap()
        val current = host.profile ?: return false
        val duration = current.timeline.durationMs
        // If DOWN lands on a still-visible Fine-exit handle, preserve the exact range used by
        // its retained pixels before clearing that visual. Starting from the coarse host.profile here
        // would make the newly acquired motor jump on the first frame.
        val ownsFineReleaseHandle = acquiredLoopBoundary != null && host.scrubFineReleaseActive
        val loopStart = if (ownsFineReleaseHandle) {
            scrubPreviewLoopStartMs
        } else {
            current.timeline.loopStartMs
        }
        val loopEnd = if (ownsFineReleaseHandle) {
            scrubPreviewLoopEndMs
        } else {
            current.timeline.loopEndMs
        }
        val renderedPosition = host.renderedPositionMs(current)
        host.clearFineReleaseVisual()
        // DOWN owns the endpoint classification. Recomputing after materializing a moving tool
        // transform could demote a real A/B press into an ordinary transport scrub.
        val loopBoundary = acquiredLoopBoundary
        val loopSessionStarted = loopBoundary != null &&
            host.sink?.onLoopBoundaryBegin(
                current.owner.generation,
                loopBoundary,
                loopStart,
                loopEnd
            ) == true
        // A visible endpoint that cannot acquire its dedicated owner consumes the gesture. It
        // must never fall through to the ordinary scrub path, whose begin call pauses playback.
        if (loopBoundary != null && !loopSessionStarted) return false
        scrubPointerId = pointerId
        scrubMode = PrecisionScrubMode.READY
        // An acquired A/B motor has already won the gesture on DOWN. It must publish the first
        // physical MOVE directly; applying ordinary playhead touch slop made small endpoint
        // movements look frozen and could let playback cross the visible B before native saw it.
        scrubDragging = loopSessionStarted
        scrubSessionStarted = loopSessionStarted
        scrubLoopBoundary = loopBoundary
        host.animateScrubGrowthTo(1f, eventTimeMs, loopBoundary)
        scrubPreviewLoopStartMs = loopStart
        scrubPreviewLoopEndMs = loopEnd
        scrubPendingLoopMove = null
        scrubOwnerGeneration = current.owner.generation
        scrubSessionSink = host.sink
        scrubStartPositionMs = when (loopBoundary) {
            FullPlayerLoopBoundary.START -> loopStart
            FullPlayerLoopBoundary.END -> loopEnd
            null -> renderedPosition
        }.coerceIn(0L, duration)
        scrubPreviewPositionMs = scrubStartPositionMs
        scrubAccumulatorMs = scrubStartPositionMs.toDouble()
        if (loopSessionStarted) beginFeedback()
        scrubStartX = x
        scrubLastX = x
        scrubLastY = y
        scrubTotalX = 0f
        scrubTotalY = 0f
        scrubVerticalWon = false
        scrubFineEngaged = false
        scrubFineIntentState = PlayerSurfaceFineIntentState()
        scrubStartEventTimeMs = eventTimeMs
        scrubLastEventTimeMs = eventTimeMs
        scrubSpeedDpPerSecond = 0.0
        scrubSensitivityMsPerPx = 0.0
        host.holdLoopHandle(loopBoundary)
        host.holdScrub(true)
        if (loopSessionStarted) host.disallowParentInterception()
        host.scheduleFrame()
        loopDetentState = if (
            loopBoundary == null && loopStart >= 0L && loopEnd > loopStart &&
            scrubStartPositionMs == loopStart
        ) {
            // The center rail won a shared playhead/A touch. Arm immediately so the first few
            // horizontal pixels feel the same A bump as an approach from elsewhere.
            app.stemwise.ui.LoopStartDetentState(
                activeLoopA = loopStart,
                activeLoopB = loopEnd,
                armed = true
            )
        } else {
            app.stemwise.ui.LoopStartDetentState()
        }
        return true
    }

    fun moveLoopBoundary(
        boundary: FullPlayerLoopBoundary,
        startMs: Long,
        endMs: Long,
        transportPositionMs: Long,
        inputEventAtMonotonicMicros: Long,
        capturedBMs: Long = -1L
    ): Boolean {
        val captureReferencePositionMs = scrubPendingLoopMove
            ?.takeIf { it.capturedBMs >= 0L && it.capturedBMs == capturedBMs }
            ?.transportPositionMs ?: transportPositionMs
        val pending = PendingLoopBoundaryMove(
            boundary = boundary,
            startMs = startMs,
            endMs = endMs,
            transportPositionMs = captureReferencePositionMs,
            inputEventAtMonotonicMicros = inputEventAtMonotonicMicros,
            capturedBMs = capturedBMs
        )
        val generation = host.profile?.owner?.generation
        if (
            generation == null || generation != scrubOwnerGeneration ||
            scrubPointerId == -1 || !scrubSessionStarted ||
            scrubLoopBoundary != boundary
        ) {
            scrubPendingLoopMove = null
            return false
        }
        val actions = scrubSessionSink ?: run {
            scrubPendingLoopMove = null
            return false
        }
        val accepted = actions.onLoopBoundaryMove(
            generation,
            boundary,
            startMs,
            endMs,
            captureReferencePositionMs,
            inputEventAtMonotonicMicros,
            capturedBMs
        )
        scrubPendingLoopMove = if (accepted) null else pending
        if (!accepted) host.scheduleFrame()
        return accepted
    }

    fun endLoopBoundary(
        boundary: FullPlayerLoopBoundary,
        inputEventAtMonotonicMicros: Long
    ): Boolean {
        val current = host.profile ?: return false
        val generation = scrubOwnerGeneration.takeIf { it != NO_SCRUB_OWNER_GENERATION } ?: return false
        return scrubSessionSink?.onLoopBoundaryEnd(
            generation,
            boundary,
            scrubPreviewLoopStartMs,
            scrubPreviewLoopEndMs,
            host.renderedPositionMs(current),
            inputEventAtMonotonicMicros
        ) == true
    }

    fun retryPendingLoopBoundaryMove(boundary: FullPlayerLoopBoundary) {
        val pending = scrubPendingLoopMove ?: return
        val current = host.profile ?: return
        if (pending.boundary != boundary) {
            scrubPendingLoopMove = null
            return
        }
        moveLoopBoundary(
            pending.boundary,
            pending.startMs,
            pending.endMs,
            host.renderedPositionMs(current),
            pending.inputEventAtMonotonicMicros,
            pending.capturedBMs
        )
    }

    fun startScrubSession(mode: PrecisionScrubMode): Boolean {
        val generation = host.profile?.owner?.generation?.takeIf { it == scrubOwnerGeneration } ?: return false
        if (!scrubSessionStarted) {
            val boundary = scrubLoopBoundary
            // A/B acquired its dedicated lease synchronously on DOWN. Reaching this branch with
            // an endpoint means that lease was lost; never replace it with a transport scrub.
            val accepted = boundary == null && scrubSessionSink?.onScrubBegin(generation) == true
            if (!accepted) return false
            scrubSessionStarted = true
            beginFeedback()
        }
        scrubDragging = true
        scrubMode = mode
        if (mode == PrecisionScrubMode.FINE) {
            scrubFineEngaged = true
            host.animateWaveformMorphTo(1f)
            scrubSessionSink?.onFineModeChanged(generation, true, scrubPreviewPositionMs)
            fineWindowRequestBucket = scrubPreviewPositionMs / FINE_WINDOW_BUCKET_MS
        }
        host.disallowParentInterception()
        host.scheduleFrame()
        return true
    }

    fun updateScrubSensitivity(
        current: PlayerSurfaceProfilePacket,
        movementX: Float,
        elapsedMs: Long,
        previousMsPerPx: Double
    ) {
        scrubSpeedDpPerSecond = abs(movementX).toDouble() /
            host.density.coerceAtLeast(.1f).toDouble() * 1_000.0 /
            elapsedMs.coerceAtLeast(1L).toDouble()
        scrubSensitivityMsPerPx = playerSurfaceContinuousScrubSensitivityMsPerPx(
            durationMs = current.timeline.durationMs,
            trackWidthPx = (host.seekerTrackRight - host.seekerTrackLeft).coerceAtLeast(1f),
            density = host.density,
            speedDpPerSecond = scrubSpeedDpPerSecond,
            fineFraction = if (scrubMode == PrecisionScrubMode.FINE) 1f else 0f,
            previousMsPerPx = previousMsPerPx,
            elapsedMs = elapsedMs
        )
    }

    fun directedScrubMovement(
        current: PlayerSurfaceProfilePacket,
        movementX: Float
    ): Float = movementX

    /** Shared playhead/A-B motor for admission, Fine transitions and steady drag. */
    fun applyScrubMovement(
        current: PlayerSurfaceProfilePacket,
        movementPx: Float,
        inputEventAtMonotonicMicros: Long = 0L,
        publishLoopBoundaryMove: Boolean = true
    ): Boolean {
        if (current.owner.generation != scrubOwnerGeneration) {
            cancelScrubGesture(commit = false, hardResetMorph = true)
            return false
        }
        val generation = current.owner.generation
        val boundary = scrubLoopBoundary
        if (boundary != null) {
            val proposedMs = scrubAccumulatorMs + movementPx.toDouble() * scrubSensitivityMsPerPx
            val adjusted = playerSurfaceAdjustedLoopBoundary(
                boundary = boundary,
                proposedMs = proposedMs,
                durationMs = current.timeline.durationMs,
                startMs = scrubPreviewLoopStartMs,
                endMs = scrubPreviewLoopEndMs,
                minimumLoopDurationMs = host.minimumBoundaryDurationMs
            )
            val boundaryPosition = if (boundary == FullPlayerLoopBoundary.START) {
                adjusted.startMs
            } else {
                adjusted.endMs
            }
            // Retain fractional-millisecond travel; discard excess only at a clamp.
            scrubAccumulatorMs = if (proposedMs.isFinite() && proposedMs >= 0.0 &&
                quantizeSeekPosition(proposedMs, current.timeline.durationMs) == boundaryPosition)
                proposedMs else boundaryPosition.toDouble()
            if (boundaryPosition == scrubPreviewPositionMs) return true
            val accepted = if (publishLoopBoundaryMove) {
                // Keep local motion continuous when native temporarily cannot accept a revision.
                // PlayerScreen retains the last accepted range for the terminal publication.
                moveLoopBoundary(
                    boundary,
                    adjusted.startMs,
                    adjusted.endMs,
                    host.renderedPositionMs(current),
                    inputEventAtMonotonicMicros
                )
            } else false
            feedback.change(scrubPreviewPositionMs.toFloat(), boundaryPosition.toFloat(), accepted, null, true)
            scrubPreviewLoopStartMs = adjusted.startMs
            scrubPreviewLoopEndMs = adjusted.endMs
            scrubPreviewPositionMs = boundaryPosition
            requestFineWindowIfNeeded(generation)
            return true
        }

        val detent = loopStartDetentStep(
            state = loopDetentState,
            currentAccumulatorMs = scrubAccumulatorMs,
            movementPx = movementPx,
            msPerPx = scrubSensitivityMsPerPx,
            durationMs = current.timeline.durationMs,
            loopA = current.timeline.loopStartMs.takeIf { it >= 0L },
            loopB = current.timeline.loopEndMs.takeIf { it >= 0L },
            captureRadiusPx = 7f * host.density,
            breakoutDistancePx = 8f * host.density
        )
        loopDetentState = detent.state
        scrubAccumulatorMs = detent.accumulatorMs
        if (detent.previewPositionMs == scrubPreviewPositionMs) return true
        if (scrubSessionSink?.onScrubPreview(generation, detent.previewPositionMs) != true) {
            cancelScrubGesture(commit = false)
            return false
        }
        feedback.change(scrubPreviewPositionMs.toFloat(), detent.previewPositionMs.toFloat(), true, null, true)
        scrubPreviewPositionMs = detent.previewPositionMs
        requestFineWindowIfNeeded(generation)
        return true
    }

    fun updateScrubPointer(
        x: Float,
        y: Float,
        eventTimeMs: Long,
        publishLoopBoundaryMove: Boolean = true
    ) {
        val current = host.profile ?: return cancelScrubGesture(commit = false, hardResetMorph = true)
        if (!ownerIsCurrent()) return cancelScrubGesture(commit = false, hardResetMorph = true)
        val deltaX = x - scrubLastX
        val deltaY = y - scrubLastY
        val elapsedMs = (eventTimeMs - scrubLastEventTimeMs).coerceAtLeast(1L)
        scrubTotalX += deltaX
        scrubTotalY += deltaY
        val fineIntent = playerSurfaceFineIntentStep(
            currentMode = scrubMode,
            state = scrubFineIntentState,
            deltaX = deltaX,
            deltaY = deltaY,
            fineEntryThresholdPx = host.fineEntryThreshold,
            fineExitThresholdPx = host.fineExitThreshold
        )
        scrubFineIntentState = fineIntent.state

        if (!scrubDragging) {
            if (abs(scrubTotalY) > host.touchSlop && abs(scrubTotalY) > abs(scrubTotalX)) {
                scrubVerticalWon = true
            }
            if (fineIntent.mode == PrecisionScrubMode.FINE) {
                scrubVerticalWon = false
                if (!startScrubSession(PrecisionScrubMode.FINE)) {
                    cancelScrubGesture(commit = false)
                    return
                }
                val admissionElapsedMs =
                    (eventTimeMs - scrubStartEventTimeMs).coerceAtLeast(1L)
                updateScrubSensitivity(
                    current = current,
                    movementX = scrubTotalX,
                    elapsedMs = admissionElapsedMs,
                    previousMsPerPx = 0.0
                )
                if (!applyScrubMovement(
                        current,
                        directedScrubMovement(current, scrubTotalX),
                        inputEventAtMonotonicMicros = eventTimeMs * 1_000L,
                        publishLoopBoundaryMove = publishLoopBoundaryMove
                    )
                ) return
                scrubLastX = x
                scrubLastY = y
                scrubLastEventTimeMs = eventTimeMs
                host.invalidateTimelineRegion()
                return
            }
            if (abs(scrubTotalX) > host.touchSlop && abs(scrubTotalX) > abs(scrubTotalY)) {
                val admissionElapsedMs =
                    (eventTimeMs - scrubStartEventTimeMs).coerceAtLeast(1L)
                if (!startScrubSession(PrecisionScrubMode.FAST_TRAVERSE)) {
                    cancelScrubGesture(commit = false)
                    return
                }
                scrubFineIntentState = PlayerSurfaceFineIntentState()
                updateScrubSensitivity(
                    current = current,
                    movementX = scrubTotalX,
                    elapsedMs = admissionElapsedMs,
                    previousMsPerPx = 0.0
                )
                val duration = current.timeline.durationMs
                val width = (host.seekerTrackRight - host.seekerTrackLeft).coerceAtLeast(1f)
                val thumbX = host.seekerTrackLeft +
                    scrubStartPositionMs.toDouble().div(duration.coerceAtLeast(1L).toDouble())
                        .toFloat() * width
                if (scrubLoopBoundary == null && abs(scrubStartX - thumbX) > 24f * host.density) {
                    val absolute = quantizeSeekPosition(
                        playerSurfaceSeekerProgressAtX(
                            scrubStartX,
                            host.seekerTrackLeft,
                            host.seekerTrackRight
                        ) *
                            duration.toDouble(),
                        duration
                    )
                    scrubPreviewPositionMs = absolute
                    scrubAccumulatorMs = absolute.toDouble()
                    if (scrubSessionSink?.onScrubPreview(current.owner.generation, absolute) != true) {
                        cancelScrubGesture(commit = false)
                        return
                    }
                }
                if (!applyScrubMovement(
                        current,
                        directedScrubMovement(current, scrubTotalX),
                        inputEventAtMonotonicMicros = eventTimeMs * 1_000L,
                        publishLoopBoundaryMove = publishLoopBoundaryMove
                    )
                ) return
                scrubLastX = x
                scrubLastY = y
                scrubLastEventTimeMs = eventTimeMs
                host.invalidateTimelineRegion()
                return
            }
            scrubLastX = x
            scrubLastY = y
            scrubLastEventTimeMs = eventTimeMs
            return
        }

        val previousMode = scrubMode
        val nextMode = fineIntent.mode
        val modeChanged = nextMode != previousMode
        if (modeChanged) {
            scrubMode = nextMode
            if (nextMode == PrecisionScrubMode.FINE) {
                scrubFineEngaged = true
                host.animateWaveformMorphTo(1f)
                scrubSessionSink?.onFineModeChanged(
                    current.owner.generation,
                    true,
                    scrubPreviewPositionMs
                )
                fineWindowRequestBucket = scrubPreviewPositionMs / FINE_WINDOW_BUCKET_MS
                host.tick()
            } else {
                if (scrubFineEngaged) {
                    scrubSessionSink?.onFineModeChanged(
                        current.owner.generation,
                        false,
                        scrubPreviewPositionMs
                    )
                }
                scrubFineEngaged = false
                fineWindowRequestBucket = Long.MIN_VALUE
                host.animateWaveformMorphTo(0f)
            }
            host.scheduleFrame()
        }
        // Fine owns the vertical intent, but the same physical sample must still advance the
        // playhead horizontally so the motor never freezes at the mode threshold.
        updateScrubSensitivity(
            current = current,
            movementX = deltaX,
            elapsedMs = elapsedMs,
            previousMsPerPx = if (modeChanged) 0.0 else scrubSensitivityMsPerPx
        )
        if (!applyScrubMovement(
                current,
                directedScrubMovement(current, deltaX),
                eventTimeMs * 1_000L,
                publishLoopBoundaryMove = publishLoopBoundaryMove
            )
        ) return
        scrubLastX = x
        scrubLastY = y
        scrubLastEventTimeMs = eventTimeMs
        host.invalidateTimelineRegion()
    }

    fun finishScrubPointer(
        x: Float,
        y: Float,
        eventTimeMs: Long,
        cancelled: Boolean
    ) {
        val current = host.profile ?: return cancelScrubGesture(commit = false)
        if (!ownerIsCurrent()) return cancelScrubGesture(commit = false, hardResetMorph = true)
        val generation = scrubOwnerGeneration
        if (cancelled) {
            cancelScrubGesture(commit = false)
            return
        }
        if (scrubDragging) {
            // ACTION_UP owns the terminal physical sample. Android may coalesce the last MOVE,
            // so apply that exact point before committing either the playhead or an A/B motor.
            val boundary = scrubLoopBoundary
            updateScrubPointer(
                x,
                y,
                eventTimeMs,
                publishLoopBoundaryMove = boundary == null ||
                    (scrubPendingLoopMove?.capturedBMs ?: -1L) < 0L
            )
            if (scrubPointerId == -1) return
            if (boundary == null) {
                val accepted = scrubSessionSink?.onScrubCommit(generation, scrubPreviewPositionMs) == true
                if (!accepted) scrubSessionSink?.onScrubCancel(generation)
                finishFeedback(accepted)
            } else {
                retryPendingLoopBoundaryMove(boundary)
                val accepted = endLoopBoundary(boundary, eventTimeMs * 1_000L)
                feedback.change(scrubStartPositionMs.toFloat(), scrubPreviewPositionMs.toFloat(), accepted, null, true)
                finishFeedback(accepted)
            }
        } else if (scrubLoopBoundary != null && scrubSessionStarted) {
            endLoopBoundary(scrubLoopBoundary!!, eventTimeMs * 1_000L)
        } else if (!scrubVerticalWon && scrubLoopBoundary == null) {
            val duration = current.timeline.durationMs
            val direct = quantizeSeekPosition(
                playerSurfaceSeekerProgressAtX(
                    x,
                    host.seekerTrackLeft,
                    host.seekerTrackRight
                ) * duration.toDouble(),
                duration
            )
            if (scrubSessionSink?.onScrubBegin(generation) == true) {
                beginFeedback()
                scrubSessionSink?.onScrubPreview(generation, direct)
                val accepted = scrubSessionSink?.onScrubCommit(generation, direct) == true
                if (!accepted) scrubSessionSink?.onScrubCancel(generation)
                feedback.change(scrubStartPositionMs.toFloat(), direct.toFloat(), accepted, null, true)
                finishFeedback(accepted)
            }
        }
        resetScrubPointer()
    }

    fun resetScrubPointer(hardResetMorph: Boolean = false) {
        val hadPointer = scrubPointerId != -1
        val oldFineEngaged = scrubFineEngaged
        val generation = scrubOwnerGeneration.takeIf { it != NO_SCRUB_OWNER_GENERATION }
        val terminalSink = scrubSessionSink
        feedback.finish(accepted = false, deferred = true)
        val releasedBoundary = scrubLoopBoundary
        val retainFineRelease = !hardResetMorph && hadPointer && scrubSessionStarted &&
            host.currentWaveformMorph(host.frameNanos()) > .001f
        host.scrubFineReleaseActive = retainFineRelease
        host.scrubFineReleaseBoundary = releasedBoundary.takeIf { retainFineRelease }
        if (hadPointer) {
            host.animateScrubGrowthTo(0f, host.uptimeMillis(), releasedBoundary)
        }
        scrubPointerId = -1
        scrubMode = PrecisionScrubMode.READY
        scrubDragging = false
        scrubSessionStarted = false
        scrubFineEngaged = false
        scrubFineIntentState = PlayerSurfaceFineIntentState()
        scrubStartEventTimeMs = 0L
        scrubLastEventTimeMs = 0L
        scrubSpeedDpPerSecond = 0.0
        scrubSensitivityMsPerPx = 0.0
        scrubLoopBoundary = null
        scrubPendingLoopMove = null
        scrubOwnerGeneration = NO_SCRUB_OWNER_GENERATION
        scrubSessionSink = null
        if (!retainFineRelease) {
            scrubPreviewLoopStartMs = -1L
            scrubPreviewLoopEndMs = -1L
        }
        fineWindowRequestBucket = Long.MIN_VALUE
        if (hadPointer) {
            host.releaseLoopHandle()
            host.holdScrub(false)
            host.scheduleFrame()
        }
        if (hardResetMorph) {
            host.hardResetWaveformMorph()
        } else {
            // Ordinary release uses FINE_WAVEFORM_MORPH_DURATION_MS while thumb growth settles.
            // Only invalidated/hidden owners hard reset.
            host.animateWaveformMorphTo(0f)
        }
        loopDetentState = app.stemwise.ui.LoopStartDetentState()
        if (oldFineEngaged && generation != null) {
            terminalSink?.onFineModeChanged(generation, false, scrubPreviewPositionMs)
        }
        host.scheduleFrame()
        host.invalidateTimelineRegion()
    }

    fun requestFineWindowIfNeeded(generation: Long) {
        if (!scrubFineEngaged) return
        val bucket = scrubPreviewPositionMs / FINE_WINDOW_BUCKET_MS
        if (bucket == fineWindowRequestBucket) return
        fineWindowRequestBucket = bucket
        scrubSessionSink?.onFineModeChanged(generation, true, scrubPreviewPositionMs)
    }

    fun cancelScrubGesture(
        commit: Boolean,
        hardResetMorph: Boolean = false
    ) {
        val generation = scrubOwnerGeneration.takeIf { it != NO_SCRUB_OWNER_GENERATION }
        val terminalSink = scrubSessionSink
        if (scrubSessionStarted && generation != null) {
            val boundary = scrubLoopBoundary
            if (boundary != null) {
                retryPendingLoopBoundaryMove(boundary)
                endLoopBoundary(boundary, scrubLastEventTimeMs * 1_000L)
            } else if (commit) {
                if (terminalSink?.onScrubCommit(generation, scrubPreviewPositionMs) != true) {
                    terminalSink?.onScrubCancel(generation)
                }
            } else {
                terminalSink?.onScrubCancel(generation)
            }
        }
        resetScrubPointer(hardResetMorph = hardResetMorph)
    }

}
