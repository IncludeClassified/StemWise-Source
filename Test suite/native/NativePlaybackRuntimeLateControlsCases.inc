bool benchmarkControlEvidence = false;
void audibleControlEvidenceRequiresAWholeSettledCallbackAndExactOwner() {
    SongLaneIngressBank first(32'768, 480);
    SongLaneIngressBank second(32'768, 480);
    configureLanes(first, 2);
    configureLanes(second, 3);
    offerAllLanes(first, 2, 12'000);
    offerAllLanes(second, 3, 12'000);
    FakeOutput output(512, 64);
    output.setMaximumAcceptance(64);
    NativePlaybackRuntime runtime(runtimeConfiguration(16'384, 64), first, second, output.port());
    [[maybe_unused]] const auto runtimeCleanup = onScopeExit([&]() noexcept {
        runtime.requestStop();
        runtime.join();
    });
    const SongRenderOwnership firstOwner{810, 2};
    const SongRenderOwnership secondOwner{811, 3};
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, firstOwner),
        assignedBank(1, secondOwner),
    };

    auto initial = controls(1, 1.0F, 0.0F, MixerSourceMode::Original);
    initial.mixer.sourceTransitionFrames = 128;
    require(runtime.publishCommand(selectedCommand(
        1, NativePlaybackBank::First, firstOwner, banks, initial)) ==
        NativePlaybackPublishStatus::Accepted, "audible evidence initial command");
    require(runtime.start(), "audible evidence runtime start");
    waitUntil([&] { return output.commitCount() == 1U; }, "audible evidence initial commit");
    require(
        runtime.diagnostics().audibleControl.sequence == 0U,
        "mailbox/render/output acceptance is not audible control evidence");
    require(output.presentFirstCurrentRecord(), "audible evidence first callback");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return sameOwnership(diagnostic.committedOwnership, firstOwner) &&
            diagnostic.audibleControl.sequence != 0U &&
            diagnostic.audibleControl.controlRevision == 1U;
    }, "initial audible control accepted");
    auto diagnostic = runtime.diagnostics();
    require(
        diagnostic.audibleControl.actualSourceMode == MixerSourceMode::Original,
        "initial callback proves original");
    std::uint64_t evidenceSequence = diagnostic.audibleControl.sequence;

    auto publishSource = [&] (
        std::uint64_t commandRevision,
        MixerSourceMode source,
        std::uint32_t transitionFrames = 128U) {
        auto snapshot = controls(commandRevision, 1.0F, 0.0F, source);
        snapshot.mixer.sourceTransitionFrames = transitionFrames;
        require(runtime.publishCommand(selectedCommand(
            commandRevision,
            NativePlaybackBank::First,
            firstOwner,
            banks,
            snapshot)) == NativePlaybackPublishStatus::Accepted,
            "source evidence command accepted");
    };

    // This case isolates audible-control evidence from incremental decoder readiness.
    waitUntil([&] {
        const auto prepared = runtime.diagnostics();
        for (std::size_t lane = 0U; lane < kLaneCount; ++lane)
            if (prepared.banks[0U].lanes[lane].appendedFrames != 12'000U) return false;
        return true;
    }, "all selected source lanes admitted before audible-control transitions");
    std::size_t recordStart = output.recordCount();
    output.consumeAll();
    publishSource(2, MixerSourceMode::Stems);
    const std::size_t transitioningToStems = waitForRecord(
        output,
        recordStart,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 2U && !record.sourceSettled;
        },
        "transitioning stems record");
    const std::size_t settledStems = waitForRecord(
        output,
        transitioningToStems,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 2U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Stems;
        },
        "settled stems record");
    require(
        runtime.diagnostics().audibleControl.sequence == evidenceSequence,
        "render application alone does not acknowledge stems");
    const std::uint64_t transitionCycles = runtime.diagnostics().renderCycles;
    require(output.presentRecord(transitioningToStems), "present source crossfade block");
    waitForRenderProgress(runtime, transitionCycles,
        "source crossfade callback was observed by the render worker");
    require(
        runtime.diagnostics().audibleControl.sequence == evidenceSequence,
        "source crossfade callback is not acknowledged");
    std::atomic<std::uint64_t> progressWake{std::numeric_limits<std::uint64_t>::max()};
    std::atomic<bool> progressWaiterFinished{false};
    std::thread progressWaiter([&] {
        progressWake.store(
            runtime.waitForAudibleControlProgress(evidenceSequence),
            std::memory_order_release);
        progressWaiterFinished.store(true, std::memory_order_release);
    });
    [[maybe_unused]] const auto progressWaiterCleanup = onScopeExit([&]() noexcept {
        if (!progressWaiter.joinable()) return;
        runtime.requestStop();
        progressWaiter.join();
    });
    require(output.presentRecord(settledStems), "present first settled stems callback");
    waitUntil(
        [&] { return progressWaiterFinished.load(std::memory_order_acquire); },
        "audible-control observer wakes after exact callback evidence");
    progressWaiter.join();
    require(
        progressWake.load(std::memory_order_acquire) > evidenceSequence,
        "render-worker audible-control publication wakes its non-real-time observer");
    waitUntil([&] {
        return runtime.diagnostics().audibleControl.controlRevision == 2U;
    }, "first all-stems callback acknowledged");
    diagnostic = runtime.diagnostics();
    require(diagnostic.audibleControl.actualSourceMode == MixerSourceMode::Stems,
        "audible stems mode exact");
    require(diagnostic.audibleControl.sequence > evidenceSequence,
        "audible control sequence monotonic");
    evidenceSequence = diagnostic.audibleControl.sequence;

    // Reverse toward Original, then supersede it while its source ramp is still audible.
    recordStart = output.recordCount();
    output.consumeAll();
    const auto beforeRevisionThreeControl = output.lastLateControl();
    publishSource(3, MixerSourceMode::Original, 4'096U);
    const std::size_t revisionThreeTransition = [&] {
        try {
            return waitForRecord(
                output,
                recordStart,
                [](const FakeOutputRecord &record) {
                    return record.controlRevision == 3U && !record.sourceSettled;
                },
                "revision three transition");
        } catch (...) {
            const auto failed = runtime.diagnostics();
            const auto control = output.lastLateControl();
            const auto count = output.recordCount();
            std::cerr << "REVISION_THREE_FAILURE records=" << count << " start=" << recordStart
                << " queued=" << output.queued() << " rendered=" << failed.outputFramesRendered
                << " before-control=" << beforeRevisionThreeControl.canonicalControlRevision << ':'
                << static_cast<unsigned>(beforeRevisionThreeControl.sourceMode)
                << " control=" << control.selectionToken << ':' << control.generation << ':'
                << control.canonicalControlRevision << ':' << static_cast<unsigned>(control.sourceMode)
                << " ramp=" << control.sourceRampFrames << '\n';
            for (std::size_t lane = 0U; lane < kLaneCount; ++lane)
                std::cerr << "REVISION_THREE_LANE " << lane << " appended="
                    << failed.banks[0U].lanes[lane].appendedFrames << '\n';
            std::size_t shown = 0U;
            for (std::size_t index = count; index != 0U && shown < 5U;) {
                const auto record = output.record(--index);
                if (record.token != firstOwner.selectionToken || record.generation != firstOwner.generation) continue;
                ++shown;
                std::cerr << "REVISION_THREE_RECORD " << index << " revision=" << record.controlRevision
                    << " source=" << static_cast<unsigned>(record.sourceMode)
                    << " settled=" << record.sourceSettled << " range=" << record.sourceStart << ".."
                    << record.sourceEnd << " frames=" << record.frames << '\n';
            }
            throw;
        }
    }();
    require(output.presentRecord(revisionThreeTransition), "present original transition");
    recordStart = output.recordCount();
    output.consumeAll();
    publishSource(4, MixerSourceMode::Stems);
    const std::size_t revisionFourTransition = waitForRecord(
        output,
        recordStart,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 4U && !record.sourceSettled;
        },
        "revision four reverse transition");
    const std::size_t revisionFourSettled = waitForRecord(
        output,
        revisionFourTransition,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 4U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Stems;
        },
        "revision four settled stems");
    const std::uint64_t reversalCycles = runtime.diagnostics().renderCycles;
    require(output.presentRecord(revisionFourTransition), "present reverse crossfade");
    waitForRenderProgress(runtime, reversalCycles,
        "reverse crossfade callback was observed by the render worker");
    require(runtime.diagnostics().audibleControl.controlRevision == 2U,
        "superseded original revision never acknowledged");
    require(output.presentRecord(revisionFourSettled), "present settled reversal");
    waitUntil([&] {
        return runtime.diagnostics().audibleControl.controlRevision == 4U;
    }, "rapid reversal newest revision acknowledged");
    evidenceSequence = runtime.diagnostics().audibleControl.sequence;

    // A callback span containing transition and settled records cannot use settled endpoints as
    // a shortcut; only a later wholly coherent callback may advance the evidence.
    recordStart = output.recordCount();
    output.consumeAll();
    publishSource(5, MixerSourceMode::Original);
    const std::size_t revisionFiveTransition = waitForRecord(
        output,
        recordStart,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 5U && !record.sourceSettled;
        },
        "revision five transition");
    const std::size_t revisionFiveSettled = waitForRecord(
        output,
        revisionFiveTransition,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 5U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Original;
        },
        "revision five settled original");
    const std::uint64_t straddledCycles = runtime.diagnostics().renderCycles;
    require(
        !output.presentControlSpan(
            revisionFiveTransition,
            revisionFiveSettled - revisionFiveTransition + 1U),
        "callback span straddling a source ramp rejected");
    waitForRenderProgress(runtime, straddledCycles,
        "straddled callback was observed by the render worker");
    require(runtime.diagnostics().audibleControl.sequence == evidenceSequence,
        "straddled callback publishes no evidence");
    require(output.presentRecord(revisionFiveSettled), "present whole original callback");
    waitUntil([&] {
        return runtime.diagnostics().audibleControl.controlRevision == 5U;
    }, "settled original callback acknowledged");

    // Commit the candidate owner, then inject a newer output-evidence sequence for the retired
    // owner. Runtime diagnostics must stay on the exact committed owner.
    output.consumeAll();
    banks[0] = assignedBank(1, firstOwner);
    banks[1] = assignedBank(1, secondOwner);
    auto candidateControl = controls(6, 1.0F, 0.0F, MixerSourceMode::Original);
    require(runtime.publishCommand(selectedCommand(
        6, NativePlaybackBank::Second, secondOwner, banks, candidateControl)) ==
        NativePlaybackPublishStatus::Accepted, "candidate audible evidence command");
    waitUntil([&] { return output.commitCount() == 2U; }, "candidate evidence commit");
    // Force the worker's split-read race: control evidence is observed before its next
    // presentation read. Publish it only once, so a later callback cannot hide its loss.
    const auto outgoingEvidence = runtime.diagnostics().audibleControl;
    output.injectControlEvidence(output.currentGenerationFirstRecord());
    const auto pendingControlCycles = runtime.diagnostics().renderCycles;
    waitForRenderProgress(runtime, pendingControlCycles,
        "candidate control evidence observed before ownership commit", 2U);
    require(runtime.diagnostics().audibleControl.sequence == outgoingEvidence.sequence,
        "candidate control evidence cannot publish against the outgoing owner");
    require(output.presentFirstCurrentPresentationOnly(), "candidate first presentation");
    waitUntil([&] {
        const auto current = runtime.diagnostics();
        return sameOwnership(current.committedOwnership, secondOwner) &&
            sameOwnership(current.audibleControl.ownership, secondOwner);
    }, "candidate audible evidence exact owner");
    const auto candidateEvidence = runtime.diagnostics().audibleControl;
    require(candidateEvidence.sequence == outgoingEvidence.sequence + 1U &&
        candidateEvidence.controlRevision == 6U,
        "candidate commits the original one-shot control tuple");
    const std::uint64_t staleEvidenceCycles = runtime.diagnostics().renderCycles;
    output.injectControlEvidence(revisionFiveSettled);
    waitForRenderProgress(runtime, staleEvidenceCycles,
        "stale owner evidence was observed by the render worker");
    diagnostic = runtime.diagnostics();
    require(sameOwnership(diagnostic.audibleControl.ownership, secondOwner),
        "stale owner evidence ignored");
    require(diagnostic.audibleControl.sequence == candidateEvidence.sequence,
        "stale owner cannot advance accepted evidence");

    std::atomic<std::uint64_t> stoppedWake{std::numeric_limits<std::uint64_t>::max()};
    std::atomic<bool> stoppedWaiterFinished{false};
    std::thread stoppedWaiter([&] {
        stoppedWake.store(
            runtime.waitForAudibleControlProgress(candidateEvidence.sequence),
            std::memory_order_release);
        stoppedWaiterFinished.store(true, std::memory_order_release);
    });
    [[maybe_unused]] const auto stoppedWaiterCleanup = onScopeExit([&]() noexcept {
        if (!stoppedWaiter.joinable()) return;
        runtime.requestStop();
        stoppedWaiter.join();
    });
    runtime.requestStop();
    waitUntil(
        [&] { return stoppedWaiterFinished.load(std::memory_order_acquire); },
        "runtime stop releases the audible-control observer");
    stoppedWaiter.join();
    require(
        stoppedWake.load(std::memory_order_acquire) == 0U,
        "runtime stop releases the non-real-time audible-control observer");
    runtime.join();
    require(runtime.status() == NativePlaybackRuntimeStatus::Stopped, "runtime stopped");
    runtime.requestStop();
    runtime.join();
    const auto full = runtime.diagnostics().audibleControl;
    const auto compact = runtime.audibleControlEvidence();
    require(compact.sequence == full.sequence && compact.ownership.selectionToken == full.ownership.selectionToken &&
        compact.ownership.generation == full.ownership.generation &&
        compact.controlRevision == full.controlRevision && compact.actualSourceMode == full.actualSourceMode &&
        compact.firstFingerprintSequence == full.firstFingerprintSequence &&
        compact.lastFingerprintSequence == full.lastFingerprintSequence &&
        compact.callbackSequence == full.callbackSequence && compact.pcmFrames == full.pcmFrames,
        "compact evidence changed the exact settled callback proof");
    if (benchmarkControlEvidence) {
        for (bool compactRead : {false, true}) {
            std::uint64_t checksum = 0;
            const auto begin = std::chrono::steady_clock::now();
            for (unsigned iteration = 0; iteration < 200000; ++iteration) {
                const auto proof = compactRead ? runtime.audibleControlEvidence() : runtime.diagnostics().audibleControl;
                checksum += proof.sequence + proof.controlRevision + proof.pcmFrames;
            }
            const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - begin).count();
            std::cout << "evidence=" << (compactRead ? "compact" : "full") << " iterations=200000 ns="
                      << elapsed << " checksum=" << checksum << '\n';
        }
    }

}

void diagnosticsSnapshotsRemainCoherentUnderConcurrentPublication() {
    SongLaneIngressBank first(4'096, 480);
    SongLaneIngressBank second(4'096, 480);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    [[maybe_unused]] const auto runtimeCleanup = onScopeExit([&]() noexcept {
        runtime.requestStop();
        runtime.join();
    });
    require(runtime.start(), "diagnostics coherence runtime start");

    constexpr std::uint64_t tokenBase = 1'000'000U;
    constexpr std::uint64_t publicationCount = 20'000U;
    std::atomic<bool> stopReader{false};
    std::atomic<bool> invalidSnapshot{false};
    std::atomic<std::uint64_t> snapshotsRead{0};
    std::jthread reader([&](std::stop_token stopToken) {
        const auto validOwner = [&](SongRenderOwnership owner) noexcept {
            if (owner.generation == 0U || owner.selectionToken == 0U) {
                return owner.generation == 0U && owner.selectionToken == 0U;
            }
            return owner.selectionToken == tokenBase + owner.generation;
        };
        while (!stopReader.load(std::memory_order_acquire) &&
               !stopToken.stop_requested()) {
            const NativePlaybackRuntimeDiagnostics diagnostic = runtime.diagnostics();
            bool valid = validOwner(diagnostic.requestedOwnership) &&
                validOwner(diagnostic.acceptedOwnership) &&
                validOwner(diagnostic.committedOwnership);
            if (diagnostic.presentationEvidence.sequence != 0U) {
                valid = valid && validOwner({
                    diagnostic.presentationEvidence.selectionToken,
                    diagnostic.presentationEvidence.generation});
            }
            if (diagnostic.presentedPosition.sequence != 0U) {
                valid = valid && validOwner({
                    diagnostic.presentedPosition.selectionToken,
                    diagnostic.presentedPosition.generation});
            }
            if (diagnostic.audibleEnd.sequence != 0U) {
                valid = valid && validOwner(diagnostic.audibleEnd.ownership);
            }
            if (diagnostic.audibleControl.sequence != 0U) {
                valid = valid && validOwner(diagnostic.audibleControl.ownership) &&
                    diagnostic.audibleControl.controlRevision != 0U &&
                    diagnostic.audibleControl.firstFingerprintSequence != 0U &&
                    diagnostic.audibleControl.lastFingerprintSequence != 0U &&
                    diagnostic.audibleControl.callbackSequence != 0U &&
                    diagnostic.audibleControl.pcmFrames != 0U;
            }
            for (const auto &bank : diagnostic.banks) {
                const bool expectedAssigned = (bank.appliedIntentRevision & 1U) != 0U;
                valid = valid && bank.assigned == expectedAssigned;
            }
            if (!valid) invalidSnapshot.store(true, std::memory_order_release);
            snapshotsRead.fetch_add(1U, std::memory_order_relaxed);
        }
    });
    [[maybe_unused]] const auto readerCleanup = onScopeExit([&]() noexcept {
        stopReader.store(true, std::memory_order_release);
        reader.request_stop();
        if (reader.joinable()) reader.join();
    });

    std::array<NativePlaybackBankIntent, 2> banks{};
    for (std::uint64_t index = 0; index < publicationCount; ++index) {
        const std::uint64_t revision = index + 1U;
        const std::uint64_t generation = index + 2U;
        const SongRenderOwnership owner{tokenBase + generation, generation};
        const std::size_t selected = static_cast<std::size_t>(index & 1U);
        const std::size_t inactive = selected == 0U ? 1U : 0U;
        banks[selected] = assignedBank(index * 2U + 1U, owner);
        banks[inactive] = NativePlaybackBankIntent{
            index * 2U + 2U, false, {}, 0, 0};
        const auto status = runtime.publishCommand(selectedCommand(
            revision,
            selected == 0U ? NativePlaybackBank::First : NativePlaybackBank::Second,
            owner,
            banks,
            controls(revision),
            false));
        require(status == NativePlaybackPublishStatus::Accepted,
            "diagnostics pressure command accepted");
        if ((index & 31U) == 0U) std::this_thread::yield();
    }
    const std::uint64_t finalGeneration = publicationCount + 1U;
    waitUntil([&] {
        return runtime.diagnostics().requestedOwnership.generation == finalGeneration;
    }, "diagnostics pressure final owner observed");
    waitUntil(
        [&] { return snapshotsRead.load(std::memory_order_acquire) >= 10'000U; },
        "diagnostics reader observes ten thousand coherent snapshots");
    stopReader.store(true, std::memory_order_release);
    reader.request_stop();
    reader.join();
    require(!invalidSnapshot.load(std::memory_order_acquire),
        "diagnostics tuple never tears across publications");
    stopAndJoin(runtime);
}

void startedStemGainPreviewHonorsCommandFloorAndEqualCoversRetirement() {
    SongLaneIngressBank first(65'536U, 480U);
    SongLaneIngressBank second(4'096U, 480U);
    configureLanes(first, 2U);
    offerAllLanes(first, 2U, 48'000U);
    FakeOutput output(512U, 64);
    output.setMaximumAcceptance(64U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(65'536U, 64U),
        first,
        second,
        output.port());
    const SongRenderOwnership owner{700U, 2U};
    const std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, owner),
        NativePlaybackBankIntent{},
    };

    require(
        runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            owner,
            banks,
            stemPreviewControls(1U))) == NativePlaybackPublishStatus::Accepted,
        "preview-floor initial command accepted");
    require(runtime.start(), "preview-floor runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "preview-floor initial commit");
    require(output.presentFirstCurrentRecord(), "preview-floor initial presentation");
    static_cast<void>(waitForRecord(
        output,
        0U,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 1U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Stems && record.peak > 0.005F;
        },
        "preview-floor canonical stems audible"));

    output.consumeAll();
    std::size_t recordStart = output.recordCount();
    NativePlaybackStemGainPreview preview = stemGainPreview(
        1U,
        11U,
        owner,
        2U,
        1U,
        0.0F);
    require(
        runtime.publishStemGainPreview(preview) == NativePlaybackPublishStatus::Accepted,
        "preview-floor future-fenced overlay accepted");
    static_cast<void>(waitForRecord(
        output,
        recordStart,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 1U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Stems && record.peak > 0.005F;
        },
        "preview-floor overlay did not apply before canonical command"));

    output.consumeAll();
    recordStart = output.recordCount();
    require(
        runtime.publishCommand(selectedCommand(
            2U,
            NativePlaybackBank::First,
            owner,
            banks,
            stemPreviewControls(2U))) == NativePlaybackPublishStatus::Accepted,
        "preview-floor releasing command accepted");
    static_cast<void>(waitForRecord(
        output,
        recordStart,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 2U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Stems && record.peak < 0.00001F;
        },
        "preview-floor overlay applied only after canonical command"));

    output.consumeAll();
    recordStart = output.recordCount();
    preview.publicationSequence = 2U;
    preview.coversOfferSequence = 11U;
    preview.activeMask = 0U;
    require(
        runtime.publishStemGainPreview(preview) == NativePlaybackPublishStatus::Accepted,
        "newer zero-mask retirement with equal covered offer accepted");
    static_cast<void>(waitForRecord(
        output,
        recordStart,
        [](const FakeOutputRecord &record) {
            return record.controlRevision == 2U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Stems && record.peak > 0.005F;
        },
        "equal-covers retirement restored canonical gain"));
    stopAndJoin(runtime);
}

void stemGainCallbackSettlementRequiresExactCommittedOwnerAndPreservesTuple() {
    SongLaneIngressBank first(65'536U, 480U);
    SongLaneIngressBank second(65'536U, 480U);
    configureLanes(first, 2U);
    configureLanes(second, 3U);
    offerAllLanes(first, 2U, 48'000U);
    offerAllLanes(second, 3U, 48'000U);
    FakeOutput output(512U, 64);
    output.setMaximumAcceptance(64U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(65'536U, 64U),
        first,
        second,
        output.port());
    const SongRenderOwnership owner{715U, 2U};
    const std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, owner),
        NativePlaybackBankIntent{},
    };
    require(
        runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            owner,
            banks,
            stemPreviewControls(1U))) == NativePlaybackPublishStatus::Accepted,
        "stem-gain callback selected command accepted");
    require(runtime.start(), "stem-gain callback runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "stem-gain callback commit");
    require(output.presentFirstCurrentRecord(), "stem-gain callback owner presented");
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, owner);
    }, "stem-gain callback owner committed");

    NativeAudioStemGainCallbackEvidence evidence{};
    evidence.sequence = 1U;
    evidence.generation = owner.generation + 99U;
    evidence.selectionToken = owner.selectionToken;
    evidence.publishedSequence = 17U;
    evidence.offeredSequence = 19U;
    evidence.activeMask = 0b10'1101U;
    evidence.gainBits = {
        std::bit_cast<std::uint32_t>(1.0F),
        std::bit_cast<std::uint32_t>(0.8F),
        std::bit_cast<std::uint32_t>(0.6F),
        std::bit_cast<std::uint32_t>(0.4F),
        std::bit_cast<std::uint32_t>(0.2F),
        std::bit_cast<std::uint32_t>(0.0F),
    };
    evidence.callbackSequence = 31U;
    evidence.firstTaggedFrame = 4'800;
    evidence.lastTaggedFrame = 4'863;
    evidence.taggedFrameCount = 64U;
    evidence.nonZeroPcmFrameCount = 63U;
    evidence.callbackObservedAtNs = 123'456'789U;
    output.publishStemGainEvidence(evidence);
    runtime.notifyIngressAvailable();
    const std::uint64_t cyclesAfterStale = runtime.diagnostics().renderCycles;
    waitUntil([&] {
        return runtime.diagnostics().renderCycles >= cyclesAfterStale + 4U;
    }, "stale stem-gain callback consumed");
    require(
        !runtime.stemGainCallbackSettlementDiagnostics().valid(),
        "retired-owner stem-gain callback not published as current settlement");

    evidence.sequence = 2U;
    evidence.generation = owner.generation;
    evidence.callbackSequence = 32U;
    evidence.callbackObservedAtNs = 223'456'789U;
    output.publishStemGainEvidence(evidence);
    runtime.notifyIngressAvailable();
    waitUntil([&] {
        return runtime.stemGainCallbackSettlementDiagnostics().sequence == 2U;
    }, "exact-owner stem-gain callback settlement published");
    const NativeAudioStemGainCallbackEvidence settled =
        runtime.stemGainCallbackSettlementDiagnostics();
    require(settled.valid(), "exact stem-gain callback settlement valid");
    require(
        settled.generation == owner.generation &&
            settled.selectionToken == owner.selectionToken &&
            settled.publishedSequence == evidence.publishedSequence &&
            settled.offeredSequence == evidence.offeredSequence &&
            settled.activeMask == evidence.activeMask &&
            settled.gainBits == evidence.gainBits &&
            settled.callbackObservedAtNs == evidence.callbackObservedAtNs,
        "runtime preserves exact callback owner/sequences/mask/gain bits/time");

    const SongRenderOwnership successor{716U, 3U};
    const std::array<NativePlaybackBankIntent, 2> successorBanks{
        assignedBank(1U, owner),
        assignedBank(2U, successor),
    };
    require(
        runtime.publishCommand(selectedCommand(
            2U,
            NativePlaybackBank::Second,
            successor,
            successorBanks,
            stemPreviewControls(2U, 102U, 202U))) ==
            NativePlaybackPublishStatus::Accepted,
        "future-evidence successor command accepted");
    waitUntil([&] { return output.commitCount() == 2U; },
        "future-evidence successor staged commit");

    // Reproduce the callback/worker race: a gain tuple for the staged successor is observed after
    // this worker cycle's presentation read but before committed ownership advances. The output
    // publishes it only once, so the runtime must retain the exact sequence for reconsideration.
    evidence.sequence = 3U;
    evidence.generation = successor.generation;
    evidence.selectionToken = successor.selectionToken;
    evidence.publishedSequence = 27U;
    evidence.offeredSequence = 29U;
    evidence.callbackSequence = 41U;
    evidence.callbackObservedAtNs = 323'456'789U;
    output.publishStemGainEvidence(evidence);
    runtime.notifyIngressAvailable();
    const std::uint64_t cyclesBeforeFutureEvidence = runtime.diagnostics().renderCycles;
    waitUntil([&] {
        return runtime.diagnostics().renderCycles >= cyclesBeforeFutureEvidence + 4U;
    }, "future-owner stem-gain callback held pending");
    require(
        runtime.stemGainCallbackSettlementDiagnostics().sequence == settled.sequence,
        "future-owner evidence not published against outgoing committed owner");

    require(output.presentFirstCurrentRecord(), "future-evidence successor presented");
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, successor);
    }, "future-evidence successor committed");
    waitUntil([&] {
        return runtime.stemGainCallbackSettlementDiagnostics().sequence == evidence.sequence;
    }, "one-shot future-owner stem-gain evidence reconsidered after commit");
    const NativeAudioStemGainCallbackEvidence reconsidered =
        runtime.stemGainCallbackSettlementDiagnostics();
    require(
        reconsidered.generation == successor.generation &&
            reconsidered.selectionToken == successor.selectionToken &&
            reconsidered.publishedSequence == evidence.publishedSequence &&
            reconsidered.offeredSequence == evidence.offeredSequence &&
            reconsidered.callbackObservedAtNs == evidence.callbackObservedAtNs,
        "future-owner callback evidence survives the presentation race exactly once");
    stopAndJoin(runtime);
}

void queueFullLateMixPublicationBypassesFortyEightMillisecondRenderReserve() {
    SongLaneIngressBank first(65'536U, 480U);
    SongLaneIngressBank second(65'536U, 480U);
    configureLanes(first, 2U);
    offerAllLanes(first, 2U, 16'000U);

    FakeOutput output(2'304U, 192);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntimeConfiguration configuration =
        runtimeConfiguration(65'536U, 256U);
    configuration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const SongRenderOwnership owner{717U, 2U};
    const std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, owner),
        NativePlaybackBankIntent{},
    };
    require(
        runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            owner,
            banks,
            controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "queue-full late-mix initial command accepted");
    require(runtime.start(), "queue-full late-mix runtime starts");
    waitUntil([&] {
        return output.queued() == 2'304U && output.commitCount() == 1U;
    }, "queue-full late-mix reserve prefills completely");

    const std::size_t recordsAtFullReserve = output.recordCount();
    const std::uint64_t publicationsBeforeSource = output.lateControlPublicationCount();
    SongRenderControlSnapshot stems = controls(
        2U,
        1.0F,
        0.0F,
        MixerSourceMode::Stems);
    stems.mixer.stems[0].muted = true;
    require(
        runtime.publishCommand(selectedCommand(
            2U,
            NativePlaybackBank::First,
            owner,
            banks,
            stems)) == NativePlaybackPublishStatus::Accepted,
        "queue-full source endpoint accepted");
    waitUntil([&] {
        const NativeAudioLateMixControl control = output.lastLateControl();
        return output.lateControlPublicationCount() > publicationsBeforeSource &&
            control.generation == owner.generation &&
            control.selectionToken == owner.selectionToken &&
            control.canonicalControlRevision == 2U &&
            control.sourceMode == NativeAudioSourceMode::Stems;
    }, "queue-full canonical source endpoint publishes without render space");
    require(
        output.queued() == 2'304U && output.recordCount() == recordsAtFullReserve,
        "queue-full source publication neither drains nor renders around the reserve");

    const std::uint64_t publicationsBeforePreview = output.lateControlPublicationCount();
    require(
        runtime.publishStemGainPreview(stemGainPreview(
            1U,
            1U,
            owner,
            2U,
            1U,
            0.25F,
            1U,
            1U)) == NativePlaybackPublishStatus::Accepted,
        "queue-full validated stem preview accepted");
    waitUntil([&] {
        const NativeAudioLateMixControl control = output.lastLateControl();
        return output.lateControlPublicationCount() > publicationsBeforePreview &&
            control.generation == owner.generation &&
            control.selectionToken == owner.selectionToken &&
            control.canonicalControlRevision == 2U &&
            control.stemGainPreviewPublicationSequence == 1U &&
            control.stemGainPreviewCoversOfferSequence == 1U &&
            control.stemGainPreviewActiveMask == 1U;
    }, "queue-full preview endpoint publishes without waiting for queued PCM");
    const NativeAudioLateMixControl published = output.lastLateControl();
    require(
        published.stemGainPreviewGainBits[0] ==
                std::bit_cast<std::uint32_t>(0.25F) &&
            published.effectiveStemGains[0] == 0.25F,
        "held slider gain overrides mute at the queue-full mixer endpoint");
    require(
        output.queued() == 2'304U && output.recordCount() == recordsAtFullReserve,
        "slider endpoint bypasses the full 48 ms reserve without manufacturing PCM");
    stopAndJoin(runtime);
}

void startedStemGainPreviewIsSelectedOnlyAndCannotSurviveABA() {
    SongLaneIngressBank first(65'536U, 480U);
    SongLaneIngressBank second(65'536U, 480U);
    configureLanes(first, 2U);
    configureLanes(second, 3U);
    offerLane(first, 0U, 2U, 0U, 16'000U);
    offerLane(second, 0U, 3U, 0U, 24'000U);

    FakeOutput output(512U, 64);
    output.setMaximumAcceptance(64U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(65'536U, 64U),
        first,
        second,
        output.port());
    const SongRenderOwnership firstOwner{710U, 2U};
    const SongRenderOwnership secondOwner{711U, 3U};
    const SongRenderOwnership reboundFirstOwner{710U, 4U};
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, firstOwner),
        assignedBank(1U, secondOwner),
    };
    const auto awaitStemLanes = [&](SongLaneIngressBank &ingress, SongRenderOwnership owner, std::size_t frames) {
        // Delay the optional decoders past Original presentation, without letting the device drain them.
        for (std::size_t lane = 1U; lane < kLaneCount; ++lane)
            offerLane(ingress, lane, owner.generation, 0U, frames);
        runtime.notifyIngressAvailable();
        waitUntil([&] {
            const auto ready = runtime.renderReadinessDiagnostics();
            if (!sameOwnership(ready.ownership, owner)) return false;
            for (std::size_t lane = 0U; lane < kLaneCount; ++lane)
                if (ready.lanes[lane].generation != owner.generation ||
                    ready.lanes[lane].availableFromRenderStart == 0U) return false;
            return true;
        }, "preview-ABA exact selected owner has all required Stems lanes before device draining");
    };

    require(
        runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            firstOwner,
            banks,
            stemPreviewControls(1U))) == NativePlaybackPublishStatus::Accepted,
        "preview-ABA first selection accepted");
    require(runtime.start(), "preview-ABA runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "preview-ABA first commit");
    require(output.presentFirstCurrentRecord(), "preview-ABA first presentation");
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, firstOwner);
    }, "preview-ABA first presentation acknowledged by runtime");
    awaitStemLanes(first, firstOwner, 16'000U);

    output.consumeAll();
    std::size_t recordStart = output.recordCount();
    require(
        runtime.publishStemGainPreview(stemGainPreview(
            1U,
            1U,
            firstOwner,
            1U,
            1U,
            0.0F)) == NativePlaybackPublishStatus::Accepted,
        "preview-ABA first-bank overlay accepted");
    static_cast<void>(waitForRecord(
        output,
        recordStart,
        [&](const FakeOutputRecord &record) {
            return record.token == firstOwner.selectionToken &&
                record.generation == firstOwner.generation &&
                record.controlRevision == 1U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Stems && record.peak < 0.00001F;
        },
        "preview-ABA first selected bank muted"));

    const std::uint64_t cyclesBeforeUnselectedPreview = runtime.diagnostics().renderCycles;
    require(
        runtime.publishStemGainPreview(stemGainPreview(
            2U,
            2U,
            secondOwner,
            1U,
            1U,
            0.0F)) == NativePlaybackPublishStatus::Accepted,
        "preview-ABA unselected-bank publication accepted into mailbox");
    waitUntil([&] {
        return runtime.diagnostics().renderCycles >= cyclesBeforeUnselectedPreview + 10U;
    }, "preview-ABA unselected publication consumed while first remained selected");

    output.consumeAll();
    banks[0] = assignedBank(1U, firstOwner);
    require(
        runtime.publishCommand(selectedCommand(
            2U,
            NativePlaybackBank::Second,
            secondOwner,
            banks,
            stemPreviewControls(2U))) == NativePlaybackPublishStatus::Accepted,
        "preview-ABA second selection accepted");
    waitUntil([&] { return output.commitCount() == 2U; }, "preview-ABA second commit");
    require(output.presentFirstCurrentRecord(), "preview-ABA second presentation");
    // A producer record and callback publication precede the runtime's ownership
    // acknowledgement. Retire the outgoing bank only after that exact transition.
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, secondOwner);
    }, "preview-ABA second presentation acknowledged before retiring first ingress");
    awaitStemLanes(second, secondOwner, 24'000U);
    recordStart = output.currentGenerationFirstRecord();
    static_cast<void>(waitForRecord(
        output,
        recordStart,
        [&](const FakeOutputRecord &record) {
            return record.token == secondOwner.selectionToken &&
                record.generation == secondOwner.generation &&
                record.controlRevision == 2U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Stems && record.peak > 0.005F;
        },
        "preview-ABA unselected publication did not leak into selected second bank"));

    // Advance the now-unselected first ingress only after generation 2 has rendered. Publishing
    // generation 4 before start would intentionally invalidate generation-2 producer authority.
    for (std::size_t lane = 0U; lane < kLaneCount; ++lane) {
        LaneIngress *ingress = first.lane(lane);
        require(
            ingress != nullptr && ingress->advanceGeneration(4U),
            "preview-ABA first bank advances to return generation");
    }
    offerLane(first, 0U, 4U, 0U, 16'000U);

    output.consumeAll();
    recordStart = output.recordCount();
    require(
        runtime.publishStemGainPreview(stemGainPreview(
            3U,
            3U,
            secondOwner,
            2U,
            1U,
            0.0F)) == NativePlaybackPublishStatus::Accepted,
        "preview-ABA selected second-bank overlay accepted");
    static_cast<void>(waitForRecord(
        output,
        recordStart,
        [&](const FakeOutputRecord &record) {
            return record.token == secondOwner.selectionToken &&
                record.generation == secondOwner.generation &&
                record.controlRevision == 2U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Stems && record.peak < 0.00001F;
        },
        "preview-ABA selected second bank muted"));

    output.consumeAll();
    banks[0] = assignedBank(2U, reboundFirstOwner);
    require(
        runtime.publishCommand(selectedCommand(
            3U,
            NativePlaybackBank::First,
            reboundFirstOwner,
            banks,
            stemPreviewControls(3U))) == NativePlaybackPublishStatus::Accepted,
        "preview-ABA rebound first selection accepted");
    waitUntil([&] { return output.commitCount() == 3U; }, "preview-ABA rebound commit");
    require(output.presentFirstCurrentRecord(), "preview-ABA rebound presentation");
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, reboundFirstOwner);
    }, "preview-ABA rebound presentation acknowledged by runtime");
    awaitStemLanes(first, reboundFirstOwner, 16'000U);
    recordStart = output.currentGenerationFirstRecord();
    try { static_cast<void>(waitForRecord(
        output,
        recordStart,
        [&](const FakeOutputRecord &record) {
            return record.token == reboundFirstOwner.selectionToken &&
                record.generation == reboundFirstOwner.generation &&
                record.controlRevision == 3U && record.sourceSettled &&
                record.sourceMode == NativeAudioSourceMode::Stems && record.peak > 0.005F;
        },
        "preview-ABA old first-bank overlay did not survive return"));
    } catch (...) {
        const auto diagnostics = runtime.diagnostics();
        const auto control = output.lastLateControl();
        std::cerr << "PREVIEW_ABA_FAILURE requested=" << diagnostics.requestedOwnership.selectionToken
            << ':' << diagnostics.requestedOwnership.generation << " committed="
            << diagnostics.committedOwnership.selectionToken << ':' << diagnostics.committedOwnership.generation
            << " playing=" << output.playing() << " queued=" << output.queued()
            << " records=" << output.recordCount() << " start=" << recordStart
            << " control=" << control.selectionToken << ':' << control.generation << ':'
            << control.canonicalControlRevision << " preview=" << control.stemGainPreviewPublicationSequence
            << " preview-mask=" << control.stemGainPreviewActiveMask
            << " effective=" << control.effectiveStemGains[0] << '\n';
        for (std::size_t index = recordStart; index < output.recordCount() && index < recordStart + 12U; ++index) {
            const auto record = output.record(index);
            std::cerr << "PREVIEW_ABA_RECORD " << index << " owner=" << record.token << ':'
                << record.generation << " revision=" << record.controlRevision << " settled="
                << record.sourceSettled << " source=" << static_cast<unsigned>(record.sourceMode)
                << " frames=" << record.frames << " peak=" << record.peak << '\n';
        }
        throw;
    }
    stopAndJoin(runtime);
}

void stemGainPreviewMailboxIsLatestOnlyAllocationFreeAndNotACommand() {
    SongLaneIngressBank first(4'096U, 480U);
    SongLaneIngressBank second(4'096U, 480U);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    NativePlaybackStemGainPreview preview{};
    preview.ownership = {700U, 900U};
    preview.sourceIntentRevision = 31U;
    preview.stemIntentRevision = 41U;
    preview.canonicalControlFloorRevision = 1U;
    preview.activeMask = 1U;
    preview.gains.fill(1.0F);

    const auto before = runtime.diagnostics();
    const std::uint64_t allocationsBefore =
        allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    for (std::uint64_t sequence = 1U; sequence <= 100'000U; ++sequence) {
        preview.publicationSequence = sequence;
        preview.coversOfferSequence = sequence;
        preview.gains[0] = static_cast<float>(sequence % 101U) / 100.0F;
        require(
            runtime.publishStemGainPreview(preview) == NativePlaybackPublishStatus::Accepted,
            "preview mailbox accepted 100k latest values");
    }
    allocation_probe::enabled.store(false, std::memory_order_release);
    require(
        allocation_probe::count.load(std::memory_order_relaxed) == allocationsBefore,
        "preview mailbox publication allocated");
    const auto after = runtime.diagnostics();
    require(
        after.commandSnapshotsPublished == before.commandSnapshotsPublished &&
            after.commandSnapshotsConsumed == before.commandSnapshotsConsumed,
        "preview publication entered the full command mailbox");

    require(
        runtime.publishStemGainPreview(preview) == NativePlaybackPublishStatus::StaleRevision,
        "duplicate native preview publication rejected");
    preview.publicationSequence += 1U;
    preview.coversOfferSequence -= 1U;
    require(
        runtime.publishStemGainPreview(preview) == NativePlaybackPublishStatus::StaleRevision,
        "regressing covered offer rejected");
    preview.coversOfferSequence += 1U;
    preview.activeMask = 1U << stemwise::audio::kNativeAudioStemGainLaneCount;
    require(
        runtime.publishStemGainPreview(preview) == NativePlaybackPublishStatus::InvalidSnapshot,
        "out-of-range preview mask rejected");
    preview.activeMask = 1U;
    preview.gains[0] = std::numeric_limits<float>::quiet_NaN();
    require(
        runtime.publishStemGainPreview(preview) == NativePlaybackPublishStatus::InvalidSnapshot,
        "non-finite preview gain rejected");
}
