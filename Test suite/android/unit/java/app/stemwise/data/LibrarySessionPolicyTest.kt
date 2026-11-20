package app.stemwise.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySessionPolicyTest {
    @Test
    fun `first run adopts an empty root without replacement confirmation`() {
        assertFalse(
            requiresExplicitLibrarySelectionConfirmation(
                rememberedLibraryId = null,
                candidateLibraryId = "new-library",
                candidateRequiresNonEmptyFolderConfirmation = false
            )
        )
    }

    @Test
    fun `first run still confirms a non-empty folder`() {
        assertTrue(
            requiresExplicitLibrarySelectionConfirmation(
                rememberedLibraryId = null,
                candidateLibraryId = "new-library",
                candidateRequiresNonEmptyFolderConfirmation = true
            )
        )
    }

    @Test
    fun `same identity reconnect proceeds but a different remembered identity confirms`() {
        assertFalse(
            requiresExplicitLibrarySelectionConfirmation(
                rememberedLibraryId = "remembered-library",
                candidateLibraryId = "remembered-library",
                candidateRequiresNonEmptyFolderConfirmation = false
            )
        )
        assertTrue(
            requiresExplicitLibrarySelectionConfirmation(
                rememberedLibraryId = "remembered-library",
                candidateLibraryId = "different-library",
                candidateRequiresNonEmptyFolderConfirmation = false
            )
        )
        assertTrue(selectsDifferentLibraryIdentity("remembered-library", "different-library"))
    }

    @Test
    fun `same portable library id at another exact uri is still a root change`() {
        assertFalse(
            selectedRootIdentityChanges(
                null,
                null,
                "content://provider/tree/root-a",
                "library-a"
            )
        )
        assertFalse(
            selectedRootIdentityChanges(
                "content://provider/tree/root-a",
                "library-a",
                "content://provider/tree/root-a",
                "library-a"
            )
        )
        assertTrue(
            selectedRootIdentityChanges(
                "content://provider/tree/root-a",
                "library-a",
                "content://provider/tree/root-b",
                "library-a"
            )
        )
        assertTrue(
            selectedRootIdentityChanges(
                "content://provider/tree/root-a",
                "library-a",
                "content://provider/tree/root-a",
                "library-b"
            )
        )
    }

    @Test
    fun `persisted SAF reconnect requires both read and write grant`() {
        assertTrue(persistedSafGrantIsUsable(read = true, write = true))
        assertFalse(persistedSafGrantIsUsable(read = true, write = false))
        assertFalse(persistedSafGrantIsUsable(read = false, write = true))
    }

    @Test
    fun `portable library ids reject textual UUID aliases`() {
        val canonical = "abcdef00-0000-4000-8000-000000000001"
        assertTrue(isCanonicalLibraryId(canonical))
        assertFalse(isCanonicalLibraryId("0-0-4-8-1"))
        assertFalse(isCanonicalLibraryId(canonical.uppercase()))
    }

    @Test
    fun `remote separation safety blocks only a genuinely different library`() {
        assertTrue(blocksLibrarySwitchForSeparationSafety(true, true))
        assertFalse(blocksLibrarySwitchForSeparationSafety(false, true))
        assertFalse(blocksLibrarySwitchForSeparationSafety(true, false))
        assertEquals(
            "LIBRARY_SWITCH_REMOTE_SEPARATION_PENDING",
            LIBRARY_SWITCH_REMOTE_SEPARATION_PENDING
        )
    }

    @Test
    fun `only exact tree URI text is a reconnect`() {
        val exact = "content://provider/tree/primary%3ARoot"
        assertTrue(reconnectsExactTreeUri(exact, exact))
        assertFalse(reconnectsExactTreeUri(null, exact))
        assertFalse(
            reconnectsExactTreeUri(
                exact,
                "content://provider/tree/primary%3Aroot"
            )
        )
        assertFalse(
            reconnectsExactTreeUri(
                exact,
                "$exact?alias=true"
            )
        )
    }

    @Test
    fun `commit checkpoint releases only a candidate that never became authority`() {
        val oldUri = "content://provider/tree/old"
        val newUri = "content://provider/tree/new"
        assertTrue(
            shouldReleaseFailedCandidatePermission(
                oldUri,
                oldUri,
                "old-library",
                newUri,
                "new-library"
            )
        )
        assertFalse(
            shouldReleaseFailedCandidatePermission(
                oldUri,
                newUri,
                "new-library",
                newUri,
                "new-library"
            )
        )
        assertFalse(
            shouldReleaseFailedCandidatePermission(
                oldUri,
                oldUri,
                "old-library",
                oldUri,
                "old-library"
            )
        )
    }

    @Test
    fun `failed first root commit preserves original failure without a recovery failure`() {
        val commitFailure = IOException("persist permission failed")
        val result = failedLibrarySwitchResult<Unit>(
            commitFailure = commitFailure,
            recoveryFailure = null
        )

        assertSame(commitFailure, result.exceptionOrNull())
        assertTrue(commitFailure.suppressed.isEmpty())
    }

    @Test
    fun `failed switch keeps original failure when persisted-root recovery also fails`() {
        val commitFailure = IOException("candidate commit failed")
        val restoreFailure = IllegalStateException("old session recovery failed")

        val result = failedLibrarySwitchResult<Unit>(
            commitFailure = commitFailure,
            recoveryFailure = restoreFailure
        )

        assertSame(commitFailure, result.exceptionOrNull())
        assertEquals(listOf(restoreFailure), commitFailure.suppressed.toList())
    }

    @Test
    fun `failed switch also survives a restore that rethrows the commit failure`() {
        val commitFailure = IOException("candidate commit failed")

        val result = failedLibrarySwitchResult<Unit>(
            commitFailure = commitFailure,
            recoveryFailure = commitFailure
        )

        assertSame(commitFailure, result.exceptionOrNull())
        assertTrue(commitFailure.suppressed.isEmpty())
    }

    @Test
    fun `schedule only failure stays outside converged root authority`() {
        var reachedAfterMaintenance = false
        val scheduleFailure = IOException("schedule unavailable")

        val reported = runPlaylistScheduleMaintenance { throw scheduleFailure }
        reachedAfterMaintenance = true

        assertSame(scheduleFailure, reported)
        assertTrue(reachedAfterMaintenance)
    }

}
