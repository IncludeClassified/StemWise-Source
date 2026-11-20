package app.stemwise.playback

import android.os.Handler
import android.os.Looper
import app.stemwise.data.StemAssetEntity
import app.stemwise.model.Song
import app.stemwise.playback.engine.AudioEngineAudit
import app.stemwise.playback.engine.NativeAudioPlaybackSessionEffect
import app.stemwise.playback.engine.NativeAudioPlaybackSessionState

/** Faults and source substitution exist only in Development. */
internal class PlaybackDebugAdapter(private val playback: AudioPlayback) : PlaybackTestAdapter {
    @Volatile var verifyPlaybackSong: (suspend (Song) -> Song)? = null
    @Volatile var verifyPlaybackStems: (suspend (String, List<StemAssetEntity>) -> List<StemAssetEntity>)? = null
    var beforeState: (suspend (NativeAudioPlaybackSessionState) -> Unit)? = null
    var beforeMetronomeRead: (suspend (Song) -> Unit)? = null
    var beforeSettingsWrite: (() -> Unit)? = null

    override suspend fun verifySong(song: Song): Song? = verifyPlaybackSong?.invoke(song)
    override suspend fun verifyStems(songId: String, stems: List<StemAssetEntity>): List<StemAssetEntity>? =
        verifyPlaybackStems?.invoke(songId, stems)
    override suspend fun beforeAdapterState(snapshot: NativeAudioPlaybackSessionState) {
        beforeState?.invoke(snapshot)
    }
    override suspend fun beforeSavedMetronomeRead(song: Song) {
        beforeMetronomeRead?.invoke(song)
    }
    override fun beforeSongSettingsWrite() {
        beforeSettingsWrite?.invoke()
    }

    fun enableInstrumentation() {
        playback.instrumentationRecorder.enable()
        playback.setDetailedPlaybackCapture(true)
    }
    fun loopBoundaryObservation() = AudioEngineAudit.loopBoundaryObservation(playback.adapter)
    fun injectNativeActorFatalFault(diagnostic: String): Boolean =
        AudioEngineAudit.injectActorFault(playback.adapter, diagnostic)
    fun holdNativeActorForSelectionBurst(): Boolean = AudioEngineAudit.holdActor(playback.adapter)
    fun releaseNativeActorForSelectionBurst() = AudioEngineAudit.releaseActor(playback.adapter)
    fun dispatchAudioFocusChange(change: Int) = playback.handleAudioFocusChange(change, "test")
    fun delayNextAudioFocusRequest() {
        check(!playback.playbackAudioFocusOwnedForTest())
        playback.platform.delayNextFocusRequestForTest()
    }

    /** Queue the real facade effect behind the current main-loop turn for race qualification. */
    fun enqueueCurrentAudibleEnd(sourceChangePending: Boolean = false): Boolean {
        val committed = playback.adapter.snapshot().publication.committed ?: return false
        Handler(Looper.getMainLooper()).post {
            playback.handleAdapterEffect(NativeAudioPlaybackSessionEffect.AudibleEnd(
                committed.selection.token, committed.owner.ownership,
                committed.transport.positionCallbackSequence, sourceChangePending))
        }
        return true
    }
}

internal val AudioPlayback.debug: PlaybackDebugAdapter
    get() = (testAdapter as? PlaybackDebugAdapter) ?: PlaybackDebugAdapter(this).also { testAdapter = it }

internal fun AudioPlayback.setDiagnosticPayloadFingerprintsForTest(enabled: Boolean) =
    AudioEngineAudit.setPayloadFingerprints(adapter, enabled)
