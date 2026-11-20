package app.stemwise.data

import app.stemwise.integration.cleanupDisposableSeparationScratch
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateScratchPolicyTest {
    @Test
    fun preparationRecoveryRetriesAFormerlyRefusedDeleteAndReapsItsEmptyMarker() {
        val root = Files.createTempDirectory("stemwise-preparation-refusal").toFile()
        val payload = File(root, "active-stem.flac").apply { writeText("retry") }
        try {
            assertThrows(IllegalStateException::class.java) {
                deleteExactPrivateTree(root, payload, "PREPARATION_CLEANUP_FAILED") { false }
            }
            assertEquals(2, root.listFiles()!!.size)
            requireOwnedPreparationScratchTree(root)
            cleanupDisposableSeparationScratch(root)
            assertEquals(emptyList<String>(), root.listFiles()!!.map(File::getName))
        } finally {
            check(root.deleteRecursively())
        }
    }

    @Test
    fun stagingRetryReapsItsRefusalMarkerAndPreservesTheCurrentImport() {
        val work = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "Test suite/work") }.first(File::isDirectory)
        val root = Files.createTempDirectory(work.toPath(), "stemwise-staging-refusal-").toFile()
        val staged = File(root, "00000000-0000-4000-8000-000000000001-0.json").apply { writeText("retry") }
        val journal = File(root, "active-import.json").apply { writeText("preserve") }
        try {
            assertThrows(IllegalStateException::class.java) {
                deleteExactPrivateTree(root, staged, "IMPORT_STAGING_CLEANUP_FAILED") { false }
            }
            assertEquals(3, root.listFiles()!!.size)
            cleanupExactPrivateChildren(root, "IMPORT_STAGING_CLEANUP_FAILED", setOf(journal)) {
                it.isFile && app.stemwise.enrichment.isEnrichmentStagingName(it.name)
            }
            assertEquals(listOf("active-import.json"), root.listFiles()!!.map(File::getName))
            assertEquals("preserve", journal.readText())
            cleanupExactPrivateChildren(root, "IMPORT_STAGING_CLEANUP_FAILED") {
                it.isFile && app.stemwise.enrichment.isEnrichmentStagingName(it.name)
            }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { check(root.deleteRecursively()) }
    }

    @Test
    fun aNonemptyFailureMarkerCannotAuthorizeDeletingUnknownOwnerData() {
        val root = Files.createTempDirectory("stemwise-preparation-marker-data").toFile()
        val payload = File(root, "active-stem.flac").apply { writeText("keep") }
        try {
            assertThrows(IllegalStateException::class.java) {
                deleteExactPrivateTree(root, payload, "PREPARATION_CLEANUP_FAILED") { false }
            }
            val marker = root.listFiles()!!.single { it != payload }
            marker.writeText("owner data")
            assertThrows(IllegalStateException::class.java) { requireOwnedPreparationScratchTree(root) }
            assertThrows(IllegalStateException::class.java) { cleanupDisposableSeparationScratch(root) }
            assertEquals("keep", payload.readText())
            assertEquals("owner data", marker.readText())
        } finally {
            check(root.deleteRecursively())
        }
    }

    @Test
    fun knownTreeConvergesToZeroAndRepeatedRecoveryIsIdempotent() {
        val root = Files.createTempDirectory("stemwise-private-scratch").toFile()
        try {
            val transaction = File(root, "transaction").apply { check(mkdir()) }
            File(transaction, "payload.bin").writeBytes(byteArrayOf(1, 2, 3, 4))

            val before = cleanupExactPrivateChildren(root, "SCRATCH_FAILED") {
                it.name == "transaction"
            }
            assertEquals(2, before.entries)
            assertEquals(4L, before.payloadBytes)
            assertEquals(PrivateScratchInventory(0, 0L), inventoryPrivateScratch(root, "SCRATCH_FAILED"))
            assertEquals(
                PrivateScratchInventory(0, 0L),
                cleanupExactPrivateChildren(root, "SCRATCH_FAILED") { false }
            )
        } finally {
            check(root.delete())
        }
    }

    @Test
    fun unknownSiblingBlocksTheWholePassWithoutDeletingEvidence() {
        val root = Files.createTempDirectory("stemwise-private-scratch-unknown").toFile()
        try {
            val known = File(root, "transaction").apply { check(mkdir()) }
            val unknown = File(root, "owner-note.txt").apply { writeText("preserve") }
            assertEquals(
                "SCRATCH_FAILED_UNKNOWN_ENTRY",
                assertThrows(IllegalStateException::class.java) {
                    cleanupExactPrivateChildren(root, "SCRATCH_FAILED") {
                        it.name == "transaction"
                    }
                }.message
            )
            assertTrue(known.isDirectory)
            assertTrue(unknown.isFile)
        } finally {
            check(root.deleteRecursively())
        }
    }

    @Test
    fun hashNamedPayloadIsNotACleanupMarkerAndStillRequiresOwnership() {
        val root = Files.createTempDirectory("stemwise-private-scratch-hash").toFile()
        val payload = File(root, "a".repeat(64)).apply { writeText("preserve") }
        try {
            assertTrue(!isWorkingMediaCleanupRefusalMarker(payload))
            assertEquals(
                "SCRATCH_FAILED_UNKNOWN_ENTRY",
                assertThrows(IllegalStateException::class.java) {
                    cleanupExactPrivateChildren(root, "SCRATCH_FAILED") { false }
                }.message
            )
            assertEquals("preserve", payload.readText())
        } finally {
            check(root.deleteRecursively())
        }
    }

    @Test
    fun enumerationNullIsNeverReportedAsEmpty() {
        val refusing = object : File("unused-refusing-enumeration") {
            override fun exists(): Boolean = true
            override fun isDirectory(): Boolean = true
            override fun listFiles(): Array<File>? = null
        }
        assertEquals(
            "SCRATCH_ENUMERATION_FAILED",
            assertThrows(IllegalStateException::class.java) {
                checkedPrivateChildren(refusing, "SCRATCH_ENUMERATION_FAILED")
            }.message
        )
    }

    @Test
    fun deleteRefusalRemainsVisibleAndRetryable() {
        val root = Files.createTempDirectory("stemwise-private-scratch-refusal").toFile()
        val payload = File(root, "payload.bin").apply { writeText("retry") }
        try {
            assertEquals(
                "SCRATCH_DELETE_FAILED",
                assertThrows(IllegalStateException::class.java) {
                    deleteExactPrivateTree(root, payload, "SCRATCH_DELETE_FAILED") { false }
                }.message
            )
            assertTrue(payload.isFile)
            deleteExactPrivateTree(root, payload, "SCRATCH_DELETE_FAILED")
            assertFalse(payload.exists())
        } finally {
            check(!root.exists() || root.deleteRecursively())
        }
    }

    @Test
    fun cleanupRefusalIsSuppressedBehindTheOriginalProductFailure() {
        val product = IllegalStateException("PRODUCT_FAILED")
        val cleanup = IllegalStateException("CLEANUP_FAILED")
        val caught = assertThrows(IllegalStateException::class.java) {
            withCheckedPrivateCleanup(
                cleanup = { throw cleanup },
                block = { throw product }
            )
        }
        assertSame(product, caught)
        assertEquals(listOf(cleanup), caught.suppressed.toList())
    }
}
