package app.stemwise.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DisplayTextTest {
    @Test
    fun artworkAndClippingPreserveSupplementaryLettersAndCombiningClusters() {
        val supplementaryLetter = "\uD801\uDC28"
        assertEquals(supplementaryLetter.uppercase() + "E\u0301", artworkGlyph("$supplementaryLetter e\u0301lan"))
        assertEquals("a", boundedDisplayText("a${supplementaryLetter}b", 2))
        assertEquals("a$supplementaryLetter", boundedDisplayText("a${supplementaryLetter}b", 3))
        assertEquals("", boundedDisplayText("e\u0301lan", 1))
        assertEquals("e\u0301", boundedDisplayText("e\u0301lan", 2))
    }

    @Test
    fun unsupportedArtworkHostStaysDeniedEvenInsideAnApprovedUrlPath() {
        assertEquals(null, normalizedApprovedArtworkUrl("https://yt3.ggpht.com/avatar=s100"))
        assertEquals("https://i.ytimg.com/lh3.googleusercontent.com/image", normalizedApprovedArtworkUrl("https://i.ytimg.com/lh3.googleusercontent.com/image"))
    }

    @Test
    fun displaySourcesAndPackagedTextHaveNoLiteralC1MojibakeControls() {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        val project = generateSequence(working) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
        (listOf("main", "debug").map { File(project, "app/src/$it") } +
            File(project.parentFile, "Test suite/android/unit/java")).flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension in setOf("kt", "xml", "json", "txt") }
            .forEach { file ->
                assertFalse("Literal C1 control in ${file.relativeTo(project)}; intentional negative fixtures must use explicit escapes",
                    file.readText(Charsets.UTF_8).let { text -> text.any { it.code in 0x80..0x9f || it.code == 0xfffd } ||
                        listOf("\u00c2\u00b7", "\u00e2\u20ac").any(text::contains) })
            }
    }
}
