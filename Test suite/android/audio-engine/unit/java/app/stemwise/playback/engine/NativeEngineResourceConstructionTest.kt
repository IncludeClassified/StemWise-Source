package app.stemwise.playback.engine

import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativeEngineResourceConstructionTest {
    @Test fun everyPartialAcquisitionUnwindsBorrowersBeforeTheirOwners() {
        for (failAt in 0..5) {
            val closed = mutableListOf<Int>()
            val failure = IllegalStateException("allocation $failAt")
            val result = runCatching {
                constructWithNativeRollback { register ->
                    for (index in 0..4) {
                        if (index == failAt) throw failure
                        register(AutoCloseable { closed += index })
                    }
                    if (failAt == 5) throw failure
                }
            }
            assertSame(failure, result.exceptionOrNull())
            assertEquals((0 until minOf(failAt, 5)).reversed().toList(), closed)
        }
        val closed = mutableListOf<Int>()
        assertEquals(7, constructWithNativeRollback { register ->
            register(AutoCloseable { closed += 1 }); 7
        })
        assertTrue(closed.isEmpty()) // A completed bundle transfers ownership to its environment.
    }

    @Test fun aCleanupFailureCannotSkipTheRemainingOwnersOrReplaceTheOriginalCause() {
        val primary = IllegalStateException("runtime construction")
        val cleanup = IllegalStateException("pool close")
        var outputClosed = false
        val actual = runCatching {
            constructWithNativeRollback<Unit> { register ->
                register(AutoCloseable { outputClosed = true })
                register(AutoCloseable { throw cleanup })
                throw primary
            }
        }.exceptionOrNull()
        assertSame(primary, actual)
        assertTrue(outputClosed)
        assertArrayEquals(arrayOf(cleanup), primary.suppressed)
    }
}
