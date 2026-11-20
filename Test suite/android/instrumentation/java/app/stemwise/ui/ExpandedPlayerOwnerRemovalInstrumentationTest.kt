package app.stemwise.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.room.Room
import app.stemwise.StemwiseServices
import app.stemwise.data.ColdOpenState
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryProjectionAvailability
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.ui.theme.StemwiseTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal class ExpandedPlayerOwnerRemovalInstrumentationTest : GoldenEnrichmentFixture() {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun automaticSuccessorsKeepTheirPreparedPresentationThroughTheNextPreload() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); grantProviderTree()
        val ids = listOf("AUTONEXT001", "AUTONEXT002", "AUTONEXT003")
        val original = targetContext.assets.open("demo_library/DEMOUI00001/original.opus").use { it.readBytes() }
        val cover = targetContext.assets.open("demo_library/DEMOUI00001/cover.png").use { it.readBytes() }
        for (id in ids) {
            val folder = File(providerRoot(), id).apply { check(mkdir()) }
            val audio = File(folder, "original.opus").apply { writeBytes(original) }
            val image = File(folder, "cover.png").apply { writeBytes(cover) }
            val incoming = org.json.JSONObject(payload("2026-08-09T12:00:00Z", id, true)
                .toString().replace(VIDEO_ID, id))
            val installed = app.stemwise.enrichment.createInstalledEnrichmentDocument(incoming, sha256(audio),
                mapOf("original.opus" to app.stemwise.enrichment.InstalledMediaIdentity(sha256(audio), original.size.toLong())),
                1L, app.stemwise.enrichment.enrichmentRevisionIdentity(incoming, sha256(image)),
                incoming.getJSONObject("artwork"), sha256(image), cover.size)
            File(folder, "song.json").writeText(installed.toString(), Charsets.UTF_8)
        }
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(false, false, true))
            override fun currentState() = state.value
        }
        lateinit var services: StemwiseServices
        val mounted = mutableStateOf(true)
        compose.runOnUiThread { services = StemwiseServices(context, network, database) }
        try {
            runBlocking { withTimeout(30_000L) {
                val roots = services.libraryRootManager
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                    .getOrThrow()).getOrThrow()
                services.preferences.activateLibraryFromRoot(checkNotNull(roots.captureHandle()).libraryId)
                services.coldOpenCoordinator.start(); services.coldOpenCoordinator.awaitCompletion()
                assertTrue(services.coldOpenCoordinator.state.value is ColdOpenState.Ready)
            } }
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides compose.activity,
                LocalContext provides context) { StemwiseTheme { if (mounted.value) StemwiseApp(services) } } }
            compose.waitUntil(10_000L) { compose.onAllNodesWithTag("library-song:${ids[0]}").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("library-song:${ids[0]}").performClick()
            val audio = services.audioPlayback
            val analysis = services.analysisRepository
            fun song(id: String) = services.externalLibraryRepository.songs.value.single { it.id == id }
            fun visible(id: String) = compose.onAllNodesWithTag("player_identity_$id", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
            compose.waitUntil(10_000L) { visible(ids[0]) && audio.controlLease.value != null }
            compose.runOnIdle { assertTrue(audio.pause()); audio.setRepeatMode("Off"); audio.setShuffle(false) }
            compose.waitUntil(10_000L) { analysis.preparedPresentation(song(ids[1]))?.isSuccess == true &&
                audio.adapter.snapshot().automaticEndPlan is app.stemwise.playback.engine.NativeEngineAutomaticEndPlan.NextOwner }
            val preparedB = analysis.preparedPresentation(song(ids[1]))!!.getOrThrow()
            fun finish(id: String) {
                val serial = audio.selectionPressureDiagnosticsForTest().requestSerial
                compose.runOnIdle { assertTrue(audio.play()) }
                var staged: app.stemwise.playback.engine.NativeRuntimeOwnership? = null
                compose.waitUntil(8_000L) {
                    val owner = audio.adapter.snapshot().publication.committed
                    val successor = audio.nativeDiagnosticsSnapshotForTest()?.runtime?.automaticSuccessor
                    val ready = owner != null && owner.selection.songId != id && successor != null &&
                        successor.outgoingOwnership == owner.owner.ownership && successor.successorOwnership != null &&
                        successor.successorOwnership != owner.owner.ownership && successor.successorQueuedFrames > 0L &&
                        successor.firstFingerprintSequence > 0L && !successor.cancellationDesired
                    if (ready) staged = successor!!.successorOwnership
                    ready
                }
                // No transport changes may retire the exact staged owner before its natural boundary.
                compose.waitUntil(20_000L) { audio.state.value.songId == id && !audio.state.value.loading }
                assertEquals("Fallback manual resolution is not a native automatic boundary", serial,
                    audio.selectionPressureDiagnosticsForTest().requestSerial)
                assertEquals(staged, audio.adapter.snapshot().publication.committed?.owner?.ownership)
            }
            finish(ids[1])
            compose.waitUntil(10_000L) { analysis.preparedPresentation(song(ids[2]))?.isSuccess == true }
            assertSame("B must survive C preloading", preparedB, analysis.preparedPresentation(song(ids[1]))?.getOrThrow())
            compose.waitUntil(10_000L) { visible(ids[1]) }
            compose.runOnIdle { assertTrue(audio.pause()); assertTrue(audio.setRepeatMode("One")) }
            assertSame(preparedB, analysis.preparedPresentation(song(ids[1]))?.getOrThrow())
            assertNull(analysis.preparedPresentation(song(ids[1]).copy(sourceHash = "f".repeat(64))))
            assertNull(analysis.preparedPresentation(song(ids[1]).copy(mediaRevision = "e".repeat(64))))
            compose.runOnIdle { assertTrue(audio.setRepeatMode("Off")) }
            compose.waitUntil(10_000L) { audio.adapter.snapshot().automaticEndPlan is app.stemwise.playback.engine.NativeEngineAutomaticEndPlan.NextOwner &&
                analysis.preparedPresentation(song(ids[2]))?.isSuccess == true }
            val preparedC = analysis.preparedPresentation(song(ids[2]))!!.getOrThrow()
            finish(ids[2])
            compose.waitUntil(10_000L) { visible(ids[2]) }
            assertSame("Last song must survive the empty successor set", preparedC,
                analysis.preparedPresentation(song(ids[2]))?.getOrThrow())
            compose.runOnIdle { assertTrue(audio.pause()) }
            runBlocking { audio.clearLibrarySession() }
            assertNull(analysis.preparedPresentation(song(ids[2])))
        } finally {
            compose.runOnIdle { mounted.value = false }
            runBlocking { withTimeout(10_000L) {
                services.coldOpenCoordinator.cancelAndJoin()
                services.externalLibraryRepository.retireScansForLibrarySwitch()
                services.audioPlayback.clearLibrarySession()
                val persistence = (services.audioPlayback.javaClass.getDeclaredField("preferencesPersistence\$delegate")
                    .apply { isAccessible = true }.get(services.audioPlayback) as Lazy<*>).value
                    as app.stemwise.playback.PlaybackPreferencesPersistence
                persistence.awaitListeningWrites()
            } }
            compose.runOnUiThread { services.audioPlayback.release() }
            database.close()
        }
    }

    @Test fun removingTheLastExpandedOwnerRestoresNavigationAndDestinationSemantics() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(false, false, true))
            override fun currentState() = state.value
        }
        lateinit var services: StemwiseServices
        val mounted = mutableStateOf(true)
        compose.runOnUiThread { services = StemwiseServices(context, network, database) }
        try {
            runBlocking { withTimeout(30_000L) {
                val roots = services.libraryRootManager
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(
                    GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                services.preferences.activateLibraryFromRoot(checkNotNull(roots.captureHandle()).libraryId)
                services.coldOpenCoordinator.start()
                services.coldOpenCoordinator.awaitCompletion()
                assertTrue(services.coldOpenCoordinator.state.value is ColdOpenState.Ready)
                assertEquals(listOf(VIDEO_ID), services.externalLibraryRepository.songs.value.map { it.id })
            } }
            compose.setContent {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides compose.activity,
                    LocalContext provides context
                ) {
                    StemwiseTheme { if (mounted.value) StemwiseApp(services) }
                }
            }
            fun awaitUi(boundary: String, ready: () -> Boolean) {
                try { compose.waitUntil(10_000L, ready) }
                catch (error: ComposeTimeoutException) {
                    val semantics = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }
                        .getOrElse { "Semantics inspection failed: $it" }
                    val nodes = runCatching {
                        "row=" + compose.onAllNodesWithTag("library-song:$VIDEO_ID").fetchSemanticsNodes().size +
                            ", identity=" + compose.onAllNodesWithTag("player_identity_$VIDEO_ID", useUnmergedTree = true)
                                .fetchSemanticsNodes().size + ", collapse=" +
                            compose.onAllNodesWithContentDescription("Collapse player", useUnmergedTree = true)
                                .fetchSemanticsNodes().size
                    }.getOrElse { "Node inspection failed: $it" }
                    var surfaces = emptyList<String>()
                    val geometry = runCatching {
                        compose.runOnIdle {
                            fun inspect(view: android.view.View): List<String> = when (view) {
                                is app.stemwise.ui.player.surface.PlayerSurfaceView -> listOf(
                                    "${view.width}x${view.height}: mixerLanes=${view.semanticsMixerLaneCount()}, " +
                                        "geometry=${view.semanticsGeometry()}")
                                is android.view.ViewGroup -> (0 until view.childCount).flatMap { inspect(view.getChildAt(it)) }
                                else -> emptyList()
                            }
                            surfaces = inspect(compose.activity.window.decorView)
                        }
                        surfaces.toString()
                    }.getOrElse { "Geometry inspection failed: $it" }
                    val native = services.audioPlayback.adapter.snapshot()
                    throw AssertionError("Timed out awaiting $boundary. Audio=${services.audioPlayback.state.value}; " +
                        "lease=${services.audioPlayback.controlLease.value}; " +
                        "root=${services.libraryRootManager.state.value}; " +
                        "projection=${services.externalLibraryRepository.projectionAvailability.value}; " +
                        "songs=${services.externalLibraryRepository.songs.value.map { it.id }}; " +
                        "requested=${native.latestRequest?.selection}; committed=${native.publication.committed?.selection}; " +
                        "nodes=$nodes; surfaces=$geometry\n$semantics", error)
                }
            }
            awaitUi("the exact Library row") {
                compose.onAllNodesWithTag("library-song:$VIDEO_ID").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("library-song:$VIDEO_ID").performClick()
            awaitUi("the selected owner's expanded player") {
                services.audioPlayback.state.value.songId == VIDEO_ID &&
                    !services.audioPlayback.state.value.loading && services.audioPlayback.controlLease.value != null &&
                    compose.onAllNodesWithContentDescription("Collapse player", useUnmergedTree = true)
                        .fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithTag("player_identity_$VIDEO_ID", useUnmergedTree = true)
                        .fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("player_identity_$VIDEO_ID", useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("Collapse player", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Activity").assertDoesNotExist()
            compose.runOnIdle { services.audioPlayback.pause() }

            // A completed direct-root scan is the authority for removing the sole playable owner.
            val packageDirectory = File(providerRoot(), VIDEO_ID).canonicalFile
            check(packageDirectory.parentFile == providerRoot().canonicalFile)
            packageDirectory.listFiles().orEmpty().forEach { check(it.isFile && it.delete()) }
            check(packageDirectory.delete())
            runBlocking { withTimeout(30_000L) { services.externalLibraryRepository.reconcile() } }
            compose.waitUntil(10_000L) {
                services.externalLibraryRepository.projectionAvailability.value == LibraryProjectionAvailability.VERIFIED &&
                    services.externalLibraryRepository.songs.value.isEmpty() && services.audioPlayback.state.value.songId == null
            }
            compose.onNodeWithTag("player_identity_$VIDEO_ID", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithContentDescription("Collapse player", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("Your library is empty.").assertIsDisplayed()
            compose.onNodeWithText("Activity").assertIsDisplayed().performClick()
            compose.onNodeWithText("Import ZIP").assertIsDisplayed()
            compose.onNodeWithText("Settings").assertIsDisplayed().performClick()
            compose.onNodeWithText("Connections").assertIsDisplayed()
            compose.onNodeWithText("Library").assertIsDisplayed().performClick()
            compose.onNodeWithText("Your library is empty.").assertIsDisplayed()
        } finally {
            compose.runOnIdle { mounted.value = false }
            runBlocking { withTimeout(10_000L) {
                services.coldOpenCoordinator.cancelAndJoin()
                services.enrichmentImports.cancelForLibraryChange()
                services.externalLibraryRepository.retireScansForLibrarySwitch()
                services.songBridge.cancelForLibraryChange()
                services.audioPlayback.clearLibrarySession()
                val persistence = (services.audioPlayback.javaClass.getDeclaredField("preferencesPersistence\$delegate")
                    .apply { isAccessible = true }.get(services.audioPlayback) as Lazy<*>).value
                    as app.stemwise.playback.PlaybackPreferencesPersistence
                persistence.awaitListeningWrites()
            } }
            compose.runOnUiThread { services.audioPlayback.release() }
            database.close()
        }
    }
}
