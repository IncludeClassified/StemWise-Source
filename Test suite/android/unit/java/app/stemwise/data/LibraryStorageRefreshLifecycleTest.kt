package app.stemwise.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class LibraryStorageRefreshLifecycleTest {
    @get:Rule
    val testHangGuard: Timeout = Timeout.seconds(10L)

    @Test fun canonicalCountsPreserveMeasurementErrorsAndWinAgainstAnOlderMetadataFlight() = runBlocking {
        val initial = LibraryInventory(actualLibraryBytes = 200L, unknownSizeFiles = 2,
            unreadableDirectories = 3, appPrivateTotalBytes = 40L, lastCheckedEpochMs = 10L)
        val lifecycle = LibraryStorageRefreshLifecycle(initial)
        lifecycle.failRefreshWithCachedInventory(IllegalStateException("isolated measurement failure"))
        val unavailable = lifecycle.state.value
        val published = lifecycle.publishCanonicalCounts(CanonicalStorageTotals(2, 18, 150L))
        assertEquals(unavailable.copy(inventory = published), lifecycle.state.value)
        assertEquals(200L, published.actualLibraryBytes)
        assertEquals(40L, published.appPrivateTotalBytes)
        assertEquals(2, published.unknownSizeFiles)
        assertEquals(3, published.unreadableDirectories)
        assertEquals(10L, published.lastCheckedEpochMs)
        assertEquals(50L, published.temporaryOrUnclassifiedBytes)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val flight = async {
            lifecycle.refresh {
                entered.complete(Unit)
                release.await()
                published.copy(actualLibraryBytes = 500L, lastCheckedEpochMs = 20L)
            }
        }
        try {
            withTimeout(1_000L) { entered.await() }
            lifecycle.publishCanonicalCounts(CanonicalStorageTotals(3, 27, 350L))
            assertTrue(lifecycle.state.value.refreshing)
            assertEquals(10L, lifecycle.state.value.inventory.lastCheckedEpochMs)
            release.complete(Unit)
            val final = withTimeout(1_000L) { flight.await() }
            assertEquals(3, final.verifiedSongPackages)
            assertEquals(27, final.verifiedStemFiles)
            assertEquals(350L, final.verifiedTotalBytes)
            assertEquals(500L, final.actualLibraryBytes)
            assertEquals(20L, final.lastCheckedEpochMs)
            assertEquals(150L, final.temporaryOrUnclassifiedBytes)
        } finally {
            release.complete(Unit)
            flight.cancelAndJoin()
        }
    }

    @Test
    fun cancelledInvalidatedCanonicalOwnerReleasesJoinersAndAllowsTheNextScan() = runBlocking {
        val coordinator = LibraryStorageTraversalCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.canonical {
                entered.complete(Unit)
                release.await()
                coordinator.invalidateCanonical()
                kotlinx.coroutines.currentCoroutineContext().cancel()
                LibraryInventory(songsFound = 1)
            }
        }
        entered.await()
        val joined = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { coordinator.canonical { error("Joined scan must not run") } }
        }
        try {
            release.complete(Unit)
            withTimeout(1_000L) { owner.join() }
            assertTrue(owner.isCancelled)
            assertTrue(withTimeout(1_000L) { joined.await() }.exceptionOrNull() is CancellationException)
            val latest = LibraryInventory(songsFound = 2)
            assertEquals(latest, withTimeout(1_000L) { coordinator.canonical { latest } })
        } finally {
            owner.cancelAndJoin()
            joined.cancelAndJoin()
        }
    }

    @Test
    fun applicationOwnedRequestSurvivesTabNavigationAndRecompositionExactlyOnce() = runBlocking {
        val initial = LibraryInventory(lastCheckedEpochMs = 10L)
        val completed = LibraryInventory(
            songsFound = 400,
            originalFilesFound = 400,
            stemFilesFound = 2_400,
            artworkFilesFound = 400,
            jsonFilesFound = 400,
            actualLibraryBytes = 3_000_000_000L,
            temporaryOrUnclassifiedBytes = 3_000_000_000L,
            appPrivateTotalBytes = 198_000_000L,
            appDatabaseBytes = 18_000_000L,
            appWorkingBytes = 170_000_000L,
            appCacheBytes = 10_000_000L,
            lastCheckedEpochMs = 20L
        )
        val lifecycle = LibraryStorageRefreshLifecycle(initial)
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstSystemComposition = CoroutineScope(Job() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var scanCount = 0
        val publications = mutableListOf<LibraryStorageRefreshState>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.state.collect(publications::add)
        }
        val owner = LibraryStorageRefreshRequestOwner(applicationScope) {
            lifecycle.refresh {
                scanCount++
                entered.complete(Unit)
                kotlinx.coroutines.withTimeout(5_000L) { release.await() }
                completed
            }
        }

        try {
            // The first System composition only dispatches the request. Cancelling it models moving
            // to Library/Tasks while the provider cursor is still active.
            firstSystemComposition.launch { assertTrue(owner.request()) }.join()
            kotlinx.coroutines.withTimeout(5_000L) { entered.await() }
            firstSystemComposition.cancel()
            assertTrue(lifecycle.state.value.refreshing)

            // A newly composed System screen can dispatch again before observing the busy state. The
            // application owner must coalesce that race rather than queue a second full traversal.
            assertFalse(owner.request())
            release.complete(Unit)
            val terminal = withTimeout(1_000L) {
                lifecycle.state.first { state -> state.generation == 1L && !state.refreshing }
            }
            withTimeout(1_000L) {
                while (publications.none { state ->
                        !state.refreshing && state.inventory == completed
                    }
                ) {
                    yield()
                }
            }

            assertEquals(1, scanCount)
            assertEquals(completed, terminal.inventory)
            assertEquals(400, terminal.inventory.songsFound)
            assertEquals(2_400, terminal.inventory.stemFilesFound)
            assertEquals(3_000_000_000L, terminal.inventory.actualLibraryBytes)
            assertEquals(198_000_000L, terminal.inventory.appPrivateTotalBytes)
            assertNull(terminal.errorMessage)
            assertEquals(
                1,
                publications.count { state -> !state.refreshing && state.inventory == completed }
            )
        } finally {
            release.complete(Unit)
            observer.cancelAndJoin()
            firstSystemComposition.cancel()
            applicationScope.cancel()
        }
    }

    @Test
    fun applicationOwnedRequestStillPublishesRealProviderFailureAndEndsProgress() = runBlocking {
        val cached = LibraryInventory(
            songsFound = 49,
            actualLibraryBytes = 3_000_000_000L,
            lastCheckedEpochMs = 123_456L
        )
        val lifecycle = LibraryStorageRefreshLifecycle(cached)
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = LibraryStorageRefreshRequestOwner(applicationScope) {
            lifecycle.refresh { throw IllegalStateException("provider cursor failed") }
        }

        try {
            assertTrue(owner.request())
            val terminal = lifecycle.state.value

            assertFalse(terminal.refreshing)
            assertEquals(cached, terminal.inventory)
            assertEquals("provider cursor failed", terminal.errorMessage)
            assertEquals(LibraryInventoryAvailability.UNAVAILABLE, terminal.availability)
        } finally {
            applicationScope.cancel()
        }
    }

    @Test
    fun failedLaterRefreshPreservesVerifiedCachedInventory() = runBlocking {
        val verified = LibraryInventory(songsFound = 49, lastCheckedEpochMs = 123_456L)
        val lifecycle = LibraryStorageRefreshLifecycle()
        lifecycle.replaceInventory(verified)

        val failure = runCatching {
            lifecycle.refresh { error("provider cursor failed") }
        }.exceptionOrNull()

        assertEquals("provider cursor failed", failure?.message)
        assertEquals(verified, lifecycle.state.value.inventory)
        assertEquals(LibraryInventoryAvailability.VERIFIED, lifecycle.state.value.availability)
        assertEquals("provider cursor failed", lifecycle.state.value.errorMessage)
    }

    @Test
    fun rootLossCancelsActualProviderOwnerAndReopensApplicationRequestAdmission() = runBlocking {
        val cached = LibraryInventory(songsFound = 49, lastCheckedEpochMs = 10L)
        val completed = cached.copy(songsFound = 50, lastCheckedEpochMs = 20L)
        val lifecycle = LibraryStorageRefreshLifecycle(cached)
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val providerEntered = CompletableDeferred<Unit>()
        val providerCancellationSignal = CompletableDeferred<Unit>()
        var attempts = 0
        val owner = LibraryStorageRefreshRequestOwner(applicationScope) {
            lifecycle.refresh {
                attempts++
                if (attempts == 1) {
                    providerEntered.complete(Unit)
                    try {
                        // Models the cancellable DocumentsProvider cursor used by the repository.
                        awaitCancellation()
                    } finally {
                        providerCancellationSignal.complete(Unit)
                    }
                }
                completed
            }
        }

        try {
            assertTrue(owner.request())
            kotlinx.coroutines.withTimeout(5_000L) { providerEntered.await() }
            lifecycle.failWithCachedInventory(
                LibraryRootException("ROOT_PERMISSION_LOST", "Reconnect the Library folder")
            )
            withTimeout(1_000L) { kotlinx.coroutines.withTimeout(5_000L) { providerCancellationSignal.await() } }
            assertFalse(lifecycle.state.value.refreshing)
            assertEquals(cached, lifecycle.state.value.inventory)
            assertEquals("Reconnect the Library folder", lifecycle.state.value.errorMessage)

            // Completion of the cancelled provider child must finish the repository-owned request and
            // reopen its atomic gate; a reconnect must never be blocked behind abandoned old work.
            withTimeout(1_000L) {
                while (!owner.request()) yield()
            }
            val terminal = withTimeout(1_000L) {
                lifecycle.state.first { state ->
                    !state.refreshing && state.inventory == completed && state.errorMessage == null
                }
            }
            assertEquals(2, attempts)
            assertEquals(completed, terminal.inventory)
        } finally {
            applicationScope.cancel()
        }
    }

    @Test
    fun concurrentAutomaticAndManualCallersShareOneScanAndOneAtomicCompletion() = runBlocking {
        val initial = LibraryInventory(lastCheckedEpochMs = 10L)
        val completed = LibraryInventory(
            songsFound = 400,
            verifiedSongPackages = 400,
            lastCheckedEpochMs = 20L
        )
        val lifecycle = LibraryStorageRefreshLifecycle(initial)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var scanCount = 0
        val observed = mutableListOf<LibraryStorageRefreshState>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.state.take(3).toList(observed)
        }

        val automatic = async {
            lifecycle.refresh {
                scanCount++
                entered.complete(Unit)
                kotlinx.coroutines.withTimeout(5_000L) { release.await() }
                completed
            }
        }
        kotlinx.coroutines.withTimeout(5_000L) { entered.await() }
        yield()
        assertTrue(lifecycle.state.value.refreshing)
        assertEquals(initial, lifecycle.state.value.inventory)

        val manual = async {
            lifecycle.refresh {
                scanCount++
                error("duplicate scan must not start")
            }
        }
        yield()
        release.complete(Unit)

        assertEquals(completed, kotlinx.coroutines.withTimeout(5_000L) { automatic.await() })
        assertEquals(completed, kotlinx.coroutines.withTimeout(5_000L) { manual.await() })
        assertEquals(1, scanCount)
        assertEquals(completed, lifecycle.state.value.inventory)
        assertFalse(lifecycle.state.value.refreshing)
        assertNull(lifecycle.state.value.errorMessage)
        kotlinx.coroutines.withTimeout(5_000L) { observer.join() }
        assertEquals(listOf(false, true, false), observed.map { it.refreshing })
        assertFalse(
            observed.any { state ->
                state.refreshing && state.inventory.lastCheckedEpochMs == completed.lastCheckedEpochMs
            }
        )
    }

    @Test
    fun joinedCanonicalRequestsOnlyReceiveTheNewestInvalidationGeneration() = runBlocking {
        val coordinator = LibraryStorageTraversalCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val verified = mutableListOf<Long>()
        var traversals = 0
        val owner = async {
            coordinator.canonical { generation ->
                traversals++
                if (entered.complete(Unit)) withTimeout(2_000L) { release.await() }
                coordinator.publishCurrent(generation) { verified += generation }
                LibraryInventory(verifiedSongPackages = 2, lastCheckedEpochMs = generation + 1)
            }
        }
        try {
            withTimeout(2_000L) { entered.await() }
            coordinator.invalidateCanonical()
            val joined = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.canonical { error("Joining caller ran a second concurrent traversal") }
            }
            release.complete(Unit)
            assertEquals(owner.await(), joined.await())
            assertEquals(2, traversals)
            assertEquals(listOf(1L), verified)
        } finally { release.complete(Unit); owner.cancelAndJoin() }
    }

    @Test
    fun repositoryManualRouteJoinsBlockedCanonicalTraversalWithoutSecondProviderScan() = runBlocking {
        val coordinator = LibraryStorageTraversalCoordinator()
        val dashboardLifecycle = LibraryStorageRefreshLifecycle()
        val canonicalInventory = LibraryInventory(
            songsFound = 400,
            verifiedSongPackages = 400,
            actualLibraryBytes = 3_000_000_000L,
            lastCheckedEpochMs = 20L
        )
        val canonicalEntered = CompletableDeferred<Unit>()
        val releaseCanonicalProvider = CompletableDeferred<Unit>()
        var providerTraversals = 0
        var metadataTraversals = 0
        var mirroredCanonicalFlights = 0

        val canonical = async {
            coordinator.canonical {
                providerTraversals++
                canonicalEntered.complete(Unit)
                kotlinx.coroutines.withTimeout(5_000L) { releaseCanonicalProvider.await() }
                canonicalInventory
            }
        }
        kotlinx.coroutines.withTimeout(5_000L) { canonicalEntered.await() }
        val manual = async {
            coordinator.manual(
                joinCanonical = { awaitCanonical ->
                    mirroredCanonicalFlights++
                    dashboardLifecycle.refresh { awaitCanonical() }
                },
                metadataOnly = {
                    metadataTraversals++
                    error("manual metadata traversal must not run behind canonical verification")
                }
            )
        }
        yield()
        assertTrue(dashboardLifecycle.state.value.refreshing)
        assertFalse(manual.isCompleted)

        releaseCanonicalProvider.complete(Unit)
        assertEquals(canonicalInventory, kotlinx.coroutines.withTimeout(5_000L) { canonical.await() })
        assertEquals(canonicalInventory, kotlinx.coroutines.withTimeout(5_000L) { manual.await() })
        assertEquals(1, providerTraversals)
        assertEquals(0, metadataTraversals)
        assertEquals(1, mirroredCanonicalFlights)
        assertEquals(canonicalInventory, dashboardLifecycle.state.value.inventory)
        assertFalse(dashboardLifecycle.state.value.refreshing)
    }

    @Test
    fun repositoryCanonicalRouteAfterMetadataAdmissionStillRunsRicherVerification() = runBlocking {
        val coordinator = LibraryStorageTraversalCoordinator()
        val metadataInventory = LibraryInventory(songsFound = 400, lastCheckedEpochMs = 10L)
        val canonicalInventory = metadataInventory.copy(
            verifiedSongPackages = 400,
            verifiedStemFiles = 2_400,
            lastCheckedEpochMs = 20L
        )
        val metadataEntered = CompletableDeferred<Unit>()
        val releaseMetadataProvider = CompletableDeferred<Unit>()
        var metadataTraversals = 0
        var canonicalTraversals = 0
        var incorrectlyJoinedCanonical = 0

        val manual = async {
            coordinator.manual(
                joinCanonical = {
                    incorrectlyJoinedCanonical++
                    it()
                },
                metadataOnly = {
                    metadataTraversals++
                    metadataEntered.complete(Unit)
                    kotlinx.coroutines.withTimeout(5_000L) { releaseMetadataProvider.await() }
                    metadataInventory
                }
            )
        }
        kotlinx.coroutines.withTimeout(5_000L) { metadataEntered.await() }
        val canonical = async {
            coordinator.canonical {
                canonicalTraversals++
                canonicalInventory
            }
        }

        assertEquals(canonicalInventory, kotlinx.coroutines.withTimeout(5_000L) { canonical.await() })
        assertEquals(1, canonicalTraversals)
        assertEquals(0, incorrectlyJoinedCanonical)
        releaseMetadataProvider.complete(Unit)
        assertEquals(metadataInventory, kotlinx.coroutines.withTimeout(5_000L) { manual.await() })
        assertEquals(1, metadataTraversals)
    }

    @Test
    fun canonicalReplacementCannotPublishUpdatedTimestampUnderManualBusyIndicator() = runBlocking {
        val initial = LibraryInventory(
            verifiedSongPackages = 49,
            lastCheckedEpochMs = 10L
        )
        val canonical = initial.copy(
            verifiedSongPackages = 50,
            pcEnrichedSongManifests = 3,
            lastCheckedEpochMs = 15L
        )
        val measured = canonical.copy(
            songsFound = 50,
            actualLibraryBytes = 3_000_000_000L,
            temporaryOrUnclassifiedBytes = 3_000_000_000L,
            appPrivateTotalBytes = 198_000_000L,
            lastCheckedEpochMs = 20L
        )
        val lifecycle = LibraryStorageRefreshLifecycle(initial)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            lifecycle.refresh {
                entered.complete(Unit)
                kotlinx.coroutines.withTimeout(5_000L) { release.await() }
                measured
            }
        }

        kotlinx.coroutines.withTimeout(5_000L) { entered.await() }
        lifecycle.replaceInventory(canonical)
        val busy = lifecycle.state.value
        assertTrue(busy.refreshing)
        assertEquals(50, busy.inventory.verifiedSongPackages)
        assertEquals(3, busy.inventory.pcEnrichedSongManifests)
        assertEquals(
            "a newer completed timestamp must never appear while this flight is busy",
            10L,
            busy.inventory.lastCheckedEpochMs
        )

        release.complete(Unit)
        assertEquals(measured, kotlinx.coroutines.withTimeout(5_000L) { owner.await() })
        assertEquals(measured, lifecycle.state.value.inventory)
        assertFalse(lifecycle.state.value.refreshing)
        assertEquals(20L, lifecycle.state.value.inventory.lastCheckedEpochMs)
    }

    @Test fun delayedMetadataScanPreservesCanonicalDeletionTotalsAtPublication() = runBlocking {
        val before = LibraryInventory(verifiedSongPackages = 1, verifiedOriginalFiles = 1,
            verifiedStemFiles = 4, verifiedJsonFiles = 1, verifiedArtworkFiles = 1,
            pcEnrichedSongManifests = 1, verifiedTotalBytes = 100L)
        val lifecycle = LibraryStorageRefreshLifecycle(before)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scan = async {
            lifecycle.refresh {
                entered.complete(Unit)
                withTimeout(5_000L) { release.await() }
                before.copy(songsFound = 1, actualLibraryBytes = 150L, lastCheckedEpochMs = 20L)
            }
        }
        try {
            withTimeout(5_000L) { entered.await() }
            lifecycle.markInventoryDirty()
            lifecycle.replaceInventory(LibraryInventory(snapshotDirty = true))
            release.complete(Unit)
            val published = withTimeout(5_000L) { scan.await() }
            assertEquals(0, published.verifiedSongPackages)
            assertEquals(0, published.verifiedOriginalFiles)
            assertEquals(0, published.verifiedStemFiles)
            assertEquals(0, published.verifiedJsonFiles)
            assertEquals(0, published.verifiedArtworkFiles)
            assertEquals(0, published.pcEnrichedSongManifests)
            assertEquals(0L, published.verifiedTotalBytes)
            assertEquals(150L, published.temporaryOrUnclassifiedBytes)
            assertEquals(20L, published.lastCheckedEpochMs)
            assertTrue(published.snapshotDirty)
            assertEquals(published, lifecycle.state.value.inventory)
            assertEquals(published, lifecycle.refresh { published })
        } finally {
            release.complete(Unit)
            scan.cancelAndJoin()
        }
    }

    @Test
    fun failureEndsTheSameBusyGenerationWithoutPublishingAFalseUpdate() = runBlocking {
        val initial = LibraryInventory(lastCheckedEpochMs = 10L)
        val lifecycle = LibraryStorageRefreshLifecycle(initial)

        val failure = runCatching {
            lifecycle.refresh { throw IllegalStateException("provider unavailable") }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(initial, lifecycle.state.value.inventory)
        assertFalse(lifecycle.state.value.refreshing)
        assertEquals("provider unavailable", lifecycle.state.value.errorMessage)
    }

    @Test
    fun unavailableRootRetainsExactCachedCountsOwnershipAndVerifiedTimestamp() = runBlocking {
        val cached = LibraryInventory(
            songsFound = 49,
            stemFilesFound = 294,
            verifiedSongPackages = 49,
            verifiedStemFiles = 294,
            pcEnrichedSongManifests = 0,
            actualLibraryBytes = 3_000_000_000L,
            temporaryOrUnclassifiedBytes = 3_000_000_000L,
            appPrivateTotalBytes = 198_000_000L,
            appDatabaseBytes = 18_000_000L,
            appWorkingBytes = 170_000_000L,
            appCacheBytes = 10_000_000L,
            lastCheckedEpochMs = 123_456L
        )
        val lifecycle = LibraryStorageRefreshLifecycle(cached)

        val retained = lifecycle.failWithCachedInventory(
            LibraryRootException("ROOT_UNAVAILABLE", "Reconnect the Library folder")
        )

        assertEquals(cached, retained)
        assertEquals(cached, lifecycle.state.value.inventory)
        assertEquals(0, lifecycle.state.value.inventory.pcEnrichedSongManifests)
        assertEquals(3_000_000_000L, lifecycle.state.value.inventory.actualLibraryBytes)
        assertEquals(198_000_000L, lifecycle.state.value.inventory.appPrivateTotalBytes)
        assertEquals(123_456L, lifecycle.state.value.inventory.lastCheckedEpochMs)
        assertFalse(lifecycle.state.value.refreshing)
        assertEquals("Reconnect the Library folder", lifecycle.state.value.errorMessage)
    }

    @Test
    fun unavailableRootTerminatesActiveFlightAndRejectsItsLateSuccess() = runBlocking {
        val cached = LibraryInventory(
            songsFound = 49,
            lastCheckedEpochMs = 10L
        )
        val falseFresh = cached.copy(
            songsFound = 50,
            lastCheckedEpochMs = 20L
        )
        val lifecycle = LibraryStorageRefreshLifecycle(cached)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            runCatching {
                lifecycle.refresh {
                    entered.complete(Unit)
                    kotlinx.coroutines.withTimeout(5_000L) { release.await() }
                    falseFresh
                }
            }
        }
        kotlinx.coroutines.withTimeout(5_000L) { entered.await() }
        assertTrue(lifecycle.state.value.refreshing)

        lifecycle.failWithCachedInventory(
            LibraryRootException("ROOT_PERMISSION_LOST", "Reconnect the Library folder")
        )
        assertFalse(lifecycle.state.value.refreshing)
        assertEquals(cached, lifecycle.state.value.inventory)

        release.complete(Unit)
        val outcome = kotlinx.coroutines.withTimeout(5_000L) { owner.await() }
        assertTrue(outcome.isFailure)
        assertEquals("Reconnect the Library folder", outcome.exceptionOrNull()?.message)
        assertEquals(cached, lifecycle.state.value.inventory)
        assertEquals(10L, lifecycle.state.value.inventory.lastCheckedEpochMs)
        assertFalse(lifecycle.state.value.refreshing)
    }

    @Test
    fun successBeforeRootLossIsOneAtomicTerminalSnapshot() = runBlocking {
        val cached = LibraryInventory(songsFound = 49, lastCheckedEpochMs = 10L)
        val verified = cached.copy(songsFound = 50, lastCheckedEpochMs = 20L)
        val lifecycle = LibraryStorageRefreshLifecycle(cached)

        assertEquals(verified, lifecycle.refresh { verified })
        lifecycle.failWithCachedInventory(
            LibraryRootException("ROOT_PERMISSION_LOST", "Reconnect the Library folder")
        )

        val terminal = lifecycle.state.value
        assertEquals(
            "a result terminalized before invalidation is the exact last verified cache",
            verified,
            terminal.inventory
        )
        assertEquals(20L, terminal.inventory.lastCheckedEpochMs)
        assertFalse(terminal.refreshing)
        assertEquals("Reconnect the Library folder", terminal.errorMessage)
    }

    @Test
    fun dirtyMutationDuringRefreshCannotBeClearedByLateCleanScan() = runBlocking {
        val initial = LibraryInventory(songsFound = 49, lastCheckedEpochMs = 10L)
        val measured = initial.copy(songsFound = 50, lastCheckedEpochMs = 20L)
        val lifecycle = LibraryStorageRefreshLifecycle(initial)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            lifecycle.refresh {
                entered.complete(Unit)
                kotlinx.coroutines.withTimeout(5_000L) { release.await() }
                measured
            }
        }

        kotlinx.coroutines.withTimeout(5_000L) { entered.await() }
        assertTrue(lifecycle.markInventoryDirty().snapshotDirty)
        release.complete(Unit)

        val published = kotlinx.coroutines.withTimeout(5_000L) { owner.await() }
        assertEquals(measured.copy(snapshotDirty = true), published)
        assertEquals(published, lifecycle.state.value.inventory)
        assertEquals(LibraryInventoryAvailability.VERIFIED, lifecycle.state.value.availability)
    }

    @Test
    fun restorationDetachesActiveRefreshAndRejectsItsOldRootResult() = runBlocking {
        val cached = LibraryInventory(songsFound = 49, lastCheckedEpochMs = 10L)
        val restored = LibraryInventory()
        val lifecycle = LibraryStorageRefreshLifecycle(cached)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val owner = async {
            runCatching {
                lifecycle.refresh {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
        }

        kotlinx.coroutines.withTimeout(5_000L) { entered.await() }
        lifecycle.beginRestoration(restored)
        withTimeout(1_000L) { kotlinx.coroutines.withTimeout(5_000L) { cancelled.await() } }

        assertTrue(kotlinx.coroutines.withTimeout(5_000L) { owner.await() }.exceptionOrNull() is CancellationException)
        assertEquals(restored, lifecycle.state.value.inventory)
        assertFalse(lifecycle.state.value.refreshing)
        assertNull(lifecycle.state.value.errorMessage)
        assertEquals(LibraryInventoryAvailability.RESTORING, lifecycle.state.value.availability)
    }

    @Test
    fun cancelledCurrentRefreshStopsWorkAndRetainsUnverifiedCachedState() = runBlocking {
        val cached = LibraryInventory(
            songsFound = 49,
            stemFilesFound = 294,
            pcEnrichedSongManifests = 0,
            actualLibraryBytes = 3_000_000_000L,
            temporaryOrUnclassifiedBytes = 3_000_000_000L,
            appPrivateTotalBytes = 198_000_000L,
            lastCheckedEpochMs = 123_456L
        )
        val lifecycle = LibraryStorageRefreshLifecycle(cached)
        val cancelled = CompletableDeferred<Unit>()

        val failure = runCatching {
            withTimeout(40L) { lifecycle.refresh {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            } }
        }.exceptionOrNull()

        kotlinx.coroutines.withTimeout(5_000L) { cancelled.await() }
        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(cached, lifecycle.state.value.inventory)
        assertEquals(123_456L, lifecycle.state.value.inventory.lastCheckedEpochMs)
        assertFalse(lifecycle.state.value.refreshing)
        assertNull(lifecycle.state.value.errorMessage)
        assertEquals(
            LibraryInventoryAvailability.RESTORING,
            lifecycle.state.value.availability
        )
        assertEquals(cached, lifecycle.refresh { cached })
        assertEquals(LibraryInventoryAvailability.VERIFIED, lifecycle.state.value.availability)
    }

    @Test
    fun cancelledCurrentRefreshKeepsPreviouslyVerifiedCacheAvailable() = runBlocking {
        val verified = LibraryInventory(
            songsFound = 49,
            lastCheckedEpochMs = 123_456L
        )
        val lifecycle = LibraryStorageRefreshLifecycle()
        lifecycle.replaceInventory(verified)

        val failure = runCatching {
            withTimeout(40L) { lifecycle.refresh { awaitCancellation() } }
        }.exceptionOrNull()

        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(verified, lifecycle.state.value.inventory)
        assertEquals(
            LibraryInventoryAvailability.VERIFIED,
            lifecycle.state.value.availability
        )
        assertNull(lifecycle.state.value.errorMessage)
    }

}
