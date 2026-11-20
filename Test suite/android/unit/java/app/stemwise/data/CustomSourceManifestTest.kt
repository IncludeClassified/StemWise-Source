package app.stemwise.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSourceManifestTest {
    @Test
    fun canonicalProducerRoundTripsAndObsoleteOrUnmarkedManifestsFailClosed() {
        val current = parseCustomSourceManifest(signed(fixture()), VIDEO_ID, SOURCE_SHA)
        assertEquals(current, parseCustomSourceManifest(current.toBytes(), VIDEO_ID, SOURCE_SHA))
        requireRemainingProducerForOriginal(current.lanes.last(), 96_000)
        for (mutation in listOf<(JSONObject) -> Unit>(
            { it.put("schemaVersion", 2) },
            { it.getJSONArray("lanes").getJSONObject(2).remove("remainingProducer") },
            { it.getJSONArray("lanes").getJSONObject(2).put("remainingProducer", "ffmpeg-48k") }
        )) {
            val changed = fixture()
            mutation(changed)
            assertRejected(signed(changed))
        }
    }

    @Test
    fun originalBoundOrderedTargetsAndRemainingRoundTripWithoutLegacyFileAliases() {
        val bytes = signed(fixture())
        val parsed = parseCustomSourceManifest(bytes, VIDEO_ID, SOURCE_SHA)

        assertEquals(listOf("vocals", "bass"), parsed.selectedSourceIds)
        assertEquals(listOf("vocals", "bass", "remaining"), parsed.lanes.map { it.sourceId })
        assertEquals(listOf("part-1.flac", "part-2.flac", "remaining.wv"), parsed.lanes.map { it.fileName })
        assertEquals(listOf("All vocals", "Bass", "Remaining"), parsed.lanes.map { it.label })
        assertEquals(FRAMES, parsed.audibleFrames)
        assertArrayEquals(bytes, parsed.toBytes())
        assertTrue(parsed.revisionSha256.matches(Regex("[a-f0-9]{64}")))
        assertFalse(parsed.lanes.any { it.fileName in setOf("vocals.opus", "drums.opus", "bass.opus", "guitar.opus", "piano.opus", "other.opus") })
    }

    @Test
    fun incompatibleOrUnknownSelectionsCannotBypassThePickerThroughTheManifest() {
        listOf(
            listOf("vocals", "backing-vocals"),
            listOf("vocals", "vocals"),
            listOf("vocals", "invented-source"),
            listOf("vocals", "remaining"),
            emptyList()
        ).forEach { ids ->
            val changed = fixture().put("selectedSourceIds", JSONArray(ids))
            assertRejected(signed(changed))
        }
    }

    @Test
    fun exactSourceIdentityAndIntegrityRejectChangedOrForeignBytes() {
        val bytes = signed(fixture())
        assertThrows(IllegalStateException::class.java) {
            parseCustomSourceManifest(bytes, "XyZ0123_-ab", SOURCE_SHA)
        }
        assertThrows(IllegalStateException::class.java) {
            parseCustomSourceManifest(bytes, VIDEO_ID, "e".repeat(64))
        }
        val tampered = JSONObject(bytes.toString(Charsets.UTF_8)).put("planSha256", "f".repeat(64))
        assertRejected(CanonicalJson.encode(tampered))
    }

    @Test
    fun distinctRolesAndFilesMayContainIdenticalRealAudioBytes() {
        val json = fixture()
        val lanes = json.getJSONArray("lanes")
        repeat(lanes.length()) { index -> lanes.getJSONObject(index).put("sha256", "2".repeat(64)) }
        val manifest = parseCustomSourceManifest(signed(json), VIDEO_ID, SOURCE_SHA)
        assertEquals(listOf("vocals", "bass", "remaining"), manifest.lanes.map { it.sourceId })
        assertEquals(listOf("part-1.flac", "part-2.flac", "remaining.wv"), manifest.lanes.map { it.fileName })
        assertEquals(setOf("2".repeat(64)), manifest.lanes.map { it.sha256 }.toSet())
    }

    @Test
    fun strictSchemaCodecTimelineAndIdentityRejectResignedInvalidDeclarations() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("extra", true) },
            { it.put("schemaVersion", "1") },
            { it.put("sampleRateHz", 44_100) },
            { it.put("channels", 1) },
            { it.put("audibleFrames", FRAMES + 1L) },
            { it.getJSONArray("lanes").getJSONObject(0).put("audibleFrames", FRAMES - 1L) },
            { it.getJSONArray("lanes").getJSONObject(0).put("fileName", "../part-1.flac") },
            { it.getJSONArray("lanes").getJSONObject(0).put("fileName", "vocals.opus") },
            { it.getJSONArray("lanes").getJSONObject(0).put("sourceId", "bass") },
            { it.getJSONArray("lanes").getJSONObject(0).put("label", "Wrong source") },
            { it.getJSONArray("lanes").getJSONObject(0).put("sha256", "X".repeat(64)) },
            { it.getJSONArray("lanes").getJSONObject(0).put("byteLength", 0L) },
            { it.getJSONArray("lanes").getJSONObject(2).put("sourceId", "vocals") },
            { it.getJSONArray("lanes").getJSONObject(2).put("extra", true) },
            { it.getJSONArray("lanes").remove(2) }
        )
        changes.forEach { mutate ->
            val changed = fixture()
            mutate(changed)
            assertRejected(signed(changed))
        }
    }

    @Test
    fun eightTargetsAndRemainingAreExplicitRealLanesWithoutUnboundedSlots() {
        val ids = listOf("lead-vocals", "bass", "kick", "acoustic-guitar", "violin", "flute", "organ", "synth")
        val labels = listOf("Lead vocals", "Bass", "Kick", "Acoustic guitar", "Violin", "Flute", "Organ", "Synthesizer")
        val root = fixture().put("selectedSourceIds", JSONArray(ids))
        val lanes = JSONArray()
        ids.forEachIndexed { index, id ->
            lanes.put(lane(id, labels[index], "part-${index + 1}.flac", (index + 1).toString(16).padStart(64, '0')))
        }
        lanes.put(lane("remaining", "Remaining", "remaining.wv", "a".repeat(64)))
        root.put("lanes", lanes)
        val durable = signed(root)
        val reopened = parseCustomSourceManifest(durable, VIDEO_ID, SOURCE_SHA)
        assertEquals(ids + "remaining", reopened.lanes.map { it.sourceId })
        assertEquals((1..8).map { "part-$it.flac" } + "remaining.wv", reopened.lanes.map { it.fileName })
        assertEquals(labels + "Remaining", reopened.lanes.map { it.label })
        assertEquals(9, reopened.lanes.map { it.sha256 }.toSet().size)
        assertArrayEquals(durable, reopened.toBytes())
        val files = reopened.lanes.map { it.fileName }.toSet() + setOf("original.opus", ".stemwise-sources.json")
        validateCustomSourceManifestFiles(reopened, files)
        reopened.lanes.forEach { asset ->
            val remaining = asset.sourceId == "remaining"
            val facts = PreparedSourceAudioFacts(48_000, 2, if (remaining) 32 else 16, remaining, FRAMES,
                mime = if (remaining) "audio/wavpack" else "audio/flac")
            validateCustomSourceAudioAsset(asset, asset.sha256, asset.byteLength, facts)
            assertThrows(IllegalStateException::class.java) { validateCustomSourceManifestFiles(reopened, files - asset.fileName) }
            assertThrows(IllegalStateException::class.java) {
                validateCustomSourceAudioAsset(asset, "f".repeat(64), asset.byteLength, facts)
            }
            assertThrows(IllegalStateException::class.java) {
                validateCustomSourceAudioAsset(asset, asset.sha256, asset.byteLength - 1, facts)
            }
            assertThrows(IllegalStateException::class.java) {
                validateCustomSourceAudioAsset(asset, asset.sha256, asset.byteLength, facts.copy(audibleFrames = FRAMES - 1))
            }
        }
        for (index in 0..8) {
            val changed = JSONObject(durable.toString(Charsets.UTF_8))
            changed.getJSONArray("lanes").remove(index)
            assertRejected(signed(changed))
        }
        for (mutate in listOf<(JSONArray) -> Unit>(
            { it.getJSONObject(1).put("fileName", "part-1.flac") },
            { it.getJSONObject(1).put("sourceId", "lead-vocals") },
            { it.getJSONObject(1).put("fileName", "replacement-part-2.flac") },
            { val first = it.get(0); it.put(0, it.get(1)); it.put(1, first) }
        )) {
            val changed = JSONObject(durable.toString(Charsets.UTF_8))
            mutate(changed.getJSONArray("lanes"))
            assertRejected(signed(changed))
        }
        val equalAudio = JSONObject(durable.toString(Charsets.UTF_8))
        repeat(9) { equalAudio.getJSONArray("lanes").getJSONObject(it).put("sha256", "c".repeat(64)) }
        assertEquals(reopened.lanes.map { it.sourceId to it.fileName },
            parseCustomSourceManifest(signed(equalAudio), VIDEO_ID, SOURCE_SHA).lanes.map { it.sourceId to it.fileName })
        assertArrayEquals(durable, reopened.toBytes())

        root.getJSONArray("selectedSourceIds").put("harmonica")
        lanes.put(8, lane("harmonica", "Harmonica", "part-9.flac", "b".repeat(64)))
        lanes.put(lane("remaining", "Remaining", "remaining.wv", "a".repeat(64)))
        assertRejected(signed(root))
    }

    @Test
    fun undeclaredMissingOrNoncanonicalCustomFilesCannotBecomePlayable() {
        val manifest = parseCustomSourceManifest(signed(fixture()), VIDEO_ID, SOURCE_SHA)
        val full = setOf("original.opus") + setOf(
            ".stemwise-sources.json", "part-1.flac", "part-2.flac", "remaining.wv"
        )
        validateCustomSourceManifestFiles(manifest, full)
        validateCustomSourceManifestFiles(manifest, full + setOf("notes.txt", "replacement-part-1.flac"))
        listOf(
            full - "part-2.flac",
            full - "remaining.wv",
            full - ".stemwise-sources.json"
        ).forEach { files ->
            assertThrows(IllegalStateException::class.java) {
                validateCustomSourceManifestFiles(manifest, files)
            }
        }
    }

    @Test
    fun extractorRawFlacStillRequiresInspectedFlacAndExactTargetLayout() {
        val lane = parseCustomSourceManifest(signed(fixture()), VIDEO_ID, SOURCE_SHA).lanes.first()
        val flac = PreparedSourceAudioFacts(48_000, 2, 16, false, FRAMES, mime = "audio/flac")
        validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, flac, "audio/raw", 48_000, 2)
        val invalid: List<() -> Unit> = listOf(
            { validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, flac.copy(mime = "audio/raw"), "audio/raw", 48_000, 2) },
            { validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, flac.copy(bitsPerSample = 24), "audio/raw", 48_000, 2) },
            { validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, flac.copy(channels = 1), "audio/raw", 48_000, 2) },
            { validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, flac, "audio/raw", 44_100, 2) },
            { validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, flac, "audio/raw", 48_000, 1) }
        )
        invalid.forEach { rejected -> assertThrows(IllegalStateException::class.java) { rejected() } }
    }

    @Test
    fun reopenedMediaFactsRejectSubstitutionWrongCodecAndTimelineEvenWithAValidManifest() {
        val lane = parseCustomSourceManifest(signed(fixture()), VIDEO_ID, SOURCE_SHA).lanes.first()
        val opus = PreparedSourceAudioFacts(48_000, 2, 16, false, FRAMES)
        validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, opus, "audio/flac", 48_000, 2)
        val invalid: List<() -> Unit> = listOf(
            { validateCustomSourceAsset(lane, "f".repeat(64), lane.byteLength, opus, "audio/flac", 48_000, 2) },
            { validateCustomSourceAsset(lane, lane.sha256, lane.byteLength + 1, opus, "audio/flac", 48_000, 2) },
            { validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, opus.copy(audibleFrames = FRAMES - 1), "audio/flac", 48_000, 2) },
            { validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, opus.copy(channels = 1), "audio/flac", 48_000, 2) },
            { validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, opus, "audio/opus", 48_000, 2) },
            { validateCustomSourceAsset(lane, lane.sha256, lane.byteLength, opus, "audio/opus", 44_100, 2) }
        )
        invalid.forEach { rejected -> assertThrows(IllegalStateException::class.java) { rejected() } }
    }

    @Test
    fun partialCustomPublicationPreservesOriginalWithoutDeclaringCustomReady() {
        val pending = inspectSelectedSongEntryNames(
            VIDEO_ID,
            listOf("original.opus") + listOf("part-1.flac", ".stemwise-source-job.json")
        )
        assertEquals(SelectedSongFolderState.INCOMPLETE, pending.state)
        assertTrue(pending.hasOriginal)
        assertTrue(pending.hasCustomSourceMedia)
        assertFalse(pending.hasStructurallyCompleteCustomSourceSet)

        val complete = inspectSelectedSongEntryNames(
            VIDEO_ID,
            listOf("original.opus") + listOf(
                "part-1.flac", "part-2.flac", "remaining.wv", ".stemwise-sources.json"
            )
        )
        assertEquals(SelectedSongFolderState.COMPLETE, complete.state)
        assertTrue(complete.hasStructurallyCompleteCustomSourceSet)
    }

    @Test
    fun customNamespacesAreFiniteExactAndDoNotGrantUnknownOrNestedPaths() {
        listOf("part-17.flac", "Part-1.flac", "sources/part-1.flac", ".stemwise-source-job.json.bak").forEach { name ->
            assertEquals(
                SelectedSongFolderState.COMPLETE,
                inspectSelectedSongEntryNames(VIDEO_ID, listOf("original.opus", name)).state
            )
        }
        val temp = inspectSelectedSongEntryNames(
            VIDEO_ID,
            listOf("original.opus", ".stemwise-sources.json.tmp", ".stemwise-source-job.json.tmp")
        )
        assertEquals(SelectedSongFolderState.INCOMPLETE, temp.state)
        assertFalse(temp.hasStructurallyCompleteCustomSourceSet)
        assertTrue(canonicalMediaMimeMatches("part-1.flac", "audio/flac"))
        assertFalse(canonicalMediaMimeMatches("part-1.flac", "audio/opus"))
    }

    @Test
    fun explicitDeletePartsIncludesIncompleteWorkAndRecoveryAuthorityButPreservesSongData() {
        val partFiles = SelectedLibraryContract.customStemFiles + listOf(
            ".stemwise-sources.json", ".stemwise-sources.json.tmp", ".stemwise-source-job.json",
            ".stemwise-source-job.json.tmp"
        )
        assertTrue(partFiles.all(::isPartDeletionFile))
        listOf("original.opus", "cover.png", "song.json", "source-unknown.opus", "part-17.flac").forEach {
            assertFalse(isPartDeletionFile(it))
        }
        val present = partFiles.toSet() + setOf("original.opus", "song.json", "cover.png")
        assertEquals(setOf("original.opus", "song.json", "cover.png"), present - present.filter(::isPartDeletionFile).toSet())
    }

    @Test
    fun parserRequiresBoundedCanonicalBytesRatherThanPermissiveJson() {
        val valid = signed(fixture())
        assertRejected(byteArrayOf())
        assertRejected(ByteArray(CUSTOM_SOURCE_MANIFEST_MAX_BYTES.toInt() + 1) { 32 })
        assertRejected(byteArrayOf(32) + valid)
        assertRejected(valid + byteArrayOf(10))
    }

    @Test fun maximumIdentifierEvidenceForEightTargetsRoundTripsThroughTheActualSerializer() {
        val ids = listOf("lead-vocals", "bass", "kick", "acoustic-guitar", "violin", "flute", "organ", "synth")
        val lanes = ids.mapIndexed { index, id ->
            val hash = (index + 1).toString(16).repeat(64)
            val evidence = SourceTimingEvidence("界".repeat(512), SOURCE_SHA, "b".repeat(64), "界".repeat(512), 1,
                "界".repeat(512), 63, emptyMap(), id, "界".repeat(512), "界".repeat(512),
                48_000, 2, FRAMES, FRAMES, FRAMES, 0, 0, 0,
                SourceTimingRawArtifact(hash, 1_000, 48_000, 2, FRAMES), hash, 1_000, FRAMES, SOURCE_TIMING_COPY_PROFILE)
            CustomSourceAsset(id, app.stemwise.model.StemSourceCatalogue.source(id)!!.label,
                "part-${index + 1}.flac", hash, 1_000, FRAMES, timingEvidence = evidence)
        } + CustomSourceAsset("remaining", "Remaining", "remaining.wv", "a".repeat(64), 1_000, FRAMES)
        val manifest = CustomSourceManifest(VIDEO_ID, SOURCE_SHA, "b".repeat(64), ids, FRAMES, lanes)
        val bytes = manifest.toBytes()
        assertTrue(bytes.size > 16 * 1024)
        assertEquals(manifest, parseCustomSourceManifest(bytes, VIDEO_ID, SOURCE_SHA))
        val oversized = lanes.map { lane -> lane.timingEvidence?.let { evidence ->
            lane.copy(timingEvidence = evidence.copy(options = mapOf("add_opt1" to "1".repeat(150_000)) ))
        } ?: lane }
        assertThrows(IllegalStateException::class.java) {
            manifest.copy(lanes = oversized).toBytes()
        }
    }

    private fun assertRejected(bytes: ByteArray) {
        assertThrows(IllegalStateException::class.java) {
            parseCustomSourceManifest(bytes, VIDEO_ID, SOURCE_SHA)
        }
    }

    private fun fixture(): JSONObject = JSONObject()
        .put("schemaVersion", 3)
        .put("kind", "stemwise.custom-sources")
        .put("videoId", VIDEO_ID)
        .put("sourceSha256", SOURCE_SHA)
        .put("planSha256", "b".repeat(64))
        .put("sampleRateHz", 48_000)
        .put("channels", 2)
        .put("audibleFrames", FRAMES)
        .put("selectedSourceIds", JSONArray(listOf("vocals", "bass")))
        .put("lanes", JSONArray().apply {
            put(lane("vocals", "All vocals", "part-1.flac", "1".repeat(64)))
            put(lane("bass", "Bass", "part-2.flac", "2".repeat(64)))
            put(lane("remaining", "Remaining", "remaining.wv", "3".repeat(64)))
        })

    private fun lane(id: String, label: String, file: String, hash: String): JSONObject = JSONObject()
        .put("sourceId", id)
        .put("label", label)
        .put("fileName", file)
        .put("sha256", hash)
        .put("byteLength", 1_000L)
        .put("audibleFrames", FRAMES)
        .apply { if (id == "remaining") put("remainingProducer", NATIVE_ORIGINAL_48K_REMAINING_PRODUCER) }

    private fun signed(json: JSONObject): ByteArray {
        json.remove("integritySha256")
        json.put("integritySha256", CanonicalJson.sha256(json))
        return CanonicalJson.encode(json)
    }

    companion object {
        private const val VIDEO_ID = "AbCdEf_12-3"
        private val SOURCE_SHA = "0".repeat(64)
        private const val FRAMES = 48_000L * 60L
    }
}
