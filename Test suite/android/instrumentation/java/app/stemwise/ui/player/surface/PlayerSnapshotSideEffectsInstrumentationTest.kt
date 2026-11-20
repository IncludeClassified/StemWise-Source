package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.*
import org.junit.Assert.*
import org.junit.Test

/** Read the real loader request set after normal publication or physical transport activation. */
internal class PlayerSnapshotSideEffectsInstrumentationTest : NativePlayerSurfaceFixture() {
    @Test fun repeatAtQueueEdgeRequestsIdenticalPredictiveArtworkForFullNarrowAndPhysicalChanges() {
        activityRule.scenario.onActivity { host ->
            val seed = completeOriginalLoopSnapshot()
            val songs = (0..4).map { index -> seed.catalog.songAt(1).copy(queueKey = 200L + index,
                songId = "repeat-art-$index", mediaRevision = "repeat-media-$index",
                artworkSource = "content://app.stemwise.artwork/repeat-$index") }
            val initial = seed.copy(
                owner = seed.owner.copy(songId = songs[0].songId, mediaRevision = songs[0].mediaRevision),
                visualSelectionSongId = songs[0].songId,
                catalog = FullPlayerCatalogPacket(seed.catalog.revision, songs),
                queue = FullPlayerQueuePacket(seed.queue.revision, songs.map { it.queueKey }.toLongArray()),
                selection = FullPlayerSelectionPacket(seed.selection.revision, 0),
                waveform = FullPlayerWaveformPacket(seed.waveform.revision, songs[0].mediaRevision,
                    seed.waveform.copyPeaks(), seed.waveform.sections()),
                transport = seed.transport.copy(repeatMode = PlayerSurfaceRepeatMode.OFF))
            assertNull(FullPlayerSnapshotValidator.validate(initial))
            for (route in listOf("narrow", "full", "physical")) {
                val sink = RecordingSink()
                val (root, surface) = qualifiedSurface(host, initial, sink)
                try {
                    assertEquals(setOf(songs[2].artworkSource), predictiveSources(surface))
                    if (route == "physical") {
                        tap(surface, requireNotNull(surface.semanticsGeometry()).repeat)
                        assertEquals(listOf(PlayerSurfaceTransportCommand.REPEAT_CYCLE), sink.transport)
                    } else {
                        val next = initial.copy(revision = initial.revision + 10,
                            transport = initial.transport.copy(revision = initial.transport.revision + 1,
                                repeatMode = PlayerSurfaceRepeatMode.ALL),
                            status = if (route == "full") initial.status.copy(revision = initial.status.revision + 1)
                                else initial.status)
                        assertEquals(if (route == "full") PlayerSurfaceSnapshotUpdatePlan.Full
                            else PlayerSurfaceSnapshotUpdatePlan.TransportOnly,
                            PlayerSurfaceSnapshotUpdatePlanner.classify(initial, next))
                        assertEquals(FullPlayerAdoptionResult.APPLIED_UPDATE,
                            surface.bind(next, sink, PlayerSurfacePositionProvider { 10_000L },
                                1_200, 2_800, host.resources.displayMetrics.density,
                                PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT))
                    }
                    assertEquals(route, setOf(songs[4].artworkSource, songs[2].artworkSource),
                        predictiveSources(surface))
                } finally { root.removeAllViews() }
            }
        }
    }

    private fun predictiveSources(surface: PlayerSurfaceView): Set<String?> {
        val loader = PlayerSurfaceView::class.java.getDeclaredField("artworkLoader")
            .apply { isAccessible = true }.get(surface)
        val predictive = PlayerSurfaceArtworkLoader::class.java.getDeclaredField("predictive")
            .apply { isAccessible = true }.get(loader) as Map<*, *>
        return predictive.keys.map { (it as PlayerSurfaceArtworkRequest).source }.toSet()
    }
}
