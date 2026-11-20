package app.stemwise.enrichment

import androidx.room.Room
import app.stemwise.data.ExternalLibraryRepository
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.SourceBindingRegistry
import app.stemwise.data.seedVerifiedBinding
import app.stemwise.data.authority
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class EnrichmentRepairPreparationRecoveryInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun malformedCanonicalJsonSurvivesInterruptedPreparationAbortAndTwoColdRecoveries() = runBlocking {
        interruptedRepair(missingCover = false)
    }
    @Test fun missingCanonicalCoverSurvivesInterruptedPreparationAbortAndTwoColdRecoveries() = runBlocking {
        interruptedRepair(missingCover = true)
    }

    @Test fun emptyCanonicalJsonAlsoSurvivesInterruptedPreparationAbort() = runBlocking {
        interruptedRepair(missingCover = false, emptyJson = true)
    }
    @Test fun changedCanonicalBytesBlockAbortReplayWithoutDiscardingRecoveryAuthority() = runBlocking {
        interruptedRepair(missingCover = false, changeAfterAbort = true)
    }

    private suspend fun interruptedRepair(missingCover: Boolean, emptyJson: Boolean = false, changeAfterAbort: Boolean = false) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            var mutations = PackageMutationCoordinator(context)
            var roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            seedDirectMedia(withEnrichment = true)
            var bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            seedVerifiedBinding(bindings, mutations, checkNotNull(roots.captureHandle()), SOURCE_ID, VIDEO_ID)
            var external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, BackgroundWorkCoordinator())
            external.reconcile()
            val folder = File(providerRoot(), VIDEO_ID)
            val sourceHashes = currentSourcePackageFiles.associateWith { sha256(File(folder, it)) }
            if (missingCover) check(File(folder, SelectedLibraryContract.COVER).delete())
            else File(folder, SelectedLibraryContract.ENRICHMENT).writeText(if (emptyJson) "" else "{", Charsets.UTF_8)
            val sidecars = SelectedLibraryContract.enrichmentFiles.associateWith { name ->
                File(folder, name).takeIf(File::isFile)?.readBytes()?.toList()
            }
            val failedPreparation = AtomicBoolean()
            val interruptedCleanup = AtomicBoolean()
            val importer = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database) { phase ->
                when (phase) {
                    ENRICHMENT_PREPARATION_AFTER_JSON_REPLACEMENT -> {
                        failedPreparation.set(true)
                        error("EXTERNAL_COMMIT_FAILED")
                    }
                    ENRICHMENT_PREPARATION_BEFORE_ABORT_CLEANUP -> {
                        interruptedCleanup.set(true)
                        if (changeAfterAbort) File(folder, SelectedLibraryContract.ENRICHMENT)
                            .writeText("UNRELATED_CANONICAL_CHANGE", Charsets.UTF_8)
                        throw EnrichmentCommitInterruption(phase)
                    }
                }
            }
            val archive = writeArchive(context, "repair-aborted.zip",
                payload("2026-10-24T12:00:00Z", "Repaired after interrupted preparation", true), COVER_PNG)
            val result = importer.importStagedFiles(listOf(archive), listOf(archive.name)).getOrThrow()
            assertFalse(result.items.single().isSuccess)
            assertTrue(failedPreparation.get()); assertTrue(interruptedCleanup.get())
            assertTrue(File(context.filesDir, "operation-journals").listFiles().orEmpty().isNotEmpty())

            val originalAuthority = checkNotNull(roots.captureHandle()).authority()
            val retainedPackage = if (changeAfterAbort) folder.listFiles().orEmpty().filter(File::isFile)
                .associate { it.name to sha256(it) } else emptyMap()
            fun retainedRecoveryArtifacts() = listOf("operation-journals", "enrichment-recovery")
                .flatMap { File(context.filesDir, it).listFiles().orEmpty().filter(File::isFile) }
                .associate { it.relativeTo(context.filesDir).invariantSeparatorsPath to sha256(it) }
            val retainedRecovery = if (changeAfterAbort) retainedRecoveryArtifacts() else emptyMap()
            if (changeAfterAbort) assertTrue(retainedRecovery.isNotEmpty())
            fun requireExactRecoveryRejection(result: Result<Unit>) {
                val failure = result.exceptionOrNull()
                assertNotNull("Changed canonical bytes must reject this recovery boundary", failure)
                assertEquals(IllegalStateException::class.java, failure!!::class.java)
                assertEquals("RECOVERY_PENDING", failure.message)
            }

            repeat(2) {
                // Fresh owners reopen the retained private journal and exact provider identity.
                val departing = runCatching { external.clearForLibrarySwitch(clearProjection = false) }
                if (changeAfterAbort) requireExactRecoveryRejection(departing) else departing.getOrThrow()
                mutations = PackageMutationCoordinator(context)
                roots = LibraryRootManager(context, mutations, database.libraryDao())
                assertTrue(roots.initialize().ready)
                assertEquals(originalAuthority, checkNotNull(roots.captureHandle()).authority())
                bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
                external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, BackgroundWorkCoordinator())
                val recovered = runCatching { external.reconcile() }
                if (changeAfterAbort) {
                    requireExactRecoveryRejection(recovered)
                    assertEquals("UNRELATED_CANONICAL_CHANGE", File(folder, SelectedLibraryContract.ENRICHMENT).readText())
                    assertEquals(retainedPackage, folder.listFiles().orEmpty().filter(File::isFile)
                        .associate { it.name to sha256(it) })
                    assertEquals(retainedRecovery, retainedRecoveryArtifacts())
                } else recovered.getOrThrow()
                assertEquals(sourceHashes, currentSourcePackageFiles.associateWith { sha256(File(folder, it)) })
                if (!changeAfterAbort) assertEquals(sidecars, SelectedLibraryContract.enrichmentFiles.associateWith { name ->
                    File(folder, name).takeIf(File::isFile)?.readBytes()?.toList()
                })
            }
            if (changeAfterAbort) return
            val retry = songImporter(context, roots, mutations, bindings, BackgroundWorkCoordinator(), database)
                .importStagedFiles(listOf(archive), listOf(archive.name)).getOrThrow()
            assertTrue("Explicit reimport did not repair the preserved package: $retry", retry.items.single().isSuccess)
            assertEquals(sourceHashes, currentSourcePackageFiles.associateWith { sha256(File(folder, it)) })
            assertTrue(File(folder, SelectedLibraryContract.COVER).isFile)
        } finally { database.close() }
    }
}
