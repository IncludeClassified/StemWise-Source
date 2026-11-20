package app.stemwise.enrichment

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsUnicodeParityInstrumentationTest {
    @Test fun pinnedUnicodeRangesDoNotDependOnTheAndroidUnicodeVersion() {
        assertEquals("18.0.0", LyricsUnicode.VERSION)
        val cases = listOf(
            "\u0628\u0870\u0628" to listOf(Utf16WordRange(0, 3)),
            "A\u1ac1B" to listOf(Utf16WordRange(0, 3)),
            "\uD801\uDC00-\uD801\uDC28x" to listOf(Utf16WordRange(0, 5), Utf16WordRange(5, 6)),
            "A-\uD81C\uDC00" to listOf(Utf16WordRange(0, 1), Utf16WordRange(2, 4)),
            "A-\u0628" to listOf(Utf16WordRange(0, 1), Utf16WordRange(2, 3)),
            "Cafe\u0301 noir" to listOf(Utf16WordRange(0, 5), Utf16WordRange(6, 10))
        )
        cases.forEach { (text, expected) -> assertEquals(text, expected, displayWordUtf16Ranges(text)) }
    }
}
