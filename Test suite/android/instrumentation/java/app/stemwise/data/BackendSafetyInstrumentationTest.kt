package app.stemwise.data

import app.stemwise.integration.createPortableSourceJobAuthority
import app.stemwise.integration.MvsepSourcePlan
import app.stemwise.integration.MvsepSourceOperation
import app.stemwise.integration.SourceJobTransition
import app.stemwise.integration.transitionPortableSourceJob
import app.stemwise.integration.SeparationRepository
import app.stemwise.integration.SeparationWorkResult
import app.stemwise.integration.MvsepAccountAccess
import app.stemwise.integration.MvsepConnectionState
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.integration.MvsepCredential
import app.stemwise.integration.MvsepServiceClient
import app.stemwise.integration.MvsepRemoteFile
import app.stemwise.integration.MvsepRemoteStatus
import app.stemwise.integration.MvsepAlgorithm
import app.stemwise.integration.YoutubeDlEngine
import app.stemwise.integration.sourcePartFileName
import app.stemwise.integration.projectSourceJob
import app.stemwise.integration.SourceAcknowledgementStore
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import kotlinx.coroutines.flow.MutableStateFlow

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.enrichment.GoldenSongMediaFixture
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Connected fault evidence for root-scoped journals and app-private artwork retirement. */
@RunWith(AndroidJUnit4::class)
class BackendSafetyInstrumentationTest {
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun acceptedSourceReceiptRetriesBlockedPublicationInTheSameProcessWithoutResubmission() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val name = "accepted-publication-deadline"
        val context = IsolatedSafetyContext(targetContext, name)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val tree = providerTreeUri(name)
        grantProviderTrees(tree)
        val release = CompletableDeferred<Unit>()
        var blocker: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            val folder = File(File(providerRoot(), name).apply(::requireDirectory), VIDEO_ID).apply(::requireDirectory)
            val original = GoldenSongMediaFixture.originalOpus(targetContext, 1_200L)
            File(folder, SelectedLibraryContract.ORIGINAL).writeBytes(original)
            seedCompleteOpusMetadata(folder, VIDEO_ID, original)
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val governor = app.stemwise.playback.BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
            external.reconcile()
            val source = checkNotNull(dao.currentSource(VIDEO_ID))
            val plan = MvsepSourcePlan(listOf("bass"), listOf(MvsepSourceOperation("mvsep-63", 63,
                emptyMap(), setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), mapOf("bass" to "bass"))))
            val submitting = transitionPortableSourceJob(createPortableSourceJobAuthority(root.libraryId,
                VIDEO_ID, source.id, source.sha256, plan, 1L), SourceJobTransition.ENTER_SUBMISSION, 2L)
            val acknowledged = transitionPortableSourceJob(submitting, SourceJobTransition.ACKNOWLEDGE_REMOTE, 3L, "accepted-once")
            val canonical = File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY).apply { writeBytes(submitting.toBytes()) }
            dao.upsertJob(projectSourceJob(submitting))
            val receiptDirectory = File(context.filesDir, "source-acknowledgements")
            val seedStore = SourceAcknowledgementStore(receiptDirectory, 100L)
            val entered = CompletableDeferred<Unit>()
            blocker = async(Dispatchers.IO) { mutations.withRootMutation { entered.complete(Unit); release.await() } }
            withTimeout(5_000L) { entered.await() }
            assertTrue(runCatching { seedStore.settle(root.authority(), acknowledged) {
                mutations.withRootMutation { error("Publication must remain blocked") }
            } }.exceptionOrNull() is java.io.IOException)
            val store = SourceAcknowledgementStore(receiptDirectory)
            var submissions = 0
            val remoteIds = mutableListOf<String>()
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(AppNetworkState(true, true, false, activeNetworkWifi = true))
                override fun currentState() = state.value
            }
            val account = object : MvsepAccountAccess {
                override val state = MutableStateFlow(MvsepConnectionState(MvsepConnectionStatus.READY))
                override suspend fun validate(expectedCredential: MvsepCredential?) = error("Unexpected authentication")
                override fun credential(): MvsepCredential? = error("Unexpected upload credentials")
                override fun isCurrent(credential: MvsepCredential) = false
                override fun apiBase() = "https://example.invalid"
            }
            val client = object : MvsepServiceClient {
                override suspend fun disablePremium(token: String) = error("Unexpected submission setup")
                override suspend fun submitConfigured(token: String, source: File, operation: MvsepSourceOperation,
                    isAuthorized: () -> Boolean, onSubmissionEntered: suspend () -> Unit,
                    onAcknowledged: suspend (String) -> Unit, onRejected: suspend (String) -> Unit): String {
                    submissions++; error("Accepted operation was resubmitted")
                }
                override suspend fun status(remoteJobId: String): MvsepRemoteStatus {
                    remoteIds += remoteJobId
                    return MvsepRemoteStatus("processing", null, emptyList())
                }
                override suspend fun download(remote: MvsepRemoteFile, target: File) = error("Unexpected output download")
            }
            val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
            val repository = SeparationRepository(context, dao, account, roots, mutations, SongWorkCoordinator(), external,
                publisher, governor, YoutubeDlEngine(targetContext, network, roots), network, client, store)
            assertEquals(SeparationWorkResult.PENDING, withTimeout(15_000L) { repository.process(submitting.jobId, root) })
            val pending = checkNotNull(dao.findJobById(submitting.jobId))
            assertEquals("PROCESSING", pending.state)
            assertEquals("accepted-once", pending.remoteJobId)
            assertNotNull(pending.nextAttemptAtEpochMs)
            assertEquals("SOURCE_ACKNOWLEDGEMENT_PUBLICATION_PENDING", pending.lastErrorCode)
            assertEquals(acknowledged, store.read(root.authority(), submitting))
            assertArrayEquals(submitting.toBytes(), canonical.readBytes())
            assertTrue(remoteIds.isEmpty())
            release.complete(Unit)
            withTimeout(5_000L) { blocker.await() }
            assertEquals(SeparationWorkResult.PENDING, withTimeout(15_000L) { repository.process(submitting.jobId, root) })
            val retried = checkNotNull(dao.findJobById(submitting.jobId))
            val replayedAuthority = app.stemwise.integration.parsePortableSourceJobAuthority(canonical.readBytes())
            val retainedReceipt = store.read(root.authority(), acknowledged)
            assertEquals("Retry state=${retried.state}, error=${retried.lastErrorCode}, " +
                "canonical=${replayedAuthority.state}/${replayedAuthority.revision}, " +
                "receipt=${retainedReceipt?.state}/${retainedReceipt?.revision}", listOf("accepted-once"), remoteIds)
            assertEquals(0, submissions)
            assertArrayEquals(acknowledged.toBytes(), canonical.readBytes())
            assertNull(store.read(root.authority(), acknowledged))
            assertArrayEquals(original, File(folder, SelectedLibraryContract.ORIGINAL).readBytes())
        } finally {
            release.complete(Unit)
            blocker?.let { withTimeout(5_000L) { it.await() } }
            database.close()
            revokeProviderTrees(tree)
            resetProvider()
            context.destroy()
        }
    }

    @Test
    fun retainedSeparationAssemblyAndPublicationFinishOfflineWithoutAccountCalls() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val name = "offline-local-separation"
        val context = IsolatedSafetyContext(targetContext, name)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val tree = providerTreeUri(name)
        grantProviderTrees(tree)
        try {
            val library = File(providerRoot(), name).apply(::requireDirectory)
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val governor = app.stemwise.playback.BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
            val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
            val fixture = CurrentSourcePublicationFixture.load(context, VIDEO_ID)
            val original = File(context.cacheDir, "original.opus").apply { writeBytes(fixture.original) }
            publisher.publishOriginal(root, VIDEO_ID, original, fixture.manifest.sourceSha256).getOrThrow()
            val folder = File(library, VIDEO_ID)
            seedCompleteOpusMetadata(folder, VIDEO_ID, fixture.original)
            external.reconcile()
            val source = checkNotNull(dao.currentSource(VIDEO_ID))
            val plan = MvsepSourcePlan(listOf("bass"), listOf(MvsepSourceOperation("mvsep-63", 63,
                emptyMap(), setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"),
                mapOf("bass" to "bass"))))
            var authority = createPortableSourceJobAuthority(root.libraryId, VIDEO_ID, source.id,
                source.sha256, plan, 1L, 1L)
            fun step(transition: SourceJobTransition, remote: String? = null) {
                authority = transitionPortableSourceJob(authority, transition, authority.updatedAtEpochMs + 1, remote)
            }
            step(SourceJobTransition.ENTER_SUBMISSION)
            step(SourceJobTransition.ACKNOWLEDGE_REMOTE, "accepted-offline")
            step(SourceJobTransition.BEGIN_DOWNLOAD)
            val bass = fixture.manifest.lanes.single { it.sourceId == "bass" }
            val retained = bass.copy(fileName = sourcePartFileName(authority.generation, plan.selectedSourceIds, "bass"))
            val part = File(context.cacheDir, "retained-bass.flac").apply { writeBytes(fixture.parts.getValue(bass.fileName)) }
            publisher.publishSourcePart(root, VIDEO_ID, retained.fileName, part, retained.sha256,
                authority.jobId, source.sha256, retained.audibleFrames).getOrThrow()
            authority = app.stemwise.integration.recordPortableSourceAsset(authority, retained, authority.updatedAtEpochMs + 1)
            step(SourceJobTransition.BEGIN_ASSEMBLY)
            val authorityFile = File(folder, app.stemwise.integration.sourceAuthorityFileNames(authority.generation).first)
            authorityFile.writeBytes(authority.toBytes())
            dao.upsertJob(projectSourceJob(authority))
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(AppNetworkState(false, false, true))
                override fun currentState() = state.value
            }
            val account = object : MvsepAccountAccess {
                override val state = MutableStateFlow(MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED))
                override suspend fun validate(expectedCredential: MvsepCredential?): Result<Unit> = error("Local work contacted account")
                override fun credential(): MvsepCredential? = error("Local work requested credentials")
                override fun isCurrent(credential: MvsepCredential) = false
                override fun apiBase() = error("Local work requested API route")
            }
            val client = object : MvsepServiceClient {
                override suspend fun sourceAlgorithms(): List<MvsepAlgorithm> = error("Local work fetched models")
                override suspend fun disablePremium(token: String) = error("Local work changed account")
                override suspend fun status(remoteJobId: String): MvsepRemoteStatus = error("Local work polled remote")
                override suspend fun download(remote: MvsepRemoteFile, target: File) = error("Local work downloaded")
            }
            fun repository() = SeparationRepository(context, dao, account, roots, mutations, SongWorkCoordinator(),
                external, publisher, governor, YoutubeDlEngine(targetContext, network, roots), network, client)
            assertEquals(SeparationWorkResult.COMPLETE, repository().process(authority.jobId, root))
            val complete = app.stemwise.integration.parsePortableSourceJobAuthority(authorityFile.readBytes())
            assertEquals("COMPLETE", complete.state)
            val published = File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).readBytes()
            // Recover the interrupted current publication boundary against the same exact receipts.
            val publishing = complete.copy(state = "PUBLISHING")
            authorityFile.writeBytes(publishing.toBytes())
            dao.upsertJob(projectSourceJob(publishing))
            assertEquals(SeparationWorkResult.COMPLETE, repository().process(authority.jobId, root))
            assertArrayEquals(published, File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).readBytes())
            assertArrayEquals(fixture.original, File(folder, SelectedLibraryContract.ORIGINAL).readBytes())
            assertEquals("COMPLETE", dao.findJobById(authority.jobId)?.state)
        } finally {
            database.close()
            revokeProviderTrees(tree)
            context.destroy()
            resetProvider()
        }
    }

    @Test fun freshGenerationUsesTheCurrentProviderRouteWhileAcceptedWorkKeepsItsFrozenPlan() = runBlocking {
        withTimeout(30_000L) {
            StandardInstrumentationTargetGuard.requireCurrentTarget()
            resetProvider()
            val name = "fresh-provider-route"
            val context = IsolatedSafetyContext(targetContext, name)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            val tree = providerTreeUri(name)
            grantProviderTrees(tree)
            try {
                val library = File(providerRoot(), name).apply(::requireDirectory)
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val governor = app.stemwise.playback.BackgroundWorkCoordinator()
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
                val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
                val fixture = CurrentSourcePublicationFixture.load(context, VIDEO_ID)
                publisher.publishOriginal(root, VIDEO_ID,
                    File(context.cacheDir, "original.opus").apply { writeBytes(fixture.original) },
                    fixture.manifest.sourceSha256).getOrThrow()
                val folder = File(library, VIDEO_ID)
                seedCompleteOpusMetadata(folder, VIDEO_ID, fixture.original)
                external.reconcile()
                val trio = listOf(SelectedLibraryContract.ORIGINAL, SelectedLibraryContract.ENRICHMENT,
                    SelectedLibraryContract.COVER).associateWith { File(folder, it).readBytes() }
                val network = object : AppNetworkAccess {
                    override val state = MutableStateFlow(AppNetworkState(true, true, false, activeNetworkWifi = true))
                    override fun currentState() = state.value
                }
                val fixtureCredential = MvsepCredential("offline-fixture-token", 1L)
                val account = object : MvsepAccountAccess {
                    override val state = MutableStateFlow(MvsepConnectionState(MvsepConnectionStatus.READY))
                    override suspend fun validate(expectedCredential: MvsepCredential?) = Result.success(Unit)
                    override fun credential() = fixtureCredential
                    override fun isCurrent(credential: MvsepCredential) = credential === fixtureCredential
                    override fun apiBase() = "https://example.invalid"
                }
                var option = "7"
                var catalogueReads = 0
                var submissions = 0
                var remoteState = "processing"
                val submitted = mutableListOf<MvsepSourceOperation>()
                val client = object : MvsepServiceClient {
                    override suspend fun sourceAlgorithms(): List<MvsepAlgorithm> {
                        catalogueReads++
                        val roles = if (option == "7") setOf("drums", "kick", "snare", "toms", "ride", "hh", "crash", "residual")
                            else setOf("drums", "kick", "snare", "toms", "cymbals", "residual")
                        return listOf(MvsepAlgorithm(37, "Current advertised drum route", roles.toList(), 1.0, 0,
                            app.stemwise.integration.MvsepCatalogueMetadata(roles,
                                listOf(app.stemwise.integration.MvsepOptionField("add_opt1", "model_type", setOf(option)),
                                    app.stemwise.integration.MvsepOptionField("add_opt2", "extract_type", setOf("0"))), emptyList())))
                    }
                    override suspend fun disablePremium(token: String) { assertEquals(fixtureCredential.token, token) }
                    override suspend fun submitConfigured(token: String, source: File, operation: MvsepSourceOperation,
                        isAuthorized: () -> Boolean, onSubmissionEntered: suspend () -> Unit,
                        onAcknowledged: suspend (String) -> Unit, onRejected: suspend (String) -> Unit): String {
                        assertTrue(isAuthorized())
                        onSubmissionEntered()
                        submissions++
                        submitted += operation
                        onAcknowledged("accepted-frozen-route")
                        return "accepted-frozen-route"
                    }
                    override suspend fun status(remoteJobId: String): MvsepRemoteStatus {
                        assertEquals("accepted-frozen-route", remoteJobId)
                        return MvsepRemoteStatus(remoteState, null, emptyList())
                    }
                    override suspend fun download(remote: MvsepRemoteFile, target: File) = error("No remote download in route admission test")
                }
                val repository = SeparationRepository(context, dao, account, roots, mutations, SongWorkCoordinator(),
                    external, publisher, governor, YoutubeDlEngine(targetContext, network, roots), network, client)
                val active = repository.queueSources(root, VIDEO_ID, listOf("kick")).getOrThrow()
                val activeId = checkNotNull(active.jobId)
                assertEquals(SeparationWorkResult.PENDING, repository.process(activeId, root))
                assertEquals("mvsep-37-six-direct", submitted.single().configurationId)
                val originalAuthority = File(folder, app.stemwise.integration.sourceAuthorityFileNames(1L).first).readBytes()
                option = "6" // The provider has retired the exact admitted six-output route.
                val beforeResume = catalogueReads
                assertEquals(activeId, repository.queueSources(root, VIDEO_ID, listOf("kick")).getOrThrow().jobId)
                assertEquals(beforeResume, catalogueReads)
                assertArrayEquals(originalAuthority,
                    File(folder, app.stemwise.integration.sourceAuthorityFileNames(1L).first).readBytes())
                remoteState = "failed"
                assertEquals(SeparationWorkResult.TERMINAL_FAILURE, repository.process(activeId, root))
                repository.discardFailedPreparation(root.authority(), activeId).getOrThrow()
                val fresh = repository.queueSources(root, VIDEO_ID, listOf("kick")).getOrThrow()
                assertNotEquals(activeId, fresh.jobId)
                assertEquals(beforeResume + 1, catalogueReads)
                val freshAuthority = app.stemwise.integration.parsePortableSourceJobAuthority(
                    File(folder, app.stemwise.integration.sourceAuthorityFileNames(2L).first).readBytes())
                assertEquals(2L, freshAuthority.generation)
                assertEquals("mvsep-37-four-direct", freshAuthority.plan.operations.single().configurationId)
                assertEquals("6", freshAuthority.plan.operations.single().options["add_opt1"])
                assertEquals(1, submissions)
                trio.forEach { (file, bytes) -> assertArrayEquals(bytes, File(folder, file).readBytes()) }
            } finally {
                database.close()
                revokeProviderTrees(tree)
                context.destroy()
                resetProvider()
            }
        }
    }

    @Test
    fun conclusivelyFailedPreparationDiscardsOnlyItsBankAndAllowsExplicitFreshWork() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (withPriorParts in listOf(false, true)) {
            resetProvider()
            val name = "discard-failed-$withPriorParts"
            val context = IsolatedSafetyContext(targetContext, name)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            val tree = providerTreeUri(name)
            val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            grantProviderTrees(tree)
            try {
                val library = File(providerRoot(), name).apply(::requireDirectory)
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val governor = app.stemwise.playback.BackgroundWorkCoordinator()
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
                val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
                val fixture = CurrentSourcePublicationFixture.load(context, VIDEO_ID)
                val original = File(context.cacheDir, "original.opus").apply { writeBytes(fixture.original) }
                publisher.publishOriginal(root, VIDEO_ID, original, fixture.manifest.sourceSha256).getOrThrow()
                val folder = File(library, VIDEO_ID)
                seedCompleteOpusMetadata(folder, VIDEO_ID, fixture.original)
                external.reconcile()
                if (withPriorParts) fixture.publishParts(context, publisher, external, root, "prior-valid-parts")
                val prior = folder.listFiles().orEmpty().filter { it.name in fixture.packageFiles }
                    .associate { it.name to it.readBytes().toList() }
                val source = checkNotNull(dao.currentSource(VIDEO_ID))
                val operations = listOf(
                    MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                        setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), mapOf("bass" to "bass")),
                    MvsepSourceOperation("mvsep-49-direct", 49, mapOf("add_opt1" to "6", "add_opt2" to "0"),
                        setOf("vocals-lead", "vocals-back", "instrum-only", "back-instrum"), mapOf("backing-vocals" to "vocals-back")))
                val plan = MvsepSourcePlan(if (withPriorParts) listOf("bass", "backing-vocals") else listOf("bass"),
                    operations.take(if (withPriorParts) 2 else 1))
                var authority = createPortableSourceJobAuthority(root.libraryId, VIDEO_ID, source.id, source.sha256,
                    plan, 1L, if (withPriorParts) 2L else 1L)
                fun step(transition: SourceJobTransition, remote: String? = null) {
                    authority = transitionPortableSourceJob(authority, transition, authority.updatedAtEpochMs + 1, remote)
                }
                step(SourceJobTransition.ENTER_SUBMISSION); step(SourceJobTransition.ACKNOWLEDGE_REMOTE, "accepted-first")
                if (withPriorParts) {
                    step(SourceJobTransition.BEGIN_DOWNLOAD)
                    val bytes = fixture.parts.getValue(fixture.manifest.lanes.first().fileName)
                    val asset = fixture.manifest.lanes.first().copy(sourceId = "bass", label = "Bass", fileName = "replacement-part-1.flac")
                    val part = File(context.cacheDir, asset.fileName).apply { writeBytes(bytes) }
                    publisher.publishSourcePart(root, VIDEO_ID, asset.fileName, part, asset.sha256,
                        authority.jobId, source.sha256, asset.audibleFrames).getOrThrow()
                    authority = app.stemwise.integration.recordPortableSourceAsset(authority, asset, authority.updatedAtEpochMs + 1)
                    step(SourceJobTransition.ADVANCE_OPERATION); step(SourceJobTransition.ENTER_SUBMISSION)
                    step(SourceJobTransition.ACKNOWLEDGE_REMOTE, "accepted-second")
                }
                val canonical = app.stemwise.integration.sourceAuthorityFileNames(authority.generation).first
                File(folder, canonical).writeBytes(authority.toBytes())
                dao.upsertJob(projectSourceJob(authority))
                val network = object : AppNetworkAccess {
                    override val state = MutableStateFlow(AppNetworkState(true, true, false, activeNetworkWifi = true))
                    override fun currentState() = state.value
                }
                val account = object : MvsepAccountAccess {
                    override val state = MutableStateFlow(MvsepConnectionState(MvsepConnectionStatus.READY))
                    override suspend fun validate(expectedCredential: MvsepCredential?): Result<Unit> = error("No authentication")
                    override fun credential(): MvsepCredential? = error("No new submission")
                    override fun isCurrent(credential: MvsepCredential) = false
                    override fun apiBase() = "https://example.invalid"
                }
                var status = "unrecognized"
                var catalogueReads = 0
                fun provider(renderId: Int, roles: Set<String>, options: Map<String, String> = emptyMap()) =
                    MvsepAlgorithm(renderId, "Fixture", roles.toList(), 1.0, 0,
                        app.stemwise.integration.MvsepCatalogueMetadata(roles, options.map { (name, value) ->
                            app.stemwise.integration.MvsepOptionField(name, name, setOf(value))
                        }, emptyList()))
                val algorithms = listOf(
                    provider(63, setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum")),
                    provider(49, setOf("vocals-lead", "vocals-back", "instrum-only", "back-instrum"),
                        mapOf("add_opt1" to "6", "add_opt2" to "0")))
                val client = object : MvsepServiceClient {
                    override suspend fun sourceAlgorithms(): List<MvsepAlgorithm> {
                        catalogueReads++
                        return algorithms
                    }
                    override suspend fun disablePremium(token: String) = error("No submission setup")
                    override suspend fun status(remoteJobId: String): MvsepRemoteStatus {
                        assertEquals(authority.currentRemoteJobId, remoteJobId)
                        return MvsepRemoteStatus(status, "fixture failure", emptyList())
                    }
                    override suspend fun download(remote: MvsepRemoteFile, target: File) = error("No download after failure")
                }
                val repository = SeparationRepository(context, dao, account, roots, mutations, SongWorkCoordinator(),
                    external, publisher, governor, YoutubeDlEngine(targetContext, network, roots), network, client)
                assertEquals(SeparationWorkResult.TERMINAL_FAILURE, repository.process(authority.jobId, root))
                assertEquals("Accepted work must retain its frozen plan", 0, catalogueReads)
                assertTrue(repository.discardFailedPreparation(root.authority(), authority.jobId).isFailure)
                assertEquals(0, catalogueReads)
                assertTrue(dao.hasRootSwitchBlockingSeparationSafetyFacts())
                repository.retry(root.authority(), authority.jobId).getOrThrow()
                assertEquals("Retry must retain its accepted request", 0, catalogueReads)
                status = "failed"
                assertEquals(SeparationWorkResult.TERMINAL_FAILURE, repository.process(authority.jobId, root))
                assertEquals(0, catalogueReads)
                assertEquals("MVSEP_REMOTE_FAILED", dao.findJobById(authority.jobId)?.lastErrorCode)
                repository.discardFailedPreparation(root.authority(), authority.jobId).getOrThrow()
                assertEquals(0, catalogueReads)
                repository.discardFailedPreparation(root.authority(), authority.jobId).getOrThrow()
                assertEquals(0, catalogueReads)
                assertEquals("OBSOLETE", dao.findJobById(authority.jobId)?.state)
                assertFalse(dao.hasRootSwitchBlockingSeparationSafetyFacts())
                prior.forEach { (file, bytes) -> assertEquals(bytes, File(folder, file).readBytes().toList()) }
                assertFalse(File(folder, "replacement-part-1.flac").exists())
                val other = "other-$name"
                File(providerRoot(), other).apply(::requireDirectory)
                val otherTree = providerTreeUri(other)
                grantProviderTrees(otherTree)
                roots.reportRuntimeAccessFailure(root, SecurityException("Close disposable activation for Library B"))
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(otherTree).getOrThrow()).getOrThrow()
                roots.reportRuntimeAccessFailure(checkNotNull(roots.captureHandle()), SecurityException("Return to Library A"))
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                external.reconcile()
                val returned = checkNotNull(roots.captureHandle())
                assertEquals("Root recovery must not start a new plan", 0, catalogueReads)
                val next = repository.queueSources(returned, VIDEO_ID, plan.selectedSourceIds).getOrThrow()
                assertEquals("Explicit fresh work must consult the current catalogue once", 1, catalogueReads)
                assertNotEquals(authority.jobId, next.jobId)
                assertEquals("CREATED", next.state)
                assertTrue(next.requiresWorker)
                prior.forEach { (file, bytes) -> assertEquals(bytes, File(folder, file).readBytes().toList()) }
                assertFalse(File(folder, "replacement-part-1.flac").exists())
                targetContext.revokeUriPermission(otherTree, grantFlags)
            } finally {
                database.close()
                targetContext.revokeUriPermission(tree, grantFlags)
                context.destroy()
                resetProvider()
            }
        }
    }

    @Test
    fun interruptedOriginalResumesOnlyItsExactJournaledBytesAndPreservesReplacements() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (replacement in listOf(false, true)) {
            resetProvider()
            val name = "original-partial-$replacement"
            val context = IsolatedSafetyContext(targetContext, name)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            val tree = providerTreeUri(name)
            grantProviderTrees(tree)
            try {
                val library = File(providerRoot(), name).apply(::requireDirectory)
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val governor = app.stemwise.playback.BackgroundWorkCoordinator()
                val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
                val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
                val bytes = ByteArray(512 * 1024) { ((it * 37 + 19) % 251).toByte() }
                val source = File(context.cacheDir, "incoming.opus").also { it.writeBytes(bytes) }
                publisher.copyProgressForTest = { throw java.io.IOException("injected interrupted provider write") }
                val interrupted = publisher.publishOriginal(root, VIDEO_ID, source, sha256(bytes))
                assertEquals("injected interrupted provider write", interrupted.exceptionOrNull()?.message)
                val target = File(library, VIDEO_ID).listFiles()!!.single { SelectedLibraryContract.isPublicationStagingName(it.name) }
                assertTrue("Fault did not leave a real partial write", target.length() in 1 until bytes.size.toLong())
                val partial = target.readBytes()
                assertTrue(bytes.copyOf(partial.size).contentEquals(partial))
                if (replacement) target.writeBytes(ByteArray(partial.size) { 91 })
                val beforeRetry = target.readBytes()
                val restarted = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
                val retry = restarted.publishOriginal(root, VIDEO_ID, source, sha256(bytes))
                if (replacement) {
                    assertEquals("ORIGINAL_CONFLICT", retry.exceptionOrNull()?.message)
                    org.junit.Assert.assertArrayEquals(beforeRetry, target.readBytes())
                    org.junit.Assert.assertArrayEquals(bytes, source.readBytes())
                } else {
                    retry.getOrThrow()
                    assertFalse(target.exists())
                    org.junit.Assert.assertArrayEquals(bytes, File(File(library, VIDEO_ID), SelectedLibraryContract.ORIGINAL).readBytes())
                    assertFalse(source.exists())
                }
            } finally {
                database.close()
                revokeProviderTrees(tree)
                resetProvider()
                context.destroy()
            }
        }
    }

    @Test fun interruptedTombstoneWritesSurviveAwayBackAndColdDeletionReplay() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for ((index, fault) in listOf(GoldenQaDocumentsProvider.FAULT_TOMBSTONE_TEMP_EMPTY,
            GoldenQaDocumentsProvider.FAULT_TOMBSTONE_TEMP_PARTIAL,
            GoldenQaDocumentsProvider.FAULT_TOMBSTONE_CANONICAL_PARTIAL).withIndex()) {
            resetProvider()
            val context = IsolatedSafetyContext(targetContext, "tombstone-away-back-$index")
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            val treeA = providerTreeUri("tombstone-away-a-$index")
            val treeB = providerTreeUri("tombstone-away-b-$index")
            grantProviderTrees(treeA, treeB)
            try {
                val rootA = File(providerRoot(), "tombstone-away-a-$index").apply(::requireDirectory)
                File(providerRoot(), "tombstone-away-b-$index").apply(::requireDirectory)
                val target = File(rootA, VIDEO_ID).apply(::requireDirectory)
                File(target, SelectedLibraryContract.ORIGINAL).writeBytes(byteArrayOf(3, 5, 8))
                val unrelated = File(File(rootA, "TOMBSTONE03").apply(::requireDirectory), SelectedLibraryContract.ORIGINAL)
                    .apply { writeBytes(byteArrayOf(13, 21, 34)) }
                val dao = database.libraryDao()
                var mutations = PackageMutationCoordinator(context)
                var roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(treeA).getOrThrow()).getOrThrow()
                val firstA = checkNotNull(roots.captureHandle())
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                dao.insertSong(SongEntity(id = VIDEO_ID, youtubeVideoId = VIDEO_ID, playlistItemId = null,
                    canonicalUrl = "https://music.youtube.com/watch?v=$VIDEO_ID", sourceTitle = "Interrupted deletion", sourceArtist = "StemWise",
                    displayTitle = null, displayArtist = null, durationMs = 10_000L, artworkPath = null,
                    currentlyInSourcePlaylist = false, highLevelState = "READY", acquiredAtEpochMs = 1L, createdAtEpochMs = 1L,
                    canonicalExternal = true, packageDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeA,
                        "root/tombstone-away-a-$index/$VIDEO_ID").toString()))
                seedVerifiedBinding(bindings, mutations, firstA, "youtube-video:$VIDEO_ID", VIDEO_ID)
                seedVerifiedBinding(bindings, mutations, firstA, "youtube-video:$SECOND_VIDEO_ID", SECOND_VIDEO_ID)
                mutations.withPackageMutation(firstA, SECOND_VIDEO_ID, "DELETE_PERMANENT", "prior-delete",
                    requireExactRoot = { roots.requireSameLibrary(firstA) }) { journal ->
                    bindings.markDeletedUnderPackageLock(firstA, "youtube-video:$SECOND_VIDEO_ID", SECOND_VIDEO_ID, "prior-delete", journal)
                    journal.complete()
                }
                val canonical = File(rootA, SelectedLibraryContract.DELETION_TOMBSTONES)
                val prior = canonical.readBytes()
                setProviderFault(fault)
                val failed = ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao()).deletePermanently(firstA, VIDEO_ID)
                assertTrue("Fault did not interrupt the actual tombstone write: $fault", failed.isFailure)
                assertTrue(target.isDirectory)
                val intent = context.filesDir.listFiles().orEmpty().single { it.name.startsWith("source-tombstones-") && it.extension == "json" }
                val retained = intent.readBytes()
                val intended = JSONObject(retained.toString(Charsets.UTF_8)).getString("authority").toByteArray(Charsets.UTF_8)
                assertTrue(decodePortableBlockedAuthority(firstA.libraryId, app.stemwise.enrichment.parseStrictManifestJson(intended)).revision >
                    decodePortableBlockedAuthority(firstA.libraryId, app.stemwise.enrichment.parseStrictManifestJson(prior)).revision)
                if (fault == GoldenQaDocumentsProvider.FAULT_TOMBSTONE_TEMP_PARTIAL) {
                    val partial = File(rootA, SelectedLibraryContract.DELETION_TOMBSTONES_TEMP).readBytes()
                    assertTrue(partial.isNotEmpty())
                    assertFalse("A newer partial revision must not be recoverable from the older prefix",
                        interruptedReservedAuthorityWriteIsRecoverable(partial, listOf(prior), true))
                }
                setProviderFault(GoldenQaDocumentsProvider.FAULT_NONE)
                mutations = PackageMutationCoordinator(context)
                roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(treeB).getOrThrow()).getOrThrow()
                assertArrayEquals(retained, intent.readBytes())
                mutations = PackageMutationCoordinator(context)
                roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(treeA).getOrThrow()).getOrThrow()
                val returnedA = checkNotNull(roots.captureHandle())
                assertEquals(firstA.libraryId, returnedA.libraryId)
                assertTrue(returnedA.epoch > firstA.epoch)
                roots = LibraryRootManager(context, mutations, dao)
                assertTrue(roots.initialize().ready)
                val current = checkNotNull(roots.captureHandle())
                val recoveredBindings = SourceBindingRegistry(context, roots, mutations, dao)
                val trash = ExternalPackageTrash(context, roots, mutations, recoveredBindings, database.libraryDao())
                trash.recoverInterruptedDeletes()
                assertFalse("Confirmed deletion did not resume after root return", target.exists())
                assertEquals(setOf("youtube-video:$VIDEO_ID", "youtube-video:$SECOND_VIDEO_ID"), recoveredBindings.blockedSourceIds(current))
                assertFalse(intent.exists())
                assertArrayEquals(byteArrayOf(13, 21, 34), unrelated.readBytes())
                val settled = directoryState(rootA)
                trash.recoverInterruptedDeletes()
                assertEquals(settled, directoryState(rootA))
            } finally {
                database.close()
                revokeProviderTrees(treeA, treeB)
                resetProvider()
                context.destroy()
            }
        }
    }

    @Test
    fun tombstoneIntentReplaysBeforeAuthorityReadAtEachProviderWriteBoundary() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (checkpoint in listOf("temp-created", "temp-partial", "canonical-partial")) {
            resetProvider()
            val name = "tombstone-$checkpoint"
            val context = IsolatedSafetyContext(targetContext, name)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            val tree = providerTreeUri(name)
            grantProviderTrees(tree)
            try {
                val library = File(providerRoot(), name).apply(::requireDirectory)
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                fun binding(id: String) = SourceBindingEntity(root.libraryId, "youtube-video:$id", id,
                    "DELETED", "delete-$id", 1L)
                val old = encodePortableBlockedAuthority(root.libraryId, 1L, listOf(binding(VIDEO_ID)))
                val expected = encodePortableBlockedAuthority(root.libraryId, 2L,
                    listOf(binding(VIDEO_ID), binding(SECOND_VIDEO_ID)).sortedBy { it.sourceId })
                val untouched = File(File(library, "TOMBSTONE03").apply(::requireDirectory), SelectedLibraryContract.ORIGINAL)
                    .apply { writeBytes(byteArrayOf(5, 8, 13)) }
                val canonical = File(library, SelectedLibraryContract.DELETION_TOMBSTONES).apply { writeBytes(old) }
                val temporary = File(library, SelectedLibraryContract.DELETION_TOMBSTONES_TEMP)
                when (checkpoint) {
                    "temp-created" -> temporary.writeBytes(byteArrayOf())
                    "temp-partial" -> temporary.writeBytes(expected.copyOf(expected.size / 2))
                    else -> { temporary.writeBytes(expected); canonical.writeBytes(expected.copyOf(expected.size / 2)) }
                }
                val rootKey = sha256("${root.libraryId}\n${root.treeUri}".toByteArray(Charsets.UTF_8))
                val intent = File(context.filesDir, "source-tombstones-$rootKey.json").apply {
                    writeText(JSONObject().put("schemaVersion", 1).put("libraryId", root.libraryId)
                        .put("treeUri", root.treeUri.toString()).put("rootEpoch", root.epoch)
                        .put("revision", 2L).put("authority", expected.toString(Charsets.UTF_8)).toString(), Charsets.UTF_8)
                }
                val intentBytes = intent.readBytes()
                val restartedMutations = PackageMutationCoordinator(context)
                val restartedRoots = LibraryRootManager(context, restartedMutations, dao)
                restartedRoots.initialize()
                val restartedRoot = checkNotNull(restartedRoots.captureHandle())
                assertEquals(root.authority(), restartedRoot.authority())
                val restored = SourceBindingRegistry(context, restartedRoots, restartedMutations, dao)
                assertEquals(setOf("youtube-video:$VIDEO_ID", "youtube-video:$SECOND_VIDEO_ID"), restored.blockedSourceIds(restartedRoot))
                org.junit.Assert.assertArrayEquals(expected, canonical.readBytes())
                org.junit.Assert.assertArrayEquals(byteArrayOf(5, 8, 13), untouched.readBytes())
                assertFalse(intent.exists())
                assertFalse(temporary.exists())
                if (checkpoint == "temp-created") {
                    val conflicting = encodePortableBlockedAuthority(root.libraryId, 2L,
                        listOf(binding("TOMBSTONE03")))
                    for (foreign in listOf("{foreign".toByteArray(), conflicting)) {
                        intent.writeBytes(intentBytes)
                        temporary.writeBytes(foreign)
                        val failure = runCatching {
                            SourceBindingRegistry(context, restartedRoots, PackageMutationCoordinator(context), dao)
                                .blockedSourceIds(restartedRoot)
                        }.exceptionOrNull()
                        assertEquals("SOURCE_BINDING_AUTHORITY_INVALID", failure?.message)
                        org.junit.Assert.assertArrayEquals(foreign, temporary.readBytes())
                        org.junit.Assert.assertArrayEquals(expected, canonical.readBytes())
                        org.junit.Assert.assertArrayEquals(intentBytes, intent.readBytes())
                        org.junit.Assert.assertArrayEquals(byteArrayOf(5, 8, 13), untouched.readBytes())
                    }
                    check(temporary.delete())
                    // Keep the numeric token intact: JSONObject would serialize Double(1.0) as 1.
                    val floatingVersion = intentBytes.toString(Charsets.UTF_8)
                        .replace("\"schemaVersion\":1,", "\"schemaVersion\":1.0,")
                        .toByteArray(Charsets.UTF_8)
                    assertFalse(intentBytes.contentEquals(floatingVersion))
                    intent.writeBytes(floatingVersion)
                    val floatingFailure = runCatching {
                        SourceBindingRegistry(context, restartedRoots, PackageMutationCoordinator(context), dao)
                            .blockedSourceIds(restartedRoot)
                    }.exceptionOrNull()
                    assertEquals("SOURCE_BINDING_AUTHORITY_INVALID", floatingFailure?.message)
                    org.junit.Assert.assertArrayEquals(floatingVersion, intent.readBytes())
                    org.junit.Assert.assertArrayEquals(expected, canonical.readBytes())
                    assertFalse(temporary.exists())
                    org.junit.Assert.assertArrayEquals(byteArrayOf(5, 8, 13), untouched.readBytes())
                }
            } finally {
                database.close(); revokeProviderTrees(tree); resetProvider(); context.destroy()
            }
        }
    }

    @Test
    fun assetCreateAndPromotionWithoutReceiptResumeExactlyOnceForOriginalPartAndRemaining() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (fileName in listOf("original.opus", "part-1.flac", "remaining.wv")) for (afterPromotion in listOf(false, true)) {
            resetProvider()
            val name = "asset-precreate-${fileName.substringBefore('.') }-$afterPromotion"
            val context = IsolatedSafetyContext(targetContext, name)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            val tree = providerTreeUri(name)
            grantProviderTrees(tree)
            try {
                val library = File(providerRoot(), name).apply(::requireDirectory)
                val dao = database.libraryDao()
                var mutations = PackageMutationCoordinator(context)
                var roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                var root = checkNotNull(roots.captureHandle())
                val fixture = CurrentSourcePublicationFixture.load(targetContext, VIDEO_ID)
                fun external(): ExternalLibraryRepository = ExternalLibraryRepository(context, roots, dao,
                    SourceBindingRegistry(context, roots, mutations, dao), mutations,
                    app.stemwise.playback.BackgroundWorkCoordinator())
                fun publisher(): CanonicalPackagePublisher {
                    val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                    val governor = app.stemwise.playback.BackgroundWorkCoordinator()
                    return CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external(), governor)
                }
                if (fileName != "original.opus") {
                    val original = File(context.cacheDir, "original.opus").apply { writeBytes(fixture.original) }
                    publisher().publishOriginal(root, VIDEO_ID, original, fixture.manifest.sourceSha256).getOrThrow()
                    seedCompleteOpusMetadata(File(library, VIDEO_ID), VIDEO_ID, fixture.original)
                    external().reconcilePackage(root, VIDEO_ID)
                    val admittedSource = checkNotNull(dao.currentSource(VIDEO_ID))
                    assertEquals(fixture.manifest.sourceSha256, admittedSource.sha256)
                    assertEquals(roots.checkedSongSnapshot(root, VIDEO_ID)?.original?.uri.toString(), admittedSource.localPath)
                }
                val bytes = if (fileName == "original.opus") fixture.original else fixture.parts.getValue(fileName)
                val incoming = File(context.cacheDir, fileName).apply { writeBytes(bytes) }
                val owner = "precreate-owner"
                suspend fun publish(current: CanonicalPackagePublisher): Result<Uri> =
                    if (fileName == "original.opus") current.publishOriginal(root, VIDEO_ID, incoming, sha256(bytes))
                    else current.publishSourcePart(root, VIDEO_ID, fileName, incoming, sha256(bytes), owner,
                        fixture.manifest.sourceSha256, fixture.manifest.audibleFrames)
                val faulted = publisher().apply {
                    val fault = { error("PROCESS_DEATH_BOUNDARY") }
                    if (afterPromotion) assetPromotedBeforeJournalForTest = fault else assetCreatedBeforeJournalForTest = fault
                }
                assertEquals("PROCESS_DEATH_BOUNDARY", publish(faulted).exceptionOrNull()?.message)
                val packageDirectory = File(library, VIDEO_ID)
                if (!afterPromotion) {
                    assertFalse(File(packageDirectory, fileName).exists())
                    assertEquals(0L, packageDirectory.listFiles()!!.single { SelectedLibraryContract.isPublicationStagingName(it.name) }.length())
                }
                mutations = PackageMutationCoordinator(context)
                roots = LibraryRootManager(context, mutations, dao)
                assertTrue(roots.initialize().ready)
                root = checkNotNull(roots.captureHandle())
                val restarted = publisher()
                if (!afterPromotion && fileName != "original.opus") {
                    val unrelatedCanonical = File(packageDirectory, fileName).apply { writeBytes(bytes) }
                    assertEquals("SOURCE_PART_RECOVERY_REQUIRED", publish(restarted).exceptionOrNull()?.message)
                    org.junit.Assert.assertArrayEquals(bytes, unrelatedCanonical.readBytes())
                    org.junit.Assert.assertArrayEquals(bytes, incoming.readBytes())
                    assertEquals(0L, packageDirectory.listFiles()!!.single { SelectedLibraryContract.isPublicationStagingName(it.name) }.length())
                    check(unrelatedCanonical.delete())
                }
                if (afterPromotion && fileName != "original.opus") {
                    val canonical = File(packageDirectory, fileName)
                    val changed = bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
                    canonical.writeBytes(changed)
                    assertEquals("SOURCE_PART_RECOVERY_REQUIRED", runCatching {
                        restarted.recoverPublishedSourcePart(root, VIDEO_ID, fileName, owner)
                    }.exceptionOrNull()?.message)
                    org.junit.Assert.assertArrayEquals(changed, canonical.readBytes())
                    org.junit.Assert.assertArrayEquals(bytes, incoming.readBytes())
                    canonical.writeBytes(bytes)
                    assertEquals(sha256(bytes), restarted.recoverPublishedSourcePart(root, VIDEO_ID, fileName, owner)?.sha256)
                    assertEquals(sha256(bytes), restarted.recoverPublishedSourcePart(root, VIDEO_ID, fileName, owner)?.sha256)
                    org.junit.Assert.assertArrayEquals(bytes, incoming.readBytes())
                }
                publish(restarted).getOrThrow()
                assertFalse(incoming.exists())
                org.junit.Assert.assertArrayEquals(bytes, File(packageDirectory, fileName).readBytes())
                assertEquals(1, packageDirectory.listFiles()!!.count { it.name == fileName })
                assertTrue(packageDirectory.listFiles()!!.none { SelectedLibraryContract.isPublicationStagingName(it.name) })
                if (fileName != "original.opus") {
                    assertEquals(sha256(bytes), restarted.recoverPublishedSourcePart(root, VIDEO_ID, fileName, owner)?.sha256)
                    val canonical = File(packageDirectory, fileName)
                    val changed = bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
                    canonical.writeBytes(changed)
                    assertEquals("SOURCE_PART_RECOVERY_REQUIRED", runCatching {
                        restarted.recoverPublishedSourcePart(root, VIDEO_ID, fileName, owner)
                    }.exceptionOrNull()?.message)
                    org.junit.Assert.assertArrayEquals(changed, canonical.readBytes())
                }
            } finally { database.close(); revokeProviderTrees(tree); resetProvider(); context.destroy() }
        }
    }

    @Test
    fun originalRestartRecoversTargetAcquiredWithoutCatchSettlementInEitherDirectoryKind() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (preExisting in listOf(false, true)) for (replacement in listOf(false, true)) {
            resetProvider()
            val name = "original-death-$preExisting-$replacement"
            val context = IsolatedSafetyContext(targetContext, name)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            val tree = providerTreeUri(name)
            grantProviderTrees(tree)
            try {
                val library = File(providerRoot(), name).apply(::requireDirectory)
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val directory = if (preExisting) {
                    File(library, VIDEO_ID).apply(::requireDirectory)
                    checkNotNull(roots.checkedSongSnapshot(root, VIDEO_ID)).directory.uri
                } else roots.createSongDirectoryDocument(root, VIDEO_ID)
                val bytes = ByteArray(256 * 1024) { ((it * 43 + 17) % 251).toByte() }
                val incoming = File(context.cacheDir, "incoming.opus").apply { writeBytes(bytes) }
                val hash = sha256(bytes)
                // Construct the durable process-death boundary directly: no publisher catch can
                // settle the observed prefix into this TARGET_ACQUIRED journal.
                val failed = runCatching {
                    mutations.withPackageMutation(root, VIDEO_ID, "FLAT_ASSET_PUBLISH", "original-death",
                        requireExactRoot = { roots.requireSameLibrary(root) }) { journal ->
                        val snapshot = roots.checkedBoundSongSnapshot(root, VIDEO_ID, directory.toString())
                        val target = roots.createExactSongChild(root, snapshot, "audio/ogg", SelectedLibraryContract.ORIGINAL).document
                        val details = JSONObject().put("fileName", SelectedLibraryContract.ORIGINAL)
                            .put("sha256", hash).put("byteLength", bytes.size.toLong())
                            .put("targetUri", target.uri.toString())
                            .put("targetDocumentId", DocumentsContract.getDocumentId(target.uri))
                            .put("createdByOperation", true).put("partialByteLength", 0L)
                            .put("partialSha256", sha256(byteArrayOf()))
                        if (!preExisting) details.put("createdDirectoryUri", directory.toString())
                        journal.write("TARGET_ACQUIRED", details)
                        File(File(library, VIDEO_ID), SelectedLibraryContract.ORIGINAL).writeBytes(bytes.copyOf(65_537))
                        error("PROCESS_DEATH_BOUNDARY")
                    }
                }.exceptionOrNull()
                assertEquals("PROCESS_DEATH_BOUNDARY", failed?.message)
                val target = File(File(library, VIDEO_ID), SelectedLibraryContract.ORIGINAL)
                if (replacement) target.writeBytes(ByteArray(65_537) { 91 })
                val preserved = target.readBytes()
                val restartedMutations = PackageMutationCoordinator(context)
                val restartedRoots = LibraryRootManager(context, restartedMutations, dao)
                restartedRoots.initialize()
                val restartedRoot = checkNotNull(restartedRoots.captureHandle())
                assertEquals(root.authority(), restartedRoot.authority())
                val bindings = SourceBindingRegistry(context, restartedRoots, restartedMutations, dao)
                val governor = app.stemwise.playback.BackgroundWorkCoordinator()
                val external = ExternalLibraryRepository(context, restartedRoots, dao, bindings, restartedMutations, governor)
                val restarted = CanonicalPackagePublisher(context, restartedRoots, dao, restartedMutations, bindings, external, governor)
                val result = restarted.publishOriginal(restartedRoot, VIDEO_ID, incoming, hash)
                if (replacement) {
                    assertEquals("ORIGINAL_CONFLICT", result.exceptionOrNull()?.message)
                    org.junit.Assert.assertArrayEquals(preserved, target.readBytes())
                    org.junit.Assert.assertArrayEquals(bytes, incoming.readBytes())
                } else {
                    result.getOrThrow()
                    org.junit.Assert.assertArrayEquals(bytes, target.readBytes())
                    assertFalse(incoming.exists())
                }
            } finally {
                database.close(); revokeProviderTrees(tree); resetProvider(); context.destroy()
            }
        }
    }

    @Test
    fun terminalStemCleanupReplaysOnlyTheExactCurrentJobReceipt() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedSafetyContext(targetContext, "owned-stem-cleanup")
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val rootUri = providerTreeUri("owned-stem-cleanup")
        grantProviderTrees(rootUri)
        try {
            val providerLibrary = File(providerRoot(), "owned-stem-cleanup")
                .apply(::requireDirectory)
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            val candidate = roots.validateCandidate(rootUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            dao.insertSong(
                SongEntity(
                    id = VIDEO_ID,
                    youtubeVideoId = VIDEO_ID,
                    playlistItemId = null,
                    canonicalUrl = "https://music.youtube.com/watch?v=$VIDEO_ID",
                    sourceTitle = "Owned cleanup",
                    sourceArtist = "StemWise",
                    displayTitle = null,
                    displayArtist = null,
                    durationMs = 10_000L,
                    artworkPath = null,
                    currentlyInSourcePlaylist = false,
                    highLevelState = "PREPARING",
                    acquiredAtEpochMs = 1L,
                    createdAtEpochMs = 1L
                )
            )

            lateinit var external: ExternalLibraryRepository
            fun publisher(
                activeRoots: LibraryRootManager,
                activeMutations: PackageMutationCoordinator
            ): CanonicalPackagePublisher {
                val bindings = SourceBindingRegistry(context, activeRoots, activeMutations, dao)
                val governor = app.stemwise.playback.BackgroundWorkCoordinator()
                external = ExternalLibraryRepository(
                    context,
                    activeRoots,
                    dao,
                    bindings,
                    activeMutations,
                    governor
                )
                return CanonicalPackagePublisher(
                    context,
                    activeRoots,
                    dao,
                    activeMutations,
                    bindings,
                    external,
                    governor
                )
            }

            val sourceFixture = CurrentSourcePublicationFixture.load(targetContext, VIDEO_ID)
            val originalBytes = sourceFixture.original
            val privateOriginal = File(context.cacheDir, "owned-original.opus").apply {
                writeBytes(originalBytes)
            }
            publisher(roots, mutations).publishOriginal(handle, VIDEO_ID, privateOriginal,
                sourceFixture.manifest.sourceSha256).getOrThrow()
            val packageDirectory = File(providerLibrary, VIDEO_ID)
            seedCompleteOpusMetadata(packageDirectory, VIDEO_ID, originalBytes)
            external.reconcilePackage(handle, VIDEO_ID)
            val preExistingBytes = sourceFixture.parts.getValue("part-1.flac")
            val preExisting = File(packageDirectory, "part-1.flac").apply { writeBytes(preExistingBytes) }
            val ownedBytes = sourceFixture.parts.getValue("part-2.flac")
            val privateOwned = File(context.cacheDir, "part-2.flac").apply { writeBytes(ownedBytes) }
            val operationOwnerId = "c340694a-3786-42cf-a804-8229871123ef"
            val interruptedPublisher = publisher(roots, mutations).apply {
                copyProgressForTest = { throw java.io.IOException("injected partial source publication") }
            }
            val publication = interruptedPublisher.publishSourcePart(handle, VIDEO_ID, "part-2.flac",
                privateOwned, sha256(ownedBytes), operationOwnerId, sourceFixture.manifest.sourceSha256,
                sourceFixture.manifest.audibleFrames)
            assertEquals("injected partial source publication", publication.exceptionOrNull()?.message)
            // Exercise cleanup of a journaled canonical target independently of precreate staging.
            val staged = packageDirectory.listFiles()!!.single { SelectedLibraryContract.isPublicationStagingName(it.name) }
            val canonicalPartial = File(packageDirectory, "part-2.flac")
            check(staged.renameTo(canonicalPartial))
            val originalReceipt = File(context.filesDir, "operation-journals").listFiles()!!.single()
            val originalEnvelope = JSONObject(originalReceipt.readText(Charsets.UTF_8))
            val canonicalTarget = checkNotNull(roots.checkedSongSnapshot(handle, VIDEO_ID)?.file("part-2.flac"))
            originalEnvelope.getJSONObject("details").apply {
                remove("stagingName")
                put("targetUri", canonicalTarget.uri.toString())
                put("targetDocumentId", canonicalTarget.documentId)
            }
            originalReceipt.writeText(originalEnvelope.toString(), Charsets.UTF_8)
            val owned = File(packageDirectory, "part-2.flac")
            val partialBytes = owned.readBytes()
            assertTrue(partialBytes.size in 1 until ownedBytes.size)
            assertTrue(owned.isFile)
            assertTrue(preExisting.isFile)
            val retainedReceipt = File(context.filesDir, "operation-journals")
                .listFiles().orEmpty().single()
            val receipt = JSONObject(retainedReceipt.readText())
            assertEquals("TARGET_ACQUIRED", receipt.getString("state"))
            assertEquals(
                operationOwnerId,
                receipt.getJSONObject("details").getString("operationOwnerId")
            )

            // Reconstruct every mutation/publication owner: only the app-private receipt links the
            // selected-root stem to this exact job after process death.
            val restartedMutations = PackageMutationCoordinator(context)
            val restartedRoots = LibraryRootManager(context, restartedMutations, dao)
            assertTrue(restartedRoots.initialize().ready)
            val restartedHandle = checkNotNull(restartedRoots.captureHandle())
            val restartedPublisher = publisher(restartedRoots, restartedMutations)
            val foreignRecovery = runCatching { restartedPublisher.recoverPublishedSourcePart(
                restartedHandle, VIDEO_ID, "part-2.flac", "foreign-operation") }
            assertEquals("SOURCE_PART_RECOVERY_REQUIRED", foreignRecovery.exceptionOrNull()?.message)
            assertEquals(partialBytes.toList(), owned.readBytes().toList())
            setProviderFault(GoldenQaDocumentsProvider.FAULT_OPEN_SECURITY)
            val unreadable = runCatching { restartedPublisher.recoverPublishedSourcePart(
                restartedHandle, VIDEO_ID, "part-2.flac", operationOwnerId) }
            assertTrue(unreadable.isFailure)
            setProviderFault(GoldenQaDocumentsProvider.FAULT_NONE)
            assertEquals(partialBytes.toList(), owned.readBytes().toList())
            assertTrue(owned.isFile)
            assertTrue(
                File(context.filesDir, "operation-journals").listFiles().orEmpty().any { file ->
                    JSONObject(file.readText()).optJSONObject("details")
                        ?.optString("operationOwnerId") == operationOwnerId
                }
            )
            setProviderFault(GoldenQaDocumentsProvider.FAULT_FILE_DELETE_FALSE_SUCCESS)
            val falseSuccess = runCatching { restartedPublisher.recoverPublishedSourcePart(
                restartedHandle, VIDEO_ID, "part-2.flac", operationOwnerId) }
            assertTrue(falseSuccess.isFailure)
            assertEquals("EXTERNAL_COMMIT_FAILED", falseSuccess.exceptionOrNull()?.message)
            assertEquals(partialBytes.toList(), owned.readBytes().toList())
            assertTrue(
                File(context.filesDir, "operation-journals").listFiles().orEmpty().any { file ->
                    JSONObject(file.readText()).optJSONObject("details")
                        ?.optString("operationOwnerId") == operationOwnerId
                }
            )
            setProviderFault(GoldenQaDocumentsProvider.FAULT_FILE_DELETE_CHILD_QUERY_UNAVAILABLE)
            val interrupted = runCatching { restartedPublisher.recoverPublishedSourcePart(
                restartedHandle, VIDEO_ID, "part-2.flac", operationOwnerId) }
            assertTrue(interrupted.isFailure)
            assertEquals("ROOT_UNAVAILABLE", (interrupted.exceptionOrNull() as LibraryRootException).code)
            assertFalse(owned.exists())
            assertEquals(preExistingBytes.toList(), preExisting.readBytes().toList())
            val pruningReceipt = File(context.filesDir, "operation-journals").listFiles().orEmpty().single()
            assertEquals(retainedReceipt, pruningReceipt)
            val pruning = JSONObject(pruningReceipt.readText(Charsets.UTF_8))
            assertEquals("PRUNING_PARTIAL_SOURCE", pruning.getString("state"))
            val pruningDetails = pruning.getJSONObject("details")
            assertEquals(operationOwnerId, pruningDetails.getString("operationOwnerId"))
            assertEquals(canonicalTarget.uri.toString(), pruningDetails.getString("targetUri"))
            assertEquals(canonicalTarget.documentId, pruningDetails.getString("targetDocumentId"))

            setProviderFault(GoldenQaDocumentsProvider.FAULT_NONE)
            val replayMutations = PackageMutationCoordinator(context)
            val replayRoots = LibraryRootManager(context, replayMutations, dao)
            assertTrue(replayRoots.initialize().ready)
            assertNull(publisher(replayRoots, replayMutations).recoverPublishedSourcePart(
                checkNotNull(replayRoots.captureHandle()), VIDEO_ID, "part-2.flac", operationOwnerId))
            assertFalse(owned.exists())
            assertEquals(preExistingBytes.toList(), preExisting.readBytes().toList())
            assertTrue(File(context.filesDir, "operation-journals").listFiles().orEmpty().isEmpty())

            // A completed receipt has stronger authority than an interrupted target: changed
            // verified bytes must never enter the partial-file pruning path.
            val replayHandle = checkNotNull(replayRoots.captureHandle())
            val replayPublisher = publisher(replayRoots, replayMutations)
            replayPublisher.publishSourcePart(replayHandle, VIDEO_ID, "part-2.flac", privateOwned,
                sha256(ownedBytes), operationOwnerId, sourceFixture.manifest.sourceSha256,
                sourceFixture.manifest.audibleFrames).getOrThrow()
            val replacementBytes = ByteArray(ownedBytes.size) { 91 }
            owned.writeBytes(replacementBytes)
            val changedVerified = runCatching { replayPublisher.recoverPublishedSourcePart(
                replayHandle, VIDEO_ID, "part-2.flac", operationOwnerId) }
            assertEquals("SOURCE_PART_RECOVERY_REQUIRED", changedVerified.exceptionOrNull()?.message)
            assertEquals(replacementBytes.toList(), owned.readBytes().toList())
            owned.writeBytes(ownedBytes)
            assertEquals(sha256(ownedBytes), replayPublisher.recoverPublishedSourcePart(
                replayHandle, VIDEO_ID, "part-2.flac", operationOwnerId)?.sha256)
            assertEquals(preExistingBytes.toList(), preExisting.readBytes().toList())
        } finally {
            setProviderFault(GoldenQaDocumentsProvider.FAULT_NONE)
            database.close()
            revokeProviderTrees(rootUri)
            resetProvider()
            context.destroy()
        }
    }

    @Test
    fun mutationJournalRejectsAnotherRootAndPreservesMonotonicFacts() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = IsolatedSafetyContext(targetContext, "journal")
        try {
            val rootDirectory = File(context.filesDir, "disposable-root").apply(::requireDirectory)
            val document = DocumentFile.fromFile(rootDirectory)
            val rootA = RootHandle(
                libraryId = "00000000-0000-4000-8000-000000000001",
                treeUri = Uri.parse("content://qa/tree/root-a"),
                rootDocument = document,
                epoch = 7L
            )
            val rootB = rootA.copy(treeUri = Uri.parse("content://qa/tree/root-b"))
            val staleEpoch = rootA.copy(epoch = 8L)
            val coordinator = PackageMutationCoordinator(context)
            var admittedRoot = rootA
            val transactionId = "fault-root-scope-0001"
            val journalFile = File(context.filesDir, "operation-journals/$transactionId.json")

            assertTrue(
                runCatching {
                    coordinator.withPackageMutation(
                        rootA,
                        VIDEO_ID,
                        "FAULT_REHEARSAL",
                        transactionId,
                        requireExactRoot = { requireAuthority(admittedRoot, rootA) }
                    ) { journal ->
                        journal.write(
                            "COPYING",
                            JSONObject().put("sha256", "a".repeat(64))
                        )
                        error("INJECT_AFTER_COPYING")
                    }
                }.exceptionOrNull()?.message == "INJECT_AFTER_COPYING"
            )
            assertEquals(2L, JSONObject(journalFile.readText()).getLong("sequence"))

            assertTrue(
                runCatching {
                    coordinator.withPackageMutation(
                        rootA,
                        VIDEO_ID,
                        "FAULT_REHEARSAL",
                        transactionId,
                        requireExactRoot = { requireAuthority(admittedRoot, rootA) }
                    ) { journal ->
                        journal.write("VERIFIED", JSONObject().put("path", "original.opus"))
                        error("INJECT_AFTER_VERIFIED")
                    }
                }.exceptionOrNull()?.message == "INJECT_AFTER_VERIFIED"
            )
            val advanced = JSONObject(journalFile.readText())
            assertEquals(3L, advanced.getLong("sequence"))
            assertEquals("VERIFIED", advanced.getString("state"))
            assertEquals("a".repeat(64), advanced.getJSONObject("details").getString("sha256"))
            assertEquals("original.opus", advanced.getJSONObject("details").getString("path"))

            listOf(rootB, staleEpoch).forEach { wrongRoot ->
                admittedRoot = wrongRoot
                assertEquals(
                    "JOURNAL_ROOT_MISMATCH",
                    runCatching {
                        coordinator.withPackageMutation(
                            wrongRoot,
                            VIDEO_ID,
                            "FAULT_REHEARSAL",
                            transactionId,
                            requireExactRoot = { requireAuthority(admittedRoot, wrongRoot) }
                        ) { error("mismatched root must never enter the mutation") }
                    }.exceptionOrNull()?.message
                )
                assertEquals(3L, JSONObject(journalFile.readText()).getLong("sequence"))
            }

            admittedRoot = rootA
            coordinator.withPackageMutation(
                rootA,
                VIDEO_ID,
                "FAULT_REHEARSAL",
                transactionId,
                requireExactRoot = { requireAuthority(admittedRoot, rootA) }
            ) { it.complete(JSONObject().put("result", "RECOVERED")) }
            assertFalse(journalFile.exists())
        } finally {
            context.destroy()
        }
    }

    @Test
    fun everyCanonicalVideoIdCanMutateAndReplayWithoutRelaxingPathSafety() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = IsolatedSafetyContext(targetContext, "canonical-package-identity")
        try {
            val root = RootHandle(
                libraryId = "00000000-0000-4000-8000-000000000001",
                treeUri = Uri.parse("content://qa/tree/root"),
                rootDocument = DocumentFile.fromFile(context.filesDir),
                epoch = 1L
            )
            val coordinator = PackageMutationCoordinator(context)
            listOf("_1234567890", "-1234567890", "A1234567890").forEachIndexed { index, id ->
                val transaction = "canonical-id-$index"
                coordinator.withPackageMutation(
                    root, id, "FAULT_REHEARSAL", transaction,
                    requireExactRoot = { requireAuthority(root, root) }
                ) { journal -> journal.write("VERIFIED", JSONObject().put("identity", id)) }
                val file = File(context.filesDir, "operation-journals/$transaction.json")
                val record = checkNotNull(readAtomicJournal(file, "JOURNAL_CORRUPT"))
                assertEquals(id, requireAtomicJournalEnvelope(record, "JOURNAL_CORRUPT").packageId)
                PackageMutationCoordinator(context).withPackageMutation(
                    root, id, "FAULT_REHEARSAL", transaction,
                    requireExactRoot = { requireAuthority(root, root) }
                ) { journal ->
                    assertEquals(id, journal.recoveredString("VERIFIED", "identity"))
                    journal.complete()
                }
                assertFalse(file.exists())
            }
            listOf("", "short", "123456789012", "../12345678", "foo.bar1234", "12345/78901").forEach { id ->
                val error = runCatching {
                    coordinator.withPackageMutation(
                        root, id, "FAULT_REHEARSAL", "invalid-package",
                        requireExactRoot = { requireAuthority(root, root) }
                    ) { error("Invalid package entered mutation") }
                }.exceptionOrNull()
                assertEquals(id, "PACKAGE_ID_INVALID", error?.message)
                assertFalse(File(context.filesDir, "operation-journals/invalid-package.json").exists())
            }
        } finally {
            context.destroy()
        }
    }

    @Test
    fun sourceJournalReconstructsTheExactLaterRevisionAfterFreshCoordinator() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val original = GoldenSongMediaFixture.originalOpus(targetContext, 1_200L)
        val plan = MvsepSourcePlan(listOf("bass"), listOf(
            MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"),
                mapOf("bass" to "bass"))))
        for (checkpoint in listOf("initial-temp-created", "initial-temp-partial", "initial-canonical-partial",
                "ack-temp-created", "ack-temp-partial", "ack-canonical-partial", "ack-foreign-copy")) {
            resetProvider()
            val context = IsolatedSafetyContext(targetContext, "source-$checkpoint")
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries().build()
            val tree = providerTreeUri("source-$checkpoint")
            grantProviderTrees(tree)
            try {
                val library = File(providerRoot(), "source-$checkpoint").apply(::requireDirectory)
                val folder = File(library, VIDEO_ID).apply(::requireDirectory)
                val originalFile = File(folder, SelectedLibraryContract.ORIGINAL).apply { writeBytes(original) }
                seedCompleteOpusMetadata(folder, VIDEO_ID, original)
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val governor = app.stemwise.playback.BackgroundWorkCoordinator()
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor).reconcile()
                val source = checkNotNull(dao.currentSource(VIDEO_ID))
                val created = createPortableSourceJobAuthority(root.libraryId, VIDEO_ID,
                    source.id, source.sha256, plan, 1L)
                val submitting = transitionPortableSourceJob(created, SourceJobTransition.ENTER_SUBMISSION, 2L)
                val acknowledged = transitionPortableSourceJob(submitting,
                    SourceJobTransition.ACKNOWLEDGE_REMOTE, 3L, "accepted-$checkpoint")
                val initial = checkpoint.startsWith("initial-")
                val intended = if (initial) created else acknowledged
                val retryEntry = checkpoint == "ack-temp-created"
                val priorJob = projectSourceJob(if (initial) created else submitting).let {
                    if (retryEntry) it.copy(state = "FAILED", lastErrorCode = "INTERRUPTED") else it
                }
                dao.upsertJob(priorJob)
                val (canonicalName, temporaryName) = app.stemwise.integration.sourceAuthorityFileNames(intended.generation)
                val canonical = File(folder, canonicalName)
                val temporary = File(folder, temporaryName)
                if (!initial) canonical.writeBytes(submitting.toBytes())
                when {
                    checkpoint.endsWith("temp-created") -> temporary.writeBytes(byteArrayOf())
                    checkpoint.endsWith("temp-partial") -> temporary.writeBytes(intended.toBytes().copyOf(intended.toBytes().size / 2))
                    checkpoint.endsWith("canonical-partial") -> {
                        temporary.writeBytes(intended.toBytes())
                        canonical.writeBytes(intended.toBytes().copyOf(intended.toBytes().size / 2))
                    }
                    else -> temporary.writeBytes("{foreign-provider-bytes".toByteArray())
                }
                val transaction = "source-${created.jobId}"
                assertEquals("INJECT_AFTER_AUTHORITY_JOURNAL", runCatching {
                    mutations.withPackageMutation(root, VIDEO_ID, "SOURCE_JOB_AUTHORITY_V1", transaction,
                        requireExactRoot = { roots.requireSameLibrary(root) }) { journal ->
                        journal.write("AUTHORITY_WRITING", JSONObject().put("authorityJson",
                            intended.toBytes().toString(Charsets.UTF_8)))
                        error("INJECT_AFTER_AUTHORITY_JOURNAL")
                    }
                }.exceptionOrNull()?.message)
                val journalFile = File(context.filesDir, "operation-journals/$transaction.json")
                val journalBefore = journalFile.readBytes()
                val canonicalBefore = canonical.takeIf(File::isFile)?.readBytes()
                val temporaryBefore = temporary.readBytes()
                val restartedMutations = PackageMutationCoordinator(context)
                val restartedRoots = LibraryRootManager(context, restartedMutations, dao)
                restartedRoots.initialize()
                val restartedRoot = checkNotNull(restartedRoots.captureHandle())
                assertEquals(root.authority(), restartedRoot.authority())
                var submissions = 0
                var otherExternalCalls = 0
                val observedRemoteIds = mutableListOf<String>()
                val network = object : AppNetworkAccess {
                    override val state = MutableStateFlow(AppNetworkState(true, true, false,
                        activeNetworkWifi = true))
                    override fun currentState() = state.value
                }
                val account = object : MvsepAccountAccess {
                    override val state = MutableStateFlow(MvsepConnectionState(MvsepConnectionStatus.READY))
                    override suspend fun validate(expectedCredential: MvsepCredential?): Result<Unit> =
                        error("Recovered admission must not authenticate a fresh submission")
                    override fun credential(): MvsepCredential? = error("Recovered admission requested upload credentials")
                    override fun isCurrent(credential: MvsepCredential) = false
                    override fun apiBase() = "https://example.invalid"
                }
                val client = object : MvsepServiceClient {
                    override suspend fun sourceAlgorithms(): List<MvsepAlgorithm> {
                        otherExternalCalls++; error("Recovered frozen plan requested a live catalogue")
                    }
                    override suspend fun disablePremium(token: String) {
                        otherExternalCalls++; error("Recovered admission entered submission setup")
                    }
                    override suspend fun submitConfigured(token: String, source: File, operation: MvsepSourceOperation,
                        isAuthorized: () -> Boolean, onSubmissionEntered: suspend () -> Unit,
                        onAcknowledged: suspend (String) -> Unit, onRejected: suspend (String) -> Unit): String {
                        submissions++; error("Recovered authority was resubmitted")
                    }
                    override suspend fun status(remoteJobId: String): MvsepRemoteStatus {
                        observedRemoteIds += remoteJobId
                        return MvsepRemoteStatus("processing", null, emptyList())
                    }
                    override suspend fun download(remote: MvsepRemoteFile, target: File) {
                        otherExternalCalls++; error("A processing job attempted output download")
                    }
                }
                val restoredBindings = SourceBindingRegistry(context, restartedRoots, restartedMutations, dao)
                val external = ExternalLibraryRepository(context, restartedRoots, dao, restoredBindings,
                    restartedMutations, governor)
                val publisher = CanonicalPackagePublisher(context, restartedRoots, dao, restartedMutations,
                    restoredBindings, external, governor)
                val repository = SeparationRepository(context, dao, account, restartedRoots, restartedMutations,
                    SongWorkCoordinator(), external, publisher, governor,
                    YoutubeDlEngine(targetContext, network, restartedRoots), network, client)
                if (checkpoint == "ack-foreign-copy") {
                    assertEquals(listOf(app.stemwise.integration.SourceAuthorityRecoveryIssue(VIDEO_ID,
                        "SOURCE_JOB_AUTHORITY_RECOVERY_REQUIRED")),
                        repository.reconcilePortableJobAuthority(restartedRoot))
                    org.junit.Assert.assertArrayEquals(canonicalBefore, canonical.readBytes())
                    org.junit.Assert.assertArrayEquals(temporaryBefore, temporary.readBytes())
                    org.junit.Assert.assertArrayEquals(journalBefore, journalFile.readBytes())
                    assertEquals(priorJob, dao.findJobById(created.jobId))
                } else {
                    // Exercise the real entry that must replay before its first sidecar parse.
                    when {
                        initial -> assertEquals(created.jobId, repository.queueSources(restartedRoot,
                            VIDEO_ID, plan.selectedSourceIds).getOrThrow().jobId)
                        retryEntry -> repository.retry(restartedRoot.authority(), created.jobId).getOrThrow()
                        else -> assertTrue(repository.reconcilePortableJobAuthority(restartedRoot).isEmpty())
                    }
                    org.junit.Assert.assertArrayEquals(intended.toBytes(), canonical.readBytes())
                    assertFalse(temporary.exists())
                    assertFalse(journalFile.exists())
                    val restored = checkNotNull(dao.findJobById(created.jobId))
                    assertEquals(created.jobId, restored.id)
                    assertEquals(source.id, restored.sourceVersionId)
                    assertEquals("source-plan:${created.planSha256}", restored.modelId)
                    assertEquals(intended.state, restored.state)
                    assertEquals(intended.currentRemoteJobId, restored.remoteJobId)
                    if (!initial) {
                        assertEquals(SeparationWorkResult.PENDING, repository.process(created.jobId, restartedRoot))
                        assertEquals(listOf(acknowledged.currentRemoteJobId), observedRemoteIds)
                        org.junit.Assert.assertArrayEquals(intended.toBytes(), canonical.readBytes())
                        assertEquals(acknowledged.currentRemoteJobId, dao.findJobById(created.jobId)?.remoteJobId)
                    }
                }
                assertEquals(0, submissions)
                assertEquals(0, otherExternalCalls)
                org.junit.Assert.assertArrayEquals(original, originalFile.readBytes())
            } finally {
                database.close()
                revokeProviderTrees(tree)
                resetProvider()
                context.destroy()
            }
        }
    }

    @Test
    fun mutationJournalRollsBackBackupOnlyAndPartialBaseBeforeReplay() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = IsolatedSafetyContext(targetContext, "atomic-journal-rollback")
        try {
            val document = DocumentFile.fromFile(
                File(context.filesDir, "disposable-root").apply(::requireDirectory)
            )
            val root = RootHandle(
                libraryId = "00000000-0000-4000-8000-000000000002",
                treeUri = Uri.parse("content://qa/tree/atomic-journal"),
                rootDocument = document,
                epoch = 9L
            )
            val coordinator = PackageMutationCoordinator(context)
            listOf(false, true).forEachIndexed { index, leavePartialBase ->
                val transactionId = "atomic-journal-rollback-$index"
                val journal = File(
                    context.filesDir,
                    "operation-journals/$transactionId.json"
                )
                val firstFailure = runCatching {
                    coordinator.withPackageMutation(
                        root,
                        VIDEO_ID,
                        "FAULT_REHEARSAL",
                        transactionId,
                        requireExactRoot = {}
                    ) { record ->
                        record.write("COPYING", JSONObject().put("proof", "durable-$index"))
                        error("INJECT_BACKUP_$index")
                    }
                }.exceptionOrNull()
                assertEquals("INJECT_BACKUP_$index", firstFailure?.message)

                val backup = File(journal.path + ".bak")
                assertTrue(journal.renameTo(backup))
                if (leavePartialBase) journal.writeText("{\"partial\":", Charsets.UTF_8)
                assertTrue(backup.isFile)

                val replayFailure = runCatching {
                    coordinator.withPackageMutation(
                        root,
                        VIDEO_ID,
                        "FAULT_REHEARSAL",
                        transactionId,
                        requireExactRoot = {}
                    ) { record ->
                        record.write("VERIFIED", JSONObject().put("replayed", true))
                        error("INJECT_REPLAY_$index")
                    }
                }.exceptionOrNull()
                assertEquals("INJECT_REPLAY_$index", replayFailure?.message)
                val replayed = JSONObject(journal.readText(Charsets.UTF_8))
                assertEquals(3L, replayed.getLong("sequence"))
                assertEquals("durable-$index", replayed.getJSONObject("details").getString("proof"))
                assertTrue(replayed.getJSONObject("details").getBoolean("replayed"))
                assertFalse(backup.exists())

                coordinator.withPackageMutation(
                    root,
                    VIDEO_ID,
                    "FAULT_REHEARSAL",
                    transactionId,
                    requireExactRoot = {}
                ) { it.complete(JSONObject().put("result", "RECOVERED")) }
                assertFalse(journal.exists())
                assertFalse(backup.exists())
            }
        } finally {
            context.destroy()
        }
    }

    @Test
    fun packageMutationAdmissionIsRootAtomicSideEffectFreeAndCapacityBounded() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = IsolatedSafetyContext(targetContext, "pre-journal-admission")
        try {
            kotlinx.coroutines.coroutineScope {
            val document = DocumentFile.fromFile(
                File(context.filesDir, "disposable-root").apply(::requireDirectory)
            )
            val rootA = RootHandle(
                libraryId = "00000000-0000-4000-8000-000000000011",
                treeUri = Uri.parse("content://qa/tree/root-a"),
                rootDocument = document,
                epoch = 11L
            )
            val rootB = rootA.copy(
                libraryId = "00000000-0000-4000-8000-000000000012",
                treeUri = Uri.parse("content://qa/tree/root-b"),
                epoch = 12L
            )
            val current = AtomicReference(rootA.authority())
            val coordinator = PackageMutationCoordinator(context)
            val blockEntries = AtomicInteger(0)
            val before = directoryState(context.filesDir)

            // This outer check models the old TOCTOU-prone caller check.
            requireAuthority(current.get(), rootA)
            val switchEntered = CompletableDeferred<Unit>()
            val releaseSwitch = CompletableDeferred<Unit>()
            val rootSwitch = async(Dispatchers.Default) {
                coordinator.withRootMutation {
                    switchEntered.complete(Unit)
                    kotlinx.coroutines.withTimeout(5_000L) { releaseSwitch.await() }
                    current.set(rootB.authority())
                }
            }
            kotlinx.coroutines.withTimeout(5_000L) { switchEntered.await() }

            val attemptStarted = CompletableDeferred<Unit>()
            val staleAttempt = async(
                Dispatchers.Default,
                start = CoroutineStart.UNDISPATCHED
            ) {
                attemptStarted.complete(Unit)
                runCatching {
                    coordinator.withPackageMutation(
                        rootA,
                        VIDEO_ID,
                        "DELETE_PERMANENT",
                        "queued-stale-root",
                        requireExactRoot = { requireAuthority(current.get(), rootA) }
                    ) {
                        blockEntries.incrementAndGet()
                        error("stale mutation block entered")
                    }
                }.exceptionOrNull()?.message
            }
            kotlinx.coroutines.withTimeout(5_000L) { attemptStarted.await() }
            releaseSwitch.complete(Unit)
            kotlinx.coroutines.withTimeout(5_000L) { rootSwitch.await() }
            assertEquals("LIBRARY_CHANGED", kotlinx.coroutines.withTimeout(5_000L) { staleAttempt.await() })

            val operations = listOf(
                "FLAT_ASSET_PUBLISH", "DELETE_PERMANENT", "DELETE_STEMS",
                "ENRICHMENT_INSTALL_V2", "SOURCE_JOB_AUTHORITY_V1", "SOURCE_MANIFEST_COMMIT"
            )
            repeat(257) { index ->
                assertEquals(
                    "attempt=$index",
                    "LIBRARY_CHANGED",
                    runCatching {
                        coordinator.withPackageMutation(
                            rootA,
                            VIDEO_ID,
                            operations[index % operations.size],
                            "stale-capacity-$index",
                            requireExactRoot = { requireAuthority(current.get(), rootA) }
                        ) {
                            blockEntries.incrementAndGet()
                            error("stale capacity mutation block entered")
                        }
                    }.exceptionOrNull()?.message
                )
            }

            val staleEpoch = rootB.copy(epoch = rootB.epoch + 1L)
            assertEquals(
                "LIBRARY_CHANGED",
                runCatching {
                    coordinator.withPackageMutation(
                        staleEpoch,
                        VIDEO_ID,
                        "DELETE_PERMANENT",
                        "stale-epoch",
                        requireExactRoot = { requireAuthority(current.get(), staleEpoch) }
                    ) {
                        blockEntries.incrementAndGet()
                        error("stale epoch mutation block entered")
                    }
                }.exceptionOrNull()?.message
            )
            assertEquals(0, blockEntries.get())
            assertEquals(before, directoryState(context.filesDir))
            assertFalse(File(context.filesDir, "operation-journals").exists())
            }
        } finally {
            context.destroy()
        }
    }

    @Test
    fun staleRootAndEpochDeletionLeaveBothRootsAndPrivateAuthorityUntouched() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedSafetyContext(targetContext, "stale-delete")
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val rootAUri = providerTreeUri("developer-stale-root-a")
        val rootBUri = providerTreeUri("developer-stale-root-b")
        grantProviderTrees(rootAUri, rootBUri)
        try {
            val rootA = File(providerRoot(), "developer-stale-root-a").apply(::requireDirectory)
            val rootB = File(providerRoot(), "developer-stale-root-b").apply(::requireDirectory)
            val packageA = File(rootA, VIDEO_ID).apply(::requireDirectory)
            listOf("original.opus", "part-1.flac", "remaining.wv", SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).forEachIndexed { index, name ->
                File(packageA, name).writeBytes("root-a-$index-$name".toByteArray())
            }
            val packageB = File(rootB, VIDEO_ID).apply(::requireDirectory)
            listOf("original.opus", "part-1.flac", "remaining.wv", SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).forEachIndexed { index, name ->
                File(packageB, name).writeBytes("root-b-$index-$name".toByteArray())
            }
            val staleRootA = RootHandle(
                libraryId = "00000000-0000-4000-8000-000000000021",
                treeUri = rootAUri,
                rootDocument = checkNotNull(DocumentFile.fromTreeUri(targetContext, rootAUri)),
                epoch = 21L
            )

            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            val candidateB = roots.validateCandidate(rootBUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidateB).getOrThrow()
            val handleB = checkNotNull(roots.captureHandle())
            dao.insertSong(
                SongEntity(
                    id = VIDEO_ID,
                    youtubeVideoId = VIDEO_ID,
                    playlistItemId = null,
                    canonicalUrl = "https://music.youtube.com/watch?v=$VIDEO_ID",
                    sourceTitle = "Root B retained projection",
                    sourceArtist = "StemWise",
                    displayTitle = null,
                    displayArtist = null,
                    durationMs = 10_000L,
                    artworkPath = null,
                    currentlyInSourcePlaylist = false,
                    highLevelState = "READY",
                    acquiredAtEpochMs = 1L,
                    createdAtEpochMs = 1L
                )
            )
            val sourceId = "youtube-video:$VIDEO_ID"
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            seedVerifiedBinding(bindings, mutations, handleB, sourceId, VIDEO_ID)
            val external = ExternalLibraryRepository(
                context,
                roots,
                dao,
                bindings,
                mutations,
                app.stemwise.playback.BackgroundWorkCoordinator()
            )
            val trash = ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao())
            val deletion = PermanentDeletionCoordinator(
                trash,
                external,
                SongWorkCoordinator()
            )

            val rootABefore = directoryState(rootA)
            val rootBBefore = directoryState(rootB)
            val privateBefore = privateState(context)
            val preferencesBefore = context.preferenceSnapshot()
            val songBefore = checkNotNull(dao.findSongById(VIDEO_ID))
            val identityBefore = checkNotNull(dao.libraryRootIdentity())
            val bindingsBefore = bindings.activePackageIdsBySource(handleB)
            val blockedBefore = bindings.blockedSourceIds(handleB)
            val admittedSideEffects = AtomicInteger(0)

            val staleHandles = listOf(
                staleRootA,
                handleB.copy(libraryId = "00000000-0000-4000-8000-000000000022"),
                handleB.copy(
                    treeUri = rootAUri,
                    rootDocument = staleRootA.rootDocument
                ),
                handleB.copy(epoch = handleB.epoch + 1L)
            )
            staleHandles.forEachIndexed { index, staleHandle ->
                val result = deletion.deletePermanently(staleHandle, VIDEO_ID) {
                    admittedSideEffects.incrementAndGet()
                }
                assertTrue("stale deletion $index unexpectedly succeeded", result.isFailure)
                assertEquals("LIBRARY_CHANGED", result.exceptionOrNull()?.message)
            }

            val restartedRoots = LibraryRootManager(context, mutations, dao)
            assertTrue(restartedRoots.initialize().ready)
            val restartedHandle = checkNotNull(restartedRoots.captureHandle())
            val restartedBindings = SourceBindingRegistry(
                context,
                restartedRoots,
                mutations,
                database.libraryDao()
            )
            ExternalPackageTrash(
                context,
                restartedRoots,
                mutations,
                restartedBindings,
                database.libraryDao()
            ).recoverInterruptedDeletes()

            assertEquals(0, admittedSideEffects.get())
            assertEquals(rootABefore, directoryState(rootA))
            assertEquals(rootBBefore, directoryState(rootB))
            assertEquals(privateBefore, privateState(context))
            assertEquals(preferencesBefore, context.preferenceSnapshot())
            assertEquals(songBefore, dao.findSongById(VIDEO_ID))
            val identityAfterRestart = checkNotNull(dao.libraryRootIdentity())
            assertEquals(identityBefore.copy(updatedAtEpochMs = identityAfterRestart.updatedAtEpochMs), identityAfterRestart)
            assertTrue(identityAfterRestart.updatedAtEpochMs >= identityBefore.updatedAtEpochMs)
            assertEquals(bindingsBefore, restartedBindings.activePackageIdsBySource(restartedHandle))
            assertEquals(blockedBefore, restartedBindings.blockedSourceIds(restartedHandle))
            assertTrue(
                File(context.filesDir, "operation-journals").listFiles().orEmpty().isEmpty()
            )
        } finally {
            database.close()
            revokeProviderTrees(rootAUri, rootBUri)
            resetProvider()
            context.destroy()
        }
    }

    @Test
    fun stemOnlyDeleteKeepsOriginalArtworkMetadataAndPackage() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (originalName in SelectedLibraryContract.originalFiles) {
            resetProvider()
            val context = IsolatedSafetyContext(targetContext, "stem-only-delete-$originalName")
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            grantProviderTree()
            try {
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, database.libraryDao())
                val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
                roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
                val handle = checkNotNull(roots.captureHandle())
                val packageDirectory = File(providerRoot(), VIDEO_ID).apply(::requireDirectory)
                (SelectedLibraryContract.allowedFiles - SelectedLibraryContract.originalFiles + originalName)
                    .forEachIndexed { index, name ->
                        File(packageDirectory, name).writeBytes("retained-$index-$name".toByteArray())
                    }
                val retainedNames = setOf(
                    originalName,
                    SelectedLibraryContract.ENRICHMENT,
                    SelectedLibraryContract.COVER
                )
                val retainedBytes = retainedNames.associateWith { name ->
                    File(packageDirectory, name).readBytes()
                }
                database.libraryDao().insertSong(
                    SongEntity(
                        id = VIDEO_ID,
                        youtubeVideoId = VIDEO_ID,
                        playlistItemId = null,
                        canonicalUrl = "https://music.youtube.com/watch?v=$VIDEO_ID",
                        sourceTitle = "Stem deletion",
                        sourceArtist = "StemWise",
                        displayTitle = null,
                        displayArtist = null,
                        durationMs = 10_000L,
                        artworkPath = null,
                        currentlyInSourcePlaylist = false,
                        highLevelState = "READY",
                        acquiredAtEpochMs = 1L,
                        createdAtEpochMs = 1L,
                        canonicalExternal = true,
                        packageDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                            GoldenQaDocumentsProvider.treeUri, "root/$VIDEO_ID"
                        ).toString()
                    )
                )
                val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())

                val expectedIdentityReadBytes = packageDirectory.listFiles()!!.sumOf(File::length)
                targetContext.contentResolver.call(Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                    GoldenQaDocumentsProvider.METHOD_RESET_METRICS, null, null)
                ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao())
                    .deleteStems(handle, VIDEO_ID)
                    .getOrThrow()

                val deletionMetrics = checkNotNull(targetContext.contentResolver.call(
                    Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                    GoldenQaDocumentsProvider.METHOD_METRICS, null, null))
                assertEquals("Fresh deletion must authenticate each child only once",
                    expectedIdentityReadBytes, deletionMetrics.getLong("openedDocumentLengthBytes"))
                assertTrue(packageDirectory.isDirectory)
                assertEquals(retainedNames, packageDirectory.listFiles().orEmpty().map { it.name }.toSet())
                retainedBytes.forEach { (name, expected) ->
                    assertTrue(expected.contentEquals(File(packageDirectory, name).readBytes()))
                }
                assertTrue(bindings.blockedSourceIds(handle).isEmpty())
                assertTrue(File(context.filesDir, "operation-journals").listFiles().orEmpty().isEmpty())
            } finally {
                database.close()
                revokeProviderTree()
                resetProvider()
                context.destroy()
            }
        }
    }

    @Test
    fun permanentDeleteRejectsProviderFalseSuccessAndUnknownChildrenBeforeReplay() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedSafetyContext(targetContext, "exact-delete-replay")
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        grantProviderTree()
        try {
            val root = providerRoot()
            val packageIds = listOf(VIDEO_ID, SECOND_VIDEO_ID)
            packageIds.forEach { packageId ->
                val directory = File(root, packageId).apply(::requireDirectory)
                listOf("original.opus", "part-1.flac", "remaining.wv", SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).forEachIndexed { index, name ->
                    File(directory, name).writeBytes("$packageId-$index-$name".toByteArray())
                }
            }

            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            packageIds.forEachIndexed { index, packageId ->
                dao.insertSong(
                    SongEntity(
                        id = packageId,
                        youtubeVideoId = packageId,
                        playlistItemId = null,
                        canonicalUrl = "https://music.youtube.com/watch?v=$packageId",
                        sourceTitle = "Exact deletion $index",
                        sourceArtist = "StemWise",
                        displayTitle = null,
                        displayArtist = null,
                        durationMs = 10_000L,
                        artworkPath = null,
                        currentlyInSourcePlaylist = false,
                        highLevelState = "READY",
                        acquiredAtEpochMs = 1L,
                        createdAtEpochMs = 1L,
                        canonicalExternal = true,
                        packageDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                            GoldenQaDocumentsProvider.treeUri,
                            "root/$packageId"
                        ).toString()
                    )
                )
                seedVerifiedBinding(bindings, mutations, handle, "youtube-video:$packageId", packageId)
            }
            val trash = ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao())

            setProviderFault(GoldenQaDocumentsProvider.FAULT_DIRECTORY_DELETE_FALSE_SUCCESS)
            val falseSuccess = trash.deletePermanently(handle, VIDEO_ID)
            assertTrue(falseSuccess.isFailure)
            assertEquals("EXTERNAL_COMMIT_FAILED", falseSuccess.exceptionOrNull()?.message)
            assertTrue(File(root, VIDEO_ID).isDirectory)
            assertTrue(trash.prepareExplicitReadd(handle, VIDEO_ID).isFailure)
            assertTrue("youtube-video:$VIDEO_ID" in bindings.blockedSourceIds(handle))
            setProviderFault(GoldenQaDocumentsProvider.FAULT_NONE)
            trash.prepareExplicitReadd(handle, VIDEO_ID).getOrThrow()
            assertFalse(File(root, VIDEO_ID).exists())
            assertFalse("youtube-video:$VIDEO_ID" in bindings.blockedSourceIds(handle))

            setProviderFault(GoldenQaDocumentsProvider.FAULT_DELETE_ADDS_UNKNOWN_CHILD)
            val unknownChild = trash.deletePermanently(handle, SECOND_VIDEO_ID)
            assertTrue(unknownChild.isFailure)
            assertTrue(File(root, "$SECOND_VIDEO_ID/unexpected-child.bin").isFile)
            setProviderFault(GoldenQaDocumentsProvider.FAULT_NONE)
            assertTrue(File(root, "$SECOND_VIDEO_ID/unexpected-child.bin").delete())
            trash.recoverInterruptedDeletes()
            assertFalse(File(root, SECOND_VIDEO_ID).exists())
            assertTrue(
                File(context.filesDir, "operation-journals").listFiles().orEmpty().isEmpty()
            )
            val restartedBindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val restartedTrash = ExternalPackageTrash(context, roots, mutations, restartedBindings, database.libraryDao())
            assertEquals(setOf("youtube-video:$SECOND_VIDEO_ID"), restartedBindings.blockedSourceIds(handle))
            val replacement = File(root, VIDEO_ID).apply(::requireDirectory)
            File(replacement, "original.opus").writeText("new-generation", Charsets.UTF_8)
            restartedTrash.recoverInterruptedDeletes()
            assertEquals("new-generation", File(replacement, "original.opus").readText(Charsets.UTF_8))
            seedVerifiedBinding(restartedBindings, mutations, handle, "youtube-video:$VIDEO_ID", VIDEO_ID)
            val external = ExternalLibraryRepository(context, roots, dao, restartedBindings, mutations,
                app.stemwise.playback.BackgroundWorkCoordinator())
            val deletion = PermanentDeletionCoordinator(restartedTrash, external, SongWorkCoordinator())
                .deletePermanently(handle, VIDEO_ID).getOrThrow()
            assertFalse(deletion.projectionRefreshPending)
            assertFalse(replacement.exists())
            assertEquals(null, dao.findSongById(VIDEO_ID))
        } finally {
            setProviderFault(GoldenQaDocumentsProvider.FAULT_NONE)
            database.close()
            revokeProviderTree()
            resetProvider()
            context.destroy()
        }
    }

    @Test
    fun completeSongSameHashColdReconciliationPreservesImportedSourceAndRemoteJob() =
        runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedSafetyContext(targetContext, "same-hash-source")
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val rootUri = providerTreeUri("developer-same-hash-source")
        grantProviderTrees(rootUri)
        try {
            val originalBytes = GoldenSongMediaFixture.originalOpus(targetContext, 1_200L)
            val packageDirectory = File(
                File(providerRoot(), "developer-same-hash-source").apply(::requireDirectory),
                VIDEO_ID
            ).apply(::requireDirectory)
            File(packageDirectory, SelectedLibraryContract.ORIGINAL).writeBytes(originalBytes)
            seedCompleteOpusMetadata(packageDirectory, VIDEO_ID, originalBytes)

            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            val candidate = roots.validateCandidate(rootUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            val originalUri = checkNotNull(
                roots.resolveAuthoritativeSongAsset(
                    handle,
                    VIDEO_ID,
                    SelectedLibraryContract.ORIGINAL
                )
            )
            val sourceId = "00000000-0000-4000-8000-0000000000aa"
            val sourceHash = sha256(originalBytes)
            val now = 20L
            dao.insertSong(
                SongEntity(
                    id = VIDEO_ID,
                    youtubeVideoId = VIDEO_ID,
                    playlistItemId = "playlist-item",
                    canonicalUrl = "https://music.youtube.com/watch?v=$VIDEO_ID",
                    sourceTitle = "Imported original",
                    sourceArtist = "StemWise",
                    displayTitle = null,
                    displayArtist = null,
                    durationMs = 1_200L,
                    artworkPath = null,
                    currentlyInSourcePlaylist = true,
                    highLevelState = "PREPARED",
                    acquiredAtEpochMs = now,
                    createdAtEpochMs = now
                )
            )
            dao.insertSource(
                SourceVersionEntity(
                    id = sourceId,
                    songId = VIDEO_ID,
                    localPath = originalUri.toString(),
                    sha256 = sourceHash,
                    codec = "opus",
                    container = "ogg",
                    sampleRateHz = 48_000,
                    channelCount = 2,

                    engineVersion = "fixture-import",
                    isCurrent = true,
                    createdAtEpochMs = now
                )
            )
            val job = ProcessingJobEntity(
                id = "00000000-0000-4000-8000-0000000000bb",
                songId = VIDEO_ID,
                sourceVersionId = sourceId,
                kind = app.stemwise.integration.SeparationRepository.KIND_SOURCES,
                provider = app.stemwise.integration.SeparationRepository.PROVIDER,
                modelId = "63",
                state = app.stemwise.integration.SeparationRepository.STATE_PROCESSING,
                remoteJobId = "fixture-accepted-remote-id",

                attempts = 1,
                nextAttemptAtEpochMs = 30L,
                lastErrorCode = null,
                lastErrorMessage = null,
                updatedAtEpochMs = now,
                createdAtEpochMs = now,
                libraryId = handle.libraryId
            )
            dao.upsertJob(job)
            val rootBefore = directoryState(File(providerRoot(), "developer-same-hash-source"))

            suspend fun reconcileWith(activeRoots: LibraryRootManager, fullScan: Boolean = false) {
                val activeHandle = checkNotNull(activeRoots.captureHandle())
                val bindings = SourceBindingRegistry(
                    context,
                    activeRoots,
                    mutations,
                    dao
                )
                val external = ExternalLibraryRepository(
                    context,
                    activeRoots,
                    dao,
                    bindings,
                    mutations,
                    app.stemwise.playback.BackgroundWorkCoordinator()
                )
                if (fullScan) {
                    external.reconcile()
                } else {
                    external.reconcilePackage(activeHandle, VIDEO_ID)
                }
                val playable = external.songs.value.single { it.id == VIDEO_ID }
                assertEquals(originalUri.toString(), playable.sourcePath)
                assertFalse(playable.hasStems)
            }

            reconcileWith(roots)
            reconcileWith(roots)
            val restartedRoots = LibraryRootManager(context, mutations, dao)
            assertTrue(restartedRoots.initialize().ready)
            reconcileWith(restartedRoots)

            val sources = dao.sourcesForSong(VIDEO_ID)
            assertEquals(1, sources.size)
            assertEquals(sourceId, sources.single().id)
            assertEquals(sourceHash, sources.single().sha256)
            assertTrue(sources.single().isCurrent)
            assertEquals(job, dao.findJobById(job.id))
            assertEquals(rootBefore, directoryState(File(providerRoot(), "developer-same-hash-source")))
            assertEquals(sourceHash, sha256(File(packageDirectory, SelectedLibraryContract.ORIGINAL).readBytes()))

            // Process death can leave one declared lane published before its source manifest. Recovery must
            // retain the same original/remote job instead of treating it as a new acquisition.
            val partialStem = File(packageDirectory, "part-1.flac").apply {
                writeBytes("incomplete-stem-publication".toByteArray())
            }
            reconcileWith(restartedRoots)
            reconcileWith(restartedRoots, fullScan = true)
            assertEquals(sourceId, dao.currentSource(VIDEO_ID)?.id)
            assertEquals(job, dao.findJobById(job.id))
            assertTrue(checkNotNull(dao.findSongById(VIDEO_ID)).canonicalExternal)
            check(partialStem.delete())
            reconcileWith(restartedRoots)
            assertTrue(checkNotNull(dao.findSongById(VIDEO_ID)).canonicalExternal)
            assertEquals(sourceId, dao.currentSource(VIDEO_ID)?.id)
            assertEquals(job, dao.findJobById(job.id))
        } finally {
            database.close()
            revokeProviderTrees(rootUri)
            resetProvider()
            context.destroy()
        }
    }

    @Test
    fun roomRootIdentityWinsOverStalePreferencesAndReconnectsItsExactTuple() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedSafetyContext(targetContext, "root-identity")
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            val unrelated = File(providerRoot(), "unrelated-owner-file").apply {
                writeText("retain owner bytes", Charsets.UTF_8)
            }
            val committed = roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            assertEquals("retain owner bytes", unrelated.readText(Charsets.UTF_8))
            assertTrue(committed.ready)
            assertEquals(
                "DEVELOPMENT_DUPLICATE_ACTIVATION",
                roots.commitValidatedCandidateForDevelopment(candidate)
                    .exceptionOrNull()?.message
            )
            val exactReconnect = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                .getOrThrow()
            assertEquals(committed.libraryId, exactReconnect.libraryId)
            val differentUri = GoldenQaDocumentsProvider.treeUri.buildUpon()
                .appendQueryParameter("alias", "different-uri-is-a-new-root")
                .build()
            val differentCandidate = roots.validateCandidate(differentUri).getOrThrow()
            assertEquals(committed.libraryId, differentCandidate.libraryId)

            val dao = database.libraryDao()
            val exactIdentity = requireNotNull(dao.libraryRootIdentity())
            val newerIdentity = exactIdentity.copy(
                libraryId = "00000000-0000-4000-8000-000000000098",
                treeUri = "content://qa/tree/newer",
                epoch = exactIdentity.epoch + 1L,
                state = "VERIFIED"
            )
            dao.upsertLibraryRootIdentity(newerIdentity)
            assertEquals(
                0,
                dao.markExactLibraryRootUnavailable(
                    exactIdentity.libraryId,
                    exactIdentity.treeUri,
                    exactIdentity.epoch,
                    System.currentTimeMillis()
                )
            )
            assertEquals(newerIdentity, dao.libraryRootIdentity())
            val stalePreferences = context.getSharedPreferences("stemwise_library_root", Context.MODE_PRIVATE)
            check(stalePreferences.edit().putString("library_id", committed.libraryId)
                .putString("tree_uri", committed.treeUri.toString()).putLong("root_epoch", committed.epoch).commit())
            val foreignIdentity = exactIdentity.copy(
                libraryId = "00000000-0000-4000-8000-000000000099",
                treeUri = "content://qa/tree/foreign", epoch = exactIdentity.epoch + 1L, state = "VERIFIED")
            dao.upsertLibraryRootIdentity(foreignIdentity)
            val unavailableForeign = LibraryRootManager(context, mutations, dao).initialize()
            assertFalse("Stale preferences overrode authoritative Room selection", unavailableForeign.ready)
            val retainedForeign = checkNotNull(dao.libraryRootIdentity())
            assertEquals(foreignIdentity.libraryId, retainedForeign.libraryId)
            assertEquals(foreignIdentity.treeUri, retainedForeign.treeUri)
            assertEquals(foreignIdentity.epoch, retainedForeign.epoch)
            assertEquals("UNAVAILABLE", retainedForeign.state)
            // The remaining cases explicitly select this test's known exact Room authority.
            dao.upsertLibraryRootIdentity(exactIdentity.copy(state = "RESTORING"))
            val repaired = LibraryRootManager(context, mutations, dao).initialize()
            assertTrue(repaired.ready)
            assertEquals(committed.libraryId, repaired.libraryId)
            assertEquals(committed.treeUri, repaired.treeUri)
            assertEquals(committed.epoch, repaired.epoch)
            assertEquals("VERIFIED", dao.libraryRootIdentity()?.state)

            listOf("RESTORING", "UNAVAILABLE").forEach { interruptedState ->
                val identity = requireNotNull(dao.libraryRootIdentity())
                dao.upsertLibraryRootIdentity(identity.copy(state = interruptedState))
                val restarted = LibraryRootManager(context, mutations, dao).initialize()
                assertTrue("state=$interruptedState", restarted.ready)
                assertEquals("VERIFIED", dao.libraryRootIdentity()?.state)
            }

            revokeProviderTree()
            val unavailable = LibraryRootManager(context, mutations, dao).initialize()
            assertFalse(unavailable.ready)
            assertEquals("ROOT_PERMISSION_LOST", unavailable.errorCode)
            assertEquals("UNAVAILABLE", dao.libraryRootIdentity()?.state)
        } finally {
            database.close()
            revokeProviderTree()
            resetProvider()
            context.destroy()
        }
    }

    @Test
    fun currentRootAuthoritySurvivesRootAToBToAAndPrivateStateLoss() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedSafetyContext(targetContext, "portable-a-b-a")
        val rootAUri = providerTreeUri("portable-root-a")
        val rootBUri = providerTreeUri("portable-root-b")
        grantProviderTrees(rootAUri, rootBUri)
        var database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val providerRoot = providerRoot()
            val rootA = File(providerRoot, "portable-root-a").apply(::requireDirectory)
            File(providerRoot, "portable-root-b").apply(::requireDirectory)
            var mutations = PackageMutationCoordinator(context)
            var roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidateA = roots.validateCandidate(rootAUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidateA).getOrThrow()
            val handleA = checkNotNull(roots.captureHandle())
            val currentLibraryId = handleA.libraryId
            assertEquals(currentLibraryId,
                parsePortableLibraryId(File(rootA, SelectedLibraryContract.ROOT_MANIFEST).readBytes()))
            assertEquals(currentLibraryId,
                parsePortableLibraryId(File(rootA, SelectedLibraryContract.ROOT_MANIFEST_BACKUP).readBytes()))
            val survivingManifest =
                File(rootA, SelectedLibraryContract.ROOT_MANIFEST_BACKUP).readBytes()
            File(rootA, SelectedLibraryContract.ROOT_MANIFEST_TEMP).writeBytes(survivingManifest)
            File(rootA, SelectedLibraryContract.ROOT_MANIFEST).writeBytes(
                survivingManifest.copyOf(survivingManifest.size / 2)
            )
            roots = LibraryRootManager(context, mutations, database.libraryDao())
            assertTrue(roots.initialize().ready)
            assertEquals(
                currentLibraryId,
                parsePortableLibraryId(
                    File(rootA, SelectedLibraryContract.ROOT_MANIFEST).readBytes()
                )
            )
            val repairedHandleA = checkNotNull(roots.captureHandle())
            assertEquals(handleA.libraryId, repairedHandleA.libraryId)
            val packageA = File(rootA, VIDEO_ID).apply(::requireDirectory)
            listOf("original.opus", "part-1.flac", "remaining.wv", SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).forEachIndexed { index, name ->
                File(packageA, name).writeBytes("portable-a-$index-$name".toByteArray())
            }
            database.libraryDao().insertSong(
                SongEntity(
                    id = VIDEO_ID,
                    youtubeVideoId = VIDEO_ID,
                    playlistItemId = null,
                    canonicalUrl = "https://music.youtube.com/watch?v=$VIDEO_ID",
                    sourceTitle = "Portable authority",
                    sourceArtist = "StemWise",
                    displayTitle = null,
                    displayArtist = null,
                    durationMs = 10_000L,
                    artworkPath = null,
                    currentlyInSourcePlaylist = false,
                    highLevelState = "READY",
                    acquiredAtEpochMs = 1L,
                    createdAtEpochMs = 1L
                )
            )
            val sourceId = "youtube-video:$VIDEO_ID"
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            seedVerifiedBinding(bindings, mutations, handleA, sourceId, VIDEO_ID)
            mutations.withPackageMutation(
                handleA,
                VIDEO_ID,
                "PORTABLE_TOMBSTONE_TEST",
                "portable-delete",
                requireExactRoot = { roots.requireSameLibrary(handleA) }
            ) { journal ->
                bindings.markDeletedUnderPackageLock(
                    handleA,
                    sourceId,
                    VIDEO_ID,
                    "portable-delete",
                    journal
                )
                journal.complete(JSONObject().put("sourceId", sourceId))
            }
            assertTrue(File(rootA, SelectedLibraryContract.DELETION_TOMBSTONES).isFile)

            roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidateB = roots.validateCandidate(rootBUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidateB).getOrThrow()
            val libraryB = checkNotNull(roots.captureHandle()).libraryId
            assertNotEquals(handleA.libraryId, libraryB)

            database.close()
            context.eraseAllPrivateState()
            assertTrue(File(rootA, SelectedLibraryContract.ROOT_MANIFEST).delete())
            database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            mutations = PackageMutationCoordinator(context)
            roots = LibraryRootManager(context, mutations, database.libraryDao())
            val returnedCandidateA = roots.validateCandidate(rootAUri).getOrThrow()
            assertEquals(handleA.libraryId, returnedCandidateA.libraryId)
            roots.commitValidatedCandidateForDevelopment(returnedCandidateA).getOrThrow()
            val returnedHandleA = checkNotNull(roots.captureHandle())
            val recoveredBindings = SourceBindingRegistry(
                context,
                roots,
                mutations,
                database.libraryDao()
            )
            assertEquals(
                setOf(sourceId),
                recoveredBindings.blockedSourceIds(returnedHandleA)
            )
            ExternalPackageTrash(
                context,
                roots,
                mutations,
                recoveredBindings,
                database.libraryDao()
            ).recoverInterruptedDeletes()
            assertFalse(packageA.exists())
        } finally {
            database.close()
            revokeProviderTrees(rootAUri, rootBUri)
            resetProvider()
            context.destroy()
        }
    }

    private fun requireDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create ${directory.name}" }
    }

    private fun requireAuthority(current: RootHandle, expected: RootHandle) =
        requireAuthority(current.authority(), expected)

    private fun requireAuthority(current: RootAuthority, expected: RootHandle) {
        requireRootAuthority(
            currentReady = true,
            currentLibraryId = current.libraryId,
            currentTreeUri = current.treeUri,
            currentEpoch = current.epoch,
            expectedLibraryId = expected.libraryId,
            expectedTreeUri = expected.treeUri.toString(),
            expectedEpoch = expected.epoch
        )
    }

    private fun directoryState(root: File): List<String> {
        if (!root.exists()) return listOf("<missing>")
        return root.walkTopDown()
            .filter { file -> file != root }
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (file.isDirectory) {
                    "D:$relative"
                } else {
                    "F:$relative:${file.length()}:${sha256(file.readBytes())}"
                }
            }
            .toList()
            .sorted()
    }

    private fun privateState(context: Context): List<String> = listOf(
        "files" to context.filesDir,
        "cache" to context.cacheDir,
        "no-backup" to context.noBackupFilesDir
    ).flatMap { (label, directory) ->
        directoryState(directory).map { entry -> "$label:$entry" }
    }.sorted()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun grantProviderTree() {
        grantProviderTrees(GoldenQaDocumentsProvider.treeUri)
    }

    private fun grantProviderTrees(vararg trees: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        trees.forEach { tree ->
            targetContext.grantUriPermission(targetContext.packageName, tree, flags)
        }
    }

    private fun revokeProviderTree() {
        revokeProviderTrees(GoldenQaDocumentsProvider.treeUri)
    }

    private fun revokeProviderTrees(vararg trees: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        trees.forEach { tree ->
            targetContext.revokeUriPermission(targetContext.packageName, tree, flags)
        }
    }

    private fun resetProvider() {
        checkNotNull(
            targetContext.contentResolver.call(
                Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                GoldenQaDocumentsProvider.METHOD_RESET,
                null,
                null
            )
        )
    }

    private fun setProviderFault(fault: String) {
        checkNotNull(
            targetContext.contentResolver.call(
                Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                GoldenQaDocumentsProvider.METHOD_SET_FAULT,
                fault,
                null
            )
        )
    }

    private fun providerRoot(): File = File(
        targetContext.noBackupFilesDir,
        "golden-enrichment-document-provider"
    )

    private fun providerTreeUri(name: String): Uri = DocumentsContract.buildTreeDocumentUri(
        GoldenQaDocumentsProvider.AUTHORITY,
        "root/$name"
    )

    private class IsolatedSafetyContext(base: Context, suffix: String) : ContextWrapper(base) {
        private val preferencePrefix = "backend-safety-$suffix-"
        private val preferenceNames = linkedSetOf<String>()
        private val root = File(base.cacheDir, "backend-safety-$suffix").canonicalFile.also {
            check(!it.exists() || it.deleteRecursively()) { "Unable to reset isolated state" }
        }
        private val isolatedFiles = File(root, "files").apply(::requireDirectoryStatic)
        private val isolatedCache = File(root, "cache").apply(::requireDirectoryStatic)
        private val isolatedNoBackup = File(root, "no-backup").apply(::requireDirectoryStatic)

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
        override fun getCacheDir(): File = isolatedCache
        override fun getNoBackupFilesDir(): File = isolatedNoBackup
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$preferencePrefix$name"
            preferenceNames += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }

        fun preferenceSnapshot(): Map<String, Map<String, String>> = preferenceNames
            .sorted()
            .associateWith { name ->
                baseContext.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    .toSortedMap()
                    .mapValues { (_, value) -> value.toString() }
            }

        fun eraseAllPrivateState() {
            preferenceNames.forEach(baseContext::deleteSharedPreferences)
            preferenceNames.clear()
            listOf(isolatedFiles, isolatedCache, isolatedNoBackup).forEach { directory ->
                directory.listFiles().orEmpty().forEach { child ->
                    check(child.deleteRecursively()) { "Unable to erase ${child.name}" }
                }
            }
        }

        fun destroy() {
            preferenceNames.forEach { name ->
                check(
                    baseContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                        .edit().clear().commit()
                ) {
                    "Unable to clear isolated preferences"
                }
            }
            check(!root.exists() || root.deleteRecursively()) { "Unable to clear isolated state" }
        }

        private companion object {
            fun requireDirectoryStatic(directory: File) {
                check(directory.isDirectory || directory.mkdirs()) {
                    "Unable to create ${directory.name}"
                }
            }
        }
    }

    private companion object {
        const val VIDEO_ID = "AbCdEf_12-3"
        const val SECOND_VIDEO_ID = "ZyXwVu_98-7"
    }
}
