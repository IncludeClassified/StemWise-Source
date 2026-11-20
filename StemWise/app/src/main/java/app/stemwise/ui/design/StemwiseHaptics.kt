package app.stemwise.ui.design

import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

private val touchVibrationAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

internal fun View.performStemwiseHapticFeedback(feedback: Int) {
    if (performHapticFeedback(feedback, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)) return
    context.getSystemService(Vibrator::class.java)?.vibrate(
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
        touchVibrationAttributes
    )
}

/** Discrete ticks follow accepted detents; continuous gestures have at most start/end feedback. */
internal class StemwiseSliderFeedback {
    private var active = false
    private var start = 0f
    private var latest = 0f
    private var continuousTicked = false

    fun change(previous: Float, next: Float, accepted: Boolean, step: Float?, defer: Boolean): Boolean {
        if (!accepted || !previous.isFinite() || !next.isFinite()) return false
        fun detent(value: Float): Float = if (step != null && step > 0f)
            kotlin.math.round(value / step) else value
        if (!active) { active = true; start = detent(previous); latest = start }
        val target = detent(next)
        if (target == latest) return false
        latest = target
        if (defer) return false
        if (step != null) return true
        if (continuousTicked) return false
        continuousTicked = true
        return true
    }

    fun finish(accepted: Boolean, deferred: Boolean): Boolean {
        val tick = active && latest != start && accepted && (deferred || continuousTicked)
        active = false
        continuousTicked = false
        return tick
    }
}
