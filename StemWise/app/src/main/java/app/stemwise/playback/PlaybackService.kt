package app.stemwise.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.stemwise.MainActivity
import app.stemwise.StemwiseApplication
import app.stemwise.playback.session.AudioPlaybackSessionTransport
import app.stemwise.playback.session.MediaSessionSnapshot
import app.stemwise.playback.session.SessionNotificationRefreshGate
import app.stemwise.playback.session.STABLE_MEDIA_SESSION_COMMAND_CAPABILITIES
import app.stemwise.playback.session.StableMediaSessionPlayer
import app.stemwise.work.AppTaskRemovalCoordinator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps arbitrary installed apps outside the exported Media3 transport boundary. */
internal fun mediaSessionControllerIsAllowed(
    applicationPackageName: String,
    controllerPackageName: String,
    isTrusted: Boolean
): Boolean = isTrusted || controllerPackageName == applicationPackageName

/**
 * Retires only a process whose sole native playback owner entered terminal safety quarantine.
 * Keeping scheduling injectable makes the one-shot policy testable without killing the test VM.
 */
internal class NativePlaybackProcessRetirement(
    private val terminate: () -> Unit
) {
    private var taskRemoved = false
    private var restartRequired = false
    private var playbackServiceCleanupComplete = false
    private var durableTaskCleanupComplete = false
    private var taskEpoch = 0L
    @Volatile private var scheduled = false

    fun onTaskRemoved(
        restartAlreadyRequired: Boolean = false,
        cleanupAlreadyComplete: Boolean = false
    ): Boolean = record {
        if (!taskRemoved) {
            taskRemoved = true
            durableTaskCleanupComplete = false
        }
        restartRequired = restartRequired || restartAlreadyRequired
        playbackServiceCleanupComplete = playbackServiceCleanupComplete || cleanupAlreadyComplete
    }

    fun onNativeRestartRequired(): Boolean = record {
        restartRequired = true
    }

    fun onPlaybackServiceCleanupComplete(): Boolean = record {
        playbackServiceCleanupComplete = true
    }

    fun onDurableTaskCleanupComplete(completedTaskEpoch: Long): Boolean = record {
        if (taskRemoved && completedTaskEpoch == taskEpoch) {
            durableTaskCleanupComplete = true
        }
    }

    fun currentTaskEpoch(): Long = synchronized(this) { taskEpoch }

    fun onPlaybackServiceCreated() {
        synchronized(this) {
            if (!scheduled) playbackServiceCleanupComplete = false
        }
    }

    /** A normal relaunch cancels only a removal that never observed native quarantine. */
    fun onTaskAvailable() {
        synchronized(this) {
            if (!restartRequired && !scheduled) {
                taskEpoch += 1L
                taskRemoved = false
                playbackServiceCleanupComplete = false
                durableTaskCleanupComplete = false
            }
        }
    }

    private fun record(update: () -> Unit): Boolean {
        val shouldSchedule = synchronized(this) {
            update()
            if (!scheduled && taskRemoved && restartRequired &&
                playbackServiceCleanupComplete && durableTaskCleanupComplete
            ) {
                scheduled = true
                true
            } else {
                false
            }
        }
        // The service-cleanup edge is delivered only after super.onDestroy(). Do not defer this on
        // a Handler: Android may freeze the now-cached process and run that callback only after the
        // next launcher tap, killing the fresh UI instead of retiring the poisoned process now.
        if (shouldSchedule) terminate()
        return shouldSchedule
    }
}

/** Suppresses duplicate cleanup for one task while allowing that epoch to retry after failure. */
internal class TaskEpochCleanupClaim {
    private var claimedEpoch = Long.MIN_VALUE

    @Synchronized
    fun claim(taskEpoch: Long): Boolean {
        if (claimedEpoch == taskEpoch) return false
        claimedEpoch = taskEpoch
        return true
    }

    @Synchronized
    fun releaseAfterFailure(taskEpoch: Long) {
        if (claimedEpoch == taskEpoch) claimedEpoch = Long.MIN_VALUE
    }
}

/**
 * Stable Android notification/Bluetooth façade around StemWise's one playback owner.
 *
 * The service-lifetime SimpleBasePlayer owns no renderer or clock and is never replaced during a
 * song/source/stem transition. This prevents System UI from briefly observing a released player,
 * stale metadata, or an empty timeline while the real engine changes owner.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var instrumentation = PlaybackInstrumentationRecorder()
    private var mediaSession: MediaSession? = null
    private var sessionTransport: AudioPlaybackSessionTransport? = null
    private var sessionPlayer: StableMediaSessionPlayer? = null
    private var playbackWakeLock: PowerManager.WakeLock? = null
    private var stoppingForTaskRemoval = false
    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationRefreshGate = SessionNotificationRefreshGate()
    @Volatile private var notificationExplicitTriggerInProgress = false
    private var delegatedNotificationIdentity: NotificationPostIdentity? = null
    private var lastNotificationPostAtMs = 0L
    private var deferredNotificationUpdate = false
    private val notificationRefreshRunnable = Runnable {
        if (stoppingForTaskRemoval) return@Runnable
        if (instrumentation.enabled) instrumentation.increment("notificationDrainCount")
        val currentSnapshot = sessionPlayer?.canonicalNotificationSnapshot()
        val latestOwnerKey = sessionTransport?.latestTransportOwnerKeyForNotification()
        if (instrumentation.enabled) instrumentation.remember("lastNotificationDrainCurrent", currentSnapshot)
        if (instrumentation.enabled) instrumentation.remember("lastNotificationDrainLatestKey", latestOwnerKey)
        val accepted = notificationRefreshGate.drain(currentSnapshot, latestOwnerKey)
        val currentOwnerMatches = currentSnapshot?.media != null &&
            currentSnapshot.transportOwnerKey == latestOwnerKey
        if ((accepted != null || (deferredNotificationUpdate && currentOwnerMatches)) &&
            mediaSession != null
        ) {
            if (instrumentation.enabled) instrumentation.increment("notificationAcceptedDrainCount")
            if (instrumentation.enabled) instrumentation.remember("lastNotificationAccepted", currentSnapshot)
            // Publish only the current owner and latest transport state after coalescing the
            // burst. MediaSession commands/position updates are independent of this OS post rate.
            if (instrumentation.enabled) instrumentation.increment("notificationTriggerCount")
            notificationExplicitTriggerInProgress = true
            try {
                triggerNotificationUpdate()
            } finally {
                notificationExplicitTriggerInProgress = false
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseForNoisyRoute()
            }
        }
    }

    private fun pauseForNoisyRoute() {
        (application as StemwiseApplication).services.audioPlayback.pause()
    }

    override fun onCreate() {
        super.onCreate()
        // The native renderer does not use ExoPlayer's wake-lock owner. Keep its decoder and
        // control threads awake during playback, including brief seek/source preparation gaps.
        playbackWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StemWise:Playback")
            .apply { setReferenceCounted(false) }
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
        nativeProcessRetirement.onPlaybackServiceCreated()
        val playback = (application as StemwiseApplication).services.audioPlayback
        instrumentation = playback.instrumentationRecorder.child()
        val transport = AudioPlaybackSessionTransport(playback, instrumentation.child())
        val stablePlayer = StableMediaSessionPlayer(
            transport = transport,
            onCanonicalSnapshotApplied = ::scheduleCanonicalNotificationRefresh,
            instrumentation = instrumentation.child()
        )
        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                if (stoppingForTaskRemoval || !mediaSessionControllerIsAllowed(
                        applicationPackageName = packageName,
                        controllerPackageName = controller.packageName,
                        isTrusted = controller.isTrusted
                    )
                ) return MediaSession.ConnectionResult.reject()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    // This is a stable capability ceiling, not the current queue boundary.
                    // The player's live availableCommands continues to publish the executable
                    // subset as songs and queue navigation change.
                    .setAvailablePlayerCommands(STABLE_MEDIA_SESSION_COMMAND_CAPABILITIES)
                    .build()
            }

        }
        val createdSession = MediaSession.Builder(this, stablePlayer)
            .setCallback(callback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    SESSION_ACTIVITY_REQUEST_CODE,
                    Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    ),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

        sessionTransport = transport
        sessionPlayer = stablePlayer
        mediaSession = createdSession
        addSession(createdSession)
        activeService = this
        playback.onPlaybackServiceCreated()

        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession?.takeIf {
        !stoppingForTaskRemoval && mediaSessionControllerIsAllowed(
            applicationPackageName = packageName,
            controllerPackageName = controllerInfo.packageName,
            isTrusted = controllerInfo.isTrusted
        )
    }

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ) {
        if (stoppingForTaskRemoval || session !== mediaSession) return
        if (instrumentation.enabled) instrumentation.increment("notificationUpdateCallbackCount")
        if (notificationExplicitTriggerInProgress) {
            if (instrumentation.enabled) instrumentation.increment("notificationExplicitUpdateCallbackCount")
        }
        val canonicalSnapshot = sessionPlayer?.canonicalNotificationSnapshot()
        // A paused prepared song still owns its notification; an ordinary background service
        // can be reclaimed even while SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS is enabled.
        val retainPreparedOwner = !stoppingForTaskRemoval && canonicalSnapshot?.let {
            it.media != null && !it.ended && it.error == null
        } == true
        val requireForeground = startInForegroundRequired || retainPreparedOwner
        if (instrumentation.enabled) {
            instrumentation.remember("lastNotificationUpdatePlayer", session.player.currentMediaItem)
            instrumentation.remember("lastNotificationUpdateOwner", canonicalSnapshot?.transportOwnerKey)
        }
        val currentIdentity = canonicalSnapshot?.toNotificationPostIdentity()
        val previousIdentity = delegatedNotificationIdentity
        val now = android.os.SystemClock.uptimeMillis()
        val shouldDelegate = startInForegroundRequired || notificationExplicitTriggerInProgress ||
            currentIdentity == null ||
            previousIdentity == null ||
            (currentIdentity == previousIdentity &&
                now - lastNotificationPostAtMs >= NOTIFICATION_POST_INTERVAL_MS)
        if (shouldDelegate) {
            // MediaSessionService's default provider posts synchronously and may later repost after
            // artwork decoding. Let it see the first owner and cadence-bounded latest updates.
            // Sending every intermediate owner or same-owner transport change can trip Android's
            // notification update throttle even though Media3 itself rejects stale bitmap futures.
            if (instrumentation.enabled) instrumentation.increment("notificationDelegatedUpdateCount")
            delegatedNotificationIdentity = currentIdentity
            lastNotificationPostAtMs = now
            deferredNotificationUpdate = false
            if (instrumentation.enabled) instrumentation.remember("lastNotificationDelegated", canonicalSnapshot)
            super.onUpdateNotification(session, requireForeground)
        } else {
            if (instrumentation.enabled) instrumentation.increment("notificationSuppressedOwnerUpdateCount")
            deferredNotificationUpdate = true
            notificationRefreshGate.offer(canonicalSnapshot)
            enqueueNotificationRefresh()
        }
    }

    /** Android 15 requires foreground eligibility before the exact paused owner's focus request. */
    private fun prepareForPlaybackFocus(): Boolean {
        if (stoppingForTaskRemoval) return false
        val session = mediaSession ?: return false
        // Media3's "started" latch survives demotion; a fresh focus request needs fresh promotion.
        super.onUpdateNotification(session, true)
        return true
    }

    private fun releaseDeniedPlaybackFocus() {
        val session = mediaSession ?: return
        if (session.player.playWhenReady) return
        onUpdateNotification(session, false)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (stoppingForTaskRemoval || activeService !== this) return
        val playback = (application as StemwiseApplication).services.audioPlayback
        val restartRequired = playback.processRestartRequiredAfterTaskRemoval()
        AppTaskRemovalCoordinator.onTaskRemoved(applicationContext)
        onAppTaskRemoved(
            applicationContext,
            restartRequired
        )
    }

    private fun refreshSessionMetadata() {
        if (stoppingForTaskRemoval) return
        // MediaSessionService observes the stable Player and updates its notification when the
        // refreshed metadata invalidates that Player. Calling triggerNotificationUpdate() here is
        // both redundant and dangerous under rapid local selection pressure: it bypasses Media3's
        // state diff and asks NotificationManager to enqueue every intermediate request.
        sessionTransport?.refresh()
    }

    private fun scheduleCanonicalNotificationRefresh(snapshot: MediaSessionSnapshot) {
        if (stoppingForTaskRemoval) return
        val shouldStayAwake = snapshot.media != null && snapshot.playWhenReady &&
            !snapshot.ended && snapshot.error == null &&
            snapshot.suppression == app.stemwise.playback.session.SessionPlaybackSuppression.NONE
        playbackWakeLock?.let { wakeLock ->
            if (shouldStayAwake && !wakeLock.isHeld) wakeLock.acquire()
            else if (!shouldStayAwake && wakeLock.isHeld) wakeLock.release()
        }
        if (instrumentation.enabled) instrumentation.increment("notificationOfferCount")
        if (instrumentation.enabled) instrumentation.remember("lastNotificationOffered", snapshot)
        if (!notificationRefreshGate.offer(snapshot)) {
            if (snapshot.media == null) {
                notificationHandler.removeCallbacks(notificationRefreshRunnable)
            }
            return
        }
        if (instrumentation.enabled) instrumentation.increment("notificationScheduleCount")
        if (instrumentation.enabled) instrumentation.remember("lastNotificationScheduled", snapshot)
        enqueueNotificationRefresh()
    }

    private fun enqueueNotificationRefresh() {
        notificationHandler.removeCallbacks(notificationRefreshRunnable)
        // One provider update can also post its asynchronously decoded artwork. Reserve both
        // posts below Android's notification rate limit; transport itself remains immediate.
        notificationHandler.postAtTime(
            notificationRefreshRunnable,
            maxOf(
                android.os.SystemClock.uptimeMillis() + NOTIFICATION_OWNER_QUIET_PERIOD_MS,
                lastNotificationPostAtMs + NOTIFICATION_POST_INTERVAL_MS
            )
        )
    }

    /** Disconnect bound controllers before stopSelf; waiting for onDestroy would retain their bind. */
    private fun retireSession() {
        stoppingForTaskRemoval = true
        playbackWakeLock?.let { if (it.isHeld) it.release() }
        playbackWakeLock = null
        notificationHandler.removeCallbacks(notificationRefreshRunnable)
        notificationRefreshGate.clear()
        deferredNotificationUpdate = false
        runCatching { unregisterReceiver(noisyReceiver) }
        mediaSession?.let { session ->
            if (isSessionAdded(session)) removeSession(session)
            super.onUpdateNotification(session, false)
            session.release()
        }
        mediaSession = null
        sessionPlayer?.close()
        sessionPlayer = null
        sessionTransport = null
    }

    override fun onDestroy() {
        retireSession()
        val ownedActiveSlot = activeService === this
        if (ownedActiveSlot) activeService = null
        if (ownedActiveSlot) {
            (application as StemwiseApplication).services.audioPlayback
                .onPlaybackServiceDestroyed()
        }
        super.onDestroy()
        // A fatal process is retired only after the session, receiver, notification gate and
        // service lifecycle have all completed their ordinary cleanup above.
        if (ownedActiveSlot) nativeProcessRetirement.onPlaybackServiceCleanupComplete()
    }

    private fun notificationDiagnostics(): String {
        val gate = notificationRefreshGate.diagnostics()
        return "offers=${instrumentation.count("notificationOfferCount")}," +
            "schedules=${instrumentation.count("notificationScheduleCount")}," +
            "drains=${instrumentation.count("notificationDrainCount")}," +
            "accepted=${instrumentation.count("notificationAcceptedDrainCount")}," +
            "triggers=${instrumentation.count("notificationTriggerCount")}," +
            "updates=${instrumentation.count("notificationUpdateCallbackCount")}," +
            "explicitUpdates=${instrumentation.count("notificationExplicitUpdateCallbackCount")}," +
            "delegated=${instrumentation.count("notificationDelegatedUpdateCount")}," +
            "suppressedOwners=${instrumentation.count("notificationSuppressedOwnerUpdateCount")}," +
            "offered=${notificationIdentityLabel(instrumentation.observation("lastNotificationOffered") as? MediaSessionSnapshot)}," +
            "scheduled=${notificationIdentityLabel(instrumentation.observation("lastNotificationScheduled") as? MediaSessionSnapshot)}," +
            "drainCurrent=${notificationIdentityLabel(instrumentation.observation("lastNotificationDrainCurrent") as? MediaSessionSnapshot)}," +
            "drainLatest=${notificationOwnerKeyLabel(instrumentation.observation("lastNotificationDrainLatestKey") as? String)}," +
            "acceptedIdentity=${notificationIdentityLabel(instrumentation.observation("lastNotificationAccepted") as? MediaSessionSnapshot)}," +
            "updatePlayer=${notificationUpdatePlayerLabel()}," +
            "delegatedIdentity=${notificationIdentityLabel(instrumentation.observation("lastNotificationDelegated") as? MediaSessionSnapshot)}," +
            "pending=${notificationIdentityLabel(gate.pending)}," +
            "refreshed=${notificationIdentityLabel(gate.refreshed)}"
    }

    /** Media3 Player getters are application-looper confined, including test diagnostics. */
    private fun sessionFacadeDiagnosticsOnMain(): String {
        check(Looper.myLooper() == Looper.getMainLooper())
        val transport = sessionTransport
        val player = sessionPlayer
        return "serviceActive=true," +
            "transport={${transport?.diagnosticsForTest()}}," +
            "notification={${notificationDiagnostics()}}," +
            "playerTimelineEmpty=${player?.currentTimeline?.isEmpty}," +
            "playerWindows=${player?.currentTimeline?.windowCount}," +
            "playerCount=${player?.mediaItemCount}," +
            "playerItem=${player?.currentMediaItem?.mediaId}," +
            "playerItemIndex=${player?.currentMediaItemIndex}," +
            "playerPeriodIndex=${player?.currentPeriodIndex}," +
            "playerPosition=${player?.currentPosition}," +
            "playerDuration=${player?.duration}," +
            "playerState=${player?.playbackState}"
    }

    private fun notificationUpdatePlayerLabel(): String {
        val item = instrumentation.observation("lastNotificationUpdatePlayer") as? androidx.media3.common.MediaItem
        return notificationIdentityLabel(item?.mediaId,
            item?.mediaMetadata?.extras?.getString(app.stemwise.playback.session.SESSION_MEDIA_REVISION_KEY),
            instrumentation.observation("lastNotificationUpdateOwner") as? String)
    }

    private fun notificationIdentityLabel(snapshot: MediaSessionSnapshot?): String =
        notificationIdentityLabel(
            mediaId = snapshot?.media?.id,
            revision = snapshot?.media?.revision,
            ownerKey = snapshot?.transportOwnerKey
        )

    private fun notificationIdentityLabel(
        identity: app.stemwise.playback.session.SessionNotificationIdentity?
    ): String = notificationIdentityLabel(
        mediaId = identity?.media?.id,
        revision = identity?.media?.revision,
        ownerKey = identity?.transportOwnerKey
    )

    private fun notificationIdentityLabel(
        mediaId: String?,
        revision: String?,
        ownerKey: String?
    ): String = if (mediaId == null) {
        "none"
    } else {
        "$mediaId@${notificationOwnerKeyLabel(ownerKey)}#${revision ?: "none"}"
    }

    private fun notificationOwnerKeyLabel(ownerKey: String?): String =
        ownerKey?.replace('\u0000', '/') ?: "none"

    private fun MediaSessionSnapshot.toNotificationPostIdentity(): NotificationPostIdentity? =
        media?.let { description ->
            NotificationPostIdentity(
                ownerKey = transportOwnerKey,
                mediaId = description.id,
                mediaRevision = description.revision
            )
        }

    private data class NotificationPostIdentity(
        val ownerKey: String?,
        val mediaId: String,
        val mediaRevision: String?
    )

    companion object {
        private const val SESSION_ACTIVITY_REQUEST_CODE = 17
        private const val NOTIFICATION_OWNER_QUIET_PERIOD_MS = 32L
        private const val NOTIFICATION_POST_INTERVAL_MS = 500L

        @Volatile private var activeService: PlaybackService? = null
        private val nativeProcessRetirement by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            NativePlaybackProcessRetirement(
                terminate = {
                    Log.e(PROCESS_RETIREMENT_TAG, "Retiring terminal native playback process")
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            )
        }
        private val processRetirementCleanupScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val processRetirementCleanupClaim = TaskEpochCleanupClaim()

        /** Either edge may arrive first: fatal output or the user's explicit Recents removal. */
        internal fun onNativeProcessRestartRequired() {
            nativeProcessRetirement.onNativeRestartRequired()
        }

        internal fun onAppTaskRemoved(
            context: Context,
            restartAlreadyRequired: Boolean
        ) {
            val retiringService = activeService
            retiringService?.stoppingForTaskRemoval = true
            val playback = (context.applicationContext as? StemwiseApplication)
                ?.services
                ?.audioPlayback
            playback?.stopForTaskRemoval()
            retiringService?.retireSession()
            retiringService?.pauseAllPlayersAndStopSelf()
            nativeProcessRetirement.onTaskRemoved(
                restartAlreadyRequired = restartAlreadyRequired,
                cleanupAlreadyComplete = retiringService == null
            )
            val taskEpoch = nativeProcessRetirement.currentTaskEpoch()
            if (!processRetirementCleanupClaim.claim(taskEpoch)) return
            val awaitCancellation = AppTaskRemovalCoordinator.captureTaskRemovalCancellation()
            processRetirementCleanupScope.launch {
                var failure: Throwable? = null
                repeat(3) { attempt ->
                    failure = runCatching {
                        awaitCancellation()
                    }.exceptionOrNull()
                    if (failure == null) {
                        nativeProcessRetirement.onDurableTaskCleanupComplete(taskEpoch)
                        return@launch
                    }
                    if (attempt < 2) delay(100L)
                }
                processRetirementCleanupClaim.releaseAfterFailure(taskEpoch)
                Log.e(
                    PROCESS_RETIREMENT_TAG,
                    "Durable task cancellation did not settle; retaining the fail-silent process",
                    failure
                )
            }
        }

        internal fun onAppTaskAvailable() {
            nativeProcessRetirement.onTaskAvailable()
        }

        private const val PROCESS_RETIREMENT_TAG = "StemwisePlayback"

        /** Metadata-only changes refresh one stable facade; Player state drives every control. */
        fun onPlaybackMetadataChanged() = activeService?.refreshSessionMetadata()

        internal fun isActiveForTest(): Boolean = activeService != null
        internal fun prepareForPlaybackFocus(): Boolean = activeService?.prepareForPlaybackFocus() == true
        internal fun releaseDeniedPlaybackFocus() = activeService?.releaseDeniedPlaybackFocus()
        @Suppress("DEPRECATION")
        internal fun isForegroundForTest(): Boolean = activeService?.let { service ->
            val manager = service.getSystemService(android.app.ActivityManager::class.java)
            manager.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == PlaybackService::class.java.name && it.foreground
            }
        } == true
        internal fun isPlaybackWakeLockHeldForTest(): Boolean =
            activeService?.playbackWakeLock?.isHeld == true

        internal fun stablePlayerForTest(): Player? = activeService?.sessionPlayer

        internal fun sessionFacadeDiagnosticsForTest(): String {
            val service = activeService ?: return "serviceActive=false"
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return service.sessionFacadeDiagnosticsOnMain()
            }

            val result = AtomicReference<String>()
            val completed = CountDownLatch(1)
            service.notificationHandler.post {
                try {
                    result.set(service.sessionFacadeDiagnosticsOnMain())
                } finally {
                    completed.countDown()
                }
            }
            return if (completed.await(2L, TimeUnit.SECONDS)) {
                result.get() ?: "serviceActive=true,diagnostics=unavailable"
            } else {
                "serviceActive=true,diagnostics=main-looper-timeout"
            }
        }

        internal fun metadataCacheBuildCountsForTest(): Pair<Long, Long>? =
            activeService?.let { service ->
                val transport = service.sessionTransport ?: return@let null
                val player = service.sessionPlayer ?: return@let null
                transport.mediaDescriptionBuildCountForTest() to
                    player.mediaMetadataBuildCountForTest()
            }

        internal fun pauseForNoisyRouteForTest(): Boolean = activeService?.let { service ->
            service.pauseForNoisyRoute()
            true
        } ?: false
    }
}
