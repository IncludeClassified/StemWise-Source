package app.stemwise.bridge

data class BridgeUiState(
    val connection: BridgeConnectionProfile? = null,
    val loading: Boolean = false,
    val enrollmentRequired: Boolean = false,
    val loginUrl: String? = null,
    val piStatus: String = "Unknown",
    val pcStatus: String = "Unknown",
    val observedAtElapsedMs: Long? = null,
    val validForMs: Long = 0,
    val error: String? = null,
    val connectionIssue: String? = null
)

enum class BridgeSongPhase {
    DRAFT, PREPARING, WAKING, WAITING_PC, REVIEW_REQUIRED, QUEUED, SUBMITTING, RUNNING, WAITING, RETRYING, RECEIVING, IMPORTING,
    ACKNOWLEDGING, IMPORTED, CANCELLING, CANCELLED, FAILED, EXPIRED, REMOVED, RETIRING,
    OUTBOX_DAMAGED, OUTBOX_UNKNOWN
}

data class BridgeSongTask(
    val id: String,
    val videoId: String,
    val title: String,
    val phase: BridgeSongPhase,
    val stage: String,
    val message: String? = null,
    val chatId: String? = null,
    val chatName: String? = null,
    val createdAtEpochMs: Long = 0,
    val retryable: Boolean = false,
    val cancelRequested: Boolean = false,
    val sourceUrl: String = "",
    val locallyImported: Boolean = false,
    val localImportPending: Boolean = false,
    val sendPreparationPending: Boolean = false,
    val canRetryWake: Boolean = false,
    val approvedSubmission: BridgeSongSubmission? = null,
    val connection: BridgeConnectionProfile? = null,
    val remoteCompletionKnown: Boolean = false,
    val canReplaceReviewedRequest: Boolean = false
) {
    val state: String get() = phase.name
    val importSucceeded: Boolean get() = locallyImported && phase != BridgeSongPhase.REMOVED
    val deliveryPending: Boolean get() = locallyImported && !terminal
    val deliveryUnavailable: Boolean get() = locallyImported && phase == BridgeSongPhase.EXPIRED
    val isDraft: Boolean get() = phase == BridgeSongPhase.DRAFT
    val terminal: Boolean get() = phase in setOf(BridgeSongPhase.IMPORTED, BridgeSongPhase.FAILED,
        BridgeSongPhase.CANCELLED, BridgeSongPhase.EXPIRED, BridgeSongPhase.REMOVED, BridgeSongPhase.RETIRING,
        BridgeSongPhase.OUTBOX_DAMAGED, BridgeSongPhase.OUTBOX_UNKNOWN)
    val canRetry: Boolean get() = retryable && phase in setOf(BridgeSongPhase.WAITING,
        BridgeSongPhase.WAITING_PC, BridgeSongPhase.RETRYING, BridgeSongPhase.RETIRING, BridgeSongPhase.CANCELLING,
        BridgeSongPhase.ACKNOWLEDGING)
    val canCancel: Boolean get() = !isDraft && !terminal && !cancelRequested && !locallyImported && !localImportPending && !remoteCompletionKnown && phase !in setOf(BridgeSongPhase.RECEIVING, BridgeSongPhase.IMPORTING, BridgeSongPhase.ACKNOWLEDGING)
    val canForget: Boolean get() = phase in setOf(BridgeSongPhase.DRAFT, BridgeSongPhase.FAILED,
        BridgeSongPhase.CANCELLED, BridgeSongPhase.EXPIRED, BridgeSongPhase.REMOVED, BridgeSongPhase.OUTBOX_DAMAGED)
}
