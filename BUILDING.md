# Building and testing the public StemWise source snapshot

**Publication status: experimental, source-only.** The repository is inspectable and includes independently buildable/testable **native mixer components**, but a clean Android APK build and complete PC model run are **not yet qualified**. The original owner's private working environment is not a portable public release.

## What anyone can run now

1. Native C++ multi-stem demo (Linux, C++20 compiler): [public-demo/README.md](public-demo/README.md). Compiles the actual StemwiseMixer and LateStemMixer implementation against synthetic PCM; no proprietary assets.
2. Word-timing measurement contract (Python 3.10+): [public-bench/README.md](public-bench/README.md). Dependency-free, deterministic synthetic fixtures; no model-quality claims.
3. A narrow existing Python source test, when dependencies can be installed:

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install --no-deps --editable 'PC side'
python 'Test suite/pc/run_tests.py' --directory 'Test suite/pc/unit' test_ipa_validation
```

4. [Focused lyric-engine regressions](public-bench/ENGINE-REGRESSIONS.md): run actual engine paths using synthetic inputs without downloading songs or model weights.

The third check exercises an existing IPA validation module; passing it does **not** imply multilingual alignment works end to end.

The [public smoke workflow](.github/workflows/public-source-checks.yml) attempts these checks in GitHub Actions. Check the **actual workflow run status** before citing a result.

## Why the complete Android build is not advertised

The filtered snapshot omits specific binaries, owner credentials/configuration, and some fixtures. Notably:
- Gradle wrapper JAR
- Prebuilt bridge AAR
- Bundled media/downloader binary
- Certain generated/raster Android resources
- Test fixtures, language-model checkpoints, external music inputs
- Owner-specific release signing and configuration

These omissions were intentional to avoid publishing private data or non-redistributable materials. A reproducible Android build requires reconstructing dependencies from auditable public sources, documenting versions and checksums, supplying generated resources/fixtures, and verifying Android/arm64-v8a output. Do not add real credentials, private songs, or unlicensed lyrics to resolve build failures.

## Planned source build qualification

- Restore or replace excluded public dependencies, verifying checksums and redistribution rights.
- Build a self-contained, synthetic integration test for Android FLAC decode → aligned PCM buses → actual native stem callback → output.
- Run a public, clean-checkout Android arm64 build and record the exact toolchain, build commands, hashes, and limitations.
- Only then mark the Android build as reproducible. No successful APK or independent Android FLAC validation is claimed today.

## Provenance

This snapshot originates from an internal development revision, retaining permitted source files but intentionally not the original project's complete git history. Third-party licenses remain authoritative for their code; first-party public code is [MIT licensed](LICENSE).