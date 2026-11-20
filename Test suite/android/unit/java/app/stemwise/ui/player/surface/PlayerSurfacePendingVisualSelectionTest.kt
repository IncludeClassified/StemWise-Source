package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerCatalogSong

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfacePendingVisualSelectionTest {
    @Test
    fun acceptedExternalQueueTargetReanchorsBeforeCallbackOwnerCatchesUp() {
        assertEquals(2, playerSurfaceVisualSelectionIndex(0, "song-c", songs()))
        assertEquals(1, playerSurfaceVisualSelectionIndex(1, "missing", songs()))
    }

    @Test
    fun acceptedTargetSurvivesSameOwnerStalePublications() {
        val queue = queue(revision = 4L)
        val songs = songs()
        val pending = PlayerSurfacePendingVisualSelection()

        pending.recordAccepted(
            ownerGeneration = 12L,
            queue = queue,
            song = songs[2],
            queueIndex = 2
        )

        val first = pending.resolve(
            12L, "song-b", queue, songs, visualSelectionSongId = "song-c"
        )
        val second = pending.resolve(
            12L, "song-b", queue, songs(), visualSelectionSongId = "song-c"
        )

        assertEquals(2, first?.queueIndex)
        assertFalse(requireNotNull(first).authoritativeOwnerCaughtUp)
        assertEquals(2, second?.queueIndex)
        assertFalse(requireNotNull(second).authoritativeOwnerCaughtUp)
    }

    @Test
    fun exactAuthoritativeOwnerCatchUpPreservesAcceptedSpringOnce() {
        val queue = queue(revision = 4L)
        val songs = songs()
        val pending = PlayerSurfacePendingVisualSelection()
        pending.recordAccepted(12L, queue, songs[2], 2)

        val caughtUp = pending.resolve(
            ownerGeneration = 13L,
            authoritativeSongId = "song-c",
            queue = queue(revision = 9L, currentIndex = 2),
            songs = songs
        )

        assertEquals(2, caughtUp?.queueIndex)
        assertEquals("song-c", caughtUp?.songId)
        assertEquals(30L, caughtUp?.queueKey)
        assertTrue(requireNotNull(caughtUp).authoritativeOwnerCaughtUp)
        assertNull(pending.resolve(13L, "song-c", queue(9L, 2), songs))
    }

    @Test
    fun newerVisualIntentInvalidatesPendingTarget() {
        val originalQueue = queue(revision = 4L)
        val songs = songs()
        val pending = PlayerSurfacePendingVisualSelection()
        pending.recordAccepted(12L, originalQueue, songs[2], 2)

        assertNull(
            pending.resolve(
                12L,
                "song-b",
                queue(revision = 5L),
                songs,
                visualSelectionSongId = "song-a"
            )
        )
        assertNull(pending.resolve(12L, "song-b", originalQueue, songs))
    }

    @Test
    fun sameOwnerSelectionRevisionAtAcceptedIndexRetainsPendingTarget() {
        val originalQueue = queue(revision = 4L)
        val songs = songs()
        val pending = PlayerSurfacePendingVisualSelection()
        pending.recordAccepted(12L, originalQueue, songs[2], 2)

        val retained = pending.resolve(
            ownerGeneration = 12L,
            authoritativeSongId = "song-b",
            queue = queue(revision = 5L, currentIndex = 2),
            songs = songs,
            visualSelectionSongId = "song-c"
        )

        assertEquals(2, retained?.queueIndex)
        assertEquals("song-c", retained?.songId)
        assertFalse(requireNotNull(retained).authoritativeOwnerCaughtUp)
    }

    @Test
    fun missingOrMisalignedTargetCannotOutliveQueueEvidence() {
        val queue = queue(revision = 4L)
        val songs = songs()
        val pending = PlayerSurfacePendingVisualSelection()
        pending.recordAccepted(12L, queue, songs[2], 2)

        val replaced = listOf(songs[0], songs[1], song(40L, "song-d"))
        assertNull(pending.resolve(12L, "song-b", queue, replaced))
        assertNull(pending.resolve(12L, "song-b", queue, songs))
    }

    @Test
    fun invalidAcceptanceDoesNotCreateAVisualOverride() {
        val queue = queue(revision = 4L)
        val pending = PlayerSurfacePendingVisualSelection()

        pending.recordAccepted(
            ownerGeneration = 12L,
            queue = queue,
            song = song(999L, "not-in-queue"),
            queueIndex = 2
        )

        assertNull(pending.resolve(12L, "song-b", queue, songs()))
    }

    @Test
    fun intermediateDecoderOwnerCannotDiscardTheNewerAcceptedQueueTarget() {
        val originalQueue = queue(revision = 4L)
        val songs = songs()
        val pending = PlayerSurfacePendingVisualSelection()
        pending.recordAccepted(12L, originalQueue, songs[2], 2)

        val intermediate = pending.resolve(
            ownerGeneration = 13L,
            authoritativeSongId = "song-b",
            // The complete hydrated owner is still song-b at index 1. It is not the separately
            // owned latest visual intent and therefore cannot supersede song-c.
            queue = queue(revision = 9L, currentIndex = 1),
            songs = songs,
            visualSelectionSongId = "song-c"
        )
        assertEquals(2, intermediate?.queueIndex)
        assertEquals("song-c", intermediate?.songId)
        assertFalse(requireNotNull(intermediate).authoritativeOwnerCaughtUp)

        val caughtUp = pending.resolve(
            ownerGeneration = 14L,
            authoritativeSongId = "song-c",
            queue = queue(revision = 10L, currentIndex = 2),
            songs = songs,
            visualSelectionSongId = "song-c"
        )
        assertTrue(requireNotNull(caughtUp).authoritativeOwnerCaughtUp)
        assertNull(pending.resolve(14L, "song-c", queue(10L, 2), songs))
    }

    @Test
    fun genuinelyDifferentVisualIntentReanchorsAndClearsPending() {
        val originalQueue = queue(revision = 4L)
        val songs = songs()
        val pending = PlayerSurfacePendingVisualSelection()

        pending.recordAccepted(12L, originalQueue, songs[2], 2)
        assertNull(
            pending.resolve(
                ownerGeneration = 13L,
                authoritativeSongId = "song-a",
                queue = queue(revision = 9L, currentIndex = 1),
                songs = songs,
                visualSelectionSongId = "song-a"
            )
        )
    }

    @Test
    fun logicalQueueChangeDuringOwnerCatchUpMustReanchor() {
        val originalQueue = queue(revision = 4L)
        val songs = songs()
        val pending = PlayerSurfacePendingVisualSelection()
        pending.recordAccepted(12L, originalQueue, songs[2], 2)
        val changedQueue = PlayerSurfaceQueuePacket(
            revision = 9L,
            itemKeys = longArrayOf(20L, 10L, 30L),
            currentIndex = 2
        )
        val changedSongs = listOf(songs[1], songs[0], songs[2])

        assertNull(pending.resolve(13L, "song-c", changedQueue, changedSongs))
        assertNull(pending.resolve(12L, "song-b", originalQueue, songs))
    }

    @Test
    fun staleGenerationCannotConsumeAcceptedTarget() {
        val originalQueue = queue(revision = 4L)
        val songs = songs()
        val pending = PlayerSurfacePendingVisualSelection()
        pending.recordAccepted(12L, originalQueue, songs[2], 2)

        assertNull(pending.resolve(11L, "song-c", originalQueue, songs))
        assertNull(pending.resolve(12L, "song-b", originalQueue, songs))
    }

    private fun queue(
        revision: Long,
        currentIndex: Int = 1
    ): PlayerSurfaceQueuePacket = PlayerSurfaceQueuePacket(
        revision = revision,
        itemKeys = longArrayOf(10L, 20L, 30L),
        currentIndex = currentIndex
    )

    private fun songs(): List<FullPlayerCatalogSong> = listOf(
        song(10L, "song-a"),
        song(20L, "song-b"),
        song(30L, "song-c")
    )

    private fun song(queueKey: Long, songId: String): FullPlayerCatalogSong =
        FullPlayerCatalogSong(
            queueKey = queueKey,
            songId = songId,
            mediaRevision = "media-$songId",
            title = songId,
            artist = "artist",
            artworkSource = null,
            accentArgb = 0
        )
}
