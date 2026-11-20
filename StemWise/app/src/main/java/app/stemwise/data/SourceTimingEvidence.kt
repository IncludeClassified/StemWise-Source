package app.stemwise.data

import org.json.JSONObject

internal enum class SourceStructuralTimelineStatus { STRUCTURAL_TIMELINE_VALID }
internal enum class SourceRouteTimingStatus { ROUTE_UNQUALIFIED }
internal enum class SourceJobTimingStatus { JOB_TIMING_UNVERIFIED }

/** Observed file/header identity. No PCM hash or waveform timing is inferred from these facts. */
internal data class SourceTimingRawArtifact(
    val sha256: String,
    val byteLength: Long,
    val sampleRateHz: Int,
    val channels: Int,
    val audibleFrames: Long
)

/** Ordinary jobs have no independent reference: structural checks never certify waveform timing. */
internal data class SourceTimingEvidence(
    val sourceVersionId: String,
    val sourceSha256: String,
    val planSha256: String,
    val jobId: String,
    val generation: Long,
    val configurationId: String,
    val renderId: Int,
    val options: Map<String, String>,
    val selectedSourceId: String,
    val providerRole: String,
    val remoteJobId: String,
    val uploadSampleRateHz: Int,
    val uploadChannels: Int,
    val uploadAudibleFrames48k: Long,
    val uploadAudibleFramesNative: Long,
    val uploadEncodedFramesNative: Long,
    val uploadPreSkipFrames: Long,
    val uploadEndTrimFrames: Long,
    val uploadInitialGranuleOffset: Long,
    val raw: SourceTimingRawArtifact?,
    val preparedSha256: String,
    val preparedByteLength: Long,
    val preparedAudibleFrames48k: Long,
    val conversionProfile: String
) {
    val structuralStatus get() = SourceStructuralTimelineStatus.STRUCTURAL_TIMELINE_VALID
    val routeStatus get() = SourceRouteTimingStatus.ROUTE_UNQUALIFIED
    val jobStatus get() = SourceJobTimingStatus.JOB_TIMING_UNVERIFIED

    init {
        check(listOf(sourceSha256, planSha256, preparedSha256).all(SHA256::matches) &&
            listOf(sourceVersionId, jobId, configurationId, selectedSourceId, providerRole, remoteJobId).all {
                it.isNotBlank() && it == it.trim() && it.length <= 512 && it.none(Char::isISOControl)
            } && generation in 1..MAX_SAFE_INTEGER && renderId > 0 &&
            uploadSampleRateHz in 8_000..384_000 && uploadChannels in 1..2 &&
            uploadAudibleFrames48k in 1..MAX_SAFE_INTEGER && preparedByteLength in 1..MAX_SAFE_INTEGER &&
            uploadAudibleFramesNative in 1..MAX_SAFE_INTEGER && uploadEncodedFramesNative in uploadAudibleFramesNative..MAX_SAFE_INTEGER &&
            uploadPreSkipFrames in 0..MAX_SAFE_INTEGER && uploadEndTrimFrames in 0..MAX_SAFE_INTEGER &&
            uploadInitialGranuleOffset in 0..MAX_SAFE_INTEGER &&
            uploadEncodedFramesNative - uploadPreSkipFrames - uploadEndTrimFrames == uploadAudibleFramesNative &&
            uploadAudibleFrames48k == (Math.multiplyExact(uploadAudibleFramesNative, 48_000L) + uploadSampleRateHz - 1L) / uploadSampleRateHz &&
            preparedAudibleFrames48k == uploadAudibleFrames48k &&
            conversionProfile in CONVERSION_PROFILES && options.size <= 3 && options.all { (key, value) ->
                key in setOf("add_opt1", "add_opt2", "add_opt3") && value.matches(Regex("^[0-9]+$"))
            }) { SOURCE_TIMING_EVIDENCE_INVALID }
        check((raw == null) == (conversionProfile == SOURCE_TIMING_RETAINED_PROFILE)) { SOURCE_TIMING_EVIDENCE_INVALID }
        raw?.let {
            check(SHA256.matches(it.sha256) && it.byteLength in 1..MAX_SAFE_INTEGER &&
                it.sampleRateHz in 8_000..384_000 && it.channels in 1..2 && it.audibleFrames in 1..MAX_SAFE_INTEGER &&
                kotlin.math.abs(Math.multiplyExact(it.audibleFrames, 48_000L) -
                    Math.multiplyExact(uploadAudibleFrames48k, it.sampleRateHz.toLong())) <= it.sampleRateHz) {
                SOURCE_TIMING_EVIDENCE_INVALID
            }
            if (conversionProfile == SOURCE_TIMING_COPY_PROFILE) check(it.sha256 == preparedSha256 &&
                it.byteLength == preparedByteLength && it.sampleRateHz == 48_000 && it.channels == 2 &&
                it.audibleFrames == preparedAudibleFrames48k) { SOURCE_TIMING_EVIDENCE_INVALID }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("structuralStatus", structuralStatus.name)
        .put("routeStatus", routeStatus.name).put("jobStatus", jobStatus.name)
        .put("analyzerProfile", "stemwise-structural-only-v1")
        .put("referenceIdentity", JSONObject.NULL).put("measuredFrames", 0)
        .put("resolutionFrames", JSONObject.NULL).put("decoderProfile", JSONObject.NULL)
        .put("sourcePcmSha256", JSONObject.NULL).put("rawPcmSha256", JSONObject.NULL)
        .put("preparedPcmSha256", JSONObject.NULL)
        .put("sourceVersionId", sourceVersionId).put("sourceSha256", sourceSha256).put("planSha256", planSha256)
        .put("jobId", jobId).put("generation", generation).put("configurationId", configurationId)
        .put("renderId", renderId).put("options", JSONObject(options)).put("selectedSourceId", selectedSourceId)
        .put("requestFields", JSONObject().put("output_format", "2").put("is_demo", "0"))
        .put("providerRole", providerRole).put("remoteJobId", remoteJobId)
        .put("uploadSampleRateHz", uploadSampleRateHz).put("uploadChannels", uploadChannels)
        .put("uploadAudibleFrames48k", uploadAudibleFrames48k)
        .put("uploadAudibleFramesNative", uploadAudibleFramesNative).put("uploadEncodedFramesNative", uploadEncodedFramesNative)
        .put("uploadPreSkipFrames", uploadPreSkipFrames).put("uploadEndTrimFrames", uploadEndTrimFrames)
        .put("uploadInitialGranuleOffset", uploadInitialGranuleOffset)
        .put("raw", raw?.let { JSONObject().put("sha256", it.sha256).put("byteLength", it.byteLength)
            .put("sampleRateHz", it.sampleRateHz).put("channels", it.channels).put("audibleFrames", it.audibleFrames) }
            ?: JSONObject.NULL)
        .put("preparedSha256", preparedSha256).put("preparedByteLength", preparedByteLength)
        .put("preparedAudibleFrames48k", preparedAudibleFrames48k).put("conversionProfile", conversionProfile)

    companion object {
        fun parse(json: JSONObject): SourceTimingEvidence = try {
            fun text(key: String): String { check(json.opt(key) is String); return json.getString(key) }
            fun number(key: String): Long = exactTimingLong(json, key)
            fun integer(key: String): Int = number(key).let { check(it in Int.MIN_VALUE..Int.MAX_VALUE); it.toInt() }
            val raw = if (json.isNull("raw")) null else json.getJSONObject("raw").let {
                check(it.opt("sha256") is String)
                SourceTimingRawArtifact(it.getString("sha256"), exactTimingLong(it, "byteLength"),
                    exactTimingInt(it, "sampleRateHz"), exactTimingInt(it, "channels"), exactTimingLong(it, "audibleFrames"))
            }
            val options = json.getJSONObject("options").let { values -> values.keys().asSequence().associateWith {
                check(values.opt(it) is String); values.getString(it)
            } }
            SourceTimingEvidence(text("sourceVersionId"), text("sourceSha256"), text("planSha256"), text("jobId"),
                number("generation"), text("configurationId"), integer("renderId"), options, text("selectedSourceId"),
                text("providerRole"), text("remoteJobId"), integer("uploadSampleRateHz"), integer("uploadChannels"),
                number("uploadAudibleFrames48k"), number("uploadAudibleFramesNative"), number("uploadEncodedFramesNative"),
                number("uploadPreSkipFrames"), number("uploadEndTrimFrames"), number("uploadInitialGranuleOffset"),
                raw, text("preparedSha256"), number("preparedByteLength"),
                number("preparedAudibleFrames48k"), text("conversionProfile")).also {
                // Canonical equality rejects invented PASS flags, nonzero coverage and unknown fields.
                check(CanonicalJson.encode(json).contentEquals(CanonicalJson.encode(it.toJson())))
            }
        } catch (error: Exception) { throw IllegalStateException(SOURCE_TIMING_EVIDENCE_INVALID, error) }
    }
}

internal fun requireSourceTimingArtifactBinding(asset: CustomSourceAsset) {
    asset.timingEvidence?.let {
        check(asset.sourceId != "remaining" && it.selectedSourceId == asset.sourceId &&
            it.preparedSha256 == asset.sha256 && it.preparedByteLength == asset.byteLength &&
            it.preparedAudibleFrames48k == asset.audibleFrames) { SOURCE_TIMING_EVIDENCE_INVALID }
    }
}

internal fun requireSourceTimingOriginalBinding(asset: CustomSourceAsset, original: OriginalAudioFacts) {
    asset.timingEvidence?.let {
        check(it.uploadSampleRateHz == original.sampleRateHz && it.uploadChannels == original.channels &&
            it.uploadAudibleFrames48k == original.audibleFrames48k && it.uploadAudibleFramesNative == original.audibleFramesNative &&
            it.uploadEncodedFramesNative == original.encodedFramesNative && it.uploadPreSkipFrames == original.preSkipFrames &&
            it.uploadEndTrimFrames == original.endTrimFrames && it.uploadInitialGranuleOffset == original.initialGranuleOffset) {
            SOURCE_TIMING_EVIDENCE_INVALID
        }
    }
}

private fun exactTimingLong(json: JSONObject, key: String): Long {
    val value = json.opt(key)
    check(value is Int || value is Long)
    return (value as Number).toLong()
}
private fun exactTimingInt(json: JSONObject, key: String): Int = exactTimingLong(json, key).let {
    check(it in Int.MIN_VALUE..Int.MAX_VALUE); it.toInt()
}

internal const val SOURCE_TIMING_EVIDENCE_INVALID = "SOURCE_TIMING_EVIDENCE_INVALID"
internal const val SOURCE_TIMING_COPY_PROFILE = "flac16-byte-copy-v1"
internal const val SOURCE_TIMING_CONVERSION_PROFILE = "ffmpeg-flac16-48k-stereo-ceiling-endpoint-v1"
internal const val SOURCE_TIMING_RETAINED_PROFILE = "retained-raw-unavailable-v1"
private val CONVERSION_PROFILES = setOf(SOURCE_TIMING_COPY_PROFILE, SOURCE_TIMING_CONVERSION_PROFILE, SOURCE_TIMING_RETAINED_PROFILE)
private val SHA256 = Regex("^[a-f0-9]{64}$")
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
