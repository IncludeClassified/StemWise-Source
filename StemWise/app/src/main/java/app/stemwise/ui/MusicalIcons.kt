package app.stemwise.ui

import app.stemwise.playback.normalizedMetronomeBeatUnit
import app.stemwise.playback.normalizedMetronomeSubdivision

internal fun beatUnitName(denominator: Int): String = when (denominator) {
    1 -> "Whole note"
    2 -> "Half note"
    8 -> "Eighth note"
    16 -> "Sixteenth note"
    32 -> "Thirty-second note"
    64 -> "Sixty-fourth note"
    128 -> "One-hundred-twenty-eighth note"
    else -> "Quarter note"
}

internal fun subdivisionNoteName(beatUnitDenominator: Int, subdivision: Int): String =
    beatUnitName(
        normalizedMetronomeBeatUnit(beatUnitDenominator) *
            normalizedMetronomeSubdivision(subdivision)
    )
