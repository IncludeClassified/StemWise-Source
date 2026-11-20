package app.stemwise.data

import org.junit.Assert.*
import org.junit.Test

class ProviderMimePolicyTest {
    @Test
    fun internalTransactionNamesUseOpaqueCreationAndAllowContentOrExtensionMetadata() {
        val prefix = ".stemwise-asset-${"a".repeat(32)}-"
        for ((name, contentMime) in listOf(
            ".stemwise-library.json.tmp" to "application/json",
            ".stemwise-library.json.bak" to "application/json",
            ".stemwise-deletion-tombstones.json.tmp" to "application/json",
            ".stemwise-ytdlp-authority.json.bak" to "application/json",
            ".stemwise-enrichment-transaction.json.tmp" to "application/json",
            ".stemwise-enrichment.json.backup" to "application/json",
            ".stemwise-enrichment.json.replacement" to "application/json",
            ".stemwise-cover.png.backup" to "image/png",
            ".stemwise-cover.png.replacement" to "image/png",
            ".stemwise-sources.json.tmp" to "application/json",
            ".stemwise-sources.json.backup" to "application/json",
            ".stemwise-source-job.json.tmp" to "application/json",
            ".stemwise-replacement-source-job.json.tmp" to "application/json",
            prefix + ".stemwise-cover.png.replacement" to "image/png",
        )) {
            assertEquals(name, "application/octet-stream", providerDocumentCreationMime(name, contentMime))
            assertTrue(name, providerCreatedDocumentMimeMatches(name, contentMime, contentMime))
            assertTrue(name, providerCreatedDocumentMimeMatches(name, contentMime, "application/octet-stream"))
            assertFalse(name, providerCreatedDocumentMimeMatches(name, contentMime, "text/plain"))
            assertFalse(name, providerCreatedDocumentMimeMatches(name, "audio/flac", "application/octet-stream"))
        }
    }

    @Test
    fun registeredBackupExtensionIsUsedWithoutGrantingPublicMediaItsMime() {
        val name = ".stemwise-library.json.bak"
        assertEquals("application/x-trash", providerDocumentCreationMime(name, "application/json", "application/x-trash"))
        assertTrue(providerCreatedDocumentMimeMatches(name, "application/json", "application/x-trash", "application/x-trash"))
        assertTrue(providerCreatedDocumentMimeMatches(name, "application/json", "application/json", "application/x-trash"))
        assertTrue(providerCreatedDocumentMimeMatches(name, "application/json", "application/octet-stream", "application/x-trash"))
        assertFalse(providerCreatedDocumentMimeMatches(name, "application/json", "text/plain", "application/x-trash"))
        for ((publicName, mime) in listOf("cover.png" to "image/png", "song.json" to "application/json")) {
            assertEquals(mime, providerDocumentCreationMime(publicName, mime, "application/x-trash"))
            assertFalse(providerCreatedDocumentMimeMatches(publicName, mime, "application/x-trash", "application/x-trash"))
        }
    }

    @Test
    fun canonicalPublicAndUnknownNamesDoNotGainGenericMetadataAuthority() {
        for ((name, contentMime) in listOf(
            ".stemwise-library.json" to "application/json",
            ".stemwise-source-job.json" to "application/json",
            "song.json" to "application/json",
            "cover.png" to "image/png",
            "original.flac" to "audio/flac",
            "unknown.json.tmp" to "application/json",
            ".stemwise-asset-${"A".repeat(32)}-.stemwise-cover.png.replacement" to "image/png",
        )) {
            assertEquals(name, contentMime, providerDocumentCreationMime(name, contentMime))
            assertFalse(name, providerCreatedDocumentMimeMatches(name, contentMime, "application/octet-stream"))
        }
        assertFalse(providerCreatedDocumentMimeMatches("unknown.json.tmp", "application/json", "application/json"))
    }

    @Test
    fun remainingAllowsOnlyItsCanonicalOrKnownFileProviderMetadataWhileOtherTypesStayExact() {
        assertTrue(providerCreatedDocumentMimeMatches("remaining.wv", "audio/wavpack", "audio/wavpack"))
        assertTrue(providerCreatedDocumentMimeMatches("remaining.wv", "audio/wavpack", "application/octet-stream"))
        assertTrue(providerCreatedDocumentMimeMatches("remaining.wv", "audio/wavpack", "audio/x-wavpack"))
        for (reported in listOf("", " ", "image/png", "vnd.android.document/directory", "audio/flac", "audio/wav")) {
            assertFalse(reported, providerCreatedDocumentMimeMatches("remaining.wv", "audio/wavpack", reported))
        }
        for (name in listOf("part-1.flac", "original.opus", "song.json", "cover.png", ".stemwise-sources.json", "remaining.wv.tmp")) {
            assertFalse(name, providerCreatedDocumentMimeMatches(name, "audio/wavpack", "application/octet-stream"))
        }
        assertFalse(providerCreatedDocumentMimeMatches("remaining.wv", "image/png", "application/octet-stream"))
        assertTrue(providerCreatedDocumentMimeMatches("song.json", "application/json", "application/json"))
    }

    @Test
    fun stagingUsesOnlyTheValidatedCanonicalRolesMimePolicy() {
        val prefix = ".stemwise-asset-${"a".repeat(32)}-"
        for (name in listOf("remaining.wv", "replacement-remaining.wv")) {
            for (reported in listOf("audio/wavpack", "application/octet-stream", "audio/x-wavpack")) {
                assertTrue(name, providerCreatedDocumentMimeMatches(prefix + name, "audio/wavpack", reported))
            }
            assertFalse(providerCreatedDocumentMimeMatches(prefix + name, "audio/wavpack", "audio/flac"))
            assertFalse(providerCreatedDocumentMimeMatches(prefix + name, "image/png", "application/octet-stream"))
        }
        for ((name, requested) in listOf("original.opus" to "audio/ogg", "original.flac" to "audio/flac",
            "part-1.flac" to "audio/flac", "replacement-part-1.flac" to "audio/flac")) {
            assertTrue(providerCreatedDocumentMimeMatches(prefix + name, requested, requested))
            assertFalse(providerCreatedDocumentMimeMatches(prefix + name, requested, "application/octet-stream"))
            assertFalse(providerCreatedDocumentMimeMatches(prefix + name, requested, "audio/x-wavpack"))
        }
        for (name in listOf(".stemwise-asset-${"a".repeat(31)}-remaining.wv",
            ".stemwise-asset-${"A".repeat(32)}-remaining.wv", prefix + "remaining.wv.tmp",
            prefix + "../remaining.wv", prefix + "unknown.wv")) {
            assertFalse(name, SelectedLibraryContract.isPublicationStagingName(name))
            assertFalse(name, providerCreatedDocumentMimeMatches(name, "audio/wavpack", "application/octet-stream"))
            assertFalse(name, providerCreatedDocumentMimeMatches(name, "audio/wavpack", "audio/wavpack"))
        }
    }
}
