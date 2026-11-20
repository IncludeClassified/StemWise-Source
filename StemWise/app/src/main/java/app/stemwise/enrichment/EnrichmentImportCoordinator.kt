package app.stemwise.enrichment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import app.stemwise.data.ExternalLibraryRepository
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.IMPORT_STORAGE_FAILURE_CODES
import app.stemwise.data.isImportStorageFailure
import app.stemwise.data.RootAuthority
import app.stemwise.data.authority
import app.stemwise.data.checkedPrivateChildren
import app.stemwise.data.deleteExactPrivateTree
import app.stemwise.data.exactAtomicJournalLong
import app.stemwise.data.readAtomicJournal
import app.stemwise.integration.queryCancellable
import app.stemwise.integration.withCancellableProviderInput
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.suspendResult
import app.stemwise.work.AppTaskWorkGate
import app.stemwise.work.TaskWorkGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class ImportProgressKind { FILES, SONGS }

internal class ImportCopyProgressCadence {
    private var lastPublishedNanos: Long? = null
    fun shouldPublish(nowNanos: Long): Boolean {
        if (lastPublishedNanos?.let { nowNanos - it < 100_000_000L } == true) return false
        lastPublishedNanos = nowNanos
        return true
    }
}

sealed interface SharedEnrichmentImportState {
    data object Idle : SharedEnrichmentImportState
    data class AwaitingConfirmation(
        val sources: List<Uri>,
        val sourceNames: List<String> = emptyList(),
        val rejectedOffers: Int = 0,
        val operationId: String = UUID.randomUUID().toString(),
        val message: String? = null
    ) : SharedEnrichmentImportState
    data class Running(
        val completed: Int = 0,
        val total: Int = 0,
        val phase: String = "Preparing",
        val rejectedOffers: Int = 0,
        val progressKind: ImportProgressKind = ImportProgressKind.SONGS,
        val copiedBytes: Long = 0L,
        val totalBytes: Long? = null,
        val operationId: String = UUID.randomUUID().toString(),
        val retirementPending: Boolean = false
    ) : SharedEnrichmentImportState
    data class Finished(
        val result: EnrichmentImportResult,
        val rejectedOffers: Int = 0
    ) : SharedEnrichmentImportState
    data class Cancelled(
        val message: String = "Import cancelled. Songs already imported are preserved.",
        val rejectedOffers: Int = 0
    ) : SharedEnrichmentImportState
    data class Failed(
        val message: String,
        val rejectedOffers: Int = 0
    ) : SharedEnrichmentImportState
}

private val SAFE_INBOUND_IMPORT_FAILURES = setOf(
    "Too many enrichment files were selected",
    "The selected import is empty",
    "Import selection is too large",
    "Not enough storage to stage this import"
)
private const val ENRICHMENT_STAGING_JOURNAL_SCHEMA_VERSION = 3L

/** Owner-facing boundary for exceptions originating in another app's DocumentsProvider. */
internal fun inboundEnrichmentFailureMessage(
    error: Throwable,
    cleanupFailed: Boolean = false
): String {
    val safe = if (error.message == "IMPORT_CANCELLED") "Import cancelled. Songs already imported are preserved."
        else error.message?.takeIf(SAFE_INBOUND_IMPORT_FAILURES::contains)
        ?: "The shared enrichment files could not be read. Check file access and try again."
    return if (cleanupFailed) "$safe Import cleanup also needs attention." else safe
}

internal fun decodeEnrichmentImportJournalRoot(journal: JSONObject): RootAuthority? =
    RootAuthority(
        libraryId = journal.opt("libraryId") as? String ?: return null,
        treeUri = journal.opt("treeUri") as? String ?: return null,
        epoch = exactAtomicJournalLong(journal.opt("rootEpoch")) ?: return null
    ).takeIf {
        exactAtomicJournalLong(journal.opt("schemaVersion")) ==
            ENRICHMENT_STAGING_JOURNAL_SCHEMA_VERSION &&
            it.libraryId.isNotBlank() && it.treeUri.isNotBlank() && it.epoch > 0L
    }

internal fun enrichmentStagingJournalIsFuture(journal: JSONObject): Boolean =
    (exactAtomicJournalLong(journal.opt("schemaVersion")) ?: Long.MIN_VALUE) >
        ENRICHMENT_STAGING_JOURNAL_SCHEMA_VERSION

class EnrichmentImportCoordinator internal constructor(
    context: Context,
    private val importer: SongEnrichmentImporter,
    private val externalLibrary: ExternalLibraryRepository,
    private val backgroundWorkCoordinator: BackgroundWorkCoordinator,
    private val roots: LibraryRootManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val stagingCleanupMutex: Mutex = Mutex(),
    private val cancellationRetirementTimeoutMs: Long = 10_000L
) {
    private val taskGate = AppTaskWorkGate.instance
    private val applicationContext = context.applicationContext
    private val stagingDirectory: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(applicationContext.noBackupFilesDir, "import-staging")
    }
    private val journalFile: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(stagingDirectory, ENRICHMENT_STAGING_JOURNAL_NAME)
    }
    @Volatile private var cancelRequested = false
    private val changedPackages = linkedSetOf<String>()

    fun requestCancel() = synchronized(startupLock) {
        val running = mutableState.value as? SharedEnrichmentImportState.Running
        if (running != null && !cancelRequested) {
            cancelRequested = true
            mutableState.value = running.copy(phase = "Cancelling import")
            activeJob?.let { owner ->
                owner.cancel(CancellationException("IMPORT_CANCELLED"))
                watchCancellationRetirement(owner, running.operationId)
            }
        }
    }

    private var cancellationNoticeOwner: Job? = null
    private fun watchCancellationRetirement(owner: Job, operationId: String) {
        if (cancellationNoticeOwner === owner) return
        cancellationNoticeOwner = owner
        scope.launch {
            kotlinx.coroutines.delay(cancellationRetirementTimeoutMs)
            synchronized(startupLock) {
                val current = mutableState.value as? SharedEnrichmentImportState.Running
                if (activeJob === owner && !owner.isCompleted && current?.operationId == operationId) {
                    mutableState.value = current.copy(retirementPending = true,
                        phase = "The import has not stopped. Force stop StemWise, then reopen it. Retained files will be recovered.")
                }
            }
        }
    }

    @Volatile private var activeJob: Job? = null
    @Volatile private var activeRoot: RootAuthority? = null
    @Volatile private var taskRemovalCleanup: Job? = null
    private val startupLock = Any()
    private val startupCommands = StartupCommandQueue(startupLock)
    private val taskRemovalCommands = TaskRemovalCommandQueue(startupLock)
    private var startupRecovery = CompletableDeferred<Result<Unit>>()
    private val mutableState = MutableStateFlow<SharedEnrichmentImportState>(
        SharedEnrichmentImportState.Idle
    )
    val state: StateFlow<SharedEnrichmentImportState> = mutableState.asStateFlow()

    private var startupJob = launchStartupRecovery(startupRecovery).also { it.start() }

    private fun launchStartupRecovery(attempt: CompletableDeferred<Result<Unit>>) = scope.launch(start = CoroutineStart.LAZY) {
        val recovery = runCatching {
            check(stagingDirectory.mkdirs() || stagingDirectory.isDirectory) {
                "Import staging storage is unavailable"
            }
            restoreStagedSession()
        }
        recovery.onFailure { error ->
            mutableState.value = SharedEnrichmentImportState.Failed(
                error.message ?: "Import staging storage is unavailable"
            )
        }
        try { startupCommands.complete(recovery.isSuccess, ::afterTaskRemoval) }
        finally { attempt.complete(recovery) }
    }

    internal suspend fun retryStartupScratch() {
        startupJob.join()
        taskRemovalCleanup?.join()
        val next = synchronized(startupLock) {
            if (taskRemovalCommands.needsRetry) {
                val generation = taskRemovalCommands.retry()
                mutableState.value = SharedEnrichmentImportState.Running(phase = "Retrying import cleanup")
                return@synchronized launchTaskRemovalCleanup(null, taskRemovalCleanup, generation).also {
                    taskRemovalCleanup = it
                }
            }
            if (!startupCommands.failed) return
            startupCommands.begin()
            mutableState.value = SharedEnrichmentImportState.Idle
            startupRecovery = CompletableDeferred()
            launchStartupRecovery(startupRecovery).also { startupJob = it }
        }
        next.start()
    }

    private fun afterStartup(command: () -> Unit) {
        val lease = taskGate.acquireLease() ?: return
        val admittedCommand = { taskGate.runIfCurrent(lease, command); Unit }
        startupCommands.submit(admittedCommand, ::afterTaskRemoval)
    }

    private fun afterTaskRemoval(command: () -> Unit) {
        taskRemovalCommands.submit(command) { it() }
    }

    /** Offers shared enrichment files without reading their provider until confirmation. */
    fun offer(uris: List<Uri>) {
        val sources = uris.distinct()
        if (sources.isEmpty()) return
        afterStartup { offerReady(sources) }
    }

    private fun offerReady(sources: List<Uri>) {
        val current = mutableState.value
        if (current is SharedEnrichmentImportState.Running) {
            mutableState.value = current.copy(rejectedOffers = current.rejectedOffers + 1)
            return
        }
        if (current is SharedEnrichmentImportState.AwaitingConfirmation) {
            mutableState.value = current.copy(rejectedOffers = current.rejectedOffers + 1)
            return
        }
        if (sources.size > MAX_SONG_INTAKE_ARCHIVES) {
            mutableState.value = SharedEnrichmentImportState.Failed(
                "Too many enrichment files were selected"
            )
            return
        }
        activeRoot = null
        mutableState.value = SharedEnrichmentImportState.AwaitingConfirmation(sources)
    }

    fun reject(message: String) {
        afterStartup {
            mutableState.value = when (val current = mutableState.value) {
                is SharedEnrichmentImportState.Running ->
                    current.copy(rejectedOffers = current.rejectedOffers + 1)
                is SharedEnrichmentImportState.AwaitingConfirmation ->
                    current.copy(rejectedOffers = current.rejectedOffers + 1)
                else -> SharedEnrichmentImportState.Failed(message)
            }
        }
    }

    fun confirm() {
        afterStartup {
            if (mutableState.value is SharedEnrichmentImportState.Running) return@afterStartup
            val awaiting = mutableState.value as? SharedEnrichmentImportState.AwaitingConfirmation
                ?: return@afterStartup
            val root = activeRoot
            if (root == null) {
                stageAcceptedOffer(awaiting.sources, awaiting.rejectedOffers, awaiting.operationId)
                return@afterStartup
            }
            val staged = stagedArchives(awaiting.sources, awaiting.sourceNames)
            if (staged == null) {
                activeRoot = null
                mutableState.value = SharedEnrichmentImportState.Failed(
                    "The shared enrichment files could not be read. Check file access and try again.",
                    awaiting.rejectedOffers
                )
                return@afterStartup
            }
            val lease = taskGate.acquireLease() ?: return@afterStartup
            if (!isAdmissionCurrent(lease, root)) return@afterStartup
            cancelRequested = false
            mutableState.value = SharedEnrichmentImportState.Running(
                operationId = awaiting.operationId,
                rejectedOffers = awaiting.rejectedOffers
            )
            launchImport(lease) { applyAcceptedStaging(lease, root, staged) }
        }
    }

    private suspend fun applyAcceptedStaging(
        lease: TaskWorkGate.Lease, root: RootAuthority, staged: List<StagedArchive>
    ) {
        val operation = runCatching {
            requireAdmission(lease, root)
            writeJournal(staged, root, "importing", 0, 0)
            backgroundWorkCoordinator.withHeavyWork("Importing song data") {
                importer.importStagedFiles(
                    archives = staged.map(StagedArchive::file),
                    sourceNames = staged.map(StagedArchive::sourceName),
                    expectedRoot = root,
                    onPackageChanged = { synchronized(startupLock) { changedPackages += it } },
                    onProgress = { completed, total, phase ->
                        updateProgress(lease, root, staged, completed, total, phase)
                    }
                ).getOrThrow()
            }
        }
        finish(operation, lease, root)
    }

    private fun stageAcceptedOffer(sources: List<Uri>, rejectedOffers: Int, operationId: String) {
        val lease = taskGate.acquireLease() ?: return
        cancelRequested = false
        mutableState.value = SharedEnrichmentImportState.Running(
            operationId = operationId,
            phase = "Copying import",
            rejectedOffers = rejectedOffers
        )
        launchImport(lease) {
            runCatching {
                val root = awaitExactRoot(lease)
                backgroundWorkCoordinator.withHeavyWork("Staging song data") {
                    stageAll(sources, root)
                }.also { requireAdmission(lease, root) } to root
            }
                .onSuccess { (staged, root) ->
                    publishIfCurrent(lease, root) {
                        activeRoot = root
                        mutableState.value = SharedEnrichmentImportState.Running(
                            operationId = operationId,
                            phase = "Importing song data",
                            rejectedOffers = (mutableState.value as? SharedEnrichmentImportState.Running)
                                ?.rejectedOffers ?: rejectedOffers
                        )
                    }
                    applyAcceptedStaging(lease, root, staged)
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (!taskGate.isCurrent(lease)) return@onFailure
                    val cleanupFailure = suspendResult {
                        cleanupStagingSerially()
                    }.exceptionOrNull()
                    taskGate.runIfCurrent(lease) {
                        activeRoot = null
                        mutableState.value = SharedEnrichmentImportState.Failed(
                            inboundEnrichmentFailureMessage(error, cleanupFailure != null),
                            (mutableState.value as? SharedEnrichmentImportState.Running)
                                ?.rejectedOffers ?: rejectedOffers
                        )
                    }
                }
        }
    }

    /** Starts one content-detected import for one or several selected files. */
    fun start(uris: List<Uri>) {
        val sources = uris.distinct()
        if (sources.isEmpty()) return
        afterStartup { startReady(sources) }
    }

    private fun startReady(sources: List<Uri>) {
        val current = mutableState.value
        if (current is SharedEnrichmentImportState.Running) {
            mutableState.value = current.copy(rejectedOffers = current.rejectedOffers + 1)
            return
        }
        if (current is SharedEnrichmentImportState.AwaitingConfirmation) {
            mutableState.value = current.copy(rejectedOffers = current.rejectedOffers + 1)
            return
        }
        startAcceptedSources(sources)
    }

    private fun startAcceptedSources(sources: List<Uri>, rejectedOffers: Int = 0) {
        val lease = taskGate.acquireLease() ?: return
        cancelRequested = false
        mutableState.value = SharedEnrichmentImportState.Running(
            phase = "Copying import",
            rejectedOffers = rejectedOffers
        )
        launchImport(lease) {
            val root = runCatching { awaitExactRoot(lease) }.getOrElse { error ->
                if (error is CancellationException) throw error
                if (error !is CancellationException) {
                    taskGate.runIfCurrent(lease) {
                        mutableState.value = SharedEnrichmentImportState.Failed(
                            inboundEnrichmentFailureMessage(error),
                            (mutableState.value as? SharedEnrichmentImportState.Running)
                                ?.rejectedOffers ?: rejectedOffers
                        )
                    }
                }
                return@launchImport
            }
            val operation = runCatching {
                backgroundWorkCoordinator.withHeavyWork("Importing song data") {
                    val staged = stageAll(sources, root)
                    requireAdmission(lease, root)
                    writeJournal(staged, root, "importing", 0, 0)
                        importer.importStagedFiles(
                        archives = staged.map(StagedArchive::file),
                        sourceNames = staged.map(StagedArchive::sourceName),
                        expectedRoot = root,
                        onPackageChanged = { synchronized(startupLock) { changedPackages += it } },
                        onProgress = { completed, total, phase ->
                            updateProgress(lease, root, staged, completed, total, phase)
                        }
                    ).getOrThrow()
                }
            }
            finish(operation, lease, root)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun launchImport(lease: TaskWorkGate.Lease, operation: suspend () -> Unit) = synchronized(startupLock) {
        changedPackages.clear()
        // ATOMIC enters the cancellation handler even when Cancel precedes IO dispatch.
        activeJob = scope.launch(start = CoroutineStart.ATOMIC) {
            var cancelledOperation = false
            try {
                synchronized(startupLock) { Unit } // Publish this owner before cancellation can settle it.
                if (cancelRequested) throw CancellationException("IMPORT_CANCELLED")
                operation()
            } catch (cancelled: CancellationException) {
                cancelledOperation = true
                val owner = currentCoroutineContext()[Job]
                withContext(NonCancellable) {
                    if (activeJob === owner && taskGate.isCurrent(lease)) {
                        val root = activeRoot
                        val rootCurrent = root == null || isSameRootIdentity(root)
                        if (rootCurrent) {
                            // Earlier bundle commits remain canonical even if a later bundle stops.
                            val refreshFailure = if (root != null && synchronized(startupLock) { changedPackages.isNotEmpty() }) {
                                if (isAdmissionCurrent(lease, root)) suspendResult {
                                    reconcileChangedPackages(root)
                                }.exceptionOrNull() else IllegalStateException("ROOT_UNAVAILABLE")
                            } else null
                            val cleanupFailure = suspendResult {
                                if (root != null) importer.cancelBundleArchiveInputs(root,
                                    checkedPrivateChildren(stagingDirectory, "IMPORT_STAGING_CLEANUP_FAILED").filter {
                                        it.isFile && isOwnedStagingFile(it)
                                    })
                                cleanupStagingSerially()
                            }.exceptionOrNull()
                            taskGate.runIfCurrent(lease) {
                                if (activeJob === owner && (root == null || isSameRootIdentity(root))) {
                                    val rejected = (mutableState.value as? SharedEnrichmentImportState.Running)
                                        ?.rejectedOffers ?: 0
                                    activeRoot = null
                                    mutableState.value = SharedEnrichmentImportState.Cancelled(
                                        inboundEnrichmentFailureMessage(
                                            IllegalStateException("IMPORT_CANCELLED"),
                                            cleanupFailure != null || refreshFailure != null
                                        ), rejected
                                    )
                                }
                            }
                        }
                    }
                }
                throw cancelled
            } finally {
                val owner = currentCoroutineContext()[Job]
                synchronized(startupLock) {
                    if (activeJob === owner) {
                        val running = mutableState.value as? SharedEnrichmentImportState.Running
                        if (running != null) {
                            // UI ownership retires even when provider authority no longer permits writes.
                            mutableState.value = if (cancelledOperation) {
                                SharedEnrichmentImportState.Cancelled(
                                    "Import stopped. Retained data will be recovered when the Library is available.",
                                    running.rejectedOffers
                                )
                            } else {
                                SharedEnrichmentImportState.Failed(
                                    "Library access changed during import. Restore access to recover retained data and retry.",
                                    running.rejectedOffers
                                )
                            }
                            activeRoot = null
                        }
                        activeJob = null
                    }
                }
            }
        }
        if (cancelRequested) activeJob?.let { owner ->
            owner.cancel(CancellationException("IMPORT_CANCELLED"))
            (mutableState.value as? SharedEnrichmentImportState.Running)?.let {
                watchCancellationRetirement(owner, it.operationId)
            }
        }
    }

    private suspend fun reconcileChangedPackages(root: RootAuthority) {
        val packages = synchronized(startupLock) { changedPackages.toList() }
        if (packages.isEmpty()) return
        val handle = roots.captureHandle()?.takeIf { it.authority() == root } ?: error("LIBRARY_CHANGED")
        externalLibrary.reconcilePackages(handle, packages)
    }

    private suspend fun finish(
        operation: Result<EnrichmentImportResult>,
        lease: TaskWorkGate.Lease,
        root: RootAuthority
    ) {
        operation.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (!isAdmissionCurrent(lease, root)) return
        val storageFailure = operation.exceptionOrNull()?.let(::isImportStorageFailure) == true ||
            operation.getOrNull()?.items?.any { it.errorCode in IMPORT_STORAGE_FAILURE_CODES } == true
        if (storageFailure) {
            val refreshFailure = suspendResult { reconcileChangedPackages(root) }.exceptionOrNull()
            val retained = suspendResult {
                val journal = checkNotNull(readAtomicJournal(journalFile, "IMPORT_STAGING_RECOVERY_PENDING")?.value)
                check(decodeEnrichmentImportJournalRoot(journal) == root) { "LIBRARY_CHANGED" }
                val files = checkNotNull(decodeJournalFiles(journal))
                check(enrichmentStagingJournalIsComplete(journal, files.size)) { "IMPORT_STAGING_RECOVERY_PENDING" }
                files
            }
            publishIfCurrent(lease, root) {
                val running = mutableState.value as? SharedEnrichmentImportState.Running
                mutableState.value = retained.fold(
                    onSuccess = { files -> SharedEnrichmentImportState.AwaitingConfirmation(
                        sources = files.map { Uri.fromFile(it.file) },
                        sourceNames = files.map(StagedArchive::sourceName),
                        rejectedOffers = running?.rejectedOffers ?: 0,
                        operationId = running?.operationId ?: UUID.randomUUID().toString(),
                        message = "Not enough storage to finish importing. Free some space, then choose Import to retry. " +
                            "Your copied files and songs already imported are preserved." +
                            if (refreshFailure != null) " The Library refresh also needs to retry." else ""
                    ) },
                    onFailure = { SharedEnrichmentImportState.Failed(
                        "Not enough storage to finish importing. Copied files are retained for recovery.",
                        running?.rejectedOffers ?: 0) }
                )
            }
            return
        }
        operation.fold(
            onSuccess = { result ->
                val refreshFailure = try {
                    reconcileChangedPackages(root)
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    error
                }
                if (!isAdmissionCurrent(lease, root)) return
                val cleanupFailure = suspendResult {
                    cleanupStagingSerially()
                }.exceptionOrNull()
                publishIfCurrent(lease, root) {
                    val rejectedOffers = (mutableState.value as? SharedEnrichmentImportState.Running)
                        ?.rejectedOffers ?: 0
                    activeRoot = null
                    mutableState.value = if (refreshFailure == null && cleanupFailure == null) {
                        SharedEnrichmentImportState.Finished(result, rejectedOffers)
                    } else {
                        SharedEnrichmentImportState.Failed(
                            postCommitFailureMessage(refreshFailure, cleanupFailure),
                            rejectedOffers
                        )
                    }
                }
            },
            onFailure = { error ->
                // A batch can fail or be cancelled after an earlier song committed. Publish those
                // real external packages before reporting the remaining failure.
                val refreshFailure = suspendResult { reconcileChangedPackages(root) }.exceptionOrNull()
                val cleanupFailure = suspendResult {
                    cleanupStagingSerially()
                }.exceptionOrNull()
                publishIfCurrent(lease, root) {
                    val rejectedOffers = (mutableState.value as? SharedEnrichmentImportState.Running)
                        ?.rejectedOffers ?: 0
                    activeRoot = null
                    mutableState.value = SharedEnrichmentImportState.Failed(
                        inboundEnrichmentFailureMessage(error, cleanupFailure != null || refreshFailure != null),
                        rejectedOffers
                    )
                }
            }
        )
    }

    private suspend fun updateProgress(
        lease: TaskWorkGate.Lease,
        root: RootAuthority,
        staged: List<StagedArchive>,
        completed: Int,
        total: Int,
        phase: String
    ) {
        requireAdmission(lease, root)
        backgroundWorkCoordinator.checkpoint()
        currentCoroutineContext().ensureActive()
        writeJournal(staged, root, phase, completed, total)
        publishIfCurrent(lease, root) {
            val rejectedOffers = (mutableState.value as? SharedEnrichmentImportState.Running)
                ?.rejectedOffers ?: 0
            mutableState.value = SharedEnrichmentImportState.Running(
                operationId = checkNotNull(mutableState.value as? SharedEnrichmentImportState.Running).operationId,
                completed = completed,
                total = total,
                phase = phase,
                rejectedOffers = rejectedOffers
            )
        }
    }

    fun dismiss() {
        afterStartup {
            if (mutableState.value !is SharedEnrichmentImportState.Running) {
                val awaiting = mutableState.value as? SharedEnrichmentImportState.AwaitingConfirmation
                if (awaiting != null) {
                    discardPendingImport(awaiting.operationId)
                } else {
                    activeRoot = null
                    mutableState.value = SharedEnrichmentImportState.Idle
                }
            }
        }
    }

    fun discardPendingImport(operationId: String) {
        afterStartup {
            val awaiting = (mutableState.value as? SharedEnrichmentImportState.AwaitingConfirmation)
                ?.takeIf { it.operationId == operationId } ?: return@afterStartup
            val root = activeRoot
            if (root == null) {
                mutableState.value = SharedEnrichmentImportState.Idle
                return@afterStartup
            }
            val lease = taskGate.acquireLease() ?: return@afterStartup
            mutableState.value = SharedEnrichmentImportState.Running(operationId = operationId, phase = "Discarding import")
            cancelRequested = false
            launchImport(lease) {
                val failure = suspendResult {
                    stagingCleanupMutex.withLock {
                        check(isSameRootIdentity(root)) { "LIBRARY_CHANGED" }
                        val journal = checkNotNull(readAtomicJournal(journalFile, "IMPORT_STAGING_RECOVERY_PENDING")?.value)
                        check(decodeEnrichmentImportJournalRoot(journal) == root) { "LIBRARY_CHANGED" }
                        val staged = checkNotNull(decodeJournalFiles(journal)) { "IMPORT_STAGING_RECOVERY_PENDING" }
                        check(staged.map { Uri.fromFile(it.file) } == awaiting.sources) { "IMPORT_STAGING_RECOVERY_PENDING" }
                        importer.cancelBundleArchiveInputs(root, staged.map(StagedArchive::file))
                        cleanupStaging()
                    }
                }.exceptionOrNull()
                taskGate.runIfCurrent(lease) {
                    if (failure == null) {
                        activeRoot = null
                        mutableState.value = SharedEnrichmentImportState.Idle
                    } else mutableState.value = awaiting.copy(message = "The import could not be discarded. Copied files are retained. Try Discard import again.")
                }
            }
        }
    }

    suspend fun cancelForLibraryChange() {
        startupJob.join()
        taskRemovalCleanup?.join()
        activeJob?.cancelAndJoin()
        activeJob = null
        activeRoot = null
        val cleanupFailure = suspendResult { cleanupStagingSerially() }.exceptionOrNull()
        if (cleanupFailure != null) {
            mutableState.value = SharedEnrichmentImportState.Failed(
                cleanupFailure.message ?: "IMPORT_STAGING_CLEANUP_FAILED"
            )
            throw cleanupFailure
        }
        mutableState.value = SharedEnrichmentImportState.Idle
    }

    internal fun onTaskRemoved() {
        val retired = activeJob
        activeJob = null
        activeRoot = null
        val preserveStartupFailure = startupCommands.failed
        if (!preserveStartupFailure) {
            mutableState.value = SharedEnrichmentImportState.Idle
        }
        // Cancel synchronously so a blocked provider receives its CancellationSignal and its
        // owned descriptor is closed before the task-removal callback returns.
        retired?.cancel()
        val cleanup = synchronized(startupLock) {
            val previousCleanup = taskRemovalCleanup
            val cleanupGeneration = taskRemovalCommands.begin()
            launchTaskRemovalCleanup(retired, previousCleanup, cleanupGeneration).also {
                taskRemovalCleanup = it
            }
        }
        cleanup.start()
    }

    private fun launchTaskRemovalCleanup(retired: Job?, previousCleanup: Job?, cleanupGeneration: Long) =
        scope.launch(start = CoroutineStart.LAZY) {
            startupJob.join()
            previousCleanup?.join()
            retired?.join()
            val cleanupFailure = suspendResult {
                cleanupStagingSerially()
            }.exceptionOrNull()
            val cleanupOwner = currentCoroutineContext()[Job]
            val (currentOwner, preserveStartupFailure) = synchronized(startupLock) {
                val current = taskRemovalCleanup === cleanupOwner
                current to startupCommands.failed
            }
            if (currentOwner) {
                if (cleanupFailure != null) {
                    mutableState.value = SharedEnrichmentImportState.Failed(
                        cleanupFailure.message ?: "IMPORT_STAGING_CLEANUP_FAILED"
                    )
                } else if (!preserveStartupFailure) {
                    // Startup may have restored AwaitingConfirmation after the synchronous
                    // reset. Reassert Idle only after those staged files are deleted.
                    mutableState.value = SharedEnrichmentImportState.Idle
                }
            }
            taskRemovalCommands.complete(
                cleanupGeneration,
                currentOwner && cleanupFailure == null
            ) { it() }
        }

    /** Cold-open barrier: startup recovery must classify staging before producers are admitted. */
    suspend fun awaitStartupScratchReady() {
        synchronized(startupLock) { startupRecovery }.await().getOrThrow()
    }

    private suspend fun stageAll(
        uris: List<Uri>,
        expectedRoot: RootAuthority
    ): List<StagedArchive> = withContext(Dispatchers.IO) {
        check(uris.isNotEmpty() && uris.size <= MAX_SONG_INTAKE_ARCHIVES) {
            "Too many enrichment files were selected"
        }
        cleanupStagingSerially()
        check(stagingDirectory.mkdirs() || stagingDirectory.isDirectory) {
            "Import staging storage is unavailable"
        }
        val sessionId = UUID.randomUUID().toString()
        val metadata = uris.mapIndexed { index, uri ->
            val values = sourceMetadata(uri)
            StagingSource(
                uri = uri,
                sourceName = values.first?.take(MAX_SOURCE_NAME_CHARS)
                    ?: "Selected enrichment ${index + 1}",
                declaredBytes = values.second
            )
        }
        val declaredTotal = metadata.mapNotNull(StagingSource::declaredBytes)
            .fold(0L) { total, bytes -> Math.addExact(total, bytes) }
        metadata.mapNotNull(StagingSource::declaredBytes).forEach { declared ->
            check(declared > 0L) { "The selected import is empty" }
        }
        check(declaredTotal <= MAX_SONG_INTAKE_BYTES) { "Import selection is too large" }
        val available = android.os.StatFs(stagingDirectory.absolutePath).availableBytes
        val allSizesKnown = metadata.all { it.declaredBytes != null }
        val initialRequirement = if (allSizesKnown) {
            declaredTotal + (metadata.mapNotNull(StagingSource::declaredBytes).maxOrNull() ?: 0L) + SONG_INTAKE_RESERVE_BYTES
        } else {
            SONG_INTAKE_RESERVE_BYTES
        }
        check(available >= initialRequirement) {
            "Not enough storage to stage this import"
        }

        val staged = mutableListOf<StagedArchive>()
        var copiedTotal = 0L
        val progressCadence = ImportCopyProgressCadence()
        metadata.forEachIndexed { index, source ->
            currentCoroutineContext().ensureActive()
            val part = File(stagingDirectory, "$sessionId-$index.part")
            val extension = source.sourceName.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it == "json" }
                ?: "zip"
            val target = File(stagingDirectory, "$sessionId-$index.$extension")
            writeJournal(
                staged + StagedArchive(part, source.sourceName),
                expectedRoot,
                "copying",
                index,
                metadata.size,
                stagingComplete = false
            )
            applicationContext.contentResolver.withCancellableProviderInput(
                source.uri
            ) { input, readCancellable ->
                FileOutputStream(part).use { fileOutput ->
                    val output = fileOutput.buffered()
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = readCancellable(buffer)
                        if (read < 0) break
                        backgroundWorkCoordinator.checkpoint()
                        copiedTotal = Math.addExact(copiedTotal, read.toLong())
                        check(copiedTotal <= MAX_SONG_INTAKE_BYTES) {
                            "Import selection is too large"
                        }
                        check(
                            android.os.StatFs(stagingDirectory.absolutePath).availableBytes >=
                                SONG_INTAKE_RESERVE_BYTES
                        ) { "Not enough storage to stage this import" }
                        output.write(buffer, 0, read)
                        val running = mutableState.value as? SharedEnrichmentImportState.Running
                        if (running != null && progressCadence.shouldPublish(System.nanoTime())) mutableState.value = running.copy(
                            phase = "Copying import", completed = index, total = metadata.size,
                            progressKind = ImportProgressKind.FILES, copiedBytes = copiedTotal,
                            totalBytes = declaredTotal.takeIf { allSizesKnown })
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            } ?: error("The selected import could not be opened")
            check(part.length() > 0L) { "The selected import is empty" }
            check(part.renameTo(target)) { "Could not commit the staged import" }
            staged += StagedArchive(target, source.sourceName)
            writeJournal(staged, expectedRoot, "copying", index + 1, metadata.size,
                stagingComplete = index + 1 == metadata.size)
        }
        writeJournal(staged, expectedRoot, "staged", 0, 0)
        staged
    }

    private fun writeJournal(
        files: List<StagedArchive>,
        root: RootAuthority,
        phase: String,
        completed: Int,
        total: Int,
        stagingComplete: Boolean = true
    ) {
        val atomic = AtomicFile(journalFile)
        val output = atomic.startWrite()
        try {
            val entries = JSONArray().apply {
                files.forEach { staged ->
                    put(
                        JSONObject()
                            .put("path", staged.file.name)
                            .put("sourceName", staged.sourceName)
                    )
                }
            }
            output.write(
                JSONObject()
                    .put("schemaVersion", ENRICHMENT_STAGING_JOURNAL_SCHEMA_VERSION)
                    .put("libraryId", root.libraryId)
                    .put("treeUri", root.treeUri)
                    .put("rootEpoch", root.epoch)
                    .put("stagedFiles", entries)
                    .put("stagingComplete", stagingComplete)
                    .put("phase", phase)
                    .put("completed", completed)
                    .put("total", total)
                    .put("updatedAt", System.currentTimeMillis())
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            )
            output.flush()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private suspend fun restoreStagedSession() {
        val journal = readAtomicJournal(
            journalFile,
            "IMPORT_STAGING_RECOVERY_PENDING"
        )?.value
        if (journal == null) {
            cleanupStaging()
            return
        }
        check(!enrichmentStagingJournalIsFuture(journal)) {
            "IMPORT_STAGING_FUTURE_SCHEMA"
        }
        val staged = decodeJournalFiles(journal) ?: run {
            cleanupStaging()
            return
        }
        if (!enrichmentStagingJournalIsComplete(journal, staged.size)) {
            cleanupStaging()
            return
        }
        val root = decodeEnrichmentImportJournalRoot(journal) ?: run {
            cleanupStaging()
            return
        }
        val files = staged.map(StagedArchive::file).toSet()
        cleanupStaging(files + journalFile)
        activeRoot = root
        mutableState.value = SharedEnrichmentImportState.AwaitingConfirmation(
            sources = staged.map { Uri.fromFile(it.file) },
            sourceNames = staged.map(StagedArchive::sourceName)
        )
    }

    private suspend fun sourceMetadata(uri: Uri): Pair<String?, Long?> =
        applicationContext.contentResolver.queryCancellable(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        ) { cursor ->
            if (!cursor.moveToFirst()) return@queryCancellable null
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getString)
                ?.trim()
                ?.takeIf(String::isNotBlank)
            val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)
            name to size
        } ?: (null to null)

    private fun stagedArchives(
        uris: List<Uri>,
        sourceNames: List<String>
    ): List<StagedArchive>? = uris.mapIndexed { index, uri ->
        val file = uri.takeIf { it.scheme == "file" }
            ?.path
            ?.let(::File)
            ?.takeIf {
                it.parentFile == stagingDirectory && it.isFile && it.length() > 0L &&
                    it.extension.lowercase() in setOf("zip", "json")
            }
            ?: return null
        StagedArchive(file, sourceNames.getOrNull(index) ?: file.name)
    }.takeIf { it.isNotEmpty() }

    private suspend fun awaitExactRoot(lease: TaskWorkGate.Lease): RootAuthority {
        val snapshot = roots.state.first { state ->
            state.ready || state.errorCode !in setOf("ROOT_CHECKING", "ROOT_RESTORING")
        }
        check(snapshot.ready) { "ROOT_UNAVAILABLE" }
        val root = roots.captureHandle()?.authority() ?: error("ROOT_UNAVAILABLE")
        requireAdmission(lease, root)
        activeRoot = root
        return root
    }

    private fun requireAdmission(lease: TaskWorkGate.Lease, root: RootAuthority) {
        if (!taskGate.isCurrent(lease)) throw CancellationException("IMPORT_ADMISSION_CLOSED")
        roots.requireSameLibrary(root)
    }

    private fun isSameRootIdentity(root: RootAuthority): Boolean = roots.state.value.let {
        it.libraryId == root.libraryId && it.treeUri?.toString() == root.treeUri && it.epoch == root.epoch
    }

    private fun isAdmissionCurrent(lease: TaskWorkGate.Lease, root: RootAuthority): Boolean =
        taskGate.isCurrent(lease) && runCatching { roots.requireSameLibrary(root) }.isSuccess

    private fun publishIfCurrent(
        lease: TaskWorkGate.Lease,
        root: RootAuthority,
        publish: () -> Unit
    ): Boolean {
        var published = false
        taskGate.runIfCurrent(lease) {
            if (runCatching { roots.requireSameLibrary(root) }.isSuccess) {
                publish()
                published = true
            }
        }
        return published
    }

    private fun decodeJournalFiles(journal: JSONObject): List<StagedArchive>? {
        val entries = journal.optJSONArray("stagedFiles") ?: return null
        if (entries.length() !in 1..MAX_SONG_INTAKE_ARCHIVES) return null
        val decoded = (0 until entries.length()).map { index ->
            val entry = entries.optJSONObject(index) ?: return null
            val path = entry.optString("path")
            val sourceName = entry.optString("sourceName").takeIf(String::isNotBlank) ?: path
            StagedArchive(File(stagingDirectory, path), sourceName)
        }
        if (decoded.map { it.file.name }.toSet().size != decoded.size) return null
        return decoded.takeIf { values -> values.all { validStagedFile(it.file) } }
    }

    private fun validStagedFile(file: File): Boolean =
        file.parentFile == stagingDirectory && file.isFile && file.length() > 0L &&
            file.extension.lowercase() in setOf("zip", "json")

    private suspend fun cleanupStaging(preserve: Set<File> = emptySet()) {
        val journal = readAtomicJournal(journalFile, "IMPORT_STAGING_RECOVERY_PENDING")?.value
        check(journal?.let(::enrichmentStagingJournalIsFuture) != true) {
            "IMPORT_STAGING_FUTURE_SCHEMA"
        }
        check(journal == null || decodeEnrichmentImportJournalRoot(journal) != null) { "IMPORT_STAGING_RECOVERY_PENDING" }
        val preserved = preserve.mapTo(hashSetOf()) { it.canonicalFile }
        importer.preserveBundleArchiveInputs(checkedPrivateChildren(stagingDirectory,
            "IMPORT_STAGING_CLEANUP_FAILED").filter {
            it.canonicalFile !in preserved && it.isFile && isOwnedStagingFile(it)
        })
        app.stemwise.data.cleanupExactPrivateChildren(stagingDirectory,
            "IMPORT_STAGING_CLEANUP_FAILED", preserve) {
            check(it.isFile && isOwnedStagingFile(it)) { "IMPORT_STAGING_UNKNOWN_ENTRY" }
            true
        }
    }

    private suspend fun cleanupStagingSerially(preserve: Set<File> = emptySet()) =
        stagingCleanupMutex.withLock { cleanupStaging(preserve) }

    private fun isOwnedStagingFile(file: File): Boolean =
        isEnrichmentStagingName(file.name)

    private fun postCommitFailureMessage(
        refreshFailure: Throwable?,
        cleanupFailure: Throwable?
    ): String = when {
        refreshFailure != null && cleanupFailure != null ->
            "Song data was updated, but the Library could not refresh and import cleanup needs attention."
        refreshFailure != null -> "Song data was updated, but the Library could not refresh."
        else -> "Song data was updated, but import cleanup needs attention."
    }

    companion object {
        private const val MAX_SOURCE_NAME_CHARS = 240
    }

    private data class StagingSource(
        val uri: Uri,
        val sourceName: String,
        val declaredBytes: Long?
    )

    private data class StagedArchive(
        val file: File,
        val sourceName: String
    )
}

internal fun enrichmentStagingJournalIsComplete(journal: JSONObject, stagedCount: Int): Boolean {
    return journal.opt("stagingComplete") == true && stagedCount in 1..MAX_SONG_INTAKE_ARCHIVES
}
