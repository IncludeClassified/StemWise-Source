package app.stemwise.data

import android.provider.DocumentsContract
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SelectedLibrarySnapshotPolicyTest {
    @Test
    fun trueEmptyEnumerationAndCaseDifferentIdsRemainDistinct() {
        assertEquals(emptyList<SelectedDocumentRow>(), validateUniqueDocumentRows(emptyList()))

        val rows = listOf(
            row("root/AbCdEf_12-3", "AbCdEf_12-3"),
            row("root/abCdEf_12-3", "abCdEf_12-3")
        )
        assertEquals(rows, validateUniqueDocumentRows(rows))
    }

    @Test
    fun blankIdentityNameOrMimeFailsClosed() {
        listOf(
            SelectedDocumentRow("", "AbCdEf_12-3", DIRECTORY, null),
            SelectedDocumentRow("root/a", "", DIRECTORY, null),
            SelectedDocumentRow("root/a", "AbCdEf_12-3", "", null),
            SelectedDocumentRow(null, "AbCdEf_12-3", DIRECTORY, null),
            SelectedDocumentRow("root/a", null, DIRECTORY, null),
            SelectedDocumentRow("root/a", "AbCdEf_12-3", null, null)
        ).forEach { invalid ->
            assertEquals(
                "ROOT_LAYOUT_UNSUPPORTED",
                assertThrows(IllegalStateException::class.java) {
                    validateUniqueDocumentRows(listOf(invalid))
                }.message
            )
        }
    }

    @Test
    fun duplicateDocumentIdentityOrExactNameFailsBeforeMaterialization() {
        val first = row("root/a", "AbCdEf_12-3")
        listOf(
            listOf(first, row("root/a", "XyZ0123_-ab")),
            listOf(first, row("root/b", "AbCdEf_12-3"))
        ).forEach { invalid ->
            assertEquals(
                "ROOT_LAYOUT_UNSUPPORTED",
                assertThrows(IllegalStateException::class.java) {
                    validateUniqueDocumentRows(invalid)
                }.message
            )
        }
    }

    @Test
    fun documentMetadataRemainsBoundedWithoutALibraryCardinalityCeiling() {
        val rows = List(8_193) { index -> row("root/$index", "Q${index.toString().padStart(10, '0')}") }
        assertEquals(rows, validateUniqueDocumentRows(rows))
        val oversized = "x".repeat(SelectedLibraryContract.MAXIMUM_METADATA_CHARACTERS + 1)
        listOf(
            SelectedDocumentRow(oversized, "AbCdEf_12-3", DIRECTORY, null),
            SelectedDocumentRow("root/a", oversized, DIRECTORY, null),
            SelectedDocumentRow("root/a", "AbCdEf_12-3", oversized, null)
        ).forEach { invalid ->
            assertEquals("ROOT_LAYOUT_UNSUPPORTED", assertThrows(IllegalStateException::class.java) {
                validateUniqueDocumentRows(listOf(invalid))
            }.message)
        }
        val exactMime = row("root/a", "AbCdEf_12-3").copy(
            mimeType = "m".repeat(SelectedLibraryContract.MAXIMUM_MIME_TYPE_CHARACTERS))
        assertEquals(listOf(exactMime), validateUniqueDocumentRows(listOf(exactMime)))
        assertThrows(IllegalStateException::class.java) {
            validateUniqueDocumentRows(listOf(exactMime.copy(mimeType = exactMime.mimeType + "m")))
        }
    }

    @Test
    fun selectedSubtreeCapacityNeverQueriesWholeDeviceRoots() {
        val rootManager = source("app/src/main/java/app/stemwise/data/LibraryRootManager.kt")
        assertFalse("buildRootsUri" in rootManager)
    }

    @Test
    fun duplicateCanonicalFileNamesRemainUnsupportedEvenWithDifferentRows() {
        assertEquals(
            SelectedSongFolderState.UNSUPPORTED,
            inspectSelectedSongEntryNames(
                "AbCdEf_12-3",
                listOf("original.opus", "original.opus")
            ).state
        )
    }

    @Test
    fun nullThrowAndPermissionLossHaveStableUnavailableTaxonomy() {
        val nullFailure = assertThrows(LibraryRootException::class.java) {
            checkedSelectedDocumentRows { null }
        }
        assertEquals("ROOT_UNAVAILABLE", nullFailure.code)

        val throwFailure = assertThrows(LibraryRootException::class.java) {
            checkedSelectedDocumentRows { throw java.io.IOException("provider offline") }
        }
        assertEquals("ROOT_UNAVAILABLE", throwFailure.code)

        val permissionFailure = assertThrows(LibraryRootException::class.java) {
            checkedSelectedDocumentRows { throw SecurityException("grant revoked") }
        }
        assertEquals("ROOT_PERMISSION_LOST", permissionFailure.code)
    }

    private fun source(relative: String): String = String(
        Files.readAllBytes(projectFile(relative)),
        StandardCharsets.UTF_8
    )

    private fun projectFile(relative: String): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .map { it.resolve(relative) }
            .firstOrNull(Files::isRegularFile)
            ?: error("Could not locate $relative from $start")
    }

    private fun row(id: String, name: String) = SelectedDocumentRow(
        documentId = id,
        displayName = name,
        mimeType = DIRECTORY,
        sizeBytes = null
    )

    private companion object {
        const val DIRECTORY = DocumentsContract.Document.MIME_TYPE_DIR
    }
}
