package app.stemwise.data


/**
 * The one durable automatic preparation owner admitted for an exact selected-root session.
 * This value is derived only from Room plus verified selected-root originals; it never contains
 * provider credentials, remote URLs, or filesystem paths.
 */
sealed interface PreparationOwnerDecision {
    val authority: RootAuthority?

    data object Unresolved : PreparationOwnerDecision {
        override val authority: RootAuthority? = null
    }

    data class None(
        override val authority: RootAuthority
    ) : PreparationOwnerDecision

    data class Separation(
        override val authority: RootAuthority,
        val jobId: String,
        val songId: String,
        val videoId: String,
        val state: String,
        val nextAttemptAtEpochMs: Long?,
        val lastErrorCode: String?
    ) : PreparationOwnerDecision

    /** Stable fail-closed projection. [ownerVideoIds] are masked by the UI before display. */
    data class Blocked(
        override val authority: RootAuthority,
        val code: String,
        val ownerVideoIds: List<String>,
        val recoverableJobId: String? = null,
        val recoverySongIds: List<String> = emptyList()
    ) : PreparationOwnerDecision
}

data class PreparationJobFact(
    val jobId: String,
    val songId: String,
    val videoId: String?,
    val sourceVersionId: String?,
    val sourceSongId: String?,
    val sourceSha256: String?,
    val currentSourceVersionId: String?,
    val currentSourceSha256: String?,
    val kind: String,
    val provider: String?,
    val modelId: String?,
    val state: String,
    val libraryId: String?,
    val remoteJobId: String?,
    val nextAttemptAtEpochMs: Long?,
    val lastErrorCode: String?,
    val createdAtEpochMs: Long
)

/** Pure policy used by cold-open and every worker wake. */
internal fun arbitratePreparationOwner(
    authority: RootAuthority,
    jobs: List<PreparationJobFact>,
    sourceGenerations: Map<String, Long>,
    acceptedRemoteIds: Map<String, String> = emptyMap()
): PreparationOwnerDecision {
    val orderedJobs = jobs.sortedWith(
        compareBy<PreparationJobFact> { it.createdAtEpochMs }
            .thenBy { it.jobId }
    )
    if (orderedJobs.isNotEmpty()) {
        val visibleIds = orderedJobs.map { it.videoId ?: it.songId }.distinct().sorted()
        if (orderedJobs.any { it.libraryId != authority.libraryId }) {
            return PreparationOwnerDecision.Blocked(
                authority,
                "PREPARATION_OWNER_LIBRARY_CONFLICT",
                visibleIds
            )
        }
        if (orderedJobs.map { it.jobId }.distinct().size != orderedJobs.size ||
            orderedJobs.map { it.songId }.distinct().size != orderedJobs.size) {
            return PreparationOwnerDecision.Blocked(
                authority,
                "PREPARATION_OWNER_CONFLICT",
                visibleIds
            )
        }
        if (orderedJobs.size > 1) {
            val decisions = orderedJobs.map { arbitratePreparationOwner(authority, listOf(it), sourceGenerations) }
            decisions.filterIsInstance<PreparationOwnerDecision.Blocked>()
                .firstOrNull { it.recoverableJobId == null }?.let { return it }
            val active = orderedJobs.filterNot { it.kind == "SOURCE_SELECTION" && it.state == "CREATED" }
            if (active.size > 1) {
                val accepted = decisions.all { it is PreparationOwnerDecision.Separation } && active.all { job ->
                    !job.remoteJobId.isNullOrBlank() && acceptedRemoteIds[job.jobId] == job.remoteJobId
                }
                if (!accepted || active.map { it.remoteJobId }.distinct().size != active.size) {
                    return PreparationOwnerDecision.Blocked(authority, "PREPARATION_OWNER_CONFLICT", visibleIds)
                }
                // Independently verified accepted jobs can be polled serially without resubmission.
                return arbitratePreparationOwner(authority, listOf(active.first()), sourceGenerations)
            }
            return arbitratePreparationOwner(authority, listOf(active.singleOrNull() ?: orderedJobs.first()), sourceGenerations)
        }
        val job = orderedJobs.single()
        val videoId = job.videoId
            ?.takeIf(SelectedLibraryContract::isVideoId)
            ?: return PreparationOwnerDecision.Blocked(
                authority,
                "PREPARATION_OWNER_IDENTITY_CONFLICT",
                visibleIds
            )
        return when (job.kind) {
            "SOURCE_SELECTION" -> {
                val sourceId = job.sourceVersionId
                val sourceHash = job.sourceSha256
                val generation = sourceGenerations[job.jobId]
                val planHash = job.modelId?.removePrefix("source-plan:")
                val uncertain = job.state == "SUBMISSION_UNKNOWN" ||
                    job.state == "SUBMISSION_STARTED" && job.remoteJobId.isNullOrBlank()
                val remoteExpected = job.state in setOf("PROCESSING", "DOWNLOADING", "ASSEMBLING",
                    "PUBLISHING", "VERIFIED", "PACKAGE_COMMITTED", "PROJECTING")
                val valid = generation != null && generation > 0 && job.provider == "MVSEP" && !sourceId.isNullOrBlank() &&
                    sourceHash?.matches(Regex("[a-f0-9]{64}")) == true &&
                    job.modelId == "source-plan:$planHash" &&
                    planHash?.matches(Regex("[a-f0-9]{64}")) == true &&
                    sourceId == job.currentSourceVersionId && sourceHash == job.currentSourceSha256 &&
                    job.sourceSongId == job.songId && job.jobId ==
                    app.stemwise.integration.sourceSeparationJobId(sourceId, sourceHash, planHash, generation) &&
                    (uncertain || job.state == "CREATED" && job.remoteJobId.isNullOrBlank() ||
                        remoteExpected && !job.remoteJobId.isNullOrBlank())
                when {
                    !valid -> PreparationOwnerDecision.Blocked(authority,
                        "PREPARATION_OWNER_IDENTITY_CONFLICT", listOf(videoId))
                    uncertain -> PreparationOwnerDecision.Blocked(authority,
                        "PREPARATION_SUBMISSION_UNKNOWN", listOf(videoId), job.jobId)
                    else -> PreparationOwnerDecision.Separation(authority, job.jobId, job.songId,
                        videoId, job.state, job.nextAttemptAtEpochMs, job.lastErrorCode)
                }
            }
            else -> PreparationOwnerDecision.Blocked(
                authority,
                "PREPARATION_OWNER_IDENTITY_CONFLICT",
                listOf(videoId)
            )
        }
    }

    return PreparationOwnerDecision.None(authority)
}

internal fun PreparationOwnerDecision.matches(authority: RootAuthority): Boolean =
    this.authority == authority

internal fun preparationBlocksRoot(decision: PreparationOwnerDecision): Boolean =
    decision is PreparationOwnerDecision.Blocked && decision.code != "PREPARATION_SUBMISSION_UNKNOWN"
