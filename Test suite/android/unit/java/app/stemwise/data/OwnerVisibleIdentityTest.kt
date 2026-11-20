package app.stemwise.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerVisibleIdentityTest {
    @Test
    fun `playlist JSON null missing blank and non-string metadata are absent`() {
        val values = listOf(
            JSONObject(),
            JSONObject().put("title", JSONObject.NULL),
            JSONObject().put("title", "  "),
            JSONObject().put("title", 1),
            JSONObject().put("title", true)
        )

        values.forEach { assertEquals(null, it.ownerVisibleString("title")) }
        assertEquals(
            "A valid title",
            JSONObject().put("title", "  A valid title  ").ownerVisibleString("title")
        )
    }

    @Test
    fun `persisted malformed display identity is repaired at the owner projection boundary`() {
        val videoId = "AbCdEf_12-3"
        listOf(null, "", "  ").forEach { malformed ->
            val identity = ownerVisibleSongIdentity(
                videoId = videoId,
                displayTitle = malformed,
                displayArtist = malformed,
                sourceTitle = null,
                sourceArtist = null
            )
            assertEquals("Unavailable playlist item · AbCdEf_1", identity.title)
            assertEquals("Unknown artist", identity.artist)
            assertFalse(identity.title.equals("null", ignoreCase = true))
        }
    }

    @Test
    fun literalNullStringsRemainValidTitlesAndArtists() {
        for (text in listOf("Null", "NULL", "null")) {
            assertEquals(text, JSONObject().put("title", text).ownerVisibleString("title"))
            val projected = projectEnrichmentMetadata(JSONObject().put("title", text)
                .put("artists", org.json.JSONArray().put(JSONObject.NULL).put(7).put(text)))
            assertEquals(text, projected.title)
            assertEquals(text, projected.artist)
            assertEquals(OwnerVisibleSongIdentity(text, text),
                ownerVisibleSongIdentity("AbCdEf_12-3", null, null, text, text))
        }
    }

    @Test
    fun `valid identity and existing artist-title inference remain unchanged`() {
        assertEquals(
            OwnerVisibleSongIdentity("Valid title", "Valid artist"),
            ownerVisibleSongIdentity(
                "AbCdEf_12-3",
                "Valid title",
                "Valid artist",
                "Source title",
                "Source artist"
            )
        )
        assertEquals(
            OwnerVisibleSongIdentity("Track", "Artist"),
            ownerVisibleSongIdentity(
                "AbCdEf_12-3",
                null,
                null,
                "Artist - Track",
                "Unknown artist"
            )
        )
    }

    @Test
    fun `owner visible metadata and remote artwork have an exact finite boundary`() {
        val maximum = SelectedLibraryContract.MAXIMUM_METADATA_CHARACTERS
        val exactText = "t".repeat(maximum)
        assertEquals(exactText, ownerVisibleTextOrNull(exactText))
        assertEquals(exactText, ownerVisibleTextOrNull(exactText + "x"))

        val prefix = "https://i.ytimg.com/"
        val exactUrl = prefix + "a".repeat(maximum - prefix.length)
        assertEquals(exactUrl, normalizedApprovedArtworkUrl(exactUrl))
        assertNull(normalizedApprovedArtworkUrl(exactUrl + "a"))
    }

}
