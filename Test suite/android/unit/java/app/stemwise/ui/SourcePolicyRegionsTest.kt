package app.stemwise.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourcePolicyRegionsTest {
    @Test fun extractionRejectsMissingReorderedAndAmbiguousArchitectureBoundaries() {
        assertEquals("body", "startbodyend".requiredSourceRegion("start", "end"))
        for (source in listOf("bodyend", "startbody", "endstart", "startstartbodyend", "startbodyendend")) {
            assertThrows(IllegalStateException::class.java) { source.requiredSourceRegion("start", "end") }
        }
        assertThrows(IllegalStateException::class.java) { "body".requiredSubstringAfter("missing") }
        assertThrows(IllegalStateException::class.java) { "body".requiredSubstringBefore("missing") }
    }
}
