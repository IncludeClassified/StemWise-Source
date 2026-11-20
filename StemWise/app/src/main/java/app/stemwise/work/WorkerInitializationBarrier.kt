package app.stemwise.work

import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import app.stemwise.data.RootHandle

internal data class RootWorkIdentity(val libraryId: String, val treeUri: String, val epoch: Long) {
    val treeUriIdentity: String get() = workRootTreeUriIdentity(treeUri)
}

internal fun RootHandle.toWorkIdentity() = RootWorkIdentity(libraryId, treeUri.toString(), epoch)

/** Process-local barrier between Application root recovery and eager WorkManager execution. */
internal class WorkerInitializationBarrier {
    private val completion = CompletableDeferred<Unit>()

    suspend fun await() = completion.await()

    fun complete() {
        check(completion.complete(Unit)) { "Worker initialization was already completed" }
    }
}

internal data class WorkerRootExpectation(
    val libraryId: String,
    val treeUriIdentity: String? = null,
    val epoch: Long? = null
) {
    val valid: Boolean
        get() = libraryId.isNotBlank() && (
            (treeUriIdentity == null && epoch == null) ||
                (treeUriIdentity?.matches(SHA256) == true && epoch != null && epoch > 0L)
            )

    fun matches(identity: RootWorkIdentity): Boolean =
        identity.libraryId == libraryId &&
            (treeUriIdentity == null || identity.treeUriIdentity == treeUriIdentity) &&
            (epoch == null || identity.epoch == epoch)

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

internal fun workRootTreeUriIdentity(treeUri: String): String {
    require(treeUri.isNotBlank()) { "ROOT_TREE_URI_INVALID" }
    return MessageDigest.getInstance("SHA-256")
        .digest(treeUri.toByteArray(Charsets.UTF_8))
        .toHexString()
}

internal enum class WorkerRootAdmission { RUN, RETRY, COMPLETE }

private val TRANSIENT_ROOT_ERRORS = setOf("ROOT_CHECKING", "ROOT_RESTORING")

/** Classifies a post-initialization root snapshot without treating ambiguity as no work. */
internal fun workerRootAdmission(
    expected: WorkerRootExpectation,
    liveRoot: RootWorkIdentity?,
    configuredLibraryId: String?,
    configuredTreeUri: String?,
    configuredEpoch: Long,
    configuredErrorCode: String?
): WorkerRootAdmission {
    if (!expected.valid) return WorkerRootAdmission.COMPLETE
    if (liveRoot != null) {
        return if (expected.matches(liveRoot)) {
            WorkerRootAdmission.RUN
        } else {
            WorkerRootAdmission.COMPLETE
        }
    }
    if (configuredErrorCode != null && configuredErrorCode !in TRANSIENT_ROOT_ERRORS) {
        return WorkerRootAdmission.COMPLETE
    }
    val configured = if (
        !configuredLibraryId.isNullOrBlank() &&
        !configuredTreeUri.isNullOrBlank() &&
        configuredEpoch > 0L
    ) {
        RootWorkIdentity(configuredLibraryId, configuredTreeUri, configuredEpoch)
    } else {
        null
    }
    if (configured != null) {
        return if (expected.matches(configured)) {
            WorkerRootAdmission.RETRY
        } else {
            WorkerRootAdmission.COMPLETE
        }
    }
    val provesNoConfiguredRoot = configuredLibraryId == null &&
        configuredTreeUri == null && configuredEpoch <= 0L && configuredErrorCode == null
    return if (provesNoConfiguredRoot) {
        WorkerRootAdmission.COMPLETE
    } else {
        WorkerRootAdmission.RETRY
    }
}
