package app.stemwise.ui.player.surface

import kotlin.math.roundToInt

internal fun playerSurfaceInterpolateOpaqueColors(base: Int, destination: Int, progress: Float): Int {
    val amount = progress.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val from = base ushr shift and 255
        val to = destination ushr shift and 255
        return (from + (to - from) * amount).roundToInt().coerceIn(0, 255) shl shift
    }
    return 0xff000000.toInt() or channel(16) or channel(8) or channel(0)
}

internal fun playerSurfaceArtworkTint(base: Int, artwork: Int, influence: Float): Int =
    playerSurfaceInterpolateOpaqueColors(base, artwork, influence.coerceIn(0f, .2f))
