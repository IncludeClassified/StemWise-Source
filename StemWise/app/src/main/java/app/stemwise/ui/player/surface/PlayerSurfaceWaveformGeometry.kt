package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot

import app.stemwise.ui.design.StemwiseSeekerDesign
import kotlin.math.min

/** Retained timeline drawing buffers and musical-time sampling; no state-publication ownership. */
internal class PlayerSurfaceWaveformGeometry(
    private val density: Float, private val maximumBars: Int, private val fineViewportMs: Long
) {
    val points = FloatArray(maximumBars * 4)
    private val centers = FloatArray(maximumBars)
    private val offsetsMs = LongArray(maximumBars)
    var barCount = 0
        private set
    var visiblePointCount = 0
        private set
    private var top = 0f
    private var bottom = 0f

    fun rebuild(controlsValid: Boolean, seekerTrackLeft: Float, seekerTrackRight: Float, seekerTop: Float, seekerBottom: Float) {
        updateVerticalBounds(seekerTop, seekerBottom)
        if (!controlsValid || seekerBottom <= seekerTop || seekerTrackRight <= seekerTrackLeft) {
            barCount = 0
            return
        }
        val fineViewport = playerSurfaceFineViewport(
            seekerTrackLeft,
            seekerTrackRight,
            StemwiseSeekerDesign.FINE_EDGE_INSET_DP * density
        )
        val trackWidth = fineViewport.rightX - fineViewport.leftX
        val count = StemwiseSeekerDesign.FINE_BAR_COUNT.coerceAtMost(maximumBars)
        barCount = count
        var index = 0
        while (index < count) {
            val fraction = (index + .5f) / count.toFloat()
            val x = fineViewport.leftX + fraction * trackWidth
            centers[index] = x
            offsetsMs[index] =
                ((fraction - .5f) * fineViewportMs.toDouble()).toLong()
            index++
        }
    }

    fun updateVerticalBounds(seekerTop: Float, seekerBottom: Float) {
        top = seekerTop
        bottom = seekerBottom
    }

    fun update(centerPositionMs: Long, durationMs: Long, packet: FullPlayerSnapshot?) {
        val seekerTop = top
        val seekerBottom = bottom
        val centerY = (seekerTop + seekerBottom) * .5f
        val maxHalf = ((seekerBottom - seekerTop) * .43f).coerceAtLeast(2f * density)
        visiblePointCount = 0
        var index = 0
        while (index < barCount) {
            val time = centerPositionMs + offsetsMs[index]
            if (!playerSurfaceFineWaveformSampleVisible(time, durationMs)) {
                index++
                continue
            }
            val amplitude = sample(packet, time, durationMs)
            if (amplitude == null) {
                index++
                continue
            }
            val half = 1.5f * density + amplitude * (maxHalf - 1.5f * density)
            val base = visiblePointCount
            val x = centers[index]
            points[base] = x
            points[base + 1] = centerY - half
            points[base + 2] = x
            points[base + 3] = centerY + half
            visiblePointCount += 4
            index++
        }
    }

    fun amplitude(value: FullPlayerSnapshot?, timeMs: Long, durationMs: Long): Float {
        return sample(value, timeMs, durationMs) ?: 0f
    }

    fun hasWaveform(value: FullPlayerSnapshot?, timeMs: Long, durationMs: Long): Boolean =
        sample(value, timeMs, durationMs) != null

    private fun sample(value: FullPlayerSnapshot?, timeMs: Long, durationMs: Long): Float? {
        val packet = value ?: return null
        if (durationMs <= 0L || packet.sourceMixer.source.phase != PlayerSurfaceSourcePhase.SETTLED) return null
        val fine = packet.fineWindow
        if (fine != null && fine.peakCount > 0 &&
            fine.mediaRevision == packet.owner.mediaRevision &&
            fine.sourceRevision == packet.sourceMixer.source.revision &&
            fine.mixerRevision == packet.sourceMixer.mixer.revision) {
            val end = fine.startMs + fine.frameDurationMs * fine.peakCount.toLong()
            if (timeMs >= fine.startMs && timeMs < end) {
                val position = (timeMs - fine.startMs).toDouble() / fine.frameDurationMs.toDouble()
                return interpolatePeak(fine.peakCount, position, fine::peakAt)
            }
        }
        if (packet.sourceMixer.source.presentedMode != PlayerSurfaceSourceMode.ORIGINAL ||
            packet.sourceMixer.source.audibleMode != PlayerSurfaceSourceMode.ORIGINAL) return null
        val coarse = packet.waveform
        if (coarse.peakCount > 0) {
            val position = timeMs.toDouble() / durationMs.toDouble() * (coarse.peakCount - 1)
            return interpolatePeak(coarse.peakCount, position, coarse::peakAt)
        }
        return null
    }

    private inline fun interpolatePeak(count: Int, position: Double, peakAt: (Int) -> Float): Float {
        if (count == 0) return 0f
        if (count == 1) return peakAt(0).coerceIn(0f, 1f)
        val clamped = position.coerceIn(0.0, (count - 1).toDouble())
        val first = clamped.toInt()
        val second = min(first + 1, count - 1)
        val fraction = (clamped - first.toDouble()).toFloat()
        return (peakAt(first) + (peakAt(second) - peakAt(first)) * fraction).coerceIn(0f, 1f)
    }

    fun timelineX(timeMs: Long, durationMs: Long, centerMs: Long, fine: Float, seekerTrackLeft: Float, seekerTrackRight: Float): Float {
        if (durationMs <= 0L) return seekerTrackLeft
        val trackWidth = seekerTrackRight - seekerTrackLeft
        val coarse = seekerTrackLeft + (timeMs.toDouble() / durationMs.toDouble()).toFloat() *
            trackWidth
        val fineViewport = playerSurfaceFineViewport(
            seekerTrackLeft,
            seekerTrackRight,
            StemwiseSeekerDesign.FINE_EDGE_INSET_DP * density
        )
        val detailFraction = .5f +
            (timeMs - centerMs).toFloat() / fineViewportMs.toFloat()
        val detail = fineViewport.leftX + detailFraction *
            (fineViewport.rightX - fineViewport.leftX)
        return coarse + (detail - coarse) * fine.coerceIn(0f, 1f)
    }

}
