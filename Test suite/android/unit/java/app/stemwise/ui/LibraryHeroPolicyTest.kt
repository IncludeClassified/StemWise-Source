package app.stemwise.ui

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryHeroPolicyTest {
    @Test
    fun shufflePrecedesThePrimaryPlayAction() {
        assertEquals(
            listOf(LibraryHeroAction.SHUFFLE, LibraryHeroAction.PLAY),
            libraryHeroActionOrder()
        )
        assertEquals(listOf("Shuffle", "Play"), libraryHeroActionOrder().map { it.label })
    }

    @Test
    fun heroUsesOneLibraryOwnedImageIndependentOfCurrentSongAndSongCount() {
        val policies = List(12) { libraryHeroArtworkPolicy() }
        assertEquals(1, policies.map { it.ownerKey }.distinct().size)
        assertTrue(policies.all { it.ownerKey.startsWith("library-hero:") })
    }

    @Test
    fun acceptedLibraryHeroUsesThePinnedLosslessWebpEncoding() {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(working, "src/main/res/drawable-nodpi/stemwise_library_artwork.webp"),
            File(working, "app/src/main/res/drawable-nodpi/stemwise_library_artwork.webp"),
            File(requireNotNull(working.parentFile),
                "app/src/main/res/drawable-nodpi/stemwise_library_artwork.webp")
        )
        val asset = candidates.firstOrNull(File::isFile)
        assertFalse("Accepted Library hero drawable is missing", asset == null)
        // The encoding changed losslessly; the artwork owner key retains the accepted image's
        // identity while this independent pin protects the actual packaged WebP bytes.
        assertEquals("8eae4628286f94f4d1520a54555a04a32f7fc46b0a6e6fe2dec00a11400c9a0d",
            sha256(requireNotNull(asset)))
        val header = asset.readBytes().take(16).toByteArray()
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBPVP8L", String(header, 8, 8, Charsets.US_ASCII))
    }

    @Test
    fun contentThatFitsTheViewportOwnsNeitherScrollNorChromeTravel() {
        assertFalse(libraryScrollRangeAvailable(false, false))
        assertTrue(libraryScrollRangeAvailable(false, true))
        assertTrue(libraryScrollRangeAvailable(true, false))
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
