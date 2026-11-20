package app.stemwise.integration

import app.stemwise.data.ProcessingJobEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SeparationQueueHandoffTest {
    @Test fun noHandoffEscapesFailedPersistenceOrMissingFailedOrChangedReadback() = runBlocking {
        val expected = job()
        val changes = listOf<(ProcessingJobEntity) -> ProcessingJobEntity>(
            { it.copy(id = "other") }, { it.copy(songId = "other") },
            { it.copy(sourceVersionId = "other") }, { it.copy(kind = "other") },
            { it.copy(provider = "other") }, { it.copy(modelId = "other") },
            { it.copy(state = "COMPLETE") }, { it.copy(remoteJobId = "other") },
            { it.copy(attempts = 1) }, { it.copy(nextAttemptAtEpochMs = 1) },
            { it.copy(lastErrorCode = "other") }, { it.copy(lastErrorMessage = "other") },
            { it.copy(updatedAtEpochMs = 2) }, { it.copy(createdAtEpochMs = 2) },
            { it.copy(libraryId = "other") }
        )
        for (scenario in listOf("write", "missing", "read") + changes.indices.map { "changed-$it" }) {
            val calls = mutableListOf<String>()
            val unrelated = expected.copy(id = "unrelated", songId = "another-song")
            val rows = mutableMapOf(unrelated.id to unrelated)
            val writeFailure = IllegalStateException("write blocked")
            val readFailure = IllegalStateException("read blocked")
            val failure = runCatching {
                persistSeparationQueueHandoff(expected, "exact-hash", persist = {
                    calls += "persist"
                    if (scenario == "write") throw writeFailure
                    rows[it.id] = it
                }, reopen = { id ->
                    calls += "read:$id"
                    when {
                        scenario == "missing" -> null
                        scenario == "read" -> throw readFailure
                        else -> changes[scenario.substringAfter('-').toInt()](rows.getValue(id))
                    }
                })
            }.exceptionOrNull()
            assertNotNull("A worker handoff escaped $scenario", failure)
            assertEquals(unrelated, rows[unrelated.id])
            assertEquals(if (scenario == "write") listOf("persist") else listOf("persist", "read:${expected.id}"), calls)
            if (scenario == "write") {
                assertSame(writeFailure, failure)
                assertFalse(rows.containsKey(expected.id))
            } else {
                assertEquals(expected, rows[expected.id])
                if (scenario == "read") assertSame(readFailure, failure)
                else assertEquals(if (scenario == "missing") "SEPARATION_HANDOFF_PERSISTENCE_FAILED" else "SEPARATION_JOB_MISMATCH", failure?.message)
            }
        }
    }

    @Test fun exactPersistedReadbackAloneDeterminesWorkerHandoff() = runBlocking {
        for (state in listOf("CREATED", "PROCESSING", "COMPLETE", "FAILED", "SUBMISSION_UNKNOWN")) {
            val expected = job().copy(state = state)
            var durable: ProcessingJobEntity? = null
            var reads = 0
            val handoff = persistSeparationQueueHandoff(expected, "exact-hash", persist = {
                assertNull(durable)
                durable = it.copy()
            }, reopen = {
                reads++
                assertEquals(expected.id, it)
                assertEquals(expected, durable)
                durable
            })
            assertEquals(1, reads)
            assertEquals(SeparationQueueHandoff(expected.id, "library", "song", "source", "exact-hash", state,
                state == "CREATED" || state == "PROCESSING"), handoff)
            assertEquals(expected, durable)
        }
    }

    private fun job() = ProcessingJobEntity("job", "song", "source", "SOURCE_SELECTION", "MVSEP",
        "source-plan:exact", "CREATED", null, 0, null, null, null,
        1, 1, "library")
}
