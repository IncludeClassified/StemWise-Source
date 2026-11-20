package app.stemwise.playback.engine

import app.stemwise.quality.runner.DeterministicTraceMinimizer
import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
internal class NativePlaybackControllerTraceTest : NativePlaybackControllerTestSupport() {
    private enum class Action {
        SELECT_A, SELECT_B, SELECT_C, PLAY, PAUSE, ACTOR, PRESENT, STOP,
        SEEK, ROUTE, LOOP, CLEAR, TUNE, MIX
    }
    private val profile = JSONObject(requireNotNull(javaClass.classLoader)
        .getResourceAsStream("engine-invariants.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() })
        .getJSONObject("replay")

    private fun Fixture.runTurns(count: Int) {
        repeat(count) { if (scheduler.activeTaskCount() > 0) scheduler.runNext() }
    }

    @Test fun generatedCommandsRetainLatestOwnerAndTransportAcrossActualActorTurns() {
        val first = profile.getInt("firstSeed")
        val count = profile.getInt("seedCount")
        require(first >= 0 && count > 0 && profile.getInt("generatedActions") > 0)
        repeat(count) { offset ->
            val seed = first + offset
            val actions = trace(seed)
            val failure = failure(actions)
            if (failure != null) {
                val minimal = DeterministicTraceMinimizer.minimize(actions, failure, ::failure)
                failure(minimal, diagnostics = true)
                fail("$failure fixture=controller-manual seed=$seed actualTrace=$minimal")
            }
        }
        println("AUDIO_REPLAY seeds=$first..${first + count - 1} actions=${profile.getInt("generatedActions")} actorStepBound=${profile.getInt("maximumActorSteps")}")
    }

    @Test fun lostPauseFailsTheSameOracleAndShrinksToItsCausalTrace() {
        val original = listOf(Action.SELECT_A, Action.ACTOR, Action.PRESENT, Action.PAUSE, Action.ACTOR)
        assertNull(failure(original))
        val minimal = DeterministicTraceMinimizer.minimize(original, "AE-14 transport") {
            failure(it, dropPause = true)
        }
        assertEquals("AE-14 transport", failure(minimal, dropPause = true))
        assertNull(failure(minimal))
    }

    @Test fun decoderCompletionBeforeAndAfterNewerAdmissionRetiresOnlyTheSupersededOwner() {
        for (completeBeforeAdmission in listOf(false, true)) {
            val trace = mutableListOf<String>()
            val fixture = Fixture()
            fixture.controller.select(request("completion-a")); fixture.scheduler.runNext(); fixture.presentLatest()
            fixture.environment.newDecodersExposePcmHead = false
            val b = fixture.controller.select(request("completion-b")); fixture.runTurns(3)
            val decoder = fixture.environment.decoders.single { it.spec.selectionToken == b }
            fun complete() {
                decoder.exposePcmHead = true
                trace += "decoder-pcm:${b.value}:${decoder.head().nativeGeneration}"
                fixture.controller.signalNativeProgress(); fixture.runTurns(8)
            }
            if (completeBeforeAdmission) complete()
            fixture.environment.newDecodersExposePcmHead = true
            val c = fixture.controller.select(request("completion-c"))
            trace += "admit:${c.value}"
            fixture.runTurns(8)
            if (!completeBeforeAdmission) complete()
            fixture.presentLatest(); fixture.runTurns(4)
            assertEquals("AE-05 actualTrace=$trace", c.value, fixture.environment.lastSelected()?.selectionToken)
            assertTrue("AE-04 actualTrace=$trace", decoder.released)
            assertEquals(c, fixture.ownerPublications().last().selection.token)
            println("AUDIO_ORDER selection-late-completion before=$completeBeforeAdmission actualTrace=$trace")
            fixture.controller.stopForTaskRemoval(); fixture.runTurns(8)
        }
    }

    @Test fun pauseBeforeAndAfterReplacementInstallRetainsTheExactTransportDisposition() {
        for (pauseBeforeInstall in listOf(false, true)) {
            val fixture = Fixture()
            fixture.controller.select(request("pause-a")); fixture.scheduler.runNext(); fixture.presentLatest()
            fixture.environment.newDecodersExposePcmHead = false
            val b = fixture.controller.select(request("pause-b")); fixture.runTurns(3)
            val decoder = fixture.environment.decoders.single { it.spec.selectionToken == b }
            fun pause() { assertTrue(fixture.controller.updateDesired(b) { it.copy(playWhenReady = false) }) }
            if (pauseBeforeInstall) pause()
            decoder.exposePcmHead = true
            fixture.controller.signalNativeProgress(); fixture.runTurns(8)
            if (!pauseBeforeInstall) pause()
            fixture.runTurns(8)
            assertEquals("AE-05 pauseBeforeInstall=$pauseBeforeInstall", b.value,
                fixture.environment.lastSelected()?.selectionToken)
            assertFalse("AE-12 pauseBeforeInstall=$pauseBeforeInstall", fixture.environment.snapshots.last().playing)
            println("AUDIO_ORDER transport-during-replacement pause-before-install=$pauseBeforeInstall " +
                "owner=${fixture.environment.lastSelected()} operations=${fixture.environment.operations}")
            fixture.controller.stopForTaskRemoval(); fixture.runTurns(8)
        }
    }

    @Test fun stopBeforeAndAfterReplacementInstallCannotBeUndoneByLateDecoderCompletion() {
        for (stopBeforeInstall in listOf(false, true)) {
            val fixture = Fixture()
            fixture.controller.select(request("stop-a")); fixture.scheduler.runNext(); fixture.presentLatest()
            fixture.environment.newDecodersExposePcmHead = false
            val b = fixture.controller.select(request("stop-b")); fixture.runTurns(3)
            val decoder = fixture.environment.decoders.single { it.spec.selectionToken == b }
            if (!stopBeforeInstall) {
                decoder.exposePcmHead = true
                fixture.controller.signalNativeProgress(); fixture.runTurns(8)
            }
            val stop = fixture.controller.stopForTaskRemoval(); fixture.runTurns(8)
            decoder.exposePcmHead = true
            fixture.controller.signalNativeProgress(); fixture.runTurns(8)
            assertEquals("AE-04 stopBeforeInstall=$stopBeforeInstall", NativeEngineStopStatus.COMPLETED, stop.status())
            assertNull("AE-12 stopBeforeInstall=$stopBeforeInstall", fixture.environment.lastSelected())
            assertTrue(decoder.released)
            println("AUDIO_ORDER transport-during-replacement stop-before-install=$stopBeforeInstall " +
                "operations=${fixture.environment.operations}")
            fixture.controller.stopForTaskRemoval(); fixture.runTurns(8)
        }
    }

    @Test fun seekBeforeAndAfterOutgoingEofKeepsTheNewExactSeekOwner() {
        for (eofBeforeSeek in listOf(false, true)) {
            val fixture = Fixture()
            val token = fixture.controller.select(request("seek-eof"))
            fixture.scheduler.runNext(); fixture.presentLatest()
            val outgoing = requireNotNull(fixture.environment.lastSelected())
            fun eof() {
                fixture.environment.end(outgoing)
                fixture.controller.signalNativeProgress(); fixture.runTurns(3)
            }
            if (eofBeforeSeek) eof()
            assertTrue(fixture.controller.updateDesired(token) {
                it.copy(requestedPositionMs = 1_500L, seekRevision = it.seekRevision + 1L)
            })
            fixture.runTurns(8)
            val seekOwner = requireNotNull(fixture.environment.lastSelected())
            assertTrue("AE-07 eofBeforeSeek=$eofBeforeSeek", seekOwner.generation > outgoing.generation)
            fixture.presentLatest(); fixture.runTurns(3)
            if (!eofBeforeSeek) eof()
            assertEquals("AE-03 eofBeforeSeek=$eofBeforeSeek", seekOwner, fixture.environment.lastSelected())
            assertEquals(token.value, seekOwner.selectionToken)
            assertEquals(if (eofBeforeSeek) 1 else 0,
                fixture.events.filterIsInstance<NativeEngineControllerEvent.AudibleEnd>().size)
            println("AUDIO_ORDER seek-eof-cold-opus eof-before-seek=$eofBeforeSeek " +
                "outgoing=$outgoing seekOwner=$seekOwner operations=${fixture.environment.operations}")
            fixture.controller.stopForTaskRemoval(); fixture.runTurns(8)
        }
    }

    @Test fun tuneAndMixBeforeAndAfterPreparedSourceRetainItsExactIntent() {
        for (controlsBeforeReady in listOf(false, true)) {
            val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
            val token = fixture.controller.select(request("prepared-controls", autoplay = false))
            fixture.scheduler.runNext(); fixture.presentLatest()
            val owner = requireNotNull(fixture.environment.lastSelected())
            val lanes = currentStems("prepared-controls")
            assertTrue(fixture.controller.updateDesired(token) { it.copy(sourceMode = EngineSourceMode.STEMS,
                stemLanes = lanes, stemRevision = "prepared-controls",
                stemControls = lanes.associate { lane -> lane.id to EngineStemControl() }) })
            fixture.runTurns(2)
            val sourceIntent = fixture.environment.snapshots.last().controls.sourceIntentRevision
            fun controls() {
                assertTrue(fixture.controller.updateDesired(token) { it.copy(tempo = 1.25f, semitoneShift = 3,
                    stemControls = it.stemControls.mapValues { (_, gain) -> gain.copy(gain = .4f) }) })
                fixture.runTurns(3)
            }
            if (controlsBeforeReady) controls()
            fixture.environment.setNativeStemRenderReadiness(owner, 0L)
            fixture.controller.signalNativeProgress(); fixture.runTurns(3)
            if (!controlsBeforeReady) controls()
            val actual = fixture.environment.snapshots.last().controls
            assertEquals("AE-09 controlsBeforeReady=$controlsBeforeReady", sourceIntent, actual.sourceIntentRevision)
            assertEquals(NativeRuntimeSourceMode.STEMS, actual.sourceMode)
            assertEquals(1.25f, actual.tempo)
            assertEquals(3f, actual.pitchSemitones)
            assertTrue(actual.stems.take(lanes.size).all { it.gain == .4f && !it.muted && !it.soloed })
            assertTrue(fixture.events.any { it is NativeEngineControllerEvent.SourceModeReadyAwaitingAudible &&
                it.desiredRevision == sourceIntent && it.sourceMode == EngineSourceMode.STEMS })
            println("AUDIO_ORDER controls-prepared-handoff controls-before-ready=$controlsBeforeReady " +
                "owner=$owner controls=$actual")
            fixture.controller.stopForTaskRemoval(); fixture.runTurns(8)
        }
    }

    private fun trace(seed: Int): List<Action> = buildList {
        addAll(listOf(Action.SELECT_A, Action.ACTOR, Action.PRESENT, Action.SELECT_B,
            Action.SELECT_C, Action.ACTOR, Action.PRESENT, Action.PAUSE, Action.ACTOR))
        addAll(listOf(Action.SEEK, Action.ROUTE, Action.LOOP, Action.CLEAR, Action.TUNE, Action.MIX, Action.ACTOR))
        val random = Random(seed)
        repeat(profile.getInt("generatedActions")) { add(Action.entries[random.nextInt(Action.entries.size)]) }
        add(Action.ACTOR)
    }

    private fun failure(actions: List<Action>, dropPause: Boolean = false, diagnostics: Boolean = false): String? {
        val fixture = Fixture()
        var latest: EngineSelectionToken? = null
        var playing = false
        var routeAwaitingPresentation = false
        val steps = profile.getInt("maximumActorSteps").also { require(it > 0) }
        var failure: String? = null
        for (action in actions) {
            when (action) {
                Action.SELECT_A, Action.SELECT_B, Action.SELECT_C -> {
                    latest = fixture.controller.select(request(action.name).copy(forceReload = true))
                    playing = true
                }
                Action.PLAY, Action.PAUSE -> latest?.let { token ->
                    playing = action == Action.PLAY
                    if (!(dropPause && action == Action.PAUSE))
                        fixture.controller.updateDesired(token) { it.copy(playWhenReady = playing) }
                }
                Action.STOP -> {
                    fixture.controller.stopForTaskRemoval(); latest = null; playing = false
                    routeAwaitingPresentation = false
                }
                Action.SEEK -> latest?.let { token -> fixture.controller.updateDesired(token) {
                    it.copy(requestedPositionMs = 1_500L, seekRevision = it.seekRevision + 1L)
                } }
                Action.ROUTE -> if (latest != null && fixture.environment.lastSelected()?.selectionToken == latest?.value) {
                    fixture.controller.requestRouteRecovery()
                    routeAwaitingPresentation = true
                }
                Action.LOOP, Action.CLEAR -> latest?.takeIf {
                    fixture.environment.lastSelected()?.selectionToken == it.value
                }?.let { fixture.setLoop(it, if (action == Action.LOOP) EngineLoopConfig(100L, 800L) else null) }
                Action.TUNE -> latest?.let { token -> fixture.controller.updateDesired(token) {
                    it.copy(tempo = 1.25f, semitoneShift = 3)
                } }
                Action.MIX -> latest?.let { token -> fixture.controller.updateDesired(token) {
                    val lanes = currentStems()
                    it.copy(stemLanes = lanes, stemRevision = "trace-mix",
                        stemControls = lanes.associate { lane -> lane.id to EngineStemControl(gain = .4f) })
                } }
                Action.PRESENT -> fixture.environment.lastSelected()?.let { owner ->
                    fixture.environment.present(owner, 0L)
                    if (owner.selectionToken == latest?.value) routeAwaitingPresentation = false
                    fixture.controller.signalNativeProgress()
                    if (fixture.scheduler.activeTaskCount() > 0) fixture.scheduler.runNext()
                }
                Action.ACTOR -> {
                    repeat(steps) { if (fixture.scheduler.activeTaskCount() > 0) fixture.scheduler.runNext() }
                    val selected = fixture.environment.lastSelected()
                    if (selected?.selectionToken != latest?.value) failure = "AE-05 latest owner"
                    // A recreated route may hold Play until fresh presentation; Pause always forbids output.
                    else if (latest != null && (!playing || !routeAwaitingPresentation) &&
                        fixture.environment.snapshots.lastOrNull()?.playing != playing)
                        failure = "AE-14 transport"
                }
            }
            if (failure != null) break
        }
        if (failure != null && diagnostics) println("AUDIO_REPLAY_FAILURE invariant=$failure " +
            "expectedToken=$latest expectedPlaying=$playing routePending=$routeAwaitingPresentation " +
            "snapshot=${fixture.environment.snapshots.lastOrNull()} operations=${fixture.environment.operations}")
        fixture.controller.stopForTaskRemoval()
        repeat(steps) { if (fixture.scheduler.activeTaskCount() > 0) fixture.scheduler.runNext() }
        return failure
    }
}
