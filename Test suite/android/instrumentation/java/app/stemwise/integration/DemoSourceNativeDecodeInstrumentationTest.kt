package app.stemwise.integration

import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.provider.DocumentsContract
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Supplies the actual playback decoder operand to the host demo-fixture generator. */
class DemoSourceNativeDecodeInstrumentationTest {
    @Test
    fun decodeCurrentDemoOriginalsForFixtureGeneration() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "golden-enrichment-document-provider/demo-original48")
        val selector = "${this@DemoSourceNativeDecodeInstrumentationTest.javaClass.name}#decodeCurrentDemoOriginalsForFixtureGeneration"
        val export = InstrumentationRegistry.getArguments().getString("class") == selector
        app.stemwise.integration.WorkerRetirementQuarantine.requireReleased(directory)
        assertTrue(!directory.exists() || directory.deleteAfterWorkerRetirement())
        assertTrue(directory.mkdirs())
        var complete = false
        try {
            for (id in listOf("DEMOUI00001", "DEMOUI00002", "DEMOUI00003")) {
                val original = File(directory, "$id.opus")
                context.assets.open("demo_library/$id/original.opus").use { input ->
                    original.outputStream().use(input::copyTo)
                }
                val pcm = File(directory, "$id.f32")
                prepareNativeOriginal48kPcm(context, original, pcm, 576_000L, 576_000L * 8L)
                assertEquals(576_000L * 8L, pcm.length())
                val digest = MessageDigest.getInstance("SHA-256").digest(original.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                File(directory, "$id.json").writeText(JSONObject()
                    .put("producer", "stemwise-native-original48-v1")
                    .put("sourceSha256", digest).put("sampleRateHz", 48_000)
                    .put("channels", 2).put("audibleFrames", 576_000).toString(), Charsets.UTF_8)
                assertTrue(original.delete())
            }
            if (export) {
                directory.listFiles().orEmpty().forEach { file ->
                    val uri = DocumentsContract.buildDocumentUri(GoldenQaDocumentsProvider.AUTHORITY,
                        "root/demo-original48/${file.name}")
                    context.grantUriPermission("com.android.shell", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            complete = true
        } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
            app.stemwise.integration.WorkerRetirementQuarantine.retain(error, files = listOf(directory))
            throw error
        } finally {
            // An explicit direct selection grants the shell these six functional SAF inputs.
            // Transfer before uninstall: connected-test selections remove their disposable target.
            // Ordinary regression runs validate the decoder and leave no scratch files behind.
            if ((!export || !complete) && app.stemwise.integration.WorkerRetirementQuarantine.failureFor(directory) == null)
                assertTrue(directory.deleteAfterWorkerRetirement())
        }
    }
}
