// Public, original-tone example of StemWise's real native C++ mixer stages.
// This does not load music, a trained aligner, or the Android app.
#include "StemwiseMixer.h"
#include "LateStemMixer.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <numbers>
#include <stdexcept>
#include <string>
#include <vector>

using namespace stemwise::audio;
namespace {
constexpr std::uint32_t kRate = 48000U;
constexpr std::uint32_t kBlock = 256U;
constexpr std::uint32_t kFrames = kRate;
constexpr std::uint32_t kStemCount = 2U;

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

void u16(std::ofstream &out, std::uint16_t value) {
    for (int i=0; i<2; ++i) out.put(static_cast<char>((value >> (8*i)) & 255U));
}
void u32(std::ofstream &out, std::uint32_t value) {
    for (int i=0; i<4; ++i) out.put(static_cast<char>((value >> (8*i)) & 255U));
}
void wav(const std::filesystem::path &path, const std::vector<float> &pcm) {
    require(pcm.size() == static_cast<std::size_t>(kFrames)*2U, "wrong WAV frame count");
    std::ofstream out(path, std::ios::binary);
    require(out.good(), "unable to open WAV output");
    const auto bytes = static_cast<std::uint32_t>(pcm.size()*2U);
    out.write("RIFF",4); u32(out,36U+bytes);
    out.write("WAVEfmt ",8); u32(out,16U); u16(out,1U); u16(out,2U);
    u32(out,kRate); u32(out,kRate*4U); u16(out,4U); u16(out,16U);
    out.write("data",4); u32(out,bytes);
    for(float sample : pcm){
        require(std::isfinite(sample),"non-finite output");
        const auto clamped=std::clamp(sample,-1.0F,1.0F);
        const auto integer=static_cast<std::int16_t>(std::lround(clamped*32767.0F));
        u16(out,static_cast<std::uint16_t>(integer));
    }
    require(out.good(),"failed writing WAV");
}
double rms(const std::vector<float> &pcm) {
    long double sum=0.0L;
    for(float x:pcm) sum += static_cast<long double>(x)*x;
    return std::sqrt(static_cast<double>(sum/pcm.size()));
}
std::vector<float> render(bool useStems) {
    MixerConfiguration cfg{};
    cfg.sampleRateHz=kRate;
    cfg.maximumBlockFrames=kBlock;
    StemwiseMixer worker(cfg);
    MixerControlSnapshot settings{};
    settings.revision=1U;
    settings.sourceMode=useStems?MixerSourceMode::Stems:MixerSourceMode::Original;
    settings.stemLaneCount=kStemCount;
    settings.stems[0].gain=1.0F;
    settings.stems[1].gain=0.25F;
    settings.musicGain=1.0F;
    settings.mixHeadroom=0.8F;
    settings.metronome.mode=MetronomeMode::Off;
    require(worker.applyControls(settings),"render controls rejected");

    const auto endpoint=worker.lateControlEndpoint();
    LateStemMixControlSnapshot control{};
    control.publicationSequence=1U;
    control.generation=1U;
    control.selectionToken=1U;
    control.canonicalControlRevision=endpoint.mixerRevision;
    control.sourceIntentRevision=1U;
    control.stemIntentRevision=1U;
    control.sourceMode=useStems?LateStemSourceMode::Stems:LateStemSourceMode::Original;
    control.requiredStemMask=stemwiseRequiredStemMask(kStemCount);
    control.effectiveStemGains=endpoint.effectiveStemGains;
    control.musicGain=endpoint.musicGain;
    control.mixHeadroom=endpoint.mixHeadroom;
    control.sourceRampFrames=1U;
    control.laneRampFrames=1U;
    control.masterRampFrames=1U;
    LateStemMixer callbackMixer(kRate);
    require(control.valid(),"public demo control invalid");
    require(callbackMixer.applyControl(control,control.requiredStemMask),"callback controls rejected");

    std::vector<float> result;
    result.reserve(static_cast<std::size_t>(kFrames)*2U);
    std::array<float,kBlock*2U> original{};
    std::array<float,kBlock*2U> first{};
    std::array<float,kBlock*2U> second{};
    std::array<float,kBlock*kStemwiseMusicChannelCount> buses{};
    std::array<float,kBlock*kStemwiseMultibusStride> payload{};
    std::array<float,kBlock*2U> mixed{};

    for(std::uint32_t at=0; at<kFrames; at+=kBlock){
        const std::uint32_t count=std::min(kBlock,kFrames-at);
        for(std::uint32_t f=0; f<count; ++f){
            const double t=static_cast<double>(at+f)/kRate;
            const float low=0.18F*static_cast<float>(std::sin(2.0*std::numbers::pi*220.0*t));
            const float high=0.14F*static_cast<float>(std::sin(2.0*std::numbers::pi*440.0*t));
            first[f*2U]=low;
            first[f*2U+1U]=low*0.7F;
            second[f*2U]=high*0.7F;
            second[f*2U+1U]=high;
            original[f*2U]=first[f*2U]+second[f*2U];
            original[f*2U+1U]=first[f*2U+1U]+second[f*2U+1U];
        }
        PreDspRenderBlock input{};
        input.original={original.data(),count};
        input.stems[0]={first.data(),count};
        input.stems[1]={second.data(),count};
        input.frameCount=count;
        input.timelineStartFrame=at;
        input.selectionToken=1U;
        input.generation=1U;
        require(worker.renderPreDspMultibus(input,buses.data(),kBlock),"pre-DSP mixer rejected block");
        const PostDspMultibusRenderBlock post{
            buses.data(),count,at,{static_cast<double>(at),static_cast<double>(at+count)},1U,1U
        };
        require(worker.renderPostDspMultibus(post,payload.data(),kBlock),"post-DSP stage rejected block");
        require(callbackMixer.mix(payload.data(),count,control.requiredStemMask,mixed.data()),"callback mixer rejected block");
        result.insert(result.end(),mixed.begin(),mixed.begin()+count*2U);
    }
    require(callbackMixer.diagnostics().renderedFrames==kFrames,"callback frame accounting mismatch");
    require(worker.diagnostics().postDspRenderedFrames==kFrames,"render frame accounting mismatch");
    return result;
}
}  // namespace

int main(int argc, char **argv) {
    try {
        if(argc!=2) throw std::runtime_error("usage: stemwise-native-demo OUTPUT_DIRECTORY");
        const std::filesystem::path output=argv[1];
        std::filesystem::create_directories(output);
        const auto original=render(false);
        const auto stems=render(true);
        long double difference=0.0L;
        for(std::size_t i=0;i<original.size();++i) {
            const long double d=original[i]-stems[i];
            difference+=d*d;
        }
        const auto delta=std::sqrt(static_cast<double>(difference/original.size()));
        require(rms(original)>0.02,"original path unexpectedly silent");
        require(rms(stems)>0.01,"stem path unexpectedly silent");
        require(delta>0.02,"stem gain adjustment did not change the output");
        wav(output/"original.wav",original);
        wav(output/"stems.wav",stems);
        std::ofstream report(output/"mixer-metrics.json");
        require(report.good(),"cannot write demo metrics");
        report<<std::fixed<<std::setprecision(8)
            <<"{\n  \"synthetic_audio\": true,\n  \"uses_real_stemwise_native_mixer\": true,\n"
            <<"  \"tests_android_playback\": false,\n  \"tests_flac_decoder\": false,\n"
            <<"  \"sample_rate_hz\": "<<kRate<<",\n  \"frames\": "<<kFrames
            <<",\n  \"stem_count\": "<<kStemCount
            <<",\n  \"original_rms\": "<<rms(original)
            <<",\n  \"stems_rms\": "<<rms(stems)
            <<",\n  \"difference_rms\": "<<delta<<"\n}\n";
        require(report.good(),"cannot write demo metrics");
        std::cout<<"real native C++ mixer: "<<kFrames<<" synthetic frames; changed-mix RMS="
                 <<std::setprecision(6)<<delta<<"\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr<<"public demo FAILED: "<<e.what()<<"\n";
        return 1;
    }
}
