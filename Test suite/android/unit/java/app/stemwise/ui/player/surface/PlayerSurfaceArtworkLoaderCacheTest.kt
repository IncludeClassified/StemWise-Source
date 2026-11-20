package app.stemwise.ui.player.surface

import app.stemwise.artwork.artworkMemoryCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfaceArtworkLoaderCacheTest {
    @Test
    fun currentArtworkPreviewReusesTheLibraryThumbnailMemoryOwner() {
        val request = request()
        assertEquals(
            artworkMemoryCacheKey(
                request.owner.songId,
                request.owner.mediaRevision,
                request.source
            ),
            playerSurfaceArtworkPreviewMemoryCacheKey(request)
        )
        assertEquals(
            playerSurfaceArtworkPreviewMemoryCacheKey(request),
            playerSurfaceArtworkPreviewMemoryCacheKey(
                request.copy(owner = request.owner.copy(generation = 900L))
            )
        )
        assertNotEquals(
            playerSurfaceArtworkPreviewMemoryCacheKey(request),
            playerSurfaceArtworkPreviewMemoryCacheKey(
                request.copy(owner = request.owner.copy(mediaRevision = "media-r2"))
            )
        )
    }

    @Test
    fun coverDecodeTargetDependsOnSurfaceNotAnimatedArtworkRectangle() {
        assertEquals(1008, playerSurfaceArtworkDecodeSizePx(1080, density = 3f))
        assertEquals(48, playerSurfaceArtworkDecodeSizePx(40, density = 3f))
        // Re-evaluating the same route frame cannot mint a new Coil size key.
        repeat(400) {
            assertEquals(1008, playerSurfaceArtworkDecodeSizePx(1080, density = 3f))
        }
    }

    @Test
    fun accessOrderPreservesHotVisibleEntries() {
        val cache = cache(maxEntries = 3, maxWeight = 30)
        cache.put("lower", "111")
        cache.put("current", "2222")
        cache.put("higher", "33333")

        assertEquals("111", cache["lower"])
        cache.put("predictive", "444444")

        assertNull(cache["current"])
        assertEquals("111", cache["lower"])
        assertEquals("33333", cache["higher"])
        assertEquals("444444", cache["predictive"])
        assertEquals(3, cache.entryCount)
    }

    @Test
    fun weightBoundEvictsAsManyEldestEntriesAsNeeded() {
        val cache = cache(maxEntries = 5, maxWeight = 10)
        cache.put("a", "1111")
        cache.put("b", "2222")
        cache.put("c", "333333")

        assertNull(cache["a"])
        assertEquals("2222", cache["b"])
        assertEquals("333333", cache["c"])
        assertEquals(10L, cache.currentWeight)
    }

    @Test
    fun replacementAndOversizedRejectionKeepWeightExact() {
        val cache = cache(maxEntries = 2, maxWeight = 5)
        cache.put("art", "12")
        cache.put("art", "1234")

        assertEquals(4L, cache.currentWeight)
        assertEquals(1, cache.entryCount)

        cache.put("art", "123456")

        assertNull(cache["art"])
        assertEquals(0L, cache.currentWeight)
        assertEquals(0, cache.entryCount)
    }

    @Test
    fun fourHundredIdenticalInflightRequestsStayUndeliveredAndTransferAuthority() {
        val request = request()
        val binding = binding(request, authority = 0)

        repeat(400) { index ->
            val state = binding.updateAuthorityIfIdentical(request, index + 1)
            assertEquals(
                PlayerSurfaceArtworkRequestBinding.IdenticalRequestState.UNDELIVERED,
                state
            )
            assertEquals(index + 1, binding.authority)
        }

        assertEquals(400, binding.authority)
        assertTrue(binding.recordSuccessfulDelivery(FakeBitmap(live = true)))
        // A completion is routed only to the latest authority, never any of the 399 superseded
        // callback owners.
        assertEquals(400, binding.authority)
    }

    @Test
    fun fourHundredIdenticalDeliveredRequestsDoNotProbeOrDecodeAgain() {
        val request = request()
        val binding = binding(request, authority = 0)
        assertTrue(binding.recordSuccessfulDelivery(FakeBitmap(live = true)))
        var cacheProbeCount = 0
        var scheduledDecodeCount = 1

        repeat(400) { index ->
            when (binding.updateAuthorityIfIdentical(request, index + 1)) {
                PlayerSurfaceArtworkRequestBinding.IdenticalRequestState.DELIVERED -> Unit
                else -> {
                    cacheProbeCount++
                    scheduledDecodeCount++
                }
            }
        }

        assertEquals(0, cacheProbeCount)
        assertEquals(1, scheduledDecodeCount)
        assertEquals(400, binding.authority)
    }

    @Test
    fun failedOrRecycledDeliveryCanRetryTheExactRequest() {
        val request = request()
        val failed = binding(request, authority = 1)
        assertFalse(failed.recordSuccessfulDelivery(FakeBitmap(live = false)))
        assertEquals(
            PlayerSurfaceArtworkRequestBinding.IdenticalRequestState.UNDELIVERED,
            failed.updateAuthorityIfIdentical(request, replacementAuthority = 2)
        )

        val recycled = FakeBitmap(live = true)
        val delivered = binding(request, authority = 3)
        assertTrue(delivered.recordSuccessfulDelivery(recycled))
        recycled.live = false
        assertEquals(
            PlayerSurfaceArtworkRequestBinding.IdenticalRequestState.UNDELIVERED,
            delivered.updateAuthorityIfIdentical(request, replacementAuthority = 4)
        )
    }

    @Test
    fun sourceRevisionGenerationAndDimensionsAlwaysRequireNewBindingQualification() {
        val original = request()
        val binding = binding(original, authority = 1)
        val changedRequests = listOf(
            original.copy(source = "/music/other-cover.jpg"),
            original.copy(owner = original.owner.copy(mediaRevision = "media-r2")),
            original.copy(owner = original.owner.copy(generation = 43L)),
            original.copy(owner = original.owner.copy(audioRevision = "audio-r2")),
            original.copy(owner = original.owner.copy(stemRevision = "stems-r2")),
            original.copy(widthPx = original.widthPx + 1),
            original.copy(heightPx = original.heightPx + 1)
        )

        changedRequests.forEachIndexed { index, changed ->
            assertNull(binding.updateAuthorityIfIdentical(changed, index + 2))
        }
    }

    @Test
    fun revokedBindingRejectsLateCompletionAndCannotRegainAuthority() {
        val request = request()
        val stale = binding(request, authority = 1)
        stale.revoke()

        assertNull(stale.updateAuthorityIfIdentical(request, replacementAuthority = 2))
        assertFalse(stale.recordSuccessfulDelivery(FakeBitmap(live = true)))

        val current = binding(request.copy(owner = request.owner.copy(generation = 43L)), 3)
        assertTrue(current.recordSuccessfulDelivery(FakeBitmap(live = true)))
        assertEquals(3, current.authority)
    }

    @Test
    fun fourHundredSuccessfulPredictiveSetsDoNotRestartWorkButBoundaryChangeDoes() {
        val ledger = PlayerSurfacePredictiveWorkLedger<String>()
        assertEquals(linkedSetOf("lower", "higher"), ledger.replace(listOf("lower", "higher")))
        ledger.recordSuccessful("lower")
        ledger.recordSuccessful("higher")

        repeat(400) {
            assertTrue(ledger.replace(listOf("higher", "lower")).isEmpty())
        }

        assertEquals(linkedSetOf("resized-higher"), ledger.replace(
            listOf("lower", "resized-higher")
        ))
    }

    @Test
    fun failedPredictiveDecodeRemainsRetryableWithoutRestartingSuccessfulPeer() {
        val ledger = PlayerSurfacePredictiveWorkLedger<String>()
        assertEquals(linkedSetOf("lower", "higher"), ledger.replace(listOf("lower", "higher")))
        ledger.recordSuccessful("lower")

        repeat(3) {
            assertEquals(linkedSetOf("higher"), ledger.replace(listOf("higher", "lower")))
        }
        ledger.recordSuccessful("higher")
        assertTrue(ledger.replace(listOf("lower", "higher")).isEmpty())
    }

    @Test
    fun revisionedArtworkSharesPhysicalCacheScopeAcrossOwnerGenerations() {
        val revisioned = request().owner
        assertNull(playerSurfaceUnversionedArtworkOwner(revisioned))
        assertNull(
            playerSurfaceUnversionedArtworkOwner(
                revisioned.copy(generation = revisioned.generation + 1L, songId = "other-owner")
            )
        )

        val unversioned = revisioned.copy(mediaRevision = "")
        assertEquals(
            PlayerSurfaceUnversionedArtworkOwner(unversioned.songId, unversioned.generation),
            playerSurfaceUnversionedArtworkOwner(unversioned)
        )
        assertEquals(
            PlayerSurfaceUnversionedArtworkOwner(unversioned.songId, unversioned.generation + 1L),
            playerSurfaceUnversionedArtworkOwner(
                unversioned.copy(generation = unversioned.generation + 1L)
            )
        )
    }

    @Test
    fun latestCurrentPreemptsEveryOlderCurrentWithoutDemotingOffscreenWork() {
        val latest = "song-399"
        repeat(399) { index ->
            assertTrue(
                playerSurfaceArtworkTaskIsSupersededCurrent(
                    activeLane = PlayerSurfaceArtworkDecodeLane.CURRENT,
                    activeKey = "song-$index",
                    requestedCurrentKey = latest
                )
            )
        }
        assertFalse(
            playerSurfaceArtworkTaskIsSupersededCurrent(
                activeLane = PlayerSurfaceArtworkDecodeLane.CURRENT,
                activeKey = latest,
                requestedCurrentKey = latest
            )
        )
        assertFalse(
            playerSurfaceArtworkTaskIsSupersededCurrent(
                activeLane = PlayerSurfaceArtworkDecodeLane.OFFSCREEN,
                activeKey = "song-0",
                requestedCurrentKey = latest
            )
        )
        assertTrue(
            playerSurfaceArtworkTaskIsSupersededCurrent(
                activeLane = PlayerSurfaceArtworkDecodeLane.CURRENT,
                activeKey = "song-0",
                requestedCurrentKey = null
            )
        )
    }

    @Test
    fun onlySameKeyOffscreenWorkPromotesIntoTheCurrentLane() {
        assertTrue(
            playerSurfaceArtworkTaskNeedsPromotion(
                activeLane = PlayerSurfaceArtworkDecodeLane.OFFSCREEN,
                requestedLane = PlayerSurfaceArtworkDecodeLane.CURRENT
            )
        )
        assertFalse(
            playerSurfaceArtworkTaskNeedsPromotion(
                activeLane = PlayerSurfaceArtworkDecodeLane.CURRENT,
                requestedLane = PlayerSurfaceArtworkDecodeLane.CURRENT
            )
        )
        assertFalse(
            playerSurfaceArtworkTaskNeedsPromotion(
                activeLane = PlayerSurfaceArtworkDecodeLane.OFFSCREEN,
                requestedLane = PlayerSurfaceArtworkDecodeLane.OFFSCREEN
            )
        )
    }

    private data class FakeBitmap(var live: Boolean)

    private fun binding(
        request: PlayerSurfaceArtworkRequest,
        authority: Int
    ): PlayerSurfaceArtworkRequestBinding<PlayerSurfaceArtworkRequest, Int, FakeBitmap> =
        PlayerSurfaceArtworkRequestBinding(
            request = request,
            initialAuthority = authority,
            isLive = FakeBitmap::live
        )

    private fun request(): PlayerSurfaceArtworkRequest = PlayerSurfaceArtworkRequest(
        owner = PlayerSurfaceOwnerKey(
            generation = 42L,
            songId = "song",
            mediaRevision = "media-r1",
            audioRevision = "audio-r1",
            stemRevision = "stems-r1"
        ),
        source = "/music/cover.jpg",
        widthPx = 1008,
        heightPx = 1008
    )

    private fun cache(
        maxEntries: Int,
        maxWeight: Long
    ): BoundedPlayerSurfaceLru<String, String> = BoundedPlayerSurfaceLru(
        maxEntries = maxEntries,
        maxWeight = maxWeight,
        weightOf = { it.length.toLong() }
    )
}
