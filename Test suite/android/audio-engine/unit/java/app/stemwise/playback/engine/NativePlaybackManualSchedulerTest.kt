package app.stemwise.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
internal class NativePlaybackManualSchedulerTest : NativePlaybackControllerTestSupport() {
    @Test fun immediateWorkAndOverlappingTimersUseAbsoluteDeadlines() {
        val scheduler = ManualScheduler()
        val events = mutableListOf<Pair<String, Long>>()
        scheduler.schedule(100) { events += "later" to scheduler.monotonicTimeMs() }
        scheduler.advanceTimeBy(30)
        scheduler.schedule(20) { events += "earlier" to scheduler.monotonicTimeMs() }
        scheduler.execute { events += "ready" to scheduler.monotonicTimeMs() }
        scheduler.runSteps(3)
        assertEquals(listOf("ready" to 30L, "earlier" to 50L, "later" to 100L), events)
    }

    @Test fun elapsedDelayIsNeverAppliedTwiceAndEqualDeadlinesKeepInsertionOrder() {
        val scheduler = ManualScheduler()
        val events = mutableListOf<Int>()
        scheduler.schedule(10) { events += 1 }
        scheduler.schedule(10) { events += 2 }
        scheduler.advanceTimeBy(50)
        scheduler.execute { events += 3 }
        scheduler.runSteps(3)
        assertEquals(listOf(1, 2, 3), events)
        assertEquals(50L, scheduler.monotonicTimeMs())
    }
}
