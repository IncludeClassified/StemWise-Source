package app.stemwise.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class LoopRangeTest {
    @Test
    fun publicAndEngineRangesShareStrictOverflowSafeAdmission() {
        val cases = listOf(Triple(0L, 99L, false), Triple(0L, 100L, true),
            Triple(-1L, 100L, false), Triple(1000L, 999L, false),
            Triple(Long.MAX_VALUE - 100L, Long.MAX_VALUE, true),
            Triple(Long.MAX_VALUE, Long.MIN_VALUE, false))
        cases.forEach { (a, b, expected) ->
            assertEquals(expected, runCatching { LoopRange(a, b) }.isSuccess)
            assertEquals(expected, runCatching {
                app.stemwise.playback.engine.EngineLoopConfig(a, b)
            }.isSuccess)
        }
    }

    @Test
    fun acceptsHundredMillisecondsAndExactSongBoundaries() {
        assertEquals(
            LoopRange(0L, 100L),
            validatedLoopRange(0L, 100L, 5_000L)
        )
        assertEquals(
            LoopRange(4_900L, 5_000L),
            validatedLoopRange(4_900L, 5_000L, 5_000L)
        )
        assertEquals(
            LoopRange(0L, 5_000L),
            validatedLoopRange(-20L, 5_020L, 5_000L)
        )
    }

    @Test
    fun rejectsCrossedOrShortRangesInsteadOfSwappingThem() {
        assertNull(validatedLoopRange(1_000L, 999L, 5_000L))
        assertNull(validatedLoopRange(1_000L, 1_099L, 5_000L))
        assertNull(validatedLoopRange(null, 1_000L, 5_000L))
        assertNull(validatedLoopRange(1_000L, null, 5_000L))
    }

    @Test
    fun bOnlyCommandBecomesPointAAndCannotPublishAnInvalidLoop() {
        assertEquals(
            NormalizedLoopDraft(pointA = 1_250L, pointB = null),
            normalizedLoopDraft(pointA = null, pointB = 1_250L, durationMs = 5_000L)
        )
        assertEquals(
            NormalizedLoopDraft(pointA = 5_000L, pointB = null),
            normalizedLoopDraft(pointA = null, pointB = 8_000L, durationMs = 5_000L)
        )
        assertEquals(
            NormalizedLoopDraft(pointA = null, pointB = null),
            normalizedLoopDraft(pointA = null, pointB = null, durationMs = 5_000L)
        )
    }

    @Test
    fun crossedSecondBoundaryRemainsAnAOnlyDraftInsteadOfBeingSwapped() {
        val draft = normalizedLoopDraft(pointA = 2_000L, pointB = 1_000L, durationMs = 5_000L)
        assertEquals(NormalizedLoopDraft(pointA = 2_000L, pointB = 1_000L), draft)
        assertNull(validatedLoopRange(draft.pointA, draft.pointB, 5_000L))
    }
}
