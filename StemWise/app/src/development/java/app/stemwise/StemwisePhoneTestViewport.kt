package app.stemwise

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.stemwise.ui.theme.PrimaryPurple

internal data class StemwisePhoneTestViewportGeometry(
    val widthDp: Float,
    val heightDp: Float
)

internal fun stemwisePhoneTestViewportGeometry(
    enabled: Boolean,
    availableWidthDp: Float,
    availableHeightDp: Float
): StemwisePhoneTestViewportGeometry? {
    if (!enabled || availableWidthDp < 600f || availableHeightDp <= 0f) return null
    val exactAspect = 3_088f / 1_440f
    val width = minOf(432f, availableWidthDp, availableHeightDp / exactAspect)
    if (width <= 0f) return null
    return StemwisePhoneTestViewportGeometry(widthDp = width, heightDp = width * exactAspect)
}

@Composable
internal fun StemwisePhoneTestViewport(enabled: Boolean = false, content: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val geometry = stemwisePhoneTestViewportGeometry(
            enabled = enabled,
            availableWidthDp = maxWidth.value,
            availableHeightDp = maxHeight.value
        )
        if (geometry == null) {
            Box(Modifier.fillMaxSize()) { content() }
        } else {
            Box(
                Modifier
                    .width(geometry.widthDp.dp)
                    .height(geometry.heightDp.dp)
                    .border(1.dp, PrimaryPurple)
            ) {
                content()
            }
        }
    }
}
