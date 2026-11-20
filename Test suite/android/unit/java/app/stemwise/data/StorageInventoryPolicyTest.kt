package app.stemwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

class StorageInventoryPolicyTest {
    @Test fun artworkOnlyMismatchRequiresCanonicalRecheck() {
        val previous = LibraryInventory(songsFound = 1, verifiedSongPackages = 1,
            originalFilesFound = 1, verifiedOriginalFiles = 1, jsonFilesFound = 1, verifiedJsonFiles = 1,
            artworkFilesFound = 1, verifiedArtworkFiles = 1)
        val measurement = LibraryStorageMeasurement(1, 1, 0, 1, 0, 0L, 0, 0, 0L, 0L, 0L, 0L,
            1L)
        val refreshed = mergeStorageMeasurement(previous, measurement)
        assertTrue(refreshed.snapshotDirty)
        assertEquals(0, refreshed.artworkFilesFound)
        assertEquals(1, refreshed.verifiedArtworkFiles)
    }
    @Test fun largeLibrarySnapshotContainsOnlyScalarCurrentInventory() {
        val json = libraryInventorySnapshotJson(LibraryInventory(songsFound = 16_384))
        assertEquals(3, json.getInt("snapshotVersion"))
        assertTrue(!json.has("sets"))
        assertTrue(json.toString().length < 2_048)
    }

    @Test
    fun scalarInventorySnapshotRoundTripsExactly() {
        val value = LibraryInventory(
            songsFound = 8_192,
            originalFilesFound = 8_190,
            stemFilesFound = 49_140,
            jsonFilesFound = 8_191,
            artworkFilesFound = 8_180,
            verifiedSongPackages = 8_189,
            verifiedOriginalFiles = 8_189,
            verifiedStemFiles = 49_134,
            verifiedJsonFiles = 8_189,
            pcEnrichedSongManifests = 4_000,
            verifiedArtworkFiles = 8_179,
            verifiedTotalBytes = 10_000_000_000L,
            actualLibraryBytes = 10_000_000_500L,
            temporaryOrUnclassifiedBytes = 500L,
            unknownSizeFiles = 2,
            unreadableDirectories = 1,
            appPrivateTotalBytes = 900L,
            appDatabaseBytes = 300L,
            appWorkingBytes = 500L,
            appCacheBytes = 100L,
            lastCheckedEpochMs = 123_456_789L,
            invalidPackages = 3,
            snapshotDirty = true
        )

        assertEquals(value, decodeLibraryInventorySnapshot(libraryInventorySnapshotJson(value)))
    }

    @Test
    fun snapshotRejectsMissingUnknownOrCoercedScalarFields() {
        val valid = libraryInventorySnapshotJson(LibraryInventory())
        val corruptions = listOf<(JSONObject) -> Unit>(
            { it.remove("ready") },
            { it.put("sets", JSONArray()) },
            { it.put("snapshotVersion", 2) },
            { it.put("snapshotVersion", "2") },
            { it.put("snapshotVersion", 2.0) },
            { it.put("songsFound", "1") },
            { it.put("songsFound", 1.5) },
            { it.put("songsFound", -1) },
            { it.put("songsFound", Long.MAX_VALUE) },
            { it.put("recognized", BigInteger("9223372036854775808")) },
            { it.put("dirty", "false") }
        )

        corruptions.forEach { corrupt ->
            val candidate = JSONObject(valid.toString())
            corrupt(candidate)
            assertThrows(IllegalStateException::class.java) {
                decodeLibraryInventorySnapshot(candidate)
            }
        }
    }

    @Test
    fun unchangedProviderMetadataAloneCannotVerifyAudioBytes() {
        val cached = AssetObservationEntity(
            packageId = "Observation1", relativePath = SelectedLibraryContract.ORIGINAL,
            documentUri = "content://provider/original", byteLength = 123L,
            lastModifiedEpochMs = 456L, expectedSha256 = "a".repeat(64),
            manifestFileSha256 = "b".repeat(64), verifiedAtEpochMs = 789L
        )
        assertTrue(!reusableFlatAssetObservation(cached.documentUri, 123L, 456L, cached))
    }

    @Test
    fun mediaObservationIsReusableOnlyWithPositiveStableProviderMetadata() {
        val cached = AssetObservationEntity(
            packageId = "Observation1",
            relativePath = SelectedLibraryContract.ORIGINAL,
            documentUri = "content://provider/tree/root/document/root%2Fsong%2Foriginal.opus",
            byteLength = 123L,
            lastModifiedEpochMs = 456L,
            expectedSha256 = "a".repeat(64),
            manifestFileSha256 = "b".repeat(64),
            verifiedAtEpochMs = 789L
        )

        assertTrue(reusableFlatAssetObservation(cached.documentUri, 123L, 456L, cached, "a".repeat(64)))
        assertTrue(!reusableFlatAssetObservation(cached.documentUri, 123L, 456L, cached, "c".repeat(64)))
        assertTrue(!reusableFlatAssetObservation(cached.documentUri, 123L, 0L, cached, "a".repeat(64)))
        assertTrue(!reusableFlatAssetObservation(cached.documentUri, 124L, 456L, cached, "a".repeat(64)))
        assertTrue(!reusableFlatAssetObservation("content://other", 123L, 456L, cached, "a".repeat(64)))
    }

}
