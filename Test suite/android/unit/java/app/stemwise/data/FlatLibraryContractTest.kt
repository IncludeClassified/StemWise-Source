package app.stemwise.data

import app.stemwise.enrichment.ENRICHMENT_GENERATOR_NAME
import app.stemwise.enrichment.ENRICHMENT_GENERATOR_VERSION
import app.stemwise.enrichment.InstalledMediaIdentity
import app.stemwise.enrichment.createInstalledEnrichmentDocument
import app.stemwise.enrichment.installedMediaIdentities
import app.stemwise.enrichment.validateInstalledEnrichmentDocument
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatLibraryContractTest {
    private val currentPackageFiles = listOf("original.opus", "part-1.flac", "remaining.wv", ".stemwise-sources.json")
    @Test
    fun unrelatedRoleNamedFilesAreIgnoredWhileCurrentManifestLanesAreAdmitted() {
        val original = "original.opus"
        val historical = listOf("bass", "drums", "guitar", "other", "piano", "vocals")
            .map { "$it.opus" }
        val withExtras = inspectSelectedSongEntryNames("AbCdEf_12-3", listOf(original) + historical)
        assertEquals(SelectedSongFolderState.COMPLETE, withExtras.state)
        assertEquals(setOf(original), withExtras.presentFiles)
        for (targets in 1..8) {
            val lanes = (1..targets).map { "part-$it.flac" } + "remaining.wv"
            assertEquals(SelectedSongFolderState.COMPLETE, inspectSelectedSongEntryNames(
                "AbCdEf_12-3", listOf(original, ".stemwise-sources.json") + lanes).state)
        }
    }

    @Test
    fun nativeFlacOriginalIsAdmittedButTwoOriginalsAreAmbiguous() {
        val native = inspectSelectedSongEntryNames("AbCdEf_12-3", listOf("original.flac"))
        assertEquals(SelectedSongFolderState.COMPLETE, native.state)
        assertTrue(native.hasOriginal)
        assertEquals(SelectedSongFolderState.UNSUPPORTED,
            inspectSelectedSongEntryNames("AbCdEf_12-3", listOf("original.flac", "original.opus")).state)
        assertTrue(canonicalMediaMimeMatches("original.flac", "audio/flac"))
        assertTrue(!canonicalMediaMimeMatches("original.flac", "audio/opus"))
    }

    @Test
    fun settledDirectFolderAllowsOriginalOnlyOrDeclaredLosslessParts() {
        val videoId = "AbCdEf_12-3"
        val originalOnly = inspectSelectedSongEntryNames(
            videoId,
            listOf(SelectedLibraryContract.ORIGINAL)
        )
        val preEnrichment = inspectSelectedSongEntryNames(
            videoId,
            currentPackageFiles
        )
        val enriched = inspectSelectedSongEntryNames(
            videoId,
            currentPackageFiles + SelectedLibraryContract.enrichmentFiles
        )
        val jsonOnly = inspectSelectedSongEntryNames(
            videoId,
            currentPackageFiles + SelectedLibraryContract.ENRICHMENT
        )
        val coverOnly = inspectSelectedSongEntryNames(
            videoId,
            currentPackageFiles + SelectedLibraryContract.COVER
        )
        val partialStems = inspectSelectedSongEntryNames(
            videoId,
            listOf(SelectedLibraryContract.ORIGINAL, "part-1.flac")
        )

        assertEquals(SelectedSongFolderState.COMPLETE, originalOnly.state)
        assertTrue(!originalOnly.hasCustomSourceMedia)
        assertEquals(SelectedSongFolderState.COMPLETE, preEnrichment.state)
        assertTrue(preEnrichment.hasStructurallyCompleteCustomSourceSet)
        assertEquals(SelectedSongFolderState.COMPLETE, enriched.state)
        assertEquals(4, preEnrichment.presentFiles.size)
        assertEquals(6, enriched.presentFiles.size)
        assertEquals(SelectedSongFolderState.COMPLETE, jsonOnly.state)
        assertEquals(SelectedSongFolderState.COMPLETE, coverOnly.state)
        assertEquals(SelectedSongFolderState.INCOMPLETE, partialStems.state)
    }

    @Test
    fun exactPortableTransactionMarkerAdmitsOnlyTheRecoverablePairTransition() {
        val videoId = "AbCdEf_12-3"
        val recoverable = inspectSelectedSongEntryNames(
            videoId,
                currentPackageFiles +
                SelectedLibraryContract.COVER +
                SelectedLibraryContract.ENRICHMENT_TRANSACTION
        )
        val unknownMarker = inspectSelectedSongEntryNames(
            videoId,
            currentPackageFiles +
                SelectedLibraryContract.COVER +
                ".stemwise-enrichment-transaction"
        )

        assertEquals(SelectedSongFolderState.COMPLETE, recoverable.state)
        assertEquals(SelectedSongFolderState.COMPLETE, unknownMarker.state)
    }

    @Test
    fun wrappersUnknownNamesCaseChangesAndDuplicatesFailClosed() {
        val videoId = "AbCdEf_12-3"
        for (entries in listOf(listOf("songs"), listOf("Original.opus"))) {
            assertEquals(SelectedSongFolderState.INCOMPLETE, inspectSelectedSongEntryNames(videoId, entries).state)
        }
        listOf(listOf("original.opus", "original.opus")).forEach { entries ->
            assertEquals(
                SelectedSongFolderState.UNSUPPORTED,
                inspectSelectedSongEntryNames(videoId, entries).state
            )
        }
        assertEquals(
            SelectedSongFolderState.UNSUPPORTED,
            inspectSelectedSongEntryNames("too-short", emptyList()).state
        )
    }

    @Test
    fun onlyAnExactActiveRootContentUriQualifiesAsCurrentMedia() {
        val active = "content://library/root/AbCdEf_12-3/original.opus"
        assertEquals(active, canonicalRootSourceLocation(active, active))
        assertNull(canonicalRootSourceLocation("/private/original.opus", active))
        assertNull(
            canonicalRootSourceLocation(
                "content://old-root/AbCdEf_12-3/original.opus",
                active
            )
        )
        assertNull(canonicalRootSourceLocation(active, null))
    }

    @Test
    fun installedJsonAuthenticatesTheDurableOriginalWithoutRequiringOptionalStems() {
        val videoId = "AbCdEf_12-3"
        val media = mediaIdentities()
        val document = createInstalledEnrichmentDocument(
            incoming = incoming(videoId),
            originalSha256 = media.getValue(SelectedLibraryContract.ORIGINAL).sha256,
            media = media,
            contentRevision = 3L,
            payloadSha256 = "f".repeat(64),
            artwork = incoming(videoId).getJSONObject("artwork"),
            artworkSha256 = "a".repeat(64),
            artworkBytes = 68
        )

        validateInstalledEnrichmentDocument(
            document,
            videoId,
            media.getValue(SelectedLibraryContract.ORIGINAL).sha256
        )
        assertEquals(
            listOf(SelectedLibraryContract.ORIGINAL),
            installedMediaIdentities(document).keys.toList()
        )
        assertTrue(
            document.getJSONObject("install").getString("generatedDigest")
                .matches(Regex("^[a-f0-9]{64}$"))
        )
        assertEquals("selected-root", document.getJSONObject("artwork").getString("delivery"))
        assertEquals("cover.png", document.getJSONObject("artwork").getString("file"))

        val tampered = JSONObject(document.toString())
        tampered.getJSONObject("media").getJSONArray("files")
            .getJSONObject(0).put("path", "nested/original.opus")
        assertTrue(
            runCatching {
                validateInstalledEnrichmentDocument(
                    tampered,
                    videoId,
                    media.getValue(SelectedLibraryContract.ORIGINAL).sha256
                )
            }.isFailure
        )
    }

    @Test
    fun canonicalExtensionsCannotHideAReencodedOrRenamedCodec() {
        assertTrue(canonicalMediaMimeMatches("original.opus", "audio/opus"))
        assertTrue(canonicalMediaMimeMatches("part-1.flac", "audio/flac"))
        assertTrue(canonicalMediaMimeMatches("remaining.wv", "audio/wavpack"))
        assertTrue(!canonicalMediaMimeMatches("bass.opus", "audio/opus"))
        assertEquals(false, canonicalMediaMimeMatches("original.opus", "audio/mpeg"))
        assertEquals(false, canonicalMediaMimeMatches("part-1.flac", "audio/opus"))
    }

    @Test
    fun installedV2RejectsCoerciveNumbersGroupsAndOpenGeneratorObjects() {
        val videoId = "AbCdEf_12-3"
        val media = mediaIdentities()
        val valid = createInstalledEnrichmentDocument(
            incoming(videoId),
            media.getValue(SelectedLibraryContract.ORIGINAL).sha256,
            media,
            1L,
            "f".repeat(64),
            incoming(videoId).getJSONObject("artwork"),
            "a".repeat(64),
            68
        )
        val invalid = listOf(
            JSONObject(valid.toString()).also {
                it.getJSONObject("install").put("contentRevision", 1.5)
            },
            JSONObject(valid.toString()).put("lyrics", org.json.JSONArray()),
            JSONObject(valid.toString()).also {
                it.getJSONObject("generator").put("extra", true)
            },
            JSONObject(valid.toString()).put("generatedAt", "2026-08-09T12:00:00.0Z")
        )
        invalid.forEach { document ->
            assertTrue(runCatching {
                validateInstalledEnrichmentDocument(
                    document,
                    videoId,
                    media.getValue(SelectedLibraryContract.ORIGINAL).sha256
                )
            }.isFailure)
        }
    }

    private fun mediaIdentities(): Map<String, InstalledMediaIdentity> =
        mapOf(
            SelectedLibraryContract.ORIGINAL to InstalledMediaIdentity(
                sha256 = "1".padStart(64, '0'),
                byteLength = 1_000L
            )
        )

    private fun incoming(videoId: String) = JSONObject()
        .put("source", JSONObject().put("provider", "youtube").put("videoId", videoId))
        .put("generatedAt", "2026-08-09T12:00:00Z")
        .put(
            "generator",
            JSONObject()
                .put("name", ENRICHMENT_GENERATOR_NAME)
                .put("version", ENRICHMENT_GENERATOR_VERSION)
        )
        .put(
            "artwork",
            JSONObject()
                .put("file", "cover.png")
                .put("kind", "youtube-thumbnail")
                .put(
                    "source",
                    JSONObject()
                        .put("name", "YouTube thumbnail")
                        .put("url", "https://www.youtube.com/watch?v=$videoId")
                )
        )
        .put(
            "metadata",
            JSONObject()
                .put("title", "Contract fixture")
                .put("matchStatus", "youtube-fallback")
                .put("artists", org.json.JSONArray().put("Contract artist"))
                .put(
                    "sources",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("name", "YouTube Music")
                            .put(
                                "url",
                                "https://music.youtube.com/watch?v=$videoId"
                            )
                    )
                )
        )
}
