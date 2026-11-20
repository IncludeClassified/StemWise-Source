void stopDuringUnpublishedRetainedBodyCopyReleasesTheBankAndAcceptsANewOwner() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{28'903U, 2U}, next{28'904U, 4U};
    constexpr std::uint64_t firstFrame = 48'000U;
    constexpr std::size_t bodyFrames = 16'384U;
    SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
    configureLanes(first, owner.generation, 7U);
    configureLanes(second, next.generation, 1U);
    offerLane(first, 0U, owner.generation, firstFrame, bodyFrames, false);
    offerLane(second, 0U, next.generation, firstFrame, 4'800U, true);
    FakeOutput output(4'096U, 256U);
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept {
        cache_test::afterRetainedBodyCopySlice.store(nullptr);
        stopAndJoin(runtime);
    });
    const auto bank = assignedBank(1U, owner, firstFrame);
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        {bank, {}}, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "pending-copy STOP starts an actual paused owner with configured optional lanes");
    waitUntil([&] {
        const auto ready = runtime.renderReadinessDiagnostics();
        return sameOwnership(ready.ownership, owner) &&
            ready.lanes[0U].endTimelineFrameExclusive >= firstFrame + bodyFrames &&
            sameOwnership(runtime.diagnostics().committedOwnership, owner);
    }, "the exact owner retains the real ordinary Original body before loop preparation");
    auto head = managedLoopIntent(1U, owner, firstFrame);
    head.boundaryMode = LoopHeadBoundaryMode::RetainedWindow;
    head.cacheKey.endTimelineFrameExclusive = firstFrame + 2'048U;
    prepareFullManagedLoopHead(runtime, head, 701U, 1U, owner);
    require(output.loopCutoverCommitCount() == 0U,
        "independent head admission has not published a physical cutover");
    static NativePlaybackRuntime *stoppingRuntime{};
    static std::atomic<NativePlaybackPublishStatus> stopPublication{NativePlaybackPublishStatus::InvalidSnapshot};
    stoppingRuntime = &runtime;
    stopPublication.store(NativePlaybackPublishStatus::InvalidSnapshot);
    cache_test::afterRetainedBodyCopySlice.store(+[]() noexcept {
        cache_test::afterRetainedBodyCopySlice.store(nullptr);
        stopPublication.store(stoppingRuntime->publishCommand(unselectedCommand(2U, 3U,
            {NativePlaybackBankIntent{.revision = 2U}, NativePlaybackBankIntent{}}, controls(2U))),
            std::memory_order_release);
    });
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, true,
        firstFrame, firstFrame + 1'024U) == NativePlaybackPublishStatus::Accepted,
        "actual loop preparation must yield while copying a body larger than one bounded slice");
    waitUntil([&] { return stopPublication.load(std::memory_order_acquire) == NativePlaybackPublishStatus::Accepted; },
        "the real bounded body-copy yield publishes exact owner withdrawal");
    require(output.loopCutoverCommitCount() == 0U,
        "STOP was published before any continuation acquired physical cutover ownership");
    waitUntil([&] {
        const auto diagnostics = runtime.diagnostics();
        return !diagnostics.banks[0U].assigned && diagnostics.banks[0U].appliedIntentRevision == 2U;
    }, "STOP must retire the bank pinned by an unpublished retained-body copy");
    require(runtime.diagnostics().banks[0U].rejectedBankIntents == 0U,
        "pending raw-copy cancellation cannot poison the exact unassignment as a permanent preparation failure");
    const auto before = output.recordCount();
    auto replacement = assignedBank(1U, next, firstFrame, 0U);
    replacement.stemPackageHash = 0U;
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::Second, next,
        {NativePlaybackBankIntent{.revision = 2U}, replacement}, controls(3U), false)) == NativePlaybackPublishStatus::Accepted,
        "a new exact owner follows the completed administrative retirement");
    static_cast<void>(waitForRecord(output, before, [=](const FakeOutputRecord &record) {
        return record.token == next.selectionToken && record.generation == next.generation &&
            record.sourceStart == static_cast<double>(firstFrame) && record.frames != 0U && record.peak > .001F;
    }, "the next owner renders actual independently offered Original PCM", 4'000U, &runtime));
    waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, next); },
        "the next owner commits without a stale pending-copy preparation receipt");
}

void failedRetainedWindowReadCannotMasqueradeAsPendingInput() {
    using namespace stemwise::audio::engine;
    constexpr std::uint64_t a = 96'000U, b = 480'000U, cursor = 315'840U;
    constexpr SongRenderOwnership owner{43U, 456U};
    LoopHeadCache head;
    std::atomic<unsigned> wakes{0U};
    head.enableAsyncReads([](void *context) noexcept {
        static_cast<std::atomic<unsigned> *>(context)->fetch_add(1U);
    }, &wakes);
    const LoopHeadContentKey key{111U, 222U, 0U, 1U, 1U, a, b};
    const std::vector<float> pcm(static_cast<std::size_t>(b - a) * 2U, .08F);
    require(head.beginStaging(key, 1U, LoopHeadBoundaryMode::RetainedWindow) == LoopHeadBeginStatus::Accepted &&
        head.writeStaging(1U, 0U, a, pcm.data(), pcm.size() / 2U).status == LoopHeadWriteStatus::LaneComplete &&
        head.commitStaging(1U) == LoopHeadCommitStatus::Accepted, "failed-read fixture commits actual retained PCM");
    auto view = head.acquireActive(key);
    std::array<float, 256U * 2U> copied{};
    LoopHeadCopyStatus status{};
    require(!view.copyLane(0U, a - 1U, copied.data(), 256U, 256U, &status) &&
        status == LoopHeadCopyStatus::Unavailable, "invalid retained ranges cannot claim asynchronous progress");
    require(!view.copyLane(0U, cursor, copied.data(), 256U, 256U, &status) &&
        status == LoopHeadCopyStatus::Pending && wakes.load() != 0U, "cold valid retained read is explicitly pending");
    SongRenderPipeline pipeline(runtimeConfiguration(16'384U, 256U).pipeline);
    require(pipeline.beginSong(owner, owner.generation, cursor, 0) == SongRenderResetStatus::Ok,
        "exact failed-read owner begins at its truthful source interval");
    require(pipeline.bindRetainedLoopHead(owner, head.acquireActive(key)), "exact live owner pins the healthy pending source");
    auto canonical = controls(1U);
    canonical.applicationEpoch = 1U;
    require(pipeline.submitControls(canonical) == SongRenderControlStatus::Accepted,
        "direct Pipeline invocation supplies the canonical application epoch normally stamped by Runtime");
    const auto restore = onScopeExit([]() noexcept { cache_test::fault.store(0); });
    cache_test::observed.store(false);
    cache_test::fault.store(5);
    require(head.serviceAsyncReads() && cache_test::observed.load(), "real retained-window disk read fails at its I/O owner");
    cache_test::fault.store(0);
    require(!view.copyLane(0U, cursor, copied.data(), 256U, 256U, &status) &&
        status == LoopHeadCopyStatus::ReadFailure && view.readFailureLaneMask() == 1U,
        "a failed read revokes source authority instead of remaining pending");
    std::array<float, 256U * stemwise::audio::kStemwiseMultibusStride> rendered{};
    const auto failure = pipeline.renderMultibus(owner, rendered.data(), 256U, 256U);
    require(failure.status == SongRenderStatus::MixerFailure && failure.framesProduced == 0U &&
        !pipeline.awaitingCacheRead() && pipeline.sourceTimelineFrame() == static_cast<double>(cursor),
        "a genuine failed retained read remains explicit failure, never pending or accepted PCM");
}

void cacheRebindingRetainsAcceptedPcmWhileReaderAdmissionWaits(bool crossBank) {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership initial{28'902U, 2U}, selected{28'902U, 3U};
    SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
    configureLanes(first, initial.generation, 1U);
    configureLanes(second, selected.generation, 1U);
    offerLane(first, 0U, initial.generation, 0U, 20'000U, true);
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept {
        denyReadConsumerGeneration.store(0U); runtime.requestStop(); runtime.join();
    });
    auto original = assignedBank(1U, initial, 0U, 0U); original.cacheFrameUpperBound = 20'000U;
    original.stemPackageHash = 0U;
    std::array<NativePlaybackBankIntent, 2U> banks{original, {}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, initial,
        banks, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "admission retry starts a real completed Original");
    waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
        return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 1U; },
        "accepted canonical Original is installed before replacement");
    const std::size_t bank = crossBank ? 1U : 0U;
    if (!crossBank) {
        first.releaseLane(0U);
        require(first.configureLane(0U, kSampleRate, selected.generation), "same-bank replacement owns new ingress");
    }
    banks[bank] = assignedBank(crossBank ? 1U : 2U, selected, 4'800U, 0U);
    banks[bank].stemPackageHash = 0U;
    banks[bank].cacheFrameUpperBound = 20'000U;
    readConsumerAdmissionDenials.store(0U); denyReadConsumerGeneration.store(selected.generation);
    require(runtime.publishCommand(selectedCommand(2U,
        crossBank ? NativePlaybackBank::Second : NativePlaybackBank::First, selected,
        banks, controls(2U), false)) == NativePlaybackPublishStatus::Accepted,
        "replacement requests the same canonical content and a new exact render owner");
    waitUntil([&] { return readConsumerAdmissionDenials.load() != 0U; },
        "exact post-rebind reader admission reaches the held failure boundary");
    const CurrentSongCacheKey rebound{selected.selectionToken, selected.generation, original.songIdentityHash,
        original.originalContentHash, original.stemPackageHash};
    std::array<float, 128U> actual{};
    require(runtime.readPcmCacheForAudit(rebound, 0U, 4'800U, actual.data(), 64U),
        "pending reader admission must retain accepted canonical PCM under its rebound exact key");
    waitUntil([&] { return sameOwnership(runtime.renderReadinessDiagnostics().ownership, selected); },
        "held admission publishes readiness for the exact new pipeline owner");
    auto stale = rebound; stale.generation = initial.generation;
    require(!runtime.readPcmCacheForAudit(stale, 0U, 4'800U, actual.data(), 64U) &&
        !runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner,
        "pending reader cannot claim installed readiness or the retired cache generation");
    const auto expected = tone(64U, 4'800U, .025F, 220.0);
    require(std::equal(actual.begin(), actual.end(), expected.begin()), "held admission changed accepted Original samples");
    denyReadConsumerGeneration.store(0U); runtime.notifyIngressAvailable();
    waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
        return sameOwnership(ready.ownership, selected) && ready.currentSongCacheBoundToSelectedOwner &&
            ready.currentSongCacheCompleteLaneMask == 1U; }, "exact cache reader retries after temporary denial");
    static_cast<void>(waitForRecord(output, 0U, [&](const FakeOutputRecord &record) {
        return record.token == selected.selectionToken && record.generation == selected.generation &&
            record.sourceStart == 4'800.0 && record.frames != 0U && record.peak > .001F;
    }, "replacement renders the accepted cached seek without any new decoder PCM", 4'000U, &runtime));
    stopAndJoin(runtime);
}

void asyncPipelineReplacementWakesTheIdleIoOwner() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{28'900U, 2U};
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 3U, 4U, 0U};
    std::atomic<std::uint64_t> wakes{};
    const auto configuration = runtimeConfiguration().pipeline;
    NativePlaybackResources resources(configuration, {}, +[](void *context) noexcept {
        static_cast<std::atomic<std::uint64_t> *>(context)->fetch_add(1U);
    }, &wakes);
    CurrentSongPcmCache *cache = nullptr;
    waitUntil([&] { bool finished = false; cache = resources.createCache(key, 1U, 4096U, finished);
        return cache != nullptr; }, "real idle I/O owner constructs the asynchronous cache");
    std::array<std::unique_ptr<SongRenderPipeline>, 3U> readers;
    for (auto &reader : readers) {
        reader = std::make_unique<SongRenderPipeline>(configuration);
        require(reader->beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok &&
            reader->bindCurrentSongPcmCache(owner, cache), "three real asynchronous pipeline readers admit");
    }
    SongRenderPipeline replacement(configuration);
    require(replacement.beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok,
        "replacement has an exact render owner");
    readers[1U].reset();
    const auto before = wakes.load();
    require(!replacement.bindCurrentSongPcmCache(owner, cache),
        "render-side admission cannot reclaim the retired cache reference itself");
    waitUntil([&] { return wakes.load() > before; },
        "retired reader reclamation wakes the otherwise idle render coordinator");
    require(replacement.bindCurrentSongPcmCache(owner, cache),
        "actual I/O-owner reclamation admits the exact replacement without manual service");
}

void coldLoopSpareCannotPublishActiveStarvationEvidence() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership initial{28'901U, 2U}, selected{28'901U, 3U};
    SongLaneIngressBank first(65'536U, 480U), second(65'536U, 480U);
    configureLanes(first, initial.generation, 1U);
    offerLane(first, 0U, initial.generation, 0U, 60'000U, true);
    FakeOutput output(4'096U, 256U);
    output.setAutoAcknowledgePausedCommit(true);
    auto configuration = runtimeConfiguration(65'536U, 256U);
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept {
        releaseBlocked.store(true); fault.store(0); runtime.requestStop(); runtime.join();
    });
    auto bank = assignedBank(1U, initial, 0U, 0U); bank.cacheFrameUpperBound = 60'000U;
    bank.stemPackageHash = 0U;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, initial,
        {bank, {}}, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "cold-spare fixture starts a real canonical Original");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 1U; },
        "frame-zero producer completes canonical Original before its decoder seek");
    first.releaseLane(0U);
    require(first.configureLane(0U, kSampleRate, selected.generation), "seek owns a new exact live ingress");
    bank = assignedBank(2U, selected, 48'000U, 0U); bank.cacheFrameUpperBound = 60'000U;
    bank.stemPackageHash = 0U;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, selected,
        {bank, {}}, controls(2U), false)) == NativePlaybackPublishStatus::Accepted,
        "selected suffix reuses the committed canonical prefix");
    waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, selected); },
        "seek is acknowledged by the actual paused output owner");
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, selected,
        {bank, {}}, controls(3U), true)) == NativePlaybackPublishStatus::Accepted,
        "exact selected suffix resumes");
    waitUntil([&] { return output.playing() && output.queued() >= configuration.minimumOutputReserveFrames(); },
        "active queue has real committed PCM before cold spare work");
    const auto needBefore = runtime.diagnostics().outputNeedDataEvents;
    blocked.store(false); blockTimedOut.store(false); releaseBlocked.store(false); fault.store(8);
    require(runtime.publishDirectLoopPolicy(1U, selected.selectionToken, selected.generation,
        true, 24'000U, 30'000U) == NativePlaybackPublishStatus::Accepted,
        "dormant exact loop requests a genuinely cold canonical A");
    waitUntil([&] { return blocked.load() && runtime.diagnostics().outputNeedDataEvents > needBefore; },
        "cold continuation actually reaches missing canonical PCM while I/O is held");
    const auto cold = runtime.diagnostics();
    require(output.loopCutoverBeginCount() != 0U && output.queued() >= configuration.minimumOutputReserveFrames() &&
        cold.needOriginalData.lowWaterEvidenceSequence == 0U && cold.needStemData.lowWaterEvidenceSequence == 0U &&
        cold.loopBoundaryPending.lowWaterEvidenceSequence == 0U && !blockTimedOut.load(),
        "zero-frame cold spare cannot classify the healthy committed active queue as starved");
    releaseBlocked.store(true); fault.store(0);
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; }, "cold spare later publishes real prepared PCM");
    require(runtime.publishDirectLoopPolicy(2U, selected.selectionToken, selected.generation,
        false, 0U, 0U) == NativePlaybackPublishStatus::Accepted, "dormant loop cancels before active deficit");
    constexpr SongRenderOwnership starved{28'903U, 4U};
    configureLanes(second, starved.generation, 1U);
    offerLane(second, 0U, starved.generation, 0U, 4'096U, false);
    auto next = assignedBank(1U, starved, 0U, 0U);
    next.stemPackageHash = 0U; ++next.songIdentityHash; ++next.originalContentHash;
    require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::Second, starved,
        {bank, next}, controls(4U), true)) == NativePlaybackPublishStatus::Accepted,
        "independent incomplete Original becomes the genuine active deficit owner");
    waitUntil([&] { return output.commitCount() == 3U; }, "new active owner commits its actual PCM");
    require(output.presentFirstCurrentRecord(), "callback acknowledges the exact deficit owner");
    waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, starved); },
        "deficit diagnostics bind to the real committed replacement");
    waitUntil([&] {
        output.consumeAll(); runtime.notifyIngressAvailable();
        const auto reason = runtime.diagnostics().needOriginalData;
        return reason.lowWaterEvidenceSequence != 0U && reason.lastGeneration == starved.generation;
    }, "actual active prefix exhaustion still publishes exact-owner starvation evidence");
    stopAndJoin(runtime);
}

void optionalCacheReadFailureReportsItsLaneAndKeepsOriginal(bool stems) {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership owner{26'051U, 2U};
    SongLaneIngressBank first(32768U, 480U), second(32768U, 480U);
    configureLanes(first, owner.generation, 7U);
    for (std::size_t lane = 0U; lane < 7U; ++lane) offerLane(first, lane, owner.generation, 0U, 16384U, true);
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(32768U, 256U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&] { fault.store(0); stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, 0U, 6U); bank.cacheFrameUpperBound = 16384U;
    std::array<NativePlaybackBankIntent, 2U> banks{bank, NativePlaybackBankIntent{}};
    auto control = controls(1U); control.mixer.sourceMode = stems ? MixerSourceMode::Stems : MixerSourceMode::Original;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        banks, control, false)) == NativePlaybackPublishStatus::Accepted && runtime.start(), "optional read starts");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 127U &&
        output.queued() >= runtimeConfiguration(32768U, 256U).minimumOutputReserveFrames(); }, "optional read fixture fully primes");
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, bank.songIdentityHash,
        bank.originalContentHash, bank.stemPackageHash};
    std::array<float, 512U> pcm{};
    fault.store(5); observed.store(false);
    require(!runtime.readPcmCacheForAudit(key, 3U, 12288U, pcm.data(), 256U), "optional canonical read actually rejects I/O");
    fault.store(0);
    require(observed.load(), "optional read fault did not reach canonical file I/O");
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, owner,
        banks, control, true)) == NativePlaybackPublishStatus::Accepted, "optional read playback resumes");
    waitUntil([&] { output.consumeAll(); runtime.notifyIngressAvailable();
        return runtime.controlProgress().readiness.failureGeneration == owner.generation; },
        "runtime reports exact failed optional lane");
    require(runtime.controlProgress().readiness.failureLane == 3,
        "optional read failure was misreported as Original failure");
    banks[0U].revision = 2U; banks[0U].stemLaneCount = 0U; banks[0U].stemPackageHash = 0U;
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, owner,
        banks, controls(2U), true)) == NativePlaybackPublishStatus::Accepted, "optional read package retires");
    waitUntil([&] { output.consumeAll(); runtime.notifyIngressAvailable();
        return runtime.diagnostics().banks[0U].appliedIntentRevision == 2U; }, "optional read retirement settles");
    auto originalKey = key; originalKey.stemPackageHash = 0U;
    require(runtime.readPcmCacheForAudit(originalKey, 0U, 0U, pcm.data(), 256U), "Original remains readable after optional failure");
    const auto expected = tone(256U, 0U, .025F, 220.0);
    require(std::equal(pcm.begin(), pcm.end(), expected.begin()), "optional read failure changed Original PCM");
    waitUntil([&] { return runtime.controlProgress().readiness.failureLane == -1; }, "retired package failure loses authority");
    for (std::size_t lane = 1U; lane < 7U; ++lane) {
        first.releaseLane(lane);
        require(first.configureLane(lane, kSampleRate, owner.generation), "optional read retry recreates exact ingress");
        offerLane(first, lane, owner.generation, 0U, 16384U, true);
    }
    banks[0U] = bank; banks[0U].revision = 3U; ++banks[0U].stemPackageHash;
    require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::First, owner,
        banks, controls(3U), false)) == NativePlaybackPublishStatus::Accepted, "optional read retry attaches repaired package");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 127U; },
        "same-owner repaired optional cache seals again");
    auto replacementKey = key; ++replacementKey.stemPackageHash;
    fault.store(5); observed.store(false);
    require(!runtime.readPcmCacheForAudit(replacementKey, 3U, 12288U, pcm.data(), 256U), "replacement read fault reaches cache");
    fault.store(0); runtime.notifyIngressAvailable();
    waitUntil([&] { const auto failure = runtime.controlProgress().readiness;
        return failure.failureLane == 3 && failure.failureGeneration == owner.generation; }, "replacement package failure has fresh authority");

}

void optionalAdmissionFallbackSurvivesTwoExactSeeks() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership firstOwner{26'001U, 2U}, spareOwner{26'001U, 3U}, finalOwner{26'001U, 4U};
    SongLaneIngressBank first(32768U, 480U), second(32768U, 480U);
    configureLanes(first, firstOwner.generation, 1U);
    configureLanes(second, spareOwner.generation, 1U);
    offerLane(first, 0U, firstOwner.generation, 0U, 20000U, true);
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(32768U, 256U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&] { fault.store(0); stopAndJoin(runtime); });
    auto bank = assignedBank(1U, firstOwner, 0U, 0U);
    bank.stemPackageHash = 0U;
    bank.cacheFrameUpperBound = 20000U;
    std::array<NativePlaybackBankIntent, 2U> banks{bank, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, firstOwner,
        banks, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(), "two-seek cache starts");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 1U; },
        "Original finishes before optional expansion");
    fault.store(2);
    banks[0U].revision = 2U; banks[0U].stemLaneCount = 8U; banks[0U].stemPackageHash = 79U;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, firstOwner,
        banks, controls(2U), false)) == NativePlaybackPublishStatus::Accepted, "optional package update accepted");
    waitUntil([&] { return runtime.diagnostics().banks[0U].appliedIntentRevision == 2U; }, "refused expansion applies ordinary stems");
    require(runtime.diagnostics().canonicalPcmIo.admittedLaneCount == 1U &&
        runtime.diagnostics().canonicalPcmIo.lastAdmissionStatus == CurrentSongCacheStorageStatus::InsufficientSpace,
        "optional expansion actually refused");
    fault.store(0);
    offerLane(second, 0U, spareOwner.generation, 4800U, 15200U, true);
    banks[1U] = assignedBank(1U, spareOwner, 4800U, 8U);
    banks[1U].cacheFrameUpperBound = 20000U;
    banks[1U].stemPackageHash = 79U;
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::Second, spareOwner,
        banks, controls(3U), false)) == NativePlaybackPublishStatus::Accepted, "spare seek accepted");
    waitUntil([&] { const auto r = runtime.renderReadinessDiagnostics(); return sameOwnership(r.ownership, spareOwner) &&
        r.currentSongCacheBoundToSelectedOwner && r.currentSongCacheCompleteLaneMask == 1U; }, "Original promotes through spare seek");
    require(second.lane(0U)->advanceGeneration(finalOwner.generation), "in-place seek advances exact decoder");
    offerLane(second, 0U, finalOwner.generation, 6000U, 14000U, true);
    banks[1U].revision = 2U; banks[1U].ownership = finalOwner; banks[1U].firstTimelineFrame = 6000U;
    require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::Second, finalOwner,
        banks, controls(4U), false)) == NativePlaybackPublishStatus::Accepted, "in-place seek accepted");
    waitUntil([&] { return runtime.diagnostics().banks[1U].appliedIntentRevision == 2U; }, "in-place seek applies");
    waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
        return sameOwnership(ready.ownership, finalOwner) && ready.currentSongCacheBoundToSelectedOwner &&
            ready.currentSongCacheCompleteLaneMask == 1U; },
        "second seek discarded completed Original after optional admission refusal");
    const CurrentSongCacheKey key{finalOwner.selectionToken, finalOwner.generation,
        banks[1U].songIdentityHash, banks[1U].originalContentHash, banks[1U].stemPackageHash};
    std::array<float, 512U> pcm{};
    waitUntil([&] { return runtime.pcmCacheRangeReady(key, 1U, 6000U, 6256U); },
        "second seek retains exact Original coverage");
    require(runtime.readPcmCacheForAudit(key, 0U, 6000U, pcm.data(), 256U), "second seek reads actual retained PCM");
    const auto expected = tone(256U, 6000U, .025F, 220.0);
    require(std::equal(pcm.begin(), pcm.end(), expected.begin()), "two seeks changed Original PCM");
    require(runtime.publishDirectLoopPolicy(1U, finalOwner.selectionToken, finalOwner.generation,
        true, 6000U, 12000U, 0U, true) == NativePlaybackPublishStatus::Accepted, "fallback loop intent accepted");
    waitUntil([&] { return output.loopCutoverCommitCount() != 0U; }, "retained Original prepares usable loop coverage");
}

void supersededOptionalAdmissionReleasesReceiptAndReservation(bool reject) {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership owner{26'011U, 2U};
    SongLaneIngressBank first(32768U, 480U), second(32768U, 480U);
    configureLanes(first, owner.generation, 1U);
    offerLane(first, 0U, owner.generation, 0U, 20000U, true);
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(32768U, 256U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&] { releaseBlocked.store(true); fault.store(0); stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, 0U, 0U);
    bank.stemPackageHash = 0U; bank.cacheFrameUpperBound = 20000U;
    std::array<NativePlaybackBankIntent, 2U> banks{bank, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        banks, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(), "admission supersession starts");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 1U; }, "supersession Original is complete");
    blocked.store(false); blockTimedOut.store(false); releaseBlocked.store(false); fault.store(6);
    banks[0U].revision = 2U; banks[0U].stemLaneCount = 8U; banks[0U].stemPackageHash = 79U;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, owner,
        banks, controls(2U), false)) == NativePlaybackPublishStatus::Accepted, "expansion begins");
    waitUntil([&] { return blocked.load(); }, "actual I/O admission is blocked");
    banks[0U].revision = 3U; banks[0U].stemLaneCount = 0U; banks[0U].stemPackageHash = 0U;
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, owner,
        banks, controls(3U), false)) == NativePlaybackPublishStatus::Accepted, "Original supersedes pending expansion");
    waitUntil([&] { return runtime.diagnostics().commandSnapshotsConsumed >= 3U; }, "latest Original intent consumed while admission blocked");
    const auto heldCycle = runtime.diagnostics().renderCycles;
    waitUntil([&] { runtime.notifyIngressAvailable();
        return runtime.diagnostics().renderCycles >= heldCycle + 8U; },
        "render owner continues servicing output while the superseded admission is held");
    require(runtime.diagnostics().banks[0U].appliedIntentRevision < 3U,
        "Original intent cannot acknowledge before its superseded admission receipt is reconciled");
    fault.store(reject ? 2 : 0); releaseBlocked.store(true);
    waitUntil([&] { runtime.notifyIngressAvailable(); return runtime.diagnostics().banks[0U].appliedIntentRevision == 3U; },
        "superseded admission receipt must not pin the resource slot");
    fault.store(0);
    const auto result = runtime.diagnostics();
    require(!blockTimedOut.load() && result.canonicalPcmIo.admittedLaneCount == 1U &&
        result.canonicalPcmIo.reservedBytes == 20000U * 8U, "superseded accepted expansion retained optional reservation");
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, bank.songIdentityHash, bank.originalContentHash, 0U};
    std::array<float, 512U> pcm{};
    require(runtime.readPcmCacheForAudit(key, 0U, 0U, pcm.data(), 256U), "supersession lost exact Original prefix");
    const auto expected = tone(256U, 0U, .025F, 220.0);
    require(std::equal(pcm.begin(), pcm.end(), expected.begin()), "supersession changed Original PCM");
}

void incompleteOriginalPrefixSurvivesStemIdentityChanges(bool supersedeAdmission, bool supersedeCreation) {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership owner{26'012U, 6U};
    constexpr std::size_t prefixFrames = 8'192U, totalFrames = 32'768U;
    constexpr auto committedPrefixFrames = prefixFrames - CurrentSongPcmCache::kChunkFrames;
    SongLaneIngressBank first(65'536U, 480U), second(65'536U, 480U);
    configureLanes(first, owner.generation, 3U);
    offerLane(first, 0U, owner.generation, 0U, prefixFrames, false);
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(65'536U, 256U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&] {
        releaseBlocked.store(true); fault.store(0); stopAndJoin(runtime);
    });
    auto bank = assignedBank(1U, owner, 0U, 0U);
    bank.stemPackageHash = 0U; bank.cacheFrameUpperBound = totalFrames;
    std::array<NativePlaybackBankIntent, 2U> banks{bank, NativePlaybackBankIntent{}};
    const CurrentSongCacheKey originalKey{owner.selectionToken, owner.generation,
        bank.songIdentityHash, bank.originalContentHash, 0U};
    const auto publish = [&](std::uint64_t revision) {
        require(runtime.publishCommand(selectedCommand(revision, NativePlaybackBank::First, owner,
            banks, controls(revision), false)) == NativePlaybackPublishStatus::Accepted,
            "same-generation source intent is accepted");
    };
    const auto holdAdmission = [&] {
        blocked.store(false); blockTimedOut.store(false); releaseBlocked.store(false); fault.store(6);
    };
    if (supersedeCreation) holdAdmission();
    publish(1U);
    require(runtime.start(), "partial Original owner starts");
    if (supersedeCreation) {
        waitUntil([&] { return blocked.load(); }, "initial cache creation reaches actual storage admission");
    } else {
        waitUntil([&] {
            const auto ready = runtime.renderReadinessDiagnostics();
            return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 0U &&
                runtime.diagnostics().banks[0U].lanes[0U].appendedFrames == prefixFrames &&
                runtime.pcmCacheRangeReady(originalKey, 1U, committedPrefixFrames - 256U, committedPrefixFrames);
        }, "actual unsealed Original prefix is committed before changing package identity");
        require(runtime.diagnostics().banks[0U].lanes[0U].appendedFrames == prefixFrames,
            "the incomplete prefix has left the decoder ingress exactly once");
        holdAdmission();
    }
    banks[0U].revision = 2U; banks[0U].stemLaneCount = 2U; banks[0U].stemPackageHash = 79U;
    publish(2U);
    waitUntil([&] { return blocked.load() && runtime.diagnostics().commandSnapshotsConsumed >= 2U; },
        "late package is consumed while its actual cache admission is held");
    std::uint64_t revision = 2U;
    if (supersedeAdmission) {
        banks[0U].revision = ++revision; banks[0U].stemLaneCount = 0U; banks[0U].stemPackageHash = 0U;
        publish(revision);
        waitUntil([&] { return runtime.diagnostics().commandSnapshotsConsumed >= revision; },
            "explicit Original reverses the held package admission");
    }
    fault.store(0); releaseBlocked.store(true);
    waitUntil([&] { runtime.notifyIngressAvailable();
        return runtime.diagnostics().banks[0U].appliedIntentRevision == revision; },
        "same-owner package change must reconcile its actual admission receipt");
    require(!blockTimedOut.load(), "resource handshake was released before its existing deadline");
    if (!supersedeAdmission) {
        const CurrentSongCacheKey packageKey{owner.selectionToken, owner.generation,
            bank.songIdentityHash, bank.originalContentHash, 79U};
        waitUntil([&] { return runtime.pcmCacheRangeReady(packageKey, 1U,
            committedPrefixFrames - 256U, committedPrefixFrames); },
            "late package preserves the incomplete Original under its exact new identity");
        std::array<float, 512U> prefix{};
        require(runtime.readPcmCacheForAudit(packageKey, 0U, committedPrefixFrames - 256U, prefix.data(), 256U),
            "the real adopted store retains the end of the accepted prefix");
        const auto expected = tone(256U, committedPrefixFrames - 256U, .025F, 220.0);
        require(std::equal(prefix.begin(), prefix.end(), expected.begin()), "package adoption changed Original PCM");
        banks[0U].revision = ++revision; banks[0U].stemLaneCount = 0U; banks[0U].stemPackageHash = 0U;
        publish(revision);
        waitUntil([&] { runtime.notifyIngressAvailable();
            return runtime.diagnostics().banks[0U].appliedIntentRevision == revision; },
            "actual optional retirement keeps the same incomplete Original owner");
        require(!runtime.pcmCacheRangeReady(packageKey, 1U, committedPrefixFrames - 256U, committedPrefixFrames),
            "retired package identity cannot read the retained Original");
    }
    offerLane(first, 0U, owner.generation, prefixFrames, totalFrames - prefixFrames, true);
    runtime.notifyIngressAvailable();
    waitUntil([&] {
        const auto ready = runtime.renderReadinessDiagnostics();
        require(ready.lanes[0U].ingressFailureGeneration == 0U,
            "same-generation cache changes lost the accepted Original prefix");
        return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 1U;
    }, "Original suffix and exact EOF finish after package identity changes");
    require(runtime.diagnostics().banks[0U].lanes[0U].appendedFrames == totalFrames,
        "Original ingress is accepted without replay or discarded prefix");
    const auto expected = tone(totalFrames, 0U, .025F, 220.0);
    std::array<float, 512U> actual{};
    for (std::size_t frame = 0U; frame < totalFrames; frame += 256U) {
        require(runtime.readPcmCacheForAudit(originalKey, 0U, frame, actual.data(), 256U),
            "final exact-key canonical read covers every accepted Original frame");
        require(std::equal(actual.begin(), actual.end(), expected.begin() + frame * 2U),
            "cache identity changes altered the retained Original samples");
    }
}

void frameZeroCandidatePreservesPreviouslyDrainedOriginalPrefix(
    bool declaredStems, bool partialOriginal, bool supersedePending = false,
    bool requestStemsWhilePending = false) {
    using stemwise::audio::engine::CurrentSongCacheKey;
    using stemwise::audio::engine::CurrentSongPcmCache;
    using stemwise::audio::engine::cache_test::beforeCacheRebind;
    static std::atomic<bool> rebindEntered{false}, releaseRebind{true}, rebindTimedOut{false};
    rebindEntered.store(false); releaseRebind.store(false); rebindTimedOut.store(false);
    constexpr SongRenderOwnership outgoing{26'013U, 5U}, candidate{26'013U, 6U};
    constexpr std::size_t prefixFrames = 8'192U, totalFrames = 32'768U;
    const auto outgoingFrames = partialOriginal ? totalFrames / 2U : totalFrames;
    const auto committedPrefixFrames = outgoingFrames - CurrentSongPcmCache::kChunkFrames;
    SongLaneIngressBank first(65'536U, 480U), second(65'536U, 480U);
    configureLanes(first, outgoing.generation, 1U);
    configureLanes(second, candidate.generation, requestStemsWhilePending ? kLaneCount : 1U);
    offerLane(first, 0U, outgoing.generation, 0U, outgoingFrames, !partialOriginal);
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(65'536U, 256U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&] {
        releaseRebind.store(true); beforeCacheRebind.store(nullptr); stopAndJoin(runtime);
    });
    auto outgoingBank = assignedBank(1U, outgoing, 0U, declaredStems ? 6U : 0U);
    if (!declaredStems) outgoingBank.stemPackageHash = 0U;
    outgoingBank.cacheFrameUpperBound = totalFrames;
    std::array<NativePlaybackBankIntent, 2U> banks{outgoingBank, NativePlaybackBankIntent{}};
    const CurrentSongCacheKey outgoingKey{outgoing.selectionToken, outgoing.generation,
        outgoingBank.songIdentityHash, outgoingBank.originalContentHash, outgoingBank.stemPackageHash};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, outgoing,
        banks, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "outgoing frame-zero Original starts");
    waitUntil([&] {
        const auto ready = runtime.renderReadinessDiagnostics();
        return ready.currentSongCacheBoundToSelectedOwner &&
            ready.currentSongCacheCompleteLaneMask == (partialOriginal ? 0U : 1U) &&
            runtime.diagnostics().banks[0U].lanes[0U].appendedFrames == outgoingFrames &&
            runtime.pcmCacheRangeReady(outgoingKey, 1U, committedPrefixFrames - 256U, committedPrefixFrames);
    }, "outgoing actual Original prefix is committed before candidate priming");

    banks[1U] = outgoingBank;
    banks[1U].ownership = candidate;
    offerLane(second, 0U, candidate.generation, 0U, prefixFrames, false);
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, outgoing,
        banks, controls(2U), false)) == NativePlaybackPublishStatus::Accepted,
        "same-content frame-zero candidate is assigned while outgoing remains selected");
    waitUntil([&] {
        return runtime.diagnostics().banks[1U].lanes[0U].appendedFrames == prefixFrames;
    }, "candidate prefix actually leaves native ingress before its selection");
    require(sameOwnership(runtime.renderReadinessDiagnostics().ownership, outgoing) &&
        second.lane(0U)->diagnostics().readableSourceFrames == 0U,
        "candidate priming preserves the outgoing selection and drains the exact offered prefix");

    beforeCacheRebind.store(+[]() noexcept {
        rebindEntered.store(true, std::memory_order_release);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(8);
        while (!releaseRebind.load(std::memory_order_acquire)) {
            if (std::chrono::steady_clock::now() >= deadline) { rebindTimedOut.store(true); return; }
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    });
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::Second, candidate,
        banks, controls(3U), false)) == NativePlaybackPublishStatus::Accepted,
        "primed frame-zero candidate is selected without another decode generation");
    waitUntil([&] { return rebindEntered.load(std::memory_order_acquire); },
        "selection reaches the actual asynchronous cache ownership transfer");
    offerLane(second, 0U, candidate.generation, prefixFrames, totalFrames - prefixFrames, true);
    runtime.notifyIngressAvailable();
    waitUntil([&] {
        const auto lane = runtime.diagnostics().banks[1U].lanes[0U];
        require(lane.discontinuityRejects == 0U && lane.endOfStreamFailures == 0U,
            "pending cache ownership transfer revoked its exact candidate ingress prefix");
        return lane.pulledFrames > prefixFrames && lane.retainedSuffixFrames != 0U;
    }, "candidate actually pulls and retains its next suffix while cache ownership transfer is held");
    require(!rebindTimedOut.load() && !releaseRebind.load() &&
        sameOwnership(runtime.renderReadinessDiagnostics().ownership, outgoing),
        "pending cache transfer retains the outgoing selected owner until its real acknowledgement");
    auto selectedCandidate = candidate;
    if (supersedePending) {
        ++selectedCandidate.generation;
        require(second.lane(0U)->advanceGeneration(selectedCandidate.generation),
            "a newer exact candidate generation supersedes the held ownership transfer");
        offerLane(second, 0U, selectedCandidate.generation, 0U, totalFrames, true);
        banks[1U].revision = 2U;
        banks[1U].ownership = selectedCandidate;
        require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::Second, selectedCandidate,
            banks, controls(4U), false)) == NativePlaybackPublishStatus::Accepted,
            "new candidate selection supersedes the actual in-flight rebind without changing the cache donor");
        waitUntil([&] {
            const auto diagnostics = runtime.diagnostics();
            return diagnostics.commandSnapshotsConsumed >= 4U && diagnostics.banks[1U].appliedIntentRevision == 2U;
        }, "new candidate bank intent actually applies while the obsolete rebind remains held");
        require(!rebindTimedOut.load() && !releaseRebind.load() &&
            runtime.diagnostics().banks[0U].appliedIntentRevision == outgoingBank.revision,
            "candidate supersession preserves the donor intent until the held transfer is released");
    }
    if (requestStemsWhilePending) {
        auto stemControls = controls(4U, 1.0F, 0.0F, MixerSourceMode::Stems);
        stemControls.sourceIntentRevision = 2U;
        require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::Second, candidate,
            banks, stemControls, false)) == NativePlaybackPublishStatus::Accepted,
            "Stems intent supersedes Original intent while the actual Original-only transfer is held");
        for (std::size_t lane = 1U; lane < kLaneCount; ++lane)
            offerLane(second, lane, candidate.generation, 0U, totalFrames, true);
        runtime.notifyIngressAvailable();
        waitUntil([&] { return runtime.diagnostics().commandSnapshotsConsumed >= 4U; },
            "the real Stems command is consumed before releasing the Original-only transfer");
        require(!rebindTimedOut.load() && !releaseRebind.load() &&
            runtime.diagnostics().banks[0U].appliedIntentRevision == outgoingBank.revision,
            "source reversal leaves the donor intent unchanged while real candidate parts are queued");
    }
    releaseRebind.store(true, std::memory_order_release);
    beforeCacheRebind.store(nullptr);
    waitUntil([&] {
        const auto ready = runtime.renderReadinessDiagnostics();
        return sameOwnership(ready.ownership, selectedCandidate) && ready.currentSongCacheBoundToSelectedOwner;
    }, "candidate cache ownership installs after the actual transfer acknowledgement");
    require(!rebindTimedOut.load(), "cache ownership transfer is released before its bounded deadline");
    waitUntil([&] {
        const auto ready = runtime.renderReadinessDiagnostics();
        require(ready.lanes[0U].ingressFailureGeneration == 0U,
            "selecting a frame-zero candidate discarded its already-drained Original prefix");
        const auto lane = runtime.diagnostics().banks[1U].lanes[0U];
        const auto completeMask = requestStemsWhilePending ? (1U << kLaneCount) - 1U : 1U;
        return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == completeMask &&
            lane.appendedFrames == totalFrames + (supersedePending ? prefixFrames : 0U) &&
            lane.endOfStreamMarks != 0U;
    }, "candidate accepts its contiguous suffix and exact EOF without replaying its prefix");
    const CurrentSongCacheKey candidateKey{selectedCandidate.selectionToken, selectedCandidate.generation,
        outgoingBank.songIdentityHash, outgoingBank.originalContentHash, outgoingBank.stemPackageHash};
    const auto expected = tone(totalFrames, 0U, .025F, 220.0);
    std::array<float, 512U> actual{};
    require(!runtime.readPcmCacheForAudit(outgoingKey, 0U, 0U, actual.data(), 256U),
        "retired outgoing ownership cannot read the candidate cache");
    for (std::size_t frame = 0U; frame < totalFrames; frame += 256U) {
        require(runtime.readPcmCacheForAudit(candidateKey, 0U, frame, actual.data(), 256U),
            "candidate exact-key cache retains every Original frame across frame-zero selection");
        require(std::equal(actual.begin(), actual.end(), expected.begin() + frame * 2U),
            "frame-zero candidate selection changed Original PCM bits");
    }
}

void impossibleLoopAfterCacheFailureReleasesNaturalEnd(bool cachedRange) {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership owner{26'031U, 2U};
    SongLaneIngressBank first(32'768U, 480U), second(256U, 256U);
    configureLanes(first, owner.generation, 1U);
    offerLane(first, 0U, owner.generation, 0U, 8'192U, false);
    FakeOutput output(512U, 64U);
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 64U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&] { fault.store(0); stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, 0U, 0U);
    bank.stemPackageHash = 0U;
    bank.cacheFrameUpperBound = 20'000U;
    std::array<NativePlaybackBankIntent, 2U> banks{bank, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        banks, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(), "failed-loop fixture starts");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner &&
        runtime.diagnostics().banks[0U].lanes[0U].appendedFrames == 8'192U; }, "loop fixture retains the exact prefix");
    fault.store(1); observed.store(false); shortCalls.store(0);
    offerLane(first, 0U, owner.generation, 8'192U, 8'192U, true);
    runtime.notifyIngressAvailable();
    waitUntil([&] { return observed.load(); }, "loop fixture actually fails cache growth");
    fault.store(0);
    waitUntil([&] { return !runtime.renderReadinessDiagnostics().currentSongCacheCanGrow &&
        runtime.diagnostics().banks[0U].lanes[0U].endOfStreamMarks != 0U; }, "failed growth settles into ordinary Original");
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, true,
        cachedRange ? 512U : 9'000U, cachedRange ? 2'048U : 12'000U) == NativePlaybackPublishStatus::Accepted,
        "loop request reaches the worker's current range authority");
    if (cachedRange) {
        waitUntil([&] { return output.loopCutoverCommitCount() != 0U; }, "fully retained range still prepares a loop");
        require(!output.retiredLoopDemand.matches(1U, owner.generation, owner.selectionToken),
            "cache failure incorrectly retired a fully retained range");
        return;
    }
    waitUntil([&] { return output.retiredLoopDemand.matches(1U, owner.generation, owner.selectionToken); },
        "terminally unavailable range retires its exact demand");
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, owner,
        banks, controls(2U), true)) == NativePlaybackPublishStatus::Accepted, "failed-loop linear playback resumes");
    waitUntil([&] { output.consumeAll(); runtime.notifyIngressAvailable();
        return runtime.diagnostics().audibleEnd.sequence != 0U; }, "unavailable loop cannot suppress natural song completion");
    require(sameOwnership(runtime.diagnostics().audibleEnd.ownership, owner) && output.loopCutoverCommitCount() == 0U,
        "unavailable range changed ownership or installed nonexistent loop PCM");
}

void failedOptionalCacheCanRetireItsPackage(bool sealFailure) {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership owner{26'021U, 2U};
    SongLaneIngressBank first(32768U, 480U), second(32768U, 480U);
    configureLanes(first, owner.generation, 9U);
    offerLane(first, 0U, owner.generation, 0U, 8705U, false);
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(32768U, 256U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&] { fault.store(0); stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, 0U, 8U); bank.cacheFrameUpperBound = 20000U;
    std::array<NativePlaybackBankIntent, 2U> banks{bank, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        banks, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(), "failed optional retirement starts");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner &&
        runtime.diagnostics().banks[0U].lanes[0U].appendedFrames == 8705U; }, "Original complete and partial chunks accepted");
    fault.store(1); observed.store(false); shortCalls.store(0);
    offerLane(first, 1U, owner.generation, 0U, sealFailure ? 513U : 2048U, sealFailure);
    runtime.notifyIngressAvailable();
    waitUntil([&] { return observed.load(); }, "optional write or seal actually fails");
    fault.store(0);
    banks[0U].revision = 2U; banks[0U].stemLaneCount = 0U; banks[0U].stemPackageHash = 0U;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, owner,
        banks, controls(2U), false)) == NativePlaybackPublishStatus::Accepted, "failed optional package withdraws");
    waitUntil([&] { runtime.notifyIngressAvailable(); return runtime.diagnostics().banks[0U].appliedIntentRevision == 2U; },
        "failed cache must settle optional package retirement");
    const auto after = runtime.diagnostics();
    require(after.canonicalPcmIo.admittedLaneCount == 1U && after.canonicalPcmIo.reservedBytes == 20000U * 8U,
        "failed optional retirement did not release lane reservation");
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, bank.songIdentityHash, bank.originalContentHash, 0U};
    std::array<float, 512U> pcm{};
    require(runtime.readPcmCacheForAudit(key, 0U, 0U, pcm.data(), 256U), "failed optional retirement lost published Original");
    const auto expected = tone(256U, 0U, .025F, 220.0);
    require(std::equal(pcm.begin(), pcm.end(), expected.begin()), "retirement changed Original PCM");
    offerLane(first, 0U, owner.generation, 8705U, 8192U, true);
    runtime.notifyIngressAvailable();
    waitUntil([&] { return runtime.diagnostics().banks[0U].lanes[0U].endOfStreamMarks != 0U; }, "Original suffix still reaches EOS after optional retirement");
    require(runtime.diagnostics().banks[0U].lanes[0U].discontinuityRejects == 0U,
        "retiring failed optional cache broke Original fallback cursor");
    const auto cursor = runtime.renderReadinessDiagnostics().renderStartTimelineFrame;
    for (std::size_t lane = 1U; lane <= 8U; ++lane) {
        // The acknowledged Original-only bank has retired every optional ingress borrower.
        first.releaseLane(lane);
        require(first.configureLane(lane, kSampleRate, owner.generation), "retry configures the exact optional ingress");
        offerLane(first, lane, owner.generation, 0U, 16'897U, true);
    }
    banks[0U] = bank;
    banks[0U].revision = 3U;
    banks[0U].stemPackageHash += 1U;
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, owner,
        banks, controls(3U, 1.0F, 0.0F, MixerSourceMode::Stems))) == NativePlaybackPublishStatus::Accepted,
        "same-owner failed cache retry requests ordinary Stems");
    waitUntil([&] { runtime.notifyIngressAvailable();
        return runtime.diagnostics().banks[0U].appliedIntentRevision == 3U; },
        "failed optional cache retry must settle adoption");
    const CurrentSongCacheKey retriedKey{owner.selectionToken, owner.generation,
        bank.songIdentityHash, bank.originalContentHash, banks[0U].stemPackageHash};
    require(runtime.readPcmCacheForAudit(retriedKey, 0U, 0U, pcm.data(), 256U) &&
        std::equal(pcm.begin(), pcm.end(), expected.begin()), "retry changed retained Original PCM");
    output.consumeAll();
    require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::First, owner,
        banks, controls(4U, 1.0F, 0.0F, MixerSourceMode::Stems), true)) == NativePlaybackPublishStatus::Accepted,
        "same-owner retry resumes playback");
    static_cast<void>(waitForRecord(output, 0U, [owner, cursor](const FakeOutputRecord &record) {
        return record.token == owner.selectionToken && record.generation == owner.generation &&
            record.controlRevision == 4U && record.sourceMode == NativeAudioSourceMode::Stems &&
            record.sourceSettled && record.stemBusReadyMask == 255U && record.sourceEnd >= cursor && record.peak > .005F;
    }, "failed optional cache retry plays ordinary Stems without a transport reset"));
    const auto settled = runtime.diagnostics();
    require(settled.banks[0U].seekCount == 0U && settled.requestedOwnership.generation == owner.generation &&
        settled.banks[0U].lanes[0U].discontinuityRejects == 0U, "retry changed Original ownership or cursor");
}

void rejectedCacheExpansionCannotAuthorizeOptionalWrites(bool spaceFailure) {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    fault.store(0);
    [[maybe_unused]] const auto resetHooks = onScopeExit([] { fault.store(0); });
    constexpr SongRenderOwnership owner{25'011U, 2U};
    SongLaneIngressBank first(262'144U, 480U), second(262'144U, 480U);
    configureLanes(first, owner.generation, 9U);
    offerLane(first, 0U, owner.generation, 0U, 8'192U, false);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(262'144U, 256U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&] { stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, 0U, 0U);
    bank.stemPackageHash = 0U;
    constexpr auto originalFrameBound = 300ULL * 48'000ULL;
    bank.cacheFrameUpperBound = originalFrameBound;
    std::array<NativePlaybackBankIntent, 2U> banks{bank, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner, banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted && runtime.start(), "expansion owner starts");
    waitUntil([&] { return output.commitCount() == 1U &&
        runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner; }, "Original cache bound");
    require(output.presentFirstCurrentRecord(), "expansion owner presented");
    if (spaceFailure) fault.store(2);
    bank.revision = 2U;
    bank.stemLaneCount = 8U;
    bank.stemPackageHash = 79U;
    if (!spaceFailure) bank.cacheFrameUpperBound = std::numeric_limits<std::uint64_t>::max();
    banks[0U] = bank;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, owner, banks,
        controls(2U))) == NativePlaybackPublishStatus::Accepted, "late package admitted to ordinary engine");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().cacheStorageStatus ==
        (spaceFailure ? CurrentSongCacheStorageStatus::InsufficientSpace : CurrentSongCacheStorageStatus::LimitExceeded);
    }, "late optional cache expansion rejected");
    // Let the accepted Original cross its actual 1MiB storage recheck after the refusal.
    fault.store(0);
    offerLane(first, 0U, owner.generation, 8'192U, 139'264U, false);
    runtime.notifyIngressAvailable();
    waitUntil([&] { output.consumeAll(); return runtime.diagnostics().banks[0U].lanes[0U].appendedFrames == 147'456U; },
        "Original advances through storage recheck");
    offerLane(first, 1U, owner.generation, 0U, 4096U, false);
    runtime.notifyIngressAvailable();
    waitUntil([&] { return runtime.diagnostics().banks[0U].lanes[1U].appendedFrames == 4096U; },
        "unadmitted stem reaches ordinary reservoir");
    require(runtime.renderReadinessDiagnostics().currentSongCacheCanGrow,
        "successful Original probe authorized a rejected optional cache lane");
    const auto admitted = runtime.diagnostics().canonicalPcmIo;
    require(admitted.admittedLaneCount == 1U &&
        admitted.admittedFrameUpperBound == originalFrameBound &&
        admitted.lastAdmissionStatus == (spaceFailure ? CurrentSongCacheStorageStatus::InsufficientSpace :
            CurrentSongCacheStorageStatus::LimitExceeded),
        "Original growth lost actual admission or the rejected expansion evidence");
    require(runtime.renderReadinessDiagnostics().lanes[0U].ingressFailureGeneration == 0U,
        "rejected optional expansion broke Original ingress");
}

void optionalStemCacheFailureKeepsExactOriginalPcm(bool partialOriginal, bool sealFailure) {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    constexpr SongRenderOwnership owner{25'001U, 2U};
    constexpr std::size_t parts = 8U;
    const std::size_t prefix = 8192U + (partialOriginal ? 513U : 0U);
    const std::size_t total = prefix + 8192U;
    SongLaneIngressBank first(65'536U, 480U), second(65'536U, 480U);
    configureLanes(first, owner.generation, parts + 1U);
    const auto offerConstant = [&](std::size_t lane, std::size_t start, std::size_t frames, bool eos) {
        std::vector<float> samples(frames * 2U);
        for (std::size_t f = 0U; f < frames; ++f) {
            samples[f * 2U] = .03125F; samples[f * 2U + 1U] = -.03125F;
        }
        const auto offered = first.lane(lane)->offer(LaneInputBlock{samples.data(), frames,
            {kSampleRate, 2U, LanePcmEncoding::Float32}, owner.generation,
            start * 1'000'000ULL / kSampleRate});
        require(offered.inputFramesAccepted == frames, "cache fault fixture accepts exact source PCM");
        if (eos) require(first.lane(lane)->markEndOfStream(owner.generation, start + frames).status ==
            LaneEndOfStreamStatus::Accepted, "cache fault fixture records exact source EOS");
    };
    offerConstant(0U, 0U, prefix, false);
    FakeOutput output;
    auto config = runtimeConfiguration(65'536U, 256U);
    config.ingressPullFrames = 1024U;
    NativePlaybackRuntime runtime(config, first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept { fault.store(0); stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, 0U, parts); bank.cacheFrameUpperBound = total;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        {bank, {}}, controls(1U))) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "optional cache failure starts ordinary Original playback");
    waitUntil([&] { return output.commitCount() == 1U; }, "optional cache owner committed");
    require(output.presentFirstCurrentRecord(), "optional cache owner presented");
    waitUntil([&] {
        return runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner &&
            runtime.diagnostics().banks[0].lanes[0].appendedFrames == prefix;
    }, "optional cache stores the complete or partial Original prefix");
    observed.store(false); shortCalls.store(0); fault.store(1);
    offerConstant(1U, 0U, sealFailure ? 513U : 2048U, sealFailure);
    runtime.notifyIngressAvailable();
    waitUntil([&] { return observed.load() &&
        runtime.renderReadinessDiagnostics().cacheStorageStatus == CurrentSongCacheStorageStatus::IoFailure; },
        "actual optional stem write or seal fault was observed");
    fault.store(0);
    offerConstant(0U, prefix, total - prefix, true);
    runtime.notifyIngressAvailable();
    waitUntil([&] {
        output.consumeAll();
        const auto lane = runtime.diagnostics().banks[0].lanes[0];
        return lane.discontinuityRejects != 0U || lane.endOfStreamMarks == 1U;
    }, "Original suffix settles after an optional cache failure");
    const auto lane = runtime.diagnostics().banks[0].lanes[0];
    require(lane.discontinuityRejects == 0U && lane.endOfStreamFailures == 0U &&
        runtime.renderReadinessDiagnostics().lanes[0].ingressFailureGeneration == 0U &&
        lane.appendedFrames == total, "optional stem cache failure lost or rejected Original suffix PCM");
    waitUntil([&] { output.consumeAll(); const auto n = output.recordCount();
        return n != 0U && output.record(n - 1U).sourceEnd == static_cast<double>(total); },
        "Original playback reaches its exact real EOS after cache fallback");
    double next{};
    for (std::size_t i = output.currentGenerationFirstRecord(); i < output.recordCount(); ++i) {
        const auto block = output.record(i);
        require(block.token == owner.selectionToken && block.generation == owner.generation &&
            block.sourceStart == next, "cache fallback duplicated PCM or changed its exact owner");
        std::uint64_t hash = 14695981039346656037ULL;
        for (std::size_t f = 0U; f < block.frames; ++f) {
            hash ^= std::bit_cast<std::uint32_t>(.03125F); hash *= 1099511628211ULL;
            hash ^= std::bit_cast<std::uint32_t>(-.03125F); hash *= 1099511628211ULL;
        }
        require(block.originalPcmHash == hash, "optional cache fallback changed Original PCM bits");
        next = block.sourceEnd;
    }
    require(next == total, "cache fallback omitted the terminal Original interval");
}

void currentSongCacheFileCreationFailureFallsBackToOriginalReservoir(int storageFault = 0,
    bool initialFailure = false) {
    constexpr SongRenderOwnership owner{1'650U, 2U};
    const std::uint64_t totalFrames = initialFailure || storageFault == 2 || storageFault == 0 ? 150'123U : 24'649U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation, 1U);
    FakeOutput output(8'192U, 256);
    NativePlaybackRuntimeConfiguration configuration = runtimeConfiguration(
        initialFailure ? 131'072U : 32'768U, 256U);
#if !defined(_WIN32)
    if (storageFault == 0) configuration.currentSongCacheDirectory =
        "/stemwise-current-song-cache-test-directory-does-not-exist";
#endif
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(unselectedCommand(
        1U,
        owner.generation - 1U,
        banks,
        controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "late cache allocation owner preassigned");
    require(runtime.start(), "late cache allocation runtime starts");

    std::array<float, 480U * 2U> input{};
    for (std::size_t sample = 0U; sample < input.size(); ++sample) {
        input[sample] = (sample & 1U) == 0U ? 0.03125F : -0.03125F;
    }
    std::atomic<bool> stopProducer{false};
    std::atomic<bool> producerDone{false};
    std::atomic<bool> producerFailed{false};
    std::atomic<std::uint64_t> producerLimit{8'192U};
    std::thread producer([&] {
        LaneIngress *lane = first.lane(0U);
        std::uint64_t frame = 0U;
        while (!stopProducer.load(std::memory_order_acquire) && frame < totalFrames) {
            const std::uint64_t limit = producerLimit.load(std::memory_order_acquire);
            if (frame >= limit) {
                std::this_thread::yield();
                continue;
            }
            const std::size_t requested = static_cast<std::size_t>(
                std::min<std::uint64_t>(
                    input.size() / 2U,
                    std::min(totalFrames, limit) - frame));
            const LaneInputBlock block{
                input.data(),
                requested,
                LaneInputFormat{kSampleRate, 2U, LanePcmEncoding::Float32},
                owner.generation,
                frame * 1'000'000U / kSampleRate,
            };
            const auto offered = lane->offer(block);
            if ((offered.status == LaneOfferStatus::Accepted ||
                 offered.status == LaneOfferStatus::Partial) &&
                offered.inputFramesAccepted != 0U) {
                frame += offered.inputFramesAccepted;
            } else if (offered.status == LaneOfferStatus::Backpressured) {
                std::this_thread::yield();
            } else {
                producerFailed.store(true, std::memory_order_release);
                break;
            }
        }
        if (!producerFailed.load(std::memory_order_acquire) && frame == totalFrames) {
            const auto ended = lane->markEndOfStream(owner.generation, totalFrames);
            producerFailed.store(
                ended.status != LaneEndOfStreamStatus::Accepted &&
                    ended.status != LaneEndOfStreamStatus::AlreadyMarked,
                std::memory_order_release);
        }
        producerDone.store(true, std::memory_order_release);
    });
    const auto joinProducer = onScopeExit([&] {
        stopProducer.store(true, std::memory_order_release);
        if (producer.joinable()) producer.join();
    });

    waitUntil([&] {
        return runtime.diagnostics().banks[0].lanes[0].appendedFrames >= 8'192U;
    }, "ordinary reservoir did not retain the frame-zero head", 15'000U);
#if defined(_WIN32)
    std::vector<std::FILE *> exhaustedTemporaryFiles;
    std::FILE *temporary = nullptr;
    while (storageFault == 0 && ::tmpfile_s(&temporary) == 0 && temporary != nullptr) {
        exhaustedTemporaryFiles.push_back(temporary);
        temporary = nullptr;
    }
    require(storageFault != 0 || !exhaustedTemporaryFiles.empty(),
        "host temporary-file slots could not be exhausted");
    const auto closeTemporaryFiles = onScopeExit([&] {
        for (std::FILE *openTemporaryFile : exhaustedTemporaryFiles) {
            static_cast<void>(std::fclose(openTemporaryFile));
        }
    });
#endif
    if (initialFailure) {
        stemwise::audio::engine::cache_test::observed.store(false);
        stemwise::audio::engine::cache_test::fault.store(storageFault);
    }
    const auto clearStorageFault = onScopeExit([] { stemwise::audio::engine::cache_test::fault.store(0); });
    require(runtime.publishCommand(selectedCommand(
        2U,
        NativePlaybackBank::First,
        owner,
        banks,
        controls(2U))) == NativePlaybackPublishStatus::Accepted,
        "file-backed cache failure owner selected");
    waitUntil([&] { return output.commitCount() == 1U; },
        "file-backed cache failure owner staged");
    require(output.presentFirstCurrentRecord(), "file-backed cache failure owner presented");
    if (storageFault == 0 || initialFailure) {
    if (initialFailure) waitUntil([&] {
        return runtime.renderReadinessDiagnostics().cacheStorageStatus ==
            (storageFault == 2 ? stemwise::audio::engine::CurrentSongCacheStorageStatus::InsufficientSpace :
                stemwise::audio::engine::CurrentSongCacheStorageStatus::IoFailure);
    }, "initial cache storage refusal was not observed");
    const auto creationFailureReadiness = runtime.renderReadinessDiagnostics();
    require(!creationFailureReadiness.currentSongCacheBoundToSelectedOwner &&
            creationFailureReadiness.currentSongCacheCompleteLaneMask == 0U &&
            !creationFailureReadiness.currentSongCacheCanGrow,
        "failed cache file creation published false cache readiness");
    } else {
        waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner; },
            "write-fault fixture did not first install the actual cache");
        stemwise::audio::engine::cache_test::shortCalls.store(0);
        stemwise::audio::engine::cache_test::observed.store(false);
        stemwise::audio::engine::cache_test::fault.store(storageFault);
    }
#if defined(_WIN32)
    for (std::FILE *openTemporaryFile : exhaustedTemporaryFiles) {
        static_cast<void>(std::fclose(openTemporaryFile));
    }
    exhaustedTemporaryFiles.clear();
#endif
    producerLimit.store(totalFrames, std::memory_order_release);
    waitUntil([&] {
        output.consumeAll();
        return producerDone.load(std::memory_order_acquire) &&
            runtime.diagnostics().banks[0].lanes[0].endOfStreamMarks == 1U;
    }, "failed cache creation did not drain continuous Original", 15'000U);
    producer.join();

    try {
        waitUntil([&] {
            output.consumeAll();
            const std::size_t count = output.recordCount();
            return count != 0U && output.record(count - 1U).sourceEnd >=
                static_cast<double>(totalFrames);
        }, "current owner stalled after cache file creation failure", 15'000U);
    } catch (...) {
        const auto diagnostic = runtime.diagnostics();
        const auto failedReadiness = runtime.renderReadinessDiagnostics();
        const std::size_t count = output.recordCount();
        std::cerr << "file-cache-failure-debug records=" << count
                  << " last-end=" << (count == 0U ? -1.0 : output.record(count - 1U).sourceEnd)
                  << " render-start=" << failedReadiness.renderStartTimelineFrame
                  << " reservoir-first=" << failedReadiness.lanes[0].firstTimelineFrame
                  << " reservoir-end=" << failedReadiness.lanes[0].endTimelineFrameExclusive
                  << " reservoir-available=" << failedReadiness.lanes[0].availableFromRenderStart
                  << " appended=" << diagnostic.banks[0].lanes[0].appendedFrames
                  << " gaps=" << failedReadiness.lanes[0].reservoirGapRejectionCount
                  << " eos=" << diagnostic.banks[0].lanes[0].endOfStreamMarks
                  << " cache-bound=" << failedReadiness.currentSongCacheBoundToSelectedOwner
                  << " cache-mask=" << failedReadiness.currentSongCacheCompleteLaneMask << '\n';
        throw;
    }
    require(!producerFailed.load(std::memory_order_acquire),
        "late cache allocation producer failed");
    try { waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.currentSongCacheBoundToSelectedOwner == (storageFault != 0 && !initialFailure) &&
            readiness.currentSongCacheCompleteLaneMask == 0U && !readiness.currentSongCacheCanGrow;
    }, "failed cache later published false readiness"); } catch (...) {
        const auto readiness = runtime.renderReadinessDiagnostics();
        std::cerr << "storage fallback fault=" << storageFault << " bound=" << readiness.currentSongCacheBoundToSelectedOwner
            << " mask=" << readiness.currentSongCacheCompleteLaneMask << " grow=" << readiness.currentSongCacheCanGrow
            << " storage=" << static_cast<unsigned>(readiness.cacheStorageStatus) << '\n';
        throw;
    }

    double expectedStart = 0.0;
    bool reachedEnd = false;
    for (std::size_t index = output.currentGenerationFirstRecord();
         index < output.recordCount();
         ++index) {
        const FakeOutputRecord record = output.record(index);
        if (record.generation != owner.generation || record.frames == 0U) continue;
        require(record.token == owner.selectionToken && record.sourceStart == expectedStart &&
            record.sourceEnd == expectedStart + static_cast<double>(record.frames) &&
            record.sourceEnd <= static_cast<double>(totalFrames),
            "file-backed cache fallback introduced an Original gap or overlap");
        std::uint64_t expectedHash = 14695981039346656037ULL;
        for (std::size_t frame = 0U; frame < record.frames; ++frame) {
            expectedHash ^= std::bit_cast<std::uint32_t>(0.03125F); expectedHash *= 1099511628211ULL;
            expectedHash ^= std::bit_cast<std::uint32_t>(-0.03125F); expectedHash *= 1099511628211ULL;
        }
        require(record.originalPcmHash == expectedHash, "cache fallback changed Original PCM bits");
        expectedStart = record.sourceEnd;
        if (expectedStart >= static_cast<double>(totalFrames)) {
            reachedEnd = true;
            break;
        }
    }
    require(reachedEnd, "file-backed cache fallback did not render through Original EOS");
    waitUntil([&] {
        output.consumeAll();
        const auto end = runtime.diagnostics().audibleEnd;
        return end.sequence != 0U && sameOwnership(end.ownership, owner);
    }, "cache fallback must present the exact owner's natural end");
    if (storageFault != 0) require(stemwise::audio::engine::cache_test::observed.load(),
        "cache write/space/seal failure was not actually reached");
    stopAndJoin(runtime);
}

void neutralReturnRestoresMaximumRenderQuantum() {
    constexpr SongRenderOwnership owner{1'718U, 2U};
    SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
    require(first.configureLane(0U, kSampleRate, owner.generation), "neutral Original configured");
    offerLane(first, 0U, owner.generation, 0U, 32'000U, true);
    FakeOutput output(4'096U, 192U);
    auto intent = assignedBank(1U, owner);
    intent.stemLaneCount = 0U;
    intent.stemPackageHash = 0U;
    const std::array<NativePlaybackBankIntent, 2U> banks{intent, NativePlaybackBankIntent{}};
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        owner, banks, controls(1U, .75f, 3.f))) == NativePlaybackPublishStatus::Accepted,
        "neutral tuned owner selected");
    require(runtime.start(), "neutral runtime started");
    static_cast<void>(waitForRecord(output, 0U, [](const FakeOutputRecord &record) {
        return record.frames == 192U && record.controlRevision == 1U &&
            record.sourceEnd - record.sourceStart == 144.0;
    }, "tuned render uses hardware burst"));
    require(output.presentFirstCurrentRecord(), "neutral owner presented");
    const auto firstNeutral = output.recordCount();
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First,
        owner, banks, controls(2U))) == NativePlaybackPublishStatus::Accepted, "neutral tuning selected");
    static_cast<void>(waitForRecord(output, firstNeutral, [](const FakeOutputRecord &record) {
        return record.frames == 256U && record.controlRevision == 2U &&
            record.sourceEnd - record.sourceStart == 256.0;
    }, "neutral direct render retained sticky burst quantum"));
    stopAndJoin(runtime);
}

void failedCacheReadPublishesExactOwnerFailure() {
    constexpr SongRenderOwnership owner{1'719U, 2U};
    SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
    require(first.configureLane(0U, kSampleRate, owner.generation), "read-fault Original configured");
    offerLane(first, 0U, owner.generation, 0U, 24'000U, true);
    FakeOutput output(4'096U, 192U);
    auto intent = assignedBank(1U, owner);
    intent.stemLaneCount = 0U;
    intent.stemPackageHash = 0U;
    const std::array<NativePlaybackBankIntent, 2U> banks{intent, NativePlaybackBankIntent{}};
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        owner, banks, controls(1U))) == NativePlaybackPublishStatus::Accepted, "read-fault selected");
    require(runtime.start(), "read-fault runtime started");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 1U; },
        "read-fault cache sealed");
    require(output.presentFirstCurrentRecord(), "read-fault owner presented");
    stemwise::audio::engine::cache_test::observed.store(false);
    stemwise::audio::engine::cache_test::fault.store(5);
    const auto clearFault = onScopeExit([] { stemwise::audio::engine::cache_test::fault.store(0); });
    waitUntil([&] {
        output.consumeAll();
        const auto readiness = runtime.renderReadinessDiagnostics();
        return sameOwnership(readiness.ownership, owner) &&
            readiness.lanes[0].ingressFailureGeneration == owner.generation;
    }, "failed cache read did not publish a controller-visible exact-owner failure");
    require(stemwise::audio::engine::cache_test::observed.load(), "read fault was not exercised");
    stopAndJoin(runtime);
}

void completeCurrentSongCachePromotesOnlyToExactSelectedOwner(bool originalOnly = false) {
    SongLaneIngressBank first(8'192U, 480U);
    SongLaneIngressBank second(8'192U, 480U);
    constexpr SongRenderOwnership builderOwner{1'700U, 2U};
    constexpr SongRenderOwnership mismatchedOwner{1'700U, 3U};
    constexpr SongRenderOwnership consumerOwner{1'700U, 4U};
    constexpr std::uint64_t seekFrame = 480U;
    constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
    const std::uint32_t requiredLaneMask = originalOnly ? 1U : allLaneMask;
    configureLanes(first, builderOwner.generation);
    configureLanes(second, mismatchedOwner.generation, 1U);
    offerLane(first, 0U, builderOwner.generation, 0U, 2'400U, true);
    offerLane(second, 0U, mismatchedOwner.generation, seekFrame, 2'400U, true);

    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, builderOwner),
        NativePlaybackBankIntent{.revision = 1U},
    };
    if (originalOnly) { banks[0].stemPackageHash = 0U; banks[0].stemLaneCount = 0U; }
    require(runtime.publishCommand(selectedCommand(
        1U,
        NativePlaybackBank::First,
        builderOwner,
        banks,
        controls(1U),
        false)) == NativePlaybackPublishStatus::Accepted,
        "current-song cache builder selected");
    require(runtime.start(), "current-song cache promotion runtime starts");

    NativePlaybackRenderReadinessDiagnostics readiness{};
    waitUntil([&] {
        readiness = runtime.renderReadinessDiagnostics();
        return readiness.sequence != 0U && readiness.assigned &&
            sameOwnership(readiness.ownership, builderOwner) &&
            readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask == 1U;
    }, "incomplete current-song cache is bound only to its builder");
    require(readiness.currentSongCacheCanGrow == !originalOnly,
        "cache growth observation must follow the declared package width");
    require(
        readiness.currentSongCacheCompleteLaneMask != allLaneMask,
        "incomplete builder cannot report all-lane readiness");

    banks[1] = assignedBank(2U, mismatchedOwner, seekFrame);
    if (originalOnly) { banks[1].stemPackageHash = 0U; banks[1].stemLaneCount = 0U; }
    if (originalOnly) ++banks[1].originalContentHash;
    else ++banks[1].stemPackageHash;
    require(runtime.publishCommand(selectedCommand(
        2U,
        NativePlaybackBank::Second,
        mismatchedOwner,
        banks,
        controls(2U),
        false)) == NativePlaybackPublishStatus::Accepted,
        "mismatched early-seek consumer selected");
    waitUntil([&] {
        const auto diagnostics = runtime.diagnostics();
        return output.commitCount() == 2U &&
            sameOwnership(diagnostics.committedOwnership, mismatchedOwner);
    }, "mismatched early-seek consumer installed");

    for (std::size_t lane = 1U; lane < (originalOnly ? 1U : kLaneCount); ++lane) {
        offerLane(first, lane, builderOwner.generation, 0U, 2'400U, true);
    }
    waitUntil([&] {
        readiness = runtime.renderReadinessDiagnostics();
        return sameOwnership(readiness.ownership, mismatchedOwner) &&
            readiness.currentSongCacheCompleteLaneMask ==
                requiredLaneMask;
    }, "retained builder seals all seven lanes after stale selection");
    require(!readiness.currentSongCacheBoundToSelectedOwner,
        "one mismatched content hash cannot promote by token alone");

    LaneIngress *consumerOriginal = second.lane(0U);
    require(consumerOriginal != nullptr &&
        consumerOriginal->advanceGeneration(consumerOwner.generation),
        "exact consumer advances its Original-only decoder generation");
    offerLane(second, 0U, consumerOwner.generation, seekFrame, 2'400U, true);
    banks[1] = assignedBank(3U, consumerOwner, seekFrame);
    if (originalOnly) { banks[1].stemPackageHash = 0U; banks[1].stemLaneCount = 0U; }
    require(runtime.publishCommand(selectedCommand(
        3U,
        NativePlaybackBank::Second,
        consumerOwner,
        banks,
        controls(3U),
        false)) == NativePlaybackPublishStatus::Accepted,
        "exact early-seek consumer selected");
    waitUntil([&] {
        readiness = runtime.renderReadinessDiagnostics();
        return sameOwnership(readiness.ownership, consumerOwner) &&
            readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask ==
                requiredLaneMask;
    }, "complete cache promotes atomically to exact selected owner");

    banks[0] = NativePlaybackBankIntent{.revision = 2U};
    require(runtime.publishCommand(selectedCommand(
        4U,
        NativePlaybackBank::Second,
        consumerOwner,
        banks,
        controls(4U),
        false)) == NativePlaybackPublishStatus::Accepted,
        "old builder unassignment published");
    waitUntil([&] {
        readiness = runtime.renderReadinessDiagnostics();
        return !runtime.diagnostics().banks[0].assigned &&
            sameOwnership(readiness.ownership, consumerOwner) &&
            readiness.currentSongCacheBoundToSelectedOwner &&
            readiness.currentSongCacheCompleteLaneMask ==
                requiredLaneMask;
    }, "old builder unassignment preserves promoted cache");
    stopAndJoin(runtime);
}

void pausedPresentationRangeSurvivesLateStemPreload(bool prepareOriginalLoop = false) {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{1'709U, 3U};
    constexpr std::size_t chunk = CurrentSongPcmCache::kChunkFrames;
    std::uint64_t presentedFrame{};
    SongLaneIngressBank first(131'072U, 256U), second(131'072U, 256U);
    configureLanes(first, owner.generation);
    offerLane(first, 0U, owner.generation, 0U, 65'536U, false);
    FakeOutput output(16'384U, 1'024U);
    auto configuration = runtimeConfiguration(32'768U, 256U);
    configuration.targetBursts = 8U;

    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept { stopAndJoin(runtime); });
    auto original = assignedBank(1U, owner, 0U, 0U);
    original.stemPackageHash = 0U; original.cacheFrameUpperBound = 65'536U;
    std::array<NativePlaybackBankIntent, 2U> banks{original, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        banks, controls(1U))) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "late-preload fixture starts the real Original owner");
    waitUntil([&] { return output.commitCount() == 1U && output.queued() >= 8'192U &&
        runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner; },
        "Original cache binds behind its actual bounded output reserve");
    require(output.presentFirstCurrentRecord(), "first actual callback commits the staged Original owner");
    bool presented = false;
    for (std::size_t index = 0U; index < output.recordCount(); ++index) {
        const auto record = output.record(index);
        if (record.sourceEnd > static_cast<double>(chunk) && record.sourceEnd <= static_cast<double>(2U * chunk) &&
            record.frames != 0U && record.fingerprint != 0U && record.peak > .001F) {
            presentedFrame = static_cast<std::uint64_t>(std::floor(record.sourceEnd)) - 1U;
            presented = output.presentRecord(index); break;
        }
    }
    require(presented, "callback evidence comes from actual PCM ending at the paused presentation head");
    const auto renderedBeforeCallback = output.recordCount();
    require(output.consumeFrames(presentedFrame + 1U) == presentedFrame + 1U,
        "the actual presented callback releases its output reserve frames");
    runtime.notifyIngressAvailable();
    waitUntil([&] { return output.recordCount() > renderedBeforeCallback; },
        "cache-bound Original renders ahead of the acknowledged presentation boundary");
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, owner,
        banks, controls(2U), false)) == NativePlaybackPublishStatus::Accepted,
        "pause retains the rendered reserve and exact presentation owner");
    waitUntil([&] {
        const auto diagnostics = runtime.diagnostics();
        return !output.playing() && diagnostics.presentedPosition.lastTimelineFrame == static_cast<std::int64_t>(presentedFrame) &&
            diagnostics.presentedPosition.generation == owner.generation;
    }, "actual presentation tuple is observed before late attachment");
    banks[0] = assignedBank(2U, owner, 0U, 6U); banks[0].cacheFrameUpperBound = 65'536U;
    for (std::size_t lane = 1U; lane < 7U; ++lane)
        offerLane(first, lane, owner.generation, 0U, 65'536U, false);
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, owner,
        banks, controls(3U), false)) == NativePlaybackPublishStatus::Accepted,
        "same-owner package attaches while Original output remains paused");
    runtime.notifyIngressAvailable();
    waitUntil([&] {
        const auto diagnostics = runtime.diagnostics();
        const auto readiness = runtime.renderReadinessDiagnostics();
        return diagnostics.banks[0U].appliedIntentRevision == 2U &&
            readiness.currentSongCacheBoundToSelectedOwner &&
            std::all_of(diagnostics.banks[0U].lanes.begin() + 1U,
                diagnostics.banks[0U].lanes.begin() + 7U,
                [&](const auto &lane) { return lane.appendedFrames >= 9U * chunk; });
    }, "unused stem lanes publish beyond their eight resident chunks toward bounded preload");
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, banks[0].songIdentityHash,
        banks[0].originalContentHash, banks[0].stemPackageHash};
    std::array<float, 256U * 2U> actual{};
    for (std::size_t lane = 1U; lane < 7U; ++lane) {
        waitUntil([&] { return runtime.readPcmCacheForAudit(key, lane, 8U * chunk,
            actual.data(), 256U); }, "actual cache I/O confirms each optional lane published its ninth chunk");
    }
    for (const std::size_t lane : {1U, 2U, 3U, 4U, 5U, 6U, 0U}) {
        const bool readable = runtime.readPcmCacheForAudit(key, lane, presentedFrame, actual.data(), 256U);
        if (!readable) std::cerr << "late-preload missing lane=" << lane << " presented=" << presentedFrame
            << " render=" << runtime.renderReadinessDiagnostics().renderStartTimelineFrame
            << " appended=" << runtime.diagnostics().banks[0U].lanes[lane].appendedFrames << '\n';
        require(readable, "paused presentation PCM must survive late stem preload on every required lane");
        const auto expected = tone(256U, presentedFrame, .025F + .002F * static_cast<float>(lane),
            220.0 + 17.0 * static_cast<double>(lane));
        require(std::memcmp(actual.data(), expected.data(), actual.size() * sizeof(float)) == 0,
            "retained paused head is actual exact-owner PCM, not an availability echo");
    }
    require(runtime.pcmCacheRangeReady(key, 127U, presentedFrame, presentedFrame + 256U),
        "actual all-lane range is available without replay after late attachment");
    if (!prepareOriginalLoop) return;
    require(runtime.pcmCacheRangeReady(key, 2U, 0U, 64U),
        "optional canonical first chunk remains committed after preload");
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation,
        true, 0U, 4'096U, 0U, true) == NativePlaybackPublishStatus::Accepted,
        "paused Original receives an exact short loop through its real policy entry point");
    waitUntil([&] { return output.loopCutoverCommitCount() != 0U; },
        "Original continuation primes directly from canonical PCM without opening another decoder");
    const auto cycles = runtime.diagnostics().renderCycles;
    static_cast<void>(runtime.renderReadinessDiagnostics());
    waitForRenderProgress(runtime, cycles, "post-commit Original loop metadata settles");
    const auto preparedBegins = output.loopCutoverBeginCount();
    auto gain = controls(4U); gain.mixer.musicGain = .5F;
    require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::First, owner,
        banks, gain, false)) == NativePlaybackPublishStatus::Accepted,
        "same-owner Original gain update reaches its already prepared continuation");
    waitUntil([&] { return output.lastLateControl().canonicalControlRevision == 4U &&
        output.lastLateControl().musicGain == .5F; }, "actual prepared Original control publication receives its gain");
    const auto gainCycles = runtime.diagnostics().renderCycles;
    static_cast<void>(runtime.renderReadinessDiagnostics());
    waitForRenderProgress(runtime, gainCycles,
        "Original-only continuation settles its compatible gain revision");
    require(output.loopCutoverBeginCount() == preparedBegins,
        "compatible Original gain preserves the prepared DSP continuation without a new stage");
}

void failedPartialStemPackageDetachesWithoutReplacingOriginal() {
    constexpr SongRenderOwnership outgoing{1'705U, 2U};
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, outgoing.generation);
    offerLane(first, 0U, outgoing.generation, 0U, 16'384U, true);
    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(480U);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 480U), first, second, output.port());
    [[maybe_unused]] const auto cleanup = onScopeExit([&]() noexcept { stopAndJoin(runtime); });
    auto original = assignedBank(1U, outgoing);
    original.stemPackageHash = 0U;
    original.stemLaneCount = 0U;
    std::array<NativePlaybackBankIntent, 2U> banks{original, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        outgoing, banks, controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "failed-parts fixture selects Original");
    require(runtime.start(), "failed-parts fixture starts");
    waitUntil([&] { return output.commitCount() == 1U; }, "failed-parts Original commits");
    require(output.presentFirstCurrentRecord(), "failed-parts Original becomes audible");
    waitUntil([&] {
        const auto ready = runtime.renderReadinessDiagnostics();
        return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 1U;
    }, "failed-parts fixture seals Original");

    // Match late attachment: one decoder never opens, while the other optional lanes have
    // accepted bytes. Original remains the audible owner throughout package failure.
    banks[0] = assignedBank(2U, outgoing);
    for (std::size_t lane = 2U; lane < kLaneCount; ++lane)
        offerLane(first, lane, outgoing.generation, 0U, 12'000U, false);
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First,
        outgoing, banks, controls(2U))) == NativePlaybackPublishStatus::Accepted,
        "partial package identity attaches to the live Original");
    try {
        waitUntil([&] { return runtime.diagnostics().banks[0U].appliedIntentRevision == 2U; },
            "partial package bank is admitted without claiming ready Stems");
    } catch (...) {
        const auto diagnostic = runtime.diagnostics();
        const auto ready = runtime.renderReadinessDiagnostics();
        std::cerr << "partial admission revision=" << diagnostic.banks[0U].appliedIntentRevision
            << " rejected=" << diagnostic.banks[0U].rejectedBankIntents
            << " operations=" << diagnostic.resourceOperationsCompleted
            << " cycles=" << diagnostic.renderCycles
            << " bound=" << ready.currentSongCacheBoundToSelectedOwner
            << " storage=" << int(ready.cacheStorageStatus)
            << " mask=" << ready.currentSongCacheCompleteLaneMask << '\n';
        throw;
    }

    banks[0] = original;
    banks[0].revision = 3U;
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First,
        outgoing, banks, controls(3U))) == NativePlaybackPublishStatus::Accepted,
        "failed package requests atomic same-owner optional retirement");
    waitUntil([&] {
        const auto ready = runtime.renderReadinessDiagnostics();
        return runtime.diagnostics().banks[0U].appliedIntentRevision == 3U &&
            ready.currentSongCacheBoundToSelectedOwner && ready.requiredSongLaneMask == 1U;
    }, "optional retirement acknowledges exact Original-only bank revision");
    const auto cursor = runtime.renderReadinessDiagnostics().renderStartTimelineFrame;
    banks[0].revision = 4U;
    require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::First,
        outgoing, banks, controls(4U))) == NativePlaybackPublishStatus::Accepted,
        "superseded-growth retirement may publish a newer identical Original-only revision");
    waitUntil([&] { return runtime.diagnostics().banks[0U].appliedIntentRevision == 4U; },
        "identical Original-only bank revision is acknowledged");
    const auto diagnostic = runtime.diagnostics();
    require(diagnostic.outputInvalidRenderResults == 0U && output.playing() &&
            diagnostic.banks[0U].seekCount == 0U && output.commitCount() == 1U &&
            sameOwnership(diagnostic.committedOwnership, outgoing) &&
            runtime.renderReadinessDiagnostics().renderStartTimelineFrame >= cursor,
        "optional retirement and superseded growth preserve Original owner, cursor and decoder epoch");
}

void stemsAttachedAfterCachedSeekBuildFromZeroWhileOriginalPlays(bool rejectParts = false, bool paused = false,
    bool rejectChangedAnchor = false) {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership originalOwner{1'711U, 3U}, soughtOwner{1'711U, 4U};
    constexpr std::uint64_t frames = 131'072U, seekFrame = 40'000U;
    SongLaneIngressBank first(262'144U, 480U), second(262'144U, 480U);
    configureLanes(first, originalOwner.generation);
    offerLane(first, 0U, originalOwner.generation, 0U, frames);
    FakeOutput output(8'192U, 64U);
    output.setMaximumAcceptance(256U);
    output.setAutoAcknowledgePausedCommit(true);
    NativePlaybackRuntime runtime(runtimeConfiguration(rejectChangedAnchor ? 16'384U : 32'768U, 256U),
        first, second, output.port());
    const auto cleanup = onScopeExit([&] {
        stemwise::audio::engine::cache_test::fault.store(0);
        stopAndJoin(runtime);
    });
    auto bank = assignedBank(1U, originalOwner, 0U, 0U);
    bank.stemPackageHash = 0U;
    bank.cacheFrameUpperBound = frames;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, originalOwner,
        {bank, NativePlaybackBankIntent{}}, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "late-after-seek fixture starts an actual Original-only owner");
    waitUntil([&] { return output.commitCount() == 1U &&
        runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 1U; },
        "actual Original ingress seals its canonical cache before seeking");
    offerLane(first, 0U, soughtOwner.generation, seekFrame, 20'000U, false);
    bank.revision = 2U; bank.ownership = soughtOwner; bank.firstTimelineFrame = seekFrame;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, soughtOwner,
        {bank, NativePlaybackBankIntent{}}, controls(2U))) == NativePlaybackPublishStatus::Accepted,
        "actual cached seek starts the exact nonzero Original owner playing");
    waitUntil([&] { return output.commitCount() == 2U &&
        sameOwnership(runtime.renderReadinessDiagnostics().ownership, soughtOwner) &&
        runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner; },
        "sealed Original cache actually rebinds to the playing sought generation");
    require(output.presentFirstCurrentRecord(), "actual sought PCM acknowledges its selected callback owner");
    const auto recordsBeforeParts = output.recordCount();
    if (rejectChangedAnchor) {
        const CurrentSongCacheKey originalKey{soughtOwner.selectionToken, soughtOwner.generation,
            bank.songIdentityHash, bank.originalContentHash, bank.stemPackageHash};
        const auto requireOriginalReadback = [&](const CurrentSongCacheKey &key) {
            std::array<float, 512U> actual{};
            for (const auto firstFrame : {0U, static_cast<std::uint32_t>(seekFrame)}) {
                require(runtime.readPcmCacheForAudit(key, 0U, firstFrame, actual.data(), 256U),
                    "rejected same-owner anchor must preserve the exact committed Original cache");
                const auto expected = tone(256U, firstFrame, .025F, 220.0);
                require(std::equal(actual.begin(), actual.end(), expected.begin()),
                    "same-owner rejection and corrected metadata retry preserve Original PCM bits");
            }
        };
        requireOriginalReadback(originalKey);
        const auto before = runtime.diagnostics();
        auto changed = bank;
        changed.revision = 3U;
        changed.firstTimelineFrame = seekFrame + 1U;
        changed.stemLaneCount = 6U;
        changed.stemPackageHash = 0x9999'AAAA'BBBB'CCCCULL;
        require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, soughtOwner,
            {changed, NativePlaybackBankIntent{}}, controls(3U))) == NativePlaybackPublishStatus::Accepted,
            "same-owner anchor and first package update reaches native bank admission");
        waitUntil([&] { return runtime.diagnostics().banks[0U].rejectedBankIntents >
            before.banks[0U].rejectedBankIntents; }, "native actually rejects the changed same-owner anchor");
        const auto rejected = runtime.diagnostics();
        require(rejected.banks[0U].appliedIntentRevision == 2U &&
            rejected.banks[0U].seekCount == before.banks[0U].seekCount && output.commitCount() == 2U &&
            sameOwnership(rejected.requestedOwnership, soughtOwner) &&
            sameOwnership(rejected.acceptedOwnership, soughtOwner) &&
            sameOwnership(rejected.committedOwnership, soughtOwner),
            "rejected anchor cannot change the admitted bank, seek or output owner");
        requireOriginalReadback(originalKey);
        changed.revision = 4U;
        changed.firstTimelineFrame = seekFrame;
        require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::First, soughtOwner,
            {changed, NativePlaybackBankIntent{}}, controls(4U))) == NativePlaybackPublishStatus::Accepted,
            "corrected higher-revision metadata request retries on the unchanged owner");
        waitUntil([&] { return runtime.diagnostics().banks[0U].appliedIntentRevision == 4U; },
            "corrected package identity applies after rejected same-owner transport mutation");
        auto adoptedKey = originalKey;
        adoptedKey.stemPackageHash = changed.stemPackageHash;
        requireOriginalReadback(adoptedKey);
        require(runtime.diagnostics().banks[0U].seekCount == before.banks[0U].seekCount &&
            output.commitCount() == 2U && output.playing(),
            "valid metadata retry preserves Original playback without another seek or output handoff");
        return;
    }
    bank.revision = 3U; bank.stemLaneCount = 6U; bank.stemPackageHash = 0x9999'AAAA'BBBB'CCCCULL;
    for (std::size_t lane = 1U; lane < kLaneCount; ++lane)
        offerLane(first, lane, soughtOwner.generation, 0U, frames);
    if (rejectParts) stemwise::audio::engine::cache_test::fault.store(2);
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, soughtOwner,
        {bank, NativePlaybackBankIntent{}}, controls(3U), !paused)) == NativePlaybackPublishStatus::Accepted,
        "late optional decoders attach from zero without replacing the sought Original owner");
    const CurrentSongCacheKey key{soughtOwner.selectionToken, soughtOwner.generation,
        bank.songIdentityHash, bank.originalContentHash, bank.stemPackageHash};
    if (rejectParts) {
        waitUntil([&] {
            const auto ready = runtime.renderReadinessDiagnostics();
            if (ready.cacheStorageStatus != CurrentSongCacheStorageStatus::InsufficientSpace ||
                ready.currentSongCacheCompleteLaneMask != 1U) return false;
            for (std::size_t lane = 1U; lane < kLaneCount; ++lane)
                if (ready.lanes[lane].availableFromRenderStart < 1'024U ||
                    ready.lanes[lane].firstTimelineFrame > ready.renderStartTimelineFrame ||
                    ready.lanes[lane].endTimelineFrameExclusive < ready.renderStartTimelineFrame + 8'192U)
                    return false;
            return true;
        }, "rejected optional cache must drain real streamed prefixes through the sought render horizon");
        std::array<float, 512U> retainedOriginal{};
        require(runtime.readPcmCacheForAudit(key, 0U, seekFrame, retainedOriginal.data(), 256U),
            "rejected parts must preserve the exact sealed Original cache");
        const auto expectedOriginal = tone(256U, seekFrame, .025F, 220.0);
        require(std::equal(retainedOriginal.begin(), retainedOriginal.end(), expectedOriginal.begin()),
            "rejected optional expansion changed sealed Original samples");
        require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::First, soughtOwner,
            {bank, NativePlaybackBankIntent{}}, controls(4U, 1.0F, 0.0F, MixerSourceMode::Stems))) ==
            NativePlaybackPublishStatus::Accepted, "streamed late parts may become the source after real native readiness");
        FakeOutputRecord settledStems{};
        waitUntil([&] {
            static_cast<void>(output.consumeFrames(256U)); runtime.notifyIngressAvailable();
            const auto count = output.recordCount();
            if (count <= recordsBeforeParts) return false;
            const auto record = output.record(count - 1U);
            if (record.token != soughtOwner.selectionToken || record.generation != soughtOwner.generation ||
                record.sourceMode != NativeAudioSourceMode::Stems || !record.sourceSettled || record.peak <= .001F)
                return false;
            settledStems = record;
            return true;
        }, "streamed late Stems must actually render while retaining the sought owner");
        const auto finalDiagnostic = runtime.diagnostics();
        require(output.commitCount() == 2U &&
            sameOwnership(runtime.renderReadinessDiagnostics().ownership, soughtOwner) &&
            sameOwnership(finalDiagnostic.requestedOwnership, soughtOwner) &&
            sameOwnership(finalDiagnostic.acceptedOwnership, soughtOwner) &&
            sameOwnership(finalDiagnostic.committedOwnership, soughtOwner) &&
            finalDiagnostic.outputInvalidRenderResults == 0U,
            "streamed fallback preserves exact Original owner without a re-seek or reopen");
        const auto expectedRenderedOriginal = tone(settledStems.frames,
            static_cast<std::uint64_t>(settledStems.sourceStart), .025F, 220.0);
        std::uint64_t expectedHash = 14695981039346656037ULL;
        for (const float sample : expectedRenderedOriginal) {
            expectedHash ^= std::bit_cast<std::uint32_t>(sample);
            expectedHash *= 1099511628211ULL;
        }
        require(settledStems.originalPcmHash == expectedHash,
            "streamed Stems transition retains the exact cached Original samples at its rendered absolute source cursor");
        return;
    }
    try { waitUntil([&] {
        static_cast<void>(output.consumeFrames(256U));
        runtime.notifyIngressAvailable();
        return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == (1U << kLaneCount) - 1U;
    }, "frame-zero optional lanes must seal the real cache while the nonzero Original keeps rendering", 8'000U); }
    catch (...) {
        const auto readiness = runtime.renderReadinessDiagnostics();
        const auto diagnostics = runtime.diagnostics();
        std::cerr << "late-cached-seek mask=" << readiness.currentSongCacheCompleteLaneMask
                  << " bound=" << readiness.currentSongCacheBoundToSelectedOwner
                  << " record-count=" << output.recordCount() << " playing=" << output.playing()
                  << " cache-lanes=" << diagnostics.canonicalPcmIo.admittedLaneCount << '\n';
        for (std::size_t lane = 0U; lane < kLaneCount; ++lane)
            std::cerr << "lane=" << lane << " appended=" << diagnostics.banks[0U].lanes[lane].appendedFrames
                      << " eos=" << diagnostics.banks[0U].lanes[lane].endOfStreamMarks
                      << " eosfail=" << diagnostics.banks[0U].lanes[lane].endOfStreamFailures
                      << " rejects=" << diagnostics.banks[0U].lanes[lane].discontinuityRejects
                      << " first=" << readiness.lanes[lane].firstTimelineFrame
                      << " end=" << readiness.lanes[lane].endTimelineFrameExclusive << '\n';
        throw;
    }
    require(output.recordCount() > recordsBeforeParts && output.playing() && output.commitCount() == 2U,
        "late cache construction preserves actual playing Original and its physical owner");
    std::array<float, 512U> actual{};
    for (std::size_t lane = 1U; lane < kLaneCount; ++lane) {
        for (const auto firstFrame : {0U, static_cast<std::uint32_t>(seekFrame)}) {
            require(runtime.readPcmCacheForAudit(key, lane, firstFrame, actual.data(), 256U),
                "late optional canonical prefix and sought range survive actual Original rendering");
            const auto expected = tone(256U, firstFrame, .025F + .002F * static_cast<float>(lane),
                220.0 + 17.0 * static_cast<double>(lane));
            require(std::equal(actual.begin(), actual.end(), expected.begin()),
                "late-after-seek cache contains the actual frame-zero optional PCM bits");
        }
    }
    require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::First, soughtOwner,
        {bank, NativePlaybackBankIntent{}}, controls(4U, 1.0F, 0.0F, MixerSourceMode::Stems))) == NativePlaybackPublishStatus::Accepted,
        "ready late Stems receives the actual source-mode command");
    waitUntil([&] {
        output.consumeAll(); runtime.notifyIngressAvailable();
        const auto count = output.recordCount();
        return count != 0U && output.record(count - 1U).sourceMode == NativeAudioSourceMode::Stems &&
            output.record(count - 1U).sourceSettled && output.record(count - 1U).peak > .001F;
    }, "the real sealed late cache produces ready Stems on the unchanged sought owner");
    require(output.commitCount() == 2U && runtime.renderReadinessDiagnostics().lanes[0U].ingressFailureGeneration == 0U,
        "late frame-zero parts preserve the exact sought Original ownership and ingress");
}

void unsealedCurrentSongCacheSurvivesRepeatedSeekWithItsFrameZeroBuilder() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    for (const unsigned mismatch : {0U, 1U, 2U}) {
        constexpr SongRenderOwnership consumer{27'001U, 5U}, builder{27'001U, 6U};
        const SongRenderOwnership sought{consumer.selectionToken + (mismatch == 1U ? 1U : 0U), 7U};
        SongLaneIngressBank first(65'536U, 480U), second(65'536U, 480U);
        require(first.configureLane(0U, kSampleRate, consumer.generation), "repeated seek Original configured");
        configureLanes(second, builder.generation);
        offerLane(first, 0U, consumer.generation, 40'000U, 20'000U, false);
        offerLane(second, 0U, builder.generation, 0U, 16'384U, false);
        for (std::size_t lane = 1U; lane < kLaneCount; ++lane)
            offerLane(second, lane, builder.generation, 0U, 960U, false);
        FakeOutput output;
        output.setAutoAcknowledgePausedCommit(true);
        NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
        [[maybe_unused]] const auto cleanup = onScopeExit([&] {
            denyReadConsumerGeneration.store(0U);
            releaseBlocked.store(true); fault.store(0); stopAndJoin(runtime);
        });
        std::array<NativePlaybackBankIntent, 2U> banks{
            assignedBank(1U, consumer, 40'000U), assignedBank(1U, builder)};
        for (auto &bank : banks) bank.cacheFrameUpperBound = 60'000U;
        require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, consumer,
            banks, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(),
            "unsealed frame-zero cache starts behind the sought Original");
        waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
            return ready.currentSongCacheBoundToSelectedOwner && ready.currentSongCacheCompleteLaneMask == 0U;
        }, "partial Original cache promotes before its frame-zero builder seals");
        const CurrentSongCacheKey originalKey{consumer.selectionToken, consumer.generation,
            banks[0U].songIdentityHash, banks[0U].originalContentHash, banks[0U].stemPackageHash};
        std::array<float, 512U> prefix{};
        require(runtime.readPcmCacheForAudit(originalKey, 0U, 0U, prefix.data(), 256U),
            "the first seek has an actual committed Original prefix");
        const auto expected = tone(256U, 0U, 0.025F, 220.0);
        require(std::equal(prefix.begin(), prefix.end(), expected.begin()), "initial prefix has the expected PCM bits");
        require(!runtime.pcmCacheRangeReady(originalKey, 127U, 0U, 2'048U),
            "partial inactive stems do not claim complete lane coverage");
        std::uint64_t builderOriginalNext = 16'384U;
        if (mismatch == 0U) {
            waitUntil([&] {
                return runtime.pcmCacheRangeReady(originalKey, 1U, 16'128U, 16'384U);
            }, "initial producer prefixes are committed before the controlled second seek");
            blocked.store(false); blockTimedOut.store(false); releaseBlocked.store(false); fault.store(7);
            offerLane(second, 0U, builder.generation, builderOriginalNext, 4'096U, false);
            builderOriginalNext += 4'096U;
            runtime.notifyIngressAvailable();
            waitUntil([&] { return blocked.load(); }, "the real Original write holds cache rebind pending");
        }
        require(first.lane(0U)->advanceGeneration(sought.generation), "second seek advances its exact live ingress");
        offerLane(first, 0U, sought.generation, 48'000U, 12'000U, false);
        banks[0U] = assignedBank(2U, sought, 48'000U);
        banks[0U].cacheFrameUpperBound = 60'000U;
        if (mismatch == 2U) ++banks[0U].originalContentHash;
        const auto cycleBeforeSeek = runtime.diagnostics().renderCycles;
        require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, sought,
            banks, controls(2U), false)) == NativePlaybackPublishStatus::Accepted,
            "second paused seek publishes with its own exact identity");
        if (mismatch == 0U) {
            waitUntil([&] {
                runtime.notifyIngressAvailable();
                const auto progress = runtime.diagnostics();
                return progress.commandSnapshotsConsumed >= 2U && progress.renderCycles >= cycleBeforeSeek + 8U;
            }, "the newer seek is reconciled while the authoritative cache write remains pending");
            require(!blockTimedOut.load(), "the controlled cache write must not escape its bounded barrier");
            require(runtime.diagnostics().banks[1U].lanes[0U].discontinuityRejects == 0U,
                "a pending seek cannot reroute the exact frame-zero Original away from its authoritative cache");
            readConsumerAdmissionDenials.store(0U);
            denyReadConsumerGeneration.store(sought.generation);
            fault.store(0); releaseBlocked.store(true);
        }
        const CurrentSongCacheKey soughtKey{sought.selectionToken, sought.generation,
            banks[0U].songIdentityHash, banks[0U].originalContentHash, banks[0U].stemPackageHash};
        if (mismatch != 0U) {
            waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
                return sameOwnership(ready.ownership, sought) && runtime.controlProgress().banks[0U].appliedIntentRevision == 2U;
            }, "different owner/content seek physically installs");
            require(!runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner &&
                !runtime.pcmCacheRangeReady(soughtKey, 1U, 0U, 256U),
                "a retained builder cannot donate another owner or content's partial prefix");
            continue;
        }
        waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
            return readConsumerAdmissionDenials.load() != 0U && sameOwnership(ready.ownership, sought) &&
                !ready.currentSongCacheBoundToSelectedOwner &&
                runtime.pcmCacheRangeReady(soughtKey, 1U, 0U, 256U);
        }, "second seek retains its exact prefix while selected reader admission is held");
        require(!runtime.pcmCacheRangeReady(originalKey, 1U, 0U, 256U) &&
            !runtime.pcmCacheRangeReady(soughtKey, 127U, 0U, 2'048U),
            "rebind retires old authority without inventing missing stem coverage");
        offerLane(second, 0U, builder.generation, builderOriginalNext, 60'000U - builderOriginalNext, true);
        for (std::size_t lane = 1U; lane < kLaneCount; ++lane)
            offerLane(second, lane, builder.generation, 960U, 60'000U - 960U, true);
        runtime.notifyIngressAvailable();
        try {
            waitUntil([&] {
                const auto progress = runtime.diagnostics();
                for (std::size_t lane = 0U; lane < kLaneCount; ++lane)
                    require(progress.banks[1U].lanes[lane].discontinuityRejects == 0U,
                        "held reader admission must not reroute the exact growing cache producer");
                return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 127U &&
                    runtime.pcmCacheRangeReady(soughtKey, 127U, 48'000U, 49'024U);
            }, "the original frame-zero producer finishes every admitted lane after the second seek", 8'000U);
        } catch (...) {
            const auto ready = runtime.renderReadinessDiagnostics();
            const auto diagnostics = runtime.diagnostics();
            std::cerr << "Repeated seek cache: mask=" << ready.currentSongCacheCompleteLaneMask
                << " bound=" << ready.currentSongCacheBoundToSelectedOwner
                << " grow=" << ready.currentSongCacheCanGrow
                << " bytes=" << diagnostics.canonicalPcmIo.fileBytes << '\n';
            for (std::size_t lane = 0U; lane < kLaneCount; ++lane) {
                const auto progress = diagnostics.banks[1U].lanes[lane];
                std::cerr << "builder lane=" << lane << " pulled=" << progress.pulledFrames
                    << " appended=" << progress.appendedFrames << " retained=" << progress.retainedSuffixFrames
                    << " gaps=" << progress.discontinuityRejects << " eos=" << progress.endOfStreamMarks << '\n';
            }
            throw;
        }
        require(runtime.readPcmCacheForAudit(soughtKey, 0U, 0U, prefix.data(), 256U) &&
            std::equal(prefix.begin(), prefix.end(), expected.begin()),
            "completion preserves the exact previously committed Original PCM");
        for (const auto frame : {20'480U, 24'320U}) {
            const auto retainedExpected = tone(256U, frame, 0.025F, 220.0);
            require(runtime.readPcmCacheForAudit(soughtKey, 0U, frame, prefix.data(), 256U) &&
                std::equal(prefix.begin(), prefix.end(), retainedExpected.begin()),
                "canonical closure preserves exact Original samples beyond the pre-seek prefix");
        }
        require(!runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner,
            "completed storage must not claim a live reader before held admission succeeds");
        denyReadConsumerGeneration.store(0U); runtime.notifyIngressAvailable();
        waitUntil([&] { const auto ready = runtime.renderReadinessDiagnostics();
            return sameOwnership(ready.ownership, sought) && ready.currentSongCacheBoundToSelectedOwner &&
                ready.currentSongCacheCompleteLaneMask == 127U;
        }, "reader admission retries after accepted growing cache completion without new decoder PCM");
        require(runtime.renderReadinessDiagnostics().lanes[0U].ingressFailureGeneration == 0U,
            "preserved cache and sought live ingress remain consistent");
    }
}

void nonzeroSelectedOwnerBuildsCurrentSongCacheOnSameSongSpareBank(
    bool failOptionalBuilder = false, bool retireBuilder = false) {
    using namespace stemwise::audio::engine;
    for (const int mode : (failOptionalBuilder || retireBuilder ? std::vector<int>{4} : std::vector<int>{0, 1, 2, 3, 4, 5})) {
        const bool shortRedundantOriginalEos = mode == 5;
        const bool withStems = mode == 1 || mode == 2 || mode == 4 || shortRedundantOriginalEos;
        const bool partialOriginal = mode == 3 || mode == 4;
        const bool paused = mode >= 2;
        const bool partialStems = mode == 2 || shortRedundantOriginalEos;
        if (shortRedundantOriginalEos) {
            CurrentSongPcmCache incomplete;
            const CurrentSongCacheKey incompleteKey{1'709U, 2U, 3U, 4U, 0U};
            require(incomplete.admitStorage(1U, 4'096U) == CurrentSongCacheStorageStatus::Admitted &&
                incomplete.begin(incompleteKey) == CurrentSongCacheBeginStatus::Accepted,
                "uncompleted Original conflict fixture admits real storage");
            const auto pcm = tone(1'024U, 0U, .025F, 220.0);
            require(incomplete.offer(incompleteKey, 0U, 0U, pcm.data(), 1'024U).status ==
                CurrentSongCacheOfferStatus::Accepted, "uncompleted Original retains its exact prefix");
            require(incomplete.sealLane(incompleteKey, 0U, 1'023U) == CurrentSongCacheSealStatus::ConflictingEnd,
                "first Original EOS cannot contradict its accepted PCM boundary");
            require(incomplete.snapshot().completeLaneMask == 0U && incomplete.snapshot().publishedFrames[0U] == 0U &&
                incomplete.snapshot().nextExpectedFrames[0U] == 1'024U,
                "rejected first EOS preserves uncompleted Original without publishing a false end");
            require(incomplete.sealLane(incompleteKey, 0U, 1'024U) == CurrentSongCacheSealStatus::Accepted,
                "the exact first Original endpoint remains retryable");
        }
        SongLaneIngressBank first(32'768U, 480U);
        SongLaneIngressBank second(65'536U, 480U);
        constexpr SongRenderOwnership consumerOwner{1'710U, 5U};
        constexpr SongRenderOwnership builderOwner{1'710U, 6U};
        const std::uint64_t seekFrame = partialOriginal ? 40'000U : 4'800U;
        constexpr std::uint32_t allLaneMask = (1U << kLaneCount) - 1U;
        require(first.configureLane(0U, kSampleRate, consumerOwner.generation),
            "post-seek consumer Original configured");
        if (withStems) configureLanes(second, builderOwner.generation);
        else require(second.configureLane(0U, kSampleRate, builderOwner.generation),
            "Original-only frame-zero builder configured");
        offerLane(first, 0U, consumerOwner.generation, seekFrame, partialStems ? 10'000U : 20'000U, false);
        if (withStems && !partialStems && !partialOriginal) offerAllLanes(second, builderOwner.generation, 20'000U);
        else {
            offerLane(second, 0U, builderOwner.generation, 0U, partialOriginal ? 16'384U : 20'000U, !partialOriginal);
            if (withStems) for (std::size_t lane = 1U; lane < kLaneCount; ++lane)
                offerLane(second, lane, builderOwner.generation, 0U, 960U, false);
        }

        FakeOutput output(8'192U, 64U);
        output.setMaximumAcceptance(256U);
        output.setAutoAcknowledgePausedCommit(true);
        auto configuration = runtimeConfiguration(32'768U, 256U);

        NativePlaybackRuntime runtime(configuration, first, second, output.port());
        std::array<NativePlaybackBankIntent, 2U> banks{
            assignedBank(1U, consumerOwner, seekFrame), NativePlaybackBankIntent{}};
        banks[0].stemPackageHash = 0U;
        banks[0].stemLaneCount = 0U;
        require(runtime.publishCommand(selectedCommand(
            1U,
            NativePlaybackBank::First,
            consumerOwner,
            banks,
            controls(1U, paused && !failOptionalBuilder ? 2.0F : 1.0F), !paused)) == NativePlaybackPublishStatus::Accepted,
            "post-seek Original selected");
        require(runtime.start(), "post-seek cache-builder runtime starts");
        waitUntil([&] { return output.commitCount() == 1U; },
            "post-seek Original committed");

        banks[0] = assignedBank(2U, consumerOwner, seekFrame);
        banks[1] = assignedBank(1U, builderOwner);
        if (!withStems) for (auto &bank : banks) {
            bank.stemPackageHash = 0U;
            bank.stemLaneCount = 0U;
        }
        require(runtime.publishCommand(selectedCommand(
            2U,
            NativePlaybackBank::First,
            consumerOwner,
            banks,
            controls(2U, paused && !failOptionalBuilder ? 2.0F : 1.0F), !paused)) == NativePlaybackPublishStatus::Accepted,
            "same-song frame-zero cache builder assigned off owner");
        require(runtime.publishDirectLoopPolicy(
            1U,
            consumerOwner.selectionToken,
            consumerOwner.generation,
            false,
            0U,
            0U) == NativePlaybackPublishStatus::Accepted,
            "disabled loop policy accepted while the spare bank owns cache construction");

        waitUntil([&] {
            const auto readiness = runtime.renderReadinessDiagnostics();
            return sameOwnership(readiness.ownership, consumerOwner) &&
                readiness.currentSongCacheBoundToSelectedOwner &&
                readiness.currentSongCacheCompleteLaneMask ==
                    (partialOriginal ? 0U : withStems && !partialStems ? allLaneMask : 1U);
        }, "off-owner cache promotes to the exact nonzero selected owner", 8'000U);
        require(output.commitCount() == 1U && output.playing() == !paused,
            "cache construction neither switches owner nor stops Original playback");
        const stemwise::audio::engine::CurrentSongCacheKey key{consumerOwner.selectionToken, consumerOwner.generation,
            banks[0].songIdentityHash, banks[0].originalContentHash, banks[0].stemPackageHash};
        if (retireBuilder) {
            [[maybe_unused]] const auto cleanup = onScopeExit([&] { stopAndJoin(runtime); });
            waitUntil([&] { return runtime.pcmCacheRangeReady(key, 1U, 0U, 256U) &&
                runtime.diagnostics().banks[0U].lanes[0U].appendedFrames == 20'000U; },
                "retained prefix and ordinary sought suffix are independently accepted");
            banks[1] = NativePlaybackBankIntent{};
            banks[1].revision = 2U;
            require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First,
                consumerOwner, banks, controls(3U, 2.0F), false)) == NativePlaybackPublishStatus::Accepted,
                "incomplete frame-zero builder retires before the clean replacement is ready");
            waitUntil([&] { return runtime.controlProgress().banks[1U].appliedIntentRevision == 2U; },
                "frame-zero builder unassignment is physically applied");
            offerLane(first, 0U, consumerOwner.generation, seekFrame + 20'000U, 4096U, true);
            runtime.notifyIngressAvailable();
            waitUntil([&] { const auto lane = runtime.diagnostics().banks[0U].lanes[0U];
                return lane.discontinuityRejects != 0U || lane.endOfStreamMarks != 0U; },
                "ordinary suffix receives an actual ingress disposition after builder retirement");
            const auto lane = runtime.diagnostics().banks[0U].lanes[0U];
            require(runtime.renderReadinessDiagnostics().lanes[0U].ingressFailureGeneration == 0U && lane.discontinuityRejects == 0U &&
                lane.endOfStreamFailures == 0U && lane.appendedFrames == 24'096U,
                "retiring an incomplete cache builder must not route the contiguous sought suffix into its prefix gap");
            std::array<float, 512U> prefix{};
            require(runtime.readPcmCacheForAudit(key, 0U, 0U, prefix.data(), 256U),
                "builder retirement preserves its published canonical Original prefix");
            const auto expectedPrefix = tone(256U, 0U, 0.025F, 220.0);
            require(std::equal(prefix.begin(), prefix.end(), expectedPrefix.begin()) &&
                !runtime.pcmCacheRangeReady(key, 1U, seekFrame, seekFrame + 256U) &&
                runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 0U,
                "ordinary suffix neither fills the canonical gap nor invents cache completion");
            require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::First,
                consumerOwner, banks, controls(4U), true)) == NativePlaybackPublishStatus::Accepted,
                "the exact sought Original resumes while replacement preparation continues");
            waitUntil([&] { output.consumeAll(); return runtime.diagnostics().audibleEnd.sequence != 0U; },
                "the ordinary sought suffix drains to its actual end without a cache builder", 8'000U);
            double next = static_cast<double>(seekFrame);
            for (std::size_t i = output.currentGenerationFirstRecord(); i < output.recordCount(); ++i) {
                const auto block = output.record(i);
                require(block.token == consumerOwner.selectionToken && block.generation == consumerOwner.generation &&
                    block.sourceStart == next, "builder retirement preserves exact PCM ownership and continuity");
                const auto expected = tone(block.frames, static_cast<std::uint64_t>(next), 0.025F, 220.0);
                std::uint64_t hash = 14695981039346656037ULL;
                for (const auto sample : expected) { hash ^= std::bit_cast<std::uint32_t>(sample); hash *= 1099511628211ULL; }
                require(block.originalPcmHash == hash, "builder retirement preserves the ordinary Original PCM bits");
                next = block.sourceEnd;
            }
            require(next == static_cast<double>(seekFrame + 24'096U) &&
                sameOwnership(runtime.diagnostics().audibleEnd.ownership, consumerOwner),
                "only the exact sought consumer publishes its real EOS");
            return;
        }
        if (failOptionalBuilder) {
            using namespace stemwise::audio::engine::cache_test;
            [[maybe_unused]] const auto cleanup = onScopeExit([&] { fault.store(0); stopAndJoin(runtime); });
            fault.store(1);
            observed.store(false);
            offerLane(second, 1U, builderOwner.generation, 960U, 1088U, true);
            runtime.notifyIngressAvailable();
            waitUntil([&] { return observed.load(); }, "retained builder's optional write actually fails");
            fault.store(0);
            waitUntil([&] { return runtime.diagnostics().banks[1U].lanes[1U].endOfStreamMarks == 1U; },
                "failed retained cache retires optional EOS without resetting the sought Original");
            const auto before = runtime.diagnostics().banks[0U].lanes[0U].appendedFrames;
            offerLane(first, 0U, consumerOwner.generation, seekFrame + 20'000U, 4096U, false);
            runtime.notifyIngressAvailable();
            waitUntil([&] { return runtime.diagnostics().banks[0U].lanes[0U].appendedFrames == before + 4096U; },
                "sought ordinary Original continues after optional builder failure");
            require(runtime.renderReadinessDiagnostics().lanes[0U].ingressFailureGeneration == 0U &&
                runtime.diagnostics().banks[0U].lanes[0U].discontinuityRejects == 0U,
                "optional builder failure changed the ordinary Original's owned cursor");
            require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First,
                consumerOwner, banks, controls(3U), true)) == NativePlaybackPublishStatus::Accepted,
                "ordinary Original resumes after retained optional failure");
            waitUntil([&] { output.consumeAll(); const auto count = output.recordCount();
                return count != 0U && output.record(count - 1U).sourceEnd >= 63'000.0; },
                "ordinary Original renders its real suffix after retained optional failure");
            double next = static_cast<double>(seekFrame);
            for (std::size_t i = output.currentGenerationFirstRecord(); i < output.recordCount(); ++i) {
                const auto block = output.record(i);
                require(block.token == consumerOwner.selectionToken && block.generation == consumerOwner.generation &&
                    block.sourceStart == next, "retained optional failure duplicated PCM or replaced its consumer");
                const auto expected = tone(block.frames, static_cast<std::uint64_t>(next), 0.025F, 220.0);
                std::uint64_t hash = 14695981039346656037ULL;
                for (const auto sample : expected) { hash ^= std::bit_cast<std::uint32_t>(sample); hash *= 1099511628211ULL; }
                require(block.originalPcmHash == hash, "retained optional failure changed ordinary Original PCM bits");
                next = block.sourceEnd;
            }
            return;
        }
        if (partialOriginal) {
            require(!runtime.pcmCacheRangeReady(key, 1U, seekFrame, seekFrame + 256U),
                "ownership alone cannot invent the not-yet-decoded sought range");
            offerLane(second, 0U, builderOwner.generation, 16'384U, 32'000U, false);
            runtime.notifyIngressAvailable();
        }
        waitUntil([&] { return runtime.pcmCacheRangeReady(key, 1U, seekFrame, seekFrame + 256U); },
            "exact sought Original range becomes resident without waiting for unused stems");
        if (partialOriginal) {
            std::array<float, 512U> pcm{};
            require(runtime.readPcmCacheForAudit(key, 0U, seekFrame, pcm.data(), 256U),
                "partial Original readback reaches the retained frame-zero producer");
            const auto expected = tone(256U, seekFrame, 0.025F, 220.0);
            require(std::equal(pcm.begin(), pcm.end(), expected.begin()),
                "partial Original handoff preserves exact published PCM bits");
            require(runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == 0U,
                "bounded paused readiness never substitutes for Original EOF");
            require(second.lane(0U)->markEndOfStream(builderOwner.generation, 48'384U).status ==
                    LaneEndOfStreamStatus::Accepted,
                "the retained Original producer publishes its real end");
            runtime.notifyIngressAvailable();
            waitUntil([&] {
                return (runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask & 1U) != 0U;
            }, "the retained Original producer seals after ownership transfer");
            require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, consumerOwner,
                    banks, controls(3U, 2.0F), true)) == NativePlaybackPublishStatus::Accepted,
                "the partially built Original resumes on its exact sought owner");
            waitUntil([&] {
                output.consumeAll();
                return runtime.diagnostics().audibleEnd.sequence != 0U;
            }, "retained producer EOS reaches the exact consumer after its PCM drains", 8'000U);
            require(sameOwnership(runtime.diagnostics().audibleEnd.ownership, consumerOwner),
                "Original EOS never belongs to the retired builder generation");
        }
        if (partialStems) {
            require(runtime.publishDirectLoopPolicy(2U, consumerOwner.selectionToken, consumerOwner.generation,
                true, 2'048U, 16'000U, 0U, true) == NativePlaybackPublishStatus::Accepted,
                "partial optional builder preserves the selected Original loop");
            waitUntil([&] { return output.loopCutoverCommitCount() != 0U; },
                "complete Original prepares actual A while optional builder is still live");
            require(output.presentCommittedLoopCutover(), "callback adopts the exact cache-backed A producer");
            waitUntil([&] {
                static_cast<void>(runtime.renderReadinessDiagnostics());
                return output.loopCutoverWriteReleaseCount() != 0U;
            }, "runtime adopts A before the sought decoder publishes its later suffix");
            const auto before = runtime.diagnostics().banks[0U].lanes[0U].appendedFrames;
            const std::uint64_t redundantTailFrames = shortRedundantOriginalEos ? 4'200U : 5'200U;
            offerLane(first, 0U, consumerOwner.generation, 14'800U, redundantTailFrames, true);
            runtime.notifyIngressAvailable();
            waitUntil([&] {
                const auto lane = runtime.diagnostics().banks[0U].lanes[0U];
                if (lane.discontinuityRejects != 0U || lane.endOfStreamFailures != 0U)
                    std::cerr << "Redundant Original EOS: mode=" << mode << " rawEnd=" << 14'800U + redundantTailFrames
                        << " canonicalEnd=20000 gaps=" << lane.discontinuityRejects
                        << " eosFailures=" << lane.endOfStreamFailures << " eosMarks=" << lane.endOfStreamMarks << '\n';
                require(lane.discontinuityRejects == 0U && lane.endOfStreamFailures == 0U,
                    "complete bound Original drains sought ingress without appending B into A's empty reservoir");
                return lane.appendedFrames >= before + redundantTailFrames && lane.endOfStreamMarks != 0U;
            }, "late sought Original suffix and actual EOS drain under the selected cache owner");
            require(!runtime.pcmCacheRangeReady(key, allLaneMask, seekFrame, seekFrame + 256U),
                "partial optional lanes cannot claim ready Stems");
            for (std::size_t lane = 1U; lane < kLaneCount; ++lane)
                offerLane(second, lane, builderOwner.generation, 960U, 19'040U, true);
            runtime.notifyIngressAvailable();
            waitUntil([&] {
                return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == allLaneMask;
            }, "the exact retained builder can finish optional lanes after Original ownership promotion");
            require(runtime.pcmCacheRangeReady(key, allLaneMask, seekFrame, seekFrame + 256U),
                "completed same-package stems become resident for the exact sought owner");
            if (shortRedundantOriginalEos) {
                std::array<float, 512U> tail{};
                waitUntil([&] { return runtime.readPcmCacheForAudit(key, 0U, 19'744U, tail.data(), 256U); },
                    "short redundant EOS preserves the canonical Original tail");
                const auto expected = tone(256U, 19'744U, .025F, 220.0);
                require(std::equal(tail.begin(), tail.end(), expected.begin()) &&
                    !runtime.pcmCacheRangeReady(key, 1U, 20'000U, 20'001U),
                    "redundant seek EOF neither truncates canonical payload nor extends its end");
                require(runtime.publishDirectLoopPolicy(3U, consumerOwner.selectionToken, consumerOwner.generation,
                    false, 0U, 0U) == NativePlaybackPublishStatus::Accepted, "sealed Original leaves its physical loop");
                waitUntil([&] { const auto policy = output.acceptedLoopPolicy();
                    return policy.revision == 3U && !policy.enabled && policy.physicalReady &&
                        output.observeCommittedLoopCutover(policy.physicalRevision());
                }, "linear sealed-Original replacement is physically committed");
                require(output.presentCommittedLoopCutover(), "callback adopts the sealed Original linear replacement");
                require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, consumerOwner,
                    banks, controls(3U, 2.0F), true)) == NativePlaybackPublishStatus::Accepted,
                    "sealed Original resumes after the redundant short decoder EOF");
                waitUntil([&] { output.consumeAll(); return runtime.diagnostics().audibleEnd.sequence != 0U; },
                    "canonical Original reaches its actual presented endpoint", 8'000U);
                const auto terminal = output.record(output.recordCount() - 1U);
                require(terminal.token == consumerOwner.selectionToken && terminal.generation == consumerOwner.generation &&
                    terminal.frames != 0U && terminal.sourceEnd == 20'000.0 &&
                    sameOwnership(runtime.diagnostics().audibleEnd.ownership, consumerOwner),
                    "short redundant EOS cannot move the complete canonical presentation endpoint");
            }
        }
        stopAndJoin(runtime);
    }
}

void blockedResourcesCannotOwnTheRenderWorker() {
    using namespace stemwise::audio::engine::cache_test;
    {
        fault.store(6);
        blocked.store(false);
        blockTimedOut.store(false);
        releaseBlocked.store(false);
        SongLaneIngressBank first(8'192U, 480U), second(8'192U, 480U);
        constexpr SongRenderOwnership initial{8'990U, 2U}, sought{8'990U, 3U};
        constexpr std::uint64_t seekFrame = 240'000U;
        require(first.configureLane(0U, kSampleRate, initial.generation),
            "pending-create seek configures Original");
        offerLane(first, 0U, initial.generation, 0U, 960U, false);
        FakeOutput output;
        NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
        const auto cleanup = onScopeExit([&]() noexcept {
            releaseBlocked.store(true, std::memory_order_release);
            fault.store(0);
            stopAndJoin(runtime);
        });
        auto intent = assignedBank(1U, initial, 0U, 0U);
        intent.stemPackageHash = 0U;
        std::array<NativePlaybackBankIntent, 2U> banks{intent, NativePlaybackBankIntent{}};
        require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
            initial, banks, controls(1U))) == NativePlaybackPublishStatus::Accepted,
            "pending-create owner is selected");
        require(runtime.start(), "pending-create seek runtime starts");
        waitUntil([&] { return blocked.load(std::memory_order_acquire); },
            "initial frame-zero cache creation waits at real storage admission");
        offerLane(first, 0U, sought.generation, seekFrame, 960U, true);
        banks[0] = assignedBank(2U, sought, seekFrame, 0U);
        banks[0].stemPackageHash = 0U;
        require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First,
            sought, banks, controls(2U))) == NativePlaybackPublishStatus::Accepted,
            "nonzero seek supersedes blocked frame-zero creation");
        waitUntil([&] { return runtime.diagnostics().commandSnapshotsConsumed == 2U; },
            "seek consumes and retires pending creation before storage is released");
        const auto consumedAt = runtime.diagnostics().renderCycles;
        waitUntil([&] { return runtime.diagnostics().renderCycles >= consumedAt + 3U; },
            "old applied owner cannot recreate its cache during retirement wait");
        releaseBlocked.store(true, std::memory_order_release);
        fault.store(0);
        waitUntil([&] {
            return sameOwnership(runtime.diagnostics().acceptedOwnership, sought) && output.commitCount() == 1U;
        }, "pending cache retirement cannot strand the exact nonzero seek ingress");
        require(!blockTimedOut.load(), "pending-create seek releases the blocked operation before its timeout");
        require(output.presentFirstCurrentRecord(), "nonzero seek publishes its actual callback owner");
        waitUntil([&] { return sameOwnership(runtime.diagnostics().committedOwnership, sought); },
            "nonzero seek commits after pending cache retirement");
    }
    for (const bool preparedResult : {false, true}) {
        fault.store(0);
        blocked.store(false);
        blockTimedOut.store(false);
        releaseBlocked.store(true);
        const auto configured = runtimeConfiguration().pipeline;
        stemwise::audio::engine::SongRenderPipeline pipeline(configured);
        std::atomic<unsigned> completions{0U};
        stemwise::audio::engine::NativePlaybackResources resource(configured, {},
            [](void *context) noexcept { static_cast<std::atomic<unsigned> *>(context)->fetch_add(1U); },
            &completions);
        const auto cleanup = onScopeExit([&]() noexcept {
            releaseBlocked.store(true, std::memory_order_release);
            fault.store(0);
        });
        const stemwise::audio::engine::CurrentSongCacheKey originalKey{8'991U, 2U, 3U, 4U, 0U};
        bool finished = false;
        waitUntil([&] { return resource.createCache(originalKey, 1U, 8'192U, finished) != nullptr && finished; },
            "retirement overlap fixture admits an actual cache");
        auto nextKey = originalKey;
        ++nextKey.generation;
        if (!preparedResult) {
            const std::array<float, 32U> pcm{};
            waitUntil([&] { return resource.offer(originalKey, 0U, 0U, pcm.data(), 16U).status ==
                    stemwise::audio::engine::CurrentSongCacheOfferStatus::Accepted; },
                "retirement overlap fixture publishes Original");
            waitUntil([&] { return resource.seal(originalKey, 0U, 16U) ==
                    stemwise::audio::engine::CurrentSongCacheSealStatus::Accepted; },
                "retirement overlap fixture seals Original");
        }
        const auto beforeCompletion = completions.load();
        if (preparedResult) require(!resource.prepareLanes(0U, 1U, 2U, pipeline),
            "retirement overlap submits prepared lanes");
        else require(!resource.rebind(originalKey, nextKey, 1U),
            "retirement overlap submits an actual complete-cache rebind");
        waitUntil([&] { return completions.load() > beforeCompletion; },
            "old resource result completes before retirement begins");
        fault.store(11);
        releaseBlocked.store(false, std::memory_order_release);
        resource.retireCache();
        waitUntil([&] { return blocked.load(std::memory_order_acquire); },
            "resource worker holds retirement before old result cleanup");
        if (preparedResult) {
            resource.discardSupersededPreparation(0U, 2U);
            require(!resource.prepareLanes(0U, 2U, 0U, pipeline),
                "preparation cannot consume or acknowledge an old result during retirement");
        } else {
            require(resource.discardSupersededCacheResult(originalKey, 2U) ==
                    stemwise::audio::engine::NativePlaybackCacheDiscardStatus::Pending,
                "cache completion cannot be consumed while worker retirement owns it");
            require(resource.snapshot().key == originalKey,
                "unacknowledged retiring completion cannot publish its replacement identity");
        }
        require(!blockTimedOut.load(), "retirement overlap remains within the bounded hook");
        releaseBlocked.store(true, std::memory_order_release);
        fault.store(0);
        waitUntil([&] { return resource.prepareLanes(0U, 2U, 0U, pipeline); },
            "retirement acknowledgement releases the shared gate");
        finished = false;
        waitUntil([&] { return resource.createCache(nextKey, 1U, 8'192U, finished) != nullptr && finished; },
            "fresh cache admission succeeds after retiring result cleanup");
        require(resource.snapshot().key == nextKey && pipeline.stemLaneCount() == 0U,
            "retired result cannot replace newly admitted cache or pipeline state");
    }
    for (const bool pollOtherBank : {false, true}) {
        const auto configured = runtimeConfiguration().pipeline;
        stemwise::audio::engine::SongRenderPipeline firstPipeline(configured), secondPipeline(configured);
        std::atomic<unsigned> completions{0U};
        stemwise::audio::engine::NativePlaybackResources resource(configured, {},
            [](void *context) noexcept { static_cast<std::atomic<unsigned> *>(context)->fetch_add(1U); },
            &completions);
        require(!resource.prepareLanes(0U, 1U, 6U, firstPipeline),
            "first bank submits asynchronous lane preparation");
        waitUntil([&] { return completions.load() != 0U; },
            "first bank preparation completes before unrelated polling");
        if (pollOtherBank) {
            require(!resource.prepareLanes(1U, 1U, 2U, secondPipeline),
                "other bank waits while first bank owns its completed preparation");
        } else {
            require(resource.seal({1U, 2U, 3U, 4U, 0U}, 0U, 0U) ==
                    stemwise::audio::engine::CurrentSongCacheSealStatus::Pending,
                "unrelated Original EOS is admitted on its independent I/O owner");
        }
        require(resource.prepareLanes(0U, 1U, 6U, firstPipeline) && firstPipeline.stemLaneCount() == 6U,
            "unrelated polling cannot discard or recompute another bank's completed lane preparation");
        if (!pollOtherBank) {
            waitUntil([&] { return resource.seal({1U, 2U, 3U, 4U, 0U}, 0U, 0U) ==
                    stemwise::audio::engine::CurrentSongCacheSealStatus::NotBegun; },
                "independent EOS polling receives its exact no-cache receipt");
        }
        require(resource.diagnostics().operationsCompleted == (pollOtherBank ? 1U : 2U),
            "exact preparation and independent I/O each execute once without recomputation");
    }
    for (const int mode : {6, 7, 8, 9, 10}) {
        fault.store(0);
        blocked.store(false);
        blockTimedOut.store(false);
        prepareFailures.store(0U);
        releaseBlocked.store(false);
        SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
        SongRenderOwnership owner{9'700U + static_cast<std::uint64_t>(mode), 2U};
        require(first.configureLane(0U, kSampleRate, owner.generation), "blocked-resource Original configured");
        offerLane(first, 0U, owner.generation, 0U, 8'192U, mode != 7);
        FakeOutput output(4'096U, 64U);
        auto intent = assignedBank(1U, owner, 0U, 0U);
        intent.stemPackageHash = 0U;
        std::array<NativePlaybackBankIntent, 2U> banks{intent, NativePlaybackBankIntent{}};
        NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
        const auto cleanup = onScopeExit([&]() noexcept {
            releaseBlocked.store(true, std::memory_order_release);
            fault.store(0);
            runtime.requestStop();
            runtime.join();
        });
        require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner, banks,
            controls(1U))) == NativePlaybackPublishStatus::Accepted, "blocked-resource owner selected");
        require(runtime.start(), "blocked-resource runtime starts");
        waitUntil([&] { return output.commitCount() == 1U; }, "blocked-resource live Original committed");
        require(output.presentFirstCurrentRecord(), "blocked-resource live Original presented");
        waitUntil([&] {
            const auto ready = runtime.renderReadinessDiagnostics();
            return ready.currentSongCacheBoundToSelectedOwner &&
                (mode == 7 || ready.currentSongCacheCompleteLaneMask == 1U);
        }, "blocked-resource actual cache ready");
        const auto acceptedBefore = runtime.diagnostics().outputFramesAccepted;
        fault.store(mode);
        if (mode == 6) {
            for (std::size_t lane = 1U; lane <= 2U; ++lane) {
                require(first.configureLane(lane, kSampleRate, owner.generation),
                    "late optional decoder configures before native package admission");
                offerLane(first, lane, owner.generation, 0U, 8'192U, false);
            }
        }
        if (mode == 7) {
            offerLane(first, 0U, owner.generation, 8'192U, 8'192U, true);
            runtime.notifyIngressAvailable();
        } else {
            if (mode == 8) {
                ++owner.generation;
                banks[0] = assignedBank(2U, owner, 4'800U, 0U);
                banks[0].stemPackageHash = 0U;
            } else banks[0] = assignedBank(2U, owner, 0U, 2U);
            require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, owner, banks,
                controls(2U))) == NativePlaybackPublishStatus::Accepted, "resource work requested");
        }
        if (mode == 10) waitUntil([&] { return runtime.diagnostics().banks[0].rejectedBankIntents == 1U; },
            "actual setup allocation failure rejects only its exact intent");
        else waitUntil([&] { return blocked.load(std::memory_order_acquire); }, "actual I/O/setup boundary blocked");
        if (mode == 6) {
            const auto diagnostic = runtime.diagnostics();
            require(diagnostic.banks[0U].lanes[1U].pulledFrames == 0U &&
                    diagnostic.banks[0U].lanes[2U].pulledFrames == 0U &&
                    first.lane(1U)->diagnostics().readableSourceFrames != 0U,
                "prepared lane capacity cannot consume optional PCM before cache/bank admission commits");
        }
        const auto consumedBefore = runtime.diagnostics().commandSnapshotsConsumed;
        require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, owner, banks,
            controls(3U), false)) == NativePlaybackPublishStatus::Accepted, "pause accepted during blocked resource");
        waitUntil([&] {
            return runtime.diagnostics().commandSnapshotsConsumed > consumedBefore && !output.playing();
        }, "render worker consumes and applies pause while I/O/setup is blocked");
        require(!blockTimedOut.load(), "resource boundary did not release itself on timeout");
        require(runtime.diagnostics().outputFramesAccepted <= acceptedBefore + 4'096U,
            "blocked resource does not inflate the normal output reserve");
        if (mode == 10) require(prepareFailures.load() == 1U,
            "failed setup is not repeatedly allocated for the same rejected revision");
        if (mode == 9 || mode == 10) {
            // Recovery is healthy work. Replace the allocation fault with the bounded
            // preparation barrier before publication: consumption may be immediate.
            if (mode == 10) fault.store(9);
            ++owner.generation;
            banks[0] = assignedBank(3U, owner, 4'800U, 3U);
            require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::First, owner, banks,
                controls(4U), false)) == NativePlaybackPublishStatus::Accepted, "newest setup revision replaces blocked setup");
            waitUntil([&] { return runtime.diagnostics().commandSnapshotsConsumed >= 4U; },
                "newest setup command is consumed before the old operation releases");
            if (mode == 10) waitUntil([&] { return blocked.load(); },
                "healthy recovery reaches preparation before its barrier releases");
            require(blocked.load() && !releaseBlocked.load() &&
                !blockTimedOut.load() && runtime.diagnostics().banks[0].appliedIntentRevision < banks[0].revision,
                "superseding revision waits behind the exact still-blocked setup result");
        }
        releaseBlocked.store(true, std::memory_order_release);
        fault.store(0);
        waitUntilDiagnosed([&] {
            const auto diagnostics = runtime.diagnostics();
            return diagnostics.resourceOperationsCompleted != 0U &&
                diagnostics.resourceMaximumOperationMicros != 0U &&
                diagnostics.banks[0].appliedIntentRevision == banks[0].revision;
        }, [&] {
            const auto diagnostic = runtime.diagnostics();
            return "mode=" + std::to_string(mode) +
                " completed=" + std::to_string(diagnostic.resourceOperationsCompleted) +
                " maximumDurationUs=" + std::to_string(diagnostic.resourceMaximumOperationMicros) +
                " lastDurationUs=" + std::to_string(diagnostic.resourceLastOperationMicros) +
                " appliedRevision=" + std::to_string(diagnostic.banks[0].appliedIntentRevision) +
                " wantedRevision=" + std::to_string(banks[0].revision) +
                " rejectedIntents=" + std::to_string(diagnostic.banks[0].rejectedBankIntents) +
                " prepareFailures=" + std::to_string(prepareFailures.load()) +
                " blockTimedOut=" + std::to_string(blockTimedOut.load());
        }, "exact newest resource result admitted after release");
        require(runtime.diagnostics().outputInvalidRenderResults == 0U,
            "blocked/superseded resource preserves exact output ownership");
        if (mode == 10) require(prepareFailures.load() == 1U &&
            runtime.diagnostics().banks[0].rejectedBankIntents == 1U,
            "healthy recovery revision cannot inherit the retired allocation fault");
    }
}

void cacheTransferWaitsForItsDestinationIoOwner() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    NativePlaybackResources previous(runtimeConfiguration().pipeline, {}, [](void *) noexcept {}, nullptr);
    NativePlaybackResources destination(runtimeConfiguration().pipeline, {}, [](void *) noexcept {}, nullptr);
    const CurrentSongCacheKey key{1'789U, 2U, 5U, 7U, 0U};
    const auto pcm = tone(4'096U, 0U, .025F, 331.0);
    CurrentSongPcmCache *cache{}; bool finished{};
    waitUntil([&] { cache = previous.createCache(key, 1U, 4'096U, finished); return cache && finished; },
        "transfer fixture creates its real source cache");
    waitUntil([&] { return previous.offer(key, 0U, 0U, pcm.data(), 4'096U).status ==
        CurrentSongCacheOfferStatus::Accepted; }, "transfer fixture commits its real PCM");
    waitUntil([&] { return previous.seal(key, 0U, 4'096U) == CurrentSongCacheSealStatus::Accepted; },
        "transfer fixture commits EOS");
    std::atomic<bool> returned{false}, inherited{false};
    blocked.store(false); releaseBlocked.store(false); blockTimedOut.store(false); fault.store(12);
    std::jthread caller([&] {
        inherited.store(destination.inheritCacheFrom(previous), std::memory_order_release);
        returned.store(true, std::memory_order_release);
    });
    const auto unblock = onScopeExit([&]() noexcept { releaseBlocked.store(true); fault.store(0); });
    waitUntil([&] { return blocked.load(std::memory_order_acquire); },
        "destination I/O worker reaches its actual transfer operation", 2'000U);
    require(!returned.load(std::memory_order_acquire) && !destination.pcmCacheRangeReady(key, 1U, 0U, 512U),
        "cache transfer cannot return or publish PCM before destination I/O ownership acknowledges it");
    releaseBlocked.store(true, std::memory_order_release);
    caller.join();
    require(inherited.load(std::memory_order_acquire) && returned.load(std::memory_order_acquire) &&
        !blockTimedOut.load(), "cache transfer acknowledges exact destination operation after release");
    std::array<float, 1'024U> actual{};
    require(destination.readPcmCacheForAudit(key, 0U, 0U, actual.data(), 512U) &&
        std::memcmp(actual.data(), pcm.data(), sizeof(actual)) == 0,
        "destination I/O owner reads back unchanged transferred PCM");
}

void completedSongCacheSurvivesRuntimeReplacement() {
    SongLaneIngressBank first(8'192U, 480U);
    SongLaneIngressBank second(8'192U, 480U);
    constexpr SongRenderOwnership original{1'790U, 2U};
    constexpr SongRenderOwnership recovered{1'790U, 3U};
    configureLanes(first, original.generation);
    for (std::size_t lane = 0U; lane < kLaneCount; ++lane) {
        offerLane(first, lane, original.generation, 0U, 2'400U, true);
    }
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    auto configuration = runtimeConfiguration(16'384U, 480U);
    configuration.idleSleepMicros = 10'000U;
    NativePlaybackRuntime previous(configuration, first, second, output.port());
    std::array<NativePlaybackBankIntent, 2U> banks{
        assignedBank(1U, original), NativePlaybackBankIntent{.revision = 1U}};
    require(previous.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        original, banks, controls(1U), false)) == NativePlaybackPublishStatus::Accepted,
        "route fixture selected");
    require(previous.start(), "route fixture started");
    waitUntil([&] {
        const auto ready = previous.renderReadinessDiagnostics();
        return ready.currentSongCacheBoundToSelectedOwner &&
            ready.currentSongCacheCompleteLaneMask == 0x7FU;
    }, "route fixture sealed every lane");
    stopAndJoin(previous);
    NativePlaybackRuntime replacement(configuration, first, second, output.port());
    require(replacement.inheritCompletedSongCache(previous), "joined cache transfer accepted");
    banks[0] = assignedBank(2U, recovered, 960U);
    require(replacement.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        recovered, banks, controls(1U), false)) == NativePlaybackPublishStatus::Accepted,
        "replacement resumes at a 10 ms position");
    require(replacement.start(), "replacement started");
    waitUntil([&] {
        const auto ready = replacement.renderReadinessDiagnostics();
        return sameOwnership(ready.ownership, recovered) &&
            ready.currentSongCacheBoundToSelectedOwner &&
            ready.currentSongCacheCompleteLaneMask == 0x7FU;
    }, "replacement retained all seven lanes without decoding again");
    require(!replacement.inheritCompletedSongCache(previous),
        "running cache cannot be replaced under the render worker");
    stopAndJoin(replacement);
}

void selectedPartCountsAreBoundedAndInactiveIngressIsNeverScheduled() {
    for (const std::size_t partCount : {0U, 2U, 6U, 9U}) {
        const NativePlaybackIngressServicePlan plan(0U, 3U, {partCount + 1U, 0U});
        require(plan.valid() && plan.actionCount() == 1U + 6U * (partCount + 1U),
            "dynamic ingress service retained idle lane work");
        for (std::size_t action = 1U; action < plan.actionCount(); action += 2U) {
            const auto pull = plan.actionAt(action);
            require(pull.kind == NativePlaybackWorkerServiceKind::IngressLane &&
                pull.bank == 0U && pull.lane <= partCount,
                "dynamic ingress service scheduled an inactive bank/part");
        }
    }
    SongLaneIngressBank first(256U, 64U);
    SongLaneIngressBank second(256U, 64U);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    std::uint64_t revision = 1U;
    for (const std::uint32_t count : {0U, 2U, 6U, 9U}) {
        const SongRenderOwnership owner{600U + revision, revision};
        auto bank = assignedBank(revision, owner);
        bank.stemLaneCount = count;
        if (count == 0U) bank.stemPackageHash = 0U;
        require(runtime.publishCommand(selectedCommand(revision, NativePlaybackBank::First, owner,
            {bank, NativePlaybackBankIntent{}}, controls(revision), false)) ==
            NativePlaybackPublishStatus::Accepted, "supported native part count rejected");
        ++revision;
    }
    const SongRenderOwnership owner{610U, revision};
    for (const std::uint32_t count : {1U, 10U}) {
        auto bank = assignedBank(revision, owner);
        bank.stemLaneCount = count;
        require(runtime.publishCommand(selectedCommand(revision, NativePlaybackBank::First, owner,
            {bank, NativePlaybackBankIntent{}}, controls(revision), false)) ==
            NativePlaybackPublishStatus::InvalidSnapshot, "unsupported native package width accepted");
    }
    auto bank = assignedBank(revision, owner);
    bank.stemPackageHash = 0U;
    require(runtime.publishCommand(selectedCommand(revision, NativePlaybackBank::First, owner,
        {bank, NativePlaybackBankIntent{}}, controls(revision), false)) ==
        NativePlaybackPublishStatus::InvalidSnapshot, "nonzero part count accepted without package identity");
    const SongRenderOwnership retainedOwner{604U, 4U};
    const auto beforeRejection = runtime.diagnostics().commandSnapshotsPublished;
    auto replacement = assignedBank(revision, retainedOwner);
    replacement.stemLaneCount = 9U;
    ++replacement.stemPackageHash;
    require(runtime.publishCommand(selectedCommand(revision, NativePlaybackBank::First, retainedOwner,
        {replacement, NativePlaybackBankIntent{}}, controls(revision), false)) ==
        NativePlaybackPublishStatus::InvalidSnapshot, "same-owner package replacement accepted without retirement");
    replacement = assignedBank(revision, retainedOwner);
    replacement.stemLaneCount = 2U;
    require(runtime.publishCommand(selectedCommand(revision, NativePlaybackBank::First, retainedOwner,
        {replacement, NativePlaybackBankIntent{}}, controls(revision), false)) ==
        NativePlaybackPublishStatus::InvalidSnapshot, "same package accepted with a different declared width");
    require(runtime.diagnostics().commandSnapshotsPublished == beforeRejection,
        "rejected package update changed accepted command state");
    replacement.stemPackageHash = 0U;
    replacement.stemLaneCount = 0U;
    require(runtime.publishCommand(selectedCommand(revision, NativePlaybackBank::First, retainedOwner,
        {replacement, NativePlaybackBankIntent{}}, controls(revision), false)) ==
        NativePlaybackPublishStatus::Accepted, "rejected package update consumed valid explicit-retirement revision");
}

void wholeSongCacheBudgetFailurePreservesOrdinaryOriginalAndParts() {
    using stemwise::audio::engine::CurrentSongCacheStorageStatus;
    constexpr SongRenderOwnership owner{1'801U, 2U};
    constexpr std::uint32_t partCount = 9U;
    constexpr std::uint32_t allPartsMask = (1U << partCount) - 1U;
    SongLaneIngressBank first(32'768U, 480U);
    SongLaneIngressBank second(32'768U, 480U);
    configureLanes(first, owner.generation, partCount + 1U);
    for (std::size_t lane = 0U; lane <= partCount; ++lane) {
        offerLane(first, lane, owner.generation, 0U, 12'000U, true);
    }
    FakeOutput output(512U, 64);
    output.setMaximumAcceptance(64U);
    NativePlaybackRuntime runtime(
        runtimeConfiguration(32'768U, 64U), first, second, output.port());
    [[maybe_unused]] const auto runtimeCleanup = onScopeExit([&]() noexcept {
        runtime.requestStop();
        runtime.join();
    });
    auto bank = assignedBank(1U, owner, 0U, partCount);
    // This multiplication cannot fit in the cache budget, regardless of host free space.
    bank.cacheFrameUpperBound = std::numeric_limits<std::uint64_t>::max();
    const std::array<NativePlaybackBankIntent, 2U> banks{bank, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        owner, banks, controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "optional cache overflow must not reject a playable owner");
    require(runtime.start(), "cache budget fallback runtime starts");
    waitUntil([&] { return output.commitCount() == 1U; },
        "cache budget fallback Original commits");
    require(output.presentFirstCurrentRecord(), "cache budget fallback owner presented");
    waitUntil([&] {
        return runtime.renderReadinessDiagnostics().cacheStorageStatus ==
            CurrentSongCacheStorageStatus::LimitExceeded;
    }, "cache budget overflow was not reported");
    const auto refused = runtime.renderReadinessDiagnostics();
    require(!refused.currentSongCacheBoundToSelectedOwner &&
        refused.currentSongCacheCompleteLaneMask == 0U && !refused.currentSongCacheCanGrow,
        "refused whole-song cache published readiness or retained partial storage");
    const std::size_t originalIndex = waitForRecord(output, 0U,
        [owner](const FakeOutputRecord &record) {
            return record.token == owner.selectionToken && record.generation == owner.generation &&
                record.sourceMode == NativeAudioSourceMode::Original && record.sourceSettled &&
                record.sourceEnd > record.sourceStart && record.peak > 0.005F;
        }, "cache refusal silenced ordinary Original PCM");
    const double originalEnd = output.record(originalIndex).sourceEnd;
    const std::size_t partsBegin = output.recordCount();
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First,
        owner, banks, stemPreviewControls(2U))) == NativePlaybackPublishStatus::Accepted,
        "cache refusal rejected the ordinary-parts source change");
    static_cast<void>(waitForRecord(output, partsBegin,
        [owner, originalEnd](const FakeOutputRecord &record) {
            return record.token == owner.selectionToken && record.generation == owner.generation &&
                record.controlRevision == 2U && record.sourceMode == NativeAudioSourceMode::Stems &&
                record.sourceSettled && record.stemBusReadyMask == allPartsMask &&
                record.sourceEnd > originalEnd && record.peak > 0.005F;
        }, "optional cache refusal blocked or silenced ready ordinary streaming parts"));
    const auto afterParts = runtime.renderReadinessDiagnostics();
    require(afterParts.cacheStorageStatus == CurrentSongCacheStorageStatus::LimitExceeded &&
        !afterParts.currentSongCacheBoundToSelectedOwner &&
        afterParts.currentSongCacheCompleteLaneMask == 0U,
        "ordinary streaming parts fabricated a successful whole-song cache");
    stopAndJoin(runtime);
}

void workerRejectedIngressPublishesTheExactFailedGeneration() {
    SongLaneIngressBank first(4'096U, 480U);
    SongLaneIngressBank second(4'096U, 480U);
    constexpr SongRenderOwnership owner{8'881U, 42U};
    configureLanes(first, owner.generation, 1U);
    // A legal decoder ingress starts later than the assigned native target. The actual worker,
    // rather than a diagnostic stub, must reject its discontinuous first append.
    offerLane(first, 0U, owner.generation, 480U, 480U);
    FakeOutput output;
    NativePlaybackRuntime runtime(runtimeConfiguration(), first, second, output.port());
    auto bank = assignedBank(1U, owner, 0U, 0U);
    bank.stemPackageHash = 0U;
    const std::array<NativePlaybackBankIntent, 2U> banks{bank, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First,
        owner, banks, controls(1U))) == NativePlaybackPublishStatus::Accepted,
        "ingress-failure owner accepted");
    require(runtime.start(), "ingress-failure worker starts");
    waitUntil([&] {
        const auto readiness = runtime.renderReadinessDiagnostics();
        return readiness.ownership.selectionToken == owner.selectionToken &&
            readiness.ownership.generation == owner.generation &&
            readiness.lanes[0].ingressFailureGeneration == owner.generation;
    }, "worker rejection must publish exact owner generation");
    const auto progress = runtime.controlProgress();
    require(progress.banks[0].assigned && progress.banks[0].appliedIntentRevision == 1U &&
        progress.readiness.ownership.selectionToken == owner.selectionToken &&
        progress.readiness.ownership.generation == owner.generation &&
        progress.readiness.failureLane == 0 && progress.readiness.failureGeneration == owner.generation,
        "compact progress retains the actual exact-owned worker failure and bank acknowledgement");
    require(runtime.diagnostics().banks[0].lanes[0].discontinuityRejects > 0U,
        "actual native worker rejected the gap");
    require(output.recordCount() == 0U,
        "a rejected first append must never fabricate audible PCM");
    stopAndJoin(runtime);
}

void unsealedBoundedCacheSeekUsesOrdinaryAllLanePlayback() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{19'701U, 2U}, sought{19'701U, 3U};
    constexpr std::uint32_t parts = 9U;
    SongLaneIngressBank first(131'072U, 480U), second(131'072U, 480U);
    configureLanes(first, owner.generation, parts + 1U);
    for (std::size_t lane = 0U; lane <= parts; ++lane) offerLane(first, lane, owner.generation, 0U, 81'920U, false);
    FakeOutput output;
    output.setAutoAcknowledgePausedCommit(true);
    auto configuration = runtimeConfiguration();
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept { stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, 0U, parts); bank.cacheFrameUpperBound = 81'920U;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        {bank, NativePlaybackBankIntent{}}, controls(1U), false)) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "unsealed bounded seek starts through normal paused owner construction");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner && output.commitCount() == 1U; },
        "unsealed cache binds and pauses its real owner");
    require(runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask != CurrentSongPcmCache::kAllLaneMask,
        "unsealed seek fixture unexpectedly reached whole-source EOS");
    constexpr std::uint64_t seekFrame = 48'000U;
    for (std::size_t lane = 0U; lane <= parts; ++lane) offerLane(first, lane, sought.generation, seekFrame, 8'192U, true);
    bank.revision = 2U; bank.ownership = sought; bank.firstTimelineFrame = seekFrame;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, sought,
        {bank, NativePlaybackBankIntent{}}, controls(2U, 1.0F, 0.0F, MixerSourceMode::Stems))) == NativePlaybackPublishStatus::Accepted,
        "ordinary nonzero seek receives the exact new bank owner");
    runtime.notifyIngressAvailable();
    waitUntil([&] { output.consumeAll(); runtime.notifyIngressAvailable(); return output.commitCount() == 2U; },
        "unsealed bounded cache cannot strand the ordinary seek generation");
    require(output.presentFirstCurrentRecord(), "ordinary seek callback acknowledges its exact owner");
    waitUntil([&] {
        for (std::size_t index = 0U; index < output.recordCount(); ++index) {
            const auto record = output.record(index);
            if (record.generation == sought.generation && record.token == sought.selectionToken &&
                record.sourceStart >= static_cast<double>(seekFrame) && record.sourceMode == NativeAudioSourceMode::Stems &&
                record.sourceSettled && record.peak > 0.001F) return true;
        }
        output.consumeAll(); runtime.notifyIngressAvailable(); return false;
    }, "ordinary unsealed seek preserves actual all-lane Stems playback");
}

void canonicalReadsUpdateDiagnosticsWithoutControlNotifications() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    std::atomic<unsigned> wakes{};
    NativePlaybackResources resources(runtimeConfiguration().pipeline, {},
        [](void *context) noexcept { static_cast<std::atomic<unsigned> *>(context)->fetch_add(1U); }, &wakes);
    [[maybe_unused]] const auto cleanup = onScopeExit([] { fault.store(0); });
    const CurrentSongCacheKey key{26'031U, 2U, 3U, 4U, 0U};
    bool created{};
    CurrentSongPcmCache *cache{};
    waitUntil([&] { cache = resources.createCache(key, 1U, 16384U, created); return created && cache; },
        "read notification fixture creates actual canonical store");
    const auto input = tone(16384U, 0U, .025F, 220.0);
    for (std::size_t frame = 0U; frame < 16384U; frame += 1024U)
        waitUntil([&] { return resources.offer(key, 0U, frame, input.data() + frame * 2U, 1024U).status ==
            CurrentSongCacheOfferStatus::Accepted; }, "read notification fixture publishes real PCM");
    waitUntil([&] { return resources.seal(key, 0U, 16384U) == CurrentSongCacheSealStatus::Accepted; },
        "read notification fixture seals Original");
    const auto publication = resources.waitPcmCacheProgress(0U);
    const auto reads = resources.diagnostics().pcmIo.readOperations;
    std::array<float, 128U> pcm{};
    require(resources.readPcmCacheForAudit(key, 0U, 0U, pcm.data(), 64U), "observational read actually completes");
    require(resources.diagnostics().pcmIo.readOperations > reads, "read counters lost their actual I/O");
    require(resources.waitPcmCacheProgress(0U) == publication, "read counters notified a control-state change");
    const auto wakeBefore = wakes.load();
    waitUntil([&] { return cache->copyLane(0U, 0U, pcm.data(), 64U, 64U); }, "cold required read publishes a render window");
    waitUntil([&] { return wakes.load() > wakeBefore; }, "async read-window readiness must still wake render");
    require(std::equal(pcm.begin(), pcm.end(), input.begin()), "async read changed actual PCM");
    require(resources.waitPcmCacheProgress(0U) == publication, "read-window metrics scheduled full control polling");
    fault.store(5); observed.store(false);
    waitUntil([&] { static_cast<void>(cache->copyLane(0U, 12288U, pcm.data(), 64U, 64U));
        return resources.snapshot().readFailureLaneMask == 1U; }, "new read failure publishes control-relevant state");
    require(observed.load() && resources.waitPcmCacheProgress(0U) > publication,
        "read failure failed to notify control observers");
    auto retirement = NativePlaybackCacheRetirementStatus::Pending;
    waitUntil([&] { retirement = resources.retireStems(key, key);
        return retirement != NativePlaybackCacheRetirementStatus::Pending; },
        "unusable Original returns a terminal retirement receipt");
    require(retirement == NativePlaybackCacheRetirementStatus::Rejected,
        "failed Original was incorrectly approved for package retirement");
}

void canonicalProgressWaitsForPublicationAndStopsWithoutEcho() {
    using namespace stemwise::audio::engine;
    NativePlaybackResources resources(runtimeConfiguration().pipeline, {}, [](void *) noexcept {}, nullptr);
    const CurrentSongCacheKey key{20'901U, 2U, 3U, 4U, 0U};
    bool created{};
    waitUntil([&] { return resources.createCache(key, 1U, 4096U, created) && created; },
        "progress fixture creates canonical store");
    const auto initial = resources.waitPcmCacheProgress(0U);
    std::atomic<std::uint64_t> observed{};
    std::atomic<bool> returned{};
    std::thread waiter([&] { observed.store(resources.waitPcmCacheProgress(initial)); returned.store(true); });
    const auto cleanup = onScopeExit([&]() noexcept {
        resources.stopPcmCacheProgressWaiters();
        if (waiter.joinable()) waiter.join();
    });
    const auto samples = tone(2048U, 0U, .025F, 220.0);
    waitUntil([&] { return resources.offer(key, 0U, 0U, samples.data(), 2048U).status ==
        CurrentSongCacheOfferStatus::Accepted; }, "actual append receives its acknowledgement");
    waitUntil([&] { return returned.load(); }, "actual canonical publication wakes paused observer");
    waiter.join();
    require(observed.load() > initial, "observer woke without a newer publication");
    const auto published = observed.load();
    returned.store(false);
    waiter = std::thread([&] { observed.store(resources.waitPcmCacheProgress(published)); returned.store(true); });
    auto stale = key; ++stale.generation;
    waitUntil([&] { return resources.seal(stale, 0U, 2048U) == CurrentSongCacheSealStatus::StaleKey; },
        "unchanged stale command still receives its acknowledgement");
    // This is a bounded negative wake observation, independent of command acknowledgement.
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(100);
    while (!returned.load() && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
    require(!returned.load(), "unchanged command acknowledgement echoed as cache progress");
    resources.stopPcmCacheProgressWaiters();
    waitUntil([&] { return returned.load(); }, "observer stop releases blocked wait");
    waiter.join();
    require(observed.load() == 0U, "stopped observer claimed a cache publication");
}

void commandAcknowledgementsCannotStarveCanonicalAsyncReads() {
    using namespace stemwise::audio::engine;
    struct PreemptingCaller final {
        NativePlaybackResources *resources{};
        CurrentSongPcmCache *cache{};
        CurrentSongCacheKey key{20'101U, 2U, 3U, 4U, 0U};
        std::array<float, CurrentSongPcmCache::kChunkFrames * 2U> pending{};
        std::array<float, 64U * 2U> readback{};
        std::uint64_t nextFrame{16'384U};
        std::atomic<bool> armed{}, done{};
        bool readSucceeded{}, invalidReceipt{};
        unsigned acknowledgements{};
        static void wake(void *context) noexcept {
            auto &self = *static_cast<PreemptingCaller *>(context);
            if (!self.armed.load(std::memory_order_acquire) || self.done.load(std::memory_order_acquire)) return;
            if (self.cache->copyLane(0U, 0U, self.readback.data(), 64U, 64U)) {
                self.readSucceeded = true; self.done.store(true, std::memory_order_release); return;
            }
            if (++self.acknowledgements == 64U) { self.done.store(true, std::memory_order_release); return; }
            const auto receipt = self.resources->offer(self.key, 0U, self.nextFrame,
                self.pending.data(), CurrentSongPcmCache::kChunkFrames);
            if (receipt.status != CurrentSongCacheOfferStatus::Accepted ||
                    receipt.inputFramesConsumed != CurrentSongPcmCache::kChunkFrames) {
                self.invalidReceipt = true; self.done.store(true, std::memory_order_release); return;
            }
            self.nextFrame += CurrentSongPcmCache::kChunkFrames;
            const auto next = self.resources->offer(self.key, 0U, self.nextFrame,
                self.pending.data(), CurrentSongPcmCache::kChunkFrames);
            if (next.status != CurrentSongCacheOfferStatus::Pending || next.inputFramesConsumed != 0U) {
                self.invalidReceipt = true; self.done.store(true, std::memory_order_release);
            }
        }
    } caller;
    NativePlaybackResources resources(runtimeConfiguration().pipeline, {}, &PreemptingCaller::wake, &caller);
    caller.resources = &resources;
    const auto disarm = onScopeExit([&]() noexcept { caller.armed.store(false, std::memory_order_release); });
    bool created{};
    waitUntil([&] { caller.cache = resources.createCache(caller.key, 1U, 262'144U, created);
        return created && caller.cache; }, "fairness fixture creates its canonical I/O cache");
    for (std::size_t first = 0U; first < 16'384U; first += CurrentSongPcmCache::kChunkFrames) {
        const auto samples = tone(CurrentSongPcmCache::kChunkFrames, first, .025F, 220.0);
        waitUntil([&] { return resources.offer(caller.key, 0U, first, samples.data(),
            CurrentSongPcmCache::kChunkFrames).status == CurrentSongCacheOfferStatus::Accepted; },
            "fairness fixture commits actual canonical PCM");
    }
    std::array<float, 64U * 2U> barrier{};
    require(resources.readPcmCacheForAudit(caller.key, 0U, 0U, barrier.data(), 64U),
        "serialized I/O barrier retires setup acknowledgements without warming an async window");
    caller.armed.store(true, std::memory_order_release);
    require(resources.offer(caller.key, 0U, caller.nextFrame, caller.pending.data(),
        CurrentSongPcmCache::kChunkFrames).status == CurrentSongCacheOfferStatus::Pending,
        "actual command train begins through the I/O owner");
    waitUntil([&] { return caller.done.load(std::memory_order_acquire); },
        "bounded priority/preemption train must terminate");
    require(!caller.invalidReceipt, "command train retains its exact acknowledgement");
    require(caller.readSucceeded, "command acknowledgements cannot starve an actual canonical PCM read");
    const auto expected = tone(64U, 0U, .025F, 220.0);
    require(std::memcmp(caller.readback.data(), expected.data(), caller.readback.size() * sizeof(float)) == 0,
        "fair read service returns actual exact PCM under command pressure");
}

void canonical44100RandomReadsMatchCanonicalIngress() {
    using namespace stemwise::audio::engine;
    constexpr std::uint32_t rate = 44'100U;
    constexpr std::size_t sourceFrames = 60'000U;
    const auto source = tone(sourceFrames, 0U, 0.025F, 331.0);
    LaneIngress canonical(rate, 131'072U, 4'096U, 1U);
    for (std::size_t first = 0U; first < sourceFrames;) {
        const auto count = std::min<std::size_t>(4'096U, sourceFrames - first);
        const auto offered = canonical.offer(LaneInputBlock{.data = source.data() + first * 2U, .frameCount = count,
            .format = {rate, 2U, LanePcmEncoding::Float32}, .generation = 1U,
            .presentationTimeUs = first * 1'000'000U / rate});
        require(offered.inputFramesAccepted == count, "canonical44100 producer accepts actual source PCM");
        first += count;
    }
    require(canonical.markEndOfStream(1U, sourceFrames).status == LaneEndOfStreamStatus::Accepted, "canonical44100 actual EOS");
    std::vector<float> expected;
    std::array<float, 8'192U> output{};
    for (std::size_t iteration = 0U; iteration < 1'000U; ++iteration) {
        const auto result = canonical.pull48k(1U, output.data(), 4'096U);
        expected.insert(expected.end(), output.begin(), output.begin() + result.outputFramesProduced * 2U);
        if (result.status == LanePullStatus::EndOfStream) break;
        require(iteration != 999U, "canonical44100 EOS must terminate");
    }
    std::atomic<std::uint64_t> resourceWakes{};
    NativePlaybackResources resources(runtimeConfiguration().pipeline, {}, [](void *context) noexcept {
        static_cast<std::atomic<std::uint64_t> *>(context)->fetch_add(1U, std::memory_order_release);
    }, &resourceWakes);
    const CurrentSongCacheKey key{19'891U, 3U, 5U, 7U, 0U};
    CurrentSongPcmCache *cache{};
    bool finished{};
    waitUntil([&] { cache = resources.createCache(key, 1U, expected.size() / 2U, finished); return cache && finished; },
        "44100 real resource worker creates bounded cache");
    for (std::size_t first = 0U; first < expected.size() / 2U;) {
        const auto count = std::min<std::size_t>(2'048U, expected.size() / 2U - first);
        if (first == 2'048U) {
            using namespace stemwise::audio::engine::cache_test;
            const auto unblock = onScopeExit([&]() noexcept { releaseBlocked.store(true); fault.store(0); });
            blocked.store(false); releaseBlocked.store(false); fault.store(7);
            require(resources.offer(key, 0U, first, expected.data() + first * 2U, count).status ==
                CurrentSongCacheOfferStatus::Pending, "metadata proof test submits an actual blocked disk write");
            waitUntil([&] { return blocked.load(); }, "actual resource I/O owner waits inside disk write");
            const auto started = std::chrono::steady_clock::now();
            require(resources.pcmCacheRangeReady(key, 1U, 0U, 512U),
                "metadata-only current range proof became unavailable behind unrelated disk I/O");
            require(std::chrono::steady_clock::now() - started < std::chrono::milliseconds(100),
                "control metadata proof waited for blocked disk I/O");
            releaseBlocked.store(true); fault.store(0);
        }
        waitUntil([&] { return resources.offer(key, 0U, first, expected.data() + first * 2U, count).status ==
            CurrentSongCacheOfferStatus::Accepted; }, "44100 canonical producer PCM committed in bounded cache");
        if (count == CurrentSongPcmCache::kChunkFrames)
            require(resources.pcmCacheRangeReady(key, 1U, first, first + count),
                "moving published cursor cannot obtain a current resident range proof");
        first += count;
    }
    waitUntil([&] { return resources.seal(key, 0U, expected.size() / 2U) == CurrentSongCacheSealStatus::Accepted; },
        "44100 canonical cache EOS");
    const auto before = resources.diagnostics().pcmIo;
    for (const auto first : {0U, 4'096U, static_cast<unsigned>(expected.size() / 2U - 512U)}) {
        const auto prior = resources.diagnostics().pcmIo;
        require(resources.readPcmCacheForAudit(key, 0U, first, output.data(), 512U) &&
            std::memcmp(output.data(), expected.data() + first * 2U, 512U * 2U * sizeof(float)) == 0,
            "44100 random canonical read differs from independent full SRC decode");
        const auto io = resources.diagnostics().pcmIo;
        require(io.readOperations == prior.readOperations + 1U && io.readBytes == prior.readBytes + 512U * 8U &&
            io.lastReadByteOffset == first * 8U && io.lastReadBytes == 512U * 8U,
            "canonical random access performs only the requested bounded pread");
    }
    auto stale = key; ++stale.generation;
    require(!resources.readPcmCacheForAudit(stale, 0U, 0U, output.data(), 512U) &&
        !resources.pcmCacheRangeReady(stale, 1U, 0U, 512U),
        "stale owner cannot read or inherit committed range authority");
    require(resources.diagnostics().pcmIo.fileBytes == before.fileBytes &&
        before.fileBytes == expected.size() * sizeof(float),
        "random reads neither reopen a source nor rewrite canonical PCM");
}

void canonicalRandomAccessWorkIsIndependentOfPosition(std::uint32_t rate, std::uint32_t parts) {
    using namespace stemwise::audio::engine;
    constexpr std::uint64_t seconds = 305U, totalFrames = seconds * 48'000U;
    constexpr std::size_t readFrames = 512U;
    const std::array<std::uint64_t, 3U> starts{10U * 48'000U, 300U * 48'000U, totalFrames - readFrames};
    const auto began = std::chrono::steady_clock::now();
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey key{21'000U + rate + parts, 1U, 3U, 5U, parts == 0U ? 0U : 7U};
    require(cache.admitStorage(parts + 1U, totalFrames) == CurrentSongCacheStorageStatus::Admitted &&
        cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "long canonical matrix admits its exact actual lanes");
    using Expected = std::array<std::array<float, readFrames * 2U>, 3U>;
    std::array<Expected, kCurrentSongCacheLaneCount> expected{};
    for (std::size_t lane = 0U; lane <= parts; ++lane) {
        LaneIngress sequential(rate, 16'384U, 4'096U, 1U);
        std::array<float, 4'096U * 2U> block{};
        std::array<std::size_t, 3U> captured{};
        std::uint64_t published{};
        const auto publish = [&](const LanePullResult &pull) {
            require(pull.firstTimelineFrame48k == published, "sequential SRC output must remain exactly contiguous");
            const auto end = published + pull.outputFramesProduced;
            for (std::size_t range = 0U; range < starts.size(); ++range) {
                const auto first = std::max(published, starts[range]);
                const auto last = std::min(end, starts[range] + readFrames);
                if (last <= first) continue;
                std::copy_n(block.data() + (first - published) * 2U, (last - first) * 2U,
                    expected[lane][range].data() + (first - starts[range]) * 2U);
                captured[range] += static_cast<std::size_t>(last - first);
            }
            require(cache.offer(key, lane, published, block.data(), pull.outputFramesProduced).inputFramesConsumed ==
                pull.outputFramesProduced, "ordinary sequential decoder PCM commits without prefix skipping");
            published = end;
        };
        // The fixture is periodic; synthesize one bounded second once, not hundreds of millions
        // of repeated trigonometric samples. A timeline bias distinguishes distant equal phases;
        // every frame still passes through the real SRC.
        const auto period = tone(kSampleRate, 0U, .025F + lane * .001F, 220.0 + lane * 17.0);
        std::array<float, 4'096U * 2U> source{};
        for (std::uint64_t first = 0U; first < seconds * rate;) {
            const auto count = static_cast<std::size_t>(std::min<std::uint64_t>(4'096U, seconds * rate - first));
            for (std::size_t frame = 0U; frame < count; ++frame) {
                const auto index = static_cast<std::size_t>((first + frame) % kSampleRate) * 2U;
                const float position = .00001F * static_cast<float>((first + frame) / kSampleRate);
                source[frame * 2U] = period[index] + position;
                source[frame * 2U + 1U] = period[index + 1U] - position;
            }
            const auto offered = sequential.offer(LaneInputBlock{.data=source.data(), .frameCount=count,
                .format={rate, 2U, LanePcmEncoding::Float32}, .generation=1U,
                .presentationTimeUs=first * 1'000'000U / rate});
            require(offered.inputFramesAccepted == count, "long matrix actual decoder block must be accepted");
            first += count;
            for (;;) {
                const auto pull = sequential.pull48k(1U, block.data(), 4'096U);
                if (pull.outputFramesProduced != 0U) publish(pull);
                if (pull.outputFramesProduced == 0U) break;
            }
        }
        require(sequential.markEndOfStream(1U, seconds * rate).status == LaneEndOfStreamStatus::Accepted,
            "long matrix records actual source EOS");
        bool ended = false;
        for (unsigned flush = 0U; flush < 64U && !ended; ++flush) {
            const auto pull = sequential.pull48k(1U, block.data(), 4'096U);
            if (pull.outputFramesProduced != 0U) publish(pull);
            ended = pull.status == LanePullStatus::EndOfStream;
        }
        require(ended && published == totalFrames && std::all_of(captured.begin(), captured.end(),
            [](std::size_t frames) { return frames == readFrames; }) &&
            cache.sealLane(key, lane, published) == CurrentSongCacheSealStatus::Accepted,
            "long matrix preserves the independently captured sequential PCM through exact EOS");
    }
    cache.enableAsyncReads([](void *) noexcept {}, nullptr);
    std::array<float, readFrames * 2U> actual{};
    for (std::size_t lane = 0U; lane <= parts; ++lane) for (std::size_t range = 0U; range < starts.size(); ++range) {
        const auto first = starts[range];
        require(!cache.copyLane(lane, first, actual.data(), readFrames, readFrames),
            "long matrix random target begins as an actual cold RAM-window miss");
        const auto before = cache.snapshot().io;
        require(cache.serviceAsyncReads(), "long matrix direct I/O owner publishes its requested canonical window");
        const auto io = cache.snapshot().io;
        require(io.readOperations == before.readOperations + 1U &&
            io.lastReadByteOffset == first / 4'096U * 4'096U * 8U &&
            io.lastReadBytes <= 4'096U * 8U && io.readBytes - before.readBytes == io.lastReadBytes,
            "10s/5min/near-EOS reads must use one bounded offset pread, never discarded-prefix work");
        require(cache.copyLane(lane, first, actual.data(), readFrames, readFrames) &&
            std::memcmp(actual.data(), expected[lane][range].data(), sizeof(actual)) == 0,
            "random canonical PCM differs from the actual complete sequential decoder/SRC output");
        static_cast<void>(cache.serviceAsyncReads()); // Retire the next-window hint before the next measurement.
    }
    require(cache.snapshot().io.fileBytes == totalFrames * (parts + 1U) * 8U &&
        cache.snapshot().io.reservedBytes == cache.snapshot().io.fileBytes,
        "long matrix storage must equal actual admitted canonical PCM, with no duplicate decoded store");
    std::cout << "canonical rate=" << rate << " parts=" << parts << " targets=10s/300s/EOS"
        << " peak-file-bytes=" << cache.snapshot().io.peakFileBytes << " elapsed-ms="
        << std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - began).count() << std::endl;
}

void allLanePlaybackAndExactCanonicalRewind() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{19'801U, 2U};
    constexpr std::size_t frames = 81'920U;
    constexpr std::uint32_t parts = 9U;
    SongLaneIngressBank first(131'072U, 480U), second(131'072U, 480U);
    configureLanes(first, owner.generation, parts + 1U);
    for (std::size_t lane = 0U; lane <= parts; ++lane) offerLane(first, lane, owner.generation, 0U, frames, true);
    FakeOutput output;
    auto configuration = runtimeConfiguration();
    configuration.ingressPullFrames = 1'024U; // Match the production producer quantum.

    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept { stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, 0U, parts);
    bank.cacheFrameUpperBound = frames;
    const std::array banks{bank, NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner, banks,
        controls(1U, 1.0F, 0.0F, MixerSourceMode::Stems))) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "bounded primary playback starts normally");
    waitUntil([&] { return output.commitCount() == 1U; }, "bounded primary owner commits");
    require(output.presentFirstCurrentRecord(), "bounded primary callback acknowledges its owner");
    const CurrentSongCacheKey initialKey{owner.selectionToken, owner.generation, bank.songIdentityHash,
        bank.originalContentHash, bank.stemPackageHash};
    // Hold the fake callback until all lanes prime; its unpaced drain can otherwise finish on valid startup Original fallback.
    waitUntil([&] {
        const auto ready = runtime.renderReadinessDiagnostics();
        return sameOwnership(ready.ownership, owner) && ready.currentSongCacheBoundToSelectedOwner &&
            runtime.pcmCacheRangeReady(initialKey, CurrentSongPcmCache::kAllLaneMask,
                ready.renderStartTimelineFrame, ready.renderStartTimelineFrame + 1'024U);
    }, "all ten exact-owner lanes prime the next actual render range before callback drain");
    static_cast<void>(waitForRecord(output, 0U, [&](const FakeOutputRecord &record) {
        return record.generation == owner.generation && record.token == owner.selectionToken &&
            record.sourceMode == NativeAudioSourceMode::Stems && record.sourceSettled &&
            record.stemBusReadyMask == (1U << parts) - 1U;
    }, "primed lanes produce an actual settled Stems record before the unpaced forward run"));
    std::size_t callbackRecordCount = 1U; // The first record was acknowledged above.
    waitUntil([&] {
        const auto count = output.recordCount();
        if (count > callbackRecordCount) {
            const auto record = output.record(count - 1U);
            require(record.generation == owner.generation && record.token == owner.selectionToken &&
                record.frames != 0U && record.fingerprint != 0U && output.presentRecord(count - 1U),
                "actual drained PCM acknowledges the exact owner's presentation boundary");
            callbackRecordCount = count;
        }
        static_cast<void>(output.consumeFrames(output.queued()));
        runtime.notifyIngressAvailable();
        return count != 0U && output.record(count - 1U).sourceEnd >= static_cast<double>(frames);
    }, "primary decoders and all playable lanes progress past five cache capacities", 12'000U);
    bool stemsObserved{};
    for (std::size_t index = 0U; index < output.recordCount(); ++index)
        stemsObserved = stemsObserved || (output.record(index).sourceMode == NativeAudioSourceMode::Stems && output.record(index).sourceSettled);
    require(stemsObserved, "bounded primary did not render actual ready Stems");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask ==
        CurrentSongPcmCache::kAllLaneMask; }, "bounded primary finishes actual all-lane EOS");
    const SongRenderOwnership sought{owner.selectionToken, owner.generation + 1U};
    auto soughtBank = bank; soughtBank.revision = 2U; soughtBank.ownership = sought;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, sought,
        {soughtBank, NativePlaybackBankIntent{}}, controls(2U), false)) ==
        NativePlaybackPublishStatus::Accepted, "sealed cache receives actual paused cross-generation rewind");
    const CurrentSongCacheKey key{sought.selectionToken, sought.generation, bank.songIdentityHash,
        bank.originalContentHash, bank.stemPackageHash};
    std::array<float, 128U> actual{};
    waitUntil([&] { return runtime.pcmCacheRangeReady(key, 1U, 0U, 64U); },
        "sealed canonical cache rebinds rewind authority to the exact new owner", 8'000U);
    stopAndJoin(runtime);
    const auto before = runtime.diagnostics().canonicalPcmIo;
    require(runtime.readPcmCacheForAudit(key, 0U, 0U, actual.data(), 64U),
        "stopped audit read observes committed canonical rewind");
    const auto expected = tone(64U, 0U, 0.025F, 220.0);
    require(std::memcmp(actual.data(), expected.data(), actual.size() * sizeof(float)) == 0,
        "runtime canonical rewind changed actual source PCM bits");
    require(!runtime.readPcmCacheForAudit(initialKey, 0U, 0U, actual.data(), 64U),
        "stale pre-rewind generation cannot access current canonical store");
    const auto after = runtime.diagnostics().canonicalPcmIo;
    require(after.fileBytes == before.fileBytes && after.fileBytes == frames * (parts + 1U) * 8U,
        "all-lane rewind retains exactly its admitted canonical bytes");
}

void coldPipelinesRequireExactResourceAdmission() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    std::atomic<unsigned> completions{0U};
    auto configured = runtimeConfiguration().pipeline;
    NativePlaybackResources resource(configured, {}, [](void *context) noexcept {
        static_cast<std::atomic<unsigned> *>(context)->fetch_add(1U);
    }, &completions);
    std::unique_ptr<SongRenderPipeline> pipeline, unrelated;
    fault.store(9);
    blocked.store(false);
    blockTimedOut.store(false);
    releaseBlocked.store(false);
    const auto cleanup = onScopeExit([&]() noexcept { releaseBlocked.store(true); fault.store(0); });
    require(!resource.preparePipeline(0U, 1U, 0U, pipeline) && !pipeline, "cold pipeline queues instead of allocating on caller");
    waitUntil([&] { return blocked.load(); }, "cold pipeline creation reaches the actual I/O worker");
    resource.discardSupersededPreparation(0U, 2U);
    require(!resource.preparePipeline(0U, 2U, 2U, pipeline) && !pipeline &&
        !resource.preparePipeline(1U, 1U, 0U, unrelated) && !unrelated,
        "neither newer nor unrelated owner can adopt blocked old creation");
    releaseBlocked.store(true);
    fault.store(0);
    waitUntil([&] { return resource.preparePipeline(0U, 2U, 2U, pipeline); }, "latest exact cold creation becomes ready");
    require(pipeline && pipeline->stemLaneCount() == 2U && !unrelated && !blockTimedOut.load(),
        "superseded result retires without substituting its capacity");
    constexpr SongRenderOwnership owner{901U, 902U};
    require(pipeline->beginSong(owner, owner.generation, 0U, 0U) == SongRenderResetStatus::Ok, "cold admitted owner begins");
    const auto pcm = tone(8'192U, 0U, .1F, 220.0);
    require(pipeline->appendLanePcm(SongRenderLane::Original, owner.generation, 0U, pcm.data(), 8'192U).status ==
        TimelineAppendStatus::Accepted, "cold admitted owner has real Original PCM");
    std::array<float, 64U * kStemwiseMultibusStride> payload{};
    allocation_probe::count.store(0U);
    allocation_probe::enabled.store(true);
    const auto rendered = pipeline->renderMultibus(owner, payload.data(), 64U, 64U);
    allocation_probe::enabled.store(false);
    require(rendered.framesProduced == 64U && rendered.ownership.generation == owner.generation &&
        allocation_probe::count.load() == 0U && payload[2U * kStemwiseMultibusStride] != 0.0F,
        "cold adopted pipeline renders owned PCM without allocation");
    pipeline.reset();
    waitUntil([&] { return resource.diagnostics().operationsCompleted >= 2U; }, "prior creation acknowledgement settles");
    fault.store(10);
    bool failed = false;
    waitUntil([&] { const bool ready = resource.preparePipeline(0U, 3U, 0U, pipeline, &failed); return !ready && failed; }, "cold allocation failure is explicit");
    const auto operations = resource.diagnostics().operationsCompleted;
    for (unsigned retry = 0U; retry < 64U; ++retry)
        require(!resource.preparePipeline(0U, 3U, 0U, pipeline, &failed) && failed && !pipeline,
            "same failed cold request cannot spin/reconstruct");
    require(resource.diagnostics().operationsCompleted == operations, "cold failure remains one terminal resource operation");
    fault.store(0);
    waitUntil([&] { return resource.preparePipeline(0U, 4U, 0U, pipeline); }, "a newer exact cold request recovers");
}

void frameZeroSeekReusesOnlyTheSameRecordingBoundedHead(bool wrongContent = false, bool wrongToken = false,
    bool wrongSong = false, bool wrongPackage = false, bool replaceHeadBeforeClear = false, bool lateHead = false,
    bool originalOnlyHead = false, bool upgradeHead = false, bool thirdHeadAfterClear = false) {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership initial{28'016U, 2U};
    const SongRenderOwnership selected{initial.selectionToken + (wrongToken ? 1U : 0U), 3U};
    constexpr std::size_t frames = 16'384U, parts = 6U;
    constexpr std::uint64_t b = 4'800U;
    std::array<std::vector<float>, parts + 1U> pcm;
    for (std::size_t lane = 0U; lane < pcm.size(); ++lane) {
        pcm[lane].resize(frames * 2U);
        for (std::size_t frame = 0U; frame < frames; ++frame) {
            pcm[lane][frame * 2U] = .015625F + static_cast<float>(frame) / 1'048'576.F + static_cast<float>(lane) / 512.F;
            pcm[lane][frame * 2U + 1U] = -.03125F + static_cast<float>(frame) / 2'097'152.F;
        }
    }
    SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
    configureLanes(first, initial.generation, pcm.size());
    const auto offerSource = [&](SongRenderOwnership owner, std::size_t firstFrame) {
        for (std::size_t lane = 0U; lane < pcm.size(); ++lane) {
            for (std::size_t offset = upgradeHead && owner.generation == selected.generation && lane != 0U
                    ? 8'192U : firstFrame; offset < frames;) {
                const auto count = std::min<std::size_t>(480U, frames - offset);
                const auto offered = first.lane(lane)->offer({pcm[lane].data() + offset * 2U, count,
                    {kSampleRate, 2U, LanePcmEncoding::Float32}, owner.generation,
                    offset * 1'000'000U / kSampleRate});
                require(offered.status == LaneOfferStatus::Accepted && offered.inputFramesAccepted == count,
                    "actual selected decoder supplies only its available suffix");
                offset += count;
            }
        }
    };
    offerSource(initial, 0U);
    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept { stopAndJoin(runtime); });
    auto bank = assignedBank(1U, initial, 0U, parts);
    bank.cacheFrameUpperBound = std::numeric_limits<std::uint64_t>::max();
    const auto headMask = originalOnlyHead ? 1U : 127U;
    auto head = managedLoopIntent(1U, initial, 0U, headMask);
    if (originalOnlyHead) head.cacheKey.stemPackageHash = 0U;
    head.boundaryMode = LoopHeadBoundaryMode::RetainedWindow;
    head.cacheKey.endTimelineFrameExclusive = thirdHeadAfterClear ? frames : 8'192U;
    const auto commitHead = [&](const LoopCycleIntent &prepared, SongRenderOwnership owner, bool awaitRetiredReaders = false) {
        const auto begin = [&] { return runtime.beginLoopHeadPreparation(prepared.cacheKey,
            prepared.requiredLaneMask, prepared.boundaryMode, owner); };
        if (awaitRetiredReaders) {
            waitUntil([&] { return begin() == LoopHeadPreparationBeginStatus::Accepted; },
                "third all-lane head must prepare after the paused Original source replaces retired head readers");
        } else {
            require(begin() == LoopHeadPreparationBeginStatus::Accepted,
                "real immutable frame-zero head begins for the initial owner");
        }
        for (std::size_t lane = 0U; lane < pcm.size(); ++lane) {
            if ((prepared.requiredLaneMask & (1U << lane)) == 0U) continue;
            const auto generation = 601U + prepared.cacheKey.loopRevision * 100U + lane;
            require(runtime.configureLoopHeadPreparationLane(prepared.cacheKey.loopRevision, lane, kSampleRate, generation) ==
                LoopHeadPreparationConfigureStatus::Accepted, "each bounded-head lane owns its decoder epoch");
            bool complete = false;
            for (std::size_t offset = 0U; offset < frames && !complete;) {
                const auto count = std::min<std::size_t>(480U, frames - offset);
                const auto offered = runtime.offerLoopHeadPreparationPcm(prepared.cacheKey.loopRevision, lane,
                    {pcm[lane].data() + offset * 2U, count, {kSampleRate, 2U, LanePcmEncoding::Float32},
                     generation, offset * 1'000'000U / kSampleRate});
                require(offered.inputFramesAccepted > 0U && offered.inputFramesAccepted <= count,
                    "immutable head consumes real nonperiodic PCM");
                offset += offered.inputFramesAccepted;
                complete = offered.status == LoopHeadPreparationOfferStatus::LaneCompleted ||
                    offered.status == LoopHeadPreparationOfferStatus::CacheCommitted;
            }
            require(complete, "all seven bounded-head lanes commit");
        }
    };
    if (!lateHead) commitHead(head, initial);
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, initial,
        {bank, {}}, controls(1U))) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "frame-zero recording starts with the real committed head");
    waitUntil([&] { return output.commitCount() == 1U; }, "initial frame-zero owner commits");
    require(output.presentFirstCurrentRecord(), "initial owner reaches the callback");
    if (!lateHead) {
        require(runtime.publishDirectLoopPolicy(1U, initial.selectionToken, initial.generation, true, 0U, b) ==
            NativePlaybackPublishStatus::Accepted, "initial zero-origin loop is admitted");
        waitUntil([&] { return output.loopCutoverCommitCount() == 1U; }, "initial loop stages actual A PCM");
        require(output.presentCommittedLoopCutover(), "callback installs the initial bounded head");
        waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 1U; }, "initial loop's producer becomes active");
    }
    offerSource(selected, 512U);
    bank.revision = 2U;
    bank.ownership = selected;
    if (wrongContent) ++bank.originalContentHash;
    if (wrongSong) ++bank.songIdentityHash;
    if (wrongPackage) ++bank.stemPackageHash;
    require(runtime.publishCommand(selectedCommand(2U, NativePlaybackBank::First, selected,
        {bank, {}}, controls(2U))) == NativePlaybackPublishStatus::Accepted,
        "an explicit frame-zero seek selects its new exact native owner");
    const CurrentSongCacheKey selectedKey{selected.selectionToken, selected.generation,
        bank.songIdentityHash, bank.originalContentHash, bank.stemPackageHash};
    waitUntil([&] { const auto diagnostic = runtime.diagnostics();
        return diagnostic.banks[0U].appliedIntentRevision == 2U && sameOwnership(diagnostic.requestedOwnership, selected); },
        "new owner's actual bank source identity applies");
    if (lateHead) {
        require(output.commitCount() == 1U, "missing frame-zero PCM cannot acknowledge the new owner prematurely");
        commitHead(head, selected);
    }
    if (wrongContent || wrongToken || wrongSong || wrongPackage) {
        require(!runtime.pcmCacheRangeReady(selectedKey, headMask, 0U, b),
            "a committed old head cannot authorize another recording or selection token");
        waitForRenderProgress(runtime, runtime.diagnostics().renderCycles,
            "incompatible source cannot manufacture a new callback owner", 4U);
        require(output.commitCount() == 1U, "incompatible frame-zero prefix stays uncommitted");
        return;
    }
    require(runtime.pcmCacheRangeReady(selectedKey, headMask, 0U, b),
        "explicit same-recording seek must retain the verified immutable frame-zero head without redecoding");
    auto unselected = selectedKey; ++unselected.generation;
    require(!runtime.pcmCacheRangeReady(unselected, headMask, 0U, b),
        "content equality cannot authorize a generation that has no applied selected bank");
    waitUntil([&] { return output.commitCount() == 2U; }, "retained frame-zero PCM primes the selected owner");
    const auto start = output.currentGenerationFirstRecord();
    const auto firstRecord = output.record(start);
    std::uint64_t expected = 14695981039346656037ULL;
    for (std::size_t frame = 0U; frame < firstRecord.frames; ++frame)
        for (std::size_t channel = 0U; channel < 2U; ++channel) {
            expected ^= std::bit_cast<std::uint32_t>(pcm[0U][frame * 2U + channel]);
            expected *= 1099511628211ULL;
        }
    require(firstRecord.generation == selected.generation && firstRecord.token == selected.selectionToken &&
        firstRecord.sourceStart == 0.0 && firstRecord.originalPcmHash == expected,
        "new owner starts at actual nonperiodic cached frame zero rather than its decoder's frame512");
    require(output.presentFirstCurrentRecord(), "callback acknowledges the new owner from actual retained PCM");
    if (originalOnlyHead) require(!runtime.pcmCacheRangeReady(selectedKey, 127U, 0U, b),
        "an Original-only head cannot authorize the bank's declared Stems package");
    const std::array banks{bank, NativePlaybackBankIntent{}};
    const auto nextSource = originalOnlyHead && !upgradeHead ? MixerSourceMode::Original : MixerSourceMode::Stems;
    require(runtime.publishCommand(selectedCommand(3U, NativePlaybackBank::First, selected, banks,
        controls(3U, 1.0F, 0.0F, nextSource), false)) == NativePlaybackPublishStatus::Accepted,
        "paused source change requests all actual retained Stems");
    waitUntil([&] { return !output.playing(); }, "source change reaches the paused adapter");
    if (upgradeHead) {
        auto upgraded = head;
        upgraded.requiredLaneMask = 127U;
        upgraded.cacheKey.loopRevision = 2U;
        upgraded.cacheKey.stemPackageHash = bank.stemPackageHash;
        commitHead(upgraded, selected);
        require(runtime.pcmCacheRangeReady(selectedKey, 127U, 0U, b),
            "the new all-lane head is independently committed for this exact selected source");
        const auto prior = output.recordCount(); output.consumeAll();
        const auto ready = waitForRecord(output, prior, [=](const FakeOutputRecord &record) {
            return record.generation == selected.generation && record.sourceMode == NativeAudioSourceMode::Stems &&
                record.sourceSettled && record.stemBusReadyMask == ((1U << parts) - 1U);
        }, "paused non-looping Original reader must acquire its compatible all-lane head upgrade", 4'000U, &runtime);
        require(!output.playing() && output.record(ready).sourceStart < 8'192.0,
            "Stems readiness uses the preserved head, without skipping to the decoder's later suffix");
    }
    const auto loopCommits = output.loopCutoverCommitCount();
    const auto releases = output.loopCutoverWriteReleaseCount();
    require(runtime.publishDirectLoopPolicy(2U, selected.selectionToken, selected.generation, true, 0U, b) ==
        NativePlaybackPublishStatus::Accepted, "new exact owner reuses the all-lane frame-zero range");
    require(runtime.publishCommand(selectedCommand(4U, NativePlaybackBank::First, selected, banks,
        controls(4U, 1.0F, 0.0F, nextSource), true)) == NativePlaybackPublishStatus::Accepted,
        "the paused Stems source resumes normally");
    waitUntil([&] { return output.loopCutoverCommitCount() > loopCommits; }, "new owner stages its actual retained loop PCM");
    require(output.presentCommittedLoopCutover(), "callback adopts the new owner's frame-zero Stems loop");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() > releases; }, "new owner owns the installed loop producer");
    const auto begin = output.recordCount(); output.consumeAll();
    const auto boundary = waitForRecord(output, begin, [=](const FakeOutputRecord &record) {
        return record.generation == selected.generation && record.loopEnabled && record.sourceEnd == static_cast<double>(b);
    }, "reused head reaches the exact short-loop B", 4'000U, &runtime);
    const auto wrap = waitForRecord(output, boundary + 1U, [=](const FakeOutputRecord &record) {
        return record.generation == selected.generation && record.loopEnabled && record.sourceStart == 0.0;
    }, "reused head wraps to real frame zero", 4'000U, &runtime);
    const auto wrapped = output.record(wrap);
    const auto expectedSource = originalOnlyHead && !upgradeHead ? NativeAudioSourceMode::Original : NativeAudioSourceMode::Stems;
    const auto expectedStemMask = originalOnlyHead && !upgradeHead ? 0U : ((1U << parts) - 1U);
    expected = 14695981039346656037ULL;
    for (std::size_t frame = 0U; frame < wrapped.frames; ++frame)
        for (std::size_t channel = 0U; channel < 2U; ++channel) {
            expected ^= std::bit_cast<std::uint32_t>(pcm[0U][frame * 2U + channel]);
            expected *= 1099511628211ULL;
        }
    if (wrapped.token != selected.selectionToken || wrapped.originalPcmHash != expected ||
        wrapped.sourceMode != expectedSource || !wrapped.sourceSettled || wrapped.stemBusReadyMask != expectedStemMask)
        std::cerr << "reused head wrap token=" << wrapped.token << " generation=" << wrapped.generation
            << " hash=" << wrapped.originalPcmHash << " expected=" << expected << " mode="
            << static_cast<unsigned>(wrapped.sourceMode) << " settled=" << wrapped.sourceSettled
            << " busMask=" << wrapped.stemBusReadyMask << '\n';
    require(wrapped.token == selected.selectionToken && wrapped.originalPcmHash == expected &&
        wrapped.sourceMode == expectedSource && wrapped.sourceSettled && wrapped.stemBusReadyMask == expectedStemMask,
        "short-loop tags and nonperiodic PCM retain the new owner and every requested Stems lane");
    if (replaceHeadBeforeClear) {
        auto replacement = head.cacheKey;
        replacement.loopRevision = 2U;
        replacement.stemPackageHash = 0U;
        replacement.startTimelineFrame = thirdHeadAfterClear ? 0U : 8'192U;
        replacement.endTimelineFrameExclusive = frames;
        require(runtime.beginLoopHeadPreparation(replacement, 1U, LoopHeadBoundaryMode::RetainedWindow, selected) ==
            LoopHeadPreparationBeginStatus::Accepted, "independent Original-only preparation preserves the installed Stems reader");
        require(runtime.configureLoopHeadPreparationLane(2U, 0U, kSampleRate, 801U) ==
            LoopHeadPreparationConfigureStatus::Accepted, "new Original head has its exact decoder epoch");
        bool complete = false;
        for (std::size_t offset = replacement.startTimelineFrame; offset < frames && !complete;) {
            const auto count = std::min<std::size_t>(480U, frames - offset);
            const auto offered = runtime.offerLoopHeadPreparationPcm(2U, 0U,
                {pcm[0U].data() + offset * 2U, count, {kSampleRate, 2U, LanePcmEncoding::Float32},
                    801U, offset * 1'000'000U / kSampleRate});
            require(offered.inputFramesAccepted > 0U, "independent head consumes actual later Original PCM");
            offset += offered.inputFramesAccepted;
            complete = offered.status == LoopHeadPreparationOfferStatus::CacheCommitted;
        }
        require(complete && !runtime.pcmCacheRangeReady(selectedKey, 127U, 0U, b),
            "latest global head truthfully cannot authorize the old all-lane range");
        require(runtime.publishDirectLoopPolicy(3U, selected.selectionToken, selected.generation, false, 0U, 0U) ==
            NativePlaybackPublishStatus::Accepted, "Clear requests linear PCM from the installed retained head");
        waitUntil([&] { const auto policy = output.acceptedLoopPolicy();
            return policy.revision == 3U && !policy.enabled && policy.physicalReady &&
                output.committedLoopCutoverHead().sourceStart == static_cast<double>(b);
        }, "unrelated global head must not prevent the installed Stems loop from clearing at exact B");
        const auto clearHead = output.committedLoopCutoverHead();
        expected = 14695981039346656037ULL;
        for (std::size_t frame = b; frame < b + clearHead.frames; ++frame)
            for (std::size_t channel = 0U; channel < 2U; ++channel) {
                expected ^= std::bit_cast<std::uint32_t>(pcm[0U][frame * 2U + channel]);
                expected *= 1099511628211ULL;
            }
        require(clearHead.token == selected.selectionToken && clearHead.generation == selected.generation &&
            !clearHead.loopEnabled && clearHead.sourceEnd == static_cast<double>(b + clearHead.frames) &&
            clearHead.originalPcmHash == expected && clearHead.sourceMode == NativeAudioSourceMode::Stems &&
            clearHead.sourceSettled && clearHead.stemBusReadyMask == ((1U << parts) - 1U),
            "Clear stages exact independent nonperiodic PCM/tags beyond B with every selected Stems lane");
        const auto releaseBaseline = output.loopCutoverWriteReleaseCount();
        require(output.presentCommittedLoopCutover(), "callback adopts the exact linear continuation");
        waitUntil([&] { return output.loopCutoverWriteReleaseCount() > releaseBaseline; },
            "linear continuation obtains actual producer ownership");
        require(runtime.loopProtocolSnapshot().preparation.commits == 2U,
            "Clear performs no replacement decode after the unrelated head committed");
        if (!thirdHeadAfterClear) return;
        const auto originalBefore = output.recordCount();
        require(runtime.publishCommand(selectedCommand(5U, NativePlaybackBank::First, selected, banks,
            controls(5U, 1.0F, 0.0F, MixerSourceMode::Original), false)) == NativePlaybackPublishStatus::Accepted,
            "paused linear Original replaces the old Stems head without changing transport owner");
        output.consumeAll();
        const auto originalIndex = waitForRecord(output, originalBefore, [=](const FakeOutputRecord &record) {
            return record.token == selected.selectionToken && record.generation == selected.generation &&
                record.sourceMode == NativeAudioSourceMode::Original && record.sourceSettled &&
                record.sourceStart >= static_cast<double>(b) && record.sourceEnd < static_cast<double>(frames);
        }, "paused Original presents its exact retained linear prefix before the old head ends", 4'000U, &runtime);
        const auto originalRecord = output.record(originalIndex);
        expected = 14695981039346656037ULL;
        for (std::size_t frame = static_cast<std::size_t>(originalRecord.sourceStart);
                frame < static_cast<std::size_t>(originalRecord.sourceStart) + originalRecord.frames; ++frame)
            for (std::size_t channel = 0U; channel < 2U; ++channel) {
                expected ^= std::bit_cast<std::uint32_t>(pcm[0U][frame * 2U + channel]);
                expected *= 1099511628211ULL;
            }
        require(!output.playing() && originalRecord.originalPcmHash == expected &&
            sameOwnership(runtime.diagnostics().committedOwnership, selected),
            "source retirement preserves exact paused Original PCM and the callback owner");
        auto third = head;
        third.cacheKey.loopRevision = 3U;
        third.cacheKey.stemPackageHash = bank.stemPackageHash;
        commitHead(third, selected, true);
        require(runtime.loopProtocolSnapshot().preparation.commits == 3U &&
            runtime.pcmCacheRangeReady(selectedKey, 127U, b, b + 256U),
            "third head retains the complete seven-lane prefix under the same selected owner");
        const auto stemsBefore = output.recordCount();
        require(runtime.publishCommand(selectedCommand(6U, NativePlaybackBank::First, selected, banks,
            controls(6U, 1.0F, 0.0F, MixerSourceMode::Stems), false)) == NativePlaybackPublishStatus::Accepted,
            "the same paused owner requests Stems from the independently committed third head");
        output.consumeAll();
        const auto stemsIndex = waitForRecord(output, stemsBefore, [=](const FakeOutputRecord &record) {
            return record.token == selected.selectionToken && record.generation == selected.generation &&
                record.sourceMode == NativeAudioSourceMode::Stems && record.sourceSettled &&
                record.stemBusReadyMask == ((1U << parts) - 1U) && !record.loopEnabled;
        }, "third head supplies every paused Stems lane without reviving the cleared loop", 4'000U, &runtime);
        require(!output.playing() && output.record(stemsIndex).sourceStart >= originalRecord.sourceStart &&
            sameOwnership(runtime.diagnostics().committedOwnership, selected),
            "third-head source replacement preserves paused linear transport ownership");
        return;
    }
    require(runtime.loopProtocolSnapshot().preparation.commits == (upgradeHead ? 2U : 1U),
        "transport owner change performs no replacement head decode");
}

void pendingBoundedHeadCannotWithdrawInstalledLoopPcm(bool abortReplacement, bool failReplacement = false,
    bool commitOnEos = false, bool clearAtEos = false) {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{28'015U, 2U};
    const std::uint64_t a = clearAtEos ? 528'000U : 48'000U;
    const std::size_t frames = clearAtEos ? 48'000U : 16'384U;
    const std::uint64_t b = a + (clearAtEos ? frames : 4'800U);
    std::vector<float> pcm(frames * 2U);
    for (std::size_t frame = 0U; frame < frames; ++frame) {
        pcm[frame * 2U] = .015625F + static_cast<float>(frame) / 1'048'576.F;
        pcm[frame * 2U + 1U] = -.03125F + static_cast<float>(frame) / 2'097'152.F;
    }
    SongLaneIngressBank first(65'536U, 480U), second(65'536U, 480U);
    configureLanes(first, owner.generation, 1U);
    for (std::size_t offset = 0U; offset < frames;) {
        const auto count = std::min<std::size_t>(480U, frames - offset);
        const auto offered = first.lane(0U)->offer({pcm.data() + offset * 2U, count,
            {kSampleRate, 2U, LanePcmEncoding::Float32}, owner.generation,
            (a + offset) * 1'000'000U / kSampleRate});
        require(offered.status == LaneOfferStatus::Accepted && offered.inputFramesAccepted == count,
            "nonperiodic late Original enters its actual decoder ingress");
        offset += count;
    }
    require(first.lane(0U)->markEndOfStream(owner.generation, a + frames).status == LaneEndOfStreamStatus::Accepted,
        "nonperiodic Original has an exact finite EOF");
    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    NativePlaybackRuntime runtime(runtimeConfiguration(65'536U, 256U), first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept { stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, a, 0U);
    bank.stemPackageHash = 0U;
    bank.cacheFrameUpperBound = std::numeric_limits<std::uint64_t>::max();
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        {bank, {}}, controls(1U))) == NativePlaybackPublishStatus::Accepted && runtime.start(),
        "late Original starts without a full-song cache");
    waitUntil([&] { return output.commitCount() == 1U; }, "late Original commits");
    require(output.presentFirstCurrentRecord(), "late Original reaches the callback");
    waitUntil([&] { return output.playing(); }, "late Original applies Play");
    auto head = managedLoopIntent(1U, owner, a);
    head.boundaryMode = commitOnEos ? LoopHeadBoundaryMode::OriginalEos : LoopHeadBoundaryMode::RetainedWindow;
    head.cacheKey.endTimelineFrameExclusive = a + (commitOnEos ? LoopHeadCache::kCapacityFrames : 8'192U);
    require(runtime.beginLoopHeadPreparation(head.cacheKey, 1U, head.boundaryMode, owner) ==
        LoopHeadPreparationBeginStatus::Accepted, "exact nonperiodic bounded head begins");
    require(runtime.configureLoopHeadPreparationLane(1U, 0U, kSampleRate, 701U) ==
        LoopHeadPreparationConfigureStatus::Accepted, "bounded Original decoder configures");
    const auto finishHeadPcm = [&](std::uint64_t revision, std::uint64_t generation, std::size_t offset = 0U) {
        while (offset < frames) {
            const auto count = std::min<std::size_t>(480U, frames - offset);
            const auto offered = runtime.offerLoopHeadPreparationPcm(revision, 0U,
                {pcm.data() + offset * 2U, count, {kSampleRate, 2U, LanePcmEncoding::Float32},
                 generation, (a + offset) * 1'000'000U / kSampleRate});
            if (offered.status == LoopHeadPreparationOfferStatus::CacheRejected) return false;
            require(offered.inputFramesAccepted > 0U && offered.inputFramesAccepted <= count,
                "bounded head consumes only an actual decoder prefix");
            offset += offered.inputFramesAccepted;
            if (offered.status == LoopHeadPreparationOfferStatus::CacheCommitted) return true;
        }
        return commitOnEos && runtime.markLoopHeadPreparationEndOfStream(revision, 0U, generation, a + frames) ==
            LoopHeadPreparationEndStatus::CacheCommitted;
    };
    require(finishHeadPcm(1U, 701U), "bounded head commits actual Original bytes");
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, bank.songIdentityHash,
        bank.originalContentHash, 0U};
    require(runtime.pcmCacheRangeReady(key, 1U, a, b), "committed bounded head authorizes exact A/B");
    require(!runtime.renderReadinessDiagnostics().currentSongCacheBoundToSelectedOwner,
        "the independently decoded bounded head is not a full-song canonical cache");
    const auto verifyPcm = [&](const FakeOutputRecord &record) {
        require(record.token == owner.selectionToken && record.generation == owner.generation &&
            record.sourceStart >= static_cast<double>(a) && record.sourceEnd <= static_cast<double>(a + frames) &&
            record.sourceEnd - record.sourceStart == static_cast<double>(record.frames),
            "callback payload has the exact owner and unity source-frame span");
        const auto firstFrame = static_cast<std::size_t>(record.sourceStart) - a;
        std::uint64_t expected = 14695981039346656037ULL;
        for (std::size_t frame = 0U; frame < record.frames; ++frame)
            for (std::size_t channel = 0U; channel < 2U; ++channel) {
                expected ^= std::bit_cast<std::uint32_t>(pcm[(firstFrame + frame) * 2U + channel]);
                expected *= 1099511628211ULL;
            }
        require(record.originalPcmHash == expected, "source tags must describe the exact nonperiodic Original PCM");
    };
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, true, a, b) ==
        NativePlaybackPublishStatus::Accepted, "bounded loop intent publishes");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; }, "bounded loop prepares exact A");
    verifyPcm(output.committedLoopCutoverHead());
    require(output.presentCommittedLoopCutover(), "callback installs the bounded loop");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 1U; }, "installed loop adopts its producer");
    const auto beforeWrap = output.recordCount();
    output.consumeAll();
    const auto boundary = waitForRecord(output, beforeWrap, [=](const FakeOutputRecord &record) {
        return record.loopEnabled && record.sourceEnd == static_cast<double>(b);
    }, "installed bounded producer emits exact B", 4'000U, &runtime);
    const auto wrap = waitForRecord(output, boundary + 1U, [=](const FakeOutputRecord &record) {
        return record.loopEnabled && record.sourceStart == static_cast<double>(a);
    }, "installed bounded producer emits A again", 4'000U, &runtime);
    verifyPcm(output.record(boundary));
    verifyPcm(output.record(wrap));
    waitUntil([&] { return output.loopCutoverCommitCount() == 2U; }, "installed loop has one dormant continuation");
    auto replacement = head.cacheKey;
    replacement.loopRevision = 2U;
    require(runtime.beginLoopHeadPreparation(replacement, 1U, head.boundaryMode, owner) ==
        LoopHeadPreparationBeginStatus::Accepted, "new decoder preparation is accepted without publishing its head");
    require(runtime.configureLoopHeadPreparationLane(2U, 0U, kSampleRate, 702U) ==
        LoopHeadPreparationConfigureStatus::Accepted, "pending replacement has its own decoder generation");
    const auto partial = runtime.offerLoopHeadPreparationPcm(2U, 0U,
        {pcm.data(), 480U, {kSampleRate, 2U, LanePcmEncoding::Float32}, 702U, a * 1'000'000U / kSampleRate});
    require(partial.inputFramesAccepted == 480U && partial.cacheFramesWritten != 0U &&
        partial.status != LoopHeadPreparationOfferStatus::CacheCommitted,
        "partial replacement PCM is written without claiming committed authority");
    if (failReplacement) {
        cache_test::fault.store(1);
        const auto restoreIo = onScopeExit([&]() noexcept { cache_test::fault.store(0); });
        require(!finishHeadPcm(2U, 702U, 480U) &&
            runtime.loopProtocolSnapshot().preparation.phase == LoopHeadPreparationPhase::Failed,
            "actual replacement write failure cannot publish the new head");
    }
    if (abortReplacement) require(runtime.abortLoopHeadPreparation(2U) == LoopHeadPreparationAbortStatus::Accepted,
        "uncommitted replacement aborts without deleting the installed head");
    require(runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation, false, 0U, 0U) ==
        NativePlaybackPublishStatus::Accepted, "Clear publishes while replacement owns no committed PCM");
    require(runtime.pcmCacheRangeReady(key, 1U, a, b),
        "pending or aborted head replacement must retain the installed committed head authority");
    auto futureKey = key;
    ++futureKey.generation;
    require(!runtime.pcmCacheRangeReady(futureKey, 1U, a, b),
        "a committed head cannot authorize a different owner generation");
    const auto clearStart = clearAtEos ? b - 1U : b;
    waitUntil([&] { const auto policy = output.acceptedLoopPolicy();
        return policy.revision == 2U && !policy.enabled && policy.physicalReady &&
            output.committedLoopCutoverHead().sourceStart == static_cast<double>(clearStart);
    }, "Clear prepares the exact linear B continuation from the retained committed head");
    verifyPcm(output.committedLoopCutoverHead());
    if (clearAtEos) {
        const auto terminal = output.committedLoopCutoverHead();
        require(terminal.frames == 1U && terminal.sourceEnd == static_cast<double>(b) &&
            terminal.endOfStreamAfterBlock && !terminal.loopEnabled && runtime.diagnostics().audibleEnd.sequence == 0U,
            "EOF Clear stages the final real sample but cannot end before callback adoption");
    }
    const auto beforeClear = output.recordCount();
    require(output.presentCommittedLoopCutover(), "callback adopts linear Clear");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 2U; }, "Clear adopts its exact producer");
    output.consumeAll();
    if (clearAtEos) {
        runtime.notifyIngressAvailable();
        waitUntil([&] { const auto end = runtime.diagnostics().audibleEnd;
            return end.sequence != 0U && sameOwnership(end.ownership, owner) &&
                end.directLoopPolicyRevision == 2U;
        }, "EOF Clear drains exact terminal callback PCM and publishes the owned audible end");
        return;
    }
    const auto linear = waitForRecord(output, beforeClear, [=](const FakeOutputRecord &record) {
        return !record.loopEnabled && record.sourceStart >= static_cast<double>(b) && record.frames != 0U;
    }, "cleared producer advances beyond B without wrapping", 4'000U, &runtime);
    verifyPcm(output.record(linear));
    const auto disabledPhysicalRevision = output.acceptedLoopPolicy().physicalRevision();
    require(disabledPhysicalRevision != 0U,
        "Clear has a callback-accepted linear physical identity");
    require(runtime.publishDirectLoopPolicy(3U, owner.selectionToken, owner.generation, false, 0U, 0U) ==
        NativePlaybackPublishStatus::Accepted, "a redundant Clear keeps the same playback owner");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().directAtomicPolicyObservedRevision == 3U; },
        "the producer observes redundant Clear before fresh linear refill");
    const auto afterRedundantClear = output.recordCount();
    output.consumeAll();
    const auto continued = waitForRecord(output, afterRedundantClear, [=](const FakeOutputRecord &record) {
        return record.token == owner.selectionToken && record.generation == owner.generation && !record.loopEnabled &&
            record.sourceStart >= static_cast<double>(b) && record.frames != 0U;
    }, "redundant Clear continues real bounded-head PCM", 4'000U, &runtime);
    const auto continuedRecord = output.record(continued);
    require(continuedRecord.loopPolicyRevision == disabledPhysicalRevision && continuedRecord.loopRevision == 0U,
        "Redundant Clear must retain the callback's accepted disabled physical identity");
    verifyPcm(continuedRecord);
    if (!abortReplacement && !failReplacement)
        require(runtime.abortLoopHeadPreparation(2U) == LoopHeadPreparationAbortStatus::Accepted,
            "finished Clear can retire the remaining partial staging bytes");
    replacement.loopRevision = 3U;
    const SongRenderOwnership futureOwner{owner.selectionToken, owner.generation + 1U};
    require(runtime.beginLoopHeadPreparation(replacement, 1U, head.boundaryMode, futureOwner) ==
        LoopHeadPreparationBeginStatus::Accepted &&
        runtime.configureLoopHeadPreparationLane(3U, 0U, kSampleRate, 703U) ==
            LoopHeadPreparationConfigureStatus::Accepted,
        "next exact owner begins a separately owned replacement");
    require(runtime.pcmCacheRangeReady(key, 1U, a, b) && !runtime.pcmCacheRangeReady(futureKey, 1U, a, b),
        "accepted future-owner staging cannot steal committed current-owner authority");
    require(finishHeadPcm(3U, 703U) && runtime.pcmCacheRangeReady(futureKey, 1U, a, b) &&
        !runtime.pcmCacheRangeReady(key, 1U, a, b),
        "only the actual replacement commit transfers bounded-head authority to its exact owner");
}

void boundedLateHeadUsesAtomicRuntimeWithMaximumParts(bool stems, bool longWindow = false,
    bool failWindowRead = false, bool replaceBeforeFailure = false) {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{28'010U, 2U};
    const std::uint64_t a = (longWindow ? 19U : 14U) * 60U * 48'000U;
    const std::uint64_t b = a + (longWindow ? 5U * 48'000U : 4'800U);
    constexpr std::uint32_t parts = 9U;
    SongLaneIngressBank first(32'768U, 480U), second(32'768U, 480U);
    configureLanes(first, owner.generation, parts + 1U);
    for (std::size_t lane = 0U; lane <= parts; ++lane) offerLane(first, lane, owner.generation, a, 16'384U, false);
    FakeOutput output(4'096U, 256U); output.setMaximumAcceptance(256U);
    auto configuration = runtimeConfiguration(32'768U, 256U);
    NativePlaybackRuntime runtime(configuration, first, second, output.port());
    const auto cleanup = onScopeExit([&]() noexcept { stopAndJoin(runtime); });
    auto bank = assignedBank(1U, owner, a, parts);
    bank.cacheFrameUpperBound = std::numeric_limits<std::uint64_t>::max();
    const auto mode = stems ? MixerSourceMode::Stems : MixerSourceMode::Original;
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner,
        {bank, NativePlaybackBankIntent{}}, controls(1U, 1.F, 0.F, mode))) == NativePlaybackPublishStatus::Accepted &&
        runtime.start(), "fifteen-minute max-role owner starts ordinary late playback");
    waitUntil([&] { return output.commitCount() == 1U; }, "late ordinary owner commits");
    require(output.presentFirstCurrentRecord(), "late ordinary owner is callback-presented");
    waitUntil([&] {
        return output.playing() && sameOwnership(runtime.diagnostics().committedOwnership, owner);
    }, "late ordinary owner has applied its playing transport before window admission");
    auto intent = managedLoopIntent(1U, owner, a, stems ? 0x3ffU : 1U);
    if (longWindow) {
        intent.boundaryMode = LoopHeadBoundaryMode::RetainedWindow;
        intent.cacheKey.endTimelineFrameExclusive = b + LoopHeadCache::kCapacityFrames;
        cache_test::fault.store(2);
        const auto restoreSpace = onScopeExit([&]() noexcept { cache_test::fault.store(0); });
        require(runtime.beginLoopHeadPreparation(intent.cacheKey, intent.requiredLaneMask,
            intent.boundaryMode, owner) == LoopHeadPreparationBeginStatus::InsufficientSpace,
            "low storage rejects the exact requested window before any file publication");
        require(sameOwnership(runtime.diagnostics().committedOwnership, owner) && output.playing(),
            "window admission failure preserves the committed Original owner and transport");
        cache_test::fault.store(0);
    }
    prepareFullManagedLoopHead(runtime, intent, 700U, parts + 1U, owner);
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, bank.songIdentityHash,
        bank.originalContentHash, bank.stemPackageHash};
    require(runtime.pcmCacheRangeReady(key, intent.requiredLaneMask, a, b), "bounded late range has exact owner authority");
    auto stale = key; ++stale.generation;
    require(!runtime.pcmCacheRangeReady(stale, intent.requiredLaneMask, a, b) &&
        !runtime.pcmCacheRangeReady(key, intent.requiredLaneMask, a, intent.cacheKey.endTimelineFrameExclusive + 1U),
        "bounded preparation cannot authorize stale owners or absent tail PCM");
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, true, a, b) ==
        NativePlaybackPublishStatus::Accepted, "late bounded head uses the ordinary atomic loop command");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; }, "late bounded head commits one atomic continuation");
    const auto installed = output.lastPublishedLoopPolicy();
    require(installed.enabled && installed.physicalReady && installed.aTimelineFrame == a &&
        installed.bTimelineFrameExclusive == b && output.committedLoopCutoverHead().sourceStart == static_cast<double>(a),
        "late atomic continuation binds exact requested A and B");
    require(output.presentCommittedLoopCutover(), "callback adopts the late independent head");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 1U; }, "late callback releases the old linear producer");
    const auto restoreIo = onScopeExit([&]() noexcept { cache_test::fault.store(0); });
    if (failWindowRead) {
        if (replaceBeforeFailure) {
            auto replacement = intent;
            replacement.requiredLaneMask = 1U;
            replacement.cacheKey.loopRevision = 2U;
            replacement.cacheKey.stemPackageHash = 0U;
            replacement.cacheKey.endTimelineFrameExclusive = a + LoopHeadCache::kCapacityFrames;
            prepareFullManagedLoopHead(runtime, replacement, 900U, 1U, owner);
            require(runtime.loopProtocolSnapshot().preparation.key.loopRevision == 2U &&
                !runtime.pcmCacheRangeReady(key, intent.requiredLaneMask, a, b),
                "new Original-only global head does not replace the active all-lane retained window");
        }
        const auto protectedIndex = output.recordCount() - 1U;
        const auto protectedPcm = output.record(protectedIndex);
        cache_test::observed.store(false);
        cache_test::fault.store(5);
        if (replaceBeforeFailure) {
            require(runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation, true,
                a + LoopHeadCache::kCapacityFrames, b) == NativePlaybackPublishStatus::Accepted,
                "retargeted loop A requires the older head's actual file-backed body");
            waitUntil([&] { output.consumeAll(); return cache_test::observed.load(); },
                "the older installed file-backed window encounters the actual injected read error");
        }
        waitUntil([&] {
            output.consumeAll();
            const auto ready = runtime.renderReadinessDiagnostics();
            if (!replaceBeforeFailure) return ready.lanes[0].ingressFailureGeneration == owner.generation;
            for (std::size_t lane = 0U; lane <= parts; ++lane)
                if (ready.lanes[lane].ingressFailureGeneration == owner.generation) return true;
            return false;
        }, "retained-window read error publishes the exact failed decoder generation");
        const auto failed = runtime.renderReadinessDiagnostics();
        require(sameOwnership(failed.ownership, owner) && failed.lanes[0].generation == owner.generation &&
            output.record(protectedIndex).originalPcmHash == protectedPcm.originalPcmHash &&
            output.record(protectedIndex).fingerprint == protectedPcm.fingerprint,
            "failed retained reads preserve prior accepted PCM and attribute the existing failure channel to the exact owner");
        require(!runtime.pcmCacheRangeReady(key, intent.requiredLaneMask, a, b),
            "failed window reads revoke range authority instead of inventing PCM");
        return;
    }
    const auto begin = output.recordCount(); output.consumeAll();
    const auto boundary = waitForRecord(output, begin, [=](const FakeOutputRecord &record) {
        return record.loopEnabled && record.sourceEnd == static_cast<double>(b);
    }, "late bounded producer reaches exact B", 4'000U, &runtime);
    const auto wrap = waitForRecord(output, boundary + 1U, [=](const FakeOutputRecord &record) {
        return record.loopEnabled && record.sourceStart == static_cast<double>(a);
    }, "late bounded producer wraps to exact A", 4'000U, &runtime);
    require(output.record(wrap).generation == owner.generation && output.record(wrap).peak > 0.001F &&
        (!stems || (output.record(wrap).stemBusReadyMask == 0x1ffU && output.record(wrap).sourceMode == NativeAudioSourceMode::Stems)),
        "late bounded callback payload retains every real selected part and Original owner");
    waitUntil([&] { return output.loopCutoverCommitCount() == 2U; }, "late loop retains one bounded dormant continuation");
    require(runtime.publishDirectLoopPolicy(2U, owner.selectionToken, owner.generation, false, 0U, 0U) ==
        NativePlaybackPublishStatus::Accepted, "late bounded loop accepts Clear without changing transport");
    waitUntil([&] {
        const auto policy = output.acceptedLoopPolicy();
        return policy.revision == 2U && !policy.enabled && policy.physicalReady &&
            output.committedLoopCutoverHead().sourceStart == static_cast<double>(b);
    }, "late bounded Clear prepares an exact linear continuation at B");
    const auto recordsBeforeClear = output.recordCount();
    require(output.presentCommittedLoopCutover(), "callback adopts the late linear continuation");
    waitUntil([&] { return output.loopCutoverWriteReleaseCount() == 2U; }, "late Clear releases its preceding producer");
    output.consumeAll();
    static_cast<void>(waitForRecord(output, recordsBeforeClear, [=](const FakeOutputRecord &record) {
        return !record.loopEnabled && record.sourceStart >= static_cast<double>(b) &&
            record.sourceEnd > static_cast<double>(b) && record.generation == owner.generation && record.peak > .001F;
    }, "ordinary playback continues after late bounded Clear", 4'000U, &runtime));

}
