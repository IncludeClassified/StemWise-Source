package app.stemwise

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.os.Bundle
import android.os.SystemClock
import android.os.StrictMode
import android.util.Log
import android.view.animation.PathInterpolator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.os.BundleCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.stemwise.artwork.CoilArtworkNetworkInterceptor
import app.stemwise.network.AndroidAppNetworkAccess
import app.stemwise.network.WifiBoundCallFactory
import okhttp3.OkHttpClient
import app.stemwise.playback.PlaybackService
import app.stemwise.ui.StemwiseApp
import app.stemwise.ui.StemwiseEnglishChrome
import app.stemwise.ui.StemwiseLaunchChoreography
import app.stemwise.ui.STEMWISE_LAUNCH_DURATION_MS
import app.stemwise.ui.theme.StemwiseTheme
import app.stemwise.work.AppTaskRemovalCoordinator
import app.stemwise.work.TaskRemovalMonitor
import app.stemwise.work.WorkerInitializationBarrier
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import coil.ImageLoader
import coil.ImageLoaderFactory

open class StemwiseApplication : Application(), ImageLoaderFactory {
    internal val networkAccess by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAppNetworkAccess(this)
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val workerInitialization = WorkerInitializationBarrier()
    lateinit var services: StemwiseServices
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
        // Install diagnostics before the dependency graph is constructed so a
        // startup regression cannot hide in a service constructor.
        services = StemwiseServices(this, networkAccess)
        applicationScope.launch {
            try {
                services.libraryRootManager.initialize(services.preferences)
                // Durable preparation must not classify the account's temporary startup default.
                services.mvsepAccount.awaitLocalLoad()
                startLibrarySession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (services.libraryRootManager.state.value.ready) {
                    runCatching { services.coldOpenCoordinator.blockStartup(error) }
                        .exceptionOrNull()
                        ?.takeIf { it !== error }
                        ?.let(error::addSuppressed)
                }
                Log.e("StemwiseStartup", "Application recovery failed", error)
            } finally {
                // Workers must classify the resulting root state even when startup failed.
                workerInitialization.complete()
            }
        }
    }

    protected open suspend fun startLibrarySession() {
        // start() registers exact-session producer admission synchronously before return.
        services.coldOpenCoordinator.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .callFactory(WifiBoundCallFactory(networkAccess, OkHttpClient()))
        .components {
            add(
                CoilArtworkNetworkInterceptor(
                    this@StemwiseApplication,
                    networkAccess
                )
            )
        }
        .build()
}

class MainActivity : ComponentActivity() {
    private val services: StemwiseServices
        get() = (application as StemwiseApplication).services
    private val networkAccess: AndroidAppNetworkAccess
        get() = (application as StemwiseApplication).networkAccess
    private var lastResumeStorageRefreshElapsedMs = Long.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        val openingSplash = installSplashScreen()
        val applicationStartupComplete = mutableStateOf(false)
        openingSplash.setOnExitAnimationListener { splash ->
            val launchInterpolator = PathInterpolator(.16f, 1f, .3f, 1f)
            splash.iconView.animate()
                .scaleX(1.24f)
                .scaleY(1.24f)
                .setInterpolator(launchInterpolator)
                .setDuration(STEMWISE_LAUNCH_DURATION_MS)
                .start()
            splash.view.animate()
                .alpha(0f)
                .setInterpolator(launchInterpolator)
                .setDuration(STEMWISE_LAUNCH_DURATION_MS)
                .withEndAction(splash::remove)
                .start()
        }
        super.onCreate(savedInstanceState)
        AppTaskRemovalCoordinator.onTaskAvailable(applicationContext)
        PlaybackService.onAppTaskAvailable()
        services.audioPlayback.onAppTaskAvailable()
        TaskRemovalMonitor.armForTask(applicationContext)
        setContent {
            LaunchedEffect(Unit) {
                (application as StemwiseApplication).workerInitialization.await()
                applicationStartupComplete.value = true
            }
            StemwiseTheme {
                StemwiseEnglishChrome {
                    StemwiseLaunchChoreography {
                        StemwiseApp(
                            services = services,
                            applicationStartupComplete = applicationStartupComplete.value
                        )
                    }
                }
            }
        }
        handleSharedIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Reassert the exact vote after window/display or power-policy transitions. Android still
        // owns the final thermal and user-policy decision; StemWise itself never imposes 60/90 Hz.
        val now = SystemClock.elapsedRealtime()
        if (lastResumeStorageRefreshElapsedMs == Long.MIN_VALUE ||
            now - lastResumeStorageRefreshElapsedMs >= RESUME_STORAGE_REFRESH_MINIMUM_INTERVAL_MS
        ) {
            lastResumeStorageRefreshElapsedMs = now
            services.externalLibraryRepository.requestProviderRefresh()
        }
    }

    private companion object {
        const val RESUME_STORAGE_REFRESH_MINIMUM_INTERVAL_MS = 30_000L
    }

    override fun onDestroy() {
        // Explicit task removal closes playback; ordinary backgrounding keeps its transport.
        if (isTaskRoot && isFinishing && !isChangingConfigurations) {
            AppTaskRemovalCoordinator.onTaskRemoved(applicationContext)
            PlaybackService.onAppTaskRemoved(
                applicationContext,
                services.audioPlayback.processRestartRequiredAfterTaskRemoval()
            )
            TaskRemovalMonitor.disarmTask(applicationContext)
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        when {
            intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                offerSharedText(intent)
            intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE ->
                offerSharedEnrichment(intent)
        }
    }

    private fun offerSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val sharedText = sharedPlaylistText(intent)
        intent.action = null
        // Drop the raw Bundle without reading a malformed parcel a second time.
        intent.replaceExtras(null as Bundle?)
        services.shareIntents.offerText(sharedText)
    }

    private fun offerSharedEnrichment(intent: Intent) {
        val streams = sharedEnrichmentStreams(intent)
        val allowed = (intent.type in SONG_ZIP_MIME_TYPES || intent.type == "*/*") &&
            !streams.isNullOrEmpty() &&
            streams.all { it.scheme == "content" } &&
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        intent.action = null
        intent.replaceExtras(null as Bundle?)
        if (!allowed) {
            services.enrichmentImports.reject(
                "The shared song ZIPs did not provide safe readable file access."
            )
            return
        }
        services.enrichmentImports.offer(checkNotNull(streams))
    }



}

internal fun sharedPlaylistText(intent: Intent): String? = try {
    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
} catch (_: RuntimeException) {
    null
}

/** Reads the exported share boundary without trusting another app's parcelable types. */
internal val SONG_ZIP_MIME_TYPES = listOf(
    "application/zip", "application/x-zip-compressed", "application/octet-stream"
)

internal fun sharedEnrichmentStreams(intent: Intent): List<Uri>? = try {
    val extras = intent.extras
    val hasStreamExtra = intent.hasExtra(Intent.EXTRA_STREAM)
    val direct = if (intent.action == Intent.ACTION_SEND && hasStreamExtra) {
        val raw = extras?.let {
            BundleCompat.getParcelable(it, Intent.EXTRA_STREAM, Parcelable::class.java)
        }
        raw as? Uri ?: return null
    } else {
        null
    }
    val multiple = if (intent.action == Intent.ACTION_SEND_MULTIPLE && hasStreamExtra) {
        val raw = extras?.let {
            BundleCompat.getParcelableArrayList(it, Intent.EXTRA_STREAM, Parcelable::class.java)
        } ?: return null
        if (raw.any { it !is Uri }) return null
        raw.filterIsInstance<Uri>()
    } else {
        emptyList()
    }
    val clipped = intent.clipData?.let { clip ->
        buildList {
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
        }
    }.orEmpty()
    (multiple + listOfNotNull(direct) + clipped).distinct()
} catch (_: RuntimeException) {
    null
}
