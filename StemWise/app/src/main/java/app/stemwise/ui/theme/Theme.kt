package app.stemwise.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.stemwise.ui.design.ProvideStemwiseMotion

internal const val INK_ARGB = 0xFF000000.toInt()
internal const val SURFACE_LOW_ARGB = 0xFF09080D.toInt()
internal const val SURFACE_ARGB = 0xFF100E17.toInt()
internal const val PRIMARY_PURPLE_ARGB = 0xFFA970FF.toInt()
internal const val PRIMARY_BRIGHT_ARGB = 0xFFD7B7FF.toInt()
internal const val CORAL_ARGB = 0xFFFF6E91.toInt()
internal const val TEXT_PRIMARY_ARGB = 0xFFF7F2FF.toInt()
internal const val TEXT_SECONDARY_ARGB = 0xFFB9AEC6.toInt()
internal const val INACTIVE_ARGB = 0xFF625C69.toInt()
internal const val OUTLINE_ARGB = 0xFF332943.toInt()

/** Current app palette with truthful purple/blue semantic names. */
val Ink = Color(INK_ARGB)
val SurfaceLow = Color(SURFACE_LOW_ARGB)
val Surface = Color(SURFACE_ARGB)
val SurfaceRaised = Color(0xFF171321)
val SurfaceSoft = Color(0xFF21182C)
val SplashBackground = Ink
val PrimaryPurple = Color(PRIMARY_PURPLE_ARGB)
val PrimaryPurpleContainer = Color(0xFF2A153F)
val Violet = Color(0xFF7787FF)
val PrimaryBright = Color(PRIMARY_BRIGHT_ARGB)
val Amber = Color(0xFFFFC56B)
val Coral = Color(CORAL_ARGB)
val SignalRed = Coral
val Sky = Color(0xFF7787FF)
val Cyan = Color(0xFF7D8CFF)
val Rose = Color(0xFFFF78CA)
val Indigo = Color(0xFF7787FF)
val Lime = Color(0xFFC9A7FF)
val Orange = Color(0xFFFFA46B)
val TextPrimary = Color(TEXT_PRIMARY_ARGB)
val TextSecondary = Color(TEXT_SECONDARY_ARGB)
val TextDisabled = Color(INACTIVE_ARGB)
val Outline = Color(OUTLINE_ARGB)
val OutlineStrong = Color(0xFF59406F)

private val StemwiseColors = darkColorScheme(
    primary = PrimaryPurple,
    onPrimary = Ink,
    primaryContainer = PrimaryPurpleContainer,
    onPrimaryContainer = PrimaryPurple,
    secondary = Violet,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF211A45),
    onSecondaryContainer = Color(0xFFE8E6FF),
    tertiary = Amber,
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF3C2C14),
    onTertiaryContainer = Color(0xFFFFDDA8),
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Surface,
    surfaceContainer = SurfaceRaised,
    surfaceContainerHigh = SurfaceSoft,
    surfaceContainerHighest = Color(0xFF281D34),
    outline = Outline,
    outlineVariant = OutlineStrong,
    inverseSurface = TextPrimary,
    inverseOnSurface = Ink,
    inversePrimary = Color(0xFF6A32B2),
    scrim = Color.Black,
    error = Coral,
    onError = Ink,
    errorContainer = Color(0xFF4B1F25),
    onErrorContainer = Color(0xFFFFDADD)
)

private val StemwiseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun StemwiseTheme(content: @Composable () -> Unit) {
    ProvideStemwiseMotion {
        MaterialTheme(
            colorScheme = StemwiseColors,
            typography = StemwiseTypography,
            shapes = StemwiseShapes,
            content = content
        )
    }
}
