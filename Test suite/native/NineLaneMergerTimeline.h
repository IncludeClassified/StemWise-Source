#pragma once

#include "MergerKnownAnswer.h"
#include "NativeAudioOutputMultibusCallbackStage.h"
#include "native_engine/dsp/TimePitchProcessor.h"

#include <array>
#include <bit>
#include <iostream>
#include <stdexcept>

namespace merger_timeline {
using namespace stemwise::audio;
using namespace merger_known_answer;

inline void check(bool value, const char *message) {
    if (!value) throw std::runtime_error(message);
}

inline LateStemMixControlSnapshot endpoint(std::uint64_t generation) {
    LateStemMixControlSnapshot control{};
    control.publicationSequence = generation; control.generation = generation; control.selectionToken = 29U;
    control.canonicalControlRevision = control.sourceIntentRevision = control.stemIntentRevision = 1U;
    control.sourceMode = LateStemSourceMode::Stems;
    control.musicGain = control.mixHeadroom = 1.0F;
    control.sourceRampFrames = control.laneRampFrames = control.masterRampFrames = 1U;
    for (std::size_t lane = 0U; lane < kLanes; ++lane)
        control.effectiveStemGains[lane] = static_cast<float>(lane == 8U ? 4U : lane + 1U) / 4.0F;
    return control;
}

inline RenderBusFrameTag tagFor(const LateStemMixControlSnapshot &control) {
    RenderBusFrameTag tag{};
    tag.generation = control.generation; tag.selectionToken = control.selectionToken;
    tag.payloadSequence = tag.payloadHash = 1U;
    tag.canonicalControlRevision = tag.sourceIntentRevision = tag.stemIntentRevision = 1U;
    tag.stemBusReadyMask = kStemwiseAllStemBusesReadyMask;
    tag.actualTempo = 1.0F;
    return tag;
}

// One comparison boundary is reused by the real scheduling run and isolated corruptions below.
inline bool lanesMatch(const float *payload, std::uint64_t sourceFrame) {
    for (std::size_t lane = 0U; lane < kLanes; ++lane)
        for (std::size_t channel = 0U; channel < 2U; ++channel)
            if (payload[stemwiseStemChannel(lane, channel)] != sample(sourceFrame, lane, channel))
                return false;
    return true;
}

inline void detectableFaults() {
    std::array<float, kStemwiseMultibusStride> correct{}, bad{};
    payloadFrame(correct.data(), 73U);
    check(lanesMatch(correct.data(), 73U), "fault comparator rejects correct known answer");
    payloadFrame(bad.data(), 74U);
    check(!lanesMatch(bad.data(), 73U), "one-sample shift escaped lane gate");
    for (const auto displaced : {41U, 105U}) {
        payloadFrame(bad.data(), displaced);
        check(!lanesMatch(bad.data(), 73U), "dropped/duplicated block escaped lane gate");
    }
    bad = correct;
    std::swap(bad[stemwiseStemChannel(3U, 0U)], bad[stemwiseStemChannel(3U, 1U)]);
    check(!lanesMatch(bad.data(), 73U), "L/R swap escaped lane gate");
    bad = correct;
    for (std::size_t channel = 0U; channel < 2U; ++channel)
        std::swap(bad[stemwiseStemChannel(1U, channel)], bad[stemwiseStemChannel(7U, channel)]);
    check(!lanesMatch(bad.data(), 73U), "role/lane swap escaped lane gate");
    bad = correct;
    auto &tiny = bad[stemwiseStemChannel(2U, 0U)];
    tiny = std::bit_cast<float>(std::bit_cast<std::uint32_t>(tiny) + 1U);
    check(!lanesMatch(bad.data(), 73U), "single-ULP perturbation escaped exact lane gate");
    bad = correct; bad[stemwiseStemChannel(8U, 1U)] *= 0.5F;
    check(!lanesMatch(bad.data(), 73U), "gain/Remaining corruption escaped lane gate");
}

inline void readinessArrivalOrders() {
    constexpr std::array<std::array<std::size_t, kLanes>, 3U> orders{{
        {0, 1, 2, 3, 4, 5, 6, 7, 8}, {8, 7, 6, 5, 4, 3, 2, 1, 0},
        {4, 0, 8, 2, 6, 1, 7, 3, 5}}};
    constexpr QuarterGains gains{1, 2, 3, 4, 5, 6, 7, 8, 4};
    for (const auto &order : orders) {
        SpscTaggedMultibusRingBuffer ring(4U);
        LateStemMixControlMailbox mailbox;
        NativeAudioOutputMultibusCallbackStage stage;
        const auto control = endpoint(17U);
        auto tag = tagFor(control); tag.stemBusReadyMask = 0U;
        check(mailbox.resetRole(0U, control.generation, control.selectionToken) &&
            mailbox.publish(0U, control), "readiness control publication");
        std::array<float, kStemwiseMultibusStride> input{};
        std::array<float, 2U> output{};
        std::array<FinalPcmFrameTag, 1U> finalTags{};
        for (std::size_t arrived = 0U; arrived <= kLanes; ++arrived) {
            if (arrived != 0U) tag.stemBusReadyMask |= 1U << order[arrived - 1U];
            payloadFrame(input.data(), arrived);
            check(ring.writeTaggedTimeline(input.data(), 1U, tag,
                static_cast<double>(arrived), static_cast<double>(arrived + 1U), false, &control) == 1U,
                "readiness payload write");
            const auto mixed = stage.readAndMix(0U, control.generation, ring, mailbox,
                output.data(), 1U, nullptr, {}, nullptr, finalTags.data());
            check(mixed.frameCount == 1U, "readiness callback progress");
            const bool complete = arrived == kLanes;
            check(finalTags[0].actualSourceMode ==
                (complete ? FinalPcmSourceMode::Stems : FinalPcmSourceMode::Original),
                "readiness order misreported actual source ownership");
            check(output[0] == (complete ? expected(arrived, 0U, gains) : 0.375F) &&
                output[1] == (complete ? expected(arrived, 1U, gains) : -0.25F),
                "readiness order exposed partial mix or Original substitution");
            if (!complete)
                check(output[0] != expected(arrived, 0U, gains),
                    "Original substitution escaped independent PCM gate");
        }
        const auto before = stage.callbackSnapshot().mixer.safeOriginalFallbackFrames;
        check(before == kLanes, "partial readiness fallback frames were not observed");
        payloadFrame(input.data(), kLanes + 1U);
        check(ring.writeTaggedTimeline(input.data(), 1U, tag, 10.0, 11.0, true, &control) == 1U,
            "settled readiness write");
        check(stage.readAndMix(0U, control.generation, ring, mailbox,
            output.data(), 1U, nullptr, {}, nullptr, finalTags.data()).frameCount == 1U &&
            finalTags[0].sourceTransitionSettled && finalTags[0].actualSourceMode == FinalPcmSourceMode::Stems &&
            stage.callbackSnapshot().mixer.safeOriginalFallbackFrames == before,
            "complete readiness did not settle without fallback");
    }
}

inline void run() {
    detectableFaults();
    readinessArrivalOrders();
    constexpr std::size_t capacity = 127U, maxChunk = 173U, maxCallback = 61U;
    constexpr QuarterGains gains{1, 2, 3, 4, 5, 6, 7, 8, 4};
    SpscTaggedMultibusRingBuffer ring(capacity);
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    engine::TimePitchProcessor neutral(48'000U, maxChunk, kStemwiseMusicChannelCount);
    check(neutral.setControls(1.0F, 0.0F), "neutral DSP controls rejected");
    std::array<float, maxChunk * kStemwiseMultibusStride> input{};
    std::array<float, maxChunk * kStemwiseMusicChannelCount> music{};
    std::array<float, maxCallback * 2U> output{};
    std::array<OutputTimelineFrameTag, maxCallback> timeline{};
    std::array<FinalPcmFrameTag, maxCallback> finalTags{};
    std::uint64_t total = 0U, partialWrites = 0U, wrappedReads = 0U;
    // Includes 32-bit frame-counter crossing without retaining a giant PCM capture.
    constexpr std::array<std::uint64_t, 4U> starts{0U, 126U, 127U, 4'294'967'201ULL};
    for (std::size_t segment = 0U; segment < starts.size(); ++segment) {
        ring.resetInactive(); stage.reset();
        if (segment != 0U) neutral.onSeek();
        const auto control = endpoint(segment + 17U);
        check(mailbox.resetRole(0U, control.generation, control.selectionToken), "timeline owner reset");
        check(mailbox.publish(0U, control), "timeline endpoint publication");
        auto tag = tagFor(control);
        // A stale decode generation reaches the actual callback consumer and is withheld.
        if (segment != 0U) {
            const auto invalidBefore = stage.diagnostics().invalidTagFrames;
            auto stale = tag; --stale.generation;
            payloadFrame(input.data(), starts[segment]);
            check(ring.writeTaggedTimeline(input.data(), 1U, stale,
                static_cast<double>(starts[segment]), static_cast<double>(starts[segment] + 1U)) == 1U,
                "stale fixture write");
            const auto rejected = stage.readAndMix(0U, control.generation, ring, mailbox,
                output.data(), 1U, timeline.data());
            check(rejected.frameCount == 1U && !timeline[0].valid() &&
                output[0] == 0.0F && output[1] == 0.0F &&
                stage.diagnostics().invalidTagFrames == invalidBefore + 1U,
                "stale generation escaped actual callback gate");
        }
        const std::uint64_t frames = segment == 0U ? 1'000'003U : 1'027U;
        std::uint64_t produced = 0U, consumed = 0U;
        std::size_t turn = 0U;
        while (consumed < frames) {
            check(++turn < 100'000U, "bounded timeline run made no progress");
            // Two unequal producer pushes before each callback deliberately apply backpressure.
            for (std::size_t push = 0U; push < 2U && produced < frames; ++push) {
                const auto chunk = static_cast<std::size_t>(std::min<std::uint64_t>(
                    67U + (turn * 37U + push * 19U) % 107U, frames - produced));
                for (std::size_t frame = 0U; frame < chunk; ++frame)
                    payloadFrame(input.data() + frame * kStemwiseMultibusStride, starts[segment] + produced + frame);
                for (std::size_t frame = 0U; frame < chunk; ++frame)
                    std::copy_n(input.data() + frame * kStemwiseMultibusStride, kStemwiseMusicChannelCount,
                        music.data() + frame * kStemwiseMusicChannelCount);
                check(neutral.requiredInputFrames(chunk) == chunk, "neutral DSP changed source clock");
                const auto processed = neutral.process(music.data(), chunk, music.data(), maxChunk, chunk);
                check(processed.status == engine::TimePitchProcessStatus::Ok &&
                    processed.inputFramesConsumed == chunk && processed.outputFramesProduced == chunk,
                    "neutral DSP changed canonical extent");
                for (std::size_t frame = 0U; frame < chunk; ++frame) {
                    const auto *payload = music.data() + frame * kStemwiseMusicChannelCount;
                    check(lanesMatch(payload, starts[segment] + produced + frame) &&
                        payload[stemwiseOriginalChannel(0U)] == 0.375F &&
                        payload[stemwiseOriginalChannel(1U)] == -0.25F,
                        "neutral DSP changed lane/channel/sample coordinates");
                    // The callback fixture supplies the separate auxiliary pass's unity envelope.
                    std::copy_n(payload, kStemwiseMusicChannelCount,
                        input.data() + frame * kStemwiseMultibusStride);
                }
                const auto accepted = ring.writeTaggedTimeline(input.data(), chunk, tag,
                    static_cast<double>(starts[segment] + produced),
                    static_cast<double>(starts[segment] + produced + chunk), produced + chunk == frames, &control);
                if (accepted < chunk) ++partialWrites;
                produced += accepted;
            }
            const std::size_t callback = 1U + (turn * 29U) % maxCallback;
            const auto lease = ring.acquireRead(callback);
            if (lease.spanCount == 2U) ++wrappedReads;
            std::size_t visited = 0U;
            for (std::size_t span = 0U; span < lease.spanCount; ++span)
                for (std::size_t frame = 0U; frame < lease.spans[span].frameCount; ++frame) {
                    check(lanesMatch(lease.spans[span].interleavedPayload + frame * kStemwiseMultibusStride,
                        starts[segment] + consumed + visited), "canonical lane/channel/frame changed before callback");
                    ++visited;
                }
            const auto mixed = stage.readAndMix(0U, control.generation, ring, mailbox,
                output.data(), callback, timeline.data(), {}, nullptr, finalTags.data());
            check(mixed.frameCount == visited && mixed.frameCount != 0U &&
                mixed.continuityViolationCount == 0U, "callback sequence/continuity mismatch");
            for (std::size_t frame = 0U; frame < mixed.frameCount; ++frame) {
                const auto position = starts[segment] + consumed + frame;
                check(timeline[frame].valid() && timeline[frame].timelineFrame == static_cast<std::int64_t>(position),
                    "timeline shift/drop/duplicate/reordering");
                check(finalTags[frame].actualSourceMode == FinalPcmSourceMode::Stems &&
                    finalTags[frame].sourceTransitionSettled, "asserted frame lacks actual complete-Stems ownership");
                for (std::size_t channel = 0U; channel < 2U; ++channel)
                    check(output[frame * 2U + channel] == expected(position, channel, gains),
                        "scheduled production PCM differs from uninterrupted mathematical sequence");
                const bool final = consumed + frame + 1U == frames;
                check((finalTags[frame].terminalWriteBoundary != 0U) == final,
                    "EOF marker missing, early or duplicated");
            }
            consumed += mixed.frameCount;
        }
        check(produced == frames && ring.queuedFrames() == 0U, "final partial block extent");
        const auto snapshot = stage.callbackSnapshot();
        check(snapshot.mixer.safeOriginalFallbackFrames == 0U && snapshot.mixer.limiterActiveFrames == 0U,
            "timeline fixture crossed fallback or limiter");
        check(stage.readAndMix(0U, control.generation, ring, mailbox, output.data(), maxCallback).frameCount == 0U,
            "EOF invented extra PCM");
        total += consumed;
    }
    check(partialWrites != 0U && wrappedReads != 0U, "fixture missed backpressure/ring wrap");
    check(neutral.diagnostics().exactBypass && neutral.diagnostics().primeCount == 0U,
        "timeline escaped neutral direct DSP contract");
    std::cout << "Nine-distinct-lane neutral merger timeline passed: frames=" << total
              << ", partial writes=" << partialWrites << ", wrapped reads=" << wrappedReads
              << ", reset/seek starts=0,126,127,4294967201; stale generations rejected; exact EOF.\n";
}
}  // namespace merger_timeline
