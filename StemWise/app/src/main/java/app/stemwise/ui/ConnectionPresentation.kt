package app.stemwise.ui

import androidx.compose.ui.graphics.Color
import app.stemwise.ui.theme.*

internal val SYSTEM_CONNECTION_SECONDARY_ACCENT = Color(0xFF8D79FF)
internal val SYSTEM_CONNECTION_READY_PILL_FILL = Color(0xFF24143C)
internal val SYSTEM_CONNECTION_PENDING_PILL_FILL = Color(0xFF1E1736)
internal val SYSTEM_CONNECTION_ERROR_PILL_FILL = Color(0xFF32131F)
internal const val SYSTEM_CONNECTION_READY_PILL_WIDTH_DP = 52f
internal const val SYSTEM_CONNECTION_READY_PILL_HEIGHT_DP = 24f

internal enum class SystemConnectionStatusVisualKind {
    READY,
    NEUTRAL,
    PENDING,
    ERROR
}

internal data class SystemConnectionStatusVisual(
    val kind: SystemConnectionStatusVisualKind,
    val fill: Color,
    val border: Color,
    val text: Color
)

internal fun systemConnectionStatusVisual(kind: SystemConnectionStatusVisualKind): SystemConnectionStatusVisual {
    return when (kind) {
        SystemConnectionStatusVisualKind.READY -> SystemConnectionStatusVisual(
            kind = kind,
            fill = SYSTEM_CONNECTION_READY_PILL_FILL,
            border = PrimaryPurple,
            text = PrimaryBright
        )
        SystemConnectionStatusVisualKind.NEUTRAL -> SystemConnectionStatusVisual(
            kind = kind,
            fill = Surface,
            border = OutlineStrong,
            text = TextSecondary
        )
        SystemConnectionStatusVisualKind.PENDING -> SystemConnectionStatusVisual(
            kind = kind,
            fill = SYSTEM_CONNECTION_PENDING_PILL_FILL,
            border = SYSTEM_CONNECTION_SECONDARY_ACCENT,
            text = PrimaryBright
        )
        SystemConnectionStatusVisualKind.ERROR -> SystemConnectionStatusVisual(
            kind = kind,
            fill = SYSTEM_CONNECTION_ERROR_PILL_FILL,
            border = Coral,
            text = Coral
        )
    }
}
