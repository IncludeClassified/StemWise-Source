package app.stemwise.integration

import app.stemwise.data.withCheckedPrivateCleanup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder
import android.os.StatFs
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.whileUnmetered

// MVSEP's registered, zero-credit tier admits 100 MB; larger files require paid usage.
internal const val MVSEP_FREE_UPLOAD_MAX_BYTES = 100_000_000L

internal class MvsepSubmissionRejected(message: String) : IllegalStateException(message)

internal fun acceptedMvsepJobId(response: JSONObject): String {
    val data = response.optJSONObject("data")
    if (response.opt("success") == false) {
        throw MvsepSubmissionRejected(data?.optString("message").orEmpty().ifBlank {
            response.optString("message").ifBlank { "MVSEP did not accept the separation" }
        })
    }
    check(response.opt("success") == true) { "MVSEP returned an invalid submission response" }
    return data?.optString("hash")?.takeIf(String::isNotBlank)
        ?: error("MVSEP accepted the job without returning its ID")
}

internal class MvsepHttpFailure(val statusCode: Int) : IllegalStateException("MVSEP_HTTP_$statusCode")

internal fun requireSuccessfulMvsepResponse(statusCode: Int) {
    if (statusCode !in 200..299) throw MvsepHttpFailure(statusCode)
}

internal fun requireMvsepUploadByteCount(bytes: Long) {
    require(bytes > 0L) { "Source audio is missing" }
    check(bytes <= MVSEP_FREE_UPLOAD_MAX_BYTES) { "SOURCE_AUDIO_TOO_LARGE" }
}

data class MvsepAlgorithm(
    val renderId: Int,
    val name: String,
    val outputStems: List<String>,
    val priceCoefficient: Double,
    val orientation: Int,
    val catalogueMetadata: MvsepCatalogueMetadata? = null
)

data class MvsepRemoteFile(
    val label: String,
    val url: String,
    val downloadName: String?,
    val declaredSize: Long?
)

data class MvsepRemoteStatus(
    val status: String,
    val message: String?,
    val files: List<MvsepRemoteFile>,
    val providerSourceName: String? = null,
    val providerModelName: String? = null
)

internal interface MvsepServiceClient {
    suspend fun sourceAlgorithms(): List<MvsepAlgorithm> =
        error("MVSEP_SOURCE_SELECTION_UNSUPPORTED")
    suspend fun disablePremium(token: String)
    suspend fun submitConfigured(
        token: String,
        source: File,
        operation: MvsepSourceOperation,
        isAuthorized: () -> Boolean,
        onSubmissionEntered: suspend () -> Unit,
        onAcknowledged: suspend (String) -> Unit = {},
        onRejected: suspend (String) -> Unit = {}
    ): String = error("MVSEP_SOURCE_SELECTION_UNSUPPORTED")
    suspend fun status(remoteJobId: String): MvsepRemoteStatus
    suspend fun download(remote: MvsepRemoteFile, target: File)
}

class MvsepClient internal constructor(
    private val apiBase: () -> String = { MvsepAccount.API_BASE },
    private val networkAccess: AppNetworkAccess
) : MvsepServiceClient {
    override suspend fun sourceAlgorithms(): List<MvsepAlgorithm> =
        availableAlgorithms().filter { it.catalogueMetadata != null }

    private suspend fun availableAlgorithms(): List<MvsepAlgorithm> {
        val body = get("/app/algorithms")
        val algorithms = JSONArray(body)
        val candidates = buildList {
            for (index in 0 until algorithms.length()) {
                val item = algorithms.optJSONObject(index) ?: continue
                if (
                    item.optInt("is_active", 1) != 1 ||
                    item.optInt("audio_upload_disabled", 0) == 1 ||
                    item.optString("audio_widget") != "single_upload" ||
                    item.optInt("orientation", 2) !in 0..1
                ) {
                    continue
                }
                val stems = parseStringList(item.opt("output_stems"))
                val metadata = runCatching { parseMvsepCatalogueMetadata(item) }.getOrNull()
                if (stems.size < 2 && metadata?.outputRoles.orEmpty().size < 2) continue
                val orientation = item.optInt("orientation", 2)
                val priceCoefficient = (item.opt("price_coefficient") as? Number)
                    ?.toDouble()
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: continue
                add(
                    MvsepAlgorithm(
                        renderId = item.optInt("render_id"),
                        name = item.optString("name").ifBlank {
                            "MVSEP model ${item.optInt("render_id")}"
                        },
                        outputStems = stems,
                        priceCoefficient = priceCoefficient,
                        orientation = orientation,
                        catalogueMetadata = metadata
                    )
                )
            }
        }
        return candidates
    }

    override suspend fun disablePremium(token: String) {
        val root = multipart(
            path = "/app/disable_premium",
            fields = mapOf("api_token" to token)
        )
        check(root.optBoolean("success")) {
            root.optString("message").ifBlank {
                "MVSEP could not enable zero-spend protection"
            }
        }
    }

    override suspend fun submitConfigured(
        token: String,
        source: File,
        operation: MvsepSourceOperation,
        isAuthorized: () -> Boolean,
        onSubmissionEntered: suspend () -> Unit,
        onAcknowledged: suspend (String) -> Unit,
        onRejected: suspend (String) -> Unit
    ): String {
        require(source.isFile) { "Source audio is missing" }
        requireMvsepUploadByteCount(source.length())
        MvsepSourcePlanner.validateOperation(operation, sourceAlgorithms())
        return submitFields(
            source = source,
            fields = configuredMvsepSubmissionFields(token, operation),
            isAuthorized = isAuthorized,
            onSubmissionEntered = onSubmissionEntered,
            onAcknowledged = onAcknowledged,
            onRejected = onRejected
        )
    }

    private suspend fun submitFields(
        source: File,
        fields: Map<String, String>,
        isAuthorized: () -> Boolean,
        onSubmissionEntered: suspend () -> Unit,
        onAcknowledged: suspend (String) -> Unit = {},
        onRejected: suspend (String) -> Unit = {}
    ): String {
        require(source.isFile) { "Source audio is missing" }
        requireMvsepUploadByteCount(source.length())
        val root = multipart(
            path = "/separation/create",
            fields = fields,
            fileField = "audiofile",
            file = source,
            isAuthorized = isAuthorized,
            onNetworkEntered = onSubmissionEntered,
            onResponseBody = { body ->
                val response = JSONObject(body)
                val id = try { acceptedMvsepJobId(response) } catch (rejected: MvsepSubmissionRejected) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { onRejected(checkNotNull(rejected.message)) }
                    throw rejected
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { onAcknowledged(id) }
            }
        )
        return acceptedMvsepJobId(root)
    }

    override suspend fun status(remoteJobId: String): MvsepRemoteStatus {
        val encoded = URLEncoder.encode(remoteJobId, Charsets.UTF_8.name())
        val root = JSONObject(get("/separation/get?hash=$encoded"))
        check(root.optBoolean("success")) {
            root.optJSONObject("data")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: "MVSEP no longer recognizes this job"
        }
        val status = root.optString("status").lowercase()
        val data = root.optJSONObject("data")
        val filesArray = data?.optJSONArray("files") ?: JSONArray()
        val files = buildList {
            for (index in 0 until filesArray.length()) {
                val item = filesArray.optJSONObject(index) ?: continue
                val rawUrl = sequenceOf(
                    item.optString("download"),
                    item.optString("url")
                ).firstOrNull { it.startsWith("http") || it.startsWith("/") } ?: continue
                val label = sequenceOf(
                    item.optString("type"),
                    item.optString("name"),
                    item.optString("title")
                ).firstOrNull { it.isNotBlank() } ?: "Stem ${index + 1}"
                add(
                    MvsepRemoteFile(
                        label = label,
                        url = absoluteUrl(rawUrl),
                        downloadName = item.optString("download")
                            .takeIf {
                                it.isNotBlank() &&
                                    !it.startsWith("http") &&
                                    !it.startsWith("/")
                            },
                        declaredSize = item.optLong("size", -1L).takeIf { it > 0L }
                    )
                )
            }
        }
        return MvsepRemoteStatus(
            status = status,
            message = data?.optString("message")?.takeIf { it.isNotBlank() },
            files = files,
            providerSourceName = listOf("source_name", "input_filename").firstNotNullOfOrNull { key ->
                (data?.opt(key) as? String)?.takeIf(String::isNotBlank)
            },
            providerModelName = listOf("model_name", "algorithm_name").firstNotNullOfOrNull { key ->
                (data?.opt(key) as? String)?.takeIf(String::isNotBlank)
            }
        )
    }

    override suspend fun download(remote: MvsepRemoteFile, target: File) {
        val requestContext = coroutineContext
        target.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "Could not create stem storage" }
        }
        val remoteUrl = URL(remote.url)
        require(remoteUrl.protocol.equals("https", ignoreCase = true)) {
            "MVSEP returned an insecure stem download link"
        }
        require(isApprovedMvsepHost(remoteUrl.host)) {
            "MVSEP returned an untrusted stem download host"
        }
        val temporary = File(target.parentFile, "${target.name}.part")
        val request = Request.Builder()
            .url(remote.url)
            .get()
            .header("Accept", "audio/*,application/octet-stream")
            .header("User-Agent", "StemWise Android")
            .build()
        withCheckedPrivateCleanup(
            cleanup = {
                check(!temporary.exists() || temporary.delete()) {
                    "Could not clean incomplete stem download"
                }
            }
        ) {
            executePreemptible(request) { response ->
                requireSuccessfulMvsepResponse(response.code)
                val body = response.body ?: error("MVSEP returned an empty stem")
                val announced = body.contentLength().takeIf { it > 0L }
                    ?: remote.declaredSize
                check(announced == null || announced <= MAX_STEM_BYTES) {
                    "Stem download exceeded 1.5 GB"
                }
                val initialAvailable = StatFs(target.parentFile?.absolutePath ?: target.absolutePath)
                    .availableBytes
                val initialNeeded = Math.addExact(
                    announced ?: MIN_UNKNOWN_STEM_RESERVE_BYTES,
                    STORAGE_SAFETY_RESERVE_BYTES
                )
                check(initialAvailable >= initialNeeded) {
                    "Not enough storage for stems. Needed ${formatBytes(initialNeeded)}, " +
                        "available ${formatBytes(initialAvailable)}. Clean working files and try again."
                }
                body.byteStream().buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    while (true) {
                        requestContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        check(copied <= MAX_STEM_BYTES) { "Stem download exceeded 1.5 GB" }
                        if (copied % SPACE_RECHECK_BYTES < buffer.size) {
                            val available = StatFs(target.parentFile?.absolutePath ?: target.absolutePath)
                                .availableBytes
                            check(available > STORAGE_SAFETY_RESERVE_BYTES) {
                                "Not enough storage for stems. Needed " +
                                    "${formatBytes(STORAGE_SAFETY_RESERVE_BYTES)}, " +
                                    "available ${formatBytes(available)}. " +
                                    "Clean working files and try again."
                            }
                        }
                        output.write(buffer, 0, read)
                    }
                }
                }
                check(temporary.length() > 0L) { "MVSEP returned an empty stem" }
                if (announced != null) {
                    check(temporary.length() == announced) { "Stem download was incomplete" }
                }
            }
            if (target.exists()) check(target.delete()) { "Could not replace an incomplete stem" }
            check(temporary.renameTo(target)) { "Could not finish the stem download" }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(java.util.Locale.ROOT, bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_048_576.0)
        else -> "${bytes / 1_024L} KB"
    }

    private suspend fun multipart(
        path: String,
        fields: Map<String, String>,
        fileField: String? = null,
        file: File? = null,
        isAuthorized: () -> Boolean = { true },
        onNetworkEntered: suspend () -> Unit = {},
        onResponseBody: (suspend (String) -> Unit)? = null
    ): JSONObject {
        val requestContext = coroutineContext
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (name, value) -> multipart.addFormDataPart(name, value) }
        if (fileField != null && file != null) {
            val filename = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val fileBody = object : RequestBody() {
                override fun contentType(): MediaType =
                    "application/octet-stream".toMediaType()

                override fun contentLength(): Long = file.length()

                override fun writeTo(sink: BufferedSink) {
                    file.source().use { source ->
                        val buffer = okio.Buffer()
                        while (true) {
                            requestContext.ensureActive()
                            check(isAuthorized()) { "MVSEP_DISCONNECTED" }
                            val read = source.read(buffer, 128L * 1024L)
                            if (read < 0L) break
                            sink.write(buffer, read)
                        }
                    }
                }
            }
            multipart.addFormDataPart(fileField, filename, fileBody)
        }
        check(isAuthorized()) { "MVSEP_DISCONNECTED" }
        val request = Request.Builder()
            .url("${apiBase()}$path")
            .post(multipart.build())
            .header("Accept", "application/json")
            .header("User-Agent", "StemWise Android")
            .build()
        val body = execute(request, onNetworkEntered, onResponseBody)
        return JSONObject(body)
    }

    private suspend fun get(path: String): String = execute(
        Request.Builder()
            .url("${apiBase()}$path")
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "StemWise Android")
            .build()
    )

    private suspend fun execute(
        request: Request,
        onNetworkEntered: suspend () -> Unit = {},
        onResponseBody: (suspend (String) -> Unit)? = null
    ): String =
        networkAccess.whileUnmetered {
            val call = networkAccess.newCall(MvsepNetwork.client, request)
            // Every request-local validation and request-body construction is complete. The
            // caller may now durably reserve the provider submission immediately before OkHttp
            // can enter the network boundary.
            onNetworkEntered()
            call.executeCancellable { response ->
                if (onResponseBody == null) requireSuccessfulMvsepResponse(response.code)
                val body = response.body?.readBoundedMvsepJson().orEmpty()
                // Submission receipts must become durable even if the HTTP status is inconsistent.
                onResponseBody?.invoke(body)
                requireSuccessfulMvsepResponse(response.code)
                body
            }
        }

    private suspend fun <T> executePreemptible(
        request: Request,
        consume: (okhttp3.Response) -> T
    ): T {
        return networkAccess.whileUnmetered {
            val call = networkAccess.newCall(MvsepNetwork.client, request)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            call.executeCancellable(consume)
        }
    }

    private fun parseStringList(value: Any?): List<String> {
        val array = when (value) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrNull()
            else -> null
        } ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index)
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    private fun absoluteUrl(raw: String): String =
        if (raw.startsWith("http")) raw else URL(URL(apiBase()), raw).toString()

    private fun isApprovedMvsepHost(host: String): Boolean {
        val normalized = host.lowercase()
        return MvsepNetwork.isApprovedMvsepHost(normalized)
    }

    companion object {
        private const val OUTPUT_FORMAT_FLAC_16 = 2
        private const val MAX_STEM_BYTES = 1_500_000_000L
        private const val MIN_UNKNOWN_STEM_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val STORAGE_SAFETY_RESERVE_BYTES = 512L * 1024L * 1024L
        private const val SPACE_RECHECK_BYTES = 8L * 1024L * 1024L
    }
}

/** The provider configuration cannot override account, privacy, file or output-format fields. */
internal fun configuredMvsepSubmissionFields(
    token: String,
    operation: MvsepSourceOperation
): Map<String, String> {
    require(token.isNotBlank()) { "MVSEP_TOKEN_MISSING" }
    MvsepSourcePlanner.requireOperationContract(operation)
    return buildMap {
        put("api_token", token)
        put("sep_type", operation.renderId.toString())
        put("output_format", "2")
        put("is_demo", "0")
        putAll(operation.options)
    }
}
