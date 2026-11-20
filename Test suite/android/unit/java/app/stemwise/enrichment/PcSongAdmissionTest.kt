package app.stemwise.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcSongAdmissionTest {
    @Test fun oneExactSongIsRequiredBeforeApplication() {
        val expected = "F84DytTL_j0"
        requirePcSongIdentity(listOf(expected), expected)
        for (ids in listOf(emptyList(), listOf(expected, expected), listOf(expected, "tWEd6wxP6eM"))) {
            assertEquals("SONG_COUNT_INVALID", assertThrows(EnrichmentImportException::class.java) {
                requirePcSongIdentity(ids, expected)
            }.code)
        }
        for (ids in listOf(listOf(null), listOf("tWEd6wxP6eM"))) {
            assertEquals("SONG_ID_MISMATCH", assertThrows(EnrichmentImportException::class.java) {
                requirePcSongIdentity(ids, expected)
            }.code)
        }
        assertEquals("SONG_ID_INVALID", assertThrows(EnrichmentImportException::class.java) {
            requirePcSongIdentity(listOf(expected), "invalid")
        }.code)
    }
}
