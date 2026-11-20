package app.stemwise.data

import android.content.ComponentName
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal class GoldenQaControlAuthorizationInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun oneFileReadGrantCannotControlQaStateFromTheIndependentProviderUid() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val granted = File(providerRoot(), "granted-control-probe.txt").apply { writeText("one-file-grant", Charsets.UTF_8) }
        val retained = File(providerRoot(), "ungranted-control-probe.txt").apply { writeText("retained-neighbor", Charsets.UTF_8) }
        val uri = DocumentsContract.buildDocumentUri(GoldenQaDocumentsProvider.AUTHORITY, "root/${granted.name}")
        providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
        val result = CompletableDeferred<androidx.activity.result.ActivityResult>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                val launcher = host.activityResultRegistry.register("golden-controls-probe",
                    ActivityResultContracts.StartActivityForResult()) { result.complete(it) }
                launcher.launch(Intent().setComponent(ComponentName("app.stemwise.testprovider",
                    "app.stemwise.testprovider.GrantQualificationTreeActivity")).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra("stemwiseQaProbeGoldenControls", true))
            }
            val reply = withTimeout(10_000L) { result.await() }
            assertEquals(android.app.Activity.RESULT_OK, reply.resultCode)
            val report = JSONObject(checkNotNull(reply.data?.getStringExtra("stemwiseQaGoldenProbeResult")))
            assertNotEquals(android.os.Process.myUid(), report.getInt("uid"))
            assertEquals("one-file-grant", report.getString("read"))
            assertEquals(granted.name, report.getString("metadata"))
            for (method in listOf("reset", "set-fault", "metrics", "reset-metrics", "measure-reads"))
                assertEquals(method, "denied", report.getString(method))
        }
        assertEquals("one-file-grant", granted.readText(Charsets.UTF_8))
        assertEquals("retained-neighbor", retained.readText(Charsets.UTF_8))
        assertEquals("one-file-grant", targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        val metrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
        assertTrue("Unauthorized reset-metrics changed global QA state", metrics.getLong("openDocumentCalls") >= 2L)
        // Same-UID instrumentation retains ordinary control admission.
        setFault(GoldenQaDocumentsProvider.FAULT_NONE)
        providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
        assertEquals(0L, providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("openDocumentCalls"))
    }
}
