package app.stemwise.playback.session

import app.stemwise.playback.AudioPlaybackSuppression
import org.junit.Assert.assertEquals
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class AudioPlaybackSuppressionMappingTest {
    @Test
    fun everyAudioSuppressionValueHasOneExactMediaSessionMapping() {
        val expected = listOf(
            AudioPlaybackSuppression.NONE to SessionPlaybackSuppression.NONE,
            AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS to
                SessionPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS,
            AudioPlaybackSuppression.UNSUITABLE_AUDIO_ROUTE to
                SessionPlaybackSuppression.UNSUITABLE_AUDIO_ROUTE,
            AudioPlaybackSuppression.UNSUITABLE_AUDIO_OUTPUT to
                SessionPlaybackSuppression.UNSUITABLE_AUDIO_OUTPUT,
            AudioPlaybackSuppression.SCRUBBING to SessionPlaybackSuppression.SCRUBBING
        )

        assertEquals(5, AudioPlaybackSuppression.entries.size)
        assertEquals(5, SessionPlaybackSuppression.entries.size)
        assertEquals(AudioPlaybackSuppression.entries, expected.map(Pair<*, *>::first))
        assertEquals(SessionPlaybackSuppression.entries, expected.map(Pair<*, *>::second))
        expected.forEach { (audio, session) ->
            assertEquals(audio.name, session.name)
            assertEquals(session, audio.toSessionPlaybackSuppression())
        }
    }
}
