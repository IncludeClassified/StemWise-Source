package app.stemwise.integration

import okhttp3.Dns
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MvsepNetworkPolicyTest {
    @Test
    fun clientUsesOnlyTheSystemDnsPolicy() {
        assertSame(Dns.SYSTEM, MvsepNetwork.client.dns)
        assertFalse(MvsepNetwork.client.retryOnConnectionFailure)
    }

    @Test
    fun failedDownloadRetainsTypedHttpFailureAndPreservesThePreviousFile() {
        requireSuccessfulMvsepResponse(200)
        requireSuccessfulMvsepResponse(299)
        val work = File(projectRoot().parentFile, "Test suite/work").also { check(it.isDirectory || it.mkdirs()) }
        val target = File.createTempFile("mvsep-http-", ".flac", work).also { it.writeText("previous", Charsets.UTF_8) }
        var reads = 0
        val responseBody = object : ResponseBody() {
            private val buffered = object : okio.ForwardingSource(Buffer().writeUtf8("error page")) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    reads++
                    return super.read(sink, byteCount)
                }
            }.buffer()
            override fun contentType(): MediaType = "text/html".toMediaType()
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = buffered
        }
        val network = responseNetwork { request ->
            assertEquals("/failed.flac", request.url.encodedPath)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(503).message("Service unavailable").body(responseBody).build()
        }
        try {
            val error = assertThrows(MvsepTransientRemoteFailure::class.java) {
                runBlocking {
                    withAcknowledgedMvsepRead {
                        MvsepClient(networkAccess = network).download(
                            MvsepRemoteFile("bass", "https://de.mvsep.com/failed.flac", null, null), target)
                    }
                }
            }
            assertEquals(503, (error.cause as MvsepHttpFailure).statusCode)
            assertEquals(0, reads)
            assertEquals("previous", target.readText(Charsets.UTF_8))
            assertFalse(File(target.parentFile, "${target.name}.part").exists())
        } finally { check(target.delete()) }
    }

    @Test
    fun actualStatusRequestRetainsTypedHttpFailureWithoutReadingOversizedErrorHtml() {
        var requests = 0
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(
                hasActiveNetwork = true, activeNetworkValidated = true, activeNetworkMetered = false, activeNetworkWifi = true
            ))
            override fun currentState() = state.value
            override fun newCall(client: OkHttpClient, request: Request): Call = client.newBuilder()
                .addInterceptor { chain ->
                    requests++
                    assertEquals("GET", chain.request().method)
                    assertEquals("accepted-42", chain.request().url.queryParameter("hash"))
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(503).message("Service unavailable")
                        .body(ByteArray((MAX_MVSEP_JSON_RESPONSE_BYTES + 1).toInt())
                            .toResponseBody("text/html".toMediaType()))
                        .build()
                }.build().newCall(request)
        }
        val error = assertThrows(MvsepTransientRemoteFailure::class.java) {
            runBlocking {
                withAcknowledgedMvsepRead { MvsepClient(networkAccess = network).status("accepted-42") }
            }
        }
        assertEquals(503, (error.cause as MvsepHttpFailure).statusCode)
        assertEquals(1, requests)
    }

    private fun responseNetwork(response: (Request) -> Response): AppNetworkAccess = object : AppNetworkAccess {
        override val state = MutableStateFlow(AppNetworkState(true, true, false, true))
        override fun currentState() = state.value
        override fun newCall(client: OkHttpClient, request: Request): Call = client.newBuilder()
            .addInterceptor { chain -> response(chain.request()) }.build().newCall(request)
    }

    @Test
    fun approvedDownloadHostsRemainNarrowlyScopedToMvsep() {
        assertTrue(MvsepNetwork.isApprovedMvsepHost("mvsep.com"))
        assertTrue(MvsepNetwork.isApprovedMvsepHost("de.mvsep.com"))
        assertFalse(MvsepNetwork.isApprovedMvsepHost("mvsep.com.example.org"))
        assertFalse(MvsepNetwork.isApprovedMvsepHost("example.org"))
    }

    @Test
    fun jsonResponsesAreReadWithinOneSharedByteLimit() {
        val json = "{\"success\":true}"
        val body = json.toResponseBody("application/json; charset=utf-8".toMediaType())

        assertEquals(json, body.readBoundedMvsepJson())
    }

    @Test
    fun oversizedJsonResponseIsRejectedBeforeParsing() {
        val bytes = ByteArray((MAX_MVSEP_JSON_RESPONSE_BYTES + 1L).toInt()) {
            'x'.code.toByte()
        }
        val body = object : ResponseBody() {
            override fun contentType(): MediaType = "application/json".toMediaType()
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = Buffer().write(bytes)
        }

        val error = assertThrows(IllegalStateException::class.java) {
            body.readBoundedMvsepJson()
        }
        assertEquals(MVSEP_JSON_RESPONSE_TOO_LARGE, error.message)
    }

    @Test
    fun stemDownloadAdmissionRejectsOversizedAnnouncedBytesWithoutOverflow() {
        val work = File(projectRoot().parentFile, "Test suite/work").also { check(it.isDirectory || it.mkdirs()) }
        val target = File.createTempFile("mvsep-oversized-", ".flac", work).also { it.writeText("previous", Charsets.UTF_8) }
        try {
            for (declaredFallback in listOf(false, true)) {
                var reads = 0
                val responseBody = object : ResponseBody() {
                    private val buffered = object : okio.ForwardingSource(Buffer().writeUtf8("unexpected")) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            reads++
                            return super.read(sink, byteCount)
                        }
                    }.buffer()
                    override fun contentType(): MediaType = "audio/flac".toMediaType()
                    override fun contentLength(): Long = if (declaredFallback) -1L else Long.MAX_VALUE
                    override fun source(): BufferedSource = buffered
                }
                val network = responseNetwork { request ->
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(responseBody).build()
                }
                val error = assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        withAcknowledgedMvsepRead {
                            MvsepClient(networkAccess = network).download(MvsepRemoteFile("bass",
                                "https://de.mvsep.com/oversized.flac", null,
                                if (declaredFallback) Long.MAX_VALUE else null), target)
                        }
                    }
                }
                assertEquals("Stem download exceeded 1.5 GB", error.message)
                assertEquals(0, reads)
                assertEquals("previous", target.readText(Charsets.UTF_8))
                assertFalse(File(target.parentFile, "${target.name}.part").exists())
            }
        } finally { check(target.delete()) }
    }

    private fun projectRoot(): File {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Could not locate StemWise project from ${working.absolutePath}")
    }
}
