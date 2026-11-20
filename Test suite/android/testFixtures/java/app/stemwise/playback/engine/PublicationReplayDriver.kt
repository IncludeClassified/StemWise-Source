package app.stemwise.playback.engine

/** App integration tests can replay publication without access to mutable production owners. */
class PublicationReplayDriver {
    private val reducer = PlaybackPublicationReducer()
    fun requestSelection(selection: EngineSelection) {
        reducer.requestSelection(PlaybackPublicationSelectionRequest(selection,
            NativeRuntimeOwnership(selection.token.value, selection.token.value)))
    }
    fun commitPaused(selection: EngineSelection) {
        reducer.onControllerEvent(NativeEngineControllerEvent.OwnerPublished(
            NativeEngineOwnerPublication(selection,
                NativeRuntimeOwnership(selection.token.value, selection.token.value), null,
                NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED, null)))
    }
    fun requestTransport(token: EngineSelectionToken, revision: Long, playing: Boolean, positionMs: Long) {
        reducer.requestTransport(token, revision, playing, positionMs)
    }
    fun snapshot(): PlaybackPublicationReducerState = reducer.snapshot()

    private val postedEffects = java.util.ArrayDeque<NativeAudioPlaybackSessionEffect>()
    private val sessionEngine = ReplaySessionEngine()
    private val session by lazy {
        NativeAudioPlaybackSessionAdapter(
            engineFactory = { listener -> sessionEngine.listener = listener; sessionEngine },
            effectListener = NativeAudioPlaybackSessionEffectListener(postedEffects::addLast)
        )
    }
    private var callbackSequence = 0L

    fun selectSession(songId: String): EngineSelectionToken = checkNotNull(session.trySelect(
        NativeEngineSelectionRequest(songId, "audio-$songId", "/songs/$songId.flac", 60_000L, autoplay = true),
        PlaybackPublicationMetadata(songId, "audio-$songId", 1L)))

    /** Controlled native-boundary input; this fixture establishes no JNI or PCM correctness. */
    fun presentSession(completedSeekRevision: Long = 0L) {
        val selection = checkNotNull(sessionEngine.desired).selection
        val callback = ++callbackSequence
        sessionEngine.listener.onEvent(NativeEngineControllerEvent.OwnerPublished(
            NativeEngineOwnerPublication(selection, NativeRuntimeOwnership(selection.token.value, callback),
                NativeEngineMetadata(selection.songId, selection.audioRevision, 1L),
                NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED,
                EngineAudibleOwnerEvidence(selection.token, selection.songId, callback,
                    firstOutputFrame = 0L, finalPcmFrames = 192, outputFingerprintSequence = callback),
                completedSeekRevision)))
    }

    fun postSessionEnd() {
        val owner = checkNotNull(session.snapshot().publication.committed)
        sessionEngine.listener.onEvent(NativeEngineControllerEvent.AudibleEnd(
            owner.selection.token, 1L, owner.owner.ownership))
    }

    fun takePostedSessionEnd(): NativeAudioPlaybackSessionEffect.AudibleEnd {
        while (postedEffects.isNotEmpty()) {
            val effect = postedEffects.removeFirst()
            if (effect is NativeAudioPlaybackSessionEffect.AudibleEnd) return effect
        }
        error("No reducer-emitted AudibleEnd effect was posted")
    }

    fun seekSession(positionMs: Long): NativeAudioPlaybackSeekReceipt? =
        session.seekToIfCurrent(checkNotNull(session.acquireCommandLease()), positionMs)
    fun pauseSession(): Boolean = session.pause()
    fun sessionSnapshot(): NativeAudioPlaybackSessionState = session.snapshot()
    fun sessionPauseCalls(): Int = sessionEngine.pauseCalls
    fun closeSession() = session.close()

    private class ReplaySessionEngine : NativeAudioPlaybackSessionEngine {
        lateinit var listener: NativeEngineControllerListener
        var desired: EngineDesiredState? = null
        var pauseCalls = 0
        private var selectionSequence = 0L

        override fun select(request: NativeEngineSelectionRequest, metadata: NativeEngineMetadata): EngineSelectionToken {
            val token = EngineSelectionToken(++selectionSequence)
            desired = EngineDesiredState(EngineSelection(token, request.songId, request.audioRevision,
                request.originalLocation, request.durationMs, request.startPositionMs, request.autoplay), 1L)
            return token
        }
        override fun updateDesired(token: EngineSelectionToken, transform: (EngineDesiredState) -> EngineDesiredState): Boolean {
            val previous = desired?.takeIf { it.selection.token == token } ?: return false
            desired = acceptedEngineDesiredState(previous, transform(previous))
            return true
        }
        override fun updateDesiredIfRevision(token: EngineSelectionToken, expectedRevision: Long,
            transform: (EngineDesiredState) -> EngineDesiredState): EngineDesiredState? {
            if (desired?.revision != expectedRevision) return null
            return if (updateDesired(token, transform)) desired else null
        }
        override fun updateDesiredWithAudibleTransportIfRevision(token: EngineSelectionToken, expectedRevision: Long,
            playWhenReady: Boolean, transform: (EngineDesiredState) -> EngineDesiredState): EngineDesiredState? =
            updateDesiredIfRevision(token, expectedRevision, transform)?.also { if (!playWhenReady) pauseCalls++ }
        override fun updateExplicitLoop(state: EngineExplicitLoopState): Boolean = error("Unexpected loop update")
        override fun updateMetadata(metadata: NativeEngineMetadata) = Unit
        override fun setAutomaticEndPlan(plan: NativeEngineAutomaticEndPlan?) = Unit
        override fun suspendForAudioFocusLoss(): Unit = error("Unexpected focus loss")
        override fun resumeAfterAudioFocusGain(): Unit = error("Unexpected focus gain")
        override fun requestRouteRecovery(): Unit = error("Unexpected route recovery")
        override fun stopForTaskRemoval(): NativeEngineStopRequest = error("Unexpected task removal")
        override fun close() = Unit
    }

}

object AudioContractProbe {
    fun mapMetadata(metadata: NativeEngineMetadata): PlaybackPublicationMetadata =
        PlaybackPublicationMetadataMapper.Default.map(metadata)
}
