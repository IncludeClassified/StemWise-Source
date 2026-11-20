package app.stemwise.ui.player.nativeplayer

internal enum class FullPlayerAdoptionResult {
    APPLIED_INITIAL,
    APPLIED_NEW_OWNER,
    APPLIED_UPDATE,
    UNCHANGED,
    REJECTED_INCOHERENT,
    REJECTED_STALE_OWNER,
    REJECTED_OWNER_COLLISION,
    REJECTED_STALE_REVISION,
    REJECTED_REVISION_COLLISION,
    REJECTED_COMPONENT_REGRESSION,
    REJECTED_COMPONENT_COLLISION
}

/**
 * UI-thread atomic snapshot owner. Rejection never changes the visible snapshot.
 *
 * A component revision identifies its immutable payload. Supplying a different object with the
 * same component revision is rejected because accepting it would make that revision ambiguous.
 */
internal class FullPlayerStateStore {
    var snapshot: FullPlayerSnapshot? = null
        private set

    fun adopt(next: FullPlayerSnapshot): FullPlayerAdoptionResult {
        if (FullPlayerSnapshotValidator.validate(next, previouslyValidated = snapshot) != null) {
            return FullPlayerAdoptionResult.REJECTED_INCOHERENT
        }
        val current = snapshot
        if (current == null) {
            snapshot = next
            return FullPlayerAdoptionResult.APPLIED_INITIAL
        }

        val currentGeneration = current.owner.generation
        val nextGeneration = next.owner.generation
        if (nextGeneration < currentGeneration) {
            return FullPlayerAdoptionResult.REJECTED_STALE_OWNER
        }
        if (nextGeneration == currentGeneration && !current.owner.sameIdentity(next.owner)) {
            return FullPlayerAdoptionResult.REJECTED_OWNER_COLLISION
        }
        if (next.revision < current.revision) {
            return FullPlayerAdoptionResult.REJECTED_STALE_REVISION
        }
        if (next.revision == current.revision) {
            return if (fullPlayerPublicationsAreIdentical(current, next)) {
                FullPlayerAdoptionResult.UNCHANGED
            } else {
                FullPlayerAdoptionResult.REJECTED_REVISION_COLLISION
            }
        }

        // Component revision identities are process-wide, not owner-local. A new owner may reuse
        // the same catalog/queue objects, but it cannot redefine or regress any revision.
        if (hasComponentRegression(current, next)) {
            return FullPlayerAdoptionResult.REJECTED_COMPONENT_REGRESSION
        }
        if (hasComponentCollision(current, next)) {
            return FullPlayerAdoptionResult.REJECTED_COMPONENT_COLLISION
        }

        snapshot = next
        return if (nextGeneration == currentGeneration) {
            FullPlayerAdoptionResult.APPLIED_UPDATE
        } else {
            FullPlayerAdoptionResult.APPLIED_NEW_OWNER
        }
    }

    fun clear() {
        snapshot = null
    }

    private fun hasComponentRegression(
        current: FullPlayerSnapshot,
        next: FullPlayerSnapshot
    ): Boolean =
        next.catalog.revision < current.catalog.revision ||
            next.queue.revision < current.queue.revision ||
            next.selection.revision < current.selection.revision ||
            next.timeline.revision < current.timeline.revision ||
            next.waveform.revision < current.waveform.revision ||
            fineWindowRegressed(current.fineWindow, next.fineWindow) ||
            next.sourceMixer.revision < current.sourceMixer.revision ||
            next.transport.revision < current.transport.revision ||
            next.toolDetail.revision < current.toolDetail.revision ||
            next.status.revision < current.status.revision

    private fun fineWindowRegressed(
        current: FullPlayerFineWindowPacket?,
        next: FullPlayerFineWindowPacket?
    ): Boolean = current != null && next != null && next.revision < current.revision

    private fun hasComponentCollision(
        current: FullPlayerSnapshot,
        next: FullPlayerSnapshot
    ): Boolean =
        collision(current.catalog.revision, next.catalog.revision, current.catalog, next.catalog) ||
            collision(current.queue.revision, next.queue.revision, current.queue, next.queue) ||
            valueCollision(
                current.selection.revision,
                next.selection.revision,
                current.selection,
                next.selection
            ) ||
            valueCollision(
                current.timeline.revision,
                next.timeline.revision,
                current.timeline,
                next.timeline
            ) ||
            collision(
                current.waveform.revision,
                next.waveform.revision,
                current.waveform,
                next.waveform
            ) ||
            nullableCollision(current.fineWindow, next.fineWindow) ||
            collision(
                current.sourceMixer.revision,
                next.sourceMixer.revision,
                current.sourceMixer,
                next.sourceMixer
            ) ||
            valueCollision(
                current.transport.revision,
                next.transport.revision,
                current.transport,
                next.transport
            ) ||
            valueCollision(
                current.toolDetail.revision,
                next.toolDetail.revision,
                current.toolDetail,
                next.toolDetail
            ) ||
            valueCollision(
                current.status.revision,
                next.status.revision,
                current.status,
                next.status
            )

    private fun collision(
        currentRevision: Long,
        nextRevision: Long,
        current: Any,
        next: Any
    ): Boolean = currentRevision == nextRevision && current !== next

    private fun valueCollision(
        currentRevision: Long,
        nextRevision: Long,
        current: Any,
        next: Any
    ): Boolean = currentRevision == nextRevision && current != next

    private fun nullableCollision(
        current: FullPlayerFineWindowPacket?,
        next: FullPlayerFineWindowPacket?
    ): Boolean = when {
        current == null || next == null -> false
        else -> collision(current.revision, next.revision, current, next)
    }
}

/** The single immutable-publication identity predicate used by the store and surface fast paths. */
internal fun fullPlayerPublicationsAreIdentical(
    a: FullPlayerSnapshot,
    b: FullPlayerSnapshot
): Boolean =
    a.owner == b.owner &&
        a.catalog === b.catalog &&
        a.queue === b.queue &&
        a.selection == b.selection &&
        a.timeline == b.timeline &&
        a.waveform === b.waveform &&
        a.fineWindow === b.fineWindow &&
        a.sourceMixer === b.sourceMixer &&
        a.transport == b.transport &&
        a.toolDetail == b.toolDetail &&
        a.status == b.status &&
        a.visualSelectionSongId == b.visualSelectionSongId
