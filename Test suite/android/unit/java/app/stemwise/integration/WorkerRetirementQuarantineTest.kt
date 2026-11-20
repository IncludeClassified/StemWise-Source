package app.stemwise.integration

import app.stemwise.playback.engine.WorkerRetirementException

import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class WorkerRetirementQuarantineTest {
    @Test fun failedCanonicalReadsPreserveTheFatalAndWithholdUnprovableIdentity() {
        val root = File("build/quarantine-canonical-${UUID.randomUUID()}")
        val canonicalError = java.io.IOException("canonical identity unavailable")
        val uncertain = object : File(root, "input.pcm") {
            override fun getCanonicalFile(): File = throw canonicalError
        }
        val failure = WorkerRetirementException("ORIGINAL_PCM_CANCELLATION_TIMED_OUT")
        val quarantine = WorkerResourceQuarantine()
        quarantine.retain(failure, files = listOf(uncertain))
        assertTrue(failure.suppressed.any { it === canonicalError })
        assertSame(failure, quarantine.failureFor(File(root, "input.pcm")))
        // Absolute spelling cannot prove that a path is unrelated to an unresolved canonical alias.
        assertSame(failure, quarantine.failureFor(File(root.parentFile, "other.pcm")))
        assertSame(failure, quarantine.failureFor(uncertain))

        val known = WorkerResourceQuarantine()
        known.retain(failure, files = listOf(File(root, "input.pcm")))
        assertSame(failure, known.failureFor(uncertain))
        assertSame(failure, runCatching { known.requireReleased(uncertain) }.exceptionOrNull())
    }

    @Test fun realScratchCleanupPreservesQuarantinedBytesAndAllowsUnrelatedCleanup() {
        val repository = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "Test suite").isDirectory }
        val work = File(repository, "Test suite/work").apply { check(isDirectory || mkdirs()) }
        val directory = java.nio.file.Files.createTempDirectory(work.toPath(), "decoder-retirement-").toFile()
        val input = File(directory, "input.pcm").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val unrelated = File(directory, "unrelated").apply { check(mkdir()) }
        File(unrelated, "payload").writeText("owned", Charsets.UTF_8)
        val failure = WorkerRetirementException("ORIGINAL_PCM_CANCELLATION_TIMED_OUT")
        WorkerRetirementQuarantine.retain(failure, files = listOf(input))
        try {
            assertSame(failure, runCatching {
                app.stemwise.data.cleanupExactPrivateChildren(directory, "CLEANUP_FAILED") { true }
            }.exceptionOrNull())
            assertArrayEquals(byteArrayOf(4, 5, 6), input.readBytes())
            app.stemwise.data.cleanupExactPrivateChildren(unrelated, "CLEANUP_FAILED") { true }
            assertTrue(unrelated.listFiles()!!.isEmpty())
        } finally {
            // No worker was started by this fault; remove only this test's scratch.
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test fun exactWorkerFilesBlockBothParentCleanupAndDependentReuse() {
        val root = File("build/quarantine-test-${UUID.randomUUID()}")
        val input = File(root, "original.opus")
        val output = File(root, "pcm.f32")
        val failure = WorkerRetirementException("ORIGINAL_PCM_CANCELLATION_TIMED_OUT")
        WorkerRetirementQuarantine.retain(failure, files = listOf(input, output))
        for (file in listOf(root, input, output)) {
            assertSame(failure, WorkerRetirementQuarantine.failureFor(file))
            assertSame(failure, runCatching { WorkerRetirementQuarantine.requireReleased(file) }.exceptionOrNull())
        }
        WorkerRetirementQuarantine.requireReleased(File(root.parentFile, "unrelated.opus"))
    }
}
