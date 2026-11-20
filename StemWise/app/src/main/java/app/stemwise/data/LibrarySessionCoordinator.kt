package app.stemwise.data

import android.content.Context
import app.stemwise.enrichment.EnrichmentImportCoordinator
import app.stemwise.enrichment.SongEnrichmentImporter
import app.stemwise.bridge.SongBridgeRepository
import app.stemwise.integration.SeparationRepository
import app.stemwise.integration.PlaylistCommitAuthorization
import app.stemwise.playback.AudioPlayback
import app.stemwise.work.ManualTaskCoordinator
import app.stemwise.work.PlaylistSyncWorker
import app.stemwise.work.MvsepPreparationWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal suspend fun <T> completeConfirmedLibraryMutation(
    block: suspend () -> T
): T = withContext(Dispatchers.IO + NonCancellable) { block() }

internal const val LIBRARY_OWNER_RETIREMENT_PENDING = "LIBRARY_OWNER_RETIREMENT_PENDING"

/** The waiter may time out; the retained stop operation continues owning every old writer. */
internal class LibraryOwnerRetirement(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val timeoutMs: Long = 10_000L
) {
    private var stopping: Deferred<Unit>? = null
    val pending: Boolean get() = stopping != null

    suspend fun await(stop: suspend () -> Unit) {
        val operation = stopping ?: scope.async { stop() }.also { stopping = it }
        if (withTimeoutOrNull(timeoutMs) { operation.await(); true } != true) {
            throw LibraryRootException(LIBRARY_OWNER_RETIREMENT_PENDING,
                "The previous Library operation has not retired. Force stop StemWise, then reopen it. Your Library files were preserved.")
        }
        stopping = null
    }
}

/** Always preserves the commit failure even when persisted-root convergence also fails. */
internal fun <T> failedLibrarySwitchResult(
    commitFailure: Throwable,
    recoveryFailure: Throwable?
): Result<T> {
    if (recoveryFailure != null && recoveryFailure !== commitFailure) {
        // Throwable rejects self-suppression; diagnostics must never replace the outcome.
        runCatching { commitFailure.addSuppressed(recoveryFailure) }
    }
    return Result.failure(commitFailure)
}

/** Schedule maintenance is observable but never part of root authority convergence. */
internal fun runPlaylistScheduleMaintenance(update: () -> Unit): Throwable? =
    runCatching(update).exceptionOrNull()

internal const val LIBRARY_SWITCH_REMOTE_SEPARATION_PENDING =
    "LIBRARY_SWITCH_REMOTE_SEPARATION_PENDING"

internal fun blocksLibrarySwitchForSeparationSafety(
    changingLibrary: Boolean,
    hasSafetyFacts: Boolean
): Boolean = changingLibrary && hasSafetyFacts

internal fun selectedRootIdentityChanges(
    previousTreeUri: String?,
    previousLibraryId: String?,
    candidateTreeUri: String,
    candidateLibraryId: String
): Boolean = previousTreeUri != null &&
    (previousTreeUri != candidateTreeUri || previousLibraryId != candidateLibraryId)

/** The owning stop/check/publication order, with external operations supplied by the session. */
internal suspend fun retirePreparationForLibrarySwitch(
    lane: Mutex,
    changingLibrary: Boolean,
    hasSafetyFacts: suspend () -> Boolean,
    stopOwners: suspend () -> Unit,
    retirement: LibraryOwnerRetirement = LibraryOwnerRetirement(),
    closeAdmission: () -> Unit = {},
    commit: suspend () -> LibraryRootState
): LibraryRootState {
    suspend fun refuseRemoteWork() {
        if (blocksLibrarySwitchForSeparationSafety(changingLibrary, hasSafetyFacts())) {
            throw LibraryRootException(LIBRARY_SWITCH_REMOTE_SEPARATION_PENDING,
                "Resolve the pending online stem job before changing Library folders")
        }
    }
    refuseRemoteWork()
    closeAdmission()
    retirement.await {
        stopOwners()
        // WorkManager acknowledges scheduling cancellation before a running writer releases this lane.
        lane.withLock { }
    }
    return lane.withLock {
        refuseRemoteWork()
        commit()
    }
}

/**
 * The single atomic boundary between two user-selected Libraries.
 *
 * No new root is published until old playback, imports, cold-open work and every
 * WorkManager owner have stopped and the global Room projection has been cleared.
 */
class LibrarySessionCoordinator(
    context: Context,
    private val roots: LibraryRootManager,
    private val preferences: StemwisePreferences,
    private val playlistRepository: PlaylistRepository,
    private val externalLibrary: ExternalLibraryRepository,
    private val enrichmentImports: EnrichmentImportCoordinator,
    private val enrichmentImporter: SongEnrichmentImporter,
    private val separationRepository: SeparationRepository,
    private val audioPlayback: AudioPlayback,
    private val coldOpen: ColdOpenCoordinator,
    private val songWorkCoordinator: SongWorkCoordinator,
    private val manualTasks: ManualTaskCoordinator,
    private val bridgeSongs: SongBridgeRepository
) {
    private val applicationContext = context.applicationContext
    private val switchMutex = Mutex()
    private val ownerRetirement = LibraryOwnerRetirement()
    private val mutableSwitching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = mutableSwitching.asStateFlow()

    suspend fun switchTo(candidate: ValidatedRootCandidate): Result<LibraryRootState> =
        // Once the user confirms a validated root, finish the stop/clear/commit
        // boundary even if the Activity or its Compose scope is recreated. A
        // cancelled UI owner must not leave the old projection cleared with no
        // authoritative root/session restarted.
        completeConfirmedLibraryMutation {
            switchMutex.withLock switch@{
                // A selection journal is recoverable only against its exact persisted root and
                // Room projection. Never stop/clear/switch that authority out from underneath it.
                val previousRoot = roots.state.value
                val previousUri = previousRoot.treeUri
                val previousLibraryId = previousRoot.libraryId
                val candidateLibraryId = candidate.libraryId
                val changingLibrary = selectedRootIdentityChanges(
                    previousUri?.toString(),
                    previousLibraryId,
                    candidate.treeUri.toString(),
                    candidateLibraryId
                )
                if (
                    previousUri != null &&
                    isAppOwnedUiDemoTreeUri(applicationContext.packageName, candidate.treeUri)
                ) {
                    return@switch Result.failure(
                        LibraryRootException(
                            "UI_DEMO_LIBRARY_REQUIRES_UNSELECTED_ROOT",
                            "The UI Demo Library is available only before a Library folder is selected"
                        )
                    )
                }
                if (
                    !ownerRetirement.pending && previousRoot.ready &&
                    previousUri == candidate.treeUri &&
                    previousLibraryId == candidateLibraryId &&
                    coldOpen.state.value !is ColdOpenState.Blocked
                ) {
                    if ((coldOpen.state.value as? ColdOpenState.Ready)?.preparationRecovery != null) {
                        return@switch app.stemwise.suspendResult {
                            coldOpen.recheckPreparation(checkNotNull(roots.captureHandle(previousRoot)).authority())
                            previousRoot
                        }
                    }
                    return@switch Result.success(previousRoot)
                }
                var switchStarted = false
                try {
                    val committed = try {
                        retirePreparationForLibrarySwitch(
                            lane = songWorkCoordinator.preparationLane,
                            changingLibrary = changingLibrary,
                            hasSafetyFacts = externalLibrary::hasRootSwitchBlockingSeparationSafetyFacts,
                            stopOwners = { stopOldSessionOwners() },
                            retirement = ownerRetirement,
                            closeAdmission = {
                                mutableSwitching.value = true
                                switchStarted = true
                                manualTasks.onLibrarySwitchStarted()
                            },
                            commit = {
                                playlistRepository.cleanupPreparationScratchUnderPreparationLane()
                                separationRepository.cleanupOrphanScratchUnderPreparationLane()
                                enrichmentImporter.cleanupOrphanScratch()
                                externalLibrary.clearForLibrarySwitch(changingLibrary)
                                roots.commitValidatedCandidate(candidate).getOrThrow()
                            }
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (!switchStarted) return@switch Result.failure(error)
                        if (ownerRetirement.pending) {
                            coldOpen.blockForPendingOwnerRetirement()
                            return@switch Result.failure(error)
                        }
                        // The Room transaction either retained the old selection/projection or
                        // committed the new selection. Recover that exact row before reopening work.
                        val recoveryFailure = runCatching {
                            songWorkCoordinator.preparationLane.withLock {
                                val restored = roots.reconnect(preferences)
                                if (restored.ready) {
                                    playlistRepository.activateCurrentLibrarySession()
                                    coldOpen.start()
                                } else if (previousRoot.ready) {
                                    error(restored.errorCode ?: "ROOT_UNAVAILABLE")
                                }
                            }
                        }.exceptionOrNull()
                        return@switch failedLibrarySwitchResult(
                            commitFailure = error,
                            recoveryFailure = recoveryFailure
                        )
                    }

                    // Room owns selection; playlist/UI activation only projects its committed row.
                    // Keep handles closed if this session activation needs to be retried.
                    val authorityFailure = runCatching {
                        preferences.activateLibraryFromRoot(
                            checkNotNull(committed.libraryId),
                            roots.configuredPlaylistIdentity()
                        )
                        check(preferences.activeLibraryIdentity() == committed.libraryId) {
                            "ACTIVE_LIBRARY_CONVERGENCE_FAILED"
                        }
                        // Bind the playlist generation before the exact Room state becomes VERIFIED.
                        playlistRepository.activateCurrentLibrarySession(committed)
                        roots.publishCommittedRoot(committed)
                    }.exceptionOrNull()
                    if (authorityFailure != null) {
                        runCatching { playlistRepository.cancelSessionWorkForLibrarySwitch() }
                            .exceptionOrNull()
                            ?.takeIf { it !== authorityFailure }
                            ?.let(authorityFailure::addSuppressed)
                        val recoveryFailure = runCatching {
                            roots.retainCommittedRootForRecovery(committed)
                        }.exceptionOrNull()
                        return@switch failedLibrarySwitchResult(
                            commitFailure = authorityFailure,
                            recoveryFailure = recoveryFailure
                        )
                    }

                    // Schedule maintenance is deliberately outside the authority transaction.
                    // Its failure is retryable and cannot invalidate an otherwise converged root.
                    runPlaylistScheduleMaintenance {
                        PlaylistSyncWorker.updateSchedule(
                            applicationContext,
                            preferences.playlist.value.configured
                        )
                    }?.let { error ->
                        android.util.Log.w(
                            "StemwiseLibrary",
                            "Library opened; playlist schedule needs a later retry",
                            error
                        )
                    }
                    checkNotNull(coldOpen.start()) { "LIBRARY_SESSION_START_FAILED" }
                    if (
                        previousUri != null &&
                        previousUri != committed.treeUri &&
                        changingLibrary
                    ) {
                        roots.releasePersistentPermission(previousUri)
                    }
                    Result.success(committed)
                } finally {
                    if (switchStarted) {
                        if (!ownerRetirement.pending) {
                            manualTasks.onLibrarySwitchFinished()
                            bridgeSongs.onLibrarySwitchFinished()
                        }
                        mutableSwitching.value = false
                    }
                }
            }
        }

    /**
     * Commits the reviewed non-destructive Keep & add path under one crash-recovery authority.
     * Replace deliberately has no mode until the owner defines its deletion/archive semantics.
     */
    internal suspend fun commitPlaylist(
        candidate: PlaylistCandidate,
        mode: PlaylistCommitMode,
        authorization: PlaylistCommitAuthorization
    ): Result<PlaylistSyncSummary> = completeConfirmedLibraryMutation {
        switchMutex.withLock {
            if (ownerRetirement.pending) return@withLock Result.failure(LibraryRootException(
                LIBRARY_OWNER_RETIREMENT_PENDING,
                "The previous Library operation has not retired. Force stop StemWise, then reopen it. Your Library files were preserved."))
            check(mode == PlaylistCommitMode.KEEP_AND_ADD) {
                "PLAYLIST_COMMIT_MODE_INVALID"
            }
            check(authorization.link == candidate.link) {
                "PLAYLIST_COMMIT_AUTHORIZATION_MISMATCH"
            }
            val expectedRoot = roots.captureHandle()?.authority()
                ?: return@withLock Result.failure(IllegalStateException("ROOT_UNAVAILABLE"))
            var lease: PlaylistCommitLease? = null
            var reopenSession = false
            var restartColdOpen = false
            var maintainSchedule = false
            mutableSwitching.value = true
            manualTasks.onLibrarySwitchStarted()
            var outcome: Result<PlaylistSyncSummary>? = null
            try {
                authorization.requireCurrent()
                lease = playlistRepository.beginExclusivePlaylistCommit(candidate, expectedRoot)
                // Admission is closed before these joins. A cancellation-ignoring queued owner is
                // rejected by its stale playlist session before it can mutate Room.
                ownerRetirement.await {
                    playlistRepository.retireExclusivePlaylistSession(checkNotNull(lease))
                    coldOpen.cancelAndJoin()
                    externalLibrary.retireScansForLibrarySwitch()
                    PlaylistSyncWorker.cancelSchedulingAndAwait(applicationContext)
                    MvsepPreparationWorker.cancelSchedulingAndAwait(applicationContext)
                    songWorkCoordinator.preparationLane.withLock { }
                }
                val committed = songWorkCoordinator.preparationLane.withLock {
                    roots.withExactPlaylistCommitMutation(expectedRoot) {
                        authorization.requireCurrent()
                        val activeLease = checkNotNull(lease)
                        playlistRepository.requireExclusivePlaylistCommit(activeLease, candidate)
                        check(preferences.playlist.value.link.id == candidate.currentPlaylistId) {
                            "PLAYLIST_CANDIDATE_STALE"
                        }
                        playlistRepository.commitPlaylistCandidate(
                            candidate = candidate, mode = mode, lease = activeLease,
                            requireCurrentAuthorization = { authorization.requireCurrent() }
                        ).getOrThrow()
                    }
                }
                reopenSession = true
                restartColdOpen = true
                maintainSchedule = true
                outcome = Result.success(committed)
            } catch (error: Throwable) {
                // A failed Room transaction leaves selection and catalog old together. Projection
                // publication can be retried directly from Room; there is no second durable store.
                if (ownerRetirement.pending) coldOpen.blockForPendingOwnerRetirement()
                if (!ownerRetirement.pending && roots.state.value.ready) {
                    reopenSession = lease != null
                    restartColdOpen = lease != null
                    maintainSchedule = lease != null
                }
                outcome = Result.failure(error)
            } finally {
                lease?.takeUnless { ownerRetirement.pending }?.let {
                    playlistRepository.finishExclusivePlaylistCommit(
                        lease = it,
                        reopenSession = reopenSession && roots.state.value.ready
                    )
                }
                if (!ownerRetirement.pending) manualTasks.onLibrarySwitchFinished()
                mutableSwitching.value = false
            }
            if (maintainSchedule) {
                runPlaylistScheduleMaintenance {
                    PlaylistSyncWorker.updateSchedule(
                        applicationContext,
                        preferences.playlist.value.configured
                    )
                }?.let { error ->
                    android.util.Log.w(
                        "StemwiseLibrary",
                        "Playlist selection recovered; background schedule needs a later retry",
                        error
                    )
                }
            }
            if (restartColdOpen) {
                runCatching { checkNotNull(coldOpen.start()) { "LIBRARY_SESSION_START_FAILED" } }.onFailure { error ->
                    outcome = checkNotNull(outcome).map { it.copy(projectionRefreshPending = true) }
                    android.util.Log.w(
                        "StemwiseLibrary",
                        "Playlist selection recovered; cold-open maintenance needs a retry",
                        error
                    )
                }
            }
            checkNotNull(outcome)
        }
    }

    private suspend fun stopOldSessionOwners() {
        // Admission was closed synchronously before dispatching this retained retirement.
        coldOpen.cancelAndJoin()
        externalLibrary.retireScansForLibrarySwitch()
        playlistRepository.cancelSessionWorkForLibrarySwitch()
        enrichmentImports.cancelForLibraryChange()
        MvsepPreparationWorker.cancelSchedulingAndAwait(applicationContext)
        app.stemwise.work.BridgeSongWorker.cancelSchedulingAndAwait(applicationContext)
        bridgeSongs.cancelForLibraryChange()
        PlaylistSyncWorker.cancelSchedulingAndAwait(applicationContext)
        audioPlayback.clearLibrarySession()
    }
}
