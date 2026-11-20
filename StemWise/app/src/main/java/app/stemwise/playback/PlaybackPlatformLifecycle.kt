package app.stemwise.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.media.AudioAttributes as PlatformAudioAttributes

internal enum class PlaybackServiceDestructionAction { NONE, PAUSE, RESTART }

/** Android registrations, route debounce and service-start lifetime for one playback facade. */
internal class PlaybackPlatformLifecycle(
    private val context: Context,
    private val handler: Handler,
    private val onFocusChange: (Int) -> Unit,
    private val onRouteRecovery: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val topology = AudioOutputTopologyRecoveryGate(audioManager.connectedOutputIdsOrNull())
    private var started = false
    private var closed = false
    private var serviceStartRequested = false
    var stoppingForTaskRemoval = false
        private set
    private var restartAfterDestroy = false
    private var nextFocusResultForTest: Int? = null
    private var focusAcquisition: FocusAcquisition? = null
    private inner class FocusAcquisition {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (Looper.myLooper() == handler.looper) {
                if (!closed && focusAcquisition === this) onFocusChange(change)
            } else {
                handler.post { if (!closed && focusAcquisition === this) onFocusChange(change) }
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener, handler)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .build()
    }
    private val routeRecovery = Runnable { if (!closed) onRouteRecovery() }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = changed(devices)
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = changed(devices)
    }

    fun start() {
        check(!closed)
        if (started) return
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        started = true
    }

    private fun changed(devices: Array<out AudioDeviceInfo>) {
        if (closed || devices.none(AudioDeviceInfo::isSink) ||
            !topology.shouldRecover(audioManager.connectedOutputIdsOrNull())) return
        // One Bluetooth transition can report several sinks while Android settles its route.
        handler.removeCallbacks(routeRecovery)
        handler.postDelayed(routeRecovery, AUDIO_ROUTE_SETTLE_MS)
    }

    fun requestFocus(): Int {
        if (closed) return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        retireFocusRequest()
        val acquisition = FocusAcquisition()
        focusAcquisition = acquisition
        val result = nextFocusResultForTest?.also { nextFocusResultForTest = null }
            ?: runCatching { audioManager.requestAudioFocus(acquisition.request) }
                .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) retireFocusRequest()
        return result
    }

    fun prepareFocusAbandon() { nextFocusResultForTest = null }

    fun abandonFocus() {
        prepareFocusAbandon()
        retireFocusRequest()
    }

    private fun retireFocusRequest() {
        val retired = focusAcquisition ?: return
        focusAcquisition = null
        runCatching { audioManager.abandonAudioFocusRequest(retired.request) }
    }

    fun delayNextFocusRequestForTest() {
        check(nextFocusResultForTest == null)
        nextFocusResultForTest = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
    }

    fun markTaskRemoval() { stoppingForTaskRemoval = true }

    fun ensureService(commandsAllowed: Boolean) {
        if (closed) return
        if (serviceStartRequested) {
            if (stoppingForTaskRemoval && commandsAllowed) restartAfterDestroy = true
            return
        }
        serviceStartRequested = true
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
            .onFailure {
                serviceStartRequested = false
                Log.w("StemWiseNativePlayback", "Could not start the playback session service", it)
            }
    }

    fun serviceCreated() {
        if (closed) return
        serviceStartRequested = true
        stoppingForTaskRemoval = false
    }

    fun serviceDestroyed(allowRestart: Boolean): PlaybackServiceDestructionAction {
        val action = when {
            closed -> PlaybackServiceDestructionAction.NONE
            stoppingForTaskRemoval && restartAfterDestroy && allowRestart -> PlaybackServiceDestructionAction.RESTART
            !stoppingForTaskRemoval -> PlaybackServiceDestructionAction.PAUSE
            else -> PlaybackServiceDestructionAction.NONE
        }
        serviceStartRequested = false
        stoppingForTaskRemoval = false
        restartAfterDestroy = false
        return action
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(routeRecovery)
        if (started) runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        started = false
        abandonFocus()
    }
}

private const val AUDIO_ROUTE_SETTLE_MS = 120L

private fun AudioManager.connectedOutputIdsOrNull(): Set<Int>? = runCatching {
    getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .asSequence()
        .filter(AudioDeviceInfo::isSink)
        .map(AudioDeviceInfo::getId)
        .toSet()
        .takeIf(Set<Int>::isNotEmpty)
}.getOrNull()
