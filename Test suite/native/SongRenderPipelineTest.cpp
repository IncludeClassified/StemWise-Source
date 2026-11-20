#include "AllocationProbeAttributes.h"
#include "SongRenderPipeline.h"
#include "CurrentSongPcmCache.h"
#include "CurrentSongPcmCacheTestHooks.h"
#include "LateStemMixer.h"
#include "NativeAudioOutputFinalDeviceStage.h"
#include "RenderPayloadFingerprint.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace allocation_probe {

std::atomic<bool> enabled{false};
std::atomic<std::uint64_t> count{0};

}  // namespace allocation_probe

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (allocation_probe::enabled.load(std::memory_order_relaxed)) {
        allocation_probe::count.fetch_add(1, std::memory_order_relaxed);
    }
    if (void *allocation = std::malloc(size)) return allocation;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    return ::operator new(size);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *allocation, std::size_t) noexcept {
    std::free(allocation);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *allocation, std::size_t) noexcept {
    std::free(allocation);
}

namespace stemwise::audio::engine::cache_test {
std::size_t beforeIo(bool, std::size_t requested) noexcept { return requested; }
std::uint64_t availableStorageBytes() noexcept { return 64ULL * 1024ULL * 1024ULL * 1024ULL; }
void beforePrepareLanes() {}
void beforeRetireCache() noexcept {}
void beforeInheritCache() noexcept {}
}

namespace {

using stemwise::audio::MetronomeMode;
using stemwise::audio::MixerSourceMode;
using stemwise::audio::LateStemMixControlSnapshot;
using stemwise::audio::LateStemMixer;
using stemwise::audio::LateStemSourceMode;
using stemwise::audio::NativeAudioOutputFinalDeviceStage;
constexpr std::uint32_t kStemwiseAllStemBusesReadyMask = 0x3FU;
using stemwise::audio::kStemwiseMultibusClickChannel;
using stemwise::audio::kStemwiseMultibusEnvelopeChannel;
using stemwise::audio::kStemwiseMultibusStride;
using stemwise::audio::stemwiseOriginalChannel;
using stemwise::audio::stemwiseStemChannel;
using stemwise::audio::engine::CurrentSongCacheBeginStatus;
using stemwise::audio::engine::CurrentSongCacheKey;
using stemwise::audio::engine::CurrentSongCacheOfferStatus;
using stemwise::audio::engine::CurrentSongCacheSealStatus;
using stemwise::audio::engine::CurrentSongPcmCache;
using stemwise::audio::engine::CurrentSongCacheStorageStatus;
using stemwise::audio::engine::CanonicalPcmStorageBudget;
using stemwise::audio::engine::RenderPayloadFingerprint;
using stemwise::audio::engine::LoopCycleDirective;
using stemwise::audio::engine::LoopCycleDirectiveAction;
using stemwise::audio::engine::LoopHeadBeginStatus;
using stemwise::audio::engine::LoopHeadBoundaryMode;
using stemwise::audio::engine::LoopHeadCache;
using stemwise::audio::engine::LoopHeadCacheSnapshot;
using stemwise::audio::engine::LoopHeadCommitStatus;
using stemwise::audio::engine::LoopHeadContentKey;
using stemwise::audio::engine::LoopHeadSealStatus;
using stemwise::audio::engine::LoopHeadWriteStatus;
using stemwise::audio::engine::SongRenderControlSnapshot;
using stemwise::audio::engine::SongRenderControlStatus;
using stemwise::audio::engine::SongRenderEosStatus;
using stemwise::audio::engine::SongRenderLane;
using stemwise::audio::engine::SongRenderLoopStatus;
using stemwise::audio::engine::SongRenderLoopDirectiveStatus;
using stemwise::audio::engine::SongRenderOwnership;
using stemwise::audio::engine::SongRenderPipeline;
using stemwise::audio::engine::SongRenderPipelineConfiguration;
using stemwise::audio::engine::SongRenderResetStatus;
using stemwise::audio::engine::SongRenderResult;
using stemwise::audio::engine::SongRenderStatus;
using stemwise::audio::engine::SongRenderStemGainPreviewStatus;
using stemwise::audio::engine::TimelineAppendStatus;
// Existing synchronization/loop fixtures author Original and exactly six parts.
constexpr std::size_t kSongRenderLaneCount = 7U;

constexpr std::uint32_t kSampleRate = 48'000;
constexpr std::uint32_t kMaximumBlock = 256;
constexpr std::uint64_t kFnvOffset = 14'695'981'039'346'656'037ULL;
constexpr std::uint64_t kFnvPrime = 1'099'511'628'211ULL;
constexpr double kPi = 3.14159265358979323846;

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

void requireNear(double actual, double expected, double tolerance, const char *message) {
    if (!std::isfinite(actual) || std::abs(actual - expected) > tolerance) {
        throw std::runtime_error(message);
    }
}

SongRenderPipelineConfiguration configuration(
    std::size_t reservoirFrames = 16'384,
    std::uint32_t transitionFrames = 512) {
    SongRenderPipelineConfiguration result;
    result.stemLaneCount = 6U;
    result.diagnosticPayloadFingerprints = true;
    result.sampleRateHz = kSampleRate;
    result.maximumBlockFrames = kMaximumBlock;
    result.laneReservoirCapacityFrames = reservoirFrames;
    result.tuneTransitionFrames = transitionFrames;
    result.maximumBeatGridPoints = 512;
    return result;
}

SongRenderControlSnapshot controls(
    std::uint64_t revision,
    MixerSourceMode source = MixerSourceMode::Original,
    float tempo = 1.0F,
    float pitch = 0.0F) {
    SongRenderControlSnapshot result;
    result.revision = revision;
    result.applicationEpoch = revision;
    result.sourceIntentRevision = revision;
    result.stemIntentRevision = revision;
    result.mixer.revision = 99'999;
    result.mixer.sourceMode = source;
    result.mixer.musicGain = 1.0F;
    result.mixer.mixHeadroom = 1.0F;
    result.mixer.sourceTransitionFrames = 1;
    result.mixer.laneRampFrames = 1;
    result.mixer.masterRampFrames = 1;
    result.mixer.defaultDeclickFrames = 32;
    result.mixer.metronome.mode = MetronomeMode::Off;
    result.tempo = tempo;
    result.pitchSemitones = pitch;
    return result;
}

std::vector<float> tone(
    std::size_t frames,
    std::uint64_t firstFrame,
    float amplitude = 0.08F,
    double frequency = 337.0) {
    std::vector<float> result(frames * 2U);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        const double phase = 2.0 * kPi * frequency *
            static_cast<double>(firstFrame + frame) / kSampleRate;
        const float sample = amplitude * static_cast<float>(std::sin(phase));
        result[frame * 2U] = sample;
        result[frame * 2U + 1U] = sample * 0.83F;
    }
    return result;
}

std::vector<float> constantPcm(std::size_t frames, float value) {
    return std::vector<float>(frames * 2U, value);
}

float provenanceSample(
    std::uint64_t timelineFrame,
    std::size_t laneIndex,
    std::size_t channel,
    float domainBase) noexcept {
    const std::uint64_t code =
        timelineFrame % 31U + laneIndex * 37U + channel * 7U;
    return domainBase + static_cast<float>(code) / 65'536.0F;
}

std::vector<float> provenancePcm(
    std::size_t frames,
    std::uint64_t firstTimelineFrame,
    std::size_t laneIndex,
    float domainBase) {
    std::vector<float> result(frames * 2U);
    for (std::size_t frame = 0U; frame < frames; ++frame) {
        for (std::size_t channel = 0U; channel < 2U; ++channel) {
            result[frame * 2U + channel] = provenanceSample(
                firstTimelineFrame + frame,
                laneIndex,
                channel,
                domainBase);
        }
    }
    return result;
}

LoopHeadContentKey loopKey(
    std::uint64_t revision,
    std::uint64_t start) noexcept {
    return {
        .songIdentityHash = 0x1000U + revision,
        .originalContentHash = 0x2000U + revision,
        .stemPackageHash = 0U,
        .contentRevision = 1U,
        .loopRevision = revision,
        .startTimelineFrame = start,
        .endTimelineFrameExclusive = start + LoopHeadCache::kCapacityFrames,
    };
}

LoopHeadCacheSnapshot prepareOriginalLoopCacheFromPcm(
    LoopHeadCache &cache,
    const LoopHeadContentKey &key,
    const std::vector<float> &pcm) {
    const std::size_t frames = pcm.size() / 2U;
    require(frames != 0U && frames <= LoopHeadCache::kCapacityFrames && pcm.size() == frames * 2U,
        "cache PCM has exact bounded decoded length");
    require(cache.beginStaging(key, 1U) == LoopHeadBeginStatus::Accepted, "cache begin");
    const auto write = cache.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame,
        pcm.data(),
        frames);
    if (frames < LoopHeadCache::kCapacityFrames) {
        require(write.status == LoopHeadWriteStatus::Accepted, "short Original awaits actual EOS");
        require(cache.sealStagingLaneWithSilence(key.loopRevision, 0U,
                    key.startTimelineFrame + frames) == LoopHeadSealStatus::Accepted,
            "cache actual Original EOS");
    } else {
        require(write.status == LoopHeadWriteStatus::LaneComplete, "cache Original runway complete");
    }
    require(write.framesWritten == frames, "cache exact frame write");
    require(
        cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted,
        "cache commit");
    auto view = cache.acquireActive(key);
    require(view.valid(), "cache active view");
    return view.snapshot();
}

LoopHeadCacheSnapshot prepareOriginalLoopCache(
    LoopHeadCache &cache,
    const LoopHeadContentKey &key,
    std::uint64_t decodedFrames,
    float value = 0.07F) {
    const std::size_t frames = static_cast<std::size_t>(
        std::min<std::uint64_t>(
            decodedFrames, LoopHeadCache::kCapacityFrames));
    return prepareOriginalLoopCacheFromPcm(cache, key, constantPcm(frames, value));
}

LoopHeadCacheSnapshot prepareAllLaneLoopCache(
    LoopHeadCache &cache,
    const LoopHeadContentKey &key,
    std::uint64_t decodedFrames,
    float domainBase) {
    constexpr std::uint32_t allLaneMask =
        (1U << static_cast<std::uint32_t>(kSongRenderLaneCount)) - 1U;
    const std::size_t frames = static_cast<std::size_t>(
        std::min<std::uint64_t>(
            decodedFrames, LoopHeadCache::kCapacityFrames));
    require(
        cache.beginStaging(key, allLaneMask) == LoopHeadBeginStatus::Accepted,
        "all-lane cache begin");
    for (std::size_t lane = 0U; lane < kSongRenderLaneCount; ++lane) {
        const auto pcm = provenancePcm(
            frames,
            key.startTimelineFrame,
            lane,
            domainBase);
        const auto write = cache.writeStaging(
            key.loopRevision,
            lane,
            key.startTimelineFrame,
            pcm.data(),
            frames);
        if (lane == 0U && frames < LoopHeadCache::kCapacityFrames) {
            require(write.status == LoopHeadWriteStatus::Accepted, "short all-lane Original awaits EOS");
            require(cache.sealStagingLaneWithSilence(key.loopRevision, lane,
                        key.startTimelineFrame + frames) == LoopHeadSealStatus::Accepted,
                "all-lane cache actual Original EOS");
        } else {
            require(write.status == LoopHeadWriteStatus::LaneComplete,
                "all-lane cache lane complete");
        }
        require(write.framesWritten == frames, "all-lane cache exact frame write");
    }
    require(
        cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted,
        "all-lane cache commit");
    auto view = cache.acquireActive(key);
    require(view.valid(), "all-lane cache active view");
    require(view.snapshot().requiredLaneMask == allLaneMask,
        "all-lane cache retains exact required mask");
    return view.snapshot();
}

LoopHeadCacheSnapshot prepareShortOriginalEosLoopCache(
    LoopHeadCache &cache,
    const LoopHeadContentKey &key,
    std::size_t decodedFrames,
    float value = 0.07F) {
    require(decodedFrames != 0U, "short Original EOS is nonempty");
    require(
        decodedFrames < LoopHeadCache::kCapacityFrames,
        "short Original EOS fits cache-only runway");
    require(
        key.endTimelineFrameExclusive - key.startTimelineFrame ==
            LoopHeadCache::kCapacityFrames,
        "Original-EOS key describes the bounded preparation runway");
    const auto pcm = constantPcm(decodedFrames, value);
    require(
        cache.beginStaging(key, 1U, LoopHeadBoundaryMode::OriginalEos) ==
            LoopHeadBeginStatus::Accepted,
        "Original-EOS cache begin");
    const auto write = cache.writeStaging(
        key.loopRevision,
        0U,
        key.startTimelineFrame,
        pcm.data(),
        decodedFrames);
    require(write.status == LoopHeadWriteStatus::Accepted, "Original-EOS partial head write");
    require(write.framesWritten == decodedFrames, "Original-EOS exact decoded head write");
    require(
        cache.sealStagingLaneWithSilence(
            key.loopRevision,
            0U,
            key.startTimelineFrame + decodedFrames) == LoopHeadSealStatus::Accepted,
        "Original-EOS exact boundary seal");
    require(
        cache.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted,
        "Original-EOS cache commit");
    auto view = cache.acquireActive(key);
    require(view.valid(), "Original-EOS active view");
    require(view.snapshot().originalEndResolved, "Original-EOS boundary resolved");
    require(
        view.snapshot().resolvedOriginalEndTimelineFrameExclusive ==
            key.startTimelineFrame + decodedFrames,
        "Original-EOS resolved boundary exact");
    return view.snapshot();
}

LoopCycleDirective loopDirective(
    LoopCycleDirectiveAction action,
    std::uint64_t sequence,
    std::uint64_t cycle,
    SongRenderOwnership owner,
    std::uint64_t ingressEpoch,
    const LoopHeadCacheSnapshot &cache,
    std::uint64_t originalEnd = 0U) noexcept {
    return {
        .valid = true,
        .sequence = sequence,
        .action = action,
        .loopRevision = cache.key.loopRevision,
        .cacheRevision = cache.key.loopRevision,
        .cycle = cycle,
        .owner = {
            .selectionToken = owner.selectionToken,
            .renderGeneration = owner.generation,
        },
        .ingressEpoch = ingressEpoch,
        .startTimelineFrame = cache.key.startTimelineFrame,
        .endTimelineFrameExclusive = originalEnd != 0U ? originalEnd :
            (cache.originalEndResolved ? cache.resolvedOriginalEndTimelineFrameExclusive :
                cache.key.endTimelineFrameExclusive),
        .headEndTimelineFrameExclusive = cache.key.startTimelineFrame + cache.frameCount,
        .cacheChecksum = cache.combinedChecksum,
        .requiredLaneMask = cache.requiredLaneMask,
        .declickFrames = 1U,
        .boundaryMode = LoopHeadBoundaryMode::OriginalEos,
    };
}

std::int16_t quantize(float sample) noexcept {
    const float finite = std::isfinite(sample) ? sample : 0.0F;
    const float clamped = std::clamp(finite, -1.0F, 1.0F);
    const float scaled = clamped * 32'767.0F;
    const std::int32_t rounded = scaled >= 0.0F
        ? static_cast<std::int32_t>(scaled + 0.5F)
        : static_cast<std::int32_t>(scaled - 0.5F);
    return static_cast<std::int16_t>(rounded);
}

void hashValue(std::uint64_t &hash, std::int16_t value) noexcept {
    const std::uint16_t bits = static_cast<std::uint16_t>(value);
    hash ^= static_cast<std::uint8_t>(bits & 0xFFU);
    hash *= kFnvPrime;
    hash ^= static_cast<std::uint8_t>((bits >> 8U) & 0xFFU);
    hash *= kFnvPrime;
}

/** Test-only closure of one production render-bus block through the callback-owned final mixer. */
[[nodiscard]] SongRenderResult renderFinal(
    SongRenderPipeline &pipeline,
    SongRenderOwnership owner,
    float *interleavedStereo,
    std::uint32_t outputCapacityFrames,
    std::uint32_t requestedFrames) noexcept {
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> payload{};
    SongRenderResult result = pipeline.renderMultibus(
        owner,
        payload.data(),
        outputCapacityFrames,
        requestedFrames);
    if (result.status != SongRenderStatus::Rendered &&
        result.status != SongRenderStatus::RenderedTuneDeferred) {
        return result;
    }

    const auto &endpoint = result.lateMixEndpoint;
    LateStemMixControlSnapshot control{
        .publicationSequence = result.payloadFingerprint.sequence,
        .generation = owner.generation,
        .selectionToken = owner.selectionToken,
        .canonicalControlRevision = endpoint.canonicalControlRevision,
        .sourceIntentRevision = endpoint.sourceIntentRevision,
        .stemIntentRevision = endpoint.stemIntentRevision,
        .stemGainPreviewPublicationSequence = endpoint.previewPublicationSequence,
        .stemGainPreviewCoversOfferSequence = endpoint.previewCoversOfferSequence,
        .stemGainPreviewActiveMask = endpoint.previewActiveMask,
        .sourceMode = endpoint.sourceMode == MixerSourceMode::Stems
            ? LateStemSourceMode::Stems
            : LateStemSourceMode::Original,
        .requiredStemMask = endpoint.requiredStemMask,
        .effectiveStemGains = endpoint.effectiveStemGains,
        .musicGain = endpoint.musicGain,
        .mixHeadroom = endpoint.mixHeadroom,
        .sourceRampFrames = endpoint.sourceRampFrames,
        .laneRampFrames = endpoint.laneRampFrames,
        .masterRampFrames = endpoint.masterRampFrames,
    };
    if (endpoint.previewPublicationSequence != 0U) {
        for (std::size_t lane = 0U; lane < endpoint.previewGains.size(); ++lane) {
            control.stemGainPreviewGainBits[lane] =
                std::bit_cast<std::uint32_t>(endpoint.previewGains[lane]);
        }
    }
    LateStemMixer mixer(kSampleRate);
    NativeAudioOutputFinalDeviceStage finalStage;
    if (!mixer.applyControl(control, result.stemBusReadyMask) ||
        !mixer.mix(
            payload.data(),
            result.framesProduced,
            result.stemBusReadyMask,
            interleavedStereo)) {
        result.status = SongRenderStatus::MixerFailure;
        result.framesProduced = 0U;
        return result;
    }
    finalStage.finalize(interleavedStereo, result.framesProduced);
    // The returned hash is the production multibus fingerprint. Reference stereo is owned
    // by this helper and must never replace production evidence.
    std::uint64_t payloadHash = kFnvOffset;
    for (std::size_t sample = 0U; sample < result.framesProduced * kStemwiseMultibusStride; ++sample) {
        hashValue(payloadHash, quantize(payload[sample]));
    }
    require(result.payloadFingerprint.hash == (payloadHash == 0U ? kFnvOffset : payloadHash),
        "production payload fingerprint survives reference final mixing");
    return result;
}

struct PcmLevel final {
    double rms{0.0};
    float peak{0.0F};
};

PcmLevel pcmLevel(const float *pcm, std::uint32_t frames) noexcept {
    if (pcm == nullptr || frames == 0U) return {};
    double sumSquares = 0.0;
    float peak = 0.0F;
    const std::size_t sampleCount = static_cast<std::size_t>(frames) * 2U;
    for (std::size_t sample = 0U; sample < sampleCount; ++sample) {
        const float value = pcm[sample];
        peak = std::max(peak, std::abs(value));
        sumSquares += static_cast<double>(value) * static_cast<double>(value);
    }
    return {
        .rms = std::sqrt(sumSquares / static_cast<double>(sampleCount)),
        .peak = peak,
    };
}

void verifyFinalBlock(
    const SongRenderResult &result,
    const float *pcm,
    SongRenderOwnership owner,
    std::uint64_t &previousFingerprintSequence) {
    if (result.status != SongRenderStatus::Rendered &&
        result.status != SongRenderStatus::RenderedTuneDeferred) {
        std::cerr << "unexpected render status=" << static_cast<int>(result.status)
                  << " requested=" << result.framesRequested
                  << " source=" << result.sourceTimeline.startFrame << '\n';
        throw std::runtime_error("rendered block status");
    }
    require(result.framesProduced != 0, "rendered block is nonempty");
    require(result.ownership.selectionToken == owner.selectionToken, "result token");
    require(result.ownership.generation == owner.generation, "result generation");
    const RenderPayloadFingerprint &tag = result.payloadFingerprint;
    require(tag.sequence > previousFingerprintSequence, "fingerprint sequence monotonic");
    previousFingerprintSequence = tag.sequence;
    require(tag.selectionToken == owner.selectionToken, "fingerprint token");
    require(tag.generation == owner.generation, "fingerprint generation");
    require(tag.frameCount == result.framesProduced, "fingerprint frame count");
    require(tag.outputClockStartFrame == result.outputClockStartFrame, "fingerprint clock");
    require(tag.hash != 0, "fingerprint nonzero");

    for (std::size_t sample = 0;
         sample < static_cast<std::size_t>(result.framesProduced) * 2U;
         ++sample) {
        require(std::isfinite(pcm[sample]), "final sample finite");
        require(std::abs(pcm[sample]) <= 1.000001F, "final sample unclipped");
    }
}

void appendChunked(
    SongRenderPipeline &pipeline,
    SongRenderLane lane,
    std::uint64_t generation,
    std::uint64_t firstFrame,
    const std::vector<float> &pcm,
    const std::array<std::size_t, 8> &chunks) {
    const std::size_t totalFrames = pcm.size() / 2U;
    std::size_t offset = 0;
    std::size_t chunkIndex = 0;
    while (offset < totalFrames) {
        const std::size_t requested = std::min(
            chunks[chunkIndex % chunks.size()],
            totalFrames - offset);
        const auto appended = pipeline.appendLanePcm(
            lane,
            generation,
            firstFrame + offset,
            pcm.data() + offset * 2U,
            requested);
        require(appended.status == TimelineAppendStatus::Accepted, "chunk append accepted");
        require(appended.inputFramesAccepted == requested, "chunk append frame count");
        offset += requested;
        ++chunkIndex;
    }
}

void appendAllLanes(
    SongRenderPipeline &pipeline,
    std::uint64_t generation,
    std::uint64_t firstFrame,
    const std::vector<float> &original,
    std::size_t stemFrames = std::numeric_limits<std::size_t>::max(),
    std::size_t omittedStem = kSongRenderLaneCount) {
    const std::array<std::size_t, 8> chunks{1, 17, 233, 4, 64, 255, 31, 127};
    appendChunked(
        pipeline,
        SongRenderLane::Original,
        generation,
        firstFrame,
        original,
        chunks);
    const std::size_t requestedStemFrames = std::min(stemFrames, original.size() / 2U);
    std::vector<float> stem(original.begin(), original.begin() +
        static_cast<std::ptrdiff_t>(requestedStemFrames * 2U));
    for (float &sample : stem) sample /= 6.0F;
    for (std::size_t index = 1; index < kSongRenderLaneCount; ++index) {
        if (index == omittedStem) continue;
        appendChunked(
            pipeline,
            static_cast<SongRenderLane>(index),
            generation,
            firstFrame,
            stem,
            chunks);
    }
}

void bindLoopFixture(CurrentSongPcmCache &cache, SongRenderPipeline &pipeline,
    SongRenderOwnership owner, std::uint64_t firstFrame, const std::vector<float> &original,
    std::size_t stemCount = 6U, bool divideStems = true) {
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 17U, 29U, 43U};
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "loop fixture cache begins");
    const std::vector<float> prefix(firstFrame * 2U, 0.0F);
    for (std::size_t lane = 0U; lane <= stemCount; ++lane) {
        if (firstFrame != 0U) require(cache.offer(key, lane, 0U, prefix.data(), firstFrame).framesStored == firstFrame,
            "loop fixture prefix publishes");
        auto input = original;
        if (lane != 0U && divideStems) for (auto &sample : input) sample /= 6.0F;
        const auto frames = input.size() / 2U;
        require(cache.offer(key, lane, firstFrame, input.data(), frames).framesStored == frames &&
            cache.sealLane(key, lane, firstFrame + frames) == CurrentSongCacheSealStatus::Accepted,
            "loop fixture commits actual source PCM");
    }
    require(pipeline.bindCurrentSongPcmCache(owner, &cache), "loop fixture binds exact owner");
}

void playbackWindowMatchesPhysicalCutWithoutChangingOriginalEof() {
    constexpr std::uint64_t start = 1'003U, fullEnd = 12'037U;
    for (const auto mode : {MixerSourceMode::Original, MixerSourceMode::Stems}) {
        for (const float tempo : {1.0F, 0.75F, 1.5F}) {
            for (const std::uint64_t trimEnd : {1'051U, 11'001U, 13'001U}) {
              for (const bool publishEof : {false, true}) {
                if (!publishEof && trimEnd > fullEnd) continue;
                const auto end = std::min(trimEnd, fullEnd);
                SongRenderPipeline actual(configuration(32'768U)), reference(configuration(32'768U));
                const SongRenderOwnership owner{9U, 1U};
                require(actual.beginSong(owner, 1U, start, 0, trimEnd) == SongRenderResetStatus::Ok &&
                    reference.beginSong(owner, 1U, start, 0) == SongRenderResetStatus::Ok, "window begins at exact source start");
                require(actual.submitControls(controls(1U, mode, tempo)) == SongRenderControlStatus::Accepted &&
                    reference.submitControls(controls(1U, mode, tempo)) == SongRenderControlStatus::Accepted,
                    "window controls use production DSP");
                for (std::size_t lane = 0U; lane < kSongRenderLaneCount; ++lane) {
                    auto pcm = tone(fullEnd - start, start, 0.03F + static_cast<float>(lane) * 0.01F,
                        251.0 + static_cast<double>(lane) * 53.0);
                    std::fill(pcm.begin() + (end - start) * 2U, pcm.end(), 0.7F);
                    require(actual.appendLanePcm(static_cast<SongRenderLane>(lane), 1U, start,
                        pcm.data(), fullEnd - start).inputFramesAccepted == fullEnd - start &&
                        reference.appendLanePcm(static_cast<SongRenderLane>(lane), 1U, start,
                        pcm.data(), end - start).inputFramesAccepted == end - start, "window retains complete source bytes");
                    require(reference.markLaneEndOfStream(static_cast<SongRenderLane>(lane), 1U, end) ==
                        SongRenderEosStatus::Accepted, "independent reference has actual cut EOF");
                    if (publishEof) require(actual.markLaneEndOfStream(static_cast<SongRenderLane>(lane), 1U, fullEnd) ==
                        SongRenderEosStatus::Accepted, "full physical EOF remains intact");
                }
                std::array<float, kMaximumBlock * kStemwiseMultibusStride> a{}, b{};
                std::uint64_t outputFrames = 0U;
                double cursor = static_cast<double>(start);
                bool terminal = false, heardSignal = false;
                for (std::size_t attempt = 0U; attempt < 2'000U; ++attempt) {
                    const auto actualBlock = actual.renderMultibus(owner, a.data(), kMaximumBlock, 193U);
                    const auto referenceBlock = reference.renderMultibus(owner, b.data(), kMaximumBlock, 193U);
                    require(actualBlock.status == referenceBlock.status && actualBlock.framesProduced == referenceBlock.framesProduced,
                        "window and physical cut render the same DSP blocks");
                    if (actualBlock.status == SongRenderStatus::PreparingTune) continue;
                    if (actualBlock.status == SongRenderStatus::EndOfStream) { terminal = true; break; }
                    require(actualBlock.status == SongRenderStatus::Rendered && actualBlock.framesProduced != 0U,
                        "trim does not wait for hidden suffix or loop");
                    require(actualBlock.sourceTimeline.startFrame == cursor && actualBlock.sourceTimeline.endFrameExclusive <= end &&
                        !actualBlock.loopEnabled && !actualBlock.loopWrappedBeforeBlock, "absolute source range never wraps or exceeds trim");
                    for (std::size_t sample = 0U; sample < actualBlock.framesProduced * kStemwiseMultibusStride; ++sample)
                        requireNear(a[sample], b[sample], 1.0e-7, "suffix cannot enter neutral or tuned PCM");
                    for (std::size_t frame = 0U; frame < actualBlock.framesProduced; ++frame) {
                        const auto channel = mode == MixerSourceMode::Original ? stemwiseOriginalChannel(0U) : stemwiseStemChannel(0U, 0U);
                        heardSignal = heardSignal || std::abs(a[frame * kStemwiseMultibusStride + channel]) > 0.001F;
                        if (tempo == 1.0F) {
                            const double frequency = mode == MixerSourceMode::Original ? 251.0 : 304.0;
                            const float amplitude = mode == MixerSourceMode::Original ? 0.03F : 0.04F;
                            const auto expected = amplitude * static_cast<float>(std::sin(2.0 * kPi * frequency *
                                (cursor + frame) / kSampleRate));
                            requireNear(a[frame * kStemwiseMultibusStride + channel], expected, 1.0e-7,
                                "actual PCM starts at retained source sample");
                        }
                    }
                    cursor = actualBlock.sourceTimeline.endFrameExclusive;
                    outputFrames += actualBlock.framesProduced;
                    require(actualBlock.endOfStreamAfterBlock == (cursor == end), "trim publishes exact natural terminal block");
                }
                require(terminal && heardSignal && cursor == end && outputFrames ==
                    static_cast<std::uint64_t>(std::ceil(static_cast<double>(end - start) / tempo)),
                    "trim emits exact tempo-adjusted cut and natural EOS");
                const auto eof = actual.laneEndSnapshot(SongRenderLane::Original);
                require(eof.marked == publishEof && (!publishEof || eof.endTimelineFrameExclusive == fullEnd),
                    "trim never forges or truncates decoder EOF");
                require(actual.beginSong({10U, 2U}, 2U, end, 0, end) == SongRenderResetStatus::InvalidTimeline,
                    "empty window rejects without replacing valid owner");
                require(actual.ownership().selectionToken == owner.selectionToken, "rejected window preserves owner");
              }
            }
        }
    }
}

void directArbitraryChunkingFingerprintsAndExplicitEos() {
    SongRenderPipeline pipeline(configuration());
    const SongRenderOwnership owner{11, 1};
    require(pipeline.beginSong(owner, (owner).generation, 0, 0) == SongRenderResetStatus::Ok, "begin direct");
    require(
        pipeline.submitControls(controls(1)) == SongRenderControlStatus::Accepted,
        "direct controls");

    constexpr std::size_t frames = 1'037;
    const auto original = tone(frames, 0);
    const std::array<std::size_t, 8> inputChunks{1, 3, 251, 17, 64, 2, 127, 255};
    appendChunked(
        pipeline,
        SongRenderLane::Original,
        owner.generation,
        0,
        original,
        inputChunks);
    require(
        pipeline.markLaneEndOfStream(
            SongRenderLane::Original,
            owner.generation,
            frames) == SongRenderEosStatus::Accepted,
        "direct eos marker");

    std::array<float, kMaximumBlock * 2U> output{};
    const std::array<std::uint32_t, 9> renderChunks{1, 7, 256, 31, 2, 199, 64, 255, 17};
    std::uint64_t fingerprintSequence = 0;
    std::uint64_t rendered = 0;
    std::size_t index = 0;
    while (rendered < frames) {
        const auto result = renderFinal(pipeline,
            owner,
            output.data(),
            kMaximumBlock,
            renderChunks[index % renderChunks.size()]);
        verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
        requireNear(
            result.sourceTimeline.startFrame,
            static_cast<double>(rendered),
            1.0e-10,
            "direct source start");
        rendered += result.framesProduced;
        requireNear(
            result.sourceTimeline.endFrameExclusive,
            static_cast<double>(rendered),
            1.0e-10,
            "direct source end");
        ++index;
    }
    require(rendered == frames, "direct eos exact frame");
    const auto eos = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
    require(eos.status == SongRenderStatus::EndOfStream, "explicit eos terminal status");
    require(!pipeline.processedTimelineActive(), "direct remains virgin");
    require(pipeline.timePitchDiagnostics().processCalls == 0, "direct never invokes DSP");
    require(pipeline.diagnostics().nonFiniteRenderPayloadSamples == 0, "direct finite diagnostics");
}

void stemsWaitForAllSixAndFallBackBeforeMissingPcm() {
    SongRenderPipeline pipeline(configuration());
    const SongRenderOwnership owner{22, 2};
    require(pipeline.beginSong(owner, (owner).generation, 0, 0) == SongRenderResetStatus::Ok, "begin stems");
    require(
        pipeline.submitControls(controls(1, MixerSourceMode::Stems)) ==
            SongRenderControlStatus::Accepted,
        "stem controls");

    constexpr std::size_t frames = 2'048;
    const auto original = tone(frames, 0, 0.06F);
    appendAllLanes(pipeline, owner.generation, 0, original, 512, 6);
    // Extend the first four stems; stem 5 intentionally ends at 512 and stem 6 is absent.
    std::vector<float> fullStem = original;
    for (float &sample : fullStem) sample /= 6.0F;
    const std::array<std::size_t, 8> chunks{1, 17, 233, 4, 64, 255, 31, 127};
    for (std::size_t index = 1; index <= 4; ++index) {
        appendChunked(
            pipeline,
            static_cast<SongRenderLane>(index),
            owner.generation,
            512,
            std::vector<float>(fullStem.begin() + 1'024, fullStem.end()),
            chunks);
    }

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;
    auto result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require((result.lateMixEndpoint.sourceMode == MixerSourceMode::Original), "render target remains Original while one lane absent");
    require(pipeline.diagnostics().stemActivationDeferrals >= 1, "stem deferral counted");

    appendChunked(
        pipeline,
        static_cast<SongRenderLane>(6U),
        owner.generation,
        0,
        fullStem,
        chunks);
    // Stem lane Piano (index 5) still has only 512 frames. Stems can activate for the next block.
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require((result.lateMixEndpoint.sourceMode == MixerSourceMode::Stems), "all six stems activate only when aligned");

    // At frame 512, Piano no longer covers the requested range. The block must use Original,
    // never a five-stem partial mix.
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require((result.lateMixEndpoint.sourceMode == MixerSourceMode::Original), "missing live stem forces original before rendering");
    require(pipeline.diagnostics().stemSafetyFallbacks == 1, "stem fallback counted once");
}

void lateStemLanesAlignOnceWithoutWeakeningGapRejection() {
    SongRenderPipeline pipeline(configuration());
    const SongRenderOwnership owner{23, 3};
    require(
        pipeline.beginSong(owner, (owner).generation, 0, 0) == SongRenderResetStatus::Ok,
        "begin late stems");
    require(
        pipeline.submitControls(controls(1, MixerSourceMode::Original)) ==
            SongRenderControlStatus::Accepted,
        "late stems original controls");

    constexpr std::size_t originalFrames = 4'096;
    const auto original = tone(originalFrames, 0, 0.06F);
    appendChunked(
        pipeline,
        SongRenderLane::Original,
        owner.generation,
        0,
        original,
        {1, 17, 233, 4, 64, 255, 31, 127});

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;
    auto result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    requireNear(pipeline.sourceTimelineFrame(), 512.0, 1.0e-10, "late stem live cursor");

    constexpr std::uint64_t lateAnchor = 256;
    constexpr std::size_t stemFrames = 2'048;
    auto stem = tone(stemFrames, lateAnchor, 0.01F);
    for (std::size_t index = 1; index < kSongRenderLaneCount; ++index) {
        const auto appended = pipeline.appendLanePcm(
            static_cast<SongRenderLane>(index),
            owner.generation,
            lateAnchor,
            stem.data(),
            stemFrames);
        require(appended.status == TimelineAppendStatus::Accepted, "late stem first block accepted");
        require(appended.timelineReset, "late stem first block aligned exactly once");
        const auto lane =
            pipeline.laneDiagnostics(static_cast<SongRenderLane>(index));
        require(lane.firstTimelineFrame == lateAnchor, "late stem reservoir starts at anchor");
        require(
            lane.endTimelineFrameExclusive == lateAnchor + stemFrames,
            "late stem reservoir covers decoded suffix");
    }

    require(
        pipeline.submitControls(controls(2, MixerSourceMode::Stems)) ==
            SongRenderControlStatus::Accepted,
        "late stems source controls");
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require((result.lateMixEndpoint.sourceMode == MixerSourceMode::Stems), "late attached stems become the render target without a bank handoff");

    constexpr std::uint64_t unexplainedGapStart = lateAnchor + stemFrames + 1U;
    const auto gap = pipeline.appendLanePcm(
        static_cast<SongRenderLane>(1U),
        owner.generation,
        unexplainedGapStart,
        stem.data(),
        1);
    require(gap.status == TimelineAppendStatus::GapRejected, "later stem gap remains rejected");
    require(!gap.timelineReset, "later stem gap cannot spend the one-shot alignment again");
    require(
        pipeline.laneDiagnostics(static_cast<SongRenderLane>(1U)).gapRejectionCount == 1U,
        "later stem gap rejection is diagnosed");
}

void tuneExtremesAutomateAndReturnToDirectAtNeutral() {
    SongRenderPipeline pipeline(configuration());
    const SongRenderOwnership owner{33, 3};
    require(pipeline.beginSong(owner, (owner).generation, 0, 0) == SongRenderResetStatus::Ok, "begin tuning");
    require(
        pipeline.submitControls(controls(1)) == SongRenderControlStatus::Accepted,
        "initial neutral controls");
    constexpr std::size_t frames = 14'000;
    const auto original = tone(frames, 0, 0.07F, 440.0);
    appendAllLanes(pipeline, owner.generation, 0, original);

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;
    auto result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require(!result.tuningProcessed, "neutral initial render direct");

    require(
        pipeline.submitControls(controls(2, MixerSourceMode::Original, 0.5F, -12.0F)) ==
            SongRenderControlStatus::Accepted,
        "minimum tuning controls");
    const double minimumStart = pipeline.sourceTimelineFrame();
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require(result.tuningProcessed, "minimum tuning processed");
    requireNear(
        result.sourceTimeline.endFrameExclusive - result.sourceTimeline.startFrame,
        128.0,
        1.0e-5,
        "minimum tempo source mapping");
    requireNear(result.sourceTimeline.startFrame, minimumStart, 1.0e-10, "tune starts exact");
    require(pipeline.processedTimelineActive(), "processed mode activated");
    require(
        pipeline.transitionDiagnostics().firstOutputDirectError == 0.0F,
        "first processed sample is exact direct sample");
    float energy = 0.0F;
    for (std::size_t sample = 0; sample < result.framesProduced * 2U; ++sample) {
        energy += output[sample] * output[sample];
    }
    require(energy > 0.0001F, "first tuned block has no silence gap");

    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require(
        pipeline.transitionDiagnostics().completedCount == 1,
        "constant-sum tuning transition completes across blocks");

    const std::array<std::pair<float, float>, 6> automation{{
        {2.0F, 12.0F},
        {0.5F, 0.0F},
        {1.25F, -7.0F},
        {0.8F, 12.0F},
        {1.0F, 0.0F},
        {2.0F, -12.0F},
    }};
    std::uint64_t revision = 3;
    for (std::size_t iteration = 0; iteration < 40; ++iteration) {
        const auto [tempoValue, pitchValue] = automation[iteration % automation.size()];
        require(
            pipeline.submitControls(
                controls(revision++, MixerSourceMode::Original, tempoValue, pitchValue)) ==
                SongRenderControlStatus::Accepted,
            "tune automation accepted");
        const std::uint32_t requested = static_cast<std::uint32_t>(
            1U + (iteration * 73U) % kMaximumBlock);
        result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, requested);
        if (result.status == SongRenderStatus::EndOfStream) break;
        verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
        const double expectedSpan = static_cast<double>(result.framesProduced) * tempoValue;
        requireNear(
            result.sourceTimeline.endFrameExclusive - result.sourceTimeline.startFrame,
            expectedSpan,
            2.0e-4,
            "automated tempo exact source mapping");
    }

    require(
        pipeline.submitControls(controls(revision, MixerSourceMode::Original, 1.0F, 0.0F)) ==
            SongRenderControlStatus::Accepted,
        "return neutral accepted");
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 64);
    if (result.status != SongRenderStatus::EndOfStream) {
        verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
        require(result.tuningProcessed, "neutral remains on processed timeline");
    }
    require(pipeline.timePitchDiagnostics().processedTimelineActivated, "DSP activation retained");

    const auto beforeSeek = pipeline.timePitchDiagnostics();
    const SongRenderOwnership seekOwner{owner.selectionToken, owner.generation + 1U};
    require(
        pipeline.seek(seekOwner, (seekOwner).generation, 1'000, pipeline.nextOutputClockFrame()) ==
            SongRenderResetStatus::Ok,
        "processed seek accepted");
    // The qualified musical window deliberately carries more lookahead than the old vendor
    // preset; keep this seek fixture large enough to exercise the reprime instead of testing
    // reservoir starvation.
    const auto seekPcm = tone(14'000, 1'000, 0.07F, 440.0);
    appendAllLanes(pipeline, seekOwner.generation, 1'000, seekPcm);
    result = renderFinal(pipeline, seekOwner, output.data(), kMaximumBlock, 64);
    verifyFinalBlock(result, output.data(), seekOwner, fingerprintSequence);
    require(!result.tuningProcessed, "neutral seek uses direct PCM at the new source position");
    requireNear(result.sourceTimeline.startFrame, 1'000.0, 1.0e-9, "seek source exact");
    require(
        pipeline.timePitchDiagnostics().seekCount == beforeSeek.seekCount + 1,
        "seek resets TimePitch once");
}

void tunedSeekReadinessUsesConsumedFeedCursorInsteadOfAudibleCursor() {
    SongRenderPipeline pipeline(configuration(32'768));
    const SongRenderOwnership firstOwner{34, 3};
    require(
        pipeline.beginSong(firstOwner, (firstOwner).generation, 0, 0) == SongRenderResetStatus::Ok,
        "begin tuned readiness owner");
    require(
        pipeline.submitControls(
            controls(1, MixerSourceMode::Original, 1.37F, -5.0F)) ==
            SongRenderControlStatus::Accepted,
        "tuned readiness controls");
    const auto initialPcm = tone(20'000, 0, 0.06F, 311.0);
    appendAllLanes(pipeline, firstOwner.generation, 0, initialPcm);

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;
    auto result = renderFinal(pipeline, firstOwner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), firstOwner, fingerprintSequence);
    require(result.tuningProcessed, "tuned readiness activates processed timeline");

    constexpr std::uint64_t seekFrame = 240'000;
    const SongRenderOwnership seekOwner{firstOwner.selectionToken, 4};
    require(
        pipeline.seek(seekOwner, (seekOwner).generation, seekFrame, pipeline.nextOutputClockFrame()) ==
            SongRenderResetStatus::Ok,
        "tuned readiness seek accepted");
    const auto seekPcm = tone(20'000, seekFrame, 0.06F, 311.0);
    appendAllLanes(pipeline, seekOwner.generation, seekFrame, seekPcm);
    result = renderFinal(pipeline, seekOwner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), seekOwner, fingerprintSequence);
    require(result.tuningProcessed, "tuned seek remains processed");

    const std::uint64_t audibleCursor =
        static_cast<std::uint64_t>(pipeline.sourceTimelineFrame());
    const std::uint64_t feedCursor = pipeline.nextPreDspTimelineFrame();
    require(feedCursor > audibleCursor, "time/pitch feed cursor runs ahead after tuned seek");
    constexpr std::size_t readinessHorizon = 1'152;
    for (std::size_t laneIndex = 0; laneIndex < kSongRenderLaneCount; ++laneIndex) {
        const auto lane = static_cast<SongRenderLane>(laneIndex);
        const auto diagnostics = pipeline.laneDiagnostics(lane);
        require(
            diagnostics.firstTimelineFrame == audibleCursor,
            "audible raw PCM remains available for a neutral return");
        require(
            pipeline.laneCoversLogicalRange(
                seekOwner,
                lane,
                audibleCursor,
                readinessHorizon),
            "audible cursor retains the PCM needed for a lossless neutral return");
        require(
            pipeline.laneCoversLogicalRange(
                seekOwner,
                lane,
                feedCursor,
                readinessHorizon),
            "next pre-DSP cursor proves exact live render coverage");
    }

    const auto needStemDataBefore = pipeline.diagnostics().needStemDataCount;
    require(
        pipeline.submitControls(
            controls(2, MixerSourceMode::Stems, 1.37F, -5.0F)) ==
            SongRenderControlStatus::Accepted,
        "same tuned control revision requests stems");
    result = renderFinal(pipeline, seekOwner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), seekOwner, fingerprintSequence);
    require(result.lateMixEndpoint.sourceMode == MixerSourceMode::Stems,
        "feed-cursor-ready stems activate without another seek");
    require(
        pipeline.diagnostics().needStemDataCount == needStemDataBefore,
        "feed-cursor-ready stems never produce NeedStemData");
}

void shortLoopHitsExactBoundaryReprimesAndNeverLeaksAnOldGeneration() {
    SongRenderPipeline pipeline(configuration(16'384, 128));
    SongRenderOwnership owner{44, 4};
    constexpr std::uint64_t start = 512;
    constexpr std::uint64_t end = 704;
    require(pipeline.beginSong(owner, (owner).generation, start, 0) == SongRenderResetStatus::Ok, "begin loop");
    require(
        pipeline.submitControls(
            controls(1, MixerSourceMode::Original, 0.75F, 4.0F)) ==
            SongRenderControlStatus::Accepted,
        "loop tune controls");
    CurrentSongPcmCache loopCache;
    const auto original = tone(8'192, start, 0.06F, 523.25);
    bindLoopFixture(loopCache, pipeline, owner, start, original);
    appendAllLanes(pipeline, owner.generation, start, original);
    require(
        pipeline.prepareAtomicLoopCutover(owner, true, start, end, start, 32) == SongRenderLoopStatus::Accepted,
        "short loop accepted");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;
    auto result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    // 192 source frames at 0.75x are 256 output frames; the fade split occurs at 224.
    require(result.framesProduced == 224, "loop split exactly at fade start");
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require(result.framesProduced == 32, "loop tail exact fade length");
    requireNear(result.sourceTimeline.endFrameExclusive, end, 1.0e-9, "loop reaches exact B");

    const auto beforeWrap = pipeline.timePitchDiagnostics();
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 17);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require(result.loopWrappedBeforeBlock, "next block wraps before render");
    requireNear(result.sourceTimeline.startFrame, start, 1.0e-9, "loop restarts exact A");
    require(
        pipeline.timePitchDiagnostics().seekCount == beforeWrap.seekCount + 1,
        "loop clears and reprimes DSP state");
    require(pipeline.diagnostics().loopWraps == 1, "loop wrap counted");

    const double beforeClearSource = pipeline.sourceTimelineFrame();
    const auto beforeClear = pipeline.timePitchDiagnostics();
    require(
        pipeline.clearLoop(owner) == SongRenderLoopStatus::Disabled,
        "live loop clear accepted");
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 19);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    requireNear(
        result.sourceTimeline.startFrame,
        beforeClearSource,
        1.0e-9,
        "loop clear keeps exact audible source position");
    require(
        pipeline.timePitchDiagnostics().seekCount == beforeClear.seekCount + 1,
        "loop clear discards old logical DSP future and reprimes");

    const SongRenderOwnership stale{owner.selectionToken, owner.generation - 1U};
    const auto staleRender = renderFinal(pipeline, stale, output.data(), kMaximumBlock, 16);
    require(staleRender.status == SongRenderStatus::StaleGeneration, "old loop owner rejected");
}

void directLoopRetargetCancelsTheObsoleteBoundaryFade() {
    SongRenderPipeline pipeline(configuration(16'384, 128));
    const SongRenderOwnership owner{45, 5};
    constexpr std::uint64_t start = 512;
    constexpr std::uint64_t oldEnd = 800;
    constexpr std::uint64_t newEnd = 1'024;
    constexpr std::uint32_t fadeFrames = 32;
    require(
        pipeline.beginSong(owner, (owner).generation, start, 0) == SongRenderResetStatus::Ok,
        "begin direct retarget loop");
    require(
        pipeline.submitControls(controls(1)) == SongRenderControlStatus::Accepted,
        "direct retarget controls");
    CurrentSongPcmCache loopCache;
    bindLoopFixture(loopCache, pipeline, owner, start, constantPcm(8'192, 0.08F));
    appendAllLanes(
        pipeline,
        owner.generation,
        start,
        constantPcm(8'192, 0.08F));
    require(
        pipeline.prepareAtomicLoopCutover(owner, true, start, oldEnd, start, fadeFrames) ==
            SongRenderLoopStatus::Accepted,
        "direct retarget loop accepted");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;
    auto result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    requireNear(result.sourceTimeline.endFrameExclusive, 768.0, 1.0e-9,
        "direct retarget reaches old fade window");
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 16);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require(
        pipeline.mixerDiagnostics().loopFadeOutRequests == 1,
        "old B begins one fade");
    require(
        pipeline.retargetAtomicLoopEnd(owner, start, newEnd) ==
            SongRenderLoopStatus::Accepted,
        "B extends while the old fade is active");

    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 64);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require(!result.loopWrappedBeforeBlock,
        "retarget crosses old B without wrapping");
    requireNear(result.sourceTimeline.startFrame, 784.0, 1.0e-9,
        "retarget preserves the audible cursor");
    requireNear(result.sourceTimeline.endFrameExclusive, 848.0, 1.0e-9,
        "retarget continues beyond old B");
    require(
        pcmLevel(output.data() + 48U * 2U, 16U).rms > 0.04,
        "obsolete old-B fade cannot silence the continuing loop");

    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    requireNear(result.sourceTimeline.endFrameExclusive, 992.0, 1.0e-9,
        "retarget reaches the new fade window");
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, fadeFrames);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    requireNear(result.sourceTimeline.endFrameExclusive, newEnd, 1.0e-9,
        "retarget reaches exact new B");
    result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 8);
    verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    require(result.loopWrappedBeforeBlock,
        "retarget still wraps at the new B");
    requireNear(result.sourceTimeline.startFrame, start, 1.0e-9,
        "retarget restarts at exact A");
}

void rapidHundredOwnershipResetsRejectEveryStaleLaneAndRender() {
    SongRenderPipeline pipeline(configuration());
    SongRenderOwnership owner{100, 100};
    require(pipeline.beginSong(owner, (owner).generation, 0, 0) == SongRenderResetStatus::Ok, "rapid initial song");
    require(
        pipeline.submitControls(controls(1)) == SongRenderControlStatus::Accepted,
        "rapid controls");
    const auto pcm = tone(512, 0, 0.04F);
    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;

    for (std::uint64_t iteration = 0; iteration < 100; ++iteration) {
        const SongRenderOwnership prior = owner;
        ++owner.generation;
        if ((iteration % 5U) == 0) {
            ++owner.selectionToken;
            require(
                pipeline.beginSong(owner, (owner).generation, 0, pipeline.nextOutputClockFrame()) ==
                    SongRenderResetStatus::Ok,
                "rapid begin newer song");
        } else {
            require(
                pipeline.seek(owner, (owner).generation, 0, pipeline.nextOutputClockFrame()) ==
                    SongRenderResetStatus::Ok,
                "rapid newer seek");
        }
        const auto staleAppend = pipeline.appendLanePcm(
            SongRenderLane::Original,
            prior.generation,
            0,
            pcm.data(),
            16);
        require(
            staleAppend.status == TimelineAppendStatus::StaleGeneration,
            "rapid stale append rejected");
        require(staleAppend.framesQueued == 0, "rapid stale append queues nothing");
        const auto staleRender = renderFinal(pipeline, prior, output.data(), kMaximumBlock, 16);
        require(
            staleRender.status == SongRenderStatus::StaleGeneration ||
                staleRender.status == SongRenderStatus::InvalidOwnership,
            "rapid stale render rejected");

        const auto appended = pipeline.appendLanePcm(
            SongRenderLane::Original,
            owner.generation,
            0,
            pcm.data(),
            pcm.size() / 2U);
        require(appended.status == TimelineAppendStatus::Accepted, "rapid current append");
        const auto current = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 16);
        verifyFinalBlock(current, output.data(), owner, fingerprintSequence);
    }
    require(pipeline.diagnostics().staleAppendCalls == 100, "all stale appends counted");
    require(pipeline.diagnostics().renderPayloadFingerprintsCompleted == 100, "all owners evidenced");
}

void authoredBeatAccentsSurviveSeekAndRetainedLoopWrap() {
    SongRenderPipeline pipeline(configuration());
    const SongRenderOwnership firstOwner{54, 400};
    require(
        pipeline.beginSong(firstOwner, (firstOwner).generation, 0, 0) == SongRenderResetStatus::Ok,
        "begin authored grid");
    auto snapshot = controls(1);
    snapshot.mixer.metronome.mode = MetronomeMode::BeatGrid;
    snapshot.mixer.metronome.gain = 0.5F;
    snapshot.mixer.metronome.beatsPerBar = 32;
    require(
        pipeline.submitControls(snapshot) == SongRenderControlStatus::Accepted,
        "authored grid controls");
    const std::array<std::int64_t, 5> grid{{0, 64, 128, 192, 256}};
    const std::array<std::uint8_t, 5> accents{{0, 1, 0, 1, 0}};
    require(
        pipeline.setBeatGrid(firstOwner, grid.data(), accents.data(), grid.size()),
        "authored pickup grid accepted");
    const auto firstPcm = constantPcm(512, 0.0F);
    require(
        pipeline.appendLanePcm(
            SongRenderLane::Original,
            firstOwner.generation,
            0,
            firstPcm.data(),
            512).status == TimelineAppendStatus::Accepted,
        "authored first generation appended");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;
    auto result = renderFinal(pipeline, firstOwner, output.data(), kMaximumBlock, 64);
    verifyFinalBlock(result, output.data(), firstOwner, fingerprintSequence);
    require(pipeline.mixerDiagnostics().metronomeAccentEvents == 0, "pickup is not accent");
    require(pipeline.mixerDiagnostics().metronomeBaseBeatEvents == 1, "pickup base beat");

    const SongRenderOwnership seekOwner{firstOwner.selectionToken, 401};
    require(
        pipeline.seek(seekOwner, (seekOwner).generation, 64, 64) == SongRenderResetStatus::Ok,
        "authored grid seek");
    const auto seekPcm = constantPcm(256, 0.0F);
    CurrentSongPcmCache loopCache;
    bindLoopFixture(loopCache, pipeline, seekOwner, 64U, seekPcm, 0U);
    require(
        pipeline.appendLanePcm(
            SongRenderLane::Original,
            seekOwner.generation,
            64,
            seekPcm.data(),
            256).status == TimelineAppendStatus::Accepted,
        "authored seek generation appended");
    require(
        pipeline.prepareAtomicLoopCutover(seekOwner, true, 64, 192, 64, 1, 1U) == SongRenderLoopStatus::Accepted,
        "authored retained loop");
    result = renderFinal(pipeline, seekOwner, output.data(), kMaximumBlock, 64);
    verifyFinalBlock(result, output.data(), seekOwner, fingerprintSequence);
    require(
        pipeline.mixerDiagnostics().metronomeAccentEvents == 1,
        "authored accent restored after seek");

    bool observedWrap = false;
    for (std::size_t attempt = 0; attempt < 4 && !observedWrap; ++attempt) {
        result = renderFinal(pipeline, seekOwner, output.data(), kMaximumBlock, 128);
        verifyFinalBlock(result, output.data(), seekOwner, fingerprintSequence);
        observedWrap = result.loopWrappedBeforeBlock;
    }
    require(observedWrap, "authored loop wrapped in block");
    require(pipeline.mixerDiagnostics().loopWraps == 1, "authored loop wrap counted");
    require(
        pipeline.mixerDiagnostics().metronomeAccentEvents == 2,
        "authored accent restored after loop wrap");
    require(
        pipeline.mixerDiagnostics().metronomeBaseBeatEvents == 3,
        "base beat remained distinct from accents");
}

void tenThousandBoundaryControlsAndSteadyRenderingAllocateNothing() {
    SongRenderPipeline pipeline(configuration());
    const SongRenderOwnership owner{55, 500};
    require(pipeline.beginSong(owner, (owner).generation, 0, 0) == SongRenderResetStatus::Ok, "begin pressure");
    constexpr std::size_t frames = 8'192;
    const auto original = tone(frames, 0, 0.03F, 281.0);
    CurrentSongPcmCache loopCache;
    bindLoopFixture(loopCache, pipeline, owner, 0U, original);
    appendAllLanes(pipeline, owner.generation, 0, original);
    require(
        pipeline.prepareAtomicLoopCutover(owner, true, 0, frames, 0, 8) == SongRenderLoopStatus::Accepted,
        "pressure loop");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;
    std::uint64_t revision = 1;
    const std::uint64_t allocationsBefore = allocation_probe::count.load(
        std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    for (std::size_t iteration = 0; iteration < 10'000; ++iteration) {
        auto snapshot = controls(
            revision++,
            (iteration % 3U) == 0 ? MixerSourceMode::Stems : MixerSourceMode::Original);
        snapshot.mixer.musicGain = 0.5F + static_cast<float>(iteration % 101U) / 100.0F;
        snapshot.mixer.stems[iteration % 6U].muted = (iteration % 5U) == 0;
        snapshot.mixer.stems[(iteration + 1U) % 6U].soloed = (iteration % 11U) == 0;
        snapshot.mixer.stems[(iteration + 2U) % 6U].gain =
            0.25F + static_cast<float>(iteration % 7U) * 0.2F;
        snapshot.mixer.metronome.mode = (iteration % 4U) == 0
            ? MetronomeMode::FreeBpm
            : MetronomeMode::Off;
        snapshot.mixer.metronome.bpm = 60.0F + static_cast<float>(iteration % 240U);
        snapshot.mixer.metronome.gain = 0.15F;
        require(
            pipeline.submitControls(snapshot) == SongRenderControlStatus::Accepted,
            "pressure control accepted");
        const auto result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 1);
        verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    }
    allocation_probe::enabled.store(false, std::memory_order_release);
    require(
        allocation_probe::count.load(std::memory_order_relaxed) == allocationsBefore,
        "10k controls/render path allocated memory");
    require(pipeline.diagnostics().renderedBlocks == 10'000, "10k blocks rendered");
    require(
        pipeline.diagnostics().controlSnapshotsAccepted == 10'000,
        "all 10k boundary controls accepted");
    require(
        pipeline.mixerDiagnostics().controlSnapshotsApplied >= 10'000,
        "all 10k boundary controls reached PRE/POST mixer");
    require(pipeline.diagnostics().nonFiniteRenderPayloadSamples == 0, "pressure samples finite");
}

void gainOnlyPreviewPreservesCanonicalControlsAndNeverDirtiesTune() {
    SongRenderPipeline pipeline(configuration(131'072));
    const SongRenderOwnership owner{56, 501};
    require(pipeline.beginSong(owner, (owner).generation, 0, 0) == SongRenderResetStatus::Ok, "preview begin");
    const auto original = constantPcm(65'536, 0.06F);
    appendAllLanes(pipeline, owner.generation, 0, original);

    auto canonical = controls(1, MixerSourceMode::Stems);
    canonical.sourceIntentRevision = 11;
    canonical.stemIntentRevision = 21;
    canonical.mixer.stems[0].soloed = true;
    canonical.mixer.stems[1].muted = true;
    require(
        pipeline.submitControls(canonical) == SongRenderControlStatus::Accepted,
        "preview canonical controls");
    std::array<float, kMaximumBlock * 2U> output{};
    SongRenderResult result{};
    float canonicalEnergy = 0.0F;
    for (std::size_t attempt = 0U; attempt < 32U && canonicalEnergy <= 0.1F; ++attempt) {
        result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
        require(result.status == SongRenderStatus::Rendered, "preview canonical render");
        canonicalEnergy = 0.0F;
        for (std::size_t sample = 0; sample < result.framesProduced * 2U; ++sample) {
            canonicalEnergy += std::abs(output[sample]);
        }
    }
    require(
        result.lateMixEndpoint.sourceMode == MixerSourceMode::Stems,
        "preview canonical source did not settle on stems");
    require(canonicalEnergy > 0.1F, "canonical solo lane is audible");

    const auto before = pipeline.diagnostics();
    const std::uint64_t mixerSnapshotsBeforePreview = before.mixerSnapshotsApplied;
    const std::uint64_t tuneSnapshotsBeforePreview = before.tuneSnapshotsApplied;
    std::array<float, stemwise::audio::kStemLaneCount> gains{
        0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F};
    const std::uint64_t allocationsBefore =
        allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    for (std::uint64_t sequence = 1; sequence <= 10'000; ++sequence) {
        gains[0] = sequence == 10'000 ? 0.0F : static_cast<float>(sequence % 101U) / 100.0F;
        require(
            pipeline.submitStemGainPreview(owner, sequence, sequence, 1U, gains) ==
                SongRenderStemGainPreviewStatus::Accepted,
            "gain-only preview accepted");
    }
    allocation_probe::enabled.store(false, std::memory_order_release);
    require(
        allocation_probe::count.load(std::memory_order_relaxed) == allocationsBefore,
        "gain-only preview allocated");
    require(
        pipeline.diagnostics().controlSnapshotsAccepted == before.controlSnapshotsAccepted,
        "gain-only preview advanced canonical controls");

    float mutedEnergy = canonicalEnergy;
    for (std::size_t attempt = 0U;
         attempt < 32U && mutedEnergy >= canonicalEnergy * 0.02F;
         ++attempt) {
        output.fill(0.0F);
        result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
        require(result.status == SongRenderStatus::Rendered, "gain-only preview render");
        mutedEnergy = 0.0F;
        for (std::size_t sample = 0; sample < result.framesProduced * 2U; ++sample) {
            mutedEnergy += std::abs(output[sample]);
        }
    }
    require(mutedEnergy < canonicalEnergy * 0.02F, "preview gain did not preserve canonical solo");
    require(
        result.stemGainPreviewPublicationSequence == 10'000U &&
            result.stemGainPreviewCoversOfferSequence == 10'000U &&
            result.stemGainPreviewActiveMask == 1U &&
            result.stemGainPreviewGains[0] == 0.0F,
        "final PCM result did not carry the exact admitted gain overlay");
    const auto afterPreviewRender = pipeline.diagnostics();
    require(
        afterPreviewRender.mixerSnapshotsApplied == mixerSnapshotsBeforePreview + 1U,
        "10k conflated previews applied exactly one mixer snapshot at render boundary");
    require(
        afterPreviewRender.tuneSnapshotsApplied == tuneSnapshotsBeforePreview,
        "gain-only preview applied a tune snapshot");
    require(
        afterPreviewRender.tuneActivations == before.tuneActivations &&
            afterPreviewRender.tunePrimes == before.tunePrimes,
        "gain-only preview dirtied time/pitch state");

    require(
        pipeline.submitStemGainPreview(owner, 10'001, 10'001, 0U, gains) ==
            SongRenderStemGainPreviewStatus::Accepted,
        "gain-only preview clear");
    float restoredEnergy = 0.0F;
    for (std::size_t attempt = 0U;
         attempt < 32U && restoredEnergy <= canonicalEnergy * 0.5F;
         ++attempt) {
        output.fill(0.0F);
        result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
        restoredEnergy = 0.0F;
        for (std::size_t sample = 0; sample < result.framesProduced * 2U; ++sample) {
            restoredEnergy += std::abs(output[sample]);
        }
    }
    require(restoredEnergy > canonicalEnergy * 0.5F, "canonical gain did not resume after clear");
    require(
        result.stemGainPreviewPublicationSequence == 10'001U &&
            result.stemGainPreviewCoversOfferSequence == 10'001U &&
            result.stemGainPreviewActiveMask == 0U,
        "final PCM result did not carry the exact gain-overlay retirement");

    require(
        pipeline.submitStemGainPreview(owner, 10'002, 10'002, 1U, gains) ==
            SongRenderStemGainPreviewStatus::Accepted,
        "package-bound preview accepted");
    auto replacement = canonical;
    replacement.revision = 2;
    replacement.applicationEpoch = 2;
    replacement.stemIntentRevision = 22;
    require(
        pipeline.submitControls(replacement) == SongRenderControlStatus::Accepted,
        "replacement package controls");
    float replacementEnergy = 0.0F;
    for (std::size_t attempt = 0U;
         attempt < 32U && replacementEnergy <= canonicalEnergy * 0.5F;
         ++attempt) {
        output.fill(0.0F);
        result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
        replacementEnergy = 0.0F;
        for (std::size_t sample = 0; sample < result.framesProduced * 2U; ++sample) {
            replacementEnergy += std::abs(output[sample]);
        }
    }
    require(replacementEnergy > canonicalEnergy * 0.5F, "package revision retained stale overlay");
}

void heldSliderGainOverridesMuteWithoutChangingCanonicalMuteOrSolo() {
    SongRenderPipeline pipeline(configuration(65'536));
    const SongRenderOwnership owner{560U, 5'010U};
    require(pipeline.beginSong(owner, owner.generation, 0, 0) == SongRenderResetStatus::Ok,
        "held slider source begins");
    appendAllLanes(pipeline, owner.generation, 0, constantPcm(32'768, 0.06F));
    auto canonical = controls(1U, MixerSourceMode::Stems);
    canonical.mixer.stems[0].muted = true;
    canonical.mixer.stems[0].soloed = true;
    require(pipeline.submitControls(canonical) == SongRenderControlStatus::Accepted,
        "held slider canonical mute/solo accepted");
    std::array<float, kMaximumBlock * 2U> output{};
    std::array<float, stemwise::audio::kStemLaneCount> gains{};
    auto assertPcm = [&](float expected) {
        SongRenderResult rendered{};
        for (unsigned block = 0; block < 4U; ++block) {
            rendered = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
            require(rendered.status == SongRenderStatus::Rendered, "held slider final PCM renders");
        }
        for (std::size_t sample = 0; sample < rendered.framesProduced * 2U; ++sample) {
            requireNear(output[sample], expected, 0.000001,
                "held slider final PCM differs from independent constant lane sample");
        }
    };
    assertPcm(0.0F);
    gains[0] = 0.5F; gains[1] = 0.9F;
    require(pipeline.submitStemGainPreview(owner, 1U, 1U, 3U, gains) ==
        SongRenderStemGainPreviewStatus::Accepted, "held positive batch preview accepted");
    // Each lane is 0.06 / 6. Canonical solo excludes lane 1 despite its positive preview.
    assertPcm(0.005F);
    gains[0] = 0.0F;
    require(pipeline.submitStemGainPreview(owner, 2U, 2U, 1U, gains) ==
        SongRenderStemGainPreviewStatus::Accepted, "held zero single preview accepted");
    assertPcm(0.0F);
    gains[0] = 0.75F;
    require(pipeline.submitStemGainPreview(owner, 3U, 3U, 1U, gains) ==
        SongRenderStemGainPreviewStatus::Accepted, "held positive single preview accepted");
    assertPcm(0.0075F);
    require(pipeline.submitStemGainPreview(owner, 4U, 4U, 0U, gains) ==
        SongRenderStemGainPreviewStatus::Accepted, "held preview retirement accepted");
    assertPcm(0.0F);
    require(pipeline.diagnostics().controlSnapshotsAccepted == 1U,
        "held preview cannot rewrite canonical mute, solo or stored gain");
}

void ownerRetirementIsExactAndResetPreservesPreviewHighWater() {
    SongRenderPipeline pipeline(configuration());
    const SongRenderOwnership firstOwner{57U, 510U};
    const SongRenderOwnership secondOwner{58U, 511U};
    const SongRenderOwnership wrongOwner{59U, 510U};
    require(
        pipeline.beginSong(firstOwner, (firstOwner).generation, 0U, 0) == SongRenderResetStatus::Ok,
        "preview-retirement first owner");

    std::array<float, stemwise::audio::kStemLaneCount> gains{};
    gains.fill(1.0F);
    gains[0] = 0.25F;
    require(
        pipeline.submitStemGainPreview(firstOwner, 7U, 7U, 1U, gains) ==
            SongRenderStemGainPreviewStatus::Accepted,
        "preview-retirement active overlay");
    require(
        !pipeline.retireStemGainPreview(wrongOwner),
        "preview-retirement rejected a nonresident owner");
    require(
        pipeline.retireStemGainPreview(firstOwner),
        "preview-retirement accepted the exact resident owner");
    require(
        pipeline.submitStemGainPreview(firstOwner, 7U, 7U, 1U, gains) ==
            SongRenderStemGainPreviewStatus::StaleSequence,
        "explicit retirement preserved preview sequence high-water");

    require(
        pipeline.beginSong(secondOwner, (secondOwner).generation, 0U, 0) == SongRenderResetStatus::Ok,
        "preview-retirement replacement owner");
    require(
        pipeline.submitStemGainPreview(secondOwner, 7U, 7U, 1U, gains) ==
            SongRenderStemGainPreviewStatus::StaleSequence,
        "pipeline reset preserved preview sequence high-water");
    require(
        pipeline.submitStemGainPreview(secondOwner, 8U, 8U, 1U, gains) ==
            SongRenderStemGainPreviewStatus::Accepted,
        "newer replacement-owner preview accepted");
    require(
        !pipeline.retireStemGainPreview(firstOwner),
        "retired owner cannot clear replacement-owner overlay");
    require(
        pipeline.retireStemGainPreview(secondOwner),
        "replacement owner retires its own overlay");
}

void processedLoopPressureAllocatesNothing() {
    SongRenderPipeline pipeline(configuration());
    const SongRenderOwnership owner{66, 600};
    require(pipeline.beginSong(owner, (owner).generation, 0, 0) == SongRenderResetStatus::Ok, "begin DSP pressure");
    constexpr std::size_t frames = 8'192;
    const auto original = tone(frames, 0, 0.025F, 392.0);
    CurrentSongPcmCache loopCache;
    bindLoopFixture(loopCache, pipeline, owner, 0U, original);
    appendAllLanes(pipeline, owner.generation, 0, original);
    require(
        pipeline.prepareAtomicLoopCutover(owner, true, 0, frames, 0, 16) == SongRenderLoopStatus::Accepted,
        "DSP pressure loop");
    require(
        pipeline.submitControls(controls(1, MixerSourceMode::Original, 0.5F, -4.0F)) ==
            SongRenderControlStatus::Accepted,
        "DSP pressure activation");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;
    auto warm = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 256);
    verifyFinalBlock(warm, output.data(), owner, fingerprintSequence);
    std::uint64_t revision = 2;
    const std::array<std::pair<float, float>, 6> automation{{
        {0.5F, -12.0F},
        {2.0F, 12.0F},
        {0.5F, 3.0F},
        {2.0F, -6.0F},
        {1.0F, 0.0F},
        {0.9F, 7.0F},
    }};

    const std::uint64_t allocationsBefore = allocation_probe::count.load(
        std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    for (std::size_t iteration = 0; iteration < 1'000; ++iteration) {
        const auto [tempoValue, pitchValue] = automation[iteration % automation.size()];
        require(
            pipeline.submitControls(
                controls(revision++, MixerSourceMode::Original, tempoValue, pitchValue)) ==
                SongRenderControlStatus::Accepted,
            "DSP automation accepted");
        const std::uint32_t requested = static_cast<std::uint32_t>(
            1U + (iteration * 37U) % kMaximumBlock);
        const auto result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, requested);
        verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
    }
    allocation_probe::enabled.store(false, std::memory_order_release);
    require(
        allocation_probe::count.load(std::memory_order_relaxed) == allocationsBefore,
        "processed tuning/loop path allocated memory");
    require(pipeline.diagnostics().nonFiniteRenderPayloadSamples == 0, "DSP pressure finite");
}

void twoResidentBanksHaveIndependentOwnershipAndEvidence() {
    SongRenderPipeline active(configuration());
    SongRenderPipeline candidate(configuration());
    const SongRenderOwnership activeOwner{71, 700};
    const SongRenderOwnership candidateOwner{72, 701};
    require(active.beginSong(activeOwner, activeOwner.generation, 0, 50) == SongRenderResetStatus::Ok, "active bank");
    require(
        candidate.beginSong(candidateOwner, candidateOwner.generation, 2'000, 50) == SongRenderResetStatus::Ok,
        "candidate bank");
    require(
        active.submitControls(controls(1)) == SongRenderControlStatus::Accepted,
        "active bank controls");
    require(
        candidate.submitControls(controls(1)) == SongRenderControlStatus::Accepted,
        "candidate bank controls");
    const auto activePcm = constantPcm(512, 0.04F);
    const auto candidatePcm = constantPcm(512, -0.07F);
    require(
        active.appendLanePcm(
            SongRenderLane::Original,
            activeOwner.generation,
            0,
            activePcm.data(),
            512).status == TimelineAppendStatus::Accepted,
        "active bank append");
    require(
        candidate.appendLanePcm(
            SongRenderLane::Original,
            candidateOwner.generation,
            2'000,
            candidatePcm.data(),
            512).status == TimelineAppendStatus::Accepted,
        "candidate bank append");

    std::array<float, kMaximumBlock * 2U> activeOutput{};
    std::array<float, kMaximumBlock * 2U> candidateOutput{};
    std::uint64_t activeSequence = 0;
    std::uint64_t candidateSequence = 0;
    const auto activeResult = renderFinal(active,
        activeOwner,
        activeOutput.data(),
        kMaximumBlock,
        64);
    const auto candidateResult = renderFinal(candidate,
        candidateOwner,
        candidateOutput.data(),
        kMaximumBlock,
        64);
    verifyFinalBlock(activeResult, activeOutput.data(), activeOwner, activeSequence);
    verifyFinalBlock(candidateResult, candidateOutput.data(), candidateOwner, candidateSequence);
    require(activeResult.payloadFingerprint.sequence == 1, "active sequence independent");
    require(candidateResult.payloadFingerprint.sequence == 1, "candidate sequence independent");
    require(
        activeResult.payloadFingerprint.hash != candidateResult.payloadFingerprint.hash,
        "bank fingerprints independent");
    require(active.ownership().selectionToken == activeOwner.selectionToken, "active owner stable");
    require(
        candidate.ownership().selectionToken == candidateOwner.selectionToken,
        "candidate owner stable");
}

void appendBackpressurePreservesRetrySuffixContract() {
    constexpr std::size_t capacity = 16'384;
    SongRenderPipeline pipeline(configuration(capacity));
    const SongRenderOwnership owner{80, 800};
    require(pipeline.beginSong(owner, (owner).generation, 0, 0) == SongRenderResetStatus::Ok, "begin append pressure");
    const auto pcm = tone(capacity + 808, 0, 0.02F);
    const auto first = pipeline.appendLanePcm(
        SongRenderLane::Original,
        owner.generation,
        0,
        pcm.data(),
        capacity + 808);
    require(first.status == TimelineAppendStatus::Partial, "oversized append partial");
    require(first.inputFramesAccepted == capacity, "partial accepts exact reservoir capacity");
    require(first.framesQueued == capacity, "partial queues exact capacity");
    const auto retry = pipeline.appendLanePcm(
        SongRenderLane::Original,
        owner.generation,
        first.inputFramesAccepted,
        pcm.data() + first.inputFramesAccepted * 2U,
        capacity + 808 - first.inputFramesAccepted);
    require(retry.status == TimelineAppendStatus::Backpressured, "suffix retry backpressured");
    require(retry.inputFramesAccepted == 0, "backpressure consumes no suffix");
}

void managedLoopUsesExactFourSecondCacheAndWaitsAtB() {
    constexpr std::uint64_t start = 48'000U;
    constexpr std::uint64_t length = LoopHeadCache::kCapacityFrames;
    constexpr std::uint64_t ingressEpoch = 100U;
    const SongRenderOwnership owner{91U, 10U};
    LoopHeadCache cache;
    const auto key = loopKey(1U, start);
    const auto cacheState = prepareOriginalLoopCache(cache, key, length);

    SongRenderPipeline pipeline(configuration());
    require(
        pipeline.beginSong(owner, ingressEpoch, start, 0) == SongRenderResetStatus::Ok,
        "cache-only begin");
    require(
        pipeline.submitControls(controls(1U)) == SongRenderControlStatus::Accepted,
        "cache-only controls");
    const auto install = loopDirective(
        LoopCycleDirectiveAction::InstallWarmCacheOnly,
        1U,
        0U,
        owner,
        ingressEpoch,
        cacheState);
    require(
        pipeline.applyLoopDirective(install, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "cache-only install");
    require(pipeline.loopCycleSnapshot().cacheOnly, "exact 192000 is cache-only");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0U;
    std::uint64_t rendered = 0U;
    const std::uint64_t allocationsBefore =
        allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    while (rendered < length) {
        const auto result = renderFinal(pipeline,
            owner,
            output.data(),
            kMaximumBlock,
            kMaximumBlock);
        verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
        require(result.loopEnabled, "cache loop block tagged enabled");
        require(result.loopRevision == key.loopRevision, "cache loop block revision");
        require(result.loopCycle == 0U, "cache loop block cycle zero");
        rendered += result.framesProduced;
        require(result.ownership.generation == owner.generation, "cache loop owner stable");
        if (rendered == length) require(result.loopBoundaryPending, "B reported in final block");
    }
    require(rendered == length, "cache loop reaches exact B");
    const auto boundary = pipeline.pendingLoopBoundary();
    require(boundary.sequence == 1U, "cache boundary sequence");
    require(boundary.loopRevision == key.loopRevision, "cache boundary revision");
    require(boundary.cycle == 0U, "cache boundary cycle zero");
    require(boundary.owner.renderGeneration == owner.generation, "cache boundary owner");
    require(boundary.ingressEpoch == ingressEpoch, "cache boundary ingress");
    require(boundary.sourceTimelineFrame == key.endTimelineFrameExclusive, "cache boundary B");
    const auto waiting = renderFinal(pipeline,
        owner,
        output.data(),
        kMaximumBlock,
        64U);
    require(waiting.status == SongRenderStatus::LoopBoundaryPending, "cache B waits");
    require(waiting.framesProduced == 0U, "cache B produces no unowned PCM");
    require(pipeline.pendingLoopBoundary().sequence == boundary.sequence, "B evidence idempotent");

    const auto wrap = loopDirective(
        LoopCycleDirectiveAction::WrapCacheOnly,
        2U,
        1U,
        owner,
        ingressEpoch,
        cacheState);
    require(
        pipeline.applyLoopDirective(wrap, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "cache wrap accepted");
    require(pipeline.ownership().generation == owner.generation, "cache wrap keeps owner");
    require(pipeline.loopCycleSnapshot().cycle == 1U, "cache cycle advances");
    const auto afterWrap = renderFinal(pipeline,
        owner,
        output.data(),
        kMaximumBlock,
        64U);
    verifyFinalBlock(afterWrap, output.data(), owner, fingerprintSequence);
    require(afterWrap.loopEnabled, "cache wrapped block tagged enabled");
    require(afterWrap.loopRevision == key.loopRevision, "cache wrapped block revision");
    require(afterWrap.loopCycle == 1U, "cache wrapped block cycle one");
    requireNear(afterWrap.sourceTimeline.startFrame, start, 1.0e-10, "cache wrap exact A");
    require(
        pipeline.applyLoopDirective(wrap, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::StaleDirective,
        "duplicate cache wrap rejected");
    allocation_probe::enabled.store(false, std::memory_order_release);
    require(
        allocation_probe::count.load(std::memory_order_relaxed) == allocationsBefore,
        "cache render and wrap allocate nothing");
    require(
        pipeline.diagnostics().loopCacheFramesCopied >= length + 64U,
        "cache supplies exact loop head");
}

void oneFramePastFourSecondsStreamsBodyWithIndependentEpoch() {
    constexpr std::uint64_t start = 96'000U;
    constexpr std::uint64_t length = LoopHeadCache::kCapacityFrames + 1U;
    constexpr std::uint64_t firstEpoch = 200U;
    const SongRenderOwnership owner{92U, 20U};
    LoopHeadCache cache;
    const auto key = loopKey(2U, start);
    const auto cacheState = prepareOriginalLoopCache(cache, key, length, 0.05F);
    require(!cacheState.cacheOnly, "192001 cache snapshot is streamed");

    SongRenderPipeline pipeline(configuration());
    require(
        pipeline.beginSong(owner, firstEpoch, start, 0) == SongRenderResetStatus::Ok,
        "stream cutoff begin");
    require(
        pipeline.submitControls(controls(1U)) == SongRenderControlStatus::Accepted,
        "stream cutoff controls");
    const auto install = loopDirective(LoopCycleDirectiveAction::InstallWarmStreamed, 1U, 0U, owner, firstEpoch, cacheState, start + length);
    require(
        pipeline.applyLoopDirective(install, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "stream cutoff install");
    require(!pipeline.loopCycleSnapshot().cacheOnly, "192001 uses streamed mode");

    const auto body = constantPcm(1U, 0.12F);
    const std::uint64_t headEnd = start + LoopHeadCache::kCapacityFrames;
    require(
        pipeline.appendLanePcm(
            SongRenderLane::Original,
            firstEpoch,
            headEnd,
            body.data(),
            1U).status == TimelineAppendStatus::Accepted,
        "first streamed body frame");
    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0U;
    std::uint64_t rendered = 0U;
    while (rendered < length) {
        const auto result = renderFinal(pipeline,
            owner,
            output.data(),
            kMaximumBlock,
            kMaximumBlock);
        verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
        require(result.loopEnabled, "stream cutoff block tagged enabled");
        require(result.loopRevision == key.loopRevision, "stream cutoff block revision");
        require(result.loopCycle == 0U, "stream cutoff block cycle zero");
        rendered += result.framesProduced;
    }
    require(rendered == length, "stream cutoff exact B");
    require(pipeline.pendingLoopBoundary().cycle == 0U, "stream cutoff boundary cycle");
    require(pipeline.diagnostics().loopBodyFramesCopied == 1U, "one body frame streamed");

    auto badWrap = loopDirective(LoopCycleDirectiveAction::WrapStreamed, 2U, 1U, owner, firstEpoch, cacheState, start + length);
    require(
        pipeline.applyLoopDirective(badWrap, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::BoundaryMismatch,
        "stream wrap requires newer ingress epoch");
    badWrap.ingressEpoch = firstEpoch + 1U;
    require(
        pipeline.applyLoopDirective(badWrap, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "stream wrap accepts prepared epoch");
    require(pipeline.ownership().generation == owner.generation, "stream wrap owner stable");
    require(pipeline.ingressEpoch() == firstEpoch + 1U, "stream wrap advances ingress only");
    require(
        pipeline.appendLanePcm(
            SongRenderLane::Original,
            firstEpoch,
            headEnd,
            body.data(),
            1U).status == TimelineAppendStatus::StaleGeneration,
        "old streamed epoch rejected");
    require(
        pipeline.appendLanePcm(
            SongRenderLane::Original,
            firstEpoch + 1U,
            headEnd,
            body.data(),
            1U).status == TimelineAppendStatus::Accepted,
        "new streamed epoch accepted");
    const auto nextCycle = renderFinal(pipeline,
        owner,
        output.data(),
        kMaximumBlock,
        64U);
    verifyFinalBlock(nextCycle, output.data(), owner, fingerprintSequence);
    require(nextCycle.loopEnabled, "stream next cycle tagged enabled");
    require(nextCycle.loopCycle == 1U, "stream next cycle exact tag");
    requireNear(nextCycle.sourceTimeline.startFrame, start, 1.0e-10, "stream wrap exact A");
}

void thirtySecondLoopStreamsFixedMemoryWithoutChangingOwner() {
    constexpr std::uint64_t start = 144'000U;
    constexpr std::uint64_t length = 30U * kSampleRate;
    constexpr std::uint64_t ingressEpoch = 300U;
    constexpr std::size_t bodyChunkFrames = 8'192U;
    const SongRenderOwnership owner{93U, 30U};
    LoopHeadCache cache;
    const auto key = loopKey(3U, start);
    const auto cacheState = prepareOriginalLoopCache(cache, key, length, 0.04F);
    const std::uint64_t headEnd = start + LoopHeadCache::kCapacityFrames;
    const std::uint64_t loopEnd = start + length;

    SongRenderPipeline pipeline(configuration(16'384U));
    require(
        pipeline.beginSong(owner, ingressEpoch, headEnd, 0) == SongRenderResetStatus::Ok,
        "30s begin at body");
    require(
        pipeline.submitControls(controls(1U)) == SongRenderControlStatus::Accepted,
        "30s controls");
    const auto install = loopDirective(LoopCycleDirectiveAction::InstallWarmStreamed, 1U, 0U, owner, ingressEpoch, cacheState, start + length);
    require(
        pipeline.applyLoopDirective(install, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "30s streamed install");

    const auto body = constantPcm(bodyChunkFrames, 0.03F);
    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0U;
    std::uint64_t cursor = headEnd;
    const std::uint64_t allocationsBefore =
        allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    while (cursor < loopEnd) {
        const std::size_t chunk = static_cast<std::size_t>(std::min<std::uint64_t>(
            bodyChunkFrames,
            loopEnd - cursor));
        const auto appended = pipeline.appendLanePcm(
            SongRenderLane::Original,
            ingressEpoch,
            cursor,
            body.data(),
            chunk);
        require(appended.status == TimelineAppendStatus::Accepted, "30s body append");
        const std::uint64_t chunkEnd = cursor + chunk;
        while (pipeline.sourceTimelineFrame() + 1.0e-9 <
               static_cast<double>(chunkEnd)) {
            const auto result = renderFinal(pipeline,
                owner,
                output.data(),
                kMaximumBlock,
                kMaximumBlock);
            verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
            require(result.ownership.generation == owner.generation, "30s owner stable");
        }
        cursor = chunkEnd;
    }
    allocation_probe::enabled.store(false, std::memory_order_release);
    require(
        allocation_probe::count.load(std::memory_order_relaxed) == allocationsBefore,
        "30s steady stream allocates nothing");
    requireNear(pipeline.sourceTimelineFrame(), loopEnd, 1.0e-10, "30s exact B");
    require(pipeline.loopCycleSnapshot().boundaryPending, "30s boundary pending");
    require(
        pipeline.diagnostics().loopBodyFramesCopied ==
            length - LoopHeadCache::kCapacityFrames,
        "30s full body streamed, never cached");
    require(LoopHeadCache::maximumPcmBytes() == 30'720'000U, "bounded maximum cache memory");
}

void streamedPostWrapClearBridgesExactNinetySixFramesAndCurrentBodyEpoch() {
    constexpr std::uint64_t start = 0U;
    constexpr std::uint64_t headEnd = LoopHeadCache::kCapacityFrames;
    constexpr std::uint64_t loopEnd = headEnd + 512U;
    constexpr std::uint64_t firstEpoch = 18U;
    constexpr std::uint64_t wrappedEpoch = 19U;
    constexpr std::uint32_t formerHoleFrames = 96U;
    constexpr float cachedDomain = 0.03125F;
    constexpr float bodyDomain = 0.125F;
    const SongRenderOwnership owner{194U, 40U};
    LoopHeadCache cache;
    const auto key = loopKey(4U, start);
    const auto cachedHead = provenancePcm(
        LoopHeadCache::kCapacityFrames,
        start,
        0U,
        cachedDomain);
    const auto cacheState = prepareOriginalLoopCacheFromPcm(cache, key, cachedHead);

    SongRenderPipeline pipeline(configuration());
    require(
        pipeline.beginSong(owner, firstEpoch, loopEnd - formerHoleFrames, 0) ==
            SongRenderResetStatus::Ok,
        "post-wrap Clear begin at first-cycle tail");
    auto provenanceControls = controls(1U);
    require(
        pipeline.submitControls(provenanceControls) == SongRenderControlStatus::Accepted,
        "post-wrap Clear controls");
    const auto install = loopDirective(LoopCycleDirectiveAction::InstallWarmStreamed, 1U, 0U, owner, firstEpoch, cacheState, loopEnd);
    require(
        pipeline.applyLoopDirective(install, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "post-wrap streamed install");
    const auto firstTail = constantPcm(formerHoleFrames, 0.09F);
    require(
        pipeline.appendLanePcm(
            SongRenderLane::Original,
            firstEpoch,
            loopEnd - formerHoleFrames,
            firstTail.data(),
            formerHoleFrames).status == TimelineAppendStatus::Accepted,
        "post-wrap first-cycle tail append");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0U;
    std::uint32_t firstTailRendered = 0U;
    bool firstBoundaryPending = false;
    while (firstTailRendered < formerHoleFrames) {
        const auto atBoundary = renderFinal(pipeline,
            owner,
            output.data(),
            kMaximumBlock,
            formerHoleFrames - firstTailRendered);
        verifyFinalBlock(atBoundary, output.data(), owner, fingerprintSequence);
        firstTailRendered += atBoundary.framesProduced;
        firstBoundaryPending = firstBoundaryPending || atBoundary.loopBoundaryPending;
    }
    require(firstTailRendered == formerHoleFrames, "post-wrap first tail reaches exact B");
    require(firstBoundaryPending, "post-wrap first cycle publishes exact B");

    const auto wrap = loopDirective(LoopCycleDirectiveAction::WrapStreamed, 2U, 1U, owner, wrappedEpoch, cacheState, loopEnd);
    require(
        pipeline.applyLoopDirective(wrap, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "post-wrap streamed wrap");
    const auto currentBody = provenancePcm(1'024U, headEnd, 0U, bodyDomain);
    require(
        pipeline.appendLanePcm(
            SongRenderLane::Original,
            wrappedEpoch,
            headEnd,
            currentBody.data(),
            currentBody.size() / 2U).status == TimelineAppendStatus::Accepted,
        "post-wrap generation-19 body append");
    const auto firstWrapped = renderFinal(pipeline,
        owner,
        output.data(),
        kMaximumBlock,
        formerHoleFrames);
    verifyFinalBlock(firstWrapped, output.data(), owner, fingerprintSequence);
    require(firstWrapped.loopEnabled, "post-wrap first head block still loop tagged");
    require(firstWrapped.loopCycle == 1U, "post-wrap first head block cycle one");

    auto clear = loopDirective(LoopCycleDirectiveAction::Clear, 3U, 1U, owner, wrappedEpoch, cacheState, loopEnd);
    const std::uint64_t allocationsBefore =
        allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    require(
        pipeline.applyLoopDirective(clear, {}) ==
            SongRenderLoopDirectiveStatus::Cleared,
        "post-wrap Clear accepted");
    require(!pipeline.loopCycleSnapshot().installed, "post-wrap loop policy disabled immediately");
    const auto exactFormerHole = renderFinal(pipeline,
        owner,
        output.data(),
        kMaximumBlock,
        formerHoleFrames);
    verifyFinalBlock(exactFormerHole, output.data(), owner, fingerprintSequence);
    require(
        exactFormerHole.framesProduced == formerHoleFrames,
        "post-wrap exact former 96-frame hole is fully rendered");
    require(!exactFormerHole.loopEnabled, "retired raw head emits no loop tag");
    require(exactFormerHole.loopRevision == 0U, "retired raw head emits no loop revision");
    require(!exactFormerHole.loopWrappedBeforeBlock, "retired raw head never wraps");

    while (pipeline.sourceTimelineFrame() + 1.0e-9 <
           static_cast<double>(headEnd - 48U)) {
        const std::uint64_t remaining = headEnd - 48U -
            static_cast<std::uint64_t>(pipeline.sourceTimelineFrame());
        const auto result = renderFinal(pipeline,
            owner,
            output.data(),
            kMaximumBlock,
            static_cast<std::uint32_t>(std::min<std::uint64_t>(remaining, kMaximumBlock)));
        verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
        require(!result.loopEnabled, "linear bridge stays transport-tag free");
    }
    const auto crossing = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 96U);
    verifyFinalBlock(crossing, output.data(), owner, fingerprintSequence);
    requireNear(crossing.sourceTimeline.startFrame, headEnd - 48U, 1.0e-10,
        "retired/live crossing starts 48 frames before head end");
    requireNear(crossing.sourceTimeline.endFrameExclusive, headEnd + 48U, 1.0e-10,
        "retired/live crossing ends 48 frames into generation-19 body");
    require(!crossing.loopEnabled, "retired/live crossing stays loop disabled");
    for (std::size_t frame = 0U; frame < crossing.framesProduced; ++frame) {
        const bool fromRetiredHead = frame < 48U;
        const std::uint64_t timelineFrame = fromRetiredHead
            ? headEnd - 48U + frame
            : headEnd + frame - 48U;
        const float domain = fromRetiredHead ? cachedDomain : bodyDomain;
        for (std::size_t channel = 0U; channel < 2U; ++channel) {
            require(
                output[frame * 2U + channel] ==
                    provenanceSample(timelineFrame, 0U, channel, domain),
                "exact 96-frame seam preserves cache/body PCM provenance");
        }
    }
    const auto bodyOnly = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 96U);
    verifyFinalBlock(bodyOnly, output.data(), owner, fingerprintSequence);
    requireNear(bodyOnly.sourceTimeline.startFrame, headEnd + 48U, 1.0e-10,
        "current body continues immediately after retired head");
    allocation_probe::enabled.store(false, std::memory_order_release);
    require(
        allocation_probe::count.load(std::memory_order_relaxed) == allocationsBefore,
        "post-wrap Clear bridge and head/body crossing allocate nothing");
    require(pipeline.diagnostics().needOriginalDataCount == 0U,
        "post-wrap Clear bridge never reports NeedOriginalData");
}

void allLaneStemsClearBridgePreservesMaskAndExactSeamProvenance() {
    constexpr std::uint64_t start = 0U;
    constexpr std::uint64_t headEnd = LoopHeadCache::kCapacityFrames;
    constexpr std::uint64_t loopEnd = headEnd + 512U;
    constexpr std::uint64_t ingressEpoch = 23U;
    constexpr std::uint32_t allLaneMask =
        (1U << static_cast<std::uint32_t>(kSongRenderLaneCount)) - 1U;
    constexpr float cachedDomain = 0.00390625F;
    constexpr float bodyDomain = 0.015625F;
    const SongRenderOwnership owner{200U, 46U};

    LoopHeadCache cache;
    auto key = loopKey(13U, start);
    key.stemPackageHash = 0x5A17U;
    const auto cacheState = prepareAllLaneLoopCache(cache, key, loopEnd - start, cachedDomain);
    require(cacheState.requiredLaneMask == allLaneMask,
        "all-lane bridge fixture publishes every required lane");

    SongRenderPipeline pipeline(configuration());
    require(
        pipeline.beginSong(owner, ingressEpoch, headEnd - 48U, 0) ==
            SongRenderResetStatus::Ok,
        "all-lane bridge begin immediately before head seam");
    auto stemsControls = controls(1U, MixerSourceMode::Stems);
    require(
        pipeline.submitControls(stemsControls) == SongRenderControlStatus::Accepted,
        "all-lane bridge Stems controls");
    const auto install = loopDirective(LoopCycleDirectiveAction::InstallWarmStreamed, 1U, 0U, owner, ingressEpoch, cacheState, loopEnd);
    require(
        pipeline.applyLoopDirective(install, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "all-lane streamed loop install");
    require(
        pipeline.loopCycleSnapshot().requiredLaneMask == allLaneMask,
        "pipeline installs exact all-lane mask");

    for (std::size_t lane = 0U; lane < kSongRenderLaneCount; ++lane) {
        const auto body = provenancePcm(256U, headEnd, lane, bodyDomain);
        require(
            pipeline.appendLanePcm(
                static_cast<SongRenderLane>(lane),
                ingressEpoch,
                headEnd,
                body.data(),
                body.size() / 2U).status == TimelineAppendStatus::Accepted,
            "all-lane current body append");
    }

    auto clear = loopDirective(LoopCycleDirectiveAction::Clear, 2U, 0U, owner, ingressEpoch, cacheState, loopEnd);
    require(
        pipeline.applyLoopDirective(clear, {}) ==
            SongRenderLoopDirectiveStatus::Cleared,
        "all-lane managed Clear");
    require(!pipeline.loopCycleSnapshot().installed,
        "all-lane Clear removes loop policy immediately");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0U;
    const auto crossing = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 96U);
    verifyFinalBlock(crossing, output.data(), owner, fingerprintSequence);
    requireNear(crossing.sourceTimeline.startFrame, headEnd - 48U, 1.0e-10,
        "all-lane seam starts in retired cache");
    requireNear(crossing.sourceTimeline.endFrameExclusive, headEnd + 48U, 1.0e-10,
        "all-lane seam ends in current ingress body");
    require(crossing.lateMixEndpoint.sourceMode == MixerSourceMode::Stems,
        "all-lane seam renders the Stems source");
    require((crossing.lateMixEndpoint.sourceMode == MixerSourceMode::Stems), "all-lane seam targets Stems");
    require(!crossing.loopEnabled, "all-lane retired head has no loop tag");

    for (std::size_t frame = 0U; frame < crossing.framesProduced; ++frame) {
        const bool fromRetiredHead = frame < 48U;
        const std::uint64_t timelineFrame = fromRetiredHead
            ? headEnd - 48U + frame
            : headEnd + frame - 48U;
        const float domain = fromRetiredHead ? cachedDomain : bodyDomain;
        for (std::size_t channel = 0U; channel < 2U; ++channel) {
            float expected = 0.0F;
            for (std::size_t lane = 1U; lane < kSongRenderLaneCount; ++lane) {
                expected += provenanceSample(timelineFrame, lane, channel, domain);
            }
            require(
                output[frame * 2U + channel] == expected,
                "all-lane Stems seam preserves exact cache/body PCM provenance");
        }
    }
    const auto diagnostics = pipeline.diagnostics();
    require(diagnostics.needOriginalDataCount == 0U,
        "all-lane seam never requests missing Original data");
    require(diagnostics.needStemDataCount == 0U,
        "all-lane seam never requests missing Stem data");
    require(diagnostics.stemActivationDeferrals == 0U,
        "all-lane seam never falls back while activating Stems");
    require(diagnostics.stemSafetyFallbacks == 0U,
        "all-lane seam never safety-falls back to Original");
}

void cacheOnlyOriginalEosClearFinishesExactlyOnceAndReleasesPinnedSlot() {
    constexpr std::uint64_t start = 0U;
    constexpr std::size_t decodedFrames = 2'048U;
    constexpr std::uint64_t ingressEpoch = 29U;
    const SongRenderOwnership owner{195U, 41U};
    LoopHeadCache cache;
    const auto key = loopKey(5U, start);
    const auto cacheState = prepareShortOriginalEosLoopCache(
        cache,
        key,
        decodedFrames,
        0.065F);

    SongRenderPipeline pipeline(configuration());
    require(
        pipeline.beginSong(owner, ingressEpoch, start, 0) == SongRenderResetStatus::Ok,
        "cache-only EOS begin");
    require(
        pipeline.submitControls(controls(1U)) == SongRenderControlStatus::Accepted,
        "cache-only EOS neutral controls");
    require(
        pipeline.markLaneEndOfStream(
            SongRenderLane::Original,
            ingressEpoch,
            start + decodedFrames) == SongRenderEosStatus::Accepted,
        "cache-only EOS marker");
    auto install = loopDirective(
        LoopCycleDirectiveAction::InstallWarmCacheOnly,
        1U,
        0U,
        owner,
        ingressEpoch,
        cacheState);
    install.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    install.endTimelineFrameExclusive = start + decodedFrames;
    install.headEndTimelineFrameExclusive = start + decodedFrames;
    require(
        pipeline.applyLoopDirective(install, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "cache-only Original-EOS install");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0U;
    std::size_t rendered = 0U;
    while (rendered < decodedFrames) {
        const auto result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
        verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
        rendered += result.framesProduced;
    }
    require(rendered == decodedFrames, "cache-only Original-EOS reaches exact first boundary");
    require(pipeline.loopCycleSnapshot().boundaryPending, "cache-only EOS boundary pending");
    auto wrap = loopDirective(
        LoopCycleDirectiveAction::WrapCacheOnly,
        2U,
        1U,
        owner,
        ingressEpoch,
        cacheState);
    wrap.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    wrap.endTimelineFrameExclusive = start + decodedFrames;
    wrap.headEndTimelineFrameExclusive = start + decodedFrames;
    require(
        pipeline.applyLoopDirective(wrap, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "cache-only EOS wrap");
    const auto afterWrap = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 96U);
    verifyFinalBlock(afterWrap, output.data(), owner, fingerprintSequence);
    require(afterWrap.loopCycle == 1U, "cache-only EOS wrapped cycle one");

    auto clear = wrap;
    clear.sequence = 3U;
    clear.action = LoopCycleDirectiveAction::Clear;
    require(
        pipeline.applyLoopDirective(clear, {}) ==
            SongRenderLoopDirectiveStatus::Cleared,
        "cache-only EOS Clear");

    const auto secondKey = loopKey(6U, 0U);
    static_cast<void>(prepareOriginalLoopCache(cache, secondKey, 64U, 0.02F));
    const auto thirdKey = loopKey(7U, 0U);
    require(
        cache.beginStaging(thirdKey, 1U) == LoopHeadBeginStatus::ReadersActive,
        "retired cache pins exactly one slot while its linear pass is live");

    std::size_t linearFrames = 0U;
    bool sawTerminalBlock = false;
    std::size_t linearRenderCalls = 0U;
    while (true) {
        require(++linearRenderCalls <= decodedFrames / kMaximumBlock + 4U,
            "cache-only Clear linear completion is bounded");
        const auto result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
        if (result.status == SongRenderStatus::EndOfStream) break;
        verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
        require(result.framesProduced != 0U,
            "cache-only Clear linear completion makes progress");
        require(!result.loopEnabled, "cache-only linear completion has no loop tag");
        require(!result.loopWrappedBeforeBlock, "cache-only linear completion never rewraps");
        linearFrames += result.framesProduced;
        sawTerminalBlock = sawTerminalBlock || result.endOfStreamAfterBlock;
    }
    require(
        linearFrames == decodedFrames - afterWrap.framesProduced,
        "cache-only Clear plays exactly one remaining pass to EOS");
    require(sawTerminalBlock, "cache-only Clear marks its one exact terminal block");
    const auto eosAgain = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 32U);
    require(eosAgain.status == SongRenderStatus::EndOfStream,
        "cache-only Clear remains terminal after exact EOS");
    require(eosAgain.framesProduced == 0U, "cache-only EOS emits no duplicate frame");
    require(
        cache.beginStaging(thirdKey, 1U) == LoopHeadBeginStatus::Accepted,
        "retired cache releases its pinned slot after exact EOS");
}

void physicalLengthOriginalEosCacheOnlyWrapsKeepEmittingAudiblePcm() {
    constexpr std::size_t decodedFrames = 86'544U;  // Exact 1,803 ms physical fixture.
    constexpr std::uint64_t seekFrame = 74'400U;    // Exact 1.55 s pressure-test seek.
    constexpr std::uint64_t ingressEpoch = 37U;
    constexpr std::uint32_t callbackFrames = 96U;
    constexpr float sourceLevel = 0.08F;
    const SongRenderOwnership owner{196U, 42U};
    LoopHeadCache cache;
    const auto key = loopKey(8U, 0U);
    const auto cacheState = prepareShortOriginalEosLoopCache(
        cache,
        key,
        decodedFrames,
        sourceLevel);

    SongRenderPipeline pipeline(configuration());
    require(
        pipeline.beginSong(owner, ingressEpoch, seekFrame, 0) == SongRenderResetStatus::Ok,
        "physical short Repeat begin at 1.55 seconds");
    require(
        pipeline.submitControls(controls(1U)) == SongRenderControlStatus::Accepted,
        "physical short Repeat neutral controls");
    require(
        pipeline.markLaneEndOfStream(
            SongRenderLane::Original,
            ingressEpoch,
            decodedFrames) == SongRenderEosStatus::Accepted,
        "physical short Repeat exact EOS marker");

    auto directive = loopDirective(
        LoopCycleDirectiveAction::InstallWarmCacheOnly,
        1U,
        0U,
        owner,
        ingressEpoch,
        cacheState);
    directive.boundaryMode = LoopHeadBoundaryMode::OriginalEos;
    directive.endTimelineFrameExclusive = decodedFrames;
    directive.headEndTimelineFrameExclusive = decodedFrames;
    directive.declickFrames = 32U;
    require(
        pipeline.applyLoopDirective(directive, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "physical short Repeat cache-only install");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0U;
    const auto renderToBoundary = [&]() {
        std::size_t blocks = 0U;
        while (!pipeline.loopCycleSnapshot().boundaryPending) {
            const auto result = renderFinal(pipeline,
                owner,
                output.data(),
                kMaximumBlock,
                kMaximumBlock);
            verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
            require(++blocks <= decodedFrames / kMaximumBlock + 8U,
                "physical short Repeat reaches bounded EOS");
        }
    };

    renderToBoundary();
    for (std::uint64_t cycle = 1U; cycle <= 3U; ++cycle) {
        directive.action = LoopCycleDirectiveAction::WrapCacheOnly;
        directive.sequence = cycle + 1U;
        directive.cycle = cycle;
        require(
            pipeline.applyLoopDirective(directive, cache.acquireActive()) ==
                SongRenderLoopDirectiveStatus::Accepted,
            "physical short Repeat exact cache-only wrap");

        const auto head = renderFinal(pipeline,
            owner,
            output.data(),
            kMaximumBlock,
            callbackFrames);
        verifyFinalBlock(head, output.data(), owner, fingerprintSequence);
        const PcmLevel level = pcmLevel(output.data(), head.framesProduced);
        require(head.loopEnabled && head.loopCycle == cycle,
            "physical short Repeat callback retains exact active cycle");
        require(level.rms > 0.01 && level.peak > 0.02F,
            "physical short Repeat callback remains audibly non-silent after wrap");
        renderToBoundary();
    }
    require(
        pipeline.diagnostics().needOriginalDataCount == 0U,
        "physical short Repeat cache never asks a retired decoder for PCM");
    require(
        pipeline.diagnostics().loopWraps == 3U,
        "physical short Repeat proves all three cache-only wraps");
}

void processedTempoPitchClearCrossesRetiredHeadIntoLiveBody() {
    constexpr std::uint64_t start = 0U;
    constexpr std::uint64_t headEnd = LoopHeadCache::kCapacityFrames;
    constexpr std::uint64_t loopEnd = headEnd + 32'768U;
    for (const float tempo : {0.5F, 2.0F}) {
        const std::uint64_t revision = tempo < 1.0F ? 8U : 9U;
        const std::uint64_t ingressEpoch = tempo < 1.0F ? 51U : 52U;
        const SongRenderOwnership owner{
            tempo < 1.0F ? 196U : 197U,
            tempo < 1.0F ? 42U : 43U};
        LoopHeadCache cache;
        const auto key = loopKey(revision, start);
        const auto cacheState = prepareOriginalLoopCache(cache, key, loopEnd - start, 0.045F);
        SongRenderPipeline pipeline(configuration(65'536U));
        require(
            pipeline.beginSong(owner, ingressEpoch, headEnd - 2'048U, 0) ==
                SongRenderResetStatus::Ok,
            "processed retired-head begin");
        require(
            pipeline.submitControls(controls(1U, MixerSourceMode::Original, tempo, 4.0F)) ==
                SongRenderControlStatus::Accepted,
            "processed retired-head tune controls");
        const auto install = loopDirective(LoopCycleDirectiveAction::InstallWarmStreamed, 1U, 0U, owner, ingressEpoch, cacheState, loopEnd);
        require(
            pipeline.applyLoopDirective(install, cache.acquireActive()) ==
                SongRenderLoopDirectiveStatus::Accepted,
            "processed retired-head streamed install");
        const auto body = constantPcm(32'768U, 0.11F);
        require(
            pipeline.appendLanePcm(
                SongRenderLane::Original,
                ingressEpoch,
                headEnd,
                body.data(),
                body.size() / 2U).status == TimelineAppendStatus::Accepted,
            "processed retired-head live body");

        std::array<float, kMaximumBlock * 2U> output{};
        std::uint64_t fingerprintSequence = 0U;
        const auto processedBeforeClear = renderFinal(pipeline,
            owner,
            output.data(),
            kMaximumBlock,
            128U);
        verifyFinalBlock(processedBeforeClear, output.data(), owner, fingerprintSequence);
        require(processedBeforeClear.tuningProcessed, "DSP active before managed Clear");
        auto clear = loopDirective(LoopCycleDirectiveAction::Clear, 2U, 0U, owner, ingressEpoch, cacheState, loopEnd);
        require(
            pipeline.applyLoopDirective(clear, {}) ==
                SongRenderLoopDirectiveStatus::Cleared,
            "processed managed Clear accepted");

        bool crossedHeadEnd = false;
        for (std::size_t block = 0U; block < 64U && !crossedHeadEnd; ++block) {
            const auto result = renderFinal(pipeline,
                owner,
                output.data(),
                kMaximumBlock,
                kMaximumBlock);
            verifyFinalBlock(result, output.data(), owner, fingerprintSequence);
            require(result.tuningProcessed, "tempo/pitch stays on processed timeline after Clear");
            require(!result.loopEnabled, "processed retired head emits no transport loop tag");
            require(!result.loopWrappedBeforeBlock, "processed retired head never wraps");
            crossedHeadEnd = result.sourceTimeline.endFrameExclusive >
                static_cast<double>(headEnd);
        }
        require(crossedHeadEnd, "tempo/pitch crosses retired head into live body");
        const auto liveBody = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 96U);
        verifyFinalBlock(liveBody, output.data(), owner, fingerprintSequence);
        require(liveBody.tuningProcessed, "live body remains tempo/pitch processed");
        require(!liveBody.loopEnabled, "live body remains loop disabled");
        require(pipeline.diagnostics().needOriginalDataCount == 0U,
            "tempo/pitch retired-head crossing never requests missing Original data");
        require(pipeline.diagnostics().internalFailures == 0U,
            "tempo/pitch retired-head crossing has no DSP/mixer failure");
    }
}

void managedRepeatHeadOwnsTheWrappedPrefixOverAnAtomicContinuationHead() {
    for (const std::uint64_t repeatStart : {48'000U, 96'000U}) {
        const SongRenderOwnership owner{201U, 54U};
        LoopHeadCache repeatCache, continuationCache;
        const auto repeatState = prepareOriginalLoopCache(repeatCache, loopKey(1U, repeatStart),
            LoopHeadCache::kCapacityFrames, .03F);
        auto continuationKey = repeatState.key;
        continuationKey.loopRevision = 2U;
        continuationKey.startTimelineFrame = 60'000U;
        continuationKey.endTimelineFrameExclusive = 252'000U;
        static_cast<void>(prepareOriginalLoopCache(continuationCache, continuationKey,
            LoopHeadCache::kCapacityFrames, .03F));
        SongRenderPipeline pipeline(configuration());
        require(pipeline.beginSong(owner, owner.generation, 84'000U, 0, 144'000U) == SongRenderResetStatus::Ok &&
            pipeline.submitControls(controls(1U, MixerSourceMode::Original)) == SongRenderControlStatus::Accepted &&
            pipeline.bindRetainedLoopHead(owner, continuationCache.acquireActive()),
            "playing continuation retains its independently bounded raw source head");
        auto directive = loopDirective(LoopCycleDirectiveAction::InstallWarmCacheOnly, 1U, 0U,
            owner, owner.generation, repeatState, 144'000U);
        directive.headEndTimelineFrameExclusive = 144'000U;
        require(pipeline.applyLoopDirective(directive, repeatCache.acquireActive()) == SongRenderLoopDirectiveStatus::Accepted,
            "Repeat accepts the exact larger-prefix head without losing the current pass");
        std::array<float, kMaximumBlock * 2U> output{};
        std::uint64_t fingerprintSequence = 0U;
        unsigned blocks = 0U;
        while (!pipeline.loopCycleSnapshot().boundaryPending) {
            const auto rendered = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
            verifyFinalBlock(rendered, output.data(), owner, fingerprintSequence);
            require(++blocks < 2'000U, "the continued pass reaches its exact trimmed end");
        }
        directive.action = LoopCycleDirectiveAction::WrapCacheOnly;
        directive.sequence = 2U;
        directive.cycle = 1U;
        require(pipeline.applyLoopDirective(directive, repeatCache.acquireActive()) == SongRenderLoopDirectiveStatus::Accepted,
            "the first exact managed cache wrap commits");
        const auto wrapped = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
        verifyFinalBlock(wrapped, output.data(), owner, fingerprintSequence);
        require(wrapped.loopCycle == 1U && wrapped.sourceTimeline.startFrame == static_cast<double>(repeatStart),
            "the accepted Repeat head supplies its prefix before the continuation head begins");
        require(std::abs(output[(wrapped.framesProduced - 1U) * 2U] - .03F) < .000001F,
            "wrapped Repeat delivers actual accepted source PCM");
    }
}

void managedOriginalEosRejectsAuthoredBoundaryMovesWithoutReprime() {
    constexpr std::uint64_t ingressEpoch = 53U;
    const SongRenderOwnership owner{200U, 46U};
    LoopHeadCache cache;
    const auto key = loopKey(13U, 0U);
    const auto cacheState = prepareOriginalLoopCache(cache, key, 8'192U, 0.03F);
    SongRenderPipeline pipeline(configuration());
    require(
        pipeline.beginSong(owner, ingressEpoch, 0U, 0) == SongRenderResetStatus::Ok,
        "live loop edit begin");
    require(
        pipeline.submitControls(controls(1U, MixerSourceMode::Original, 1.25F, 4.0F)) ==
            SongRenderControlStatus::Accepted,
        "live loop edit tuning controls");
    const auto live = constantPcm(16'384U, 0.03F);
    require(
        pipeline.appendLanePcm(
            SongRenderLane::Original,
            ingressEpoch,
            0U,
            live.data(),
            live.size() / 2U).status == TimelineAppendStatus::Accepted,
        "live loop edit PCM");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0U;
    const auto before = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 128U);
    verifyFinalBlock(before, output.data(), owner, fingerprintSequence);
    require(before.tuningProcessed, "live loop edit starts on the processed timeline");
    const std::uint64_t primesBeforePolicyMove = pipeline.diagnostics().tunePrimes;
    const std::uint64_t resetsBeforePolicyMove =
        pipeline.mixerDiagnostics().discontinuityResets;
    require(primesBeforePolicyMove == 1U, "live loop edit establishes one tuning prime");

    const auto install = loopDirective(
        LoopCycleDirectiveAction::InstallWarmCacheOnly,
        1U,
        0U,
        owner,
        ingressEpoch,
        cacheState);
    require(
        pipeline.applyLoopDirective(install, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted,
        "live loop edit warm install");
    const auto afterInstall = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 128U);
    verifyFinalBlock(afterInstall, output.data(), owner, fingerprintSequence);
    require(afterInstall.loopEnabled, "live loop edit publishes the warm range");
    require(
        pipeline.diagnostics().tunePrimes == primesBeforePolicyMove,
        "warm loop range publication must not re-prime active tuning");
    require(
        pipeline.mixerDiagnostics().discontinuityResets == resetsBeforePolicyMove,
        "warm loop range publication must not reset post-DSP state");

    auto retarget = install;
    retarget.sequence = 2U;
    retarget.loopRevision = 14U;
    const double sourceBeforeRetarget = pipeline.sourceTimelineFrame();
    retarget.endTimelineFrameExclusive =
        static_cast<std::uint64_t>(sourceBeforeRetarget);
    retarget.headEndTimelineFrameExclusive = retarget.endTimelineFrameExclusive;
    require(
        pipeline.applyLoopDirective(retarget, cache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::CacheMismatch,
        "managed Original-EOS cannot be retargeted to an authored B");
    const auto retargeted = pipeline.loopCycleSnapshot();
    require(retargeted.loopRevision == install.loopRevision &&
            retargeted.endTimelineFrameExclusive == install.endTimelineFrameExclusive &&
            retargeted.ingressEpoch == ingressEpoch,
        "rejected authored B preserves the installed Original-EOS boundary");
    require(pipeline.sourceTimelineFrame() == sourceBeforeRetarget,
        "same-cache B retarget never moves transport");
    require(pipeline.diagnostics().tunePrimes == primesBeforePolicyMove,
        "same-cache B retarget must not re-prime tuning");
    require(pipeline.mixerDiagnostics().discontinuityResets == resetsBeforePolicyMove,
        "same-cache B retarget must not reset post-DSP state");

}

void retiredHeadRcuSurvivesRejectionAndHardDropsOnReplacementOrSeek() {
    const auto exercise = [](bool replaceLoop) {
        constexpr std::uint64_t ingressEpoch = 61U;
        const SongRenderOwnership owner{
            replaceLoop ? 198U : 199U,
            replaceLoop ? 44U : 45U};
        LoopHeadCache cache;
        const auto firstKey = loopKey(10U, 0U);
        const auto firstCache = prepareOriginalLoopCache(cache, firstKey, 64U, 0.03F);
        SongRenderPipeline pipeline(configuration());
        require(
            pipeline.beginSong(owner, ingressEpoch, 0U, 0) == SongRenderResetStatus::Ok,
            "retired RCU begin");
        require(
            pipeline.submitControls(controls(1U)) == SongRenderControlStatus::Accepted,
            "retired RCU controls");
        const auto firstInstall = loopDirective(
            LoopCycleDirectiveAction::InstallWarmCacheOnly,
            1U,
            0U,
            owner,
            ingressEpoch,
            firstCache);
        require(
            pipeline.applyLoopDirective(firstInstall, cache.acquireActive()) ==
                SongRenderLoopDirectiveStatus::Accepted,
            "retired RCU first install");
        std::array<float, kMaximumBlock * 2U> output{};
        std::uint64_t fingerprintSequence = 0U;
        const auto first = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 8U);
        verifyFinalBlock(first, output.data(), owner, fingerprintSequence);
        auto clear = loopDirective(
            LoopCycleDirectiveAction::Clear,
            2U,
            0U,
            owner,
            ingressEpoch,
            firstCache);
        require(
            pipeline.applyLoopDirective(clear, {}) ==
                SongRenderLoopDirectiveStatus::Cleared,
            "retired RCU Clear");

        const auto secondKey = loopKey(11U, 0U);
        const auto secondCache = prepareOriginalLoopCache(cache, secondKey, 64U, 0.04F);
        const auto thirdKey = loopKey(12U, 0U);
        auto replacement = loopDirective(
            LoopCycleDirectiveAction::InstallWarmCacheOnly,
            3U,
            0U,
            owner,
            ingressEpoch,
            secondCache);
        auto rejected = replacement;
        ++rejected.cacheChecksum;
        require(
            pipeline.applyLoopDirective(rejected, cache.acquireActive()) ==
                SongRenderLoopDirectiveStatus::CacheMismatch,
            "invalid replacement is rejected before touching retired RCU view");
        require(
            cache.beginStaging(thirdKey, 1U) == LoopHeadBeginStatus::ReadersActive,
            "rejected replacement leaves retired raw head pinned");

        if (replaceLoop) {
            require(
                pipeline.applyLoopDirective(replacement, cache.acquireActive()) ==
                    SongRenderLoopDirectiveStatus::Accepted,
                "validated replacement installs atomically");
        } else {
            const SongRenderOwnership rebound{owner.selectionToken, owner.generation + 1U};
            require(
                pipeline.seek(rebound, ingressEpoch + 1U, 8U, 8) ==
                    SongRenderResetStatus::Ok,
                "seek hard-drops retired raw head");
        }
        require(
            cache.beginStaging(thirdKey, 1U) == LoopHeadBeginStatus::Accepted,
            "accepted replacement or seek releases retired RCU slot immediately");
    };

    exercise(true);
    exercise(false);
}

void newerEquivalentHeadRebindPreservesActiveOriginalWhileStemsArePending() {
    for (unsigned fault = 0U; fault < 3U; ++fault) {
        constexpr SongRenderOwnership owner{2'802U, 73U};
        LoopHeadCache cache;
        const auto firstKey = loopKey(10U, 0U);
        static_cast<void>(prepareOriginalLoopCache(cache, firstKey, 1'024U, 0.03F));
        SongRenderPipeline source(configuration()), active(configuration());
        require(source.beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok &&
            source.submitControls(controls(1U)) == SongRenderControlStatus::Accepted &&
            source.bindRetainedLoopHead(owner, cache.acquireActive(firstKey)), "Original source owns its exact raw head");
        require(active.prepareAtomicLoopContinuationFrom(source, owner, true, 0U, 512U, 0U, 0U, 1U, &cache) ==
            SongRenderLoopStatus::Accepted, "active physical Original loop clones the exact raw head");
        source.releaseRetiredLoopHeadReaders();
        require(active.submitControls(controls(2U, MixerSourceMode::Stems)) == SongRenderControlStatus::Accepted,
            "Stems can be requested before their independent raw prefix arrives");
        auto older = fault == 0U ? cache.acquireActive(firstKey) : LoopHeadCache::ReadView{};
        auto nextKey = firstKey;
        nextKey.loopRevision = 11U;
        if (fault == 1U) ++nextKey.originalContentHash;
        static_cast<void>(prepareOriginalLoopCache(cache, nextKey, fault == 2U ? 768U : 1'024U, 0.03F));
        const auto cursor = active.sourceTimelineFrame();
        const auto rebound = active.bindRetainedLoopHead(owner, cache.acquireActive(nextKey));
        require(rebound == (fault == 0U), "only a same-source fully covering replacement can rebind the active head");
        require(active.sourceTimelineFrame() == cursor, "head migration cannot retime the active source");
        if (fault == 0U) {
            require(!active.bindRetainedLoopHead(owner, std::move(older)) &&
                !active.bindRetainedLoopHead(owner, cache.acquireActive(nextKey)),
                "older or equal same-mask heads cannot replace the active view");
        }
        auto thirdKey = firstKey;
        thirdKey.loopRevision = 12U;
        const auto admitted = cache.beginStaging(thirdKey, 1U);
        require(admitted == (fault == 0U ? LoopHeadBeginStatus::Accepted : LoopHeadBeginStatus::ReadersActive),
            "valid migration releases the old slot while rejected replacement preserves its live reader");
        std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
        const auto rendered = active.renderMultibus(owner, output.data(), kMaximumBlock, 64U);
        require(rendered.status == SongRenderStatus::Rendered && rendered.framesProduced == 64U &&
            rendered.sourceTimeline.startFrame == cursor && rendered.lateMixEndpoint.sourceMode == MixerSourceMode::Original,
            "pending Stems keeps actual Original samples on the same active timeline");
        for (std::size_t frame = 0U; frame < rendered.framesProduced; ++frame)
            require(output[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(0U)] == 0.03F &&
                output[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(1U)] == 0.03F,
                "head replacement preserves independent Original PCM exactly");
    }
}

void ingressEpochIsIndependentFromAudibleOwnership() {
    SongRenderPipeline pipeline(configuration());
    const SongRenderOwnership owner{90, 10};
    constexpr std::uint64_t ingressEpoch = 100;
    require(
        pipeline.beginSong(owner, ingressEpoch, 0, 0) == SongRenderResetStatus::Ok,
        "begin split ingress epoch");
    require(pipeline.ownership().generation == owner.generation, "split owner generation");
    require(pipeline.ingressEpoch() == ingressEpoch, "split ingress epoch stored");
    require(
        pipeline.submitControls(controls(1)) == SongRenderControlStatus::Accepted,
        "split ingress controls");

    const auto pcm = tone(512, 0, 0.03F);
    const auto ownerGenerationAppend = pipeline.appendLanePcm(
        SongRenderLane::Original,
        owner.generation,
        0,
        pcm.data(),
        16);
    require(
        ownerGenerationAppend.status == TimelineAppendStatus::StaleGeneration,
        "owner generation cannot masquerade as ingress epoch");
    const auto currentAppend = pipeline.appendLanePcm(
        SongRenderLane::Original,
        ingressEpoch,
        0,
        pcm.data(),
        pcm.size() / 2U);
    require(currentAppend.status == TimelineAppendStatus::Accepted, "split ingress accepted");

    std::array<float, kMaximumBlock * 2U> output{};
    std::uint64_t fingerprintSequence = 0;
    const auto first = renderFinal(pipeline, owner, output.data(), kMaximumBlock, 64);
    verifyFinalBlock(first, output.data(), owner, fingerprintSequence);
    require(
        first.payloadFingerprint.generation == owner.generation,
        "audible fingerprint uses owner generation, not ingress epoch");

    const SongRenderOwnership reboundOwner{owner.selectionToken, 11};
    constexpr std::uint64_t reboundIngressEpoch = 101;
    require(
        pipeline.seek(reboundOwner, reboundIngressEpoch, 64, 64) == SongRenderResetStatus::Ok,
        "split ingress seek");
    require(
        pipeline.ingressEpoch() == reboundIngressEpoch,
        "seek advances ingress independently");
    const auto staleIngressAppend = pipeline.appendLanePcm(
        SongRenderLane::Original,
        ingressEpoch,
        64,
        pcm.data() + 128,
        16);
    require(
        staleIngressAppend.status == TimelineAppendStatus::StaleGeneration,
        "seek rejects prior ingress epoch");
    const auto reboundAppend = pipeline.appendLanePcm(
        SongRenderLane::Original,
        reboundIngressEpoch,
        64,
        pcm.data() + 128,
        256);
    require(
        reboundAppend.status == TimelineAppendStatus::Accepted,
        "seek accepts rebound ingress epoch");
    const auto rebound = renderFinal(pipeline, reboundOwner, output.data(), kMaximumBlock, 64);
    verifyFinalBlock(rebound, output.data(), reboundOwner, fingerprintSequence);
    require(
        rebound.payloadFingerprint.generation == reboundOwner.generation,
        "rebound fingerprint remains public ownership");
}

void applicationEpochReappliesCanonicalControlsWithoutChangingEvidence() {
    SongRenderPipeline pipeline(configuration(32'768));
    const SongRenderOwnership firstOwner{100U, 40U};
    require(
        pipeline.beginSong(firstOwner, (firstOwner).generation, 0U, 0) == SongRenderResetStatus::Ok,
        "application epoch begin owner");

    SongRenderControlSnapshot initial = controls(7U, MixerSourceMode::Original);
    initial.applicationEpoch = 100U;
    require(
        pipeline.submitControls(initial) == SongRenderControlStatus::Accepted,
        "first epoch-aware control accepted");

    SongRenderControlSnapshot sameCanonical = initial;
    sameCanonical.applicationEpoch = 101U;
    require(
        pipeline.submitControls(sameCanonical) == SongRenderControlStatus::Accepted,
        "higher application epoch reapplies same canonical revision");

    SongRenderControlSnapshot staleApplication = sameCanonical;
    require(
        pipeline.submitControls(staleApplication) == SongRenderControlStatus::StaleRevision,
        "equal application epoch rejected");
    staleApplication.applicationEpoch = 100U;
    require(
        pipeline.submitControls(staleApplication) == SongRenderControlStatus::StaleRevision,
        "older application epoch rejected");

    SongRenderControlSnapshot missingEpoch = sameCanonical;
    missingEpoch.applicationEpoch = 0U;
    require(pipeline.submitControls(missingEpoch) == SongRenderControlStatus::StaleRevision,
        "a positive application epoch is mandatory");
    SongRenderControlSnapshot nextCanonical = controls(8U, MixerSourceMode::Original);
    nextCanonical.applicationEpoch = 102U;
    require(pipeline.submitControls(nextCanonical) == SongRenderControlStatus::Accepted,
        "new canonical revision with a newer application epoch is accepted");
    require(pipeline.submitControls(nextCanonical) == SongRenderControlStatus::StaleRevision,
        "duplicate application remains stale");
    SongRenderControlSnapshot repeatedCanonical = nextCanonical;
    repeatedCanonical.applicationEpoch = 103U;
    require(pipeline.submitControls(repeatedCanonical) == SongRenderControlStatus::Accepted,
        "new application can repeat the canonical revision");
    SongRenderControlSnapshot canonicalRegression = controls(7U, MixerSourceMode::Original);
    canonicalRegression.applicationEpoch = 104U;
    require(
        pipeline.submitControls(canonicalRegression) == SongRenderControlStatus::StaleRevision,
        "new application epoch cannot admit canonical regression");

    SongRenderControlSnapshot tuned = controls(
        9U,
        MixerSourceMode::Original,
        1.25F,
        7.0F);
    tuned.applicationEpoch = 104U;
    tuned.mixer.musicGain = 0.42F;
    require(
        pipeline.submitControls(tuned) == SongRenderControlStatus::Accepted,
        "rejected regression does not consume application epoch");

    const SongRenderOwnership reboundOwner{101U, 41U};
    require(
        pipeline.beginSong(
            reboundOwner,
            reboundOwner.generation,
            0U,
            pipeline.nextOutputClockFrame()) == SongRenderResetStatus::Ok,
        "application epoch owner reset");
    const auto retainedEndpoint = pipeline.prepareLateMixEndpoint(reboundOwner);
    require(retainedEndpoint.valid(), "owner reset rebinds a valid mixer endpoint");
    require(
        retainedEndpoint.canonicalControlRevision == 9U &&
            retainedEndpoint.sourceIntentRevision == 9U &&
            retainedEndpoint.stemIntentRevision == 9U,
        "owner reset retains canonical control identity");
    requireNear(
        retainedEndpoint.musicGain,
        0.42,
        1.0e-7,
        "owner reset retains mixer gain");

    SongRenderControlSnapshot reboundApplication = tuned;
    reboundApplication.applicationEpoch = 105U;
    require(
        pipeline.submitControls(reboundApplication) == SongRenderControlStatus::Accepted,
        "new owner accepts same canonical revision at newer application epoch");

    constexpr std::size_t frames = 20'000U;
    const auto pcm = tone(frames, 0U, 0.06F, 440.0);
    appendAllLanes(pipeline, reboundOwner.generation, 0U, pcm);
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
    const SongRenderResult rendered = pipeline.renderMultibus(
        reboundOwner,
        output.data(),
        kMaximumBlock,
        kMaximumBlock);
    require(rendered.status == SongRenderStatus::Rendered, "rebound controls render");
    require(rendered.ownership.selectionToken == reboundOwner.selectionToken &&
            rendered.ownership.generation == reboundOwner.generation,
        "owner reset rebinds rendered ownership");
    require(
        rendered.controlRevision == 9U &&
            rendered.lateMixEndpoint.canonicalControlRevision == 9U,
        "application epoch never replaces canonical render evidence");
    requireNear(
        rendered.lateMixEndpoint.musicGain,
        0.42,
        1.0e-7,
        "rebound endpoint retains intended mixer gain");
    const auto tune = pipeline.timePitchDiagnostics();
    requireNear(tune.tempo, 1.25, 0.0, "owner reset retains tempo");
    requireNear(tune.pitchSemitones, 7.0, 0.0, "owner reset retains key");
    require(rendered.tuningProcessed, "owner reset applies retained non-neutral tuning");
}

void productionMultibusPreservesEveryAlignedLaneBeforeCallbackMix() {
    SongRenderPipeline pipeline(configuration());
    const SongRenderOwnership owner{101U, 41U};
    require(
        pipeline.beginSong(owner, (owner).generation, 0U, 0) == SongRenderResetStatus::Ok,
        "begin production multibus owner");
    SongRenderControlSnapshot multibusControls = controls(1U, MixerSourceMode::Stems);
    multibusControls.mixer.stems[0U].gain = 0.25F;
    multibusControls.mixer.stems[1U].gain = 0.50F;
    multibusControls.mixer.stems[1U].muted = true;
    multibusControls.mixer.stems[2U].gain = 0.75F;
    multibusControls.mixer.stems[2U].soloed = true;
    multibusControls.mixer.stems[3U].gain = 1.00F;
    multibusControls.mixer.stems[4U].gain = 1.25F;
    multibusControls.mixer.stems[5U].gain = 1.50F;
    require(
        pipeline.submitControls(multibusControls) ==
            SongRenderControlStatus::Accepted,
        "production multibus controls");
    std::array<float, stemwise::audio::kStemLaneCount> previewGains{
        0.25F, 0.50F, 1.37F, 1.00F, 1.25F, 1.50F};
    require(
        pipeline.submitStemGainPreview(
            owner,
            3U,
            7U,
            1U << 2U,
            previewGains) == SongRenderStemGainPreviewStatus::Accepted,
        "production multibus gain preview");
    const std::uint64_t allocationsBeforeImmediateEndpoint =
        allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    const auto immediateEndpoint = pipeline.prepareLateMixEndpoint(owner);
    allocation_probe::enabled.store(false, std::memory_order_release);
    require(
        allocation_probe::count.load(std::memory_order_relaxed) ==
            allocationsBeforeImmediateEndpoint,
        "queue-independent late endpoint allocates nothing");
    require(immediateEndpoint.valid(), "queue-independent late endpoint valid");
    require(
        immediateEndpoint.sourceMode == MixerSourceMode::Stems &&
            immediateEndpoint.effectiveStemGains[2U] == previewGains[2U] &&
            immediateEndpoint.previewGains == previewGains &&
            immediateEndpoint.previewPublicationSequence == 3U &&
            immediateEndpoint.previewCoversOfferSequence == 7U,
        "queue-independent late endpoint carries exact admitted preview");

    constexpr std::size_t frames = 512U;
    std::array<std::vector<float>, kSongRenderLaneCount> lanePcm{};
    for (std::size_t lane = 0U; lane < lanePcm.size(); ++lane) {
        lanePcm[lane].resize(frames * 2U);
        for (std::size_t frame = 0U; frame < frames; ++frame) {
            lanePcm[lane][frame * 2U] =
                0.01F * static_cast<float>(lane + 1U) +
                static_cast<float>(frame % 13U) / 10'000.0F;
            lanePcm[lane][frame * 2U + 1U] =
                -0.01F * static_cast<float>(lane + 1U) -
                static_cast<float>(frame % 17U) / 10'000.0F;
        }
        const auto appended = pipeline.appendLanePcm(
            static_cast<SongRenderLane>(lane),
            owner.generation,
            0U,
            lanePcm[lane].data(),
            frames);
        require(appended.status == TimelineAppendStatus::Accepted, "multibus lane append");
    }

    std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
    const SongRenderResult rendered = pipeline.renderMultibus(
        owner,
        output.data(),
        kMaximumBlock,
        kMaximumBlock);
    require(rendered.status == SongRenderStatus::Rendered, "multibus direct render status");
    require(rendered.framesProduced == kMaximumBlock, "multibus direct frame count");
    require(
        rendered.stemBusReadyMask == kStemwiseAllStemBusesReadyMask,
        "multibus exact all-stem readiness mask");
    require(rendered.lateMixEndpoint.valid(), "multibus exact late endpoint valid");
    require(
        rendered.lateMixEndpoint.canonicalControlRevision == 1U &&
            rendered.lateMixEndpoint.sourceIntentRevision == 1U &&
            rendered.lateMixEndpoint.stemIntentRevision == 1U,
        "multibus exact canonical intent tuple");
    require(
        rendered.lateMixEndpoint.sourceMode == MixerSourceMode::Stems,
        "multibus exact safe Stems endpoint");
    for (std::size_t lane = 0U; lane < stemwise::audio::kStemLaneCount; ++lane) {
        requireNear(
            rendered.lateMixEndpoint.effectiveStemGains[lane],
            lane == 2U ? previewGains[lane] : 0.0F,
            0.0,
            "multibus mute solo preview endpoint policy");
    }
    require(
        rendered.lateMixEndpoint.previewPublicationSequence == 3U &&
            rendered.lateMixEndpoint.previewCoversOfferSequence == 7U &&
            rendered.lateMixEndpoint.previewActiveMask == (1U << 2U) &&
            rendered.lateMixEndpoint.previewGains == previewGains,
        "multibus exact preview identity");
    std::uint64_t expectedPayloadHash = kFnvOffset;
    for (const float sample : output) hashValue(expectedPayloadHash, quantize(sample));
    require(rendered.payloadFingerprint.hash == (expectedPayloadHash == 0U ? kFnvOffset : expectedPayloadHash),
            "multibus payload hash preserves quantization and byte order");
    for (std::size_t frame = 0U; frame < kMaximumBlock; ++frame) {
        const float *payload = output.data() + frame * kStemwiseMultibusStride;
        requireNear(
            payload[stemwiseOriginalChannel(0U)],
            lanePcm[0U][frame * 2U],
            0.0,
            "multibus Original left exact");
        requireNear(
            payload[stemwiseOriginalChannel(1U)],
            lanePcm[0U][frame * 2U + 1U],
            0.0,
            "multibus Original right exact");
        for (std::size_t stem = 0U; stem < 6U; ++stem) {
            requireNear(
                payload[stemwiseStemChannel(stem, 0U)],
                lanePcm[stem + 1U][frame * 2U],
                0.0,
                "multibus stem left exact");
            requireNear(
                payload[stemwiseStemChannel(stem, 1U)],
                lanePcm[stem + 1U][frame * 2U + 1U],
                0.0,
                "multibus stem right exact");
        }
        requireNear(
            payload[kStemwiseMultibusClickChannel],
            0.0,
            0.0,
            "multibus metronome off");
        constexpr std::uint32_t startupDeclickFrames =
            stemwise::audio::MixerControlSnapshot{}.defaultDeclickFrames;
        const float expectedEnvelope = frame < startupDeclickFrames
            ? static_cast<float>(frame) /
                static_cast<float>(startupDeclickFrames - 1U)
            : 1.0F;
        requireNear(
            payload[kStemwiseMultibusEnvelopeChannel],
            expectedEnvelope,
            8.0e-6,
            "multibus startup declick envelope exact");
    }

    SongRenderPipeline safeOriginal(configuration());
    const SongRenderOwnership safeOwner{102U, 42U};
    require(
        safeOriginal.beginSong(safeOwner, (safeOwner).generation, 0U, 0) == SongRenderResetStatus::Ok,
        "begin safe Original multibus owner");
    require(
        safeOriginal.submitControls(controls(1U, MixerSourceMode::Stems)) ==
            SongRenderControlStatus::Accepted,
        "safe Original requested Stems");
    require(
        safeOriginal.appendLanePcm(
            SongRenderLane::Original,
            safeOwner.generation,
            0U,
            lanePcm[0U].data(),
            frames).status == TimelineAppendStatus::Accepted,
        "safe Original lane append");
    output.fill(0.0F);
    const SongRenderResult fallback = safeOriginal.renderMultibus(
        safeOwner,
        output.data(),
        kMaximumBlock,
        kMaximumBlock);
    require(fallback.status == SongRenderStatus::Rendered, "safe Original multibus render");
    require(fallback.stemBusReadyMask == 0U, "missing stems never marked ready");
    require(fallback.lateMixEndpoint.valid(), "safe Original late endpoint valid");
    require(
        fallback.lateMixEndpoint.sourceMode == MixerSourceMode::Original,
        "missing stems force exact safe Original endpoint");
    for (std::size_t frame = 0U; frame < kMaximumBlock; ++frame) {
        const float *payload = output.data() + frame * kStemwiseMultibusStride;
        for (std::size_t stem = 0U; stem < 6U; ++stem) {
            require(payload[stemwiseStemChannel(stem, 0U)] == 0.0F, "missing stem left zero");
            require(payload[stemwiseStemChannel(stem, 1U)] == 0.0F, "missing stem right zero");
        }
    }

    SongRenderPipeline opportunistic(configuration());
    const SongRenderOwnership opportunisticOwner{103U, 43U};
    require(
        opportunistic.beginSong(opportunisticOwner, opportunisticOwner.generation, 0U, 0) == SongRenderResetStatus::Ok,
        "begin opportunistic multibus owner");
    require(
        opportunistic.submitControls(controls(1U, MixerSourceMode::Original)) ==
            SongRenderControlStatus::Accepted,
        "opportunistic Original controls");
    for (std::size_t lane = 0U; lane < lanePcm.size(); ++lane) {
        require(
            opportunistic.appendLanePcm(
                static_cast<SongRenderLane>(lane),
                opportunisticOwner.generation,
                0U,
                lanePcm[lane].data(),
                frames).status == TimelineAppendStatus::Accepted,
            "opportunistic multibus lane append");
    }
    output.fill(0.0F);
    const SongRenderResult opportunisticResult = opportunistic.renderMultibus(
        opportunisticOwner,
        output.data(),
        kMaximumBlock,
        kMaximumBlock);
    require(
        opportunisticResult.status == SongRenderStatus::Rendered &&
            opportunisticResult.lateMixEndpoint.sourceMode == MixerSourceMode::Original,
        "opportunistic payload remains audibly Original");
    require(
        opportunisticResult.stemBusReadyMask == kStemwiseAllStemBusesReadyMask,
        "Original target still carries ready stem buses through reserve");
    for (std::size_t stem = 0U; stem < 6U; ++stem) {
        require(
            output[stemwiseStemChannel(stem, 0U)] == lanePcm[stem + 1U][0U] &&
                output[stemwiseStemChannel(stem, 1U)] == lanePcm[stem + 1U][1U],
            "opportunistic stem payload remains exact");
    }
}

void disabledAtomicCutoverRetiresAStaleManagedLoop() {
    constexpr std::size_t frames = 1'024U;
    constexpr std::uint64_t ingressEpoch = 61U;
    const SongRenderOwnership owner{204U, 50U};

    CurrentSongPcmCache currentSong;
    const CurrentSongCacheKey currentKey{
        owner.selectionToken,
        owner.generation,
        0x1111U,
        0x2222U,
        0x3333U,
    };
    require(
        currentSong.begin(currentKey) == CurrentSongCacheBeginStatus::Accepted,
        "atomic-clear current-song cache begins");
    for (std::size_t lane = 0U; lane < kSongRenderLaneCount; ++lane) {
        const auto pcm = constantPcm(frames, 0.02F + static_cast<float>(lane) * 0.001F);
        const auto offered = currentSong.offer(currentKey, lane, 0U, pcm.data(), frames);
        require(
            offered.status == CurrentSongCacheOfferStatus::Accepted &&
                offered.inputFramesConsumed == frames,
            "atomic-clear current-song lane accepted");
        require(
            currentSong.sealLane(currentKey, lane, frames) ==
                CurrentSongCacheSealStatus::Accepted,
            "atomic-clear current-song lane sealed");
    }
    require(
        currentSong.complete(((1U << kSongRenderLaneCount) - 1U)),
        "atomic-clear current-song cache is complete");

    LoopHeadCache managedCache;
    const auto managedKey = loopKey(21U, 0U);
    const auto managedSnapshot = prepareOriginalLoopCache(managedCache, managedKey, frames, 0.04F);
    SongRenderPipeline pipeline(configuration());
    require(
        pipeline.beginSong(owner, ingressEpoch, 0U, 0) == SongRenderResetStatus::Ok,
        "atomic-clear owner begins");
    require(
        pipeline.submitControls(controls(1U)) == SongRenderControlStatus::Accepted,
        "atomic-clear controls accepted");
    require(
        pipeline.bindCurrentSongPcmCache(owner, &currentSong),
        "atomic-clear complete current-song cache bound");
    const auto install = loopDirective(
        LoopCycleDirectiveAction::InstallWarmCacheOnly,
        1U,
        0U,
        owner,
        ingressEpoch,
        managedSnapshot);
    require(
        pipeline.applyLoopDirective(install, managedCache.acquireActive()) ==
            SongRenderLoopDirectiveStatus::Accepted &&
            pipeline.loopCycleSnapshot().installed,
        "atomic-clear stale managed loop installed");

    require(
        pipeline.prepareAtomicLoopCutover(owner, false, 0U, 0U, 128U, 8U) ==
            SongRenderLoopStatus::Disabled,
        "atomic-clear disabled direct cutover accepted");
    require(
        !pipeline.loopCycleSnapshot().installed,
        "atomic-clear direct authority retires stale managed loop");

    std::array<float, kMaximumBlock * 2U> output{};
    const SongRenderResult result = renderFinal(
        pipeline,
        owner,
        output.data(),
        kMaximumBlock,
        64U);
    require(
        result.status == SongRenderStatus::Rendered && !result.loopEnabled &&
            result.sourceTimeline.startFrame == 128.0,
        "atomic-clear stages unlooped PCM at the exact continuation");
}

void atomicContinuationReturnsToDirectAtNeutral() {
    constexpr std::size_t frames = 32'768U;
    const SongRenderOwnership owner{205U, 51U};
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 11U, 22U, 0U};
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "continuation cache begins");
    const auto pcm = tone(frames, 0U);
    require(cache.offer(key, 0U, 0U, pcm.data(), frames).inputFramesConsumed == frames,
            "continuation Original cached");
    require(cache.sealLane(key, 0U, frames) == CurrentSongCacheSealStatus::Accepted,
            "continuation Original complete");
    SongRenderPipeline live(configuration());
    SongRenderPipeline spare(configuration());
    require(live.beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok, "continuation owner");
    require(live.bindCurrentSongPcmCache(owner, &cache), "continuation cache bound");
    require(live.submitControls(controls(1U, MixerSourceMode::Original, 1.0F, 3.0F)) ==
                SongRenderControlStatus::Accepted, "continuation tuned controls");
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
    require(live.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock).status ==
                SongRenderStatus::Rendered, "continuation live tuning activated");
    require(live.submitControls(controls(2U)) == SongRenderControlStatus::Accepted,
            "continuation neutral controls");
    require(live.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock).status ==
                SongRenderStatus::Rendered && live.timePitchDiagnostics().processedTimelineActivated,
            "continuation live stays processed at neutral");
    require(spare.timePitchDiagnostics().exactBypass, "continuation spare is still virgin");
    require(spare.prepareAtomicLoopContinuationFrom(
                live, owner, true, 1'024U, 30'000U, 1'024U, 8U,
                CurrentSongPcmCache::kOriginalLaneMask) == SongRenderLoopStatus::Accepted,
            "Original-only cached loop exceeds the rolling reservoir and prepares exact A");
    const auto result = spare.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock);
    require(result.status == SongRenderStatus::Rendered && result.framesProduced == kMaximumBlock &&
                result.sourceTimeline.startFrame == 1'024.0 &&
                !result.tuningProcessed,
            "neutral continuation uses direct PCM at exact A");
    require(spare.refreshAtomicLoopContinuationControlsFrom(live) == SongRenderLoopStatus::InvalidOwnership,
            "Original-only preparation cannot promise unprepared stem buses");
    for (std::uint32_t lane = 1U; lane < kSongRenderLaneCount; ++lane) {
        require(cache.offer(key, lane, 0U, pcm.data(), frames).inputFramesConsumed == frames &&
                    cache.sealLane(key, lane, frames) == CurrentSongCacheSealStatus::Accepted,
                "continuation stems cached");
    }
    const auto cursor = spare.sourceTimelineFrame();
    auto updated = controls(3U, MixerSourceMode::Stems);
    updated.mixer.musicGain = .2F;
    require(live.submitControls(updated) == SongRenderControlStatus::Accepted &&
                spare.refreshAtomicLoopContinuationControlsFrom(live) == SongRenderLoopStatus::Accepted &&
                spare.sourceTimelineFrame() == cursor,
            "late source and gain controls preserve prepared PCM and cursor");
    for (unsigned change = 0U; change < 4U; ++change) {
        auto authored = updated;
        authored.revision += change + 1U;
        authored.applicationEpoch = authored.revision;
        if (change == 0U) authored.tempo = 1.35F;
        if (change == 1U) authored.pitchSemitones = 3.0F;
        if (change == 2U) authored.mixer.metronome.mode = MetronomeMode::FreeBpm;
        if (change == 3U) authored.mixer.defaultDeclickFrames += 1U;
        require(live.submitControls(authored) == SongRenderControlStatus::Accepted &&
                    spare.refreshAtomicLoopContinuationControlsFrom(live) == SongRenderLoopStatus::InvalidOwnership &&
                    spare.sourceTimelineFrame() == cursor,
                "authored PCM changes require new preparation without mutating the old head");
    }
    auto tuned = controls(8U, MixerSourceMode::Stems, .5F, -12.F);
    require(live.submitControls(tuned) == SongRenderControlStatus::Accepted &&
                spare.prepareAtomicLoopContinuationFrom(live, owner, true,
                    1'024U, 30'000U, 1'024U, 8U,
                    (1U << kSongRenderLaneCount) - 1U) == SongRenderLoopStatus::Accepted,
            "tuned continuation prepares every declared lane");
    std::fill(output.begin(), output.end(), .123F);
    auto stepped = spare.renderMultibus(owner, output.data(), kMaximumBlock, 96U, 96U);
    require(stepped.status == SongRenderStatus::PreparingTune && stepped.framesProduced == 0U &&
                spare.sourceTimelineFrame() == 1'024.0 &&
                std::all_of(output.begin(), output.end(), [](float value) { return value == .123F; }),
            "partial continuation prime preserves timeline and produces no counterfeit PCM");
    for (unsigned step = 0U; stepped.status == SongRenderStatus::PreparingTune && step < 200U; ++step) {
        stepped = spare.renderMultibus(owner, output.data(), kMaximumBlock, 96U, 96U);
    }
    require(stepped.status == SongRenderStatus::Rendered && stepped.framesProduced == 96U &&
                stepped.sourceTimeline.startFrame == 1'024.0 && stepped.tuningProcessed,
            "bounded continuation completes with tuned PCM at exact A");

}

void earlyTunedContinuationPrimesItsActualHeadInsteadOfAnActivationCrossfade() {
    constexpr std::size_t frames = 32'768U;
    constexpr SongRenderOwnership owner{207U, 60U};
    constexpr std::uint64_t a = 1'024U, b = 30'000U;
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 11U, 22U, 33U};
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "early tune cache begins");
    for (std::uint32_t lane = 0U; lane < kSongRenderLaneCount; ++lane) {
        const auto pcm = tone(frames, 0U, .03F, 217.0 + lane * 31.0);
        require(cache.offer(key, lane, 0U, pcm.data(), frames).inputFramesConsumed == frames &&
            cache.sealLane(key, lane, frames) == CurrentSongCacheSealStatus::Accepted,
            "early tune retains independent source PCM for every lane");
    }
    for (const auto source : {MixerSourceMode::Original, MixerSourceMode::Stems}) {
        for (const bool half : {false, true}) {
            const float tempo = half ? .5F : 1.5F;
            const float pitch = half ? -12.0F : 7.0F;
            const auto laneMask = source == MixerSourceMode::Original ? 1U : 127U;
            SongRenderPipeline live(configuration()), early(configuration()), settled(configuration());
            require(live.beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok &&
                live.bindCurrentSongPcmCache(owner, &cache) &&
                live.submitControls(controls(1U, source)) == SongRenderControlStatus::Accepted,
                "early tune starts a real neutral producer");
            std::array<float, kMaximumBlock * kStemwiseMultibusStride> scratch{}, actual{}, expected{};
            require(live.renderMultibus(owner, scratch.data(), kMaximumBlock, kMaximumBlock).framesProduced == kMaximumBlock &&
                live.submitControls(controls(2U, source, tempo, pitch)) == SongRenderControlStatus::Accepted,
                "new tune is accepted before its first live render");
            require(early.prepareAtomicLoopContinuationFrom(live, owner, true, a, b, a, 32U, laneMask) ==
                SongRenderLoopStatus::Accepted, "exact A head prepares before the live tune activates");
            const auto activating = live.renderMultibus(owner, scratch.data(), kMaximumBlock, kMaximumBlock);
            require(activating.framesProduced == kMaximumBlock && activating.actualTempo == 0.0F,
                "live activation truthfully remains a mixed numerical transition");
            require(settled.prepareAtomicLoopContinuationFrom(live, owner, true, a, b, a, 32U, laneMask) ==
                SongRenderLoopStatus::Accepted, "reference head prepares after live activation");
            const auto reference = settled.renderMultibus(owner, expected.data(), kMaximumBlock, kMaximumBlock);
            const auto result = early.renderMultibus(owner, actual.data(), kMaximumBlock, kMaximumBlock);
            require(reference.framesProduced == kMaximumBlock && result.framesProduced == kMaximumBlock &&
                result.sourceTimeline.startFrame == static_cast<double>(a) &&
                result.sourceTimeline.endFrameExclusive == reference.sourceTimeline.endFrameExclusive &&
                result.actualTempo == tempo && result.actualPitchSemitones == pitch &&
                reference.actualTempo == tempo && reference.actualPitchSemitones == pitch,
                "prepared head must contain the actual target DSP, not a cached virgin activation blend");
            require(actual == expected,
                "early preparation must preserve every sample of the independently primed target head");
        }
    }
}

void retainedContinuationRebindPreservesEverySample() {
    constexpr std::size_t frames = 32'768U;
    constexpr SongRenderOwnership owner{208U, 61U};
    constexpr SongRenderOwnership next{owner.selectionToken, owner.generation + 1U};
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 11U, 22U, 33U};
    for (const bool tuned : {false, true}) {
        CurrentSongPcmCache cache, referenceCache;
        for (auto *fixtureCache : {&cache, &referenceCache}) {
            require(fixtureCache->begin(key) == CurrentSongCacheBeginStatus::Accepted, "retained cache begins");
            for (std::uint32_t lane = 0U; lane < kSongRenderLaneCount; ++lane) {
                const auto pcm = tone(frames, 0U, .03F, 217.0 + lane * 31.0);
                require(fixtureCache->offer(key, lane, 0U, pcm.data(), frames).inputFramesConsumed == frames &&
                    fixtureCache->sealLane(key, lane, frames) == CurrentSongCacheSealStatus::Accepted,
                    "retained cache contains every sealed bus");
            }
        }
        SongRenderPipeline live(configuration()), retained(configuration()), reference(configuration());
        const auto settings = controls(1U, MixerSourceMode::Stems,
            tuned ? 1.35F : 1.0F, tuned ? 3.0F : 0.0F);
        require(live.beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok &&
                    live.bindCurrentSongPcmCache(owner, &cache) &&
                    live.submitControls(settings) == SongRenderControlStatus::Accepted,
                "retained live owner and controls");
        std::array<float, kMaximumBlock * kStemwiseMultibusStride> actual{}, expected{};
        require(live.renderMultibus(owner, actual.data(), kMaximumBlock, kMaximumBlock).framesProduced ==
                    kMaximumBlock, "retained live processing activated");
        for (auto *pipeline : {&retained, &reference}) {
            require(pipeline->prepareAtomicLoopContinuationFrom(live, owner, true,
                        1'024U, 30'000U, 1'024U, 16U, ((1U << kSongRenderLaneCount) - 1U)) ==
                        SongRenderLoopStatus::Accepted, "identical continuations prepared");
            if (pipeline == &reference)
                require(reference.bindCurrentSongPcmCache(owner, &referenceCache),
                    "independent exact reference retains its original cache owner");
            for (unsigned block = 0U; block < 10U; ++block) {
                require(pipeline->renderMultibus(owner, actual.data(), kMaximumBlock, kMaximumBlock)
                            .framesProduced == kMaximumBlock, "complete head primed");
            }
        }
        retained.releaseCacheReadRequirements();
        require(retained.refreshAtomicLoopContinuationControlsFrom(live) == SongRenderLoopStatus::Accepted,
            "same-owner retained refresh survives exact reader retirement");
        const auto retiredActual = retained.renderMultibus(owner, actual.data(), kMaximumBlock, kMaximumBlock);
        const auto retiredExpected = reference.renderMultibus(owner, expected.data(), kMaximumBlock, kMaximumBlock);
        require(retiredActual.framesProduced == kMaximumBlock && retiredExpected.framesProduced == kMaximumBlock &&
            actual == expected, "released same-owner reader resumes exact already-prepared PCM");
        auto nextSettings = settings;
        nextSettings.revision = 2U;
    nextSettings.applicationEpoch = 2U;
        const CurrentSongCacheKey nextKey{next.selectionToken, next.generation, 11U, 22U, 33U};
        require(cache.rebindCompleteOwnership(key, nextKey, ((1U << kSongRenderLaneCount) - 1U)) &&
                    live.seek(next, next.generation, 5'000U, live.nextOutputClockFrame()) == SongRenderResetStatus::Ok &&
                    live.bindCurrentSongPcmCache(next, &cache) &&
                    live.submitControls(nextSettings) == SongRenderControlStatus::Accepted,
                "seek owner keeps exact song and tuning");
        const auto cursor = retained.sourceTimelineFrame();
        require(retained.refreshAtomicLoopContinuationControlsFrom(live) == SongRenderLoopStatus::InvalidOwnership,
                "ordinary control refresh cannot cross an owner generation");
        const auto allocations = allocation_probe::count.load(std::memory_order_relaxed);
        allocation_probe::enabled.store(true, std::memory_order_release);
        const auto rebound = retained.refreshAtomicLoopContinuationControlsFrom(live,
            ((1U << kSongRenderLaneCount) - 1U));
        allocation_probe::enabled.store(false, std::memory_order_release);
        require(rebound == SongRenderLoopStatus::Accepted && retained.sourceTimelineFrame() == cursor &&
                    allocation_probe::count.load(std::memory_order_relaxed) == allocations,
                "retained owner rebind preserves DSP cursor without allocating");
        for (unsigned block = 0U; block < 24U; ++block) {
            const auto a = retained.renderMultibus(next, actual.data(), kMaximumBlock, kMaximumBlock);
            const auto b = reference.renderMultibus(owner, expected.data(), kMaximumBlock, kMaximumBlock);
            require(a.framesProduced == kMaximumBlock && b.framesProduced == kMaximumBlock &&
                        a.sourceTimeline.startFrame == b.sourceTimeline.startFrame &&
                        a.sourceTimeline.endFrameExclusive == b.sourceTimeline.endFrameExclusive &&
                        actual == expected && a.payloadFingerprint.hash == b.payloadFingerprint.hash,
                    "rebound continuation tail is bit exact on every audio, click and envelope bus");
        }
        require(retained.renderMultibus(owner, actual.data(), kMaximumBlock, kMaximumBlock)
                    .framesProduced == 0U, "retained continuation rejects its old owner");
        auto changed = settings;
        changed.revision = 3U;
    changed.applicationEpoch = 3U;
        changed.mixer.metronome.mode = MetronomeMode::FreeBpm;
        require(live.submitControls(changed) == SongRenderControlStatus::Accepted &&
                    retained.refreshAtomicLoopContinuationControlsFrom(live, ((1U << kSongRenderLaneCount) - 1U)) ==
                        SongRenderLoopStatus::InvalidOwnership,
                "metronome activation requires fresh clock-aligned PCM");
    }
}

void neutralReturnPreservesPcmAndCanReverseWhileMovingTune() {
    constexpr std::size_t frames = 32'768U;
    const SongRenderOwnership owner{207U, 53U};
    for (const bool loop : {false, true}) {
        SongRenderPipeline pipeline(configuration(frames));
        require(pipeline.beginSong(owner, (owner).generation, 0U, 0) == SongRenderResetStatus::Ok, "neutral owner");
        const auto pcm = tone(frames, 0U, 0.07F, 317.0);
        appendAllLanes(pipeline, owner.generation, 0U, pcm);
        CurrentSongPcmCache loopCache;
        if (loop) bindLoopFixture(loopCache, pipeline, owner, 0U, pcm);
        if (loop) require(pipeline.prepareAtomicLoopCutover(owner, true, 0U, frames, 0U, 16U) ==
                          SongRenderLoopStatus::Accepted, "neutral loop");
        std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
        std::uint64_t revision = 0U;
        auto tune = [&](float pitch) {
            require(pipeline.submitControls(controls(++revision, MixerSourceMode::Stems, 1.0F, pitch)) ==
                    SongRenderControlStatus::Accepted, "neutral tune accepted");
        };
        double expectedSource = 0.0;
        float lastSample = 0.0F;
        auto render = [&](std::uint32_t count) {
            const auto result = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, count);
            require(result.status == SongRenderStatus::Rendered && result.framesProduced == count,
                    "neutral transition has no missing output");
            requireNear(result.sourceTimeline.startFrame, expectedSource, 1e-9, "neutral exact source");
            expectedSource = result.sourceTimeline.endFrameExclusive;
            for (std::size_t frame = 0U; frame < count; ++frame) {
                const float sample = output[frame * kStemwiseMultibusStride];
                require(std::isfinite(sample) && std::abs(sample - lastSample) < .025F,
                        "neutral transition has no hard sample step");
                lastSample = sample;
            }
            return result;
        };
        tune(3.0F);
        for (unsigned block = 0U; block < 8U; ++block) render(256U);
        tune(0.0F);
        render(128U);
        tune(3.0F); // Reverse a partially completed return instead of jumping back to wet PCM.
        render(64U);
        tune(0.0F);
        for (unsigned block = 0U; block < 12U; ++block) render(64U);
        require(!pipeline.processedTimelineActive(), "sustained neutral leaves the DSP path");
        const auto processes = pipeline.timePitchDiagnostics().processCalls;
        const auto direct = render(256U);
        require(!direct.tuningProcessed && pipeline.timePitchDiagnostics().processCalls == processes,
                "direct playback performs no further DSP calls");
        for (std::size_t frame = 0U; frame < direct.framesProduced; ++frame) {
            const auto source = static_cast<std::size_t>(direct.sourceTimeline.startFrame) + frame;
            require(output[frame * kStemwiseMultibusStride] == pcm[source * 2U],
                    "returned neutral PCM matches the exact audible source");
        }
        tune(-3.0F);
        require(render(256U).tuningProcessed, "tuning reactivates after direct return");
    }
}

void requiredInputReserveUsesBoundedCanonicalReadsBeforeAnAudibleMiss() {
    constexpr SongRenderOwnership owner{629U, 17U};
    constexpr std::size_t frames = 196'608U, start = 32'768U;
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 23U, 33U, 43U};
    for (const bool stems : {false, true}) for (const float tempo : {1.0F, 2.0F}) {
        CurrentSongPcmCache cache;
        require(cache.admitStorage(7U, frames) == CurrentSongCacheStorageStatus::Admitted &&
            cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "canonical reserve cache begins");
        for (std::size_t lane = 0U; lane < 7U; ++lane) {
            const auto pcm = tone(frames, 0U, .03F, 220.0 + lane * 35.0);
            require(cache.offer(key, lane, 0U, pcm.data(), frames).inputFramesConsumed == frames &&
                cache.sealLane(key, lane, frames) == CurrentSongCacheSealStatus::Accepted,
                "canonical reserve has real sequential PCM for every admitted lane");
        }
        auto configured = configuration(32'768U);
        configured.maximumBlockFrames = 256U;
        configured.stemLaneCount = stems ? 6U : 0U;
        SongRenderPipeline pipeline(configured), reference(configured);
        for (auto *consumer : {&pipeline, &reference})
            require(consumer->beginSong(owner, owner.generation, start, 0U) == SongRenderResetStatus::Ok &&
                consumer->bindCurrentSongPcmCache(owner, &cache) &&
                consumer->submitControls(controls(1U, stems ? MixerSourceMode::Stems : MixerSourceMode::Original, tempo)) ==
                    SongRenderControlStatus::Accepted, "canonical reserve binds exact owner/source");
        std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{}, expected{};
        const auto independent = reference.renderMultibus(owner, expected.data(), kMaximumBlock, 256U);
        require(independent.framesProduced == 256U, "independent synchronous PCM reference renders");
        cache.enableAsyncReads([](void *) noexcept {}, nullptr);
        SongRenderResult rendered;
        for (unsigned attempt = 0U; attempt < 64U; ++attempt) {
            rendered = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, 256U);
            cache.serviceAsyncReads();
            if (rendered.framesProduced != 0U) break;
        }
        require(rendered.framesProduced == 256U && rendered.lateMixEndpoint.sourceMode ==
            (stems ? MixerSourceMode::Stems : MixerSourceMode::Original) &&
            rendered.sourceTimeline.startFrame == independent.sourceTimeline.startFrame &&
            std::memcmp(output.data(), expected.data(), 256U * kStemwiseMultibusStride * sizeof(float)) == 0,
            "async canonical input preserves exact selected-source PCM/timeline");
        pipeline.requestUpcomingCacheInput();
        cache.serviceAsyncReads();
        std::array<float, 128U> scratch{};
        require(!cache.copyLane(0U, 0U, scratch.data(), 64U, 64U), "hard rewind is cold in RAM");
        cache.requestUpcoming(1U, 131'072U, 64U);
        const auto before = cache.snapshot().io;
        require(cache.serviceAsyncReads(), "hard rewind makes bounded direct I/O progress");
        const auto after = cache.snapshot().io;
        const bool copied = cache.copyLane(0U, 0U, scratch.data(), 64U, 64U);
        require(after.readOperations > before.readOperations && after.lastReadByteOffset == 0U &&
            after.lastReadBytes <= 4'096U * 8U && copied,
            ("upcoming work cannot replace the exact hard read or scan its discarded source prefix: stems=" +
                std::to_string(stems) + " tempo=" + std::to_string(tempo) + " offset=" +
                std::to_string(after.lastReadByteOffset) + " reads=" +
                std::to_string(after.readOperations - before.readOperations) + " copied=" +
                std::to_string(copied)).c_str());
        const auto original = tone(64U, 0U, .03F, 220.0);
        require(std::memcmp(scratch.data(), original.data(), scratch.size() * sizeof(float)) == 0,
            "direct hard read returns exact sequential PCM");
    }
}

void asynchronousSealedLoopPrimeRetainsItsOptionalBusPlan(bool requiredStems) {
    const auto source = requiredStems ? MixerSourceMode::Stems : MixerSourceMode::Original;
    constexpr SongRenderOwnership owner{623U, 13U};
    constexpr std::size_t frames = 32'768U;
    constexpr std::uint64_t a = 2'048U, b = 24'576U;
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 21U, 31U, 41U};
    auto configured = configuration(frames);
    configured.maximumBlockFrames = 256U;
    configured.stemLaneCount = 6U;
    CurrentSongPcmCache cache;
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "async prime cache begins");
    SongRenderPipeline live(configured), spare(configured), reference(configured);
    for (auto *pipeline : {&live, &reference})
        require(pipeline->beginSong(owner, owner.generation, 0U, 0U) == SongRenderResetStatus::Ok &&
            pipeline->submitControls(controls(1U, source, 2.0F)) ==
                SongRenderControlStatus::Accepted, "async prime live/reference owner and actual tempo admitted");
    for (std::size_t lane = 0U; lane < 7U; ++lane) {
        const auto pcm = tone(frames, 0U, .03F, 220.0 + lane * 35.0);
        require(cache.offer(key, lane, 0U, pcm.data(), frames).inputFramesConsumed == frames &&
            cache.sealLane(key, lane, frames) == CurrentSongCacheSealStatus::Accepted,
            "async prime uses an actually sealed all-resident package");
        for (auto *pipeline : {&live, &reference}) {
            if (pipeline == &reference && !requiredStems && lane != 0U) continue;
            require(pipeline->appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation,
                0U, pcm.data(), frames).status == TimelineAppendStatus::Accepted,
                "known resident input seeds the real active producer and independent selected-source reference");
        }
    }
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> actual{}, expected{};
    require(live.renderMultibus(owner, actual.data(), kMaximumBlock, 256U).framesProduced == 256U,
        "live producer processes its real initial tuned PCM before continuation");
    cache.enableAsyncReads([](void *) noexcept {}, nullptr);
    require(live.bindCurrentSongPcmCache(owner, &cache), "async prime binds its actual canonical source");
    auto prepared = spare.prepareAtomicLoopContinuationFrom(live, owner, true, a, b, a, 32U, requiredStems ? 127U : 1U);
    for (unsigned turn = 0U; prepared == SongRenderLoopStatus::Preparing && turn < 128U; ++turn) {
        cache.serviceAsyncReads();
        prepared = spare.prepareAtomicLoopContinuationFrom(live, owner, true, a, b, a, 32U, requiredStems ? 127U : 1U);
    }
    require(prepared == SongRenderLoopStatus::Accepted,
        "real asynchronous sealed-cache continuation is prepared before its cold windows load");
    require(reference.renderMultibus(owner, expected.data(), kMaximumBlock, 256U).framesProduced == 256U,
        "reference independently processes the same initial tuned PCM");
    SongRenderPipeline referenceLoop(configured);
    CurrentSongPcmCache referenceCache;
    require(referenceCache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "reference cache begins");
    for (std::size_t lane = 0U; lane < (requiredStems ? 7U : 1U); ++lane) {
        const auto pcm = tone(frames, 0U, .03F, 220.0 + lane * 35.0);
        require(referenceCache.offer(key, lane, 0U, pcm.data(), frames).inputFramesConsumed == frames &&
            referenceCache.sealLane(key, lane, frames) == CurrentSongCacheSealStatus::Accepted,
            "synchronous reference commits exact known PCM");
    }
    require(reference.bindCurrentSongPcmCache(owner, &referenceCache), "reference binds its actual canonical source");
    auto referencePrepared = referenceLoop.prepareAtomicLoopContinuationFrom(reference, owner, true, a, b, a, 32U, requiredStems ? 127U : 1U);
    for (unsigned turn = 0U; referencePrepared == SongRenderLoopStatus::Preparing && turn < 128U; ++turn)
        referencePrepared = referenceLoop.prepareAtomicLoopContinuationFrom(reference, owner, true, a, b, a, 32U, requiredStems ? 127U : 1U);
    require(referencePrepared == SongRenderLoopStatus::Accepted, "synchronous reference continuation begins at exact A");
    SongRenderResult result;
    for (unsigned attempt = 0U; attempt < 128U; ++attempt) {
        result = spare.renderMultibus(owner, actual.data(), kMaximumBlock, 256U);
        cache.serviceAsyncReads();

        if (result.framesProduced != 0U) break;
    }
    require(result.framesProduced == 256U && result.ownership.selectionToken == owner.selectionToken &&
        result.ownership.generation == owner.generation && result.lateMixEndpoint.sourceMode == source &&
        result.stemBusReadyMask == (requiredStems ? 63U : 0U),
        "sealed async tempo-two continuation completes its bounded prime despite cold optional windows");
    const auto independent = referenceLoop.renderMultibus(owner, expected.data(), kMaximumBlock, 256U);
    require(independent.framesProduced == result.framesProduced &&
        result.sourceTimeline.startFrame == independent.sourceTimeline.startFrame &&
        result.sourceTimeline.endFrameExclusive == independent.sourceTimeline.endFrameExclusive,
        "asynchronous staging preserves the independently rendered exact timeline");
    for (std::size_t frame = 0U; frame < result.framesProduced; ++frame)
        for (std::size_t channel = 0U; channel < (requiredStems ? kStemwiseMultibusStride : 2U); ++channel)
            require(actual[frame * kStemwiseMultibusStride + channel] ==
                expected[frame * kStemwiseMultibusStride + channel],
                "asynchronous staging preserves exact independently rendered callback PCM");
}

void optionalOriginalBusesNeverBlockOnColdReadWindows() {
    constexpr SongRenderOwnership owner{617U, 11U};
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 17U, 27U, 37U};
    constexpr std::size_t frames = 131'072U;
    for (const float tempo : {1.0F, 2.0F}) {
        CurrentSongPcmCache cache;
        require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "optional canonical cache begins");
        for (std::size_t lane = 0U; lane < 7U; ++lane) {
            const auto pcm = tone(frames, 0U, .03F, 220.0 + lane * 35.0);
            require(cache.offer(key, lane, 0U, pcm.data(), frames).inputFramesConsumed == frames &&
                cache.sealLane(key, lane, frames) == CurrentSongCacheSealStatus::Accepted,
                "optional canonical lanes contain real committed PCM");
        }
        cache.enableAsyncReads([](void *) noexcept {}, nullptr);
        std::array<float, 2'048U> scratch{};
        require(!cache.copyLane(0U, 0U, scratch.data(), 256U, 256U) && cache.serviceAsyncReads() &&
            cache.copyLane(0U, 0U, scratch.data(), 256U, 256U), "Original window warms through real I/O");
        auto configured = configuration();
        SongRenderPipeline pipeline(configured);
        require(pipeline.beginSong(owner, owner.generation, 0U, 0U) == SongRenderResetStatus::Ok &&
            pipeline.bindCurrentSongPcmCache(owner, &cache) &&
            pipeline.submitControls(controls(1U, MixerSourceMode::Original, tempo)) == SongRenderControlStatus::Accepted,
            "Original binds exact optional package");
        std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
        SongRenderResult result;
        for (unsigned attempt = 0U; attempt < 128U; ++attempt) {
            result = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, 256U);
            cache.serviceAsyncReads();
            if (result.framesProduced != 0U) break;
        }
        require(result.framesProduced == 256U && result.lateMixEndpoint.sourceMode == MixerSourceMode::Original &&
            result.stemBusReadyMask == 0U, "cold optional windows cannot block Original or invent ready stem buses");
        const auto readsBefore = cache.snapshot().io.readOperations;
        // Required Original progresses while optional windows remain cold, without reading six files.
        result = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, 256U);
        cache.serviceAsyncReads();
        require(result.framesProduced == 256U && result.stemBusReadyMask == 0U &&
            cache.snapshot().io.readOperations <= readsBefore + 1U,
            "cold optional buses cannot authorize extra canonical reads during Original playback");
        if (tempo == 1.F) {
            const auto next = pipeline.nextPreDspTimelineFrame();
            for (std::size_t lane = 1U; lane < 7U; ++lane) {
                bool ready = false;
                for (unsigned attempt = 0U; attempt < 4U && !ready; ++attempt) {
                    ready = cache.copyLane(lane, next, scratch.data(), 256U, 256U);
                    if (!ready) cache.serviceAsyncReads();
                }
                require(ready, "actual optional windows warm through their explicit consumer");
            }
            result = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, 256U);
            require(result.framesProduced == 256U && result.stemBusReadyMask == 63U,
                "actual warm optional direct buses remain ready for source switching");
        }
        require(pipeline.submitControls(controls(2U, MixerSourceMode::Stems, tempo)) == SongRenderControlStatus::Accepted,
            "ready-bus source switch accepts the actual selected package");
        bool switched = false;
        for (unsigned attempt = 0U; attempt < frames / 256U && !switched; ++attempt) {
            result = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, 256U);
            cache.serviceAsyncReads();
            switched = result.framesProduced == 256U && result.lateMixEndpoint.sourceMode == MixerSourceMode::Stems &&
                result.stemBusReadyMask == 63U;
        }
        require(switched, "required Stems loads its exact windows and preserves the ready-bus source switch");
    }
}

void longLoopsReadTheirCanonicalBody(unsigned seconds, float tempo, std::uint32_t parts) {
    constexpr SongRenderOwnership owner{611U, 9U};
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 11U, 22U, 33U};
    {
        const std::uint64_t a = seconds == 37U ? 0U : 48'000U;
        const std::uint64_t b = a + seconds * 48'000U;
        const std::size_t songFrames = static_cast<std::size_t>(b + (a == 0U ? 0U : 48'000U));
        CurrentSongPcmCache cache;
        require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "long loop cache begins");

        std::vector<std::vector<float>> fixture;
        auto configured = configuration(2'097'152U, 32U);
        configured.stemLaneCount = parts;
        SongRenderPipeline live(configured), loop(configured), reference(configured);
        for (auto *pipeline : {&live, &reference}) {
            require(pipeline->beginSong(owner, owner.generation, a, 0) == SongRenderResetStatus::Ok,
                "long loop owner begins");
            require(pipeline->submitControls(controls(1U, parts == 0U ? MixerSourceMode::Original :
                MixerSourceMode::Stems, tempo, tempo == 1.0F ? 0.0F : 3.0F)) ==
                SongRenderControlStatus::Accepted, "long loop controls admitted");
        }
        for (std::size_t lane = 0U; lane <= parts; ++lane) {
            fixture.push_back(tone(songFrames, 0U, .04F / static_cast<float>(parts + 1U), 440.0 + lane * 20.0));
            const auto &pcm = fixture.back();
            require(cache.offer(key, lane, 0U, pcm.data(), songFrames).inputFramesConsumed == songFrames &&
                cache.sealLane(key, lane, songFrames) == CurrentSongCacheSealStatus::Accepted,
                "long loop logical extent commits despite bounded residency");
            require(reference.appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation, a,
                pcm.data() + a * 2U, songFrames - a).status == TimelineAppendStatus::Accepted,
                "independent resident reference retains Original and every declared bus");
            require(reference.markLaneEndOfStream(static_cast<SongRenderLane>(lane), owner.generation,
                songFrames) == SongRenderEosStatus::Accepted, "long loop reference EOS");
        }
        require(cache.covers((1U << (parts + 1U)) - 1U, a, static_cast<std::size_t>(b - a)),
            "canonical loop body retains its exact committed extent outside bounded RAM windows");
        cache.enableAsyncReads([](void *) noexcept {}, nullptr);
        const auto service = [&] { cache.serviceAsyncReads(); };
        require(live.bindCurrentSongPcmCache(owner, &cache), "long loop binds exact cache owner");
        SongRenderLoopStatus admission = SongRenderLoopStatus::RangeNotRetained;
        for (unsigned attempt = 0U; attempt != 64U && admission != SongRenderLoopStatus::Accepted; ++attempt) {
            admission = loop.prepareAtomicLoopContinuationFrom(live, owner, true, a, b, a, 32U,
                (1U << (parts + 1U)) - 1U);
            service();
        }
        require(admission == SongRenderLoopStatus::Accepted, "long loop admits from bounded head plus logical extent");
        CurrentSongPcmCache referenceCache;
        const CurrentSongCacheKey referenceKey{owner.selectionToken, owner.generation, 7U, 9U, 11U};
        require(referenceCache.begin(referenceKey) == CurrentSongCacheBeginStatus::Accepted, "reference cache begins");
        for (std::size_t lane = 0U; lane <= parts; ++lane) {
            require(referenceCache.offer(referenceKey, lane, 0U, fixture[lane].data(), songFrames).framesStored == songFrames &&
                referenceCache.sealLane(referenceKey, lane, songFrames) == CurrentSongCacheSealStatus::Accepted, "reference cache PCM");
        }
        require(reference.bindCurrentSongPcmCache(owner, &referenceCache), "reference exact cache binds");
        require(reference.prepareAtomicLoopCutover(owner, true, a, b, a, 32U) == SongRenderLoopStatus::Accepted,
            "independent reservoir reference admits logical loop");
        std::array<float, kMaximumBlock * kStemwiseMultibusStride> actual{}, expected{};
        double expectedStart = static_cast<double>(a);
        for (unsigned block = 0U; block != 100'000U && loop.diagnostics().loopWraps < 3U; ++block) {
            SongRenderResult rendered;
            for (unsigned attempt = 0U; attempt != 64U; ++attempt) {
                rendered = loop.renderMultibus(owner, actual.data(), kMaximumBlock, kMaximumBlock);
                if (rendered.framesProduced != 0U) break;
                service();
            }
            if (rendered.framesProduced == 0U) std::cerr << "long loop seconds=" << seconds << " tempo=" << tempo
                << " parts=" << parts << " block=" << block << " status=" << static_cast<unsigned>(rendered.status)
                << " cursor=" << loop.sourceTimelineFrame() << '\n';
            require(rendered.framesProduced != 0U, "long loop body makes bounded refill progress");
            const auto independent = reference.renderMultibus(owner, expected.data(), kMaximumBlock, kMaximumBlock);
            require(independent.framesProduced == rendered.framesProduced &&
                rendered.ownership.selectionToken == owner.selectionToken && rendered.ownership.generation == owner.generation,
                "long loop preserves exact output owner and reference frame count");
            require(rendered.actualTempo == tempo && independent.actualTempo == tempo &&
                rendered.actualPitchSemitones == (tempo == 1.0F ? 0.0F : 3.0F) &&
                independent.actualPitchSemitones == rendered.actualPitchSemitones,
                "both canonical and independent resident cutovers must prime the actual numerical tuple");
            requireNear(rendered.sourceTimeline.startFrame, expectedStart, 1e-7, "long loop B to A progression");
            expectedStart = rendered.sourceTimeline.endFrameExclusive >= static_cast<double>(b)
                ? static_cast<double>(a) : rendered.sourceTimeline.endFrameExclusive;
            require(std::memcmp(actual.data(), expected.data(), rendered.framesProduced *
                kStemwiseMultibusStride * sizeof(float)) == 0,
                "cache eviction changed loop callback bus PCM relative to independent resident input");
            require(rendered.stemBusReadyMask == (1U << parts) - 1U,
                "long loop retains all eight targets plus Remaining");
            service();
        }
        require(loop.diagnostics().loopWraps == 3U, "long loop completes three exact wraps");
        require(cache.snapshot().fileBytes == (parts + 1U) * songFrames * 8U &&
            cache.snapshot().fileBytes <= cache.snapshot().io.reservedBytes,
            "canonical files contain only published PCM within their admitted storage");
    }
}

void asynchronousRetainedHeadMissIsPendingInputRatherThanMixerFailure() {
    constexpr std::uint64_t a = 96'000U, b = 480'000U, cursor = 315'840U;
    constexpr SongRenderOwnership owner{42U, 456U};
    LoopHeadCache head;
    std::atomic<unsigned> wakes{0U};
    head.enableAsyncReads([](void *context) noexcept {
        static_cast<std::atomic<unsigned> *>(context)->fetch_add(1U);
    }, &wakes);
    auto key = loopKey(1U, a);
    key.endTimelineFrameExclusive = b;
    const auto pcm = provenancePcm(static_cast<std::size_t>(b - a), a, 0U, .08F);
    require(head.beginStaging(key, 1U, LoopHeadBoundaryMode::RetainedWindow) == LoopHeadBeginStatus::Accepted &&
        head.writeStaging(key.loopRevision, 0U, a, pcm.data(), pcm.size() / 2U).status == LoopHeadWriteStatus::LaneComplete &&
        head.commitStaging(key.loopRevision) == LoopHeadCommitStatus::Accepted,
        "actual eight-second retained window commits immutable Original PCM");
    auto view = head.acquireActive(key);
    std::array<float, kMaximumBlock * 2U> inlinePcm{};
    stemwise::audio::engine::LoopHeadCopyStatus copyStatus{};
    require(view.copyLane(0U, a + 1U, inlinePcm.data(), kMaximumBlock, kMaximumBlock, &copyStatus) &&
        copyStatus == stemwise::audio::engine::LoopHeadCopyStatus::Complete && wakes.load() == 0U,
        "the immutable RAM prefix remains immediately readable without I/O");
    SongRenderPipeline pipeline(configuration());
    require(pipeline.beginSong(owner, owner.generation, cursor, 0) == SongRenderResetStatus::Ok &&
        pipeline.bindRetainedLoopHead(owner, head.acquireActive(key)) &&
        pipeline.submitControls(controls(1U)) == SongRenderControlStatus::Accepted,
        "exact sought owner binds its real retained head beyond the four-second RAM prefix");
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
    const auto cold = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock);
    require(cold.status == SongRenderStatus::NeedOriginalData && cold.framesProduced == 0U &&
        pipeline.awaitingCacheRead() && wakes.load() != 0U && pipeline.sourceTimelineFrame() == static_cast<double>(cursor),
        "a cold asynchronous retained-window read must be pending input, not a mixer failure");
    require(head.serviceAsyncReads(), "actual I/O owner publishes requested retained-window PCM");
    const auto warm = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock);
    require(warm.status == SongRenderStatus::Rendered && warm.framesProduced == kMaximumBlock &&
        !pipeline.awaitingCacheRead() && warm.sourceTimeline.startFrame == static_cast<double>(cursor),
        "resolved async read resumes exactly the unconsumed source interval");
    for (std::size_t frame = 0U; frame != kMaximumBlock; ++frame)
        for (std::size_t channel = 0U; channel != 2U; ++channel)
            require(output[frame * kStemwiseMultibusStride + channel] == provenanceSample(cursor + frame, 0U, channel, .08F),
                "the disk-backed retained suffix must preserve every accepted Original sample");
}

void originalOnlyRawHeadDoesNotShadowContiguousStemReservoirs() {
    constexpr SongRenderOwnership owner{15U, 31U};
    constexpr std::uint64_t headStart = 31'200U, stemStart = 34'224U, cursor = 36'960U, end = 57'600U;
    LoopHeadCache head;
    const auto key = loopKey(7U, headStart);
    prepareOriginalLoopCache(head, key, end - headStart, .08F);
    SongRenderPipeline pipeline(configuration(32'768U));
    require(pipeline.beginSong(owner, owner.generation, cursor, 0) == SongRenderResetStatus::Ok &&
        pipeline.bindRetainedLoopHead(owner, head.acquireActive(key)),
        "selected owner pins its actual Original-only raw head");
    require(pipeline.laneCoversLogicalRange(owner, SongRenderLane::Original, cursor, 2'400U),
        "Original remains available from the declared head");
    auto view = head.acquireActive(key);
    std::array<float, kMaximumBlock * 2U> rejected{};
    require(!view.copyLane(1U, cursor, rejected.data(), kMaximumBlock, kMaximumBlock),
        "Original-only head cannot manufacture undeclared Stem PCM");
    require(!pipeline.laneCoversLogicalRange(owner, static_cast<SongRenderLane>(1U), cursor, 2'400U),
        "absent Stem reservoir remains unavailable");
    for (std::size_t lane = 1U; lane < kSongRenderLaneCount; ++lane) {
        const auto pcm = constantPcm(static_cast<std::size_t>(end - stemStart), .01F * static_cast<float>(lane));
        const auto role = static_cast<SongRenderLane>(lane);
        require(pipeline.appendLanePcm(role, owner.generation + 1U, stemStart, pcm.data(), pcm.size() / 2U).status ==
            TimelineAppendStatus::StaleGeneration, "another ingress epoch cannot authorize a Stem");
        require(pipeline.appendLanePcm(role, owner.generation, stemStart, pcm.data(), pcm.size() / 2U).status ==
            TimelineAppendStatus::Accepted &&
            pipeline.markLaneEndOfStream(role, owner.generation, end) == SongRenderEosStatus::Accepted,
            "real paused Stem decoder supplies its exact contiguous interval and EOF");
        require(!pipeline.laneCoversLogicalRange({owner.selectionToken, owner.generation + 1U}, role, cursor, 2'400U),
            "another owner cannot borrow the accepted Stem reservoir");
        require(!pipeline.laneCoversLogicalRange(owner, role, stemStart - 1U, 1U),
            "an undeclared head lane cannot fill a genuine raw prefix gap");
        require(pipeline.laneCoversLogicalRange(owner, role, cursor, 2'400U),
            "Original-only raw head must not shadow an exact contiguous Stem reservoir");
    }
    require(pipeline.submitControls(controls(1U, MixerSourceMode::Stems)) == SongRenderControlStatus::Accepted,
        "paused owner requests its actually available Stems");
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
    const auto result = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock);
    require(result.status == SongRenderStatus::Rendered && result.framesProduced == kMaximumBlock &&
        result.stemBusReadyMask == 0x3fU && result.lateMixEndpoint.sourceMode == MixerSourceMode::Stems &&
        result.sourceTimeline.startFrame == static_cast<double>(cursor),
        "production renderer admits all six real Stem lanes without changing the source cursor");
    for (std::size_t frame = 0U; frame < kMaximumBlock; ++frame) {
        requireNear(output[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(0U)], .08F, 1.0e-7,
            "declared Original head PCM remains authoritative");
        for (std::size_t lane = 0U; lane < 6U; ++lane)
            requireNear(output[frame * kStemwiseMultibusStride + stemwiseStemChannel(lane, 0U)],
                .01F * static_cast<float>(lane + 1U), 1.0e-7, "undeclared head lanes use only their real reservoir PCM");
    }
}

void lateShortLoopsUseBoundedHeadsWithoutWholeSongReservation(bool retainedOriginal = false) {
    const std::uint64_t a = retainedOriginal ? 8'192U : 14U * 60U * 48'000U, b = a + 4'800U;
    constexpr SongRenderOwnership owner{811U, 83U};
    for (const auto mode : {MixerSourceMode::Original, MixerSourceMode::Stems}) {
        CurrentSongPcmCache rejected;
        require(rejected.admitStorage(10U, std::numeric_limits<std::uint64_t>::max()) ==
            stemwise::audio::engine::CurrentSongCacheStorageStatus::LimitExceeded, "unrepresentable whole-song reservation fails before allocation");
        LoopHeadCache head;
        const LoopHeadContentKey key{11U, 22U, 33U, 41U, 1U, a, a + LoopHeadCache::kCapacityFrames};
        const auto mask = mode == MixerSourceMode::Stems ? 0x3ffU : 1U;
        require(head.beginStaging(key, mask) == LoopHeadBeginStatus::Accepted, "independent bounded late head begins");
        for (std::size_t lane = 0U; lane < 10U; ++lane) {
            if ((mask & (1U << lane)) == 0U) continue;
            const auto pcm = tone(LoopHeadCache::kCapacityFrames, a, .03F, 211.0 + lane * 19.0);
            require(head.writeStaging(1U, lane, a, pcm.data(), LoopHeadCache::kCapacityFrames).status ==
                LoopHeadWriteStatus::LaneComplete, "exact late normalized lane fills only bounded head");
        }
        require(head.commitStaging(1U) == LoopHeadCommitStatus::Accepted, "every requested late lane commits");
        auto config = configuration(32'768U); config.stemLaneCount = 9U;
        SongRenderPipeline live(config), loop(config);
        require(live.beginSong(owner, owner.generation, a, 0) == SongRenderResetStatus::Ok &&
            live.submitControls(controls(1U, mode)) == SongRenderControlStatus::Accepted, "late ordinary owner starts");
        CurrentSongPcmCache retained;
        if (retainedOriginal) {
            const auto end = a + LoopHeadCache::kCapacityFrames + 8'192U;
            const CurrentSongCacheKey originalKey{owner.selectionToken, owner.generation, 11U, 22U, 0U};
            const auto canonical = tone(static_cast<std::size_t>(end), 0U, .03F, 211.0);
            require(retained.admitStorage(1U, end) == stemwise::audio::engine::CurrentSongCacheStorageStatus::Admitted &&
                retained.begin(originalKey) == CurrentSongCacheBeginStatus::Accepted &&
                retained.offer(originalKey, 0U, 0U, canonical.data(), static_cast<std::size_t>(end)).inputFramesConsumed == end &&
                retained.sealLane(originalKey, 0U, end) == CurrentSongCacheSealStatus::Accepted &&
                live.bindCurrentSongPcmCache(owner, &retained),
                "optional admission rejection preserves the existing exact Original canonical source");
        }
        const auto pcm = tone(8'192U, a, .03F, 211.0);
        require(live.appendLanePcm(SongRenderLane::Original, owner.generation, a, pcm.data(), 8'192U).inputFramesAccepted == 8'192U,
            "ordinary Original ingress remains independently owned");
        require(live.markLaneEndOfStream(SongRenderLane::Original, owner.generation,
            a + LoopHeadCache::kCapacityFrames + 8'192U) == SongRenderEosStatus::Accepted,
            "ordinary source retains its independently observed end");
        require(loop.prepareAtomicLoopContinuationFrom(live, owner, true, a, b, a, 0U, mask, &head) ==
            SongRenderLoopStatus::Accepted, "late short loop uses independent bounded cache through the atomic producer");
        const CurrentSongCacheKey pinnedKey{owner.selectionToken, owner.generation, 11U, 22U, 33U};
        require(loop.acquireRetainedLoopHead(pinnedKey, mask, a, b).valid(),
            "the installed raw head is available only through its actual owner and content identity");
        for (unsigned field = 0U; field < (mode == MixerSourceMode::Stems ? 5U : 4U); ++field) {
            auto wrong = pinnedKey;
            switch (field) {
                case 0U: ++wrong.selectionToken; break;
                case 1U: ++wrong.generation; break;
                case 2U: ++wrong.songIdentityHash; break;
                case 3U: ++wrong.originalContentHash; break;
                default: ++wrong.stemPackageHash; break;
            }
            require(!loop.acquireRetainedLoopHead(wrong, mask, a, b).valid(),
                "pinned PCM cannot authorize another owner, recording, or required Stems package");
        }
        require(!loop.acquireRetainedLoopHead(pinnedKey, mask, a - 1U, b).valid() &&
            !loop.acquireRetainedLoopHead(pinnedKey, mask, a, key.endTimelineFrameExclusive + 1U).valid() &&
            !loop.acquireRetainedLoopHead(pinnedKey, mask, a, a).valid() &&
            !loop.acquireRetainedLoopHead(pinnedKey, 0U, a, b).valid() &&
            !loop.acquireRetainedLoopHead(pinnedKey, 1U << 10U, a, b).valid(),
            "pinned authority cannot invent absent lanes, empty ranges, or bytes outside its immutable span");
        std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
        const auto before = allocation_probe::count.load(); allocation_probe::enabled.store(true);
        for (unsigned block = 0U; block < 80U; ++block) {
            const auto rendered = loop.renderMultibus(owner, output.data(), kMaximumBlock, 192U);
            require(rendered.framesProduced != 0U && rendered.ownership.generation == owner.generation &&
                rendered.sourceTimeline.startFrame >= a && rendered.sourceTimeline.endFrameExclusive <= b,
                "late bounded loop emits exact owned A-B PCM without a song prefix");
            if (mode == MixerSourceMode::Stems) require(rendered.stemBusReadyMask == 0x1ffU &&
                rendered.lateMixEndpoint.sourceMode == MixerSourceMode::Stems, "all eight targets plus Remaining remain real Stems");
            for (std::size_t frame = 0U; frame < rendered.framesProduced; ++frame) {
                const auto source = static_cast<std::uint64_t>(rendered.sourceTimeline.startFrame) + frame;
                for (std::size_t lane = 0U; lane < 10U; ++lane) {
                    if ((mask & (1U << lane)) == 0U) continue;
                    const float expected = static_cast<float>(.03 * std::sin(2.0 * kPi * (211.0 + lane * 19.0) * static_cast<double>(source) / kSampleRate));
                    requireNear(output[frame * kStemwiseMultibusStride + lane * 2U], expected, 1e-6, "bounded late cache retains independent known PCM");
                }
            }
        }
        allocation_probe::enabled.store(false);
        require(allocation_probe::count.load() == before, "bounded loop rendering allocates nothing");
        const auto ordinary = live.renderMultibus(owner, output.data(), kMaximumBlock, 192U);
        require(ordinary.framesProduced == 192U && ordinary.sourceTimeline.startFrame == static_cast<double>(a),
            "bounded preparation never moves or consumes the ordinary producer");
        std::size_t rawFrames = 0U;
        for (std::size_t lane = 0U; lane <= config.stemLaneCount; ++lane)
            rawFrames += live.laneDiagnostics(static_cast<SongRenderLane>(lane)).storedFrames;
        const auto maximumCopyTurns = (rawFrames + 4'095U) / 4'096U + 1U;
        auto oversized = loop.prepareAtomicLoopContinuationFrom(live, owner, true,
            a, a + LoopHeadCache::kCapacityFrames + 1U, a, 0U, mask, &head);
        const auto initialCopiedFrames = loop.laneDiagnostics(SongRenderLane::Original).storedFrames;
        for (std::size_t turn = 0U; oversized == SongRenderLoopStatus::Preparing && turn < maximumCopyTurns; ++turn)
            oversized = loop.prepareAtomicLoopContinuationFrom(live, owner, true,
                a, a + LoopHeadCache::kCapacityFrames + 1U, a, 0U, mask, &head);
        if (retainedOriginal && mode == MixerSourceMode::Original) {
            if (oversized != SongRenderLoopStatus::Accepted)
                std::cerr << "Canonical oversized terminal: status=" << static_cast<unsigned>(oversized)
                    << " rawFrames=" << rawFrames << " boundedTurns=" << maximumCopyTurns
                    << " initialCopied=" << initialCopiedFrames
                    << " finalCopied=" << loop.laneDiagnostics(SongRenderLane::Original).storedFrames << '\n';
            require(oversized == SongRenderLoopStatus::Accepted,
                "larger loops remain supported when their real canonical Original range is admitted");
        } else {
            if (oversized != SongRenderLoopStatus::InvalidOwnership)
                std::cerr << "Oversized bounded terminal: status=" << static_cast<unsigned>(oversized)
                    << " retainedOriginal=" << retainedOriginal << " source=" << static_cast<unsigned>(mode) << '\n';
            require(oversized == SongRenderLoopStatus::InvalidOwnership,
                "oversized bounded fallback is rejected rather than shortened");
        }
        SongRenderPipeline linear(config);
        require(linear.prepareAtomicLoopContinuationFrom(loop, owner, false, 0U, 0U, b, 0U, mask, &head) ==
            SongRenderLoopStatus::Disabled, "bounded Clear retains the current immutable head for linear playback");
        const auto headEnd = a + LoopHeadCache::kCapacityFrames;
        for (std::size_t lane = 0U; lane < 10U; ++lane) {
            if ((mask & (1U << lane)) == 0U) continue;
            if (retainedOriginal && lane == 0U) continue; // Its accepted PCM remains in the canonical store.
            const auto tail = tone(8'192U, headEnd, .03F, 211.0 + lane * 19.0);
            require(linear.appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation,
                headEnd, tail.data(), 8'192U).inputFramesAccepted == 8'192U,
                "normal decoder body attaches beyond the immutable bounded window");
        }
        auto expectedStart = b;
        while (expectedStart < headEnd + 8'192U) {
            const auto rendered = linear.renderMultibus(owner, output.data(), kMaximumBlock, 192U);
            require(rendered.framesProduced != 0U && rendered.sourceTimeline.startFrame == static_cast<double>(expectedStart) &&
                !rendered.loopEnabled, "Clear remains continuous through the end of the bounded head into normal decoder PCM");
            for (std::size_t frame = 0U; frame < rendered.framesProduced; ++frame) {
                for (std::size_t lane = 0U; lane < 10U; ++lane) {
                    if ((mask & (1U << lane)) == 0U) continue;
                    const float expected = static_cast<float>(.03 * std::sin(2.0 * kPi * (211.0 + lane * 19.0) *
                        static_cast<double>(expectedStart + frame) / kSampleRate));
                    requireNear(output[frame * kStemwiseMultibusStride + lane * 2U], expected, 1e-6,
                        "bounded-head expiry preserves exact normal PCM instead of inventing silence");
                }
            }
            expectedStart = static_cast<std::uint64_t>(rendered.sourceTimeline.endFrameExclusive);
        }
        require(linear.renderMultibus(owner, output.data(), kMaximumBlock, 192U).status == SongRenderStatus::EndOfStream,
            "bounded continuation retains real Original EOF through loop and Clear");
    }
}

void shortLoopsUseOnlyTheirPublishedRangeBeforeRecordingEos() {
    constexpr std::size_t prefix = 16'384U;
    constexpr SongRenderOwnership owner{311U, 79U};
    CurrentSongPcmCache cache;
    const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 11U, 22U, 33U};
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "range cache begins");
    for (std::size_t lane = 0U; lane < 3U; ++lane) {
        const auto pcm = tone(prefix, 0U, .04F, 220.0 + lane * 110.0);
        require(cache.offer(key, lane, 0U, pcm.data(), prefix).inputFramesConsumed == prefix,
                "range prefix admitted without waiting for whole-song EOF");
    }
    auto config = configuration(prefix);
    config.stemLaneCount = 2U;
    SongRenderPipeline live(config), first(config), moved(config);
    require(live.beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok &&
                live.bindCurrentSongPcmCache(owner, &cache) &&
                live.submitControls(controls(1U, MixerSourceMode::Stems, .51F)) == SongRenderControlStatus::Accepted,
            "live range owner is tuned with actual stem intent");
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
    require(live.renderMultibus(owner, output.data(), kMaximumBlock, 192U).framesProduced == 192U,
            "live prefix renders before EOF");
    require(!cache.complete(7U), "unrelated song tail remains undecoded");
    for (const auto& range : {std::pair{0U, 4'800U}, std::pair{8'192U, 12'992U}}) {
        auto &loop = range.first == 0U ? first : moved;
        require(loop.prepareAtomicLoopContinuationFrom(live, owner, true, range.first, range.second,
                    range.first, 240U, 7U) == SongRenderLoopStatus::Accepted,
                "100ms loop continuation uses the exact requested prefix range");
        for (unsigned block = 0U; block < 100U; ++block) {
            const auto result = loop.renderMultibus(owner, output.data(), kMaximumBlock, 192U);
            require(result.framesProduced != 0U && result.ownership.generation == owner.generation &&
                        result.stemBusReadyMask == 3U && result.lateMixEndpoint.sourceMode == MixerSourceMode::Stems,
                    "requested range wraps with current stem PCM before unrelated tail exists");
            require(result.sourceTimeline.startFrame >= range.first &&
                        result.sourceTimeline.endFrameExclusive <= range.second,
                    "range move never exposes an old or unrelated timeline");
        }
    }
    require(cache.snapshot().storedBytes == prefix * 3U * 8U && !cache.complete(7U),
            "short-loop readiness does not grow storage to the declared song duration");
}

void processedStemsRemainReadyAcrossZeroInputSteps() {
    constexpr std::size_t frames = 16'384U;
    constexpr std::uint32_t blockFrames = 192U;
    constexpr SongRenderOwnership owner{309U, 71U};
    for (const float tempo : {0.5F, 0.51F}) {
        auto config = configuration(frames);
        config.stemLaneCount = 2U;
        SongRenderPipeline pipeline(config);
        require(pipeline.beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok,
                "zero-input owner begins");
        for (std::size_t lane = 0U; lane <= 2U; ++lane) {
            const auto pcm = tone(frames, 0U, .04F, 220.0 + lane * 110.0);
            require(pipeline.appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation,
                        0U, pcm.data(), frames).status == TimelineAppendStatus::Accepted,
                    "zero-input lanes available");
        }
        CurrentSongPcmCache loopCache;
        const CurrentSongCacheKey loopKey{owner.selectionToken, owner.generation, 7U, 9U, 11U};
        require(loopCache.begin(loopKey) == CurrentSongCacheBeginStatus::Accepted, "zero-input cache begins");
        for (std::size_t lane = 0U; lane <= 2U; ++lane) {
            const auto input = tone(frames, 0U, .04F, 220.0 + lane * 110.0);
            require(loopCache.offer(loopKey, lane, 0U, input.data(), frames).framesStored == frames &&
                loopCache.sealLane(loopKey, lane, frames) == CurrentSongCacheSealStatus::Accepted, "zero-input cache PCM");
        }
        require(pipeline.bindCurrentSongPcmCache(owner, &loopCache), "zero-input exact cache binds");
        require(pipeline.prepareAtomicLoopCutover(owner, true, 0U, 6'096U, 0U, 240U) == SongRenderLoopStatus::Accepted &&
                    pipeline.submitControls(controls(1U, MixerSourceMode::Stems, tempo)) ==
                        SongRenderControlStatus::Accepted, "zero-input tuned loop admitted");
        std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
        bool observedZero = false, observedInput = false;
        auto before = pipeline.timePitchDiagnostics().inputFramesConsumed;
        for (unsigned block = 0U; block < 180U; ++block) {
            const auto requested = tempo == 0.5F ? 1U : blockFrames;
            const auto result = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, requested);
            require(result.framesProduced != 0U && result.framesProduced <= blockFrames &&
                        result.status == SongRenderStatus::Rendered,
                    "slow loop produces a bounded block at its exact loop boundary");
            const auto after = pipeline.timePitchDiagnostics().inputFramesConsumed;
            observedZero = observedZero || after == before;
            observedInput = observedInput || after != before;
            before = after;
            require(result.stemBusReadyMask == 3U && result.lateMixEndpoint.sourceMode == MixerSourceMode::Stems,
                    "warmed stem buses stay ready when DSP consumes zero new frames");
            for (std::size_t sample = 0U; sample < result.framesProduced * kStemwiseMultibusStride; ++sample) {
                require(std::isfinite(output[sample]), "zero-input stem payload finite");
            }
        }
        require(observedZero && observedInput, "slow DSP alternates input-consuming and buffered steps");
        const SongRenderOwnership next{owner.selectionToken, owner.generation + 1U};
        require(pipeline.beginSong(next, next.generation, 0U, 0) == SongRenderResetStatus::Ok,
                "cold generation resets old stem DSP authority");
        const auto original = tone(frames, 0U);
        require(pipeline.appendLanePcm(SongRenderLane::Original, next.generation, 0U,
                    original.data(), frames).status == TimelineAppendStatus::Accepted &&
                    pipeline.submitControls(controls(2U, MixerSourceMode::Stems, tempo)) ==
                        SongRenderControlStatus::Accepted, "cold Original is independently available");
        for (unsigned block = 0U; block < 8U; ++block) {
            const auto result = pipeline.renderMultibus(next, output.data(), kMaximumBlock, blockFrames);
            require(result.stemBusReadyMask == 0U && result.lateMixEndpoint.sourceMode == MixerSourceMode::Original,
                    "cold generation cannot borrow preceding stem readiness");
        }
    }
}

void ordinaryRenderingKeepsIdentityWithoutPayloadHashing() {
    constexpr SongRenderOwnership owner{901U, 902U};
    for (const auto parts : {0U, 2U, 9U}) for (const auto tempo : {1.0F, 0.5F}) {
        auto config = configuration(32'768U);
        config.stemLaneCount = parts;
        config.diagnosticPayloadFingerprints = false;
        SongRenderPipeline ordinary(config);
        config.diagnosticPayloadFingerprints = true;
        SongRenderPipeline diagnostic(config);
        std::array<CurrentSongPcmCache, 2U> caches;
        std::size_t cacheIndex = 0U;
        for (auto *pipeline : {&ordinary, &diagnostic}) {
            require(pipeline->beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok, "hash-mode owner");
            for (std::uint32_t lane = 0U; lane <= parts; ++lane) {
                const auto input = tone(32'768U, 0U, .05F, 220.0 + lane * 17.0);
                require(pipeline->appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation, 0U,
                    input.data(), 32'768U).status == TimelineAppendStatus::Accepted, "hash-mode real lane");
            }
            auto &cache = caches[cacheIndex++];
            const CurrentSongCacheKey key{owner.selectionToken, owner.generation, 17U, 19U, 23U};
            require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted, "hash-mode cache");
            for (std::uint32_t lane = 0U; lane <= parts; ++lane) {
                const auto input = tone(32'768U, 0U, .05F, 220.0 + lane * 17.0);
                require(cache.offer(key, lane, 0U, input.data(), 32'768U).framesStored == 32'768U &&
                    cache.sealLane(key, lane, 32'768U) == CurrentSongCacheSealStatus::Accepted, "hash-mode cache PCM");
            }
            require(pipeline->bindCurrentSongPcmCache(owner, &cache), "hash-mode cache binds");
            require(pipeline->prepareAtomicLoopCutover(owner, true, 0U, 4'096U, 0U, 16U) == SongRenderLoopStatus::Accepted &&
                pipeline->submitControls(controls(1U, parts ? MixerSourceMode::Stems : MixerSourceMode::Original,
                    tempo, tempo == 1.0F ? 0.0F : 7.0F)) == SongRenderControlStatus::Accepted,
                "hash-mode loop and tuning");
        }
        std::array<float, kMaximumBlock * kStemwiseMultibusStride> actual{}, expected{};
        for (unsigned block = 0U; block < 96U; ++block) {
            const auto a = ordinary.renderMultibus(owner, actual.data(), kMaximumBlock, kMaximumBlock);
            const auto b = diagnostic.renderMultibus(owner, expected.data(), kMaximumBlock, kMaximumBlock);
            require(a.framesProduced != 0U && a.framesProduced == b.framesProduced && a.status == b.status &&
                a.payloadFingerprint.sequence == b.payloadFingerprint.sequence && a.payloadFingerprint.hash == 0U && b.payloadFingerprint.hash != 0U &&
                a.ownership.selectionToken == owner.selectionToken && a.ownership.generation == owner.generation &&
                a.sourceTimeline.startFrame == b.sourceTimeline.startFrame && a.sourceTimeline.endFrameExclusive == b.sourceTimeline.endFrameExclusive &&
                a.stemBusReadyMask == b.stemBusReadyMask && a.loopCycle == b.loopCycle,
                "ordinary playback retains exact block identity, readiness and loop boundary without hashing");
            require(std::memcmp(actual.data(), expected.data(), a.framesProduced * kStemwiseMultibusStride * sizeof(float)) == 0,
                "diagnostic mode must not change any PCM bit");
        }
        require(ordinary.diagnostics().loopWraps > 0U && ordinary.diagnostics().renderPayloadFingerprintsCompleted == 0U &&
            diagnostic.diagnostics().renderPayloadFingerprintsCompleted == 96U && ordinary.diagnostics().nonFiniteRenderPayloadSamples == 0U,
            "only explicit diagnostic mode computes content hashes");
    }
}

void benchmarkRenderPayload(bool tuned, std::uint32_t blockFrames = 480U, bool returnNeutral = false,
                            std::uint32_t partCount = 6U, bool measureFingerprint = false, bool hashEnabled = true) {
    constexpr std::uint32_t benchmarkMaximumBlock = 480U;
    constexpr std::size_t reservoirFrames = 32'768U;
    const std::size_t iterations = 512'000U / blockFrames;
    auto config = configuration(reservoirFrames);
    config.diagnosticPayloadFingerprints = hashEnabled;
    config.maximumBlockFrames = benchmarkMaximumBlock;
    config.stemLaneCount = partCount;
    SongRenderPipeline pipeline(config);
    const SongRenderOwnership owner{71U, 17U};
    require(pipeline.beginSong(owner, (owner).generation, 0U, 0) == SongRenderResetStatus::Ok, "benchmark owner");
    const auto input = tone(reservoirFrames, 0U);
    for (std::uint32_t lane = 0U; lane <= partCount; ++lane) {
        require(pipeline.appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation,
            0U, input.data(), reservoirFrames).status == TimelineAppendStatus::Accepted,
            "benchmark declared lane");
    }
    CurrentSongPcmCache loopCache;
    bindLoopFixture(loopCache, pipeline, owner, 0U, input, partCount, false);
    require(pipeline.prepareAtomicLoopCutover(owner, true, 0U, reservoirFrames, 0U, 16U) == SongRenderLoopStatus::Accepted,
            "benchmark loop");
    require(pipeline.submitControls(controls(1U, partCount ? MixerSourceMode::Stems : MixerSourceMode::Original,
                                           tuned && measureFingerprint ? .5F : 1.0F,
                                           tuned ? (measureFingerprint ? 12.0F : 3.0F) : 0.0F)) == SongRenderControlStatus::Accepted,
            "benchmark controls");
    std::array<float, benchmarkMaximumBlock * kStemwiseMultibusStride> output{};
    if (returnNeutral) {
        for (unsigned warm = 0U; warm < 128U; ++warm) {
            require(pipeline.renderMultibus(owner, output.data(), benchmarkMaximumBlock, blockFrames).framesProduced != 0U,
                    "benchmark tuned warmup");
        }
        require(pipeline.submitControls(controls(2U, MixerSourceMode::Stems, 1.0F, 0.0F)) ==
                    SongRenderControlStatus::Accepted, "benchmark return neutral");
        for (unsigned warm = 0U; warm < 128U; ++warm) {
            require(pipeline.renderMultibus(owner, output.data(), benchmarkMaximumBlock, blockFrames).framesProduced != 0U,
                    "benchmark neutral warmup");
        }
    }
    std::vector<std::int64_t> timings(iterations);
    std::chrono::nanoseconds elapsed{};
    std::chrono::nanoseconds fingerprintElapsed{};
    std::uint64_t hash = kFnvOffset;
    std::uint64_t frames = 0U;
    const auto allocationsBefore = allocation_probe::count.load(std::memory_order_relaxed);
    allocation_probe::enabled.store(true, std::memory_order_release);
    for (std::size_t iteration = 0; iteration < iterations; ++iteration) {
        const auto begin = std::chrono::steady_clock::now();
        const auto result = pipeline.renderMultibus(owner, output.data(), benchmarkMaximumBlock, blockFrames);
        const auto duration = std::chrono::steady_clock::now() - begin;
        elapsed += duration;
        timings[iteration] = std::chrono::duration_cast<std::chrono::nanoseconds>(duration).count();
        require(result.status == SongRenderStatus::Rendered, "benchmark render");
        frames += result.framesProduced;
        if (measureFingerprint) {
            std::uint64_t nonfinite = 0U;
            const auto fingerprintBegin = std::chrono::steady_clock::now();
            const auto measured = stemwise::audio::engine::payload_fingerprint::fingerprintRenderPayload(
                output.data(), result.framesProduced, nonfinite);
            fingerprintElapsed += std::chrono::steady_clock::now() - fingerprintBegin;
            require(nonfinite == 0U && (hashEnabled ? measured == result.payloadFingerprint.hash : result.payloadFingerprint.hash == 0U),
                "paired benchmark hashes the identical complete production payload");
        }
        // Hash every float bit outside the timed region, including transitions and loop seams.
        for (std::size_t sample = 0; sample < result.framesProduced * kStemwiseMultibusStride; ++sample) {
            hash ^= std::bit_cast<std::uint32_t>(output[sample]);
            hash *= kFnvPrime;
        }
    }
    allocation_probe::enabled.store(false, std::memory_order_release);
    const auto allocations = allocation_probe::count.load(std::memory_order_relaxed) - allocationsBefore;
    require(allocations == 0U, "benchmark render allocation");
    std::sort(timings.begin(), timings.end());
    std::cout << "mode=" << (returnNeutral ? "returned-neutral" : (tuned ? "tuned" : "direct")) << " block=" << blockFrames
              << " frames=" << frames << " render_ns=" << elapsed.count()
              << " p50_ns=" << timings[timings.size() / 2U]
              << " p95_ns=" << timings[(timings.size() - 1U) * 95U / 100U]
              << " pcm_hash=" << hash << " allocations=" << allocations << '\n';
    if (measureFingerprint) std::cout << "parts=" << partCount
        << " paired_fingerprint_ns=" << fingerprintElapsed.count()
        << " diagnostic_hash=" << hashEnabled << " actual_render_ns=" << elapsed.count() << '\n';
}

void maximumMusicalGridRendersItsAuthoredTwoHourEndpoint() {
    auto configured = configuration();
    configured.maximumBeatGridPoints = stemwise::audio::engine::kMaximumNativeBeatGridPoints;
    SongRenderPipeline pipeline(configured);
    const SongRenderOwnership owner{591U, 1U};
    constexpr std::uint64_t finalBeatFrame = 7'200'000ULL * 48U;
    require(pipeline.beginSong(owner, owner.generation, finalBeatFrame, 0U) == SongRenderResetStatus::Ok,
        "seek directly to the musical grid endpoint");
    auto snapshot = controls(1U);
    snapshot.mixer.metronome.mode = MetronomeMode::BeatGrid;
    snapshot.mixer.metronome.gain = 0.5F;
    require(pipeline.submitControls(snapshot) == SongRenderControlStatus::Accepted, "maximum grid controls");
    std::vector<std::int64_t> grid(72'001U);
    std::vector<std::uint8_t> accents(grid.size(), 0U);
    for (std::size_t beat = 0U; beat < grid.size(); ++beat) grid[beat] = static_cast<std::int64_t>(beat * 4'800U);
    accents.back() = 1U;
    require(pipeline.setBeatGrid(owner, grid.data(), accents.data(), grid.size()),
        "native musical maximum keeps every base beat");
    const auto pcm = constantPcm(kMaximumBlock, 0.0F);
    require(pipeline.appendLanePcm(SongRenderLane::Original, owner.generation, finalBeatFrame,
        pcm.data(), kMaximumBlock).status == TimelineAppendStatus::Accepted, "endpoint Original appended");
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> payload{};
    const auto result = pipeline.renderMultibus(owner, payload.data(), kMaximumBlock, kMaximumBlock);
    require(result.framesProduced == kMaximumBlock && result.sourceTimeline.startFrame == finalBeatFrame,
        "endpoint render uses the exact seek position");
    double clickEnergy = 0.0;
    for (std::size_t frame = 0U; frame < kMaximumBlock; ++frame) {
        const auto click = payload[frame * kStemwiseMultibusStride + kStemwiseMultibusClickChannel];
        clickEnergy += static_cast<double>(click) * click;
    }
    require(clickEnergy > 0.0 && pipeline.mixerDiagnostics().metronomeAccentEvents == 1U,
        "the final authored accent produces actual click PCM");
}

void tunedLateStemsKeepAudibleOriginalUntilTheirProcessedOutputIsValid() {
    for (const auto& [tempo, pitch] : {std::pair{1.25F, 4.0F}, std::pair{0.5F, -12.0F},
                                    std::pair{2.0F, 12.0F}}) {
        constexpr std::size_t frames = 131'072U;
        SongRenderPipeline pipeline(configuration(frames));
        const SongRenderOwnership owner{590U, 1U};
        require(pipeline.beginSong(owner, owner.generation, 0U, 0U) == SongRenderResetStatus::Ok,
            "tuned late-stem owner begins");
        require(pipeline.submitControls(controls(1U, MixerSourceMode::Original, tempo, pitch)) ==
            SongRenderControlStatus::Accepted, "tuned Original controls accepted");
        const auto original = tone(frames, 0U, 0.1F, 521.0);
        require(pipeline.appendLanePcm(SongRenderLane::Original, owner.generation, 0U,
            original.data(), frames).status == TimelineAppendStatus::Accepted,
            "tuned late-stem Original appended");
        std::array<float, kMaximumBlock * 2U> output{};
        for (std::size_t block = 0U; block < 32U; ++block) {
            require(renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock).framesProduced ==
                kMaximumBlock, "Original tuning history advances before late stems");
        }
        const auto stem = tone(frames, 0U, 0.01F, 347.0);
        for (std::size_t lane = 1U; lane < kSongRenderLaneCount; ++lane) {
            require(pipeline.appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation,
                0U, stem.data(), frames).status == TimelineAppendStatus::Accepted,
                "late nonzero stem is appended");
        }
        require(pipeline.submitControls(controls(2U, MixerSourceMode::Stems, tempo, pitch)) ==
            SongRenderControlStatus::Accepted, "late Stems requested while tuning");
        bool heardReadyStems = false;
        for (std::size_t block = 0U; block < 128U; ++block) {
            if (block == 4U || block == 8U) require(pipeline.submitControls(controls(
                block == 4U ? 3U : 4U, block == 4U ? MixerSourceMode::Original : MixerSourceMode::Stems,
                tempo, pitch)) == SongRenderControlStatus::Accepted, "source toggle preserves stem priming");
            const auto result = renderFinal(pipeline, owner, output.data(), kMaximumBlock, kMaximumBlock);
            require(result.framesProduced == kMaximumBlock, "late-stem render continues without stalling");
            double energy = 0.0;
            for (const auto sample : output) energy += static_cast<double>(sample) * sample;
            if (energy / output.size() <= 0.0004) std::cerr << "late-stem tempo=" << tempo << " pitch=" << pitch
                << " block=" << block << " ready=" << result.stemBusReadyMask << " energy=" << energy / output.size() << '\n';
            require(energy / output.size() > 0.0004,
                "late-stem readiness must not fade Original into zero-filled DSP history");
            require((result.lateMixEndpoint.sourceMode == MixerSourceMode::Original) || result.stemBusReadyMask == kStemwiseAllStemBusesReadyMask,
                "Original fallback cannot claim ready Stems");
            if (result.stemBusReadyMask == kStemwiseAllStemBusesReadyMask) heardReadyStems = true;
        }
        require(heardReadyStems, "late processed stems eventually become ready");
    }
}

void optionalStemRetirementPreservesTunedOriginalTail() {
    const auto configured = configuration(65'536U, 32U);
    SongRenderPipeline retained(configured), reference(configured);
    constexpr SongRenderOwnership owner{499U, 1U};
    const auto original = tone(65'536U, 0U, 0.08F, 521.0);
    for (auto *pipeline : {&retained, &reference}) {
        require(pipeline->beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok,
            "tuned optional retirement begins Original");
        require(pipeline->submitControls(controls(1U, MixerSourceMode::Original, 1.2F, 3.0F)) ==
                SongRenderControlStatus::Accepted, "tuned optional retirement controls");
        require(pipeline->appendLanePcm(SongRenderLane::Original, owner.generation, 0U,
            original.data(), original.size() / 2U).status == TimelineAppendStatus::Accepted,
            "tuned optional retirement retains independent Original bytes");
        // Match failed attachment: one required decoder is missing, so the nonzero optional
        // inputs are never admitted to DSP. The reference keeps that same failed package.
        for (std::size_t lane = 2U; lane < kSongRenderLaneCount; ++lane)
            require(pipeline->appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation, 0U,
                original.data(), original.size() / 2U).status == TimelineAppendStatus::Accepted,
                "tuned optional retirement stages incomplete optional inputs");
    }
    std::array<float, kMaximumBlock * 2U> actual{}, expected{};
    bool detached = false;
    for (std::size_t block = 0U; block < 32U; ++block) {
        if (block == 8U) {
            const auto before = retained.timePitchDiagnostics();
            auto prepared = SongRenderPipeline::prepareStemLaneCount(configured, 0U);
            require(prepared && retained.adoptPreparedStemLaneCount(*prepared) &&
                    retained.stemLaneCount() == 0U, "optional retirement adopts declared Original-only width");
            const auto after = retained.timePitchDiagnostics();
            require(after.generation == before.generation && after.resetCount == before.resetCount &&
                    after.primeCount == before.primeCount && after.processCalls == before.processCalls,
                "optional retirement cannot replace or reset the processed Original DSP tail");
            detached = true;
        }
        const auto a = renderFinal(retained, owner, actual.data(), kMaximumBlock, kMaximumBlock);
        const auto b = renderFinal(reference, owner, expected.data(), kMaximumBlock, kMaximumBlock);
        require(a.status == b.status && a.framesProduced == b.framesProduced &&
                a.sourceTimeline.startFrame == b.sourceTimeline.startFrame &&
                a.sourceTimeline.endFrameExclusive == b.sourceTimeline.endFrameExclusive,
            "optional retirement preserves exact Original render progress");
        if (a.framesProduced != 0U) {
            require(std::memcmp(actual.data(), expected.data(), a.framesProduced * 2U * sizeof(float)) == 0,
                "retired optional inputs/tails cannot change any tuned Original output sample");
            if (detached) require(a.stemBusReadyMask == 0U && (a.lateMixEndpoint.sourceMode == MixerSourceMode::Original) &&
                    a.lateMixEndpoint.requiredStemMask == 0U,
                "retired optional tails cannot claim ready or audible Stems");
        }
    }
    require(detached && retained.timePitchDiagnostics().primeCount == reference.timePitchDiagnostics().primeCount,
        "optional retirement does not re-prime Original after the detach boundary");
}

void unusedLaneChangesPreserveOriginalDspAndFractionalPhase() {
    auto configured = configuration(65'536U, 32U);
    configured.stemLaneCount = 0U;
    SongRenderPipeline changed(configured), reference(configured);
    constexpr SongRenderOwnership owner{519U, 1U};
    const auto pcm = tone(65'536U, 0U, 0.15F, 440.0);
    for (auto *pipeline : {&changed, &reference}) {
        require(pipeline->beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok,
            "attachment comparison begins");
        require(pipeline->appendLanePcm(SongRenderLane::Original, owner.generation, 0U,
            pcm.data(), pcm.size() / 2U).status == TimelineAppendStatus::Accepted,
            "attachment comparison Original admitted");
        require(pipeline->submitControls(controls(1U, MixerSourceMode::Original, .83F, 3.0F)) ==
            SongRenderControlStatus::Accepted, "attachment comparison tunes Original");
    }
    std::array<float, kMaximumBlock * 2U> actual{}, expected{};
    for (unsigned block = 0U; block < 80U; ++block) {
        if (block == 8U || block == 24U || block == 26U || block == 48U)
            require(changed.configureStemLaneCount(block == 24U ? 0U : 2U),
                "unused optional lane attachment accepted");
        if (block == 26U || block == 48U) {
            for (auto *pipeline : {&changed, &reference})
                require(pipeline->submitControls(controls(block, MixerSourceMode::Original,
                    block == 26U ? 1.17F : .91F, block == 26U ? -2.0F : 4.0F)) ==
                    SongRenderControlStatus::Accepted, "attachment during tune update accepted");
        }
        const auto a = renderFinal(changed, owner, actual.data(), kMaximumBlock, 191U);
        const auto b = renderFinal(reference, owner, expected.data(), kMaximumBlock, 191U);
        require(a.status == b.status && a.framesProduced == b.framesProduced &&
                a.sourceTimeline.startFrame == b.sourceTimeline.startFrame &&
                a.sourceTimeline.endFrameExclusive == b.sourceTimeline.endFrameExclusive,
            "lane changes preserve exact fractional Original progression");
        require(std::memcmp(actual.data(), expected.data(), a.framesProduced * 2U * sizeof(float)) == 0,
            "adding/removing/readding unused lanes changed tuned Original PCM");
    }
    require(changed.timePitchDiagnostics().primeCount == reference.timePitchDiagnostics().primeCount,
        "optional lane changes did not reprime Original");
}

void recreatedStemLanesStartWithFreshTimelineAndEndState() {
    auto configured = configuration();
    configured.stemLaneCount = 0U;
    SongRenderPipeline pipeline(configured), reference(configured);
    constexpr SongRenderOwnership owner{8'105U, 1U};
    const auto original = tone(8'192U, 0U, .15F, 440.0);
    for (auto *current : {&pipeline, &reference}) {
        require(current->beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok, "replacement begins");
        require(current->appendLanePcm(SongRenderLane::Original, owner.generation, 0U,
            original.data(), 8'192U).status == TimelineAppendStatus::Accepted, "replacement Original admitted");
    }
    std::array<float, kMaximumBlock * 2U> actual{}, expected{};
    for (const std::size_t parts : {2U, 8U, 9U}) {
        for (const std::uint64_t start : {0U, 64U, 128U}) {
            require(pipeline.configureStemLaneCount(parts), "replacement lanes created");
            for (std::size_t lane = 1U; lane <= parts; ++lane) {
                const auto end = 512U + start * 8U;
                const auto samples = constantPcm(end - start, .01F * static_cast<float>(lane));
                const auto role = static_cast<SongRenderLane>(lane);
                require(!pipeline.laneEndSnapshot(role).marked, "replacement inherited an obsolete endpoint");
                require(pipeline.appendLanePcm(role, owner.generation, start, samples.data(), end - start).status ==
                    TimelineAppendStatus::Accepted, "replacement rejected its first nonzero timeline frame");
                require(pipeline.markLaneEndOfStream(role, owner.generation, end) == SongRenderEosStatus::Accepted,
                    "replacement rejected a changed endpoint");
                std::vector<float> copied(samples.size());
                require(pipeline.copyRetainedLanePcm(owner, role, start, copied.data(), end - start, end - start).framesCopied ==
                    end - start && copied == samples, "replacement retained obsolete optional PCM");
            }
            require(pipeline.configureStemLaneCount(0U), "replacement lanes retired");
            const auto a = renderFinal(pipeline, owner, actual.data(), kMaximumBlock, 64U);
            const auto b = renderFinal(reference, owner, expected.data(), kMaximumBlock, 64U);
            require(a.status == b.status && a.framesProduced == b.framesProduced &&
                std::memcmp(actual.data(), expected.data(), a.framesProduced * 2U * sizeof(float)) == 0,
                "replacement changed retained Original PCM");
        }
    }
}

void dynamicSelectedPartsUseExactMasksAndPreserveOriginalOnAttachment() {
    constexpr std::size_t frames = 512U;
    for (const std::size_t count : {0U, 2U, 6U, 9U}) {
        auto configured = configuration();
        configured.stemLaneCount = static_cast<std::uint32_t>(count);
        SongRenderPipeline pipeline(configured);
        const SongRenderOwnership owner{400U + count, 1U};
        require(pipeline.beginSong(owner, (owner).generation, 0U, 0) == SongRenderResetStatus::Ok,
            "dynamic package begin");
        require(pipeline.submitControls(controls(1U, count == 0U
            ? MixerSourceMode::Original : MixerSourceMode::Stems)) == SongRenderControlStatus::Accepted,
            "dynamic package controls");
        for (std::size_t lane = 0U; lane <= count; ++lane) {
            const auto pcm = constantPcm(frames, 0.003F * static_cast<float>(lane + 1U));
            require(pipeline.appendLanePcm(static_cast<SongRenderLane>(lane), owner.generation,
                0U, pcm.data(), frames).status == TimelineAppendStatus::Accepted,
                "declared dynamic lane append");
        }
        std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
        const auto allocationsBefore = allocation_probe::count.load(std::memory_order_relaxed);
        allocation_probe::enabled.store(true, std::memory_order_release);
        const auto rendered = pipeline.renderMultibus(owner, output.data(), kMaximumBlock, kMaximumBlock);
        allocation_probe::enabled.store(false, std::memory_order_release);
        require(rendered.status == SongRenderStatus::Rendered && rendered.framesProduced == kMaximumBlock,
            "declared dynamic package render");
        require(allocation_probe::count.load(std::memory_order_relaxed) == allocationsBefore,
            "dynamic package render allocated");
        require(rendered.stemBusReadyMask == (1U << count) - 1U &&
            rendered.lateMixEndpoint.requiredStemMask == (1U << count) - 1U,
            "dynamic package gated on undeclared capacity lanes");
        for (std::size_t frame = 0U; frame < rendered.framesProduced; ++frame) {
            require(output[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(0U)] == 0.003F,
                "dynamic package changed Original PCM");
            for (std::size_t part = 0U; part < stemwise::audio::kStemwiseStemBusCount; ++part) {
                const float expected = part < count ? 0.003F * static_cast<float>(part + 2U) : 0.0F;
                require(output[frame * kStemwiseMultibusStride + stemwiseStemChannel(part, 0U)] == expected,
                    "dynamic package lost or fabricated a part bus");
            }
        }
        if (count < stemwise::audio::kStemwiseStemBusCount) {
            const auto pcm = constantPcm(1U, 0.1F);
            require(pipeline.appendLanePcm(static_cast<SongRenderLane>(count + 1U), owner.generation,
                kMaximumBlock, pcm.data(), 1U).status == TimelineAppendStatus::InvalidArgument,
                "undeclared part accepted PCM");
        }
    }
    auto originalOnly = configuration();
    originalOnly.stemLaneCount = 0U;
    SongRenderPipeline pipeline(originalOnly);
    const SongRenderOwnership owner{500U, 1U};
    require(pipeline.beginSong(owner, (owner).generation, 0U, 0) == SongRenderResetStatus::Ok, "late package begin");
    const auto original = tone(frames, 0U, 0.1F, 521.0);
    require(pipeline.appendLanePcm(SongRenderLane::Original, owner.generation, 0U,
        original.data(), frames).status == TimelineAppendStatus::Accepted, "late package Original append");
    std::array<float, 64U * kStemwiseMultibusStride> output{};
    require(pipeline.renderMultibus(owner, output.data(), 64U, 64U).status == SongRenderStatus::Rendered,
        "late package initial Original render");
    require(pipeline.configureStemLaneCount(2U), "late package attachment rejected");
    require(!pipeline.configureStemLaneCount(stemwise::audio::kStemwiseStemBusCount + 1U) && pipeline.stemLaneCount() == 2U,
        "invalid attachment changed declared width");
    const auto rendered = pipeline.renderMultibus(owner, output.data(), 64U, 64U);
    require(rendered.status == SongRenderStatus::Rendered && rendered.sourceTimeline.startFrame == 64.0,
        "late package attachment reset Original position");
    for (std::size_t frame = 0U; frame < 64U; ++frame) {
        require(output[frame * kStemwiseMultibusStride + stemwiseOriginalChannel(0U)] ==
            original[(frame + 64U) * 2U], "late package attachment discarded retained Original PCM");
    }
}

void speculativePrefetchCannotEvictTwoLivePipelineWorkingSets() {
    CurrentSongPcmCache cache;
    const SongRenderOwnership owner{819U, 1U};
    const CurrentSongCacheKey key{819U, 1U, 4U, 5U, 0U};
    const auto pcm = constantPcm(16'384U, .1F);
    require(cache.begin(key) == CurrentSongCacheBeginStatus::Accepted &&
        cache.offer(key, 0U, 0U, pcm.data(), 16'384U).status == CurrentSongCacheOfferStatus::Accepted &&
        cache.sealLane(key, 0U, 16'384U) == CurrentSongCacheSealStatus::Accepted, "two-reader Original sealed");
    auto configured = configuration(); configured.stemLaneCount = 0U;
    SongRenderPipeline live(configured), spare(configured);
    auto control = controls(1U, MixerSourceMode::Original);
    require(live.beginSong(owner, owner.generation, 4096U, 0) == SongRenderResetStatus::Ok &&
        live.bindCurrentSongPcmCache(owner, &cache) && live.submitControls(control) == SongRenderControlStatus::Accepted &&
        spare.prepareAtomicLoopContinuationFrom(live, owner, true, 0U, 12'288U, 0U, 0U, 1U) ==
            SongRenderLoopStatus::Accepted, "real active and prepared cache readers");
    cache.enableAsyncReads([](void*) noexcept {}, nullptr);
    std::array<float, 128U> warm{};
    for (const auto frame : {4096U, 0U}) {
        static_cast<void>(cache.copyLane(0U, frame, warm.data(), 64U, 64U));
        cache.serviceAsyncReads();
        require(cache.copyLane(0U, frame, warm.data(), 64U, 64U), "both required windows start resident");
        cache.serviceAsyncReads();
    }
    std::array<float, kMaximumBlock * kStemwiseMultibusStride> output{};
    unsigned liveMisses = 0U, spareMisses = 0U;
    for (unsigned turn = 0U; turn != 140U; ++turn) {
        const auto active = live.renderMultibus(owner, output.data(), kMaximumBlock, 64U);
        if (active.framesProduced) live.requestUpcomingCacheInput();
        const auto next = spare.renderMultibus(owner, output.data(), kMaximumBlock, 64U);
        if (next.framesProduced) spare.requestUpcomingCacheInput();
        liveMisses += active.status == SongRenderStatus::NeedOriginalData;
        spareMisses += next.status == SongRenderStatus::NeedOriginalData;
        cache.serviceAsyncReads();
    }
    require(liveMisses == 0U && spareMisses == 0U,
        ("speculation displaced required live PCM: active=" + std::to_string(liveMisses) +
            " continuation=" + std::to_string(spareMisses) +
            " reads=" + std::to_string(cache.snapshot().diskReadOperations)).c_str());
    require(live.renderMultibus({owner.selectionToken, owner.generation + 1U}, output.data(),
        kMaximumBlock, 64U).status == SongRenderStatus::InvalidOwnership,
        "wrong render owner cannot publish cache demand");
    SongRenderPipeline third(configured), fourth(configured);
    require(third.beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok &&
        third.bindCurrentSongPcmCache(owner, &cache) &&
        fourth.beginSong(owner, owner.generation, 0U, 0) == SongRenderResetStatus::Ok &&
        !fourth.bindCurrentSongPcmCache(owner, &cache), "physical pipeline admission rejects fourth reader");
    spare.releaseCacheReadRequirements();
    cache.serviceAsyncReads();
    require(fourth.bindCurrentSongPcmCache(owner, &cache),
        "retired continuation releases its exact cache reader slot");
}

#include "RetainedContinuationCopyCases.inc"

}  // namespace

int main(int argumentCount, char **arguments) {
    struct Case { std::string_view selector; void (*run)(); };
    const Case cases[]{
#define STEMWISE_FOCUSED_CASE(selector, ...) {selector, __VA_ARGS__},
#include "SongRenderPipelineCases.def"
#undef STEMWISE_FOCUSED_CASE
    };
    try {
        if (argumentCount == 2 && std::string_view(arguments[1]) == "--list-cases") {
            for (const auto &test : cases) std::cout << test.selector << '\n';
            return EXIT_SUCCESS;
        }
        if (argumentCount == 2 && std::string_view(arguments[1]) == "--benchmark-responsive") {
            for (const std::uint32_t block : {64U, 256U}) {
                benchmarkRenderPayload(false, block);
                benchmarkRenderPayload(true, block);
                benchmarkRenderPayload(true, block, true);
            }
            return 0;
        }

        if (argumentCount == 2 && std::string_view(arguments[1]) == "--benchmark") {
            benchmarkRenderPayload(false);
            benchmarkRenderPayload(true);
            benchmarkRenderPayload(true, 480U, true);
            return 0;
        }

        if (argumentCount == 2 && std::string_view(arguments[1]) == "--benchmark-without-fingerprint") {
            for (const auto parts : {0U, 2U, 9U}) {
                benchmarkRenderPayload(false, 480U, false, parts, true, false);
                benchmarkRenderPayload(true, 480U, false, parts, true, false);
            }
            return 0;
        }

        if (argumentCount == 2 && std::string_view(arguments[1]) == "--benchmark-fingerprint") {
            for (const std::uint32_t parts : {0U, 2U, 9U}) {
                benchmarkRenderPayload(false, 480U, false, parts, true);
                benchmarkRenderPayload(true, 480U, false, parts, true);
            }
            return 0;
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
        std::cout << "SongRenderPipeline cases passed\n";
        return EXIT_SUCCESS;
    } catch (const std::exception &error) {
        allocation_probe::enabled.store(false, std::memory_order_release);
        std::cerr << "SongRenderPipeline test failure: " << error.what() << '\n';
        std::_Exit(EXIT_FAILURE);
    }
}
