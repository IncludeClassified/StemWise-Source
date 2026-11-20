package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerTestFixtures
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfaceLayoutOwnerTest {
    @Test
    fun maximumQueueSelectionSharesMembershipAndUsesItsIndexedCatalog() {
        val catalog = FullPlayerTestFixtures.catalog(size = 50_000)
        val snapshot = FullPlayerTestFixtures.snapshot(catalog = catalog,
            queue = FullPlayerTestFixtures.queue(catalog))
        val projection = PlayerSurfaceLayoutOwner()
        val first = projection.profile(snapshot, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
        val next = projection.profile(snapshot.copy(revision = snapshot.revision + 2,
            selection = snapshot.selection.copy(revision = snapshot.selection.revision + 1, currentIndex = 49_999)),
            1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
        assertSame(snapshot.queue.membership, first.queue.membership)
        assertSame(first.queue.membership, next.queue.membership)
        assertTrue(next.queue.currentIndex == 49_999)
        val pending = PlayerSurfacePendingVisualSelection()
        val target = catalog.last()
        pending.recordAccepted(1L, first.queue, target, 49_999)
        assertTrue(pending.resolve(1L, target.songId, next.queue, catalog)?.queueIndex == 49_999)
        assertTrue(playerSurfaceVisualSelectionIndex(0, target.songId, catalog) == 49_999)
        val shuffled = PlayerSurfaceQueuePacket(next.queue.revision + 1,
            next.queue.copyItemKeys().reversedArray(), 0)
        assertTrue(pending.resolve(1L, target.songId, shuffled, catalog) == null)
    }

    @Test
    fun fourHundredSongTransportUpdateReusesQueueAndLayout() {
        val catalog = FullPlayerTestFixtures.catalog(size = 400)
        val snapshot = FullPlayerTestFixtures.snapshot(
            catalog = catalog,
            queue = FullPlayerTestFixtures.queue(catalog),
            sourceMode = PlayerSurfaceSourceMode.STEMS
        )
        val projection = PlayerSurfaceLayoutOwner()
        val firstProfile = projection.profile(
            snapshot, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        )
        val transportUpdate = snapshot.copy(
            revision = snapshot.revision + 2L,
            transport = snapshot.transport.copy(
                revision = snapshot.transport.revision + 1L,
                shuffleEnabled = !snapshot.transport.shuffleEnabled
            )
        )

        val nextProfile = projection.profile(
            transportUpdate, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        )

        assertSame(firstProfile.queue, nextProfile.queue)
        assertSame(firstProfile.layout, nextProfile.layout)
    }

    @Test
    fun selectedToolChangeReusesProjectedLayoutWhileProfileRootAdvances() {
        val catalog = FullPlayerTestFixtures.catalog()
        val queue = FullPlayerTestFixtures.queue(catalog)
        val none = FullPlayerTestFixtures.snapshot(catalog = catalog, queue = queue)
        val tuneDetail = FullPlayerTestFixtures.snapshot(
            catalog = catalog,
            queue = queue,
            selectedTool = PlayerSurfaceTool.TUNE
        ).toolDetail.copy(revision = none.toolDetail.revision + 1L)
        val tune = none.copy(
            revision = none.revision + 10L,
            toolDetail = tuneDetail
        )
        val projection = PlayerSurfaceLayoutOwner()
        val first = projection.profile(
            none, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        )
        val second = projection.profile(
            tune, 1440, 2800, 3f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
        )

        assertSame(first.layout, second.layout)
        assertTrue(second.profileRevision > first.profileRevision)
    }
}
