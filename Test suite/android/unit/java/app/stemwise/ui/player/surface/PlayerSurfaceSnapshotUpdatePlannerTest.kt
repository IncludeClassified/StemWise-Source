package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerAdoptionResult
import app.stemwise.ui.player.nativeplayer.FullPlayerDetailState
import app.stemwise.ui.player.nativeplayer.FullPlayerFineWindowPacket
import app.stemwise.ui.player.nativeplayer.FullPlayerMixerLaneVisual
import app.stemwise.ui.player.nativeplayer.FullPlayerQueuePacket
import app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot
import app.stemwise.ui.player.nativeplayer.FullPlayerSourceMixerPacket
import app.stemwise.ui.player.nativeplayer.FullPlayerStemRole
import app.stemwise.ui.player.nativeplayer.FullPlayerStateStore
import app.stemwise.ui.player.nativeplayer.FullPlayerTestFixtures
import app.stemwise.ui.player.nativeplayer.FullPlayerWaveformPacket
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSurfaceSnapshotUpdatePlannerTest {
    @Test
    fun exactTransportOnlyPublicationUsesTransportPlan() {
        val previous = FullPlayerTestFixtures.snapshot()
        val next = transportUpdate(previous)
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.TransportOnly,
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun loopTimelineAndItsDerivedToolPacketUseTimelineOnlyPlan() {
        val previous = FullPlayerTestFixtures.snapshot(selectedTool = PlayerSurfaceTool.LOOP)
        val nextTimeline = previous.timeline.copy(
            revision = previous.timeline.revision + 1L,
            positionMs = previous.timeline.positionMs + 17L,
            loopStartMs = previous.timeline.loopStartMs + 250L,
            loopEndMs = previous.timeline.loopEndMs + 500L
        )
        val next = previous.copy(
            revision = previous.revision + 10L,
            timeline = nextTimeline,
            toolDetail = previous.toolDetail.copy(
                revision = previous.toolDetail.revision + 1L,
                detail = FullPlayerDetailState.Loop(
                    durationMs = nextTimeline.durationMs,
                    loopStartMs = nextTimeline.loopStartMs,
                    loopEndMs = nextTimeline.loopEndMs
                )
            )
        )
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.TimelineOnly,
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun nullToFineWindowPublicationUsesFineWindowPlan() {
        val previous = FullPlayerTestFixtures.snapshot()
        val next = previous.copy(
            revision = previous.revision + 10L,
            fineWindow = fineWindow(previous, revision = previous.revision + 1L)
        )
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.FineWindowOnly,
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun newerFineWindowReplacementUsesFineWindowPlan() {
        val fixture = FullPlayerTestFixtures.snapshot()
        val previous = fixture.copy(
            fineWindow = fineWindow(fixture, revision = fixture.revision - 1L)
        )
        val next = previous.copy(
            revision = previous.revision + 10L,
            fineWindow = fineWindow(
                previous,
                revision = checkNotNull(previous.fineWindow).revision + 1L,
                startMs = 1_100L,
                peaks = floatArrayOf(.7f, .5f, .3f)
            )
        )
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.FineWindowOnly,
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun fineWindowRemovalUsesFineWindowPlan() {
        val fixture = FullPlayerTestFixtures.snapshot()
        val previous = fixture.copy(
            fineWindow = fineWindow(fixture, revision = fixture.revision - 1L)
        )
        val next = previous.copy(
            revision = previous.revision + 10L,
            fineWindow = null
        )
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.FineWindowOnly,
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun fineWindowRevisionCollisionsAndRegressionNeverUseNarrowPlan() {
        val fixture = FullPlayerTestFixtures.snapshot()
        val previous = fixture.copy(
            fineWindow = fineWindow(fixture, revision = fixture.revision - 1L)
        )
        val previousFineRevision = checkNotNull(previous.fineWindow).revision
        val rootCollision = previous.copy(
            fineWindow = fineWindow(previous, revision = previousFineRevision + 1L)
        )
        val componentCollision = previous.copy(
            revision = previous.revision + 10L,
            fineWindow = fineWindow(
                previous,
                revision = previousFineRevision,
                peaks = floatArrayOf(.9f, .6f, .3f)
            )
        )
        val componentRegression = previous.copy(
            revision = previous.revision + 10L,
            fineWindow = fineWindow(previous, revision = previousFineRevision - 1L)
        )

        listOf(rootCollision, componentCollision, componentRegression).forEach { candidate ->
            assertEquals(
                PlayerSurfaceSnapshotUpdatePlan.Full,
                PlayerSurfaceSnapshotUpdatePlanner.classify(previous, candidate)
            )
        }
        assertRejectedUpdate(
            previous,
            rootCollision,
            FullPlayerAdoptionResult.REJECTED_REVISION_COLLISION
        )
        assertRejectedUpdate(
            previous,
            componentCollision,
            FullPlayerAdoptionResult.REJECTED_COMPONENT_COLLISION
        )
        assertRejectedUpdate(
            previous,
            componentRegression,
            FullPlayerAdoptionResult.REJECTED_COMPONENT_REGRESSION
        )
    }

    @Test
    fun simultaneousFineWindowAndTransportChangesUseFullPlan() {
        val previous = FullPlayerTestFixtures.snapshot()
        val next = previous.copy(
            revision = previous.revision + 10L,
            fineWindow = fineWindow(previous, revision = previous.revision + 1L),
            transport = previous.transport.copy(
                revision = previous.transport.revision + 1L,
                shuffleEnabled = !previous.transport.shuffleEnabled
            )
        )
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.Full,
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun selectedToolAndMatchingDetailPublicationUsesToolDetailPlan() {
        val previous = FullPlayerTestFixtures.snapshot()
        val next = toolDetailUpdate(previous)
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.ToolDetailOnly,
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun staleEqualAndCollidingToolPublicationsNeverUseNarrowPlan() {
        val previous = FullPlayerTestFixtures.snapshot()
        val validShape = toolDetailUpdate(previous)
        val staleToolRevision = validShape.copy(
            toolDetail = validShape.toolDetail.copy(
                revision = previous.toolDetail.revision - 1L
            )
        )
        val collidingToolRevision = validShape.copy(
            toolDetail = validShape.toolDetail.copy(
                revision = previous.toolDetail.revision
            )
        )
        val equalRootRevision = validShape.copy(revision = previous.revision)

        listOf(staleToolRevision, collidingToolRevision, equalRootRevision)
            .forEach { candidate ->
                assertEquals(
                    PlayerSurfaceSnapshotUpdatePlan.Full,
                    PlayerSurfaceSnapshotUpdatePlanner.classify(previous, candidate)
                )
            }
        assertRejectedUpdate(
            previous,
            staleToolRevision,
            FullPlayerAdoptionResult.REJECTED_COMPONENT_REGRESSION
        )
        assertRejectedUpdate(
            previous,
            collidingToolRevision,
            FullPlayerAdoptionResult.REJECTED_COMPONENT_COLLISION
        )
        assertRejectedUpdate(
            previous,
            equalRootRevision,
            FullPlayerAdoptionResult.REJECTED_REVISION_COLLISION
        )
    }

    @Test
    fun everyUnrelatedComponentExcludesToolDetailFastPath() {
        val previous = FullPlayerTestFixtures.snapshot()
        val toolCandidate = toolDetailUpdate(previous)
        val replacementCatalog = FullPlayerTestFixtures.catalog(
            size = previous.catalog.size,
            revision = previous.catalog.revision + 1L
        )
        val replacementQueue = FullPlayerQueuePacket(
            revision = previous.queue.revision + 1L,
            itemKeys = LongArray(previous.queue.itemCount) { previous.queue.itemKeyAt(it) }
        )
        val replacementWaveform = FullPlayerWaveformPacket(
            revision = previous.waveform.revision + 1L,
            mediaRevision = previous.waveform.mediaRevision,
            peaks = previous.waveform.copyPeaks(),
            sections = previous.waveform.sections()
        )
        val replacementFineWindow = fineWindow(
            previous,
            revision = previous.revision + 1L
        )
        val source = previous.sourceMixer.source.copy(
            revision = previous.sourceMixer.source.revision + 1L,
            phase = PlayerSurfaceSourcePhase.PREPARING
        )
        val replacementSourceMixer = sourceUpdate(previous, source = source).sourceMixer
        val exclusions = linkedMapOf<String, FullPlayerSnapshot>(
            "owner" to toolCandidate.copy(
                owner = previous.owner.copy(audioRevision = "another-audio-revision")
            ),
            "visual selection" to toolCandidate.copy(
                visualSelectionSongId = previous.catalog.songAt(1).songId
            ),
            "catalog" to toolCandidate.copy(catalog = replacementCatalog),
            "queue" to toolCandidate.copy(queue = replacementQueue),
            "selection" to toolCandidate.copy(
                selection = previous.selection.copy(revision = previous.selection.revision + 1L)
            ),
            "timeline" to toolCandidate.copy(
                timeline = previous.timeline.copy(revision = previous.timeline.revision + 1L)
            ),
            "waveform" to toolCandidate.copy(waveform = replacementWaveform),
            "fine window" to toolCandidate.copy(fineWindow = replacementFineWindow),
            "source mixer" to toolCandidate.copy(sourceMixer = replacementSourceMixer),
            "transport" to toolCandidate.copy(
                transport = previous.transport.copy(
                    revision = previous.transport.revision + 1L,
                    repeatMode = PlayerSurfaceRepeatMode.ALL
                )
            ),
            "status" to toolCandidate.copy(
                status = previous.status.copy(revision = previous.status.revision + 1L)
            )
        )

        exclusions.forEach { (label, candidate) ->
            assertEquals(
                label,
                PlayerSurfaceSnapshotUpdatePlan.Full,
                PlayerSurfaceSnapshotUpdatePlanner.classify(previous, candidate)
            )
        }
    }

    @Test
    fun sourcePacketEvidenceWithoutPresentedModeChangeUsesPhasePlan() {
        val previous = FullPlayerTestFixtures.snapshot()
        val source = previous.sourceMixer.source.copy(
            revision = previous.sourceMixer.source.revision + 1L,
            audibleMode = PlayerSurfaceSourceMode.STEMS,
            phase = PlayerSurfaceSourcePhase.PREPARING,
            stemsAvailabilitySettled = false
        )
        val next = sourceUpdate(previous, source = source)
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.SourceMixerOnly(
                PlayerSurfaceSourceMixerUpdateWork.PHASE_ONLY
            ),
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun volumeMuteSoloPublicationInsideSamePackageUsesMixerStatePlan() {
        val previous = FullPlayerTestFixtures.snapshot(
            sourceMode = PlayerSurfaceSourceMode.STEMS
        )
        val mixer = mixerPacket(
            previous.sourceMixer,
            revision = previous.sourceMixer.mixer.revision + 1L,
            packageRevision = previous.sourceMixer.mixer.packageRevision,
            volumeOverride = .37f,
            firstLaneFlags = PlayerSurfaceMixerPacket.FLAG_MUTED or
                PlayerSurfaceMixerPacket.FLAG_SOLO
        )
        val next = sourceUpdate(previous, mixer = mixer)
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.SourceMixerOnly(
                PlayerSurfaceSourceMixerUpdateWork.MIXER_STATE_ONLY
            ),
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun presentedModeAndLanePackageChangeUseAtomicPresentationTopologyPlan() {
        val previous = FullPlayerTestFixtures.snapshot()
        val source = previous.sourceMixer.source.copy(
            revision = previous.sourceMixer.source.revision + 1L,
            presentedMode = PlayerSurfaceSourceMode.STEMS,
            audibleMode = PlayerSurfaceSourceMode.STEMS,
            phase = PlayerSurfaceSourcePhase.SETTLED,
            stemsAvailable = true,
            stemsAvailabilitySettled = true
        )
        val lanes = sixLanes()
        val mixer = PlayerSurfaceMixerPacket(
            revision = previous.sourceMixer.mixer.revision + 2L,
            packageRevision = previous.sourceMixer.mixer.packageRevision + 1L,
            laneKeys = IntArray(lanes.size) { lanes[it].laneKey },
            volumes = FloatArray(lanes.size) { .82f },
            laneFlags = IntArray(lanes.size)
        )
        val next = sourceUpdate(
            previous = previous,
            source = source,
            mixer = mixer,
            lanes = lanes,


        )
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.SourceMixerOnly(
                PlayerSurfaceSourceMixerUpdateWork.PRESENTATION_OR_TOPOLOGY
            ),
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun laneVisualChangeInsidePresentedStemsUsesPresentationTopologyPlan() {
        val previous = FullPlayerTestFixtures.snapshot(
            sourceMode = PlayerSurfaceSourceMode.STEMS
        )
        val lanes = previous.sourceMixer.lanes().toMutableList().also { entries ->
            entries[0] = entries[0].copy(label = "Lead vocals")
        }
        val next = sourceUpdate(previous, lanes = lanes)
        assertAcceptedUpdate(previous, next)

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.SourceMixerOnly(
                PlayerSurfaceSourceMixerUpdateWork.PRESENTATION_OR_TOPOLOGY
            ),
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, next)
        )
    }

    @Test
    fun everyUnrelatedComponentExcludesTransportFastPath() {
        val previous = FullPlayerTestFixtures.snapshot()
        val transport = transportUpdate(previous)
        val replacementCatalog = FullPlayerTestFixtures.catalog(
            size = previous.catalog.size,
            revision = previous.catalog.revision + 1L
        )
        val replacementQueue = FullPlayerQueuePacket(
            revision = previous.queue.revision + 1L,
            itemKeys = LongArray(previous.queue.itemCount) { previous.queue.itemKeyAt(it) }
        )
        val replacementWaveform = FullPlayerWaveformPacket(
            revision = previous.waveform.revision + 1L,
            mediaRevision = previous.waveform.mediaRevision,
            peaks = previous.waveform.copyPeaks(),
            sections = previous.waveform.sections()
        )
        val replacementFineWindow = FullPlayerFineWindowPacket(
            revision = previous.revision + 1L,
            mediaRevision = previous.owner.mediaRevision,
            sourceRevision = previous.sourceMixer.source.revision,
            mixerRevision = previous.sourceMixer.mixer.revision,
            startMs = 0L,
            frameDurationMs = 5L,
            peaks = floatArrayOf(.2f, .4f, .6f)
        )
        val exclusions = linkedMapOf<String, FullPlayerSnapshot>(
            "owner" to transport.copy(
                owner = previous.owner.copy(audioRevision = "another-audio-revision")
            ),
            "visual selection" to transport.copy(
                visualSelectionSongId = previous.catalog.songAt(1).songId
            ),
            "catalog" to transport.copy(catalog = replacementCatalog),
            "queue" to transport.copy(queue = replacementQueue),
            "selection" to transport.copy(
                selection = previous.selection.copy(revision = previous.selection.revision + 1L)
            ),
            "timeline" to transport.copy(
                timeline = previous.timeline.copy(revision = previous.timeline.revision + 1L)
            ),
            "waveform" to transport.copy(waveform = replacementWaveform),
            "fine window" to transport.copy(fineWindow = replacementFineWindow),
            "tool" to transport.copy(
                toolDetail = previous.toolDetail.copy(
                    revision = previous.toolDetail.revision + 1L
                )
            ),
            "status" to transport.copy(
                status = previous.status.copy(revision = previous.status.revision + 1L)
            )
        )

        exclusions.forEach { (label, candidate) ->
            assertEquals(
                label,
                PlayerSurfaceSnapshotUpdatePlan.Full,
                PlayerSurfaceSnapshotUpdatePlanner.classify(previous, candidate)
            )
        }
    }

    @Test
    fun everyUnrelatedComponentExcludesSourceMixerFastPath() {
        val previous = FullPlayerTestFixtures.snapshot()
        val source = previous.sourceMixer.source.copy(
            revision = previous.sourceMixer.source.revision + 1L,
            phase = PlayerSurfaceSourcePhase.PREPARING
        )
        val sourceCandidate = sourceUpdate(previous, source = source)
        val replacementCatalog = FullPlayerTestFixtures.catalog(
            size = previous.catalog.size,
            revision = previous.catalog.revision + 1L
        )
        val replacementQueue = FullPlayerQueuePacket(
            revision = previous.queue.revision + 1L,
            itemKeys = LongArray(previous.queue.itemCount) { previous.queue.itemKeyAt(it) }
        )
        val replacementWaveform = FullPlayerWaveformPacket(
            revision = previous.waveform.revision + 1L,
            mediaRevision = previous.waveform.mediaRevision,
            peaks = previous.waveform.copyPeaks(),
            sections = previous.waveform.sections()
        )
        val replacementFineWindow = FullPlayerFineWindowPacket(
            revision = previous.revision + 1L,
            mediaRevision = previous.owner.mediaRevision,
            sourceRevision = previous.sourceMixer.source.revision,
            mixerRevision = previous.sourceMixer.mixer.revision,
            startMs = 0L,
            frameDurationMs = 5L,
            peaks = floatArrayOf(.2f, .4f, .6f)
        )
        val exclusions = linkedMapOf<String, FullPlayerSnapshot>(
            "owner" to sourceCandidate.copy(
                owner = previous.owner.copy(stemRevision = "another-stem-revision")
            ),
            "visual selection" to sourceCandidate.copy(
                visualSelectionSongId = previous.catalog.songAt(1).songId
            ),
            "catalog" to sourceCandidate.copy(catalog = replacementCatalog),
            "queue" to sourceCandidate.copy(queue = replacementQueue),
            "selection" to sourceCandidate.copy(
                selection = previous.selection.copy(revision = previous.selection.revision + 1L)
            ),
            "timeline" to sourceCandidate.copy(
                timeline = previous.timeline.copy(revision = previous.timeline.revision + 1L)
            ),
            "waveform" to sourceCandidate.copy(waveform = replacementWaveform),
            "fine window" to sourceCandidate.copy(fineWindow = replacementFineWindow),
            "transport" to sourceCandidate.copy(
                transport = previous.transport.copy(
                    revision = previous.transport.revision + 1L,
                    repeatMode = PlayerSurfaceRepeatMode.ALL
                )
            ),
            "tool" to sourceCandidate.copy(
                toolDetail = previous.toolDetail.copy(
                    revision = previous.toolDetail.revision + 1L
                )
            ),
            "status" to sourceCandidate.copy(
                status = previous.status.copy(revision = previous.status.revision + 1L)
            )
        )

        exclusions.forEach { (label, candidate) ->
            assertEquals(
                label,
                PlayerSurfaceSnapshotUpdatePlan.Full,
                PlayerSurfaceSnapshotUpdatePlanner.classify(previous, candidate)
            )
        }
    }

    @Test
    fun simultaneousSourceAndTransportChangesUseFullPlan() {
        val previous = FullPlayerTestFixtures.snapshot()
        val source = previous.sourceMixer.source.copy(
            revision = previous.sourceMixer.source.revision + 1L,
            phase = PlayerSurfaceSourcePhase.PREPARING
        )
        val sourceUpdate = sourceUpdate(previous, source = source)
        val simultaneous = sourceUpdate.copy(
            revision = sourceUpdate.revision + 1L,
            transport = previous.transport.copy(
                revision = previous.transport.revision + 1L,
                shuffleEnabled = !previous.transport.shuffleEnabled
            )
        )

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.Full,
            PlayerSurfaceSnapshotUpdatePlanner.classify(previous, simultaneous)
        )
    }

    @Test
    fun simultaneousSourceEvidenceAndMixerStateChangesUseFullPlan() {
        val previous = FullPlayerTestFixtures.snapshot(
            sourceMode = PlayerSurfaceSourceMode.STEMS
        )
        val source = previous.sourceMixer.source.copy(
            revision = previous.sourceMixer.source.revision + 1L,
            phase = PlayerSurfaceSourcePhase.PREPARING
        )
        val mixer = mixerPacket(
            previous.sourceMixer,
            revision = previous.sourceMixer.mixer.revision + 1L,
            packageRevision = previous.sourceMixer.mixer.packageRevision,
            volumeOverride = .41f
        )

        assertEquals(
            PlayerSurfaceSnapshotUpdatePlan.Full,
            PlayerSurfaceSnapshotUpdatePlanner.classify(
                previous,
                sourceUpdate(previous, source = source, mixer = mixer)
            )
        )
    }

    @Test
    fun staleEqualAndCollidingRevisionsNeverUseNarrowPlan() {
        val previous = FullPlayerTestFixtures.snapshot()
        val changedTransportAtSameRevision = previous.transport.copy(
            shuffleEnabled = !previous.transport.shuffleEnabled
        )
        val equalRoot = previous.copy(transport = changedTransportAtSameRevision)
        val newerRootWithCollidingTransport = previous.copy(
            revision = previous.revision + 1L,
            transport = changedTransportAtSameRevision
        )
        val newerTransportWithEqualRoot = previous.copy(
            transport = previous.transport.copy(
                revision = previous.transport.revision + 1L,
                shuffleEnabled = !previous.transport.shuffleEnabled
            )
        )

        listOf(equalRoot, newerRootWithCollidingTransport, newerTransportWithEqualRoot)
            .forEach { candidate ->
                assertEquals(
                    PlayerSurfaceSnapshotUpdatePlan.Full,
                    PlayerSurfaceSnapshotUpdatePlanner.classify(previous, candidate)
                )
            }
    }

    @Test
    fun collidingNestedSourceAndMixerRevisionsNeverUseNarrowPlan() {
        val original = FullPlayerTestFixtures.snapshot()
        val collidingSource = original.sourceMixer.source.copy(
            phase = PlayerSurfaceSourcePhase.PREPARING
        )
        val stems = FullPlayerTestFixtures.snapshot(
            sourceMode = PlayerSurfaceSourceMode.STEMS
        )
        val collidingMixer = mixerPacket(
            stems.sourceMixer,
            revision = stems.sourceMixer.mixer.revision,
            packageRevision = stems.sourceMixer.mixer.packageRevision,
            volumeOverride = .22f
        )

        listOf(
            original to sourceUpdate(original, source = collidingSource),
            stems to sourceUpdate(stems, mixer = collidingMixer)
        ).forEach { (previous, candidate) ->
            assertEquals(
                PlayerSurfaceSnapshotUpdatePlan.Full,
                PlayerSurfaceSnapshotUpdatePlanner.classify(previous, candidate)
            )
        }
    }

    @Test
    fun regressedNestedSourceOrMixerRevisionNeverUsesNarrowPlan() {
        val previous = FullPlayerTestFixtures.snapshot(
            sourceMode = PlayerSurfaceSourceMode.STEMS
        )
        val regressedSource = previous.sourceMixer.source.copy(
            revision = previous.sourceMixer.source.revision - 1L,
            phase = PlayerSurfaceSourcePhase.PREPARING
        )
        val regressedMixer = mixerPacket(
            previous.sourceMixer,
            revision = previous.sourceMixer.mixer.revision - 1L,
            packageRevision = previous.sourceMixer.mixer.packageRevision,
            volumeOverride = .2f
        )

        listOf(
            sourceUpdate(previous, source = regressedSource),
            sourceUpdate(previous, mixer = regressedMixer)
        ).forEach { candidate ->
            assertEquals(
                PlayerSurfaceSnapshotUpdatePlan.Full,
                PlayerSurfaceSnapshotUpdatePlanner.classify(previous, candidate)
            )
        }
    }

    private fun transportUpdate(previous: FullPlayerSnapshot): FullPlayerSnapshot = previous.copy(
        revision = previous.revision + 10L,
        transport = previous.transport.copy(
            revision = previous.transport.revision + 1L,
            shuffleEnabled = !previous.transport.shuffleEnabled
        )
    )

    private fun fineWindow(
        snapshot: FullPlayerSnapshot,
        revision: Long,
        startMs: Long = 900L,
        peaks: FloatArray = floatArrayOf(.2f, .4f, .6f)
    ): FullPlayerFineWindowPacket = FullPlayerFineWindowPacket(
        revision = revision,
        mediaRevision = snapshot.owner.mediaRevision,
        sourceRevision = snapshot.sourceMixer.source.revision,
        mixerRevision = snapshot.sourceMixer.mixer.revision,
        startMs = startMs,
        frameDurationMs = 5L,
        peaks = peaks
    )

    private fun toolDetailUpdate(previous: FullPlayerSnapshot): FullPlayerSnapshot {
        val tuneDetail = FullPlayerTestFixtures.snapshot(
            selectedIndex = previous.selection.currentIndex,
            catalog = previous.catalog,
            queue = previous.queue,
            selectedTool = PlayerSurfaceTool.TUNE
        ).toolDetail.copy(revision = previous.toolDetail.revision + 1L)
        return previous.copy(
            revision = previous.revision + 10L,
            toolDetail = tuneDetail
        )
    }

    private fun sourceUpdate(
        previous: FullPlayerSnapshot,
        source: PlayerSurfaceSourcePacket = previous.sourceMixer.source,
        mixer: PlayerSurfaceMixerPacket = previous.sourceMixer.mixer,
        lanes: List<FullPlayerMixerLaneVisual> = previous.sourceMixer.lanes(),


    ): FullPlayerSnapshot {
        val packet = FullPlayerSourceMixerPacket(
            revision = previous.sourceMixer.revision + 3L,
            source = source,
            mixer = mixer,
            lanes = lanes)
        return previous.copy(
            revision = previous.revision + 10L,
            sourceMixer = packet
        )
    }

    private fun mixerPacket(
        sourceMixer: FullPlayerSourceMixerPacket,
        revision: Long,
        packageRevision: Long,
        volumeOverride: Float,
        firstLaneFlags: Int = 0
    ): PlayerSurfaceMixerPacket = PlayerSurfaceMixerPacket(
        revision = revision,
        packageRevision = packageRevision,
        laneKeys = IntArray(sourceMixer.mixer.laneCount) {
            sourceMixer.mixer.laneKeyAt(it)
        },
        volumes = FloatArray(sourceMixer.mixer.laneCount) { lane ->
            if (lane == 0) volumeOverride else sourceMixer.mixer.volumeAt(lane)
        },
        laneFlags = IntArray(sourceMixer.mixer.laneCount) { lane ->
            if (lane == 0) {
                firstLaneFlags
            } else {
                (if (sourceMixer.mixer.mutedAt(lane)) {
                    PlayerSurfaceMixerPacket.FLAG_MUTED
                } else 0) or
                    (if (sourceMixer.mixer.soloAt(lane)) {
                        PlayerSurfaceMixerPacket.FLAG_SOLO
                    } else 0)
            }
        }
    )

    private fun sixLanes(): List<FullPlayerMixerLaneVisual> =
        listOf(FullPlayerStemRole.VOCALS, FullPlayerStemRole.DRUMS, FullPlayerStemRole.BASS,
            FullPlayerStemRole.GUITAR, FullPlayerStemRole.PIANO, FullPlayerStemRole.OTHER).mapIndexed { index, role ->
            FullPlayerMixerLaneVisual(
                laneKey = index + 1,
                role = role,
                label = role.name.lowercase().replaceFirstChar(Char::uppercase),
                colorArgb = 0xFF44CC88.toInt() + index
            )
        }

    private fun assertAcceptedUpdate(
        previous: FullPlayerSnapshot,
        next: FullPlayerSnapshot
    ) {
        val store = FullPlayerStateStore()
        assertEquals(FullPlayerAdoptionResult.APPLIED_INITIAL, store.adopt(previous))
        assertEquals(FullPlayerAdoptionResult.APPLIED_UPDATE, store.adopt(next))
    }

    private fun assertRejectedUpdate(
        previous: FullPlayerSnapshot,
        next: FullPlayerSnapshot,
        expected: FullPlayerAdoptionResult
    ) {
        val store = FullPlayerStateStore()
        assertEquals(FullPlayerAdoptionResult.APPLIED_INITIAL, store.adopt(previous))
        assertEquals(expected, store.adopt(next))
    }
}
