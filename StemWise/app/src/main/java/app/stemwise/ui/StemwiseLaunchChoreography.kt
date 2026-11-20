package app.stemwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.stemwise.ui.theme.Ink

/**
 * Keeps the complete application composed behind the platform-owned launch mark.
 * The platform splash is already the sole launch animation owner; transforming this whole tree
 * exposes partially rendered AndroidView/Compose layers while the splash exits.
 */
@Composable
internal fun StemwiseLaunchChoreography(
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        content()
    }
}

internal const val STEMWISE_LAUNCH_DURATION_MS = 1_140L
