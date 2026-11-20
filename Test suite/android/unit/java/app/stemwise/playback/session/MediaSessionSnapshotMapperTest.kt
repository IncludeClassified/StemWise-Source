package app.stemwise.playback.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class MediaSessionSnapshotMapperTest {
    @Test
    fun playbackSpeedIsProjectedForExternalPositionExtrapolation() {
        val projection = MediaSessionSnapshotMapper.project(
            MediaSessionSnapshot(
                media = SessionMediaDescription("speed", "Speed"),
                playbackSpeed = 1.4f,
                playing = true,
                playWhenReady = true
            )
        )

        assertEquals(1.4f, projection.playbackSpeed, 0f)
    }

    @Test
    fun sessionCadencePublishesStructureSeeksAndLoopWrapsImmediately() {
        val playing = MediaSessionSnapshot(
            media = SessionMediaDescription("song", "Song"),
            positionMs = 10_000L,
            playing = true,
            playWhenReady = true
        )

        assertFalse(
            mediaSessionSnapshotNeedsImmediatePublication(
                playing,
                playing.copy(positionMs = 10_120L)
            )
        )
        assertTrue(
            mediaSessionSnapshotNeedsImmediatePublication(
                playing,
                playing.copy(positionMs = 15_000L)
            )
        )
        assertTrue(
            mediaSessionSnapshotNeedsImmediatePublication(
                playing,
                playing.copy(positionMs = 2_000L)
            )
        )
        assertTrue(
            mediaSessionSnapshotNeedsImmediatePublication(
                playing,
                playing.copy(playbackSpeed = 1.25f)
            )
        )
        assertTrue(
            mediaSessionSnapshotNeedsImmediatePublication(
                playing,
                playing.copy(playing = false)
            )
        )
    }

    @Test
    fun onlyRapidPendingOwnerIdentityChurnMayCoalesce() {
        val committed = MediaSessionSnapshot(
            media = SessionMediaDescription("a", "A"),
            playing = true,
            playWhenReady = true
        )
        val pendingB = MediaSessionSnapshot(
            media = SessionMediaDescription("b", "B"),
            loading = true,
            playWhenReady = true
        )
        val pendingC = pendingB.copy(media = SessionMediaDescription("c", "C"))

        assertFalse(mediaSessionPendingOwnerChangeCanCoalesce(committed, pendingB))
        assertTrue(mediaSessionPendingOwnerChangeCanCoalesce(pendingB, pendingC))
        assertFalse(
            mediaSessionPendingOwnerChangeCanCoalesce(
                pendingB,
                pendingB.copy(playWhenReady = false)
            )
        )
        assertFalse(
            mediaSessionPendingOwnerChangeCanCoalesce(
                pendingB,
                pendingC.copy(loading = false, playing = true)
            )
        )
    }

    @Test
    fun notificationRefreshConflatesFourHundredOwnersToLatestCanonicalIdentity() {
        val gate = SessionNotificationRefreshGate()
        val owners = (0 until 400).map { index ->
            MediaSessionSnapshot(
                media = SessionMediaDescription(
                    id = "pressure-scale-$index",
                    title = "Pressure Scale ${index.toString().padStart(3, '0')}",
                    artist = "Pressure Artist",
                    revision = "revision-$index",
                    artworkData = SessionArtworkData(byteArrayOf(index.toByte(), 1, 2, 3))
                ),
                transportOwnerKey = "token-$index\u0000pressure-scale-$index"
            )
        }

        owners.forEach { owner -> assertTrue(gate.offer(owner)) }

        assertNull(
            "A superseded canonical owner must not refresh",
            gate.drain(owners.first(), owners.last().transportOwnerKey)
        )
        assertEquals(
            SessionNotificationIdentity(
                owners.last().transportOwnerKey,
                requireNotNull(owners.last().media)
            ),
            gate.drain(owners.last(), owners.last().transportOwnerKey)
        )
        assertNull(
            "The final owner refresh is one-shot",
            gate.drain(owners.last(), owners.last().transportOwnerKey)
        )
    }

    @Test
    fun notificationRefreshTracksMetadataRevisionAndResetsAcrossEmptyOwner() {
        val gate = SessionNotificationRefreshGate()
        val ownerKey = "token\u0000song"
        val initial = MediaSessionSnapshot(
            media = SessionMediaDescription("song", "Initial", revision = "r1"),
            transportOwnerKey = ownerKey
        )
        val enriched = initial.copy(
            media = requireNotNull(initial.media).copy(title = "Enriched", revision = "r2")
        )

        assertTrue(gate.offer(initial))
        assertEquals(initial.media, gate.drain(initial, ownerKey)?.media)
        assertFalse("Identical media must not enqueue another repair", gate.offer(initial))

        assertTrue(gate.offer(enriched))
        assertEquals(enriched.media, gate.drain(enriched, ownerKey)?.media)

        assertFalse(gate.offer(MediaSessionSnapshot()))
        assertTrue("A new session may publish the same identity", gate.offer(enriched))
        assertEquals(enriched.media, gate.drain(enriched, ownerKey)?.media)
    }

    @Test
    fun notificationRefreshRejectsSupersededSameSongNativeOwner() {
        val gate = SessionNotificationRefreshGate()
        val media = SessionMediaDescription("same-song", "Same Song", revision = "same-r1")
        val oldOwner = MediaSessionSnapshot(
            media = media,
            transportOwnerKey = "token-41\u0000same-song"
        )
        val reloadedOwner = oldOwner.copy(transportOwnerKey = "token-42\u0000same-song")

        assertTrue(gate.offer(oldOwner))
        assertTrue(gate.offer(reloadedOwner))
        assertNull(
            gate.drain(
                currentCanonicalSnapshot = oldOwner,
                latestTransportOwnerKey = reloadedOwner.transportOwnerKey
            )
        )
        assertEquals(
            reloadedOwner.transportOwnerKey,
            gate.drain(
                currentCanonicalSnapshot = reloadedOwner,
                latestTransportOwnerKey = reloadedOwner.transportOwnerKey
            )?.transportOwnerKey
        )
    }

    @Test
    fun completeSnapshotMapsEverySessionFieldAndClampsPosition() {
        val artwork = SessionArtworkData(byteArrayOf(1, 2, 3, 4))
        val projection = MediaSessionSnapshotMapper.project(
            MediaSessionSnapshot(
                media = SessionMediaDescription(
                    id = "  track-7  ",
                    title = "   ",
                    artist = "  Example Artist  ",
                    revision = "  manifest-r7  ",
                    artworkData = artwork,
                    artworkUri = "  HTTPS://example.com/artwork/7.jpg  "
                ),
                durationMs = 120_000L,
                positionMs = 140_000L,
                playing = true,
                playWhenReady = true,
                repeatMode = SessionRepeatMode.ALL,
                shuffleEnabled = true,
                canGoNext = true,
                canGoPrevious = true,
                seekable = true
            )
        )

        assertEquals("track-7", projection.media?.id)
        assertEquals("track-7", projection.media?.title)
        assertEquals("Example Artist", projection.media?.artist)
        assertEquals("manifest-r7", projection.media?.revision)
        assertEquals(artwork, projection.media?.artworkData)
        assertNull(projection.artworkUri)
        assertEquals(120_000L, projection.durationMs)
        assertEquals(120_000L, projection.positionMs)
        assertEquals(SessionPlaybackPhase.READY, projection.playbackPhase)
        assertTrue(projection.playing)
        assertTrue(projection.playWhenReady)
        assertFalse(projection.loading)
        assertNull(projection.error)
        assertEquals(SessionPlaybackSuppression.NONE, projection.suppression)
        assertEquals(SessionRepeatMode.ALL, projection.repeatMode)
        assertTrue(projection.shuffleEnabled)
        assertTrue(projection.canGoNext)
        assertTrue(projection.canGoPrevious)
        assertTrue(projection.seekable)
    }

    @Test
    fun artworkPayloadIsContentEqualImmutableAndStrictlyBounded() {
        val source = byteArrayOf(5, 8, 13, 21)
        val first = SessionArtworkData(source)
        val equal = SessionArtworkData(source.copyOf())

        source[0] = 99
        val exposedCopy = first.bytes
        exposedCopy[1] = 99

        assertEquals(equal, first)
        assertEquals(equal.hashCode(), first.hashCode())
        assertEquals(4, first.size)
        assertTrue(first.bytes.contentEquals(byteArrayOf(5, 8, 13, 21)))
        assertThrows(IllegalArgumentException::class.java) {
            SessionArtworkData(ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionArtworkData(ByteArray(MAXIMUM_SESSION_ARTWORK_BYTES + 1))
        }
    }

    @Test
    fun playbackPhaseDistinguishesIdlePendingLoadingSuppressedReadyEndedAndError() {
        val media = SessionMediaDescription("id", "Title")

        val idle = MediaSessionSnapshotMapper.project(
            MediaSessionSnapshot(durationMs = 10_000L, positionMs = 4_000L)
        )
        assertEquals(SessionPlaybackPhase.IDLE, idle.playbackPhase)
        assertFalse(idle.loading)
        assertNull(idle.durationMs)
        assertEquals(0L, idle.positionMs)
        val startupLoadingWithoutMedia = MediaSessionSnapshotMapper.project(
            MediaSessionSnapshot(loading = true)
        )
        assertEquals(SessionPlaybackPhase.IDLE, startupLoadingWithoutMedia.playbackPhase)
        assertFalse(startupLoadingWithoutMedia.loading)
        assertEquals(
            SessionPlaybackPhase.BUFFERING,
            MediaSessionSnapshotMapper.project(
                MediaSessionSnapshot(media = media, playWhenReady = true)
            ).playbackPhase
        )
        assertEquals(
            SessionPlaybackPhase.BUFFERING,
            MediaSessionSnapshotMapper.project(
                MediaSessionSnapshot(media = media, loading = true)
            ).playbackPhase
        )
        val playingWhileLoading = MediaSessionSnapshotMapper.project(
            MediaSessionSnapshot(
                media = media,
                playing = true,
                playWhenReady = true,
                loading = true
            )
        )
        assertEquals(SessionPlaybackPhase.READY, playingWhileLoading.playbackPhase)
        assertTrue(playingWhileLoading.loading)
        assertEquals(
            SessionPlaybackPhase.READY,
            MediaSessionSnapshotMapper.project(
                MediaSessionSnapshot(
                    media = media,
                    playWhenReady = true,
                    suppression = SessionPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS
                )
            ).playbackPhase
        )
        assertEquals(
            SessionPlaybackPhase.READY,
            MediaSessionSnapshotMapper.project(MediaSessionSnapshot(media = media)).playbackPhase
        )
        val endedWhileLoading = MediaSessionSnapshotMapper.project(
            MediaSessionSnapshot(media = media, ended = true, loading = true)
        )
        assertEquals(SessionPlaybackPhase.ENDED, endedWhileLoading.playbackPhase)
        assertFalse(endedWhileLoading.loading)
        val erroredWhileLoading = MediaSessionSnapshotMapper.project(
            MediaSessionSnapshot(
                media = media,
                loading = true,
                error = SessionPlaybackError(message = "Decoder failed")
            )
        )
        assertEquals(SessionPlaybackPhase.IDLE, erroredWhileLoading.playbackPhase)
        assertFalse(erroredWhileLoading.loading)
    }

    @Test
    fun artworkAcceptsOnlyValidSupportedUris() {
        val accepted = listOf(
            "android.resource://app.stemwise/drawable/cover"
        )
        accepted.forEach { uri ->
            assertEquals(uri, MediaSessionSnapshotMapper.validArtworkUri(uri))
        }

        val rejected = listOf(
            "",
            "https://example.com/cover.jpg",
            "HTTP://example.com/cover.jpg",
            "C:\\covers\\cover.jpg",
            "/tmp/cover.jpg",
            "content://app.stemwise/private/cover.jpg",
            "file:///data/user/0/app.stemwise/cache/cover.jpg",
            "javascript:alert(1)",
            "https:///missing-host.jpg",
            "content:/missing-authority",
            ":not-a-uri"
        )
        rejected.forEach { uri ->
            assertNull(uri, MediaSessionSnapshotMapper.validArtworkUri(uri))
        }
    }

    @Test
    fun referenceCacheDoesNotRebuildArtworkWrapperForPositionOnlySnapshots() {
        val cache = ReferenceIdentityCache<Any, SessionArtworkData>()
        val unchangedMediaItem = Any()
        var artworkCopies = 0
        fun project(mediaItem: Any, positionMs: Long): SessionArtworkData =
            cache.getOrCreate(mediaItem) {
                artworkCopies += 1
                SessionArtworkData(byteArrayOf(1, 3, 5, 7))
            }.also { assertTrue(positionMs >= 0L) }

        val initial = project(unchangedMediaItem, 0L)
        repeat(10_000) { index ->
            assertTrue(initial === project(unchangedMediaItem, index.toLong()))
        }
        assertEquals(1, artworkCopies)

        val replacement = project(Any(), 10_001L)
        assertFalse(initial === replacement)
        assertEquals(2, artworkCopies)
    }

    @Test
    fun invalidPlayingAndIdentitySnapshotsFailAtTheBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionMediaDescription(id = "  ", title = "Title")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaSessionSnapshot(
                media = SessionMediaDescription("id", "Title"),
                playing = true,
                playWhenReady = false
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaSessionSnapshot(
                media = SessionMediaDescription("id", "Title"),
                playing = true,
                playWhenReady = true,
                ended = true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionPlaybackError(message = "  ")
        }
    }

    @Test
    fun snapshotPublicationConflatesBurstsAndSuppressesEqualInvalidations() {
        val initial = MediaSessionSnapshot(media = SessionMediaDescription("a", "A"))
        val publication = SessionSnapshotPublication(initial)

        publication.offer(initial.copy())
        assertNull(publication.drain())
        assertFalse(publication.hasPending())

        val intermediate = initial.copy(positionMs = 1_000L)
        val latest = initial.copy(positionMs = 2_000L)
        publication.offer(intermediate)
        publication.offer(latest)

        assertTrue(publication.hasPending())
        assertEquals(latest, publication.drain())
        assertEquals(latest, publication.current())
        assertFalse(publication.hasPending())
        assertNull(publication.drain())
    }
}
