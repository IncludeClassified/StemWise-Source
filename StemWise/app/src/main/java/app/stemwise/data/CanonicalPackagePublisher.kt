package app.stemwise.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.stemwise.integration.withCancellableProviderOutput
import app.stemwise.integration.withCancellableProviderInput
import app.stemwise.integration.runCancellableBlockingOperation
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.suspendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Byte-preserving publisher for the flat selected-folder contract.
 * Private precreate intent owns a unique staging child until verified bytes can be promoted.
 */
class CanonicalPackagePublisher(
    private val context: Context,
    private val roots: LibraryRootManager,
    private val dao: LibraryDao,
    private val mutations: PackageMutationCoordinator,
    private val sourceBindings: SourceBindingRegistry,
    private val externalLibrary: ExternalLibraryRepository,
    private val backgroundWorkCoordinator: BackgroundWorkCoordinator
) {
    @Volatile internal var assetCreatedBeforeJournalForTest: (() -> Unit)? = null
    @Volatile internal var assetPromotedBeforeJournalForTest: (() -> Unit)? = null
    @Volatile internal var copyProgressForTest: ((Long) -> Unit)? = null
    @Volatile internal var sourceManifestWrittenForTest: (() -> Unit)? = null
    @Volatile internal var sourceLaneDeletedForTest: (() -> Unit)? = null
    @Volatile internal var beforeDirectoryBindingForTest: (suspend () -> Unit)? = null

    internal suspend fun verifyPublishedSong(root: RootHandle, songId: String, originalSha256: String) {
        val song = externalLibrary.verifyPlaybackSong(root, songId)
        check(song.sourceHash == originalSha256) { "BUNDLE_READBACK_FAILED" }
        roots.requireSameLibrary(root)
    }

    suspend fun publishOriginal(
        expectedRoot: RootHandle,
        songId: String,
        privateFile: File,
        expectedSha256: String,
        beforeOriginalPublication: suspend () -> Unit = {}
    ): Result<Uri> = publishAsset(
        expectedRoot = expectedRoot,
        songId = songId,
        fileName = "original.${privateFile.extension}",
        privateFile = privateFile,
        expectedSha256 = expectedSha256,
        mime = if (privateFile.extension == "flac") "audio/flac" else "audio/ogg",
        measuredNextAssetBytes = privateFile.length().coerceAtMost(MAX_NEXT_ASSET_RESERVE_BYTES),
        beforeCommit = beforeOriginalPublication
    )

    /** Publishes one real selected target or Remaining, without pretending a partial package is ready. */
    internal suspend fun publishSourcePart(
        expectedRoot: RootHandle,
        songId: String,
        fileName: String,
        privateFile: File,
        expectedSha256: String,
        operationOwnerId: String,
        sourceSha256: String,
        audibleFrames: Long,
        measuredNextAssetBytes: Long = 0L
    ): Result<Uri> {
        require(fileName in SelectedLibraryContract.customStemFiles && privateFile.extension == fileName.substringAfterLast(".")) {
            "ASSET_PATH_INVALID"
        }
        require(OPERATION_OWNER_ID.matches(operationOwnerId) && SHA256.matches(sourceSha256)) {
            "PUBLICATION_OWNER_INVALID"
        }
        val inspectionContext = currentCoroutineContext()
        val audio = privateFile.inputStream().use {
            inspectPreparedSourceAudio(it, privateFile.length()) { inspectionContext.ensureActive() }
        }
        check(audio.channels == 2 && audio.sampleRate == 48_000 && audio.audibleFrames == audibleFrames &&
            audio.isFloat == fileName.endsWith(".wv") &&
            canonicalMediaMimeMatches(fileName, audio.mime)) {
            CUSTOM_SOURCE_MANIFEST_INVALID
        }
        return publishAsset(expectedRoot, songId, fileName, privateFile, expectedSha256, audio.mime,
            measuredNextAssetBytes, operationOwnerId,
            beforePublication = { snapshot ->
                requireSourceIdentity(expectedRoot, songId, snapshot, sourceSha256, audibleFrames, reopenOriginal = false)
            })
    }

    /** Recovers the publication-before-receipt crash window without another separation or encode. */
    internal suspend fun recoverPublishedSourcePart(
        expectedRoot: RootHandle,
        songId: String,
        fileName: String,
        operationOwnerId: String
    ): ProviderDocumentIdentity? = withContext(Dispatchers.IO) {
        require(fileName in SelectedLibraryContract.customStemFiles && OPERATION_OWNER_ID.matches(operationOwnerId)) {
            "PUBLICATION_OWNER_INVALID"
        }
        var partial: PublicationJournalRecord? = null
        val recovered = mutations.withRootLease({ roots.requireSameLibrary(expectedRoot) }) {
            mutations.withPackageRead(expectedRoot, songId) {
                roots.requireSameLibrary(expectedRoot)
                val snapshot = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId)
                    ?: error("PACKAGE_NOT_FOUND")
                val records = publicationJournalRecords(songId).filter { record ->
                    record.matches(expectedRoot) && record.fileName == fileName && record.operationOwnerId == operationOwnerId
                }
                val existing = snapshot.file(fileName)
                if (existing == null && (records.isEmpty() || records.all { it.stagingName != null })) return@withPackageRead null
                val record = records.filter {
                    it.sourceRecoveryClaim() != null || it.state == "STAGING_VERIFIED" &&
                        it.createdByOperation && it.stagingName != null &&
                        SHA256.matches(it.expectedSha256) && it.byteLength > 0L
                }.singleOrNull() ?: error("SOURCE_PART_RECOVERY_REQUIRED")
                if (record.state == "STAGING_VERIFIED") {
                    partial = record
                    return@withPackageRead null
                }
                val claim = requireNotNull(record.sourceRecoveryClaim())
                if (existing == null) {
                    check(record.state == "PRUNING_PARTIAL_SOURCE") { "SOURCE_PART_RECOVERY_REQUIRED" }
                    partial = record
                    return@withPackageRead null
                }
                val measured = measureSourceRecoveryDocument(expectedRoot, existing, claim.byteLength)
                if (ownedSourcePartRecoveryAllowed(operationOwnerId, fileName, existing.uri.toString(),
                    existing.documentId, measured, claim)) return@withPackageRead measured
                check(ownedUnverifiedSourcePartCleanupAllowed(operationOwnerId, fileName, existing.uri.toString(),
                    existing.documentId, record.state, record.createdByOperation, measured, claim)) {
                    "SOURCE_PART_RECOVERY_REQUIRED"
                }
                partial = record
                null
            }
        }
        val record = partial ?: return@withContext recovered
        mutations.withPackageMutation(expectedRoot, songId, "FLAT_ASSET_PUBLISH",
            record.file.name.removeSuffix(".json"), requireExactRoot = { roots.requireSameLibrary(expectedRoot) }, holdRootLock = false) { journal ->
            val current = publicationJournalRecords(songId).singleOrNull { it.file == record.file }
                ?: error("SOURCE_PART_RECOVERY_REQUIRED")
            check(current == record) { "SOURCE_PART_RECOVERY_REQUIRED" }
            val snapshot = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId) ?: error("PACKAGE_NOT_FOUND")
            val existing = snapshot.file(fileName)
            if (record.state == "STAGING_VERIFIED") {
                check(existing != null && snapshot.file(checkNotNull(record.stagingName)) == null) {
                    "SOURCE_PART_RECOVERY_REQUIRED"
                }
                val measured = measureSourceRecoveryDocument(expectedRoot, existing, record.byteLength)
                check(measured.sha256 == record.expectedSha256 && measured.byteLength == record.byteLength) {
                    "SOURCE_PART_RECOVERY_REQUIRED"
                }
                roots.requireSameLibrary(expectedRoot)
                journal.write("TARGET_ACQUIRED", JSONObject().put("targetUri", existing.uri.toString())
                    .put("targetDocumentId", existing.documentId))
                journal.write("VERIFIED")
                journal.complete(JSONObject().put("result", "PUBLISHED"), retainForRecovery = true)
                return@withPackageMutation measured
            }
            if (existing != null) {
                val measured = measureSourceRecoveryDocument(expectedRoot, existing, record.byteLength)
                check(ownedUnverifiedSourcePartCleanupAllowed(operationOwnerId, fileName, existing.uri.toString(),
                    existing.documentId, record.state, record.createdByOperation, measured, record.sourceRecoveryClaim())) {
                    "SOURCE_PART_RECOVERY_REQUIRED"
                }
                // Re-enter only this unverified publication. Its exact target facts survive
                // deletion until the exact publication journal has been retired.
                journal.write("PRUNING_PARTIAL_SOURCE")
                roots.requireSameLibrary(expectedRoot)
                check(existing.document.delete()) { "EXTERNAL_COMMIT_FAILED" }
            } else check(record.state == "PRUNING_PARTIAL_SOURCE") { "SOURCE_PART_RECOVERY_REQUIRED" }
            roots.requireSameLibrary(expectedRoot)
            val after = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId) ?: error("PACKAGE_NOT_FOUND")
            check(after.file(fileName) == null && after.files.values.none {
                it.uri.toString() == record.targetUri || it.documentId == record.targetDocumentId
            }) { "EXTERNAL_COMMIT_FAILED" }
            journal.complete(JSONObject().put("result", "PARTIAL_RETRY"))
            null
        }
    }

    /** Terminal failed owners may retire only their unpublished bank and exact retained receipts. */
    internal suspend fun discardFailedSourceParts(
        expectedRoot: RootHandle,
        songId: String,
        sourceSha256: String,
        fileNames: Set<String>,
        assets: Collection<CustomSourceAsset>,
        operationOwnerIds: Set<String>
    ) = mutations.withRootLease({ roots.requireSameLibrary(expectedRoot) }) {
        mutations.withPackageRead(expectedRoot, songId) {
            roots.requireSameLibrary(expectedRoot)
            check(fileNames.all { it in SelectedLibraryContract.customStemFiles } &&
                operationOwnerIds.isNotEmpty() && operationOwnerIds.all(OPERATION_OWNER_ID::matches)) { "PUBLICATION_OWNER_INVALID" }
            val manifest = roots.readExactSongSidecars(expectedRoot, songId,
                setOf(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST), CUSTOM_SOURCE_MANIFEST_MAX_BYTES)
                .get(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST)?.let { parseCustomSourceManifest(it, songId, sourceSha256) }
            check(manifest?.lanes.orEmpty().none { it.fileName in fileNames }) { "SOURCE_REPLACEMENT_CLEANUP_REFUSED" }
            val records = publicationJournalRecords(songId).filter {
                it.matches(expectedRoot) && it.operationOwnerId in operationOwnerIds
            }
            check(records.all { it.fileName in fileNames }) { "SOURCE_PART_RECOVERY_REQUIRED" }
            val receipts = assets.associateBy(CustomSourceAsset::fileName)
            val names = fileNames + records.mapNotNull(PublicationJournalRecord::stagingName)
            for (name in names) {
                val current = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId) ?: error("PACKAGE_NOT_FOUND")
                val observed = current.file(name) ?: continue
                val receipt = receipts[name]
                val record = records.firstOrNull {
                    it.stagingName == name || it.fileName == name && it.createdByOperation
                }
                if (receipt != null) {
                    val measured = measureSelectedLibraryDocument(context, observed) { roots.requireSameLibrary(expectedRoot) }
                    check(measured.sha256 == receipt.sha256 && measured.byteLength == receipt.byteLength) { "SOURCE_PART_RECOVERY_REQUIRED" }
                } else {
                    check(record != null && record.byteLength > 0L && (record.createdByOperation ||
                        record.state == "STAGING_PREPARED" && record.stagingName == name)) { "SOURCE_PART_RECOVERY_REQUIRED" }
                    // A retained unique staging receipt also covers creation before the target-ID write.
                    check(observed.sizeBytes == null || observed.sizeBytes <= record.byteLength) { "SOURCE_PART_RECOVERY_REQUIRED" }
                    if (name == record.fileName) {
                        val measured = measureSelectedLibraryDocument(context, observed) { roots.requireSameLibrary(expectedRoot) }
                        check(measured.sha256 == record.expectedSha256 && measured.byteLength == record.byteLength) { "SOURCE_PART_RECOVERY_REQUIRED" }
                    }
                }
                check(observed.document.delete()) { "EXTERNAL_COMMIT_FAILED" }
                val after = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId) ?: error("PACKAGE_NOT_FOUND")
                check(after.file(name) == null && after.files.values.none { it.documentId == observed.documentId }) { "EXTERNAL_COMMIT_FAILED" }
            }
            deleteExactJournalRecords(records)
        }
    }

    /** Candidate lanes occupy the inactive bank; one validated manifest is the publication boundary. */
    internal suspend fun publishCustomSources(
        expectedRoot: RootHandle,
        songId: String,
        manifest: CustomSourceManifest,
        operationOwnerId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        suspendResult {
            check(manifest.videoId == songId && OPERATION_OWNER_ID.matches(operationOwnerId)) { "PUBLICATION_OWNER_INVALID" }
            mutations.withPackageMutation(expectedRoot, songId, "SOURCE_MANIFEST_COMMIT", "sources-$operationOwnerId",
                requireExactRoot = { roots.requireSameLibrary(expectedRoot) }, holdRootLock = false) { journal ->
                val cleanupCommitted = journal.wasRecoveredInState("CLEANUP")
                val snapshot = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId) ?: error("PACKAGE_NOT_FOUND")
                val original = checkNotNull(requireSourceIdentity(expectedRoot, songId, snapshot,
                    manifest.sourceSha256, manifest.audibleFrames))
                manifest.lanes.forEach { lane ->
                    requireSourceTimingOriginalBinding(lane, original)
                    val observed = snapshot.file(lane.fileName) ?: error(CUSTOM_SOURCE_MANIFEST_INVALID)
                    val identity = measureSelectedLibraryDocument(context, observed) {
                        roots.requireSameLibrary(expectedRoot)
                        backgroundWorkCoordinator.checkpoint()
                    }
                    validateCustomSourceAudioAsset(lane, identity.sha256, identity.byteLength,
                        inspectPublishedSourceAudio(observed, identity.byteLength))
                }
                val bytes = manifest.toBytes()
                val sidecars = roots.readExactSongSidecars(expectedRoot, songId,
                    setOf(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP),
                    CUSTOM_SOURCE_MANIFEST_MAX_BYTES)
                val canonical = sidecars[SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST]
                val backup = sidecars[SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP]
                val oldBytes = if (backup != null && canonical != null && !canonical.contentEquals(bytes) &&
                    runCatching { parseCustomSourceManifest(backup, songId, manifest.sourceSha256) }.isFailure) {
                    check(backup.size <= canonical.size && backup.contentEquals(canonical.copyOf(backup.size))) { "SOURCE_REPLACEMENT_CONFLICT" }
                    canonical
                } else backup ?: canonical?.takeUnless { it.contentEquals(bytes) }
                val oldManifest = oldBytes?.let { parseCustomSourceManifest(it, songId, manifest.sourceSha256) }
                check(oldManifest == null || oldManifest.lanes.map(CustomSourceAsset::fileName)
                    .intersect(manifest.lanes.map(CustomSourceAsset::fileName).toSet()).isEmpty()) { "SOURCE_REPLACEMENT_CONFLICT" }
                if (!cleanupCommitted) journal.write("COMMITTING", JSONObject().put("manifestJson", bytes.toString(Charsets.UTF_8)))
                if (oldBytes != null && (backup == null || !backup.contentEquals(oldBytes))) roots.writeExactSongSidecar(expectedRoot, songId,
                    SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP, "application/json", oldBytes,
                    CUSTOM_SOURCE_MANIFEST_MAX_BYTES)
                if (cleanupCommitted) {
                    check(canonical?.contentEquals(bytes) == true &&
                        journal.recoveredString("CLEANUP", "manifestJson") == bytes.toString(Charsets.UTF_8)) {
                        "SOURCE_REPLACEMENT_CONFLICT"
                    }
                } else {
                    try {
                        mutations.withRootMutation {
                            roots.requireSameLibrary(expectedRoot)
                            roots.writeExactSongSidecar(expectedRoot, songId, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST,
                                "application/json", bytes, CUSTOM_SOURCE_MANIFEST_MAX_BYTES)
                        }
                        sourceManifestWrittenForTest?.invoke()
                        val reopened = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId) ?: error("PACKAGE_NOT_FOUND")
                        validateCustomSourceManifestFiles(manifest, reopened.files.keys)
                        externalLibrary.reconcilePackageUnderPackageOwnership(expectedRoot, songId)
                        val published = externalLibrary.songs.value.singleOrNull { it.id == songId }
                        check(published?.hasStems == true && published.stemRevision.startsWith("${manifest.revisionSha256}|")) {
                            CUSTOM_SOURCE_MANIFEST_INVALID
                        }
                    } catch (failure: Throwable) {
                        if (oldBytes != null) {
                            try {
                                withContext(kotlinx.coroutines.NonCancellable) {
                                    mutations.withRootMutation {
                                        roots.requireSameLibrary(expectedRoot)
                                        roots.writeExactSongSidecar(expectedRoot, songId, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST,
                                            "application/json", oldBytes, CUSTOM_SOURCE_MANIFEST_MAX_BYTES)
                                    }
                                    externalLibrary.reconcilePackageUnderPackageOwnership(expectedRoot, songId)
                                }
                            } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                        }
                        throw failure
                    }
                    journal.write("CLEANUP", JSONObject().put("manifestJson", bytes.toString(Charsets.UTF_8)))
                }
                // A completed replacement is already usable before retiring the prior bank.
                oldManifest?.lanes?.forEach { old ->
                    val current = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId) ?: error("PACKAGE_NOT_FOUND")
                    current.file(old.fileName)?.let { observed ->
                        val measured = measureSelectedLibraryDocument(context, observed) { roots.requireSameLibrary(expectedRoot) }
                        check(measured.sha256 == old.sha256 && measured.byteLength == old.byteLength) { "SOURCE_REPLACEMENT_CLEANUP_REFUSED" }
                        check(observed.document.delete()) { "EXTERNAL_COMMIT_FAILED" }
                        val after = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId) ?: error("PACKAGE_NOT_FOUND")
                        check(after.file(old.fileName) == null && after.files.values.none {
                            it.documentId == observed.documentId
                        }) { "EXTERNAL_COMMIT_FAILED" }
                        sourceLaneDeletedForTest?.invoke()
                    }
                }
                roots.deleteExactSongSidecars(expectedRoot, songId, setOf(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP))
                deleteExactJournalRecords(publicationJournalRecords(songId).filter { record ->
                    record.matches(expectedRoot) && record.operationOwnerId in setOf(operationOwnerId,
                        "$operationOwnerId.stemwise-native-original48-v1") && record.fileName in manifest.lanes.map { it.fileName }
                })
                journal.complete(JSONObject().put("revisionSha256", manifest.revisionSha256))
            }
        }
    }

    private suspend fun requireSourceIdentity(
        expectedRoot: RootHandle,
        songId: String,
        snapshot: SelectedSongSnapshot,
        sourceSha256: String,
        audibleFrames: Long,
        reopenOriginal: Boolean = true
    ): OriginalAudioFacts? {
        roots.requireSameLibrary(expectedRoot)
        val source = snapshot.original ?: error("ORIGINAL_MISSING")
        val currentSource = dao.currentSource(songId)
        check(dao.findSongById(songId)?.highLevelState != "DELETING" &&
            currentSource?.sha256 == sourceSha256 && currentSource.localPath == source.uri.toString()) {
            "SEPARATION_SOURCE_CHANGED"
        }
        if (!reopenOriginal) return null
        val identity = measureSelectedLibraryDocument(context, source) {
            roots.requireSameLibrary(expectedRoot)
            backgroundWorkCoordinator.checkpoint()
        }
        val facts = inspectPublishedOriginal(source, identity.byteLength)
        check(identity.sha256 == sourceSha256 && facts.audibleFrames48k == audibleFrames) {
            "SEPARATION_SOURCE_CHANGED"
        }
        return facts
    }

    private suspend fun inspectPublishedOriginal(file: SelectedDocumentSnapshot, byteLength: Long): OriginalAudioFacts {
        val inspectionContext = currentCoroutineContext()
        return context.contentResolver.withCancellableProviderInput(file.uri) { input, _ ->
            runCancellableBlockingOperation(cancelExact = input::close) {
                inspectOriginalAudio(input, byteLength) { inspectionContext.ensureActive() }
            }
        } ?: error("ASSET_MISSING")
    }

    private suspend fun inspectPublishedSourceAudio(file: SelectedDocumentSnapshot, byteLength: Long): PreparedSourceAudioFacts {
        val inspectionContext = currentCoroutineContext()
        return context.contentResolver.withCancellableProviderInput(file.uri) { input, _ ->
            runCancellableBlockingOperation(cancelExact = input::close) {
                inspectPreparedSourceAudio(input, byteLength) { inspectionContext.ensureActive() }
            }
        } ?: error("ASSET_MISSING")
    }

    private suspend fun publishAsset(
        expectedRoot: RootHandle,
        songId: String,
        fileName: String,
        privateFile: File,
        expectedSha256: String,
        mime: String,
        measuredNextAssetBytes: Long,
        operationOwnerId: String? = null,
        beforePublication: suspend (SelectedSongSnapshot) -> Unit = {},
        beforeCommit: suspend () -> Unit = {}
    ): Result<Uri> = withContext(Dispatchers.IO) {
        suspendResult {
            roots.requireSameLibrary(expectedRoot)
            check(SelectedLibraryContract.isVideoId(songId)) { "PACKAGE_ID_INVALID" }
            check(fileName in SelectedLibraryContract.allMediaFiles ||
                fileName == SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST) { "ASSET_PATH_INVALID" }
            check(privateFile.isFile && privateFile.length() > 0L) { "ASSET_MISSING" }
            check(SHA256.matches(expectedSha256)) {
                "ASSET_HASH_MISMATCH"
            }
            val transactionId = "asset-${UUID.randomUUID()}"
            var preJournalRecoveredDirectoryUri: String? = null
            mutations.withPackageMutation(
                expectedRoot,
                songId,
                "FLAT_ASSET_PUBLISH",
                transactionId,
                requireExactRoot = { roots.requireSameLibrary(expectedRoot) },
                holdRootLock = false,
                beforeJournal = {
                    cleanupOperationJournals(
                        expectedRoot,
                        songId,
                        "FLAT_ASSET_PUBLISH",
                        preparedOnly = true
                    )
                    if (fileName in SelectedLibraryContract.originalFiles) {
                        preJournalRecoveredDirectoryUri =
                            reconcileCreatedDirectoryBeforeJournal(expectedRoot, songId)
                    }
                }
            ) { journal ->
                roots.requireSameLibrary(expectedRoot)
                mutations.withRootMutation {
                    check(
                        canonicalAssetPublicationAllowed(
                            songId,
                            sourceBindings.blockedSourceIds(expectedRoot)
                        )
                    ) {
                        "SOURCE_DELETED"
                    }
                }
                roots.requireSameLibrary(expectedRoot)
                val songSnapshot = resolvePublicationDirectory(
                    expectedRoot = expectedRoot,
                    songId = songId,
                    fileName = fileName,
                    journal = journal,
                    currentTransactionId = transactionId,
                    preJournalRecoveredDirectoryUri = preJournalRecoveredDirectoryUri
                )
                roots.requireSameLibrary(expectedRoot)
                beforePublication(songSnapshot)
                if (fileName in SelectedLibraryContract.originalFiles) {
                    check(songSnapshot.original == null || songSnapshot.original?.displayName == fileName) {
                        "ORIGINAL_CONFLICT"
                    }
                }
                if (fileName !in SelectedLibraryContract.originalFiles) {
                    check(songSnapshot.original != null) {
                        "ORIGINAL_MISSING"
                    }
                }
                val pendingMatches = publicationJournalRecords(songId).any {
                    it.matches(expectedRoot) && it.fileName == fileName &&
                        it.operationOwnerId == operationOwnerId.orEmpty() &&
                        it.expectedSha256 == expectedSha256 && it.byteLength == privateFile.length()
                }
                var ownedOriginalRecovery: PublicationJournalRecord? = null
                var stagingName: String? = null
                var target = songSnapshot.file(fileName)?.document
                val existingTarget = target
                if (existingTarget != null) {
                    val measured = suspendResult {
                        measureDocument(expectedRoot, existingTarget, privateFile.length())
                    }.getOrElse { error ->
                        if (error is java.io.IOException || error is SecurityException || error is LibraryRootException) throw error
                        null
                    }
                    if (
                        measured != null && measured.byteLength == privateFile.length() &&
                        measured.sha256 == expectedSha256
                    ) {
                        roots.requireSameLibrary(expectedRoot)
                        beforeCommit()
                        val pendingStaging = publicationJournalRecords(songId).filter {
                            it.matches(expectedRoot) && it.fileName == fileName && it.stagingName != null &&
                                it.state != "COMPLETE" && it.expectedSha256 == expectedSha256 &&
                                it.byteLength == privateFile.length() && it.operationOwnerId == operationOwnerId.orEmpty()
                        }
                        val resumedStaging = pendingStaging.isNotEmpty()
                        if (resumedStaging) {
                            // Equal external bytes alone do not transfer staging ownership. Only a
                            // verified staging child that disappeared into its intended final name
                            // can complete the publication-before-receipt interruption.
                            val retainedStagingName = pendingStaging.map { it.stagingName }.distinct().singleOrNull()
                            check(retainedStagingName != null && songSnapshot.file(retainedStagingName) == null &&
                                pendingStaging.any { it.state == "STAGING_VERIFIED" && it.createdByOperation }) {
                                "SOURCE_PART_RECOVERY_REQUIRED"
                            }
                        }
                        if (resumedStaging) journal.write("TARGET_ACQUIRED", JSONObject()
                            .put("fileName", fileName).put("sha256", expectedSha256).put("byteLength", privateFile.length())
                            .put("targetUri", existingTarget.uri.toString())
                            .put("targetDocumentId", DocumentsContract.getDocumentId(existingTarget.uri))
                            .put("createdByOperation", true)
                            .apply { operationOwnerId?.let { put("operationOwnerId", it) } })
                        check(privateFile.delete()) { "PRIVATE_CLEANUP_FAILED" }
                        cleanupRecoveredAssetJournals(
                            expectedRoot,
                            songId,
                            fileName,
                            transactionId,
                            operationOwnerId
                        )
                        journal.complete(
                            JSONObject()
                                .put("fileName", fileName)
                                .put("result", if (resumedStaging) "PUBLISHED" else "ALREADY_VERIFIED"),
                            retainForRecovery = resumedStaging && operationOwnerId != null
                        )
                        return@withPackageMutation existingTarget.uri
                    }
                }
                if (target != null && fileName in SelectedLibraryContract.customStemFiles) {
                    // A canonical source is immutable. A changed final child is never a staging
                    // retry target, even while this operation still has a pending publication journal.
                    error("SOURCE_PART_RECOVERY_REQUIRED")
                }
                if (target != null && fileName in SelectedLibraryContract.originalFiles &&
                    (dao.currentSource(songId) != null ||
                        songSnapshot.file(SelectedLibraryContract.ENRICHMENT) != null ||
                        songSnapshot.inspection.hasCustomSourceMedia)) {
                    error("ORIGINAL_CONFLICT")
                }
                if (target != null && fileName in SelectedLibraryContract.originalFiles) {
                    val exactTarget = target
                    check(pendingMatches) { "ORIGINAL_CONFLICT" }
                    val observed = measureSourceRecoveryDocument(expectedRoot,
                        checkNotNull(songSnapshot.file(fileName)), privateFile.length())
                    ownedOriginalRecovery = publicationJournalRecords(songId).firstOrNull {
                        it.matches(expectedRoot) && it.fileName == fileName &&
                            it.expectedSha256 == expectedSha256 && it.byteLength == privateFile.length() &&
                            it.createdByOperation && it.targetUri == exactTarget.uri.toString() &&
                            it.targetDocumentId == DocumentsContract.getDocumentId(exactTarget.uri) &&
                            observed.sha256 == sha256Prefix(privateFile, observed.byteLength)
                    }
                    check(ownedOriginalRecovery != null) { "ORIGINAL_CONFLICT" }
                }
                check(target == null || pendingMatches) { "CANONICAL_ASSET_CONFLICT" }
                requireImportStorageCapacity(providerImportAvailableBytes(context, expectedRoot.treeUri),
                    Math.addExact(privateFile.length(), measuredNextAssetBytes), "LIBRARY_STORAGE_LOW")
                beforeCommit()
                journal.write(
                    "COPYING",
                    JSONObject()
                        .put("fileName", fileName)
                        .put("sha256", expectedSha256)
                        .put("byteLength", privateFile.length())
                        .put("measuredNextAssetReserveBytes", measuredNextAssetBytes)
                        .apply {
                            operationOwnerId?.let { put("operationOwnerId", it) }
                        }
                )
                val createdByOperation = target == null
                if (createdByOperation) {
                    val prior = publicationJournalRecords(songId).filter {
                        it.matches(expectedRoot) && it.fileName == fileName && it.expectedSha256 == expectedSha256 &&
                            it.byteLength == privateFile.length() && it.operationOwnerId == operationOwnerId.orEmpty() &&
                            it.stagingName != null
                    }.mapNotNull { it.stagingName }.distinct()
                    check(prior.size <= 1) { "SOURCE_PART_RECOVERY_REQUIRED" }
                    stagingName = prior.singleOrNull() ?: ".stemwise-asset-${UUID.randomUUID().toString().replace("-", "")}-$fileName"
                    journal.write("STAGING_PREPARED", JSONObject().put("stagingName", stagingName))
                    val exact = roots.checkedBoundSongSnapshot(expectedRoot, songId, songSnapshot.directory.uri.toString())
                    val retained = exact.file(checkNotNull(stagingName))
                    if (retained != null) {
                        val prefix = measureSourceRecoveryDocument(expectedRoot, retained, privateFile.length())
                        check(prefix.sha256 == sha256Prefix(privateFile, prefix.byteLength)) {
                            if (fileName in SelectedLibraryContract.originalFiles) "ORIGINAL_CONFLICT" else "SOURCE_PART_RECOVERY_REQUIRED"
                        }
                    }
                    target = retained?.document ?: roots.createExactSongChild(
                        expectedRoot, exact, mime, checkNotNull(stagingName)).document.also {
                            assetCreatedBeforeJournalForTest?.invoke()
                        }
                }
                journal.write(
                    "TARGET_ACQUIRED",
                    JSONObject()
                        .put("targetUri", requireNotNull(target).uri.toString())
                        .put(
                            "targetDocumentId",
                            DocumentsContract.getDocumentId(requireNotNull(target).uri)
                        )
                        .put("createdByOperation", createdByOperation || ownedOriginalRecovery != null)
                        .apply {
                            ownedOriginalRecovery?.let { prior ->
                                put("partialSha256", prior.partialSha256)
                                put("partialByteLength", prior.partialByteLength)
                            }
                            if (createdByOperation && fileName in SelectedLibraryContract.originalFiles) {
                                put("partialSha256", MessageDigest.getInstance("SHA-256").digest().toHexString())
                                put("partialByteLength", 0L)
                            }
                        }
                )
                try {
                    copyAndVerify(expectedRoot, privateFile, requireNotNull(target), expectedSha256)
                } catch (failure: Throwable) {
                    if (stagingName == null && fileName in SelectedLibraryContract.originalFiles) {
                        try {
                            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                                val current = checkNotNull(roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId)?.file(fileName))
                                check(current.uri == requireNotNull(target).uri) { "ORIGINAL_CONFLICT" }
                                val partial = measureSourceRecoveryDocument(expectedRoot, current, privateFile.length())
                                check(partial.sha256 == sha256Prefix(privateFile, partial.byteLength)) { "ORIGINAL_CONFLICT" }
                                journal.write("PARTIAL_WRITTEN", JSONObject()
                                    .put("partialSha256", partial.sha256).put("partialByteLength", partial.byteLength))
                            }
                        } catch (settlement: Throwable) { failure.addSuppressed(settlement) }
                    }
                    throw failure
                }
                if (stagingName != null) {
                    journal.write("STAGING_VERIFIED")
                    target = roots.promotePublicationStagingChild(expectedRoot, songSnapshot,
                        checkNotNull(stagingName), checkNotNull(target).uri, fileName)
                    assetPromotedBeforeJournalForTest?.invoke()
                    journal.write("TARGET_ACQUIRED", JSONObject().put("targetUri", checkNotNull(target).uri.toString())
                        .put("targetDocumentId", DocumentsContract.getDocumentId(checkNotNull(target).uri)))
                    val measured = measureDocument(expectedRoot, checkNotNull(target), privateFile.length())
                    check(measured.sha256 == expectedSha256 && measured.byteLength == privateFile.length()) { "ASSET_HASH_MISMATCH" }
                }
                journal.write("VERIFIED", JSONObject().put("fileName", fileName))
                roots.requireSameLibrary(expectedRoot)
                check(privateFile.delete()) { "PRIVATE_CLEANUP_FAILED" }
                cleanupRecoveredAssetJournals(expectedRoot, songId, fileName, transactionId, operationOwnerId)
                roots.requireSameLibrary(expectedRoot)
                journal.complete(
                    JSONObject()
                        .put("fileName", fileName)
                        .put("uri", requireNotNull(target).uri.toString())
                        .put("result", if (createdByOperation) "PUBLISHED" else "RECOVERED")
                        .apply {
                            operationOwnerId?.let { put("operationOwnerId", it) }
                        },
                    retainForRecovery = createdByOperation && operationOwnerId != null
                )
                requireNotNull(target).uri
            }
        }
    }

    /**
     * Stem publication is reopen-only. Original publication may create exactly one directory,
     * but only after durable URI/source facts and exact-root recovery evidence prove this is the
     * first publication. Every returned provider identity is journaled before it is inspected.
     */
    private suspend fun resolvePublicationDirectory(
        expectedRoot: RootHandle,
        songId: String,
        fileName: String,
        journal: PackageMutationCoordinator.Journal,
        currentTransactionId: String,
        preJournalRecoveredDirectoryUri: String?
    ): SelectedSongSnapshot {
        roots.requireSameLibrary(expectedRoot)
        val song = dao.findSongById(songId)
        check(song != null || fileName in SelectedLibraryContract.originalFiles) { "PACKAGE_NOT_FOUND" }
        song?.packageDocumentUri?.takeIf(String::isNotBlank)?.let { durableUri ->
            return roots.checkedBoundSongSnapshot(expectedRoot, songId, durableUri)
        }

        if (fileName !in SelectedLibraryContract.originalFiles) {
            throw LibraryRootException(
                "PACKAGE_DIRECTORY_IDENTITY_UNAVAILABLE",
                "Stem publication requires the durable exact song directory"
            )
        }

        (preJournalRecoveredDirectoryUri ?: recoverCreatedDirectoryUri(
            expectedRoot,
            songId,
            currentTransactionId
        ))?.let { recoveryUri ->
            val recovered = roots.checkedBoundSongSnapshot(
                expectedRoot,
                songId,
                recoveryUri
            )
            persistPackageDirectoryIdentity(expectedRoot, songId, recovered.directory.uri)
            journal.write(
                "DIRECTORY_RECOVERED",
                JSONObject().put("createdDirectoryUri", recoveryUri)
            )
            return recovered
        }

        // A checked root listing may still find an older exact directory even when the Room URI
        // was not durable (for example after process death between provider and Room commits).
        roots.checkedSongSnapshot(expectedRoot, songId)?.let { existing ->
            persistPackageDirectoryIdentity(expectedRoot, songId, existing.directory.uri)
            journal.write(
                "DIRECTORY_BOUND",
                JSONObject().put("packageDocumentUri", existing.directory.uri.toString())
            )
            return roots.checkedBoundSongSnapshot(
                expectedRoot,
                songId,
                existing.directory.uri.toString()
            )
        }

        // A durable source without a durable/reopenable directory is uncertainty, not permission
        // to create. This is the fail-closed boundary that prevents cold recovery from acquiring
        // new selected-root authority.
        if (dao.currentSource(songId) != null) {
            throw LibraryRootException(
                "PACKAGE_DIRECTORY_IDENTITY_UNAVAILABLE",
                "The durable source has no reopenable exact song directory"
            )
        }

        val createdUri = roots.createSongDirectoryDocument(expectedRoot, songId)
        try {
            journal.write(
                "DIRECTORY_CREATED",
                JSONObject()
                    .put("fileName", fileName)
                    .put("createdDirectoryUri", createdUri.toString())
            )
        } catch (journalError: Throwable) {
            val cleanupError = suspendResult {
                roots.deleteExactEmptyBoundDirectory(expectedRoot, createdUri)
            }.exceptionOrNull()
            if (cleanupError != null) {
                throw LibraryRootException(
                    "PACKAGE_DIRECTORY_CLEANUP_PENDING",
                    "The returned directory could not be journaled or safely removed"
                ).also {
                    it.addSuppressed(journalError)
                    it.addSuppressed(cleanupError)
                }
            }
            throw journalError
        }

        try {
            // Exact reopen alone cannot prove root membership or the bounded complete snapshot.
            if (!roots.rootContainsBoundDirectoryIdentity(expectedRoot, createdUri)) {
                throw missingReturnedDirectoryIdentity()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rootError: Throwable) {
            val cleanupError = suspendResult {
                roots.deleteExactEmptyBoundDirectory(expectedRoot, createdUri)
            }.exceptionOrNull()
            if (cleanupError != null) {
                throw LibraryRootException(
                    "PACKAGE_DIRECTORY_CLEANUP_PENDING",
                    "The over-capacity returned directory could not be safely removed"
                ).also {
                    it.addSuppressed(rootError)
                    it.addSuppressed(cleanupError)
                }
            }
            try {
                journal.complete(
                    JSONObject()
                        .put("result", "POST_CREATE_ROOT_REJECTED_ROLLED_BACK")
                        .put("createdDirectoryUri", createdUri.toString())
                )
            } catch (journalError: Throwable) {
                journalError.addSuppressed(rootError)
                throw journalError
            }
            throw rootError
        }

        val returned = try {
            roots.checkedBoundDirectory(expectedRoot, createdUri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw LibraryRootException(
                "PACKAGE_DIRECTORY_IDENTITY_UNCERTAIN",
                "The returned song directory could not be verified"
            ).also { it.initCause(error) }
        }
        if (returned.displayName != songId) {
            try {
                roots.deleteExactEmptyBoundDirectory(expectedRoot, createdUri)
                journal.complete(
                    JSONObject()
                        .put("result", "RENAMED_COLLISION_ROLLED_BACK")
                        .put("createdDirectoryUri", createdUri.toString())
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cleanupError: Throwable) {
                throw LibraryRootException(
                    "PACKAGE_DIRECTORY_COLLISION",
                    "The provider renamed a colliding song directory request"
                ).also { it.addSuppressed(cleanupError) }
            }
            throw LibraryRootException(
                "PACKAGE_DIRECTORY_COLLISION",
                "The provider renamed a colliding song directory request"
            )
        }

        val created = roots.checkedBoundSongSnapshot(
            expectedRoot,
            songId,
            createdUri.toString()
        )
        persistPackageDirectoryIdentity(expectedRoot, songId, createdUri)
        journal.write(
            "DIRECTORY_BOUND",
            JSONObject().put("packageDocumentUri", createdUri.toString())
        )
        return created
    }

    /**
     * Converges an earlier DIRECTORY_CREATED record before a new PREPARED attempt is admitted.
     * The exact returned URI is the only external deletion authority. A provider-renamed result
     * is never rebound as a song directory, and an unreadable/conflicting record remains blocking.
     */
    private suspend fun reconcileCreatedDirectoryBeforeJournal(
        handle: RootHandle,
        songId: String
    ): String? {
        val records = publicationJournalRecords(songId).filter { record ->
            record.state != "COMPLETE" &&
                (record.fileName.isBlank() ||
                    record.fileName in SelectedLibraryContract.originalFiles ||
                    record.createdDirectoryUri != null)
        }
        if (records.isEmpty()) return null
        val exact = records.filter { it.matches(handle) }
        if (exact.size != records.size) {
            throw LibraryRootException(
                "PACKAGE_DIRECTORY_RECOVERY_PENDING",
                "A prior directory mutation belongs to another root authority"
            )
        }
        val createdRecords = exact.filter { it.createdDirectoryUri != null }
        val identities = createdRecords.mapNotNull(PublicationJournalRecord::createdDirectoryUri)
            .distinct()
        if (identities.size > 1) {
            throw LibraryRootException(
                "PACKAGE_DIRECTORY_RECOVERY_PENDING",
                "Conflicting returned directory identities require review"
            )
        }
        if (identities.isEmpty()) {
            // A pre-existing directory can still contain an asset created by this operation.
            // Keep that exact target receipt even when no directory creation was necessary.
            deleteExactJournalRecords(exact.filter { it.targetUri == null && it.stagingName == null })
            return null
        }

        val identity = identities.single()
        val owner = createdRecords
            .filter { it.createdDirectoryUri == identity }
            .sortedWith(compareByDescending<PublicationJournalRecord> { it.sequence }
                .thenBy { it.file.name })
            .first()
        val redundant = exact.filter { it.file != owner.file && it.stagingName == null }
        val createdUri = runCatching { Uri.parse(identity) }.getOrElse { error ->
            throw recoveryPending("The returned directory URI is invalid", error)
        }
        roots.requireSameLibrary(handle)
        val returned = try {
            roots.checkedBoundDirectory(handle, createdUri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val exactIdentityAbsent = suspendResult {
                !roots.rootContainsBoundDirectoryIdentity(handle, createdUri)
            }.getOrDefault(false)
            if (exactIdentityAbsent) {
                // Only a complete parent inventory proves disappearance; direct lookup can be stale.
                deleteExactJournalRecords(exact)
                throw collisionFailure(identity)
            }
            deleteExactJournalRecords(redundant)
            throw recoveryPending("The returned directory identity is unreadable or changed", error)
        }

        if (returned.displayName == songId) {
            if (owner.state == "DIRECTORY_CREATED") {
                try {
                    if (!roots.rootContainsBoundDirectoryIdentity(handle, createdUri)) {
                        throw missingReturnedDirectoryIdentity()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (rootError: Throwable) {
                    val cleanupError = suspendResult {
                        roots.deleteExactEmptyBoundDirectory(handle, createdUri)
                    }.exceptionOrNull()
                    if (cleanupError != null) {
                        val pending = LibraryRootException(
                            "PACKAGE_DIRECTORY_RECOVERY_PENDING",
                            "The unverified returned directory could not be safely removed"
                        ).also {
                            it.addSuppressed(rootError)
                            it.addSuppressed(cleanupError)
                        }
                        runCatching {
                            deleteExactJournalRecords(redundant)
                        }.exceptionOrNull()?.let(pending::addSuppressed)
                        throw pending
                    }
                    try {
                        deleteExactJournalRecords(exact)
                    } catch (journalError: Throwable) {
                        journalError.addSuppressed(rootError)
                        throw journalError
                    }
                    throw rootError
                }
            }
            deleteExactJournalRecords(redundant)
            return identity
        }

        try {
            roots.deleteExactEmptyBoundDirectory(handle, createdUri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cleanupError: Throwable) {
            deleteExactJournalRecords(redundant)
            throw collisionFailure(identity).also { it.addSuppressed(cleanupError) }
        }
        deleteExactJournalRecords(exact)
        throw collisionFailure(identity)
    }

    private fun publicationJournalRecords(songId: String): List<PublicationJournalRecord> =
        checkedAtomicJournalRecords(
            File(context.filesDir, "operation-journals"),
            "JOURNAL_ENUMERATION_FAILED"
        ).asSequence()
            .mapNotNull { record ->
                val file = record.file
                val envelope = requireAtomicJournalEnvelope(record, "JOURNAL_CORRUPT")
                if (envelope.operation != "FLAT_ASSET_PUBLISH") {
                    return@mapNotNull null
                }
                check(envelope.state in ASSET_PUBLICATION_STATES) { "JOURNAL_CORRUPT" }
                if (envelope.packageId != songId) return@mapNotNull null
                val details = envelope.details
                val fileName = (details.opt("fileName") as? String).orEmpty()
                val createdUri = (details.opt("createdDirectoryUri") as? String)
                    ?.takeIf(String::isNotBlank)
                val operationOwnerId = (details.opt("operationOwnerId") as? String).orEmpty()
                val targetUri = (details.opt("targetUri") as? String)?.takeIf(String::isNotBlank)
                val targetDocumentId = (details.opt("targetDocumentId") as? String)
                    ?.takeIf(String::isNotBlank)
                val expectedSha256 = (details.opt("sha256") as? String).orEmpty()
                val byteLength = (details.opt("byteLength") as? Number)?.toLong() ?: -1L
                val createdByOperation = details.opt("createdByOperation") as? Boolean ?: false
                val result = (details.opt("result") as? String).orEmpty()
                PublicationJournalRecord(
                    file = file,
                    libraryId = envelope.libraryId,
                    treeUri = envelope.treeUri,
                    rootEpoch = envelope.rootEpoch,
                    sequence = envelope.sequence,
                    state = envelope.state,
                    fileName = fileName,
                    createdDirectoryUri = createdUri,
                    operationOwnerId = operationOwnerId,
                    targetUri = targetUri,
                    targetDocumentId = targetDocumentId,
                    stagingName = (details.opt("stagingName") as? String)?.also {
                        check(SelectedLibraryContract.isPublicationStagingName(it)) { "JOURNAL_CORRUPT" }
                    },
                    expectedSha256 = expectedSha256,
                    byteLength = byteLength,
                    createdByOperation = createdByOperation,
                    result = result,
                    partialSha256 = (details.opt("partialSha256") as? String)?.takeIf(SHA256::matches),
                    partialByteLength = exactAtomicJournalLong(details.opt("partialByteLength"))?.takeIf { it >= 0L }
                )
            }
            .sortedBy { it.file.name }
            .toList()

    private fun deleteExactJournalRecords(records: Collection<PublicationJournalRecord>) {
        deleteExactJournalFiles(records.map(PublicationJournalRecord::file))
    }

    private fun cleanupOperationJournals(
        handle: RootHandle,
        songId: String,
        operation: String,
        currentTransactionId: String? = null,
        preparedOnly: Boolean
    ) {
        val files = checkedAtomicJournalRecords(
            File(context.filesDir, "operation-journals"),
            "JOURNAL_ENUMERATION_FAILED"
        ).mapNotNull { record ->
            val envelope = requireAtomicJournalEnvelope(record, "JOURNAL_CORRUPT")
            if (
                envelope.operation != operation ||
                envelope.packageId != songId ||
                envelope.libraryId != handle.libraryId ||
                envelope.treeUri != handle.treeUri.toString() ||
                envelope.rootEpoch != handle.epoch ||
                envelope.transactionId == currentTransactionId ||
                (preparedOnly && (envelope.state != "PREPARED" || envelope.details.length() != 0))
            ) null else record.file
        }
        deleteExactJournalFiles(files)
    }

    private fun deleteExactJournalFiles(files: Collection<File>) {
        files.distinctBy(File::getPath).forEach { file ->
            val parent = File(context.filesDir, "operation-journals").canonicalFile
            val exact = file.canonicalFile
            check(exact.parentFile == parent && exact.extension == "json") {
                "JOURNAL_CLEANUP_PENDING"
            }
            deleteAtomicJournal(exact, "JOURNAL_CLEANUP_PENDING")
        }
    }

    private fun recoveryPending(message: String, cause: Throwable): LibraryRootException =
        LibraryRootException("PACKAGE_DIRECTORY_RECOVERY_PENDING", message).also {
            it.addSuppressed(cause)
        }

    private fun missingReturnedDirectoryIdentity(): LibraryRootException =
        LibraryRootException(
            "PACKAGE_DIRECTORY_IDENTITY_UNCERTAIN",
            "The returned directory was absent from the complete root snapshot"
        )

    private fun collisionFailure(createdUri: String): LibraryRootException =
        LibraryRootException(
            "PACKAGE_DIRECTORY_COLLISION",
            "The provider-renamed collision at $createdUri was not accepted as package authority"
        )

    private suspend fun persistPackageDirectoryIdentity(
        expectedRoot: RootHandle,
        songId: String,
        directoryUri: Uri
    ) {
        roots.requireSameLibrary(expectedRoot)
        beforeDirectoryBindingForTest?.invoke()
        // Publish the manual row with its binding atomically; playlist pruning owns unbound discoveries.
        dao.bindPublishedPackageDocument(SongEntity(
            songId, songId, null, "https://music.youtube.com/watch?v=$songId",
            songId, "Unknown artist", null, null, durationMs = 0L,
            artworkPath = null, currentlyInSourcePlaylist = false,
            highLevelState = "DISCOVERED", acquiredAtEpochMs = null,
            createdAtEpochMs = System.currentTimeMillis(), packageDocumentUri = directoryUri.toString()
        ))
        roots.requireSameLibrary(expectedRoot)
        check(dao.findSongById(songId)?.packageDocumentUri == directoryUri.toString()) {
            "PACKAGE_DIRECTORY_IDENTITY_COMMIT_FAILED"
        }
    }

    private fun recoverCreatedDirectoryUri(
        handle: RootHandle,
        songId: String,
        currentTransactionId: String
    ): String? {
        val identities = publicationJournalRecords(songId)
            .asSequence()
            .filter { it.file.name != "$currentTransactionId.json" }
            .filter { it.matches(handle) }
            .filter { it.fileName in SelectedLibraryContract.originalFiles }
            .mapNotNull(PublicationJournalRecord::createdDirectoryUri)
            .distinct()
            .toList()
        if (identities.size > 1) error("PACKAGE_DIRECTORY_RECOVERY_PENDING")
        return identities.singleOrNull()
    }

    private data class PublicationJournalRecord(
        val file: File,
        val libraryId: String,
        val treeUri: String,
        val rootEpoch: Long,
        val sequence: Long,
        val state: String,
        val fileName: String,
        val createdDirectoryUri: String?,
        val operationOwnerId: String,
        val targetUri: String?,
        val targetDocumentId: String?,
        val stagingName: String?,
        val expectedSha256: String,
        val byteLength: Long,
        val createdByOperation: Boolean,
        val result: String,
        val partialSha256: String?,
        val partialByteLength: Long?
    ) {
        fun matches(handle: RootHandle): Boolean =
            libraryId == handle.libraryId &&
                treeUri == handle.treeUri.toString() &&
                rootEpoch == handle.epoch

        fun sourceRecoveryClaim(): OwnedStemCleanupClaim? = takeIf {
            createdByOperation && targetUri != null && targetDocumentId != null &&
                SHA256.matches(expectedSha256) && byteLength > 0L &&
                (state == "TARGET_ACQUIRED" || state == "PRUNING_PARTIAL_SOURCE" ||
                    state == "VERIFIED" || state == "COMPLETE" && result == "PUBLISHED")
        }?.let {
            OwnedStemCleanupClaim(operationOwnerId, fileName, requireNotNull(targetUri),
                requireNotNull(targetDocumentId), expectedSha256, byteLength)
        }
    }

    private suspend fun copyAndVerify(
        expectedRoot: RootHandle,
        source: File,
        target: DocumentFile,
        expectedSha256: String
    ) {
        try {
            roots.requireSameLibrary(expectedRoot)
            val expectedBytes = source.length()
            requireImportStorageCapacity(providerImportAvailableBytes(context, expectedRoot.treeUri),
                expectedBytes, "LIBRARY_STORAGE_LOW")
            context.contentResolver.withCancellableProviderOutput(target.uri) {
                    _, writeCancellable, flushCancellable ->
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        roots.requireSameLibrary(expectedRoot)
                        check(read.toLong() <= expectedBytes - copied) { "ASSET_SIZE_MISMATCH" }
                        writeCancellable(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        copyProgressForTest?.invoke(copied)
                        backgroundWorkCoordinator.checkpoint()
                    }
                    check(digest.digest().toHexString() == expectedSha256) { "ASSET_HASH_MISMATCH" }
                    check(copied == expectedBytes) { "ASSET_SIZE_MISMATCH" }
                }
                flushCancellable()
            } ?: error("EXTERNAL_COMMIT_FAILED")
        } catch (error: IOException) {
            if (
                error.message.orEmpty().contains("space", ignoreCase = true) ||
                error.message.orEmpty().contains("ENOSPC", ignoreCase = true)
            ) error("LIBRARY_STORAGE_LOW")
            throw error
        }
        roots.requireSameLibrary(expectedRoot)
        val measured = measureDocument(expectedRoot, target, source.length())
        check(measured.byteLength == source.length()) { "ASSET_SIZE_MISMATCH" }
        check(measured.sha256 == expectedSha256) { "ASSET_HASH_MISMATCH" }
        roots.requireSameLibrary(expectedRoot)
    }

    private suspend fun measureDocument(
        expectedRoot: RootHandle,
        file: DocumentFile,
        maximumBytes: Long
    ): ProviderDocumentIdentity {
        roots.requireSameLibrary(expectedRoot)
        val measured = measureProviderDocument(
            context = context,
            uri = file.uri,
            metadataSizeBytes = file.length().takeIf { it > 0L },
            maximumBytes = maximumBytes
        ) {
            roots.requireSameLibrary(expectedRoot)
            backgroundWorkCoordinator.checkpoint()
        }
        roots.requireSameLibrary(expectedRoot)
        return measured
    }

    /** Reads empty or interrupted newly-owned targets, bounded by their declared copy size. */
    private suspend fun measureSourceRecoveryDocument(
        expectedRoot: RootHandle,
        file: SelectedDocumentSnapshot,
        maximumBytes: Long
    ): ProviderDocumentIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        context.contentResolver.withCancellableProviderInput(file.uri) { _, read ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                roots.requireSameLibrary(expectedRoot)
                val count = read(buffer)
                if (count < 0) break
                bytes = Math.addExact(bytes, count.toLong())
                check(bytes <= maximumBytes) { "SOURCE_PART_RECOVERY_REQUIRED" }
                digest.update(buffer, 0, count)
                backgroundWorkCoordinator.checkpoint()
            }
        } ?: error("SOURCE_PART_RECOVERY_REQUIRED")
        return ProviderDocumentIdentity(digest.digest().toHexString(), bytes)
    }

    private suspend fun sha256Prefix(file: File, byteLength: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = byteLength
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (remaining > 0L) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(read > 0) { "ORIGINAL_CONFLICT" }
                digest.update(buffer, 0, read)
                remaining -= read
                backgroundWorkCoordinator.checkpoint()
            }
        }
        return digest.digest().toHexString()
    }

    private fun cleanupRecoveredAssetJournals(
        handle: RootHandle,
        songId: String,
        fileName: String,
        currentTransactionId: String,
        operationOwnerId: String?
    ) {
        publicationJournalRecords(songId)
            .filter { it.file.name != "$currentTransactionId.json" }
            .forEach { record ->
                if (
                    record.matches(handle) &&
                    record.fileName == fileName &&
                    (record.operationOwnerId.isBlank() ||
                        record.operationOwnerId == operationOwnerId && record.stagingName != null && record.state != "COMPLETE")
                ) deleteAtomicJournal(record.file, "JOURNAL_CLEANUP_PENDING")
            }
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 128 * 1024
        private const val MAX_NEXT_ASSET_RESERVE_BYTES = 256L * 1024L * 1024L
        private val ASSET_PUBLICATION_STATES = setOf(
            "PREPARED",
            "DIRECTORY_RECOVERED",
            "DIRECTORY_BOUND",
            "DIRECTORY_CREATED",
            "COPYING",
            "TARGET_ACQUIRED",
            "STAGING_PREPARED",
            "STAGING_VERIFIED",
            "PARTIAL_WRITTEN",
            "PRUNING_PARTIAL_SOURCE",
            "VERIFIED",
            "COMPLETE"
        )
        private val SHA256 = Regex("^[a-f0-9]{64}$")
        private val OPERATION_OWNER_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

internal data class OwnedStemCleanupClaim(
    val operationOwnerId: String,
    val fileName: String,
    val targetUri: String,
    val targetDocumentId: String,
    val sha256: String,
    val byteLength: Long
)

internal fun ownedStemCleanupDeletionAllowed(
    operationOwnerId: String,
    fileName: String,
    targetUri: String,
    targetDocumentId: String,
    measured: ProviderDocumentIdentity?,
    claim: OwnedStemCleanupClaim?
): Boolean = claim != null &&
    measured != null &&
    claim.operationOwnerId == operationOwnerId &&
    claim.fileName == fileName &&
    claim.targetUri == targetUri &&
    claim.targetDocumentId == targetDocumentId &&
    measured.sha256 == claim.sha256 &&
    measured.byteLength == claim.byteLength

internal fun ownedSourcePartRecoveryAllowed(
    operationOwnerId: String,
    fileName: String,
    targetUri: String,
    targetDocumentId: String,
    measured: ProviderDocumentIdentity?,
    claim: OwnedStemCleanupClaim?
): Boolean = fileName in SelectedLibraryContract.customStemFiles &&
    ownedStemCleanupDeletionAllowed(operationOwnerId, fileName, targetUri, targetDocumentId, measured, claim)

internal fun ownedUnverifiedSourcePartCleanupAllowed(
    operationOwnerId: String,
    fileName: String,
    targetUri: String,
    targetDocumentId: String,
    state: String,
    createdByOperation: Boolean,
    measured: ProviderDocumentIdentity?,
    claim: OwnedStemCleanupClaim?
): Boolean = createdByOperation && state in setOf("TARGET_ACQUIRED", "PRUNING_PARTIAL_SOURCE") &&
    fileName in SelectedLibraryContract.customStemFiles && claim != null && measured != null &&
    claim.operationOwnerId == operationOwnerId && claim.fileName == fileName &&
    claim.targetUri == targetUri && claim.targetDocumentId == targetDocumentId &&
    measured.byteLength in 0 until claim.byteLength

internal fun canonicalAssetPublicationAllowed(
    songId: String,
    blockedSourceIds: Set<String>
): Boolean = "youtube-video:$songId" !in blockedSourceIds
