package app.stemwise.playback

internal val METRONOME_BEAT_UNITS = listOf(1, 2, 4, 8, 16, 32)

internal fun normalizedMetronomeSubdivision(value: Int): Int = when (value) {
    2, 4 -> value
    else -> 1
}

internal fun normalizedMetronomeBeatUnit(value: Int): Int =
    value.takeIf(METRONOME_BEAT_UNITS::contains) ?: 4

internal fun normalizedMetronomeBpmOrNull(value: Float): Float? =
    value.takeIf(Float::isFinite)?.coerceIn(20f, 400f)

internal fun normalizedMetronomeVolumeOrNull(value: Float): Float? =
    value.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
