package app.stemwise.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaIdentityTest {
    @Test
    fun youtubeVideoIdAcceptsOnlyElevenAsciiContractCharacters() {
        val valid = listOf(
            "rZNpBqlrwkI",
            "___________",
            "-----------",
            "A0_-a9ZzYyX"
        )
        val invalid = listOf(
            "",
            "          ",
            "0123456789",
            "012345678901",
            "0123456789 ",
            " 0123456789",
            "0123456789/",
            "0123456789?",
            "0123456789#",
            "0123456789é",
            "youtube-video:rZNpBqlrwkI"
        )

        valid.forEach { assertTrue(it, MediaIdentity.isYoutubeVideoId(it)) }
        invalid.forEach { assertFalse(it, MediaIdentity.isYoutubeVideoId(it)) }
    }

    @Test
    fun sourceIdentityRequiresOneExactCaseSensitivePrefixAndVideoId() {
        val videoId = "rZNpBqlrwkI"
        val expected = "youtube-video:$videoId"

        assertTrue(MediaIdentity.isYoutubeSourceId(expected))
        listOf(
            videoId,
            "YouTube-video:$videoId",
            "youtube-video:$videoId ",
            "youtube-video:short",
            "youtube-video:youtube-video:$videoId",
            "youtube-video:/rZNpBqlrwkI"
        ).forEach { assertFalse(it, MediaIdentity.isYoutubeSourceId(it)) }
    }

    @Test
    fun playlistIdentityEnforcesTenThroughOneHundredTwentyEightCharacters() {
        val valid = listOf(
            "PL12345678",
            "_".repeat(10),
            "-".repeat(128),
            "Ab0_-".repeat(25) + "Ab0"
        )
        val invalid = listOf(
            "",
            "_".repeat(9),
            "-".repeat(129),
            "PL1234567 ",
            "PL1234567/",
            "PL1234567é"
        )

        valid.forEach { assertTrue(it.length.toString(), MediaIdentity.isYoutubePlaylistId(it)) }
        invalid.forEach { assertFalse(it.length.toString(), MediaIdentity.isYoutubePlaylistId(it)) }
    }
}
