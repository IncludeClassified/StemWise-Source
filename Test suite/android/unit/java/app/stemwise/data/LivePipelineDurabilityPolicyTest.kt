package app.stemwise.data

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePipelineDurabilityPolicyTest {
    @Test
    fun coldOpenFallbackIsStageSpecificAndNeverIncludesTheRawReason() {
        val diagnostic = coldOpenFailureDiagnostic(
            ColdOpenStage.LIBRARY_PROJECTION,
            IllegalStateException("content://owner/private/path?token=secret")
        )

        assertEquals("LIBRARY_PROJECTION_FAILED", diagnostic.code)
        assertTrue(diagnostic.message.contains("Library projection"))
        assertTrue(diagnostic.message.contains("IllegalStateException"))
        assertFalse(diagnostic.message.contains("content://"))
        assertFalse(diagnostic.message.contains("secret"))
    }

    @Test
    fun unknownNestedPreparationEvidenceRefusesCleanupBeforeDeletingAnything() {
        val work = projectFile("app/build.gradle.kts").parent.parent.parent.resolve("Test suite/work")
        Files.createDirectories(work)
        val scratch = Files.createTempDirectory(work, "preparation-scratch-").toFile()
        try {
            val song = scratch.resolve("F84DytTL_j0")
            val version = song.resolve("incoming/123e4567-e89b-12d3-a456-426614174000")
            val owned = version.resolve("original.opus.part")
            val unknown = version.resolve("foreign.bin")
            check(version.mkdirs())
            owned.writeBytes(byteArrayOf(1, 2, 3))
            unknown.writeBytes(byteArrayOf(4, 5, 6))

            val failure = runCatching { requireOwnedPreparationScratchTree(scratch) }.exceptionOrNull()

            assertEquals("PREPARATION_CLEANUP_UNKNOWN_ENTRY", failure?.message)
            assertTrue(scratch.isDirectory)
            assertTrue(owned.isFile)
            assertTrue(unknown.isFile)
        } finally {
            check(scratch.deleteRecursively()) { "Unable to remove preparation fixture $scratch" }
        }
    }

    private fun projectFile(relative: String): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .map { it.resolve(relative) }
            .firstOrNull(Files::isRegularFile)
            ?: error("Could not locate $relative from $start")
    }
}
