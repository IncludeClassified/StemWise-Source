package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot
import app.stemwise.ui.player.nativeplayer.fullPlayerPublicationsAreIdentical

/**
 * Skips structural native-surface work for unchanged route-frame publications.
 *
 * Snapshot revisions are immutable publication identities. Once a revision/owner/layout tuple has
 * been accepted, seeing that exact tuple again must not validate, project, copy arrays, or touch
 * artwork. The caller may still replace the action and position delegates on that fast path.
 */
internal class PlayerSurfaceSnapshotBindGate {
    private var acceptedSnapshot: FullPlayerSnapshot? = null
    private var acceptedWidthPx = 0
    private var acceptedHeightPx = 0
    private var acceptedDensityBits = 0
    private var acceptedDirection: PlayerSurfaceLayoutDirection? = null

    fun isAcceptedUnchanged(
        snapshot: FullPlayerSnapshot,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        direction: PlayerSurfaceLayoutDirection
    ): Boolean {
        val previous = acceptedSnapshot
        return previous != null &&
            previous.revision == snapshot.revision &&
            fullPlayerPublicationsAreIdentical(previous, snapshot) &&
            acceptedWidthPx == widthPx &&
            acceptedHeightPx == heightPx &&
            acceptedDensityBits == density.toRawBits() &&
            acceptedDirection == direction
    }

    /** Call only after the complete publication has been accepted by the native surface. */
    fun recordStructuralAdoption(
        snapshot: FullPlayerSnapshot,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        direction: PlayerSurfaceLayoutDirection
    ) {
        acceptedSnapshot = snapshot
        acceptedWidthPx = widthPx
        acceptedHeightPx = heightPx
        acceptedDensityBits = density.toRawBits()
        acceptedDirection = direction
    }
}

/** Continuous left-edge origin used while identity text morphs from compact to centered. */
internal fun playerSurfaceIdentityTextOriginX(
    identityLeft: Float,
    identityRight: Float,
    measuredTextWidth: Float,
    expansionProgress: Float
): Float {
    val left = minOf(identityLeft, identityRight)
    val right = maxOf(identityLeft, identityRight)
    val centeredLeft = (left + right - measuredTextWidth.coerceAtLeast(0f)) * .5f
    val progress = expansionProgress.coerceIn(0f, 1f)
    return left + (centeredLeft - left) * progress
}
