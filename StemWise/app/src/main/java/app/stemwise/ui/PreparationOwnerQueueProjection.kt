package app.stemwise.ui

import app.stemwise.data.PreparationOwnerDecision
import app.stemwise.data.RootAuthority
import app.stemwise.data.ColdOpenState
import app.stemwise.model.JobKind
import app.stemwise.model.JobState
import app.stemwise.model.QueueJobAction
import app.stemwise.model.QueueOperationPhase
import app.stemwise.model.QueueJob
import app.stemwise.model.Song
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.PrimaryPurple

internal fun projectPreparationRecoveryQueueJob(state: ColdOpenState, root: RootAuthority?): QueueJob? {
    val ready = state as? ColdOpenState.Ready ?: return null
    val diagnosis = ready.preparationRecovery ?: return null
    if (!ready.preparationBlocked || root == null || ready.libraryId != root.libraryId ||
        ready.treeUri != root.treeUri || ready.rootEpoch != root.epoch) return null
    return QueueJob(id = "preparation-retirement-recovery", songTitle = "Preparation needs recovery",
        artist = "Library preparation", kind = JobKind.LIBRARY, state = JobState.NEEDS_ATTENTION,
        progress = 0f, detail = diagnosis.message, stableCode = diagnosis.code, accent = Coral,
        action = QueueJobAction.RETRY)
}

/** A blocked aggregate is the sole Tasks representation of conflicting automatic owner rows. */
internal fun suppressUnderlyingAutomaticPreparationRows(
    owner: PreparationOwnerDecision,
    currentAuthority: RootAuthority?
): Boolean = owner is PreparationOwnerDecision.Blocked && owner.authority == currentAuthority

/** Nonsecret Tasks projection of the exact owner admitted by cold-open and the workers. */
internal fun projectPreparationOwnerQueueJob(
    owner: PreparationOwnerDecision,
    currentAuthority: RootAuthority?,
    songsById: Map<String, Song>
): QueueJob? {
    if (currentAuthority == null || owner.authority != currentAuthority) return null

    fun identity(songId: String, videoId: String): Pair<String, String> {
        val song = songsById[songId]
        val masked = videoId.take(8).ifBlank { "unknown" }
        return (song?.title ?: "Song $masked") to
            (song?.artist ?: "Automatic preparation · $masked")
    }

    return when (owner) {
        PreparationOwnerDecision.Unresolved,
        is PreparationOwnerDecision.None -> null
        is PreparationOwnerDecision.Separation -> {
            val (title, artist) = identity(owner.songId, owner.videoId)
            val phase = preparationQueuePhase(owner.state,
                nextAttemptAtEpochMs = owner.nextAttemptAtEpochMs)
            QueueJob(
                id = owner.jobId,
                songTitle = title,
                artist = artist,
                kind = JobKind.SEPARATION,
                state = phase.state,
                progress = phase.progress,
                detail = phase.detail,
                accent = songsById[owner.songId]?.accent ?: PrimaryPurple,
                stableCode = owner.lastErrorCode,
                automaticPreparation = true
            )
        }
        is PreparationOwnerDecision.Blocked -> QueueJob(
            id = owner.recoverableJobId ?: "preparation-owner-block:${owner.code}",
            songTitle = "Automatic preparation paused",
            artist = owner.ownerVideoIds
                .map { it.take(8).ifBlank { "unknown" } }
                .distinct()
                .joinToString(prefix = "Owners · "),
            kind = if (owner.recoverableJobId != null) JobKind.SEPARATION else JobKind.LIBRARY,
            state = JobState.NEEDS_ATTENTION,
            progress = 0f,
            detail = "${owner.code} · retained owner state needs attention",
            accent = Coral,
            action = if (owner.recoverableJobId != null || owner.recoverySongIds.isNotEmpty())
                QueueJobAction.VIEW_DETAILS else null,
            stableCode = owner.code
        )
    }
}

internal fun projectPersistedPreparationQueueJob(
    job: app.stemwise.data.ProcessingJobEntity,
    songsById: Map<String, Song>,
    exactPreparationJobId: String?,
    suppressAutomaticPreparationRows: Boolean
): QueueJob? {
    if (suppressAutomaticPreparationRows) return null
    if (job.kind != app.stemwise.integration.SeparationRepository.KIND_SOURCES) {
        return null
    }
    if (job.id == exactPreparationJobId) return null
    val queueSong = songsById[job.songId]
    val maskedVideoId = queueSong?.videoId?.take(8)
        ?: job.songId.take(8).ifBlank { "unknown" }
    val phase = preparationQueuePhase(job.state, job.lastErrorMessage, job.nextAttemptAtEpochMs)
    return QueueJob(
        id = job.id,
        songTitle = queueSong?.title ?: "Song $maskedVideoId",
        artist = queueSong?.artist ?: "Automatic preparation · $maskedVideoId",
        kind = JobKind.SEPARATION,
        state = phase.state,
        progress = phase.progress,
        detail = phase.detail,
        accent = queueSong?.accent ?: PrimaryPurple,
        action = when (job.state) {
            "SUBMISSION_UNKNOWN" -> QueueJobAction.VIEW_DETAILS
            "FAILED" -> if (job.lastErrorCode == "MVSEP_REMOTE_FAILED") {
                QueueJobAction.DISCARD_FAILED_PREPARATION
            } else if (
                job.lastErrorCode in setOf("APP_STORAGE_LOW", "LIBRARY_STORAGE_LOW", "PRIVATE_CLEANUP_FAILED", "PREPARATION_SCRATCH_PEAK_EXCEEDED")
            ) {
                QueueJobAction.CLEAN_AND_RETRY
            } else {
                QueueJobAction.RETRY
            }
            else -> null
        },
        phase = when {
            job.nextAttemptAtEpochMs != null -> QueueOperationPhase.RETRYING
            job.state in setOf("VERIFIED", "PUBLISHING", "PACKAGE_COMMITTED", "PROJECTING") -> QueueOperationPhase.VERIFYING
            else -> QueueOperationPhase.PROCESSING
        },
        stableCode = job.lastErrorCode,
        automaticPreparation = true
    )
}

internal data class PreparationQueuePhase(val state: JobState, val progress: Float, val detail: String)

internal fun preparationQueuePhase(
    state: String,
    lastErrorMessage: String? = null,
    nextAttemptAtEpochMs: Long? = null
): PreparationQueuePhase = when (state) {
    "CREATED" -> PreparationQueuePhase(JobState.WAITING, 0f,
        if (nextAttemptAtEpochMs != null) "Waiting in MVSEP's no-spend queue" else "Waiting for MVSEP")
    "SUBMISSION_STARTED" -> PreparationQueuePhase(JobState.RUNNING, .15f, "Sending source audio")
    "REMOTE_JOB_CREATED" -> PreparationQueuePhase(JobState.RUNNING, .3f, "Accepted by MVSEP")
    "PROCESSING" -> PreparationQueuePhase(JobState.RUNNING, .55f, lastErrorMessage ?: "Separating stems")
    "DOWNLOADING" -> PreparationQueuePhase(JobState.RUNNING, .7f, "Downloading selected parts")
    "ASSEMBLING" -> PreparationQueuePhase(JobState.RUNNING, .8f, "Assembling remaining audio")
    "VERIFIED", "PUBLISHING", "PACKAGE_COMMITTED", "PROJECTING" ->
        PreparationQueuePhase(JobState.RUNNING, .85f, "Saving the verified stem package")
    "FAILED" -> PreparationQueuePhase(JobState.NEEDS_ATTENTION, 0f, lastErrorMessage ?: "This task needs attention")
    "SUBMISSION_UNKNOWN" -> PreparationQueuePhase(JobState.NEEDS_ATTENTION, 0f,
        lastErrorMessage ?: "MVSEP submission outcome is unknown")
    else -> PreparationQueuePhase(JobState.WAITING, 0f, "Preparing stem separation")
}
