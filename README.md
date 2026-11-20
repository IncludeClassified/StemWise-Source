# StemWise

StemWise is an experimental Android music player with a Windows processing pipeline. Its research priorities include automatic word-level lyric alignment for English, Arabic (including diacritics), and Turkish, alongside audio-processing and playback workflows.

The project is under active development. These are development goals and research areas, not a claim that every song, language, or processing path currently succeeds.

## What this snapshot contains

- `StemWise/`: Android application code, native components, build configuration, and retained third-party source notices.
- `PC side/`: Windows processing, research, and supporting tooling.
- `Test suite/`: retained test source and supporting configuration.

This source-only publication snapshot is based on development commit `a76feaeda265a42129dd025a5c3f46ae8fcd89d8`. Included source files were checked against their original Git blob hashes. The snapshot begins with fresh publication history; it is not a mirror of the development repository's full history.

## Build and test status

This snapshot has not been qualified as a standalone build or release. Personal configuration, hardware identifiers, research logs, downloaded music metadata, imported song lyrics, external datasets, generated media, and bundled binary artifacts were excluded from publication.

In particular, the Gradle wrapper JAR, a prebuilt bridge AAR, a bundled yt-dlp executable, raster/media assets, some dataset fixtures, and owner-specific configuration are not included. Source or scripts for some components remain, but a working build or test environment may require restoring public dependencies, generating assets, and supplying your own configuration and appropriately licensed inputs. No build or test success is implied by publishing this snapshot.

CI workflow files are retained in `.github/workflows-disabled/` because this snapshot omits assets and configuration required by the original workflows.

## Licensing

No project-wide open-source license has been granted for StemWise's first-party code. Public source visibility should not be treated as an MIT, Apache, GPL, or other open-source license grant. Third-party components retain their own licenses and notices as supplied in their respective directories.

## Maintainer

[IncludeClassified](https://github.com/IncludeClassified) is the primary maintainer.
