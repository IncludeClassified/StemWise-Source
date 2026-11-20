package app.stemwise

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.playback.admitCurrentPlaybackLibrarySnapshot
import app.stemwise.playback.resetForInstrumentationIsolation
import app.stemwise.data.ColdOpenState
import app.stemwise.ui.stemwiseShellStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin

@RunWith(AndroidJUnit4::class)
class MainActivityStartupInstrumentationTest {
    @Test
    fun playbackFixtureAdmissionWaitsForTheExactRootProjection() = kotlinx.coroutines.runBlocking {
        app.stemwise.playback.requireDisposablePlaybackTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as StemwiseApplication
        val services = application.services
        kotlinx.coroutines.withTimeout(30_000L) {
            application.workerInitialization.await()
            services.coldOpenCoordinator.awaitCompletion()
            services.externalLibraryRepository.reconcile()
        }
        val roots = services.libraryRootManager
        val root = checkNotNull(roots.captureHandle())
        val external = services.externalLibraryRepository
        val coldOpen = services.coldOpenCoordinator
        assertTrue(coldOpen.isProducerAdmissionReady(root))
        services.audioPlayback.resetForInstrumentationIsolation(instrumentation)
        val oldScanHook = external.canonicalScanStartedForTest
        val scanEntered = kotlinx.coroutines.CompletableDeferred<kotlinx.coroutines.Job>()
        val releaseScan = kotlinx.coroutines.CompletableDeferred<Unit>()
        val admissionStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.supervisorScope {
            var refresh: kotlinx.coroutines.Deferred<Unit>? = null
            var admission: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                external.canonicalScanStartedForTest = {
                    scanEntered.complete(checkNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]))
                    kotlinx.coroutines.withTimeout(30_000L) { releaseScan.await() }
                }
                external.invalidateProviderProjection()
                refresh = async(kotlinx.coroutines.Dispatchers.IO) { external.reconcile() }
                kotlinx.coroutines.withTimeout(5_000L) { scanEntered.await() }
                // Snapshot acquisition must remain usable while the real scan is held outside its root lock.
                val held = kotlinx.coroutines.withTimeout(5_000L) { external.playbackSnapshot() }
                assertFalse(held.snapshotReady)
                assertTrue(coldOpen.isProducerAdmissionReady(root))
                admission = async {
                    kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                        admissionStarted.complete(Unit)
                        application.admitCurrentPlaybackLibrarySnapshot()
                    }
                }
                kotlinx.coroutines.withTimeout(5_000L) { admissionStarted.await() }
                assertNull("Fixture admission completed while its real projection was unavailable",
                    kotlinx.coroutines.withTimeoutOrNull(250L) { admission.await() })
                releaseScan.complete(Unit)
                kotlinx.coroutines.withTimeout(30_000L) { refresh.await(); admission.await() }
                roots.requireSameLibrary(root)
                assertTrue(coldOpen.isProducerAdmissionReady(root))
                val admitted = external.playbackSnapshot()
                assertTrue(admitted.snapshotReady)
                assertEquals(admitted.songs.map { it.id }.toSet(), services.audioPlayback.state.value.queueOrder.toSet())
            } finally {
                releaseScan.complete(Unit)
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        kotlinx.coroutines.withTimeout(10_000L) {
                            admission?.cancelAndJoin()
                            refresh?.cancelAndJoin()
                            if (scanEntered.isCompleted) scanEntered.await().join()
                        }
                    }
                } finally {
                    external.canonicalScanStartedForTest = oldScanHook
                    services.audioPlayback.resetForInstrumentationIsolation(instrumentation)
                }
            }
        }
    }

    @Test
    fun developmentStartupRestoresItsExactOwnDemoTreeGrant() {
        app.stemwise.qualification.StandardInstrumentationTargetGuard.requireCurrentTarget()
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StemwiseApplication
        assertEquals("Real Development startup must restore its provider grant before root recovery",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            application.checkUriPermission(app.stemwise.demo.UiDemoLibraryDocumentsProvider.treeUri,
                android.os.Process.myPid(), android.os.Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }

    @Test
    fun repositoryConstructionDefersPrivateDirectoryQueriesUntilItsIoWork() {
        app.stemwise.qualification.StandardInstrumentationTargetGuard.requireCurrentTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as StemwiseApplication
        val services = application.services
        val guarded = object : android.content.ContextWrapper(application) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File {
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                    "Repository construction queried private storage on the UI thread"
                }
                return super.getFilesDir()
            }
        }
        val dao = app.stemwise.data.LibraryDatabase.get(application).libraryDao()
        val mutations = app.stemwise.data.PackageMutationCoordinator(application)
        val network = services.networkAccess
        var playback: app.stemwise.playback.AudioPlayback? = null
        try {
            instrumentation.runOnMainSync {
                app.stemwise.data.ExternalPackageTrash(guarded, services.libraryRootManager, mutations,
                    services.sourceBindingRegistry, dao)
                app.stemwise.integration.SeparationRepository(guarded, dao, services.mvsepAccount,
                    services.libraryRootManager, mutations, app.stemwise.data.SongWorkCoordinator(),
                    services.externalLibraryRepository, services.canonicalPackagePublisher,
                    services.backgroundWorkCoordinator, services.engine, network)
                playback = app.stemwise.playback.AudioPlayback(guarded, services.playlistRepository,
                    services.separationRepository, services.libraryRootManager, services.analysisRepository)
            }
        } finally { instrumentation.runOnMainSync { playback?.release() } }
    }

    @Test fun activityRecreationKeepsTheLiveQueueUntilRoomOrderingIsAuthoritative() = kotlinx.coroutines.runBlocking {
        app.stemwise.playback.requireDisposablePlaybackTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as StemwiseApplication
        application.admitCurrentPlaybackLibrarySnapshot()
        val services = application.services
        // Ready admits playback before cold-open's background catalog validation finishes.
        kotlinx.coroutines.withTimeout(30_000L) { services.coldOpenCoordinator.awaitCompletion() }
        val database = app.stemwise.data.LibraryDatabase.get(application)
        val dao = database.libraryDao()
        val root = checkNotNull(services.libraryRootManager.captureHandle())
        val ownedPackages = mutableListOf<androidx.documentfile.provider.DocumentFile>()
        val playlistId = "queue-recreation-${System.nanoTime()}"
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null
        var controller: androidx.media3.session.MediaController? = null
        try {
            val ownedIds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val templateId = "DEMOUI00001"
                val template = application.assets.open("demo_library/$templateId/song.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val original = application.assets.open("demo_library/$templateId/original.opus").use { it.readBytes() }
                val cover = application.assets.open("demo_library/$templateId/cover.png").use { it.readBytes() }
                (1..3).map { index ->
                    services.libraryRootManager.requireSameLibrary(root)
                    val id = "QR" + java.util.UUID.randomUUID().toString().replace("-", "").take(9)
                    val directory = checkNotNull(root.rootDocument.createDirectory(id)).also(ownedPackages::add)
                    fun publish(name: String, mime: String, bytes: ByteArray) {
                        val document = checkNotNull(directory.createFile(mime, name))
                        checkNotNull(application.contentResolver.openOutputStream(document.uri, "w")).use { it.write(bytes) }
                    }
                    publish("original.opus", "audio/ogg", original)
                    publish("cover.png", "image/png", cover)
                    publish("song.json", "application/json", app.stemwise.demo.uiDemoLibraryManifest(template, templateId, id, index))
                    services.externalLibraryRepository.reconcilePackage(root, id)
                    id
                }
            }
            kotlinx.coroutines.withTimeout(30_000L) { services.externalLibraryRepository.reconcile() }
            services.libraryRootManager.requireSameLibrary(root)
            val occurrenceOrder = listOf(ownedIds[0], ownedIds[2], ownedIds[1])
            assertTrue("Fixture must own previously absent occurrence positions",
                dao.allRemotePlaylistItems().none { it.packageId in ownedIds })
            dao.upsertRemotePlaylist(app.stemwise.data.RemotePlaylistEntity(playlistId, "fixture", "https://example.invalid",
                "Queue recreation", 1L, 1L))
            dao.upsertRemotePlaylistItems(occurrenceOrder.mapIndexed { index, id ->
                app.stemwise.data.RemotePlaylistItemEntity(playlistId, "item-$index", "youtube-video:$id", id, index, true)
            })
            val canonicalById = services.externalLibraryRepository.songs.value.associateBy { it.id }
            val expected = dao.observeSongSnapshots().first().mapNotNull { canonicalById[it.song.id] }
            assertEquals(occurrenceOrder, expected.filter { it.id in ownedIds }.map { it.id })
            scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            instrumentation.runOnMainSync {
                assertEquals(expected[0], services.audioPlayback.startQueue(expected,
                    shuffle = false, autoplay = false))
            }
            val deadline = SystemClock.elapsedRealtime() + 8_000L
            while ((services.audioPlayback.state.value.songId != expected[0].id ||
                    services.audioPlayback.state.value.queueOrder != expected.map { it.id }) &&
                SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20L)
            assertEquals(expected[0].id, services.audioPlayback.state.value.songId)
            assertEquals(expected.map { it.id }, services.audioPlayback.state.value.queueOrder)
            services.playlistRepository.beforeSongSnapshotEmissionForTest = { entered.complete(Unit); release.await() }
            scenario.recreate()
            kotlinx.coroutines.withTimeout(5_000L) { entered.await() }
            instrumentation.waitForIdleSync()
            assertEquals(expected.map { it.id }, services.audioPlayback.state.value.queueOrder)
            controller = androidx.media3.session.MediaController.Builder(application,
                androidx.media3.session.SessionToken(application, android.content.ComponentName(application,
                    app.stemwise.playback.PlaybackService::class.java))).buildAsync().get(8, TimeUnit.SECONDS)
            kotlinx.coroutines.withTimeout(8_000L) {
                var nextAdmitted = false
                while (!nextAdmitted) {
                    instrumentation.runOnMainSync {
                        val connected = checkNotNull(controller)
                        nextAdmitted = connected.currentMediaItem?.mediaId == expected[0].id &&
                            connected.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    }
                    if (!nextAdmitted) kotlinx.coroutines.delay(20L)
                }
            }
            instrumentation.runOnMainSync { checkNotNull(controller).seekToNextMediaItem() }
            val nextDeadline = SystemClock.elapsedRealtime() + 8_000L
            while (services.audioPlayback.state.value.songId != expected[1].id &&
                SystemClock.elapsedRealtime() < nextDeadline) SystemClock.sleep(20L)
            assertEquals(expected[1].id, services.audioPlayback.state.value.songId)
            release.complete(Unit)
            instrumentation.waitForIdleSync()
            assertEquals(expected[1].id, services.audioPlayback.state.value.songId)
        } finally {
            services.playlistRepository.beforeSongSnapshotEmissionForTest = null
            release.complete(Unit)
            instrumentation.runOnMainSync { controller?.release() }
            scenario?.close()
            services.audioPlayback.resetForInstrumentationIsolation(instrumentation)
            database.runInTransaction {
                database.openHelper.writableDatabase.execSQL("DELETE FROM remote_playlist_items WHERE playlistId = ?", arrayOf(playlistId))
                database.openHelper.writableDatabase.execSQL("DELETE FROM remote_playlists WHERE id = ?", arrayOf(playlistId))
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                for (directory in ownedPackages) {
                    services.libraryRootManager.requireSameLibrary(root)
                    val id = checkNotNull(directory.name)
                    check(directory.delete()) { "Could not remove recreation fixture ${directory.uri}" }
                    services.externalLibraryRepository.removeDeletedPackage(root, id)
                }
            }
        }
    }

    @Test
    fun initializedApplicationLauncherActivityDrawsWithinTwelveSeconds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as StemwiseApplication
        assumeTrue(app.packageName == "app.stemwise.development")
        val launcher = requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val firstDraw = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { firstDraw.countDown() }
        var activity: MainActivity? = null
        var content: View? = null
        try {
            val launchedAt = SystemClock.elapsedRealtime()
            app.startActivity(launcher)
            activity = monitor.waitForActivityWithTimeout(10_000L) as? MainActivity
            instrumentation.runOnMainSync {
                content = activity?.findViewById(android.R.id.content)
                content?.viewTreeObserver?.addOnDrawListener(listener)
                // Also covers a first frame that raced the monitor's return.
                content?.invalidate()
            }
            val remainingMs = (12_000L - (SystemClock.elapsedRealtime() - launchedAt)).coerceAtLeast(0L)
            val drawn = activity != null && firstDraw.await(remainingMs, TimeUnit.MILLISECONDS)
            if (!drawn) {
                var state = ""
                instrumentation.runOnMainSync {
                    val root = app.services.libraryRootManager.state.value
                    val coldOpen = app.services.coldOpenCoordinator.state.value
                    val switching = app.services.librarySessionCoordinator.switching.value
                    val stage = stemwiseShellStage(
                        rootReady = root.ready,
                        rootErrorCode = root.errorCode,
                        rootCandidateErrorPresent = false,
                        hasRememberedRoot = root.treeUri != null,
                        switchingLibrary = switching,
                        openingLibrary = coldOpen is ColdOpenState.Idle || coldOpen is ColdOpenState.Recovering,
                        coldOpenBlockedCode = (coldOpen as? ColdOpenState.Blocked)?.code
                    )
                    state = "root.ready=${root.ready}, errorCode=${root.errorCode}, " +
                        "coldOpen=${coldOpen.javaClass.simpleName}, switchingLibrary=$switching, " +
                        "computedShellStage=$stage (no candidate error), " +
                        "content=${content?.measuredWidth}x${content?.measuredHeight}, " +
                        "activityStarted=${activity != null}"
                }
                assertTrue("Launcher content did not draw: $state", drawn)
            }
        } finally {
            instrumentation.runOnMainSync {
                content?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(listener)
                activity?.finish()
            }
            instrumentation.removeMonitor(monitor)
        }
    }
}
