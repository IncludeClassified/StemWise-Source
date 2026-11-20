package app.stemwise.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import app.stemwise.ui.player.surface.PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP

/** Logical transport direction; only direct swipes carry a spatial transition. */
internal enum class TrackChangeDirection(val sign: Int) {
    PREVIOUS(-1),
    NEXT(1)
}

internal enum class TrackVisualDirection {
    LOWER_INDEX,
    HIGHER_INDEX
}

/** Keeps the entering direct-gesture target at the same pixel when it becomes the center owner. */
internal fun acceptedTargetCarryOffset(
    offsetFraction: Float,
    direction: TrackVisualDirection
): Float = when (direction) {
    TrackVisualDirection.LOWER_INDEX -> (offsetFraction - 1f).coerceIn(-1f, 1f)
    TrackVisualDirection.HIGHER_INDEX -> (offsetFraction + 1f).coerceIn(-1f, 1f)
}

/**
 * Stable-ID reconciliation for the mounted native player.
 *
 * This owns no animation. Direct pointer physics remain inside PlayerSurfaceView; programmatic
 * transport publishes the exact target immediately and never inherits pager spring state.
 */
@Stable
internal class PlayerVisualOwnerState(initialSongId: String) {
    var settledSongId by mutableStateOf(initialSongId)
        private set

    private var orderedIds: Set<String> = setOf(initialSongId)
    private var orderInput: List<String>? = null

    fun updateOrder(updatedOrderIds: List<String>) {
        if (orderInput === updatedOrderIds) return
        orderInput = updatedOrderIds
        orderedIds = updatedOrderIds.toSet()
    }

    suspend fun snapToSong(songId: String) {
        if (songId !in orderedIds) return
        settledSongId = songId
    }
}

@Composable
internal fun rememberPlayerVisualOwnerState(initialSongId: String): PlayerVisualOwnerState =
    remember { PlayerVisualOwnerState(initialSongId) }

/**
 * One normalized timeline for the mounted persistent player.
 *
 * Kept beside the live shell constants so Compose and native rendering share these exact endpoint
 * and interaction rules.
 */
internal object PlayerMotionPolicy {
    fun miniChromeAlpha(progress: Float): Float =
        (1f - ((normalized(progress) - .01f) / .14f)).coerceIn(0f, 1f)

    fun headerAlpha(progress: Float): Float = ramp(progress, .84f, .97f)

    fun fullChromeAlpha(progress: Float): Float = ramp(progress, .54f, .90f)

    fun identityAlpha(progress: Float): Float {
        val resolved = normalized(progress)
        return if (resolved <= .5f) {
            1f - ramp(resolved, 0f, .022f)
        } else {
            ramp(resolved, .52f, .86f)
        }
    }

    fun artworkProgress(progress: Float): Float =
        ((normalized(progress) - .025f) / .975f).coerceIn(0f, 1f)

    /** Full Stems withdraws media identity; the compact shared element remains visible. */
    fun artworkAlpha(progress: Float, mixerWorkspaceProgress: Float): Float =
        1f - artworkProgress(progress) * ramp(normalized(mixerWorkspaceProgress), 0f, .35f)

    /** Media fades out, full-size lanes appear, then the shared controls make room. */
    fun sourceMixerAlpha(progress: Float): Float = ramp(normalized(progress), .35f, .65f)

    fun sourceLayoutProgress(progress: Float): Float {
        val phase = ramp(normalized(progress), .65f, 1f)
        return phase * phase * (3f - 2f * phase)
    }

    fun identityTopPx(compactTopPx: Float, expandedTopPx: Float, progress: Float): Float {
        val resolved = normalized(progress)
        return when {
            resolved <= .06f -> compactTopPx
            resolved < .5f -> {
                val transport = ((resolved - .06f) / .44f).coerceIn(0f, 1f)
                compactTopPx + (expandedTopPx - compactTopPx) * transport
            }
            else -> expandedTopPx
        }
    }

    fun miniChromeInteractive(progress: Float): Boolean = normalized(progress) < .18f

    fun destinationChromeInteractive(progress: Float): Boolean = normalized(progress) >= .96f

    private fun ramp(progress: Float, start: Float, end: Float): Float =
        ((normalized(progress) - start) / (end - start)).coerceIn(0f, 1f)

    private fun normalized(progress: Float): Float = progress.coerceIn(0f, 1f)
}

internal data class PlayerRouteSemanticsPresentation(
    val compact: Boolean,
    val controlsInteractive: Boolean
)

internal fun playerRouteSemanticsPresentation(
    expansionProgress: Float
): PlayerRouteSemanticsPresentation {
    val miniInteractive = PlayerMotionPolicy.miniChromeInteractive(expansionProgress)
    val fullInteractive = PlayerMotionPolicy.destinationChromeInteractive(expansionProgress)
    return PlayerRouteSemanticsPresentation(
        compact = !fullInteractive,
        controlsInteractive = miniInteractive || fullInteractive
    )
}

internal const val PLAYER_PEEK_HEIGHT_DP = 68
internal const val SIGNAL_NAVIGATION_HEIGHT_DP = 72f
internal const val SIGNAL_NAVIGATION_ITEM_HEIGHT_DP = 72f
internal const val PLAYER_NAVIGATION_REVEAL_MAX_EXPANSION = .08f

/**
 * Bind navigation pixels to the real sheet position instead of a timer started by its target.
 * During collapse the bar remains absent for the first 92% of travel, then follows one smooth,
 * reversible curve to full opacity exactly at MINI. An interrupted route cannot reveal it early.
 */
internal fun playerNavigationAlpha(
    hasActiveSession: Boolean,
    expansionProgress: Float
): Float {
    if (!hasActiveSession) return 1f
    val linear = (
        (PLAYER_NAVIGATION_REVEAL_MAX_EXPANSION - expansionProgress.coerceIn(0f, 1f)) /
            PLAYER_NAVIGATION_REVEAL_MAX_EXPANSION
        ).coerceIn(0f, 1f)
    return linear * linear * (3f - 2f * linear)
}

internal fun playerModalActive(
    hasActiveSession: Boolean,
    currentExpanded: Boolean,
    targetExpanded: Boolean
): Boolean = hasActiveSession && (currentExpanded || targetExpanded)

internal fun playerOwnerRemoved(
    ownerId: String?,
    visibleOwnerIds: Set<String>,
    settled: Boolean,
    audioOwnerId: String?,
    pendingOpenId: String?
): Boolean = settled && ownerId != null && ownerId !in visibleOwnerIds &&
    audioOwnerId == null && pendingOpenId == null

/**
 * Material's anchored sheet is the one owner of mini/full vertical dragging.
 *
 * This gate depends only on stable route state. Native A/B input already disallows ancestor
 * interception after DOWN; changing this modifier during that pointer stream cancels the gesture.
 */
internal fun playerSheetSwipeEnabled(
    hasActiveSession: Boolean,
    libraryImeActive: Boolean
): Boolean = hasActiveSession && !libraryImeActive

internal data class PlayerShellLayout(
    val pageBottomReserveDp: Float,
    val sheetPeekHeightDp: Float
)

internal data class SignalNavigationGeometry(
    val surfaceHeightDp: Float,
    val itemHeightDp: Float,
    val iconTopDp: Float,
    val labelBaselineDp: Float,
    val itemCentersDp: List<Float>
)

/** Exact three-third control geometry; system insets live outside this row. */
internal fun signalNavigationGeometry(viewportWidthDp: Float): SignalNavigationGeometry {
    require(viewportWidthDp > 0f)
    val third = viewportWidthDp / 3f
    return SignalNavigationGeometry(
        surfaceHeightDp = SIGNAL_NAVIGATION_HEIGHT_DP,
        itemHeightDp = SIGNAL_NAVIGATION_ITEM_HEIGHT_DP,
        iconTopDp = 11f,
        labelBaselineDp = 52f,
        itemCentersDp = List(3) { index -> third * (index + .5f) }
    )
}

/** Fixed shell-page reservation shared by both player route endpoints. */
internal fun playerShellLayout(
    hasActiveSession: Boolean,
    bottomGestureInsetDp: Float = 0f
): PlayerShellLayout {
    val navigationFootprint = SIGNAL_NAVIGATION_HEIGHT_DP + bottomGestureInsetDp.coerceAtLeast(0f)
    // A route must never remeasure the page underneath it. Removing the mini-player reservation
    // as soon as Expanded became the target moved the whole Library/Tasks/System surface by 68 dp
    // mid-flight, which made the navigation bar and native player appear to disappear and jump.
    // The full sheet simply covers this fixed reservation; no visible expanded space is consumed.
    // The compact card ends 2dp inside its 68dp envelope. Revealing only those 66dp aligns its
    // rounded bottom with navigation instead of exposing a full-width black host strip below it.
    val miniPeek = if (hasActiveSession) {
        PLAYER_PEEK_HEIGHT_DP - PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP
    } else {
        0f
    }
    return PlayerShellLayout(
        pageBottomReserveDp = navigationFootprint + miniPeek,
        sheetPeekHeightDp = navigationFootprint + miniPeek
    )
}

internal fun playerSnackbarBottomOffsetDp(
    playerModal: Boolean,
    hasActiveSession: Boolean,
    bottomGestureInsetDp: Float
): Float = when {
    playerModal -> maxOf(16f, bottomGestureInsetDp.coerceAtLeast(0f))
    else -> playerShellLayout(hasActiveSession, bottomGestureInsetDp).sheetPeekHeightDp
}

/** Avoid retaining an alpha RenderNode at stable route endpoints. */
internal fun Modifier.playerEndpointAlpha(alpha: Float): Modifier {
    val resolved = alpha.coerceIn(0f, 1f)
    return when {
        resolved >= .999f -> this
        resolved <= .001f -> drawWithContent { }
        else -> graphicsLayer { this.alpha = resolved }
    }
}
