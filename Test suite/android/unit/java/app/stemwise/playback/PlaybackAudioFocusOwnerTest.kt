package app.stemwise.playback

import android.media.AudioManager
import app.stemwise.playback.engine.*
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlaybackAudioFocusOwnerTest {
    @Test fun failedReacquisitionRetiresResumeAuthorityAndAllowsANewRequest() {
        val host = Host()
        val owner = PlaybackAudioFocusOwner(host)
        assertTrue(owner.request())
        owner.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(41L, owner.resumeAfterAudioFocusGainToken)
        host.requestResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        assertFalse(owner.request())
        assertFalse(owner.audioFocusRequested)
        assertNull(owner.resumeAfterAudioFocusGainToken)
        owner.onChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(owner.audioFocusHeld)
        host.requestResult = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        assertTrue(owner.request())
        assertTrue(owner.audioFocusRequested)
        host.requestResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        assertTrue(owner.request())
        assertFalse(owner.focusSuspended)
    }

    @Test fun delayedTerminationPreservesGrantedAndDelayedFocusForAPendingSuccessor() {
        for (result in listOf(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, AudioManager.AUDIOFOCUS_REQUEST_DELAYED)) {
            val host = Host().apply { requestResult = result }
            val owner = PlaybackAudioFocusOwner(host)
            assertTrue(owner.request())
            host.session = session(42)
            host.commands.clear()
            assertFalse(owner.retireTerminatedOwner(41, resolvingSuccessor = false))
            assertTrue(owner.audioFocusRequested)
            assertEquals(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, owner.audioFocusHeld)
            assertEquals(result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED, owner.focusSuspended)
            assertTrue(host.commands.isEmpty())
            host.session = session(41)
            assertFalse(owner.retireTerminatedOwner(41, resolvingSuccessor = true))
            assertTrue(host.commands.isEmpty())
            assertTrue(owner.retireTerminatedOwner(41, resolvingSuccessor = false))
            assertFalse(owner.audioFocusRequested)
            assertFalse(owner.focusSuspended)
            assertTrue(host.commands.contains("abandon"))
        }
    }

    @Test fun selectionDuringTransientLossDoesNotInheritTheOutgoingResumeProof() {
        val host = Host()
        val owner = PlaybackAudioFocusOwner(host)
        assertTrue(owner.request())
        owner.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(41L, owner.resumeAfterAudioFocusGainToken)
        assertEquals(AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS, host.state.suppression)
        host.session = session(42)
        owner.onChange(AudioManager.AUDIOFOCUS_GAIN)
        assertNull(owner.resumeAfterAudioFocusGainToken)
        assertNull(owner.focusResumeProof)
        assertFalse(host.state.playing)
        assertEquals(AudioPlaybackSuppression.NONE, host.state.suppression)
        assertEquals(listOf("suspend", "resume"), host.commands)
    }

    @Test fun permanentLossRetiresResolvingPlayBeforeClearingGlobalSuspension() {
        val host = Host()
        val owner = PlaybackAudioFocusOwner(host)
        owner.request()
        owner.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        host.commands.clear()
        owner.onChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(listOf("retire-resolving-play", "pause", "resume", "clear-transport"), host.commands)
        assertFalse(host.state.playWhenReady)
        assertFalse(owner.audioFocusRequested)
        assertFalse(owner.focusSuspended)
        host.commands.clear()
        owner.onChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(host.commands.isEmpty())
    }

    @Test fun synchronousFreshGrantClearsSuspensionWithoutWaitingForAGainCallback() {
        val host = Host().apply { requestResult = AudioManager.AUDIOFOCUS_REQUEST_DELAYED }
        val owner = PlaybackAudioFocusOwner(host)
        assertTrue(owner.request())
        assertTrue(owner.focusSuspended)
        host.requestResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        assertTrue(owner.request())
        assertTrue(owner.audioFocusHeld)
        assertFalse(owner.focusSuspended)
        assertEquals(listOf("suspend", "resume"), host.commands)
    }

    private class Host : PlaybackAudioFocusHost {
        override var state = AudioPlaybackState(playing = true, playWhenReady = true)
        var session = session(41)
        var requestResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        val commands = mutableListOf<String>()
        override fun snapshot() = session
        override fun commandsBlocked() = false
        override fun requestPlatformFocus() = requestResult
        override fun cancelFailedSelectionRelease() = Unit
        override fun prepareAbandon() = Unit
        override fun abandonPlatformFocus() { commands += "abandon" }
        override fun suspendOutput() { commands += "suspend" }
        override fun resumeOutput() { commands += "resume" }
        override fun pauseOutput() { commands += "pause" }
        override fun cancelScrub() = Unit
        override fun retireResolvingPlay() { commands += "retire-resolving-play" }
        override fun clearTransportIntent() { commands += "clear-transport" }
        override fun record(event: String) = Unit
        override fun publish(state: AudioPlaybackState) { this.state = state }
    }

    companion object {
        private fun session(token: Long): NativeAudioPlaybackSessionState {
            val selection = EngineSelection(EngineSelectionToken(token), "song-$token", "audio", "content://$token", 10_000, 0, true)
            val metadata = PlaybackPublicationMetadata(selection.songId, selection.audioRevision, 1)
            val metronome = NativeAudioPlaybackMetronomeSnapshot(false, false, 120f, 0f, 1, 4, 4, emptyList(), emptyList(), emptyList())
            val controls = NativeAudioPlaybackControlSnapshot(1, true, 0, 0, EngineSourceMode.ORIGINAL,
                emptyList(), emptyMap(), 1f, 0, null, metronome)
            return NativeAudioPlaybackSessionState(latestRequest = NativeAudioPlaybackRequestedOwner(selection, metadata, null, controls))
        }
    }
}
