package app.stemwise.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.AtomicFile
import androidx.room.withTransaction
import app.stemwise.integration.EngineState
import app.stemwise.integration.PlaylistLink
import app.stemwise.integration.MediaIdentity
import app.stemwise.integration.YoutubeDlEngine
import app.stemwise.integration.playlistMetadataRequest
import app.stemwise.integration.PRIVATE_PREPARATION_SCRATCH_NAMES
import app.stemwise.integration.withCancellableProviderInput
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.model.StemRoleContract
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.whileUnmetered
import app.stemwise.network.WAITING_FOR_UNMETERED_NETWORK
import app.stemwise.suspendResult
import app.stemwise.ui.theme.Amber
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.Cyan
import app.stemwise.ui.theme.Indigo
import app.stemwise.ui.theme.Lime
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.Orange
import app.stemwise.ui.theme.Rose
import app.stemwise.ui.theme.Sky
import app.stemwise.ui.theme.Violet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class PlaylistSyncSummary(
    val discovered: Int,
    val playlistName: String,
    val completedAtEpochMs: Long,
    val projectionRefreshPending: Boolean = false
)

data class PlaylistSyncStatus(
    val running: Boolean = false,
    val queued: Boolean = false,
    val waitingForNetwork: Boolean = false,
    val lastSummary: PlaylistSyncSummary? = null,
    val error: String? = null
)

internal val SONG_ARTWORK_ACCENTS = listOf(
    PrimaryPurple, Sky, Violet, Coral, Amber,
    Cyan, Rose, Indigo, Lime, Orange
)
private val ARTWORK_GLYPH_WHITESPACE = Regex("""\s+""")

internal fun artworkGlyph(title: String): String =
    title.split(ARTWORK_GLYPH_WHITESPACE)
        .asSequence()
        .filter { it.isNotBlank() }
        .take(2)
        .map { firstDisplayGrapheme(it).uppercase(Locale.ROOT) }
        .joinToString("")
        .ifBlank { "♪" }

/** Accepts metadata strings; JSON null and other non-string values remain absent. */
internal fun ownerVisibleTextOrNull(value: Any?): String? =
    (value as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { boundedDisplayText(it, SelectedLibraryContract.MAXIMUM_METADATA_CHARACTERS) }

internal fun JSONObject.ownerVisibleString(key: String): String? =
    ownerVisibleTextOrNull(opt(key)?.takeUnless { it === JSONObject.NULL })

internal data class OwnerVisibleSongIdentity(
    val title: String,
    val artist: String
)

internal fun unavailablePlaylistItemTitle(videoId: String): String =
    "Unavailable playlist item · ${videoId.take(8).ifBlank { "unknown" }}"

internal const val PLAYLIST_ITEM_UNAVAILABLE_CODE = "PLAYLIST_ITEM_UNAVAILABLE"
internal fun normalizedApprovedArtworkUrl(source: String): String? {
    if (source.length > SelectedLibraryContract.MAXIMUM_METADATA_CHARACTERS) return null
    val parsed = runCatching { URL(source) }.getOrNull() ?: return null
    if (parsed.protocol != "https" || !isApprovedArtworkHost(parsed.host)) return null
    val upgraded = when {
        parsed.host.equals("lh3.googleusercontent.com", ignoreCase = true) ->
            source.substringBeforeLast('=') + "=w1200-h1200-l90-rj"
        else -> source
    }
    return upgraded.takeIf {
        it.length <= SelectedLibraryContract.MAXIMUM_METADATA_CHARACTERS
    }
}

private fun isApprovedArtworkHost(host: String): Boolean {
    val normalized = host.lowercase(Locale.ROOT)
    return normalized == "ytimg.com" ||
        normalized.endsWith(".ytimg.com") ||
        normalized == "googleusercontent.com" ||
        normalized.endsWith(".googleusercontent.com") ||
        normalized == "youtube.com" ||
        normalized.endsWith(".youtube.com")
}

/** Authoritative read-boundary repair for new input and already-persisted malformed identity. */
internal fun ownerVisibleSongIdentity(
    videoId: String,
    displayTitle: String?,
    displayArtist: String?,
    sourceTitle: String?,
    sourceArtist: String?
): OwnerVisibleSongIdentity {
    val usableSourceTitle = ownerVisibleTextOrNull(sourceTitle)
    val usableSourceArtist = ownerVisibleTextOrNull(sourceArtist)
        ?.takeUnless { it.equals("Unknown artist", ignoreCase = true) }
    val inferred = usableSourceTitle?.let { title ->
        val separator = title.indexOf(" - ")
        if (
            usableSourceArtist == null && separator in 2..60 &&
            separator < title.lastIndex - 2
        ) {
            ownerVisibleTextOrNull(title.substring(separator + 3)) to
                ownerVisibleTextOrNull(title.substring(0, separator))
        } else {
            title to usableSourceArtist
        }
    }
    return OwnerVisibleSongIdentity(
        title = ownerVisibleTextOrNull(displayTitle)
            ?: inferred?.first
            ?: unavailablePlaylistItemTitle(videoId),
        artist = ownerVisibleTextOrNull(displayArtist)
            ?: inferred?.second
            ?: "Unknown artist"
    )
}

/**
 * Validates the complete app-generated acquisition tree before callers delete its first byte.
 * Kept independently testable so an unknown nested entry is proven fail-closed.
 */
internal fun requireOwnedPreparationScratchTree(scratch: File) {
    val failure = "PREPARATION_CLEANUP_UNKNOWN_ENTRY"
    fun directChildren(directory: File): List<File> {
        val canonical = directory.canonicalFile
        return checkedPrivateChildren(canonical, "PREPARATION_CLEANUP_FAILED").also { children ->
            check(children.all { it.canonicalFile.parentFile == canonical }) { failure }
        }
    }
    directChildren(scratch).forEach { child ->
        check(child.isFile && (child.name in PRIVATE_PREPARATION_SCRATCH_NAMES ||
            isWorkingMediaCleanupRefusalMarker(child))) { failure }
    }
}

internal data class PlaylistSessionToken(
    val libraryId: String,
    val rootLocator: String,
    val epoch: Long
)

/**
 * Invalidates both active and mutex-queued playlist work across a Library switch.
 * The guard is deliberately independent of coroutine cancellation: a waiter may
 * not yet have a Job registered when the switch begins, but its captured epoch
 * still becomes stale before it can mutate Room.
 */
internal class PlaylistSessionEpoch {
    private val monitor = Any()
    private var epoch = 0L
    private var acceptingWork = true
    private var exclusiveToken: PlaylistSessionToken? = null

    fun capture(libraryId: String?, rootLocator: String?): PlaylistSessionToken =
        synchronized(monitor) {
            check(acceptingWork) { "LIBRARY_SWITCH_IN_PROGRESS" }
            check(!libraryId.isNullOrBlank() && !rootLocator.isNullOrBlank()) {
                "ROOT_UNAVAILABLE"
            }
            PlaylistSessionToken(libraryId, rootLocator, epoch)
        }

    fun isCurrent(
        token: PlaylistSessionToken,
        libraryId: String?,
        rootLocator: String?
    ): Boolean = synchronized(monitor) {
        acceptingWork &&
            token.epoch == epoch &&
            token.libraryId == libraryId &&
            token.rootLocator == rootLocator
    }

    fun requireCurrent(
        token: PlaylistSessionToken,
        libraryId: String?,
        rootLocator: String?
    ) {
        check(isCurrent(token, libraryId, rootLocator)) { "PLAYLIST_SESSION_STALE" }
    }

    fun invalidate() = synchronized(monitor) {
        acceptingWork = false
        exclusiveToken = null
        epoch++
    }

    fun beginExclusive(
        token: PlaylistSessionToken,
        libraryId: String?,
        rootLocator: String?
    ) = synchronized(monitor) {
        check(
            acceptingWork &&
                exclusiveToken == null &&
                token.epoch == epoch &&
                token.libraryId == libraryId &&
                token.rootLocator == rootLocator
        ) { "PLAYLIST_CANDIDATE_STALE" }
        acceptingWork = false
        exclusiveToken = token
    }

    fun requireExclusive(
        token: PlaylistSessionToken,
        libraryId: String?,
        rootLocator: String?
    ) = synchronized(monitor) {
        check(
            !acceptingWork &&
                exclusiveToken == token &&
                token.epoch == epoch &&
                token.libraryId == libraryId &&
                token.rootLocator == rootLocator
        ) { "PLAYLIST_COMMIT_SESSION_STALE" }
    }

    fun activate() = synchronized(monitor) {
        epoch++
        exclusiveToken = null
        acceptingWork = true
    }
}

class PlaylistCandidate internal constructor(
    val link: PlaylistLink,
    val playlistName: String,
    internal val discoveredSongs: List<SongEntity>,
    val currentPlaylistId: String,
    val currentPlaylistCount: Int,
    val existingLibraryCount: Int,
    val localOnlyCount: Int,
    internal val reviewedMembershipSha256: String,
    internal val session: PlaylistSessionToken
) {
    private val committed = AtomicBoolean(false)
    val songCount: Int get() = discoveredSongs.size
    internal val sessionLibraryId: String get() = session.libraryId
    internal val sessionEpoch: Long get() = session.epoch
    internal fun claimCommit(): Boolean = committed.compareAndSet(false, true)
}

internal enum class PlaylistCommitMode { REFRESH_CURRENT, KEEP_AND_ADD }

internal fun completePlaylistEntries(root: JSONObject, playlistId: String): JSONArray {
    check(root.optString("_type") == "playlist" && root.optString("id") == playlistId) {
        "PLAYLIST_RESPONSE_INCOMPLETE"
    }
    val entries = root.opt("entries") as? JSONArray ?: error("PLAYLIST_RESPONSE_INCOMPLETE")
    for (key in listOf("playlist_count", "n_entries")) {
        if (root.has(key) && !root.isNull(key)) {
            val count = root.opt(key) as? Number ?: error("PLAYLIST_RESPONSE_INCOMPLETE")
            check(count.toDouble() == entries.length().toDouble()) { "PLAYLIST_RESPONSE_INCOMPLETE" }
        }
    }
    return entries
}

internal data class PlaylistCommitLease(
    val id: String,
    val candidateSession: PlaylistSessionToken,
    val rootAuthority: RootAuthority
)

internal fun canonicalRootSourceLocation(
    storedLocation: String,
    resolvedActiveRootLocation: String?
): String? = storedLocation.takeIf {
    resolvedActiveRootLocation != null &&
        it.startsWith("content://") &&
        it == resolvedActiveRootLocation
}

class PlaylistRepository internal constructor(
    private val context: Context,
    private val database: LibraryDatabase,
    private val dao: LibraryDao,
    private val preferences: StemwisePreferences,
    private val engine: YoutubeDlEngine,
    private val songWorkCoordinator: SongWorkCoordinator,
    private val roots: LibraryRootManager,
    private val externalLibrary: ExternalLibraryRepository,
    private val backgroundWorkCoordinator: BackgroundWorkCoordinator,
    private val networkAccess: AppNetworkAccess,
    private val onPlaylistCommitted: suspend (RootAuthority) -> Unit = {}
) {
    private val syncMutex = Mutex()
    private val playlistSessions = PlaylistSessionEpoch()
    private val mutableSyncStatus = MutableStateFlow(PlaylistSyncStatus())
    val syncStatus: StateFlow<PlaylistSyncStatus> = mutableSyncStatus.asStateFlow()
    private val mutablePreparationOwner =
        MutableStateFlow<PreparationOwnerDecision>(PreparationOwnerDecision.Unresolved)
    val preparationOwner: StateFlow<PreparationOwnerDecision> =
        mutablePreparationOwner.asStateFlow()
    @Volatile private var activeSessionJob: Job? = null
    private val commitLeaseMonitor = Any()
    private var activeCommitLease: PlaylistCommitLease? = null

    @Volatile internal var beforeSongSnapshotEmissionForTest: (suspend () -> Unit)? = null
    val songs: Flow<List<Song>> = dao.observeSongSnapshots().map { snapshots ->
        beforeSongSnapshotEmissionForTest?.invoke()
        snapshots.map(::toSong)
    }.flowOn(Dispatchers.Default)

    suspend fun verifyPlaybackSong(songId: String): Song {
        val root = roots.captureHandle() ?: error("LIBRARY_CHANGED")
        val song = externalLibrary.verifyOriginalPlaybackSong(root, songId)
        roots.requireSameLibrary(root)
        return song
    }

    suspend fun sync(): Result<PlaylistSyncSummary> {
        val session = runCatching(::capturePlaylistSession)
            .getOrElse { return Result.failure(it) }
        return syncMutex.withLock {
            val owner = currentCoroutineContext()[Job]
            activeSessionJob = owner
            try {
                requirePlaylistSession(session)
                mutableSyncStatus.value = PlaylistSyncStatus(running = true)
                val playlist = preferences.playlist.value
                check(playlist.configured) {
                    "Share a YouTube Music playlist with StemWise first"
                }
                val candidate = fetchPlaylistCandidate(playlist.link, session)
                requirePlaylistSession(session)
                val summary = persistPlaylistCandidate(
                    candidate,
                    mode = PlaylistCommitMode.REFRESH_CURRENT
                )
                val published = try {
                    requirePlaylistSession(session)
                    preferences.recordSyncSuccess(summary.playlistName, summary.completedAtEpochMs)
                    summary
                } catch (error: Exception) {
                    summary.copy(projectionRefreshPending = true)
                }
                mutableSyncStatus.value = PlaylistSyncStatus(lastSummary = published)
                Result.success(published)
            } catch (cancelled: CancellationException) {
                mutableSyncStatus.value = PlaylistSyncStatus()
                throw cancelled
            } catch (error: Throwable) {
                if (isPlaylistSessionCurrent(session)) {
                    val message = friendlySyncError(error)
                    val handling = playlistSyncFailureHandling(error, message)
                    mutableSyncStatus.value = handling.status
                    try {
                        handling.persistentError?.let { preferences.recordSyncFailure(it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (diagnosticFailure: Throwable) {
                        if (diagnosticFailure !== error) error.addSuppressed(diagnosticFailure)
                    }
                } else {
                    mutableSyncStatus.value = PlaylistSyncStatus()
                }
                Result.failure(error)
            } finally {
                if (activeSessionJob === owner) activeSessionJob = null
            }
        }
    }

    suspend fun previewPlaylist(link: PlaylistLink): Result<PlaylistCandidate> {
        val session = runCatching(::capturePlaylistSession)
            .getOrElse { return Result.failure(it) }
        return syncMutex.withLock {
            val owner = currentCoroutineContext()[Job]
            activeSessionJob = owner
            try {
                requirePlaylistSession(session)
                mutableSyncStatus.value = PlaylistSyncStatus(running = true)
                val candidate = fetchPlaylistCandidate(link, session)
                requirePlaylistSession(session)
                mutableSyncStatus.value = PlaylistSyncStatus()
                Result.success(candidate)
            } catch (cancelled: CancellationException) {
                mutableSyncStatus.value = PlaylistSyncStatus()
                throw cancelled
            } catch (error: Throwable) {
                if (isPlaylistSessionCurrent(session)) {
                    mutableSyncStatus.value = playlistSyncFailureHandling(
                        error = error,
                        displayMessage = friendlySyncError(error)
                    ).status
                } else {
                    mutableSyncStatus.value = PlaylistSyncStatus()
                }
                Result.failure(error)
            } finally {
                if (activeSessionJob === owner) activeSessionJob = null
            }
        }
    }


    internal suspend fun beginExclusivePlaylistCommit(
        candidate: PlaylistCandidate,
        expectedRoot: RootAuthority
    ): PlaylistCommitLease {
        val snapshot = roots.state.value
        check(
            snapshot.ready &&
                snapshot.libraryId == expectedRoot.libraryId &&
                snapshot.treeUri?.toString() == expectedRoot.treeUri &&
                snapshot.epoch == expectedRoot.epoch
        ) { "LIBRARY_CHANGED" }
        val lease = synchronized(commitLeaseMonitor) {
            check(activeCommitLease == null) { "PLAYLIST_COMMIT_ALREADY_RUNNING" }
            playlistSessions.beginExclusive(
                candidate.session,
                snapshot.libraryId,
                snapshot.treeUri.toString()
            )
            PlaylistCommitLease(
                id = UUID.randomUUID().toString(),
                candidateSession = candidate.session,
                rootAuthority = expectedRoot
            ).also {
                activeCommitLease = it
            }
        }
        return lease
    }

    internal suspend fun retireExclusivePlaylistSession(lease: PlaylistCommitLease) {
        check(synchronized(commitLeaseMonitor) { activeCommitLease == lease }) { "PLAYLIST_COMMIT_LEASE_STALE" }
        activeSessionJob?.cancelAndJoin()
        activeSessionJob = null
    }

    internal fun requireExclusivePlaylistCommit(
        lease: PlaylistCommitLease,
        candidate: PlaylistCandidate,
        commitOwner: Job? = null
    ) {
        check(if (commitOwner == null) activeSessionJob == null else activeSessionJob === commitOwner) {
            "PLAYLIST_SESSION_RETIREMENT_PENDING"
        }
        check(synchronized(commitLeaseMonitor) { activeCommitLease == lease }) {
            "PLAYLIST_COMMIT_LEASE_STALE"
        }
        check(lease.candidateSession == candidate.session) { "PLAYLIST_CANDIDATE_STALE" }
        val snapshot = roots.state.value
        check(
            snapshot.ready &&
                snapshot.libraryId == lease.rootAuthority.libraryId &&
                snapshot.treeUri?.toString() == lease.rootAuthority.treeUri &&
                snapshot.epoch == lease.rootAuthority.epoch
        ) { "LIBRARY_CHANGED" }
        playlistSessions.requireExclusive(
            lease.candidateSession,
            snapshot.libraryId,
            snapshot.treeUri.toString()
        )
    }

    internal fun finishExclusivePlaylistCommit(
        lease: PlaylistCommitLease,
        reopenSession: Boolean
    ) {
        synchronized(commitLeaseMonitor) {
            if (activeCommitLease != lease) return
            activeCommitLease = null
            if (reopenSession) playlistSessions.activate() else playlistSessions.invalidate()
        }
    }

    internal suspend fun commitPlaylistCandidate(
        candidate: PlaylistCandidate,
        mode: PlaylistCommitMode,
        lease: PlaylistCommitLease,
        requireCurrentAuthorization: () -> Unit
    ): Result<PlaylistSyncSummary> {
        check(mode == PlaylistCommitMode.KEEP_AND_ADD) { "PLAYLIST_COMMIT_MODE_INVALID" }
        return syncMutex.withLock {
            val owner = checkNotNull(currentCoroutineContext()[Job])
            try {
                requireExclusivePlaylistCommit(lease, candidate)
                activeSessionJob = owner
                requireExclusivePlaylistCommit(lease, candidate, owner)
                mutableSyncStatus.value = PlaylistSyncStatus(running = true)
                val summary = persistPlaylistCandidate(
                    candidate = candidate,
                    mode = mode,
                    requireCurrentAuthorization = requireCurrentAuthorization,
                    requireCommitSession = {
                        requireExclusivePlaylistCommit(lease, candidate, owner)
                    }
                )
                mutableSyncStatus.value = PlaylistSyncStatus(lastSummary = summary)
                Result.success(summary)
            } catch (cancelled: CancellationException) {
                mutableSyncStatus.value = PlaylistSyncStatus()
                throw cancelled
            } catch (error: Throwable) {
                mutableSyncStatus.value = PlaylistSyncStatus(error = friendlySyncError(error))
                Result.failure(error)
            } finally {
                if (activeSessionJob === owner) activeSessionJob = null
            }
        }
    }

    private fun capturePlaylistSession(): PlaylistSessionToken {
        val handle = roots.captureHandle() ?: error("ROOT_UNAVAILABLE")
        return playlistSessions.capture(handle.libraryId, handle.treeUri.toString())
    }

    private fun isPlaylistSessionCurrent(session: PlaylistSessionToken): Boolean {
        val snapshot = roots.state.value
        return playlistSessions.isCurrent(
            session,
            snapshot.libraryId,
            snapshot.treeUri?.toString()
        )
    }

    private fun requirePlaylistSession(session: PlaylistSessionToken) {
        val snapshot = roots.state.value
        playlistSessions.requireCurrent(
            session,
            snapshot.libraryId,
            snapshot.treeUri?.toString()
        )
    }

    /** Retire the removed downloader's Room work; installed media is never touched. */
    suspend fun reconcilePreparationOwner(
        expectedRoot: RootHandle,
        damagedSongIds: List<String>? = null
    ): PreparationOwnerDecision = withContext(Dispatchers.IO) {
        songWorkCoordinator.preparationLane.withLock {
            roots.requireSameLibrary(expectedRoot)
            val authority = expectedRoot.authority()
            val retainedRecovery = mutablePreparationOwner.value as? PreparationOwnerDecision.Blocked
            if (damagedSongIds == null && retainedRecovery?.authority == authority &&
                retainedRecovery.recoverySongIds.isNotEmpty()) return@withLock retainedRecovery
            if (!damagedSongIds.isNullOrEmpty()) {
                return@withLock PreparationOwnerDecision.Blocked(authority,
                    app.stemwise.integration.PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED, damagedSongIds,
                    recoverySongIds = damagedSongIds).also { mutablePreparationOwner.value = it }
            }
            // One joined read replaces per-job source/current-source lookups after any
            // terminalization writes have committed.
            val jobs = dao.automaticPreparationOwnerFacts()
            if (jobs.isNotEmpty()) {
                val authorities = jobs.mapNotNull { job ->
                    app.stemwise.integration.readPortableSourceJobAuthority(roots, expectedRoot, job.songId)
                }.associateBy { it.jobId }
                roots.requireSameLibrary(expectedRoot)
                return@withLock arbitratePreparationOwner(authority, jobs,
                    authorities.mapValues { it.value.generation },
                    jobs.mapNotNull { job ->
                        val portable = authorities[job.jobId]
                        portable?.currentRemoteJobId?.takeIf {
                            portable.state == job.state && portable.state in setOf("PROCESSING", "DOWNLOADING", "ASSEMBLING", "PUBLISHING")
                        }?.let { job.jobId to it }
                    }.toMap()).also {
                    mutablePreparationOwner.value = it
                }
            }
            roots.requireSameLibrary(expectedRoot)
            PreparationOwnerDecision.None(authority).also {
                mutablePreparationOwner.value = it
            }
        }
    }

    internal suspend fun blockPreparationAfterRetirement(expectedRoot: RootHandle, jobId: String,
        songId: String, code: String) = songWorkCoordinator.preparationLane.withLock {
        roots.requireSameLibrary(expectedRoot)
        mutablePreparationOwner.value = PreparationOwnerDecision.Blocked(expectedRoot.authority(), code,
            listOf(songId), recoverableJobId = jobId)
    }

    fun preparationOwnerAllowsAutomaticEnqueue(expectedRoot: RootHandle): Boolean {
        val owner = preparationOwner.value
        return owner.matches(expectedRoot.authority()) &&
            owner !is PreparationOwnerDecision.Blocked &&
            owner !is PreparationOwnerDecision.Unresolved
    }

    suspend fun cancelSessionWorkForLibrarySwitch() {
        // Invalidate first. Mutex waiters that have not registered as the active
        // Job are then rejected when they eventually acquire the lock.
        synchronized(commitLeaseMonitor) { activeCommitLease = null; playlistSessions.invalidate() }
        activeSessionJob?.cancelAndJoin()
        activeSessionJob = null
    }

    /** Reopens playlist work only after the coordinator publishes or restores a root. */
    fun activateCurrentLibrarySession() {
        check(roots.state.value.ready) { "ROOT_UNAVAILABLE" }
        synchronized(commitLeaseMonitor) { activeCommitLease = null; playlistSessions.activate() }
    }

    /** Binds the new generation while its exact committed root is still fail-closed RESTORING. */
    fun activateCurrentLibrarySession(expectedRoot: LibraryRootState) {
        roots.requireSameIdentity(expectedRoot)
        synchronized(commitLeaseMonitor) { activeCommitLease = null; playlistSessions.activate() }
    }

    private suspend fun fetchPlaylistCandidate(
        link: PlaylistLink,
        session: PlaylistSessionToken
    ): PlaylistCandidate =
        withContext(Dispatchers.IO) {
            require(
                app.stemwise.integration.PlaylistLinks.parse(link.canonicalUrl)?.id == link.id &&
                    link.id.isNotBlank()
            ) { "PLAYLIST_IDENTITY_INVALID" }
            networkAccess.requireUnmetered()
            engine.ensureReady().getOrThrow()
            check(engine.status.value.state == EngineState.READY) {
                engine.status.value.error ?: "The playlist extractor did not start"
            }
            val request = playlistMetadataRequest(link.canonicalUrl)
            val response = networkAccess.whileUnmetered {
                currentCoroutineContext().ensureActive()
                engine.execute(request, PLAYLIST_SYNC_PROCESS_ID)
            }
            parsePlaylistCandidate(response.out, link, session)
        }

    private suspend fun parsePlaylistCandidate(
        rawJson: String,
        link: PlaylistLink,
        session: PlaylistSessionToken
    ): PlaylistCandidate {
        val root = JSONObject(rawJson.trim())
        val playlistName = root.ownerVisibleString("title") ?: "YouTube Music playlist"
        val entries = completePlaylistEntries(root, link.id)
        val occurrences = buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: error("PLAYLIST_RESPONSE_INCOMPLETE")
                add(parseEntry(entry) ?: error("PLAYLIST_RESPONSE_INCOMPLETE"))
            }
        }
        val existingSongs = dao.allSongs()
        val existingPlaylistCount = existingSongs.count { it.currentlyInSourcePlaylist }
        val localOnlyCount = existingSongs.size - existingPlaylistCount
        val currentPlaylistId = preferences.playlist.value.link.id
        return PlaylistCandidate(
            link = link,
            playlistName = playlistName,
            discoveredSongs = occurrences,
            currentPlaylistId = currentPlaylistId,
            currentPlaylistCount = existingPlaylistCount,
            existingLibraryCount = existingSongs.size,
            localOnlyCount = localOnlyCount,
            reviewedMembershipSha256 = playlistMembershipSha256(existingSongs),
            session = session
        )
    }

    private data class BuiltPlaylistState(
        val mergedCandidates: List<SongEntity>,
        val finalSongs: List<SongEntity>,
        val remotePlaylist: RemotePlaylistEntity,
        val remoteItems: List<RemotePlaylistItemEntity>,
        val authority: PlaylistRoomAuthority,
        val summary: PlaylistSyncSummary
    )

    internal suspend fun persistPlaylistCandidate(
        candidate: PlaylistCandidate,
        mode: PlaylistCommitMode,
        requireCurrentAuthorization: () -> Unit = {},
        requireCommitSession: () -> Unit = {}
    ): PlaylistSyncSummary = withContext(Dispatchers.IO) {
        val committedRoot = roots.captureHandle() ?: error("ROOT_UNAVAILABLE")
        check(committedRoot.libraryId == candidate.sessionLibraryId &&
            committedRoot.treeUri.toString() == candidate.session.rootLocator) { "LIBRARY_CHANGED" }
        val committedAt = System.currentTimeMillis().coerceAtLeast(1L)
        val built = database.withTransaction {
            if (mode == PlaylistCommitMode.KEEP_AND_ADD) requireCommitSession()
            else requirePlaylistSession(candidate.session)
            val selected = dao.selectedPlaylist(candidate.sessionLibraryId)
            check(selected?.playlistId.orEmpty() == candidate.currentPlaylistId) {
                "PLAYLIST_CANDIDATE_STALE"
            }
            val existing = dao.allSongs()
            requireReviewedPlaylistState(candidate, existing)
            val state = buildPlaylistState(candidate, existing, mode, committedAt)
            // The exact review/root lease is checked adjacent to the first durable write.
            requireCurrentAuthorization()
            roots.requireSameLibrary(committedRoot)
            if (mode == PlaylistCommitMode.KEEP_AND_ADD) {
                requireCommitSession()
                check(candidate.claimCommit()) { "PLAYLIST_CANDIDATE_ALREADY_COMMITTED" }
            }
            applyPlaylistState(state)
            dao.upsertSelectedPlaylist(SelectedPlaylistEntity(candidate.sessionLibraryId,
                candidate.link.id, candidate.link.canonicalUrl, candidate.playlistName, committedAt, null))
            check(dao.playlistRoomAuthority() == state.authority) { "PLAYLIST_COMMIT_VERIFY_FAILED" }
            state
        }
        // Projection publication follows the Room commit. Restart reads the same row directly.
        try {
            roots.requireSameLibrary(committedRoot)
            preferences.activateLibraryFromRoot(candidate.sessionLibraryId)
            roots.requireSameLibrary(committedRoot)
            onPlaylistCommitted(committedRoot.authority())
            built.summary
        } catch (error: Exception) {
            // The transaction above is the commit point; refresh cannot undo its result.
            android.util.Log.w("StemwiseLibrary", "Playlist committed; projection refresh needs a retry", error)
            built.summary.copy(projectionRefreshPending = true)
        }
    }

    private fun requireReviewedPlaylistState(
        candidate: PlaylistCandidate,
        existing: List<SongEntity>
    ) {
        check(existing.size == candidate.existingLibraryCount) { "PLAYLIST_CANDIDATE_STALE" }
        check(existing.count(SongEntity::currentlyInSourcePlaylist) == candidate.currentPlaylistCount) {
            "PLAYLIST_CANDIDATE_STALE"
        }
        check(playlistMembershipSha256(existing) == candidate.reviewedMembershipSha256) {
            "PLAYLIST_CANDIDATE_STALE"
        }
        check(preferences.playlist.value.link.id == candidate.currentPlaylistId) {
            "PLAYLIST_PREFERENCE_IDENTITY_STALE"
        }
    }

    private suspend fun buildPlaylistState(
        candidate: PlaylistCandidate,
        existing: List<SongEntity>,
        mode: PlaylistCommitMode,
        now: Long
    ): BuiltPlaylistState {
        val occurrencesByVideo = candidate.discoveredSongs.groupBy(SongEntity::youtubeVideoId)
        val discovered = occurrencesByVideo.values.map { it.first() }
        val refreshingSamePlaylist =
            candidate.currentPlaylistId.isBlank() || candidate.currentPlaylistId == candidate.link.id
        if (mode == PlaylistCommitMode.REFRESH_CURRENT) {
            check(refreshingSamePlaylist) { "PLAYLIST_REFRESH_IDENTITY_CHANGED" }
        }
        val existingByVideoId = existing.associateBy(SongEntity::youtubeVideoId)
        val merged = discovered.mapIndexed { index, item ->
            val old = existingByVideoId[item.youtubeVideoId]
            val hasPackage = old != null && (old.canonicalExternal || !old.packageDocumentUri.isNullOrBlank())
            val sameMember = refreshingSamePlaylist && old?.currentlyInSourcePlaylist == true &&
                (old.playlistItemId == null || occurrencesByVideo.getValue(item.youtubeVideoId).any {
                    it.playlistItemId == null || it.playlistItemId == old.playlistItemId
                })
            val discoveryDismissed = sameMember && old?.pcDiscoveryDismissed == true
            val nextState = when {
                hasPackage -> checkNotNull(old).highLevelState
                discoveryDismissed -> item.highLevelState
                old?.highLevelState == SongState.AWAITING_PC.name -> SongState.AWAITING_PC.name
                (old == null || !sameMember || old.highLevelState == SongState.NEEDS_ATTENTION.name) &&
                    item.highLevelState != SongState.NEEDS_ATTENTION.name -> SongState.AWAITING_PC.name
                else -> item.highLevelState
            }
            if (old == null) {
                item.copy(createdAtEpochMs = now + index, highLevelState = nextState)
            } else {
                old.copy(
                    playlistItemId = old.playlistItemId.takeIf { sameMember } ?: item.playlistItemId,
                    canonicalUrl = item.canonicalUrl,
                    sourceTitle = item.sourceTitle,
                    sourceArtist = item.sourceArtist,
                    durationMs = if (old.canonicalExternal || !old.packageDocumentUri.isNullOrBlank())
                        old.durationMs else item.durationMs.takeIf { it > 0L } ?: old.durationMs,
                    highLevelState = nextState,
                    pcDiscoveryDismissed = discoveryDismissed,
                    additionalInfoJson = mergeAdditionalInfo(
                        old.additionalInfoJson,
                        parseRoomAdditionalInfo(item.additionalInfoJson)
                    ),
                    artworkPath = old.artworkPath
                        ?.takeIf {
                            it.startsWith("content://") ||
                                !it.startsWith("http") && File(it).isFile
                        }
                        ?: item.artworkPath
                        ?: old.artworkPath,
                    currentlyInSourcePlaylist = true
                )
            }
        }
        val mergedById = merged.associateBy(SongEntity::id)
        val activePackageIds = dao.activeProcessingSongIds().toHashSet()
        val existingIds = existing.mapTo(hashSetOf(), SongEntity::id)
        val finalSongs = buildList {
            existing.filter { it.canonicalExternal || !it.packageDocumentUri.isNullOrBlank() ||
                it.highLevelState == SongState.AWAITING_PC.name ||
                it.id in mergedById || it.id in activePackageIds }.forEach { old ->
                add(
                    mergedById[old.id]
                        ?: old.copy(
                            playlistItemId = null,
                            currentlyInSourcePlaylist = false
                        )
                )
            }
            merged.filterTo(this) { it.id !in existingIds }
        }
        val remotePlaylist = RemotePlaylistEntity(
            id = candidate.link.id,
            provider = "youtube-music",
            canonicalUrl = candidate.link.canonicalUrl,
            displayName = candidate.playlistName,
            snapshotRevision = now,
            synchronizedAtEpochMs = now
        )
        val occurrenceCounts = mutableMapOf<String, Int>()
        val declaredItemCounts = candidate.discoveredSongs.mapNotNull { it.playlistItemId }.groupingBy { it }.eachCount()
        val remoteItems = candidate.discoveredSongs.mapIndexed { position, item ->
            val occurrence = occurrenceCounts.getOrDefault(item.youtubeVideoId, 0)
            occurrenceCounts[item.youtubeVideoId] = occurrence + 1
            RemotePlaylistItemEntity(
                playlistId = candidate.link.id,
                remoteItemId = item.playlistItemId
                    ?.takeIf { it.isNotBlank() && declaredItemCounts[it] == 1 }
                    ?: "video:${item.youtubeVideoId}:occurrence:$occurrence",
                sourceId = "youtube-video:${item.youtubeVideoId}",
                packageId = item.id,
                position = position,
                available = item.highLevelState != SongState.NEEDS_ATTENTION.name
            )
        }
        check(remoteItems.map { it.remoteItemId }.toSet().size == remoteItems.size) { "PLAYLIST_RESPONSE_INCOMPLETE" }
        val authority = playlistRoomAuthority(
            songs = finalSongs,
            playlists = listOf(remotePlaylist),
            items = remoteItems
        )
        return BuiltPlaylistState(
            mergedCandidates = merged,
            finalSongs = finalSongs,
            remotePlaylist = remotePlaylist,
            remoteItems = remoteItems,
            authority = authority,
            summary = PlaylistSyncSummary(
                discovered = candidate.discoveredSongs.size,
                playlistName = candidate.playlistName,
                completedAtEpochMs = now
            )
        )
    }

    private suspend fun applyPlaylistState(state: BuiltPlaylistState) {
        dao.markAllSongsOutsideSourcePlaylist()
        dao.upsertSongs(state.mergedCandidates)
        // Foreign-key-safe order: dependent remote items first, then their playlist owner.
        dao.clearRemotePlaylistItems()
        dao.clearRemotePlaylists()
        dao.upsertRemotePlaylist(state.remotePlaylist)
        if (state.remoteItems.isNotEmpty()) dao.upsertRemotePlaylistItems(state.remoteItems)
        dao.deleteUnreferencedDiscoverySongs()
    }

    private fun parseEntry(entry: JSONObject): SongEntity? {
        val videoId = entry.optString("id")
            .takeIf(MediaIdentity::isYoutubeVideoId)
            ?: return null
        val sourceTitle = entry.ownerVisibleString("title")
        val unavailable = sourceTitle == null ||
            sourceTitle == "[Private video]" ||
            sourceTitle == "[Deleted video]"
        val title = sourceTitle.takeUnless { unavailable }
            ?: unavailablePlaylistItemTitle(videoId)
        val channel = sequenceOf(
            entry.ownerVisibleString("artist"),
            entry.ownerVisibleString("uploader"),
            entry.ownerVisibleString("channel")
        ).filterNotNull().firstOrNull() ?: "Unknown artist"
        val durationSeconds = entry.optDouble("duration", 0.0)
            .takeIf { it.isFinite() && it > 0.0 }
            ?: 0.0
        val artworkUrl = thumbnailUrl(entry)
        return SongEntity(
            id = stableSongId(videoId),
            youtubeVideoId = videoId,
            playlistItemId = (entry.opt("playlist_item_id") as? String)
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty() &&
                        it.length <= SelectedLibraryContract.MAXIMUM_METADATA_CHARACTERS
                },
            canonicalUrl = "https://music.youtube.com/watch?v=$videoId",
            sourceTitle = title,
            sourceArtist = channel,
            displayTitle = null,
            displayArtist = null,
            durationMs = (durationSeconds * 1000.0).toLong(),
            additionalInfoJson = JSONObject()
                .put(ARTWORK_SOURCE_URL_KEY, artworkUrl ?: JSONObject.NULL)
                .toString(),
            artworkPath = artworkUrl,
            currentlyInSourcePlaylist = true,
            highLevelState = if (unavailable) {
                SongState.NEEDS_ATTENTION.name
            } else {
                SongState.DISCOVERED.name
            },
            acquiredAtEpochMs = null,
            createdAtEpochMs = 0L
        )
    }

    private fun thumbnailUrl(entry: JSONObject): String? {
        val candidates = buildList {
            normalizedApprovedArtworkUrl(entry.optString("thumbnail"))
                ?.let { add(Triple(it, 0, 0)) }
            val thumbnails = entry.optJSONArray("thumbnails") ?: JSONArray()
            for (index in 0 until thumbnails.length()) {
                thumbnails.optJSONObject(index)?.let { thumbnail ->
                    normalizedApprovedArtworkUrl(thumbnail.optString("url"))
                        ?.let {
                            add(
                                Triple(
                                    it,
                                    thumbnail.optInt("width", 0),
                                    thumbnail.optInt("height", 0)
                                )
                            )
                        }
                }
            }
        }
        return candidates.maxByOrNull { (_, width, height) -> width.toLong() * height }
            ?.first
    }

    private fun toSong(snapshot: SongSnapshot): Song {
        val entity = snapshot.song
        val identity = ownerVisibleSongIdentity(
            videoId = entity.youtubeVideoId,
            displayTitle = entity.displayTitle,
            displayArtist = entity.displayArtist,
            sourceTitle = entity.sourceTitle,
            sourceArtist = entity.sourceArtist
        )
        val title = identity.title
        val artist = identity.artist
        val accent = SONG_ARTWORK_ACCENTS[
            (entity.youtubeVideoId.hashCode() and Int.MAX_VALUE) % SONG_ARTWORK_ACCENTS.size
        ]
        return Song(
            id = entity.id,
            videoId = entity.youtubeVideoId,
            title = title,
            artist = artist,
            durationMs = entity.durationMs,
            state = runCatching { SongState.valueOf(entity.highLevelState) }
                .getOrDefault(SongState.DISCOVERED),
            accent = accent,
            year = entity.displayYear.orEmpty(),
            album = entity.displayAlbum.orEmpty(),
            bpm = snapshot.primaryBpm?.toFloat(),
            key = snapshot.musicalKey,
            keyStatus = snapshot.keyStatus,
            keyReason = snapshot.keyReason,
            meter = snapshot.likelyMeter,
            hasStems = snapshot.hasStems,
            artworkGlyph = artworkGlyph(title),
            canonicalUrl = entity.canonicalUrl,
            sourceHash = snapshot.sourceHash,
            lyricTracks = emptyList(),
            additionalInfo = parseRoomAdditionalInfo(entity.additionalInfoJson),
            artworkSource = publishedArtworkSource(
                primarySource = entity.artworkPath,
                additionalInfoJson = entity.additionalInfoJson,
                youtubeVideoId = entity.youtubeVideoId
            ),
            sourcePath = snapshot.sourcePath,
            sourceCodec = snapshot.sourceCodec,
            sourceContainer = snapshot.sourceContainer,
            audioRevision = snapshot.sourceHash.orEmpty(),
            mediaRevision = entity.manifestFileSha256.orEmpty(),
            stemRevision = snapshot.stemRevision.orEmpty()
        )
    }

    private fun preparationScratchRoot(): File =
        File(context.noBackupFilesDir, PREPARATION_ACTIVE_DIRECTORY)

    /** Removes only app-owned, uncommitted bytes after a different Library commits. */
    suspend fun cleanupPreparationScratchForLibrarySwitch() = withContext(Dispatchers.IO) {
        songWorkCoordinator.preparationLane.withLock {
            cleanupPreparationScratchUnderPreparationLane()
        }
    }

    internal fun cleanupPreparationScratchUnderPreparationLane() {
        val noBackup = context.noBackupFilesDir.canonicalFile
        val scratch = preparationScratchRoot().canonicalFile
        check(scratch.parentFile == noBackup) { "PREPARATION_CLEANUP_REFUSED" }
        requireOwnedPreparationScratchTree(scratch)
        cleanupExactPrivateChildren(
            scratch,
            "PREPARATION_CLEANUP_FAILED"
        ) { child ->
            child.isFile && child.name in PRIVATE_PREPARATION_SCRATCH_NAMES
        }
        if (scratch.exists()) check(scratch.delete()) { "PREPARATION_CLEANUP_FAILED" }
    }

    private fun mergeAdditionalInfo(
        currentJson: String,
        added: Map<String, String>
    ): String {
        val merged = runCatching { JSONObject(currentJson) }.getOrDefault(JSONObject())
        added.forEach(merged::put)
        return merged.toString()
    }

    private fun stableSongId(videoId: String): String = videoId

    private fun friendlySyncError(error: Throwable): String {
        if (error.message == "PLAYLIST_RESPONSE_INCOMPLETE") return "The provider returned an incomplete playlist. The current selection was kept; try again."

        val raw = error.message.orEmpty()
        return when {
            raw.contains("Sign in", ignoreCase = true) ||
                raw.contains("private", ignoreCase = true) ->
                "YouTube requires access to this playlist or one of its songs"
            raw.contains("network", ignoreCase = true) ||
                raw.contains("Unable to download", ignoreCase = true) ->
                "Could not reach YouTube Music"
            raw.isNotBlank() -> raw.lineSequence().last().take(180)
            else -> "Playlist sync failed"
        }
    }

    companion object {
        private const val PLAYLIST_SYNC_PROCESS_ID = "stemwise-playlist-sync"
        private const val ARTWORK_SOURCE_URL_KEY = ROOM_ARTWORK_SOURCE_URL_KEY
        private const val PREPARATION_ACTIVE_DIRECTORY = "preparation-active"

    }
}
