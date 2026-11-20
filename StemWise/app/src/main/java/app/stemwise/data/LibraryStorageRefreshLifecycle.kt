package app.stemwise.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** One publication boundary for the visible inventory and its refresh lifecycle. */
enum class LibraryInventoryAvailability {
    RESTORING,
    VERIFIED,
    UNAVAILABLE
}

data class LibraryStorageRefreshState(
    val inventory: LibraryInventory = LibraryInventory(),
    val refreshing: Boolean = false,
    val generation: Long = 0L,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val availability: LibraryInventoryAvailability = LibraryInventoryAvailability.RESTORING
)

/**
 * Coalesces every caller onto one structured refresh flight.
 *
 * The previous implementation let an automatic scan publish "Updated" and then made a manual
 * scan queued behind it run the same full traversal again. UI busy state also lived separately in
 * Compose, so it could outlive the inventory publication. This owner publishes the next inventory
 * and `refreshing = false` in the same StateFlow value; joiners observe and reuse that exact result.
 */
internal class LibraryStorageRefreshLifecycle(
    initialInventory: LibraryInventory = LibraryInventory()
) {
    private data class ActiveFlight(
        val result: CompletableDeferred<Result<LibraryInventory>>,
        val generation: Long,
        val dirtyGenerationAtAdmission: Long,
        val replacementGenerationAtAdmission: Long,
        val ownerJob: Deferred<LibraryInventory>
    )

    private data class Admission(
        val flight: CompletableDeferred<Result<LibraryInventory>>,
        val generation: Long,
        val owner: Boolean
    )

    /*
     * Every admission and terminal publication is a short, non-suspending critical section.
     * A JVM monitor is intentional here: coroutine Mutex acquisition is cancellable, which can
     * strand an already-cancelled owner before it clears the flight. The monitor also lets root
     * invalidation cancel the actual lazy child after detaching it, without invoking cancellation
     * callbacks while the state boundary is locked.
     */
    private val admissionMutex = Any()
    private var activeFlight: ActiveFlight? = null
    private var dirtyGeneration = 0L
    private var replacementGeneration = 0L
    private val mutableState = MutableStateFlow(
        LibraryStorageRefreshState(inventory = initialInventory)
    )
    val state: StateFlow<LibraryStorageRefreshState> = mutableState.asStateFlow()

    suspend fun refresh(block: suspend () -> LibraryInventory): LibraryInventory = coroutineScope {
        // The lifecycle owns this child rather than the caller's entire Job. Root invalidation can
        // therefore propagate to a DocumentsProvider CancellationSignal without cancelling an
        // unrelated application operation that happened to request the refresh.
        val candidateOwner = async(start = CoroutineStart.LAZY) { block() }
        val admission = synchronized(admissionMutex) {
            activeFlight?.let { current ->
                return@synchronized Admission(
                    flight = current.result,
                    generation = current.generation,
                    owner = false
                )
            }
            val generation = mutableState.value.generation + 1L
            val flight = CompletableDeferred<Result<LibraryInventory>>()
            activeFlight = ActiveFlight(
                result = flight,
                generation = generation,
                dirtyGenerationAtAdmission = dirtyGeneration,
                replacementGenerationAtAdmission = replacementGeneration,
                ownerJob = candidateOwner
            )
            mutableState.value = mutableState.value.copy(
                refreshing = true,
                generation = generation,
                errorCode = null,
                errorMessage = null
            )
            Admission(flight = flight, generation = generation, owner = true)
        }
        if (!admission.owner) {
            candidateOwner.cancel()
            return@coroutineScope admission.flight.await().getOrThrow()
        }

        candidateOwner.start()
        val outcome = try {
            Result.success(candidateOwner.await())
        } catch (error: Throwable) {
            Result.failure(error)
        }
        var publishedOutcome = outcome
        val accepted = synchronized(admissionMutex) {
            val currentFlight = activeFlight
            if (
                currentFlight == null ||
                currentFlight.result !== admission.flight ||
                currentFlight.generation != admission.generation ||
                mutableState.value.generation != admission.generation
            ) {
                false
            } else {
                val inventory = outcome.getOrNull()?.let { measured ->
                    if (replacementGeneration != currentFlight.replacementGenerationAtAdmission) {
                        preserveCanonicalInventory(measured, mutableState.value.inventory)
                    } else measured
                }
                val error = outcome.exceptionOrNull()
                mutableState.value = if (inventory != null) {
                    val publishedInventory = if (
                        dirtyGeneration != currentFlight.dirtyGenerationAtAdmission
                    ) {
                        inventory.copy(snapshotDirty = true)
                    } else {
                        inventory
                    }
                    publishedOutcome = Result.success(publishedInventory)
                    LibraryStorageRefreshState(
                        inventory = publishedInventory,
                        refreshing = false,
                        generation = admission.generation,
                        errorCode = null,
                errorMessage = null,
                        availability = LibraryInventoryAvailability.VERIFIED
                    )
                } else {
                    val current = mutableState.value
                    current.copy(
                        refreshing = false,
                        errorCode = error?.let(::storageRefreshErrorCode),
                        errorMessage = error
                            ?.takeUnless { it is CancellationException }
                            ?.let(::storageRefreshErrorDetail),
                        availability = if (error is CancellationException) {
                            current.availability
                        } else {
                            libraryInventoryAvailabilityAfterRefreshFailure(
                                current.availability
                            )
                        }
                    )
                }
                activeFlight = null
                admission.flight.complete(publishedOutcome)
                true
            }
        }
        if (!accepted) {
            // Root loss won the same admission mutex and owns the authoritative terminal result.
            // Awaiting the independent flight is safe even when its provider child was cancelled.
            return@coroutineScope admission.flight.await().getOrThrow()
        }
        publishedOutcome.getOrThrow()
    }

    /** A failed check invalidates only history that has never reached VERIFIED. */
    suspend fun failRefreshWithCachedInventory(error: Throwable): LibraryInventory =
        failWithCachedInventory(error, forceUnavailable = false)

    /**
     * Ends a refresh generation when the selected SAF root is no longer usable.
     *
     * Storage totals are a last-verified snapshot, so root loss must retain the inventory and its
     * original [LibraryInventory.lastCheckedEpochMs]. Only the lifecycle/error fields change. An
     * active flight is detached and completed as a failure so joiners cannot wait forever and a
     * late owner cannot publish a fake fresh result.
     */
    suspend fun failWithCachedInventory(error: Throwable): LibraryInventory =
        failWithCachedInventory(error, forceUnavailable = true)

    private suspend fun failWithCachedInventory(
        error: Throwable,
        forceUnavailable: Boolean
    ): LibraryInventory {
        val interruptedFlight: ActiveFlight?
        val retainedInventory: LibraryInventory
        synchronized(admissionMutex) {
            val current = mutableState.value
            retainedInventory = current.inventory
            interruptedFlight = activeFlight
            val detail = storageRefreshErrorDetail(error)
            if (
                interruptedFlight == null &&
                !current.refreshing &&
                current.errorMessage == detail &&
                (!forceUnavailable ||
                    current.availability == LibraryInventoryAvailability.UNAVAILABLE)
            ) {
                return@synchronized
            }
            activeFlight = null
            mutableState.value = current.copy(
                refreshing = false,
                generation = current.generation + 1L,
                errorCode = storageRefreshErrorCode(error),
                errorMessage = detail,
                availability = if (forceUnavailable) {
                    LibraryInventoryAvailability.UNAVAILABLE
                } else {
                    libraryInventoryAvailabilityAfterRefreshFailure(current.availability)
                }
            )
            interruptedFlight?.result?.complete(Result.failure(error))
        }
        // Cancellation happens after the atomic terminal publication. A provider cursor sees this
        // through suspendCancellableCoroutine and closes its CancellationSignal immediately.
        interruptedFlight?.ownerJob?.cancel(
            LibraryStorageRefreshInvalidatedCancellation(error)
        )
        return retainedInventory
    }

    /** Used by root restore/clear and mutation-dirty publications outside a full scan. */
    fun replaceInventory(
        inventory: LibraryInventory,
        availability: LibraryInventoryAvailability = LibraryInventoryAvailability.VERIFIED
    ) {
        synchronized(admissionMutex) {
            replacementGeneration += 1L
            val current = mutableState.value
            // Canonical reconciliation has a separate flight and can finish while a manual
            // metadata refresh is waiting for the root lock. Preserve its authoritative counts,
            // but never publish its newer "checked" timestamp under this flight's busy marker.
            // The manual owner will stamp and publish its own completed measurement atomically.
            val visibleInventory = if (current.refreshing) {
                inventory.copy(lastCheckedEpochMs = current.inventory.lastCheckedEpochMs)
            } else {
                inventory
            }
            mutableState.value = current.copy(
                inventory = visibleInventory,
                errorCode = null,
                errorMessage = null,
                availability = availability
            )
        }
    }

    /** Committed counts cannot replace physical measurements or their busy/error/availability state. */
    fun publishCanonicalCounts(totals: CanonicalStorageTotals): LibraryInventory = synchronized(admissionMutex) {
        replacementGeneration += 1L
        val current = mutableState.value
        val inventory = current.inventory.copy(
            verifiedSongPackages = totals.packages,
            verifiedOriginalFiles = totals.packages,
            verifiedStemFiles = totals.stems,
            verifiedJsonFiles = totals.packages,
            pcEnrichedSongManifests = totals.packages,
            verifiedArtworkFiles = totals.packages,
            verifiedTotalBytes = totals.verifiedBytes,
            temporaryOrUnclassifiedBytes = (current.inventory.actualLibraryBytes - totals.verifiedBytes).coerceAtLeast(0L),
            snapshotDirty = true
        )
        mutableState.value = current.copy(inventory = inventory)
        inventory
    }

    /** Monotonic mutation token used to keep clean scan snapshots from crossing dirty writes. */
    fun dirtyGenerationToken(): Long = synchronized(admissionMutex) { dirtyGeneration }

    /** Marks the visible snapshot dirty in the same boundary observed by active refresh flights. */
    fun markInventoryDirty(): LibraryInventory = synchronized(admissionMutex) {
        dirtyGeneration += 1L
        val current = mutableState.value
        val dirtyInventory = current.inventory.copy(snapshotDirty = true)
        mutableState.value = current.copy(inventory = dirtyInventory)
        dirtyInventory
    }

    /** Publishes cached counts only as history while the exact selected tree is re-enumerated. */
    fun restoreCachedInventory(inventory: LibraryInventory) = replaceInventory(
        inventory,
        LibraryInventoryAvailability.RESTORING
    )

    /** Invalidates zero/count truth immediately when a root/session transition starts. */
    fun beginRestoration(inventory: LibraryInventory = state.value.inventory) {
        val cancellation = LibraryStorageRefreshRestorationCancellation()
        val interruptedFlight: ActiveFlight?
        synchronized(admissionMutex) {
            val current = mutableState.value
            interruptedFlight = activeFlight
            activeFlight = null
            dirtyGeneration += 1L
            mutableState.value = LibraryStorageRefreshState(
                inventory = inventory,
                refreshing = false,
                generation = current.generation + 1L,
                errorCode = null,
                errorMessage = null,
                availability = LibraryInventoryAvailability.RESTORING
            )
            interruptedFlight?.result?.complete(Result.failure(cancellation))
        }
        interruptedFlight?.ownerJob?.cancel(cancellation)
    }
}

internal fun libraryInventoryAvailabilityAfterRefreshFailure(
    current: LibraryInventoryAvailability
): LibraryInventoryAvailability = if (current == LibraryInventoryAvailability.RESTORING) {
    LibraryInventoryAvailability.UNAVAILABLE
} else {
    current
}

private class LibraryStorageRefreshInvalidatedCancellation(
    error: Throwable
) : CancellationException(error.message ?: "Storage refresh invalidated") {
    init {
        initCause(error)
    }
}

private class LibraryStorageRefreshRestorationCancellation : CancellationException(
    "Storage refresh invalidated for Library restoration"
)

/**
 * Starts a dashboard refresh from an application-owned scope instead of the System composable.
 *
 * A screen can disappear immediately after the tap (Library/Tasks navigation, recreation, or
 * recomposition) without cancelling the scan. The atomic admission gate also closes the tiny
 * interval before [LibraryStorageRefreshLifecycle] publishes `refreshing = true`, so two fast taps
 * cannot queue a second traversal after the first one finishes.
 */
internal class LibraryStorageRefreshRequestOwner(
    private val applicationScope: CoroutineScope,
    private val refresh: suspend () -> Unit
) {
    private val requestInFlight = AtomicBoolean(false)

    fun request(): Boolean {
        if (!requestInFlight.compareAndSet(false, true)) return false
        val job: Job = applicationScope.launch {
            try {
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The storage lifecycle is the single user-visible error owner. Repository scans
                // publish their exact terminal failure there before returning to this boundary.
            }
        }
        // invokeOnCompletion also runs when the application scope was already cancelled, avoiding
        // a permanently closed request gate in tests or controlled process shutdown.
        job.invokeOnCompletion { requestInFlight.set(false) }
        return true
    }
}

internal fun storageRefreshErrorDetail(error: Throwable): String =
    error.message?.trim()?.takeIf(String::isNotBlank)?.take(180)
        ?: "Storage refresh failed"

internal fun storageRefreshErrorCode(error: Throwable): String = when (error) {
    is LibraryRootException -> error.code
    else -> "STORAGE_REFRESH_FAILED"
}
