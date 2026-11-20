package app.stemwise.playback.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

/** Source-timeline music energy, before tuning, limiter, click and device volume. */
internal class NativeSourceWaveformProcessor {
    private val cacheGate = Any()
    private var requestSerial = 0L
    private var latest: SourceWaveformWindow? = null
    private var latestFrameCount = 0L
    private var latestMixed: FloatArray? = null

    suspend fun window(
        identity: SourceWaveformIdentity,
        centerMs: Long,
        radiusMs: Long = 5_000L,
        frameCount: Long,
        read: suspend (SourceWaveformSource, Long, Int) -> FloatArray?
    ): SourceWaveformWindow? {
        require(radiusMs in 1L..5_000L && frameCount > 0L)
        val center = centerMs.coerceIn(0L, identity.durationMs)
        val firstBucket = (center - radiusMs).coerceAtLeast(0L) / BUCKET_MS
        val endFrame = minOf(frameCount, (center + radiusMs) * FRAMES_PER_MS)
        val endBucket = minOf((endFrame + BUCKET_FRAMES - 1L) / BUCKET_FRAMES, firstBucket + 2_000L)
        if (endBucket <= firstBucket) return null
        val coroutine = currentCoroutineContext()
        var previous: Pair<Long, FloatArray>? = null
        val request = synchronized(cacheGate) {
            coroutine.ensureActive()
            requestSerial++
            latest?.takeIf { it.identity == identity && latestFrameCount == frameCount &&
                it.startMs == firstBucket * BUCKET_MS && it.endMs == endBucket * BUCKET_MS }?.let { return it }
            if (latest?.identity == identity && latestFrameCount == frameCount) {
                latestMixed?.let { previous = checkNotNull(latest).startMs * FRAMES_PER_MS to it }
            }
            requestSerial
        }
        val firstFrame = firstBucket * BUCKET_FRAMES
        val finalFrame = minOf(endBucket * BUCKET_FRAMES, frameCount)
        val frames = (finalFrame - firstFrame).toInt()
        val mixed = FloatArray(frames * 2)
        val lanes = if (identity.sourceMode == NativeRuntimeSourceMode.ORIGINAL) listOf(0 to 1f)
            else identity.stemGains.mapIndexedNotNull { lane, gain ->
                if (gain == 0f) null else lane + 1 to gain
            }
        val overlapFirst = previous?.let { maxOf(firstFrame, it.first) } ?: finalFrame
        val overlapEnd = previous?.let { minOf(finalFrame, it.first + it.second.size / 2) } ?: firstFrame
        val ranges = if (overlapEnd > overlapFirst) {
            val retained = checkNotNull(previous)
            retained.second.copyInto(mixed, ((overlapFirst - firstFrame) * 2).toInt(),
                ((overlapFirst - retained.first) * 2).toInt(), ((overlapEnd - retained.first) * 2).toInt())
            listOf(firstFrame to overlapFirst, overlapEnd to finalFrame)
        } else listOf(firstFrame to finalFrame)
        for ((rangeFirst, rangeEnd) in ranges) {
            if (rangeEnd <= rangeFirst) continue
            val count = (rangeEnd - rangeFirst).toInt()
            val offset = ((rangeFirst - firstFrame) * 2).toInt()
            for ((lane, gain) in lanes) {
                coroutine.ensureActive()
                val samples = read(identity.sources[lane], rangeFirst, count) ?: return null
                coroutine.ensureActive()
                if (samples.size != count * 2 || samples.any { !it.isFinite() }) return null
                for (sample in samples.indices) mixed[offset + sample] += samples[sample] * gain
            }
        }
        val energies = DoubleArray((endBucket - firstBucket).toInt())
        val counts = IntArray(energies.size)
        for (frame in 0 until frames) {
            if ((frame and 4_095) == 0) coroutine.ensureActive()
            val bucket = (frame / BUCKET_FRAMES).toInt()
            val left = mixed[frame * 2].toDouble()
            val right = mixed[frame * 2 + 1].toDouble()
            if (!left.isFinite() || !right.isFinite()) return null
            energies[bucket] += left * left + right * right
            counts[bucket] += 2
        }
        val result = SourceWaveformWindow(identity, firstBucket * BUCKET_MS, endBucket * BUCKET_MS,
            BUCKET_MS, List(energies.size) { bucket ->
                sqrt(energies[bucket] / counts[bucket]).toFloat().coerceIn(0f, 1f)
            })
        synchronized(cacheGate) {
            coroutine.ensureActive()
            if (requestSerial == request) { latest = result; latestFrameCount = frameCount; latestMixed = mixed }
        }
        return result
    }

    private companion object {
        const val BUCKET_MS = 5L
        const val FRAMES_PER_MS = 48L
        const val BUCKET_FRAMES = BUCKET_MS * FRAMES_PER_MS
    }
}
