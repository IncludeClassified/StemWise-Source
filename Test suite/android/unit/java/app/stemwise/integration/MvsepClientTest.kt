package app.stemwise.integration

import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MvsepClientTest {
    private val roles = listOf("vocals", "bass", "drums", "guitar", "piano", "other")

    @Test
    fun explicitRejectionIsTypedAndMalformedAcceptanceRemainsUncertain() {
        val rejected = assertThrows(MvsepSubmissionRejected::class.java) {
            acceptedMvsepJobId(org.json.JSONObject("""{"success":false,"data":{"message":"Correct the token"}}"""))
        }
        assertEquals("Correct the token", rejected.message)
        for (body in listOf("{}", """{"success":"false"}""", """{"success":true}""")) {
            val failure = assertThrows(IllegalStateException::class.java) { acceptedMvsepJobId(org.json.JSONObject(body)) }
            assertFalse(failure is MvsepSubmissionRejected)
        }
        assertEquals("accepted-job", acceptedMvsepJobId(org.json.JSONObject(
            """{"success":true,"data":{"hash":"accepted-job"}}""")))
    }

    @Test
    fun actualSubmissionSettlesExplicitAcknowledgementOrRejectionBeforeHttpFailure() {
        for (status in listOf(200, 400)) {
            withSubmissionResponse(status, """{"success":true,"data":{"hash":"accepted-42"}}""") { result, events ->
                assertEquals(listOf("entered", "ack:accepted-42"), events)
                if (status == 200) assertEquals("accepted-42", result.getOrThrow())
                else assertEquals(status, (result.exceptionOrNull() as MvsepHttpFailure).statusCode)
            }
            withSubmissionResponse(status, """{"success":false,"data":{"message":"Correct the token"}}""") { result, events ->
                assertEquals(listOf("entered", "rejected:Correct the token"), events)
                assertEquals(MvsepSubmissionRejected::class.java, result.exceptionOrNull()?.javaClass)
            }
        }
    }

    @Test
    fun actualAmbiguousSubmissionNeverFabricatesAcknowledgementOrRejection() {
        for ((status, body) in listOf(200 to "{}", 200 to """{"success":true}""",
            503 to "<html>unavailable</html>", 503 to "x".repeat((MAX_MVSEP_JSON_RESPONSE_BYTES + 1).toInt()))) {
            withSubmissionResponse(status, body) { result, events ->
                assertEquals(listOf("entered"), events)
                assertFalse(result.isSuccess)
                assertFalse(result.exceptionOrNull() is MvsepSubmissionRejected)
            }
        }
    }

    private fun withSubmissionResponse(status: Int, body: String, verify: (Result<String>, List<String>) -> Unit) {
        val catalogue = """[{"render_id":63,"name":"Fixture","audio_widget":"single_upload","orientation":0,"price_coefficient":0,"output_stems":["vocals","bass","drums","guitar","piano","other","instrum"],"outputs":{"vocals":{},"bass":{},"drums":{},"guitar":{},"piano":{},"other":{},"instrum":{}}}]"""
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(true, true, false, true))
            override fun currentState() = state.value
            override fun newCall(client: OkHttpClient, request: Request): Call = client.newBuilder()
                .addInterceptor { chain ->
                    val submission = when (chain.request().url.encodedPath) {
                        "/api/app/algorithms" -> false
                        "/api/separation/create" -> true
                        else -> error("Unexpected provider request")
                    }
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(if (submission) status else 200).message("Fixture")
                        .body((if (submission) body else catalogue).toResponseBody()).build()
                }.build().newCall(request)
        }
        val work = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "Test suite") }.first(File::isDirectory)
            .resolve("work").also { check(it.isDirectory || it.mkdirs()) }
        val source = File.createTempFile("mvsep-response-", ".flac", work).also { it.writeBytes(byteArrayOf(1)) }
        try {
            val events = mutableListOf<String>()
            val result = runCatching {
                runBlocking {
                    val client = MvsepClient(networkAccess = network)
                    val operation = MvsepSourcePlanner.plan(listOf("vocals"), client.sourceAlgorithms()).operations.single()
                    client.submitConfigured("synthetic-token", source, operation, { true },
                        { events += "entered" }, { events += "ack:$it" }, { events += "rejected:$it" })
                }
            }
            verify(result, events)
        } finally { check(source.delete()) }
    }

    @Test
    fun uploadAdmissionUsesTheProvidersDecimalMegabytesWithoutOverflow() {
        requireMvsepUploadByteCount(1L)
        requireMvsepUploadByteCount(100_000_000L)
        for (bytes in listOf(100_000_001L, 100L * 1024L * 1024L, Long.MAX_VALUE)) {
            val error = assertThrows(IllegalStateException::class.java) { requireMvsepUploadByteCount(bytes) }
            assertEquals("SOURCE_AUDIO_TOO_LARGE", error.message)
        }
        assertThrows(IllegalArgumentException::class.java) { requireMvsepUploadByteCount(0L) }
    }

    @Test
    fun oversizedOriginalIsRejectedBeforeNetworkAdmissionOrSubmissionReservation() {
        val work = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "Test suite") }.first(File::isDirectory)
            .resolve("work").also { check(it.isDirectory || it.mkdirs()) }
        val source = File.createTempFile("mvsep-upload-limit-", ".flac", work)
        try {
            RandomAccessFile(source, "rw").use { it.setLength(100_000_001L) }
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(AppNetworkState(false, false, true))
                override fun currentState(): AppNetworkState = state.value
            }
            val client = MvsepClient(networkAccess = network)
            var reserved = false
            val operation = MvsepSourcePlanner.plan(listOf("vocals"),
                listOf(MvsepAlgorithm(63, "Unused name", roles + "instrum", 1.0, 0))).operations.single()
            val configuredError = assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    client.submitConfigured("unused-test-token", source, operation,
                        { true }, { reserved = true })
                }
            }
            assertEquals("SOURCE_AUDIO_TOO_LARGE", configuredError.message)
            assertFalse(reserved)
        } finally {
            check(source.delete()) { "Could not remove upload-limit fixture" }
        }
    }

    @Test
    fun configuredSubmissionUsesPinnedPrivateLosslessRequestFields() {
        val operation = MvsepSourcePlanner.plan(
            listOf("vocals"), listOf(MvsepAlgorithm(63, "Hidden provider name", roles + "instrum", 1.0, 0))
        ).operations.single()
        assertEquals(
            mapOf("api_token" to "test-token", "sep_type" to "63", "output_format" to "2", "is_demo" to "0"),
            configuredMvsepSubmissionFields("test-token", operation)
        )
        assertThrows(IllegalArgumentException::class.java) { configuredMvsepSubmissionFields(" ", operation) }
    }

    @Test
    fun configurationCannotOverrideProtectedFieldsOrAddUndocumentedParameters() {
        val operation = MvsepSourcePlanner.plan(
            listOf("vocals"), listOf(MvsepAlgorithm(63, "Hidden provider name", roles + "instrum", 1.0, 0))
        ).operations.single()
        listOf("api_token", "audiofile", "url", "preset_id", "sep_type", "output_format", "is_demo", "webhook_url", "add_opt1").forEach { key ->
            assertThrows("Rejected option $key", IllegalArgumentException::class.java) {
                configuredMvsepSubmissionFields("test-token", operation.copy(options = mapOf(key to "tampered")))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            configuredMvsepSubmissionFields("test-token", operation.copy(renderId = 999))
        }
        assertThrows(IllegalArgumentException::class.java) {
            configuredMvsepSubmissionFields("test-token", operation.copy(outputBindings = mapOf("vocals" to "other")))
        }
    }
}
