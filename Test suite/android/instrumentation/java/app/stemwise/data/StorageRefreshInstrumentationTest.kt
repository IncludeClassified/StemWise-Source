package app.stemwise.data

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Base64
import android.media.MediaExtractor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.enrichment.InstalledMediaIdentity
import app.stemwise.enrichment.createInstalledEnrichmentDocument
import app.stemwise.enrichment.GoldenSongMediaFixture
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production-repository, direct-layout performance gate for the System storage refresh. */
@RunWith(AndroidJUnit4::class)
class StorageRefreshInstrumentationTest {
    private val repositoryOwners = mutableListOf<Job>()
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun physicalCountsKeepUnpairedArtworkMetadataPartialStemsAndDuplicateOriginals() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedStorageContext(targetContext)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            grantProviderTree()
            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val directory = File(targetContext.noBackupFilesDir, "golden-enrichment-document-provider")
            val packages = listOf(
                "JSONONLY001" to listOf(SelectedLibraryContract.ENRICHMENT),
                "COVERONLY01" to listOf(SelectedLibraryContract.COVER),
                "DUPLICATE01" to (SelectedLibraryContract.originalFiles.toList() +
                    SelectedLibraryContract.customStemFiles.first() + SelectedLibraryContract.ENRICHMENT)
            )
            packages.forEach { (id, names) ->
                val folder = File(directory, id).apply { check(mkdir()) }
                names.forEach { File(folder, it).writeBytes(byteArrayOf(1, 2, 3)) }
            }
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            val scan = LibraryStorageScanner(context, BackgroundWorkCoordinator())
                .scanRootStorage(checkNotNull(roots.captureHandle()).rootDocument)
            assertEquals(3, scan.songsFound)
            assertEquals(SelectedLibraryContract.originalFiles.size, scan.originalFiles)
            assertEquals(1, scan.stemFiles)
            assertEquals(2, scan.jsonFiles)
            assertEquals(1, scan.artworkFiles)
            assertEquals(directory.walkTopDown().filter(File::isFile).sumOf(File::length), scan.actualBytes)
            assertEquals(0L, providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("openDocumentCalls"))
        } finally {
            database.close(); revokeProviderTree(); resetProvider(); context.destroy()
        }
    }

    @Test
    fun storageRefreshUsesTheCanonicalChildBoundAndPreservesForeignFiles() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedStorageContext(targetContext)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            grantProviderTree()
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            seedCanonicalSongFolders(1)
            val directory = File(targetContext.noBackupFilesDir, "golden-enrichment-document-provider/C0000000000")
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, BackgroundWorkCoordinator(),
                scope = ownedRepositoryScope())
            awaitProviderObservation(external, roots)
            val limit = SelectedLibraryContract.maximumPackageDirectoryEntries
            for (count in listOf(35, 36, limit)) {
                while (directory.listFiles()!!.size < count) {
                    File(directory, "foreign-${directory.listFiles()!!.size}.bin").writeBytes(byteArrayOf(7))
                }
                external.reconcile()
                external.refreshStorageInventory()
                assertTrue(roots.state.value.ready)
                assertEquals(1, external.songs.value.size)
                assertNull(external.storageRefreshState.value.errorCode)
                assertEquals(count, directory.listFiles()!!.size)
                directory.listFiles()!!.filter { it.name.startsWith("foreign-") }.forEach {
                    assertArrayEquals(byteArrayOf(7), it.readBytes())
                }
            }
            File(directory, "overflow.bin").writeBytes(byteArrayOf(8))
            assertTrue(runCatching { LibraryStorageScanner(context, BackgroundWorkCoordinator())
                .scanRootStorage(root.rootDocument) }.isFailure)
            assertEquals(limit + 1, directory.listFiles()!!.size)
        } finally {
            retireRepositoryOwners()
            database.close(); revokeProviderTree(); resetProvider(); context.destroy()
        }
    }

    @Test
    fun coldOpenValidationPublishesOnlyCommittedCurrentPackageCountsAndRejectsLostRoot() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedStorageContext(targetContext)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            grantProviderTree()
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            seedCanonicalSongFolders(2)
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val repository = ExternalLibraryRepository(context, roots, dao, bindings, mutations, BackgroundWorkCoordinator(),
                scope = ownedRepositoryScope())
            awaitProviderObservation(repository, roots)

            repository.refreshStorageInventory()
            val measured = repository.storageRefreshState.value.inventory
            assertTrue(measured.actualLibraryBytes > 0L)
            assertEquals(0, measured.verifiedSongPackages)
            assertEquals(0, measured.verifiedStemFiles)

            repository.validateProjectedLibrary(root)
            fun assertVerifiedCounts(packages: Int) {
                val inventory = repository.storageRefreshState.value.inventory
                assertEquals(packages, inventory.verifiedSongPackages)
                assertEquals(packages, inventory.verifiedOriginalFiles)
                assertEquals(packages, inventory.verifiedJsonFiles)
                assertEquals(packages, inventory.verifiedArtworkFiles)
                assertEquals(packages * 9, inventory.verifiedStemFiles)
                assertTrue(inventory.verifiedTotalBytes > 0L)
            }
            assertVerifiedCounts(2)
            assertEquals("Canonical counts must not invent a newer physical measurement", measured.lastCheckedEpochMs,
                repository.storageRefreshState.value.inventory.lastCheckedEpochMs)
            assertEquals(measured.actualLibraryBytes, repository.storageRefreshState.value.inventory.actualLibraryBytes)
            repository.refreshStorageInventory()
            assertVerifiedCounts(2)

            val departed = File(targetContext.noBackupFilesDir,
                "golden-enrichment-document-provider/C0000000001")
            check(departed.deleteRecursively())
            repository.validateProjectedLibrary(root)
            assertVerifiedCounts(1)
            assertEquals("Missing folders must not count through retained Room history", 2, dao.canonicalStorageTotals().packages)

            val retained = repository.storageRefreshState.value.inventory
            roots.reportRuntimeAccessFailure(root, SecurityException("isolated storage root loss"))
            val rejected = runCatching { repository.validateProjectedLibrary(root) }
            assertTrue("A lost root published canonical totals", rejected.isFailure)
            assertEquals(retained, repository.storageRefreshState.value.inventory)
        } finally {
            retireRepositoryOwners()
            database.close(); revokeProviderTree(); resetProvider(); context.destroy()
        }
    }

    @Test
    fun rootSidecarsCountOnceOnCanonicalScanAndMetadataRefreshIncludingUnknownSizes() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (unknownSizes in listOf(false, true)) {
            resetProvider()
            val context = IsolatedStorageContext(targetContext)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            try {
                grantProviderTree()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, database.libraryDao())
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val directory = File(targetContext.noBackupFilesDir, "golden-enrichment-document-provider")
                val songDirectory = File(directory, "SIDECARS001").apply { check(mkdir()) }
                val original = CurrentSourcePublicationFixture.load(targetContext, "SIDECARS001").original
                File(songDirectory, SelectedLibraryContract.ORIGINAL).writeBytes(original)
                seedCompleteOpusMetadata(songDirectory, "SIDECARS001", original)
                val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
                val repository = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings,
                    mutations, BackgroundWorkCoordinator(), scope = ownedRepositoryScope())
                awaitProviderObservation(repository, roots)
                if (unknownSizes) providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT,
                    GoldenQaDocumentsProvider.FAULT_UNKNOWN_SIZE)
                assertFalse(repository.playbackSnapshot().snapshotReady)
                repository.reconcile()
                assertTrue(repository.playbackSnapshot().snapshotReady)
                assertEquals(listOf("SIDECARS001"), repository.playbackSnapshot().songs.map { it.id })
                fun requireExactPhysicalTotals() {
                    val files = directory.walkTopDown().filter(File::isFile).toList()
                    assertTrue("Fixture must contain root sidecars", files.any { it.parentFile == directory })
                    val inventory = repository.storageRefreshState.value.inventory
                    assertEquals(if (unknownSizes) 0L else files.sumOf(File::length), inventory.actualLibraryBytes)
                    assertEquals(if (unknownSizes) files.size else 0, inventory.unknownSizeFiles)
                }
                requireExactPhysicalTotals()
                repository.refreshStorageInventory()
                requireExactPhysicalTotals()
                assertNull(repository.storageRefreshState.value.errorMessage)
            } finally {
                retireRepositoryOwners()
                providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_NONE)
                database.close(); revokeProviderTree(); resetProvider(); context.destroy()
            }
        }
    }

    @Test
    fun observationInitializationQueriesAreSeparateFromTheMeasuredStorageRefresh() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedStorageContext(targetContext)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        executor.execute {
            entered.countDown()
            check(release.await(20L, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(8L, java.util.concurrent.TimeUnit.SECONDS))
            grantProviderTree()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            seedCanonicalSongFolders(2)
            val repository = ExternalLibraryRepository(context, roots, database.libraryDao(),
                SourceBindingRegistry(context, roots, mutations, database.libraryDao()), mutations,
                BackgroundWorkCoordinator(), scope = ownedRepositoryScope(dispatcher))
            assertEquals(0, repository.providerObservationCountForTest)
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            repository.refreshStorageInventory()
            assertEquals(3L, providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("queryChildCalls"))
            assertEquals(2, repository.storageRefreshState.value.inventory.songsFound)
            release.countDown()
            awaitProviderObservation(repository, roots)
            assertEquals(3, repository.providerObservationCountForTest)
            // First refresh: 3. Readiness root read: 1. Observation root read and 3 cursors: 4.
            assertEquals(8L, providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("queryChildCalls"))
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            repository.refreshStorageInventory()
            val metrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            assertEquals(3L, metrics.getLong("queryChildCalls"))
            assertEquals(0L, metrics.getLong("openDocumentCalls"))
            assertEquals(2, repository.storageRefreshState.value.inventory.songsFound)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            release.countDown()
            var cleanupFailure = cleanupIsolatedState(database, context)
            try {
                dispatcher.close()
                withContext(NonCancellable + Dispatchers.IO) {
                    check(executor.awaitTermination(8L, java.util.concurrent.TimeUnit.SECONDS))
                }
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            if (failure == null && cleanupFailure != null) throw cleanupFailure
            if (failure != null && cleanupFailure != null) failure.addSuppressed(cleanupFailure)
        }
        Unit
    }

    @Test
    fun twoThousandSongLibraryRefreshesWithinBudgetWithoutOpeningMedia() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedStorageContext(targetContext)
        var database: LibraryDatabase? = null
        var failure: Throwable? = null
        try {
            grantProviderTree()
            val mutations = PackageMutationCoordinator(context)
            database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            assertTrue(roots.state.value.ready)
            val expectedLibraryBytes = seedStorageScaleSongFolders() +
                File(targetContext.noBackupFilesDir, "golden-enrichment-document-provider")
                    .listFiles()!!.filter(File::isFile).sumOf(File::length)
            val sourceBindings = SourceBindingRegistry(
                context,
                roots,
                mutations,
                database.libraryDao()
            )
            val repository = ExternalLibraryRepository(
                context = context,
                roots = roots,
                dao = database.libraryDao(),
                sourceBindings = sourceBindings,
                mutations = mutations,
                backgroundWorkCoordinator = BackgroundWorkCoordinator(),
                scope = ownedRepositoryScope()
            )

            awaitProviderObservation(repository, roots)
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            val startedAt = SystemClock.elapsedRealtime()
            repository.refreshStorageInventory()
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val terminal = repository.storageRefreshState.value
            val metrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)

            assertFalse("Storage spinner remained active", terminal.refreshing)
            assertNull("Storage refresh reported: ${terminal.errorMessage}", terminal.errorMessage)
            assertTrue(
                "Storage refresh exceeded the 5 s visible budget: ${elapsedMs}ms",
                elapsedMs <= 5_250L
            )
            assertEquals(STORAGE_SCALE_FIXTURE_SONGS, terminal.inventory.songsFound)
            assertEquals(STORAGE_SCALE_FIXTURE_SONGS, terminal.inventory.originalFilesFound)
            assertEquals(STORAGE_SCALE_FIXTURE_SONGS * 2, terminal.inventory.stemFilesFound)
            assertEquals(STORAGE_SCALE_FIXTURE_SONGS / 4, terminal.inventory.jsonFilesFound)
            assertEquals(STORAGE_SCALE_FIXTURE_SONGS / 4, terminal.inventory.artworkFilesFound)
            assertEquals(expectedLibraryBytes, terminal.inventory.actualLibraryBytes)
            // Metadata refresh counts bytes but does not authenticate media; until canonical
            // reconciliation, every fixture byte is correctly classified as unverified.
            assertEquals(
                terminal.inventory.actualLibraryBytes,
                terminal.inventory.temporaryOrUnclassifiedBytes
            )
            assertEquals(0, terminal.inventory.unknownSizeFiles)
            assertEquals(0, terminal.inventory.unreadableDirectories)
            assertEquals(0L, metrics.getLong("openDocumentCalls"))
            assertEquals(0L, metrics.getLong("openedDocumentLengthBytes"))
            val childCalls = metrics.getLong("queryChildCalls")
            val documentCalls = metrics.getLong("queryDocumentCalls")
            assertTrue("queryChildCalls=$childCalls", childCalls in 2_049L..2_053L)
            assertTrue("queryDocumentCalls=$documentCalls", documentCalls <= 2L)
            Log.i(
                "StemwiseStorageGate",
                "elapsedMs=$elapsedMs childCalls=$childCalls documentCalls=$documentCalls " +
                    "openCalls=${metrics.getLong("openDocumentCalls")} " +
                    "openedDocumentLengthBytes=${metrics.getLong("openedDocumentLengthBytes")} " +
                    "actualBytes=${terminal.inventory.actualLibraryBytes}"
            )

        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val cleanupFailure = cleanupIsolatedState(database, context)
            if (failure == null && cleanupFailure != null) throw cleanupFailure
            if (failure != null && cleanupFailure != null) failure.addSuppressed(cleanupFailure)
        }
        Unit
    }

    @Test
    fun productionStorageProjectionAccountsForRetainedPayloadBytesAndTheirRemoval() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedStorageContext(targetContext)
        var database: LibraryDatabase? = null
        var failure: Throwable? = null
        try {
            grantProviderTree()
            val mutations = PackageMutationCoordinator(context)
            database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val repository = ExternalLibraryRepository(
                context,
                roots,
                database.libraryDao(),
                SourceBindingRegistry(context, roots, mutations, database.libraryDao()),
                mutations,
                BackgroundWorkCoordinator(),
                scope = ownedRepositoryScope()
            )
            awaitProviderObservation(repository, roots)
            val scratch = File(context.noBackupFilesDir, "preparation-active").apply {
                check(mkdirs() || isDirectory)
            }
            val payload = File(scratch, "upload-original.opus").apply {
                writeBytes(ByteArray(4_097) { 7 })
            }

            repository.refreshStorageInventory()
            val active = repository.storageRefreshState.value.inventory
            assertTrue(active.appWorkingBytes >= payload.length())

            check(payload.delete())
            repository.refreshStorageInventory()
            val clean = repository.storageRefreshState.value.inventory
            assertTrue(clean.appWorkingBytes < active.appWorkingBytes)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val cleanupFailure = cleanupIsolatedState(database, context)
            if (failure == null && cleanupFailure != null) throw cleanupFailure
            if (failure != null && cleanupFailure != null) failure.addSuppressed(cleanupFailure)
        }
        Unit
    }

    @Test
    fun eightCanonicalSongsReconcileWithReleasedPackageTurnsBoundedIoAndZeroScratch() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedStorageContext(targetContext)
        var database: LibraryDatabase? = null
        var failure: Throwable? = null
        try {
            grantProviderTree()
            val mutations = PackageMutationCoordinator(context)
            database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val expectedInitialOpenedBytes = seedCanonicalSongFolders(8)
            val sourceBindings = SourceBindingRegistry(
                context,
                roots,
                mutations,
                database.libraryDao()
            )
            val dao = database.libraryDao()
            val logicalRoomWrites = AtomicInteger(-1)
            val projectionSnapshots = AtomicInteger()
            dao.upsertSongs(listOf(SongEntity("C0000000000", "C0000000000", "playlist-thumbnail",
                "https://music.youtube.com/watch?v=C0000000000", "Playlist title", "Playlist artist", null, null,
                additionalInfoJson = "{\"artworkSourceUrl\":\"https://i.ytimg.com/old.jpg\"}",
                durationMs = 12_000L, artworkPath = "https://i.ytimg.com/old.jpg", currentlyInSourcePlaylist = true,
                highLevelState = "AWAITING_PC", acquiredAtEpochMs = null, createdAtEpochMs = 1L)))
            val repository = ExternalLibraryRepository(
                context = context,
                roots = roots,
                dao = dao,
                sourceBindings = sourceBindings,
                mutations = mutations,
                backgroundWorkCoordinator = BackgroundWorkCoordinator(),
                canonicalWriteObserver = logicalRoomWrites::set,
                scope = ownedRepositoryScope()
            )
            repository.songSnapshotPublishedForTest = { projectionSnapshots.incrementAndGet() }
            val visibleSongsBetweenTurns = mutableListOf<Int>()
            repository.canonicalPackageVerifiedForTest = {
                kotlinx.coroutines.withTimeout(5_000L) {
                    mutations.withRootMutation {
                        visibleSongsBetweenTurns += repository.songs.value.size
                    }
                }
            }
            val scratchBefore = appOwnedPayloadBytes(context)
            awaitProviderObservation(repository, roots)
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)

            val startedAt = SystemClock.elapsedRealtime()
            repository.reconcile()
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val metrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            val scratchIdle = appOwnedPayloadBytes(context)
            val firstWrites = logicalRoomWrites.get()

            assertEquals(8, repository.songs.value.size)
            assertTrue(repository.problems.value.isEmpty())
            assertEquals(8, firstWrites)
            assertEquals("Each verified package must publish before the next turn", 8, projectionSnapshots.get())
            assertEquals((1..8).toList(), visibleSongsBetweenTurns)
            val promoted = checkNotNull(dao.findSongById("C0000000000"))
            assertTrue(promoted.artworkPath.orEmpty().startsWith("content://"))
            assertEquals("{}", promoted.additionalInfoJson)
            // Initial/final snapshots, exact package reopen, and metadata inventory are bounded.
            assertTrue("child calls=${metrics.getLong("queryChildCalls")}", metrics.getLong("queryChildCalls") <= 80L)
            assertTrue("document calls=${metrics.getLong("queryDocumentCalls")}", metrics.getLong("queryDocumentCalls") <= 36L)
            // Original: hash/codec/EOS; each FLAC: hash/header/codec; Remaining: hash/header; two JSON reads.
            // Cover hashing, manifest binding and PNG decoding share one bounded byte-array read.
            assertEquals(8L * (3L + 8L * 3L + 2L + 2L + 1L), metrics.getLong("openDocumentCalls"))
            assertEquals(expectedInitialOpenedBytes, metrics.getLong("openedDocumentLengthBytes"))
            assertEquals(0L, scratchBefore)
            assertEquals(0L, scratchIdle)
            assertTrue("Canonical reconcile exceeded 30 s: ${elapsedMs}ms", elapsedMs <= 30_000L)

            val retainedPackageId = "C0000000000"
            val retainedSongCreatedAt = requireNotNull(dao.findSongById(retainedPackageId))
                .createdAtEpochMs
            val retainedSourceCreatedAt = requireNotNull(dao.currentSource(retainedPackageId))
                .createdAtEpochMs
            val retainedSetCreatedAt = dao.separationSetsForSong(retainedPackageId)
                .single().createdAtEpochMs
            val retainedRevision = requireNotNull(dao.findSongById(retainedPackageId)).contentRevision
            val retainedManifest = requireNotNull(dao.findSongById(retainedPackageId)).manifestFileSha256
            logicalRoomWrites.set(-1)
            visibleSongsBetweenTurns.clear()
            repository.reconcile()
            assertEquals(0, logicalRoomWrites.get())
            assertEquals("Unchanged scan published the Library again", 8, projectionSnapshots.get())
            assertEquals(List(8) { 8 }, visibleSongsBetweenTurns)
            assertEquals(retainedRevision, requireNotNull(dao.findSongById(retainedPackageId)).contentRevision)
            assertEquals(retainedManifest, requireNotNull(dao.findSongById(retainedPackageId)).manifestFileSha256)
            assertEquals(
                retainedSongCreatedAt,
                requireNotNull(dao.findSongById(retainedPackageId)).createdAtEpochMs
            )
            assertEquals(
                retainedSourceCreatedAt,
                requireNotNull(dao.currentSource(retainedPackageId)).createdAtEpochMs
            )
            assertEquals(
                retainedSetCreatedAt,
                dao.separationSetsForSong(retainedPackageId).single().createdAtEpochMs
            )

            val removedPackageId = "C0000000007"
            val providerRoot = File(
                targetContext.noBackupFilesDir,
                "golden-enrichment-document-provider"
            )
            check(File(providerRoot, removedPackageId).deleteRecursively())
            logicalRoomWrites.set(-1)
            visibleSongsBetweenTurns.clear()
            repository.reconcile()
            assertEquals(0, logicalRoomWrites.get())
            assertNull(dao.findSongById(removedPackageId))
            assertEquals(7, repository.songs.value.size)
            assertEquals("Missing packages must remain visible until the final scan reconciliation",
                List(7) { 8 }, visibleSongsBetweenTurns)
            assertEquals("Final removal must publish once after the package turns", 9, projectionSnapshots.get())
            Log.i(
                "StemwiseCanonicalGate",
                "songs=8 elapsedMs=$elapsedMs packageTurns=8 " +
                    "childCalls=${metrics.getLong("queryChildCalls")} " +
                    "documentCalls=${metrics.getLong("queryDocumentCalls")} " +
                    "mediaOpens=${metrics.getLong("openDocumentCalls")} " +
                    "openedDocumentLengthBytes=${metrics.getLong("openedDocumentLengthBytes")} " +
                    "roomPackageWrites=$firstWrites repeatRoomPackageWrites=0 " +
                    "scratchBefore=$scratchBefore scratchIdle=$scratchIdle"
            )
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val cleanupFailure = cleanupIsolatedState(database, context)
            if (failure == null && cleanupFailure != null) throw cleanupFailure
            if (failure != null && cleanupFailure != null) failure.addSuppressed(cleanupFailure)
        }
        Unit
    }

    @Test
    fun storageRefreshPreservesSafTaxonomyWithoutOpeningOrMutatingMedia() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        data class SafCase(
            val name: String,
            val expectedCode: String?,
            val fault: String = GoldenQaDocumentsProvider.FAULT_NONE,
            val songsFound: Int = 0,
            val seed: (File) -> Unit = {}
        )
        val validFolder: (File) -> Unit = { root ->
            check(File(root, "A0000000000").mkdir())
        }
        val cases = listOf(
            SafCase("true-empty", null),
            SafCase("missing-column", "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_MISSING_COLUMN, seed = validFolder),
            SafCase("blank-id", "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_BLANK_ID, seed = validFolder),
            SafCase("blank-name", "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_BLANK_NAME, seed = validFolder),
            SafCase("blank-mime", "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_BLANK_MIME, seed = validFolder),
            SafCase("oversized-mime", "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_OVERSIZED_MIME, seed = validFolder),
            SafCase("duplicate-id", "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_DUPLICATE_ID, seed = validFolder),
            SafCase("duplicate-name", "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_DUPLICATE_NAME, seed = validFolder),
            SafCase("large-root", null,
                GoldenQaDocumentsProvider.FAULT_TOO_MANY_ROOT_ROWS,
                songsFound = 8_192 + SelectedLibraryContract.rootAuthorityFiles.size + 1),
            SafCase("too-many-child-rows", "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_TOO_MANY_CHILD_ROWS, seed = validFolder),
            SafCase("non-directory-root-child", null, seed = { root ->
                File(root, "orphan.bin").writeBytes(byteArrayOf(1, 2, 3))
            }),
            SafCase("nested-song-entry", null, songsFound = 1, seed = { root ->
                val song = File(root, "A0000000000").apply { check(mkdir()) }
                check(File(song, "nested").mkdir())
            }),
            SafCase("unknown-song-name", null, songsFound = 1, seed = { root ->
                val song = File(root, "A0000000000").apply { check(mkdir()) }
                File(song, "unknown.bin").writeBytes(byteArrayOf(4, 5, 6))
            }),
            SafCase("null-cursor", "ROOT_UNAVAILABLE",
                GoldenQaDocumentsProvider.FAULT_NULL_CURSOR, seed = validFolder),
            SafCase("empty-cursor-with-provider-error", "ROOT_UNAVAILABLE",
                GoldenQaDocumentsProvider.FAULT_EMPTY_WITH_ERROR, seed = validFolder),
            SafCase("incomplete-loading-cursor", "ROOT_UNAVAILABLE",
                GoldenQaDocumentsProvider.FAULT_LOADING_CURSOR, seed = validFolder),
            SafCase("provider-throw", "ROOT_UNAVAILABLE",
                GoldenQaDocumentsProvider.FAULT_THROW, seed = validFolder),
            SafCase("permission-loss", "ROOT_PERMISSION_LOST",
                GoldenQaDocumentsProvider.FAULT_SECURITY, seed = validFolder)
        )

        cases.forEach { case ->
            resetProvider()
            val context = IsolatedStorageContext(targetContext)
            var database: LibraryDatabase? = null
            var failure: Throwable? = null
            try {
                grantProviderTree()
                val mutations = PackageMutationCoordinator(context)
                database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
                val roots = LibraryRootManager(context, mutations, database.libraryDao())
                val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                    .getOrThrow()
                roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
                val providerRoot = File(
                    targetContext.noBackupFilesDir,
                    "golden-enrichment-document-provider"
                )
                case.seed(providerRoot)
                val before = providerEvidenceFingerprint(providerRoot)
                val repository = ExternalLibraryRepository(
                    context = context,
                    roots = roots,
                    dao = database.libraryDao(),
                    sourceBindings = SourceBindingRegistry(
                        context,
                        roots,
                        mutations,
                        database.libraryDao()
                    ),
                    mutations = mutations,
                    backgroundWorkCoordinator = BackgroundWorkCoordinator(),
                    scope = ownedRepositoryScope()
                )
                awaitProviderObservation(repository, roots)
                providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, case.fault)
                providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
                val error = runCatching { repository.refreshStorageInventory() }.exceptionOrNull()
                val metrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)

                if (case.expectedCode == null) {
                    assertNull("${case.name}: unexpected failure: ${error?.stackTraceToString()}", error)
                    assertTrue("${case.name}: root was demoted", roots.state.value.ready)
                    assertEquals(case.name, case.songsFound, repository.storageRefreshState.value.inventory.songsFound)
                } else {
                    assertEquals(
                        "${case.name}: wrong thrown taxonomy",
                        case.expectedCode,
                        (error as? LibraryRootException)?.code
                    )
                    assertEquals(
                        "${case.name}: wrong published root taxonomy",
                        case.expectedCode,
                        roots.state.value.errorCode
                    )
                    assertFalse("${case.name}: failed root remained handle-ready", roots.state.value.ready)
                }
                assertEquals("${case.name}: media was opened", 0L, metrics.getLong("openDocumentCalls"))
                assertEquals("${case.name}: media bytes were read", 0L, metrics.getLong("openedDocumentLengthBytes"))
                if (case.fault == GoldenQaDocumentsProvider.FAULT_TOO_MANY_ROOT_ROWS) {
                    assertEquals(
                        "${case.name}: storage refresh did not inspect every recognized folder",
                        1L + case.songsFound,
                        metrics.getLong("queryChildCalls")
                    )
                }
                if (case.fault == GoldenQaDocumentsProvider.FAULT_TOO_MANY_CHILD_ROWS) {
                    assertEquals(
                        "${case.name}: storage refresh continued after child overflow",
                        2L,
                        metrics.getLong("queryChildCalls")
                    )
                }
                assertEquals("${case.name}: selected evidence changed", before,
                    providerEvidenceFingerprint(providerRoot))
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                val cleanupFailure = cleanupIsolatedState(database, context)
                if (failure == null && cleanupFailure != null) throw cleanupFailure
                if (failure != null && cleanupFailure != null) failure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun seedCanonicalSongFolders(songCount: Int): Long {
        val fixture = CurrentSourcePublicationFixture.load(targetContext, "DEMOUI00001")
        var bytes = 0L
        val root = File(
            targetContext.noBackupFilesDir,
            "golden-enrichment-document-provider"
        )
        repeat(songCount) { songIndex ->
            val videoId = "C${songIndex.toString().padStart(10, '0')}"
            val folder = File(root, videoId).apply { check(mkdir()) }
            File(folder, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original)
            bytes += fixture.original.size * 3L
            fixture.parts.forEach { (name, content) ->
                File(folder, name).writeBytes(content)
                bytes += content.size * if (name == "remaining.wv") 2L else 3L
            }
            val manifest = fixture.manifest.copy(videoId = videoId).toBytes()
            File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).writeBytes(manifest)
            bytes += manifest.size
            seedCompleteOpusMetadata(folder, videoId, fixture.original)
            bytes += File(folder, SelectedLibraryContract.ENRICHMENT).length() +
                File(folder, SelectedLibraryContract.COVER).length()
        }
        return bytes
    }

    private fun mediaDurationMs(bytes: ByteArray, suffix: String): Long {
        val file = File.createTempFile("canonical-reconcile-duration-", suffix, targetContext.cacheDir)
        return try {
            file.writeBytes(bytes)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                extractor.getTrackFormat(0).getLong(android.media.MediaFormat.KEY_DURATION)
                    .div(1_000L).coerceAtLeast(1L)
            } finally {
                extractor.release()
            }
        } finally {
            check(!file.exists() || file.delete())
        }
    }

    private fun appOwnedPayloadBytes(context: Context): Long = listOf(
        File(context.cacheDir, "enrichment"),
        File(context.noBackupFilesDir, "import-staging"),
        File(context.noBackupFilesDir, "preparation-active")
    ).sumOf { root -> inventoryPrivateScratch(root, "SCRATCH_INVENTORY_FAILED").payloadBytes }

    private fun providerEvidenceFingerprint(root: File): List<String> =
        root.walkTopDown().drop(1).map { entry ->
            val kind = if (entry.isDirectory) "D" else "F"
            "$kind:${entry.relativeTo(root).invariantSeparatorsPath}:${entry.length()}"
        }.sorted().toList()

    private fun seedStorageScaleSongFolders(): Long {
        val root = File(
            targetContext.noBackupFilesDir,
            "golden-enrichment-document-provider"
        )
        check(root.isDirectory)
        var expectedBytes = 0L
        repeat(STORAGE_SCALE_FIXTURE_SONGS) { index ->
            val videoId = "S${index.toString().padStart(10, '0')}"
            check(SelectedLibraryContract.isVideoId(videoId))
            val folder = File(root, videoId).apply { check(mkdir()) }
            val media = linkedMapOf<String, InstalledMediaIdentity>()
            val originalBytes = byteArrayOf(0x4f, 0x50)
            File(folder, SelectedLibraryContract.ORIGINAL).writeBytes(originalBytes)
            media[SelectedLibraryContract.ORIGINAL] = InstalledMediaIdentity(
                sha256(originalBytes),
                originalBytes.size.toLong()
            )
            expectedBytes += originalBytes.size
            (listOf("part-1.flac", "remaining.wv")).forEachIndexed { roleIndex, name ->
                val bytes = byteArrayOf(0x4d, roleIndex.toByte())
                File(folder, name).writeBytes(bytes)
                expectedBytes += bytes.size
            }
            if (index % 4 == 0) {
                val external = JSONObject()
                    .put("formatVersion", 2)
                    .put("kind", "stemwise.song-enrichment")
                    .put(
                        "source",
                        JSONObject().put("provider", "youtube").put("videoId", videoId)
                    )
                    .put("generatedAt", "2026-08-09T12:00:00Z")
                    .put(
                        "generator",
                        JSONObject().put("name", "storage-fixture").put("version", "1")
                    )
                    .put(
                        "artwork",
                        JSONObject()
                            .put("file", "cover.png")
                            .put("kind", "youtube-thumbnail")
                            .put("source", JSONObject().put("name", "fixture"))
                    )
                val coverBytes = java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk" +
                        "+A8AAQUBAScY42YAAAAASUVORK5CYII="
                )
                val installed = createInstalledEnrichmentDocument(
                    incoming = external,
                    originalSha256 = media.getValue(SelectedLibraryContract.ORIGINAL).sha256,
                    media = mapOf(
                        SelectedLibraryContract.ORIGINAL to
                            media.getValue(SelectedLibraryContract.ORIGINAL)
                    ),
                    contentRevision = 1L,
                    payloadSha256 = "f".repeat(64),
                    artwork = external.getJSONObject("artwork"),
                    artworkSha256 = sha256(coverBytes),
                    artworkBytes = coverBytes.size
                ).toString()
                File(folder, SelectedLibraryContract.COVER).writeBytes(coverBytes)
                File(folder, SelectedLibraryContract.ENRICHMENT).writeText(
                    installed,
                    Charsets.UTF_8
                )
                expectedBytes += coverBytes.size + installed.toByteArray(Charsets.UTF_8).size
            }
            assertTrue(folder.listFiles().orEmpty().all { it.isFile })
            assertTrue(folder.listFiles().orEmpty().all { it.name in SelectedLibraryContract.allowedFiles })
        }
        return expectedBytes
    }

    private companion object {
        const val STORAGE_SCALE_FIXTURE_SONGS = 2_048
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun ownedRepositoryScope(dispatcher: CoroutineDispatcher = Dispatchers.IO): CoroutineScope {
        val owner = SupervisorJob()
        repositoryOwners += owner
        return CoroutineScope(owner + dispatcher)
    }

    private suspend fun retireRepositoryOwners() = withContext(NonCancellable) {
        repositoryOwners.forEach { it.cancel() }
        withTimeout(8_000L) { repositoryOwners.forEach { it.join() } }
        repositoryOwners.clear()
    }

    private suspend fun awaitProviderObservation(repository: ExternalLibraryRepository, roots: LibraryRootManager) {
        val expected = roots.checkedRootDirectories(checkNotNull(roots.captureHandle())).size + 1
        withTimeout(8_000L) {
            while (repository.providerObservationCountForTest != expected) delay(1L)
        }
    }

    private suspend fun cleanupIsolatedState(
        database: LibraryDatabase?,
        context: IsolatedStorageContext
    ): Throwable? {
        // An unretired owner may still hold the provider or database; retain them on failure.
        runCatching { retireRepositoryOwners() }.exceptionOrNull()?.let { return it }
        val failures = mutableListOf<Throwable>()
        listOf<() -> Unit>(
            { database?.close() },
            { revokeProviderTree() },
            { resetProvider() },
            { context.destroy() }
        ).forEach { cleanup -> runCatching(cleanup).exceptionOrNull()?.let(failures::add) }
        if (failures.isEmpty()) return null
        return AssertionError("Isolated storage-test cleanup failed").apply {
            failures.forEach(::addSuppressed)
        }
    }

    private fun grantProviderTree() {
        targetContext.grantUriPermission(
            targetContext.packageName,
            GoldenQaDocumentsProvider.treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    }

    private fun revokeProviderTree() {
        targetContext.revokeUriPermission(
            targetContext.packageName,
            GoldenQaDocumentsProvider.treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    private fun resetProvider() {
        providerCall(GoldenQaDocumentsProvider.METHOD_RESET)
    }

    private fun providerCall(method: String, arg: String? = null): Bundle = checkNotNull(
        targetContext.contentResolver.call(
            Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
            method,
            arg,
            null
        )
    ) { "Private storage provider call returned no result: $method" }

    @Test
    fun appPrivateScannerRejectsCategoryAndFinalTotalOverflow() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        class Entry(path: String, private val bytes: Long = 0L,
            private val children: Array<File>? = null, private val parent: File? = null) : File(path) {
            override fun exists() = true
            override fun isDirectory() = children != null
            override fun isFile() = children == null
            override fun listFiles(): Array<File>? = children
            override fun length() = bytes
            override fun getParentFile(): File? = parent ?: super.getParentFile()
        }
        fun context(database: List<Long>, working: List<Long>, cache: List<Long>): Context {
            val prefix = "/stemwise-inventory-${UUID.randomUUID()}"
            fun directory(name: String, sizes: List<Long>) = Entry("$prefix/$name",
                children = sizes.mapIndexed { index, size -> Entry("$prefix/$name/$index", size) }.toTypedArray<File>())
            val databases = directory("database", database)
            val files = directory("working", working)
            val caches = directory("cache", cache)
            return object : ContextWrapper(targetContext) {
                override fun getDatabasePath(name: String): File = Entry("$prefix/database/$name", parent = databases)
                override fun getFilesDir(): File = files
                override fun getNoBackupFilesDir(): File = directory("no-backup", emptyList())
                override fun getCacheDir(): File = caches
                override fun getCodeCacheDir(): File = directory("code-cache", emptyList())
                override fun getApplicationInfo() = android.content.pm.ApplicationInfo().apply { dataDir = "$prefix/absent" }
            }
        }
        val ordinary = LibraryStorageScanner(context(listOf(3L), listOf(5L), listOf(7L)),
            BackgroundWorkCoordinator()).scanAppPrivateStorage()
        assertEquals(3L, ordinary.databaseBytes)
        assertEquals(5L, ordinary.workingBytes)
        assertEquals(7L, ordinary.cacheBytes)
        assertEquals(15L, ordinary.totalBytes)
        val scenarios: List<Triple<List<Long>, List<Long>, List<Long>>> = listOf(
            Triple(listOf(Long.MAX_VALUE, 1L), emptyList(), emptyList()),
            Triple(emptyList(), listOf(Long.MAX_VALUE, 1L), emptyList()),
            Triple(emptyList(), emptyList(), listOf(Long.MAX_VALUE, 1L)),
            Triple(listOf(Long.MAX_VALUE), listOf(1L), emptyList())
        )
        for ((database, working, cache) in scenarios) {
            val failure = runCatching {
                LibraryStorageScanner(context(database, working, cache), BackgroundWorkCoordinator())
                    .scanAppPrivateStorage()
            }.exceptionOrNull()
            assertTrue("App-private byte overflow escaped the actual scanner: $failure", failure is ArithmeticException)
        }
    }

    private class IsolatedStorageContext(base: Context) : ContextWrapper(base) {
        private val namespace = UUID.randomUUID().toString()
        private val ownedPreferenceNames = linkedSetOf<String>()
        private val root = File(base.cacheDir, "storage-refresh-test/$namespace").apply {
            check(mkdirs() || isDirectory)
        }
        private val isolatedFiles = File(root, "files").apply { mkdirs() }
        private val isolatedCache = File(root, "cache").apply { mkdirs() }
        private val isolatedNoBackup = File(root, "no-backup").apply { mkdirs() }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
        override fun getCacheDir(): File = isolatedCache
        override fun getNoBackupFilesDir(): File = isolatedNoBackup
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val ownedName = "storage-qa-$namespace-$name"
            synchronized(ownedPreferenceNames) { ownedPreferenceNames += ownedName }
            return baseContext.getSharedPreferences(ownedName, mode)
        }

        fun destroy() {
            val failures = mutableListOf<String>()
            synchronized(ownedPreferenceNames) {
                ownedPreferenceNames.forEach { name ->
                    baseContext.deleteSharedPreferences(name)
                    val directory = File(baseContext.applicationInfo.dataDir, "shared_prefs")
                    listOf(File(directory, "$name.xml"), File(directory, "$name.xml.bak"))
                        .filter(File::exists)
                        .forEach { failures += it.absolutePath }
                }
                ownedPreferenceNames.clear()
            }
            if (root.exists() && !root.deleteRecursively()) failures += root.absolutePath
            check(failures.isEmpty()) { "Unable to remove isolated state: ${failures.joinToString()}" }
        }
    }
}
