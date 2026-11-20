# Independent English singing references

These four evaluation cases use published manual JamendoLyrics word starts and
ends. Their windows were selected from the annotations before model output.
`timing-reference.json` follows the existing timing-reference collection format.
It retains the original decimal seconds, source word indices, exact source cuts,
audio/annotation hashes, and null annotation uncertainty. No reverb-tail or
sample-accurate offset convention was supplied by the dataset.

Only the two Cortez cases are local development cases. Lower Loveday and Rxbyn
are held out by us; training overlap with installed models is unknown. The full
Rxbyn CSV contains overlaps outside the selected window. It is retained unchanged;
the selected words have positive, nonoverlapping intervals.

Feed a worker only its case WAV and `.lyrics.txt`, never the reference boundaries.
The reference labels are independent evaluation data, not content approval for
production songs. Counterfactual wrong-word/deletion/repeat inputs are negative
controls, not replacement timing annotations.

The WAVs are ignored local fixtures. They retain exact float32 PCM from the
existing canonical decoder; PCM24 FLAC would clip two Cortez samples. The
preparer uses deterministic bitexact float WAV containers and checks exact PCM
readback. Full MP3 downloads and disposable decodes stay under ignored work.

## Reproduce

From the repository root, acquire only these pinned source files:

```powershell
$names = @('Cortez_-_Feel__Stripped_', 'Lower_Loveday_-_Is_It_Right_', 'Rxbyn_-_Bad_Side')
$files = @('LICENSE', 'JamendoLyrics.csv')
foreach ($name in $names) {
    $files += "annotations/words/$name.csv", "lyrics/$name.words.txt", "subsets/en/mp3/$name.mp3"
}
hf download jamendolyrics/jamendolyrics @files --type dataset --revision de188c963fd4539bc769b3feb83582e5a9595e36 --local-dir 'Test suite/work/independent-lyrics-qualification/source'
. './PC side/stemwise-playlist-builder/runtime-bootstrap.ps1'
$runtime = Get-StemWiseRuntime -BuilderWindows (Join-Path $PWD 'PC side/stemwise-playlist-builder') -Route main
Invoke-StemWiseRuntime -Runtime $runtime -CommandArguments @("$($runtime.PcSide)/../Test suite/fixtures/lyrics-timing/English/prepare.py", '--source', "$($runtime.PcSide)/../Test suite/work/independent-lyrics-qualification/source")
```

The preparer verifies source SHA-256 values and actual MP3 copyright tags, checks
every crop against the original annotations, and refuses to replace differing
fixture bytes. It does not download, load models, or submit jobs. Reproduction
uses the installed qualified main runtime and its canonical FFmpeg decoder.

For a model-free plan, invoke `Test suite/pc/tools/measure_english_timing.py` through
the same managed main runtime. Add `--run-models` only for an explicitly requested
measurement; repeat `--family` to select `mms`, `wav2vec2`, `owsm` or `sylla`. Native model
inputs contain only the exact audio, plain lyrics and full-crop geometry. Annotation
boundaries enter the existing scorer afterward, with every required word retained
in the denominator. Results stay private under `Test suite/work`; they do not approve
wording, qualify a model or prove a delivered package. Sylla remains shadow-only,
and full-mix MMS measurements do not qualify its production isolated-vocal route.

The separate English Sylla witness qualification uses all six full recordings and
2,090 words in `sylla-held-out/input-manifest.json`. Its criteria were frozen in
`sylla-qualification-plan.json` before their model outputs; the four crops above are
development data for that qualification. Pass `--reference` with this manifest to
measure it. The complete BY-ND recordings receive only technical format conversion;
audio stays ignored. Raw annotations, lyrics, actual license tags and attribution
remain unedited. Its `prepare_inputs.py` takes the repository root and uses pinned
source MP3s at the manifest paths. No timing labels enter model requests, and known
human annotation overlaps remain in the measurement rather than being excluded.
After measurement, `--assess-sylla` recomputes the frozen criteria from the exact
native packets and bindings. It never runs models or treats saved metric/pass fields
as authority. A later case failure preserves earlier results; missing words stay in
the full denominator. This assessment does not run the separate integration gates.

The frozen six-recording global-CTC candidate failed its criteria (1,942/2,090 joint
word bounds; 110/131 held words). Its exact results are retained under
`Engine telemetry/independent-english-singing/diagnostics/9be209c08d38584d5358f8c92262d4d66e64b2878f2ff974a3f8bf934bf7933f`.
The optional `--compare-whisper-guards --run-models --family sylla` experiment uses
managed lyrics for the normal unprompted timed Whisper route and main for Sylla.
Launch it with the normal lyrics worker's `_alignment_worker_environment()`;
environment leases alone do not supply CUDA library search paths.
It compares full and guarded requests for HILA/Ridgway against the same acoustic
cache. These inputs are development data for the new candidate; this comparison
does not grant fresh held-out qualification. Without `--run-models`, it only plans.
Native short-window or boundary rejections use the existing adjacent-window repair
and recheck the unchanged guard. The report retains requested/resolved segments,
bound failure causes and merges; collapse to a full recording is explicit.

## Attribution and licenses

`ATTRIBUTION.json` retains each artist, title, original track URL, pinned dataset
revision, artifact hashes and license URL. MP3 copyright tags and dataset metadata
agree: Cortez and Rxbyn use CC BY 3.0; Lower Loveday uses CC BY-SA 3.0. The latter
license also applies to its audio crop. Keep these notices with any distributed
adaptations. Annotations use the retained `LICENSE.annotations.txt`; its MIT grant
explicitly excludes audio and lyric text, which follow the track licenses.

Dataset: https://huggingface.co/datasets/jamendolyrics/jamendolyrics/tree/de188c963fd4539bc769b3feb83582e5a9595e36

Annotation authors: Simon Durand, Daniel Stoller, Sebastian Ewert,
*Contrastive Learning-Based Audio to Lyrics Alignment for Multiple Languages*,
ICASSP 2023, https://doi.org/10.1109/ICASSP49357.2023.10096725.
