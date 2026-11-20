#include "AllocationProbeAttributes.h"
#include "NativeAudioOutputMultibusCallbackStage.h"
#include "AudibleTimelineHistory.h"

#include <array>
#include <atomic>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <new>
#include <stdexcept>
#include <thread>

namespace stemwise::audio::late_control_test {
std::atomic<bool> hold{false}, entered{false}, release{false}, timedOut{false};
void afterWriteBegins(std::uint32_t, std::uint64_t publication) noexcept {
    if (!hold.load() || publication != 3U) return;
    entered.store(true);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!release.load()) {
        if (std::chrono::steady_clock::now() >= deadline) { timedOut.store(true); break; }
        std::this_thread::yield();
    }
}
}

namespace allocation_probe {
std::atomic<bool> enabled{false};
std::atomic<std::uint64_t> count{0U};
}  // namespace allocation_probe

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (allocation_probe::enabled) ++allocation_probe::count;
    if (void *memory = std::malloc(size)) return memory;
    throw std::bad_alloc();
}

STEMWISE_ALLOCATION_HOOK void *operator new[](std::size_t size) {
    return ::operator new(size);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *memory) noexcept {
    std::free(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory) noexcept {
    ::operator delete(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete(void *memory, std::size_t) noexcept {
    std::free(memory);
}

STEMWISE_ALLOCATION_HOOK void operator delete[](void *memory, std::size_t) noexcept {
    ::operator delete(memory);
}

namespace {

using stemwise::audio::LateStemMixControlMailbox;
using stemwise::audio::LateStemMixControlSnapshot;
using stemwise::audio::LateStemSourceMode;
using stemwise::audio::NativeAudioOutputMultibusCallbackStage;
using stemwise::audio::AudibleTimelineHistory;
using stemwise::audio::OutputTimelineFrameTag;
using stemwise::audio::OutputTimelineLoopPolicy;
using stemwise::audio::RenderBusFrameTag;
using stemwise::audio::SpscTaggedMultibusRingBuffer;
using stemwise::audio::kStemwiseAllStemBusesReadyMask;
using stemwise::audio::kStemwiseMultibusClickChannel;
using stemwise::audio::kStemwiseMultibusEnvelopeChannel;
using stemwise::audio::kStemwiseMultibusStride;
using stemwise::audio::stemwiseOriginalChannel;
using stemwise::audio::stemwiseStemChannel;

constexpr std::size_t kFrames = 8U;
constexpr float kTolerance = 1.0e-6F;

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

void requireNear(float actual, float expected, const char *message) {
    require(std::abs(actual - expected) <= kTolerance, message);
}

LateStemMixControlSnapshot control(
    std::uint64_t generation,
    std::uint64_t token,
    std::uint64_t publication,
    LateStemSourceMode source = LateStemSourceMode::Original) noexcept {
    LateStemMixControlSnapshot result;
    result.publicationSequence = publication;
    result.generation = generation;
    result.selectionToken = token;
    result.canonicalControlRevision = 10U + publication;
    result.sourceIntentRevision = 20U + publication;
    result.stemIntentRevision = 30U + publication;
    result.sourceMode = source;
    result.effectiveStemGains.fill(1.0F);
    result.musicGain = 1.0F;
    result.mixHeadroom = 1.0F;
    result.sourceRampFrames = 240U;
    result.laneRampFrames = 240U;
    result.masterRampFrames = 240U;
    return result;
}

RenderBusFrameTag tagFor(const LateStemMixControlSnapshot &endpoint) noexcept {
    return {
        .generation = endpoint.generation,
        .selectionToken = endpoint.selectionToken,
        .payloadSequence = 71U,
        .payloadHash = 91U,
        .canonicalControlRevision = endpoint.canonicalControlRevision,
        .sourceIntentRevision = endpoint.sourceIntentRevision,
        .stemIntentRevision = endpoint.stemIntentRevision,
        .stemBusReadyMask = kStemwiseAllStemBusesReadyMask,
        .loopRevision = 0U,
        .loopCycle = 0U,
        .loopEnabled = false,
    };
}

std::array<float, kFrames * kStemwiseMultibusStride> payload(
    float originalLeft,
    float originalRight,
    float stemSample,
    float click) noexcept {
    std::array<float, kFrames * kStemwiseMultibusStride> result{};
    for (std::size_t frame = 0U; frame < kFrames; ++frame) {
        float *sample = result.data() + frame * kStemwiseMultibusStride;
        sample[stemwiseOriginalChannel(0U)] = originalLeft;
        sample[stemwiseOriginalChannel(1U)] = originalRight;
        for (std::size_t lane = 0U; lane < 6U; ++lane) {
            sample[stemwiseStemChannel(lane, 0U)] = stemSample;
            sample[stemwiseStemChannel(lane, 1U)] = -stemSample;
        }
        sample[kStemwiseMultibusClickChannel] = click;
        sample[kStemwiseMultibusEnvelopeChannel] = 1.0F;
    }
    return result;
}

void numericalTupleStraddlesDoNotSuppressLateMixAcknowledgement() {
    SpscTaggedMultibusRingBuffer ring(32U);
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    const auto endpoint = control(17U, 29U, 1U);
    require(mailbox.resetRole(0U, endpoint.generation, endpoint.selectionToken), "bind numerical owner role");
    require(mailbox.publish(0U, endpoint), "numerical endpoint publication");
    auto unity = tagFor(endpoint);
    unity.actualTempo = 1.0F;
    auto processed = unity;
    processed.payloadSequence += 1U;
    processed.actualTempo = 1.5F;
    processed.actualPitchSemitones = 3.0F;
    const auto samples = payload(.08F, -.06F, .01F, 0.0F);
    require(ring.writeTaggedTimeline(samples.data(), 4U, unity, 0.0, 4.0) == 4U &&
        ring.writeTaggedTimeline(samples.data(), 4U, processed, 4.0, 10.0) == 4U,
        "two numerical producer tuples queued under one mix revision");
    std::array<float, kFrames * 2U> pcm{};
    const auto mixed = stage.readAndMix(0U, 17U, ring, mailbox, pcm.data(), kFrames);
    require(mixed.frameCount == kFrames && mixed.audibleControlTupleCoherent &&
        !mixed.audibleTuningTupleCoherent,
        "numerical straddle must withhold tuning independently of settled source/gain");
    require(ring.writeTaggedTimeline(samples.data(), kFrames, processed, 10.0, 22.0) == kFrames,
        "processed numerical tuple queued");
    const auto settled = stage.readAndMix(0U, 17U, ring, mailbox, pcm.data(), kFrames);
    require(settled.audibleControlTupleCoherent && settled.audibleTuningTupleCoherent &&
        settled.firstTag.actualTempo == 1.5F && settled.lastTag.actualPitchSemitones == 3.0F,
        "whole processed tuple independently settles numerical evidence");
    processed.actualTempo = 0.0F;
    processed.actualPitchSemitones = 0.0F;
    require(ring.writeTaggedTimeline(samples.data(), kFrames, processed, 22.0, 34.0) == kFrames,
        "unknown transition tuple queued");
    const auto transition = stage.readAndMix(0U, 17U, ring, mailbox, pcm.data(), kFrames);
    require(transition.audibleControlTupleCoherent && !transition.audibleTuningTupleCoherent,
        "a numerical transition must not fabricate unity or acknowledge its requested endpoint");
}

bool heldPublicationPreservesCommittedPcm(bool warm, bool stems = false, std::uint32_t role = 0U) {
    using namespace stemwise::audio::late_control_test;
    SpscTaggedMultibusRingBuffer ring(32U);
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    auto first = control(17U, 29U, 1U);
    auto committed = control(17U, 29U, 2U, stems ? LateStemSourceMode::Stems : LateStemSourceMode::Original);
    first.sourceRampFrames = committed.sourceRampFrames = 1U;
    auto newer = control(17U, 29U, 3U);
    newer.sourceRampFrames = 1U;
    const auto samples = payload(.20F, -.10F, .01F, 0.0F);
    std::array<float, kFrames * 2U> pcm{};
    require(mailbox.resetRole(role, 17U, 29U) && mailbox.publish(role, first), "bind lawful control owner");
    if (warm) {
        require(ring.writeTagged(samples.data(), kFrames, tagFor(first)) == kFrames,
            "warm callback cache with the prior legal revision");
        require(stage.readAndMix(role, 17U, ring, mailbox, pcm.data(), kFrames).firstTag.selectionToken == 29U,
            "warm callback observes prior endpoint");
    }
    require(mailbox.publish(role, committed) &&
        ring.writeTagged(samples.data(), kFrames, tagFor(committed), &committed) == kFrames,
        "revision two is completely published before its PCM is committed");
    entered.store(false); release.store(false); timedOut.store(false); hold.store(true);
    std::atomic<bool> publicationSucceeded{false};
    std::jthread writer([&] { publicationSucceeded.store(mailbox.publish(role, newer)); });
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (!entered.load() && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
    const bool held = entered.load();
    allocation_probe::count = 0U;
    allocation_probe::enabled = true;
    const auto read = stage.readAndMix(role, 17U, ring, mailbox, pcm.data(), kFrames);
    allocation_probe::enabled = false;
    const bool returnedDuringHold = !publicationSucceeded.load() && !timedOut.load();
    release.store(true); writer.join(); hold.store(false);
    require(held && returnedDuringHold && !timedOut.load() && publicationSucceeded.load(),
        "callback must finish while the legal newer publisher remains held");
    require(allocation_probe::count == 0U && ring.queuedFrames() == 0U && read.frameCount == kFrames,
        "held-publication callback remains allocation-free and consumes one exact lease");
    const bool valid = read.firstTag.selectionToken == 29U && read.lastTag.selectionToken == 29U &&
        stage.diagnostics().missingControlFrames == 0U && stage.diagnostics().staleControlFrames == 0U &&
        std::abs(pcm[0U] - (stems ? .06F : .20F)) <= kTolerance &&
        std::abs(pcm[1U] - (stems ? -.06F : -.10F)) <= kTolerance;
    if (!valid) std::cerr << "held publisher warm=" << warm << " owner=" << read.firstTag.selectionToken
        << " sample=" << pcm[0U] << " missing=" << stage.diagnostics().missingControlFrames
        << " stale=" << stage.diagnostics().staleControlFrames << '\n';
    require(ring.writeTagged(samples.data(), kFrames, tagFor(newer)) == kFrames,
        "released publisher negative control queues fresh lawful PCM");
    require(stage.readAndMix(role, 17U, ring, mailbox, pcm.data(), kFrames).firstTag.selectionToken == 29U &&
        pcm[0U] == .20F, "normal authorized PCM works after publication completes");
    return valid;
}

void lawfulPublicationCannotWithdrawCommittedPcm() {
    const bool cold = heldPublicationPreservesCommittedPcm(false);
    const bool warm = heldPublicationPreservesCommittedPcm(true);
    const bool coldSpare = heldPublicationPreservesCommittedPcm(false, true, 1U);
    const bool warmSpare = heldPublicationPreservesCommittedPcm(true, true, 1U);
    require(cold && warm && coldSpare && warmSpare,
        "a newer in-progress publication cannot withdraw already authorized queued PCM");
}

void baseAndOverlaySequencesNeverShareAnOrderingNamespace() {
    SpscTaggedMultibusRingBuffer ring(32U);
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    auto stale = control(17U, 29U, 1U);
    auto base = control(17U, 29U, 2U);
    base.publicationSequence = 9'000U;
    base.musicGain = .5F;
    base.masterRampFrames = 1U;
    const auto samples = payload(.20F, -.10F, .01F, 0.0F);
    auto tag = tagFor(base);
    tag.actualTempo = 1.5F; tag.actualPitchSemitones = 7.0F;
    std::array<float, kFrames * 2U> pcm{};
    require(mailbox.resetRole(0U, 17U, 29U) && mailbox.publish(0U, stale), "older overlay publishes legally");
    require(ring.writeTagged(samples.data(), kFrames, tag, &base) == kFrames, "newer rendered base commits");
    auto read = stage.readAndMix(0U, 17U, ring, mailbox, pcm.data(), kFrames);
    requireNear(pcm[0U], .10F, "older readable overlay cannot invalidate the exact committed base");
    require(read.firstTag.actualTempo == 1.5F && read.firstTag.actualPitchSemitones == 7.0F,
        "fallback retains the numerical tuple from actual rendered PCM");
    auto overlay = base;
    overlay.publicationSequence = 2U; overlay.musicGain = .25F;
    require(mailbox.publish(0U, overlay) &&
        ring.writeTagged(samples.data(), kFrames, tag, &base) == kFrames, "compatible gain overlay follows base");
    read = stage.readAndMix(0U, 17U, ring, mailbox, pcm.data(), kFrames);
    requireNear(pcm[0U], .05F, "global overlay two must override local base nine thousand");
    auto futureBase = control(17U, 29U, 3U);
    futureBase.publicationSequence = 1U; futureBase.musicGain = .75F; futureBase.masterRampFrames = 1U;
    require(ring.writeTagged(samples.data(), kFrames, tagFor(futureBase), &futureBase) == kFrames,
        "later rendered revision may have a lower producer-local sequence");
    read = stage.readAndMix(0U, 17U, ring, mailbox, pcm.data(), kFrames);
    requireNear(pcm[0U], .15F, "an incompatible cached overlay cannot displace the later base");
    require(read.firstTag.controlRevision == futureBase.canonicalControlRevision &&
        stage.diagnostics().missingControlFrames == 0U && stage.diagnostics().staleControlFrames == 0U,
        "base fallback reports the actual revision without false authorization faults");
}

void delayedBaseSurvivesAnUnrelatedPublicationButCannotAuthorizeOtherBlocks() {
    SpscTaggedMultibusRingBuffer ring(32U);
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    const auto base = control(17U, 29U, 2U);
    const auto unrelated = control(18U, 39U, 3U);
    require(mailbox.resetRole(0U, 17U, 29U) && mailbox.publish(0U, base) &&
        mailbox.resetRole(0U, 18U, 39U) && mailbox.publish(0U, unrelated), "newer unrelated endpoint is complete");
    const auto samples = payload(.20F, -.10F, .01F, 0.0F);
    require(ring.writeTagged(samples.data(), kFrames, tagFor(base), &base) == kFrames,
        "retained pending PCM may enqueue after an incompatible publication");
    std::array<float, kFrames * 2U> pcm{};
    auto read = stage.readAndMix(0U, 17U, ring, mailbox, pcm.data(), kFrames);
    require(read.firstTag.selectionToken == 29U && pcm[0U] == .20F,
        "immutable base remains valid for the callback's exact outgoing owner");
    require(ring.writeTagged(samples.data(), kFrames, tagFor(base)) == kFrames, "unbacked raw ring negative control");
    read = stage.readAndMix(0U, 17U, ring, mailbox, pcm.data(), kFrames);
    require(read.firstTag.selectionToken == 0U && stage.diagnostics().missingControlFrames == kFrames,
        "a previous block's base must never become cached authority for an unbacked block");
    for (float sample : pcm) require(sample == 0.0F, "unbacked PCM remains silent");
    ring.resetInactive(); stage.reset();
    require(ring.writeTagged(samples.data(), kFrames, tagFor(unrelated), &unrelated) == kFrames,
        "inactive role reuse commits its own exact endpoint");
    read = stage.readAndMix(0U, 18U, ring, mailbox, pcm.data(), kFrames);
    require(read.firstTag.selectionToken == 39U && pcm[0U] == .20F, "role reuse cannot retain outgoing base authority");
}

void malformedBaseCannotBeRescuedByALegitimateLatestOverlay() {
    const auto base = control(17U, 29U, 2U);
    for (unsigned fault = 0U; fault < 6U; ++fault) {
        auto wrong = base;
        if (fault == 0U) ++wrong.generation;
        if (fault == 1U) ++wrong.selectionToken;
        if (fault == 2U) --wrong.canonicalControlRevision;
        if (fault == 3U) --wrong.sourceIntentRevision;
        if (fault == 4U) --wrong.stemIntentRevision;
        if (fault == 5U) wrong.publicationSequence = 0U;
        SpscTaggedMultibusRingBuffer ring(32U);
        LateStemMixControlMailbox mailbox;
        NativeAudioOutputMultibusCallbackStage stage;
        const auto samples = payload(.20F, -.10F, .01F, 0.0F);
        require(mailbox.resetRole(0U, 17U, 29U) && mailbox.publish(0U, base) &&
            ring.writeTagged(samples.data(), kFrames, tagFor(base), &wrong) == kFrames,
            "low-level negative fixture injects a malformed committed base");
        std::array<float, kFrames * 2U> pcm{};
        const auto read = stage.readAndMix(0U, 17U, ring, mailbox, pcm.data(), kFrames);
        require(read.firstTag.selectionToken == 0U && ring.queuedFrames() == 0U &&
            stage.diagnostics().staleControlFrames == kFrames, "malformed base fails closed despite a correct overlay");
        for (float sample : pcm) require(sample == 0.0F, "malformed base cannot emit PCM");
    }
}

void exactOwnerMixesBeforeQueueReleaseWithoutAllocation() {
    SpscTaggedMultibusRingBuffer ring(32U);
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    auto endpoint = control(17U, 29U, 1U);
    endpoint.stemGainPreviewPublicationSequence = 501U;
    endpoint.stemGainPreviewCoversOfferSequence = 601U;
    endpoint.stemGainPreviewActiveMask = 0x15U;
    constexpr std::array<float, 6U> kRawPreviewGains{
        0.125F, 0.25F, 0.375F, 0.5F, 0.625F, 0.75F};
    for (std::size_t lane = 0U; lane < kRawPreviewGains.size(); ++lane) {
        endpoint.stemGainPreviewGainBits[lane] =
            std::bit_cast<std::uint32_t>(kRawPreviewGains[lane]);
    }
    require(mailbox.resetRole(0U, endpoint.generation, endpoint.selectionToken), "bind role");
    require(mailbox.publish(0U, endpoint), "publish endpoint");
    auto queued = payload(0.20F, -0.10F, 0.01F, 0.05F);
    auto unhashedTag = tagFor(endpoint);
    unhashedTag.payloadHash = 0U;
    require(
        ring.writeTaggedTimeline(
            queued.data(),
            kFrames,
            unhashedTag,
            100.0,
            108.0,
            true) == kFrames,
        "prefill exact owner");
    std::array<float, kFrames * 2U> output{};
    std::array<OutputTimelineFrameTag, kFrames> timelineTags{};

    allocation_probe::count = 0U;
    allocation_probe::enabled = true;
    const auto read = stage.readAndMix(
        0U,
        endpoint.generation,
        ring,
        mailbox,
        output.data(),
        kFrames,
        timelineTags.data());
    allocation_probe::enabled = false;

    require(allocation_probe::count == 0U, "callback path allocation-free");
    require(read.frameCount == kFrames, "complete lease mixed");
    for (std::size_t frame = 0U; frame < kFrames; ++frame) {
        require(timelineTags[frame].generation == endpoint.generation, "timeline generation exact");
        require(timelineTags[frame].selectionToken == endpoint.selectionToken, "timeline token exact");
        require(timelineTags[frame].timelineFrame == static_cast<std::int64_t>(100U + frame),
            "per-output-frame timeline exact");
    }
    AudibleTimelineHistory history(kFrames);
    history.publish(900U, timelineTags.data(), timelineTags.size());
    const auto audible = history.position(903U);
    require(audible.valid() && audible.generation == endpoint.generation &&
            audible.selectionToken == endpoint.selectionToken && audible.timelineFrame == 103,
        "device output frame maps back to exact owner timeline");
    require(ring.queuedFrames() == 0U, "lease released after mix");
    require(read.firstTag.generation == endpoint.generation, "generation preserved");
    require(read.firstTag.selectionToken == endpoint.selectionToken, "token preserved");
    require(read.firstTag.controlRevision == endpoint.canonicalControlRevision, "control exact");
    require(
        read.firstTag.stemGainPreviewPublicationSequence == 501U &&
            read.firstTag.stemGainPreviewCoversOfferSequence == 601U &&
            read.firstTag.stemGainPreviewActiveMask == 0x15U &&
            read.firstTag.stemGainPreviewGainBits == endpoint.stemGainPreviewGainBits,
        "raw preview evidence remains distinct from effective gains");
    require(read.audibleControlTupleCoherent, "new owner endpoint is immediately coherent");
    require(read.lastTag.terminalWriteBoundary == kFrames, "terminal boundary preserved");
    require(read.firstTag.timelineFrame == 100, "first timeline frame preserved");
    require(read.lastTag.timelineFrame == 107, "last timeline frame preserved");
    for (std::size_t frame = 0U; frame < kFrames; ++frame) {
        requireNear(output[frame * 2U], 0.25F, "original left plus click");
        requireNear(output[frame * 2U + 1U], -0.05F, "original right plus click");
    }
}

void readinessTransitionRampsWithoutAFirstSampleDiscontinuity() {
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    auto endpoint = control(22U, 34U, 6U, LateStemSourceMode::Stems);
    endpoint.sourceRampFrames = 4U;
    endpoint.laneRampFrames = 4U;
    endpoint.masterRampFrames = 4U;
    require(mailbox.resetRole(0U, endpoint.generation, endpoint.selectionToken), "bind ramp role");
    require(mailbox.publish(0U, endpoint), "publish ramp endpoint");
    auto queued = payload(0.20F, 0.20F, 0.05F, 0.0F);

    SpscTaggedMultibusRingBuffer missing(32U);
    RenderBusFrameTag missingTag = tagFor(endpoint);
    missingTag.stemBusReadyMask = 0U;
    require(missing.writeTagged(queued.data(), kFrames, missingTag) == kFrames, "queue fallback");
    std::array<float, kFrames * 2U> fallbackOutput{};
    const auto fallback = stage.readAndMix(
        0U,
        endpoint.generation,
        missing,
        mailbox,
        fallbackOutput.data(),
        kFrames);
    require(fallback.audibleControlTupleCoherent, "initial safe fallback is settled");
    requireNear(fallbackOutput[(kFrames - 1U) * 2U], 0.20F, "fallback endpoint");

    SpscTaggedMultibusRingBuffer ready(32U);
    RenderBusFrameTag readyTag = tagFor(endpoint);
    require(ready.writeTagged(queued.data(), 4U, readyTag) == 4U, "queue readiness ramp");
    std::array<float, 8U> rampOutput{};
    const auto ramp = stage.readAndMix(
        0U,
        endpoint.generation,
        ready,
        mailbox,
        rampOutput.data(),
        4U);
    require(!ramp.audibleControlTupleCoherent, "ramp is not settled evidence");
    requireNear(
        rampOutput[0U],
        fallbackOutput[(kFrames - 1U) * 2U],
        "readiness ramp first sample is continuous");
    requireNear(rampOutput[6U], 0.30F, "readiness ramp reaches complete stems");
    require(rampOutput[0U] < rampOutput[2U] && rampOutput[2U] < rampOutput[4U] &&
            rampOutput[4U] < rampOutput[6U],
        "readiness ramp is monotonic");

    SpscTaggedMultibusRingBuffer settled(8U);
    require(settled.writeTagged(queued.data(), 1U, readyTag) == 1U, "queue settled frame");
    std::array<float, 2U> settledOutput{};
    const auto settledRead = stage.readAndMix(
        0U,
        endpoint.generation,
        settled,
        mailbox,
        settledOutput.data(),
        1U);
    require(settledRead.audibleControlTupleCoherent, "post-ramp evidence is settled");
    requireNear(settledOutput[0U], 0.30F, "post-ramp stems endpoint");
}

void incompleteStemsNeverLeakAndFallbackKeepsClickContinuous() {
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    auto endpoint = control(23U, 35U, 7U, LateStemSourceMode::Stems);
    endpoint.sourceRampFrames = 4U;
    endpoint.laneRampFrames = 4U;
    endpoint.masterRampFrames = 4U;
    require(mailbox.resetRole(0U, endpoint.generation, endpoint.selectionToken), "bind leak role");
    require(mailbox.publish(0U, endpoint), "publish leak endpoint");

    auto completePayload = payload(0.20F, 0.20F, 0.05F, 0.04F);
    SpscTaggedMultibusRingBuffer complete(32U);
    RenderBusFrameTag completeTag = tagFor(endpoint);
    require(
        complete.writeTagged(completePayload.data(), kFrames, completeTag) == kFrames,
        "queue complete stems before loss");
    std::array<float, kFrames * 2U> completeOutput{};
    const auto completeRead = stage.readAndMix(
        0U,
        endpoint.generation,
        complete,
        mailbox,
        completeOutput.data(),
        kFrames);
    require(completeRead.audibleControlTupleCoherent, "complete stems settled");
    requireNear(completeOutput[(kFrames - 1U) * 2U], 0.34F, "complete stems plus click");

    auto poisonedPartial = payload(0.20F, 0.20F, 20.0F, 0.04F);
    SpscTaggedMultibusRingBuffer partial(32U);
    RenderBusFrameTag partialTag = tagFor(endpoint);
    partialTag.stemBusReadyMask = 0x01U;
    require(partial.writeTagged(poisonedPartial.data(), 4U, partialTag) == 4U, "queue partial stems");
    std::array<float, 8U> fallback{};
    const auto partialRead = stage.readAndMix(
        0U,
        endpoint.generation,
        partial,
        mailbox,
        fallback.data(),
        4U);
    require(!partialRead.audibleControlTupleCoherent, "fallback transition unsettled");
    requireNear(
        fallback[0U],
        completeOutput[(kFrames - 1U) * 2U],
        "ready-to-missing first sample continuity");
    requireNear(fallback[6U], 0.24F, "fallback reaches Original plus current click");
    require(fallback[0U] > fallback[2U] && fallback[2U] > fallback[4U] &&
            fallback[4U] > fallback[6U],
        "fallback is monotonic");
    for (float sample : fallback) {
        require(std::abs(sample) <= 0.34F + kTolerance, "partial stem poison never leaks");
    }

    SpscTaggedMultibusRingBuffer settled(8U);
    require(settled.writeTagged(poisonedPartial.data(), 1U, partialTag) == 1U, "queue fallback endpoint");
    std::array<float, 2U> settledOutput{};
    const auto settledRead = stage.readAndMix(
        0U,
        endpoint.generation,
        settled,
        mailbox,
        settledOutput.data(),
        1U);
    require(settledRead.audibleControlTupleCoherent, "fallback endpoint settles");
    requireNear(settledOutput[0U], 0.24F, "click remains independently mixed");
}

void rapidReadinessRecoveryKeepsTheFallbackContinuous() {
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    auto endpoint = control(24U, 36U, 8U, LateStemSourceMode::Stems);
    endpoint.sourceRampFrames = 4U;
    endpoint.laneRampFrames = 4U;
    endpoint.masterRampFrames = 4U;
    require(mailbox.resetRole(0U, endpoint.generation, endpoint.selectionToken),
        "bind rapid readiness role");
    require(mailbox.publish(0U, endpoint), "publish rapid readiness endpoint");

    auto completePayload = payload(0.20F, 0.20F, 0.05F, 0.0F);
    RenderBusFrameTag completeTag = tagFor(endpoint);
    SpscTaggedMultibusRingBuffer complete(32U);
    require(complete.writeTagged(completePayload.data(), kFrames, completeTag) == kFrames,
        "queue rapid readiness complete prefix");
    std::array<float, kFrames * 2U> completeOutput{};
    const auto completeRead = stage.readAndMix(
        0U,
        endpoint.generation,
        complete,
        mailbox,
        completeOutput.data(),
        kFrames);
    require(completeRead.audibleControlTupleCoherent, "rapid readiness prefix settled");
    requireNear(completeOutput[(kFrames - 1U) * 2U], 0.30F,
        "rapid readiness complete endpoint");

    auto poisonedPartial = payload(0.20F, 0.20F, 20.0F, 0.0F);
    RenderBusFrameTag partialTag = completeTag;
    partialTag.stemBusReadyMask = 0x01U;
    SpscTaggedMultibusRingBuffer partial(8U);
    require(partial.writeTagged(poisonedPartial.data(), 2U, partialTag) == 2U,
        "queue short readiness loss");
    std::array<float, 4U> lossOutput{};
    const auto lossRead = stage.readAndMix(
        0U,
        endpoint.generation,
        partial,
        mailbox,
        lossOutput.data(),
        2U);
    require(!lossRead.audibleControlTupleCoherent, "short readiness loss unsettled");
    requireNear(lossOutput[0U], 0.30F, "short readiness loss begins continuously");
    requireNear(lossOutput[2U], 0.26666668F, "short readiness loss follows bounded slope");
    for (float sample : lossOutput) {
        require(std::abs(sample) <= 0.30F + kTolerance,
            "short readiness loss excludes poisoned partial stems");
    }

    SpscTaggedMultibusRingBuffer recovered(16U);
    require(recovered.writeTagged(completePayload.data(), 4U, completeTag) == 4U,
        "queue early readiness recovery");
    std::array<float, 8U> recoveredOutput{};
    const auto recoveredRead = stage.readAndMix(
        0U,
        endpoint.generation,
        recovered,
        mailbox,
        recoveredOutput.data(),
        4U);
    require(!recoveredRead.audibleControlTupleCoherent,
        "early readiness recovery remains unsettled through the ramp");
    const float lossStep = lossOutput[0U] - lossOutput[2U];
    const float recoveryBoundaryStep = lossOutput[2U] - recoveredOutput[0U];
    requireNear(recoveryBoundaryStep, lossStep,
        "early readiness recovery preserves the in-flight fallback slope");
    requireNear(recoveredOutput[6U], 0.30F,
        "early readiness recovery reaches complete Stems");

    SpscTaggedMultibusRingBuffer settled(8U);
    require(settled.writeTagged(completePayload.data(), 1U, completeTag) == 1U,
        "queue rapid readiness settled frame");
    std::array<float, 2U> settledOutput{};
    const auto settledRead = stage.readAndMix(
        0U,
        endpoint.generation,
        settled,
        mailbox,
        settledOutput.data(),
        1U);
    require(settledRead.audibleControlTupleCoherent,
        "rapid readiness recovery publishes only after settlement");
    requireNear(settledOutput[0U], 0.30F, "rapid readiness post-ramp endpoint");
}

void staleControlFailsClosedAndStillConsumesTheLease() {
    SpscTaggedMultibusRingBuffer ring(32U);
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    const auto endpoint = control(18U, 30U, 2U);
    require(mailbox.resetRole(0U, endpoint.generation, endpoint.selectionToken), "bind stale role");
    require(mailbox.publish(0U, endpoint), "publish stale endpoint");
    RenderBusFrameTag tag = tagFor(endpoint);
    tag.canonicalControlRevision = endpoint.canonicalControlRevision + 1U;
    auto queued = payload(0.3F, 0.3F, 0.0F, 0.0F);
    require(ring.writeTagged(queued.data(), kFrames, tag) == kFrames, "prefill stale run");
    std::array<float, kFrames * 2U> output;
    output.fill(1.0F);
    const auto read = stage.readAndMix(
        0U,
        endpoint.generation,
        ring,
        mailbox,
        output.data(),
        kFrames);
    require(read.frameCount == kFrames, "stale lease accounted");
    require(read.firstTag.selectionToken == 0U, "stale control cannot publish ownership");
    require(ring.queuedFrames() == 0U, "stale payload cannot replay");
    require(stage.diagnostics().staleControlFrames == kFrames, "stale frames counted once");
    require(stage.diagnostics().missingControlFrames == 0U, "stale control is not missing control");
    for (float sample : output) require(sample == 0.0F, "stale control outputs silence");
}

void allMuteRampsAnInFlightSourceFade() {
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    auto endpoint = control(19U, 31U, 3U, LateStemSourceMode::Original);
    require(mailbox.resetRole(0U, endpoint.generation, endpoint.selectionToken), "bind fading role");
    require(mailbox.publish(0U, endpoint), "publish Original");
    auto queued = payload(0.20F, 0.20F, 0.01F, 0.04F);
    SpscTaggedMultibusRingBuffer ring(32U);
    std::array<float, kFrames * 2U> output{};
    auto render = [&] {
        require(ring.writeTagged(queued.data(), kFrames, tagFor(endpoint)) == kFrames, "queue fade payload");
        require(stage.readAndMix(0U, endpoint.generation, ring, mailbox, output.data(), kFrames).frameCount == kFrames, "read fade payload");
    };
    render();
    endpoint.sourceMode = LateStemSourceMode::Stems;
    ++endpoint.publicationSequence;
    require(mailbox.publish(0U, endpoint), "begin source fade");
    render();
    endpoint.effectiveStemGains.fill(0.0F);
    ++endpoint.publicationSequence;
    require(mailbox.publish(0U, endpoint), "mute during source fade");
    render();
    require(output.front() > 0.04F, "all-mute source fade cut music abruptly");
    for (std::size_t callback = 1U; callback < 240U / kFrames; ++callback) render();
    requireNear(output.back(), 0.04F, "all-mute source fade failed to settle");
    render();
    for (float sample : output) requireNear(sample, 0.04F, "settled all-mute retained music");
}

void allMutedStemsRetainClickThroughMissingStemReadiness() {
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage stage;
    auto endpoint = control(19U, 31U, 3U, LateStemSourceMode::Stems);
    endpoint.effectiveStemGains.fill(0.0F);
    require(mailbox.resetRole(0U, endpoint.generation, endpoint.selectionToken), "bind muted role");
    require(mailbox.publish(0U, endpoint), "publish muted endpoint");
    auto queued = payload(0.20F, -0.20F, 0.10F, 0.04F);

    SpscTaggedMultibusRingBuffer allReady(32U);
    RenderBusFrameTag readyTag = tagFor(endpoint);
    require(allReady.writeTagged(queued.data(), kFrames, readyTag) == kFrames, "queue ready stems");
    std::array<float, kFrames * 2U> output{};
    const auto readyRead = stage.readAndMix(
        0U,
        endpoint.generation,
        allReady,
        mailbox,
        output.data(),
        kFrames);
    require(readyRead.frameCount == kFrames, "muted stems read");
    for (float sample : output) requireNear(sample, 0.04F, "click remains over muted music");

    stage.reset();
    SpscTaggedMultibusRingBuffer missing(32U);
    RenderBusFrameTag missingTag = tagFor(endpoint);
    missingTag.stemBusReadyMask = 0U;
    require(missing.writeTagged(queued.data(), kFrames, missingTag) == kFrames, "queue missing stems");
    output.fill(0.0F);
    const auto missingRead = stage.readAndMix(
        0U,
        endpoint.generation,
        missing,
        mailbox,
        output.data(),
        kFrames);
    require(missingRead.frameCount == kFrames, "missing stems read");
    require(
        missingRead.firstTag.actualSourceMode ==
            stemwise::audio::FinalPcmSourceMode::Original,
        "missing stems evidence uses safe Original");
    for (std::size_t frame = 0U; frame < kFrames; ++frame) {
        requireNear(output[frame * 2U], 0.04F, "missing stems restored muted Original left");
        requireNear(output[frame * 2U + 1U], 0.04F, "missing stems restored muted Original right");
    }
}

void wrongRoleOwnerCannotLeakIntoSuccessor() {
    LateStemMixControlMailbox mailbox;
    NativeAudioOutputMultibusCallbackStage successorStage;
    const auto outgoing = control(20U, 32U, 4U);
    const auto successor = control(21U, 33U, 5U);
    require(mailbox.resetRole(0U, outgoing.generation, outgoing.selectionToken), "bind outgoing");
    require(mailbox.publish(0U, outgoing), "publish outgoing");
    require(mailbox.resetRole(1U, successor.generation, successor.selectionToken), "bind successor");
    require(mailbox.publish(1U, successor), "publish successor");
    SpscTaggedMultibusRingBuffer ring(32U);
    auto queued = payload(0.15F, 0.15F, 0.0F, 0.0F);
    require(
        ring.writeTagged(queued.data(), kFrames, tagFor(successor)) == kFrames,
        "queue successor");
    std::array<float, kFrames * 2U> output{};
    const auto read = successorStage.readAndMix(
        0U,
        successor.generation,
        ring,
        mailbox,
        output.data(),
        kFrames);
    require(read.firstTag.selectionToken == 0U, "wrong role cannot borrow successor owner");
    require(successorStage.diagnostics().missingControlFrames == kFrames,
            "missing-control frames counted once");
    for (float sample : output) require(sample == 0.0F, "wrong role is silent");
}

void tempoMappedFramesAndLoopWrapsHaveExactContinuity() {
    for (const double tempo : {0.5, 0.75, 1.0, 1.5, 2.0}) {
        for (const bool loopEnabled : {false, true}) {
            for (const bool wrongBoundary : {false, true}) {
                SpscTaggedMultibusRingBuffer ring(8U);
                LateStemMixControlMailbox mailbox;
                NativeAudioOutputMultibusCallbackStage stage;
                const auto endpoint = control(25U, 37U, 9U);
                require(mailbox.resetRole(0U, 25U, 37U), "tempo role");
                require(mailbox.publish(0U, endpoint), "tempo endpoint");
                auto tag = tagFor(endpoint);
                const auto queued = payload(.2F, .2F, 0.0F, 0.0F);
                // Offset the physical ring so the callback also crosses a storage wrap.
                require(ring.writeTagged(queued.data(), 2U, tag) == 2U, "tempo ring offset");
                require(ring.advanceRead(ring.acquireRead(2U)), "tempo ring offset consumed");
                tag.loopEnabled = loopEnabled;
                tag.loopPolicyRevision = loopEnabled ? 17U : 0U;
                tag.loopRevision = tag.loopPolicyRevision;
                tag.loopCycle = loopEnabled ? 1U : 0U;
                const double firstStart = loopEnabled ? 108.0 - 4.0 * tempo : 100.25;
                const double firstEnd = firstStart + 4.0 * tempo;
                require(ring.writeTaggedTimeline(queued.data(), 4U, tag, firstStart, firstEnd) == 4U,
                        "tempo first block");
                ++tag.payloadSequence;
                if (loopEnabled) ++tag.loopCycle;
                const double secondStart = (loopEnabled ? 100.0 : firstEnd) +
                    (wrongBoundary ? 1.0 : 0.0);
                require(ring.writeTaggedTimeline(queued.data(), 4U, tag,
                            secondStart, secondStart + 4.0 * tempo) == 4U, "tempo second block");
                const OutputTimelineLoopPolicy policy{
                    .generation = 25U, .selectionToken = 37U, .physicalRevision = 17U,
                    .enabled = true, .aTimelineFrame = 100U, .bTimelineFrameExclusive = 108U,
                };
                std::array<float, kFrames * 2U> output{};
                const auto result = stage.readAndMix(0U, 25U, ring, mailbox,
                    output.data(), kFrames, nullptr, loopEnabled ? policy : OutputTimelineLoopPolicy{});
                require(result.frameCount == kFrames, "tempo callback frame count");
                require(result.continuityViolationCount == (wrongBoundary ? 1U : 0U),
                        "tempo mapping must preserve exact continuity without hiding jumps");
            }
        }
    }
}

void timelineContinuityChecksEveryAdjacentFrame() {
    const auto violations = [](
        const auto &timelineFrames,
        const auto &physicalRevisions,
        const auto &loopCycles,
        bool loopEnabled,
        OutputTimelineLoopPolicy loopPolicy = {}) {
        SpscTaggedMultibusRingBuffer ring(8U);
        LateStemMixControlMailbox mailbox;
        NativeAudioOutputMultibusCallbackStage stage;
        const auto endpoint = control(25U, 37U, 9U);
        require(mailbox.resetRole(0U, endpoint.generation, endpoint.selectionToken),
            "bind continuity role");
        require(mailbox.publish(0U, endpoint), "publish continuity endpoint");
        const auto queued = payload(0.20F, 0.20F, 0.0F, 0.0F);
        for (std::size_t frame = 0U; frame < timelineFrames.size(); ++frame) {
            auto tag = tagFor(endpoint);
            tag.loopPolicyRevision = physicalRevisions[frame];
            tag.loopRevision = loopEnabled ? physicalRevisions[frame] : 0U;
            tag.loopCycle = loopCycles[frame];
            tag.loopEnabled = loopEnabled;
            require(
                ring.writeTaggedTimeline(
                    queued.data(),
                    1U,
                    tag,
                    static_cast<double>(timelineFrames[frame]),
                    static_cast<double>(timelineFrames[frame] + 1)) == 1U,
                "queue continuity frame");
        }
        std::array<float, kFrames * 2U> output{};
        return stage.readAndMix(
            0U,
            endpoint.generation,
            ring,
            mailbox,
            output.data(),
            timelineFrames.size(),
            nullptr,
            loopPolicy).continuityViolationCount;
    };

    require(
        violations(
            std::array<std::int64_t, 3U>{100, 101, 102},
            std::array<std::uint64_t, 3U>{0U, 0U, 0U},
            std::array<std::uint64_t, 3U>{0U, 0U, 0U},
            false) == 0U,
        "linear timeline is continuous");
    require(
        violations(
            std::array<std::int64_t, 3U>{100, 999, 102},
            std::array<std::uint64_t, 3U>{0U, 0U, 0U},
            std::array<std::uint64_t, 3U>{0U, 0U, 0U},
            false) != 0U,
        "interior timeline jump is detected");
    constexpr OutputTimelineLoopPolicy kLoopPolicy{
        .generation = 25U,
        .selectionToken = 37U,
        .physicalRevision = 17U,
        .enabled = true,
        .aTimelineFrame = 100U,
        .bTimelineFrameExclusive = 108U,
    };
    require(
        violations(
            std::array<std::int64_t, 4U>{106, 107, 100, 101},
            std::array<std::uint64_t, 4U>{17U, 17U, 17U, 17U},
            std::array<std::uint64_t, 4U>{1U, 1U, 2U, 2U},
            true,
            kLoopPolicy) == 0U,
        "exact loop wrap is continuous");
    require(
        violations(
            std::array<std::int64_t, 2U>{107, 101},
            std::array<std::uint64_t, 2U>{17U, 17U},
            std::array<std::uint64_t, 2U>{1U, 1U},
            true,
            kLoopPolicy) != 0U,
        "inexact loop wrap is detected");
    require(
        violations(
            std::array<std::int64_t, 3U>{100, 101, 102},
            std::array<std::uint64_t, 3U>{17U, 18U, 17U},
            std::array<std::uint64_t, 3U>{0U, 0U, 0U},
            false) != 0U,
        "interior physical revision excursion is detected");
}

void managedLoopAuthorityTravelsWithQueuedBlocks() {
    using stemwise::audio::ManagedLoopRenderContext;
    for (unsigned fault = 0U; fault != 8U; ++fault) {
        SpscTaggedMultibusRingBuffer ring(8U);
        LateStemMixControlMailbox mailbox;
        NativeAudioOutputMultibusCallbackStage stage;
        const auto endpoint = control(25U, 37U, 9U);
        require(mailbox.resetRole(0U, 25U, 37U) && mailbox.publish(0U, endpoint), "managed role control");
        auto tag = tagFor(endpoint);
        tag.loopEnabled = true;
        tag.loopRevision = 52U;
        const auto queued = payload(.2F, .2F, 0.0F, 0.0F);
        ManagedLoopRenderContext context{52U, 100U, 108U};
        if (fault == 1U) context = {};
        if (fault == 2U) ++context.revision;
        if (fault == 3U) ++context.startTimelineFrame;
        if (fault == 4U) ++context.endTimelineFrameExclusive;
        const auto base = endpoint;
        require(ring.writeTaggedTimeline(queued.data(), 4U, tag, 104.0, 108.0, false, &base, &context) == 4U,
            "queued managed tail retains its actual block authority");
        ++tag.payloadSequence;
        tag.loopCycle = fault == 5U ? 2U : 1U;
        if (fault == 6U) tag.loopPolicyRevision = 17U;
        require(ring.writeTaggedTimeline(queued.data(), 4U, tag, 100.0, 104.0, false, &base, &context) == 4U,
            "queued managed head retains its actual block authority");
        context = {99U, 0U, 1U}; // Later producer state cannot rewrite queued authority.
        std::array<float, 16U> output{};
        const OutputTimelineLoopPolicy direct{.generation=25U, .selectionToken=37U,
            .physicalRevision=17U, .enabled=true, .aTimelineFrame=100U, .bTimelineFrameExclusive=108U};
        const auto read = stage.readAndMix(0U, 25U, ring, mailbox, output.data(), 8U, nullptr,
            fault == 7U ? direct : OutputTimelineLoopPolicy{});
        require(read.frameCount == 8U && (read.continuityViolationCount == 0U) == (fault == 0U),
            "managed wrap needs the exact admitted context, boundary, physical namespace and next cycle");
    }
    for (const bool enable : {false, true}) {
        SpscTaggedMultibusRingBuffer ring(8U);
        LateStemMixControlMailbox mailbox;
        NativeAudioOutputMultibusCallbackStage stage;
        const auto endpoint = control(25U, 37U, 9U);
        require(mailbox.resetRole(0U, 25U, 37U) && mailbox.publish(0U, endpoint), "managed change control");
        const auto queued = payload(.2F, .2F, 0.0F, 0.0F);
        const ManagedLoopRenderContext context{52U, 0U, 100U}, linear{};
        auto tag = tagFor(endpoint);
        tag.loopEnabled = !enable;
        tag.loopRevision = tag.loopEnabled ? 52U : 0U;
        require(ring.writeTaggedTimeline(queued.data(), 4U, tag, 0.0, 4.0, false, &endpoint,
            enable ? &linear : &context) == 4U, "managed change old PCM");
        tag.loopEnabled = enable;
        tag.loopRevision = enable ? 52U : 0U;
        ++tag.payloadSequence;
        require(ring.writeTaggedTimeline(queued.data(), 4U, tag, 4.0, 8.0, false, &endpoint,
            enable ? &context : &linear) == 4U, "managed change new PCM");
        std::array<float, 16U> output{};
        require(stage.readAndMix(0U, 25U, ring, mailbox, output.data(), 8U).continuityViolationCount == 0U,
            "actual managed enable or disable preserves linear PCM without trusting an unproven wrap");
    }
}

}  // namespace

int main() {
    try {
        lawfulPublicationCannotWithdrawCommittedPcm();
        baseAndOverlaySequencesNeverShareAnOrderingNamespace();
        delayedBaseSurvivesAnUnrelatedPublicationButCannotAuthorizeOtherBlocks();
        malformedBaseCannotBeRescuedByALegitimateLatestOverlay();
        numericalTupleStraddlesDoNotSuppressLateMixAcknowledgement();
        exactOwnerMixesBeforeQueueReleaseWithoutAllocation();
        staleControlFailsClosedAndStillConsumesTheLease();
        allMuteRampsAnInFlightSourceFade();
        allMutedStemsRetainClickThroughMissingStemReadiness();
        readinessTransitionRampsWithoutAFirstSampleDiscontinuity();
        incompleteStemsNeverLeakAndFallbackKeepsClickContinuous();
        rapidReadinessRecoveryKeepsTheFallbackContinuous();
        wrongRoleOwnerCannotLeakIntoSuccessor();
        timelineContinuityChecksEveryAdjacentFrame();
        tempoMappedFramesAndLoopWrapsHaveExactContinuity();
        managedLoopAuthorityTravelsWithQueuedBlocks();
        std::cout << "Native output multibus callback-stage tests passed\n";
        return 0;
    } catch (const std::exception &error) {
        allocation_probe::enabled = false;
        std::cerr << "Native output multibus callback-stage test failed: "
                  << error.what() << '\n';
        return 1;
    }
}
