package app.stemwise

import android.content.Context
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.LibrarySessionCoordinator
import app.stemwise.data.PlaylistRepository
import app.stemwise.data.PlaylistCandidate
import app.stemwise.data.PlaylistCommitMode
import app.stemwise.data.StemwisePreferences
import app.stemwise.data.SongWorkCoordinator
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.ColdOpenCoordinator
import app.stemwise.data.ExternalLibraryRepository
import app.stemwise.data.CanonicalPackagePublisher
import app.stemwise.data.ExternalPackageTrash
import app.stemwise.data.PermanentDeletionCoordinator
import app.stemwise.data.SourceBindingRegistry
import app.stemwise.analysis.ImportedAnalysisRepository
import app.stemwise.enrichment.SongEnrichmentImporter
import app.stemwise.enrichment.EnrichmentImportCoordinator
import app.stemwise.integration.YoutubeDlEngine
import app.stemwise.integration.MvsepAccount
import app.stemwise.integration.SeparationRepository
import app.stemwise.integration.ShareIntentCoordinator
import app.stemwise.integration.PlaylistCommitAuthorization
import app.stemwise.playback.AudioPlayback
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.work.ManualTaskCoordinator
import app.stemwise.network.AndroidAppNetworkAccess
import app.stemwise.network.AppNetworkAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StemwiseServices internal constructor(
    context: Context,
    internal val networkAccess: AppNetworkAccess,
    private val database: LibraryDatabase = LibraryDatabase.get(context)
) {
    private val playlistCommitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val backgroundWorkCoordinator = BackgroundWorkCoordinator()
    val shareIntents = ShareIntentCoordinator()
    private val packageMutationCoordinator =
        PackageMutationCoordinator(context.applicationContext)
    private val songWorkCoordinator = SongWorkCoordinator()
    val libraryRootManager = LibraryRootManager(
        context.applicationContext,
        packageMutationCoordinator,
        database.libraryDao()
    )
    val sourceBindingRegistry = SourceBindingRegistry(
        context = context.applicationContext,
        roots = libraryRootManager,
        mutations = packageMutationCoordinator,
        dao = database.libraryDao()
    )
    val externalLibraryRepository = ExternalLibraryRepository(
        context = context.applicationContext,
        roots = libraryRootManager,
        dao = database.libraryDao(),
        sourceBindings = sourceBindingRegistry,
        mutations = packageMutationCoordinator,
        backgroundWorkCoordinator = backgroundWorkCoordinator
    )
    val externalPackageTrash = ExternalPackageTrash(
        context = context.applicationContext,
        roots = libraryRootManager,
        mutations = packageMutationCoordinator,
        sourceBindings = sourceBindingRegistry,
        dao = database.libraryDao()
    )
    val permanentDeletion = PermanentDeletionCoordinator(
        trash = externalPackageTrash,
        externalLibrary = externalLibraryRepository,
        songWorkCoordinator = songWorkCoordinator
    )
    val preferences = StemwisePreferences(
        context,
        initialLibraryId = libraryRootManager.state.value.libraryId
    )
    val engine = YoutubeDlEngine(context, networkAccess, libraryRootManager)
    val mvsepAccount = MvsepAccount(context, networkAccess)
    val canonicalPackagePublisher = CanonicalPackagePublisher(
        context = context.applicationContext,
        roots = libraryRootManager,
        dao = database.libraryDao(),
        mutations = packageMutationCoordinator,
        sourceBindings = sourceBindingRegistry,
        externalLibrary = externalLibraryRepository,
        backgroundWorkCoordinator = backgroundWorkCoordinator
    )
    val enrichmentImporter = SongEnrichmentImporter(
        context = context.applicationContext,
        roots = libraryRootManager,
        mutations = packageMutationCoordinator,
        sourceBindings = sourceBindingRegistry,
        backgroundWorkCoordinator = backgroundWorkCoordinator,
        originalPublisher = { canonicalPackagePublisher }
    )
    val enrichmentImports = EnrichmentImportCoordinator(
        context = context.applicationContext,
        importer = enrichmentImporter,
        externalLibrary = externalLibraryRepository,
        backgroundWorkCoordinator = backgroundWorkCoordinator,
        roots = libraryRootManager
    )
    val songBridge = app.stemwise.bridge.SongBridgeRepository(
        context.applicationContext, libraryRootManager, enrichmentImporter, externalLibraryRepository, database.libraryDao(),
        backgroundWorkCoordinator,
        transportFor = { app.stemwise.bridge.AndroidSongBridgeTransport(context.applicationContext, it, networkAccess) },
        producerReady = ::bridgeProducerReady,
        blockedIds = {
            val root = libraryRootManager.captureHandle() ?: error("ROOT_UNAVAILABLE")
            sourceBindingRegistry.confirmedDeletedPackageIds(root)
        },
        prepareExplicitReadd = { root, videoId ->
            songWorkCoordinator.withSong(videoId) { externalPackageTrash.prepareExplicitReadd(root, videoId).getOrThrow() }
        }
    )
    val playlistRepository = PlaylistRepository(
        context = context.applicationContext,
        database = database,
        dao = database.libraryDao(),
        preferences = preferences,
        engine = engine,
        songWorkCoordinator = songWorkCoordinator,
        externalLibrary = externalLibraryRepository,
        roots = libraryRootManager,
        backgroundWorkCoordinator = backgroundWorkCoordinator,
        networkAccess = networkAccess,
        onPlaylistCommitted = { songBridge.queuePlaylistSongs(it) }
    )
    val separationRepository = SeparationRepository(
        context = context.applicationContext,
        dao = database.libraryDao(),
        account = mvsepAccount,
        roots = libraryRootManager,
        mutations = packageMutationCoordinator,
        songWorkCoordinator = songWorkCoordinator,
        externalLibrary = externalLibraryRepository,
        canonicalPublisher = canonicalPackagePublisher,
        backgroundWorkCoordinator = backgroundWorkCoordinator,
        engine = engine,
        networkAccess = networkAccess
    )
    val analysisRepository = ImportedAnalysisRepository(
        context = context.applicationContext,
        roots = libraryRootManager
    )
    val audioPlayback = AudioPlayback(
        context = context,
        repository = playlistRepository,
        separationRepository = separationRepository,
        roots = libraryRootManager,
        analysisRepository = analysisRepository
    )
    val coldOpenCoordinator = ColdOpenCoordinator(
        roots = libraryRootManager,
        externalLibrary = externalLibraryRepository,
        repository = playlistRepository,
        preferences = preferences,
        externalPackageTrash = externalPackageTrash,
        separationRepository = separationRepository,
        enrichmentImports = enrichmentImports,
        enrichmentImporter = enrichmentImporter
    )
    val manualTasks = ManualTaskCoordinator(
        context = context.applicationContext,
        separationRepository = separationRepository,
        roots = libraryRootManager,
        coldOpen = coldOpenCoordinator,
        preferences = preferences
    )
    val librarySessionCoordinator = LibrarySessionCoordinator(
        context = context.applicationContext,
        roots = libraryRootManager,
        preferences = preferences,
        playlistRepository = playlistRepository,
        externalLibrary = externalLibraryRepository,
        enrichmentImports = enrichmentImports,
        enrichmentImporter = enrichmentImporter,
        separationRepository = separationRepository,
        audioPlayback = audioPlayback,
        coldOpen = coldOpenCoordinator,
        songWorkCoordinator = songWorkCoordinator,
        manualTasks = manualTasks,
        bridgeSongs = songBridge
    )

    init {
        coldOpenCoordinator.setReadyListener {
            manualTasks.onColdOpenFinished()
            songBridge.requestResume()
        }
    }

    internal fun resumeBridgeTasks() {
        songBridge.requestResume()
    }

    private fun bridgeProducerReady(root: app.stemwise.data.RootHandle): Boolean =
        coldOpenCoordinator.isProducerAdmissionReady(root)

    /**
     * Owns the full claimed-offer lifecycle outside Activity/Compose cancellation. Every claim
     * terminally becomes a success/failure Notice even if the UI is recreated mid-commit.
     */
    internal fun submitClaimedPlaylistCommit(
        candidate: PlaylistCandidate,
        authorization: PlaylistCommitAuthorization
    ): Boolean {
        if (!authorization.consumeForExecution()) return false
        playlistCommitScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = runCatching {
                librarySessionCoordinator.commitPlaylist(
                    candidate = candidate,
                    mode = PlaylistCommitMode.KEEP_AND_ADD,
                    authorization = authorization
                )
            }.getOrElse { Result.failure(it) }
            result.fold(
                onSuccess = { summary ->
                    authorization.complete(
                        message = "Playlist connected · ${summary.discovered} songs" +
                            if (summary.projectionRefreshPending) ". Library refresh is pending; reopen or retry Sync." else "",
                        playlistCommitted = true
                    )
                },
                onFailure = {
                    val recoveryRequired = !libraryRootManager.state.value.ready
                    authorization.complete(
                        message = if (recoveryRequired) {
                            "Playlist recovery needs attention. Reopen StemWise before trying again."
                        } else {
                            "Could not connect this playlist. Nothing was changed."
                        },
                        playlistCommitted = false
                    )
                }
            )
        }
        return true
    }
}
