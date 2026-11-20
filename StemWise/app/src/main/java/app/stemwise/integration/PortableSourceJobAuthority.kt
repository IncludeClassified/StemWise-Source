package app.stemwise.integration

import app.stemwise.data.CanonicalJson
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.interruptedReservedAuthorityWriteIsRecoverable
import app.stemwise.data.CustomSourceAsset
import app.stemwise.data.NATIVE_ORIGINAL_48K_REMAINING_PRODUCER
import app.stemwise.data.validateCustomSourceAssetDeclaration
import app.stemwise.data.SourceTimingEvidence
import app.stemwise.data.SourceTimingRawArtifact
import app.stemwise.data.OriginalAudioFacts
import app.stemwise.data.SOURCE_TIMING_EVIDENCE_INVALID
import app.stemwise.model.StemSourceCatalogue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val PORTABLE_SOURCE_JOB_MAX_BYTES = 64L * 1024L
internal const val PORTABLE_SOURCE_JOB_INVALID = "SOURCE_JOB_AUTHORITY_INVALID"
internal const val PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED = "SOURCE_JOB_AUTHORITY_RECOVERY_REQUIRED"

internal val SOURCE_JOB_AUTHORITY_FILES = setOf(SelectedLibraryContract.SOURCE_JOB_AUTHORITY,
    SelectedLibraryContract.SOURCE_JOB_AUTHORITY_TEMP, SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY,
    SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY_TEMP)

internal suspend fun readPortableSourceJobAuthority(roots: app.stemwise.data.LibraryRootManager,
    root: app.stemwise.data.RootHandle, songId: String): PortableSourceJobAuthority? {
    roots.requireSameLibrary(root)
    val copies = roots.readExactSongSidecars(root, songId, SOURCE_JOB_AUTHORITY_FILES, PORTABLE_SOURCE_JOB_MAX_BYTES)
    return latestSourceJobAuthority(listOfNotNull(
        selectPortableSourceJobAuthority(copies[SelectedLibraryContract.SOURCE_JOB_AUTHORITY], copies[SelectedLibraryContract.SOURCE_JOB_AUTHORITY_TEMP]),
        selectPortableSourceJobAuthority(copies[SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY], copies[SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY_TEMP])
    ))?.also {
        check(it.libraryId == root.libraryId && it.videoId == songId) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
    }
}

internal data class PortableSourceJobAuthority(
    val libraryId: String,
    val videoId: String,
    val sourceVersionId: String,
    val sourceSha256: String,
    val jobId: String,
    val plan: MvsepSourcePlan,
    val generation: Long,
    val revision: Long,
    val state: String,
    val remoteJobIds: List<String?>,
    val activeOperationIndex: Int,
    val attempts: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val assets: Map<String, CustomSourceAsset> = emptyMap(),
    val schemaVersion: Int = 2
) {
    init { requireValid() }

    val planSha256: String get() = mvsepSourcePlanSha256(plan)
    val requiresSubmission: Boolean get() = state == "CREATED" && remoteJobIds[activeOperationIndex] == null
    val currentRemoteJobId: String? get() = remoteJobIds[activeOperationIndex]

    fun toBytes(): ByteArray {
        requireValid()
        val payload = JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("kind", "stemwise.source-job")
            .put("libraryId", libraryId).put("videoId", videoId)
            .put("sourceVersionId", sourceVersionId).put("sourceSha256", sourceSha256)
            .put("jobId", jobId).put("planSha256", planSha256).put("plan", sourcePlanJson(plan))
            .put("generation", generation).put("revision", revision).put("state", state)
            .put("remoteJobIds", JSONArray().apply { remoteJobIds.forEach { put(it ?: JSONObject.NULL) } })
            .put("activeOperationIndex", activeOperationIndex).put("attempts", attempts)
            .put("createdAtEpochMs", createdAtEpochMs).put("updatedAtEpochMs", updatedAtEpochMs)
            .put("assets", JSONObject().apply { assets.forEach { (id, asset) -> put(id, sourceAssetJson(asset)) } })
        payload.put("integritySha256", CanonicalJson.sha256(payload))
        return CanonicalJson.encode(payload)
    }

    internal fun sameIdentity(other: PortableSourceJobAuthority): Boolean =
        libraryId == other.libraryId && videoId == other.videoId && sourceVersionId == other.sourceVersionId &&
            sourceSha256 == other.sourceSha256 && jobId == other.jobId && plan == other.plan &&
            generation == other.generation && createdAtEpochMs == other.createdAtEpochMs

    private fun requireValid() {
        require(schemaVersion == 2) { PORTABLE_SOURCE_JOB_INVALID }
        require(runCatching { UUID.fromString(libraryId).toString() == libraryId }.getOrDefault(false)) {
            PORTABLE_SOURCE_JOB_INVALID
        }
        require(SelectedLibraryContract.isVideoId(videoId) &&
            sourceVersionId.isNotBlank() && sourceVersionId == sourceVersionId.trim() &&
            sourceVersionId.length <= 512 && sourceVersionId.none(Char::isISOControl) &&
            SHA256.matches(sourceSha256)) { PORTABLE_SOURCE_JOB_INVALID }
        MvsepSourcePlanner.requirePlanContract(plan)
        require(jobId == sourceSeparationJobId(sourceVersionId, sourceSha256, planSha256, generation)) {
            PORTABLE_SOURCE_JOB_INVALID
        }
        require(generation in 1..MAX_SAFE_INTEGER && revision in 1..MAX_SAFE_INTEGER &&
            createdAtEpochMs in 1..MAX_SAFE_INTEGER && updatedAtEpochMs in createdAtEpochMs..MAX_SAFE_INTEGER &&
            state in STATES && remoteJobIds.size == plan.operations.size &&
            activeOperationIndex in plan.operations.indices) { PORTABLE_SOURCE_JOB_INVALID }
        remoteJobIds.forEachIndexed { index, remoteId ->
            require(remoteId == null || validRemoteId(remoteId)) { PORTABLE_SOURCE_JOB_INVALID }
            require(index >= activeOperationIndex || remoteId != null) { PORTABLE_SOURCE_JOB_INVALID }
            require(index <= activeOperationIndex || remoteId == null) { PORTABLE_SOURCE_JOB_INVALID }
        }
        require(remoteJobIds.filterNotNull().distinct().size == remoteJobIds.filterNotNull().size) {
            PORTABLE_SOURCE_JOB_INVALID
        }
        val currentPending = state in PENDING_STATES
        require((currentRemoteJobId == null) == currentPending) { PORTABLE_SOURCE_JOB_INVALID }
        require(attempts == activeOperationIndex + if (state == "CREATED") 0 else 1) {
            PORTABLE_SOURCE_JOB_INVALID
        }
        if (state in FINAL_STATES) {
            require(activeOperationIndex == plan.operations.lastIndex && remoteJobIds.all { it != null }) {
                PORTABLE_SOURCE_JOB_INVALID
            }
        }
        assets.forEach { (id, asset) ->
            require(id == asset.sourceId) { PORTABLE_SOURCE_JOB_INVALID }
            validateCustomSourceAssetDeclaration(plan.selectedSourceIds, asset)
            requirePortableSourceTimingBinding(this, asset)
            require(asset.fileName == sourcePartFileName(generation, plan.selectedSourceIds, id)) { PORTABLE_SOURCE_JOB_INVALID }
            if (id == "remaining") {
                require(state in FINAL_STATES) { PORTABLE_SOURCE_JOB_INVALID }
                require(asset.remainingProducer == NATIVE_ORIGINAL_48K_REMAINING_PRODUCER) { PORTABLE_SOURCE_JOB_INVALID }
            } else {
                val operationIndex = plan.operations.indexOfFirst { id in it.outputBindings }
                require(operationIndex in 0..activeOperationIndex &&
                    (operationIndex < activeOperationIndex || state in setOf("DOWNLOADING", "REMOTE_FAILED", "DISCARDED") || state in FINAL_STATES)) {
                    PORTABLE_SOURCE_JOB_INVALID
                }
            }
        }
        // Identical audio across distinct real roles is valid; each immutable receipt still
        // binds its own exact filename, bytes and provider role to this source plan.
        require(assets.values.map(CustomSourceAsset::audibleFrames).distinct().size <= 1) { PORTABLE_SOURCE_JOB_INVALID }
        plan.operations.take(activeOperationIndex).forEach { operation ->
            require(assets.keys.containsAll(operation.outputBindings.keys)) { PORTABLE_SOURCE_JOB_INVALID }
        }
        if (state in FINAL_STATES) require(assets.keys.containsAll(plan.selectedSourceIds)) { PORTABLE_SOURCE_JOB_INVALID }
        if (state == "PUBLISHING" || state == "COMPLETE") require("remaining" in assets) { PORTABLE_SOURCE_JOB_INVALID }
    }
}

internal fun sourceSeparationJobId(sourceVersionId: String, sourceSha256: String, planSha256: String, generation: Long = 1L): String =
    UUID.nameUUIDFromBytes(("mvsep-sources:$sourceVersionId:$sourceSha256:$planSha256" +
        if (generation == 1L) "" else ":$generation").toByteArray(Charsets.UTF_8)).toString()

internal fun sourcePartFileName(generation: Long, selectedSourceIds: List<String>, sourceId: String): String {
    val file = if (sourceId == "remaining") SelectedLibraryContract.REMAINING_FILE else
        SelectedLibraryContract.customTargetFiles[selectedSourceIds.indexOf(sourceId)]
    return if (generation % 2L == 0L) "replacement-$file" else file
}

internal fun sourceAuthorityFileNames(generation: Long): Pair<String, String> = if (generation % 2L == 0L)
    SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY to SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY_TEMP
else SelectedLibraryContract.SOURCE_JOB_AUTHORITY to SelectedLibraryContract.SOURCE_JOB_AUTHORITY_TEMP

internal fun latestSourceJobAuthority(authorities: List<PortableSourceJobAuthority>): PortableSourceJobAuthority? {
    val ordered = authorities.sortedBy(PortableSourceJobAuthority::generation)
    ordered.zipWithNext().forEach { (old, next) ->
        check(old.generation < next.generation && old.state in setOf("COMPLETE", "DISCARDED") &&
            old.libraryId == next.libraryId && old.videoId == next.videoId &&
            old.sourceVersionId == next.sourceVersionId && old.sourceSha256 == next.sourceSha256) {
            PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
        }
    }
    return ordered.lastOrNull()
}

internal fun mvsepSourcePlanSha256(plan: MvsepSourcePlan): String {
    MvsepSourcePlanner.requirePlanContract(plan)
    return CanonicalJson.sha256(sourcePlanJson(plan))
}

internal fun createPortableSourceJobAuthority(
    libraryId: String,
    videoId: String,
    sourceVersionId: String,
    sourceSha256: String,
    plan: MvsepSourcePlan,
    now: Long,
    generation: Long = 1L
): PortableSourceJobAuthority = PortableSourceJobAuthority(
    libraryId, videoId, sourceVersionId, sourceSha256,
    sourceSeparationJobId(sourceVersionId, sourceSha256, mvsepSourcePlanSha256(plan), generation), plan,
    generation, 1L, "CREATED", List(plan.operations.size) { null }, 0, 0, now, now
)

internal enum class SourceJobTransition {
    ENTER_SUBMISSION, ACKNOWLEDGE_REMOTE, BEGIN_DOWNLOAD, ADVANCE_OPERATION,
    BEGIN_ASSEMBLY, BEGIN_PUBLICATION, COMPLETE, MARK_SUBMISSION_UNKNOWN, REJECT_SUBMISSION, RETRY_REJECTED, RECOVER_REMOTE,
    REMOTE_FAILED, DISCARD_FAILED
}

/** Callers verify downloaded assets before advancement, and reopen the published package before completion. */
internal fun transitionPortableSourceJob(
    authority: PortableSourceJobAuthority,
    transition: SourceJobTransition,
    now: Long,
    remoteJobId: String? = null
): PortableSourceJobAuthority {
    check(now > 0L && authority.revision < MAX_SAFE_INTEGER) {
        PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
    }
    check(remoteJobId == null || transition in setOf(SourceJobTransition.ACKNOWLEDGE_REMOTE, SourceJobTransition.RECOVER_REMOTE)) {
        PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
    }
    val next = when (transition) {
        SourceJobTransition.REMOTE_FAILED -> {
            check(authority.state in setOf("PROCESSING", "DOWNLOADING")) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            authority.copy(state = "REMOTE_FAILED")
        }
        SourceJobTransition.DISCARD_FAILED -> {
            check(authority.state == "REMOTE_FAILED") { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            authority.copy(state = "DISCARDED")
        }
        SourceJobTransition.ENTER_SUBMISSION -> {
            check(authority.requiresSubmission) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            authority.copy(state = "SUBMISSION_STARTED", attempts = Math.addExact(authority.attempts, 1))
        }
        SourceJobTransition.ACKNOWLEDGE_REMOTE, SourceJobTransition.RECOVER_REMOTE -> {
            val expectedState = if (transition == SourceJobTransition.RECOVER_REMOTE) "SUBMISSION_UNKNOWN" else "SUBMISSION_STARTED"
            check(authority.state == expectedState && remoteJobId != null && validRemoteId(remoteJobId) &&
                remoteJobId !in authority.remoteJobIds) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            val ids = authority.remoteJobIds.toMutableList()
            ids[authority.activeOperationIndex] = remoteJobId
            authority.copy(state = "PROCESSING", remoteJobIds = ids.toList())
        }
        SourceJobTransition.BEGIN_DOWNLOAD -> {
            check(authority.state == "PROCESSING") { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            authority.copy(state = "DOWNLOADING")
        }
        SourceJobTransition.ADVANCE_OPERATION -> {
            check(authority.state == "DOWNLOADING" && authority.activeOperationIndex < authority.plan.operations.lastIndex) {
                PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
            }
            check(authority.assets.keys.containsAll(authority.plan.operations[authority.activeOperationIndex].outputBindings.keys)) {
                PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
            }
            authority.copy(state = "CREATED", activeOperationIndex = authority.activeOperationIndex + 1)
        }
        SourceJobTransition.BEGIN_ASSEMBLY -> {
            check(authority.state == "DOWNLOADING" && authority.activeOperationIndex == authority.plan.operations.lastIndex) {
                PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
            }
            check(authority.assets.keys.containsAll(authority.plan.selectedSourceIds)) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            authority.copy(state = "ASSEMBLING")
        }
        SourceJobTransition.BEGIN_PUBLICATION -> {
            check(authority.state == "ASSEMBLING") { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            check("remaining" in authority.assets) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            authority.copy(state = "PUBLISHING")
        }
        SourceJobTransition.COMPLETE -> {
            check(authority.state == "PUBLISHING") { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            authority.copy(state = "COMPLETE")
        }
        SourceJobTransition.REJECT_SUBMISSION -> {
            check(authority.state == "SUBMISSION_STARTED") { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            authority.copy(state = "SUBMISSION_REJECTED")
        }
        SourceJobTransition.RETRY_REJECTED -> {
            check(authority.state == "SUBMISSION_REJECTED") { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            authority.copy(state = "CREATED", attempts = authority.activeOperationIndex)
        }
        SourceJobTransition.MARK_SUBMISSION_UNKNOWN -> {
            check(authority.state == "SUBMISSION_STARTED") { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            authority.copy(state = "SUBMISSION_UNKNOWN")
        }
    }
    return next.copy(revision = authority.revision + 1L, updatedAtEpochMs = maxOf(now, authority.updatedAtEpochMs))
}

/** Retained parts are reusable only after the caller reopens bytes matching this immutable receipt. */
internal fun recordPortableSourceAsset(
    authority: PortableSourceJobAuthority,
    asset: CustomSourceAsset,
    now: Long
): PortableSourceJobAuthority {
    check(now > 0L && authority.revision < MAX_SAFE_INTEGER) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
    val existing = authority.assets[asset.sourceId]
    if (existing != null) {
        check(existing == asset) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
        return authority
    }
    check(if (asset.sourceId == "remaining") authority.state == "ASSEMBLING" else
        authority.state == "DOWNLOADING" &&
            asset.sourceId in authority.plan.operations[authority.activeOperationIndex].outputBindings) {
        PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
    }
    return authority.copy(assets = authority.assets + (asset.sourceId to asset),
        revision = authority.revision + 1, updatedAtEpochMs = maxOf(now, authority.updatedAtEpochMs),
        schemaVersion = 2)
}

internal fun parsePortableSourceJobAuthority(bytes: ByteArray): PortableSourceJobAuthority = try {
    check(bytes.isNotEmpty() && bytes.size.toLong() <= PORTABLE_SOURCE_JOB_MAX_BYTES)
    val json = JSONObject(bytes.toString(Charsets.UTF_8))
    check(json.keys().asSequence().toSet() == AUTHORITY_KEYS && CanonicalJson.encode(json).contentEquals(bytes))
    val integrity = json.sourceString("integritySha256")
    json.remove("integritySha256")
    check(SHA256.matches(integrity) && CanonicalJson.sha256(json) == integrity)
    val version = json.sourceInt("schemaVersion")
    check(version == 2 && json.sourceString("kind") == "stemwise.source-job")
    val plan = parseSourcePlan(json.getJSONObject("plan"))
    check(json.sourceString("planSha256") == mvsepSourcePlanSha256(plan))
    val ids = json.getJSONArray("remoteJobIds")
    check(ids.length() == plan.operations.size)
    PortableSourceJobAuthority(
        json.sourceString("libraryId"), json.sourceString("videoId"), json.sourceString("sourceVersionId"),
        json.sourceString("sourceSha256"), json.sourceString("jobId"), plan,
        json.sourceLong("generation"), json.sourceLong("revision"), json.sourceString("state"),
        List(ids.length()) { index ->
            val value = ids.get(index)
            check(value == JSONObject.NULL || value is String)
            if (value == JSONObject.NULL) null else value as String
        }, json.sourceInt("activeOperationIndex"), json.sourceInt("attempts"),
        json.sourceLong("createdAtEpochMs"), json.sourceLong("updatedAtEpochMs"),
        json.getJSONObject("assets").let { declared ->
            declared.keys().asSequence().associateWith { parseSourceAsset(declared.getJSONObject(it), version) }
        }, version
    ).also { check(it.toBytes().contentEquals(bytes)) }
} catch (error: Exception) {
    throw IllegalStateException(PORTABLE_SOURCE_JOB_INVALID, error)
}

internal fun selectPortableSourceJobAuthority(
    canonical: ByteArray?,
    temporary: ByteArray?,
    authenticatedAuthority: PortableSourceJobAuthority? = null
): PortableSourceJobAuthority? {
    if (canonical == null && temporary == null && authenticatedAuthority == null) return null
    val copies = listOfNotNull(canonical, temporary)
    val parsed = copies.map { it to runCatching { parsePortableSourceJobAuthority(it) } }
    val candidates = (parsed.mapNotNull { it.second.getOrNull() } + listOfNotNull(authenticatedAuthority)).distinct()
    check(candidates.isNotEmpty()) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
    authenticatedAuthority?.let { check(parsePortableSourceJobAuthority(it.toBytes()) == it) }
    parsed.filter { it.second.isFailure }.forEach { (bytes, _) ->
        check(interruptedReservedAuthorityWriteIsRecoverable(
            bytes, candidates.map(PortableSourceJobAuthority::toBytes), authorityAuthenticated = true
        )) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
    }
    val ordered = candidates.sortedBy(PortableSourceJobAuthority::revision)
    check(ordered.all(ordered.first()::sameIdentity)) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
    ordered.zipWithNext().forEach { (previous, next) ->
        check(previous.revision < MAX_SAFE_INTEGER && next.revision == previous.revision + 1L &&
            (SourceJobTransition.entries.any { transition ->
                runCatching {
                    transitionPortableSourceJob(previous, transition, next.updatedAtEpochMs,
                        next.currentRemoteJobId.takeIf { transition in setOf(SourceJobTransition.ACKNOWLEDGE_REMOTE, SourceJobTransition.RECOVER_REMOTE) }) == next
                }.getOrDefault(false)
            } || next.assets.size == previous.assets.size + 1 &&
                next.assets.keys.containsAll(previous.assets.keys) &&
                runCatching {
                    val added = (next.assets.keys - previous.assets.keys).single()
                    recordPortableSourceAsset(previous, next.assets.getValue(added), next.updatedAtEpochMs) == next
                }.getOrDefault(false))) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
    }
    return ordered.last()
}

private fun sourcePlanJson(plan: MvsepSourcePlan): JSONObject = JSONObject()
    .put("selectedSourceIds", JSONArray(plan.selectedSourceIds))
    .put("operations", JSONArray().apply {
        plan.operations.forEach { operation ->
            put(JSONObject().put("configurationId", operation.configurationId).put("renderId", operation.renderId)
                .put("options", JSONObject(operation.options)).put("expectedRoles", JSONArray(operation.expectedRoles.sorted()))
                .put("outputBindings", JSONObject(operation.outputBindings)))
        }
    })

private fun sourceAssetJson(asset: CustomSourceAsset): JSONObject = JSONObject()
    .put("sourceId", asset.sourceId).put("label", asset.label).put("fileName", asset.fileName)
    .put("sha256", asset.sha256).put("byteLength", asset.byteLength).put("audibleFrames", asset.audibleFrames)
    .apply {
        asset.remainingProducer?.let { put("remainingProducer", it) }
        asset.timingEvidence?.let { put("timingEvidence", it.toJson()) }
    }

private fun parseSourceAsset(json: JSONObject, schemaVersion: Int): CustomSourceAsset {
    val markedRemaining = schemaVersion == 2 && json.sourceString("sourceId") == "remaining"
    val keys = setOf("sourceId", "label", "fileName", "sha256", "byteLength", "audibleFrames")
    val expected = if (markedRemaining) keys + "remainingProducer" else keys
    check(json.keys().asSequence().toSet() == if (json.has("timingEvidence")) expected + "timingEvidence" else expected)
    return CustomSourceAsset(json.sourceString("sourceId"), json.sourceString("label"), json.sourceString("fileName"),
        json.sourceString("sha256"), json.sourceLong("byteLength"), json.sourceLong("audibleFrames"),
        if (markedRemaining) json.sourceString("remainingProducer") else null,
        if (json.has("timingEvidence")) SourceTimingEvidence.parse(json.getJSONObject("timingEvidence")) else null)
}

internal fun sourceTimingEvidence(authority: PortableSourceJobAuthority, asset: CustomSourceAsset,
    original: OriginalAudioFacts, raw: SourceTimingRawArtifact?, conversionProfile: String): SourceTimingEvidence {
    val index = authority.plan.operations.indexOfFirst { asset.sourceId in it.outputBindings }
    check(index >= 0) { SOURCE_TIMING_EVIDENCE_INVALID }
    val operation = authority.plan.operations[index]
    return SourceTimingEvidence(authority.sourceVersionId, authority.sourceSha256, authority.planSha256,
        authority.jobId, authority.generation, operation.configurationId, operation.renderId, operation.options,
        asset.sourceId, operation.outputBindings.getValue(asset.sourceId), checkNotNull(authority.remoteJobIds[index]),
        original.sampleRateHz, original.channels, original.audibleFrames48k, original.audibleFramesNative,
        original.encodedFramesNative, original.preSkipFrames, original.endTrimFrames, original.initialGranuleOffset, raw, asset.sha256,
        asset.byteLength, asset.audibleFrames, conversionProfile)
}

/** Serialize the largest future receipts before accepting any remote preparation. */
internal fun requireSourceManifestCapacity(authority: PortableSourceJobAuthority, original: OriginalAudioFacts) {
    val ids = authority.plan.selectedSourceIds
    val lanes = ids.map { id ->
        val operation = authority.plan.operations.first { id in it.outputBindings }
        val asset = CustomSourceAsset(id, checkNotNull(StemSourceCatalogue.source(id)).label,
            sourcePartFileName(authority.generation, ids, id), "f".repeat(64), MAX_SAFE_INTEGER, original.audibleFrames48k)
        asset.copy(timingEvidence = SourceTimingEvidence(authority.sourceVersionId, authority.sourceSha256,
            authority.planSha256, authority.jobId, authority.generation, operation.configurationId,
            operation.renderId, operation.options, id, operation.outputBindings.getValue(id), "界".repeat(512),
            original.sampleRateHz, original.channels, original.audibleFrames48k, original.audibleFramesNative,
            original.encodedFramesNative, original.preSkipFrames, original.endTrimFrames, original.initialGranuleOffset,
            SourceTimingRawArtifact("f".repeat(64), MAX_SAFE_INTEGER, 48_000, 2, original.audibleFrames48k),
            asset.sha256, asset.byteLength, asset.audibleFrames, app.stemwise.data.SOURCE_TIMING_CONVERSION_PROFILE))
    } + CustomSourceAsset("remaining", "Remaining", sourcePartFileName(authority.generation, ids, "remaining"),
        "f".repeat(64), MAX_SAFE_INTEGER, original.audibleFrames48k)
    app.stemwise.data.CustomSourceManifest(authority.videoId, authority.sourceSha256, authority.planSha256,
        ids, original.audibleFrames48k, lanes).toBytes()
}

/** Missing raw observations remain unknown; retained metadata cannot grant a waveform claim. */
internal fun requirePortableSourceTimingBinding(authority: PortableSourceJobAuthority, asset: CustomSourceAsset) {
    asset.timingEvidence?.let { evidence ->
        val index = authority.plan.operations.indexOfFirst { asset.sourceId in it.outputBindings }
        check(index >= 0) { SOURCE_TIMING_EVIDENCE_INVALID }
        val operation = authority.plan.operations[index]
        check(evidence.sourceVersionId == authority.sourceVersionId && evidence.sourceSha256 == authority.sourceSha256 &&
            evidence.planSha256 == authority.planSha256 && evidence.jobId == authority.jobId &&
            evidence.generation == authority.generation && evidence.configurationId == operation.configurationId &&
            evidence.renderId == operation.renderId && evidence.options == operation.options &&
            evidence.selectedSourceId == asset.sourceId && evidence.providerRole == operation.outputBindings[asset.sourceId] &&
            evidence.remoteJobId == authority.remoteJobIds[index]) { SOURCE_TIMING_EVIDENCE_INVALID }
    }
}

private fun parseSourcePlan(json: JSONObject): MvsepSourcePlan {
    check(json.keys().asSequence().toSet() == setOf("selectedSourceIds", "operations"))
    val selected = json.getJSONArray("selectedSourceIds").sourceStrings()
    val operations = json.getJSONArray("operations")
    check(operations.length() in 1..StemSourceCatalogue.MAX_TARGETS)
    val plan = MvsepSourcePlan(selected, List(operations.length()) { index ->
        val operation = operations.getJSONObject(index)
        check(operation.keys().asSequence().toSet() == OPERATION_KEYS)
        val roles = operation.getJSONArray("expectedRoles").sourceStrings()
        check(roles == roles.distinct().sorted())
        MvsepSourceOperation(operation.sourceString("configurationId"), operation.sourceInt("renderId"),
            operation.getJSONObject("options").sourceStringMap(), roles.toSet(),
            operation.getJSONObject("outputBindings").sourceStringMap())
    })
    MvsepSourcePlanner.requirePlanContract(plan)
    return plan
}

private fun JSONArray.sourceStrings(): List<String> {
    check(length() in 1..128)
    return List(length()) { index -> check(opt(index) is String); getString(index) }
}
private fun JSONObject.sourceStringMap(): Map<String, String> = keys().asSequence().associateWith(::sourceString)
private fun JSONObject.sourceString(key: String): String { check(opt(key) is String); return getString(key) }
private fun JSONObject.sourceLong(key: String): Long {
    val value = opt(key); check(value is Int || value is Long); return (value as Number).toLong()
}
private fun JSONObject.sourceInt(key: String): Int {
    val value = sourceLong(key); check(value in Int.MIN_VALUE..Int.MAX_VALUE); return value.toInt()
}
private fun validRemoteId(value: String): Boolean =
    value.isNotBlank() && value == value.trim() && value.length <= 512 && value.none(Char::isISOControl)

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private val SHA256 = Regex("^[a-f0-9]{64}$")
private val PENDING_STATES = setOf("CREATED", "SUBMISSION_STARTED", "SUBMISSION_UNKNOWN", "SUBMISSION_REJECTED")
private val FINAL_STATES = setOf("ASSEMBLING", "PUBLISHING", "COMPLETE")
private val STATES = PENDING_STATES + FINAL_STATES + setOf("PROCESSING", "DOWNLOADING", "REMOTE_FAILED", "DISCARDED")
private val AUTHORITY_KEYS = setOf("schemaVersion", "kind", "libraryId", "videoId", "sourceVersionId", "sourceSha256",
    "jobId", "planSha256", "plan", "generation", "revision", "state", "remoteJobIds", "activeOperationIndex",
    "attempts", "createdAtEpochMs", "updatedAtEpochMs", "assets", "integritySha256")
private val OPERATION_KEYS = setOf("configurationId", "renderId", "options", "expectedRoles", "outputBindings")

/** Replay private current-operation authority before any caller parses its possibly torn sidecars. */
internal data class SourceAuthorityRecoveryIssue(val videoId: String, val code: String)

internal suspend fun replayJournaledSourceAuthorities(
    root: app.stemwise.data.RootAuthority,
    records: List<app.stemwise.data.AtomicJournalRecord>,
    publish: suspend (PortableSourceJobAuthority) -> Unit
): List<SourceAuthorityRecoveryIssue> {
    val issues = mutableListOf<SourceAuthorityRecoveryIssue>()
    records.forEach { record ->
        val envelope = app.stemwise.data.requireAtomicJournalEnvelope(record, PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED)
        if (envelope.operation == "SOURCE_JOB_AUTHORITY_V1" && envelope.libraryId == root.libraryId &&
            envelope.treeUri == root.treeUri && envelope.rootEpoch == root.epoch &&
            envelope.state == "AUTHORITY_WRITING") {
            check(app.stemwise.data.SelectedLibraryContract.isVideoId(envelope.packageId)) {
                PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
            }
            try {
                val bytes = (envelope.details.opt("authorityJson") as? String)
                    ?.toByteArray(Charsets.UTF_8) ?: error(PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED)
                val authority = parsePortableSourceJobAuthority(bytes)
                check(authority.libraryId == root.libraryId && authority.videoId == envelope.packageId &&
                    envelope.transactionId == "source-${authority.jobId}") { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
                publish(authority)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                app.stemwise.data.canonicalProjectionRootAccessFailure(error)?.let { throw it }
                issues += SourceAuthorityRecoveryIssue(envelope.packageId, PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED)
            }
        }
    }
    return issues
}
