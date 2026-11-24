# StemWise

StemWise is an independently maintained, research-oriented Android music player and Windows audio-processing pipeline. Its central research goal is **fully automatic, accurate word-level lyric timing for singing**, with particular attention to **English, Arabic (including diacritics), and Turkish**.

> **Status:** Active experimental development. This is a source-only publication snapshot, not a finished product, a qualified standalone build, or a claim of general-purpose alignment accuracy.

## Why this work matters

Most automatic speech recognition and forced-alignment workflows are designed primarily for speech. Singing presents distinct challenges: elongated vowels, melisma (multiple notes sung on a syllable), repeated choruses and words, overlapping/background vocals, and disagreements between supplied lyric text and what was actually performed. Multilingual normalization adds further challenges, including Arabic orthographic variations and diacritics.

StemWise investigates ways to obtain reliable **word identities and start/end boundaries** despite those conditions. It aims to distinguish uncertainty in lyric content from uncertainty in timing, and to make failures measurable rather than silently produce misleading timestamps. **These are engineering goals and active research questions, not claims that all cases are solved.**

## Native audio playback and multi-stem engine

StemWise is **not only an alignment pipeline or a user interface around an external player**. The Android source includes a built-in native C++ audio engine responsible for playback-side mixing and multibus output, with source selection between the original mix and separately managed stems. Its [mixer interface](StemWise/audio-engine/src/main/cpp/StemwiseMixer.h) defines per-stem gain, mute and solo controls, smooth control ramps, and metronome behavior; the [native multibus output stage](StemWise/audio-engine/src/main/cpp/NativeAudioOutputMultibusCallbackStage.h) coordinates stem mixing with the device audio callback.

The application also handles **FLAC audio** and is designed for high-quality playback of original audio and multiple stem tracks. FLAC describes lossless source-file encoding; the presence of FLAC handling does **not** by itself guarantee bit-perfect output through every Android audio route. The relevant source code is provided for inspection, not as a benchmark or blanket claim of production readiness.

The division of responsibilities is deliberate: Windows-side tooling researches and prepares audio/lyrics, while Android provides the in-app, responsive playback and stem-mixing experience.

## What is in this repository

- [`StemWise/`](StemWise/): Android application source, the native [audio-engine module](StemWise/audio-engine/), build logic, playback-facing modules, and retained third-party source notices.
- [`PC side/`](PC%20side/): Windows-side processing and orchestration, including [lyric-alignment](PC%20side/stemwise-playlist-builder/lyrics-align.ps1), [lyric handling](PC%20side/stemwise-playlist-builder/lyrics.ps1), [recording analysis](PC%20side/stemwise-playlist-builder/recording-analysis.ps1), and supporting tooling.
- [`Test suite/`](Test%20suite/): source tests and fixtures, including [PC alignment tests](Test%20suite/pc/alignment/), host checks, native tests, and Android test sources.

The Android player and Windows processor form a larger end-to-end engineering effort. The repository includes **1,760 retained source, configuration, and notice files**, plus this README. The code is provided so the architecture, experimental approach, and test organization can be inspected; the number of files is not a performance or quality metric.

## Reproducible public checks and audio demonstration

The [native C++ multi-stem demo](public-demo/README.md) compiles and executes StemWise's real render-stage and callback-stage mixer sources on original, synthetic stereo audio. Its WAV outputs distinguish unmodified playback from two-stem mixing with independent gains. The [public source CI workflow](.github/workflows/public-source-checks.yml) is intended to compile and run that demo and verify an **FFmpeg-only FLAC round trip**. This is **not** a complete Android or on-device FLAC-decoder qualification.

The [word-timing scoring contract](public-bench/README.md) validates endpoint metrics and correct handling of content disagreements using original, synthetic English/Arabic/Turkish fixtures; it does not run an ASR or alignment model or establish real-song accuracy.

- [How to build and what is not yet buildable](BUILDING.md)
- [Roadmap and verifiable milestones](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Open issues](https://github.com/IncludeClassified/StemWise-Source/issues)

Check actual GitHub Actions run results before treating any CI check as passing.

## Current engineering priorities

1. Investigate automatic lyric-content verification and end-of-word timing, including repeated and disputed passages.
2. Compare and regression-test alignment approaches on supported languages and singing phenomena.
3. Maintain and test native multi-stem playback, FLAC handling, audio quality, and real-time control behavior.
4. Improve the Windows processing workflow and its connection to Android playback.
5. Preserve evidence of failures and prevent regressions without representing experimental results as production-ready capabilities.

The primary maintainer is [IncludeClassified](https://github.com/IncludeClassified). The original development history is maintained separately; this public repository starts with a clean, curated publication snapshot.

## Current alignment research: timing sanity checks

A timestamp for every word measures coverage, not accuracy. A word can have a positive duration and still start late, end before a sustained vowel finishes, or be attached to the wrong repeat of a chorus.

The next research milestone is to make those failures easier to find and verify:

- **Place the right occurrence first.** Match repeated lines to their location in the recording. Use pronunciation guidance and recording evidence together so the aligner can distinguish identical lines sung at different points.
- **Check starts and releases separately.** Use the vocal evidence to distinguish late starts, clipped endings, and possible lead/backing-vocal confusion. Flag unusually short intervals for inspection rather than automatically stretching or rejecting them.
- **Show the evidence.** Report flagged words with their timestamps, the reason for review, and a short audio excerpt where rights permit. Compare before and after on the same independently checked examples, including normal-looking controls. Report coverage, start error, end error, and regressions separately.

These are research targets, not capabilities or accuracy claims for this public snapshot. The [public scoring fixtures](public-bench/README.md) are synthetic and do not measure real-song alignment.

## Scope, provenance, and build status

The source snapshot is based on development commit `a76feaeda265a42129dd025a5c3f46ae8fcd89d8`. The retained files were checked against their original Git blob hashes. Publication does **not** imply that the snapshot builds or tests successfully on a fresh machine.

Personal configuration, owner-specific device identifiers, research logs, downloaded music metadata, imported song lyrics, external datasets, generated media, and bundled binaries were intentionally excluded. In particular, the Gradle wrapper JAR, a prebuilt bridge AAR, a bundled yt-dlp executable, raster/media assets, some dataset fixtures, and device-specific configuration are absent. A standalone build may require public dependency restoration, generated assets, valid test inputs, and additional environment setup.

Original CI workflow definitions are retained under `.github/workflows-disabled/` so incomplete automated checks are not presented as passing.

## Licensing

**First-party code in this public source snapshot is released under the [MIT License](LICENSE)**, except where a file carries a different explicit license or a third-party notice. Independently licensed third-party files, vendored libraries, and upstream components remain subject to their own licenses and attribution requirements, including notices in relevant `third_party` directories. The MIT grant applies to this public snapshot; it does not publish or license omitted private data, recordings, binaries, or unpublished code.
