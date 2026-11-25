# Reproduce the lyric-engine regressions

These fixtures exercise the actual source, unlike the separate [synthetic scoring contract](README.md), which tests the metric itself.

| Case | What the fixture checks | What it does not prove |
| --- | --- | --- |
| Held vowel and voiced handoff | A release can be measured after an early estimate, even without silence. Backing-only evidence and releases outside the search window are rejected. | Recognition of the correct word or phoneme in arbitrary songs. |
| Clipped word next to a displaced entrance | The two boundaries are evaluated separately; an unaffected neighbor or existing timing lock is preserved. | Automatic correction of every short word. |
| Lead/choir overlap and repeats | Timing can follow the intended part. Preview additions use known lead text; a flagged addition can suppress the repeat summary. | Recovery of every repeat, or permission for a preview to publish it. |
| Instrumental break | A verified break conflict blocks delivery; synthetic controls distinguish vocal decay from continuing voice. | Detection without usable stems and a matched guide. |

## Clean-checkout commands

Activate a Python 3.11 to 3.13 virtual environment, then run from the repository root:

```sh
python -m pip install -r public-bench/engine-regression-requirements.txt
python -m pip install --no-deps --editable "PC side"
python -B public-bench/run_engine_regressions.py
```

The November 25 source update passed all 34 focused checks in a separate Windows checkout with Python 3.13. This is not a full-suite result; broader workflow and model checks require additional dependencies and, for process-lock tests, Linux or WSL.

The fixtures use generated audio, constructed phoneme traces and controlled timing data. No accounts, private tracks, downloaded music or model weights are needed. The [public workflow](../.github/workflows/public-source-checks.yml) uses the same entry point. Consult [GitHub Actions](https://github.com/IncludeClassified/StemWise-Source/actions) for the result of a specific commit.

A useful next contribution is a synthetic reproduction or a rights-cleared excerpt with the intended word, singer and approximate boundary. An independently annotated corpus would let us report word coverage, start error, end error, wrong-repeat selection and abstentions separately. There is no general real-song accuracy claim or qualified standalone Android build.
