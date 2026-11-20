package app.stemwise.playback.engine

import app.stemwise.playback.sourceWaveformIdentityCandidate
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
internal class SourceWaveformAuthorityTest : NativePlaybackSessionTestSupport() {
    private fun configured(autoplay: Boolean = true, originalHash: String? = null): Harness {
        val harness = Harness()
        val token = requireNotNull(harness.adapter.trySelect(
            request("waveform", autoplay).copy(originalContentSha256 = originalHash), metadata("waveform", 1L)))
        harness.engine.publishOwner(token, 700L, 1L)
        installStemPackage(harness, token, stems("waveform"), "parts-r1")
        return harness
    }

    private fun native(session: NativeAudioPlaybackSessionState): NativeEngineDiagnosticsSnapshot {
        val committed = requireNotNull(session.publication.committed)
        val owner = committed.owner.ownership
        val requested = requireNotNull(session.latestRequest).controls
        val lanes = requireNotNull(canonicalEngineStemLanes(requested.stemLanes))
        val signature = engineStemPackageSignature(lanes)
        val controls = NativeRuntimeControls(1L, sourceMode = NativeRuntimeSourceMode.STEMS,
            stems = List(NativeRuntimeControls.STEM_COUNT) { index ->
                val control = lanes.getOrNull(index)?.id?.let(requested.stemControls::get)
                NativeRuntimeStemControl(control?.gain ?: 0f, control?.muted ?: true, control?.solo ?: false)
            })
        val initial = NativeRuntimeDiagnosticsCodec.decode(LongArray(NativeRuntimeDiagnosticsCodec.VALUE_COUNT).apply {
            this[0] = NativeRuntimeDiagnosticsCodec.SCHEMA_VERSION
        })
        val runtime = initial.copy(ownership = NativeRuntimeOwnershipProgress(owner, owner, owner, 1L),
            audibleControl = NativeRuntimeAudibleControlEvidence(1L, owner, 1L,
                NativeRuntimeSourceMode.STEMS, 1L, 1L, 1L, 240L))
        return NativeEngineDiagnosticsSnapshot(runtime, null,
            controllerSourceControlPublications = listOf(NativeSourceControlPublicationDiagnostics(
                1L, owner, owner.selectionToken, 1L, EngineSourceMode.STEMS, NativeRuntimeSourceMode.STEMS,
                signature, "parts-r1", 1L, controls, lanes.map(EngineStemLane::id))))
    }

    @Test fun actualOwnerSourcePackageAndNewestCallbackAreAllRequired() {
        val originalHash = "1".repeat(64)
        val harness = configured(originalHash = originalHash)
        val session = harness.adapter.snapshot()
        val native = native(session)
        val identity = requireNotNull(sourceWaveformIdentityCandidate(session, native, null))
        assertEquals(originalHash, identity.sources.first().contentIdentity)
        assertEquals(6, identity.stemGains.size)
        assertTrue(identity.stemGains.all { it == 1f })
        assertNull(sourceWaveformIdentityCandidate(session, native.copy(runtime = native.runtime.copy(
            audibleControl = requireNotNull(native.runtime.audibleControl).copy(actualSourceMode = NativeRuntimeSourceMode.ORIGINAL))), null))
        assertNull(sourceWaveformIdentityCandidate(session, native.copy(controllerSourceControlPublications =
            native.controllerSourceControlPublications.map { it.copy(stemPackageSignature = "foreign-package") }), null))
        assertNull(sourceWaveformIdentityCandidate(session, native.copy(controllerSourceControlPublications =
            native.controllerSourceControlPublications + native.controllerSourceControlPublications.single().copy(nativeControlRevision = 2L)), null))
        assertNull(sourceWaveformIdentityCandidate(session, native.copy(runtime = native.runtime.copy(
            ownership = native.runtime.ownership.copy(requested = NativeRuntimeOwnership(identity.ownership.selectionToken, 701L)))), null))
    }

    @Test fun acknowledgedMixSurvivesPauseButNewRequestedGainsCannotBorrowItsShape() {
        val harness = configured()
        val before = harness.adapter.snapshot()
        val ack = native(before)
        val first = sourceWaveformIdentityCandidate(before, ack, null)
        assertNotNull(first)
        assertTrue(harness.adapter.pause())
        assertEquals(first, sourceWaveformIdentityCandidate(harness.adapter.snapshot(), ack, null))
        val current = harness.adapter.snapshot()
        val desired = requireNotNull(current.latestRequest)
        val firstLane = desired.controls.stemLanes.first().id
        val changed = current.copy(latestRequest = desired.copy(controls = desired.controls.copy(
            stemControls = desired.controls.stemControls + (firstLane to EngineStemControl(.5f)))))
        assertNull(sourceWaveformIdentityCandidate(changed, ack, null))
        val changedIdentity = requireNotNull(sourceWaveformIdentityCandidate(changed, native(changed), null))
        assertNotEquals(first, changedIdentity)
        assertTrue(changedIdentity.stemGains.contains(.5f))
    }

    @Test fun steadyCallbacksAdvancePositionWithoutRepublishingAnUnchangedControlTuple() {
        val harness = configured()
        val session = harness.adapter.snapshot()
        val ack = native(session)
        val first = requireNotNull(sourceWaveformIdentityCandidate(session, ack, null))
        val later = ack.copy(runtime = ack.runtime.copy(presentedPosition = NativeRuntimePresentedPosition(
            100L, first.ownership, 100L, 48_000L, 48_000L, 100L, 240L)))
        assertEquals(1L, later.runtime.audibleControl?.callbackSequence)
        assertEquals(first, sourceWaveformIdentityCandidate(session, later, null))
        assertTrue(harness.adapter.pause())
        assertEquals(first, sourceWaveformIdentityCandidate(harness.adapter.snapshot(), later, null))
    }

    @Test fun exactPausedPreparedLeaseProvidesSilentPreviewAuthority() {
        val harness = Harness()
        val token = requireNotNull(harness.adapter.trySelect(request("waveform", false), metadata("waveform", 1L)))
        harness.engine.publishOwner(token, 700L, 1L)
        val command = requireNotNull(harness.adapter.acquireCommandLease())
        assertTrue(harness.adapter.configureStemPackageIfCurrent(command, "parts-r1", stems("waveform"), true))
        val committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        val request = requireNotNull(committed.stems.desired)
        harness.engine.emit(NativeEngineControllerEvent.SourceModeReadyAwaitingAudible(token,
            committed.source.desiredRevision, EngineSourceMode.STEMS))
        harness.engine.emit(NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(token,
            request.packageSignature, request.stemRevision, request.requestRevision, false))
        val session = harness.adapter.snapshot()
        val lease = requireNotNull(harness.adapter.capturePausedPreparedStemMixLease(command))
        val pending = native(session).let { it.copy(runtime = it.runtime.copy(audibleControl = null)) }
        assertNull(sourceWaveformIdentityCandidate(session, pending, null))
        assertNotNull(sourceWaveformIdentityCandidate(session, pending, lease))
        val independent = requireNotNull(sourceWaveformIdentityCandidate(session, pending, lease))
        assertEquals(lease.ownership, independent.ownership)
        assertEquals(session.latestRequest?.selection?.originalLocation, independent.sources.first().location)
        assertEquals(independent.stemGains.size + 1, independent.sources.size)
        assertEquals(1, independent.sources.count { it.directFloat32Audio })
        assertTrue(independent.sources.all { it.contentIdentity.isNotBlank() })
    }
}
