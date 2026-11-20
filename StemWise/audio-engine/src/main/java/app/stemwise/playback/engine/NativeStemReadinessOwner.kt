package app.stemwise.playback.engine

/** Actor-owned observation cache; its ready tuple memoizes admitted proof, never grants admission. */
internal class NativeStemReadinessOwner {
    val heads = arrayOfNulls<NativeEngineDecoderHead>(NativeSongLaneIngressBank.LANE_COUNT)
    private val decoderIds = arrayOfNulls<String>(heads.size)
    private val laneFlags = IntArray(heads.size) { -1 }
    private val eos = arrayOfNulls<Long>(heads.size)
    private val nativeAhead = LongArray(heads.size) { Long.MIN_VALUE }
    private val nativeGeneration = LongArray(heads.size) { Long.MIN_VALUE }
    private var nativeReady: Boolean? = null
    private var nativeAssigned: Boolean? = null
    private var nativeBank: NativeRuntimeBank? = null
    private var nativeOwner: NativeRuntimeOwnership? = null
    private var nativeMask = -1
    var changed = false
        private set
    var readyGeneration = 0L
    var readyPackageSignature: String? = null

    fun begin() { changed = false }

    fun observeLane(index: Int, decoderId: String?, flags: Int) {
        val end = heads[index]?.endOfStreamTimelineFrameExclusive48k
        if (decoderIds[index] != decoderId || laneFlags[index] != flags || eos[index] != end) changed = true
        decoderIds[index] = decoderId
        laneFlags[index] = flags
        eos[index] = end
    }

    fun observeNative(ready: Boolean, observation: NativeRuntimeRenderReadinessDiagnostics?, stemCount: Int) {
        if (nativeReady != ready || nativeAssigned != observation?.assigned || nativeBank != observation?.bank ||
            nativeOwner != observation?.ownership || nativeMask != (observation?.requiredSongLaneMask ?: -1)) changed = true
        nativeReady = ready; nativeAssigned = observation?.assigned; nativeBank = observation?.bank
        nativeOwner = observation?.ownership; nativeMask = observation?.requiredSongLaneMask ?: -1
        if (ready) return
        observation?.lanes?.forEach { lane ->
            if (lane.laneIndex !in 1..stemCount) return@forEach
            if (nativeAhead[lane.laneIndex] != lane.availableFromRenderStart ||
                nativeGeneration[lane.laneIndex] != lane.generation) changed = true
            nativeAhead[lane.laneIndex] = lane.availableFromRenderStart
            nativeGeneration[lane.laneIndex] = lane.generation
        }
    }
}
