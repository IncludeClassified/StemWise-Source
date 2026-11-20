package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerQueuePacket
import app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot

/** Retains only the renderer's derived queue selection and physical layout. */
internal class PlayerSurfaceLayoutOwner {
    private var queuePacket: FullPlayerQueuePacket? = null
    private var queueSelectionRevision = -1L
    private var queueSelectionIndex = -1
    private var projectedQueue: PlayerSurfaceQueuePacket? = null
    private var layoutWidthPx = -1
    private var layoutHeightPx = -1
    private var layoutDensity = -1f
    private var layoutDirection: PlayerSurfaceLayoutDirection? = null
    private var layoutRevision = nextPlayerSurfaceRevision()
    private var projectedLayout: PlayerSurfaceLayoutPacket? = null

    fun profile(
        snapshot: FullPlayerSnapshot,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        direction: PlayerSurfaceLayoutDirection
    ): PlayerSurfaceProfilePacket {
        val safeWidth = widthPx.coerceAtLeast(1)
        val safeHeight = heightPx.coerceAtLeast(1)
        if (
            safeWidth != layoutWidthPx ||
            safeHeight != layoutHeightPx ||
            density != layoutDensity ||
            direction != layoutDirection
        ) {
            layoutWidthPx = safeWidth
            layoutHeightPx = safeHeight
            layoutDensity = density
            layoutDirection = direction
            layoutRevision = nextPlayerSurfaceRevision()
            projectedLayout = PlayerSurfaceLayoutPacket(
                revision = layoutRevision,
                widthPx = safeWidth,
                heightPx = safeHeight,
                density = density,
                direction = direction
            )
        }
        if (
            queuePacket !== snapshot.queue ||
            queueSelectionRevision != snapshot.selection.revision ||
            queueSelectionIndex != snapshot.selection.currentIndex
        ) {
            queuePacket = snapshot.queue
            queueSelectionRevision = snapshot.selection.revision
            queueSelectionIndex = snapshot.selection.currentIndex
            projectedQueue = PlayerSurfaceQueuePacket(
                revision = maxOf(snapshot.queue.revision, snapshot.selection.revision),
                membership = snapshot.queue.membership,
                currentIndex = snapshot.selection.currentIndex
            )
        }
        return PlayerSurfaceProfilePacket(
            profileRevision = snapshot.revision,
            owner = snapshot.owner,
            queue = checkNotNull(projectedQueue),
            timeline = snapshot.timeline,
            source = snapshot.sourceMixer.source,
            mixer = snapshot.sourceMixer.mixer,
            transport = snapshot.transport,
            layout = checkNotNull(projectedLayout)
        )
    }

}
