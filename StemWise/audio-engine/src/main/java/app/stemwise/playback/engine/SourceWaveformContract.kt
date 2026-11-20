package app.stemwise.playback.engine

/** Optional canonical-cache evidence. Stale/corrupt reads never authorize decoder fallback. */
sealed interface SourcePcmWindowRead {
    data class Ready(val samples: FloatArray) : SourcePcmWindowRead
    data object Delayed : SourcePcmWindowRead
    data object Stale : SourcePcmWindowRead
    data object Unsupported : SourcePcmWindowRead
    data class Corrupt(val diagnostic: String) : SourcePcmWindowRead
}

data class SourceWaveformSource(
    val location: String,
    val contentIdentity: String,
    val normalizedRole: String,
    val directFloat32Audio: Boolean
) {
    init { require(location.isNotBlank() && contentIdentity.isNotBlank() && normalizedRole.isNotBlank()) }
}

data class SourceWaveformIdentity(
    val songId: String,
    val audioRevision: String,
    val durationMs: Long,
    val ownership: NativeRuntimeOwnership,
    val sourceMode: NativeRuntimeSourceMode,
    /** Canonical lane order; mute and solo have already resolved to effective gains. */
    val stemGains: List<Float>,
    /** Exact selected descriptors, Original followed by canonical stems. */
    val sources: List<SourceWaveformSource>
) {
    init {
        require(songId.isNotBlank() && audioRevision.isNotBlank() && durationMs > 0L)
        require(durationMs <= Long.MAX_VALUE / 48L - 5_000L)
        require(stemGains.size <= NativeRuntimeControls.STEM_COUNT)
        require(stemGains.all { it.isFinite() && it in 0f..2f })
        require(sources.size == stemGains.size + 1)
        require(sourceMode != NativeRuntimeSourceMode.STEMS || stemGains.isNotEmpty())
    }
}

data class SourceWaveformWindow(
    val identity: SourceWaveformIdentity,
    val startMs: Long,
    val endMs: Long,
    val frameDurationMs: Long,
    val peaks: List<Float>
)

