package app.stemwise.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class CanonicalScanInvalidated : IllegalStateException("LIBRARY_SCAN_INVALIDATED")

/**
 * Orders the two storage traversal classes without weakening canonical verification.
 *
 * A canonical reconciliation is a strict superset of the dashboard metadata scan, so a manual
 * request that arrives after canonical admission can mirror that exact result. The reverse is not
 * valid: canonical work admitted after a metadata-only request must still run its richer package
 * validation. The monitor makes that direction decision atomic without holding a coroutine lock
 * while either provider traversal runs.
 */
internal class LibraryStorageTraversalCoordinator {
    private data class CanonicalAdmission(
        val flight: CompletableDeferred<Result<LibraryInventory>>,
        val owner: Boolean
    )

    private val admissionMutex = Any()
    private var activeCanonicalFlight: CompletableDeferred<Result<LibraryInventory>>? = null
    private var invalidationGeneration = 0L

    fun invalidateCanonical(onInvalidate: () -> Unit = {}) = synchronized(admissionMutex) {
        invalidationGeneration++
        onInvalidate()
    }

    fun requireCurrent(generation: Long) = synchronized(admissionMutex) {
        if (generation != invalidationGeneration) throw CanonicalScanInvalidated()
    }

    fun publishCurrent(generation: Long, publish: () -> Unit) = synchronized(admissionMutex) {
        requireCurrent(generation)
        publish()
    }

    suspend fun canonical(block: suspend (Long) -> LibraryInventory): LibraryInventory {
        val admission = synchronized(admissionMutex) {
            activeCanonicalFlight?.let { active ->
                return@synchronized CanonicalAdmission(active, owner = false)
            }
            val flight = CompletableDeferred<Result<LibraryInventory>>()
            activeCanonicalFlight = flight
            CanonicalAdmission(flight, owner = true)
        }
        if (!admission.owner) return admission.flight.await().getOrThrow()

        while (true) {
            val generation = synchronized(admissionMutex) { invalidationGeneration }
            val outcome = try {
                // A cancelled owner must terminalize its admitted flight, including cancellation
                // after an invalidated traversal returned and before its replacement starts.
                currentCoroutineContext().ensureActive()
                Result.success(block(generation))
            } catch (error: Throwable) { Result.failure(error) }
            val accepted = synchronized(admissionMutex) {
                if (generation != invalidationGeneration &&
                    (outcome.isSuccess || outcome.exceptionOrNull() is CanonicalScanInvalidated)) false
                else {
                    admission.flight.complete(outcome)
                    activeCanonicalFlight = null
                    true
                }
            }
            if (accepted) return outcome.getOrThrow()
        }
    }

    suspend fun manual(
        joinCanonical: suspend (awaitCanonical: suspend () -> LibraryInventory) -> LibraryInventory,
        metadataOnly: suspend () -> LibraryInventory
    ): LibraryInventory {
        val canonicalFlight = synchronized(admissionMutex) { activeCanonicalFlight }
        return if (canonicalFlight != null) {
            joinCanonical { canonicalFlight.await().getOrThrow() }
        } else {
            // This request won admission before any canonical owner. A later canonical request is
            // deliberately allowed to start and will serialize behind the metadata root lock.
            metadataOnly()
        }
    }
}
