package app.stemwise.enrichment

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundEnrichmentFailurePolicyTest {
    @Test
    fun `provider failures never expose platform detail`() {
        val failures = listOf(
            SecurityException(
                "Permission Denial: opening provider com.example.private for " +
                    "content://com.example.private/secret"
            ),
            IllegalStateException("android.database.CursorWindow failed at Provider.java:123"),
            java.io.FileNotFoundException("content://provider/internal/owner-file")
        )

        failures.forEach { failure ->
            val message = inboundEnrichmentFailureMessage(failure)
            assertEquals(
                "The shared enrichment files could not be read. Check file access and try again.",
                message
            )
            listOf("SecurityException", "content://", "com.example", "Provider.java").forEach {
                assertFalse(message.contains(it, ignoreCase = true))
            }
        }
    }

    @Test
    fun `bounded user actionable staging failures remain specific`() {
        assertEquals(
            "Import selection is too large",
            inboundEnrichmentFailureMessage(IllegalStateException("Import selection is too large"))
        )
        assertEquals(
            "The shared enrichment files could not be read. Check file access and try again. " +
                "Import cleanup also needs attention.",
            inboundEnrichmentFailureMessage(SecurityException("private"), cleanupFailed = true)
        )
    }

    @Test
    fun `batch importer cannot acquire provider URIs or trees directly`() {
        val importer = source(
            "StemWise/app/src/main/java/app/stemwise/enrichment/SongEnrichmentImporter.kt"
        )
        assertFalse("Importer regained provider URI intake", "source: Uri" in importer)
        assertFalse("Importer regained provider tree intake", "importFolder(" in importer)
    }

    private fun source(relative: String): String = String(
        Files.readAllBytes(projectFile(relative)),
        StandardCharsets.UTF_8
    )

    private fun projectFile(relative: String): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .map { it.resolve(relative) }
            .firstOrNull(Files::isRegularFile)
            ?: error("Could not locate $relative from $start")
    }
}
