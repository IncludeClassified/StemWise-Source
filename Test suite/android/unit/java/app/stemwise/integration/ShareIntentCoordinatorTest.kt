package app.stemwise.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntentCoordinatorTest {
    @Test fun multipleSharedSongLinksRequireCorrectionInsteadOfChoosingTheFirst() {
        val coordinator = ShareIntentCoordinator()
        val text = "https://youtu.be/abcdefghijk\nhttps://youtu.be/lmnopqrstuv"
        coordinator.offerText(text)
        val state = coordinator.playlist.value
        assertTrue(state is SharedPlaylistState.Error)
        assertEquals(text, (state as SharedPlaylistState.Error).editableText)
    }

    @Test
    fun songSharesOfferEditableConfirmationWithoutSubmittingWork() {
        val coordinator = ShareIntentCoordinator()
        for (url in listOf("https://www.youtube.com/watch?v=abcdefghijk",
            "https://music.youtube.com/watch?v=abcdefghijk", "https://youtu.be/abcdefghijk",
            "https://www.youtube.com/shorts/abcdefghijk")) {
            val id = coordinator.offerText("Listen to this: $url")
            val offered = coordinator.playlist.value as SharedPlaylistState.AddSong
            assertEquals(id, offered.offerId)
            assertEquals("abcdefghijk", offered.videoId)
            assertEquals(url, offered.originalText)
        }
        val playlistId = coordinator.offerText("https://www.youtube.com/watch?v=abcdefghijk&list=PLabcdefghijk")
        assertTrue(coordinator.playlist.value is SharedPlaylistState.Confirm)
        assertTrue(coordinator.isCurrent(playlistId))
        val invalid = coordinator.offerText("https://youtube.com.evil.example/watch?v=abcdefghijk")
        assertTrue(coordinator.playlist.value is SharedPlaylistState.Error)
        coordinator.recheck(invalid, "https://youtu.be/abcdefghijk")
        assertTrue(coordinator.playlist.value is SharedPlaylistState.AddSong)
        val active = requireNotNull(coordinator.playlist.value.offerId)
        assertFalse(coordinator.dismiss(invalid))
        assertTrue(coordinator.dismiss(active))
        assertEquals(SharedPlaylistState.Idle, coordinator.playlist.value)
    }

    @Test
    fun offersAreMonotonicAndNewestOfferWins() {
        val coordinator = ShareIntentCoordinator()
        val firstLink = link("playlist-a")
        val secondLink = link("playlist-b")

        val firstOfferId = coordinator.confirm(firstLink)
        val secondOfferId = coordinator.confirm(secondLink)

        assertTrue(secondOfferId > firstOfferId)
        assertFalse(coordinator.isCurrent(firstOfferId))
        assertTrue(coordinator.isCurrent(secondOfferId))
        assertEquals(
            SharedPlaylistState.Confirm(secondOfferId, secondLink),
            coordinator.playlist.value
        )
    }

    @Test
    fun stalePreviewCallbacksCannotOverwriteOrDismissNewOffer() {
        val coordinator = ShareIntentCoordinator()
        val firstOfferId = coordinator.confirm(link("playlist-a"))
        val secondLink = link("playlist-b")
        val secondOfferId = coordinator.confirm(secondLink)

        assertFalse(coordinator.notice(firstOfferId, "Stale preview finished"))
        assertFalse(coordinator.dismiss(firstOfferId))
        assertEquals(
            SharedPlaylistState.Confirm(secondOfferId, secondLink),
            coordinator.playlist.value
        )
    }

    @Test
    fun completedOlderSyncCannotOverwriteNewConfirmation() {
        val coordinator = ShareIntentCoordinator()
        val syncOfferId = coordinator.syncing()
        val newLink = link("playlist-b")
        val newOfferId = coordinator.confirm(newLink)

        assertFalse(coordinator.notice(syncOfferId, "Sync complete"))
        assertEquals(
            SharedPlaylistState.Confirm(newOfferId, newLink),
            coordinator.playlist.value
        )
    }

    @Test
    fun currentSyncPreservesSyncingNoticeAndDismissFlow() {
        val coordinator = ShareIntentCoordinator()

        val offerId = coordinator.syncing()

        assertEquals(SharedPlaylistState.Syncing(offerId), coordinator.playlist.value)
        assertTrue(coordinator.notice(offerId, "Sync complete"))
        assertEquals(
            SharedPlaylistState.Notice(offerId, "Sync complete"),
            coordinator.playlist.value
        )
        assertTrue(coordinator.dismiss(offerId))
        assertEquals(SharedPlaylistState.Idle, coordinator.playlist.value)
    }

    private fun link(id: String) = PlaylistLink(
        id = id,
        canonicalUrl = "https://music.youtube.com/playlist?list=$id"
    )
}
