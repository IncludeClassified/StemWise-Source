package app.stemwise.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.stemwise.suspendResult
import app.stemwise.integration.withCancellableProviderInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import app.stemwise.integration.PortableSourceJobAuthority
import app.stemwise.integration.SOURCE_JOB_AUTHORITY_FILES
import app.stemwise.integration.selectPortableSourceJobAuthority
import app.stemwise.integration.parsePortableSourceJobAuthority
import app.stemwise.integration.projectSourceJob
import app.stemwise.integration.remainingPublicationOwnerId
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal enum class DeletionJournalReplayMode {
    CURRENT,
    REBASE_CONFIRMED,
    SETTLE_ABSENT,
    SETTLE_PREPARED,
    RETAIN
}

internal fun deletionJournalReplayMode(
    current: RootAuthority,
    recordedLibraryId: String,
    recordedTreeUri: String,
    recordedRootEpoch: Long,
    state: String
): DeletionJournalReplayMode = when {
    current.libraryId != recordedLibraryId || current.treeUri != recordedTreeUri ->
        DeletionJournalReplayMode.RETAIN
    current.epoch == recordedRootEpoch -> DeletionJournalReplayMode.CURRENT
    recordedRootEpoch > current.epoch -> DeletionJournalReplayMode.RETAIN
    state == "PREPARED" && recordedRootEpoch < current.epoch -> DeletionJournalReplayMode.SETTLE_PREPARED
    state in setOf("DELETE_CONFIRMED", "SOURCE_BINDING_COMMIT") -> DeletionJournalReplayMode.REBASE_CONFIRMED
    state == "COMPLETE" ->
        DeletionJournalReplayMode.SETTLE_ABSENT
    else -> DeletionJournalReplayMode.RETAIN
}

internal fun rebasedDeletionTransactionId(rootEpoch: Long, transactionId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$rootEpoch:$transactionId".toByteArray(Charsets.UTF_8))
        .toHexString()
    return "delete-recovery-$digest"
}

internal data class DeletionChildIdentity(
    val documentId: String,
    val sha256: String?,
    val byteLength: Long?
)

internal data class DeletionPlan(
    val directoryUri: String,
    val directoryId: String,
    val children: Map<String, DeletionChildIdentity>,
    val contentIdentityRecorded: Boolean,
    val preparationAuthorities: List<PortableSourceJobAuthority> = emptyList(),
    val publicationJournals: Map<String, String> = emptyMap(),
    val privateRecoveryFiles: Map<String, String> = emptyMap()
)

internal data class DeletionDirectoryIdentity(
    val packageId: String,
    val directoryUri: String,
    val directoryId: String
)

internal fun completeDeletionSnapshotProvesTargetAbsent(
    current: RootAuthority,
    recordedLibraryId: String,
    recordedTreeUri: String,
    packageId: String,
    plan: DeletionPlan,
    recordedDirectoryBoundToCurrentRoot: Boolean,
    completeDirectories: List<DeletionDirectoryIdentity>?
): Boolean = current.libraryId == recordedLibraryId &&
    current.treeUri == recordedTreeUri &&
    recordedDirectoryBoundToCurrentRoot &&
    completeDirectories != null &&
    completeDirectories.none { directory ->
        directory.packageId == packageId ||
            directory.directoryUri == plan.directoryUri ||
            directory.directoryId == plan.directoryId
    }

internal fun deletionTargetMatchesPlan(
    directoryUri: String,
    directoryId: String,
    children: Map<String, DeletionChildIdentity>,
    plan: DeletionPlan,
    requireContentIdentity: Boolean
): Boolean = directoryUri == plan.directoryUri && directoryId == plan.directoryId &&
    (!requireContentIdentity || plan.contentIdentityRecorded) && children.all { (name, current) ->
        val recorded = plan.children[name] ?: return@all false
        current.documentId == recorded.documentId &&
            (!requireContentIdentity ||
                current.sha256 == recorded.sha256 && current.byteLength == recorded.byteLength)
    }

private class DeletionFinalizationRetainedException : IllegalStateException()

/** Permanent deletion with a portable root tombstone plus exact app-private replay details. */
class ExternalPackageTrash(
    context: Context,
    private val roots: LibraryRootManager,
    private val mutations: PackageMutationCoordinator,
    private val sourceBindings: SourceBindingRegistry,
    private val dao: LibraryDao
) {
    private val applicationContext = context.applicationContext
    private val journalDirectory by lazy { File(applicationContext.filesDir, "operation-journals") }
    @Volatile internal var beforePreparationRetirementForTest: (() -> Unit)? = null
    @Volatile internal var deletionRecoveryReadForTest: (suspend (String, String) -> Unit)? = null
    @Volatile internal var deletionChildRemovedForTest: (suspend (String) -> Unit)? = null

    /** Only the explicit Add song action may release permanent automatic-reimport suppression. */
    suspend fun prepareExplicitReadd(handle: RootHandle, packageId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            suspendResult {
                require(SelectedLibraryContract.isVideoId(packageId)) { "PACKAGE_ID_INVALID" }
                roots.requireSameLibrary(handle)
                val sourceId = "youtube-video:$packageId"
                if (sourceId !in sourceBindings.blockedSourceIds(handle)) return@suspendResult
                recoverInterruptedDeletes(handle)
                mutations.withRootMutation {
                    roots.requireSameLibrary(handle)
                    val pending = checkedAtomicJournalRecords(journalDirectory, "JOURNAL_ENUMERATION_FAILED")
                        .map { requireAtomicJournalEnvelope(it, "JOURNAL_CORRUPT") }
                        .any { it.libraryId == handle.libraryId && it.treeUri == handle.treeUri.toString() &&
                            it.packageId == packageId && it.operation in setOf("DELETE_PERMANENT", DELETE_STEMS_OPERATION) }
                    check(!pending) { "DELETE_RECOVERY_REQUIRED" }
                    check(roots.checkedSongSnapshot(handle, packageId) == null) { "DELETE_RECOVERY_REQUIRED" }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        sourceBindings.retireDeletedSourceUnderRootLock(handle, packageId)
                    }
                }
            }
        }

    suspend fun deletePermanently(
        handle: RootHandle,
        packageId: String,
        onCommitted: () -> Unit = {},
        onExactRootAdmitted: suspend () -> Unit = {}
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            suspendResult {
                require(SelectedLibraryContract.isVideoId(packageId)) { "PACKAGE_ID_INVALID" }
                val transactionId = UUID.randomUUID().toString()
                mutations.withPackageMutation(
                    handle,
                    packageId,
                    "DELETE_PERMANENT",
                    transactionId,
                    requireExactRoot = { roots.requireSameLibrary(handle) }
                ) { journal ->
                    roots.requireSameLibrary(handle)
                    onExactRootAdmitted()
                    val sourceSnapshot = roots.checkedAuthoritativeSongSnapshot(
                        handle,
                        packageId,
                        requireDurableIdentity = true
                    ) ?: error("DELETE_IDENTITY_UNAVAILABLE")
                    val sourceId = "youtube-video:$packageId"
                    val deletionPlan = deletionPlan(handle, sourceSnapshot, includeBundles = true)
                    check(sourceSnapshot.files.keys.all(deletionPlan.children::containsKey) &&
                        sourceSnapshot.files.values.none { it.directory }) { "PACKAGE_DIRECTORY_CLEANUP_REFUSED" }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        journal.write(
                            "DELETE_CONFIRMED",
                            deletionPlanDetails(handle, sourceId, deletionPlan)
                        )
                        sourceBindings.markDeletedUnderPackageLock(
                            handle,
                            sourceId,
                            packageId,
                            transactionId,
                            journal
                        )
                        deleteExactPackage(handle, packageId, deletionPlan, freshlyCaptured = true)
                        journal.complete(JSONObject().put("result", "DELETED_PERMANENTLY"))
                        onCommitted()
                    }
                }
            }
        }

    suspend fun deleteStems(
        handle: RootHandle,
        packageId: String,
        onCommitted: () -> Unit = {},
        onExactRootAdmitted: suspend () -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        suspendResult {
            require(SelectedLibraryContract.isVideoId(packageId)) { "PACKAGE_ID_INVALID" }
            val transactionId = UUID.randomUUID().toString()
            mutations.withPackageMutation(
                handle,
                packageId,
                DELETE_STEMS_OPERATION,
                transactionId,
                requireExactRoot = { roots.requireSameLibrary(handle) }
            ) { journal ->
                roots.requireSameLibrary(handle)
                onExactRootAdmitted()
                val snapshot = roots.checkedAuthoritativeSongSnapshot(
                    handle,
                    packageId,
                    requireDurableIdentity = true
                ) ?: error("DELETE_IDENTITY_UNAVAILABLE")
                check(snapshot.inspection.hasOriginal) { "ORIGINAL_MISSING" }
                check(snapshot.files.keys.any(::isPartDeletionFile)) { "STEMS_UNAVAILABLE" }
                val plan = deletionPlan(handle, snapshot)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    journal.write(
                        "DELETE_CONFIRMED",
                        deletionPlanDetails(handle, "youtube-video:$packageId", plan)
                    )
                    deleteExactStems(handle, packageId, plan, freshlyCaptured = true)
                    journal.complete(JSONObject().put("result", "STEMS_DELETED"))
                    onCommitted()
                }
            }
        }
    }

    /** Rolls a process-death deletion journal forward without creating external recovery files. */
    suspend fun recoverInterruptedDeletes(expectedRoot: RootHandle? = null) = withContext(Dispatchers.IO) {
        val handle = expectedRoot ?: roots.captureHandle() ?: return@withContext
        roots.requireSameLibrary(handle)
        sourceBindings.recoverRetainedTombstoneWrites(handle)
        mutations.withRootMutation { checkedAtomicJournalRecords(journalDirectory, "JOURNAL_ENUMERATION_FAILED") }
            .map(AtomicJournalRecord::file)
            .forEach { file ->
                // A stale-epoch replay can complete and delete a deterministic current-epoch
                // journal that was also present in the original enumeration.
                val record = readAtomicJournal(file, "JOURNAL_CORRUPT") ?: return@forEach
                var value = record.value
                var envelope = requireAtomicJournalEnvelope(record, "JOURNAL_CORRUPT")
                if (envelope.operation !in setOf(DELETE_STEMS_OPERATION, "DELETE_PERMANENT")) return@forEach
                deletionRecoveryReadForTest?.invoke(envelope.packageId, envelope.state)
                val settled = mutations.withPackageRead(handle, envelope.packageId) {
                    mutations.withRootMutation {
                        roots.requireSameLibrary(handle)
                        val latest = readAtomicJournal(file, "JOURNAL_CORRUPT") ?: return@withRootMutation true
                        val current = requireAtomicJournalEnvelope(latest, "JOURNAL_CORRUPT")
                        check(current.transactionId == envelope.transactionId && current.packageId == envelope.packageId &&
                            current.operation == envelope.operation) { "JOURNAL_CORRUPT" }
                        val mode = deletionJournalReplayMode(handle.authority(), current.libraryId,
                            current.treeUri, current.rootEpoch, current.state)
                        if (mode in setOf(DeletionJournalReplayMode.CURRENT, DeletionJournalReplayMode.SETTLE_PREPARED) &&
                            current.state in setOf("PREPARED", "COMPLETE") ||
                            mode == DeletionJournalReplayMode.SETTLE_ABSENT && current.operation == DELETE_STEMS_OPERATION && current.state == "COMPLETE") {
                            deleteAtomicJournal(file, "JOURNAL_CLEANUP_PENDING")
                            true
                        } else false
                    }
                }
                if (settled) return@forEach
                // No stale PREPARED snapshot may delete a journal advanced by its live owner.
                val latest = readAtomicJournal(file, "JOURNAL_CORRUPT") ?: return@forEach
                val latestEnvelope = requireAtomicJournalEnvelope(latest, "JOURNAL_CORRUPT")
                value = latest.value
                envelope = latestEnvelope
                if (envelope.operation == DELETE_STEMS_OPERATION) {
                    recoverInterruptedStemDelete(handle, file, latest.value, latestEnvelope)
                    return@forEach
                }
                if (envelope.operation != "DELETE_PERMANENT") return@forEach
                check(envelope.state in DELETE_STATES) { "JOURNAL_CORRUPT" }
                val packageId = envelope.packageId
                val transactionId = envelope.transactionId
                check(SelectedLibraryContract.isVideoId(packageId)) { "JOURNAL_CORRUPT" }
                val replayMode = deletionJournalReplayMode(
                    handle.authority(),
                    envelope.libraryId,
                    envelope.treeUri,
                    envelope.rootEpoch,
                    envelope.state
                )
                if (replayMode == DeletionJournalReplayMode.RETAIN) {
                    Log.w(
                        "StemwiseStorage",
                        "Retaining mismatched-root deletion journal ${file.name}"
                    )
                    return@forEach
                }
                if (envelope.state == "PREPARED" || replayMode == DeletionJournalReplayMode.CURRENT && envelope.state == "COMPLETE") return@forEach
                val replayPlan = persistedDeletionPlan(
                    value,
                    expectedLibraryId = envelope.libraryId,
                    expectedPackageId = packageId
                ) ?: error("JOURNAL_CORRUPT")
                if (replayMode == DeletionJournalReplayMode.REBASE_CONFIRMED) {
                    check(replayPlan.contentIdentityRecorded) {
                        "DELETE_REBASE_IDENTITY_UNAVAILABLE"
                    }
                }
                if (replayMode == DeletionJournalReplayMode.SETTLE_ABSENT) {
                    if (!permanentReplayTargetAbsent(handle, envelope, replayPlan)) {
                        Log.w("StemwiseStorage", "Retaining deletion journal without a complete absence proof ${file.name}")
                        return@forEach
                    }
                    retireStoppedDeletionJournal(handle, file, envelope)
                    return@forEach
                }
                val replayTransactionId = if (replayMode == DeletionJournalReplayMode.REBASE_CONFIRMED) {
                    rebasedDeletionTransactionId(handle.epoch, transactionId)
                } else transactionId
                val replayCompleted = try {
                    mutations.withPackageMutation(
                        handle,
                        packageId,
                        "DELETE_PERMANENT",
                        replayTransactionId,
                        requireExactRoot = { roots.requireSameLibrary(handle) },
                        beforeJournal = {
                            if (replayMode == DeletionJournalReplayMode.REBASE_CONFIRMED) {
                                val target = checkedDeletionTarget(handle, packageId, replayPlan, requireContentIdentity = true)
                                if (target == null && !permanentReplayTargetAbsent(handle, envelope, replayPlan)) {
                                    throw DeletionFinalizationRetainedException()
                                }
                            }
                        }
                    ) { journal ->
                        roots.requireSameLibrary(handle)
                        if (replayMode == DeletionJournalReplayMode.REBASE_CONFIRMED) {
                            journal.write(
                                "DELETE_CONFIRMED",
                                deletionPlanDetails(handle, "youtube-video:$packageId", replayPlan)
                            )
                        }
                        sourceBindings.markDeletedUnderPackageLock(
                            handle,
                            "youtube-video:$packageId",
                            packageId,
                            replayTransactionId,
                            journal
                        )
                        deleteExactPackage(handle, packageId, replayPlan)
                        journal.complete(JSONObject().put("result", "DELETED_PERMANENTLY"))
                    }
                    true
                } catch (_: DeletionFinalizationRetainedException) {
                    false
                }
                if (!replayCompleted) {
                    Log.w(
                        "StemwiseStorage",
                        "Retaining deletion journal after its absence proof changed ${file.name}"
                    )
                    return@forEach
                }
                if (replayMode == DeletionJournalReplayMode.REBASE_CONFIRMED) {
                    retireStoppedDeletionJournal(handle, file, envelope)
                }
        }
        sourceBindings.confirmedDeletedPackageIds(handle).sorted().forEach { packageId ->
            val snapshot = try {
                roots.checkedAuthoritativeSongSnapshot(
                    handle,
                    packageId,
                    requireDurableIdentity = false
                )
            } catch (error: LibraryRootException) {
                if (error.code == "PACKAGE_DIRECTORY_UNAVAILABLE") return@forEach
                throw error
            } ?: return@forEach
            val transactionId = "portable-delete-${UUID.randomUUID()}"
            mutations.withPackageMutation(
                handle,
                packageId,
                "DELETE_PERMANENT",
                transactionId,
                requireExactRoot = { roots.requireSameLibrary(handle) }
            ) { journal ->
                val admittedSnapshot = roots.checkedAuthoritativeSongSnapshot(
                    handle,
                    packageId,
                    requireDurableIdentity = false
                )
                if (admittedSnapshot == null) {
                    journal.complete(JSONObject().put("result", "ALREADY_ABSENT"))
                    return@withPackageMutation
                }
                val sourceId = "youtube-video:$packageId"
                val deletionPlan = deletionPlan(handle, admittedSnapshot, includeBundles = true)
                journal.write(
                    "DELETE_CONFIRMED",
                    deletionPlanDetails(handle, sourceId, deletionPlan)
                )
                sourceBindings.markDeletedUnderPackageLock(
                    handle,
                    sourceId,
                    packageId,
                    transactionId,
                    journal
                )
                deleteExactPackage(handle, packageId, deletionPlan)
                journal.complete(JSONObject().put("result", "DELETED_PERMANENTLY"))
            }
        }
    }

    private suspend fun recoverInterruptedStemDelete(
        handle: RootHandle,
        file: File,
        value: JSONObject,
        envelope: AtomicJournalEnvelope
    ) {
        check(envelope.state in STEM_DELETE_STATES) { "JOURNAL_CORRUPT" }
        val replayMode = deletionJournalReplayMode(
            handle.authority(),
            envelope.libraryId,
            envelope.treeUri,
            envelope.rootEpoch,
            envelope.state
        )
        if (replayMode == DeletionJournalReplayMode.RETAIN) {
            Log.w("StemwiseStorage", "Retaining mismatched-root stem deletion journal ${file.name}")
            return
        }
        if (envelope.state == "COMPLETE") {
            return
        }
        if (envelope.state == "PREPARED") {
            return
        }
        val plan = persistedDeletionPlan(
            value,
            expectedLibraryId = envelope.libraryId,
            expectedPackageId = envelope.packageId
        ) ?: error("JOURNAL_CORRUPT")
        check(plan.contentIdentityRecorded) { "DELETE_REBASE_IDENTITY_UNAVAILABLE" }
        val transactionId = if (replayMode == DeletionJournalReplayMode.REBASE_CONFIRMED) {
            rebasedDeletionTransactionId(handle.epoch, envelope.transactionId)
        } else {
            envelope.transactionId
        }
        mutations.withPackageMutation(
            handle,
            envelope.packageId,
            DELETE_STEMS_OPERATION,
            transactionId,
            requireExactRoot = { roots.requireSameLibrary(handle) },
            beforeJournal = {
                checkedDeletionTarget(
                    handle,
                    envelope.packageId,
                    plan,
                    requireContentIdentity = true
                )
            }
        ) { journal ->
            journal.write(
                "DELETE_CONFIRMED",
                deletionPlanDetails(
                    handle,
                    "youtube-video:${envelope.packageId}",
                    plan
                )
            )
            deleteExactStems(handle, envelope.packageId, plan)
            journal.complete(JSONObject().put("result", "STEMS_DELETED"))
        }
        if (replayMode == DeletionJournalReplayMode.REBASE_CONFIRMED) {
            retireStoppedDeletionJournal(handle, file, envelope)
        }
    }

    private suspend fun retireStoppedDeletionJournal(handle: RootHandle, file: File, expected: AtomicJournalEnvelope) =
        mutations.withPackageRead(handle, expected.packageId) {
            mutations.withRootMutation {
                roots.requireSameLibrary(handle)
                val current = readAtomicJournal(file, "JOURNAL_CORRUPT")?.let { requireAtomicJournalEnvelope(it, "JOURNAL_CORRUPT") }
                    ?: return@withRootMutation
                if (current.transactionId == expected.transactionId && current.sequence == expected.sequence &&
                    current.state == expected.state && current.libraryId == expected.libraryId &&
                    current.treeUri == expected.treeUri && current.rootEpoch == expected.rootEpoch) {
                    deleteAtomicJournal(file, "JOURNAL_CLEANUP_PENDING")
                }
            }
        }

    private suspend fun deletionPlan(handle: RootHandle, snapshot: SelectedSongSnapshot, includeBundles: Boolean = false): DeletionPlan {
        val copies = roots.readExactSongSidecars(handle, snapshot.videoId, SOURCE_JOB_AUTHORITY_FILES,
            app.stemwise.integration.PORTABLE_SOURCE_JOB_MAX_BYTES)
        // Even inconsistent generations cannot hide a valid accepted/unknown authority copy.
        copies.values.mapNotNull { runCatching { parsePortableSourceJobAuthority(it) }.getOrNull() }
            .forEach { authority ->
                check(authority.libraryId == handle.libraryId && authority.videoId == snapshot.videoId &&
                    authority.state !in setOf("SUBMISSION_STARTED", "SUBMISSION_UNKNOWN", "PROCESSING")) {
                    "SOURCE_JOB_AUTHORITY_RECOVERY_REQUIRED"
                }
            }
        var damagedAuthority = false
        fun select(canonical: String, temporary: String): PortableSourceJobAuthority? = try {
            selectPortableSourceJobAuthority(copies[canonical], copies[temporary])
        } catch (invalid: IllegalStateException) {
            if (invalid.message != app.stemwise.integration.PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED) throw invalid
            // Explicit discard may remove damaged optional tracking, never a still-owned operation.
            damagedAuthority = true
            null
        }
        val authorities = listOfNotNull(
            select(SelectedLibraryContract.SOURCE_JOB_AUTHORITY, SelectedLibraryContract.SOURCE_JOB_AUTHORITY_TEMP),
            select(SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY, SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY_TEMP))
        if (damagedAuthority) {
            check(dao.unresolvedSourceJobsForSong(snapshot.videoId, handle.libraryId).isEmpty()) {
                "SOURCE_JOB_AUTHORITY_RECOVERY_REQUIRED"
            }
        }
        val source = dao.currentSource(snapshot.videoId)
        val portableOriginal = if (source == null && authorities.isNotEmpty()) {
            check(authorities.all { it.state in setOf("COMPLETE", "DISCARDED") }) {
                "SOURCE_JOB_AUTHORITY_RECOVERY_REQUIRED"
            }
            measureDeletionContent(checkNotNull(snapshot.original) { "SOURCE_JOB_AUTHORITY_RECOVERY_REQUIRED" })
        } else null
        authorities.forEach { authority ->
            check(authority.libraryId == handle.libraryId && authority.videoId == snapshot.videoId &&
                authority.state !in setOf("SUBMISSION_STARTED", "SUBMISSION_UNKNOWN", "PROCESSING")) {
                "SOURCE_JOB_AUTHORITY_RECOVERY_REQUIRED"
            }
            check(if (source != null) source.id == authority.sourceVersionId && source.sha256 == authority.sourceSha256
                else portableOriginal?.sha256 == authority.sourceSha256) {
                "SOURCE_JOB_AUTHORITY_RECOVERY_REQUIRED"
            }
            dao.findJobById(authority.jobId)?.let { projectSourceJob(authority, it) }
        }
        val owners = authorities.flatMap { listOf(it.jobId, remainingPublicationOwnerId(it.jobId)) }.toSet()
        val publications = checkedAtomicJournalRecords(journalDirectory, "JOURNAL_ENUMERATION_FAILED").filter { record ->
            val envelope = requireAtomicJournalEnvelope(record, "JOURNAL_CORRUPT")
            if (envelope.packageId != snapshot.videoId || envelope.libraryId != handle.libraryId ||
                envelope.treeUri != handle.treeUri.toString() || envelope.rootEpoch != handle.epoch) return@filter false
            if (damagedAuthority && envelope.operation in setOf("SOURCE_JOB_AUTHORITY_V1", "SOURCE_MANIFEST_COMMIT")) {
                error("SOURCE_JOB_AUTHORITY_RECOVERY_REQUIRED")
            }
            when (envelope.operation) {
                "FLAT_ASSET_PUBLISH" -> {
                    val name = envelope.details.optString("fileName")
                    if (name !in SelectedLibraryContract.customStemFiles) false else {
                        val owner = envelope.details.optString("operationOwnerId")
                        if (owner !in owners) {
                            val baseOwner = owner.removeSuffix(".${NATIVE_ORIGINAL_48K_REMAINING_PRODUCER}")
                            check(dao.findJobById(baseOwner) == null) { "SOURCE_PART_RECOVERY_REQUIRED" }
                        }
                        true
                    }
                }
                "SONG_BUNDLE_IMPORT_V1" -> includeBundles
                "ENRICHMENT_INSTALL_V2" -> if (!includeBundles) false else {
                    app.stemwise.enrichment.requireEnrichmentRecoveryState(envelope.state)
                    val uri = envelope.details.opt("packageDocumentUri") as? String
                    if (uri == null && envelope.state in setOf("PREPARED", "COMPLETE")) false else {
                        check(uri == snapshot.directory.uri.toString() &&
                            envelope.details.opt("packageDocumentId") == snapshot.directory.documentId &&
                            envelope.details.opt("libraryId") == handle.libraryId &&
                            envelope.details.opt("videoId") == snapshot.videoId) { "ENRICHMENT_RECOVERY_REQUIRED" }
                        true
                    }
                }
                "SOURCE_JOB_AUTHORITY_V1" -> authorities.any { envelope.transactionId == "source-${it.jobId}" }
                "SOURCE_MANIFEST_COMMIT" -> authorities.any { envelope.transactionId == "sources-${it.jobId}" }
                else -> false
            }
        }
        val staging = publications.mapNotNull { record ->
            val envelope = requireAtomicJournalEnvelope(record, "JOURNAL_CORRUPT")
            (envelope.details.opt("stagingName") as? String)?.also { name ->
                check(SelectedLibraryContract.isPublicationStagingName(name)) { "JOURNAL_CORRUPT" }
                snapshot.file(name)?.let { child ->
                    val uri = envelope.details.opt("targetUri") as? String
                    check(!child.directory && (uri == null || child.uri.toString() == uri)) { "SOURCE_PART_RECOVERY_REQUIRED" }
                }
            }
        }.toSet()
        val privateFiles = linkedMapOf<String, String>()
        if (includeBundles) publications.forEach { record ->
            val envelope = requireAtomicJournalEnvelope(record, "JOURNAL_CORRUPT")
            suspend fun capture(name: String, expectedHash: String) {
                val file = privateRecoveryFile(name)
                if (file.exists()) {
                    check(expectedHash.matches(SHA256) && privateRecoverySha256(file) == expectedHash) {
                        "ENRICHMENT_RECOVERY_REQUIRED"
                    }
                    privateFiles[name] = expectedHash
                }
            }
            when (envelope.operation) {
                "ENRICHMENT_INSTALL_V2" -> {
                    check(runCatching { UUID.fromString(envelope.transactionId).toString() }.getOrNull() == envelope.transactionId) {
                        "JOURNAL_CORRUPT"
                    }
                    for ((role, suffix, hash) in listOf(
                        Triple("jsonBackupName", "json.backup", "oldJsonSha256"),
                        Triple("jsonReplacementName", "json.replacement", "newJsonSha256"),
                        Triple("coverBackupName", "cover.backup", "oldCoverSha256"),
                        Triple("coverReplacementName", "cover.replacement", "newCoverSha256")
                    )) {
                        val name = "${envelope.transactionId}.$suffix"
                        check(envelope.details.opt(role) == name) { "JOURNAL_CORRUPT" }
                        capture("enrichment-recovery/$name", envelope.details.optString(hash))
                    }
                }
                "SONG_BUNDLE_IMPORT_V1" -> {
                    val hash = envelope.details.optString("archiveSha256")
                    check(hash.matches(SHA256)) { "JOURNAL_CORRUPT" }
                    val identity = MessageDigest.getInstance("SHA-256").digest(
                        "${handle.libraryId}|${handle.treeUri}|${snapshot.videoId}|$hash".toByteArray(Charsets.UTF_8)).toHexString()
                    check(envelope.transactionId == "bundle-$identity") { "JOURNAL_CORRUPT" }
                    capture("song-bundle-intake/${envelope.transactionId}.zip", hash)
                }
            }
        }
        return DeletionPlan(snapshot.directory.uri.toString(), snapshot.directory.documentId,
            snapshot.files.filter { (name, child) -> (name in SelectedLibraryContract.packageFiles || name in staging) && !child.directory }
                .mapValues { (_, child) ->
                    val content = portableOriginal?.takeIf { child == snapshot.original } ?: measureDeletionContent(child)
                    DeletionChildIdentity(child.documentId, content.sha256, content.byteLength)
                }, true, authorities, publications.associate { it.file.name to CanonicalJson.sha256(it.value) }, privateFiles)
    }

    private suspend fun retireDeletedPreparation(handle: RootHandle, packageId: String, plan: DeletionPlan) {
        roots.requireSameLibrary(handle)
        beforePreparationRetirementForTest?.invoke()
        // The song/preparation lane has retired workers; the deletion journal owns replay until this settles.
        val journals = plan.publicationJournals.mapNotNull { (name, digest) ->
            val file = File(journalDirectory, name)
            val record = readAtomicJournal(file, "JOURNAL_CORRUPT") ?: return@mapNotNull null
            val envelope = requireAtomicJournalEnvelope(record, "JOURNAL_CORRUPT")
            check(envelope.packageId == packageId && envelope.libraryId == handle.libraryId &&
                envelope.treeUri == handle.treeUri.toString() && CanonicalJson.sha256(record.value) == digest) { "SOURCE_PART_RECOVERY_REQUIRED" }
            file
        }
        plan.preparationAuthorities.forEach { authority ->
            check(authority.libraryId == handle.libraryId && authority.videoId == packageId) { "DELETE_IDENTITY_INVALID" }
            dao.findJobById(authority.jobId)?.let { previous ->
                val job = projectSourceJob(authority, previous)
                dao.upsertJob(job.copy(state = "OBSOLETE", nextAttemptAtEpochMs = null,
                    lastErrorCode = null, lastErrorMessage = null))
            }
        }
        val privateFiles = plan.privateRecoveryFiles.map { (name, digest) ->
            privateRecoveryFile(name).also { file ->
                check(!file.exists() || privateRecoverySha256(file) == digest) { "ENRICHMENT_RECOVERY_REQUIRED" }
            }
        }
        privateFiles.forEach { file -> check(!file.exists() || file.delete()) { "JOURNAL_CLEANUP_PENDING" } }
        journals.forEach { deleteAtomicJournal(it, "JOURNAL_CLEANUP_PENDING") }
    }

    private fun privateRecoveryFile(name: String): File {
        check(Regex("(?:enrichment-recovery/[a-f0-9-]{36}\\.(?:json|cover)\\.(?:backup|replacement)|song-bundle-intake/bundle-[a-f0-9]{64}\\.zip)").matches(name)) {
            "JOURNAL_CORRUPT"
        }
        val root = applicationContext.filesDir.canonicalFile
        val file = File(root, name)
        check(file.canonicalFile == file.absoluteFile && file.parentFile?.parentFile == root &&
            (!file.exists() || file.isFile)) { "ENRICHMENT_RECOVERY_REQUIRED" }
        return file
    }

    private suspend fun privateRecoverySha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0) { "ENRICHMENT_RECOVERY_REQUIRED" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    private suspend fun permanentReplayTargetAbsent(
        handle: RootHandle,
        envelope: AtomicJournalEnvelope,
        plan: DeletionPlan
    ): Boolean = completeDeletionSnapshotProvesTargetAbsent(
        handle.authority(), envelope.libraryId, envelope.treeUri, envelope.packageId, plan,
        selectedBoundUriMatchesRoot(handle.treeUri, Uri.parse(plan.directoryUri)),
        completeDeletionDirectoryIdentities(handle)
    )

    private suspend fun completeDeletionDirectoryIdentities(
        handle: RootHandle
    ): List<DeletionDirectoryIdentity>? = try {
        roots.checkedSnapshot(handle).songDirectories.map { directory ->
            DeletionDirectoryIdentity(
                packageId = directory.displayName,
                directoryUri = directory.uri.toString(),
                directoryId = directory.documentId
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.w(
            "StemwiseStorage",
            "A complete deletion-finalization snapshot is unavailable",
            error
        )
        null
    }

    private fun deletionPlanDetails(
        handle: RootHandle,
        sourceId: String,
        plan: DeletionPlan
    ): JSONObject = JSONObject().apply {
        put("libraryId", handle.libraryId)
        put("sourceId", sourceId)
        put("packageDocumentUri", plan.directoryUri)
        put("packageDocumentId", plan.directoryId)
        put("preparationAuthorities", JSONArray().apply {
            plan.preparationAuthorities.forEach { put(JSONObject(it.toBytes().toString(Charsets.UTF_8))) }
        })
        put("publicationJournals", JSONObject(plan.publicationJournals))
        put("privateRecoveryFiles", JSONObject(plan.privateRecoveryFiles))
        put("packageChildDocumentIds", JSONObject().apply {
            plan.children.toSortedMap().forEach { (name, identity) ->
                put(name, identity.documentId)
            }
        })
        if (plan.contentIdentityRecorded) {
            put("packageChildContentIdentities", JSONObject().apply {
                plan.children.toSortedMap().forEach { (name, identity) ->
                    put(
                        name,
                        JSONObject()
                            .put("sha256", checkNotNull(identity.sha256))
                            .put("byteLength", checkNotNull(identity.byteLength))
                    )
                }
            })
        }
    }

    private fun persistedDeletionPlan(
        journal: JSONObject,
        expectedLibraryId: String,
        expectedPackageId: String
    ): DeletionPlan? {
        val details = journal.optJSONObject("details") ?: return null
        if (details.opt("libraryId") != expectedLibraryId) return null
        if (details.opt("sourceId") != "youtube-video:$expectedPackageId") return null
        val directoryUri = (details.opt("packageDocumentUri") as? String)
            ?.takeIf(String::isNotBlank) ?: return null
        val parsedUri = Uri.parse(directoryUri)
        if (parsedUri.scheme != "content" || parsedUri.authority.isNullOrBlank()) return null
        val directoryId = (details.opt("packageDocumentId") as? String)
            ?.takeIf(String::isNotBlank) ?: return null
        if (runCatching { DocumentsContract.getDocumentId(parsedUri) }.getOrNull() != directoryId) {
            return null
        }
        val storedChildren = details.optJSONObject("packageChildDocumentIds") ?: return null
        val childIds = linkedMapOf<String, String>()
        val uniqueIds = hashSetOf<String>()
        for (name in storedChildren.keys().asSequence().toList()) {
            if (name !in SelectedLibraryContract.packageFiles && !SelectedLibraryContract.isPublicationStagingName(name)) return null
            val documentId = (storedChildren.opt(name) as? String)
                ?.takeIf(String::isNotBlank) ?: return null
            if (!uniqueIds.add(documentId)) return null
            childIds[name] = documentId
        }
        val storedContent = details.optJSONObject("packageChildContentIdentities")
        val content = storedContent?.let { values ->
            if (values.keys().asSequence().toSet() != childIds.keys) return null
            childIds.mapValues { (name, documentId) ->
                val identity = values.optJSONObject(name) ?: return null
                if (identity.keys().asSequence().toSet() != setOf("sha256", "byteLength")) {
                    return null
                }
                val sha256 = (identity.opt("sha256") as? String)
                    ?.takeIf { it.matches(SHA256) } ?: return null
                val byteLength = exactAtomicJournalLong(identity.opt("byteLength"))
                    ?.takeIf { it >= 0L } ?: return null
                DeletionChildIdentity(documentId, sha256, byteLength)
            }
        }
        return DeletionPlan(
            directoryUri,
            directoryId,
            content ?: childIds.mapValues { (_, documentId) ->
                DeletionChildIdentity(documentId, null, null)
            },
            contentIdentityRecorded = storedContent != null,
            preparationAuthorities = details.optJSONArray("preparationAuthorities")?.let { values ->
                (0 until values.length()).map { index ->
                    parsePortableSourceJobAuthority(values.getJSONObject(index).toString().toByteArray(Charsets.UTF_8)).also {
                        check(it.libraryId == expectedLibraryId && it.videoId == expectedPackageId) { "JOURNAL_CORRUPT" }
                    }
                }
            }.orEmpty(),
            publicationJournals = details.optJSONObject("publicationJournals")?.let { values ->
                values.keys().asSequence().associateWith { name ->
                    check(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json").matches(name)) { "JOURNAL_CORRUPT" }
                    checkNotNull((values.opt(name) as? String)?.takeIf(SHA256::matches)) { "JOURNAL_CORRUPT" }
                }
            }.orEmpty(),
            privateRecoveryFiles = details.optJSONObject("privateRecoveryFiles")?.let { values ->
                values.keys().asSequence().associateWith { name ->
                    privateRecoveryFile(name)
                    checkNotNull((values.opt(name) as? String)?.takeIf(SHA256::matches)) { "JOURNAL_CORRUPT" }
                }
            }.orEmpty()
        )
    }

    private suspend fun checkedDeletionTarget(
        handle: RootHandle,
        packageId: String,
        plan: DeletionPlan,
        requireContentIdentity: Boolean
    ): SelectedSongSnapshot? {
        val directoryUri = Uri.parse(plan.directoryUri)
        val current = try {
            roots.checkedBoundSongSnapshot(
                handle,
                packageId,
                plan.directoryUri,
            )
        } catch (error: LibraryRootException) {
            if (error.code != "PACKAGE_DIRECTORY_UNAVAILABLE") throw error
            check(!roots.rootContainsBoundDirectoryIdentity(handle, directoryUri)) {
                "EXTERNAL_COMMIT_FAILED"
            }
            return null
        }
        val currentChildren = current.files.filter { (name, child) -> (name in SelectedLibraryContract.packageFiles || name in plan.children) && !child.directory }.mapValues { (_, child) ->
            if (requireContentIdentity) {
                val content = measureDeletionContent(child)
                DeletionChildIdentity(child.documentId, content.sha256, content.byteLength)
            } else {
                DeletionChildIdentity(child.documentId, null, null)
            }
        }
        check(
            deletionTargetMatchesPlan(
                current.directory.uri.toString(),
                current.directory.documentId,
                currentChildren,
                plan,
                requireContentIdentity
            )
        ) { "PACKAGE_DIRECTORY_CLEANUP_REFUSED" }
        return current
    }

    private suspend fun deleteExactPackage(
        handle: RootHandle,
        packageId: String,
        plan: DeletionPlan,
        freshlyCaptured: Boolean = false
    ) {
        roots.requireSameLibrary(handle)
        val directoryUri = Uri.parse(plan.directoryUri)
        check(directoryUri.scheme == "content" && !directoryUri.authority.isNullOrBlank()) {
            "DELETE_IDENTITY_INVALID"
        }
        check(runCatching { DocumentsContract.getDocumentId(directoryUri) }.getOrNull() == plan.directoryId) {
            "DELETE_IDENTITY_INVALID"
        }
        val current = checkedDeletionTarget(
            handle,
            packageId,
            plan,
            requireContentIdentity = !freshlyCaptured && plan.contentIdentityRecorded
        ) ?: return retireDeletedPreparation(handle, packageId, plan)

        check(current.files.keys.all(plan.children::containsKey) && current.files.values.none { it.directory }) {
            "PACKAGE_DIRECTORY_CLEANUP_REFUSED"
        }
        current.files.values.filter { it.displayName in plan.children && !it.directory }.sortedBy { it.displayName }.forEach { child ->
            roots.requireSameLibrary(handle)
            check(child.document.delete()) { "EXTERNAL_COMMIT_FAILED" }
            deletionChildRemovedForTest?.invoke(packageId)
        }

        val remaining = checkedDeletionTarget(
            handle,
            packageId,
            plan,
            requireContentIdentity = false
        ) ?: error("EXTERNAL_COMMIT_FAILED")
        check(
            remaining.directory.documentId == plan.directoryId &&
                remaining.directory.uri.toString() == plan.directoryUri
        ) { "PACKAGE_DIRECTORY_CLEANUP_REFUSED" }
        check(remaining.files.keys.none { it in SelectedLibraryContract.packageFiles }) { "EXTERNAL_COMMIT_FAILED" }
        check(remaining.files.isEmpty()) { "PACKAGE_DIRECTORY_CLEANUP_REFUSED" }
        try {
            roots.deleteExactEmptyBoundDirectory(handle, directoryUri)
        } catch (error: IllegalStateException) {
            if (error.message == "PACKAGE_DIRECTORY_CLEANUP_PENDING") {
                throw IllegalStateException("EXTERNAL_COMMIT_FAILED", error)
            }
            throw error
        }
        check(!roots.rootContainsBoundDirectoryIdentity(handle, directoryUri)) {
            "EXTERNAL_COMMIT_FAILED"
        }
        retireDeletedPreparation(handle, packageId, plan)
        roots.requireSameLibrary(handle)
    }

    private suspend fun deleteExactStems(
        handle: RootHandle,
        packageId: String,
        plan: DeletionPlan,
        freshlyCaptured: Boolean = false
    ) {
        val current = checkedDeletionTarget(
            handle,
            packageId,
            plan,
            requireContentIdentity = !freshlyCaptured
        ) ?: return
        current.files.values
            .filter { !it.directory && (isPartDeletionFile(it.displayName) || it.displayName in plan.children &&
                SelectedLibraryContract.isPublicationStagingName(it.displayName)) }
            .sortedBy { it.displayName }
            .forEach { stem ->
                roots.requireSameLibrary(handle)
                check(stem.document.delete()) { "EXTERNAL_COMMIT_FAILED" }
                deletionChildRemovedForTest?.invoke(packageId)
            }
        val remaining = checkedDeletionTarget(
            handle,
            packageId,
            plan,
            requireContentIdentity = false
        ) ?: error("EXTERNAL_COMMIT_FAILED")
        check(
            remaining.directory.documentId == plan.directoryId &&
                remaining.directory.uri.toString() == plan.directoryUri &&
                remaining.inspection.hasOriginal &&
                !remaining.inspection.hasCustomSourceMedia &&
                remaining.files.filter { (name, child) -> name in SelectedLibraryContract.packageFiles && !child.directory }.keys ==
                plan.children.keys.filter { !isPartDeletionFile(it) && !SelectedLibraryContract.isPublicationStagingName(it) }.toSet()
        ) { "EXTERNAL_COMMIT_FAILED" }
        retireDeletedPreparation(handle, packageId, plan)
        roots.requireSameLibrary(handle)
    }

    private suspend fun measureDeletionContent(
        file: SelectedDocumentSnapshot
    ): ProviderDocumentIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteLength = 0L
        applicationContext.contentResolver.withCancellableProviderInput(file.uri) { _, read ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                check(count > 0) { "DELETE_IDENTITY_UNAVAILABLE" }
                byteLength = Math.addExact(byteLength, count.toLong())
                digest.update(buffer, 0, count)
            }
        } ?: error("DELETE_IDENTITY_UNAVAILABLE")
        return ProviderDocumentIdentity(
            digest.digest().toHexString(),
            byteLength
        )
    }

    private companion object {
        const val DELETE_STEMS_OPERATION = "DELETE_STEMS"
        val SHA256 = Regex("^[a-f0-9]{64}$")
        val DELETE_STATES = setOf(
            "PREPARED",
            "DELETE_CONFIRMED",
            "SOURCE_BINDING_COMMIT",
            "COMPLETE"
        )
        val STEM_DELETE_STATES = setOf("PREPARED", "DELETE_CONFIRMED", "COMPLETE")
    }
}

/** Explicit Delete parts removes only preparation-owned namespaces, preserving original/enrichment. */
internal fun isPartDeletionFile(name: String): Boolean = name in SelectedLibraryContract.customStemFiles || name in setOf(
        SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_TEMP,
        SelectedLibraryContract.SOURCE_JOB_AUTHORITY, SelectedLibraryContract.SOURCE_JOB_AUTHORITY_TEMP,
        SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY, SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY_TEMP,
        SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP
    )
