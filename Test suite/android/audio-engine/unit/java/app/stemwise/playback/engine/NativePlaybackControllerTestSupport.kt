package app.stemwise.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal open class NativePlaybackControllerTestSupport {

    protected fun finiteStemTailFixture(song: String): Fixture {
        val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request(song).copy(durationMs = 1_000L, playbackEndMs = 1_000L,
            startPositionMs = 985L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 47_280L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = listOf("vocals", "remaining").map { role ->
            EngineStemLane(role, role, if (role == "remaining") "/remaining.wav" else "/vocals.flac",
                "sha-$song-$role")
        }
        assertTrue(fixture.controller.updateDesired(token) { previous -> previous.copy(
            sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
            stemRevision = "$song-parts", stemControls = stems.associate { it.id to EngineStemControl() }) })
        fixture.scheduler.runNext()
        assertEquals(NativeRuntimeSourceMode.ORIGINAL, fixture.environment.snapshots.last().controls.sourceMode)
        assertEquals(47_280L, requireNotNull(fixture.controller.diagnosticsSnapshot()
            .controllerStemReadiness).anchorFrame48k)
        fixture.environment.decoders.forEach { decoder ->
            assertEquals(if (decoder.laneIndex == 0) 984_000L else 985_000L, decoder.spec.initialPositionUs)
        }
        val snapshot = fixture.environment.snapshots.last()
        assertEquals(47_280L, snapshot.banks[requireNotNull(snapshot.selectedBank).nativeCode].firstTimelineFrame)
        fixture.environment.decoders.forEach { decoder ->
            decoder.setAcceptedCoverage(47_280L, 48_000L)
            decoder.exactEndOfStreamFrame = 48_000L
        }
        // Native reports logical renderability through accepted EOS, not just the shorter raw
        // reservoir. Its complete horizon proof remains required even for a finite tail.
        fixture.environment.setNativeStemRenderReadiness(owner, 47_280L,
            readyFrames = fixture.environment.requiredNativeStemRenderAheadFrames,
            rawEndTimelineFrameExclusive = 48_000L)
        return fixture
    }

    protected class Fixture(
        stuckDecoders: Boolean = false,
        requireNativeStemReadiness: Boolean = false,
        currentSongCacheSealedByDefault: Boolean = true,
        onEvent: ((NativeEngineControllerEvent) -> Unit)? = null
    ) {
        val scheduler = ManualScheduler()
        val environment = FakeEnvironment(
            stuckDecoders,
            requireNativeStemReadiness,
            currentSongCacheSealedByDefault
        )
        val events = mutableListOf<NativeEngineControllerEvent>()
        val controller = NativePlaybackEngineController(
            environment,
            scheduler,
            NativeEngineControllerListener { event -> events.add(event); onEvent?.invoke(event) }
        )
        private val loopRevisions = mutableMapOf<Long, Long>()

        fun setLoop(
            token: EngineSelectionToken,
            loop: EngineLoopConfig?,
            inputEventAtMonotonicMicros: Long = 0L,
            heldBGestureId: Long = 0L,
            capturedBMs: Long = -1L,
            captureReferencePositionMs: Long = -1L
        ): Boolean {
            val revision = (loopRevisions[token.value] ?: 0L) + 1L
            return controller.updateExplicitLoop(
                EngineExplicitLoopState(
                    token,
                    revision,
                    loop,
                    requireNotNull(environment.lastSelected()),
                    inputEventAtMonotonicMicros,
                    heldBGestureId,
                    capturedBMs,
                    captureReferencePositionMs
                )
            ).also { accepted ->
                if (accepted) loopRevisions[token.value] = revision
            }
        }

        fun reclassifyLoopForSeek(
            token: EngineSelectionToken,
            loop: EngineLoopConfig
        ): Boolean {
            val revision = (loopRevisions[token.value] ?: 0L) + 1L
            return controller.updateExplicitLoopForTransportSeek(
                EngineExplicitLoopState(
                    token,
                    revision,
                    loop,
                    requireNotNull(environment.lastSelected())
                )
            ).also { accepted ->
                if (accepted) loopRevisions[token.value] = revision
            }
        }

        fun ownerPublications(): List<NativeEngineOwnerPublication> = events.mapNotNull {
            (it as? NativeEngineControllerEvent.OwnerPublished)?.publication
        }

        fun presentLatest(timelineFrame: Long = 0L) {
            environment.present(requireNotNull(environment.lastSelected()), timelineFrame)
            scheduler.runNext()
        }

        fun presentLatestWithAudibleControl(timelineFrame: Long = 0L) {
            presentLatest(timelineFrame)
            val owner = requireNotNull(environment.lastSelected())
            val snapshot = environment.snapshots.last()
            environment.presentAudibleControl(
                owner = owner,
                controlRevision = snapshot.controls.revision,
                sourceMode = snapshot.controls.sourceMode
            )
            controller.signalNativeProgress()
            scheduler.runNext()
        }
    }

    protected class ManualScheduler : NativeEngineActorScheduler {
        private data class Task(
            val dueMs: Long,
            val order: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
            var started: Boolean = false
        )

        private val tasks = ArrayDeque<Task>()
        private val taskGate = Any()
        val scheduledDelays = mutableListOf<Long>()
        var cancelledDelayedTasks = 0
        private var elapsedMs = 0L
        private var nextOrder = 0L
        override fun execute(task: () -> Unit) {
            synchronized(taskGate) { tasks.addLast(Task(elapsedMs, nextOrder++, task)) }
        }

        override fun schedule(delayMs: Long, task: () -> Unit): NativeEngineScheduledTask {
            require(delayMs >= 0L)
            val scheduled = synchronized(taskGate) {
                scheduledDelays += delayMs
                Task(Math.addExact(elapsedMs, delayMs), nextOrder++, task).also(tasks::addLast)
            }
            return NativeEngineScheduledTask {
                synchronized(taskGate) {
                    if (scheduled.cancelled || scheduled.started) {
                        false
                    } else {
                        scheduled.cancelled = true
                        cancelledDelayedTasks++
                        true
                    }
                }
            }
        }

        fun runNext() {
            val task = synchronized(taskGate) {
                val next = tasks.filterNot { it.cancelled }.minWithOrNull(compareBy(Task::dueMs, Task::order))
                assertNotNull("No controller task was scheduled", next)
                requireNotNull(next).also {
                    tasks.remove(it)
                    it.started = true
                    elapsedMs = maxOf(elapsedMs, it.dueMs)
                }
            }
            task.action.invoke()
        }

        /** Mirrors ScheduledThreadPoolExecutor: ready execute() work precedes a future deadline. */
        fun runNextImmediate() {
            val task = synchronized(taskGate) {
                val immediate = tasks.filter { !it.cancelled && it.dueMs <= elapsedMs }
                    .minWithOrNull(compareBy(Task::dueMs, Task::order))
                assertNotNull("No immediate controller task was scheduled", immediate)
                requireNotNull(immediate).also {
                    tasks.remove(it)
                    it.started = true
                }
            }
            task.action.invoke()
        }

        fun markNextDelayedTaskStarted() {
            val task = synchronized(taskGate) {
                tasks.filter { !it.cancelled && it.dueMs > elapsedMs }
                    .minWithOrNull(compareBy(Task::dueMs, Task::order))
            }
            assertNotNull("No delayed controller task was scheduled", task)
            requireNotNull(task).started = true
        }

        fun runSteps(count: Int) = repeat(count) { runNext() }
        fun advanceTimeBy(durationMs: Long) {
            require(durationMs >= 0L)
            synchronized(taskGate) { elapsedMs += durationMs }
        }
        fun runUntil(maximumSteps: Int = 32, condition: () -> Boolean) {
            repeat(maximumSteps) {
                if (condition()) return
                runNext()
            }
            assertTrue("Condition did not settle within $maximumSteps actor steps", condition())
        }
        fun activeTaskCount(): Int = synchronized(taskGate) { tasks.count { !it.cancelled } }
        override fun monotonicTimeMs(): Long = synchronized(taskGate) { elapsedMs }
        override fun close() = Unit
    }

    protected data class PublishedStemGainPreview(
        val publicationSequence: Long,
        val coversOfferSequence: Long,
        val ownership: NativeRuntimeOwnership,
        val sourceIntentRevision: Long,
        val stemIntentRevision: Long,
        val canonicalControlFloorRevision: Long,
        val activeMask: Int,
        val gains: FloatArray
    )

    protected data class PublishedDirectLoopPolicy(
        val revision: Long,
        val ownership: NativeRuntimeOwnership,
        val enabled: Boolean,
        val aTimelineFrame: Long,
        val bTimelineFrameExclusive: Long,
        val capturedBTimelineFrameExclusive: Long,
        val inputEventAtMonotonicMicros: Long,
        val heldBGestureId: Long,
        val boundaryArmedAtPublication: Boolean,
        val boundaryCrossedAtPublication: Boolean,
        val captureReferenceTimelineFrame: Long
    )

    protected class FakeEnvironment(
        private val stuckDecoders: Boolean = false,
        requireNativeStemReadiness: Boolean = false,
        private val currentSongCacheSealedByDefault: Boolean = true
    ) : NativeEngineEnvironment {
        val detailedCaptureChanges = mutableListOf<Boolean>()
        override fun setDetailedDiagnosticCapture(enabled: Boolean) { detailedCaptureChanges += enabled }
        var canonicalPcmIo: NativeRuntimeCanonicalPcmIoDiagnostics? = null
        var detailedRuntimeReads = 0
        var renderReadinessDiagnosticReads = 0
        var outputDiagnosticReads = 0
        var decoderLeaseDiagnosticReads = 0
        var observedOutput: NativeAudioDiagnostics? = null
        var sealCacheAfterNextCheck = false
        override val requiredNativeStemRenderAheadFrames: Long = if (requireNativeStemReadiness) {
            maxOf(
                NATIVE_ENGINE_MINIMUM_STEM_RENDER_AHEAD_FRAMES,
                NativeRuntimeConfiguration().minimumOutputReserveMs.toLong() *
                    NATIVE_ENGINE_SAMPLE_RATE_HZ / 1_000L
            )
        } else {
            0L
        }
        override var outputGeneration: Long = 1L
        var sourceTransitionNextFrame: Long? = null
        var sourceTransitionReady = true
        val sourceTransitionRequests = mutableListOf<Pair<NativeRuntimeOwnership, NativeRuntimeOwnership>>()
        val sourceTransitionCancellations = mutableListOf<NativeRuntimeOwnership>()
        override fun cancelSourceTransition(target: NativeRuntimeOwnership) { sourceTransitionCancellations += target }
        override fun prepareSourceTransition(outgoing: NativeRuntimeOwnership, target: NativeRuntimeOwnership,
            pausedFallbackFrame: Long?): Long? {
            sourceTransitionRequests += outgoing to target
            if (!sourceTransitionReady) return null
            return sourceTransitionNextFrame ?: position?.takeIf { it.ownership == outgoing }
                ?.lastTimelineFrame?.let { it + 1L } ?: pausedFallbackFrame
        }
        // Ordinary publications obey the production generation fence; fault fixtures must opt out explicitly.
        var enforceNativeOutputGeneration = true
        private var acceptedNativeSnapshot: NativeRuntimeCommandSnapshot? = null
        val snapshots = mutableListOf<NativeRuntimeCommandSnapshot>()
        val appliedBankIntentRevisionOverrides = mutableMapOf<NativeRuntimeBank, Long>()
        val stemGainPreviews = mutableListOf<PublishedStemGainPreview>()
        val beatGridUpdates = mutableListOf<NativeRuntimeBeatGridUpdate>()
        var beatGridPublishStatus = NativeRuntimePublishStatus.ACCEPTED
        val managedLoopIntents = mutableListOf<NativeRuntimeManagedLoopIntent>()
        var directLoopPolicyStatus = NativeRuntimePublishStatus.ACCEPTED
        var directLoopPolicyPublicationCount = 0
        val directLoopPolicies = mutableListOf<PublishedDirectLoopPolicy>()
        val managedLoopReady = mutableListOf<NativeRuntimeManagedLoopIngressReady>()
        val loopPreparationSpecs = mutableListOf<NativeEngineLoopPreparationSpec>()
        var exposePreparedLoopRange = false
        var loopPreparationBeginStatus = NativeRuntimeLoopHeadBeginStatus.ACCEPTED
        var enforceLoopHeadRevisionOrder = false
        private var lastAcceptedLoopHeadRevision = 0L
        val abortedLoopRevisions = mutableListOf<Long>()
        val operations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val decoders = mutableListOf<FakeDecoder>()
        var newDecodersExposePcmHead = true
        var maximumOpenDecoderCount = 0
        var openedDecoderCount = 0
        val decoderOpenAttempts = mutableListOf<Pair<NativeSongBankRole, String>>()
        var releasedDecoderCount = 0
        var holdLoopPreparation = false
        var loopPreparationAcceptedSourceFrame = 0L
        var loopPreparationCancelRequestCount = 0
        var onAbortLoopPreparationOnce: (() -> Unit)? = null
        var loopPreparationJoinWatchdog = false
        var loopPreparationPendingJoinPolls = 0
        var loopPreparationCloseGate: CountDownLatch? = null
        val loopPreparationCloseEntered = CountDownLatch(1)
        var failLoopPreparationCount = 0
        var loopIngressRequest: NativeRuntimeLoopIngressRequest? = null
        var reopenOutputSucceeds = true
        var outputRetirementPendingPolls = 0
        var recreateRuntimeSucceeds = true
        var outputRecoveryRequired = false
        var quiesceOutputCallCount = 0
        var quiesceOutputReady = true
        override var outputWarmPauseGraceMs = 1_500L
        val outputSessionSuspensions = mutableListOf<Boolean>()
        override fun setOutputSessionSuspended(suspended: Boolean) {
            outputSessionSuspensions += suspended
        }
        var throwDiagnosticsOnce = false
        var onNextControlProgress: (() -> Unit)? = null
        var immediateSilenceFailure: Throwable? = null
        var runtimeJoinFailure: Throwable? = null
        var outputRetirementFailure: Throwable? = null
        var onImmediateSilence: (() -> Unit)? = null
        var onNextAcceptedSnapshot: (() -> Unit)? = null
        var holdUnassignmentAcknowledgement = false
        val heldUnassignmentAcknowledgementBanks = mutableSetOf<NativeRuntimeBank>()
        var holdOutputGenerationObservationOnPublish = false
        private var cancellationGroup = emptySet<FakeDecoder>()
        private var onFirstCancellationRequested: (() -> Unit)? = null
        var manualSelectionGateArmed = false
            private set
        val manualSelectionGateAuthorizations = mutableListOf<Long>()
        val openLoopPreparationCount: Int get() = if (loopPreparationOpen) 1 else 0
        val loopCacheCommitted: Boolean
            get() = latestManagedLoopIntent?.cacheKey?.loopRevision in committedCacheRevisions
        val latestPreparationIntent: NativeRuntimeManagedLoopIntent?
            get() = loopPreparationSpecs.lastOrNull()?.let { preparationIntents[it.loopRevision] }
        private var running = false
        private var presentation: NativeRuntimePresentationEvidence? = null
        private var position: NativeRuntimePresentedPosition? = null
        private var end: NativeRuntimeAudibleEndEvent? = null
        private var audibleControl: NativeRuntimeAudibleControlEvidence? = null
        private var audibleControlProgressListener:
            ((NativeRuntimeAudibleControlEvidence) -> Unit)? = null
        private var ownership = NativeRuntimeOwnershipProgress(null, null, null, 0L)
        private var automaticSuccessor = NativeRuntimeAutomaticSuccessorDiagnostics(
            phase = NativeRuntimeAutomaticSuccessorPhase.IDLE,
            cancellationStatus = NativeRuntimeDrainCancelStatus.NOT_REQUESTED,
            revision = 0L,
            transitionRevision = 0L,
            outgoingOwnership = null,
            successorOwnership = null,
            successorQueuedFrames = 0L,
            firstFingerprintSequence = 0L,
            cancellationDesired = false,
            successorEndObserved = false
        )
        private var presentationSequence = 0L
        private var positionSequence = 0L
        private var endSequence = 0L
        private var audibleControlSequence = 0L
        private var failOpenLaneOnce: Int? = null
        private val permanentlyFailedOpenLocations = mutableSetOf<String>()
        private var latestManagedLoopIntent: NativeRuntimeManagedLoopIntent? = null
        private val preparationIntents = mutableMapOf<Long, NativeRuntimeManagedLoopIntent>()
        private val committedCacheRevisions = mutableSetOf<Long>()
        private var loopPreparationOpen = false
        private var renderReadinessSequence = 0L
        private var renderReadiness: NativeRuntimeRenderReadinessDiagnostics? = null
        var fixedLoopHeadReady = true
        var onNextReadinessRequest: (() -> Unit)? = null
        var residentCacheOwner: NativeRuntimeOwnership? = null
        var residentCacheRange: LongRange? = null
        var holdCacheRangeProof = false
        var residentCacheLaneMask = NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK
        val cacheRangeQueries = mutableListOf<Pair<Long, Long>>()
        private val sealedCurrentSongCacheLaneMasks =
            mutableMapOf<NativeRuntimeOwnership, Int>()
        var currentSongCacheSealCheckCount = 0
            private set

        var outputPreparation = NativeOutputPreparationState.READY
        override fun prepareOutput(): NativeOutputPreparationState {
            operations += "prepare-output"
            return outputPreparation
        }

        var runtimeStartFailures = 0
        override fun startRuntime(): Boolean {
            operations += "start-runtime"
            if (runtimeStartFailures > 0) { runtimeStartFailures--; return false }
            running = true
            return true
        }

        override fun publish(snapshot: NativeRuntimeCommandSnapshot): NativeRuntimePublishStatus {
            check(running)
            val previous = acceptedNativeSnapshot
            if (enforceNativeOutputGeneration && previous != null &&
                (snapshot.outputGeneration < previous.outputGeneration ||
                    (snapshot.selectedOwnership != previous.selectedOwnership &&
                        snapshot.outputGeneration <= previous.outputGeneration))
            ) return NativeRuntimePublishStatus.INVALID_SNAPSHOT
            acceptedNativeSnapshot = snapshot
            snapshots += snapshot
            val token = snapshot.selectedOwnership?.selectionToken ?: 0L
            operations += "publish:$token:${snapshot.outputGeneration}:${snapshot.playing}:" +
                snapshot.controls.sourceMode
            if (!holdOutputGenerationObservationOnPublish) {
                outputGeneration = maxOf(outputGeneration, snapshot.outputGeneration)
            }
            val pausedAdoption = automaticSuccessor.takeIf {
                it.phase ==
                    NativeRuntimeAutomaticSuccessorPhase.APPLIED_PAUSED_AWAITING_SELECTION &&
                    it.cancellationStatus == NativeRuntimeDrainCancelStatus.SUCCESSOR_ACTIVE &&
                    snapshot.selectedOwnership == it.outgoingOwnership
            }
            ownership = if (pausedAdoption != null) {
                val successor = requireNotNull(pausedAdoption.successorOwnership)
                NativeRuntimeOwnershipProgress(successor, successor, successor, 1L)
            } else {
                ownership.copy(requested = snapshot.selectedOwnership)
            }
            onNextAcceptedSnapshot?.also { onNextAcceptedSnapshot = null }?.invoke()
            return NativeRuntimePublishStatus.ACCEPTED
        }

        override fun publishStemGainPreview(
            publicationSequence: Long,
            coversOfferSequence: Long,
            ownership: NativeRuntimeOwnership,
            sourceIntentRevision: Long,
            stemIntentRevision: Long,
            canonicalControlFloorRevision: Long,
            activeMask: Int,
            gains: FloatArray
        ): NativeRuntimePublishStatus {
            check(running)
            if (publicationSequence <= 0L || coversOfferSequence <= 0L) {
                return NativeRuntimePublishStatus.INVALID_SNAPSHOT
            }
            stemGainPreviews += PublishedStemGainPreview(
                publicationSequence,
                coversOfferSequence,
                ownership,
                sourceIntentRevision,
                stemIntentRevision,
                canonicalControlFloorRevision,
                activeMask,
                gains.copyOf()
            )
            return NativeRuntimePublishStatus.ACCEPTED
        }

        override fun publishBeatGrid(
            update: NativeRuntimeBeatGridUpdate
        ): NativeRuntimePublishStatus {
            beatGridUpdates += update
            return beatGridPublishStatus
        }

        override fun publishManagedLoopIntent(
            intent: NativeRuntimeManagedLoopIntent
        ): NativeRuntimePublishStatus {
            if (!running) return NativeRuntimePublishStatus.STOPPED
            managedLoopIntents += intent
            latestManagedLoopIntent = intent
            return NativeRuntimePublishStatus.ACCEPTED
        }

        override fun publishDirectLoopPolicy(
            revision: Long,
            selectionToken: Long,
            generation: Long,
            enabled: Boolean,
            aTimelineFrame: Long,
            bTimelineFrameExclusive: Long,
            capturedBTimelineFrameExclusive: Long,
            inputEventAtMonotonicMicros: Long,
            heldBGestureId: Long,
            boundaryArmedAtPublication: Boolean,
            boundaryCrossedAtPublication: Boolean,
            captureReferenceTimelineFrame: Long
        ): NativeRuntimePublishStatus {
            directLoopPolicyPublicationCount += 1
            directLoopPolicies += PublishedDirectLoopPolicy(
                revision = revision,
                ownership = NativeRuntimeOwnership(selectionToken, generation),
                enabled = enabled,
                aTimelineFrame = aTimelineFrame,
                bTimelineFrameExclusive = bTimelineFrameExclusive,
                capturedBTimelineFrameExclusive = capturedBTimelineFrameExclusive,
                inputEventAtMonotonicMicros = inputEventAtMonotonicMicros,
                heldBGestureId = heldBGestureId,
                boundaryArmedAtPublication = boundaryArmedAtPublication,
                boundaryCrossedAtPublication = boundaryCrossedAtPublication,
                captureReferenceTimelineFrame = captureReferenceTimelineFrame
            )
            return directLoopPolicyStatus
        }

        override fun publishManagedLoopIngressReady(
            evidence: NativeRuntimeManagedLoopIngressReady
        ): NativeRuntimePublishStatus {
            managedLoopReady += evidence
            return NativeRuntimePublishStatus.ACCEPTED
        }

        override fun beginLoopHeadPreparation(
            intent: NativeRuntimeManagedLoopIntent
        ): NativeRuntimeLoopHeadBeginStatus {
            if (loopPreparationBeginStatus != NativeRuntimeLoopHeadBeginStatus.ACCEPTED)
                return loopPreparationBeginStatus
            val revision = requireNotNull(intent.cacheKey).loopRevision
            if (enforceLoopHeadRevisionOrder && revision <= lastAcceptedLoopHeadRevision)
                return NativeRuntimeLoopHeadBeginStatus.STALE_REVISION
            lastAcceptedLoopHeadRevision = revision
            preparationIntents[revision] = intent
            return NativeRuntimeLoopHeadBeginStatus.ACCEPTED
        }

        override fun abortLoopHeadPreparation(
            loopRevision: Long
        ): NativeRuntimeLoopHeadAbortStatus {
            abortedLoopRevisions += loopRevision
            preparationIntents.remove(loopRevision)
            onAbortLoopPreparationOnce?.also { callback ->
                onAbortLoopPreparationOnce = null
                callback()
            }
            return NativeRuntimeLoopHeadAbortStatus.ACCEPTED
        }

        override fun loopProtocolSnapshot(): NativeRuntimeLoopProtocolSnapshot =
            emptyLoopProtocolSnapshot(
                latestManagedLoopIntent,
                latestManagedLoopIntent?.cacheKey?.loopRevision in committedCacheRevisions,
                loopIngressRequest
            )

        override fun controlProgress(): NativeRuntimeControlObservation {
            onNextControlProgress?.also { callback ->
                onNextControlProgress = null
                callback()
            }
            if (throwDiagnosticsOnce) {
                throwDiagnosticsOnce = false
                error("synthetic diagnostics invariant failure")
            }
            return NativeRuntimeControlProgress(
                status = if (running) NativeRuntimeStatus.RUNNING else NativeRuntimeStatus.STOPPED,
                ownership = ownership, presentation = presentation, presentedPosition = position,
                audibleEnd = end, audibleControl = audibleControl, automaticSuccessor = automaticSuccessor,
                banks = NativeRuntimeBank.entries.map { bank ->
                    val intent = snapshots.lastOrNull()?.banks?.get(bank.nativeCode)
                    NativeRuntimeBankControlProgress(appliedBankIntentRevisionOverrides[bank] ?: intent?.revision ?: 0L,
                        intent?.assigned ?: false)
                }, readinessProgress = renderReadiness?.let { detail ->
                    val failure = detail.lanes.firstOrNull { it.ingressFailureGeneration != 0L }
                    NativeRuntimeReadinessProgress(detail.sequence, detail.assigned, detail.ownership,
                        detail.controlRevision, detail.currentSongCacheBoundToSelectedOwner,
                        detail.currentSongCacheCanGrow, detail.currentSongCacheCompleteLaneMask,
                        detail.requiredSongLaneMask, detail.cacheStorageStatus, detail.fixedLoopHeadPrepared,
                        detail.directAtomicPolicyGeneration, failure?.laneIndex ?: -1,
                        failure?.ingressFailureGeneration ?: 0L,
                        loopPreparationFailure = detail.loopPreparationFailure)
                })
        }

        override fun diagnostics(): NativeRuntimeDiagnostics {
            detailedRuntimeReads++
            if (throwDiagnosticsOnce) {
                throwDiagnosticsOnce = false
                error("synthetic diagnostics invariant failure")
            }
            return NativeRuntimeDiagnostics(
                status = if (running) NativeRuntimeStatus.RUNNING else NativeRuntimeStatus.STOPPED,
                counters = ZERO_COUNTERS,
                ownership = ownership,
                presentation = presentation,
                presentedPosition = position,
                audibleEnd = end,
                banks = NativeRuntimeBank.entries.map { bank ->
                    val intent = snapshots.lastOrNull()?.banks?.get(bank.nativeCode)
                    NativeRuntimeBankDiagnostics(
                        appliedIntentRevision = appliedBankIntentRevisionOverrides[bank] ?: intent?.revision ?: 0L,
                        assigned = intent?.assigned ?: false,
                        beginSongCount = 0L,
                        seekCount = 0L,
                        rejectedIntentCount = 0L,
                        beatGridUpdateCount = 0L,
                        beatGridClearCount = 0L
                    )
                },
                audibleControl = audibleControl,
                automaticSuccessor = automaticSuccessor,
                canonicalPcmIo = canonicalPcmIo
            )
        }

        override fun renderReadinessDiagnostics(): NativeRuntimeRenderReadinessDiagnostics? {
            renderReadinessDiagnosticReads++
            val previous = renderReadiness
            // JNI returns its prior snapshot while requesting the worker's next publication.
            onNextReadinessRequest?.also { publish ->
                onNextReadinessRequest = null
                publish()
            }
            return previous
        }

        override fun isCurrentSongCacheAllLanesSealed(
            ownership: NativeRuntimeOwnership
        ): Boolean = isCurrentSongCacheLaneMaskSealed(
            ownership,
            NativeRuntimeManagedLoopIntent.ALL_LANES_MASK
        )

        override fun isFixedLoopHeadPrepared(ownership: NativeRuntimeOwnership): Boolean =
            fixedLoopHeadReady && ownership == lastSelected()

        override fun isCurrentSongCacheRangeReady(intent: NativeRuntimeBankIntent,
            requiredLaneMask: Int, first: Long, end: Long): Boolean {
            cacheRangeQueries += first to end
            if (holdCacheRangeProof) return false
            val owner = requireNotNull(intent.ownership)
            if (owner != lastSelected()) return false
            if (isCurrentSongCacheLaneMaskSealed(owner, requiredLaneMask)) return true
            val range = residentCacheRange ?: return false
            return residentCacheOwner == owner && first >= range.first && end - 1L <= range.last &&
                residentCacheLaneMask and requiredLaneMask == requiredLaneMask
        }

        private var pcmCacheProgressListener: (() -> Unit)? = null
        override fun setPcmCacheProgressListener(listener: (() -> Unit)?) { pcmCacheProgressListener = listener }

        fun releaseCacheRangeProof() {
            holdCacheRangeProof = false
            pcmCacheProgressListener?.invoke()
        }

        override fun isCurrentSongCacheLaneMaskSealed(
            ownership: NativeRuntimeOwnership,
            requiredLaneMask: Int
        ): Boolean {
            currentSongCacheSealCheckCount++
            val completeMask = if (currentSongCacheSealedByDefault) {
                NativeRuntimeManagedLoopIntent.ALL_LANES_MASK
            } else {
                sealedCurrentSongCacheLaneMasks[ownership] ?: 0
            }
            if (sealCacheAfterNextCheck) {
                sealCacheAfterNextCheck = false
                sealCurrentSongCacheFor(ownership)
            }
            return completeMask and requiredLaneMask == requiredLaneMask
        }

        fun sealCurrentSongCacheFor(owner: NativeRuntimeOwnership) {
            sealedCurrentSongCacheLaneMasks[owner] =
                NativeRuntimeManagedLoopIntent.ALL_LANES_MASK
        }

        fun sealCurrentSongOriginalFor(owner: NativeRuntimeOwnership) {
            sealedCurrentSongCacheLaneMasks[owner] =
                NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK
        }

        fun unsealCurrentSongCacheFor(owner: NativeRuntimeOwnership) {
            sealedCurrentSongCacheLaneMasks -= owner
        }

        fun expectCancellationBeforeJoin(
            decoders: Collection<FakeDecoder>,
            onFirstCancellationRequested: () -> Unit
        ) {
            cancellationGroup = decoders.toSet()
            this.onFirstCancellationRequested = onFirstCancellationRequested
        }

        fun onDecoderCancellationRequested() {
            onFirstCancellationRequested?.also { callback ->
                onFirstCancellationRequested = null
                callback()
            }
        }

        fun assertCancellationGroupRequested() {
            check(cancellationGroup.all(FakeDecoder::cancelAttempted)) {
                "Every decoder in one bank must be cancelled before the first join wait"
            }
        }

        fun setNativeStemRenderReadiness(
            owner: NativeRuntimeOwnership,
            renderStartTimelineFrame: Long,
            shortLane: Int? = null,
            shortLaneAvailableFrames: Long = 0L,
            readyFrames: Long = 8_192L,
            rawFirstTimelineFrame: Long = renderStartTimelineFrame,
            rawEndTimelineFrameExclusive: Long? = null,
            cacheCanGrow: Boolean = false,
            cacheStorageStatus: NativeCacheStorageStatus = NativeCacheStorageStatus.NOT_REQUESTED,
            ingressFailureGeneration: Long = 0L,
            ingressFailureLane: Int = 0,
            loopPreparationFailure: NativeRuntimeLoopPreparationFailure? = null
        ) {
            renderReadinessSequence++
            val selectedBank = requireNotNull(snapshots.last().selectedBank)
            val declaredStemCount = snapshots.last().banks[selectedBank.nativeCode].stemLaneCount
            renderReadiness = NativeRuntimeRenderReadinessDiagnostics(
                sequence = renderReadinessSequence,
                assigned = true,
                bank = selectedBank,
                ownership = owner,
                controlRevision = snapshots.last().controls.revision,
                desiredSourceMode = NativeRuntimeSourceMode.ORIGINAL,
                renderStartTimelineFrame = renderStartTimelineFrame,
                pendingControlRevision = 0L,
                pendingActualSourceMode = NativeRuntimeSourceMode.ORIGINAL,
                pendingSourceStartTimelineFrame = 0L,
                pendingSourceEndTimelineFrameExclusive = 0L,
                pendingFrames = 0L,
                outputQueuedFrames = 2_304L,
                stemActivationDeferrals = 0L,
                stemSafetyFallbacks = 0L,
                needStemDataCount = 0L,
                currentSongCacheCanGrow = cacheCanGrow,
                currentSongCacheBoundToSelectedOwner = cacheCanGrow || currentSongCacheSealedByDefault ||
                    (sealedCurrentSongCacheLaneMasks[owner] ?: 0) != 0,
                currentSongCacheCompleteLaneMask = if (currentSongCacheSealedByDefault)
                    NativeRuntimeManagedLoopIntent.ALL_LANES_MASK else sealedCurrentSongCacheLaneMasks[owner] ?: 0,
                cacheStorageStatus = cacheStorageStatus,
                requiredSongLaneMask = musicLaneMaskForStemCount(declaredStemCount),
                loopPreparationFailure = loopPreparationFailure,
                lanes = (0 until NativeSongLaneIngressBank.LANE_COUNT).map { laneIndex ->
                    val available = if (laneIndex > declaredStemCount) {
                        0L
                    } else if (laneIndex == shortLane) {
                        shortLaneAvailableFrames
                    } else {
                        readyFrames
                    }
                    NativeRuntimeRenderLaneReadinessDiagnostics(
                        laneIndex = laneIndex,
                        generation = owner.generation,
                        firstTimelineFrame = rawFirstTimelineFrame,
                        endTimelineFrameExclusive = rawEndTimelineFrameExclusive
                            ?: rawFirstTimelineFrame + available,
                        availableFromRenderStart = available,
                        reservoirGapRejectionCount = 0L,
                        ingressFailureGeneration = if (laneIndex == ingressFailureLane) ingressFailureGeneration else 0L,
                        ingressActiveGeneration = owner.generation,
                        ingressProducerGeneration = owner.generation,
                        ingressConsumerGeneration = owner.generation,
                        ingressReadableSourceFrames = available,
                        ingressPulls = 0L,
                        ingressStaleAfterPull = 0L,
                        ingressFutureGenerationDeferrals = 0L
                    )
                }
            )
        }

        override fun hasAppliedUnassignment(
            bank: NativeRuntimeBank,
            exactRevision: Long
        ): Boolean = !holdUnassignmentAcknowledgement &&
            bank !in heldUnassignmentAcknowledgementBanks &&
            snapshots.lastOrNull()?.banks?.get(bank.nativeCode)?.let {
            it.revision == exactRevision && !it.assigned
        } == true

        override fun notifyIngressAvailable() {
            operations += "notify-ingress"
        }

        override fun quiesceOutputIfPaused(): Boolean {
            quiesceOutputCallCount++
            operations += "quiesce-output"
            return quiesceOutputReady
        }

        override fun setAudibleControlProgressListener(
            listener: ((NativeRuntimeAudibleControlEvidence) -> Unit)?
        ) {
            audibleControlProgressListener = listener
        }

        override fun openDecoder(
            bank: NativeRuntimeBank,
            bankRole: NativeSongBankRole,
            laneIndex: Int,
            spec: NativeEngineDecoderSpec
        ): NativeEngineDecoder {
            decoderOpenAttempts += bankRole to spec.location
            if (spec.location in permanentlyFailedOpenLocations) {
                error("synthetic permanent open failure for ${spec.location}")
            }
            if (failOpenLaneOnce == laneIndex) {
                failOpenLaneOnce = null
                error("synthetic lane-$laneIndex open failure")
            }
            return FakeDecoder(
                environment = this,
                bank = bank,
                role = bankRole,
                laneIndex = laneIndex,
                spec = spec,
                stuck = stuckDecoders
            ).also {
                it.exposePcmHead = newDecodersExposePcmHead
                decoders += it
                openedDecoderCount++
                maximumOpenDecoderCount = maxOf(
                    maximumOpenDecoderCount,
                    openDecoders().size + openLoopPreparationCount
                )
                check(openDecoders().size + openLoopPreparationCount <= NativeSongLaneIngressBank.LANE_COUNT + 2) {
                    "open=${openDecoders().map { decoder -> "${decoder.laneIndex}:${decoder.role}:${decoder.released}" }}"
                }
            }
        }

        override fun releaseDecoder(bank: NativeRuntimeBank, decoder: NativeEngineDecoder) {
            check(decoder.safeToReleaseLane())
            (decoder as FakeDecoder).released = true
            decoder.close()
            releasedDecoderCount++
        }

        override fun tryOpenLoopHeadPreparationDecoder(
            spec: NativeEngineLoopPreparationSpec
        ): NativeEngineLoopPreparationDecoder? {
            if (loopPreparationOpen) return null
            val intent = preparationIntents[spec.loopRevision]
            if (intent == null) return null
            loopPreparationOpen = true
            loopPreparationSpecs += spec
            maximumOpenDecoderCount = maxOf(
                maximumOpenDecoderCount,
                openDecoders().size + openLoopPreparationCount
            )
            check(openDecoders().size + openLoopPreparationCount <= NativeSongLaneIngressBank.LANE_COUNT + 2)
            val finalLane = (NativeSongLaneIngressBank.LANE_COUNT - 1 downTo 0).first {
                intent.requiredLaneMask and (1 shl it) != 0
            }
            val completedOutcome = if (failLoopPreparationCount > 0) {
                failLoopPreparationCount -= 1
                NativeEngineLoopPreparationOutcome.FAILED
            } else if (spec.laneIndex == finalLane) {
                NativeEngineLoopPreparationOutcome.CACHE_COMMITTED
            } else {
                NativeEngineLoopPreparationOutcome.LANE_COMPLETED
            }
            return FakeLoopPreparationDecoder(
                spec,
                completedOutcome,
                holdRunning = { holdLoopPreparation },
                acceptedSourceFrame = { loopPreparationAcceptedSourceFrame },
                joinWatchdog = {
                    if (loopPreparationPendingJoinPolls > 0) {
                        loopPreparationPendingJoinPolls--
                        true
                    } else loopPreparationJoinWatchdog
                },
                closeGate = loopPreparationCloseGate,
                closeEntered = loopPreparationCloseEntered,
                onCancelRequested = { loopPreparationCancelRequestCount++ }
            )
        }

        override fun releaseLoopHeadPreparationDecoder(
            decoder: NativeEngineLoopPreparationDecoder
        ) {
            check(decoder.safeToRelease())
            if (decoder.snapshot().outcome == NativeEngineLoopPreparationOutcome.CACHE_COMMITTED) {
                val revision = loopPreparationSpecs.last { it.decoderId == decoder.decoderId }.loopRevision
                committedCacheRevisions += revision
                if (exposePreparedLoopRange) {
                    val prepared = requireNotNull(preparationIntents[revision])
                    val key = requireNotNull(prepared.cacheKey)
                    residentCacheOwner = prepared.ownership
                    residentCacheRange = key.startTimelineFrame until key.endTimelineFrameExclusive
                    residentCacheLaneMask = prepared.requiredLaneMask
                }
            }
            decoder.close()
            loopPreparationOpen = false
        }

        override fun outputDiagnostics(): NativeAudioDiagnostics? {
            outputDiagnosticReads++
            return observedOutput
        }

        override fun decoderLeaseSnapshot(): NativeDecoderLeaseSnapshot {
            decoderLeaseDiagnosticReads++
            return NativeDecoderLeaseSnapshot(
                activeDecoderCount = openDecoders().count { it.role == NativeSongBankRole.ACTIVE },
                candidateDecoderCount = openDecoders().count {
                    it.role == NativeSongBankRole.CANDIDATE
                },
                loopPreparationDecoderCount = openLoopPreparationCount
            )
        }

        override fun armManualSelectionGate(): Long {
            if (!manualSelectionGateArmed) {
                manualSelectionGateArmed = true
                operations += "manual-gate-arm:$outputGeneration"
            }
            return outputGeneration
        }

        override fun authorizeManualSelectionGate(generation: Long): Boolean {
            val accepted = manualSelectionGateArmed && generation == outputGeneration
            operations += "manual-gate-authorize:$generation:$accepted"
            if (accepted) {
                manualSelectionGateArmed = false
                manualSelectionGateAuthorizations += generation
            }
            return accepted
        }

        override fun engageImmediateSilenceGate(): Long {
            immediateSilenceFailure?.let { throw it }
            onImmediateSilence?.invoke()
            operations += "immediate-silence:$outputGeneration"
            return outputGeneration
        }

        override fun stopRuntimeAndJoin() {
            operations += "stop-join-runtime"
            runtimeJoinFailure?.let { throw it }
            running = false
        }

        override fun needsOutputRecovery(): Boolean = outputRecoveryRequired

        override fun reopenOutputForRouteChange(): Boolean {
            operations += "reopen-output"
            // Native force-resets roles/generation before attempting to open the replacement
            // stream, so a failed open still retires the preceding output generation.
            outputGeneration++
            if (reopenOutputSucceeds) {
                manualSelectionGateArmed = false
                outputRecoveryRequired = false
            }
            return reopenOutputSucceeds
        }

        override fun recreateRuntime(): Boolean {
            operations += "recreate-runtime"
            if (!recreateRuntimeSucceeds) return false
            lastAcceptedLoopHeadRevision = 0L
            running = true
            // Route recovery replaces only the runtime. The output object survives its stream
            // reopen, so its presentation, position, and applied-control evidence and sequences
            // remain monotonic and observable to the replacement runtime.
            end = null
            endSequence = 0L
            renderReadinessSequence = 0L
            renderReadiness = null
            ownership = NativeRuntimeOwnershipProgress(null, null, null, 0L)
            return true
        }

        override fun retireOutputForStop(newGeneration: Long): Long? {
            check(!running)
            check(newGeneration > outputGeneration)
            operations += "retire-output:$newGeneration"
            if (outputRetirementPendingPolls > 0) { outputRetirementPendingPolls--; return null }
            outputRetirementFailure?.let { throw it }
            outputGeneration = newGeneration
            return newGeneration
        }

        override fun close() {
            operations += "close-environment"
        }

        fun lastSelected(): NativeRuntimeOwnership? = snapshots.lastOrNull()?.selectedOwnership

        fun present(
            owner: NativeRuntimeOwnership,
            timelineFrame: Long,
            extendDecoderCoverage: Boolean = true
        ) {
            presentationSequence++
            positionSequence++
            presentation = NativeRuntimePresentationEvidence(
                presentationSequence,
                owner,
                presentationSequence,
                timelineFrame,
                timelineFrame,
                presentationSequence,
                256L
            )
            position = NativeRuntimePresentedPosition(
                positionSequence,
                owner,
                presentationSequence,
                timelineFrame,
                timelineFrame,
                presentationSequence,
                256L
            )
            ownership = NativeRuntimeOwnershipProgress(owner, owner, owner,
                presentationSequence)
            if (extendDecoderCoverage) {
                decoders.filter { !it.released && it.spec.decoderId.startsWith("${owner.selectionToken}-") }
                    .forEach { it.extendCoverageThrough(timelineFrame + 8_192L) }
            }
        }

        fun presentWithoutSequenceAdvance(owner: NativeRuntimeOwnership, timelineFrame: Long) {
            check(presentationSequence > 0L)
            positionSequence++
            presentation = NativeRuntimePresentationEvidence(
                presentationSequence,
                owner,
                presentationSequence,
                timelineFrame,
                timelineFrame,
                presentationSequence,
                256L
            )
            position = NativeRuntimePresentedPosition(
                positionSequence,
                owner,
                presentationSequence,
                timelineFrame,
                timelineFrame,
                presentationSequence,
                256L
            )
            ownership = NativeRuntimeOwnershipProgress(
                owner,
                owner,
                owner,
                presentationSequence
            )
            decoders.filter { !it.released && it.spec.decoderId.startsWith("${owner.selectionToken}-") }
                .forEach { it.extendCoverageThrough(timelineFrame + 8_192L) }
        }

        fun installPaused(owner: NativeRuntimeOwnership) {
            ownership = NativeRuntimeOwnershipProgress(owner, owner, owner, 1L)
        }

        fun acceptNonEmptyWithoutPresentation(owner: NativeRuntimeOwnership) {
            ownership = NativeRuntimeOwnershipProgress(
                requested = owner,
                accepted = owner,
                committed = null,
                acceptedFirstFingerprintSequence = 1L
            )
        }

        fun installPausedAutomaticSuccessor(intent: NativeRuntimeAutomaticSuccessorIntent) {
            automaticSuccessor = NativeRuntimeAutomaticSuccessorDiagnostics(
                phase =
                    NativeRuntimeAutomaticSuccessorPhase.APPLIED_PAUSED_AWAITING_SELECTION,
                cancellationStatus = NativeRuntimeDrainCancelStatus.SUCCESSOR_ACTIVE,
                revision = intent.revision,
                transitionRevision = intent.revision,
                outgoingOwnership = intent.outgoingOwnership,
                successorOwnership = intent.successorOwnership,
                successorQueuedFrames = 8_192L,
                firstFingerprintSequence = 1L,
                cancellationDesired = false,
                successorEndObserved = false
            )
            ownership = NativeRuntimeOwnershipProgress(
                requested = intent.successorOwnership,
                accepted = intent.successorOwnership,
                committed = intent.successorOwnership,
                acceptedFirstFingerprintSequence = 1L
            )
        }

        fun end(owner: NativeRuntimeOwnership, directLoopPolicyRevision: Long = 0L) {
            endSequence++
            end = NativeRuntimeAudibleEndEvent(
                endSequence,
                owner,
                directLoopPolicyRevision
            )
        }

        fun presentAudibleControl(
            owner: NativeRuntimeOwnership,
            controlRevision: Long,
            sourceMode: NativeRuntimeSourceMode,
            callbackSequence: Long = audibleControlSequence + 1L
        ) {
            audibleControlSequence++
            audibleControl = NativeRuntimeAudibleControlEvidence(
                sequence = audibleControlSequence,
                ownership = owner,
                controlRevision = controlRevision,
                actualSourceMode = sourceMode,
                firstFingerprintSequence = callbackSequence,
                lastFingerprintSequence = callbackSequence,
                callbackSequence = callbackSequence,
                pcmFrames = 256L
            )
        }

        fun emitAudibleControlProgress() {
            val evidence = requireNotNull(audibleControl)
            audibleControl = null
            audibleControlProgressListener?.invoke(evidence)
        }

        fun failNextOpenAtLane(laneIndex: Int) {
            require(laneIndex in 0 until NativeSongLaneIngressBank.LANE_COUNT)
            failOpenLaneOnce = laneIndex
        }

        fun failAllOpensAtLocation(location: String) {
            require(location.isNotBlank())
            permanentlyFailedOpenLocations += location
        }

        fun openAttemptCount(location: String): Int =
            decoderOpenAttempts.count { (_, attemptedLocation) -> attemptedLocation == location }

        fun fakeDecoder(lane: Int, generation: Long): FakeDecoder = FakeDecoder(
            this,
            NativeRuntimeBank.FIRST,
            NativeSongBankRole.ACTIVE,
            lane,
            NativeEngineDecoderSpec(
                "fake-$lane",
                "lane-$lane",
                "/fake",
                0L,
                generation,
                EngineSelectionToken(1L)
            ),
            false
        )

        private fun openDecoders() = decoders.filter { !it.released }
    }

    protected class FakeLoopPreparationDecoder(
        private val spec: NativeEngineLoopPreparationSpec,
        private val completedOutcome: NativeEngineLoopPreparationOutcome,
        private val holdRunning: () -> Boolean,
        private val acceptedSourceFrame: () -> Long,
        private val joinWatchdog: () -> Boolean,
        private val closeGate: CountDownLatch?,
        private val closeEntered: CountDownLatch,
        private val onCancelRequested: () -> Unit
    ) : NativeEngineLoopPreparationDecoder {
        override val decoderId: String get() = spec.decoderId
        override val laneIndex: Int get() = spec.laneIndex
        private var started = false
        private var joined = false
        private var terminalObserved = false
        private var cancelled = false
        private var cancellationRequested = false
        private var retirement: NativePreparationRetirement? = null

        override fun start(): Boolean {
            started = true
            return true
        }

        override fun snapshot(): NativeEngineLoopPreparationSnapshot {
            val outcome = when {
                cancelled -> NativeEngineLoopPreparationOutcome.CANCELLED
                started && !holdRunning() -> completedOutcome
                else -> NativeEngineLoopPreparationOutcome.RUNNING
            }
            if (outcome == completedOutcome) terminalObserved = true
            return NativeEngineLoopPreparationSnapshot(
                outcome = outcome,
                sourceRateHz = NATIVE_ENGINE_SAMPLE_RATE_HZ,
                finalSourceFrameExclusive = acceptedSourceFrame(),
                cause = null
            )
        }

        override fun requestCancel() {
            if (cancellationRequested) return
            cancellationRequested = true
            if (!terminalObserved) cancelled = true
            onCancelRequested()
            closeGate?.let { gate ->
                retirement = NativePreparationRetirement(
                    stop = { closeEntered.countDown(); check(gate.await(5, TimeUnit.SECONDS)) },
                    isJoined = { true },
                    release = {}
                )
            }
        }

        override fun pollJoin(): Boolean {
            if (joinWatchdog()) return false
            if (retirement?.await(0L, TimeUnit.NANOSECONDS) == false) return false
            joined = true
            return true
        }

        override fun cancelAndJoin(watchdogMs: Long): NativeEngineDecoderJoinStatus {
            requestCancel()
            if (!terminalObserved) cancelled = true
            if (joinWatchdog()) return NativeEngineDecoderJoinStatus.WATCHDOG_EXPIRED
            if (retirement?.await(watchdogMs, TimeUnit.MILLISECONDS) == false) {
                return NativeEngineDecoderJoinStatus.WATCHDOG_EXPIRED
            }
            joined = true
            return NativeEngineDecoderJoinStatus.JOINED
        }

        override fun safeToRelease(): Boolean = joined
        override fun close() = Unit
    }

    protected class FakeDecoder(
        private val environment: FakeEnvironment,
        val bank: NativeRuntimeBank,
        var role: NativeSongBankRole,
        override val laneIndex: Int,
        val spec: NativeEngineDecoderSpec,
        private val stuck: Boolean
    ) : NativeEngineDecoder {
        override val decoderId: String get() = spec.decoderId
        private var localGeneration = 0L
        private var nativeGeneration = spec.generationBase
        private var joined = false
        private var firstFrame = spec.initialPositionUs * NATIVE_ENGINE_SAMPLE_RATE_HZ / 1_000_000L
        private var endFrame = firstFrame + 8_192L
        var exposePcmHead = true
        var exactEndOfStreamFrame: Long? = null
        var reportedNativeGenerationOverride: Long? = null
        private var terminalFailure: NativeEngineDecoderFailureSignal? = null
        var failNativeAdvance = false
        var failAuthorization = false
        var seekAuthorizationOverride: ((NativeEngineDecoderSeekTicket) -> Boolean)? = null
        var nativeAdvanceCount = 0
        var authorizationCount = 0
        var sinkRetriesThrottled = false
        var cancelAttempted = false
        var joinWatchdogExpired = false
        val joinTimeouts = mutableListOf<Long>()
        var released = false
        val requestedSeekTargets = mutableListOf<Long>()
        @Volatile
        private var nextDiagnosticHeadEntered: CountDownLatch? = null
        @Volatile
        private var nextDiagnosticHeadRelease: CountDownLatch? = null

        override fun start(): Boolean = true
        override fun readableSourceFrames(): Int = 0
        override fun failure(): NativeEngineDecoderFailureSignal? = terminalFailure
        override fun head(): NativeEngineDecoderHead {
            val entered = nextDiagnosticHeadEntered
            val release = nextDiagnosticHeadRelease
            if (entered != null && release != null) {
                nextDiagnosticHeadEntered = null
                nextDiagnosticHeadRelease = null
                entered.countDown()
                check(release.await(1L, TimeUnit.SECONDS)) {
                    "Timed out waiting to release the diagnostic decoder head"
                }
            }
            return NativeEngineDecoderHead(
                configured = true,
                acceptedTimelineFrames48k = (endFrame - firstFrame)
                    .takeIf { exposePcmHead } ?: 0L,
                firstTimelineFrame48k = firstFrame.takeIf { exposePcmHead },
                acceptedTimelineFrameExclusive48k = endFrame.takeIf { exposePcmHead },
                discontinuities = 0L,
                nativeGeneration = reportedNativeGenerationOverride ?: nativeGeneration,
                endOfStreamTimelineFrameExclusive48k = exactEndOfStreamFrame
            )
        }

        fun blockNextHead(entered: CountDownLatch, release: CountDownLatch) {
            check(nextDiagnosticHeadEntered == null && nextDiagnosticHeadRelease == null)
            nextDiagnosticHeadEntered = entered
            nextDiagnosticHeadRelease = release
        }

        override fun requestSeek(
            positionUs: Long,
            targetNativeGeneration: Long
        ): NativeEngineDecoderSeekTicket {
            environment.operations +=
                "decoder-seek:${spec.selectionToken.value}:$targetNativeGeneration"
            localGeneration++
            firstFrame = positionUs * NATIVE_ENGINE_SAMPLE_RATE_HZ / 1_000_000L
            endFrame = firstFrame + 8_192L
            requestedSeekTargets += targetNativeGeneration
            return NativeEngineDecoderSeekTicket(localGeneration, targetNativeGeneration)
        }

        override fun canAuthorizeSeek(ticket: NativeEngineDecoderSeekTicket): Boolean =
            seekAuthorizationOverride?.invoke(ticket) ?: (ticket.decoderGeneration >= localGeneration &&
                ticket.nativeGeneration > nativeGeneration)

        override fun advanceNativeGeneration(ticket: NativeEngineDecoderSeekTicket): Boolean {
            nativeAdvanceCount++
            if (failNativeAdvance) return false
            nativeGeneration = ticket.nativeGeneration
            return true
        }

        override fun authorizeDecoderGeneration(ticket: NativeEngineDecoderSeekTicket): Boolean {
            check(ticket.nativeGeneration == nativeGeneration)
            if (failAuthorization) return false
            authorizationCount++
            return true
        }

        override fun promoteCandidateLeaseToActive(): Boolean {
            role = NativeSongBankRole.ACTIVE
            return true
        }

        override fun setSinkRetryThrottled(throttled: Boolean) {
            sinkRetriesThrottled = throttled
        }

        override fun requestCancel() {
            if (cancelAttempted) return
            cancelAttempted = true
            environment.onDecoderCancellationRequested()
        }

        override fun awaitJoin(timeoutNanos: Long): NativeEngineDecoderJoinStatus {
            require(timeoutNanos >= 0L)
            joinTimeouts += timeoutNanos
            check(cancelAttempted)
            environment.assertCancellationGroupRequested()
            if (stuck || joinWatchdogExpired) return NativeEngineDecoderJoinStatus.WATCHDOG_EXPIRED
            joined = true
            return NativeEngineDecoderJoinStatus.JOINED
        }

        override fun safeToReleaseLane(): Boolean = joined
        override fun close() = Unit

        fun extendCoverageThrough(frame: Long) {
            endFrame = maxOf(endFrame, frame)
        }

        fun setAcceptedCoverage(firstFrame: Long, endFrameExclusive: Long) {
            require(firstFrame >= 0L && endFrameExclusive >= firstFrame)
            this.firstFrame = firstFrame
            endFrame = endFrameExclusive
        }

        fun failAsync(
            cause: String,
            generation: Long = nativeGeneration,
            token: EngineSelectionToken = spec.selectionToken
        ) {
            terminalFailure = NativeEngineDecoderFailureSignal(
                decoderId,
                token,
                generation,
                laneIndex,
                cause
            )
        }
    }

    companion object {
        const val TEST_THREAD_WATCHDOG_MS = 5_000L
        const val TEST_THREAD_CLEANUP_MS = 1_000L
        const val BLOCKED_TRANSACTION_OBSERVATION_MS = 100L

        val ZERO_COUNTERS = NativeRuntimeCounters(
            commandSnapshotsPublished = 0L,
            commandSnapshotsConsumed = 0L,
            commandSnapshotsSuperseded = 0L,
            rejectedCommandSnapshots = 0L,
            beatGridUpdatesPublished = 0L,
            rejectedBeatGridUpdates = 0L,
            renderCycles = 0L,
            idleSleeps = 0L,
            outputFlushes = 0L,
            outputFlushFailures = 0L,
            outputHandoffBegins = 0L,
            outputHandoffBeginRetries = 0L,
            outputHandoffCommits = 0L,
            outputHandoffCommitRetries = 0L,
            outputBlocksRendered = 0L,
            outputFramesRendered = 0L,
            outputFramesAccepted = 0L,
            outputPartialAccepts = 0L,
            outputBackpressureEvents = 0L,
            outputInvalidRenderResults = 0L,
            outputNeedDataEvents = 0L,
            outputEndOfStreamEvents = 0L,
            targetQueuedFrames = 0L,
            lastOutputFingerprintSequence = 0L,
            lastRenderPayloadHash = 0L,
            outputFlushRetries = 0L
        )

        fun emptyLoopProtocolSnapshot(
            intent: NativeRuntimeManagedLoopIntent?,
            cacheCommitted: Boolean,
            ingressRequest: NativeRuntimeLoopIngressRequest? = null
        ) = NativeRuntimeLoopProtocolSnapshot(
            adopted = intent != null,
            bank = NativeRuntimeBank.FIRST,
            state = NativeRuntimeLoopCycleState(
                mode = if (intent?.enabled == true) NativeRuntimeLoopCycleMode.STREAMED
                    else NativeRuntimeLoopCycleMode.DISABLED,
                phase = when {
                    intent?.enabled != true -> NativeRuntimeLoopCyclePhase.DISABLED
                    cacheCommitted &&
                        intent.boundaryMode == NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS ->
                        NativeRuntimeLoopCyclePhase.WAITING_FOR_BOUNDARY
                    cacheCommitted -> NativeRuntimeLoopCyclePhase.WAITING_FOR_SAFE_WINDOW
                    else -> NativeRuntimeLoopCyclePhase.WAITING_FOR_CACHE
                },
                activation = NativeRuntimeLoopCycleActivation.NONE,
                latestRevision = intent?.revision ?: 0L,
                ownership = intent?.ownership,
                renderCycle = 0L,
                activeIngressEpoch = intent?.ownership?.generation ?: 0L,
                preparedIngressEpoch = 0L,
                cacheChecksum = 0L,
                cacheAccepted = cacheCommitted,
                renderInstalled = cacheCommitted && intent?.enabled == true,
                renderActive = false,
                audiblyProven = false,
                ingressRequestPending = ingressRequest != null,
                renderDirectivePending = false,
                boundaryMode = intent?.boundaryMode
                    ?: NativeRuntimeManagedLoopBoundaryMode.UNRESOLVED,
                boundaryResolved = false,
                resolvedLoopEndTimelineFrameExclusive = 0L
            ),
            ingressRequest = ingressRequest,
            renderDirective = null,
            audibleEvidence = null,
            activeCache = null,
            preparation = NativeRuntimeLoopHeadPreparationSnapshot(
                phase = if (cacheCommitted) {
                    NativeRuntimeLoopHeadPreparationPhase.COMMITTED
                } else {
                    NativeRuntimeLoopHeadPreparationPhase.IDLE
                },
                loopRevision = intent?.cacheKey?.loopRevision ?: 0L,
                requiredLaneMask = intent?.requiredLaneMask ?: 0,
                completedLaneMask = if (cacheCommitted) intent?.requiredLaneMask ?: 0 else 0,
                activeLane = 7,
                activeSourceRateHz = 0,
                activePreparationGeneration = 0L,
                nextExpectedTimelineFrame = 0L,
                beginCalls = 0L,
                configuredLanes = 0L,
                offerCalls = 0L,
                inputFramesAccepted = 0L,
                cacheFramesWritten = 0L,
                lanesCompleted = 0L,
                commits = if (cacheCommitted) 1L else 0L,
                failures = 0L,
                aborts = 0L
            ),
            counters = NativeRuntimeLoopProtocolCounters(
                acceptedIntents = 0L,
                unchangedIntents = 0L,
                ownershipRebinds = 0L,
                staleIntents = 0L,
                revisionCollisions = 0L,
                cachesAccepted = 0L,
                cacheRejections = 0L,
                ingressRequests = 0L,
                ingressAcks = 0L,
                ingressAckRejections = 0L,
                positionObservations = 0L,
                rejectedPositionObservations = 0L,
                boundaryEvents = 0L,
                rejectedBoundaryEvents = 0L,
                renderDirectives = 0L,
                renderCommits = 0L,
                cacheOnlyWraps = 0L,
                streamedWraps = 0L,
                deadlineFallbacks = 0L,
                callbackProofs = 0L,
                rejectedCallbackProofs = 0L
            ),
            lastSubmitStatus = 0,
            lastCacheStatus = 0,
            lastIngressStatus = 0,
            lastPositionStatus = 0,
            lastBoundaryStatus = 0,
            lastRenderCommitStatus = 0,
            lastCallbackStatus = 0,
            positionEvidenceSequence = 0L,
            lastOutputLoopEvidenceSequence = 0L
        )

        fun request(song: String, autoplay: Boolean = true) = NativeEngineSelectionRequest(
            song,
            "rev-$song",
            "/$song.ogg",
            180_000L,
            autoplay = autoplay
        )

        fun metadata(song: String, revision: Long) = NativeEngineMetadata(
            song,
            "rev-$song",
            revision,
            "metadata-$revision"
        )

        fun queueItem(song: String) = NativeEngineQueueItem(
            request(song).identity(),
            metadata(song, 1L)
        )

        fun currentStems(version: String = "base"): List<EngineStemLane> = listOf(
            "vocals", "drums", "bass", "guitar", "piano", "remaining"
        ).map { role ->
            EngineStemLane(
                role,
                role,
                "/$version-$role.flac",
                "sha-$version-$role"
            )
        }

        fun msToFrameForTest(positionMs: Long): Long =
            positionMs * NATIVE_ENGINE_SAMPLE_RATE_HZ / 1_000L
    }
}
