package app.stemwise.ui

import app.stemwise.playback.engine.*
import androidx.compose.ui.graphics.Color
import app.stemwise.model.Stem
import app.stemwise.ui.player.surface.PlayerSurfaceVolumeGesture
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativeMixerGestureOwnerTest {
    private fun lease(lane: Int) = EngineStemMixPreviewLease(
        EngineStemMixPreviewAuthority(1, EngineSelectionToken(1), NativeRuntimeOwnership(1, 1), "fixture", 1), lane, 1)
    private fun stem(lane: Int) = Stem("lane-$lane", "Lane $lane", Color.Red, .8f, false, false)

    @Test fun togglesAndOtherLanesCannotRetireOrOverwriteTheHeldVolumeTransaction() {
        val owner = NativeMixerGestureOwner()
        val slot = StemUiSlot.from(stem(0))
        val held = PlayerSurfaceVolumeGesture(7L, 0)
        val other = PlayerSurfaceVolumeGesture(7L, 1)
        assertTrue(owner.begin(held, 3, lease(3)))
        assertTrue(owner.begin(other, 4, lease(4)))
        slot.previewSliderLogical(.4f)
        slot.commitNative(slot.snapshot().copy(muted = true, solo = true))
        assertTrue(owner.owns(held))
        slot.previewSliderLogical(.2f)
        assertEquals(.2f, slot.snapshot().volume, 0f)
        assertFalse("Raising the slider unmutes its lane", slot.snapshot().muted)
        assertTrue(slot.snapshot().solo)
        assertEquals(.4f, slot.committedVolume.floatValue, 0f)
        slot.rollbackVolumePreview()
        assertEquals(.4f, slot.snapshot().volume, 0f)
        assertTrue(slot.snapshot().muted)
        assertTrue(slot.snapshot().solo)
        assertTrue(owner.finish(other))
        assertTrue(owner.owns(held))
        assertTrue(owner.finish(held))
        assertFalse(owner.owns(held))
    }

    @Test fun zeroSliderRetainsLastPositiveGainAndRollbackRestoresTheAcceptedMute() {
        val slot = StemUiSlot.from(stem(0))
        slot.previewSliderLogical(.35f)
        slot.previewSliderLogical(0f)
        assertEquals(.35f, slot.snapshot().volume, 0f)
        assertTrue(slot.snapshot().muted)
        slot.commitNative(slot.sliderSnapshot(0f))
        assertEquals(0f, stemSliderEffectiveFraction(slot.committedVolume.floatValue, slot.muted.value), 0f)
        slot.previewSliderLogical(.2f)
        assertFalse(slot.snapshot().muted)
        slot.rollbackVolumePreview()
        assertEquals(.35f, slot.snapshot().volume, 0f)
        assertTrue(slot.snapshot().muted)
        val raised = slot.sliderSnapshot(.6f)
        assertEquals(.6f, raised.volume, 0f)
        assertFalse(raised.muted)
        val empty = StemUiSlot.from(stem(0).copy(volume = 0f))
        assertEquals(0f, stemSliderEffectiveFraction(empty.snapshot().volume, empty.snapshot().muted), 0f)
        assertEquals(1f, empty.sliderSnapshot(0f).volume, 0f)
    }

    @Test fun staleOrLookalikeHandlesCannotFinishAReplacementGesture() {
        val owner = NativeMixerGestureOwner()
        val first = PlayerSurfaceVolumeGesture(7L, 0)
        val lookalike = PlayerSurfaceVolumeGesture(7L, 0)
        assertTrue(owner.begin(first, 2, lease(2)))
        assertFalse(owner.begin(lookalike, 2, lease(2)))
        assertFalse(owner.finish(lookalike))
        assertEquals(-1, owner.canonicalLaneIndex(lookalike))
        assertTrue(owner.owns(first))
        assertTrue(owner.finish(first))
        val replacement = PlayerSurfaceVolumeGesture(8L, 0)
        assertTrue(owner.begin(replacement, 2, lease(2)))
        assertFalse(owner.finish(first))
        assertTrue(owner.owns(replacement))
        assertEquals(2, owner.canonicalLaneIndex(replacement))
    }
}
