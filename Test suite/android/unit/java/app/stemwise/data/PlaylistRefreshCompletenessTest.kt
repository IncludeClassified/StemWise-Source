package app.stemwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject

class PlaylistRefreshCompletenessTest {
    @Test
    fun completeEmptyAndDuplicateObservationsAreAccepted() {
        for (entries in listOf("[]", "[{\"id\":\"A\"},{\"id\":\"A\"}]")) {
            val root = JSONObject("""{"_type":"playlist","id":"PL123","entries":$entries}""")
            root.put("playlist_count", root.getJSONArray("entries").length())
            assertEquals(root.getJSONArray("entries").length(), completePlaylistEntries(root, "PL123").length())
        }
    }

    @Test
    fun truncatedMalformedAndForeignExtractionsCannotRemoveMemberships() {
        for (payload in listOf(
            """{"_type":"playlist","id":"PL123","entries":[],"playlist_count":3}""",
            """{"_type":"playlist","id":"PL123","entries":[],"n_entries":1.5}""",
            """{"_type":"playlist","id":"PLother","entries":[]}""",
            """{"_type":"playlist","id":"PL123"}""",
            """{"id":"PL123","entries":[]}""")) {
            assertThrows(IllegalStateException::class.java) { completePlaylistEntries(JSONObject(payload), "PL123") }
        }
    }
}
