package app.stemwise.ui.theme

import androidx.compose.ui.graphics.Color

internal const val ARTWORK_AURA_BLUE_ARGB = 0xFF6F73FF.toInt()
internal const val ARTWORK_AURA_GRAPHITE_ARGB = 0xFF342A46.toInt()
internal const val COMPACT_PROGRESS_FILL_ARGB = 0xFF281149.toInt()
internal const val SELECTED_SHADOW_ARGB = 0xFF4E19A7.toInt()

/**
 * Semantic colors used by screen-level UI.
 *
 * Opaque palette values and fixed alpha composites live here so screens describe intent instead
 * of rebuilding visual values. Runtime-generated accents and renderer masks remain with their
 * owning renderer.
 */
internal object StemwisePalette {
    val artworkPlaceholderCore = Color.White.copy(alpha = .08f)
    val artworkPlaceholderHighlight = Color.White.copy(alpha = .55f)
    val progressTrack = artworkPlaceholderCore
    val cardOutline = Outline.copy(alpha = .70f)
    val pageGradientTop = SurfaceLow
    val pageGradientBottom = Ink
    val artworkAuraBlue = Color(ARTWORK_AURA_BLUE_ARGB)
    val artworkAuraGraphite = Color(ARTWORK_AURA_GRAPHITE_ARGB)
    val navigationSurface = Color(0xFF08070B)
    val navigationDivider = Color(0xFF18131F)
    val navigationInactiveIcon = TextDisabled
    val navigationActiveLabel = PrimaryBright
    val selectedLibraryRowGradientStart = Color(0xFF4C1A83).copy(alpha = .72f)
    val selectedLibraryRowGradientEnd = Color(0xFF1B0D2B).copy(alpha = .62f)
    val compactPlayerEdgePurple = Color(0xFFD45CFF)
    val compactPlayerEdgeBlue = Color(0xFF7787FF)
    val playerAccentLabel = PrimaryBright
    val playerFineValue = Color(0xFFC9A7FF)

    val preparationCardFill = PrimaryPurple.copy(alpha = .12f)
    val preparationCardOutline = PrimaryPurple.copy(alpha = .42f)
    val preparationStepFill = PrimaryPurple.copy(alpha = .18f)
    val preparationStepConnector = PrimaryPurple.copy(alpha = .55f)
    val disabledLibraryActionFill = SurfaceRaised.copy(alpha = .42f)
    val disabledLibraryActionOutline = Outline.copy(alpha = .4f)
    val disabledLibraryActionContent = TextSecondary.copy(alpha = .5f)
    val activeSearchOutline = PrimaryPurple.copy(alpha = .82f)
    val searchMatchFill = PrimaryPurple.copy(alpha = .24f)

    val queuedWorkIndicator = TextSecondary.copy(alpha = .45f)
    val diagnosticCodeContent = TextSecondary.copy(alpha = .72f)
    val destructiveActionFill = Coral.copy(alpha = .10f)
    val subtleDivider = Color.White.copy(alpha = .06f)

    val busyOverlayFill = Ink.copy(alpha = .72f)
    val selectedSurfaceFill = PrimaryPurple.copy(alpha = .18f)
    val selectedSurfaceOutline = preparationStepConnector
    val activeControlFill = searchMatchFill
    val disabledControlFill = SurfaceRaised.copy(alpha = .55f)
    val disabledControlOutline = Outline.copy(alpha = .42f)
    val disabledControlContent = TextDisabled
    val disabledTaskTileFill = Surface.copy(alpha = .55f)
    val inactiveSwitchFill = subtleDivider
    val metronomeBpmTrack = Color.White.copy(alpha = .10f)
    val idleSegmentFill = Color.White.copy(alpha = .04f)
    val metronomeVolumeTrack = Color.White.copy(alpha = .11f)
    val enabledStepFill = Color.White.copy(alpha = .055f)
    val disabledStepFill = Color.White.copy(alpha = .02f)
    val disabledStepContent = TextSecondary.copy(alpha = .35f)
    val tuneDivider = Outline.copy(alpha = .7f)
    val tuneSliderTrack = Color.White.copy(alpha = .09f)

    val seekerInactiveWaveform = Color.White.copy(alpha = .22f)
    val seekerTrack = metronomeVolumeTrack
    val seekerInactiveFineWaveform = Color.White.copy(alpha = .25f)
    val seekerLoopHandleFill = SurfaceRaised

    private val orderedStemColors = listOf(
        Color(0xFFFF4FA3),
        Color(0xFFFF9F43),
        Color(0xFF9B6CFF),
        Color(0xFF2DD4BF),
        Color(0xFF38BDF8),
        Color(0xFFFACC15)
    )
    private val stemColorsByRole = mapOf(
        "vocals" to orderedStemColors[0],
        "drums" to orderedStemColors[1],
        "bass" to orderedStemColors[2],
        "guitar" to orderedStemColors[3],
        "piano" to orderedStemColors[4],
        "other" to orderedStemColors[5]
    )

    fun stemColor(id: String, index: Int): Color =
        stemColorsByRole[id.lowercase()] ?: orderedStemColors[index % orderedStemColors.size]
}
