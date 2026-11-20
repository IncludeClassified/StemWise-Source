package app.stemwise.bridge

import app.stemwise.data.CanonicalJson
import app.stemwise.data.RootAuthority
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal class BridgeApiFailure(val status: Int, val code: String, message: String, val retryable: Boolean) :
    IllegalStateException(message)

internal enum class BridgeFailureCode(val disposition: BridgeFailureDisposition) {
    LIBRARY_CHANGED(BridgeFailureDisposition.WAIT),
    ROOT_UNAVAILABLE(BridgeFailureDisposition.WAIT),
    RECOVERY_PENDING(BridgeFailureDisposition.WAIT),
    BRIDGE_CONNECTION_REQUIRED(BridgeFailureDisposition.WAIT),
    BRIDGE_REGISTRATION_CHANGED(BridgeFailureDisposition.WAIT),
    BRIDGE_LOCAL_OUTPUT_CHANGED(BridgeFailureDisposition.WAIT),
    BRIDGE_LOCAL_OUTPUT_UNVERIFIED(BridgeFailureDisposition.WAIT),
    BRIDGE_REMOTE_OUTCOME_UNRESOLVED(BridgeFailureDisposition.WAIT),
    BRIDGE_SONG_REPORT_INVALID(BridgeFailureDisposition.REJECT),
    BRIDGE_SONG_OUTPUT_INVALID(BridgeFailureDisposition.REJECT),
    BRIDGE_REQUEST_CONFLICT(BridgeFailureDisposition.REJECT),
    BRIDGE_OUTPUT_CHANGED(BridgeFailureDisposition.REJECT),
    BRIDGE_FILE_INVALID(BridgeFailureDisposition.REJECT),
    BRIDGE_JOB_IDENTITY_INVALID(BridgeFailureDisposition.REJECT),
    BRIDGE_RESPONSE_IDENTITY_INVALID(BridgeFailureDisposition.REJECT),
    BRIDGE_INPUT_CHANGED(BridgeFailureDisposition.REJECT),
    BRIDGE_CANCEL_RECEIPT_INVALID(BridgeFailureDisposition.RETRY),
    BRIDGE_SUBMISSION_UNCONFIRMED(BridgeFailureDisposition.RETRY)
}

internal class BridgeLocalFailure(val code: BridgeFailureCode, cause: Throwable? = null) :
    IllegalStateException(code.name, cause)

internal fun checkBridge(condition: Boolean, code: BridgeFailureCode) {
    if (!condition) throw BridgeLocalFailure(code)
}

internal fun bridgeHasMultipleLinks(text: String): Boolean =
    Regex("https?://", RegexOption.IGNORE_CASE).findAll(text).take(2).count() > 1

internal fun bridgeVideoId(text: String): String {
    require(!bridgeHasMultipleLinks(text)) { "Send one song link per job. Remove the extra links." }
    val uri = URI(text.trim())
    require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443)) { "Enter an HTTPS YouTube song link" }
    val host = app.stemwise.integration.normalizedYoutubeHost(uri.host)
    val id = when (host) {
        "youtu.be" -> uri.path.removePrefix("/")
        "youtube.com", "music.youtube.com" -> when {
            uri.path == "/watch" -> uri.rawQuery.orEmpty().split('&').singleOrNull { it.startsWith("v=") }?.removePrefix("v=")
            uri.path.startsWith("/shorts/") -> uri.path.removePrefix("/shorts/")
            else -> null
        }
        else -> null
    }
    require(id != null && Regex("[A-Za-z0-9_-]{11}").matches(id)) { "Enter one YouTube song link" }
    return id
}

internal data class BridgeFile(val id: String, val name: String, val size: Long, val sha256: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("size", size).put("sha256", sha256)
    suspend fun matches(file: File): Boolean = file.isFile && file.length() == size &&
        file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        } == sha256
    companion object {
        fun parse(json: JSONObject): BridgeFile = BridgeFile(json.getString("id"), json.getString("name"),
            json.getLong("size"), json.getString("sha256")).also {
            checkBridge(Regex("[a-f0-9]{32}").matches(it.id) && Regex("[a-f0-9]{64}").matches(it.sha256) &&
                it.size > 0 && it.name.isNotBlank() && it.name.length <= 255, BridgeFailureCode.BRIDGE_FILE_INVALID)
        }
    }
}

internal data class BridgeAttempt(
    val number: Int,
    val startedAtEpochMs: Long,
    val elapsedMs: Long,
    val stage: String,
    val outcome: String,
    val disposition: BridgeFailureDisposition? = null,
    val cause: String? = null,
    val verifiedTransferBytes: Long = 0L
) {
    fun json(): JSONObject = JSONObject().put("number", number).put("startedAt", startedAtEpochMs)
        .put("elapsedMs", elapsedMs).put("stage", stage).put("outcome", outcome)
        .put("disposition", disposition?.name ?: JSONObject.NULL).put("cause", cause ?: JSONObject.NULL)
        .put("verifiedTransferBytes", verifiedTransferBytes)

    companion object {
        fun parse(value: JSONObject): BridgeAttempt = BridgeAttempt(
            value.getInt("number"), value.getLong("startedAt"), value.getLong("elapsedMs"),
            value.getString("stage"), value.getString("outcome"),
            value.optString("disposition").takeUnless { it.isBlank() || it == "null" }
                ?.let(BridgeFailureDisposition::valueOf),
            value.optString("cause").takeUnless { it.isBlank() || it == "null" }, value.getLong("verifiedTransferBytes")
        ).also {
            check(it.number > 0 && it.startedAtEpochMs >= 0 && it.elapsedMs >= 0 &&
                it.outcome in setOf("running", "complete", "failed", "cancelled") &&
                (it.cause?.length ?: 0) <= 4096 && it.verifiedTransferBytes >= 0) { "BRIDGE_DIAGNOSTIC_INVALID" }
        }
    }
}

internal enum class BridgeSubmission { UNSENT, UNKNOWN, ACKNOWLEDGED }

internal data class BridgeSendPreparation(val wakeClaimed: Boolean = false, val catalogValidated: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("wakeClaimed", wakeClaimed).put("catalogValidated", catalogValidated)
}

internal data class BridgeSongRecord(
    val root: RootAuthority,
    val guide: String,
    val request: JSONObject,
    val task: BridgeSongTask,
    val inputsVerified: Boolean = false,
    val imported: Boolean = false,
    val output: BridgeFile? = null,
    val retired: Boolean = false,
    val remoteRetired: Boolean = false,
    val attempts: List<BridgeAttempt> = emptyList(),
    val approval: BridgeSongSubmission? = null,
    val submission: BridgeSubmission = BridgeSubmission.UNSENT,
    val outputVerified: Boolean = false,
    val connection: BridgeConnectionProfile? = null,
    val preparation: BridgeSendPreparation? = null,
    val replacesRequestId: String? = null,
    val installationVerified: Boolean = false,
    val remoteTerminalObserved: Boolean = false
) {
    val canReplaceReviewedRequest: Boolean get() = task.phase == BridgeSongPhase.REVIEW_REQUIRED &&
        approval != null && preparation?.catalogValidated == false && submission == BridgeSubmission.UNSENT &&
        !inputsVerified && request.getJSONArray("files").length() == 0 && !outputVerified && output == null &&
        !imported && !retired && !remoteRetired && !task.cancelRequested
    val canRetryImport: Boolean get() = approval != null && connection != null && inputsVerified &&
        task.phase == BridgeSongPhase.FAILED && submission == BridgeSubmission.ACKNOWLEDGED &&
        outputVerified && output != null && !imported && !task.cancelRequested && !retired && !remoteRetired

    val resumableTask: BridgeSongTask get() {
        val pending = preparation?.catalogValidated == false && !task.terminal
        val displayed = task.copy(remoteCompletionKnown = output != null,
            localImportPending = outputVerified && output != null && !imported,
            sendPreparationPending = pending,
            canRetryWake = pending && preparation?.wakeClaimed == true && task.phase == BridgeSongPhase.WAITING_PC && !task.cancelRequested,
            approvedSubmission = approval, connection = connection, canReplaceReviewedRequest = canReplaceReviewedRequest)
        return when {
            canRetryImport -> {
                val cause = if (task.message.isNullOrBlank() || task.message.startsWith("This task needs attention."))
                    attempts.lastOrNull { it.outcome == "failed" }?.cause?.takeIf(String::isNotBlank) else null
                displayed.copy(phase = BridgeSongPhase.WAITING, stage = "Song import needs attention", retryable = true,
                    message = cause?.let { retainBridgeMessage(task.message,
                        "${it.lineSequence().first()}\n\nBridge diagnostics:\n$it") } ?: task.message)
            }
            approval != null && connection != null && inputsVerified && task.phase == BridgeSongPhase.FAILED &&
                submission == BridgeSubmission.UNKNOWN && !retired && !remoteRetired ->
                displayed.copy(phase = BridgeSongPhase.WAITING, stage = "Original PC task needs confirmation", retryable = true,
                    message = listOfNotNull("Resume to check the original PC task. It will not be sent again.",
                        task.message?.takeIf(String::isNotBlank)).joinToString("\n\n"))
            else -> displayed
        }
    }

    fun requireApproval() {
        checkNotNull(connection) { "BRIDGE_CONNECTION_REQUIRED" }
        val selected = checkNotNull(approval) { "BRIDGE_APPROVAL_REQUIRED" }
        check(task.phase != BridgeSongPhase.DRAFT && selected.link == request.getString("text") &&
            selected.model == request.getString("model") && selected.effort == request.getString("effort") &&
            selected.serviceTier == request.getString("serviceTier")) { "BRIDGE_APPROVAL_CHANGED" }
        check(bridgeVideoId(selected.link) == task.videoId && selected.model.isNotBlank() &&
            selected.effort.isNotBlank() && (selected.serviceTier.isEmpty() || selected.serviceTier.isNotBlank())) {
            "BRIDGE_APPROVAL_INVALID"
        }
        check(if (selected.serviceTier.isEmpty()) request.opt("nativeDefaultServiceTier") == true
            else !request.has("nativeDefaultServiceTier")) { "BRIDGE_APPROVAL_CHANGED" }
    }
    fun json(): JSONObject = JSONObject().put("version", 6).put("libraryId", root.libraryId)
        .put("replacesRequestId", replacesRequestId ?: JSONObject.NULL)
        .put("preparation", preparation?.json() ?: JSONObject.NULL)
        .put("connection", connection?.json() ?: JSONObject.NULL)
        .put("treeUri", root.treeUri).put("epoch", root.epoch).put("guide", guide).put("request", request)
        .put("videoId", task.videoId).put("state", task.state).put("stage", task.stage)
        .put("message", task.message ?: JSONObject.NULL).put("chatId", task.chatId ?: JSONObject.NULL)
        .put("chatName", task.chatName ?: JSONObject.NULL).put("retryable", task.retryable)
        .put("cancelRequested", task.cancelRequested)
        .put("inputsVerified", inputsVerified).put("imported", imported)
        .put("installationVerified", installationVerified)
        .put("remoteTerminalObserved", remoteTerminalObserved)
        .put("submission", submission.name).put("outputVerified", outputVerified)
        .put("output", output?.json() ?: JSONObject.NULL).put("retired", retired)
        .put("remoteRetired", remoteRetired)
        .put("attempts", JSONArray().also { array -> attempts.forEach { array.put(it.json()) } })
        .put("approval", approval?.let { JSONObject().put("link", it.link).put("model", it.model)
            .put("effort", it.effort).put("serviceTier", it.serviceTier) } ?: JSONObject.NULL)

    companion object {
        fun parse(json: JSONObject): BridgeSongRecord {
            check(json.getInt("version") == 6) { "BRIDGE_OUTBOX_VERSION_UNSUPPORTED" }
            val request = json.getJSONObject("request")
            val id = request.getString("requestId")
            check(Regex("[a-f0-9]{32}").matches(id)) { "BRIDGE_OUTBOX_INVALID" }
            val task = BridgeSongTask(id, json.getString("videoId"), request.getString("title"),
                BridgeSongPhase.valueOf(json.getString("state")), json.getString("stage"), json.optString("message").takeUnless { it == "null" || it.isBlank() },
                json.optString("chatId").takeUnless { it == "null" || it.isBlank() },
                json.optString("chatName").takeUnless { it == "null" || it.isBlank() }, request.getLong("createdAt"), json.getBoolean("retryable"), json.optBoolean("cancelRequested"), request.getString("text"), json.getBoolean("imported"))
            check(bridgeVideoId(request.getString("text")) == task.videoId) { "BRIDGE_OUTBOX_INVALID" }
            check(!json.has("preparation") || json.isNull("preparation") || json.get("preparation") is JSONObject) { "BRIDGE_PREPARATION_INVALID" }
            return BridgeSongRecord(RootAuthority(json.getString("libraryId"), json.getString("treeUri"), json.getLong("epoch")),
                json.getString("guide"), request, task, json.getBoolean("inputsVerified"),
                json.getBoolean("imported"), json.optJSONObject("output")?.let(BridgeFile::parse), json.getBoolean("retired"),
                json.getBoolean("remoteRetired"),
                json.optJSONArray("attempts")?.let { array ->
                    check(array.length() <= 8) { "BRIDGE_DIAGNOSTIC_INVALID" }
                    List(array.length()) { BridgeAttempt.parse(array.getJSONObject(it)) }
                }.orEmpty(), json.optJSONObject("approval")?.let {
                    BridgeSongSubmission(it.getString("link"), it.getString("model"), it.getString("effort"), it.getString("serviceTier"))
                }, BridgeSubmission.valueOf(json.getString("submission")), json.getBoolean("outputVerified"),
                json.get("connection").let {
                    if (it == JSONObject.NULL) null else {
                        check(it is JSONObject) { "BRIDGE_CONNECTION_INVALID" }
                        BridgeConnectionProfile.parse(it)
                    }
                }, json.optJSONObject("preparation")?.let {
                    BridgeSendPreparation(it.getBoolean("wakeClaimed"), it.getBoolean("catalogValidated"))
                }, json.optString("replacesRequestId").takeUnless { it.isBlank() || it == "null" }, json.getBoolean("installationVerified"), json.getBoolean("remoteTerminalObserved")).also { record ->
                    check(!record.remoteTerminalObserved || record.submission == BridgeSubmission.ACKNOWLEDGED &&
                        record.task.phase in setOf(BridgeSongPhase.FAILED, BridgeSongPhase.CANCELLED, BridgeSongPhase.RETIRING,
                            BridgeSongPhase.IMPORTED)) { "BRIDGE_OUTBOX_INVALID" }
                    check(!record.installationVerified || record.imported && record.outputVerified && record.output != null) { "BRIDGE_OUTBOX_INVALID" }
                    check(record.task.phase != BridgeSongPhase.REMOVED || record.installationVerified) { "BRIDGE_OUTBOX_INVALID" }
                    check(record.replacesRequestId == null || record.approval != null &&
                        Regex("[a-f0-9]{32}").matches(record.replacesRequestId) && record.replacesRequestId != record.task.id) { "BRIDGE_REPLACEMENT_INVALID" }
                    check(!record.outputVerified || record.output != null) { "BRIDGE_OUTBOX_INVALID" }
                    if (record.preparation != null) {
                        check(record.approval != null) { "BRIDGE_APPROVAL_REQUIRED" }
                        if (!record.preparation.catalogValidated) check(record.submission == BridgeSubmission.UNSENT &&
                            !record.inputsVerified && !record.imported && record.output == null && !record.outputVerified &&
                            request.getJSONArray("files").length() == 0) { "BRIDGE_PREPARATION_INVALID" }
                    }
                    if (record.approval != null) record.requireApproval()
                    else check(record.connection == null && record.task.isDraft && !record.inputsVerified && !record.imported && record.output == null &&
                        !record.retired && !record.remoteRetired && record.attempts.isEmpty() && !record.task.cancelRequested &&
                        record.submission == BridgeSubmission.UNSENT && !record.outputVerified &&
                        request.getJSONArray("files").length() == 0) { "BRIDGE_APPROVAL_REQUIRED" }
                }
        }
    }
}

/** One atomic outbox record precedes every external mutation; incomplete writes never replace it. */
internal class BridgeSongStore(val directory: File,
    private val atomicMove: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
    private val readText: (File) -> String = { it.readText(Charsets.UTF_8) }) {
    private var damaged = emptyList<BridgeSongTask>()
    private var index: LinkedHashMap<String, BridgeSongRecord>? = null
    private val unread = linkedSetOf<File>()
    private val bySong = mutableMapOf<Triple<String, String, String>, MutableSet<String>>()
    private val guides = mutableMapOf<String, String>()

    private fun indexRecord(record: BridgeSongRecord) {
        val key = Triple(record.root.libraryId, record.root.treeUri, record.task.videoId)
        index?.get(record.task.id)?.let { previous ->
            if (Triple(previous.root.libraryId, previous.root.treeUri, previous.task.videoId) != key) removeIndexedRecord(previous)
        }
        checkNotNull(index)[record.task.id] = record
        bySong.getOrPut(key) { linkedSetOf() } += record.task.id
    }

    @Synchronized fun record(id: String): BridgeSongRecord? {
        if (index == null || unread.isNotEmpty()) records()
        return index?.get(id)
    }

    @Synchronized fun recordsFor(root: RootAuthority, videoId: String): List<BridgeSongRecord> {
        if (index == null || unread.isNotEmpty()) records()
        return bySong[Triple(root.libraryId, root.treeUri, videoId)].orEmpty()
            .mapNotNull { index?.get(it) }.sortedBy { it.task.createdAtEpochMs }
    }

    private fun retainedGuide(digest: String): String {
        check(Regex("[a-f0-9]{64}").matches(digest)) { "BRIDGE_GUIDE_INVALID" }
        return guides.getOrPut(digest) {
            val file = File(directory, "guide-$digest.md")
            check(!Files.isSymbolicLink(file.toPath())) { "BRIDGE_GUIDE_INVALID" }
            check(!Files.notExists(file.toPath())) { "BRIDGE_GUIDE_MISSING" }
            val bytes = file.inputStream().use { it.readNBytes(1_048_577) }
            check(bytes.size <= 1_048_576) { "BRIDGE_GUIDE_INVALID" }
            val text = String(bytes, Charsets.UTF_8)
            check(guideDigest(text) == digest) { "BRIDGE_GUIDE_INVALID" }
            text
        }
    }

    private fun guideDigest(guide: String): String = MessageDigest.getInstance("SHA-256")
        .digest(guide.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun persistGuide(guide: String): String {
        val digest = guideDigest(guide)
        val file = File(directory, "guide-$digest.md")
        if (!file.exists()) {
            val pending = File(directory, "guide-$digest.new")
            FileOutputStream(pending).use { it.write(guide.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        check(retainedGuide(digest) == guide) { "BRIDGE_GUIDE_INVALID" }
        return digest
    }
    @Synchronized fun records(): List<BridgeSongRecord> {
        if (index == null) {
            val files = if (!directory.exists()) emptyList() else
                directory.listFiles()?.toList() ?: throw java.io.IOException("BRIDGE_OUTBOX_UNAVAILABLE")
            index = linkedMapOf()
            // Unrelated JSON is not an owned request and cannot block authorization.
            unread += files.filter { Regex("[a-f0-9]{32}\\.json").matches(it.name) }
        }
        var readFailure: java.io.IOException? = null
        for (file in unread.toList()) {
            try {
                val record = readRecord(file)
                indexRecord(record)
                unread.remove(file)
            } catch (error: java.io.IOException) {
                readFailure = error
            } catch (error: Exception) {
                val owned = file.isFile && !Files.isSymbolicLink(file.toPath())
                damaged = damaged + BridgeSongTask("damaged:${file.name}", "", "Damaged saved task",
                    if (owned) BridgeSongPhase.OUTBOX_DAMAGED else BridgeSongPhase.OUTBOX_UNKNOWN, "Saved task needs recovery",
                    "The saved record is malformed. Other tasks can continue. Discard removes this local record and its transfer copies after retaining incomplete import inputs. It does not remove any PC task.")
                unread.remove(file)
            }
        }
        readFailure?.let { throw java.io.IOException("BRIDGE_OUTBOX_UNAVAILABLE", it) }
        return checkNotNull(index).values.sortedBy { it.task.createdAtEpochMs }
    }
    private fun readRecord(file: File): BridgeSongRecord {
        check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..1_048_576) { "BRIDGE_OUTBOX_INVALID" }
        val value = JSONObject(readText(file))
        val guide = retainedGuide(value.getString("guideSha256"))
        value.remove("guideSha256")
        value.put("guide", guide)
        return BridgeSongRecord.parse(value).also {
            check(file.name == "${it.task.id}.json") { "BRIDGE_OUTBOX_INVALID" }
        }
    }
    @Synchronized fun damagedTasks(): List<BridgeSongTask> {
        if (index == null || unread.isNotEmpty()) records()
        return damaged.toList()
    }
    private fun damagedRecord(id: String): File {
        check(Regex("damaged:[a-f0-9]{32}\\.json").matches(id)) { "BRIDGE_CLEANUP_REFUSED" }
        val file = File(directory, id.removePrefix("damaged:"))
        check(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile.parentFile == directory.canonicalFile) { "BRIDGE_CLEANUP_REFUSED" }
        try {
            readRecord(file)
            error("BRIDGE_CLEANUP_REFUSED")
        } catch (error: java.io.IOException) { throw error }
        catch (error: Exception) { check(error.message != "BRIDGE_CLEANUP_REFUSED") { "BRIDGE_CLEANUP_REFUSED" } }
        return file
    }
    @Synchronized fun damagedTransferCopies(id: String): List<File> {
        damagedRecord(id)
        val taskId = id.removePrefix("damaged:").removeSuffix(".json")
        return listOf("zip", "md", "input").map { suffix -> File(directory, "$taskId.$suffix") }.onEach {
            check(it.canonicalFile.parentFile == directory.canonicalFile &&
                !Files.isSymbolicLink(it.toPath()) && (!it.exists() || it.isFile)) { "BRIDGE_CLEANUP_REFUSED" }
        }
    }
    @Synchronized fun discardDamaged(id: String) {
        val record = damagedRecord(id)
        // Keep the discoverable damaged record until every exact local copy is retired.
        damagedTransferCopies(id).forEach { Files.deleteIfExists(it.toPath()) }
        Files.delete(record.toPath())
        damaged = damaged.filterNot { it.id == id }
    }
    @Synchronized fun update(id: String, transform: (BridgeSongRecord) -> BridgeSongRecord): BridgeSongRecord {
        val next = transform(checkNotNull(record(id)))
        save(next)
        return checkNotNull(record(id))
    }
    private fun replaceRecord(source: Path, target: Path) {
        val started = System.nanoTime()
        var firstDenial: AccessDeniedException? = null
        while (true) {
            try {
                atomicMove(source, target)
                return
            } catch (denied: AccessDeniedException) {
                val original = firstDenial ?: denied.also { firstDenial = it }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                if (elapsedMs >= 250L || Thread.currentThread().isInterrupted) throw original
                try {
                    // Brief readers can deny replacement; keep the already-synced candidate intact.
                    Thread.sleep(minOf(10L, 250L - elapsedMs))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw original
                }
            }
        }
    }
    @Synchronized fun save(candidate: BridgeSongRecord): Boolean {
        if (index == null || unread.isNotEmpty()) records()
        val prior = index?.get(candidate.task.id)
        val record = if (prior?.task?.cancelRequested == true && !candidate.task.cancelRequested)
            candidate.copy(task = candidate.task.copy(cancelRequested = true)) else candidate
        if (prior != null && CanonicalJson.encode(prior.json()).contentEquals(CanonicalJson.encode(record.json()))) return false
        if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("BRIDGE_OUTBOX_UNAVAILABLE")
        check(Regex("[a-f0-9]{32}").matches(record.task.id)) { "BRIDGE_OUTBOX_INVALID" }
        val validated = BridgeSongRecord.parse(record.json())
        if (prior?.approval != null) {
            check((prior.preparation == null) == (record.preparation == null)) { "BRIDGE_PREPARATION_CHANGED" }
            check(prior.preparation?.catalogValidated != true || record.preparation?.catalogValidated == true) { "BRIDGE_PREPARATION_CHANGED" }
            check(prior.approval == record.approval && prior.guide == record.guide && prior.connection == record.connection) { "BRIDGE_APPROVAL_CHANGED" }
            check(prior.replacesRequestId == record.replacesRequestId) { "BRIDGE_REPLACEMENT_CHANGED" }
            check(record.submission.ordinal >= prior.submission.ordinal) { "BRIDGE_SUBMISSION_CHANGED" }
            // Input preparation materializes only files; every approved instruction stays frozen.
            val comparableRequest = if (prior.submission == BridgeSubmission.UNSENT)
                JSONObject(record.request.toString()).put("files", prior.request.getJSONArray("files")) else record.request
            requireSameBridgeRequest(comparableRequest, prior.request)
            checkBridge(!prior.outputVerified || record.outputVerified && prior.output == record.output,
                BridgeFailureCode.BRIDGE_OUTPUT_CHANGED)
            check(!prior.imported || record.imported) { "BRIDGE_IMPORT_CHANGED" }
        }
        val stored = record.json().put("guideSha256", persistGuide(record.guide)).also { it.remove("guide") }
        val bytes = stored.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 1_048_576) { "BRIDGE_OUTBOX_TOO_LARGE" }
        val pending = File(directory, "${record.task.id}.new")
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            replaceRecord(pending.toPath(), File(directory, "${record.task.id}.json").toPath())
        } catch (error: java.io.IOException) {
            throw java.io.IOException("BRIDGE_OUTBOX_UNAVAILABLE", error)
        }
        indexRecord(validated.copy(guide = retainedGuide(stored.getString("guideSha256"))))
        return true
    }
    private fun removeIndexedRecord(record: BridgeSongRecord) {
        index?.remove(record.task.id)
        val key = Triple(record.root.libraryId, record.root.treeUri, record.task.videoId)
        bySong[key]?.let { ids ->
            ids.remove(record.task.id)
            if (ids.isEmpty()) bySong.remove(key)
        }
    }

    private fun pruneUnusedGuides() {
        if (index == null || unread.isNotEmpty()) records()
        if (damaged.isNotEmpty()) return
        val retained = checkNotNull(index).values.mapTo(hashSetOf()) { guideDigest(it.guide) }
        for (file in directory.listFiles() ?: throw java.io.IOException("BRIDGE_OUTBOX_UNAVAILABLE")) {
            val match = Regex("guide-([a-f0-9]{64})\\.md").matchEntire(file.name) ?: continue
            val digest = match.groupValues[1]
            if (digest !in retained) {
                check(file.isFile && !Files.isSymbolicLink(file.toPath())) { "BRIDGE_GUIDE_INVALID" }
                Files.delete(file.toPath())
                guides.remove(digest)
            }
        }
    }

    @Synchronized fun removeDraft(record: BridgeSongRecord) {
        val current = readRecord(File(directory, "${record.task.id}.json"))
        check(current.task.isDraft && current.approval == null &&
            CanonicalJson.encode(current.json()).contentEquals(CanonicalJson.encode(record.json()))) { "BRIDGE_CLEANUP_REFUSED" }
        Files.delete(File(directory, "${record.task.id}.json").toPath())
        removeIndexedRecord(record)
        pruneUnusedGuides()
    }
    @Synchronized fun removeRetired(record: BridgeSongRecord) {
        val current = readRecord(File(directory, "${record.task.id}.json"))
        check(record.task.phase in setOf(BridgeSongPhase.IMPORTED, BridgeSongPhase.RETIRING) && record.retired && record.remoteRetired &&
            CanonicalJson.encode(current.json()).contentEquals(CanonicalJson.encode(record.json()))) {
            "BRIDGE_CLEANUP_REFUSED"
        }
        check(File(directory, "${record.task.id}.json").delete()) { "BRIDGE_CLEANUP_PENDING" }
        removeIndexedRecord(record)
        pruneUnusedGuides()
    }
    /** Caller has read the installed song back and preserved any incomplete-import dependencies. */
    @Synchronized fun removeUnavailableDelivery(record: BridgeSongRecord) {
        val file = File(directory, "${record.task.id}.json")
        val current = readRecord(file)
        check(current.imported && current.outputVerified && current.output != null &&
            (current.task.phase == BridgeSongPhase.EXPIRED || current.task.phase == BridgeSongPhase.REMOVED && current.installationVerified) &&
            CanonicalJson.encode(current.json()).contentEquals(CanonicalJson.encode(record.json()))) {
            "BRIDGE_CLEANUP_REFUSED"
        }
        for (suffix in listOf("zip", "md", "input")) Files.deleteIfExists(this.file(record.task.id, suffix).toPath())
        Files.delete(file.toPath())
        removeIndexedRecord(record)
        pruneUnusedGuides()
    }
    @Synchronized fun removeTransferCopies(record: BridgeSongRecord) {
        val current = readRecord(File(directory, "${record.task.id}.json"))
        check(current.task.phase == BridgeSongPhase.IMPORTED || current.task.phase == BridgeSongPhase.RETIRING && current.remoteRetired) { "BRIDGE_CLEANUP_REFUSED" }
        requireSameBridgeRequest(current.request, record.request)
        for (suffix in listOf("zip", "md", "input")) Files.deleteIfExists(file(record.task.id, suffix).toPath())
    }
    fun file(id: String, suffix: String): File {
        check(Regex("[a-f0-9]{32}").matches(id) && suffix in setOf("md", "zip", "input"))
        check(directory.isDirectory || directory.mkdirs())
        return File(directory, "$id.$suffix")
    }
}

internal fun newBridgeSong(root: RootAuthority, originalText: String, guide: String, now: Long): BridgeSongRecord {
    val videoId = bridgeVideoId(originalText)
    val id = UUID.randomUUID().toString().replace("-", "")
    val instructions = """
        StemWise Add a song typed protocol v1 for exact YouTube slot $videoId; authorization is scoped to this song.
        Retrieve the declared stemwise-song.json and SEND_TO_AGENTS.md through the authenticated bridge handoff and verify both descriptors.
        In the host's configured StemWise project invoke PC side/STEMWISE_BRIDGE.ps1 with -ContextFile, -GuideFile and the live handoff -MaxFileBytes.
        The typed dispatcher owns acquisition, selected-recording resolution, progress and verified package handoff. Reinvoke it after reconnect; never recreate accepted work.
        Add -Inspect to read current structured progress while its operation runs; inspection admits no new work.
        Use SEND_TO_AGENTS.md only for required editorial reasoning and its normal typed review/Validate actions.
        Wrong or uncertain wording, pronunciation or performed occurrences withhold affected lyric claims and dependent features. Validate may complete independently verified audio with truthful partial, plain or unavailable lyrics.
        Recording identity, time-base and package integrity failures still block publication.
        Copy the dispatcher's status/code into the exact bridge job report. Only completed/stemwise_song_ready may deliver its exact verified package.
        Queue only that package using the authenticated producer, persist its stable output ID and copy the queue reply's descriptor unchanged. Omit result and extra files.
        Respect native permissions, immutable requests and every existing content/publication gate. A size refusal requires manual import of the preserved full-quality PC ZIP.
    """.trimIndent()
    val request = JSONObject().put("requestId", id).put("createdAt", now).put("title", "Add song · $videoId")
        .put("text", originalText).put("instructions", instructions).put("files", JSONArray()).put("model", "").put("effort", "").put("serviceTier", "")
    return BridgeSongRecord(root, guide, request, BridgeSongTask(id, videoId, "Add song · $videoId", BridgeSongPhase.DRAFT, "Waiting for authorization", createdAtEpochMs = now, sourceUrl = originalText))
}

internal fun approveBridgeSong(draft: BridgeSongRecord, submission: BridgeSongSubmission, guide: String, now: Long,
    connection: BridgeConnectionProfile): BridgeSongRecord {
    check(draft.task.isDraft && draft.approval == null) { "This job has already been authorized" }
    val fresh = newBridgeSong(draft.root, submission.link, guide, now)
    val request = JSONObject(fresh.request.toString()).put("requestId", draft.task.id)
        .put("model", submission.model).put("effort", submission.effort).put("serviceTier", submission.serviceTier)
    if (submission.serviceTier.isEmpty()) request.put("nativeDefaultServiceTier", true)
    return fresh.copy(request = request, approval = submission, connection = connection,
        task = fresh.task.copy(id = draft.task.id, phase = BridgeSongPhase.QUEUED, stage = "Queued")).also { it.requireApproval() }
}

internal fun bridgeSongContext(record: BridgeSongRecord): ByteArray = CanonicalJson.encode(JSONObject()
    .put("formatVersion", 1).put("kind", "stemwise.add-song").put("requestId", record.task.id)
    .put("videoId", record.task.videoId).put("sourceUrl", record.request.getString("text").trim())
    .put("guideSha256", MessageDigest.getInstance("SHA-256").digest(record.guide.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }))

internal fun requireSameBridgeRequest(actual: JSONObject, expected: JSONObject) {
    checkBridge(CanonicalJson.encode(actual).contentEquals(CanonicalJson.encode(expected)), BridgeFailureCode.BRIDGE_REQUEST_CONFLICT)
}

internal fun requireSameBridgeInput(actual: JSONObject, expected: JSONObject) {
    val input = JSONObject(actual.toString())
    // The current Bridge wire omits an empty optional service tier; the frozen local request keeps it.
    if (!input.has("serviceTier") && expected.opt("serviceTier") == "") input.put("serviceTier", "")
    requireSameBridgeRequest(input, expected)
}
