void failedLoopResourcePreparationSettlesAndExplicitRevisionRetries(bool lanes = false) {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership owner{28'510U, 2U};
    SongLaneIngressBank first(16'384U, 480U), second(16'384U, 480U);
    configureLanes(first, owner.generation, lanes ? 7U : 1U);
    offerLane(first, 0U, owner.generation, 0U, 16'384U, true);
    FakeOutput output(4'096U, 64U);
    NativePlaybackRuntime runtime(runtimeConfiguration(16'384U, 64U), first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept { fault.store(0); stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, 0U, 0U);
    bank.stemPackageHash = 0U;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        {bank, {}}, controls(1U))) == NativePlaybackPublishStatus::Accepted && runtime.start(), "loop resource failure owner starts");
    waitUntil([&] { return output.commitCount() == 1U &&
        runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 1U; }, "current Original is independent and complete");
    require(output.presentFirstCurrentRecord(), "current Original is actually audible before loop allocation");
    std::uint64_t failedRevision = 1U, priorCommits = 0U;
    if (lanes) {
        require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, true, 512U, 4'800U) ==
            NativePlaybackPublishStatus::Accepted, "Original first prepares its actual zero-Stems spare");
        waitUntil([&] { return output.loopCutoverCommitCount() == 1U; }, "Original spare is actually prepared");
        require(runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation, false, 0U, 0U) ==
            NativePlaybackPublishStatus::Accepted, "unused Original spare is cancelled before source attachment");
        waitUntil([&] { return runtime.renderReadinessDiagnostics().directAtomicPolicyObservedRevision == 2U &&
            !runtime.renderReadinessDiagnostics().fixedLoopHeadPrepared; }, "unused spare cancellation settles");
        for (std::size_t lane = 1U; lane < 7U; ++lane) offerLane(first, lane, owner.generation, 0U, 16'384U, true);
        bank = assignedBank(2U, owner, 0U, 6U);
        require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, owner,
            {bank, {}}, controls(2U))) == NativePlaybackPublishStatus::Accepted, "real active bank admits its six new lanes");
        waitUntil([&] { return runtime.diagnostics().banks[0U].appliedIntentRevision == 2U &&
            runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 127U; }, "active six-lane preparation and cache finish before injected spare failure");
        failedRevision = 3U;
        priorCommits = 1U;
    }
    prepareFailures.store(0U);
    fault.store(10);
    require(runtime.publishDirectLoopPolicy(failedRevision, owner.selectionToken, owner.generation, true, 512U, 4'800U) ==
        NativePlaybackPublishStatus::Accepted, "exact failing loop request accepted");
    waitUntil([&] { return prepareFailures.load() == 1U; }, "actual preparation worker fails its pipeline allocation");
    fault.store(0);
    waitUntil([&] { return runtime.renderReadinessDiagnostics().loopPreparationFailure.revision == failedRevision; },
        "failed exact loop request must settle rather than remain pending forever");
    const auto failure = runtime.controlProgress().readiness.loopPreparationFailure;
    require(sameOwnership(failure.ownership, owner) && failure.reason == (lanes ? NativePlaybackLoopPreparationFailureReason::Lanes : NativePlaybackLoopPreparationFailureReason::Pipeline) &&
        output.playing() && output.loopCutoverCommitCount() == priorCommits && output.commitCount() == 1U,
        "terminal pipeline failure preserves ordinary playback and its exact owner/cause");
    require(runtime.publishDirectLoopPolicy(failedRevision + 1U, owner.selectionToken, owner.generation, true, 512U, 4'800U) ==
        NativePlaybackPublishStatus::Accepted, "explicit retry owns a new loop revision on the same song");
    waitUntil([&] { return output.loopCutoverCommitCount() == priorCommits + 1U; }, "new exact loop request succeeds after allocator recovery");
    require(runtime.renderReadinessDiagnostics().loopPreparationFailure.revision == 0U && prepareFailures.load() == 1U,
        "fresh preparation clears only the superseded terminal failure without spinning the failed request");
}

void unrelatedCommittedHeadCannotBackANewSongsPartialCache(bool matchingHead) {
    constexpr SongRenderOwnership previous{28'500U, 2U}, selected{28'501U, 3U};
    SongLaneIngressBank first(16'384U, 480U), second(16'384U, 480U);
    configureLanes(first, selected.generation, 1U);
    offerLane(first, 0U, selected.generation, 0U, 4'096U, false);
    FakeOutput output(4'096U, 64U);
    NativePlaybackRuntime runtime(runtimeConfiguration(16'384U, 64U), first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept { stopAndJoin(runtime); });
    auto bank = assignedBank(1U, selected, 0U, 0U);
    ++bank.songIdentityHash;
    ++bank.originalContentHash;
    bank.stemPackageHash = 0U;
    bank.cacheFrameUpperBound = 16'384U;
    auto head = managedLoopIntent(1U, matchingHead ? selected : previous, 0U);
    head.boundaryMode = LoopHeadBoundaryMode::RetainedWindow;
    head.cacheKey.endTimelineFrameExclusive = 16'384U;
    if (matchingHead) {
        head.cacheKey.songIdentityHash = bank.songIdentityHash;
        head.cacheKey.originalContentHash = bank.originalContentHash;
    }
    prepareFullManagedLoopHead(runtime, head, 701U, 1U, matchingHead ? selected : previous);
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, selected,
        {bank, {}}, controls(1U))) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "new recording starts alongside a previously committed bounded head");
    waitUntil([&] { return output.commitCount() == 1U &&
        runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner; }, "new song owns its actual canonical prefix");
    require(output.presentFirstCurrentRecord(), "new song is independently presented");
    const auto resourcesBefore = runtime.diagnostics().resourceOperationsCompleted;
    require(runtime.publishDirectLoopPolicy(1U, selected.selectionToken, selected.generation, true, 512U, 12'000U) ==
        NativePlaybackPublishStatus::Accepted, "new song requests coordinates also covered by the retained head");
    if (matchingHead) {
        waitUntil([&] { return output.loopCutoverCommitCount() == 1U; }, "matching recording head prepares an actual continuation");
        require(output.committedLoopCutoverHead().token == selected.selectionToken,
            "validated matching head carries only the selected owner");
    } else {
        waitUntil([&] { return runtime.diagnostics().resourceOperationsCompleted > resourcesBefore; },
            "real asynchronous continuation preparation is serviced");
        waitForRenderProgress(runtime, runtime.diagnostics().renderCycles, "unrelated head is examined by the real render worker", 8U);
        require(output.loopCutoverCommitCount() == 0U,
            "a new song's partial canonical cache must never install an unrelated retained head");
    }
}

void repeatCacheSurvivesCommandRevisionsAndOwnerRebind() {
    SongLaneIngressBank first(16'384U, 480U);
    SongLaneIngressBank second(16'384U, 480U);
    constexpr SongRenderOwnership owner{1'240U, 2U};
    configureLanes(first, owner.generation, 1U);
    offerLane(first, 0U, owner.generation, 0U, 4'800U, true);
    FakeOutput output(4'096U, 64U);
    output.setMaximumAcceptance(64U);
    NativePlaybackRuntime runtime(runtimeConfiguration(16'384U, 64U), first, second, output.port());

    LoopCycleIntent repeat = managedLoopIntent(30U, owner, 0U);
    repeat.cacheKey.loopRevision = 10U;
    repeat.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    prepareShortManagedOriginalEos(runtime, repeat, 400U, 4'800U);
    require(runtime.publishLoopCycleIntent(repeat) == NativePlaybackPublishStatus::Accepted,
        "Repeat command 30 accepts independently prepared cache revision 10");
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "Repeat owner selected");
    require(runtime.start(), "Repeat runtime starts");

    const auto waitForInstalled = [&](std::uint64_t commandRevision,
                                      std::uint64_t cacheRevision,
                                      LoopHeadBoundaryMode boundaryMode,
                                      const char *message) {
        decltype(runtime.loopProtocolSnapshot()) observed{};
        try {
            waitUntil([&] {
                output.consumeAll();
                observed = runtime.loopProtocolSnapshot();
                return observed.adopted &&
                    observed.state.latestRevision == commandRevision &&
                    observed.state.boundaryMode == boundaryMode &&
                    observed.state.cacheAccepted &&
                    observed.activeCache.valid &&
                    observed.activeCache.key.loopRevision == cacheRevision;
            }, "loop command adopts its exact resident cache", 8'000U);
        } catch (const std::exception &) {
            const auto diagnostic = runtime.diagnostics();
            throw std::runtime_error(
                "loop command adopts its exact resident cache: adopted=" +
                std::to_string(observed.adopted) +
                " expected=" + std::to_string(commandRevision) +
                " latest=" + std::to_string(observed.state.latestRevision) +
                " phase=" + std::to_string(static_cast<unsigned>(observed.state.phase)) +
                " expected-boundary=" +
                std::to_string(static_cast<unsigned>(boundaryMode)) +
                " actual-boundary=" +
                std::to_string(static_cast<unsigned>(observed.state.boundaryMode)) +
                " cache-accepted=" + std::to_string(observed.state.cacheAccepted) +
                " active=" + std::to_string(observed.activeCache.valid) +
                " active-revision=" +
                std::to_string(observed.activeCache.key.loopRevision) +
                " requested-generation=" +
                std::to_string(diagnostic.requestedOwnership.generation) +
                " accepted-generation=" +
                std::to_string(diagnostic.acceptedOwnership.generation));
        }
        waitUntil([&] {
            output.consumeAll();
            const auto protocol = runtime.loopProtocolSnapshot();
            return protocol.state.latestRevision == commandRevision &&
                protocol.state.renderInstalled;
        }, message, 8'000U);
    };

    waitForInstalled(
        30U,
        10U,
        LoopHeadBoundaryMode::OriginalEos,
        "Repeat command 30 installs cache revision 10");
    const std::uint64_t repeatPreparationBegins =
        runtime.loopProtocolSnapshot().preparation.beginCalls;
    require(repeatPreparationBegins == 1U,
        "Repeat cache is decoded exactly once before policy toggles");

    LoopCycleIntent disabled{};
    disabled.revision = 31U;
    disabled.owner = repeat.owner;
    require(runtime.publishLoopCycleIntent(disabled) == NativePlaybackPublishStatus::Accepted,
        "command 31 disables Repeat without deleting its cache");
    waitUntil([&] {
        output.consumeAll();
        const auto protocol = runtime.loopProtocolSnapshot();
        return protocol.state.latestRevision == 31U &&
            protocol.state.mode == LoopCycleMode::Disabled &&
            !protocol.state.renderInstalled;
    }, "disabled command clears the installed Repeat renderer");
    require(runtime.loopProtocolSnapshot().preparation.beginCalls == repeatPreparationBegins,
        "Repeat disable performs no decode");

    repeat.revision = 32U;
    require(runtime.publishLoopCycleIntent(repeat) == NativePlaybackPublishStatus::Accepted,
        "command 32 re-enables cache revision 10");
    waitForInstalled(
        32U,
        10U,
        LoopHeadBoundaryMode::OriginalEos,
        "Repeat re-enable reuses cache revision 10");
    require(runtime.loopProtocolSnapshot().preparation.beginCalls == repeatPreparationBegins,
        "Repeat off-on reuses the one decoded cache");

    LoopCycleIntent rebound = repeat;
    rebound.owner.renderGeneration += 1U;
    require(runtime.publishLoopCycleIntent(rebound) == NativePlaybackPublishStatus::Accepted,
        "same command revision accepts a newer owner generation");
    waitUntil([&] {
        const auto protocol = runtime.loopProtocolSnapshot();
        return protocol.state.latestRevision == 32U &&
            protocol.state.owner.renderGeneration == rebound.owner.renderGeneration &&
            protocol.state.cacheAccepted;
    }, "owner rebind retains exact Repeat cache acceptance");
    require(runtime.loopProtocolSnapshot().preparation.beginCalls == repeatPreparationBegins,
        "owner rebind performs no Repeat decode");

    LoopCycleIntent mismatched = repeat;
    mismatched.revision = 35U;
    mismatched.cacheKey.songIdentityHash += 1U;
    require(runtime.publishLoopCycleIntent(mismatched) == NativePlaybackPublishStatus::Accepted,
        "new command may request a distinct cache identity");
    waitUntil([&] {
        const auto protocol = runtime.loopProtocolSnapshot();
        return protocol.state.latestRevision == 35U &&
            !protocol.state.cacheAccepted && !protocol.activeCache.valid;
    }, "mismatched key cannot adopt resident Repeat PCM");

    LoopHeadContentKey stale = repeat.cacheKey;
    stale.loopRevision = 9U;
    require(runtime.beginLoopHeadPreparation(
        stale,
        repeat.requiredLaneMask,
        LoopHeadBoundaryMode::OriginalEos) == LoopHeadPreparationBeginStatus::StaleRevision,
        "stale Repeat cache revision 9 is rejected after revision 10 committed");
    stopAndJoin(runtime);
}

void directLoopDemandWithoutPhysicalCacheFencesNaturalEnd() {
    {
        SongLaneIngressBank first(8'192U, 480U);
        SongLaneIngressBank second(8'192U, 480U);
        constexpr SongRenderOwnership owner{1'248U, 2U};
        configureLanes(first, owner.generation, 1U);
        offerLane(first, 0U, owner.generation, 0U, 4'096U, true);
        FakeOutput output(1'024U, 64U);
        NativePlaybackRuntime runtime(
            runtimeConfiguration(16'384U, 64U),
            first,
            second,
            output.port());
        const std::array<NativePlaybackBankIntent, 2U> banks{
            assignedBank(1U, owner), NativePlaybackBankIntent{}};
        NativePlaybackCommandSnapshot command = selectedCommand(
            1U,
            NativePlaybackBank::First,
            owner,
            banks,
            controls(1U, 1.0F, 0.0F, MixerSourceMode::Stems));
        require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
            "owner selected before direct A-B");
        require(runtime.publishDirectLoopPolicy(
            1U,
            owner.selectionToken,
            owner.generation,
            true,
            0U,
            512U,
            0U,
            true) == NativePlaybackPublishStatus::Accepted,
            "armed direct A-B owns Command-v2 range without an all-lane current-song cache");
        require(runtime.start(), "direct-owner runtime starts");
        waitUntil([&] { return output.commitCount() == 1U; },
            "logical-only direct-loop owner commits");
        waitUntil([&] {
            const NativeAudioOutputLoopPolicy demand = output.lastPublishedLoopDemand();
            return demand.enabled && !demand.physicalReady &&
                demand.generation == owner.generation &&
                demand.selectionToken == owner.selectionToken;
        }, "enabled logical loop demand reaches output without physical readiness");
        require(output.presentFirstCurrentRecord(),
            "logical-only direct-loop owner reaches the callback");
        waitUntil([&] {
            return sameOwnership(runtime.diagnostics().committedOwnership, owner);
        }, "logical-only direct-loop owner becomes audible");
        require(output.playing(),
            "logical-only direct loop starts with output transport playing");

        for (std::size_t iteration = 0U; iteration < 1'000U; ++iteration) {
            const std::uint64_t renderCyclesBeforeDrain = runtime.diagnostics().renderCycles;
            output.consumeAll();
            if (runtime.diagnostics().outputEndOfStreamEvents != 0U) break;
            waitForRenderProgress(
                runtime,
                renderCyclesBeforeDrain,
                "logical-only direct loop advances toward producer EOS");
        }
        waitForRenderProgress(
            runtime,
            runtime.diagnostics().renderCycles,
            "logical-only direct loop observes producer EOS without accepting terminal end",
            4U);
        const auto diagnostic = runtime.diagnostics();
        require(diagnostic.outputEndOfStreamEvents == 1U,
            "logical-only direct loop retains one linear producer EOS");
        require(diagnostic.audibleEnd.sequence == 0U,
            "accepted logical A-B demand cannot publish an audible song end while A stages");
        require(output.terminalSilenceAuthorization() == 0U,
            "accepted logical A-B demand keeps terminal silence fenced while A stages");
        require(output.playing(),
            "accepted logical A-B demand preserves requested Play while A stages");
        for (std::size_t index = 0U; index < output.recordCount(); ++index) {
            const FakeOutputRecord record = output.record(index);
            require(record.loopPolicyRevision == 0U && !record.loopEnabled,
                "logical loop demand without a physical cache never stamps emitted PCM");
        }
        stopAndJoin(runtime);
    }

}

void replacementOwnerCannotResolveRetiredRepeatEos() {
    for (const bool playing : {false, true}) {
        constexpr SongRenderOwnership oldOwner{8'001U, 41U}, nextOwner{8'002U, 42U};
        SongLaneIngressBank first(262'144U, 480U), second(16'384U, 480U);
        configureLanes(first, oldOwner.generation, 1U);
        configureLanes(second, nextOwner.generation, 1U);
        offerLane(first, 0U, oldOwner.generation, 0U, 480U, false);
        offerLane(second, 0U, nextOwner.generation, 0U, 480U, true);
        FakeOutput output(4'096U, 256U);
        NativePlaybackRuntime runtime(runtimeConfiguration(262'144U, 256U), first, second, output.port());
        const auto cleanup = onScopeExit([&]() noexcept { runtime.requestStop(); runtime.join(); });
        auto repeat = managedLoopIntent(11U, oldOwner, 0U);
        prepareManagedLoopOriginal(runtime, repeat, 101U);
        require(runtime.publishLoopCycleIntent(repeat) == NativePlaybackPublishStatus::Accepted, "old unresolved Repeat accepted");
        std::array<NativePlaybackBankIntent, 2U> banks{assignedBank(1U, oldOwner), NativePlaybackBankIntent{}};
        require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, oldOwner, banks, controls(1U), playing)) ==
            NativePlaybackPublishStatus::Accepted, "old Repeat selection accepted");
        require(runtime.start(), "replacement Repeat runtime starts");
        waitUntil([&] { return runtime.loopProtocolSnapshot().state.cacheAccepted; }, "old Repeat cache accepted");
        require(!runtime.loopProtocolSnapshot().state.boundaryResolved, "old Repeat EOS is unresolved");
        banks[1U] = assignedBank(2U, nextOwner);
        const auto before = runtime.diagnostics();
        require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::Second, nextOwner, banks, controls(2U), playing)) ==
            NativePlaybackPublishStatus::Accepted, "replacement short owner accepted before loop-intent retirement");
        waitUntil([&] { return runtime.diagnostics().outputEndOfStreamEvents > before.outputEndOfStreamEvents; },
            "replacement Original EOS reaches the real render pipeline");
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        const auto after = runtime.diagnostics();
        require(runtime.loopProtocolSnapshot().diagnostics.originalEosRejected == 0U,
            "replacement EOS is never submitted as retired Repeat evidence");
        require(after.renderCycles - before.renderCycles < 1'000U && after.idleSleeps > before.idleSleeps,
            "retired Repeat rejection cannot count as progress or spin the refill worker");
        stopAndJoin(runtime);
    }
}

void managedOriginalEosBoundaryComesFromActiveDecoderForExactAndLongSongs(bool playbackWindow = false) {
    const auto decodedEnds = playbackWindow ? std::array<std::uint64_t, 3U>{24'000U, 72'000U, 200'000U} :
        std::array<std::uint64_t, 3U>{72'000U, 192'000U, 200'000U};
    for (std::size_t scenario = 0U; scenario < decodedEnds.size(); ++scenario) {
        const std::uint64_t decodedEnd = decodedEnds[scenario];
        const auto expectedEnd = playbackWindow ? std::min<std::uint64_t>(48'000U, decodedEnd) : decodedEnd;
        const std::uint64_t currentEpoch = 20U + scenario;
        SongLaneIngressBank first(262'144U, 480U);
        SongLaneIngressBank second(262'144U, 480U);
        configureLanes(first, currentEpoch, 1U);
        offerLane(
            first,
            0U,
            currentEpoch,
            0U,
            static_cast<std::size_t>(decodedEnd),
            true);
        FakeOutput output(2'048U, 256U);
        output.setMaximumAcceptance(256U);
        NativePlaybackRuntime runtime(
            runtimeConfiguration(262'144U, 256U),
            first,
            second,
            output.port());
        const auto cleanup = onScopeExit([&]() noexcept { stopAndJoin(runtime); });
        const SongRenderOwnership owner{1'300U + scenario, currentEpoch};
        LoopCycleIntent intent = managedLoopIntent(10U + scenario, owner, 0U);
        intent.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
        if (decodedEnd < 192'000U) {
            prepareShortManagedOriginalEos(
                runtime,
                intent,
                100U + scenario,
                static_cast<std::size_t>(decodedEnd));
        } else {
            prepareManagedLoopOriginal(runtime, intent, 100U + scenario);
        }
        require(runtime.publishLoopCycleIntent(intent) == NativePlaybackPublishStatus::Accepted,
            "Original-EOS intent published");
        std::array<NativePlaybackBankIntent, 2U> banks{
            assignedBank(1U, owner), NativePlaybackBankIntent{}};
        if (playbackWindow) banks[0].playbackEndFrameExclusive = 48'000U;
        require(runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            owner,
            banks,
            controls(1U))) == NativePlaybackPublishStatus::Accepted,
            "Original-EOS selection published");
        require(runtime.start(), "Original-EOS runtime starts");

        waitUntil([&] {
            const auto protocol = runtime.loopProtocolSnapshot();
            return protocol.state.boundaryResolved &&
                protocol.state.resolvedLoopEndTimelineFrameExclusive == expectedEnd;
        }, "active decoder resolves exact Original EOS", 8'000U);
        const auto protocol = runtime.loopProtocolSnapshot();
        require(protocol.state.boundaryMode == LoopHeadBoundaryMode::OriginalEos,
            "Original-EOS mode remains explicit");
        require(protocol.state.mode == (expectedEnd <= 192'000U
                ? LoopCycleMode::CacheOnly
                : LoopCycleMode::Streamed),
            "exact four seconds caches while longer content streams");
        require(protocol.activeCache.valid &&
                protocol.activeCache.frameCount ==
                    static_cast<std::uint32_t>(std::min<std::uint64_t>(decodedEnd, 192'000U)) &&
                protocol.activeCache.originalEndResolved == (decodedEnd < 192'000U),
            "short content resolves in cache while exact/long content keeps the full runway");
        require(protocol.diagnostics.originalEosAccepted ==
                (playbackWindow || decodedEnd < 192'000U ? 0U : 1U),
            "only exact/long active-decoder EOS needs state-machine resolution");
        if (expectedEnd <= 192'000U) {
            const std::size_t installed = waitForRecord(
                output,
                0U,
                [&](const FakeOutputRecord &record) {
                    return record.loopEnabled && record.loopRevision == intent.revision;
                },
                "resolved short/exact Original-EOS cache installs in renderer",
                8'000U);
            require(output.record(installed).sourceEnd <= static_cast<double>(expectedEnd),
                "renderer never crosses decoded Original EOS");
        }
        if (playbackWindow) {
            waitUntil([&] { return output.commitCount() == 1U; },
                "trimmed repeat PCM belongs to a committed output generation");
            require(output.presentFirstCurrentRecord(), "trimmed repeat owner is callback-presented before clear");
            waitUntil([&] {
                const auto observed = runtime.diagnostics();
                return sameOwnership(observed.committedOwnership, owner) && observed.presentedPosition.valid() &&
                    observed.presentedPosition.selectionToken == owner.selectionToken &&
                    observed.presentedPosition.generation == owner.generation;
            }, "trimmed repeat callback evidence commits its exact owner before clear");
            const LoopCycleIntent disabled{.revision = intent.revision + 1U, .owner = intent.owner};
            require(runtime.publishLoopCycleIntent(disabled) == NativePlaybackPublishStatus::Accepted,
                "trimmed repeat clears through normal loop policy");
            waitUntil([&] { output.consumeAll(); return runtime.diagnostics().audibleEnd.sequence != 0U; },
                "cleared repeat reaches exact natural playback end", 8'000U);
            for (std::size_t index = 0U; index < output.recordCount(); ++index)
                require(output.record(index).sourceEnd <= expectedEnd,
                    "repeat clear never drops the selected playback end");
        }
        stopAndJoin(runtime);
    }
}

void lateShortRepeatCacheClearsOnlyMatchingNaturalEosAndResumesRendering() {
    constexpr std::uint64_t decodedEnd = 7'200U;
    constexpr std::uint64_t currentEpoch = 31U;
    SongLaneIngressBank first(16'384U, 480U);
    SongLaneIngressBank second(16'384U, 480U);
    configureLanes(first, currentEpoch, 1U);
    offerLane(first, 0U, currentEpoch, 0U, decodedEnd, true);
    FakeOutput output(2'048U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(16'384U, 256U),
        first,
        second,
        output.port());
    const SongRenderOwnership owner{1'400U, currentEpoch};
    LoopCycleIntent intent = managedLoopIntent(20U, owner, 0U);
    intent.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    require(runtime.publishLoopCycleIntent(intent) == NativePlaybackPublishStatus::Accepted,
        "late short Repeat intent published before cache exists");
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "late short Repeat selection published");
    require(runtime.start(), "late short Repeat runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "late short Repeat owner commits");
    require(output.presentFirstCurrentRecord(), "late short Repeat owner is audible");

    waitUntil([&] {
        output.consumeAll();
        const auto diagnostics = runtime.diagnostics();
        const auto protocol = runtime.loopProtocolSnapshot();
        return diagnostics.outputEndOfStreamEvents != 0U && output.queued() == 0U &&
            protocol.state.phase == LoopCyclePhase::WaitingForCache;
    }, "short song reaches decoded EOS while Repeat cache is still pending", 8'000U);
    const auto pendingProtocol = runtime.loopProtocolSnapshot();
    require(pendingProtocol.state.latestRevision == intent.revision &&
            pendingProtocol.state.owner.selectionToken == owner.selectionToken &&
            pendingProtocol.state.owner.renderGeneration == owner.generation &&
            !pendingProtocol.state.cacheAccepted,
        "pending Repeat remains owner-fenced while waiting for its cache");

    const std::size_t resumedRecordStart = output.recordCount();
    prepareShortManagedOriginalEos(runtime, intent, 210U, decodedEnd);
    const std::size_t resumed = waitForRecord(
        output,
        resumedRecordStart,
        [&](const FakeOutputRecord &record) {
            return record.loopEnabled && record.loopRevision == intent.revision;
        },
        "late exact cache activation clears the matching stale EOS latch",
        8'000U);
    require(output.record(resumed).sourceEnd <= static_cast<double>(decodedEnd),
        "late recovery never renders beyond decoded Original EOS");
    require(output.record(resumed).token == owner.selectionToken &&
            output.record(resumed).generation == owner.generation,
        "late Repeat recovery resumes the same audible owner");
    stopAndJoin(runtime);
}

void streamedRepeatDrainsRealSrcPrerollBeforeBodyAcknowledgement(
    bool shortStem = false, bool gap = false, bool lateAck = false) {
    using namespace stemwise::audio::engine;
    constexpr std::uint32_t rate = 44'100U;
    constexpr std::uint64_t prefixSource = 88'200U, bodyFrame = 192'000U;
    constexpr std::size_t futureSourceEnd = 185'220U, outgoingSourceStart = 348'390U, sourceEnd = 352'800U;
    constexpr SongRenderOwnership owner{1'453U, 46U};
    std::vector<float> source(sourceEnd * 2U);
    for (std::size_t frame = 0U; frame < sourceEnd; ++frame) {
        source[frame * 2U] = static_cast<float>(static_cast<int>((frame * 31'337U) % 65'521U) - 32'760) / 1'048'576.0F;
        source[frame * 2U + 1U] = static_cast<float>(static_cast<int>((frame * 17'123U) % 65'519U) - 32'759) / 1'048'576.0F;
    }
    const auto offer = [&](LaneIngress &lane, std::uint64_t epoch, std::size_t first, std::size_t count) {
        return lane.offer(LaneInputBlock{.data = source.data() + first * 2U, .frameCount = count,
            .format = {rate, 2U, LanePcmEncoding::Float32}, .generation = epoch,
            .presentationTimeUs = first * 1'000'000U / rate});
    };
    // An independent uninterrupted real SRC supplies the sample oracle, not the runtime under test.
    LaneIngress reference(rate, 131'072U, 480U, 1U);
    for (std::size_t first = prefixSource; first < futureSourceEnd;) {
        const auto count = std::min<std::size_t>(480U, futureSourceEnd - first);
        require(offer(reference, 1U, first, count).inputFramesAccepted == count, "reference source accepted");
        first += count;
    }
    require(reference.markEndOfStream(1U, futureSourceEnd).status == LaneEndOfStreamStatus::Accepted,
        "reference has real finite source EOS");
    std::vector<float> expected;
    std::array<float, 512U> scratch{};
    std::uint64_t referenceCursor = 96'000U;
    for (std::size_t pull = 0U;; ++pull) {
        require(pull < 1'000U, "reference SRC retires within its finite extent");
        const auto result = reference.pull48k(1U, scratch.data(), scratch.size() / 2U);
        require(result.status == LanePullStatus::Ok || result.status == LanePullStatus::OutputCapacityExhausted ||
            result.status == LanePullStatus::EndOfStream, "reference SRC succeeds without manufactured gaps");
        if (result.outputFramesProduced != 0U) {
            require(result.firstTimelineFrame48k == referenceCursor, "reference SRC tags are contiguous");
            referenceCursor += result.outputFramesProduced;
            expected.insert(expected.end(), scratch.begin(), scratch.begin() + result.outputFramesProduced * 2U);
        }
        if (result.status == LanePullStatus::EndOfStream) break;
    }

    SongLaneIngressBank first(16'384U, 480U), second(16'384U, 480U);
    for (std::size_t lane = 0U; lane < (shortStem ? 3U : 1U); ++lane) {
        require(first.configureLane(lane, rate, owner.generation), "real fractional-rate lane configured");
        for (std::size_t cursor = outgoingSourceStart; cursor < sourceEnd;) {
            const auto count = std::min<std::size_t>(480U, sourceEnd - cursor);
            require(offer(*first.lane(lane), owner.generation, cursor, count).inputFramesAccepted == count,
                "finite outgoing source fits its bounded ingress");
            cursor += count;
        }
        require(first.lane(lane)->markEndOfStream(owner.generation, sourceEnd).status == LaneEndOfStreamStatus::Accepted,
            "outgoing decoder publishes its exact EOS");
    }
    FakeOutput output(2'048U, 256U);
    NativePlaybackRuntime runtime(runtimeConfiguration(16'384U, 256U), first, second, output.port());
    auto intent = managedLoopIntent(26U, owner, 0U, shortStem ? 7U : 1U);
    prepareFullManagedLoopHead(runtime, intent, 260U, shortStem ? 3U : 1U);
    auto bank = assignedBank(1U, owner, 379'200U, shortStem ? 2U : 0U);
    bank.stemPackageHash = intent.cacheKey.stemPackageHash;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        {bank, {}}, controls(1U))) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "near-end fractional-rate owner starts normally");
    const auto cleanup = onScopeExit([&]() noexcept { stopAndJoin(runtime); });
    waitUntil([&] { return output.commitCount() == 1U && runtime.diagnostics().banks[0U].lanes[0U].endOfStreamMarks != 0U; },
        "old epoch EOS is consumed before the future body request");
    require(output.presentFirstCurrentRecord(), "outgoing owner has actual callback proof");
    const auto outgoing = output.record(0U);
    const auto outgoingPulledFrames = runtime.diagnostics().banks[0U].lanes[0U].pulledFrames;
    require(runtime.publishLoopCycleIntent(intent) == NativePlaybackPublishStatus::Accepted, "real streamed Repeat enabled");
    LoopCycleIngressRequest request{};
    waitUntil([&] { request = runtime.loopProtocolSnapshot().ingressRequest; return request.valid; },
        "runtime publishes its exact streamed-body request");
    require(request.targetTimelineFrame == bodyFrame && request.ingressEpoch > owner.generation,
        "body request binds the four-second target and a future epoch");

    std::array<std::size_t, 3U> cursors{prefixSource, prefixSource, prefixSource};
    const std::size_t shortEnd = 176'459U;
    bool stemEos = false, insertedGap = false, prerollParked = false, deadlineFallback = false;
    const auto produceUntilReady = [&] {
        for (std::size_t lane = 0U; lane < (shortStem ? 3U : 1U); ++lane) {
            // Keep a required real producer unready until the warm request becomes cold.
            if (lateAck && !deadlineFallback && lane == 2U) continue;
            auto &cursor = cursors[lane];
            const auto end = lane == 1U ? shortEnd : futureSourceEnd;
            if (gap && !insertedGap && cursor >= prefixSource + 4'096U) { cursor += 97U; insertedGap = true; }
            if (cursor < end) {
                const auto offered = offer(*first.lane(lane), request.ingressEpoch, cursor, std::min<std::size_t>(480U, end - cursor));
                require(offered.status == LaneOfferStatus::Accepted || offered.status == LaneOfferStatus::Partial ||
                    offered.status == LaneOfferStatus::Backpressured, "future producer remains bounded by real ingress");
                cursor += offered.inputFramesAccepted;
            }
            if (lane == 1U && cursor == end && !stemEos) {
                require(first.lane(lane)->markEndOfStream(request.ingressEpoch, end).status == LaneEndOfStreamStatus::Accepted,
                    "short future stem seals only its actually accepted source");
                stemEos = true;
            }
        }
        runtime.notifyIngressAvailable();
        require(sameOwnership(runtime.diagnostics().committedOwnership, owner) &&
            ((lateAck && prerollParked) || output.commitCount() == 1U) &&
            output.record(0U).originalPcmHash == outgoing.originalPcmHash,
            "future preroll cannot replace or reinterpret the audible owner");
        if (lateAck && !deadlineFallback) {
            if (!prerollParked && cursors[0U] >= futureSourceEnd && stemEos &&
                runtime.diagnostics().banks[0U].lanes[0U].pulledFrames > outgoingPulledFrames + bodyFrame - 96'000U &&
                runtime.diagnostics().banks[0U].lanes[0U].retainedSuffixFrames != 0U) {
                const auto warm = runtime.loopProtocolSnapshot().ingressRequest;
                require(warm.valid && warm.sequence == request.sequence &&
                    warm.activation == LoopCycleActivation::WarmCurrentPass && cursors[2U] == prefixSource,
                    "first body suffix is parked under the warm request before the last lane starts");
                prerollParked = true;
            }
            if (!prerollParked) return false;
            output.consumeAll();
            const auto protocol = runtime.loopProtocolSnapshot();
            const auto cold = protocol.ingressRequest;
            if (protocol.diagnostics.deadlineFallbacks == 0U || !cold.valid ||
                cold.activation != LoopCycleActivation::ColdAtLoopStart ||
                runtime.diagnostics().outputEndOfStreamEvents == 0U) return false;
            require(cold.sequence == request.sequence && cold.loopRevision == request.loopRevision &&
                cold.targetCycle == request.targetCycle && cold.owner == request.owner &&
                cold.ingressEpoch == request.ingressEpoch && cold.targetTimelineFrame == request.targetTimelineFrame &&
                cold.requiredEndTimelineFrameExclusive == request.requiredEndTimelineFrameExclusive &&
                cold.requiredLaneMask == request.requiredLaneMask,
                "deadline fallback changes scheduling without replacing the parked PCM request");
            deadlineFallback = true;
            return false;
        }
        if (gap) return runtime.renderReadinessDiagnostics().lanes[0U].ingressFailureGeneration == request.ingressEpoch;
        return cursors[0U] >= futureSourceEnd && (!shortStem ||
            (cursors[2U] >= futureSourceEnd && stemEos && first.lane(1U)->diagnostics().readableSourceFrames == 0U));
    };
    const auto diagnoseProducer = [&] {
        const auto diagnostic = runtime.diagnostics();
        const auto protocol = runtime.loopProtocolSnapshot();
        const auto ready = runtime.renderReadinessDiagnostics();
        const auto &lanes = diagnostic.banks[0U].lanes;
        return "lateAck=" + std::to_string(lateAck) +
            " parked=" + std::to_string(prerollParked) + " fallback=" + std::to_string(deadlineFallback) +
            " stemEos=" + std::to_string(stemEos) +
            " cursors=" + std::to_string(cursors[0U]) + "," + std::to_string(cursors[1U]) + "," + std::to_string(cursors[2U]) +
            " pulled=" + std::to_string(lanes[0U].pulledFrames) + "," + std::to_string(lanes[2U].pulledFrames) +
            " outgoingPulled=" + std::to_string(outgoingPulledFrames) +
            " retained=" + std::to_string(lanes[0U].retainedSuffixFrames) + "," + std::to_string(lanes[2U].retainedSuffixFrames) +
            " activeGen=" + std::to_string(first.lane(0U)->activeGeneration()) + "," +
                std::to_string(shortStem ? first.lane(2U)->activeGeneration() : 0U) +
            " requestValid=" + std::to_string(protocol.ingressRequest.valid) +
            " activation=" + std::to_string(static_cast<unsigned>(protocol.ingressRequest.activation)) +
            " deadlineFallbacks=" + std::to_string(protocol.diagnostics.deadlineFallbacks) +
            " outputEos=" + std::to_string(diagnostic.outputEndOfStreamEvents) +
            " gaps=" + std::to_string(lanes[0U].discontinuityRejects) +
            " failureGen=" + std::to_string(ready.lanes[0U].ingressFailureGeneration);
    };
    if (lateAck) {
        waitUntilDiagnosed([&] {
            static_cast<void>(produceUntilReady());
            return deadlineFallback;
        }, diagnoseProducer, "parked warm body suffix reaches actual deadline fallback before the last lane starts");
    }
    waitUntilDiagnosed(produceUntilReady, diagnoseProducer,
        gap ? "real future source gap must be rejected" : "real 2s SRC prefix must drain through the bounded ring before 4s body acknowledgement");
    if (gap) {
        require(runtime.diagnostics().banks[0U].lanes[0U].discontinuityRejects != 0U &&
            !runtime.loopProtocolSnapshot().state.renderInstalled, "a genuine gap cannot install a loop body");
        return;
    }
    if (lateAck) {
        require(prerollParked && deadlineFallback &&
            runtime.renderReadinessDiagnostics().lanes[0U].ingressFailureGeneration != request.ingressEpoch,
            "warm-to-cold fallback preserves the consumed first body suffix without a source gap");
    }
    require(cursors[0U] * 48'000U / rate >= request.requiredEndTimelineFrameExclusive,
        "actual source acceptance, not requested position, proves the body coverage");
    LoopCycleIngressReadyEvidence ready{.requestSequence = request.sequence, .loopRevision = request.loopRevision,
        .targetCycle = request.targetCycle, .owner = request.owner, .ingressEpoch = request.ingressEpoch,
        .readyLaneMask = request.requiredLaneMask, .eosPaddedLaneMask = shortStem ? 2U : 0U,
        .firstTimelineFrame = 96'000U, .endTimelineFrameExclusive = cursors[0U] * 48'000U / rate};
    for (unsigned rejection = 0U; rejection < 3U; ++rejection) {
        auto wrong = ready;
        if (rejection == 0U) ++wrong.owner.selectionToken;
        else if (rejection == 1U) ++wrong.ingressEpoch;
        else ++wrong.requestSequence;
        const auto before = runtime.loopProtocolSnapshot().diagnostics.ingressAckRejections;
        require(runtime.publishLoopIngressReady(wrong) == NativePlaybackPublishStatus::Accepted, "negative proof reaches real consumer");
        waitUntil([&] { return runtime.loopProtocolSnapshot().diagnostics.ingressAckRejections > before; },
            "wrong owner, epoch or request cannot authorize retained body PCM");
        require(!runtime.loopProtocolSnapshot().state.renderInstalled, "rejected proof preserves the pending request");
    }
    require(runtime.publishLoopIngressReady(ready) == NativePlaybackPublishStatus::Accepted, "only exact actual body coverage is acknowledged");
    waitUntil([&] { return runtime.loopProtocolSnapshot().state.renderInstalled; }, "exact retained future PCM installs its loop directive");
    const auto crossing = waitForRecord(output, 0U, [&](const FakeOutputRecord &record) {
        return record.loopEnabled && record.loopRevision == intent.revision && record.sourceStart <= bodyFrame && record.sourceEnd > bodyFrame;
    }, "first target-crossing body suffix reaches actual output", 4'000U, &runtime);
    const auto following = waitForRecord(output, crossing + 1U, [&](const FakeOutputRecord &record) {
        return record.loopEnabled && record.loopRevision == intent.revision && record.sourceStart == output.record(crossing).sourceEnd;
    }, "body continues without dropping its first retained suffix", 4'000U, &runtime);
    for (auto index = crossing; index <= following; ++index) {
        const auto record = output.record(index);
        require(record.token == owner.selectionToken && record.generation == owner.generation &&
            record.sourceEnd == record.sourceStart + record.frames, "body retains exact owner and sample positions");
        auto samples = tone(record.frames, static_cast<std::uint64_t>(record.sourceStart), .03F, 211.0);
        for (std::size_t frame = 0U; frame < record.frames; ++frame) {
            const auto position = static_cast<std::uint64_t>(record.sourceStart) + frame;
            if (position < bodyFrame) continue;
            const auto offset = (position - 96'000U) * 2U;
            require(offset + 1U < expected.size(), "body sample lies inside independent SRC reference");
            samples[frame * 2U] = expected[offset]; samples[frame * 2U + 1U] = expected[offset + 1U];
        }
        std::uint64_t hash = 14695981039346656037ULL;
        for (const auto sample : samples) { hash ^= std::bit_cast<std::uint32_t>(sample); hash *= 1099511628211ULL; }
        require(record.originalPcmHash == hash, "first body suffix and following PCM match uninterrupted real SRC exactly");
    }
}

void newlyAttachedStemsBuildTheOwnerCacheAfterRepeatAdvancesIngress() {
    constexpr std::uint64_t end = 200'000U;
    constexpr std::uint64_t headEnd = 192'000U;
    constexpr SongRenderOwnership owner{1'452U, 36U};
    SongLaneIngressBank first(262'144U, 480U);
    SongLaneIngressBank second(262'144U, 480U);
    configureLanes(first, owner.generation);
    offerLane(first, 0U, owner.generation, 0U, end, true);
    FakeOutput output(8'192U, 96U);
    NativePlaybackRuntime runtime(runtimeConfiguration(262'144U, 480U), first, second, output.port());
    LoopCycleIntent intent = managedLoopIntent(25U, owner, 0U);
    intent.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    prepareManagedLoopOriginal(runtime, intent, 250U);
    require(runtime.publishLoopCycleIntent(intent) == NativePlaybackPublishStatus::Accepted,
        "Original Repeat policy published before stems attach");
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        banks, controls(1U))) == NativePlaybackPublishStatus::Accepted, "Repeat owner published");
    require(runtime.start(), "Repeat cache-ingress runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "Repeat owner commits");
    require(output.presentFirstCurrentRecord(), "Repeat owner is audible");
    stemwise::audio::engine::LoopCycleIngressRequest request{};
    waitUntil([&] {
        request = runtime.loopProtocolSnapshot().ingressRequest;
        return request.valid;
    }, "Original Repeat requests its next body");
    offerLane(first, 0U, request.ingressEpoch, headEnd, end - headEnd, true);
    const LoopCycleIngressReadyEvidence ready{
        .requestSequence = request.sequence, .loopRevision = request.loopRevision,
        .targetCycle = request.targetCycle, .owner = request.owner,
        .ingressEpoch = request.ingressEpoch, .readyLaneMask = 1U,
        .firstTimelineFrame = headEnd, .endTimelineFrameExclusive = end,
    };
    require(runtime.publishLoopIngressReady(ready) == NativePlaybackPublishStatus::Accepted,
        "Original next body acknowledged");
    waitUntil([&] {
        const auto protocol = runtime.loopProtocolSnapshot();
        return protocol.state.renderInstalled && protocol.state.preparedIngressEpoch == request.ingressEpoch;
    }, "Repeat accepts its exact future body before playback reaches EOS");
    const auto wrappedRecord = waitForRecord(output, 0U, [&](const FakeOutputRecord &record) {
        return record.loopEnabled && record.loopCycle == 1U;
    }, "Repeat advances only its render ingress epoch", 12'000U);
    require(output.presentRecord(wrappedRecord), "Repeat wrap is presented");
    for (std::size_t lane = 1U; lane < kLaneCount; ++lane) {
        offerLane(first, lane, owner.generation, 0U, end, true);
    }
    waitUntil([&] {
        return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 127U;
    }, "owner-stamped new stems seal the cache while Repeat uses a newer body epoch");
    require(runtime.diagnostics().outputInvalidRenderResults == 0U,
        "cache-building stems cannot corrupt live ownership");
    stopAndJoin(runtime);
}

void tunedRepeatPreparesBeforeEosAfterRawLookaheadWasConsumed() {
    constexpr std::uint64_t end = 384'000U;
    constexpr std::uint64_t seek = 350'000U;
    constexpr SongRenderOwnership owner{1'451U, 36U};
    SongLaneIngressBank first(65'536U, 480U);
    SongLaneIngressBank second(65'536U, 480U);
    configureLanes(first, owner.generation, 1U);
    offerLane(first, 0U, owner.generation, seek, end - seek, true);
    FakeOutput output(8'192U, 96U);
    NativePlaybackRuntime runtime(runtimeConfiguration(65'536U, 480U), first, second, output.port());
    LoopCycleIntent intent = managedLoopIntent(25U, owner, 0U);
    intent.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    prepareManagedLoopOriginal(runtime, intent, 250U);
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner, seek), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        banks, controls(1U, 1.35F))) == NativePlaybackPublishStatus::Accepted,
        "tuned near-end owner published");
    require(runtime.start(), "tuned Repeat readiness runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "tuned owner primes and commits");
    require(output.presentFirstCurrentRecord(), "tuned owner is audible");
    require(runtime.publishLoopCycleIntent(intent) == NativePlaybackPublishStatus::Accepted,
        "Repeat enabled after lookahead enters DSP");
    waitUntil([&] {
        const auto protocol = runtime.loopProtocolSnapshot();
        return protocol.ingressRequest.valid &&
            protocol.ingressRequest.activation == LoopCycleActivation::WarmCurrentPass;
    }, "consumed DSP lookahead must not postpone Repeat body preparation until EOS");
    require(runtime.diagnostics().outputEndOfStreamEvents == 0U,
        "Repeat prepares while outgoing tuned PCM remains queued");
    stopAndJoin(runtime);
}

void streamedDeadlineFallbackRecoversAfterNaturalEosWithLateExactBody() {
    constexpr std::uint64_t loopEnd = 200'000U;
    constexpr std::uint64_t headEnd = 192'000U;
    constexpr std::uint64_t currentEpoch = 36U;
    SongLaneIngressBank first(262'144U, 480U);
    SongLaneIngressBank second(262'144U, 480U);
    configureLanes(first, currentEpoch, 1U);
    offerLane(first, 0U, currentEpoch, 0U, loopEnd, true);
    FakeOutput output(2'048U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(262'144U, 256U),
        first,
        second,
        output.port());
    const SongRenderOwnership owner{1'450U, currentEpoch};
    LoopCycleIntent intent = managedLoopIntent(25U, owner, 0U);
    intent.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    prepareManagedLoopOriginal(runtime, intent, 250U);
    require(runtime.publishLoopCycleIntent(intent) == NativePlaybackPublishStatus::Accepted,
        "deadline-fallback Repeat intent published");
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "deadline-fallback Repeat selection published");
    require(runtime.start(), "deadline-fallback Repeat runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "deadline-fallback owner commits");
    require(output.presentFirstCurrentRecord(), "deadline-fallback owner is audible");
    waitUntil([&] {
        const auto request = runtime.loopProtocolSnapshot().ingressRequest;
        return request.valid && request.activation == LoopCycleActivation::WarmCurrentPass;
    }, "deadline fallback starts from an actual warm body request before intentional drain", 8'000U);

    stemwise::audio::engine::LoopCycleIngressRequest coldRequest{};
    waitUntil([&] {
        output.consumeAll();
        const auto protocol = runtime.loopProtocolSnapshot();
        const auto diagnostics = runtime.diagnostics();
        if (protocol.diagnostics.deadlineFallbacks == 0U ||
            diagnostics.outputEndOfStreamEvents == 0U ||
            !protocol.ingressRequest.valid ||
            protocol.ingressRequest.activation != LoopCycleActivation::ColdAtLoopStart) {
            return false;
        }
        coldRequest = protocol.ingressRequest;
        return true;
    }, "streamed deadline fallback reaches natural EOS with one cold request", 12'000U);
    require(runtime.diagnostics().audibleEnd.sequence == 0U,
        "pending streamed Repeat suppresses terminal end during cold recovery");

    offerLane(
        first,
        0U,
        coldRequest.ingressEpoch,
        headEnd,
        static_cast<std::size_t>(loopEnd - headEnd),
        true);
    const LoopCycleIngressReadyEvidence ready{
        .requestSequence = coldRequest.sequence,
        .loopRevision = coldRequest.loopRevision,
        .targetCycle = coldRequest.targetCycle,
        .owner = coldRequest.owner,
        .ingressEpoch = coldRequest.ingressEpoch,
        .readyLaneMask = coldRequest.requiredLaneMask,
        .firstTimelineFrame = headEnd,
        .endTimelineFrameExclusive = loopEnd,
    };
    const std::size_t recoveryRecordStart = output.recordCount();
    require(runtime.publishLoopIngressReady(ready) == NativePlaybackPublishStatus::Accepted,
        "late exact cold body readiness published");
    const std::size_t recovered = waitForRecord(
        output,
        recoveryRecordStart,
        [&](const FakeOutputRecord &record) {
            return record.loopEnabled && record.loopRevision == intent.revision &&
                record.loopCycle == coldRequest.targetCycle;
        },
        "cold streamed activation clears stale EOS and renders again",
        8'000U);
    require(output.record(recovered).sourceStart < static_cast<double>(headEnd),
        "cold streamed recovery restarts from the bounded loop head");
    require(runtime.diagnostics().audibleEnd.sequence == 0U,
        "cold streamed recovery remains the same audible owner");
    stopAndJoin(runtime);
}

void streamedRepeatZeroPadsAStemOnlyFromExactFutureEpochEos() {
    constexpr std::uint64_t loopEnd = 200'000U;
    constexpr std::uint64_t stemEnd = 193'000U;
    constexpr std::uint64_t headEnd = 192'000U;
    constexpr std::uint64_t currentEpoch = 41U;
    SongLaneIngressBank first(262'144U, 480U);
    SongLaneIngressBank second(262'144U, 480U);
    configureLanes(first, currentEpoch, 2U);
    offerLane(first, 0U, currentEpoch, 0U, loopEnd, true);
    offerLane(first, 1U, currentEpoch, 0U, stemEnd, true);
    FakeOutput output(2'048U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(262'144U, 256U),
        first,
        second,
        output.port());
    const SongRenderOwnership owner{1'500U, currentEpoch};
    LoopCycleIntent intent = managedLoopIntent(30U, owner, 0U, 0b11U);
    intent.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    prepareFullManagedLoopHead(runtime, intent, 300U);
    require(runtime.publishLoopCycleIntent(intent) == NativePlaybackPublishStatus::Accepted,
        "shorter-stem Repeat intent published");
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "shorter-stem Repeat selection published");
    require(runtime.start(), "shorter-stem Repeat runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "shorter-stem owner commits");
    require(output.presentFirstCurrentRecord(), "shorter-stem owner is audible");
    stemwise::audio::engine::LoopCycleIngressRequest request{};
    waitUntil([&] {
        request = runtime.loopProtocolSnapshot().ingressRequest;
        return request.valid;
    }, "shorter-stem Repeat requests its exact future body", 8'000U);
    require(request.requiredEndTimelineFrameExclusive > stemEnd,
        "future body prime extends beyond the shorter stem EOS");
    offerLane(
        first,
        0U,
        request.ingressEpoch,
        headEnd,
        static_cast<std::size_t>(loopEnd - headEnd),
        true);

    LoopCycleIngressReadyEvidence ready{
        .requestSequence = request.sequence,
        .loopRevision = request.loopRevision,
        .targetCycle = request.targetCycle,
        .owner = request.owner,
        .ingressEpoch = request.ingressEpoch,
        .readyLaneMask = request.requiredLaneMask,
        .eosPaddedLaneMask = 0b10U,
        .firstTimelineFrame = headEnd,
        .endTimelineFrameExclusive = loopEnd,
    };
    require(runtime.publishLoopIngressReady(ready) == NativePlaybackPublishStatus::Accepted,
        "unproven shorter-stem EOS claim enters the runtime mailbox");
    waitUntil([&] {
        return runtime.loopProtocolSnapshot().lastIngressStatus ==
            LoopCycleIngressStatus::MissingRequiredLanes;
    }, "runtime strips a padding bit without exact matching native EOS");

    LaneIngress *stem = first.lane(1U);
    require(stem != nullptr && stem->advanceGeneration(request.ingressEpoch),
        "short stem advances to the exact requested future epoch");
    const auto marked = stem->markEndOfStream(request.ingressEpoch, 0U);
    require(marked.status == LaneEndOfStreamStatus::Accepted,
        "empty future stem publishes exact epoch EOS");
    require(runtime.publishLoopIngressReady(ready) == NativePlaybackPublishStatus::Accepted,
        "exact shorter-stem EOS proof republishes readiness");

    waitUntil([&] {
        const auto state = runtime.loopProtocolSnapshot().state;
        return state.renderInstalled && state.renderCycle == 0U && state.activeIngressEpoch == currentEpoch;
    }, "shorter-stem natural wrap starts from an actually installed warm current pass", 8'000U);
    const auto warm = waitForRecord(output, 0U, [&](const FakeOutputRecord &record) {
        return record.token == owner.selectionToken && record.generation == owner.generation &&
            record.loopEnabled && record.loopRevision == intent.revision && record.loopCycle == 0U;
    }, "warm Repeat produces actual exact-owner cycle-zero PCM");
    require(output.presentRecord(warm), "warm cycle zero receives actual callback acknowledgement");
    waitUntil([&] {
        const auto state = runtime.loopProtocolSnapshot().state;
        return state.renderActive && state.renderCycle == 0U && state.activeIngressEpoch == currentEpoch;
    }, "warm Repeat is audibly active before the unpaced natural-boundary drain");

    const std::size_t cycleOne = waitForRecord(
        output,
        0U,
        [&](const FakeOutputRecord &record) {
            return record.loopEnabled && record.loopRevision == intent.revision &&
                record.loopCycle == 1U;
        },
        "shorter stem zero-pads through Original B and wraps without deadlock",
        12'000U);
    require(output.presentRecord(cycleOne),
        "shorter-stem streamed wrap receives exact callback proof");
    waitUntil([&] {
        const auto protocol = runtime.loopProtocolSnapshot();
        return protocol.state.renderActive && protocol.state.renderCycle == 1U &&
            protocol.state.activeIngressEpoch == request.ingressEpoch;
    }, "shorter-stem future epoch becomes the active streamed cycle", 8'000U);
    require(runtime.loopProtocolSnapshot().diagnostics.streamedWraps == 1U,
        "shorter-stem Repeat completes one exact streamed wrap");
    require(runtime.diagnostics().audibleEnd.sequence == 0U,
        "shorter stem cannot terminate Repeat before Original EOS");
    stopAndJoin(runtime);
}

void directLoopStemIdentityChangesPreserveEnabledRange(
    bool preloadSparePrefix = false,
    std::uint32_t partCount = 6U) {
    constexpr SongRenderOwnership owner{1'720U, 2U};
    constexpr SongRenderOwnership builderOwner{1'720U, 3U};
    const std::size_t activeLaneCount = partCount + 1U;
    const std::uint32_t allLaneMask = (1U << activeLaneCount) - 1U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation, activeLaneCount);
    configureLanes(second, builderOwner.generation, activeLaneCount);
    offerAllLanes(first, owner.generation, 16'384U, activeLaneCount);

    FakeOutput output(4'096U, 64U);
    output.setMaximumAcceptance(256U);
    auto configuration = runtimeConfiguration(32'768U, 256U);
    configuration.ingressPullFrames = 1'024U; // Match the production producer quantum.
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const auto recordingIntent = [&](std::uint64_t revision, SongRenderOwnership ownership) {
        auto intent = assignedBank(revision, ownership, 0U, partCount);
        intent.cacheFrameUpperBound = 16'384U;
        return intent;
    };
    std::array<NativePlaybackBankIntent, 2U> banks{
        recordingIntent(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "direct-loop cache-identity owner selected");
    require(runtime.start(), "direct-loop cache-identity runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "direct-loop cache-identity owner committed");
    require(output.presentFirstCurrentRecord(),
        "direct-loop cache-identity owner presented");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "direct-loop cache-identity all-lane cache sealed", 8'000U);

    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        0U,
        4'800U) == NativePlaybackPublishStatus::Accepted,
        "enabled direct-loop cache demand accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy policy = output.lastPublishedLoopPolicy();
        return policy.revision == 1U && policy.enabled;
    }, "enabled direct-loop cache demand reaches output");

    banks[0] = recordingIntent(2U, owner);
    banks[0].stemPackageHash = 0U;
    banks[0].stemLaneCount = 0U;
    banks[1] = recordingIntent(1U, builderOwner);
    ++banks[1].stemPackageHash;
    require(runtime.publishCommand(selectedCommand(
        2U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(2U))) == NativePlaybackPublishStatus::Accepted,
        "explicit part retirement accepted during direct loop");
    waitUntil([&] {
        return runtime.diagnostics().banks[1].appliedIntentRevision == 1U;
    }, "same-song replacement cache builder assigned");
    waitUntil([&] {
        return runtime.diagnostics().banks[0].appliedIntentRevision == 2U &&
            runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 1U;
    }, "part retirement applies without disabling the user's direct loop");
    const auto retainedPolicy = output.lastPublishedLoopPolicy();
    require(runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner &&
            retainedPolicy.revision == 1U && retainedPolicy.enabled &&
            retainedPolicy.aTimelineFrame == 0U &&
            retainedPolicy.bTimelineFrameExclusive == 4'800U &&
            output.commitCount() == 1U && output.playing(),
        "part retirement preserves Original, owner, playback and the enabled A/B range");

    constexpr std::size_t prefixFrames = 2'048U;
    if (preloadSparePrefix) {
        offerLane(second, 0U, builderOwner.generation, 0U, 16'384U, true);
        offerLane(second, 1U, builderOwner.generation, 0U, prefixFrames, false);
        runtime.notifyIngressAvailable();
        waitUntil([&] {
            return runtime.diagnostics().banks[1U].lanes[1U].appendedFrames >= prefixFrames;
        }, "spare retains new part prefix before selected package adoption");
    }
    banks[0] = recordingIntent(3U, owner);
    ++banks[0].stemPackageHash;
    require(runtime.publishCommand(selectedCommand(
        3U, NativePlaybackBank::First, owner, banks, controls(3U))) ==
        NativePlaybackPublishStatus::Accepted, "new package accepted with the direct loop still enabled");
    waitUntil([&] {
        return runtime.diagnostics().banks[0].appliedIntentRevision == 3U;
    }, "new package adopts retained Original after explicit retirement");

    if (preloadSparePrefix) {
        offerLane(second, 1U, builderOwner.generation, prefixFrames, 16'384U - prefixFrames, true);
        for (std::size_t lane = 2U; lane < activeLaneCount; ++lane) {
            offerLane(second, lane, builderOwner.generation, 0U, 16'384U, true);
        }
    } else offerAllLanes(second, builderOwner.generation, 16'384U, activeLaneCount);
    const auto rebuildStarted = testMonotonicMicros();
    bool halfwaySampled{};
    std::uint64_t halfwayOperations{};
    std::array<std::uint64_t, kSongRenderLaneCount> halfwayPulled{}, halfwayAppended{};
    try {
        waitUntil([&] {
            if (!halfwaySampled && testMonotonicMicros() - rebuildStarted >= 4'000'000U) {
                const auto halfway = runtime.diagnostics();
                halfwayOperations = halfway.resourceOperationsCompleted;
                for (std::size_t lane = 0U; lane < activeLaneCount; ++lane) {
                    halfwayPulled[lane] = halfway.banks[1U].lanes[lane].pulledFrames;
                    halfwayAppended[lane] = halfway.banks[1U].lanes[lane].appendedFrames;
                }
                halfwaySampled = true;
            }
            const auto readiness = runtime.renderReadinessDiagnostics();
            return readiness.currentSongCacheBoundToSelectedOwner &&
                readiness.currentSongCacheCompleteLaneMask == allLaneMask;
        }, "updated stem identity cache rebuilds and promotes", 8'000U);
    } catch (...) {
        const auto ready = runtime.renderReadinessDiagnostics();
        const auto diagnostic = runtime.diagnostics();
        std::cerr << "replacement cache state mask=" << ready.currentSongCacheCompleteLaneMask
                  << " bound=" << ready.currentSongCacheBoundToSelectedOwner
                  << " grow=" << ready.currentSongCacheCanGrow
                  << " renderStart=" << ready.renderStartTimelineFrame;
        for (std::size_t bank = 0U; bank < 2U; ++bank) {
            std::cerr << " bank" << bank << "={revision:" << diagnostic.banks[bank].appliedIntentRevision
                      << ",rejected:" << diagnostic.banks[bank].rejectedBankIntents
                      << ",Original-pulled:" << diagnostic.banks[bank].lanes[0U].pulledFrames
                      << ",part1-pulled:" << diagnostic.banks[bank].lanes[1U].pulledFrames
                      << ",part1-appended:" << diagnostic.banks[bank].lanes[1U].appendedFrames << '}';
        }
        std::cerr << " parts=" << partCount << " preloaded=" << preloadSparePrefix
                  << " resource-progress=" << halfwayOperations << "->" << diagnostic.resourceOperationsCompleted;
        for (std::size_t lane = 0U; lane < activeLaneCount; ++lane)
            std::cerr << " lane" << lane << "={pulled:" << halfwayPulled[lane] << "->"
                      << diagnostic.banks[1U].lanes[lane].pulledFrames << ",appended:"
                      << halfwayAppended[lane] << "->" << diagnostic.banks[1U].lanes[lane].appendedFrames << '}';
        std::cerr << '\n';
        stopAndJoin(runtime);
        throw;
    }
    const auto rebuiltPolicy = output.lastPublishedLoopPolicy();
    require(output.commitCount() == 1U && output.playing() &&
            rebuiltPolicy.revision == 1U && rebuiltPolicy.enabled &&
            rebuiltPolicy.aTimelineFrame == 0U &&
            rebuiltPolicy.bTimelineFrameExclusive == 4'800U,
        "cache identity retirement and rebuild preserve playback and the enabled A/B range");
    stopAndJoin(runtime);
}

void disabledDirectLoopRetiresWithoutBreakingIdempotentRetry() {
    constexpr SongRenderOwnership owner{1'750U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(4'096U, 64U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(32'768U, 256U),
        first,
        second,
        output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "direct-loop retirement owner selected");
    require(runtime.start(), "direct-loop retirement runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "direct-loop retirement owner committed");
    require(output.presentFirstCurrentRecord(),
        "direct-loop retirement owner presented");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "direct-loop retirement cache ready", 8'000U);

    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        0U,
        4'800U) == NativePlaybackPublishStatus::Accepted,
        "enabled direct-loop demand accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy demand = output.lastPublishedLoopDemand();
        return demand.revision == 1U && demand.enabled && !demand.physicalReady;
    }, "enabled direct-loop demand is immediately visible");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "enabled direct loop publishes one prepared A stage");
    const NativeAudioOutputLoopPolicy prepared = output.lastPublishedLoopPolicy();
    require(prepared.revision == 1U && prepared.enabled &&
            prepared.physicalReady && prepared.hasPhysicalCutover() &&
            prepared.cutoverTimelineFrameExclusive == 4'800U &&
            prepared.continuationTimelineFrame == 0U &&
            prepared.physicalRevision() != 0U,
        "enabled direct loop has one exact callback-claimable B-to-A role");

    require(runtime.publishDirectLoopPolicy(
        2U,
        owner.selectionToken,
        owner.generation,
        false,
        0U,
        0U) == NativePlaybackPublishStatus::Accepted,
        "direct-loop disable accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy demand = output.lastPublishedLoopDemand();
        return demand.revision == 2U && !demand.enabled && !demand.physicalReady;
    }, "disable reaches callback as logical demand");
    waitUntil([&] {
        return !output.loopCutoverIsOpen() && !output.loopCutoverIsCommitted();
    }, "disable retires the sole prepared role");
    require(output.loopCutoverCommitCount() == 1U && output.playing(),
        "disable creates no replacement stage and leaves transport linear");

    require(runtime.publishDirectLoopPolicy(
        2U,
        owner.selectionToken,
        owner.generation,
        false,
        0U,
        0U) == NativePlaybackPublishStatus::Accepted,
        "retired direct-loop publication remains idempotent");
    waitForRenderProgress(
        runtime,
        runtime.diagnostics().renderCycles,
        "idempotent disable remains settled",
        2U);
    require(output.loopCutoverCommitCount() == 1U &&
            !output.loopCutoverIsOpen() && !output.loopCutoverIsCommitted() &&
            output.playing(),
        "idempotent retry cannot resurrect physical loop state");
    stopAndJoin(runtime);
}
void directLoopEngagementStaysLatchedAtExactB() {
    constexpr SongRenderOwnership owner{1'754U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    constexpr std::uint64_t a = 128U;
    constexpr std::uint64_t b = 4'800U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(4'096U, 64);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(32'768U, 256U),
        first,
        second,
        output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "exact-B latch owner selected");
    require(runtime.start(), "exact-B latch runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "exact-B latch owner committed");
    require(output.presentFirstCurrentRecord(),
        "exact-B latch owner publishes callback cursor");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "exact-B latch complete cache ready", 8'000U);

    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        a,
        b) == NativePlaybackPublishStatus::Accepted,
        "exact-B latch prior loop accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy policy = output.lastPublishedLoopPolicy();
        return policy.revision == 1U && policy.enabled;
    }, "exact-B latch prior loop engages");

    const NativeAudioPresentedPosition before = runtime.diagnostics().presentedPosition;
    require(before.valid() && before.lastTimelineFrame >= 0 &&
            static_cast<std::uint64_t>(before.lastTimelineFrame) < b,
        "exact-B latch starts before B");
    output.advanceAudiblePosition(
        b - static_cast<std::uint64_t>(before.lastTimelineFrame));
    waitUntil([&] {
        const NativeAudioPresentedPosition position = runtime.diagnostics().presentedPosition;
        return position.valid() && position.lastTimelineFrame == static_cast<std::int64_t>(b);
    }, "exact-B latch callback reaches exact B");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy policy = output.lastPublishedLoopPolicy();
        return policy.revision == 1U && policy.physicalReady;
    }, "exact-B latch prior A is physically prepared");
    const std::uint64_t priorPhysicalRevision =
        output.lastPublishedLoopPolicy().physicalRevision();
    require(output.presentCommittedLoopCutover(false),
        "exact-B callback installs the A role before hardware presents it");
    waitUntil([&] {
        const auto spare = output.lastPublishedLoopPolicy();
        return output.loopCutoverCommitCount() == 2U && spare.nextRevisionOnly &&
            spare.cutoverSourceRevision == priorPhysicalRevision;
    }, "exact-B latch primes one spare before the identical edit");
    require(output.lastPublishedLoopPolicy().continuationTimelineFrame == a,
        "the outgoing hardware B cursor cannot turn the newly adopted A spare into a linear B continuation");
    require(runtime.diagnostics().presentedPosition.lastTimelineFrame == static_cast<std::int64_t>(b) &&
        runtime.diagnostics().presentedPosition.loopPolicyRevision == 0U && output.presentFirstCurrentRecord(),
        "private A adoption preserves the outgoing hardware cursor until actual A presentation");
    waitUntil([&] {
        return runtime.diagnostics().presentedPosition.loopPolicyRevision == priorPhysicalRevision;
    }, "worker observes exact-B prepared A hardware presentation");
    const std::uint64_t cutoverBeginsBeforeReplacement =
        output.loopCutoverBeginCount();

    require(runtime.publishDirectLoopPolicy(
        2U,
        owner.selectionToken,
        owner.generation,
        true,
        a,
        b) == NativePlaybackPublishStatus::Accepted,
        "exact-B latch replacement accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy policy = output.acceptedLoopPolicy();
        return policy.revision == 2U && policy.physicalReady;
    },
        "exact-B latch replacement reaches output");
    const NativeAudioOutputLoopPolicy replacement = output.acceptedLoopPolicy();
    if (!replacement.enabled || replacement.hasPhysicalCutover() ||
        replacement.physicalRevision() != priorPhysicalRevision ||
        output.loopCutoverBeginCount() != cutoverBeginsBeforeReplacement) {
        std::cerr << "exact-B replacement: enabled=" << replacement.enabled
            << " cutover=" << replacement.hasPhysicalCutover()
            << " physical=" << replacement.physicalRevision() << "/" << priorPhysicalRevision
            << " begins=" << output.loopCutoverBeginCount() << "/" << cutoverBeginsBeforeReplacement << '\n';
    }
    require(replacement.enabled && !replacement.hasPhysicalCutover() &&
            replacement.physicalRevision() == priorPhysicalRevision &&
            output.loopCutoverBeginCount() == cutoverBeginsBeforeReplacement,
        "identical exact-B replacement reuses prepared A without DSP or staging");
    const std::size_t recordsBeforeReplacement = output.recordCount();
    const std::size_t queuedBeforeReplacement = output.queued();
    require(queuedBeforeReplacement != 0U &&
            output.consumeFrames(std::min<std::size_t>(512U, queuedBeforeReplacement)) != 0U,
        "exact-B latch makes room for replacement PCM");
    const std::size_t engagedRecord = waitForRecord(
        output,
        recordsBeforeReplacement,
        [physicalRevision = replacement.physicalRevision()](const FakeOutputRecord &record) {
            return record.loopPolicyRevision == physicalRevision;
        },
        "exact-B latch replacement PCM reaches output");
    require(output.record(engagedRecord).loopEnabled,
        "exact-B latch replacement PCM remains physically loop-enabled");
    stopAndJoin(runtime);
}

void directLoopEngagementPreservesAtomicPointerDecision() {
    constexpr SongRenderOwnership owner{1'758U, 2U};
    SongLaneIngressBank first(8'192U, 480U);
    SongLaneIngressBank second(8'192U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 4'096U);

    FakeOutput output(4'096U, 64);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(runtimeConfiguration(8'192U, 256U), first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "pointer-position owner selected");
    require(runtime.start(), "pointer-position runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "pointer-position owner committed");
    require(output.presentFirstCurrentRecord(), "pointer-position owner presented");
    const std::uint64_t positionSequence = runtime.diagnostics().presentedPosition.sequence;
    output.advanceAudiblePosition(256U);
    waitUntil([&] {
        const NativeAudioPresentedPosition position = runtime.diagnostics().presentedPosition;
        return position.sequence > positionSequence && position.lastTimelineFrame > 128;
    }, "native callback cursor advances beyond the authored B");

    output.setAudiblePosition(owner.generation, owner.selectionToken, 64);
    require(runtime.publishDirectLoopPolicy(
        1U, owner.selectionToken, owner.generation, true, 16U, 128U, 123U, false) ==
            NativePlaybackPublishStatus::Accepted,
        "caller-disarmed policy accepted");
    require(!output.lastPublishedLoopDemand().boundaryArmedAtPublication,
        "atomic pointer publication preserves its disarmed boundary decision");

    output.setAudiblePosition(owner.generation, owner.selectionToken, 256);
    require(runtime.publishDirectLoopPolicy(
        2U, owner.selectionToken, owner.generation, true, 16U, 128U, 124U, true) ==
            NativePlaybackPublishStatus::Accepted,
        "caller-armed policy accepted");
    require(output.lastPublishedLoopDemand().boundaryArmedAtPublication,
        "later device polling cannot overturn the atomic pointer decision");

    output.clearAudiblePositionOverride();
    // Make the fallback unavailable too: no exact-owner timestamp/history means the caller keeps
    // its old authority rather than native guessing at output latency.
    output.setAudiblePosition(9'999U, owner.selectionToken, 64);
    require(runtime.publishDirectLoopPolicy(
        3U, owner.selectionToken, owner.generation, true, 16U, 128U, 125U, true) ==
            NativePlaybackPublishStatus::Accepted,
        "unavailable device-audible policy accepted");
    require(output.lastPublishedLoopDemand().boundaryArmedAtPublication,
        "caller remains fallback when timestamp/history has no exact owner");

    // The UI/controller has already proven that B crossed the cursor, but the callback-committed
    // position visible to output publication can still be one quantum behind B. A newer ordinary
    // active-range MOVE must not erase that one-shot crossing before the callback consumes it.
    output.setAudiblePosition(owner.generation, owner.selectionToken, 120);
    require(runtime.publishDirectLoopPolicy(
        4U, owner.selectionToken, owner.generation, true, 16U, 128U, 126U, true, true,
        41U, 112U) ==
            NativePlaybackPublishStatus::Accepted,
        "crossed policy is accepted while callback position trails B");
    output.setAudiblePosition(owner.generation, owner.selectionToken, 64);
    require(runtime.publishDirectLoopPolicy(
        5U, owner.selectionToken, owner.generation, true, 16U, 256U, 127U, true) ==
            NativePlaybackPublishStatus::Accepted,
        "newer ordinary active-range policy is accepted");
    require(output.lastCapturedLoopDemand().revision == 4U,
        "controller-proven B crossing survives callback-position lag and newer visible B");
    require(output.lastCapturedLoopDemand().bTimelineFrameExclusive == 112U,
        "folded historical B is retained separately from the newest visible B");
    require(output.lastPublishedLoopDemand().bTimelineFrameExclusive == 256U,
        "newest visible B remains the logical demand after a historical capture");
    stopAndJoin(runtime);
}

void boundedHeadReentryRetiresCancelledSpareReaders(bool eosDuringStage = false,
    bool seekBeforeProducerRelease = false) {
    constexpr SongRenderOwnership initial{1'758U, 4U}, outside{1'758U, 5U}, reentry{1'758U, 6U};
    constexpr std::uint64_t a = 2'048U, b = 4'096U, outsideFrame = 8'192U;
    SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
    configureLanes(first, initial.generation);
    for (std::size_t lane = 0U; lane < kLaneCount; ++lane)
        offerLane(first, lane, initial.generation, a, 16'384U, false);
    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    const auto cleanup = onScopeExit([&] { stopAndJoin(runtime); });
    auto bank = assignedBank(1U, initial, a);
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, initial,
        {bank, NativePlaybackBankIntent{}}, controls(1U))) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "bounded reentry starts a real nonzero owner without canonical cache");
    waitUntil([&] { return output.commitCount() == 1U; }, "bounded reentry Original commits");
    require(output.presentFirstCurrentRecord(), "bounded reentry Original reaches callback");
    auto head = managedLoopIntent(1U, initial, a);
    head.boundaryMode = LoopHeadBoundaryMode::RetainedWindow;
    head.cacheKey.endTimelineFrameExclusive = a + 8'192U;
    prepareFullManagedLoopHead(runtime, head, 700U, 1U, initial);
    require(runtime.publishDirectLoopPolicy(1U, initial.selectionToken, initial.generation,
        true, a, b, 0U, true) == NativePlaybackPublishStatus::Accepted, "bounded reentry installs the initial loop");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; }, "initial bounded A commits");
    require(output.presentCommittedLoopCutover(), "callback adopts the initial bounded A");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 1U && output.loopCutoverCommitCount() == 2U; },
        "initial bounded loop owns one active producer and one prepared spare");
    if (seekBeforeProducerRelease) {
        require(!runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner,
            "bounded seek race cannot borrow a canonical cache to conceal a missing body");
        output.setLoopCutoverWriteReleaseBlocked(true);
        const auto release = onScopeExit([&] { output.setLoopCutoverWriteReleaseBlocked(false); });
        require(output.presentCommittedLoopCutover(false),
            "callback adopts the bounded spare before publishing any source position");
        waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
            "worker observes the held callback-owned bounded producer", 4U);
        require(output.loopCutoverWriteReleaseCount() == 1U,
            "the adopted producer remains fenced until its exact release");

        const auto readiness = runtime.renderReadinessDiagnostics();
        const auto protocol = runtime.loopProtocolSnapshot();
        auto highWater = initial.generation;
        for (const auto &lane : readiness.lanes) {
            highWater = std::max(highWater, lane.generation);
            highWater = std::max(highWater, lane.ingressActiveGeneration);
        }
        highWater = std::max(highWater, protocol.state.activeIngressEpoch);
        highWater = std::max(highWater, protocol.state.preparedIngressEpoch);
        if (protocol.ingressRequest.valid) highWater = std::max(highWater, protocol.ingressRequest.ingressEpoch);
        constexpr std::uint64_t seekFrame = 255'360U;
        constexpr std::uint64_t requiredThrough = seekFrame + 12'288U;
        const SongRenderOwnership seekOwner{initial.selectionToken, highWater + 1U};
        for (std::size_t lane = 0U; lane < kLaneCount; ++lane)
            offerLane(first, lane, seekOwner.generation, seekFrame, 16'384U, false);
        bank = assignedBank(2U, seekOwner, seekFrame);
        const auto consumed = runtime.diagnostics().commandSnapshotsConsumed;
        require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, seekOwner,
            {bank, NativePlaybackBankIntent{}}, controls(2U))) == NativePlaybackPublishStatus::Accepted,
            "a distant manual seek publishes exact new-generation PCM after callback adoption");
        waitUntil([&] { return runtime.diagnostics().commandSnapshotsConsumed > consumed; },
            "the held bounded producer observes the new manual seek");
        waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
            "the manual seek waits for callback-owned producer retirement", 4U);
        require(runtime.diagnostics().banks[0U].seekCount == 0U && output.commitCount() == 1U,
            "a held physical producer cannot be overwritten by the bank seek");

        output.setLoopCutoverWriteReleaseBlocked(false);
        waitUntil([&] { return output.commitCount() == 2U; },
            "the released bounded producer yields to the exact manual seek");
        const auto firstRecord = output.currentGenerationFirstRecord();
        require(output.record(firstRecord).sourceStart == static_cast<double>(seekFrame),
            "manual seek begins at its authored frame, never at the retired loop head");
        require(output.presentFirstCurrentRecord(), "callback acknowledges the new manual owner");
        waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, seekOwner); },
            "the exact manual owner becomes committed after physical acknowledgement");
        std::size_t lastRecord = firstRecord;
        waitUntil([&] {
            output.consumeAll();
            runtime.notifyIngressAvailable();
            for (auto index = firstRecord; index < output.recordCount(); ++index) {
                const auto record = output.record(index);
                if (record.generation == seekOwner.generation && record.sourceEnd >= static_cast<double>(requiredThrough)) {
                    lastRecord = index;
                    return true;
                }
            }
            return false;
        }, "manual seek renders contiguous PCM beyond the bounded head reserve", 4'000U);
        double nextFrame = static_cast<double>(seekFrame);
        for (auto index = firstRecord; index <= lastRecord; ++index) {
            const auto record = output.record(index);
            require(record.token == seekOwner.selectionToken && record.generation == seekOwner.generation &&
                record.sourceStart == nextFrame && record.sourceEnd > record.sourceStart &&
                !record.loopEnabled && record.loopPolicyRevision == 0U && record.peak > 0.0F,
                "every new-owner PCM span is audible, linear and contiguous");
            nextFrame = record.sourceEnd;
        }
        const auto after = runtime.renderReadinessDiagnostics();
        require(!after.currentSongCacheBoundToSelectedOwner && after.lanes[0U].ingressFailureGeneration == 0U &&
            runtime.diagnostics().banks[0U].lanes[0U].discontinuityRejects == 0U,
            "callback retirement and distant seek preserve Original ingress without a canonical cache");
        return;
    }
    for (std::size_t lane = 0U; lane < kLaneCount; ++lane)
        offerLane(first, lane, outside.generation, outsideFrame, 16'384U, false);
    bank.revision = 2U; bank.ownership = outside; bank.firstTimelineFrame = outsideFrame;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, outside,
        {bank, NativePlaybackBankIntent{}}, controls(2U))) == NativePlaybackPublishStatus::Accepted,
        "explicit seek past B retires the old bounded producer");
    waitUntil([&] { return output.commitCount() == 2U; }, "outside seek commits exact linear PCM");
    require(output.presentFirstCurrentRecord(), "outside seek becomes the actual callback owner");
    waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, outside); },
        "outside seek owner is physically acknowledged");
    head.cacheKey.loopRevision = 2U;
    prepareFullManagedLoopHead(runtime, head, 800U, 1U, outside);
    require(runtime.publishDirectLoopPolicy(2U, outside.selectionToken, outside.generation,
        true, a, b, 0U, false) == NativePlaybackPublishStatus::Accepted,
        "outside owner retains dormant A-B with a new bounded head");
    waitForRenderProgress(runtime, runtime.diagnostics().renderCycles, "dormant outside range settles", 4U);
    require(!output.loopCutoverIsOpen() && !output.loopCutoverIsCommitted(),
        "a callback already past B must not prepare an unreachable replacement");
    for (std::size_t lane = 0U; lane < kLaneCount; ++lane)
        offerLane(first, lane, reentry.generation, a + 256U, 16'384U, false);
    bank.revision = 3U; bank.ownership = reentry; bank.firstTimelineFrame = a + 256U;
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, reentry,
        {bank, NativePlaybackBankIntent{}}, controls(3U))) == NativePlaybackPublishStatus::Accepted,
        "explicit seek reenters the saved A-B range");
    waitUntil([&] { return output.commitCount() == 3U; }, "reentry commits exact linear PCM");
    require(output.presentFirstCurrentRecord(), "callback acknowledges the reentered owner");
    waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, reentry); },
        "reentered owner is physically acknowledged before replacing its bounded head");
    head.cacheKey.loopRevision = 3U;
    require(runtime.loopProtocolSnapshot().preparation.commits == 2U,
        "initial and dormant owners each committed their exact bounded head");
    try { prepareFullManagedLoopHead(runtime, head, 900U, 1U, reentry); }
    catch (...) { throw std::runtime_error("reentered bounded head is blocked by the cancelled spare's retired reader"); }
    require(runtime.publishDirectLoopPolicy(3U, reentry.selectionToken, reentry.generation,
        true, a, b, 0U, true) == NativePlaybackPublishStatus::Accepted,
        "reentered owner arms its exact replacement head");
    waitUntil([&] { return output.loopCutoverIsCommitted(); }, "reentered owner prepares exact A again");
    const auto beforeTail = runtime.diagnostics().banks[0U].lanes;
    constexpr auto firstTail = a + 256U + 16'384U;
    for (const auto lane : {0U, 1U})
        offerLane(first, lane, reentry.generation, firstTail, 4'096U, eosDuringStage);
    runtime.notifyIngressAvailable();
    waitUntil([&] { const auto lanes = runtime.diagnostics().banks[0U].lanes;
        return lanes[0U].appendedFrames == beforeTail[0U].appendedFrames + 4'096U &&
            lanes[1U].appendedFrames == beforeTail[1U].appendedFrames + 4'096U &&
            (!eosDuringStage || (lanes[0U].endOfStreamMarks == 1U && lanes[1U].endOfStreamMarks == 1U)); },
        "outgoing primary accepts exact Original and inactive stem tails after spare preparation");
    require(output.presentCommittedLoopCutover(), "callback adopts the reentered A head");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 2U; }, "reentered A owns its producer writes");
    if (!eosDuringStage) {
        for (const auto lane : {0U, 1U})
            offerLane(first, lane, reentry.generation, firstTail + 4'096U, 4'096U, false);
        runtime.notifyIngressAvailable();
        waitUntil([&] { const auto lanes = runtime.diagnostics().banks[0U].lanes;
            return lanes[0U].discontinuityRejects != 0U || lanes[1U].discontinuityRejects != 0U ||
                (lanes[0U].appendedFrames == beforeTail[0U].appendedFrames + 8'192U &&
                 lanes[1U].appendedFrames == beforeTail[1U].appendedFrames + 8'192U); },
            "new bounded producer receives both decoders' next contiguous tails");
    }
    for (const auto lane : {0U, 1U})
        require(runtime.renderReadinessDiagnostics().lanes[lane].ingressFailureGeneration == 0U &&
            runtime.diagnostics().banks[0U].lanes[lane].discontinuityRejects == 0U,
            "bounded callback adoption preserves accepted Original and inactive stem PCM");
    const auto begin = output.recordCount(); output.consumeAll();
    const auto boundary = waitForRecord(output, begin, [=](const FakeOutputRecord &record) {
        return record.loopEnabled && record.generation == reentry.generation && record.sourceEnd == static_cast<double>(b);
    }, "reentered bounded producer reaches exact B", 4'000U, &runtime);
    static_cast<void>(waitForRecord(output, boundary + 1U, [=](const FakeOutputRecord &record) {
        return record.loopEnabled && record.generation == reentry.generation && record.sourceStart == static_cast<double>(a);
    }, "reentered bounded producer audibly wraps to exact A", 4'000U, &runtime));
    if (eosDuringStage) {
        require(runtime.publishDirectLoopPolicy(4U, reentry.selectionToken, reentry.generation,
            false, 0U, 0U) == NativePlaybackPublishStatus::Accepted, "Clear retains the staged primary's actual EOS");
        waitUntil([&] { const auto policy = output.lastPublishedLoopPolicy();
            return policy.revision == 4U && !policy.enabled && output.loopCutoverIsCommitted(); },
            "Clear prepares exact linear continuation after staged EOS");
        require(output.presentCommittedLoopCutover(), "callback adopts exact linear Clear after staged EOS");
        waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 3U; }, "linear Clear releases prior loop writes");
        waitUntil([&] { output.consumeAll(); runtime.notifyIngressAvailable();
            return runtime.diagnostics().audibleEnd.sequence != 0U; },
            "EOS received during spare staging survives callback adoption and Clear");
        require(sameOwnership(runtime.diagnostics().audibleEnd.ownership, reentry) &&
            runtime.diagnostics().presentedPosition.lastTimelineFrame == static_cast<std::int64_t>(firstTail + 4'096U - 1U),
            "only the exact reentered owner ends at the real primary decoder boundary");
    }
}

void dormantSeekLoopStageCannotFenceNaturalEnd(bool boundedDormant = false) {
    constexpr SongRenderOwnership initial{1'757U, 4U}, sought{1'757U, 5U};
    constexpr std::uint64_t frames = 32'768U, seekFrame = 28'000U;
    SongLaneIngressBank first(65'536U, 480U), second(65'536U, 480U);
    require(first.configureLane(0U, kSampleRate, initial.generation), "dormant seek Original configured");
    const std::uint64_t initialFrame = boundedDormant ? 2'048U : 0U;
    offerLane(first, 0U, initial.generation, initialFrame, static_cast<std::size_t>(frames - initialFrame));
    FakeOutput output(8'192U, 64U);
    output.setMaximumAcceptance(256U);
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    const auto cleanup = onScopeExit([&] { stopAndJoin(runtime); });
    auto bank = assignedBank(1U, initial, initialFrame, 0U);
    bank.stemPackageHash = 0U; bank.cacheFrameUpperBound = frames;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, initial,
        {bank, NativePlaybackBankIntent{}}, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "dormant seek fixture starts actual paused Original");
    waitUntil([&] { return output.commitCount() == 1U && (boundedDormant ?
        !runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner :
        runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 1U); },
        "dormant seek fixture establishes actual Original cache availability");
    // Exercise the schedule where the prior generation seals before the seek. Lane
    // diagnostics are cumulative, so the sought EOS is then the second mark.
    if (boundedDormant) waitUntil([&] {
        return runtime.diagnostics().banks[0U].lanes[0U].endOfStreamMarks == 1U;
    }, "controlled initial owner EOS precedes seek replacement");
    offerLane(first, 0U, sought.generation, seekFrame,
        boundedDormant ? 512U : static_cast<std::size_t>(frames - seekFrame), !boundedDormant);
    bank.revision = 2U; bank.ownership = sought; bank.firstTimelineFrame = seekFrame;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, sought,
        {bank, NativePlaybackBankIntent{}}, controls(2U))) == NativePlaybackPublishStatus::Accepted,
        "cached seek beyond retained A-B begins playing the exact new generation");
    waitUntil([&] { return output.commitCount() == 2U &&
        sameOwnership(runtime.renderReadinessDiagnostics().ownership, sought) &&
        (boundedDormant || runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner); },
        "cached seek receives actual linear PCM before callback acknowledgement");
    if (boundedDormant) {
        auto head = managedLoopIntent(1U, sought, 2'048U);
        head.boundaryMode = LoopHeadBoundaryMode::RetainedWindow;
        head.cacheKey.endTimelineFrameExclusive = 10'240U;
        prepareFullManagedLoopHead(runtime, head, 1'000U, 1U, sought);
    }
    require(runtime.publishDirectLoopPolicy(1U, sought.selectionToken, sought.generation,
        true, 2'048U, 4'096U, 0U, false) == NativePlaybackPublishStatus::Accepted,
        "seek rebinds retained dormant A-B before its new callback arrives");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "retained A head is physically prepared while callback still reports the prior owner");
    if (boundedDormant) {
        const auto before = runtime.diagnostics().banks[0U].lanes[0U].appendedFrames;
        offerLane(first, 0U, sought.generation, seekFrame + 512U, 256U, false);
        runtime.notifyIngressAvailable();
        waitUntil([&] { const auto lane = runtime.diagnostics().banks[0U].lanes[0U];
            return lane.discontinuityRejects != 0U || lane.appendedFrames == before + 256U; },
            "actual unsealed Original feeds its suffix before the sought callback acknowledges");
        require(runtime.renderReadinessDiagnostics().lanes[0U].ingressFailureGeneration == 0U,
            "unreachable bounded spare must not reject the healthy Original beyond its head end");
    }
    require(output.presentFirstCurrentRecord(), "callback acknowledges the actual sought linear PCM");
    waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, sought) &&
        runtime.diagnostics().presentedPosition.lastTimelineFrame >= static_cast<std::int64_t>(seekFrame); },
        "exact callback position proves the retained A-B range is behind the seek");
    waitForRenderProgress(runtime, runtime.diagnostics().renderCycles, "nonterminal sought owner settles", 4U);
    require(runtime.diagnostics().endOrdering.pipelineEndObserved.sequence == 0U,
        "nonterminal continuation check precedes the actual linear EOS boundary");
    require(output.loopCutoverIsCommitted(),
        "a nonterminal dormant continuation must remain prepared until exact linear EOS");
    if (boundedDormant) {
        const auto marksBeforeFinalOffer = runtime.diagnostics().banks[0U].lanes[0U].endOfStreamMarks;
        offerLane(first, 0U, sought.generation, seekFrame + 768U,
            static_cast<std::size_t>(frames - seekFrame - 768U));
        runtime.notifyIngressAvailable();
        waitUntilDiagnosed([&] { return runtime.diagnostics().banks[0U].lanes[0U].endOfStreamMarks > marksBeforeFinalOffer ||
            runtime.renderReadinessDiagnostics().lanes[0U].ingressFailureGeneration != 0U; },
            [&] {
                const auto diagnostic = runtime.diagnostics();
                const auto ready = runtime.renderReadinessDiagnostics();
                const auto ingress = first.lane(0U)->diagnostics();
                const auto &lane = diagnostic.banks[0U].lanes[0U];
                const auto dormant = output.committedLoopCutoverHead();
                return "boundedDormant=" + std::to_string(boundedDormant) +
                    " acceptedInputFrames=" + std::to_string(ingress.acceptedInputFrames) +
                    " consumedSourceFrames=" + std::to_string(ingress.sourceFramesConsumed) +
                    " readableSourceFrames=" + std::to_string(ingress.readableSourceFrames) +
                    " pulledFrames=" + std::to_string(lane.pulledFrames) +
                    " appendedFrames=" + std::to_string(lane.appendedFrames) +
                    " retainedSuffixFrames=" + std::to_string(lane.retainedSuffixFrames) +
                    " ingressEosGeneration=" + std::to_string(ingress.endOfStreamGeneration) +
                    " ingressEosAccepted=" + std::to_string(ingress.acceptedEndOfStreamMarks) +
                    " ingressEosCompleted=" + std::to_string(ingress.completedEndOfStreams) +
                    " runtimeEosMarks=" + std::to_string(lane.endOfStreamMarks) +
                    " marksBeforeFinalOffer=" + std::to_string(marksBeforeFinalOffer) +
                    " ingressConsumerGeneration=" + std::to_string(ingress.consumerGeneration) +
                    " finalSourceFrameExclusive=" + std::to_string(ingress.finalSourceFrameExclusive) +
                    " ingressFailureGeneration=" + std::to_string(ready.lanes[0U].ingressFailureGeneration) +
                    " committedGeneration=" + std::to_string(diagnostic.committedOwnership.generation) +
                    " dormantGeneration=" + std::to_string(dormant.generation) +
                    " dormantToken=" + std::to_string(dormant.token) +
                    " dormantRevision=" + std::to_string(dormant.loopPolicyRevision) +
                    " dormantCommitted=" + std::to_string(output.loopCutoverIsCommitted()) +
                    " outputAccepted=" + std::to_string(diagnostic.outputFramesAccepted) +
                    " outputQueued=" + std::to_string(output.queued());
            },
            "dormant bounded spare cannot backpressure linear Original EOS after callback acknowledgement");
        require(runtime.renderReadinessDiagnostics().lanes[0U].ingressFailureGeneration == 0U,
            "exact dormant callback keeps the healthy ordinary Original ingress authoritative");
        const auto ingress = first.lane(0U)->diagnostics();
        require(ingress.endOfStreamGeneration == sought.generation &&
            ingress.consumerGeneration == sought.generation &&
            runtime.diagnostics().banks[0U].lanes[0U].endOfStreamMarks == marksBeforeFinalOffer + 1U,
            "new EOS mark belongs to the sought generation after the final ordinary suffix");
    }
    waitUntil([&] { output.consumeAll(); runtime.notifyIngressAvailable();
        return runtime.diagnostics().audibleEnd.sequence != 0U; },
        "a dormant pre-callback A stage must not suppress exact natural EOS after the sought PCM drains");
    const auto diagnostics = runtime.diagnostics();
    const auto ended = diagnostics.audibleEnd;
    const auto terminal = diagnostics.presentedPosition;
    require(sameOwnership(ended.ownership, sought) && ended.directLoopPolicyRevision == 1U &&
        !output.loopCutoverIsCommitted() && !output.loopCutoverIsOpen() &&
        output.loopCutoverWriteReleaseCount() == 0U && diagnostics.endOrdering.audibleEndPublished.terminalAfterFrame &&
        terminal.loopPolicyRevision == 0U && terminal.lastTimelineFrame == static_cast<std::int64_t>(frames - 1U),
        "natural EOS retains exact seek and loop revision proof after retiring the unreachable spare");
    double next = static_cast<double>(seekFrame);
    for (std::size_t index = output.currentGenerationFirstRecord(); index < output.recordCount(); ++index) {
        const auto record = output.record(index);
        // The fake retains prepared spare records as well as active-ring records.
        if (record.loopPolicyRevision != 0U) continue;
        require(record.generation == sought.generation && record.token == sought.selectionToken &&
            record.sourceStart == next && !record.loopEnabled,
            "dormant A-B never jumps the sought consumer back into its old range");
        next = record.sourceEnd;
    }
    require(next == static_cast<double>(frames), "the exact ordinary sought PCM reaches its real EOS");
}

void dormantPastDirectLoopCannotFenceAutomaticSuccessor() {
    constexpr SongRenderOwnership outgoing{1'754U, 4U};
    constexpr SongRenderOwnership successor{1'755U, 5U};
    SongLaneIngressBank first(4'096U, 64U);
    SongLaneIngressBank second(4'096U, 64U);
    configureLanes(first, outgoing.generation);
    configureLanes(second, successor.generation);
    offerLane(first, 0U, outgoing.generation, 0U, 150U);
    offerLane(second, 0U, successor.generation, 0U, 2'400U);
    FakeOutput output(4'096U, 64U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(4'096U, 1U),
        first,
        second,
        output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, outgoing), assignedBank(1U, successor)};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        outgoing,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "dormant-past successor outgoing owner selected");
    require(runtime.start(), "dormant-past successor runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "dormant-past successor outgoing owner committed");
    require(output.presentFirstCurrentRecord(),
        "dormant-past successor outgoing owner reaches callback");
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, outgoing);
    }, "dormant-past successor outgoing owner becomes audible");
    output.advanceAudiblePosition(148U);
    waitUntil([&] {
        const NativeAudioPresentedPosition position = runtime.diagnostics().presentedPosition;
        return position.valid() && position.generation == outgoing.generation &&
            position.selectionToken == outgoing.selectionToken &&
            position.lastTimelineFrame >= 148;
    }, "near-EOS cursor is beyond the authored range");
    require(runtime.publishDirectLoopPolicy(
        1U,
        outgoing.selectionToken,
        outgoing.generation,
        true,
        140U,
        147U,
        0U,
        false) == NativePlaybackPublishStatus::Accepted,
        "dormant-past successor demand accepted");
    waitForRenderProgress(
        runtime,
        runtime.diagnostics().renderCycles,
        "dormant-past successor demand is observed",
        4U);
    require(output.loopCutoverBeginCount() == 0U,
        "dormant-past successor demand never stages unreachable A PCM");

    auto automatic = selectedCommand(
        2U,
        NativePlaybackBank::First,
        outgoing,
        banks,
        controls(2U));
    automatic.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
        true,
        false,
        81U,
        outgoing,
        NativePlaybackBank::Second,
        successor};
    require(runtime.publishCommand(automatic) == NativePlaybackPublishStatus::Accepted,
        "automatic successor is accepted with dormant-past demand");
    waitUntil([&] { return output.drainCommitCount() == 1U; },
        "dormant-past demand cannot fence automatic successor");
    const auto terminalPermission = output.terminalSilenceAuthorization();
    require(terminalPermission == 0U || terminalPermission == successor.generation,
        "near-EOS dormant A-B never permits outgoing terminal silence while a successor is prepared");
    require(runtime.diagnostics().audibleEnd.sequence == 0U,
        "near-EOS dormant A-B cannot publish an outgoing audible end before handoff");
    output.consumeAll();
    const FakeOutput::CallbackResult successorCallback = output.consumeCallback(64U);
    require(successorCallback.successorFrames == 64U && output.playing(),
        "automatic successor stays audible with requested Play intact");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return sameOwnership(diagnostic.requestedOwnership, successor) &&
            sameOwnership(diagnostic.committedOwnership, successor);
    }, "runtime adopts successor after dormant-past outgoing demand");
    stopAndJoin(runtime);
}

void dormantDirectLoopPreparesBeforeAudibleRangeEntry() {
    constexpr SongRenderOwnership owner{1'755U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    constexpr std::uint64_t a = 8'000U;
    constexpr std::uint64_t b = 9'000U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(4'096U, 64);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(32'768U, 256U),
        first,
        second,
        output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "dormant direct-loop owner selected");
    require(runtime.start(), "dormant direct-loop runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "dormant direct-loop owner committed");
    require(output.presentFirstCurrentRecord(),
        "dormant direct-loop owner publishes callback cursor");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "dormant direct-loop complete cache ready", 8'000U);

    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        a,
        b) == NativePlaybackPublishStatus::Accepted,
        "dormant direct-loop policy accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy policy = output.lastPublishedLoopPolicy();
        return policy.revision == 1U && policy.physicalReady &&
            policy.hasPhysicalCutover() && output.loopCutoverCommitCount() == 1U;
    }, "dormant direct-loop policy prepares before range entry");
    const NativeAudioOutputLoopPolicy prepared = output.lastPublishedLoopPolicy();
    require(prepared.enabled && prepared.cutoverTimelineFrameExclusive == b &&
            prepared.continuationTimelineFrame == a &&
            !prepared.boundaryArmedAtPublication,
        "dormant direct-loop candidate preserves exact B-to-A coordinates");
    const FakeOutputRecord preparedHead = output.committedLoopCutoverHead();
    require(preparedHead.loopEnabled && preparedHead.loopPolicyRevision == 1U &&
            preparedHead.loopRevision == 1U &&
            static_cast<std::uint64_t>(std::floor(preparedHead.sourceStart)) == a,
        "dormant candidate head is physically loop-enabled from A");

    const NativeAudioPresentedPosition before = runtime.diagnostics().presentedPosition;
    require(before.valid() && before.lastTimelineFrame >= 0 &&
            static_cast<std::uint64_t>(before.lastTimelineFrame) < a,
        "transport remains linear before dormant A");
    const std::uint64_t commitsBeforeEntry = output.loopCutoverCommitCount();
    output.advanceAudiblePosition(
        a - static_cast<std::uint64_t>(before.lastTimelineFrame));
    waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
        "audible range entry observes the prepared dormant policy", 4U);
    require(output.loopCutoverCommitCount() == commitsBeforeEntry &&
            output.lastPublishedLoopPolicy().physicalReady,
        "range entry does not cause delayed physical preparation");

    require(output.presentCommittedLoopCutover(),
        "callback installs the prepared dormant loop after range entry");
    waitUntil([&] {
        return runtime.diagnostics().presentedPosition.loopPolicyRevision ==
            prepared.physicalRevision();
    }, "worker observes the installed dormant loop");
    waitForRenderProgress(
        runtime,
        runtime.diagnostics().renderCycles,
        "installed dormant loop becomes the active producer",
        4U);
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy spare = output.lastPublishedLoopPolicy();
        return output.loopCutoverCommitCount() == commitsBeforeEntry + 1U &&
            spare.nextRevisionOnly &&
            spare.cutoverSourceRevision == prepared.physicalRevision();
    }, "installed dormant loop primes one dormant continuation");
    const std::uint64_t commitsBeforePastEdit = output.loopCutoverCommitCount();

    constexpr std::uint64_t pastA = 16U;
    constexpr std::uint64_t pastB = 64U;
    require(runtime.publishDirectLoopPolicy(
        2U,
        owner.selectionToken,
        owner.generation,
        true,
        pastA,
        pastB,
        0U,
        false) == NativePlaybackPublishStatus::Accepted,
        "disjoint range behind the cursor is accepted as dormant-past");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy bridge = output.lastPublishedLoopPolicy();
        return bridge.revision == 2U && bridge.physicalReady &&
            bridge.cutoverTimelineFrameExclusive == b &&
            bridge.continuationTimelineFrame == b &&
            bridge.cutoverSourceRevision == prepared.physicalRevision() &&
            output.loopCutoverCommitCount() == commitsBeforePastEdit + 1U;
    }, "dormant-past edit prepares one exact linear escape from the installed loop");
    const NativeAudioOutputLoopPolicy bridge = output.lastPublishedLoopPolicy();
    const FakeOutputRecord bridgeHead = output.committedLoopCutoverHead();
    require(!bridgeHead.loopEnabled &&
            bridgeHead.loopPolicyRevision == bridge.physicalRevision() &&
            static_cast<std::uint64_t>(std::floor(bridgeHead.sourceStart)) == b,
        "dormant-past bridge preserves the old boundary without jumping to new A");
    require(output.presentCommittedLoopCutover(),
        "callback atomically installs the dormant-past linear bridge");
    waitUntil([&] {
        return runtime.diagnostics().presentedPosition.loopPolicyRevision ==
            bridge.physicalRevision();
    }, "worker observes the dormant-past linear producer");
    const std::uint64_t beginsAfterBridge = output.loopCutoverBeginCount();
    const std::uint64_t commitsAfterBridge = output.loopCutoverCommitCount();
    waitForRenderProgress(
        runtime,
        runtime.diagnostics().renderCycles,
        "dormant-past range remains linear after callback proof",
        4U);
    require(output.loopCutoverBeginCount() == beginsAfterBridge &&
            output.loopCutoverCommitCount() == commitsAfterBridge && output.playing(),
        "dormant-past range never rebuilds an unreachable loop or stops playback");
    stopAndJoin(runtime);
}

void cancelledDirectLoopRestoreClearsStagedEndEvidence() {
    constexpr SongRenderOwnership owner{1'756U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    constexpr std::uint64_t a = 128U;
    constexpr std::uint64_t b = 1'536U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(32'768U, 256U),
        first,
        second,
        output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "cancel-restore owner selected");
    require(runtime.start(), "cancel-restore runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "cancel-restore owner committed");
    require(output.presentFirstCurrentRecord(),
        "cancel-restore owner publishes callback cursor");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "cancel-restore complete cache ready", 8'000U);

    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        a,
        b) == NativePlaybackPublishStatus::Accepted,
        "cancel-restore prior loop accepted");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "cancel-restore prior A role committed");
    const NativeAudioOutputLoopPolicy prepared = output.lastPublishedLoopPolicy();
    require(prepared.revision == 1U && prepared.enabled &&
            prepared.physicalReady && prepared.hasPhysicalCutover() &&
            prepared.aTimelineFrame == a &&
            prepared.bTimelineFrameExclusive == b &&
            prepared.physicalRevision() != 0U,
        "cancel-restore prior loop has exact logical and physical identity");

    const std::size_t nearBoundaryRecord = waitForRecord(
        output,
        0U,
        [b](const FakeOutputRecord &record) {
            return record.loopPolicyRevision == 0U && !record.loopEnabled &&
                static_cast<std::uint64_t>(std::floor(record.sourceEnd)) == b;
        },
        "cancel-restore active linear record near prior B");
    require(output.presentRecord(nearBoundaryRecord),
        "cancel-restore callback reaches the active linear frame before prior B");
    waitUntil([&] {
        const NativeAudioPresentedPosition position = runtime.diagnostics().presentedPosition;
        return position.valid() && position.loopPolicyRevision == 0U &&
            position.lastTimelineFrame == static_cast<std::int64_t>(b - 1U);
    }, "cancel-restore callback is positioned at prior B minus one");

    require(runtime.publishDirectLoopPolicy(
        2U,
        owner.selectionToken,
        owner.generation,
        false,
        0U,
        0U) == NativePlaybackPublishStatus::Accepted,
        "cancel-restore disable accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy demand = output.lastPublishedLoopDemand();
        return demand.revision == 2U && !demand.enabled && !demand.physicalReady &&
            !output.loopCutoverIsOpen() && !output.loopCutoverIsCommitted();
    }, "disable cancels the sole prepared role without a replacement");
    require(output.loopCutoverCommitCount() == 1U && output.playing(),
        "disable leaves the active producer linear and playing");

    const std::size_t recordsBeforeRefill = output.recordCount();
    output.consumeAll();
    bool sawLinearRefill = false;
    waitUntil([&] {
        for (std::size_t index = recordsBeforeRefill; index < output.recordCount(); ++index) {
            const FakeOutputRecord record = output.record(index);
            if (record.loopPolicyRevision == 0U && !record.loopEnabled &&
                record.sourceStart >= static_cast<double>(b)) {
                sawLinearRefill = true;
                break;
            }
        }
        if (!sawLinearRefill) output.consumeAll();
        return sawLinearRefill && output.queued() != 0U;
    }, "linear producer refills past prior B after cancellation");
    require(sawLinearRefill,
        "post-cancellation refill is untagged linear PCM beyond prior B");
    const NativePlaybackRuntimeDiagnostics diagnostics = runtime.diagnostics();
    require(diagnostics.outputEndOfStreamEvents == 0U,
        "cancelled role leaves no producer EOS latch");
    require(diagnostics.audibleEnd.sequence == 0U,
        "cancelled role leaves no audible terminal hold");
    require(output.terminalSilenceAuthorization() == owner.generation,
        "disable restores ordinary linear natural-end authorization without triggering it");
    require(output.playing(),
        "cancelled role cannot stop active linear refills");
    stopAndJoin(runtime);
}

void sameTokenSeekRetiresInstalledDirectLoopPhysicalIdentity() {
    constexpr SongRenderOwnership owner{1'760U, 2U};
    constexpr SongRenderOwnership seekOwner{owner.selectionToken, 3U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    constexpr std::uint64_t seekFrame = 512U;
    constexpr std::uint64_t a = 128U;
    constexpr std::uint64_t b = 1'536U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(32'768U, 256U),
        first,
        second,
        output.port());
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "direct-loop seek owner selected");
    require(runtime.start(), "direct-loop seek runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "direct-loop seek owner committed");
    require(output.presentFirstCurrentRecord(),
        "direct-loop seek owner publishes callback cursor");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "direct-loop seek complete cache ready", 8'000U);

    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        a,
        b) == NativePlaybackPublishStatus::Accepted,
        "direct-loop seek prior policy accepted");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "direct-loop seek prior physical role committed");
    const NativeAudioOutputLoopPolicy prior = output.lastPublishedLoopPolicy();
    require(prior.revision == 1U && prior.generation == owner.generation &&
            prior.physicalRevision() != 0U,
        "direct-loop seek prior role has exact physical identity");
    require(output.presentCommittedLoopCutover(),
        "callback installs the prior internal loop");
    waitUntil([&] {
        return runtime.diagnostics().presentedPosition.loopPolicyRevision ==
            prior.physicalRevision();
    }, "worker observes the prior installed loop");
    waitUntil([&] { return output.loopCutoverCommitCount() == 2U; },
        "installed loop primes one dormant continuation before seek");
    const NativeAudioOutputLoopPolicy spare = output.lastPublishedLoopPolicy();
    require(spare.nextRevisionOnly &&
            spare.cutoverSourceRevision == prior.physicalRevision() &&
            spare.physicalRevision() > prior.physicalRevision(),
        "dormant continuation belongs only to the installed physical role");
    output.setLoopCutoverWriteReleaseBlocked(true);
    require(output.presentCommittedLoopCutover(),
        "callback installs the dormant continuation before the seek command");
    waitForRenderProgress(
        runtime,
        runtime.diagnostics().renderCycles,
        "worker observes blocked continuation write release",
        4U);
    const std::uint64_t cutoversBeforeSeek = output.loopCutoverCommitCount();
    require(output.loopCutoverBeginCount() == cutoversBeforeSeek &&
            output.loopCutoverWriteReleaseCount() == 1U &&
            !output.loopCutoverIsOpen() && !output.loopCutoverIsCommitted(),
        "callback-owned continuation remains blocked on exact producer release");

    for (std::size_t lane = 0U; lane < kLaneCount; ++lane) {
        offerLane(first, lane, seekOwner.generation, seekFrame, 8'192U);
    }
    banks[0] = assignedBank(2U, seekOwner, seekFrame);
    SongRenderControlSnapshot seekControls = controls(
        2U, 1.0F, 0.0F, MixerSourceMode::Stems);
    seekControls.sourceIntentRevision = 2U;
    seekControls.stemIntentRevision = 2U;
    const std::uint64_t commandsConsumedBeforeSeek =
        runtime.diagnostics().commandSnapshotsConsumed;
    require(runtime.publishCommand(selectedCommand(
        2U,
        NativePlaybackBank::First,
        seekOwner,
        banks,
        seekControls)) == NativePlaybackPublishStatus::Accepted,
        "same-token next-generation bank seek accepted");
    waitUntil([&] {
        return runtime.diagnostics().commandSnapshotsConsumed >
            commandsConsumedBeforeSeek;
    }, "blocked callback-owned continuation consumes the seek command");
    waitForRenderProgress(
        runtime,
        runtime.diagnostics().renderCycles,
        "blocked callback-owned continuation prevents seek adoption",
        4U);
    require(runtime.diagnostics().banks[0].seekCount == 0U &&
            output.commitCount() == 1U &&
            output.loopCutoverWriteReleaseCount() == 1U &&
            output.loopCutoverBeginCount() == cutoversBeforeSeek,
        "seek cannot advance until callback-owned producer release succeeds");
    output.setLoopCutoverWriteReleaseBlocked(false);
    waitUntil([&] {
        return runtime.diagnostics().banks[0].seekCount == 1U &&
            output.commitCount() == 2U;
    }, "same-token next-generation seek stages linear output", 8'000U);
    require(output.loopCutoverBeginCount() == cutoversBeforeSeek,
        "released callback-owned producer cannot reopen the old-owner loop role");
    const std::size_t seekRecordStart = output.currentGenerationFirstRecord();
    static_cast<void>(waitForRecord(
        output,
        seekRecordStart,
        [seekOwner](const FakeOutputRecord &record) {
            return record.generation == seekOwner.generation &&
                record.controlRevision == 2U &&
                record.sourceIntentRevision == 2U &&
                record.stemIntentRevision == 2U &&
                record.sourceMode == NativeAudioSourceMode::Stems &&
                record.stemBusReadyMask == kStemwiseAllStemBusesReadyMask;
        },
        "released next-generation seek owns exact canonical Stems controls"));
    require(output.presentFirstCurrentRecord(),
        "callback presents the next-generation seek");
    waitUntil([&] {
        const auto diagnostics = runtime.diagnostics();
        return sameOwnership(diagnostics.committedOwnership, seekOwner) &&
            diagnostics.presentedPosition.generation == seekOwner.generation &&
            diagnostics.presentedPosition.loopPolicyRevision == 0U;
    }, "next-generation owner is presented linearly");

    const std::size_t recordsBeforeLinearRefill = output.recordCount();
    output.consumeAll();
    const std::size_t linearRefill = waitForRecord(
        output,
        recordsBeforeLinearRefill,
        [seekOwner](const FakeOutputRecord &record) {
            return record.generation == seekOwner.generation &&
                record.loopPolicyRevision == 0U && !record.loopEnabled;
        },
        "next-generation seek continues with linear refill");
    require(output.presentRecord(linearRefill),
        "callback presents next-generation linear refill");
    for (std::size_t index = seekRecordStart; index < output.recordCount(); ++index) {
        const FakeOutputRecord record = output.record(index);
        if (record.generation != seekOwner.generation) continue;
        require(record.loopPolicyRevision == 0U && record.loopRevision == 0U &&
                !record.loopEnabled,
            "next-generation output stays linear before a new direct policy");
    }
    require(output.loopCutoverBeginCount() == cutoversBeforeSeek,
        "bank seek cannot begin another old-owner loop role");
    require(output.loopCutoverCommitCount() == cutoversBeforeSeek,
        "bank seek cannot commit another old-owner loop role");
    require(!output.loopCutoverIsOpen() && !output.loopCutoverIsCommitted(),
        "bank seek retires both old-owner loop roles");
    require(output.playing(),
        "bank seek retirement preserves Play");

    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return sameOwnership(readiness.ownership, seekOwner) &&
            readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "next-generation direct-loop cache is owner-bound", 8'000U);
    require(runtime.publishDirectLoopPolicy(
        2U,
        seekOwner.selectionToken,
        seekOwner.generation,
        true,
        a,
        b,
        0U,
        true) == NativePlaybackPublishStatus::Accepted,
        "new-generation direct policy accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy policy = output.lastPublishedLoopPolicy();
        return policy.revision == 2U && policy.generation == seekOwner.generation &&
            policy.physicalReady &&
            output.loopCutoverCommitCount() == cutoversBeforeSeek + 1U;
    }, "new-generation policy creates its own physical role");
    const NativeAudioOutputLoopPolicy rebound = output.lastPublishedLoopPolicy();
    require(rebound.cutoverSourceRevision == 0U &&
            rebound.physicalRevision() > prior.physicalRevision() &&
            rebound.aTimelineFrame == a && rebound.bTimelineFrameExclusive == b,
        "new-generation direct policy starts from linear state with fresh identity");
    const FakeOutputRecord reboundHead = output.committedLoopCutoverHead();
    require(reboundHead.generation == seekOwner.generation && reboundHead.loopEnabled &&
            reboundHead.loopPolicyRevision == rebound.physicalRevision() &&
            reboundHead.loopRevision == rebound.physicalRevision(),
        "new-generation physical head carries only its fresh loop identity");
    for (std::size_t index = seekRecordStart; index < output.recordCount(); ++index) {
        const FakeOutputRecord record = output.record(index);
        if (record.generation != seekOwner.generation) continue;
        require(record.loopPolicyRevision != prior.physicalRevision() &&
                record.loopRevision != prior.physicalRevision(),
            "old physical loop identity never appears on next-generation output");
    }
    require(output.playing(),
        "same-token generation seek and fresh loop policy never stop playback");
    stopAndJoin(runtime);
}

void sameSongSeekReusesUnconsumedPreparedLoopHead(bool withStems,
    std::uint64_t nextB = 24'000U, bool expectReuse = true) {
    constexpr SongRenderOwnership owner{1'761U, 2U};
    constexpr SongRenderOwnership next{owner.selectionToken, 3U};
    constexpr std::uint64_t a = 512U, b = 24'000U;
    SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
    configureLanes(first, owner.generation);
    const auto laneCount = withStems ? kLaneCount : 1U;
    offerAllLanes(first, owner.generation, 32'768U, laneCount);
    FakeOutput output(4'096U, 256U);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    const auto tuning = controls(1U, 1.35F, 3.0F,
        withStems ? MixerSourceMode::Stems : MixerSourceMode::Original);
    if (!withStems) { banks[0].stemPackageHash = 0U; banks[0].stemLaneCount = 0U; }
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
                owner, banks, tuning)) == NativePlaybackPublishStatus::Accepted && runtime.start(),
            "retained head owner starts");
    waitUntil([&] {
        return output.commitCount() == 1U &&
            runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == (1U << laneCount) - 1U;
    }, "retained head all-lane cache ready");
    require(output.presentFirstCurrentRecord(), "retained head current owner presented");
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation,
                true, a, b) == NativePlaybackPublishStatus::Accepted, "retained head initial demand");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; }, "retained head initially ready");
    const auto oldHead = output.committedLoopCutoverHead();
    waitUntil([&] { return runtime.renderReadinessDiagnostics().fixedLoopHeadPrepared; },
            "committed exact-owner A head authorizes loop editing");
    for (std::size_t lane = 0U; lane < laneCount; ++lane) {
        offerLane(first, lane, next.generation, 6'000U, 8'192U);
    }
    banks[0] = assignedBank(2U, next, 6'000U);
    if (!withStems) { banks[0].stemPackageHash = 0U; banks[0].stemLaneCount = 0U; }
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First,
                next, banks, tuning)) == NativePlaybackPublishStatus::Accepted, "retained head seek accepted");
    waitUntil([&] { return output.commitCount() == 2U; }, "retained head seek committed");
    require(output.presentFirstCurrentRecord(), "retained head seek presented");
    waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
        "retained head active reserve ready", 4U);
    waitUntil([&] { return sameOwnership(runtime.renderReadinessDiagnostics().ownership, next); },
            "seek readiness snapshot belongs to the new owner");
    require(!runtime.renderReadinessDiagnostics().fixedLoopHeadPrepared,
            "a seek cannot inherit its old owner's head readiness");
    const auto rendered = runtime.diagnostics().outputFramesRendered;
    const auto allocations = allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    const auto published = runtime.publishDirectLoopPolicy(2U, next.selectionToken, next.generation,
        true, a, nextB, 0U, true);
    waitUntil([&] { return output.loopCutoverCommitCount() == 2U; }, "retained head rebound ready");
    allocation_probe::enabled.store(false, std::memory_order_release);
    const auto newHead = output.committedLoopCutoverHead();
    require(published == NativePlaybackPublishStatus::Accepted,
            "same-song seek accepts the new loop boundary");
    if (!expectReuse) {
        require(runtime.diagnostics().outputFramesRendered > rendered &&
                    output.lastPublishedLoopPolicy().bTimelineFrameExclusive == nextB,
                "B intersecting retained lookahead rebuilds PCM for the new boundary");
        stopAndJoin(runtime);
        return;
    }
    require(runtime.diagnostics().outputFramesRendered == rendered &&
                allocation_probe::count.load(std::memory_order_relaxed) == allocations,
            "same-song seek reuses prepared head without rendering or allocation");
    require(newHead.generation == next.generation && newHead.token == next.selectionToken &&
                newHead.fingerprint > oldHead.fingerprint &&
                newHead.loopPolicyRevision != oldHead.loopPolicyRevision &&
                newHead.sourceStart == oldHead.sourceStart && newHead.sourceEnd == oldHead.sourceEnd &&
                newHead.frames == oldHead.frames && newHead.peak == oldHead.peak &&
                newHead.stemBusReadyMask == (withStems ? kStemwiseAllStemBusesReadyMask : 0U),
            "reused PCM has fresh owner, fingerprint and loop identities with every bus intact");
    stopAndJoin(runtime);
}

void partialCachePromotionFinishesItsRetainedOriginalPrefix() {
    using stemwise::audio::engine::CurrentSongCacheKey;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership initial{1'762U, 3U}, selected{1'763U, 7U}, builder{selected.selectionToken, 6U};
    constexpr std::uint64_t prefixEnd = 16'384U, nextEnd = prefixEnd + 4'096U;
    SongLaneIngressBank first(65'536U, 480U), second(65'536U, 480U);
    require(first.configureLane(0U, kSampleRate, initial.generation) &&
        second.configureLane(0U, kSampleRate, builder.generation), "retained migration Originals configured");
    offerLane(first, 0U, initial.generation, 40'000U, 20'000U, false);
    offerLane(second, 0U, builder.generation, 0U, prefixEnd, false);
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    const auto cleanup = onScopeExit([&] {
        fault.store(0); releaseBlocked.store(true); stopAndJoin(runtime);
    });
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, initial, 40'000U, 0U), assignedBank(1U, builder, 0U, 0U)};
    for (auto &bank : banks) { bank.stemPackageHash = 0U; bank.cacheFrameUpperBound = 60'000U; }
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, initial, banks, controls(1U), false)) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "a different-token frame-zero bank prewarms through ordinary ingress");
    waitUntil([&] { const auto progress = runtime.diagnostics();
        return progress.banks[0U].lanes[0U].appendedFrames == 20'000U &&
            progress.banks[1U].lanes[0U].appendedFrames == prefixEnd;
    }, "the complete prewarmed prefix is retained before its token becomes cache-eligible");
    blocked.store(false); blockTimedOut.store(false); releaseBlocked.store(false); fault.store(6);
    offerLane(first, 0U, selected.generation, 40'000U, 20'000U, false);
    banks[0U] = assignedBank(2U, selected, 40'000U, 0U);
    banks[0U].stemPackageHash = 0U; banks[0U].cacheFrameUpperBound = 60'000U;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, selected, banks, controls(2U), false)) ==
        NativePlaybackPublishStatus::Accepted, "normal selection makes the retained frame-zero bank an exact same-song builder");
    waitUntil([&] { return blocked.load() && runtime.diagnostics().banks[1U].lanes[0U].appendedFrames == prefixEnd; },
        "real storage admission starts only after the entire builder prefix is already retained");
    require(!blockTimedOut.load(), "the retained prefix is established within the bounded storage barrier");
    fault.store(0); releaseBlocked.store(true); runtime.notifyIngressAvailable();
    const CurrentSongCacheKey key{selected.selectionToken, selected.generation,
        banks[0U].songIdentityHash, banks[0U].originalContentHash, 0U};
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner &&
        runtime.pcmCacheRangeReady(key, 1U, 0U, 256U); }, "the sought owner receives a real published canonical prefix");
    offerLane(second, 0U, builder.generation, prefixEnd, nextEnd - prefixEnd, false);
    runtime.notifyIngressAvailable();
    try {
        waitUntil([&] { return runtime.pcmCacheRangeReady(key, 1U, prefixEnd - 256U, prefixEnd) &&
            runtime.pcmCacheRangeReady(key, 1U, nextEnd - 256U, nextEnd); },
            "promotion must finish every retained Original frame before accepting the builder's next contiguous suffix");
    } catch (...) {
        const auto ready = runtime.renderReadinessDiagnostics();
        const auto progress = runtime.diagnostics();
        const auto lane = progress.banks[1U].lanes[0U];
        std::cerr << "Retained prefix migration: bound=" << ready.currentSongCacheBoundToSelectedOwner
            << " mask=" << ready.currentSongCacheCompleteLaneMask << " grow=" << ready.currentSongCacheCanGrow
            << " fileBytes=" << progress.canonicalPcmIo.fileBytes << " pulled=" << lane.pulledFrames
            << " appended=" << lane.appendedFrames << " retained=" << lane.retainedSuffixFrames
            << " gaps=" << lane.discontinuityRejects << '\n';
        throw;
    }
    require(runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 0U &&
        runtime.diagnostics().banks[1U].lanes[0U].discontinuityRejects == 0U,
        "an unsealed current-song cache advances without inventing EOS or dropping decoder PCM");
    for (const auto frame : {0U, static_cast<unsigned>(prefixEnd - 256U), static_cast<unsigned>(nextEnd - 256U)}) {
        std::array<float, 512U> actual{};
        const auto expected = tone(256U, frame, 0.025F, 220.0);
        require(runtime.readPcmCacheForAudit(key, 0U, frame, actual.data(), 256U) &&
            std::equal(actual.begin(), actual.end(), expected.begin()),
            "promoted canonical PCM preserves the exact retained prefix and new decoder suffix");
    }
}

void sameSongSeekPreservesOrdinaryOriginalAfterUnsealedHeadReuse() {
    using stemwise::audio::engine::CurrentSongCacheKey;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership owner{1'762U, 5U}, next{owner.selectionToken, 7U};
    constexpr std::uint64_t a = 512U, b = 12'000U, prefixEnd = 16'384U, seekFrame = 6'000U;
    constexpr std::uint64_t ordinaryEnd = seekFrame + 16'384U;
    constexpr std::uint64_t expandedPrefixEnd = prefixEnd + 4'096U;
    SongLaneIngressBank first(65'536U, 480U), second(65'536U, 480U);
    require(first.configureLane(0U, kSampleRate, owner.generation) &&
        second.configureLane(0U, kSampleRate, next.generation), "unsealed head-reuse Originals configured");
    offerLane(first, 0U, owner.generation, 0U, prefixEnd, false);
    FakeOutput output(8'192U, 64U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    const auto cleanup = onScopeExit([&] {
        fault.store(0); releaseBlocked.store(true); stopAndJoin(runtime);
    });
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner, 0U, 0U), NativePlaybackBankIntent{}};
    banks[0U].stemPackageHash = 0U; banks[0U].cacheFrameUpperBound = 60'000U;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner, banks, controls(1U))) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "unsealed head-reuse owner starts normally");
    waitUntil([&] { return output.commitCount() >= 1U &&
        sameOwnership(runtime.controlProgress().acceptedOwnership, owner); },
        "frame-zero Original PCM commits");
    require(output.presentFirstCurrentRecord(), "the initial frame-zero owner receives physical acknowledgement");
    waitUntil([&] { return sameOwnership(runtime.controlProgress().committedOwnership, owner); },
        "the initial frame-zero owner becomes public");
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, false, 0U, 0U) ==
        NativePlaybackPublishStatus::Accepted, "disabled direct loop permits ordinary cache construction");
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation,
        banks[0U].songIdentityHash, banks[0U].originalContentHash, 0U};
    try {
        waitUntil([&] { return sameOwnership(runtime.controlProgress().committedOwnership, owner) &&
            runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner &&
            runtime.pcmCacheRangeReady(key, 1U, a, a + 256U) &&
            runtime.pcmCacheRangeReady(key, 1U, prefixEnd - 256U, prefixEnd);
        }, "real frame-zero builder publishes the unsealed prefix");
    } catch (...) {
        const auto ready = runtime.renderReadinessDiagnostics();
        const auto progress = runtime.diagnostics();
        std::cerr << "Unsealed reuse cache: owner=" << ready.ownership.selectionToken << '/' << ready.ownership.generation
            << " bound=" << ready.currentSongCacheBoundToSelectedOwner << " mask=" << ready.currentSongCacheCompleteLaneMask
            << " grow=" << ready.currentSongCacheCanGrow << " bytes=" << progress.canonicalPcmIo.fileBytes
            << " commits=" << output.commitCount()
            << " headReady=" << runtime.pcmCacheRangeReady(key, 1U, a, a + 256U)
            << " prefixReady=" << runtime.pcmCacheRangeReady(key, 1U, prefixEnd - 256U, prefixEnd)
            << " builderPulled=" << progress.banks[0U].lanes[0U].pulledFrames
            << " builderAppended=" << progress.banks[0U].lanes[0U].appendedFrames << '\n';
        throw;
    }
    require(runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 0U &&
        !runtime.pcmCacheRangeReady(key, 1U, prefixEnd, prefixEnd + 256U),
        "unsealed head-reuse cannot hide the ordinary suffix in a complete cache");
    require(runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation, true, a, b, 0U, true) ==
        NativePlaybackPublishStatus::Accepted, "unsealed prefix prepares the exact A head");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; }, "unsealed A head is callback-ready");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().fixedLoopHeadPrepared; },
        "the exact initial A head is installed before its owner is replaced");
    const auto oldHead = output.committedLoopCutoverHead();

    blocked.store(false); blockTimedOut.store(false); releaseBlocked.store(false); fault.store(7);
    offerLane(first, 0U, owner.generation, prefixEnd, expandedPrefixEnd - prefixEnd, false);
    runtime.notifyIngressAvailable();
    waitUntil([&] { return blocked.load(); }, "the real builder write holds canonical key rebind pending");
    offerLane(second, 0U, next.generation, seekFrame, 16'384U, false);
    const auto commitsBeforeSeek = output.commitCount();
    banks[1U] = assignedBank(1U, next, seekFrame, 0U);
    banks[1U].stemPackageHash = 0U; banks[1U].cacheFrameUpperBound = 60'000U;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::Second, next, banks, controls(2U))) ==
        NativePlaybackPublishStatus::Accepted, "same-token seek retains its real frame-zero prefix producer");
    waitUntil([&] { return output.commitCount() > commitsBeforeSeek &&
        sameOwnership(runtime.controlProgress().acceptedOwnership, next); },
        "unsealed seek commits exact new-owner PCM");
    require(output.presentFirstCurrentRecord(), "unsealed seek receives physical owner acknowledgement");
    waitUntil([&] { return sameOwnership(runtime.controlProgress().committedOwnership, next); },
        "the physically acknowledged sought owner becomes public");
    waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
        return sameOwnership(ready.ownership, next) &&
            ready.lanes[0U].firstTimelineFrame <= prefixEnd && ready.lanes[0U].endTimelineFrameExclusive == ordinaryEnd;
    }, "the sought live pipeline retains its ordinary Original while key rebind is pending");
    require(!blockTimedOut.load(), "the ordinary sought suffix is admitted before the bounded write barrier expires");
    fault.store(0); releaseBlocked.store(true); runtime.notifyIngressAvailable();
    const CurrentSongCacheKey nextKey{next.selectionToken, next.generation,
        banks[1U].songIdentityHash, banks[1U].originalContentHash, 0U};
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner &&
        runtime.pcmCacheRangeReady(nextKey, 1U, expandedPrefixEnd - 256U, expandedPrefixEnd);
    }, "the real completed write rebinds its expanded prefix to the exact sought owner");
    require(!runtime.pcmCacheRangeReady(nextKey, 1U, expandedPrefixEnd, expandedPrefixEnd + 256U),
        "the ordinary sought suffix remains outside the actual expanded canonical prefix");
    waitForRenderProgress(runtime, runtime.diagnostics().renderCycles, "sought audible reserve settles before reuse", 4U);
    const auto rendered = runtime.diagnostics().outputFramesRendered;
    const auto beforeHeadReadiness = runtime.renderReadinessDiagnostics();
    require(runtime.publishDirectLoopPolicy(3U, next.selectionToken, next.generation, true, a, b, 0U, true) ==
        NativePlaybackPublishStatus::Accepted, "unsealed seek reuses its exact existing A head");
    waitUntil([&] { return output.loopCutoverCommitCount() == 2U; }, "unsealed reused head is callback-ready");
    const auto reused = output.committedLoopCutoverHead();
    const auto renderedAfterHead = runtime.diagnostics().outputFramesRendered;
    const bool validHead = reused.token == next.selectionToken && reused.generation == next.generation &&
        reused.sourceStart == static_cast<double>(a) && reused.sourceEnd > reused.sourceStart &&
        reused.sourceEnd <= static_cast<double>(b) && reused.frames != 0U && reused.peak > 0.001F;
    if (!validHead) {
        std::cerr << "Unsealed continuation head: old=" << oldHead.token << '/' << oldHead.generation
            << ' ' << oldHead.sourceStart << ".." << oldHead.sourceEnd << " frames=" << oldHead.frames
            << " new=" << reused.token << '/' << reused.generation << ' ' << reused.sourceStart << ".." << reused.sourceEnd
            << " frames=" << reused.frames << " peak=" << reused.peak
            << " renderedBefore=" << rendered << " renderedAfter=" << runtime.diagnostics().outputFramesRendered << '\n';
    }
    require(validHead, "the new-owner continuation has actual PCM from its exact authored A range");
    require(output.presentCommittedLoopCutover(), "callback adopts the reused partial-cache continuation");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 1U; }, "reused continuation owns producer writes");
    const auto adoptedReadiness = runtime.renderReadinessDiagnostics();

    const auto beforeTail = runtime.diagnostics().banks[1U].lanes[0U];
    require(beforeTail.discontinuityRejects == 0U, "unsealed seek has no prior Original discontinuity");
    offerLane(second, 0U, next.generation, ordinaryEnd, 1'024U, false);
    runtime.notifyIngressAvailable();
    waitUntil([&] { const auto lane = runtime.diagnostics().banks[1U].lanes[0U];
        return lane.discontinuityRejects != 0U || lane.appendedFrames >= beforeTail.appendedFrames + 1'024U;
    }, "adopted head receives the decoder's next contiguous Original quantum");
    const auto afterTail = runtime.diagnostics().banks[1U].lanes[0U];
    const auto faultReadiness = runtime.renderReadinessDiagnostics();
    if (afterTail.discontinuityRejects != 0U || faultReadiness.lanes[0U].ingressFailureGeneration != 0U) {
        std::cerr << "Unsealed adopted tail: oldHead=" << oldHead.sourceStart << ".." << oldHead.sourceEnd
            << " newHead=" << reused.sourceStart << ".." << reused.sourceEnd
            << " rendered=" << rendered << "->" << renderedAfterHead
            << " live=" << beforeHeadReadiness.ownership.generation << ':' << beforeHeadReadiness.lanes[0U].firstTimelineFrame
            << ".." << beforeHeadReadiness.lanes[0U].endTimelineFrameExclusive
            << " adopted=" << adoptedReadiness.ownership.generation << ':' << adoptedReadiness.lanes[0U].firstTimelineFrame
            << ".." << adoptedReadiness.lanes[0U].endTimelineFrameExclusive
            << " fault=" << faultReadiness.ownership.generation << ':' << faultReadiness.lanes[0U].firstTimelineFrame
            << ".." << faultReadiness.lanes[0U].endTimelineFrameExclusive
            << " canonicalEnd=" << expandedPrefixEnd << " gaps=" << afterTail.discontinuityRejects
            << " failureGeneration=" << faultReadiness.lanes[0U].ingressFailureGeneration << '\n';
    }
    require(afterTail.discontinuityRejects == 0U && faultReadiness.lanes[0U].ingressFailureGeneration == 0U,
        "prepared-head reuse must preserve the live ordinary suffix before accepting its next quantum");

    const auto boundary = waitForRecord(output, output.currentGenerationFirstRecord(), [&](const FakeOutputRecord &record) {
        return record.token == next.selectionToken && record.generation == next.generation && record.loopEnabled &&
            record.loopPolicyRevision == reused.loopPolicyRevision && record.sourceEnd == static_cast<double>(b);
    }, "the adopted physical loop producer emits its actual final record before B", 4'000U, &runtime);
    const auto boundaryRecord = output.record(boundary);
    require(output.presentRecord(boundary), "the callback presents the adopted producer through its actual B boundary");
    waitUntil([&] { const auto presented = runtime.diagnostics().presentedPosition;
        return presented.valid() && presented.selectionToken == next.selectionToken && presented.generation == next.generation &&
            presented.loopPolicyRevision == boundaryRecord.loopPolicyRevision &&
            presented.lastTimelineFrame == static_cast<std::int64_t>(boundaryRecord.sourceEnd) - 1;
    }, "the physical cursor acknowledges the exact last real source frame before Clear");
    const double clearCursor = boundaryRecord.sourceEnd;
    require(runtime.publishDirectLoopPolicy(4U, next.selectionToken, next.generation, false, 0U, 0U) ==
        NativePlaybackPublishStatus::Accepted, "Clear requests a real linear continuation beyond cached Original");
    waitUntil([&] { const auto policy = output.lastPublishedLoopPolicy();
        return policy.revision == 4U && !policy.enabled && output.loopCutoverIsCommitted();
    }, "Clear prepares its exact linear producer");
    require(output.committedLoopCutoverHead().sourceStart == clearCursor,
        "Clear starts at the independently presented producer's actual next source cursor");
    require(output.presentCommittedLoopCutover(), "callback adopts the linear Clear producer");
    const auto clearStart = output.currentGenerationFirstRecord();
    std::size_t beyondPrefix = clearStart;
    waitUntil([&] {
        output.consumeAll(); runtime.notifyIngressAvailable();
        for (auto index = clearStart; index < output.recordCount(); ++index) {
            const auto record = output.record(index);
            if (record.generation == next.generation && !record.loopEnabled && record.sourceStart >= expandedPrefixEnd &&
                record.sourceEnd >= expandedPrefixEnd + 1'024U) { beyondPrefix = index; return true; }
        }
        return false;
    }, "Clear plays the retained ordinary suffix beyond every cached frame", 4'000U);
    double cursor = clearCursor;
    for (auto index = clearStart; index <= beyondPrefix; ++index) {
        const auto record = output.record(index);
        require(record.token == next.selectionToken && record.generation == next.generation &&
            !record.loopEnabled && record.sourceStart == cursor && record.sourceEnd > cursor,
            "Clear preserves the exact owner and contiguous source positions");
        if (record.sourceStart >= expandedPrefixEnd) {
            const auto expected = tone(record.frames, static_cast<std::uint64_t>(record.sourceStart), 0.025F, 220.0);
            std::uint64_t hash = 14695981039346656037ULL;
            for (const auto sample : expected) { hash ^= std::bit_cast<std::uint32_t>(sample); hash *= 1099511628211ULL; }
            require(record.originalPcmHash == hash && record.peak > 0.001F,
                "ordinary PCM after Clear matches the independently authored Original samples");
        }
        cursor = record.sourceEnd;
    }
}

void completedOriginalLoopClearReleasesOnlyObsoleteSpareHeadReaders() {
    constexpr SongRenderOwnership owner{2'801U, 71U};
    constexpr std::uint64_t a = 48'000U, b = a + 2'048U;
    constexpr std::uint32_t all = (1U << kLaneCount) - 1U;
    SongLaneIngressBank first(262'144U, 480U), second(262'144U, 480U);
    configureLanes(first, owner.generation);
    for (std::size_t lane = 0U; lane < kLaneCount; ++lane) {
        const auto prefix = lane == 1U ? 7'168U : 0U;
        offerLane(first, lane, owner.generation, a + prefix, 208'896U - prefix, false);
    }
    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    output.setLoopCutoverRequiredFrames(b - a);
    NativePlaybackRuntime runtime(runtimeConfiguration(262'144U, 256U), first, second, output.port());
    const auto cleanup = onScopeExit([&] { stopAndJoin(runtime); });
    const std::array<NativePlaybackBankIntent, 2U> banks{assignedBank(1U, owner, a), NativePlaybackBankIntent{}};
    std::uint64_t revision = 1U;
    const auto publish = [&](MixerSourceMode source, bool playing) {
        ++revision;
        require(runtime.publishCommand(selectedCommand(revision, NativePlaybackBank::First,
            owner, banks, controls(revision, 1.0F, 0.0F, source), playing)) == NativePlaybackPublishStatus::Accepted,
            "retired head source command admitted");
        waitUntil([&] { return runtime.diagnostics().endOrdering.commandConsumed.commandRevision == revision; },
            "retired head source command consumed");
    };
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner, banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted && runtime.start(), "retired head Original starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "retired head Original commits");
    require(output.presentFirstCurrentRecord(), "retired head Original physically acknowledged");
    auto head = managedLoopIntent(1U, owner, a, 1U);
    prepareFullManagedLoopHead(runtime, head, 100U, 1U, owner);
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation,
        true, a, b, 0U, true) == NativePlaybackPublishStatus::Accepted, "retired head Original short loop");
    for (unsigned cycle = 0U; cycle < 3U; ++cycle) {
        waitUntil([&] {
            output.consumeAll(); runtime.notifyIngressAvailable();
            return output.loopCutoverIsCommitted() && runtime.renderReadinessDiagnostics().renderStartTimelineFrame >= b;
        }, "active and prepared producers complete their exact full A-B traversals");
        require(output.committedLoopCutoverHead().sourceStart == static_cast<double>(a) &&
            output.loopCutoverFramesAtCommit() == b - a, "the prepared role contains a whole physical cycle");
        require(output.presentCommittedLoopCutover(), "callback adopts the complete-cycle role");
        waitUntil([&] { return output.loopCutoverWriteReleaseCount() >= cycle + 1U; },
            "complete-cycle physical producer retirement acknowledged");
    }
    publish(MixerSourceMode::Original, false);
    waitUntil([&] { return !output.playing(); }, "the physical Original output acknowledges Pause");
    require(runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation,
        false, 0U, 0U, 0U, false) == NativePlaybackPublishStatus::Accepted, "clear the authored physical loop");
    publish(MixerSourceMode::Stems, false);
    // Stems cannot cover the old range yet. A newly committed Original head must remain usable
    // while the obsolete spare releases the other slot for the independently decoded Stems head.
    head = managedLoopIntent(2U, owner, a, 1U);
    prepareFullManagedLoopHead(runtime, head, 200U, 1U, owner);
    head = managedLoopIntent(3U, owner, a, all);
    auto status = runtime.beginLoopHeadPreparation(head.cacheKey, head.requiredLaneMask, head.boundaryMode, owner);
    const auto restorePlayAdmission = onScopeExit([&] { output.rejectPlay.store(false, std::memory_order_release); });
    const auto attemptsBeforeResume = output.playAttempts.load(std::memory_order_acquire);
    output.rejectPlay.store(true, std::memory_order_release);
    publish(MixerSourceMode::Stems, true);
    waitUntil([&] { return output.playAttempts.load(std::memory_order_acquire) > attemptsBeforeResume; },
        "the pending Play request reaches the output while its start is temporarily rejected");
    waitUntil([&] {
        if (status != LoopHeadPreparationBeginStatus::ReadersActive)
            return status == LoopHeadPreparationBeginStatus::Accepted;
        runtime.notifyIngressAvailable();
        static_cast<void>(runtime.renderReadinessDiagnostics());
        status = runtime.beginLoopHeadPreparation(head.cacheKey, head.requiredLaneMask, head.boundaryMode, owner);
        return status == LoopHeadPreparationBeginStatus::Accepted;
    }, "Clear with pending Stems must retire obsolete spare readers so the next head can begin");
    require(sameOwnership(runtime.diagnostics().committedOwnership, owner),
        "head admission preserves the actual current owner before physical Play acknowledgement");
    require(!output.playing(), "accepted head preparation cannot claim a temporarily rejected physical Play");
    output.rejectPlay.store(false, std::memory_order_release);
    waitUntil([&] {
        const auto applied = runtime.diagnostics().endOrdering.outputPlayingApplied;
        return output.playing() && applied.commandRevision == revision && applied.outputPlaying &&
            sameOwnership(applied.ownership, owner);
    }, "the current owner resumes only after its actual physical Play acknowledgement");
    const auto retainedPhysical = output.acceptedLoopPolicy();
    require(retainedPhysical.enabled && retainedPhysical.aTimelineFrame == a &&
        retainedPhysical.bTimelineFrameExclusive == b, "missing Stems cannot retire the active physical Original loop");
    const auto original = output.record(output.currentGenerationFirstRecord());
    const auto expected = tone(original.frames, static_cast<std::uint64_t>(original.sourceStart), 0.03F, 211.0);
    std::uint64_t hash = 14'695'981'039'346'656'037ULL;
    for (const auto sample : expected) { hash ^= std::bit_cast<std::uint32_t>(sample); hash *= 1'099'511'628'211ULL; }
    require(original.token == owner.selectionToken && original.generation == owner.generation &&
        original.sourceMode == NativeAudioSourceMode::Original && original.originalPcmHash == hash &&
        original.peak > 0.001F && output.queued() > 0U,
        "the retained physical Original contains independently authored nonzero PCM");
    require(runtime.abortLoopHeadPreparation(head.cacheKey.loopRevision) ==
        stemwise::audio::engine::LoopHeadPreparationAbortStatus::Accepted,
        "an interrupted replacement head can be abandoned independently");
    require(sameOwnership(runtime.diagnostics().committedOwnership, owner) && output.playing() &&
        output.acceptedLoopPolicy().physicalRevision() == retainedPhysical.physicalRevision(),
        "failed replacement preserves the exact live Original role");
    head = managedLoopIntent(4U, owner, a, all);
    prepareFullManagedLoopHead(runtime, head, 400U, kLaneCount, owner);
    waitUntil([&] {
        output.consumeAll(); runtime.notifyIngressAvailable();
        return output.loopCutoverIsCommitted() && !output.acceptedLoopPolicy().enabled &&
            output.committedLoopCutoverHead().sourceMode == NativeAudioSourceMode::Stems;
    }, "the independently decoded Stems head completes the exact linear Clear replacement");
    require(output.presentCommittedLoopCutover(), "callback adopts the prepared linear Stems role");
    require(output.record(output.currentGenerationFirstRecord()).sourceStart == static_cast<double>(b),
        "linear Stems begins at the existing physical B without retiming");
}

enum class LoopClearOrder {
    ClearOnly,
    ClearPauseSourceResume,
    PauseClearSourceResume,
    PauseSourceClearResume,
    SourcePauseClearResume,
};

const char *loopClearOrderName(LoopClearOrder order) noexcept {
    switch (order) {
        case LoopClearOrder::ClearOnly: return "clear-only";
        case LoopClearOrder::ClearPauseSourceResume: return "clear-pause-source-resume";
        case LoopClearOrder::PauseClearSourceResume: return "pause-clear-source-resume";
        case LoopClearOrder::PauseSourceClearResume: return "pause-source-clear-resume";
        case LoopClearOrder::SourcePauseClearResume: return "source-pause-clear-resume";
    }
    return "invalid";
}

void directLoopPhysicalCutoverUsesDistinctBoundaryAndContinuation(
    LoopClearOrder clearOrder = LoopClearOrder::ClearOnly,
    bool pressureOnClear = false,
    bool callbackClaimOnExpansion = false,
    bool preparedSuccessor = false,
    bool captureBeforeExpansionAdoption = false,
    bool holdClearUntilResume = false,
    bool deferActiveReadOnClear = false) {
    const bool pauseAndSwitchOnClear = clearOrder != LoopClearOrder::ClearOnly;
    constexpr SongRenderOwnership owner{1'751U, 2U};
    constexpr SongRenderOwnership successor{1'752U, 3U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    constexpr std::uint64_t a = 128U;
    constexpr std::uint64_t b = 1'536U;
    constexpr std::uint64_t expandedA = 256U;
    constexpr std::uint64_t expandedB = 2'048U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);
    if (preparedSuccessor) {
        configureLanes(second, successor.generation);
        offerAllLanes(second, successor.generation, 16'384U);
    }

    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    output.setLoopCutoverRequiredFrames(1'536U);
    auto physicalConfiguration = runtimeConfiguration(32'768U, 256U);
    if (callbackClaimOnExpansion) physicalConfiguration.idleSleepMicros = 250'000U;
    if (pressureOnClear) physicalConfiguration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(
        physicalConfiguration,
        first,
        second,
        output.port());
    const auto releaseReadHook = onScopeExit([&] {
        if (deferActiveReadOnClear) {
            stemwise::audio::engine::cache_test::deferReadCopy.store(nullptr);
            stopAndJoin(runtime);
        }
    });
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), preparedSuccessor ? assignedBank(1U, successor) : NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "physical-cycle owner selected");
    if (pauseAndSwitchOnClear) {
        require(runtime.publishCommand(selectedCommand(
            2U, NativePlaybackBank::First, owner, banks,
            controls(2U, 1.0F, 0.0F, MixerSourceMode::Stems))) ==
                NativePlaybackPublishStatus::Accepted, "clear-combo starts in Stems");
    }
    require(runtime.start(), "physical-cycle runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "physical-cycle owner committed");
    require(output.presentFirstCurrentRecord(),
        "physical-cycle owner publishes callback cursor");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "physical-cycle cache ready", 8'000U);

    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        a,
        b) == NativePlaybackPublishStatus::Accepted,
        "physical-cycle loop accepted");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "single exact A role committed");
    const NativeAudioOutputLoopPolicy installed = output.lastPublishedLoopPolicy();
    require(installed.revision == 1U && installed.enabled &&
            installed.physicalReady && installed.hasPhysicalCutover() &&
            installed.aTimelineFrame == a &&
            installed.bTimelineFrameExclusive == b &&
            installed.cutoverTimelineFrameExclusive == b &&
            installed.continuationTimelineFrame == a &&
            installed.cutoverSourceRevision == 0U &&
            installed.physicalRevision() != 0U &&
            output.loopCutoverBeginCount() == 1U,
        "one physical role binds linear B to an internally looping A producer");
    const FakeOutputRecord installedHead = output.committedLoopCutoverHead();
    require(installedHead.loopEnabled &&
            installedHead.loopPolicyRevision == installed.physicalRevision() &&
            installedHead.loopRevision == installed.physicalRevision() &&
            static_cast<std::uint64_t>(std::floor(installedHead.sourceStart)) == a,
        "installed role begins at exact A with one physical identity");

    require(output.presentCommittedLoopCutover(),
        "callback installs the internally looping producer");
    waitUntil([&] {
        return runtime.diagnostics().presentedPosition.loopPolicyRevision ==
            installed.physicalRevision();
    }, "worker observes the installed physical role");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 1U; },
        "installed role releases the old linear producer once");

    const std::size_t recordsBeforeActiveLoopRefill = output.recordCount();
    output.consumeAll();
    const std::size_t oldBoundary = waitForRecord(
        output,
        recordsBeforeActiveLoopRefill,
        [physicalRevision = installed.physicalRevision(), b](const FakeOutputRecord &record) {
            return record.loopEnabled &&
                record.loopPolicyRevision == physicalRevision &&
                record.loopRevision == physicalRevision &&
                static_cast<std::uint64_t>(std::floor(record.sourceEnd)) == b;
        },
        "installed producer emits exact old B");
    const std::size_t wrappedHead = waitForRecord(
        output,
        oldBoundary + 1U,
        [physicalRevision = installed.physicalRevision(), a](const FakeOutputRecord &record) {
            return record.loopEnabled &&
                record.loopPolicyRevision == physicalRevision &&
                record.loopRevision == physicalRevision &&
                static_cast<std::uint64_t>(std::floor(record.sourceStart)) == a;
        },
        "installed producer wraps internally from B back to A");
    require(output.record(wrappedHead).loopCycle ==
            output.record(oldBoundary).loopCycle + 1U,
        "a natural direct-loop wrap advances its physical cycle tag");
    require(output.presentRecord(oldBoundary),
        "callback presents the stable physical role through B");
    require(output.presentRecord(wrappedHead),
        "callback presents the same physical role again at A");
    waitUntil([&] {
        const NativeAudioPresentedPosition position = runtime.diagnostics().presentedPosition;
        return position.loopPolicyRevision == installed.physicalRevision() &&
            position.lastTimelineFrame >= static_cast<std::int64_t>(a);
    }, "callback reports the stable physical identity after A-B-A output");
    try {
        waitUntil([&] { return output.loopCutoverCommitCount() == 2U; },
            "unchanged logical loop primes one dormant continuation");
    } catch (...) {
        const auto diagnostic = runtime.diagnostics();
        const auto position = diagnostic.presentedPosition;
        const auto policy = output.lastPublishedLoopPolicy();
        std::cerr << "dormant-spare order=" << static_cast<int>(clearOrder)
                  << " pressure=" << pressureOnClear << " begins=" << output.loopCutoverBeginCount()
                  << " commits=" << output.loopCutoverCommitCount() << " releases=" << output.loopCutoverWriteReleaseCount()
                  << " open=" << output.loopCutoverIsOpen() << " committed=" << output.loopCutoverIsCommitted()
                  << " logical=" << policy.revision << " physical=" << policy.physicalRevision()
                  << " source=" << policy.cutoverSourceRevision << " frame=" << position.lastTimelineFrame
                  << " emitted=" << position.loopPolicyRevision
                  << " staged=" << output.loopCutoverStagedFrames()
                  << " reads=" << diagnostic.canonicalPcmIo.readOperations
                  << " cycles=" << diagnostic.renderCycles << " idle=" << diagnostic.idleSleeps
                  << " need=" << diagnostic.outputNeedDataEvents
                  << " invalid=" << diagnostic.outputInvalidRenderResults
                  << " accepted=" << diagnostic.outputFramesAccepted
                  << " queued=" << output.queued() << " target=" << diagnostic.targetQueuedFrames << '\n';
        throw;
    }
    const NativeAudioOutputLoopPolicy installedSpare = output.lastPublishedLoopPolicy();
    require(installedSpare.nextRevisionOnly &&
            installedSpare.cutoverSourceRevision == installed.physicalRevision() &&
            installedSpare.physicalRevision() > installed.physicalRevision(),
        "dormant continuation belongs only to the installed physical role");
    const std::uint64_t cutoversBeforeExpansion = output.loopCutoverCommitCount();
    require(output.loopCutoverBeginCount() == cutoversBeforeExpansion &&
            output.loopCutoverWriteReleaseCount() == 1U &&
            !output.loopCutoverIsOpen() && output.loopCutoverIsCommitted(),
        "unchanged logical revision owns one installed role and one dormant spare");

    const std::size_t recordsBeforeBlockedReplacement = output.recordCount();
    if (callbackClaimOnExpansion) output.setLoopCutoverCancellationInFlight(true);
    output.setLoopCutoverEnqueueBlocked(true);
    require(runtime.publishDirectLoopPolicy(
        2U,
        owner.selectionToken,
        owner.generation,
        true,
        expandedA,
        expandedB) == NativePlaybackPublishStatus::Accepted,
        "expanded endpoint tuple accepted");
    if (callbackClaimOnExpansion) {
        waitUntil([&] { return output.loopCancellationWasObserved(); },
            "new endpoint encounters a callback-owned continuation");
        const auto idleBeforeRelease = runtime.diagnostics().idleSleeps;
        waitUntil([&] { return runtime.diagnostics().idleSleeps > idleBeforeRelease; },
            "worker waits for the callback to release its continuation");
        output.setLoopCutoverCancellationInFlight(false);
        // Real callbacks release this claim without notifying the render worker. A long idle
        // budget makes a missed acknowledgement poll deterministic without a fragile 1ms test.
        waitUntil([&] { return output.loopCutoverBeginCount() > cutoversBeforeExpansion; },
            "released callback claim must not wait for the ordinary idle timer", 75U);
    }

    waitUntil([&] {
        return output.loopCutoverBeginCount() == cutoversBeforeExpansion + 1U &&
            output.loopCutoverEnqueueEntered();
    }, "expanded endpoint replacement is blocked before commit");
    output.consumeAll();
    const std::size_t preservedBoundary = waitForRecord(
        output,
        recordsBeforeBlockedReplacement,
        [physicalRevision = installed.physicalRevision(), b](const FakeOutputRecord &record) {
            return record.loopEnabled &&
                record.loopPolicyRevision == physicalRevision &&
                record.loopRevision == physicalRevision &&
                static_cast<std::uint64_t>(std::floor(record.sourceEnd)) == b;
        },
        "installed producer keeps emitting old B while replacement is blocked");
    static_cast<void>(waitForRecord(
        output,
        preservedBoundary + 1U,
        [physicalRevision = installed.physicalRevision(), a](const FakeOutputRecord &record) {
            return record.loopEnabled &&
                record.loopPolicyRevision == physicalRevision &&
                record.loopRevision == physicalRevision &&
                static_cast<std::uint64_t>(std::floor(record.sourceStart)) == a;
        },
        "installed producer keeps wrapping to old A while replacement is blocked"));
    require(output.playing(),
        "blocked replacement never stops the installed loop transport");
    output.setLoopCutoverEnqueueBlocked(false);
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy policy = output.lastPublishedLoopPolicy();
        return policy.revision == 2U && policy.physicalReady &&
            output.loopCutoverCommitCount() == cutoversBeforeExpansion + 1U;
    }, "expanded endpoint tuple commits one new role");
    const NativeAudioOutputLoopPolicy expanded = output.lastPublishedLoopPolicy();
    require(expanded.aTimelineFrame == expandedA &&
            expanded.bTimelineFrameExclusive == expandedB,
        "B expansion retains exact new A-B");
    require(expanded.cutoverTimelineFrameExclusive == b,
        "B expansion cuts over at old B");
    require(expanded.continuationTimelineFrame == b,
        "B expansion continues linearly from exact old B");
    require(expanded.cutoverSourceRevision == installed.physicalRevision(),
        "B expansion targets the installed physical source revision");
    require(expanded.physicalRevision() > installed.physicalRevision(),
        "B expansion receives a fresh physical identity");
    const std::uint64_t replacementBeginCount = output.loopCutoverBeginCount();
    require(replacementBeginCount >= cutoversBeforeExpansion + 1U &&
            replacementBeginCount <= cutoversBeforeExpansion + 2U,
        "blocked B expansion has at most one retired preparation retry");
    require(output.loopCutoverIsCommitted(),
        "B expansion leaves its replacement callback-claimable");
    const FakeOutputRecord expandedHead = output.committedLoopCutoverHead();
    require(expandedHead.loopEnabled &&
            expandedHead.loopPolicyRevision == expanded.physicalRevision() &&
            expandedHead.loopRevision == expanded.physicalRevision() &&
            static_cast<std::uint64_t>(std::floor(expandedHead.sourceStart)) == b,
        "expanded role preserves the audible cursor while installing the new range");
    if (captureBeforeExpansionAdoption) {
        output.setLoopCutoverCancellationInFlight(true);
        output.captureObservationAvailable.store(false, std::memory_order_release);
        require(runtime.publishDirectLoopPolicy(3U, owner.selectionToken, owner.generation,
            true, expandedA, 1'024U, 0U, true, true, 71U, 1'024U, 1'600) ==
                NativePlaybackPublishStatus::Accepted &&
            runtime.publishDirectLoopPolicy(4U, owner.selectionToken, owner.generation,
                true, expandedA, expandedB, 0U, true, false, 71U) == NativePlaybackPublishStatus::Accepted,
            "accepted inward capture survives the newer ahead B while the expansion body is committed");
        waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
            "unavailable capture state is retried without cancelling committed PCM", 4U);
        require(output.loopCutoverIsCommitted() &&
            output.lastPublishedLoopPolicy().physicalRevision() == expanded.physicalRevision() &&
            output.committedLoopCutoverHead().sourceStart == static_cast<double>(b),
            "missing capture observation preserves the exact committed expansion body");
        output.captureObservationAvailable.store(true, std::memory_order_release);
        runtime.notifyIngressAvailable();
        waitUntil([&] { return output.loopCancellationWasObserved(); },
            "the owed capture cancels an incompatible committed old-B continuation");
        require(output.playing() && output.loopCutoverIsCommitted(),
            "capture replacement waits behind an existing claim while retaining current playback");
        output.setLoopCutoverCancellationInFlight(false);
        waitUntil([&] {
            const auto prepared = output.lastPublishedLoopPolicy();
            return output.loopCutoverIsCommitted() && prepared.revision == 4U &&
                prepared.physicalRevision() > expanded.physicalRevision() &&
                prepared.continuationTimelineFrame == expandedA;
        }, "retained capture prepares actual A despite the latest B being an expansion");
        const auto captureHead = output.committedLoopCutoverHead();
        require(captureHead.generation == owner.generation && captureHead.token == owner.selectionToken &&
            captureHead.sourceStart == static_cast<double>(expandedA),
            "the replacement's actual PCM begins at the exact captured A owner");
        const auto latestDemand = output.lastPublishedLoopDemand();
        const auto pending = output.port().pendingLoopCapture(output.port().handle, latestDemand);
        require(pending.available && pending.revision == 3U,
            "preparation cannot settle an accepted capture before its actual A cutover");
        require(output.presentCommittedLoopCutover(), "the callback actually adopts the prepared capture A");
        waitUntil([&] {
            return runtime.diagnostics().presentedPosition.loopPolicyRevision == captureHead.loopPolicyRevision;
        }, "worker adopts only the actual captured A physical role");
        const auto settled = output.port().pendingLoopCapture(output.port().handle, latestDemand);
        require(settled.available && settled.revision == 0U,
            "only actual A adoption settles the retained capture");
        require(runtime.publishDirectLoopPolicy(5U, owner.selectionToken, owner.generation,
            true, expandedA, 2'560U, 0U, true, false, 71U) == NativePlaybackPublishStatus::Accepted,
            "ordinary expansion resumes after the exact capture acknowledgement");
        waitUntil([&] {
            const auto next = output.lastPublishedLoopPolicy();
            return next.revision == 5U && next.bTimelineFrameExclusive == 2'560U;
        }, "settled capture does not pin later ordinary expansion to its old A replacement");
        stopAndJoin(runtime);
        return;
    }
    require(output.presentCommittedLoopCutover(),
        "callback installs the expanded internally looping producer");
    waitUntil([&] {
        return runtime.diagnostics().presentedPosition.loopPolicyRevision ==
            expanded.physicalRevision();
    }, "worker observes the expanded physical role");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy spare = output.lastPublishedLoopPolicy();
        return output.loopCutoverCommitCount() == cutoversBeforeExpansion + 2U &&
            spare.nextRevisionOnly &&
            spare.cutoverSourceRevision == expanded.physicalRevision();
    }, "expanded logical loop primes one dormant continuation");
    require(output.loopCutoverBeginCount() == replacementBeginCount + 1U &&
            output.loopCutoverWriteReleaseCount() == 2U &&
            !output.loopCutoverIsOpen() && output.loopCutoverIsCommitted(),
        "changed tuple installs once and primes only one dormant continuation");
    const std::uint64_t beginsBeforeDisable = output.loopCutoverBeginCount();
    const std::uint64_t commitsBeforeDisable = output.loopCutoverCommitCount();

    const std::size_t recordsBeforeDisable = output.recordCount();
    if (pressureOnClear) output.setCallbackPressureReserve(1'792U);
    if (holdClearUntilResume) output.setLoopCutoverEnqueueBlocked(true);
    std::uint64_t commandRevision = 2U;
    bool playing = true;
    auto currentControls = pauseAndSwitchOnClear
        ? controls(2U, 1.0F, 0.0F, MixerSourceMode::Stems) : controls(1U);
    const auto publishControls = [&] {
        auto command = selectedCommand(
            ++commandRevision, NativePlaybackBank::First, owner, banks, currentControls, playing);
        if (preparedSuccessor && playing) command.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
            true, false, 81U, owner, NativePlaybackBank::Second, successor};
        require(runtime.publishCommand(command) ==
                NativePlaybackPublishStatus::Accepted, "clear-combo independent command accepted");
    };
    if (preparedSuccessor) {
        publishControls();
        waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
            "prepared Next intent is observed while the physical loop owns output", 4U);
        require(output.drainBeginCount() == 0U,
            "prepared Next cannot acquire the physical loop's spare output role");
    }
    const auto clear = [&] {
        require(runtime.publishDirectLoopPolicy(
            3U, owner.selectionToken, owner.generation, false, 0U, 0U) ==
                NativePlaybackPublishStatus::Accepted, "physical-cycle loop disable accepted");
        if (holdClearUntilResume && playing) {
            waitUntil([&] { return output.loopCutoverEnqueueEntered(); },
                "AE-06 Clear reaches continuation acceptance before the competing command");
        }
    };
    const auto pause = [&] {
        playing = false;
        publishControls();
        waitUntil([&] { return !output.playing(); }, "clear-combo output paused");
    };
    const auto selectOriginal = [&] {
        currentControls = controls(3U);
        publishControls();
        if (holdClearUntilResume) {
            waitUntil([&] { return runtime.renderReadinessDiagnostics().controlRevision == 3U; },
                "AE-06 Original control is applied while Clear acceptance is held");
        }
    };
    switch (clearOrder) {
        case LoopClearOrder::ClearOnly: clear(); break;
        case LoopClearOrder::ClearPauseSourceResume: clear(); pause(); selectOriginal(); break;
        case LoopClearOrder::PauseClearSourceResume: pause(); clear(); selectOriginal(); break;
        case LoopClearOrder::PauseSourceClearResume: pause(); selectOriginal(); clear(); break;
        case LoopClearOrder::SourcePauseClearResume: selectOriginal(); pause(); clear(); break;
    }
    if (pauseAndSwitchOnClear) {
        playing = true;
        publishControls();
    }
    if (holdClearUntilResume) {
        waitUntil([&] { return output.playing(); }, "AE-06 resume is applied before Clear can accept PCM");
        if (deferActiveReadOnClear) {
            static std::atomic<unsigned> deferredReads{};
            deferredReads.store(0U);
            stemwise::audio::engine::cache_test::deferReadCopy.store(+[](
                const stemwise::audio::engine::CurrentSongPcmReadConsumer *, std::size_t,
                std::uint64_t firstFrame, std::size_t, std::size_t) noexcept {
                if (firstFrame >= expandedB) return false;
                deferredReads.fetch_add(1U);
                return true;
            });
            waitUntil([&] { return deferredReads.load() != 0U; },
                "AE-06 active cache copy is contended while linear Clear PCM is ready");
        }
        output.setLoopCutoverEnqueueBlocked(false);
    }
    NativeAudioOutputLoopPolicy disabled{};
    FakeOutputRecord disabledHead{};
    try { waitUntil([&] {
        const NativeAudioOutputLoopPolicy physical = output.acceptedLoopPolicy();
        const auto head = output.committedLoopCutoverHead();
        if (physical.revision != 3U || physical.enabled || !physical.physicalReady ||
            physical.cutoverTimelineFrameExclusive != expandedB ||
            physical.continuationTimelineFrame != expandedB ||
            physical.cutoverSourceRevision != expanded.physicalRevision() ||
            !output.observeCommittedLoopCutover(physical.physicalRevision()) ||
            head.loopPolicyRevision != physical.physicalRevision() ||
            (pauseAndSwitchOnClear
                ? output.loopCutoverCommitCount() < commitsBeforeDisable + 1U
                : output.loopCutoverCommitCount() != commitsBeforeDisable + 1U)) return false;
        // A pause/source update can retire an earlier clear commit while its replacement opens.
        if (pauseAndSwitchOnClear && (!output.playing() ||
                head.controlRevision != currentControls.revision ||
                head.sourceMode != NativeAudioSourceMode::Original)) return false;
        disabled = physical;
        disabledHead = head;
        return true;
    }, "disable prepares one linear replacement without mutating the audible loop"); }
    catch (const std::exception &) {
        const auto physical = output.acceptedLoopPolicy();
        const auto head = output.committedLoopCutoverHead();
        const auto runtimeState = runtime.diagnostics();
        const auto readiness = runtime.renderReadinessDiagnostics();
        throw std::runtime_error(std::string("AE-06 Clear preparation order=") + loopClearOrderName(clearOrder) +
            " owner=" + std::to_string(runtimeState.requestedOwnership.selectionToken) +
            "/" + std::to_string(runtimeState.requestedOwnership.generation) +
            " command=" + std::to_string(runtimeState.endOrdering.commandConsumed.commandRevision) +
            " demand=" + std::to_string(readiness.directAtomicPolicyPublishedRevision) +
            "/" + std::to_string(readiness.directAtomicPolicyAdoptedRevision) +
            "/" + std::to_string(readiness.directAtomicPolicyObservedRevision) +
            " revision=" + std::to_string(physical.revision) +
            " enabled=" + std::to_string(physical.enabled) + " begins=" + std::to_string(output.loopCutoverBeginCount()) +
            " commits=" + std::to_string(output.loopCutoverCommitCount()) + " drain=" + std::to_string(output.drainBeginCount()) +
            " physical=" + std::to_string(physical.physicalRevision()) +
            " sourcePhysical=" + std::to_string(physical.cutoverSourceRevision) +
            " staged=" + std::to_string(output.loopCutoverStagedFrames()) +
            " commitAttempts=" + std::to_string(output.loopCutoverCommitAttemptCount()) +
            " open=" + std::to_string(output.loopCutoverIsOpen()) +
            " committed=" + std::to_string(output.loopCutoverIsCommitted()) +
            " releases=" + std::to_string(output.loopCutoverWriteReleaseCount()) +
            " headLoop=" + std::to_string(head.loopEnabled) +
            " headPhysical=" + std::to_string(head.loopPolicyRevision) +
            " headStart=" + std::to_string(head.sourceStart) +
            " headEnd=" + std::to_string(head.sourceEnd) +
            " headControl=" + std::to_string(head.controlRevision) + " expectedControl=" + std::to_string(currentControls.revision) +
            " actualPlaying=" + std::to_string(output.playing()) +
            " pauseScenario=" + std::to_string(pauseAndSwitchOnClear) +
            " queued=" + std::to_string(output.queued()) +
            " records=" + std::to_string(output.recordCount()) +
            " renderCycles=" + std::to_string(runtimeState.renderCycles));
    }
    if (deferActiveReadOnClear)
        stemwise::audio::engine::cache_test::deferReadCopy.store(nullptr);
    if (pressureOnClear) {
        require(output.recordCount() > recordsBeforeDisable &&
                output.queued() < runtime.diagnostics().targetQueuedFrames,
            "clear-combo progresses despite active reserve staying below target");
        output.setCallbackPressureReserve(0U);
    }
    require(!disabledHead.loopEnabled &&
            disabledHead.loopPolicyRevision == disabled.physicalRevision() &&
            static_cast<std::uint64_t>(std::floor(disabledHead.sourceStart)) == expandedB,
        "disable replacement is exact linear PCM at the installed B boundary");
    require(output.presentCommittedLoopCutover(),
        "callback atomically installs the linear disable replacement");
    waitUntil([&] {
        return runtime.diagnostics().presentedPosition.loopPolicyRevision ==
            disabled.physicalRevision();
    }, "worker observes the physical disable replacement");
    if (preparedSuccessor) {
        waitUntil([&] { return output.drainBeginCount() == 1U; },
            "physical Clear acknowledgement releases the spare role to prepared Next");
        for (std::size_t callback = 0U; callback < 2'000U; ++callback) {
            static_cast<void>(output.consumeCallback(256U));
            if (sameOwnership(runtime.diagnostics().committedOwnership, successor)) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        require(sameOwnership(runtime.diagnostics().committedOwnership, successor),
            "Clear exits the loop and subsequently presents the prepared Next owner");
        stopAndJoin(runtime);
        return;
    }
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 3U; },
        "worker adopts the disabled producer before its next active refill");
    const auto recordsAfterDisableAdoption = output.recordCount();
    output.consumeAll();
    const std::size_t linearRefill = waitForRecord(
        output,
        recordsAfterDisableAdoption,
        [physicalRevision = disabled.physicalRevision()](const FakeOutputRecord &record) {
            return record.loopPolicyRevision == physicalRevision && !record.loopEnabled && record.loopRevision == 0U;
        },
        "disabled internal loop refills linearly with its accepted physical identity");
    require(output.presentRecord(linearRefill),
        "callback presents linear PCM after loop disable");
    waitUntil([&] {
        return runtime.diagnostics().presentedPosition.loopPolicyRevision == disabled.physicalRevision();
    }, "disable preserves the accepted linear physical identity in presented PCM");
    require((pauseAndSwitchOnClear || output.loopCutoverBeginCount() == beginsBeforeDisable + 1U) &&
            (pauseAndSwitchOnClear || output.loopCutoverCommitCount() == commitsBeforeDisable + 1U) &&
            output.loopCutoverWriteReleaseCount() == 3U &&
            output.playing(),
        "disable performs one atomic cutover and leaves linear playback active");
    stopAndJoin(runtime);
}

void directLoopClearCommandOrdersProgressUnderOutputPressure(bool holdClearUntilResume = false,
    bool deferActiveReadOnClear = false) {
    for (const auto order : {LoopClearOrder::ClearPauseSourceResume, LoopClearOrder::ClearOnly,
             LoopClearOrder::PauseClearSourceResume, LoopClearOrder::PauseSourceClearResume,
             LoopClearOrder::SourcePauseClearResume}) {
        try {
            directLoopPhysicalCutoverUsesDistinctBoundaryAndContinuation(
                order, true, false, false, false, holdClearUntilResume, deferActiveReadOnClear);
        } catch (const std::exception &error) {
            throw std::runtime_error(std::string("AE-06 order=") + loopClearOrderName(order) +
                " heldClear=" + std::to_string(holdClearUntilResume) + ": " + error.what());
        }
    }
}

void nearQueuedDirectLoopBoundaryDoesNotWaitAnotherTraversal() {
    constexpr SongRenderOwnership owner{1'753U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(4'096U, 256);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntimeConfiguration configuration =
        runtimeConfiguration(32'768U, 256U);
    configuration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "near-boundary owner selected");
    require(runtime.start(), "near-boundary runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "near-boundary owner committed");
    require(output.presentFirstCurrentRecord(),
        "near-boundary owner publishes callback cursor");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "near-boundary current-song cache ready", 8'000U);
    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        128U,
        4'800U) == NativePlaybackPublishStatus::Accepted,
        "near-boundary outgoing loop accepted");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "near-boundary outgoing A role committed");
    const std::uint64_t outgoingPhysicalRevision =
        output.lastPublishedLoopPolicy().physicalRevision();
    require(outgoingPhysicalRevision != 0U,
        "near-boundary outgoing role has a physical identity");
    waitUntil([&] { return output.queued() >= 2'048U; },
        "near-boundary active reserve queued");

    const std::size_t nearBoundaryRecord = waitForRecord(
        output,
        0U,
        [](const FakeOutputRecord &record) {
            return record.sourceStart < 1'280.0 && record.sourceEnd >= 1'280.0;
        },
        "near-boundary callback record");
    require(output.presentRecord(nearBoundaryRecord),
        "near-boundary callback approaches B");
    const NativeAudioLoopBoundaryCoverage coverage = output.loopBoundaryCoverageForTest(
        owner.generation,
        owner.selectionToken,
        1'536U,
        std::numeric_limits<std::uint64_t>::max());
    require(coverage.boundaryQueued && coverage.unreadPrefixFrames < 1'024U,
        "near-boundary B is queued inside the former four-burst rejection window");

    require(runtime.publishDirectLoopPolicy(
        2U,
        owner.selectionToken,
        owner.generation,
        true,
        128U,
        1'536U) == NativePlaybackPublishStatus::Accepted,
        "near-boundary contraction accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy physical = output.lastPublishedLoopPolicy();
        return physical.revision == 2U &&
            physical.bTimelineFrameExclusive == 1'536U;
    }, "near-boundary contraction replaces prepared PCM that crossed the new B");
    const NativeAudioOutputLoopPolicy policy = output.lastPublishedLoopPolicy();
    require(policy.revision == 2U && policy.enabled && policy.hasPhysicalCutover() &&
            policy.cutoverTimelineFrameExclusive == 1'536U &&
            policy.continuationTimelineFrame == 128U &&
            policy.cutoverSourceRevision == 0U &&
            policy.physicalRevision() > outgoingPhysicalRevision &&
            output.loopCutoverBeginCount() == 2U &&
            output.loopCutoverCommitCount() == 2U,
        "near-boundary contraction commits exact B without reusing stale prepared PCM");
    stopAndJoin(runtime);
}

void directLoopStageNeverPreemptsAudibleOutputRefill(std::uint32_t burst = 256U) {
    constexpr SongRenderOwnership owner{1'751U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(8'192U, burst);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntimeConfiguration configuration =
        runtimeConfiguration(32'768U, 256U);
    configuration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "output-priority owner selected");
    require(runtime.start(), "output-priority runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "output-priority owner committed");
    require(output.presentFirstCurrentRecord(),
        "output-priority owner publishes callback cursor");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "output-priority current-song cache ready", 8'000U);

    static std::atomic<bool> activeReadPending{};
    activeReadPending.store(false);
    stemwise::audio::engine::cache_test::afterReadCopy.store(+[](
        const stemwise::audio::engine::CurrentSongPcmReadConsumer *reader, bool copied) noexcept {
        if (reader && !reader->preparation.load()) activeReadPending.store(!copied);
    });
    const auto clearReadObserver = onScopeExit([] {
        stemwise::audio::engine::cache_test::afterReadCopy.store(nullptr);
    });
    output.setMaximumAcceptance(0U);
    output.consumeAll();
    const auto releaseAcceptedWrite = onScopeExit([&] {
        output.releaseLoopCutoverEnqueuePause(); runtime.requestStop(); runtime.join();
    });
    output.pauseNextLoopCutoverEnqueueAfterAcceptance();
    output.setAudiblePosition(owner.generation, owner.selectionToken, 128);
    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        128U,
        4'800U,
        0U,
        true) == NativePlaybackPublishStatus::Accepted,
        "output-priority loop accepted");
    waitUntil([&] { return output.loopCutoverIsOpen(); },
        "output-priority loop waits behind blocked output");
    output.setMaximumAcceptance(256U);
    runtime.notifyIngressAvailable();
    waitUntil([&] { return output.loopCutoverEnqueuePauseIsEntered(); },
        "output-priority loop pauses after one accepted inactive block");
    const auto queuedAtPreparation = output.queued();
    const bool audibleRefilled = queuedAtPreparation >= 2'304U;
    const bool activeReadBlocked = activeReadPending.load();
    output.releaseLoopCutoverEnqueuePause();
    require(!output.loopCutoverEnqueuePauseExpired(), "AE-06 output-priority handshake stayed within its bound");
    require(audibleRefilled || (queuedAtPreparation != 0U && activeReadBlocked),
        ("AE-11 continuation requires a full reserve or an actual blocked active read with surviving PCM: burst=" + std::to_string(burst) +
        " queued=" + std::to_string(queuedAtPreparation) +
        " target=" + std::to_string(runtime.diagnostics().targetQueuedFrames)).c_str());
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "output-priority loop commits its bounded continuation reserve");
    require(output.loopCutoverFramesAtCommit() >= 2'304U && output.playing(),
        "output-priority loop preserves the full continuation reserve and Play");
    const auto recordsAtBoundary = output.recordCount();
    output.consumeAll();
    runtime.notifyIngressAvailable();
    waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
        "outgoing producer reaches the prepared cutover boundary", 4U);
    require(output.recordCount() == recordsAtBoundary,
        "a committed loop continuation must stop outgoing PCM and cold re-primes beyond B");
    const auto prepared = output.lastPublishedLoopPolicy();
    require(runtime.publishDirectLoopPolicy(
        2U, owner.selectionToken, owner.generation, true, 128U, 4'928U) ==
            NativePlaybackPublishStatus::Accepted,
        "100 ms loop update accepted on the reported audio route");
    waitUntil([&] {
        return runtime.renderReadinessDiagnostics().directAtomicPolicyObservedRevision == 2U;
    }, "worker observes the 100 ms endpoint");
    waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
        "worker finishes retargeting the reserved A head", 4U);
    require(output.loopCutoverBeginCount() == 1U &&
            output.loopCutoverCommitCount() == 1U &&
            output.lastPublishedLoopPolicy().physicalRevision() == prepared.physicalRevision(),
        "20 ms device callbacks must not invalidate the prepared 100 ms A head");
    output.setLoopCutoverEnqueueBlocked(true);
    require(output.presentCommittedLoopCutover(), "callback adopts the retargeted A head");
    waitUntil([&] {
        return output.loopCutoverBeginCount() == 2U && output.loopCutoverEnqueueEntered();
    }, "next-revision spare opens before its PCM can finish");
    require(runtime.publishDirectLoopPolicy(
        3U, owner.selectionToken, owner.generation, true, 128U, 4'800U) ==
            NativePlaybackPublishStatus::Accepted,
        "B contracts while the dormant spare is still preparing");
    waitUntil([&] {
        return runtime.renderReadinessDiagnostics().directAtomicPolicyObservedRevision == 3U;
    }, "worker observes the B contraction during preparation");
    waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
        "open spare adopts the newer B", 4U);
    output.setLoopCutoverEnqueueBlocked(false);
    waitUntil([&] { return output.loopCutoverCommitCount() == 2U; },
        "retargeted open spare commits");
    const auto retargeted = output.lastPublishedLoopPolicy();
    require(!retargeted.nextRevisionOnly && retargeted.revision == 3U &&
            output.presentCommittedLoopCutover(),
        "the MOVE that retargeted an open spare must be eligible to adopt that spare");
    const auto commitsBeforeTune = output.loopCutoverCommitCount();
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, owner,
                banks, controls(2U, .5F, -12.F))) == NativePlaybackPublishStatus::Accepted,
        "tuned cycle controls accepted");
    output.consumeAll();
    runtime.notifyIngressAvailable();
    waitUntil([&] { return output.loopCutoverCommitCount() > commitsBeforeTune; },
        "tuned A head completes");
    const auto tuned = output.lastPublishedLoopPolicy();
    require(!tuned.nextRevisionOnly && output.presentCommittedLoopCutover(),
        "tuned loop enters its prepared A head");
    waitUntil([&] { return output.loopCutoverCommitCount() > commitsBeforeTune + 1U; },
        "natural tuned cycle prepares a physical successor");
    const auto cycle = output.lastPublishedLoopPolicy();
    require(!cycle.nextRevisionOnly && cycle.isPhysicalCycleSuccessorOf(tuned) &&
                output.committedLoopCutoverHead().sourceStart == 128.0 &&
                output.presentCommittedLoopCutover(),
        "natural tuned cycles can claim exact A without another handle edit or cold current prime");
    stopAndJoin(runtime);
}

void rapidDirectLoopBurstCancelsObsoleteOpenStageBeforeCommit() {
    constexpr SongRenderOwnership owner{1'752U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntimeConfiguration configuration =
        runtimeConfiguration(32'768U, 256U);
    configuration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    configuration.idleSleepMicros = 200'000U;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "rapid direct-loop owner selected");
    require(runtime.start(), "rapid direct-loop runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "rapid direct-loop owner committed");
    require(output.presentFirstCurrentRecord(),
        "rapid direct-loop owner publishes callback cursor");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "rapid direct-loop complete cache ready", 8'000U);

    output.setLoopCutoverEnqueueBlocked(true);
    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        128U,
        4'800U) == NativePlaybackPublishStatus::Accepted,
        "first rapid loop tuple accepted");
    waitUntil([&] {
        return output.loopCutoverBeginCount() == 1U &&
            output.loopCutoverEnqueueEntered();
    }, "first rapid loop opens the sole inactive role");

    require(runtime.publishDirectLoopPolicy(
        2U,
        owner.selectionToken,
        owner.generation,
        true,
        256U,
        1'800U) == NativePlaybackPublishStatus::Accepted,
        "second rapid loop tuple accepted");
    require(runtime.publishDirectLoopPolicy(
        3U,
        owner.selectionToken,
        owner.generation,
        true,
        384U,
        1'600U) == NativePlaybackPublishStatus::Accepted,
        "latest rapid loop tuple accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy demand = output.lastPublishedLoopDemand();
        return demand.revision == 3U && demand.aTimelineFrame == 384U &&
            demand.bTimelineFrameExclusive == 1'600U && output.loopCutoverIsOpen() &&
            output.blockedLoopCutoverSourceStart() == 384.0;
    }, "rapid tuple burst replaces the first bounded fill");
    require(output.loopCutoverCommitCount() == 0U,
        "blocked rapid tuple cannot physically commit");
    require(output.loopCutoverIsOpen(),
        "rapid tuple burst keeps one physical preparation open");
    require(output.playing(),
        "rapid tuple replacement preserves playback");

    const auto sleepsBeforeUnblock = runtime.diagnostics().idleSleeps;
    waitUntil([&] { return runtime.diagnostics().idleSleeps > sleepsBeforeUnblock; },
        "blocked head waits instead of busy-spinning");
    // Callback availability can change without waking the worker. A pending physical edit
    // must not inherit the ordinary playback idle interval.
    output.setLoopCutoverEnqueueBlocked(false);
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy physical = output.lastPublishedLoopPolicy();
        return physical.revision == 3U && physical.physicalReady &&
            output.loopCutoverCommitCount() == 1U;
    }, "released head must commit before the ordinary idle interval", 75U);
    const NativeAudioOutputLoopPolicy latest = output.lastPublishedLoopPolicy();
    const std::uint64_t replacementBegins = output.loopCutoverBeginCount();
    require(latest.aTimelineFrame == 384U &&
            latest.bTimelineFrameExclusive == 1'600U &&
            latest.cutoverSourceRevision == 0U &&
            latest.physicalRevision() != 0U &&
            replacementBegins >= 2U &&
            output.loopCutoverBeginCount() == replacementBegins &&
            output.loopCutoverIsCommitted(),
        "every observed obsolete fill is discarded and only the latest tuple is callback-claimable");
    require(output.loopCutoverFramesAtCommit() >= 2'304U,
        "latest boundary commits its full continuation starvation reserve");
    const FakeOutputRecord latestHead = output.committedLoopCutoverHead();
    require(latestHead.loopEnabled &&
            latestHead.loopPolicyRevision == latest.physicalRevision() &&
            latestHead.loopRevision == latest.physicalRevision() &&
            static_cast<std::uint64_t>(std::floor(latestHead.sourceStart)) == 384U,
        "latest physical head starts at exact A with its physical identity");

    require(output.presentCommittedLoopCutover(),
        "callback adopts the latest rapid tuple");
    waitUntil([&] {
        return runtime.diagnostics().presentedPosition.loopPolicyRevision ==
            latest.physicalRevision();
    }, "worker observes the callback-owned active physical revision");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy spare = output.lastPublishedLoopPolicy();
        return output.loopCutoverCommitCount() == 2U && spare.nextRevisionOnly &&
            spare.revision == latest.revision &&
            spare.physicalRevision() > latest.physicalRevision();
    }, "installed loop primes one dormant A-head spare");
    const NativeAudioOutputLoopPolicy spare = output.lastPublishedLoopPolicy();
    require(spare.aTimelineFrame == latest.aTimelineFrame &&
            spare.bTimelineFrameExclusive == latest.bTimelineFrameExclusive &&
            spare.cutoverSourceRevision == latest.physicalRevision() &&
            output.playing(),
        "dormant spare preserves the installed logical loop and Play");

    const std::uint64_t beginsBeforeEdit = output.loopCutoverBeginCount();
    const auto releaseAcceptedWrite = onScopeExit([&] {
        output.releaseLoopCutoverEnqueuePause(); runtime.requestStop(); runtime.join();
    });
    output.pauseNextLoopCutoverEnqueueAfterAcceptance();
    require(runtime.publishDirectLoopPolicy(
        4U,
        owner.selectionToken,
        owner.generation,
        true,
        640U,
        1'400U) == NativePlaybackPublishStatus::Accepted,
        "newest tuple accepted after the installed rapid loop");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy demand = output.lastPublishedLoopDemand();
        return demand.revision == 4U && demand.enabled && !demand.physicalReady;
    }, "newest logical tuple reaches the callback immediately");
    waitUntil([&] { return output.loopCutoverEnqueuePauseIsEntered(); },
        "obsolete A preparation pauses after one accepted render block");
    require(runtime.publishDirectLoopPolicy(
        5U,
        owner.selectionToken,
        owner.generation,
        true,
        768U,
        1'600U) == NativePlaybackPublishStatus::Accepted,
        "newer A arrives while obsolete preparation is rendering");
    output.releaseLoopCutoverEnqueuePause();
    require(!output.loopCutoverEnqueuePauseExpired(), "AE-06 superseded A handshake stayed within its bound");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy physical = output.lastPublishedLoopPolicy();
        return physical.revision == 5U && physical.physicalReady;
    }, "newest tuple preempts obsolete preparation");
    const NativeAudioOutputLoopPolicy finalPolicy = output.lastPublishedLoopPolicy();
    require(finalPolicy.aTimelineFrame == 768U &&
            finalPolicy.bTimelineFrameExclusive == 1'600U &&
            finalPolicy.cutoverSourceRevision == latest.physicalRevision() &&
            finalPolicy.physicalRevision() > latest.physicalRevision() &&
            output.loopCutoverBeginCount() == beginsBeforeEdit + 2U &&
            output.lastCancelledLoopCutoverQueuedFrames() <=
                configuration.pipeline.maximumBlockFrames &&
            output.loopCutoverIsCommitted() && output.playing(),
        "obsolete DSP stops within one bounded block and only newest A commits");
    const FakeOutputRecord finalHead = output.committedLoopCutoverHead();
    require(finalHead.loopEnabled &&
            finalHead.loopPolicyRevision == finalPolicy.physicalRevision() &&
            finalHead.loopRevision == finalPolicy.physicalRevision() &&
            static_cast<std::uint64_t>(std::floor(finalHead.sourceStart)) == 768U,
        "newest physical head is exact loop-enabled PCM beginning at latest A");
    stopAndJoin(runtime);
}

void adoptedLoopContinuationReplaysNewerCanonicalControls() {
    constexpr SongRenderOwnership owner{1'758U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(32'768U, 256U), first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U, 1.0F, 0.0F, MixerSourceMode::Stems))) == NativePlaybackPublishStatus::Accepted,
        "continuation-control owner selected");
    require(runtime.start(), "continuation-control runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "continuation-control owner committed");
    require(output.presentFirstCurrentRecord(),
        "continuation-control owner presented");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "continuation-control cache ready", 8'000U);
    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        128U,
        4'800U) == NativePlaybackPublishStatus::Accepted,
        "continuation-control loop accepted");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "continuation-control spare committed");
    const FakeOutputRecord prepared = output.committedLoopCutoverHead();
    require(prepared.controlRevision == 1U &&
            prepared.sourceIntentRevision == 1U &&
            prepared.sourceMode == NativeAudioSourceMode::Stems &&
            prepared.stemBusReadyMask == kStemwiseAllStemBusesReadyMask,
        "continuation was prepared from actual ready Stems buses");
    const auto originalStart = output.recordCount();
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, owner, banks,
        controls(2U))) == NativePlaybackPublishStatus::Accepted, "prepared buses switch to Original without replacing DSP");
    static_cast<void>(waitForRecord(output, originalStart, [](const FakeOutputRecord &record) {
        return record.controlRevision == 2U && record.sourceMode == NativeAudioSourceMode::Original;
    }, "Original controls render before the latest Stems intent"));
    require(output.loopCutoverBeginCount() == 1U,
        "ready Stems-to-Original controls preserve the prepared all-bus head");

    SongRenderControlSnapshot stems = controls(
        3U, 1.0F, 0.0F, MixerSourceMode::Stems);
    stems.sourceIntentRevision = 3U;
    stems.stemIntentRevision = 3U;
    output.consumeAll();
    const std::size_t stemsRecordStart = output.recordCount();
    require(runtime.publishCommand(selectedCommand(
        3U,
        NativePlaybackBank::First,
        owner,
        banks,
        stems)) == NativePlaybackPublishStatus::Accepted,
        "newer stems controls accepted while continuation is committed");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.controlRevision == 3U &&
            readiness.desiredSourceMode == MixerSourceMode::Stems &&
            output.loopCutoverIsCommitted() &&
            output.loopCutoverWriteReleaseCount() == 0U;
    }, "newer controls apply before continuation adoption");
    static_cast<void>(waitForRecord(
        output,
        stemsRecordStart,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 3U &&
                record.sourceIntentRevision == 3U &&
                record.sourceMode == NativeAudioSourceMode::Stems;
        },
        "active producer renders newer stems controls"));

    require(output.loopCutoverBeginCount() == 1U,
        "final-mix control changes must preserve the already prepared all-lane A head");
    const std::size_t adoptedRecordStart = output.recordCount();
    require(output.presentCommittedLoopCutover(),
        "callback adopts the older prepared continuation");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 1U; },
        "worker adopts callback-owned continuation");
    output.consumeAll();
    static_cast<void>(waitForRecord(
        output,
        adoptedRecordStart,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 3U &&
                record.sourceIntentRevision == 3U &&
                record.stemIntentRevision == 3U &&
                record.sourceMode == NativeAudioSourceMode::Stems &&
                record.stemBusReadyMask == kStemwiseAllStemBusesReadyMask;
        },
        "adopted continuation replays the newer stems controls"));
    require(output.playing(),
        "continuation control replay preserves playback");
    stopAndJoin(runtime);
}

bool loopTimingQualification = false;
std::array<std::uint64_t, 128U> loopTimingSamples{};
std::size_t loopTimingSampleCount = 0U;
void observeLoopLatency(std::uint64_t micros) {
    if (!loopTimingQualification) return;
    require(loopTimingSampleCount < loopTimingSamples.size(), "loop performance sample overflow");
    loopTimingSamples[loopTimingSampleCount++] = micros;
}

void rapidBOnlyLoopBurstReusesPreparedA(bool newerBAtAdoption = false,
    bool expansionAtCancellation = false) {
    constexpr SongRenderOwnership owner{1'759U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(4'096U, 192U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntimeConfiguration configuration =
        runtimeConfiguration(32'768U, 256U);
    configuration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(
        configuration, first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U, NativePlaybackBank::First, owner, banks, controls(1U))) ==
            NativePlaybackPublishStatus::Accepted,
        "B-only owner selected");
    require(runtime.start(), "B-only runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "B-only owner committed");
    require(output.presentFirstCurrentRecord(), "B-only owner presented");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "B-only cache ready", 8'000U);

    output.setLoopCutoverEnqueueBlocked(true);
    require(runtime.publishDirectLoopPolicy(
        1U, owner.selectionToken, owner.generation, true, 128U, 4'800U) ==
            NativePlaybackPublishStatus::Accepted,
        "B-only first tuple accepted");
    waitUntil([&] {
        return output.loopCutoverBeginCount() == 1U &&
            output.loopCutoverEnqueueEntered();
    }, "B-only prepared A is open");

    require(runtime.publishDirectLoopPolicy(
        2U, owner.selectionToken, owner.generation, true, 128U, 5'200U) ==
            NativePlaybackPublishStatus::Accepted,
        "B-only second tuple accepted");
    require(runtime.publishDirectLoopPolicy(
        3U, owner.selectionToken, owner.generation, true, 128U, 5'600U) ==
            NativePlaybackPublishStatus::Accepted,
        "B-only latest tuple accepted");
    waitForRenderProgress(
        runtime,
        runtime.diagnostics().renderCycles,
        "B-only burst reaches the open stage",
        4U);
    require(output.loopCutoverBeginCount() == 1U && output.loopCutoverIsOpen() &&
            output.loopCutoverCommitCount() == 0U && output.playing(),
        "B-only MOVE retains one prepared A role");

    output.setLoopCutoverEnqueueBlocked(false);
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy physical = output.lastPublishedLoopPolicy();
        return physical.revision == 3U && physical.aTimelineFrame == 128U &&
            physical.bTimelineFrameExclusive == 5'600U && physical.physicalReady &&
            output.loopCutoverCommitCount() == 1U;
    }, "latest B commits on the retained A role");
    require(output.loopCutoverBeginCount() == 1U && output.playing(),
        "B-only burst neither rebuilds A nor changes Play");
    require(output.loopCutoverFramesAtCommit() >= configuration.minimumOutputReserveFrames(),
        "prepared A becomes callback-claimable only with its starvation reserve");

    const auto beforeEmptyGrid = runtime.diagnostics().renderCycles;
    require(runtime.publishBeatGrid({1U, NativePlaybackBank::First, owner, 0U}, nullptr, nullptr) ==
        NativePlaybackPublishStatus::Accepted, "empty grid accepted over prepared loop");
    waitForRenderProgress(runtime, beforeEmptyGrid, "prepared loop sees empty-grid no-op", 4U);
    require(output.loopCutoverBeginCount() == 1U && output.loopCutoverCommitCount() == 1U &&
        output.loopCutoverIsCommitted() && runtime.diagnostics().banks[0].beatGridClears == 0U,
        "empty-grid no-op invalidated an already prepared loop");

    const NativeAudioOutputLoopPolicy prepared = output.lastPublishedLoopPolicy();
    const std::uint64_t attemptsBeforeMove = output.loopCutoverCommitAttemptCount();
    const std::uint64_t moveStarted = testMonotonicMicros();
    require(runtime.publishDirectLoopPolicy(
        4U, owner.selectionToken, owner.generation, true, 128U, 4'400U) ==
             NativePlaybackPublishStatus::Accepted,
        "published B-only MOVE is accepted");
    require(runtime.publishDirectLoopPolicy(
        5U, owner.selectionToken, owner.generation, true, 128U, 4'000U) ==
             NativePlaybackPublishStatus::Accepted,
        "latest published B-only MOVE is accepted");
    observeLoopLatency(testMonotonicMicros() - moveStarted);
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy demand = output.lastPublishedLoopDemand();
        return demand.revision == 5U && demand.bTimelineFrameExclusive == 4'000U;
    }, "callback mailbox owns latest B without a physical republish");
    waitForRenderProgress(
        runtime,
        runtime.diagnostics().renderCycles,
        "prepared A producer adopts latest B",
        4U);
    require(output.loopCutoverCommitAttemptCount() == attemptsBeforeMove &&
            output.lastPublishedLoopPolicy().physicalRevision() ==
                prepared.physicalRevision(),
        "B-only MOVE cannot re-enter the physical commit path");
    require(output.loopCutoverBeginCount() == 1U &&
            output.loopCutoverCommitCount() == 1U && output.playing(),
        "published B-only MOVE preserves one physical A role and Play");

    output.setLoopCutoverWriteReleaseBlocked(true);
    require(output.presentCommittedLoopCutover(),
        "callback installs the retargeted prepared A role");
    if (newerBAtAdoption) {
        output.setLoopCutoverEnqueueBlocked(true);
        require(runtime.publishDirectLoopPolicy(
            6U, owner.selectionToken, owner.generation, true, 128U, 3'600U) ==
                NativePlaybackPublishStatus::Accepted,
            "new B arrives after callback swap but before worker adoption");
        waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
            "worker retargets the callback-owned producer before adoption", 4U);
    }
    for (std::uint32_t delayedWorkerCallback = 0U;
         delayedWorkerCallback < 8U;
         ++delayedWorkerCallback) {
        require(output.consumeFrames(192U) == 192U && output.playing(),
            "prepared A starved while post-B worker recovery was delayed");
    }
    output.setLoopCutoverWriteReleaseBlocked(false);
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 1U; },
        "retargeted role becomes continuously writable");
    if (newerBAtAdoption) {
        waitUntil([&] {
            const auto policy = output.lastPublishedLoopPolicy();
            return policy.revision == 6U && policy.physicalReady && !policy.hasPhysicalCutover();
        }, "adopting a physical role must publish its newer logical B to the callback");
        require(output.playing(), "logical adoption preserves uninterrupted playback");
        stopAndJoin(runtime);
        return;
    }
    for (std::uint32_t refill = 0U; refill < 8U; ++refill) {
        output.consumeAll();
        waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
            "retargeted producer refills", 4U);
    }
    const std::size_t boundary = waitForRecord(
        output,
        0U,
        [physicalRevision = prepared.physicalRevision()](const FakeOutputRecord &record) {
            return record.loopEnabled && record.loopPolicyRevision == physicalRevision &&
                static_cast<std::uint64_t>(std::floor(record.sourceEnd)) == 4'000U;
        },
        "retargeted producer reaches latest B while Play remains active");
    static_cast<void>(waitForRecord(
        output,
        boundary + 1U,
        [physicalRevision = prepared.physicalRevision()](const FakeOutputRecord &record) {
            return record.loopEnabled && record.loopPolicyRevision == physicalRevision &&
                static_cast<std::uint64_t>(std::floor(record.sourceStart)) == 128U;
        },
        "next rendered block starts at A instead of starving at B"));
    require(output.playing(), "B-to-A wrap cannot change Play");

    waitUntil([&] {
        const NativeAudioOutputLoopPolicy spare = output.lastPublishedLoopPolicy();
        return spare.nextRevisionOnly && spare.revision == 5U;
    }, "active loop primes its dormant A-head spare");
    const std::size_t activeReplacementFirstRecord = output.recordCount();
    const std::uint64_t beginsBeforeBMove = output.loopCutoverBeginCount();
    const std::uint64_t commitsBeforeBMove = output.loopCutoverCommitCount();
    const std::uint64_t bUpdateStarted = testMonotonicMicros();
    const NativePlaybackPublishStatus bPublishStatus = runtime.publishDirectLoopPolicy(
        6U, owner.selectionToken, owner.generation, true, 128U, 4'096U);
    require(bPublishStatus == NativePlaybackPublishStatus::Accepted,
        "active-loop B MOVE is accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy physical = output.lastPublishedLoopPolicy();
        return physical.revision == 6U && physical.bTimelineFrameExclusive == 4'096U &&
            physical.physicalReady && !physical.nextRevisionOnly;
    }, "active-loop B MOVE prepares its exact callback-claimable continuation");
    const std::uint64_t bBeginMicros = output.lastLoopCutoverBeginMicros();
    const std::uint64_t bCommitMicros = output.lastLoopCutoverCommitMicros();
    require(output.loopCutoverBeginCount() == beginsBeforeBMove + 1U &&
            output.loopCutoverCommitCount() == commitsBeforeBMove + 1U &&
            bBeginMicros >= bUpdateStarted && bCommitMicros >= bBeginMicros,
        "active-loop B MOVE event timestamps are ordered");
    const std::uint64_t bUpdateMicros = bCommitMicros - bUpdateStarted;
    observeLoopLatency(bUpdateMicros);
    const NativeAudioOutputLoopPolicy activeBPrepared = output.lastPublishedLoopPolicy();

    const std::uint64_t attemptsBeforeLatestB =
        output.loopCutoverCommitAttemptCount();
    const std::uint64_t commitsBeforeLatestB = output.loopCutoverCommitCount();
    const std::uint64_t latestBUpdateStarted = testMonotonicMicros();
    require(runtime.publishDirectLoopPolicy(
        7U, owner.selectionToken, owner.generation, true, 128U, 3'800U) ==
             NativePlaybackPublishStatus::Accepted,
        "committed active-loop B MOVE is accepted");
    require(runtime.publishDirectLoopPolicy(
        8U, owner.selectionToken, owner.generation, true, 128U, 3'600U) ==
            NativePlaybackPublishStatus::Accepted,
        "latest committed active-loop B MOVE is accepted");
    observeLoopLatency(testMonotonicMicros() - latestBUpdateStarted);
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy demand = output.lastPublishedLoopDemand();
        return demand.revision == 8U && demand.bTimelineFrameExclusive == 3'600U;
    }, "callback mailbox owns the latest active-loop B contraction");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy physical = output.lastPublishedLoopPolicy();
        return physical.physicalReady &&
            physical.physicalRevision() > activeBPrepared.physicalRevision() &&
            output.loopCutoverIsCommitted();
    }, "B contraction has one callback-claimable A head");
    waitForRenderProgress(
        runtime,
        runtime.diagnostics().renderCycles,
        "committed A head retargets to the latest B demand",
        4U);
    const NativeAudioOutputLoopPolicy latestActiveBPrepared =
        output.lastPublishedLoopPolicy();
    require(output.loopCutoverCommitAttemptCount() > attemptsBeforeLatestB &&
            output.loopCutoverCommitCount() == commitsBeforeLatestB + 1U &&
            output.playing(),
        "semantic B transition builds one A head without changing Play");
    if (expansionAtCancellation) {
        output.setLoopBoundaryCoverageAllowed(false);
        output.adoptCommittedLoopAtNextCancellation();
        require(runtime.publishDirectLoopPolicy(9U, owner.selectionToken, owner.generation,
            true, 128U, 6'000U) == NativePlaybackPublishStatus::Accepted,
            "expansion accepted while contracted callback continuation is pending");
        waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 2U; },
            "expansion cancellation adopts the exact callback producer");
        output.setLoopBoundaryCoverageAllowed(true);
        static_cast<void>(waitForRecord(output, activeReplacementFirstRecord,
            [](const FakeOutputRecord &record) {
                return record.loopEnabled && record.sourceEnd == 6'000.0;
            }, "adopted producer retained contracted B after expansion"));
        require(output.lastPublishedLoopDemand().bTimelineFrameExclusive == 6'000U,
            "callback expansion policy changed unexpectedly");
        stopAndJoin(runtime);
        return;
    }
    require(output.presentCommittedLoopCutover(),
        "callback installs the latest active-loop B continuation");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 2U; },
        "latest active-loop B continuation becomes writable");
    const std::size_t activeBoundary = waitForRecord(
        output,
        activeReplacementFirstRecord,
        [revision = latestActiveBPrepared.physicalRevision()](const FakeOutputRecord &record) {
            return record.loopEnabled && record.loopPolicyRevision == revision &&
                static_cast<std::uint64_t>(std::floor(record.sourceEnd)) == 3'600U;
        },
        "active-loop B burst reaches latest B without starving");
    static_cast<void>(waitForRecord(
        output,
        activeBoundary + 1U,
        [revision = latestActiveBPrepared.physicalRevision()](const FakeOutputRecord &record) {
            return record.loopEnabled && record.loopPolicyRevision == revision &&
                static_cast<std::uint64_t>(std::floor(record.sourceStart)) == 128U;
        },
        "active-loop B burst emits A immediately after latest B"));
    require(output.playing(), "active-loop B retarget cannot change Play");

    waitUntil([&] {
        const NativeAudioOutputLoopPolicy spare = output.lastPublishedLoopPolicy();
        return spare.nextRevisionOnly && spare.revision == 8U;
    }, "retargeted active loop primes its dormant A-head spare");
    const std::size_t aReplacementFirstRecord = output.recordCount();
    const std::uint64_t commitsBeforeAMove = output.loopCutoverCommitCount();
    const std::uint64_t aUpdateStarted = testMonotonicMicros();
    require(runtime.publishDirectLoopPolicy(
        9U, owner.selectionToken, owner.generation, true, 256U, 3'600U) ==
            NativePlaybackPublishStatus::Accepted,
        "active-loop A MOVE is accepted");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy physical = output.lastPublishedLoopPolicy();
        return physical.revision == 9U && physical.aTimelineFrame == 256U &&
            physical.bTimelineFrameExclusive == 3'600U && physical.physicalReady &&
            !physical.nextRevisionOnly;
    }, "active-loop A MOVE prepares exact new A");
    const std::uint64_t aCommitMicros = output.lastLoopCutoverCommitMicros();
    require(aCommitMicros >= aUpdateStarted,
        "active-loop A MOVE event timestamp follows publication");
    const std::uint64_t aUpdateMicros = aCommitMicros - aUpdateStarted;
    observeLoopLatency(aUpdateMicros);
    const NativeAudioOutputLoopPolicy activeAPrepared = output.lastPublishedLoopPolicy();
    require(output.loopCutoverCommitCount() == commitsBeforeAMove + 1U &&
            activeAPrepared.revision == 9U && activeAPrepared.aTimelineFrame == 256U &&
            activeAPrepared.bTimelineFrameExclusive == 3'600U && output.playing(),
        "A MOVE commits exact coordinates without changing Play");
    require(output.presentCommittedLoopCutover(),
        "callback installs exact moved A");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 3U; },
        "moved A producer becomes continuously writable");
    const std::size_t movedABoundary = waitForRecord(
        output,
        aReplacementFirstRecord,
        [revision = activeAPrepared.physicalRevision()](const FakeOutputRecord &record) {
            return record.loopEnabled && record.loopPolicyRevision == revision &&
                static_cast<std::uint64_t>(std::floor(record.sourceEnd)) == 3'600U;
        },
        "moved A producer reaches B without starving");
    static_cast<void>(waitForRecord(
        output,
        movedABoundary + 1U,
        [revision = activeAPrepared.physicalRevision()](const FakeOutputRecord &record) {
            return record.loopEnabled && record.loopPolicyRevision == revision &&
                static_cast<std::uint64_t>(std::floor(record.sourceStart)) == 256U;
        },
        "moved A producer emits exact new A after B"));
    require(output.playing(), "A MOVE and subsequent wrap cannot change Play");
    stopAndJoin(runtime);
}

void rapidDirectLoopRevertCancelsObsoleteOpenStage() {
    constexpr SongRenderOwnership owner{1'757U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    constexpr std::uint64_t a = 128U;
    constexpr std::uint64_t b = 4'800U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);

    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntimeConfiguration configuration =
        runtimeConfiguration(32'768U, 256U);
    configuration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "rapid revert owner selected");
    require(runtime.start(), "rapid revert runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "rapid revert owner committed");
    require(output.presentFirstCurrentRecord(),
        "rapid revert owner publishes callback cursor");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "rapid revert complete cache ready", 8'000U);

    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        a,
        b) == NativePlaybackPublishStatus::Accepted,
        "rapid revert baseline tuple accepted");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "rapid revert baseline physically commits");
    const NativeAudioOutputLoopPolicy baseline = output.lastPublishedLoopPolicy();
    require(baseline.revision == 1U && baseline.aTimelineFrame == a &&
            baseline.bTimelineFrameExclusive == b &&
            baseline.physicalRevision() != 0U,
        "rapid revert baseline has exact logical and physical identity");

    require(output.presentCommittedLoopCutover(),
        "callback adopts the rapid revert baseline");
    waitUntil([&] {
        return runtime.diagnostics().presentedPosition.loopPolicyRevision ==
            baseline.physicalRevision();
    }, "worker observes the rapid revert active physical revision");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy spare = output.lastPublishedLoopPolicy();
        return output.loopCutoverCommitCount() == 2U && spare.nextRevisionOnly &&
            spare.revision == baseline.revision &&
            spare.physicalRevision() > baseline.physicalRevision();
    }, "rapid revert baseline primes one dormant A-head spare");
    require(runtime.diagnostics().presentedPosition.loopPolicyRevision ==
                baseline.physicalRevision() &&
            output.playing(),
        "dormant spare does not replace the installed baseline role");

    output.setLoopCutoverEnqueueBlocked(true);
    const std::uint64_t beginsBeforeEdits = output.loopCutoverBeginCount();
    const std::uint64_t commitsBeforeEdits = output.loopCutoverCommitCount();
    require(runtime.publishDirectLoopPolicy(
        2U,
        owner.selectionToken,
        owner.generation,
        true,
        256U,
        1'800U) == NativePlaybackPublishStatus::Accepted,
        "rapid revert temporary tuple accepted");
    waitUntil([&] {
        return output.loopCutoverBeginCount() == beginsBeforeEdits + 1U &&
            output.loopCutoverEnqueueEntered();
    }, "temporary tuple cancels and reopens the sole inactive role");

    require(runtime.publishDirectLoopPolicy(
        3U,
        owner.selectionToken,
        owner.generation,
        true,
        a,
        b) == NativePlaybackPublishStatus::Accepted,
        "rapid revert latest coordinates accepted as a new demand");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy physical = output.lastPublishedLoopPolicy();
        return physical.revision == 3U && physical.physicalReady &&
            !physical.hasPhysicalCutover();
    }, "rapid revert restores the installed physical tuple");
    require(output.loopCutoverCommitCount() == commitsBeforeEdits,
        "rapid revert cannot commit obsolete preparation");
    waitUntil([&] {
        return output.loopCutoverBeginCount() == beginsBeforeEdits + 2U &&
            output.loopCutoverIsOpen();
    }, "rapid revert replaces the retired dormant spare");
    require(runtime.diagnostics().presentedPosition.loopPolicyRevision ==
                baseline.physicalRevision() &&
            output.playing(),
        "rapid revert preserves the audible role and Play");
    stopAndJoin(runtime);
}
