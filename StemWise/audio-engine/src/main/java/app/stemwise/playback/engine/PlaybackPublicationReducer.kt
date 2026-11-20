package app.stemwise.playback.engine

/**
 * Canonical song metadata identity. App models remain behind an app-owned immutable key.
 */
data class PlaybackPublicationMetadata(
    val songId: String,
    val audioRevision: String,
    val revision: Long,
    val payloadKey: String? = null
) {
    init {
        require(songId.isNotBlank())
        require(audioRevision.isNotBlank())
        require(revision > 0L)
    }
}

/** Maps the controller's opaque metadata without coupling the reducer to AudioPlayback models. */
internal fun interface PlaybackPublicationMetadataMapper {
    fun map(metadata: NativeEngineMetadata): PlaybackPublicationMetadata

    companion object {
        val Default = PlaybackPublicationMetadataMapper { metadata ->
            PlaybackPublicationMetadata(
                songId = metadata.songId,
                audioRevision = metadata.audioRevision,
                revision = metadata.revision,
                payloadKey = metadata.payloadKey
            )
        }
    }
}

data class PlaybackCommittedEngineOwner(
    val ownership: NativeRuntimeOwnership,
    val kind: NativeEngineOwnerPublicationKind,
    val callbackSequence: Long?
) {
    init {
        val pausedInstall = kind == NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED ||
            kind == NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PAUSED_INSTALLED
        require(pausedInstall == (callbackSequence == null))
        require(callbackSequence == null || callbackSequence > 0L)
    }
}

data class PlaybackPublicationTransport(
    val desiredRevision: Long = 0L,
    val playWhenReady: Boolean,
    val requestedPositionMs: Long,
    val presentedPositionMs: Long,
    val durationMs: Long,
    val positionCallbackSequence: Long = 0L,
    val completedSeekRevision: Long = 0L
) {
    init {
        require(desiredRevision >= 0L)
        require(durationMs > 0L)
        require(requestedPositionMs in 0L..durationMs)
        require(presentedPositionMs in 0L..durationMs)
        require(positionCallbackSequence >= 0L)
    }
}

data class PlaybackPublicationSourceState(
    val desiredRevision: Long = 0L,
    val desiredMode: EngineSourceMode = EngineSourceMode.ORIGINAL,
    val preparedRevision: Long = 0L,
    val preparedMode: EngineSourceMode = EngineSourceMode.ORIGINAL,
    val appliedRevision: Long = 0L,
    val appliedMode: EngineSourceMode = EngineSourceMode.ORIGINAL,
    val notReadyReason: String? = null
) {
    init {
        require(desiredRevision >= 0L)
        require(preparedRevision in 0L..desiredRevision)
        require(appliedRevision >= 0L)
        require(appliedRevision <= desiredRevision)
    }
}

data class PlaybackStemPackageRequest(
    val packageSignature: String,
    val stemRevision: String,
    val requestRevision: Long
) {
    init {
        require(packageSignature.isNotBlank())
        require(stemRevision.isNotBlank())
        require(requestRevision > 0L)
    }
}

data class PlaybackInstalledStemPackage(
    val packageSignature: String,
    val stemRevision: String,
    val requestRevision: Long,
    val callbackSequence: Long
) {
    init {
        require(packageSignature.isNotBlank())
        require(stemRevision.isNotBlank())
        require(requestRevision > 0L)
        require(callbackSequence > 0L)
    }
}

enum class PlaybackStemPackagePhase {
    NONE,
    REQUESTED,
    PREPARING,
    READY_AWAITING_AUDIBLE,
    INSTALLED,
    FAILED
}

data class PlaybackPublicationStemState(
    val desired: PlaybackStemPackageRequest? = null,
    val installed: PlaybackInstalledStemPackage? = null,
    val phase: PlaybackStemPackagePhase = PlaybackStemPackagePhase.NONE,
    val retainedPriorPackage: Boolean = false,
    val failureCause: String? = null
) {
    init {
        require((desired == null) == (phase == PlaybackStemPackagePhase.NONE))
        require(phase != PlaybackStemPackagePhase.INSTALLED || installed != null)
        if (installed != null && desired != null &&
            installed.requestRevision == desired.requestRevision
        ) {
            require(installed.packageSignature == desired.packageSignature)
            require(installed.stemRevision == desired.stemRevision)
        }
    }
}

data class PlaybackPublicationIssue(
    val category: String,
    val cause: String,
    val recoverable: Boolean,
    val token: EngineSelectionToken,
    val generation: Long? = null,
    val revision: Long? = null
) {
    init {
        require(category.isNotBlank())
        require(cause.isNotBlank())
        require(generation == null || generation > 0L)
        require(revision == null || revision > 0L)
    }
}

/** The only object a player UI or session facade may expose as the current song. */
data class PlaybackCommittedPublication(
    val selection: EngineSelection,
    val owner: PlaybackCommittedEngineOwner,
    val metadata: PlaybackPublicationMetadata,
    val transport: PlaybackPublicationTransport,
    val source: PlaybackPublicationSourceState = PlaybackPublicationSourceState(),
    val stems: PlaybackPublicationStemState = PlaybackPublicationStemState(),
    val issue: PlaybackPublicationIssue? = null
) {
    init {
        require(selection.token.value == owner.ownership.selectionToken)
        require(selection.songId == metadata.songId)
        require(selection.audioRevision == metadata.audioRevision)
        require(transport.durationMs == selection.durationMs)
        require(issue == null || issue.token == selection.token)
    }
}

/** A request may load and accumulate desired state, but it is never the visible current song. */
data class PlaybackPendingSelection(
    val selection: EngineSelection,
    val expectedOwnership: NativeRuntimeOwnership,
    val metadata: PlaybackPublicationMetadata,
    val transport: PlaybackPublicationTransport,
    val source: PlaybackPublicationSourceState = PlaybackPublicationSourceState(),
    val stems: PlaybackPublicationStemState = PlaybackPublicationStemState(),
    val issue: PlaybackPublicationIssue? = null
) {
    init {
        require(selection.token.value == expectedOwnership.selectionToken)
        require(selection.songId == metadata.songId)
        require(selection.audioRevision == metadata.audioRevision)
        require(transport.durationMs == selection.durationMs)
        require(issue == null || issue.token == selection.token)
    }
}

internal data class PlaybackPublicationSelectionRequest(
    val selection: EngineSelection,
    val expectedOwnership: NativeRuntimeOwnership,
    val metadata: PlaybackPublicationMetadata? = null
) {
    init {
        require(selection.token.value == expectedOwnership.selectionToken)
        if (metadata != null) {
            require(metadata.songId == selection.songId)
            require(metadata.audioRevision == selection.audioRevision)
        }
    }
}

data class PlaybackPendingStop(
    val requestId: NativeEngineStopRequestId,
    val selectionSerial: Long
) {
    init { require(selectionSerial >= 0L) }
}

data class PlaybackAudibleEndKey(
    val ownership: NativeRuntimeOwnership,
    val sequence: Long
) {
    init { require(sequence > 0L) }
}

data class PlaybackPublicationReducerState(
    val committed: PlaybackCommittedPublication? = null,
    val pendingSelection: PlaybackPendingSelection? = null,
    val pendingStop: PlaybackPendingStop? = null,
    val selectionSerial: Long = 0L,
    val acceptedAudibleEnd: PlaybackAudibleEndKey? = null
) {
    init {
        require(selectionSerial >= 0L)
        if (acceptedAudibleEnd != null) {
            require(committed?.owner?.ownership == acceptedAudibleEnd.ownership)
        }
    }
}

internal enum class PlaybackPublicationDisposition {
    APPLIED,
    IGNORED_STALE,
    OBSERVED_ONLY
}

internal sealed interface PlaybackPublicationEffect {
    data class OutputAvailabilityChanged(val token: EngineSelectionToken, val available: Boolean,
        val diagnostic: String?) : PlaybackPublicationEffect
    data class AudibleEnd(
        val token: EngineSelectionToken,
        val ownership: NativeRuntimeOwnership,
        val sequence: Long,

        val sourceChangePending: Boolean = false
    ) : PlaybackPublicationEffect

    data class StopCompletion(
        val requestId: NativeEngineStopRequestId,
        val clearedPublication: Boolean
    ) : PlaybackPublicationEffect

    data class Diagnostic(val operation: String, val diagnostic: String) :
        PlaybackPublicationEffect

    data class OwnerTerminated(
        val ownership: NativeRuntimeOwnership,
        val cause: String
    ) : PlaybackPublicationEffect

    data class SelectionFailed(
        val token: EngineSelectionToken,
        val cause: String
    ) : PlaybackPublicationEffect

    data class LoopRejected(
        val token: EngineSelectionToken,
        val cause: String
    ) : PlaybackPublicationEffect

    data class OutputUnavailable(val diagnostic: String) : PlaybackPublicationEffect

    data object Closed : PlaybackPublicationEffect
}

internal data class PlaybackPublicationReduction(
    val state: PlaybackPublicationReducerState,
    val disposition: PlaybackPublicationDisposition,
    val effects: List<PlaybackPublicationEffect> = emptyList()
)

/** A new exact source/package request is the retry boundary for recoverable source failures. */
private fun PlaybackPublicationIssue?.clearedForFreshSourceRequest(): PlaybackPublicationIssue? =
    this?.takeUnless { issue ->
        val failedStemLane = issue.category.removePrefix("decoder-")
            .toIntOrNull() in 1..app.stemwise.model.StemRoleContract.maximumStemCount
        issue.recoverable && (issue.category == "source-mode" ||
            issue.category == "stem-package" || failedStemLane)
    }

/**
 * Serialized, side-effect-free authority for current playback publication.
 *
 * All public methods replace one immutable snapshot under this object's monitor. They never call
 * a renderer, queue, MediaSession, listener, or decoder. Queue advancement, repeat, and shuffle
 * consume returned effects elsewhere and deliberately do not exist in this reducer.
 */
internal class PlaybackPublicationReducer(
    private val metadataMapper: PlaybackPublicationMetadataMapper =
        PlaybackPublicationMetadataMapper.Default
) {
    private var current = PlaybackPublicationReducerState()

    @Synchronized
    fun snapshot(): PlaybackPublicationReducerState = current

    /**
     * Explicit/manual selection boundary.
     *
     * Native silences the outgoing owner at this boundary, so retaining its committed publication
     * would let UI/MediaSession claim that old PCM is still playing. The upcoming owner remains
     * represented by the adapter's latest request until exact generation evidence can create the
     * normal pending/committed publication. Automatic successors never call this method and keep
     * their atomic gapless outgoing-to-successor publication path.
     */
    @Synchronized
    fun beginManualSelection(): PlaybackPublicationReduction {
        current = current.copy(
            committed = null,
            pendingSelection = null,
            acceptedAudibleEnd = null
        )
        return applied()
    }

    @Synchronized
    fun requestSelection(
        request: PlaybackPublicationSelectionRequest
    ): PlaybackPublicationReduction {
        val newestToken = maxOf(
            current.committed?.selection?.token?.value ?: 0L,
            current.pendingSelection?.selection?.token?.value ?: 0L
        )
        if (request.selection.token.value <= newestToken) return ignored()
        check(current.selectionSerial < Long.MAX_VALUE) { "Selection serial space exhausted" }
        val selection = request.selection
        val startPositionMs = selection.startPositionMs.coerceIn(selection.playbackStartMs, selection.playbackEndMs)
        val metadata = request.metadata ?: fallbackMetadata(selection)
        current = current.copy(
            pendingSelection = PlaybackPendingSelection(
                selection = selection,
                expectedOwnership = request.expectedOwnership,
                metadata = metadata,
                transport = PlaybackPublicationTransport(
                    playWhenReady = selection.autoplay,
                    requestedPositionMs = startPositionMs,
                    presentedPositionMs = startPositionMs,
                    durationMs = selection.durationMs
                )
            ),
            selectionSerial = current.selectionSerial + 1L
        )
        return applied()
    }

    @Synchronized
    fun requestTransport(
        token: EngineSelectionToken,
        revision: Long,
        playWhenReady: Boolean,
        requestedPositionMs: Long
    ): PlaybackPublicationReduction {
        require(revision > 0L)
        val pending = current.pendingSelection
        if (pending != null) {
            if (pending.selection.token != token ||
                revision <= pending.transport.desiredRevision ||
                requestedPositionMs !in pending.selection.playbackStartMs..pending.selection.playbackEndMs
            ) return ignored()
            current = current.copy(
                pendingSelection = pending.copy(
                    transport = pending.transport.copy(
                        desiredRevision = revision,
                        playWhenReady = playWhenReady,
                        requestedPositionMs = requestedPositionMs
                    )
                )
            )
            return applied()
        }
        val committed = current.committed ?: return ignored()
        if (committed.selection.token != token ||
            revision <= committed.transport.desiredRevision ||
            requestedPositionMs !in committed.selection.playbackStartMs..committed.selection.playbackEndMs
        ) return ignored()
        current = current.copy(
            committed = committed.copy(
                transport = committed.transport.copy(
                    desiredRevision = revision,
                    playWhenReady = playWhenReady,
                    requestedPositionMs = requestedPositionMs
                )
            )
        )
        return applied()
    }

    @Synchronized
    fun requestSourceMode(
        token: EngineSelectionToken,
        desiredRevision: Long,
        mode: EngineSourceMode
    ): PlaybackPublicationReduction {
        require(desiredRevision > 0L)
        val pending = current.pendingSelection
        if (pending?.selection?.token == token) {
            if (desiredRevision <= pending.source.desiredRevision) return ignored()
            current = current.copy(
                pendingSelection = pending.copy(
                    source = pending.source.copy(
                        desiredRevision = desiredRevision,
                        desiredMode = mode,
                        notReadyReason = null
                    ),
                    issue = pending.issue.clearedForFreshSourceRequest()
                )
            )
            return applied()
        }
        val committed = current.committed ?: return ignored()
        if (committed.selection.token != token ||
            desiredRevision <= committed.source.desiredRevision
        ) return ignored()
        current = current.copy(
            committed = committed.copy(
                source = committed.source.copy(
                    desiredRevision = desiredRevision,
                    desiredMode = mode,
                    notReadyReason = null
                ),
                issue = committed.issue.clearedForFreshSourceRequest()
            )
        )
        return applied()
    }

    @Synchronized
    fun requestStemPackage(
        token: EngineSelectionToken,
        request: PlaybackStemPackageRequest
    ): PlaybackPublicationReduction {
        val pending = current.pendingSelection
        if (pending?.selection?.token == token) {
            if (request.requestRevision <=
                (pending.stems.desired?.requestRevision ?: 0L)
            ) return ignored()
            current = current.copy(
                pendingSelection = pending.copy(
                    stems = pending.stems.copy(
                        desired = request,
                        phase = PlaybackStemPackagePhase.REQUESTED,
                        retainedPriorPackage = pending.stems.installed != null,
                        failureCause = null
                    ),
                    issue = pending.issue.clearedForFreshSourceRequest()
                )
            )
            return applied()
        }
        val committed = current.committed ?: return ignored()
        if (committed.selection.token != token ||
            request.requestRevision <=
            (committed.stems.desired?.requestRevision ?: 0L)
        ) return ignored()
        current = current.copy(
            committed = committed.copy(
                stems = committed.stems.copy(
                    desired = request,
                    phase = PlaybackStemPackagePhase.REQUESTED,
                    retainedPriorPackage = committed.stems.installed != null,
                    failureCause = null
                ),
                issue = committed.issue.clearedForFreshSourceRequest()
            )
        )
        return applied()
    }

    @Synchronized
    fun requestStop(requestId: NativeEngineStopRequestId): PlaybackPublicationReduction {
        current = current.copy(
            pendingStop = PlaybackPendingStop(requestId, current.selectionSerial)
        )
        return applied()
    }

    @Synchronized
    fun onControllerEvent(event: NativeEngineControllerEvent): PlaybackPublicationReduction =
        when (event) {
            is NativeEngineControllerEvent.OutputAvailabilityChanged -> observed(
                PlaybackPublicationEffect.OutputAvailabilityChanged(event.token, event.available, event.diagnostic))
            is NativeEngineControllerEvent.OwnerPublished -> ownerPublished(event.publication)
            is NativeEngineControllerEvent.MetadataUpdated -> metadataUpdated(event.metadata)
            is NativeEngineControllerEvent.Position -> position(event)
            is NativeEngineControllerEvent.AudibleEnd -> audibleEnd(event)
            is NativeEngineControllerEvent.SourceModeApplied -> sourceModeApplied(event)
            is NativeEngineControllerEvent.SourceModeReadyAwaitingAudible ->
                sourceModeReadyAwaitingAudible(event)
            is NativeEngineControllerEvent.SourceModeNotReady -> sourceModeNotReady(event)
            is NativeEngineControllerEvent.StemPackagePreparing -> stemPreparing(event)
            is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible ->
                stemReadyAwaitingAudible(event)
            is NativeEngineControllerEvent.StemPackageInstalled -> stemInstalled(event)
            is NativeEngineControllerEvent.StemPackageFailed -> stemFailed(event)
            is NativeEngineControllerEvent.DecoderFailed -> decoderFailed(event)
            is NativeEngineControllerEvent.SelectionFailed -> selectionFailed(event)
            is NativeEngineControllerEvent.OwnerTerminated -> ownerTerminated(event)
            is NativeEngineControllerEvent.LoopNotReady -> loopRejected(event)
            is NativeEngineControllerEvent.LoopAvailabilityChanged -> ignored()
            is NativeEngineControllerEvent.BeatGridNotReady -> observed(
                PlaybackPublicationEffect.Diagnostic("beat-grid", event.reason)
            )
            is NativeEngineControllerEvent.Failure -> observed(
                PlaybackPublicationEffect.Diagnostic(event.operation, event.diagnostic)
            )
            is NativeEngineControllerEvent.FatalRecoveryRequired -> outputUnavailable(event)
            is NativeEngineControllerEvent.StopCompleted -> stopCompleted(event.requestId)
            NativeEngineControllerEvent.Closed -> observed(PlaybackPublicationEffect.Closed)
        }

    private fun outputUnavailable(
        event: NativeEngineControllerEvent.FatalRecoveryRequired
    ): PlaybackPublicationReduction {
        val committed = current.committed?.let { publication ->
            publication.copy(
                transport = publication.transport.copy(
                    desiredRevision = publication.transport.desiredRevision.nextSaturated(),
                    playWhenReady = false
                ),
                issue = PlaybackPublicationIssue(
                    category = "output-unavailable",
                    cause = event.diagnostic,
                    recoverable = false,
                    token = publication.selection.token,
                    generation = publication.owner.ownership.generation
                )
            )
        }
        val pending = current.pendingSelection?.let { publication ->
            publication.copy(
                transport = publication.transport.copy(
                    desiredRevision = publication.transport.desiredRevision.nextSaturated(),
                    playWhenReady = false
                ),
                issue = PlaybackPublicationIssue(
                    category = "output-unavailable",
                    cause = event.diagnostic,
                    recoverable = false,
                    token = publication.selection.token,
                    generation = publication.expectedOwnership.generation
                )
            )
        }
        current = current.copy(committed = committed, pendingSelection = pending)
        return applied(PlaybackPublicationEffect.OutputUnavailable(event.diagnostic))
    }

    private fun ownerTerminated(
        event: NativeEngineControllerEvent.OwnerTerminated
    ): PlaybackPublicationReduction {
        val committed = current.committed ?: return ignored()
        if (committed.owner.ownership != event.ownership) return ignored()
        current = current.copy(
            committed = null,
            pendingSelection = current.pendingSelection?.takeUnless {
                it.selection.token.value == event.ownership.selectionToken
            },
            acceptedAudibleEnd = null
        )
        return applied(PlaybackPublicationEffect.OwnerTerminated(event.ownership, event.cause))
    }

    private fun selectionFailed(
        event: NativeEngineControllerEvent.SelectionFailed
    ): PlaybackPublicationReduction {
        if (current.pendingSelection?.selection?.token != event.token) return ignored()
        current = current.copy(pendingSelection = null)
        return applied(PlaybackPublicationEffect.SelectionFailed(event.token, event.cause))
    }

    private fun loopRejected(
        event: NativeEngineControllerEvent.LoopNotReady
    ): PlaybackPublicationReduction {
        val known = current.committed?.selection?.token == event.token ||
            current.pendingSelection?.selection?.token == event.token
        return if (known) {
            observed(PlaybackPublicationEffect.LoopRejected(event.token, event.reason))
        } else {
            ignored()
        }
    }

    private fun ownerPublished(
        publication: NativeEngineOwnerPublication
    ): PlaybackPublicationReduction {
        val selection = publication.selection
        val pending = current.pendingSelection
        if (pending == null || pending.selection.token != selection.token) {
            return rebaseCommittedOwner(publication)
        }
        if (selection.token != pending.selection.token ||
            selection != pending.selection ||
            pending.expectedOwnership != publication.ownership
        ) return ignored()

        val evidence = publication.evidence
        if (evidence != null &&
            (evidence.token != selection.token || evidence.songId != selection.songId)
        ) return ignored()

        val eventMetadata = publication.metadata?.let(::mapMetadataOrNull)
        if (eventMetadata != null &&
            (eventMetadata.songId != selection.songId ||
                eventMetadata.audioRevision != selection.audioRevision)
        ) return ignored()
        val metadata = listOfNotNull(pending.metadata, eventMetadata)
            .maxBy(PlaybackPublicationMetadata::revision)
        val owner = PlaybackCommittedEngineOwner(
            ownership = publication.ownership,
            kind = publication.kind,
            callbackSequence = evidence?.callbackSequence
        )
        current = current.copy(
            committed = PlaybackCommittedPublication(
                selection = selection,
                owner = owner,
                metadata = metadata,
                transport = pending.transport.copy(
                    completedSeekRevision = publication.completedSeekRevision,
                    presentedPositionMs = if (publication.completedSeekRevision > 0L)
                        pending.transport.requestedPositionMs else pending.transport.presentedPositionMs
                ),
                source = pending.source,
                stems = pending.stems,
                issue = pending.issue
            ),
            pendingSelection = null,
            acceptedAudibleEnd = null
        )
        return applied()
    }

    /**
     * A seek, route rebuild, or same-token bank replacement advances native generation without
     * changing song identity. That transfer is still an owner publication: end/position evidence
     * from the retired generation must stop being authoritative immediately, even while a newer
     * song selection is pending.
     */
    private fun rebaseCommittedOwner(
        publication: NativeEngineOwnerPublication
    ): PlaybackPublicationReduction {
        val committed = current.committed ?: return ignored()
        val selection = publication.selection
        if (selection != committed.selection ||
            publication.ownership.selectionToken != selection.token.value ||
            publication.ownership.generation <= committed.owner.ownership.generation
        ) return ignored()

        val evidence = publication.evidence
        if (evidence != null &&
            (evidence.token != selection.token || evidence.songId != selection.songId)
        ) return ignored()
        val eventMetadata = publication.metadata?.let(::mapMetadataOrNull)
        if (eventMetadata != null &&
            (eventMetadata.songId != selection.songId ||
                eventMetadata.audioRevision != selection.audioRevision)
        ) return ignored()

        val metadata = listOfNotNull(committed.metadata, eventMetadata)
            .maxBy(PlaybackPublicationMetadata::revision)
        current = current.copy(
            committed = committed.copy(
                owner = PlaybackCommittedEngineOwner(
                    ownership = publication.ownership,
                    kind = publication.kind,
                    callbackSequence = evidence?.callbackSequence
                ),
                metadata = metadata,
                transport = committed.transport.copy(
                    completedSeekRevision = maxOf(committed.transport.completedSeekRevision, publication.completedSeekRevision),
                    presentedPositionMs = if (publication.completedSeekRevision > committed.transport.completedSeekRevision)
                        committed.transport.requestedPositionMs else committed.transport.presentedPositionMs
                ),
                // Prepared/applied source evidence belongs to one physical output generation.
                // Keep the user's desired source and canonical package identity, but require the
                // rebased owner to prove its actual source before UI or MediaSession exposes it.
                source = committed.source.copy(
                    preparedRevision = 0L,
                    preparedMode = EngineSourceMode.ORIGINAL,
                    appliedRevision = 0L,
                    appliedMode = EngineSourceMode.ORIGINAL,
                    notReadyReason = null
                ),
                issue = committed.issue?.takeUnless { issue ->
                    issue.recoverable && issue.generation != null &&
                        issue.generation < publication.ownership.generation
                }
            ),
            acceptedAudibleEnd = null
        )
        return applied()
    }

    private fun metadataUpdated(
        nativeMetadata: NativeEngineMetadata
    ): PlaybackPublicationReduction {
        val metadata = mapMetadataOrNull(nativeMetadata) ?: return ignored()
        var changed = false
        var committed = current.committed
        if (committed != null && metadata.matches(committed.selection) &&
            metadata.revision > committed.metadata.revision
        ) {
            committed = committed.copy(metadata = metadata)
            changed = true
        }
        var pending = current.pendingSelection
        if (pending != null && metadata.matches(pending.selection) &&
            metadata.revision > pending.metadata.revision
        ) {
            pending = pending.copy(metadata = metadata)
            changed = true
        }
        if (!changed) return ignored()
        current = current.copy(committed = committed, pendingSelection = pending)
        return applied()
    }

    private fun position(event: NativeEngineControllerEvent.Position): PlaybackPublicationReduction {
        val committed = current.committed ?: return ignored()
        if (committed.selection.token != event.token || event.positionMs < 0L ||
            event.ownership != null && event.ownership != committed.owner.ownership ||
            event.callbackSequence <= committed.transport.positionCallbackSequence
        ) return ignored()
        current = current.copy(
            committed = committed.copy(
                transport = committed.transport.copy(
                    presentedPositionMs = event.positionMs.coerceIn(
                        committed.selection.playbackStartMs, committed.selection.playbackEndMs
                    ),
                    positionCallbackSequence = event.callbackSequence
                )
            )
        )
        return applied()
    }

    private fun audibleEnd(
        event: NativeEngineControllerEvent.AudibleEnd
    ): PlaybackPublicationReduction {
        val committed = current.committed ?: return ignored()
        if (committed.selection.token != event.token ||
            committed.owner.ownership != event.ownership ||
            event.endSequence <= 0L ||
            current.acceptedAudibleEnd?.ownership == event.ownership
        ) return ignored()
        current = current.copy(
            acceptedAudibleEnd = PlaybackAudibleEndKey(event.ownership, event.endSequence)
        )
        return applied(
            PlaybackPublicationEffect.AudibleEnd(
                event.token,
                event.ownership,
                event.endSequence,

                event.sourceChangePending
            )
        )
    }

    private fun sourceModeApplied(
        event: NativeEngineControllerEvent.SourceModeApplied
    ): PlaybackPublicationReduction = updateTarget(event.token) { source, stems, issue ->
        if (event.desiredRevision != source.desiredRevision ||
            event.sourceMode != source.desiredMode
        ) return@updateTarget null
        TargetUpdate(
            source.copy(
                preparedRevision = event.desiredRevision,
                preparedMode = event.sourceMode,
                appliedRevision = event.desiredRevision,
                appliedMode = event.sourceMode,
                notReadyReason = null
            ),
            stems,
            issue
        )
    }

    private fun sourceModeReadyAwaitingAudible(
        event: NativeEngineControllerEvent.SourceModeReadyAwaitingAudible
    ): PlaybackPublicationReduction = updateTarget(event.token) { source, stems, issue ->
        if (event.desiredRevision != source.desiredRevision ||
            event.sourceMode != source.desiredMode ||
            event.desiredRevision < source.preparedRevision
        ) return@updateTarget null
        TargetUpdate(
            source.copy(
                preparedRevision = event.desiredRevision,
                preparedMode = event.sourceMode,
                notReadyReason = null
            ),
            stems,
            issue
        )
    }

    private fun sourceModeNotReady(
        event: NativeEngineControllerEvent.SourceModeNotReady
    ): PlaybackPublicationReduction = updateTarget(event.token) { source, stems, issue ->
        if (event.desiredRevision != source.desiredRevision ||
            event.requestedMode != source.desiredMode
        ) return@updateTarget null
        TargetUpdate(
            source.copy(notReadyReason = event.reason),
            stems,
            // A temporary renderer fallback while the requested lanes fill is preparation state,
            // not a package failure. StemPackageFailed/DecoderFailed remain the authoritative
            // failure paths; turning this normal gate into an issue leaves the UI falsely Failed
            // even after callback evidence later proves the requested source audible.
            issue
        )
    }

    private fun stemPreparing(
        event: NativeEngineControllerEvent.StemPackagePreparing
    ): PlaybackPublicationReduction = updateStemTarget(
        event.token,
        event.packageSignature,
        event.stemRevision,
        event.requestRevision
    ) { stems ->
        stems.copy(
            phase = PlaybackStemPackagePhase.PREPARING,
            retainedPriorPackage = event.replacingPackageSignature != null,
            failureCause = null
        )
    }

    private fun stemReadyAwaitingAudible(
        event: NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
    ): PlaybackPublicationReduction = updateStemTarget(
        event.token,
        event.packageSignature,
        event.stemRevision,
        event.requestRevision
    ) { stems ->
        // Readiness is deliberately not an install acknowledgement.
        stems.copy(
            phase = PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE,
            retainedPriorPackage = event.retainedPriorPackage,
            failureCause = null
        )
    }

    private fun stemInstalled(
        event: NativeEngineControllerEvent.StemPackageInstalled
    ): PlaybackPublicationReduction {
        if (event.callbackSequence <= 0L) return ignored()
        return updateStemTarget(
            event.token,
            event.packageSignature,
            event.stemRevision,
            event.requestRevision
        ) { stems ->
            stems.copy(
                installed = PlaybackInstalledStemPackage(
                    event.packageSignature,
                    event.stemRevision,
                    event.requestRevision,
                    event.callbackSequence
                ),
                phase = PlaybackStemPackagePhase.INSTALLED,
                retainedPriorPackage = false,
                failureCause = null
            )
        }
    }

    private fun stemFailed(
        event: NativeEngineControllerEvent.StemPackageFailed
    ): PlaybackPublicationReduction = updateTarget(event.token) { source, stems, issue ->
        val desired = stems.desired
        if (desired == null || desired.requestRevision != event.requestRevision ||
            desired.packageSignature != event.packageSignature ||
            desired.stemRevision != event.stemRevision
        ) return@updateTarget null
        TargetUpdate(
            source,
            stems.copy(
                installed = if (event.retainedPriorPackage) stems.installed else null,
                phase = PlaybackStemPackagePhase.FAILED,
                retainedPriorPackage = event.retainedPriorPackage,
                failureCause = event.cause
            ),
            if (source.desiredMode == EngineSourceMode.STEMS) {
                PlaybackPublicationIssue(
                    "stem-package",
                    event.cause,
                    event.recoverable,
                    event.token,
                    revision = event.requestRevision
                )
            } else {
                issue
            }
        )
    }

    private fun decoderFailed(
        event: NativeEngineControllerEvent.DecoderFailed
    ): PlaybackPublicationReduction {
        val pending = current.pendingSelection
        if (pending?.selection?.token == event.token) {
            if (pending.expectedOwnership.generation != event.generation) return ignored()
            if (event.laneIndex > 0 && pending.source.desiredMode == EngineSourceMode.ORIGINAL) {
                return ignored()
            }
            current = current.copy(
                pendingSelection = pending.copy(
                    issue = PlaybackPublicationIssue(
                        "decoder-${event.laneIndex}",
                        event.cause,
                        event.recoverable,
                        event.token,
                        event.generation
                    )
                )
            )
            return applied()
        }
        val committed = current.committed ?: return ignored()
        if (committed.selection.token != event.token ||
            committed.owner.ownership.generation != event.generation
        ) return ignored()
        if (event.laneIndex > 0 && committed.source.desiredMode == EngineSourceMode.ORIGINAL) {
            return ignored()
        }
        current = current.copy(
            committed = committed.copy(
                issue = PlaybackPublicationIssue(
                    "decoder-${event.laneIndex}",
                    event.cause,
                    event.recoverable,
                    event.token,
                    event.generation
                )
            )
        )
        return applied()
    }

    private fun updateStemTarget(
        token: EngineSelectionToken,
        signature: String,
        stemRevision: String,
        requestRevision: Long,
        transform: (PlaybackPublicationStemState) -> PlaybackPublicationStemState
    ): PlaybackPublicationReduction = updateTarget(token) { source, stems, issue ->
        val desired = stems.desired
        if (desired == null || desired.requestRevision != requestRevision ||
            desired.packageSignature != signature || desired.stemRevision != stemRevision
        ) return@updateTarget null
        TargetUpdate(source, transform(stems), issue)
    }

    private data class TargetUpdate(
        val source: PlaybackPublicationSourceState,
        val stems: PlaybackPublicationStemState,
        val issue: PlaybackPublicationIssue?
    )

    private fun updateTarget(
        token: EngineSelectionToken,
        transform: (
            PlaybackPublicationSourceState,
            PlaybackPublicationStemState,
            PlaybackPublicationIssue?
        ) -> TargetUpdate?
    ): PlaybackPublicationReduction {
        val pending = current.pendingSelection
        if (pending?.selection?.token == token) {
            val update = transform(pending.source, pending.stems, pending.issue)
                ?: return ignored()
            current = current.copy(
                pendingSelection = pending.copy(
                    source = update.source,
                    stems = update.stems,
                    issue = update.issue
                )
            )
            return applied()
        }
        val committed = current.committed ?: return ignored()
        if (committed.selection.token != token) return ignored()
        val update = transform(committed.source, committed.stems, committed.issue)
            ?: return ignored()
        current = current.copy(
            committed = committed.copy(
                source = update.source,
                stems = update.stems,
                issue = update.issue
            )
        )
        return applied()
    }

    private fun stopCompleted(
        requestId: NativeEngineStopRequestId
    ): PlaybackPublicationReduction {
        val pending = current.pendingStop ?: return ignored()
        if (pending.requestId != requestId) return ignored()
        if (pending.selectionSerial != current.selectionSerial) {
            current = current.copy(pendingStop = null)
            return PlaybackPublicationReduction(
                current,
                PlaybackPublicationDisposition.IGNORED_STALE,
                listOf(PlaybackPublicationEffect.StopCompletion(requestId, false))
            )
        }
        current = current.copy(
            committed = null,
            pendingSelection = null,
            pendingStop = null,
            acceptedAudibleEnd = null
        )
        return applied(PlaybackPublicationEffect.StopCompletion(requestId, true))
    }

    private fun mapMetadataOrNull(
        metadata: NativeEngineMetadata
    ): PlaybackPublicationMetadata? = try {
        metadataMapper.map(metadata).takeIf {
            it.songId == metadata.songId &&
                it.audioRevision == metadata.audioRevision &&
                it.revision == metadata.revision
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun fallbackMetadata(selection: EngineSelection) = PlaybackPublicationMetadata(
        songId = selection.songId,
        audioRevision = selection.audioRevision,
        revision = 1L
    )

    private fun PlaybackPublicationMetadata.matches(selection: EngineSelection): Boolean =
        songId == selection.songId && audioRevision == selection.audioRevision

    private fun applied(vararg effects: PlaybackPublicationEffect) =
        PlaybackPublicationReduction(
            current,
            PlaybackPublicationDisposition.APPLIED,
            effects.toList()
        )

    private fun ignored() = PlaybackPublicationReduction(
        current,
        PlaybackPublicationDisposition.IGNORED_STALE
    )

    private fun observed(effect: PlaybackPublicationEffect) = PlaybackPublicationReduction(
        current,
        PlaybackPublicationDisposition.OBSERVED_ONLY,
        listOf(effect)
    )
}

private fun Long.nextSaturated(): Long = if (this == Long.MAX_VALUE) this else this + 1L
