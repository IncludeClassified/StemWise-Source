package app.stemwise.enrichment

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.testprovider.QualificationDocumentsProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QualificationProviderSeedInstrumentationTest {
    @Test
    fun bothFreshProviderRootsValidateRawSeedsAndSealedOwnerEditsArePreserved() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val temporary = File(target.cacheDir, "qualification-seed-${UUID.randomUUID()}")
            .apply { check(mkdir()) }
        val isolated = object : ContextWrapper(target) {
            private val files = File(temporary, "files").apply { check(mkdir()) }
            private val noBackup = File(temporary, "no-backup").apply { check(mkdir()) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = noBackup
        }
        val provider = QualificationDocumentsProvider()
        try {
            // attachInfo invokes the real onCreate and seedRoot with task-owned storage.
            provider.attachInfo(isolated, ProviderInfo().apply {
                authority = QualificationDocumentsProvider.AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            })
            val ids = listOf("DEMOUI00001", "DEMOUI00002", "DEMOUI00003")
            val roots = listOf(QualificationDocumentsProvider.ROOT_ID,
                QualificationDocumentsProvider.ALTERNATE_ROOT_ID).map { rootId ->
                QualificationDocumentsProvider.rootDirectory(isolated, rootId)
            }
            for (root in roots) {
                assertEquals(ids, checkNotNull(root.listFiles()).filter(File::isDirectory).map(File::getName).sorted())
                for (id in ids) {
                    val directory = File(root, id)
                    val manifestFile = File(directory, "song.json")
                    assertArrayEquals(target.assets.open("demo_library/$id/song.json").use { it.readBytes() },
                        manifestFile.readBytes())
                    val document = JSONObject(manifestFile.readText(Charsets.UTF_8))
                    val original = File(directory, "original.opus")
                    val originalSha = sha256(original)
                    validateInstalledEnrichmentDocument(document, id, originalSha)
                    val media = installedMediaIdentities(document)
                    assertEquals(listOf("original.opus"), media.keys.toList())
                    assertEquals(originalSha, media.getValue("original.opus").sha256)
                    assertEquals(original.length(), media.getValue("original.opus").byteLength)
                    val artwork = document.getJSONObject("artwork")
                    val cover = File(directory, "cover.png")
                    assertEquals(cover.length(), artwork.getLong("byteLength"))
                    assertEquals(sha256(cover), artwork.getString("sha256"))
                }
            }
            File(roots[0], "DEMOUI00001/song.json").appendText("\nowner edit", Charsets.UTF_8)
            File(roots[1], "DEMOUI00002/original.opus").appendBytes(byteArrayOf(1, 2, 3))
            fun snapshot() = temporary.walkTopDown().filter(File::isFile).associate {
                it.relativeTo(temporary).invariantSeparatorsPath to (it.length() to sha256(it))
            }
            val edited = snapshot()
            assertTrue(provider.onCreate())
            assertEquals(edited, snapshot())
        } finally {
            provider.shutdown()
            check(temporary.deleteRecursively()) { "Could not remove isolated qualification seed $temporary" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
