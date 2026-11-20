package app.stemwise.analysis

import app.stemwise.data.SelectedLibraryContract
import app.stemwise.enrichment.InstalledMediaIdentity
import app.stemwise.enrichment.createInstalledEnrichmentDocument
import app.stemwise.enrichment.installedEnrichmentDigest
import app.stemwise.enrichment.validateInstalledEnrichmentDocument
import app.stemwise.ui.withImportedSongDetail
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ImportedAnalysisIntegrityTest {
    @Test fun verifiedRhythmProjectionLeavesWaveformCacheUnmaterialized() {
        val songId = "A0000000000"
        val document = validInstalledManifest(songId)
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        val verified = requireNotNull(parseActiveInstalledAnalysisManifest(
            app.stemwise.data.RootAuthority("metronome", "content://provider/tree/metronome", 1L),
            bytes, songId, sha256(bytes), document.getJSONObject("install").getString("originalSha256")
        ) { JSONObject(it.toString(Charsets.UTF_8)) })
        val timeline = projectImportedMetronomeTimeline(verified.analysis?.optJSONObject("rhythm"))
        assertEquals(25, timeline.beats.size)
        assertEquals(listOf(BeatEvent(500L, true), BeatEvent(969L, false), BeatEvent(1_438L, false)),
            timeline.beats.take(3))
        assertEquals(listOf(MeterSegment(0L, 9_000L, 4, 4), MeterSegment(9_000L, 12_000L, 3, 4)),
            timeline.meterSegments)
        var decoded = false
        assertEquals(listOf(.25f), verified.waveform(50L) { decoded = true; floatArrayOf(.25f) })
        assertTrue(decoded)
        assertTrue(metronomeAvailableFor(timeline.beats.size))
        assertEquals(60_000f / 473.5f, effectiveGridBpm(96f, timeline.beats.map(BeatEvent::timeMs)), .001f)
    }

    @Test fun validatedLyricsAvailabilityAndReasonReachSongWithoutBeingUpgradedByItsTracks() {
        val songId = "A0000000000"
        val reason = "Only independently verified performed lines are included; uncertain occurrences were withheld."
        val base = app.stemwise.model.Song(songId, songId, "Song", "Artist", 60_000L,
            app.stemwise.model.SongState.READY, androidx.compose.ui.graphics.Color.White, artworkGlyph = "S",
            audioRevision = "same-audio", mediaRevision = "same-manifest")
        for (status in listOf(null, "complete", "partial", "unavailable")) {
            val installed = validInstalledManifest(songId)
            when (status) {
                null -> installed.remove("lyrics")
                "complete" -> installed.getJSONObject("lyrics").put("status", status).remove("reason")
                "partial" -> installed.getJSONObject("lyrics").put("status", status).put("reason", reason)
                else -> installed.put("lyrics", JSONObject().put("status", status).put("reason", reason).put("tracks", JSONArray()))
            }
            installed.getJSONObject("install").put("generatedDigest", installedEnrichmentDigest(installed))
            validateInstalledEnrichmentDocument(installed, songId, installed.getJSONObject("install").getString("originalSha256"))
            val detail = projectImportedSongDetail(installed)
            val song = base.withImportedSongDetail(detail)
            val expectedReason = reason.takeIf { status == "partial" || status == "unavailable" }
            assertEquals(status, detail.lyricsStatus)
            assertEquals(expectedReason, detail.lyricsReason)
            assertEquals(status, song.lyricsStatus)
            assertEquals(expectedReason, song.lyricsReason)
            assertEquals(detail.lyricTracks, song.lyricTracks)
            assertEquals(status == "complete" || status == "partial", song.lyricTracks.isNotEmpty())
            val updated = song.copy(progress = .5f, hasStems = true)
            assertEquals(status, updated.lyricsStatus)
            assertEquals(expectedReason, updated.lyricsReason)
            assertEquals(base.audioRevision, updated.audioRevision)
        }
    }

    @Test
    fun sharedWaveformDoesNotExposeItsMutableStorage() {
        val input = floatArrayOf(0.25f, 0.5f)
        val verified = app.stemwise.enrichment.VerifiedInstalledEnrichment(JSONObject(), "a".repeat(64))
        val peaks = verified.waveform(5L) { input }
        input[0] = 1f
        assertEquals(0.25f, peaks[0])
        assertTrue(runCatching { (peaks as MutableList<Float>)[0] = 1f }.isFailure)
        assertEquals(listOf(0.25f, 0.5f), verified.waveform(5L) { error("decoded twice") })
    }

    @Test
    fun verifiedManifestCannotBeChangedThroughDecoderOrConsumerAliases() {
        val cache = app.stemwise.enrichment.InstalledEnrichmentVerificationCache()
        val root = app.stemwise.data.RootAuthority("immutable", "content://provider/tree/immutable", 1L)
        val original = validInstalledManifest("A0000000000")
        val title = original.getJSONObject("metadata").getString("title")
        val bytes = original.toString().toByteArray(Charsets.UTF_8)
        val source = original.getJSONObject("install").getString("originalSha256")
        val verified = cache.verify(bytes, root, "A0000000000", source, decode = { original })
        original.getJSONObject("metadata").put("title", "Changed after validation")
        assertEquals(title, verified.document.getJSONObject("metadata").getString("title"))
        verified.document.getJSONObject("metadata").put("title", "Changed by a consumer")
        verified.detail.getJSONObject("metadata").put("title", "Changed through a detail projection")
        val analysisStatus = verified.analysis!!.getString("status")
        verified.analysis!!.put("status", "Changed through an analysis projection")
        assertEquals(analysisStatus, verified.analysis!!.getString("status"))
        assertEquals(title, verified.detail.getJSONObject("metadata").getString("title"))
        assertEquals(title, cache.find(root, "A0000000000", source, sha256(bytes))!!
            .document.getJSONObject("metadata").getString("title"))
    }

    @Test
    fun maximumDurationWaveformManifestParsesAndDecodesOncePerOwner() {
        val cache = app.stemwise.enrichment.InstalledEnrichmentVerificationCache()
        val root = app.stemwise.data.RootAuthority("maximum", "content://provider/tree/maximum", 1L)
        val songId = "A0000000000"
        val installed = validInstalledManifest(songId)
        installed.put("lyrics", JSONObject().put("status", "unavailable").put("reason", "Fixture")
            .put("tracks", JSONArray()))
        val analysis = installed.getJSONObject("analysis").put("durationMs", 7_200_000L).put("status", "partial")
        for (feature in listOf("rhythm", "key", "structure", "tuning", "loudness")) {
            analysis.put(feature, JSONObject().put("status", "unavailable").put("reason", "Fixture"))
        }
        analysis.remove("diagnostics")
        val levels = JSONArray()
        for (spacing in listOf(5L, 50L, 250L)) {
            val count = (7_200_000L / spacing).toInt()
            levels.put(JSONObject().put("frameDurationMs", spacing).put("frameCount", count)
                .put("data", java.util.Base64.getEncoder().encodeToString(ByteArray(count * 6))))
        }
        analysis.getJSONObject("waveform").put("levels", levels)
        installed.getJSONObject("install").put("generatedDigest", installedEnrichmentDigest(installed))
        val bytes = installed.toString().toByteArray(Charsets.UTF_8)
        val source = installed.getJSONObject("install").getString("originalSha256")
        var parses = 0
        // JsonReader is an Android boundary; this JVM check keeps verification and waveform decoding real.
        val decode: (ByteArray) -> JSONObject = { parses++; JSONObject(it.toString(Charsets.UTF_8)) }
        val verified = cache.verify(bytes, root, songId, source, decode = decode)
        assertEquals(verified, cache.verify(bytes, root, songId, source, decode = decode))
        assertEquals(1, parses)
        var waveformDecodes = 0
        val fine = verified.waveform(5L) {
            waveformDecodes++
            decodeImportedWaveformLevel(verified.document.getJSONObject("analysis")
                .getJSONObject("waveform").getJSONArray("levels").getJSONObject(0)).toFloatArray()
        }
        assertEquals(1_440_000, fine.size)
        assertEquals(fine, verified.waveform(5L) { error("second decode") })
        assertEquals(1, waveformDecodes)
    }

    @Test
    fun sharedVerificationRetainsExactOwnersAcrossUnrelatedScans() {
        val cache = app.stemwise.enrichment.InstalledEnrichmentVerificationCache()
        val root = app.stemwise.data.RootAuthority("library", "content://provider/tree/library", 1L)
        val songId = "A0000000000"
        val installed = validInstalledManifest(songId)
        val source = installed.getJSONObject("install").getString("originalSha256")
        val bytes = installed.toString().toByteArray(Charsets.UTF_8)
        var decodes = 0
        val decode: (ByteArray) -> JSONObject = { decodes++; JSONObject(it.toString(Charsets.UTF_8)) }
        val first = cache.verify(bytes, root, songId, source, decode = decode)
        assertEquals(first, cache.verify(bytes, root, songId, source, decode = decode))
        assertEquals(1, decodes)
        assertEquals(first, cache.find(root, songId, source, sha256(bytes)))
        assertNull(cache.find(root.copy(epoch = 2L), songId, source, sha256(bytes)))
        var waveformDecodes = 0
        // The supported two-hour, 5 ms waveform retains one decoded array across requests.
        val peaks = first.waveform(5L) { waveformDecodes++; FloatArray(7_200_000 / 5) }
        assertEquals(peaks, first.waveform(5L) { error("waveform decoded twice") })
        assertEquals(1, waveformDecodes)
        val otherId = "B0000000000"
        val other = validInstalledManifest(otherId)
        cache.verify(other.toString().toByteArray(Charsets.UTF_8), root, otherId,
            other.getJSONObject("install").getString("originalSha256"), decode = decode)
        assertEquals(first, cache.find(root, songId, source, sha256(bytes)))
        assertEquals(peaks, first.waveform(5L) { error("scan evicted active waveform") })
        val changedBytes = bytes + byteArrayOf(32)
        val changed = cache.verify(changedBytes, root, songId, source, decode = decode)
        assertEquals(3, decodes)
        assertEquals(first, cache.find(root, songId, source, sha256(bytes)))
        assertEquals(changed, cache.find(root, songId, source, sha256(changedBytes)))
        assertTrue(runCatching {
            cache.verify(bytes + byteArrayOf(33), root, songId, source)
        }.isFailure)
        assertEquals(changed, cache.find(root, songId, source, sha256(changedBytes)))
    }

    @Test fun verificationCacheEvictsOldOwnersWithinItsCombinedManifestAndWaveformReservation() {
        val cache = app.stemwise.enrichment.InstalledEnrichmentVerificationCache()
        val root = app.stemwise.data.RootAuthority("library", "content://provider/tree/library", 1L)
        val owners = (0..7).map { index ->
            val id = "A" + index.toString().padStart(10, '0')
            val document = validInstalledManifest(id)
            val bytes = document.toString().toByteArray(Charsets.UTF_8)
            val source = document.getJSONObject("install").getString("originalSha256")
            cache.verify(bytes, root, id, source, decode = { JSONObject(it.toString(Charsets.UTF_8)) })
            Triple(id, source, sha256(bytes))
        }
        val first = owners.first()
        val last = owners.last()
        assertNull(cache.find(root, first.first, first.second, first.third))
        assertTrue(cache.find(root, last.first, last.second, last.third) != null)
    }

    @Test
    fun digestProjectsWithoutSerializingOrMutatingTheInputRoot() {
        val input = object : JSONObject() {
            override fun toString(): String = error("whole input root was serialized")
        }.put("analysis", JSONObject().put("status", "unavailable"))
            .put("install", JSONObject().put("contentRevision", 2L).put("generatedDigest", "old"))
        val expected = app.stemwise.data.CanonicalJson.sha256(JSONObject()
            .put("analysis", JSONObject().put("status", "unavailable"))
            .put("install", JSONObject().put("contentRevision", 2L)))
        assertEquals(expected, installedEnrichmentDigest(input))
        assertTrue(input.getJSONObject("install").has("generatedDigest"))
    }

    @Test
    fun exactManifestDetailProjectsLyricsAndMetadataOnlyOnDemand() {
        val manifest = JSONObject()
            .put(
                "metadata",
                JSONObject()
                    .put("title", "Song")
                    .put("artists", JSONArray().put("Artist"))
                    .put("description", "Full detail")
            )
            .put(
                "lyrics",
                JSONObject().put(
                    "tracks",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "original")
                            .put("kind", "original")
                            .put("format", "plain")
                            .put("language", "en")
                            .put("text", "One\nTwo")
                    )
                )
            )

        val detail = projectImportedSongDetail(manifest)

        assertEquals("One\nTwo", detail.lyricTracks.single().text)
        assertEquals("Full detail", detail.additionalInfo["Description"])
    }

    @Test
    fun runtimeInstalledAnalysisRequiresExactRevisionSongAndClosedContract() {
        val songId = "A0000000000"
        val installed = validInstalledManifest(songId)
        validateInstalledEnrichmentDocument(
            installed,
            songId,
            installed.getJSONObject("install").getString("originalSha256")
        )
        val bytes = installed.toString().toByteArray(Charsets.UTF_8)
        val revision = sha256(bytes)
        val sourceSha256 = installed.getJSONObject("install").getString("originalSha256")
        val decode: (ByteArray) -> JSONObject = { JSONObject(it.toString(Charsets.UTF_8)) }

        assertNotNull(
            parseActiveInstalledAnalysisManifest(
                app.stemwise.data.RootAuthority("analysis-fixture", "content://provider/tree/analysis", 1L),
                bytes, songId, revision, sourceSha256, decode
            )
        )
        assertNull(
            parseActiveInstalledAnalysisManifest(
                app.stemwise.data.RootAuthority("analysis-fixture", "content://provider/tree/analysis", 1L),
                bytes, songId, "0".repeat(64), sourceSha256, decode
            )
        )
        assertNull(
            parseActiveInstalledAnalysisManifest(
                app.stemwise.data.RootAuthority("analysis-fixture", "content://provider/tree/analysis", 1L),
                bytes, "B0000000000", revision, sourceSha256, decode
            )
        )
        assertNull(
            parseActiveInstalledAnalysisManifest(
                app.stemwise.data.RootAuthority("analysis-fixture", "content://provider/tree/analysis", 1L),
                bytes, songId, revision, "f".repeat(64), decode
            )
        )

        val openContractBytes = JSONObject(installed.toString())
            .put("unexpected", true)
            .toString()
            .toByteArray(Charsets.UTF_8)
        assertNull(
            parseActiveInstalledAnalysisManifest(
                app.stemwise.data.RootAuthority("analysis-fixture", "content://provider/tree/analysis", 1L),
                openContractBytes,
                songId,
                sha256(openContractBytes),
                sourceSha256,
                decode
            )
        )
    }

    private fun validInstalledManifest(songId: String): JSONObject {
        val media = mapOf(
            SelectedLibraryContract.ORIGINAL to InstalledMediaIdentity(
                sha256 = sha256(byteArrayOf(0)),
                byteLength = 1L
            )
        )
        val fixture = listOf(
            File("src/development/assets/qa/enrichment/full-song-enrichment-v2.json"),
            File("app/src/development/assets/qa/enrichment/full-song-enrichment-v2.json")
        ).first(File::isFile)
        val payload = JSONObject(fixture.readText()).also {
            it.getJSONObject("source").put("videoId", songId)
        }
        val artwork = JSONObject()
            .put("kind", "youtube-thumbnail")
            .put(
                "source",
                JSONObject()
                    .put("name", "YouTube Music")
                    .put("url", "https://music.youtube.com/watch?v=$songId")
            )
        return createInstalledEnrichmentDocument(
            incoming = payload,
            originalSha256 = media.getValue(SelectedLibraryContract.ORIGINAL).sha256,
            media = media,
            contentRevision = 1L,
            payloadSha256 = "f".repeat(64),
            artwork = artwork,
            artworkSha256 = "a".repeat(64),
            artworkBytes = 1
        )
    }

    @Test
    fun runtimeRejectsInvalidNestedGroupsEvenWithFreshDigestsAndExactMedia() {
        val songId = "A0000000000"
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("metadata").getJSONObject("album").put("releaseDate", 20260729) },
            {
                val track = it.getJSONObject("lyrics").getJSONArray("tracks").getJSONObject(0)
                val lines = track.getJSONArray("lines")
                track.put("lines", JSONArray().put(lines.getJSONObject(1)).put(lines.getJSONObject(0)).also { reversed ->
                    for (index in 2 until lines.length()) reversed.put(lines.getJSONObject(index))
                })
            },
            { it.getJSONObject("analysis").getJSONArray("toolRuns").getJSONObject(0).put("status", "pretend") }
        )
        mutations.forEach { mutate ->
            val installed = validInstalledManifest(songId)
            mutate(installed)
            installed.getJSONObject("install").put("generatedDigest", installedEnrichmentDigest(installed))
            val bytes = installed.toString().toByteArray(Charsets.UTF_8)
            assertNull(parseActiveInstalledAnalysisManifest(
                app.stemwise.data.RootAuthority("analysis-fixture", "content://provider/tree/analysis", 1L),
                bytes, songId, sha256(bytes),
                installed.getJSONObject("install").getString("originalSha256")
            ) { JSONObject(it.toString(Charsets.UTF_8)) })
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

}
