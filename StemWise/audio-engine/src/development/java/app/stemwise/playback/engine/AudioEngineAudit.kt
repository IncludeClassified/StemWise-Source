package app.stemwise.playback.engine

/** Controls compile only into Development. */
object AudioEngineAudit {
    fun setDetailedCapture(engine: NativeAudioPlaybackSessionAdapter, enabled: Boolean): Boolean =
        engine.setDetailedDiagnosticCapture(enabled)
    fun loopBoundaryObservation(engine: NativeAudioPlaybackSessionAdapter): Pair<NativeRuntimePresentedPosition?, NativeAudioDiagnostics?>? =
        engine.loopBoundaryObservationForTest()
    fun injectActorFault(engine: NativeAudioPlaybackSessionAdapter, diagnostic: String): Boolean =
        engine.injectFatalActorFaultForTest(diagnostic)
    fun holdActor(engine: NativeAudioPlaybackSessionAdapter): Boolean = engine.holdActorForSelectionBurstForTest()
    fun releaseActor(engine: NativeAudioPlaybackSessionAdapter) = engine.releaseActorForSelectionBurstForTest()
    fun setPayloadFingerprints(engine: NativeAudioPlaybackSessionAdapter, enabled: Boolean) =
        engine.setDiagnosticPayloadFingerprints(enabled)
}
