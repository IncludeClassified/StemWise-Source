# Plain-lyrics acquisition and reviewed input

The explicit `acquire` command requests plain lyrics linked to exact frozen
YouTube Music video IDs. It never title-matches and never overwrites existing
owner text. Corrected or transcribed text uses the same layout:

```text
owner-input/
|- drafts/
|  `- <videoId>/
|     |- lyrics.meta.json
|     |- original.txt OR original.lrc
|     |- translation-en.txt OR translation-en.lrc
|     `- transliteration-ipa.txt OR transliteration-ipa.lrc
`- <videoId>.lyrics.json              # generated; do not edit by hand
```

Acquire every song, one song, or a playlist range with:

```powershell
.\lyrics.ps1 acquire
.\lyrics.ps1 acquire --video-id <videoId>
.\lyrics.ps1 acquire --from-position 1 --to-position 20
```

Successful exact-ID results create `original.txt`, create or preserve the
manifest, and compile the sidecar. The text-free report and missing worklists
are `lyrics-acquisition-report.json`, `lyrics-missing.json`, and
`lyrics-missing.csv` in this folder.

Create a manifest safely with:

```powershell
.\lyrics.ps1 scaffold --video-id <videoId> --language tr --script Latn
```

The exact manifest shape is:

```json
{
  "videoId": "a1B2c3D4e5F",
  "originalLanguage": "tr",
  "originalScript": "Latn",
  "sources": {
    "original": {
      "name": "Owner-supplied transcription",
      "url": "https://example.com/stable-source-page"
    },
    "translationEn": {
      "name": "Codex-generated English translation from reviewed original; review recommended"
    },
    "ipa": {
      "name": "Codex-generated broad IPA from reviewed original; review recommended"
    }
  }
}
```

`sources.original` is required. A companion source is required only when its
file exists. `url` is optional, must be a stable HTTPS page, and is stored only
as provenance: the compiler never opens or downloads it. A transcription made
by ear needs only an honest source name. Never put credentials, cookies,
authorization headers, signed download URLs, audio, or stems here.

The acquired language is a routing estimate, not ground truth. Detection
considers the detector's full supported language set and uses `und` when
confidence is insufficient. Correct the manifest and rebuild if it is wrong;
review truly mixed songs as `mul`. Every Arabic dialect/variation is
canonicalized to `ar`.

## Which files are required

| Original language | Companion policy | Draft compiler status |
|---|---|---|
| English (`en` or `en-*`) | English translation and IPA are forbidden | Partial until automatic endpoint refinement |
| Arabic (`ar`; all variations collapse to it) | English is required; IPA is forbidden | Partial until automatic endpoint refinement |
| Multilingual (`mul`) with `componentLanguages` | English is required; IPA is required only if any component is neither Arabic nor English | Partial until automatic endpoint refinement |
| Multilingual (`mul`) without `componentLanguages` | English is required; IPA is forbidden until the components are declared | Partial until automatic endpoint refinement |
| Any other language | English and IPA are required | Partial until automatic endpoint refinement |

Declare mixed components in `lyrics.meta.json`, for example
`"originalLanguage": "mul", "componentLanguages": ["ar", "en"]` forbids IPA,
while `["ar", "tr"]` requires it. Components use the same canonical language
tags as originals; Arabic variants collapse to `ar`. Recognition does not decide
these editorial declarations. The compiler preserves the supplied component list.

TXT and LRC both compile as authoritative plain text with `partial` status.
Reviewed LRC starts become private identity-bound evidence for automatic
alignment; they do not make a public track synchronized and never supply
`endMs`. A companion may be TXT or LRC. Fixed generated IDs are `original`,
`translation-en`, and `transliteration-ipa`; IPA uses
`<original-language>-fonipa`, `script: "Latn"`, and points to `original`.

Unresolved IPA candidates are retained as `transliteration-ipa.unreviewed.txt`
or `.lrc`. These are review inputs, never compiled tracks. The generated sidecar
states that IPA is withheld pending review while preserving other valid tracks.
Restore a canonical IPA filename only after correcting and reviewing that candidate.

## TXT and LRC rules

- Encode files as UTF-8 or UTF-8 with BOM. Newlines are normalized; internal
  spelling and blank lines are preserved.
- Use only one TXT or LRC file for each role. Supplying both is an error.
- LRC supports `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]`, multiple
  timestamps on one line, and one optional `[offset:+/-N]` in milliseconds.
- Minutes may exceed 59; seconds must be below 60. Standard metadata tags are
  ignored.
- Resulting private start evidence is integer milliseconds, sorted, unique, and
  nonnegative. The compiler never invents `endMs`; only automatic endpoint
  refinement can publish a synced track.
- Blank timed lines, malformed timestamps, enhanced word timing, duplicate
  starts, or times outside a completed song's exact analysis duration fail.

Compile with `.\lyrics.ps1 build --video-id <videoId>` or compile every draft
with `.\lyrics.ps1 build`. Output is written atomically, so a failed draft never
clobbers a good sidecar. `.\lyrics.ps1 status` reports only IDs and file-state
booleans; neither commands nor logs print lyric text.

These sidecars are private review inputs. Follow the repository's current
`SEND_TO_AGENTS.md` and scoped `STEMWISE_AGENT.ps1` Status/Task actions to validate
and finalize a song. Only the verified public ZIP containing its FLAC or Opus
Original, PNG and JSON is delivered to Android.
