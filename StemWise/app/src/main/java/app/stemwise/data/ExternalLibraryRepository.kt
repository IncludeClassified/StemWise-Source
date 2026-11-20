package app.stemwise.data

import android.database.ContentObserver
import app.stemwise.launchRecoverable
import android.content.Context
import android.net.Uri
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import app.stemwise.enrichment.EnrichmentInstallPhase
import app.stemwise.enrichment.requireEnrichmentRecoveryState
import app.stemwise.enrichment.isEnrichmentStagingName
import app.stemwise.enrichment.ENRICHMENT_STAGING_JOURNAL_NAME
import app.stemwise.enrichment.installedMediaIdentities
import app.stemwise.enrichment.parseStrictManifestJson
import app.stemwise.enrichment.PortableEnrichmentTransaction
import app.stemwise.enrichment.selectPortableEnrichmentTransaction
import app.stemwise.enrichment.installedEnrichmentVerificationCache
import app.stemwise.enrichment.validateEnrichmentOriginalBinding
import app.stemwise.enrichment.validateEnrichmentCoverPng
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.integration.SOURCE_JOB_AUTHORITY_FILES
import app.stemwise.integration.queryCancellable
import app.stemwise.integration.withCancellableProviderAssetFileDescriptor
import app.stemwise.integration.withCancellableProviderOutput
import app.stemwise.integration.withCancellableProviderInput
import app.stemwise.integration.runCancellableBlockingOperation
import app.stemwise.suspendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class PackageProblem(
    val packageId: String,
    val code: String,
    val message: String
)

/** Provider/root failures invalidate the whole scan; package validation failures do not. */
internal fun canonicalProjectionRootAccessFailure(error: Throwable): LibraryRootException? {
    if (error is LibraryRootException && error.code == "PACKAGE_DIRECTORY_UNAVAILABLE") return null
    val causes = generateSequence(error) { it.cause }.toList()
    causes.filterIsInstance<LibraryRootException>().firstOrNull {
            it.code == "ROOT_PERMISSION_LOST" ||
            it.code == "ROOT_UNAVAILABLE" ||
            it.code == "ROOT_LAYOUT_UNSUPPORTED"
    }?.let { return it }
    val code = when {
        causes.any { it is SecurityException } -> "ROOT_PERMISSION_LOST"
        else -> return null
    }
    return LibraryRootException(code, "Reconnect the Library folder").also {
        it.addSuppressed(error)
    }
}

private fun retryablePackageReadFailure(error: Throwable): Boolean =
    error is IOException && error !is app.stemwise.enrichment.EnrichmentImportException &&
        canonicalProjectionRootAccessFailure(error) == null

internal enum class LibraryProjectionAvailability {
    /** The selected root has not completed verification; Room projection is hidden. */
    RESTORING,
    /** Exact-root Room metadata is visible; every playback selection still requires opened-byte verification. */
    PROJECTED,
    /** The current root completed a canonical scan, including a valid scan with zero songs. */
    VERIFIED,
    /** The current root could not supply a trustworthy canonical projection. */
    UNAVAILABLE
}

internal class LibraryProjectionAvailabilityTracker {
    private val mutableState = MutableStateFlow(LibraryProjectionAvailability.RESTORING)
    val state: StateFlow<LibraryProjectionAvailability> = mutableState.asStateFlow()

    fun beginRestoration() {
        mutableState.value = LibraryProjectionAvailability.RESTORING
    }

    fun markProjected() {
        mutableState.value = LibraryProjectionAvailability.PROJECTED
    }

    fun markVerified() {
        mutableState.value = LibraryProjectionAvailability.VERIFIED
    }

    fun markUnavailable() {
        mutableState.value = LibraryProjectionAvailability.UNAVAILABLE
    }
}

internal data class PlaybackLibrarySnapshot(val songs: List<Song>, val snapshotReady: Boolean)

internal fun reusableFlatAssetObservation(
    documentUri: String,
    sizeBytes: Long?,
    lastModifiedEpochMs: Long?,
    cached: AssetObservationEntity?,
    measuredSha256: String? = null
): Boolean {
    val modified = lastModifiedEpochMs ?: return false
    val size = sizeBytes ?: return false
    return modified > 0L && size > 0L &&
        cached?.documentUri == documentUri &&
        cached.byteLength == size &&
        cached.lastModifiedEpochMs == modified &&
        cached.expectedSha256.length == 64 &&
        cached.expectedSha256.all { it in '0'..'9' || it in 'a'..'f' } &&
        measuredSha256 == cached.expectedSha256
}

internal fun committedCanonicalSong(
    projected: Song,
    commit: CanonicalPackageProjectionCommit
): Song {
    val entity = commit.song
    val identity = ownerVisibleSongIdentity(
        entity.youtubeVideoId,
        entity.displayTitle,
        entity.displayArtist,
        entity.sourceTitle,
        entity.sourceArtist
    )
    val analysis = commit.analysis
    val title = identity.title
    return projected.copy(
        title = title,
        artist = identity.artist,
        durationMs = entity.durationMs,
        state = runCatching { SongState.valueOf(entity.highLevelState) }
            .getOrDefault(SongState.DISCOVERED),
        year = entity.displayYear.orEmpty(),
        album = entity.displayAlbum.orEmpty(),
        bpm = analysis?.primaryBpm?.toFloat(),
        key = analysis?.musicalKey,
        keyStatus = analysis?.keyStatus,
        keyReason = analysis?.keyReason,
        meter = analysis?.likelyMeter,
        artworkGlyph = artworkGlyph(title),
        canonicalUrl = entity.canonicalUrl,
        additionalInfo = parseRoomAdditionalInfo(entity.additionalInfoJson),
        artworkSource = publishedArtworkSource(
            entity.artworkPath,
            entity.additionalInfoJson,
            entity.youtubeVideoId
        ),
        mediaRevision = entity.manifestFileSha256.orEmpty()
    )
}

/**
 * Refreshes canonical SAF projection and performs fenced publication/recovery of selected-root packages.
 */
class ExternalLibraryRepository(
    private val context: Context,
    private val roots: LibraryRootManager,
    private val dao: LibraryDao,
    private val sourceBindings: SourceBindingRegistry,
    private val mutations: PackageMutationCoordinator,
    private val backgroundWorkCoordinator: BackgroundWorkCoordinator,
    private val canonicalWriteObserver: (packageWrites: Int) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val enrichmentRecovery = EnrichmentTransactionRecovery(context, roots, backgroundWorkCoordinator, mutations)
    private val inventorySnapshotPersistenceLock = Any()
    private val storageScanner = LibraryStorageScanner(context, backgroundWorkCoordinator)
    private val reconcileAdmissionLock = Any()
    private var reconcileAdmissionClosed = false
    @Volatile private var eventualReconcileJob: Job? = null
    @Volatile private var providerRefreshJob: Job? = null
    private var manualCanonicalRefreshJob: Job? = null
    @Volatile internal var beforeProviderPackageRefreshForTest: (suspend (String) -> Unit)? = null
    private var observedProviderTreeUri: Uri? = null
    private val providerObservationLock = Any()
    private var providerObservationCursors = emptyList<android.database.Cursor>()
    private val providerChangeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            requestProviderRefresh(uri)
        }
    }
    private val pendingProviderUris = linkedSetOf<Uri>()
    private val pendingProviderPackages = linkedMapOf<String, Uri>()
    private var providerStructureChanged = false

    /** Repeated manual requests join their exact worker without redirtying its consumed pass. */
    internal fun requestCanonicalStorageRefresh(): Job = synchronized(reconcileAdmissionLock) {
        manualCanonicalRefreshJob?.takeIf { it.isActive }?.let { return@synchronized it }
        requestProviderRefresh().also { manualCanonicalRefreshJob = it }
    }

    internal fun requestProviderRefresh(changedUri: Uri? = null): kotlinx.coroutines.Job = synchronized(reconcileAdmissionLock) {
        if (reconcileAdmissionClosed) return@synchronized Job().apply { complete() }
        val expectedRoot = roots.captureHandle() ?: return@synchronized Job().apply { complete() }
        if (changedUri == null) providerStructureChanged = true else pendingProviderUris += changedUri
        providerRefreshJob?.takeIf { it.isActive }?.let { return@synchronized it }
        scope.launchRecoverable(onFailure = { error ->
            val current = roots.state.value
            if (current.libraryId == expectedRoot.libraryId &&
                current.treeUri == expectedRoot.treeUri && current.epoch == expectedRoot.epoch) {
                canonicalReconcileLifecycle.failWithCachedInventory(error)
                storageRefreshLifecycle.failWithCachedInventory(error)
            }
        }) {
            var retryPasses = 0
            while (true) {
                roots.requireSameLibrary(expectedRoot)
                delay(PROVIDER_REFRESH_DEBOUNCE_MS)
                roots.requireSameLibrary(expectedRoot)
                val (structural, uris) = synchronized(reconcileAdmissionLock) {
                    (providerStructureChanged to pendingProviderUris.toList()).also {
                        providerStructureChanged = false
                        pendingProviderUris.clear()
                    }
                }
                var completed = false
                try {
                    val packages = linkedMapOf<String, MutableList<Uri>>()
                    var unknown = structural
                    val knownUris = hashSetOf<String>()
                    synchronized(reconcileAdmissionLock) {
                        for ((id, uri) in pendingProviderPackages) {
                            packages.getOrPut(id) { mutableListOf() } += uri
                            knownUris += uri.toString()
                        }
                        pendingProviderPackages.clear()
                    }
                    for (batch in uris.toList().chunked(450)) {
                        for (known in dao.canonicalPackagesForDocuments(batch.map(Uri::toString))) {
                            knownUris += known.documentUri
                            packages.getOrPut(known.packageId) { mutableListOf() } += Uri.parse(known.documentUri)
                        }
                    }
                    if (uris.any { it.toString() !in knownUris }) unknown = true
                    if (unknown) {
                        invalidateProviderProjection()
                        reconcile()
                        refreshProviderObservation(expectedRoot)
                    } else {
                        var retryFailure: Throwable? = null
                        for ((packageId, changedUris) in packages) {
                            when (val outcome = refreshProviderPackage(expectedRoot, packageId)) {
                                is ProviderPackageRefresh.Retry -> {
                                    synchronized(reconcileAdmissionLock) {
                                        pendingProviderUris += changedUris
                                        pendingProviderPackages[packageId] = changedUris.first()
                                    }
                                    retryFailure = outcome.error
                                }
                                else -> Unit
                            }
                        }
                        markInventoryDirtyAndScheduleStorageRefresh()
                        if (retryFailure != null && ++retryPasses >= 3) throw retryFailure
                    }
                    completed = true
                } finally {
                    if (!completed) synchronized(reconcileAdmissionLock) {
                        providerStructureChanged = providerStructureChanged || structural
                        pendingProviderUris += uris
                    }
                }
                val more = synchronized(reconcileAdmissionLock) {
                    (providerStructureChanged || pendingProviderUris.isNotEmpty()).also {
                        if (!it) providerRefreshJob = null
                    }
                }
                if (!more) break
            }
        }.also { providerRefreshJob = it }
    }

    private sealed interface ProviderPackageRefresh {
        data object Valid : ProviderPackageRefresh
        data object Absent : ProviderPackageRefresh
        data object Rejected : ProviderPackageRefresh
        data class Retry(val error: Throwable) : ProviderPackageRefresh
    }

    private suspend fun refreshProviderPackage(root: RootHandle, packageId: String): ProviderPackageRefresh =
        mutations.withPackageRead(root, packageId) {
            beforeProviderPackageRefreshForTest?.invoke(packageId)
            try {
                verifyPackageOutsideRootLock(root, packageId)
                ProviderPackageRefresh.Valid
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                roots.requireSameLibrary(root)
                if (error.message == "PACKAGE_NOT_FOUND" ||
                    error is LibraryRootException && error.code == "PACKAGE_DIRECTORY_UNAVAILABLE") {
                    // A failed direct lookup is not absence; require a completed parent inventory.
                    return@withPackageRead mutations.withRootMutation {
                        val present = roots.checkedRootDirectories(root).any { it.displayName == packageId }
                        if (present) ProviderPackageRefresh.Retry(error) else {
                            withdrawInvalidProjection(root, packageId, IllegalStateException("PACKAGE_NOT_FOUND").also {
                                it.addSuppressed(error)
                            })
                            ProviderPackageRefresh.Absent
                        }
                    }
                }
                canonicalProjectionRootAccessFailure(error)?.let { throw it }
                if (error is IOException && error !is app.stemwise.enrichment.EnrichmentImportException ||
                    error.message in setOf("SOURCE_AUDIO_CHANGED", "SOURCE_BINDING_SCAN_CHANGED")) {
                    return@withPackageRead ProviderPackageRefresh.Retry(error)
                }
                if (error is app.stemwise.enrichment.EnrichmentImportException ||
                    error is IllegalStateException && error.message?.let(ERROR_CODE::matches) == true) {
                    mutations.withRootMutation { withdrawInvalidProjection(root, packageId, error) }
                    ProviderPackageRefresh.Rejected
                } else throw error
            }
        }

    internal suspend fun invalidateProviderProjection() = mutations.withRootMutation {
        sourceBindings.invalidateScanAuthority()
        storageTraversalCoordinator.invalidateCanonical {
            // Keep the last complete projection; UNAVAILABLE prevents playback admission until rescan.
            projectionAvailabilityTracker.markUnavailable()
        }
    }

    private val projectionAvailabilityTracker = LibraryProjectionAvailabilityTracker()
    @Volatile private var projectionSessionGeneration = 0L
    private val mutableSongs = MutableStateFlow<List<Song>>(emptyList())
    private val songIndex = SongDisplayIndex()
    @Volatile private var songSnapshotDirty = false
    @Volatile internal var songSnapshotPublishedForTest: (() -> Unit)? = null

    private fun publishSongSnapshot() {
        if (!songSnapshotDirty) return
        mutableSongs.value = songIndex.snapshot()
        songSnapshotDirty = false
        songSnapshotPublishedForTest?.invoke()
    }

    private fun removeProjectedSong(packageId: String, publishSnapshot: Boolean = true) {
        if (pendingProjectionReads.remove(packageId) && pendingProjectionReads.isEmpty() &&
            projectionAvailabilityTracker.state.value == LibraryProjectionAvailability.PROJECTED) {
            projectionAvailabilityTracker.markVerified()
        }
        if (songIndex.remove(packageId)) songSnapshotDirty = true
        if (publishSnapshot) publishSongSnapshot()
    }
    private var projectionRevision = 0L
    private val packageProjectionRevisions = mutableMapOf<String, Long>()
    private val pendingProjectionReads = mutableSetOf<String>()
    private fun recordPackageProjectionChange(packageId: String) {
        packageProjectionRevisions[packageId] = ++projectionRevision
    }
    val songs: StateFlow<List<Song>> = mutableSongs.asStateFlow()
    internal val projectionAvailability: StateFlow<LibraryProjectionAvailability> = projectionAvailabilityTracker.state

    internal suspend fun playbackSnapshot(): PlaybackLibrarySnapshot = mutations.withRootMutation {
        PlaybackLibrarySnapshot(mutableSongs.value, roots.state.value.ready &&
            projectionAvailabilityTracker.state.value in setOf(LibraryProjectionAvailability.PROJECTED, LibraryProjectionAvailability.VERIFIED))
    }
    private val mutableProblems = MutableStateFlow<List<PackageProblem>>(emptyList())
    val problems: StateFlow<List<PackageProblem>> = mutableProblems.asStateFlow()
    /** Canonical verification and metadata measurement retain their distinct single flights. */
    private val canonicalReconcileLifecycle = LibraryStorageRefreshLifecycle()
    internal val canonicalRefreshState: StateFlow<LibraryStorageRefreshState> = canonicalReconcileLifecycle.state
    private val storageRefreshLifecycle = LibraryStorageRefreshLifecycle()
    private val storageTraversalCoordinator = LibraryStorageTraversalCoordinator()
    private val storageRefreshRequestOwner = LibraryStorageRefreshRequestOwner(
        applicationScope = scope,
        refresh = ::refreshStorageInventory
    )
    val storageRefreshState: StateFlow<LibraryStorageRefreshState> =
        storageRefreshLifecycle.state
    private val mutableStemSets =
        MutableStateFlow<Map<String, List<SeparationSetEntity>>>(emptyMap())
    private val mutableStems =
        MutableStateFlow<Map<String, List<StemAssetEntity>>>(emptyMap())

    init {
        // Root permission can be revoked while a refresh owner awaits heavy-work admission.
        // End that visible generation immediately; the scan revalidates its captured root before
        // doing any work, so it cannot revive an invalidated generation later.
        scope.launch {
            try {
            roots.state.collect { rootState ->
                val nextTreeUri = rootState.treeUri?.takeIf { rootState.ready }
                if (nextTreeUri != observedProviderTreeUri) {
                    synchronized(reconcileAdmissionLock) {
                        pendingProviderUris.clear()
                        pendingProviderPackages.clear()
                        providerStructureChanged = false
                    }
                    runCatching { context.contentResolver.unregisterContentObserver(providerChangeObserver) }
                    synchronized(providerObservationLock) {
                        providerObservationCursors.forEach { runCatching(it::close) }
                        providerObservationCursors = emptyList()
                    }
                    observedProviderTreeUri = nextTreeUri
                    if (nextTreeUri != null) {
                        runCatching {
                            context.contentResolver.registerContentObserver(
                                nextTreeUri,
                                true,
                                providerChangeObserver
                            )
                        }
                        roots.captureHandle(rootState)?.let { root ->
                            try { refreshProviderObservation(root) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Foreground/manual canonical refresh remains available. */ }
                        }
                    }
                }
                if (
                    !rootState.ready &&
                    rootState.errorCode?.let(ROOT_ACCESS_ERROR_CODES::contains) == true
                ) {
                    val failure = LibraryRootException(
                        rootState.errorCode,
                        "Reconnect the Library folder"
                    )
                    canonicalReconcileLifecycle.failWithCachedInventory(failure)
                    storageRefreshLifecycle.failWithCachedInventory(failure)
                }
            }
            } finally {
                synchronized(providerObservationLock) {
                    providerObservationCursors.forEach { runCatching(it::close) }
                    providerObservationCursors = emptyList()
                }
                runCatching { context.contentResolver.unregisterContentObserver(providerChangeObserver) }
            }
        }
    }

    /** SAF watches belong to open directory cursors; document paths are not tree-URI descendants. */
    private suspend fun refreshProviderObservation(root: RootHandle) {
        roots.requireSameLibrary(root)
        val directories = roots.checkedRootDirectories(root)
        val ids = listOf(DocumentsContract.getDocumentId(root.rootDocument.uri)) + directories.map { it.documentId }
        val opened = mutableListOf<android.database.Cursor>()
        try {
            for (id in ids) {
                roots.requireSameLibrary(root)
                val uri = DocumentsContract.buildChildDocumentsUriUsingTree(root.treeUri, id)
                val signal = android.os.CancellationSignal()
                val owned = java.util.concurrent.atomic.AtomicReference<android.database.Cursor?>()
                var retained = false
                try {
                    val cursor = runCancellableBlockingOperation(cancelExact = {
                        signal.cancel()
                        owned.getAndSet(null)?.close()
                    }) {
                        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                            null, null, null, signal)?.also { owned.set(it) }
                    } ?: continue
                    currentCoroutineContext().ensureActive()
                    cursor.registerContentObserver(providerChangeObserver)
                    opened += cursor
                    retained = true
                } finally { if (!retained) owned.getAndSet(null)?.close() }
            }
            roots.requireSameLibrary(root)
            synchronized(providerObservationLock) {
                roots.requireSameLibrary(root)
                providerObservationCursors.forEach { runCatching(it::close) }
                providerObservationCursors = opened.toList()
                opened.clear()
            }
        } finally { opened.forEach { runCatching(it::close) } }
    }

    internal val providerObservationCountForTest: Int
        get() = synchronized(providerObservationLock) { providerObservationCursors.size }

    fun observeStemSets(packageId: String) =
        kotlinx.coroutines.flow.combine(mutableStemSets, mutableSongs) { sets, _ ->
            sets[packageId].orEmpty()
        }

    fun observeActiveStems(packageId: String) =
        kotlinx.coroutines.flow.combine(mutableStemSets, mutableStems) { sets, stems ->
            val active = sets[packageId].orEmpty().firstOrNull { it.isActive }
            active?.let { stems[it.id].orEmpty() }.orEmpty()
        }

    internal fun currentStemMetadata(packageId: String): Pair<List<SeparationSetEntity>, List<StemAssetEntity>> {
        val sets = mutableStemSets.value[packageId].orEmpty()
        val active = sets.firstOrNull { it.isActive }
        return sets to active?.let { mutableStems.value[it.id] }.orEmpty()
    }

    /** Replay current transactions before projecting any root whose publication may be interrupted. */
    internal suspend fun recoverForColdOpen(expectedRoot: RootHandle) = mutations.withRootMutation {
        roots.requireSameLibrary(expectedRoot)
        synchronized(reconcileAdmissionLock) { reconcileAdmissionClosed = false }
        enrichmentRecovery.recover(expectedRoot)
        roots.requireSameLibrary(expectedRoot)
    }

    /** Display-only projection: persisted paths never authorize a playback selection. */
    internal suspend fun restoreProjectedLibrary(expectedRoot: RootHandle) = mutations.withRootMutation {
        roots.requireSameLibrary(expectedRoot)
        val selected = dao.libraryRootIdentity()
        check(selected?.libraryId == expectedRoot.libraryId && selected.treeUri == expectedRoot.treeUri.toString() &&
            selected.epoch == expectedRoot.epoch) { "LIBRARY_CHANGED" }
        val restoredSongs = dao.allSongs().filter { it.canonicalExternal &&
            it.highLevelState == "READY" && !it.packageDocumentUri.isNullOrBlank() }.map { entity ->
            val identity = ownerVisibleSongIdentity(entity.youtubeVideoId, entity.displayTitle,
                entity.displayArtist, entity.sourceTitle, entity.sourceArtist)
            Song(entity.id, entity.youtubeVideoId, identity.title, identity.artist,
                entity.durationMs, SongState.READY, accentFor(entity.id), album = entity.displayAlbum.orEmpty(),
                year = entity.displayYear.orEmpty(), artworkGlyph = artworkGlyph(identity.title),
                canonicalUrl = entity.canonicalUrl, additionalInfo = parseRoomAdditionalInfo(entity.additionalInfoJson))
        }
        projectionSessionGeneration++
        pendingProjectionReads.clear()
        songIndex.replaceAll(restoredSongs)
        songSnapshotDirty = true
        publishSongSnapshot()
        mutableStemSets.value = emptyMap()
        mutableStems.value = emptyMap()
        projectionAvailabilityTracker.markProjected()
    }

    /** Reads without blocking unrelated mutations, then commits only the exact captured package. */
    suspend fun verifyPlaybackSong(expectedRoot: RootHandle, songId: String): Song =
        mutations.withPackageRead(expectedRoot, songId) {
            verifyPackageOutsideRootLock(expectedRoot, songId, activate = true).first
        }

    /** Delivery confirmation pins the verified package and exact root through its external commit. */
    internal suspend fun withVerifiedPlaybackSong(expectedRoot: RootHandle, songId: String,
        use: suspend (Song) -> Unit) = mutations.withRootLease({ roots.requireSameLibrary(expectedRoot) }) {
        mutations.withPackageRead(expectedRoot, songId) {
            val song = verifyPackageOutsideRootLock(expectedRoot, songId, activate = true).first
            roots.requireSameLibrary(expectedRoot)
            use(song)
        }
    }

    private data class PackageVerification(
        val snapshot: SelectedSongSnapshot,
        val reuseFacts: CanonicalProjectionReuseFacts,
        val publicationRevision: Long?,
        val authority: SourceBindingScanAuthority
    )

    private fun samePackageDocuments(before: SelectedSongSnapshot, after: SelectedSongSnapshot): Boolean =
        before.directory.uri == after.directory.uri && before.files.keys == after.files.keys &&
            before.files.all { (name, old) -> after.files[name]?.let { current ->
                old.uri == current.uri && old.documentId == current.documentId && old.mimeType == current.mimeType &&
                    old.sizeBytes == current.sizeBytes && old.lastModifiedEpochMs == current.lastModifiedEpochMs
            } == true }

    private suspend fun verifyPackageOutsideRootLock(
        expectedRoot: RootHandle,
        songId: String,
        activate: Boolean = false,
        boundDirectory: String? = null,
        scanAuthority: SourceBindingScanAuthority? = null,
        requireCurrent: () -> Unit = {}
    ): Pair<Song, Boolean> = withContext(Dispatchers.IO) {
        val captured = mutations.withRootMutation {
            roots.requireSameLibrary(expectedRoot)
            requireCurrent()
            val authority = sourceBindings.scanAuthority(expectedRoot, scanAuthority)
            check("youtube-video:$songId" !in authority.blockedSourceIds) { "SOURCE_DELETED" }
            val snapshot = if (boundDirectory == null) roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId)
                ?: error("PACKAGE_NOT_FOUND") else roots.checkedBoundSongSnapshot(expectedRoot, songId, boundDirectory)
            PackageVerification(snapshot, dao.canonicalProjectionReuseFacts(listOf(songId)),
                packageProjectionRevisions[songId], authority)
        }
        val projection = try {
            projectFlat(expectedRoot, captured.snapshot, captured.reuseFacts)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            if (retryablePackageReadFailure(error)) throw error
            mutations.withRootMutation {
                roots.requireSameLibrary(expectedRoot)
                requireCurrent()
                val current = roots.checkedBoundSongSnapshot(expectedRoot, songId, captured.snapshot.directory.uri.toString())
                if (packageProjectionRevisions[songId] == captured.publicationRevision && samePackageDocuments(captured.snapshot, current) &&
                    sourceBindings.scanAuthority(expectedRoot, captured.authority) == captured.authority) {
                    withdrawInvalidProjection(expectedRoot, songId, error)
                }
            }
            throw error
        }
        packageProjectionPreparedForTest?.invoke(songId)
        mutations.withRootMutation {
            roots.requireSameLibrary(expectedRoot)
            requireCurrent()
            check(packageProjectionRevisions[songId] == captured.publicationRevision) { "SOURCE_AUDIO_CHANGED" }
            val current = roots.checkedBoundSongSnapshot(expectedRoot, songId, captured.snapshot.directory.uri.toString())
            check(samePackageDocuments(captured.snapshot, current) &&
                sourceBindings.scanAuthority(expectedRoot, captured.authority) == captured.authority) { "SOURCE_AUDIO_CHANGED" }
            val changed = publishProjectedPackageUnderRootLock(expectedRoot, current, projection)
            if (activate) sourceBindings.activateVerifiedPackageUnderRootLock(expectedRoot, songId, captured.authority)
            checkNotNull(songIndex[songId]) to changed
        }
    }

    /** Byte verification pins the package without holding the global root lock across decoding. */
    suspend fun verifyOriginalPlaybackSong(expectedRoot: RootHandle, songId: String): Song = withContext(Dispatchers.IO) {
        mutations.withPackageRead(expectedRoot, songId) {
            val captured = mutations.withRootMutation {
                roots.requireSameLibrary(expectedRoot)
                val authority = sourceBindings.scanAuthority(expectedRoot)
                check("youtube-video:$songId" !in authority.blockedSourceIds) { "SOURCE_DELETED" }
                try {
                    OriginalPlaybackVerification(
                        roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId) ?: error("PACKAGE_NOT_FOUND"),
                        dao.canonicalProjectionReuseFacts(listOf(songId)), packageProjectionRevisions[songId], authority)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) {
                    if (!retryablePackageReadFailure(error)) withdrawInvalidProjection(expectedRoot, songId, error)
                    throw error
                }
            }
            val projected = try {
                projectFlat(expectedRoot, withoutOptionalSources(captured.snapshot), captured.reuseFacts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (retryablePackageReadFailure(error)) throw error
                mutations.withRootMutation {
                    roots.requireSameLibrary(expectedRoot)
                    // A failed old read cannot withdraw a package committed while it was in flight.
                    if (packageProjectionRevisions[songId] == captured.publicationRevision &&
                        sourceBindings.scanAuthority(expectedRoot, captured.authority) == captured.authority) {
                        withdrawInvalidProjection(expectedRoot, songId, error)
                    }
                }
                throw error
            }
            mutations.withRootMutation {
                roots.requireSameLibrary(expectedRoot)
                check("youtube-video:$songId" !in sourceBindings.blockedSourceIds(expectedRoot)) { "SOURCE_DELETED" }
                check(packageProjectionRevisions[songId] == captured.publicationRevision) { "SOURCE_AUDIO_CHANGED" }
                val current = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId) ?: error("PACKAGE_NOT_FOUND")
                check(sameOriginalSelectionDocuments(captured.snapshot, current)) { "SOURCE_AUDIO_CHANGED" }
                sourceBindings.activateVerifiedPackageUnderRootLock(expectedRoot, songId, captured.authority)
                roots.requireSameLibrary(expectedRoot)
                val prior = songIndex[songId]?.takeIf { it.sourceHash == projected.song.sourceHash }
                projected.song.copy(
                    hasStems = current.inspection.hasStructurallyCompleteCustomSourceSet,
                    hasSourceParts = current.inspection.hasCustomSourceMedia || current.inspection.hasCustomSourceManifest ||
                        current.files.keys.any { it in SOURCE_JOB_AUTHORITY_FILES },
                    stemRevision = prior?.stemRevision.orEmpty()
                )
            }
        }
    }

    private data class OriginalPlaybackVerification(
        val snapshot: SelectedSongSnapshot,
        val reuseFacts: CanonicalProjectionReuseFacts,
        val publicationRevision: Long?,
        val authority: SourceBindingScanAuthority
    )

    private fun sameOriginalSelectionDocuments(before: SelectedSongSnapshot, after: SelectedSongSnapshot): Boolean {
        if (before.directory.uri != after.directory.uri ||
            before.inspection.originalFileName != after.inspection.originalFileName) return false
        val original = before.inspection.originalFileName ?: return false
        return listOf(original, SelectedLibraryContract.ENRICHMENT, SelectedLibraryContract.COVER).all { name ->
            val old = before.files[name] ?: return@all false
            val current = after.files[name] ?: return@all false
            old.uri == current.uri && old.mimeType == current.mimeType &&
                old.sizeBytes == current.sizeBytes && old.lastModifiedEpochMs == current.lastModifiedEpochMs
        }
    }

    /** No previously displayed inventory can authorize a changed selected lane. */
    suspend fun verifyPlaybackStems(expectedRoot: RootHandle, songId: String,
        expected: List<StemAssetEntity>): List<StemAssetEntity> {
        check(expected.isNotEmpty() && expected.all(StemAssetEntity::validated)) { "SOURCE_AUDIO_CHANGED" }
        return mutations.withPackageRead(expectedRoot, songId) {
            verifyPackageOutsideRootLock(expectedRoot, songId)
            mutations.withRootMutation {
                roots.requireSameLibrary(expectedRoot)
                check("youtube-video:$songId" !in sourceBindings.blockedSourceIds(expectedRoot)) { "SOURCE_DELETED" }
                val active = mutableStemSets.value[songId].orEmpty().singleOrNull { it.isActive }
                val actual = active?.let { mutableStems.value[it.id] }.orEmpty()
                check(actual.sortedBy(StemAssetEntity::id) == expected.sortedBy(StemAssetEntity::id)) { "SOURCE_AUDIO_CHANGED" }
                actual
            }
        }
    }

    /** Each package releases mutation ownership so selected playback need not wait for the whole Library. */
    internal suspend fun validateProjectedLibrary(expectedRoot: RootHandle): Unit = try {
        val (snapshot, initialAuthority) = mutations.withRootMutation {
            roots.requireSameLibrary(expectedRoot)
            val current = roots.checkedSnapshot(expectedRoot)
            current to sourceBindings.scanAuthority(expectedRoot, snapshot = current)
        }
        var authority: SourceBindingScanAuthority? = initialAuthority
        val retryPackages = linkedMapOf<String, Uri>()
        for (discovered in snapshot.songDirectories) {
            currentCoroutineContext().ensureActive()
            try {
                authority = mutations.withRootMutation { sourceBindings.scanAuthority(expectedRoot, authority, snapshot) }
                mutations.withPackageRead(expectedRoot, discovered.displayName) {
                    verifyPackageOutsideRootLock(expectedRoot, discovered.displayName, activate = true,
                        boundDirectory = discovered.uri.toString(), scanAuthority = authority)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                canonicalProjectionRootAccessFailure(error)?.let { throw it }
                if (retryablePackageReadFailure(error)) {
                    retryPackages[discovered.displayName] = discovered.uri
                    mutations.withRootMutation {
                        roots.requireSameLibrary(expectedRoot)
                        mutableProblems.value = mutableProblems.value.filterNot { it.packageId == discovered.displayName } +
                            PackageProblem(discovered.displayName, "PACKAGE_READ_RETRY_PENDING",
                                "Song details could not be read. Imported files were kept; check the Library again to retry.")
                    }
                }
            }
            kotlinx.coroutines.yield()
        }
        mutations.withRootMutation {
            roots.requireSameLibrary(expectedRoot)
            val presentIds = roots.checkedSnapshot(expectedRoot).songDirectories.mapTo(hashSetOf()) { it.displayName }
            songIndex.values.filter { it.id !in presentIds || it.sourcePath.isNullOrBlank() && it.id !in retryPackages }
                .forEach { removeProjectedSong(it.id, publishSnapshot = false) }
            pendingProjectionReads += retryPackages.keys.intersect(presentIds)
            publishSongSnapshot()
            publishCommittedInventoryUnderRootLock(expectedRoot, presentIds)
            if (pendingProjectionReads.isEmpty()) projectionAvailabilityTracker.markVerified()
            else projectionAvailabilityTracker.markProjected()
        }
        retryPackages.values.forEach { requestProviderRefresh(it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        canonicalProjectionRootAccessFailure(error)?.let { failure ->
            roots.reportRuntimeAccessFailure(expectedRoot, failure)
            markProjectionUnavailable()
        }
        throw error
    } finally {
        flushCurrentSongSnapshot(expectedRoot)
    }

    private suspend fun flushCurrentSongSnapshot(expectedRoot: RootHandle) =
        withContext(kotlinx.coroutines.NonCancellable) {
            mutations.withRootMutation {
                if (roots.captureHandle()?.authority() == expectedRoot.authority()) publishSongSnapshot()
            }
        }

    private suspend fun publishCommittedInventoryUnderRootLock(expectedRoot: RootHandle, packageIds: Set<String>) {
        roots.requireSameLibrary(expectedRoot)
        val totals = packageIds.toList().chunked(CANONICAL_REUSE_BATCH_SIZE)
            .fold(CanonicalStorageTotals(0, 0, 0L)) { total, batch ->
                val current = dao.canonicalStorageTotalsForPackages(batch)
                CanonicalStorageTotals(Math.addExact(total.packages, current.packages),
                    Math.addExact(total.stems, current.stems), Math.addExact(total.verifiedBytes, current.verifiedBytes))
            }
        roots.requireSameLibrary(expectedRoot)
        val inventory = storageRefreshLifecycle.publishCanonicalCounts(totals)
        persistInventorySnapshot(expectedRoot.libraryId, inventory)
    }

    suspend fun reconcile() {
        val sessionGeneration = projectionSessionGeneration
        val rootHandle = captureUsableStorageRoot(validateLayout = false).getOrElse { error ->
            beforeReconcileFailureForTest?.invoke()
            mutations.withRootMutation {
                // Retry can restore the same root/epoch while this failed capture is suspended.
                if (sessionGeneration == projectionSessionGeneration) {
                    markProjectionUnavailable()
                    canonicalReconcileLifecycle.failWithCachedInventory(error)
                    storageRefreshLifecycle.failWithCachedInventory(error)
                }
            }
            // Cold-open owns this call as a recovery stage. Returning success here previously let
            // it publish Ready over an unavailable exact root while the UI retained a cached zero.
            throw error
        }
        storageTraversalCoordinator.canonical { generation ->
            canonicalReconcileLifecycle.refresh {
                backgroundWorkCoordinator.withHeavyWork("Checking Library storage") {
                    withContext(Dispatchers.IO) {
                        try {
                            reconcileLibrary(rootHandle, generation)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            mutations.withRootMutation {
                                // A departed scan cannot demote the replacement root's projection.
                                if (sessionGeneration == projectionSessionGeneration) {
                                    roots.requireSameLibrary(rootHandle)
                                    canonicalProjectionRootAccessFailure(error)?.let { failure ->
                                        roots.reportRuntimeAccessFailure(rootHandle, failure)
                                        markProjectionUnavailable()
                                        throw failure
                                    }
                                }
                                throw error
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Bounded dashboard refresh: count/size metadata only, with no package hashing or projection
     * rebuild. Installed JSON remains the last canonically verified count while physical optional
     * song.json files are reported separately; a found/verified mismatch marks the snapshot dirty.
     */
    fun requestStorageInventoryRefresh() {
        storageRefreshRequestOwner.request()
    }

    /** Performs the repository-owned single storage flight. Direct callers join the same flight. */
    suspend fun refreshStorageInventory() {
        storageTraversalCoordinator.manual(
            joinCanonical = { awaitCanonical ->
                // Mirror the richer result through the dashboard lifecycle so navigation sees one
                // truthful busy/terminal generation without starting a second provider traversal.
                storageRefreshLifecycle.refresh { awaitCanonical() }
            },
            metadataOnly = ::refreshStorageInventoryMetadata
        )
    }

    private suspend fun refreshStorageInventoryMetadata(): LibraryInventory {
        // This path performs its own single-pass direct-layout validation while it measures the
        // root. Re-running SelectedLibraryContract first would query the root and every song
        // directory twice (two complete provider traversals).
        val sessionGeneration = projectionSessionGeneration
        beforeStorageRootCaptureForTest?.invoke()
        val rootHandle = captureUsableStorageRoot(validateLayout = false).getOrElse { error ->
            beforeStorageRootFailureForTest?.invoke()
            return mutations.withRootMutation {
                if (sessionGeneration == projectionSessionGeneration) {
                    markProjectionUnavailable()
                    storageRefreshLifecycle.failWithCachedInventory(error)
                } else storageRefreshLifecycle.state.value.inventory
            }
        }
        return mutations.withRootLease({ roots.requireSameLibrary(rootHandle) }) {
            // A flight captured before a completed switch is stale work, not a failure of the
            // newly selected root. Reject it before entering any lifecycle publication catch.
            roots.requireSameLibrary(rootHandle)
            try {
                // Root replacement joins this lease, while other packages keep admitting playback.
                val dirtyGenerationAtAdmission = storageRefreshLifecycle.dirtyGenerationToken()
                val refreshed = storageRefreshLifecycle.refresh {
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            // App-private bytes do not share the SAF mutation boundary. Measure them
                            // in parallel so the owner-visible budget is spent on provider latency,
                            // not on two independent serial traversals.
                            val appInventory = async {
                                storageScanner.scanAppPrivateStorage()
                            }
                            val previous = storageRefreshLifecycle.state.value.inventory
                            // One cancellable child cursor supplies name, kind, and size for every
                            // entry in a directory. No audio payloads are opened or hashed.
                            val rootInventory = storageScanner.scanRootStorage(
                                root = rootHandle.rootDocument
                            )
                            val app = appInventory.await()
                            roots.requireSameLibrary(rootHandle)
                            mergeStorageMeasurement(
                                previous,
                                LibraryStorageMeasurement(
                                    songsFound = rootInventory.songsFound,
                                    originalFilesFound = rootInventory.originalFiles,
                                    stemFilesFound = rootInventory.stemFiles,
                                    jsonFilesFound = rootInventory.jsonFiles,
                                    artworkFilesFound = rootInventory.artworkFiles,
                                    actualLibraryBytes = rootInventory.actualBytes,
                                    unknownSizeFiles = rootInventory.unknownSizeFiles,
                                    unreadableDirectories = rootInventory.unreadableDirectories,
                                    appPrivateTotalBytes = app.totalBytes,
                                    appDatabaseBytes = app.databaseBytes,
                                    appWorkingBytes = app.workingBytes,
                                    appCacheBytes = app.cacheBytes,
                                    measuredAtEpochMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
                mutations.withRootMutation {
                    roots.requireSameLibrary(rootHandle)
                    persistAcceptedInventorySnapshot(rootHandle.libraryId, refreshed, dirtyGenerationAtAdmission)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutations.withRootMutation {
                    roots.requireSameLibrary(rootHandle)
                    val rootFailure = canonicalProjectionRootAccessFailure(error)
                    if (rootFailure != null) {
                        roots.reportRuntimeAccessFailure(rootHandle, rootFailure)
                        markProjectionUnavailable()
                        storageRefreshLifecycle.failWithCachedInventory(rootFailure)
                        throw rootFailure
                    }
                    // Traversal failures are normally published by the lifecycle itself. This also
                    // covers a genuine snapshot-persistence failure after a successful scan, which
                    // must not be hidden after the exact measurement was accepted.
                    storageRefreshLifecycle.failRefreshWithCachedInventory(error)
                    throw error
                }
            }
        }
    }

    private suspend fun captureUsableStorageRoot(
        validateLayout: Boolean = true
    ): Result<RootHandle> =
        withContext(Dispatchers.IO) {
            val rootState = roots.state.value
            val handle = roots.captureHandle() ?: return@withContext Result.failure(
                LibraryRootException(
                    rootState.errorCode?.takeIf { it in ROOT_ACCESS_ERROR_CODES }
                        ?: "ROOT_UNAVAILABLE",
                    "Reconnect the Library folder"
                )
            )
            suspendResult {
                val root = handle.rootDocument
                if (validateLayout && (!root.isDirectory || !root.canRead())) {
                    throw LibraryRootException(
                        "ROOT_UNAVAILABLE",
                        "Reconnect the Library folder"
                    )
                }
                if (validateLayout) {
                    try {
                        roots.checkedSnapshot(handle)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (error is LibraryRootException) throw error
                        throw LibraryRootException(
                            "ROOT_LAYOUT_UNSUPPORTED",
                            "The selected folder contains an old or unknown layout"
                        ).also { it.addSuppressed(error) }
                    }
                }
                handle
            }.onFailure { error ->
                roots.reportRuntimeAccessFailure(handle, error)
            }
        }

    internal suspend fun retireScansForLibrarySwitch() {
        val scans = synchronized(reconcileAdmissionLock) {
            reconcileAdmissionClosed = true
            sourceBindings.invalidateScanAuthority()
            listOfNotNull(eventualReconcileJob, providerRefreshJob).distinct().also {
                eventualReconcileJob = null
                providerRefreshJob = null
            }
        }
        reconcileScanRetiringForTest?.invoke()
        scans.forEach { it.cancel() }
        // Scan finalizers flush under the root lock; join without holding mutation ownership.
        scans.forEach { it.join() }
    }

    suspend fun clearForLibrarySwitch(clearProjection: Boolean = true) = withContext(Dispatchers.IO) {
        // The session also joins these owners inside its retained retirement deadline.
        retireScansForLibrarySwitch()
        mutations.withRootMutation {
            roots.captureHandle()?.let { enrichmentRecovery.recover(it) }
            projectionSessionGeneration++
            projectionAvailabilityTracker.beginRestoration()
            storageRefreshLifecycle.beginRestoration(
                if (clearProjection) LibraryInventory() else storageRefreshLifecycle.state.value.inventory
            )
            if (clearProjection) {
                clearProjectionState()
                mutableProblems.value = emptyList()
            }
        }
    }

    suspend fun hasRootSwitchBlockingSeparationSafetyFacts(): Boolean =
        withContext(Dispatchers.IO) {
            dao.hasRootSwitchBlockingSeparationSafetyFacts()
        }

    @Volatile internal var reconcileScanRetiringForTest: (() -> Unit)? = null
    @Volatile internal var beforeReconcileFailureForTest: (suspend () -> Unit)? = null
    @Volatile internal var beforeStorageRootCaptureForTest: (suspend () -> Unit)? = null
    @Volatile internal var beforeStorageRootFailureForTest: (suspend () -> Unit)? = null
    @Volatile internal var canonicalScanStartedForTest: (suspend () -> Unit)? = null
    @Volatile internal var canonicalPackageVerifiedForTest: (suspend (String) -> Unit)? = null
    @Volatile internal var packageProjectionPreparedForTest: (suspend (String) -> Unit)? = null

    private suspend fun reconcileLibrary(rootHandle: RootHandle, generation: Long): LibraryInventory = try {
        canonicalScanStartedForTest?.invoke()
        val (snapshot, initialAuthority) = mutations.withRootMutation {
            roots.requireSameLibrary(rootHandle)
            val current = enrichmentRecovery.recover(rootHandle) ?: roots.checkedSnapshot(rootHandle)
            if (projectionAvailabilityTracker.state.value == LibraryProjectionAvailability.UNAVAILABLE) {
                projectionAvailabilityTracker.beginRestoration()
            }
            current to sourceBindings.scanAuthority(rootHandle, snapshot = current)
        }
        var authority: SourceBindingScanAuthority? = initialAuthority
        var writes = 0
        val retryPackages = linkedMapOf<String, Uri>()
        for (discovered in snapshot.songDirectories) {
            storageTraversalCoordinator.requireCurrent(generation)
            backgroundWorkCoordinator.checkpoint()
            try {
                authority = mutations.withRootMutation { sourceBindings.scanAuthority(rootHandle, authority, snapshot) }
                val changed = mutations.withPackageRead(rootHandle, discovered.displayName) {
                    verifyPackageOutsideRootLock(rootHandle, discovered.displayName,
                        boundDirectory = discovered.uri.toString(), scanAuthority = authority, requireCurrent = {
                            storageTraversalCoordinator.requireCurrent(generation)
                        }).second
                }
                if (changed) writes++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (invalidated: CanonicalScanInvalidated) { throw invalidated }
            catch (error: Throwable) {
                canonicalProjectionRootAccessFailure(error)?.let { throw it }
                if (retryablePackageReadFailure(error)) {
                    retryPackages[discovered.displayName] = discovered.uri
                    mutations.withRootMutation {
                        roots.requireSameLibrary(rootHandle)
                        mutableProblems.value = mutableProblems.value.filterNot { it.packageId == discovered.displayName } +
                            PackageProblem(discovered.displayName, "PACKAGE_READ_RETRY_PENDING", "Song details could not be read. Retrying this Library package.")
                    }
                }
            }
            canonicalPackageVerifiedForTest?.invoke(discovered.displayName)
            kotlinx.coroutines.yield()
        }
        storageTraversalCoordinator.requireCurrent(generation)
        canonicalWriteObserver(writes)
        val dirtyGeneration = storageRefreshLifecycle.dirtyGenerationToken()
        val physical = storageScanner.scanRootStorage(rootHandle.rootDocument)
        val app = storageScanner.scanAppPrivateStorage()
        mutations.withRootMutation {
            roots.requireSameLibrary(rootHandle)
            val currentSnapshot = roots.checkedSnapshot(rootHandle)
            val present = currentSnapshot.songDirectories.mapTo(hashSetOf()) { it.displayName }
            storageTraversalCoordinator.requireCurrent(generation)
            val nextSongs = songIndex.values.filter { it.id in present && (!it.sourcePath.isNullOrBlank() || it.id in retryPackages) }
            val retained = nextSongs.mapTo(hashSetOf()) { it.id }
            val nextSets = mutableStemSets.value.filterKeys { it in retained }
            val nextStems = mutableStems.value
            dao.removeMissingCanonicalPackageProjections(retained)
            sourceBindings.rebuildFromVerifiedBindingsUnderRootLock(rootHandle,
                nextSongs.associate { "youtube-video:${it.videoId}" to it.id }, currentSnapshot)
            // Read current committed observations, so a package updated between scan turns is retained.
            val observations = retained.toList().chunked(CANONICAL_REUSE_BATCH_SIZE).flatMap {
                dao.canonicalProjectionReuseFacts(it).observationsByPackageId.values.flatten()
            }
            val verifiedBytes = checkedLibraryByteTotal(observations.asSequence().map { it.byteLength })
            val retainedSets = nextSets.values.flatten()
            val nextInventory = LibraryInventory(
                songsFound = physical.songsFound, originalFilesFound = physical.originalFiles,
                stemFilesFound = physical.stemFiles, jsonFilesFound = physical.jsonFiles,
                artworkFilesFound = physical.artworkFiles, verifiedSongPackages = retained.size,
                verifiedOriginalFiles = retained.size,
                verifiedStemFiles = retainedSets.sumOf { nextStems[it.id].orEmpty().size },
                verifiedJsonFiles = retained.size,
                pcEnrichedSongManifests = retained.size,
                verifiedArtworkFiles = retained.size,
                verifiedTotalBytes = verifiedBytes, actualLibraryBytes = physical.actualBytes,
                temporaryOrUnclassifiedBytes = (physical.actualBytes - verifiedBytes).coerceAtLeast(0L),
                unknownSizeFiles = physical.unknownSizeFiles, unreadableDirectories = physical.unreadableDirectories,
                appPrivateTotalBytes = app.totalBytes, appDatabaseBytes = app.databaseBytes,
                appWorkingBytes = app.workingBytes, appCacheBytes = app.cacheBytes,
                lastCheckedEpochMs = System.currentTimeMillis(),
                invalidPackages = mutableProblems.value.filterNot { it.code == "PACKAGE_READ_RETRY_PENDING" }
                            .map(PackageProblem::packageId).filter(String::isNotBlank).toSet().size,
                snapshotDirty = storageRefreshLifecycle.dirtyGenerationToken() != dirtyGeneration || retryPackages.isNotEmpty()
            )
            roots.requireSameLibrary(rootHandle)
            persistInventorySnapshot(rootHandle.libraryId, nextInventory)
            songIndex.keys.filter { it !in retained }.forEach { removeProjectedSong(it, publishSnapshot = false) }
            publishSongSnapshot()
            mutableStemSets.value = nextSets
            val sets = nextSets.values.flatten().mapTo(hashSetOf()) { it.id }
            mutableStems.value = nextStems.filterKeys { it in sets }
            mutableProblems.value = mutableProblems.value.filter { it.packageId in present }
            pendingProjectionReads.retainAll(present)
            pendingProjectionReads += retryPackages.keys.intersect(present)
            synchronized(reconcileAdmissionLock) { pendingProviderPackages.putAll(retryPackages.filterKeys { it in present }) }
            storageTraversalCoordinator.publishCurrent(generation) {
                if (pendingProjectionReads.isEmpty()) projectionAvailabilityTracker.markVerified()
                else projectionAvailabilityTracker.markProjected()
            }
            storageRefreshLifecycle.replaceInventory(nextInventory)
            retryPackages.values.firstOrNull()?.let { requestProviderRefresh(it) }
            nextInventory
        }
    } finally {
        flushCurrentSongSnapshot(rootHandle)
    }

    private suspend fun publishProjectedPackageUnderRootLock(
        expectedRoot: RootHandle,
        songSnapshot: SelectedSongSnapshot,
        projection: ProjectedPackage
    ): Boolean {
        val packageId = songSnapshot.videoId
        val inspection = songSnapshot.inspection
        roots.requireSameLibrary(expectedRoot)
        val replacedSetIds = mutableStemSets.value[packageId]
            .orEmpty()
            .mapTo(hashSetOf()) { it.id }
        val commit = projection.reusedCommit ?: dao.replaceCanonicalPackageProjection(
            projection.roomSong,
            projection.roomSource,
            projection.sets,
            projection.stems.values.flatten(),
            projection.analysis
        )
        roots.requireSameLibrary(expectedRoot)
        if (projection.reusedCommit == null) dao.replaceAssetObservations(packageId, projection.observations)
        roots.requireSameLibrary(expectedRoot)
        val song = committedCanonicalSong(projection.song.copy(
            hasSourceParts = inspection.hasCustomSourceMedia || inspection.hasCustomSourceManifest ||
                songSnapshot.files.keys.any { it in SOURCE_JOB_AUTHORITY_FILES }
        ), commit)
        // Fresh verified bytes supersede older reads even when the displayed projection is identical.
        recordPackageProjectionChange(packageId)
        if (songIndex.upsert(song)) songSnapshotDirty = true
        publishSongSnapshot()
        mutableProblems.value = mutableProblems.value.filterNot { it.packageId == packageId } + listOfNotNull(projection.optionalProblem)
        if (pendingProjectionReads.remove(packageId) && pendingProjectionReads.isEmpty()) {
            projectionAvailabilityTracker.markVerified()
        }
        if (inspection.hasCustomSourceMedia && !inspection.hasStructurallyCompleteCustomSourceSet) {
            mutableProblems.value += PackageProblem(packageId, "PACKAGE_INCOMPLETE", "Part preparation is incomplete")
        }
        if (mutableStemSets.value[packageId].orEmpty() != projection.sets) {
            mutableStemSets.value = mutableStemSets.value + (packageId to projection.sets)
        }
        if (replacedSetIds.any { mutableStems.value[it].orEmpty() != projection.stems[it].orEmpty() } ||
            projection.stems.any { (id, stems) -> mutableStems.value[id].orEmpty() != stems }) {
            mutableStems.value = mutableStems.value.filterKeys { it !in replacedSetIds }.plus(projection.stems)
        }
        storageRefreshLifecycle.markInventoryDirty()
        return projection.reusedCommit == null
    }

    private suspend fun withdrawInvalidProjection(expectedRoot: RootHandle, packageId: String, error: Throwable) {
        canonicalProjectionRootAccessFailure(error)?.let { failure ->
            roots.reportRuntimeAccessFailure(expectedRoot, failure)
            markProjectionUnavailable()
            throw failure
        }
        roots.requireSameLibrary(expectedRoot)
        dao.invalidateCanonicalProjection(packageId)
        recordPackageProjectionChange(packageId)
        removeProjectedSong(packageId)
        val staleSets = mutableStemSets.value[packageId].orEmpty().mapTo(hashSetOf()) { it.id }
        mutableStemSets.value = mutableStemSets.value - packageId
        mutableStems.value = mutableStems.value.filterKeys { it !in staleSets }
        mutableProblems.value = mutableProblems.value.filterNot { it.packageId == packageId } +
            PackageProblem(packageId, error.message?.takeIf(ERROR_CODE::matches) ?: "PACKAGE_INVALID",
                "The complete song bundle could not be verified; its files were preserved.")
    }

    suspend fun reconcilePackage(expectedRoot: RootHandle, packageId: String) =
        reconcilePackages(expectedRoot, listOf(packageId))

    /** Known app mutations verify only their affected packages; external refresh still verifies all bytes. */
    suspend fun reconcilePackages(expectedRoot: RootHandle, packageIds: Collection<String>) =
        withContext(Dispatchers.IO) {
            for (packageId in packageIds.distinct()) {
                check(SelectedLibraryContract.isVideoId(packageId)) { "PACKAGE_ID_INVALID" }
                backgroundWorkCoordinator.checkpoint()
                mutations.withPackageRead(expectedRoot, packageId) {
                    verifyPackageOutsideRootLock(expectedRoot, packageId)
                }
                canonicalPackageVerifiedForTest?.invoke(packageId)
            }
            mutations.withRootMutation {
                roots.requireSameLibrary(expectedRoot)
                markInventoryDirtyAndScheduleStorageRefresh()
            }
        }

    /** Caller already owns the exact package mutex; never reacquire its non-reentrant lock. */
    internal suspend fun reconcilePackageUnderPackageOwnership(expectedRoot: RootHandle, packageId: String) {
        verifyPackageOutsideRootLock(expectedRoot, packageId)
        mutations.withRootMutation {
            roots.requireSameLibrary(expectedRoot)
            markInventoryDirtyAndScheduleStorageRefresh()
        }
    }

    /**
     * Removes a package from the live/Room projection after permanent external deletion commits.
     * The metadata-only storage measurement is deliberately coalesced so deletion feedback is immediate.
     */
    suspend fun removeDeletedPackage(
        expectedRoot: RootHandle,
        packageId: String
    ) = withContext(Dispatchers.IO) {
        mutations.withRootMutation {
            roots.requireSameLibrary(expectedRoot)
            val removedSetIds = mutableStemSets.value[packageId]
                .orEmpty()
                .mapTo(hashSetOf()) { it.id }
            roots.requireSameLibrary(expectedRoot)
            recordPackageProjectionChange(packageId)
            removeProjectedSong(packageId)
            mutableProblems.value = mutableProblems.value.filterNot { it.packageId == packageId }
            mutableStemSets.value = mutableStemSets.value - packageId
            mutableStems.value = mutableStems.value.filterKeys { setId ->
                setId !in removedSetIds && setId.substringBefore(':') != packageId
            }
            try {
                roots.requireSameLibrary(expectedRoot)
                dao.removeCanonicalPackageProjection(packageId)
                roots.requireSameLibrary(expectedRoot)
            } finally {
                markInventoryDirtyAndScheduleStorageRefresh()
            }
        }
    }

    fun restoreInventorySnapshot(libraryId: String) {
        val raw = context.getSharedPreferences(STORAGE_PREFERENCES, Context.MODE_PRIVATE)
            .getString("snapshot:$libraryId", null)
            ?: return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        runCatching { decodeLibraryInventorySnapshot(json) }.onSuccess { inventory ->
            storageRefreshLifecycle.restoreCachedInventory(inventory)

        }
    }

    private fun persistInventorySnapshot(libraryId: String, value: LibraryInventory) {
        synchronized(inventorySnapshotPersistenceLock) {
            context.getSharedPreferences(STORAGE_PREFERENCES, Context.MODE_PRIVATE)
                .edit {
                    putString("snapshot:$libraryId", libraryInventorySnapshotJson(value).toString())
                }
        }
    }

    private fun persistAcceptedInventorySnapshot(
        libraryId: String,
        value: LibraryInventory,
        dirtyGenerationAtAdmission: Long
    ): LibraryInventory = synchronized(inventorySnapshotPersistenceLock) {
        val merged = preserveCanonicalInventory(value, storageRefreshLifecycle.state.value.inventory)
        val persisted = merged.copy(
            snapshotDirty = merged.snapshotDirty ||
                storageRefreshLifecycle.dirtyGenerationToken() != dirtyGenerationAtAdmission
        )
        persistInventorySnapshot(libraryId, persisted)
        persisted
    }

    private suspend fun markInventoryDirtyAndScheduleStorageRefresh() {
        val expectedRoot = roots.captureHandle() ?: return
        synchronized(inventorySnapshotPersistenceLock) {
            val dirtyInventory = storageRefreshLifecycle.markInventoryDirty()
            roots.state.value.libraryId?.let { persistInventorySnapshot(it, dirtyInventory) }
        }
        synchronized(reconcileAdmissionLock) {
            if (reconcileAdmissionClosed) return
            eventualReconcileJob?.cancel()
            eventualReconcileJob = scope.launchRecoverable(onFailure = { error ->
                if (roots.captureHandle()?.authority() == expectedRoot.authority()) {
                    storageRefreshLifecycle.failWithCachedInventory(error)
                }
            }) {
                delay(1_500L)
                mutations.withRootMutation {
                    roots.requireSameLibrary(expectedRoot)
                    // These are already committed verification records, not a new proof of external bytes.
                    val totals = dao.canonicalStorageTotals()
                    val packages = totals.packages
                    val stems = totals.stems
                    val verifiedBytes = totals.verifiedBytes
                    val prior = storageRefreshLifecycle.state.value
                    storageRefreshLifecycle.replaceInventory(prior.inventory.copy(
                        verifiedSongPackages = packages,
                        verifiedOriginalFiles = packages,
                        verifiedStemFiles = stems,
                        verifiedJsonFiles = packages,
                        pcEnrichedSongManifests = packages,
                        verifiedArtworkFiles = packages,
                        verifiedTotalBytes = verifiedBytes,
                        temporaryOrUnclassifiedBytes = (prior.inventory.actualLibraryBytes - verifiedBytes).coerceAtLeast(0L),
                        invalidPackages = mutableProblems.value.map(PackageProblem::packageId).filter(String::isNotBlank).toSet().size,
                        snapshotDirty = true
                    ), availability = prior.availability)
                    roots.requireSameLibrary(expectedRoot)
                }
                refreshStorageInventory()
            }
        }
    }

    private fun clearProjectionState() {
        clearCanonicalProjectionState()
        storageRefreshLifecycle.beginRestoration(LibraryInventory())
    }

    private fun clearCanonicalProjectionState() {
        pendingProjectionReads.clear()
        songIndex.clear()
        songSnapshotDirty = false
        mutableSongs.value = emptyList()
        mutableStemSets.value = emptyMap()
        mutableStems.value = emptyMap()
    }

    private fun markProjectionUnavailable() {
        // Publish UNAVAILABLE first so clearing canonical memory cannot transiently enable the
        // Room fallback. Owner history remains in Room for a later successful reconnect.
        projectionAvailabilityTracker.markUnavailable()
        // Inventory is a separately persisted, last-verified storage snapshot. Keep its imported
        // JSON count and user/app byte ownership intact while the canonical URI-backed projection
        // fails closed.
        clearCanonicalProjectionState()
    }

    private fun withoutOptionalSources(snapshot: SelectedSongSnapshot): SelectedSongSnapshot {
        val excluded = SelectedLibraryContract.customStemFiles.toSet() + SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST
        val files = snapshot.files.filterKeys { it !in excluded }
        return snapshot.copy(files = files, inspection = inspectSelectedSongEntryNames(snapshot.videoId,
            files.filterValues { !it.directory }.keys.toList()))
    }

    private suspend fun recoverPortableSourceVersionId(
        expectedRoot: RootHandle,
        snapshot: SelectedSongSnapshot,
        originalHash: String,
        allowDamagedJobMetadata: Boolean
    ): String? {
        suspend fun read(name: String): ByteArray? = snapshot.file(name)?.let { file ->
            try {
                readBoundedProviderDocument(context, file.uri, file.sizeBytes,
                    app.stemwise.integration.PORTABLE_SOURCE_JOB_MAX_BYTES, requireNonEmpty = false)
            } catch (invalid: IllegalStateException) {
                if (!allowDamagedJobMetadata || invalid.message !in setOf("MANIFEST_TOO_LARGE", "ASSET_SIZE_MISMATCH")) throw invalid
                null
            }
        }
        val copies = SOURCE_JOB_AUTHORITY_FILES.associateWith { read(it) }
        val authority = try {
            app.stemwise.integration.latestSourceJobAuthority(listOfNotNull(
                app.stemwise.integration.selectPortableSourceJobAuthority(copies[SelectedLibraryContract.SOURCE_JOB_AUTHORITY],
                    copies[SelectedLibraryContract.SOURCE_JOB_AUTHORITY_TEMP]),
                app.stemwise.integration.selectPortableSourceJobAuthority(copies[SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY],
                    copies[SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY_TEMP])))
        } catch (invalid: IllegalStateException) {
            if (!allowDamagedJobMetadata || invalid.message != app.stemwise.integration.PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED) throw invalid
            // A valid copy may retain an accepted source identity without admitting any optional media.
            return copies.values.filterNotNull().mapNotNull {
                runCatching { app.stemwise.integration.parsePortableSourceJobAuthority(it) }.getOrNull()
            }.filter { it.libraryId == expectedRoot.libraryId && it.videoId == snapshot.videoId && it.sourceSha256 == originalHash }
                .map { it.sourceVersionId }.distinct().singleOrNull()
        } ?: return null
        check(authority.videoId == snapshot.videoId && authority.libraryId == expectedRoot.libraryId) {
            app.stemwise.integration.PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
        }
        return authority.sourceVersionId.takeIf { authority.sourceSha256 == originalHash }
    }

    private suspend fun projectFlat(
        expectedRoot: RootHandle,
        songSnapshot: SelectedSongSnapshot,
        reuseFacts: CanonicalProjectionReuseFacts? = null
    ): ProjectedPackage {
        val folder = songSnapshot.directory.document
        val videoId = songSnapshot.videoId
        val inspection = songSnapshot.inspection
        // Optional stem publication can be interrupted between files. Verify and expose the
        // original independently; partial stems never enter the mixer projection below.
        check(inspection.hasOriginal && inspection.state != SelectedSongFolderState.UNSUPPORTED) {
            "PACKAGE_INCOMPLETE"
        }
        val existing = if (reuseFacts == null) {
            dao.findSongById(videoId)
        } else {
            reuseFacts.songsById[videoId]
        }
        val existingSource = if (reuseFacts == null) {
            dao.currentSource(videoId)
        } else {
            reuseFacts.currentSourcesBySongId[videoId]
        }
        val cachedObservations = if (reuseFacts == null) {
            dao.assetObservationsForPackage(videoId)
        } else {
            reuseFacts.observationsByPackageId[videoId].orEmpty()
        }
            .associateBy(AssetObservationEntity::relativePath)
        val originalSnapshot = songSnapshot.original
            ?: error("ORIGINAL_MISSING")
        val originalFile = originalSnapshot.document
        val originalName = originalSnapshot.displayName
        val originalCodec = if (originalName == SelectedLibraryContract.ORIGINAL_FLAC) "flac" else "opus"
        val originalMime = "audio/$originalCodec"
        val originalContainer = if (originalCodec == "flac") "flac" else "ogg"
        val originalVerification = verifyFlatDocument(
            originalSnapshot,
            cachedObservations[originalName]
        )
        val originalHash = originalVerification.sha256
        val originalFacts = if (
            originalVerification.reused &&
            existingSource != null && existing != null &&
            existingSource.sha256 == originalHash &&
            existingSource.localPath == originalFile.uri.toString() &&
            existingSource.codec == originalCodec &&
            existingSource.isCurrent &&
            existing.durationMs > 0L
        ) {
            FlatAudioFacts(
                mime = originalMime,
                durationMs = existing.durationMs,
                sampleRateHz = existingSource.sampleRateHz,
                channelCount = existingSource.channelCount
            )
        } else {
            probeFlatAudio(originalFile)
        }
        val flacFacts = if (originalCodec == "flac") {
            inspectFlatSourceAudio(originalSnapshot, originalVerification, allowHighPrecisionFlac = true)
                .also { facts ->
                    check(facts.mime == "audio/flac" && facts.channels in 1..2 &&
                        facts.sampleRate in 8_000..384_000 &&
                        facts.sampleRate == originalFacts.sampleRateHz &&
                        facts.channels == originalFacts.channelCount &&
                        facts.audibleFrames <= app.stemwise.MAX_ENRICHMENT_DURATION_MS * facts.sampleRate / 1_000L) { "ORIGINAL_CODEC_INVALID" }
                }
        } else null
        check(canonicalMediaMimeMatches(originalName, originalFacts.mime) ||
            (flacFacts != null && originalFacts.mime == "audio/raw")) { "ORIGINAL_CODEC_INVALID" }
        val manifestSnapshot = songSnapshot.file(SelectedLibraryContract.ENRICHMENT)
        val coverSnapshot = songSnapshot.file(SelectedLibraryContract.COVER)
        check(manifestSnapshot != null && coverSnapshot != null) {
            "ENRICHMENT_PAIR_REQUIRED"
        }
        val manifestBytes = readBounded(manifestSnapshot, MAX_MANIFEST_BYTES)
        val verifiedManifest = installedEnrichmentVerificationCache.verify(
            manifestBytes, expectedRoot.authority(), videoId, originalHash)
        val manifestHash = verifiedManifest.sha256
        val manifest = verifiedManifest.document
        check(coverSnapshot.mimeType == "image/png") { "ARTWORK_NEEDS_REIMPORT" }
        val coverBytes = readBounded(coverSnapshot, MAX_PNG_BYTES)
        val coverHash = sha256(coverBytes)
        val priorCover = cachedObservations[SelectedLibraryContract.COVER]
        val coverReused = reusableFlatAssetObservation(coverSnapshot.uri.toString(),
            coverSnapshot.sizeBytes, coverSnapshot.lastModifiedEpochMs, priorCover, coverHash)
        val coverVerification = FlatDocumentVerification(coverHash, coverBytes.size.toLong(),
            if (coverReused) checkNotNull(priorCover).verifiedAtEpochMs else System.currentTimeMillis(), coverReused)
        validateEnrichmentCoverPng(coverBytes)
        manifest.optJSONObject("artwork")?.let { artwork ->
            check(
                artwork.optString("delivery") == "selected-root" &&
                    artwork.optString("file") == SelectedLibraryContract.COVER &&
                    artwork.optString("mimeType") == "image/png" &&
                    artwork.optLong("byteLength", -1L) ==
                    coverVerification.byteLength &&
                    artwork.optString("sha256") == coverHash
            ) { "ARTWORK_NEEDS_REIMPORT" }
        }
        val declaredMedia = installedMediaIdentities(manifest)
        declaredMedia[originalName]?.let { declared ->
            check(declared.byteLength == originalVerification.byteLength) {
                "ASSET_SIZE_MISMATCH"
            }
            check(declared.sha256 == originalHash) { "ASSET_HASH_MISMATCH" }
            validateEnrichmentOriginalBinding(manifest.getJSONObject("source"),
                originalName, declared, flacFacts?.let {
                    (it.audibleFrames * 1_000L + it.sampleRate / 2) / it.sampleRate
                } ?: originalFacts.durationMs)
        }
        val generatedMetadata = projectEnrichmentMetadata(
            manifest.optJSONObject("metadata")
        )
        val tags = if (originalCodec == "flac" && (!originalVerification.reused ||
                generatedMetadata.album.isBlank() || generatedMetadata.year.isBlank())) {
            readOriginalTags(originalFile)
        } else emptyMap()
        val metadataProjection = generatedMetadata.withOriginalAlbumAndYear(
            tags[MediaMetadataRetriever.METADATA_KEY_ALBUM],
            tags[MediaMetadataRetriever.METADATA_KEY_YEAR]
        )
        val additionalInfo = catalogAdditionalInfo(metadataProjection.additionalInfo)
        val existingIdentity = existing?.let { entity ->
            ownerVisibleSongIdentity(
                videoId,
                entity.displayTitle,
                entity.displayArtist,
                entity.sourceTitle,
                entity.sourceArtist
            )
        }
        val title = boundedCatalogText(
            ownerVisibleTextOrNull(metadataProjection.title)
            ?.takeUnless { it == "Unknown title" }
            ?: existingIdentity?.title
            ?: tags[MediaMetadataRetriever.METADATA_KEY_TITLE]
            ?: unavailablePlaylistItemTitle(videoId),
            CATALOG_PRIMARY_TEXT_MAX_CHARS
        ) ?: unavailablePlaylistItemTitle(videoId)
        val artist = boundedCatalogText(
            ownerVisibleTextOrNull(metadataProjection.artist)
            ?.takeUnless { it.equals("Unknown artist", ignoreCase = true) }
            ?: existingIdentity?.artist
            ?: tags[MediaMetadataRetriever.METADATA_KEY_ARTIST]
            ?: "Unknown artist",
            CATALOG_PRIMARY_TEXT_MAX_CHARS
        ) ?: "Unknown artist"
        val album = boundedCatalogText(
            metadataProjection.album,
            CATALOG_SECONDARY_TEXT_MAX_CHARS
        ).orEmpty()
        val year = boundedCatalogText(
            metadataProjection.year,
            CATALOG_TOKEN_MAX_CHARS
        ).orEmpty()
        val durationMs = flacFacts?.let { (it.audibleFrames * 1_000L + it.sampleRate / 2) / it.sampleRate }
            ?: originalFacts.durationMs.takeIf { it > 0L }
            ?: existing?.durationMs?.takeIf { it > 0L }
            ?: error("TIMELINE_INVALID")
        // Optional repair reuses the already verified immutable core within this exact scan.
        suspend fun projectSources(songSnapshot: SelectedSongSnapshot, allowDamagedJobMetadata: Boolean): ProjectedPackage {
            // A canonical refresh is not a new acquisition. Keep an existing byte identity so an
            // optional, still-running separation remains attached to the original it submitted.
            val portableSourceId = recoverPortableSourceVersionId(expectedRoot, songSnapshot, originalHash, allowDamagedJobMetadata)
            val retainedSource = existingSource?.takeIf {
                it.songId == videoId && it.sha256 == originalHash &&
                    it.codec.equals(originalCodec, ignoreCase = true) &&
                    it.container.equals(originalContainer, ignoreCase = true)
            }
            val sourceId = retainedSource?.id ?: portableSourceId ?: flatSourceId(videoId, originalHash)
            val observations = mutableListOf<AssetObservationEntity>()
            val priorManifest = cachedObservations[SelectedLibraryContract.ENRICHMENT]
            val manifestReused = reusableFlatAssetObservation(manifestSnapshot.uri.toString(),
                manifestSnapshot.sizeBytes, manifestSnapshot.lastModifiedEpochMs, priorManifest, manifestHash)
            observations += flatObservation(videoId, SelectedLibraryContract.ENRICHMENT, manifestSnapshot.document,
                manifestSnapshot, FlatDocumentVerification(manifestHash, manifestBytes.size.toLong(),
                    if (manifestReused) checkNotNull(priorManifest).verifiedAtEpochMs else System.currentTimeMillis(),
                    manifestReused), manifestHash)
            observations += flatObservation(
                videoId,
                originalName,
                originalFile,
                originalSnapshot,
                originalVerification,
                manifestHash
            )
            observations += flatObservation(
                    videoId,
                    SelectedLibraryContract.COVER,
                    coverSnapshot.document,
                    coverSnapshot,
                    coverVerification,
                    manifestHash
                )
            val customManifestSnapshot = songSnapshot.file(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST)
            var customRevisionPreviouslyVerified = false
            val customManifest = customManifestSnapshot?.let { canonical ->
                var declared = canonical
                val (bytes, custom) = try {
                    val bytes = readBounded(canonical, CUSTOM_SOURCE_MANIFEST_MAX_BYTES)
                    bytes to parseCustomSourceManifest(bytes, videoId, originalHash)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (invalid: Exception) {
                    canonicalProjectionRootAccessFailure(invalid)?.let { throw it }
                    if (retryablePackageReadFailure(invalid)) throw invalid
                    declared = songSnapshot.file(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP) ?: throw invalid
                    val bytes = readBounded(declared, CUSTOM_SOURCE_MANIFEST_MAX_BYTES)
                    bytes to parseCustomSourceManifest(bytes, videoId, originalHash)
                }
                custom.also { custom ->
                    validateCustomSourceManifestFiles(custom, songSnapshot.files.keys)
                    val prior = cachedObservations[declared.displayName]
                    customRevisionPreviouslyVerified = prior?.expectedSha256 == custom.revisionSha256 &&
                        prior.documentUri == declared.uri.toString() && prior.byteLength == bytes.size.toLong()
                    if (!originalVerification.reused || !customRevisionPreviouslyVerified) {
                        val sourceFrames = flacFacts?.let {
                            (it.audibleFrames * 48_000L + it.sampleRate - 1L) / it.sampleRate
                        } ?: inspectFlatOpus(originalSnapshot, originalVerification).decodedAudibleFrameCount
                        check(sourceFrames == custom.audibleFrames) { CUSTOM_SOURCE_MANIFEST_INVALID }
                    }
                    observations += flatObservation(videoId, declared.displayName, declared.document, declared,
                        FlatDocumentVerification(sha256(bytes), bytes.size.toLong(),
                            if (customRevisionPreviouslyVerified) checkNotNull(prior).verifiedAtEpochMs else System.currentTimeMillis(),
                            customRevisionPreviouslyVerified),
                        custom.revisionSha256)
                }
            }
            val setId = "$videoId:sources:${customManifest?.revisionSha256 ?: originalHash}"
            val hasCompleteStemSet = customManifest != null
            val stemEntities = customManifest?.let { custom ->
                val previousCustomAssets = if (reuseFacts == null) dao.stemsForSet(setId) else
                    reuseFacts.stemsBySetId[setId].orEmpty()
                val previousBySource = previousCustomAssets.associateBy(StemAssetEntity::normalizedCapability)
                custom.lanes.map { lane ->
                    val fileSnapshot = songSnapshot.file(lane.fileName) ?: error(CUSTOM_SOURCE_MANIFEST_INVALID)
                    val verification = verifyFlatDocument(fileSnapshot, cachedObservations[lane.fileName])
                    check(lane.sha256 == verification.sha256 && lane.byteLength == verification.byteLength) {
                        CUSTOM_SOURCE_MANIFEST_INVALID
                    }
                    val prior = previousBySource[lane.sourceId]
                    val codec = if (lane.sourceId == "remaining") "wavpack" else "flac"
                    val reuse = customRevisionPreviouslyVerified && verification.reused && prior != null &&
                        prior.validated && prior.sha256 == lane.sha256 && prior.localPath == fileSnapshot.uri.toString() &&
                        prior.codec == codec && prior.sampleRateHz == 48_000 && prior.channelCount == 2 &&
                        prior.durationMs > 0L && prior.encoderDelaySamples == 0
                    val audio = if (reuse) null else {
                        inspectFlatSourceAudio(fileSnapshot, verification).also { opened ->
                            if (opened.isFloat) {
                                // Remaining is rendered by our direct Float32 reader, not MediaCodec.
                                validateCustomSourceAudioAsset(lane, verification.sha256, verification.byteLength, opened)
                            } else {
                                val decoded = probeFlatAudio(fileSnapshot.document)
                                validateCustomSourceAsset(lane, verification.sha256, verification.byteLength, opened,
                                    decoded.mime, decoded.sampleRateHz, decoded.channelCount)
                            }
                        }
                    }
                    observations += flatObservation(videoId, lane.fileName, fileSnapshot.document,
                        fileSnapshot, verification, custom.revisionSha256)
                    StemAssetEntity(id = "$setId:${lane.sourceId}", separationSetId = setId,
                        providerLabel = lane.label, normalizedCapability = lane.sourceId,
                        localPath = fileSnapshot.uri.toString(), sha256 = verification.sha256,
                        durationMs = audio?.let { (it.audibleFrames * 1_000L + 24_000L) / 48_000L }
                            ?: checkNotNull(prior).durationMs, codec = codec,
                        sampleRateHz = 48_000, channelCount = 2, validated = true,
                        encoderDelaySamples = 0)
                }
            } ?: emptyList()
            val existingSet = if (customManifest == null) null else if (reuseFacts == null) {
                dao.separationSetById(setId)
            } else {
                reuseFacts.setsById[setId]
            }
            val proposedSet = if (hasCompleteStemSet) {
                SeparationSetEntity(
                    id = setId,
                    songId = videoId,
                    sourceVersionId = sourceId,
                    sourceSha256 = originalHash,
                    provider = "MVSEP",
                    modelId = "source-plan:${checkNotNull(customManifest).planSha256}",
                    modelName = "Selected parts",
                    remoteJobId = null,

                    state = "COMPLETE",
                    isActive = true,
                    createdAtEpochMs = 0L
                )
            } else {
                null
            }
            val set = proposedSet?.let { proposed ->
                existingSet?.let { prior ->
                    proposed.copy(createdAtEpochMs = prior.createdAtEpochMs)
                        .takeIf { it == prior }
                } ?: proposed
            }
            val analysisJson = manifest.optJSONObject("analysis")
            val analysisProjection = projectEnrichmentAnalysis(analysisJson)
            val artworkSource = manifest.optJSONObject("artwork")?.let {
                coverSnapshot.uri.toString()
            }
            val now = System.currentTimeMillis()
            val proposedSource = SourceVersionEntity(
                id = sourceId,
                songId = videoId,
                localPath = originalFile.uri.toString(),
                sha256 = originalHash,
                codec = originalCodec,
                container = originalContainer,
                sampleRateHz = originalFacts.sampleRateHz,
                channelCount = originalFacts.channelCount,

                engineVersion = "selected-folder-flat-v1",
                isCurrent = true,
                createdAtEpochMs = now
            )
            val sourceEntity = retainedSource?.copy(
                localPath = originalFile.uri.toString(),
                isCurrent = true
            ) ?: proposedSource
            val roomSong = SongEntity(
                id = videoId,
                youtubeVideoId = videoId,
                playlistItemId = existing?.playlistItemId,
                canonicalUrl = "https://music.youtube.com/watch?v=$videoId",
                sourceTitle = boundedCatalogText(
                    existing?.sourceTitle ?: title,
                    CATALOG_PRIMARY_TEXT_MAX_CHARS
                ) ?: title,
                sourceArtist = boundedCatalogText(
                    existing?.sourceArtist ?: artist,
                    CATALOG_PRIMARY_TEXT_MAX_CHARS
                ) ?: artist,
                displayTitle = title,
                displayArtist = artist,
                displayAlbum = album.takeIf(String::isNotBlank),
                displayYear = year.takeIf(String::isNotBlank),
                additionalInfoJson = JSONObject(additionalInfo).toString(),
                durationMs = durationMs,
                artworkPath = artworkSource,
                currentlyInSourcePlaylist = existing?.currentlyInSourcePlaylist ?: false,
                highLevelState = "READY",
                acquiredAtEpochMs = existing?.acquiredAtEpochMs ?: now,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                canonicalExternal = true,
                packageDocumentUri = folder.uri.toString(),
                manifestFileSha256 = manifestHash,
                contentRevision = manifest.optJSONObject("install")
                    ?.optLong("contentRevision", 0L) ?: 0L,
                manifestState = "enriched"
            )
            val proposedAnalysis = analysisJson?.let {
                AnalysisEntity(
                    id = "$videoId:analysis",
                    songId = videoId,
                    sourceSha256 = originalHash,
                    algorithmVersion = "stemwise.analysis.v1",
                    primaryBpm = analysisProjection.bpm,
                    musicalKey = boundedCatalogText(
                        analysisProjection.musicalKey,
                        CATALOG_TOKEN_MAX_CHARS
                    ),
                    keyConfidence = analysisProjection.keyConfidence,
                    likelyMeter = boundedCatalogText(
                        analysisProjection.meter,
                        CATALOG_TOKEN_MAX_CHARS
                    ),
                    tuningCents = analysisProjection.tuningCents,
                    keyStatus = analysisProjection.keyStatus,
                    keyReason = boundedCatalogText(analysisProjection.keyReason, CATALOG_SECONDARY_TEXT_MAX_CHARS),
                    tuningStatus = analysisProjection.tuningStatus,
                    tuningReason = boundedCatalogText(analysisProjection.tuningReason, CATALOG_SECONDARY_TEXT_MAX_CHARS),
                    analyzedAtEpochMs = now
                )
            }
            val existingAnalysis = reuseFacts?.analysesBySongId?.get(videoId)
            val analysis = proposedAnalysis?.let { proposed ->
                existingAnalysis?.let { prior ->
                    proposed.copy(analyzedAtEpochMs = prior.analyzedAtEpochMs)
                        .takeIf { it == prior }
                } ?: proposed
            }
            val song = Song(
                id = videoId,
                videoId = videoId,
                title = title,
                artist = artist,
                durationMs = durationMs,
                state = SongState.READY,
                accent = accentFor(videoId),
                album = album,
                year = year,
                bpm = analysisProjection.bpm?.toFloat(),
                keyStatus = analysisProjection.keyStatus,
                keyReason = boundedCatalogText(analysisProjection.keyReason, CATALOG_SECONDARY_TEXT_MAX_CHARS),
                key = boundedCatalogText(
                    analysisProjection.musicalKey,
                    CATALOG_TOKEN_MAX_CHARS
                ),
                meter = boundedCatalogText(
                    analysisProjection.meter,
                    CATALOG_TOKEN_MAX_CHARS
                ),
                hasStems = hasCompleteStemSet,
                artworkGlyph = artworkGlyph(title),
                canonicalUrl = "https://music.youtube.com/watch?v=$videoId",
                sourceHash = originalHash,
                artworkSource = artworkSource,
                sourcePath = originalFile.uri.toString(),
                sourceCodec = originalCodec,
                sourceContainer = originalContainer,
                audioRevision = originalHash,
                mediaRevision = manifestHash,
                stemRevision = (customManifest?.revisionSha256?.let { "$it|" } ?: "") +
                    stemEntities.sortedBy(StemAssetEntity::id)
                    .joinToString("|") { "${it.id}:${it.sha256}" },
                lyricTracks = emptyList(),
                additionalInfo = additionalInfo
            )
            val promoted = promotedCanonicalPackageProjection(
                song = roomSong,
                analysis = analysis,
                existingSong = existing
            )
            val reusedCommit = reuseFacts?.takeIf { facts ->
                canonicalProjectionMatchesReuseFacts(
                    videoId = videoId,
                    source = sourceEntity,
                    sets = listOfNotNull(set),
                    stems = stemEntities,
                    observations = observations,
                    promoted = promoted,
                    facts = facts
                )
            }?.let { promoted }
            return ProjectedPackage(
                song,
                roomSong,
                sourceEntity,
                listOfNotNull(set),
                if (set == null) emptyMap() else mapOf(setId to stemEntities),
                analysis,
                observations,
                reusedCommit
            )
        }
        try {
            return projectSources(songSnapshot, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            canonicalProjectionRootAccessFailure(error)?.let { throw it }
            if (retryablePackageReadFailure(error)) throw error
            val candidate = withoutOptionalSources(songSnapshot)
            if (candidate.files == songSnapshot.files && songSnapshot.files.keys.none { it in SOURCE_JOB_AUTHORITY_FILES }) throw error
            try {
                return projectSources(candidate, true).copy(optionalProblem = PackageProblem(songSnapshot.videoId,
                    error.message?.takeIf(ERROR_CODE::matches) ?: "OPTIONAL_PACKAGE_REPAIR_REQUIRED",
                    "The song is available. Retry parts recovery, or open Song details and delete its prepared parts."))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (optionalFailure: Throwable) {
                canonicalProjectionRootAccessFailure(optionalFailure)?.let { throw it }
                if (retryablePackageReadFailure(optionalFailure)) throw optionalFailure
                throw error
            }
        }
    }

    private fun canonicalProjectionMatchesReuseFacts(
        videoId: String,
        source: SourceVersionEntity,
        sets: List<SeparationSetEntity>,
        stems: List<StemAssetEntity>,
        observations: List<AssetObservationEntity>,
        promoted: CanonicalPackageProjectionCommit,
        facts: CanonicalProjectionReuseFacts
    ): Boolean {
        if (videoId in facts.songIdsNeedingCatalogSanitization) return false
        val existingSets = facts.setsBySongId[videoId].orEmpty()
        val existingStems = existingSets.flatMap { facts.stemsBySetId[it.id].orEmpty() }
        return facts.songsById[videoId] == promoted.song &&
            facts.sourceVersionsBySongId[videoId].orEmpty().sortedBy(SourceVersionEntity::id) ==
                listOf(source) &&
            existingSets.sortedBy(SeparationSetEntity::id) ==
                sets.sortedBy(SeparationSetEntity::id) &&
            existingStems.sortedBy(StemAssetEntity::id) == stems.sortedBy(StemAssetEntity::id) &&
            facts.analysesBySongId[videoId] == promoted.analysis &&
            facts.observationsByPackageId[videoId].orEmpty()
                .sortedBy(AssetObservationEntity::relativePath) ==
                observations.sortedBy(AssetObservationEntity::relativePath)
    }

    private suspend fun verifyFlatDocument(
        file: SelectedDocumentSnapshot,
        cached: AssetObservationEntity?
    ): FlatDocumentVerification {
        val measured = measureFlatDocument(file)
        if (
            reusableFlatAssetObservation(
                file.uri.toString(),
                file.sizeBytes,
                file.lastModifiedEpochMs,
                cached,
                measured.sha256
            )
        ) {
            return FlatDocumentVerification(
                sha256 = requireNotNull(cached).expectedSha256,
                byteLength = cached.byteLength,
                verifiedAtEpochMs = cached.verifiedAtEpochMs,
                reused = true
            )
        }
        return FlatDocumentVerification(
            measured.sha256,
            measured.byteLength,
            System.currentTimeMillis(),
            reused = false
        )
    }

    private suspend fun measureFlatDocument(
        file: SelectedDocumentSnapshot
    ): ProviderDocumentIdentity = measureSelectedLibraryDocument(context, file) {
        backgroundWorkCoordinator.checkpoint()
    }

    private suspend fun probeFlatAudio(file: DocumentFile): FlatAudioFacts {
        return context.contentResolver.withCancellableProviderAssetFileDescriptor(file.uri) { descriptor ->
            val extractor = MediaExtractor()
            try {
                if (descriptor.declaredLength >= 0L) {
                    extractor.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.declaredLength
                    )
                } else {
                    extractor.setDataSource(descriptor.fileDescriptor)
                }
                val format = (0 until extractor.trackCount)
                    .map(extractor::getTrackFormat)
                    .firstOrNull {
                        it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    ?: error("MEDIA_UNSUPPORTED")
                FlatAudioFacts(
                    mime = requireNotNull(format.getString(MediaFormat.KEY_MIME)),
                    durationMs = format.longOrNull(MediaFormat.KEY_DURATION)
                        ?.let { (it + 500L) / 1_000L } ?: 0L,
                    sampleRateHz = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                )
            } finally {
                extractor.release()
            }
        } ?: error("ASSET_MISSING")
    }

    private suspend fun inspectFlatOpus(
        file: SelectedDocumentSnapshot,
        verification: FlatDocumentVerification
    ): OggOpusFacts {
        backgroundWorkCoordinator.checkpoint()
        val inspectionContext = currentCoroutineContext()
        return context.contentResolver.withCancellableProviderInput(file.uri) { input, _ ->
            runCancellableBlockingOperation(cancelExact = input::close) {
                OggOpusInspector.inspect(input, verification.byteLength) { inspectionContext.ensureActive() }
            }
        } ?: error("ASSET_MISSING")
    }

    private suspend fun inspectFlatSourceAudio(
        file: SelectedDocumentSnapshot, verification: FlatDocumentVerification,
        allowHighPrecisionFlac: Boolean = false
    ): PreparedSourceAudioFacts {
        backgroundWorkCoordinator.checkpoint()
        val inspectionContext = currentCoroutineContext()
        return context.contentResolver.withCancellableProviderInput(file.uri) { input, _ ->
            runCancellableBlockingOperation(cancelExact = input::close) {
                inspectPreparedSourceAudio(input, verification.byteLength, allowHighPrecisionFlac) { inspectionContext.ensureActive() }
            }
        } ?: error("ASSET_MISSING")
    }

    private suspend fun readOriginalTags(file: DocumentFile): Map<Int, String> =
        context.contentResolver.withCancellableProviderAssetFileDescriptor(file.uri) { descriptor ->
            val retriever = MediaMetadataRetriever()
            try {
                if (descriptor.declaredLength >= 0L) {
                    retriever.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                } else retriever.setDataSource(descriptor.fileDescriptor)
                listOf(MediaMetadataRetriever.METADATA_KEY_TITLE, MediaMetadataRetriever.METADATA_KEY_ARTIST,
                    MediaMetadataRetriever.METADATA_KEY_ALBUM, MediaMetadataRetriever.METADATA_KEY_YEAR)
                    .mapNotNull { key -> ownerVisibleTextOrNull(retriever.extractMetadata(key))?.let { key to it } }
                    .toMap()
            } finally { retriever.release() }
        }.orEmpty()

    private fun flatObservation(
        videoId: String,
        fileName: String,
        file: DocumentFile,
        snapshot: SelectedDocumentSnapshot,
        verification: FlatDocumentVerification,
        revision: String
    ) = AssetObservationEntity(
        packageId = videoId,
        relativePath = fileName,
        documentUri = file.uri.toString(),
        byteLength = verification.byteLength,
        lastModifiedEpochMs = snapshot.lastModifiedEpochMs ?: 0L,
        expectedSha256 = verification.sha256,
        manifestFileSha256 = revision,
        verifiedAtEpochMs = verification.verifiedAtEpochMs
    )

    private fun flatSourceId(videoId: String, hash: String) =
        "$videoId:original:${hash.take(16)}"

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    private data class FlatAudioFacts(
        val mime: String,
        val durationMs: Long,
        val sampleRateHz: Int?,
        val channelCount: Int?
    )

    private data class FlatDocumentVerification(
        val sha256: String,
        val byteLength: Long,
        val verifiedAtEpochMs: Long,
        val reused: Boolean
    )


    private suspend fun readBounded(file: SelectedDocumentSnapshot, max: Long): ByteArray {
        return readBoundedProviderDocument(context, file.uri, file.sizeBytes, max)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHexString()

    private fun accentFor(packageId: String): Color =
        SONG_ARTWORK_ACCENTS[(packageId.hashCode() and Int.MAX_VALUE) % SONG_ARTWORK_ACCENTS.size]

    private data class ProjectedPackage(
        val song: Song,
        val roomSong: SongEntity,
        val roomSource: SourceVersionEntity,
        val sets: List<SeparationSetEntity>,
        val stems: Map<String, List<StemAssetEntity>>,
        val analysis: AnalysisEntity?,
        val observations: List<AssetObservationEntity>,
        val reusedCommit: CanonicalPackageProjectionCommit? = null,
        val optionalProblem: PackageProblem? = null,
    )

    companion object {
        private const val CANONICAL_REUSE_BATCH_SIZE = 512
        private const val PROVIDER_REFRESH_DEBOUNCE_MS = 750L
        private val ROOT_ACCESS_ERROR_CODES = setOf(
            "ROOT_UNAVAILABLE",
            "ROOT_PERMISSION_LOST",
            "ROOT_LAYOUT_UNSUPPORTED"
        )
        private const val STORAGE_PREFERENCES = "storage_snapshots"
        private const val MAX_MANIFEST_BYTES = app.stemwise.enrichment.ENRICHMENT_ARTIFACT_MAX_BYTES
        private const val MAX_PNG_BYTES = 16L * 1024L * 1024L
        private val ERROR_CODE = Regex("^[A-Z][A-Z0-9_]+$")
    }
}
