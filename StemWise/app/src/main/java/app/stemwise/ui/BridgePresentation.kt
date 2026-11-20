package app.stemwise.ui

import app.stemwise.bridge.BridgeUiState
import app.stemwise.bridge.BridgeApiFailure
import app.stemwise.bridge.BridgeSongPhase
import app.stemwise.bridge.BridgeSongTask
import java.net.URI

internal enum class BridgeTaskTone { PROGRESS, SUCCESS, WARNING, ERROR }

internal data class BridgeIssuePresentation(val summary: String, val detail: String)

internal data class BridgeTaskPresentation(
    val title: String,
    val status: String,
    val tone: BridgeTaskTone,
    val issue: BridgeIssuePresentation?
)

internal fun bridgeTaskPresentation(task: BridgeSongTask): BridgeTaskPresentation {
    val issue = task.message?.takeIf(String::isNotBlank)?.let(::bridgeIssuePresentation)
    val status = if (task.importSucceeded) "Song added"
    else if (task.localImportPending && task.canRetry) "Import needs attention"
    else when (task.phase) {
        BridgeSongPhase.DRAFT -> "Waiting for authorization"
        BridgeSongPhase.PREPARING -> "Preparing reviewed request"
        BridgeSongPhase.WAKING -> "Waking PC"
        BridgeSongPhase.WAITING_PC -> "Waiting for PC"
        BridgeSongPhase.REVIEW_REQUIRED -> "Processing options need review"
        BridgeSongPhase.QUEUED -> "Queued"
        BridgeSongPhase.SUBMITTING -> "Sending to PC"
        BridgeSongPhase.RUNNING -> "Preparing song"
        BridgeSongPhase.WAITING -> if (task.stage == "Original PC task needs confirmation")
            "Original request needs confirmation" else "Waiting for the PC"
        BridgeSongPhase.RETRYING -> "Waiting to retry"
        BridgeSongPhase.RECEIVING -> "Receiving song"
        BridgeSongPhase.IMPORTING -> "Adding to library"
        BridgeSongPhase.ACKNOWLEDGING -> "Confirming delivery"
        BridgeSongPhase.IMPORTED -> "Song added"
        BridgeSongPhase.CANCELLING -> "Cancelling"
        BridgeSongPhase.CANCELLED -> "Cancelled"
        BridgeSongPhase.FAILED -> "Could not prepare song"
        BridgeSongPhase.EXPIRED -> "Request expired"
        BridgeSongPhase.REMOVED -> "Song removed from Library"
        BridgeSongPhase.RETIRING -> "Finishing cleanup"
        BridgeSongPhase.OUTBOX_DAMAGED, BridgeSongPhase.OUTBOX_UNKNOWN -> "Saved task needs repair"
    }
    val tone = when {
        task.phase in setOf(BridgeSongPhase.FAILED, BridgeSongPhase.OUTBOX_DAMAGED, BridgeSongPhase.OUTBOX_UNKNOWN) -> BridgeTaskTone.ERROR
        task.phase in setOf(BridgeSongPhase.WAITING, BridgeSongPhase.WAITING_PC, BridgeSongPhase.REVIEW_REQUIRED, BridgeSongPhase.RETRYING, BridgeSongPhase.EXPIRED, BridgeSongPhase.REMOVED) ||
            issue != null && (task.phase != BridgeSongPhase.CANCELLING || task.retryable) -> BridgeTaskTone.WARNING
        task.importSucceeded -> BridgeTaskTone.SUCCESS
        else -> BridgeTaskTone.PROGRESS
    }
    return BridgeTaskPresentation(
        if (task.title.isBlank() || task.title == "Add song · ${task.videoId}") "YouTube song" else task.title,
        status, tone, issue)
}

internal fun bridgeIssuePresentation(message: String): BridgeIssuePresentation {
    val readable = message.substringBefore("\n\nBridge diagnostics:\n")
    val nativeFailure = readable.contains("native_turn_failed", ignoreCase = true)
    val issues = readable.split("\n\n").filterNot { it in setOf("This task needs attention.",
        "This task needs attention. Copy task diagnostics for the cause, then resume this same task.") }
        .ifEmpty { listOf("This task needs attention. Open Error details to see the reported issue.") }
    val summary = issues.map { issue ->
        fun contains(vararg values: String) = values.any { issue.contains(it, ignoreCase = true) }
        val transport = (sequenceOf(issue) + issue.lineSequence().map { it.removePrefix("Caused by: ") })
            .mapNotNull { runCatching { org.json.JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("bridgeTransportError") == "v1" }
        when {
            transport != null -> when (val code = transport.optString("code")) {
                "bridge_transport_starting", "paired_peer_map_pending", "bridge_status_unavailable", "bridge_readiness_timeout" ->
                    "Bridge is still starting. Wait a moment, then check the connection."
                "paired_target_invalid", "paired_identity_mismatch", "paired_identity_ambiguous", "paired_dns_mismatch",
                "paired_owner_mismatch", "paired_address_invalid" ->
                    "Bridge pairing could not be verified. Check the saved Bridge and PC details in Settings."
                "paired_device_enrollment_expired", "bridge_enrollment_required" ->
                    "Reconnect Bridge in Settings to authorize this device."
                "bridge_device_approval_required" -> "Approve this device for the Bridge connection, then check again."
                "bridge_transport_stopped", "bridge_transport_unavailable" -> "Bridge is offline. Check the connection in Settings."
                "bridge_operation_cancelled" -> "The Bridge call was cancelled. Check the saved task before trying again."
                else -> "Bridge reported: ${code.ifBlank { "unrecognized connection issue" }.replace('_', ' ')}."
            }
            contains("native_auth_required") -> "Sign in to Codex on your PC before preparing this song."
            contains("native_auth_change_pending") -> "The PC is finishing current work before it can use the new sign-in."
            contains("native_auth_source_unverified") -> "Bridge could not verify its PC runtime. Check the Bridge installation on your PC."
            contains("native_auth_unavailable") -> "The PC sign-in could not be checked. Check Codex on your PC and try again."
            contains("access token could not be refreshed") -> "Sign in to Codex on your PC before preparing this song."
            nativeFailure && contains("unauthorized", "login_required", "auth_required", "not logged in") ->
                "The PC needs you to sign in before it can prepare this song."
            contains("unauthorized", "login_required", "auth_required", "not logged in") ->
                "Bridge access was denied. Reconnect Bridge in Settings and try again."
            contains("BRIDGE_MODEL_CATALOG") -> "The PC sent processing options that could not be read. Refresh the options."
            contains("BRIDGE_CONNECTION_REQUIRED") -> "Set up the PC connection in Settings before sending a song."
            contains("BRIDGE_REQUEST_CONFLICT", "BRIDGE_INPUT_CHANGED", "BRIDGE_JOB_IDENTITY_INVALID", "BRIDGE_RESPONSE_IDENTITY_INVALID") ->
                "The PC response could not be verified for this song. Your saved work is preserved."
            contains("BRIDGE_LOCAL_OUTPUT", "BRIDGE_OUTPUT_CHANGED", "BRIDGE_SONG_OUTPUT_INVALID", "BRIDGE_SONG_REPORT_INVALID", "BRIDGE_FILE_INVALID") ->
                "The song package could not be verified. Your existing library is preserved."
            contains("BRIDGE_REMOTE_OUTCOME_UNRESOLVED", "BRIDGE_SUBMISSION_UNCONFIRMED", "job_unavailable") ->
                "The original PC task needs confirmation. It will not be sent again."
            contains("ROOT_UNAVAILABLE", "LIBRARY_CHANGED", "ROOT_PERMISSION_REQUIRED") ->
                "The original library is unavailable. Reconnect it before resuming this task."
            contains("SOURCE_TIMELINE_MISMATCH") ->
                "The song's audio timing does not match its package. Your existing library is preserved."
            contains("STORAGE_RESERVE_LOW", "No space left", "ENOSPC") -> "Storage is full. Free some space before resuming."
            contains("BRIDGE_OUTBOX_UNAVAILABLE") -> "The task could not be saved. Check this device's storage."
            contains("native_turn_failed") -> "The PC could not finish preparing this song."
            contains("UnknownHostException", "SocketException", "ConnectException", "timed out", "connection refused") ->
                "The PC could not be reached. Check the connection before resuming."
            contains("Bridge transport unavailable") -> "Bridge is offline. Check the connection in Settings, then try again."
            contains("Paired same-owner device unavailable") -> "The paired PC or Bridge is unavailable. Check the connection in Settings, then try again."
            else -> {
                val code = Regex("[A-Za-z][A-Za-z0-9]*(_[A-Za-z0-9]+)+")
                when {
                    code.matches(issue) -> "The PC reported: ${issue.lowercase().replace('_', ' ')}."
                    code.matches(issue.substringBefore('\n')) -> issue.substringAfter('\n')
                    else -> issue
                }
            }
        }
    }.distinct().joinToString("\n\n")
    return BridgeIssuePresentation(summary, message)
}

internal fun bridgeOperationFailureMessage(error: Throwable, fallback: String): String {
    val seen = mutableSetOf<Throwable>()
    return generateSequence(error) { it.cause }.takeWhile(seen::add).joinToString("\nCaused by: ") {
        if (it is BridgeApiFailure) "${it.code}: ${it.message.orEmpty()}"
        else it.message?.takeIf(String::isNotBlank) ?: fallback
    }
}

internal fun bridgeConnectionFresh(state: BridgeUiState, nowElapsedMs: Long): Boolean {
    val observedAt = state.observedAtElapsedMs ?: return false
    return state.validForMs > 0 && nowElapsedMs >= observedAt &&
        nowElapsedMs - observedAt < state.validForMs
}

internal fun validBridgeAuthorizationUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("login.tailscale.com", ignoreCase = true) &&
        uri.rawUserInfo == null && uri.port in setOf(-1, 443)
}.getOrDefault(false)
