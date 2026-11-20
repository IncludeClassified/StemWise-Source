package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerAdoptionResult
import app.stemwise.ui.player.nativeplayer.FullPlayerStateStore
import app.stemwise.ui.player.nativeplayer.FullPlayerTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlayerSurfaceSnapshotBindGateTest {
    @Test
    fun fourHundredUnchangedRouteFramesPerformZeroAdditionalStructuralAdoptions() {
        val snapshot = FullPlayerTestFixtures.snapshot()
        val gate = PlayerSurfaceSnapshotBindGate()

        assertFalse(gate.isAcceptedUnchanged(
            snapshot, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        ))
        gate.recordStructuralAdoption(
            snapshot, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        )
        repeat(400) {
            assertTrue(gate.isAcceptedUnchanged(
                snapshot, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            ))
        }

    }

    @Test
    fun layoutIdentityChangeRequiresOneNewStructuralProjection() {
        val snapshot = FullPlayerTestFixtures.snapshot()
        val gate = PlayerSurfaceSnapshotBindGate()
        gate.recordStructuralAdoption(
            snapshot, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        )

        assertFalse(gate.isAcceptedUnchanged(
            snapshot, 1400, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        ))
    }

    @Test
    fun sameRevisionChangedPayloadMustNotTakeUnchangedFastPath() {
        val accepted = FullPlayerTestFixtures.snapshot()
        val collidingPayload = accepted.copy(
            timeline = accepted.timeline.copy(positionMs = accepted.timeline.positionMs + 1_000L)
        )
        val authoritativeStore = FullPlayerStateStore()
        assertEquals(FullPlayerAdoptionResult.APPLIED_INITIAL, authoritativeStore.adopt(accepted))
        assertEquals(
            FullPlayerAdoptionResult.REJECTED_REVISION_COLLISION,
            authoritativeStore.adopt(collidingPayload)
        )

        val gate = PlayerSurfaceSnapshotBindGate()
        gate.recordStructuralAdoption(
            accepted, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        )

        // A revision is immutable only after content identity has also been proven.
        assertFalse(
            "same-revision changed content bypassed collision validation as UNCHANGED",
            gate.isAcceptedUnchanged(
                collidingPayload, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            )
        )
    }

    @Test
    fun viewGateRequiresAuthoritativePublicationIdentity() {
        val accepted = FullPlayerTestFixtures.snapshot()
        val collision = accepted.copy(
            transport = accepted.transport.copy(
                playWhenReady = !accepted.transport.playWhenReady
            )
        )
        val gate = PlayerSurfaceSnapshotBindGate()
        gate.recordStructuralAdoption(
            accepted, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        )
        assertFalse(gate.isAcceptedUnchanged(
            collision, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        ))
        assertFalse(gate.isAcceptedUnchanged(
            accepted.copy(revision = accepted.revision + 1L),
            1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        ))
        assertTrue(gate.isAcceptedUnchanged(
            accepted, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        ))
    }

    @Test
    fun identityTextOriginIsContinuousAcrossFormerHalfwayThreshold() {
        val before = playerSurfaceIdentityTextOriginX(40f, 440f, 200f, .4999f)
        val after = playerSurfaceIdentityTextOriginX(40f, 440f, 200f, .5001f)

        assertTrue(abs(after - before) < .1f)
        assertEquals(40f, playerSurfaceIdentityTextOriginX(40f, 440f, 200f, 0f), .001f)
        assertEquals(140f, playerSurfaceIdentityTextOriginX(40f, 440f, 200f, 1f), .001f)
    }
}
