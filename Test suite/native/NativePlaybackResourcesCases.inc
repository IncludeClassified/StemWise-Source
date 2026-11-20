void originalPublicationReevaluatesTransientStemOfferReceipts() {
    using namespace stemwise::audio::engine;
    for (const std::size_t frames : {1'000U, 5'096U}) {
        NativePlaybackResources resources(runtimeConfiguration().pipeline, {}, +[](void *) noexcept {}, nullptr);
        const CurrentSongCacheKey key{28'400U + frames, 3U, 47U, 53U, 0U};
        const auto original = tone(frames, 0U, .031F, 419.0);
        const auto stem = tone(frames, 0U, .017F, 631.0);
        bool finished{};
        CurrentSongPcmCache *cache{};
        waitUntil([&] { cache = resources.createCache(key, 7U, frames, finished); return finished && cache; },
            "transient offer fixture creates its three real cache lanes");
        const auto offer = [&](std::size_t lane, std::size_t first, std::size_t count, const float *pcm) {
            CurrentSongCacheOfferResult receipt{};
            waitUntil([&] {
                receipt = resources.offer(key, lane, first, pcm, count);
                return receipt.status != CurrentSongCacheOfferStatus::Pending;
            }, "actual I/O owner returns an offer receipt");
            return receipt;
        };
        for (std::size_t first = 0U; first < frames; first += 4'096U) {
            const auto count = std::min<std::size_t>(4'096U, frames - first);
            require(offer(0U, first, count, original.data() + first * 2U).inputFramesConsumed == count,
                "Original accepts its real unsealed PCM");
        }
        const std::size_t first = frames > 4'096U ? 2'048U : 0U;
        const auto count = frames - first;
        for (const auto lane : {1U, 2U}) {
            if (first != 0U) require(offer(lane, 0U, 3'072U, stem.data()).inputFramesConsumed == 3'072U,
                "stem commits its published Original prefix");
            const auto pending = offer(lane, first, count, stem.data() + first * 2U);
            require(pending.inputFramesConsumed == (first == 0U ? 0U : 2'048U) &&
                pending.status == (first == 0U ? CurrentSongCacheOfferStatus::OriginalRangePending :
                    CurrentSongCacheOfferStatus::Accepted), "unsealed Original yields a transient exact prefix");
            const auto again = offer(lane, first, count, stem.data() + first * 2U);
            require(again.inputFramesConsumed == pending.inputFramesConsumed,
                "unchanged retry never invents progress");
        }
        waitUntil([&] { return resources.seal(key, 0U, frames) == CurrentSongCacheSealStatus::Accepted; },
            "Original seal publishes its partial final chunk");
        // Retry the most recently consumed receipt first, before another offer can replace it.
        for (const auto lane : {2U, 1U}) {
            const auto accepted = offer(lane, first, count, stem.data() + first * 2U);
            require(accepted.status == CurrentSongCacheOfferStatus::Accepted && accepted.inputFramesConsumed == count,
                "same stem offer must see newly published Original coverage");
            const auto duplicate = offer(lane, first, count, stem.data() + first * 2U);
            require(duplicate.inputFramesConsumed == count && duplicate.nextExpectedFrame == frames,
                "accepted prefix replay remains idempotent");
            waitUntil([&] { return resources.seal(key, lane, frames) == CurrentSongCacheSealStatus::Accepted; },
                "each completed stem seals after retry");
            std::vector<float> actual(frames * 2U);
            for (std::size_t offset = 0U; offset < frames; offset += 4'096U)
                require(resources.readPcmCacheForAudit(key, lane, offset, actual.data() + offset * 2U,
                    std::min<std::size_t>(4'096U, frames - offset)), "each bounded read returns committed PCM");
            require(actual == stem,
                "retry retains exactly the supplied stem samples without duplicates or gaps");
        }
    }
}

void boundedWaveformReadsUseCanonicalPcmAndRejectStaleOrCancelledOwners() {
    using namespace stemwise::audio::engine;
    std::atomic<std::uint64_t> wakes{};
    NativePlaybackResources resources(runtimeConfiguration().pipeline, {}, +[](void *context) noexcept {
        static_cast<std::atomic<std::uint64_t> *>(context)->fetch_add(1U, std::memory_order_release);
    }, &wakes);
    constexpr CurrentSongCacheKey key{28'399U, 3U, 47U, 53U, 0U};
    constexpr std::size_t frames = 8'192U;
    const auto source = tone(frames, 0U, .031F, 419.0);
    bool finished{};
    CurrentSongPcmCache *cache{};
    waitUntil([&] { cache = resources.createCache(key, 1U, frames, finished); return finished && cache; },
        "waveform read uses the existing canonical I/O owner");
    for (std::size_t first = 0U; first < frames; first += 2'048U)
        waitUntil([&] { return resources.offer(key, 0U, first, source.data() + first * 2U, 2'048U).status == CurrentSongCacheOfferStatus::Accepted; },
            "waveform fixture commits actual PCM chunks");
    waitUntil([&] { return resources.seal(key, 0U, frames) == CurrentSongCacheSealStatus::Accepted; }, "waveform source seals");
    std::vector<float> actual(6'144U * 2U);
    const auto ticket = resources.beginPcmWindowRead(key, 0U, 1'024U, 6'144U);
    require(ticket != 0U, "bounded waveform request admitted without a decoder");
    waitUntil([&] { return resources.pollPcmWindowRead(ticket, actual.data(), 6'144U) != 0; }, "waveform I/O read finishes");
    require(resources.pollPcmWindowRead(ticket, actual.data(), 6'144U) == 6'144 &&
        std::equal(actual.begin(), actual.end(), source.begin() + 2'048U), "waveform PCM matches independent committed source samples");
    resources.cancelPcmWindowRead(ticket);
    const auto partial = resources.beginPcmWindowRead(key, 0U, 6'144U, 6'144U);
    waitUntil([&] { return resources.pollPcmWindowRead(partial, actual.data(), 6'144U) != 0; }, "covered waveform prefix settles");
    require(resources.pollPcmWindowRead(partial, actual.data(), 6'144U) == 2'048 &&
        std::equal(actual.begin(), actual.begin() + 4'096U, source.begin() + 12'288U),
        "partial canonical coverage returns its exact real prefix without inventing missing PCM");
    resources.cancelPcmWindowRead(partial);
    const auto uncovered = resources.beginPcmWindowRead(key, 0U, frames, 6'144U);
    waitUntil([&] { return resources.pollPcmWindowRead(uncovered, actual.data(), 6'144U) != 0; }, "uncovered waveform settles");
    require(resources.pollPcmWindowRead(uncovered, actual.data(), 6'144U) == -1,
        "absent coverage is distinct from stale ownership and corruption");
    require(resources.pollPcmWindowRead(uncovered, actual.data(), 1U) == -2,
        "a malformed receipt request is corruption, never unsupported coverage");
    resources.cancelPcmWindowRead(uncovered);
    auto stale = key; ++stale.generation;
    const auto wrong = resources.beginPcmWindowRead(stale, 0U, 1'024U, 6'144U);
    waitUntil([&] { return resources.pollPcmWindowRead(wrong, actual.data(), 6'144U) != 0; }, "stale waveform settles unavailable");
    require(resources.pollPcmWindowRead(wrong, actual.data(), 6'144U) == -3, "stale cache ownership is distinct from uncovered PCM");
    resources.cancelPcmWindowRead(wrong);
    const auto cancelled = resources.beginPcmWindowRead(key, 0U, 0U, 6'144U);
    resources.cancelPcmWindowRead(cancelled);
    require(resources.pollPcmWindowRead(cancelled, actual.data(), 6'144U) == -3, "cancelled request loses all publication authority");
    const auto next = resources.beginPcmWindowRead(key, 0U, 1'024U, 6'144U);
    require(next > cancelled, "replacement waveform has its own exact ticket");
    resources.cancelPcmWindowRead(cancelled);
    waitUntil([&] { return resources.pollPcmWindowRead(next, actual.data(), 6'144U) != 0; }, "replacement waveform completes");
    require(resources.pollPcmWindowRead(next, actual.data(), 6'144U) == 6'144, "late cancellation cannot retire a replacement");
    resources.cancelPcmWindowRead(next);
    [[maybe_unused]] const auto restoreReadFault = onScopeExit([] { cache_test::fault.store(0); });
    cache_test::fault.store(5);
    const auto corrupt = resources.beginPcmWindowRead(key, 0U, 0U, 6'144U);
    waitUntil([&] { return resources.pollPcmWindowRead(corrupt, actual.data(), 6'144U) != 0; }, "failed canonical I/O settles explicitly");
    require(resources.pollPcmWindowRead(corrupt, actual.data(), 6'144U) == -2,
        "actual canonical read failure cannot be downgraded to retryable unsupported coverage");
    resources.cancelPcmWindowRead(corrupt);
    cache_test::fault.store(0);
    resources.retireCache();
    auto replacement = key; ++replacement.generation;
    CurrentSongCacheOfferResult offered{};
    waitUntil([&] {
        offered = resources.offer(replacement, 0U, 0U, source.data(), 16U);
        return offered.status != CurrentSongCacheOfferStatus::Pending;
    }, "retired cache resolves the new owner's actual offer");
    require(offered.status == CurrentSongCacheOfferStatus::NotBegun && offered.inputFramesConsumed == 0U &&
        offered.framesStored == 0U && offered.nextExpectedFrame == 0U,
        "an absent cache cannot reuse another owner's accepted offer receipt");
    CurrentSongCacheSealStatus sealed{};
    waitUntil([&] {
        sealed = resources.seal(replacement, 0U, 16U);
        return sealed != CurrentSongCacheSealStatus::Pending;
    }, "retired cache resolves the new owner's EOS");
    require(sealed == CurrentSongCacheSealStatus::NotBegun,
        "an absent cache cannot reuse another owner's accepted seal receipt");
}

void fakeOutputUsesTheActualOutputOwnersControlPublicationNamespace() {
    constexpr SongRenderOwnership owner{28'303U, 2U};
    FakeOutput output;
    const auto port = output.port();
    require(port.flush(port.handle, owner.generation) == stemwise::audio::NativeAudioFlushStatus::Accepted,
        "fixture control owner installs normally");
    stemwise::audio::NativeAudioLateMixControl control{};
    control.publicationSequence = 25U;
    control.generation = owner.generation;
    control.selectionToken = owner.selectionToken;
    control.canonicalControlRevision = control.sourceIntentRevision = control.stemIntentRevision = 2U;
    control.musicGain = .5F;
    control.mixHeadroom = 1.0F;
    control.sourceRampFrames = control.laneRampFrames = control.masterRampFrames = 1U;
    require(port.publishLateMixControl(port.handle, control), "fixture accepts the current runtime-local counter");
    control.publicationSequence = 14U;
    require(port.publishLateMixControl(port.handle, control),
        "fixture must accept the same endpoint with an earlier runtime-local counter just like actual Core");
    auto wrongOwner = control;
    ++wrongOwner.selectionToken;
    require(!port.publishLateMixControl(port.handle, wrongOwner), "fixture preserves exact active role ownership");
    auto oldRevision = control;
    oldRevision.publicationSequence = 100U;
    oldRevision.canonicalControlRevision = oldRevision.sourceIntentRevision = oldRevision.stemIntentRevision = 1U;
    require(!port.publishLateMixControl(port.handle, oldRevision), "fixture preserves canonical and intent revision floors");
    const auto actual = output.lastLateControl();
    require(actual.selectionToken == owner.selectionToken && actual.generation == owner.generation &&
        actual.canonicalControlRevision == 2U && actual.musicGain == .5F && actual.publicationSequence == 14U,
        "fixture observes frozen producer control without restamping its numerical base");
}

void residentCopyContentionWakesTheCoordinatorWithoutMoreDiskIo() {
    using namespace stemwise::audio::engine;
    std::atomic<std::uint64_t> wakes{};
    NativePlaybackResources resources(runtimeConfiguration().pipeline, {}, +[](void *context) noexcept {
        static_cast<std::atomic<std::uint64_t> *>(context)->fetch_add(1U, std::memory_order_release);
    }, &wakes);
    constexpr CurrentSongCacheKey key{28'302U, 3U, 47U, 53U, 0U};
    constexpr std::size_t frames = CurrentSongPcmCache::kChunkFrames;
    const auto source = tone(frames, 0U, .031F, 419.0);
    bool finished{};
    CurrentSongPcmCache *cache{};
    waitUntil([&] { cache = resources.createCache(key, 1U, frames, finished); return finished && cache; },
        "copy-contention fixture creates its actual canonical store");
    waitUntil([&] { return resources.offer(key, 0U, 0U, source.data(), frames).status == CurrentSongCacheOfferStatus::Accepted; },
        "copy-contention fixture publishes exact canonical PCM");
    waitUntil([&] { return resources.seal(key, 0U, frames) == CurrentSongCacheSealStatus::Accepted; },
        "copy-contention fixture seals its real source");
    const auto reader = std::make_shared<CurrentSongPcmReadConsumer>();
    const auto retireReader = onScopeExit([&] { reader->active.store(false); });
    waitUntil([&] { return cache->admitReadConsumer(reader, key.selectionToken, key.generation); },
        "selected reader admits its exact actual cache owner");
    std::array<float, 64U * 2U> actual{};
    cache->requireReadRange(*reader, 3U, 1U, 0U, 64U);
    waitUntil([&] { return cache->copyLane(0U, 0U, actual.data(), 64U, 64U, true, reader.get(), 3U); },
        "actual I/O owner materializes the selected input before contention");
    require(resources.readPcmCacheForAudit(key, 0U, 0U, actual.data(), 64U),
        "serialized I/O readback retires all preceding read-service work");
    const auto readsBefore = resources.diagnostics().pcmIo.readOperations;
    const auto wakesBefore = wakes.load(std::memory_order_acquire);
    static std::atomic<const CurrentSongPcmReadConsumer *> target{};
    static std::atomic<bool> deferOnce{};
    target.store(reader.get());
    deferOnce.store(true);
    cache_test::deferReadCopy.store(+[](const CurrentSongPcmReadConsumer *candidate, std::size_t,
        std::uint64_t, std::size_t, std::size_t) noexcept {
        return candidate == target.load() && deferOnce.exchange(false);
    });
    const auto releaseHook = onScopeExit([] { cache_test::deferReadCopy.store(nullptr); target.store(nullptr); });
    require(!cache->copyLane(0U, 0U, actual.data(), 64U, 64U, true, reader.get(), 3U) && !deferOnce.load(),
        "one selected resident copy takes the actual nonblocking-contention failure path");
    waitUntil([&] { return wakes.load(std::memory_order_acquire) > wakesBefore; },
        "resident copy contention must explicitly wake the coordinator even when no disk read can progress");
    require(cache->copyLane(0U, 0U, actual.data(), 64U, 64U, true, reader.get(), 3U) &&
        std::equal(actual.begin(), actual.end(), source.begin()) &&
        resources.diagnostics().pcmIo.readOperations == readsBefore,
        "retry keeps exact already-resident PCM and performs no invented disk work");
}

void fullCurrentOutputRetriesDeferredCacheRequirementPublication() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{28'301U, 2U};
    constexpr std::size_t target = 1'024U;
    static std::atomic<bool> defer{};
    static std::atomic<const CurrentSongPcmReadConsumer *> deferredReader{};
    defer.store(true);
    deferredReader.store(nullptr);
    cache_test::deferReadRequirement.store(+[](const CurrentSongPcmReadConsumer &reader,
        std::size_t domain, std::uint64_t first, std::size_t) noexcept {
        if (defer.load() && !reader.preparation.load() && domain == 3U && first == target) {
            deferredReader.store(&reader, std::memory_order_release);
            return true;
        }
        return false;
    });
    const auto releaseHook = onScopeExit([] {
        defer.store(false);
        cache_test::deferReadRequirement.store(nullptr);
    });
    SongLaneIngressBank first(32'768U, 256U), second(32'768U, 256U);
    configureLanes(first, owner.generation);
    offerAllLanes(first, owner.generation, 16'384U);
    FakeOutput output(4'096U, 256U);
    output.setMaximumAcceptance(256U);
    output.setLoopCutoverRequiredFrames(768U);
    NativePlaybackRuntime runtime(runtimeConfiguration(32'768U, 256U), first, second, output.port());
    const std::array<NativePlaybackBankIntent, 2U> banks{assignedBank(1U, owner), NativePlaybackBankIntent{}};
    require(runtime.publishCommand(selectedCommand(1U, NativePlaybackBank::First, owner, banks, controls(1U))) ==
        NativePlaybackPublishStatus::Accepted && runtime.start(), "deferred-publication owner starts normally");
    waitUntil([&] { return output.queued() == target && deferredReader.load(std::memory_order_acquire) != nullptr; },
        "actual post-render publication is deferred with the current queue full");
    const auto *reader = deferredReader.load(std::memory_order_acquire);
    require(reader->active.load() && reader->pendingDomains.load() != 0U,
        "failed publication retains exact active-reader pending authority");
    const auto rendered = runtime.diagnostics().outputFramesRendered;
    const auto records = output.recordCount();
    defer.store(false);
    waitUntil([&] { return reader->pendingDomains.load(std::memory_order_acquire) == 0U; },
        "full current output retries its deferred cache publication without another render");
    require(output.queued() == target && output.recordCount() == records &&
        runtime.diagnostics().outputFramesRendered == rendered,
        "publication retry neither drains nor renders extra current PCM");
    waitUntil([&] { return runtime.renderReadinessDiagnostics().currentSongCacheCompleteLaneMask == (1U << kLaneCount) - 1U; },
        "same actual canonical source completes independently of publication retry");
    require(output.presentFirstCurrentRecord(), "actual current record supplies callback authority");
    require(runtime.publishDirectLoopPolicy(1U, owner.selectionToken, owner.generation, true, 128U, 768U) ==
        NativePlaybackPublishStatus::Accepted, "same-owner spare loop is accepted");
    waitUntil([&] { return output.loopCutoverCommitCount() == 1U; },
        "spare progresses after pending publication clears with current output still full");
    require(output.queued() == target, "spare service preserves the full current output reserve");
}

void activeCanonicalReadsSurviveHeldDspPreparation(bool coldPipeline, std::uint32_t parts) {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    const auto configured = runtimeConfiguration().pipeline;
    NativePlaybackResources resources(configured, {}, [](void *) noexcept {}, nullptr);
    constexpr SongRenderOwnership owner{28'101U, 7U};
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 31U, 37U, parts == 0U ? 0U : 41U};
    constexpr std::size_t frames = 16'384U, first = 8'192U, count = 64U;
    bool finished{};
    CurrentSongPcmCache *cache{};
    waitUntil([&] { cache = resources.createCache(key, parts + 1U, frames, finished); return finished && cache; },
        "held preparation fixture creates the real canonical cache");
    std::vector<std::vector<float>> source;
    for (std::size_t lane = 0U; lane <= parts; ++lane) {
        source.push_back(tone(frames, 0U, .025F, 220.0 + lane * 31.0));
        for (std::size_t frame = 0U; frame < frames; frame += CurrentSongPcmCache::kChunkFrames)
            waitUntil([&] { return resources.offer(key, lane, frame, source.back().data() + frame * 2U,
                CurrentSongPcmCache::kChunkFrames).status == CurrentSongCacheOfferStatus::Accepted; },
                "held preparation fixture commits exact canonical PCM");
        waitUntil([&] { return resources.seal(key, lane, frames) == CurrentSongCacheSealStatus::Accepted; },
            "held preparation fixture seals each actual lane");
    }
    auto activeConfiguration = configured;
    activeConfiguration.stemLaneCount = parts;
    SongRenderPipeline active(activeConfiguration), spare(configured);
    static std::atomic<bool> holdRead{}, readEntered{}, readTimedOut{};
    holdRead.store(true); readEntered.store(false); readTimedOut.store(false);
    beforeReadIo.store(+[]() noexcept {
        readEntered.store(true, std::memory_order_release);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        while (holdRead.load(std::memory_order_acquire)) {
            if (std::chrono::steady_clock::now() >= deadline) { readTimedOut.store(true); return; }
            std::this_thread::yield();
        }
    });
    const auto releaseRead = onScopeExit([] { holdRead.store(false); beforeReadIo.store(nullptr); });
    require(active.beginSong(owner, owner.generation, first, 0U) == SongRenderResetStatus::Ok &&
        active.bindCurrentSongPcmCache(owner, cache), "active reader binds exact committed source identity");
    auto activeControls = controls(1U, 1.0F, 0.0F,
        parts == 0U ? MixerSourceMode::Original : MixerSourceMode::Stems);
    activeControls.applicationEpoch = 1U;
    require(active.submitControls(activeControls) == SongRenderControlStatus::Accepted,
        "active reader requests its real supported source mode");
    std::unique_ptr<SongRenderPipeline> future;
    const auto prepare = [&] {
        return coldPipeline ? resources.preparePipeline(1U, 101U, stemwise::audio::kStemwiseStemBusCount, future)
            : resources.prepareLanes(1U, 101U, stemwise::audio::kStemwiseStemBusCount, spare);
    };
    blocked.store(false);
    blockTimedOut.store(false);
    releaseBlocked.store(false);
    fault.store(9);
    const auto cleanup = onScopeExit([] { releaseBlocked.store(true); fault.store(0); });
    require(!prepare(), "future DSP work is admitted asynchronously");
    waitUntil([&] { return blocked.load(); }, "future DSP construction reaches its bounded preparation hold");
    std::array<float, count * kStemwiseMultibusStride> payload{};
    const auto reads = resources.diagnostics().pcmIo.readOperations;
    auto rendered = active.renderMultibus(owner, payload.data(), count, count);
    require(rendered.framesProduced == 0U, "the active renderer must actually request a cold committed window");
    waitUntil([] { return readEntered.load(); }, "the exact active cache I/O reaches its bounded hold");
    holdRead.store(false, std::memory_order_release);
    waitUntil([&] {
        rendered = active.renderMultibus(owner, payload.data(), count, count);
        return rendered.framesProduced == count || blockTimedOut.load();
    }, "active canonical read service must progress while future DSP construction remains held");
    require(!readTimedOut.load() && !blockTimedOut.load() && !releaseBlocked.load() && rendered.framesProduced == count,
        "active PCM waited for the nonurgent preparation hold to expire");
    require(rendered.ownership.generation == owner.generation && rendered.ownership.selectionToken == owner.selectionToken &&
        rendered.sourceTimeline.startFrame == first && rendered.sourceTimeline.endFrameExclusive == first + count,
        "read service preserves the active render owner and source clock");
    for (std::size_t lane = 0U; lane <= parts; ++lane)
        for (std::size_t frame = 0U; frame < count; ++frame)
            for (std::size_t channel = 0U; channel < 2U; ++channel)
                require(payload[frame * kStemwiseMultibusStride + lane * 2U + channel] ==
                    source[lane][(first + frame) * 2U + channel],
                    "serviced active reads must contain exact canonical lane PCM");
    require(resources.diagnostics().pcmIo.readOperations > reads && !prepare(),
        "actual read I/O finishes before the unrelated preparation receipt can become ready");
    releaseBlocked.store(true);
    fault.store(0);
    waitUntil(prepare, "the exact future preparation completes after release");
    require((coldPipeline ? future->stemLaneCount() : spare.stemLaneCount()) == stemwise::audio::kStemwiseStemBusCount,
        "future preparation retains its exact requested lane capacity");
}

void retiredPreparationCannotReplaceTheNewCanonicalOwner() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    NativePlaybackResources resources(runtimeConfiguration().pipeline, {}, [](void *) noexcept {}, nullptr);
    const CurrentSongCacheKey oldKey{28'111U, 1U, 2U, 3U, 0U};
    auto nextKey = oldKey;
    ++nextKey.selectionToken;
    bool finished{};
    waitUntil([&] { return resources.createCache(oldKey, 1U, 4'096U, finished) && finished; },
        "retirement fixture creates its actual first cache owner");
    std::unique_ptr<SongRenderPipeline> future;
    blocked.store(false);
    blockTimedOut.store(false);
    releaseBlocked.store(false);
    fault.store(9);
    const auto cleanup = onScopeExit([] { releaseBlocked.store(true); fault.store(0); });
    require(!resources.preparePipeline(1U, 1U, 9U, future), "old preparation is queued");
    waitUntil([&] { return blocked.load(); }, "old preparation is actually held");
    resources.retireCache();
    waitUntil([&] { return resources.createCache(nextKey, 1U, 4'096U, finished) && finished; },
        "canonical retirement and new ownership do not wait for unrelated DSP construction");
    SongRenderPipeline alreadyPrepared(runtimeConfiguration().pipeline);
    require(resources.prepareLanes(0U, 1U, 0U, alreadyPrepared),
        "an already-admitted lane layout must not wait for unrelated preparation retirement");
    require(!resources.preparePipeline(1U, 1U, 9U, future) &&
        !resources.preparePipeline(1U, 2U, 2U, future) && !future && !blockTimedOut.load(),
        "retirement keeps both old and replacement preparation unavailable until old work retires");
    releaseBlocked.store(true);
    fault.store(0);
    waitUntil([&] { return resources.preparePipeline(1U, 2U, 2U, future); },
        "only the exact replacement preparation is admitted after retirement");
    require(future && future->stemLaneCount() == 2U && resources.snapshot().key == nextKey,
        "retired preparation cannot replace the new canonical identity or its own exact result");
    future.reset();
    fault.store(10);
    bool failed{};
    waitUntil([&] {
        static_cast<void>(resources.preparePipeline(1U, 3U, 0U, future, &failed));
        return failed;
    }, "allocation failure remains an explicit terminal preparation receipt");
    require(!future && resources.snapshot().key == nextKey &&
        resources.storageStatus() == CurrentSongCacheStorageStatus::Admitted,
        "DSP allocation failure must not revoke the independent canonical store admission");
}

void cacheInheritanceWaitsForHeldPreparationRetirement() {
    using namespace stemwise::audio::engine;
    using namespace stemwise::audio::engine::cache_test;
    NativePlaybackResources previous(runtimeConfiguration().pipeline, {}, [](void *) noexcept {}, nullptr);
    NativePlaybackResources destination(runtimeConfiguration().pipeline, {}, [](void *) noexcept {}, nullptr);
    const CurrentSongCacheKey key{28'121U, 1U, 2U, 3U, 0U};
    const auto source = tone(2'048U, 0U, .025F, 331.0);
    bool finished{};
    waitUntil([&] { return previous.createCache(key, 1U, 4'096U, finished) && finished; },
        "inheritance fixture creates the real canonical cache");
    waitUntil([&] { return previous.offer(key, 0U, 0U, source.data(), 2'048U).status ==
        CurrentSongCacheOfferStatus::Accepted; }, "inheritance fixture commits actual PCM");
    waitUntil([&] { return previous.seal(key, 0U, 2'048U) == CurrentSongCacheSealStatus::Accepted; },
        "inheritance fixture seals its exact Original extent");
    std::unique_ptr<SongRenderPipeline> unused;
    blocked.store(false);
    blockTimedOut.store(false);
    releaseBlocked.store(false);
    fault.store(9);
    const auto release = onScopeExit([] { releaseBlocked.store(true); fault.store(0); });
    require(!previous.preparePipeline(1U, 1U, 9U, unused), "inheritance fixture queues future preparation");
    waitUntil([&] { return blocked.load(); }, "inheritance fixture actually holds the preparation owner");
    std::atomic<bool> started{}, complete{}, inherited{};
    std::thread transfer([&] {
        started.store(true, std::memory_order_release);
        inherited.store(destination.inheritCacheFrom(previous), std::memory_order_release);
        complete.store(true, std::memory_order_release);
    });
    const auto join = onScopeExit([&] {
        releaseBlocked.store(true);
        fault.store(0);
        if (transfer.joinable()) transfer.join();
    });
    waitUntil([&] { return started.load(std::memory_order_acquire); }, "inheritance control operation starts");
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(100);
    while (!complete.load(std::memory_order_acquire) && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    require(!complete.load(std::memory_order_acquire), "cache moved before both old owners retired");
    releaseBlocked.store(true);
    fault.store(0);
    waitUntil([&] { return complete.load(std::memory_order_acquire); }, "both stopped owners retire and transfer terminates");
    transfer.join();
    std::array<float, 64U * 2U> actual{};
    require(inherited.load(std::memory_order_acquire) && !unused && !blockTimedOut.load() &&
        destination.readPcmCacheForAudit(key, 0U, 0U, actual.data(), 64U) &&
        std::equal(actual.begin(), actual.end(), source.begin()),
        "joined preparation retirement preserves the exact inherited canonical PCM");
}
