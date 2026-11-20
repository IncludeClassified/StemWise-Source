package app.stemwise.demo

import app.stemwise.data.LibraryRootState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopmentLibraryStartupTest {
    @Test fun freshLibraryIsSeededBeforeSessionActivation() = runBlocking {
        val events = mutableListOf<String>()
        var root = LibraryRootState()
        val activated = initializeDefaultDemoLibrary(
            rootState = { root },
            seed = { events += "seed" },
            activate = {
                assertEquals(listOf("seed"), events)
                events += "activate"
                root = root.copy(libraryId = "demo")
            }
        )
        assertTrue(activated)
        assertEquals(listOf("seed", "activate"), events)
        assertEquals("demo", root.libraryId)
    }

    @Test fun selectedLibrariesAndFailedRecoveryNeverSeedOrActivateDemos() = runBlocking {
        listOf(
            LibraryRootState(libraryId = "owner-library"),
            LibraryRootState(libraryId = "owner-library", errorCode = "ROOT_PERMISSION_LOST"),
            LibraryRootState(errorCode = "ROOT_UNAVAILABLE"),
            LibraryRootState(errorCode = "ROOT_CHECKING")
        ).forEach { existing ->
            var root = existing
            val activated = initializeDefaultDemoLibrary(
                rootState = { root },
                seed = { error("Existing Library must not seed demos") },
                activate = { root = LibraryRootState(libraryId = "demo") }
            )
            assertFalse(activated)
            assertSame(existing, root)
        }
    }

    @Test fun ownerSelectionDuringSeedingIsPreserved() = runBlocking {
        var root = LibraryRootState()
        val selected = root.copy(libraryId = "owner-library")
        val activated = initializeDefaultDemoLibrary(
            rootState = { root },
            seed = { root = selected },
            activate = { error("Owner selection must not be replaced") }
        )
        assertFalse(activated)
        assertSame(selected, root)
    }

    @Test fun failedSeedingCannotPublishADemoRoot() = runBlocking {
        val initial = LibraryRootState()
        var root = initial
        val failure = IllegalStateException("Fixture copy failed")
        val result = runCatching {
            initializeDefaultDemoLibrary(
                rootState = { root },
                seed = { throw failure },
                activate = { root = LibraryRootState(libraryId = "demo") }
            )
        }
        assertSame(failure, result.exceptionOrNull())
        assertSame(initial, root)
    }

    @Test fun sessionFailureRemainsFailureAndCanBeRetried() = runBlocking {
        val root = LibraryRootState()
        val failure = IllegalStateException("Root validation failed")
        val events = mutableListOf<String>()
        val result = runCatching {
            initializeDefaultDemoLibrary(
                rootState = { root },
                seed = { events += "seed" },
                activate = { throw failure }
            )
        }
        assertSame(failure, result.exceptionOrNull())
        initializeDefaultDemoLibrary(
            rootState = { root },
            seed = { events += "retry seed" },
            activate = { events += "activate" }
        )
        assertEquals(listOf("seed", "retry seed", "activate"), events)
        assertTrue(root.libraryId == null)
    }
}
