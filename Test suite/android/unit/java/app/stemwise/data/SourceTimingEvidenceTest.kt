package app.stemwise.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceTimingEvidenceTest {
    @Test fun actualProducerEvidenceRoundTripsThroughManifestWithoutPromotingTiming() {
        val target = target()
        val evidence = evidence()
        val manifest = CustomSourceManifest(VIDEO, SOURCE, PLAN, listOf("vocals"), 48_000,
            listOf(target.copy(timingEvidence = evidence),
                CustomSourceAsset("remaining", "Remaining", "remaining.wv", "d".repeat(64), 2_000, 48_000)))
        val reopened = parseCustomSourceManifest(manifest.toBytes(), VIDEO, SOURCE)
        assertEquals(evidence, reopened.lanes.first().timingEvidence)
        assertEquals(SourceJobTimingStatus.JOB_TIMING_UNVERIFIED, reopened.jobTimingStatus)
        assertEquals(SourceRouteTimingStatus.ROUTE_UNQUALIFIED, evidence.routeStatus)
        val record = evidence.toJson()
        for (field in listOf("referenceIdentity", "resolutionFrames", "sourcePcmSha256", "rawPcmSha256",
            "preparedPcmSha256", "decoderProfile")) assertTrue(record.isNull(field))
        assertEquals(0, record.getInt("measuredFrames"))
        assertEquals(emptyMap<String, String>(), evidence.options)
        assertFalse(record.getJSONObject("options").has("add_opt1"))
        assertEquals("2", record.getJSONObject("requestFields").getString("output_format"))
    }

    @Test fun fabricatedVerificationCoverageReferenceAndPcmClaimsAreRejected() {
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("jobStatus", "JOB_TIMING_VERIFIED_WITHIN_BOUND") },
            { it.put("routeStatus", "ROUTE_QUALIFIED_ON_REFERENCES") },
            { it.put("measuredFrames", 48_000) },
            { it.put("resolutionFrames", 1) },
            { it.put("referenceIdentity", "host-report-pass") },
            { it.put("rawPcmSha256", "e".repeat(64)) },
            { it.put("decoderProfile", "unobserved-decoder") },
            { it.getJSONObject("requestFields").put("is_demo", "1") },
            { it.getJSONObject("raw").put("unobserved", true) }
        )
        val original = evidence().toJson()
        val durable = CanonicalJson.encode(original)
        for (mutation in mutations) {
            val changed = JSONObject(original.toString())
            mutation(changed)
            assertThrows(IllegalStateException::class.java) { SourceTimingEvidence.parse(changed) }
        }
        assertArrayEquals(durable, CanonicalJson.encode(original))
    }

    @Test fun preparedByteClockAndRoleSubstitutionsFailTheirOwnBindings() {
        val target = target().copy(timingEvidence = evidence())
        val facts = PreparedSourceAudioFacts(48_000, 2, 16, false, 48_000)
        validateCustomSourceAudioAsset(target, target.sha256, target.byteLength, facts)
        for (changed in listOf(target.copy(sha256 = "f".repeat(64)), target.copy(byteLength = 1_001),
            target.copy(audibleFrames = 47_999), target.copy(sourceId = "bass"))) {
            assertThrows(IllegalStateException::class.java) { requireSourceTimingArtifactBinding(changed) }
        }
        val original = OriginalAudioFacts(2, 48_000, "audio/flac", 48_000, audibleFramesNative = 48_000,
            encodedFramesNative = 48_000, preSkipFrames = 0, endTrimFrames = 0, initialGranuleOffset = 0)
        requireSourceTimingOriginalBinding(target, original)
        for (changed in listOf(original.copy(channels = 1), original.copy(sampleRateHz = 44_100),
            original.copy(audibleFrames48k = 47_999))) {
            assertThrows(IllegalStateException::class.java) { requireSourceTimingOriginalBinding(target, changed) }
        }
        for (changed in listOf<(SourceTimingEvidence) -> SourceTimingEvidence>(
            { it.copy(uploadEncodedFramesNative = 48_001) },
            { it.copy(uploadPreSkipFrames = 1) },
            { it.copy(uploadEndTrimFrames = 1) }
        )) assertThrows(IllegalStateException::class.java) { changed(evidence()) }
        assertThrows(IllegalArgumentException::class.java) {
            CustomSourceManifest(VIDEO, "e".repeat(64), PLAN, listOf("vocals"), 48_000,
                listOf(target, CustomSourceAsset("remaining", "Remaining", "remaining.wv", "d".repeat(64), 2_000, 48_000)))
        }
    }

    @Test fun unavailableRawEvidenceAndAbsentReceiptEvidenceStayUnverified() {
        val recovered = evidence().copy(raw = null, conversionProfile = SOURCE_TIMING_RETAINED_PROFILE)
        assertEquals(recovered, SourceTimingEvidence.parse(recovered.toJson()))
        assertTrue(recovered.toJson().isNull("raw"))
        assertEquals(SourceJobTimingStatus.JOB_TIMING_UNVERIFIED, recovered.jobStatus)
        requireSourceTimingArtifactBinding(target())
        assertThrows(IllegalStateException::class.java) { evidence().copy(raw = null) }
        assertThrows(IllegalStateException::class.java) {
            evidence().copy(raw = evidence().raw!!.copy(audibleFrames = 48_100))
        }
    }

    private fun target() = CustomSourceAsset("vocals", "All vocals", "part-1.flac", PREPARED, 1_000, 48_000)
    private fun evidence() = SourceTimingEvidence("source-version", SOURCE, PLAN, "job", 1, "mvsep-63", 63,
        emptyMap(), "vocals", "vocals", "accepted-job", 48_000, 2, 48_000, 48_000, 48_000, 0, 0, 0,
        SourceTimingRawArtifact(PREPARED, 1_000, 48_000, 2, 48_000), PREPARED, 1_000, 48_000,
        SOURCE_TIMING_COPY_PROFILE)

    companion object {
        private const val VIDEO = "AbCdEf_12-3"
        private val SOURCE = "a".repeat(64)
        private val PLAN = "b".repeat(64)
        private val PREPARED = "c".repeat(64)
    }
}
