package app.stemwise.playback.engine

import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativeControllerPreparationEvidenceTest {
    @Test fun supersededOperationsRetainBoundedExactTerminalClocks() {
        val evidence = NativeControllerPreparationEvidence()
        val a = NativeRuntimeOwnership(1L, 2L)
        val b = NativeRuntimeOwnership(1L, 3L)
        repeat(80) { revision ->
            evidence.begin(NativeControllerPreparationOperation.STEM_PACKAGE, a,
                revision.toLong(), "opening", revision * 10L)
        }
        evidence.begin(NativeControllerPreparationOperation.STEM_PACKAGE, b, 79L, "opening", 800L)
        evidence.cancelOwnership(a, 830L)
        val snapshot = evidence.snapshot(850L)
        assertEquals(32, snapshot.size)
        assertEquals(31, snapshot.count { it.ownership == a })
        assertEquals("release-requested", snapshot.last { it.ownership == a }.stage)
        assertEquals(40L, snapshot.last { it.ownership == a }.elapsedMs)
        assertEquals(50L, snapshot.single { it.ownership == b }.elapsedMs)
        assertNull(snapshot.single { it.ownership == b }.cause)
        evidence.finish(NativeControllerPreparationOperation.STEM_PACKAGE, 1L, 79L,
            "failed", 870L, "Exact source no longer readable")
        val failed = evidence.snapshot(900L).single { it.ownership == b }
        assertEquals(70L, failed.elapsedMs)
        assertEquals("Exact source no longer readable", failed.cause)
    }
}
