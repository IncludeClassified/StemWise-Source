package app.stemwise.integration

import androidx.room.Room
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ManualVerifiedYtDlpUpdaterInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun immediateManualRetryAfterConnectivityFailureChecksTheReleaseAgain() = runBlocking {
        withUpdater(failFirst = true) { updater, requests ->
            val first = runCatching { updater.checkForUpdate() }
            assertTrue(first.isFailure)
            assertEquals(1, requests.get())
            updater.checkForUpdate()
            assertEquals("An explicit retry was suppressed after connectivity recovered", 2, requests.get())
        }
    }

    @Test fun secondExplicitCheckWithinADayChecksTheReleaseAgain() = runBlocking {
        withUpdater(failFirst = false) { updater, requests ->
            val first = updater.checkForUpdate()
            val second = updater.checkForUpdate()
            assertEquals(first.version, second.version)
            assertEquals(first.sha256, second.sha256)
            assertEquals("The second explicit check did not reach the release endpoint", 2, requests.get())
        }
    }

    private suspend fun withUpdater(failFirst: Boolean,
        block: suspend (VerifiedYtDlpUpdater, AtomicInteger) -> Unit) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val requests = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val attempt = requests.incrementAndGet()
                if (failFirst && attempt == 1) throw IOException("CONTROLLED_CONNECTIVITY_FAILURE")
                check(chain.request().url.encodedPath.endsWith("/latest/download/SHA2-256SUMS"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                    .header("Location", "https://github.com/yt-dlp/yt-dlp/releases/download/$YT_DLP_BUNDLED_VERSION/SHA2-256SUMS")
                    .body("".toResponseBody()).build()
            }.build()
            val snapshot = AppNetworkState(true, true, false, activeNetworkWifi = true)
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(snapshot)
                override fun currentState() = snapshot
                override fun newCall(client: OkHttpClient, request: Request): Call = client.newCall(request)
            }
            val updater = VerifiedYtDlpUpdater(context, client, network, SelectedRootYtDlpAuthority(roots)) { _, _, _ ->
                error("An unchanged signed release must not download or execute a candidate")
            }
            updater.requireExecutableEligible()
            block(updater, requests)
        } finally { database.close() }
    }
}
