package app.stemwise.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun playerSheetMotionSpecs(enabled: Boolean, visibilityThreshold: Float): PlayerSheetMotionSpecs {
    val settle: FiniteAnimationSpec<Float> = if (enabled) {
        spring(dampingRatio = .82f, stiffness = 420f, visibilityThreshold = visibilityThreshold)
    } else snap()
    return PlayerSheetMotionSpecs(expand = settle, collapse = settle, dragSettle = settle)
}

@OptIn(ExperimentalMaterial3Api::class)
internal data class PlayerSheetMotionSpecs(
    val expand: FiniteAnimationSpec<Float>,
    val collapse: FiniteAnimationSpec<Float>,
    val dragSettle: AnimationSpec<Float>
) {
    fun install(state: SheetState) {
        StemwiseSheetMotionBridge.install(state, expand, collapse,
            boundedPlayerSheetSettleSpec(dragSettle,
                minimum = { StemwiseSheetMotionBridge.anchorOffset(state, SheetValue.Expanded) },
                maximum = { StemwiseSheetMotionBridge.anchorOffset(state, SheetValue.PartiallyExpanded) }))
    }
}

/** A release at FULL must not launch the spring beyond the same bounds used while dragging. */
internal fun boundedPlayerSheetSettleSpec(
    delegate: AnimationSpec<Float>,
    minimum: () -> Float,
    maximum: () -> Float
): AnimationSpec<Float> = object : AnimationSpec<Float> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<Float, V>): VectorizedAnimationSpec<V> {
        val animation = delegate.vectorize(converter)
        val zero = converter.convertToVector(0f)
        fun acceptedInitialVelocity(value: V, velocity: V): V {
            val position = converter.convertFromVector(value)
            val speed = converter.convertFromVector(velocity)
            return if ((position <= minimum() && speed < 0f) || (position >= maximum() && speed > 0f)) zero else velocity
        }
        fun bounded(value: Float): Float {
            val low = minimum()
            val high = maximum()
            return if (low.isFinite() && high.isFinite() && low <= high) value.coerceIn(low, high) else value
        }
        return object : VectorizedAnimationSpec<V> by animation {
            override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
                animation.getDurationNanos(initialValue, targetValue, acceptedInitialVelocity(initialValue, initialVelocity))

            override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
                val value = animation.getValueFromNanos(playTimeNanos, initialValue, targetValue,
                    acceptedInitialVelocity(initialValue, initialVelocity))
                val raw = converter.convertFromVector(value)
                val clipped = bounded(raw)
                return if (clipped == raw) value else converter.convertToVector(clipped)
            }

            override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
                val acceptedVelocity = acceptedInitialVelocity(initialValue, initialVelocity)
                val raw = converter.convertFromVector(animation.getValueFromNanos(playTimeNanos, initialValue, targetValue, acceptedVelocity))
                return if (bounded(raw) != raw) zero else
                    animation.getVelocityFromNanos(playTimeNanos, initialValue, targetValue, acceptedVelocity)
            }

            override fun getEndVelocity(initialValue: V, targetValue: V, initialVelocity: V): V =
                animation.getEndVelocity(initialValue, targetValue, acceptedInitialVelocity(initialValue, initialVelocity))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
internal suspend fun SheetState.expandWithStemwiseMotion(motion: PlayerSheetMotionSpecs) {
    motion.install(this)
    expand()
}

@OptIn(ExperimentalMaterial3Api::class)
internal suspend fun SheetState.collapseWithStemwiseMotion(motion: PlayerSheetMotionSpecs) {
    motion.install(this)
    partialExpand()
}

private class PlayerPredictiveBackMotionOwner {
    var generation = 0L
    var cancellationSettle: Job? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerPredictiveBackHandler(
    enabled: Boolean,
    state: SheetState,
    motion: PlayerSheetMotionSpecs,
    onPreviewChanged: (Boolean) -> Unit = {},
    onGestureReleased: () -> Unit = {}
) {
    key(state) {
        val scope = rememberCoroutineScope()
        val owner = remember { PlayerPredictiveBackMotionOwner() }
        val previewChanged = rememberUpdatedState(onPreviewChanged)
        DisposableEffect(owner) {
            onDispose {
                owner.generation++
                owner.cancellationSettle?.cancel()
                previewChanged.value(false)
            }
        }
        PredictiveBackHandler(enabled = enabled) { events ->
            val generation = ++owner.generation
            owner.cancellationSettle?.cancel()
            owner.cancellationSettle = null
            onPreviewChanged(true)
            var cancellationOwnsPreview = false
            try {
                val start = state.requireOffset()
                val collapsed = StemwiseSheetMotionBridge.anchorOffset(state, SheetValue.PartiallyExpanded)
                try {
                    StemwiseSheetMotionBridge.draggableState(state).drag(MutatePriority.UserInput) {
                        events.collect { event ->
                            val target = start + (collapsed - start) * event.progress.coerceIn(0f, 1f)
                            dragBy(target - state.requireOffset())
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (scope.isActive && owner.generation == generation) {
                        onGestureReleased()
                        cancellationOwnsPreview = true
                        // AndroidX cancels the gesture job too; restoration belongs to the mounted sheet.
                        owner.cancellationSettle = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                state.expandWithStemwiseMotion(motion)
                            } finally {
                                if (owner.generation == generation) onPreviewChanged(false)
                            }
                        }
                    }
                    throw cancelled
                }
                onGestureReleased()
                state.collapseWithStemwiseMotion(motion)
            } finally {
                if (!cancellationOwnsPreview && owner.generation == generation) onPreviewChanged(false)
            }
        }
    }
}
