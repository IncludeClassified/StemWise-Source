package app.stemwise.data

import app.stemwise.integration.remainingPublicationOwnerId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePartPublicationRecoveryTest {
    @Test
    fun nativeRemainingCannotRecoverOrPruneAReceiptFromThePreviousProducer() {
        val owner = remainingPublicationOwnerId("job-one")
        val old = CLAIM.copy(fileName = "remaining.wv")
        assertFalse(ownedSourcePartRecoveryAllowed(owner, "remaining.wv", URI, "part-document", IDENTITY, old))
        assertFalse(ownedUnverifiedSourcePartCleanupAllowed(owner, "remaining.wv", URI, "part-document",
            "TARGET_ACQUIRED", true, IDENTITY.copy(byteLength = 400L), old))
        assertTrue(ownedSourcePartRecoveryAllowed(owner, "remaining.wv", URI, "part-document", IDENTITY,
            old.copy(operationOwnerId = owner)))
    }

    @Test
    fun exactOwnedPublishedBytesCanRecoverBeforePortableReceipt() {
        assertTrue(allowed(CLAIM, IDENTITY))
    }

    @Test
    fun alteredBytesWrongOwnerOrReplacedProviderDocumentCannotBecomeAReceipt() {
        assertFalse(allowed(null, IDENTITY))
        assertFalse(allowed(CLAIM, null))
        assertFalse(allowed(CLAIM.copy(operationOwnerId = "other-job"), IDENTITY))
        assertFalse(allowed(CLAIM.copy(fileName = "part-2.flac"), IDENTITY))
        assertFalse(allowed(CLAIM.copy(targetUri = "content://other/root/part-1.flac"), IDENTITY))
        assertFalse(allowed(CLAIM.copy(targetDocumentId = "replaced"), IDENTITY))
        assertFalse(allowed(CLAIM, IDENTITY.copy(sha256 = "f".repeat(64))))
        assertFalse(allowed(CLAIM, IDENTITY.copy(byteLength = IDENTITY.byteLength - 1)))
        assertFalse(ownedSourcePartRecoveryAllowed("job-one", "original.opus", URI, "part-document", IDENTITY,
            CLAIM.copy(fileName = "original.opus")))
    }

    @Test
    fun onlyExactUnverifiedTornOrEmptyOwnedCopyCanBePrunedForRedownload() {
        val torn = IDENTITY.copy(sha256 = "b".repeat(64), byteLength = 400L)
        assertTrue(prunable("TARGET_ACQUIRED", true, CLAIM, torn))
        assertTrue(prunable("PRUNING_PARTIAL_SOURCE", true, CLAIM, torn))
        assertTrue(prunable("TARGET_ACQUIRED", true, CLAIM, torn.copy(byteLength = 0L)))
        assertFalse(prunable("VERIFIED", true, CLAIM, torn))
        assertFalse(prunable("COMPLETE", true, CLAIM, torn))
        assertFalse(prunable("COPYING", true, CLAIM, torn))
        assertFalse(prunable("TARGET_ACQUIRED", false, CLAIM, torn))
        assertFalse(prunable("TARGET_ACQUIRED", true, null, torn))
        assertFalse(prunable("TARGET_ACQUIRED", true, CLAIM.copy(operationOwnerId = "another-job"), torn))
        assertFalse(prunable("TARGET_ACQUIRED", true, CLAIM.copy(targetDocumentId = "replaced"), torn))
        assertFalse(prunable("TARGET_ACQUIRED", true, CLAIM.copy(targetUri = "content://foreign"), torn))
        // A full-length altered asset is not a torn copy, and must never be removed automatically.
        assertFalse(prunable("TARGET_ACQUIRED", true, CLAIM, torn.copy(byteLength = IDENTITY.byteLength)))
        assertFalse(prunable("TARGET_ACQUIRED", true, CLAIM, torn.copy(byteLength = IDENTITY.byteLength + 1L)))
        assertFalse(ownedUnverifiedSourcePartCleanupAllowed("job-one", "original.opus", URI,
            "part-document", "TARGET_ACQUIRED", true, torn, CLAIM.copy(fileName = "original.opus")))
    }

    private fun prunable(state: String, created: Boolean, claim: OwnedStemCleanupClaim?,
        measured: ProviderDocumentIdentity): Boolean = ownedUnverifiedSourcePartCleanupAllowed(
        "job-one", "part-1.flac", URI, "part-document", state, created, measured, claim)

    private fun allowed(claim: OwnedStemCleanupClaim?, identity: ProviderDocumentIdentity?): Boolean =
        ownedSourcePartRecoveryAllowed("job-one", "part-1.flac", URI, "part-document", identity, claim)

    companion object {
        private const val URI = "content://provider/root/song/part-1.flac"
        private val IDENTITY = ProviderDocumentIdentity("a".repeat(64), 1_000L)
        private val CLAIM = OwnedStemCleanupClaim("job-one", "part-1.flac", URI, "part-document", IDENTITY.sha256, IDENTITY.byteLength)
    }
}
