package app.stemwise.ui.player.nativeplayer

import app.stemwise.ui.player.surface.PlayerSurfaceMixerPacket
import app.stemwise.ui.player.surface.PlayerSurfaceSourceMode
import app.stemwise.ui.player.surface.PlayerSurfaceSourcePacket
import app.stemwise.ui.player.surface.PlayerSurfaceTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FullPlayerStateStoreTest {
    @Test fun speedTabWorksWithoutAuthorizingMetronomeWhenTimingIsUnavailable() {
        val base = FullPlayerTestFixtures.snapshot(selectedTool = PlayerSurfaceTool.METRONOME)
        val snapshot = base.copy(toolDetail = base.toolDetail.copy(
            activity = base.toolDetail.activity.copy(metronomeAvailable = false,
                metronomeActive = false, speedActive = true),
            detail = (base.toolDetail.detail as FullPlayerDetailState.Metronome).copy(
                enabled = false, timingAvailable = false, tempo = .8f)
        ))
        assertNull(FullPlayerSnapshotValidator.validate(snapshot))
        assertEquals(FullPlayerAdoptionResult.APPLIED_INITIAL, FullPlayerStateStore().adopt(snapshot))
        val invalid = snapshot.copy(toolDetail = snapshot.toolDetail.copy(
            detail = (snapshot.toolDetail.detail as FullPlayerDetailState.Metronome).copy(enabled = true)))
        assertEquals(FullPlayerSnapshotIssue.TOOL_ACTIVITY_MISMATCH, FullPlayerSnapshotValidator.validate(invalid))
    }

    @Test fun keyPanelSnapshotsAcceptIndependentPitchAndTempo() {
        for (tempo in listOf(.5f, .8f, 1f, 1.25f, 2f)) {
            for (pitch in listOf(-12, 0, 12)) {
                val base = FullPlayerTestFixtures.snapshot(selectedTool = PlayerSurfaceTool.TUNE)
                val snapshot = base.copy(toolDetail = base.toolDetail.copy(
                    detail = FullPlayerDetailState.Tune("C", tempo, pitch),
                    activity = base.toolDetail.activity.copy(tuneActive = pitch != 0, speedActive = app.stemwise.ui.tuneTempoActive(tempo))
                ))
                assertNull("tempo=$tempo pitch=$pitch", FullPlayerSnapshotValidator.validate(snapshot))
                val store = FullPlayerStateStore()
                assertEquals(FullPlayerAdoptionResult.APPLIED_INITIAL, store.adopt(snapshot))
                assertSame(snapshot, store.snapshot)
            }
        }
    }

    @Test
    fun reusedMembershipKeepsFullValidationForChangedOwnersAndPackets() {
        val catalog = FullPlayerTestFixtures.catalog(size = 401)
        val queue = FullPlayerQueuePacket(2L, LongArray(400) { catalog.songAt(it).queueKey })
        val accepted = FullPlayerTestFixtures.snapshot(catalog = catalog, queue = queue)
        assertNull(FullPlayerSnapshotValidator.validate(accepted))
        val updated = accepted.copy(revision = accepted.revision + 1L)
        val candidates = listOf(
            updated,
            updated.copy(transport = updated.transport.copy(playWhenReady = !updated.transport.playWhenReady)),
            updated.copy(owner = updated.owner.copy(songId = "wrong-owner")),
            updated.copy(owner = updated.owner.copy(mediaRevision = "wrong-media")),
            updated.copy(selection = updated.selection.copy(currentIndex = 400)),
            updated.copy(visualSelectionSongId = catalog.songAt(399).songId),
            updated.copy(visualSelectionSongId = catalog.songAt(400).songId),
            updated.copy(catalog = FullPlayerCatalogPacket(3L, catalog.songs().dropLast(2))),
            updated.copy(queue = FullPlayerQueuePacket(3L, longArrayOf(catalog.songAt(0).queueKey, -99L))),
            updated.copy(timeline = updated.timeline.copy(waveformRevision = 0L))
        )
        for (candidate in candidates) {
            assertEquals(
                FullPlayerSnapshotValidator.validate(candidate),
                FullPlayerSnapshotValidator.validate(candidate, previouslyValidated = accepted)
            )
        }
        val store = FullPlayerStateStore()
        assertEquals(FullPlayerAdoptionResult.APPLIED_INITIAL, store.adopt(accepted))
        assertEquals(FullPlayerAdoptionResult.REJECTED_INCOHERENT, store.adopt(candidates[7]))
        assertSame(accepted, store.snapshot)
        assertEquals(FullPlayerAdoptionResult.APPLIED_UPDATE, store.adopt(updated))
    }

    @Test
    fun benchmarkValidationWithFourHundredUnchangedQueueEntries() {
        val catalog = FullPlayerTestFixtures.catalog(size = 400)
        val snapshot = FullPlayerTestFixtures.snapshot(catalog = catalog, selectedIndex = 399)
        assertNull(FullPlayerSnapshotValidator.validate(snapshot))
        fun measure(previous: FullPlayerSnapshot?): Long {
            val start = System.nanoTime()
            var valid = 0
            repeat(20_000) {
                if (FullPlayerSnapshotValidator.validate(snapshot, previous) == null) valid++
            }
            assertEquals(20_000, valid)
            return System.nanoTime() - start
        }
        repeat(2) { measure(null); measure(snapshot) }
        val uncached = LongArray(5)
        val reused = LongArray(5)
        repeat(5) { round ->
            // Alternate order to keep warm-up and host-load bias out of the comparison.
            if (round % 2 == 0) {
                uncached[round] = measure(null)
                reused[round] = measure(snapshot)
            } else {
                reused[round] = measure(snapshot)
                uncached[round] = measure(null)
            }
        }
        println("400-entry validation median ns/op: full=${uncached.sorted()[2] / 20_000}, " +
            "reused=${reused.sorted()[2] / 20_000}")
    }

    @Test
    fun coherentSnapshotIsAdoptedAtomically() {
        val snapshot = FullPlayerTestFixtures.snapshot()
        val store = FullPlayerStateStore()

        assertNull(FullPlayerSnapshotValidator.validate(snapshot))
        assertEquals(FullPlayerAdoptionResult.APPLIED_INITIAL, store.adopt(snapshot))
        assertSame(snapshot, store.snapshot)
        assertEquals(FullPlayerAdoptionResult.UNCHANGED, store.adopt(snapshot))
        assertSame(snapshot, store.snapshot)
    }

    @Test
    fun ownerMustEqualCurrentQueueEntryAndRejectionPreservesVisibleSnapshot() {
        val accepted = FullPlayerTestFixtures.snapshot()
        val incoherent = accepted.copy(
            revision = accepted.revision + 1L,
            owner = accepted.owner.copy(songId = "not-the-selected-song")
        )
        val store = FullPlayerStateStore()
        store.adopt(accepted)

        assertEquals(
            FullPlayerSnapshotIssue.OWNER_DOES_NOT_MATCH_QUEUE_SELECTION,
            FullPlayerSnapshotValidator.validate(incoherent)
        )
        assertEquals(FullPlayerAdoptionResult.REJECTED_INCOHERENT, store.adopt(incoherent))
        assertSame(accepted, store.snapshot)
    }

    @Test
    fun visualSelectionIntentMustRemainInsideTheSameLogicalQueue() {
        val accepted = FullPlayerTestFixtures.snapshot()
        val incoherent = accepted.copy(
            revision = accepted.revision + 1L,
            visualSelectionSongId = "not-in-the-queue"
        )

        assertEquals(
            FullPlayerSnapshotIssue.VISUAL_SELECTION_MISSING_FROM_QUEUE,
            FullPlayerSnapshotValidator.validate(incoherent)
        )
    }

    @Test
    fun visualSelectionIsPartOfTheExactPublicationIdentity() {
        val accepted = FullPlayerTestFixtures.snapshot()
        val changedAtSameRevision = accepted.copy(
            visualSelectionSongId = accepted.catalog.songAt(1).songId
        )
        val store = FullPlayerStateStore()
        assertEquals(FullPlayerAdoptionResult.APPLIED_INITIAL, store.adopt(accepted))
        assertEquals(
            FullPlayerAdoptionResult.REJECTED_REVISION_COLLISION,
            store.adopt(changedAtSameRevision)
        )
    }

    @Test
    fun sourceAndMixerTopologyCannotBePublishedSeparately() {
        val accepted = FullPlayerTestFixtures.snapshot(
            sourceMode = PlayerSurfaceSourceMode.STEMS
        )
        val originalSource = PlayerSurfaceSourcePacket(
            revision = accepted.sourceMixer.source.revision,
            presentedMode = PlayerSurfaceSourceMode.ORIGINAL,
            audibleMode = PlayerSurfaceSourceMode.ORIGINAL,
            phase = accepted.sourceMixer.source.phase,
            stemsAvailable = true
        )
        val splitPublication = FullPlayerSourceMixerPacket(
            revision = accepted.sourceMixer.revision,
            source = originalSource,
            mixer = accepted.sourceMixer.mixer,
            lanes = accepted.sourceMixer.lanes())
        val incoherent = accepted.copy(
            revision = accepted.revision + 1L,
            sourceMixer = splitPublication
        )

        assertEquals(
            FullPlayerSnapshotIssue.ORIGINAL_SOURCE_EXPOSES_MIXER,
            FullPlayerSnapshotValidator.validate(incoherent)
        )
    }

    @Test
    fun selectedToolAndDetailCannotDiverge() {
        val accepted = FullPlayerTestFixtures.snapshot()
        val incoherent = accepted.copy(
            revision = accepted.revision + 1L,
            toolDetail = accepted.toolDetail.copy(
                revision = accepted.toolDetail.revision + 1L,
                selectedTool = PlayerSurfaceTool.TUNE,
                detail = FullPlayerDetailState.None
            )
        )

        assertEquals(
            FullPlayerSnapshotIssue.TOOL_DETAIL_MISMATCH,
            FullPlayerSnapshotValidator.validate(incoherent)
        )
    }

    @Test
    fun metronomeDetailAcceptsTheActualPositiveGridTempoWithoutA240BpmDisplayCap() {
        val snapshot = FullPlayerTestFixtures.snapshot(selectedTool = PlayerSurfaceTool.METRONOME)
        val detail = snapshot.toolDetail.detail as FullPlayerDetailState.Metronome
        for (bpm in listOf(12f, 300f, 600f, 1_200f)) {
            val actual = snapshot.copy(toolDetail = snapshot.toolDetail.copy(detail = detail.copy(bpm = bpm)))
            assertEquals(null, FullPlayerSnapshotValidator.validate(actual))
        }
        for (bpm in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val invalid = snapshot.copy(toolDetail = snapshot.toolDetail.copy(detail = detail.copy(bpm = bpm)))
            assertEquals(FullPlayerSnapshotIssue.METRONOME_DETAIL_INVALID, FullPlayerSnapshotValidator.validate(invalid))
        }
    }

    @Test
    fun metronomeCannotClaimTimingOrBeActiveWhenEnrichmentIsUnavailable() {
        val selected = FullPlayerTestFixtures.snapshot(
            selectedTool = PlayerSurfaceTool.METRONOME
        )
        val unavailableSelected = selected.copy(
            revision = selected.revision + 1L,
            toolDetail = selected.toolDetail.copy(
                revision = selected.toolDetail.revision + 1L,
                activity = selected.toolDetail.activity.copy(metronomeAvailable = false)
            )
        )
        assertEquals(
            FullPlayerSnapshotIssue.METRONOME_DETAIL_INVALID,
            FullPlayerSnapshotValidator.validate(unavailableSelected)
        )

        val closed = FullPlayerTestFixtures.snapshot()
        val unavailableClosed = closed.copy(
            revision = closed.revision + 1L,
            toolDetail = closed.toolDetail.copy(
                revision = closed.toolDetail.revision + 1L,
                activity = closed.toolDetail.activity.copy(
                    metronomeActive = false,
                    metronomeAvailable = false
                )
            )
        )
        assertNull(FullPlayerSnapshotValidator.validate(unavailableClosed))
    }

    @Test
    fun loopStartWithoutEndIsAValidAtomicToolAndTimelineState() {
        val complete = FullPlayerTestFixtures.snapshot(selectedTool = PlayerSurfaceTool.LOOP)
        val partialDetail = (complete.toolDetail.detail as FullPlayerDetailState.Loop).copy(
            loopEndMs = null
        )
        val partial = complete.copy(
            revision = complete.revision + 2L,
            timeline = complete.timeline.copy(
                revision = complete.timeline.revision + 1L,
                loopEndMs = -1L
            ),
            toolDetail = complete.toolDetail.copy(
                revision = complete.toolDetail.revision + 1L,
                activity = complete.toolDetail.activity.copy(loopActive = false),
                detail = partialDetail
            )
        )

        assertNull(FullPlayerSnapshotValidator.validate(partial))
    }

    @Test
    fun loopCannotOpenBeforeTheExactCurrentSongCacheIsReady() {
        val selected = FullPlayerTestFixtures.snapshot(selectedTool = PlayerSurfaceTool.LOOP)
        val unavailable = selected.copy(
            revision = selected.revision + 1L,
            toolDetail = selected.toolDetail.copy(
                revision = selected.toolDetail.revision + 1L,
                activity = selected.toolDetail.activity.copy(loopAvailable = false)
            )
        )

        assertEquals(
            FullPlayerSnapshotIssue.LOOP_DETAIL_INVALID,
            FullPlayerSnapshotValidator.validate(unavailable)
        )
    }

    @Test
    fun sameOwnerComponentRegressionIsRejectedWithoutPartialAdoption() {
        val accepted = FullPlayerTestFixtures.snapshot(baseRevision = 30L)
        val next = FullPlayerTestFixtures.snapshot(
            generation = accepted.owner.generation,
            baseRevision = 50L,
            catalog = accepted.catalog,
            queue = accepted.queue
        ).copy(
            selection = FullPlayerSelectionPacket(
                revision = accepted.selection.revision - 1L,
                currentIndex = accepted.selection.currentIndex
            )
        )
        val store = FullPlayerStateStore()
        store.adopt(accepted)

        assertEquals(
            FullPlayerAdoptionResult.REJECTED_COMPONENT_REGRESSION,
            store.adopt(next)
        )
        assertSame(accepted, store.snapshot)
    }

    @Test
    fun fourHundredSongCatalogAndQueueAreReusedAcrossOwnerSwitch() {
        val catalog = FullPlayerTestFixtures.catalog(size = 400)
        val queue = FullPlayerTestFixtures.queue(catalog)
        val first = FullPlayerTestFixtures.snapshot(
            generation = 1L,
            selectedIndex = 0,
            baseRevision = 10L,
            catalog = catalog,
            queue = queue
        )
        val last = FullPlayerTestFixtures.snapshot(
            generation = 2L,
            selectedIndex = 399,
            baseRevision = 100L,
            catalog = catalog,
            queue = queue
        )
        val store = FullPlayerStateStore()

        assertEquals(FullPlayerAdoptionResult.APPLIED_INITIAL, store.adopt(first))
        assertEquals(FullPlayerAdoptionResult.APPLIED_NEW_OWNER, store.adopt(last))
        assertSame(catalog, store.snapshot?.catalog)
        assertSame(queue, store.snapshot?.queue)
        assertEquals("song-399", store.snapshot?.owner?.songId)
    }

    @Test
    fun equalComponentRevisionCannotNameDifferentCatalogContent() {
        val accepted = FullPlayerTestFixtures.snapshot(baseRevision = 20L)
        val collidingCatalog = FullPlayerTestFixtures.catalog(
            size = accepted.catalog.size,
            revision = accepted.catalog.revision
        )
        val next = FullPlayerTestFixtures.snapshot(
            generation = accepted.owner.generation,
            baseRevision = 40L,
            catalog = collidingCatalog,
            queue = accepted.queue
        )
        val store = FullPlayerStateStore()
        store.adopt(accepted)

        assertEquals(
            FullPlayerAdoptionResult.REJECTED_COMPONENT_COLLISION,
            store.adopt(next)
        )
        assertSame(accepted, store.snapshot)
    }

    @Test
    fun progressStatusRequiresFiniteNormalizedProgressOnlyForProgressKind() {
        val valid = FullPlayerTestFixtures.snapshot(
            status = FullPlayerStatusPacket(
                revision = 19L,
                kind = FullPlayerStatusKind.PROGRESS,
                primaryText = "Downloading stems",
                secondaryText = "42%",
                progress = .42f
            )
        )
        val invalid = valid.copy(
            revision = valid.revision + 1L,
            status = valid.status.copy(progress = 1.2f)
        )

        assertNull(FullPlayerSnapshotValidator.validate(valid))
        assertEquals(
            FullPlayerSnapshotIssue.STATUS_CONTENT_MISMATCH,
            FullPlayerSnapshotValidator.validate(invalid)
        )
        assertTrue(valid.status.progress!! in 0f..1f)
    }
}
