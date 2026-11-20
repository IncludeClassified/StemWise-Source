package app.stemwise.work

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskRemovalMonitorRegistryTest {
    @Test
    fun destroyReplacesAnOwnedServiceAndTheLastOwnerStillStopsIt() {
        val registry = TaskRemovalMonitorRegistry()
        var starts = 0
        var stops = 0
        val start = { starts += 1; true }

        registry.armForWorker("worker", start)
        registry.onServiceStarted { stops += 1 }
        registry.onServiceDestroyed(start)
        registry.disarmWorker("worker") { stops += 1 }

        assertEquals(2, starts)
        assertEquals(1, stops)
    }

    @Test
    fun ownerArrivingDuringStopIsNotErasedByTheOldDestroyCallback() {
        val registry = TaskRemovalMonitorRegistry()
        var starts = 0
        var stops = 0
        val start = { starts += 1; true }

        registry.armForTask(start)
        registry.disarmTask { stops += 1 }
        registry.armForWorker("replacement", start)
        registry.onServiceDestroyed(start)
        registry.disarmWorker("replacement") { stops += 1 }

        assertEquals(3, starts)
        assertEquals(2, stops)
    }

    @Test
    fun lateStartAfterLastOwnerStopsDoesNotResurrectTheMonitor() {
        val registry = TaskRemovalMonitorRegistry()
        var starts = 0
        var stops = 0

        registry.armForTask { starts += 1; true }
        registry.disarmTask { stops += 1 }
        registry.onServiceStarted { stops += 1 }

        assertEquals(1, starts)
        assertEquals(2, stops)
    }
}
