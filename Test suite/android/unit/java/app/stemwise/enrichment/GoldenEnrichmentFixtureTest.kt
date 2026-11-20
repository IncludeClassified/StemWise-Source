package app.stemwise.enrichment

import app.stemwise.analysis.decodeImportedWaveformLevel
import app.stemwise.analysis.importedWaveformByteCountMatches
import app.stemwise.analysis.projectImportedMetronomeTimeline
import app.stemwise.analysis.projectImportedSongSections
import app.stemwise.data.CanonicalJson
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.parseRoomAdditionalInfo
import app.stemwise.data.projectEnrichmentAnalysis
import app.stemwise.data.projectEnrichmentLyricTracks
import app.stemwise.data.projectEnrichmentMetadata
import app.stemwise.data.withOriginalAlbumAndYear
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.zip.CRC32
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenEnrichmentFixtureTest {
    @Test fun keyAndTuningUncertaintySurviveValidatedFeatureProjectionWithoutChangingValues() {
        for (uncertain in listOf(false, true)) {
            val document = fixture(FULL)
            val analysis = document.getJSONObject("analysis")
            val keyReason = "Similar support for the two strongest keys. ".repeat(8).trim()
            val tuningReason = "Reference pitch varies across the recording. ".repeat(8).trim()
            if (uncertain) {
                analysis.put("status", "partial")
                analysis.getJSONObject("key").put("status", "uncertain").put("reason", keyReason)
                analysis.getJSONObject("tuning").put("status", "uncertain").put("reason", tuningReason)
            }
            validateEnrichmentContract(document, hasCover = true)
            val projected = projectEnrichmentAnalysis(analysis)
            assertEquals(if (uncertain) "uncertain" else "complete", projected.keyStatus)
            assertEquals(if (uncertain) "uncertain" else "complete", projected.tuningStatus)
            assertEquals(if (uncertain) keyReason else null, projected.keyReason)
            assertEquals(if (uncertain) tuningReason else null, projected.tuningReason)
            assertEquals("C# Minor", projected.musicalKey)
            assertEquals(.79, checkNotNull(projected.tuningCents), 0.0)
        }
    }

    @Test
    fun metadataReplacementUsesCurrentOriginalTagsWhenAlbumOrDateIsOmitted() {
        val document = fixture(FULL)
        val metadata = document.getJSONObject("metadata")
        metadata.remove("album")
        metadata.put("title", "Current title").put("artists", JSONArray().put("Current artist"))
        validateEnrichmentContract(document, hasCover = true)
        val projection = projectEnrichmentMetadata(metadata)
        assertEquals("", projection.withOriginalAlbumAndYear(null, null).album)
        assertEquals("", projection.withOriginalAlbumAndYear(null, null).year)
        val intrinsic = projection.withOriginalAlbumAndYear("Original album", "2001")
        assertEquals("Original album", intrinsic.album)
        assertEquals("2001", intrinsic.year)
        metadata.put("album", JSONObject().put("title", "New album"))
        val replaced = projectEnrichmentMetadata(metadata).withOriginalAlbumAndYear("Original album", "2001")
        assertEquals("New album", replaced.album)
        assertEquals("2001", replaced.year)
        metadata.getJSONObject("album").put("releaseDate", "2026-10-18")
        assertEquals("2026", projectEnrichmentMetadata(metadata)
            .withOriginalAlbumAndYear("Original album", "2001").year)
    }
    @Test
    fun youtubeOriginalUsesActualCanonicalCodecAndSurvivesInstallation() {
        for (original in listOf("original.opus", "original.flac")) {
            val incoming = fixture(FULL)
            val source = incoming.getJSONObject("source")
            val identity = InstalledMediaIdentity("a".repeat(64), 123456L)
            validateEnrichmentOriginalBinding(source, original, identity, 12000L)
            val installed = createInstalledEnrichmentDocument(incoming, identity.sha256,
                mapOf(original to identity), 1L, "b".repeat(64),
                incoming.getJSONObject("artwork"), "c".repeat(64), 100)

            validateInstalledEnrichmentDocument(installed, "FIXTURE0001", identity.sha256)

            assertEquals(mapOf(original to identity), installedMediaIdentities(installed))
            assertFalse(installed.getJSONObject("source").has("recording"))
        }
    }

    @Test
    fun installedOriginalRejectsUnknownAndAmbiguousFilesWithoutChangingInput() {
        val identity = InstalledMediaIdentity("a".repeat(64), 123456L)
        for (media in listOf(
            emptyMap(),
            mapOf("original.opus" to identity, "original.flac" to identity),
            mapOf("Original.flac" to identity),
            mapOf("original.wav" to identity),
            mapOf("original.flac" to identity, "part-1.flac" to identity)
        )) {
            val incoming = fixture(FULL)
            val before = incoming.toString()
            assertTrue(runCatching {
                createInstalledEnrichmentDocument(incoming, identity.sha256,
                    media, 1L, "b".repeat(64), incoming.getJSONObject("artwork"),
                    "c".repeat(64), 100)
            }.isFailure)
            assertEquals(before, incoming.toString())
        }
        val mutations: List<(JSONArray) -> Unit> = listOf(
            { it.remove(0) },
            { it.put(JSONObject(it.getJSONObject(0).toString()).put("path", "original.opus")) },
            { it.getJSONObject(0).put("path", "original.wav") },
            { it.getJSONObject(0).put("path", "Original.flac") }
        )
        for (mutation in mutations) {
            val incoming = fixture(FULL)
            val installed = createInstalledEnrichmentDocument(incoming, identity.sha256,
                mapOf("original.flac" to identity), 1L, "b".repeat(64),
                incoming.getJSONObject("artwork"), "c".repeat(64), 100)
            mutation(installed.getJSONObject("media").getJSONArray("files"))
            installed.getJSONObject("install").put("generatedDigest", installedEnrichmentDigest(installed))
            val before = installed.toString()
            assertTrue(runCatching {
                validateInstalledEnrichmentDocument(installed, "FIXTURE0001", identity.sha256)
            }.isFailure)
            assertEquals(before, installed.toString())
        }
        for (path in listOf("original.wav", "Original.flac")) {
            assertFailure("SOURCE_BINDING_CONFLICT") {
                validateEnrichmentOriginalBinding(fixture(FULL).getJSONObject("source"),
                    path, identity, 12000L)
            }
        }
    }

    @Test
    fun nativeFlacSourceMarkerIsAcceptedWithoutLosingItsExactBinding() {
        val golden = fixture(FULL)
        val recording = JSONObject()
            .put("recordingKey", "qobuz:123")
            .put("trackId", 123)
            .put("file", "PC side/qobuz-comparison/downloads/123/audio.flac")
            .put("sha256", "a".repeat(64))
            .put("byteCount", 123456L)
            .put("durationMs", 12000L)
        golden.getJSONObject("source").put("recording", recording)

        validateEnrichmentContract(golden, hasCover = true)

        assertEquals(recording.toString(), golden.getJSONObject("source")
            .getJSONObject("recording").toString())
    }

    @Test
    fun recordingTrackIdBoundaryMatchesTheSharedProducerFixture() {
        val boundaries = listOf(
            File("../Test suite/android/fixtures/schemas/recording-track-id-boundaries.json"),
            File("../../Test suite/android/fixtures/schemas/recording-track-id-boundaries.json")
        ).first(File::isFile)
        val cases = JSONArray(boundaries.readText(Charsets.UTF_8))
        for (index in 0 until cases.length()) {
            val boundary = cases.getJSONObject(index)
            val incoming = fixture(FULL)
            incoming.getJSONObject("source").put("recording", nativeRecording()
                .put("trackId", boundary.getLong("trackId"))
                .put("recordingKey", boundary.getString("recordingKey")))
            val admitted = runCatching { validateEnrichmentContract(incoming, hasCover = true) }
            assertEquals(boundary.getBoolean("accepted"), admitted.isSuccess)
            if (admitted.isSuccess) {
                val identity = InstalledMediaIdentity("a".repeat(64), 123456L)
                val installed = createInstalledEnrichmentDocument(incoming, identity.sha256,
                    mapOf(SelectedLibraryContract.ORIGINAL_FLAC to identity), 1L, "f".repeat(64),
                    incoming.getJSONObject("artwork"), "b".repeat(64), 1)
                validateInstalledEnrichmentDocument(installed,
                    incoming.getJSONObject("source").getString("videoId"), identity.sha256)
            }
        }
    }

    @Test
    fun nativeRecordingMarkerRejectsCoercionsUnsafePathsAndUnknownFields() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("trackId", "123") }, { it.put("trackId", 123.0) },
            { it.put("recordingKey", "qobuz:124") }, { it.put("byteCount", 0) },
            { it.put("byteCount", 1_073_741_825L) }, { it.put("durationMs", "12000") },
            { it.put("durationMs", 7_200_001L) }, { it.put("sha256", "A".repeat(64)) },
            { it.put("file", "/audio.flac") }, { it.put("file", "../audio.flac") },
            { it.put("file", "PC side/./audio.flac") }, { it.put("file", "C:/audio.flac") },
            { it.put("file", "PC side\\audio.flac") }, { it.put("approved", true) }
        )
        mutations.forEach { mutation ->
            val golden = fixture(FULL)
            golden.getJSONObject("source").put("recording", nativeRecording().also(mutation))
            assertFailure("SOURCE_BINDING_CONFLICT") {
                validateEnrichmentContract(golden, hasCover = true)
            }
        }
    }

    @Test
    fun originalContainerMustMatchItsCanonicalNameBeforePublication() {
        validateEnrichmentOriginalContainer("original.flac", "audio/flac")
        validateEnrichmentOriginalContainer("original.opus", "audio/opus")
        listOf("original.flac" to "audio/opus", "original.opus" to "audio/flac",
            "original.flac" to "audio/wav", "original.opus" to "audio/ogg").forEach { (name, mime) ->
            assertFailure("ORIGINAL_CODEC_INVALID") { validateEnrichmentOriginalContainer(name, mime) }
        }
    }

    @Test
    fun nativeRecordingMarkerBoundsLyricAndAnalysisTimelines() {
        val golden = fixture(FULL)
        golden.getJSONObject("source").put("recording", nativeRecording().put("durationMs", 11998L))
        assertFailure("TIMELINE_INVALID") { validateEnrichmentContract(golden, hasCover = true) }
        golden.remove("analysis")
        golden.getJSONObject("source").getJSONObject("recording").put("durationMs", 1000L)
        assertFailure("LYRICS_INVALID") { validateEnrichmentContract(golden, hasCover = true) }
    }

    @Test
    fun nativeBindingRequiresActualFlacBytesHashAndTimelineAndSurvivesInstallation() {
        val incoming = fixture(FULL)
        val source = incoming.getJSONObject("source").put("recording", nativeRecording())
        val identity = InstalledMediaIdentity("a".repeat(64), 123456L)
        validateEnrichmentOriginalBinding(source, "original.flac", identity, 12000L)
        listOf<() -> Unit>(
            { validateEnrichmentOriginalBinding(source, "original.opus", identity, 12000L) },
            { validateEnrichmentOriginalBinding(source, "original.flac", identity.copy(sha256 = "b".repeat(64)), 12000L) },
            { validateEnrichmentOriginalBinding(source, "original.flac", identity.copy(byteLength = 123457L), 12000L) },
            { validateEnrichmentOriginalBinding(source, "original.flac", identity, 12002L) }
        ).forEach { assertFailure("SOURCE_BINDING_CONFLICT", it) }
        val installed = createInstalledEnrichmentDocument(incoming, identity.sha256,
            mapOf("original.flac" to identity), 1L, "b".repeat(64),
            incoming.getJSONObject("artwork"), "c".repeat(64), 100)
        validateInstalledEnrichmentDocument(installed, "FIXTURE0001", identity.sha256)
        assertEquals(mapOf("original.flac" to identity), installedMediaIdentities(installed))
        assertEquals(source.getJSONObject("recording").toString(),
            installed.getJSONObject("source").getJSONObject("recording").toString())
        installed.getJSONObject("source").getJSONObject("recording").put("sha256", "d".repeat(64))
        installed.getJSONObject("install").put("generatedDigest", installedEnrichmentDigest(installed))
        val failure = runCatching {
            validateInstalledEnrichmentDocument(installed, "FIXTURE0001", identity.sha256)
        }.exceptionOrNull()
        assertEquals("SOURCE_BINDING_CONFLICT", failure?.message)
    }

    private fun nativeRecording(): JSONObject = JSONObject()
        .put("recordingKey", "qobuz:123").put("trackId", 123)
        .put("file", "PC side/qobuz-comparison/downloads/123/audio.flac")
        .put("sha256", "a".repeat(64)).put("byteCount", 123456L).put("durationMs", 12000L)


    @Test
    fun shortSongsAdmitCurrentWaveformSpacingWithoutAdmittingOutOfRangePositions() {
        for (duration in listOf(1L, 100L, 249L)) {
            val payload = fixture(FULL).apply { remove("lyrics") }
            val analysis = payload.getJSONObject("analysis").put("status", "partial").put("durationMs", duration)
            listOf("rhythm", "key", "structure", "tuning", "loudness").forEach { feature ->
                analysis.put(feature, JSONObject().put("status", "unavailable").put("reason", "Short fixture"))
            }
            analysis.remove("diagnostics")
            val levels = JSONArray()
            for (spacing in listOf(5L, 50L, 250L)) {
                val frames = (duration + spacing - 1L) / spacing
                levels.put(JSONObject().put("frameDurationMs", spacing).put("frameCount", frames)
                    .put("data", Base64.getEncoder().encodeToString(ByteArray(frames.toInt() * 6))))
            }
            analysis.put("waveform", JSONObject(fixture(FULL).getJSONObject("analysis")
                .getJSONObject("waveform").toString()).put("levels", levels))
            validateEnrichmentContract(payload, hasCover = true)
            val badBeat = JSONObject(payload.toString())
            badBeat.getJSONObject("analysis").put("rhythm", fixture(FULL).getJSONObject("analysis").getJSONObject("rhythm"))
            assertFailure("TIMELINE_INVALID") { validateEnrichmentContract(badBeat, hasCover = true) }
            val badLyric = JSONObject(payload.toString()).put("lyrics", fixture(FULL).getJSONObject("lyrics"))
            assertFailure("LYRICS_INVALID") { validateEnrichmentContract(badLyric, hasCover = true) }
        }
    }

    @Test
    fun waveformFrameByteCountCannotWrapIntArithmetic() {
        assertFalse(importedWaveformByteCountMatches(2, 715_827_883))
        assertTrue(importedWaveformByteCountMatches(6, 1))
        assertTrue(
            decodeImportedWaveformLevel(
                JSONObject()
                    .put("frameCount", 715_827_883)
                    .put("data", java.util.Base64.getEncoder().encodeToString(byteArrayOf(0, 0)))
            ).isEmpty()
        )
    }

    @Test
    fun waveformBase64MustEncodeExactlySixBytesPerFrame() {
        val golden = fixture(FULL)
        val level = golden.getJSONObject("analysis").getJSONObject("waveform")
            .getJSONArray("levels").getJSONObject(0)
        val data = level.getString("data")
        validateEnrichmentContract(golden, hasCover = true)

        listOf(
            data.dropLast(1),
            data + "AAAA",
            "-" + data.drop(1),
            "_" + data.drop(1),
            "\n" + data.drop(1),
            data.dropLast(1) + "=",
            data.dropLast(2) + "=="
        ).forEach { invalid ->
            level.put("data", invalid)
            assertFailure("WAVEFORM_INVALID") {
                validateEnrichmentContract(golden, hasCover = true)
            }
        }
    }

    @Test
    fun fullPcGoldenFilePassesTheExactProductionValidator() {
        val golden = fixture(FULL)
        validateEnrichmentContract(golden, hasCover = true)

        assertEquals("FIXTURE0001", golden.getJSONObject("source").getString("videoId"))
        assertEquals(
            setOf("metadata", "artwork", "lyrics", "analysis"),
            enrichmentGeneratedSections(golden).keys
        )
        assertEquals("complete", golden.getJSONObject("lyrics").getString("status"))
        assertEquals("complete", golden.getJSONObject("analysis").getString("status"))
    }

    @Test
    fun rootFormatVersionIsNumericIntegerTwoInValidation() {
        val accepted = fixture(FULL)
        validateEnrichmentContract(accepted, hasCover = true)

        listOf("2", 2.0, 1, JSONObject.NULL).forEach { invalidVersion ->
            val invalid = fixture(FULL).put("formatVersion", invalidVersion)
            assertFailure("SCHEMA_UNSUPPORTED") {
                validateEnrichmentContract(invalid, hasCover = true)
            }
        }
    }

    @Test
    fun goldenContractAcceptsARealElevenCharacterSongIdentity() {
        val golden = fixture(FULL)
        golden.getJSONObject("source").put("videoId", "UXAUDIT0001")

        validateEnrichmentContract(golden, hasCover = true)

        assertEquals("UXAUDIT0001", golden.getJSONObject("source").getString("videoId"))
    }

    @Test
    fun goldenArtworkSidecarIsARealStaticPngMatchingTheDescriptor() {
        val golden = fixture(FULL)
        val artwork = golden.getJSONObject("artwork")
        val png = Base64.getDecoder().decode(resourceText("cover.png.base64").trim())

        assertEquals("cover.png", artwork.getString("file"))
        assertEquals("album-cover", artwork.getString("kind"))
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            png.copyOfRange(0, 8)
        )
        assertEquals(256, readBigEndianInt(png, 16))
        assertEquals(256, readBigEndianInt(png, 20))
        assertTrue(validatePngChunks(png))
    }

    @Test
    fun stereoAntiPhaseGoldenKeepsItsFullScaleEnergy() {
        val waveform = fixture(FULL).getJSONObject("analysis").getJSONObject("waveform")
        val first = waveform.getJSONArray("levels").objects().first { it.getInt("frameDurationMs") == 5 }
        val frame = java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(first.getString("data")))
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(-32768, frame.short.toInt())
        assertEquals(32767, frame.short.toInt())
        assertEquals(32767, frame.short.toInt() and 0xffff)
    }

    @Test
    fun completeWaveformFrameCountsCoverTheDeclaredTwelveSecondTimeline() {
        val analysis = fixture(FULL).getJSONObject("analysis")
        val durationMs = analysis.getLong("durationMs")
        val levels = analysis.getJSONObject("waveform").getJSONArray("levels").objects()

        levels.forEach { level ->
            val frameDurationMs = level.getLong("frameDurationMs")
            val expectedFrames = (durationMs + frameDurationMs - 1L) / frameDurationMs
            assertEquals(expectedFrames, level.getLong("frameCount"))
            assertEquals(
                expectedFrames * 6L,
                Base64.getDecoder().decode(level.getString("data")).size.toLong()
            )
        }
        assertEquals(setOf(5L, 50L, 250L), levels.map { it.getLong("frameDurationMs") }.toSet())
    }

    @Test
    fun waveformCoverageUsesCeilingForANonMultipleDuration() {
        val golden = fixture(FULL)
        val analysis = golden.getJSONObject("analysis")
        analysis.put("durationMs", 12_001L)
        analysis.getJSONObject("waveform").getJSONArray("levels").objects().forEach { level ->
            val frameDurationMs = level.getLong("frameDurationMs")
            val expectedFrames = (12_001L + frameDurationMs - 1L) / frameDurationMs
            val original = Base64.getDecoder().decode(level.getString("data"))
            val resized = original.copyOf((expectedFrames * 6L).toInt())
            original.copyOfRange(original.size - 6, original.size).copyInto(
                destination = resized,
                destinationOffset = resized.size - 6
            )
            level.put("frameCount", expectedFrames)
            level.put("data", Base64.getEncoder().encodeToString(resized))
        }

        validateEnrichmentContract(golden, hasCover = true)
    }

    @Test
    fun waveformObjectAcceptsPublishedKeysAndRejectsUnknownKeys() {
        val accepted = fixture(FULL)
        validateEnrichmentContract(accepted, hasCover = true)

        val unknown = fixture(FULL)
        unknown.getJSONObject("analysis").getJSONObject("waveform")
            .put("previewColor", "#ffffff")
        assertFailure("JSON_FIELD_UNKNOWN") {
            validateEnrichmentContract(unknown, hasCover = true)
        }
    }

    @Test
    fun waveformCoverageRejectsByteConsistentShortAndLongLevels() {
        listOf(-1, 1).forEach { frameDelta ->
            val golden = fixture(FULL)
            val level = golden.getJSONObject("analysis").getJSONObject("waveform")
                .getJSONArray("levels").getJSONObject(0)
            val originalFrames = level.getInt("frameCount")
            val changedFrames = originalFrames + frameDelta
            val original = Base64.getDecoder().decode(level.getString("data"))
            val resized = original.copyOf(changedFrames * 6)
            if (frameDelta > 0) {
                original.copyOfRange(original.size - 6, original.size).copyInto(
                    destination = resized,
                    destinationOffset = resized.size - 6
                )
            }
            level.put("frameCount", changedFrames)
            level.put("data", Base64.getEncoder().encodeToString(resized))

            assertFailure("WAVEFORM_INVALID") {
                validateEnrichmentContract(golden, hasCover = true)
            }
        }
    }

    @Test
    fun actualAndroidMetadataLyricsAndAnalysisProjectionsReadTheGoldenFields() {
        val golden = fixture(FULL)
        val metadata = golden.getJSONObject("metadata")
        val metadataProjection = projectEnrichmentMetadata(metadata)
        assertEquals("Golden Hour Circuit", metadataProjection.title)
        assertEquals(
            "StemWise Fixture Artist · Contract Ensemble",
            metadataProjection.artist
        )
        assertEquals("Executable Examples", metadataProjection.album)
        assertEquals("2026", metadataProjection.year)
        assertEquals("Electronic, Test fixture", metadataProjection.additionalInfo["Genres"])
        assertTrue(metadataProjection.additionalInfo.getValue("Credits").contains("composer"))
        assertEquals("StemWise Local Lab", metadataProjection.additionalInfo["Label"])

        val lyricJson = golden.getJSONObject("lyrics").getJSONArray("tracks").objects()
        val tracks = projectEnrichmentLyricTracks(lyricJson)
        assertEquals(3, tracks.size)
        val original = tracks.single { it.id == "original-mul" }
        assertEquals("synced", original.format)
        assertEquals("mul", original.language)
        assertEquals(
            listOf("en", "tr"),
            original.componentLanguages
        )
        assertEquals("Zyyy", original.script)
        assertEquals(3, original.lines.size)
        assertEquals(500L, original.lines.first().startMs)
        assertEquals(10_500L, original.lines.last().endMs)
        assertTrue(original.lines.all { it.words.isNotEmpty() })
        assertEquals(3, original.lines.first().words.size)
        assertEquals(6, original.lines.first().words[1].startUtf16)
        val translation = tracks.single { it.kind == "translation" }
        assertEquals("original-mul", translation.basedOnTrackId)
        assertEquals("en", translation.language)
        assertEquals(3, translation.lines.size)
        val ipa = tracks.single { it.id == "transliteration-tr-fonipa" }
        assertEquals("synced", ipa.format)
        assertEquals("tr-fonipa", ipa.language)
        assertEquals(3, ipa.lines.size)
        assertEquals(original.lines.map { it.startMs }, ipa.lines.map { it.startMs })
        assertEquals(original.lines.map { it.endMs }, ipa.lines.map { it.endMs })
        assertEquals(original.lines.map { it.startMs }, translation.lines.map { it.startMs })
        assertEquals(original.lines.map { it.endMs }, translation.lines.map { it.endMs })
        assertTrue(ipa.lines.first().text.contains("ɑɫtɯn"))

        val analysis = golden.getJSONObject("analysis")
        val summary = projectEnrichmentAnalysis(analysis)
        assertEquals(128.0, summary.bpm!!, 0.0)
        assertEquals("4/4", summary.meter)
        assertEquals("C# Minor", summary.musicalKey)
        assertEquals(0.87, summary.keyConfidence!!, 0.0)
        assertEquals(0.79, summary.tuningCents!!, 0.0)
        assertEquals(-10.7, summary.integratedLufs!!, 0.0)
        assertEquals(6.2, summary.loudnessRangeLu!!, 0.0)
        assertEquals(-0.8, summary.truePeakDbtp!!, 0.0)

        val metronome = projectImportedMetronomeTimeline(analysis.getJSONObject("rhythm"))
        assertEquals(25, metronome.beats.size)
        assertEquals(
            listOf(500L, 2_375L, 4_250L, 6_127L, 8_032L, 9_460L, 10_889L),
            metronome.beats.filter { it.isDownbeat }.map { it.timeMs }
        )
        assertEquals(11_841L, metronome.beats.last().timeMs)
        assertTrue(metronome.beats.last().timeMs > 11_800L)
        assertEquals(listOf(4, 3), metronome.meterSegments.map { it.numerator })
        assertEquals(listOf(0L, 9_000L), metronome.meterSegments.map { it.startMs })
        assertEquals(listOf(9_000L, 12_000L), metronome.meterSegments.map { it.endMs })
        val sections = projectImportedSongSections(analysis)
        assertEquals(listOf("intro", "verse", "chorus"), sections.map { it.label })
        assertEquals(12000L, sections.last().endMs)
        val fine = analysis.getJSONObject("waveform").getJSONArray("levels").objects()
            .single { it.getLong("frameDurationMs") == 5L }
        val peaks = decodeImportedWaveformLevel(fine)
        assertEquals(2400, peaks.size)
        assertTrue(peaks.all { it in 0f..1f })
        assertTrue("Golden waveform must be visibly non-uniform", peaks.max() - peaks.min() > .15f)
        assertTrue(
            "Golden waveform must exercise interpolation across many amplitudes",
            peaks.map { (it * 1_000f).toInt() }.distinct().size > 32
        )
    }

    @Test
    fun metronomeProjectionPreservesEveryMeterAcceptedByThePcContract() {
        val rhythm = JSONObject().put(
            "meterSegments",
            JSONArray()
                .put(
                    JSONObject()
                        .put("startMs", 0)
                        .put("endMs", 750)
                        .put("numerator", 13)
                        .put("denominator", 8)
                )
                .put(
                    JSONObject()
                        .put("startMs", 1000)
                        .put("endMs", 1750)
                        .put("numerator", 3)
                        .put("denominator", 32)
                )
        )

        val segments = projectImportedMetronomeTimeline(rhythm).meterSegments

        assertEquals(listOf(0L, 1000L), segments.map { it.startMs })
        assertEquals(listOf(750L, 1750L), segments.map { it.endMs })
        assertEquals(listOf(13, 3), segments.map { it.numerator })
        assertEquals(listOf(8, 32), segments.map { it.denominator })
    }

    @Test
    fun projectionDiscardsLocalOnlySchemeAndLabelAndFrozenTransportRejectsThem() {
        val golden = fixture(FULL)
        val tracks = golden.getJSONObject("lyrics").getJSONArray("tracks")
        val localOnly = JSONObject(tracks.getJSONObject(2).toString())
            .put("scheme", "IPA")
            .put("label", "Turkish IPA")
        val projected = projectEnrichmentLyricTracks(listOf(localOnly)).single()
        assertEquals(
            listOf(projected),
            projectEnrichmentLyricTracks(listOf(localOnly))
        )

        tracks.put(2, localOnly)
        val failure = runCatching {
            validateEnrichmentContract(golden, hasCover = true)
        }.exceptionOrNull() as EnrichmentImportException
        assertEquals("LYRICS_INVALID", failure.code)
    }

    @Test
    fun contractIdentifiersAndArrayLinksProjectToVisibleInfoRows() {
        val metadata = fixture(FULL).getJSONObject("metadata")
        val projection = projectEnrichmentMetadata(metadata)

        assertEquals("USSWI2600001", metadata.getJSONObject("identifiers").getString("isrc"))
        assertEquals(2, metadata.getJSONArray("links").length())
        assertTrue(projection.additionalInfo.getValue("Identifiers").contains("USSWI2600001"))
        assertTrue(projection.additionalInfo.getValue("Identifiers").contains("ISRC:"))
        assertTrue(
            projection.additionalInfo.getValue("Identifiers").contains("MusicBrainz recording:")
        )
        assertFalse(projection.additionalInfo.getValue("Identifiers").contains("MUSICBRAINZRECORDINGID"))
        assertTrue(projection.additionalInfo.getValue("Links").contains("YouTube:"))
        assertTrue(projection.additionalInfo.getValue("Links").contains("youtube.com"))
        assertTrue(projection.additionalInfo.getValue("Links").contains("stemwise.local"))
    }

    @Test
    fun currentManifestProjectionRetainsEveryOrderedTimedTrack() {
        val golden = fixture(FULL)
        val projectedTracks = projectEnrichmentLyricTracks(
            golden.getJSONObject("lyrics").getJSONArray("tracks").objects()
        )
        validateEnrichmentContract(golden, hasCover = true)
        val restoredTracks = projectEnrichmentLyricTracks(JSONArray(
            golden.getJSONObject("lyrics").getJSONArray("tracks").toString()).objects())
        assertEquals(projectedTracks, restoredTracks)
        assertEquals(
            projectedTracks.flatMap { track -> track.lines.map { it.startMs to it.endMs } },
            restoredTracks.flatMap { track -> track.lines.map { it.startMs to it.endMs } }
        )
        assertEquals(
            projectedTracks.map {
                listOf(it.id, it.kind, it.format, it.basedOnTrackId, it.componentLanguages, it.script)
            },
            restoredTracks.map {
                listOf(it.id, it.kind, it.format, it.basedOnTrackId, it.componentLanguages, it.script)
            }
        )

        val metadataProjection = projectEnrichmentMetadata(golden.getJSONObject("metadata"))
        assertEquals(
            metadataProjection.additionalInfo,
            parseRoomAdditionalInfo(metadataProjection.roomAdditionalInfoJson)
        )
    }

    @Test
    fun timedPlainNoIpaNoTranslationAndNoLyricsVariantsAllValidate() {
        val full = fixture(FULL)
        val plain = fixture(PLAIN_LYRICS)
        val unavailable = fixture(NO_LYRICS)

        validateEnrichmentContract(full, hasCover = true)
        validateEnrichmentContract(plain, hasCover = false)
        validateEnrichmentContract(unavailable, hasCover = false)

        val plainTracks = projectEnrichmentLyricTracks(
            plain.getJSONObject("lyrics").getJSONArray("tracks").objects()
        )
        assertEquals(1, plainTracks.size)
        assertEquals("plain", plainTracks.single().format)
        assertTrue(plainTracks.none { it.kind == "translation" || it.language.orEmpty().contains("fonipa") })
        assertTrue(
            projectEnrichmentLyricTracks(
                unavailable.getJSONObject("lyrics").getJSONArray("tracks").objects()
            ).isEmpty()
        )
    }

    @Test
    fun partialAndUnavailableAnalysisVariantsPassTheSameProductionValidator() {
        val partial = analysisOnlyFixture("2026-07-29T08:20:00Z")
        partial.getJSONObject("analysis").apply {
            put("status", "partial")
            put("key", JSONObject().put("status", "unavailable").put("reason", "No reliable key."))
            val fiftyMs = getJSONObject("waveform").getJSONArray("levels").objects()
                .single { it.getLong("frameDurationMs") == 50L }
            put(
                "waveform",
                JSONObject()
                    .put("status", "partial")
                    .put("reason", "Only the normal-detail envelope is retained in this variant.")
                    .put("encoding", "s16le-min-max-rms-base64-v1")
                    .put("levels", JSONArray().put(fiftyMs))
            )
            getJSONArray("toolRuns").objects().single { it.getString("name") == "s-key" }.apply {
                put("status", "failed")
                put("reason", "The fixture deliberately omits a usable key.")
            }
        }
        validateEnrichmentContract(partial, hasCover = false)

        val unavailableFeature = {
            JSONObject().put("status", "unavailable").put("reason", "Analysis audio unavailable.")
        }
        val unavailable = baseUpdate("2026-07-29T08:25:00Z").put(
            "analysis",
            JSONObject()
                .put("status", "unavailable")
                .put("rhythm", unavailableFeature())
                .put("key", unavailableFeature())
                .put("structure", unavailableFeature())
                .put("tuning", unavailableFeature())
                .put("loudness", unavailableFeature())
                .put("waveform", unavailableFeature())
                .put(
                    "toolRuns",
                    JSONArray(EXPECTED_TOOLS.map { name ->
                        JSONObject()
                            .put("name", name)
                            .put("status", "skipped")
                            .put("reason", "Analysis audio unavailable.")
                    })
                )
        )
        validateEnrichmentContract(unavailable, hasCover = false)
    }

    @Test
    fun sameSongCompleteFixturesSelectOnlyTheNewestRevision() {
        val documents = listOf(
            fixture(FULL), fixture(PLAIN_LYRICS), fixture(NO_LYRICS), fixture(METADATA_ONLY)
        )
        assertEquals(setOf("FIXTURE0001"), documents.map(::videoId).toSet())
        assertEquals(
            documents.map { Instant.parse(it.getString("generatedAt")) }.sorted(),
            documents.map { Instant.parse(it.getString("generatedAt")) }
        )
        documents.zipWithNext().forEach { (installed, incoming) ->
            assertEquals(
                EnrichmentRevisionDecision.APPLY,
                decideEnrichmentRevision(
                    installedState = "enriched",
                    installedUpdatedAt = installed.getString("generatedAt"),
                    installedInputSha256 = CanonicalJson.sha256(installed),
                    incomingGeneratedAt = incoming.getString("generatedAt"),
                    incomingInputSha256 = CanonicalJson.sha256(incoming)
                )
            )
        }

        val shuffled = documents.mapIndexed { index, json -> revision(index, json) }.reversed()
        val plan = planEnrichmentBatchRevisions(shuffled)
        assertEquals(EnrichmentBatchRevisionDisposition.APPLY, plan[3])
        (0..2).forEach { index ->
            assertEquals(EnrichmentBatchRevisionDisposition.SUPERSEDED, plan[index])
        }
        assertEquals(
            listOf(3),
            enrichmentBatchApplicationOrder(shuffled, plan)
        )
    }

    @Test
    fun sameTimestampConflictAndExactDuplicateRulesUseGoldenDigests() {
        val golden = fixture(FULL)
        val digest = CanonicalJson.sha256(golden)
        assertEquals(EXPECTED_GOLDEN_SHA256, digest)
        assertEquals(
            EnrichmentRevisionDecision.UNCHANGED,
            decideEnrichmentRevision(
                "enriched", golden.getString("generatedAt"), digest,
                "2026-07-29T09:00:00Z", digest
            )
        )
        assertEquals(
            EnrichmentRevisionDecision.CONFLICT,
            decideEnrichmentRevision(
                "enriched", golden.getString("generatedAt"), digest,
                golden.getString("generatedAt"), "different"
            )
        )

        val reordered = JSONObject().also { output ->
            golden.keys().asSequence().toList().reversed().forEach { key -> output.put(key, golden.get(key)) }
        }
        assertArrayEquals(CanonicalJson.encode(golden), CanonicalJson.encode(reordered))
    }

    @Test
    fun schemaRootAndGoldenFixtureStayCompatible() {
        val schema = JSONObject(schemaFile().readText())
        val golden = fixture(FULL)
        val schemaFields = schema.getJSONObject("properties").keys().asSequence().toSet()
        assertTrue(golden.keys().asSequence().all { it in schemaFields })
        assertFalse(schema.optBoolean("additionalProperties", true))
        assertEquals(
            setOf("metadata", "artwork", "lyrics", "analysis"),
            schema.getJSONArray("anyOf").objects()
                .map { it.getJSONArray("required").getString(0) }
                .toSet()
        )
        val definitions = schema.getJSONObject("\$defs")
        listOf(
            "metadata", "album", "artwork", "lyrics", "lyricTrack", "lyricLine",
            "analysis", "rhythm", "beat", "key", "structure", "section", "tuning",
            "loudness", "waveform", "waveformLevel", "toolRun"
        ).forEach { definition ->
            assertFalse(
                "$definition must reject unknown PC-manager fields",
                definitions.getJSONObject(definition).optBoolean("additionalProperties", true)
            )
        }
        assertEquals(
            setOf("id", "kind", "language", "componentLanguages", "script", "basedOnTrackId", "format", "text", "lines", "source"),
            definitions.getJSONObject("lyricTrack").getJSONObject("properties")
                .keys().asSequence().toSet()
        )
    }

    @Test
    fun shapeRangeTimelineAndArtworkDriftFailClearly() {
        val unknownMetadata = fixture(FULL)
        unknownMetadata.getJSONObject("metadata").put("mood", "unsupported")
        assertFailure("JSON_FIELD_UNKNOWN") {
            validateEnrichmentContract(unknownMetadata, hasCover = true)
        }

        val wrongMetadataType = fixture(FULL)
        wrongMetadataType.getJSONObject("metadata").put("title", 123)
        assertFailure("MANIFEST_JSON_INVALID") {
            validateEnrichmentContract(wrongMetadataType, hasCover = true)
        }

        val badBpm = fixture(FULL)
        badBpm.getJSONObject("analysis").getJSONObject("rhythm").put("bpm", 0)
        assertFailure("ANALYSIS_STATUS_INVALID") {
            validateEnrichmentContract(badBpm, hasCover = true)
        }

        val badWaveform = fixture(FULL)
        badWaveform.getJSONObject("analysis").getJSONObject("waveform")
            .getJSONArray("levels").getJSONObject(0).put("frameCount", 2399)
        assertFailure("WAVEFORM_INVALID") {
            validateEnrichmentContract(badWaveform, hasCover = true)
        }

        val nonObjectToolRun = fixture(FULL)
        nonObjectToolRun.getJSONObject("analysis").getJSONArray("toolRuns")
            .put("not-a-tool-run")
        assertFailure("TOOL_RUN_INVALID") {
            validateEnrichmentContract(nonObjectToolRun, hasCover = true)
        }

        val nonObjectTimedSection = fixture(FULL)
        nonObjectTimedSection.getJSONObject("analysis").getJSONObject("structure")
            .getJSONArray("sections").put("not-a-section")
        assertFailure("TIMELINE_INVALID") {
            validateEnrichmentContract(nonObjectTimedSection, hasCover = true)
        }

        val outOfRangeLyrics = fixture(FULL)
        outOfRangeLyrics.getJSONObject("lyrics").getJSONArray("tracks").getJSONObject(0)
            .getJSONArray("lines").getJSONObject(0).put("startMs", 12001)
        assertFailure("LYRICS_INVALID") {
            validateEnrichmentContract(outOfRangeLyrics, hasCover = true)
        }

        val nonObjectTrack = fixture(FULL)
        nonObjectTrack.getJSONObject("lyrics").getJSONArray("tracks").put("not-a-track")
        assertFailure("LYRICS_INVALID") {
            validateEnrichmentContract(nonObjectTrack, hasCover = true)
        }

        val nonObjectLine = fixture(FULL)
        nonObjectLine.getJSONObject("lyrics").getJSONArray("tracks").getJSONObject(0)
            .getJSONArray("lines").put("not-a-line")
        assertFailure("LYRICS_INVALID") {
            validateEnrichmentContract(nonObjectLine, hasCover = true)
        }

        assertFailure("PNG_MISSING") {
            validateEnrichmentContract(fixture(FULL), hasCover = false)
        }
    }

    @Test
    fun malformedOptionalAnalysisTypesNeverImportAsMissingData() {
        data class Mutation(
            val label: String,
            val code: String,
            val apply: (JSONObject) -> Unit
        )

        val mutations = listOf(
            Mutation("duration string", "TIMELINE_INVALID") { analysis ->
                analysis.put("durationMs", "12000")
            },
            Mutation("diagnostics string", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.put("diagnostics", "not-an-object")
            },
            Mutation("rhythm bpm string", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("rhythm").put("bpm", "128")
            },
            Mutation("alternative BPMs object", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("rhythm").put("alternativeBpms", JSONObject())
            },
            Mutation("meter numerator double", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("rhythm").getJSONObject("meter").put("numerator", 4.0)
            },
            Mutation("beats string", "TIMELINE_INVALID") { analysis ->
                analysis.getJSONObject("rhythm").put("beats", "not-an-array")
            },
            Mutation("beat time string", "TIMELINE_INVALID") { analysis ->
                analysis.getJSONObject("rhythm").getJSONArray("beats")
                    .getJSONObject(0).put("timeMs", "500")
            },
            Mutation("tempo segments string", "TIMELINE_INVALID") { analysis ->
                analysis.getJSONObject("rhythm").put("tempoSegments", "not-an-array")
            },
            Mutation("key tonic number", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("key").put("tonic", 1)
            },
            Mutation("key alternatives object", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("key").put("alternatives", JSONObject())
            },
            Mutation("sections string", "TIMELINE_INVALID") { analysis ->
                analysis.getJSONObject("structure").put("sections", "not-an-array")
            },
            Mutation("section start double", "TIMELINE_INVALID") { analysis ->
                analysis.getJSONObject("structure").getJSONArray("sections")
                    .getJSONObject(0).put("startMs", 0.0)
            },
            Mutation("tuning frequency string", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("tuning").put("frequencyHz", "440")
            },
            Mutation("tuning cents boolean", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("tuning").put("centsFromA440", false)
            },
            Mutation("loudness scalar string", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("loudness").put("integratedLufs", "-10.7")
            },
            Mutation("descriptors object", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("loudness").put("descriptors", JSONObject())
            },
            Mutation("descriptor value string", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("loudness").getJSONArray("descriptors")
                    .getJSONObject(0).put("value", "0.5")
            },
            Mutation("waveform encoding number", "WAVEFORM_INVALID") { analysis ->
                analysis.getJSONObject("waveform").put("encoding", 1)
            },
            Mutation("waveform levels object", "WAVEFORM_INVALID") { analysis ->
                analysis.getJSONObject("waveform").put("levels", JSONObject())
            },
            Mutation("waveform frame count double", "WAVEFORM_INVALID") { analysis ->
                analysis.getJSONObject("waveform").getJSONArray("levels")
                    .getJSONObject(0).put("frameCount", 2400.0)
            },
            Mutation("diagnostic bpm string", "ANALYSIS_STATUS_INVALID") { analysis ->
                analysis.getJSONObject("diagnostics").getJSONObject("allInOneRhythm")
                    .put("bpm", "128")
            },
            Mutation("diagnostic beats object", "TIMELINE_INVALID") { analysis ->
                analysis.getJSONObject("diagnostics").getJSONObject("allInOneRhythm")
                    .put("beatsMs", JSONObject())
            },
            Mutation("tool warnings object", "TOOL_RUN_INVALID") { analysis ->
                analysis.getJSONArray("toolRuns").getJSONObject(0)
                    .put("warnings", JSONObject())
            },
            Mutation("tool version number", "TOOL_RUN_INVALID") { analysis ->
                analysis.getJSONArray("toolRuns").getJSONObject(0).put("version", 1)
            }
        )

        mutations.forEach { mutation ->
            val document = fixture(FULL)
            mutation.apply(document.getJSONObject("analysis"))
            val failure = runCatching {
                validateEnrichmentContract(document, hasCover = true)
            }.exceptionOrNull()
            assertTrue(mutation.label, failure is EnrichmentImportException)
            assertEquals(
                mutation.label,
                mutation.code,
                (failure as EnrichmentImportException).code
            )
        }
    }

    @Test
    fun syncedLyricsRejectCoercedOrUnreachableStartTimes() {
        val stringStart = fixture(FULL)
        stringStart.getJSONObject("lyrics").getJSONArray("tracks").getJSONObject(0)
            .getJSONArray("lines").getJSONObject(0).put("startMs", "500")
        assertFailure("LYRICS_INVALID") {
            validateEnrichmentContract(stringStart, hasCover = true)
        }

        val tiedStart = fixture(FULL)
        tiedStart.getJSONObject("lyrics").getJSONArray("tracks").let { tracks ->
            for (trackIndex in 0 until tracks.length()) {
                tracks.getJSONObject(trackIndex).getJSONArray("lines")
                    .getJSONObject(1).put("startMs", 500)
            }
        }
        validateEnrichmentContract(tiedStart, hasCover = true)

        val durationBoundary = fixture(FULL)
        durationBoundary.getJSONObject("lyrics").getJSONArray("tracks").getJSONObject(0)
            .getJSONArray("lines").getJSONObject(2).apply {
                put("startMs", 12000)
                put("endMs", 12001)
            }
        assertFailure("LYRICS_INVALID") {
            validateEnrichmentContract(durationBoundary, hasCover = true)
        }
    }

    @Test
    fun analysisStatusClaimsCannotOutrunTheirPayload() {
        data class Mutation(
            val label: String,
            val code: String = "ANALYSIS_STATUS_INVALID",
            val apply: (JSONObject) -> Unit
        )

        val mutations = listOf(
            Mutation("complete rhythm without bpm") { analysis ->
                analysis.getJSONObject("rhythm").remove("bpm")
            },
            Mutation("complete key without tonic and mode") { analysis ->
                analysis.getJSONObject("key").apply {
                    remove("tonic")
                    remove("mode")
                }
            },
            Mutation("complete structure without sections") { analysis ->
                analysis.getJSONObject("structure").remove("sections")
            },
            Mutation("complete tuning without frequency") { analysis ->
                analysis.getJSONObject("tuning").remove("frequencyHz")
            },
            Mutation("complete loudness without integrated LUFS") { analysis ->
                analysis.getJSONObject("loudness").remove("integratedLufs")
            },
            Mutation("unavailable rhythm carrying complete data") { analysis ->
                analysis.getJSONObject("rhythm")
                    .put("status", "unavailable")
                    .put("reason", "No rhythm result.")
            },
            Mutation("partial loudness without usable data") { analysis ->
                analysis.getJSONObject("loudness").apply {
                    put("status", "partial")
                    put("reason", "No stable value completed.")
                    remove("integratedLufs")
                    remove("loudnessRangeLu")
                    remove("truePeakDbtp")
                    remove("descriptors")
                }
            },
            Mutation("complete diagnostic without beats") { analysis ->
                analysis.getJSONObject("diagnostics").getJSONObject("allInOneRhythm")
                    .remove("beatsMs")
            },
            Mutation("complete overall analysis with uncertain key") { analysis ->
                analysis.getJSONObject("key")
                    .put("status", "uncertain")
                    .put("reason", "Two close key candidates.")
            },
            Mutation("complete overall analysis with failed tool") { analysis ->
                analysis.getJSONArray("toolRuns").getJSONObject(0)
                    .put("status", "failed")
                    .put("reason", "Analyzer failed.")
            },
            Mutation("partial overall analysis without duration", "TIMELINE_INVALID") { analysis ->
                analysis.put("status", "partial").remove("durationMs")
                analysis.getJSONObject("key")
                    .put("status", "uncertain")
                    .put("reason", "Two close key candidates.")
            }
        )

        mutations.forEach { mutation ->
            val document = fixture(FULL)
            mutation.apply(document.getJSONObject("analysis"))
            val failure = runCatching {
                validateEnrichmentContract(document, hasCover = true)
            }.exceptionOrNull()
            assertTrue(mutation.label, failure is EnrichmentImportException)
            assertEquals(
                mutation.label,
                mutation.code,
                (failure as EnrichmentImportException).code
            )
        }
    }

    @Test
    fun schemaStatusConditionalsMatchTheExecutableGoldenContract() {
        val definitions = JSONObject(schemaFile().readText()).getJSONObject("\$defs")
        val completeRequirements = mapOf(
            "rhythm" to setOf("bpm", "beats"),
            "key" to setOf("tonic", "mode"),
            "structure" to setOf("sections"),
            "tuning" to setOf("frequencyHz", "centsFromA440"),
            "loudness" to setOf("integratedLufs", "loudnessRangeLu")
        )
        listOf("rhythm", "key", "structure", "tuning", "loudness").forEach { name ->
            val conditional = definitions.getJSONObject(name).getJSONArray("allOf")
                .getJSONObject(0)
            assertEquals(
                "complete",
                conditional.getJSONObject("if").getJSONObject("properties")
                    .getJSONObject("status").getString("const")
            )
            assertEquals("reason", conditional.getJSONObject("else")
                .getJSONArray("required").getString(0))
            val required = conditional.getJSONObject("then").getJSONArray("required")
                .let { values -> (0 until values.length()).map(values::getString).toSet() }
            assertEquals(completeRequirements.getValue(name), required)
        }
        assertEquals(3, definitions.getJSONObject("analysis").getJSONArray("allOf").length())
        assertEquals(3, definitions.getJSONObject("waveform").getJSONArray("allOf").length())
        assertEquals(3, definitions.getJSONObject("toolRun").getJSONArray("allOf").length())
        assertEquals(
            "reason",
            definitions.getJSONObject("diagnostics").getJSONObject("properties")
                .getJSONObject("allInOneRhythm").getJSONArray("allOf")
                .getJSONObject(0).getJSONObject("else").getJSONArray("required").getString(0)
        )
    }

    private fun analysisOnlyFixture(generatedAt: String): JSONObject {
        val full = fixture(FULL)
        return baseUpdate(generatedAt).put("analysis", JSONObject(full.getJSONObject("analysis").toString()))
    }

    private fun baseUpdate(generatedAt: String): JSONObject = JSONObject()
        .put("formatVersion", 2)
        .put("kind", "stemwise.song-enrichment")
        .put("source", JSONObject().put("provider", "youtube").put("videoId", "FIXTURE0001"))
        .put("generatedAt", generatedAt)
        .put(
            "generator",
            JSONObject()
                .put("name", ENRICHMENT_GENERATOR_NAME)
                .put("version", ENRICHMENT_GENERATOR_VERSION)
        )

    private fun revision(index: Int, json: JSONObject) = EnrichmentBatchRevision(
        index = index,
        sourceName = "$index.json",
        videoId = videoId(json),
        generatedAt = json.getString("generatedAt"),
        inputSha256 = CanonicalJson.sha256(json)
    )

    private fun videoId(json: JSONObject): String =
        json.getJSONObject("source").getString("videoId")

    private fun assertFailure(code: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue(failure is EnrichmentImportException)
        assertEquals(code, (failure as EnrichmentImportException).code)
    }

    private fun fixture(name: String): JSONObject = JSONObject(resourceText(name))

    /**
     * JSON fixtures live in debug assets so the host contract tests and the
     * device production-import test consume one byte-identical source of truth.
     * The PNG base64 remains a host-only integrity fixture; device QA creates a
     * valid static PNG and sends it through the real ZIP importer.
     */
    private fun resourceText(name: String): String {
        val sourceFile = listOf(
            File("src/development/assets/qa/enrichment/$name"),
            File("app/src/development/assets/qa/enrichment/$name"),
            File("../Test suite/android/unit/resources/fixtures/enrichment/$name"),
            File("../../Test suite/android/unit/resources/fixtures/enrichment/$name")
        ).firstOrNull(File::isFile)
        if (sourceFile != null) return sourceFile.readText(Charsets.UTF_8)
        return requireNotNull(
            javaClass.classLoader?.getResource("fixtures/enrichment/$name")
        ) { "Missing golden fixture $name" }.readText(Charsets.UTF_8)
    }

    private fun schemaFile(): File = listOf(
        File("../../StemWise/contracts/enrichment/song-enrichment-v2.schema.json"),
        File("../StemWise/contracts/enrichment/song-enrichment-v2.schema.json")
    ).first(File::isFile)

    private fun JSONArray.objects(): List<JSONObject> = buildList {
        repeat(length()) { index -> add(getJSONObject(index)) }
    }

    private fun validatePngChunks(bytes: ByteArray): Boolean {
        var offset = 8
        var foundEnd = false
        while (offset + 12 <= bytes.size) {
            val length = readBigEndianInt(bytes, offset)
            if (length < 0 || offset + length + 12 > bytes.size) return false
            val typeAndData = bytes.copyOfRange(offset + 4, offset + 8 + length)
            val expectedCrc = readBigEndianInt(bytes, offset + 8 + length).toLong() and 0xffffffffL
            val actualCrc = CRC32().apply { update(typeAndData) }.value
            if (expectedCrc != actualCrc) return false
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            offset += length + 12
            if (type == "IEND") {
                foundEnd = true
                break
            }
        }
        return foundEnd && offset == bytes.size
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    companion object {
        private const val FULL = "full-song-enrichment-v2.json"
        private const val PLAIN_LYRICS = "partial-plain-lyrics-update-v2.json"
        private const val NO_LYRICS = "unavailable-lyrics-update-v2.json"
        private const val METADATA_ONLY = "metadata-only-update-v2.json"
        private const val EXPECTED_GOLDEN_SHA256 =
            "e1ebeb6aa86354c99e979b2e8fb5909658f553494bd87b304ec52848bde374da"
        private val EXPECTED_TOOLS = listOf(
            "beat-this-full", "stemwise-rhythm-normalizer", "s-key",
            "all-in-one", "essentia", "stemwise-waveform"
        )
    }
}
