package app.stemwise.testprovider

import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QualificationDocumentPathsTest {
    @Test
    fun exactIdentitiesCannotAliasAnotherRootAndBothRootsAreProtected() {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        val project = generateSequence(working) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
        val fixture = File(project.parentFile, "Test suite/work/provider-boundary-${UUID.randomUUID()}").canonicalFile
        val primary = File(fixture, "root").also { check(it.mkdirs()) }
        val alternate = File(fixture, "alternate").also { check(it.mkdir()) }
        val first = File(primary, "retained.bin").also { it.writeText("primary bytes") }
        val second = File(alternate, "retained.bin").also { it.writeText("alternate bytes") }
        try {
            assertEquals(first, QualificationDocumentPaths.requireDocument(primary, "root", "root/retained.bin"))
            assertEquals(second, QualificationDocumentPaths.requireDocument(alternate, "alternate", "alternate/retained.bin"))
            assertTrue(QualificationDocumentPaths.isRoot(primary, primary, alternate))
            assertTrue(QualificationDocumentPaths.isRoot(alternate, primary, alternate))
            assertFalse(QualificationDocumentPaths.isRoot(first, primary, alternate))
            for (id in listOf("root/../alternate/retained.bin", "root/./retained.bin", "root//retained.bin", "root/../../retained.bin")) {
                assertThrows(FileNotFoundException::class.java) { QualificationDocumentPaths.requireDocument(primary, "root", id) }
                assertEquals("primary bytes", first.readText())
                assertEquals("alternate bytes", second.readText())
            }
            val provider = File(project, "qa-provider/src/main/java/app/stemwise/testprovider/QualificationDocumentsProvider.java").readText()
            assertTrue("Provider no longer delegates exact document identity", "QualificationDocumentPaths.requireDocument(rootDirectory(rootId), rootId, documentId)" in provider)
            assertTrue("Provider no longer refuses deleting either root", "QualificationDocumentPaths.isRoot(target, rootDirectory(ROOT_ID), rootDirectory(ALTERNATE_ROOT_ID))" in provider)
        } finally { check(fixture.deleteRecursively()) }
    }
}
