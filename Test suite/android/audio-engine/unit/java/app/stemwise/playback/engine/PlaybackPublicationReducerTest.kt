package app.stemwise.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlaybackPublicationReducerTest {
    @Test fun trimWindowBoundsAbsoluteTransportAndPresentationWithoutChangingSourceDuration() {
        val reducer = PlaybackPublicationReducer()
        val song = selection("trimmed", 1L).copy(playbackStartMs = 10_000L, playbackEndMs = 40_000L)
        val owner = ownership(song, 101L)
        val metadata = metadata(song.songId, 1L, "Trimmed")
        request(reducer, song, owner, metadata)
        assertEquals(10_000L, reducer.snapshot().pendingSelection?.transport?.requestedPositionMs)
        assertEquals(10_000L, reducer.snapshot().pendingSelection?.transport?.presentedPositionMs)
        assertEquals(60_000L, reducer.snapshot().pendingSelection?.transport?.durationMs)
        for (outside in listOf(9_999L, 40_001L)) {
            assertEquals(PlaybackPublicationDisposition.IGNORED_STALE,
                reducer.requestTransport(song.token, 2L, true, outside).disposition)
        }
        publishCallback(reducer, song, metadata, 1L)
        val before = reducer.snapshot().committed
        for (outside in listOf(9_999L, 40_001L)) {
            assertEquals(PlaybackPublicationDisposition.IGNORED_STALE,
                reducer.requestTransport(song.token, 2L, true, outside).disposition)
            assertEquals(before, reducer.snapshot().committed)
        }
        assertEquals(PlaybackPublicationDisposition.APPLIED,
            reducer.requestTransport(song.token, 2L, false, 40_000L).disposition)
        reducer.onControllerEvent(NativeEngineControllerEvent.Position(song.token, 0L, 2L, owner))
        assertEquals(10_000L, reducer.snapshot().committed?.transport?.presentedPositionMs)
        reducer.onControllerEvent(NativeEngineControllerEvent.Position(song.token, 60_000L, 3L, owner))
        assertEquals(40_000L, reducer.snapshot().committed?.transport?.presentedPositionMs)
        assertEquals(60_000L, reducer.snapshot().committed?.transport?.durationMs)
    }

    @Test
    fun terminalOwnerIsExactAndPendingFailurePreservesHealthyCommittedOwner() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("terminal-a", 1L)
        val owner = ownership(a, 101L)
        request(reducer, a, owner, metadata(a.songId, 1L, "A"))
        publishCallback(reducer, a, metadata(a.songId, 1L, "A"), 1L)
        val b = selection("pending-b", 2L)
        request(reducer, b, ownership(b, 202L), metadata(b.songId, 1L, "B"))
        reducer.onControllerEvent(NativeEngineControllerEvent.SelectionFailed(b.token, "B unavailable"))
        assertEquals(owner, reducer.snapshot().committed?.owner?.ownership)
        assertEquals(PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(NativeEngineControllerEvent.OwnerTerminated(
                owner.copy(generation = 100L), "old failure")).disposition)
        assertEquals(owner, reducer.snapshot().committed?.owner?.ownership)
        val result = reducer.onControllerEvent(NativeEngineControllerEvent.OwnerTerminated(owner, "lost"))
        assertNull(result.state.committed)
        assertTrue(result.effects.single() is PlaybackPublicationEffect.OwnerTerminated)
        publishCallback(reducer, a, metadata(a.songId, 1L, "A"), 3L)
        assertNull(reducer.snapshot().committed)
    }

    @Test
    fun terminalOwnerClearsSameTokenReplacementButPreservesDistinctPendingSelection() {
        for (sameSelection in listOf(true, false)) {
            val reducer = PlaybackPublicationReducer()
            val a = selection("terminal-replacement", 1L)
            val owner = ownership(a, 101L)
            request(reducer, a, owner, metadata(a.songId, 1L, "A"))
            publishCallback(reducer, a, metadata(a.songId, 1L, "A"), 1L)
            val pending = if (sameSelection) a else selection("newer-b", 2L)
            request(reducer, pending, ownership(pending, 202L), metadata(pending.songId, 1L, "Pending"))
            val result = reducer.onControllerEvent(NativeEngineControllerEvent.OwnerTerminated(owner, "empty EOS"))
            assertNull(result.state.committed)
            assertEquals(if (sameSelection) null else pending.token,
                result.state.pendingSelection?.selection?.token)
            assertTrue(result.effects.single() is PlaybackPublicationEffect.OwnerTerminated)
        }
    }

    @Test
    fun physicalSeekCompletionSurvivesConflatedPositionUpdates() {
        val reducer = PlaybackPublicationReducer()
        val song = selection("seek-owner", 1L)
        val oldOwner = ownership(song, 101L)
        request(reducer, song, oldOwner, metadata("seek-owner", 1L, "Seek"))
        publishCallback(reducer, song, metadata("seek-owner", 1L, "Seek"), 11L)
        val old = checkNotNull(reducer.snapshot().committed)
        val newOwner = old.owner.ownership.copy(generation = old.owner.ownership.generation + 1L)
        reducer.onControllerEvent(NativeEngineControllerEvent.OwnerPublished(NativeEngineOwnerPublication(
            song, newOwner, null, NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
            null, completedSeekRevision = 4L
        )))
        reducer.onControllerEvent(NativeEngineControllerEvent.Position(song.token, 12_700L, 30L, newOwner))
        val latest = checkNotNull(reducer.snapshot().committed)
        assertEquals(4L, latest.transport.completedSeekRevision)
        assertEquals(12_700L, latest.transport.presentedPositionMs)
        val rejected = reducer.onControllerEvent(NativeEngineControllerEvent.Position(song.token, 100L, 31L, old.owner.ownership))
        assertEquals(PlaybackPublicationDisposition.IGNORED_STALE, rejected.disposition)
        assertEquals(latest, reducer.snapshot().committed)
    }

    @Test
    fun manualSelectionImmediatelyInvalidatesOutgoingAndFailureCannotRestoreIt() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("manual-a", 1L)
        val aOwner = ownership(a, 101L)
        request(reducer, a, aOwner, metadata("manual-a", 1L, "A"))
        publishCallback(reducer, a, metadata("manual-a", 1L, "A"), 11L)

        val boundary = reducer.beginManualSelection()
        assertEquals(PlaybackPublicationDisposition.APPLIED, boundary.disposition)
        assertNull(boundary.state.committed)
        assertNull(boundary.state.pendingSelection)

        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.Position(a.token, 4_000L, 12L)
            ).disposition
        )
        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.AudibleEnd(a.token, 1L, aOwner)
            ).disposition
        )

        val b = selection("manual-b", 2L)
        request(reducer, b, ownership(b, 202L), metadata("manual-b", 1L, "B"))
        reducer.onControllerEvent(
            NativeEngineControllerEvent.SelectionFailed(b.token, "broken B")
        )
        assertNull(reducer.snapshot().committed)
        assertNull(reducer.snapshot().pendingSelection)
    }

    @Test
    fun pendingBDoesNotOverlayAudibleAAndStaleACallbacksCannotMutateCommittedB() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("a", 1L)
        val aOwner = ownership(a, 101L)
        request(reducer, a, aOwner, metadata("a", 1L, "A title"))
        publishCallback(reducer, a, metadata("a", 1L, "A title"), 11L)
        reducer.requestSourceMode(a.token, 7L, EngineSourceMode.STEMS)
        reducer.requestStemPackage(a.token, stem("a-v1", "stem-a-v1", 9L))

        val b = selection("b", 2L)
        val bOwner = ownership(b, 202L)
        request(reducer, b, bOwner, metadata("b", 1L, "B title"))

        // A remains the one publication until B has owner proof. A position is still legitimate
        // while its PCM remains audible, but it cannot expose B metadata.
        assertEquals(
            PlaybackPublicationDisposition.APPLIED,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.Position(a.token, 3_000L, 12L)
            ).disposition
        )
        assertPublishedSong(reducer, "a")
        assertEquals("b", reducer.snapshot().pendingSelection?.selection?.songId)

        publishCallback(reducer, b, metadata("b", 1L, "B title"), 21L)
        val bBeforeStaleEvents = requireNotNull(reducer.snapshot().committed)
        assertPublishedSong(reducer, "b")

        val staleEvents = listOf<NativeEngineControllerEvent>(
            NativeEngineControllerEvent.Position(a.token, 9_000L, 99L),
            NativeEngineControllerEvent.AudibleEnd(a.token, 1L, aOwner),
            NativeEngineControllerEvent.SourceModeApplied(
                a.token,
                7L,
                EngineSourceMode.STEMS
            ),
            NativeEngineControllerEvent.StemPackageInstalled(
                a.token,
                "a-v1",
                "stem-a-v1",
                9L,
                100L
            ),
            NativeEngineControllerEvent.DecoderFailed(
                a.token,
                aOwner.generation,
                0,
                "late A decoder",
                true
            )
        )
        staleEvents.forEach { event ->
            assertEquals(
                PlaybackPublicationDisposition.IGNORED_STALE,
                reducer.onControllerEvent(event).disposition
            )
        }

        assertEquals(bBeforeStaleEvents, reducer.snapshot().committed)
        assertPublishedSong(reducer, "b")
    }

    @Test
    fun sameAudioMetadataUpdateChangesOnlyAtomicMetadata() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("a", 1L)
        request(reducer, a, ownership(a, 10L), metadata("a", 1L, "old"))
        publishCallback(reducer, a, metadata("a", 1L, "old"), 1L)
        reducer.requestTransport(a.token, 2L, false, 4_000L)
        reducer.requestSourceMode(a.token, 3L, EngineSourceMode.STEMS)
        reducer.requestStemPackage(a.token, stem("v1", "stem-v1", 4L))

        val before = requireNotNull(reducer.snapshot().committed)
        val result = reducer.onControllerEvent(
            NativeEngineControllerEvent.MetadataUpdated(metadata("a", 2L, "new"))
        )
        val after = requireNotNull(result.state.committed)

        assertEquals(PlaybackPublicationDisposition.APPLIED, result.disposition)
        assertEquals("new", after.metadata.payloadKey)
        assertEquals(before.owner, after.owner)
        assertEquals(before.selection, after.selection)
        assertEquals(before.transport, after.transport)
        assertEquals(before.source, after.source)
        assertEquals(before.stems, after.stems)
        assertPublishedSong(reducer, "a")

        // Metadata for a pending song is retained only in pending state and cannot overlay A.
        val b = selection("b", 2L)
        request(reducer, b, ownership(b, 20L), metadata("b", 1L, "B old"))
        reducer.onControllerEvent(
            NativeEngineControllerEvent.MetadataUpdated(metadata("b", 2L, "B new"))
        )
        assertPublishedSong(reducer, "a")
        assertEquals("B new", reducer.snapshot().pendingSelection?.metadata?.payloadKey)
    }

    @Test
    fun rapidAThenBThenCCommitsOnlyLatestOwnerProof() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("a", 1L)
        val b = selection("b", 2L)
        val c = selection("c", 3L)
        request(reducer, a, ownership(a, 10L), metadata("a", 1L, "A"))
        request(reducer, b, ownership(b, 20L), metadata("b", 1L, "B"))
        request(reducer, c, ownership(c, 30L), metadata("c", 1L, "C"))

        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            publishCallback(reducer, a, metadata("a", 1L, "A"), 1L).disposition
        )
        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            publishCallback(reducer, b, metadata("b", 1L, "B"), 2L).disposition
        )
        assertNull(reducer.snapshot().committed)

        assertEquals(
            PlaybackPublicationDisposition.APPLIED,
            publishCallback(reducer, c, metadata("c", 1L, "C"), 3L).disposition
        )
        assertPublishedSong(reducer, "c")
        assertNull(reducer.snapshot().pendingSelection)
    }

    @Test
    fun pausedNonemptyInstallIsAnAtomicOwnerPublicationWithoutFabricatedCallback() {
        val reducer = PlaybackPublicationReducer()
        val paused = selection("paused", 1L, autoplay = false)
        request(
            reducer,
            paused,
            ownership(paused, 77L),
            metadata("paused", 1L, "Paused")
        )

        val result = reducer.onControllerEvent(
            NativeEngineControllerEvent.OwnerPublished(
                NativeEngineOwnerPublication(
                    selection = paused,
                    ownership = ownership(paused, 77L),
                    metadata = metadata("paused", 1L, "Paused"),
                    kind = NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
                    evidence = null
                )
            )
        )
        val publication = requireNotNull(result.state.committed)

        assertEquals(PlaybackPublicationDisposition.APPLIED, result.disposition)
        assertEquals(
            NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
            publication.owner.kind
        )
        assertNull(publication.owner.callbackSequence)
        assertFalse(publication.transport.playWhenReady)
        assertPublishedSong(reducer, "paused")
    }

    @Test
    fun ownerPublicationWithMismatchedExactOwnershipIsRejected() {
        val reducer = PlaybackPublicationReducer()
        val selection = selection("owner", 1L)
        val expected = ownership(selection, 77L)
        request(reducer, selection, expected, metadata("owner", 1L, "Owner"))

        val result = reducer.onControllerEvent(
            NativeEngineControllerEvent.OwnerPublished(
                NativeEngineOwnerPublication(
                    selection = selection,
                    ownership = ownership(selection, 78L),
                    metadata = metadata("owner", 1L, "Owner"),
                    kind = NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED,
                    evidence = EngineAudibleOwnerEvidence(
                        token = selection.token,
                        songId = selection.songId,
                        callbackSequence = 1L,
                        firstOutputFrame = 0L,
                        finalPcmFrames = 192,
                        outputFingerprintSequence = 1L
                    )
                )
            )
        )

        assertEquals(PlaybackPublicationDisposition.IGNORED_STALE, result.disposition)
        assertNull(result.state.committed)
        assertEquals(expected, result.state.pendingSelection?.expectedOwnership)
    }

    @Test
    fun sameTokenOwnerRebaseRejectsRetiredEndAndAcceptsNewGenerationExactlyOnce() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("a", 1L)
        val originalOwner = ownership(a, 10L)
        request(reducer, a, originalOwner, metadata("a", 1L, "A"))
        publishCallback(reducer, a, metadata("a", 1L, "A"), 10L)

        // A newer requested song must not prevent the still-audible owner from publishing its
        // exact post-seek/route generation.
        val b = selection("b", 2L)
        request(reducer, b, ownership(b, 20L), metadata("b", 1L, "B"))
        val rebasedOwner = ownership(a, 11L)
        val rebased = reducer.onControllerEvent(
            NativeEngineControllerEvent.OwnerPublished(
                NativeEngineOwnerPublication(
                    selection = a,
                    ownership = rebasedOwner,
                    metadata = metadata("a", 1L, "A"),
                    kind = NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED,
                    evidence = EngineAudibleOwnerEvidence(
                        token = a.token,
                        songId = a.songId,
                        callbackSequence = 11L,
                        firstOutputFrame = 1_024L,
                        finalPcmFrames = 192,
                        outputFingerprintSequence = 11L
                    )
                )
            )
        )

        assertEquals(PlaybackPublicationDisposition.APPLIED, rebased.disposition)
        assertEquals(rebasedOwner, rebased.state.committed?.owner?.ownership)
        assertEquals(b, rebased.state.pendingSelection?.selection)
        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.AudibleEnd(a.token, 1L, originalOwner)
            ).disposition
        )

        val accepted = reducer.onControllerEvent(
            NativeEngineControllerEvent.AudibleEnd(a.token, 2L, rebasedOwner)
        )
        assertEquals(PlaybackPublicationDisposition.APPLIED, accepted.disposition)
        assertEquals(
            listOf(PlaybackPublicationEffect.AudibleEnd(a.token, rebasedOwner, 2L)),
            accepted.effects
        )
        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.AudibleEnd(a.token, 3L, rebasedOwner)
            ).disposition
        )
        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.OwnerPublished(
                    NativeEngineOwnerPublication(
                        selection = a,
                        ownership = originalOwner,
                        metadata = metadata("a", 1L, "A"),
                        kind = NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
                        evidence = null
                    )
                )
            ).disposition
        )
    }

    @Test
    fun sameTokenOwnerRebaseRequiresFreshSourceProof() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("source-rebase", 1L)
        request(reducer, a, ownership(a, 10L), metadata(a.songId, 1L, "A"))
        publishCallback(reducer, a, metadata(a.songId, 1L, "A"), 10L)
        reducer.requestSourceMode(a.token, 7L, EngineSourceMode.STEMS)
        reducer.requestStemPackage(a.token, stem("pkg", "stem-v1", 8L))
        reducer.onControllerEvent(
            NativeEngineControllerEvent.SourceModeApplied(a.token, 7L, EngineSourceMode.STEMS)
        )
        reducer.onControllerEvent(
            NativeEngineControllerEvent.StemPackageInstalled(
                a.token,
                "pkg",
                "stem-v1",
                8L,
                11L
            )
        )

        val rebasedOwner = ownership(a, 11L)
        val rebased = reducer.onControllerEvent(
            NativeEngineControllerEvent.OwnerPublished(
                NativeEngineOwnerPublication(
                    selection = a,
                    ownership = rebasedOwner,
                    metadata = metadata(a.songId, 1L, "A"),
                    kind = NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED,
                    evidence = EngineAudibleOwnerEvidence(
                        token = a.token,
                        songId = a.songId,
                        callbackSequence = 12L,
                        firstOutputFrame = 1_024L,
                        finalPcmFrames = 192,
                        outputFingerprintSequence = 12L
                    )
                )
            )
        )
        val publication = requireNotNull(rebased.state.committed)

        assertEquals(PlaybackPublicationDisposition.APPLIED, rebased.disposition)
        assertEquals(rebasedOwner, publication.owner.ownership)
        assertEquals(EngineSourceMode.STEMS, publication.source.desiredMode)
        assertEquals(7L, publication.source.desiredRevision)
        assertEquals(EngineSourceMode.ORIGINAL, publication.source.preparedMode)
        assertEquals(0L, publication.source.preparedRevision)
        assertEquals(EngineSourceMode.ORIGINAL, publication.source.appliedMode)
        assertEquals(0L, publication.source.appliedRevision)
        assertEquals("pkg", publication.stems.installed?.packageSignature)

        val restored = reducer.onControllerEvent(
            NativeEngineControllerEvent.SourceModeApplied(a.token, 7L, EngineSourceMode.STEMS)
        )
        assertEquals(PlaybackPublicationDisposition.APPLIED, restored.disposition)
        assertEquals(
            EngineSourceMode.STEMS,
            restored.state.committed?.source?.appliedMode
        )
    }

    @Test
    fun sourceReversalRequiresTheExactLatestDesiredRevisionAndMode() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("a", 1L)
        request(reducer, a, ownership(a, 10L), metadata("a", 1L, "A"))
        publishCallback(reducer, a, metadata("a", 1L, "A"), 1L)

        reducer.requestSourceMode(a.token, 10L, EngineSourceMode.STEMS)
        reducer.requestSourceMode(a.token, 11L, EngineSourceMode.ORIGINAL)
        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.SourceModeApplied(
                    a.token,
                    10L,
                    EngineSourceMode.STEMS
                )
            ).disposition
        )
        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.SourceModeApplied(
                    a.token,
                    11L,
                    EngineSourceMode.STEMS
                )
            ).disposition
        )
        assertEquals(
            PlaybackPublicationDisposition.APPLIED,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.SourceModeApplied(
                    a.token,
                    11L,
                    EngineSourceMode.ORIGINAL
                )
            ).disposition
        )

        val source = requireNotNull(reducer.snapshot().committed).source
        assertEquals(11L, source.desiredRevision)
        assertEquals(11L, source.appliedRevision)
        assertEquals(EngineSourceMode.ORIGINAL, source.desiredMode)
        assertEquals(EngineSourceMode.ORIGINAL, source.appliedMode)
    }

    @Test
    fun originalFallbackRejectsLateStemErrorsAndNewOwnersRetireRecoverableDecoderErrors() {
        val reducer = PlaybackPublicationReducer()
        val selection = selection("decoder-fallback", 1L)
        val owner = ownership(selection, 10L)
        val metadata = metadata(selection.songId, 1L, "Fallback")
        request(reducer, selection, owner, metadata)
        publishCallback(reducer, selection, metadata, 1L)
        reducer.requestStemPackage(selection.token, stem("fallback", "stem-fallback", 2L))
        reducer.requestSourceMode(selection.token, 2L, EngineSourceMode.STEMS)
        reducer.requestSourceMode(selection.token, 3L, EngineSourceMode.ORIGINAL)

        val failedStemRequest = requireNotNull(reducer.snapshot().committed?.stems?.desired)
        assertEquals(
            PlaybackPublicationDisposition.APPLIED,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.StemPackageFailed(
                    token = selection.token,
                    packageSignature = failedStemRequest.packageSignature,
                    stemRevision = failedStemRequest.stemRevision,
                    requestRevision = failedStemRequest.requestRevision,
                    recoverable = true,
                    retainedPriorPackage = false,
                    cause = "late failed stem package"
                )
            ).disposition
        )
        assertEquals(
            PlaybackStemPackagePhase.FAILED,
            requireNotNull(reducer.snapshot().committed).stems.phase
        )
        assertNull(requireNotNull(reducer.snapshot().committed).issue)

        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.DecoderFailed(
                    selection.token,
                    owner.generation,
                    6,
                    "late failed stem",
                    true
                )
            ).disposition
        )
        assertNull(requireNotNull(reducer.snapshot().committed).issue)

        assertEquals(
            PlaybackPublicationDisposition.APPLIED,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.DecoderFailed(
                    selection.token,
                    owner.generation,
                    0,
                    "failed Original",
                    true
                )
            ).disposition
        )
        assertEquals("decoder-0", requireNotNull(reducer.snapshot().committed).issue?.category)

        reducer.onControllerEvent(
            NativeEngineControllerEvent.OwnerPublished(
                NativeEngineOwnerPublication(
                    selection,
                    ownership(selection, 11L),
                    metadata,
                    NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
                    null
                )
            )
        )
        assertNull(requireNotNull(reducer.snapshot().committed).issue)

        reducer.onControllerEvent(
            NativeEngineControllerEvent.DecoderFailed(
                selection.token,
                11L,
                0,
                "unrecoverable Original failure",
                false
            )
        )
        reducer.onControllerEvent(
            NativeEngineControllerEvent.OwnerPublished(
                NativeEngineOwnerPublication(
                    selection,
                    ownership(selection, 12L),
                    metadata,
                    NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
                    null
                )
            )
        )
        assertEquals("decoder-0", requireNotNull(reducer.snapshot().committed).issue?.category)
    }

    @Test
    fun pausedPreparedOriginalSettlesSeparatelyFromAudibleSourceEvidence() {
        val reducer = PlaybackPublicationReducer()
        val selection = selection("paused-original", 1L)
        request(
            reducer,
            selection,
            ownership(selection, 10L),
            metadata(selection.songId, 1L, "Paused")
        )
        publishCallback(reducer, selection, metadata(selection.songId, 1L, "Paused"), 1L)
        reducer.requestSourceMode(selection.token, 10L, EngineSourceMode.STEMS)
        reducer.onControllerEvent(
            NativeEngineControllerEvent.SourceModeApplied(
                selection.token,
                10L,
                EngineSourceMode.STEMS
            )
        )
        reducer.requestSourceMode(selection.token, 11L, EngineSourceMode.ORIGINAL)

        val result = reducer.onControllerEvent(
            NativeEngineControllerEvent.SourceModeReadyAwaitingAudible(
                selection.token,
                11L,
                EngineSourceMode.ORIGINAL
            )
        )
        val source = requireNotNull(result.state.committed).source

        assertEquals(PlaybackPublicationDisposition.APPLIED, result.disposition)
        assertEquals(11L, source.preparedRevision)
        assertEquals(EngineSourceMode.ORIGINAL, source.preparedMode)
        assertEquals(10L, source.appliedRevision)
        assertEquals(EngineSourceMode.STEMS, source.appliedMode)
    }

    @Test
    fun transientSourceNotReadyRemainsPreparationAndExactAppliedEvidenceClearsIt() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("source-preparing", 1L)
        request(reducer, a, ownership(a, 10L), metadata(a.songId, 1L, "Preparing"))
        publishCallback(reducer, a, metadata(a.songId, 1L, "Preparing"), 1L)
        reducer.requestSourceMode(a.token, 2L, EngineSourceMode.STEMS)

        assertEquals(
            PlaybackPublicationDisposition.APPLIED,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.SourceModeNotReady(
                    a.token,
                    2L,
                    EngineSourceMode.STEMS,
                    "The requested source is not yet contiguous"
                )
            ).disposition
        )
        var publication = requireNotNull(reducer.snapshot().committed)
        assertEquals(
            "The requested source is not yet contiguous",
            publication.source.notReadyReason
        )
        assertNull(publication.issue)
        assertEquals(EngineSourceMode.ORIGINAL, publication.source.appliedMode)

        assertEquals(
            PlaybackPublicationDisposition.APPLIED,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.SourceModeApplied(
                    a.token,
                    2L,
                    EngineSourceMode.STEMS
                )
            ).disposition
        )
        publication = requireNotNull(reducer.snapshot().committed)
        assertEquals(EngineSourceMode.STEMS, publication.source.appliedMode)
        assertNull(publication.source.notReadyReason)
        assertNull(publication.issue)
    }

    @Test
    fun stemV1AndV2RequireExactRequestsAndReadyAwaitingAudibleNeverInstalls() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("a", 1L)
        request(reducer, a, ownership(a, 10L), metadata("a", 1L, "A"))
        publishCallback(reducer, a, metadata("a", 1L, "A"), 1L)

        reducer.requestStemPackage(a.token, stem("pkg-v1", "stem-v1", 1L))
        reducer.onControllerEvent(
            NativeEngineControllerEvent.StemPackagePreparing(
                a.token,
                "pkg-v1",
                "stem-v1",
                1L,
                null
            )
        )
        reducer.onControllerEvent(
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(
                a.token,
                "pkg-v1",
                "stem-v1",
                1L,
                false
            )
        )
        var stems = requireNotNull(reducer.snapshot().committed).stems
        assertEquals(PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE, stems.phase)
        assertNull(stems.installed)

        reducer.onControllerEvent(
            NativeEngineControllerEvent.StemPackageInstalled(
                a.token,
                "pkg-v1",
                "stem-v1",
                1L,
                10L
            )
        )
        stems = requireNotNull(reducer.snapshot().committed).stems
        assertEquals("pkg-v1", stems.installed?.packageSignature)

        reducer.requestStemPackage(a.token, stem("pkg-v2", "stem-v2", 2L))
        reducer.onControllerEvent(
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(
                a.token,
                "pkg-v2",
                "stem-v2",
                2L,
                true
            )
        )
        stems = requireNotNull(reducer.snapshot().committed).stems
        assertEquals(PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE, stems.phase)
        assertEquals("pkg-v1", stems.installed?.packageSignature)

        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.StemPackageInstalled(
                    a.token,
                    "pkg-v1",
                    "stem-v1",
                    1L,
                    11L
                )
            ).disposition
        )
        reducer.onControllerEvent(
            NativeEngineControllerEvent.StemPackageInstalled(
                a.token,
                "pkg-v2",
                "stem-v2",
                2L,
                12L
            )
        )
        stems = requireNotNull(reducer.snapshot().committed).stems
        assertEquals(PlaybackStemPackagePhase.INSTALLED, stems.phase)
        assertEquals("pkg-v2", stems.installed?.packageSignature)
        assertEquals(2L, stems.installed?.requestRevision)
    }

    @Test
    fun delayedStopCompletionCannotClearASelectionRequestedAfterThatStop() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("a", 1L)
        request(reducer, a, ownership(a, 10L), metadata("a", 1L, "A"))
        publishCallback(reducer, a, metadata("a", 1L, "A"), 1L)
        val stopId = NativeEngineStopRequestId(40L)
        reducer.requestStop(stopId)

        val b = selection("b", 2L)
        request(reducer, b, ownership(b, 20L), metadata("b", 1L, "B"))
        val delayed = reducer.onControllerEvent(
            NativeEngineControllerEvent.StopCompleted(stopId)
        )

        assertEquals(PlaybackPublicationDisposition.IGNORED_STALE, delayed.disposition)
        assertEquals(
            PlaybackPublicationEffect.StopCompletion(stopId, false),
            delayed.effects.single()
        )
        assertPublishedSong(reducer, "a")
        assertEquals("b", reducer.snapshot().pendingSelection?.selection?.songId)

        publishCallback(reducer, b, metadata("b", 1L, "B"), 2L)
        assertPublishedSong(reducer, "b")
    }

    @Test
    fun exactStopCompletionClearsOnlyItsCorrelatedPublicationEpoch() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("a", 1L)
        request(reducer, a, ownership(a, 10L), metadata("a", 1L, "A"))
        publishCallback(reducer, a, metadata("a", 1L, "A"), 1L)
        val expected = NativeEngineStopRequestId(50L)
        reducer.requestStop(expected)

        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.StopCompleted(NativeEngineStopRequestId(51L))
            ).disposition
        )
        assertNotNull(reducer.snapshot().committed)

        val completion = reducer.onControllerEvent(
            NativeEngineControllerEvent.StopCompleted(expected)
        )
        assertEquals(PlaybackPublicationDisposition.APPLIED, completion.disposition)
        assertEquals(
            PlaybackPublicationEffect.StopCompletion(expected, true),
            completion.effects.single()
        )
        assertNull(reducer.snapshot().committed)
        assertNull(reducer.snapshot().pendingSelection)
    }

    @Test
    fun audibleEndIsOncePerExactOwnershipAndLeavesQueueChoiceExternal() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("a", 1L)
        val aOwner = ownership(a, 10L)
        request(reducer, a, aOwner, metadata("a", 1L, "A"))
        publishCallback(reducer, a, metadata("a", 1L, "A"), 1L)

        val first = reducer.onControllerEvent(
            NativeEngineControllerEvent.AudibleEnd(a.token, 7L, aOwner)
        )
        assertEquals(PlaybackPublicationDisposition.APPLIED, first.disposition)
        assertEquals(
            PlaybackPublicationEffect.AudibleEnd(a.token, aOwner, 7L),
            first.effects.single()
        )
        assertPublishedSong(reducer, "a")

        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.AudibleEnd(a.token, 7L, aOwner)
            ).disposition
        )
        assertEquals(
            PlaybackPublicationDisposition.IGNORED_STALE,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.AudibleEnd(a.token, 8L, aOwner)
            ).disposition
        )
        assertPublishedSong(reducer, "a")

        val b = selection("b", 2L)
        val bOwner = ownership(b, 20L)
        request(reducer, b, bOwner, metadata("b", 1L, "B"))
        publishCallback(reducer, b, metadata("b", 1L, "B"), 2L)
        assertEquals(
            PlaybackPublicationDisposition.APPLIED,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.AudibleEnd(b.token, 7L, bOwner)
            ).disposition
        )
        assertPublishedSong(reducer, "b")
    }

    @Test
    fun tenThousandEventStormNeverCrossesOwnerTokenGenerationOrRevision() {
        val reducer = PlaybackPublicationReducer()
        val a = selection("a", 1L)
        val aOwner = ownership(a, 10L)
        request(reducer, a, aOwner, metadata("a", 1L, "A"))
        publishCallback(reducer, a, metadata("a", 1L, "A"), 1L)

        val b = selection("b", 2L)
        val bOwner = ownership(b, 20L)
        request(reducer, b, bOwner, metadata("b", 1L, "B"))
        reducer.requestSourceMode(b.token, 100L, EngineSourceMode.STEMS)
        reducer.requestStemPackage(b.token, stem("b-v2", "stem-b-v2", 200L))
        publishCallback(reducer, b, metadata("b", 1L, "B"), 2L)

        repeat(10_000) { index ->
            when (index % 5) {
                0 -> reducer.onControllerEvent(
                    NativeEngineControllerEvent.Position(a.token, index.toLong(), index + 3L)
                )
                1 -> reducer.onControllerEvent(
                    NativeEngineControllerEvent.DecoderFailed(
                        b.token,
                        bOwner.generation - 1L,
                        0,
                        "wrong generation",
                        true
                    )
                )
                2 -> reducer.onControllerEvent(
                    NativeEngineControllerEvent.SourceModeApplied(
                        b.token,
                        99L,
                        EngineSourceMode.STEMS
                    )
                )
                3 -> reducer.onControllerEvent(
                    NativeEngineControllerEvent.StemPackageInstalled(
                        b.token,
                        "b-v1",
                        "stem-b-v1",
                        199L,
                        index + 1L
                    )
                )
                else -> reducer.onControllerEvent(
                    NativeEngineControllerEvent.AudibleEnd(a.token, index + 1L, aOwner)
                )
            }
        }

        val publication = requireNotNull(reducer.snapshot().committed)
        assertEquals(b.token, publication.selection.token)
        assertEquals(bOwner, publication.owner.ownership)
        assertEquals(EngineSourceMode.ORIGINAL, publication.source.appliedMode)
        assertNull(publication.stems.installed)
        assertNull(publication.issue)
        assertPublishedSong(reducer, "b")

        assertEquals(
            PlaybackPublicationDisposition.APPLIED,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.SourceModeApplied(
                    b.token,
                    100L,
                    EngineSourceMode.STEMS
                )
            ).disposition
        )
        assertEquals(
            PlaybackPublicationDisposition.APPLIED,
            reducer.onControllerEvent(
                NativeEngineControllerEvent.StemPackageInstalled(
                    b.token,
                    "b-v2",
                    "stem-b-v2",
                    200L,
                    10_001L
                )
            ).disposition
        )
        assertPublishedSong(reducer, "b")
    }

    private fun request(
        reducer: PlaybackPublicationReducer,
        selection: EngineSelection,
        ownership: NativeRuntimeOwnership,
        metadata: NativeEngineMetadata
    ): PlaybackPublicationReduction = reducer.requestSelection(
        PlaybackPublicationSelectionRequest(
            selection,
            ownership,
            PlaybackPublicationMetadataMapper.Default.map(metadata)
        )
    )

    private fun publishCallback(
        reducer: PlaybackPublicationReducer,
        selection: EngineSelection,
        metadata: NativeEngineMetadata,
        callbackSequence: Long
    ): PlaybackPublicationReduction = reducer.onControllerEvent(
        NativeEngineControllerEvent.OwnerPublished(
            NativeEngineOwnerPublication(
                selection = selection,
                ownership = reducer.snapshot().let { state ->
                    state.pendingSelection
                        ?.takeIf { it.selection.token == selection.token }
                        ?.expectedOwnership
                        ?: state.committed
                            ?.takeIf { it.selection.token == selection.token }
                            ?.owner
                            ?.ownership
                        ?: ownership(selection, callbackSequence)
                },
                metadata = metadata,
                kind = NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED,
                evidence = EngineAudibleOwnerEvidence(
                    token = selection.token,
                    songId = selection.songId,
                    callbackSequence = callbackSequence,
                    firstOutputFrame = 0L,
                    finalPcmFrames = 192,
                    outputFingerprintSequence = callbackSequence
                )
            )
        )
    )

    private fun selection(
        songId: String,
        token: Long,
        autoplay: Boolean = true
    ) = EngineSelection(
        token = EngineSelectionToken(token),
        songId = songId,
        audioRevision = "audio-$songId",
        originalLocation = "/songs/$songId.m4a",
        durationMs = 60_000L,
        startPositionMs = 0L,
        autoplay = autoplay
    )

    private fun ownership(selection: EngineSelection, generation: Long) =
        NativeRuntimeOwnership(selection.token.value, generation)

    private fun metadata(
        songId: String,
        revision: Long,
        title: String
    ): NativeEngineMetadata {
        val value = PlaybackPublicationMetadata(
            songId = songId,
            audioRevision = "audio-$songId",
            revision = revision,
            payloadKey = title
        )
        return NativeEngineMetadata(
            songId = songId,
            audioRevision = "audio-$songId",
            revision = revision,
            payloadKey = value.payloadKey
        )
    }

    private fun stem(signature: String, revision: String, requestRevision: Long) =
        PlaybackStemPackageRequest(signature, revision, requestRevision)

    private fun assertPublishedSong(reducer: PlaybackPublicationReducer, expected: String) {
        val publication = requireNotNull(reducer.snapshot().committed)
        assertEquals(expected, publication.selection.songId)
        assertEquals(expected, publication.metadata.songId)
        assertEquals(
            publication.selection.token.value,
            publication.owner.ownership.selectionToken
        )
        assertSame(publication, reducer.snapshot().committed)
    }
}
