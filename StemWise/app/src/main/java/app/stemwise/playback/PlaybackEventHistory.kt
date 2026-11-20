package app.stemwise.playback

/** Event-driven diagnostics only; never called by the audio callback or position ticker. */
internal class PlaybackEventHistory {
    // Reuse records during seeks/owner changes; format ownership only when diagnostics are read.
    private val entries = Array(64) { Entry() }
    private var sequence = 0L
    private var next = 0
    private var size = 0
    @Volatile var recordingEnabled: Boolean = false
        private set

    fun setCaptureEnabled(enabled: Boolean) { recordingEnabled = enabled }

    private class Entry {
        var sequence = 0L
        var elapsedMs = 0L
        var event = ""
        var requestedGeneration = 0L
        var requestedToken = 0L
        var committedGeneration = 0L
        var committedToken = 0L
        var focusRequested = false
        var focusHeld = false
        var focusSuspended = false
        var resumeToken: Long? = null

        fun describe(): String = "$sequence@$elapsedMs:$event" +
            " requested=$requestedToken/$requestedGeneration committed=$committedToken/$committedGeneration" +
            " focus=$focusRequested/$focusHeld suspended=$focusSuspended resumeToken=$resumeToken"
    }

    @Synchronized
    fun record(
        elapsedMs: Long,
        event: String,
        requestedGeneration: Long = 0L,
        requestedToken: Long = 0L,
        committedGeneration: Long = 0L,
        committedToken: Long = 0L,
        focusRequested: Boolean = false,
        focusHeld: Boolean = false,
        focusSuspended: Boolean = false,
        resumeToken: Long? = null
    ) {
        if (!recordingEnabled) return
        val entry = entries[next]
        entry.sequence = ++sequence
        entry.elapsedMs = elapsedMs
        entry.event = event
        entry.requestedGeneration = requestedGeneration
        entry.requestedToken = requestedToken
        entry.committedGeneration = committedGeneration
        entry.committedToken = committedToken
        entry.focusRequested = focusRequested
        entry.focusHeld = focusHeld
        entry.focusSuspended = focusSuspended
        entry.resumeToken = resumeToken
        next = (next + 1) % entries.size
        if (size < entries.size) size++
    }

    @Synchronized
    fun snapshot(): List<String> {
        val start = if (size == entries.size) next else 0
        return List(size) { entries[(start + it) % entries.size].describe() }
    }
}
