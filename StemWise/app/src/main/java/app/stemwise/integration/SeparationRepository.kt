package app.stemwise.integration

import android.content.Context
import android.net.Uri
import android.media.MediaExtractor
import android.media.MediaFormat
import app.stemwise.data.LibraryDao
import app.stemwise.data.ProcessingJobEntity
import app.stemwise.data.SeparationSetEntity
import app.stemwise.data.SourceVersionEntity
import app.stemwise.data.StemAssetEntity
import app.stemwise.data.SongWorkCoordinator
import app.stemwise.data.ExternalLibraryRepository
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.LibraryRootException
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.RootHandle
import app.stemwise.data.RootAuthority
import app.stemwise.data.authority
import app.stemwise.data.checkedPrivateChildren
import app.stemwise.data.deleteExactPrivateTree
import app.stemwise.data.CanonicalPackagePublisher
import app.stemwise.data.OriginalAudioFacts
import app.stemwise.data.inspectOriginalAudio
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.CustomSourceAsset
import app.stemwise.data.CustomSourceManifest
import app.stemwise.data.NATIVE_ORIGINAL_48K_REMAINING_PRODUCER
import app.stemwise.data.SOURCE_REMAINING_REPREPARE_REQUIRED
import app.stemwise.data.requireRemainingProducerForOriginal
import app.stemwise.data.validateCustomSourceAsset
import app.stemwise.data.validateCustomSourceAudioAsset
import app.stemwise.data.SourceTimingRawArtifact
import app.stemwise.data.SOURCE_TIMING_COPY_PROFILE
import app.stemwise.data.SOURCE_TIMING_CONVERSION_PROFILE
import app.stemwise.data.SOURCE_TIMING_RETAINED_PROFILE
import app.stemwise.data.requireSourceTimingOriginalBinding
import app.stemwise.data.inspectPreparedSourceAudio
import app.stemwise.model.StemSource
import app.stemwise.model.StemSourceCatalogue
import app.stemwise.data.canonicalRootSourceLocation
import app.stemwise.model.StemRoleContract
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.suspendResult
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.UnmeteredNetworkRequiredException
import app.stemwise.network.WAITING_FOR_UNMETERED_NETWORK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.abs

enum class SeparationWorkResult {
    COMPLETE,
    NEXT_OPERATION,
    PENDING,
    WAITING_FOR_ACCOUNT,
    SUBMISSION_UNKNOWN,
    TERMINAL_FAILURE
}

internal data class SeparationQueueHandoff(
    val jobId: String?,
    val libraryId: String,
    val songId: String,
    val sourceVersionId: String?,
    val sourceSha256: String?,
    val state: String,
    val requiresWorker: Boolean
)

class SeparationRepository internal constructor(
    context: Context,
    private val dao: LibraryDao,
    private val account: MvsepAccountAccess,
    private val roots: LibraryRootManager,
    private val mutations: PackageMutationCoordinator,
    private val songWorkCoordinator: SongWorkCoordinator,
    private val externalLibrary: ExternalLibraryRepository,
    private val canonicalPublisher: CanonicalPackagePublisher,
    private val backgroundWorkCoordinator: BackgroundWorkCoordinator,
    private val engine: YoutubeDlEngine,
    networkAccess: AppNetworkAccess,
    private val client: MvsepServiceClient = MvsepClient(
        account::apiBase,
        networkAccess
    ),
    acknowledgements: SourceAcknowledgementStore? = null
) {
    private val applicationContext = context.applicationContext
    private val acknowledgementStore by lazy {
        acknowledgements ?: SourceAcknowledgementStore(File(applicationContext.filesDir, "source-acknowledgements"))
    }
    @Volatile internal var beforeSourceAssemblyForTest: (suspend () -> Unit)? = null
    val activeJobs: Flow<List<ProcessingJobEntity>> = dao.observeActiveJobs()

    suspend fun availableSources(): List<StemSource> =
        MvsepSourcePlanner.availableSources(client.sourceAlgorithms())

    internal suspend fun queueSources(expectedRoot: RootHandle, songId: String,
        selectedIds: List<String>): Result<SeparationQueueHandoff> = withContext(Dispatchers.IO) {
        suspendResult {
            StemSourceCatalogue.requireSelection(selectedIds)
            songWorkCoordinator.withSong(songId) {
                songWorkCoordinator.preparationLane.withLock {
                    roots.requireSameLibrary(expectedRoot)
                    val source = canonicalCurrentSource(expectedRoot, songId)
                        ?: error("The original audio is no longer available")
                    val prior = readSourceAuthority(expectedRoot, songId)
                    val publishedBytes = roots.readExactSongSidecars(expectedRoot, songId,
                        setOf(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST), app.stemwise.data.CUSTOM_SOURCE_MANIFEST_MAX_BYTES)
                        .get(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST)
                    val published = publishedBytes?.let { bytes ->
                        runCatching { app.stemwise.data.parseCustomSourceManifest(bytes, songId, source.sha256) }.getOrNull()
                    }
                    val publishedWithoutJob = published.takeIf { prior == null }
                    val verifiedComplete = if (published != null &&
                        published.selectedSourceIds.toSet() == selectedIds.toSet()) {
                        val verified = externalLibrary.verifyPlaybackSong(expectedRoot, songId)
                        verified.hasStems && verified.sourceHash == source.sha256 &&
                            verified.stemRevision.startsWith("${published.revisionSha256}|")
                    } else false
                    if (prior == null && verifiedComplete) {
                        return@withLock SeparationQueueHandoff(null, expectedRoot.libraryId, songId, source.id,
                            source.sha256, STATE_COMPLETE, false)
                    }
                    val initialGeneration = if (publishedWithoutJob != null &&
                        !publishedWithoutJob.lanes.first().fileName.startsWith("replacement-")) 2L else 1L
                    // A conclusively completed owner may start an explicit repair generation.
                    // Unknown/accepted in-flight requests still keep their exact frozen authority.
                    val replacing = prior != null && (prior.state == "DISCARDED" || prior.state == STATE_COMPLETE &&
                        (!verifiedComplete || prior.plan.selectedSourceIds.toSet() != selectedIds.toSet()))
                    val plan = if (prior != null && !replacing && prior.plan.selectedSourceIds.toSet() == selectedIds.toSet()) prior.plan
                        else MvsepSourcePlanner.plan(selectedIds.toList(), client.sourceAlgorithms())
                    val requested = if (replacing) {
                        check(prior!!.sourceVersionId == source.id && prior.sourceSha256 == source.sha256) { "SOURCE_AUDIO_CHANGED" }
                        var generation = Math.addExact(prior.generation, 1L)
                        val publishedUsesReplacement = published?.lanes?.first()?.fileName?.startsWith("replacement-")
                        if (publishedUsesReplacement != null && (generation % 2L == 0L) == publishedUsesReplacement) {
                            generation = Math.addExact(generation, 1L)
                        }
                        createPortableSourceJobAuthority(expectedRoot.libraryId, songId, source.id, source.sha256,
                            plan, System.currentTimeMillis(), generation)
                    } else prior?.also {
                        check(it.sourceVersionId == source.id && it.sourceSha256 == source.sha256 &&
                            it.plan.selectedSourceIds.toSet() == selectedIds.toSet()) {
                            "Finish or recover the current preparation before choosing different parts. Existing parts are preserved."
                        }
                    } ?: createPortableSourceJobAuthority(expectedRoot.libraryId, songId,
                        source.id, source.sha256, plan, System.currentTimeMillis(), initialGeneration)
                    val original = roots.checkedAuthoritativeSongSnapshot(expectedRoot, songId)?.original
                        ?: error("SOURCE_AUDIO_MISSING")
                    original.sizeBytes?.let(::requireMvsepUploadByteCount)
                    val facts = inspectSourceOriginal(original.uri, MVSEP_FREE_UPLOAD_MAX_BYTES)
                    requireSourceManifestCapacity(requested, facts)
                    requireSourcePreparationResources(facts.audibleFrames48k, plan.selectedSourceIds.size,
                        android.os.StatFs(applicationContext.noBackupFilesDir.absolutePath).availableBytes)
                    requireSourceOwnerAdmission(expectedRoot, requested.jobId, songId)
                    val durable = persistSourceAuthority(expectedRoot, requested)
                    val job = projectSourceJob(durable, dao.findJobById(durable.jobId))
                    persistSeparationQueueHandoff(job, source.sha256, dao::upsertJob, dao::findJobById)
                }
            }
        }
    }

    private val sourceAuthorityFiles = SOURCE_JOB_AUTHORITY_FILES

    private suspend fun requireSourceOwnerAdmission(root: RootHandle, jobId: String, songId: String,
        promotesAcceptedOwner: Boolean = false) {
        val owners = dao.automaticPreparationOwnerJobs()
        check(owners.all { it.libraryId == root.libraryId && (it.songId != songId || it.id == jobId) }) {
            "This song already has a different preparation owner."
        }
        check(!promotesAcceptedOwner || owners.none { it.id != jobId && it.state != STATE_CREATED }) {
            "Another song is being prepared. Retry this accepted job after that preparation finishes."
        }
    }

    private suspend fun recoverSourceAuthorities(root: RootHandle): List<SourceAuthorityRecoveryIssue> {
        roots.requireSameLibrary(root)
        val directory = File(applicationContext.filesDir, "operation-journals")
        val records = app.stemwise.data.checkedAtomicJournalRecords(directory, PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED)
        return replayJournaledSourceAuthorities(root.authority(), records) { persistSourceAuthority(root, it) }
    }

    private suspend fun readSourceAuthority(root: RootHandle, songId: String): PortableSourceJobAuthority? {
        check(recoverSourceAuthorities(root).isEmpty()) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
        return readSourceAuthorityAfterRecovery(root, songId)
    }

    private suspend fun readSourceAuthorityAfterRecovery(root: RootHandle, songId: String): PortableSourceJobAuthority? {
        return readPortableSourceJobAuthority(roots, root, songId)
    }

    private suspend fun persistSourceAuthority(root: RootHandle,
        requested: PortableSourceJobAuthority): PortableSourceJobAuthority = mutations.withPackageMutation(
        root, requested.videoId, "SOURCE_JOB_AUTHORITY_V1", "source-${requested.jobId}",
        requireExactRoot = { roots.requireSameLibrary(root) }
    ) { journal ->
        val (canonicalName, temporaryName) = sourceAuthorityFileNames(requested.generation)
        var before = roots.readExactSongSidecars(root, requested.videoId, sourceAuthorityFiles,
            PORTABLE_SOURCE_JOB_MAX_BYTES)
        val recovered = journal.recoveredString("AUTHORITY_WRITING", "authorityJson")?.let {
            parsePortableSourceJobAuthority(it.toByteArray(Charsets.UTF_8))
        }
        check(!journal.wasRecoveredInState("AUTHORITY_WRITING") || recovered != null) {
            PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
        }
        recovered?.let { check(it.sameIdentity(requested)) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED } }
        val previousSlot = try {
            selectPortableSourceJobAuthority(before[canonicalName], before[temporaryName])
        } catch (error: IllegalStateException) {
            if (recovered == null) throw error
            selectPortableSourceJobAuthority(before[canonicalName], before[temporaryName], recovered)
        }
        if (previousSlot != null && previousSlot.generation != requested.generation) {
            check(previousSlot.state in setOf(STATE_COMPLETE, "DISCARDED") && requested.state == STATE_CREATED && requested.revision == 1L &&
                requested.generation > previousSlot.generation) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            roots.deleteExactSongSidecars(root, requested.videoId, setOf(canonicalName, temporaryName))
            before = roots.readExactSongSidecars(root, requested.videoId, sourceAuthorityFiles, PORTABLE_SOURCE_JOB_MAX_BYTES)
        }
        val current = selectPortableSourceJobAuthority(before[canonicalName], before[temporaryName], recovered)
        val durable = if (current == null) {
            check(requested.revision == 1L && requested.state == STATE_CREATED) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            requested
        } else selectPortableSourceJobAuthority(current.toBytes(), requested.toBytes())!!
        val bytes = durable.toBytes()
        journal.write("AUTHORITY_WRITING", JSONObject().put("authorityJson", bytes.toString(Charsets.UTF_8)))
        roots.writeExactSongSidecar(root, durable.videoId, temporaryName,
            "application/json", bytes, PORTABLE_SOURCE_JOB_MAX_BYTES)
        roots.writeExactSongSidecar(root, durable.videoId, canonicalName,
            "application/json", bytes, PORTABLE_SOURCE_JOB_MAX_BYTES)
        roots.deleteExactSongSidecars(root, durable.videoId, setOf(temporaryName))
        check(readSourceAuthorityAfterRecovery(root, durable.videoId) == durable) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
        journal.complete(JSONObject().put("revision", durable.revision))
        durable
    }

    private suspend fun saveSourceProgress(root: RootHandle, authority: PortableSourceJobAuthority): PortableSourceJobAuthority {
        val durable = persistSourceAuthority(root, authority)
        dao.upsertJob(projectSourceJob(durable, dao.findJobById(durable.jobId)).copy(
            nextAttemptAtEpochMs = null,
            lastErrorCode = "MVSEP_REMOTE_FAILED".takeIf { durable.state == "REMOTE_FAILED" },
            lastErrorMessage = projectSourceJob(durable).lastErrorMessage.takeIf { durable.state == "REMOTE_FAILED" }))
        return durable
    }

    private fun recoveredAcknowledgement(root: RootHandle, current: PortableSourceJobAuthority): PortableSourceJobAuthority? =
        acknowledgementStore.read(root.authority(), current)

    private suspend fun settleAcknowledgement(root: RootHandle, acknowledged: PortableSourceJobAuthority): PortableSourceJobAuthority =
        acknowledgementStore.settle(root.authority(), acknowledged) { saveSourceProgress(root, acknowledged) }

    private suspend fun reconcileSourceAuthorities(root: RootHandle): List<SourceAuthorityRecoveryIssue> {
        val issues = recoverSourceAuthorities(root).toMutableList()
        roots.checkedSnapshot(root).songDirectories.forEach { directory ->
            if (issues.any { it.videoId == directory.displayName }) return@forEach
            val song = roots.checkedBoundSongSnapshot(root, directory.displayName, directory.uri.toString())
            if (song.files.keys.none(sourceAuthorityFiles::contains)) return@forEach
            try {
                var authority = checkNotNull(readSourceAuthorityAfterRecovery(root, song.videoId))
                recoveredAcknowledgement(root, authority)?.let { recovered ->
                    authority = saveSourceProgress(root, recovered)
                    acknowledgementStore.remove(root.authority(), authority.jobId)
                }
                if (canonicalCurrentSource(root, song.videoId) == null) {
                    externalLibrary.verifyPlaybackSong(root, song.videoId)
                }
                val source = checkNotNull(canonicalCurrentSource(root, song.videoId))
                check(source.id == authority.sourceVersionId && source.sha256 == authority.sourceSha256) {
                    PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
                }
                if (authority.state == STATE_SUBMISSION_STARTED) {
                    authority = transitionPortableSourceJob(authority, SourceJobTransition.MARK_SUBMISSION_UNKNOWN,
                        maxOf(System.currentTimeMillis(), authority.updatedAtEpochMs))
                }
                val durable = persistSourceAuthority(root, authority)
                dao.upsertJob(projectSourceJob(durable, dao.findJobById(durable.jobId)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (unavailable: java.io.IOException) { throw unavailable }
            catch (error: Exception) {
                roots.requireSameLibrary(root)
                app.stemwise.data.canonicalProjectionRootAccessFailure(error)?.let { throw it }
                issues += SourceAuthorityRecoveryIssue(song.videoId, PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED)
            }
        }
        return issues.distinctBy { it.videoId }
    }

    private suspend fun processSources(job: ProcessingJobEntity, root: RootHandle): SeparationWorkResult {
        val retained = acknowledgementStore.read(root.authority(), job)
        var authority = retained ?: checkNotNull(readSourceAuthority(root, job.songId))
        check(projectSourceJob(authority, job).id == job.id)
        val directory = File(applicationContext.noBackupFilesDir, "preparation-active")
        try {
            activeScratchDirectory()
            authority = if (retained != null) settleAcknowledgement(root, retained)
                else authority
            if (authority.state in setOf("REMOTE_FAILED", "DISCARDED")) return SeparationWorkResult.TERMINAL_FAILURE
            if (retained == null && job.state == STATE_FAILED && authority.state != STATE_COMPLETE) return SeparationWorkResult.TERMINAL_FAILURE
            if (authority.state == STATE_SUBMISSION_UNKNOWN) return SeparationWorkResult.SUBMISSION_UNKNOWN
            cleanupDisposableSeparationScratch(directory)
            val source = checkNotNull(canonicalCurrentSource(root, job.songId))
            check(source.id == authority.sourceVersionId && source.sha256 == authority.sourceSha256) {
                "SOURCE_AUDIO_CHANGED"
            }
            val sourceUri = checkNotNull(roots.resolveAuthoritativeSongAsset(root, job.songId,
                SelectedLibraryContract.ORIGINAL))
            if (authority.state == STATE_COMPLETE) {
                dao.upsertJob(projectSourceJob(authority, job))
                return SeparationWorkResult.COMPLETE
            }
            var verifiedOriginal: Pair<File, OriginalAudioFacts>? = null
            suspend fun originalCopy(): Pair<File, OriginalAudioFacts> {
                verifiedOriginal?.let { return it }
                val original = materializeUploadSource(sourceUri, source.sha256, source.codec)
                val inspectionContext = currentCoroutineContext()
                return (original to inspectOriginalAudio(original) { inspectionContext.ensureActive() })
                    .also { verifiedOriginal = it }
            }
            withRequiredSourceSubmission(authority) {
                val credential = mvsepSourceSubmissionCredential(account, client)
                    ?: return SeparationWorkResult.WAITING_FOR_ACCOUNT
                val operation = authority.plan.operations[authority.activeOperationIndex]
                MvsepSourcePlanner.validateOperation(operation, client.sourceAlgorithms())
                val (_, remoteId) = submitPreparedSourceUpload(
                    materialize = { originalCopy().first },
                    requireResources = { prepared ->
                        val facts = originalCopy().second
                        check(facts.channels in 1..2) { "SOURCE_AUDIO_UNSUPPORTED" }
                        requireMvsepUploadByteCount(prepared.length())
                        requireSourcePreparationResources(facts.audibleFrames48k, authority.plan.selectedSourceIds.size,
                            android.os.StatFs(directory.absolutePath).availableBytes)
                    },
                    ensureEncoderReady = { engine.ensureSourceEncodersReady() },
                    submit = { upload ->
                        client.submitConfigured(credential.token, upload, operation,
                            isAuthorized = { account.isCurrent(credential) }, onSubmissionEntered = {
                                roots.requireSameLibrary(root)
                                check(canonicalCurrentSource(root, job.songId)?.id == source.id &&
                                    dao.findSongById(job.songId)?.highLevelState != "DELETING") { "SOURCE_AUDIO_CHANGED" }
                                authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                                    SourceJobTransition.ENTER_SUBMISSION, System.currentTimeMillis()))
                            }, onAcknowledged = { id ->
                                authority = settleAcknowledgement(root, transitionPortableSourceJob(authority,
                                    SourceJobTransition.ACKNOWLEDGE_REMOTE, System.currentTimeMillis(), id))
                            }, onRejected = { reason ->
                                authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                                    SourceJobTransition.REJECT_SUBMISSION, System.currentTimeMillis()))
                                dao.upsertJob(projectSourceJob(authority).copy(state = STATE_FAILED,
                                    lastErrorCode = "MVSEP_SUBMISSION_REJECTED", lastErrorMessage = reason))
                            })
                    }
                )
                check(authority.currentRemoteJobId == remoteId) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            }
            if (authority.state == STATE_SUBMISSION_STARTED) {
                authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                    SourceJobTransition.MARK_SUBMISSION_UNKNOWN, System.currentTimeMillis()))
                return SeparationWorkResult.SUBMISSION_UNKNOWN
            }
            if (authority.state == "DOWNLOADING") {
                val operation = authority.plan.operations[authority.activeOperationIndex]
                if (operation.outputBindings.keys.all(authority.assets::containsKey)) {
                    val facts = originalCopy().second
                    operation.outputBindings.keys.forEach { sourceId ->
                        val receipt = authority.assets.getValue(sourceId)
                        requirePortableSourceTimingBinding(authority, receipt)
                        requireSourceTimingOriginalBinding(receipt, facts)
                        checkRetainedSourceAsset(root, job.songId, receipt)
                    }
                    authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                        if (authority.activeOperationIndex < authority.plan.operations.lastIndex)
                            SourceJobTransition.ADVANCE_OPERATION else SourceJobTransition.BEGIN_ASSEMBLY,
                        System.currentTimeMillis()))
                    if (authority.state == STATE_CREATED) return SeparationWorkResult.NEXT_OPERATION
                }
            }
            if (authority.state in setOf("PROCESSING", "DOWNLOADING")) {
                val operation = authority.plan.operations[authority.activeOperationIndex]
                val remote = withAcknowledgedMvsepRead { client.status(checkNotNull(authority.currentRemoteJobId)) }
                if (remote.status in setOf("waiting", "preparing", "processing", "distributing")) {
                    dao.upsertJob(projectSourceJob(authority).copy(
                        nextAttemptAtEpochMs = System.currentTimeMillis() + POLL_INTERVAL_MS,
                        lastErrorMessage = "Preparing selected parts"))
                    return SeparationWorkResult.PENDING
                }
                // MVSEP's successful status response explicitly names `failed`; transport/unknown responses do not retire accepted work.
                if (remote.status == "failed") {
                    authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                        SourceJobTransition.REMOTE_FAILED, System.currentTimeMillis()))
                    return SeparationWorkResult.TERMINAL_FAILURE
                }
                check(remote.status == "done") { "MVSEP_SEPARATION_FAILED" }
                val files = requireSourceOperationFiles(operation, remote.files)
                val originalFacts = originalCopy().second
                if (authority.state == "PROCESSING") authority = saveSourceProgress(root,
                    transitionPortableSourceJob(authority, SourceJobTransition.BEGIN_DOWNLOAD, System.currentTimeMillis()))
                for (sourceId in operation.outputBindings.keys) {
                    if (sourceId !in authority.assets) {
                        recoverSourceReceipt(root, authority, sourceId, originalFacts)?.let {
                            authority = saveSourceProgress(root, recordPortableSourceAsset(authority, it, System.currentTimeMillis()))
                        }
                    }
                    authority.assets[sourceId]?.let { receipt ->
                        requirePortableSourceTimingBinding(authority, receipt)
                        requireSourceTimingOriginalBinding(receipt, originalFacts)
                        checkRetainedSourceAsset(root, job.songId, receipt)
                    } ?: run {
                        cleanupDisposableSeparationScratch(directory, preserveOriginal = true)
                        val flac = File(directory, "active-stem.flac")
                        val output = File(directory, "active-part.flac.part")
                        withAcknowledgedMvsepRead { client.download(files.getValue(sourceId), flac) }
                        val flacFacts = inspectFlac16(flac)
                        check(flacFacts.channelCount in 1..2) { "MVSEP_OUTPUT_FORMAT_INVALID" }
                        requireSameSourceTimeline(flacFacts.totalSamples, flacFacts.sampleRateHz,
                            originalFacts.audibleFrames48k)
                        val raw = SourceTimingRawArtifact(sha256(flac), flac.length(), flacFacts.sampleRateHz,
                            flacFacts.channelCount, flacFacts.totalSamples)
                        val byteCopy = flacFacts.sampleRateHz == 48_000 && flacFacts.channelCount == 2 &&
                            flacFacts.totalSamples == originalFacts.audibleFrames48k
                        if (byteCopy) {
                            check(flac.renameTo(output)) { "STEM_FLAC_ENCODE_FAILED" }
                        } else engine.encodeLosslessPart(flac, output,
                            MAX_PRIVATE_PREPARATION_ASSET_BYTES - directory.listFiles()!!.sumOf(File::length), flacFacts.channelCount,
                            originalFrames48k = originalFacts.audibleFrames48k)
                        requireBoundedPrivatePreparationScratch(directory)
                        val facts = inspectPreparedSourceAudio(output)
                        check(facts.channels == 2 && facts.sampleRate == 48_000 && facts.audibleFrames == originalFacts.audibleFrames48k) {
                            "SOURCE_TIMELINE_MISMATCH"
                        }
                        val receipt = CustomSourceAsset(sourceId, checkNotNull(StemSourceCatalogue.source(sourceId)).label,
                            sourcePartFileName(authority.generation, authority.plan.selectedSourceIds, sourceId), sha256(output), output.length(),
                            facts.audibleFrames).let { asset -> asset.copy(timingEvidence = sourceTimingEvidence(authority,
                                asset, originalFacts, raw,
                                if (byteCopy) SOURCE_TIMING_COPY_PROFILE else SOURCE_TIMING_CONVERSION_PROFILE)) }
                        check(!flac.exists() || flac.delete()) { "PRIVATE_CLEANUP_FAILED" }
                        val ready = File(directory, "active-part.flac")
                        check(output.renameTo(ready)) { "STEM_FLAC_ENCODE_FAILED" }
                        canonicalPublisher.publishSourcePart(root, job.songId, receipt.fileName, ready,
                            receipt.sha256, job.id, authority.sourceSha256, receipt.audibleFrames).getOrThrow()
                        checkRetainedSourceAsset(root, job.songId, receipt)
                        authority = saveSourceProgress(root, recordPortableSourceAsset(authority, receipt, System.currentTimeMillis()))
                        cleanupDisposableSeparationScratch(directory, preserveOriginal = true)
                    }
                }
                if (authority.activeOperationIndex == authority.plan.operations.lastIndex) beforeSourceAssemblyForTest?.invoke()
                authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                    if (authority.activeOperationIndex < authority.plan.operations.lastIndex)
                        SourceJobTransition.ADVANCE_OPERATION else SourceJobTransition.BEGIN_ASSEMBLY,
                    System.currentTimeMillis()))
                if (authority.state == STATE_CREATED) return SeparationWorkResult.NEXT_OPERATION
            }
            if (authority.state == "ASSEMBLING") {
                val (original, facts) = originalCopy()
                authority.plan.selectedSourceIds.forEach { sourceId ->
                    val asset = authority.assets.getValue(sourceId)
                    requirePortableSourceTimingBinding(authority, asset)
                    requireSourceTimingOriginalBinding(asset, facts)
                    checkRetainedSourceAsset(root, job.songId, asset)
                }
                authority.assets["remaining"]?.let { requireRemainingProducerForOriginal(it, facts.sampleRateHz) }
                if ("remaining" !in authority.assets) {
                    recoverSourceReceipt(root, authority, "remaining", facts)?.let {
                        authority = saveSourceProgress(root, recordPortableSourceAsset(authority, it, System.currentTimeMillis()))
                    }
                }
                if ("remaining" !in authority.assets) {
                    val targets = authority.plan.selectedSourceIds.mapIndexed { index, sourceId ->
                        val asset = authority.assets.getValue(sourceId)
                        val uri = checkNotNull(roots.resolveAuthoritativeSongAsset(root, job.songId, asset.fileName))
                        copySourceOperand(uri, File(directory, "remaining-input-${index + 1}.flac"), asset)
                    }
                    val output = File(directory, "active-remaining.wv.part")
                    requireBoundedRemainingScratch(directory)
                    engine.renderRemaining(original, targets, output, MAX_PRIVATE_PREPARATION_ASSET_BYTES -
                        directory.listFiles()!!.sumOf(File::length), originalChannels = facts.channels)
                    requireBoundedRemainingScratch(directory)
                    val remainingFacts = inspectPreparedSourceAudio(output)
                    check(remainingFacts.channels == 2 && remainingFacts.audibleFrames == facts.audibleFrames48k && remainingFacts.isFloat) {
                        "SOURCE_TIMELINE_MISMATCH"
                    }
                    val receipt = CustomSourceAsset("remaining", "Remaining", sourcePartFileName(authority.generation, authority.plan.selectedSourceIds, "remaining"),
                        sha256(output), output.length(), facts.audibleFrames48k, NATIVE_ORIGINAL_48K_REMAINING_PRODUCER)
                    val ready = File(directory, "active-remaining.wv")
                    check(output.renameTo(ready)) { "REMAINING_RENDER_FAILED" }
                    canonicalPublisher.publishSourcePart(root, job.songId, receipt.fileName, ready,
                        receipt.sha256, remainingPublicationOwnerId(job.id), authority.sourceSha256,
                        receipt.audibleFrames).getOrThrow()
                    checkRetainedSourceAsset(root, job.songId, receipt)
                    authority = saveSourceProgress(root, recordPortableSourceAsset(authority, receipt, System.currentTimeMillis()))
                } else checkRetainedSourceAsset(root, job.songId, authority.assets.getValue("remaining"))
                authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                    SourceJobTransition.BEGIN_PUBLICATION, System.currentTimeMillis()))
            }
            if (authority.state == "PUBLISHING") {
                val originalFacts = originalCopy().second
                requireRemainingProducerForOriginal(authority.assets.getValue("remaining"), originalFacts.sampleRateHz)
                cleanupDisposableSeparationScratch(directory, preserveOriginal = true)
                val assets = (authority.plan.selectedSourceIds + "remaining").map { authority.assets.getValue(it) }
                assets.forEach {
                    requirePortableSourceTimingBinding(authority, it)
                    requireSourceTimingOriginalBinding(it, originalFacts)
                    checkRetainedSourceAsset(root, job.songId, it)
                }
                val manifest = CustomSourceManifest(job.songId, authority.sourceSha256, authority.planSha256,
                    authority.plan.selectedSourceIds, assets.first().audibleFrames, assets)
                canonicalPublisher.publishCustomSources(root, job.songId, manifest, job.id).getOrThrow()
                authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                    SourceJobTransition.COMPLETE, System.currentTimeMillis()))
            }
            check(authority.state == STATE_COMPLETE) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            return SeparationWorkResult.COMPLETE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error is app.stemwise.playback.engine.WorkerRetirementException) {
                app.stemwise.integration.WorkerRetirementQuarantine.retain(error, files = listOf(directory))
                try {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        roots.requireSameLibrary(root)
                        val current = dao.findJobById(job.id)
                        check(current != null && current.libraryId == root.libraryId &&
                            current.songId == job.songId && current.sourceVersionId == job.sourceVersionId &&
                            current.kind == job.kind && current.modelId == job.modelId) { "SEPARATION_JOB_MISMATCH" }
                        roots.requireSameLibrary(root)
                        dao.upsertJob(current.copy(state = STATE_FAILED, nextAttemptAtEpochMs = null,
                            lastErrorCode = stableSeparationFailureCode(error),
                            lastErrorMessage = "Preparation stopped because its worker has not retired. Working files and accepted jobs were preserved.",
                            updatedAtEpochMs = System.currentTimeMillis()))
                    }
                } catch (reporting: Throwable) {
                    if (reporting !== error) error.addSuppressed(reporting)
                }
                throw error
            }
            roots.requireSameLibrary(root)
            recoveredAcknowledgement(root, authority)?.let { retained ->
                // The private receipt owns the accepted ID while selected-root publication retries.
                dao.upsertJob(projectSourceJob(retained, dao.findJobById(job.id)).copy(
                    nextAttemptAtEpochMs = System.currentTimeMillis() + RETRY_INTERVAL_MS,
                    lastErrorCode = "SOURCE_ACKNOWLEDGEMENT_PUBLICATION_PENDING",
                    lastErrorMessage = "Saving the accepted job. Retrying without resubmitting."))
                return SeparationWorkResult.PENDING
            }
            // Portable publication may have committed before its Room projection failed.
            authority = checkNotNull(readSourceAuthority(root, job.songId))
            val recovered = projectSourceJob(authority, dao.findJobById(job.id))
            if (authority.state == STATE_COMPLETE) {
                dao.upsertJob(recovered)
                return SeparationWorkResult.COMPLETE
            }
            if (authority.state == STATE_SUBMISSION_STARTED) {
                authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                    if (error is MvsepSubmissionRejected) SourceJobTransition.REJECT_SUBMISSION
                    else SourceJobTransition.MARK_SUBMISSION_UNKNOWN,
                    maxOf(System.currentTimeMillis(), authority.updatedAtEpochMs)))
                if (error !is MvsepSubmissionRejected) return SeparationWorkResult.SUBMISSION_UNKNOWN
            }
            val wait = error is UnmeteredNetworkRequiredException
            val current = projectSourceJob(authority, dao.findJobById(job.id))
            val retry = scheduleAcknowledgedMvsepRetry(current, error, System.currentTimeMillis())
            if (retry != null) {
                dao.upsertJob(retry)
                return SeparationWorkResult.PENDING
            }
            dao.upsertJob(current.copy(
                state = if (wait) authority.state else STATE_FAILED,
                nextAttemptAtEpochMs = if (wait) System.currentTimeMillis() + RETRY_INTERVAL_MS else null,
                lastErrorCode = if (wait) current.lastErrorCode?.takeIf { it in MVSEP_REMOTE_RETRY_CODES }
                    ?: WAITING_FOR_UNMETERED_NETWORK else stableSeparationFailureCode(error),
                lastErrorMessage = if (wait) "Waiting for an unmetered connection" else friendlyError(error)))
            return if (wait) SeparationWorkResult.PENDING else SeparationWorkResult.TERMINAL_FAILURE
        } finally {
            cleanupDisposableSeparationScratch(directory)
        }
    }

    private suspend fun checkRetainedSourceAsset(root: RootHandle, songId: String, asset: CustomSourceAsset) {
        val uri = checkNotNull(roots.resolveAuthoritativeSongAsset(root, songId, asset.fileName))
        val context = currentCoroutineContext()
        val facts = applicationContext.contentResolver.withCancellableProviderInput(uri) { input, _ ->
            runCancellableBlockingOperation(cancelExact = input::close) {
                inspectPreparedSourceAudio(input, asset.byteLength) { context.ensureActive() }
            }
        } ?: error("SOURCE_ASSET_MISSING")
        val snapshot = roots.checkedAuthoritativeSongSnapshot(root, songId)?.file(asset.fileName)
            ?: error("SOURCE_ASSET_MISSING")
        val actual = app.stemwise.data.measureSelectedLibraryDocument(applicationContext, snapshot) {
            context.ensureActive()
            roots.requireSameLibrary(root)
        }
        validateCustomSourceAudioAsset(asset, actual.sha256, actual.byteLength, facts)
        if (facts.isFloat) return
        applicationContext.contentResolver.withCancellableProviderAssetFileDescriptor(uri) { descriptor ->
            val extractor = MediaExtractor()
            try {
                if (descriptor.declaredLength >= 0L) {
                    extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                } else {
                    extractor.setDataSource(descriptor.fileDescriptor)
                }
                val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: error("SOURCE_AUDIO_UNSUPPORTED")
                validateCustomSourceAsset(asset, actual.sha256, actual.byteLength, facts,
                    format.getString(MediaFormat.KEY_MIME) ?: "", format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT))
            } finally { extractor.release() }
        } ?: error("SOURCE_ASSET_MISSING")
        roots.requireSameLibrary(root)
    }

    private suspend fun inspectSourceOriginal(uri: Uri, maxBytes: Long): app.stemwise.data.OriginalAudioFacts {
        val context = currentCoroutineContext()
        return applicationContext.contentResolver.withCancellableProviderInput(uri) { input, _ ->
            runCancellableBlockingOperation(cancelExact = input::close) {
                inspectOriginalAudio(input, maxBytes) { context.ensureActive() }
            }
        } ?: error("SOURCE_ASSET_MISSING")
    }

    private suspend fun recoverSourceReceipt(root: RootHandle, authority: PortableSourceJobAuthority,
        sourceId: String, original: app.stemwise.data.OriginalAudioFacts): CustomSourceAsset? {
        val fileName = sourcePartFileName(authority.generation, authority.plan.selectedSourceIds, sourceId)
        val remaining = sourceId == "remaining"
        val observed = try {
            canonicalPublisher.recoverPublishedSourcePart(root, authority.videoId, fileName,
                if (remaining) remainingPublicationOwnerId(authority.jobId) else authority.jobId)
        } catch (error: IllegalStateException) {
            if (remaining && error.message == "SOURCE_PART_RECOVERY_REQUIRED")
                throw IllegalStateException(SOURCE_REMAINING_REPREPARE_REQUIRED, error)
            throw error
        } ?: return null
        val label = if (sourceId == "remaining") "Remaining" else checkNotNull(StemSourceCatalogue.source(sourceId)).label
        return CustomSourceAsset(sourceId, label, fileName, observed.sha256, observed.byteLength, original.audibleFrames48k,
            if (remaining) NATIVE_ORIGINAL_48K_REMAINING_PRODUCER else null).let {
            if (remaining) it else it.copy(timingEvidence = sourceTimingEvidence(authority, it, original,
                null, SOURCE_TIMING_RETAINED_PROFILE))
        }.also {
            checkRetainedSourceAsset(root, authority.videoId, it)
        }
    }

    private suspend fun copySourceOperand(uri: Uri, target: File, asset: CustomSourceAsset): File {
        check(!target.exists() || target.delete()) { "PRIVATE_CLEANUP_FAILED" }
        applicationContext.contentResolver.withCancellableProviderInput(uri) { _, read ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                var bytes = 0L
                while (true) {
                    backgroundWorkCoordinator.checkpoint()
                    val count = read(buffer)
                    if (count < 0) break
                    bytes = Math.addExact(bytes, count.toLong())
                    check(bytes <= asset.byteLength) { "SOURCE_ASSET_CHANGED" }
                    output.write(buffer, 0, count)
                }
                check(bytes == asset.byteLength) { "SOURCE_ASSET_CHANGED" }
            }
        } ?: error("SOURCE_ASSET_MISSING")
        check(sha256(target) == asset.sha256) { "SOURCE_ASSET_CHANGED" }
        return target
    }

    internal suspend fun reconcilePortableJobAuthority(expectedRoot: RootHandle): List<SourceAuthorityRecoveryIssue> = withContext(Dispatchers.IO) {
        songWorkCoordinator.preparationLane.withLock {
            roots.requireSameLibrary(expectedRoot)
            reconcileSourceAuthorities(expectedRoot)
        }
    }
    internal suspend fun verifyPlaybackStems(songId: String, stems: List<StemAssetEntity>): List<StemAssetEntity> {
        val root = roots.captureHandle() ?: error("LIBRARY_CHANGED")
        return externalLibrary.verifyPlaybackStems(root, songId, stems)
    }

    fun observeSets(songId: String): Flow<List<SeparationSetEntity>> = externalLibrary.observeStemSets(songId)
    fun observeActiveStems(songId: String): Flow<List<StemAssetEntity>> = externalLibrary.observeActiveStems(songId)
    internal fun currentStemMetadata(songId: String) = externalLibrary.currentStemMetadata(songId)
    suspend fun stemsForSet(setId: String): List<StemAssetEntity> = withContext(Dispatchers.IO) { dao.stemsForSet(setId) }
    suspend fun openSeparationJobs(expectedRoot: RootHandle): List<ProcessingJobEntity> = withContext(Dispatchers.IO) {
        roots.requireSameLibrary(expectedRoot)
        dao.dueOpenJobs(KIND_SOURCES, System.currentTimeMillis()).filter { it.libraryId == expectedRoot.libraryId }
    }
    suspend fun nextAttemptAt(expectedRoot: RootHandle, jobId: String): Long? = withContext(Dispatchers.IO) {
        roots.requireSameLibrary(expectedRoot)
        dao.findJobByIdForLibrary(jobId, expectedRoot.libraryId)?.takeIf { it.kind == KIND_SOURCES }?.nextAttemptAtEpochMs
    }
    suspend fun retry(expectedRoot: RootAuthority, jobId: String): Result<Unit> = withContext(Dispatchers.IO) {
        suspendResult {
            val first = checkNotNull(dao.findJobByIdForLibrary(jobId, expectedRoot.libraryId))
            songWorkCoordinator.withSong(first.songId) {
                songWorkCoordinator.preparationLane.withLock {
                    val root = roots.captureHandle()?.takeIf { it.authority() == expectedRoot } ?: error("LIBRARY_CHANGED")
                    val job = checkNotNull(dao.findJobByIdForLibrary(jobId, expectedRoot.libraryId))
                    check(job.kind == KIND_SOURCES && job.state == STATE_FAILED) { "This request cannot be resubmitted safely" }
                    var authority = checkNotNull(readSourceAuthority(root, job.songId))
                    check(authority.state !in setOf("REMOTE_FAILED", "DISCARDED")) {
                        "Discard the conclusively failed preparation before starting a new plan"
                    }
                    check(authority.state !in setOf(STATE_SUBMISSION_STARTED, STATE_SUBMISSION_UNKNOWN)) {
                        "MVSEP may have accepted this request; its remote identity must be recovered first"
                    }
                    val source = checkNotNull(canonicalCurrentSource(root, job.songId))
                    check(source.id == authority.sourceVersionId && source.sha256 == authority.sourceSha256) { "SOURCE_AUDIO_CHANGED" }
                    requireSourceOwnerAdmission(root, jobId, job.songId,
                        promotesAcceptedOwner = authority.currentRemoteJobId != null)
                    if (authority.state == "SUBMISSION_REJECTED") authority = saveSourceProgress(root,
                        transitionPortableSourceJob(authority, SourceJobTransition.RETRY_REJECTED, System.currentTimeMillis()))
                    dao.upsertJob(projectSourceJob(authority, job.copy(state = authority.state)).copy(
                        nextAttemptAtEpochMs = null, lastErrorCode = null, lastErrorMessage = null,
                        updatedAtEpochMs = System.currentTimeMillis()))
                }
            }
        }
    }

    suspend fun discardFailedPreparation(expectedRoot: RootAuthority, jobId: String): Result<Unit> = withContext(Dispatchers.IO) {
        suspendResult {
            val first = checkNotNull(dao.findJobByIdForLibrary(jobId, expectedRoot.libraryId))
            songWorkCoordinator.withSong(first.songId) {
                songWorkCoordinator.preparationLane.withLock {
                    val root = roots.captureHandle()?.takeIf { it.authority() == expectedRoot } ?: error("LIBRARY_CHANGED")
                    var authority = checkNotNull(readSourceAuthority(root, first.songId))
                    check(authority.jobId == jobId && authority.state in setOf("REMOTE_FAILED", "DISCARDED")) {
                        "Only a conclusively failed remote preparation can be discarded. Unknown or active requests must be recovered."
                    }
                    if (authority.state != "DISCARDED") {
                        val source = checkNotNull(canonicalCurrentSource(root, first.songId))
                        check(source.id == authority.sourceVersionId && source.sha256 == authority.sourceSha256) { "SOURCE_AUDIO_CHANGED" }
                        canonicalPublisher.discardFailedSourceParts(root, first.songId, authority.sourceSha256,
                            (authority.plan.selectedSourceIds + "remaining").mapTo(linkedSetOf()) {
                                sourcePartFileName(authority.generation, authority.plan.selectedSourceIds, it)
                            }, authority.assets.values, setOf(jobId, remainingPublicationOwnerId(jobId)))
                        authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                            SourceJobTransition.DISCARD_FAILED, System.currentTimeMillis()))
                    }
                    dao.upsertJob(projectSourceJob(authority, dao.findJobById(jobId)))
                }
            }
        }
    }
    /** Review an existing uncertain request; status inspection never submits another job. */
    suspend fun reviewRecovery(expectedRoot: RootAuthority, jobId: String, remoteJobId: String): Result<SeparationRecoveryReview> = withContext(Dispatchers.IO) {
        suspendResult {
            val first = checkNotNull(dao.findJobByIdForLibrary(jobId, expectedRoot.libraryId))
            songWorkCoordinator.withSong(first.songId) {
                songWorkCoordinator.preparationLane.withLock {
                    val root = roots.captureHandle()?.takeIf { it.authority() == expectedRoot } ?: error("LIBRARY_CHANGED")
                    val authority = checkNotNull(readSourceAuthority(root, first.songId))
                    requireRecoverableSourceJob(authority, jobId)
                    val pending = if (authority.state == "SUBMISSION_STARTED") transitionPortableSourceJob(authority,
                        SourceJobTransition.MARK_SUBMISSION_UNKNOWN, System.currentTimeMillis()) else authority
                    transitionPortableSourceJob(pending, SourceJobTransition.RECOVER_REMOTE, System.currentTimeMillis(), remoteJobId)
                    val provider = client.status(remoteJobId)
                    roots.requireSameLibrary(root)
                    val source = checkNotNull(canonicalCurrentSource(root, first.songId))
                    check(source.id == authority.sourceVersionId && source.sha256 == authority.sourceSha256) { "SOURCE_AUDIO_CHANGED" }
                    val operation = authority.plan.operations[authority.activeOperationIndex]
                    SeparationRecoveryReview(expectedRoot, jobId, remoteJobId, source.id, source.sha256,
                        authority.generation, authority.revision, authority.activeOperationIndex, authority.planSha256,
                        checkNotNull(dao.findSongById(first.songId)).let { it.displayTitle?.takeIf(String::isNotBlank) ?: it.sourceTitle },
                        "Original (${source.codec.uppercase()})",
                        "${operation.configurationId} / model ${operation.renderId} / ${operation.options.entries.joinToString { "${it.key}=${it.value}" }} / ${authority.plan.selectedSourceIds.joinToString()}",
                        provider.providerSourceName, provider.providerModelName)
                }
            }
        }
    }

    /** Only the reviewed exact source/configuration may adopt this manually supplied remote ID. */
    suspend fun recoverSubmission(review: SeparationRecoveryReview, sourceConfirmed: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        suspendResult {
            check(sourceConfirmed) { "Confirm that this remote job uses the displayed source before recovering it." }
            val expectedRoot = review.root
            val first = checkNotNull(dao.findJobByIdForLibrary(review.jobId, expectedRoot.libraryId))
            songWorkCoordinator.withSong(first.songId) {
                songWorkCoordinator.preparationLane.withLock {
                    val root = roots.captureHandle()?.takeIf { it.authority() == expectedRoot } ?: error("LIBRARY_CHANGED")
                    var authority = checkNotNull(readSourceAuthority(root, first.songId))
                    requireRecoverableSourceJob(authority, review.jobId)
                    requireRecoveryReviewMatches(authority, review)
                    client.status(review.remoteJobId)
                    roots.requireSameLibrary(root)
                    val source = checkNotNull(canonicalCurrentSource(root, first.songId))
                    check(source.id == review.sourceVersionId && source.sha256 == review.sourceSha256) { "SOURCE_AUDIO_CHANGED" }
                    requireSourceOwnerAdmission(root, review.jobId, first.songId)
                    if (authority.state == "SUBMISSION_STARTED") {
                        authority = saveSourceProgress(root, transitionPortableSourceJob(authority,
                            SourceJobTransition.MARK_SUBMISSION_UNKNOWN, System.currentTimeMillis()))
                    }
                    val recovered = transitionPortableSourceJob(authority, SourceJobTransition.RECOVER_REMOTE,
                        System.currentTimeMillis(), review.remoteJobId)
                    saveSourceProgress(root, recovered)
                    dao.upsertJob(projectSourceJob(recovered))
                }
            }
        }
    }

    @Volatile internal var beforeProcessOwnershipForTest: (suspend (ProcessingJobEntity) -> Unit)? = null

    suspend fun process(jobId: String, expectedRoot: RootHandle? = null): SeparationWorkResult = withContext(Dispatchers.IO) {
        val root = expectedRoot ?: roots.captureHandle() ?: return@withContext SeparationWorkResult.TERMINAL_FAILURE
        roots.requireSameLibrary(root)
        val selected = dao.findJobById(jobId) ?: return@withContext SeparationWorkResult.TERMINAL_FAILURE
        check(selected.libraryId == root.libraryId && selected.kind == KIND_SOURCES) { "SEPARATION_JOB_MISMATCH" }
        beforeProcessOwnershipForTest?.invoke(selected)
        songWorkCoordinator.withSong(selected.songId) {
            songWorkCoordinator.preparationLane.withLock {
                roots.requireSameLibrary(root)
                val current = dao.findJobById(jobId) ?: return@withLock SeparationWorkResult.TERMINAL_FAILURE
                check(current.libraryId == root.libraryId && current.kind == KIND_SOURCES &&
                    current.songId == selected.songId && current.sourceVersionId == selected.sourceVersionId &&
                    current.provider == selected.provider && current.modelId == selected.modelId) { "SEPARATION_JOB_MISMATCH" }
                if (current.state == "OBSOLETE") SeparationWorkResult.TERMINAL_FAILURE
                else processSources(current, root)
            }
        }
    }
    suspend fun cleanScratch() = withContext(Dispatchers.IO) { cleanupOrphanScratch() }
    suspend fun cleanupOrphanScratch() = withContext(Dispatchers.IO) {
        songWorkCoordinator.preparationLane.withLock { cleanupOrphanScratchUnderPreparationLane() }
    }
    internal fun cleanupOrphanScratchUnderPreparationLane() {
        val directory = File(applicationContext.noBackupFilesDir, "preparation-active")
        WorkerRetirementQuarantine.requireReleased(directory)
        if (directory.isDirectory) cleanupDisposableSeparationScratch(directory)
    }
    private suspend fun canonicalCurrentSource(
        expectedRoot: RootHandle,
        songId: String
    ): SourceVersionEntity? {
        roots.requireSameLibrary(expectedRoot)
        val song = dao.findSongById(songId) ?: return null
        val source = dao.currentSource(songId) ?: return null
        val canonical = roots.resolveAuthoritativeSongAsset(
            expectedRoot,
            song.youtubeVideoId,
            SelectedLibraryContract.ORIGINAL
        )?.toString()
        return source.takeIf {
            canonicalRootSourceLocation(it.localPath, canonical) != null
        }
    }

    private fun activeScratchDirectory(): File {
        val noBackup = applicationContext.noBackupFilesDir.canonicalFile
        val directory = File(noBackup, "preparation-active").canonicalFile
        check(directory.parentFile == noBackup) { "PREPARATION_CLEANUP_REFUSED" }
        app.stemwise.integration.WorkerRetirementQuarantine.requireReleased(directory)
        check(directory.isDirectory || directory.mkdirs()) { "APP_STORAGE_LOW" }
        return directory
    }

    private suspend fun materializeUploadSource(uri: Uri, expectedSha256: String, codec: String): File {
        check(codec in setOf("opus", "flac")) { "ORIGINAL_CODEC_INVALID" }
        val target = File(activeScratchDirectory(), "upload-original.$codec")
        check(!target.exists() || target.delete()) { "PRIVATE_CLEANUP_FAILED" }
        val available = android.os.StatFs(applicationContext.noBackupFilesDir.absolutePath)
            .availableBytes
        var copied = 0L
        try {
            applicationContext.contentResolver.withCancellableProviderInput(uri) { _, read ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        backgroundWorkCoordinator.checkpoint()
                        val count = read(buffer)
                        if (count < 0) break
                        copied += count
                        requireMvsepUploadByteCount(copied)
                        check(copied + PRIVATE_UPLOAD_RESERVE_BYTES <= available) {
                            "APP_STORAGE_LOW"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("The source audio is no longer available")
            check(copied > 0L && sha256(target) == expectedSha256) {
                "The original audio changed before separation"
            }
            sourceWorkspaceCopiedForTest?.invoke()
            return target
        } catch (error: Throwable) {
            runCatching {
                if (target.exists()) {
                    check(target.delete()) { "PREPARATION_CLEANUP_FAILED" }
                }
            }.exceptionOrNull()?.let { cleanup ->
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    @Volatile internal var sourceWorkspaceCopiedForTest: (() -> Unit)? = null

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                backgroundWorkCoordinator.checkpoint()
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun stableSeparationFailureCode(error: Throwable): String =
        if (error is MvsepSubmissionRejected) "MVSEP_SUBMISSION_REJECTED" else error.message?.takeIf { it.matches(Regex("^[A-Z][A-Z0-9_]+$")) }
            ?: stableSeparationFailureCode(error.message.orEmpty())

    private fun stableSeparationFailureCode(message: String): String = when {
        message.contains("storage", ignoreCase = true) -> "MVSEP_STORAGE_FAILED"
        message.contains("source", ignoreCase = true) -> "MVSEP_SOURCE_FAILED"
        message.contains("token", ignoreCase = true) ||
            message.contains("unauthorized", ignoreCase = true) -> "MVSEP_ACCOUNT_FAILED"
        message.contains("stem", ignoreCase = true) ||
            message.contains("render", ignoreCase = true) -> "MVSEP_OUTPUT_CONTRACT_FAILED"
        else -> "MVSEP_PROCESSING_FAILED"
    }

    companion object {
        internal fun friendlyError(error: Throwable): String {
            val message = error.message.orEmpty()
            return when {
                error is MvsepTransientRemoteFailure ->
                    "MVSEP is temporarily unavailable. Retry from Tasks to continue the same accepted job."
                error is MvsepHttpFailure && error.statusCode in setOf(401, 403) ->
                    "Reconnect MVSEP in System"
                message == SOURCE_REMAINING_REPREPARE_REQUIRED ->
                    "Remove this song's prepared parts in Song info, then prepare them again. The original and enrichment stay in your library."
                message == "SOURCE_AUDIO_TOO_LARGE" ->
                    "MVSEP's free service accepts files up to 100 MB. This original was kept at its full quality."
                message == "MVSEP_FREE_DURATION_LIMIT" ->
                    "MVSEP's free service accepts songs up to 10 minutes. This original was kept in full."
                message == "SOURCE_TIMELINE_MISMATCH" ->
                    "Returned audio does not match the song. The parts were not activated"
                message.contains("timed out", ignoreCase = true) ||
                    message.contains("resolve", ignoreCase = true) ||
                    message.contains("connect", ignoreCase = true) ->
                    "Could not reach MVSEP"
                message.contains("storage", ignoreCase = true) -> "Not enough storage to prepare these parts"
                message.contains("source", ignoreCase = true) && !message.contains("selection", ignoreCase = true) ->
                    "The original or a prepared part changed or is unavailable"
                message.contains("token", ignoreCase = true) || message.contains("unauthorized", ignoreCase = true) ->
                    "Reconnect MVSEP in System"
                message.contains("timeline", ignoreCase = true) || message.contains("format", ignoreCase = true) ->
                    "Returned audio does not match the song. The parts were not activated"
                message.contains("role", ignoreCase = true) || message.contains("model", ignoreCase = true) ||
                    message.contains("configuration", ignoreCase = true) || message.contains("render", ignoreCase = true) ->
                    "Some selected parts are unavailable. Choose different parts"
                else -> "Could not prepare the selected parts. Retry from Tasks"
            }
        }

        const val KIND_SOURCES = "SOURCE_SELECTION"
        const val PROVIDER = "MVSEP"
        const val STATE_CREATED = "CREATED"
        const val STATE_SUBMISSION_STARTED = "SUBMISSION_STARTED"
        const val STATE_PROCESSING = "PROCESSING"
        const val STATE_SUBMISSION_UNKNOWN = "SUBMISSION_UNKNOWN"
        const val STATE_PUBLISHING = "PUBLISHING"
        const val STATE_COMPLETE = "COMPLETE"
        const val STATE_FAILED = "FAILED"
        private const val POLL_INTERVAL_MS = 45_000L
        private const val RETRY_INTERVAL_MS = 60_000L
        private const val PRIVATE_UPLOAD_RESERVE_BYTES = 32L * 1024L * 1024L
    }
}
/** Upload/stem materialization is reproducible and never becomes durable job authority. */
internal fun cleanupDisposableSeparationScratch(directory: File, preserveOriginal: Boolean = false) {
    if (app.stemwise.integration.WorkerRetirementQuarantine.failureFor(directory) != null) return
    check(checkedPrivateChildren(directory, "PREPARATION_CLEANUP_FAILED").all {
        it.isFile && (it.name in PRIVATE_PREPARATION_SCRATCH_NAMES ||
            app.stemwise.data.isWorkingMediaCleanupRefusalMarker(it))
    }) { "PREPARATION_CLEANUP_UNKNOWN_ENTRY" }
    app.stemwise.data.cleanupExactPrivateChildren(directory, "PREPARATION_CLEANUP_FAILED",
        preserve = if (preserveOriginal) setOf(File(directory, "upload-original.opus"),
            File(directory, "upload-original.flac")) else emptySet()) {
        it.isFile && it.name in PRIVATE_PREPARATION_SCRATCH_NAMES
    }
}

internal const val MAX_PRIVATE_PREPARATION_ASSET_BYTES = 3L * 1024L * 1024L * 1024L
internal fun remainingPublicationOwnerId(jobId: String): String = "$jobId.$NATIVE_ORIGINAL_48K_REMAINING_PRODUCER"
internal val PRIVATE_PREPARATION_SCRATCH_NAMES = setOf(
    "upload-original.opus",
    "upload-original.flac",
    "active-stem.flac",
    "active-stem.flac.part",
    "active-part.flac",
    "active-part.flac.part",
    "active-remaining.wv",
    "source-manifest.json",
    CANONICAL_ORIGINAL_PCM_FILE,
    "active-remaining.wv.part"
) + (1..StemSourceCatalogue.MAX_TARGETS).map { "remaining-input-$it.flac" }

internal fun requireBoundedRemainingScratch(directory: File) {
    val files = checkedPrivateChildren(directory, "PREPARATION_CLEANUP_FAILED")
    check(files.all { it.isFile && it.name in PRIVATE_PREPARATION_SCRATCH_NAMES &&
        it.name != "active-stem.flac" && it.name != "active-stem.flac.part" }) { "PREPARATION_SCRATCH_OWNERSHIP_INVALID" }
    check(files.sumOf(File::length) <= MAX_PRIVATE_PREPARATION_ASSET_BYTES) { "PREPARATION_SCRATCH_PEAK_EXCEEDED" }
}

internal fun requireSourcePreparationResources(audibleFrames: Long, targets: Int, privateAvailableBytes: Long) {
    require(targets in 1..StemSourceCatalogue.MAX_TARGETS) { "SOURCE_SELECTION_INVALID" }
    check(audibleFrames in 1..48_000L * 600L) { "MVSEP_FREE_DURATION_LIMIT" }
    val seconds = (audibleFrames + 47_999L) / 48_000L
    // FLAC may be incompressible. Budget PCM16 plus frame overhead, Original upload,
    // all retained targets, native Float32 Original, residual, and a reserve before submission.
    val operands = Math.multiplyExact(seconds, 196_608L * targets + 2L * 384_000L + 64_000L) +
        (targets + 1L) * 1024 * 1024
    val conversion = Math.multiplyExact(seconds, 2L * 196_608L) + 2L * 1024 * 1024
    check(maxOf(operands, conversion) <= MAX_PRIVATE_PREPARATION_ASSET_BYTES &&
        privateAvailableBytes >= maxOf(operands, conversion) + 512L * 1024 * 1024) { "APP_STORAGE_LOW" }
}

internal fun requireSameSourceTimeline(samples: Long, sampleRate: Int, sourceFrames: Long) {
    check(samples > 0 && sampleRate > 0 && sourceFrames > 0) { "SOURCE_TIMELINE_MISMATCH" }
    check(abs(Math.multiplyExact(samples, 48_000L) - Math.multiplyExact(sourceFrames, sampleRate.toLong())) <= sampleRate) {
        "SOURCE_TIMELINE_MISMATCH"
    }
}

internal fun requireSourceOperationFiles(operation: MvsepSourceOperation,
    files: List<MvsepRemoteFile>): Map<String, MvsepRemoteFile> {
    val grouped = files.groupBy { StemRoleContract.normalize(it.label) }
    check(files.size == operation.expectedRoles.size && grouped.values.all { it.size == 1 } &&
        grouped.keys == operation.expectedRoles.map(StemRoleContract::normalize).toSet() &&
        files.map { it.url }.distinct().size == files.size) { "MVSEP_ROLE_MISMATCH" }
    return operation.outputBindings.mapValues { (_, role) -> grouped.getValue(StemRoleContract.normalize(role)).single() }
}

/** One selected part is materialized at a time; conversion owns only its input/output pair. */
internal fun requireBoundedPrivatePreparationScratch(
    entries: Collection<Pair<String, Long>>
): Long {
    val originals = entries.filter { it.first in setOf("upload-original.opus", "upload-original.flac") }
    val parts = entries.filterNot { it in originals }
    check(originals.size <= 1 && parts.size <= 2) { "PREPARATION_SCRATCH_PEAK_EXCEEDED" }
    entries.forEach { (name, bytes) ->
        check(name in PRIVATE_PREPARATION_SCRATCH_NAMES && bytes in 0..MAX_PRIVATE_PREPARATION_ASSET_BYTES) {
            "PREPARATION_SCRATCH_OWNERSHIP_INVALID"
        }
    }
    if (parts.size == 2) {
        check(parts.mapTo(mutableSetOf(), Pair<String, Long>::first) == setOf(
            "active-stem.flac",
            "active-part.flac.part"
        )) { "PREPARATION_SCRATCH_PEAK_EXCEEDED" }
    }
    return entries.sumOf(Pair<String, Long>::second).also { total ->
        check(total <= MAX_PRIVATE_PREPARATION_ASSET_BYTES) {
            "PREPARATION_SCRATCH_PEAK_EXCEEDED"
        }
    }
}

internal fun requireBoundedPrivatePreparationScratch(directory: File): Long {
    val canonicalDirectory = directory.canonicalFile
    check(canonicalDirectory.isDirectory) { "PREPARATION_SCRATCH_OWNERSHIP_INVALID" }
    val entries = canonicalDirectory.listFiles()
        ?: error("PREPARATION_SCRATCH_OWNERSHIP_INVALID")
    check(entries.all { it.isFile && it.canonicalFile.parentFile == canonicalDirectory }) {
        "PREPARATION_SCRATCH_OWNERSHIP_INVALID"
    }
    return requireBoundedPrivatePreparationScratch(entries.map { it.name to it.length() })
}

internal class MvsepTransientRemoteFailure(cause: Throwable) :
    IllegalStateException("MVSEP_TEMPORARILY_UNAVAILABLE", cause)

/** Only wrap acknowledged status/download reads; a submission failure must never enter this path. */
internal suspend fun <T> withAcknowledgedMvsepRead(read: suspend () -> T): T = try {
    read()
} catch (error: Throwable) {
    val transient = when (error) {
        is MvsepHttpFailure -> error.statusCode in setOf(408, 425, 429, 500, 502, 503, 504)
        is java.net.SocketTimeoutException, is java.net.SocketException,
        is java.net.UnknownHostException, is java.io.EOFException -> true
        else -> false
    }
    if (transient) throw MvsepTransientRemoteFailure(error)
    throw error
}

// This Room-only scheduling marker keeps read retries separate from durable paid-submission attempts.
private val MVSEP_REMOTE_RETRY_CODES = (1..4).map { "MVSEP_REMOTE_RETRY_$it" }

internal fun scheduleAcknowledgedMvsepRetry(
    job: ProcessingJobEntity,
    error: Throwable,
    nowEpochMs: Long
): ProcessingJobEntity? {
    if (error !is MvsepTransientRemoteFailure || job.kind != SeparationRepository.KIND_SOURCES ||
        job.provider != SeparationRepository.PROVIDER || job.remoteJobId.isNullOrBlank() ||
        job.state !in setOf("PROCESSING", "DOWNLOADING")) return null
    val completedRetries = MVSEP_REMOTE_RETRY_CODES.indexOf(job.lastErrorCode) + 1
    if (completedRetries == MVSEP_REMOTE_RETRY_CODES.size) return null
    return job.copy(
        nextAttemptAtEpochMs = Math.addExact(nowEpochMs, 30_000L shl completedRetries),
        lastErrorCode = MVSEP_REMOTE_RETRY_CODES[completedRetries],
        lastErrorMessage = "MVSEP is temporarily unavailable. Retrying the same accepted job",
        updatedAtEpochMs = nowEpochMs
    )
}

internal data class Flac16Facts(
    val sampleRateHz: Int,
    val channelCount: Int,
    val totalSamples: Long,
    val durationMs: Long
)

/** Provider FLAC is checked before either byte-preserving publication or rate conversion. */
internal fun inspectFlac16(file: File): Flac16Facts {
    val facts = try { inspectPreparedSourceAudio(file) } catch (error: Exception) {
        throw IllegalStateException("MVSEP_OUTPUT_FORMAT_INVALID", error)
    }
    check(!facts.isFloat && facts.bitsPerSample == 16) { "MVSEP_OUTPUT_FORMAT_INVALID" }
    return Flac16Facts(facts.sampleRate, facts.channels, facts.audibleFrames,
        facts.audibleFrames * 1_000L / facts.sampleRate)
}

internal suspend fun mvsepSourceSubmissionCredential(
    account: MvsepAccountAccess,
    client: MvsepServiceClient
): MvsepCredential? {
    if (account.state.value.status != MvsepConnectionStatus.READY && account.validate().isFailure)
        return null
    val credential = account.credential() ?: return null
    client.disablePremium(credential.token)
    account.validate(credential).getOrThrow()
    val refreshed = account.credential() ?: error("MVSEP_DISCONNECTED")
    check(refreshed.token == credential.token && refreshed.generation >= credential.generation &&
        !account.state.value.premiumEnabled && account.isCurrent(refreshed)) { "MVSEP_DISCONNECTED" }
    return refreshed
}

/** A queued worker receives only the exact row read back after durable persistence. */
internal suspend fun persistSeparationQueueHandoff(
    job: ProcessingJobEntity,
    hash: String,
    persist: suspend (ProcessingJobEntity) -> Unit,
    reopen: suspend (String) -> ProcessingJobEntity?
): SeparationQueueHandoff {
    persist(job)
    val reopened = checkNotNull(reopen(job.id)) { "SEPARATION_HANDOFF_PERSISTENCE_FAILED" }
    check(reopened == job) { "SEPARATION_JOB_MISMATCH" }
    return SeparationQueueHandoff(
        reopened.id, checkNotNull(reopened.libraryId), reopened.songId, reopened.sourceVersionId,
        hash, reopened.state, reopened.state !in setOf(SeparationRepository.STATE_COMPLETE,
            SeparationRepository.STATE_FAILED, SeparationRepository.STATE_SUBMISSION_UNKNOWN)
    )
}

internal fun projectSourceJob(authority: PortableSourceJobAuthority,
    previous: ProcessingJobEntity? = null): ProcessingJobEntity {
    previous?.let {
        check(it.id == authority.jobId && it.kind == SeparationRepository.KIND_SOURCES && it.provider == SeparationRepository.PROVIDER &&
            it.songId == authority.videoId && it.sourceVersionId == authority.sourceVersionId &&
            it.libraryId == authority.libraryId && it.modelId == "source-plan:${authority.planSha256}") {
            PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
        }
    }
    val completed = authority.state in setOf(SeparationRepository.STATE_COMPLETE, "DISCARDED")
    return ProcessingJobEntity(authority.jobId, authority.videoId, authority.sourceVersionId,
        SeparationRepository.KIND_SOURCES, SeparationRepository.PROVIDER, "source-plan:${authority.planSha256}",
        when {
            authority.state == "DISCARDED" -> "OBSOLETE"
            authority.state in setOf("SUBMISSION_REJECTED", "REMOTE_FAILED") || !completed && previous?.state == SeparationRepository.STATE_FAILED -> SeparationRepository.STATE_FAILED
            else -> authority.state
        },
        authority.currentRemoteJobId, authority.attempts,
        previous?.nextAttemptAtEpochMs.takeUnless { completed || authority.state == "REMOTE_FAILED" },
        if (authority.state == "REMOTE_FAILED") "MVSEP_REMOTE_FAILED" else previous?.lastErrorCode.takeUnless { completed },
        previous?.lastErrorMessage.takeUnless { completed }
            ?: when (authority.state) {
                "SUBMISSION_REJECTED" -> "MVSEP rejected this request. Correct the account or selected parts, then retry."
                "REMOTE_FAILED" -> "MVSEP reported that this preparation failed. Discard it to start a fresh plan; Original is preserved."
                else -> null
            }, authority.updatedAtEpochMs, authority.createdAtEpochMs, authority.libraryId)
}

/** The live submission boundary consumes durable authority, so recovered accepted work cannot re-enter upload. */
internal suspend inline fun withRequiredSourceSubmission(authority: PortableSourceJobAuthority, submit: () -> Unit) {
    if (authority.requiresSubmission) submit()
}

/** Submission authority is entered only after every local step has succeeded. */
internal suspend fun <Upload, Result> submitPreparedSourceUpload(
    materialize: suspend () -> Upload,
    requireResources: suspend (Upload) -> Unit,
    ensureEncoderReady: suspend () -> Unit,
    submit: suspend (Upload) -> Result
): Pair<Upload, Result> {
    val upload = materialize()
    requireResources(upload)
    ensureEncoderReady()
    return upload to submit(upload)
}
