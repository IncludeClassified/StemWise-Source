package app.stemwise.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/** Architecture guard only. AtomicJournalFiles and BackendSafety execute recovery behavior. */
class AtomicJournalRecoveryPolicyTest {
    @Test fun journalOwnersCannotIntroduceRawBaseOnlyParsing() {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        val project = generateSequence(working) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val data = project.resolve("app/src/main/java/app/stemwise/data")
        val owners = listOf("PackageMutationCoordinator.kt", "ExternalPackageTrash.kt",
            "CanonicalPackagePublisher.kt", "EnrichmentTransactionRecovery.kt") +
            listOf("../enrichment/EnrichmentImportCoordinator.kt")
        owners.forEach { owner ->
            val source = data.resolve(owner).readText()
            listOf("JSONObject(file.readText", "JSONObject(candidate.readText",
                "JSONObject(journalFile.readText").forEach { bypass ->
                assertFalse("$owner introduces raw journal parsing: $bypass", bypass in source)
            }
        }
    }
}
