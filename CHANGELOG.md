# StemWise public source changelog

This file records **public repository history only**. It does not imply that the Android application, FLAC decoder, or full model pipeline was independently built, tested, or released.

## Unreleased — public-source preparation (November 20, 2026)

- Published a privacy-filtered source-only snapshot of the Android and Windows development project.
- Added an MIT license for first-party public code while retaining applicable upstream notices.
- Expanded architecture and feature documentation for real-time native multibus mixing, independently controlled stems, and FLAC-oriented workflows.
- Added a standalone real-C++-mixer synthetic demonstration and generated-output checks.
- Added an explicitly synthetic multilingual word-timing scoring contract with negative controls; **not** a singing-model benchmark.
- Added a narrowly scoped GitHub Actions smoke workflow; successful execution must be verified from actual run records.
- Documented clean-checkout build gaps, a roadmap, contribution instructions, and public issues.

## Release policy

There is **no qualified downloadable Android APK or independently validated full-system release** in this public repository. Any future release announcement must identify the source commit, exact commands, supported platform, build/test outcomes, checksums, third-party license compliance, and known limitations. Source-only previews should be labeled plainly and not represented as production releases.
