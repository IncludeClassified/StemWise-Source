package app.stemwise.ui

import android.graphics.Rect
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.documentfile.provider.DocumentFile
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.enrichment.InstalledMediaIdentity
import app.stemwise.enrichment.createInstalledEnrichmentDocument
import app.stemwise.enrichment.enrichmentRevisionIdentity
import org.json.JSONObject
import java.io.File
import java.util.UUID
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.stemwise.StemwiseApplication
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.PlaybackContentFixture
import app.stemwise.playback.DisposablePlaybackTargetRule
import app.stemwise.playback.resetForInstrumentationIsolation
import app.stemwise.playback.instrumentationIsolationDiagnostics
import app.stemwise.playback.requireDisposablePlaybackTarget
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.ui.player.surface.PlayerSurfaceBounds
import app.stemwise.ui.player.surface.PlayerSurfaceView
import app.stemwise.ui.theme.StemwiseTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Physical regression for the production layer ordering missed by the direct native selector.
 *
 * This mounts the real [PlayerScreen], real [PlayerSurfaceView], production semantics geometry,
 * and the same Material sheet callback boundary used by StemwiseApp. Pointer events enter through
 * the mounted hierarchy; no action sink is invoked directly and no MainActivity startup is used.
 */
@OptIn(ExperimentalMaterial3Api::class)
class CompactPlayerProductionIntegrationTest {
    @get:Rule(order = 0)
    val disposablePlaybackTarget: org.junit.rules.TestRule = DisposablePlaybackTargetRule()

    @get:Rule(order = 1)
    val compose = createStemwiseComposeRule()

    private val application: StemwiseApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as StemwiseApplication

    private lateinit var song: Song
    private var ownedPackage: DocumentFile? = null
    private var ownedSettings: SongPlaybackSettingsIdentity? = null

    @Before
    fun registerSyntheticPlayback() {
        application.services.audioPlayback.resetForInstrumentationIsolation(InstrumentationRegistry.getInstrumentation())
        runBlocking { withTimeout(8_000L) {
            val roots = application.services.libraryRootManager
            if (!roots.state.value.ready) roots.initialize(application.services.preferences)
            if (!roots.state.value.ready) {
                val tree = GoldenQaDocumentsProvider.treeUri
                application.grantUriPermission(application.packageName, tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
            }
            assertTrue("Disposable Library did not become ready", roots.state.value.ready)
        } }
        val root = checkNotNull(application.services.libraryRootManager.captureHandle())
        val id = "CP" + UUID.randomUUID().toString().replace("-", "").take(9)
        val directory = checkNotNull(root.rootDocument.createDirectory(id)).also { ownedPackage = it }
        fun publish(name: String, mime: String, bytes: ByteArray): DocumentFile {
            val document = checkNotNull(directory.createFile(mime, name))
            checkNotNull(application.contentResolver.openOutputStream(document.uri, "w")).use { it.write(bytes) }
            return document
        }
        val originalBytes = application.assets.open("demo_library/DEMOUI00001/original.opus").use { it.readBytes() }
        val original = publish("original.opus", "audio/ogg", originalBytes)
        val coverBytes = application.assets.open("demo_library/DEMOUI00001/cover.png").use { it.readBytes() }
        val cover = publish("cover.png", "image/png", coverBytes)
        val originalHash = PlaybackContentFixture.sourceHash(application, original.uri.toString())
        val coverHash = PlaybackContentFixture.sourceHash(application, cover.uri.toString())
        val template = object : GoldenEnrichmentFixture() {
            fun document() = payload("2026-08-09T12:00:00Z", "Compact production boundary", includeArtwork = true)
        }.document()
        val incoming = JSONObject(template.toString().replace(template.getJSONObject("source").getString("videoId"), id))
        val installed = createInstalledEnrichmentDocument(incoming, originalHash,
            mapOf("original.opus" to InstalledMediaIdentity(originalHash, originalBytes.size.toLong())),
            1L, enrichmentRevisionIdentity(incoming, coverHash), incoming.getJSONObject("artwork"), coverHash, coverBytes.size)
        val manifest = publish("song.json", "application/json", installed.toString().toByteArray(Charsets.UTF_8))
        song = Song(id = id, videoId = id, title = "Compact production boundary", artist = "StemWise",
            durationMs = 12_000L, state = SongState.READY, accent = androidx.compose.ui.graphics.Color(0xFF55E6B1),
            artworkGlyph = "CP", sourcePath = PlaybackContentFixture.register(application, original.uri.toString()),
            artworkSource = cover.uri.toString(), sourceHash = originalHash, sourceCodec = "opus", sourceContainer = "ogg",
            audioRevision = originalHash, mediaRevision = PlaybackContentFixture.sourceHash(application, manifest.uri.toString()))
        ownedSettings = SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), id, originalHash)
        runBlocking { withTimeout(8_000L) {
            val presentation = application.services.analysisRepository.presentation(id, song.mediaRevision, originalHash)
            assertTrue("Untimed fixture must not claim verified beats", presentation.timeline.beats.isEmpty())
        } }
        compose.runOnIdle { application.services.audioPlayback.setPlaybackQueue(listOf(song), snapshotReady = true) }
        compose.waitUntil(5_000L) {
            application.services.libraryRootManager.state.value.ready &&
                application.services.audioPlayback.cachedSongPlaybackSettings(song) != null
        }
    }

    @After
    fun releaseSyntheticPlaybackIntent() {
        application.services.audioPlayback.resetForInstrumentationIsolation(InstrumentationRegistry.getInstrumentation())
        PlaybackContentFixture.clear(application)
        ownedSettings?.let { SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).clear(it) }
        ownedPackage?.let { check(it.delete()) { "Could not remove compact-player fixture ${it.uri}" } }
        ownedPackage = null
        ownedSettings = null
    }

    @Test fun preparedQueuePublishesCoherentFirstIncomingFramesWithoutReopeningPresentationDocuments() {
        val roots = checkNotNull(application.services.libraryRootManager.captureHandle())
        val playback = application.services.audioPlayback
        val analysis = application.services.analysisRepository
        val extraPackages = mutableListOf<DocumentFile>()
        val extraSettings = mutableListOf<SongPlaybackSettingsIdentity>()
        val manifests = mutableMapOf<DocumentFile, ByteArray>()
        val store = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        var frameListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
        try {
            val songs = listOf(song) + (1..2).map { index ->
                val id = "QP" + UUID.randomUUID().toString().replace("-", "").take(9)
                val directory = checkNotNull(roots.rootDocument.createDirectory(id)).also(extraPackages::add)
                fun publish(name: String, mime: String, bytes: ByteArray): DocumentFile {
                    val file = checkNotNull(directory.createFile(mime, name))
                    checkNotNull(application.contentResolver.openOutputStream(file.uri, "w")).use { it.write(bytes) }
                    return file
                }
                val original = publish("original.opus", "audio/ogg", application.assets.open("demo_library/DEMOUI00001/original.opus").use { it.readBytes() })
                val cover = publish("cover.png", "image/png", application.assets.open("demo_library/DEMOUI00001/cover.png").use { it.readBytes() })
                val template = JSONObject(application.assets.open("demo_library/DEMOUI00001/song.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() })
                template.getJSONObject("analysis").getJSONObject("rhythm").apply {
                    // An independently authored 500ms grid is exactly120 BPM at every interval.
                    put("bpm", 120).put("alternativeBpms", org.json.JSONArray(listOf(60, 240)))
                    put("beats", org.json.JSONArray().also { beats -> repeat(24) { beat ->
                        beats.put(JSONObject().put("timeMs", beat * 500L).put("isDownbeat", beat % 4 == 0)
                            .put("beatInBar", beat % 4 + 1).put("barIndex", beat / 4))
                    } })
                    put("tempoSegments", org.json.JSONArray().put(JSONObject().put("startMs", 0L)
                        .put("endMs", 12_000L).put("bpm", 120)))
                    put("meterSegments", org.json.JSONArray().put(JSONObject().put("startMs", 0L)
                        .put("endMs", 12_000L).put("numerator", 4).put("denominator", 4)))
                }
                val bytes = app.stemwise.demo.uiDemoLibraryManifest(template.toString(), "DEMOUI00001", id, index)
                val manifest = publish("song.json", "application/json", bytes)
                manifests[manifest] = bytes
                val hash = PlaybackContentFixture.sourceHash(application, original.uri.toString())
                extraSettings += SongPlaybackSettingsIdentity(roots.libraryId, roots.treeUri.toString(), id, hash)
                song.copy(id = id, videoId = id, title = "Prepared target $index", sourceHash = hash,
                    sourcePath = PlaybackContentFixture.register(application, original.uri.toString()),
                    artworkSource = cover.uri.toString(), audioRevision = hash,
                    mediaRevision = PlaybackContentFixture.sourceHash(application, manifest.uri.toString()))
            }
            val preferences = songs.mapIndexed { index, current ->
                val identity = SongPlaybackSettingsIdentity(roots.libraryId, roots.treeUri.toString(), current.id, checkNotNull(current.sourceHash))
                val settings = app.stemwise.data.SongPlaybackSettings(
                    trim = app.stemwise.data.SongTrimRange(1_000L, 11_000L),
                    loop = app.stemwise.data.SongLoopPreference(2_000L, 6_000L, true),
                    tempo = listOf(.75f, 1.25f, 1.5f)[index], semitones = listOf(-2, 3, 5)[index])
                store.update(identity, current.durationMs) { settings }
                runBlocking { playback.savedSongTrim(current) }
                current.id to settings
            }.toMap()
            compose.runOnIdle { checkNotNull(playback.startQueue(songs, shuffle = false, autoplay = false)) }
            compose.waitUntil(8_000L) {
                val native = playback.adapter.snapshot()
                playback.state.value.songId == songs[0].id && !playback.state.value.loading &&
                    native.publication.committed?.selection?.songId == songs[0].id &&
                    native.publication.committed?.selection?.token == native.latestRequest?.selection?.token &&
                    songs.all { analysis.preparedPresentation(it)?.isSuccess == true }
            }
            val prepared = songs.associate { it.id to checkNotNull(analysis.preparedPresentation(it)).getOrThrow() }
            songs.drop(1).forEach { assertTrue(prepared.getValue(it.id).timeline.beats.size >= 2) }
            // Every compact queue projection must survive later provider unavailability and shuffle.
            manifests.keys.forEach { check(it.delete()); check(!it.exists()) }
            val shown = androidx.compose.runtime.mutableStateOf(songs.first())
            compose.setContent { StemwiseTheme {
                val session = playback.sessionState.collectAsStateWithLifecycle().value
                val sheetSong = songs.firstOrNull { it.id == session.songId } ?: shown.value
                PlayerScreen(sheetSong, {}, library = songs, audioPlayback = playback,
                    separationRepository = application.services.separationRepository, analysisRepository = analysis,
                    onSongChanged = { shown.value = it },
                    onDeleteSong = { Result.failure(IllegalStateException("Outside fixture")) }, modifier = Modifier.fillMaxSize())
            } }
            val surface = awaitCompactSurface()
            val firstFrames = java.util.concurrent.ConcurrentHashMap<app.stemwise.ui.player.surface.PlayerSurfaceOwnerKey,
                app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot>()
            val missingPlayerFrames = AtomicInteger(0)
            val firstMissingPlayerFrame = java.util.concurrent.atomic.AtomicReference<String?>(null)
            compose.runOnIdle {
                frameListener = android.view.ViewTreeObserver.OnPreDrawListener {
                    val live = findPlayerSurface(compose.activity.window.decorView)
                    val snapshot = live?.let {
                        (it.javaClass.getDeclaredField("fullPlayerState").apply { isAccessible = true }
                            .get(it) as app.stemwise.ui.player.nativeplayer.FullPlayerStateStore).snapshot
                    }
                    if (snapshot == null) {
                        missingPlayerFrames.incrementAndGet()
                        firstMissingPlayerFrame.compareAndSet(null,
                            "view=${live?.let(System::identityHashCode)}; session=${playback.sessionState.value}")
                    } else firstFrames.putIfAbsent(snapshot.owner, snapshot)
                    true
                }.also { compose.activity.window.decorView.viewTreeObserver.addOnPreDrawListener(it) }
                surface.invalidate()
            }
            fun assertPreparedFrame(frame: app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot) {
                val id = frame.owner.songId
                assertEquals("First incoming frame lost its saved trim", 10_000L, frame.timeline.durationMs)
                assertEquals("First incoming frame lost A", 1_000L, frame.timeline.loopStartMs)
                assertEquals("First incoming frame lost B", 5_000L, frame.timeline.loopEndMs)
                assertTrue(frame.toolDetail.activity.tuneActive)
                assertTrue(frame.toolDetail.activity.speedActive)
                assertEquals("First incoming frame lost timing capability", id != songs.first().id,
                    frame.toolDetail.activity.metronomeAvailable)
            }
            fun awaitFirst(id: String) {
                try {
                    compose.waitUntil(8_000L) { firstFrames.values.maxByOrNull { it.owner.generation }?.owner?.songId == id }
                } catch (failure: Throwable) {
                    val rendered = surface.javaClass.getDeclaredField("fullPlayerState").apply { isAccessible = true }
                        .get(surface) as app.stemwise.ui.player.nativeplayer.FullPlayerStateStore
                    var liveSurface: PlayerSurfaceView? = null
                    compose.runOnIdle { liveSurface = findPlayerSurface(compose.activity.window.decorView) }
                    val liveRendered = liveSurface?.let { live ->
                        live.javaClass.getDeclaredField("fullPlayerState").apply { isAccessible = true }
                            .get(live) as app.stemwise.ui.player.nativeplayer.FullPlayerStateStore
                    }
                    val native = playback.adapter.snapshot()
                    throw AssertionError("Expected incoming renderer $id; owner=${rendered.snapshot?.owner}; " +
                        "capturedView=${System.identityHashCode(surface)}/${surface.isAttachedToWindow}; " +
                        "liveView=${liveSurface?.let(System::identityHashCode)}/${liveSurface?.isAttachedToWindow}; " +
                        "liveOwner=${liveRendered?.snapshot?.owner}; session=${playback.sessionState.value}; " +
                        "visualRequest=${rendered.snapshot?.visualSelectionSongId}; queue=${playback.currentQueueSongId()}; " +
                        "request=${native.latestRequest?.selection}; committed=${native.publication.committed?.selection}", failure)
                }
                val frame = firstFrames.values.filter { it.owner.songId == id }.maxBy { it.owner.generation }
                assertPreparedFrame(frame)
                val expected = preferences.getValue(id)
                fun inspectControl(tool: String, description: String, value: Float) {
                    val toolbar = compose.onNode(androidx.compose.ui.test.hasContentDescription(tool) and
                        androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.Role,
                            androidx.compose.ui.semantics.Role.Button))
                    try {
                        compose.waitUntil(5_000L) {
                            runCatching {
                                toolbar.assertIsDisplayed().assertIsEnabled()
                            }.isSuccess
                        }
                    } catch (failure: Throwable) {
                        val nodes = compose.onAllNodesWithContentDescription(tool).fetchSemanticsNodes().map { node ->
                            "role=${if (node.config.contains(SemanticsProperties.Role)) node.config[SemanticsProperties.Role] else null}, " +
                                "disabled=${node.config.contains(SemanticsProperties.Disabled)}, bounds=${node.boundsInRoot}"
                        }
                        val native = playback.adapter.snapshot()
                        val current = songs.first { it.id == id }
                        val profile = surface.javaClass.getDeclaredField("fullPlayerState").apply { isAccessible = true }
                            .get(surface) as app.stemwise.ui.player.nativeplayer.FullPlayerStateStore
                        throw AssertionError("Incoming $id did not expose its visible enabled $tool control; nodes=$nodes; " +
                            "UI=${profile.snapshot?.owner}; lease=${playback.controlLease.value}; " +
                            "request=${native.latestRequest?.selection}; committed=${native.publication.committed?.selection}; " +
                            "settingsAuthoritative=${playback.currentSongPlaybackSettingsAreAuthoritative(current)}; " +
                            "prepared=${analysis.preparedPresentation(current)?.isSuccess}; playback=${playback.state.value.songId}; " +
                            "geometry=${surface.semanticsGeometry()}", failure)
                    }
                    toolbar.performClick()
                    compose.waitUntil(5_000L) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size == 1 }
                    assertEquals("The first opened $tool control lost the incoming saved value", value,
                        compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode()
                            .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current, 0f)
                    toolbar.performClick()
                    try {
                        compose.waitUntil(5_000L) { compose.onAllNodesWithContentDescription("Next track").fetchSemanticsNodes().size == 1 }
                    } catch (failure: Throwable) {
                        val snapshot = surface.javaClass.getDeclaredField("fullPlayerState").apply { isAccessible = true }
                            .get(surface) as app.stemwise.ui.player.nativeplayer.FullPlayerStateStore
                        throw AssertionError("Closing $tool for $id left Next count=" +
                            compose.onAllNodesWithContentDescription("Next track").fetchSemanticsNodes().size +
                            "; native owner=${snapshot.snapshot?.owner}, detail=${snapshot.snapshot?.toolDetail}, " +
                            "geometry=${surface.semanticsGeometry()}", failure)
                    }
                }
                inspectControl(PLAYER_DETAIL_TUNE, "Key adjustment", expected.semitones.toFloat())
                if (id == songs.first().id) inspectControl(PLAYER_DETAIL_METRONOME, "Tempo adjustment", expected.tempo)
                else inspectControl(PLAYER_DETAIL_METRONOME, "Metronome BPM", 120f * expected.tempo)
            }
            awaitFirst(songs[0].id)
            compose.onNodeWithContentDescription("Next track").performClick(); awaitFirst(songs[1].id)
            compose.onNodeWithContentDescription("Next track").performClick(); awaitFirst(songs[2].id)
            var previousPairMs = -1L
            try {
                compose.waitUntil(8_000L) { playback.state.value.let {
                    it.songId == songs[2].id && !it.loading && !it.playWhenReady
                } && playback.currentQueueSongId() == songs[2].id }
                // The actual Previous contract is restart, then skip on a second press within2s.
                // Separate performClick calls may spend that window waiting for asynchronous idle.
                compose.onNodeWithContentDescription("Previous track or restart")
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { activate ->
                        val started = android.os.SystemClock.elapsedRealtime()
                        assertTrue("First Previous press was rejected", activate())
                        assertTrue("Second Previous press was rejected", activate())
                        previousPairMs = android.os.SystemClock.elapsedRealtime() - started
                        assertTrue("Previous pair missed its real two-second window", previousPairMs <= 2_000L)
                    }
                compose.waitUntil(8_000L) { playback.state.value.songId == songs[1].id }
            } catch (failure: Throwable) {
                val native = playback.adapter.snapshot()
                throw AssertionError("Previous pair elapsed=$previousPairMs; expected=${songs[1].id}; " +
                    "queue=${playback.currentQueueSongId()}; UI=${shown.value.id}; state=${playback.state.value}; " +
                    "request=${native.latestRequest?.selection}; committed=${native.publication.committed?.selection}", failure)
            }
            awaitFirst(songs[1].id)
            var shuffledTarget = ""
            compose.runOnIdle {
                assertTrue(playback.setRepeatMode("All"))
                assertTrue(playback.setShuffle(true, 17L))
                repeat(7) { shuffledTarget = checkNotNull(playback.nextTrack()) }
            }
            // The live session drives this parent exactly as StemwiseApp does. Assert the actual
            // renderer and saved controls rather than the separate UI callback cursor.
            awaitFirst(shuffledTarget)
            assertEquals("Prepared navigation lost its physical player: ${firstMissingPlayerFrame.get()}",
                0, missingPlayerFrames.get())
            firstFrames.values.forEach(::assertPreparedFrame)
            songs.forEach { current ->
                org.junit.Assert.assertSame("Queue navigation must not reopen or evict prepared details",
                    prepared.getValue(current.id), analysis.preparedPresentation(current)?.getOrThrow())
            }
            songs.drop(1).forEach { current ->
                org.junit.Assert.assertNull(analysis.preparedPresentation(current.copy(sourceHash = "f".repeat(64))))
                org.junit.Assert.assertNull(analysis.preparedPresentation(current.copy(mediaRevision = "e".repeat(64))))
            }
        } finally {
            compose.runOnIdle {
                frameListener?.let { compose.activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(it) }
                compose.activity.setContent { }
            }
            playback.resetForInstrumentationIsolation(InstrumentationRegistry.getInstrumentation())
            extraSettings.forEach(store::clear)
            extraPackages.forEach { check(it.delete()) }
        }
    }

    @Test
    fun routeDrawKeepsNativeArtworkWithSharedSheetProgressBeforeTheEndpoint() {
        val playback = application.services.audioPlayback
        val route = androidx.compose.runtime.mutableFloatStateOf(0f)
        compose.runOnIdle { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
        compose.waitUntil(5_000L) {
            playback.state.value.songId == song.id && !playback.state.value.loading && playback.controlLease.value != null
        }
        compose.setContent {
            StemwiseTheme {
                PlayerScreen(song = song, onBack = {}, library = listOf(song), audioPlayback = playback,
                    separationRepository = application.services.separationRepository,
                    analysisRepository = application.services.analysisRepository,
                    onDeleteSong = { Result.failure(IllegalStateException("Outside this fixture")) },
                    expansionProgressState = route, modifier = Modifier.fillMaxSize())
            }
        }
        val surface = awaitCompactSurface()
        val nativeProgress = PlayerSurfaceView::class.java.getDeclaredField("expansionProgress").apply { isAccessible = true }
        var previousWidth = checkNotNull(surface.semanticsGeometry()).artwork.width
        for (fraction in listOf(.25f, .5f, .75f, 1f)) {
            compose.runOnIdle { route.floatValue = fraction }
            compose.waitUntil(5_000L) { nativeProgress.getFloat(surface) == fraction }
            compose.runOnIdle {
                val geometry = checkNotNull(surface.semanticsGeometry()) {
                    val fields = listOf("controlTop", "controlBottom", "centerArtworkTop", "centerArtworkBottom",
                        "centerIdentityTop", "centerIdentityBottom", "routeGeometry").associateWith { name ->
                        PlayerSurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(surface)
                    }
                    "Route $fraction withheld geometry: ${surface.width}x${surface.height}; $fields"
                }
                assertTrue("Route $fraction moved artwork outside the native surface", geometry.artwork.top >= 0f)
                assertTrue("Route $fraction moved its page outside the native surface", geometry.page.top >= 0f)
                val width = geometry.artwork.width
                assertTrue("Artwork stopped following the route at $fraction: $width <= $previousWidth", width > previousWidth)
                previousWidth = width
            }
        }
    }

    @Test
    fun shortPlayerScrollKeepsPhysicalToolsAndTheirSemanticBoundsTogetherAtLargeText() {
        val playback = application.services.audioPlayback
        val largeText = androidx.compose.runtime.mutableStateOf(false)
        compose.runOnIdle { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
        try {
            compose.waitUntil(5_000L) {
                playback.state.value.songId == song.id && !playback.state.value.loading &&
                    playback.controlLease.value != null
            }
        } catch (failure: Throwable) {
            throw AssertionError("Playback did not prepare: ${playback.instrumentationIsolationDiagnostics()}", failure)
        }
        compose.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(
                current.density, if (largeText.value) 2f else 1f)) {
                StemwiseTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(360.dp)) {
                            PlayerScreen(song, {}, audioPlayback = playback,
                                separationRepository = application.services.separationRepository,
                                analysisRepository = application.services.analysisRepository,
                                onDeleteSong = { Result.failure(IllegalStateException("Outside this fixture")) },
                                modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        for (scaled in listOf(false, true)) {
            compose.runOnIdle { largeText.value = scaled }
            try {
                compose.waitUntil(5_000L) {
                    compose.onAllNodesWithContentDescription(PLAYER_DETAIL_METRONOME).fetchSemanticsNodes().size == 1
                }
            } catch (failure: Throwable) {
                var details = ""
                compose.runOnIdle {
                    val surface = findPlayerSurface(compose.activity.window.decorView)
                    val fields = surface?.let { native -> listOf("liveSelectedTool", "expandedArtworkSizePx", "controlTop",
                        "controlBottom", "toolTop", "toolBottom", "renderedToolDetailHeightPx", "expansionProgress",
                        "controlsValid", "fontScale").associateWith { name ->
                        PlayerSurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(native)
                    } }
                    val motion = surface?.let { native -> PlayerSurfaceView::class.java.getDeclaredField("toolMotion")
                        .apply { isAccessible = true }.get(native) as app.stemwise.ui.player.surface.PlayerToolMotionOwner }
                    details = "scaled=$scaled rootReady=${application.services.libraryRootManager.state.value.ready} " +
                        "cachedSettings=${playback.cachedSongPlaybackSettings(song)} surface=${surface?.width}x${surface?.height} " +
                        "fields=$fields motion=${motion?.toolMotionSelectedTool}/${motion?.toolMotionActive}/${motion?.toolMotionEpoch} " +
                        "native=${surface?.semanticsGeometry()} playback=${playback.state.value}"
                }
                val count = compose.onAllNodesWithContentDescription(PLAYER_DETAIL_METRONOME).fetchSemanticsNodes().size
                throw AssertionError("Metronome tool unavailable: count=$count; $details", failure)
            }
            val speedTool = compose.onNodeWithContentDescription(PLAYER_DETAIL_METRONOME)
            speedTool.performScrollTo().assertIsDisplayed()
            lateinit var surface: PlayerSurfaceView
            compose.runOnIdle { surface = checkNotNull(findPlayerSurface(compose.activity.window.decorView)) }
            val semantic = speedTool.fetchSemanticsNode().boundsInRoot
            val physical = checkNotNull(surface.semanticsGeometry()).metronomeTool
            val location = IntArray(2)
            val origin = IntArray(2)
            compose.runOnIdle {
                surface.getLocationOnScreen(location)
                compose.activity.findViewById<View>(android.R.id.content).getLocationOnScreen(origin)
            }
            assertWithinOnePixel("Scrolled tool left", location[0] - origin[0] + physical.left, semantic.left)
            assertWithinOnePixel("Scrolled tool top", location[1] - origin[1] + physical.top, semantic.top)
            speedTool.performTouchInput { click(center) }
            // The native Choreographer clock settles after Compose becomes idle. Detail semantics
            // stay deliberately hidden until that exact tool epoch completes; await public input.
            try {
                compose.waitUntil(5_000L) {
                    compose.onAllNodesWithContentDescription("Tempo adjustment").fetchSemanticsNodes().size == 1
                }
            } catch (failure: Throwable) {
                var details = ""
                compose.runOnIdle {
                    val fields = listOf("liveSelectedTool", "expandedArtworkSizePx", "controlTop", "controlBottom",
                        "toolTop", "toolBottom", "renderedToolDetailHeightPx").associateWith { name ->
                        PlayerSurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(surface)
                    }
                    val motion = PlayerSurfaceView::class.java.getDeclaredField("toolMotion")
                        .apply { isAccessible = true }.get(surface) as app.stemwise.ui.player.surface.PlayerToolMotionOwner
                    details = "scaled=$scaled surface=${surface.width}x${surface.height} fields=$fields " +
                        "motion=${motion.toolMotionSelectedTool}/${motion.toolMotionActive}/${motion.toolMotionEpoch} " +
                        "native=${surface.semanticsGeometry()} playback=${playback.state.value}"
                }
                throw AssertionError("Tempo panel did not become interactive: $details", failure)
            }
            try {
                compose.onNodeWithContentDescription("Tempo adjustment").assertIsDisplayed()
            } catch (failure: Throwable) {
                val bounds = compose.onNodeWithContentDescription("Tempo adjustment").getUnclippedBoundsInRoot()
                val root = compose.onRoot().getUnclippedBoundsInRoot()
                var native = ""
                compose.runOnIdle {
                    val fields = listOf("expandedArtworkSizePx", "controlTop", "controlBottom", "toolTop", "toolBottom",
                        "renderedToolDetailHeightPx", "externalTransport", "fontScale").associateWith { name ->
                        PlayerSurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(surface)
                    }
                    native = "surface=${surface.width}x${surface.height} insets=${surface.rootWindowInsets} fields=$fields"
                }
                throw AssertionError("Tempo slider clipped: scaled=$scaled slider=$bounds root=$root $native", failure)
            }
            compose.onNodeWithContentDescription("Tempo adjustment").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(if (scaled) 1.25f else 1.5f) }
            compose.waitUntil(5_000L) { playback.state.value.tempo == if (scaled) 1.25f else 1.5f }
            // Return to the native tool owner through its actual pointer target.
            try {
                speedTool.assertIsDisplayed().performTouchInput { click(center) }
            } catch (failure: Throwable) {
                val bounds = runCatching { speedTool.getUnclippedBoundsInRoot() }.getOrNull()
                val root = compose.onRoot().getUnclippedBoundsInRoot()
                val scrolls = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                    .fetchSemanticsNodes().map { node ->
                        val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
                        "${node.boundsInRoot}: ${range.value()}/${range.maxValue()}"
                    }
                var native = ""
                compose.runOnIdle {
                    val visible = Rect()
                    val location = IntArray(2)
                    surface.getGlobalVisibleRect(visible)
                    surface.getLocationOnScreen(location)
                    val fields = listOf("controlTop", "controlBottom", "toolTop", "toolBottom",
                        "renderedToolDetailHeightPx", "externalTransport", "fontScale").associateWith { name ->
                        PlayerSurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(surface)
                    }
                    native = "surface=${surface.width}x${surface.height} at=${location.toList()} visible=$visible " +
                        "parent=${(surface.parent as? View)?.let { "${it.width}x${it.height}" }} fields=$fields " +
                        "tool=${surface.semanticsGeometry()?.metronomeTool}"
                }
                throw AssertionError("Metronome close clipped: scaled=$scaled tempo=${playback.state.value.tempo} " +
                    "tool=$bounds root=$root scrolls=$scrolls $native", failure)
            }
        }
    }

    @Test
    fun visibleClearLoopButtonClearsTheNativePlaybackRange() {
        requireDisposablePlaybackTarget()
        val playback = application.services.audioPlayback
        compose.runOnIdle { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
        compose.waitUntil(8_000L) {
            playback.state.value.songId == song.id && playback.state.value.loopAvailable
        }
        compose.runOnIdle {
            playback.pause()
            playback.seekTo(2_000L)
        }
        try {
            compose.waitUntil(8_000L) {
                !playback.state.value.playing && playback.state.value.loopAvailable &&
                    playback.state.value.positionMs == 2_000L
            }
        } catch (failure: Throwable) {
            val state = playback.state.value
            val native = playback.nativeDiagnosticsSnapshotForTest()
            throw AssertionError("Paused seek did not settle: playing=${state.playing}, " +
                "position=${state.positionMs}, loopAvailable=${state.loopAvailable}, " +
                "publicOwner=${native?.controllerPublicOwnership}, " +
                "loopOwner=${native?.controllerPublishedLoopOwnership}, " +
                "loopReady=${native?.controllerPublishedLoopAvailable}, " +
                "pendingSeek=${native?.controllerPendingSeekOwnership}, " +
                "cacheOwner=${native?.nativeRenderReadiness?.ownership}, " +
                "cacheBound=${native?.nativeRenderReadiness?.currentSongCacheBoundToSelectedOwner}, " +
                "cacheMask=${native?.nativeRenderReadiness?.currentSongCacheCompleteLaneMask}", failure)
        }
        compose.waitUntil(8_000L) {
            playback.nativeDiagnosticsSnapshotForTest()?.controllerOutputPhysicallyQuiesced == true
        }
        assertEquals("Paused seek drifted after warm callbacks stopped", 2_000L,
            playback.state.value.positionMs)
        compose.runOnIdle {
            val lease = checkNotNull(playback.acquireControlLease(song.id))
            assertTrue(playback.setRepeatMode("Off"))
            assertTrue(playback.setLoop(2_000L, 3_000L, lease))
            playback.play()
        }
        val harness = mountProductionPlayer()
        awaitCompactSurface()
        compose.onNodeWithTag("player_identity_${song.id}", useUnmergedTree = true)
            .performTouchInput { click(center) }
        harness.awaitExpanded(expectedExpansionCount = 1)
        compose.onNodeWithContentDescription("A-B loop").performClick()
        // The native motion endpoint restores actionable detail semantics after Compose is idle.
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithText("Clear loop").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Clear loop").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(3_000L) {
            playback.state.value.loopA == null && playback.state.value.loopB == null
        }
        compose.waitUntil(5_000L) {
            val position = playback.nativeDiagnosticsSnapshotForTest()?.runtime?.presentedPosition
            position != null && position.lastTimelineFrame > 4_000L * 48L
        }
        assertTrue(playback.state.value.playing)
    }

    @Test
    fun stoppedPlayerPositionCollectionResumesAtTheLatestPlaybackPosition() {
        requireDisposablePlaybackTarget()
        val playback = application.services.audioPlayback
        val screenOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        compose.runOnIdle {
            screenOwner.lifecycle.currentState = Lifecycle.State.STARTED
            playback.startQueue(listOf(song), shuffle = false, autoplay = false)
        }
        compose.waitUntil(5_000L) {
            playback.state.value.songId == song.id && !playback.state.value.loading &&
                playback.controlLease.value != null
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screenOwner) {
                StemwiseTheme {
                    PlayerScreen(
                        song = song,
                        onBack = {},
                        library = listOf(song),
                        audioPlayback = playback,
                        separationRepository = application.services.separationRepository,
                        analysisRepository = application.services.analysisRepository,
                        onDeleteSong = { Result.failure(IllegalStateException("Outside this fixture")) },
                        expansionProgress = 1f,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        fun visiblePosition() = compose.onAllNodesWithContentDescription(
            "Playback position", useUnmergedTree = true
        ).fetchSemanticsNodes().singleOrNull()?.config
            ?.get(SemanticsProperties.ProgressBarRangeInfo)?.current
        try {
            compose.runOnIdle { assertTrue(playback.seekTo(1_000L)) }
            compose.waitUntil(5_000L) { visiblePosition() == 1_000f }
            compose.runOnIdle { screenOwner.lifecycle.currentState = Lifecycle.State.CREATED }
            compose.runOnIdle { assertTrue(playback.seekTo(2_000L)) }
            compose.waitUntil(5_000L) { playback.state.value.positionMs == 2_000L }
            assertEquals("Stopped UI must retain its last collected position", 1_000f, checkNotNull(visiblePosition()), 0f)
            compose.runOnIdle { screenOwner.lifecycle.currentState = Lifecycle.State.STARTED }
            compose.waitUntil(5_000L) { visiblePosition() == 2_000f }
        } finally {
            compose.runOnIdle { screenOwner.lifecycle.currentState = Lifecycle.State.DESTROYED }
        }
    }

    @Test
    fun compactArtworkAndIdentityPhysicalCentersExpandExactlyOnceThroughRealSheet() {
        preparePausedHarnessSong()
        val harness = mountProductionPlayer()
        val surface = awaitCompactSurface()

        assertPhysicalBoundsMatchNative(
            tag = "player_identity_${song.id}",
            surface = surface,
            nativeBounds = requireNotNull(surface.semanticsGeometry()).identity
        )
        compose.onNodeWithTag("player_identity_${song.id}", useUnmergedTree = true)
            .performTouchInput { click(center) }
        harness.awaitExpanded(expectedExpansionCount = 1)
        assertEquals(
            1L,
            surface.compactActionDiagnosticsForQualification().openAcceptedCount
        )

        harness.collapse()
        val compactSurface = awaitCompactSurface()
        val actionsBeforeArtworkTap =
            compactSurface.compactActionDiagnosticsForQualification()
        assertPhysicalBoundsMatchNative(
            tag = "player_artwork_${song.id}",
            surface = compactSurface,
            nativeBounds = requireNotNull(compactSurface.semanticsGeometry()).artwork
        )
        compose.onNodeWithTag("player_artwork_${song.id}", useUnmergedTree = true)
            .performTouchInput { click(center) }
        harness.awaitExpanded(expectedExpansionCount = 2)

        val actions = compactSurface.compactActionDiagnosticsForQualification()
        assertEquals(actionsBeforeArtworkTap.openAttemptCount + 1L, actions.openAttemptCount)
        assertEquals(actionsBeforeArtworkTap.openAcceptedCount + 1L, actions.openAcceptedCount)
        assertEquals(2, harness.expansionCount.get())
    }

    @Test
    fun compactPlayDragCancelAndAccessibilityBoundsPreserveSeparateOwnership() {
        preparePausedHarnessSong()
        val harness = mountProductionPlayer()
        val surface = awaitCompactSurface()

        compose.onNodeWithContentDescription("Play", useUnmergedTree = true)
            .performTouchInput { click(center) }
        compose.runOnIdle {
            val actions = surface.compactActionDiagnosticsForQualification()
            assertEquals(1L, actions.miniPlayAttemptCount)
            assertEquals(1L, actions.miniPlayAcceptedCount)
            assertEquals(0L, actions.openAttemptCount)
            assertEquals(0, harness.expansionCount.get())
            assertEquals(SheetValue.PartiallyExpanded, harness.sheetState.currentValue)
        }

        // Mini Play can legitimately publish a new synthetic audio owner. Rebind this assertion
        // to the currently mounted native surface instead of retaining a superseded AndroidView.
        val gestureSurface = awaitCompactSurface()
        val openBaseline = gestureSurface.compactActionDiagnosticsForQualification()
        val identity = compose.onNodeWithTag(
            "player_identity_${song.id}",
            useUnmergedTree = true
        )
        identity.performTouchInput {
            down(center)
            moveTo(center + Offset(width * .30f, 0f))
            up()
        }
        compose.runOnIdle {
            assertEquals(0, harness.expansionCount.get())
            assertEquals(SheetValue.PartiallyExpanded, harness.sheetState.currentValue)
        }

        identity.performTouchInput {
            down(center)
            cancel()
        }
        compose.runOnIdle {
            assertEquals(0, harness.expansionCount.get())
            assertEquals(SheetValue.PartiallyExpanded, harness.sheetState.currentValue)
            val afterCancel = gestureSurface.compactActionDiagnosticsForQualification()
            assertEquals(openBaseline.openAttemptCount, afterCancel.openAttemptCount)
            assertEquals(openBaseline.openAcceptedCount, afterCancel.openAcceptedCount)
        }

        assertPhysicalBoundsMatchNative(
            tag = "player_identity_${song.id}",
            surface = gestureSurface,
            nativeBounds = requireNotNull(gestureSurface.semanticsGeometry()).identity
        )
        identity.performClick()
        harness.awaitExpanded(expectedExpansionCount = 1)
        compose.onNodeWithContentDescription("Collapse player", useUnmergedTree = true)
            .assertExists()
            .performClick()
        harness.awaitCollapsed()

        // Accessibility invokes the independent semantics projection, not the native physical
        // pager callback. Its sheet result is asserted separately from the already-proven physical
        // Mini Play and drag/cancel owners because sheet routing may replace the AndroidView.
        assertEquals(1, harness.expansionCount.get())
    }

    @Test
    fun acceptedArtworkSkipRemainsAbsentFromSemanticsThroughoutItsVisibleAndExitLifetime() {
        val playback = application.services.audioPlayback
        compose.runOnIdle { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
        compose.waitUntil(5_000L) { playback.state.value.songId == song.id &&
            !playback.state.value.loading && playback.controlLease.value != null }
        val harness = mountProductionPlayer()
        awaitCompactSurface()
        compose.onNodeWithTag("player_identity_${song.id}", useUnmergedTree = true).performClick()
        harness.awaitExpanded(expectedExpansionCount = 1)
        compose.mainClock.autoAdvance = false
        try {
            val artwork = compose.onNodeWithTag("player_artwork_${song.id}", useUnmergedTree = true)
                .fetchSemanticsNode()
            val forward = artwork.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
                .single { it.label == "Forward 5 seconds" }
            compose.runOnIdle { assertTrue(forward.action()) }
            compose.waitUntil(5_000L) { playback.state.value.positionMs >= 4_500L }
            var retainedFeedbackId: Int? = null
            // Include the visible interval, the 650ms dismissal and its retained exit frames.
            for (elapsed in listOf(16L, 100L, 400L, 200L, 100L, 1_000L)) {
                compose.mainClock.advanceTimeBy(elapsed)
                // The public merged projection must never expose a second announcement.
                compose.onAllNodesWithText("+5 sec").assertCountEquals(0)
                compose.onAllNodesWithText("−5 sec").assertCountEquals(0)
                val unmerged = compose.onAllNodesWithText("+5 sec", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                if (unmerged.isNotEmpty()) {
                    assertEquals("The decorative response was duplicated", 1, unmerged.size)
                    retainedFeedbackId = unmerged.single().id
                }
                retainedFeedbackId?.let { id ->
                    // Unmerged inspection deliberately retains Text below a clearing parent.
                    // Follow that same node through exit even when its text becomes empty.
                    compose.onAllNodes(SemanticsMatcher("retained decorative feedback") { it.id == id },
                        useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                        val clearing = generateSequence(node.parent) { it.parent }
                            .firstOrNull { it.config.isClearingSemantics }
                        assertTrue("Decorative feedback lost its accessibility-clearing ancestor", clearing != null)
                        val config = requireNotNull(clearing).config
                        assertTrue("The clearing owner announced decorative text", !config.contains(SemanticsProperties.Text))
                        assertTrue("The clearing owner announced a description", !config.contains(SemanticsProperties.ContentDescription))
                        assertTrue("The clearing owner became a live announcement", !config.contains(SemanticsProperties.LiveRegion))
                        assertTrue("The clearing owner exposed an action", !config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
                        assertTrue("The clearing owner exposed custom actions", !config.contains(androidx.compose.ui.semantics.SemanticsActions.CustomActions))
                    }
                }
            }
            assertTrue("Accepted seek never mounted its decorative response", retainedFeedbackId != null)
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun preparePausedHarnessSong() {
        val playback = application.services.audioPlayback
        compose.runOnIdle { checkNotNull(playback.startQueue(listOf(song), shuffle = false, autoplay = false)) }
        compose.waitUntil(5_000L) {
            val state = playback.state.value
            state.songId == song.id && !state.loading && !state.playWhenReady && !state.playing &&
                playback.currentSongPlaybackSettingsAreAuthoritative(song) &&
                playback.cachedSongPlaybackSettings(song) != null &&
                application.services.analysisRepository.preparedPresentation(song)?.isSuccess == true
        }
    }

    private fun mountProductionPlayer(): ProductionPlayerHarness {
        val harness = ProductionPlayerHarness()
        compose.setContent {
            StemwiseTheme {
                ProductionPlayerSheet(harness)
            }
        }
        compose.waitUntil(5_000L) { harness.ready }
        return harness
    }

    @Composable
    private fun ProductionPlayerSheet(harness: ProductionPlayerHarness) {
        val sheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true
        )
        val scaffoldState = rememberBottomSheetScaffoldState(sheetState)
        val scope = rememberCoroutineScope()
        SideEffect { harness.bind(sheetState, scope) }
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = 68.dp,
            sheetDragHandle = null,
            sheetContent = {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                    val peekPx = with(density) { 70.dp.toPx() }
                    val collapsedOffset = (heightPx - peekPx).coerceAtLeast(1f)
                    val offset = runCatching { sheetState.requireOffset() }
                        .getOrElse { collapsedOffset }
                    val expansionProgress = (1f - offset / collapsedOffset).coerceIn(0f, 1f)
                    PlayerScreen(
                        song = song,
                        onBack = { scope.launch { sheetState.partialExpand() } },
                        library = listOf(song),
                        audioPlayback = application.services.audioPlayback,
                        separationRepository = application.services.separationRepository,
                        analysisRepository = application.services.analysisRepository,
                        onDeleteSong = {
                            Result.failure(IllegalStateException("Deletion is outside this fixture"))
                        },
                        expansionProgress = expansionProgress,
                        onExpand = {
                            harness.expansionCount.incrementAndGet()
                            scope.launch { sheetState.expand() }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        ) { Box(Modifier.fillMaxSize()) }
    }

    private fun awaitCompactSurface(): PlayerSurfaceView {
        var found: PlayerSurfaceView? = null
        compose.waitUntil(5_000L) {
            compose.runOnIdle {
                found = findPlayerSurface(compose.activity.window.decorView)
            }
            val geometry = found?.semanticsGeometry()
            geometry != null && geometry.artwork.width > 0f && geometry.identity.width > 0f
        }
        compose.onNodeWithTag("player_identity_${song.id}", useUnmergedTree = true)
            .assertExists()
        return checkNotNull(found)
    }

    private fun assertPhysicalBoundsMatchNative(
        tag: String,
        surface: PlayerSurfaceView,
        nativeBounds: PlayerSurfaceBounds
    ) {
        val semantic = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val surfaceOnScreen = IntArray(2)
        val rootOnScreen = IntArray(2)
        compose.runOnIdle {
            surface.getLocationOnScreen(surfaceOnScreen)
            compose.activity.findViewById<View>(android.R.id.content).getLocationOnScreen(rootOnScreen)
        }
        val translated = Rect(
            (surfaceOnScreen[0] - rootOnScreen[0] + nativeBounds.left).toInt(),
            (surfaceOnScreen[1] - rootOnScreen[1] + nativeBounds.top).toInt(),
            (surfaceOnScreen[0] - rootOnScreen[0] + nativeBounds.right).toInt(),
            (surfaceOnScreen[1] - rootOnScreen[1] + nativeBounds.bottom).toInt()
        )
        assertWithinOnePixel("$tag left", translated.left.toFloat(), semantic.left)
        assertWithinOnePixel("$tag top", translated.top.toFloat(), semantic.top)
        assertWithinOnePixel("$tag right", translated.right.toFloat(), semantic.right)
        assertWithinOnePixel("$tag bottom", translated.bottom.toFloat(), semantic.bottom)
    }

    private fun assertWithinOnePixel(label: String, expected: Float, actual: Float) {
        assertTrue("$label differed: native=$expected semantic=$actual", kotlin.math.abs(expected - actual) <= 1f)
    }

    private fun findPlayerSurface(view: View): PlayerSurfaceView? {
        if (view is PlayerSurfaceView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findPlayerSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private inner class ProductionPlayerHarness {
        val expansionCount = AtomicInteger(0)
        lateinit var sheetState: SheetState
            private set
        private lateinit var scope: CoroutineScope
        val ready: Boolean get() = ::sheetState.isInitialized && ::scope.isInitialized

        fun bind(sheetState: SheetState, scope: CoroutineScope) {
            this.sheetState = sheetState
            this.scope = scope
        }

        fun awaitExpanded(expectedExpansionCount: Int) {
            compose.waitUntil(5_000L) {
                expansionCount.get() == expectedExpansionCount &&
                    sheetState.currentValue == SheetValue.Expanded
            }
        }

        fun collapse() {
            compose.runOnIdle { scope.launch { sheetState.partialExpand() } }
            awaitCollapsed()
        }

        fun awaitCollapsed() {
            compose.waitUntil(5_000L) {
                sheetState.currentValue == SheetValue.PartiallyExpanded
            }
            compose.onNodeWithTag("player_identity_${song.id}", useUnmergedTree = true)
                .assertExists()
        }
    }
}
