package app.stemwise.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryInventoryAvailabilityTest {
    @Test
    fun `cached zero is restoring until exact enumeration verifies it`() = runBlocking {
        val lifecycle = LibraryStorageRefreshLifecycle()
        val cachedZero = LibraryInventory(lastCheckedEpochMs = 123L)

        lifecycle.restoreCachedInventory(cachedZero)
        assertEquals(LibraryInventoryAvailability.RESTORING, lifecycle.state.value.availability)
        assertEquals(123L, lifecycle.state.value.inventory.lastCheckedEpochMs)

        lifecycle.refresh { LibraryInventory(lastCheckedEpochMs = 456L) }
        assertEquals(LibraryInventoryAvailability.VERIFIED, lifecycle.state.value.availability)
        assertEquals(456L, lifecycle.state.value.inventory.lastCheckedEpochMs)
    }

    @Test
    fun `provider failure retains cached history but marks totals unavailable`() = runBlocking {
        val lifecycle = LibraryStorageRefreshLifecycle()
        val cached = LibraryInventory(songsFound = 3, actualLibraryBytes = 99L)
        lifecycle.restoreCachedInventory(cached)

        lifecycle.failWithCachedInventory(
            LibraryRootException("ROOT_UNAVAILABLE", "Reconnect the Library folder")
        )

        assertEquals(LibraryInventoryAvailability.UNAVAILABLE, lifecycle.state.value.availability)
        assertEquals(3, lifecycle.state.value.inventory.songsFound)
        assertEquals(99L, lifecycle.state.value.inventory.actualLibraryBytes)
        assertEquals("Reconnect the Library folder", lifecycle.state.value.errorMessage)
    }

    @Test
    fun `true root invalidation overrides a same-detail verified refresh failure`() = runBlocking {
        val verified = LibraryInventory(songsFound = 3, lastCheckedEpochMs = 123L)
        val lifecycle = LibraryStorageRefreshLifecycle()
        val failure = IllegalStateException("provider unavailable")
        lifecycle.replaceInventory(verified)

        lifecycle.failRefreshWithCachedInventory(failure)
        assertEquals(LibraryInventoryAvailability.VERIFIED, lifecycle.state.value.availability)

        lifecycle.failWithCachedInventory(failure)
        assertEquals(LibraryInventoryAvailability.UNAVAILABLE, lifecycle.state.value.availability)
        assertEquals(verified, lifecycle.state.value.inventory)
    }
}
