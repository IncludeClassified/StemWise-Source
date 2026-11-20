package app.stemwise.ui;

import androidx.compose.animation.core.AnimationSpec;
import androidx.compose.animation.core.FiniteAnimationSpec;
import androidx.annotation.OptIn;
import androidx.compose.material3.ExperimentalMaterial3Api;
import androidx.compose.material3.SheetState;
import androidx.compose.material3.SheetValue;
import androidx.compose.foundation.gestures.DraggableState;

/**
 * Installs StemWise's accepted motion on Material3's retained sheet state.
 *
 * Material3 1.4 does not expose these three setters to Kotlin even though they are public JVM
 * methods. This intentionally pinned bridge makes an AndroidX ABI change fail at compile time
 * instead of silently falling back to Material's unrelated motion scheme.
 */
@OptIn(markerClass = ExperimentalMaterial3Api.class)
public final class StemwiseSheetMotionBridge {
    private StemwiseSheetMotionBridge() {}

    public static DraggableState draggableState(SheetState state) {
        return state.getAnchoredDraggableState$material3().getDraggableState$material3();
    }

    public static float anchorOffset(SheetState state, SheetValue value) {
        return state.getAnchoredDraggableState$material3().getAnchors().positionOf(value);
    }

    public static void install(
            SheetState state,
            FiniteAnimationSpec<Float> expandSpec,
            FiniteAnimationSpec<Float> collapseSpec,
            AnimationSpec<Float> dragSettleSpec
    ) {
        state.setShowMotionSpec$material3(expandSpec);
        state.setHideMotionSpec$material3(collapseSpec);
        state.setAnchoredDraggableMotionSpec$material3(dragSettleSpec);
    }
}
