package app.stemwise.integration

import org.junit.Assert.assertThrows
import org.junit.Test

class MetadataOnlyExtractorTest {
    private val metadata = listOf("--flat-playlist", "--dump-single-json", "--no-progress",
        "https://music.youtube.com/playlist?list=PL123456789012345")

    @Test fun productionPlaylistRequestPassesItsMetadataValidator() {
        requireMetadataOnlyYtDlpRequest(playlistMetadataRequest(metadata.last()).buildCommand())
    }

    @Test fun playlistMetadataIsAdmittedAndMediaSideEffectsAreRejected() {
        requireMetadataOnlyYtDlpRequest(metadata)
        listOf("--no-skip-download", "--write-thumbnail", "--write-info-json", "--exec",
            "--extract-audio", "--format", "--output", "--config-locations").forEach { option ->
            assertThrows(IllegalStateException::class.java) {
                requireMetadataOnlyYtDlpRequest(metadata + listOf(option, "anything"))
            }
        }
        assertThrows(IllegalStateException::class.java) {
            requireMetadataOnlyYtDlpRequest(metadata + metadata.last())
        }
        assertThrows(IllegalStateException::class.java) {
            requireMetadataOnlyYtDlpRequest(listOf("https://youtube.com/watch?v=a1B2c3D4e5F"))
        }
    }
}
