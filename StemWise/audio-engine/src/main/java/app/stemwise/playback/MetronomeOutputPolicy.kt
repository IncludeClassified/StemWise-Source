package app.stemwise.playback

/** Full native click gain; the post-DSP limiter owns the final 0.98 output ceiling. */
const val METRONOME_CLICK_MAX_AMPLITUDE = 1f

fun metronomeClickAmplitude(volume: Float): Float =
    volume.coerceIn(0f, 1f) * METRONOME_CLICK_MAX_AMPLITUDE
