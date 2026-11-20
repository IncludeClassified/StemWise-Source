package app.stemwise.playback

import app.stemwise.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Qualification accounting is disabled until the debug/test adapter explicitly enables it. */
internal class PlaybackInstrumentationRecorder private constructor(private val optIn: AtomicBoolean?) {
    constructor() : this(if (BuildConfig.BUILD_TYPE == "development") AtomicBoolean(false) else null)

    private var counters: MutableMap<String, Long>? = null
    private var observations: MutableMap<String, Any?>? = null

    val enabled: Boolean get() = BuildConfig.BUILD_TYPE == "development" && optIn?.get() == true

    fun enable() { if (BuildConfig.BUILD_TYPE == "development") optIn?.set(true) }

    /** A recreated service/transport gets fresh accounting while sharing explicit opt-in. */
    fun child(): PlaybackInstrumentationRecorder = PlaybackInstrumentationRecorder(optIn)

    fun increment(name: String) {
        if (!enabled) return
        synchronized(this) {
            val target = counters ?: mutableMapOf<String, Long>().also { counters = it }
            target[name] = (target[name] ?: 0L) + 1L
        }
    }

    fun count(name: String): Long = if (!enabled) 0L else synchronized(this) { counters?.get(name) ?: 0L }

    fun remember(name: String, value: Any?) {
        if (enabled) synchronized(this) {
            val target = observations ?: mutableMapOf<String, Any?>().also { observations = it }
            target[name] = value
        }
    }

    fun observation(name: String): Any? = if (!enabled) null else synchronized(this) { observations?.get(name) }
}
