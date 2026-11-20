package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerCatalogSong

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfacePagerSpringContinuityTest {
    @Test
    fun fullyClippedPagesAreNotSubmittedButAnyVisibleFractionIs() {
        val left = 20f
        val right = 1020f
        val width = right - left

        assertFalse(playerSurfacePageIntersectsViewport(left - width, width, left, right))
        assertFalse(playerSurfacePageIntersectsViewport(right, width, left, right))
        assertTrue(playerSurfacePageIntersectsViewport(left, width, left, right))
        assertTrue(
            playerSurfacePageIntersectsViewport(left - width + .01f, width, left, right)
        )
        assertTrue(playerSurfacePageIntersectsViewport(right - .01f, width, left, right))
        assertFalse(playerSurfacePageIntersectsViewport(left, 0f, left, right))
    }

    @Test
    fun exactAuthoritativeCatchUpKeepsResidualOffsetAndVelocity() {
        val visual = song(30L, "song-c")
        val preserve = preserveAcceptedPlayerSurfacePagerContinuity(
            ownerChanged = true,
            queueChanged = true,
            layoutChanged = false,
            logicalQueueUnchanged = true,
            visualSelectionSongId = "song-c",
            visualQueueIndex = 2,
            visual = visual
        )

        val spring = playerSurfacePagerSpringAfterCancellation(
            offsetFraction = -.21f,
            velocityFraction = -.74f,
            wasAnimating = true,
            preserveSpring = preserve
        )

        assertTrue(preserve)
        assertEquals(-.21f, spring.offsetFraction, 0f)
        assertEquals(-.74f, spring.velocityFraction, 0f)
        assertTrue(spring.animating)
        assertFalse(
            playerSurfacePagerRequiresReanchor(
                ownerChanged = true,
                queueChanged = true,
                preserveAcceptedSpring = preserve,
                visualQueueIndex = 2,
                desiredIndex = 2,
                visualSongId = "song-c",
                desiredSongId = "song-c"
            )
        )
    }

    @Test
    fun mismatchedVisualCannotCarrySpringAcrossOwnerGeneration() {
        val preserve = preserveAcceptedPlayerSurfacePagerContinuity(
            ownerChanged = true,
            queueChanged = true,
            layoutChanged = false,
            logicalQueueUnchanged = true,
            visualSelectionSongId = "song-c",
            visualQueueIndex = 1,
            visual = song(20L, "song-b")
        )
        val spring = playerSurfacePagerSpringAfterCancellation(
            offsetFraction = -.21f,
            velocityFraction = -.74f,
            wasAnimating = true,
            preserveSpring = preserve
        )

        assertFalse(preserve)
        assertEquals(-.21f, spring.offsetFraction, 0f)
        assertEquals(0f, spring.velocityFraction, 0f)
        assertTrue(spring.animating)
        assertTrue(
            playerSurfacePagerRequiresReanchor(
                ownerChanged = true,
                queueChanged = true,
                preserveAcceptedSpring = preserve,
                visualQueueIndex = 1,
                desiredIndex = 2,
                visualSongId = "song-b",
                desiredSongId = "song-c"
            )
        )
    }

    @Test
    fun reducedMotionSnapsPagerCancellationWithoutOwningAnotherFrame() {
        val spring = playerSurfacePagerSpringAfterCancellation(
            offsetFraction = -.42f,
            velocityFraction = -1.7f,
            wasAnimating = true,
            preserveSpring = true,
            reducedMotion = true
        )

        assertEquals(0f, spring.offsetFraction, 0f)
        assertEquals(0f, spring.velocityFraction, 0f)
        assertFalse(spring.animating)
    }

    @Test
    fun intermediateOwnerKeepsTheAcceptedPagerLeaseAndLiveGesture() {
        val visual = song(30L, "song-c")
        assertTrue(
            preserveAcceptedPlayerSurfacePagerContinuity(
                ownerChanged = true,
                queueChanged = true,
                layoutChanged = false,
                logicalQueueUnchanged = true,
                visualSelectionSongId = "song-c",
                visualQueueIndex = 2,
                visual = visual
            )
        )
        assertFalse(
            preserveAcceptedPlayerSurfacePagerContinuity(
                ownerChanged = true,
                queueChanged = true,
                layoutChanged = true,
                logicalQueueUnchanged = true,
                visualSelectionSongId = "song-c",
                visualQueueIndex = 2,
                visual = visual
            )
        )
    }

    @Test
    fun queueReorderOrNewIntentCannotMigratePagerOwnership() {
        val visual = song(30L, "song-c")
        assertFalse(
            preserveAcceptedPlayerSurfacePagerContinuity(
                ownerChanged = true,
                queueChanged = true,
                layoutChanged = false,
                logicalQueueUnchanged = false,
                visualSelectionSongId = "song-c",
                visualQueueIndex = 2,
                visual = visual
            )
        )
        assertFalse(
            preserveAcceptedPlayerSurfacePagerContinuity(
                ownerChanged = true,
                queueChanged = true,
                layoutChanged = false,
                logicalQueueUnchanged = true,
                visualSelectionSongId = "song-a",
                visualQueueIndex = 2,
                visual = visual
            )
        )
    }

    @Test
    fun queueChangeWithoutExactCatchUpAlwaysReanchors() {
        assertTrue(
            playerSurfacePagerRequiresReanchor(
                ownerChanged = false,
                queueChanged = true,
                preserveAcceptedSpring = false,
                visualQueueIndex = 2,
                desiredIndex = 2,
                visualSongId = "song-c",
                desiredSongId = "song-c"
            )
        )
    }

    private fun song(queueKey: Long, songId: String) = FullPlayerCatalogSong(
        queueKey = queueKey,
        songId = songId,
        mediaRevision = "media-$songId",
        title = songId,
        artist = "artist",
        artworkSource = null,
        accentArgb = 0
    )
}
