# Public roadmap and maintainer evidence

This is a **roadmap**, not a declaration that all items are finished.

| Area | Public evidence | Next verifiable milestone |
| --- | --- | --- |
| Native mixing | Built-in multibus/stem-mixing C++ source and [synthetic native demo](public-demo/README.md) | Cross-platform CI result plus playback control regression tests |
| FLAC | FLAC processing support in the wider system; public FFmpeg round-trip fixture | End-to-end Android FLAC decode into the actual StemWise audio callback |
| Multilingual timing | Large PC-side alignment implementation and tests; [synthetic scoring contract](public-bench/README.md) | Rights-cleared held-out audio dataset with independent start/end annotations |
| Buildability | Filtered source snapshot with current missing assets documented in [BUILDING.md](BUILDING.md) | Clean Windows/Android rebuild without private configuration |
| Releases | Source and notices published, no standalone qualified APK | Signed or source-only release with reproducible checksums and changelog |
| Community | [Contribution guidance](CONTRIBUTING.md) and public GitHub issues | Respond to genuine external contributions and publish fixes |

The main project has a distinct private development history. Public adoption, external contribution counts, downloadable APK quality, and alignment success rates must be reported only when verified—not inferred from the number of files or the private history.

## How to help

External contributors can start with narrowly scoped work: improving Linux/Windows build instructions, developing original/synthetic audio fixtures, testing FLAC boundary conditions, checking Arabic/Turkish Unicode handling, and documenting a reproducible clean checkout. See [CONTRIBUTING.md](CONTRIBUTING.md), then use GitHub Issues.