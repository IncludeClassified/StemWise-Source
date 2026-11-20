#include "NativePlaybackRuntimeTestSupport.h"
#include "CurrentSongPcmCacheTestHooks.h"

namespace {

void deadlineFirstIngressPlanIsBoundedFairAndSelectedOriginalFirst() {
    constexpr std::size_t selectedBank = 1U;
    constexpr std::uint32_t pullsPerLane = 4U;
    const NativePlaybackIngressServicePlan plan(selectedBank, pullsPerLane);
    require(plan.valid(), "deadline-first service plan valid");
    require(
        plan.actionCount() ==
            1U + 2U * pullsPerLane * kNativePlaybackBankCount * kSongRenderLaneCount,
        "deadline-first service plan is statically bounded");

    std::array<std::array<std::uint32_t, kSongRenderLaneCount>,
        kNativePlaybackBankCount> pulls{};
    for (std::size_t actionIndex = 0; actionIndex < plan.actionCount(); ++actionIndex) {
        const NativePlaybackWorkerServiceAction action = plan.actionAt(actionIndex);
        if ((actionIndex & 1U) == 0U) {
            require(
                action.kind == NativePlaybackWorkerServiceKind::CurrentOutput,
                "current output runs first and after every ingress pull");
            continue;
        }

        require(
            action.kind == NativePlaybackWorkerServiceKind::IngressLane,
            "odd service action is one bounded ingress pull");
        require(action.bank < kNativePlaybackBankCount, "service bank is bounded");
        require(action.lane < kSongRenderLaneCount, "service lane is bounded");
        require(action.pullRound < pullsPerLane, "service pull round is bounded");
        require(
            plan.actionAt(actionIndex - 1U).kind ==
                NativePlaybackWorkerServiceKind::CurrentOutput &&
            plan.actionAt(actionIndex + 1U).kind ==
                NativePlaybackWorkerServiceKind::CurrentOutput,
            "no two ingress pulls can run without an audible-output service point");
        ++pulls[action.bank][action.lane];
    }

    for (std::size_t bank = 0; bank < kNativePlaybackBankCount; ++bank) {
        for (std::size_t lane = 0; lane < kSongRenderLaneCount; ++lane) {
            require(
                pulls[bank][lane] == pullsPerLane,
                "round service preserves equal bounded throughput for every lane");
        }
    }

    for (std::size_t ordinal = 0; ordinal < 2U * kSongRenderLaneCount; ++ordinal) {
        const NativePlaybackWorkerServiceAction action = plan.actionAt(1U + ordinal * 2U);
        const std::size_t expectedBank = (ordinal % kNativePlaybackBankCount) == 0U
            ? selectedBank
            : 1U - selectedBank;
        require(action.pullRound == 0U, "every lane receives one pull before round two");
        require(action.bank == expectedBank, "each lane services selected before prewarm bank");
        require(
            action.lane == ordinal / kNativePlaybackBankCount,
            "both Originals precede stable selected/prewarm stem pairs");
    }
    const NativePlaybackWorkerServiceAction firstIngress = plan.actionAt(1U);
    require(
        firstIngress.bank == selectedBank && firstIngress.lane == 0U,
        "selected Original is the first ingress service");
    const NativePlaybackWorkerServiceAction secondIngress = plan.actionAt(3U);
    require(
        secondIngress.bank == 1U - selectedBank && secondIngress.lane == 0U,
        "prewarm Original is ready before either bank spends work on stems");
}

void outputReserveUsesFiftyMillisecondFloorEndToEnd() {
    NativePlaybackRuntimeConfiguration configuration{};
    require(
        configuration.minimumOutputReserveMicros == 50'000U,
        "native and Android production defaults use the same 50 ms reserve");
    require(
        configuration.minimumOutputReserveFrames() == 2'400U,
        "50 ms at 48 kHz is exactly 2400 frames");
    require(
        configuration.desiredOutputFrames(96U) == 2'400U,
        "time reserve wins over six small device bursts");
    require(
        configuration.desiredOutputFrames(400U) == 2'400U,
        "equal burst-count and time reserves preserve the exact floor");
    require(
        configuration.desiredOutputFrames(401U) == 2'406U,
        "larger burst-count reserve remains authoritative");

    SongLaneIngressBank first(4'096U, 480U);
    SongLaneIngressBank second(4'096U, 480U);
    FakeOutput output(4'096U, 96);
    const auto rejected = [&] (NativePlaybackRuntimeConfiguration candidate) {
        try {
            static_cast<void>(NativePlaybackRuntime(candidate, first, second, output.port()));
            return false;
        } catch (const std::invalid_argument &) {
            return true;
        }
    };
    configuration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kMinimumOutputReserveMicros - 1U;
    require(rejected(configuration), "native runtime rejects a subminimum time reserve");
    configuration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kMaximumOutputReserveMicros + 1U;
    require(rejected(configuration), "native runtime rejects an excessive time reserve");
    configuration = NativePlaybackRuntimeConfiguration{};
    configuration.maximumPullsPerLanePerCycle =
        NativePlaybackRuntimeConfiguration::kMaximumPullsPerLanePerCycle + 1U;
    require(rejected(configuration), "native runtime rejects an unbounded ingress pull count");

    configuration = runtimeConfiguration(16'384U, 480U);
    configuration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    configureLanes(first, 2U, 1U);
    // The reserve assertion is not a short-input test. Supply enough runway beyond the DSP's
    // retained look-ahead for the full 2,400-frame device reserve to become renderable.
    offerLane(first, 0U, 2U, 0U, 4'096U, false);
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const SongRenderOwnership owner{801U, 2U};
    const std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(
        runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            owner,
            banks,
            controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "reserve-floor command accepted");
    require(runtime.start(), "reserve-floor runtime starts");
    try {
        waitUntil([&] {
            const NativePlaybackRuntimeDiagnostics diagnostic = runtime.diagnostics();
            return diagnostic.targetQueuedFrames == 2'400U && output.queued() == 2'400U;
        }, "runtime maintains exact 50 ms output reserve");
    } catch (const std::exception &) {
        const NativePlaybackRuntimeDiagnostics diagnostic = runtime.diagnostics();
        const std::string evidence =
            "runtime maintains exact 50 ms output reserve: target=" +
            std::to_string(diagnostic.targetQueuedFrames) + " queued=" +
            std::to_string(output.queued()) + " rendered=" +
            std::to_string(diagnostic.outputFramesRendered) + " accepted=" +
            std::to_string(diagnostic.outputFramesAccepted) + " need-data=" +
            std::to_string(diagnostic.outputNeedDataEvents) + " pulls=" +
            std::to_string(diagnostic.banks[0].lanes[0].pulls) + " pulled=" +
            std::to_string(diagnostic.banks[0].lanes[0].pulledFrames) + " appended=" +
            std::to_string(diagnostic.banks[0].lanes[0].appendedFrames);
        stopAndJoin(runtime);
        throw std::runtime_error(evidence);
    }
    stopAndJoin(runtime);
}

void producerStarvationDiagnosticsAreCommittedQueueLowWaterGated() {
    {
        SongLaneIngressBank first(4'096U, 480U);
        SongLaneIngressBank second(4'096U, 480U);
        configureLanes(first, 2U, 1U);
        FakeOutput output;
        NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
        const SongRenderOwnership owner{1'499U, 2U};
        const std::array<NativePlaybackBankIntent, 2U> banks{
            assignedBank(1U, owner), NativePlaybackBankIntent{}};
        require(runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            owner,
            banks,
            controls(1U))) == NativePlaybackPublishStatus::Accepted,
            "uncommitted wait-reason diagnostic command accepted");
        require(runtime.start(), "uncommitted wait-reason diagnostic runtime starts");
        waitUntil([&] { return runtime.diagnostics().outputNeedDataEvents > 32U; },
            "uncommitted requested owner repeatedly reaches NeedOriginal");
        require(
            runtime.diagnostics().needOriginalData.lowWaterEvidenceSequence == 0U,
            "NeedOriginal cannot publish before the requested owner is current and committed");
        stopAndJoin(runtime);
    }

    SongLaneIngressBank first(4'096U, 480U);
    SongLaneIngressBank second(4'096U, 480U);
    constexpr std::uint64_t firstGeneration = 4U;
    constexpr std::uint64_t secondGeneration = 5U;
    constexpr std::uint64_t firstTimelineFrame = 480U;
    constexpr std::size_t firstFrames = 4'096U;
    configureLanes(first, firstGeneration, 1U);
    configureLanes(second, secondGeneration, 1U);
    offerLane(first, 0U, firstGeneration, firstTimelineFrame, firstFrames, false);
    offerLane(second, 0U, secondGeneration, firstTimelineFrame, 4'096U, false);

    FakeOutput output(4'096U, 96);
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    const SongRenderOwnership firstOwner{1'500U, firstGeneration};
    const SongRenderOwnership secondOwner{1'501U, secondGeneration};
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, firstOwner, firstTimelineFrame),
        assignedBank(1U, secondOwner, firstTimelineFrame)};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        firstOwner,
        banks,
        controls(1U, 1.0F, 0.0F, MixerSourceMode::Original))) ==
        NativePlaybackPublishStatus::Accepted,
        "queue-gated NeedOriginal diagnostic command accepted");
    require(runtime.start(), "queue-gated NeedOriginal diagnostic runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "first diagnostic owner stages and commits");
    require(output.presentFirstCurrentRecord(),
        "first diagnostic owner receives exact callback evidence");
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, firstOwner);
    }, "first diagnostic owner becomes runtime-committed");
    waitUntil([&] {
        const auto diagnostics = runtime.diagnostics();
        if (diagnostics.needOriginalData.lowWaterEvidenceSequence >= 1U &&
            diagnostics.needOriginalData.lastMonotonicMicros != 0U &&
            diagnostics.needOriginalData.lastGeneration == firstGeneration &&
            diagnostics.currentOutputQueueLowWater.sequence != 0U &&
            diagnostics.currentOutputQueueLowWater.generation == firstGeneration) {
            return true;
        }
        const std::size_t queued = output.queued();
        if (queued > 64U) {
            static_cast<void>(output.consumeFrames(std::min<std::size_t>(64U, queued - 64U)));
        }
        return false;
    }, "first committed NeedOriginal publishes queue-low-water evidence");

    // The predicate above deliberately drains in 64-frame steps. The worker can already have
    // sampled the predicate's final queue value while its matching publication is still pending.
    // Hold the queue fixed for one complete repeated-NeedOriginal window before retaining the
    // baseline; the next equal window below then proves that same/higher observations stay quiet.
    const std::uint64_t settlingNeedDataBefore = runtime.diagnostics().outputNeedDataEvents;
    waitUntil([&] {
        return runtime.diagnostics().outputNeedDataEvents > settlingNeedDataBefore + 32U;
    }, "first reason low-water settles at one fixed queue depth");
    const auto firstEvidence = runtime.diagnostics();
    const std::size_t firstQueuedFrames = output.queued();
    require(firstQueuedFrames != 0U,
        "same/higher queue test retains nonzero committed output");
    const std::uint64_t repeatedNeedDataBefore = firstEvidence.outputNeedDataEvents;
    waitUntil([&] {
        return runtime.diagnostics().outputNeedDataEvents > repeatedNeedDataBefore + 32U;
    }, "repeated same-queue NeedOriginal results remain observable in legacy aggregate");
    const auto sameQueueEvidence = runtime.diagnostics().needOriginalData;
    require(
        sameQueueEvidence.lowWaterEvidenceSequence ==
            firstEvidence.needOriginalData.lowWaterEvidenceSequence &&
        sameQueueEvidence.lastMonotonicMicros ==
            firstEvidence.needOriginalData.lastMonotonicMicros,
        "repeated same-queue NeedOriginal takes no new timestamp or publication");

    const std::uint64_t higherNeedDataBefore = runtime.diagnostics().outputNeedDataEvents;
    offerLane(
        first,
        0U,
        firstGeneration,
        firstTimelineFrame + firstFrames,
        64U,
        false);
    runtime.notifyIngressAvailable();
    waitUntil([&] {
        return output.queued() > firstQueuedFrames &&
            runtime.diagnostics().outputNeedDataEvents > higherNeedDataBefore;
    }, "refilled owner reaches NeedOriginal above the retained reason low-water");
    const auto higherQueueEvidence = runtime.diagnostics().needOriginalData;
    require(
        higherQueueEvidence.lowWaterEvidenceSequence ==
            firstEvidence.needOriginalData.lowWaterEvidenceSequence &&
        higherQueueEvidence.lastMonotonicMicros ==
            firstEvidence.needOriginalData.lastMonotonicMicros,
        "higher-queue NeedOriginal takes no new timestamp or publication");

    output.consumeAll();
    waitUntil([&] {
        const auto diagnostics = runtime.diagnostics();
        return diagnostics.needOriginalData.lowWaterEvidenceSequence >
                firstEvidence.needOriginalData.lowWaterEvidenceSequence &&
            diagnostics.currentOutputQueueLowWater.generation == firstGeneration &&
            diagnostics.currentOutputQueueLowWater.frames == 0U &&
            diagnostics.needOriginalData.lastMonotonicMicros ==
                diagnostics.currentOutputQueueLowWater.monotonicMicros;
    }, "strictly lower queue advances evidence and reuses the current-output timestamp");
    auto lowerQueueEvidence = runtime.diagnostics().needOriginalData;

    offerLane(first, 0U, firstGeneration,
        firstTimelineFrame + firstFrames + 64U, firstFrames, false);
    runtime.notifyIngressAvailable();
    waitUntil([&] {
        return output.queued() >= runtime.diagnostics().targetQueuedFrames &&
            runtime.diagnostics().targetQueuedFrames != 0U;
    }, "recovered owner restores its healthy output reserve");
    const auto recoveredCycles = runtime.diagnostics().renderCycles;
    waitForRenderProgress(runtime, recoveredCycles,
        "healthy reserve ends the prior starvation episode", 2U);
    const auto recoveredQueued = output.queued();
    require(recoveredQueued > 128U, "recovered owner has output to drain");
    static_cast<void>(output.consumeFrames(recoveredQueued - 128U));
    runtime.notifyIngressAvailable();
    waitUntil([&] {
        const auto reason = runtime.diagnostics().needOriginalData;
        if (reason.lowWaterEvidenceSequence > lowerQueueEvidence.lowWaterEvidenceSequence &&
            reason.lastGeneration == firstGeneration && output.queued() > 0U) return true;
        const auto queued = output.queued();
        if (queued > 128U) static_cast<void>(output.consumeFrames(queued - 128U));
        return false;
    }, "a second starvation episode publishes above the prior zero low-water");
    const auto secondEpisode = runtime.diagnostics();
    require(secondEpisode.needOriginalData.lastMonotonicMicros >
        lowerQueueEvidence.lastMonotonicMicros,
        "the recovered owner's later starvation has a fresh timestamp");
    const auto secondEpisodeNeedCount = secondEpisode.outputNeedDataEvents;
    waitUntil([&] {
        return runtime.diagnostics().outputNeedDataEvents > secondEpisodeNeedCount + 32U;
    }, "second episode repeatedly observes a fixed queue");
    require(runtime.diagnostics().needOriginalData.lowWaterEvidenceSequence ==
        secondEpisode.needOriginalData.lowWaterEvidenceSequence,
        "same queue within the second episode remains quiet");
    lowerQueueEvidence = runtime.diagnostics().needOriginalData;

    require(runtime.publishCommand(selectedCommand(
        2U,
        NativePlaybackBank::Second,
        secondOwner,
        banks,
        controls(2U, 1.0F, 0.0F, MixerSourceMode::Original))) ==
        NativePlaybackPublishStatus::Accepted,
        "second-generation diagnostic command accepted");
    waitUntil([&] { return output.commitCount() == 2U; },
        "second diagnostic owner stages and commits");
    const std::uint64_t stagedRenderCycles = runtime.diagnostics().renderCycles;
    waitForRenderProgress(
        runtime,
        stagedRenderCycles,
        "staged replacement remains uncommitted across later render cycles",
        8U);
    require(
        runtime.diagnostics().needOriginalData.lowWaterEvidenceSequence ==
            lowerQueueEvidence.lowWaterEvidenceSequence,
        "staged replacement cannot publish committed-output wait evidence");
    require(output.presentFirstCurrentRecord(),
        "second diagnostic owner receives exact callback evidence");
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, secondOwner);
    }, "second diagnostic owner becomes runtime-committed");
    waitUntil([&] {
        const auto diagnostics = runtime.diagnostics();
        if (diagnostics.needOriginalData.lowWaterEvidenceSequence >
                lowerQueueEvidence.lowWaterEvidenceSequence &&
            diagnostics.needOriginalData.lastGeneration == secondGeneration) {
            return true;
        }
        const std::size_t queued = output.queued();
        if (queued > 64U) {
            static_cast<void>(output.consumeFrames(std::min<std::size_t>(64U, queued - 64U)));
        }
        return false;
    }, "new generation publishes reason evidence after becoming current");
    const auto generationResetEvidence = runtime.diagnostics();
    require(
        generationResetEvidence.needOriginalData.lowWaterEvidenceSequence >
            lowerQueueEvidence.lowWaterEvidenceSequence,
        "new generation advances the reason low-water sequence");
    require(
        generationResetEvidence.currentOutputQueueLowWater.generation == secondGeneration,
        "new generation owns the current-output low-water publication");
    require(
        generationResetEvidence.currentOutputQueueLowWater.frames != 0U,
        "new generation resets the reason low-water above the prior zero");

    const auto diagnostics = runtime.diagnostics();
    require(diagnostics.needStemData.lowWaterEvidenceSequence == 0U,
        "Original starvation cannot publish NeedStem diagnostics");
    require(diagnostics.loopBoundaryPending.lowWaterEvidenceSequence == 0U,
        "Original starvation cannot publish LoopBoundary diagnostics");
    stopAndJoin(runtime);
}

void twoInstancesAndSteadyControlPressureStayIndependentAndAllocationFree() {
    SongLaneIngressBank firstA(4'096, 480);
    SongLaneIngressBank secondA(4'096, 480);
    SongLaneIngressBank firstB(4'096, 480);
    SongLaneIngressBank secondB(4'096, 480);
    configureLanes(firstA, 2, 1);
    configureLanes(secondA, 3, 1);
    configureLanes(firstB, 20, 1);
    configureLanes(secondB, 21, 1);
    offerLane(firstA, 0, 2, 0, 2'400, false);
    offerLane(firstB, 0, 20, 0, 2'400, false);
    FakeOutput outputA;
    FakeOutput outputB;
    NativePlaybackRuntime runtimeA(runtimeConfiguration(), firstA, secondA, outputA.port());
    NativePlaybackRuntime runtimeB(runtimeConfiguration(), firstB, secondB, outputB.port());
    const NativePlaybackRuntimeDiagnostics twoRuntimeEvidence = runtimeA.diagnostics();
    require(twoRuntimeEvidence.processLiveInstanceCount == 2U,
        "process-global diagnostics expose both live runtimes");
    require(twoRuntimeEvidence.processConstructionCount >= 2U,
        "process-global runtime construction evidence is cumulative");
    require(runtimeB.diagnostics().processLiveInstanceCount ==
            twoRuntimeEvidence.processLiveInstanceCount,
        "all runtimes observe the same process-global live count");
    std::array<NativePlaybackBankIntent, 2> banksA{
        assignedBank(1, {600, 2}), NativePlaybackBankIntent{}};
    std::array<NativePlaybackBankIntent, 2> banksB{
        assignedBank(1, {700, 20}), NativePlaybackBankIntent{}};
    require(runtimeA.publishCommand(selectedCommand(
        1, NativePlaybackBank::First, {600, 2}, banksA, controls(1))) ==
        NativePlaybackPublishStatus::Accepted, "instance A command");
    require(runtimeB.publishCommand(selectedCommand(
        1, NativePlaybackBank::First, {700, 20}, banksB, controls(1))) ==
        NativePlaybackPublishStatus::Accepted, "instance B command");
    require(runtimeA.start() && runtimeB.start(), "two instances start");
    waitUntil([&] {
        return runtimeA.diagnostics().outputFramesAccepted != 0U &&
            runtimeB.diagnostics().outputFramesAccepted != 0U;
    }, "two instances render");

    const std::uint64_t allocationsBefore = allocation_probe::count.load(
        std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    [[maybe_unused]] const auto allocationProbeCleanup = onScopeExit([]() noexcept {
        allocation_probe::enabled.store(false, std::memory_order_release);
    });
    for (std::uint64_t revision = 2; revision <= 1'001; ++revision) {
        const float tempoValues[4]{0.5F, 0.9F, 1.0F, 2.0F};
        const float pitchValues[4]{-12.0F, -4.0F, 4.0F, 12.0F};
        const std::size_t choice = static_cast<std::size_t>(revision & 3U);
        const auto command = selectedCommand(
            revision,
            NativePlaybackBank::First,
            {600, 2},
            banksA,
            controls(revision, tempoValues[choice], pitchValues[choice]));
        const auto status = runtimeA.publishCommand(command);
        if (status != NativePlaybackPublishStatus::Accepted) {
            allocation_probe::enabled.store(false, std::memory_order_release);
            throw std::runtime_error("steady command rejected");
        }
        outputA.consumeAll();
    }
    waitUntil([&] {
        const NativePlaybackRuntimeDiagnostics diagnostic = runtimeA.diagnostics();
        return diagnostic.commandSnapshotsPublished == 1'001U &&
            diagnostic.commandSnapshotsConsumed + diagnostic.commandSnapshotsSuperseded ==
                diagnostic.commandSnapshotsPublished;
    }, "final allocation-pressure command leaves no pending mailbox state", 8'000U);
    allocation_probe::enabled.store(false, std::memory_order_release);
    require(
        allocation_probe::count.load(std::memory_order_relaxed) == allocationsBefore,
        "steady runtime allocated memory");
    require(runtimeA.diagnostics().requestedOwnership.selectionToken == 600U,
        "instance A ownership stable");
    require(runtimeB.diagnostics().requestedOwnership.selectionToken == 700U,
        "instance B ownership independent");
    stopAndJoin(runtimeA);
    stopAndJoin(runtimeB);
}

void pausedIdleParksUntilExplicitWake() {
    SongLaneIngressBank first(256, 64);
    SongLaneIngressBank second(256, 64);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    require(runtime.start(), "paused-idle runtime starts");
    waitUntil([&] {
        return runtime.diagnostics().renderCycles > 0U;
    }, "paused-idle worker reaches its first park");
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    const std::uint64_t parkedCycles = runtime.diagnostics().renderCycles;
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    require(runtime.diagnostics().renderCycles == parkedCycles,
        "paused-idle worker performs no timer-driven render cycles");

    runtime.notifyIngressAvailable();
    waitUntil([&] {
        return runtime.diagnostics().renderCycles > parkedCycles;
    }, "paused-idle worker wakes immediately for explicit work");
    stopAndJoin(runtime);
}

void rejectedPlayingUsesBoundedIdleRetryAndRetainsPcm() {
    constexpr SongRenderOwnership owner{2'079U, 2U};
    SongLaneIngressBank first(16'384U, 480U), second(16'384U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 8'192U);
    FakeOutput output;
    output.rejectPlay.store(true);
    output.setAutoAcknowledgePausedCommit(true);
    auto configuration = runtimeConfiguration();
    configuration.idleSleepMicros = 10'000U;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        owner, banks, controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "rejected-start selection accepted");
    require(runtime.start(), "rejected-start worker starts");
    waitUntil([&] { return output.playAttempts.load() != 0U; }, "start attempted");
    const auto before = runtime.diagnostics();
    const auto attempts = output.playAttempts.load();
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    const auto after = runtime.diagnostics();
    require(output.playAttempts.load() - attempts < 30U && after.idleSleeps > before.idleSleeps,
        "rejected Play sleeps between bounded retries");
    require(output.queued() != 0U && !output.playing(), "rejected Play retains staged PCM");
    output.rejectPlay.store(false);
    runtime.notifyIngressAvailable();
    waitUntil([&] { return output.playing(); }, "subsequent start succeeds");
    require(output.presentFirstCurrentRecord(), "start retry presents retained PCM");
    waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, owner); },
        "start retry never replaces the accepted owner");
    stopAndJoin(runtime);
}

void benchmarkPlayingWorkerCadence() {
#ifdef _WIN32
    require(timeBeginPeriod(1U) == TIMERR_NOERROR, "benchmark timer resolution");
    const auto timer = onScopeExit([] { timeEndPeriod(1U); });
#endif
    constexpr SongRenderOwnership owner{2'081U, 2U};
    SongLaneIngressBank first(131'072U, 480U);
    SongLaneIngressBank second(131'072U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 120'000U);
    FakeOutput output(4'096U, 96U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntimeConfiguration config{};
    NativePlaybackRuntime runtime(config, first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner, banks,
                                                  controls(1U))) == NativePlaybackPublishStatus::Accepted,
            "cadence selection accepted");
    require(runtime.start(), "cadence worker starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "cadence output committed");
    require(output.presentFirstCurrentRecord(), "cadence owner presented");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask ==
                           (1U << kLaneCount) - 1U; }, "cadence cache ready", 8'000U);
    const auto before = runtime.diagnostics();
    const auto recordsBefore = output.recordCount();
    std::size_t minimumQueued = output.queued();
    auto deadline = std::chrono::steady_clock::now();
    for (unsigned tick = 0U; tick < 200U; ++tick) {
        deadline += std::chrono::milliseconds(10);
        std::this_thread::sleep_until(deadline);
        minimumQueued = std::min(minimumQueued, output.queued());
        require(output.consumeFrames(480U) == 480U, "10 ms drain never underruns");
    }
    const auto after = runtime.diagnostics();
    const auto recordsAfter = output.recordCount();
    const auto editStart = std::chrono::steady_clock::now();
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, true,
                                            128U, 110'000U) == NativePlaybackPublishStatus::Accepted,
            "cadence urgent loop accepted");
    while (output.loopCutoverCommitCount() == 0U &&
           std::chrono::steady_clock::now() - editStart < std::chrono::seconds(1)) {
        std::this_thread::yield();
    }
    require(output.loopCutoverCommitCount() == 1U, "cadence urgent loop prepared");
    const auto loopMicros = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - editStart).count();
    std::cout << "playing_ms=2000 cycles=" << after.renderCycles - before.renderCycles
              << " sleeps=" << after.idleSleeps - before.idleSleeps
              << " blocks=" << recordsAfter - recordsBefore
              << " minimum_queued=" << minimumQueued << " loop_prepare_us=" << loopMicros << '\n';
    stopAndJoin(runtime);
}

void processLifecycleCountersReturnAfterScopedRuntimeDestruction() {
    SongLaneIngressBank firstA(256, 64);
    SongLaneIngressBank secondA(256, 64);
    FakeOutput outputA;
    NativePlaybackRuntime runtimeA(runtimeConfiguration(), firstA, secondA, outputA.port());
    const NativePlaybackRuntimeDiagnostics oneRuntime = runtimeA.diagnostics();
    require(oneRuntime.processLiveInstanceCount == 1U,
        "prior scoped runtimes leave no process-global live leak");

    {
        SongLaneIngressBank firstB(256, 64);
        SongLaneIngressBank secondB(256, 64);
        FakeOutput outputB;
        NativePlaybackRuntime runtimeB(runtimeConfiguration(), firstB, secondB, outputB.port());
        const NativePlaybackRuntimeDiagnostics twoRuntimes = runtimeA.diagnostics();
        require(twoRuntimes.processConstructionCount ==
                oneRuntime.processConstructionCount + 1U,
            "constructing a runtime advances process-global construction evidence");
        require(twoRuntimes.processLiveInstanceCount == 2U,
            "constructing a second runtime advances process-global live evidence");
        require(runtimeB.diagnostics().processLiveInstanceCount == 2U,
            "second runtime observes the shared live evidence");
        require(
            twoRuntimes.processOutputInvalidRenderResults ==
                oneRuntime.processOutputInvalidRenderResults &&
                twoRuntimes.processOutputFlushFailures ==
                    oneRuntime.processOutputFlushFailures,
            "constructing a runtime cannot reset or manufacture process fault evidence");
    }

    const NativePlaybackRuntimeDiagnostics afterDestruction = runtimeA.diagnostics();
    require(afterDestruction.processConstructionCount ==
            oneRuntime.processConstructionCount + 1U,
        "runtime destruction retains cumulative construction evidence");
    require(afterDestruction.processLiveInstanceCount == 1U,
        "runtime destruction releases process-global live evidence");
    require(
        afterDestruction.processOutputInvalidRenderResults ==
            oneRuntime.processOutputInvalidRenderResults &&
            afterDestruction.processOutputFlushFailures ==
                oneRuntime.processOutputFlushFailures,
        "runtime destruction retains both process-cumulative fault ledgers");
}

#include "NativePlaybackRuntimeLateControlsCases.inc"
#include "NativePlaybackRuntimeHandoffCases.inc"
#include "NativePlaybackRuntimeLoopContinuationCases.inc"
#include "NativePlaybackRuntimeCacheLifecycleCases.inc"
#include "NativePlaybackResourcesCases.inc"

void clearedDirectRoleRetainsItsPhysicalIdentityUntilOwnerRetirement(bool automaticAdvance = false) {
    constexpr SongRenderOwnership owner{2'190U, 2U}, nextOwner{2'191U, 4U};
    SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);
    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    output.setLoopCutoverRequiredFrames(1'536U);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    std::array<NativePlaybackBankIntent, 2U> banks{assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner, banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted, "Clear identity owner selected");
    require(runtime.start(), "Clear identity runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "Clear identity owner commits");
    require(output.presentFirstCurrentRecord(), "Clear identity callback presents owner");
    waitUntil([&] {
        const auto ready = runtime.renderReadinessDiagnostics();
        return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 127U;
    }, "Clear identity canonical PCM ready");
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, true, 128U, 1'536U) ==
        NativePlaybackPublishStatus::Accepted, "Clear identity loop accepted");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; }, "Clear identity loop commits");
    const auto enabled = output.acceptedLoopPolicy();
    require(enabled.enabled && enabled.physicalRevision() != 0U, "Clear identity has a real enabled role");
    require(output.presentCommittedLoopCutover(), "Clear identity callback adopts loop");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 1U; }, "Clear identity enabled producer adopted");
    require(runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation, false, 0U, 0U) ==
        NativePlaybackPublishStatus::Accepted, "Clear identity disable accepted");
    NativeAudioOutputLoopPolicy disabled{};
    waitUntil([&] {
        const auto policy = output.acceptedLoopPolicy();
        if (policy.revision != 2U || policy.enabled || !policy.physicalReady ||
            !output.observeCommittedLoopCutover(policy.physicalRevision())) return false;
        disabled = policy;
        return true;
    }, "Clear identity linear replacement commits");
    require(disabled.physicalRevision() > enabled.physicalRevision(), "Clear installs a distinct physical role");
    require(output.presentCommittedLoopCutover(), "Clear callback adopts linear replacement");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 2U; }, "Clear linear producer adopted");
    const auto afterAdoption = output.recordCount();
    output.consumeAll();
    const auto linear = waitForRecord(output, afterAdoption, [=](const FakeOutputRecord &record) {
        return record.generation == owner.generation && record.token == owner.selectionToken && !record.loopEnabled;
    }, "Clear active producer continues linearly");
    const auto continued = output.record(linear);
    require(continued.loopPolicyRevision == disabled.physicalRevision(),
        "Linear PCM must retain the callback's accepted disabled physical identity");
    require(continued.loopRevision == 0U && continued.loopCycle == 0U && continued.frames != 0U &&
        continued.sourceEnd > continued.sourceStart && continued.peak > 0.0F,
        "Retained physical identity does not fabricate a loop or empty PCM");
    require(output.presentRecord(linear), "Clear callback presents continued linear PCM");
    waitUntil([&] { return runtime.diagnostics().presentedPosition.loopPolicyRevision == disabled.physicalRevision(); },
        "Clear continued presentation preserves disabled role identity");

    if (automaticAdvance) {
        configureLanes(second, nextOwner.generation);
        offerAllLanes(second, nextOwner.generation, 16'384U);
        banks[1] = assignedBank(1U, nextOwner);
        auto automatic = selectedCommand(2U, NativePlaybackBank::First, owner, banks, controls(2U));
        automatic.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
            true, false, 77U, owner, NativePlaybackBank::Second, nextOwner};
        const auto successorBegin = output.recordCount();
        require(runtime.publishCommand(automatic) == NativePlaybackPublishStatus::Accepted,
            "Clear automatic successor accepted");
        waitUntil([&] {
            static_cast<void>(output.consumeCallback(256U));
            runtime.notifyIngressAvailable();
            return sameOwnership(runtime.diagnostics().committedOwnership, nextOwner);
        }, "Clear automatic successor becomes audible");
        const auto refillBegin = output.recordCount();
        output.consumeAll();
        waitForRecord(output, refillBegin, [=](const FakeOutputRecord &record) {
            return record.generation == nextOwner.generation;
        }, "Clear automatic successor refills after presentation");
        for (auto index = successorBegin; index < output.recordCount(); ++index) {
            const auto record = output.record(index);
            if (record.generation != nextOwner.generation) continue;
            require(record.loopPolicyRevision == 0U && record.loopRevision == 0U && !record.loopEnabled,
                "Automatic successor prefill and refill cannot inherit outgoing physical loop identity");
        }
        require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::Second, nextOwner,
            banks, controls(3U))) == NativePlaybackPublishStatus::Accepted, "Clear successor selection accepted");
        require(runtime.publishDirectLoopPolicy(3U, nextOwner.selectionToken, nextOwner.generation,
            true, 8'192U, 12'288U) == NativePlaybackPublishStatus::Accepted,
            "Automatic successor can enable its own loop");
        waitUntil([&] {
            const auto policy = output.acceptedLoopPolicy();
            return policy.generation == nextOwner.generation && policy.enabled && policy.physicalReady;
        }, "Automatic successor prepares its own loop");
        require(output.acceptedLoopPolicy().cutoverSourceRevision == 0U,
            "Automatic successor's first loop replaces its own linear source revision zero");
        stopAndJoin(runtime);
        return;
    }

    require(runtime.publishDirectLoopPolicy(3U, owner.selectionToken, owner.generation, true, 8'192U, 12'288U) ==
        NativePlaybackPublishStatus::Accepted, "Clear role can be re-enabled");
    NativeAudioOutputLoopPolicy reenabled{};
    waitUntil([&] {
        const auto policy = output.acceptedLoopPolicy();
        if (policy.revision != 3U || !policy.enabled || !policy.physicalReady ||
            !output.observeCommittedLoopCutover(policy.physicalRevision())) return false;
        reenabled = policy;
        return true;
    }, "Clear re-enable commits its exact replacement");
    require(reenabled.physicalRevision() > disabled.physicalRevision() &&
        reenabled.cutoverSourceRevision == disabled.physicalRevision(),
        "Re-enable replaces the actual disabled physical source");
    require(output.presentCommittedLoopCutover(), "Clear re-enable callback adopts replacement");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 3U; }, "Clear re-enabled producer adopted");
    banks[0] = NativePlaybackBankIntent{.revision = 2U};
    require(runtime.publishCommand(unselectedCommand(2U, 3U, banks, controls(2U))) ==
        NativePlaybackPublishStatus::Accepted, "Clear identity owner unload accepted");
    waitUntil([&] { return runtime.diagnostics().requestedOwnership.generation == 0U && !output.playing() && output.queued() == 0U; },
        "Unload retires the exact physical owner");
    configureLanes(second, nextOwner.generation);
    offerAllLanes(second, nextOwner.generation, 8'192U);
    banks[1] = assignedBank(1U, nextOwner);
    const auto nextStart = output.recordCount();
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::Second, nextOwner, banks,
        controls(3U))) == NativePlaybackPublishStatus::Accepted, "New owner selected after Clear/unload");
    const auto nextRecord = waitForRecord(output, nextStart, [=](const FakeOutputRecord &record) {
        return record.generation == nextOwner.generation && record.token == nextOwner.selectionToken;
    }, "New owner produces exact PCM");
    require(output.record(nextRecord).loopPolicyRevision == 0U && !output.record(nextRecord).loopEnabled,
        "New owner cannot inherit the retired physical role");
    stopAndJoin(runtime);
}

void measureUnusedPipelineConstruction() {
    SongLaneIngressBank first(8'192U, 480U), second(8'192U, 480U);
    FakeOutput output;
    auto config = NativePlaybackRuntimeConfiguration{};
    const auto before = allocation_probe::liveBytes.load();
    allocation_probe::peakBytes.store(before);
    const auto allocations = allocation_probe::count.load();
    const auto start = std::chrono::steady_clock::now();
    allocation_probe::enabled.store(true);
    {
        NativePlaybackRuntime runtime(config, first, second, output.port());
        allocation_probe::enabled.store(false);
        std::cout << "runtime-construction owned-live-bytes=" << allocation_probe::liveBytes.load() - before
            << " owned-peak-bytes=" << allocation_probe::peakBytes.load() - before
            << " allocations=" << allocation_probe::count.load() - allocations
            << " setup-us=" << std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count() << '\n';
    }
    require(allocation_probe::liveBytes.load() == before, "measured runtime teardown retained owned allocations");
}

} // namespace

int main(int argumentCount, char **arguments) {
    struct Case { std::string_view selector; void (*run)(); };
    const Case cases[]{
#define STEMWISE_FOCUSED_CASE(selector, ...) {selector, __VA_ARGS__},
#include "NativePlaybackRuntimeCases.def"
#undef STEMWISE_FOCUSED_CASE
    };
    try {
        if (argumentCount == 2 && std::string_view(arguments[1]) == "--list-cases") {
            for (const auto &test : cases) std::cout << test.selector << '\n';
            return EXIT_SUCCESS;
        }
        if (argumentCount == 2 && std::string_view(arguments[1]) == "--benchmark-pipeline-memory") {
            measureUnusedPipelineConstruction();
            return 0;
        }

        if (argumentCount == 2 && std::string_view(arguments[1]) == "--benchmark-evidence") {
            benchmarkControlEvidence = true;
            audibleControlEvidenceRequiresAWholeSettledCallbackAndExactOwner();
            return 0;
        }

        if (argumentCount == 2 && std::string_view(arguments[1]) == "--benchmark-direct-loop-responsiveness") {
            loopTimingQualification = true;
            for (unsigned sample = 0U; sample != 20U; ++sample) rapidBOnlyLoopBurstReusesPreparedA();
            require(loopTimingSampleCount == 80U, "direct-loop performance sampling was incomplete");
            std::sort(loopTimingSamples.begin(), loopTimingSamples.begin() + loopTimingSampleCount);
            const auto percentile95 = loopTimingSamples[loopTimingSampleCount * 95U / 100U];
            std::cout << "HOST_PERFORMANCE direct-loop samples=" << loopTimingSampleCount
                << " p50_us=" << loopTimingSamples[loopTimingSampleCount / 2U]
                << " p95_us=" << percentile95 << " max_us=" << loopTimingSamples[loopTimingSampleCount - 1U] << '\n';
            require(percentile95 < 10'000U, "sustained direct-loop responsiveness exceeds host 10ms budget");
            return EXIT_SUCCESS;
        }

        if (argumentCount == 2 && std::string_view(arguments[1]) == "--benchmark-cadence") {
            benchmarkPlayingWorkerCadence();
            return EXIT_SUCCESS;
        }
        if (argumentCount == 2) {
            for (const auto &test : cases) {
                if (std::string_view(arguments[1]) == test.selector) {
                    test.run();
                    return EXIT_SUCCESS;
                }
            }
        }
        require(argumentCount == 1, "unknown focused test argument");
        for (const auto &test : cases) test.run();
        std::cout << "NativePlaybackRuntime cases passed\n";
        return EXIT_SUCCESS;
    } catch (const std::exception &error) {
        allocation_probe::enabled.store(false, std::memory_order_release);
        std::cerr << "NativePlaybackRuntime test failure: " << error.what() << '\n';
        std::_Exit(EXIT_FAILURE);
    }
}
