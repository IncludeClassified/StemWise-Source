package app.stemwise.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryProjectionAvailabilityTest {
    @Test
    fun `availability tracker represents restore verify and failure`() {
        val tracker = LibraryProjectionAvailabilityTracker()

        assertEquals(LibraryProjectionAvailability.RESTORING, tracker.state.value)
        tracker.markProjected()
        assertEquals(LibraryProjectionAvailability.PROJECTED, tracker.state.value)
        tracker.markVerified()
        assertEquals(LibraryProjectionAvailability.VERIFIED, tracker.state.value)
        tracker.beginRestoration()
        assertEquals(LibraryProjectionAvailability.RESTORING, tracker.state.value)
        tracker.markUnavailable()
        assertEquals(LibraryProjectionAvailability.UNAVAILABLE, tracker.state.value)
    }

}
