package app.stemwise.playback

import app.stemwise.playback.engine.NativeAudioPresentationJournalSnapshot
import app.stemwise.playback.engine.NativeAudioPresentationSpan
import app.stemwise.playback.engine.NativeRuntimeOwnership
import app.stemwise.qa.DirectLoopEnduranceOracle
import app.stemwise.qa.loopClearHasReachedLinearPresentation
import app.stemwise.playback.engine.NativeRuntimeBank
import app.stemwise.playback.engine.NativeRuntimePresentedPosition
import app.stemwise.playback.engine.NativeRuntimeRenderReadinessDiagnostics
import app.stemwise.playback.engine.NativeRuntimeSourceMode
import org.junit.Assert.assertFalse
import app.stemwise.qa.requirePreservedProcessFaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class DirectLoopEnduranceOracleTest {
    @Test fun acknowledgedClearDoesNotMakeQueuedOldLoopPcmLinear() {
        val owner = NativeRuntimeOwnership(7L, 2L)
        val ready = NativeRuntimeRenderReadinessDiagnostics(1L, true, NativeRuntimeBank.FIRST, owner,
            1L, NativeRuntimeSourceMode.ORIGINAL, 0L, 0L, NativeRuntimeSourceMode.ORIGINAL,
            0L, 0L, 0L, 2048L, 0L, 0L, 0L, emptyList(),
            directAtomicPolicyGeneration = owner.generation, directAtomicPolicyPublishedRevision = 42L,
            directAtomicPolicyAdoptedRevision = 42L, directAtomicPolicyObservedRevision = 42L)
        val position = NativeRuntimePresentedPosition(1L, owner, 1L, 130_623L, 1L, 1L, 256L,
            loopPolicyRevision = 601L, loopPolicyAdoptedRevision = 42L)
        assertFalse(loopClearHasReachedLinearPresentation(owner, 42L, 3_007L * 48L, position, ready))
        assertFalse(loopClearHasReachedLinearPresentation(owner, 42L, 3_007L * 48L,
            position.copy(lastTimelineFrame = 96_911L), ready))
        val linear = position.copy(lastTimelineFrame = 3_007L * 48L)
        assertTrue(loopClearHasReachedLinearPresentation(owner, 42L, 3_007L * 48L, linear, ready))
        assertFalse(loopClearHasReachedLinearPresentation(owner, 43L, 3_007L * 48L, linear, ready))
        assertFalse(loopClearHasReachedLinearPresentation(owner, 42L, 3_007L * 48L, linear,
            ready.copy(directAtomicPolicyObservedRevision = 41L)))
        assertFalse(loopClearHasReachedLinearPresentation(owner, 42L, 3_007L * 48L,
            linear.copy(ownership = NativeRuntimeOwnership(8L, 4L)), ready))
    }
    private fun span(sequence: Long, first: Long, last: Long, frames: Long, faults: Long = 0L,
                     tempo: Float = 1f, sourceStart: Double = first.toDouble(),
                     sourceEnd: Double = sourceStart + frames * tempo.toDouble(),
                     totalFrames: Long = frames, offset: Long = 0L, mappingOffset: Long = 0L,
                     fingerprint: Long = sequence, next: Long = sourceEnd.toLong()) =
        NativeAudioPresentationSpan(sequence, 2L, 7L, fingerprint, fingerprint, sequence, frames,
            3L, 3L, first, last, faults, sourceStart, sourceEnd, totalFrames, offset, mappingOffset, tempo, next)

    private fun journal(after: Long, vararg entries: NativeAudioPresentationSpan,
                        collisions: Long = 0L, rejected: Long = 0L) =
        NativeAudioPresentationJournalSnapshot(32, after, entries.lastOrNull()?.sequence ?: after,
            1L, 0L, collisions, rejected, false, true, entries.toList())

    private fun oracle() = DirectLoopEnduranceOracle(100L, 200L,
        NativeRuntimeOwnership(7L, 2L), 3L, journal(0L))

    @Test fun exactBToACrossingsCountAcrossAndInsideCallbackSpans() {
        val oracle = oracle()
        oracle.consume(journal(0L, span(1L, 150L, 199L, 50L)))
        oracle.consume(journal(1L, span(2L, 100L, 199L, 100L).copy(callbackSequence = 2L, callbackPcmFrames = 250L),
            span(3L, 100L, 199L, 100L).copy(callbackSequence = 2L, callbackPcmFrames = 250L),
            span(4L, 100L, 149L, 50L).copy(callbackSequence = 2L, callbackPcmFrames = 250L)))
        assertEquals(3L, oracle.wraps)
    }

    @Test fun inRangePrematureRewindsCannotCountAsWraps() {
        val oracle = oracle()
        oracle.consume(journal(0L, span(1L, 110L, 170L, 61L)))
        val failure = assertThrows(IllegalStateException::class.java) {
            oracle.consume(journal(1L, span(2L, 110L, 170L, 61L)))
        }
        assertTrue(failure.message.orEmpty().contains("rewound before B"))
    }

    @Test fun retainedHeadFingerprintsMayRepeatOnlyAtAnExactBToABoundary() {
        val observed = DirectLoopEnduranceOracle(48000L, 144000L, NativeRuntimeOwnership(2L, 6L),
            495L, journal(2377L), .5)
        val before = span(2378L, 143888L, 143999L, 224L, tempo = .5f,
            sourceStart = 143880.0, sourceEnd = 144000.0, totalFrames = 240L,
            offset = 16L, fingerprint = 1052L, next = 144000L).copy(
            generation = 6L, selectionToken = 2L, callbackSequence = 1414L,
            firstLoopPolicyRevision = 495L, lastLoopPolicyRevision = 495L, callbackPcmFrames = 256L)
        val reusedHead = span(2379L, 48000L, 48015L, 32L, tempo = .5f,
            sourceEnd = 48128.0, totalFrames = 256L, fingerprint = 481L, next = 48016L).copy(
            generation = 6L, selectionToken = 2L, callbackSequence = 1414L,
            firstLoopPolicyRevision = 1688L, lastLoopPolicyRevision = 1688L, callbackPcmFrames = 256L)
        observed.consume(journal(2377L, before, reusedHead))
        assertEquals(1L, observed.wraps)

        val singlePayload = oracle()
        singlePayload.consume(journal(0L, span(1L, 100L, 199L, 100L, fingerprint = 1L),
            span(2L, 100L, 199L, 100L, fingerprint = 1L)))
        assertEquals(1L, singlePayload.wraps)

        for (next in listOf(span(2L, 100L, 149L, 50L, fingerprint = 2L),
            span(2L, 150L, 199L, 50L, fingerprint = 1L))) {
            val premature = oracle()
            premature.consume(journal(0L, span(1L, 100L, 149L, 50L, fingerprint = 2L)))
            assertThrows(IllegalStateException::class.java) { premature.consume(journal(1L, next)) }
        }
    }

    @Test fun sourceClockProofCountsRealWrapsAtHalfAndDoubleTempo() {
        val double = DirectLoopEnduranceOracle(100L, 200L, NativeRuntimeOwnership(7L, 2L),
            3L, journal(0L), 2.0)
        double.consume(journal(0L, span(1L, 150L, 198L, 25L, tempo = 2f), span(2L, 100L, 148L, 25L, tempo = 2f)))
        assertEquals(1L, double.wraps)
        val half = DirectLoopEnduranceOracle(100L, 200L, NativeRuntimeOwnership(7L, 2L),
            3L, journal(0L), .5)
        half.consume(journal(0L, span(1L, 199L, 199L, 1L, tempo = .5f, sourceStart = 199.5),
            span(2L, 100L, 149L, 100L, tempo = .5f)))
        assertEquals(1L, half.wraps)
        assertThrows(IllegalStateException::class.java) {
            half.consume(journal(2L, span(3L, 110L, 159L, 100L, tempo = .5f)))
        }
    }

    @Test fun oneAndAHalfTempoTracksFractionalOriginsAndRejectsPrematureRewinds() {
        for (origin in listOf(199.0, 199.5)) {
            val oracle = DirectLoopEnduranceOracle(100L, 205L, NativeRuntimeOwnership(7L, 2L),
                3L, journal(0L), 1.5)
            val end = origin + 3.0
            oracle.consume(journal(0L, span(1L, 199L, if (origin == 199.0) 200L else 201L,
                2L, tempo = 1.5f, sourceStart = origin)))
            oracle.consume(journal(1L, span(2L, 202L, 203L, 2L, tempo = 1.5f,
                sourceStart = end, sourceEnd = 205.0), span(3L, 100L, 103L, 3L, tempo = 1.5f)))
            assertEquals(1L, oracle.wraps)
            assertThrows(IllegalStateException::class.java) {
                oracle.consume(journal(3L, span(4L, 100L, 103L, 3L, tempo = 1.5f)))
            }
        }
        val wrongClock = DirectLoopEnduranceOracle(100L, 205L, NativeRuntimeOwnership(7L, 2L),
            3L, journal(0L), 1.5)
        assertThrows(IllegalStateException::class.java) {
            wrongClock.consume(journal(0L, span(1L, 100L, 104L, 3L, tempo = 1.5f)))
        }
    }

    @Test fun fractionalBoundaryClampPreservesPartialReceiptsAndRejectsForgedClocks() {
        val speed = 1.35f
        val source = 30.0 * speed.toDouble()
        fun proof() = DirectLoopEnduranceOracle(0L, 45L, NativeRuntimeOwnership(7L, 2L),
            3L, journal(0L), speed.toDouble())
        val first = span(1L, 40L, 41L, 2L, tempo = speed, sourceStart = source,
            sourceEnd = 45.0, totalFrames = 4L, next = 42L)
        val suffix = span(2L, 42L, 43L, 2L, tempo = speed, sourceStart = source,
            sourceEnd = 45.0, totalFrames = 4L, offset = 2L, mappingOffset = 2L, fingerprint = 1L, next = 45L)
        val nextCycle = span(3L, 0L, 4L, 4L, tempo = speed)
        val valid = proof()
        valid.consume(journal(0L, first, suffix, nextCycle))
        assertEquals(1L, valid.wraps)
        for (wrong in listOf(suffix.copy(firstTimelineFrame = 41L),
            suffix.copy(firstRenderFrameOffset = 1L), suffix.copy(lastNextTimelineFrame = 44L),
            suffix.copy(renderSourceEndFrameExclusive = 44.0),
            suffix.copy(renderFrameCount = 5L), suffix.copy(actualTempo = 1f))) {
            val rejected = proof()
            rejected.consume(journal(0L, first))
            assertThrows(IllegalStateException::class.java) { rejected.consume(journal(1L, wrong)) }
        }
        val early = proof()
        early.consume(journal(0L, first))
        assertThrows(IllegalStateException::class.java) { early.consume(journal(1L, nextCycle.copy(sequence = 2L))) }
        for (wrong in listOf(first.copy(generation = 3L), first.copy(sequence = 2L))) {
            assertThrows(IllegalStateException::class.java) { proof().consume(journal(0L, wrong)) }
        }
    }

    @Test fun exactFusedAndUnfusedMappingsRemainConsistentWithinAnEnqueue() {
        fun proof() = DirectLoopEnduranceOracle(0L, 12L, NativeRuntimeOwnership(7L, 2L),
            3L, journal(0L), 1.35f.toDouble())
        fun prefix(next: Long) = span(1L, 1L, 6L, 5L, tempo = 1.35f, sourceStart = 0.0,
            sourceEnd = 12.0, totalFrames = 9L, offset = 1L, mappingOffset = 1L, next = next)
        fun suffix(first: Long) = span(2L, first, first, 1L, tempo = 1.35f, sourceStart = 0.0,
            sourceEnd = 12.0, totalFrames = 9L, offset = 6L, mappingOffset = 1L,
            fingerprint = 1L, next = 9L)
        // At original offset 6, 4/3 + 5*(4/3) floors to 7 unfused and 8 fused.
        for (knownFrame in listOf(7L, 8L)) {
            proof().consume(journal(0L, prefix(knownFrame), suffix(knownFrame)))
        }
        for (wrongFrame in listOf(6L, 9L)) {
            assertThrows(IllegalStateException::class.java) {
                proof().consume(journal(0L, prefix(7L), suffix(wrongFrame)))
            }
        }
        assertThrows(IllegalStateException::class.java) {
            proof().consume(journal(0L, prefix(7L), suffix(8L)))
        }
        val changedArithmetic = DirectLoopEnduranceOracle(0L, 36L, NativeRuntimeOwnership(7L, 2L),
            3L, journal(0L), 1.35f.toDouble())
        // The shared adjacent frame is 9 in both modes; only the whole enqueue proves the conflict.
        changedArithmetic.consume(journal(0L, span(1L, 7L, 7L, 1L, tempo = 1.35f,
            sourceStart = 0.0, sourceEnd = 36.0, totalFrames = 27L,
            offset = 6L, mappingOffset = 1L, next = 9L)))
        assertThrows(IllegalStateException::class.java) {
            changedArithmetic.consume(journal(1L, span(2L, 9L, 20L, 9L, tempo = 1.35f,
                sourceStart = 0.0, sourceEnd = 36.0, totalFrames = 27L,
                offset = 7L, mappingOffset = 1L, fingerprint = 1L, next = 21L)))
        }
        // The enqueue-origin multiply/add is independent of the per-frame multiply/add.
        for (knownFrame in listOf(239945L, 239946L)) {
            DirectLoopEnduranceOracle(0L, 240000L, NativeRuntimeOwnership(7L, 2L),
                3L, journal(0L), 1.35f.toDouble()).consume(journal(0L,
                span(1L, knownFrame, knownFrame, 1L, tempo = 1.35f,
                    sourceStart = 239935.19999999998, sourceEnd = 240000.0, totalFrames = 48L,
                    offset = 8L, mappingOffset = 8L, next = 239947L)))
        }
    }

    @Test fun aNewPhysicalContinuationRevisionRequiresAnActualBoundary() {
        val valid = oracle()
        valid.consume(journal(0L, span(1L, 150L, 199L, 50L)))
        valid.consume(journal(1L, span(2L, 100L, 149L, 50L).copy(
            firstLoopPolicyRevision = 4L, lastLoopPolicyRevision = 4L)))
        assertEquals(1L, valid.wraps)
        val premature = oracle()
        premature.consume(journal(0L, span(1L, 100L, 149L, 50L)))
        assertThrows(IllegalStateException::class.java) {
            premature.consume(journal(1L, span(2L, 150L, 199L, 50L).copy(
                firstLoopPolicyRevision = 4L, lastLoopPolicyRevision = 4L)))
        }
    }

    @Test fun hiddenInteriorFaultAndBothPublicationLossCountersReject() {
        val observed = oracle()
        val previous = span(1L, 100L, 129L, 30L)
        val offending = span(2L, 130L, 159L, 30L, faults = 1L)
        observed.consume(journal(0L, previous))
        val continuity = assertThrows(IllegalStateException::class.java) {
            observed.consume(journal(1L, offending))
        }
        assertTrue(continuity.message.orEmpty().contains("callback continuity fault"))
        assertTrue(continuity.message.orEmpty().contains("previousSpan=$previous span=$offending"))
        assertEquals(1L, observed.cursor)
        assertEquals(0L, observed.wraps)
        for (counters in listOf(1L to 0L, 0L to 1L)) {
            val loss = assertThrows(IllegalStateException::class.java) {
                oracle().consume(journal(0L, span(1L, 100L, 159L, 60L),
                    collisions = counters.first, rejected = counters.second))
            }
            assertTrue(loss.message.orEmpty().contains("lost or rejected publication"))
        }
        assertThrows(IllegalStateException::class.java) {
            oracle().consume(journal(0L, span(2L, 100L, 159L, 60L)))
        }
    }

    @Test fun resetMutantCannotPreserveARealPositiveFaultBaseline() {
        requirePreservedProcessFaults(0L, 3L, 0L, 3L)
        assertThrows(IllegalStateException::class.java) { requirePreservedProcessFaults(0L, 3L, 0L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { requirePreservedProcessFaults(0L, 0L, 0L, 0L) }
    }
}
