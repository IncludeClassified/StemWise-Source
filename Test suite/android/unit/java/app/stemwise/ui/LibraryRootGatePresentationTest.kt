package app.stemwise.ui

import app.stemwise.data.RETAINED_OPERATION_ROOT_REQUIRED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRootGatePresentationTest {
    @Test
    fun `startup gate uses the accepted normal-state copy only`() {
        val presentation = libraryRootGatePresentation(
            errorCode = null,
            operationalError = null
        )

        assertEquals("Choose your Library folder", presentation.heading)
        assertEquals("Choose folder", presentation.actionLabel)
        assertNull(presentation.operationalError)
    }

    @Test
    fun `startup gate carries cancellation and rejects invalid layouts concisely`() {
        val cancelled = libraryRootGatePresentation(
            errorCode = "ROOT_CHECKING",
            operationalError = "Folder access was not granted. Choose a folder to continue."
        )
        val invalid = libraryRootGatePresentation(
            errorCode = "ROOT_LAYOUT_UNSUPPORTED",
            operationalError = null
        )

        assertEquals(
            "Folder access was not granted. Choose a folder to continue.",
            cancelled.operationalError
        )
        assertEquals(
            "This folder cannot be used. Choose a different folder.",
            invalid.operationalError
        )
        assertEquals("Choose your Library folder", invalid.heading)
        assertEquals("Choose folder", invalid.actionLabel)


    }

    @Test
    fun `inaccessible and legacy roots use folder recovery`() {
        assertTrue(libraryRootFailureNeedsReconnect(true, "ROOT_PERMISSION_LOST"))
        assertTrue(libraryRootFailureNeedsReconnect(true, "ROOT_UNAVAILABLE"))
        assertFalse(libraryRootFailureNeedsReconnect(false, RETAINED_OPERATION_ROOT_REQUIRED))
        assertFalse(libraryRootFailureNeedsReconnect(false, "ROOT_PERMISSION_LOST"))
        assertFalse(libraryRootFailureNeedsReconnect(true, "ROOT_LAYOUT_UNSUPPORTED"))
        assertFalse(libraryRootFailureNeedsReconnect(true, null))
    }

    @Test
    fun `permission recovery uses the accepted assurance and action`() {
        val presentation = libraryRecoveryPresentation(
            code = "ROOT_PERMISSION_LOST",
            message = "Folder access was not granted. Reconnect the same folder to continue."
        )

        assertEquals("The Library folder could not be opened.", presentation.summary)
        assertEquals("Folder permission expired", presentation.reason)
        assertEquals("Reconnect the same folder to continue.", presentation.nextStep)
        assertEquals("Reconnect", presentation.actionLabel)
        assertTrue(presentation.reconnectsFolder)
        assertEquals(
            "Folder access was not granted. Reconnect the same folder to continue.",
            presentation.operationalNotice
        )
    }

    @Test
    fun `unavailable and generic recovery remain truthful`() {
        val unavailable = libraryRecoveryPresentation("ROOT_UNAVAILABLE", "provider unavailable")
        val generic = libraryRecoveryPresentation("DATABASE_OPEN_FAILED", "Database could not open")
        val pending = libraryRecoveryPresentation(RETAINED_OPERATION_ROOT_REQUIRED, "")

        assertEquals("Library folder unavailable", unavailable.reason)
        assertEquals("Connect the storage and reconnect the same folder.", unavailable.nextStep)
        assertEquals("Reconnect", unavailable.actionLabel)
        assertEquals("provider unavailable", unavailable.operationalNotice)
        assertNull(libraryRecoveryPresentation("ROOT_UNAVAILABLE", "").operationalNotice)
        assertEquals("The Library could not finish opening.", generic.summary)
        assertEquals("Database could not open", generic.reason)
        assertEquals("Try recovery again to continue.", generic.nextStep)
        assertEquals("Retry", generic.actionLabel)
        assertFalse(generic.reconnectsFolder)
        assertEquals("A pending operation still uses the selected Library.", pending.summary)
        assertEquals("Retry", pending.actionLabel)
        assertFalse(pending.reconnectsFolder)
    }

    @Test
    fun `gate geometry follows the accepted responsive widths`() {
        assertEquals(16f, startupGateHorizontalMarginDp(360f), 0f)
        assertEquals(32f, startupGateHorizontalMarginDp(432f), 0f)
        assertEquals(91.5f, startupGateHorizontalMarginDp(599f), 0f)
        assertEquals(16f, recoveryGateHorizontalMarginDp(360f), 0f)
        assertEquals(28f, recoveryGateHorizontalMarginDp(432f), 0f)
        assertEquals(87.5f, recoveryGateHorizontalMarginDp(599f), 0f)
        assertEquals(40f, startupGateTopPaddingDp(800f), 0f)
        assertEquals(82f, startupGateTopPaddingDp(915f), 0f)
        assertEquals(36f, recoveryGateTopPaddingDp(800f), 0f)
        assertEquals(104f, recoveryGateTopPaddingDp(915f), 0f)
    }

}
