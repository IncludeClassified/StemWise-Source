package app.stemwise.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

/** Android supplies ordinary UI and content glyphs, including language-specific fallback. */
val StemwiseSystemFontFamily: FontFamily = FontFamily.SansSerif

val StemwiseTypography = Typography(
    displaySmall = TextStyle(
        lineBreak = LineBreak.Heading,
        fontFeatureSettings = "tnum",
        fontFamily = StemwiseSystemFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp
    ),
    headlineMedium = TextStyle(
        lineBreak = LineBreak.Heading,
        fontFeatureSettings = "tnum",
        fontFamily = StemwiseSystemFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        lineBreak = LineBreak.Heading,
        fontFeatureSettings = "tnum",
        fontFamily = StemwiseSystemFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        lineBreak = LineBreak.Heading,
        fontFeatureSettings = "tnum",
        fontFamily = StemwiseSystemFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        lineBreak = LineBreak.Paragraph,
        fontFeatureSettings = "tnum",
        fontFamily = StemwiseSystemFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        lineBreak = LineBreak.Paragraph,
        fontFeatureSettings = "tnum",
        fontFamily = StemwiseSystemFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFeatureSettings = "tnum",
        fontFamily = StemwiseSystemFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        lineBreak = LineBreak.Paragraph,
        fontFeatureSettings = "tnum",
        fontFamily = StemwiseSystemFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)
