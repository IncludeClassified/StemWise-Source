package app.stemwise.data

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPinnedPublicationPolicyTest {
    private data class Identity(val libraryId: String, val treeUri: String, val epoch: Long)

    @Test
    fun `same library id at another tree or epoch is never the originating root`() {
        val expected = Identity(LIBRARY_ID, "content://provider/tree/root-a", 7L)
        assertTrue(matches(expected, expected))
        assertFalse(matches(expected.copy(treeUri = "content://provider/tree/root-b"), expected))
        assertFalse(matches(expected.copy(epoch = 8L), expected))
    }

    @Test
    fun `shared lane model serializes root changes and rejects exact stale authority`() =
        runBlocking {
            withTimeout(5_000L) {
                val phase = "shared preparation lane model"
                val coordinator = SongWorkCoordinator()
                val expected = Identity(LIBRARY_ID, "content://provider/tree/root-a", 1L)
                val replacement = expected.copy(treeUri = "content://provider/tree/root-b", epoch = 2L)
                val current = AtomicReference(expected)
                val publicationPaused = CompletableDeferred<Unit>()
                val resumePublication = CompletableDeferred<Unit>()
                val switchAdmitted = CompletableDeferred<Unit>()

                val publication = async(Dispatchers.Default) {
                    coordinator.preparationLane.withLock {
                        publicationPaused.complete(Unit)
                        resumePublication.await()
                        assertTrue(phase, matches(current.get(), expected))
                    }
                }
                publicationPaused.await()
                val rootSwitch = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    switchAdmitted.complete(Unit)
                    coordinator.preparationLane.withLock { current.set(replacement) }
                }
                try {
                    switchAdmitted.await()
                    assertFalse("$phase switched while publication was paused", rootSwitch.isCompleted)
                } finally { resumePublication.complete(Unit) }
                publication.await()
                rootSwitch.await()

                val stale = runCatching {
                    requireRootAuthority(
                        currentReady = true,
                        currentLibraryId = current.get().libraryId,
                        currentTreeUri = current.get().treeUri,
                        currentEpoch = current.get().epoch,
                        expectedLibraryId = expected.libraryId,
                        expectedTreeUri = expected.treeUri,
                        expectedEpoch = expected.epoch
                    )
                }.exceptionOrNull()
                assertEquals("LIBRARY_CHANGED", stale?.message)
            }
        }

    private fun matches(current: Identity, expected: Identity): Boolean = rootAuthorityMatches(
        currentReady = true,
        currentLibraryId = current.libraryId,
        currentTreeUri = current.treeUri,
        currentEpoch = current.epoch,
        expectedLibraryId = expected.libraryId,
        expectedTreeUri = expected.treeUri,
        expectedEpoch = expected.epoch
    )

    private companion object {
        const val LIBRARY_ID = "00000000-0000-4000-8000-000000000001"
    }
}
