package app.stemwise.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Keeps vertical drag and fling remainder inside the retained Compose lyrics viewport.
 *
 * Lyrics can live inside another vertically movable owner (the wide-player list or the app's
 * bottom sheet). Once an inner lyric list reaches an edge, its unused delta would otherwise be
 * offered to that ancestor and move the whole player. Consuming only the vertical remainder keeps
 * horizontal ancestors available while preserving the lyric list's own normal scroll first.
 */
internal object LyricsScrollIsolationConnection : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = Offset(x = 0f, y = available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(x = 0f, y = available.y)
}

internal fun Modifier.isolateLyricsVerticalScroll(): Modifier =
    nestedScroll(LyricsScrollIsolationConnection)

/** The list scrolls first; pulling past its top moves the expanded sheet. */
internal class LyricsSheetScrollConnection(
    private val offset: () -> Float,
    private val drag: (Float) -> Unit,
    private val settle: suspend (Float) -> Unit
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f || offset() <= 0f) return Offset.Zero
        val consumed = available.y.coerceAtLeast(-offset())
        drag(consumed)
        return Offset(0f, consumed)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        if (source == NestedScrollSource.UserInput && available.y > 0f) drag(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset() <= 0f) return Velocity.Zero
        settle(available.y)
        return Velocity(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}
