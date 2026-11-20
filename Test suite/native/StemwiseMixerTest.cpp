#include "AllocationProbeAttributes.h"
#include "StemwiseMixer.h"
#include "LateStemMixer.h"
#include "MergerKnownAnswer.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <limits>
#include <new>
#include <source_location>
#include <vector>

namespace {

std::atomic<bool> gTrackAllocations{false};
std::atomic<std::uint64_t> gAllocationCount{0};

}  // namespace

STEMWISE_ALLOCATION_HOOK void *operator new(std::size_t size) {
    if (gTrackAllocations.load(std::memory_order_relaxed)) {
        gAllocationCount.fetch_add(1, std::memory_order_relaxed);
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


#include <cassert>

using namespace stemwise::audio;
namespace {
constexpr std::size_t kFrames = 256;
using Music = std::array<float, kFrames * kStemwiseMusicChannelCount>;
using Payload = std::array<float, kFrames * kStemwiseMultibusStride>;
void render(StemwiseMixer &mixer, Music &music, Payload &payload, std::int64_t output,
            double start, double end, std::uint64_t generation = 1) {
    const auto before = gAllocationCount.load();
    gTrackAllocations.store(true);
    const bool accepted = mixer.renderPostDspMultibus(
        {music.data(), kFrames, output, {start, end}, 1, generation}, payload.data(), kFrames);
    gTrackAllocations.store(false);
    assert(accepted && gAllocationCount.load() == before);
}
MixerConfiguration configuration() {
    MixerConfiguration result{};
    result.maximumBlockFrames = kFrames;
    return result;
}
void currentMultibusPreservesMusicAndSeparatesClickAndEnvelope() {
    StemwiseMixer mixer(configuration());
    MixerControlSnapshot controls{};
    controls.metronome = {MetronomeMode::FreeBpm, 120.f, 1.f, 1, 4, 4};
    assert(mixer.applyControls(controls));
    Music music{}; Payload payload{};
    for (std::size_t i=0;i<music.size();++i) music[i] = static_cast<float>(i % 47)/32.f;
    music[17] = std::numeric_limits<float>::quiet_NaN();
    render(mixer,music,payload,0,0,kFrames);
    bool click=false;
    for (std::size_t frame=0;frame<kFrames;++frame) {
        for (std::size_t c=0;c<kStemwiseMusicChannelCount;++c) {
            const auto source=music[frame*kStemwiseMusicChannelCount+c];
            assert(payload[frame*kStemwiseMultibusStride+c] == (std::isfinite(source)?source:0.f));
        }
        click |= payload[frame*kStemwiseMultibusStride+kStemwiseMultibusClickChannel] != 0.f;
        assert(payload[frame*kStemwiseMultibusStride+kStemwiseMultibusEnvelopeChannel] == 1.f);
    }
    assert(click && mixer.diagnostics().postDspNonFiniteSamplesScrubbed == 1);
    assert(mixer.diagnostics().metronomeEvents == 1);
}
void identicalBeatGridPreservesLiveClickTail() {
    StemwiseMixer reference(configuration()), repeated(configuration());
    MixerControlSnapshot controls{};
    controls.metronome = {MetronomeMode::BeatGrid, 120.f, 1.f, 1, 4, 1};
    const std::int64_t grid[]{0, 24'000};
    const std::uint8_t accents[]{1, 0};
    for (auto *mixer : {&reference, &repeated}) {
        assert(mixer->applyControls(controls));
        assert(mixer->setBeatGrid(grid, accents, 2));
    }
    Music music{}; Payload expected{}, actual{};
    render(reference, music, expected, 0, 0, kFrames);
    render(repeated, music, actual, 0, 0, kFrames);
    assert(expected == actual);
    assert(repeated.setBeatGrid(grid, accents, 2));
    render(reference, music, expected, kFrames, kFrames, 2 * kFrames);
    render(repeated, music, actual, kFrames, kFrames, 2 * kFrames);
    assert(std::any_of(expected.begin(), expected.end(), [](float value) { return value != 0.f && value != 1.f; }));
    assert(expected == actual);
}
void explicitGridAccentsAndSubdivisionDuplicateAndOverflowBounds() {
    StemwiseMixer mixer(configuration()); Music music{}; Payload payload{};
    MixerControlSnapshot controls{};
    controls.metronome = {MetronomeMode::BeatGrid, 120.f, 1.f, 4, 4, 4};
    assert(mixer.applyControls(controls));
    const std::int64_t grid[]{0,100,200,300}; const std::uint8_t accents[]{0,1,0,1};
    assert(mixer.setBeatGrid(grid,accents,4));
    render(mixer,music,payload,0,0,400);
    assert(mixer.diagnostics().metronomeEvents == 16);
    assert(mixer.diagnostics().metronomeAccentEvents == 2);
    assert(mixer.diagnostics().metronomeBaseBeatEvents == 2);
    assert(mixer.diagnostics().metronomeSubdivisionEvents == 12);
    const auto before=mixer.diagnostics().invalidBeatGridsRejected;
    const std::uint8_t twoAccents[]{1,0};
    const std::int64_t invalid[]{1,1}; assert(!mixer.setBeatGrid(invalid,twoAccents,2));
    assert(mixer.diagnostics().invalidBeatGridsRejected == before+1);
    const std::int64_t extremes[]{INT64_MAX-1000,INT64_MAX};
    assert(mixer.setBeatGrid(extremes,twoAccents,2));
    const std::int64_t shortGrid[]{0,1};
    assert(mixer.setBeatGrid(shortGrid,twoAccents,2));
    mixer.clearDiagnostics();
    mixer.resetPostDspForDiscontinuity(1,1,1);
    render(mixer,music,payload,0,0,2);
    assert(mixer.diagnostics().metronomeEvents == 2);
}
void freeBpmUsesOutputClockAndMeterAndRetainsVoiceAcrossUnrelatedControls() {
    StemwiseMixer first(configuration()), second(configuration());
    MixerControlSnapshot controls{};
    controls.metronome={MetronomeMode::FreeBpm,120.f,0.5f,2,6,8};
    assert(first.applyControls(controls) && second.applyControls(controls));
    Music music{}; Payload a{},b{};
    for (std::int64_t clock=0;clock<25000;clock+=kFrames) {
        if(clock==kFrames) {
            controls.revision++; controls.musicGain=0.3f;controls.stems[0].muted=true;
            assert(second.applyControls(controls));
        }
        render(first,music,a,clock,clock*.5,(clock+kFrames)*.5);
        render(second,music,b,clock,clock*2.,(clock+kFrames)*2.);
        for(std::size_t frame=0;frame<kFrames;++frame)
            assert(a[frame*kStemwiseMultibusStride+kStemwiseMultibusClickChannel] ==
                b[frame*kStemwiseMultibusStride+kStemwiseMultibusClickChannel]);
    }
    assert(first.diagnostics().metronomeEvents==5);
    assert(first.diagnostics().metronomeAccentEvents==1);
    assert(first.diagnostics().metronomeSubdivisionEvents==2);
}
void quarterNoteGridAtSlowedTempoEmitsEachClickOnceAtItsSourceBeat() {
    StemwiseMixer mixer(configuration());
    MixerControlSnapshot controls{};
    constexpr double tempo = 0.77;
    controls.metronome = {MetronomeMode::BeatGrid, 98.f * static_cast<float>(tempo), 1.f, 1, 4, 4};
    assert(mixer.applyControls(controls));
    std::array<std::int64_t, 8> grid{};
    std::array<std::uint8_t, 8> accents{};
    for (std::size_t beat = 0; beat < grid.size(); ++beat) {
        const auto milliseconds = 100 + std::llround(static_cast<double>(beat) * 60'000.0 / 98.0);
        grid[beat] = milliseconds * 48;
        accents[beat] = beat % 4 == 0 ? 1 : 0;
    }
    assert(mixer.setBeatGrid(grid.data(), accents.data(), grid.size()));
    Music music{}; Payload payload{};
    std::vector<std::int64_t> onsets;
    bool previousClick = false;
    const auto end = static_cast<std::int64_t>(std::ceil(static_cast<double>(grid.back()) / tempo)) + 2'048;
    for (std::int64_t clock = 0; clock < end; clock += kFrames) {
        render(mixer, music, payload, clock, static_cast<double>(clock) * tempo,
               static_cast<double>(clock + kFrames) * tempo);
        for (std::size_t frame = 0; frame < kFrames; ++frame) {
            const bool click = payload[frame * kStemwiseMultibusStride + kStemwiseMultibusClickChannel] != 0.f;
            if (click && !previousClick) onsets.push_back(clock + static_cast<std::int64_t>(frame));
            previousClick = click;
        }
    }
    assert(onsets.size() == grid.size());
    for (std::size_t beat = 0; beat < grid.size(); ++beat) {
        // A beat is rendered by the output sample whose source interval contains it.
        const auto expected = static_cast<std::int64_t>(std::floor(static_cast<double>(grid[beat]) / tempo));
        assert(std::abs(onsets[beat] - expected) <= 1);
    }
    assert(mixer.diagnostics().metronomeEvents == grid.size());
    assert(mixer.diagnostics().metronomeAccentEvents == 2);
    assert(mixer.diagnostics().metronomeSubdivisionEvents == 0);
}
void currentEnvelopeCanReverseAndResetsOnOwnershipDiscontinuity() {
    StemwiseMixer mixer(configuration()); Music music{};Payload payload{};
    render(mixer,music,payload,0,0,kFrames);
    mixer.beginPostDspLoopFadeOut(512);
    render(mixer,music,payload,kFrames,kFrames,2*kFrames);
    const auto tail=payload[(kFrames-1)*kStemwiseMultibusStride+kStemwiseMultibusEnvelopeChannel];
    assert(tail>0.f && tail<1.f);
    mixer.continuePostDspAfterLoopPolicyChange(256);
    render(mixer,music,payload,2*kFrames,2*kFrames,3*kFrames);
    assert(payload[kStemwiseMultibusEnvelopeChannel]>tail-.005f);
    assert(payload[(kFrames-1)*kStemwiseMultibusStride+kStemwiseMultibusEnvelopeChannel]>.99f);
    mixer.completePostDspLoopWrap(1,1,256);
    render(mixer,music,payload,3*kFrames,0,kFrames);
    assert(payload[kStemwiseMultibusEnvelopeChannel]<.01f);
    assert(payload[(kFrames-1)*kStemwiseMultibusStride+kStemwiseMultibusEnvelopeChannel]>.99f);
    const std::int64_t beats[]{0,100};const std::uint8_t accents[]{1,0};
    assert(mixer.setBeatGrid(beats,accents,2));
    render(mixer,music,payload,4*kFrames,kFrames,2*kFrames,2);
    assert(mixer.diagnostics().beatGridClears==1);
}
void admittedEndpointUsesExactCurrentMuteSoloTargets() {
    StemwiseMixer mixer(configuration());MixerControlSnapshot controls{};
    constexpr std::size_t lastLane = kStemLaneCount - 1U;
    controls.sourceMode=MixerSourceMode::Stems;controls.stemLaneCount=kStemLaneCount;
    controls.stems[lastLane]={1.4f,false,true}; controls.stems[0]={2.f,false,false};
    assert(mixer.applyControls(controls));
    const auto target=mixer.lateControlEndpoint();
    for(std::size_t i=0;i<kStemLaneCount;++i)assert(target.effectiveStemGains[i]==(i==lastLane?1.4f:0.f));
    controls.revision++;controls.stems[lastLane].muted=true;
    assert(mixer.applyControls(controls));
    for(float gain:mixer.lateControlEndpoint().effectiveStemGains)assert(gain==0.f);
}

void everySubsetAndSoloMapsThroughRealControlsIntoNineLanePcm() {
    using namespace merger_known_answer;
    StemwiseMixer mapping(configuration());
    LateStemMixer late;
    MixerControlSnapshot controls{};
    controls.sourceMode = MixerSourceMode::Stems;
    controls.mixHeadroom = 1.0F;
    controls.sourceTransitionFrames = controls.laneRampFrames = controls.masterRampFrames = 9U;
    Payload payload{};
    std::array<float, kFrames * 2U> output{};
    for (std::size_t frame = 0U; frame < kFrames; ++frame)
        payloadFrame(payload.data() + frame * kStemwiseMultibusStride, frame);
    auto check = [&](const QuarterGains &expectedGains) {
        ++controls.revision;
        assert(mapping.applyControls(controls));
        const auto endpoint = mapping.lateControlEndpoint();
        LateStemMixControlSnapshot callback{};
        callback.publicationSequence = controls.revision;
        callback.generation = 1U; callback.selectionToken = 2U;
        callback.canonicalControlRevision = controls.revision;
        callback.sourceIntentRevision = callback.stemIntentRevision = 1U;
        callback.sourceMode = endpoint.sourceMode == MixerSourceMode::Stems
            ? LateStemSourceMode::Stems : LateStemSourceMode::Original;
        callback.effectiveStemGains = endpoint.effectiveStemGains;
        callback.musicGain = endpoint.musicGain;
        callback.mixHeadroom = endpoint.mixHeadroom;
        callback.sourceRampFrames = endpoint.sourceRampFrames;
        callback.laneRampFrames = endpoint.laneRampFrames;
        callback.masterRampFrames = endpoint.masterRampFrames;
        for (std::size_t lane = 0U; lane < kLanes; ++lane)
            assert(callback.effectiveStemGains[lane] == static_cast<float>(expectedGains[lane]) / 4.0F);
        assert(late.applyControl(callback));
        assert(late.mix(payload.data(), kFrames, kStemwiseAllStemBusesReadyMask, output.data()));
        for (std::size_t frame = 9U; frame < kFrames; ++frame)
            for (std::size_t channel = 0U; channel < 2U; ++channel)
                assert(output[frame * 2U + channel] == expected(frame, channel, expectedGains));
        assert(late.diagnostics().safeOriginalFallbackFrames == 0U &&
            late.diagnostics().limiterActiveFrames == 0U);
    };
    for (std::uint32_t mask = 0U; mask < 512U; ++mask) {
        QuarterGains gains{};
        for (std::size_t lane = 0U; lane < kLanes; ++lane) {
            controls.stems[lane] = {1.0F, (mask & (1U << lane)) == 0U, false};
            gains[lane] = (mask & (1U << lane)) != 0U ? 4 : 0;
        }
        check(gains);
    }
    for (std::size_t solo = 0U; solo < kLanes; ++solo) {
        for (auto &lane : controls.stems) lane = {1.0F, false, false};
        controls.stems[solo].soloed = true;
        QuarterGains gains{}; gains[solo] = 4;
        check(gains);
        // A muted solo still excludes non-soloed lanes and is itself silent.
        controls.stems[solo].muted = true;
        gains[solo] = 0;
        check(gains);
    }
    for (auto &lane : controls.stems) lane = {1.0F, false, false};
    controls.stems[1] = {0.5F, false, true};
    controls.stems[8] = {1.5F, false, true};
    QuarterGains gains{}; gains[1] = 2; gains[8] = 6;
    check(gains);
    // One sequential stream retains neither solo nor mute intent after explicit clearing.
    gains = {1, 2, 3, 4, 5, 6, 7, 8, 0};
    for (std::size_t lane = 0U; lane < kLanes; ++lane)
        controls.stems[lane] = {static_cast<float>(gains[lane]) / 4.0F, false, false};
    check(gains);
    for (std::size_t lane = 0U; lane < kLanes; ++lane)
        for (const std::int32_t gain : {0, 2, 4, 6, 8}) {
            gains.fill(4); gains[lane] = gain;
            for (std::size_t index = 0U; index < kLanes; ++index)
                controls.stems[index] = {static_cast<float>(gains[index]) / 4.0F, false, false};
            check(gains);
        }
    std::puts("Merger mapping passed: 512/512 subsets, 9/9 solos, muted solos, multiple solos, "
              "45 gain endpoints and fractional sequence through production PCM.");
}
}
int main() {
    everySubsetAndSoloMapsThroughRealControlsIntoNineLanePcm();
    currentMultibusPreservesMusicAndSeparatesClickAndEnvelope();
    identicalBeatGridPreservesLiveClickTail();
    explicitGridAccentsAndSubdivisionDuplicateAndOverflowBounds();
    freeBpmUsesOutputClockAndMeterAndRetainsVoiceAcrossUnrelatedControls();
    quarterNoteGridAtSlowedTempoEmitsEachClickOnceAtItsSourceBeat();
    currentEnvelopeCanReverseAndResetsOnOwnershipDiscontinuity();
    admittedEndpointUsesExactCurrentMuteSoloTargets();
}
