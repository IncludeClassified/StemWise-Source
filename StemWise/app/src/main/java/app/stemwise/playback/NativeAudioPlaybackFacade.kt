package app.stemwise.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.stemwise.analysis.BeatEvent
import app.stemwise.analysis.MeterSegment
import app.stemwise.analysis.ImportedAnalysisRepository
import app.stemwise.analysis.effectiveGridBpm
import app.stemwise.analysis.metronomeAvailableFor
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.SONG_PLAYBACK_SETTINGS_SHA256_PATTERN
import app.stemwise.data.LastListeningSession
import app.stemwise.data.LastListeningSessionStore
import app.stemwise.data.RootAuthority
import app.stemwise.data.PlaylistRepository
import app.stemwise.data.StemAssetEntity
import app.stemwise.data.SongTrimRange
import app.stemwise.data.SongPlaybackSettings
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.data.SongLoopPreference
import app.stemwise.data.SongMetronomeSettings
import app.stemwise.integration.SeparationRepository
import app.stemwise.model.Song
import app.stemwise.model.StemRoleContract
import app.stemwise.playback.engine.EngineAudioIdentity
import app.stemwise.playback.engine.EngineLoopConfig
import app.stemwise.playback.engine.EngineMeterSegment
import app.stemwise.playback.engine.EngineMetronomeConfig
import app.stemwise.playback.engine.EngineSelectionToken
import app.stemwise.playback.engine.EngineSelection
import app.stemwise.playback.engine.EngineSourceMode
import app.stemwise.playback.engine.EngineStemControl
import app.stemwise.playback.engine.EngineStemLane
import app.stemwise.playback.engine.EngineStemMixPreviewLease
import app.stemwise.playback.engine.ENGINE_STEM_MIX_PREVIEW_LANE_COUNT
import app.stemwise.playback.engine.NativeAudioPlaybackControlSnapshot
import app.stemwise.playback.engine.NativeAudioPlaybackRequestedOwner
import app.stemwise.playback.engine.NativeAudioPlaybackSeekReceipt
import app.stemwise.playback.engine.NativeAudioPlaybackCommandLease
import app.stemwise.playback.engine.NativeAudioPlaybackSessionAdapter
import app.stemwise.playback.engine.NativeAudioPlaybackSessionEffect
import app.stemwise.playback.engine.NativeAudioPlaybackSessionEffectListener
import app.stemwise.playback.engine.NativeAudioPlaybackSessionLifecycle
import app.stemwise.playback.engine.NativeAudioPlaybackSessionState
import app.stemwise.playback.engine.NativeAudioPresentationJournalSnapshot
import app.stemwise.playback.engine.NativeEngineMetadata
import app.stemwise.playback.engine.NativeEngineDiagnosticsSnapshot
import app.stemwise.playback.engine.NativeEngineAutomaticEndPlan
import app.stemwise.playback.engine.NativeEngineQueueItem
import app.stemwise.playback.engine.NativeEngineOwnerPublicationKind
import app.stemwise.playback.engine.NativeEngineSelectionRequest
import app.stemwise.playback.engine.NativeEngineInitialControls
import app.stemwise.playback.engine.NativeRuntimeOwnership
import app.stemwise.playback.engine.NativeSourceWaveformProcessor
import app.stemwise.playback.engine.SourceWaveformIdentity
import app.stemwise.playback.engine.SourceWaveformWindow
import app.stemwise.playback.engine.SourceWaveformSource
import app.stemwise.playback.engine.NativeMediaSource
import app.stemwise.playback.engine.inspectNativeSourceFrameCount
import app.stemwise.playback.engine.readNativeSourcePcmWindow
import app.stemwise.playback.engine.NATIVE_ENGINE_SAMPLE_RATE_HZ
import app.stemwise.playback.engine.PlaybackCommittedPublication
import app.stemwise.playback.engine.PlaybackPublicationMetadata
import app.stemwise.playback.engine.PlaybackPublicationSourceState
import app.stemwise.playback.engine.PausedPreparedStemMixLease
import app.stemwise.playback.engine.PlaybackStemPackagePhase
import app.stemwise.playback.engine.audioIdentity
import app.stemwise.playback.engine.stemMixPackageRequestRevisionsMatch
import app.stemwise.playback.session.SessionArtworkOwner
import app.stemwise.playback.session.sessionArtworkKey
import app.stemwise.playback.session.SESSION_MEDIA_REVISION_KEY
import app.stemwise.artwork.ArtworkAccessPolicy
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class PendingOwnerlessRebind(
    val song: Song,
    val positionMs: Long,
    val autoplay: Boolean,
    val requestSerial: Long,
    val restartAtZero: Boolean = false,
    val preservePreviousPair: Boolean = false
)

private data class StemPackageCandidate(
    val songId: String,
    val stemRevision: String,
    val packageSignature: String,
    val setId: String,
    val assets: List<StemAssetEntity>,
    val lanes: List<EngineStemLane>
)

/** Requested playback reserves heavy-work admission before callback-backed PLAYING exists. */
private data class OptimisticSeek(
    val ownerToken: Long,
    val positionMs: Long,
    val callbackSequenceAtRequest: Long,
    val seekRevision: Long
)

internal sealed interface SeekAdmission {
    data class Position(val receipt: NativeAudioPlaybackSeekReceipt) : SeekAdmission
    data object TerminalNavigation : SeekAdmission
}

private data class PositionPublication(
    val ownerToken: Long,
    val songId: String,
    val positionMs: Long
)

private data class ScrubPreviewPublication(
    val sessionId: Long,
    val ownerToken: EngineSelectionToken,
    val songId: String,
    val positionMs: Long
)

private data class TrimReloadControls(
    val requestSerial: Long,
    val controls: NativeAudioPlaybackControlSnapshot,
    val stemRevision: String?
)

internal data class SongTrimPreviewLease(val songId: String, val requestSerial: Long)

private data class SongTrimPreview(
    val lease: SongTrimPreviewLease,
    val song: Song,
    val root: RootAuthority,
    val sourcePositionMs: Long,
    val stemRevision: String?
)

private data class PendingSettingsRestore(
    val token: EngineSelectionToken,
    val loop: SongLoopPreference,
    val stems: Boolean,
    val autoplay: Boolean
)

private data class SongSettingsOwner(val identity: SongPlaybackSettingsIdentity, val durationMs: Long)

internal fun stemMixPreviewPackageAuthorityMatches(
    installedSignature: String,
    installedRequestRevision: Long,
    desiredSignature: String,
    desiredRequestRevision: Long,
    authoritySignature: String,
    authorityRequestRevision: Long
): Boolean = installedSignature == authoritySignature &&
    desiredSignature == authoritySignature &&
    stemMixPackageRequestRevisionsMatch(
        installedRequestRevision,
        desiredRequestRevision,
        authorityRequestRevision
    )

private data class StemMixPreviewOwner(
    val controlLease: NativeAudioPlaybackCommandLease,
    val packageSignature: String,
    val packageRequestRevision: Long,
    val laneIds: Set<String>
) {
    val token: EngineSelectionToken get() = controlLease.token
    val songId: String get() = controlLease.songId
}

private data class StemMixPreviewPublication(
    val owner: StemMixPreviewOwner,
    val mix: Map<String, StemMixState>
)

/**
 * One immutable UI-command authority for one logical playback selection.
 *
 * The same song may be selected repeatedly, so song id alone is never sufficient. Callbacks keep
 * this lease from the composition/profile that created them; every adapter mutation validates the
 * exact native selection token atomically with the write.
 */
internal data class PlaybackControlLease(
    internal val native: NativeAudioPlaybackCommandLease,
    internal val pausedPreparedStemMix: PausedPreparedStemMixLease? = null
) {
    /** Stable only for this exact selection; changes even when the same song is reloaded. */
    val ownerEpoch: Long get() = native.token.value
    val songId: String get() = native.songId
    val audioRevision: String get() = native.audioRevision
}

private data class ScrubSnapshot(
    val sessionId: Long,
    val controlLease: PlaybackControlLease,
    val ownerOwnership: NativeRuntimeOwnership,
    val wasPlaying: Boolean,
    val wasEnded: Boolean,
    val positionMs: Long,
    val durationMs: Long
) {
    val ownerToken: EngineSelectionToken get() = controlLease.native.token
    val songId: String get() = controlLease.songId
}

/** Exact paused source preparation retained only across the Play-to-audible callback gap. */
internal class AudioOutputTopologyRecoveryGate(
    outputIdsBeforeNativeRuntimeCreation: Set<Int>?
) {
    private var lastUsableOutputIds = outputIdsBeforeNativeRuntimeCreation
        ?.takeIf(Set<Int>::isNotEmpty)

    fun shouldRecover(observedOutputIds: Set<Int>?): Boolean {
        val usableOutputIds = observedOutputIds?.takeIf(Set<Int>::isNotEmpty) ?: return true
        val topologyChanged = usableOutputIds != lastUsableOutputIds
        lastUsableOutputIds = usableOutputIds
        return topologyChanged
    }
}

/**
 * App/session controller around StemWise's one native playback runtime.
 *
 * Media3 remains only the stable Android notification/Bluetooth projection. It never decodes,
 * renders, clocks, mixes, seeks, or owns a queue. Visible and session identity is copied solely
 * from the adapter's committed native publication; a newer tap is represented only as loading
 * until the exact native bank/generation proves ownership.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPlayback(
    context: Context,
    private val repository: PlaylistRepository,
    private val separationRepository: SeparationRepository,
    private val roots: LibraryRootManager,
    private val analysisRepository: ImportedAnalysisRepository = ImportedAnalysisRepository(context.applicationContext, roots)
) {
    internal val instrumentationRecorder = PlaybackInstrumentationRecorder()
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val platform by lazy {
        PlaybackPlatformLifecycle(applicationContext, mainHandler,
            onFocusChange = { handleAudioFocusChange(it) },
            onRouteRecovery = { if (!playbackCommandsBlocked()) adapter.requestRouteRecovery() })
    }
    /** Exactly one queue authority for UI, session, shuffle, repeat, and audible-end policy. */
    private val queueController = PlaybackQueueController()

    /** Exactly one production adapter, which itself owns the one controller/JNI runtime. */
    internal val adapter: NativeAudioPlaybackSessionAdapter
    @Volatile internal var testAdapter: PlaybackTestAdapter? = null

    private val mutableState = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = mutableState.asStateFlow()
    private var selectionArtworkTargetListener: ((Song?) -> Unit)? = null
    /** Low-frequency exact command authority, independent from the 120 Hz visual clock. */
    private val mutableControlLease = MutableStateFlow<PlaybackControlLease?>(null)
    internal val controlLease: StateFlow<PlaybackControlLease?> =
        mutableControlLease.asStateFlow()
    /** Immediate seed only; callers must observe [state] for every subsequent change. */
    internal fun currentStateSnapshot(): AudioPlaybackState = mutableState.value

    private val sourceWaveformProcessor = NativeSourceWaveformProcessor()
    private val sourceWaveformMutex = Mutex()
    private var sourceWaveformHeader: Pair<SourceWaveformSource, Long>? = null
    private var sourceWaveformFailure: Pair<SourceWaveformIdentity, Long>? = null
    private val preferencesPersistence by lazy {
        PlaybackPreferencesPersistence(applicationContext.filesDir, scope,
            settingsFailure = { failure ->
                if (!released) {
                    Log.w(TAG, "Could not save song settings", failure)
                    publishState(mutableState.value.copy(notice = PlaybackNotice("Settings not saved",
                        "Playback changed, but these settings could not be saved. Check available storage and try again.",
                        PlaybackNoticeCategory.SONG_SELECTION)))
                }
            }, listeningFailure = { error ->
                Log.w(TAG, "Could not save listening session", error)
                mainHandler.post {
                    if (!released) publishState(mutableState.value.copy(notice = PlaybackNotice("Listening position not saved",
                        "Check available storage and try again.", PlaybackNoticeCategory.SONG_SELECTION)))
                }
            })
    }
    private var listeningRestoreAllowed = true
    private var removedTaskRestoreSerial: Long? = null
    private var removedTaskAvailable = false
    private var listeningRestoreJob: Job? = null
    private var listeningStemRestoreJob: Job? = null
    private var restoringListeningSession: Pair<Long, LastListeningSession>? = null
    private var lastListeningCaptureAtMs = Long.MIN_VALUE
    private var listeningRootAuthority: RootAuthority? = null
    private val savedMetronomes = java.util.concurrent.ConcurrentHashMap<Pair<SongPlaybackSettingsIdentity, String>, EngineMetronomeConfig>()
    internal val songSettingsRevision: StateFlow<Long> get() = preferencesPersistence.settingsRevision
    private var settingsPreloadJob: Job? = null
    private var presentationPreloadJob: Job? = null
    private var settingsPreloadRoot: RootAuthority? = null
    private var settingsPreloadTargets = emptyList<Triple<SongPlaybackSettingsIdentity, Long, String>>()
    private var pendingSettingsRestore: PendingSettingsRestore? = null
    private var trimReloadControls: TrimReloadControls? = null
    private var songTrimPreview: SongTrimPreview? = null
    private val trimMutationMutex = Mutex()

    private fun cancelListeningRestore() {
        removedTaskRestoreSerial = null
        removedTaskAvailable = false
        listeningRestoreAllowed = false
        listeningRestoreJob?.cancel()
        listeningRestoreJob = null
        listeningStemRestoreJob?.cancel()
        listeningStemRestoreJob = null
        restoringListeningSession = null
    }

    private fun restoreListeningSessionIfReady(snapshotReady: Boolean) {
        if (!snapshotReady || !listeningRestoreAllowed || listeningRestoreJob != null || queueById.isEmpty()) return
        val root = roots.captureAuthority() ?: return
        val serial = requestSerial
        val restore = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val saved = withContext(Dispatchers.IO) { preferencesPersistence.listeningStore.read() }
                if (!listeningRestoreAllowed || serial != requestSerial || playbackCommandsBlocked()) return@launch
                roots.requireSameLibrary(root)
                if (saved == null || saved.identity.libraryId != root.libraryId ||
                    saved.identity.treeUri != root.treeUri) {
                    listeningRestoreAllowed = false
                    return@launch
                }
                val song = queueById[saved.identity.songId] ?: return@launch
                val currentIdentity = settingsIdentity(song) ?: return@launch
                if (currentIdentity != saved.identity) {
                    listeningRestoreAllowed = false
                    return@launch
                }
                queueController.restore(saved.queue)
                queueController.updateLibrary(libraryOrder)
                publishQueueState()
                if (loadInternal(song, autoplay = false, positionMs = 0L, forceReload = true, restoredSession = saved)) {
                    listeningRestoreAllowed = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                listeningRestoreAllowed = false
                Log.w(TAG, "Could not restore listening session", error)
                publishState(mutableState.value.copy(notice = PlaybackNotice("Listening session could not open",
                    "Your saved session could not be read. Select a song to continue.", PlaybackNoticeCategory.SONG_SELECTION)))
            } finally {
                if (listeningRestoreJob === currentCoroutineContext()[Job]) listeningRestoreJob = null
            }
        }
        // Register before starting; a fast store read can finish without suspending.
        listeningRestoreJob = restore
        restore.start()
    }

    /** Captures the committed owner only; pending selection and pointer previews cannot become history. */
    private fun saveListeningSession(immediate: Boolean = false) {
        if (released || hideCommittedPublication || resolvingRequestSerial != null ||
            restoringListeningSession != null || scrubSnapshot != null || songTrimPreview != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!immediate && lastListeningCaptureAtMs != Long.MIN_VALUE && now - lastListeningCaptureAtMs < 1_000L) return
        val snapshot = adapter.snapshot()
        val committed = snapshot.publication.committed ?: return
        if (listeningRootAuthority == null || roots.captureAuthority() != listeningRootAuthority) return
        if (snapshot.latestRequest?.selection?.token != committed.selection.token) return
        val song = metadataPublications.payloadFor(committed.metadata)?.song ?: return
        val identity = settingsIdentity(song)?.takeIf {
            it.originalSha256.equals(committed.selection.originalContentSha256, ignoreCase = true)
        } ?: return
        val queue = queueController.snapshot().takeIf { it.currentId == song.id } ?: return
        if (mutableState.value.selectionToken != committed.selection.token.value || mutableState.value.songId != song.id) return
        val saved = LastListeningSession(identity, queue,
            committed.selection.sourcePositionMs(projectedPosition(committed)),
            mutableState.value.sourceMode)
        lastListeningCaptureAtMs = now
        preferencesPersistence.saveListeningSession(saved)
    }

    private fun settingsIdentity(song: Song): SongPlaybackSettingsIdentity? {
        val root = roots.state.value
        if (!root.ready) return null
        val libraryId = root.libraryId ?: return null
        val treeUri = root.treeUri?.toString() ?: return null
        val hash = song.sourceHash?.lowercase()?.takeIf(SONG_PLAYBACK_SETTINGS_SHA256_PATTERN::matches) ?: return null
        return SongPlaybackSettingsIdentity(libraryId, treeUri, song.id, hash)
    }

    private fun cachedSettings(identity: SongPlaybackSettingsIdentity): Result<SongPlaybackSettings>? =
        preferencesPersistence.cachedSettings(identity)

    /** Null is unread/unavailable; a successfully read absent row is an explicit default. */
    internal fun cachedSongPlaybackSettings(song: Song): SongPlaybackSettings? =
        settingsIdentity(song)?.let { cachedSettings(it)?.getOrNull() }?.takeIf {
            runCatching { it.validateDuration(song.durationMs) }.isSuccess
        }

    internal fun currentSongPlaybackSettingsAreAuthoritative(song: Song): Boolean {
        if (playbackCommandsBlocked() || hideCommittedPublication || state.value.songId != song.id) return false
        if (pendingSettingsRestore?.loop?.pointA != null) return false
        val committed = lastAdapterState.publication.committed ?: return false
        return state.value.selectionToken == committed.selection.token.value &&
            adapter.snapshot().latestRequest?.selection?.token == committed.selection.token &&
            committed.selection.songId == song.id && committed.selection.originalContentSha256 == song.sourceHash &&
            committed.selection.audioRevision == effectiveAudioRevision(song, song.sourcePath.orEmpty()) &&
            settingsIdentity(song) != null
    }

    private suspend fun prepareSongSettings(song: Song, retryFailure: Boolean = false): SongPlaybackSettings {
        val identity = settingsIdentity(song) ?: return SongPlaybackSettings()
        val settings = readSongSettings(song, identity, retryFailure)
        prepareSavedMetronome(song, identity, settings)
        return settings
    }

    private suspend fun readSongSettings(song: Song, identity: SongPlaybackSettingsIdentity,
        retryFailure: Boolean = false): SongPlaybackSettings =
        preferencesPersistence.readSettings(identity, song.durationMs, retryFailure) {
            check(settingsIdentity(song) == identity) { "LIBRARY_CHANGED" }
        }

    private fun cancelSettingsPreload() {
        settingsPreloadJob?.cancel()
        settingsPreloadJob = null
        settingsPreloadTargets = emptyList()
        settingsPreloadRoot = null
        presentationPreloadJob?.cancel()
        preferencesPersistence.savedSettings.clear()
        savedMetronomes.clear()
    }

    /** The full permutation shares one small settings cache, including rapid shuffle skips. */
    private fun preloadQueueSettings(songs: List<Song>) {
        val root = roots.captureAuthority()
        val targets = songs.mapNotNull { song -> settingsIdentity(song)?.let { Triple(it, song.durationMs, song.mediaRevision) } }
        if (targets == settingsPreloadTargets && root == settingsPreloadRoot) return
        settingsPreloadRoot = root
        settingsPreloadTargets = targets
        val liveSettings = targets.mapTo(hashSetOf()) { it.first }
        preferencesPersistence.savedSettings.keys.retainAll(liveSettings)
        val liveRhythms = targets.mapTo(hashSetOf()) { it.first to it.third }
        savedMetronomes.keys.retainAll(liveRhythms)
        settingsPreloadJob?.cancel()
        settingsPreloadJob = scope.launch {
            // A provider-backed rhythm read must not hold up another song's small settings row.
            withContext(Dispatchers.IO) {
                for (song in songs) {
                    val identity = settingsIdentity(song) ?: continue
                    try { readSongSettings(song, identity) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Log.w(TAG, "Could not read saved song settings", error) }
                }
            }
            updateLikelyNext()
        }
        preloadQueuePresentations(songs, checkNotNull(settingsPreloadJob))
    }

    private fun preloadQueuePresentations(songs: List<Song>, settingsReady: Job) {
        val current = metadataPublications.payloadFor(lastAdapterState.publication.committed?.metadata)?.song
        // Compact display projections belong to the full queue, independently of native prewarming.
        analysisRepository.retainQueuePresentations(listOfNotNull(current) + songs)
        val identities = songs.mapNotNull { song -> settingsIdentity(song)?.let { it to song.mediaRevision } }.toSet()
        val currentIdentity = current?.let { song -> settingsIdentity(song)?.let { it to song.mediaRevision } }
        savedMetronomes.keys.retainAll(identities + listOfNotNull(currentIdentity))
        presentationPreloadJob?.cancel()
        presentationPreloadJob = scope.launch(Dispatchers.IO) {
            // Finish every small settings row before any provider-backed presentation/rhythm read.
            settingsReady.join()
            for (song in songs) {
                try {
                    analysisRepository.presentation(song.id, song.mediaRevision, song.sourceHash.orEmpty())
                    val identity = settingsIdentity(song) ?: continue
                    val settings = cachedSettings(identity)?.getOrNull() ?: continue
                    prepareSavedMetronome(song, identity, settings)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Log.w(TAG, "Could not prepare queued song presentation", error) }
            }
        }
    }

    internal suspend fun savedSongTrim(song: Song): SongTrimRange? {
        val identity = settingsIdentity(song) ?: return null
        return preferencesPersistence.readTrim(identity, song.durationMs)
    }

    internal fun songSettingsNeedReset(song: Song): Boolean {
        var error = settingsIdentity(song)?.let { preferencesPersistence.savedSettings[it]?.exceptionOrNull() }
        while (error != null) {
            if (error is app.stemwise.data.AtomicJournalFormatException) return true
            error = error.cause
        }
        return false
    }

    internal suspend fun resetMalformedSongSettings(song: Song): Result<Unit> = try {
        val identity = checkNotNull(settingsIdentity(song)) { "Library unavailable" }
        preferencesPersistence.resetMalformed(identity, song.durationMs)
        withContext(Dispatchers.Main.immediate) {
            savedMetronomes.keys.removeAll { it.first == identity }
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

    private fun settingsOwner(lease: NativeAudioPlaybackCommandLease,
        requested: NativeAudioPlaybackRequestedOwner? = adapter.snapshot().latestRequest): SongSettingsOwner? {
        val request = requested?.takeIf { it.selection.token == lease.token } ?: return null
        val song = metadataPublications.payloadFor(request.metadata)?.song ?: return null
        val identity = settingsIdentity(song)?.takeIf {
            it.originalSha256.equals(request.selection.originalContentSha256, ignoreCase = true)
        } ?: return null
        return SongSettingsOwner(identity, request.selection.durationMs)
    }

    /** An accepted edit belongs to its captured song even if native advances before disk I/O. */
    private fun persistSettings(owner: SongSettingsOwner?, delta: (SongPlaybackSettings) -> SongPlaybackSettings) {
        if (owner == null) return
        saveListeningSession(immediate = true)
        testAdapter?.beforeSongSettingsWrite()
        val identity = owner.identity
        val expected = delta(checkNotNull(cachedSettings(identity)).getOrThrow())
        preferencesPersistence.persistSettings(identity, owner.durationMs, expected)
    }

    internal fun beginSongTrimPreview(song: Song, controlLease: PlaybackControlLease): SongTrimPreviewLease? {
        if (playbackCommandsBlocked() || resolvingRequestSerial != null ||
            !adapter.commandLeaseIsCurrent(controlLease.native)) return null
        val session = adapter.snapshot()
        val owner = session.publication.committed?.takeIf { it.selection.token == controlLease.native.token &&
            it.selection.songId == song.id && it.selection.originalContentSha256.equals(song.sourceHash, true) } ?: return null
        val controls = session.latestRequest?.controls ?: return null
        val root = roots.captureAuthority() ?: return null
        saveListeningSession(immediate = true)
        requestSerial = incrementRevision(requestSerial, "trim preview")
        val lease = SongTrimPreviewLease(song.id, requestSerial)
        val preview = SongTrimPreview(lease, song, root,
            owner.selection.sourcePositionMs(projectedPosition(owner)), owner.stems.desired?.stemRevision)
        songTrimPreview = preview
        trimReloadControls = TrimReloadControls(requestSerial, controls, preview.stemRevision)
        automaticPrewarm.invalidate()
        if (!selectResolvedSong(song, owner.selection.originalLocation, controls.playWhenReady,
                preview.sourcePositionMs, forceReload = true, serial = requestSerial)) {
            songTrimPreview = null
            return null
        }
        return lease
    }

    internal fun songTrimPreviewControlLease(lease: SongTrimPreviewLease): PlaybackControlLease? =
        acquireControlLease(lease.songId)?.takeIf {
            songTrimPreview?.lease == lease && requestSerial == lease.requestSerial &&
                roots.captureAuthority() == songTrimPreview?.root
        }

    internal fun songTrimPreviewIsReady(lease: SongTrimPreviewLease): Boolean {
        val control = songTrimPreviewControlLease(lease) ?: return false
        return adapter.snapshot().publication.committed?.selection?.token == control.native.token &&
            pendingSettingsRestore?.token != control.native.token
    }

    private fun trimPreviewOwns(selection: EngineSelection): Boolean = songTrimPreview?.let {
        it.lease.requestSerial == requestSerial && it.song.id == selection.songId &&
            roots.captureAuthority() == it.root
    } == true

    internal fun previewSongTrimBoundary(lease: SongTrimPreviewLease, positionMs: Long): Boolean {
        return songTrimPreviewControlLease(lease)?.let { control ->
            // The editor owns the complete source; B auditions stay before exclusive EOS.
            val duration = adapter.snapshot().latestRequest?.selection?.durationMs ?: return false
            seekToWithReceipt(positionMs.coerceIn(0L, duration - 1L), control) != null
        } == true
    }

    internal fun cancelSongTrimPreview(lease: SongTrimPreviewLease) {
        val preview = songTrimPreview?.takeIf { it.lease == lease } ?: return
        val control = songTrimPreviewControlLease(lease)
        if (control == null) {
            songTrimPreview = null
            return
        }
        val current = adapter.snapshot()
        val controls = current.latestRequest?.controls ?: return
        val autoplay = currentResolvingTransportIntent() ?: controls.playWhenReady
        val stemRevision = current.publication.committed?.takeIf { it.selection.token == control.native.token }
            ?.stems?.desired?.stemRevision ?: preview.stemRevision
        val trim = settingsIdentity(preview.song)?.let(::cachedSettings)?.getOrNull()?.trim
        val start = trim?.startMs ?: 0L
        val end = trim?.endMs ?: preview.song.durationMs
        val position = (preview.sourcePositionMs - start).coerceIn(0L, end - start - 1L)
        if (load(preview.song, autoplay, position, forceReload = true)) {
            trimReloadControls = TrimReloadControls(requestSerial, controls, stemRevision)
        }
    }

    internal suspend fun setSongTrim(
        song: Song,
        range: SongTrimRange?,
        controlLease: PlaybackControlLease?
    ): Result<Unit> = trimMutationMutex.withLock { try {
        withContext(Dispatchers.Main.immediate) {
            val lease = requireNotNull(controlLease) { "This song is still opening. Try again when it is ready." }
            check(adapter.commandLeaseIsCurrent(lease.native) && lease.songId == song.id) { "The selected song changed." }
            val session = adapter.snapshot()
            val owner = checkNotNull(session.publication.committed).takeIf { it.selection.token == lease.native.token }
                ?: error("This song is still opening.")
            check(owner.selection.originalContentSha256.equals(song.sourceHash, ignoreCase = true)) { "The song audio changed." }
            val root = checkNotNull(roots.captureAuthority()) { "Library unavailable" }
            val identity = checkNotNull(settingsIdentity(song))
            try {
                preferencesPersistence.writeTrim(identity, song.durationMs, range,
                    requireCurrent = { roots.requireSameLibrary(root) }, onCommitted = {
                        if (!released && roots.captureAuthority() == root) {
                            automaticPrewarm.invalidateForSong(song.id)
                            updateLikelyNext()
                        }
                    })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw java.io.IOException("Could not confirm your trim. Check storage access and available space, then retry.", error)
            }
            roots.requireSameLibrary(root)
            check(adapter.commandLeaseIsCurrent(lease.native)) { "Trim saved. The selected song changed." }
            val currentSession = adapter.snapshot()
            val currentOwner = checkNotNull(currentSession.publication.committed)
            val previousSourcePosition = currentOwner.selection.sourcePositionMs(projectedPosition(currentOwner))
            val nextStart = range?.startMs ?: 0L
            val nextEnd = range?.endMs ?: song.durationMs
            val nextPosition = (previousSourcePosition - nextStart).coerceIn(0L, (nextEnd - nextStart - 1L).coerceAtLeast(0L))
            val controls = checkNotNull(currentSession.latestRequest).controls
            check(load(song, controls.playWhenReady, nextPosition, forceReload = true)) { "Trim saved. Reopen this song to apply it." }
            trimReloadControls = TrimReloadControls(requestSerial, controls, currentOwner.stems.desired?.stemRevision)
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Could not apply song trim", error)
        Result.failure(error)
    } }

    /** Fine-gesture scoped observation; callers poll while visible, never from a display frame. */
    internal fun sourceWaveformIdentity(): SourceWaveformIdentity? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (playbackCommandsBlocked() || frameBoundedStemMixPreviewLeases.any { it != null }) return null
        val session = adapter.snapshot()
        val command = adapter.acquireCommandLease() ?: return null
        val prepared = adapter.capturePausedPreparedStemMixLease(command)
        val native = adapter.sourceWaveformDiagnosticsSnapshot() ?: return null
        return sourceWaveformIdentityCandidate(session, native, prepared)
    }

    internal suspend fun readSourceWaveformWindow(
        expectedIdentity: SourceWaveformIdentity,
        centerMs: Long,
        radiusMs: Long = 5_000L
    ): SourceWaveformWindow? {
        val selection = withContext(Dispatchers.Main.immediate) {
            adapter.snapshot().publication.committed?.selection?.takeIf { it.songId == expectedIdentity.songId }
        } ?: return null
        val window = readSourceWaveformWindowAbsolute(expectedIdentity, selection.sourcePositionMs(centerMs), radiusMs)
            ?: return null
        val first = ((maxOf(window.startMs, selection.playbackStartMs) - window.startMs +
            window.frameDurationMs - 1L) / window.frameDurationMs).toInt()
        val last = ((minOf(window.endMs, selection.playbackEndMs) - window.startMs) /
            window.frameDurationMs).toInt().coerceAtMost(window.peaks.size)
        if (first >= last) return null
        val start = window.startMs + first * window.frameDurationMs
        val end = window.startMs + last * window.frameDurationMs
        return window.copy(startMs = start - selection.playbackStartMs, endMs = end - selection.playbackStartMs,
            peaks = window.peaks.subList(first, last))
    }

    internal suspend fun readSourceWaveformWindowAbsolute(
        expectedIdentity: SourceWaveformIdentity,
        centerMs: Long,
        radiusMs: Long = 5_000L
    ): SourceWaveformWindow? {
        if (withContext(Dispatchers.Main.immediate) { sourceWaveformIdentity() } != expectedIdentity) return null
        val result = withContext(Dispatchers.IO) {
            sourceWaveformMutex.withLock {
                val failed = sourceWaveformFailure
                if (failed != null && failed.first == expectedIdentity && android.os.SystemClock.elapsedRealtime() < failed.second) {
                    return@withLock null
                }
                try {
                    val remaining = expectedIdentity.sources.single { it.directFloat32Audio }
                    val frames = sourceWaveformHeader?.takeIf { it.first == remaining }?.second
                        ?: inspectNativeSourceFrameCount(applicationContext,
                            NativeMediaSource.fromLocation(remaining.location, true, remaining.contentIdentity) as NativeMediaSource.Float32Audio)
                            .also { sourceWaveformHeader = remaining to it }
                    sourceWaveformProcessor.window(expectedIdentity, centerMs, radiusMs, frameCount = frames) { source, first, count ->
                        readNativeSourcePcmWindow(applicationContext,
                            NativeMediaSource.fromLocation(source.location, source.directFloat32Audio, source.contentIdentity),
                            first, count, packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory,
                            readCached = { adapter.readSourcePcmWindow(expectedIdentity, source, first, count) })
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val unavailable = error is java.io.IOException || error is SecurityException ||
                        error is IllegalStateException && (error.message == "SOURCE_AUDIO_FORMAT_INVALID" ||
                            error.message?.startsWith("SOURCE_WINDOW_") == true)
                    if (!unavailable) throw error
                    Log.w(TAG, "Source waveform unavailable", error)
                    sourceWaveformFailure = expectedIdentity to (android.os.SystemClock.elapsedRealtime() + 2_000L)
                    null
                }
            }
        } ?: return null
        return if (withContext(Dispatchers.Main.immediate) { sourceWaveformIdentity() } == expectedIdentity) result else null
    }

    /** UI-only QoS signal; playback and artwork ownership remain with their existing controllers. */
    internal fun setSelectionArtworkTargetListener(listener: ((Song?) -> Unit)?) {
        selectionArtworkTargetListener = listener
    }

    private fun restoreSelectionArtworkTarget() {
        selectionArtworkTargetListener?.invoke(null)
    }

    /** Resolved queue-selection cursor shared by player navigation and the committed audio owner. */
    internal fun currentQueueSongId(): String? = queueController.snapshot().currentId

    /**
     * Captures the exact latest selection once for a visible song profile.
     *
     * UI callbacks must retain this lease instead of reacquiring by song id when invoked; doing so
     * makes a delayed callback from an older rendering harmless even after the same song reloads.
     */
    internal fun acquireControlLease(expectedSongId: String): PlaybackControlLease? =
        if (playbackCommandsBlocked()) null
        else adapter.acquireCommandLease(expectedSongId)?.let {
            PlaybackControlLease(it, adapter.capturePausedPreparedStemMixLease(it))
        }

    /** MediaSession/internal commands acquire the same lease at their command boundary. */
    private fun acquireCurrentControlLease(): PlaybackControlLease? =
        if (playbackCommandsBlocked()) null
        else adapter.acquireCommandLease()?.let {
            PlaybackControlLease(it, adapter.capturePausedPreparedStemMixLease(it))
        }

    /** Always re-reads adapter authority so a delayed state callback cannot republish an old lease. */
    private fun publishCurrentControlLease() {
        mutableControlLease.value = acquireCurrentControlLease()
    }

    val sessionState: StateFlow<AudioSessionState> = state
        .map { value ->
            AudioSessionState(
                songId = value.songId,
                loading = value.loading,
                playing = value.playing,
                playWhenReady = value.playWhenReady,
                ended = value.ended,
                error = value.error,
                repeatMode = value.repeatMode,
                shuffleEnabled = value.shuffleEnabled,
                canGoPrevious = value.canGoPrevious,
                canGoNext = value.canGoNext
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, AudioSessionState())

    private var libraryOrder: List<String> = emptyList()
    /** Main-owned mutable index: explicit selection updates are O(1), never whole-map copies. */
    private val queueById = LinkedHashMap<String, Song>()
    private val automaticPrewarm by lazy {
        AutomaticQueuePrewarmOwner(scope, object : AutomaticQueuePrewarmSink {
            override suspend fun verify(identity: EngineAudioIdentity): NativeEngineQueueItem? =
                queueById[identity.songId]?.let { verifiedQueueItem(it, allowDormantControls = true) }
            override fun stillCurrent(request: AutomaticQueuePrewarmRequest): Boolean =
                !playbackCommandsBlocked() && request.selectionSerial == requestSerial &&
                    adapter.snapshot().latestRequest?.selection?.token == request.owner &&
                    roots.captureAuthority() == request.root &&
                    request.audio.all { (id, identity) -> queueById[id]?.let { queuedAudioIdentity(it, allowDormantControls = true) } == identity } &&
                    request.automaticSongIds == request.audio.mapNotNull { (id, _) ->
                        queueById[id]?.takeIf { queuedAudioIdentity(it) != null }?.id
                    }.toSet()
            override fun refreshMetadata() {
                adapter.refreshAutomaticEndMetadata { identity ->
                    queueById[identity.songId]?.let { nativeQueueItem(it) }
                        ?.takeIf { it.identity == identity }?.metadata
                }
            }
            override fun refreshed(item: NativeEngineQueueItem): NativeEngineQueueItem =
                queueById[item.identity.songId]?.let { nativeQueueItem(it, allowDormantControls = true) }
                    ?.takeIf { it.identity == item.identity } ?: item
            override fun publish(plan: NativeEngineAutomaticEndPlan?) { adapter.setAutomaticEndPlan(plan) }
            override fun predict(item: NativeEngineQueueItem?) {
                // Prediction may warm a decoder, but only publish() authorizes automatic audio.
                if (manualPrewarmSongId == null) adapter.setManualPrewarm(item?.takeIf { candidate ->
                    queueById[candidate.identity.songId]?.let { queuedAudioIdentity(it) == null } == true
                })
                if (item == null) sessionArtwork.cancelPrefetch()
                else queueById[item.identity.songId]?.let(::scheduleLikelyNextArtwork)
            }
        })
    }
    private val partsDeletion by lazy {
        PartsDeletionPlaybackOwner(object : PartsDeletionPlaybackSink {
            override fun currentRoot() = roots.captureAuthority()
            override fun selectionSerial() = requestSerial
            override fun quiesce(root: RootAuthority, songId: String): PartsPlaybackRestoreIntent? {
                val snapshot = adapter.snapshot()
                val latest = snapshot.latestRequest
                val pendingManualSong = resolvingSongId.takeIf { resolvingRequestSerial == requestSerial }
                    ?: pendingOwnerlessRebind?.takeIf { it.requestSerial == requestSerial }?.song?.id
                val preserveNewerSelection = pendingManualSong != null && pendingManualSong != songId
                val outgoingOwner = latest?.takeIf { it.selection.songId == songId }
                val restoring = outgoingOwner.takeUnless { preserveNewerSelection }
                val song = metadataPublications.payloadFor(restoring?.metadata)?.song
                    ?: queueById[songId]?.takeIf { resolvingSongId == songId }
                // Preserve an admitted seek until its exact acknowledgement, as transport projection does.
                val position = snapshot.publication.committed?.takeIf {
                    it.selection.token == restoring?.selection?.token
                }?.let(::projectedPosition) ?: restoring?.let {
                    it.selection.publicPositionMs(it.controls.requestedPositionMs)
                } ?: 0L
                val autoplay = if (resolvingSongId == songId) currentResolvingTransportIntent() ?: false
                    else restoring?.controls?.playWhenReady ?: false
                // Revoke dormant decoder plans before asking the actor for its retirement fence.
                automaticPrewarm.invalidateForSong(songId)
                if (manualPrewarmSongId == songId) previewSelection(null)
                if (outgoingOwner != null) unloadNativeOwner(songId, preserveNewerSelection)
                else cancelUnpublishedSelection(songId)
                stemCandidates.entries.removeAll { it.value.songId == songId }
                return song?.let { PartsPlaybackRestoreIntent(root, it, requestSerial, position, autoplay) }
            }
            override fun retirementTokens(songId: String) = adapter.selectionRetirementTokens(songId)
            override fun quiesceLateSelection(root: RootAuthority, songId: String): PartsPlaybackRestoreIntent? {
                val current = adapter.snapshot()
                if (current.lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE ||
                    current.latestRequest?.selection?.songId != songId
                ) return null
                return quiesce(root, songId)
            }
            override fun retired(token: EngineSelectionToken) = adapter.isSelectionRetired(token)
            override fun restoreOriginal(intent: PartsPlaybackRestoreIntent) {
                if (!playbackCommandsBlocked()) {
                    load(intent.song, intent.playWhenReady, intent.positionMs, forceReload = true)
                }
            }
            override fun finished(songId: String) {
                automaticPrewarm.invalidateForSong(songId)
                updateLikelyNext()
            }
        })
    }
    private fun partsDeletionBlocks(songId: String): Boolean =
        partsDeletion.blocks(roots.captureAuthority(), songId)

    /** The provider invokes admission before mutating parts; Original restoration is intent-fenced. */
    suspend fun <T> deletePartsPreservingPlayback(
        root: RootAuthority,
        songId: String,
        operation: suspend (suspend () -> Unit) -> Result<T>
    ): Result<T> = partsDeletion.delete(root, songId, operation = operation)

    /** Permanent deletion shares exact consumer retirement, but never restores deleted audio. */
    suspend fun <T> withPermanentSongDeletion(
        root: RootAuthority,
        songId: String,
        operation: suspend (onExactRootAdmitted: suspend () -> Unit) -> Result<T>
    ): Result<T> = partsDeletion.delete(root, songId, restoreAfterDeletion = false) { admitted ->
        operation(admitted).also { result ->
            if (result.isSuccess) withContext(Dispatchers.Main.immediate) {
                if (roots.captureAuthority() == root) {
                    setPlaybackQueue(libraryOrder.mapNotNull(queueById::get).filterNot { it.id == songId },
                        snapshotReady = true)
                }
            }
        }
    }

    private var manualPrewarmJob: Job? = null
    private var manualPrewarmSerial = 0L
    private var manualPrewarmSongId: String? = null
    private var requestSerial = 0L
    /** Closed across a root switch so retained UI/MediaSession commands cannot revive old URIs. */
    private var librarySessionCommandsAllowed = true
    private var pendingOwnerlessRebind: PendingOwnerlessRebind? = null
    private val selectionResolution = PlaybackSelectionResolutionOwner()
    private val resolvingRequestSerial get() = selectionResolution.current?.serial
    private val resolvingSongId get() = selectionResolution.current?.songId
    private var manualNavigationSongId: String? = null
    private var outputFailureNotice: Pair<EngineSelectionToken, PlaybackNotice>? = null
    private var hideCommittedPublication = false
    private var released = false
    /**
     * A true native quarantine cannot safely construct a second engine beside retained codec or
     * output ownership. Keep the process fail-silent until an explicit Recents removal retires it.
     */
    @Volatile private var nativeProcessRestartRequired = false
    private var lastAdapterState = NativeAudioPlaybackSessionState()
    private var lastCommittedToken: Long? = null
    private var lastCommittedMetadataRevision = 0L
    private var endedOwnerToken: Long? = null
    /** Queue/repeat view of the native fixed-loop dormancy contract, fenced by selection token. */
    /** Last accepted latest-owner intent survives a selection failure long enough to skip it. */
    private var latestTransportIntent: Boolean? = null
    private var optimisticSeek: OptimisticSeek? = null
    private var preparedSourceResumeProof: PreparedSourceResumeProof? = null
    private var pendingTerminalSeek: TokenBoundTerminalSeek? = null
    private var scrubSnapshot: ScrubSnapshot? = null
    private var suppressedScrubEnd: Pair<EngineSelectionToken, NativeRuntimeOwnership>? = null
    private var scrubSessionSerial = 0L
    private var lastScrubPreviewDispatchAtMs: Long? = null
    /** Exact native leases captured at DOWN and fenced at terminal input or owner change. */
    private val frameBoundedStemMixPreviewLeases =
        arrayOfNulls<EngineStemMixPreviewLease>(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT)
    private var lastHandledAdapterStructuralRevision = -1L

    private val metadataPublications = PlaybackMetadataPublicationOwner()
    private val stemCandidates = linkedMapOf<String, StemPackageCandidate>()
    private val sessionMediaCache = SessionMediaItemCache<MediaItem>()
    private val sessionArtwork = SessionArtworkOwner(applicationContext, scope)

    private val positionPublications = FrameConflatedPublication<PositionPublication>()
    /**
     * The native clock is sampled independently from the display. Publish its newest value at a
     * display frame boundary so a 16 ms engine poll cannot inject recomposition at an arbitrary
     * phase of an 8.33 ms (120 Hz) frame. A slow Handler fallback keeps the public clock moving
     * for service/headset clients while the display is off and Choreographer is not producing
     * frames; Media3 extrapolates position between these publications.
     */
    private val positionChoreographer = Choreographer.getInstance()
    private val positionPublicationFrameCallback = Choreographer.FrameCallback {
        drainPositionPublicationFromFrame()
    }
    private val positionPublicationFallbackRunnable = Runnable {
        drainPositionPublicationFromFallback()
    }
    private val scrubPreviewPublications = FrameConflatedPublication<ScrubPreviewPublication>()
    private val scrubPreviewFrameCallback = Choreographer.FrameCallback {
        drainScrubPreviewPublication()
    }
    private fun drainPositionPublicationFromFrame() {
        mainHandler.removeCallbacks(positionPublicationFallbackRunnable)
        drainPositionPublication()
    }

    private fun drainPositionPublicationFromFallback() {
        positionChoreographer.removeFrameCallback(positionPublicationFrameCallback)
        drainPositionPublication()
    }

    private fun schedulePositionPublication() {
        positionChoreographer.postFrameCallback(positionPublicationFrameCallback)
        mainHandler.postDelayed(
            positionPublicationFallbackRunnable,
            BACKGROUND_POSITION_PUBLICATION_FALLBACK_MS
        )
    }

    private fun drainPositionPublication() {
        val publication = positionPublications.drain() ?: return
        val committed = lastAdapterState.publication.committed
        if (
            !hideCommittedPublication &&
            committed?.selection?.token?.value == publication.ownerToken &&
            mutableState.value.songId == publication.songId
        ) {
            publishState(mutableState.value.copy(positionMs = publication.positionMs))
        }
    }

    /**
     * Touch remains visually immediate in Compose, while the decoder graph receives at most the
     * newest preview target once per display frame. Unlike the ordinary playback clock, a live
     * gesture needs no screen-off fallback: commit/cancel always performs one exact synchronous
     * terminal operation.
     */
    private fun drainScrubPreviewPublication() {
        val publication = scrubPreviewPublications.drain() ?: return
        val snapshot = scrubSnapshot ?: return
        val stillOwned = snapshot.sessionId == publication.sessionId &&
            snapshot.ownerToken == publication.ownerToken &&
            snapshot.songId == publication.songId &&
            scrubOwnerStillCurrent(snapshot)
        if (!stillOwned) {
            invalidateScrub()
            return
        }
        val dispatchAtMs = android.os.SystemClock.elapsedRealtime()
        if (scrubPreviewDispatchAllowed(lastScrubPreviewDispatchAtMs, dispatchAtMs)) {
            lastScrubPreviewDispatchAtMs = dispatchAtMs
            val selection = adapter.snapshot().latestRequest?.selection?.takeIf { it.token == publication.ownerToken }
            if (selection != null && adapter.previewSeekTo(publication.ownerToken, selection.sourcePositionMs(publication.positionMs))) return
        }
        // A held finger may produce no further MOVE. Keep its latest target until it can cross
        // the rate/owner-rebase gate; one transient rejection must not disable the whole gesture.
        if (scrubPreviewPublications.offer(publication)) {
            positionChoreographer.postFrameCallback(scrubPreviewFrameCallback)
        }
    }

    private fun cancelPendingScrubPreview() {
        positionChoreographer.removeFrameCallback(scrubPreviewFrameCallback)
        scrubPreviewPublications.drain()
    }

    private fun invalidateScrub() {
        scrubSnapshot?.let(::suppressDelayedScrubEnd)
        scrubSnapshot = null
        cancelPendingScrubPreview()
    }

    private fun currentStemMixPreviewOwner(): StemMixPreviewOwner? {
        if (released) return null
        val snapshot = lastAdapterState
        val committed = snapshot.publication.committed ?: return null
        val latest = snapshot.latestRequest?.takeIf {
            it.selection.token == committed.selection.token
        } ?: return null
        val installed = committed.stems.installed ?: return null
        val desired = committed.stems.desired ?: return null
        val value = mutableState.value
        if (committed.stems.phase != PlaybackStemPackagePhase.INSTALLED ||
            value.songId != committed.selection.songId ||
            value.loadedStemSignature != installed.packageSignature ||
            !stemMixPreviewPackageAuthorityMatches(
                installed.packageSignature,
                installed.requestRevision,
                desired.packageSignature,
                desired.requestRevision,
                installed.packageSignature,
                installed.requestRevision
            )
        ) return null
        val laneIds = latest.controls.stemLanes
            .mapTo(linkedSetOf(), EngineStemLane::id)
        if (!StemRoleContract.isPlayable(latest.controls.stemLanes.map(EngineStemLane::normalizedRole)) ||
            latest.controls.stemControls.keys != laneIds ||
            value.stemMix.keys != laneIds
        ) return null
        return StemMixPreviewOwner(
            controlLease = NativeAudioPlaybackCommandLease(
                token = committed.selection.token,
                songId = committed.selection.songId,
                audioRevision = committed.selection.audioRevision
            ),
            packageSignature = installed.packageSignature,
            packageRequestRevision = installed.requestRevision,
            laneIds = laneIds
        )
    }

    private fun invalidateStemMixPreview() {
        endEveryFrameBoundedStemMixPreview()
    }

    private fun endEveryFrameBoundedStemMixPreview() {
        var lane = 0
        while (lane < frameBoundedStemMixPreviewLeases.size) {
            frameBoundedStemMixPreviewLeases[lane]?.let {
                adapter.endFrameBoundedStemMixPreview(it)
            }
            frameBoundedStemMixPreviewLeases[lane] = null
            lane++
        }
    }

    private fun publishAcceptedStemMix(publication: StemMixPreviewPublication) {
        if (!adapter.commandLeaseIsCurrent(publication.owner.controlLease)) return
        val value = mutableState.value
        if (value.songId != publication.owner.songId ||
            value.loadedStemSignature != publication.owner.packageSignature
        ) return
        publishState(value.copy(stemMix = publication.mix))
    }

    private fun commitStemMixPublication(publication: StemMixPreviewPublication): Boolean {
        if (playbackCommandsBlocked()) return false
        if (currentStemMixPreviewOwner() != publication.owner) return false
        val settingsOwner = settingsOwner(publication.owner.controlLease)
        val byRole = stemMixByRole(publication.owner.controlLease, publication.mix)
        val receipt = adapter.commitStemMixIfCurrent(
            token = publication.owner.token,
            packageSignature = publication.owner.packageSignature,
            controls = publication.mix.mapValues { (_, control) -> control.toEngineControl() }
        ) ?: return false
        if (receipt.packageSignature != publication.owner.packageSignature) return false
        if (byRole != null) persistSettings(settingsOwner) { it.copy(stemMix = byRole) }
        publishAcceptedStemMix(publication)
        return true
    }

    private fun stemMixByRole(lease: NativeAudioPlaybackCommandLease, mix: Map<String, StemMixState>): Map<String, StemMixState>? {
        val lanes = adapter.snapshot().latestRequest?.takeIf { it.selection.token == lease.token }
            ?.controls?.stemLanes ?: return null
        return lanes.associate { it.normalizedRole to (mix[it.id] ?: return null) }
    }

    private fun metronomeSettings(lease: NativeAudioPlaybackCommandLease): SongMetronomeSettings? {
        val config = adapter.snapshot().latestRequest?.takeIf { it.selection.token == lease.token }
            ?.controls?.metronome ?: return null
        return SongMetronomeSettings(config.enabled, config.volume, config.subdivision,
            config.numerator, config.denominator)
    }

    private fun persistLoop(owner: SongSettingsOwner?, selection: app.stemwise.playback.engine.EngineSelection,
        a: Long?, b: Long?) {
        val loop = SongLoopPreference(a?.let(selection::sourcePositionMs), b?.let(selection::sourcePositionMs),
            a != null && b != null)
        pendingSettingsRestore = pendingSettingsRestore?.let {
            if (it.token == selection.token) it.copy(loop = SongLoopPreference()) else it
        }
        persistSettings(owner) { it.copy(loop = loop) }
    }

    private fun invalidateStemMixPreviewIfOwnerChanged(
        snapshot: NativeAudioPlaybackSessionState
    ) {
        val batchAuthority = frameBoundedStemMixPreviewLeases.firstNotNullOfOrNull { lease ->
            lease?.authority
        }
        if (batchAuthority == null) return
        val committed = snapshot.publication.committed
        val latest = snapshot.latestRequest
        val installed = committed?.stems?.installed
        val desired = committed?.stems?.desired
        val batchInvalid = (
            committed == null || latest == null || installed == null || desired == null ||
                latest.selection.token != batchAuthority.token ||
                latest.exactOwnership != batchAuthority.ownership ||
                latest.controls.sourceMode != EngineSourceMode.STEMS ||
                committed.selection.token != batchAuthority.token ||
                committed.owner.ownership != batchAuthority.ownership ||
                !stemMixPreviewPackageAuthorityMatches(
                    installed.packageSignature,
                    installed.requestRevision,
                    desired.packageSignature,
                    desired.requestRevision,
                    batchAuthority.packageSignature,
                    batchAuthority.packageRequestRevision
                )
            )
        if (batchInvalid) invalidateStemMixPreview()
    }

    /** Restores the same owner's exact pre-gesture cursor before an explicit transport command. */
    private fun restoreScrubPositionBeforeTransportCommand() {
        val snapshot = scrubSnapshot ?: return
        suppressDelayedScrubEnd(snapshot)
        scrubSnapshot = null
        cancelPendingScrubPreview()
        if (!scrubOwnerStillCurrent(snapshot)) return
        if (snapshot.wasEnded) {
            restoreEndedScrub(snapshot)
        } else {
            finishScrubOwner(
                snapshot = snapshot,
                positionMs = snapshot.positionMs,
                playWhenReady = false
            )
        }
    }

    /** System interruption cancels the gesture fully, including its remembered play intent. */
    private fun cancelScrubForSystemInterruption() {
        val snapshot = scrubSnapshot ?: return
        cancelScrub()
    }

    private fun suppressDelayedScrubEnd(snapshot: ScrubSnapshot) {
        // The main-thread snapshot can trail a same-token native recovery generation. Refresh the
        // retained delayed-end fence without inserting a pause/resume pulse into audible scrubbing.
        val ownership = adapter.setPlayWhenReadyIfCurrent(
            snapshot.ownerToken,
            snapshot.wasPlaying
        )?.ownership
            ?: snapshot.ownerOwnership
        suppressedScrubEnd = snapshot.ownerToken to ownership
    }

    private val playbackEvents = PlaybackEventHistory()
    private val mutableDetailedCapture = MutableStateFlow(false)
    internal val detailedCaptureActive = mutableDetailedCapture.asStateFlow()

    internal fun setDetailedPlaybackCapture(enabled: Boolean) {
        playbackEvents.setCaptureEnabled(enabled)
        setNativeDetailedCapture(enabled)
        mutableDetailedCapture.value = enabled
    }

    private fun recordPlaybackEvent(
        event: String,
        snapshot: NativeAudioPlaybackSessionState? = null
    ) {
        if (!playbackEvents.recordingEnabled) return
        val captured = snapshot ?: adapter.snapshot()
        val requested = captured.latestRequest?.exactOwnership
        val committed = captured.publication.committed?.owner?.ownership
        playbackEvents.record(
            android.os.SystemClock.elapsedRealtime(), event,
            requested?.generation ?: 0L, requested?.selectionToken ?: 0L,
            committed?.generation ?: 0L, committed?.selectionToken ?: 0L,
            focusOwner.audioFocusRequested, focusOwner.audioFocusHeld, focusOwner.focusSuspended, focusOwner.resumeAfterAudioFocusGainToken
        )
    }

    private fun updateLastAdapterState(snapshot: NativeAudioPlaybackSessionState) {
        if (lastAdapterState.latestRequest?.exactOwnership != snapshot.latestRequest?.exactOwnership ||
            lastAdapterState.publication.committed?.owner?.ownership !=
            snapshot.publication.committed?.owner?.ownership) {
            recordPlaybackEvent("owner-transition", snapshot)
        }
        lastAdapterState = snapshot
    }

    /** Captures on Main, formats on a worker; no audio callback or periodic writer is involved. */
    suspend fun exportPlaybackSnapshot(): String {
        val captured = withContext(Dispatchers.Main.immediate) {
            val engine = adapter.diagnosticsSnapshot()
            PlaybackEvidenceSnapshot(
                System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(),
                roots.captureAuthority(), requestSerial, adapter.snapshot(), engine != null,
                engine?.runtime?.ownership, engine?.controllerPreparationStages.orEmpty(),
                engine?.runtime?.endOrdering, playbackEvents.snapshot(), stemPreparationDiagnostics(),
                engine?.runtime?.canonicalPcmIo
            )
        }
        return withContext(Dispatchers.Default) { captured.toJson() }
    }

    internal fun playbackEventHistoryForTest(): List<String> = playbackEvents.snapshot()

    private val focusOwner: PlaybackAudioFocusOwner by lazy {
        PlaybackAudioFocusOwner(object : PlaybackAudioFocusHost {
            override val state get() = mutableState.value
            override fun snapshot() = adapter.snapshot()
            override fun commandsBlocked() = playbackCommandsBlocked()
            override fun requestPlatformFocus(): Int = platform.requestFocus()
            override fun cancelFailedSelectionRelease() { mainHandler.removeCallbacks(failedSelectionFocusReleaseRunnable) }
            override fun prepareAbandon() { platform.prepareFocusAbandon(); cancelFailedSelectionRelease() }
            override fun abandonPlatformFocus() = platform.abandonFocus()
            override fun suspendOutput() = adapter.suspendForAudioFocusLoss()
            override fun resumeOutput() = adapter.resumeAfterAudioFocusGain()
            override fun pauseOutput() { adapter.pause() }
            override fun cancelScrub() = cancelScrubForSystemInterruption()
            override fun retireResolvingPlay() {
                if (currentResolvingTransportIntent() != null) selectionResolution.updateTransport(requestSerial, false)
                pendingSettingsRestore = pendingSettingsRestore?.copy(autoplay = false)
                updateDeferredTransportIntent(false)
            }
            override fun clearTransportIntent() { latestTransportIntent = false }
            override fun record(event: String) = recordPlaybackEvent(event)
            override fun publish(state: AudioPlaybackState) = publishState(state)
        })
    }
    private val failedSelectionFocusReleaseRunnable = Runnable {
        val snapshot = adapter.snapshot()
        if (snapshot.publication.committed == null && snapshot.latestRequest == null &&
            resolvingRequestSerial == null) abandonPlaybackAudioFocus()
    }

    init {
        adapter = NativeAudioPlaybackSessionAdapter.create(
            applicationContext,
            NativeAudioPlaybackSessionEffectListener { effect ->
                if (effect is NativeAudioPlaybackSessionEffect.OutputUnavailable) {
                    // Latch on the controller-callback thread before a queued main-thread effect
                    // can race an explicit Recents removal.
                    nativeProcessRestartRequired = true
                    PlaybackService.onNativeProcessRestartRequired()
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    handleAdapterEffect(effect)
                } else {
                    mainHandler.post { handleAdapterEffect(effect) }
                }
            },
            packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory
        )
        platform.start()
        scope.launch {
            adapter.state.collect { snapshot ->
                testAdapter?.beforeAdapterState(snapshot)
                handleAdapterState(snapshot)
            }
        }
        scope.launch {
            val preparationAdmission = state.map {
                !playbackCommandsBlocked() && resolvingRequestSerial == null
            }.distinctUntilChanged()
            combine(adapter.state, preparationAdmission) { snapshot, admitted ->
                snapshot.publication.committed?.takeIf { committed ->
                    admitted && snapshot.latestRequest?.selection == committed.selection &&
                        committed.source.desiredMode == EngineSourceMode.ORIGINAL
                }?.selection?.token
            }.distinctUntilChanged().collectLatest { token ->
                if (token == null) return@collectLatest
                val selection = adapter.snapshot().publication.committed?.selection
                    ?.takeIf { it.token == token } ?: return@collectLatest
                separationRepository.observeActiveStems(selection.songId)
                    .distinctUntilChanged().collect { stems ->
                        prepareLocalStemsWhileOriginal(token, stems)
                    }
            }
        }
    }

    /** Attach local inventory only after Original owns the song; the engine schedules its cache. */
    private suspend fun prepareLocalStemsWhileOriginal(
        token: EngineSelectionToken,
        stems: List<StemAssetEntity>
    ) {
        if (playbackCommandsBlocked() || resolvingRequestSerial != null || stems.isEmpty()) return
        val snapshot = adapter.snapshot()
        val committed = snapshot.publication.committed ?: return
        if (committed.selection.token != token ||
            snapshot.latestRequest?.selection != committed.selection ||
            committed.source.desiredMode != EngineSourceMode.ORIGINAL
        ) return
        val lease = acquireControlLease(committed.selection.songId)
            ?.takeIf { it.native.token == token } ?: return
        val song = metadataPublications.payloadFor(committed.metadata)?.song ?: return
        if (partsDeletionBlocks(song.id)) return
        val verified = try {
            verifiedPlaybackStems(song.id, stems)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        if (!adapter.commandLeaseIsCurrent(lease.native) || resolvingRequestSerial != null || partsDeletionBlocks(song.id)) return
        val candidate = runCatching { stemCandidate(song, verified) }.getOrNull() ?: return
        if (committed.stems.desired?.let {
                it.packageSignature == candidate.packageSignature &&
                    it.stemRevision == candidate.stemRevision
            } == true
        ) return
        stemCandidates[candidate.packageSignature] = candidate
        trimStemCandidates()
        if (adapter.configureStemPackageIfCurrent(
                lease.native, candidate.stemRevision, candidate.lanes, selectStems = false
            )
        ) {
            restoreStemMix(song, lease.native, candidate.lanes)
            instrumentationRecorder.increment("currentStemPrewarmBuildCount")
        }
    }

    private fun restoreStemMix(song: Song, lease: NativeAudioPlaybackCommandLease, lanes: List<EngineStemLane>) {
        val mix = settingsIdentity(song)?.let { cachedSettings(it)?.getOrNull()?.stemMix }.orEmpty()
        if (mix.isEmpty()) return
        adapter.setStemMixIfCurrent(lease, lanes.associate { lane ->
            lane.id to (mix[lane.normalizedRole]?.toEngineControl() ?: EngineStemControl())
        })
    }

    private fun processRestartRequired(): Boolean =
        nativeProcessRestartRequired || adapter.processRestartRequired()

    private fun playbackLifecycleBlocked(): Boolean = released || processRestartRequired()

    private fun playbackCommandsBlocked(): Boolean =
        playbackLifecycleBlocked() || !librarySessionCommandsAllowed

    fun load(
        song: Song,
        autoplay: Boolean,
        positionMs: Long = 0L,
        forceReload: Boolean = false
    ): Boolean = loadInternal(song, autoplay, positionMs, forceReload)

    private fun loadInternal(
        song: Song,
        autoplay: Boolean,
        positionMs: Long,
        forceReload: Boolean,
        restoredSession: LastListeningSession? = null,
        restartAtZero: Boolean = false,
        preservePreviousPair: Boolean = false
    ): Boolean {
        if (playbackCommandsBlocked() || partsDeletionBlocks(song.id)) return false
        val outgoingPreview = songTrimPreview?.takeIf { it.lease.requestSerial == requestSerial &&
            roots.captureAuthority() == it.root }
        val outgoingPreviewToken = outgoingPreview?.let { adapter.snapshot().latestRequest?.selection?.token }
        saveListeningSession(immediate = true)
        songTrimPreview = null
        if (restoredSession == null) cancelListeningRestore()
        // A new explicit selection supersedes the old queue edge before source resolution. If the
        // provider lookup suspends, the already-staged automatic successor must not become audible.
        adapter.supersedeAutomaticEndForManualSelection()
        selectionResolution.cancel()
        manualNavigationSongId = song.id
        previewSelection(null)
        sessionArtwork.cancelSelected()
        sessionArtwork.cancelPrefetch()
        selectionArtworkTargetListener?.invoke(song)
        pendingOwnerlessRebind = null
        requestSerial = incrementRevision(requestSerial, "selection request")
        val serial = requestSerial
        restoringListeningSession = restoredSession?.let { serial to it }
        selectionResolution.begin(serial, song, positionMs, autoplay, forceReload, restartAtZero, preservePreviousPair)
        publishState(mutableState.value.copy(loading = true, error = null, notice = null))

        selectionResolution.resolve(scope,
            resolve = { withContext(Dispatchers.IO) {
                resolvePlayableSong(song, prepareSelection = true)?.also { (verified, _) ->
                    check(restoredSession == null || settingsIdentity(verified) == restoredSession.identity) {
                        "Saved listening audio no longer matches this song"
                    }
                }
            } },
            isCurrent = { serial == requestSerial && !playbackCommandsBlocked() },
            onUnavailable = { failure ->
                restoringListeningSession = null
                if (outgoingPreview != null && outgoingPreviewToken != null &&
                    adapter.snapshot().latestRequest?.selection?.token == outgoingPreviewToken) {
                    latestTransportIntent = false
                    unloadNativeOwner(outgoingPreview.song.id, preservePendingManualSelection = true)
                }
                if (failure != null && failure !is CancellationException) {
                    Log.w(TAG, "Could not resolve the selected local audio", failure)
                }
                completeUnresolvedManualSelection(if (failure is SongPlaybackSettingsException)
                    PlaybackNotice("Saved song settings unavailable", requireNotNull(failure.message), PlaybackNoticeCategory.SONG_SELECTION)
                    else songSelectionNotice(failure))
            },
            onResolved = { verified, location, intent ->
                selectResolvedSong(verified, location, intent.autoplay, intent.positionMs, intent.forceReload, intent.serial,
                    intent.restartAtZero, intent.preservePreviousPair)
            }
        )
        return true // The selection is accepted; publication follows verified source resolution.
    }

    private fun clearResolvingSelection() {
        cancelListeningRestore()
        selectionResolution.clear()
        pendingSettingsRestore = null
    }

    private fun cancelResolvingSelection() {
        pendingSettingsRestore = null
        selectionResolution.cancel()
        manualNavigationSongId = null
    }

    private fun cancelUnpublishedSelection(songId: String): Boolean {
        if (resolvingSongId != songId && pendingOwnerlessRebind?.song?.id != songId) return false
        requestSerial = incrementRevision(requestSerial, "unpublished selection cancellation")
        pendingOwnerlessRebind = null
        selectionResolution.cancel()
        manualNavigationSongId = null
        clearResolvingSelection()
        completeUnresolvedManualSelection()
        return true
    }

    private fun completeUnresolvedManualSelection(notice: PlaybackNotice? = null) {
        adapter.completeManualSelectionIntentWithoutReplacement()
        val snapshot = adapter.snapshot()
        latestTransportIntent = pendingSettingsRestore?.autoplay
            ?: snapshot.latestRequest?.controls?.playWhenReady ?: false
        if (latestTransportIntent != true) abandonPlaybackAudioFocus()
        snapshot.publication.committed?.selection?.songId?.let(queueController::select)
        restoreSelectionArtworkTarget()
        updateLikelyNext()
        publishState(unresolvedSelectionState(projectState(snapshot, ownerChanged = false), notice))
    }

    fun setPlaybackQueue(songs: List<Song>, snapshotReady: Boolean, shuffle: Boolean = state.value.shuffleEnabled) {
        if (playbackLifecycleBlocked()) return
        val previousSession = adapter.snapshot()
        val previousLatest = previousSession.latestRequest
        val previousLatestSong = previousLatest?.selection?.songId?.let(queueById::get)
        val distinct = songs
            .asSequence()
            .filter { it.id.isNotBlank() && it.durationMs > 0L }
            .distinctBy(Song::id)
            .toList()
        val committed = previousSession.publication.committed
        val committedId = committed?.selection?.songId
        val previousCommittedSong = committedId?.let(queueById::get)
        if (distinct.isEmpty() && emptyPlaybackQueueIsTransient(
                hasCurrentOwner = previousLatest != null || committed != null ||
                    selectionResolution.current != null || pendingOwnerlessRebind != null,
                explicitUnloadPending = hideCommittedPublication,
                snapshotReady = snapshotReady
            )
        ) return
        libraryOrder = distinct.map(Song::id)
        rebuildQueueMap(distinct)
        if (manualNavigationSongId !in queueById) manualNavigationSongId = null

        if (queueController.snapshot().order.isEmpty()) {
            queueController.start(libraryOrder, shuffle = shuffle)
        } else {
            queueController.setShuffle(shuffle)
            if (shuffle) {
                queueController.updateLibrary(libraryOrder)
            } else {
                queueController.setSequentialOrder(libraryOrder)
            }
        }
        if (snapshotReady) librarySessionCommandsAllowed = true

        val unresolvedSongId = resolvingSongId ?: pendingOwnerlessRebind?.song?.id
        if (unresolvedSongId != null && unresolvedSongId !in queueById) {
            cancelUnpublishedSelection(unresolvedSongId)
        }

        // The newer lookup owns its complete intent; retiring outgoing audio must not replace it.
        val incoming = selectionResolution.current
        val waiting = pendingOwnerlessRebind
        val survivingIncomingId = incoming?.songId ?: waiting?.song?.id
        if (survivingIncomingId != null && survivingIncomingId in queueById) {
            if (committedId != null && committedId !in queueById ||
                previousLatest != null && previousLatest.selection.songId !in queueById) {
                unloadNativeOwner(committedId ?: previousLatest!!.selection.songId,
                    preservePendingManualSelection = true)
            }
            val refreshed = queueById.getValue(survivingIncomingId)
            if (incoming != null && incoming.song != refreshed) {
                val saved = restoringListeningSession?.takeIf { it.first == incoming.serial }?.second
                    ?.takeIf { settingsIdentity(refreshed) == it.identity }
                loadInternal(refreshed, incoming.autoplay, incoming.positionMs, incoming.forceReload, saved,
                    incoming.restartAtZero, incoming.preservePreviousPair)
            }
            publishQueueState()
            updateLikelyNext(availabilityRefreshed = true)
            return
        }

        // A library refresh is also an ownership boundary. If pending B disappeared, allowing its
        // already-queued native publication to commit would create an owner that is outside the
        // one canonical queue. Supersede it immediately with the queue's valid fallback (or stop
        // when the refreshed library is empty); stale B publications then fail latest-token
        // authority in the adapter.
        if (previousLatest != null && previousLatest.selection.songId !in queueById) {
            val fallback = queueController.snapshot().currentId?.let(queueById::get)
            if (fallback != null) {
                load(
                    fallback,
                    autoplay = previousLatest.controls.playWhenReady,
                    positionMs = 0L,
                    forceReload = true
                )
            } else {
                unload(previousLatest.selection.songId)
                publishQueueState()
            }
            return
        }

        val pendingLatest = previousLatest?.takeIf { requested ->
            requested.selection.token != committed?.selection?.token
        }
        if (pendingLatest != null) {
            val refreshed = queueById[pendingLatest.selection.songId]
            if (refreshed != null) {
                val location = refreshed.sourcePath?.takeIf(String::isNotBlank)
                    ?: pendingLatest.selection.originalLocation
                val revision = effectiveAudioRevision(refreshed, location)
                if (!sameAudioSelection(
                        pendingLatest.selection,
                        refreshed.id,
                        revision,
                        location,
                        refreshed.durationMs,
                        refreshed.sourceHash
                    )
                ) {
                    load(
                        refreshed,
                        autoplay = pendingLatest.controls.playWhenReady,
                        positionMs = pendingLatest.selection.publicPositionMs(pendingLatest.controls.requestedPositionMs),
                        forceReload = true
                    )
                    return
                }
                if (previousLatestSong != refreshed) {
                    adapter.updateMetadata(publicationMetadata(refreshed, location, revision))
                    scheduleSessionArtwork(refreshed, location, revision)
                }
            }
        }

        if (committedId != null && committedId !in queueById) {
            unload(committedId)
        } else if (committed != null) {
            queueController.select(committed.selection.songId)
            queueById[committed.selection.songId]?.let { refreshed ->
                updateCommittedSongFromLibrary(committed, previousCommittedSong, refreshed)
            }
        }

        publishQueueState()
        updateLikelyNext(availabilityRefreshed = true)
        restoreListeningSessionIfReady(snapshotReady)
    }

    fun startQueue(
        songs: List<Song>,
        shuffle: Boolean,
        autoplay: Boolean = true,
        startId: String? = null,
        seed: Long = System.nanoTime()
    ): Song? {
        if (playbackCommandsBlocked()) return null
        val ready = songs
            .asSequence()
            .filter { it.id.isNotBlank() && it.durationMs > 0L }
            .distinctBy(Song::id)
            .toList()
        if (ready.isEmpty()) return null
        previousPressTracker.reset()
        libraryOrder = ready.map(Song::id)
        rebuildQueueMap(ready)
        val queue = queueController.start(libraryOrder, startId, shuffle, seed)
        val first = queue.currentId?.let(queueById::get) ?: return null
        return first.takeIf {
            load(first, autoplay = autoplay, positionMs = 0L, forceReload = true)
        }
    }

    fun selectSong(song: Song, autoplay: Boolean = true, positionMs: Long = 0L): Boolean {
        previousPressTracker.reset()
        return load(song, autoplay, positionMs, forceReload = true)
    }

    /** Cancellable swipe intent; decoding begins only after exact package validation. */
    fun previewSelection(song: Song?): Boolean {
        if (playbackCommandsBlocked()) return false
        manualPrewarmJob?.cancel()
        manualPrewarmSerial = incrementRevision(manualPrewarmSerial, "manual prewarm")
        val serial = manualPrewarmSerial
        adapter.setManualPrewarm(null)
        manualPrewarmSongId = null
        if (song == null) return true
        if (partsDeletionBlocks(song.id)) return false
        val queued = queueById[song.id] ?: return false
        val selectionSerial = requestSerial
        val current = adapter.snapshot().latestRequest?.selection ?: return false
        if (queued.id == current.songId) return false
        manualPrewarmSongId = queued.id
        manualPrewarmJob = scope.launch {
            val verified = verifiedQueueItem(queued, allowDormantControls = true) ?: return@launch
            if (serial == manualPrewarmSerial && selectionSerial == requestSerial &&
                !playbackCommandsBlocked() && queueById[queued.id] == queued &&
                adapter.snapshot().latestRequest?.selection == current
            ) adapter.setManualPrewarm(verified)
        }
        return true
    }

    fun setShuffle(enabled: Boolean, seed: Long = System.nanoTime()): Boolean {
        if (playbackCommandsBlocked()) return false
        if (restoringListeningSession == null) cancelListeningRestore()
        queueController.setShuffle(enabled, seed)
        if (!enabled) queueController.setSequentialOrder(libraryOrder)
        publishQueueState()
        updateLikelyNext()
        return true
    }

    private val previousPressTracker = PreviousPressTracker()

    fun nextTrack(): String? = navigate(QueueTransition.EXPLICIT_NEXT)

    fun previousTrack(): String? = navigate(QueueTransition.EXPLICIT_PREVIOUS)

    fun previousOrRestart(): String? =
        navigate(QueueTransition.EXPLICIT_PREVIOUS, previousPress = true)

    fun toggle(song: Song, positionMs: Long = 0L) {
        val latest = adapter.snapshot().latestRequest
        when {
            latest?.selection?.songId != song.id ->
                selectSong(song, autoplay = true, positionMs = positionMs)
            latest.controls.playWhenReady -> pause()
            else -> play()
        }
    }

    /**
     * Player-surface transport with an exact selected-song recovery boundary.
     *
     * Process/session recreation can legitimately restore the visible selected song before a
     * native request exists. In that one state, an enabled Play action must recreate the exact
     * request rather than silently applying a current-owner command to no owner. A pending or
     * committed request always keeps the established current-owner toggle semantics, so an older
     * presentation can never rewind a newer external/automatic selection.
     */
    fun toggleCurrentOrRebind(selectedSong: Song, positionMs: Long = 0L): Boolean {
        if (playbackCommandsBlocked()) return false
        val snapshot = adapter.snapshot()
        if (snapshot.lifecycle == NativeAudioPlaybackSessionLifecycle.STOPPING) {
            // The old request remains visible inside the adapter until its asynchronous stop
            // settles, but transport commands are intentionally rejected in this lifecycle.
            // Preserve the exact selected-card intent and replay it only after StopCompleted;
            // otherwise the first enabled Play action is deterministically lost.
            pendingOwnerlessRebind = PendingOwnerlessRebind(
                song = selectedSong,
                positionMs = positionMs,
                autoplay = true,
                requestSerial = requestSerial
            )
            return true
        }
        return when (currentResolvingTransportIntent() ?: snapshot.latestRequest?.controls?.playWhenReady) {
            true -> pause()
            false -> play()
            null -> load(
                song = selectedSong,
                autoplay = true,
                positionMs = positionMs,
                forceReload = true
            )
        }
    }

    fun pause(): Boolean = pauseInternal(preservePreviousPair = false)

    private fun pauseInternal(preservePreviousPair: Boolean): Boolean {
        recordPlaybackEvent("pause-request")
        restoreScrubPositionBeforeTransportCommand()
        val resolving = currentResolvingTransportIntent() != null
        if (resolving) selectionResolution.updateTransport(requestSerial, false)
        pendingSettingsRestore = pendingSettingsRestore?.copy(autoplay = false)
        val deferred = updateDeferredTransportIntent(false)
        val accepted = adapter.pause() || resolving || deferred
        if (accepted) {
            if (!preservePreviousPair) previousPressTracker.reset()
            latestTransportIntent = false
            focusOwner.resumeAfterAudioFocusGainToken = null
            focusOwner.focusResumeProof = null
            publishState(
                mutableState.value.copy(
                    playing = false,
                    playWhenReady = false,
                    suppression = AudioPlaybackSuppression.NONE
                )
            )
        }
        abandonPlaybackAudioFocus()
        saveListeningSession(immediate = true)
        return accepted
    }

    fun play(): Boolean = playInternal(preservePreviousPair = false)

    private fun updateDeferredTransportIntent(autoplay: Boolean): Boolean {
        val pending = pendingOwnerlessRebind?.takeIf { it.requestSerial == requestSerial } ?: return false
        pendingOwnerlessRebind = pending.copy(autoplay = autoplay)
        return true
    }

    private fun playInternal(preservePreviousPair: Boolean): Boolean {
        recordPlaybackEvent("play-request")
        if (playbackCommandsBlocked()) return false
        if (restoringListeningSession != null && pendingSettingsRestore == null) cancelListeningRestore()
        restoreScrubPositionBeforeTransportCommand()
        if (updateDeferredTransportIntent(true)) {
            if (!preservePreviousPair) previousPressTracker.reset()
            latestTransportIntent = true
            publishState(mutableState.value.copy(playWhenReady = true))
            return true
        }
        if (selectionResolution.current?.serial == requestSerial) {
            if (!requestPlaybackAudioFocus()) return false
            selectionResolution.updateTransport(requestSerial, true)
            if (!preservePreviousPair) previousPressTracker.reset()
            latestTransportIntent = true
            ensurePlaybackService()
            publishState(mutableState.value.copy(playWhenReady = true))
            return true
        }
        pendingSettingsRestore?.let {
            if (!requestPlaybackAudioFocus()) return false
            pendingSettingsRestore = it.copy(autoplay = true)
            latestTransportIntent = true
            if (!preservePreviousPair) previousPressTracker.reset()
            ensurePlaybackService()
            publishState(mutableState.value.copy(playWhenReady = true))
            return true
        }
        val snapshot = adapter.snapshot()
        val latest = snapshot.latestRequest ?: return false
        val exactCommitted = snapshot.publication.committed?.takeIf {
            it.selection.token == latest.selection.token
        }
        // Only an exact committed owner can prove EOS. A hidden/pending target must retain its
        // requested position and simply update its desired transport while it decodes.
        if (exactCommitted != null &&
            (endedOwnerToken == exactCommitted.selection.token.value || state.value.ended)
        ) {
            seekTo(0L)
        }
        if (!requestPlaybackAudioFocus()) return false
        endedOwnerToken = null
        focusOwner.resumeAfterAudioFocusGainToken = latest.selection.token.value.takeIf { focusOwner.focusSuspended }
        focusOwner.focusResumeProof = null
        val accepted = adapter.play()
        if (accepted) {
            if (!preservePreviousPair) previousPressTracker.reset()
            ensurePlaybackService()
            latestTransportIntent = true
            publishState(
                mutableState.value.copy(
                    playing = exactCommitted != null && !focusOwner.focusSuspended,
                    playWhenReady = true,
                    ended = false,
                    error = null,
                    suppression = if (focusOwner.focusSuspended) AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS
                        else AudioPlaybackSuppression.NONE
                )
            )
        }
        return accepted
    }

    private fun currentResolvingTransportIntent(): Boolean? = selectionResolution.current
        ?.takeIf { it.serial == requestSerial }?.autoplay ?: pendingSettingsRestore?.autoplay

    fun stop() {
        invalidateScrub()
        pause()
        seekTo(0L)
    }

    internal fun stopForTaskRemoval() {
        if (released || platform.stoppingForTaskRemoval) return
        saveListeningSession(immediate = true)
        cancelListeningRestore()
        cancelSettingsPreload()
        pendingOwnerlessRebind = null
        platform.markTaskRemoval()
        pendingTerminalSeek = null
        requestSerial = incrementRevision(requestSerial, "task removal")
        removedTaskRestoreSerial = requestSerial
        removedTaskAvailable = false
        cancelResolvingSelection()
        hideCommittedPublication = true
        sessionArtwork.cancelSelected()
        sessionArtwork.cancelPrefetch()
        optimisticSeek = null
        invalidateScrub()
        positionPublications.discardPending()
        // The adapter synchronously engages the native silence gate before returning.
        adapter.stop()
        publishCurrentControlLease()
        latestTransportIntent = null
        queueController.clear()
        abandonPlaybackAudioFocus()
        publishState(emptyPlaybackState())
    }

    internal fun onAppTaskAvailable() {
        removedTaskAvailable = true
        restoreRemovedTaskIfReady()
    }

    private fun restoreRemovedTaskIfReady() {
        if (!removedTaskAvailable) return
        val serial = removedTaskRestoreSerial ?: return
        scope.launch {
            preferencesPersistence.awaitListeningWrites()
            if (removedTaskRestoreSerial != serial || requestSerial != serial || playbackCommandsBlocked()) return@launch
            val snapshot = adapter.snapshot()
            if (snapshot.lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE ||
                snapshot.latestRequest != null || snapshot.publication.committed != null) return@launch
            removedTaskRestoreSerial = null
            listeningRestoreAllowed = true
            restoreListeningSessionIfReady(librarySessionCommandsAllowed)
        }
    }

    /** True only after an unrecoverable native safety failure, never for an ordinary route retry. */
    internal fun processRestartRequiredAfterTaskRemoval(): Boolean = processRestartRequired()

    fun unload(songId: String) = unloadNativeOwner(songId, preservePendingManualSelection = false)

    private fun unloadNativeOwner(songId: String, preservePendingManualSelection: Boolean) {
        val snapshot = adapter.snapshot()
        if (snapshot.latestRequest?.selection?.songId != songId &&
            snapshot.publication.committed?.selection?.songId != songId
        ) {
            cancelUnpublishedSelection(songId)
            return
        }
        if (!preservePendingManualSelection) {
            saveListeningSession(immediate = true)
            cancelListeningRestore()
            cancelSettingsPreload()
            pendingOwnerlessRebind = null
            requestSerial = incrementRevision(requestSerial, "unload")
            cancelResolvingSelection()
        }
        invalidateScrub()
        pendingTerminalSeek = null
        hideCommittedPublication = true
        sessionArtwork.cancelSelected()
        sessionArtwork.cancelPrefetch()
        adapter.setAutomaticEndPlan(null)
        adapter.stop()
        publishCurrentControlLease()
        if (!preservePendingManualSelection) latestTransportIntent = null
        abandonPlaybackAudioFocus()
        publishState(emptyPlaybackState().copy(loading = resolvingRequestSerial != null))
    }

    suspend fun clearLibrarySession() = withContext(Dispatchers.Main.immediate) {
        saveListeningSession(immediate = true)
        cancelListeningRestore()
        listeningRootAuthority = null
        cancelSettingsPreload()
        librarySessionCommandsAllowed = false
        pendingOwnerlessRebind = null
        invalidateStemMixPreview()
        pendingTerminalSeek = null
        requestSerial = incrementRevision(requestSerial, "library clear")
        cancelResolvingSelection()
        hideCommittedPublication = true
        sessionArtwork.clear()
        adapter.setAutomaticEndPlan(null)
        adapter.stop()
        publishCurrentControlLease()
        latestTransportIntent = null
        abandonPlaybackAudioFocus()
        queueController.clear()
        libraryOrder = emptyList()
        queueById.clear()
        analysisRepository.retainSelectionPresentation(null)
        analysisRepository.retainQueuePresentations(emptyList())
        metadataPublications.clear()
        stemCandidates.clear()
        optimisticSeek = null
        invalidateScrub()
        publishState(emptyPlaybackState())
    }

    /** Current-owner transport path used by MediaSession and internal queue policy. */
    fun seekTo(positionMs: Long): Boolean = seekToForSession(positionMs) != null

    internal fun seekToForSession(positionMs: Long, preservePreviousPair: Boolean = false): SeekAdmission? {
        val lease = acquireCurrentControlLease() ?: return null
        return seekToInternal(
            positionMs = positionMs,
            terminalPlayWhenReady = null,
            controlLease = lease,
            allowPendingTerminal = true,
            preservePreviousPair = preservePreviousPair
        )
    }

    internal fun seekByForSession(deltaMs: Long): SeekAdmission? {
        if (playbackCommandsBlocked()) return null
        terminalRelativePosition(deltaMs)?.let { return seekToForSession(it) }
        val admission = adapter.resolveRelativeSeek(deltaMs) ?: return null
        val selection = adapter.snapshot().latestRequest?.selection?.takeIf { it.token == admission.lease.token } ?: return null
        return seekToInternal(selection.publicPositionMs(admission.positionMs), null, PlaybackControlLease(admission.lease), true)
    }

    /** UI transport path: a delayed callback can only address the selection that created it. */
    internal fun seekTo(positionMs: Long, controlLease: PlaybackControlLease): Boolean =
        seekToInternal(
            positionMs = positionMs,
            terminalPlayWhenReady = null,
            controlLease = controlLease,
            allowPendingTerminal = false
        ) != null

    internal fun seekToWithReceipt(positionMs: Long, controlLease: PlaybackControlLease): NativeAudioPlaybackSeekReceipt? =
        (seekToInternal(positionMs, null, controlLease, false) as? SeekAdmission.Position)?.receipt

    internal fun seekByWithReceipt(deltaMs: Long, controlLease: PlaybackControlLease): NativeAudioPlaybackSeekReceipt? {
        if (!adapter.commandLeaseIsCurrent(controlLease.native)) return null
        terminalRelativePosition(deltaMs)?.let { return seekToWithReceipt(it, controlLease) }
        val admission = adapter.resolveRelativeSeek(deltaMs)?.takeIf { it.lease == controlLease.native } ?: return null
        val selection = adapter.snapshot().latestRequest?.selection?.takeIf { it.token == admission.lease.token } ?: return null
        return seekToWithReceipt(selection.publicPositionMs(admission.positionMs), controlLease)
    }

    private fun terminalRelativePosition(deltaMs: Long): Long? {
        val snapshot = adapter.snapshot()
        val latest = snapshot.latestRequest ?: return null
        val pending = pendingTerminalSeek?.ownerToken == latest.selection.token
        val committed = snapshot.publication.committed
        if (!pending && (committed?.selection?.token != latest.selection.token ||
            endedOwnerToken != latest.selection.token.value)) return null
        val end = latest.selection.playbackDurationMs
        return if (deltaMs > 0L) end else if (deltaMs < -end) 0L else end + deltaMs
    }

    private fun seekToInternal(
        positionMs: Long,
        terminalPlayWhenReady: Boolean?,
        controlLease: PlaybackControlLease,
        allowPendingTerminal: Boolean,
        preservePreviousPair: Boolean = false
    ): SeekAdmission? {
        if (playbackCommandsBlocked()) return null
        val snapshot = adapter.snapshot()
        val latest = snapshot.latestRequest ?: return null
        val expectedOwnerToken = controlLease.native.token
        if (!adapter.commandLeaseIsCurrent(controlLease.native) ||
            latest.selection.token != expectedOwnerToken
        ) return null
        // A rejected outgoing callback must not retire the current owner's gesture.
        invalidateScrub()
        val target = playbackControlPositionMs(positionMs.toDouble(), latest.selection.playbackDurationMs)
        pendingTerminalSeek = null
        if (target > 0L && target == latest.selection.playbackDurationMs) {
            // Seeking to the exact exclusive EOS cannot wait for callback proof from a generation
            // that contains no PCM. A pending target therefore retains one token-bound intent and
            // resolves it as soon as that exact owner commits. An unresolved newer load has no
            // token yet, so this seek still belongs to the outgoing owner and must not skip it.
            if (resolvingRequestSerial != null) return null
            val request = TokenBoundTerminalSeek(latest.selection.token, target)
            if (snapshot.publication.committed?.selection?.token == request.ownerToken) {
                navigate(
                    QueueTransition.AUTOMATIC_END,
                    positionMs = target,
                    automaticEndOwnerToken = request.ownerToken,
                    playWhenReadyOverride = terminalPlayWhenReady
                )
            } else {
                if (!allowPendingTerminal) return null
                pendingTerminalSeek = request
            }
            pauseTrimPreviewAtEnd(latest.selection)
            if (!preservePreviousPair) previousPressTracker.reset()
            return SeekAdmission.TerminalNavigation
        }
        val nativeReceipt = adapter.seekToIfCurrent(controlLease.native, latest.selection.sourcePositionMs(target)) ?: return null
        val receipt = nativeReceipt.copy(positionMs = latest.selection.publicPositionMs(nativeReceipt.positionMs))
        if (!adapter.commandLeaseIsCurrent(controlLease.native)) {
            return null
        }
        val exactCommitted = snapshot.publication.committed?.takeIf {
            it.selection.token == latest.selection.token
        }
        // Relative/absolute media commands are allowed while the selected owner is still pending.
        // Bind the accepted position to that latest token so its first paused commit cannot snap
        // the external controller and UI back to the decoder's initial zero publication.
        optimisticSeek = OptimisticSeek(
            ownerToken = receipt.token.value,
            positionMs = receipt.positionMs,
            callbackSequenceAtRequest = exactCommitted?.transport?.positionCallbackSequence ?: 0L,
            seekRevision = receipt.seekRevision
        )
        endedOwnerToken = null
        positionPublications.discardPending()
        publishState(
            mutableState.value.copy(
                positionMs = receipt.positionMs,
                loopAvailable = false,
                ended = false,
                error = null
            )
        )
        saveListeningSession(immediate = true)
        if (!preservePreviousPair) previousPressTracker.reset()
        return SeekAdmission.Position(receipt)
    }

    internal fun beginScrub(controlLease: PlaybackControlLease): Boolean {
        if (playbackCommandsBlocked()) return false
        val current = adapter.snapshot()
        val committed = current.publication.committed ?: return false
        if (committed.selection.token != controlLease.native.token ||
            !adapter.commandLeaseIsCurrent(controlLease.native)
        ) return false
        val latestRequest = current.latestRequest ?: return false
        if (latestRequest.selection.token != committed.selection.token) {
            return false
        }
        if (scrubSnapshot != null) return false
        cancelPendingScrubPreview()
        lastScrubPreviewDispatchAtMs = null
        scrubSessionSerial = incrementRevision(scrubSessionSerial, "scrub session")
        val provisionalSnapshot = ScrubSnapshot(
            sessionId = scrubSessionSerial,
            controlLease = PlaybackControlLease(
                NativeAudioPlaybackCommandLease(
                    token = committed.selection.token,
                    songId = committed.selection.songId,
                    audioRevision = committed.selection.audioRevision
                )
            ),
            ownerOwnership = committed.owner.ownership,
            wasPlaying = latestRequest.controls.playWhenReady,
            wasEnded = state.value.ended ||
                endedOwnerToken == committed.selection.token.value,
            positionMs = state.value.positionMs,
            durationMs = committed.selection.playbackDurationMs
        )
        // Install token-wide gesture ownership before crossing into the adapter. A native end
        // delivered synchronously while ownership is acquired must not advance the queue.
        scrubSnapshot = provisionalSnapshot
        // A clock publication admitted just before ACTION_DOWN must not overwrite local preview.
        positionPublications.discardPending()
        // Claim every scrub, including a paused start, and replace potentially stale G1 ownership
        // with the exact G2 receipt observed under the adapter gate. Preserve the pre-gesture
        // transport intent so a playing song provides live audible preview as the cursor moves,
        // while a paused song never starts unexpectedly.
        val receipt = adapter.setPlayWhenReadyIfCurrent(
            provisionalSnapshot.ownerToken,
            provisionalSnapshot.wasPlaying
        )
        if (receipt == null) {
            // No hold was acquired, so do not retry through invalidateScrub(): a second attempt
            // could succeed after this one lost authority and leave an invisible paused owner.
            scrubSnapshot = null
            cancelPendingScrubPreview()
            return false
        }
        val snapshot = provisionalSnapshot.copy(ownerOwnership = receipt.ownership)
        scrubSnapshot = snapshot
        previousPressTracker.reset()
        return true
    }

    fun previewScrub(positionMs: Long) {
        val snapshot = scrubSnapshot ?: return
        if (!scrubOwnerStillCurrent(snapshot)) {
            invalidateScrub()
            return
        }
        val publication = ScrubPreviewPublication(
            sessionId = snapshot.sessionId,
            ownerToken = snapshot.ownerToken,
            songId = snapshot.songId,
            // Exact EOS is a queue/repeat command. Native preview stays inside playable PCM while
            // the UI may still show the finger at the exact endpoint until ACTION_UP.
            positionMs = nativeScrubPreviewTarget(positionMs, snapshot.durationMs)
        )
        if (scrubPreviewPublications.offer(publication)) {
            positionChoreographer.postFrameCallback(scrubPreviewFrameCallback)
        }
    }

    internal val activeScrubSessionId: Long? get() = scrubSnapshot?.sessionId

    internal fun cancelScrub(sessionId: Long) {
        if (scrubSnapshot?.sessionId == sessionId) cancelScrub()
    }

    fun cancelScrub() {
        val snapshot = scrubSnapshot ?: return
        suppressDelayedScrubEnd(snapshot)
        scrubSnapshot = null
        cancelPendingScrubPreview()
        if (!scrubOwnerStillCurrent(snapshot)) return
        if (snapshot.wasEnded) {
            restoreEndedScrub(snapshot)
            return
        }
        finishScrubOwner(snapshot, snapshot.positionMs, snapshot.wasPlaying)
    }

    fun commitScrub(positionMs: Long) {
        val snapshot = scrubSnapshot ?: return
        suppressDelayedScrubEnd(snapshot)
        scrubSnapshot = null
        cancelPendingScrubPreview()
        if (!scrubOwnerStillCurrent(snapshot)) return
        val target = playbackControlPositionMs(positionMs.toDouble(), snapshot.durationMs)
        // Exact EOS owns queue/repeat/Stop transport semantics. Restoring the pre-scrub transport
        // afterward would restart a terminal Repeat-Off owner or fight the successor handoff.
        if (target > 0L && target == snapshot.durationMs) {
            val receipt = adapter.setPlayWhenReadyIfCurrent(
                snapshot.ownerToken,
                playWhenReady = false
            ) ?: return
            suppressedScrubEnd = receipt.token to receipt.ownership
            seekToInternal(
                positionMs = target,
                terminalPlayWhenReady = snapshot.wasPlaying,
                controlLease = snapshot.controlLease,
                allowPendingTerminal = false
            )
            return
        }
        finishScrubOwner(snapshot, target, snapshot.wasPlaying)
    }

    /** One token-fenced terminal mutation prevents A's cursor or intent from ever reaching B. */
    private fun finishScrubOwner(
        snapshot: ScrubSnapshot,
        positionMs: Long,
        playWhenReady: Boolean
    ): Boolean {
        val target = playbackControlPositionMs(positionMs.toDouble(), snapshot.durationMs)
        val before = adapter.snapshot().publication.committed?.takeIf { committed ->
            committed.selection.token == snapshot.ownerToken
        } ?: return false
        val receipt = adapter.finishScrubWithReceipt(
            snapshot.ownerToken,
            before.selection.sourcePositionMs(target),
            playWhenReady
        ) ?: return false
        suppressedScrubEnd = receipt.token to receipt.ownership
        val callbackSequenceAtRequest = adapter.snapshot().publication.committed?.takeIf {
            it.selection.token == receipt.token && it.owner.ownership == receipt.ownership
        }?.transport?.positionCallbackSequence
            ?: before.transport.positionCallbackSequence
        latestTransportIntent = playWhenReady
        optimisticSeek = OptimisticSeek(
            ownerToken = snapshot.ownerToken.value,
            positionMs = target,
            callbackSequenceAtRequest = callbackSequenceAtRequest,
            seekRevision = checkNotNull(adapter.snapshot().latestRequest).controls.seekRevision
        )
        endedOwnerToken = null
        pendingTerminalSeek = null
        positionPublications.discardPending()
        publishState(
            mutableState.value.copy(
                positionMs = target,
                playing = projectedPlaybackIsPlaying(
                    playWhenReady, focusOwner.focusResumeProof != null, false, null,
                    mutableState.value.suppression
                ),
                playWhenReady = playWhenReady,
                loopAvailable = false,
                ended = false,
                error = null
            )
        )
        saveListeningSession(immediate = true)
        return true
    }

    private fun restoreEndedScrub(snapshot: ScrubSnapshot) {
        // Calling public seekTo(duration) would execute queue/repeat navigation. Restore the native
        // cursor just inside PCM and retain exact exclusive-EOS presentation for this same owner.
        val receipt = adapter.setPlayWhenReadyIfCurrent(
            snapshot.ownerToken,
            playWhenReady = false
        ) ?: return
        suppressedScrubEnd = receipt.token to receipt.ownership
        if (!adapter.previewSeekTo(
                snapshot.ownerToken,
                (adapter.snapshot().latestRequest?.selection ?: return).sourcePositionMs(
                    nativeScrubPreviewTarget(snapshot.durationMs, snapshot.durationMs))
            )
        ) return
        endedOwnerToken = snapshot.ownerToken.value
        optimisticSeek = null
        pendingTerminalSeek = null
        latestTransportIntent = false
        positionPublications.discardPending()
        abandonPlaybackAudioFocus()
        publishState(endedAtDurationState(mutableState.value, snapshot.durationMs))
    }

    private fun scrubOwnerStillCurrent(snapshot: ScrubSnapshot): Boolean =
        scrubOwnerMatches(
            scrubOwnerToken = snapshot.ownerToken.value,
            scrubSongId = snapshot.songId,
            latestOwnerToken = lastAdapterState.latestRequest?.selection?.token?.value,
            committedOwnerToken = lastAdapterState.publication.committed
                ?.selection?.token?.value,
            visibleSongId = state.value.songId
        )

    internal fun switchToOriginal(controlLease: PlaybackControlLease): Boolean {
        if (!switchToOriginalInternal(controlLease)) return false
        cancelListeningRestore()
        pendingSettingsRestore = pendingSettingsRestore?.copy(stems = false)
        publishState(projectState(adapter.snapshot(), ownerChanged = false))
        saveListeningSession(immediate = true)
        return true
    }

    private fun switchToOriginalInternal(controlLease: PlaybackControlLease): Boolean {
        if (playbackCommandsBlocked()) return false
        val snapshot = adapter.snapshot()
        val committed = snapshot.publication.committed ?: return false
        if (snapshot.latestRequest?.selection?.token != committed.selection.token) return false
        if (committed.selection.token != controlLease.native.token ||
            !adapter.commandLeaseIsCurrent(controlLease.native)
        ) return false
        if (committed.source.desiredMode == EngineSourceMode.ORIGINAL &&
            committed.source.appliedMode == EngineSourceMode.ORIGINAL
        ) return true
        if (!rewindEndedOwnerBeforeSourceSwitch(committed, controlLease)) return false
        if (!adapter.setSourceModeIfCurrent(controlLease.native, EngineSourceMode.ORIGINAL)) return false
        if (!adapter.commandLeaseIsCurrent(controlLease.native)) return false
        pendingSettingsRestore = pendingSettingsRestore?.takeIf { it.token == controlLease.native.token }
            ?.copy(stems = false)
        publishState(projectState(adapter.snapshot(), ownerChanged = false))
        return true
    }

    /** Rejects a delayed UI/repository result instead of reviving an outgoing song. */
    internal suspend fun activateStemsForCurrentSong(
        song: Song,
        stems: List<StemAssetEntity>,
        controlLease: PlaybackControlLease
    ): Result<Unit> {
        val requiredLease = controlLease
        if (playbackCommandsBlocked() || partsDeletionBlocks(song.id)) {
            return Result.failure(IllegalStateException("The Library session is changing"))
        }
        val expectedRevision = effectiveAudioRevision(song, song.sourcePath.orEmpty())
        run {
            val latest = adapter.snapshot().latestRequest?.selection
            if (requiredLease.songId != song.id ||
                latest?.token != requiredLease.native.token ||
                latest.audioRevision != expectedRevision ||
                latest.originalContentSha256 != song.sourceHash ||
                !adapter.commandLeaseIsCurrent(requiredLease.native)
            ) {
                return Result.success(Unit)
            }
        }
        val current = adapter.snapshot().publication.committed
        val committedIdentityMatches = current?.selection?.songId == song.id &&
            current.selection.audioRevision == expectedRevision &&
            current.selection.originalContentSha256 == song.sourceHash
        val committedLeaseMatches = current?.selection?.token == requiredLease.native.token
        if (!committedIdentityMatches || !committedLeaseMatches) {
            // The exact song is already the latest request; wait for its callback-committed
            // native owner without issuing a second selection or reviving an older song.
            val preparedSession = adapter.state.first { value ->
                value.lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE ||
                value.latestRequest?.selection?.token != requiredLease.native.token ||
                    value.publication.committed?.selection?.token == requiredLease.native.token ||
                    value.publication.pendingSelection?.issue != null
            }
            preparedSession.terminalFailure?.let { cause ->
                return Result.failure(IllegalStateException(cause))
            }
            if (preparedSession.lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) {
                return Result.success(Unit)
            }
            preparedSession.publication.pendingSelection?.issue?.cause?.let { cause ->
                return Result.failure(IllegalStateException(cause))
            }
            if (preparedSession.latestRequest?.selection?.token != requiredLease.native.token ||
                preparedSession.publication.committed?.selection?.token !=
                requiredLease.native.token
            ) {
                return Result.success(Unit)
            }
        }

        return withContext(Dispatchers.Main.immediate) {
            activateStems(stems, requiredLease)
        }
    }

    internal suspend fun activateStems(
        stems: List<StemAssetEntity>,
        controlLease: PlaybackControlLease
    ): Result<Unit> {
        if (playbackCommandsBlocked()) {
            return Result.failure(IllegalStateException("The Library session is changing"))
        }
        if (partsDeletionBlocks(controlLease.songId)) return Result.failure(IllegalStateException("Parts are being deleted"))
        val started = if (instrumentationRecorder.enabled) android.os.SystemClock.elapsedRealtime() else 0L
        val committed = adapter.snapshot().publication.committed
            ?: return Result.failure(IllegalStateException("Prepare the original audio first"))
        if (committed.selection.token != controlLease.native.token ||
            !adapter.commandLeaseIsCurrent(controlLease.native)
        ) {
            return Result.success(Unit)
        }
        val payload = metadataPublications.payloadFor(committed.metadata)
            ?: return Result.failure(IllegalStateException("Prepare the original audio first"))
        val verified = try {
            verifiedPlaybackStems(payload.song.id, stems)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Result.failure(failure)
        }
        if (!adapter.commandLeaseIsCurrent(controlLease.native) || partsDeletionBlocks(payload.song.id)) return Result.success(Unit)
        val candidate = runCatching { stemCandidate(payload.song, verified) }
            .getOrElse { return Result.failure(it) }
        if (!rewindEndedOwnerBeforeSourceSwitch(committed, controlLease)) {
            return Result.failure(
                IllegalStateException("The ended song could not rewind before Stems opened")
            )
        }
        val attachedPackage = committed.stems.desired?.let { desired ->
            desired.packageSignature == candidate.packageSignature &&
                desired.stemRevision == candidate.stemRevision
        } == true
        val installedPackage = committed.stems.installed?.let { installed ->
            installed.packageSignature == candidate.packageSignature &&
                installed.stemRevision == candidate.stemRevision
        } == true
        stemCandidates[candidate.packageSignature] = candidate
        trimStemCandidates()
        val accepted = adapter.configureStemPackageIfCurrent(
            lease = controlLease.native,
            stemRevision = candidate.stemRevision,
            lanes = candidate.lanes,
            selectStems = false
        )
        if (!accepted) {
            return Result.success(Unit)
        }
        if (!adapter.commandLeaseIsCurrent(controlLease.native)) {
            return Result.success(Unit)
        }
        if (!attachedPackage) restoreStemMix(payload.song, controlLease.native, candidate.lanes)
        if (!adapter.setSourceModeIfCurrent(controlLease.native, EngineSourceMode.STEMS)) return Result.success(Unit)
        val activationSourceRevision = adapter.snapshot().publication.committed
            ?.takeIf { it.selection.token == controlLease.native.token }
            ?.source
            ?.takeIf { it.desiredMode == EngineSourceMode.STEMS }
            ?.desiredRevision
            ?: return Result.failure(
                IllegalStateException("The Stems request was superseded before it could open")
            )
        // A hot or duplicate request can already be settled. Publish the adapter's exact
        // state instead of overwriting its completed callback with a late Preparing state.
        publishState(projectState(adapter.snapshot(), ownerChanged = false))
        when {
            !attachedPackage -> instrumentationRecorder.increment("foregroundStemGraphBuildCount")
            !installedPackage -> instrumentationRecorder.increment("currentStemPrewarmJoinCount")
            // Hot Original -> Stems reactivation reuses the already-installed graph and is not
            // a decoder build or a prewarm join.
        }
        val settlement = try {
            awaitStemActivationSettlement(
                controlLease.native,
                activationSourceRevision,
                candidate
            )
        } finally {
            if (instrumentationRecorder.enabled) instrumentationRecorder.remember(
                "lastStemActivationTotalMs", android.os.SystemClock.elapsedRealtime() - started)
        }
        return completeStemActivation(settlement) {
            // An explicitly failed package must never leave the public source transaction in
            // PreparingStems forever. Return to the still-audible Original owner using the exact
            // lease; a later tap receives a fresh source/package revision and can retry.
            if (
                stemActivationRequestStillCurrent(
                    controlLease.native,
                    activationSourceRevision,
                    candidate
                )
            ) {
                switchToOriginalInternal(controlLease)
            }
        }
    }

    private suspend fun awaitStemActivationSettlement(
        lease: NativeAudioPlaybackCommandLease,
        sourceRevision: Long,
        candidate: StemPackageCandidate
    ): StemActivationSettlement = awaitStemActivationTerminalSettlement(
        adapter.state.map { snapshot ->
            stemActivationSettlement(
                snapshot, lease, sourceRevision, candidate.packageSignature, candidate.stemRevision
            )
        }
    )

    private fun stemActivationRequestStillCurrent(
        lease: NativeAudioPlaybackCommandLease,
        sourceRevision: Long,
        candidate: StemPackageCandidate
    ): Boolean {
        if (!adapter.commandLeaseIsCurrent(lease)) return false
        val committed = adapter.snapshot().publication.committed
            ?.takeIf { it.selection.token == lease.token }
            ?: return false
        val desired = committed.stems.desired ?: return false
        return committed.source.desiredMode == EngineSourceMode.STEMS &&
            committed.source.desiredRevision == sourceRevision &&
            desired.packageSignature == candidate.packageSignature &&
            desired.stemRevision == candidate.stemRevision
    }

    /**
     * A source change replaces the render graph but retains the same logical owner token. EOS is
     * also token-bound, so leaving that marker installed would project every callback from the new
     * graph at duration and immediately mark it ended. Rewind through the normal exact-token seek
     * path first; it preserves the paused play intent while clearing terminal and optimistic state.
     */
    private fun rewindEndedOwnerBeforeSourceSwitch(
        committed: PlaybackCommittedPublication,
        controlLease: PlaybackControlLease
    ): Boolean {
        val token = committed.selection.token.value
        val visible = mutableState.value
        if (!sourceSwitchRequiresRewind(
                ended = endedOwnerToken == token || visible.ended
            )
        ) return true
        seekToInternal(
            positionMs = 0L,
            terminalPlayWhenReady = null,
            controlLease = controlLease,
            allowPendingTerminal = false
        )
        return endedOwnerToken != token && !mutableState.value.ended
    }

    internal fun pausedPreparedStemMixLeaseIsCurrent(lease: PausedPreparedStemMixLease): Boolean =
        !playbackCommandsBlocked() && adapter.pausedPreparedStemMixLeaseIsCurrent(lease)

    internal fun commitPausedPreparedStemMix(lease: PausedPreparedStemMixLease,
        stemId: String, volume: Float, muted: Boolean, solo: Boolean): Boolean {
        if (playbackCommandsBlocked() || Looper.myLooper() != Looper.getMainLooper() ||
            !volume.isFinite() || stemId !in lease.laneIds) return false
        val requested = adapter.snapshot().latestRequest?.controls?.stemControls ?: return false
        if (requested.keys != lease.laneIds) return false
        val updated = updatedStemMixState(requested.mapValues { it.value.toStemMixState() }, stemId, volume, muted, solo)
        val settingsOwner = settingsOwner(lease.command)
        val byRole = stemMixByRole(lease.command, updated)
        if (!adapter.commitPausedPreparedStemMix(lease, updated.mapValues { it.value.toEngineControl() })) return false
        if (byRole != null) persistSettings(settingsOwner) { it.copy(stemMix = byRole) }
        val value = mutableState.value
        if (value.songId == lease.command.songId && value.loadedStemSignature == lease.packageSignature) {
            publishState(value.copy(stemMix = updated))
        }
        return true
    }

    /** Full-player DOWN boundary for primitive frame previews. */
    internal fun beginStemMixPreviewBatchLane(
        laneIndex: Int,
        controlLease: PlaybackControlLease
    ): EngineStemMixPreviewLease? {
        if (playbackCommandsBlocked() || Looper.myLooper() != Looper.getMainLooper() ||
            laneIndex !in frameBoundedStemMixPreviewLeases.indices) return null
        val lease = adapter.beginFrameBoundedStemMixPreviewIfCurrent(controlLease.native, laneIndex) ?: return null
        frameBoundedStemMixPreviewLeases[laneIndex]?.let { adapter.endFrameBoundedStemMixPreview(it) }
        frameBoundedStemMixPreviewLeases[laneIndex] = lease
        return lease
    }

    /** Full-player display boundary: primitive values and atomics, with no map or main lock. */
    internal fun offerStemMixPreviewBatchFrameBounded(
        lease: EngineStemMixPreviewLease,
        changedMask: Int,
        gains: FloatArray
    ): Boolean {
        if (playbackCommandsBlocked() || Looper.myLooper() != Looper.getMainLooper() ||
            frameBoundedStemMixPreviewLeases[lease.laneIndex] !== lease) return false
        return adapter.offerFrameBoundedStemMixPreviewBatch(lease, changedMask, gains)
    }

    /** Full-player UP/CANCEL can retire only the exact lease returned to its DOWN. */
    internal fun endStemMixPreviewBatchLane(lease: EngineStemMixPreviewLease): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() ||
            frameBoundedStemMixPreviewLeases[lease.laneIndex] !== lease) return false
        frameBoundedStemMixPreviewLeases[lease.laneIndex] = null
        return adapter.endFrameBoundedStemMixPreview(lease)
    }

    /** Exact ACTION_UP/accessibility/mute/solo boundary; the final value is never conflated away. */
    internal fun commitStemMix(
        stemId: String,
        volume: Float,
        muted: Boolean,
        solo: Boolean,
        controlLease: PlaybackControlLease
    ): Boolean {
        if (playbackCommandsBlocked() || Looper.myLooper() != Looper.getMainLooper()) return false
        if (!volume.isFinite()) return false
        val owner = currentStemMixPreviewOwner() ?: run {
            invalidateStemMixPreview()
            return false
        }
        if (owner.token != controlLease.native.token ||
            owner.songId != controlLease.songId ||
            !adapter.commandLeaseIsCurrent(controlLease.native)
        ) return false
        if (stemId !in owner.laneIds) return false
        val base = mutableState.value.stemMix
        if (stemId !in base || base.keys != owner.laneIds) return false
        val updated = updatedStemMixState(base, stemId, volume, muted, solo)
        return commitStemMixPublication(StemMixPreviewPublication(owner, updated))
    }

    internal fun resetStemMix(controlLease: PlaybackControlLease): Boolean {
        if (playbackCommandsBlocked() || Looper.myLooper() != Looper.getMainLooper()) return false
        val prepared = controlLease.pausedPreparedStemMix
        if (prepared != null) {
            val controls = prepared.laneIds.associateWith { EngineStemControl() }
            val settingsOwner = settingsOwner(controlLease.native)
            val byRole = stemMixByRole(controlLease.native, prepared.laneIds.associateWith { StemMixState() })
            if (!adapter.commitPausedPreparedStemMix(prepared, controls)) return false
            if (byRole != null) persistSettings(settingsOwner) { it.copy(stemMix = byRole) }
            val value = mutableState.value
            if (value.songId == controlLease.songId && value.loadedStemSignature == prepared.packageSignature) {
                publishState(value.copy(stemMix = prepared.laneIds.associateWith { StemMixState() }))
            }
            return true
        }
        val owner = currentStemMixPreviewOwner() ?: return false
        if (owner.controlLease != controlLease.native || owner.songId != controlLease.songId) return false
        return commitStemMixPublication(StemMixPreviewPublication(owner,
            owner.laneIds.associateWith { StemMixState() }))
    }

    internal fun setTempoAndPitch(
        tempo: Float,
        semitones: Int,
        controlLease: PlaybackControlLease,
        persist: Boolean = true
    ): Boolean {
        if (playbackCommandsBlocked()) return false
        val normalizedTempo = tempo
            .takeIf(Float::isFinite)
            ?.coerceIn(MINIMUM_TEMPO, MAXIMUM_TEMPO)
            ?: 1f
        val normalizedKey = semitones.coerceIn(-12, 12)
        val settingsOwner = if (persist) settingsOwner(controlLease.native) else null
        if (!adapter.setTempoAndKeyIfCurrent(
                controlLease.native,
                normalizedTempo,
                normalizedKey
            )
        ) return false
        if (persist) persistSettings(settingsOwner) { it.copy(tempo = normalizedTempo, semitones = normalizedKey) }
        if (!adapter.commandLeaseIsCurrent(controlLease.native)) return false
        publishState(
            mutableState.value.copy(
                tempo = normalizedTempo,
                semitoneShift = normalizedKey
            )
        )
        return true
    }

    internal fun commitTuningSettings(controlLease: PlaybackControlLease?): Boolean {
        val lease = controlLease ?: return false
        if (playbackCommandsBlocked()) return false
        val request = adapter.snapshot().latestRequest?.takeIf { it.selection.token == lease.native.token } ?: return false
        val owner = settingsOwner(lease.native, request)
        val tempo = request.controls.tempo
        val semitones = request.controls.semitoneShift
        if (!adapter.commandLeaseIsCurrent(lease.native)) return false
        persistSettings(owner) { it.copy(tempo = tempo, semitones = semitones) }
        return true
    }

    internal fun setLoop(
        pointA: Long?,
        pointB: Long?,
        controlLease: PlaybackControlLease
    ): Boolean = applyLoop(pointA, pointB, controlLease = controlLease)

    internal fun retryLoopPreparation(controlLease: PlaybackControlLease, preparation: LoopPreparationState): Boolean {
        if (playbackCommandsBlocked()) return false
        return adapter.retryLoopPreparation(controlLease.native, preparation)
    }

    /** A/B movement has one state: an accepted physical sample is the real engine/UI/saved loop. */
    internal fun setLoop(
        pointA: Long?,
        pointB: Long?,
        controlLease: PlaybackControlLease,
        transportPositionMs: Long,
        publishPresentation: Boolean = true,
        inputEventAtMonotonicMicros: Long = 0L,
        heldBGestureId: Long = 0L,
        capturedBMs: Long = -1L
    ): Boolean = applyLoop(
        pointA = pointA,
        pointB = pointB,
        controlLease = controlLease,
        transportPositionMs = transportPositionMs.coerceAtLeast(0L),
        publishPresentation = publishPresentation,
        inputEventAtMonotonicMicros = inputEventAtMonotonicMicros,
        heldBGestureId = heldBGestureId,
        capturedBMs = capturedBMs
    )

    /** Flushes the already-audible endpoint value to session/UI without another native policy. */
    internal fun publishLoopPresentation(
        pointA: Long?,
        pointB: Long?,
        controlLease: PlaybackControlLease
    ): Boolean {
        if (playbackCommandsBlocked()) return false
        val session = adapter.snapshot()
        val requested = session.latestRequest?.takeIf {
            it.selection.token == controlLease.native.token
        } ?: return false
        val draft = normalizedLoopDraft(
            pointA,
            pointB,
            requested.selection.playbackDurationMs
        )
        val range = validatedLoopRange(
            draft.pointA,
            draft.pointB,
            requested.selection.playbackDurationMs
        )
        val loop = range?.let {
            EngineLoopConfig(requested.selection.sourcePositionMs(it.startMs),
                requested.selection.sourcePositionMs(it.endMs), parkUntilRangeEntry = true)
        }
        val settingsOwner = settingsOwner(controlLease.native, requested)
        if (!adapter.publishExplicitLoopPresentationIfCurrent(controlLease.native, loop)) return false
        persistLoop(settingsOwner, requested.selection, draft.pointA, range?.endMs)
        if (!adapter.commandLeaseIsCurrent(controlLease.native)) return false
        publishState(mutableState.value.copy(loopA = draft.pointA, loopB = range?.endMs))
        return true
    }

    fun setRepeatMode(mode: String): Boolean {
        if (playbackCommandsBlocked()) return false
        if (restoringListeningSession == null) cancelListeningRestore()
        queueController.setRepeatMode(RepeatMode.fromLabel(mode))
        publishQueueState()
        updateLikelyNext()
        return true
    }

    internal fun setMetronomeVolume(
        volume: Float,
        controlLease: PlaybackControlLease
    ): Boolean {
        if (playbackCommandsBlocked()) return false
        val normalized = normalizedMetronomeVolumeOrNull(volume) ?: return false
        val settingsOwner = settingsOwner(controlLease.native)
        val metronome = metronomeSettings(controlLease.native)
        if (!adapter.setMetronomeVolumeIfCurrent(controlLease.native, normalized)) return false
        if (metronome != null) persistSettings(settingsOwner) {
            it.copy(metronome = (it.metronome ?: metronome).copy(volume = normalized))
        }
        if (!adapter.commandLeaseIsCurrent(controlLease.native)) return false
        publishState(mutableState.value.copy(metronomeVolume = normalized))
        return true
    }

    internal fun setMetronome(
        enabled: Boolean,
        bpm: Float,
        songSynced: Boolean,
        beatEvents: List<BeatEvent> = emptyList(),
        meterSegments: List<MeterSegment> = emptyList(),
        subdivision: Int = 1,
        numerator: Int = 4,
        denominator: Int = 4,
        controlLease: PlaybackControlLease,
        persist: Boolean = true
    ): Boolean {
        if (playbackCommandsBlocked()) return false
        val normalizedBeatEvents = beatEvents
            .filter { it.timeMs >= 0L }
            .distinctBy(BeatEvent::timeMs)
            .sortedBy(BeatEvent::timeMs)
        val normalizedMeterSegments = meterSegments
            .filter { it.startMs >= 0L }
            .distinctBy(MeterSegment::startMs)
            .sortedBy(MeterSegment::startMs)
        val normalizedBpm = normalizedMetronomeBpmOrNull(bpm) ?: return false
        val normalizedSubdivision = normalizedMetronomeSubdivision(subdivision)
        val normalizedNumerator = numerator.coerceIn(1, 32)
        val normalizedDenominator = normalizedMetronomeBeatUnit(denominator)
        val config = EngineMetronomeConfig(
            enabled = enabled,
            songSynced = songSynced,
            bpm = normalizedBpm,
            // The adapter preserves this owner's current volume atomically with the profile write.
            volume = 1f,
            subdivision = normalizedSubdivision,
            numerator = normalizedNumerator,
            denominator = normalizedDenominator,
            beatGridMs = normalizedBeatEvents.map(BeatEvent::timeMs).toLongArray(),
            downbeats = normalizedBeatEvents.map(BeatEvent::isDownbeat).toBooleanArray(),
            meterSegments = projectEngineMeterSegments(normalizedMeterSegments)
        )
        val settingsOwner = if (persist) settingsOwner(controlLease.native) else null
        val volume = if (persist) metronomeSettings(controlLease.native)?.volume ?: 1f else 1f
        if (!adapter.setMetronomeProfileIfCurrent(controlLease.native, config)) return false
        if (persist) persistSettings(settingsOwner) {
            it.copy(metronome = SongMetronomeSettings(enabled, it.metronome?.volume ?: volume,
                normalizedSubdivision, normalizedNumerator, normalizedDenominator))
        }
        if (!adapter.commandLeaseIsCurrent(controlLease.native)) return false
        publishState(
            mutableState.value.copy(
                metronomeEnabled = enabled,
                metronomeSongSynced = songSynced,
                metronomeBpm = normalizedBpm,
                metronomeSubdivision = normalizedSubdivision,
                metronomeNumerator = normalizedNumerator,
                metronomeDenominator = normalizedDenominator
            )
        )
        return true
    }

    fun release() {
        if (released) return
        saveListeningSession(immediate = true)
        cancelListeningRestore()
        released = true
        cancelResolvingSelection()
        pendingOwnerlessRebind = null
        selectionArtworkTargetListener = null
        invalidateStemMixPreview()
        invalidateScrub()
        pendingTerminalSeek = null
        positionChoreographer.removeFrameCallback(positionPublicationFrameCallback)
        mainHandler.removeCallbacks(positionPublicationFallbackRunnable)
        sessionArtwork.close()
        abandonPlaybackAudioFocus()
        platform.close()
        adapter.close()
        mutableControlLease.value = null
        scope.cancel()
    }

    /** Service-instance lifecycle acknowledgement for the one-start-in-flight gate. */
    internal fun onPlaybackServiceCreated() = platform.serviceCreated()

    internal fun onPlaybackServiceDestroyed() {
        when (platform.serviceDestroyed(!playbackCommandsBlocked() && adapter.snapshot().latestRequest != null)) {
            PlaybackServiceDestructionAction.RESTART -> ensurePlaybackService()
            PlaybackServiceDestructionAction.PAUSE -> pause()
            PlaybackServiceDestructionAction.NONE -> Unit
        }
    }

    /** Instrumentation access to the one service-owned player registered with MediaSession. */
    internal fun media3Player(): Player = checkNotNull(PlaybackService.stablePlayerForTest()) {
        "PlaybackService is not ready"
    }

    internal fun graphMediaId(): String? =
        lastAdapterState.publication.committed?.takeUnless { hideCommittedPublication }
            ?.selection?.songId

    internal fun transportPositionMs(): Long = mutableState.value.positionMs

    internal fun primaryAudibleOwnershipEpochForTest(): Long =
        lastAdapterState.publication.committed?.owner?.ownership?.generation ?: 0L

    internal fun queueTransitionTargetSummaryForTest(): String? =
        lastAdapterState.publication.pendingSelection?.let { pending ->
            "${pending.selection.songId}/native-pending/${pending.transport.playWhenReady}/" +
                "${pending.expectedOwnership.generation}/${pending.transport.requestedPositionMs}ms"
        }

    internal fun preparedQueueMediaIds(): Set<String> =
        lastAdapterState.likelyNextIdentity?.songId?.let(::setOf).orEmpty()

    /** Main-thread instrumentation seam proving likely-next artwork is resident before promotion. */
    internal fun hasCachedSessionArtworkForTest(songId: String): Boolean {
        val song = queueById[songId] ?: return false
        val key = sessionArtworkKey(song) ?: return false
        return sessionArtwork.cached(key) != null
    }

    internal fun playbackResourceDiagnosticsForTest(): PlaybackResourceDiagnostics {
        val native = adapter.diagnosticsSnapshot()
        val leases = native?.decoderLeases
        val runtime = native?.runtime
        val output = native?.output
        val decoderWorkers = adapter.decoderWorkerDiagnostics()
        val lifecycleEvidenceAvailable = runtime != null && output != null &&
            runtime.processConstructionCount > 0L && runtime.processLiveInstanceCount > 0L &&
            runtime.processConstructionCount >= runtime.processLiveInstanceCount &&
            output.processConstructionCount > 0L && output.processLiveInstanceCount > 0L &&
            output.processConstructionCount >= output.processLiveInstanceCount
        return PlaybackResourceDiagnostics(
            queueSongCount = queueById.size,
            evidenceAvailable = lifecycleEvidenceAvailable && leases != null &&
                runtime.banks.size == 2,
            nativeRuntimeConstructionCount = runtime?.processConstructionCount ?: 0L,
            nativeRuntimeCount = runtime?.processLiveInstanceCount.asResourceCount(),
            nativeOutputConstructionCount = output?.processConstructionCount ?: 0L,
            nativeOutputStreamCount = output?.processLiveInstanceCount.asResourceCount(),
            assignedNativeBankCount = runtime?.banks?.count { it.assigned } ?: 0,
            activeDecoderLeaseCount = leases?.activeDecoderCount ?: 0,
            candidateDecoderLeaseCount = leases?.candidateDecoderCount ?: 0,
            loopPreparationDecoderLeaseCount = leases?.loopPreparationDecoderCount ?: 0,
            totalDecoderLeaseCount = leases?.totalDecoderCount ?: 0,
            decoderWorkerRunningCount = decoderWorkers.runningTaskCount,
            decoderWorkerQueuedCount = decoderWorkers.queuedTaskCount,
            cumulativeBankBeginSongCount = runtime?.banks?.fold(0L) { total, bank ->
                total + bank.beginSongCount
            } ?: 0L
        )
    }

    internal fun selectionPressureDiagnosticsForTest(): SelectionPressureDiagnostics {
        // The UI collector is intentionally frame-conflated. Pressure qualification needs the
        // adapter's synchronous latest-token view immediately after each main-thread request.
        val adapterState = adapter.snapshot()
        val committed = adapterState.publication.committed?.selection
        val pending = adapterState.publication.pendingSelection?.selection
        val latest = adapterState.latestRequest?.selection
        return SelectionPressureDiagnostics(
            requestSerial = requestSerial,
            resolvingRequestSerial = resolvingRequestSerial,
            queueCurrentId = queueController.snapshot().currentId,
            latestRequestedSongId = latest?.songId,
            latestRequestedToken = latest?.token?.value,
            committedSongId = committed?.songId,
            committedToken = committed?.token?.value,
            pendingSongId = pending?.songId,
            pendingToken = pending?.token?.value
        )
    }

    internal fun latestRequestedControlsForTest(): NativeAudioPlaybackControlSnapshot? =
        adapter.snapshot().latestRequest?.controls

    /**
     * Stable command-owner key for stateful MediaSession policies such as relative-seek
     * accumulation. Public [AudioPlaybackState.songId] intentionally remains callback-committed,
     * so it is null during every manual handoff and cannot distinguish pending B from pending C.
     */
    internal fun sessionTransportOwnerKey(): String? {
        if (hideCommittedPublication) return null
        val snapshot = adapter.snapshot()
        val selection = snapshot.latestRequest?.selection
            ?: snapshot.publication.committed?.selection
            ?: return null
        return "${selection.token.value}\u0000${selection.songId}"
    }


    internal fun stemPreparationDiagnostics(): StemPreparationDiagnostics =
        StemPreparationDiagnostics(
            currentPrewarmBuildCount = instrumentationRecorder.count("currentStemPrewarmBuildCount"),
            foregroundBuildCount = instrumentationRecorder.count("foregroundStemGraphBuildCount"),
            joinedPrewarmCount = instrumentationRecorder.count("currentStemPrewarmJoinCount"),
            lastActivationTotalMs = instrumentationRecorder.observation("lastStemActivationTotalMs") as? Long ?: 0L
        )

    @Synchronized
    internal fun sessionMediaItem(): MediaItem? {
        if (hideCommittedPublication) {
            sessionMediaCache.clear()
            return null
        }
        val snapshot = adapter.snapshot()
        val committed = snapshot.publication.committed
        val pending = snapshot.latestRequest?.takeIf { requested ->
            requested.selection.token != committed?.selection?.token
        }
        val selection = pending?.selection ?: committed?.selection
        val metadata = pending?.metadata ?: committed?.metadata
        if (selection == null || metadata == null) {
            sessionMediaCache.clear()
            return null
        }
        val key = SessionMediaIdentity(
            selection.token.value,
            metadata.revision
        )
        val payload = metadataPublications.payloadFor(metadata)
        if (payload == null) {
            sessionMediaCache.clear()
            return null
        }
        return sessionMediaCache.getOrCreate(key) {
            metadataPublications.mediaItem(payload)
        }
    }

    internal fun nativeDiagnosticsSnapshotForTest(): NativeEngineDiagnosticsSnapshot? =
        adapter.diagnosticsSnapshot()

    internal fun playbackAudioFocusOwnedForTest(): Boolean =
        focusOwner.audioFocusRequested || focusOwner.audioFocusHeld

    internal fun librarySessionCommandsAllowedForTest(): Boolean =
        librarySessionCommandsAllowed

    internal fun presentationJournalForTest(
        afterSequence: Long
    ): NativeAudioPresentationJournalSnapshot = adapter.presentationJournal(afterSequence)

    /**
     * Compact timeout evidence for end-to-end queue/media-key qualification.
     *
     * Keep this projection in production code instead of teaching instrumentation about actor
     * internals: every value is copied from the same immutable publications used by the UI and
     * Media3 facade, plus the controller's read-only native diagnostics snapshot.
     */
    internal fun mediaSessionLifecycleDiagnosticsForTest(): String {
        val adapterState = lastAdapterState
        val publication = adapterState.publication
        val diagnostics = adapter.diagnosticsSnapshot()
        val runtime = diagnostics?.runtime
        val ownership = runtime?.ownership
        val automaticSuccessor = runtime?.automaticSuccessor
        val output = diagnostics?.output
        fun selectionLabel(songId: String?, token: Long?): String =
            "${songId ?: "none"}/${token ?: 0L}"

        return buildString {
            append("queueCurrent=")
            append(queueController.snapshot().currentId ?: "none")
            append(", adapterLatest=")
            append(
                selectionLabel(
                    adapterState.latestRequest?.selection?.songId,
                    adapterState.latestRequest?.selection?.token?.value
                )
            )
            append(", adapterCommitted=")
            append(
                selectionLabel(
                    publication.committed?.selection?.songId,
                    publication.committed?.selection?.token?.value
                )
            )
            append(", adapterPending=")
            append(
                selectionLabel(
                    publication.pendingSelection?.selection?.songId,
                    publication.pendingSelection?.selection?.token?.value
                )
            )
            append(", loopAvailability(observed/current)=")
            append(adapterState.loopAvailable)
            append('/')
            append(adapter.snapshot().loopAvailable)
            append(", adapterOwner(requested/committed)=")
            append(adapterState.latestRequest?.exactOwnership)
            append('/')
            append(publication.committed?.owner?.ownership)
            append(", nativeOwnership(requested/accepted/committed)=")
            append(ownership?.requested)
            append('/')
            append(ownership?.accepted)
            append('/')
            append(ownership?.committed)
            append(", automaticSuccessor=")
            append(automaticSuccessor?.phase)
            append('/')
            append(automaticSuccessor?.cancellationStatus)
            append("/cancelDesired=")
            append(automaticSuccessor?.cancellationDesired)
            append("/out=")
            append(automaticSuccessor?.outgoingOwnership)
            append("/next=")
            append(automaticSuccessor?.successorOwnership)
            append(", output=")
            append(output?.streamState)
            append("/playing=")
            append(output?.playing)
            append("/queued=")
            append(output?.queuedFrames)
            append("/appliedGeneration=")
            append(output?.appliedGeneration)
            append("/presentedGeneration=")
            append(output?.lastPresentedGeneration)
            append("/acceptedFrames=")
            append(output?.totalFramesAccepted)
            append(", events=")
            append(playbackEvents.snapshot().joinToString(" | "))
        }
    }

    /** Actual one-runtime counters/callback evidence plus the facade's requested control snapshot. */
    internal fun graphDiagnostics(): NativePlaybackDiagnostics {
        val committed = lastAdapterState.publication.committed
            ?: return NativePlaybackDiagnostics(0L, 0L, 0L)
        val controls = committedControls()
        val gains = controls?.stemControls?.let(::effectiveStemGains).orEmpty()
        val native = adapter.diagnosticsSnapshot()
        val runtime = native?.runtime
        val output = native?.output
        val counters = runtime?.counters
        val presented = runtime?.presentedPosition
        val fingerprintSequence = counters?.lastOutputFingerprintSequence ?: 0L
        val fingerprintFrames = (runtime?.audibleControl?.pcmFrames
            ?: presented?.pcmFrames
            ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val committedOwnership = runtime?.ownership?.committed
        val requestedOwnership = runtime?.ownership?.requested
        val levelMatchesCommittedOutput = output != null && committedOwnership != null &&
            output.pcmLevelSequence > 0L &&
            output.pcmLevelFrames > 0L &&
            output.pcmLevelGeneration == committedOwnership.generation &&
            output.pcmLevelSelectionToken == committedOwnership.selectionToken &&
            output.lastPresentedGeneration == output.pcmLevelGeneration &&
            output.lastPresentedSelectionToken == output.pcmLevelSelectionToken &&
            output.lastPresentedFingerprintSequence == output.pcmLevelFingerprintSequence &&
            output.pcmLevelCallbackSequence >= (runtime?.audibleControl?.callbackSequence ?: Long.MAX_VALUE)
        val latestFingerprint = if (fingerprintSequence > 0L && fingerprintFrames > 0) {
            PcmOutputFingerprint(
                sequence = fingerprintSequence,
                frames = fingerprintFrames,
                sampleRateHz = NATIVE_ENGINE_SAMPLE_RATE_HZ,
                // The runtime exposes a pipeline hash, not fabricated amplitude/frequency values.
                audibleOwnershipEpoch = committedOwnership?.generation ?: 0L
            )
        } else {
            null
        }
        val appliedPublication = callbackControlPublication(committedOwnership,
            runtime?.audibleControl, native?.controllerSourceControlPublications.orEmpty())
        val appliedTuning = callbackTuningEvidence(committedOwnership, runtime?.audibleControl)
        val appliedControls = appliedPublication?.nativeControls
        val appliedGains = appliedControls?.let { applied ->
            val lanes = requireNotNull(appliedPublication).stemLaneIds
            val anySolo = applied.stems.take(lanes.size).any { it.soloed }
            lanes.mapIndexed { index, id ->
                val lane = applied.stems[index]
                id to if (lane.muted || (anySolo && !lane.soloed)) 0f else lane.gain
            }.toMap()
        }.orEmpty()
        return NativePlaybackDiagnostics(
            renderedFrames = counters?.outputFramesAccepted ?: 0L,
            discontinuities = counters?.outputFlushes ?: 0L,
            invalidSamples = counters?.outputInvalidRenderResults ?: 0L,
            requestedTransportPositionMs = committed.transport.requestedPositionMs,
            requestedLoopStartMs = controls?.loop?.startMs,
            requestedLoopEndMs = controls?.loop?.endMs,
            requestedTempo = controls?.tempo ?: mutableState.value.tempo,
            requestedSemitoneShift = controls?.semitoneShift ?: mutableState.value.semitoneShift,
            appliedTempo = appliedTuning?.actualTempo,
            appliedPitchRatio = appliedTuning?.actualPitchSemitones?.let { 2.0.pow(it / 12.0).toFloat() },
            appliedControlRevision = appliedPublication?.nativeControlRevision ?: 0L,
            appliedStemGains = appliedGains,
            stemControlRevision = controls?.revision ?: 0L,
            requestedStemGains = gains,
            outputFingerprintSequence = fingerprintSequence,
            outputFingerprintFrames = fingerprintFrames,
            outputSampleRateHz = if (fingerprintSequence > 0L) {
                NATIVE_ENGINE_SAMPLE_RATE_HZ
            } else {
                0
            },
            outputRms = if (levelMatchesCommittedOutput) {
                sqrt(output.pcmLevelMeanSquare.toDouble()).toFloat()
            } else {
                0f
            },
            outputPeak = if (levelMatchesCommittedOutput) output.pcmLevelPeak else 0f,
            requestedAudibleOwnershipEpoch = requestedOwnership?.generation
                ?: committed.owner.ownership.generation,
            appliedAudibleOwnershipEpoch = committedOwnership?.generation ?: 0L,
            audibleOwnershipMarkerSequence = runtime?.presentation?.fingerprintSequence ?: 0L,
            latestAudibleOwnershipFingerprint = latestFingerprint,
            renderPayloadHash = counters?.lastRenderPayloadHash ?: 0L
        )
    }

    private fun selectResolvedSong(
        song: Song,
        location: String,
        autoplay: Boolean,
        positionMs: Long,
        forceReload: Boolean,
        serial: Long,
        restartAtZero: Boolean = false,
        preservePreviousPair: Boolean = false
    ): Boolean {
        if (serial != requestSerial || playbackCommandsBlocked()) return false
        val duration = song.durationMs.takeIf { it > 0L } ?: run {
            adapter.completeManualSelectionIntentWithoutReplacement()
            restoreSelectionArtworkTarget()
            return false
        }
        val sourceHash = requireNotNull(song.sourceHash) { "Verified Original SHA-256 is missing" }
        require(sourceHash.matches(Regex("[a-fA-F0-9]{64}"))) { "Verified Original SHA-256 is invalid" }
        val settings = settingsIdentity(song)?.let { identity ->
            checkNotNull(cachedSettings(identity)) { "Saved song settings have not been read" }.getOrThrow()
        } ?: SongPlaybackSettings()
        val trimPreview = songTrimPreview?.takeIf { it.lease.requestSerial == serial && it.song.id == song.id }
        val trim = settings.trim.takeUnless { trimPreview != null }
        val trimStart = trim?.startMs ?: 0L
        val trimEnd = trim?.endMs ?: duration
        val listeningRestore = restoringListeningSession?.takeIf { it.first == serial }?.second
        val revision = effectiveAudioRevision(song, location)
        val normalizedSong = song.copy(
            sourcePath = location,
            audioRevision = revision
        )
        val metadata = publicationMetadata(normalizedSong, location, revision)
        // Commands consult the adapter synchronously. The main-thread collector is intentionally
        // frame-conflated and may still expose the previous request during a rapid A -> B -> A tap.
        val latest = adapter.snapshot().latestRequest?.selection
        val sameAudio = sameAudioSelection(latest, song.id, revision, location, duration, song.sourceHash) &&
            latest?.playbackStartMs == trimStart && latest?.playbackEndMs == trimEnd
        val playablePosition = listeningRestore?.sourcePositionMs?.let {
            (it - trimStart).coerceIn(0L, (trimEnd - trimStart - 1L).coerceAtLeast(0L))
        } ?: playableHandoffPosition(positionMs, trimEnd - trimStart)

        // Source resolution is the ownership boundary. Until this point the outgoing queue owner,
        // loop, Stems controls and scrub remain intact, so an unavailable destination cannot flash
        // new artwork or destructively reset the song that is still audible.
        pendingOwnerlessRebind = null
        invalidateScrub()
        pendingTerminalSeek = null
        hideCommittedPublication = false
        endedOwnerToken = null

        if (adapter.snapshot().lifecycle == NativeAudioPlaybackSessionLifecycle.STOPPING) {
            pendingOwnerlessRebind = PendingOwnerlessRebind(
                song = normalizedSong,
                positionMs = playablePosition,
                autoplay = autoplay,
                requestSerial = serial,
                restartAtZero = restartAtZero,
                preservePreviousPair = preservePreviousPair
            )
            return true
        }

        ensurePlaybackService()
        if (sameAudio && !forceReload) {
            adapter.completeManualSelectionIntentWithoutReplacement()
            commitResolvedQueueSelection(normalizedSong)
            adapter.updateMetadata(metadata)
            if ((positionMs > 0L || restartAtZero) && abs(playablePosition - state.value.positionMs) > 1L) {
                seekToForSession(playablePosition, preservePreviousPair)
            }
            if (autoplay && focusOwner.awaitingFocusGain) {
                adapter.play()
                focusOwner.resumeAfterAudioFocusGainToken = latest?.token?.value
            } else if (autoplay) playInternal(preservePreviousPair) else pauseInternal(preservePreviousPair)
            updateLikelyNext()
            return true
        }

        val effectiveAutoplay = when (selectionFocusAction(autoplay)) {
            SelectionFocusAction.REQUEST_FOR_AUTOPLAY -> focusOwner.awaitingFocusGain || requestPlaybackAudioFocus()
            SelectionFocusAction.RETAIN_THROUGH_PENDING -> false
        }
        val retained = trimReloadControls?.takeIf { it.requestSerial == serial }
        val loop = settings.loop.takeIf { trimPreview == null &&
            (it.pointA == null || it.pointA in trimStart..trimEnd) &&
                (it.pointB == null || it.pointB in trimStart..trimEnd)
        } ?: SongLoopPreference()
        val retainedStems = retained?.controls?.sourceMode == EngineSourceMode.STEMS ||
            listeningRestore?.sourceMode == PlaybackSourceMode.STEMS
        val restoreBeforePlay = loop.pointA != null || retainedStems
        val metronome = retained?.controls?.metronome?.let {
            EngineMetronomeConfig(it.enabled, it.songSynced, it.bpm, it.volume, it.subdivision,
                it.numerator, it.denominator, it.beatGridMs.toLongArray(), it.downbeats.toBooleanArray(),
                it.meterSegments)
        } ?: settings.metronome?.let { preference ->
            val current = settingsIdentity(song)?.let { savedMetronomes[it to song.mediaRevision] }
            (current ?: EngineMetronomeConfig()).copy(
                enabled = preference.enabled && current?.songSynced == true,
                volume = preference.volume, subdivision = preference.subdivision,
                numerator = preference.numerator, denominator = preference.denominator
            )
        } ?: EngineMetronomeConfig()
        // Decoder/output startup is already latency-sensitive before callback presentation proves
        // PLAYING, so enter the adapter's exact playback-owner boundary before publication.
        val token = adapter.trySelect(
            NativeEngineSelectionRequest(
                songId = song.id,
                audioRevision = revision,
                originalLocation = location,
                durationMs = duration,
                startPositionMs = trimStart + playablePosition,
                autoplay = effectiveAutoplay && !restoreBeforePlay,
                forceReload = forceReload,
                originalContentSha256 = sourceHash,
                playbackStartMs = trimStart,
                playbackEndMs = trimEnd,
                initialControls = NativeEngineInitialControls(
                    retained?.controls?.tempo ?: settings.tempo,
                    retained?.controls?.semitoneShift ?: settings.semitones,
                    metronome
                )
            ),
            metadata,
            requestTag = serial
        ) ?: run {
            restoringListeningSession = null
            nativeProcessRestartRequired = true
            PlaybackService.onNativeProcessRestartRequired()
            latestTransportIntent = null
            restoreSelectionArtworkTarget()
            abandonPlaybackAudioFocus()
            publishState(
                mutableState.value.copy(
                    loading = false,
                    playing = false,
                    playWhenReady = false,
                    error = "Playback was safely stopped. Swipe StemWise from Recents and reopen it."
                )
            )
            return false
        }
        listeningRootAuthority = roots.captureAuthority()
        // The outgoing owner's deferred settings survive lookup failure until a replacement is admitted.
        pendingSettingsRestore = null
        if (effectiveAutoplay && focusOwner.focusSuspended)
            focusOwner.resumeAfterAudioFocusGainToken = token.value
        retained?.let { restore ->
            trimReloadControls = null
            val lease = requireNotNull(adapter.acquireCommandLease(song.id))
            val controls = restore.controls
            if (controls.stemLanes.isNotEmpty() && restore.stemRevision != null) {
                adapter.configureStemPackageIfCurrent(lease, restore.stemRevision, controls.stemLanes,
                    selectStems = false)
                adapter.setStemMixIfCurrent(lease, controls.stemControls)
                if (retainedStems) adapter.setSourceModeIfCurrent(lease, EngineSourceMode.STEMS)
            }
        }
        if (restoreBeforePlay) pendingSettingsRestore = PendingSettingsRestore(token, loop, retainedStems, effectiveAutoplay)
        if (listeningRestore?.sourceMode == PlaybackSourceMode.STEMS) {
            listeningStemRestoreJob = scope.launch {
                try {
                    val stems = withContext(Dispatchers.IO) {
                        separationRepository.observeActiveStems(song.id).first()
                    }
                    check(stems.isNotEmpty()) { "The saved Stems are unavailable. Original is ready paused." }
                    if (serial != requestSerial || restoringListeningSession?.first != serial) return@launch
                    val lease = acquireControlLease(song.id)?.takeIf { it.native.token == token } ?: return@launch
                    activateStemsForCurrentSong(song, stems, lease).getOrThrow()
                    if (serial == requestSerial && restoringListeningSession?.first == serial) {
                        check(projectState(adapter.snapshot(), false).sourceSwitchState is SourceSwitchState.Stems) {
                            "The saved Stems request changed before it was ready. Original remains paused."
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (serial == requestSerial && restoringListeningSession?.first == serial &&
                        adapter.snapshot().latestRequest?.selection?.token == token) {
                        pendingSettingsRestore = pendingSettingsRestore?.takeIf { it.token == token }
                            ?.copy(stems = false, autoplay = false)
                        if (finishSettingsRestore(adapter.snapshot())) handleAdapterState(adapter.snapshot())
                        // Retain the saved source choice until a new explicit listening action.
                        publishState(projectState(adapter.snapshot(), false).copy(notice = PlaybackNotice("Saved Stems could not open",
                            error.message ?: "Select a source to continue.", PlaybackNoticeCategory.SONG_SELECTION)))
                    }
                }
            }
        }
        commitResolvedQueueSelection(normalizedSong)
        // A rapid B -> C selection can leave every public scalar equal (both are loading at the
        // same position with the same transport intent). Identity still changed synchronously in
        // the adapter, so publish C's pending projection and force the stable MediaSession to
        // re-read it even when MutableStateFlow would otherwise suppress an equal value.
        publishCurrentControlLease()
        val requestedSnapshot = adapter.snapshot()
        // Keep the facade's callback-committed graph cache on the same manual-selection boundary
        // as the public pending projection. Leaving the previous snapshot here let immediate
        // readers observe B's title/session intent beside A's stale graph until the collector ran.
        updateLastAdapterState(requestedSnapshot)
        publishState(projectState(requestedSnapshot, ownerChanged = false))
        PlaybackService.onPlaybackMetadataChanged()
        latestTransportIntent = effectiveAutoplay
        focusOwner.focusResumeProof = null
        if (focusOwner.focusSuspended) {
            focusOwner.resumeAfterAudioFocusGainToken = token.value.takeIf { effectiveAutoplay }
        }
        if (!effectiveAutoplay) {
            // Manual selection invalidates the outgoing publication/output immediately. A paused
            // or focus-denied target therefore has no audible owner for which focus should linger.
            abandonPlaybackAudioFocus()
        }
        // The new owner admits only its exact audio-bound settings and verified current rhythm.
        // Manual selection is silence-gated and publication-invalidated; only the exact new owner
        // may become audible. Automatic successor handoff is the separate gapless path.
        updateLikelyNext()
        return true
    }

    internal suspend fun awaitManualSelectionResolutionForTest() {
        selectionResolution.await()
    }

    private suspend fun verifiedPlaybackStems(songId: String, stems: List<StemAssetEntity>): List<StemAssetEntity> =
        testAdapter?.verifyStems(songId, stems) ?: separationRepository.verifyPlaybackStems(songId, stems)

    private suspend fun verifiedPlaybackSong(song: Song): Song =
        testAdapter?.verifySong(song) ?: repository.verifyPlaybackSong(song.id)

    private suspend fun resolvePlayableSong(song: Song, prepareSelection: Boolean = false): Pair<Song, String>? {
        val verified = verifiedPlaybackSong(song)
        val location = verified.sourcePath?.takeIf(String::isNotBlank) ?: return null
        if (prepareSelection) analysisRepository.retainSelectionPresentation(verified)
        if (prepareSelection) {
            try { analysisRepository.presentation(verified.id, verified.mediaRevision, verified.sourceHash.orEmpty(), retryFailure = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w(TAG, "Selected song presentation is unavailable", error) }
        }
        prepareSongSettings(verified, retryFailure = true)
        return verified to location
    }

    private suspend fun prepareSavedMetronome(song: Song, identity: SongPlaybackSettingsIdentity, settings: SongPlaybackSettings) {
        val key = identity to song.mediaRevision
        if (settings.metronome != null && !savedMetronomes.containsKey(key)) {
            testAdapter?.beforeSavedMetronomeRead(song)
            val preference = settings.metronome
            val timeline = try {
                analysisRepository.metronomeTimeline(song.id, song.mediaRevision,
                    identity.originalSha256)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Saved metronome needs verified current rhythm", error)
                return
            }
            currentCoroutineContext().ensureActive()
            check(settingsIdentity(song) == identity) { "LIBRARY_CHANGED" }
            val beats = timeline?.beats.orEmpty()
            val available = metronomeAvailableFor(beats.size)
            savedMetronomes[key] = EngineMetronomeConfig(
                enabled = preference.enabled && available,
                songSynced = available,
                bpm = effectiveGridBpm(null, beats.map(BeatEvent::timeMs)),
                volume = preference.volume,
                subdivision = preference.subdivision,
                numerator = preference.numerator,
                denominator = preference.denominator,
                beatGridMs = beats.map(BeatEvent::timeMs).toLongArray(),
                downbeats = beats.map(BeatEvent::isDownbeat).toBooleanArray(),
                meterSegments = projectEngineMeterSegments(timeline?.meterSegments.orEmpty())
            )
        }
    }

    private fun updateCommittedSongFromLibrary(
        committed: PlaybackCommittedPublication,
        previous: Song?,
        refreshed: Song
    ) {
        val location = refreshed.sourcePath?.takeIf(String::isNotBlank)
            ?: committed.selection.originalLocation
        val revision = effectiveAudioRevision(refreshed, location)
        val sameAudio = revision == committed.selection.audioRevision &&
            location == committed.selection.originalLocation &&
            refreshed.durationMs == committed.selection.durationMs &&
            refreshed.sourceHash == committed.selection.originalContentSha256
        if (!sameAudio) {
            load(
                refreshed,
                autoplay = committed.transport.playWhenReady,
                positionMs = committed.selection.publicPositionMs(committed.transport.presentedPositionMs),
                forceReload = true
            )
            return
        }
        if (previous != refreshed) {
            adapter.updateMetadata(publicationMetadata(refreshed, location, revision))
            scheduleSessionArtwork(refreshed, location, revision)
        }
    }

    private fun handleAdapterState(snapshot: NativeAudioPlaybackSessionState) {
        if (released) return
        val positionOnly =
            lastHandledAdapterStructuralRevision == snapshot.structuralRevision
        if (!positionOnly) publishCurrentControlLease()
        invalidateStemMixPreviewIfOwnerChanged(snapshot)
        updateLastAdapterState(snapshot)
        if (pendingSettingsRestore == null) snapshot.latestRequest?.controls?.playWhenReady?.let { latestTransportIntent = it }
        if (positionOnly && handlePositionOnlyAdapterState(snapshot)) return
        lastHandledAdapterStructuralRevision = snapshot.structuralRevision

        var effectiveSnapshot = snapshot
        var committed = snapshot.publication.committed
        suppressedScrubEnd?.let { (suppressedToken, suppressedOwnership) ->
            committed?.let { current ->
                if (current.selection.token != suppressedToken ||
                    current.owner.ownership != suppressedOwnership
                ) {
                    suppressedScrubEnd = null
                }
            }
        }
        val committedMetadataRevision = committed?.metadata?.revision
        val metadataChanged = committedMetadataRevision != null &&
            committedMetadataRevision != lastCommittedMetadataRevision
        if (metadataChanged) {
            lastCommittedMetadataRevision = requireNotNull(committedMetadataRevision)
        }
        val token = committed?.selection?.token?.value
        val ownerChanged = token != null && token != lastCommittedToken
        if (ownerChanged) {
            val interruptedScrub = scrubSnapshot
            // A gesture can never migrate to a newly committed decoder owner.
            if (interruptedScrub != null) invalidateScrub()
            lastCommittedToken = token
            endedOwnerToken = null
            if (optimisticSeek?.ownerToken != token) optimisticSeek = null
            // Manual selection invalidates the gesture synchronously before adapter.trySelect(). An
            // owner change that still sees a scrub is therefore a native automatic boundary that
            // won the race with the hold. Preserve only A's logical play intent on exact B; never
            // carry A's cursor or controls across that boundary.
            if (interruptedScrub?.wasPlaying == true &&
                adapter.setPlayWhenReadyIfCurrent(
                    requireNotNull(committed).selection.token,
                    playWhenReady = true
                ) != null
            ) {
                effectiveSnapshot = adapter.snapshot()
                updateLastAdapterState(effectiveSnapshot)
                lastHandledAdapterStructuralRevision = effectiveSnapshot.structuralRevision
                committed = effectiveSnapshot.publication.committed
                effectiveSnapshot.latestRequest?.let { requested ->
                    latestTransportIntent = requested.controls.playWhenReady
                }
            }
            val acceptedCommitted = requireNotNull(committed)
            queueController.select(acceptedCommitted.selection.songId)
            onCommittedOwnerChanged(acceptedCommitted)
        }

        val rawProjected = projectState(effectiveSnapshot, ownerChanged)
        val current = mutableState.value
        val projected = scrubSnapshot
            ?.takeIf { active ->
                active.ownerToken.value == token && scrubOwnerStillCurrent(active)
            }
            ?.let { rawProjected.copy(positionMs = current.positionMs) }
            ?: rawProjected
        val projectedStateChanged = projected.withoutPosition() != current.withoutPosition()
        if (projectedStateChanged) {
            positionPublications.discardPending()
            publishState(projected)
        } else if (
            projected.positionMs != current.positionMs &&
            token != null &&
            scrubSnapshot == null
        ) {
            val schedule = positionPublications.offer(
                PositionPublication(token, projected.songId ?: return, projected.positionMs)
            )
            if (schedule) schedulePositionPublication()
        }
        // App-only state changes can bypass the session observer; refresh after coherent publication.
        if (metadataChanged) {
            PlaybackService.onPlaybackMetadataChanged()
        }
        resolvePendingTerminalSeek(effectiveSnapshot)
        if (finishSettingsRestore(effectiveSnapshot)) handleAdapterState(adapter.snapshot())
        restoringListeningSession?.takeIf { (serial, saved) ->
            serial == requestSerial && committed?.selection?.songId == saved.identity.songId &&
                pendingSettingsRestore == null && projectState(adapter.snapshot(), false).sourceMode == saved.sourceMode
        }?.let {
            restoringListeningSession = null
            saveListeningSession(immediate = true)
        }
    }

    /** A saved loop is installed against the actual prepared owner before transport starts. */
    private fun finishSettingsRestore(snapshot: NativeAudioPlaybackSessionState): Boolean {
        val pending = pendingSettingsRestore ?: return false
        if (snapshot.latestRequest?.selection?.token != pending.token || playbackCommandsBlocked()) {
            pendingSettingsRestore = null
            return false
        }
        val committed = snapshot.publication.committed?.takeIf { it.selection.token == pending.token }
            ?: return false
        val failed = committed.issue?.cause ?: snapshot.loopPreparation.takeIf {
            pending.loop.enabled && it.selectionToken == pending.token.value &&
                it.generation == committed.owner.ownership.generation &&
                it.phase in setOf(LoopPreparationPhase.FAILED, LoopPreparationPhase.REJECTED)
        }?.reason
        if (failed != null) {
            pendingSettingsRestore = null
            latestTransportIntent = currentResolvingTransportIntent() ?: false
            adapter.setPlayWhenReadyIfCurrent(pending.token, false)
            if (latestTransportIntent != true) abandonPlaybackAudioFocus()
            publishState(projectState(adapter.snapshot(), false).copy(notice = PlaybackNotice("Settings could not open",
                failed, PlaybackNoticeCategory.SONG_SELECTION)))
            return false
        }
        if (pending.stems && projectState(snapshot, false).sourceSwitchState !is SourceSwitchState.Stems) return false
        if (pending.loop.enabled && !snapshot.loopAvailable) return false
        val lease = acquireControlLease(committed.selection.songId)?.takeIf { it.native.token == pending.token }
            ?: return false
        if (pending.loop.pointA != null) {
            val a = committed.selection.publicPositionMs(pending.loop.pointA)
            val b = pending.loop.pointB?.takeIf { pending.loop.enabled }?.let(committed.selection::publicPositionMs)
            if (!applyLoop(a, b, lease, persist = false)) return false
        }
        pendingSettingsRestore = null
        latestTransportIntent = currentResolvingTransportIntent() ?: pending.autoplay
        if (pending.autoplay) {
            // Restoring this owner cannot rewrite a newer selection's independent Play intent.
            if (focusOwner.awaitingFocusGain || requestPlaybackAudioFocus()) {
                if (adapter.setPlayWhenReadyIfCurrent(pending.token, true) != null && focusOwner.awaitingFocusGain) {
                    focusOwner.resumeAfterAudioFocusGainToken = pending.token.value
                }
            } else latestTransportIntent = currentResolvingTransportIntent() ?: false
        }
        // Retiring the deferred overlay needs publication even when no native controls changed.
        lastHandledAdapterStructuralRevision = -1L
        return true
    }

    private fun resolvePendingTerminalSeek(snapshot: NativeAudioPlaybackSessionState) {
        val request = pendingTerminalSeek ?: return
        when (terminalSeekResolution(
            request = request,
            latestRequestedToken = snapshot.latestRequest?.selection?.token,
            committedToken = snapshot.publication.committed?.selection?.token
        )) {
            TerminalSeekResolution.WAIT_FOR_OWNER -> Unit
            TerminalSeekResolution.CANCEL -> pendingTerminalSeek = null
            TerminalSeekResolution.EXECUTE -> {
                pendingTerminalSeek = null
                navigate(
                    transition = QueueTransition.AUTOMATIC_END,
                    positionMs = request.positionMs,
                    automaticEndOwnerToken = request.ownerToken
                )
            }
        }
    }

    /**
     * Ordinary native clock callbacks retain the adapter's structural revision. Avoid rebuilding
     * queue, stem-control, source, and metadata projections on the main thread for those ticks;
     * only the optimistic/native position arbitration and one VSYNC-conflated publication remain.
     */
    private fun handlePositionOnlyAdapterState(
        snapshot: NativeAudioPlaybackSessionState
    ): Boolean {
        if (focusOwner.focusResumeProof != null) return false
        val committed = snapshot.publication.committed ?: return true
        if (hideCommittedPublication) return true
        val token = committed.selection.token.value
        val current = mutableState.value
        if (token != lastCommittedToken || current.songId != committed.metadata.songId) return false
        // Compose owns the immediate finger position. Native preview callbacks must not rebuild the
        // public/session clock until the exact commit or cancellation command settles the gesture.
        if (scrubSnapshot != null) return true
        val position = projectedPosition(committed)
        if (position == current.positionMs) return true
        val schedule = positionPublications.offer(
            PositionPublication(token, committed.metadata.songId, position)
        )
        if (schedule) schedulePositionPublication()
        return true
    }

    private fun projectState(
        snapshot: NativeAudioPlaybackSessionState,
        ownerChanged: Boolean
    ): AudioPlaybackState {
        val queue = queueController.snapshot()
        val committed = snapshot.publication.committed?.takeUnless { hideCommittedPublication }
        val latest = snapshot.latestRequest
        if (snapshot.lifecycle == NativeAudioPlaybackSessionLifecycle.RESTART_REQUIRED) {
            return mutableState.value.copy(
                playing = false, playWhenReady = false, loading = false,
                error = snapshot.terminalFailure ?: "Playback must restart",
                loopAvailable = false
            )
        }
        val pendingIssue = snapshot.publication.pendingSelection?.issue
        val selectionPending = resolvingRequestSerial != null ||
            (latest != null && latest.selection.token != committed?.selection?.token) ||
            snapshot.publication.pendingSelection != null
        if (committed == null) {
            preparedSourceResumeProof = null
            val pendingControls = latest?.controls
            return mutableState.value.copy(
                songId = null,
                selectionToken = null,
                loading = selectionPending && pendingIssue == null,
                playing = false,
                // Identity remains callback-committed only, but transport intent belongs to the
                // synchronous latest request. The Player can therefore render Pause for an
                // autoplay target during decode and its next tap performs the action it shows.
                playWhenReady = pendingControls?.playWhenReady ?: false,
                positionMs = latest?.selection?.publicPositionMs(pendingControls?.requestedPositionMs ?: 0L) ?: 0L,
                durationMs = latest?.selection?.playbackDurationMs ?: 0L,
                sourceDurationMs = latest?.selection?.durationMs ?: 0L,
                trimStartMs = latest?.selection?.playbackStartMs ?: 0L,
                ended = false,
                error = pendingIssue?.cause ?: mutableState.value.error,
                sourceMode = PlaybackSourceMode.ORIGINAL,
                sourceSwitchState = SourceSwitchState.Original,
                // A pending owner carries its own neutral/requested control snapshot. Retaining
                // the outgoing values here could pair the new title/artwork with the old owner's
                // speed or metronome in MediaSession while local decode is still committing.
                tempo = pendingControls?.tempo ?: 1f,
                semitoneShift = pendingControls?.semitoneShift ?: 0,
                loopA = null,
                loopB = null,
                loopAvailable = false,
                metronomeEnabled = pendingControls?.metronome?.enabled ?: false,
                metronomeSongSynced = pendingControls?.metronome?.songSynced ?: false,
                metronomeBpm = pendingControls?.metronome?.bpm ?: 120f,
                metronomeVolume = pendingControls?.metronome?.volume ?: 1f,
                metronomeSubdivision = pendingControls?.metronome?.subdivision ?: 1,
                metronomeNumerator = pendingControls?.metronome?.numerator ?: 4,
                metronomeDenominator = pendingControls?.metronome?.denominator ?: 4,
                stemMix = emptyMap(),
                loadedStemSetId = null,
                loadedStemSignature = null,
                loadedStemAssets = emptyList(),
                repeatMode = queue.repeatMode.label,
                shuffleEnabled = queue.shuffleEnabled,
                canGoPrevious = manualNavigationAvailable(QueueTransition.EXPLICIT_PREVIOUS),
                canGoNext = manualNavigationAvailable(QueueTransition.EXPLICIT_NEXT),
                queueOrder = queue.order,
                currentQueueSongId = queue.currentId
            )
        }

        val controls = requireNotNull(snapshot.committedControls)
        val installed = committed.stems.installed
        val installedCandidate = installed?.packageSignature?.let(stemCandidates::get)
        val desiredStemPackage = committed.stems.desired
        val requestedCandidate = desiredStemPackage?.packageSignature?.let(stemCandidates::get)
        val requestedStemCandidateMatches = requestedCandidate?.let { candidate ->
            !desiredStemPackage.packageSignature.isBlank() &&
                !desiredStemPackage.stemRevision.isBlank() &&
                candidate.songId == committed.selection.songId &&
                candidate.packageSignature == desiredStemPackage.packageSignature &&
                candidate.stemRevision == desiredStemPackage.stemRevision
        } == true
        val exactInstalledStemPackage = installed != null && desiredStemPackage != null &&
            installed.packageSignature == desiredStemPackage.packageSignature &&
            installed.stemRevision == desiredStemPackage.stemRevision &&
            installed.requestRevision == desiredStemPackage.requestRevision
        val sourcePresentation = projectedSourcePresentation(
            committed = committed,
            requestedStemCandidateMatches = requestedStemCandidateMatches,
            installedStemCandidateAvailable = installedCandidate != null,
            installedStemPackageMatchesDesired = exactInstalledStemPackage,
            previousResumeProof = preparedSourceResumeProof
        )
        preparedSourceResumeProof = sourcePresentation.resumeProof
        val projectedPreparedOriginal =
            sourcePresentation.preparedMode == EngineSourceMode.ORIGINAL
        val projectedPreparedCandidate = requestedCandidate?.takeIf {
            sourcePresentation.preparedMode == EngineSourceMode.STEMS
        }
        val presentedStemCandidate = projectedStemPresentationValue(
            sourcePresentation.stemPackageAuthority,
            installed = installedCandidate,
            prepared = requestedCandidate
        )
        val presentedStemSignature = projectedStemPresentationValue(
            sourcePresentation.stemPackageAuthority,
            installed = installed?.packageSignature,
            prepared = desiredStemPackage?.packageSignature
        )
        val stemMix = controls.stemControls.mapValues { (_, control) -> control.toStemMixState() }
        val loop = controls.loop
        val loopAvailable = snapshot.loopAvailable &&
            latest?.selection == committed.selection &&
            latest.exactOwnership == committed.owner.ownership
        val position = projectedPosition(committed)
        val ended = endedOwnerToken == committed.selection.token.value
        val awaitingFocusResumeProof = focusOwner.focusResumeProof?.let { proof ->
            when {
                proof.ownerToken != committed.selection.token.value -> {
                    focusOwner.focusResumeProof = null
                    false
                }
                committed.transport.positionCallbackSequence > proof.callbackSequenceAtGain -> {
                    focusOwner.focusResumeProof = null
                    false
                }
                else -> true
            }
        } ?: false
        // Paused preparation has no audible callback. Its exact proof may bridge only the next
        // Play-to-callback gap; reducer appliedMode/INSTALLED remain callback-authoritative.
        val sourceMode = sourcePresentation.mode.toPublicSourceMode()
        val sourceState = sourceSwitchState(
            committed,
            installedCandidate,
            projectedPreparedCandidate,
            projectedPreparedOriginal
        )
        val globalError = committed.issue
            ?.takeUnless { it.category == "source-mode" || it.category == "stem-package" }
            ?.cause
        val suppression = projectedPlaybackSuppression(
            focusSuspended = focusOwner.focusSuspended,
            previousSuppression = mutableState.value.suppression
        )
        return mutableState.value.copy(
            songId = committed.metadata.songId,
            selectionToken = committed.selection.token.value,
            loading = selectionPending && pendingIssue == null,
            playing = projectedPlaybackIsPlaying(
                playWhenReady = committed.transport.playWhenReady,
                awaitingFocusResumeProof = awaitingFocusResumeProof,
                ended = ended,
                error = globalError,
                suppression = suppression
            ),
            playWhenReady = committed.transport.playWhenReady,
            positionMs = position,
            durationMs = committed.selection.playbackDurationMs,
            sourceDurationMs = committed.selection.durationMs,
            trimStartMs = committed.selection.playbackStartMs,
            ended = ended,
            error = globalError,
            suppression = suppression,
            sourceMode = sourceMode,
            sourceSwitchState = sourceState,
            tempo = controls.tempo,
            semitoneShift = controls.semitoneShift,
            loopA = loop?.startMs?.let(committed.selection::publicPositionMs) ?: retainedIncompleteLoopStart(
                previousA = mutableState.value.loopA,
                previousB = mutableState.value.loopB,
                ownerChanged = ownerChanged
            ),
            loopB = loop?.endMs?.let(committed.selection::publicPositionMs),
            loopAvailable = loopAvailable,
            loopPreparation = snapshot.loopPreparation.takeIf {
                it.selectionToken == committed.owner.ownership.selectionToken &&
                    it.generation == committed.owner.ownership.generation
            } ?: LoopPreparationState(),
            metronomeEnabled = controls.metronome.enabled,
            metronomeSongSynced = controls.metronome.songSynced,
            metronomeBpm = controls.metronome.bpm,
            metronomeVolume = controls.metronome.volume,
            metronomeSubdivision = controls.metronome.subdivision,
            metronomeNumerator = controls.metronome.numerator,
            metronomeDenominator = controls.metronome.denominator,
            stemMix = stemMix,
            // Prepared-paused and installed-audible phases share this exact canonical candidate.
            // Transport therefore cannot reorder the mixer or replace its source chrome.
            loadedStemSetId = presentedStemCandidate?.setId,
            loadedStemSignature = presentedStemSignature,
            loadedStemAssets = presentedStemCandidate?.assets.orEmpty(),
            repeatMode = queue.repeatMode.label,
            shuffleEnabled = queue.shuffleEnabled,
            canGoPrevious = manualNavigationAvailable(QueueTransition.EXPLICIT_PREVIOUS),
            canGoNext = manualNavigationAvailable(QueueTransition.EXPLICIT_NEXT),
            queueOrder = queue.order,
            currentQueueSongId = queue.currentId
        )
    }

    private fun projectedPosition(committed: PlaybackCommittedPublication): Long {
        if (endedOwnerToken == committed.selection.token.value) {
            optimisticSeek = null
            return committed.selection.playbackDurationMs
        }
        val optimistic = optimisticSeek
        if (optimistic?.ownerToken != committed.selection.token.value) {
            optimisticSeek = null
            return committed.selection.publicPositionMs(committed.transport.presentedPositionMs)
        }
        if (!nativeSeekRevisionAcknowledged(optimistic.ownerToken, committed.selection.token.value,
                optimistic.seekRevision, committed.transport.completedSeekRevision)) {
            return optimistic.positionMs
        }
        optimisticSeek = null
        return committed.selection.publicPositionMs(committed.transport.presentedPositionMs)
    }

    private fun sourceSwitchState(
        committed: PlaybackCommittedPublication,
        installedCandidate: StemPackageCandidate?,
        pausedPreparedCandidate: StemPackageCandidate?,
        pausedPreparedOriginal: Boolean
    ): SourceSwitchState {
        val issue = committed.issue
        if (issue != null && (issue.category == "source-mode" || issue.category == "stem-package")) {
            return SourceSwitchState.Failed(
                committed.source.appliedMode.toPublicSourceMode(),
                issue.cause
            )
        }
        return when (committed.source.desiredMode) {
            EngineSourceMode.ORIGINAL -> if (
                committed.source.appliedMode == EngineSourceMode.ORIGINAL ||
                pausedPreparedOriginal
            ) {
                SourceSwitchState.Original
            } else {
                SourceSwitchState.ReturningOriginal
            }
            EngineSourceMode.STEMS -> if (
                committed.source.appliedMode == EngineSourceMode.STEMS &&
                committed.stems.phase == PlaybackStemPackagePhase.INSTALLED
            ) {
                SourceSwitchState.Stems(installedCandidate?.setId)
            } else if (pausedPreparedCandidate != null) {
                SourceSwitchState.Stems(pausedPreparedCandidate.setId)
            } else {
                SourceSwitchState.PreparingStems(
                    committed.stems.desired?.packageSignature
                        ?.let(stemCandidates::get)
                        ?.setId
                )
            }
        }
    }

    internal fun handleAdapterEffect(effect: NativeAudioPlaybackSessionEffect) {
        when (effect) {
            is NativeAudioPlaybackSessionEffect.AudibleEnd -> {
                // beginScrub synchronously claims the token, but an end event already posted
                // before ACTION_DOWN may still reach main afterward. The gesture owns that exact
                // token until its terminal command and therefore suppresses the stale event.
                val retainedScrubEnd = suppressedScrubEnd
                if (scrubEndIsSuppressed(
                        activeScrubToken = scrubSnapshot?.ownerToken,
                        retainedToken = retainedScrubEnd?.first,
                        retainedOwnership = retainedScrubEnd?.second,
                        effectToken = effect.token,
                        effectOwnership = effect.ownership
                    )
                ) return
                // State collection and effect delivery are independently scheduled on main. The
                // adapter has already reduced this effect, so validate it against that same atomic
                // snapshot instead of a potentially one-generation-old facade cache.
                dispatchAudibleEnd(
                    snapshot = adapter.snapshot(),
                    effect = effect,
                    rewindForSourceChange = { committed ->
                        val lease = acquireControlLease(committed.selection.songId)
                        !trimPreviewOwns(committed.selection) && lease != null && seekToInternal(
                            positionMs = 0L,
                            terminalPlayWhenReady = null,
                            controlLease = lease,
                            allowPendingTerminal = false
                        ) != null
                    },
                    onEnd = { committed ->
                        navigate(
                            transition = QueueTransition.AUTOMATIC_END,
                            positionMs = committed.selection.playbackDurationMs,
                            automaticEndOwnerToken = effect.token
                        )
                    }
                )
            }
            is NativeAudioPlaybackSessionEffect.OwnerTerminated -> {
                val snapshot = adapter.snapshot()
                updateLastAdapterState(snapshot)
                if (pendingTerminalSeek?.ownerToken?.value == effect.ownership.selectionToken) {
                    pendingTerminalSeek = null
                }
                // Focus admission precedes native publication; its current request fences all effects.
                if (!focusOwner.retireTerminatedOwner(effect.ownership.selectionToken,
                        resolvingSuccessor = resolvingRequestSerial != null ||
                            pendingOwnerlessRebind?.requestSerial == requestSerial)) return
                mainHandler.removeCallbacks(failedSelectionFocusReleaseRunnable)
                restoreSelectionArtworkTarget()
                publishQueueState()
                updateLikelyNext()
                publishState(projectState(snapshot, ownerChanged = true).copy(
                    notice = PlaybackNotice("Song unavailable", effect.cause,
                        PlaybackNoticeCategory.SONG_SELECTION)
                ))
            }
            is NativeAudioPlaybackSessionEffect.SelectionFailed -> {
                if (!selectionFailureBelongsToLatestRequest(
                        failedRequestSerial = effect.requestTag,
                        latestRequestSerial = requestSerial
                    )
                ) return
                if (pendingTerminalSeek?.ownerToken == effect.token) pendingTerminalSeek = null
                clearResolvingSelection()
                val snapshot = adapter.snapshot()
                updateLastAdapterState(snapshot)
                val hasCommittedOwner = snapshot.publication.committed != null
                if (failedSelectionMayHandOffFocus(
                        hasCommittedOwner = hasCommittedOwner,
                        latestTransportIntent = latestTransportIntent,
                        audioFocusHeld = focusOwner.audioFocusHeld
                    )
                ) {
                    mainHandler.removeCallbacks(failedSelectionFocusReleaseRunnable)
                    mainHandler.postDelayed(
                        failedSelectionFocusReleaseRunnable,
                        FAILED_SELECTION_FOCUS_HANDOFF_MS
                    )
                } else if (!hasCommittedOwner) {
                    abandonPlaybackAudioFocus()
                }
                snapshot.publication.committed?.selection?.songId?.let(queueController::select)
                restoreSelectionArtworkTarget()
                publishQueueState()
                updateLikelyNext()
                val cause = effect.cause.takeIf(String::isNotBlank)
                    ?: "The selected local audio could not be opened."
                publishState(
                    projectState(snapshot, ownerChanged = false).copy(
                        loading = false,
                        notice = PlaybackNotice("Song unavailable", cause,
                            PlaybackNoticeCategory.SONG_SELECTION)
                    )
                )
            }
            is NativeAudioPlaybackSessionEffect.LoopRejected -> {
                val snapshot = adapter.snapshot()
                updateLastAdapterState(snapshot)
                val committed = snapshot.publication.committed
                if (committed?.selection?.token == effect.token) {
                    publishState(
                        loopRejectedState(
                            projectState(snapshot, ownerChanged = false),
                            effect.cause
                        )
                    )
                    updateLikelyNext()
                }
            }
            is NativeAudioPlaybackSessionEffect.StopCompleted -> {
                if (effect.clearedPublication && hideCommittedPublication) {
                    publishState(emptyPlaybackState().copy(notice = mutableState.value.notice))
                }
                restoreRemovedTaskIfReady()
                val pending = pendingOwnerlessRebind
                    ?.takeIf { it.requestSerial == requestSerial }
                pendingOwnerlessRebind = null
                if (pending != null &&
                    adapter.snapshot().lifecycle == NativeAudioPlaybackSessionLifecycle.ACTIVE &&
                    adapter.snapshot().latestRequest == null &&
                    !playbackCommandsBlocked()
                ) {
                    loadInternal(
                        song = pending.song,
                        autoplay = pending.autoplay,
                        positionMs = pending.positionMs,
                        forceReload = true,
                        restartAtZero = pending.restartAtZero,
                        preservePreviousPair = pending.preservePreviousPair
                    )
                }
            }
            is NativeAudioPlaybackSessionEffect.Diagnostic -> {
                Log.w(TAG, "Native ${effect.operation}: ${effect.diagnostic}")
            }
            is NativeAudioPlaybackSessionEffect.OutputAvailabilityChanged -> {
                if (adapter.snapshot().latestRequest?.selection?.token != effect.token) return
                if (!effect.available) {
                    val notice = PlaybackNotice("Playback unavailable", requireNotNull(effect.diagnostic),
                        PlaybackNoticeCategory.SONG_SELECTION)
                    outputFailureNotice = effect.token to notice
                    publishState(mutableState.value.copy(notice = notice))
                } else {
                    val previous = outputFailureNotice
                    if (previous?.first == effect.token) {
                        outputFailureNotice = null
                        if (mutableState.value.notice === previous.second) publishState(mutableState.value.copy(notice = null))
                    }
                }
            }
            is NativeAudioPlaybackSessionEffect.OutputUnavailable -> {
                nativeProcessRestartRequired = true
                latestTransportIntent = false
                focusOwner.focusResumeProof = null
                abandonPlaybackAudioFocus()
                PlaybackService.onNativeProcessRestartRequired()
                sessionArtwork.cancelPrefetch()
                val snapshot = adapter.snapshot()
                updateLastAdapterState(snapshot)
                publishState(projectState(snapshot, ownerChanged = false))
                Log.e(TAG, "Native output unavailable: ${effect.diagnostic}")
            }
            NativeAudioPlaybackSessionEffect.Closed -> Unit
        }
    }

    private fun pauseTrimPreviewAtEnd(selection: EngineSelection): Boolean {
        if (!trimPreviewOwns(selection)) return false
        pendingSettingsRestore = pendingSettingsRestore?.let {
            if (it.token == selection.token) it.copy(autoplay = false) else it
        }
        latestTransportIntent = false
        adapter.setPlayWhenReadyIfCurrent(selection.token, false)
        abandonPlaybackAudioFocus()
        publishState(mutableState.value.copy(playWhenReady = false, playing = false))
        return true
    }

    private fun manualNavigationCursor(): String? =
        manualNavigationSongId?.takeIf(queueController::contains) ?: queueController.snapshot().currentId

    private fun manualNavigationAvailable(transition: QueueTransition): Boolean =
        queueController.preview(transition, manualNavigationCursor()) is QueueDecision.Move

    private fun navigate(
        transition: QueueTransition,
        positionMs: Long? = null,
        previousPress: Boolean = false,
        automaticEndOwnerToken: EngineSelectionToken? = null,
        playWhenReadyOverride: Boolean? = null
    ): String? {
        if (playbackCommandsBlocked()) return null
        if (transition != QueueTransition.AUTOMATIC_END) pendingTerminalSeek = null
        val adapterState = adapter.snapshot()
        val committed = adapterState.publication.committed
        val latest = adapterState.latestRequest
        if (transition == QueueTransition.AUTOMATIC_END) {
            // A native EOS may move the queue only for the exact still-current audible owner.
            // Explicit headset/UI navigation is intentionally allowed while a newer owner is
            // pending or resolving, but an outgoing EOS never skips that incoming target.
            val requestedOwner = automaticEndOwnerToken ?: return null
            if (!automaticEndOwnerIsCurrent(
                    selectionResolutionPending = resolvingRequestSerial != null,
                    committedToken = committed?.selection?.token,
                    latestRequestedToken = latest?.selection?.token,
                    requestedOwnerToken = requestedOwner
                )
            ) return null
            if (!automaticEndQueueOwnerIsCurrent(
                    queueCurrentSongId = queueController.snapshot().currentId,
                    committedSongId = committed?.selection?.songId
                )
            ) return null
            if (committed != null && pauseTrimPreviewAtEnd(committed.selection)) {
                endedOwnerToken = requestedOwner.value
                publishState(endedAtDurationState(mutableState.value, checkNotNull(committed).selection.playbackDurationMs))
                return null
            }
            if (pendingTerminalSeek?.ownerToken == requestedOwner) pendingTerminalSeek = null
            acquireCurrentControlLease()?.let { applyLoop(null, null, it, persist = false) }
        }
        val playWhenReady = playWhenReadyOverride
            ?: currentResolvingTransportIntent()
            ?: scrubSnapshot?.takeIf(::scrubOwnerStillCurrent)?.wasPlaying
            ?: latest?.controls?.playWhenReady
            ?: committed?.transport?.playWhenReady
            // A failed manual owner is deliberately removed from adapter authority, but the queue
            // must still be able to skip that bad item while preserving the user's play/pause
            // intent for the next valid local song.
            ?: latestTransportIntent
            ?: return null
        val currentId = (if (transition == QueueTransition.AUTOMATIC_END) queueController.snapshot().currentId
            else manualNavigationCursor()) ?: return null
        val isPrevious = previousPress
        val decision = if (isPrevious) {
            if (previousPressTracker.press(currentId, android.os.SystemClock.elapsedRealtime())) {
                queueController.preview(QueueTransition.EXPLICIT_PREVIOUS, currentId).let {
                    if (it == QueueDecision.Boundary) QueueDecision.Restart(currentId) else it
                }
            } else QueueDecision.Restart(currentId)
        } else {
            previousPressTracker.reset()
            queueController.preview(transition, currentId)
        }
        return when (decision) {
            is QueueDecision.Move -> {
                val target = queueById[decision.mediaId] ?: return null
                if (load(
                        target,
                        autoplay = playWhenReady,
                        positionMs = 0L,
                        forceReload = false
                    )
                ) decision.mediaId else null
            }
            is QueueDecision.Restart -> {
                if (latest?.selection?.songId != currentId || resolvingRequestSerial != null) {
                    // A still-resolving selection must never seek the outgoing audible owner.
                    val target = queueById[currentId] ?: return null
                    if (loadInternal(target, autoplay = playWhenReady, positionMs = 0L, forceReload = false,
                            restartAtZero = true, preservePreviousPair = isPrevious)) {
                        decision.mediaId
                    } else {
                        previousPressTracker.reset()
                        null
                    }
                } else {
                    val loop = latest.controls.loop
                    val currentPosition = if (committed?.selection?.token == latest.selection.token) {
                        projectedPosition(committed)
                    } else latest.selection.publicPositionMs(latest.controls.requestedPositionMs)
                    val restartAt = if (isPrevious) previousRestartPositionMs(
                        currentPosition, loop?.startMs?.let(latest.selection::publicPositionMs),
                        loop?.endMs?.let(latest.selection::publicPositionMs)
                    ) else 0L
                    if (seekToForSession(restartAt, preservePreviousPair = isPrevious) == null) {
                        previousPressTracker.reset()
                        return null
                    }
                    if (playWhenReady) playInternal(preservePreviousPair = isPrevious)
                    publishQueueState()
                    decision.mediaId
                }
            }
            QueueDecision.Boundary -> {
                publishQueueState()
                null
            }
            QueueDecision.Stop -> {
                val exactCommitted = committed ?: return null
                endedOwnerToken = exactCommitted.selection.token.value
                adapter.setAutomaticEndPlan(null)
                adapter.pause()
                abandonPlaybackAudioFocus()
                publishState(endedAtDurationState(
                    mutableState.value,
                    exactCommitted.selection.playbackDurationMs
                ))
                publishQueueState()
                null
            }
        }
    }

    private fun stemCandidate(song: Song, stems: List<StemAssetEntity>): StemPackageCandidate {
        require(stems.all(StemAssetEntity::validated)) { "Every stem must be validated" }
        val setIds = stems.mapTo(linkedSetOf(), StemAssetEntity::separationSetId)
        require(setIds.size == 1 && setIds.first().isNotBlank()) {
            "All selected parts must belong to one package"
        }
        val byRole = stems.groupBy { stem ->
            StemRoleContract.normalize(stem.normalizedCapability ?: stem.providerLabel)
        }
        val roles = StemRoleContract.orderedPlayableRoles(stems.map {
            it.normalizedCapability ?: it.providerLabel
        })
        val lanes = roles.map { role ->
            val stem = byRole.getValue(role).single()
            EngineStemLane(
                id = stem.id,
                normalizedRole = role,
                location = stem.localPath.trim().also { require(it.isNotBlank()) },
                contentSha256 = stem.sha256.also {
                    require(it.matches(Regex("[0-9a-f]{64}"))) { "Every stem needs a measured SHA-256" }
                }
            )
        }
        val identity = derivedStemPackageIdentity(song.id, lanes)
        // The selected assets are the revision authority. Song.stemRevision describes the
        // database's currently active set and is stale while the user is choosing another set.
        return StemPackageCandidate(
            songId = song.id,
            stemRevision = identity.stemRevision,
            packageSignature = identity.packageSignature,
            setId = setIds.single(),
            assets = roles.map { byRole.getValue(it).single() },
            lanes = lanes
        )
    }

    private fun applyLoop(
        pointA: Long?,
        pointB: Long?,
        controlLease: PlaybackControlLease,
        transportPositionMs: Long? = null,
        publishPresentation: Boolean = true,
        inputEventAtMonotonicMicros: Long = 0L,
        heldBGestureId: Long = 0L,
        capturedBMs: Long = -1L,
        persist: Boolean = true
    ): Boolean {
        require(inputEventAtMonotonicMicros >= 0L)
        require(heldBGestureId >= 0L)
        require(capturedBMs >= -1L)
        if (playbackCommandsBlocked()) return false
        val session = adapter.snapshot()
        val requested = session.latestRequest?.takeIf {
            it.selection.token == controlLease.native.token
        }
            ?: return false
        val selection = requested.selection
        val duration = selection.playbackDurationMs
        val draft = normalizedLoopDraft(pointA, pointB, duration)
        val range = validatedLoopRange(draft.pointA, draft.pointB, duration)
        require(capturedBMs < 0L ||
            (heldBGestureId > 0L && range != null &&
                capturedBMs - range.startMs >= AudioPlayback.MINIMUM_LOOP_DURATION_MS &&
                capturedBMs <= duration)
        )
        val visible = mutableState.value
        // A seek accepted immediately before this loop command already owns the logical cursor,
        // even though callback-presented position can still describe the pre-seek generation for
        // one actor turn.  Classifying from that stale callback can park an A-B range that was
        // actually committed at A, leaving the native cache permanently waiting for re-entry.
        val exactPosition = transportPositionMs?.coerceIn(0L, duration) ?: optimisticSeek
            ?.takeIf { it.ownerToken == controlLease.ownerEpoch }
            ?.positionMs
            ?: lastAdapterState.publication.committed
                ?.takeIf { it.selection.token == controlLease.native.token }
                ?.let { it.selection.publicPositionMs(it.transport.presentedPositionMs) }
            ?: visible.positionMs
        // Endpoint gestures author a boundary; they never authorize a transport relocation.
        // Native will adopt the range warm when the exact cursor is inside it, or leave it dormant
        // until natural entry. Non-gesture commands retain their position-derived activation rule.
        val parkUntilRangeEntry = editedLoopShouldParkUntilRangeEntry(
            nextRange = range,
            transportPositionMs = exactPosition
        )
        val engineLoop = range?.let {
            EngineLoopConfig(
                startMs = selection.sourcePositionMs(it.startMs),
                endMs = selection.sourcePositionMs(it.endMs),
                parkUntilRangeEntry = parkUntilRangeEntry
            )
        }
        val settingsOwner = if (publishPresentation && persist) settingsOwner(controlLease.native, requested) else null
        if (!adapter.setLoopIfCurrent(
                controlLease.native,
                engineLoop,
                publishPresentation = publishPresentation,
                inputEventAtMonotonicMicros = inputEventAtMonotonicMicros,
                heldBGestureId = heldBGestureId,
                capturedBMs = if (capturedBMs >= 0L) selection.sourcePositionMs(capturedBMs) else -1L,
                captureReferencePositionMs = if (capturedBMs >= 0L) selection.sourcePositionMs(exactPosition) else -1L
            )
        ) return false
        if (publishPresentation && persist) persistLoop(settingsOwner, selection, draft.pointA, range?.endMs)
        if (!adapter.commandLeaseIsCurrent(controlLease.native)) return false
        if (publishPresentation) {
            publishState(
                mutableState.value.copy(
                    loopA = draft.pointA,
                    loopB = range?.endMs
                )
            )
        }
        return true
    }

    private fun onCommittedOwnerChanged(
        committed: PlaybackCommittedPublication
    ) {
        hideCommittedPublication = false
        // A previously admitted owner may commit while a newer song is still resolving.
        // Only that resolver (or explicit cancellation) may clear its request serial.
        queueById[committed.selection.songId]?.let { song ->
            scheduleSessionArtwork(
                song = song,
                location = committed.selection.originalLocation,
                audioRevision = committed.selection.audioRevision
            )
        }
        val restoringPlay = pendingSettingsRestore?.let {
            it.token == committed.selection.token && it.autoplay
        } == true
        val incomingPlay = selectionResolution.current?.takeIf { it.serial == requestSerial }?.autoplay == true
        if (ownerCommitShouldAbandonFocus(committed.transport.playWhenReady || restoringPlay || incomingPlay)) {
            abandonPlaybackAudioFocus()
        }
        updateLikelyNext()
    }

    private fun updateLikelyNext(availabilityRefreshed: Boolean = false) {
        if (playbackCommandsBlocked()) return
        if (songTrimPreview?.lease?.requestSerial == requestSerial) {
            adapter.setAutomaticEndPlan(null)
            return
        }
        val session = adapter.snapshot()
        val committed = session.publication.committed
        val queue = queueController.snapshot()
        val exactCurrentMayNavigate = committed != null &&
            session.latestRequest?.selection?.token == committed.selection.token &&
            queue.currentId == committed.selection.songId
        val decision = if (exactCurrentMayNavigate) {
            queueController.previewAutomaticEnd()
        } else null
        val fallback = if (decision is QueueDecision.Restart) {
            queueController.previewAutomaticEndIgnoringRepeatOne() as? QueueDecision.Move
        } else null
        val candidates = listOfNotNull((decision as? QueueDecision.Move)?.mediaId, fallback?.mediaId)
            .distinct().mapNotNull(queueById::get)
        if (availabilityRefreshed) {
            candidates.mapNotNull(::settingsIdentity).forEach { key ->
                if (preferencesPersistence.savedSettings[key]?.isFailure == true) preferencesPersistence.savedSettings.remove(key)
            }
        }
        automaticPrewarm.update(AutomaticQueuePrewarmRequest(
            requestSerial, committed?.selection?.token, committed?.selection?.audioIdentity(),
            decision, fallback, roots.captureAuthority(),
            candidates.map { it.id to queuedAudioIdentity(it, allowDormantControls = true) },
            candidates.filter { queuedAudioIdentity(it) != null }.map(Song::id).toSet()
        ), availabilityRefreshed = availabilityRefreshed)
    }

    private suspend fun verifiedQueueItem(song: Song, allowDormantControls: Boolean = false): NativeEngineQueueItem? = try {
        if (partsDeletionBlocks(song.id)) null else {
            val verified = withContext(Dispatchers.IO) { resolvePlayableSong(song)?.first }
            if (verified == null || partsDeletionBlocks(song.id)) null else nativeQueueItem(verified, allowDormantControls)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Could not validate queued local audio", error)
        null
    }

    private fun queuedAudioIdentity(song: Song, allowDormantControls: Boolean = false): EngineAudioIdentity? {
        val location = song.sourcePath?.takeIf(String::isNotBlank) ?: return null
        if (song.durationMs <= 0L) return null
        val sourceHash = song.sourceHash?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) } ?: return null
        val trim = settingsIdentity(song)?.let { identity ->
            val stored = cachedSettings(identity) ?: return null
            if (stored.isFailure) return null
            val settings = stored.getOrNull() ?: return null
            // Native automatic successors are neutral; non-default controls use ordinary selection.
            if (!allowDormantControls && settings.copy(trim = null) != SongPlaybackSettings()) return null
            settings.trim
        }
        return EngineAudioIdentity(song.id, effectiveAudioRevision(song, location), location, song.durationMs,
            sourceHash, trim?.startMs ?: 0L, trim?.endMs ?: song.durationMs)
    }

    private fun nativeQueueItem(song: Song, allowDormantControls: Boolean = false): NativeEngineQueueItem? {
        val identity = queuedAudioIdentity(song, allowDormantControls) ?: return null
        val publication = publicationMetadata(song, identity.originalLocation, identity.audioRevision)
        return NativeEngineQueueItem(identity, NativeEngineMetadata(song.id, identity.audioRevision,
            publication.revision, publication.payloadKey))
    }

    private fun publicationMetadata(
        song: Song,
        location: String,
        audioRevision: String
    ): PlaybackPublicationMetadata {
        return metadataPublications.publish(
            NativePlaybackMetadataPayload(
                song = song,
                originalLocation = location,
                sessionArtwork = sessionArtworkKey(song)?.let(sessionArtwork::cached)
            ),
            audioRevision
        )
    }

    private fun scheduleSessionArtwork(song: Song, location: String, audioRevision: String) {
        sessionArtwork.select(sessionArtworkKey(song)) { key ->
            val currentSong = queueById[song.id] ?: return@select
            if (sessionArtworkKey(currentSong) != key) return@select
            val current = adapter.snapshot().let { snapshot ->
                snapshot.latestRequest?.selection ?: snapshot.publication.committed?.selection
            } ?: return@select
            if (current.songId == currentSong.id && current.audioRevision == audioRevision &&
                current.originalLocation == location
            ) {
                adapter.updateMetadata(publicationMetadata(currentSong, location, audioRevision))
            }
        }
    }

    private fun scheduleLikelyNextArtwork(song: Song) {
        sessionArtwork.prefetch(sessionArtworkKey(song)) { key ->
            // The existing prewarm owner remains the sole automatic-end plan publisher.
            if (queueById[song.id]?.let(::sessionArtworkKey) == key) updateLikelyNext()
        }
    }

    private fun effectiveAudioRevision(song: Song, location: String): String =
        song.audioRevision.takeIf(String::isNotBlank)
            ?: song.sourceHash?.takeIf(String::isNotBlank)
            ?: playbackStableDigest("${song.id}|$location|${song.durationMs}")

    private fun ensureQueued(song: Song) {
        putQueueSong(song)
        val queue = queueController.snapshot()
        if (queueController.contains(song.id)) return
        if (song.id !in libraryOrder) libraryOrder = libraryOrder + song.id
        if (queue.shuffleEnabled) {
            queueController.updateLibrary(libraryOrder)
        } else {
            queueController.setSequentialOrder(libraryOrder)
        }
    }

    /** Publishes a queue cursor only after the destination has resolved and audio accepted it. */
    private fun commitResolvedQueueSelection(song: Song) {
        ensureQueued(song)
        queueController.select(song.id)
        if (manualNavigationSongId == song.id) manualNavigationSongId = null
        publishQueueState()
    }

    private fun publishQueueState() {
        val queue = queueController.snapshot()
        publishState(
            mutableState.value.copy(
                repeatMode = queue.repeatMode.label,
                shuffleEnabled = queue.shuffleEnabled,
                canGoPrevious = manualNavigationAvailable(QueueTransition.EXPLICIT_PREVIOUS),
                canGoNext = manualNavigationAvailable(QueueTransition.EXPLICIT_NEXT),
                queueOrder = queue.order,
                currentQueueSongId = queue.currentId
            )
        )
    }

    private fun publishState(projected: AudioPlaybackState) {
        val navigation = projected.copy(
            canGoPrevious = manualNavigationAvailable(QueueTransition.EXPLICIT_PREVIOUS),
            canGoNext = manualNavigationAvailable(QueueTransition.EXPLICIT_NEXT)
        )
        val value = currentResolvingTransportIntent()?.let {
            navigation.copy(playWhenReady = it, loading = true)
        } ?: navigation
        if (mutableState.value == value) return
        val previous = mutableState.value
        if (previous.playing != value.playing || previous.playWhenReady != value.playWhenReady ||
            previous.suppression != value.suppression || previous.loading != value.loading ||
            (previous.error == null) != (value.error == null)) {
            recordPlaybackEvent("state playing=${value.playing} desired=${value.playWhenReady}" +
                " suppression=${value.suppression} loading=${value.loading} error=${value.error != null}")
        }
        mutableState.value = value
        saveListeningSession(immediate = previous.songId != value.songId || previous.playing != value.playing ||
            previous.playWhenReady != value.playWhenReady || previous.sourceMode != value.sourceMode ||
            previous.repeatMode != value.repeatMode || previous.shuffleEnabled != value.shuffleEnabled ||
            previous.currentQueueSongId != value.currentQueueSongId || previous.queueOrder !== value.queueOrder)
    }

    private fun emptyPlaybackState(): AudioPlaybackState {
        val queue = queueController.snapshot()
        return AudioPlaybackState(
            repeatMode = queue.repeatMode.label,
            shuffleEnabled = queue.shuffleEnabled,
            canGoPrevious = manualNavigationAvailable(QueueTransition.EXPLICIT_PREVIOUS),
            canGoNext = manualNavigationAvailable(QueueTransition.EXPLICIT_NEXT),
            queueOrder = queue.order,
            currentQueueSongId = queue.currentId
        )
    }

    private fun committedControls(): NativeAudioPlaybackControlSnapshot? =
        lastAdapterState.committedControls

    private fun requestPlaybackAudioFocus(): Boolean {
        if (focusOwner.audioFocusHeld) return focusOwner.request()
        ensurePlaybackService()
        val foreground = PlaybackService.prepareForPlaybackFocus()
        recordPlaybackEvent("focus-foreground=$foreground")
        val accepted = focusOwner.request()
        if (!accepted) PlaybackService.releaseDeniedPlaybackFocus()
        return accepted
    }

    private fun abandonPlaybackAudioFocus(): Unit = focusOwner.abandon()

    internal fun handleAudioFocusChange(change: Int, origin: String = "platform"): Unit = focusOwner.onChange(change, origin)

    private fun ensurePlaybackService() = platform.ensureService(!playbackCommandsBlocked())

    private fun rebuildQueueMap(songs: List<Song>) {
        queueById.clear()
        songs.forEach { song -> queueById[song.id] = song }
        preloadQueueSettings(songs)
    }

    private fun putQueueSong(song: Song) {
        val previous = queueById[song.id]
        queueById[song.id] = song
        if (previous != song || settingsPreloadJob == null) preloadQueueSettings(queueById.values.toList())
    }

    private fun trimStemCandidates() {
        while (stemCandidates.size > MAXIMUM_STEM_CANDIDATES) {
            val installed = lastAdapterState.publication.committed?.stems?.installed?.packageSignature
            val removable = stemCandidates.keys.firstOrNull { it != installed } ?: return
            stemCandidates.remove(removable)
        }
    }

    companion object {
        const val MINIMUM_LOOP_DURATION_MS = PlaybackLoopValidation.minimumDurationMs
        /** StemWise's shared Original-and-source time-stretch window. */
        const val MINIMUM_TEMPO = MINIMUM_PLAYBACK_TEMPO
        const val MAXIMUM_TEMPO = MAXIMUM_PLAYBACK_TEMPO

        private const val TAG = "StemWiseNativePlayback"
        private const val BACKGROUND_POSITION_PUBLICATION_FALLBACK_MS = 250L
        private const val FAILED_SELECTION_FOCUS_HANDOFF_MS = 500L
        private const val MAXIMUM_STEM_CANDIDATES = 4
    }
}

private fun AudioPlaybackState.withoutPosition(): AudioPlaybackState = copy(positionMs = 0L)

/**
 * Decoder-bank previews are intentionally slower than visual pointer frames. At 64 ms, an active
 * source graph receives at most about sixteen coordinated seeks per second, while the
 * terminal scrub command remains exact and synchronous.
 */
private fun EngineSourceMode.toPublicSourceMode(): PlaybackSourceMode = when (this) {
    EngineSourceMode.ORIGINAL -> PlaybackSourceMode.ORIGINAL
    EngineSourceMode.STEMS -> PlaybackSourceMode.STEMS
}

/**
 * Translates queue policy into an engine plan without turning Repeat One into a second owner.
 */
internal fun automaticEndPlanForDecision(
    decision: QueueDecision,
    currentIdentity: EngineAudioIdentity,
    fallbackNext: NativeEngineQueueItem? = null,
    resolveNextOwner: (String) -> NativeEngineQueueItem?
): NativeEngineAutomaticEndPlan? = when {
    decision is QueueDecision.Move -> resolveNextOwner(decision.mediaId)?.let {
        if (it.identity == currentIdentity) null else NativeEngineAutomaticEndPlan.NextOwner(it)
    }
    decision is QueueDecision.Restart && decision.mediaId == currentIdentity.songId &&
        currentIdentity.playbackEndMs - currentIdentity.playbackStartMs >= PlaybackLoopValidation.minimumDurationMs -> NativeEngineAutomaticEndPlan.RestartCurrent(
            identity = currentIdentity,
            // Retained for queue-policy validation/UI only. Native Repeat One ignores metadata
            // duration and resolves its endpoint from Original extent and the playback window.
            metadataDurationHintMs = currentIdentity.durationMs,
            fallbackNext = fallbackNext
        )
    else -> null
}

private fun EngineStemControl.toStemMixState() = StemMixState(gain, muted, solo)

private fun StemMixState.toEngineControl() = EngineStemControl(volume, muted, solo)

private fun effectiveStemGains(controls: Map<String, EngineStemControl>): Map<String, Float> {
    val soloed = controls.values.any { it.solo }
    return controls.mapValues { (_, value) ->
        if (value.muted || (soloed && !value.solo)) 0f else value.gain
    }
}

internal fun incrementRevision(value: Long, label: String): Long {
    check(value < Long.MAX_VALUE) { "$label revision space exhausted" }
    return value + 1L
}

private fun Long?.asResourceCount(): Int = this
    ?.coerceAtMost(Int.MAX_VALUE.toLong())
    ?.toInt()
    ?: 0
