package app.stemwise.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryRootRuntimePolicyTest {
    @Test
    fun `canonical scan aborts for provider access loss but isolates package corruption`() {
        assertEquals(
            "ROOT_PERMISSION_LOST",
            canonicalProjectionRootAccessFailure(SecurityException("revoked"))?.code
        )
        assertEquals(
            "ROOT_PERMISSION_LOST",
            canonicalProjectionRootAccessFailure(
                IOException("provider query failed", SecurityException("revoked"))
            )?.code
        )
        assertEquals(
            "ROOT_LAYOUT_UNSUPPORTED",
            canonicalProjectionRootAccessFailure(
                LibraryRootException("ROOT_LAYOUT_UNSUPPORTED", "unsafe root")
            )?.code
        )
        assertNull(
            canonicalProjectionRootAccessFailure(
                IllegalStateException("MANIFEST_JSON_INVALID")
            )
        )
        assertNull(
            canonicalProjectionRootAccessFailure(
                java.io.FileNotFoundException("one package asset was deleted")
            )
        )
        assertNull(
            canonicalProjectionRootAccessFailure(
                LibraryRootException("PACKAGE_DIRECTORY_UNAVAILABLE", "one package disappeared")
            )
        )
    }

    @Test
    fun securityFailureAnywhereInCauseChainMeansPermissionWasLost() {
        val failure = IOException("provider query failed", SecurityException("permission revoked"))

        assertEquals("ROOT_PERMISSION_LOST", runtimeLibraryRootErrorCode(failure))
    }

    @Test
    fun knownUnavailableCodeIsPreserved() {
        val failure = LibraryRootException("ROOT_UNAVAILABLE", "provider unavailable")

        assertEquals("ROOT_UNAVAILABLE", runtimeLibraryRootErrorCode(failure))
    }

    @Test
    fun typedUnsupportedLayoutSurvivesWhileTheOldRawMutationDoesNot() {
        assertEquals(
            "ROOT_LAYOUT_UNSUPPORTED",
            runtimeLibraryRootErrorCode(
                LibraryRootException("ROOT_LAYOUT_UNSUPPORTED", "invalid metadata")
            )
        )
        assertEquals(
            "ROOT_UNAVAILABLE",
            runtimeLibraryRootErrorCode(IllegalStateException("ROOT_LAYOUT_UNSUPPORTED"))
        )
    }

    @Test
    fun unknownProviderFailureFailsClosedAsUnavailable() {
        val failure = IOException("provider stopped responding")

        assertEquals("ROOT_UNAVAILABLE", runtimeLibraryRootErrorCode(failure))
    }

    @Test
    fun persistedPermissionReconciliationKeepsOnlyTheDurableRootAndRetriesFailures() {
        val active = "content://provider/tree/active"
        val stale = "content://provider/tree/stale"
        val attempts = mutableListOf<String>()
        var failRelease = true

        assertEquals(
            listOf(stale),
            releaseObsoletePersistedRootPermissions(
                activeTreeUri = active,
                persistedTreeUris = listOf(active, stale, stale)
            ) { treeUri ->
                attempts += treeUri
                if (failRelease) Result.failure(IOException("release failed"))
                else Result.success(Unit)
            }
        )
        failRelease = false
        assertEquals(
            emptyList<String>(),
            releaseObsoletePersistedRootPermissions(
                activeTreeUri = active,
                persistedTreeUris = listOf(active, stale)
            ) { treeUri ->
                attempts += treeUri
                Result.success(Unit)
            }
        )
        assertEquals(listOf(stale, stale), attempts)

        val noActiveRootAttempts = mutableListOf<String>()
        assertEquals(
            emptyList<String>(),
            releaseObsoletePersistedRootPermissions(
                activeTreeUri = null,
                persistedTreeUris = listOf(active, stale)
            ) { treeUri ->
                noActiveRootAttempts += treeUri
                Result.success(Unit)
            }
        )
        assertEquals(listOf(active, stale), noActiveRootAttempts)
    }

    @Test
    fun rehearsalActivationGuardRejectsEveryProductionBypassShape() {
        assertEquals(
            "DEVELOPMENT_PACKAGE_REJECTED",
            developmentRootActivationFailure("app.stemwise", "app.stemwise.golden.qa.documents", false, true)
        )
        assertEquals(
            "DEVELOPMENT_PROVIDER_REJECTED",
            developmentRootActivationFailure(
                "app.stemwise.development", "untrusted.documents", false, true
            )
        )
        assertEquals(
            "DEVELOPMENT_DUPLICATE_ACTIVATION",
            developmentRootActivationFailure(
                "app.stemwise.development",
                "app.stemwise.development.golden.qa.documents",
                true,
                true
            )
        )
        assertEquals(
            "DEVELOPMENT_PERMISSION_MISSING",
            developmentRootActivationFailure(
                "app.stemwise.development",
                "app.stemwise.development.golden.qa.documents",
                false,
                false
            )
        )
        assertNull(
            developmentRootActivationFailure(
                "app.stemwise.development",
                "app.stemwise.development.golden.qa.documents",
                false,
                true
            )
        )
    }
}
