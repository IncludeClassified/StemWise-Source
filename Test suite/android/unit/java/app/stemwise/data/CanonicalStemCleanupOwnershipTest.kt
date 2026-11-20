package app.stemwise.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalStemCleanupOwnershipTest {
    private val owner = "97fbfc2d-8d7c-4a75-8138-acfd13d16fe2"
    private val uri = "content://library/document/song%2Fbass.opus"
    private val documentId = "song/bass.opus"
    private val hash = "a".repeat(64)
    private val measured = ProviderDocumentIdentity(hash, 123L)
    private val claim = OwnedStemCleanupClaim(
        owner,
        "bass.opus",
        uri,
        documentId,
        hash,
        123L
    )

    @Test
    fun exactCurrentOperationReceiptAuthorizesDeletion() {
        assertTrue(
            ownedStemCleanupDeletionAllowed(
                owner,
                "bass.opus",
                uri,
                documentId,
                measured,
                claim
            )
        )
    }

    @Test
    fun recognizedPreExistingStemWithoutReceiptIsPreserved() {
        assertFalse(
            ownedStemCleanupDeletionAllowed(
                owner,
                "bass.opus",
                uri,
                documentId,
                measured,
                null
            )
        )
    }

    @Test
    fun anotherJobReceiptCannotAuthorizeDeletion() {
        assertFalse(
            ownedStemCleanupDeletionAllowed(
                "50cf0061-5887-4745-aa9b-9973edc2e4b8",
                "bass.opus",
                uri,
                documentId,
                measured,
                claim
            )
        )
    }

    @Test
    fun changedOrReplacedOwnedStemIsPreserved() {
        assertFalse(
            ownedStemCleanupDeletionAllowed(
                owner,
                "bass.opus",
                uri,
                documentId,
                ProviderDocumentIdentity("b".repeat(64), 123L),
                claim
            )
        )
        assertFalse(
            ownedStemCleanupDeletionAllowed(
                owner,
                "bass.opus",
                "$uri-replaced",
                "$documentId-replaced",
                measured,
                claim
            )
        )
    }

}
