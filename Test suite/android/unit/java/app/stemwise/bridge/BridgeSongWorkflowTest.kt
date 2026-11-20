package app.stemwise.bridge

import androidx.work.ListenableWorker
import app.stemwise.work.advanceBridgeSongWork
import app.stemwise.data.CanonicalJson
import app.stemwise.data.RootAuthority
import app.stemwise.enrichment.EnrichmentImportException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class BridgeSongWorkflowTest {
    @Test fun authenticatedReceiverLossAfterObservedTerminalStateCanForgetWithoutReplay() = runBlocking {
        for (terminal in listOf("failed", "cancelled", "interrupted", "rejected")) {
            val harness = Harness("terminal-receiver-loss-$terminal")
            val request = harness.record()
            assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(request.task.id))
            checkNotNull(harness.transport.remote).put("state", terminal)
            assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(request.task.id))
            assertTrue(BridgeSongStore(harness.outbox).records().single().task.phase in
                setOf(BridgeSongPhase.FAILED, BridgeSongPhase.CANCELLED))
            harness.transport.lookupFailure = BridgeApiFailure(409, "app_registration_required", "Receiver removed", false)
            assertTrue("Observed $terminal must not depend on recreating its missing receiver",
                harness.workflow().retire(request.task.id))
            assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
            assertEquals(1, harness.transport.submitCount)
            assertEquals(0, harness.transport.deleteCount + harness.transport.cancelCount)
            assertEquals(1, harness.transport.discardCount)
        }
    }

    @Test fun terminalRetirementRejectsChangedReceiverAndTransientOrUntrustedAbsence() = runBlocking {
        val errors = listOf(IOException("Offline"),
            BridgeApiFailure(409, "app_registration_changed", "Receiver changed", false),
            BridgeApiFailure(503, "app_registration_required", "Unavailable", true),
            BridgeLocalFailure(BridgeFailureCode.BRIDGE_RESPONSE_IDENTITY_INVALID))
        for ((index, error) in errors.withIndex()) {
            val harness = Harness("terminal-receiver-control-$index")
            val request = harness.record()
            harness.workflow().advance(request.task.id)
            checkNotNull(harness.transport.remote).put("state", "failed")
            harness.workflow().advance(request.task.id)
            harness.transport.lookupFailure = error
            assertFalse(harness.workflow().retire(request.task.id))
            assertFalse(BridgeSongStore(harness.outbox).records().single().remoteRetired)
            assertEquals(0, harness.transport.deleteCount + harness.transport.discardCount)
            assertEquals(1, harness.transport.submitCount)
        }
    }

    @Test fun receiverLossCannotRetireUnknownOrOnlyLocallyFailedWork() = runBlocking {
        for (submission in listOf(BridgeSubmission.UNKNOWN, BridgeSubmission.ACKNOWLEDGED))
            for (state in listOf("running", "unknown", "failed")) {
            val harness = Harness("unknown-receiver-loss-$submission-$state")
            val request = harness.record()
            harness.transport.loseSubmitAcknowledgement = submission == BridgeSubmission.UNKNOWN
            assertEquals(if (submission == BridgeSubmission.UNKNOWN) BridgeSongWorkProgress.RETRY else BridgeSongWorkProgress.PENDING,
                harness.workflow().advance(request.task.id))
            val store = BridgeSongStore(harness.outbox)
            val accepted = store.records().single()
            assertEquals(submission, accepted.submission)
            // A local failure is not an authenticated observation of remote termination.
            store.save(accepted.copy(task = accepted.task.copy(phase = BridgeSongPhase.FAILED)))
            checkNotNull(harness.transport.remote).put("state", state)
            harness.transport.lookupFailure = BridgeApiFailure(409, "app_registration_required", "Receiver removed", false)
            assertFalse(harness.workflow().retire(request.task.id))
            val retained = BridgeSongStore(harness.outbox).records().single()
            assertFalse(retained.remoteRetired)
            assertEquals(1, harness.transport.submitCount)
            assertEquals(0, harness.transport.deleteCount + harness.transport.discardCount)
        }
    }

    @Test fun terminalObservationSurvivesProcessLossBeforeDeleteAndReconcilesUnknownSubmission() = runBlocking {
        for (submission in listOf(BridgeSubmission.UNKNOWN, BridgeSubmission.ACKNOWLEDGED)) {
            val harness = Harness("terminal-before-delete-$submission")
            val original = harness.record()
            val record = original.copy(submission = submission, task = original.task.copy(phase = BridgeSongPhase.FAILED))
            val store = BridgeSongStore(harness.outbox)
            store.save(record)
            val recovery = listOf("zip", "md", "input").associateWith { suffix ->
                store.file(record.task.id, suffix).also { it.writeText("retained $suffix", Charsets.UTF_8) }.readBytes()
            }
            harness.transport.remote = JSONObject().put("id", record.task.id).put("input", record.request).put("state", "completed")
            harness.transport.deleteFailure = SimulatedProcessInterruption()
            try { harness.workflow().retire(record.task.id); error("Interruption was swallowed") }
            catch (_: SimulatedProcessInterruption) { }
            val restarted = BridgeSongStore(harness.outbox)
            val retained = restarted.records().single()
            assertEquals(BridgeSubmission.ACKNOWLEDGED, retained.submission)
            assertTrue(retained.remoteTerminalObserved)
            assertFalse(retained.remoteRetired)
            assertEquals(record.root, retained.root)
            assertEquals(record.approval, retained.approval)
            assertArrayEquals(CanonicalJson.encode(record.request), CanonicalJson.encode(retained.request))
            recovery.forEach { (suffix, bytes) -> assertArrayEquals(bytes, restarted.file(record.task.id, suffix).readBytes()) }
            harness.transport.lookupFailure = BridgeApiFailure(409, "app_registration_required", "Receiver removed", false)
            harness.transport.deleteFailure = null
            assertTrue(harness.workflow().retire(record.task.id))
            assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
            assertEquals(1, harness.transport.deleteCount)
            assertEquals(0, harness.transport.submitCount + harness.transport.cancelCount)
            assertEquals(1, harness.transport.discardCount)
        }
    }

    @Test fun receiverLossBetweenTerminalLookupAndDeleteCanRetireLocallyRejectedCompletion() = runBlocking {
        val harness = Harness("terminal-delete-receiver-loss")
        val record = harness.record()
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        harness.complete(record).getJSONObject("report").put("code", "invalid-song-report")
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        val failed = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.FAILED, failed.task.phase)
        assertFalse(failed.remoteTerminalObserved)
        harness.transport.deleteFailure = BridgeApiFailure(409, "app_registration_required", "Receiver removed", false)
        assertTrue(harness.workflow().retire(record.task.id))
        assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
        assertEquals(1, harness.transport.deleteCount)
        assertEquals(1, harness.transport.submitCount)
        assertEquals(0, harness.transport.cancelCount)
    }

    @Test fun deliveredSongCleanupRetainsTerminalObservationAcrossRestart() = runBlocking {
        val harness = Harness("delivered-terminal-delete-retry")
        val record = harness.record()
        harness.complete(record)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        harness.transport.deleteFailure = BridgeApiFailure(503, "job_cleanup_authorization_unconfirmed", "Cleanup pending", true)
        assertFalse(harness.workflow().retire(record.task.id))
        val retained = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.IMPORTED, retained.task.phase)
        assertTrue(retained.remoteTerminalObserved)
        assertFalse(retained.remoteRetired)
        harness.transport.lookupFailure = BridgeApiFailure(409, "app_registration_required", "Receiver removed", false)
        assertTrue(harness.workflow().retire(record.task.id))
        assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
        assertEquals(1, harness.transport.deleteCount)
        assertEquals(0, harness.transport.submitCount + harness.transport.cancelCount)
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertEquals(1, harness.events.count { it == "ack" })
    }

    @Test fun terminalReceiverRetirementPreservesInputsUntilTheirRecoveryOwnerReleasesThem() = runBlocking {
        val harness = Harness("terminal-receiver-inputs")
        val request = harness.record()
        harness.workflow().advance(request.task.id)
        checkNotNull(harness.transport.remote).put("state", "failed")
        harness.workflow().advance(request.task.id)
        harness.transport.lookupFailure = BridgeApiFailure(409, "app_registration_required", "Receiver removed", false)
        harness.beforeArchiveCleanup = { throw IOException("A retained import still owns these inputs") }
        assertFalse(harness.workflow().retire(request.task.id))
        val retained = BridgeSongStore(harness.outbox).records().single()
        assertTrue(retained.remoteRetired)
        assertFalse(retained.retired)
        assertTrue(BridgeSongStore(harness.outbox).file(request.task.id, "input").isFile)
        assertEquals(0, harness.transport.discardCount)
        harness.beforeArchiveCleanup = null
        assertTrue(harness.workflow().retire(request.task.id))
        assertEquals(1, harness.transport.submitCount)
        assertEquals(1, harness.transport.discardCount)
    }

    @Test fun confirmedMissingGuideDamagesOnlyItsOwningRecordAndCanBeDiscarded() {
        val outbox = File(directory, "missing-guide/outbox")
        val store = BridgeSongStore(outbox)
        val a = authorizedSong(root, originalText, "first guide", 1L)
        val b = authorizedSong(root, "https://youtu.be/lmnopqrstuv", "second guide", 2L)
        store.save(a); store.save(b)
        val record = JSONObject(File(outbox, "${a.task.id}.json").readText(Charsets.UTF_8))
        check(File(outbox, "guide-${record.getString("guideSha256")}.md").delete())
        val restarted = BridgeSongStore(outbox)
        assertEquals(listOf(b.task.id), restarted.records().map { it.task.id })
        val damaged = restarted.damagedTasks().single()
        assertEquals(BridgeSongPhase.OUTBOX_DAMAGED, damaged.phase)
        restarted.discardDamaged(damaged.id)
        assertEquals(listOf(b.task.id), BridgeSongStore(outbox).records().map { it.task.id })
    }

    @Test fun unavailableGuideRemainsRetryableAndCannotBeDiscardedAsDamaged() {
        val outbox = File(directory, "unavailable-guide/outbox")
        val store = BridgeSongStore(outbox)
        val a = authorizedSong(root, originalText, "temporarily unavailable guide", 1L)
        val b = authorizedSong(root, "https://youtu.be/lmnopqrstuv", "healthy guide", 2L)
        store.save(a); store.save(b)
        val record = JSONObject(File(outbox, "${a.task.id}.json").readText(Charsets.UTF_8))
        val guide = File(outbox, "guide-${record.getString("guideSha256")}.md")
        val bytes = guide.readBytes()
        check(guide.delete()); check(guide.mkdir())
        val restarted = BridgeSongStore(outbox)
        try {
            assertThrows(IOException::class.java) { restarted.records() }
            assertThrows(IOException::class.java) { restarted.discardDamaged("damaged:${a.task.id}.json") }
            assertTrue(File(outbox, "${a.task.id}.json").isFile)
        } finally { check(guide.delete()); guide.writeBytes(bytes) }
        assertEquals(setOf(a.task.id, b.task.id), restarted.records().map { it.task.id }.toSet())
        assertTrue(restarted.damagedTasks().isEmpty())
    }

    @Test fun rootUnavailabilityCannotSettleOrRetireAPreviouslyVerifiedDeletion() = runBlocking {
        val harness = Harness("unavailable-deleted-root")
        val record = harness.record()
        harness.complete(record)
        harness.transport.ackFailure = IOException("Offline")
        harness.workflow().advance(record.task.id)
        harness.explicitlyDeleted = true
        val bytes = File(harness.outbox, "${record.task.id}.json").readBytes()
        harness.producerOpen = false
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        assertArrayEquals(bytes, File(harness.outbox, "${record.task.id}.json").readBytes())
        assertEquals(BridgeSongPhase.ACKNOWLEDGING, BridgeSongStore(harness.outbox).records().single().task.phase)
        assertEquals(0, harness.transport.deleteCount + harness.transport.discardCount)
    }

    @Test fun authenticatedDeletionAfterSuccessfulReadbackRetiresPendingDeliveryWithoutAckOrReimport() = runBlocking {
        for (acknowledgement in listOf("offline", "missing", "expired")) {
            val harness = Harness("deleted-pending-$acknowledgement")
            val record = harness.record()
            harness.complete(record)
            harness.transport.ackFailure = when (acknowledgement) {
                "missing" -> BridgeApiFailure(404, "job_unavailable", "Remote job unavailable", false)
                "expired" -> BridgeApiFailure(410, "request_deleted", "Expired", false)
                else -> IOException("Offline")
            }
            harness.workflow().advance(record.task.id)
            assertTrue(BridgeSongStore(harness.outbox).records().single().installationVerified)
            harness.explicitlyDeleted = true
            harness.published.delete()
            val events = harness.events.toList()
            val restored = harness.workflow()
            assertEquals(BridgeSongWorkProgress.COMPLETE, restored.advance(record.task.id))
            val removed = BridgeSongStore(harness.outbox).records().single()
            assertEquals(BridgeSongPhase.REMOVED, removed.task.phase)
            assertTrue(removed.task.canForget)
            assertEquals(events, harness.events)
            harness.beforeArchiveCleanup = { throw IOException("Required recovery archive cannot be preserved") }
            val refused = runCatching { restored.retire(record.task.id) }.exceptionOrNull()
            assertTrue(refused is IOException)
            assertTrue(BridgeSongStore(harness.outbox).file(record.task.id, "zip").isFile)
            assertEquals(BridgeSongPhase.REMOVED, BridgeSongStore(harness.outbox).records().single().task.phase)
            harness.beforeArchiveCleanup = null
            assertTrue(restored.retire(record.task.id))
            assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
            assertEquals(0, harness.transport.deleteCount + harness.transport.discardCount)
        }
    }

    @Test fun accidentalRemovalAndDeletionBeforeFirstReadbackCannotClaimVerifiedDeletion() = runBlocking {
        for (verified in listOf(false, true)) {
            val harness = Harness("unproved-deletion-$verified")
            val record = harness.record()
            harness.complete(record)
            harness.transport.ackFailure = IOException("Offline")
            if (!verified) harness.verificationFailure = IOException("Readback unavailable")
            harness.workflow().advance(record.task.id)
            harness.verificationFailure = null
            harness.explicitlyDeleted = !verified
            harness.published.delete()
            harness.workflow().advance(record.task.id)
            val retained = BridgeSongStore(harness.outbox).records().single()
            assertEquals(BridgeSongPhase.ACKNOWLEDGING, retained.task.phase)
            assertTrue(retained.imported)
            assertEquals(verified, retained.installationVerified)
            assertEquals(1, harness.events.count { it == "install" })
        }
    }
    @Test fun optionalDiagnosticFaultsDoNotStopReceiveOrValidCancellationReceipt() = runBlocking {
        for (malformed in listOf(false, true)) for (cancel in listOf(false, true)) {
            val harness = Harness("optional-log-$malformed-$cancel")
            val approved = harness.record()
            harness.workflow().advance(approved.task.id)
            harness.transport.observeLogs = true
            harness.transport.malformedLog = malformed
            if (!malformed) harness.transport.logFailure = IOException("Log unavailable")
            if (cancel) {
                harness.transport.remote!!.put("state", "cancelled").put("cancelRequested", 1L).put("cancelRetired", 2L)
                harness.workflow().requestCancellation(approved.task.id)
            } else harness.complete(approved)
            assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(approved.task.id))
            val saved = BridgeSongStore(harness.outbox).records().single()
            assertEquals(if (cancel) BridgeSongPhase.CANCELLED else BridgeSongPhase.IMPORTED, saved.task.phase)
            assertEquals(!cancel, harness.events.contains("receive"))
            assertEquals(1, harness.transport.submitCount)
        }
    }
    private val harnesses = mutableListOf<Harness>()
    private var diagnosticsAtExit = ""

    @Test fun optionalLogFailuresPreserveAuthoritativeObservationAndCancellation(): Unit = runBlocking {
        val id = "a".repeat(32)
        for (state in listOf("completed", "cancelled")) {
            for (malformed in listOf(false, true)) {
                val cache = BridgeJobObservationCache()
                val observed = cache.complete(id, JSONObject().put("id", id).put("state", state),
                    { JSONObject().put("requestId", id) },
                    { if (malformed) JSONObject().put("entries", "invalid") else throw IOException("log offline") })
                assertEquals(state, observed.getString("state"))
                assertEquals(id, observed.getJSONObject("input").getString("requestId"))
                assertTrue(observed.getString("diagnostic").contains("unavailable"))
            }
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking {
            BridgeJobObservationCache().complete(id, JSONObject().put("id", id).put("state", "completed"),
                { JSONObject().put("requestId", id) }, { throw kotlinx.coroutines.CancellationException("stop") })
        } }
        assertThrows(BridgeLocalFailure::class.java) { runBlocking {
            BridgeJobObservationCache().complete(id, JSONObject().put("id", id).put("state", "completed"),
                { JSONObject().put("requestId", "changed") }, { error("Must not read diagnostics") })
        } }
    }

    @Test fun explicitPairingReplacementRequiresEveryOldApprovedRequestToBeResolvedFirst() {
        val pending = authorizedSong(root, originalText, guide, 1L)
        for (submission in BridgeSubmission.entries) {
            assertThrows(IllegalStateException::class.java) {
                requireBridgePairingReplacement(listOf(pending.copy(submission = submission)), connection)
            }
        }
        requireBridgePairingReplacement(listOf(pending.copy(retired = true, remoteRetired = true)), connection)
        requireBridgePairingReplacement(listOf(newBridgeSong(root, originalText, "", 1L)), connection)
        requireBridgePairingReplacement(listOf(pending), connection.copy(pcId = "another-pc"))
    }

    @Test fun pairingAdoptsAResetReceiverOnlyAfterExplicitActionAndSuccessfulValidation(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val actual = JSONObject().put("id", "new-app").put("name", "StemWise")
        val api: suspend (String, String, JSONObject?) -> JSONObject = { method, path, _ ->
            calls += "$method $path"
            actual
        }
        val failure = assertThrows(BridgeLocalFailure::class.java) { runBlocking {
            retainBridgeRegistration("old-app", false, api, { saved += it })
        } }
        assertEquals(BridgeFailureDisposition.WAIT, bridgeFailureDisposition(failure))
        assertTrue(saved.isEmpty())
        retainBridgeRegistration("old-app", true, api, { saved += it })
        assertEquals(listOf("new-app"), saved)
        calls.clear()
        retainBridgeRegistration("old-app", true, { method, path, _ ->
            calls += "$method $path"
            if (method == "GET") throw BridgeApiFailure(401, "app_registration_required", "reset", false)
            actual
        }, { saved += it })
        assertEquals(listOf("GET /v2/app", "POST /v2/app"), calls)
        actual.put("name", "Other application")
        assertThrows(BridgeLocalFailure::class.java) { runBlocking {
            retainBridgeRegistration("old-app", true, api) { error("Invalid registration was saved") }
        } }
    }

    @Test fun remoteObservationsReuseImmutableInputsAndAlwaysReadCurrentDiagnostics(): Unit = runBlocking {
        val cache = BridgeJobObservationCache()
        val id = "a".repeat(32)
        var inputs = 0
        var logs = 0
        val job = JSONObject().put("id", id).put("state", "running")
            .put("report", JSONObject().put("code", "stemwise_source").put("status", "running"))
        suspend fun observe() = cache.complete(id, JSONObject(job.toString()),
            { inputs++; JSONObject().put("requestId", id) },
            { logs++; JSONObject().put("entries", JSONArray()).put("dropped", 0) })
        repeat(8) { observe() }
        assertEquals(1, inputs)
        assertEquals(8, logs)
        job.getJSONObject("report").put("code", "stemwise_alignment")
        observe()
        assertEquals(1, inputs)
        assertEquals(9, logs)
        cache.remove(id)
        observe()
        assertEquals(2, inputs)
        assertEquals(10, logs)
        assertThrows(BridgeLocalFailure::class.java) { runBlocking {
            cache.complete("b".repeat(32), job, { error("Wrong job read inputs") }, { error("Wrong job read logs") })
        } }
    }

    @Test fun sameStageObservationErrorsAndRetentionChangesReachSavedAndCopiedDiagnostics(): Unit = runBlocking {
        val harness = Harness("same-stage-log")
        val approved = harness.record()
        harness.workflow().advance(approved.task.id)
        harness.transport.observeLogs = true
        val remote = checkNotNull(harness.transport.remote).put("state", "submitted")
        harness.workflow().advance(approved.task.id)
        for ((index, cause) in listOf("First history failure", "Different history failure").withIndex()) {
            remote.put("code", "job_observation_unavailable").put("updated", index + 1L)
            remote.put("issue", JSONObject().put("code", "job_observation_unavailable")
                .put("message", "The exact native job outcome could not be established.")
                .put("action", "Retry status observation without resubmitting work.").put("retryable", true))
            harness.transport.log = JSONObject().put("entries", JSONArray().put(JSONObject()
                .put("error", JSONObject().put("summary", cause).put("context", "native turn")
                    .put("suggestedFix", "Restore native history")))).put("dropped", index)
            harness.workflow().advance(approved.task.id)
            val saved = BridgeSongStore(harness.outbox).records().single()
            assertTrue(saved.task.message.orEmpty().contains(cause))
            assertTrue(bridgeTaskDiagnostics(saved).contains("Restore native history"))
            assertTrue(bridgeTaskDiagnostics(saved).contains("native turn"))
            assertEquals(1, harness.transport.submitCount)
        }
    }

    @Test fun recordsShareOneVerifiedGuideRevisionAndUseIndexedSongLookupAfterRestart() {
        val harness = Harness("shared-guides")
        val first = harness.record()
        val second = authorizedSong(first.root, "https://www.youtube.com/watch?v=BBBBBBBBBBB", first.guide, 2L)
        val store = BridgeSongStore(harness.outbox)
        store.save(second)
        val files = harness.outbox.listFiles()!!.filter { it.name.startsWith("guide-") && it.extension == "md" }
        assertEquals(1, files.size)
        for (record in listOf(first, second)) {
            val raw = JSONObject(File(harness.outbox, "${record.task.id}.json").readText(Charsets.UTF_8))
            assertFalse(raw.has("guide"))
            assertEquals(64, raw.getString("guideSha256").length)
        }
        val reopened = BridgeSongStore(harness.outbox)
        assertEquals(first.guide, reopened.recordsFor(first.root, first.task.videoId).single().guide)
        assertEquals(second.task.id, reopened.recordsFor(second.root, second.task.videoId).single().task.id)
        assertArrayEquals(CanonicalJson.encode(first.request), CanonicalJson.encode(checkNotNull(reopened.record(first.task.id)).request))
        assertEquals(first.approval, reopened.record(first.task.id)?.approval)
    }

    @Test fun atomicRecordReplacementSurvivesAReaderReleasedAfterActualMoveDenial() {
        val harness = Harness("atomic-reader-release")
        val prior = harness.record()
        val candidate = prior.copy(task = prior.task.copy(stage = "Updated local stage"))
        val canonical = File(harness.outbox, "${prior.task.id}.json")
        val pending = File(harness.outbox, "${prior.task.id}.new")
        val oldBytes = canonical.readBytes()
        var staged: ByteArray? = null
        var attempts = 0
        var denials = 0
        FileInputStream(canonical).use { reader ->
            val store = BridgeSongStore(harness.outbox, atomicMove = { source, target ->
                attempts++
                assertEquals(pending.toPath(), source)
                assertEquals(canonical.toPath(), target)
                val bytes = pending.readBytes()
                assertEquals(candidate.task.stage, JSONObject(String(bytes, Charsets.UTF_8)).getString("stage"))
                staged?.let { assertArrayEquals(it, bytes) } ?: run { staged = bytes }
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (denied: AccessDeniedException) {
                    denials++
                    assertArrayEquals(oldBytes, canonical.readBytes())
                    reader.close()
                    throw denied
                }
            })
            assertEquals(prior.task.stage, store.record(prior.task.id)?.task?.stage)
            assertTrue(store.save(candidate))
            if (System.getProperty("os.name").startsWith("Windows")) assertTrue(denials >= 1)
            assertEquals(denials + 1, attempts)
            assertArrayEquals(checkNotNull(staged), canonical.readBytes())
            assertFalse(pending.exists())
            assertEquals(candidate.task.stage, store.record(prior.task.id)?.task?.stage)
            assertEquals(candidate.task.stage, store.recordsFor(prior.root, prior.task.videoId).single().task.stage)
            assertArrayEquals(CanonicalJson.encode(candidate.json()),
                CanonicalJson.encode(checkNotNull(BridgeSongStore(harness.outbox).record(prior.task.id)).json()))
        }
    }

    @Test fun permanentAtomicRecordDenialIsBoundedAndRetainsCanonicalBytesAndIndexes() {
        val harness = Harness("atomic-permanent-denial")
        val prior = harness.record()
        val candidate = prior.copy(task = prior.task.copy(stage = "Unpublished local stage"))
        val canonical = File(harness.outbox, "${prior.task.id}.json")
        val pending = File(harness.outbox, "${prior.task.id}.new")
        val oldBytes = canonical.readBytes()
        val firstDenial = AccessDeniedException(canonical.path)
        var attempts = 0
        var staged: ByteArray? = null
        val store = BridgeSongStore(harness.outbox, atomicMove = { source, target ->
            attempts++
            assertEquals(pending.toPath(), source)
            assertEquals(canonical.toPath(), target)
            val bytes = pending.readBytes()
            assertEquals(candidate.task.stage, JSONObject(String(bytes, Charsets.UTF_8)).getString("stage"))
            staged?.let { assertArrayEquals(it, bytes) } ?: run { staged = bytes }
            assertArrayEquals(oldBytes, canonical.readBytes())
            throw if (attempts == 1) firstDenial else AccessDeniedException(canonical.path)
        })
        assertEquals(prior.task.stage, store.record(prior.task.id)?.task?.stage)
        val started = System.nanoTime()
        val error = assertThrows(IOException::class.java) { store.save(candidate) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals("BRIDGE_OUTBOX_UNAVAILABLE", error.message)
        assertSame(firstDenial, error.cause)
        assertTrue("Atomic replacement must retry briefly", attempts > 1)
        assertTrue("Atomic replacement must stop within its local retry bound: ${elapsedMs}ms", elapsedMs < 2_000L)
        assertArrayEquals(oldBytes, canonical.readBytes())
        assertArrayEquals(checkNotNull(staged), pending.readBytes())
        assertEquals(prior.task.stage, store.record(prior.task.id)?.task?.stage)
        assertEquals(prior.task.stage, store.recordsFor(prior.root, prior.task.videoId).single().task.stage)
        assertArrayEquals(CanonicalJson.encode(prior.json()),
            CanonicalJson.encode(checkNotNull(BridgeSongStore(harness.outbox).record(prior.task.id)).json()))
    }

    @Test fun otherAtomicRecordMoveFailuresAreNotRetried() {
        val harness = Harness("atomic-other-failure")
        val prior = harness.record()
        val canonical = File(harness.outbox, "${prior.task.id}.json")
        val oldBytes = canonical.readBytes()
        val failure = IOException("Actual move failure other than access denial")
        var attempts = 0
        val store = BridgeSongStore(harness.outbox, atomicMove = { _, _ -> attempts++; throw failure })
        assertEquals(prior.task.stage, store.record(prior.task.id)?.task?.stage)
        val error = assertThrows(IOException::class.java) { store.save(prior.copy(task = prior.task.copy(stage = "Unpublished"))) }
        assertSame(failure, error.cause)
        assertEquals(1, attempts)
        assertArrayEquals(oldBytes, canonical.readBytes())
        assertEquals(prior.task.stage, store.record(prior.task.id)?.task?.stage)
        assertEquals(prior.task.stage, store.recordsFor(prior.root, prior.task.videoId).single().task.stage)
    }

    @Test fun interruptedAtomicRecordRetryPreservesTheDenialAndInterrupt() {
        val harness = Harness("atomic-interrupted")
        val prior = harness.record()
        val canonical = File(harness.outbox, "${prior.task.id}.json")
        val oldBytes = canonical.readBytes()
        val denial = AccessDeniedException(canonical.path)
        var attempts = 0
        val store = BridgeSongStore(harness.outbox, atomicMove = { _, _ ->
            attempts++
            Thread.currentThread().interrupt()
            throw denial
        })
        assertEquals(prior.task.stage, store.record(prior.task.id)?.task?.stage)
        try {
            val error = assertThrows(IOException::class.java) { store.save(prior.copy(task = prior.task.copy(stage = "Unpublished"))) }
            assertSame(denial, error.cause)
            assertEquals(1, attempts)
            assertTrue(Thread.currentThread().isInterrupted)
            assertArrayEquals(oldBytes, canonical.readBytes())
            assertEquals(prior.task.stage, store.record(prior.task.id)?.task?.stage)
            assertEquals(prior.task.stage, store.recordsFor(prior.root, prior.task.videoId).single().task.stage)
        } finally { Thread.interrupted() }
    }

    @Test fun initialTransientOutboxReadsRecoverInPlaceWithoutRereadingHealthyNeighbors() {
        val harness = Harness("retry-read")
        val record = harness.record()
        val other = newBridgeSong(record.root, "https://www.youtube.com/watch?v=BBBBBBBBBBB", "guide", 2L)
        BridgeSongStore(harness.outbox).save(other)
        val reads = mutableMapOf<String, Int>()
        var unavailable = true
        val store = BridgeSongStore(harness.outbox) { file ->
            reads[file.name] = (reads[file.name] ?: 0) + 1
            if (file.name == "${record.task.id}.json" && unavailable) throw IOException("temporary read")
            file.readText(Charsets.UTF_8)
        }
        repeat(2) { assertThrows(IOException::class.java) { store.records() } }
        assertEquals(1, reads["${other.task.id}.json"])
        unavailable = false
        assertEquals(setOf(record.task.id, other.task.id), store.records().map { it.task.id }.toSet())
        assertArrayEquals(CanonicalJson.encode(record.request), CanonicalJson.encode(store.records().single { it.task.id == record.task.id }.request))
        assertEquals(record.approval, store.records().single { it.task.id == record.task.id }.approval)
        assertTrue(store.damagedTasks().isEmpty())
        assertEquals(1, reads["${other.task.id}.json"])
        File(harness.outbox, "unrelated.json").writeText("{}", Charsets.UTF_8)
        assertTrue(BridgeSongStore(harness.outbox).damagedTasks().isEmpty())
    }

    @Test fun perItemImportFailuresPreserveTheExactDispositionAndMalformedBatchesStayGeneric() {
        val id = "AAAAAAAAAAA"
        for ((code, disposition) in listOf("ENRICHMENT_UPDATE_STALE" to BridgeFailureDisposition.REJECT,
            "ENRICHMENT_REVISION_CONFLICT" to BridgeFailureDisposition.REJECT,
            "BUNDLE_RECOVERY_REQUIRED" to BridgeFailureDisposition.WAIT,
            "STORAGE_RESERVE_LOW" to BridgeFailureDisposition.RETRY)) {
            val item = app.stemwise.enrichment.EnrichmentImportItem(id, id, "invalid", code)
            val error = assertThrows(EnrichmentImportException::class.java) {
                requireBridgeSongImport(app.stemwise.enrichment.EnrichmentImportResult(listOf(item)), id)
            }
            assertEquals(code, error.code)
            assertEquals(disposition, bridgeFailureDisposition(error))
        }
        requireBridgeSongImport(app.stemwise.enrichment.EnrichmentImportResult(listOf(
            app.stemwise.enrichment.EnrichmentImportItem(id, id, "unchanged"))), id)
        assertThrows(IllegalStateException::class.java) {
            requireBridgeSongImport(app.stemwise.enrichment.EnrichmentImportResult(emptyList()), id)
        }
    }

    @Test fun registrationChangesAndBridgeRequestCancellationWaitWithoutReplayingAcceptedWork() = runBlocking {
        for ((code, status) in listOf("app_registration_changed" to 409, "app_request_cancelled" to 503)) {
            val harness = Harness(code)
            val record = harness.record()
            assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
            val accepted = BridgeSongStore(harness.outbox).records().single()
            val failure = BridgeApiFailure(status, code, "Exact Bridge detail for $code", false)
            assertEquals(BridgeFailureDisposition.WAIT, bridgeFailureDisposition(failure))
            harness.transport.afterLookup = { throw failure }
            repeat(2) {
                assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
                val waiting = BridgeSongStore(harness.outbox).records().single()
                assertEquals(BridgeSongPhase.WAITING, waiting.task.phase)
                assertEquals(accepted.submission, waiting.submission)
                assertEquals(accepted.task.id, waiting.task.id)
                assertArrayEquals(CanonicalJson.encode(accepted.request), CanonicalJson.encode(waiting.request))
                assertTrue(waiting.task.message.orEmpty().contains("Exact Bridge detail for $code"))
                assertTrue(waiting.task.message.orEmpty().contains(if (status == 409) "registration changed" else "Bridge stopped this request"))
            }
            assertEquals(1, harness.transport.submitCount)
        }
    }

    @Test fun exactReceiptOnlyAdmitsVerifiedMatchingBytesOrConclusiveAbsence() {
        val file = descriptor("d".repeat(32), "song.zip", byteArrayOf(1, 2, 3))
        val absent = JSONObject().put("state", "absent").put("pcIdentity", "paired-pc").put("transferId", file.id)
        assertFalse(bridgeReceivedFileIsRetained(absent, "paired-pc", file))
        val verified = JSONObject().put("state", "received_local_verified").put("pcIdentity", "paired-pc").put("file", file.json())
        assertTrue(bridgeReceivedFileIsRetained(verified, "paired-pc", file))
        for (invalid in listOf(
            JSONObject(absent.toString()).put("pcIdentity", "other-pc"),
            JSONObject(absent.toString()).put("transferId", "e".repeat(32)),
            JSONObject(absent.toString()).put("file", file.json()),
            JSONObject(absent.toString()).put("state", "unreadable"),
            JSONObject(verified.toString()).put("file", file.copy(sha256 = "f".repeat(64)).json())
        )) assertThrows(IllegalStateException::class.java) { bridgeReceivedFileIsRetained(invalid, "paired-pc", file) }
    }

    @Test fun repeatedDistinctFailuresRemainReadableAndCancellationSurvivesAStaleProgressSave() {
        val harness = Harness("bounded-failures")
        val record = harness.record()
        val store = BridgeSongStore(harness.outbox)
        var current = record.copy(task = record.task.copy(message = "Remote warning\n\nBridge diagnostics:\nremote-observation-proof"))
        repeat(1_000) { index ->
            current = current.copy(task = current.task.copy(phase = BridgeSongPhase.RETRYING,
                message = retainBridgeMessage(current.task.message, "Current failure $index\n\nBridge diagnostics:\nLatest failure $index")),
                attempts = (current.attempts + BridgeAttempt(index + 1, 1L, 1L, "RETRYING", "failed",
                    cause = "Current failure $index")).takeLast(8))
            if (index % 100 == 99) store.save(current)
        }
        val reloaded = BridgeSongStore(harness.outbox).records().single()
        assertTrue(reloaded.task.message.orEmpty().contains("Current failure 999"))
        assertTrue(reloaded.task.message.orEmpty().contains("Latest failure 999"))
        assertTrue(reloaded.task.message.orEmpty().contains("remote-observation-proof"))
        assertFalse(reloaded.task.message.orEmpty().contains("Current failure 998"))
        assertFalse(reloaded.task.message.orEmpty().contains("Latest failure 998"))
        assertEquals(8, reloaded.attempts.size)
        assertTrue(File(harness.outbox, "${record.task.id}.json").length() < 1_048_576L)
        store.update(record.task.id) { it.copy(task = it.task.copy(cancelRequested = true, phase = BridgeSongPhase.CANCELLING)) }
        store.save(current.copy(task = current.task.copy(stage = "Late network response")))
        assertTrue(BridgeSongStore(harness.outbox).records().single().task.cancelRequested)
        assertFalse(reloaded.task.copy(phase = BridgeSongPhase.RECEIVING).canCancel)
    }

    @Test fun pendingReviewedPreparationCannotPrepareInputsOrSubmitUntilCatalogValidationCommits() = runBlocking {
        val harness = Harness("pending-reviewed-send")
        val pending = authorizedSong(root, originalText, guide, 1L).let {
            it.copy(preparation = BridgeSendPreparation(), task = it.task.copy(phase = BridgeSongPhase.PREPARING))
        }
        val store = BridgeSongStore(harness.outbox)
        store.save(pending)
        val durable = File(harness.outbox, "${pending.task.id}.json").readBytes()
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(pending.task.id))
        assertEquals(0, harness.transport.prepareCount)
        assertEquals(0, harness.transport.submitCount)
        assertArrayEquals(durable, File(harness.outbox, "${pending.task.id}.json").readBytes())
        store.save(pending.copy(preparation = BridgeSendPreparation(wakeClaimed = true, catalogValidated = true),
            task = pending.task.copy(phase = BridgeSongPhase.SUBMITTING)))
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(pending.task.id))
        assertEquals(1, harness.transport.submitCount)
        assertEquals(BridgeSubmission.ACKNOWLEDGED, BridgeSongStore(harness.outbox).records().single().submission)
    }

    @Test fun wakeClaimRoundTripsSeparatelyFromSubmissionAndCannotBeAddedToExistingApprovedWork() {
        val store = BridgeSongStore(File(directory, "preparation-roundtrip"))
        val pending = authorizedSong(root, originalText, guide, 1L).let {
            it.copy(preparation = BridgeSendPreparation(), task = it.task.copy(phase = BridgeSongPhase.PREPARING))
        }
        store.save(pending)
        store.save(pending.copy(preparation = BridgeSendPreparation(wakeClaimed = true)))
        val claimed = BridgeSongStore(store.directory).records().single()
        assertTrue(claimed.preparation!!.wakeClaimed)
        assertFalse(claimed.preparation!!.catalogValidated)
        assertEquals(BridgeSubmission.UNSENT, claimed.submission)
        assertArrayEquals(CanonicalJson.encode(pending.request), CanonicalJson.encode(claimed.request))
        assertThrows(IllegalStateException::class.java) { store.save(claimed.copy(preparation = null)) }
        assertThrows(IllegalStateException::class.java) { store.save(claimed.copy(submission = BridgeSubmission.UNKNOWN)) }
        val validated = claimed.copy(preparation = BridgeSendPreparation(true, true))
        store.save(validated)
        assertThrows(IllegalStateException::class.java) { store.save(claimed) }
        assertThrows(IllegalStateException::class.java) { BridgeSongRecord.parse(pending.json().put("preparation", "invalid")) }

        val existing = authorizedSong(root, "https://youtu.be/abcdefghijk", guide, 2L)
        val existingJson = existing.json().apply { remove("preparation") }
        val reopened = BridgeSongRecord.parse(existingJson)
        assertEquals(null, reopened.preparation)
        store.save(reopened)
        assertThrows(IllegalStateException::class.java) { store.save(reopened.copy(preparation = BridgeSendPreparation())) }
    }

    @Test fun cancellingReviewedPreparationIsLocalAndNeverWaitsForWakeOrCatalog() = runBlocking {
        val harness = Harness("cancel-reviewed-preparation")
        val pending = authorizedSong(root, originalText, guide, 1L).let {
            it.copy(preparation = BridgeSendPreparation(wakeClaimed = true),
                task = it.task.copy(phase = BridgeSongPhase.WAITING_PC, retryable = true))
        }
        BridgeSongStore(harness.outbox).save(pending)
        harness.workflow().requestCancellation(pending.task.id)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(pending.task.id))
        val cancelled = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.CANCELLED, cancelled.task.phase)
        assertTrue(cancelled.remoteRetired)
        assertEquals(BridgeSubmission.UNSENT, cancelled.submission)
        assertEquals(0, harness.transport.prepareCount)
        assertEquals(0, harness.transport.submitCount)
        assertEquals(0, harness.transport.cancelCount)
    }

    @Test fun failedVerifiedOutputOnlyOffersImportRecoveryForItsExactLiveOwner() {
        val task = authorizedSong(root, originalText, guide, 1L)
        val failed = task.copy(task = task.task.copy(phase = BridgeSongPhase.FAILED, message = "Original import error"),
            inputsVerified = true, submission = BridgeSubmission.ACKNOWLEDGED,
            output = descriptor("b".repeat(32), "song.zip", byteArrayOf(1)), outputVerified = true)
        assertTrue(failed.canRetryImport)
        assertTrue(failed.resumableTask.canRetry)
        assertTrue(failed.resumableTask.localImportPending)
        assertFalse(failed.resumableTask.canCancel)
        assertEquals(failed.task.message, failed.resumableTask.message)
        val generic = "This task needs attention. Copy task diagnostics for the cause, then resume this same task."
        val cause = "EnrichmentImportException: SOURCE_TIMELINE_MISMATCH\nOriginal recorded stack"
        val recordedFailure = failed.copy(task = failed.task.copy(message = generic), attempts = listOf(
            BridgeAttempt(1, 1L, 1L, "IMPORTING", "failed", cause = cause)))
        assertTrue(recordedFailure.resumableTask.message.orEmpty().startsWith("EnrichmentImportException: SOURCE_TIMELINE_MISMATCH"))
        assertFalse(recordedFailure.resumableTask.message.orEmpty().contains(generic))
        assertTrue(recordedFailure.resumableTask.message.orEmpty().contains(cause))
        assertEquals(generic, recordedFailure.task.message)
        for (excluded in listOf(failed.copy(approval = null), failed.copy(connection = null),
            failed.copy(inputsVerified = false), failed.copy(submission = BridgeSubmission.UNSENT),
            failed.copy(submission = BridgeSubmission.UNKNOWN), failed.copy(outputVerified = false),
            failed.copy(output = null), failed.copy(imported = true), failed.copy(retired = true),
            failed.copy(remoteRetired = true), failed.copy(task = failed.task.copy(cancelRequested = true)),
            failed.copy(task = failed.task.copy(phase = BridgeSongPhase.CANCELLED)))) {
            assertFalse(excluded.toString(), excluded.canRetryImport)
        }
    }

    @Test fun pendingCancellationObservationPreservesItsReturnedErrors() = runBlocking {
        val harness = Harness("pending-cancel-errors")
        val approved = harness.record()
        harness.workflow().advance(approved.task.id)
        harness.workflow().requestCancellation(approved.task.id)
        val saved = BridgeSongStore(harness.outbox).records().single()
        val job = completeBridgeJobObservation(checkNotNull(harness.transport.remote), saved.request,
            diagnosticLog("cancel_observation_error", "PC could not confirm cancellation"))
        job.put("cancelRequested", 1L).put("cancelRetired", 0L)
            .put("issue", JSONObject().put("code", "provider_issue").put("message", "Cancellation issue")
                .put("action", "Inspect the PC provider").put("retryable", true))
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(approved.task.id))
        val waiting = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.CANCELLING, waiting.task.phase)
        for (detail in listOf("Cancellation issue", "Inspect the PC provider", "first_diagnostic", "cancel_observation_error"))
            assertTrue(detail, waiting.task.message.orEmpty().contains(detail))
        assertEquals(1, harness.transport.cancelCount)
        assertEquals(1, harness.transport.submitCount)
    }

    @Test fun invalidCompletedReportPreservesTheObservedErrorsBeforeRejectingItsOutput() = runBlocking {
        val harness = Harness("invalid-report-errors")
        val approved = harness.record()
        harness.workflow().advance(approved.task.id)
        val submitted = BridgeSongStore(harness.outbox).records().single()
        val job = completeBridgeJobObservation(harness.complete(submitted), submitted.request,
            diagnosticLog("previous_native_error", "Earlier PC error"))
        job.getJSONObject("report").put("code", "invalid_completion_code")
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(approved.task.id))
        val failed = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.FAILED, failed.task.phase)
        assertTrue(failed.task.message.orEmpty().contains("BRIDGE_SONG_REPORT_INVALID"))
        assertTrue(failed.task.message.orEmpty().contains("previous_native_error"))
        val readable = failed.task.message!!.substringBefore("\n\nBridge diagnostics:\n")
        assertTrue(readable.startsWith("The PC result does not match this song request."))
        assertTrue(readable.contains("Earlier PC error"))
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
        assertFalse(harness.events.contains("receive"))
        assertFalse(harness.events.contains("ack"))
        assertEquals(1, harness.transport.submitCount)
    }

    @Test fun unconfirmedSubmissionRecoveryKeepsTheOriginalFailureVisible() {
        val harness = Harness("recovery-error-visible")
        val approved = harness.record()
        val failed = approved.copy(inputsVerified = true, submission = BridgeSubmission.UNKNOWN,
            task = approved.task.copy(phase = BridgeSongPhase.FAILED, message = "BRIDGE_INPUT_CHANGED"))
        assertEquals(BridgeSongPhase.WAITING, failed.resumableTask.phase)
        assertTrue(failed.resumableTask.message.orEmpty().contains("BRIDGE_INPUT_CHANGED"))
        assertTrue(failed.resumableTask.message.orEmpty().contains("It will not be sent again."))
        assertEquals("BRIDGE_INPUT_CHANGED", failed.task.message)
        assertEquals(BridgeSongPhase.FAILED, failed.task.phase)
    }

    @Test fun runningAndFailedObservationsPreserveEveryReturnedDiagnosticAndIssueField() = runBlocking {
        val harness = Harness("all-remote-errors")
        val approved = harness.record()
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(approved.task.id))
        val submitted = BridgeSongStore(harness.outbox).records().single()
        val log = diagnosticLog("native_turn_failed", "Provider detail " + "x".repeat(700))
        val job = completeBridgeJobObservation(checkNotNull(harness.transport.remote), submitted.request, log)
        assertEquals("Earlier error\n\nProvider detail " + "x".repeat(700),
            job.getString("diagnostic").substringBefore("\n\nBridge diagnostics:\n"))
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(approved.task.id))
        var saved = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.RUNNING, saved.task.phase)
        assertTrue(saved.task.message.orEmpty().contains("first_diagnostic"))
        assertTrue(saved.task.message.orEmpty().contains("native_turn_failed"))
        assertTrue(saved.task.message.orEmpty().contains("x".repeat(700)))
        assertTrue(saved.task.message.orEmpty().contains("repair_provider_on_pc"))
        val retainedLog = JSONObject(saved.task.message!!.substringAfter("Bridge diagnostics:\n"))
        assertArrayEquals(CanonicalJson.encode(log), CanonicalJson.encode(retainedLog))

        job.put("issue", JSONObject().put("code", "provider_issue").put("message", "Still working with an issue")
            .put("action", "Inspect the PC provider settings").put("retryable", true))
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(approved.task.id))
        saved = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.WAITING, saved.task.phase)
        for (text in listOf("provider_issue", "Still working with an issue", "Inspect the PC provider settings", "first_diagnostic"))
            assertTrue(text, saved.task.message.orEmpty().contains(text))

        job.put("state", "failed").put("code", "native_turn_failed")
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(approved.task.id))
        saved = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.FAILED, saved.task.phase)
        assertEquals(BridgeSubmission.ACKNOWLEDGED, saved.submission)
        assertTrue(saved.task.message.orEmpty().contains("Still working with an issue"))
        assertTrue(saved.task.message.orEmpty().contains("first_diagnostic"))
        assertFalse(saved.task.canRetry)
        assertEquals(1, harness.transport.submitCount)
        assertArrayEquals(CanonicalJson.encode(submitted.request), CanonicalJson.encode(saved.request))
    }

    @Test fun completedPackageKeepsRemoteErrorsThroughImportAndDelivery() = runBlocking {
        val harness = Harness("completed-with-diagnostics")
        val approved = harness.record()
        harness.workflow().advance(approved.task.id)
        val submitted = BridgeSongStore(harness.outbox).records().single()
        completeBridgeJobObservation(harness.complete(submitted), submitted.request,
            diagnosticLog("provider_recovered", "Recovered provider warning"))
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(approved.task.id))
        val saved = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.IMPORTED, saved.task.phase)
        assertTrue(saved.imported)
        assertTrue(saved.task.message.orEmpty().contains("provider_recovered"))
        assertTrue(saved.task.message.orEmpty().contains("Recovered provider warning"))
        assertTrue(saved.task.message.orEmpty().contains("repair_provider_on_pc"))
        assertEquals(1, harness.transport.submitCount)
    }

    @Test fun timelineImportFailurePreservesItsTypedCodePackageAndExistingLibrary() = runBlocking {
        val harness = Harness("timeline-import-error")
        val approved = harness.record()
        harness.workflow().advance(approved.task.id)
        val submitted = BridgeSongStore(harness.outbox).records().single()
        completeBridgeJobObservation(harness.complete(submitted), submitted.request,
            diagnosticLog("prior_provider_warning", "Earlier provider issue"))
        harness.installFailure = EnrichmentImportException("SOURCE_TIMELINE_MISMATCH", "Decoded duration differs from the supplied timeline")
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(approved.task.id))
        val saved = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.FAILED, saved.task.phase)
        assertFalse(saved.task.canRetry)
        assertFalse(saved.imported)
        assertTrue(saved.outputVerified)
        assertTrue(saved.task.message.orEmpty().contains("SOURCE_TIMELINE_MISMATCH"))
        assertTrue(saved.task.message.orEmpty().contains("Decoded duration differs from the supplied timeline"))
        assertTrue(saved.task.message.orEmpty().contains("Earlier provider issue"))
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
        assertArrayEquals(harness.transport.receiveBytes, BridgeSongStore(harness.outbox).file(approved.task.id, "zip").readBytes())
        assertFalse(harness.events.contains("ack"))
        assertEquals(1, harness.transport.submitCount)
        val durable = File(harness.outbox, "${approved.task.id}.json").readBytes()
        harness.workflow().requestCancellation(approved.task.id)
        assertArrayEquals(durable, File(harness.outbox, "${approved.task.id}.json").readBytes())
        assertEquals(0, harness.transport.cancelCount)
    }

    @Test fun appApiFailureKeepsItsFullCodeAndMessageThroughTheWorkflow() = runBlocking {
        val raw = "Provider failure: " + "z".repeat(900)
        val error = BridgeApiFailure(503, "provider_unavailable", raw, true)
        val harness = Harness("api-error-details")
        val approved = harness.record()
        harness.workflow().advance(approved.task.id)
        harness.complete(BridgeSongStore(harness.outbox).records().single())
        harness.transport.receiveFailure = error
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(approved.task.id))
        val before = BridgeSongStore(harness.outbox).records().single()
        assertTrue(before.task.message.orEmpty().contains("provider_unavailable"))
        assertTrue(before.task.message.orEmpty().contains(raw))
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(approved.task.id))
        val after = BridgeSongStore(harness.outbox).records().single()
        assertEquals(before.task.message, after.task.message)
    }

    private fun diagnosticLog(code: String, summary: String): JSONObject {
        fun entry(value: String, text: String) = JSONObject().put("source", "native").put("revision", 1)
            .put("observedAt", 123L).put("error", JSONObject().put("code", value).put("operation", "observe_native_turn")
                .put("summary", text).put("cause", "codex_native_failure").put("retryable", false)
                .put("context", JSONObject().put("additional_details", "[redacted] provider diagnostic"))
                .put("expected", "completed").put("actual", "failed").put("suggestedFix", JSONArray().put("repair_provider_on_pc")))
        return JSONObject().put("entries", JSONArray().put(entry("first_diagnostic", "Earlier error"))
            .put(entry(code, summary))).put("dropped", 3)
    }

    @get:Rule val persistedFailureDiagnostics = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            System.err.println("Persisted Bridge state for ${description.methodName}:\n$diagnosticsAtExit")
            harnesses.forEach { harness ->
                harness.workerFailure?.let { System.err.println("Worker boundary failure:\n${it.stackTraceToString().take(8192)}") }
            }
        }
    }
    @Test
    fun twoConnectionProfilesRouteSavedJobsAndCannotRetargetAnApprovedRequest() = runBlocking {
        val store = BridgeSongStore(File(directory, "profiles"))
        val secondProfile = BridgeConnectionProfile("bridge-b.tailnet.ts.net", "bridge-b", "pc-b")
        val first = authorizedSong(root, originalText, guide, 1L).also(store::save)
        val second = authorizedSong(root, "https://youtu.be/abcdefghijk", guide, 2L)
            .copy(connection = secondProfile).also(store::save)
        val firstTransport = FakeTransport(mutableListOf())
        val secondTransport = FakeTransport(mutableListOf())
        val routed = mutableListOf<BridgeConnectionProfile>()
        fun workflow() = BridgeSongWorkflow(BridgeSongStore(store.directory), { profile ->
            routed += profile
            when (profile) {
                connection -> firstTransport
                secondProfile -> secondTransport
                else -> error("Unexpected connection")
            }
        }, {}, { _, _, _ -> error("No completed output") }, { _, _ -> }, {}, {})
        assertEquals(BridgeSongWorkProgress.PENDING, workflow().advance(first.task.id))
        assertEquals(BridgeSongWorkProgress.PENDING, workflow().advance(second.task.id))
        // Recreate workflow/store and resume accepted A after another connection's job was sent.
        assertEquals(BridgeSongWorkProgress.PENDING, workflow().advance(first.task.id))
        assertEquals(listOf(connection, secondProfile, connection), routed)
        assertEquals(1, firstTransport.submitCount)
        assertEquals(1, secondTransport.submitCount)
        assertEquals(first.task.id, firstTransport.remote?.getJSONObject("input")?.getString("requestId"))
        assertEquals(second.task.id, secondTransport.remote?.getJSONObject("input")?.getString("requestId"))
        val file = File(store.directory, "${first.task.id}.json")
        val prior = file.readBytes()
        val accepted = BridgeSongStore(store.directory).records().single { it.task.id == first.task.id }
        assertThrows(IllegalStateException::class.java) { store.save(accepted.copy(connection = secondProfile)) }
        assertArrayEquals(prior, file.readBytes())
        assertFalse(connection.registrationKey() == secondProfile.registrationKey())
    }

    @Test fun transferCleanupPreservesBundleInputBeforeDeletingItsTaskAndRetriesARefusedHandoff() = runBlocking {
        val harness = Harness("bundle-input-handoff")
        val record = harness.record()
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        checkNotNull(harness.transport.remote).put("state", "failed")
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        val archive = BridgeSongStore(harness.outbox).file(record.task.id, "zip")
        archive.writeText("accepted bundle bytes", Charsets.UTF_8)
        harness.beforeArchiveCleanup = { file ->
            assertEquals(archive, file)
            assertTrue(file.isFile)
            throw IOException("Bundle handoff unavailable")
        }
        assertFalse(harness.workflow().retire(record.task.id))
        val retained = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.RETIRING, retained.task.phase)
        assertTrue(retained.task.canRetry)
        assertTrue(retained.remoteRetired)
        assertEquals("accepted bundle bytes", archive.readText(Charsets.UTF_8))
        val recovery = File(harness.outbox.parentFile, "bundle-recovery.zip")
        harness.beforeArchiveCleanup = { file ->
            assertTrue("Input disappeared before its recovery owner accepted it", file.isFile)
            check(file.renameTo(recovery))
        }
        assertTrue(harness.workflow().retire(record.task.id))
        assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
        assertFalse(archive.exists())
        assertEquals("accepted bundle bytes", recovery.readText(Charsets.UTF_8))
    }

    @Test fun exceptionExplanationCannotMasqueradeAsARejectionCode() = runBlocking {
        val harness = Harness("untyped-explanation")
        val record = harness.record()
        harness.complete(record)
        harness.installFailure = IOException("BRIDGE_SONG_REPORT_INVALID")
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        val saved = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.RETRYING, saved.task.phase)
        assertEquals(BridgeFailureDisposition.RETRY, saved.attempts.last().disposition)
        assertFalse(saved.imported)
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
        assertFalse(harness.events.contains("ack"))
        harness.installFailure = null
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertEquals(0, harness.transport.submitCount)
    }

    @Test fun importRecoveryUsesTheCodeEvenWhenItsExplanationChanges() = runBlocking {
        val harness = Harness("typed-recovery")
        val record = harness.record()
        harness.complete(record)
        harness.installFailure = EnrichmentImportException("BUNDLE_RECOVERY_REQUIRED", "Restore this transaction first")
        harness.workflow().advance(record.task.id)
        val saved = BridgeSongStore(harness.outbox).records().single()
        assertEquals("WAITING", saved.task.state)
        assertTrue(saved.task.canRetry)
        assertFalse(saved.imported)
        assertTrue(BridgeSongStore(harness.outbox).file(record.task.id, "zip").isFile)
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
        assertFalse(harness.events.contains("ack"))
    }

    @Test fun unrecognizedDurablePhaseIsQuarantinedWithoutAuthorizingWork() {
        val harness = Harness("unrecognized-phase")
        val record = harness.record()
        val file = File(harness.outbox, "${record.task.id}.json")
        val malformed = JSONObject(file.readText(Charsets.UTF_8)).put("state", "completed")
        file.writeText(malformed.toString(), Charsets.UTF_8)
        val savedBytes = file.readBytes()
        val restarted = BridgeSongStore(harness.outbox)
        assertTrue(restarted.records().isEmpty())
        assertEquals(BridgeSongPhase.OUTBOX_DAMAGED, restarted.damagedTasks().single().phase)
        assertArrayEquals(savedBytes, file.readBytes())
        assertEquals(0, harness.transport.submitCount)
    }

    @Test fun neverSubmittedCancellationCompletesLocallyWhileOffline() = runBlocking {
        val harness = Harness("unsent-cancel")
        val record = harness.record()
        harness.transport.cancelFailure = IOException("offline")
        harness.workflow().requestCancellation(record.task.id)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertEquals("CANCELLED", BridgeSongStore(harness.outbox).records().single().task.state)
        assertEquals(0, harness.transport.cancelCount)
        assertEquals(0, harness.transport.submitCount)
    }

    @Test fun boundedDiagnosticsKeepTheUnderlyingCauseBeforeLongWrapperStacks() = runBlocking {
        val harness = Harness("nested-cause")
        val record = harness.record()
        harness.complete(record)
        harness.installFailure = IllegalStateException("wrapper " + "x".repeat(5000), IOException("underlying storage failure"))
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        val cause = BridgeSongStore(harness.outbox).records().single().attempts.last().cause!!
        assertTrue(cause, cause.startsWith("java.io.IOException: underlying storage failure"))
        assertTrue(cause.length <= 4096)
    }

    @Test fun importedSongCannotEnterRemoteCancellationWhenAcknowledgementIsPending() = runBlocking {
        val harness = Harness("imported-cancel")
        val record = harness.record()
        harness.complete(record)
        harness.transport.ackFailure = BridgeApiFailure(404, "job_unavailable", "Tracking unavailable", false)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        val before = BridgeSongStore(harness.outbox).records().single()
        assertTrue(before.imported)
        assertFalse(before.task.canCancel)
        harness.workflow().requestCancellation(record.task.id)
        assertFalse(BridgeSongStore(harness.outbox).records().single().task.cancelRequested)
        harness.transport.remote = null
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertEquals(0, harness.transport.cancelCount)
        assertEquals(1, harness.events.count { it == "install" })
    }

    @Test fun disappearedAcceptedAndUnknownJobsNeverResubmitAfterRestart() = runBlocking {
        for (lostReceipt in listOf(false, true)) {
            val harness = Harness("missing-$lostReceipt")
            val record = harness.record()
            harness.transport.loseSubmitAcknowledgement = lostReceipt
            assertEquals(if (lostReceipt) BridgeSongWorkProgress.RETRY else BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
            val request = CanonicalJson.encode(BridgeSongStore(harness.outbox).records().single().request)
            harness.transport.remote = null
            repeat(2) { harness.workflow().advance(record.task.id) }
            assertEquals(1, harness.transport.submitCount)
            val saved = BridgeSongStore(harness.outbox).records().single()
            assertEquals("WAITING", saved.task.state)
            assertArrayEquals(request, CanonicalJson.encode(saved.request))
        }
    }

    @Test fun receivedZipImportsWithoutRemoteObservationAndKeepsAcknowledgementPending() = runBlocking {
        val harness = Harness("received-local")
        val record = harness.record()
        harness.complete(record)
        harness.installFailure = EnrichmentImportException("STORAGE_RESERVE_LOW")
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        val descriptor = BridgeSongStore(harness.outbox).records().single().output
        harness.installFailure = null
        harness.transport.remote = null
        harness.transport.afterLookup = { error("Verified local output must not require remote polling") }
        harness.transport.ackFailure = BridgeApiFailure(404, "job_unavailable", "Tracking unavailable", false)
        harness.workflow().advance(record.task.id)
        val installed = BridgeSongStore(harness.outbox).records().single()
        assertTrue(installed.imported)
        assertEquals(descriptor, installed.output)
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertTrue(BridgeSongStore(harness.outbox).file(record.task.id, "zip").isFile)
        harness.workflow().advance(record.task.id)
        assertEquals(1, harness.events.count { it == "receive" })
        assertEquals(2, harness.events.count { it == "install" })
        assertEquals(0, harness.transport.submitCount)
    }

    @Test fun missingRemoteTrackingKeepsAnInstalledSongRetryable() = runBlocking {
        val harness = Harness("imported-tracking-404")
        val record = harness.record()
        harness.complete(record)
        harness.transport.ackFailure = BridgeApiFailure(404, "job_unavailable", "Tracking unavailable", false)
        repeat(2) {
            val progress = harness.workflow().advance(record.task.id)
            assertEquals(bridgeTaskDiagnostics(BridgeSongStore(harness.outbox).records().single()),
                BridgeSongWorkProgress.COMPLETE, progress)
            val saved = BridgeSongStore(harness.outbox).records().single()
            assertTrue(saved.imported)
            assertTrue(saved.task.importSucceeded)
            assertTrue(saved.task.deliveryPending)
            assertEquals(BridgeSongPhase.ACKNOWLEDGING, saved.task.phase)
            assertTrue(saved.task.canRetry)
            assertFalse(saved.remoteRetired || saved.retired || saved.task.canForget)
            assertTrue(checkNotNull(saved.output).matches(BridgeSongStore(harness.outbox).file(record.task.id, "zip")))
        }
        harness.transport.ackFailure = null
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertFalse(BridgeSongStore(harness.outbox).records().single().task.deliveryPending)
        assertEquals(1, harness.events.count { it == "install" })
        assertEquals(1, harness.events.count { it == "receive" })
        assertEquals(0, harness.transport.submitCount)
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
    }

    @Test fun missingInstalledDeliveryReconcilesExactRetirementBeforeLocalForget() = runBlocking {
        val harness = Harness("imported-retirement-404")
        val record = harness.record()
        harness.complete(record)
        harness.transport.ackFailure = BridgeApiFailure(404, "job_unavailable", "Tracking unavailable", false)
        harness.workflow().advance(record.task.id)
        assertEquals(1, harness.transport.retirementCount)
        assertEquals(BridgeSongPhase.ACKNOWLEDGING, BridgeSongStore(harness.outbox).records().single().task.phase)
        harness.transport.retirementFailure = BridgeApiFailure(410, "request_deleted", "Request retired", false)
        harness.workflow().advance(record.task.id)
        val retired = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.EXPIRED, retired.task.phase)
        assertTrue(retired.task.canForget)
        assertFalse(retired.task.phase == BridgeSongPhase.IMPORTED)
        assertTrue(harness.workflow().retire(record.task.id))
        assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertEquals(1, harness.events.count { it == "install" })
        assertEquals(0, harness.transport.submitCount + harness.transport.deleteCount + harness.transport.discardCount)
    }

    @Test fun expiredImportedTrackingCanRetireOnlyAfterReadbackInTheSameReturnedLibrary() = runBlocking {
        val harness = Harness("expired-library-return")
        val record = harness.record()
        harness.complete(record)
        harness.transport.ackFailure = BridgeApiFailure(410, "request_deleted", "Tracking expired", false)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        val store = BridgeSongStore(harness.outbox)
        val retained = File(harness.outbox, "${record.task.id}.json").readBytes()
        harness.currentRoot = RootAuthority("other-library", "content://fixture/tree/other", root.epoch + 1)
        assertThrows(BridgeLocalFailure::class.java) { runBlocking {
            harness.workflow().retire(record.task.id, harness.currentRoot)
        } }
        assertArrayEquals(retained, File(harness.outbox, "${record.task.id}.json").readBytes())
        harness.currentRoot = root.copy(epoch = root.epoch + 2)
        val calls = harness.events.toList()
        assertTrue(harness.workflow().retire(record.task.id, harness.currentRoot))
        assertEquals(calls + "verify", harness.events)
        assertTrue(store.records().isEmpty())
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
    }

    @Test fun retainedInboxRecoversAfterCopyFailureWithoutObservingTheExpiredRemoteJob() = runBlocking {
        val harness = Harness("retained-inbox-recovery")
        val record = harness.record()
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        harness.complete(record)
        harness.transport.receiveFailure = IOException("App outbox copy interrupted after SDK receipt")
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        val store = BridgeSongStore(harness.outbox)
        val accepted = store.records().single()
        assertTrue(accepted.output != null && !accepted.outputVerified)
        assertFalse(store.file(record.task.id, "zip").exists())
        harness.transport.retainedInboxBytes = harness.transport.receiveBytes
        harness.transport.online = false
        harness.transport.afterLookup = { error("Local recovery contacted the remote job") }
        val before = harness.events.toList()
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        val recovered = BridgeSongStore(harness.outbox).records().single()
        assertTrue(recovered.imported && recovered.outputVerified)
        assertEquals(accepted.output, recovered.output)
        assertEquals(accepted.task.id, recovered.task.id)
        assertEquals(before + listOf("receive-local", "install", "verify"), harness.events)
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertEquals(1, harness.transport.submitCount)
    }

    @Test fun expiredInstalledDeliveryRechecksTheSongBeforeRemovingRedundantCopies() = runBlocking {
        val harness = Harness("imported-tracking-expired")
        val record = harness.record()
        harness.complete(record)
        harness.transport.ackFailure = BridgeApiFailure(410, "request_deleted", "Tracking removed", false)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        val store = BridgeSongStore(harness.outbox)
        val saved = store.records().single()
        assertTrue(saved.imported && saved.outputVerified)
        assertEquals(BridgeSongPhase.EXPIRED, saved.task.phase)
        val retained = listOf("zip", "md", "input").associateWith { store.file(record.task.id, it).readBytes() }
        // imported=true may precede the first successful readback. A cold Forget must prove it.
        harness.published.writeText("incomplete song", Charsets.UTF_8)
        assertThrows(IllegalStateException::class.java) { runBlocking { harness.workflow().retire(record.task.id) } }
        for ((suffix, bytes) in retained) assertArrayEquals(bytes, store.file(record.task.id, suffix).readBytes())
        assertEquals(saved.json().toString(), BridgeSongStore(harness.outbox).records().single().json().toString())
        harness.published.writeText(videoId, Charsets.UTF_8)
        var preserved = 0
        harness.beforeArchiveCleanup = { preserved++ }
        val guide = store.file(record.task.id, "md")
        check(guide.delete())
        check(guide.mkdir())
        val blocked = File(guide, "cleanup-denied").apply { writeText("retained", Charsets.UTF_8) }
        assertThrows(java.nio.file.DirectoryNotEmptyException::class.java) { runBlocking { harness.workflow().retire(record.task.id) } }
        assertFalse(store.file(record.task.id, "zip").exists())
        assertTrue(BridgeSongStore(harness.outbox).records().single().imported)
        check(blocked.delete() && guide.delete())
        assertTrue(harness.workflow().retire(record.task.id))
        assertEquals(2, preserved)
        assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
        assertTrue(harness.outbox.listFiles()!!.isEmpty())
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertEquals(0, harness.transport.submitCount + harness.transport.deleteCount + harness.transport.discardCount)
    }

    @Test fun unapprovedTaskNeverTouchesTheBridgeAfterRestore() = runBlocking {
        val harness = Harness()
        val draft = newBridgeSong(root, originalText, guide, 123_456L)
        val store = BridgeSongStore(harness.outbox)
        store.save(draft)
        val before = File(harness.outbox, "${draft.task.id}.json").readBytes()
        assertFalse(harness.workflow().resumeForLibrary(draft.task.id, root))
        harness.workflow().requestCancellation(draft.task.id)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(draft.task.id))
        assertEquals(0, harness.transport.cancelCount)
        assertEquals(0, harness.transport.prepareCount)
        assertEquals(0, harness.transport.submitCount)
        assertTrue(harness.events.isEmpty())
        assertArrayEquals(before, File(harness.outbox, "${draft.task.id}.json").readBytes())
    }

    @Test fun missingOrChangedApprovalCannotReplaceDurableBytes() {
        val store = BridgeSongStore(File(directory, "approval-contract"))
        val draft = newBridgeSong(root, originalText, guide, 1L)
        store.save(draft)
        val file = File(store.directory, "${draft.task.id}.json")
        var before = file.readBytes()
        assertThrows(IllegalStateException::class.java) {
            store.save(draft.copy(task = draft.task.copy(phase = BridgeSongPhase.QUEUED)))
        }
        assertArrayEquals(before, file.readBytes())
        val approved = approveBridgeSong(draft, BridgeSongSubmission(originalText, "fixture-model", "high", "default"), guide, 2L, connection)
        store.save(approved)
        before = file.readBytes()
        val contextBefore = bridgeSongContext(approved)
        for (field in listOf("text", "model", "effort", "serviceTier", "instructions", "title", "createdAt")) {
            val changed = JSONObject(approved.request.toString()).put(field, when (field) {
                "text" -> "https://youtu.be/abcdefghijk"
                "createdAt" -> 3L
                else -> "changed"
            })
            assertThrows(IllegalStateException::class.java) { store.save(approved.copy(request = changed)) }
            assertArrayEquals(before, file.readBytes())
        }
        assertTrue(store.save(approved.copy(task = approved.task.copy(stage = "Preparing declared inputs"))))
        val restored = BridgeSongStore(store.directory).records().single()
        assertEquals(approved.approval, restored.approval)
        assertEquals(2L, restored.task.createdAtEpochMs)
        assertEquals("Preparing declared inputs", restored.task.stage)
        assertArrayEquals(contextBefore, bridgeSongContext(restored))
        val instructions = restored.request.getString("instructions")
        assertTrue(instructions.contains("Wrong or uncertain wording, pronunciation or performed occurrences withhold affected lyric claims and dependent features."))
        assertTrue(instructions.contains("Validate may complete independently verified audio with truthful partial, plain or unavailable lyrics."))
        assertTrue(instructions.contains("Recording identity, time-base and package integrity failures still block publication."))
        assertTrue(instructions.contains("Only completed/stemwise_song_ready may deliver its exact verified package."))
        assertFalse(instructions.contains("uncertain lyrics or pronunciation remain blockers"))
    }

    @Test fun nativeDefaultApprovalPersistsExactResetChoiceAndRejectsMutation() {
        for (tier in listOf("", "priority")) {
            val store = BridgeSongStore(File(directory, "native-default-${tier.ifEmpty { "empty" }}"))
            val selection = BridgeSongSubmission(originalText, "fixture-model", "high", tier)
            val approved = approveBridgeSong(newBridgeSong(root, originalText, guide, 1L), selection, guide, 2L, connection)
            assertEquals(tier.isEmpty(), approved.request.has("nativeDefaultServiceTier"))
            if (tier.isEmpty()) assertEquals(true, approved.request.get("nativeDefaultServiceTier"))
            store.save(approved)
            val restored = BridgeSongStore(store.directory).records().single()
            assertEquals(selection, restored.approval)
            assertArrayEquals(CanonicalJson.encode(approved.request), CanonicalJson.encode(restored.request))
            val file = File(store.directory, "${approved.task.id}.json")
            val before = file.readBytes()
            for (value in listOf<Any?>(null, false, true, "true")) {
                val changed = JSONObject(approved.request.toString())
                if (value == null) changed.remove("nativeDefaultServiceTier") else changed.put("nativeDefaultServiceTier", value)
                if (changed.toString() == approved.request.toString()) continue
                assertThrows(IllegalStateException::class.java) { store.save(approved.copy(request = changed)) }
                assertArrayEquals(before, file.readBytes())
            }
        }
    }

    @Test fun nativeDefaultRetryReusesExactApprovedBodyAfterLostAcknowledgement() = runBlocking {
        val harness = Harness("native-default-retry")
        val selection = BridgeSongSubmission(originalText, "fixture-model", "high", "")
        val approved = approveBridgeSong(newBridgeSong(root, originalText, guide, 1L), selection, guide, 2L, connection)
        BridgeSongStore(harness.outbox).save(approved)
        harness.transport.loseSubmitAcknowledgement = true
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(approved.task.id))
        val restored = BridgeSongStore(harness.outbox).records().single()
        assertEquals(selection, restored.approval)
        assertEquals(true, restored.request.get("nativeDefaultServiceTier"))
        assertEquals("", restored.request.getString("serviceTier"))
        val frozen = CanonicalJson.encode(restored.request)
        assertArrayEquals(frozen, CanonicalJson.encode(checkNotNull(harness.transport.remote).getJSONObject("input")))
        val progress = harness.workflow().advance(approved.task.id)
        assertEquals(bridgeTaskDiagnostics(BridgeSongStore(harness.outbox).records().single()), BridgeSongWorkProgress.PENDING, progress)
        assertEquals(1, harness.transport.submitCount)
        assertArrayEquals(frozen, CanonicalJson.encode(BridgeSongStore(harness.outbox).records().single().request))
    }

    @Test fun embeddedSecondLinkCannotBeSubmittedAsOneSong() {
        assertThrows(IllegalArgumentException::class.java) {
            bridgeVideoId("https://youtube.com/watch?v=$videoId&next=https://youtu.be/lmnopqrstuv")
        }
    }

    @Test fun nativeDefaultEchoOmissionReconcilesTheOriginalAcceptedSubmission() = runBlocking {
        val harness = Harness("native-default-wire-echo")
        val selection = BridgeSongSubmission(originalText, "fixture-model", "high", "")
        val approved = approveBridgeSong(newBridgeSong(root, originalText, guide, 1L), selection, guide, 2L, connection)
        BridgeSongStore(harness.outbox).save(approved)
        harness.transport.loseSubmitAcknowledgement = true
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(approved.task.id))
        val saved = BridgeSongStore(harness.outbox).records().single()
        val frozen = CanonicalJson.encode(saved.request)
        // GET inputs uses the Bridge's appInput JSON: empty serviceTier is omitted.
        val remoteInput = checkNotNull(harness.transport.remote).getJSONObject("input")
        remoteInput.remove("serviceTier")
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(approved.task.id))
        val reconciled = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSubmission.ACKNOWLEDGED, reconciled.submission)
        assertEquals(1, harness.transport.submitCount)
        assertEquals(1, harness.transport.guideSendCount)
        assertEquals(1, harness.transport.contextSendCount)
        assertArrayEquals(frozen, CanonicalJson.encode(reconciled.request))
        assertFalse(remoteInput.has("serviceTier"))
    }

    @Test fun emptyTierWireOmissionPreservesEveryOtherApprovedFieldAndLocalImmutability() {
        val selection = BridgeSongSubmission(originalText, "fixture-model", "high", "")
        val expected = approveBridgeSong(newBridgeSong(root, originalText, guide, 1L), selection, guide, 2L, connection).request
        val actual = JSONObject(expected.toString()).apply { remove("serviceTier") }
        requireSameBridgeInput(actual, expected)
        assertFalse(actual.has("serviceTier"))
        assertThrows(BridgeLocalFailure::class.java) { requireSameBridgeRequest(actual, expected) }
        for ((key, value) in listOf("text" to "https://youtu.be/lmnopqrstuv", "effort" to "low",
                "serviceTier" to "priority", "serviceTier" to JSONObject.NULL,
                "nativeDefaultServiceTier" to false, "unexpected" to true)) {
            val changed = JSONObject(actual.toString()).put(key, value)
            assertThrows("Accepted changed $key", BridgeLocalFailure::class.java) { requireSameBridgeInput(changed, expected) }
        }
        val missingDefault = JSONObject(actual.toString()).apply { remove("nativeDefaultServiceTier") }
        assertThrows(BridgeLocalFailure::class.java) { requireSameBridgeInput(missingDefault, expected) }
        val explicitTier = JSONObject(expected.toString()).put("serviceTier", "priority")
        assertThrows(BridgeLocalFailure::class.java) { requireSameBridgeInput(actual, explicitTier) }
    }

    @Test fun nativeDefaultWireEchoAllowsDeliveredCleanupAndFailedTaskRetirement() = runBlocking {
        for (completed in listOf(false, true)) {
            val harness = Harness("native-default-retirement-$completed")
            val selection = BridgeSongSubmission(originalText, "fixture-model", "high", "")
            val approved = approveBridgeSong(newBridgeSong(root, originalText, guide, 1L), selection, guide, 2L, connection)
            BridgeSongStore(harness.outbox).save(approved)
            harness.transport.loseSubmitAcknowledgement = true
            assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(approved.task.id))
            val accepted = BridgeSongStore(harness.outbox).records().single()
            val frozen = CanonicalJson.encode(accepted.request)
            val echo = JSONObject(accepted.request.toString()).apply { remove("serviceTier") }
            if (completed) harness.complete(accepted).put("input", echo)
            else checkNotNull(harness.transport.remote).put("state", "failed").put("input", echo)
            assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(approved.task.id))
            val terminal = BridgeSongStore(harness.outbox).records().single()
            assertEquals(if (completed) BridgeSongPhase.IMPORTED else BridgeSongPhase.FAILED, terminal.task.phase)
            assertArrayEquals(frozen, CanonicalJson.encode(terminal.request))
            assertTrue(harness.workflow().retire(approved.task.id))
            assertEquals(1, harness.transport.submitCount)
            assertEquals(1, harness.transport.deleteCount)
            assertEquals(1, harness.transport.discardCount)
            assertFalse(BridgeSongStore(harness.outbox).file(approved.task.id, "md").exists())
            assertFalse(BridgeSongStore(harness.outbox).file(approved.task.id, "input").exists())
            assertFalse(BridgeSongStore(harness.outbox).file(approved.task.id, "zip").exists())
            val remaining = BridgeSongStore(harness.outbox).records()
            assertTrue(remaining.isEmpty())
            assertTrue(harness.workflow().retire(approved.task.id))
            assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
        }
    }

    @Test fun failedUnknownSubmissionCancelsOnlyItsOriginalRemoteJob() = runBlocking {
        val harness = Harness("failed-unknown-cancel")
        val approved = harness.record()
        harness.transport.loseSubmitAcknowledgement = true
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(approved.task.id))
        val store = BridgeSongStore(harness.outbox)
        val unconfirmed = store.records().single()
        store.save(unconfirmed.copy(task = unconfirmed.task.copy(phase = BridgeSongPhase.FAILED, retryable = false)))
        harness.workflow().requestCancellation(approved.task.id)
        val cancelling = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.CANCELLING, cancelling.task.phase)
        assertTrue(cancelling.task.cancelRequested)
        checkNotNull(harness.transport.remote).put("state", "cancelled").put("cancelRequested", 1L).put("cancelRetired", 2L)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(approved.task.id))
        val retired = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.CANCELLED, retired.task.phase)
        assertEquals(1, harness.transport.submitCount)
        assertEquals(1, harness.transport.cancelCount)
        assertArrayEquals(CanonicalJson.encode(unconfirmed.request), CanonicalJson.encode(retired.request))
    }

    @Test fun importedHistoryDoesNotDeduplicateAnAbsentSongButLiveRequestsDo() {
        val completed = authorizedSong(root, originalText, guide, 1L).let {
            it.copy(task = it.task.copy(phase = BridgeSongPhase.IMPORTED), imported = true, retired = true, remoteRetired = true)
        }
        assertEquals("Completed history is not a current installation", null,
            retainedBridgeRequest(listOf(completed), root, videoId))
        val pending = authorizedSong(root, originalText, guide, 2L)
        assertEquals(pending, retainedBridgeRequest(listOf(completed, pending), root, videoId))
        assertEquals(null, retainedBridgeRequest(listOf(pending), root.copy(treeUri = "content://other"), videoId))
    }

    @Test fun unresolvedSubmissionOwnershipSurvivesTerminalPresentation() {
        val accepted = authorizedSong(root, originalText, guide, 2L).let {
            it.copy(inputsVerified = true, submission = BridgeSubmission.ACKNOWLEDGED,
                task = it.task.copy(phase = BridgeSongPhase.FAILED))
        }
        for (phase in listOf(BridgeSongPhase.FAILED, BridgeSongPhase.RETIRING)) {
            val retained = accepted.copy(task = accepted.task.copy(phase = phase))
            assertEquals(retained, retainedBridgeRequest(listOf(retained), root, videoId))
            assertEquals(retained, retainedBridgeRequest(listOf(retained), root.copy(epoch = root.epoch + 1), videoId))
        }
        for (settled in listOf(accepted.copy(remoteTerminalObserved = true),
            accepted.copy(remoteRetired = true),
            accepted.copy(task = accepted.task.copy(phase = BridgeSongPhase.EXPIRED)),
            accepted.copy(submission = BridgeSubmission.UNSENT))) {
            assertEquals(null, retainedBridgeRequest(listOf(settled), root, videoId))
        }
        val retiring = accepted.copy(remoteTerminalObserved = true,
            task = accepted.task.copy(phase = BridgeSongPhase.RETIRING))
        assertEquals(retiring, retainedBridgeRequest(listOf(retiring), root, videoId))
        assertEquals(null, retainedBridgeRequest(listOf(retiring.copy(retired = true, remoteRetired = true)), root, videoId))
    }

    @Test
    fun typedContextAndGuideAreBothFrozenBeforeTheSameRemoteJobIsSubmitted() = runBlocking {
        val harness = Harness("typed-inputs")
        val record = harness.record()
        harness.transport.loseSubmitAcknowledgement = true
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        val durable = BridgeSongStore(harness.outbox).records().single()
        val files = durable.request.getJSONArray("files")
        assertEquals(durable.attempts.lastOrNull()?.cause, 2, files.length())
        assertEquals(0, record.request.getJSONArray("files").length())
        assertEquals(record.request.getString("instructions"), durable.request.getString("instructions"))
        assertEquals(record.request.getString("title"), durable.request.getString("title"))
        assertEquals(listOf("SEND_TO_AGENTS.md", "stemwise-song.json"),
            (0 until files.length()).map { files.getJSONObject(it).getString("name") })
        val context = JSONObject(String(harness.transport.contextBytes!!, Charsets.UTF_8))
        assertEquals(record.task.id, context.getString("requestId"))
        assertEquals(videoId, context.getString("videoId"))
        assertEquals(originalText.trim(), context.getString("sourceUrl"))
        assertEquals(descriptor("a".repeat(32), "SEND_TO_AGENTS.md", guide.toByteArray(Charsets.UTF_8)).sha256,
            context.getString("guideSha256"))
        assertFalse(durable.request.getString("instructions").contains("C:/Users/"))
        val frozen = CanonicalJson.encode(durable.request)
        val resumed = harness.workflow().advance(record.task.id)
        assertEquals(bridgeTaskDiagnostics(BridgeSongStore(harness.outbox).records().single()),
            BridgeSongWorkProgress.PENDING, resumed)
        assertArrayEquals(frozen, CanonicalJson.encode(BridgeSongStore(harness.outbox).records().single().request))
        assertEquals(1, harness.transport.submitCount)
        assertEquals(1, harness.transport.contextSendCount)
        assertEquals("fixture-model", durable.request.getString("model"))
        assertEquals("high", durable.request.getString("effort"))
        assertEquals("default", durable.request.getString("serviceTier"))
    }
    private lateinit var workRoot: File
    private lateinit var directory: File
    private val connection = BridgeConnectionProfile("bridge-a.tailnet.ts.net", "bridge-a", "pc-a")
    private val root = RootAuthority("library", "content://fixture/tree/library", 7)
    private val videoId = "dQw4w9WgXcQ"
    private val originalText = " https://music.youtube.com/watch?v=$videoId&list=fixture "
    private val guide = "Frozen engine instructions\nKeep the campaign paused.\n"

    private fun authorizedSong(root: RootAuthority, text: String, guide: String, now: Long): BridgeSongRecord =
        approveBridgeSong(newBridgeSong(root, text, guide, now),
            BridgeSongSubmission(text, "fixture-model", "high", "default"), guide, now, connection)

    @Before
    fun createWorkDirectory() {
        val project = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "Test suite").isDirectory }
        workRoot = File(project, "Test suite/work").canonicalFile
        check(workRoot.isDirectory || workRoot.mkdirs())
        directory = File(workRoot, "bridge-workflow-${UUID.randomUUID()}")
        check(directory.mkdir())
    }

    @After
    fun removeOnlyOwnedWorkDirectory() {
        if (!::directory.isInitialized) return
        // Keep bounded state in memory before cleanup; emit only when the test fails.
        diagnosticsAtExit = directory.walkTopDown().filter { it.isFile && it.extension == "json" }
            .take(16).joinToString("\n") { file ->
                val diagnostic = runCatching {
                    bridgeTaskDiagnostics(checkNotNull(BridgeSongStore(checkNotNull(file.parentFile)).record(file.name.removeSuffix(".json"))))
                }.getOrElse { "Record unavailable: ${it.toString().take(1024)}" }
                "${file.relativeTo(directory)}: $diagnostic"
            }.take(32768)
        val target = directory.toPath().toRealPath()
        check(target.parent == workRoot.toPath().toRealPath())
        check(directory.name.startsWith("bridge-workflow-"))
        Files.walk(target).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test fun damagedOutboxRecordDoesNotHideOrPreventRetiringHealthyWork() {
        val store = BridgeSongStore(File(directory, "mixed"))
        val healthy = authorizedSong(root, originalText, guide, 1L)
        val delivered = healthy.copy(task = healthy.task.copy(phase = BridgeSongPhase.IMPORTED), imported = true, retired = true, remoteRetired = true)
        store.save(delivered)
        val badId = "d".repeat(32)
        val bad = File(store.directory, "$badId.json").apply { writeText("{unfinished", Charsets.UTF_8) }
        val transfer = store.file(badId, "zip").apply { writeText("retained transfer", Charsets.UTF_8) }
        val bytes = bad.readBytes()
        val restarted = BridgeSongStore(store.directory)
        assertEquals(listOf(delivered.task.id), restarted.records().map { it.task.id })
        assertArrayEquals(bytes, bad.readBytes())
        val issue = restarted.damagedTasks().single()
        assertTrue(issue.canForget)
        assertFalse(issue.canRetry)
        restarted.removeRetired(restarted.records().single())
        assertTrue(restarted.records().isEmpty())
        assertArrayEquals(bytes, bad.readBytes())
        assertThrows(IllegalStateException::class.java) { restarted.discardDamaged("damaged:../outside.json") }
        restarted.discardDamaged(issue.id)
        assertTrue(restarted.damagedTasks().isEmpty())
        assertFalse(transfer.exists())
        BridgeSongStore(store.directory).save(healthy)
        assertThrows(IllegalStateException::class.java) { store.discardDamaged("damaged:${healthy.task.id}.json") }
        assertEquals(healthy.task.id, store.records().single().task.id)
    }

    @Test fun damagedTransferCleanupFailureKeepsTheRecordDiscoverableUntilRetry() {
        val store = BridgeSongStore(File(directory, "cleanup-retry").apply { mkdirs() })
        val id = "e".repeat(32)
        val record = File(store.directory, "$id.json").apply { writeText("{damaged") }
        val unsafeTransfer = File(store.directory, "$id.zip").apply { mkdirs() }
        val task = store.damagedTasks().single()
        assertThrows(IllegalStateException::class.java) { store.discardDamaged(task.id) }
        assertTrue(record.exists())
        assertEquals(task.id, BridgeSongStore(store.directory).damagedTasks().single().id)
        assertTrue(unsafeTransfer.delete())
        val transfer = File(store.directory, "$id.zip").apply { writeText("local copy") }
        store.discardDamaged(task.id)
        assertFalse(transfer.exists())
        assertFalse(record.exists())
    }

    @Test fun unexpectedOutboxJsonMembersStayIsolatedWithoutUnsafeDiscard() {
        val store = BridgeSongStore(File(directory, "unexpected"))
        val healthy = authorizedSong(root, originalText, guide, 1L)
        store.save(healthy)
        val unknown = File(store.directory, "owner-notes.json").apply { writeText("retain unknown bytes", Charsets.UTF_8) }
        val incomplete = File(store.directory, "${"c".repeat(32)}.new").apply { writeText("unfinished atomic write", Charsets.UTF_8) }
        val restarted = BridgeSongStore(store.directory)
        assertEquals(healthy.task.id, restarted.records().single().task.id)
        assertTrue(restarted.damagedTasks().isEmpty())
        assertThrows(IllegalStateException::class.java) { store.discardDamaged("damaged:owner-notes.json") }
        assertEquals("retain unknown bytes", unknown.readText(Charsets.UTF_8))
        assertEquals("unfinished atomic write", incomplete.readText(Charsets.UTF_8))
    }

    @Test fun unchangedPollsKeepFailureHistoryAndDoNotRewriteOrRereadRetainedRecords() = runBlocking {
        val harness = Harness("poll-index")
        val record = harness.record()
        harness.transport.loseSubmitAcknowledgement = true
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        val store = BridgeSongStore(harness.outbox)
        repeat(127) { index -> store.save(authorizedSong(root, originalText, guide, index.toLong())) }
        val current = store.records().single { it.task.id == record.task.id }
        assertTrue(current.attempts.any { it.outcome == "failed" })
        val file = File(harness.outbox, "${record.task.id}.json")
        val bytes = file.readBytes()
        check(file.setLastModified(1_000L))
        val timestamp = file.lastModified()
        // A cached record remains usable without opening the retained payloads again.
        val withheld = File(harness.outbox, "${store.records().first { it.task.id != record.task.id }.task.id}.json")
        val hidden = File(harness.outbox, withheld.name + ".withheld")
        check(withheld.renameTo(hidden))
        var publications = 0
        val workflow = BridgeSongWorkflow(store, { harness.transport }, {}, { _, _, _ -> }, { _, _ -> }, { publications++ }, {})
        try {
            repeat(12) { assertEquals(BridgeSongWorkProgress.PENDING, workflow.advance(record.task.id)) }
            assertEquals(128, store.records().size)
            assertEquals(current.attempts, store.records().single { it.task.id == record.task.id }.attempts)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(timestamp, file.lastModified())
            assertEquals(0, publications)
        } finally { check(hidden.renameTo(withheld)) }
    }

    @Test fun interruptedGuideTransferResumesTheSameDescriptorBeforeSendAndAfterLostReceipt() = runBlocking {
        for (beforeSend in listOf(true, false)) {
            val harness = Harness(if (beforeSend) "before-guide-send" else "after-guide-send")
            val record = harness.record()
            harness.transport.interruptBeforeGuideSend = beforeSend
            harness.transport.loseGuideAcknowledgement = !beforeSend
            if (beforeSend) assertThrows(SimulatedProcessInterruption::class.java) {
                runBlocking {
                    val pending = harness.workflow().advance(record.task.id)
                    error("Expected transfer interruption; pending=$pending: " + bridgeTaskDiagnostics(BridgeSongStore(harness.outbox).records().single()))
                }
            } else assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
            val interrupted = BridgeSongStore(harness.outbox).records().single()
            val immutable = CanonicalJson.encode(interrupted.request)
            assertFalse(interrupted.inputsVerified)
            assertEquals(0, harness.transport.submitCount)
            assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
            val resumed = BridgeSongStore(harness.outbox).records().single()
            assertTrue(resumed.inputsVerified)
            assertEquals(record.task.id, resumed.task.id)
            assertArrayEquals(immutable, CanonicalJson.encode(resumed.request))
            assertEquals(guide, resumed.guide)
            assertEquals(1, harness.transport.prepareCount)
            assertEquals(2, harness.transport.guideRequests.size)
            assertEquals(harness.transport.guideRequests[0], harness.transport.guideRequests[1])
            assertEquals(1, harness.transport.guideSendCount)
            assertEquals(1, harness.transport.submitCount)
            assertEquals(originalText, resumed.request.getString("text"))
            assertEquals(123_456L, resumed.request.getLong("createdAt"))
            assertTrue(harness.events.none { it == "install" || it == "ack" })
        }
    }

    @Test fun expiredUnsentInputCacheResumesTheExactApprovedRequestFromRetainedBytes() = runBlocking {
        val harness = Harness("expired-unsent-inputs")
        val record = harness.record()
        harness.transport.interruptBeforeGuideSend = true
        assertThrows(SimulatedProcessInterruption::class.java) {
            runBlocking { harness.workflow().advance(record.task.id) }
        }
        val store = BridgeSongStore(harness.outbox)
        val interrupted = store.records().single()
        val frozenRequest = CanonicalJson.encode(interrupted.request)
        assertEquals(BridgeSubmission.UNSENT, interrupted.submission)
        assertFalse(interrupted.inputsVerified)
        assertEquals(2, interrupted.request.getJSONArray("files").length())
        harness.transport.expirePreparedInputs()
        // The immutable record, rather than an SDK cache or these temporary copies, owns the inputs.
        check(store.file(record.task.id, "md").delete())
        check(store.file(record.task.id, "input").delete())

        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        val resumed = BridgeSongStore(harness.outbox).records().single()
        assertTrue(resumed.inputsVerified)
        assertArrayEquals(frozenRequest, CanonicalJson.encode(resumed.request))
        assertEquals(1, harness.transport.prepareCount)
        assertEquals(1, harness.transport.submitCount)
        assertEquals(2, harness.transport.restoredInputs.size)
        assertEquals(guide, store.file(record.task.id, "md").readText(Charsets.UTF_8))
        assertArrayEquals(bridgeSongContext(interrupted), store.file(record.task.id, "input").readBytes())
    }

    @Test fun verifiedUnsentInputsAreRevalidatedAfterRemoteRetention() = runBlocking {
        for (expireRemote in listOf(false, true)) {
            val harness = Harness("verified-unsent-retention-$expireRemote")
            val record = harness.record()
            harness.transport.afterLookup = { throw SimulatedProcessInterruption() }
            assertThrows(SimulatedProcessInterruption::class.java) {
                runBlocking { harness.workflow().advance(record.task.id) }
        }
        val store = BridgeSongStore(harness.outbox)
        val interrupted = store.records().single()
        val frozenRequest = CanonicalJson.encode(interrupted.request)
        assertTrue(interrupted.inputsVerified)
        assertEquals(BridgeSubmission.UNSENT, interrupted.submission)
        harness.transport.afterLookup = null
        if (expireRemote) harness.transport.storedInputs.clear()
        harness.transport.expirePreparedInputs()
        check(store.file(record.task.id, "md").delete())
        check(store.file(record.task.id, "input").delete())
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        val resumed = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSubmission.ACKNOWLEDGED, resumed.submission)
        assertArrayEquals(frozenRequest, CanonicalJson.encode(resumed.request))
        assertEquals(1, harness.transport.submitCount)
        assertEquals(1, harness.transport.prepareCount)
        assertEquals(2, harness.transport.storedInputs.size)
        assertEquals(0, harness.transport.retirementCount)
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        assertEquals(1, harness.transport.submitCount)
        assertEquals(0, harness.transport.retirementCount)
        }
    }

    @Test fun verifiedUnsentInputsStayUnsentWhenExactInputRecoveryFails() = runBlocking {
        val harness = Harness("verified-unsent-inputs")
        val record = harness.record()
        harness.transport.afterLookup = { throw SimulatedProcessInterruption() }
        assertThrows(SimulatedProcessInterruption::class.java) {
            runBlocking { harness.workflow().advance(record.task.id) }
        }
        val store = BridgeSongStore(harness.outbox)
        val interrupted = store.records().single()
        val frozenRequest = CanonicalJson.encode(interrupted.request)
        assertTrue(interrupted.inputsVerified)
        assertEquals(BridgeSubmission.UNSENT, interrupted.submission)
        harness.transport.afterLookup = null
        val temporaryCopy = store.file(record.task.id, "md")
        check(temporaryCopy.delete() && temporaryCopy.mkdir())
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        assertArrayEquals(frozenRequest, CanonicalJson.encode(BridgeSongStore(harness.outbox).records().single().request))
        assertEquals(BridgeSubmission.UNSENT, BridgeSongStore(harness.outbox).records().single().submission)
        assertEquals(0, harness.transport.submitCount)
        assertEquals(1, harness.transport.prepareCount)
        assertTrue(harness.transport.restoredInputs.isEmpty())
    }

    @Test fun expiredInputCacheNeverRepreparesOrResubmitsAcceptedOrUncertainWork() = runBlocking {
        for (lostAcknowledgement in listOf(false, true)) {
            val harness = Harness("expired-accepted-$lostAcknowledgement")
            val record = harness.record()
            harness.transport.loseSubmitAcknowledgement = lostAcknowledgement
            assertEquals(if (lostAcknowledgement) BridgeSongWorkProgress.RETRY else BridgeSongWorkProgress.PENDING,
                harness.workflow().advance(record.task.id))
            val frozenRequest = CanonicalJson.encode(BridgeSongStore(harness.outbox).records().single().request)
            harness.transport.expirePreparedInputs()
            assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
            assertArrayEquals(frozenRequest, CanonicalJson.encode(BridgeSongStore(harness.outbox).records().single().request))
            assertEquals(1, harness.transport.submitCount)
            assertEquals(1, harness.transport.prepareCount)
            assertTrue(harness.transport.restoredInputs.isEmpty())
        }
    }

    @Test fun unsentCacheRecoveryRejectsADescriptorThatDoesNotMatchItsFrozenInput() = runBlocking {
        val harness = Harness("changed-unsent-input")
        val record = harness.record()
        harness.transport.interruptBeforeGuideSend = true
        assertThrows(SimulatedProcessInterruption::class.java) {
            runBlocking { harness.workflow().advance(record.task.id) }
        }
        val store = BridgeSongStore(harness.outbox)
        val interrupted = store.records().single()
        val request = JSONObject(interrupted.request.toString())
        request.getJSONArray("files").getJSONObject(0).put("sha256", "f".repeat(64))
        store.save(interrupted.copy(request = request))
        harness.transport.expirePreparedInputs()
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        val rejected = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.FAILED, rejected.task.phase)
        assertEquals(BridgeSubmission.UNSENT, rejected.submission)
        assertEquals(0, harness.transport.submitCount)
        assertTrue(harness.transport.restoredInputs.isEmpty())
        assertEquals(1, harness.transport.prepareCount)
    }

    @Test
    fun lostSubmissionAcknowledgementResumesThePersistedRequestWithoutAnotherSubmit() = runBlocking {
        val harness = Harness()
        harness.transport.loseSubmitAcknowledgement = true
        val record = harness.record()
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        val durable = BridgeSongStore(harness.outbox).records().single()
        assertEquals("RETRYING", durable.task.state)
        assertTrue(durable.task.canRetry)
        assertEquals(1, harness.transport.submitCount)
        assertEquals(guide, durable.guide)
        assertEquals(originalText, durable.request.getString("text"))
        assertEquals(123_456L, durable.request.getLong("createdAt"))
        val frozenRequest = CanonicalJson.encode(durable.request)

        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        val resumed = BridgeSongStore(harness.outbox).records().single()
        assertArrayEquals(frozenRequest, CanonicalJson.encode(resumed.request))
        assertEquals(durable.task.id, resumed.task.id)
        assertEquals(1, harness.transport.submitCount)
        assertEquals(1, harness.transport.prepareCount)
        assertEquals(1, harness.transport.guideRequests.size)
        assertEquals(1, harness.transport.guideSendCount)
        assertTrue(harness.events.none { it == "install" || it == "ack" })
    }

    @Test
    fun nativeCompletionWithoutAValidReportAndOutputNeverImportsOrAcknowledges() = runBlocking {
        for (kind in listOf("missing-report", "missing-output", "wrong-report-code", "zero-revision")) {
            val harness = Harness(kind)
            val record = harness.record()
            val remote = harness.complete(record)
            when (kind) {
                "missing-report" -> remote.remove("report")
                "missing-output" -> remote.put("outputs", JSONArray())
                "wrong-report-code" -> remote.getJSONObject("report").put("code", "some_other_result")
                "zero-revision" -> remote.getJSONObject("report").put("revision", 0)
            }
            harness.workflow().advance(record.task.id)
            assertTrue(kind, harness.events.none { it == "install" || it == "ack" })
            assertEquals(kind, "prior library song", harness.published.readText(Charsets.UTF_8))
            assertFalse(kind, BridgeSongStore(harness.outbox).records().single().imported)
            assertFalse(kind, BridgeSongStore(harness.outbox).records().single().task.state == "IMPORTED")
            if (kind == "missing-report") {
                val task = BridgeSongStore(harness.outbox).records().single().task
                assertEquals("WAITING", task.state)
                assertTrue(task.canRetry)
            }
        }
    }

    @Test
    fun completedNativeTaskWithFailedDeveloperReportIsTerminalWithoutAcknowledgement() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.complete(record).getJSONObject("report")
            .put("status", "failed").put("code", "stemwise_validation_failed")
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        val saved = BridgeSongStore(harness.outbox).records().single()
        assertEquals("FAILED", saved.task.state)
        assertTrue(saved.task.terminal)
        assertFalse(saved.task.canRetry)
        assertFalse(saved.imported)
        assertTrue(harness.events.isEmpty())
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
    }

    @Test
    fun deliveredRecordRemovalRequiresRetirementAndTheExactCurrentPersistedRecord() {
        val store = BridgeSongStore(File(directory, "retention"))
        val original = authorizedSong(root, originalText, guide, 123_456L)
        val imported = original.copy(imported = true,
            task = original.task.copy(phase = BridgeSongPhase.IMPORTED, stage = "Song added"))
        store.save(imported)
        val beforeRefusal = File(store.directory, "${imported.task.id}.json").readBytes()
        assertThrows(IllegalStateException::class.java) { store.removeRetired(imported) }
        assertArrayEquals(beforeRefusal, File(store.directory, "${imported.task.id}.json").readBytes())

        val retired = imported.copy(retired = true, remoteRetired = true)
        store.save(retired)
        val roundTrip = BridgeSongStore(store.directory).records().single()
        assertTrue(roundTrip.retired)
        assertArrayEquals(CanonicalJson.encode(retired.json()), CanonicalJson.encode(roundTrip.json()))
        val changed = roundTrip.copy(task = roundTrip.task.copy(stage = "Current saved details"))
        store.save(changed)
        assertThrows(IllegalStateException::class.java) { store.removeRetired(roundTrip) }
        assertEquals("Current saved details", store.records().single().task.stage)

        val other = authorizedSong(root, originalText, guide, 123_457L)
        store.save(other)
        val current = BridgeSongStore(store.directory).records().single { it.task.id == changed.task.id }
        store.removeRetired(current)
        assertEquals(listOf(other.task.id), BridgeSongStore(store.directory).records().map { it.task.id })
    }

    @Test
    fun transferredBytesMustMatchTheDescriptorBeforeTheInstallerRuns() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.complete(record)
        harness.transport.receiveBytes = "wrong ZIP bytes".toByteArray(Charsets.UTF_8)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertTrue(harness.events.none { it == "install" || it == "ack" })
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
        val retained = BridgeSongStore(harness.outbox).records().single()
        assertEquals(record.task.id, retained.task.id)
        assertTrue(retained.task.canRetry)
        assertFalse(retained.imported)
        assertTrue(BridgeSongStore(harness.outbox).file(record.task.id, "zip").isFile)
    }

    @Test
    fun rejectedZipPreservesThePriorPublicationAndDurableRequestWithoutAcknowledgement() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.transport.receiveBytes = "not a ZIP".toByteArray(Charsets.UTF_8)
        harness.complete(record, harness.transport.receiveBytes)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertEquals(listOf("receive", "install"), harness.events)
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
        val retained = BridgeSongStore(harness.outbox).records().single()
        assertFalse(retained.imported)
        assertFalse(retained.task.canRetry)
        assertEquals("FAILED", retained.task.state)
        assertEquals(record.request.getString("requestId"), retained.request.getString("requestId"))
    }

    @Test
    fun transientReceiveAndImportFailuresResumeTheSameCompletedOutputAfterRestart() = runBlocking {
        for (failure in listOf(IOException("Wi-Fi disconnected"),
            BridgeApiFailure(503, "temporarily_unavailable", "PC unavailable", true),
            EnrichmentImportException("STORAGE_RESERVE_LOW"))) {
            val harness = Harness(failure.javaClass.simpleName + failure.message.hashCode())
            val record = harness.record()
            harness.complete(record)
            if (failure is EnrichmentImportException) harness.installFailure = failure
            else harness.transport.receiveFailure = failure
            assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
            val interrupted = BridgeSongStore(harness.outbox).records().single()
            assertEquals("RETRYING", interrupted.task.state)
            assertFalse(interrupted.task.terminal)
            assertEquals(record.task.id, interrupted.task.id)
            assertFalse(interrupted.imported)
            harness.installFailure = null
            harness.transport.receiveFailure = null
            assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
            assertEquals("IMPORTED", BridgeSongStore(harness.outbox).records().single().task.state)
            assertEquals(0, harness.transport.submitCount)
            assertEquals(1, harness.events.count { it == "ack" })
            assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun ancientCompletedJobKeepsImportTrackingAndRequiredTransfersUntilExactDeliveryAcknowledgement() = runBlocking {
        val harness = Harness("ancient-delivery-gate")
        val record = harness.record(createdAt = 1L)
        harness.complete(record)
        harness.transport.ackFailure = IOException("Exact delivery acknowledgement unavailable")
        var archiveHandoffs = 0
        harness.beforeArchiveCleanup = { archiveHandoffs++ }
        var frozenRequest: ByteArray? = null
        var completedOutput: BridgeFile? = null
        repeat(3) {
            assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
            val restarted = BridgeSongStore(harness.outbox)
            val saved = restarted.records().single()
            assertEquals(1L, saved.task.createdAtEpochMs)
            assertEquals(record.task.id, saved.task.id)
            assertEquals(BridgeSongPhase.ACKNOWLEDGING, saved.task.phase)
            assertTrue("Delivery failure lost the durable local import", saved.imported)
            assertTrue(saved.outputVerified)
            assertFalse(saved.retired)
            assertFalse(saved.remoteRetired)
            val request = CanonicalJson.encode(saved.request)
            frozenRequest?.let { assertArrayEquals(it, request) }
            frozenRequest = request
            completedOutput?.let { assertEquals(it, saved.output) }
            completedOutput = checkNotNull(saved.output)
            assertTrue(checkNotNull(saved.output).matches(restarted.file(saved.task.id, "zip")))
            assertArrayEquals(harness.transport.receiveBytes, restarted.file(saved.task.id, "zip").readBytes())
            assertTrue(restarted.file(saved.task.id, "md").isFile)
            assertTrue(restarted.file(saved.task.id, "input").isFile)
            assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
            assertEquals(0, archiveHandoffs)
            assertEquals(0, harness.transport.deleteCount)
            assertEquals(0, harness.transport.discardCount)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { harness.workflow().retire(saved.task.id) }
            }
        }
        assertEquals(1, harness.events.count { it == "install" })
        assertEquals(1, harness.events.count { it == "receive" })
        assertEquals(0, harness.transport.submitCount)
        assertEquals(0, harness.transport.retirementCount)

        harness.transport.ackFailure = null
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        val delivered = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.IMPORTED, delivered.task.phase)
        assertEquals(completedOutput, delivered.output)
        assertEquals(1, archiveHandoffs)
        assertEquals(4, harness.events.count { it == "ack" })
        assertEquals(1, harness.events.count { it == "install" })
        assertEquals(0, harness.transport.deleteCount)
        assertEquals(0, harness.transport.discardCount)
        assertFalse(BridgeSongStore(harness.outbox).file(record.task.id, "zip").exists())
        // The repository's normal post-delivery retirement may now delete remote tracking.
        assertTrue(harness.workflow().retire(record.task.id))
        assertEquals(1, harness.transport.deleteCount)
        assertEquals(1, harness.transport.discardCount)
        assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
    }

    @Test
    fun lostDeliveryAcknowledgementRetriesWithoutAnotherSubmissionOrImport() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.complete(record)
        harness.transport.ackFailure = IOException("Acknowledgement lost")
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        val interrupted = BridgeSongStore(harness.outbox).records().single()
        assertTrue("Before acknowledgement: ${harness.events}; ${interrupted.attempts}", interrupted.imported)
        assertEquals("ACKNOWLEDGING", interrupted.task.state)
        assertTrue(BridgeSongStore(harness.outbox).file(record.task.id, "zip").isFile)
        harness.transport.ackFailure = null
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertEquals(1, harness.events.count { it == "install" })
        assertEquals(2, harness.events.count { it == "verify" })
        assertEquals(2, harness.events.count { it == "ack" })
        assertEquals(0, harness.transport.submitCount)
        assertEquals("IMPORTED", BridgeSongStore(harness.outbox).records().single().task.state)
        assertTrue(interrupted.attempts.single().cause.orEmpty().contains("Acknowledgement lost"))
        assertEquals(harness.transport.receiveBytes.size.toLong(), interrupted.attempts.single().verifiedTransferBytes)
    }

    @Test
    fun localDiagnosticsRetainBoundedTypedAttemptsAcrossOutboxReloads() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.complete(record)
        repeat(10) { index ->
            harness.transport.receiveFailure = IOException("receive-$index " + "x".repeat(8_000))
            assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        }
        val restored = BridgeSongStore(harness.outbox).records().single()
        assertEquals((3..10).toList(), restored.attempts.map { it.number })
        assertTrue(restored.attempts.all { it.outcome == "failed" && it.disposition == BridgeFailureDisposition.RETRY })
        assertTrue(restored.attempts.all { it.cause.orEmpty().length <= 4096 && it.elapsedMs >= 0 })
        assertTrue(restored.attempts.last().cause.orEmpty().contains("receive-9"))
        assertEquals(record.task.videoId, restored.task.videoId)
        assertEquals(record.task.id, restored.request.getString("requestId"))
        assertTrue(restored.output != null)
        assertEquals(0, harness.transport.submitCount)
    }

    @Test
    fun verifiedTransferIsInstalledAndReadBackBeforeDeliveryAcknowledgement() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.complete(record)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertEquals(listOf("receive", "install", "verify", "ack"), harness.events)
        val saved = BridgeSongStore(harness.outbox).records().single()
        assertEquals("IMPORTED", saved.task.state)
        assertTrue(saved.imported)
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertFalse(BridgeSongStore(harness.outbox).file(record.task.id, "zip").exists())
        assertFalse(BridgeSongStore(harness.outbox).file(record.task.id, "md").exists())
    }

    @Test
    fun restartImmediatelyAfterImportedCommitCleansOnlyOwnedTransferCopiesIdempotently() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.complete(record)
        harness.afterImportedSave = { throw SimulatedProcessInterruption() }
        assertThrows(SimulatedProcessInterruption::class.java) {
            val progress = runBlocking { harness.workflow().advance(record.task.id) }
            error("Expected interruption after imported commit; progress=$progress; events=${harness.events}; " +
                bridgeTaskDiagnostics(BridgeSongStore(harness.outbox).records().single()))
        }
        val restored = BridgeSongStore(harness.outbox)
        val imported = restored.records().single()
        val committed = CanonicalJson.encode(imported.json())
        assertEquals("IMPORTED", imported.task.state)
        assertTrue(restored.file(record.task.id, "zip").isFile)
        assertTrue(restored.file(record.task.id, "md").isFile)
        val unrelated = File(harness.outbox, "${"c".repeat(32)}.zip")
            .also { it.writeText("another task transfer", Charsets.UTF_8) }

        restored.removeTransferCopies(imported)
        BridgeSongStore(harness.outbox).removeTransferCopies(imported)

        assertFalse(restored.file(record.task.id, "zip").exists())
        assertFalse(restored.file(record.task.id, "md").exists())
        assertEquals("another task transfer", unrelated.readText(Charsets.UTF_8))
        assertArrayEquals(committed, CanonicalJson.encode(restored.records().single().json()))
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertEquals(listOf("receive", "install", "verify", "ack"), harness.events)
    }

    @Test
    fun cleanupFailureKeepsImportedCommitAndCanResumeAfterTheFilesystemFaultIsRemoved() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.complete(record)
        val store = BridgeSongStore(harness.outbox)
        val guideCopy = store.file(record.task.id, "md")
        val blocker = File(guideCopy, "fault-blocker")
        harness.afterImportedSave = {
            check(guideCopy.delete())
            check(guideCopy.mkdir())
            blocker.writeText("prevent nonrecursive removal", Charsets.UTF_8)
        }
        val pending = harness.workflow().advance(record.task.id)
        assertEquals(bridgeTaskDiagnostics(BridgeSongStore(harness.outbox).records().single()), BridgeSongWorkProgress.COMPLETE, pending)
        val imported = BridgeSongStore(harness.outbox).records().single()
        assertEquals("IMPORTED", imported.task.state)
        assertTrue(imported.imported)
        assertTrue(imported.task.message.orEmpty().contains("cleanup"))
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertFalse(store.file(record.task.id, "zip").exists())
        assertThrows(IOException::class.java) { store.removeTransferCopies(imported) }
        assertEquals("IMPORTED", store.records().single().task.state)
        assertTrue(blocker.isFile)

        check(blocker.delete())
        check(guideCopy.delete())
        guideCopy.writeText(guide, Charsets.UTF_8)
        BridgeSongStore(harness.outbox).removeTransferCopies(imported)
        assertFalse(guideCopy.exists())
        assertEquals("IMPORTED", store.records().single().task.state)
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertEquals(listOf("receive", "install", "verify", "ack"), harness.events)
    }

    @Test
    fun transferCleanupRequiresThePersistedImportedCommitAndExactRequest() {
        val store = BridgeSongStore(File(directory, "cleanup-authority"))
        val queued = authorizedSong(root, originalText, guide, 123_456L)
        store.save(queued)
        val transfer = store.file(queued.task.id, "zip").also { it.writeText("pending transfer", Charsets.UTF_8) }
        val claimedImported = queued.copy(imported = true, task = queued.task.copy(phase = BridgeSongPhase.IMPORTED))
        assertThrows(IllegalStateException::class.java) { store.removeTransferCopies(claimedImported) }
        assertEquals("pending transfer", transfer.readText(Charsets.UTF_8))
        assertEquals("QUEUED", store.records().single().task.state)

        store.save(claimedImported)
        val changedRequest = JSONObject(claimedImported.request.toString()).put("instructions", "changed request")
        assertThrows(IllegalStateException::class.java) {
            store.removeTransferCopies(claimedImported.copy(request = changedRequest))
        }
        assertEquals("pending transfer", transfer.readText(Charsets.UTF_8))
        assertArrayEquals(CanonicalJson.encode(claimedImported.request), CanonicalJson.encode(store.records().single().request))
    }

    @Test
    fun retainedZipInstallsOfflineAndReconnectAcknowledgesWithoutReplay() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.complete(record)
        val store = BridgeSongStore(harness.outbox)
        // Reconstruct the durable receiving boundary without any remote observation on restart.
        val output = BridgeFile.parse(checkNotNull(harness.transport.remote).getJSONArray("outputs").getJSONObject(0))
        store.file(record.task.id, "zip").writeBytes(harness.transport.receiveBytes)
        store.save(store.records().single().copy(output = output, submission = BridgeSubmission.ACKNOWLEDGED))
        harness.transport.online = false
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        assertTrue(BridgeSongStore(harness.outbox).records().single().imported)
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertEquals(listOf("install", "verify"), harness.events)
        harness.transport.online = true
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertEquals(listOf("install", "verify", "verify", "ack"), harness.events)
        assertEquals(0, harness.transport.submitCount)
        assertEquals(0, harness.transport.lookupCount)
    }

    @Test
    fun restartAfterInstallBeforeAcknowledgementReadsBackWithoutInstallingAgain() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.complete(record)
        harness.interruptVerification = true
        assertThrows(SimulatedProcessInterruption::class.java) {
            runBlocking { harness.workflow().advance(record.task.id) }
        }
        assertTrue(BridgeSongStore(harness.outbox).records().single().imported)
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        assertEquals(listOf("receive", "install", "verify"), harness.events)

        harness.interruptVerification = false
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertEquals(listOf("receive", "install", "verify", "verify", "ack"), harness.events)
        assertEquals("IMPORTED", BridgeSongStore(harness.outbox).records().single().task.state)
        assertEquals(0, harness.transport.submitCount)
    }

    @Test
    fun localCancellationLeavesRunningAndReceivingWorkExplicitlyResumable() = runBlocking {
        for (receiving in listOf(false, true)) {
            val harness = Harness("library-switch-$receiving")
            val record = harness.record()
            val initialProgress = harness.workflow().advance(record.task.id)
            assertEquals(bridgeTaskDiagnostics(BridgeSongStore(harness.outbox).records().single()),
                BridgeSongWorkProgress.PENDING, initialProgress)
            if (receiving) {
                harness.complete(record)
                harness.transport.receiveFailure = kotlinx.coroutines.CancellationException("LIBRARY_CHANGED")
            } else {
                harness.transport.afterLookup = { throw kotlinx.coroutines.CancellationException("LIBRARY_CHANGED") }
            }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { harness.workflow().advance(record.task.id) }
            }
            val stopped = BridgeSongStore(harness.outbox).records().single()
            assertEquals("WAITING", stopped.task.state)
            assertTrue(stopped.task.canRetry)
            assertFalse(stopped.task.terminal)
            assertEquals(root, stopped.root)
            assertEquals(1, harness.transport.submitCount)
            assertEquals("cancelled", stopped.attempts.last().outcome)
        }
    }

    @Test
    fun returningToTheSameLibraryResumesTheExactRunningAndReceivingRequest() = runBlocking {
        for (receiving in listOf(false, true)) {
            val harness = Harness("library-return-$receiving")
            val record = harness.record()
            assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
            if (receiving) {
                harness.complete(record)
                harness.transport.receiveFailure = kotlinx.coroutines.CancellationException("LIBRARY_CHANGED")
            } else {
                harness.transport.afterLookup = { throw kotlinx.coroutines.CancellationException("LIBRARY_CHANGED") }
            }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { harness.workflow().advance(record.task.id) }
            }
            val stopped = BridgeSongStore(harness.outbox).records().single()
            val immutable = CanonicalJson.encode(stopped.request)
            harness.currentRoot = RootAuthority("other-library", "content://fixture/tree/other", root.epoch + 1)
            assertFalse(harness.workflow().resumeForLibrary(record.task.id, harness.currentRoot))
            assertEquals(root, BridgeSongStore(harness.outbox).records().single().root)
            harness.currentRoot = root.copy(epoch = root.epoch + 2)
            assertTrue(harness.workflow().resumeForLibrary(record.task.id, harness.currentRoot))
            val rebound = BridgeSongStore(harness.outbox).records().single()
            assertEquals(harness.currentRoot, rebound.root)
            assertArrayEquals(immutable, CanonicalJson.encode(rebound.request))
            assertEquals(stopped.output, rebound.output)
            harness.transport.afterLookup = null
            harness.transport.receiveFailure = null
            if (!receiving) {
                assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
                harness.complete(rebound)
            }
            val pending = harness.workflow().advance(record.task.id)
            assertEquals(BridgeSongStore(harness.outbox).records().single().attempts.lastOrNull()?.cause, BridgeSongWorkProgress.COMPLETE, pending)
            assertEquals(1, harness.transport.submitCount)
            assertEquals("IMPORTED", BridgeSongStore(harness.outbox).records().single().task.state)
        }
    }

    @Test
    fun changedLibraryRejectsBeforeAnyRemoteMutationOrImport() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.currentRoot = root.copy(epoch = root.epoch + 1)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertEquals(0, harness.transport.prepareCount)
        assertEquals(0, harness.transport.submitCount)
        assertTrue(harness.transport.guideRequests.isEmpty())
        assertTrue(harness.events.isEmpty())
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
        assertEquals(root, BridgeSongStore(harness.outbox).records().single().root)
    }

    @Test
    fun sourceParsingAcceptsExactYoutubeOriginsAndRejectsLookalikesAndAmbiguousSlots() {
        listOf(
            "https://youtu.be/$videoId",
            "https://www.youtube.com/watch?v=$videoId",
            "https://music.youtube.com/watch?v=$videoId&list=fixture",
            "https://m.youtube.com/shorts/$videoId"
        ).forEach { assertEquals(it, videoId, bridgeVideoId(it)) }
        listOf(
            "https://youtube.com.attacker.invalid/watch?v=$videoId",
            "https://attacker.invalid/watch?v=$videoId",
            "http://youtube.com/watch?v=$videoId",
            "https://owner@youtube.com/watch?v=$videoId",
            "https://youtube.com:8443/watch?v=$videoId",
            "https://youtube.com/watch?v=$videoId&v=$videoId",
            "https://youtu.be/$videoId/extra",
            "https://youtube.com/watch?v=too-short"
        ).forEach { assertThrows(it, IllegalArgumentException::class.java) { bridgeVideoId(it) } }
    }

    @Test
    fun failedTaskRetirementPreservesUnknownWorkAndResumesAfterCleanupInterruption() = runBlocking {
        val harness = Harness()
        val original = harness.record()
        val store = BridgeSongStore(harness.outbox)
        val failed = original.copy(submission = BridgeSubmission.ACKNOWLEDGED, task = original.task.copy(phase = BridgeSongPhase.FAILED))
        store.save(failed)
        val zip = store.file(failed.task.id, "zip").also { it.writeText("retained failed bytes") }
        harness.transport.remote = JSONObject().put("id", failed.task.id).put("state", "unknown")
            .put("input", failed.request)
        assertFalse(harness.workflow().retire(failed.task.id))
        assertTrue(zip.isFile)
        assertEquals(0, harness.transport.deleteCount)
        assertEquals("RETIRING", BridgeSongStore(harness.outbox).records().single().task.state)
        assertTrue(BridgeSongStore(harness.outbox).records().single().task.canRetry)

        harness.transport.remote!!.put("state", "failed")
        val request = CanonicalJson.encode(failed.request)
        val zipBytes = zip.readBytes()
        harness.transport.deleteFailure = BridgeApiFailure(503, "job_cleanup_authorization_unconfirmed",
            "PC cleanup authorization was not durably acknowledged", true)
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(failed.task.id))
        val authorizationPending = BridgeSongStore(harness.outbox).records().single()
        assertEquals(BridgeSongPhase.RETIRING, authorizationPending.task.phase)
        assertTrue(authorizationPending.task.canRetry)
        val cleanupMessage = checkNotNull(authorizationPending.task.message)
        assertTrue(cleanupMessage, cleanupMessage.contains("Cleanup could not be confirmed"))
        assertTrue(cleanupMessage, cleanupMessage.contains("saved work is preserved"))
        assertFalse(authorizationPending.remoteRetired)
        assertTrue(authorizationPending.remoteTerminalObserved)
        assertFalse(authorizationPending.retired)
        assertArrayEquals(request, CanonicalJson.encode(authorizationPending.request))
        assertArrayEquals(zipBytes, zip.readBytes())
        assertEquals("failed", checkNotNull(harness.transport.remote).getString("state"))
        assertEquals(1, harness.transport.deleteCount)
        assertEquals(0, harness.transport.discardCount)
        assertEquals(0, harness.transport.submitCount)
        harness.transport.deleteFailure = null
        harness.transport.lookupFailure = BridgeApiFailure(409, "app_registration_required", "Receiver removed", false)
        harness.transport.discardFailure = SimulatedProcessInterruption()
        try { harness.workflow().advance(failed.task.id); error("Interruption was swallowed") }
        catch (_: SimulatedProcessInterruption) { }
        assertTrue(BridgeSongStore(harness.outbox).records().single().remoteRetired)
        assertFalse(BridgeSongStore(harness.outbox).records().single().retired)
        assertTrue(zip.isFile)
        assertEquals(1, harness.transport.deleteCount)
        harness.transport.discardFailure = null
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(failed.task.id))
        assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
        assertFalse(zip.exists())
        assertEquals(1, harness.transport.deleteCount)
        assertEquals(0, harness.transport.cancelCount)
        assertEquals(0, harness.transport.submitCount)
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
    }

    @Test
    fun retiringOneOfAFullFailedOutboxReclaimsOnlyItsSlotAndTransfer() = runBlocking {
        val store = BridgeSongStore(File(directory, "full"))
        val records = List(128) { index ->
            authorizedSong(root, originalText, guide, index.toLong()).let {
                it.copy(task = it.task.copy(phase = BridgeSongPhase.FAILED))
            }.also(store::save)
        }
        val transport = FakeTransport(mutableListOf())
        val workflow = BridgeSongWorkflow(store, { transport }, {}, { _, _, _ -> error("Unexpected import") },
            { _, _ -> error("Unexpected verification") }, {}, {})
        val target = records.first()
        val other = store.file(records.last().task.id, "zip").also { it.writeText("keep") }
        store.file(target.task.id, "zip").writeText("owned")
        assertTrue(workflow.retire(target.task.id))
        assertEquals(127, store.records().size)
        assertEquals("keep", other.readText())
        store.save(authorizedSong(root, originalText, guide, 200))
        assertEquals(128, store.records().size)
        assertEquals(0, transport.submitCount)
    }

    @Test
    fun admissionClosingDuringLookupCannotSubmitThePreviouslyQueuedRequest() = runBlocking {
        val harness = Harness()
        val record = harness.record()
        harness.transport.afterLookup = { harness.producerOpen = false }
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        assertEquals(0, harness.transport.submitCount)
        val retained = BridgeSongStore(harness.outbox).records().single()
        assertEquals("WAITING", retained.task.state)
        assertTrue(retained.task.canRetry)
        assertEquals(record.task.id, retained.task.id)
        harness.producerOpen = true
        harness.transport.afterLookup = null
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        assertEquals(1, harness.transport.submitCount)
    }

    @Test fun scheduledWorkerRetriesWhenBothOutboxAndDiagnosticWritesFailThenResumesTheSameRequest() = runBlocking {
        val harness = Harness("local-write-retry")
        val record = harness.record()
        val immutableRequest = CanonicalJson.encode(record.request)
        val blockedWrite = File(harness.outbox, "${record.task.id}.new")
        assertTrue(blockedWrite.mkdir())
        val result = advanceBridgeSongWork(waitForRemote = {}) { harness.advanceForWorker(record.task.id) }
        assertEquals(ListenableWorker.Result.retry(), result)
        val retained = BridgeSongStore(harness.outbox).records().single()
        assertEquals(record.task.id, retained.task.id)
        assertArrayEquals(immutableRequest, CanonicalJson.encode(retained.request))
        assertEquals(0, harness.transport.submitCount)
        assertTrue(blockedWrite.delete())
        harness.complete(record)
        val resumed = advanceBridgeSongWork(waitForRemote = {}) { harness.advanceForWorker(record.task.id) }
        assertEquals(BridgeSongStore(harness.outbox).records().single().attempts.lastOrNull()?.cause,
            ListenableWorker.Result.success(), resumed)
        assertEquals(0, harness.transport.submitCount)
        assertEquals(1, harness.events.count { it == "ack" })
        assertEquals("IMPORTED", BridgeSongStore(harness.outbox).records().single().task.state)
    }

    @Test fun transientWorkflowTransportFailureUsesWorkerBackoffWithoutHealthyContinuation() = runBlocking {
        val harness = Harness("worker-transport-backoff")
        val record = harness.record()
        harness.complete(record)
        harness.transport.receiveFailure = BridgeApiFailure(503, "pc_unavailable", "Unavailable", true)
        var polls = 0
        var waits = 0
        var continuations = 0
        val result = advanceBridgeSongWork(waitForRemote = { waits++ }, continuePending = { continuations++ }) {
            polls++
            harness.advanceForWorker(record.task.id)
        }
        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(1, polls)
        assertEquals(0, waits)
        assertEquals(0, continuations)
        val failed = BridgeSongStore(harness.outbox).records().single()
        assertEquals("RETRYING", failed.task.state)
        assertTrue(failed.task.retryable)
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
        harness.transport.receiveFailure = null
        assertEquals(ListenableWorker.Result.success(),
            advanceBridgeSongWork(waitForRemote = {}) { harness.advanceForWorker(record.task.id) })
        assertEquals("IMPORTED", BridgeSongStore(harness.outbox).records().single().task.state)
        assertEquals(1, harness.events.count { it == "install" })
    }

    @Test fun cancellingCompletedWithoutUsableOutputMakesOnlyOneCancelCall() = runBlocking {
        for (report in listOf<JSONObject?>(null, JSONObject().put("status", "waiting"))) {
            val harness = Harness("cancel-completed-${report != null}")
            val record = harness.record()
            assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
            harness.workflow().requestCancellation(record.task.id)
            checkNotNull(harness.transport.remote).put("state", "completed")
                .put("outputs", JSONArray()).put("cancelRequested", 0L).put("cancelRetired", 0L)
                .put("report", report ?: JSONObject.NULL)
            assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
            val stopped = BridgeSongStore(harness.outbox).records().single()
            assertEquals(BridgeSongPhase.CANCELLED, stopped.task.phase)
            assertEquals(1, harness.transport.cancelCount)
            assertEquals(record.task.id, stopped.task.id)
            assertFalse(stopped.imported)
        }
    }

    @Test fun forgettingMissingAcknowledgedFailureRequiresTheRetirementReceipt() = runBlocking {
        val harness = Harness("forget-missing-acknowledged")
        val record = harness.record()
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        val store = BridgeSongStore(harness.outbox)
        val accepted = store.records().single()
        store.save(accepted.copy(task = accepted.task.copy(phase = BridgeSongPhase.FAILED)))
        harness.transport.remote = null
        assertFalse(harness.workflow().retire(record.task.id))
        val uncertain = BridgeSongStore(harness.outbox).records().single()
        assertFalse(uncertain.remoteRetired)
        assertEquals(0, harness.transport.deleteCount)
        assertEquals(0, harness.transport.discardCount)
        harness.transport.retirementFailure = BridgeApiFailure(410, "request_deleted", "Tracking retired", false)
        assertTrue(harness.workflow().retire(record.task.id))
        assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
        assertEquals(2, harness.transport.retirementCount)
        assertEquals(1, harness.transport.submitCount)
    }

    @Test fun forgettingMissingFailurePreservesARetainedRunningRequest() = runBlocking {
        val harness = Harness("forget-retained-running")
        val original = harness.record()
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(original.task.id))
        val store = BridgeSongStore(harness.outbox)
        val accepted = store.records().single()
        assertEquals(BridgeSubmission.ACKNOWLEDGED, accepted.submission)
        store.save(accepted.copy(task = accepted.task.copy(phase = BridgeSongPhase.FAILED)))
        harness.transport.retirementRemote = harness.transport.remote
        harness.transport.remote = null
        assertFalse(harness.workflow().retire(original.task.id))
        val retained = BridgeSongStore(harness.outbox).records().single()
        assertFalse(retained.remoteRetired)
        assertEquals(0, harness.transport.deleteCount)
        assertEquals(0, harness.transport.discardCount)
        assertArrayEquals(CanonicalJson.encode(accepted.request), CanonicalJson.encode(retained.request))
    }

    @Test fun completedOutputStagingRemainsRecoverableWithoutCancellation() = runBlocking {
        val harness = Harness("completed-staging-recovery")
        val original = harness.record()
        harness.workflow().advance(original.task.id)
        checkNotNull(harness.transport.remote).put("state", "completed").put("outputs", JSONArray())
            .put("report", JSONObject().put("status", "waiting"))
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(original.task.id))
        assertFalse(BridgeSongStore(harness.outbox).records().single().task.terminal)
        harness.complete(original)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(original.task.id))
        assertTrue(BridgeSongStore(harness.outbox).records().single().imported)
        assertEquals(0, harness.transport.cancelCount)
    }

    @Test fun missingAcceptedRequestRequiresAuthoritativeRetirementAndNeverSubmitsAgain() = runBlocking {
        for (cancel in listOf(false, true)) {
            val harness = Harness("missing-retirement-$cancel")
            val record = harness.record()
            assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
            harness.transport.remote = null
            if (cancel) {
                harness.workflow().requestCancellation(record.task.id)
                harness.transport.cancelFailure = BridgeApiFailure(404, "job_unavailable", "Missing", false)
            }
            harness.workflow().advance(record.task.id)
            assertFalse(BridgeSongStore(harness.outbox).records().single().task.canForget)
            harness.transport.retirementFailure = BridgeApiFailure(410, "request_deleted", "Tracking retired", false)
            assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
            val retired = BridgeSongStore(harness.outbox).records().single()
            assertEquals(BridgeSongPhase.EXPIRED, retired.task.phase)
            assertTrue(retired.task.canForget)
            assertTrue(retired.remoteRetired)
            assertTrue(retired.task.message!!.contains("historical execution outcome unknown"))
            assertEquals(record.task.id, retired.task.id)
            assertEquals(1, harness.transport.submitCount)
            assertTrue(harness.workflow().retire(record.task.id))
            assertTrue(BridgeSongStore(harness.outbox).records().isEmpty())
        }
    }

    @Test fun retainedRemoteResultWinsTheMissingRequestRetirementRace() = runBlocking {
        val harness = Harness("retirement-race")
        val record = harness.record()
        val initial = harness.workflow().advance(record.task.id)
        val submitted = BridgeSongStore(harness.outbox).records().single()
        assertEquals("Initial submission: events=${harness.events}; phase=${submitted.task.phase}; " +
            "attempts=${submitted.attempts}", BridgeSongWorkProgress.PENDING, initial)
        val completed = harness.complete(record)
        harness.transport.remote = null
        harness.transport.retirementRemote = completed
        val progress = harness.workflow().advance(record.task.id)
        val restored = BridgeSongStore(harness.outbox).records().single()
        assertEquals("Retained completion: events=${harness.events}; phase=${restored.task.phase}; " +
            "submission=${restored.submission}; imported=${restored.imported}; " +
            "retirementCalls=${harness.transport.retirementCount}; submissions=${harness.transport.submitCount}; " +
            "attempts=${restored.attempts}", BridgeSongWorkProgress.COMPLETE, progress)
        assertEquals(BridgeSongPhase.IMPORTED, restored.task.phase)
        assertEquals(1, harness.transport.retirementCount)
        assertEquals(1, harness.transport.submitCount)
        assertEquals(1, harness.events.count { it == "install" })
    }

    @Test fun cancellationTransportFailureBacksOffButHealthyRetirementPendingUsesBoundedContinuation() = runBlocking {
        val harness = Harness("worker-cancel-backoff")
        val record = harness.record()
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        harness.workflow().requestCancellation(record.task.id)
        harness.transport.cancelFailure = BridgeApiFailure(503, "pc_unavailable", "Unavailable", true)
        var polls = 0
        var waits = 0
        var continuations = 0
        val failure = advanceBridgeSongWork(waitForRemote = { waits++ }, continuePending = { continuations++ }) {
            polls++
            harness.advanceForWorker(record.task.id)
        }
        assertEquals(ListenableWorker.Result.retry(), failure)
        assertEquals(1, polls)
        assertEquals(0, waits)
        assertEquals(0, continuations)
        val failed = BridgeSongStore(harness.outbox).records().single()
        assertEquals("CANCELLING", failed.task.state)
        assertTrue(bridgeTaskDiagnostics(failed) + "\n" + harness.workerFailure?.stackTraceToString(), failed.task.retryable)
        harness.transport.cancelFailure = null
        checkNotNull(harness.transport.remote).put("cancelRequested", 1L).put("cancelRetired", 0L)
        polls = 0
        val pending = advanceBridgeSongWork(waitForRemote = { waits++ }, continuePending = { continuations++ }) {
            polls++
            harness.advanceForWorker(record.task.id)
        }
        assertEquals(ListenableWorker.Result.success(), pending)
        assertEquals(6, polls)
        assertEquals(5, waits)
        assertEquals(1, continuations)
        val healthy = BridgeSongStore(harness.outbox).records().single()
        assertEquals("CANCELLING", healthy.task.state)
        assertFalse(healthy.task.retryable)
        assertTrue(healthy.task.cancelRequested)
        assertEquals(1, harness.transport.submitCount)
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
    }

    @Test fun cancellationWaitsForExactRetirementAndPreservesTheRequestAcrossRootLoss() = runBlocking {
        val harness = Harness("cancel-retirement")
        val record = harness.record()
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        val request = CanonicalJson.encode(BridgeSongStore(harness.outbox).records().single().request)
        harness.workflow().requestCancellation(record.task.id)
        assertTrue(BridgeSongStore(harness.outbox).records().single().task.cancelRequested)
        harness.currentRoot = root.copy(epoch = root.epoch + 1)
        val remote = checkNotNull(harness.transport.remote)
        remote.put("cancelRequested", 1L).put("cancelAcknowledged", 2L).put("cancelRetired", 0L)
        assertEquals(BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        assertEquals("CANCELLING", BridgeSongStore(harness.outbox).records().single().task.state)
        remote.put("state", "failed")
        assertEquals("Terminal state without requested-cancel retirement is not a receipt",
            BridgeSongWorkProgress.PENDING, harness.workflow().advance(record.task.id))
        remote.put("state", "cancelled")
        for (name in listOf("cancelRequested", "cancelRetired")) {
            for (malformed in listOf(null, "1", -1L, .5)) {
                remote.put("cancelRequested", 1L).put("cancelRetired", 3L)
                if (malformed == null) remote.remove(name) else remote.put(name, malformed)
                assertEquals("Malformed $name=$malformed was accepted as retirement", BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
                val pending = BridgeSongStore(harness.outbox).records().single()
                assertTrue(pending.task.cancelRequested)
                assertEquals("CANCELLING", pending.task.state)
                assertArrayEquals(request, CanonicalJson.encode(pending.request))
                assertEquals(1, harness.transport.submitCount)
                assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
            }
        }
        remote.put("cancelRequested", 1L).put("cancelRetired", 3L)
        assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id))
        val settled = BridgeSongStore(harness.outbox).records().single()
        assertEquals("CANCELLED", settled.task.state)
        assertArrayEquals(request, CanonicalJson.encode(settled.request))
        assertEquals(1, harness.transport.submitCount)
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
        assertTrue(harness.events.isEmpty())
    }

    @Test fun missingOrDisconnectedCancellationNeverResubmitsEvenAfterColdOutboxReload() = runBlocking {
        val harness = Harness("cancel-missing")
        val record = harness.record()
        BridgeSongStore(harness.outbox).save(record.copy(submission = BridgeSubmission.UNKNOWN))
        harness.workflow().requestCancellation(record.task.id)
        harness.transport.cancelFailure = IOException("PC disconnected")
        assertEquals(BridgeSongWorkProgress.RETRY, harness.workflow().advance(record.task.id))
        harness.transport.cancelFailure = BridgeApiFailure(404, "job_unavailable", "Unavailable", true)
        repeat(2) { assertEquals(BridgeSongWorkProgress.COMPLETE, harness.workflow().advance(record.task.id)) }
        val retained = BridgeSongStore(harness.outbox).records().single()
        assertTrue(retained.task.cancelRequested)
        assertEquals("WAITING", retained.task.state)
        assertEquals(0, harness.transport.prepareCount)
        assertEquals(0, harness.transport.submitCount)
        assertEquals(3, harness.transport.cancelCount)
        assertEquals("prior library song", harness.published.readText(Charsets.UTF_8))
    }

    @Test fun alreadyCompletedCancellationImportsTheOriginalResultAndNeverReplacesCanonicalMedia() = runBlocking {
        val harness = Harness("cancel-completed")
        val record = harness.record()
        val initialProgress = harness.workflow().advance(record.task.id)
        assertEquals(bridgeTaskDiagnostics(BridgeSongStore(harness.outbox).records().single()),
            BridgeSongWorkProgress.PENDING, initialProgress)
        harness.complete(record).put("cancelRequested", 0L).put("cancelAcknowledged", 0L).put("cancelRetired", 0L)
        harness.workflow().requestCancellation(record.task.id)
        val pending = harness.workflow().advance(record.task.id)
        assertEquals(bridgeTaskDiagnostics(BridgeSongStore(harness.outbox).records().single()), BridgeSongWorkProgress.COMPLETE, pending)
        val completed = BridgeSongStore(harness.outbox).records().single()
        assertEquals("IMPORTED", completed.task.state)
        assertEquals(videoId, harness.published.readText(Charsets.UTF_8))
        val bytes = harness.published.readBytes()
        val saved = CanonicalJson.encode(completed.json())
        harness.workflow().requestCancellation(record.task.id)
        assertArrayEquals(saved, CanonicalJson.encode(BridgeSongStore(harness.outbox).records().single().json()))
        assertArrayEquals(bytes, harness.published.readBytes())
        assertEquals(1, harness.events.count { it == "install" })
        assertEquals(1, harness.transport.cancelCount)
        assertEquals(1, harness.transport.submitCount)
    }

    @Test fun diagnosticsDistinguishCausesAndCopyOnlyBoundedTaskHistory() {
        assertTrue(bridgeFailureMessage(IOException("ENOSPC")).contains("Storage is full"))
        assertTrue(bridgeFailureMessage(SecurityException("Permission denied")).contains("Library access"))
        assertTrue(bridgeFailureMessage(BridgeApiFailure(503, "pc_unavailable", "Unavailable", true)).contains("PC is unavailable"))
        assertTrue(bridgeFailureMessage(BridgeLocalFailure(BridgeFailureCode.BRIDGE_OUTPUT_CHANGED)).contains("ZIP verification"))
        assertTrue(bridgeFailureMessage(IOException("BRIDGE_OUTBOX_UNAVAILABLE")).contains("could not be written"))
        val fullMessage = "Complete diagnostic: " + "m".repeat(1_500)
        val task = authorizedSong(root, originalText, "private instructions", 1L).let { record ->
            record.copy(task = record.task.copy(message = fullMessage), attempts = List(8) { index -> BridgeAttempt(index + 1, 1L, 2L, "RECEIVING", "failed",
                cause = "technical-cause-" + "x".repeat(4096), verifiedTransferBytes = 123L) })
        }
        val text = bridgeTaskDiagnostics(task)
        val json = JSONObject(text)
        assertEquals(task.task.id, json.getString("requestId"))
        assertEquals(8, json.getJSONArray("attempts").length())
        assertEquals(fullMessage, json.getString("message"))
        assertTrue(json.getJSONArray("attempts").getJSONObject(0).getString("cause").startsWith("technical-cause-"))
        assertTrue(text.length < 40_000)
        assertFalse(text.contains("private instructions"))
        assertFalse(text.contains(originalText))
        assertFalse(json.has("request"))
    }

    private inner class Harness(name: String = "main") {
        init { harnesses += this }
        val outbox = File(directory, "$name/outbox")
        val published = File(directory, "$name/published.txt").also {
            check(checkNotNull(it.parentFile).mkdirs())
            it.writeText("prior library song", Charsets.UTF_8)
        }
        val events = mutableListOf<String>()
        val boundConnections = mutableListOf<BridgeConnectionProfile>()
        val transport = FakeTransport(events)
        var currentRoot = root
        var producerOpen = true
        var interruptVerification = false
        var installFailure: Exception? = null
        var verificationFailure: Exception? = null
        var explicitlyDeleted = false
        var afterImportedSave: (() -> Unit)? = null
        var beforeArchiveCleanup: (suspend (File) -> Unit)? = null

        fun record(createdAt: Long = 123_456L): BridgeSongRecord = authorizedSong(root, originalText, guide, createdAt).also {
            BridgeSongStore(outbox).save(it)
        }

        fun complete(record: BridgeSongRecord, bytes: ByteArray = transport.receiveBytes): JSONObject =
            JSONObject().put("id", record.task.id).put("state", "completed")
                .put("report", JSONObject().put("status", "completed").put("revision", 1).put("code", "stemwise_song_ready"))
                .put("outputs", JSONArray().put(descriptor("b".repeat(32), "song.zip", bytes).json()))
                .also { transport.remote = it }

        var workerFailure: Exception? = null
        suspend fun advanceForWorker(id: String): BridgeSongWorkProgress = try {
            workflow().advance(id)
        } catch (error: Exception) {
            workerFailure = error
            throw error
        }

        fun workflow() = BridgeSongWorkflow(
            BridgeSongStore(outbox), { profile -> boundConnections += profile; transport },
            requireRoot = {
                checkBridge(it == currentRoot, BridgeFailureCode.LIBRARY_CHANGED)
                checkBridge(producerOpen, BridgeFailureCode.RECOVERY_PENDING)
            },
            install = { file, slot, expectedRoot ->
                events += "install"
                installFailure?.let { throw it }
                assertEquals(currentRoot, expectedRoot)
                val entry = ZipInputStream(file.inputStream()).use { zip ->
                    if (zip.nextEntry?.name != "song.txt") throw EnrichmentImportException("ZIP_FORMAT_UNRECOGNIZED")
                    String(zip.readBytes(), Charsets.UTF_8)
                }
                check(entry == slot) { "WRONG_SONG_SLOT" }
                published.writeText(entry, Charsets.UTF_8)
            },
            verifyInstalled = { slot, expectedRoot ->
                events += "verify"
                verificationFailure?.let { throw it }
                if (interruptVerification) throw SimulatedProcessInterruption()
                assertEquals(currentRoot, expectedRoot)
                check(published.readText(Charsets.UTF_8) == slot)
            },
            preserveArchive = { beforeArchiveCleanup?.invoke(it) },
            explicitlyDeleted = { _, expectedRoot -> assertEquals(currentRoot, expectedRoot); explicitlyDeleted },
            changed = {
                val saved = BridgeSongStore(outbox).records().singleOrNull()
                if (saved?.task?.state == "IMPORTED") {
                    val callback = afterImportedSave
                    afterImportedSave = null
                    callback?.invoke()
                }
            }
        )
    }

    private inner class FakeTransport(private val events: MutableList<String>) : SongBridgeTransport {
        var logFailure: Exception? = null
        var malformedLog = false
        var observeLogs = false
        var log: JSONObject? = null
        private val observations = BridgeJobObservationCache()
        var remote: JSONObject? = null
        var cancelFailure: Exception? = null
        var cancelCount = 0
        var retirementFailure: Exception? = null
        var retirementRemote: JSONObject? = null
        var retirementCount = 0
        override suspend fun retireMissingJob(id: String): JSONObject {
            retirementCount++
            retirementFailure?.let { throw it }
            retirementRemote?.let { remote = it; return checkNotNull(job(id)) }
            throw BridgeApiFailure(404, "job_unavailable", "No authoritative retirement", false)
        }
        override suspend fun api(method: String, path: String, body: JSONObject?): JSONObject {
            val id = path.removePrefix("/v2/jobs/").substringBefore('/')
            if (method == "GET") return when (path) {
                "/v2/jobs/$id/inputs" -> remote?.optJSONObject("input") ?: JSONObject(
                    directory.walkTopDown().single { it.name == "$id.json" }.readText(Charsets.UTF_8)).getJSONObject("request")
                "/v2/jobs/$id/log" -> {
                    logFailure?.let { throw it }
                    log ?: JSONObject().put("entries", if (malformedLog) "invalid" else JSONArray())
                }
                else -> error("Unexpected observation $path")
            }
            assertEquals("POST", method)
            assertEquals("/v2/jobs/$id/cancel", path)
            val saved = directory.walkTopDown().single { it.name == "$id.json" }
            assertTrue("Cancellation intent must be durable before the RPC", JSONObject(saved.readText(Charsets.UTF_8)).getBoolean("cancelRequested"))
            cancelCount++
            cancelFailure?.let { throw it }
            // appJobView omits input; exercise production enrichment instead of returning job().
            return JSONObject(checkNotNull(remote).toString()).also { it.remove("input") }
        }
        var loseSubmitAcknowledgement = false
        var receiveFailure: Exception? = null
        var retainedInboxBytes: ByteArray? = null
        var ackFailure: Exception? = null
        var discardFailure: Throwable? = null
        var deleteFailure: Throwable? = null
        var deleteCount = 0
        var discardCount = 0
        var afterLookup: (() -> Unit)? = null
        var submitCount = 0
        var prepareCount = 0
        val guideRequests = mutableListOf<BridgeFile>()
        var guideSendCount = 0
        var interruptBeforeGuideSend = false
        var loseGuideAcknowledgement = false
        private var savedGuide: BridgeFile? = null
        private var preparedGuide: BridgeFile? = null
        private var preparedContext: BridgeFile? = null
        val restoredInputs = mutableListOf<BridgeFile>()
        val storedInputs = mutableSetOf<BridgeFile>()
        fun expirePreparedInputs() {
            preparedGuide = null
            preparedContext = null
        }
        var contextBytes: ByteArray? = null
        var contextSendCount = 0
        var receiveBytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("song.txt"))
                zip.write(videoId.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()
        private var input: JSONObject? = null

        override suspend fun prepareInput(source: File, name: String): BridgeFile {
            if (name == "stemwise-song.json") {
                contextBytes = source.readBytes()
                return descriptor("c".repeat(32), name, source.readBytes()).also { preparedContext = it }
            }
            assertEquals("SEND_TO_AGENTS.md", name)
            prepareCount++
            assertEquals(guide, source.readText(Charsets.UTF_8))
            return descriptor("a".repeat(32), "SEND_TO_AGENTS.md", source.readBytes()).also { preparedGuide = it }
        }
        override suspend fun ensureInput(file: BridgeFile, source: File) {
            assertTrue(file.matches(source))
            if (file.name == "stemwise-song.json" && preparedContext == null) {
                preparedContext = file
                restoredInputs += file
            } else if (file.name == "SEND_TO_AGENTS.md" && preparedGuide == null) {
                preparedGuide = file
                restoredInputs += file
            }
            if (file.name == "stemwise-song.json") {
                check(file == preparedContext)
                if (contextSendCount == 0) contextSendCount++
                storedInputs += file
                return
            }
            guideRequests += file
            check(file == preparedGuide)
            if (savedGuide == null) {
                if (interruptBeforeGuideSend) { interruptBeforeGuideSend = false; throw SimulatedProcessInterruption() }
                guideSendCount++
                savedGuide = file
                if (loseGuideAcknowledgement) { loseGuideAcknowledgement = false; throw IOException("Guide receipt lost after storage") }
            }
            check(savedGuide == file)
            storedInputs += file
        }
        var lookupFailure: Exception? = null
        var lookupCount = 0
        override suspend fun job(id: String): JSONObject? {
            lookupCount++
            lookupFailure?.let { throw it }
            afterLookup?.invoke()
            val observed = remote?.also {
                assertEquals(id, it.getString("id"))
                if (!it.has("input")) {
                    // A prepared input descriptor is immutable before the first remote lookup.
                    val saved = directory.walkTopDown().single { file -> file.name == "$id.json" }
                    input = JSONObject(saved.readText(Charsets.UTF_8)).getJSONObject("request")
                    it.put("input", JSONObject(checkNotNull(input).toString()))
                }
            }
            return if (observeLogs && observed != null) observations.complete(id, observed,
                { api("GET", "/v2/jobs/$id/inputs") }, { api("GET", "/v2/jobs/$id/log") }) else observed
        }
        override suspend fun submit(request: JSONObject): JSONObject {
            val requestedFiles = request.getJSONArray("files")
            for (index in 0 until requestedFiles.length()) {
                if (BridgeFile.parse(requestedFiles.getJSONObject(index)) !in storedInputs)
                    throw BridgeApiFailure(409, "input_bytes_unverified", "Input expired before admission", false)
            }
            submitCount++
            input = JSONObject(request.toString())
            remote = JSONObject().put("id", request.getString("requestId")).put("state", "running")
                .put("input", JSONObject(request.toString())).put("outputs", JSONArray())
            if (loseSubmitAcknowledgement) throw IOException("Acknowledgement lost after acceptance")
            return checkNotNull(remote)
        }
        override suspend fun receiveRetained(file: BridgeFile, destination: File): Boolean {
            val bytes = retainedInboxBytes ?: return false
            checkBridge(descriptor(file.id, file.name, bytes) == file, BridgeFailureCode.BRIDGE_OUTPUT_CHANGED)
            events += "receive-local"
            destination.writeBytes(bytes)
            return true
        }
        override suspend fun receive(file: BridgeFile, destination: File) {
            events += "receive"
            receiveFailure?.let { throw it }
            check(!destination.exists())
            destination.writeBytes(receiveBytes)
        }
        var online = true
        override val networkAvailable get() = online
        override suspend fun acknowledge(id: String, file: BridgeFile, destination: File) {
            events += "ack"
            ackFailure?.let { throw it }
            assertEquals(id, checkNotNull(remote).getString("id"))
            assertArrayEquals(receiveBytes, destination.readBytes())
            val saved = directory.walkTopDown().single { it.name == "$id.json" }
            assertTrue(JSONObject(saved.readText(Charsets.UTF_8)).getBoolean("imported"))
            ackFailure?.let { throw it }
        }
        override suspend fun deleteJob(id: String) {
            deleteCount++
            deleteFailure?.let { throw it }
            remote = null
        }
        override suspend fun discardTransfers(record: BridgeSongRecord) {
            check(record.remoteRetired)
            discardCount++
            discardFailure?.let { throw it }
        }
    }

    private fun descriptor(id: String, name: String, bytes: ByteArray) = BridgeFile(
        id, name, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    )

    private class SimulatedProcessInterruption : Error()
}
