package app.stemwise.ui.player.surface

internal const val PLAYER_SURFACE_HEADER_HEIGHT_DP = 60f

/** Shared route/header bounds for retained drawing, physical hits and accessibility. */
internal class PlayerSurfaceRouteGeometry {
    var coreHeight = 0f
        private set
    var pagerLeft = 0f
        private set
    var pagerTop = 0f
        private set
    var pagerRight = 0f
        private set
    var pagerBottom = 0f
        private set
    var sourceOriginalHitBounds = PlayerSurfaceBounds(0f, 0f, 0f, 0f)
        private set
    var sourceStemsHitBounds = PlayerSurfaceBounds(0f, 0f, 0f, 0f)
        private set
    var collapseLeft = 0f
        private set
    var collapseTop = 0f
        private set
    var collapseRight = 0f
        private set
    var collapseBottom = 0f
        private set
    var infoLeft = 0f
        private set
    var infoTop = 0f
        private set
    var infoRight = 0f
        private set
    var infoBottom = 0f
        private set
    var collapseHitBounds = PlayerSurfaceBounds(0f, 0f, 0f, 0f)
        private set
    var infoHitBounds = PlayerSurfaceBounds(0f, 0f, 0f, 0f)
        private set
    var miniPlayLeft = 0f
        private set
    var miniPlayTop = 0f
        private set
    var miniPlayRight = 0f
        private set
    var miniPlayBottom = 0f
        private set
    var miniPlayHitBounds = PlayerSurfaceBounds(0f, 0f, 0f, 0f)
        private set

    fun rebuild(width: Int, height: Int, density: Float, progress: Float,
        artworkTopPx: Float, expandedArtworkSizePx: Float, identityTopPx: Float, expandedIdentityHeightPx: Float) {
        if (width <= 0 || height <= 0) return
        val expandedCoreHeight = playerSurfaceExpandedPagerBottomPx(
            artworkTopPx = artworkTopPx,
            artworkSizePx = expandedArtworkSizePx,
            identityTopPx = identityTopPx,
            identityHeightPx = expandedIdentityHeightPx
        )
        coreHeight = lerp(
            PLAYER_SURFACE_COMPACT_ENVELOPE_HEIGHT_DP * density,
            expandedCoreHeight,
            progress
        )
        val playSize = PLAYER_SURFACE_COMPACT_ACTION_SIZE_DP * density
        val collapsedPagerWidth = (
            width - PLAYER_SURFACE_COMPACT_ACTION_RIGHT_INSET_DP * density -
                playSize - 6f * density
            )
            .coerceAtLeast(0f)
        val pagerWidth = lerp(collapsedPagerWidth, width.toFloat(), progress)
        pagerLeft = 0f
        pagerTop = lerp(0f, artworkTopPx, progress)
        pagerRight = pagerWidth
        pagerBottom = coreHeight
        miniPlayLeft = width - PLAYER_SURFACE_COMPACT_ACTION_RIGHT_INSET_DP * density - playSize
        miniPlayTop = PLAYER_SURFACE_COMPACT_ARTWORK_TOP_DP * density
        miniPlayRight = miniPlayLeft + playSize
        miniPlayBottom = miniPlayTop + playSize
        val compactExpansion = 3f * density
        miniPlayHitBounds = PlayerSurfaceBounds(
            (miniPlayLeft - compactExpansion).coerceAtLeast(0f),
            (miniPlayTop - compactExpansion).coerceAtLeast(0f),
            (miniPlayRight + compactExpansion).coerceAtMost(width.toFloat()),
            (miniPlayBottom + compactExpansion).coerceAtMost(height.toFloat())
        )
        collapseLeft = 8f * density
        collapseTop = 6f * density
        collapseRight = collapseLeft + 48f * density
        collapseBottom = collapseTop + 48f * density
        infoRight = width - 8f * density
        infoLeft = infoRight - 48f * density
        infoTop = collapseTop
        infoBottom = collapseBottom
    }

    fun rebuildHeaderHits(width: Int, density: Float, sourceOriginalLeft: Float,
        sourceOriginalRight: Float, sourceStemsLeft: Float, sourceStemsRight: Float) {
        val expansion = 4f * density
        val headerBottom = PLAYER_SURFACE_HEADER_HEIGHT_DP * density
        collapseHitBounds = PlayerSurfaceBounds(
            (collapseLeft - expansion).coerceAtLeast(0f),
            (collapseTop - expansion).coerceAtLeast(0f),
            (collapseRight + expansion).coerceAtMost(sourceOriginalLeft),
            (collapseBottom + expansion).coerceAtMost(headerBottom)
        )
        infoHitBounds = PlayerSurfaceBounds(
            (infoLeft - expansion).coerceAtLeast(sourceStemsRight),
            (infoTop - expansion).coerceAtLeast(0f),
            (infoRight + expansion).coerceAtMost(width.toFloat()),
            (infoBottom + expansion).coerceAtMost(headerBottom)
        )
        sourceOriginalHitBounds = PlayerSurfaceBounds(
            (sourceOriginalLeft - expansion).coerceAtLeast(collapseHitBounds.right),
            0f,
            sourceOriginalRight,
            headerBottom
        )
        sourceStemsHitBounds = PlayerSurfaceBounds(
            sourceStemsLeft,
            0f,
            (sourceStemsRight + expansion).coerceAtMost(infoHitBounds.left),
            headerBottom
        )
    }

    private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction
}
