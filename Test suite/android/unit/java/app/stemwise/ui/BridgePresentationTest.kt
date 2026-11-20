package app.stemwise.ui

import app.stemwise.bridge.BridgeSongPhase
import app.stemwise.bridge.BridgeSongTask
import app.stemwise.bridge.BridgeUiState
import app.stemwise.bridge.BridgeApiFailure
import app.stemwise.bridge.completeBridgeJobObservation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePresentationTest {
    @Test fun structuredReadinessErrorsStayReadableWithoutChangingOrDroppingTheirEnvelope() {
        val cases = mapOf(
            "bridge_transport_starting" to "Bridge is still starting. Wait a moment, then check the connection.",
            "paired_owner_mismatch" to "Bridge pairing could not be verified. Check the saved Bridge and PC details in Settings.",
            "bridge_enrollment_required" to "Reconnect Bridge in Settings to authorize this device.",
            "bridge_operation_cancelled" to "The Bridge call was cancelled. Check the saved task before trying again.",
            "unknown_new_code" to "Bridge reported: unknown new code."
        )
        for ((code, expected) in cases) {
            val raw = org.json.JSONObject().put("bridgeTransportError", "v1").put("code", code)
                .put("phase", "pre_send").put("requestSent", false).put("retryable", code == "bridge_transport_starting").toString()
            val issue = bridgeIssuePresentation(raw)
            assertEquals(expected, issue.summary)
            assertEquals(raw, issue.detail)
            val wrapped = bridgeOperationFailureMessage(IllegalStateException(raw, IllegalStateException(raw)), "Failed")
            assertEquals(expected, bridgeIssuePresentation(wrapped).summary)
            assertEquals(wrapped, bridgeIssuePresentation(wrapped).detail)
        }
        val unmarked = org.json.JSONObject().put("code", "paired_owner_mismatch").put("requestSent", false).toString()
        assertEquals(unmarked, bridgeIssuePresentation(unmarked).detail)
    }

    @Test fun everyReturnedLogIssueHasItsOwnVisibleExplanation() {
        val entries = JSONArray().put(JSONObject().put("error", JSONObject()
            .put("code", "connection_failed").put("summary", "Connection timed out")))
            .put(JSONObject().put("error", JSONObject().put("code", "disk_full").put("summary", "ENOSPC")))
        val log = JSONObject().put("entries", entries).put("dropped", 0)
        val observation = completeBridgeJobObservation(JSONObject(), JSONObject(), log)
        val issue = bridgeIssuePresentation(observation.getString("diagnostic"))
        assertEquals("The PC could not be reached. Check the connection before resuming.\n\nStorage is full. Free some space before resuming.", issue.summary)
        assertEquals(log.toString(2), issue.detail.substringAfter("Bridge diagnostics:\n"))
    }

    @Test fun taskLabelsKeepProgressDraftsAndUnconfirmedSubmissionsDistinct() {
        val task = BridgeSongTask("exact-request", "abcdefghijk", "Add song · abcdefghijk", BridgeSongPhase.RUNNING,
            "Stage: internal_progress_code")
        val preparing = bridgeTaskPresentation(task)
        assertEquals("YouTube song", preparing.title)
        assertEquals("Preparing song", preparing.status)
        assertEquals(BridgeTaskTone.PROGRESS, preparing.tone)
        assertNull(preparing.issue)
        assertEquals("Waiting for authorization", bridgeTaskPresentation(task.copy(phase = BridgeSongPhase.DRAFT)).status)
        assertEquals("Original request needs confirmation", bridgeTaskPresentation(task.copy(
            phase = BridgeSongPhase.WAITING, stage = "Original PC task needs confirmation",
            message = "Resume to check the original PC task. It will not be sent again.")).status)
        assertEquals("A real song title", bridgeTaskPresentation(task.copy(title = "A real song title")).title)
        assertEquals("Add song · abcdefghijk", task.title)
        assertEquals("exact-request", task.id)
        val local = task.copy(phase = BridgeSongPhase.WAITING, retryable = true, localImportPending = true)
        assertEquals("Import needs attention", bridgeTaskPresentation(local).status)
        assertFalse(local.canCancel)
    }

    @Test fun taskFailuresWarningsAndDeliveryIssuesRemainVisibleWithExactDetails() {
        val raw = "  native_turn_failed: unauthorized\nPC diagnostic: retry requires sign-in  "
        val task = BridgeSongTask("job", "abcdefghijk", "Song", BridgeSongPhase.FAILED, "PC task ended", message = raw)
        val failed = bridgeTaskPresentation(task)
        assertEquals(BridgeTaskTone.ERROR, failed.tone)
        assertEquals("Could not prepare song", failed.status)
        assertEquals("The PC needs you to sign in before it can prepare this song.", failed.issue!!.summary)
        assertEquals(raw, failed.issue.detail)
        val delivered = bridgeTaskPresentation(task.copy(phase = BridgeSongPhase.ACKNOWLEDGING, locallyImported = true))
        assertEquals("Song added", delivered.status)
        assertEquals(BridgeTaskTone.WARNING, delivered.tone)
        assertEquals(raw, delivered.issue!!.detail)
        val attention = bridgeTaskPresentation(task.copy(phase = BridgeSongPhase.RUNNING, message = "Please unlock the PC."))
        assertEquals(BridgeTaskTone.WARNING, attention.tone)
        assertEquals("Please unlock the PC.", attention.issue!!.summary)
        val cancelling = bridgeTaskPresentation(task.copy(phase = BridgeSongPhase.CANCELLING,
            message = "Waiting for the PC to confirm that this task has stopped."))
        assertEquals(BridgeTaskTone.PROGRESS, cancelling.tone)
        assertEquals("Waiting for the PC to confirm that this task has stopped.", cancelling.issue!!.detail)
    }

    @Test fun knownAndUnknownIssuesKeepTheirOriginalUntruncatedMessages() {
        val cases = mapOf(
            "native_auth_required" to "Sign in to Codex on your PC before preparing this song.",
            "native_auth_change_pending" to "The PC is finishing current work before it can use the new sign-in.",
            "native_auth_source_unverified" to "Bridge could not verify its PC runtime. Check the Bridge installation on your PC.",
            "native_auth_unavailable" to "The PC sign-in could not be checked. Check Codex on your PC and try again.",
            "Bridge transport unavailable" to "Bridge is offline. Check the connection in Settings, then try again.",
            "Paired same-owner device unavailable" to "The paired PC or Bridge is unavailable. Check the connection in Settings, then try again.",
            "Your access token could not be refreshed because you have since logged out or signed in to another account. Please sign in again." to
                "Sign in to Codex on your PC before preparing this song.",
            "SOURCE_TIMELINE_MISMATCH" to "The song's audio timing does not match its package. Your existing library is preserved.",
            "BRIDGE_MODEL_CATALOG_INVALID_DEFAULT" to "The PC sent processing options that could not be read. Refresh the options.",
            "BRIDGE_REQUEST_CONFLICT" to "The PC response could not be verified for this song. Your saved work is preserved.",
            "BRIDGE_LOCAL_OUTPUT_UNVERIFIED" to "The song package could not be verified. Your existing library is preserved.",
            "job_unavailable" to "The original PC task needs confirmation. It will not be sent again.",
            "ENOSPC" to "Storage is full. Free some space before resuming.",
            "UNRECOGNIZED_PROVIDER_ERROR" to "The PC reported: unrecognized provider error.",
            "The provider is unavailable until tomorrow." to "The provider is unavailable until tomorrow.",
            "Long unknown detail: " + "x".repeat(1_000) to "Long unknown detail: " + "x".repeat(1_000)
        )
        for ((raw, summary) in cases) {
            val issue = bridgeIssuePresentation(raw)
            assertEquals(summary, issue.summary)
            assertEquals(raw, issue.detail)
        }
        val raw = "First error explained.\nAnother problem explained.\n\nBridge diagnostics:\n{\"exact\":true}"
        assertEquals("First error explained.\nAnother problem explained.", bridgeIssuePresentation(raw).summary)
        assertEquals(raw, bridgeIssuePresentation(raw).detail)
    }

    @Test fun operationFailuresKeepApiCodesAndEveryCauseBeyondTheOldTruncationLimit() {
        val raw = "Original provider diagnostic " + "z".repeat(1_000)
        val cause = BridgeApiFailure(401, "unauthorized", raw, false)
        val failure = IllegalStateException("Request could not finish", cause)
        val message = bridgeOperationFailureMessage(failure, "Could not send song")
        assertEquals("Request could not finish\nCaused by: unauthorized: $raw", message)
        assertEquals(message, bridgeIssuePresentation(message).detail)
        assertEquals("Bridge access was denied. Reconnect Bridge in Settings and try again.", bridgeIssuePresentation(message).summary)
        assertEquals("Could not send song", bridgeOperationFailureMessage(IllegalStateException(), "Could not send song"))
    }

    @Test fun currentIssueAndEveryReadableSummarySurviveHistoricalDiagnostics() {
        val raw = "SOURCE_TIMELINE_MISMATCH\n\nAnother provider problem.\n\nBridge diagnostics:\nold native_turn_failed unauthorized"
        val issue = bridgeIssuePresentation(raw)
        assertEquals("The song's audio timing does not match its package. Your existing library is preserved.\n\nAnother provider problem.", issue.summary)
        assertEquals(raw, issue.detail)
        val oldMessage = "SOURCE_TIMELINE_MISMATCH\n\nThis task needs attention. Copy task diagnostics for the cause, then resume this same task."
        assertEquals("The song's audio timing does not match its package. Your existing library is preserved.",
            bridgeIssuePresentation(oldMessage).summary)
        assertEquals(oldMessage, bridgeIssuePresentation(oldMessage).detail)
        assertEquals("The provider needs attention.\nCheck its connection.",
            bridgeIssuePresentation("UNRECOGNIZED_PROVIDER_ERROR\nThe provider needs attention.\nCheck its connection.").summary)
    }

    @Test
    fun connectionDisplayRequiresAnUnexpiredObservation() {
        val state = BridgeUiState(observedAtElapsedMs = 1_000, validForMs = 500)
        assertFalse(bridgeConnectionFresh(BridgeUiState(), 1_000))
        assertFalse(bridgeConnectionFresh(state.copy(validForMs = 0), 1_000))
        assertFalse(bridgeConnectionFresh(state, 999))
        assertTrue(bridgeConnectionFresh(state, 1_000))
        assertTrue(bridgeConnectionFresh(state, 1_499))
        assertFalse(bridgeConnectionFresh(state, 1_500))
    }

    @Test
    fun authorizationOnlyOpensTheHttpsTailscaleLoginHost() {
        assertTrue(validBridgeAuthorizationUrl("https://login.tailscale.com/a/example"))
        assertTrue(validBridgeAuthorizationUrl("https://login.tailscale.com:443/a/example"))
        listOf(
            "http://login.tailscale.com/a/example",
            "https://login.tailscale.com.attacker.invalid/a/example",
            "https://attacker.invalid/login.tailscale.com",
            "https://owner@login.tailscale.com/a/example",
            "https://login.tailscale.com:8443/a/example",
            "javascript:alert(1)",
            "not a URL"
        ).forEach { assertFalse(it, validBridgeAuthorizationUrl(it)) }
    }

    @Test
    fun onlyAuthoritativeImportedStateClaimsSuccessAndResumeNeedsExplicitPermission() {
        fun task(state: String, retryable: Boolean = false) =
            BridgeSongTask("job", "video", "Song", BridgeSongPhase.valueOf(state), "Stage", retryable = retryable)

        listOf("QUEUED", "SUBMITTING", "RUNNING", "WAITING", "RETRYING", "RECEIVING", "IMPORTING", "ACKNOWLEDGING", "CANCELLING")
            .forEach { assertFalse(it, task(it).terminal) }
        listOf("IMPORTED", "FAILED", "CANCELLED", "EXPIRED", "RETIRING")
            .forEach { assertTrue(it, task(it).terminal) }
        assertFalse(task("FAILED").canRetry)
        listOf("WAITING", "RETRYING", "RETIRING", "ACKNOWLEDGING").forEach {
            assertFalse(it, task(it).canRetry)
            assertTrue(it, task(it, retryable = true).canRetry)
        }
        listOf("IMPORTED", "FAILED", "RUNNING", "CANCELLED", "EXPIRED")
            .forEach { assertFalse(it, task(it, retryable = true).canRetry) }
        listOf("FAILED", "CANCELLED", "EXPIRED")
            .forEach { assertTrue(it, task(it).canForget) }
        listOf("IMPORTED", "RUNNING", "WAITING", "RETRYING", "RETIRING")
            .forEach { assertFalse(it, task(it).canForget) }
    }
    @Test fun localImportSuccessDoesNotDependOnRemoteDeliveryBookkeeping() {
        val task = BridgeSongTask("job", "video", "Song", BridgeSongPhase.ACKNOWLEDGING,
            "Confirming delivery", retryable = true, locallyImported = true)
        assertTrue(task.importSucceeded)
        assertTrue(task.deliveryPending)
        assertTrue(task.canRetry)
        assertFalse(task.canCancel)
        assertFalse(task.canForget)
        assertFalse(task.copy(phase = BridgeSongPhase.IMPORTED).deliveryPending)
        val expired = task.copy(phase = BridgeSongPhase.EXPIRED, retryable = false)
        assertTrue(expired.importSucceeded && expired.deliveryUnavailable && expired.canForget)
        assertFalse(expired.deliveryPending || expired.canRetry || expired.canCancel)
        assertFalse(task.copy(locallyImported = false).importSucceeded)
    }
}
