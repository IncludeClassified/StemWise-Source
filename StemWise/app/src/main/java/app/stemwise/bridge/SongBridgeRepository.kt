package app.stemwise.bridge

import android.content.Context
import android.os.SystemClock
import app.stemwise.launchRecoverable
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.RootAuthority
import app.stemwise.data.RootHandle
import app.stemwise.data.authority
import app.stemwise.data.ExternalLibraryRepository
import app.stemwise.data.LibraryDao
import app.stemwise.enrichment.SongEnrichmentImporter
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.work.AppTaskWorkGate
import app.stemwise.work.BridgeSongWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import app.stemwise.KeyedOperationMutex

class SongBridgeRepository internal constructor(
    private val context: Context,
    private val roots: LibraryRootManager,
    private val importer: SongEnrichmentImporter,
    private val external: ExternalLibraryRepository,
    private val dao: LibraryDao,
    private val backgroundWork: BackgroundWorkCoordinator,
    private val producerReady: (RootHandle) -> Boolean,
    private val blockedIds: suspend () -> Set<String>,
    private val prepareExplicitReadd: suspend (RootHandle, String) -> Unit,
    store: BridgeSongStore? = null,
    connections: BridgeConnectionStore? = null,
    private val transportFor: (BridgeConnectionProfile) -> SongBridgeTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val schedule: (String, Boolean) -> Unit = { id, replace -> BridgeSongWorker.enqueue(context, id, replace) }
) {
    private val suppliedStore = store
    private val suppliedConnections = connections
    private val store by lazy { suppliedStore ?: BridgeSongStore(File(context.noBackupFilesDir, "pc-song-jobs")) }
    private val connections by lazy { suppliedConnections ?: BridgeConnectionStore(File(context.noBackupFilesDir, "bridge-connection.json")) }
    private val queueLock = Mutex()
    private val owners = KeyedOperationMutex()
    private val preparationEffects = KeyedOperationMutex()
    private val operationOwner = BridgeOperationOwner()
    private val connectionGate = Any()
    private val transports = java.util.concurrent.ConcurrentHashMap<BridgeConnectionProfile, SongBridgeTransport>()
    private fun transport(profile: BridgeConnectionProfile): SongBridgeTransport = transports.computeIfAbsent(profile, transportFor)
    private val mutableState = MutableStateFlow(BridgeUiState(loading = true))
    val state: StateFlow<BridgeUiState> = mutableState.asStateFlow()
    private val mutableTasks = MutableStateFlow<List<BridgeSongTask>>(emptyList())
    val tasks: StateFlow<List<BridgeSongTask>> = mutableTasks.asStateFlow()
    private val workflow by lazy { BridgeSongWorkflow(this.store, ::transport, ::requireProducerRoot,
        install = { file, videoId, root ->
            val result = backgroundWork.withHeavyWork("Adding song from PC") {
                requireProducerRoot(root)
                importer.importPcSong(file, videoId, root).getOrThrow()
            }
            requireBridgeSongImport(result, videoId)
        }, verifyInstalled = { videoId, root ->
            backgroundWork.withHeavyWork("Verifying added song") {
                requireProducerRoot(root)
                val song = external.verifyPlaybackSong(roots.captureHandle() ?: throw BridgeLocalFailure(BridgeFailureCode.ROOT_UNAVAILABLE), videoId)
                check(song.sourcePath != null) { "BRIDGE_SONG_IMPORT_UNVERIFIED" }
                roots.requireSameLibrary(root)
            }
        }, changed = ::publishTasks,
        preserveArchive = { importer.preserveBundleArchiveInputs(listOf(it)) },
        explicitlyDeleted = { videoId, root ->
            requireProducerRoot(root)
            (videoId in blockedIds()).also { requireProducerRoot(root) }
        }, withDeliveryConfirmation = { videoId, root, confirm ->
            backgroundWork.withHeavyWork("Confirming added song") {
                requireProducerRoot(root)
                val handle = roots.captureHandle() ?: throw BridgeLocalFailure(BridgeFailureCode.ROOT_UNAVAILABLE)
                external.withVerifiedPlaybackSong(handle, videoId) { song ->
                    check(song.sourcePath != null) { "BRIDGE_SONG_IMPORT_UNVERIFIED" }
                    requireProducerRoot(root)
                    confirm()
                }
            }
        }) }

    init { scope.launch {
        synchronized(connectionGate) {
            runCatching { this@SongBridgeRepository.connections.current() }.fold(
                onSuccess = { mutableState.value = state.value.copy(connection = it, loading = false) },
                onFailure = { mutableState.value = state.value.copy(loading = false, error = "The saved Bridge connection could not be read. Enter its details again.") })
        }
        runCatching(::publishTasks).onFailure { mutableState.value = state.value.copy(error = bridgeFailureMessage(it)) }
    } }

    private fun requireProducerRoot(root: RootAuthority) {
        val handle = roots.captureHandle()?.takeIf { it.authority() == root }
            ?: throw BridgeLocalFailure(BridgeFailureCode.LIBRARY_CHANGED)
        try { roots.requireSameLibrary(root) }
        catch (error: IllegalStateException) { throw BridgeLocalFailure(BridgeFailureCode.LIBRARY_CHANGED, error) }
        checkBridge(producerReady(handle), BridgeFailureCode.RECOVERY_PENDING)
    }

    private fun publishTasks() {
        mutableTasks.value = (store.records().map { it.resumableTask } + store.damagedTasks())
            .sortedByDescending { it.createdAtEpochMs }
    }

    private fun retireReplacedReview(record: BridgeSongRecord) {
        check(record.canReplaceReviewedRequest && listOf("md", "input", "zip").none { store.file(record.task.id, it).exists() }) {
            "Only a reviewed, unsubmitted request without transfers can be replaced"
        }
        if (store.save(record.copy(remoteRetired = true, retired = true, task = record.task.copy(
            phase = BridgeSongPhase.CANCELLED, stage = "Replaced by a newly reviewed request", retryable = false)))) publishTasks()
    }

    private fun admitNewRecord() {
        check(store.damagedTasks().isEmpty()) { "Recover or discard the damaged saved task before adding another song." }
    }

    private fun connection(): BridgeConnectionProfile = synchronized(connectionGate) {
        connections.current() ?: throw BridgeLocalFailure(BridgeFailureCode.BRIDGE_CONNECTION_REQUIRED)
    }

    private fun publishConnection(profile: BridgeConnectionProfile, value: BridgeUiState) = synchronized(connectionGate) {
        if (connections.current() == profile) mutableState.value = value.copy(connection = profile)
    }

    suspend fun saveConnection(profile: BridgeConnectionProfile): Result<Unit> = withContext(Dispatchers.IO) {
        capture {
            synchronized(connectionGate) {
                connections.save(profile)
                mutableState.value = BridgeUiState(connection = profile)
            }
        }
    }

    /** Pairing is an explicit Settings action; saved requests must retain their existing receiver. */
    suspend fun pairAgain(): Result<Unit> = withContext(Dispatchers.IO) { capture {
        queueLock.withLock {
            val profile = connection()
            requireBridgePairingReplacement(store.records(), profile)
            transport(profile).pairAgain()
            publishConnection(profile, BridgeUiState(connection = profile))
        }
    } }

    suspend fun loadModelCatalog(profile: BridgeConnectionProfile? = null): Result<BridgeModelCatalog> = withContext(Dispatchers.IO) {
        capture { modelCatalog(profile ?: connection()) }
    }

    private suspend fun modelCatalog(profile: BridgeConnectionProfile): BridgeModelCatalog {
        val transport = transport(profile)
        transport.ensureApp()
        return fetchBridgeModelCatalog(transport::api)
    }

    /** Called only by the reviewed form's explicit Send to Bridge action. */
    suspend fun submitSong(submission: BridgeSongSubmission, profile: BridgeConnectionProfile? = null): Result<Unit> =
        queueSong(submission.link, null, submission, null, profile).map { Unit }

    suspend fun authorizeDraft(id: String, submission: BridgeSongSubmission, profile: BridgeConnectionProfile? = null): Result<Unit> =
        queueSong(submission.link, null, submission, id, profile).map { Unit }

    internal fun queuePlaylistSongs(expectedRoot: RootAuthority) { scope.launch { drainPlaylistSongs(expectedRoot) } }

    private suspend fun drainPlaylistSongs(expectedRoot: RootAuthority) {
        capture {
            val pending = dao.pendingPcSongs()
            playlistDiscoveryCapturedForTest?.invoke()
            for (song in pending) {
                roots.requireSameLibrary(expectedRoot)
                val resolved = song.youtubeVideoId in blockedIds() || queueSong(song.canonicalUrl, expectedRoot, null, null).getOrThrow()
                if (resolved) roots.withExactRootMutation(expectedRoot) { dao.clearPendingPcSong(song.id) }
            }
            publishTasks()
        }.onFailure { mutableState.value = state.value.copy(error = bridgeFailureMessage(it)) }
    }

    /** Discoveries only persist drafts. Only a reviewed submission may freeze and schedule a request. */
    private suspend fun queueSong(originalText: String, expectedRoot: RootAuthority?,
        submission: BridgeSongSubmission?, draftId: String?, reviewedProfile: BridgeConnectionProfile? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        capture {
            val videoId = bridgeVideoId(originalText)
            val lease = AppTaskWorkGate.instance.acquireLease() ?: error("Task is paused")
            val handle = roots.captureHandle() ?: throw BridgeLocalFailure(BridgeFailureCode.ROOT_UNAVAILABLE)
            checkBridge(producerReady(handle), BridgeFailureCode.RECOVERY_PENDING)
            val root = handle.authority()
            if (expectedRoot != null) { checkBridge(root == expectedRoot, BridgeFailureCode.LIBRARY_CHANGED); roots.requireSameLibrary(expectedRoot) }
            fun reviewedRecord(): BridgeSongRecord {
                val reviewed = store.record(checkNotNull(draftId)) ?: error("This draft is no longer available")
                checkBridge(reviewed.root.libraryId == root.libraryId && reviewed.root.treeUri == root.treeUri,
                    BridgeFailureCode.LIBRARY_CHANGED)
                check(reviewed.task.isDraft || reviewed.task.videoId == videoId) {
                    "This saved request is already authorized for another song. Resume that request instead."
                }
                return reviewed
            }
            if (draftId != null) reviewedRecord()
            val deleted = videoId in blockedIds()
            roots.requireSameLibrary(root)
            if (submission == null) check(!deleted) { "This song was permanently removed from this library" }
            if (!deleted && external.songs.value.any { it.id == videoId && it.sourcePath != null }) {
                roots.requireSameLibrary(root)
                val installed = external.verifyPlaybackSong(roots.captureHandle() ?: throw BridgeLocalFailure(BridgeFailureCode.ROOT_UNAVAILABLE), videoId)
                check(installed.sourcePath != null)
                return@capture true
            }
            val record = queueLock.withLock {
                if (submission == null && !dao.hasPendingPcSong(videoId)) {
                    return@withLock null
                }
                if (deleted) for (old in store.recordsFor(root, videoId)) {
                    owners.withKey(old.task.id) { workflow.settleExplicitDeletion(old.task.id, root) }
                }
                val retained = store.recordsFor(root, videoId)
                val existing = if (draftId == null) retainedBridgeRequest(retained, root, videoId) else {
                    val draft = reviewedRecord()
                    val live = retainedBridgeRequest(retained.filterNot { it.task.id == draftId }, root, videoId)
                    check(live == null || live.replacesRequestId == draftId) {
                        "This song already has a saved Bridge task. Open that task instead."
                    }
                    draft
                }
                val profile = if (submission != null) existing?.connection ?: reviewedProfile ?: connection() else null
                val id = existing?.task?.id
                suspend fun saveSelection(): BridgeSongRecord {
                    val current = id?.let { checkNotNull(store.record(it)) }
                    val successor = id?.let { oldId -> store.records().singleOrNull { it.replacesRequestId == oldId } }
                    if (successor != null) {
                        check(submission == successor.approval) { "This review already has an authorized replacement. Resume that request instead." }
                        return successor
                    }
                    if (current?.approval != null) {
                        if (submission != null && current.canReplaceReviewedRequest) {
                            check(listOf("md", "input", "zip").none { store.file(current.task.id, it).exists() }) {
                                "This request has retained transfers and cannot be replaced"
                            }
                            admitNewRecord()
                            val guide = context.assets.open("bridge/SEND_TO_AGENTS.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val now = System.currentTimeMillis()
                            val replacement = approveBridgeSong(newBridgeSong(root, originalText, guide, now),
                                submission, guide, now, requireNotNull(profile)).let {
                                it.copy(replacesRequestId = current.task.id, preparation = BridgeSendPreparation(),
                                    task = it.task.copy(phase = BridgeSongPhase.PREPARING, stage = "Preparing reviewed song request"))
                            }
                            roots.withExactRootMutation(root) {
                                check(AppTaskWorkGate.instance.runIfCurrent(lease) { store.save(replacement) }) { "Task is paused" }
                                reviewedReplacementSavedForTest?.invoke()
                                retireReplacedReview(current)
                            }
                            return replacement
                        }
                        if (submission != null) check(current.approval == submission) {
                            "This song already has an authorized job with different settings. Resume that job instead."
                        }
                        return current
                    }
                    if (current == null) admitNewRecord()
                    val guide = if (submission == null) "" else context.assets.open("bridge/SEND_TO_AGENTS.md")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val draft = current?.copy(root = root) ?: newBridgeSong(root, originalText, guide, System.currentTimeMillis())
                    val selected = if (submission == null) draft else
                        approveBridgeSong(draft, submission, guide, System.currentTimeMillis(), requireNotNull(profile)).let {
                            it.copy(preparation = BridgeSendPreparation(), task = it.task.copy(
                                phase = BridgeSongPhase.PREPARING, stage = "Preparing reviewed song request"))
                        }
                    roots.withExactRootMutation(root) {
                        check(AppTaskWorkGate.instance.runIfCurrent(lease) { store.save(selected) }) { "Task is paused" }
                    }
                    return selected
                }
                if (id == null) saveSelection() else owners.withKey(id) { saveSelection() }
            }
            if (submission != null) publishTasks()
            if (submission != null && record != null && record.approval != null && !record.task.terminal) schedule(record.task.id, false)
            record?.approval != null
        }
    }

    /** Called under the exact task owner; queue/storage locks are released before network calls. */
    private suspend fun prepareSend(initial: BridgeSongRecord): BridgeSongWorkProgress? {
        var record = initial
        fun save(next: BridgeSongRecord) {
            record = store.update(record.task.id) { if (it.task.cancelRequested) it else next }
            publishTasks()
        }
        // Claims serialize with cancellation; network waits never hold the cancellation gate.
        suspend fun claim(effect: suspend () -> Unit): Boolean = preparationEffects.withKey(record.task.id) {
            record = checkNotNull(store.record(record.task.id))
            if (record.task.cancelRequested) false else { effect(); true }
        }
        try {
            record.requireApproval()
            requireProducerRoot(record.root)
            val profile = checkNotNull(record.connection)
            val observation = state.value
            val observedAt = observation.observedAtElapsedMs
            val now = elapsedRealtime()
            val pcReady = observation.connection == profile && observedAt != null && now >= observedAt &&
                now - observedAt < observation.validForMs && observation.pcStatus == "reachable"
            var wake = false
            if (!claim {
                if (!checkNotNull(record.preparation).wakeClaimed && !pcReady) {
                    save(record.copy(preparation = record.preparation!!.copy(wakeClaimed = true),
                        task = record.task.copy(phase = BridgeSongPhase.WAKING, stage = "Waking PC", retryable = false)))
                    wake = true
                }
            }) return null
            if (wake) transport(profile).api("POST", "/v2/connection/wake")
            if (!claim {
                save(record.copy(task = record.task.copy(phase = BridgeSongPhase.PREPARING,
                    stage = "Checking reviewed processing options", retryable = false)))
            }) return null
            val catalog = modelCatalog(profile)
            if (!claim {
                try { catalog.validate(checkNotNull(record.approval)) }
                catch (changed: IllegalArgumentException) {
                    save(record.copy(task = record.task.copy(phase = BridgeSongPhase.REVIEW_REQUIRED,
                        stage = "Processing options need review", retryable = false,
                        message = bridgeFailureMessage(changed))))
                    return@claim
                }
                requireProducerRoot(record.root)
                val handle = checkNotNull(roots.captureHandle())
                if (record.task.videoId in blockedIds()) prepareExplicitReadd(handle, record.task.videoId)
                roots.requireSameLibrary(record.root)
                check(record.task.videoId !in blockedIds()) { "This song's deletion has not been retired" }
                save(record.copy(preparation = record.preparation!!.copy(catalogValidated = true),
                    task = record.task.copy(phase = BridgeSongPhase.SUBMITTING, stage = "Sending reviewed song request",
                        retryable = false, message = null)))
            }) return null
            return if (record.task.phase == BridgeSongPhase.REVIEW_REQUIRED) BridgeSongWorkProgress.COMPLETE else null
        } catch (cancelled: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) {
                save(record.copy(task = record.task.copy(phase = BridgeSongPhase.WAITING_PC,
                    stage = "Song preparation interrupted", retryable = true,
                    message = "Resume this saved request to check the PC. A claimed wake will not be repeated.")))
            }
            throw cancelled
        } catch (error: Exception) {
            save(record.copy(task = record.task.copy(phase = BridgeSongPhase.WAITING_PC,
                stage = "Waiting for PC", retryable = true, message = bridgeFailureMessage(error))))
            return if (record.task.cancelRequested) null else BridgeSongWorkProgress.RETRY
        }
    }

    suspend fun retryWake(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        capture {
            owners.withKey(id) {
                val record = store.records().single { it.task.id == id }
                check(record.resumableTask.canRetryWake) { "This task cannot start another wake attempt" }
                requireProducerRoot(record.root)
                store.save(record.copy(preparation = checkNotNull(record.preparation).copy(wakeClaimed = false),
                    task = record.task.copy(phase = BridgeSongPhase.PREPARING,
                        stage = "Preparing another authorized wake attempt", retryable = false)))
            }
            publishTasks()
            schedule(id, true)
        }
    }

    private suspend fun resumePending() = withContext(Dispatchers.IO) {
        publishTasks()
        store.records().filter { it.task.phase == BridgeSongPhase.RETIRING || it.task.phase == BridgeSongPhase.IMPORTED && !it.retired }.forEach { pending ->
            val retired = operationOwner.run {
                owners.withKey(pending.task.id) {
                    if (store.records().none { it.task.id == pending.task.id }) true
                    else workflow.retire(pending.task.id)
                }
            }
            if (!retired) schedule(pending.task.id, true)
        }
        store.records().filter { it.task.cancelRequested && !it.task.terminal }.forEach { schedule(it.task.id, true) }
        val handle = roots.captureHandle() ?: return@withContext
        if (!producerReady(handle)) return@withContext
        val root = handle.authority()
        queueLock.withLock {
            for (replacement in store.records().filter { it.replacesRequestId != null &&
                it.root.libraryId == root.libraryId && it.root.treeUri == root.treeUri }) {
                owners.withKey(checkNotNull(replacement.replacesRequestId)) {
                    val prior = store.records().singleOrNull { it.task.id == replacement.replacesRequestId }
                    if (prior?.canReplaceReviewedRequest == true) roots.withExactRootMutation(root) { retireReplacedReview(prior) }
                }
            }
        }
        drainPlaylistSongs(root)
        for (pending in store.records().filter { it.approval != null && !it.task.terminal && it.task.phase != BridgeSongPhase.REVIEW_REQUIRED &&
            it.root.libraryId == root.libraryId && it.root.treeUri == root.treeUri }) {
            val resume = operationOwner.run {
                owners.withKey(pending.task.id) {
                    workflow.resumeForLibrary(pending.task.id, root)
                }
            }
            if (resume) schedule(pending.task.id, true)
        }
    }

    suspend fun taskDiagnostics(id: String): Result<String> = withContext(Dispatchers.IO) {
        capture { bridgeTaskDiagnostics(store.records().single { it.task.id == id }) }
    }

    suspend fun cancel(id: String): Result<Unit> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        withContext(Dispatchers.IO) {
            capture {
                check(!store.records().single { it.task.id == id }.task.isDraft) { "This task cannot be cancelled" }
                preparationEffects.withKey(id) { workflow.requestCancellation(id) }
                publishTasks()
                schedule(id, true)
            }
        }
    }.await()

    internal fun canAdvanceWithoutLibrary(id: String): Boolean = store.records().any {
        it.task.id == id && (it.task.isDraft || it.task.cancelRequested || it.task.phase in setOf(BridgeSongPhase.IMPORTED, BridgeSongPhase.RETIRING))
    }

    suspend fun retry(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        capture {
            owners.withKey(id) {
                val old = store.records().single { it.task.id == id }
                check(old.resumableTask.canRetry) { "This task cannot be resumed" }
                if (old.task.phase == BridgeSongPhase.RETIRING) {
                    store.save(old.copy(task = old.task.copy(retryable = false)))
                    return@withKey
                }
                if (old.task.cancelRequested) {
                    store.save(old.copy(task = old.task.copy(phase = BridgeSongPhase.CANCELLING, retryable = false, message = null)))
                    return@withKey
                }
                val root = roots.captureHandle()?.authority() ?: throw BridgeLocalFailure(BridgeFailureCode.ROOT_UNAVAILABLE)
                checkBridge(root.libraryId == old.root.libraryId && root.treeUri == old.root.treeUri, BridgeFailureCode.LIBRARY_CHANGED)
                val output = store.file(id, "zip")
                if (old.canRetryImport) {
                    checkBridge(checkNotNull(old.output).matches(output), BridgeFailureCode.BRIDGE_LOCAL_OUTPUT_CHANGED)
                } else if (old.output != null && output.exists() && !old.output.matches(output)) {
                    check(output.canonicalFile.parentFile == store.directory.canonicalFile && output.delete()) { "BRIDGE_CLEANUP_REFUSED" }
                }
                // Explicit recovery may rebind a restored generation of the same selected library.
                store.save(old.copy(root = root,
                    task = old.task.copy(phase = if (old.preparation?.catalogValidated == false)
                            BridgeSongPhase.PREPARING else BridgeSongPhase.QUEUED,
                        retryable = false, message = if (old.canRetryImport || old.preparation?.catalogValidated == false)
                            old.resumableTask.message else null)))
            }
            publishTasks()
            schedule(id, true)
        }
    }

    suspend fun forget(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        capture {
            suspend fun retireRecord() = operationOwner.run {
                owners.withKey(id) {
                    if (id.startsWith("damaged:")) {
                        importer.preserveBundleArchiveInputs(store.damagedTransferCopies(id))
                        store.discardDamaged(id)
                        publishTasks()
                        return@withKey
                    }
                    val record = store.records().single { it.task.id == id }
                    check(record.task.canForget) { "BRIDGE_CLEANUP_REFUSED" }
                    if (record.task.isDraft) {
                        val root = roots.captureHandle()?.authority() ?: throw BridgeLocalFailure(BridgeFailureCode.ROOT_UNAVAILABLE)
                        checkBridge(root.libraryId == record.root.libraryId && root.treeUri == record.root.treeUri, BridgeFailureCode.LIBRARY_CHANGED)
                        // Queue ownership covers both clearing discovery intent and retiring its draft.
                        roots.withExactRootMutation(root) {
                            dao.pendingPcSongs().filter { it.youtubeVideoId == record.task.videoId }
                                .forEach { dao.clearPendingPcSong(it.id) }
                        }
                    }
                    if (!workflow.retire(id, roots.captureAuthority())) schedule(id, true)
                }
            }
            if (!id.startsWith("damaged:") && store.records().single { it.task.id == id }.task.isDraft) {
                queueLock.withLock { retireRecord() }
            } else retireRecord()
            roots.captureHandle()?.authority()?.let(::queuePlaylistSongs)
            Unit
        }
    }

    internal suspend fun advance(id: String): BridgeSongWorkProgress = operationOwner.run {
        owners.withKey(id) {
        val record = store.records().singleOrNull { it.task.id == id } ?: return@withKey BridgeSongWorkProgress.COMPLETE
        if (record.approval == null) return@withKey BridgeSongWorkProgress.COMPLETE
        val handle = roots.captureHandle()
        if (!record.task.cancelRequested && record.task.phase !in setOf(BridgeSongPhase.IMPORTED, BridgeSongPhase.RETIRING) && handle != null && handle.authority() == record.root &&
            !producerReady(handle)) return@withKey BridgeSongWorkProgress.PENDING
        if (record.preparation?.catalogValidated == false && !record.task.cancelRequested && !record.task.terminal) {
            prepareSend(record)?.let { return@withKey it }
        }
        val progress = if (record.task.phase == BridgeSongPhase.IMPORTED) {
            if (!workflow.retire(id)) return@withKey BridgeSongWorkProgress.RETRY
            BridgeSongWorkProgress.COMPLETE
        } else workflow.advance(id)
        store.records().singleOrNull { it.task.id == id && it.task.phase == BridgeSongPhase.IMPORTED && !it.retired }?.let {
            if (!workflow.retire(id)) return@withKey BridgeSongWorkProgress.RETRY
            roots.captureHandle()?.authority()?.let(::queuePlaylistSongs)
        }
        if (store.records().none { it.task.id == id }) roots.captureHandle()?.authority()?.let(::queuePlaylistSongs)
        progress
        }
    }

    internal suspend fun cancelForLibraryChange() = operationOwner.closeAndJoin()

    @Volatile internal var playlistDiscoveryCapturedForTest: (suspend () -> Unit)? = null
    @Volatile internal var reviewedReplacementSavedForTest: (() -> Unit)? = null


    internal fun requestResume(): kotlinx.coroutines.Job {
        mutableState.value = state.value.copy(error = null)
        return scope.launchRecoverable(
            onFailure = { mutableState.value = state.value.copy(error = bridgeFailureMessage(it)) }
        ) { resumePending() }
    }

    internal fun onLibrarySwitchFinished() {
        operationOwner.reopen()
        requestResume()
    }

    suspend fun refreshConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        var selected: BridgeConnectionProfile? = null
        capture {
            val profile = connection().also { selected = it }
            val transport = transport(profile)
            publishConnection(profile, state.value.copy(loading = true, loginUrl = null, error = null))
            val started = elapsedRealtime()
            val local = transport.status()
            val backend = local.optString("backendState")
            if (backend != "Running" || local.optBoolean("enrollmentExpired")) {
                publishConnection(profile, BridgeUiState(enrollmentRequired = local.optBoolean("enrollmentExpired") ||
                    backend in setOf("NeedsLogin", "NeedsMachineAuth", "NoState"),
                    error = "Authorize this StemWise installation, then check the connection."))
            } else {
                val data = transport.api("GET", "/v2/connection")
                val valid = data.getLong("validForMs")
                val now = elapsedRealtime()
                val elapsed = now - started
                val remaining = if (elapsed < 0L) 0L else (valid - elapsed).coerceAtLeast(0L)
                val auth = data.optJSONObject("codexAuth")
                val connectionIssue = auth?.takeUnless { it.optString("state") == "ready" }?.let {
                    it.optString("code").takeIf(String::isNotBlank)
                        ?: it.optString("state").takeIf(String::isNotBlank) ?: "native_auth_unavailable"
                }
                publishConnection(profile, BridgeUiState(piStatus = if (remaining > 0) data.getString("state") else "Unknown",
                    pcStatus = if (remaining > 0) data.getString("pcState") else "Unknown",
                    observedAtElapsedMs = now, validForMs = remaining, connectionIssue = connectionIssue))
                transport.ensureApp()
                requestResume()
            }
            Unit
        }.also { result -> if (result.isFailure) {
            val failed = state.value.copy(loading = false, piStatus = "Unknown", pcStatus = "Unknown",
                error = bridgeFailureMessage(checkNotNull(result.exceptionOrNull())))
            selected?.let { publishConnection(it, failed) } ?: run { mutableState.value = failed }
        } }
    }

    suspend fun enroll(): Result<String?> = withContext(Dispatchers.IO) {
        capture {
            val profile = connection()
            val transport = transport(profile)
            transport.login()
            var url: String? = null
            repeat(10) {
                if (url == null) {
                    url = transport.status().optString("loginUrl").takeIf(String::isNotBlank)
                    if (url == null) delay(200)
                }
            }
            url?.let {
                val uri = java.net.URI(it)
                check(uri.scheme == "https" && uri.host == "login.tailscale.com" && uri.userInfo == null && uri.port in setOf(-1, 443)) {
                    "Invalid bridge authorization URL"
                }
            }
            url
        }
    }

    suspend fun wakePc(profile: BridgeConnectionProfile? = null): Result<Unit> = withContext(Dispatchers.IO) {
        // The separate Settings action authorizes only this profile's manual wake.
        capture {
            val selected = profile ?: connection()
            transportFor(selected).api("POST", "/v2/connection/wake")
            if (state.value.connection == selected) refreshConnection().getOrThrow()
            Unit
        }
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
}

internal fun retainedBridgeRequest(records: List<BridgeSongRecord>, root: RootAuthority,
    videoId: String): BridgeSongRecord? = records.lastOrNull {
    val unresolvedRemote = it.submission != BridgeSubmission.UNSENT && !it.remoteRetired &&
        !it.remoteTerminalObserved && it.task.phase !in setOf(BridgeSongPhase.IMPORTED,
            BridgeSongPhase.EXPIRED, BridgeSongPhase.REMOVED)
    val unresolvedRetirement = it.task.phase == BridgeSongPhase.RETIRING && (!it.retired || !it.remoteRetired)
    it.root.libraryId == root.libraryId && it.root.treeUri == root.treeUri && it.task.videoId == videoId &&
        (!it.resumableTask.terminal || unresolvedRemote || unresolvedRetirement)
}

/** Preserve importer diagnosis after validating the exact single-song batch envelope. */
internal fun requireBridgeSongImport(result: app.stemwise.enrichment.EnrichmentImportResult, videoId: String) {
    check(result.items.size == 1 && result.items.single().videoId == videoId) { "BRIDGE_SONG_IMPORT_FAILED" }
    val item = result.items.single()
    if (!item.isSuccess) throw app.stemwise.enrichment.EnrichmentImportException(
        item.errorCode ?: "BRIDGE_SONG_IMPORT_FAILED")
}

internal fun requireBridgePairingReplacement(records: List<BridgeSongRecord>, profile: BridgeConnectionProfile) {
    check(records.none { it.connection == profile && it.approval != null && !it.retired }) {
        "Resolve or remove this connection's saved Bridge tasks before pairing again. Their original receiver must remain unchanged."
    }
}
