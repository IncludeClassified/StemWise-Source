void commandSelectionFenceMatrixIsExact() {
    SongLaneIngressBank first(4'096U, 480U);
    SongLaneIngressBank second(4'096U, 480U);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    std::array<NativePlaybackBankIntent, 2> banks{
        NativePlaybackBankIntent{1U, false, {}, 0U, 0},
        NativePlaybackBankIntent{1U, false, {}, 0U, 0},
    };

    require(
        runtime.publishCommand(unselectedCommand(1U, 1U, banks, controls(1U))) ==
            NativePlaybackPublishStatus::Accepted,
        "unselected 0/0 fence pair accepted");

    const SongRenderOwnership owner{11U, 2U};
    banks[0] = assignedBank(2U, owner);
    SongRenderControlSnapshot selected = controls(2U);
    selected.sourceIntentRevision = 7U;
    selected.stemIntentRevision = 9U;
    require(
        runtime.publishCommand(selectedCommand(
            2U,
            NativePlaybackBank::First,
            owner,
            banks,
            selected,
            false)) == NativePlaybackPublishStatus::Accepted,
        "selected positive/positive fence pair accepted");

    auto unboundedBanks = banks;
    unboundedBanks[0].cacheFrameUpperBound = 0U;
    auto boundedControls = selected;
    boundedControls.revision = 3U;
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, owner,
        unboundedBanks, boundedControls, false)) == NativePlaybackPublishStatus::InvalidSnapshot,
        "an assigned bank without its explicit cache frame bound is rejected");

    SongRenderControlSnapshot selectedZero = controls(3U);
    selectedZero.sourceIntentRevision = 0U;
    selectedZero.stemIntentRevision = 0U;
    require(
        runtime.publishCommand(selectedCommand(
            3U, NativePlaybackBank::First, owner, banks, selectedZero, false)) ==
            NativePlaybackPublishStatus::InvalidSnapshot,
        "selected 0/0 fence pair rejected");

    SongRenderControlSnapshot selectedSourceOnly = controls(4U);
    selectedSourceOnly.stemIntentRevision = 0U;
    require(
        runtime.publishCommand(selectedCommand(
            4U, NativePlaybackBank::First, owner, banks, selectedSourceOnly, false)) ==
            NativePlaybackPublishStatus::InvalidSnapshot,
        "selected positive/zero fence pair rejected");

    SongRenderControlSnapshot selectedStemOnly = controls(5U);
    selectedStemOnly.sourceIntentRevision = 0U;
    require(
        runtime.publishCommand(selectedCommand(
            5U, NativePlaybackBank::First, owner, banks, selectedStemOnly, false)) ==
            NativePlaybackPublishStatus::InvalidSnapshot,
        "selected zero/positive fence pair rejected");

    NativePlaybackCommandSnapshot unselectedPositive =
        unselectedCommand(6U, 3U, banks, controls(6U));
    unselectedPositive.controls.sourceIntentRevision = 7U;
    unselectedPositive.controls.stemIntentRevision = 9U;
    require(
        runtime.publishCommand(unselectedPositive) ==
            NativePlaybackPublishStatus::InvalidSnapshot,
        "unselected positive/positive fence pair rejected");

    NativePlaybackCommandSnapshot unselectedMixed =
        unselectedCommand(7U, 3U, banks, controls(7U));
    unselectedMixed.controls.sourceIntentRevision = 7U;
    require(
        runtime.publishCommand(unselectedMixed) ==
            NativePlaybackPublishStatus::InvalidSnapshot,
        "unselected mixed fence pair rejected");

    NativePlaybackCommandSnapshot playingWithoutSelection =
        unselectedCommand(8U, 3U, banks, controls(8U));
    playingWithoutSelection.playing = true;
    require(
        runtime.publishCommand(playingWithoutSelection) ==
            NativePlaybackPublishStatus::InvalidSnapshot,
        "playing without a selection rejected");

    require(
        runtime.publishCommand(unselectedCommand(9U, 3U, banks, controls(9U))) ==
            NativePlaybackPublishStatus::Accepted,
        "selected owner can retire through an exact unselected 0/0 epoch");
    const NativePlaybackRuntimeDiagnostics diagnostics = runtime.diagnostics();
    require(diagnostics.commandSnapshotsPublished == 3U,
            "only three valid matrix commands published");
    require(diagnostics.rejectedCommandSnapshots == 7U,
            "all seven malformed matrix commands rejected");
}

void latestOfFourHundredSelectionsWinsAndCommitsOnlyOnPresentation() {
    SongLaneIngressBank first(8'192, 480);
    SongLaneIngressBank second(8'192, 480);
    constexpr std::uint64_t finalGeneration = 401;
    configureLanes(first, finalGeneration - 1U, 1);
    configureLanes(second, finalGeneration, 1);
    offerLane(second, 0, finalGeneration, 0, 2'400);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());

    std::array<NativePlaybackBankIntent, 2> banks{};
    for (std::uint64_t index = 0; index < 400; ++index) {
        const std::uint64_t generation = index + 2U;
        const std::uint64_t token = 10'000U + index;
        const std::size_t selected = static_cast<std::size_t>(index & 1U);
        banks[selected] = assignedBank(index + 1U, {token, generation});
        const auto command = selectedCommand(
            index + 1U,
            selected == 0U ? NativePlaybackBank::First : NativePlaybackBank::Second,
            {token, generation},
            banks,
            controls(index + 1U));
        require(
            runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
            "rapid command accepted");
    }
    require(runtime.start(), "rapid runtime start");
    waitUntil(
        [&] { return runtime.diagnostics().acceptedOwnership.generation == finalGeneration; },
        "final rapid selection accepted");
    waitUntil([&] { return output.commitCount() == 1U; }, "final rapid selection staged commit");
    auto diagnostic = runtime.diagnostics();
    require(diagnostic.commandSnapshotsPublished == 400, "400 commands published");
    require(diagnostic.commandSnapshotsConsumed == 1, "only latest command consumed");
    require(diagnostic.commandSnapshotsSuperseded == 399, "399 commands superseded");
    require(diagnostic.committedOwnership.generation == 0, "not committed before callback");
    require(output.flushCount() == 0, "selection never hard-flushed");
    require(output.beginCount() == 1, "only final generation staged");
    require(output.commitCount() == 1, "only final generation committed");

    require(output.presentFirstCurrentRecord(), "present final selection");
    waitUntil(
        [&] { return runtime.diagnostics().committedOwnership.generation == finalGeneration; },
        "final selection committed");
    diagnostic = runtime.diagnostics();
    require(diagnostic.committedOwnership.selectionToken == 10'399U, "final token committed");
    require(diagnostic.acceptedFirstFingerprintSequence != 0, "fingerprint evidence exists");
    waitUntil(
        [&] { return runtime.diagnostics().presentedPosition.generation == finalGeneration; },
        "callback-authoritative position published");
    for (std::size_t index = 0; index < output.recordCount(); ++index) {
        const auto record = output.record(index);
        require(record.generation == finalGeneration, "no stale rapid generation enqueued");
        require(record.token == 10'399U, "no stale rapid token enqueued");
    }
    const std::size_t recordsBeforeStop = output.recordCount();
    stopAndJoin(runtime);
    require(output.recordCount() == recordsBeforeStop, "no output after join");
}

void bothBanksPrewarmAndFastSwitchWithAllControls() {
    SongLaneIngressBank first(16'384, 480);
    SongLaneIngressBank second(16'384, 480);
    configureLanes(first, 2);
    configureLanes(second, 3);
    offerAllLanes(first, 2, 2'880);
    offerAllLanes(second, 3, 2'880);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    const std::uint64_t processInvalidRenderBaseline =
        runtime.diagnostics().processOutputInvalidRenderResults;
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, {100, 2}),
        assignedBank(1, {200, 3}),
    };
    auto firstControls = controls(1, 0.5F, -12.0F, MixerSourceMode::Stems);
    firstControls.mixer.metronome.mode = MetronomeMode::FreeBpm;
    firstControls.mixer.metronome.bpm = 240.0F;
    firstControls.mixer.metronome.gain = 1.0F;
    firstControls.mixer.stems[0].muted = true;
    firstControls.mixer.stems[1].soloed = true;
    require(
        runtime.publishCommand(selectedCommand(
            1,
            NativePlaybackBank::First,
            {100, 2},
            banks,
            firstControls)) == NativePlaybackPublishStatus::Accepted,
        "initial two-bank command");
    const std::array<std::int64_t, 5> beatGrid{{0, 480, 960, 1'440, 1'920}};
    const std::array<std::uint8_t, 5> beatAccents{{1,0,0,0,1}};
    require(
        runtime.publishBeatGrid(
            {1, NativePlaybackBank::Second, {200, 3}, beatGrid.size()},
            beatGrid.data(), beatAccents.data()) == NativePlaybackPublishStatus::Accepted,
        "candidate beat grid staged");
    require(runtime.start(), "two-bank start");
    waitUntil(
        [&] {
            const auto diagnostic = runtime.diagnostics();
            for (std::size_t lane = 0; lane < kLaneCount; ++lane) {
                if (diagnostic.banks[1].lanes[lane].appendedFrames == 0U) return false;
            }
            return diagnostic.banks[1].beatGridUpdates == 1U;
        },
        "candidate all lanes prewarmed");
    const auto beforeEmptyGrid = runtime.diagnostics().renderCycles;
    require(runtime.publishBeatGrid({1U, NativePlaybackBank::First, {100U, 2U}, 0U},
        nullptr, nullptr) == NativePlaybackPublishStatus::Accepted, "empty native grid accepted");
    waitForRenderProgress(runtime, beforeEmptyGrid, "empty grid consumed", 4U);
    require(runtime.diagnostics().banks[0].beatGridClears == 0U,
        "empty grid no-op reset the existing grid scheduler");
    const auto beforeSameGrid = runtime.diagnostics().renderCycles;
    require(runtime.publishBeatGrid({2U, NativePlaybackBank::Second, {200U, 3U}, beatGrid.size()},
        beatGrid.data(), beatAccents.data()) == NativePlaybackPublishStatus::Accepted,
        "identical native grid accepted");
    waitForRenderProgress(runtime, beforeSameGrid, "identical grid consumed", 4U);
    require(runtime.diagnostics().banks[1].beatGridUpdates == 1U,
        "identical native grid manufactured a new grid application");
    auto changedAccents = beatAccents;
    changedAccents[1] = 1U;
    require(runtime.publishBeatGrid({3U, NativePlaybackBank::Second, {200U, 3U}, beatGrid.size()},
        beatGrid.data(), changedAccents.data()) == NativePlaybackPublishStatus::Accepted,
        "changed native accent content accepted");
    waitUntil([&] { return runtime.diagnostics().banks[1].beatGridUpdates == 2U; },
        "changed accent content not applied");

    waitUntil([&] { return output.commitCount() == 1U; }, "initial owner staged commit");
    require(output.presentFirstCurrentRecord(), "initial owner presented before fast switch");
    waitUntil([&] { return runtime.diagnostics().committedOwnership.generation == 2U; },
        "initial owner committed");

    auto secondControls = controls(2, 2.0F, 12.0F, MixerSourceMode::Original);
    secondControls.mixer.metronome.mode = MetronomeMode::BeatGrid;
    require(
        runtime.publishCommand(selectedCommand(
            2,
            NativePlaybackBank::Second,
            {200, 3},
            banks,
            secondControls)) == NativePlaybackPublishStatus::Accepted,
        "candidate selected");
    waitUntil(
        [&] { return runtime.diagnostics().acceptedOwnership.generation == 3U; },
        "candidate accepted immediately");
    waitUntil([&] { return output.commitCount() == 2U; }, "candidate staged commit");
    require(output.presentFirstCurrentRecord(), "candidate presented");
    waitUntil(
        [&] { return runtime.diagnostics().committedOwnership.generation == 3U; },
        "candidate committed");
    require(runtime.diagnostics().outputInvalidRenderResults == 0U, "valid tuned render results");
    require(
        runtime.diagnostics().processOutputInvalidRenderResults ==
            processInvalidRenderBaseline,
        "valid tuned rendering does not advance the process invalid-result ledger");
    stopAndJoin(runtime);
}

void recoveredRuntimeRendersReplayedAccentedBeatGrid(bool paused) {
    constexpr SongRenderOwnership original{27'001U, 2U}, recovered{27'001U, 3U};
    SongLaneIngressBank first(8'192U, 480U), second(8'192U, 480U);
    configureLanes(first, original.generation, 1U);
    const std::vector<float> silence(4'096U * 2U, 0.0F);
    require(first.lane(0U)->offer(LaneInputBlock{silence.data(), 4'096U,
        LaneInputFormat{kSampleRate, 2, LanePcmEncoding::Float32}, original.generation, 0U})
        .status == LaneOfferStatus::Accepted, "route grid silent Original accepted");
    require(first.lane(0U)->markEndOfStream(original.generation, 4'096U).status ==
        LaneEndOfStreamStatus::Accepted, "route grid Original sealed");
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime previous(runtimeConfiguration(), first, second, output.port());
    auto bank = assignedBank(1U, original, 0U, 0U);
    bank.stemPackageHash = 0U;
    bank.cacheFrameUpperBound = 4'096U;
    std::array<NativePlaybackBankIntent, 2U> banks{bank, NativePlaybackBankIntent{}};
    auto metronome = controls(1U);
    metronome.mixer.metronome.mode = MetronomeMode::BeatGrid;
    metronome.mixer.metronome.gain = .5F;
    const std::array<std::int64_t, 3U> frames{0, 960, 1'920};
    const std::array<std::uint8_t, 3U> accents{1, 0, 1};
    require(previous.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        original, banks, metronome, false)) == NativePlaybackPublishStatus::Accepted &&
        previous.publishBeatGrid({1U, NativePlaybackBank::First, original, frames.size()},
            frames.data(), accents.data()) == NativePlaybackPublishStatus::Accepted &&
        previous.start(), "route grid initial owner prepared");
    waitUntil([&] { return previous.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 1U &&
        previous.diagnostics().banks[0U].beatGridUpdates == 1U; }, "initial grid and silent cache installed");
    stopAndJoin(previous);

    NativePlaybackRuntime replacement(runtimeConfiguration(), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&] { stopAndJoin(replacement); });
    require(replacement.inheritCompletedSongCache(previous), "route grid cache transferred");
    const auto priorPlayAttempts = output.playAttempts.load(std::memory_order_acquire);
    banks[0U].revision = 2U;
    banks[0U].ownership = recovered;
    banks[0U].firstTimelineFrame = 1'920U;
    require(replacement.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        recovered, banks, metronome, !paused)) == NativePlaybackPublishStatus::Accepted &&
        replacement.publishBeatGrid({2U, NativePlaybackBank::First, recovered, frames.size()},
            frames.data(), accents.data()) == NativePlaybackPublishStatus::Accepted &&
        replacement.start(), "new runtime receives exact-owner grid replay");
    waitUntil([&] { return replacement.diagnostics().banks[0U].beatGridUpdates == 1U; },
        "replayed grid applied to replacement bank");
    if (paused) {
        require(output.playAttempts.load(std::memory_order_acquire) == priorPlayAttempts,
            "paused recovery cannot start physical output");
        require(replacement.publishCommand(selectedCommand(2U, NativePlaybackBank::First,
            recovered, banks, metronome, true)) == NativePlaybackPublishStatus::Accepted,
            "paused recovery resumes without changing metronome controls");
    }
    waitUntil([&] { return output.playAttempts.load(std::memory_order_acquire) > priorPlayAttempts; },
        "recovered metronome playback starts physical output");
    static_cast<void>(waitForRecord(output, 0U, [recovered](const FakeOutputRecord &record) {
        return record.token == recovered.selectionToken && record.generation == recovered.generation &&
            record.sourceStart == 1'920.0 && record.peak > .01F;
    }, "replayed authored beat produces actual click PCM over silent Original"));
}

void metronomeAndBeatGridSchemaRejectInvalidValuesWithoutClamping() {
    SongLaneIngressBank first(4'096, 480);
    SongLaneIngressBank second(4'096, 480);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    const SongRenderOwnership owner{205, 2};
    const std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, owner), NativePlaybackBankIntent{}};

    auto badDenominator = controls(1);
    badDenominator.mixer.metronome.beatUnitDenominator = 3;
    require(
        runtime.publishCommand(selectedCommand(
            1,
            NativePlaybackBank::First,
            owner,
            banks,
            badDenominator)) == NativePlaybackPublishStatus::InvalidSnapshot,
        "invalid beat-unit denominator rejected");

    auto badMeter = controls(1);
    badMeter.mixer.metronome.beatsPerBar = 33;
    require(
        runtime.publishCommand(selectedCommand(
            1,
            NativePlaybackBank::First,
            owner,
            banks,
            badMeter)) == NativePlaybackPublishStatus::InvalidSnapshot,
        "meter above 32 rejected");

    auto belowProviderTempo = controls(1);
    belowProviderTempo.tempo = 0.499F;
    require(
        runtime.publishCommand(selectedCommand(
            1,
            NativePlaybackBank::First,
            owner,
            banks,
            belowProviderTempo)) == NativePlaybackPublishStatus::InvalidSnapshot,
        "tempo below 0.5x rejected at native command boundary");

    auto aboveProviderTempo = controls(1);
    aboveProviderTempo.tempo = 2.001F;
    require(
        runtime.publishCommand(selectedCommand(
            1,
            NativePlaybackBank::First,
            owner,
            banks,
            aboveProviderTempo)) == NativePlaybackPublishStatus::InvalidSnapshot,
        "tempo above 2x rejected at native command boundary");

    auto maximumMeter = controls(1);
    maximumMeter.mixer.metronome.beatsPerBar = 32;
    maximumMeter.mixer.metronome.beatUnitDenominator = 32;
    require(
        runtime.publishCommand(selectedCommand(
            1,
            NativePlaybackBank::First,
            owner,
            banks,
            maximumMeter)) == NativePlaybackPublishStatus::Accepted,
        "meter 32 and denominator 32 accepted exactly");

    const std::array<std::int64_t, 3> frames{{0, 12'000, 24'000}};
    const std::array<std::uint8_t, 3> invalidAccents{{0, 2, 0}};
    const NativePlaybackBeatGridUpdate explicitUpdate{
        1, NativePlaybackBank::First, owner, frames.size()};
    require(
        runtime.publishBeatGrid(explicitUpdate, frames.data(), invalidAccents.data()) ==
            NativePlaybackPublishStatus::InvalidSnapshot,
        "non-Boolean accent rejected");
    require(
        runtime.publishBeatGrid(explicitUpdate, frames.data(), nullptr) ==
            NativePlaybackPublishStatus::InvalidSnapshot,
        "missing explicit accents rejected");

    const std::array<std::uint8_t, 3> accents{{0, 1, 0}};
    require(
        runtime.publishBeatGrid(explicitUpdate, frames.data(), accents.data()) ==
            NativePlaybackPublishStatus::Accepted,
        "explicit pickup accents accepted");
    require(
        runtime.publishBeatGrid(explicitUpdate, frames.data(), accents.data()) ==
            NativePlaybackPublishStatus::StaleRevision,
        "duplicate beat-grid revision rejected as stale");
}

void selectedOwnerAfterAcceptedEmptyEpochMustAdvanceProducerHighWater() {
    SongLaneIngressBank first(256, 64);
    SongLaneIngressBank second(256, 64);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());

    const SongRenderOwnership outgoing{901U, 2U};
    const SongRenderOwnership retainedPrewarm{902U, 3U};
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, outgoing),
        assignedBank(1U, retainedPrewarm),
    };
    require(
        runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            outgoing,
            banks,
            controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "initial selected producer epoch accepted");

    banks[0] = NativePlaybackBankIntent{2U, false, {}, 0U, 0};
    require(
        runtime.publishCommand(unselectedCommand(
            2U,
            4U,
            banks,
            controls(2U))) == NativePlaybackPublishStatus::Accepted,
        "newer empty producer epoch accepted while prewarm remains assigned");

    require(
        runtime.publishCommand(selectedCommand(
            3U,
            NativePlaybackBank::Second,
            retainedPrewarm,
            banks,
            controls(3U))) == NativePlaybackPublishStatus::InvalidSnapshot,
        "selected owner cannot roll back behind an accepted empty producer epoch");

    const SongRenderOwnership rebased{902U, 5U};
    banks[1] = assignedBank(2U, rebased);
    require(
        runtime.publishCommand(selectedCommand(
            4U,
            NativePlaybackBank::Second,
            rebased,
            banks,
            controls(4U))) == NativePlaybackPublishStatus::Accepted,
        "rebased selected owner advances beyond the accepted empty producer epoch");
    const NativePlaybackRuntimeDiagnostics diagnostics = runtime.diagnostics();
    require(diagnostics.commandSnapshotsPublished == 3U,
        "only valid producer epochs are published");
    require(diagnostics.rejectedCommandSnapshots == 1U,
        "the one rollback attempt is counted exactly");
}

void automaticSuccessorCommandContractRejectsMalformedFences() {
    SongLaneIngressBank first(4'096, 480);
    SongLaneIngressBank second(4'096, 480);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    const SongRenderOwnership outgoing{310U, 20U};
    const SongRenderOwnership successor{320U, 21U};
    const std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, outgoing), assignedBank(2U, successor)};
    const NativePlaybackAutomaticSuccessorIntent exactIntent{
        true, false, 9U, outgoing, NativePlaybackBank::Second, successor};

    auto exact = selectedCommand(
        1U, NativePlaybackBank::First, outgoing, banks, controls(1U));
    exact.automaticSuccessor = exactIntent;
    require(runtime.publishCommand(exact) == NativePlaybackPublishStatus::Accepted,
        "exact automatic-successor fence accepted");

    auto paused = selectedCommand(
        2U, NativePlaybackBank::First, outgoing, banks, controls(2U), false);
    paused.automaticSuccessor = exactIntent;
    require(runtime.publishCommand(paused) == NativePlaybackPublishStatus::InvalidSnapshot,
        "paused automatic successor rejected");

    auto selectedAsSuccessor = selectedCommand(
        2U, NativePlaybackBank::First, outgoing, banks, controls(2U));
    selectedAsSuccessor.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
        true, false, 10U, outgoing, NativePlaybackBank::First, successor};
    require(
        runtime.publishCommand(selectedAsSuccessor) ==
            NativePlaybackPublishStatus::InvalidSnapshot,
        "selected bank cannot also be the successor bank");

    auto wrongOutgoing = selectedCommand(
        2U, NativePlaybackBank::First, outgoing, banks, controls(2U));
    wrongOutgoing.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
        true, false, 10U, SongRenderOwnership{311U, 20U}, NativePlaybackBank::Second, successor};
    require(runtime.publishCommand(wrongOutgoing) == NativePlaybackPublishStatus::InvalidSnapshot,
        "automatic successor rejects a non-selected outgoing owner");

    auto staleSuccessor = selectedCommand(
        2U,
        NativePlaybackBank::First,
        outgoing,
        std::array<NativePlaybackBankIntent, 2>{
            assignedBank(1U, outgoing), assignedBank(2U, {321U, 20U})},
        controls(2U));
    staleSuccessor.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
        true, false, 10U, outgoing, NativePlaybackBank::Second, SongRenderOwnership{321U, 20U}};
    require(
        runtime.publishCommand(staleSuccessor) == NativePlaybackPublishStatus::InvalidSnapshot,
        "automatic successor generation must be newer than outgoing");

    auto mismatchedBank = selectedCommand(
        2U,
        NativePlaybackBank::First,
        outgoing,
        std::array<NativePlaybackBankIntent, 2>{
            assignedBank(1U, outgoing), assignedBank(2U, {322U, 21U})},
        controls(2U));
    mismatchedBank.automaticSuccessor = exactIntent;
    require(runtime.publishCommand(mismatchedBank) == NativePlaybackPublishStatus::InvalidSnapshot,
        "automatic successor must equal the spare bank's exact owner");


}

void emptyEndedSuccessorCannotSuppressOutgoingEndForever() {
    SongLaneIngressBank first(4'096U, 64U);
    SongLaneIngressBank second(4'096U, 64U);
    configureLanes(first, 2U, 1U);
    configureLanes(second, 3U, 1U);
    offerLane(first, 0U, 2U, 0U, 150U);
    offerLane(second, 0U, 3U, 0U, 0U);
    FakeOutput output(4'096U, 64);
    NativePlaybackRuntime runtime(runtimeConfiguration(4'096U, 1U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&]() noexcept {
        runtime.requestStop();
        runtime.join();
    });
    const SongRenderOwnership outgoing{910U, 2U};
    const SongRenderOwnership successor{920U, 3U};
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, outgoing), assignedBank(1U, successor)};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        outgoing, banks, controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "empty successor outgoing accepted");
    require(runtime.start(), "empty successor runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "outgoing committed");
    require(output.presentFirstCurrentRecord(), "outgoing presented");
    waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, outgoing); },
        "outgoing presentation observed");
    auto automatic = selectedCommand(2U, NativePlaybackBank::First, outgoing, banks, controls(2U));
    automatic.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
        true, false, 90U, outgoing, NativePlaybackBank::Second, successor};
    require(runtime.publishCommand(automatic) == NativePlaybackPublishStatus::Accepted,
        "empty successor intent accepted");
    waitUntil([&] { return runtime.diagnostics().automaticSuccessor.successorEndObserved; },
        "runtime knows successor has ended without PCM");
    // Publish the actual outgoing terminal tuple; consumeCallback only models successor PCM.
    output.consumeAll();
    waitUntil([&] { return runtime.diagnostics().audibleEnd.sequence == 1U; },
        "empty ended successor releases exact outgoing terminal ownership");
    require(runtime.diagnostics().audibleEnd.sequence != 0U,
        "ended empty successor must return outgoing EOS to queue policy instead of silent deadlock");
}

void prewarmedSuccessorEosMustSealPromotedOriginalCache() {
    SongLaneIngressBank first(4'096U, 64U);
    SongLaneIngressBank second(4'096U, 64U);
    configureLanes(first, 2U);
    configureLanes(second, 3U);
    offerAllLanes(first, 2U, 150U);
    offerLane(second, 0U, 3U, 0U, 1'600U);
    FakeOutput output(4'096U, 64);
    NativePlaybackRuntime runtime(runtimeConfiguration(4'096U, 1U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&]() noexcept {
        runtime.requestStop();
        runtime.join();
    });
    const SongRenderOwnership outgoing{930U, 2U};
    const SongRenderOwnership successor{940U, 3U};
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, outgoing), assignedBank(1U, successor)};
    auto outgoingControls = controls(1U, 1.0F, 0.0F, MixerSourceMode::Stems);
    outgoingControls.sourceIntentRevision = 3U;
    outgoingControls.stemIntentRevision = 3U;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        outgoing, banks, outgoingControls)) == NativePlaybackPublishStatus::Accepted,
        "prewarm EOS outgoing accepted");
    require(runtime.start(), "prewarm EOS runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "outgoing committed");
    require(output.presentFirstCurrentRecord(), "outgoing presented");
    waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, outgoing); },
        "outgoing presentation observed");
    outgoingControls.revision = 2U;
    auto automatic = selectedCommand(2U, NativePlaybackBank::First, outgoing, banks, outgoingControls);
    automatic.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
        true, false, 91U, outgoing, NativePlaybackBank::Second, successor};
    require(runtime.publishCommand(automatic) == NativePlaybackPublishStatus::Accepted,
        "prewarm EOS successor accepted");
    waitUntil([&] { return output.drainCommitCount() == 1U; }, "successor ready");
    static_cast<void>(output.consumeCallback(192U));
    waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, successor); },
        "successor committed");
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::Second,
        successor, banks, controls(3U))) == NativePlaybackPublishStatus::Accepted,
        "successor controller adoption accepted");
    waitUntil([&] { return runtime.diagnostics().commandSnapshotsConsumed >= 3U; },
        "successor adoption consumed");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner; },
        "successor cache binding observed");
    const auto cache = runtime.renderReadinessDiagnostics();
    std::cerr << "prewarm-EOS cache evidence: bound=" << cache.currentSongCacheBoundToSelectedOwner
        << " completeMask=" << cache.currentSongCacheCompleteLaneMask << '\n';
    require((cache.currentSongCacheCompleteLaneMask & 1U) != 0U,
        "already-decoded successor Original EOS must seal its new cache so Stems can drain");
    for (std::size_t lane = 1U; lane < kLaneCount; ++lane) {
        offerLane(second, lane, successor.generation, 0U, 1'600U);
    }
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 127U; },
        "all six stems drain and seal after the prewarmed Original cache is adopted");
    waitUntil([&] {
        NativeAudioLateMixControl applied{};
        return output.lateControlForOwner(successor, applied) &&
            applied.canonicalControlRevision == 3U && applied.sourceIntentRevision == 1U &&
            applied.stemIntentRevision == 1U;
    }, "successor accepts its own initial intent revisions after outgoing Stems edits");
}

void tunedSuccessorMakesIncrementalProgressForDynamicParts() {
    for (const std::uint32_t parts : std::array<std::uint32_t, 2U>{6U, static_cast<std::uint32_t>(stemwise::audio::kStemLaneCount)}) {
        SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
        configureLanes(first, 2U, 1U);
        configureLanes(second, 3U, parts + 1U);
        offerLane(first, 0U, 2U, 0U, 150U);
        offerAllLanes(second, 3U, 16'384U, parts + 1U);
        FakeOutput output(4'096U, 64U);
        auto configuration = runtimeConfiguration(32'768U, 64U);
        configuration.maximumOutputBlocksPerCycle = 32U;
        NativePlaybackRuntime runtime(configuration, first, second, output.port());
        const auto cleanup = onScopeExit([&]() noexcept { runtime.requestStop(); runtime.join(); });
        const SongRenderOwnership outgoing{9'100U + parts, 2U}, successor{9'200U + parts, 3U};
        const std::array<NativePlaybackBankIntent, 2U> banks{
            assignedBank(1U, outgoing), assignedBank(1U, successor, 0U, parts)};
        const auto tune = controls(1U, 0.75F, 3.0F, MixerSourceMode::Stems);
        require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
            outgoing, banks, tune)) == NativePlaybackPublishStatus::Accepted, "tuned successor initial command");
        require(runtime.start(), "tuned successor runtime start");
        waitUntil([&] { return output.presentFirstCurrentRecord(); }, "tuned successor outgoing presented");
        waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, outgoing); },
            "tuned successor outgoing owner committed");
        auto command = selectedCommand(2U, NativePlaybackBank::First, outgoing, banks, tune);
        command.automaticSuccessor = {true, true, 100U, outgoing, NativePlaybackBank::Second, successor};
        require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted, "inherited tuning successor intent");
        try {
            waitUntil([&] { return output.drainCommitCount() == 1U; }, "incrementally tuned successor has ready PCM", 8'000U);
        } catch (...) {
            const auto failed = runtime.diagnostics();
            std::cerr << "tuned successor parts=" << parts << " phase=" << static_cast<unsigned>(failed.automaticSuccessor.phase)
                << " queued=" << failed.automaticSuccessor.successorQueuedFrames
                << " fingerprint=" << failed.automaticSuccessor.firstFingerprintSequence
                << " need=" << failed.outputNeedDataEvents << " invalid=" << failed.outputInvalidRenderResults
                << " eos=" << failed.outputEndOfStreamEvents;
            for (std::size_t bank = 0U; bank < 2U; ++bank) {
                std::cerr << " bank" << bank << "{revision=" << failed.banks[bank].appliedIntentRevision
                    << ",Original=" << failed.banks[bank].lanes[0].appendedFrames
                    << ",OriginalEos=" << failed.banks[bank].lanes[0].endOfStreamMarks
                    << ",lastPart=" << failed.banks[bank].lanes[bank == 0U ? 0U : parts].appendedFrames << '}';
            }
            std::cerr << '\n';
            throw;
        }
        const auto diagnostics = runtime.diagnostics();
        require(diagnostics.outputInvalidRenderResults == 0U && diagnostics.automaticSuccessor.firstFingerprintSequence != 0U &&
            diagnostics.automaticSuccessor.successorQueuedFrames != 0U,
            "incremental priming must retain exact ready successor ownership");
        for (std::size_t index = 0U; index < output.recordCount(); ++index) {
            const auto record = output.record(index);
            if (record.token == outgoing.selectionToken && record.generation == outgoing.generation)
                require(record.sourceEnd <= 150.0, "asynchronous cache seal rendered outgoing PCM past decoder EOS");
        }
    }
}

void automaticSuccessorFillsExactEosCallbackWithoutHole(bool trimmed = false) {
    const std::uint64_t successorStart = trimmed ? 48'144U : 0U;
    SongLaneIngressBank first(4'096, 64);
    SongLaneIngressBank second(4'096, 64);
    configureLanes(first, 2U, 1U);
    configureLanes(second, 3U, 1U);
    offerLane(first, 0U, 2U, 0U, 150U);
    offerLane(second, 0U, 3U, successorStart, 3'071U, false);
    FakeOutput output(4'096U, 64);
    NativePlaybackRuntimeConfiguration configuration = runtimeConfiguration(4'096U, 1U);
    configuration.minimumOutputReserveMicros =
        NativePlaybackRuntimeConfiguration::kDefaultOutputReserveMicros;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    [[maybe_unused]] const auto runtimeCleanup = onScopeExit([&]() noexcept {
        runtime.requestStop();
        runtime.join();
    });
    const SongRenderOwnership outgoing{410U, 2U};
    const SongRenderOwnership successor{420U, 3U};
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, outgoing), assignedBank(1U, successor, successorStart)};
    if (trimmed) banks[1].playbackEndFrameExclusive = successorStart + 3'072U;

    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        outgoing,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "automatic boundary outgoing command accepted");
    require(runtime.start(), "automatic boundary runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "automatic boundary outgoing stage committed");
    require(output.presentFirstCurrentRecord(),
        "automatic boundary outgoing presentation published");
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, outgoing);
    }, "automatic boundary outgoing owner committed");

    auto automatic = selectedCommand(
        2U,
        NativePlaybackBank::First,
        outgoing,
        banks,
        controls(2U));
    automatic.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
        true,
        false,
        77U,
        outgoing,
        NativePlaybackBank::Second,
        successor};
    output.setDrainCommitBlocked(true);
    [[maybe_unused]] const auto drainCommitCleanup = onScopeExit([&]() noexcept {
        output.setDrainCommitBlocked(false);
    });
    require(runtime.publishCommand(automatic) == NativePlaybackPublishStatus::Accepted,
        "automatic successor command accepted");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics().automaticSuccessor;
        return diagnostic.phase == NativePlaybackAutomaticSuccessorPhase::Staging &&
            sameOwnership(diagnostic.outgoingOwnership, outgoing) &&
            sameOwnership(diagnostic.successorOwnership, successor) &&
            diagnostic.successorQueuedFrames == 3'071U &&
            diagnostic.firstFingerprintSequence != 0U &&
            !diagnostic.successorEndObserved;
    }, "one-frame-short production successor remains staged");
    require(output.drainCommitCount() == 0U && !output.drainCommitEntered(),
        "3071 successor frames cannot commit the 3072-frame handoff reserve");
    offerLane(second, 0U, 3U, successorStart + 3'071U, 1U, false);
    waitUntil([&] { return output.drainCommitEntered(); },
        "automatic successor worker enters the blocked output commit");
    const NativePlaybackRuntimeDiagnostics beforeOutputCommit = runtime.diagnostics();
    const std::uint64_t drainBeginsBeforeRelease = output.drainBeginCount();
    const std::uint64_t outputCommitsBeforeRelease = output.drainCommitCount();
    output.setDrainCommitBlocked(false);
    require(drainBeginsBeforeRelease == 1U,
        "automatic successor drain stage opened");
    require(
        beforeOutputCommit.automaticSuccessor.phase ==
            NativePlaybackAutomaticSuccessorPhase::DrainCommitted &&
            sameOwnership(
                beforeOutputCommit.automaticSuccessor.outgoingOwnership,
                outgoing) &&
            sameOwnership(
                beforeOutputCommit.automaticSuccessor.successorOwnership,
                successor) &&
            beforeOutputCommit.automaticSuccessor.successorQueuedFrames == 3'072U,
        "runtime publishes the exact committed-drain fence before exposing output commit");
    require(outputCommitsBeforeRelease == 0U,
        "blocked output commit was not visible before the runtime fence");
    waitUntil([&] { return output.drainCommitCount() == 1U; },
        "automatic successor drain committed after exact outgoing EOS");
    bool sawOutgoingSourceEos = false;
    for (std::size_t index = 0U; index < output.recordCount(); ++index) {
        const FakeOutputRecord record = output.record(index);
        if (record.generation != outgoing.generation || !record.endOfStreamAfterBlock) continue;
        sawOutgoingSourceEos = true;
    }
    require(sawOutgoingSourceEos,
        "automatic successor test reaches one exact outgoing source EOS marker");
    waitUntil([&] { return output.terminalSilenceAuthorization() == (trimmed ? successor.generation : 0U); },
        "an expected successor revokes outgoing terminal silence; only its exact known end may authorize successor silence");
    for (std::size_t index = 0U; index < output.recordCount(); ++index) {
        const auto record = output.record(index);
        if (record.token != successor.selectionToken) continue;
        require(record.sourceStart >= static_cast<double>(successorStart) &&
            record.sourceEnd <= static_cast<double>(successorStart + 3'072U),
            "automatic successor must retain its exact trimmed source boundaries");
        const auto expected = tone(record.frames, static_cast<std::uint64_t>(record.sourceStart), .025F, 220.0);
        std::uint64_t expectedHash = 14695981039346656037ULL;
        for (const float sample : expected) {
            expectedHash ^= std::bit_cast<std::uint32_t>(sample);
            expectedHash *= 1099511628211ULL;
        }
        require(record.originalPcmHash == expectedHash,
            "trimmed successor PCM must correspond to its absolute source samples");
    }

    const FakeOutput::CallbackResult firstCallback = output.consumeCallback(64U);
    const FakeOutput::CallbackResult secondCallback = output.consumeCallback(64U);
    const FakeOutput::CallbackResult boundaryCallback = output.consumeCallback(64U);
    require(firstCallback.outgoingFrames == 64U && firstCallback.zeroFrames == 0U,
        "first outgoing callback is full");
    require(secondCallback.outgoingFrames == 64U && secondCallback.zeroFrames == 0U,
        "second outgoing callback is full");
    require(boundaryCallback.outgoingFrames == 22U &&
            boundaryCallback.successorFrames == 42U &&
            boundaryCallback.zeroFrames == 0U,
        "EOS callback preserves outgoing tail and fills successor suffix without a hole");

    waitUntil([&] {
        const NativePlaybackRuntimeDiagnostics diagnostic = runtime.diagnostics();
        return sameOwnership(diagnostic.requestedOwnership, successor) &&
            sameOwnership(diagnostic.acceptedOwnership, successor) &&
            sameOwnership(diagnostic.committedOwnership, successor);
    }, "automatic successor becomes exact runtime owner on callback evidence");
    require(runtime.diagnostics().audibleEnd.sequence == 0U,
        "automatic boundary suppresses duplicate outgoing audible end");

    require(runtime.publishCommand(selectedCommand(
        3U,
        NativePlaybackBank::Second,
        successor,
        banks,
        controls(3U))) == NativePlaybackPublishStatus::Accepted,
        "controller successor selection accepted after callback proof");
    waitUntil([&] {
        return runtime.diagnostics().commandSnapshotsConsumed >= 3U;
    }, "controller successor selection consumed");
    require(output.beginCount() == 1U,
        "callback-promoted successor does not incur another immediate handoff");
    stopAndJoin(runtime);
}

void rejectedDirectLoopDemandIsTransactionalAndRetryable() {
    constexpr SongRenderOwnership owner{425U, 2U};
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
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
    [[maybe_unused]] const auto cleanup = onScopeExit([&]() noexcept {
        runtime.requestStop();
        runtime.join();
    });
    const std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "transactional direct-loop owner selected");
    require(runtime.start(), "transactional direct-loop runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "transactional direct-loop owner committed");
    require(output.presentFirstCurrentRecord(),
        "transactional direct-loop owner presented");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == allLaneMask;
    }, "transactional direct-loop cache ready", 8'000U);

    const std::uint64_t beginsBeforeRejection = output.loopCutoverBeginCount();
    output.rejectNextLoopDemandPublication();
    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        128U,
        4'800U) == NativePlaybackPublishStatus::InvalidSnapshot,
        "rejected logical demand rejects the whole direct-loop publication");
    require(output.rejectedLoopDemandPublishCount() == 1U &&
            output.loopDemandPublishCount() == 0U,
        "one output-demand rejection is exact and publishes no logical tuple");
    waitForRenderProgress(
        runtime,
        runtime.diagnostics().renderCycles,
        "rejected direct-loop publication remains worker-invisible",
        4U);
    require(output.loopCutoverBeginCount() == beginsBeforeRejection &&
            output.loopCutoverCommitCount() == 0U,
        "rejected demand cannot open or commit a ghost physical continuation");

    require(runtime.publishDirectLoopPolicy(
        1U,
        owner.selectionToken,
        owner.generation,
        true,
        128U,
        4'800U) == NativePlaybackPublishStatus::Accepted,
        "same-revision direct-loop retry completes the interrupted transaction");
    waitUntil([&] {
        const NativeAudioOutputLoopPolicy demand = output.lastPublishedLoopDemand();
        return output.loopDemandPublishCount() == 1U && demand.revision == 1U &&
            demand.enabled && !demand.physicalReady;
    }, "same-revision retry publishes the exact logical demand");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "same-revision retry becomes worker-visible and commits one continuation");
}

void directLoopDemandLinearizesAgainstAutomaticSuccessor() {
    {
        constexpr SongRenderOwnership outgoing{426U, 2U};
        constexpr SongRenderOwnership successor{427U, 3U};
        SongLaneIngressBank first(4'096U, 64U);
        SongLaneIngressBank second(4'096U, 64U);
        configureLanes(first, outgoing.generation);
        configureLanes(second, successor.generation);
        offerLane(first, 0U, outgoing.generation, 0U, 150U);
        offerLane(second, 0U, successor.generation, 0U, 2'400U);
        FakeOutput output(4'096U, 64);
        NativePlaybackRuntime runtime(
            runtimeConfiguration(4'096U, 1U),
            first,
            second,
            output.port());
        [[maybe_unused]] const auto cleanup = onScopeExit([&]() noexcept {
            runtime.requestStop();
            runtime.join();
        });
        const std::array<NativePlaybackBankIntent, 2U> banks{
            assignedBank(1U, outgoing), assignedBank(1U, successor)};
        require(runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            outgoing,
            banks,
            controls(1U))) == NativePlaybackPublishStatus::Accepted,
            "loop-fence outgoing owner selected");
        require(runtime.start(), "loop-fence runtime starts");
        waitUntil([&] { return output.commitCount() == 1U; },
            "loop-fence outgoing owner committed");
        require(output.presentFirstCurrentRecord(), "loop-fence outgoing owner presented");
        waitUntil([&] {
            const auto readiness = runtime.renderReadinessDiagnostics();
            return readiness.currentSongCacheBoundToSelectedOwner &&
                (readiness.currentSongCacheCompleteLaneMask & 1U) != 0U;
        }, "loop-fence outgoing cache ready", 8'000U);

        auto automatic = selectedCommand(
            2U,
            NativePlaybackBank::First,
            outgoing,
            banks,
            controls(2U));
        automatic.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
            true,
            false,
            79U,
            outgoing,
            NativePlaybackBank::Second,
            successor};
        require(runtime.publishCommand(automatic) == NativePlaybackPublishStatus::Accepted,
            "loop-fence automatic successor accepted");
        waitUntil([&] { return output.drainCommitCount() == 1U; },
            "loop-fence automatic successor committed");

        require(runtime.publishDirectLoopPolicy(
            1U,
            outgoing.selectionToken,
            outgoing.generation,
            true,
            0U,
            128U) == NativePlaybackPublishStatus::Accepted,
            "enabled direct-loop demand accepted over committed successor");
        waitUntil([&] {
            const auto diagnostic = runtime.diagnostics().automaticSuccessor;
            return diagnostic.phase == NativePlaybackAutomaticSuccessorPhase::DrainCommitted &&
                diagnostic.cancellationDesired &&
                diagnostic.cancellationStatus ==
                    NativeAudioDrainCancelStatus::CancellationRequested;
        }, "direct-loop demand fences the committed successor before callback swap");
        const FakeOutput::CallbackResult cancellationCallback = output.consumeCallback(1U);
        require(cancellationCallback.successorFrames == 0U,
            "cancellation callback cannot change to the successor generation");
        waitUntil([&] {
            return runtime.diagnostics().automaticSuccessor.phase ==
                    NativePlaybackAutomaticSuccessorPhase::Idle &&
                output.loopCutoverBeginCount() != 0U;
        }, "acknowledged successor cancellation releases the inactive role to A/B");
        require(output.drainBeginCount() == 1U &&
                sameOwnership(runtime.diagnostics().requestedOwnership, outgoing) &&
                sameOwnership(runtime.diagnostics().committedOwnership, outgoing),
            "enabled A/B demand cannot restage or promote the cancelled successor");
    }

    {
        constexpr SongRenderOwnership outgoing{428U, 2U};
        constexpr SongRenderOwnership successor{429U, 3U};
        SongLaneIngressBank first(4'096U, 64U);
        SongLaneIngressBank second(4'096U, 64U);
        configureLanes(first, outgoing.generation);
        configureLanes(second, successor.generation);
        offerLane(first, 0U, outgoing.generation, 0U, 150U);
        offerLane(second, 0U, successor.generation, 0U, 2'400U);
        FakeOutput output(4'096U, 64);
        NativePlaybackRuntime runtime(
            runtimeConfiguration(4'096U, 1U),
            first,
            second,
            output.port());
        [[maybe_unused]] const auto cleanup = onScopeExit([&]() noexcept {
            runtime.requestStop();
            runtime.join();
        });
        const std::array<NativePlaybackBankIntent, 2U> banks{
            assignedBank(1U, outgoing), assignedBank(1U, successor)};
        require(runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            outgoing,
            banks,
            controls(1U))) == NativePlaybackPublishStatus::Accepted,
            "late-loop outgoing owner selected");
        require(runtime.start(), "late-loop runtime starts");
        waitUntil([&] { return output.commitCount() == 1U; },
            "late-loop outgoing owner committed");
        require(output.presentFirstCurrentRecord(), "late-loop outgoing owner presented");

        auto automatic = selectedCommand(
            2U,
            NativePlaybackBank::First,
            outgoing,
            banks,
            controls(2U));
        automatic.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
            true,
            false,
            80U,
            outgoing,
            NativePlaybackBank::Second,
            successor};
        require(runtime.publishCommand(automatic) == NativePlaybackPublishStatus::Accepted,
            "late-loop automatic successor accepted");
        waitUntil([&] { return output.drainCommitCount() == 1U; },
            "late-loop automatic successor committed");
        output.consumeAll();
        const FakeOutput::CallbackResult successorCallback = output.consumeCallback(64U);
        require(successorCallback.successorFrames == 64U,
            "callback irrevocably installs successor before late A/B demand");
        waitUntil([&] {
            const auto diagnostic = runtime.diagnostics();
            return sameOwnership(diagnostic.requestedOwnership, successor) &&
                sameOwnership(diagnostic.committedOwnership, successor);
        }, "runtime adopts callback-installed successor");

        const std::uint64_t loopBeginsBeforeLateRequest = output.loopCutoverBeginCount();
        const NativePlaybackPublishStatus lateStatus = runtime.publishDirectLoopPolicy(
            1U,
            outgoing.selectionToken,
            outgoing.generation,
            true,
            0U,
            128U);
        require(lateStatus == NativePlaybackPublishStatus::Accepted ||
                lateStatus == NativePlaybackPublishStatus::InvalidSnapshot,
            "callback-installed successor classifies stale outgoing A/B without a runtime fault");
        waitForRenderProgress(
            runtime,
            runtime.diagnostics().renderCycles,
            "stale outgoing A/B remains retired after successor adoption",
            4U);
        require(output.loopCutoverBeginCount() == loopBeginsBeforeLateRequest &&
                sameOwnership(runtime.diagnostics().requestedOwnership, successor) &&
                sameOwnership(runtime.diagnostics().committedOwnership, successor),
            "late old-owner demand cannot publish, stage, or resurrect its source");
    }
}

void unloadWaitsForCommittedDrainCancellationBeforeHardFlush() {
    SongLaneIngressBank first(4'096, 64);
    SongLaneIngressBank second(4'096, 64);
    configureLanes(first, 2U, 1U);
    configureLanes(second, 3U, 1U);
    offerLane(first, 0U, 2U, 0U, 150U);
    offerLane(second, 0U, 3U, 0U, 2'048U);
    FakeOutput output(512U, 64);
    NativePlaybackRuntime runtime(runtimeConfiguration(4'096U, 64U), first, second, output.port());
    const SongRenderOwnership outgoing{430U, 2U};
    const SongRenderOwnership successor{440U, 3U};
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, outgoing), assignedBank(1U, successor)};

    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        outgoing,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "drain-cancel unload outgoing command accepted");
    require(runtime.start(), "drain-cancel unload runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "drain-cancel unload outgoing stage committed");
    require(output.presentFirstCurrentRecord(),
        "drain-cancel unload outgoing presentation published");
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, outgoing);
    }, "drain-cancel unload outgoing owner committed");

    auto automatic = selectedCommand(
        2U,
        NativePlaybackBank::First,
        outgoing,
        banks,
        controls(2U));
    automatic.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
        true,
        false,
        78U,
        outgoing,
        NativePlaybackBank::Second,
        successor};
    require(runtime.publishCommand(automatic) == NativePlaybackPublishStatus::Accepted,
        "drain-cancel unload automatic successor accepted");
    waitUntil([&] { return output.drainCommitCount() == 1U; },
        "drain-cancel unload successor physically committed");

    banks[0] = NativePlaybackBankIntent{2U, false, {}, 0U, 0};
    banks[1] = NativePlaybackBankIntent{2U, false, {}, 0U, 0};
    require(runtime.publishCommand(unselectedCommand(3U, 4U, banks, controls(3U))) ==
        NativePlaybackPublishStatus::Accepted,
        "drain-cancel unload command accepted");
    waitUntil([&] {
        const NativePlaybackRuntimeDiagnostics diagnostic = runtime.diagnostics();
        return diagnostic.automaticSuccessor.phase ==
                NativePlaybackAutomaticSuccessorPhase::DrainCommitted &&
            diagnostic.automaticSuccessor.cancellationDesired &&
            diagnostic.automaticSuccessor.cancellationStatus ==
                NativeAudioDrainCancelStatus::CancellationRequested;
    }, "unload waits on exact committed-drain cancellation acknowledgement");
    require(output.flushCount() == 0U,
        "hard flush does not race callback-owned cancellation publication");
    require(runtime.diagnostics().outputFlushFailures == 0U,
        "expected cancellation acknowledgement is not an output flush failure");

    // Hold the callback claim open until the render worker has retried cancellation. Production's
    // coordinator treats that claim as immutable callback ownership; the retry must not create a
    // second request that would require a second callback before unload can flush.
    require(output.claimRequestedDrainCancellationWithoutAcknowledgement(),
        "callback claims the exact committed-drain cancellation");
    const std::uint64_t attemptsWhileClaimed = output.drainCancelAttemptCount();
    waitUntil([&] {
        return output.drainCancelAttemptCount() > attemptsWhileClaimed;
    }, "producer cancellation retry observes the callback-owned claim");
    require(output.acknowledgeClaimedDrainCancellation(),
        "callback acknowledges its exact committed-drain cancellation claim");
    waitUntil([&] {
        const NativePlaybackRuntimeDiagnostics diagnostic = runtime.diagnostics();
        return diagnostic.committedOwnership.generation == 0U &&
            !diagnostic.banks[0].assigned && !diagnostic.banks[1].assigned &&
            output.flushCount() == 1U && output.queued() == 0U && !output.playing();
    }, "acknowledged drain cancellation permits one clean unload flush");
    require(runtime.diagnostics().outputFlushFailures == 0U,
        "task-removal-style unload completes without false flush fault accounting");
    stopAndJoin(runtime);
}

void sameCanonicalRevisionAutomaticSuccessorLifecycleReappliesPerOwnerControls() {
    SongLaneIngressBank first(8'192, 64);
    SongLaneIngressBank second(8'192, 64);
    configureLanes(first, 2U);
    configureLanes(second, 3U, 1U);
    offerLane(first, 0U, 2U, 0U, 150U);
    offerLane(second, 0U, 3U, 0U, 2'048U);
    FakeOutput output(512U, 64);
    NativePlaybackRuntime runtime(runtimeConfiguration(8'192U, 64U), first, second, output.port());
    const SongRenderOwnership outgoing{510U, 2U};
    const SongRenderOwnership successor{520U, 3U};
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, outgoing), assignedBank(1U, successor)};
    const SongRenderControlSnapshot canonicalControls =
        controls(1U, 1.25F, 3.0F, MixerSourceMode::Stems);

    require(runtime.publishCommand(selectedCommand(
        1U, NativePlaybackBank::First, outgoing, banks, canonicalControls)) ==
        NativePlaybackPublishStatus::Accepted, "claimed-drain outgoing command accepted");
    require(runtime.start(), "claimed-drain runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "claimed-drain outgoing stage committed");
    require(output.presentFirstCurrentRecord(), "claimed-drain outgoing owner presented");
    waitUntil([&] {
        return sameOwnership(runtime.diagnostics().committedOwnership, outgoing);
    }, "claimed-drain outgoing owner committed");

    auto automatic = selectedCommand(
        2U, NativePlaybackBank::First, outgoing, banks, canonicalControls);
    automatic.automaticSuccessor = NativePlaybackAutomaticSuccessorIntent{
        true, false, 91U, outgoing, NativePlaybackBank::Second, successor};
    require(runtime.publishCommand(automatic) == NativePlaybackPublishStatus::Accepted,
        "claimed-drain automatic successor accepted");
    waitUntil([&] { return output.drainCommitCount() == 1U; },
        "claimed-drain successor committed");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics().automaticSuccessor;
        return diagnostic.phase == NativePlaybackAutomaticSuccessorPhase::DrainCommitted &&
            sameOwnership(diagnostic.outgoingOwnership, outgoing) &&
            sameOwnership(diagnostic.successorOwnership, successor) &&
            diagnostic.successorQueuedFrames >= 256U &&
            diagnostic.firstFingerprintSequence != 0U;
    }, "claimed-drain diagnostics prove successor PCM staged");
    waitUntil([&] {
        NativeAudioLateMixControl neutral{};
        return output.lateControlForOwner(successor, neutral) &&
            neutral.canonicalControlRevision == canonicalControls.revision &&
            neutral.sourceMode == NativeAudioSourceMode::Original;
    }, "same-canonical successor prewarm applies neutral controls at a new application epoch");

    output.consumeAll();
    require(output.claimCommittedDrainWithoutReading(),
        "callback claims exact drain after outgoing queue empties");
    require(runtime.publishCommand(selectedCommand(
        3U, NativePlaybackBank::First, outgoing, banks, canonicalControls, false)) ==
        NativePlaybackPublishStatus::Accepted, "Pause wins while callback owns swap claim");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics().automaticSuccessor;
        return diagnostic.phase == NativePlaybackAutomaticSuccessorPhase::DrainCommitted &&
            diagnostic.cancellationDesired &&
            diagnostic.cancellationStatus ==
                NativeAudioDrainCancelStatus::AdoptionInFlight;
    }, "producer observes too-late cancellation without deadlocking");

    require(output.acknowledgeClaimedDrainWithoutPresentation(),
        "callback acknowledges successor without reading PCM");
    waitUntil([&] {
        const NativePlaybackRuntimeDiagnostics diagnostic = runtime.diagnostics();
        return diagnostic.automaticSuccessor.phase ==
                NativePlaybackAutomaticSuccessorPhase::AppliedPausedAwaitingSelection &&
            diagnostic.automaticSuccessor.cancellationStatus ==
                NativeAudioDrainCancelStatus::SuccessorActive &&
            sameOwnership(diagnostic.requestedOwnership, successor) &&
            sameOwnership(diagnostic.acceptedOwnership, successor) &&
            sameOwnership(diagnostic.committedOwnership, successor) &&
            diagnostic.presentationEvidence.sequence == 0U && !output.playing();
    }, "silent callback adoption becomes exact paused physical owner");

    // The controller can now administratively install the reserved successor while paused without
    // asking native output to perform an impossible handoff back to the stale outgoing generation.
    require(runtime.publishCommand(selectedCommand(
        4U, NativePlaybackBank::Second, successor, banks, canonicalControls, false)) ==
        NativePlaybackPublishStatus::Accepted, "paused reserved successor installed");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return diagnostic.automaticSuccessor.phase ==
                NativePlaybackAutomaticSuccessorPhase::Idle &&
            sameOwnership(diagnostic.committedOwnership, successor);
    }, "paused reserved successor clears automatic plan");
    waitUntil([&] {
        NativeAudioLateMixControl selected{};
        return output.lateControlForOwner(successor, selected) &&
            selected.canonicalControlRevision == canonicalControls.revision &&
            selected.sourceMode == NativeAudioSourceMode::Stems;
    }, "same-canonical exact selection restores normal controls and clears neutral policy");
    require(runtime.diagnostics().presentationEvidence.sequence == 0U,
        "paused adoption never fabricates audible evidence");

    // A distinct paused manual selection must supersede the physically adopted successor with a
    // genuinely newer generation, then task removal must still flush and quiesce both banks.
    const SongRenderOwnership manual{530U, 4U};
    offerAllLanes(first, 4U, 2'048U);
    banks[0] = assignedBank(2U, manual);
    output.setAutoAcknowledgePausedCommit(true);
    require(runtime.publishCommand(selectedCommand(
        5U, NativePlaybackBank::First, manual, banks, canonicalControls, false)) ==
        NativePlaybackPublishStatus::Accepted, "newer paused manual selection accepted");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return sameOwnership(diagnostic.requestedOwnership, manual) &&
            sameOwnership(diagnostic.acceptedOwnership, manual) &&
            sameOwnership(diagnostic.committedOwnership, manual) && !output.playing();
    }, "newer paused manual selection supersedes adopted successor");
    waitUntil([&] {
        return output.sawLateControlForOwner(
            manual,
            canonicalControls.revision,
            NativeAudioSourceMode::Stems);
    }, "same-canonical owner reset reapplies controls to the rebound bank owner");

    banks[0] = NativePlaybackBankIntent{3U, false, {}, 0U, 0};
    banks[1] = NativePlaybackBankIntent{2U, false, {}, 0U, 0};
    require(runtime.publishCommand(unselectedCommand(6U, 5U, banks, canonicalControls)) ==
        NativePlaybackPublishStatus::Accepted, "post-race unload accepted");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return diagnostic.requestedOwnership.selectionToken == 0U &&
            diagnostic.requestedOwnership.generation == 0U &&
            diagnostic.acceptedOwnership.selectionToken == 0U &&
            diagnostic.acceptedOwnership.generation == 0U &&
            diagnostic.committedOwnership.selectionToken == 0U &&
            diagnostic.committedOwnership.generation == 0U &&
            !diagnostic.banks[0].assigned && !diagnostic.banks[1].assigned &&
            output.queued() == 0U && !output.playing();
    }, "post-race unload flushes and quiesces without ghost audio");
    stopAndJoin(runtime);
}

void openStageIsSupersededAndCommittedStageConflatesNewestSelection() {
    SongLaneIngressBank first(8'192, 480);
    SongLaneIngressBank second(8'192, 480);
    configureLanes(first, 2, 1);
    configureLanes(second, 3, 1);
    offerLane(first, 0, 2, 0, 2'400, false);
    offerLane(second, 0, 3, 0, 2'400, false);
    FakeOutput output;
    output.setCommitAllowed(false);
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, {210, 2}),
        assignedBank(1, {220, 3}),
    };
    require(runtime.publishCommand(selectedCommand(
        1, NativePlaybackBank::First, {210, 2}, banks, controls(1))) ==
        NativePlaybackPublishStatus::Accepted, "open-stage initial command");
    require(runtime.start(), "open-stage start");
    waitUntil([&] {
        return runtime.diagnostics().acceptedOwnership.generation == 2U &&
            output.recordCount() != 0U;
    }, "first stage populated but uncommitted");
    require(output.commitCount() == 0U, "first stage held open");

    require(runtime.publishCommand(selectedCommand(
        2, NativePlaybackBank::Second, {220, 3}, banks, controls(2))) ==
        NativePlaybackPublishStatus::Accepted, "newest open stage command");
    waitUntil([&] {
        return runtime.diagnostics().acceptedOwnership.generation == 3U &&
            output.beginCount() == 2U;
    }, "new generation superseded open stage");
    const std::size_t generationThreeStart = output.currentGenerationFirstRecord();
    output.setCommitAllowed(true);
    waitUntil([&] { return output.commitCount() == 1U; }, "newest open stage committed");
    for (std::size_t index = generationThreeStart; index < output.recordCount(); ++index) {
        require(output.record(index).generation == 3U, "superseded stage cannot leak stale PCM");
    }

    // A command arriving after commit publication cannot overwrite the ring until the callback
    // acknowledges it. The runtime keeps only that newest intent and retries without blocking.
    offerLane(first, 0, 4, 0, 2'400, false);
    banks[0] = assignedBank(3, {230, 4});
    require(runtime.publishCommand(selectedCommand(
        3, NativePlaybackBank::First, {230, 4}, banks, controls(3))) ==
        NativePlaybackPublishStatus::Accepted, "post-commit newer command");
    waitUntil([&] {
        return runtime.diagnostics().outputHandoffBeginRetries != 0U;
    }, "newest command retried while callback ack pending");
    output.setCommitAllowed(false);
    require(output.presentFirstCurrentRecord(), "generation three callback ack");
    waitUntil([&] {
        return runtime.diagnostics().acceptedOwnership.generation == 4U &&
            output.beginCount() == 3U;
    }, "generation four staged after ack");
    waitUntil([&] { return runtime.diagnostics().committedOwnership.generation == 3U; },
        "generation three remains audible while four stages");
    const auto oldPosition = runtime.diagnostics().presentedPosition;
    output.advanceAudiblePosition(64);
    waitUntil([&] {
        const auto position = runtime.diagnostics().presentedPosition;
        return position.sequence > oldPosition.sequence && position.generation == 3U &&
            position.lastTimelineFrame > oldPosition.lastTimelineFrame;
    }, "audible old-owner position stays live during delayed staging");
    output.setCommitAllowed(true);
    waitUntil([&] { return output.commitCount() == 2U; }, "generation four committed");
    require(output.presentFirstCurrentRecord(), "generation four presented");
    waitUntil([&] { return runtime.diagnostics().committedOwnership.generation == 4U; },
        "newest committed owner exact");
    stopAndJoin(runtime);
}

void partialOutputSuffixesAndCacheIngressAreNeverLost() {
    SongLaneIngressBank first(32'768, 480);
    SongLaneIngressBank second(32'768, 480);
    configureLanes(first, 2, 1);
    configureLanes(second, 3, 1);
    offerLane(first, 0, 2, 0, 20'000);
    FakeOutput output(512, 16);
    output.setMaximumAcceptance(17);
    auto configuration = runtimeConfiguration(16'384, 64);
    configuration.ingressPullFrames = 256;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, {300, 2}),
        NativePlaybackBankIntent{},
    };
    require(
        runtime.publishCommand(selectedCommand(
            1,
            NativePlaybackBank::First,
            {300, 2},
            banks,
            controls(1))) == NativePlaybackPublishStatus::Accepted,
        "partial command");
    require(runtime.start(), "partial start");
    waitUntil(
        [&] {
            const auto diagnostic = runtime.diagnostics();
            return diagnostic.outputPartialAccepts != 0U &&
                diagnostic.banks[0].lanes[0].appendedFrames == 20'000U;
        },
        "bounded output retained its suffix while the song cache accepted ingress");

    std::uint64_t lastFingerprint = 0;
    for (std::size_t index = 0; index < output.recordCount(); ++index) {
        const auto record = output.record(index);
        require(record.frames <= 17U, "fake output forced bounded prefix");
        require(record.sourceEnd >= record.sourceStart, "partial interval ordered");
        require(record.fingerprint >= lastFingerprint, "fingerprint tags monotonic");
        lastFingerprint = record.fingerprint;
    }
    const auto ingress = runtime.diagnostics().banks[0].lanes[0];
    require(ingress.pulledFrames == 20'000U && ingress.appendedFrames == 20'000U &&
            ingress.retainedSuffixFrames == 0U,
        "current-song cache lost or stranded an ingress suffix");
    stopAndJoin(runtime);
}

void fullCurrentIngressPrefixCannotDeadlockFutureLoopEpochPriming() {
    SongLaneIngressBank first(64, 64);
    SongLaneIngressBank second(64, 64);
    configureLanes(first, 80, 1);
    LaneIngress *original = first.lane(0);
    require(original != nullptr, "loop-prime original configured");

    const auto current = tone(64, 0, 0.1F, 220.0);
    const LaneInputBlock currentBlock{
        current.data(),
        64,
        LaneInputFormat{kSampleRate, 2, LanePcmEncoding::Float32},
        80,
        0,
    };
    require(
        original->offer(currentBlock).status == LaneOfferStatus::Accepted,
        "loop-prime current ingress fills ring");
    require(original->advanceGeneration(81), "loop-prime future epoch authorized");

    const auto future = tone(64, 288'000, 0.2F, 330.0);
    const LaneInputBlock futureBlock{
        future.data(),
        64,
        LaneInputFormat{kSampleRate, 2, LanePcmEncoding::Float32},
        81,
        6'000'000,
    };
    require(
        original->offer(futureBlock).status == LaneOfferStatus::Backpressured,
        "loop-prime future epoch initially waits behind full current ring");

    FakeOutput output;
    auto configuration = runtimeConfiguration(512, 64);
    configuration.ingressPullFrames = 64;
    configuration.maximumPullsPerLanePerCycle = 1;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, {900, 80}), NativePlaybackBankIntent{}};
    require(
        runtime.publishCommand(selectedCommand(
            1,
            NativePlaybackBank::First,
            {900, 80},
            banks,
            controls(1))) == NativePlaybackPublishStatus::Accepted,
        "loop-prime current owner command");
    require(runtime.start(), "loop-prime runtime start");
    waitUntil(
        [&] { return original->diagnostics().readableSourceFrames == 0U; },
        "loop-prime runtime retires the obsolete full prefix");

    require(
        original->offer(futureBlock).status == LaneOfferStatus::Accepted,
        "loop-prime future epoch queues after stale retirement");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return diagnostic.banks[0].lanes[0].futureGenerationDeferrals != 0U &&
            original->diagnostics().readableSourceFrames == 64U;
    }, "loop-prime preserves the future body until the loop epoch is selected");
    stopAndJoin(runtime);
}

void absoluteFiveSecondSeekStagesExactNewGeneration() {
    SongLaneIngressBank first(8'192, 480);
    SongLaneIngressBank second(8'192, 480);
    configureLanes(first, 2);
    configureLanes(second, 10, 1);
    for (std::size_t lane = 0; lane < kLaneCount; ++lane) {
        offerLane(first, lane, 2, 0, 2'400);
    }
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, {800, 2}), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(
        1, NativePlaybackBank::First, {800, 2}, banks, controls(1))) ==
        NativePlaybackPublishStatus::Accepted, "seek initial command");
    require(runtime.start(), "seek runtime start");
    waitUntil([&] { return output.commitCount() == 1U; }, "seek initial commit");
    require(output.presentFirstCurrentRecord(), "seek initial presentation");
    waitUntil([&] { return runtime.diagnostics().committedOwnership.generation == 2U; },
        "seek initial owner committed");

    constexpr std::uint64_t fiveSecondsAt48k = 5U * kSampleRate;
    for (std::size_t lane = 0; lane < kLaneCount; ++lane) {
        offerLane(first, lane, 3, fiveSecondsAt48k, 2'400, false);
    }
    waitUntil([&] {
        for (std::size_t lane = 0; lane < kLaneCount; ++lane) {
            const auto ingress = first.lane(lane)->diagnostics();
            if (ingress.readableSourceFrames != 2'400U ||
                ingress.consumerGeneration != 2U) {
                return false;
            }
        }
        return true;
    }, "all future lane heads remain untouched before atomic bank seek");
    for (std::size_t lane = 0; lane < kLaneCount; ++lane) {
        require(first.lane(lane)->diagnostics().readableSourceFrames != 0U,
            "future lane head remains queued before bank seek");
    }
    banks[0] = assignedBank(2, {800, 3}, fiveSecondsAt48k);
    require(runtime.publishCommand(selectedCommand(
        2, NativePlaybackBank::First, {800, 3}, banks, controls(2))) ==
        NativePlaybackPublishStatus::Accepted, "absolute five-second seek command");
    waitUntil([&] {
        return runtime.diagnostics().acceptedOwnership.generation == 3U &&
            output.commitCount() == 2U;
    }, "seek generation staged and committed");
    const std::size_t seekStartRecord = output.currentGenerationFirstRecord();
    require(output.presentFirstCurrentPositionOnly(),
        "seek callback publishes the future owner's position first");
    const std::uint64_t splitPositionSequence = output.publishedPositionSequence();
    require(splitPositionSequence != 0U, "seek split position has an exact sequence");
    waitUntil([&] {
        return output.lastPositionSequenceRead() == splitPositionSequence &&
            runtime.diagnostics().committedOwnership.generation == 2U;
    }, "worker observes future seek position before presentation commits ownership");
    require(runtime.diagnostics().presentedPosition.generation != 3U,
        "future seek position cannot publish against the outgoing committed owner");
    require(output.presentFirstCurrentPresentationOnly(),
        "seek callback publishes matching first-presentation evidence");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return diagnostic.committedOwnership.generation == 3U &&
            diagnostic.presentedPosition.generation == 3U &&
            diagnostic.presentedPosition.sequence == splitPositionSequence;
    }, "retained seek position commits after matching presentation without another callback");
    require(output.publishedPositionSequence() == splitPositionSequence,
        "seek position recovery uses the original callback tuple exactly once");
    require(runtime.diagnostics().banks[0].seekCount == 1U, "same-token newer generation is seek");
    require(output.flushCount() == 0U, "seek never hard flushes");
    for (std::size_t index = seekStartRecord; index < output.recordCount(); ++index) {
        const auto record = output.record(index);
        require(record.generation == 3U, "seek ring contains only new generation");
        require(record.sourceStart >= static_cast<double>(fiveSecondsAt48k),
            "seek starts at exact absolute five-second frame");
    }
    stopAndJoin(runtime);
}

void pausedTerminalTailCannotAutoAdvance() {
    SongLaneIngressBank first(4'096, 480);
    SongLaneIngressBank second(4'096, 480);
    configureLanes(first, 2, 1);
    configureLanes(second, 3, 1);
    offerLane(first, 0, 2, 0, 960);
    FakeOutput output(512, 32);
    CommandPublicationVisibilityBarrier pauseVisibilityBarrier(2U);
    NativePlaybackRuntimeConfiguration configuration = runtimeConfiguration();
    configuration.commandPublicationVisibleHookContext = &pauseVisibilityBarrier;
    configuration.commandPublicationVisibleHook = &CommandPublicationVisibilityBarrier::hook;
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, {900, 2}), NativePlaybackBankIntent{}};
    auto command = selectedCommand(
        1, NativePlaybackBank::First, {900, 2}, banks, controls(1));
    require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
        "retained loop command");
    require(runtime.start(), "retained loop start");
    waitUntil([&] { return output.commitCount() == 1U; }, "retained loop commit");
    require(output.presentFirstCurrentRecord(), "retained loop presented");
    waitUntil([&] { return runtime.diagnostics().committedOwnership.generation == 2U; },
        "retained loop owner committed");

    // Loop wrapping is covered by directLoopPhysicalCutoverUsesDistinctBoundaryAndContinuation.
    // This scenario owns the independent Pause-publication versus final-drain ordering.

    command.revision = 2;
    command.controls = controls(2);
    command.playing = false;
    NativePlaybackPublishStatus pausePublicationStatus = NativePlaybackPublishStatus::Stopped;
    std::atomic<bool> pausePublisherReturned{false};
    const NativePlaybackCommandSnapshot pauseCommand = command;
    std::thread pausePublisher([&] {
        pausePublicationStatus = runtime.publishCommand(pauseCommand);
        pausePublisherReturned.store(true, std::memory_order_release);
    });
    waitUntil(
        [&] { return pauseVisibilityBarrier.visible.load(std::memory_order_acquire); },
        "Pause mailbox visibility hook entered");
    waitUntil([&] {
        const auto ordering = runtime.diagnostics().endOrdering;
        return ordering.commandConsumed.commandRevision == 2U &&
            !ordering.commandConsumed.desiredPlaying &&
            ordering.outputPlayingApplied.commandRevision == 2U &&
            !ordering.outputPlayingApplied.outputPlaying;
    }, "Pause is consumed while its producer remains inside the post-visibility hook");
    const auto forcedInterleavingOrdering = runtime.diagnostics().endOrdering;
    const bool consumedBeforePublisherReturn =
        !pausePublisherReturned.load(std::memory_order_acquire);
    pauseVisibilityBarrier.release();
    pausePublisher.join();
    require(consumedBeforePublisherReturn,
        "worker consumes Pause before producer-side publish completion");
    require(pausePublicationStatus == NativePlaybackPublishStatus::Accepted,
        "pause before cleared-loop drain command");
    require(forcedInterleavingOrdering.commandPublished.sequence <
            forcedInterleavingOrdering.commandConsumed.sequence,
        "forced visibility interleaving retains publication-before-consumption order");

    for (std::size_t iteration = 0; iteration < 100U; ++iteration) {
        const std::uint64_t renderCyclesBeforeDrain = runtime.diagnostics().renderCycles;
        output.consumeAll();
        // consumeAll() models a final callback that was already in flight when Pause won. This
        // test owns that external actor, so publish its explicit worker wake only after the
        // terminal position becomes observable.
        runtime.notifyIngressAvailable();
        const auto ordering = runtime.diagnostics().endOrdering;
        if (ordering.physicalDrainObserved.sequence != 0U) break;
        waitForRenderProgress(
            runtime,
            renderCyclesBeforeDrain,
            "paused cleared-loop worker advances toward exact drain");
    }
    waitUntil([&] {
        return runtime.diagnostics().endOrdering.physicalDrainObserved.sequence != 0U;
    }, "paused owner reaches an observable physical drain");
    auto ordering = runtime.diagnostics().endOrdering;
    require(ordering.commandPublished.commandRevision == 2U &&
            sameOwnership(ordering.commandPublished.ownership, SongRenderOwnership{900U, 2U}) &&
            !ordering.commandPublished.desiredPlaying &&
            ordering.commandPublished.outputPlaying &&
            !ordering.commandPublished.commandUnread &&
            ordering.commandPublished.workerStateSequence != 0U &&
            ordering.commandPublished.workerStateSequence <
                ordering.commandPublished.sequence,
        "Pause publication is retained in end-order trace");
    require(ordering.commandPublished.sequence < ordering.commandConsumed.sequence,
        "Pause publication precedes Pause consumption");
    require(ordering.commandConsumed.sequence <= ordering.outputPlayingApplied.sequence,
        "Pause consumption precedes physical output pause");
    require(ordering.commandConsumed.commandRevision == 2U &&
            sameOwnership(ordering.commandConsumed.ownership, SongRenderOwnership{900U, 2U}) &&
            !ordering.commandConsumed.desiredPlaying &&
            ordering.commandConsumed.outputPlaying &&
            !ordering.commandConsumed.commandUnread,
        "Pause consumption binds the exact owner and pre-application output state");
    require(ordering.outputPlayingApplied.commandRevision == 2U &&
            sameOwnership(
                ordering.outputPlayingApplied.ownership,
                SongRenderOwnership{900U, 2U}) &&
            !ordering.outputPlayingApplied.desiredPlaying &&
            !ordering.outputPlayingApplied.outputPlaying &&
            !ordering.outputPlayingApplied.commandUnread,
        "output-pause trace binds the exact applied command and owner");
    require(ordering.outputPlayingApplied.sequence < ordering.physicalDrainObserved.sequence,
        "applied Pause precedes physical drain eligibility");
    require(ordering.pipelineEndObserved.sequence < ordering.physicalDrainObserved.sequence,
        "pipeline EOS observation precedes physical drain");
    require(ordering.pipelineEndObserved.commandRevision == 2U &&
            sameOwnership(
                ordering.pipelineEndObserved.ownership,
                SongRenderOwnership{900U, 2U}) &&
            !ordering.pipelineEndObserved.desiredPlaying &&
            !ordering.pipelineEndObserved.outputPlaying &&
            ordering.pipelineEndObserved.pipelineEndObserved &&
            !ordering.pipelineEndObserved.commandUnread,
        "pipeline-EOS trace binds the paused command, owner, and unread state");
    require(ordering.physicalDrainObserved.commandRevision == 2U &&
            sameOwnership(
                ordering.physicalDrainObserved.ownership,
                SongRenderOwnership{900U, 2U}) &&
            !ordering.physicalDrainObserved.desiredPlaying &&
            !ordering.physicalDrainObserved.outputPlaying &&
            ordering.physicalDrainObserved.pipelineEndObserved &&
            !ordering.physicalDrainObserved.commandUnread &&
            ordering.physicalDrainObserved.pendingOutputFrames == 0U &&
            ordering.physicalDrainObserved.queuedOutputFrames == 0U &&
            ordering.physicalDrainObserved.terminalPositionValid &&
            ordering.physicalDrainObserved.terminalAfterFrame,
        "physical-drain trace binds paused state, owner, queue, and terminal evidence");
    require(runtime.diagnostics().audibleEnd.sequence == 0U,
        "Pause applied before physical drain suppresses automatic next");
    require(ordering.audibleEndPublished.sequence == 0U,
        "suppressed paused EOS has no publication trace");
    std::cout << "END_ORDER_TRACE pause-before-drain"
              << " forced-producer-incomplete=" << consumedBeforePublisherReturn;
    writeEndOrderingEvent("publish", ordering.commandPublished);
    writeEndOrderingEvent("consume", ordering.commandConsumed);
    writeEndOrderingEvent("output-pause", ordering.outputPlayingApplied);
    writeEndOrderingEvent("eos", ordering.pipelineEndObserved);
    writeEndOrderingEvent("drain", ordering.physicalDrainObserved);
    writeEndOrderingEvent("audible-end", ordering.audibleEndPublished);
    std::cout << '\n';

    command.revision = 3;
    command.controls = controls(3);
    command.playing = true;
    require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
        "resume at EOS command");
    waitUntil([&] { return runtime.diagnostics().audibleEnd.sequence == 1U; },
        "resume publishes one exact audible EOS");
    ordering = runtime.diagnostics().endOrdering;
    require(ordering.commandConsumed.commandRevision == 3U &&
            ordering.commandConsumed.desiredPlaying,
        "Resume consumption is explicit in end-order trace");
    require(ordering.commandConsumed.sequence < ordering.audibleEndPublished.sequence,
        "audible EOS follows explicit Resume consumption");
    const std::uint64_t resumedRenderCycles = runtime.diagnostics().renderCycles;
    waitForRenderProgress(
        runtime,
        resumedRenderCycles,
        "resumed EOS remains stable across later render cycles",
        4U);
    require(runtime.diagnostics().audibleEnd.sequence == 1U, "resumed EOS remains once-only");
    stopAndJoin(runtime);
}

void nonzeroColdOwnerAudibleEndIsExactOnce(bool playbackWindow = false) {
    SongLaneIngressBank first(32'768, 480);
    SongLaneIngressBank second(32'768, 480);
    configureLanes(first, 2, 1);
    configureLanes(second, 3, 1);
    // A nonzero cold start cannot build the frame-zero current-song cache.
    // Keep exercising decoder refill and finite EOS independently of the cache path.
    const std::uint64_t start = playbackWindow ? 1'003U : 1U;
    constexpr std::uint64_t trimmedEnd = 7'007U;
    offerLane(first, 0, 2, start, 20'000, !playbackWindow);
    FakeOutput output(512, 32);
    NativePlaybackRuntime runtime(runtimeConfiguration(16'384, 64), first, second, output.port());
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, {400, 2}, start),
        NativePlaybackBankIntent{},
    };
    if (playbackWindow) banks[0].playbackEndFrameExclusive = trimmedEnd;
    auto command = selectedCommand(
        1,
        NativePlaybackBank::First,
        {400, 2},
        banks,
        controls(1, playbackWindow ? 1.25F : 1.0F));
    require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
        "long loop command");
    require(runtime.start(), "long loop start");
    auto diagnostic = runtime.diagnostics();

    waitUntil([&] { return output.presentFirstCurrentRecord(); }, "EOS owner presented");
    waitUntil(
        [&] { return runtime.diagnostics().committedOwnership.generation == 2U; },
        "EOS owner committed");
    // Keep simulating callbacks. The event cannot publish before the pipeline reports EOS and the
    // final accepted ring content has drained.
    for (std::size_t iteration = 0; iteration < 1'000U; ++iteration) {
        const std::uint64_t renderCyclesBeforeDrain = runtime.diagnostics().renderCycles;
        output.consumeAll();
        if (runtime.diagnostics().audibleEnd.sequence != 0U) break;
        waitForRenderProgress(
            runtime,
            renderCyclesBeforeDrain,
            "finite owner worker observes the simulated output callback");
    }
    waitUntil(
        [&] { return runtime.diagnostics().audibleEnd.sequence == 1U; },
        "audible EOS published");
    diagnostic = runtime.diagnostics();
    require(diagnostic.audibleEnd.ownership.selectionToken == 400U, "EOS token exact");
    require(diagnostic.audibleEnd.ownership.generation == 2U, "EOS generation exact");
    bool sawSourceTerminal = false;
    std::uint64_t totalFrames = 0U;
    std::uint64_t neutralFrames = 0U;
    bool tunedSpanObserved = false;
    double sourceCursor = static_cast<double>(start);
    for (std::size_t index = 0U; index < output.recordCount(); ++index) {
        const FakeOutputRecord record = output.record(index);
        if (playbackWindow && record.generation == 2U) {
            require(record.sourceStart == sourceCursor && record.sourceEnd <= trimmedEnd &&
                !record.loopEnabled && record.peak > 0.0F, "bank window owns contiguous real PCM before cutoff");
            sourceCursor = record.sourceEnd;
            totalFrames += record.frames;
            const double sourceFrames = record.sourceEnd - record.sourceStart;
            if (record.endOfStreamAfterBlock) {
                require(tunedSpanObserved && sourceFrames > (record.frames - 1U) * 1.25 &&
                    sourceFrames <= record.frames * 1.25, "only final tuned frame may clamp at exclusive end");
            } else if (sourceFrames == record.frames) {
                require(!tunedSpanObserved, "settled tuning cannot revert to neutral without a command");
                neutralFrames += record.frames;
            } else {
                require(sourceFrames == record.frames * 1.25, "observed tuning consumes exact source rate");
                tunedSpanObserved = true;
            }
        }
        if (record.generation == 2U && record.endOfStreamAfterBlock) {
            sawSourceTerminal = true;
        }
    }
    if (playbackWindow) {
        require(tunedSpanObserved && sourceCursor == trimmedEnd && totalFrames == neutralFrames +
            static_cast<std::uint64_t>(std::ceil(static_cast<double>(trimmedEnd - start - neutralFrames) / 1.25)),
            "assigned bank window produces exact tempo-adjusted cutoff without decoder EOF");
        require(diagnostic.banks[0].lanes[0].endOfStreamMarks == 0U,
            "playback cutoff cannot forge physical decoder EOF");
    }
    require(sawSourceTerminal,
        "finite owner publishes one exact source-terminal PCM boundary");
    require(output.terminalSilenceAuthorization() == 2U,
        "finite owner without a successor authorizes its exact transport generation");
    const std::uint64_t eosSequence = diagnostic.audibleEnd.sequence;
    const std::uint64_t eosRenderCycles = runtime.diagnostics().renderCycles;
    output.consumeAll();
    waitForRenderProgress(
        runtime,
        eosRenderCycles,
        "finite EOS remains stable across later render cycles",
        4U);
    require(runtime.diagnostics().audibleEnd.sequence == eosSequence, "EOS never duplicates");
    require(runtime.diagnostics().outputEndOfStreamEvents == 1U, "pipeline EOS counted once");

    command.revision = 2U;
    command.controls = controls(2U);
    command.playing = false;
    require(runtime.publishCommand(command) == NativePlaybackPublishStatus::Accepted,
        "Pause after published audible EOS accepted");
    waitUntil([&] {
        const auto ordering = runtime.diagnostics().endOrdering;
        return ordering.commandConsumed.commandRevision == 2U &&
            !ordering.commandConsumed.desiredPlaying && !output.playing();
    }, "Pause after audible EOS is consumed and applied");
    const auto postEndOrdering = runtime.diagnostics().endOrdering;
    require(postEndOrdering.audibleEndPublished.sequence != 0U &&
            postEndOrdering.audibleEndPublished.sequence <
                postEndOrdering.commandPublished.sequence &&
            postEndOrdering.commandPublished.sequence <
                postEndOrdering.commandConsumed.sequence,
        "trace proves Pause was issued only after audible EOS publication");
    require(postEndOrdering.audibleEndPublished.commandRevision == 1U &&
            sameOwnership(
                postEndOrdering.audibleEndPublished.ownership,
                SongRenderOwnership{400U, 2U}) &&
            postEndOrdering.audibleEndPublished.desiredPlaying &&
            postEndOrdering.audibleEndPublished.outputPlaying &&
            postEndOrdering.audibleEndPublished.pipelineEndObserved &&
            !postEndOrdering.audibleEndPublished.commandUnread &&
            postEndOrdering.audibleEndPublished.pendingOutputFrames == 0U &&
            postEndOrdering.audibleEndPublished.queuedOutputFrames == 0U &&
            postEndOrdering.audibleEndPublished.terminalPositionValid &&
            postEndOrdering.audibleEndPublished.terminalAfterFrame,
        "late-Pause trace binds the exact owner and completed audible-end evidence");
    require(postEndOrdering.commandPublished.commandRevision == 2U &&
            sameOwnership(
                postEndOrdering.commandPublished.ownership,
                SongRenderOwnership{400U, 2U}) &&
            !postEndOrdering.commandPublished.desiredPlaying &&
            postEndOrdering.commandPublished.outputPlaying &&
            !postEndOrdering.commandPublished.commandUnread &&
            postEndOrdering.commandPublished.workerStateSequence ==
                postEndOrdering.audibleEndPublished.sequence,
        "late Pause publication observes the exact completed audible-end worker checkpoint");
    require(postEndOrdering.commandConsumed.commandRevision == 2U &&
            sameOwnership(
                postEndOrdering.commandConsumed.ownership,
                SongRenderOwnership{400U, 2U}) &&
            !postEndOrdering.commandConsumed.desiredPlaying &&
            postEndOrdering.commandConsumed.outputPlaying &&
            !postEndOrdering.commandConsumed.commandUnread,
        "late Pause consumption binds the exact owner and unread state");
    require(postEndOrdering.outputPlayingApplied.commandRevision == 2U &&
            sameOwnership(
                postEndOrdering.outputPlayingApplied.ownership,
                SongRenderOwnership{400U, 2U}) &&
            !postEndOrdering.outputPlayingApplied.desiredPlaying &&
            !postEndOrdering.outputPlayingApplied.outputPlaying &&
            !postEndOrdering.outputPlayingApplied.commandUnread,
        "late output pause binds the exact applied command and owner");
    require(runtime.diagnostics().audibleEnd.sequence == eosSequence,
        "Pause after audible EOS cannot retroactively erase or duplicate the event");
    const std::uint64_t acceptedPublicationSequence =
        postEndOrdering.commandPublished.sequence;
    require(runtime.publishCommand(command) == NativePlaybackPublishStatus::StaleRevision,
        "same-revision late Pause is rejected");
    require(runtime.diagnostics().endOrdering.commandPublished.sequence ==
            acceptedPublicationSequence,
        "rejected publication emits no accepted-publication diagnostic event");
    std::cout << "END_ORDER_TRACE pause-after-audible-end";
    writeEndOrderingEvent("publish", postEndOrdering.commandPublished);
    writeEndOrderingEvent("consume", postEndOrdering.commandConsumed);
    writeEndOrderingEvent("output-pause", postEndOrdering.outputPlayingApplied);
    writeEndOrderingEvent("eos", postEndOrdering.pipelineEndObserved);
    writeEndOrderingEvent("drain", postEndOrdering.physicalDrainObserved);
    writeEndOrderingEvent("audible-end", postEndOrdering.audibleEndPublished);
    std::cout << '\n';
    stopAndJoin(runtime);
}

void unloadQuiescesBanksClearsGhostAndCanSelectAgain() {
    SongLaneIngressBank first(4'096, 480);
    SongLaneIngressBank second(4'096, 480);
    configureLanes(first, 2, 1);
    configureLanes(second, 3, 1);
    offerLane(first, 0, 2, 0, 1'440);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, {500, 2}),
        NativePlaybackBankIntent{},
    };
    require(runtime.publishCommand(selectedCommand(
        1,
        NativePlaybackBank::First,
        {500, 2},
        banks,
        controls(1))) == NativePlaybackPublishStatus::Accepted, "unload initial select");
    require(runtime.start(), "unload start");
    waitUntil([&] { return runtime.diagnostics().acceptedOwnership.generation == 2U; },
        "unload owner accepted");
    waitUntil([&] { return output.commitCount() == 1U && output.queued() != 0U; },
        "old PCM committed before unload");
    require(output.presentFirstCurrentRecord(), "old owner presented");
    waitUntil([&] { return runtime.diagnostics().committedOwnership.generation == 2U; },
        "old owner committed");

    banks[0] = NativePlaybackBankIntent{2, false, {}, 0, 0};
    const auto unload = unselectedCommand(2, 3, banks, controls(2));
    require(runtime.publishCommand(unload) == NativePlaybackPublishStatus::Accepted,
        "unload accepted");
    waitUntil(
        [&] {
            const auto diagnostic = runtime.diagnostics();
            return diagnostic.committedOwnership.generation == 0U &&
                !diagnostic.banks[0].assigned &&
                diagnostic.banks[0].appliedIntentRevision == 2U && output.queued() == 0U;
        },
        "unload hard flush and bank quiescence ack");
    require(!output.playing(), "unload pauses output");
    const std::uint64_t flushesAfterUnload = output.flushCount();
    const std::uint64_t consumedBeforeRepeatedUnload =
        runtime.diagnostics().commandSnapshotsConsumed;
    require(runtime.publishCommand(unselectedCommand(3, 3, banks, controls(3))) ==
        NativePlaybackPublishStatus::Accepted, "repeated unload accepted");
    waitUntil([&] {
        return runtime.diagnostics().commandSnapshotsConsumed >
            consumedBeforeRepeatedUnload;
    }, "repeated unload command is consumed before idempotence is asserted");
    require(output.flushCount() == flushesAfterUnload, "repeated unload is idempotent");

    // Exact quiescence acknowledgement authorizes lazy lane storage replacement.
    first.releaseLane(0);
    require(first.configureLane(0, kSampleRate, 4), "lane safely reused after ack");
    offerLane(first, 0, 4, 0, 960);
    banks[0] = assignedBank(4, {501, 4});
    require(runtime.publishCommand(selectedCommand(
        4,
        NativePlaybackBank::First,
        {501, 4},
        banks,
        controls(4))) == NativePlaybackPublishStatus::Accepted, "post-unload select");
    waitUntil([&] { return runtime.diagnostics().acceptedOwnership.generation == 4U; },
        "post-unload owner accepted");
    stopAndJoin(runtime);
}

void administrativeFlushRetryIsBackpressureWhileStaleRemainsFault() {
    std::uint64_t processFlushBaseline = 0U;
    std::uint64_t processInvalidRenderBaseline = 0U;
    {
        SongLaneIngressBank first(256, 64);
        SongLaneIngressBank second(256, 64);
        FakeOutput output;
        NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
        const auto diagnostic = runtime.diagnostics();
        processFlushBaseline = diagnostic.processOutputFlushFailures;
        processInvalidRenderBaseline = diagnostic.processOutputInvalidRenderResults;
    }

    const auto runCase = [](
        NativeAudioFlushStatus forcedStatus,
        std::uint64_t expectedRetries,
        std::uint64_t expectedFailures,
        std::uint64_t token) {
        SongLaneIngressBank first(4'096, 480);
        SongLaneIngressBank second(4'096, 480);
        configureLanes(first, 2, 1);
        offerLane(first, 0, 2, 0, 1'440);
        FakeOutput output;
        NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
        std::array<NativePlaybackBankIntent, 2> banks{
            assignedBank(1, {token, 2}),
            NativePlaybackBankIntent{},
        };
        require(runtime.publishCommand(selectedCommand(
            1,
            NativePlaybackBank::First,
            {token, 2},
            banks,
            controls(1))) == NativePlaybackPublishStatus::Accepted,
            "typed-flush initial selection accepted");
        require(runtime.start(), "typed-flush runtime starts");
        waitUntil([&] {
            return runtime.diagnostics().acceptedOwnership.generation == 2U;
        }, "typed-flush owner accepted");
        waitUntil([&] { return output.presentFirstCurrentRecord(); }, "typed-flush owner presented");
        waitUntil([&] {
            return runtime.diagnostics().committedOwnership.generation == 2U;
        }, "typed-flush owner committed");

        const auto processFaultBaseline = runtime.diagnostics();
        output.forceNextFlushStatus(forcedStatus);
        banks[0] = NativePlaybackBankIntent{2, false, {}, 0, 0};
        require(runtime.publishCommand(unselectedCommand(2, 3, banks, controls(2))) ==
            NativePlaybackPublishStatus::Accepted,
            "typed-flush unload accepted");
        waitUntil([&] {
            const auto diagnostic = runtime.diagnostics();
            return diagnostic.committedOwnership.generation == 0U &&
                output.flushCount() == 1U;
        }, "typed-flush retry settles to one accepted invalidation");
        const auto diagnostic = runtime.diagnostics();
        require(diagnostic.outputFlushRetries == expectedRetries,
            "callback-ack flush backpressure classified only as retry");
        require(diagnostic.outputFlushFailures == expectedFailures,
            "stale/invalid flush remains a native fault");
        require(
            diagnostic.processOutputFlushFailures ==
                processFaultBaseline.processOutputFlushFailures + expectedFailures,
            "typed flush advances the process fault ledger exactly once when hard-failed");
        require(
            diagnostic.processOutputInvalidRenderResults ==
                processFaultBaseline.processOutputInvalidRenderResults,
            "valid typed-flush rendering does not advance process invalid-render faults");
        stopAndJoin(runtime);
    };

    runCase(
        NativeAudioFlushStatus::RetryAfterCallbackAcknowledgement,
        1U,
        0U,
        550U);
    runCase(NativeAudioFlushStatus::StaleGeneration, 0U, 1U, 551U);

    SongLaneIngressBank finalFirst(256, 64);
    SongLaneIngressBank finalSecond(256, 64);
    FakeOutput finalOutput;
    NativePlaybackRuntime finalRuntime(
        runtimeConfiguration(),
        finalFirst,
        finalSecond,
        finalOutput.port());
    const auto retained = finalRuntime.diagnostics();
    require(
        retained.processOutputFlushFailures == processFlushBaseline + 1U,
        "hard flush fault survives destruction and is visible from a replacement runtime");
    require(
        retained.processOutputInvalidRenderResults == processInvalidRenderBaseline,
        "replacement runtime sees the unchanged process invalid-render ledger");
}

void unloadRetriesRealCallbackPublicationThenReusesOneUnselectedEpoch() {
    SongLaneIngressBank first(4'096, 64);
    SongLaneIngressBank second(4'096, 64);
    configureLanes(first, 2U, 1U);
    configureLanes(second, 3U, 1U);
    offerLane(first, 0U, 2U, 0U, 1'440U);
    offerLane(second, 0U, 3U, 0U, 1'440U);
    FakeOutput output(4'096U, 64);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(4'096U, 64U),
        first,
        second,
        output.port());
    const SongRenderOwnership outgoing{560U, 2U};
    const SongRenderOwnership prewarm{561U, 3U};
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1U, outgoing),
        assignedBank(1U, prewarm),
    };

    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        outgoing,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "real-pending owner accepted");
    require(runtime.start(), "real-pending runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "outgoing non-empty publication remains callback-owned");
    // Deliberately do not present: the real FakeOutput commit is still callback-owned.

    banks[0] = NativePlaybackBankIntent{2U, false, {}, 0U, 0};
    require(runtime.publishCommand(unselectedCommand(2U, 4U, banks, controls(2U))) ==
        NativePlaybackPublishStatus::Accepted,
        "first unselected bank revision accepted");
    waitUntil([&] { return runtime.diagnostics().outputFlushRetries > 0U; },
        "real callback-owned commit produces typed flush retry");
    require(output.flushCount() == 0U,
        "retry cannot fabricate an accepted flush");
    require(runtime.diagnostics().outputFlushFailures == 0U,
        "callback acknowledgement backpressure is not a fault");
    require(!output.playing(), "unload pauses before retrying the empty generation");

    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    require(output.presentFirstCurrentRecord(),
        "callback acknowledges the exact outgoing non-empty publication");
    waitUntil([&] { return output.flushCount() == 1U; },
        "administrative flush progresses without diagnostics or an ingress wake");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return output.flushCount() == 1U &&
            diagnostic.committedOwnership.generation == 0U &&
            !diagnostic.banks[0].assigned &&
            diagnostic.banks[0].appliedIntentRevision == 2U &&
            diagnostic.banks[1].assigned;
    }, "one empty generation settles after callback acknowledgement");

    // Releasing the second bank belongs to the same logical empty output epoch. Its bank intent
    // may advance, but it must neither allocate nor physically flush another output generation.
    banks[1] = NativePlaybackBankIntent{2U, false, {}, 0U, 0};
    require(runtime.publishCommand(unselectedCommand(3U, 4U, banks, controls(3U))) ==
        NativePlaybackPublishStatus::Accepted,
        "same empty epoch newer bank revision accepted");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return !diagnostic.banks[1].assigned &&
            diagnostic.banks[1].appliedIntentRevision == 2U;
    }, "second bank unassigns without a second output invalidation");
    require(output.flushCount() == 1U,
        "same unselected epoch is physically idempotent");
    require(runtime.diagnostics().outputFlushFailures == 0U,
        "real retry plus same-generation dedupe remains fault-free");
    stopAndJoin(runtime);
}

void pausedSelectionsInstallMetadataWithoutAudibleEvidenceOrGhostOwner() {
    SongLaneIngressBank first(4'096, 480);
    SongLaneIngressBank second(4'096, 480);
    configureLanes(first, 2, 1);
    configureLanes(second, 3, 1);
    // The production reserve floor is 2,304 output frames and neutral DSP retains 1,792 source
    // frames of lookahead, so a non-EOS paused fixture needs 4,096 source frames to commit.
    offerLane(first, 0, 2, 0, 4'096, false);
    offerLane(second, 0, 3, 0, 4'096, false);
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, {1'000, 2}),
        assignedBank(1, {1'001, 3}),
    };
    require(runtime.publishCommand(selectedCommand(
        1, NativePlaybackBank::First, {1'000, 2}, banks, controls(1), false)) ==
        NativePlaybackPublishStatus::Accepted, "initial paused selection");
    require(runtime.start(), "paused-selection start");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return output.commitCount() == 1U &&
            diagnostic.committedOwnership.generation == 2U;
    }, "initial paused owner installed on nonempty commit");
    require(!output.playing(), "initial paused owner emits nothing");
    require(runtime.diagnostics().presentationEvidence.sequence == 0U,
        "paused install needs no fake audible evidence");
    require(runtime.diagnostics().audibleControl.sequence == 0U,
        "paused install emits no audible control evidence");

    require(runtime.publishCommand(selectedCommand(
        2, NativePlaybackBank::Second, {1'001, 3}, banks, controls(2), false)) ==
        NativePlaybackPublishStatus::Accepted, "paused replacement selection");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return output.commitCount() == 2U &&
            diagnostic.committedOwnership.generation == 3U;
    }, "paused replacement installed without consuming old ring");
    require(!output.playing(), "paused replacement remains silent");
    require(runtime.diagnostics().presentedPosition.sequence == 0U,
        "paused replacement exposes no stale position");
    require(runtime.diagnostics().audibleControl.sequence == 0U,
        "paused replacement emits no audible control evidence");
    const std::size_t replacementStart = output.currentGenerationFirstRecord();
    for (std::size_t index = replacementStart; index < output.recordCount(); ++index) {
        require(output.record(index).generation == 3U, "paused ring contains replacement only");
    }

    require(runtime.publishCommand(selectedCommand(
        3, NativePlaybackBank::Second, {1'001, 3}, banks, controls(3), true)) ==
        NativePlaybackPublishStatus::Accepted, "play installed paused owner");
    waitUntil([&] { return output.playing(); }, "play flag enabled after paused install");
    require(output.beginCount() == 2U && output.commitCount() == 2U,
        "play reuses already installed new ring");
    require(output.presentFirstCurrentRecord(), "first played callback is replacement ring");
    waitUntil([&] {
        const auto position = runtime.diagnostics().presentedPosition;
        return position.generation == 3U && position.selectionToken == 1'001U;
    }, "first played position cannot belong to old paused owner");
    stopAndJoin(runtime);
}

void pauseAfterCommitBeforeCallbackInstallsExactOwner() {
    SongLaneIngressBank first(4'096, 480);
    SongLaneIngressBank second(4'096, 480);
    configureLanes(first, 2, 1);
    offerLane(first, 0, 2, 0, 4'096, false);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    std::array<NativePlaybackBankIntent, 2> banks{
        assignedBank(1, {1'100, 2}),
        NativePlaybackBankIntent{},
    };
    require(runtime.publishCommand(selectedCommand(
        1, NativePlaybackBank::First, {1'100, 2}, banks, controls(1), true)) ==
        NativePlaybackPublishStatus::Accepted, "commit-then-pause initial Play");
    require(runtime.start(), "commit-then-pause start");
    waitUntil([&] {
        return output.commitCount() == 1U && output.playing();
    }, "handoff committed and Play published before callback");
    require(runtime.diagnostics().committedOwnership.generation == 0U,
        "owner still awaits first audible callback");

    require(runtime.publishCommand(selectedCommand(
        2, NativePlaybackBank::First, {1'100, 2}, banks, controls(2), false)) ==
        NativePlaybackPublishStatus::Accepted, "Pause wins before callback presentation");
    waitUntil([&] {
        const auto diagnostic = runtime.diagnostics();
        return !output.playing() && diagnostic.committedOwnership.generation == 2U &&
            diagnostic.committedOwnership.selectionToken == 1'100U;
    }, "paused committed ring installs exact owner without callback evidence");
    require(runtime.diagnostics().presentationEvidence.sequence == 0U,
        "commit-then-pause manufactures no presentation evidence");
    require(runtime.diagnostics().presentedPosition.sequence == 0U,
        "commit-then-pause exposes no stale position");
    require(runtime.diagnostics().audibleControl.sequence == 0U,
        "commit-then-pause emits no audible control evidence");
    require(output.beginCount() == 1U && output.commitCount() == 1U,
        "Pause does not restage the already committed ring");

    require(runtime.publishCommand(selectedCommand(
        3, NativePlaybackBank::First, {1'100, 2}, banks, controls(3), true)) ==
        NativePlaybackPublishStatus::Accepted, "resume commit-then-pause owner");
    waitUntil([&] { return output.playing(); }, "installed paused owner resumes");
    require(output.presentFirstCurrentRecord(), "resumed callback presents committed owner");
    waitUntil([&] {
        const auto position = runtime.diagnostics().presentedPosition;
        return position.generation == 2U && position.selectionToken == 1'100U;
    }, "resumed callback position belongs to exact committed owner");
    stopAndJoin(runtime);
}
