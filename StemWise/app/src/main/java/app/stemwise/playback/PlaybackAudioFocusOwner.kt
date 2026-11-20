package app.stemwise.playback

import android.media.AudioManager
import app.stemwise.playback.engine.NativeAudioPlaybackSessionState

internal data class FocusResumeProof(
    val ownerToken: Long,
    val callbackSequenceAtGain: Long
)

internal interface PlaybackAudioFocusHost {
    val state: AudioPlaybackState
    fun snapshot(): NativeAudioPlaybackSessionState
    fun commandsBlocked(): Boolean
    fun requestPlatformFocus(): Int
    fun cancelFailedSelectionRelease()
    fun prepareAbandon()
    fun abandonPlatformFocus()
    fun suspendOutput()
    fun resumeOutput()
    fun pauseOutput()
    fun cancelScrub()
    fun retireResolvingPlay()
    fun clearTransportIntent()
    fun record(event: String)
    fun publish(state: AudioPlaybackState)
}

/** Owns focus permission and exact-token resume proof; session selection remains authoritative. */
internal class PlaybackAudioFocusOwner(private val host: PlaybackAudioFocusHost) {
    var audioFocusRequested = false
    var audioFocusHeld = false
    var resumeAfterAudioFocusGainToken: Long? = null
    var focusResumeProof: FocusResumeProof? = null
    var focusSuspended = false
    val awaitingFocusGain: Boolean get() = audioFocusRequested && focusSuspended

    fun request(): Boolean {
        host.cancelFailedSelectionRelease()
        if (audioFocusHeld) return true
        val result = host.requestPlatformFocus()
        audioFocusRequested = result != AudioManager.AUDIOFOCUS_REQUEST_FAILED
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!audioFocusRequested) {
            resumeAfterAudioFocusGainToken = null
            focusResumeProof = null
        }
        host.record("focus-request-result=$result")
        if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            // Preserve the requested song's Play intent while Android temporarily owns output.
            // GAIN resumes this exact token through the existing suspension fence.
            focusSuspended = true
            host.suspendOutput()
        }
        if (audioFocusHeld && focusSuspended) {
            // Android may grant a fresh request synchronously without issuing a matching GAIN
            // callback. Clear the controller-owned suspension at the successful request boundary.
            focusSuspended = false
            resumeAfterAudioFocusGainToken = null
            focusResumeProof = null
            host.resumeOutput()
        }
        return audioFocusHeld || result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
    }

    /** Returns whether the terminated owner still owns every presentation side effect. */
    fun retireTerminatedOwner(ownerToken: Long, resolvingSuccessor: Boolean): Boolean {
        val snapshot = host.snapshot()
        if (resolvingSuccessor || snapshot.publication.committed != null ||
            snapshot.latestRequest?.selection?.token?.value?.let { it != ownerToken } == true) return false
        abandon()
        return true
    }

    fun abandon() {
        host.prepareAbandon()
        if (audioFocusRequested || audioFocusHeld || focusSuspended) {
            host.record("focus-abandon")
        }
        // The controller's focus suppression is global, not a play request. Clear it after the
        // latest desired transport has already been paused so a later fresh Play cannot inherit a
        // stale suspended runtime when Android sends no matching GAIN callback.
        if (focusSuspended) host.resumeOutput()
        if (audioFocusRequested) {
            host.abandonPlatformFocus()
        }
        audioFocusRequested = false
        audioFocusHeld = false
        resumeAfterAudioFocusGainToken = null
        focusResumeProof = null
        focusSuspended = false
    }

    fun onChange(change: Int, origin: String = "platform") {
        host.record("focus-change=$change origin=$origin")
        if (host.commandsBlocked()) {
            resumeAfterAudioFocusGainToken = null
            focusResumeProof = null
            focusSuspended = false
            abandon()
            return
        }
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!audioFocusRequested) return
                audioFocusHeld = true
                val wasSuspended = focusSuspended
                val snapshot = host.snapshot()
                val latest = snapshot.latestRequest
                val authorizedToken = resumeAfterAudioFocusGainToken
                val authorized = wasSuspended && authorizedToken != null &&
                    latest?.selection?.token?.value == authorizedToken &&
                    latest.controls.playWhenReady
                host.record("focus-gain-authorized=$authorized")
                focusSuspended = false
                resumeAfterAudioFocusGainToken = null
                focusResumeProof = if (authorized) {
                    snapshot.publication.committed?.takeIf { committed ->
                        committed.selection.token == latest.selection.token
                    }?.let { committed ->
                        FocusResumeProof(
                            ownerToken = committed.selection.token.value,
                            callbackSequenceAtGain = committed.transport.positionCallbackSequence
                        )
                    }
                } else {
                    null
                }
                if (wasSuspended) {
                    host.resumeOutput()
                }
                if (wasSuspended || host.state.suppression != AudioPlaybackSuppression.NONE) {
                    host.publish(
                        host.state.copy(
                            // GAIN is permission to resume, not callback proof that PCM from the
                            // exact owner has reached output. The adapter publication clears this
                            // gate after a newer callback sequence arrives.
                            playing = if (wasSuspended) false else host.state.playing,
                            suppression = AudioPlaybackSuppression.NONE
                        )
                    )
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                host.cancelScrub()
                val latest = host.snapshot().latestRequest
                resumeAfterAudioFocusGainToken = latest
                    ?.takeIf { it.controls.playWhenReady }
                    ?.selection
                    ?.token
                    ?.value
                focusResumeProof = null
                audioFocusHeld = false
                focusSuspended = true
                host.suspendOutput()
                host.publish(
                    host.state.copy(
                        playing = false,
                        suppression = AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS
                    )
                )
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                host.cancelScrub()
                // Permanent loss also retires Play for a newer song still resolving locally.
                // Otherwise that resolver can reacquire focus and start without another Play tap.
                host.retireResolvingPlay()
                val wasSuspended = focusSuspended
                audioFocusHeld = false
                audioFocusRequested = false
                focusSuspended = false
                resumeAfterAudioFocusGainToken = null
                focusResumeProof = null
                host.pauseOutput()
                // Set the durable transport false before clearing global suspension. Every actor
                // snapshot between these commands therefore remains silent.
                if (wasSuspended) host.resumeOutput()
                host.clearTransportIntent()
                host.publish(
                    host.state.copy(
                        playing = false,
                        playWhenReady = false,
                        suppression = AudioPlaybackSuppression.NONE
                    )
                )
            }
        }
    }

}
