package app.stemwise.data

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AtomicJournalEnvelopeTest {
    @Test
    fun `strict envelope accepts only integral typed identity and exact transaction filename`() {
        val valid = record("transaction-1.json", envelope())
        assertEquals("transaction-1", requireAtomicJournalEnvelope(valid, "INVALID").transactionId)

        listOf(
            envelope().put("schemaVersion", "2"),
            envelope().put("rootEpoch", "7"),
            envelope().put("rootEpoch", 7.0),
            envelope().put("rootEpoch", 0L),
            envelope().put("packageId", "")
        ).forEach { malformed ->
            assertThrows(IllegalStateException::class.java) {
                requireAtomicJournalEnvelope(record("transaction-1.json", malformed), "INVALID")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            requireAtomicJournalEnvelope(
                record("other-transaction.json", envelope()),
                "INVALID"
            )
        }
    }

    private fun record(name: String, value: JSONObject) = AtomicJournalRecord(
        File(System.getProperty("java.io.tmpdir"), name),
        value
    )

    private fun envelope() = JSONObject()
        .put("schemaVersion", 2)
        .put("transactionId", "transaction-1")
        .put("packageId", "AbCdEf_12-3")
        .put("operation", "DELETE_PERMANENT")
        .put("libraryId", "00000000-0000-4000-8000-000000000001")
        .put("treeUri", "content://provider/tree/root")
        .put("rootEpoch", 7L)
        .put("sequence", 1L)
        .put("state", "PREPARED")
        .put("details", JSONObject())
}
