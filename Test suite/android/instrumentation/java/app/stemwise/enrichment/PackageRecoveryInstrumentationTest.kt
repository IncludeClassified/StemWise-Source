package app.stemwise.enrichment

import android.net.Uri
import androidx.room.Room
import app.stemwise.data.*
import app.stemwise.integration.*
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

internal class PackageRecoveryInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun stagingStartupCanRetryInTheSameProcessAndThenImport() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            seedDirectMedia(withEnrichment = true)
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, background)
            val importer = songImporter(context, roots, mutations, bindings, background, database)
            val staging = File(context.noBackupFilesDir, "import-staging")
            staging.writeText("temporarily unavailable", Charsets.UTF_8)
            val coordinator = EnrichmentImportCoordinator(context, importer, external, background, roots)
            assertTrue(runCatching { withTimeout(5_000L) { coordinator.awaitStartupScratchReady() } }.isFailure)
            assertTrue(coordinator.state.value is SharedEnrichmentImportState.Failed)
            assertEquals("temporarily unavailable", staging.readText(Charsets.UTF_8))
            check(staging.delete())
            coordinator.retryStartupScratch()
            withTimeout(5_000L) { coordinator.awaitStartupScratchReady() }
            val archive = writeArchive(context, "recovered-startup.zip",
                payload("2026-10-25T12:00:00Z", "After staging retry", true), COVER_PNG)
            coordinator.start(listOf(Uri.fromFile(archive)))
            val result = withTimeout(30_000L) { coordinator.state.first {
                it is SharedEnrichmentImportState.Finished || it is SharedEnrichmentImportState.Failed
            } }
            assertTrue(result.toString(), result is SharedEnrichmentImportState.Finished)
            assertTrue((result as SharedEnrichmentImportState.Finished).result.items.single().isSuccess)
        } finally { database.close() }
    }

    @Test fun cancellationNoticeWaitsForTheActualImportOwnerAndPreservesItsFences() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var coordinator: EnrichmentImportCoordinator? = null
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            seedDirectMedia(withEnrichment = true)
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, background)
            val importer = songImporter(context, roots, mutations, bindings, background, database)
            importer.extractionProgressForTest = {
                entered.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    withTimeout(10_000L) { release.await() }
                }
            }
            val imports = EnrichmentImportCoordinator(context, importer, external, background, roots,
                cancellationRetirementTimeoutMs = 30L).also { coordinator = it }
            imports.awaitStartupScratchReady()
            val archive = writeArchive(context, "cancellation-owner.zip",
                payload("2026-10-25T12:00:00Z", "After exact retirement", true), COVER_PNG)
            imports.start(listOf(Uri.fromFile(archive)))
            withTimeout(10_000L) { entered.await() }
            val staging = File(context.noBackupFilesDir, "import-staging")
            val retained = staging.listFiles()!!.associate { it.name to it.readBytes().toList() }
            val ownerId = (imports.state.value as SharedEnrichmentImportState.Running).operationId
            imports.requestCancel()
            val pending = withTimeout(5_000L) { imports.state.first {
                it is SharedEnrichmentImportState.Running && it.retirementPending
            } } as SharedEnrichmentImportState.Running
            assertEquals(ownerId, pending.operationId)
            assertTrue(pending.phase.contains("Force stop StemWise"))
            assertEquals(retained, staging.listFiles()!!.associate { it.name to it.readBytes().toList() })
            imports.start(listOf(Uri.fromFile(archive)))
            assertEquals(ownerId, (imports.state.value as SharedEnrichmentImportState.Running).operationId)
            assertEquals(1, (imports.state.value as SharedEnrichmentImportState.Running).rejectedOffers)
            release.complete(Unit)
            withTimeout(10_000L) { imports.state.first { it is SharedEnrichmentImportState.Cancelled } }
            importer.extractionProgressForTest = null
            imports.start(listOf(Uri.fromFile(archive)))
            val finished = withTimeout(30_000L) { imports.state.first {
                it is SharedEnrichmentImportState.Finished || it is SharedEnrichmentImportState.Failed
            } }
            assertTrue(finished.toString(), finished is SharedEnrichmentImportState.Finished)
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { coordinator?.cancelForLibraryChange() }
            database.close()
        }
    }

    @Test fun matchingPcBundleEnrichesOriginalWhileFailedPartOwnershipRemainsRecoverable() = runBlocking {
        interruptedParts("enrich")
    }
    @Test fun deletingPartsRetiresTheExactFailedPublicationAndPreparationOwner() = runBlocking {
        interruptedParts("parts")
    }
    @Test fun permanentDeletionRetiresTheExactFailedPublicationAndPreparationOwner() = runBlocking {
        interruptedParts("permanent")
    }
    @Test fun interruptedPartsDeletionReplaysItsExactPreparationRetirement() = runBlocking {
        interruptedParts("parts-recovery")
    }
    @Test fun interruptedPermanentDeletionReplaysItsExactPreparationRetirement() = runBlocking {
        interruptedParts("permanent-recovery")
    }
    @Test fun unsupportedChildRefusesPermanentDeletionBeforeAnyPackageBytesChange() = runBlocking {
        interruptedParts("unsupported")
    }

    private suspend fun interruptedParts(action: String) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            seedDirectMedia(withEnrichment = true)
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            seedVerifiedBinding(bindings, mutations, root, SOURCE_ID, VIDEO_ID)
            val background = BackgroundWorkCoordinator()
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
            external.reconcile()
            val source = checkNotNull(dao.currentSource(VIDEO_ID))
            val folder = File(providerRoot(), VIDEO_ID)
            (SelectedLibraryContract.customStemFiles + SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST)
                .forEach { name -> File(folder, name).takeIf(File::exists)?.let { check(it.delete()) } }
            val media = CurrentSourcePublicationFixture.load(targetContext, VIDEO_ID)
            val plan = MvsepSourcePlan(listOf("bass", "drums"), listOf(MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"),
                mapOf("bass" to "bass", "drums" to "drums"))))
            var authority = createPortableSourceJobAuthority(root.libraryId, VIDEO_ID, source.id, source.sha256,
                plan, 1_780_000_000_000L)
            authority = transitionPortableSourceJob(authority, SourceJobTransition.ENTER_SUBMISSION, authority.updatedAtEpochMs + 1)
            authority = transitionPortableSourceJob(authority, SourceJobTransition.ACKNOWLEDGE_REMOTE, authority.updatedAtEpochMs + 1, "accepted-fixture")
            authority = transitionPortableSourceJob(authority, SourceJobTransition.BEGIN_DOWNLOAD, authority.updatedAtEpochMs + 1)
            val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, background)
            val first = File(context.cacheDir, "part-1.flac").also { it.writeBytes(media.parts.getValue(it.name)) }
            val firstAsset = CustomSourceAsset("bass", "Bass", first.name, sha256(first), first.length(), media.manifest.audibleFrames)
            publisher.publishSourcePart(root, VIDEO_ID, first.name, first, firstAsset.sha256, authority.jobId,
                source.sha256, media.manifest.audibleFrames).getOrThrow()
            authority = recordPortableSourceAsset(authority, firstAsset, authority.updatedAtEpochMs + 1)
            val second = File(context.cacheDir, "part-2.flac").also { it.writeBytes(media.parts.getValue(it.name)) }
            publisher.copyProgressForTest = { error("TEST_PART_COPY_FAILURE") }
            val failed = publisher.publishSourcePart(root, VIDEO_ID, second.name, second, sha256(second), authority.jobId,
                source.sha256, media.manifest.audibleFrames)
            assertTrue(failed.isFailure)
            publisher.copyProgressForTest = null
            File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY).writeBytes(authority.toBytes())
            dao.upsertJob(projectSourceJob(authority).copy(state = "FAILED"))
            val staging = folder.listFiles()!!.filter { SelectedLibraryContract.isPublicationStagingName(it.name) }
            assertEquals(1, staging.size)
            val originalTrio = (SelectedLibraryContract.enrichmentFiles + SelectedLibraryContract.ORIGINAL).associateWith {
                File(folder, it).readBytes().toList()
            }
            val journalDirectory = File(context.filesDir, "operation-journals")
            val journals = journalDirectory.listFiles()!!.associate { it.name to sha256(it) }
            assertTrue(journals.isNotEmpty())
            val partBytes = (staging + File(folder, "part-1.flac") + File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY))
                .associate { it.name to it.readBytes().toList() }
            when (action) {
                "enrich" -> {
                    val original = File(folder, SelectedLibraryContract.ORIGINAL)
                    val archive = writeArchive(context, "partial-bank.zip",
                        payload("2026-10-25T12:00:00Z", "Matching PC bundle", true), COVER_PNG, original)
                    val importer = SongEnrichmentImporter(context, roots, mutations, bindings, background, originalPublisher = { publisher })
                    val imported = importer.importPcSong(archive, VIDEO_ID, root.authority()).getOrThrow().items.single()
                    assertTrue(imported.errorCode, imported.isSuccess)
                    assertEquals(originalTrio.getValue(SelectedLibraryContract.ORIGINAL), original.readBytes().toList())
                    partBytes.forEach { (name, bytes) -> assertEquals(bytes, File(folder, name).readBytes().toList()) }
                    assertEquals(journals, journalDirectory.listFiles()!!.associate { it.name to sha256(it) })
                    assertEquals("FAILED", checkNotNull(dao.findJobById(authority.jobId)).state)
                    assertEquals("Matching PC bundle", external.verifyPlaybackSong(root, VIDEO_ID).title)
                    assertTrue(dao.hasRootSwitchBlockingSeparationSafetyFacts())
                }
                "unsupported" -> {
                    File(folder, "owner-file.txt").writeText("preserve", Charsets.UTF_8)
                    val before = folder.listFiles()!!.associate { it.name to it.readBytes().toList() }
                    val trash = ExternalPackageTrash(context, roots, mutations, bindings, dao)
                    val failure = trash.deletePermanently(root, VIDEO_ID).exceptionOrNull()
                    assertEquals("PACKAGE_DIRECTORY_CLEANUP_REFUSED", failure?.message)
                    assertEquals(before, folder.listFiles()!!.associate { it.name to it.readBytes().toList() })
                    assertEquals("FAILED", checkNotNull(dao.findJobById(authority.jobId)).state)
                }
                else -> {
                    val trash = ExternalPackageTrash(context, roots, mutations, bindings, dao)
                    val coordinator = PermanentDeletionCoordinator(trash, external, SongWorkCoordinator())
                    val interrupted = action.endsWith("-recovery")
                    if (interrupted) trash.beforePreparationRetirementForTest = { error("TEST_RETIREMENT_INTERRUPTION") }
                    val outcome = if (action.startsWith("parts")) coordinator.deleteStems(root, VIDEO_ID)
                        else coordinator.deletePermanently(root, VIDEO_ID)
                    if (interrupted) {
                        assertTrue(outcome.isFailure)
                        assertEquals("FAILED", checkNotNull(dao.findJobById(authority.jobId)).state)
                        assertTrue(journalDirectory.listFiles().orEmpty().size > journals.size)
                        ExternalPackageTrash(context, roots, mutations, bindings, dao).recoverInterruptedDeletes(root)
                        external.reconcile()
                    } else outcome.getOrThrow()
                    if (action.startsWith("parts")) {
                        assertEquals(originalTrio, folder.listFiles()!!.associate { it.name to it.readBytes().toList() })
                        assertEquals("OBSOLETE", checkNotNull(dao.findJobById(authority.jobId)).state)
                        external.verifyPlaybackSong(root, VIDEO_ID)
                    } else {
                        assertFalse(folder.exists())
                    }
                    assertFalse(dao.hasRootSwitchBlockingSeparationSafetyFacts())
                    assertTrue(journalDirectory.listFiles().orEmpty().isEmpty())
                }
            }
        } finally { database.close() }
    }
}
