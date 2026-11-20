package app.stemwise.playback

import app.stemwise.data.RootAuthority
import app.stemwise.playback.engine.*
import org.json.JSONArray
import org.json.JSONObject

/** A user-requested immutable observation, never an owner or an always-on archive. */
internal data class PlaybackEvidenceSnapshot(
    val capturedAtEpochMs: Long,
    val capturedAtElapsedMs: Long,
    val root: RootAuthority?,
    val selectionOperationSerial: Long,
    val session: NativeAudioPlaybackSessionState,
    val engineEvidenceAvailable: Boolean,
    val nativeOwnership: NativeRuntimeOwnershipProgress?,
    val preparationStages: List<NativeControllerPreparationStageDiagnostics>,
    val endOrdering: NativeRuntimeEndOrderingDiagnostics?,
    val eventTail: List<String>,
    val stemPreparation: StemPreparationDiagnostics,
    val canonicalPcmIo: NativeRuntimeCanonicalPcmIoDiagnostics? = null
) {
    fun toJson(): String {
        val committed = session.publication.committed
        val requested = session.latestRequest
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("capturedAtEpochMs", capturedAtEpochMs)
            put("capturedAtElapsedMs", capturedAtElapsedMs)
            put("observation", "Session, actor and native snapshots have distinct clocks; requested intent is not audible ownership.")
            put("root", root?.let { JSONObject().put("libraryId", it.libraryId).put("treeUri", it.treeUri).put("epoch", it.epoch) }.orUnknown())
            put("selectionOperationSerial", selectionOperationSerial)
            put("sessionLifecycle", session.lifecycle.name)
            put("requested", requested?.let { selectionJson(it.selection, it.exactOwnership)
                .put("playWhenReady", it.controls.playWhenReady)
                .put("requestedPositionMs", it.controls.requestedPositionMs) }.orUnknown())
            put("committed", committed?.let { selectionJson(it.selection, it.owner.ownership)
                .put("playWhenReady", it.transport.playWhenReady)
                .put("presentedPositionMs", it.transport.presentedPositionMs)
                .put("callbackSequence", it.transport.positionCallbackSequence) }.orUnknown())
            put("terminalCause", session.terminalFailure?.take(1_024).orUnknown())
            put("pendingCause", session.publication.pendingSelection?.issue?.cause?.take(1_024).orUnknown())
            put("committedCause", committed?.issue?.cause?.take(1_024).orUnknown())
            put("engineEvidenceAvailable", engineEvidenceAvailable)
            put("nativeOwnership", nativeOwnership?.let { JSONObject()
                .put("requested", ownershipJson(it.requested).orUnknown())
                .put("accepted", ownershipJson(it.accepted).orUnknown())
                .put("committed", ownershipJson(it.committed).orUnknown()) }.orUnknown())
            put("preparationClock", "controller scheduler monotonic milliseconds; independent of capture elapsed time")
            put("preparationStageHistoryComplete", false)
            put("preparationStages", JSONArray().apply {
                preparationStages.takeLast(32).forEach { stage -> put(JSONObject()
                    .put("sequence", stage.sequence)
                    .put("ownership", ownershipJson(stage.ownership).orUnknown())
                    .put("operation", stage.operation.name)
                    .put("stage", stage.stage.take(128))
                    .put("startedAtMonotonicMs", stage.startedAtMs)
                    .put("elapsedMs", stage.elapsedMs)
                    .put("cause", stage.cause?.take(1_024).orUnknown())) }
            })
            put("nativeEndOrdering", endOrdering?.let { ordering -> JSONObject()
                .put("completeHistory", ordering.completeHistory)
                .put("scope", "latest observation of each kind in one native monotonic order")
                .put("missingKinds", JSONArray(ordering.missingKinds.map { it.name }))
                .put("events", JSONArray().apply { ordering.events.forEach { event -> put(JSONObject()
                    .put("kind", event.kind.name).put("sequence", event.sequence)
                    .put("workerStateSequence", event.workerStateSequence).put("commandRevision", event.commandRevision)
                    .put("ownership", ownershipJson(event.ownership).orUnknown())
                    .put("desiredPlaying", event.desiredPlaying).put("outputPlaying", event.outputPlaying)
                    .put("pipelineEndObserved", event.pipelineEndObserved).put("commandUnread", event.commandUnread)
                    .put("pendingOutputFrames", event.pendingOutputFrames).put("queuedOutputFrames", event.queuedOutputFrames)
                    .put("terminalPositionValid", event.terminalPositionValid).put("terminalAfterFrame", event.terminalAfterFrame)) } })
            }.orUnknown())
            put("canonicalPcmIo", canonicalPcmIo?.let { io -> JSONObject()
                .put("ownership", ownershipJson(io.ownership))
                .put("songIdentityHash", io.songIdentityHash).put("originalContentHash", io.originalContentHash)
                .put("stemPackageHash", io.stemPackageHash)
                .put("readOperations", io.readOperations).put("readBytes", io.readBytes)
                .put("lastReadByteOffset", io.lastReadByteOffset).put("lastReadBytes", io.lastReadBytes)
                .put("fileBytes", io.fileBytes).put("reservedBytes", io.reservedBytes).put("peakFileBytes", io.peakFileBytes)
                .put("admittedLaneCount", io.admittedLaneCount).put("admittedFrameUpperBound", io.admittedFrameUpperBound)
                .put("lastAdmissionStatus", io.lastAdmissionStatus.name)
            }.orUnknown())
            put("eventTail", JSONArray(eventTail.takeLast(64).map { it.take(2_048) }))
            put("stemPreparation", JSONObject()
                .put("currentPrewarmBuildCount", stemPreparation.currentPrewarmBuildCount)
                .put("foregroundBuildCount", stemPreparation.foregroundBuildCount)
                .put("joinedPrewarmCount", stemPreparation.joinedPrewarmCount)
                .put("lastActivationTotalMs", stemPreparation.lastActivationTotalMs))
        }.toString(2)
    }
}

private fun Any?.orUnknown(): Any = this ?: JSONObject.NULL
private fun ownershipJson(owner: NativeRuntimeOwnership?): JSONObject? = owner?.let {
    JSONObject().put("selectionToken", it.selectionToken).put("generation", it.generation)
}
private fun selectionJson(selection: EngineSelection, owner: NativeRuntimeOwnership?): JSONObject = JSONObject()
    .put("songId", selection.songId).put("audioRevision", selection.audioRevision)
    .put("selectionToken", selection.token.value).put("durationMs", selection.durationMs)
    .put("ownership", ownershipJson(owner).orUnknown())
