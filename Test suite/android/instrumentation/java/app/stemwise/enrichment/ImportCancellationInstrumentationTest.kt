package app.stemwise.enrichment

import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import org.junit.Rule
import app.stemwise.data.*
import app.stemwise.integration.withCancellableProviderAssetFileDescriptor
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.playback.engine.NativeMediaSource
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
internal class ImportCancellationInstrumentationTest : GoldenEnrichmentFixture() {
    @get:Rule val compose = app.stemwise.ui.createStemwiseComposeRule()
    @Test fun queuedCancelSettlesWithoutWaitingForTheHeavyWorkOwner() = runBlocking {
        withFixture { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val held = async(Dispatchers.IO) { fixture.background.withHeavyWork("Held work") {
                entered.complete(Unit); withTimeout(10_000L) { release.await() }
            } }
            try {
                withTimeout(5_000L) { entered.await() }
                fixture.coordinator.start(listOf(fixture.uri(fixture.archive("queued.zip"))))
                withTimeout(5_000L) { fixture.background.state.first { it.waitingWork == "Importing song data" } }
                fixture.coordinator.requestCancel()
                fixture.awaitCancelled()
                assertFalse(held.isCompleted)
                assertTrue(fixture.background.state.value.waitingWork == null)
                fixture.assertPreserved()
            } finally { release.complete(Unit); withTimeout(5_000L) { held.await() } }
        }
    }

    @Test fun cooperativeProviderOpenCancelsAndRetiresWithoutFixtureRelease() = runBlocking {
        withFixture { fixture ->
            val source = File(providerRoot(), "blocked-import.zip").apply { writeBytes(fixture.archive("source.zip").readBytes()) }
            providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_BLOCK_IMPORT_OPEN)
            try {
                fixture.coordinator.start(listOf(fixture.uri(source)))
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                fixture.coordinator.requestCancel()
                fixture.awaitCancelled()
                val provider = providerCall(GoldenQaDocumentsProvider.METHOD_IMPORT_INPUT_STATE)
                assertTrue("The passed CancellationSignal did not release openDocument", provider.getBoolean("released"))
                assertTrue("The provider open body is still active", provider.getBoolean("retired"))
                fixture.assertPreserved()
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_NONE)
                check(source.delete())
            }
        }
    }

    @Test fun cooperativeDescriptorOpenCancelsBeforeConsumerAndRetiresProvider() = runBlocking {
        withFixture { fixture ->
            val source = File(providerRoot(), "blocked-import.zip").apply { writeBytes(fixture.archive("source.zip").readBytes()) }
            providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_BLOCK_IMPORT_OPEN)
            val consumed = AtomicBoolean()
            val operation = launch(Dispatchers.IO) {
                fixture.context.contentResolver.withCancellableProviderAssetFileDescriptor(fixture.uri(source)) {
                    consumed.set(true)
                }
            }
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                operation.cancel()
                withTimeout(5_000L) { operation.join() }
                assertTrue(operation.isCancelled)
                assertFalse("A cancelled open reached its media consumer", consumed.get())
                val provider = providerCall(GoldenQaDocumentsProvider.METHOD_IMPORT_INPUT_STATE)
                assertTrue("The passed CancellationSignal did not release openDocument", provider.getBoolean("released"))
                assertTrue("The descriptor provider open body is still active", provider.getBoolean("retired"))
                fixture.assertPreserved()
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                try { withTimeout(5_000L) { operation.cancelAndJoin() } } finally {
                    providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_NONE)
                    check(source.delete())
                }
            }
        }
    }

    @Test fun cooperativeNativeSourceOpenCancelsBeforeConsumerAndRetiresProvider() = runBlocking {
        withFixture { fixture ->
            val source = File(providerRoot(), "blocked-import.zip").apply { writeBytes(fixture.archive("source.zip").readBytes()) }
            providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_BLOCK_IMPORT_OPEN)
            val cancellation = CancellationSignal()
            val consumed = AtomicBoolean()
            val operation = async(Dispatchers.IO) {
                runCatching {
                    NativeMediaSource.ContentUri(fixture.uri(source)).withDescriptor(fixture.context, cancellation) {
                        consumed.set(true)
                    }
                }.exceptionOrNull()
            }
            try {
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                cancellation.cancel()
                val failure = withTimeout(5_000L) { operation.await() }
                assertTrue("Native source lost the typed provider cancellation: $failure", failure is OperationCanceledException)
                assertFalse("A cancelled native open reached its extractor consumer", consumed.get())
                val provider = providerCall(GoldenQaDocumentsProvider.METHOD_IMPORT_INPUT_STATE)
                assertTrue("The native open did not pass its CancellationSignal", provider.getBoolean("released"))
                assertTrue("The native provider open body is still active", provider.getBoolean("retired"))
                fixture.assertPreserved()
            } finally {
                cancellation.cancel()
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                try { withTimeout(5_000L) { operation.cancelAndJoin() } } finally {
                    providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_NONE)
                    check(source.delete())
                }
            }
        }
    }

    @Test fun uncooperativeProviderReadKeepsCancellingOwnershipUntilTheWorkerRetires() = runBlocking {
        withFixture { fixture ->
            val source = File(providerRoot(), "blocked-import.zip").apply { writeBytes(fixture.archive("source.zip").readBytes()) }
            providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_BLOCK_IMPORT_READ)
            val cancelReturned = java.util.concurrent.CountDownLatch(1)
            val cancelFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            var cancelThread: Thread? = null
            try {
                val uri = DocumentsContract.buildDocumentUriUsingTree(GoldenQaDocumentsProvider.treeUri, "root/${source.name}")
                fixture.coordinator.start(listOf(uri))
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
                cancelThread = Thread {
                    try { fixture.coordinator.requestCancel() } catch (error: Throwable) {
                        cancelFailure.set(error)
                    } finally { cancelReturned.countDown() }
                }.apply { isDaemon = true; start() }
                assertTrue("Cancellation waited behind the blocked input", cancelReturned.await(3, java.util.concurrent.TimeUnit.SECONDS))
                cancelFailure.get()?.let { throw it }
                val cancelling = fixture.coordinator.state.value as SharedEnrichmentImportState.Running
                assertEquals("Cancelling import", cancelling.phase)
                val staging = File(fixture.context.noBackupFilesDir, "import-staging")
                val retained = staging.listFiles()!!.associate { it.name to it.readBytes() }
                assertTrue("The blocked worker lost its staging owner", retained.keys.any { it.endsWith(".part") })
                assertFalse(providerCall(GoldenQaDocumentsProvider.METHOD_IMPORT_INPUT_STATE).getBoolean("retired"))
                fixture.coordinator.start(listOf(fixture.uri(fixture.archive("replacement.zip"))))
                val rejected = fixture.coordinator.state.value as SharedEnrichmentImportState.Running
                assertEquals("Cancelling import", rejected.phase)
                assertEquals(cancelling.rejectedOffers + 1, rejected.rejectedOffers)
                assertEquals(retained.keys, staging.listFiles()!!.map { it.name }.toSet())
                retained.forEach { (name, bytes) -> assertArrayEquals(bytes, File(staging, name).readBytes()) }
                fixture.assertPublishedSongPreserved()
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                fixture.awaitCancelled()
                assertTrue(providerCall(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_RETIREMENT).getBoolean("retired"))
                fixture.assertPreserved()
            } finally {
                providerCall(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
                try {
                    cancelThread?.join(3_000L)
                    assertFalse("Cancellation worker did not retire", cancelThread?.isAlive == true)
                } finally {
                    providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_NONE)
                    check(source.delete())
                }
            }
        }
    }

    @Test fun cancelDuringRealZipExpansionRetiresScratchAndPreservesThePublishedSong() = runBlocking {
        withFixture { fixture ->
            val reached = AtomicBoolean()
            fixture.importer.extractionProgressForTest = {
                if (reached.compareAndSet(false, true)) {
                    assertTrue(File(fixture.context.cacheDir, "enrichment").isDirectory)
                    fixture.coordinator.requestCancel()
                }
            }
            fixture.coordinator.start(listOf(fixture.uri(fixture.archive("extract.zip"))))
            fixture.awaitCancelled()
            assertTrue(reached.get())
            fixture.assertPreserved()
        }
    }

    @Test fun cancelDuringOriginalInspectionStopsAtTheCurrentPageAndRetiresImportOwnership() = runBlocking {
        withFixture { fixture ->
            val pages = AtomicInteger()
            fixture.importer.originalInspectionCheckpointForTest = {
                if (pages.incrementAndGet() == 2) fixture.coordinator.requestCancel()
            }
            try {
                fixture.coordinator.start(listOf(fixture.uri(fixture.archive("inspect-cancel.zip"))))
                fixture.awaitCancelled()
                assertEquals("Cancelled Original inspection continued to another page", 2, pages.get())
                fixture.assertPreserved()
                assertFalse(fixture.background.state.value.heavyWorkActive)
                assertNull(fixture.background.state.value.activeWork)
            } finally { fixture.importer.originalInspectionCheckpointForTest = null }
        }
    }

    @Test fun cancellingAfterSameRootAccessLossStillSettlesAndCleansPrivateScratch() = runBlocking {
        withFixture { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val reached = AtomicBoolean()
            fixture.importer.extractionProgressForTest = {
                if (reached.compareAndSet(false, true)) {
                    assertTrue(checkedAtomicJournalRecords(File(fixture.context.filesDir, "operation-journals"),
                        "BUNDLE_RECOVERY_REQUIRED").none {
                        requireAtomicJournalEnvelope(it, "BUNDLE_RECOVERY_REQUIRED").operation == "SONG_BUNDLE_IMPORT_V1"
                    })
                    fixture.roots.reportRuntimeAccessFailure(root, SecurityException("isolated access loss"))
                    fixture.coordinator.requestCancel()
                }
            }
            fixture.coordinator.start(listOf(fixture.uri(fixture.archive("root-loss.zip"))))
            fixture.awaitCancelled()
            assertTrue(reached.get())
            assertFalse(fixture.roots.state.value.ready)
            assertEquals(root.libraryId, fixture.roots.state.value.libraryId)
            assertEquals(root.treeUri, fixture.roots.state.value.treeUri)
            assertEquals(root.epoch, fixture.roots.state.value.epoch)
            assertEquals("ROOT_PERMISSION_LOST", fixture.roots.state.value.errorCode)
            fixture.assertPreserved()
        }
    }

    @Test fun unavailableRootCancellationPreservesItsAcceptedBundleAndRejectsDamagedAuthority() = runBlocking {
        withFixture { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val reached = AtomicBoolean()
            val importer = songImporter(fixture.context, fixture.roots, fixture.mutations, fixture.bindings,
                fixture.background, fixture.database) { phase ->
                if (phase == "BUNDLE_ORIGINAL_PUBLISHED") {
                    reached.set(true)
                    throw EnrichmentCommitInterruption(phase)
                }
            }
            val staging = File(fixture.context.noBackupFilesDir, "import-staging").apply { mkdirs() }
            val archive = fixture.archive("retained-root-loss.zip")
                .copyTo(File(staging, "${java.util.UUID.randomUUID()}-0.zip"))
            assertFalse(importer.importStagedFiles(listOf(archive), expectedRoot = root.authority())
                .getOrThrow().items.single().isSuccess)
            assertTrue(reached.get())
            val record = checkedAtomicJournalRecords(File(fixture.context.filesDir, "operation-journals"),
                "BUNDLE_RECOVERY_REQUIRED").single {
                requireAtomicJournalEnvelope(it, "BUNDLE_RECOVERY_REQUIRED").operation == "SONG_BUNDLE_IMPORT_V1"
            }
            val envelope = requireAtomicJournalEnvelope(record, "BUNDLE_RECOVERY_REQUIRED")
            assertEquals("BUNDLE_STAGED", envelope.state)
            assertEquals("manual/${archive.name}", envelope.details.getString("archiveSource"))
            val archiveBytes = archive.readBytes()
            val journalBytes = record.file.readBytes()
            try {
                importer.cancelBundleArchiveInputs(root.authority().copy(epoch = root.epoch + 1L), listOf(archive))
                assertArrayEquals(archiveBytes, archive.readBytes())
                assertArrayEquals(journalBytes, record.file.readBytes())

                fixture.roots.reportRuntimeAccessFailure(root, SecurityException("accepted bundle access loss"))
                val unavailable = fixture.roots.state.value
                val denied = runCatching { importer.cancelBundleArchiveInputs(root.authority(), listOf(archive)) }
                    .exceptionOrNull()
                assertTrue(denied is EnrichmentImportException)
                assertEquals("LIBRARY_CHANGED", (denied as EnrichmentImportException).code)
                assertArrayEquals(archiveBytes, archive.readBytes())
                assertArrayEquals(journalBytes, record.file.readBytes())
                assertEquals(unavailable, fixture.roots.state.value)
                assertEquals(root.libraryId, unavailable.libraryId)
                assertEquals(root.treeUri, unavailable.treeUri)
                assertEquals(root.epoch, unavailable.epoch)
                assertEquals("ROOT_PERMISSION_LOST", unavailable.errorCode)

                val malformed = "{damaged authority".toByteArray(Charsets.UTF_8)
                record.file.writeBytes(malformed)
                val damaged = runCatching { importer.cancelBundleArchiveInputs(root.authority(), listOf(archive)) }
                    .exceptionOrNull()
                assertEquals("BUNDLE_RECOVERY_REQUIRED", damaged?.message)
                assertArrayEquals(malformed, record.file.readBytes())
                assertArrayEquals(archiveBytes, archive.readBytes())
                assertEquals(unavailable, fixture.roots.state.value)
                fixture.assertPublishedSongPreserved()
            } finally {
                record.file.writeBytes(journalBytes)
            }
        }
    }

    @Test fun cancelAfterTheFirstSortedSongCommitRetainsItAndDoesNotApplyTheNextSong() = runBlocking {
        withFixture(cancelOnCommit = true) { fixture ->
            val first = fixture.archive("first.zip", "2026-08-09T12:00:00Z")
            val secondId = "ZZZZZZZZZZ1"
            val second = fixture.archive("second.zip", "2026-08-09T13:00:00Z", secondId)
            fixture.coordinator.start(listOf(fixture.uri(first), fixture.uri(second)))
            try {
                withTimeout(15_000L) { fixture.firstCommitCancellationRequested.await() }
            } catch (failure: TimeoutCancellationException) {
                val state = fixture.coordinator.state.value
                val diagnostic = when (state) {
                    is SharedEnrichmentImportState.Running -> "Running(phase=${state.phase}, " +
                        "completed=${state.completed}/${state.total}, retirementPending=${state.retirementPending})"
                    is SharedEnrichmentImportState.Finished -> "Finished(outcomes=" +
                        state.result.items.map { it.status to it.errorCode } + ")"
                    is SharedEnrichmentImportState.Failed -> "Failed(rejectedOffers=${state.rejectedOffers})"
                    is SharedEnrichmentImportState.AwaitingConfirmation -> "AwaitingConfirmation"
                    is SharedEnrichmentImportState.Cancelled -> "Cancelled"
                    SharedEnrichmentImportState.Idle -> "Idle"
                }
                throw AssertionError("First sorted commit cancellation was not requested; " +
                    "commitReached=${fixture.commitReached.get()}, state=$diagnostic", failure)
            }
            fixture.awaitCancelled()
            assertTrue(fixture.commitReached.get())
            val installed = JSONObject(File(fixture.songDirectory, "song.json").readText(Charsets.UTF_8))
            assertEquals("2026-08-09T12:00:00Z", installed.getString("generatedAt"))
            assertEquals(64, fixture.external.songs.value.single { it.id == VIDEO_ID }.mediaRevision.length)
            assertFalse(File(fixture.songDirectory.parentFile, secondId).exists())
            assertTrue(fixture.external.songs.value.none { it.id == secondId })
            assertArrayEquals(fixture.before.getValue("original.opus"), File(fixture.songDirectory, "original.opus").readBytes())
            fixture.assertNoScratch()
        }
    }

    @Test fun compressedZipProviderMimeIsAdmittedByThePickerAndNormalImportStaging() = runBlocking {
        withFixture { fixture ->
            val archive = fixture.archive("compressed-import.zip")
            val uri = fixture.uri(archive)
            val advertised = fixture.context.contentResolver.getType(uri)
            assertEquals("application/x-zip-compressed", advertised)
            val picker = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
                .createIntent(fixture.context, app.stemwise.SONG_ZIP_MIME_TYPES.toTypedArray())
            assertTrue(picker.getStringArrayExtra(android.content.Intent.EXTRA_MIME_TYPES).orEmpty().contains(advertised))
            fixture.coordinator.start(listOf(uri))
            val result = withTimeout(15_000L) { fixture.coordinator.state.first { it is SharedEnrichmentImportState.Finished } }
                as SharedEnrichmentImportState.Finished
            assertEquals(1, result.result.items.count { it.isSuccess })
            fixture.assertNoScratch()
        }
    }

    @Test fun oneSharedConfirmationContinuesToPublicationForTheAcceptedOperation() = runBlocking {
        withFixture { fixture ->
            val archive = fixture.archive("one-confirmation.zip")
            val expectedBytes = java.util.zip.ZipFile(archive).use { zip -> zip.entries().asSequence().sumOf { it.size } }
            fixture.coordinator.offer(listOf(fixture.uri(archive)))
            val accepted = fixture.coordinator.state.value as SharedEnrichmentImportState.AwaitingConfirmation
            compose.setContent {
                app.stemwise.ui.theme.StemwiseTheme {
                    app.stemwise.ui.QueueScreen(onImportEnrichment = {},
                        playlistSettings = PlaylistSettings(checkNotNull(app.stemwise.integration.PlaylistLinks.parse(
                            "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
                        syncStatus = PlaylistSyncStatus(), onSync = { true }, bridgeState = app.stemwise.bridge.BridgeUiState(),
                        bridgeTasks = emptyList(), onOpenAddSong = {}, onReviewDraft = {},
                        onRetryBridgeTask = { Result.success(Unit) }, onForgetBridgeTask = { Result.success(Unit) },
                        onRetryJob = {}, sharedImportState = fixture.coordinator.state.collectAsState().value,
                        onConfirmSharedImport = fixture.coordinator::confirm)
                }
            }
            compose.onNodeWithText("Review import").performScrollTo().performClick()
            compose.onNodeWithText("Import shared files?").assertIsDisplayed()
            compose.onNodeWithText("adds or updates matching Library songs", substring = true).assertIsDisplayed()
            assertEquals(fixture.before.getValue("song.json").toList(), File(fixture.songDirectory, "song.json").readBytes().toList())
            compose.onNodeWithText("Import", substring = false).performClick()
            val result = withTimeout(15_000L) { fixture.coordinator.state.first { it is SharedEnrichmentImportState.Finished } }
                as SharedEnrichmentImportState.Finished
            assertEquals(1, result.result.items.count { it.isSuccess })
            assertEquals("A complete child is materialized once, then the validated staging is reused", expectedBytes, result.result.materializedBytes)
            assertEquals("2026-08-09T12:00:00Z", JSONObject(File(fixture.songDirectory, "song.json").readText()).getString("generatedAt"))
            assertTrue(accepted.operationId.isNotBlank())
            fixture.assertNoScratch()
        }
    }

    @Test fun freshOfferDiscardDoesNotReadItsSourceAndImmediatelyAdmitsAnotherSelection() = runBlocking {
        withFixture { fixture ->
            val a = fixture.uri(fixture.archive("fresh-a.zip"))
            val b = fixture.uri(fixture.archive("fresh-b.zip"))
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            fixture.coordinator.offer(listOf(a))
            val first = fixture.coordinator.state.value as SharedEnrichmentImportState.AwaitingConfirmation
            fixture.coordinator.discardPendingImport(first.operationId)
            assertTrue(fixture.coordinator.state.value is SharedEnrichmentImportState.Idle)
            fixture.coordinator.offer(listOf(b))
            val second = fixture.coordinator.state.value as SharedEnrichmentImportState.AwaitingConfirmation
            assertEquals(listOf(b), second.sources)
            assertNotEquals(first.operationId, second.operationId)
            assertEquals(0L, providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("openDocumentCalls"))
            fixture.assertNoScratch()
        }
    }

    @Test fun unreadableImportJournalSurvivesTaskRemovalWithEveryCopiedArchive() = runBlocking {
        withFixture { fixture ->
            val staging = File(fixture.context.noBackupFilesDir, "import-staging").apply { mkdirs() }
            val copied = fixture.archive("retained-unreadable.zip").copyTo(File(staging, "${java.util.UUID.randomUUID()}-0.zip"))
            val root = checkNotNull(fixture.roots.captureHandle())
            val journal = File(staging, ENRICHMENT_STAGING_JOURNAL_NAME).apply {
                writeText(JSONObject().put("schemaVersion", 3).put("libraryId", root.libraryId)
                    .put("treeUri", root.treeUri.toString()).put("rootEpoch", root.epoch)
                    .put("phase", "awaiting_confirmation").put("stagingComplete", true)
                    .put("stagedFiles", org.json.JSONArray().put(JSONObject().put("path", copied.name)
                        .put("sourceName", "retained-unreadable.zip"))).toString(), Charsets.UTF_8)
            }
            val bytes = copied.readBytes()
            val authority = journal.readBytes()
            try {
                android.system.Os.chmod(journal.absolutePath, 0)
                fixture.coordinator.onTaskRemoved()
                withTimeout(5_000L) { fixture.coordinator.state.first { it is SharedEnrichmentImportState.Failed } }
                android.system.Os.chmod(journal.absolutePath, 384)
                assertArrayEquals(bytes, copied.readBytes())
                assertArrayEquals(authority, journal.readBytes())
                fixture.assertPublishedSongPreserved()
            } finally {
                android.system.Os.chmod(journal.absolutePath, 384)
                check(journal.delete())
                check(copied.delete())
            }
        }
    }

    @Test fun malformedImportJournalSurvivesTaskRemovalWithEveryCopiedArchive() = runBlocking {
        withFixture { fixture ->
            val staging = File(fixture.context.noBackupFilesDir, "import-staging").apply { mkdirs() }
            val copied = fixture.archive("retained-invalid.zip").copyTo(File(staging, "${java.util.UUID.randomUUID()}-0.zip"))
            val journal = File(staging, ENRICHMENT_STAGING_JOURNAL_NAME).apply { writeText("{unreadable authority", Charsets.UTF_8) }
            val bytes = copied.readBytes()
            val authority = journal.readBytes()
            try {
                fixture.coordinator.onTaskRemoved()
                withTimeout(5_000L) { fixture.coordinator.state.first { it is SharedEnrichmentImportState.Failed } }
                assertArrayEquals(bytes, copied.readBytes())
                assertArrayEquals(authority, journal.readBytes())
                fixture.assertPublishedSongPreserved()
            } finally {
                check(journal.delete())
                check(copied.delete())
            }
        }
    }

    @Test fun restoredPendingReviewDefersThenDiscardsOnlyItsExactIntakeAndAdmitsAnotherZip() = runBlocking {
        withFixture { fixture ->
            val staging = File(fixture.context.noBackupFilesDir, "import-staging").apply { mkdirs() }
            val staged = fixture.archive("restored-a.zip").copyTo(File(staging, "${java.util.UUID.randomUUID()}-0.zip"))
            val root = checkNotNull(fixture.roots.captureHandle())
            File(staging, ENRICHMENT_STAGING_JOURNAL_NAME).writeText(JSONObject().put("schemaVersion", 3)
                .put("libraryId", root.libraryId).put("treeUri", root.treeUri.toString()).put("rootEpoch", root.epoch)
                .put("phase", "awaiting_confirmation").put("stagingComplete", true).put("completed", 0).put("total", 0)
                .put("stagedFiles", org.json.JSONArray().put(JSONObject().put("path", staged.name)
                    .put("sourceName", "restored-a.zip"))).toString(), Charsets.UTF_8)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val restored = EnrichmentImportCoordinator(fixture.context, fixture.importer, fixture.external,
                fixture.background, fixture.roots, scope, fixture.cleanupMutex)
            try {
                restored.awaitStartupScratchReady()
                val a = restored.state.value as SharedEnrichmentImportState.AwaitingConfirmation
                compose.setContent { app.stemwise.ui.theme.StemwiseTheme {
                    app.stemwise.ui.QueueScreen(onImportEnrichment = {},
                        playlistSettings = PlaylistSettings(checkNotNull(app.stemwise.integration.PlaylistLinks.parse(
                            "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
                        syncStatus = PlaylistSyncStatus(), onSync = { true }, bridgeState = app.stemwise.bridge.BridgeUiState(),
                        bridgeTasks = emptyList(), onOpenAddSong = {}, onReviewDraft = {},
                        onRetryBridgeTask = { Result.success(Unit) }, onForgetBridgeTask = { Result.success(Unit) },
                        onRetryJob = {}, sharedImportState = restored.state.collectAsState().value,
                        onConfirmSharedImport = restored::confirm, onDiscardSharedImport = restored::discardPendingImport)
                } }
                compose.onNodeWithText("Review import").performScrollTo().performClick()
                compose.onNodeWithText("Not now").performClick()
                assertTrue(staged.isFile)
                assertEquals(a.operationId, (restored.state.value as SharedEnrichmentImportState.AwaitingConfirmation).operationId)
                compose.onNodeWithText("Review import").performClick()
                compose.onNodeWithText("Discard import").performClick()
                withTimeout(5_000L) { restored.state.first { it is SharedEnrichmentImportState.Idle } }
                assertFalse(staged.exists())
                fixture.assertPublishedSongPreserved()
                restored.offer(listOf(fixture.uri(fixture.archive("new-b.zip"))))
                val b = restored.state.value as SharedEnrichmentImportState.AwaitingConfirmation
                assertNotEquals(a.operationId, b.operationId)
                restored.discardPendingImport(a.operationId)
                assertEquals(b, restored.state.value)
                restored.confirm()
                withTimeout(15_000L) { restored.state.first { it is SharedEnrichmentImportState.Finished } }
                fixture.assertNoScratch()
            } finally { restored.cancelForLibraryChange(); scope.cancel() }
        }
    }

    @Test fun failedTaskRemovalCleanupRetainsPendingOfferAndConfirmationUntilExplicitRetry() = runBlocking {
        withFixture { fixture ->
            val staging = File(fixture.context.noBackupFilesDir, "import-staging").apply { mkdirs() }
            val blocked = File(staging, "unowned-directory").apply { check(mkdir()) }
            fixture.coordinator.onTaskRemoved()
            withTimeout(5_000L) { fixture.coordinator.state.first { it is SharedEnrichmentImportState.Failed } }
            var committed = 0
            fixture.importer.bundleCommittedForTest = { committed++ }
            fixture.coordinator.offer(listOf(fixture.uri(fixture.archive("after-cleanup-retry.zip"))))
            fixture.coordinator.confirm()
            assertTrue(fixture.coordinator.state.value is SharedEnrichmentImportState.Failed)
            assertTrue(blocked.delete())
            fixture.coordinator.retryStartupScratch()
            withTimeout(15_000L) { fixture.coordinator.state.first { it is SharedEnrichmentImportState.Finished } }
            assertEquals(1, committed)
            fixture.assertNoScratch()
        }
    }

    @Test fun malformedNewestEncodedAudioDoesNotSupersedeAUsableOlderBundle() = runBlocking {
        withFixture { fixture ->
            val source = app.stemwise.data.CurrentSourcePublicationFixture.load(fixture.context, VIDEO_ID)
            val goodFlac = source.parts.entries.first { it.key.endsWith(".flac") }.value
            var end = 4
            do {
                val last = goodFlac[end].toInt() and 0x80 != 0
                val size = ((goodFlac[end + 1].toInt() and 255) shl 16) or
                    ((goodFlac[end + 2].toInt() and 255) shl 8) or (goodFlac[end + 3].toInt() and 255)
                end += 4 + size
            } while (!last)
            val malformed = File(fixture.context.cacheDir, "original.flac").apply { writeBytes(goodFlac.copyOf(end)) }
            val facts = inspectOriginalAudio(malformed)
            assertTrue("Fixture must pass header admission", facts.audibleFrames48k > 0)
            assertTrue(runCatching { app.stemwise.integration.verifyImportedOriginalPcm(fixture.context,
                malformed, facts.audibleFrames48k) }.isFailure)
            val newest = writeArchive(fixture.context, "bad-newest.zip", payload("2026-08-09T13:00:00Z",
                "Malformed newest", includeArtwork = true), COVER_PNG, malformed)
                .copyTo(File(providerRoot(), "bad-newest.zip"), overwrite = true)
            fixture.coordinator.start(listOf(fixture.uri(fixture.archive("usable-older.zip")), fixture.uri(newest)))
            val result = withTimeout(15_000L) { fixture.coordinator.state.first { it is SharedEnrichmentImportState.Finished } }
                as SharedEnrichmentImportState.Finished
            assertEquals(0, result.result.supersededCount)
            assertEquals(1, result.result.items.count { it.isSuccess })
            assertEquals(1, result.result.items.count { !it.isSuccess })
            assertEquals("2026-08-09T12:00:00Z", JSONObject(File(fixture.songDirectory, "song.json").readText()).getString("generatedAt"))
            fixture.assertNoScratch()
        }
    }

    @Test fun providerLossDuringApplyAndFinalReconciliationSettlesAndAllowsAnotherImport() = runBlocking {
        for (duringReconcile in listOf(false, true)) withFixture { fixture ->
            val reached = AtomicBoolean()
            suspend fun loseAccess() {
                if (reached.compareAndSet(false, true)) {
                    fixture.roots.reportRuntimeAccessFailure(checkNotNull(fixture.roots.captureHandle()),
                        SecurityException("isolated provider access loss"))
                }
            }
            if (duringReconcile) fixture.external.canonicalPackageVerifiedForTest = { loseAccess() }
            else fixture.importer.extractionProgressForTest = { loseAccess() }
            try {
                fixture.coordinator.start(listOf(fixture.uri(fixture.archive("lost-provider.zip"))))
                val settled = withTimeout(5_000L) {
                    fixture.coordinator.state.first { it is SharedEnrichmentImportState.Failed }
                }
                assertTrue(reached.get())
                assertFalse(fixture.roots.state.value.ready)
                fixture.coordinator.requestCancel()
                assertEquals(settled, fixture.coordinator.state.value)
                fixture.importer.extractionProgressForTest = null
                fixture.external.canonicalPackageVerifiedForTest = null
                fixture.roots.commitValidatedCandidateForDevelopment(
                    fixture.roots.validateCandidate(fixture.libraryTree).getOrThrow()).getOrThrow()
                fixture.external.reconcile()
                fixture.coordinator.dismiss()
                withTimeout(5_000L) { fixture.coordinator.state.first { it is SharedEnrichmentImportState.Idle } }
                fixture.coordinator.start(listOf(fixture.uri(fixture.archive("retry-provider.zip", "2026-08-09T13:00:00Z"))))
                withTimeout(15_000L) { fixture.coordinator.state.first { it is SharedEnrichmentImportState.Finished } }
                assertArrayEquals(fixture.before.getValue("original.opus"),
                    File(fixture.songDirectory, "original.opus").readBytes())
                fixture.assertNoScratch()
            } finally {
                fixture.importer.extractionProgressForTest = null
                fixture.external.canonicalPackageVerifiedForTest = null
            }
        }
    }

    @Test fun stagedDiscardCancelledBeforeDispatchRetiresAndAcceptsTheNextImport() = runBlocking {
        assertStagedDiscardCancellationRetires(beforeDispatch = true)
    }

    @Test fun stagedDiscardCancelledWhileCleanupIsHeldRetiresAndPreservesCommittedSongs() = runBlocking {
        assertStagedDiscardCancellationRetires(beforeDispatch = false)
    }

    private suspend fun assertStagedDiscardCancellationRetires(beforeDispatch: Boolean) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val dispatcher = PausableImportDispatcher()
        val operationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
        val fixture = Fixture(false, operationScope)
        var restored: EnrichmentImportCoordinator? = null
        try {
            fixture.open()
            val staging = File(fixture.context.noBackupFilesDir, "import-staging").apply { mkdirs() }
            val staged = fixture.archive("discard.zip").copyTo(File(staging, "${java.util.UUID.randomUUID()}-0.zip"))
            val root = checkNotNull(fixture.roots.captureHandle())
            File(staging, ENRICHMENT_STAGING_JOURNAL_NAME).writeText(JSONObject().put("schemaVersion", 3)
                .put("libraryId", root.libraryId).put("treeUri", root.treeUri.toString()).put("rootEpoch", root.epoch)
                .put("phase", "awaiting_confirmation").put("stagingComplete", true).put("completed", 0).put("total", 0)
                .put("stagedFiles", org.json.JSONArray().put(JSONObject().put("path", staged.name)
                    .put("sourceName", "restored-discard.zip"))).toString())
            val coordinator = EnrichmentImportCoordinator(fixture.context, fixture.importer, fixture.external,
                fixture.background, fixture.roots, operationScope, fixture.cleanupMutex).also { restored = it }
            coordinator.awaitStartupScratchReady()
            assertTrue(coordinator.state.value is SharedEnrichmentImportState.AwaitingConfirmation)
            if (beforeDispatch) dispatcher.paused = true else fixture.cleanupMutex.lock()
            coordinator.dismiss()
            assertTrue(coordinator.state.value is SharedEnrichmentImportState.Running)
            if (!beforeDispatch) withTimeout(3_000L) { fixture.cleanupMutex.waiting.await() }
            coordinator.requestCancel()
            if (beforeDispatch) dispatcher.release() else fixture.cleanupMutex.unlock()
            withTimeout(3_000L) { coordinator.state.first { it !is SharedEnrichmentImportState.Running } }
            fixture.assertPreserved()
            coordinator.start(listOf(fixture.uri(fixture.archive("after-discard.zip"))))
            withTimeout(15_000L) { coordinator.state.first { it is SharedEnrichmentImportState.Finished } }
            fixture.assertNoScratch()
        } finally {
            dispatcher.release()
            if (fixture.cleanupMutex.isLocked) fixture.cleanupMutex.unlock()
            try { restored?.cancelForLibraryChange(); fixture.close() } finally { operationScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
    }

    private class ObservedCleanupMutex(
        private val delegate: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex()
    ) : kotlinx.coroutines.sync.Mutex by delegate {
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun lock(owner: Any?) {
            if (delegate.isLocked) waiting.complete(Unit)
            delegate.lock(owner)
        }
    }

    private class PausableImportDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        @Volatile var paused = false
        private val pending = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (paused) pending.add(block) else Dispatchers.IO.dispatch(context, block)
        }
        fun release() {
            paused = false
            while (true) { val block = pending.poll() ?: break; Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, block) }
        }
    }

    private suspend fun withFixture(cancelOnCommit: Boolean = false, block: suspend (Fixture) -> Unit) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val fixture = Fixture(cancelOnCommit)
        try { fixture.open(); block(fixture) } finally { fixture.close() }
    }

    private inner class Fixture(cancelOnCommit: Boolean,
        operationScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)) {
        val cleanupMutex = ObservedCleanupMutex()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val mutations = PackageMutationCoordinator(context)
        val roots = LibraryRootManager(context, mutations, database.libraryDao())
        val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
        val background = BackgroundWorkCoordinator()
        val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, background)
        val commitReached = AtomicBoolean()
        val firstCommitCancellationRequested = CompletableDeferred<Unit>()
        val importer: SongEnrichmentImporter = songImporter(context, roots, mutations, bindings, background, database).apply {
            bundleCommittedForTest = {
                if (cancelOnCommit && commitReached.compareAndSet(false, true)) {
                    coordinator.requestCancel()
                    firstCommitCancellationRequested.complete(Unit)
                }
            }
        }
        val coordinator: EnrichmentImportCoordinator = EnrichmentImportCoordinator(context, importer, external, background, roots, operationScope, cleanupMutex)
        lateinit var before: Map<String, ByteArray>
        val libraryTree = DocumentsContract.buildTreeDocumentUri(GoldenQaDocumentsProvider.AUTHORITY, "root/selected-library")
        val songDirectory = File(providerRoot(), "selected-library/$VIDEO_ID")
        suspend fun open() {
            check(File(providerRoot(), "selected-library").mkdir())
            check(File(providerRoot(), VIDEO_ID).renameTo(songDirectory))
            grantProviderTree(libraryTree)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(libraryTree).getOrThrow()).getOrThrow()
            external.reconcile(); coordinator.awaitStartupScratchReady()
            before = songDirectory.listFiles()!!.associate { it.name to it.readBytes() }
        }
        fun archive(name: String, time: String = "2026-08-09T12:00:00Z", videoId: String = VIDEO_ID): File {
            val document = payload(time, "Cancelled import", includeArtwork = true)
            document.getJSONObject("source").put("videoId", videoId)
            return writeArchive(context, name, document, COVER_PNG,
                original = File(songDirectory, "original.opus")).copyTo(File(providerRoot(), name), overwrite = true)
        }
        fun uri(file: File): Uri = DocumentsContract.buildDocumentUriUsingTree(GoldenQaDocumentsProvider.treeUri, "root/${file.name}")
        suspend fun awaitCancelled() {
            withTimeout(5_000L) { coordinator.state.first { it is SharedEnrichmentImportState.Cancelled } }
            assertNoScratch()
        }
        fun assertNoScratch() {
            assertFalse(File(context.cacheDir, "enrichment").exists())
            assertTrue(File(context.noBackupFilesDir, "import-staging").listFiles().orEmpty().isEmpty())
        }
        fun assertPreserved() {
            assertPublishedSongPreserved()
            assertNoScratch()
        }
        fun assertPublishedSongPreserved() {
            before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(songDirectory, name).readBytes()) }
        }
        suspend fun close() {
            importer.extractionProgressForTest = null
            importer.originalInspectionCheckpointForTest = null
            importer.bundleCommittedForTest = null
            try { withTimeout(10_000L) { coordinator.cancelForLibraryChange() } } finally {
                database.close()
                targetContext.revokeUriPermission(targetContext.packageName, libraryTree,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }
}
