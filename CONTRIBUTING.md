# Contributing to StemWise

StemWise is an experimental, MIT-licensed, source-first Android/Windows audio project. Contributions are welcome on the public codebase, especially improvements that make singing-alignment experiments and multi-stem playback more reliable and reproducible.

## Good contributions

- Clearly scoped bug fixes in Android, native audio, or the Windows processing pipeline
- Tests for documented lyric-text, word-boundary, Arabic/Turkish normalization, or repeated-phrase failures
- Tests for native multibus/stem controls, FLAC handling, and audio-output regressions
- Documentation that makes existing code easier to understand, configure, or review
- Improvements to build reproducibility that do not require copying private assets or proprietary datasets

## Before opening an issue or pull request

1. Look for an existing discussion or test covering the same behavior.
2. Describe expected versus observed behavior. Include a minimal reproduction, the affected source revision, relevant platform/environment, and sanitized logs where available.
3. Add focused regression tests for demonstrated defects when feasible. Note clearly if tests could not be run.
4. Do not include copyrighted recordings or lyrics, private credentials, device identifiers, research logs, or external model weights/datasets without a verified right to distribute them.
5. Keep third-party license notices intact and identify any added dependencies and their applicable licenses.

## Current checkout limitations

The public snapshot intentionally omits some original build assets, binary dependencies, media, test fixtures, and owner-specific configuration. It has not been verified as a standalone build. A failure due to an absent excluded asset should not be reported as evidence that the original project is broken. Contributions that replace an omitted dependency with a legally redistributable, reproducible alternative are especially useful.

The repository is maintained by [IncludeClassified](https://github.com/IncludeClassified). No external response-time or release schedule is promised. The [MIT License](LICENSE) covers first-party source here; upstream third-party code retains its own notices and licenses.
