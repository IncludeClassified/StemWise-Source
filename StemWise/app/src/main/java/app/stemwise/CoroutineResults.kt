package app.stemwise

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Result wrapper for suspend work. Unlike Kotlin's runCatching, cancellation
 * remains control flow and is never converted into an application failure.
 */
suspend inline fun <T> suspendResult(
    crossinline block: suspend () -> T
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

/** Owned background launch: failure publication belongs to its caller, cancellation stays control flow. */
internal fun kotlinx.coroutines.CoroutineScope.launchRecoverable(
    onFailure: suspend (Throwable) -> Unit,
    block: suspend () -> Unit
): kotlinx.coroutines.Job = launch {
    suspendResult(block).exceptionOrNull()?.let { onFailure(it) }
}
