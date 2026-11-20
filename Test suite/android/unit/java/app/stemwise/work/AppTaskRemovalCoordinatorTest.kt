package app.stemwise.work

import org.junit.Assert.assertEquals
import org.junit.Test

class AppTaskRemovalCoordinatorTest {
    @Test
    fun taskRemovalClosesEnqueuesBeforeCancellingEveryDurableWorkOwner() {
        val calls = mutableListOf<String>()

        performTaskRemoval(
            closeEnqueueOwners = { calls += "gate-and-manual" },
            cancelSync = { calls += "sync" },
            cancelPreparation = { calls += "preparation-and-mvsep" }
        )

        assertEquals(
            listOf("gate-and-manual", "sync", "preparation-and-mvsep"),
            calls
        )
    }
}
