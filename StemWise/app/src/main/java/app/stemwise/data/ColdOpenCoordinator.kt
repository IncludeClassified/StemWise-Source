package app.stemwise.data

import android.util.Log
import app.stemwise.enrichment.EnrichmentImportCoordinator
import app.stemwise.enrichment.SongEnrichmentImporter
import app.stemwise.integration.SeparationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ColdOpenSession(
    val id: String,
    val localStarted: Boolean,
    val remoteStarted: Boolean,
    val generation: Long = 0L
)

sealed interface ColdOpenState {
    data object Idle : ColdOpenState
    data class Recovering(val step: String) : ColdOpenState
    data class Ready(
        val sessionId: String,
        val generation: Long,
        val libraryId: String,
        val treeUri: String,
        val rootEpoch: Long,
        val preparationBlocked: Boolean = false,
        val preparationRecovery: Blocked? = null
    ) : ColdOpenState
    data class Blocked(val code: String, val message: String) : ColdOpenState
}

internal enum class ColdOpenProducerAdmission { RUN, RETRY, COMPLETE }

internal enum class ColdOpenStage(val fallbackCode: String, val ownerLabel: String) {
    ROOT_SESSION("LIBRARY_ROOT_SESSION_FAILED", "Library connection"),
    PRIVATE_RECOVERY("LIBRARY_PRIVATE_RECOVERY_FAILED", "private recovery"),
    PRIVATE_SCRATCH("LIBRARY_PRIVATE_SCRATCH_FAILED", "private cleanup"),
    LIBRARY_PROJECTION("LIBRARY_PROJECTION_FAILED", "Library projection"),
    AUTHORITY_CONVERGENCE("LIBRARY_AUTHORITY_CONVERGENCE_FAILED", "Library authority"),
    SOURCE_VERIFICATION("ASSET_VERIFICATION_FAILED", "source verification"),
    QUEUE_MAINTENANCE("ACQUISITION_QUEUE_RECONCILE_FAILED", "queue maintenance"),
    PREPARATION_ARBITRATION(
        "PREPARATION_OWNER_RECONCILE_FAILED",
        "preparation ownership"
    )
}

internal data class ColdOpenFailureDiagnostic(val code: String, val message: String)

internal fun coldOpenFailureDiagnostic(
    stage: ColdOpenStage,
    error: Throwable
): ColdOpenFailureDiagnostic {
    val stableCode = (error as? LibraryRootException)?.code
        ?: error.message?.takeIf { it.matches(Regex("^[A-Z][A-Z0-9_]+$")) }
        ?: stage.fallbackCode
    val safeClass = error.javaClass.simpleName
        .takeIf { it.matches(Regex("^[A-Za-z][A-Za-z0-9_$]*$")) }
        ?: "Throwable"
    return ColdOpenFailureDiagnostic(
        code = stableCode,
        message = "Library recovery stopped during ${stage.ownerLabel} ($safeClass)"
    )
}

/** Exact-session publication authority independent of coroutine cancellation cooperation. */
internal class ColdOpenSessionAuthority {
    data class Token(val sessionId: String, val generation: Long)

    private val lock = Any()
    private var generation = 0L
    private var active: Token? = null

    fun begin(sessionId: String, publishNonReady: () -> Unit): Token = synchronized(lock) {
        generation += 1L
        Token(sessionId, generation).also { token ->
            active = token
            publishNonReady()
        }
    }

    fun invalidate(publishNonReady: () -> Unit) = synchronized(lock) {
        generation += 1L
        active = null
        publishNonReady()
    }

    fun publishIfActive(token: Token, publication: () -> Unit): Boolean = synchronized(lock) {
        if (active != token) return@synchronized false
        publication()
        true
    }

    fun isActive(token: Token): Boolean = synchronized(lock) { active == token }

    fun admits(state: ColdOpenState, expectedRoot: RootAuthority): Boolean = synchronized(lock) {
        admitsLocked(state, expectedRoot)
    }

    fun producerAdmission(
        state: () -> ColdOpenState,
        expectedRoot: RootAuthority
    ): ColdOpenProducerAdmission = synchronized(lock) {
        val current = state()
        when {
            current is ColdOpenState.Ready && current.preparationBlocked &&
                admitsLocked(current.copy(preparationBlocked = false), expectedRoot) -> ColdOpenProducerAdmission.RETRY
            admitsLocked(current, expectedRoot) -> ColdOpenProducerAdmission.RUN
            current is ColdOpenState.Idle || current is ColdOpenState.Recovering ->
                ColdOpenProducerAdmission.RETRY
            else -> ColdOpenProducerAdmission.COMPLETE
        }
    }

    private fun admitsLocked(state: ColdOpenState, expectedRoot: RootAuthority): Boolean {
        val ready = state as? ColdOpenState.Ready ?: return false
        return !ready.preparationBlocked && active == Token(ready.sessionId, ready.generation) &&
            ready.libraryId == expectedRoot.libraryId &&
            ready.treeUri == expectedRoot.treeUri &&
            ready.rootEpoch == expectedRoot.epoch
    }
}

/** A local-only readiness barrier: recover first, publish projection second, maintain last. */
class ColdOpenCoordinator(
    private val roots: LibraryRootManager,
    private val externalLibrary: ExternalLibraryRepository,
    private val repository: PlaylistRepository,
    private val preferences: StemwisePreferences,
    private val externalPackageTrash: ExternalPackageTrash,
    private val separationRepository: SeparationRepository,
    private val enrichmentImports: EnrichmentImportCoordinator,
    private val enrichmentImporter: SongEnrichmentImporter
) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionLock = Any()
    private val sessionAuthority = ColdOpenSessionAuthority()
    private var sessionOwner: ColdOpenSessionAuthority.Token? = null
    @Volatile private var sessionJob: Job? = null
    @Volatile private var readyListener: (() -> Unit)? = null
    @Volatile private var ownerRetirementBlocked = false
    @Volatile private var recoveryFailure: Throwable? = null
    @Volatile internal var beforePreparationArbitrationForTest: (() -> Unit)? = null
    private val mutableState = MutableStateFlow<ColdOpenState>(ColdOpenState.Idle)
    val state: StateFlow<ColdOpenState> = mutableState.asStateFlow()

    internal fun setReadyListener(listener: () -> Unit) {
        check(readyListener == null) { "Cold-open ready listener is already registered" }
        readyListener = listener
    }

    /** Converts a synchronous start failure into an explicit non-admitting UI state. */
    internal fun blockStartup(error: Throwable) = synchronized(sessionLock) {
        if (ownerRetirementBlocked) return@synchronized
        val diagnostic = coldOpenFailureDiagnostic(ColdOpenStage.ROOT_SESSION, error)
        sessionAuthority.invalidate {
            recoveryFailure = error
            mutableState.value = ColdOpenState.Blocked(diagnostic.code, diagnostic.message)
        }
    }

    fun start(reconnectRoot: Boolean = false): ColdOpenSession? {
        if (ownerRetirementBlocked) return null
        // Folder selection is a prerequisite for recovery, not a recovery failure.
        // A new installation reaches this coordinator before the SAF picker can
        // return, so leave the gate idle until the selected root is ready.
        if (!roots.state.value.ready && (!reconnectRoot || roots.state.value.treeUri == null)) {
            synchronized(sessionLock) {
                if (!ownerRetirementBlocked) sessionAuthority.invalidate { mutableState.value = ColdOpenState.Idle }
            }
            return null
        }
        val sessionId = UUID.randomUUID().toString()
        val (token, job) = synchronized(sessionLock) {
            if (ownerRetirementBlocked) return null
            if (!started.compareAndSet(false, true)) return null
            val accepted = sessionAuthority.begin(sessionId) {
                recoveryFailure = null
                mutableState.value = ColdOpenState.Recovering("Opening Library")
            }
            // LAZY makes registration and synchronous demotion one atomic boundary. Cancellation
            // can either own the registered job or invalidate the token before any recovery runs.
            sessionJob = scope.launch(start = CoroutineStart.LAZY) {
                runSession(accepted, reconnectRoot)
            }
            val registeredJob = checkNotNull(sessionJob)
            sessionOwner = accepted
            accepted to registeredJob
        }
        job.invokeOnCompletion {
            synchronized(sessionLock) {
                val released = releaseColdOpenOwnership(
                    currentOwner = sessionOwner,
                    currentJob = sessionJob,
                    currentStarted = started.get(),
                    capturedOwner = token,
                    capturedJob = job
                )
                sessionOwner = released.owner
                sessionJob = released.job
                started.set(released.started)
            }
        }
        job.start()
        return coldOpenSession(sessionId, token.generation)
    }

    fun retry() {
        if (ownerRetirementBlocked) return
        scope.launch {
            if (ownerRetirementBlocked) return@launch
            cancelAndJoin()
            if (ownerRetirementBlocked) return@launch
            enrichmentImports.retryStartupScratch()
            start(reconnectRoot = true)
        }
    }

    /** Accepted remote work reopens the actual producer gate, including a failed local recovery read. */
    internal suspend fun refreshPreparationAdmission(expectedRoot: RootAuthority) {
        roots.requireSameLibrary(expectedRoot)
        awaitCompletion()
        roots.requireSameLibrary(expectedRoot)
        check(!ownerRetirementBlocked) { LIBRARY_OWNER_RETIREMENT_PENDING }
        // The selected root is already exact and admitted; do not reconnect or acquire new authority.
        start(reconnectRoot = false)
        awaitCompletion()
        roots.requireSameLibrary(expectedRoot)
        val handle = roots.captureHandle()?.takeIf { it.authority() == expectedRoot } ?: error("LIBRARY_CHANGED")
        if (isProducerAdmissionReady(handle)) return
        when (val current = state.value) {
            is ColdOpenState.Blocked -> throw recoveryFailure ?: IllegalStateException(current.code)
            is ColdOpenState.Ready -> throw recoveryFailure ?: IllegalStateException("PREPARATION_OWNER_RECOVERY_REQUIRED")
            else -> throw java.io.IOException("Library recovery is still pending")
        }
    }

    /** An explicit recheck cannot reconnect or acquire authority for a newer selected root. */
    internal suspend fun recheckPreparation(expectedRoot: RootAuthority) {
        roots.requireSameLibrary(expectedRoot)
        app.stemwise.integration.bundledToolRuntimeMutex.requireHealthy()
        refreshPreparationAdmission(expectedRoot)
    }

    internal fun blockForPendingOwnerRetirement() = synchronized(sessionLock) {
        ownerRetirementBlocked = true
        sessionAuthority.invalidate {
            mutableState.value = ColdOpenState.Blocked(LIBRARY_OWNER_RETIREMENT_PENDING,
                "The previous Library operation has not retired. Force stop StemWise, then reopen it. Your Library files were preserved.")
        }
    }

    suspend fun cancelAndJoin() {
        var cancellingOwner: ColdOpenSessionAuthority.Token? = null
        val cancellingJob = synchronized(sessionLock) {
            if (ownerRetirementBlocked) sessionAuthority.invalidate { Unit }
            else sessionAuthority.invalidate { mutableState.value = ColdOpenState.Idle }
            cancellingOwner = sessionOwner
            sessionJob
        }
        cancellingJob?.cancelAndJoin()
        synchronized(sessionLock) {
            val released = releaseColdOpenOwnership(
                currentOwner = sessionOwner,
                currentJob = sessionJob,
                currentStarted = started.get(),
                capturedOwner = cancellingOwner,
                capturedJob = cancellingJob
            )
            sessionOwner = released.owner
            sessionJob = released.job
            started.set(released.started)
        }
    }

    suspend fun awaitCompletion() {
        val awaitedJob = synchronized(sessionLock) { sessionJob }
        awaitedJob?.join()
    }

    internal fun producerAdmission(handle: RootHandle): ColdOpenProducerAdmission {
        val admission = sessionAuthority.producerAdmission(
            state = { state.value },
            expectedRoot = handle.authority()
        )
        return if (
            admission == ColdOpenProducerAdmission.RUN &&
            runCatching { roots.requireSameLibrary(handle) }.isFailure
        ) {
            ColdOpenProducerAdmission.COMPLETE
        } else {
            admission
        }
    }

    fun isProducerAdmissionReady(handle: RootHandle): Boolean =
        producerAdmission(handle) == ColdOpenProducerAdmission.RUN

    private suspend fun runSession(
        token: ColdOpenSessionAuthority.Token,
        reconnectRoot: Boolean
    ) {
        val handle: RootHandle
        var preparationBlocked = false
        var preparationRecovery: ColdOpenState.Blocked? = null
        var stage = ColdOpenStage.ROOT_SESSION
        try {
            if (reconnectRoot) roots.reconnect(preferences)
            check(roots.state.value.ready) {
                roots.state.value.errorCode ?: "ROOT_UNAVAILABLE"
            }
            val libraryId = checkNotNull(roots.state.value.libraryId) {
                "ROOT_MANIFEST_INVALID"
            }
            check(preferences.activeLibraryIdentity() == libraryId) {
                "ACTIVE_LIBRARY_CONVERGENCE_FAILED"
            }
            handle = checkNotNull(roots.captureHandle()) { "ROOT_UNAVAILABLE" }
            externalLibrary.restoreInventorySnapshot(libraryId)
            // Every cleanup/replay remains behind the exact-session producer barrier.
            try {
                app.stemwise.integration.bundledToolRuntimeMutex.requireHealthy()
                stage = ColdOpenStage.PRIVATE_RECOVERY
                externalPackageTrash.recoverInterruptedDeletes()
                stage = ColdOpenStage.PRIVATE_SCRATCH
                enrichmentImports.awaitStartupScratchReady()
                enrichmentImporter.cleanupOrphanScratch()
                repository.cleanupPreparationScratchForLibrarySwitch()
                separationRepository.cleanupOrphanScratch()
                stage = ColdOpenStage.PRIVATE_RECOVERY
                enrichmentImporter.prepareBundleRecovery(handle)
                externalLibrary.recoverForColdOpen(handle)
                enrichmentImporter.recoverIncompleteBundles(handle)
            } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
                // Quarantined working bytes cannot authorize mutation; installed packages remain independently verifiable.
                Log.w(TAG, "Tool retirement pending; opening Library for playback", error)
                preparationBlocked = true
                val diagnostic = coldOpenFailureDiagnostic(stage, error)
                preparationRecovery = ColdOpenState.Blocked(diagnostic.code,
                    "Preparation is paused until its previous worker retires. Installed songs remain available. Recheck preparation to try recovery.")
                sessionAuthority.publishIfActive(token) { recoveryFailure = error }
            }
            stage = ColdOpenStage.LIBRARY_PROJECTION
            externalLibrary.restoreProjectedLibrary(handle)
            stage = ColdOpenStage.AUTHORITY_CONVERGENCE
            roots.requireSameLibrary(handle)
            check(preferences.activeLibraryIdentity() == handle.libraryId) {
                "ACTIVE_LIBRARY_CONVERGENCE_FAILED"
            }
            // Producer admission remains closed until durable source/job truth and its unique
            // WorkManager continuation have converged for this exact root authority. A failure in
            // either stage is a recovery block, never a post-Ready warning.
            if (!preparationBlocked) {
                stage = ColdOpenStage.PREPARATION_ARBITRATION
                beforePreparationArbitrationForTest?.invoke()
                val issues = separationRepository.reconcilePortableJobAuthority(handle)
                roots.requireSameLibrary(handle)
                preparationBlocked = preparationBlocksRoot(
                    repository.reconcilePreparationOwner(handle, issues.map { it.videoId }))
            }
            roots.requireSameLibrary(handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Cold-open failed during $stage", error)
            val diagnostic = coldOpenFailureDiagnostic(stage, error)
            sessionAuthority.publishIfActive(token) {
                recoveryFailure = error
                mutableState.value = ColdOpenState.Blocked(
                    diagnostic.code,
                    diagnostic.message
                )
            }
            return
        }

        val readyPublished = sessionAuthority.publishIfActive(token) {
            mutableState.value = ColdOpenState.Ready(
                sessionId = token.sessionId,
                generation = token.generation,
                libraryId = handle.libraryId,
                treeUri = handle.treeUri.toString(),
                rootEpoch = handle.epoch,
                preparationBlocked = preparationBlocked,
                preparationRecovery = preparationRecovery
            )
        }
        if (!readyPublished) return
        runCatching { readyListener?.invoke() }
            .onFailure { error -> Log.w(TAG, "Cold-open ready callback failed", error) }
        try {
            externalLibrary.validateProjectedLibrary(handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Background Library verification needs a retry", error)
        }
    }

    companion object {
        private const val TAG = "ColdOpenCoordinator"
    }
}

internal data class ColdOpenOwnershipSnapshot(
    val owner: ColdOpenSessionAuthority.Token?,
    val job: Job?,
    val started: Boolean
)

/**
 * Releases only the token/job pair captured by one completion or cancellation boundary.
 * A replacement registration is returned unchanged even if the obsolete boundary resumes later.
 */
internal fun releaseColdOpenOwnership(
    currentOwner: ColdOpenSessionAuthority.Token?,
    currentJob: Job?,
    currentStarted: Boolean,
    capturedOwner: ColdOpenSessionAuthority.Token?,
    capturedJob: Job?
): ColdOpenOwnershipSnapshot =
    if (currentOwner == capturedOwner && currentJob === capturedJob) {
        ColdOpenOwnershipSnapshot(null, null, false)
    } else {
        ColdOpenOwnershipSnapshot(currentOwner, currentJob, currentStarted)
    }

internal fun coldOpenSession(id: String, generation: Long = 0L): ColdOpenSession = ColdOpenSession(
    id = id,
    localStarted = true,
    remoteStarted = false,
    generation = generation
)
