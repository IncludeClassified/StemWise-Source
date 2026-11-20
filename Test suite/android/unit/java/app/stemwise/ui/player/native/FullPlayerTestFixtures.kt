package app.stemwise.ui.player.nativeplayer

import app.stemwise.ui.player.surface.PlayerSurfaceMixerPacket
import app.stemwise.ui.player.surface.PlayerSurfaceOwnerKey
import app.stemwise.ui.player.surface.PlayerSurfaceRepeatMode
import app.stemwise.ui.player.surface.PlayerSurfaceSourceMode
import app.stemwise.ui.player.surface.PlayerSurfaceSourcePacket
import app.stemwise.ui.player.surface.PlayerSurfaceSourcePhase
import app.stemwise.ui.player.surface.PlayerSurfaceTimelinePacket
import app.stemwise.ui.player.surface.PlayerSurfaceTool
import app.stemwise.ui.player.surface.PlayerSurfaceTransportPacket

internal object FullPlayerTestFixtures {
    fun catalog(size: Int = 3, revision: Long = 1L): FullPlayerCatalogPacket =
        FullPlayerCatalogPacket(
            revision = revision,
            songs = List(size) { index ->
                FullPlayerCatalogSong(
                    queueKey = 10_000L + index,
                    songId = "song-$index",
                    mediaRevision = "media-$index",
                    title = "Song $index",
                    artist = "Artist $index",
                    artworkSource = "art-$index",
                    accentArgb = 0xFF00AA88.toInt() + index
                )
            }
        )

    fun queue(catalog: FullPlayerCatalogPacket, revision: Long = 2L): FullPlayerQueuePacket =
        FullPlayerQueuePacket(
            revision = revision,
            itemKeys = LongArray(catalog.size) { catalog.songAt(it).queueKey }
        )

    fun snapshot(
        generation: Long = 1L,
        selectedIndex: Int = 0,
        baseRevision: Long = 10L,
        catalog: FullPlayerCatalogPacket = catalog(),
        queue: FullPlayerQueuePacket = queue(catalog),
        sourceMode: PlayerSurfaceSourceMode = PlayerSurfaceSourceMode.ORIGINAL,
        selectedTool: PlayerSurfaceTool = PlayerSurfaceTool.NONE,
        status: FullPlayerStatusPacket = FullPlayerStatusPacket(baseRevision + 9L)
    ): FullPlayerSnapshot {
        val song = catalog.songForQueueKey(queue.itemKeyAt(selectedIndex))!!
        val detail = detail(selectedTool, song.mediaRevision)
        val loop = detail as? FullPlayerDetailState.Loop
        val waveform = FullPlayerWaveformPacket(
            revision = baseRevision + 2L,
            mediaRevision = song.mediaRevision,
            peaks = floatArrayOf(.1f, .4f, .8f, .3f),
            sections = listOf(
                FullPlayerSection(0L, 10_000L, "Intro"),
                FullPlayerSection(10_000L, 30_000L, "Verse")
            )
        )
        val timeline = PlayerSurfaceTimelinePacket(
            revision = baseRevision + 3L,
            durationMs = 60_000L,
            positionMs = 1_000L,
            loopStartMs = loop?.loopStartMs ?: -1L,
            loopEndMs = loop?.loopEndMs ?: -1L,
            waveformRevision = waveform.revision
        )
        val laneCount = if (sourceMode == PlayerSurfaceSourceMode.STEMS) 6 else 0
        val laneKeys = IntArray(laneCount) { it + 1 }
        val source = PlayerSurfaceSourcePacket(
            revision = baseRevision + 4L,
            presentedMode = sourceMode,
            audibleMode = sourceMode,
            phase = PlayerSurfaceSourcePhase.SETTLED,
            stemsAvailable = sourceMode == PlayerSurfaceSourceMode.STEMS
        )
        val mixer = PlayerSurfaceMixerPacket(
            revision = baseRevision + 5L,
            packageRevision = baseRevision + 4L,
            laneKeys = laneKeys,
            volumes = FloatArray(laneCount) { .82f },
            laneFlags = IntArray(laneCount)
        )
        val roles = listOf(FullPlayerStemRole.VOCALS, FullPlayerStemRole.DRUMS,
            FullPlayerStemRole.BASS, FullPlayerStemRole.GUITAR, FullPlayerStemRole.PIANO,
            FullPlayerStemRole.REMAINING, FullPlayerStemRole.VIOLIN, FullPlayerStemRole.FLUTE,
            FullPlayerStemRole.TRUMPET)
        val sourceMixer = FullPlayerSourceMixerPacket(
            revision = baseRevision + 6L,
            source = source,
            mixer = mixer,
            lanes = List(laneCount) { lane ->
                FullPlayerMixerLaneVisual(
                    laneKey = laneKeys[lane],
                    role = roles[lane],
                    label = roles[lane].name.lowercase().replaceFirstChar(Char::uppercase),
                    colorArgb = 0xFF44CC88.toInt() + lane
                )
            })
        val activity = when (detail) {
            FullPlayerDetailState.None ->
                FullPlayerToolActivity(false, false, false, false, true)
            is FullPlayerDetailState.Loop ->
                FullPlayerToolActivity(true, false, false, false, true)
            is FullPlayerDetailState.Tune ->
                FullPlayerToolActivity(false, true, false, false, true)
            is FullPlayerDetailState.Lyrics ->
                FullPlayerToolActivity(false, false, true, false, true)
            is FullPlayerDetailState.Metronome ->
                FullPlayerToolActivity(false, false, false, true, true)
        }
        return FullPlayerSnapshot(
            revision = baseRevision + 10L,
            owner = PlayerSurfaceOwnerKey(
                generation = generation,
                songId = song.songId,
                mediaRevision = song.mediaRevision,
                audioRevision = "audio-${song.songId}",
                stemRevision = "stems-${song.songId}"
            ),
            catalog = catalog,
            queue = queue,
            selection = FullPlayerSelectionPacket(baseRevision + 1L, selectedIndex),
            timeline = timeline,
            waveform = waveform,
            fineWindow = null,
            sourceMixer = sourceMixer,
            transport = PlayerSurfaceTransportPacket(
                revision = baseRevision + 7L,
                playWhenReady = true,
                repeatMode = PlayerSurfaceRepeatMode.OFF,
                shuffleEnabled = false,
                canGoPrevious = true,
                canGoNext = true
            ),
            toolDetail = FullPlayerToolDetailPacket(
                revision = baseRevision + 8L,
                selectedTool = selectedTool,
                activity = activity,
                detail = detail
            ),
            status = status
        )
    }

    private fun detail(
        selectedTool: PlayerSurfaceTool,
        mediaRevision: String
    ): FullPlayerDetailState = when (selectedTool) {
        PlayerSurfaceTool.NONE -> FullPlayerDetailState.None
        PlayerSurfaceTool.LOOP -> FullPlayerDetailState.Loop(
            durationMs = 60_000L,
            loopStartMs = 5_000L,
            loopEndMs = 15_000L
        )
        PlayerSurfaceTool.TUNE -> FullPlayerDetailState.Tune(
            originalKey = "C",
            tempo = 1.25f,
            semitoneShift = 2
        )
        PlayerSurfaceTool.LYRICS -> FullPlayerDetailState.Lyrics(
            mediaRevision = mediaRevision,
            contentRevision = 3L,
            trackCount = 3
        )
        PlayerSurfaceTool.METRONOME -> FullPlayerDetailState.Metronome(
            enabled = true,
            bpm = 120f,
            subdivision = 2,
            numerator = 4,
            denominator = 4,
            volume = .8f,
            timingAvailable = true
        )
    }
}
