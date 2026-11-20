package app.stemwise.playback

import app.stemwise.data.RootAuthority
import app.stemwise.playback.engine.EngineAudioIdentity
import app.stemwise.playback.engine.EngineSelectionToken
import app.stemwise.playback.engine.NativeEngineAutomaticEndPlan
import app.stemwise.playback.engine.NativeEngineQueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Metadata is deliberately absent: refreshes may update a ready plan without reopening its PCM. */
internal data class AutomaticQueuePrewarmRequest(
    val selectionSerial: Long,
    val owner: EngineSelectionToken?,
    val currentIdentity: EngineAudioIdentity?,
    val decision: QueueDecision?,
    val fallback: QueueDecision.Move?,
    val root: RootAuthority?,
    val audio: List<Pair<String, EngineAudioIdentity?>>,
    val automaticSongIds: Set<String>? = null
)

internal interface AutomaticQueuePrewarmSink {
    suspend fun verify(identity: EngineAudioIdentity): NativeEngineQueueItem?
    fun stillCurrent(request: AutomaticQueuePrewarmRequest): Boolean
    fun refreshMetadata()
    fun refreshed(item: NativeEngineQueueItem): NativeEngineQueueItem
    fun publish(plan: NativeEngineAutomaticEndPlan?)
    fun predict(item: NativeEngineQueueItem?)
}

/** Owns only one verification flight. Queue policy and the authoritative session remain callers. */
internal class AutomaticQueuePrewarmOwner(
    private val scope: CoroutineScope,
    private val sink: AutomaticQueuePrewarmSink
) {
    private sealed interface State {
        data object Idle : State
        class Verifying(val request: AutomaticQueuePrewarmRequest) : State
        class Ready(val request: AutomaticQueuePrewarmRequest) : State
        class Failed(val request: AutomaticQueuePrewarmRequest) : State
    }

    private var state: State = State.Idle
    private var job: Job? = null
    private val request: AutomaticQueuePrewarmRequest?
        get() = when (val current = state) {
            State.Idle -> null
            is State.Verifying -> current.request
            is State.Ready -> current.request
            is State.Failed -> current.request
        }

    fun update(next: AutomaticQueuePrewarmRequest, availabilityRefreshed: Boolean = true) {
        if (request == next) {
            sink.refreshMetadata()
            if (state !is State.Failed || !availabilityRefreshed) return
        } else {
            invalidate()
        }
        val flight = State.Verifying(next)
        state = flight
        job = scope.launch {
            val verified = next.audio.mapNotNull { (id, identity) ->
                identity?.let { sink.verify(it) }?.takeIf { it.identity == identity }?.let { id to it }
            }.toMap()
            // An equal request can recur after cancellation. Only this exact flight owns admission.
            if (!isActive || state !== flight) return@launch
            if (!sink.stillCurrent(next)) {
                state = State.Failed(next)
                return@launch
            }
            val refreshed = verified.mapValues { (_, item) -> sink.refreshed(item) }
            val automatic = refreshed.filterKeys { next.automaticSongIds == null || it in next.automaticSongIds }
            val plan = next.decision?.let { decision ->
                next.currentIdentity?.let { current -> automaticEndPlanForDecision(
                    decision, current, next.fallback?.mediaId?.let(automatic::get), automatic::get)
                }
            }
            state = if (verified.size == next.audio.size) State.Ready(next) else State.Failed(next)
            sink.publish(plan)
            sink.predict(when (plan) {
                is NativeEngineAutomaticEndPlan.NextOwner -> plan.item
                is NativeEngineAutomaticEndPlan.RestartCurrent -> plan.fallbackNext
                null -> (next.decision as? QueueDecision.Move)?.mediaId?.let(refreshed::get)
                    ?: next.fallback?.mediaId?.let(refreshed::get)
            })
        }
    }

    fun invalidateForSong(songId: String) {
        if (request?.currentIdentity?.songId == songId || request?.audio?.any { it.first == songId } == true) invalidate()
    }

    fun invalidate() {
        state = State.Idle
        job?.cancel()
        job = null
        sink.publish(null)
        sink.predict(null)
    }
}
