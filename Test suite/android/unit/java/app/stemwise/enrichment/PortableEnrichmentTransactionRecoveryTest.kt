package app.stemwise.enrichment

import app.stemwise.data.SelectedLibraryContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableEnrichmentTransactionRecoveryTest {
    @Test
    fun rollbackReplayNeedsOnlyTheVerifiedRestoredPairAndNeverTreatsUnreadableAsAbsent() {
        fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val json = "old-json".toByteArray()
        val cover = "old-cover".toByteArray()
        // Repeated re-entry after any subset of recovery files has already been removed.
        repeat(5) {
            requireEnrichmentRecoveryState(EnrichmentInstallPhase.ROLLED_BACK)
            requireRolledBackEnrichmentPair(true, sha256(json), sha256(cover), json, cover, true, true)
            requireRolledBackEnrichmentPair(false, "", "", null, null, false, false)
        }
        assertThrows(IllegalStateException::class.java) { requireEnrichmentRecoveryState("UNKNOWN_PHASE") }
        assertThrows(IllegalStateException::class.java) {
            requireRolledBackEnrichmentPair(true, sha256(json), sha256(cover), "new".toByteArray(), cover, true, true)
        }
        assertThrows(IllegalStateException::class.java) {
            requireRolledBackEnrichmentPair(false, "", "", null, null, true, false)
        }
        assertThrows(IllegalStateException::class.java) {
            requireRolledBackEnrichmentPair(true, sha256(json), sha256(cover), null, cover, true, true)
        }
    }

    @Test
    fun interruptedMarkerCommitRecoversOnlyOneExactGeneration() {
        val transaction = transaction()
        val bytes = "current".toByteArray()
        val parse = { candidate: ByteArray ->
            when (candidate.toString(Charsets.UTF_8)) {
                "current" -> transaction
                "ambiguous" -> transaction(
                    transactionId = "00000000-0000-4000-8000-000000000003"
                )
                else -> error("RECOVERY_PENDING")
            }
        }

        assertNull(
            selectPortableEnrichmentTransactionCopies(
                mapOf(SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP to bytes),
                parse
            )
        )
        assertEquals(
            transaction,
            selectPortableEnrichmentTransactionCopies(
                mapOf(
                    SelectedLibraryContract.ENRICHMENT_TRANSACTION to bytes,
                    SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP to bytes
                ),
                parse
            )
        )

        assertThrows(IllegalStateException::class.java) {
            selectPortableEnrichmentTransactionCopies(
                mapOf(
                    SelectedLibraryContract.ENRICHMENT_TRANSACTION to bytes,
                    SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP to
                        "ambiguous".toByteArray()
                ),
                parse
            )
        }
    }

    @Test
    fun futureTemporaryMarkerIsPreservedInsteadOfDeletedBehindCurrentCanonical() {
        val current = "current".toByteArray()
        val future = "future-with-unknown-fields".toByteArray()
        val before = future.copyOf()

        assertThrows(IllegalStateException::class.java) {
            selectPortableEnrichmentTransactionCopies(
                mapOf(
                    SelectedLibraryContract.ENRICHMENT_TRANSACTION to current,
                    SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP to future
                ),
                parse = { candidate ->
                    candidate.toString(Charsets.UTF_8).also { check(it == "current") }
                    transaction()
                }
            )
        }
        assertArrayEquals(before, future)
    }

    private fun transaction(
        transactionId: String = "00000000-0000-4000-8000-000000000002"
    ) = PortableEnrichmentTransaction(
        libraryId = "00000000-0000-4000-8000-000000000001",
        videoId = "abc123DEF_-",
        transactionId = transactionId,
        hadOldPair = false,
        oldJsonSha256 = "",
        oldJsonBytes = 0L,
        oldCoverSha256 = "",
        oldCoverBytes = 0L,
        newJsonSha256 = "a".repeat(64),
        newJsonBytes = 1L,
        newCoverSha256 = "b".repeat(64),
        newCoverBytes = 1L
    )

}
