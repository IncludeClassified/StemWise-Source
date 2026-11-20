# StemWise

StemWise is an independently maintained, research-oriented Android music player and Windows audio-processing pipeline. Its central research goal is **fully automatic, accurate word-level lyric timing for singing**, with particular attention to **English, Arabic (including diacritics), and Turkish**.

> **Status:** Active experimental development. This is a source-only publication snapshot, not a finished product, a qualified standalone build, or a claim of general-purpose alignment accuracy.

## Why this work matters

Most automatic speech recognition and forced-alignment workflows are designed primarily for speech. Singing presents distinct challenges: elongated vowels, melisma (multiple notes sung on a syllable), repeated choruses and words, overlapping/background vocals, and disagreements between supplied lyric text and what was actually performed. Multilingual normalization adds further challenges, including Arabic orthographic variations and diacritics.

StemWise investigates ways to obtain reliable **word identities and start/end boundaries** despite those conditions. It aims to distinguish uncertainty in lyric content from uncertainty in timing, and to make failures measurable rather than silently produce misleading timestamps. **These are engineering goals and active research questions, not claims that all cases are solved.**

## What is in this repository

- [`StemWise/`](StemWise/): Android application source, the native [audio-engine module](StemWise/audio-engine/), build logic, playback-facing modules, and retained third-party source notices.
- [`PC side/`](PC%20side/): Windows-side processing and orchestration, including [lyric-alignment](PC%20side/stemwise-playlist-builder/lyrics-align.ps1), [lyric handling](PC%20side/stemwise-playlist-builder/lyrics.ps1), [recording analysis](PC%20side/stemwise-playlist-builder/recording-analysis.ps1), and supporting tooling.
- [`Test suite/`](Test%20suite/): source tests and fixtures, including [PC alignment tests](Test%20suite/pc/alignment/), host checks, native tests, and Android test sources.

The Android player and Windows processor form a larger end-to-end engineering effort. The repository includes **1,760 retained source, configuration, and notice files**, plus this README. The code is provided so the architecture, experimental approach, and test organization can be inspected; the number of files is not a performance or quality metric.

## Current engineering priorities

1. Investigate automatic lyric-content verification and end-of-word timing, including repeated and disputed passages.
2. Compare and regression-test alignment approaches on supported languages and singing phenomena.
3. Improve the reliability of the Windows processing workflow and its connection to Android playback and audio processing.
4. Preserve evidence of failures and prevent regressions without representing experimental results as production-ready capabilities.

The primary maintainer is [IncludeClassified](https://github.com/IncludeClassified). The original development history is maintained separately; this public repository starts with a clean, curated publication snapshot.

## Scope, provenance, and build status

The source snapshot is based on development commit `a76feaeda265a42129dd025a5c3f46ae8fcd89d8`. The retained files were checked against their original Git blob hashes. Publication does **not** imply that the snapshot builds or tests successfully on a fresh machine.

Personal configuration, owner-specific device identifiers, research logs, downloaded music metadata, imported song lyrics, external datasets, generated media, and bundled binaries were intentionally excluded. In particular, the Gradle wrapper JAR, a prebuilt bridge AAR, a bundled yt-dlp executable, raster/media assets, some dataset fixtures, and device-specific configuration are absent. A standalone build may require public dependency restoration, generated assets, valid test inputs, and additional environment setup.

Original CI workflow definitions are retained under `.github/workflows-disabled/` so incomplete automated checks are not presented as passing.

## Licensing

**No project-wide open-source license has yet been granted for StemWise's first-party code.** Public source access does not by itself grant the rights provided by MIT, Apache-2.0, GPL, or another open-source license. Third-party components retain their own licenses and notices in their respective locations.
