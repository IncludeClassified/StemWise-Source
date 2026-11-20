package app.stemwise.data

import app.stemwise.playback.PlaybackQueueController
import app.stemwise.playback.PlaybackQueueSnapshot
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.QueueDecision
import app.stemwise.playback.QueueTransition
import app.stemwise.playback.RepeatMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LastListeningSessionTest {
    private val session = LastListeningSession(
        SongPlaybackSettingsIdentity("library-a", "content://fixture/root-a", "song-a", "a".repeat(64)),
        PlaybackQueueSnapshot(listOf("song-c", "song-a", "song-b"), 1, true, Long.MIN_VALUE, RepeatMode.ALL),
        12_345L, PlaybackSourceMode.STEMS
    )

    @Test fun exactIdentitySourceClockAndQueueSurviveSerializationIntoCurrentQueueConsumer() {
        val restored = parseLastListeningSession(JSONObject(lastListeningSessionJson(session).toString()))
        assertEquals(session, restored)
        val queue = PlaybackQueueController()
        queue.restore(restored.queue)
        assertEquals(QueueDecision.Move("song-b", 2), queue.preview(QueueTransition.EXPLICIT_NEXT))
        assertEquals(QueueDecision.Move("song-c", 0), queue.preview(QueueTransition.EXPLICIT_PREVIOUS))
        for (identity in listOf(session.identity.copy(libraryId = "other"),
            session.identity.copy(treeUri = "content://fixture/other"),
            session.identity.copy(originalSha256 = "b".repeat(64)))) {
            assertNotEquals(identity, restored.identity)
        }
        assertEquals(PlaybackSourceMode.ORIGINAL, session.copy(sourceMode = PlaybackSourceMode.ORIGINAL).sourceMode)
        assertEquals(0L, parseLastListeningSession(lastListeningSessionJson(session.copy(sourcePositionMs = 0L))).sourcePositionMs)
    }

    @Test fun malformedValuesAndUnknownContractsCannotCoerceIntoValidSession() {
        fun changed(change: (JSONObject) -> Unit) = lastListeningSessionJson(session).also(change)
        for (malformed in listOf(
            changed { it.put("version", "1") }, changed { it.put("version", 2) },
            changed { it.remove("libraryId") }, changed { it.put("treeUri", " ") },
            changed { it.put("originalSha256", "invalid") }, changed { it.put("songId", "other") },
            changed { it.put("sourcePositionMs", -1L) }, changed { it.put("sourcePositionMs", "12345") },
            changed { it.put("sourcePositionMs", 12.5) }, changed { it.put("sourceMode", "stems") },
            changed { it.put("playing", true) },
            changed { it.getJSONObject("queue").put("currentIndex", Long.MAX_VALUE) },
            changed { it.getJSONObject("queue").put("currentIndex", 1.0) },
            changed { it.getJSONObject("queue").put("shuffleEnabled", "true") },
            changed { it.getJSONObject("queue").put("shuffleSeed", 1.5) },
            changed { it.getJSONObject("queue").put("repeatMode", "All") },
            changed { it.getJSONObject("queue").put("order", JSONArray(listOf("song-a", "song-a"))) },
            changed { it.getJSONObject("queue").put("order", JSONArray(listOf("song-b", ""))) },
            changed { it.getJSONObject("queue").put("order", JSONArray(listOf("song-b", 5))) },
            changed { it.getJSONObject("queue").put("order", JSONArray()) }
        )) assertThrows(Exception::class.java) { parseLastListeningSession(malformed) }
    }

    @Test fun emptyQueueAndNegativePositionCannotBecomeDurableSession() {
        assertThrows(IllegalArgumentException::class.java) { session.copy(queue = PlaybackQueueSnapshot()) }
        assertThrows(IllegalArgumentException::class.java) { session.copy(sourcePositionMs = -1L) }
    }
}
