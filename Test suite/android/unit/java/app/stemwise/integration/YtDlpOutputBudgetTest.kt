package app.stemwise.integration

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YtDlpOutputBudgetTest {
    private val project = generateSequence(java.io.File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { java.io.File(it, "AGENTS.md").isFile && java.io.File(it, "Test suite").isDirectory }
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder(
        java.io.File(project, "Test suite/work").apply { check(isDirectory || mkdirs()) })

    @Test fun ringTailMatchesKnownBytesAcrossWraparoundAndPartialFinalChunks() {
        for (size in listOf(1, 100, 8191, 8192, 8193, 70001)) {
            val bytes = ByteArray(size) { (it % 251).toByte() }
            for (capacity in listOf(1, 3, 100, 65536)) {
                val result = ByteArrayOutputStream()
                readBoundedYtDlpOutput(ByteArrayInputStream(bytes), result, size.toLong(), capacity)
                org.junit.Assert.assertArrayEquals(bytes.takeLast(capacity).toByteArray(), result.toByteArray())
            }
        }
    }

    @Test fun bundledRuntimeUsesContentAndRetriesIncompleteExtraction() = runBlocking {
        val archive = temporary.newFile("python.zip")
        val runtime = java.io.File(temporary.root, "runtime")
        var extractions = 0
        val extract: (java.io.File, java.io.File) -> Unit = { input, destination ->
            extractions++
            java.io.File(destination, "library").writeBytes(input.readBytes())
        }
        archive.writeText("one", Charsets.UTF_8)
        installBundledPythonLibraries(archive, runtime, extract)
        installBundledPythonLibraries(archive, runtime, extract)
        assertEquals(1, extractions)
        archive.writeText("two", Charsets.UTF_8)
        installBundledPythonLibraries(archive, runtime, extract)
        assertEquals(2, extractions)
        assertEquals("two", java.io.File(runtime, "library").readText(Charsets.UTF_8))
        archive.writeText("new", Charsets.UTF_8)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                installBundledPythonLibraries(archive, runtime) { _, output ->
                    java.io.File(output, "partial").writeText("partial", Charsets.UTF_8)
                    error("interrupted extraction")
                }
            }
        }
        installBundledPythonLibraries(archive, runtime, extract)
        assertEquals(3, extractions)
        org.junit.Assert.assertFalse(java.io.File(runtime, "partial").exists())
    }

    @Test(timeout = 5_000) fun firstEncoderInstallationWaitsForTheUnmarkedRuntimeConsumer() = runBlocking {
        val archive = temporary.newFile("python.zip").apply { writeText("bundled", Charsets.UTF_8) }
        val runtime = temporary.newFolder("upstream-python")
        val library = java.io.File(runtime, "library").apply { writeText("in use", Charsets.UTF_8) }
        val retire = CompletableDeferred<Unit>()
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            bundledToolRuntimeMutex.withLock {
                retire.await()
                assertEquals("in use", library.readText(Charsets.UTF_8))
            }
        }
        var extractions = 0
        val installation = launch(start = CoroutineStart.UNDISPATCHED) {
            installBundledPythonLibraries(archive, runtime) { _, output ->
                extractions++
                java.io.File(output, "library").writeText("installed", Charsets.UTF_8)
            }
        }
        try {
            assertEquals(0, extractions)
            assertEquals("in use", library.readText(Charsets.UTF_8))
            retire.complete(Unit)
            withTimeout(2_000) { consumer.join(); installation.join() }
            assertEquals(1, extractions)
            assertEquals("installed", library.readText(Charsets.UTF_8))
        } finally {
            retire.complete(Unit)
            consumer.cancelAndJoin()
            installation.cancelAndJoin()
        }
    }

    @Test
    fun oversizedSingleLineIsRejectedBeforeItCanBeMaterializedAsJson() {
        val output = ByteArrayOutputStream()
        val error = assertThrows(IllegalStateException::class.java) {
            readBoundedYtDlpOutput(ByteArrayInputStream(ByteArray(16_385) { 120 }), output, 16_384L)
        }
        assertEquals("YT_DLP_METADATA_OUTPUT_LIMIT", error.message)
        assertEquals(16_384, output.size())
    }

    @Test
    fun diagnosticsKeepOnlyTheBoundedTailAndHaveTheirOwnTotalBudget() {
        val output = ByteArrayOutputStream()
        val bytes = ByteArray(20_000) { (it % 127).toByte() }
        readBoundedYtDlpOutput(ByteArrayInputStream(bytes), output, 20_000L, 100)
        org.junit.Assert.assertArrayEquals(bytes.takeLast(100).toByteArray(), output.toByteArray())
        assertThrows(IllegalStateException::class.java) {
            readBoundedYtDlpOutput(ByteArrayInputStream(bytes), ByteArrayOutputStream(), 19_999L, 100)
        }
    }
}
