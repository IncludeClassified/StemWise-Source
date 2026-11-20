package app.stemwise.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

data class PermanentDeletionOutcome(
    val projectionRefreshPending: Boolean
)

internal suspend fun completePermanentDeletion(
    songWorkCoordinator: SongWorkCoordinator,
    packageId: String,
    deleteCanonicalPackage: suspend (onCommitted: () -> Unit) -> Result<Unit>,
    removeCommittedProjection: suspend () -> Unit
): Result<PermanentDeletionOutcome> = withContext(Dispatchers.IO) {
    songWorkCoordinator.withSong(packageId) {
        songWorkCoordinator.preparationLane.withLock {
            var committed = false
            var cancellation: CancellationException? = null
            val deletionError = try {
                deleteCanonicalPackage { committed = true }.exceptionOrNull().also {
                    if (it is CancellationException) throw it
                }
            } catch (cancelled: CancellationException) {
                if (!committed) throw cancelled
                cancellation = cancelled
                null
            }
            if (!committed) return@withLock Result.failure(
                deletionError ?: IllegalStateException("DELETE_COMMIT_UNCONFIRMED"))

            val projectionError = withContext(NonCancellable) {
                runCatching { removeCommittedProjection() }.exceptionOrNull()
            }
            cancellation?.let { cancelled ->
                projectionError?.let(cancelled::addSuppressed)
                throw cancelled
            }
            if (deletionError != null) {
                projectionError?.let(deletionError::addSuppressed)
                return@withLock Result.failure(deletionError)
            }
            Result.success(
                PermanentDeletionOutcome(
                    projectionRefreshPending = projectionError != null
                )
            )
        }
    }
}

/** Owns the post-confirmation boundary so Activity recreation cannot hide a committed delete. */
class PermanentDeletionCoordinator(
    private val trash: ExternalPackageTrash,
    private val externalLibrary: ExternalLibraryRepository,
    private val songWorkCoordinator: SongWorkCoordinator
) {
    suspend fun deleteStems(
        handle: RootHandle,
        packageId: String,
        onExactRootAdmitted: suspend () -> Unit = {}
    ): Result<PermanentDeletionOutcome> = completePermanentDeletion(
        songWorkCoordinator = songWorkCoordinator,
        packageId = packageId,
        deleteCanonicalPackage = { onCommitted ->
            trash.deleteStems(handle, packageId, onCommitted, onExactRootAdmitted)
        },
        removeCommittedProjection = {
            externalLibrary.reconcilePackage(handle, packageId)
        }
    )

    suspend fun deletePermanently(
        handle: RootHandle,
        packageId: String,
        onExactRootAdmitted: suspend () -> Unit = {}
    ): Result<PermanentDeletionOutcome> = completePermanentDeletion(
        songWorkCoordinator = songWorkCoordinator,
        packageId = packageId,
        deleteCanonicalPackage = { onCommitted ->
            trash.deletePermanently(handle, packageId, onCommitted, onExactRootAdmitted)
        },
        removeCommittedProjection = {
            externalLibrary.removeDeletedPackage(handle, packageId)
        }
    )
}
