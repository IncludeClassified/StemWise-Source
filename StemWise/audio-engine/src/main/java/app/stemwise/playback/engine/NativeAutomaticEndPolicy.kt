package app.stemwise.playback.engine

/** Queue audio authority excludes mutable presentation metadata and duration hints. */
internal fun sameAutomaticEndAudioDecision(
    left: NativeEngineAutomaticEndPlan?,
    right: NativeEngineAutomaticEndPlan?
): Boolean = when {
    left == null || right == null -> left == null && right == null
    left is NativeEngineAutomaticEndPlan.NextOwner && right is NativeEngineAutomaticEndPlan.NextOwner ->
        left.item.identity == right.item.identity
    left is NativeEngineAutomaticEndPlan.RestartCurrent && right is NativeEngineAutomaticEndPlan.RestartCurrent ->
        left.identity == right.identity && left.fallbackNext?.identity == right.fallbackNext?.identity
    else -> false
}

/** Actor-owned queue policy and corrupt-source latch; bank/decoder lifetimes stay with the controller. */
internal class NativeAutomaticEndPolicy {
    private data class Failure(val identity: EngineAudioIdentity, val incarnation: Long)
    private var failure: Failure? = null
    fun mayPrewarm(identity: EngineAudioIdentity, incarnation: Long) = failure != Failure(identity, incarnation)
    fun failed(identity: EngineAudioIdentity, incarnation: Long) { failure = Failure(identity, incarnation) }
    fun succeeded(identity: EngineAudioIdentity, incarnation: Long) {
        if (failure == Failure(identity, incarnation)) failure = null
    }
    fun retainedItem(plan: NativeEngineAutomaticEndPlan?, manual: NativeEngineManualPrewarmRequest?,
        latestToken: EngineSelectionToken?): NativeEngineQueueItem? {
        manual?.takeIf { it.ownerToken == latestToken }?.let { return it.item }
        return when (plan) {
            is NativeEngineAutomaticEndPlan.NextOwner -> plan.item
            is NativeEngineAutomaticEndPlan.RestartCurrent -> plan.fallbackNext
            null -> null
        }
    }
    // Repeat One may prewarm a fallback but cannot cross its owner's drain boundary.
    fun successor(plan: NativeEngineAutomaticEndPlan?): NativeEngineQueueItem? =
        (plan as? NativeEngineAutomaticEndPlan.NextOwner)?.item
}
