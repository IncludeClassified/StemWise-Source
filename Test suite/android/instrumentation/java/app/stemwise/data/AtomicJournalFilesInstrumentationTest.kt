package app.stemwise.data

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicJournalFilesInstrumentationTest {
    @Test fun structuralWaveformAllowanceCannotBeBorrowedByALiteralDottedKey() {
        val literal = JSONObject().put("analysis.waveform.levels.*.data", "A".repeat(8 * 1024 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) {
            app.stemwise.readStrictJsonObject(literal.toString().toByteArray(Charsets.UTF_8), 16L * 1024L * 1024L)
        }
    }

    @Test(timeout = 10_000L) fun longParentKeyDoesNotMultiplyAcrossArrayElements() {
        val key = "k".repeat(1024 * 1024)
        val bytes = ("{\"" + key + "\":[" + "0,".repeat(99_999) + "0]}").toByteArray(Charsets.UTF_8)
        val parsed = app.stemwise.readStrictJsonObject(bytes, 2L * 1024L * 1024L)
        assertEquals(100_000, parsed.getJSONArray(key).length())
        assertEquals(0, parsed.getJSONArray(key).getInt(99_999))
    }

    @Test fun scratchSymlinksCannotDeleteOrInventoryAnotherJobsBytes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "scratch-links-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val job = File(root, "job-a").also { check(it.mkdir()) }
        val other = File(root, "job-b").also { check(it.mkdir()) }
        val retained = File(other, "retained.zip").apply { writeText("accepted bytes", Charsets.UTF_8) }
        val link = File(job, "sibling")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), other.toPath())
            assertThrows(IllegalStateException::class.java) { inventoryPrivateScratch(job, "UNOWNED_LINK") }
            assertThrows(IllegalStateException::class.java) { deleteExactPrivateTree(root, link, "UNOWNED_LINK") }
            assertEquals("accepted bytes", retained.readText(Charsets.UTF_8))
            assertThrows(IllegalStateException::class.java) { deleteExactPrivateTree(root, job, "UNOWNED_LINK") }
            assertEquals("accepted bytes", retained.readText(Charsets.UTF_8))
            assertTrue(other.isDirectory)
        } finally {
            java.nio.file.Files.deleteIfExists(link.toPath())
            check(root.deleteRecursively())
        }
    }

    @Test fun inaccessibleJournalIsNeitherAbsentNorSuccessfullyDeleted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "inaccessible-journal-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val locked = File(root, "locked").also { check(it.mkdir()) }
        val journal = File(locked, "receipt.json").apply { writeText("{\"authority\":\"retained\"}", Charsets.UTF_8) }
        val before = journal.readBytes()
        try {
            android.system.Os.chmod(locked.absolutePath, 0)
            try {
                assertFalse("An inaccessible path must not prove absence", java.nio.file.Files.notExists(journal.toPath()))
                assertNotNull(runCatching { readAtomicJournal(journal, "JOURNAL_READ_UNAVAILABLE") }.exceptionOrNull())
                assertNotNull(runCatching { deleteAtomicJournal(journal, "JOURNAL_CLEANUP_PENDING") }.exceptionOrNull())
            } finally { android.system.Os.chmod(locked.absolutePath, 448) }
            org.junit.Assert.assertArrayEquals(before, journal.readBytes())
            deleteAtomicJournal(journal, "JOURNAL_CLEANUP_PENDING")
            assertNull(readAtomicJournal(journal, "JOURNAL_READ_UNAVAILABLE"))
        } finally { check(root.deleteRecursively()) }
    }
    @Test fun twoHourFiveMillisecondWaveformUsesItsOwnBoundWithoutEnlargingGeneralStrings() {
        val frames = (2L * 60L * 60L * 1000L / 5L).toInt()
        val encoded = "A".repeat(frames * 8)
        fun document(data: String, waveform: Boolean): ByteArray = if (waveform) {
            ("{\"analysis\":{\"waveform\":{\"levels\":[{\"data\":\"" + data + "\"}]}}}").toByteArray()
        } else ("{\"title\":\"" + data + "\"}").toByteArray()
        val parsed = app.stemwise.readStrictJsonObject(document(encoded, true), 16L * 1024L * 1024L)
        assertEquals(11_520_000, parsed.getJSONObject("analysis").getJSONObject("waveform")
            .getJSONArray("levels").getJSONObject(0).getString("data").length)
        assertThrows(IllegalArgumentException::class.java) {
            app.stemwise.readStrictJsonObject(document(encoded + "A", true), 16L * 1024L * 1024L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            app.stemwise.readStrictJsonObject(document("A".repeat(8 * 1024 * 1024 + 1), false), 16L * 1024L * 1024L)
        }
    }

    @Test
    fun importProgressSurvivesTheProductionAtomicJournalParser() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "import-journal-${UUID.randomUUID()}").also { check(it.mkdir()) }
        try {
            for (phase in listOf("staged", "awaiting_confirmation", "copying")) {
                val progress = if (phase == "copying") 3 else 0
                for (complete in listOf(true, false, "true")) {
                    val file = File(root, "session.json")
                    val atomic = android.util.AtomicFile(file)
                    val stream = atomic.startWrite()
                    stream.write(JSONObject().put("phase", phase).put("completed", progress)
                        .put("total", progress).put("stagingComplete", complete).toString().toByteArray())
                    atomic.finishWrite(stream)
                    val restored = checkNotNull(readAtomicJournal(file, "INVALID")).value
                    assertEquals(progress, restored.getInt("completed"))
                    assertEquals(complete == true,
                        app.stemwise.enrichment.enrichmentStagingJournalIsComplete(restored, 3))
                }
            }
        } finally { check(root.deleteRecursively()) }
    }

    @Test
    fun malformedJournalsFailClosedWithoutRewritingAuthority() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "atomic-journal-${UUID.randomUUID()}").also { check(it.mkdir()) }
        try {
            val malformed = listOf("{x:1}", "{'x':1}", "{\"x\":1,\"x\":2}", "{}{}",
                "{/*comment*/\"x\":1}", "{\"x\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}")
                .map { it.toByteArray() } + listOf(byteArrayOf(123, 34, 120, 34, 58, 34, 0xc3.toByte(), 40, 34, 125))
            malformed.forEachIndexed { index, bytes ->
                val file = File(root, "case-$index.json").also { it.writeBytes(bytes) }
                val failure = assertThrows(IllegalStateException::class.java) { readAtomicJournal(file, "JOURNAL_CORRUPT") }
                assertEquals("JOURNAL_CORRUPT", failure.message)
                org.junit.Assert.assertArrayEquals(bytes, file.readBytes())
            }
        } finally { check(root.deleteRecursively()) }
    }

    @Test
    fun atomicJournalBoundsAndCompanionCleanupAreEnforced() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "atomic-journal-${UUID.randomUUID()}")
        check(root.mkdir())
        try {
            val first = File(root, "first.json").apply { writeText("{}") }
            File(root, "first.json.bak").writeText("{}")
            File(root, "first.json.new").writeText("{}")
            assertEquals(
                listOf(first.canonicalFile),
                checkedAtomicJournalFiles(root, "INVALID", maximum = 1)
            )
            requireAtomicJournalAdmission(root, first, "BOUNDED", maximum = 1)
            assertThrows(IllegalStateException::class.java) {
                requireAtomicJournalAdmission(
                    root,
                    File(root, "new-transaction.json"),
                    "BOUNDED",
                    maximum = 1
                )
            }

            File(root, "second.json").writeText("{}")
            assertThrows(IllegalStateException::class.java) {
                requireAtomicJournalAdmission(root, first, "BOUNDED", maximum = 1)
            }
            val bounded = assertThrows(IllegalStateException::class.java) {
                checkedAtomicJournalFiles(
                    root,
                    "INVALID",
                    maximum = 1,
                    boundedErrorCode = "BOUNDED"
                )
            }
            assertEquals("BOUNDED", bounded.message)

            File(root, "second.json").delete()
            val oversized = JSONObject().put("payload", "1234567890").toString()
            File(root, "oversized.json").writeText(oversized)
            val sizeFailure = assertThrows(IllegalStateException::class.java) {
                readAtomicJournal(File(root, "oversized.json"), "TOO_LARGE", 8L)
            }
            assertEquals("TOO_LARGE", sizeFailure.message)
            assertTrue(sizeFailure is AtomicJournalFormatException)

            val orphan = File(root, "orphan.json")
            val orphanPending = File(root, "orphan.json.new").apply { writeText("{}") }
            assertNull(readAtomicJournal(orphan, "RECOVERY_PENDING"))
            assertFalse(orphanPending.exists())
            orphan.writeText("{}")
            assertEquals(orphan.canonicalFile, readAtomicJournal(
                orphan,
                "RECOVERY_PENDING"
            )?.file)

            val cleanup = File(root, "cleanup.json").apply { writeText("{}") }
            File(root, "cleanup.json.bak").writeText("{}")
            File(root, "cleanup.json.new").writeText("{}")
            deleteAtomicJournal(cleanup, "CLEANUP_PENDING")
            assertFalse(cleanup.exists())
            assertFalse(File(root, "cleanup.json.bak").exists())
            assertFalse(File(root, "cleanup.json.new").exists())

            val aggregateRoot = File(root, "aggregate").also { check(it.mkdir()) }
            val firstAggregate = File(aggregateRoot, "first.json").apply {
                writeText("obsolete uncommitted base")
            }
            File(aggregateRoot, "first.json.bak").writeText("{\"v\":1}")
            val secondAggregate = File(aggregateRoot, "second.json").apply {
                writeText("{\"v\":2}")
            }
            val pendingOnly = File(aggregateRoot, "pending.json.new").apply { writeText("{}") }
            assertEquals(
                listOf(firstAggregate.canonicalFile, secondAggregate.canonicalFile),
                checkedAtomicJournalRecords(
                    aggregateRoot,
                    "AGGREGATE_LIMIT",
                    maximumBytes = 7L,
                    maximumAggregateBytes = 14L
                ).map(AtomicJournalRecord::file)
            )
            assertFalse(pendingOnly.exists())

            val overflowPending = File(aggregateRoot, "pending.json.new").apply { writeText("{}") }
            val aggregateFailure = assertThrows(IllegalStateException::class.java) {
                checkedAtomicJournalRecords(
                    aggregateRoot,
                    "AGGREGATE_LIMIT",
                    maximumBytes = 7L,
                    maximumAggregateBytes = 13L
                )
            }
            assertEquals("AGGREGATE_LIMIT", aggregateFailure.message)
            assertFalse(overflowPending.exists())
            assertTrue(firstAggregate.exists())
            assertTrue(secondAggregate.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
