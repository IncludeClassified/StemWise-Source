package app.stemwise.bridge

import android.content.Context
import android.os.Build
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppOwnedNetworkPolicy
import app.stemwise.network.whileUnmetered
import app.stemwise.integration.runCancellableBlockingOperation
import app.stemwise.enrichment.parseStrictManifestJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import local.phonepc.bridge.BridgeRecovery
import local.phonepc.bridge.mobile.Client
import org.json.JSONArray
import org.json.JSONObject

internal fun bridgeAppResponse(wire: String, profile: BridgeConnectionProfile): JSONObject {
    val envelope = parseStrictManifestJson(wire.toByteArray(Charsets.UTF_8))
    val response = envelope.getJSONObject("response")
    checkBridge(response.getString("protocol") == "phone-pc-bridge-app-v1" &&
        response.getString("bridgeIdentity") == profile.bridgeId && response.getString("pcIdentity") == profile.pcId,
        BridgeFailureCode.BRIDGE_RESPONSE_IDENTITY_INVALID)
    val data = response.getJSONObject("data")
    val status = envelope.getInt("httpStatus")
    if (status !in 200..299) throw BridgeApiFailure(status, data.getString("code"),
        data.getString("message"), data.getBoolean("retryable"))
    return data
}

internal fun requireBridgeInputReceipt(wire: String, expected: BridgeFile) {
    val receipt = parseStrictManifestJson(wire.toByteArray(Charsets.UTF_8))
    check(receipt.getString("state") == "stored" &&
        BridgeFile.parse(receipt.getJSONObject("file")) == expected) { "BRIDGE_GUIDE_RECEIPT_INVALID" }
}

/** Absence is an exact SDK observation; inspection failures must propagate without another download. */
internal fun bridgeReceivedFileIsRetained(receipt: JSONObject, pcIdentity: String, file: BridgeFile): Boolean {
    check(receipt.getString("pcIdentity") == pcIdentity) { "BRIDGE_OUTPUT_IDENTITY_CHANGED" }
    return when (receipt.getString("state")) {
        "absent" -> {
            check(receipt.getString("transferId") == file.id && !receipt.has("file")) { "BRIDGE_OUTPUT_IDENTITY_CHANGED" }
            false
        }
        "received_local_verified" -> {
            check(BridgeFile.parse(receipt.getJSONObject("file")) == file) { "BRIDGE_OUTPUT_IDENTITY_CHANGED" }
            true
        }
        else -> error("BRIDGE_OUTPUT_IDENTITY_CHANGED")
    }
}

internal fun completeBridgeJobObservation(job: JSONObject, inputs: JSONObject, log: JSONObject): JSONObject {
    job.put("input", inputs)
    val entries = log.getJSONArray("entries")
    if (entries.length() > 0 || log.optLong("dropped") > 0) {
        val summary = if (entries.length() > 0) List(entries.length()) { index ->
            entries.getJSONObject(index).getJSONObject("error").getString("summary")
        }.joinToString("\n\n") else "Earlier Bridge diagnostics are unavailable."
        // The Bridge already bounds and redacts this log; retain all of the returned diagnostic fields.
        job.put("diagnostic", "$summary\n\nBridge diagnostics:\n${log.toString(2)}")
    }
    return job
}

internal class AndroidSongBridgeTransport(
    private val context: Context,
    private val profile: BridgeConnectionProfile,
    private val networkAccess: AppNetworkAccess,
    private val createClient: () -> Client = {
        BridgeRecovery.client(context.applicationContext, "stemwise-bridge", true)
    }
) : SongBridgeTransport {
    @Volatile internal var beforeReceivedCopyForTest: (() -> Unit)? = null
    override val networkAvailable: Boolean
        get() = AppOwnedNetworkPolicy.allowsNetwork(networkAccess.currentState())
    private val client: Client by lazy {
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "BRIDGE_REQUIRES_ARM64" }
        createClient()
    }
    private val observations = BridgeJobObservationCache()
    @Volatile private var verifiedAppId: String? = null
    private val registration by lazy { context.getSharedPreferences("bridge-registration", Context.MODE_PRIVATE) }

    private suspend fun <T> call(block: (Client, String) -> T): T = networkAccess.whileUnmetered { localCall(block) }

    private suspend fun <T> localCall(block: (Client, String) -> T): T = withContext(Dispatchers.IO) {
        val owner = client
        currentCoroutineContext().ensureActive()
        val operation = owner.newOperation()
        try {
            runCancellableBlockingOperation(cancelExact = operation::cancel) { block(owner, operation.token()) }
        } finally {
            // The runner joins the actual worker, including a cancelled or never-started phase.
            operation.close()
        }
    }
    override suspend fun api(method: String, path: String, body: JSONObject?): JSONObject = try {
        call { owner, operation -> bridgeAppResponse(owner.callApp(operation, profile.host, profile.bridgeId,
            method, path, body?.toString().orEmpty()), profile) }
    } catch (error: BridgeApiFailure) {
        if (error.code in setOf("app_registration_required", "app_registration_changed")) verifiedAppId = null
        throw error
    }

    override suspend fun ensureApp() = ensureRegistration(replace = false)
    override suspend fun pairAgain() = ensureRegistration(replace = true)

    private suspend fun ensureRegistration(replace: Boolean) {
        val saved = registration.getString(profile.registrationKey(), null)
        if (!replace && saved != null && saved == verifiedAppId) return
        retainBridgeRegistration(saved, replace, ::api) { id ->
            check(registration.edit().putString(profile.registrationKey(), id).commit()) { "BRIDGE_REGISTRATION_SAVE_FAILED" }
        }
        verifiedAppId = registration.getString(profile.registrationKey(), null)
    }
    override suspend fun status(): JSONObject = call { owner, _ -> parseStrictManifestJson(owner.status().toByteArray(Charsets.UTF_8)) }
    override suspend fun login() = call { owner, _ -> owner.login() }
    private suspend fun <T> files(block: (local.phonepc.bridge.mobile.FileClient, String) -> T): T {
        val opened = call { owner, operation -> owner.openAppFiles(operation, profile.host, profile.bridgeId) }
        return call { _, operation -> block(opened, operation) }
    }
    override suspend fun prepareInput(source: File, name: String): BridgeFile {
        ensureApp()
        return files { files, operation -> BridgeFile.parse(parseStrictManifestJson(
            files.prepareFile(operation, name, source.absolutePath).toByteArray(Charsets.UTF_8))) }
    }
    override suspend fun ensureInput(file: BridgeFile, source: File) {
        val local = localCall { owner, _ -> owner.localFiles(profile.host, profile.bridgeId) }
        val restored = localCall { _, operation -> BridgeFile.parse(parseStrictManifestJson(
            local.restorePreparedFile(operation, file.json().toString(), source.absolutePath).toByteArray(Charsets.UTF_8))) }
        checkBridge(restored == file, BridgeFailureCode.BRIDGE_INPUT_CHANGED)
        val receipt = files { files, operation -> files.ensureSent(operation, file.id) }
        requireBridgeInputReceipt(receipt, file)
    }
    override suspend fun job(id: String): JSONObject? {
        ensureApp()
        val job = try { api("GET", "/v2/jobs/$id") } catch (error: BridgeApiFailure) {
            if (error.status == 404 && error.code == "job_unavailable") return null
            throw error
        }
        return completeJobObservation(id, job)
    }
    override suspend fun retireMissingJob(id: String): JSONObject =
        completeJobObservation(id, api("POST", "/v2/jobs/$id/retire"))

    private suspend fun completeJobObservation(id: String, job: JSONObject): JSONObject {
        return observations.complete(id, job,
            readInputs = { api("GET", "/v2/jobs/$id/inputs") },
            readLog = { api("GET", "/v2/jobs/$id/log") })
    }
    override suspend fun submit(request: JSONObject): JSONObject {
        api("POST", "/v2/jobs", request)
        return job(request.getString("requestId")) ?: throw BridgeLocalFailure(BridgeFailureCode.BRIDGE_SUBMISSION_UNCONFIRMED)
    }
    override suspend fun receiveRetained(file: BridgeFile, destination: File): Boolean {
        val local = localCall { owner, _ -> owner.localFiles(profile.host, profile.bridgeId) }
        val retained = bridgeReceivedFileIsRetained(localCall { _, operation ->
            parseStrictManifestJson(local.receivedInfo(operation, file.id).toByteArray(Charsets.UTF_8))
        }, profile.bridgeId, file)
        if (!retained) return false
        app.stemwise.data.requireImportStorageCapacity(android.os.StatFs(checkNotNull(destination.parentFile).absolutePath).availableBytes,
            file.size, "STORAGE_RESERVE_LOW")
        localCall { _, operation ->
            beforeReceivedCopyForTest?.invoke()
            local.copyReceived(operation, file.id, destination.absolutePath)
        }
        checkBridge(file.matches(destination), BridgeFailureCode.BRIDGE_LOCAL_OUTPUT_UNVERIFIED)
        return true
    }
    override suspend fun receive(file: BridgeFile, destination: File) {
        if (receiveRetained(file, destination)) return
        app.stemwise.data.requireImportStorageCapacity(android.os.StatFs(context.filesDir.absolutePath).availableBytes,
            Math.multiplyExact(file.size, 2L), "STORAGE_RESERVE_LOW")
        files { files, operation -> files.receiveMatching(operation, file.id, file.name, file.size, file.sha256) }
        checkBridge(receiveRetained(file, destination), BridgeFailureCode.BRIDGE_LOCAL_OUTPUT_UNVERIFIED)
    }
    override suspend fun acknowledge(id: String, file: BridgeFile, destination: File) {
        val imports = JSONArray().put(JSONObject().put("file", file.json()).put("path", destination.absolutePath))
        val result = call { owner, operation -> bridgeAppResponse(owner.confirmDelivery(operation, profile.host,
            profile.bridgeId, id, imports.toString()), profile) }
        check(result.getString("id") == id && result.getLong("delivered") > 0L) { "BRIDGE_DELIVERY_UNCONFIRMED" }
        files { files, _ -> files.removeReceived(file.id) }
    }
    override suspend fun deleteJob(id: String) {
        ensureApp()
        check(api("DELETE", "/v2/jobs/$id").getBoolean("deleted")) { "BRIDGE_CLEANUP_UNCONFIRMED" }
        observations.remove(id)
    }
    override suspend fun discardTransfers(record: BridgeSongRecord) {
        check(record.remoteRetired && record.connection == profile) { "BRIDGE_CLEANUP_REFUSED" }
        val local = localCall { owner, _ -> owner.localFiles(profile.host, profile.bridgeId) }
        val inputs = record.request.getJSONArray("files")
        for (index in 0 until inputs.length()) {
            val input = BridgeFile.parse(inputs.getJSONObject(index))
            localCall { _, _ -> local.discardPrepared(input.id) }
        }
        record.output?.let { output -> localCall { _, _ -> local.removeReceived(output.id) } }
    }
}

/** Explicit pairing can adopt the current receiver only after the repository excludes all unresolved jobs. */
internal suspend fun retainBridgeRegistration(saved: String?, replace: Boolean,
    api: suspend (String, String, JSONObject?) -> JSONObject, persist: (String) -> Unit) {
    val actual = try { api("GET", "/v2/app", null) } catch (error: BridgeApiFailure) {
        if (error.code != "app_registration_required" || saved != null && !replace) throw error
        api("POST", "/v2/app", JSONObject().put("name", "StemWise"))
    }
    val id = actual.getString("id")
    checkBridge(actual.getString("name") == "StemWise" && id.isNotBlank() &&
        (replace || saved == null || saved == id), BridgeFailureCode.BRIDGE_REGISTRATION_CHANGED)
    if (saved != id) persist(id)
}

/** Receiver inputs are immutable; diagnostics can change on any status observation. */
internal class BridgeJobObservationCache {
    private val inputs = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    suspend fun complete(id: String, job: JSONObject, readInputs: suspend () -> JSONObject,
        readLog: suspend () -> JSONObject): JSONObject {
        checkBridge(job.getString("id") == id, BridgeFailureCode.BRIDGE_JOB_IDENTITY_INVALID)
        val input = inputs[id] ?: readInputs().also {
            checkBridge(it.getString("requestId") == id, BridgeFailureCode.BRIDGE_INPUT_CHANGED)
            inputs.putIfAbsent(id, it)
        }
        return try {
            completeBridgeJobObservation(job, input, readLog())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            job.put("input", input).put("diagnostic", "Bridge diagnostics are unavailable (${error.javaClass.simpleName}).")
        }
    }

    fun remove(id: String) { inputs.remove(id) }
}
