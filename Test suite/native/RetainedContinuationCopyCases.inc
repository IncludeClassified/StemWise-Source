void boundedHeadCloneKeepsOrdinarySuffixAfterCanonicalGap() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{28'204U, 9U};
    constexpr std::uint64_t headEnd = 2'048U, prefixEnd = 8'192U, bodyFrames = 16'384U, tailFrames = 1'024U;
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 17U, 19U, 0U};
    const auto prefix = tone(prefixEnd, 0U);
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted &&
        cache.offer(key, 0U, 0U, prefix.data(), prefixEnd).inputFramesConsumed == prefixEnd,
        "head clone has an actual canonical prefix covering every frame before its ordinary suffix");
    LoopHeadCache head;
    const LoopHeadContentKey headKey{17U, 19U, 0U, 29U, 1U, 0U, headEnd};
    require(head.beginStaging(headKey, 1U, LoopHeadBoundaryMode::RetainedWindow) == LoopHeadBeginStatus::Accepted &&
        head.writeStaging(1U, 0U, 0U, prefix.data(), headEnd).status == LoopHeadWriteStatus::LaneComplete &&
        head.commitStaging(1U) == LoopHeadCommitStatus::Accepted,
        "exact immutable A-B head is shorter than the independently cached prefix");
    auto configured = configuration(bodyFrames + tailFrames);
    configured.stemLaneCount = 0U;
    SongRenderPipeline live(configured), spare(configured);
    const auto body = tone(bodyFrames, prefixEnd);
    require(live.beginSong(owner, owner.generation, prefixEnd, 0U) == SongRenderResetStatus::Ok &&
        live.bindCurrentSongPcmCache(owner, &cache) &&
        live.submitControls(controls(1U)) == SongRenderControlStatus::Accepted &&
        live.appendLanePcm(SongRenderLane::Original, owner.generation, prefixEnd, body.data(), bodyFrames)
            .inputFramesAccepted == bodyFrames,
        "ordinary decoder suffix follows the canonical prefix without a source gap");
    const auto prepare = [&] {
        return spare.prepareAtomicLoopContinuationFrom(live, owner, true, 0U, headEnd, 0U, 0U, 1U, &head);
    };
    auto status = prepare();
    for (unsigned turn = 0U; status == SongRenderLoopStatus::Preparing && turn < bodyFrames / 4'096U + 1U; ++turn)
        status = prepare();
    require(status == SongRenderLoopStatus::Accepted, "bounded A head clone completes its real ordinary suffix transfer");
    const auto tail = tone(tailFrames, prefixEnd + bodyFrames);
    require(spare.appendLanePcm(SongRenderLane::Original, owner.generation, prefixEnd + bodyFrames,
        tail.data(), tailFrames).inputFramesAccepted == tailFrames,
        "bounded head adoption must preserve the accepted ordinary suffix when its first frame lies beyond headEnd");
    require(spare.clearLoop(owner) == SongRenderLoopStatus::Disabled, "Clear retains every real source interval after head cloning");
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
    std::uint64_t frame = 0U;
    while (frame < prefixEnd + bodyFrames + tailFrames) {
        const auto rendered = spare.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock);
        require(rendered.framesProduced != 0U && rendered.sourceTimeline.startFrame == static_cast<double>(frame),
            "Clear crosses the bounded head, canonical prefix and actual ordinary body without skipping source frames");
        if (frame >= prefixEnd) {
            const auto expected = tone(rendered.framesProduced, frame);
            for (std::size_t offset = 0U; offset < rendered.framesProduced; ++offset)
                for (std::size_t channel = 0U; channel < 2U; ++channel)
                    require(output[offset * kStemwiseMultibusStride + channel] == expected[offset * 2U + channel],
                        "PCM beyond canonical coverage remains the independently authored ordinary stereo source");
        }
        frame += rendered.framesProduced;
    }
}

void cancelledRetainedRefreshCannotAcceptAPartialRawBody() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{28'203U, 5U}, next{owner.selectionToken, 7U};
    constexpr std::uint64_t prefixEnd = 16'384U, first = 6'000U, bodyFrames = 16'384U;
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 11U, 22U, 0U};
    const CurrentSongCacheKey nextKey{next.selectionToken, next.generation, 11U, 22U, 0U};
    CurrentSongPcmCache cache;
    const auto prefix = tone(prefixEnd, 0U);
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted &&
        cache.offer(key, 0U, 0U, prefix.data(), prefixEnd).inputFramesConsumed == prefixEnd,
        "refresh cancellation retains a real unsealed canonical Original prefix");
    auto configured = configuration(32'768U);
    configured.stemLaneCount = 0U;
    SongRenderPipeline live(configured), spare(configured);
    require(live.beginSong(owner, owner.generation, 0U, 0U) == SongRenderResetStatus::Ok &&
        live.bindCurrentSongPcmCache(owner, &cache) &&
        live.submitControls(controls(1U)) == SongRenderControlStatus::Accepted &&
        spare.prepareAtomicLoopContinuationFrom(live, owner, true, 512U, 12'000U, 512U, 8U, 1U) ==
            SongRenderLoopStatus::Accepted, "refresh cancellation starts with the exact prepared A head");
    const auto cursor = spare.sourceTimelineFrame();
    const auto body = tone(bodyFrames, first);
    require(cache.rebindStreamingOriginalOwnership(key, nextKey) &&
        live.seek(next, next.generation, first, live.nextOutputClockFrame()) == SongRenderResetStatus::Ok &&
        live.bindCurrentSongPcmCache(next, &cache) &&
        live.appendLanePcm(SongRenderLane::Original, next.generation, first, body.data(), bodyFrames)
            .inputFramesAccepted == bodyFrames,
        "a legitimate newer owner retains its complete ordinary decoder suffix");
    const auto refresh = [&] { return spare.refreshAtomicLoopContinuationControlsFrom(live, 1U); };
    require(refresh() == SongRenderLoopStatus::Preparing &&
        spare.laneDiagnostics(SongRenderLane::Original).storedFrames == 4'096U,
        "refresh must yield after one bounded real raw-body slice");
    spare.cancelAtomicLoopContinuationPreparationFrom(live);
    auto status = refresh();
    require(status == SongRenderLoopStatus::Preparing &&
        spare.laneDiagnostics(SongRenderLane::Original).storedFrames == 4'096U,
        "same-owner retry after exact cancellation cannot accept a partial raw body");
    cache_test::readConsumerAdmissionDenials.store(0U);
    cache_test::denyReadConsumerGeneration.store(next.generation);
    for (unsigned turn = 0U; status == SongRenderLoopStatus::Preparing && turn < 8U; ++turn) {
        require(spare.ownership().selectionToken == owner.selectionToken && spare.ownership().generation == owner.generation,
            "partial refresh cannot publish the replacement owner before target reader admission");
        status = refresh();
    }
    const bool deniedWithoutCommit = status == SongRenderLoopStatus::InvalidOwnership &&
        cache_test::readConsumerAdmissionDenials.load() != 0U &&
        spare.ownership().selectionToken == owner.selectionToken && spare.ownership().generation == owner.generation;
    cache_test::denyReadConsumerGeneration.store(0U);
    require(deniedWithoutCommit, "failed target cache admission must preserve the old owner after a complete raw copy");
    status = refresh();
    require(status == SongRenderLoopStatus::Preparing &&
        spare.laneDiagnostics(SongRenderLane::Original).storedFrames == 4'096U,
        "retry after reader rejection must recopy the entire exact raw body before accepting");
    for (unsigned turn = 0U; status == SongRenderLoopStatus::Preparing && turn < 8U; ++turn) status = refresh();
    require(status == SongRenderLoopStatus::Accepted && spare.sourceTimelineFrame() == cursor &&
        spare.laneDiagnostics(SongRenderLane::Original).storedFrames == bodyFrames,
        "bounded refresh retry completes every raw frame without resetting the prepared cursor");
    std::vector<float> copied(body.size());
    const auto result = spare.copyRetainedLanePcm(next, SongRenderLane::Original, first,
        copied.data(), bodyFrames, bodyFrames);
    require(result.status == TimelineCopyStatus::Complete && result.framesCopied == bodyFrames && copied == body,
        "cancelled refresh recovery preserves every independently authored stereo PCM sample");
    const auto tail = tone(1'024U, first + bodyFrames);
    require(spare.appendLanePcm(SongRenderLane::Original, next.generation, first + bodyFrames, tail.data(), 1'024U)
        .inputFramesAccepted == 1'024U,
        "completed refresh admits the decoder's exact next contiguous quantum");
}

void retainedContinuationCopyIsBoundedAndKeepsSourceBody() {
    using namespace stemwise::audio::engine;
    constexpr std::size_t lanes = stemwise::audio::kStemwiseMusicBusCount;
    constexpr std::size_t bodyFrames = 131'072U, headFrames = 2'048U, lateFrames = 1'024U;
    constexpr SongRenderOwnership owner{28'201U, 3U};
    constexpr auto mask = (1U << lanes) - 1U;
    LoopHeadCache head;
    const LoopHeadContentKey key{17U, 19U, 23U, 29U, 1U, 0U, headFrames};
    require(head.beginStaging(key, mask, LoopHeadBoundaryMode::RetainedWindow) == LoopHeadBeginStatus::Accepted,
        "bounded copy head begins");
    for (std::size_t lane = 0U; lane < lanes; ++lane) {
        const auto pcm = tone(headFrames, 0U, .03F, 211.0 + lane * 19.0);
        require(head.writeStaging(1U, lane, 0U, pcm.data(), headFrames).status == LoopHeadWriteStatus::LaneComplete,
            "bounded copy fixture commits its independently authored head");
    }
    require(head.commitStaging(1U) == LoopHeadCommitStatus::Accepted, "bounded copy head is immutable");
    auto config = configuration(bodyFrames + lateFrames);
    config.stemLaneCount = lanes - 1U;
    SongRenderPipeline live(config), spare(config);
    require(live.beginSong(owner, owner.generation, headFrames, 0U) == SongRenderResetStatus::Ok &&
        live.submitControls(controls(1U, MixerSourceMode::Stems)) == SongRenderControlStatus::Accepted,
        "maximum-lane ordinary source starts");
    for (std::size_t lane = 0U; lane < lanes; ++lane) {
        const auto pcm = tone(bodyFrames, headFrames, .03F, 211.0 + lane * 19.0);
        require(live.appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation, headFrames,
            pcm.data(), bodyFrames).inputFramesAccepted == bodyFrames,
            "maximum supported retained body is actual source PCM");
    }
    const auto stored = [&] {
        std::size_t result{};
        for (std::size_t lane = 0U; lane < lanes; ++lane)
            result += spare.laneDiagnostics(static_cast<SongRenderLane>(lane)).storedFrames;
        return result;
    };
    const auto prepare = [&] {
        return spare.prepareAtomicLoopContinuationFrom(live, owner, true, 0U, headFrames,
            0U, 0U, mask, &head);
    };
    auto status = prepare();
    require(status == SongRenderLoopStatus::Preparing, "large transfer yields before publication");
    auto copied = stored();
    require(copied > 0U && copied <= 4'096U,
        "one continuation preparation copied more than the 4096-frame whole-turn budget");
    for (std::size_t lane = 0U; lane < lanes; ++lane) {
        const auto pcm = tone(lateFrames, headFrames + bodyFrames, .03F, 211.0 + lane * 19.0);
        require(live.appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation,
            headFrames + bodyFrames, pcm.data(), lateFrames).inputFramesAccepted == lateFrames &&
            live.markLaneEndOfStream(static_cast<SongRenderLane>(lane), owner.generation,
                headFrames + bodyFrames + lateFrames) == SongRenderEosStatus::Accepted,
            "source appends and exact EOS may arrive after the first bounded slice");
    }
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
    std::size_t activeTurns{};
    while (status != SongRenderLoopStatus::Accepted) {
        require(++activeTurns <= lanes * (bodyFrames + lateFrames) / 4'096U + 2U, "bounded retained copy must terminate");
        const auto active = live.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock);
        require(active.framesProduced == kMaximumBlock || active.status == SongRenderStatus::EndOfStream,
            "ordinary current output remains serviceable between retained-copy slices");
        status = prepare();
        require(status == SongRenderLoopStatus::Preparing || status == SongRenderLoopStatus::Accepted,
            "exact continuation remains resumable until fully prepared");
        const auto next = stored();
        require(next >= copied && next - copied <= 4'096U,
            "a resumed continuation reset/replayed data or exceeded its whole-turn copy budget");
        copied = next;
    }
    require(activeTurns > 1U && copied == lanes * (bodyFrames + lateFrames),
        "continuation copies the entire real body through multiple active-service turns");
    require(spare.clearLoop(owner) == SongRenderLoopStatus::Disabled, "completed continuation can clear normally");
    std::uint64_t frame{};
    while (frame < headFrames + bodyFrames + lateFrames) {
        const auto rendered = spare.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock);
        require(rendered.framesProduced > 0U && rendered.sourceTimeline.startFrame == static_cast<double>(frame),
            "completed transfer preserves every source frame through head and retained body");
        for (std::size_t offset = 0U; offset < rendered.framesProduced; ++offset)
            for (std::size_t lane = 0U; lane < lanes; ++lane) {
                const float expected = static_cast<float>(.03 * std::sin(2.0 * kPi * (211.0 + lane * 19.0) *
                    static_cast<double>(frame + offset) / kSampleRate));
                requireNear(output[offset * kStemwiseMultibusStride + lane * 2U], expected, 1e-6,
                    "resumable copy must preserve exact known lane content");
                requireNear(output[offset * kStemwiseMultibusStride + lane * 2U + 1U], expected * .83F, 1e-6,
                    "resumable copy must preserve both stereo channels");
            }
        frame += rendered.framesProduced;
    }
    require(spare.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock).status == SongRenderStatus::EndOfStream,
        "resumable transfer preserves exact actual EOS");
}

void retainedContinuationCancellationReleasesOnlyItsSource() {
    using namespace stemwise::audio::engine;
    constexpr SongRenderOwnership owner{28'202U, 7U};
    constexpr std::size_t headFrames = 1'024U, bodyFrames = 12'288U;
    LoopHeadCache head;
    const LoopHeadContentKey key{31U, 37U, 41U, 43U, 1U, 0U, headFrames};
    require(head.beginStaging(key, 1U, LoopHeadBoundaryMode::RetainedWindow) == LoopHeadBeginStatus::Accepted,
        "cancel fixture head begins");
    const auto headPcm = tone(headFrames, 0U);
    require(head.writeStaging(1U, 0U, 0U, headPcm.data(), headFrames).status == LoopHeadWriteStatus::LaneComplete &&
        head.commitStaging(1U) == LoopHeadCommitStatus::Accepted, "cancel fixture immutable head commits");
    auto config = configuration(bodyFrames);
    SongRenderPipeline live(config), unrelated(config), spare(config);
    const auto fill = [&](SongRenderPipeline &pipeline, SongRenderOwnership target) {
        const auto pcm = tone(bodyFrames, headFrames);
        require(pipeline.beginSong(target, target.generation, headFrames, 0U) == SongRenderResetStatus::Ok &&
            pipeline.appendLanePcm(SongRenderLane::Original, target.generation, headFrames,
                pcm.data(), bodyFrames).inputFramesAccepted == bodyFrames, "cancel fixture source fills");
    };
    fill(live, owner);
    fill(unrelated, owner);
    std::array<std::int64_t, 2U> beats{0, 512};
    std::array<std::uint8_t, 2U> accents{1U, 0U};
    require(live.setBeatGrid(owner, beats.data(), accents.data(), beats.size()), "initial exact beat grid accepts");
    const auto prepare = [&](SongRenderPipeline &source, SongRenderOwnership target) {
        return spare.prepareAtomicLoopContinuationFrom(source, target, true, 0U, headFrames, 0U, 0U, 1U, &head);
    };
    require(prepare(live, owner) == SongRenderLoopStatus::Preparing, "cancel fixture owns a partial body");
    beats[1U] = 640;
    require(live.setBeatGrid(owner, beats.data(), accents.data(), beats.size()) &&
        prepare(live, owner) == SongRenderLoopStatus::Preparing &&
        spare.laneDiagnostics(SongRenderLane::Original).storedFrames == 4'096U,
        "same-count changed beat positions restart the exact partial preparation");
    accents[1U] = 1U;
    require(live.setBeatGrid(owner, beats.data(), accents.data(), beats.size()) &&
        prepare(live, owner) == SongRenderLoopStatus::Preparing &&
        spare.laneDiagnostics(SongRenderLane::Original).storedFrames == 4'096U,
        "same-count changed beat accents restart the exact partial preparation");
    live.clearBeatGrid(owner);
    require(prepare(live, owner) == SongRenderLoopStatus::Preparing &&
        spare.laneDiagnostics(SongRenderLane::Original).storedFrames == 4'096U,
        "clearing beat metadata restarts without replaying an obsolete numerical plan");
    require(spare.beginSong({owner.selectionToken, owner.generation + 1U}, owner.generation + 1U, 0U, 0U) ==
        SongRenderResetStatus::InvalidOwnership, "replacement must cancel its exact outstanding transfer first");
    spare.cancelAtomicLoopContinuationPreparationFrom(unrelated);
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
    for (unsigned turn = 0U; turn < 24U; ++turn)
        require(live.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock).framesProduced == kMaximumBlock,
            "held source still renders real output");
    require(live.laneDiagnostics(SongRenderLane::Original).firstTimelineFrame == headFrames,
        "an unrelated cancellation cannot discard the exact transfer source");
    spare.cancelAtomicLoopContinuationPreparationFrom(live);
    require(live.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock).framesProduced == kMaximumBlock &&
        live.laneDiagnostics(SongRenderLane::Original).firstTimelineFrame > headFrames,
        "exact cancellation promptly releases the source floor");

    require(prepare(unrelated, owner) == SongRenderLoopStatus::Preparing,
        "cancelled spare admits another actual source without a permanent reservation");
    constexpr SongRenderOwnership successor{owner.selectionToken, owner.generation + 1U};
    fill(unrelated, successor);
    require(prepare(unrelated, owner) == SongRenderLoopStatus::InvalidOwnership,
        "a source reset cannot finish stale-owner continuation PCM");
    require(prepare(unrelated, successor) == SongRenderLoopStatus::Preparing,
        "source reset releases the old floor and allows a fresh exact transfer");
    spare.cancelAtomicLoopContinuationPreparationFrom(unrelated);
    require(spare.beginSong({owner.selectionToken, owner.generation + 2U}, owner.generation + 2U, 0U, 0U) ==
        SongRenderResetStatus::Ok, "cancelled destination can be replaced normally");
}
