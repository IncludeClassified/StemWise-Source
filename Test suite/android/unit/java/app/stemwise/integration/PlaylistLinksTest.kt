package app.stemwise.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistLinksTest {
    @Test fun shareExaminesEveryUrlAndIgnoresRepeatedTrackingButRejectsConflictingPlaylists() {
        val playlist = "https://youtube.com/playlist?list=PL_DEMO_1234567890&si=a&si=b"
        assertEquals("PL_DEMO_1234567890", PlaylistLinks.parse("Read https://example.com/about then $playlist")?.id)
        assertEquals("PL_DEMO_1234567890", PlaylistLinks.parse("$playlist $playlist")?.id)
        assertNull(PlaylistLinks.parse("$playlist https://youtube.com/playlist?list=PL_OTHER_1234567890"))
    }

    @Test
    fun desktopMusicAndMobileShareTheSamePlaylistIdentity() {
        val expected = PlaylistLink("PL_DEMO_1234567890", "https://music.youtube.com/playlist?list=PL_DEMO_1234567890")
        for (host in listOf("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com")) {
            assertEquals(expected, PlaylistLinks.parse("https://$host/playlist?list=${expected.id}"))
        }
    }

    @Test
    fun parsesYouTubeMusicPlaylistAndRemovesTrackingParameters() {
        val parsed = PlaylistLinks.parse(
            "https://music.youtube.com/playlist?list=PL_DEMO_1234567890abcdef&si=tracking"
        )

        assertEquals("PL_DEMO_1234567890abcdef", parsed?.id)
        assertEquals(
            "https://music.youtube.com/playlist?list=PL_DEMO_1234567890abcdef",
            parsed?.canonicalUrl
        )
    }

    @Test
    fun extractsPlaylistLinkFromSharedText() {
        val parsed = PlaylistLinks.parse(
            "Listen: https://www.youtube.com/watch?v=DEMOvid1234&list=PL_DEMO_1234567890abcdef"
        )

        assertEquals("PL_DEMO_1234567890abcdef", parsed?.id)
    }

    @Test
    fun rejectsVideoWithoutPlaylistAndOtherHosts() {
        assertNull(PlaylistLinks.parse("https://music.youtube.com/watch?v=DEMOvid1234"))
        assertNull(PlaylistLinks.parse("https://example.com/playlist?list=PL_DEMO_1234567890abcdef"))
    }

    @Test
    fun rejectsMalformedEscapesDuplicatesAndOversizedSharesWithoutThrowing() {
        listOf("%", "%2", "%GG").forEach { escape ->
            assertNull(
                PlaylistLinks.parse(
                    "https://music.youtube.com/playlist?list=PL_DEMO_1234567890$escape"
                )
            )
        }
        assertNull(
            PlaylistLinks.parse(
                "https://music.youtube.com/playlist?list=PL_DEMO_1234567890" +
                    "&list=PL_OTHER_1234567890"
            )
        )
        assertNull(PlaylistLinks.parse("x".repeat(16_385)))
    }

    @Test
    fun acceptsEncodedPlaylistId() {
        assertEquals(
            "PL_DEMO_1234567890",
            PlaylistLinks.parse(
                "https://music.youtube.com/playlist?list=PL_DEMO_%31%32%33%34%35%36%37%38%39%30"
            )?.id
        )
    }
}
