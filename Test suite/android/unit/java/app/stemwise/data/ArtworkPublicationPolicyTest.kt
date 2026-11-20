package app.stemwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkPublicationPolicyTest {
    @Test
    fun canonicalArtworkWinsOverRemoteFallback() {
        assertEquals(
            "content://library/artwork/cover.webp",
            publishedArtworkSource(
                primarySource = "content://library/artwork/cover.webp",
                additionalInfoJson = """{"artworkSourceUrl":"https://i.ytimg.com/cover.jpg"}"""
            )
        )
    }

    @Test
    fun missingCanonicalArtworkPublishesPersistedRemoteFallback() {
        assertEquals(
            "https://i.ytimg.com/cover.jpg",
            publishedArtworkSource(
                primarySource = null,
                additionalInfoJson = """{"artworkSourceUrl":"https://i.ytimg.com/cover.jpg"}"""
            )
        )
    }

    @Test
    fun malformedOrNonHttpsFallbackDoesNotBecomePublishedArtwork() {
        assertNull(publishedArtworkSource(null, "not-json"))
        assertNull(
            publishedArtworkSource(
                null,
                """{"artworkSourceUrl":"file:///untrusted/cover.jpg"}"""
            )
        )
    }

    @Test
    fun previouslyErasedArtworkSelfHealsFromStableYoutubeIdentity() {
        assertEquals(
            "https://i.ytimg.com/vi/rZNpBqlrwkI/hqdefault.jpg",
            publishedArtworkSource(
                primarySource = null,
                additionalInfoJson = "{}",
                youtubeVideoId = "rZNpBqlrwkI"
            )
        )
    }

    @Test
    fun blankAndTrimmedEmptyMetadataKeepTheYoutubeFallback() {
        listOf("", " \t\n", "{}", " \t{}\r\n").forEach { metadata ->
            assertEquals(
                "https://i.ytimg.com/vi/rZNpBqlrwkI/hqdefault.jpg",
                publishedArtworkSource(
                    primarySource = null,
                    additionalInfoJson = metadata,
                    youtubeVideoId = "rZNpBqlrwkI"
                )
            )
        }
    }

    @Test
    fun sharedArtworkGlyphPreservesWhitespaceProjectionAndWholeGraphemes() {
        val examples = listOf(
            "  Neon   Skyline  " to "NS",
            "single" to "S",
            "\tArabic\nGroove\r\nSession" to "AG",
            "   " to "♪",
            "🎵 mix" to "🎵M",
            "e\u0301cho beat" to "E\u0301B"
        )

        examples.forEach { (title, expected) ->
            assertEquals(expected, artworkGlyph(title))
        }
    }

}
