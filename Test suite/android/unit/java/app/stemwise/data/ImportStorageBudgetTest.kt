package app.stemwise.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportStorageBudgetTest {
    @Test
    fun storageFailuresPreserveRetryWithoutMistakingOtherProviderErrorsForCapacity() {
        assertTrue(isImportStorageFailure(IllegalStateException("LIBRARY_STORAGE_LOW")))
        assertTrue(isImportStorageFailure(IllegalStateException("copy failed",
            java.io.IOException("write failed: ENOSPC (No space left on device)"))))
        assertFalse(isImportStorageFailure(java.io.IOException("permission denied")))
    }

    @Test
    fun budgetsPeakAdditionalBytesWithoutOverflowAndAllowsUnknownProviderCapacity() {
        val zipBytes = 500L
        requireImportStorageCapacity(IMPORT_STORAGE_RESERVE_BYTES + 2 * zipBytes, 2 * zipBytes, "LOW")
        assertThrows(IllegalStateException::class.java) {
            requireImportStorageCapacity(IMPORT_STORAGE_RESERVE_BYTES + zipBytes, 2 * zipBytes, "LOW")
        }
        assertThrows(IllegalStateException::class.java) {
            requireImportStorageCapacity(Long.MAX_VALUE, Long.MAX_VALUE, "LOW")
        }
        requireImportStorageCapacity(null, zipBytes, "LOW")
        assertThrows(IllegalArgumentException::class.java) {
            requireImportStorageCapacity(null, -1L, "LOW")
        }
    }
}
