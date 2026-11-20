package app.stemwise.integration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class YoutubeDlMetadataProbeTest {
    @Test fun metadataProbeDoesNotRequestOrCreateAudio() = runBlocking {
        withDirectory { directory ->
            verifyYtDlpMetadataProbe(directory, "https://www.youtube.com/watch?v=YE7VzlLtp-4") { arguments ->
                assertTrue("--skip-download" in arguments)
                assertEquals("%(.{id,title})j", arguments[arguments.indexOf("--print") + 1])
                assertTrue("--dump-single-json" !in arguments && "--flat-playlist" !in arguments)
                assertTrue(arguments.none { it in setOf("--test", "--format", "--output", "--write-thumbnail") })
                """{"id":"YE7VzlLtp-4","title":"Canary"}"""
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }
    @Test fun invalidMetadataOrUnexpectedFilesCannotPromoteACandidate() = runBlocking {
        for (scenario in listOf("wrong-video", "missing-title", "media-side-effect")) {
            withDirectory { directory ->
                try {
                    verifyYtDlpMetadataProbe(directory, "https://www.youtube.com/watch?v=YE7VzlLtp-4") {
                        if (scenario == "media-side-effect") directory.resolve("sample.opus").writeBytes(byteArrayOf(1))
                        if (scenario == "wrong-video") """{"id":"other-video","title":"Wrong"}"""
                        else if (scenario == "missing-title") """{"id":"YE7VzlLtp-4"}"""
                        else """{"id":"YE7VzlLtp-4","title":"Canary"}"""
                    }
                    fail("Accepted $scenario")
                } catch (failure: IllegalStateException) {
                    assertEquals("YT_DLP_METADATA_PROBE_FAILED", failure.message)
                }
            }
        }
    }
    private suspend fun withDirectory(check: suspend (File) -> Unit) {
        val project = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val work = checkNotNull(project.parentFile).resolve("Test suite/work")
        check(work.isDirectory || work.mkdirs())
        val directory = Files.createTempDirectory(work.toPath(), "metadata-probe-").toFile()
        try { check(directory) } finally { check(directory.deleteRecursively()) }
    }
}
