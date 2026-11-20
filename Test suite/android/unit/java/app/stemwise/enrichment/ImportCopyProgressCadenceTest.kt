package app.stemwise.enrichment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCopyProgressCadenceTest {
    @Test fun byteReadsPublishAtMostTenTimesPerSecond() {
        val cadence = ImportCopyProgressCadence()
        assertTrue(cadence.shouldPublish(0L))
        for (millisecond in 1L..99L) assertFalse(cadence.shouldPublish(millisecond * 1_000_000L))
        assertTrue(cadence.shouldPublish(100_000_000L))
        assertFalse(cadence.shouldPublish(199_000_000L))
        assertTrue(cadence.shouldPublish(200_000_000L))
    }
}
