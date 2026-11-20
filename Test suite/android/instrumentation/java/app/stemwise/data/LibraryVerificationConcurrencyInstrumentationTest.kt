package app.stemwise.data

import androidx.room.Room
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import org.junit.Assert.*
import org.junit.Test

internal class LibraryVerificationConcurrencyInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun manualCanonicalRefreshCoalescesAfterTheWorkerConsumesItsDirtyFlag() = runBlocking {
        withLibrary { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scans = java.util.concurrent.atomic.AtomicInteger()
            val previous = fixture.external.storageRefreshState.value.inventory
            fixture.external.canonicalScanStartedForTest = {
                scans.incrementAndGet()
                entered.complete(Unit)
                withTimeout(5_000L) { release.await() }
            }
            val refresh = fixture.external.requestCanonicalStorageRefresh()
            try {
                withTimeout(5_000L) { entered.await() }
                assertTrue(fixture.external.canonicalRefreshState.value.refreshing)
                assertEquals(previous.lastCheckedEpochMs,
                    fixture.external.storageRefreshState.value.inventory.lastCheckedEpochMs)
                repeat(20) { assertSame(refresh, fixture.external.requestCanonicalStorageRefresh()) }
                release.complete(Unit)
                withTimeout(10_000L) { refresh.join() }
                assertEquals(1, scans.get())
                assertFalse(fixture.external.canonicalRefreshState.value.refreshing)
                assertNull(fixture.external.storageRefreshState.value.errorCode)
                assertTrue(fixture.external.storageRefreshState.value.inventory.lastCheckedEpochMs > previous.lastCheckedEpochMs)
                assertEquals(1, fixture.external.storageRefreshState.value.inventory.verifiedSongPackages)
                fixture.assertPreserved()
            } finally {
                release.complete(Unit)
                withTimeout(5_000L) { refresh.cancelAndJoin() }
                fixture.external.canonicalScanStartedForTest = null
            }
        }
    }

    @Test fun manualCanonicalRefreshStillVerifiesWhileAnEarlierMetadataRefreshIsActive() = runBlocking {
        withLibrary { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.external.canonicalScanStartedForTest = {
                entered.complete(Unit)
                withTimeout(5_000L) { release.await() }
            }
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_STORAGE_QUERY)
            val metadata = async(Dispatchers.IO) { fixture.external.refreshStorageInventory() }
            var canonical: Job? = null
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                canonical = fixture.external.requestCanonicalStorageRefresh()
                withTimeout(5_000L) { entered.await() }
                assertTrue(fixture.external.storageRefreshState.value.refreshing)
                assertTrue(fixture.external.canonicalRefreshState.value.refreshing)
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { metadata.await() }
                assertFalse(fixture.external.storageRefreshState.value.refreshing)
                assertTrue("Metadata completion hid the ongoing canonical scan",
                    fixture.external.canonicalRefreshState.value.refreshing)
                assertFalse(checkNotNull(canonical).isCompleted)
                release.complete(Unit)
                withTimeout(10_000L) { checkNotNull(canonical).join() }
                assertEquals(1, fixture.external.storageRefreshState.value.inventory.verifiedSongPackages)
                fixture.assertPreserved()
            } finally {
                release.complete(Unit)
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { metadata.cancelAndJoin(); canonical?.cancelAndJoin() }
                fixture.external.canonicalScanStartedForTest = null
            }
        }
    }

    @Test fun providerNotificationDuringManualCanonicalRefreshRetainsItsRequiredNextPass() = runBlocking {
        withLibrary { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scans = java.util.concurrent.atomic.AtomicInteger()
            fixture.external.canonicalScanStartedForTest = { scans.incrementAndGet() }
            fixture.external.canonicalPackageVerifiedForTest = {
                if (entered.complete(Unit)) withTimeout(5_000L) { release.await() }
            }
            val refresh = fixture.external.requestCanonicalStorageRefresh()
            try {
                withTimeout(5_000L) { entered.await() }
                val otherId = "MANUAL00001"
                val other = File(providerRoot(), otherId).apply { check(mkdir()) }
                File(other, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original.readBytes())
                seedCompleteOpusMetadata(other, otherId, fixture.original.readBytes())
                val notification = fixture.external.requestProviderRefresh(
                    android.provider.DocumentsContract.buildChildDocumentsUri(GoldenQaDocumentsProvider.AUTHORITY, "root"))
                assertSame(refresh, notification)
                repeat(20) { assertSame(refresh, fixture.external.requestCanonicalStorageRefresh()) }
                release.complete(Unit)
                withTimeout(10_000L) { refresh.join() }
                assertEquals(2, scans.get())
                assertEquals(setOf(VIDEO_ID, otherId), fixture.external.songs.value.map { it.id }.toSet())
                assertEquals(2, fixture.external.storageRefreshState.value.inventory.verifiedSongPackages)
                fixture.assertPreserved()
            } finally {
                release.complete(Unit)
                withTimeout(5_000L) { refresh.cancelAndJoin() }
                fixture.external.canonicalScanStartedForTest = null
                fixture.external.canonicalPackageVerifiedForTest = null
            }
        }
    }

    @Test fun manualCanonicalRefreshFailureRetainsStateAndAllowsAnExplicitRetry() = runBlocking {
        withLibrary { fixture ->
            val inventory = fixture.external.storageRefreshState.value.inventory
            val songs = fixture.external.songs.value
            val root = checkNotNull(fixture.roots.captureHandle())
            val source = fixture.database.libraryDao().currentSource(VIDEO_ID)
            val job = fixture.seedPendingJob(root)
            fixture.external.canonicalScanStartedForTest = { throw java.io.IOException("Manual scan failed") }
            try {
                withTimeout(5_000L) { fixture.external.requestCanonicalStorageRefresh().join() }
                val failed = fixture.external.storageRefreshState.value
                assertFalse(failed.refreshing)
                assertFalse(fixture.external.canonicalRefreshState.value.refreshing)
                assertEquals("Manual scan failed", failed.errorMessage)
                assertEquals(inventory, failed.inventory)
                assertEquals(songs, fixture.external.songs.value)
                assertEquals(source, fixture.database.libraryDao().currentSource(VIDEO_ID))
                assertEquals(job, fixture.database.libraryDao().findJobById(job.id))
                fixture.assertPreserved()
                fixture.external.canonicalScanStartedForTest = null
                withTimeout(10_000L) { fixture.external.requestCanonicalStorageRefresh().join() }
                assertNull(fixture.external.storageRefreshState.value.errorCode)
                assertEquals(LibraryInventoryAvailability.VERIFIED, fixture.external.storageRefreshState.value.availability)
                assertEquals(1, fixture.external.storageRefreshState.value.inventory.verifiedSongPackages)
                assertEquals(source, fixture.database.libraryDao().currentSource(VIDEO_ID))
                assertEquals(job, fixture.database.libraryDao().findJobById(job.id))
                assertNull(fixture.escaped.get())
                fixture.assertPreserved()
            } finally { fixture.external.canonicalScanStartedForTest = null }
        }
    }

    @Test fun metadataScanRestartsAfterActualDeletionMakesCapturedChildSecurityStale() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val otherId = "SELECT00001"
            val other = File(providerRoot(), otherId).apply { check(mkdir()) }
            File(other, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original.readBytes())
            seedCompleteOpusMetadata(other, otherId, fixture.original.readBytes())
            fixture.external.verifyPlaybackSong(root, otherId)
            val scanner = LibraryStorageScanner(fixture.context, BackgroundWorkCoordinator())
            val trash = ExternalPackageTrash(fixture.context, fixture.roots, fixture.mutations, fixture.bindings, fixture.database.libraryDao())
            var removed = false
            scanner.beforeCapturedDirectoryListingForTest = { id ->
                if (id == VIDEO_ID && !removed) {
                    removed = true
                    trash.deletePermanently(root, VIDEO_ID).getOrThrow()
                    setFault(GoldenQaDocumentsProvider.FAULT_MISSING_DOCUMENT_SECURITY)
                }
            }
            val scan = withTimeout(10_000L) { scanner.scanRootStorage(root.rootDocument) }
            assertTrue(removed)
            assertEquals(1, scan.songsFound)
            assertEquals(1, scan.originalFiles)
            assertTrue(fixture.roots.state.value.ready)
            assertFalse(fixture.folder.exists())
            assertEquals(setOf("youtube-video:$VIDEO_ID"), fixture.bindings.blockedSourceIds(root))
            assertEquals(otherId, fixture.external.verifyPlaybackSong(root, otherId).id)
            setFault(GoldenQaDocumentsProvider.FAULT_SECURITY)
            val denied = runCatching { scanner.scanRootStorage(root.rootDocument) }.exceptionOrNull()
            assertEquals("ROOT_PERMISSION_LOST", canonicalProjectionRootAccessFailure(checkNotNull(denied))?.code)
        }
    }
    @Test fun deletionRecoveryCannotConsumeLivePreparedOrConfirmedJournal() = runBlocking {
        for (stemsOnly in listOf(false, true)) withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val trash = ExternalPackageTrash(fixture.context, fixture.roots, fixture.mutations, fixture.bindings, fixture.database.libraryDao())
            val prepared = CompletableDeferred<Unit>()
            val confirm = java.util.concurrent.CountDownLatch(1)
            val read = CompletableDeferred<String>()
            val holdRecovery = CompletableDeferred<Unit>()
            fixture.mutations.journalWriteForTest = { operation, state ->
                if (operation.startsWith("DELETE_") && state == "DELETE_CONFIRMED") {
                    prepared.complete(Unit)
                    check(confirm.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Confirmation barrier timed out" }
                }
            }
            trash.deletionRecoveryReadForTest = { _, state -> read.complete(state); holdRecovery.await() }
            trash.deletionChildRemovedForTest = { throw java.io.IOException("Interrupted after actual child deletion") }
            val deletion = async(Dispatchers.IO) {
                if (stemsOnly) trash.deleteStems(root, VIDEO_ID) else trash.deletePermanently(root, VIDEO_ID)
            }
            var recovery: Job? = null
            try {
                withTimeout(5_000L) { prepared.await() }
                recovery = launch(Dispatchers.IO) { trash.recoverInterruptedDeletes(root) }
                assertNull("Recovery observed a journal still owned by live deletion", withTimeoutOrNull(200L) { read.await() })
                confirm.countDown()
                assertTrue(withTimeout(5_000L) { deletion.await() }.isFailure)
                val state = withTimeout(5_000L) { read.await() }
                assertTrue(state in setOf("DELETE_CONFIRMED", "SOURCE_BINDING_COMMIT"))
                withTimeout(5_000L) { recovery.cancelAndJoin() }
                val records = checkedAtomicJournalRecords(File(fixture.context.filesDir, "operation-journals"), "JOURNAL_CORRUPT")
                assertTrue(records.any { it.value.getString("state") in setOf("DELETE_CONFIRMED", "SOURCE_BINDING_COMMIT") })
                fixture.mutations.journalWriteForTest = null
                val restarted = ExternalPackageTrash(fixture.context, fixture.roots, fixture.mutations, fixture.bindings, fixture.database.libraryDao())
                withTimeout(10_000L) { restarted.recoverInterruptedDeletes(root) }
                if (stemsOnly) {
                    assertArrayEquals(fixture.before.getValue(SelectedLibraryContract.ORIGINAL), fixture.original.readBytes())
                    assertFalse(fixture.folder.list().orEmpty().any { it in SelectedLibraryContract.customStemFiles })
                } else assertFalse(fixture.folder.exists())
            } finally {
                confirm.countDown(); holdRecovery.complete(Unit)
                fixture.mutations.journalWriteForTest = null
                withTimeout(5_000L) { deletion.cancelAndJoin(); recovery?.cancelAndJoin() }
            }
        }
    }

    @Test fun abandonedPreparedDeleteFromEarlierEpochDoesNotBlockExplicitReadd() = runBlocking {
        for (stemsOnly in listOf(false, true)) withLibrary { fixture ->
            val first = checkNotNull(fixture.roots.captureHandle())
            val trash = ExternalPackageTrash(fixture.context, fixture.roots, fixture.mutations, fixture.bindings, fixture.database.libraryDao())
            fixture.mutations.journalWriteForTest = { operation, state ->
                if (operation.startsWith("DELETE_") && state == "DELETE_CONFIRMED") throw java.io.IOException("Before confirmation")
            }
            assertTrue((if (stemsOnly) trash.deleteStems(first, VIDEO_ID) else trash.deletePermanently(first, VIDEO_ID)).isFailure)
            fixture.mutations.journalWriteForTest = null
            val away = File(providerRoot(), "away").apply { check(mkdir()) }
            val treeB = android.provider.DocumentsContract.buildTreeDocumentUri(GoldenQaDocumentsProvider.AUTHORITY, "root/away")
            targetContext.grantUriPermission(targetContext.packageName, treeB,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            try {
                withTimeout(5_000L) { fixture.scope.coroutineContext[Job]?.cancelAndJoin() }
                // The QA activation seam admits one root per owner; restart owners between selections.
                val awayRoots = LibraryRootManager(fixture.context, fixture.mutations, fixture.database.libraryDao())
                awayRoots.commitValidatedCandidateForDevelopment(awayRoots.validateCandidate(treeB).getOrThrow()).getOrThrow()
                assertNotEquals(first.libraryId, checkNotNull(awayRoots.captureHandle()).libraryId)
                val returnedRoots = LibraryRootManager(fixture.context, fixture.mutations, fixture.database.libraryDao())
                returnedRoots.commitValidatedCandidateForDevelopment(returnedRoots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val current = checkNotNull(returnedRoots.captureHandle())
                assertEquals(first.libraryId, current.libraryId)
                assertTrue(current.epoch > first.epoch)
                val returnedBindings = SourceBindingRegistry(fixture.context, returnedRoots, fixture.mutations, fixture.database.libraryDao())
                val returnedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val returnedLibrary = ExternalLibraryRepository(fixture.context, returnedRoots, fixture.database.libraryDao(),
                        returnedBindings, fixture.mutations, BackgroundWorkCoordinator(), scope = returnedScope)
                    assertEquals(VIDEO_ID, withTimeout(10_000L) { returnedLibrary.verifyPlaybackSong(current, VIDEO_ID) }.id)
                } finally { withTimeout(5_000L) { returnedScope.coroutineContext[Job]?.cancelAndJoin() } }
                val restarted = ExternalPackageTrash(fixture.context, returnedRoots, fixture.mutations, returnedBindings, fixture.database.libraryDao())
                restarted.deletePermanently(current, VIDEO_ID).getOrThrow()
                restarted.prepareExplicitReadd(current, VIDEO_ID).getOrThrow()
                restarted.recoverInterruptedDeletes(current)
                assertFalse("youtube-video:$VIDEO_ID" in returnedBindings.blockedSourceIds(current))
                assertFalse(fixture.folder.exists())
            } finally {
                targetContext.revokeUriPermission(targetContext.packageName, treeB,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }
    @Test fun childDocumentNotificationRetriesNewPackageWithoutRoomMapping() = runBlocking {
        withLibrary { fixture ->
            val otherId = "ZZNEW000001"
            val other = File(providerRoot(), otherId).apply { check(mkdir()) }
            File(other, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original.readBytes())
            seedCompleteOpusMetadata(other, otherId, fixture.original.readBytes())
            var armed = false
            fixture.external.canonicalPackageVerifiedForTest = { id ->
                if (id == VIDEO_ID && !armed) {
                    armed = true
                    setFault(GoldenQaDocumentsProvider.FAULT_MANIFEST_READ_ONCE)
                }
            }
            withTimeout(5_000L) {
                while (fixture.external.providerObservationCountForTest == 0) delay(10L)
            }
            targetContext.contentResolver.notifyChange(android.provider.DocumentsContract.buildChildDocumentsUri(
                GoldenQaDocumentsProvider.AUTHORITY, "root"), null)
            val song = withTimeout(10_000L) { fixture.external.songs.first { songs -> songs.any { it.id == otherId } } }
                .single { it.id == otherId }
            assertTrue("Structural scan never reached the transient package failure", armed)
            assertEquals(otherId, song.id)
            assertTrue(fixture.external.songs.value.any { it.id == VIDEO_ID })
            assertTrue(fixture.roots.state.value.ready)
            fixture.external.canonicalPackageVerifiedForTest = null

            withTimeout(5_000L) { while (fixture.external.providerObservationCountForTest < 3) delay(10L) }
            val manifest = File(other, SelectedLibraryContract.ENRICHMENT)
            val previous = org.json.JSONObject(manifest.readText(Charsets.UTF_8))
            val incoming = payload("2026-08-10T12:00:00Z", "Nested notification update", true)
            val url = "https://music.youtube.com/watch?v=$otherId"
            incoming.getJSONObject("source").put("videoId", otherId)
            incoming.getJSONObject("metadata").getJSONArray("sources").getJSONObject(0).put("url", url)
            incoming.getJSONObject("artwork").getJSONObject("source").put("url", url)
            val cover = File(other, SelectedLibraryContract.COVER)
            val coverHash = sha256(cover)
            val changed = app.stemwise.enrichment.createInstalledEnrichmentDocument(incoming, fixture.originalHash,
                mapOf(SelectedLibraryContract.ORIGINAL to app.stemwise.enrichment.InstalledMediaIdentity(
                    fixture.originalHash, fixture.original.length())), previous.getJSONObject("install").getLong("contentRevision") + 1L,
                app.stemwise.enrichment.enrichmentRevisionIdentity(incoming, coverHash), incoming.getJSONObject("artwork"), coverHash, cover.length().toInt())
            manifest.writeText(changed.toString(), Charsets.UTF_8)
            targetContext.contentResolver.notifyChange(android.provider.DocumentsContract.buildChildDocumentsUri(
                GoldenQaDocumentsProvider.AUTHORITY, "root/$otherId"), null)
            withTimeout(10_000L) { fixture.external.songs.first { songs -> songs.any { it.id == otherId && it.title == "Nested notification update" } } }
            fixture.assertPreserved()
            assertNull(fixture.escaped.get())
        }
    }
    @Test fun playbackVerificationFromMainKeepsProjectionOffUiAndPreservesPackage() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val projected = AtomicReference<Thread?>()
            fixture.external.packageProjectionPreparedForTest = {
                projected.set(Thread.currentThread())
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                    "Package verification resumed its projection on the UI thread"
                }
            }
            try {
                assertEquals(fixture.originalHash, withContext(Dispatchers.Main) {
                    fixture.external.verifyPlaybackSong(root, VIDEO_ID)
                }.sourceHash)
                assertNotNull(projected.get())
                assertEquals(fixture.originalHash, withContext(Dispatchers.Main) {
                    fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID)
                }.sourceHash)
                fixture.assertPreserved()
            } finally {
                fixture.external.packageProjectionPreparedForTest = null
            }
        }
    }

    @Test fun candidateIdentityChangeBeforeCommitPreservesSelectedRootAndProjection() = runBlocking {
        withLibrary { fixture ->
            val original = fixture.roots.state.value
            val identity = fixture.database.libraryDao().libraryRootIdentity("active")
            val songs = fixture.external.songs.value
            val candidate = fixture.roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            val copies = listOf(SelectedLibraryContract.ROOT_MANIFEST, SelectedLibraryContract.ROOT_MANIFEST_BACKUP)
                .associateWith { name -> File(providerRoot(), name).readBytes() }
            try {
                val changed = org.json.JSONObject(copies.values.first().toString(Charsets.UTF_8))
                    .put("libraryId", java.util.UUID.randomUUID().toString()).toString().toByteArray(Charsets.UTF_8)
                copies.keys.forEach { File(providerRoot(), it).writeBytes(changed) }
                val incoming = LibraryRootManager(fixture.context, fixture.mutations, fixture.database.libraryDao())
                val failure = incoming.commitValidatedCandidateForDevelopment(candidate).exceptionOrNull()
                assertEquals("ROOT_MANIFEST_INVALID", failure?.message)
                assertEquals(original, fixture.roots.state.value)
                assertEquals(identity, fixture.database.libraryDao().libraryRootIdentity("active"))
                assertEquals(songs, fixture.external.songs.value)
            } finally { copies.forEach { (name, bytes) -> File(providerRoot(), name).writeBytes(bytes) } }
        }
    }

    @Test fun slowPublicationKeepsOtherPlaybackAdmissibleAndRootReplacementJoinsTheWriter() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val candidate = fixture.roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            val publisher = CanonicalPackagePublisher(fixture.context, fixture.roots, fixture.database.libraryDao(),
                fixture.mutations, fixture.bindings, fixture.external, BackgroundWorkCoordinator())
            val privateFile = File(fixture.context.cacheDir, "peer-publication.opus").apply { writeBytes(fixture.original.readBytes()) }
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            publisher.copyProgressForTest = {
                entered.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Publication gate timed out" }
            }
            val publication = async(Dispatchers.IO) { publisher.publishOriginal(root, "PUBLISH0001", privateFile, fixture.originalHash) }
            var replacement: Deferred<Result<LibraryRootState>>? = null
            try {
                withTimeout(5_000L) { entered.await() }
                assertTrue(withTimeout(2_000L) { fixture.external.playbackSnapshot() }.snapshotReady)
                assertEquals(VIDEO_ID, withTimeout(2_000L) { fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) }.id)
                val incoming = LibraryRootManager(fixture.context, fixture.mutations, fixture.database.libraryDao())
                replacement = async(Dispatchers.IO) { incoming.commitValidatedCandidateForDevelopment(candidate) }
                delay(100L)
                assertFalse("Root replacement crossed its active writer", replacement.isCompleted)
                assertEquals(root.authority(), checkNotNull(fixture.roots.captureHandle()).authority())
                release.countDown()
                withTimeout(5_000L) { checkNotNull(publication).await().getOrThrow(); replacement.await().getOrThrow() }
                assertArrayEquals(fixture.original.readBytes(), File(providerRoot(), "PUBLISH0001/original.opus").readBytes())
            } finally {
                release.countDown()
                withTimeout(5_000L) { publication.cancelAndJoin(); replacement?.cancelAndJoin() }
                privateFile.delete()
            }
        }
    }

    @Test fun knownChildNotificationRefreshesOnlyItsPackageAndPreservesItsNeighbor() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val otherId = "SELECT00001"
            val other = File(providerRoot(), otherId).apply { check(mkdir()) }
            File(other, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original.readBytes())
            seedCompleteOpusMetadata(other, otherId, fixture.original.readBytes())
            fixture.external.verifyPlaybackSong(root, otherId)
            val neighbor = fixture.external.songs.value.single { it.id == otherId }
            val manifest = File(fixture.folder, SelectedLibraryContract.ENRICHMENT)
            val changed = org.json.JSONObject(manifest.readText(Charsets.UTF_8))
            changed.getJSONObject("metadata").put("title", "Changed package only")
            changed.getJSONObject("install").put("generatedDigest", app.stemwise.enrichment.installedEnrichmentDigest(changed))
            manifest.writeText(changed.toString(), Charsets.UTF_8)
            val prepared = java.util.Collections.synchronizedList(mutableListOf<String>())
            fixture.external.packageProjectionPreparedForTest = { prepared += it }
            val uri = checkNotNull(fixture.roots.checkedAuthoritativeSongSnapshot(root, VIDEO_ID)?.file(SelectedLibraryContract.ENRICHMENT)).uri
            withTimeout(5_000L) { fixture.external.requestProviderRefresh(uri).join() }
            assertEquals(listOf(VIDEO_ID), prepared.toList())
            assertEquals("Changed package only", fixture.external.songs.value.single { it.id == VIDEO_ID }.title)
            assertEquals(neighbor, fixture.external.songs.value.single { it.id == otherId })
            assertTrue(fixture.external.playbackSnapshot().snapshotReady)
            fixture.external.packageProjectionPreparedForTest = { error("Unexpected refresh defect") }
            withTimeout(5_000L) { fixture.external.requestProviderRefresh(uri).join() }
            assertNotNull(fixture.external.storageRefreshState.value.errorCode)
            assertEquals(neighbor, fixture.external.songs.value.single { it.id == otherId })
            assertNull(fixture.escaped.get())
        }
    }

    @Test fun sourceManifestValidationDoesNotBlockAnotherSongAndRootReplacementJoinsIt() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val candidate = fixture.roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            val otherId = "SELECT00001"
            val other = File(providerRoot(), otherId).apply { check(mkdir()) }
            File(other, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original.readBytes())
            seedCompleteOpusMetadata(other, otherId, fixture.original.readBytes())
            fixture.external.verifyOriginalPlaybackSong(root, otherId)
            val publisher = CanonicalPackagePublisher(fixture.context, fixture.roots, fixture.database.libraryDao(),
                fixture.mutations, fixture.bindings, fixture.external, BackgroundWorkCoordinator())
            val media = CurrentSourcePublicationFixture.load(fixture.context, VIDEO_ID)
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_OPTIONAL_READ)
            val publication = async(Dispatchers.IO) { publisher.publishCustomSources(root, VIDEO_ID, media.manifest, "manifest-validation-peer") }
            val replacing = CompletableDeferred<Unit>()
            fixture.mutations.rootReplacementWaitingForTest = { replacing.complete(Unit) }
            var replacement: Deferred<Result<LibraryRootState>>? = null
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                assertEquals(otherId, withTimeout(2_000L) { fixture.external.verifyOriginalPlaybackSong(root, otherId) }.id)
                val incoming = LibraryRootManager(fixture.context, fixture.mutations, fixture.database.libraryDao())
                replacement = async(Dispatchers.IO) { incoming.commitValidatedCandidateForDevelopment(candidate) }
                withTimeout(5_000L) { replacing.await() }
                assertFalse("Root replacement crossed manifest validation", replacement.isCompleted)
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(10_000L) { checkNotNull(publication).await().getOrThrow(); replacement.await().getOrThrow() }
                assertArrayEquals(media.manifest.toBytes(), File(fixture.folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).readBytes())
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { publication.cancelAndJoin(); replacement?.cancelAndJoin() }
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
                fixture.mutations.rootReplacementWaitingForTest = null
            }
        }
    }

    @Test fun metadataCursorDoesNotBlockPlaybackAndRootReplacementJoinsItsFlight() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val candidate = fixture.roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_STORAGE_QUERY)
            val inventory = async(Dispatchers.IO) { fixture.external.refreshStorageInventory() }
            val replacing = CompletableDeferred<Unit>()
            fixture.mutations.rootReplacementWaitingForTest = { replacing.complete(Unit) }
            var replacement: Deferred<Result<LibraryRootState>>? = null
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                assertEquals(VIDEO_ID, withTimeout(2_000L) { fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) }.id)
                val incoming = LibraryRootManager(fixture.context, fixture.mutations, fixture.database.libraryDao())
                replacement = async(Dispatchers.IO) { incoming.commitValidatedCandidateForDevelopment(candidate) }
                withTimeout(5_000L) { replacing.await() }
                assertFalse("Root replacement crossed the storage cursor", replacement.isCompleted)
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(10_000L) { inventory.await(); replacement.await().getOrThrow() }
                assertFalse(fixture.external.storageRefreshState.value.refreshing)
                assertTrue(fixture.external.storageRefreshState.value.inventory.originalFilesFound > 0)
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { inventory.cancelAndJoin(); replacement?.cancelAndJoin() }
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
                fixture.mutations.rootReplacementWaitingForTest = null
            }
        }
    }

    @Test fun bundleArchiveCopyDoesNotBlockPlaybackAndRetainsItsRootUntilCancellationRetires() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val otherId = "SELECT00001"
            val other = File(providerRoot(), otherId).apply { check(mkdir()) }
            File(other, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original.readBytes())
            seedCompleteOpusMetadata(other, otherId, fixture.original.readBytes())
            fixture.external.verifyOriginalPlaybackSong(root, otherId)
            val otherBefore = other.listFiles()!!.associate { it.name to it.readBytes() }
            val importer = songImporter(fixture.context, fixture.roots, fixture.mutations, fixture.bindings,
                BackgroundWorkCoordinator(), fixture.database)
            val archive = writeArchive(fixture.context, "copy-lease.zip", payload("2026-08-09T12:00:00Z", "Copy lease", true), COVER_PNG)
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            importer.bundleArchiveCopyForTest = {
                entered.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Archive copy gate timed out" }
            }
            val importing = async(Dispatchers.IO) { importer.importStagedFiles(listOf(archive), expectedRoot = root.authority()) }
            val replaced = CompletableDeferred<Unit>()
            val replacing = CompletableDeferred<Unit>()
            fixture.mutations.rootReplacementWaitingForTest = { replacing.complete(Unit) }
            var replacement: Job? = null
            var sameSong: Deferred<app.stemwise.model.Song>? = null
            try {
                withTimeout(5_000L) { entered.await() }
                val reader = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID)
                }
                sameSong = reader
                assertNull("Same-song verification crossed archive ownership", withTimeoutOrNull(200L) { reader.await() })
                val neighbor = withTimeout(2_000L) { fixture.external.verifyOriginalPlaybackSong(root, otherId) }
                assertEquals(otherId, neighbor.id)
                assertEquals(fixture.originalHash, neighbor.sourceHash)
                replacement = launch(Dispatchers.IO) { fixture.mutations.withRootReplacement { replaced.complete(Unit) } }
                withTimeout(5_000L) { replacing.await() }
                assertFalse("Root replacement crossed an archive copy", replaced.isCompleted)
                importing.cancel()
                release.countDown()
                withTimeout(5_000L) { importing.join(); replacement.join(); replaced.await() }
                val verified = withTimeout(5_000L) { reader.await() }
                assertEquals(VIDEO_ID, verified.id)
                assertEquals(fixture.originalHash, verified.sourceHash)
                assertTrue(archive.isFile)
                fixture.assertPreserved()
                otherBefore.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(other, name).readBytes()) }
                assertEquals(root.authority(), checkNotNull(fixture.roots.captureHandle()).authority())
            } finally {
                release.countDown()
                withTimeout(5_000L) { sameSong?.cancelAndJoin(); importing.cancelAndJoin(); replacement?.cancelAndJoin() }
                archive.delete()
                fixture.mutations.rootReplacementWaitingForTest = null
            }
        }
    }

    @Test fun enrichmentCommitHashDoesNotBlockAnotherSongAndCancellationPreservesThePriorPackage() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val otherId = "SELECT00001"
            val other = File(providerRoot(), otherId).apply { check(mkdir()) }
            File(other, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original.readBytes())
            seedCompleteOpusMetadata(other, otherId, fixture.original.readBytes())
            fixture.external.verifyOriginalPlaybackSong(root, otherId)
            val phaseReady = CompletableDeferred<Unit>()
            val importer = songImporter(fixture.context, fixture.roots, fixture.mutations, fixture.bindings,
                BackgroundWorkCoordinator(), fixture.database) { phase ->
                if (phase == "ENRICHMENT_BEFORE_MEDIA_VERIFY") {
                    setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_ORIGINAL_READ)
                    phaseReady.complete(Unit)
                }
            }
            val archive = writeArchive(fixture.context, "commit-lease.zip", payload("2026-08-09T12:00:00Z", "Commit lease", true), COVER_PNG)
            val importing = async(Dispatchers.IO) { importer.importStagedFiles(listOf(archive), expectedRoot = root.authority()) }
            val replacing = CompletableDeferred<Unit>()
            val replaced = CompletableDeferred<Unit>()
            fixture.mutations.rootReplacementWaitingForTest = { replacing.complete(Unit) }
            var replacement: Job? = null
            try {
                withTimeout(5_000L) {
                    select<Unit> {
                        phaseReady.onAwait { }
                        importing.onAwait { result -> throw AssertionError("Import finished before its commit hash: $result") }
                    }
                }
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                val live = checkedAtomicJournalRecords(File(fixture.context.filesDir, "operation-journals"), "RECOVERY_PENDING")
                    .single { requireAtomicJournalEnvelope(it, "RECOVERY_PENDING").operation == "ENRICHMENT_INSTALL_V2" }
                val journalBytes = live.file.readBytes()
                withTimeout(2_000L) { fixture.external.recoverForColdOpen(root) }
                assertArrayEquals("Recovery consumed a live import journal", journalBytes, live.file.readBytes())
                assertEquals(otherId, withTimeout(2_000L) { fixture.external.verifyOriginalPlaybackSong(root, otherId) }.id)
                replacement = launch(Dispatchers.IO) { fixture.mutations.withRootReplacement { replaced.complete(Unit) } }
                withTimeout(5_000L) { replacing.await() }
                assertFalse("Root replacement crossed a commit hash", replaced.isCompleted)
                importing.cancel()
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(10_000L) { importing.join(); replacement.join(); replaced.await() }
                fixture.assertPreserved()
                assertTrue(archive.isFile)
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { importing.cancelAndJoin(); replacement?.cancelAndJoin() }
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
                fixture.mutations.rootReplacementWaitingForTest = null
                archive.delete()
            }
        }
    }

    @Test fun failedSourceCleanupHashDoesNotBlockPlaybackOrRetireItsRootEarly() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val otherId = "SELECT00001"
            val other = File(providerRoot(), otherId).apply { check(mkdir()) }
            File(other, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original.readBytes())
            seedCompleteOpusMetadata(other, otherId, fixture.original.readBytes())
            fixture.external.verifyOriginalPlaybackSong(root, otherId)
            val otherBefore = other.listFiles()!!.associate { it.name to it.readBytes() }
            val media = CurrentSourcePublicationFixture.load(fixture.context, VIDEO_ID)
            val lane = media.manifest.lanes.first { it.fileName.endsWith(".flac") }
            val failed = lane.copy(fileName = "replacement-${lane.fileName}")
            val retained = File(fixture.folder, failed.fileName).apply { writeBytes(media.parts.getValue(lane.fileName)) }
            val publisher = CanonicalPackagePublisher(fixture.context, fixture.roots, fixture.database.libraryDao(),
                fixture.mutations, fixture.bindings, fixture.external, BackgroundWorkCoordinator())
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_OPTIONAL_READ)
            val cleanup = async(Dispatchers.IO) { publisher.discardFailedSourceParts(root, VIDEO_ID,
                media.manifest.sourceSha256, setOf(failed.fileName), listOf(failed), setOf("failed-source-peer")) }
            val replacing = CompletableDeferred<Unit>()
            val replaced = CompletableDeferred<Unit>()
            fixture.mutations.rootReplacementWaitingForTest = { replacing.complete(Unit) }
            var replacement: Job? = null
            var sameSong: Deferred<app.stemwise.model.Song>? = null
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                val reader = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID)
                }
                sameSong = reader
                assertNull("Same-song verification crossed failed-source ownership", withTimeoutOrNull(200L) { reader.await() })
                val neighbor = withTimeout(2_000L) { fixture.external.verifyOriginalPlaybackSong(root, otherId) }
                assertEquals(otherId, neighbor.id)
                assertEquals(fixture.originalHash, neighbor.sourceHash)
                replacement = launch(Dispatchers.IO) { fixture.mutations.withRootReplacement { replaced.complete(Unit) } }
                withTimeout(5_000L) { replacing.await() }
                assertFalse("Root replacement crossed failed-source verification", replaced.isCompleted)
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(10_000L) { cleanup.await(); replacement.join(); replaced.await() }
                val verified = withTimeout(5_000L) { reader.await() }
                assertEquals(VIDEO_ID, verified.id)
                assertEquals(fixture.originalHash, verified.sourceHash)
                assertFalse(retained.exists())
                fixture.assertPreserved()
                otherBefore.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(other, name).readBytes()) }
                assertEquals(root.authority(), checkNotNull(fixture.roots.captureHandle()).authority())
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { sameSong?.cancelAndJoin(); cleanup.cancelAndJoin(); replacement?.cancelAndJoin() }
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
                fixture.mutations.rootReplacementWaitingForTest = null
            }
        }
    }

    @Test fun providerBatchRejectsInvalidPackageAndStillUpdatesItsNeighborThenWithdrawsDeletion() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val otherId = "SELECT00001"
            val other = File(providerRoot(), otherId).apply { check(mkdir()) }
            File(other, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original.readBytes())
            seedCompleteOpusMetadata(other, otherId, fixture.original.readBytes())
            fixture.external.verifyPlaybackSong(root, otherId)
            val firstUri = checkNotNull(fixture.roots.checkedAuthoritativeSongSnapshot(root, VIDEO_ID)?.file(SelectedLibraryContract.ENRICHMENT)).uri
            val secondUri = checkNotNull(fixture.roots.checkedAuthoritativeSongSnapshot(root, otherId)?.file(SelectedLibraryContract.ENRICHMENT)).uri
            fun changeTitle(folder: File, title: String) {
                val manifest = File(folder, SelectedLibraryContract.ENRICHMENT)
                val json = org.json.JSONObject(manifest.readText(Charsets.UTF_8))
                val metadata = payload("2026-08-09T12:00:00Z", title, includeArtwork = false).getJSONObject("metadata")
                metadata.getJSONArray("sources").getJSONObject(0).put("url",
                    "https://music.youtube.com/watch?v=${json.getJSONObject("source").getString("videoId")}")
                json.put("metadata", metadata)
                if (title.isEmpty()) json.put("schemaVersion", -1)
                json.getJSONObject("install").put("generatedDigest", app.stemwise.enrichment.installedEnrichmentDigest(json))
                manifest.writeText(json.toString(), Charsets.UTF_8)
            }
            changeTitle(fixture.folder, "")
            changeTitle(other, "Changed independent neighbor")
            fixture.external.requestProviderRefresh(firstUri)
            withTimeout(5_000L) { fixture.external.requestProviderRefresh(secondUri).join() }
            assertFalse(fixture.external.songs.value.any { it.id == VIDEO_ID })
            assertEquals("Changed independent neighbor", fixture.external.songs.value.single { it.id == otherId }.title)
            other.listFiles()!!.forEach { check(it.delete()) }; check(other.delete())
            withTimeout(5_000L) { fixture.external.requestProviderRefresh(secondUri).join() }
            assertFalse(fixture.external.songs.value.any { it.id == otherId })
            assertNull(fixture.escaped.get())
        }
    }

    @Test fun transientOptionalReadsRetainProjectionAndRetryWithoutAnotherProviderEvent() = runBlocking {
        for (fault in listOf(GoldenQaDocumentsProvider.FAULT_OPTIONAL_READ_ONCE,
            GoldenQaDocumentsProvider.FAULT_CUSTOM_MANIFEST_READ_ONCE,
            GoldenQaDocumentsProvider.FAULT_CUSTOM_MANIFEST_BACKUP_READ_ONCE)) withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val before = fixture.external.songs.value.single { it.id == VIDEO_ID }
            val stems = fixture.external.observeActiveStems(VIDEO_ID).first()
            val row = fixture.database.libraryDao().findSongById(VIDEO_ID)
            assertTrue(before.hasStems && stems.isNotEmpty())
            val manifest = File(fixture.folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST)
            // Even an invalid older backup must not be consulted for a transient canonical read.
            val backup = File(fixture.folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP)
            val manifestBytes = manifest.readBytes()
            if (fault == GoldenQaDocumentsProvider.FAULT_CUSTOM_MANIFEST_BACKUP_READ_ONCE) {
                backup.writeBytes(manifestBytes)
                manifest.writeText("{}", Charsets.UTF_8)
            } else backup.writeText("{}", Charsets.UTF_8)
            val affected = if (fault == GoldenQaDocumentsProvider.FAULT_OPTIONAL_READ_ONCE)
                File(fixture.folder, app.stemwise.data.parseCustomSourceManifest(manifest.readBytes(), VIDEO_ID,
                    fixture.originalHash).lanes.first().fileName) else manifest
            check(affected.setLastModified(System.currentTimeMillis() + 10_000L))
            val retryEntered = CompletableDeferred<Unit>()
            val allowRetry = CompletableDeferred<Unit>()
            var reads = 0
            fixture.external.beforeProviderPackageRefreshForTest = { id ->
                if (id == VIDEO_ID && ++reads == 2) { retryEntered.complete(Unit); allowRetry.await() }
            }
            try {
                setFault(fault)
                val snapshot = checkNotNull(fixture.roots.checkedAuthoritativeSongSnapshot(root, VIDEO_ID))
                val refresh = fixture.external.requestProviderRefresh(checkNotNull(snapshot.file(affected.name)).uri)
                withTimeout(8_000L) { retryEntered.await() }
                assertEquals(row, fixture.database.libraryDao().findSongById(VIDEO_ID))
                assertEquals(stems, fixture.external.observeActiveStems(VIDEO_ID).first())
                assertTrue(fixture.external.songs.value.single { it.id == VIDEO_ID }.hasStems)
                allowRetry.complete(Unit)
                withTimeout(8_000L) { refresh.join() }
                assertTrue(fixture.external.songs.value.single { it.id == VIDEO_ID }.hasStems)
                assertEquals(stems, fixture.external.observeActiveStems(VIDEO_ID).first())
                assertFalse(fixture.external.problems.value.any { it.packageId == VIDEO_ID })
                manifest.writeBytes(manifestBytes)
                fixture.assertPreserved()
            } finally {
                manifest.writeBytes(manifestBytes)
                allowRetry.complete(Unit)
                fixture.external.beforeProviderPackageRefreshForTest = null
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
                check(backup.delete())
            }
        }
    }

    @Test fun genuinelyCorruptOptionalManifestStillFallsBackToOriginalAndCanBeRepaired() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val manifest = File(fixture.folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST)
            val bytes = manifest.readBytes()
            try {
                manifest.writeText("{}", Charsets.UTF_8)
                fixture.external.reconcilePackage(root, VIDEO_ID)
                assertFalse(fixture.external.songs.value.single { it.id == VIDEO_ID }.hasStems)
                assertTrue(fixture.external.observeActiveStems(VIDEO_ID).first().isEmpty())
                assertTrue(fixture.external.problems.value.any { it.packageId == VIDEO_ID })
            } finally { manifest.writeBytes(bytes) }
            fixture.external.reconcilePackage(root, VIDEO_ID)
            assertTrue(fixture.external.songs.value.single { it.id == VIDEO_ID }.hasStems)
            assertTrue(fixture.external.observeActiveStems(VIDEO_ID).first().isNotEmpty())
            fixture.assertPreserved()
        }
    }

    @Test fun originalVerificationReadFailurePreservesRoomAndTheLiveProjection() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val before = fixture.external.songs.value.single { it.id == VIDEO_ID }
            val row = fixture.database.libraryDao().findSongById(VIDEO_ID)
            setFault(GoldenQaDocumentsProvider.FAULT_MANIFEST_READ_ONCE)
            val failure = runCatching { fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) }.exceptionOrNull()
            assertTrue("The actual provider read fault must be exercised: $failure", failure is java.io.IOException)
            assertEquals(row, fixture.database.libraryDao().findSongById(VIDEO_ID))
            assertEquals(before, fixture.external.songs.value.single { it.id == VIDEO_ID })
            assertEquals(before.sourceHash, fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID).sourceHash)
            assertFalse(fixture.external.problems.value.any { it.packageId == VIDEO_ID })
        }
    }

    @Test fun coldCatalogRetainsDisplayOnlyFailedReadsAndRetriesWithoutAnotherNotification() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            fixture.external.restoreProjectedLibrary(root)
            val retryStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.external.beforeProviderPackageRefreshForTest = { id ->
                if (id == VIDEO_ID) { retryStarted.complete(Unit); release.await() }
            }
            try {
                setFault(GoldenQaDocumentsProvider.FAULT_MANIFEST_READ_ONCE)
                fixture.external.validateProjectedLibrary(root)
                withTimeout(5_000L) { retryStarted.await() }
                val pending = fixture.external.songs.value.single { it.id == VIDEO_ID }
                assertNull(pending.sourcePath)
                assertNull(pending.sourceHash)
                assertTrue(fixture.external.problems.value.any { it.packageId == VIDEO_ID && it.code == "PACKAGE_READ_RETRY_PENDING" })
                assertTrue(fixture.database.libraryDao().findSongById(VIDEO_ID)?.canonicalExternal == true)
                val folderUri = checkNotNull(fixture.roots.checkedAuthoritativeSongSnapshot(root, VIDEO_ID)).directory.uri
                val retry = fixture.external.requestProviderRefresh(folderUri)
                release.complete(Unit)
                withTimeout(5_000L) { retry.join() }
                assertFalse(fixture.external.songs.value.single { it.id == VIDEO_ID }.sourcePath.isNullOrBlank())
                assertFalse(fixture.external.problems.value.any { it.packageId == VIDEO_ID })
                assertEquals(LibraryProjectionAvailability.VERIFIED, fixture.external.projectionAvailability.value)
                assertNull(fixture.escaped.get())
            } finally {
                fixture.external.beforeProviderPackageRefreshForTest = null
                release.complete(Unit)
            }
        }
    }

    @Test fun originalVerificationWaitsForTheActualEnrichmentWriterAndReadsItsCommittedMetadata() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val importer = songImporter(fixture.context, fixture.roots, fixture.mutations, fixture.bindings,
                BackgroundWorkCoordinator(), fixture.database) { stage ->
                if (stage == app.stemwise.enrichment.ENRICHMENT_COMMIT_AFTER_COVER_WRITE) {
                    entered.complete(Unit)
                    check(release.await(8, java.util.concurrent.TimeUnit.SECONDS)) { "Enrichment writer gate timed out" }
                }
            }
            val archive = writeArchive(fixture.context, "playback-writer.zip",
                payload("2026-11-04T12:00:00Z", "Committed after the writer", true), COVER_PNG)
            val writer = async(Dispatchers.IO) { importer.importStagedFiles(listOf(archive), expectedRoot = root.authority()) }
            var reader: Deferred<app.stemwise.model.Song>? = null
            try {
                withTimeout(5_000L) { entered.await() }
                reader = async(Dispatchers.IO) { fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) }
                assertNull("Playback read crossed the package writer", withTimeoutOrNull(200L) { checkNotNull(reader).await() })
                release.countDown()
                withTimeout(8_000L) { writer.await() }
                assertEquals("Committed after the writer", withTimeout(8_000L) { checkNotNull(reader).await() }.title)
                assertTrue(fixture.external.problems.value.none { it.packageId == VIDEO_ID })
            } finally {
                release.countDown()
                withTimeout(8_000L) { writer.cancelAndJoin(); reader?.cancelAndJoin() }
                archive.delete()
            }
        }
    }

    @Test fun transientProviderReadRetriesTheRetainedNotificationWithoutWithdrawingItsSong() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val before = fixture.external.songs.value.single { it.id == VIDEO_ID }
            val uri = checkNotNull(fixture.roots.checkedAuthoritativeSongSnapshot(root, VIDEO_ID)?.file(SelectedLibraryContract.ENRICHMENT)).uri
            setFault(GoldenQaDocumentsProvider.FAULT_MANIFEST_READ_ONCE)
            withTimeout(5_000L) { fixture.external.requestProviderRefresh(uri).join() }
            assertEquals(before, fixture.external.songs.value.single { it.id == VIDEO_ID })
            assertTrue(fixture.external.playbackSnapshot().snapshotReady)
            assertFalse(fixture.external.problems.value.any { it.packageId == VIDEO_ID })
            assertNull(fixture.escaped.get())
        }
    }

    @Test fun cachedCatalogAppearsBeforeMediaReadsAndBlockedBackgroundStemDoesNotPreventAnotherSelection() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val otherId = "SELECT00001"
            val other = File(providerRoot(), otherId).apply { check(mkdir()) }
            File(other, SelectedLibraryContract.ORIGINAL).writeBytes(fixture.original.readBytes())
            seedCompleteOpusMetadata(other, otherId, fixture.original.readBytes())
            fixture.external.verifyPlaybackSong(root, otherId)
            providerCall(GoldenQaDocumentsProvider.METHOD_MEASURE_READS, "true")
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            val started = android.os.SystemClock.elapsedRealtime()
            fixture.external.restoreProjectedLibrary(root)
            val cached = fixture.external.songs.value
            assertEquals(setOf(VIDEO_ID, otherId), cached.map { it.id }.toSet())
            assertTrue(cached.all { it.sourcePath == null && it.sourceHash == null && it.audioRevision.isEmpty() &&
                it.mediaRevision.isEmpty() && it.stemRevision.isEmpty() && !it.hasStems })
            assertTrue(fixture.external.playbackSnapshot().snapshotReady)
            assertEquals(0L, providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("readMediaBytes"))
            android.util.Log.i("StemwiseStorageGate", "cachedCatalog songs=${cached.size} mediaBytes=0 elapsedMs=" +
                (android.os.SystemClock.elapsedRealtime() - started))
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_OPTIONAL_READ)
            val background = async(Dispatchers.IO) { fixture.external.validateProjectedLibrary(root) }
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                val selected = withTimeout(2_000L) { fixture.external.verifyOriginalPlaybackSong(root, otherId) }
                assertEquals(fixture.originalHash, selected.sourceHash)
                assertFalse(selected.sourcePath.isNullOrBlank())
                assertEquals(setOf(VIDEO_ID, otherId), fixture.external.songs.value.map { it.id }.toSet())
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(10_000L) { background.await() }
                assertTrue(fixture.external.songs.value.all { !it.sourcePath.isNullOrBlank() })
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { background.cancelAndJoin() }
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
                providerCall(GoldenQaDocumentsProvider.METHOD_MEASURE_READS, "false")
            }
        }
    }

    @Test fun realImportFinalizationAndValidOriginalSelectionSerializeInBothOrders() = runBlocking {
        for (readerFirst in listOf(true, false)) withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val background = BackgroundWorkCoordinator()
            val committed = CompletableDeferred<Unit>()
            val releaseCommit = java.util.concurrent.CountDownLatch(1)
            val finalizing = CompletableDeferred<Unit>()
            val releaseFinal = java.util.concurrent.CountDownLatch(1)
            val importer = songImporter(fixture.context, fixture.roots, fixture.mutations,
                fixture.bindings, background, fixture.database) { stage ->
                if (readerFirst && stage == "BUNDLE_VERIFIED") {
                    committed.complete(Unit)
                    check(releaseCommit.await(8, java.util.concurrent.TimeUnit.SECONDS)) { "Import commit gate timed out" }
                }
            }
            // The importer's publisher uses its own repository; this hook is reached only by
            // the coordinator's public final reconciliation on fixture.external.
            fixture.external.packageProjectionPreparedForTest = {
                if (!readerFirst) {
                    finalizing.complete(Unit)
                    check(releaseFinal.await(8, java.util.concurrent.TimeUnit.SECONDS)) { "Import finalization gate timed out" }
                }
            }
            val coordinator = app.stemwise.enrichment.EnrichmentImportCoordinator(fixture.context, importer,
                fixture.external, background, fixture.roots, scope = fixture.scope)
            val archive = writeArchive(fixture.context, "real-finalization.zip",
                payload("2026-11-04T12:00:00Z", "Finalized without an unavailable selection", true), COVER_PNG)
            var reader: Deferred<app.stemwise.model.Song>? = null
            try {
                withTimeout(5_000L) { coordinator.awaitStartupScratchReady() }
                coordinator.start(listOf(android.net.Uri.fromFile(archive)))
                if (readerFirst) {
                    withTimeout(5_000L) { committed.await() }
                    setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_ORIGINAL_READ)
                    val requested = CompletableDeferred<Unit>()
                    reader = async(Dispatchers.IO) { requested.complete(Unit); fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) }
                    withTimeout(2_000L) { requested.await() }
                    assertNull(withTimeoutOrNull(200L) { checkNotNull(reader).await() })
                    releaseCommit.countDown()
                    assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                    assertNull("Import final reconciliation crossed the active Original reader",
                        withTimeoutOrNull(200L) { coordinator.state.first {
                            it is app.stemwise.enrichment.SharedEnrichmentImportState.Finished ||
                                it is app.stemwise.enrichment.SharedEnrichmentImportState.Failed } })
                    providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                } else {
                    withTimeout(8_000L) { finalizing.await() }
                    reader = async(Dispatchers.IO) { fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) }
                    assertNull("Original reader crossed final reconciliation ownership",
                        withTimeoutOrNull(200L) { checkNotNull(reader).await() })
                    releaseFinal.countDown()
                }
                assertEquals("Finalized without an unavailable selection", withTimeout(8_000L) { checkNotNull(reader).await() }.title)
                val terminal = withTimeout(8_000L) { coordinator.state.first {
                    it is app.stemwise.enrichment.SharedEnrichmentImportState.Finished ||
                        it is app.stemwise.enrichment.SharedEnrichmentImportState.Failed } }
                assertTrue(terminal.toString(), terminal is app.stemwise.enrichment.SharedEnrichmentImportState.Finished)
                assertTrue((terminal as app.stemwise.enrichment.SharedEnrichmentImportState.Finished).result.items.single().isSuccess)
                assertTrue(fixture.external.problems.value.none { it.packageId == VIDEO_ID })
                assertEquals(fixture.originalHash, fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID).sourceHash)
            } finally {
                releaseCommit.countDown(); releaseFinal.countDown()
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                fixture.external.packageProjectionPreparedForTest = null
                withTimeout(8_000L) { reader?.cancelAndJoin(); coordinator.cancelForLibraryChange() }
                archive.delete()
            }
        }
    }

    @Test fun unchangedPublicReconciliationSerializesWithOriginalVerificationInBothOrders() = runBlocking {
        for (readerFirst in listOf(true, false)) withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_ORIGINAL_READ)
            val first = async(Dispatchers.IO) {
                if (readerFirst) fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID)
                else fixture.external.reconcilePackage(root, VIDEO_ID)
            }
            var second: Deferred<Any>? = null
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                val started = CompletableDeferred<Unit>()
                second = async(Dispatchers.IO) {
                    started.complete(Unit)
                    if (readerFirst) fixture.external.reconcilePackage(root, VIDEO_ID)
                    else fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID)
                }
                withTimeout(2_000L) { started.await() }
                assertNull("Same package verification crossed active ownership", withTimeoutOrNull(200L) { checkNotNull(second).await() })
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(8_000L) { first.await(); checkNotNull(second).await() }
                assertEquals(fixture.originalHash, fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID).sourceHash)
                fixture.assertPreserved()
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(8_000L) { first.cancelAndJoin(); second?.cancelAndJoin() }
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
            }
        }
    }

    @Test fun samePackagePublicationWaitsForItsReaderAndPublishesAfterItRetires() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val expected = fixture.external.observeActiveStems(VIDEO_ID).first()
            assertTrue(expected.isNotEmpty())
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_OPTIONAL_READ)
            val verification = async(Dispatchers.IO) { fixture.external.verifyPlaybackStems(root, VIDEO_ID, expected) }
            var publication: Deferred<Unit>? = null
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                publication = async(Dispatchers.IO) {
                    fixture.mutations.withPackageMutation(root, VIDEO_ID, "TEST_ENRICHMENT", "serialized-reader",
                        requireExactRoot = { fixture.roots.requireSameLibrary(root) }, holdRootLock = false) { journal ->
                        val file = File(fixture.folder, SelectedLibraryContract.ENRICHMENT)
                        val json = org.json.JSONObject(file.readText(Charsets.UTF_8))
                        json.getJSONObject("metadata").put("title", "Replacement after reader")
                        json.getJSONObject("install").put("generatedDigest", app.stemwise.enrichment.installedEnrichmentDigest(json))
                        file.writeText(json.toString(), Charsets.UTF_8)
                        journal.complete()
                    }
                    fixture.external.reconcilePackage(root, VIDEO_ID)
                }
                assertNull("Writer crossed the exact package reader", withTimeoutOrNull(200L) { checkNotNull(publication).await() })
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                assertEquals(expected.sortedBy(StemAssetEntity::id), withTimeout(8_000L) { verification.await() }.sortedBy(StemAssetEntity::id))
                withTimeout(8_000L) { checkNotNull(publication).await() }
                assertEquals("Replacement after reader", fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID).title)
                assertTrue(fixture.external.songs.value.single { it.id == VIDEO_ID }.hasStems)
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(8_000L) { verification.cancelAndJoin(); publication?.cancelAndJoin() }
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
            }
        }
    }

    @Test fun aTombstonedCachedDisplayRowNeverBecomesAVerifiedPackageOrReactivatedBinding() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            fixture.external.restoreProjectedLibrary(root)
            assertEquals(VIDEO_ID, fixture.external.songs.value.single().id)
            assertNull(fixture.external.songs.value.single().sourcePath)
            fixture.mutations.withPackageMutation(root, VIDEO_ID, "TEST_TOMBSTONE", "cached-row-deletion",
                requireExactRoot = { fixture.roots.requireSameLibrary(root) }) { journal ->
                fixture.bindings.activateUnderPackageLock(root, "youtube-video:$VIDEO_ID", VIDEO_ID,
                    "cached-row-deletion", journal)
                fixture.bindings.markDeletedUnderPackageLock(root, "youtube-video:$VIDEO_ID", VIDEO_ID,
                    "cached-row-deletion", journal)
                journal.complete()
            }
            fixture.external.reconcile()
            assertTrue(fixture.external.songs.value.isEmpty())
            val inventory = fixture.external.storageRefreshState.value.inventory
            assertEquals(1, inventory.songsFound)
            assertEquals(0, inventory.verifiedSongPackages)
            assertEquals(0, inventory.verifiedOriginalFiles)
            assertEquals(0L, inventory.verifiedTotalBytes)
            assertTrue("youtube-video:$VIDEO_ID" in fixture.bindings.blockedSourceIds(root))
            assertTrue(fixture.external.playbackSnapshot().songs.isEmpty())
            fixture.assertPreserved()
        }
    }

    @Test fun providerNotificationFailurePublishesRetiresAndRetriesWithoutEscapingItsOwner() = runBlocking {
        withLibrary { fixture ->
            setFault(GoldenQaDocumentsProvider.FAULT_THROW)
            try { withTimeout(5_000L) { fixture.external.requestProviderRefresh().join() } }
            finally { setFault(GoldenQaDocumentsProvider.FAULT_NONE) }
            assertNull("Provider failure escaped the owned launch", fixture.escaped.get())
            assertFalse(fixture.external.storageRefreshState.value.refreshing)
            assertNotNull(fixture.external.storageRefreshState.value.errorCode)
            fixture.assertPreserved()
            if (!fixture.roots.state.value.ready) fixture.roots.commitValidatedCandidateForDevelopment(
                fixture.roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            withTimeout(5_000L) { fixture.external.requestProviderRefresh().join() }
            assertEquals(LibraryInventoryAvailability.VERIFIED, fixture.external.storageRefreshState.value.availability)
            assertNull(fixture.external.storageRefreshState.value.errorCode)
            assertFalse(fixture.external.storageRefreshState.value.refreshing)
            assertNull(fixture.escaped.get())
            fixture.assertPreserved()
        }
    }

    @Test fun librarySwitchRetiresDelayedScanOutsideTheMutationLane() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val source = fixture.database.libraryDao().currentSource(VIDEO_ID)
            val job = fixture.seedPendingJob(root)
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_ORIGINAL_READ)
            val verification = async(Dispatchers.IO) {
                runCatching { fixture.external.reconcilePackage(root, VIDEO_ID) }
            }
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                withTimeout(5_000L) { fixture.external.clearForLibrarySwitch() }
                assertTrue(fixture.external.songs.value.isEmpty())
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                assertEquals("SOURCE_AUDIO_CHANGED", withTimeout(5_000L) {
                    verification.await()
                }.exceptionOrNull()?.message)
                fixture.mutations.withRootMutation { assertTrue(fixture.external.songs.value.isEmpty()) }
                assertEquals(source, fixture.database.libraryDao().currentSource(VIDEO_ID))
                assertEquals(job, fixture.database.libraryDao().findJobById(job.id))
                fixture.assertPreserved()
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                try { withTimeout(5_000L) { verification.cancelAndJoin() } }
                finally { setFault(GoldenQaDocumentsProvider.FAULT_NONE) }
            }
        }
    }

    @Test fun librarySwitchRetiresDelayedScanWaitingForTheMutationLane() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val source = fixture.database.libraryDao().currentSource(VIDEO_ID)
            val job = fixture.seedPendingJob(root)
            val prepared = CompletableDeferred<Unit>()
            val acquired = CompletableDeferred<Unit>()
            val retiring = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.external.packageProjectionPreparedForTest = { prepared.complete(Unit) }
            fixture.external.reconcileScanRetiringForTest = { retiring.complete(Unit) }
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_ORIGINAL_READ)
            val verification = async(Dispatchers.IO) {
                runCatching { fixture.external.reconcilePackage(root, VIDEO_ID) }
            }
            var holder: Job? = null
            var switching: Deferred<Unit>? = null
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                holder = launch(Dispatchers.IO) {
                    fixture.mutations.withRootMutation {
                        acquired.complete(Unit)
                        release.await()
                    }
                }
                withTimeout(5_000L) { acquired.await() }
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { prepared.await() }
                assertFalse("Verified package bypassed held mutation ownership", verification.isCompleted)
                switching = async(Dispatchers.IO) { fixture.external.clearForLibrarySwitch() }
                withTimeout(5_000L) { retiring.await() }
                assertFalse("Library switch bypassed held mutation ownership", switching.isCompleted)
                release.complete(Unit)
                assertEquals("SOURCE_AUDIO_CHANGED", withTimeout(5_000L) {
                    verification.await()
                }.exceptionOrNull()?.message)
                withTimeout(5_000L) { switching.await(); holder.join() }
                assertTrue(fixture.external.songs.value.isEmpty())
                assertEquals(source, fixture.database.libraryDao().currentSource(VIDEO_ID))
                assertEquals(job, fixture.database.libraryDao().findJobById(job.id))
                fixture.assertPreserved()
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                release.complete(Unit)
                fixture.external.packageProjectionPreparedForTest = null
                fixture.external.reconcileScanRetiringForTest = null
                try { withTimeout(5_000L) {
                    verification.cancelAndJoin(); holder?.cancelAndJoin(); switching?.cancelAndJoin()
                } } finally { setFault(GoldenQaDocumentsProvider.FAULT_NONE) }
            }
        }
    }

    @Test fun originalVerificationDoesNotHoldTheLibraryMutationLaneWhileProviderBytesAreBlocked() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_ORIGINAL_READ)
            val verification = async(Dispatchers.IO) { fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) }
            var mutation: Deferred<Boolean>? = null
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                mutation = async(Dispatchers.IO) { fixture.mutations.withRootMutation { fixture.roots.requireSameLibrary(root); true } }
                assertTrue("Full media I/O kept an unrelated mutation waiting", withTimeout(750L) { mutation.await() })
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                val song = withTimeout(5_000L) { verification.await() }
                assertEquals(fixture.originalHash, song.sourceHash)
                fixture.assertPreserved()
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                try { withTimeout(5_000L) { verification.cancelAndJoin(); mutation?.cancelAndJoin() } }
                finally { setFault(GoldenQaDocumentsProvider.FAULT_NONE) }
            }
        }
    }

    @Test fun invalidatedProviderScanCannotActivateItsInFlightByteVerification() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_ORIGINAL_READ)
            val verification = async(Dispatchers.IO) { runCatching { fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) } }
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                fixture.external.invalidateProviderProjection()
                assertEquals(LibraryProjectionAvailability.UNAVAILABLE, fixture.external.projectionAvailability.value)
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                val result = withTimeout(5_000L) { verification.await() }
                assertEquals("SOURCE_BINDING_SCAN_CHANGED", result.exceptionOrNull()?.message)
                assertEquals(LibraryProjectionAvailability.UNAVAILABLE, fixture.external.projectionAvailability.value)
                fixture.assertPreserved()
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { verification.cancelAndJoin() }
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
            }
        }
    }

    @Test fun oldRootVerificationCannotPublishOrWithdrawTheReplacementSession() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_ORIGINAL_READ)
            val verification = async(Dispatchers.IO) { runCatching { fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) } }
            val replacementTree = android.provider.DocumentsContract.buildTreeDocumentUri(
                GoldenQaDocumentsProvider.AUTHORITY, "root/replacement-library")
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                val replacementDirectory = File(providerRoot(), "replacement-library").apply { check(mkdir()) }
                check(fixture.folder.copyRecursively(File(replacementDirectory, VIDEO_ID)))
                grantProviderTree(replacementTree)
                fixture.roots.reportRuntimeAccessFailure(root, SecurityException("isolated root replacement"))
                fixture.roots.commitValidatedCandidateForDevelopment(fixture.roots.validateCandidate(
                    replacementTree).getOrThrow()).getOrThrow()
                fixture.external.reconcile()
                val replacement = checkNotNull(fixture.roots.captureHandle())
                assertNotEquals(root.authority(), replacement.authority())
                assertEquals(replacementTree, replacement.treeUri)
                val published = fixture.external.songs.value.single { it.id == VIDEO_ID }
                assertEquals(fixture.originalHash, published.sourceHash)
                assertEquals(fixture.roots.checkedAuthoritativeSongSnapshot(replacement, VIDEO_ID)?.original?.uri.toString(),
                    published.sourcePath)
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                assertTrue(withTimeout(5_000L) { verification.await() }.isFailure)
                assertEquals(published, fixture.external.songs.value.single { it.id == VIDEO_ID })
                fixture.assertPreserved()
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { verification.cancelAndJoin() }
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
                targetContext.revokeUriPermission(targetContext.packageName, replacementTree,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    @Test fun externalReplacementWaitsForOldReadBeforeReconciliationPublishesValidBytes() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val correct = fixture.original.readBytes()
            val source = fixture.database.libraryDao().currentSource(VIDEO_ID)
            val job = fixture.seedPendingJob(root)
            fixture.original.writeBytes(correct.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            setFault(GoldenQaDocumentsProvider.FAULT_BLOCK_ORIGINAL_READ)
            val verification = async(Dispatchers.IO) { runCatching { fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) } }
            val replacement = File(fixture.folder, ".verification-replacement")
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                replacement.writeBytes(correct)
                java.nio.file.Files.move(replacement.toPath(), fixture.original.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                val publication = async(Dispatchers.IO) { fixture.external.reconcilePackage(root, VIDEO_ID) }
                assertNull(withTimeoutOrNull(200L) { publication.await() })
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                assertTrue(withTimeout(5_000L) { verification.await() }.isFailure)
                withTimeout(8_000L) { publication.await() }
                val published = fixture.external.songs.value.singleOrNull { it.id == VIDEO_ID }
                assertNotNull("Fresh verified replacement was not published", published)
                assertEquals(fixture.originalHash, published!!.sourceHash)
                assertEquals(source, fixture.database.libraryDao().currentSource(VIDEO_ID))
                assertEquals(job, fixture.database.libraryDao().findJobById(job.id))
                fixture.assertPreserved()
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                assertTrue(withTimeout(5_000L) { verification.await() }.isFailure)
                assertEquals("Old corrupt read withdrew the newer verified publication", published,
                    fixture.external.songs.value.singleOrNull { it.id == VIDEO_ID })
                assertEquals(source, fixture.database.libraryDao().currentSource(VIDEO_ID))
                assertEquals(job, fixture.database.libraryDao().findJobById(job.id))
                fixture.assertPreserved()
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                withTimeout(5_000L) { verification.cancelAndJoin() }
                setFault(GoldenQaDocumentsProvider.FAULT_NONE)
                replacement.delete()
                fixture.original.writeBytes(correct)
            }
        }
    }

    @Test fun repeatedOriginalSelectionsMeasureActualBytesAndNeverAdmitChangedContentOrOptionalLanes() = runBlocking {
        withLibrary { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            providerCall(GoldenQaDocumentsProvider.METHOD_MEASURE_READS, "true")
            try {
                repeat(2) {
                    providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
                    val song = fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID)
                    assertEquals(fixture.originalHash, song.sourceHash)
                    val metrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
                    assertTrue("Selection used metadata instead of actual Original bytes", metrics.getLong("readMediaBytes") >= fixture.original.length())
                    assertEquals(0L, metrics.getLong("openedOptionalMediaLengthBytes"))
                }
                val bytes = fixture.original.readBytes()
                val modified = fixture.original.lastModified()
                try {
                    fixture.original.writeBytes(bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                    check(fixture.original.setLastModified(modified))
                    assertTrue(runCatching { fixture.external.verifyOriginalPlaybackSong(root, VIDEO_ID) }.isFailure)
                    assertArrayEquals(bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }, fixture.original.readBytes())
                } finally { fixture.original.writeBytes(bytes); check(fixture.original.setLastModified(modified)) }
            } finally { providerCall(GoldenQaDocumentsProvider.METHOD_MEASURE_READS, "false") }
            fixture.assertPreserved()
        }
    }

    private suspend fun withLibrary(block: suspend (Fixture) -> Unit) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val fixture = Fixture()
        try { fixture.open(); block(fixture) } finally { setFault(GoldenQaDocumentsProvider.FAULT_NONE); fixture.close() }
    }
    private inner class Fixture {
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val mutations = PackageMutationCoordinator(context)
        val roots = LibraryRootManager(context, mutations, database.libraryDao())
        val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
        val escaped = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> escaped.set(error) })
        val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, BackgroundWorkCoordinator(), scope = scope)
        val folder = File(providerRoot(), VIDEO_ID)
        val original = File(folder, SelectedLibraryContract.ORIGINAL)
        val originalHash = sha256(original)
        val before = folder.listFiles()!!.associate { it.name to it.readBytes() }
        suspend fun open() {
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            external.reconcile()
        }
        suspend fun seedPendingJob(root: RootHandle): ProcessingJobEntity {
            val source = checkNotNull(database.libraryDao().currentSource(VIDEO_ID))
            return ProcessingJobEntity("verification-peer", VIDEO_ID, source.id, "SOURCE_SELECTION", "MVSEP",
                "fixture-plan", "PROCESSING", "accepted-remote", 1, null, null, null, 1L, 1L,
                root.libraryId).also { database.libraryDao().upsertJob(it) }
        }
        fun assertPreserved() { before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(folder, name).readBytes()) } }
        suspend fun close() { withTimeout(5_000L) { scope.coroutineContext[Job]?.cancelAndJoin() }; database.close() }
    }
}
