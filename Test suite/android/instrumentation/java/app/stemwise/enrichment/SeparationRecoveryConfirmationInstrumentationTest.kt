package app.stemwise.enrichment

import androidx.room.Room
import app.stemwise.data.*
import app.stemwise.integration.*
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

internal class SeparationRecoveryConfirmationInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun compatibleRemoteJobCannotBeAdoptedWithoutExactSourceConfirmation() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var cold: ColdOpenCoordinator? = null
        var imports: EnrichmentImportCoordinator? = null
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(app.stemwise.qa.GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val background = BackgroundWorkCoordinator()
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background, scope = scope)
            external.reconcile()
            val source = checkNotNull(dao.currentSource(VIDEO_ID))
            val plan = MvsepSourcePlan(listOf("bass"), listOf(MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), mapOf("bass" to "bass"))))
            var authority = createPortableSourceJobAuthority(root.libraryId, VIDEO_ID, source.id, source.sha256, plan, 1L)
            authority = transitionPortableSourceJob(authority, SourceJobTransition.ENTER_SUBMISSION, 2L)
            authority = transitionPortableSourceJob(authority, SourceJobTransition.MARK_SUBMISSION_UNKNOWN, 3L)
            val authorityFile = File(providerRoot(), "$VIDEO_ID/${sourceAuthorityFileNames(authority.generation).first}")
            authorityFile.writeBytes(authority.toBytes())
            dao.upsertJob(projectSourceJob(authority))
            val before = authorityFile.readBytes()
            var statusCalls = 0
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(AppNetworkState(false, false, true))
                override fun currentState() = state.value
            }
            val account = object : MvsepAccountAccess {
                override val state = MutableStateFlow(MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED))
                override suspend fun validate(expectedCredential: MvsepCredential?): Result<Unit> = error("No new submission")
                override fun credential(): MvsepCredential? = error("No new submission")
                override fun isCurrent(credential: MvsepCredential) = false
                override fun apiBase() = error("No new submission")
            }
            val client = object : MvsepServiceClient {
                override suspend fun disablePremium(token: String) = error("No new submission")
                override suspend fun status(remoteJobId: String): MvsepRemoteStatus {
                    assertEquals("manually-recovered", remoteJobId)
                    statusCalls++
                    return MvsepRemoteStatus("processing", null, emptyList())
                }
                override suspend fun download(remote: MvsepRemoteFile, target: File) = error("Unconfirmed media must not publish")
            }
            val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, background)
            val songWork = SongWorkCoordinator()
            val repository = SeparationRepository(context, dao, account, roots, mutations, songWork, external,
                publisher, background, YoutubeDlEngine(context, network, roots), network, client)
            val review = repository.reviewRecovery(root.authority(), authority.jobId, "manually-recovered").getOrThrow()
            assertEquals(source.sha256, review.sourceSha256)
            assertEquals(authority.planSha256, review.planSha256)
            assertNull(review.providerSourceName)
            assertTrue(review.configuration.contains("mvsep-63"))
            assertArrayEquals(before, authorityFile.readBytes())
            assertTrue(repository.recoverSubmission(review, false).isFailure)
            assertArrayEquals(before, authorityFile.readBytes())
            assertTrue(repository.recoverSubmission(review.copy(planSha256 = "0".repeat(64)), true).isFailure)
            assertArrayEquals(before, authorityFile.readBytes())
            assertEquals(1, statusCalls)
            val preferences = StemwisePreferences(context, database = database)
            preferences.activateLibraryFromRoot(root.libraryId)
            val playlist = PlaylistRepository(context, database, dao, preferences,
                YoutubeDlEngine(context, network, roots), songWork, roots, external, background, network)
            val importer = songImporter(context, roots, mutations, bindings, background, database)
            val importOwner = EnrichmentImportCoordinator(context, importer, external, background, roots, scope)
                .also { imports = it }
            val coldOwner = ColdOpenCoordinator(roots, external, playlist, preferences,
                ExternalPackageTrash(context, roots, mutations, bindings, dao), repository, importOwner, importer)
                .also { cold = it }
            checkNotNull(coldOwner.start())
            withTimeout(20_000L) { coldOwner.awaitCompletion() }
            assertTrue(coldOwner.state.value is ColdOpenState.Ready)
            assertTrue(playlist.preparationOwner.value is PreparationOwnerDecision.Blocked)
            val wake = CompletableDeferred<Unit>()
            val failedAdmission = CompletableDeferred<Unit>()
            val repairedAdmission = CompletableDeferred<Unit>()
            val adoptions = java.util.concurrent.atomic.AtomicInteger()
            val refreshes = java.util.concurrent.atomic.AtomicInteger()
            val injectReadFailure = java.util.concurrent.atomic.AtomicBoolean(false)
            coldOwner.beforePreparationArbitrationForTest = {
                if (injectReadFailure.compareAndSet(true, false))
                    setFault(app.stemwise.qa.GoldenQaDocumentsProvider.FAULT_AUTHORITY_READ_ONCE)
            }
            val actual = app.stemwise.work.AndroidManualTaskActions(context, repository, roots, coldOwner)
            val actions = object : app.stemwise.work.ManualTaskActions by actual {
                override suspend fun recoverSubmission(review: SeparationRecoveryReview, sourceConfirmed: Boolean): Result<Unit> {
                    adoptions.incrementAndGet()
                    return actual.recoverSubmission(review, sourceConfirmed).also {
                        it.getOrThrow()
                        // Only the following real ColdOpen authority read fails; adoption is already durable.
                        injectReadFailure.set(true)
                    }
                }
                override suspend fun refreshPreparationOwner(expectedRoot: RootAuthority): Result<Unit> {
                    refreshes.incrementAndGet()
                    return actual.refreshPreparationOwner(expectedRoot).also { result ->
                        if (result.isFailure) {
                            val failure = result.exceptionOrNull()
                            val observedState = coldOwner.state.value
                            val diagnostic = "refresh=${refreshes.get()} failure=${failure?.stackTraceToString()}" +
                                " cold=$observedState root=${roots.state.value} expected=${root.authority()}" +
                                " admitted=${coldOwner.isProducerAdmissionReady(root)}"
                            assertTrue(diagnostic, failure is java.io.IOException)
                            assertTrue(diagnostic, observedState is ColdOpenState.Blocked)
                            assertFalse(diagnostic, coldOwner.isProducerAdmissionReady(root))
                            assertEquals("PROCESSING", dao.findJobById(authority.jobId)?.state)
                            assertEquals("manually-recovered", parsePortableSourceJobAuthority(authorityFile.readBytes()).currentRemoteJobId)
                            failedAdmission.complete(Unit)
                        } else repairedAdmission.complete(Unit)
                    }
                }
                override fun updatePeriodicSync(configured: Boolean) = Unit
                override fun enqueueSeparation(expectedRoot: RootAuthority, afterCurrent: Boolean): Boolean {
                    assertEquals(root.authority(), expectedRoot)
                    assertTrue("A wake cannot bypass actual ColdOpen admission", coldOwner.isProducerAdmissionReady(root))
                    assertTrue(afterCurrent)
                    check(wake.complete(Unit)) { "Adopted job enqueued twice" }
                    return true
                }
            }
            val manual = app.stemwise.work.ManualTaskCoordinator(scope, app.stemwise.work.TaskWorkGate(), actions,
                captureReadyRoot = { root.authority().takeIf { coldOwner.isProducerAdmissionReady(root) } },
                isReadyRoot = { it == root.authority() && coldOwner.isProducerAdmissionReady(root) },
                captureRecoveryRoot = { roots.captureAuthority() })
            // Use the same Ready callback as StemwiseServices. The accepted wake owns the cold-open retry.
            coldOwner.setReadyListener { manual.onColdOpenFinished() }
            manual.recoverSeparationSubmission(review, true).getOrThrow()
            withTimeout(10_000L) { failedAdmission.await() }
            withTimeout(20_000L) { wake.await() }
            // Ready authorizes enqueue before recovery/job retirement; later faults wait for both.
            withTimeout(10_000L) { repairedAdmission.await() }
            assertEquals(1, adoptions.get())
            assertEquals(2, refreshes.get())
            assertTrue(coldOwner.isProducerAdmissionReady(root))
            assertEquals("manually-recovered", parsePortableSourceJobAuthority(authorityFile.readBytes()).currentRemoteJobId)
            assertEquals(2, statusCalls)
            assertEquals("PROCESSING", dao.findJobById(authority.jobId)?.state)
            val accepted = checkNotNull(dao.findJobById(authority.jobId))
            val portable = authorityFile.readBytes()
            setFault(app.stemwise.qa.GoldenQaDocumentsProvider.FAULT_AUTHORITY_READ_ONCE)
            val retry = app.stemwise.work.retryUnavailablePreparationRead {
                playlist.reconcilePreparationOwner(root)
                error("An unavailable authority read must not reach processing")
            }
            assertEquals(androidx.work.ListenableWorker.Result.retry(), retry)
            assertEquals(accepted, dao.findJobById(authority.jobId))
            assertArrayEquals(portable, authorityFile.readBytes())
            val resumed = app.stemwise.work.retryUnavailablePreparationRead {
                assertTrue(playlist.reconcilePreparationOwner(root) is PreparationOwnerDecision.Separation)
                assertEquals(SeparationWorkResult.PENDING, repository.process(authority.jobId, root))
                androidx.work.ListenableWorker.Result.success()
            }
            assertEquals(androidx.work.ListenableWorker.Result.success(), resumed)
            assertEquals(3, statusCalls)
            assertEquals("manually-recovered", dao.findJobById(authority.jobId)?.remoteJobId)
            assertArrayEquals(portable, authorityFile.readBytes())
            // Confirmed parser/integrity damage is an explicit block, never an automatic I/O retry.
            val invalidAuthorities = listOf("{".toByteArray(Charsets.UTF_8), CanonicalJson.encode(
                org.json.JSONObject(portable.toString(Charsets.UTF_8)).put("integritySha256", "0".repeat(64))))
            val readHook = coldOwner.beforePreparationArbitrationForTest
            for (invalid in invalidAuthorities) {
                try {
                    coldOwner.beforePreparationArbitrationForTest = { authorityFile.writeBytes(invalid) }
                    val refused = actual.refreshPreparationOwner(root.authority())
                    assertTrue(refused.exceptionOrNull() is IllegalStateException)
                    assertFalse(refused.exceptionOrNull() is java.io.IOException)
                    assertFalse(coldOwner.isProducerAdmissionReady(root))
                    assertTrue(playlist.preparationOwner.value is PreparationOwnerDecision.Blocked)
                    assertArrayEquals(invalid, authorityFile.readBytes())
                    assertEquals(3, statusCalls)
                } finally {
                    authorityFile.writeBytes(portable)
                    coldOwner.beforePreparationArbitrationForTest = readHook
                }
                actual.refreshPreparationOwner(root.authority()).getOrThrow()
                assertTrue(coldOwner.isProducerAdmissionReady(root))
            }
            val admitted = coldOwner.state.value
            val wrongEpoch = actual.refreshPreparationOwner(root.authority().copy(epoch = root.epoch + 1L))
            assertTrue(wrongEpoch.isFailure)
            assertEquals(admitted, coldOwner.state.value)
            assertArrayEquals(portable, authorityFile.readBytes())
            assertEquals(1, adoptions.get())
        } finally {
            cold?.beforePreparationArbitrationForTest = null
            try {
                withTimeout(10_000L) { cold?.cancelAndJoin(); imports?.cancelForLibraryChange() }
            } finally {
                withTimeout(5_000L) { scope.coroutineContext[Job]?.cancelAndJoin() }
                database.close()
            }
        }
    }
}
