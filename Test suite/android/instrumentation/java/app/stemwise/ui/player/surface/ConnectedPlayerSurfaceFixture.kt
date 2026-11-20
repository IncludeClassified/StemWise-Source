package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.*

internal fun connectedStemsLoopSnapshot(): FullPlayerSnapshot {
    val mediaRevision = "connected-media-1"
    val currentSong = FullPlayerCatalogSong(
        queueKey = 10_001L,
        songId = "connected-native-song",
        mediaRevision = mediaRevision,
        title = "Sonic Flag",
        artist = "StemWise",
        artworkSource = null,
        accentArgb = 0xFFA78BFA.toInt()
    )
    val catalog = FullPlayerCatalogPacket(
        revision = 1L,
        songs = listOf(
            currentSong.copy(
                queueKey = 10_000L,
                songId = "connected-before-song",
                mediaRevision = "connected-media-before",
                title = "Before Sonic Flag"
            ),
            currentSong,
            currentSong.copy(
                queueKey = 10_002L,
                songId = "connected-after-song",
                mediaRevision = "connected-media-after",
                title = "After Sonic Flag"
            )
        )
    )
    val queue = FullPlayerQueuePacket(
        revision = 2L,
        itemKeys = longArrayOf(10_000L, 10_001L, 10_002L)
    )
    val waveform = FullPlayerWaveformPacket(
        revision = 4L,
        mediaRevision = mediaRevision,
        peaks = FloatArray(96) { index -> .12f + (index % 7) * .11f },
        sections = listOf(
            FullPlayerSection(0L, 20_000L, "Intro"),
            FullPlayerSection(20_000L, 60_000L, "Body")
        )
    )
    val laneRoles = listOf(FullPlayerStemRole.VOCALS, FullPlayerStemRole.DRUMS,
        FullPlayerStemRole.BASS, FullPlayerStemRole.GUITAR, FullPlayerStemRole.PIANO,
        FullPlayerStemRole.OTHER)
    val laneKeys = IntArray(laneRoles.size) { it + 1 }
    val source = PlayerSurfaceSourcePacket(
        revision = 5L,
        presentedMode = PlayerSurfaceSourceMode.STEMS,
        audibleMode = PlayerSurfaceSourceMode.STEMS,
        phase = PlayerSurfaceSourcePhase.SETTLED,
        stemsAvailable = true
    )
    val mixer = PlayerSurfaceMixerPacket(
        revision = 6L,
        packageRevision = 5L,
        laneKeys = laneKeys,
        volumes = FloatArray(laneRoles.size) { .82f },
        laneFlags = IntArray(laneRoles.size)
    )
    return FullPlayerSnapshot(
        revision = 12L,
        owner = PlayerSurfaceOwnerKey(
            generation = 1L,
            songId = "connected-native-song",
            mediaRevision = mediaRevision,
            audioRevision = "connected-audio-1",
            stemRevision = "connected-stems-1"
        ),
        catalog = catalog,
        queue = queue,
        selection = FullPlayerSelectionPacket(3L, 1),
        timeline = PlayerSurfaceTimelinePacket(
            revision = 4L,
            durationMs = 60_000L,
            positionMs = 10_000L,
            loopStartMs = 5_000L,
            loopEndMs = 15_000L,
            waveformRevision = waveform.revision
        ),
        waveform = waveform,
        fineWindow = null,
        sourceMixer = FullPlayerSourceMixerPacket(
            revision = 7L,
            source = source,
            mixer = mixer,
            lanes = laneRoles.mapIndexed { index, role ->
                FullPlayerMixerLaneVisual(
                    laneKey = laneKeys[index],
                    role = role,
                    label = role.name.lowercase().replaceFirstChar(Char::uppercase),
                    colorArgb = 0xFF44CC88.toInt() + index
                )
            }),
        transport = PlayerSurfaceTransportPacket(
            revision = 8L,
            playWhenReady = true,
            repeatMode = PlayerSurfaceRepeatMode.OFF,
            shuffleEnabled = false,
            canGoPrevious = true,
            canGoNext = false
        ),
        toolDetail = FullPlayerToolDetailPacket(
            revision = 9L,
            selectedTool = PlayerSurfaceTool.LOOP,
            activity = FullPlayerToolActivity(
                loopActive = true,
                tuneActive = false,
                lyricsAvailable = false,
                metronomeActive = false,
                metronomeAvailable = true
            ),
            detail = FullPlayerDetailState.Loop(
                durationMs = 60_000L,
                loopStartMs = 5_000L,
                loopEndMs = 15_000L
            )
        ),
        status = FullPlayerStatusPacket(10L)
    )
}


internal fun connectedOriginalLoopSnapshot(): FullPlayerSnapshot {
    val stems = connectedStemsLoopSnapshot()
    return stems.copy(
        revision = stems.revision + 20L,
        sourceMixer = FullPlayerSourceMixerPacket(
            revision = stems.sourceMixer.revision + 20L,
            source = stems.sourceMixer.source.copy(
                revision = stems.sourceMixer.source.revision + 20L,
                presentedMode = PlayerSurfaceSourceMode.ORIGINAL,
                audibleMode = PlayerSurfaceSourceMode.ORIGINAL
            ),
            mixer = PlayerSurfaceMixerPacket(
                revision = stems.sourceMixer.mixer.revision + 20L,
                packageRevision = stems.sourceMixer.mixer.packageRevision + 20L,
                laneKeys = IntArray(0),
                volumes = FloatArray(0),
                laneFlags = IntArray(0)
            ),
            lanes = emptyList<FullPlayerMixerLaneVisual>())
    )
}
