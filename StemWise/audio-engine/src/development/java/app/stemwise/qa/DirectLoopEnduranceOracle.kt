package app.stemwise.qa

import app.stemwise.playback.engine.NativeAudioPresentationJournalSnapshot
import app.stemwise.playback.engine.NativeAudioPresentationSpan
import app.stemwise.playback.engine.NativeRuntimeOwnership
import app.stemwise.playback.engine.NativeRuntimePresentedPosition
import app.stemwise.playback.engine.NativeRuntimeRenderReadinessDiagnostics
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Logical acknowledgement alone cannot retire already accepted loop PCM. */
fun loopClearHasReachedLinearPresentation(
    owner: NativeRuntimeOwnership,
    clearRevision: Long,
    outgoingEndFrameExclusive: Long,
    position: NativeRuntimePresentedPosition?,
    readiness: NativeRuntimeRenderReadinessDiagnostics?
): Boolean {
    require(clearRevision > 0L && outgoingEndFrameExclusive > 0L)
    return position?.ownership == owner && readiness?.ownership == owner &&
        readiness.directAtomicPolicyGeneration == owner.generation &&
        readiness.directAtomicPolicyPublishedRevision == clearRevision &&
        readiness.directAtomicPolicyAdoptedRevision == clearRevision &&
        readiness.directAtomicPolicyObservedRevision == clearRevision &&
        position.loopPolicyAdoptedRevision == clearRevision &&
        position.lastTimelineFrame >= outgoingEndFrameExclusive
}

/** Exact consumed source mappings; endpoint proximity alone cannot establish a loop. */
class DirectLoopEnduranceOracle(
    private val startFrame: Long,
    private val endFrame: Long,
    private val owner: NativeRuntimeOwnership,
    policyRevision: Long,
    baseline: NativeAudioPresentationJournalSnapshot,
    val sourceFramesPerPcmFrame: Double = 1.0
) {
    private val tempo = sourceFramesPerPcmFrame.toFloat()
    private val collisions = baseline.writerCollisionCount
    private val rejected = baseline.invalidPublicationCount
    private var physicalRevision = policyRevision
    private var previousSpan: NativeAudioPresentationSpan? = null
    private var mappingModes = 0b1111
    var cursor: Long = baseline.writeSequence
        private set
    var wraps: Long = 0L
        private set

    init {
        require(startFrame >= 0L && endFrame > startFrame && policyRevision > 0L)
        require(sourceFramesPerPcmFrame.isFinite() && tempo in .5f..2f)
        require(baseline.coherent && !baseline.sequenceExhausted && baseline.overflowCount == 0L)
    }

    fun consume(journal: NativeAudioPresentationJournalSnapshot) {
        check(journal.coherent && !journal.sequenceExhausted && journal.overflowCount == 0L) {
            "Loop callback journal is incomplete"
        }
        check(journal.requestedAfterSequence == cursor &&
            journal.writerCollisionCount == collisions && journal.invalidPublicationCount == rejected) {
            "Loop callback journal lost or rejected publication"
        }
        for (span in journal.entries) {
            check(span.sequence == Math.addExact(cursor, 1L)) { "Loop callback journal sequence gap" }
            check(span.generation == owner.generation && span.selectionToken == owner.selectionToken &&
                span.firstLoopPolicyRevision >= physicalRevision &&
                span.firstLoopPolicyRevision == span.lastLoopPolicyRevision) {
                "Loop changed exact owner or regressed its physical revision: expected=$owner/$physicalRevision span=$span"
            }
            check(span.continuityViolationCount == 0L) {
                "Loop callback continuity fault: sameCallbackSpans=" +
                    journal.entries.filter { it.callbackSequence == span.callbackSequence } +
                    " previousSpan=$previousSpan span=$span"
            }
            check(span.firstFingerprintSequence == span.lastFingerprintSequence && span.actualTempo == tempo) {
                "Loop mapping did not preserve one exact rendered numerical payload: " +
                    "expectedTempo=$tempo previousSpan=$previousSpan span=$span"
            }
            val count = span.renderFrameCount
            val first = span.firstRenderFrameOffset
            val after = Math.addExact(first, span.pcmFrames)
            val mappingFirst = span.mappingFirstRenderFrameOffset
            val sourceStart = span.renderSourceStartFrame
            val sourceEnd = span.renderSourceEndFrameExclusive
            check(count > 0L && mappingFirst <= first && first < count && after <= count &&
                sourceStart >= startFrame.toDouble() && sourceStart < endFrame.toDouble() &&
                sourceEnd > sourceStart && sourceEnd <= endFrame.toDouble()) {
                "Loop has missing or invalid consumed mapping: $span"
            }
            // This is the current producer's frame-count boundary rule, not an endpoint tolerance.
            val boundaryFrames = ceil(max(0.0,
                (endFrame.toDouble() - sourceStart) / tempo.toDouble() - 1.0e-9)).toLong()
            if (sourceEnd == endFrame.toDouble()) {
                check(count == boundaryFrames) { "Loop compressed or extended its final source block: $span" }
            } else {
                val nominalEnd = sourceStart + count.toDouble() * tempo.toDouble()
                check(count < boundaryFrames && (sourceEnd == nominalEnd ||
                    sourceEnd == Math.fma(count.toDouble(), tempo.toDouble(), sourceStart))) {
                    "Loop payload disagrees with the actual numerical source clock: $span"
                }
            }
            // Partial receipts interpolate the remaining interval again. Preserve that exact
            // enqueue origin. Native contraction can independently fuse its rebase and frame math.
            var possibleModes = 0
            for (mode in 0..3) {
                val fraction = mappingFirst.toDouble() / count.toDouble()
                val remainingStart = if ((mode and 1) != 0)
                    Math.fma(sourceEnd - sourceStart, fraction, sourceStart)
                else sourceStart + (sourceEnd - sourceStart) * fraction
                val step = (sourceEnd - remainingStart) / (count - mappingFirst).toDouble()
                fun mapped(offset: Long): Double = if ((mode and 2) != 0)
                    Math.fma((offset - mappingFirst).toDouble(), step, remainingStart)
                else remainingStart + (offset - mappingFirst).toDouble() * step
                val expectedNext = if (after == count) sourceEnd.toLong()
                    else min(mapped(after), sourceEnd).toLong()
                if (span.firstTimelineFrame == mapped(first).toLong() &&
                    span.lastTimelineFrame == mapped(after - 1L).toLong() &&
                    span.lastNextTimelineFrame == expectedNext) possibleModes = possibleModes or (1 shl mode)
            }
            check(possibleModes != 0 &&
                span.firstTimelineFrame in startFrame until endFrame &&
                span.lastTimelineFrame in startFrame until endFrame) {
                "Loop disagrees with its consumed source/PCM mapping: previousSpan=$previousSpan span=$span"
            }
            previousSpan?.let { previous ->
                val previousAfter = Math.addExact(previous.firstRenderFrameOffset, previous.pcmFrames)
                val samePayload = span.firstFingerprintSequence == previous.lastFingerprintSequence
                val exactBoundary = previousAfter == previous.renderFrameCount &&
                    previous.renderSourceEndFrameExclusive == endFrame.toDouble() &&
                    sourceStart == startFrame.toDouble() && first == 0L &&
                    previous.lastNextTimelineFrame == endFrame
                check(span.firstLoopPolicyRevision == physicalRevision || exactBoundary) {
                    "Loop physical revision changed before an actual boundary"
                }
                if (exactBoundary) {
                    // A retained prepared head may reuse its immutable fingerprint at the next A.
                    wraps = Math.addExact(wraps, 1L)
                } else if (samePayload) {
                    check(sourceStart == previous.renderSourceStartFrame &&
                        sourceEnd == previous.renderSourceEndFrameExclusive && count == previous.renderFrameCount &&
                        first == previousAfter &&
                        (mappingFirst == previous.mappingFirstRenderFrameOffset || mappingFirst == first)) {
                        "Loop payload was replayed or changed its mapping: previousSpan=$previous span=$span"
                    }
                    if (mappingFirst == previous.mappingFirstRenderFrameOffset) {
                        possibleModes = possibleModes and mappingModes
                        check(possibleModes != 0) { "Loop changed arithmetic within one enqueue mapping" }
                    }
                } else {
                    check(span.firstFingerprintSequence > previous.lastFingerprintSequence &&
                        previousAfter == previous.renderFrameCount && first == 0L &&
                        sourceStart == previous.renderSourceEndFrameExclusive) {
                        "Loop rewound before B or skipped/replayed payload frames: previousSpan=$previous span=$span"
                    }
                }
                check(exactBoundary || span.firstTimelineFrame == previous.lastNextTimelineFrame) {
                    "Loop has a gap between consumed mappings: previousSpan=$previous span=$span"
                }
            }
            mappingModes = possibleModes
            physicalRevision = span.lastLoopPolicyRevision
            cursor = span.sequence
            previousSpan = span
        }
    }
}

/** The caller must first observe a real positive fault; a zero baseline proves no preservation. */
fun requirePreservedProcessFaults(
    beforeInvalid: Long, beforeFlush: Long, afterInvalid: Long, afterFlush: Long
) {
    require(beforeInvalid >= 0L && beforeFlush >= 0L && (beforeInvalid > 0L || beforeFlush > 0L)) {
        "Fault preservation requires an observed positive baseline"
    }
    check(afterInvalid >= beforeInvalid && afterFlush >= beforeFlush) {
        "Runtime replacement reset process fault evidence"
    }
}
