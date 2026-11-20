package app.stemwise.integration

import app.stemwise.data.RootAuthority

data class SeparationRecoveryReview(
    val root: RootAuthority,
    val jobId: String,
    val remoteJobId: String,
    val sourceVersionId: String,
    val sourceSha256: String,
    val generation: Long,
    val revision: Long,
    val operationIndex: Int,
    val planSha256: String,
    val songTitle: String,
    val sourceName: String,
    val configuration: String,
    val providerSourceName: String?,
    val providerModelName: String?
)

internal fun requireRecoverableSourceJob(authority: PortableSourceJobAuthority, jobId: String) {
    check(authority.jobId == jobId && authority.state in setOf("SUBMISSION_STARTED", "SUBMISSION_UNKNOWN")) {
        "This request does not need remote-job recovery"
    }
}


internal fun requireRecoveryReviewMatches(authority: PortableSourceJobAuthority, review: SeparationRecoveryReview) {
    check(authority.sourceVersionId == review.sourceVersionId && authority.sourceSha256 == review.sourceSha256 &&
        authority.generation == review.generation && authority.revision == review.revision &&
        authority.activeOperationIndex == review.operationIndex && authority.planSha256 == review.planSha256) {
        "SOURCE_RECOVERY_REVIEW_CHANGED"
    }
}
