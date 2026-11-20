package app.stemwise.data

import app.stemwise.model.StemSourceCatalogue
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal const val CUSTOM_SOURCE_MANIFEST_MAX_BYTES = 128L * 1024L
internal const val CUSTOM_SOURCE_MANIFEST_INVALID = "SOURCE_MANIFEST_INVALID"
internal const val NATIVE_ORIGINAL_48K_REMAINING_PRODUCER = "stemwise-native-original48-v1"
internal const val SOURCE_REMAINING_REPREPARE_REQUIRED = "SOURCE_REMAINING_REPREPARE_REQUIRED"

/** One real, materialized 48 kHz stereo lossless lane; no placeholders are admitted. */
internal data class CustomSourceAsset(
    val sourceId: String,
    val label: String,
    val fileName: String,
    val sha256: String,
    val byteLength: Long,
    val audibleFrames: Long,
    val remainingProducer: String? = if (sourceId == "remaining") NATIVE_ORIGINAL_48K_REMAINING_PRODUCER else null,
    val timingEvidence: SourceTimingEvidence? = null
)

/** Declared lossless sources are independent of PC enrichment and preserve the Original identity. */
internal data class CustomSourceManifest(
    val videoId: String,
    val sourceSha256: String,
    val planSha256: String,
    val selectedSourceIds: List<String>,
    val audibleFrames: Long,
    val lanes: List<CustomSourceAsset>
) {
    init { requireValid() }

    val jobTimingStatus get() = SourceJobTimingStatus.JOB_TIMING_UNVERIFIED

    val revisionSha256: String
        get() = MessageDigest.getInstance("SHA-256").digest(toBytes()).toHexString()

    fun toBytes(): ByteArray {
        requireValid()
        val payload = JSONObject()
            .put("schemaVersion", 3)
            .put("kind", "stemwise.custom-sources")
            .put("videoId", videoId)
            .put("sourceSha256", sourceSha256)
            .put("planSha256", planSha256)
            .put("sampleRateHz", 48_000)
            .put("channels", 2)
            .put("audibleFrames", audibleFrames)
            .put("selectedSourceIds", JSONArray(selectedSourceIds))
            .put("lanes", JSONArray().apply {
                lanes.forEach { lane ->
                    put(JSONObject()
                        .put("sourceId", lane.sourceId)
                        .put("label", lane.label)
                        .put("fileName", lane.fileName)
                        .put("sha256", lane.sha256)
                        .put("byteLength", lane.byteLength)
                        .put("audibleFrames", lane.audibleFrames)
                        .apply {
                            lane.remainingProducer?.let { put("remainingProducer", it) }
                            lane.timingEvidence?.let { put("timingEvidence", it.toJson()) }
                        })
                }
            })
        payload.put("integritySha256", CanonicalJson.sha256(payload))
        return CanonicalJson.encode(payload).also { check(it.size.toLong() <= CUSTOM_SOURCE_MANIFEST_MAX_BYTES) { CUSTOM_SOURCE_MANIFEST_INVALID } }
    }

    private fun requireValid() {
        require(SelectedLibraryContract.isVideoId(videoId)) { CUSTOM_SOURCE_MANIFEST_INVALID }
        require(SHA256.matches(sourceSha256) && SHA256.matches(planSha256)) {
            CUSTOM_SOURCE_MANIFEST_INVALID
        }
        StemSourceCatalogue.requireSelection(selectedSourceIds)
        require(audibleFrames in 1..MAX_SAFE_INTEGER) { CUSTOM_SOURCE_MANIFEST_INVALID }
        require(lanes.size == selectedSourceIds.size + 1 &&
            lanes.size in 2..StemSourceCatalogue.MAX_TARGETS + 1) {
            CUSTOM_SOURCE_MANIFEST_INVALID
        }
        lanes.forEachIndexed { index, lane ->
            val sourceId = if (index == selectedSourceIds.size) "remaining" else selectedSourceIds[index]
            require(lane.sourceId == sourceId) {
                CUSTOM_SOURCE_MANIFEST_INVALID
            }
            validateCustomSourceAssetDeclaration(selectedSourceIds, lane, audibleFrames)
            lane.timingEvidence?.let {
                require(it.sourceSha256 == sourceSha256 && it.planSha256 == planSha256) {
                    CUSTOM_SOURCE_MANIFEST_INVALID
                }
            }
            require(lane.fileName.startsWith("replacement-") == lanes.first().fileName.startsWith("replacement-")) {
                CUSTOM_SOURCE_MANIFEST_INVALID
            }
        }
        // Distinct real parts may contain identical audio (for example two absent instruments).
        // Hashes bind each declared file's bytes; role and filename uniqueness establish its slot.
    }

    private companion object {
        val SHA256 = Regex("^[a-f0-9]{64}$")
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}

/** Shared by partial durable receipts and the final complete manifest. */
internal fun validateCustomSourceAssetDeclaration(
    selectedSourceIds: List<String>,
    asset: CustomSourceAsset,
    audibleFrames: Long = asset.audibleFrames
) {
    StemSourceCatalogue.requireSelection(selectedSourceIds)
    val remaining = asset.sourceId == "remaining"
    val index = selectedSourceIds.indexOf(asset.sourceId)
    check(remaining || index >= 0) { CUSTOM_SOURCE_MANIFEST_INVALID }
    val label = if (remaining) "Remaining" else checkNotNull(StemSourceCatalogue.source(asset.sourceId)).label
    val file = if (remaining) SelectedLibraryContract.REMAINING_FILE else SelectedLibraryContract.customTargetFiles[index]
    check(asset.label == label && asset.fileName in setOf(file, "replacement-$file") && asset.sha256.matches(Regex("^[a-f0-9]{64}$")) &&
        asset.byteLength in 1..9_007_199_254_740_991L && audibleFrames in 1..9_007_199_254_740_991L &&
        asset.audibleFrames == audibleFrames &&
        (if (remaining) asset.remainingProducer == NATIVE_ORIGINAL_48K_REMAINING_PRODUCER
            else asset.remainingProducer == null)) { CUSTOM_SOURCE_MANIFEST_INVALID }
    requireSourceTimingArtifactBinding(asset)
}

internal fun remainingProducerMatchesOriginal(remaining: CustomSourceAsset, originalSampleRateHz: Int): Boolean {
    check(remaining.sourceId == "remaining" && originalSampleRateHz in 8_000..384_000) {
        CUSTOM_SOURCE_MANIFEST_INVALID
    }
    return remaining.remainingProducer == NATIVE_ORIGINAL_48K_REMAINING_PRODUCER
}

internal fun requireRemainingProducerForOriginal(remaining: CustomSourceAsset, originalSampleRateHz: Int) {
    check(remainingProducerMatchesOriginal(remaining, originalSampleRateHz)) {
        SOURCE_REMAINING_REPREPARE_REQUIRED
    }
}

/** Exact canonical schema and source binding; no coercive numbers or unknown fields. */
internal fun parseCustomSourceManifest(
    bytes: ByteArray,
    expectedVideoId: String,
    expectedSourceSha256: String
): CustomSourceManifest = try {
    check(bytes.isNotEmpty() && bytes.size.toLong() <= CUSTOM_SOURCE_MANIFEST_MAX_BYTES)
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    check(root.keys().asSequence().toSet() == ROOT_KEYS)
    check(CanonicalJson.encode(root).contentEquals(bytes))
    val integrity = root.exactString("integritySha256")
    root.remove("integritySha256")
    check(CanonicalJson.sha256(root) == integrity)
    val version = root.exactLong("schemaVersion")
    check(version == 3L &&
        root.exactString("kind") == "stemwise.custom-sources" &&
        root.exactLong("sampleRateHz") == 48_000L && root.exactLong("channels") == 2L)
    val selected = root.getJSONArray("selectedSourceIds").let { values ->
        check(values.length() in 1..StemSourceCatalogue.MAX_TARGETS)
        List(values.length()) { index ->
            check(values.opt(index) is String)
            values.getString(index)
        }
    }
    val lanes = root.getJSONArray("lanes").let { values ->
        check(values.length() in 2..StemSourceCatalogue.MAX_TARGETS + 1)
        List(values.length()) { index ->
            val lane = values.getJSONObject(index)
            val markedRemaining = lane.exactString("sourceId") == "remaining"
            val keys = if (markedRemaining) LANE_KEYS + "remainingProducer" else LANE_KEYS
            check(lane.keys().asSequence().toSet() ==
                if (lane.has("timingEvidence")) keys + "timingEvidence" else keys)
            CustomSourceAsset(
                lane.exactString("sourceId"), lane.exactString("label"),
                lane.exactString("fileName"), lane.exactString("sha256"),
                lane.exactLong("byteLength"), lane.exactLong("audibleFrames"),
                if (markedRemaining) lane.exactString("remainingProducer") else null,
                if (lane.has("timingEvidence")) SourceTimingEvidence.parse(lane.getJSONObject("timingEvidence")) else null
            )
        }
    }
    CustomSourceManifest(
        root.exactString("videoId"), root.exactString("sourceSha256"),
        root.exactString("planSha256"), selected, root.exactLong("audibleFrames"), lanes
    ).also { manifest ->
        check(manifest.videoId == expectedVideoId && manifest.sourceSha256 == expectedSourceSha256)
        check(manifest.toBytes().contentEquals(bytes))
    }
} catch (error: Exception) {
    throw IllegalStateException(CUSTOM_SOURCE_MANIFEST_INVALID, error)
}

/** Every declared custom file exists and no undeclared custom lane can enter the package. */
internal fun validateCustomSourceManifestFiles(
    manifest: CustomSourceManifest,
    presentFileNames: Set<String>
) {
    check(SelectedLibraryContract.originalFiles.count(presentFileNames::contains) == 1 &&
        SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST in presentFileNames &&
        presentFileNames.containsAll(manifest.lanes.map(CustomSourceAsset::fileName))) {
        CUSTOM_SOURCE_MANIFEST_INVALID
    }
}

/** Independent opened-byte and decoder facts are required; manifest claims alone are insufficient. */
internal fun validateCustomSourceAsset(
    asset: CustomSourceAsset,
    observedSha256: String,
    observedByteLength: Long,
    audio: PreparedSourceAudioFacts,
    decodedMime: String,
    decodedSampleRateHz: Int?,
    decodedChannels: Int?
) {
    validateCustomSourceAudioAsset(asset, observedSha256, observedByteLength, audio)
    // Android's extractor may expose inspected FLAC as decoded PCM.
    val mimeMatches = decodedMime == audio.mime || audio.mime == "audio/flac" && decodedMime == "audio/raw"
    check(mimeMatches && decodedSampleRateHz == 48_000 && decodedChannels == 2) {
        CUSTOM_SOURCE_MANIFEST_INVALID
    }
}

/** Structural media facts; these do not claim that a decoder produced PCM. */
internal fun validateCustomSourceAudioAsset(
    asset: CustomSourceAsset,
    observedSha256: String,
    observedByteLength: Long,
    audio: PreparedSourceAudioFacts
) {
    requireSourceTimingArtifactBinding(asset)
    val remaining = asset.sourceId == "remaining"
    check(asset.sha256 == observedSha256 && asset.byteLength == observedByteLength &&
        asset.audibleFrames == audio.audibleFrames && audio.sampleRate == 48_000 && audio.channels == 2 &&
        audio.isFloat == remaining && audio.bitsPerSample == (if (remaining) 32 else 16) &&
        audio.mime == (if (remaining) "audio/wavpack" else "audio/flac") &&
        asset.fileName.endsWith(if (remaining) ".wv" else ".flac")) {
        CUSTOM_SOURCE_MANIFEST_INVALID
    }
}

private fun JSONObject.exactString(key: String): String {
    check(opt(key) is String)
    return getString(key)
}

private fun JSONObject.exactLong(key: String): Long {
    val value = opt(key)
    check(value is Int || value is Long)
    return (value as Number).toLong()
}

private val ROOT_KEYS = setOf(
    "schemaVersion", "kind", "videoId", "sourceSha256", "planSha256", "sampleRateHz", "channels",
    "audibleFrames", "selectedSourceIds", "lanes", "integritySha256"
)
private val LANE_KEYS = setOf("sourceId", "label", "fileName", "sha256", "byteLength", "audibleFrames")
