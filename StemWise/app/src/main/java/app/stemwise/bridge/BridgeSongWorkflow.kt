package app.stemwise.bridge

import app.stemwise.data.RootAuthority
import app.stemwise.enrichment.EnrichmentImportException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class BridgeFailureDisposition { RETRY, WAIT, EXPIRED, REJECT }

internal enum class BridgeSongWorkProgress { COMPLETE, PENDING, RETRY }

/** The next effect follows durable facts; display labels never authorize an effect or a retry. */
internal enum class BridgeSongAction { STOP, RETIRE, CANCEL_LOCAL, FINISH_LOCAL, CANCEL_REMOTE, PREPARE_INPUTS, OBSERVE }

internal fun bridgeSongAction(record: BridgeSongRecord, localOutputAvailable: Boolean): BridgeSongAction = when {
    record.approval == null -> BridgeSongAction.STOP
    record.task.phase == BridgeSongPhase.RETIRING -> BridgeSongAction.RETIRE
    record.task.terminal -> BridgeSongAction.STOP
    record.preparation?.catalogValidated == false && !record.task.cancelRequested -> BridgeSongAction.STOP
    record.task.cancelRequested && record.submission == BridgeSubmission.UNSENT -> BridgeSongAction.CANCEL_LOCAL
    (!record.task.cancelRequested || record.imported) && record.output != null && localOutputAvailable -> BridgeSongAction.FINISH_LOCAL
    record.task.cancelRequested -> BridgeSongAction.CANCEL_REMOTE
    record.submission == BridgeSubmission.UNSENT -> BridgeSongAction.PREPARE_INPUTS
    else -> BridgeSongAction.OBSERVE
}

internal fun bridgeFailureDisposition(error: Exception): BridgeFailureDisposition = when (error) {
    is BridgeLocalFailure -> error.code.disposition
    is app.stemwise.data.LibraryRootException, is SecurityException -> BridgeFailureDisposition.WAIT
    is EnrichmentImportException -> when (error.code) {
        "LIBRARY_CHANGED", "ROOT_UNAVAILABLE", "RECOVERY_PENDING", "BUNDLE_RECOVERY_REQUIRED" -> BridgeFailureDisposition.WAIT
        "STORAGE_RESERVE_LOW", "LIBRARY_STORAGE_LOW", "EXTERNAL_COMMIT_FAILED", "BUNDLE_READBACK_FAILED" -> BridgeFailureDisposition.RETRY
        else -> BridgeFailureDisposition.REJECT
    }
    is BridgeApiFailure -> when {
        error.code in setOf("app_registration_changed", "app_request_cancelled") -> BridgeFailureDisposition.WAIT
        error.code in setOf("request_expired_or_future", "request_deleted", "creation_request_expired") -> BridgeFailureDisposition.EXPIRED
        error.retryable || error.status >= 500 || error.status == 429 || error.code == "app_capacity_reached" -> BridgeFailureDisposition.RETRY
        else -> BridgeFailureDisposition.WAIT
    }
    else -> BridgeFailureDisposition.RETRY
}

private sealed interface BridgeRemoteDecision {
    val task: BridgeSongTask
    data class Pending(override val task: BridgeSongTask) : BridgeRemoteDecision
    data class Ended(override val task: BridgeSongTask) : BridgeRemoteDecision
    data class Output(override val task: BridgeSongTask, val file: BridgeFile) : BridgeRemoteDecision
}

internal const val BRIDGE_CURRENT_MESSAGE_MAX_CHARS = 65_536

internal fun retainBridgeMessage(previous: String?, message: String): String {
    // Retain one observation separately from the latest local failure, never nested failure history.
    val diagnostics = "\n\nBridge diagnostics:\n"
    val observation = "\n\nCurrent Bridge observation:\n"
    val localDetails = "\n\nLatest local diagnostic:\n"
    val remote = when {
        previous?.contains(observation) == true -> previous.substringAfter(observation).substringBefore(localDetails)
        previous?.contains(diagnostics) == true -> previous
        else -> null
    }?.take(BRIDGE_CURRENT_MESSAGE_MAX_CHARS / 2)
    val current = if (remote == null) message else buildString {
        append(message.substringBefore(diagnostics))
        append(observation)
        append(remote)
        if (diagnostics in message) {
            append(localDetails)
            append(message.substringAfter(diagnostics))
        }
    }
    return current.take(BRIDGE_CURRENT_MESSAGE_MAX_CHARS)
}

private fun bridgeRemoteMessage(remote: JSONObject): String? {
    val state = remote.getString("state")
    val code = remote.optString("code")
    val report = remote.optJSONObject("report")
    val ended = state in setOf("failed", "rejected", "cancelled", "interrupted") ||
        state == "completed" && report?.optString("status") == "failed"
    return buildList {
        if (ended) {
            code.takeIf(String::isNotBlank)?.let(::add)
            report?.optString("code")?.takeIf(String::isNotBlank)?.let(::add)
        }
        remote.optJSONObject("issue")?.let { issue ->
            add(listOf("code", "message", "action").mapNotNull { key ->
                issue.optString(key).takeIf(String::isNotBlank)
            }.joinToString("\n"))
        }
        remote.optString("diagnostic").takeIf(String::isNotBlank)?.let(::add)
    }.filter(String::isNotBlank).distinct().joinToString("\n\n")
        .take(BRIDGE_CURRENT_MESSAGE_MAX_CHARS).takeIf(String::isNotBlank)
}

/** Parse and validate one exact remote observation without performing or committing work. */
private fun bridgeRemoteDecision(record: BridgeSongRecord, remote: JSONObject): BridgeRemoteDecision {
    val task = record.task.copy(chatId = remote.optString("threadId").takeIf(String::isNotBlank),
        chatName = remote.optString("developer").takeIf(String::isNotBlank))
    val state = remote.getString("state")
    val code = remote.optString("code")
    val report = remote.optJSONObject("report")
    val ended = state in setOf("failed", "rejected", "cancelled", "interrupted") ||
        state == "completed" && report?.optString("status") == "failed"
    val message = bridgeRemoteMessage(remote)
    val observedTask = task.copy(message = message)
    if (ended) {
        return BridgeRemoteDecision.Ended(observedTask.copy(
            phase = if (state == "cancelled") BridgeSongPhase.CANCELLED else BridgeSongPhase.FAILED,
            stage = "PC task ended", retryable = false,
            message = message ?: "The PC did not produce a song package."))
    }
    if (state != "completed" || report?.optString("status") != "completed" || remote.optJSONArray("outputs")?.length() == 0) {
        val waiting = state in setOf("unknown", "completed") || report?.optString("status") == "waiting" || remote.optJSONObject("issue") != null
        return BridgeRemoteDecision.Pending(observedTask.copy(phase = if (waiting) BridgeSongPhase.WAITING else BridgeSongPhase.RUNNING,
            stage = bridgeStageLabel(report?.optString("code").orEmpty().ifBlank { code }),
            retryable = waiting))
    }
    checkBridge(report.getLong("revision") > 0 && report.getString("code") == "stemwise_song_ready", BridgeFailureCode.BRIDGE_SONG_REPORT_INVALID)
    val outputs = remote.getJSONArray("outputs")
    checkBridge(outputs.length() == 1, BridgeFailureCode.BRIDGE_SONG_OUTPUT_INVALID)
    val output = BridgeFile.parse(outputs.getJSONObject(0))
    checkBridge(output.name.endsWith(".zip"), BridgeFailureCode.BRIDGE_SONG_OUTPUT_INVALID)
    checkBridge(record.output == null || record.output == output, BridgeFailureCode.BRIDGE_OUTPUT_CHANGED)
    return BridgeRemoteDecision.Output(observedTask, output)
}

private fun bridgeCancellationRetired(remote: JSONObject): Boolean {
    fun timestamp(name: String): Long {
        val value = remote.get(name)
        checkBridge(value is Int || value is Long, BridgeFailureCode.BRIDGE_CANCEL_RECEIPT_INVALID)
        return (value as Number).toLong().also { checkBridge(it >= 0L, BridgeFailureCode.BRIDGE_CANCEL_RECEIPT_INVALID) }
    }
    val requested = timestamp("cancelRequested")
    val retired = timestamp("cancelRetired")
    return remote.getString("state") in setOf("completed", "failed", "rejected", "cancelled", "interrupted") &&
        (requested == 0L || retired > 0L)
}

internal interface SongBridgeTransport {
    val networkAvailable: Boolean get() = true
    suspend fun api(method: String, path: String, body: JSONObject? = null): JSONObject = error("BRIDGE_CONTROL_UNSUPPORTED")
    suspend fun status(): JSONObject = error("BRIDGE_CONTROL_UNSUPPORTED")
    suspend fun login(): Unit = error("BRIDGE_CONTROL_UNSUPPORTED")
    suspend fun ensureApp(): Unit = error("BRIDGE_CONTROL_UNSUPPORTED")
    suspend fun pairAgain(): Unit = error("BRIDGE_CONTROL_UNSUPPORTED")
    suspend fun prepareInput(source: File, name: String): BridgeFile
    suspend fun ensureInput(file: BridgeFile, source: File)
    suspend fun job(id: String): JSONObject?
    suspend fun cancelJob(id: String): JSONObject = BridgeJobObservationCache().complete(id,
        api("POST", "/v2/jobs/$id/cancel"),
        readInputs = { api("GET", "/v2/jobs/$id/inputs") }, readLog = { api("GET", "/v2/jobs/$id/log") })
    suspend fun retireMissingJob(id: String): JSONObject = api("POST", "/v2/jobs/$id/retire")
    suspend fun submit(request: JSONObject): JSONObject
    suspend fun receiveRetained(file: BridgeFile, destination: File): Boolean = false
    suspend fun receive(file: BridgeFile, destination: File)
    suspend fun acknowledge(id: String, file: BridgeFile, destination: File)
    suspend fun deleteJob(id: String)
    suspend fun discardTransfers(record: BridgeSongRecord)
}

/** Remote completion, transferred bytes, canonical import and delivery acknowledgement are separate commits. */
internal class BridgeSongWorkflow(
    private val store: BridgeSongStore,
    private val transportFor: (BridgeConnectionProfile) -> SongBridgeTransport,
    private val requireRoot: (RootAuthority) -> Unit,
    private val install: suspend (File, String, RootAuthority) -> Unit,
    private val verifyInstalled: suspend (String, RootAuthority) -> Unit,
    private val changed: () -> Unit,
    private val preserveArchive: suspend (File) -> Unit,
    private val explicitlyDeleted: suspend (String, RootAuthority) -> Boolean = { _, _ -> false },
    private val withDeliveryConfirmation: suspend (String, RootAuthority, suspend () -> Unit) -> Unit = { _, _, confirm -> confirm() }
) {
    suspend fun settleExplicitDeletion(id: String, root: RootAuthority): Boolean {
        val record = store.record(id) ?: return false
        if (record.task.phase == BridgeSongPhase.REMOVED) return true
        if (!record.installationVerified || record.task.phase !in setOf(BridgeSongPhase.ACKNOWLEDGING,
                BridgeSongPhase.RETRYING, BridgeSongPhase.WAITING, BridgeSongPhase.EXPIRED) ||
            record.root.libraryId != root.libraryId || record.root.treeUri != root.treeUri) return false
        requireRoot(root)
        if (!explicitlyDeleted(record.task.videoId, root)) return false
        requireRoot(root)
        if (store.save(record.copy(root = root, task = record.task.copy(phase = BridgeSongPhase.REMOVED,
                stage = "Song removed from Library", retryable = false,
                message = "The song was explicitly deleted. Delivery acknowledgement was not confirmed. Forget retires local tracking; remote files retain their normal expiry.")))) changed()
        return true
    }
    /** The repository holds the local operation owner and exact task mutex before rebinding. */
    fun resumeForLibrary(id: String, root: RootAuthority): Boolean {
        requireRoot(root)
        val record = store.records().singleOrNull { it.task.id == id } ?: return false
        if (record.approval == null || record.task.terminal || record.root.libraryId != root.libraryId || record.root.treeUri != root.treeUri) return false
        if (record.root != root && store.save(record.copy(root = root))) changed()
        return true
    }

    /** The local cancellation latch precedes every cancel RPC and survives observer/process loss. */
    fun requestCancellation(id: String) {
        store.update(id) { record ->
            if (record.approval == null || !record.resumableTask.canCancel || record.imported) record
            else record.copy(task = record.task.copy(cancelRequested = true,
                phase = BridgeSongPhase.CANCELLING, stage = "Cancellation requested", retryable = false,
                message = "Waiting for the PC to confirm that this task has stopped."))
        }
        changed()
    }

    suspend fun advance(id: String): BridgeSongWorkProgress {
        val record = store.records().single { it.task.id == id }
        try {
            if (settleExplicitDeletion(id, record.root)) return BridgeSongWorkProgress.COMPLETE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: java.io.IOException) { return BridgeSongWorkProgress.RETRY }
        catch (error: BridgeLocalFailure) {
            if (error.code.disposition == BridgeFailureDisposition.WAIT) return BridgeSongWorkProgress.RETRY
            throw error
        } catch (_: app.stemwise.data.LibraryRootException) { return BridgeSongWorkProgress.RETRY }
        catch (_: SecurityException) { return BridgeSongWorkProgress.RETRY }
        val action = bridgeSongAction(record, record.output != null && store.file(id, "zip").isFile)
        if (action == BridgeSongAction.STOP) return BridgeSongWorkProgress.COMPLETE
        record.requireApproval()
        if (action == BridgeSongAction.RETIRE) return if (retire(id)) BridgeSongWorkProgress.COMPLETE else BridgeSongWorkProgress.RETRY
        return Attempt(record).run(action)
    }

    /** One attempt runs effects and commits through the existing atomic record writer. */
    private inner class Attempt(var record: BridgeSongRecord) {
        private val transport = transportFor(requireNotNull(record.connection))
        private val id = record.task.id
        private val started = System.nanoTime()
        private val history = record.attempts
        private val attempt = BridgeAttempt((record.attempts.lastOrNull()?.number ?: 0) + 1,
            System.currentTimeMillis(), 0L, record.task.state, "running")
        private var verifiedBytes = if (record.imported) record.output?.size ?: 0L else 0L
        private var cancelIssued = false

        private fun save(next: BridgeSongRecord) {
            val saved = store.save(next)
            record = store.records().single { it.task.id == id }
            if (saved) changed()
        }
        private fun diagnostic(outcome: String, error: Exception? = null, disposition: BridgeFailureDisposition? = null,
            stage: String = record.task.state): List<BridgeAttempt> =
            (history + attempt.copy(
                elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0), stage = stage,
                outcome = outcome, disposition = disposition, cause = error?.let { failure ->
                    val rootCause = generateSequence<Throwable>(failure) { it.cause }.take(8).last()
                    (rootCause.toString().take(512) + "\n" + failure.stackTraceToString()).take(4096)
                }, verifiedTransferBytes = verifiedBytes)).takeLast(8)

        private fun stage(phase: BridgeSongPhase, label: String) = save(record.copy(task = record.task.copy(
            phase = phase, stage = label, retryable = false), attempts = diagnostic("running", stage = phase.name)))

        suspend fun run(action: BridgeSongAction): BridgeSongWorkProgress {
            return try {
                if (action == BridgeSongAction.OBSERVE && record.output != null) {
                    val destination = store.file(id, "zip")
                    if (!destination.exists()) {
                        requireRoot(record.root)
                        if (transport.receiveRetained(checkNotNull(record.output), destination)) return finishLocalOutput()
                    }
                }
                when (action) {
                    BridgeSongAction.CANCEL_LOCAL -> {
                        save(record.copy(remoteRetired = true, task = record.task.copy(phase = BridgeSongPhase.CANCELLED,
                            stage = "Cancelled before submission", retryable = false, message = null),
                            attempts = diagnostic("complete", stage = "CANCELLED")))
                        BridgeSongWorkProgress.COMPLETE
                    }
                    BridgeSongAction.FINISH_LOCAL -> finishLocalOutput()
                    BridgeSongAction.CANCEL_REMOTE -> cancelRemote()
                    BridgeSongAction.PREPARE_INPUTS, BridgeSongAction.OBSERVE -> {
                        if (action == BridgeSongAction.PREPARE_INPUTS) prepareInputs()
                        record = store.records().single { it.task.id == id }
                        if (record.task.cancelRequested) return run(bridgeSongAction(record, false))
                        val remote = transport.job(id) ?: run {
                            record = store.records().single { it.task.id == id }
                            if (record.task.cancelRequested) return run(bridgeSongAction(record, false))
                            if (record.submission != BridgeSubmission.UNSENT) return@run transport.retireMissingJob(id)
                            requireRoot(record.root)
                            // A lost response or process death after this commit can only reconcile this exact request.
                            save(record.copy(submission = BridgeSubmission.UNKNOWN))
                            transport.submit(record.request)
                        }
                        applyRemote(remote)
                    }
                    BridgeSongAction.STOP, BridgeSongAction.RETIRE -> error("Unexpected effect $action")
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    try {
                        save(record.copy(task = if (record.task.terminal) record.task else record.task.copy(
                            phase = if (record.task.cancelRequested) BridgeSongPhase.CANCELLING else BridgeSongPhase.WAITING,
                            stage = "Song task interrupted", retryable = true,
                            message = if (record.task.cancelRequested) "Cancellation is still pending. Resume to check the same PC task."
                                else "Return to this library to resume the saved PC task."),
                            attempts = diagnostic("cancelled", cancelled)))
                    } catch (writeError: Exception) { cancelled.addSuppressed(writeError) }
                }
                throw cancelled
            } catch (error: Exception) {
                failed(error)
            }

        }

        private suspend fun prepareInputs() {
            requireRoot(record.root)
            val inputs = listOf("md" to "SEND_TO_AGENTS.md", "input" to "stemwise-song.json")
            for ((index, input) in inputs.withIndex()) {
                record = store.records().single { it.task.id == id }
                if (record.task.cancelRequested) return
                val source = store.file(id, input.first)
                source.writeBytes(
                    if (index == 0) record.guide.toByteArray(Charsets.UTF_8) else bridgeSongContext(record))
                if (record.request.getJSONArray("files").length() <= index) {
                    val descriptor = transport.prepareInput(source, input.second)
                    checkBridge(descriptor.name == input.second && descriptor.matches(source), BridgeFailureCode.BRIDGE_INPUT_CHANGED)
                    val files = JSONArray(record.request.getJSONArray("files").toString()).put(descriptor.json())
                    save(record.copy(request = JSONObject(record.request.toString()).put("files", files)))
                }
            }
            // Uploaded inputs can expire before admission; only definitely-unsent work may restore them.
            stage(BridgeSongPhase.SUBMITTING, "Sending song instructions")
            checkBridge(record.request.getJSONArray("files").length() == inputs.size, BridgeFailureCode.BRIDGE_INPUT_CHANGED)
            for (index in inputs.indices) {
                record = store.records().single { it.task.id == id }
                if (record.task.cancelRequested) return
                val input = BridgeFile.parse(record.request.getJSONArray("files").getJSONObject(index))
                val source = store.file(id, inputs[index].first)
                checkBridge(input.name == inputs[index].second && input.matches(source), BridgeFailureCode.BRIDGE_INPUT_CHANGED)
                transport.ensureInput(input, source)
            }
            if (!record.inputsVerified) save(record.copy(inputsVerified = true))
            requireRoot(record.root)
        }

        private suspend fun cancelRemote(): BridgeSongWorkProgress {
            check(!cancelIssued) { "BRIDGE_CANCEL_ALREADY_OBSERVED" }
            cancelIssued = true
            val remote = try { transport.cancelJob(id) }
            catch (error: BridgeApiFailure) {
                if (error.status != 404 || error.code != "job_unavailable") throw error
                return applyRemote(transport.retireMissingJob(id))
            }
            checkBridge(remote.getString("id") == id, BridgeFailureCode.BRIDGE_JOB_IDENTITY_INVALID)
            requireSameBridgeInput(remote.getJSONObject("input"), record.request)
            if (!bridgeCancellationRetired(remote)) {
                val observed = bridgeRemoteMessage(remote)
                save(record.copy(task = record.task.copy(phase = BridgeSongPhase.CANCELLING,
                    stage = "Waiting for PC task retirement", retryable = false,
                    message = retainBridgeMessage(observed ?: record.task.message,
                        "Cancellation is pending. The original task will not be resubmitted.")),
                    attempts = diagnostic("complete", stage = "CANCELLING")))
                return BridgeSongWorkProgress.PENDING
            }
            return applyRemote(remote, cancellationRetired = true)
        }

        private suspend fun applyRemote(remote: JSONObject, cancellationRetired: Boolean = false): BridgeSongWorkProgress {
            checkBridge(remote.getString("id") == id, BridgeFailureCode.BRIDGE_JOB_IDENTITY_INVALID)
            requireSameBridgeInput(remote.getJSONObject("input"), record.request)
            if (record.submission != BridgeSubmission.ACKNOWLEDGED) save(record.copy(submission = BridgeSubmission.ACKNOWLEDGED))
            val observedMessage = bridgeRemoteMessage(remote)
            if (record.task.message != observedMessage) save(record.copy(task = record.task.copy(message = observedMessage)))
            val decision = bridgeRemoteDecision(record, remote)
            return when (decision) {
                is BridgeRemoteDecision.Ended -> {
                    save(record.copy(task = decision.task, remoteTerminalObserved = true,
                        attempts = diagnostic("failed", IllegalStateException(decision.task.message), BridgeFailureDisposition.REJECT)))
                    BridgeSongWorkProgress.COMPLETE
                }
                is BridgeRemoteDecision.Pending -> {
                    record = store.records().single { it.task.id == id }
                    if (record.task.cancelRequested && cancellationRetired) {
                        save(record.copy(task = decision.task.copy(phase = BridgeSongPhase.CANCELLED,
                            stage = "PC task ended without a usable song", retryable = false,
                            message = retainBridgeMessage(decision.task.message,
                                "The PC task has ended without a usable song package. The original request will not be sent again.")),
                            remoteTerminalObserved = true,
                            attempts = diagnostic("complete", stage = "CANCELLED")))
                        return BridgeSongWorkProgress.COMPLETE
                    }
                    if (record.task.cancelRequested && !cancelIssued) return cancelRemote()
                    if (decision.task != record.task) save(record.copy(task = decision.task,
                        attempts = diagnostic("complete", stage = decision.task.state)))
                    BridgeSongWorkProgress.PENDING
                }
                is BridgeRemoteDecision.Output -> {
                    save(record.copy(task = decision.task, output = decision.file))
                    val destination = store.file(id, "zip")
                    stage(BridgeSongPhase.RECEIVING, "Receiving song ZIP")
                    val retained = destination.exists()
                    if (!retained) transport.receive(decision.file, destination)
                    checkBridge(decision.file.matches(destination), if (retained) BridgeFailureCode.BRIDGE_LOCAL_OUTPUT_CHANGED
                        else BridgeFailureCode.BRIDGE_LOCAL_OUTPUT_UNVERIFIED)
                    finishLocalOutput()
                }
            }
        }

        private suspend fun finishLocalOutput(): BridgeSongWorkProgress {
            val output = checkNotNull(record.output)
            val destination = store.file(id, "zip")
            checkBridge(output.matches(destination), BridgeFailureCode.BRIDGE_LOCAL_OUTPUT_CHANGED)
            verifiedBytes = output.size
            if (!record.outputVerified) save(record.copy(outputVerified = true))
            requireRoot(record.root)
            if (!record.imported) {
                stage(BridgeSongPhase.IMPORTING, "Validating and adding song")
                install(destination, record.task.videoId, record.root)
                save(record.copy(imported = true))
            }
            verifyInstalled(record.task.videoId, record.root)
            if (!record.installationVerified) save(record.copy(installationVerified = true))
            stage(BridgeSongPhase.ACKNOWLEDGING, "Song saved; confirming delivery")
            if (!transport.networkAvailable) return BridgeSongWorkProgress.RETRY
            try {
                withDeliveryConfirmation(record.task.videoId, record.root) {
                    transport.acknowledge(id, output, destination)
                }
            } catch (error: BridgeApiFailure) {
                if (error.status != 404 || error.code != "job_unavailable") throw error
                val remote = transport.retireMissingJob(id)
                checkBridge(remote.getString("id") == id, BridgeFailureCode.BRIDGE_JOB_IDENTITY_INVALID)
                requireSameBridgeInput(remote.getJSONObject("input"), record.request)
                // A retained remote request is not a delivery acknowledgement; keep observing it.
                throw error
            }
            save(record.copy(task = record.task.copy(phase = BridgeSongPhase.IMPORTED, stage = "Song added", retryable = false),
                attempts = diagnostic("complete")))
            preserveArchive(destination)
            store.removeTransferCopies(record)
            return BridgeSongWorkProgress.COMPLETE
        }

        private fun failed(error: Exception): BridgeSongWorkProgress {
            val failure = retainBridgeMessage(record.task.message, bridgeFailureMessage(error))
            if (record.task.phase == BridgeSongPhase.IMPORTED) {
                save(record.copy(task = record.task.copy(message = retainBridgeMessage(failure, "Song added; local transfer cleanup needs attention.")),
                    attempts = diagnostic("failed", error, BridgeFailureDisposition.WAIT)))
                return BridgeSongWorkProgress.COMPLETE
            }
            val disposition = bridgeFailureDisposition(error)
            if (record.imported) {
                val unavailable = disposition == BridgeFailureDisposition.EXPIRED
                save(record.copy(task = record.task.copy(phase = if (unavailable) BridgeSongPhase.EXPIRED else BridgeSongPhase.ACKNOWLEDGING,
                    stage = "Song added", retryable = !unavailable,
                    message = retainBridgeMessage(failure, if (unavailable)
                        "Delivery confirmation is unavailable. Forget this task to retire local tracking; the song and recovery inputs are kept."
                        else "Delivery confirmation is pending.")),
                    attempts = diagnostic("failed", error, disposition)))
                return if (disposition == BridgeFailureDisposition.RETRY) BridgeSongWorkProgress.RETRY
                    else BridgeSongWorkProgress.COMPLETE
            }
            if (record.task.cancelRequested && disposition != BridgeFailureDisposition.EXPIRED) {
                val missing = error is BridgeApiFailure && error.status == 404 && error.code == "job_unavailable"
                save(record.copy(task = record.task.copy(phase = if (missing) BridgeSongPhase.WAITING else BridgeSongPhase.CANCELLING, retryable = true,
                    stage = if (missing) "Original PC task unavailable" else "Cancellation needs confirmation",
                    message = if (missing) retainBridgeMessage(failure, "Cancellation could not be confirmed. Resume to reconcile the original task; it will not be sent again.")
                        else failure), attempts = diagnostic("failed", error, disposition)))
                return if (missing) BridgeSongWorkProgress.COMPLETE else BridgeSongWorkProgress.RETRY
            }
            val phase = when (disposition) {
                BridgeFailureDisposition.RETRY -> BridgeSongPhase.RETRYING
                BridgeFailureDisposition.WAIT -> BridgeSongPhase.WAITING
                BridgeFailureDisposition.EXPIRED -> BridgeSongPhase.EXPIRED
                BridgeFailureDisposition.REJECT -> BridgeSongPhase.FAILED
            }
            try {
                val trackingRetired = error is BridgeApiFailure && error.status == 410 && error.code == "request_deleted"
                save(record.copy(remoteRetired = record.remoteRetired || trackingRetired, task = record.task.copy(phase = phase,
                    stage = if (trackingRetired) "Bridge request tracking retired" else if (disposition == BridgeFailureDisposition.EXPIRED) "Bridge request expired" else record.task.stage,
                    message = if (trackingRetired) retainBridgeMessage(failure, "Request tracking retired; historical execution outcome unknown.") else failure,
                    retryable = disposition in setOf(BridgeFailureDisposition.RETRY, BridgeFailureDisposition.WAIT)),
                    attempts = diagnostic("failed", error, disposition)))
            } catch (writeError: Exception) {
                if (writeError !== error) error.addSuppressed(writeError)
                throw error
            }
            return if (disposition == BridgeFailureDisposition.RETRY) BridgeSongWorkProgress.RETRY else BridgeSongWorkProgress.COMPLETE
        }
    }

    suspend fun retire(id: String, currentRoot: RootAuthority? = null): Boolean {
        var record = store.record(id) ?: return true
        if (record.task.isDraft) {
            store.removeDraft(record)
            changed()
            return true
        }
        record.requireApproval()
        if (record.imported && record.task.phase in setOf(BridgeSongPhase.EXPIRED, BridgeSongPhase.REMOVED)) {
            val root = currentRoot ?: record.root
            checkBridge(root.libraryId == record.root.libraryId && root.treeUri == record.root.treeUri,
                BridgeFailureCode.LIBRARY_CHANGED)
            requireRoot(root)
            if (record.task.phase != BridgeSongPhase.REMOVED) {
                if (!settleExplicitDeletion(id, root)) verifyInstalled(record.task.videoId, root)
                else record = checkNotNull(store.record(id))
            }
            requireRoot(root)
            if (record.root != root) {
                record = record.copy(root = root)
                store.save(record)
            }
            preserveArchive(store.file(id, "zip"))
            store.removeUnavailableDelivery(record)
            changed()
            return true
        }
        val transport = transportFor(requireNotNull(record.connection))
        check(record.task.phase == BridgeSongPhase.IMPORTED || record.task.canForget || record.task.phase == BridgeSongPhase.RETIRING) {
            "BRIDGE_CLEANUP_REFUSED"
        }
        fun save(next: BridgeSongRecord) { val saved = store.save(next); record = next; if (saved) changed() }
        if (record.task.canForget) save(record.copy(task = record.task.copy(
            phase = BridgeSongPhase.RETIRING, stage = "Removing bridge task", retryable = false)))
        try {
            if (!record.remoteRetired && record.submission == BridgeSubmission.UNSENT) save(record.copy(remoteRetired = true))
            if (!record.remoteRetired) {
                try {
                    val remote = transport.job(id) ?: transport.retireMissingJob(id)
                    checkBridge(remote.getString("id") == id, BridgeFailureCode.BRIDGE_JOB_IDENTITY_INVALID)
                    requireSameBridgeInput(remote.getJSONObject("input"), record.request)
                    checkBridge(remote.getString("state") in setOf("completed", "failed", "rejected", "cancelled", "interrupted"),
                        BridgeFailureCode.BRIDGE_REMOTE_OUTCOME_UNRESOLVED)
                    save(record.copy(submission = BridgeSubmission.ACKNOWLEDGED, remoteTerminalObserved = true))
                    // DELETE independently rejects an active job if the observation raced with a remote change.
                    transport.deleteJob(id)
                } catch (error: BridgeApiFailure) {
                    val deleted = error.status == 410 && error.code == "request_deleted"
                    // Receiver loss cannot retire work whose execution outcome was never observed.
                    val endedReceiver = error.status == 409 && error.code == "app_registration_required" &&
                        record.remoteTerminalObserved
                    if (!deleted && !endedReceiver) throw error
                }
                save(record.copy(remoteRetired = true))
            }
            preserveArchive(store.file(id, "zip"))
            transport.discardTransfers(record)
            store.removeTransferCopies(record)
            save(record.copy(retired = true))
            store.removeRetired(record)
            changed()
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            save(record.copy(task = record.task.copy(message = retainBridgeMessage(record.task.message, bridgeFailureMessage(error)),
                retryable = record.task.phase == BridgeSongPhase.RETIRING)))
            return false
        }
    }
}

internal fun bridgeStageLabel(code: String): String = when (code) {
    "stemwise_source" -> "Preparing exact recording"
    "stemwise_lyrics" -> "Preparing lyrics and song details"
    "stemwise_alignment" -> "Aligning song timing"
    "stemwise_validation" -> "Validating song"
    "stemwise_packaging", "stemwise_song_ready" -> "Preparing song ZIP"
    "stemwise_editorial" -> "Reviewing song content"
    "stemwise_engine_waiting" -> "Song preparation needs attention"
    "stemwise_dispatch_failed" -> "Song preparation failed"
    "awaiting_pc", "" -> "Waiting for PC"
    else -> "Stage: ${code.take(128)}"
}

internal fun bridgeFailureMessage(error: Throwable): String {
    val seen = mutableSetOf<Throwable>()
    val detail = generateSequence(error) { it.cause }.takeWhile(seen::add).joinToString("\nCaused by: ") {
        when (it) {
            is BridgeApiFailure -> "${it.code}: ${it.message.orEmpty()}"
            is EnrichmentImportException -> "${it.code}: ${it.message.orEmpty()}"
            else -> it.toString()
        }
    }
    return "${bridgeFailureSummary(error)}\n\nBridge diagnostics:\n$detail"
}

private fun bridgeFailureSummary(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    if (causes.any { it.message in setOf("STORAGE_RESERVE_LOW", "LIBRARY_STORAGE_LOW") ||
            it.message?.contains("ENOSPC", ignoreCase = true) == true ||
            it.message?.contains("No space left", ignoreCase = true) == true ||
            (it as? EnrichmentImportException)?.code == "STORAGE_RESERVE_LOW" }) {
        return "Storage is full. Free space, then resume this saved task; existing songs are preserved."
    }
    if (causes.any { it.message == "BRIDGE_OUTBOX_UNAVAILABLE" || it is java.nio.file.FileSystemException }) {
        return "The saved task could not be written. Check device storage, then resume the same task."
    }
    if (causes.any { it is SecurityException || it.message in setOf("ROOT_QUERY_FAILED", "ROOT_PERMISSION_REQUIRED", "ROOT_UNAVAILABLE") }) {
        return "Library access is unavailable. Reconnect the original Library folder, then resume this saved task."
    }
    if ((error as? BridgeApiFailure)?.code == "job_cleanup_authorization_unconfirmed") {
        return "Cleanup could not be confirmed. Your saved work is preserved. Resume this task to retry removal."
    }
    if ((error as? BridgeApiFailure)?.code == "app_registration_changed") {
        return "Bridge registration changed. Check the saved Bridge setup, then resume this same task."
    }
    if ((error as? BridgeApiFailure)?.code == "app_request_cancelled") {
        return "The Bridge stopped this request. Resume this saved task to check its original PC job."
    }
    return when ((error as? BridgeLocalFailure)?.code) {
    BridgeFailureCode.BRIDGE_CONNECTION_REQUIRED -> "Set up the Bridge connection in Settings before sending a song."
    BridgeFailureCode.BRIDGE_REGISTRATION_CHANGED -> "The saved PC pairing changed. Resolve saved Bridge tasks, then use Pair again in Settings."
    BridgeFailureCode.LIBRARY_CHANGED, BridgeFailureCode.ROOT_UNAVAILABLE -> "The selected library changed. Return to the original library and resume this task."
    BridgeFailureCode.BRIDGE_LOCAL_OUTPUT_CHANGED, BridgeFailureCode.BRIDGE_LOCAL_OUTPUT_UNVERIFIED,
    BridgeFailureCode.BRIDGE_OUTPUT_CHANGED -> "Song ZIP verification failed. The original request and prior library files are preserved."
    BridgeFailureCode.BRIDGE_REMOTE_OUTCOME_UNRESOLVED -> "The original PC task is unavailable. Its submission may have been accepted; it will not be sent again."
    BridgeFailureCode.BRIDGE_INPUT_CHANGED -> "The prepared song inputs do not match the frozen request. The request and prior library files are preserved."
    BridgeFailureCode.BRIDGE_SONG_REPORT_INVALID, BridgeFailureCode.BRIDGE_SONG_OUTPUT_INVALID,
    BridgeFailureCode.BRIDGE_REQUEST_CONFLICT -> "The PC result does not match this song request. The existing library is preserved."
    else -> if (error is BridgeApiFailure && (error.retryable || error.status >= 500) ||
        causes.any { it is java.net.SocketException || it is java.net.UnknownHostException })
        "The PC is unavailable. Check the connection, then resume this saved task."
    else when (error) {
        is EnrichmentImportException -> "${error.code}: ${error.message.orEmpty()}"
        is BridgeApiFailure -> "${error.code}: ${error.message.orEmpty()}"
        else -> error.message?.takeIf(String::isNotBlank) ?: "This task needs attention."
    }
}
}
