package app.stemwise.testprovider

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QualificationSeedPublisherTest {
    private val project = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "AGENTS.md").isFile && File(it, "Test suite").isDirectory }
    @get:Rule val temporary = TemporaryFolder(File(project, "Test suite/work").apply { check(isDirectory || mkdirs()) })

    @Test fun interruptedSeedIsUnpublishedAndRestartsWithoutKeepingPartialBytes() {
        val base = File(temporary.root, "fixture")
        val seal = File(temporary.root, "sealed")
        assertThrows(InterruptedSeed::class.java) {
            QualificationSeedPublisher.ensurePublished(base, seal) { staging ->
                File(staging, "partial").writeText("unfinished", Charsets.UTF_8)
                throw InterruptedSeed()
            }
        }
        assertFalse(base.exists())
        assertFalse(seal.exists())
        var attempts = 0
        QualificationSeedPublisher.ensurePublished(base, seal) { staging ->
            attempts++
            assertFalse(File(staging, "partial").exists())
            File(staging, "original").writeText("complete", Charsets.UTF_8)
        }
        assertEquals(1, attempts)
        assertEquals("complete", File(base, "original").readText(Charsets.UTF_8))
        assertTrue(seal.isFile)
        QualificationSeedPublisher.ensurePublished(base, seal) { error("Published fixture was reseeded") }
        assertEquals("complete", File(base, "original").readText(Charsets.UTF_8))
    }

    @Test fun interruptionAfterPublicationRepairsOnlyTheSealAndPreservesOwnerEdits() {
        val base = File(temporary.root, "fixture")
        val seal = File(temporary.root, "sealed")
        QualificationSeedPublisher.ensurePublished(base, seal) { staging ->
            File(staging, "original").writeText("initial", Charsets.UTF_8)
        }
        File(base, "original").writeText("owner edit", Charsets.UTF_8)
        assertTrue(seal.delete())
        QualificationSeedPublisher.ensurePublished(base, seal) { error("Already published fixture was replaced") }
        assertEquals("owner edit", File(base, "original").readText(Charsets.UTF_8))
        assertTrue(seal.isFile)
    }

    @Test fun unrecognizedExistingTreesAndMissingSealedTreesArePreservedAndRejected() {
        val base = File(temporary.root, "fixture").apply { mkdir() }
        val owner = File(base, "owner").apply { writeText("keep", Charsets.UTF_8) }
        val seal = File(temporary.root, "sealed")
        assertThrows(IOException::class.java) {
            QualificationSeedPublisher.ensurePublished(base, seal) { error("Unrecognized tree was replaced") }
        }
        assertEquals("keep", owner.readText(Charsets.UTF_8))
        seal.writeText("SEALED_V3\n", Charsets.US_ASCII)
        val absent = File(temporary.root, "missing")
        assertThrows(IOException::class.java) {
            QualificationSeedPublisher.ensurePublished(absent, seal) { error("Missing published tree was replaced") }
        }
        assertFalse(absent.exists())
    }

    private class InterruptedSeed : Error("simulated process interruption")
}
