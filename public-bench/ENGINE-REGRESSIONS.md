# Reproduce the lyric-engine regressions

These fixtures exercise the actual source, unlike the separate [synthetic scoring contract](README.md), which tests the metric itself.

| Case | What the fixture checks | What it does not prove |
| --- | --- | --- |
| Held vowel and voiced handoff | A release can be measured after an early estimate, even without silence. Backing-only evidence and releases outside the search window are rejected. | Recognition of the correct word or phoneme in arbitrary songs. |
| Clipped word next to a displaced entrance | The two boundaries are evaluated separately; an unaffected neighbor or existing timing lock is preserved. | Automatic correction of every short word. |
| Lead/choir overlap and repeats | Timing can follow the intended part. Preview additions use known lead text; a flagged addition can suppress the repeat summary. | Recovery of every repeat, or permission for a preview to publish it. |
| Instrumental break | A verified break conflict blocks delivery; synthetic controls distinguish vocal decay from continuing voice. | Detection without usable stems and a matched guide. |

| Arabic phrase endings | A held vowel can end in a quiet breath or fade into a rest. The masked case requires a sustained drop in both vocal views; an interrupted vowel, short rest or disagreement is rejected. | That a quiet breath removed by separation is absent from the mix. |
| Turkish endings before a break | A lead consonant can stop before continuing backing. The fixture requires agreement between the lead and pooled vocals and checks for competing vocal parts. | Recovery when the vocal part is ambiguous or the guide is off. |
| Provider lyric formatting | CRLF input and trailing ASCII spaces or tabs are cleaned while interior spacing and stanza breaks are preserved. | That the acquired wording matches the performance. |


## Clean-checkout commands

Activate a Python 3.11 to 3.13 virtual environment, then run from the repository root:

```sh
python -m pip install -r public-bench/engine-regression-requirements.txt
python -m pip install --no-deps --editable "PC side"
python -B public-bench/run_engine_regressions.py
```

The November 25 follow-up passed 43 focused checks in a separate Windows checkout with Python 3.13: 41 timing checks and two text-normalization checks. The public snapshot includes selected timing and intake changes, not the full recording-review or delivery workflow. End-to-end acquisition was not verified: the broader acquisition tests hit Windows file-operation permission errors in the test environment.

The fixtures use generated audio, constructed phoneme traces and controlled timing data. No accounts, private tracks, downloaded music or model weights are needed. The [public workflow](../.github/workflows/public-source-checks.yml) uses the same entry point. Consult [GitHub Actions](https://github.com/IncludeClassified/StemWise-Source/actions) for the result of a specific commit.

A useful next contribution is a synthetic reproduction or a rights-cleared excerpt with the intended word, singer and approximate boundary. An independently annotated corpus would let us report word coverage, start error, end error, wrong-repeat selection and abstentions separately. There is no general real-song accuracy claim or qualified standalone Android build.
