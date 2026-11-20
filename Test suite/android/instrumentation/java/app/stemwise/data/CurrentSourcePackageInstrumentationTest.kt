package app.stemwise.data

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.integration.YoutubeDlEngine
import app.stemwise.integration.MergerOracleFixture
import app.stemwise.integration.MvsepAccountAccess
import app.stemwise.integration.MvsepAlgorithm
import app.stemwise.integration.MvsepCatalogueMetadata
import app.stemwise.integration.MvsepConnectionState
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.integration.MvsepCredential
import app.stemwise.integration.MvsepOptionField
import app.stemwise.integration.MvsepRemoteFile
import app.stemwise.integration.MvsepRemoteStatus
import app.stemwise.integration.MvsepServiceClient
import app.stemwise.integration.MvsepSourceOperation
import app.stemwise.integration.SeparationRepository
import app.stemwise.integration.SeparationWorkResult
import app.stemwise.integration.parsePortableSourceJobAuthority
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.model.StemSourceCatalogue
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic format/ownership qualification through the real publisher; no separation-quality claim. */
class CurrentSourcePackageInstrumentationTest {
    @Test
    fun eightOfflineAcceptedJobsPreserveReceivedFlacBytesThroughRepositoryRestartsAndCompleteRemaining() = runBlocking {
        withTimeout(120_000L) {
            StandardInstrumentationTargetGuard.requireCurrentTarget()
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val provider = Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}")
            checkNotNull(target.contentResolver.call(provider, GoldenQaDocumentsProvider.METHOD_RESET, null, null))
            val tree = GoldenQaDocumentsProvider.treeUri
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            target.grantUriPermission(target.packageName, tree, flags)
            val context = FixtureContext(target)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val governor = BackgroundWorkCoordinator()
                val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
                val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
                val oracle = MergerOracleFixture()
                val selected = listOf("lead-vocals", "bass", "kick", "acoustic-guitar", "violin", "flute", "organ", "synth")
                assertEquals(selected, oracle.roles)
                val original = oracle.original(target, context.cacheDir)
                val originalBytes = original.readBytes()
                val originalHash = sha(original)
                val targets = oracle.targets(target, context.cacheDir)
                val received = targets.map(File::readBytes)
                targets.forEach { file ->
                    val facts = inspectPreparedSourceAudio(file)
                    assertEquals(48_000, facts.sampleRate)
                    assertEquals(2, facts.channels)
                    assertEquals(16, facts.bitsPerSample)
                    assertEquals(oracle.frameCount.toLong(), facts.audibleFrames)
                }
                val videoId = "REPOFLAC001"
                publisher.publishOriginal(root, videoId, original.copyTo(File(context.cacheDir, "publish-original.flac")),
                    originalHash).getOrThrow()
                val folder = File(File(target.noBackupFilesDir, "golden-enrichment-document-provider"), videoId)
                seedCompleteOpusMetadata(folder, videoId, originalBytes, SelectedLibraryContract.ORIGINAL_FLAC)
                external.reconcilePackage(root, videoId)
                val network = object : AppNetworkAccess {
                    override val state = MutableStateFlow(AppNetworkState(true, true, false, activeNetworkWifi = true))
                    override fun currentState() = state.value
                }
                val fixtureCredential = MvsepCredential("offline-fixture-token", 1L)
                val account = object : MvsepAccountAccess {
                    override val state = MutableStateFlow(MvsepConnectionState(MvsepConnectionStatus.READY))
                    override suspend fun validate(expectedCredential: MvsepCredential?): Result<Unit> {
                        assertTrue(expectedCredential === fixtureCredential)
                        return Result.success(Unit)
                    }
                    override fun credential() = fixtureCredential
                    override fun isCurrent(credential: MvsepCredential) = credential === fixtureCredential
                    override fun apiBase() = "https://example.invalid"
                }
                fun advertised(render: Int, roles: Set<String>, options: Map<String, String> = emptyMap()) =
                    MvsepAlgorithm(render, "Offline canonical FLAC", roles.toList(), 1.0, 0,
                        MvsepCatalogueMetadata(roles, options.map { (key, value) ->
                            MvsepOptionField(key, key, setOf(value))
                        }, emptyList()))
                val algorithms = listOf(
                    advertised(49, setOf("vocals-lead", "vocals-back", "instrum-only", "back-instrum"),
                        mapOf("add_opt1" to "6", "add_opt2" to "0")),
                    advertised(63, setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum")),
                    advertised(37, setOf("drums", "kick", "snare", "toms", "ride", "hh", "crash", "residual"),
                        mapOf("add_opt1" to "7", "add_opt2" to "0")),
                    advertised(66, setOf("acoustic-guitar", "other"), mapOf("add_opt2" to "0")),
                    advertised(65, setOf("violin", "other")),
                    advertised(67, setOf("flute", "other"), mapOf("add_opt1" to "1", "add_opt2" to "0")),
                    advertised(58, setOf("organ", "other"), mapOf("add_opt1" to "3")),
                    advertised(88, setOf("synth", "other"), mapOf("add_opt1" to "0")))
                val accepted = mutableListOf<Pair<String, MvsepSourceOperation>>()
                val paused = mutableSetOf<Int>()
                val downloaded = mutableListOf<String>()
                var forbidRemote = false
                val client = object : MvsepServiceClient {
                    override suspend fun sourceAlgorithms() = algorithms
                    override suspend fun disablePremium(token: String) { assertEquals(fixtureCredential.token, token) }
                    override suspend fun submitConfigured(token: String, source: File, operation: MvsepSourceOperation,
                        isAuthorized: () -> Boolean, onSubmissionEntered: suspend () -> Unit,
                        onAcknowledged: suspend (String) -> Unit, onRejected: suspend (String) -> Unit): String {
                        assertTrue(isAuthorized())
                        assertEquals(fixtureCredential.token, token)
                        assertTrue(originalBytes.contentEquals(source.readBytes()))
                        assertEquals(selected[accepted.size], operation.outputBindings.keys.single())
                        onSubmissionEntered()
                        val remoteId = "offline-accepted-${accepted.size + 1}"
                        accepted += remoteId to operation
                        onAcknowledged(remoteId)
                        return remoteId
                    }
                    override suspend fun status(remoteJobId: String): MvsepRemoteStatus {
                        check(!forbidRemote) { "Receipt-complete recovery contacted MVSEP" }
                        val index = accepted.indexOfFirst { it.first == remoteJobId }
                        check(index >= 0) { "Polling an unaccepted job" }
                        if (index in listOf(2, 6) && paused.add(index)) return MvsepRemoteStatus("processing", null, emptyList())
                        val files = accepted[index].second.expectedRoles.map { role ->
                            MvsepRemoteFile(role, "https://example.invalid/$index/$role.flac", null, null)
                        }.reversed()
                        return MvsepRemoteStatus("done", null, files)
                    }
                    override suspend fun download(remote: MvsepRemoteFile, target: File) {
                        val index = accepted.indices.single { candidate ->
                            remote.label == accepted[candidate].second.outputBindings.values.single() &&
                                remote.url == "https://example.invalid/$candidate/${remote.label}.flac"
                        }
                        check(remote.url !in downloaded) { "Downloaded an accepted asset twice" }
                        downloaded += remote.url
                        target.writeBytes(received[index])
                    }
                }
                var sourceCopies = 0
                fun newRepository() = SeparationRepository(context, dao, account, roots, mutations, SongWorkCoordinator(),
                    external, publisher, governor, YoutubeDlEngine(target, network, roots), network, client).also {
                    it.sourceWorkspaceCopiedForTest = { sourceCopies++ }
                }
                var repository = newRepository()
                val handoff = repository.queueSources(root, videoId, selected).getOrThrow()
                val jobId = checkNotNull(handoff.jobId)
                val authorityFile = File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY)
                repeat(8) { index ->
                    val copiesBefore = sourceCopies
                    if (index == 7) repository.beforeSourceAssemblyForTest = {
                        throw kotlinx.coroutines.CancellationException("Process stopped after the last durable receipt")
                    }
                    var result = if (index == 7) {
                        try {
                            repository.process(jobId, root)
                            error("Last-receipt interruption was not exercised")
                        } catch (expected: kotlinx.coroutines.CancellationException) {
                            val retained = parsePortableSourceJobAuthority(authorityFile.readBytes())
                            assertEquals("DOWNLOADING", retained.state)
                            assertEquals(selected.toSet(), retained.assets.keys)
                        }
                        forbidRemote = true
                        repository = newRepository()
                        repository.process(jobId, root)
                    } else repository.process(jobId, root)
                    assertEquals("One verified Original per worker pass", copiesBefore + if (index == 7) 2 else 1, sourceCopies)
                    if (index in listOf(2, 6)) {
                        assertEquals(SeparationWorkResult.PENDING, result)
                        val durable = authorityFile.readBytes()
                        val retained = parsePortableSourceJobAuthority(durable)
                        assertEquals("PROCESSING", retained.state)
                        assertEquals(accepted.map { it.first }, retained.remoteJobIds.filterNotNull())
                        assertEquals(selected.take(index).toSet(), retained.assets.keys)
                        retained.assets.values.forEach { lane ->
                            val evidence = checkNotNull(lane.timingEvidence)
                            assertEquals(SourceJobTimingStatus.JOB_TIMING_UNVERIFIED, evidence.jobStatus)
                            assertEquals(lane.sha256, evidence.preparedSha256)
                            assertEquals(lane.sha256, checkNotNull(evidence.raw).sha256)
                        }
                        val retainedParts = retained.assets.values.associate { it.fileName to File(folder, it.fileName).readBytes() }
                        repository = newRepository()
                        assertEquals(jobId, repository.queueSources(root, videoId, selected).getOrThrow().jobId)
                        assertTrue(durable.contentEquals(authorityFile.readBytes()))
                        assertEquals(index + 1, accepted.size)
                        retainedParts.forEach { (name, bytes) -> assertTrue(bytes.contentEquals(File(folder, name).readBytes())) }
                        val resumedCopiesBefore = sourceCopies
                        result = repository.process(jobId, root)
                        assertEquals("Download, assembly and publication must share Original", resumedCopiesBefore + 1, sourceCopies)
                    }
                    assertEquals(if (index == 7) SeparationWorkResult.COMPLETE else SeparationWorkResult.NEXT_OPERATION, result)
                    assertTrue("Worker retained disposable Original or part scratch",
                        File(context.noBackupFilesDir, "preparation-active").listFiles().orEmpty().isEmpty())
                }
                assertEquals(listOf(49, 63, 37, 66, 65, 67, 58, 88), accepted.map { it.second.renderId })
                assertEquals(8, downloaded.size)
                assertEquals(8, downloaded.toSet().size)
                val authority = parsePortableSourceJobAuthority(authorityFile.readBytes())
                assertEquals("COMPLETE", authority.state)
                assertEquals(accepted.map { it.first }, authority.remoteJobIds)
                val manifest = parseCustomSourceManifest(File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).readBytes(),
                    videoId, originalHash)
                assertEquals(authority.planSha256, manifest.planSha256)
                assertEquals(selected + "remaining", manifest.lanes.map { it.sourceId })
                manifest.lanes.forEachIndexed { index, lane ->
                    val committed = File(folder, lane.fileName)
                    assertEquals(lane, authority.assets.getValue(lane.sourceId))
                    assertEquals(lane.sha256, sha(committed))
                    assertEquals(lane.byteLength, committed.length())
                    if (index < 8) {
                        val evidence = checkNotNull(lane.timingEvidence)
                        assertEquals(SourceJobTimingStatus.JOB_TIMING_UNVERIFIED, evidence.jobStatus)
                        assertEquals(SourceRouteTimingStatus.ROUTE_UNQUALIFIED, evidence.routeStatus)
                        assertEquals(originalHash, evidence.sourceSha256)
                        assertEquals(authority.planSha256, evidence.planSha256)
                        assertEquals(authority.generation, evidence.generation)
                        assertEquals(accepted[index].first, evidence.remoteJobId)
                        assertEquals(accepted[index].second.configurationId, evidence.configurationId)
                        assertEquals(accepted[index].second.options, evidence.options)
                        assertEquals(accepted[index].second.outputBindings.getValue(lane.sourceId), evidence.providerRole)
                        assertEquals(sha(targets[index]), checkNotNull(evidence.raw).sha256)
                        assertEquals(lane.sha256, evidence.preparedSha256)
                        assertEquals("received/committed ${lane.sourceId}", sha(targets[index]), sha(committed))
                        assertTrue(received[index].contentEquals(committed.readBytes()))
                    }
                    MergerOracleFixture.assertPcm("repository committed ${lane.sourceId}",
                        if (index < 8) oracle.targetPcm(index) else oracle.remainingPcm(8),
                        MergerOracleFixture.decode(target, committed, oracle.frameCount, directFloat = index == 8))
                }
                assertTrue(external.verifyPlaybackSong(root, videoId).hasStems)
                assertTrue(originalBytes.contentEquals(File(folder, SelectedLibraryContract.ORIGINAL_FLAC).readBytes()))
                assertTrue(!repository.queueSources(root, videoId, selected).getOrThrow().requiresWorker)
                assertEquals(8, accepted.size)
                assertEquals(8, downloaded.size)
            } finally {
                database.close()
                context.destroy()
                target.revokeUriPermission(tree, flags)
                checkNotNull(target.contentResolver.call(provider, GoldenQaDocumentsProvider.METHOD_RESET, null, null))
            }
        }
    }

    @Test
    fun oneThroughEightTargetsAndEveryCatalogueRolePublishWithActualRemaining() = verifyPackages(allRoles=true)

    @Test
    fun genericRemainingProviderMimeStillRequiresAndPublishesTheActualVerifiedWavPack() = verifyPackages(allRoles=false)

    @Test
    fun replacementCleanupRequiresActualAbsenceBeforeAThirdGenerationCanReuseItsBank() = runBlocking {
        withTimeout(120_000L) {
            StandardInstrumentationTargetGuard.requireCurrentTarget()
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val provider = Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}")
            fun fault(value: String) {
                checkNotNull(target.contentResolver.call(provider, GoldenQaDocumentsProvider.METHOD_SET_FAULT, value, null))
            }
            checkNotNull(target.contentResolver.call(provider, GoldenQaDocumentsProvider.METHOD_RESET, null, null))
            val tree = GoldenQaDocumentsProvider.treeUri
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            target.grantUriPermission(target.packageName, tree, flags)
            val context = FixtureContext(target)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val governor = BackgroundWorkCoordinator()
                val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
                val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
                val fixture = CurrentSourcePublicationFixture.load(context, "CLEANUP0001")
                val original = File(context.cacheDir, "original.opus").apply { writeBytes(fixture.original) }
                publisher.publishOriginal(root, fixture.manifest.videoId, original, fixture.manifest.sourceSha256).getOrThrow()
                val folder = File(File(target.noBackupFilesDir, "golden-enrichment-document-provider"), fixture.manifest.videoId)
                seedCompleteOpusMetadata(folder, fixture.manifest.videoId, fixture.original)
                external.reconcilePackage(root, fixture.manifest.videoId)
                suspend fun publish(generation: Int, prepare: Boolean = true): Result<Unit> {
                    val owner = "cleanup-generation-$generation"
                    val lanes = fixture.manifest.lanes.map { old ->
                        old.copy(fileName = if (generation % 2 == 0) "replacement-${old.fileName}" else old.fileName).also { next ->
                            if (prepare) {
                                val part = File(context.cacheDir, next.fileName).apply { writeBytes(fixture.parts.getValue(old.fileName)) }
                                publisher.publishSourcePart(root, fixture.manifest.videoId, next.fileName, part,
                                    next.sha256, owner, fixture.manifest.sourceSha256, next.audibleFrames).getOrThrow()
                            }
                        }
                    }
                    val manifest = fixture.manifest.copy(planSha256 = generation.toString().repeat(64), lanes = lanes)
                    return publisher.publishCustomSources(root, fixture.manifest.videoId, manifest, owner)
                }
                publish(1).getOrThrow()
                publisher.sourceLaneDeletedForTest = { throw java.io.IOException("Interrupted after retiring the first old lane") }
                assertTrue(publish(2).isFailure)
                publisher.sourceLaneDeletedForTest = null
                val canonical = File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST)
                val committed = canonical.readBytes()
                assertEquals(fixture.manifest.lanes.size - 1, fixture.manifest.lanes.count { File(folder, it.fileName).isFile })
                val replacement = File(folder, "replacement-${fixture.manifest.lanes.first().fileName}")
                val healthy = replacement.readBytes()
                replacement.writeBytes(healthy.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                assertTrue("Retry validation accepted damaged committed bytes", publish(2, prepare = false).isFailure)
                assertTrue("Cleanup retry restored a partially deleted old bank", committed.contentEquals(canonical.readBytes()))
                replacement.writeBytes(healthy)
                publisher.sourceManifestWrittenForTest = {
                    throw kotlinx.coroutines.CancellationException("A cleanup retry must not rewrite the committed manifest")
                }
                fault(GoldenQaDocumentsProvider.FAULT_FILE_DELETE_FALSE_SUCCESS)
                val failed = publish(2, prepare = false)
                assertTrue("A no-op deletion must retain recovery authority", failed.isFailure)
                assertEquals("EXTERNAL_COMMIT_FAILED", failed.exceptionOrNull()?.message)
                publisher.sourceManifestWrittenForTest = null
                assertTrue(File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP).isFile)
                assertEquals(fixture.manifest.lanes.size - 1, fixture.manifest.lanes.count { File(folder, it.fileName).isFile })
                assertTrue(committed.contentEquals(canonical.readBytes()))
                fault(GoldenQaDocumentsProvider.FAULT_NONE)
                publish(2, prepare = false).getOrThrow()
                assertTrue(fixture.manifest.lanes.none { File(folder, it.fileName).exists() })
                publish(3).getOrThrow()
                assertTrue(fixture.manifest.lanes.all { File(folder, it.fileName).isFile })
                assertTrue(fixture.manifest.lanes.none { File(folder, "replacement-${it.fileName}").exists() })
                assertTrue(external.songs.value.single { it.id == fixture.manifest.videoId }.hasStems)
            } finally {
                fault(GoldenQaDocumentsProvider.FAULT_NONE)
                database.close()
                context.destroy()
                target.revokeUriPermission(tree, flags)
                checkNotNull(target.contentResolver.call(provider, GoldenQaDocumentsProvider.METHOD_RESET, null, null))
            }
        }
    }

    private fun verifyPackages(allRoles: Boolean) = runBlocking {
        withTimeout(120_000L) {
            StandardInstrumentationTargetGuard.requireCurrentTarget()
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val provider = Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}")
            checkNotNull(target.contentResolver.call(provider, GoldenQaDocumentsProvider.METHOD_RESET, null, null))
            if (!allRoles) checkNotNull(target.contentResolver.call(provider, GoldenQaDocumentsProvider.METHOD_SET_FAULT,
                GoldenQaDocumentsProvider.FAULT_GENERIC_REMAINING_MIME, null))
            val tree = GoldenQaDocumentsProvider.treeUri
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            target.grantUriPermission(target.packageName, tree, flags)
            val context = FixtureContext(target)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
                val root = checkNotNull(roots.captureHandle())
                val bindings = SourceBindingRegistry(context, roots, mutations, dao)
                val governor = BackgroundWorkCoordinator()
                val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
                val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
                val engine = YoutubeDlEngine(target, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null)
                val oracle = MergerOracleFixture()
                val original = oracle.original(target, context.cacheDir)
                val frames = inspectOriginalAudio(original).audibleFrames48k
                assertEquals(oracle.frameCount.toLong(), frames)
                val sourceHash = sha(original)
                val operands = oracle.targets(target, context.cacheDir)
                val operandBytes = operands.map(File::readBytes)
                operands.forEachIndexed { index, file ->
                    MergerOracleFixture.assertPcm("prepared target-$index", oracle.targetPcm(index),
                        MergerOracleFixture.decode(target, file, oracle.frameCount))
                }
                val remainingByCount = (if (allRoles) 1..8 else 1..1).associateWith { count ->
                    File(context.cacheDir, "remaining-$count.wv").also {
                        engine.renderRemaining(original, operands.take(count), it, 2L * 1024 * 1024, 2)
                    }
                }
                val anchors = oracle.roles
                val selections = if (allRoles) (1..8).map { anchors.take(it) }.toMutableList() else mutableListOf(listOf("bass"))
                val uncovered = if (allRoles) StemSourceCatalogue.sources.map { it.id }.toMutableList() else mutableListOf()
                while (uncovered.isNotEmpty()) {
                    val selection = mutableListOf<String>()
                    for (id in uncovered) {
                        if (selection.size < 8 && StemSourceCatalogue.selectionIssue(selection + id) == null) selection += id
                    }
                    check(selection.isNotEmpty())
                    selections += selection
                    uncovered.removeAll(selection.toSet())
                }
                val covered = linkedSetOf<String>()
                selections.forEachIndexed { case, selected ->
                    StemSourceCatalogue.requireSelection(selected)
                    val videoId = "PQA" + case.toString().padStart(8, '0')
                    publisher.publishOriginal(root, videoId, original.copyTo(File(context.cacheDir, "publish-original.flac")), sourceHash).getOrThrow()
                    val folder = File(File(target.noBackupFilesDir, "golden-enrichment-document-provider"), videoId)
                    seedCompleteOpusMetadata(folder, videoId, original.readBytes(), SelectedLibraryContract.ORIGINAL_FLAC)
                    external.reconcilePackage(root, videoId)
                    assertEquals(64, external.songs.value.single { it.id == videoId }.mediaRevision.length)
                    val owner = UUID.randomUUID().toString()
                    val assets = selected.mapIndexed { index, id ->
                        val file = operands[index].copyTo(File(context.cacheDir, "part-${index + 1}.flac"))
                        val asset = CustomSourceAsset(id, StemSourceCatalogue.sources.first { it.id == id }.label,
                            file.name, sha(file), file.length(), frames)
                        publisher.publishSourcePart(root, videoId, file.name, file, asset.sha256, owner, sourceHash, frames).getOrThrow()
                        asset
                    }.toMutableList()
                    if (!allRoles) {
                        val invalidRemaining = operands.first().copyTo(File(context.cacheDir, "invalid-remaining.wv"))
                        val invalidBytes = invalidRemaining.readBytes()
                        val priorFiles = folder.listFiles()!!.associate { it.name to it.readBytes().toList() }
                        val rejected = runCatching {
                            publisher.publishSourcePart(root, videoId, "remaining.wv", invalidRemaining,
                                sha(invalidRemaining), owner, sourceHash, frames).getOrThrow()
                        }
                        assertEquals(CUSTOM_SOURCE_MANIFEST_INVALID, rejected.exceptionOrNull()?.message)
                        assertTrue(invalidBytes.contentEquals(invalidRemaining.readBytes()))
                        assertEquals(priorFiles, folder.listFiles()!!.associate { it.name to it.readBytes().toList() })
                    }
                    val remaining = remainingByCount.getValue(selected.size).copyTo(File(context.cacheDir, "remaining.wv"))
                    val residual = CustomSourceAsset("remaining", "Remaining", remaining.name, sha(remaining), remaining.length(), frames,
                        NATIVE_ORIGINAL_48K_REMAINING_PRODUCER)
                    publisher.publishSourcePart(root, videoId, residual.fileName, remaining, residual.sha256, owner, sourceHash, frames).getOrThrow()
                    if (!allRoles) assertEquals("application/octet-stream", target.contentResolver.getType(
                        android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "root/$videoId/remaining.wv")))
                    assets += residual
                    val manifest = CustomSourceManifest(videoId, sourceHash, "1".repeat(64), selected, frames, assets)
                    publisher.publishCustomSources(root, videoId, manifest, owner).getOrThrow()
                    val installed = File(File(File(target.noBackupFilesDir, "golden-enrichment-document-provider"), videoId), SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST)
                    val reopened = parseCustomSourceManifest(installed.readBytes(), videoId, sourceHash)
                    assertEquals(selected, reopened.selectedSourceIds)
                    assertEquals(selected + "remaining", reopened.lanes.map { it.sourceId })
                    assertEquals(manifest, reopened)
                    reopened.lanes.forEachIndexed { index, lane ->
                        val committed = File(folder, lane.fileName)
                        assertEquals("${lane.sourceId}: committed byte identity", lane.sha256, sha(committed))
                        assertEquals(lane.byteLength, committed.length())
                        assertEquals(frames, inspectPreparedSourceAudio(committed).audibleFrames)
                        if (index < selected.size) {
                            assertTrue("${lane.sourceId}: compatible target bytes changed",
                                operandBytes[index].contentEquals(committed.readBytes()))
                        }
                        val expected = if (index < selected.size) oracle.targetPcm(index)
                            else oracle.remainingPcm(selected.size)
                        MergerOracleFixture.assertPcm("committed ${lane.sourceId} case=$case", expected,
                            MergerOracleFixture.decode(target, committed, oracle.frameCount,
                                directFloat = lane.sourceId == "remaining"))
                    }
                    assertTrue("Current package did not become playable", external.songs.value.single { it.id == videoId }.hasStems)
                    covered += selected
                }
                assertEquals(if (allRoles) StemSourceCatalogue.sources.mapTo(linkedSetOf()) { it.id } else setOf("bass"), covered)
            } finally {
                database.close()
                context.destroy()
                target.revokeUriPermission(tree, flags)
                checkNotNull(target.contentResolver.call(provider, GoldenQaDocumentsProvider.METHOD_RESET, null, null))
            }
        }
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        private val prefix = "current-parts-${UUID.randomUUID()}-"
        private val preferences = linkedSetOf<String>()
        private val root = File(base.cacheDir, prefix).also { check(it.mkdir()) }
        private val files = File(root, "files").also { check(it.mkdir()) }
        private val cache = File(root, "cache").also { check(it.mkdir()) }
        private val noBackup = File(root, "no-backup").also { check(it.mkdir()) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getNoBackupFilesDir(): File = noBackup
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(prefix + name, mode).also { preferences += prefix + name }
        fun destroy() {
            preferences.forEach { check(baseContext.deleteSharedPreferences(it)) }
            check(root.deleteRecursively())
        }
    }
}
