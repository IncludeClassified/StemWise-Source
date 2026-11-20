# Public native multi-stem demo

This demo compiles and executes the **actual StemWise C++ mixer sources**—not a mock implementation:
- `StemWise/audio-engine/src/main/cpp/StemwiseMixer.cpp`
- `StemWise/audio-engine/src/main/cpp/LateStemMixer.cpp`

It generates one second of original, non-copyrighted synthetic stereo audio at 48 kHz. Two aligned tone stems are passed through the real render-stage multibus mixer and callback-stage mixer. Two separate runs show how **Original** and **Stems** modes with independent stem gain settings produce measurably different PCM output.

## Run on Linux with a C++20 compiler

```bash
mkdir -p public-demo/output
c++ -std=c++20 -O2 -Wall -Wextra -Werror -UNDEBUG \
  -I'StemWise/audio-engine/src/main/cpp' \
  StemWise/audio-engine/src/main/cpp/StemwiseMixer.cpp \
  StemWise/audio-engine/src/main/cpp/LateStemMixer.cpp \
  public-demo/native_mixer_demo.cpp \
  -o public-demo/output/stemwise-native-demo
public-demo/output/stemwise-native-demo public-demo/output
```

Outputs: `original.wav`, `stems.wav`, and `mixer-metrics.json`. The program exits with an error if PCM output is silent, mixing fails, or changing stem gain makes no meaningful difference. Listen to the two WAVs to hear the change.

## Optional FLAC lossless round-trip (requires FFmpeg)

```bash
ffmpeg -nostdin -hide_banner -loglevel error -i public-demo/output/stems.wav \
  -c:a flac public-demo/output/stems.flac
ffmpeg -nostdin -hide_banner -loglevel error -i public-demo/output/stems.flac \
  -f s16le -acodec pcm_s16le -ac 2 -ar 48000 public-demo/output/roundtrip.pcm
```

The CI workflow additionally compares the raw PCM hashes of the source WAV and decoded FLAC to verify that **FFmpeg's** lossless conversion has preserved the samples.

## Scope / non-claims

- The **multi-stem mixing demonstration exercises real StemWise C++ code**; the tones are synthetic and do not need model outputs, copyrighted songs, or private configuration.
- The FLAC round-trip exercises **FFmpeg's FLAC codec**, **not StemWise's Android FLAC reader**. An end-to-end Android FLAC integration test remains open work.
- This does not demonstrate the complete Android application, Android real-time audio scheduling, the separate time/pitch DSP, on-device FLAC decoding, or singing ASR/forced alignment. The filtered public snapshot is still not a fully qualified Android build.
- The output is reproducible on compatible compilers; the exact floating-point and PCM bytes may vary slightly across platforms. CI compares FLAC and WAV **within the same run**, not against a fixed cross-platform hash.

The [public CI workflow](../.github/workflows/public-source-checks.yml) builds this program, executes it, and uploads the outputs.