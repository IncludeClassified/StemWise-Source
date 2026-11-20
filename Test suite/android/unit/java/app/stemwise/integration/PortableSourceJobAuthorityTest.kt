package app.stemwise.integration

import app.stemwise.data.CanonicalJson
import app.stemwise.data.CustomSourceAsset
import app.stemwise.data.NATIVE_ORIGINAL_48K_REMAINING_PRODUCER
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableSourceJobAuthorityTest {
    @Test fun timingObservationsFollowExactAcceptedArtifactsAndCannotBeReboundOnRestart() {
        val downloading = step(step(step(created(), SourceJobTransition.ENTER_SUBMISSION),
            SourceJobTransition.ACKNOWLEDGE_REMOTE, "timing-accepted"), SourceJobTransition.BEGIN_DOWNLOAD)
        val asset = CustomSourceAsset("bass", "Bass", "part-1.flac", "c".repeat(64), 1_000, 48_000)
        val original = originalFacts()
        val evidence = sourceTimingEvidence(downloading, asset, original,
            app.stemwise.data.SourceTimingRawArtifact(asset.sha256, asset.byteLength, 48_000, 2, 48_000),
            app.stemwise.data.SOURCE_TIMING_COPY_PROFILE)
        val observed = asset.copy(timingEvidence = evidence)
        val recorded = recordPortableSourceAsset(downloading, observed, downloading.updatedAtEpochMs + 1)
        val durable = recorded.toBytes()
        assertEquals(recorded, selectPortableSourceJobAuthority(downloading.toBytes(), durable))
        repeat(2) {
            val restored = parsePortableSourceJobAuthority(durable)
            assertEquals(evidence, restored.assets.getValue("bass").timingEvidence)
            assertEquals("timing-accepted", restored.currentRemoteJobId)
            assertFalse(restored.requiresSubmission)
            assertEquals(app.stemwise.data.SourceJobTimingStatus.JOB_TIMING_UNVERIFIED,
                restored.assets.getValue("bass").timingEvidence!!.jobStatus)
        }
        for (changed in listOf(evidence.copy(sourceVersionId = "changed-source"),
            evidence.copy(sourceSha256 = "e".repeat(64)), evidence.copy(planSha256 = "e".repeat(64)),
            evidence.copy(jobId = "changed-job"), evidence.copy(generation = 2),
            evidence.copy(configurationId = "other-config"), evidence.copy(renderId = 49),
            evidence.copy(options = mapOf("add_opt1" to "0")), evidence.copy(providerRole = "vocals"),
            evidence.copy(remoteJobId = "different-accepted"))) {
            assertThrows(IllegalStateException::class.java) {
                recordPortableSourceAsset(downloading, asset.copy(timingEvidence = changed), downloading.updatedAtEpochMs + 1)
            }
        }
        val forged = JSONObject(durable.toString(Charsets.UTF_8))
        forged.getJSONObject("assets").getJSONObject("bass").getJSONObject("timingEvidence")
            .put("jobStatus", "JOB_TIMING_VERIFIED_WITHIN_BOUND")
        forged.remove("integritySha256")
        forged.put("integritySha256", CanonicalJson.sha256(forged))
        assertThrows(IllegalStateException::class.java) { parsePortableSourceJobAuthority(CanonicalJson.encode(forged)) }
        org.junit.Assert.assertArrayEquals(durable, recorded.toBytes())
    }

    @Test fun recoveredPublishedPartCanRecordUnknownRawWithoutInventingEvidenceOrResubmitting() {
        val downloading = step(step(step(created(), SourceJobTransition.ENTER_SUBMISSION),
            SourceJobTransition.ACKNOWLEDGE_REMOTE, "recover-timing"), SourceJobTransition.BEGIN_DOWNLOAD)
        val asset = CustomSourceAsset("bass", "Bass", "part-1.flac", "c".repeat(64), 1_000, 48_000)
        val recovered = asset.copy(timingEvidence = sourceTimingEvidence(downloading, asset,
            originalFacts(), null,
            app.stemwise.data.SOURCE_TIMING_RETAINED_PROFILE))
        val restored = parsePortableSourceJobAuthority(recordPortableSourceAsset(downloading, recovered,
            downloading.updatedAtEpochMs + 1).toBytes())
        assertNull(restored.assets.getValue("bass").timingEvidence!!.raw)
        assertFalse(restored.requiresSubmission)
        val missing = receipt(downloading, "bass")
        assertNull(parsePortableSourceJobAuthority(missing.toBytes()).assets.getValue("bass").timingEvidence)
        assertFalse(missing.requiresSubmission)
    }

    @Test
    fun eightAcceptedOperationsSurviveRestartsAfterThreeAndSevenWithoutResubmission() = kotlinx.coroutines.runBlocking {
        val plan = eightOperationMergerPlan()
        var authority = createPortableSourceJobAuthority(LIBRARY_ID, VIDEO_ID, SOURCE_VERSION, SOURCE_SHA, plan, NOW)
        val remoteIds = (1..8).map { "accepted-merger-job-$it" }
        var submissions = 0
        plan.selectedSourceIds.forEachIndexed { index, id ->
            withRequiredSourceSubmission(authority) { submissions++ }
            authority = step(step(authority, SourceJobTransition.ENTER_SUBMISSION),
                SourceJobTransition.ACKNOWLEDGE_REMOTE, remoteIds[index])
            assertThrows(IllegalStateException::class.java) { step(authority, SourceJobTransition.BEGIN_ASSEMBLY) }
            if (index in listOf(2, 6)) {
                val durable = authority.toBytes()
                repeat(2) {
                    authority = parsePortableSourceJobAuthority(durable)
                    withRequiredSourceSubmission(authority) { error("Accepted operation was resubmitted") }
                    assertEquals(remoteIds.take(index + 1), authority.remoteJobIds.filterNotNull())
                    assertEquals(plan, authority.plan)
                    assertEquals(plan.selectedSourceIds.take(index).toSet(), authority.assets.keys)
                    org.junit.Assert.assertArrayEquals(durable, authority.toBytes())
                }
            }
            authority = step(authority, SourceJobTransition.BEGIN_DOWNLOAD)
            assertThrows(IllegalStateException::class.java) {
                step(authority, if (index == 7) SourceJobTransition.BEGIN_ASSEMBLY else SourceJobTransition.ADVANCE_OPERATION)
            }
            authority = receipt(authority, id)
            assertEquals(authority, parsePortableSourceJobAuthority(authority.toBytes()))
            authority = step(authority, if (index == 7) SourceJobTransition.BEGIN_ASSEMBLY else SourceJobTransition.ADVANCE_OPERATION)
        }
        assertEquals(8, submissions)
        assertEquals(remoteIds, authority.remoteJobIds)
        assertEquals(8, authority.assets.size)
        assertThrows(IllegalStateException::class.java) { step(authority, SourceJobTransition.BEGIN_PUBLICATION) }
        authority = receipt(authority, "remaining")
        val lanes = (plan.selectedSourceIds + "remaining").map { authority.assets.getValue(it) }
        val manifest = app.stemwise.data.CustomSourceManifest(VIDEO_ID, SOURCE_SHA, authority.planSha256,
            plan.selectedSourceIds, 48_000L, lanes)
        val reopened = app.stemwise.data.parseCustomSourceManifest(manifest.toBytes(), VIDEO_ID, SOURCE_SHA)
        assertEquals(lanes, reopened.lanes)
        assertEquals(authority.planSha256, reopened.planSha256)
        authority = step(step(authority, SourceJobTransition.BEGIN_PUBLICATION), SourceJobTransition.COMPLETE)
        assertEquals(authority, parsePortableSourceJobAuthority(authority.toBytes()))
        assertEquals(9, authority.assets.size)
        assertEquals(remoteIds, authority.remoteJobIds)
        withRequiredSourceSubmission(authority) { error("Completed operation was resubmitted") }
    }

    @Test
    fun eightOperationReceiptsRejectMissingDuplicateSlotsAndSubstitutionWithoutChangingDurableState() {
        val plan = eightOperationMergerPlan()
        var authority = createPortableSourceJobAuthority(LIBRARY_ID, VIDEO_ID, SOURCE_VERSION, SOURCE_SHA, plan, NOW)
        plan.selectedSourceIds.forEachIndexed { index, id ->
            authority = step(step(step(authority, SourceJobTransition.ENTER_SUBMISSION),
                SourceJobTransition.ACKNOWLEDGE_REMOTE, "receipt-job-$index"), SourceJobTransition.BEGIN_DOWNLOAD)
            val durable = authority.toBytes()
            if (index < 7) assertThrows(IllegalStateException::class.java) {
                receipt(authority, plan.selectedSourceIds[index + 1])
            }
            if (index > 0) {
                val prior = authority.assets.getValue(plan.selectedSourceIds[index - 1])
                val wrongSlot = prior.copy(sourceId = id,
                    label = checkNotNull(app.stemwise.model.StemSourceCatalogue.source(id)).label)
                assertThrows(IllegalStateException::class.java) {
                    recordPortableSourceAsset(authority, wrongSlot, authority.updatedAtEpochMs + 1)
                }
            }
            org.junit.Assert.assertArrayEquals(durable, authority.toBytes())
            authority = receipt(authority, id, "c".repeat(64))
            val recorded = authority.toBytes()
            val asset = authority.assets.getValue(id)
            assertEquals(authority, recordPortableSourceAsset(authority, asset, authority.updatedAtEpochMs + 1))
            for (changed in listOf(asset.copy(sha256 = "f".repeat(64)), asset.copy(byteLength = asset.byteLength + 1),
                asset.copy(audibleFrames = asset.audibleFrames - 1))) {
                assertThrows(IllegalStateException::class.java) {
                    recordPortableSourceAsset(authority, changed, authority.updatedAtEpochMs + 1)
                }
            }
            org.junit.Assert.assertArrayEquals(recorded, authority.toBytes())
            authority = step(authority, if (index == 7) SourceJobTransition.BEGIN_ASSEMBLY else SourceJobTransition.ADVANCE_OPERATION)
        }
        for (id in plan.selectedSourceIds) assertThrows(IllegalArgumentException::class.java) {
            authority.copy(assets = authority.assets - id)
        }
        assertEquals(setOf("c".repeat(64)), authority.assets.values.map { it.sha256 }.toSet())
        assertEquals(8, authority.assets.values.map { it.fileName }.toSet().size)
        val durable = authority.toBytes()
        assertThrows(IllegalStateException::class.java) { step(authority, SourceJobTransition.BEGIN_PUBLICATION) }
        org.junit.Assert.assertArrayEquals(durable, parsePortableSourceJobAuthority(durable).toBytes())
    }

    @Test
    fun eightOperationRecoveryRejectsWrongOriginalPlanGenerationAndChangedAcceptedIdentity() {
        val plan = eightOperationMergerPlan()
        var authority = createPortableSourceJobAuthority(LIBRARY_ID, VIDEO_ID, SOURCE_VERSION, SOURCE_SHA, plan, NOW)
        repeat(7) { index ->
            authority = step(step(step(authority, SourceJobTransition.ENTER_SUBMISSION),
                SourceJobTransition.ACKNOWLEDGE_REMOTE, "identity-job-$index"), SourceJobTransition.BEGIN_DOWNLOAD)
            authority = step(receipt(authority, plan.selectedSourceIds[index]), SourceJobTransition.ADVANCE_OPERATION)
        }
        val submitted = step(authority, SourceJobTransition.ENTER_SUBMISSION)
        for (accepted in submitted.remoteJobIds.filterNotNull()) assertThrows(IllegalStateException::class.java) {
            step(submitted, SourceJobTransition.ACKNOWLEDGE_REMOTE, accepted)
        }
        val accepted = step(submitted, SourceJobTransition.ACKNOWLEDGE_REMOTE, "identity-job-7")
        val durable = accepted.toBytes()
        val changedId = accepted.copy(revision = accepted.revision + 1,
            remoteJobIds = accepted.remoteJobIds.mapIndexed { index, id -> if (index == 3) "substituted-job" else id })
        assertThrows(IllegalStateException::class.java) { selectPortableSourceJobAuthority(durable, changedId.toBytes()) }
        val otherPlan = plan.copy(operations = plan.operations.reversed())
        val foreign = listOf(
            createPortableSourceJobAuthority(LIBRARY_ID, VIDEO_ID, SOURCE_VERSION, "e".repeat(64), plan, NOW),
            createPortableSourceJobAuthority(LIBRARY_ID, VIDEO_ID, SOURCE_VERSION, SOURCE_SHA, otherPlan, NOW),
            createPortableSourceJobAuthority(LIBRARY_ID, VIDEO_ID, SOURCE_VERSION, SOURCE_SHA, plan, NOW, 2L))
        foreign.forEach { other ->
            assertThrows(IllegalStateException::class.java) { selectPortableSourceJobAuthority(durable, other.toBytes()) }
        }
        assertEquals(accepted, selectPortableSourceJobAuthority(durable, durable))
        org.junit.Assert.assertArrayEquals(durable, accepted.toBytes())
    }

    @Test
    fun onlyAcknowledgedTerminalFailureCanBeDiscardedAndNeverResubmitted() {
        val submitting = step(created(), SourceJobTransition.ENTER_SUBMISSION)
        val unknown = step(submitting, SourceJobTransition.MARK_SUBMISSION_UNKNOWN)
        assertThrows(IllegalStateException::class.java) { step(unknown, SourceJobTransition.DISCARD_FAILED) }
        val running = step(submitting, SourceJobTransition.ACKNOWLEDGE_REMOTE, "terminal-job")
        val failed = step(running, SourceJobTransition.REMOTE_FAILED)
        assertEquals("FAILED", projectSourceJob(failed).state)
        assertEquals("MVSEP_REMOTE_FAILED", projectSourceJob(failed).lastErrorCode)
        val discarded = step(failed, SourceJobTransition.DISCARD_FAILED)
        assertEquals(discarded, parsePortableSourceJobAuthority(discarded.toBytes()))
        assertEquals(discarded, selectPortableSourceJobAuthority(failed.toBytes(), discarded.toBytes()))
        assertEquals("OBSOLETE", projectSourceJob(discarded, projectSourceJob(failed)).state)
        assertFalse(discarded.requiresSubmission)
        assertEquals("terminal-job", discarded.currentRemoteJobId)
        val next = createPortableSourceJobAuthority(discarded.libraryId, discarded.videoId, discarded.sourceVersionId,
            discarded.sourceSha256, discarded.plan, discarded.updatedAtEpochMs + 1, 2L)
        assertEquals(next, latestSourceJobAuthority(listOf(discarded, next)))
    }
    @Test
    fun unboundSongEnvelopeCannotBecomeARecoverablePerSongIssue() {
        val root = app.stemwise.data.RootAuthority("library", "content://provider/tree/current", 7L)
        val record = app.stemwise.data.AtomicJournalRecord(java.io.File("source-corrupt.json"), JSONObject()
            .put("schemaVersion", 2).put("transactionId", "source-corrupt").put("packageId", "not/a/song")
            .put("operation", "SOURCE_JOB_AUTHORITY_V1").put("libraryId", root.libraryId)
            .put("treeUri", root.treeUri).put("rootEpoch", root.epoch).put("sequence", 2)
            .put("state", "AUTHORITY_WRITING").put("details", JSONObject().put("authorityJson", "{")))
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { replayJournaledSourceAuthorities(root, listOf(record)) { error("Unexpected publish") } }
        }
    }

    @Test
    fun damagedSourceJournalIsReportedWithoutLosingAnotherAcceptedAuthority() = kotlinx.coroutines.runBlocking {
        val acknowledged = step(step(created(), SourceJobTransition.ENTER_SUBMISSION),
            SourceJobTransition.ACKNOWLEDGE_REMOTE, "retained-remote")
        val root = app.stemwise.data.RootAuthority(acknowledged.libraryId, "content://provider/tree/current", 7L)
        fun journal(id: String, transaction: String, bytes: String) = app.stemwise.data.AtomicJournalRecord(
            java.io.File("$transaction.json"), JSONObject().put("schemaVersion", 2).put("transactionId", transaction)
                .put("packageId", id).put("operation", "SOURCE_JOB_AUTHORITY_V1").put("libraryId", root.libraryId)
                .put("treeUri", root.treeUri).put("rootEpoch", root.epoch).put("sequence", 2)
                .put("state", "AUTHORITY_WRITING").put("details", JSONObject().put("authorityJson", bytes)))
        val damaged = journal("DAMAGED0001", "source-damaged", "{")
        val good = journal(acknowledged.videoId, "source-${acknowledged.jobId}",
            acknowledged.toBytes().toString(Charsets.UTF_8))
        val published = mutableListOf<PortableSourceJobAuthority>()
        val issues = replayJournaledSourceAuthorities(root, listOf(damaged, good)) { published += it }
        assertEquals(listOf(SourceAuthorityRecoveryIssue("DAMAGED0001", PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED)), issues)
        assertEquals(listOf(acknowledged), published)
        assertFalse(published.single().requiresSubmission)
        assertEquals("retained-remote", published.single().currentRemoteJobId)
        assertEquals("{", damaged.value.getJSONObject("details").getString("authorityJson"))
    }

    @Test
    fun replacementGenerationHasDistinctOwnershipAndCannotDisplaceUnresolvedRemoteWork() {
        val old = created()
        val next = createPortableSourceJobAuthority(old.libraryId, old.videoId, old.sourceVersionId,
            old.sourceSha256, old.plan, old.updatedAtEpochMs + 1, 2L)
        assertTrue(old.jobId != next.jobId)
        for (id in old.plan.selectedSourceIds + "remaining") {
            assertFalse(sourcePartFileName(1L, old.plan.selectedSourceIds, id) ==
                sourcePartFileName(2L, old.plan.selectedSourceIds, id))
        }
        assertEquals(next, parsePortableSourceJobAuthority(next.toBytes()))
        assertThrows(IllegalStateException::class.java) { latestSourceJobAuthority(listOf(old, next)) }
        val unknown = step(step(old, SourceJobTransition.ENTER_SUBMISSION), SourceJobTransition.MARK_SUBMISSION_UNKNOWN)
        assertThrows(IllegalStateException::class.java) { latestSourceJobAuthority(listOf(unknown, next)) }
        assertEquals(next, latestSourceJobAuthority(listOf(next)))
    }

    @Test
    fun definiteRejectionCanRetryButUnknownOnlyRecoversTheExactAcceptedJob() = kotlinx.coroutines.runBlocking {
        val submitted = step(created(), SourceJobTransition.ENTER_SUBMISSION)
        val rejected = step(submitted, SourceJobTransition.REJECT_SUBMISSION)
        val reopened = parsePortableSourceJobAuthority(rejected.toBytes())
        assertFalse(reopened.requiresSubmission)
        assertEquals("SUBMISSION_REJECTED", reopened.state)
        assertEquals("FAILED", projectSourceJob(reopened).state)
        assertEquals(1, reopened.attempts)
        assertEquals(submitted.plan, reopened.plan)
        assertEquals(rejected, selectPortableSourceJobAuthority(submitted.toBytes(), rejected.toBytes()))
        val retried = step(reopened, SourceJobTransition.RETRY_REJECTED)
        assertTrue(retried.requiresSubmission)
        assertEquals("SUBMISSION_STARTED", step(retried, SourceJobTransition.ENTER_SUBMISSION).state)
        val unknown = step(submitted, SourceJobTransition.MARK_SUBMISSION_UNKNOWN)
        assertThrows(IllegalStateException::class.java) { step(unknown, SourceJobTransition.REJECT_SUBMISSION) }
        val recovered = transitionPortableSourceJob(unknown, SourceJobTransition.RECOVER_REMOTE,
            unknown.updatedAtEpochMs + 1, "owner-confirmed-exact-id")
        assertEquals(recovered, selectPortableSourceJobAuthority(unknown.toBytes(), recovered.toBytes()))
        var uploads = 0
        withRequiredSourceSubmission(parsePortableSourceJobAuthority(recovered.toBytes())) { uploads++ }
        assertEquals(0, uploads)
        assertEquals("owner-confirmed-exact-id", recovered.currentRemoteJobId)
    }

    @Test
    fun onlyDeclaredTargetsWithRemainingArePlayable() {
        assertFalse(app.stemwise.model.StemRoleContract.isPlayable(
            listOf("vocals", "drums", "bass", "guitar", "piano", "other")))
        assertTrue(app.stemwise.model.StemRoleContract.isPlayable(listOf("bass", "remaining")))
        assertFalse(app.stemwise.model.StemRoleContract.isPlayable(listOf("bass", "bass", "remaining")))
        assertEquals(listOf("bass", "remaining"), app.stemwise.model.StemRoleContract
            .orderedPlayableRoles(listOf("remaining", "bass")))
    }

    @Test
    fun recoveredProcessingJobCannotSubmitAgain() = kotlinx.coroutines.runBlocking {
        val fresh = created()
        var submissions = 0
        withRequiredSourceSubmission(fresh) { submissions++ }
        val acknowledged = step(step(fresh, SourceJobTransition.ENTER_SUBMISSION),
            SourceJobTransition.ACKNOWLEDGE_REMOTE, "already-accepted")
        val durable = acknowledged.toBytes()
        repeat(3) {
            val recovered = parsePortableSourceJobAuthority(durable)
            withRequiredSourceSubmission(recovered) { submissions++; error("Recovered accepted job entered upload") }
            assertEquals("already-accepted", recovered.currentRemoteJobId)
            org.junit.Assert.assertArrayEquals(durable, recovered.toBytes())
        }
        assertEquals(1, submissions)
    }

    @Test
    fun replayRunsBeforeSidecarSelectionAndRetainsTheAcknowledgedJob() = kotlinx.coroutines.runBlocking {
        val prior = step(created(), SourceJobTransition.ENTER_SUBMISSION)
        val acknowledged = step(prior, SourceJobTransition.ACKNOWLEDGE_REMOTE, "already-accepted")
        val root = app.stemwise.data.RootAuthority(prior.libraryId, "content://provider/tree/current", 7L)
        val transaction = "source-${acknowledged.jobId}"
        val record = app.stemwise.data.AtomicJournalRecord(java.io.File("$transaction.json"), JSONObject()
            .put("schemaVersion", 2).put("transactionId", transaction).put("packageId", acknowledged.videoId)
            .put("operation", "SOURCE_JOB_AUTHORITY_V1").put("libraryId", root.libraryId)
            .put("treeUri", root.treeUri).put("rootEpoch", root.epoch).put("sequence", 2)
            .put("state", "AUTHORITY_WRITING").put("details", JSONObject()
                .put("authorityJson", acknowledged.toBytes().toString(Charsets.UTF_8))))
        for (cut in listOf(0, 1, acknowledged.toBytes().size / 2)) {
            var canonical = acknowledged.toBytes().copyOf(cut)
            var writes = 0
            replayJournaledSourceAuthorities(root, listOf(record)) { recovered ->
                val selected = selectPortableSourceJobAuthority(canonical, prior.toBytes(), recovered)
                assertEquals(acknowledged, selected)
                canonical = recovered.toBytes()
                writes++
            }
            assertEquals(1, writes)
            val restored = parsePortableSourceJobAuthority(canonical)
            assertEquals("already-accepted", restored.currentRemoteJobId)
            assertFalse(restored.requiresSubmission)
        }
        var writes = 0
        replayJournaledSourceAuthorities(root.copy(epoch = 8L), listOf(record)) { writes++ }
        assertEquals(0, writes)
    }

    @Test
    fun wallClockRollbackCannotLoseAcknowledgementsOrRegressReceiptTime() {
        var authority = created()
        val initialTime = authority.updatedAtEpochMs
        authority = transitionPortableSourceJob(authority, SourceJobTransition.ENTER_SUBMISSION, initialTime + 10_000L)
        authority = transitionPortableSourceJob(authority, SourceJobTransition.ACKNOWLEDGE_REMOTE, initialTime, "clock-remote")
        assertEquals("clock-remote", authority.currentRemoteJobId)
        assertEquals(initialTime + 10_000L, authority.updatedAtEpochMs)
        assertEquals(3L, authority.revision)
        authority = transitionPortableSourceJob(authority, SourceJobTransition.BEGIN_DOWNLOAD, initialTime - 1L)
        assertEquals(initialTime + 10_000L, authority.updatedAtEpochMs)
        assertEquals(authority, parsePortableSourceJobAuthority(authority.toBytes()))
        val forward = transitionPortableSourceJob(created(), SourceJobTransition.ENTER_SUBMISSION,
            initialTime + 20_000L)
        assertEquals(initialTime + 20_000L, forward.updatedAtEpochMs)
    }

    @Test
    fun reconciliationRepairsAFailedProjectionFromExactCompletedAuthorityWithoutResubmission() {
        var authority = created()
        for (id in listOf("bass", "backing-vocals")) {
            authority = step(authority, SourceJobTransition.ENTER_SUBMISSION)
            authority = step(authority, SourceJobTransition.ACKNOWLEDGE_REMOTE, "remote-$id")
            authority = receipt(step(authority, SourceJobTransition.BEGIN_DOWNLOAD), id)
            if (id == "bass") authority = step(authority, SourceJobTransition.ADVANCE_OPERATION)
        }
        authority = receipt(step(authority, SourceJobTransition.BEGIN_ASSEMBLY), "remaining")
        authority = step(step(authority, SourceJobTransition.BEGIN_PUBLICATION), SourceJobTransition.COMPLETE)
        val durable = authority.toBytes()
        var projection = projectSourceJob(authority).copy(state = "FAILED", lastErrorCode = "ROOM_WRITE_FAILED",
            lastErrorMessage = "Synthetic projection failure", nextAttemptAtEpochMs = 123)
        // The portable commit survived the failed Room upsert. Ordinary reconciliation reads it again.
        repeat(3) {
            val recovered = parsePortableSourceJobAuthority(durable)
            projection = projectSourceJob(recovered, projection)
            assertEquals("COMPLETE", projection.state)
            assertEquals(null, projection.lastErrorCode)
            assertEquals(null, projection.lastErrorMessage)
            assertEquals(null, projection.nextAttemptAtEpochMs)
            assertFalse(recovered.requiresSubmission)
            org.junit.Assert.assertArrayEquals(durable, recovered.toBytes())
        }
        val incomplete = created()
        assertEquals("FAILED", projectSourceJob(incomplete, projectSourceJob(incomplete).copy(state = "FAILED")).state)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            projectSourceJob(authority, projection.copy(libraryId = "wrong-root"))
        }
    }

    @Test
    fun obsoleteAuthoritySchemaIsNotASecondRecoveryContract() {
        assertThrows(IllegalArgumentException::class.java) { created().copy(schemaVersion = 1) }
    }

    @Test
    fun frozenPlanSurvivesPrivateDatabaseLossWithTheExactAcknowledgedRemoteJob() {
        val submitted = step(created(), SourceJobTransition.ENTER_SUBMISSION)
        val processing = step(submitted, SourceJobTransition.ACKNOWLEDGE_REMOTE, "remote-one")
        val reopened = parsePortableSourceJobAuthority(processing.toBytes())
        assertEquals(processing, reopened)
        assertEquals(listOf("remote-one", null), reopened.remoteJobIds)
        assertFalse(reopened.requiresSubmission)
        assertEquals("PROCESSING", reopened.state)
        assertEquals(processing.plan, reopened.plan)
        assertEquals(processing.jobId, sourceSeparationJobId(SOURCE_VERSION, SOURCE_SHA, processing.planSha256))
    }

    @Test
    fun unacknowledgedRestartCannotDispatchAgainOrManufactureCompletion() {
        val pending = step(created(), SourceJobTransition.ENTER_SUBMISSION)
        assertFalse(parsePortableSourceJobAuthority(pending.toBytes()).requiresSubmission)
        val unknown = step(pending, SourceJobTransition.MARK_SUBMISSION_UNKNOWN)
        assertEquals("SUBMISSION_UNKNOWN", unknown.state)
        assertNull(unknown.remoteJobIds[0])
        assertThrows(IllegalStateException::class.java) { step(unknown, SourceJobTransition.ENTER_SUBMISSION) }
        assertThrows(IllegalStateException::class.java) { step(unknown, SourceJobTransition.COMPLETE) }
    }

    @Test
    fun nextOperationRetainsAcknowledgedIdsAndFinalAssemblyRequiresEveryOperation() {
        var authority = step(created(), SourceJobTransition.ENTER_SUBMISSION)
        authority = step(authority, SourceJobTransition.ACKNOWLEDGE_REMOTE, "remote-one")
        assertThrows(IllegalStateException::class.java) { step(authority, SourceJobTransition.ADVANCE_OPERATION) }
        authority = step(authority, SourceJobTransition.BEGIN_DOWNLOAD)
        assertThrows(IllegalStateException::class.java) { step(authority, SourceJobTransition.ADVANCE_OPERATION) }
        assertThrows(IllegalStateException::class.java) { step(authority, SourceJobTransition.BEGIN_ASSEMBLY) }
        authority = receipt(authority, "bass")
        authority = step(authority, SourceJobTransition.ADVANCE_OPERATION)
        assertEquals(1, authority.activeOperationIndex)
        assertEquals(listOf("remote-one", null), authority.remoteJobIds)
        assertTrue(authority.requiresSubmission)
        authority = step(authority, SourceJobTransition.ENTER_SUBMISSION)
        authority = step(authority, SourceJobTransition.ACKNOWLEDGE_REMOTE, "remote-two")
        authority = step(authority, SourceJobTransition.BEGIN_DOWNLOAD)
        authority = receipt(authority, "backing-vocals")
        authority = step(authority, SourceJobTransition.BEGIN_ASSEMBLY)
        assertThrows(IllegalStateException::class.java) { step(authority, SourceJobTransition.BEGIN_PUBLICATION) }
        authority = receipt(authority, "remaining")
        authority = step(authority, SourceJobTransition.BEGIN_PUBLICATION)
        authority = step(authority, SourceJobTransition.COMPLETE)
        assertEquals(listOf("remote-one", "remote-two"), authority.remoteJobIds)
        assertEquals(2, authority.attempts)
        assertEquals("COMPLETE", parsePortableSourceJobAuthority(authority.toBytes()).state)
        assertThrows(IllegalStateException::class.java) { step(authority, SourceJobTransition.ENTER_SUBMISSION) }
    }

    @Test
    fun acknowledgementCannotChangeReuseOrSkipRemoteIdentity() {
        val submitted = step(created(), SourceJobTransition.ENTER_SUBMISSION)
        assertThrows(IllegalStateException::class.java) { step(submitted, SourceJobTransition.ACKNOWLEDGE_REMOTE, " ") }
        val acknowledged = step(submitted, SourceJobTransition.ACKNOWLEDGE_REMOTE, "remote-one")
        assertThrows(IllegalStateException::class.java) {
            step(acknowledged, SourceJobTransition.ACKNOWLEDGE_REMOTE, "different")
        }
        val next = step(receipt(step(acknowledged, SourceJobTransition.BEGIN_DOWNLOAD), "bass"), SourceJobTransition.ADVANCE_OPERATION)
        assertThrows(IllegalStateException::class.java) {
            step(step(next, SourceJobTransition.ENTER_SUBMISSION), SourceJobTransition.ACKNOWLEDGE_REMOTE, "remote-one")
        }
    }

    @Test
    fun reservedCopiesAdmitOnlyOneAdjacentAllowedTransition() {
        val before = created()
        val submitted = step(before, SourceJobTransition.ENTER_SUBMISSION)
        val processing = step(submitted, SourceJobTransition.ACKNOWLEDGE_REMOTE, "remote-one")
        assertEquals(submitted, selectPortableSourceJobAuthority(before.toBytes(), submitted.toBytes()))
        assertEquals(processing, selectPortableSourceJobAuthority(processing.toBytes(), processing.toBytes()))
        assertThrows(IllegalStateException::class.java) {
            selectPortableSourceJobAuthority(before.toBytes(), processing.toBytes())
        }
        val foreign = createPortableSourceJobAuthority(before.libraryId, before.videoId, before.sourceVersionId,
            before.sourceSha256, before.plan, before.createdAtEpochMs, before.generation + 1)
        assertEquals(foreign, selectPortableSourceJobAuthority(foreign.toBytes(), null))
        assertThrows(IllegalStateException::class.java) {
            selectPortableSourceJobAuthority(before.toBytes(), foreign.toBytes())
        }
    }

    @Test
    fun truncatedTemporaryCanRecoverButContradictoryWellFormedMetadataCannot() {
        val canonical = created().toBytes()
        assertEquals(created(), selectPortableSourceJobAuthority(canonical, canonical.copyOf(19)))
        val changed = JSONObject(canonical.toString(Charsets.UTF_8)).put("attempts", 4)
        assertThrows(IllegalStateException::class.java) {
            selectPortableSourceJobAuthority(canonical, CanonicalJson.encode(changed))
        }
    }

    @Test
    fun receiptRecoveryRequiresExactMonotonicAssetsAndForbidsOverwriteOrFutureOutputs() {
        val downloading = step(step(step(created(), SourceJobTransition.ENTER_SUBMISSION),
            SourceJobTransition.ACKNOWLEDGE_REMOTE, "remote-one"), SourceJobTransition.BEGIN_DOWNLOAD)
        assertThrows(IllegalStateException::class.java) { receipt(downloading, "backing-vocals") }
        assertThrows(IllegalStateException::class.java) { receipt(downloading, "remaining") }
        val recorded = receipt(downloading, "bass")
        assertEquals(recorded, selectPortableSourceJobAuthority(downloading.toBytes(), recorded.toBytes()))
        assertEquals(recorded, recordPortableSourceAsset(recorded, recorded.assets.getValue("bass"), recorded.updatedAtEpochMs + 1))
        assertThrows(IllegalStateException::class.java) {
            recordPortableSourceAsset(recorded, recorded.assets.getValue("bass").copy(sha256 = "f".repeat(64)), recorded.updatedAtEpochMs + 1)
        }
    }

    @Test
    fun resignedPlanOrStateMutationsCannotBypassKnownConfigurationAndDispatchRules() {
        val invalid: List<(JSONObject) -> Unit> = listOf(
            { it.put("schemaVersion", "1") },
            { it.put("extra", true) },
            { it.put("jobId", "00000000-0000-0000-0000-000000000000") },
            { it.put("activeOperationIndex", 1) },
            { it.put("state", "COMPLETE") },
            { it.getJSONArray("remoteJobIds").put(1, "future-remote") },
            { it.getJSONObject("plan").getJSONArray("operations").getJSONObject(0).put("renderId", 999) },
            { it.getJSONObject("plan").getJSONArray("operations").getJSONObject(1)
                .getJSONObject("options").put("add_opt1", "0") },
            { it.getJSONObject("plan").getJSONArray("operations").getJSONObject(1)
                .getJSONObject("outputBindings").put("backing-vocals", "vocals-lead") }
        )
        invalid.forEach { mutate ->
            val json = JSONObject(created().toBytes().toString(Charsets.UTF_8))
            mutate(json)
            json.remove("integritySha256")
            json.put("integritySha256", CanonicalJson.sha256(json))
            assertThrows(IllegalStateException::class.java) { parsePortableSourceJobAuthority(CanonicalJson.encode(json)) }
        }
    }

    @Test
    fun distinctProviderRolesCanRetainIdenticalAudioWithoutSharingFileIdentity() {
        val firstDownload = step(step(step(created(), SourceJobTransition.ENTER_SUBMISSION),
            SourceJobTransition.ACKNOWLEDGE_REMOTE, "remote-one"), SourceJobTransition.BEGIN_DOWNLOAD)
        val first = receipt(firstDownload, "bass")
        val secondDownload = step(step(step(step(first, SourceJobTransition.ADVANCE_OPERATION),
            SourceJobTransition.ENTER_SUBMISSION), SourceJobTransition.ACKNOWLEDGE_REMOTE, "remote-two"),
            SourceJobTransition.BEGIN_DOWNLOAD)
        val bass = first.assets.getValue("bass")
        val backing = bass.copy(sourceId = "backing-vocals", label = "Backing vocals", fileName = "part-2.flac")
        val both = recordPortableSourceAsset(secondDownload, backing, secondDownload.updatedAtEpochMs + 1)
        assertEquals(both, parsePortableSourceJobAuthority(both.toBytes()))
        assertEquals(setOf("part-1.flac", "part-2.flac"), both.assets.values.map { it.fileName }.toSet())
        assertEquals(setOf(bass.sha256), both.assets.values.map { it.sha256 }.toSet())
        val error = assertThrows(IllegalStateException::class.java) {
            recordPortableSourceAsset(secondDownload, backing.copy(fileName = "part-1.flac"),
                secondDownload.updatedAtEpochMs + 1)
        }
        assertEquals("SOURCE_MANIFEST_INVALID", error.message)
    }

    private fun originalFacts() = app.stemwise.data.OriginalAudioFacts(2, 48_000, "audio/flac", 48_000,
        audibleFramesNative = 48_000, encodedFramesNative = 48_000, preSkipFrames = 0,
        endTrimFrames = 0, initialGranuleOffset = 0)

    private fun created() = createPortableSourceJobAuthority(
        "1e9167b7-fdb3-4a3c-92d4-d23433b4f29e", "AbCdEf_12-3", SOURCE_VERSION, SOURCE_SHA,
        MvsepSourcePlan(listOf("bass", "backing-vocals"), listOf(
            MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), mapOf("bass" to "bass")),
            MvsepSourceOperation("mvsep-49-direct", 49, mapOf("add_opt1" to "6", "add_opt2" to "0"),
                setOf("vocals-lead", "vocals-back", "instrum-only", "back-instrum"),
                mapOf("backing-vocals" to "vocals-back"))
        )), NOW
    )

    private fun step(authority: PortableSourceJobAuthority, transition: SourceJobTransition, remoteId: String? = null) =
        transitionPortableSourceJob(authority, transition, authority.updatedAtEpochMs + 1, remoteId)

    private fun receipt(authority: PortableSourceJobAuthority, id: String, sha256: String? = null): PortableSourceJobAuthority {
        val index = authority.plan.selectedSourceIds.indexOf(id)
        val asset = if (id == "remaining") CustomSourceAsset(id, "Remaining", "remaining.wv", "3".repeat(64), 100L, 48_000L,
            NATIVE_ORIGINAL_48K_REMAINING_PRODUCER) else
            CustomSourceAsset(id, checkNotNull(app.stemwise.model.StemSourceCatalogue.source(id)).label,
                sourcePartFileName(authority.generation, authority.plan.selectedSourceIds, id),
                (index + 1).toString().repeat(64), 100L, 48_000L)
        return recordPortableSourceAsset(authority, if (sha256 == null) asset else asset.copy(sha256 = sha256),
            authority.updatedAtEpochMs + 1)
    }

    companion object {
        private const val LIBRARY_ID = "1e9167b7-fdb3-4a3c-92d4-d23433b4f29e"
        private const val VIDEO_ID = "AbCdEf_12-3"
        private const val NOW = 1_780_000_000_000L
        private val SOURCE_SHA = "1".repeat(64)
        private const val SOURCE_VERSION = "AbCdEf_12-3:original:1111111111111111"
    }
}
