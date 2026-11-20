# Public word-timing scoring contract

This is a **reproducible reference-versus-prediction scorer**, not a model evaluation result.

```bash
python3 public-bench/score_alignment.py --self-test \
  --fixture public-bench/synthetic-fixtures.json \
  --output public-bench/output/example-score.json
```

The included English, Arabic-with-diacritics, and Turkish examples contain only original, synthetic reference word intervals and hand-constructed predictions. Tests verify three behaviors:
1. Perfect word identities and boundaries score zero start/end error.
2. Incorrect end/start boundaries reduce the fraction of fully accurate words.
3. Disagreement over a word's identity (e.g., “You” versus “We”) blocks timing evaluation instead of falsely claiming success.

**No audio model is executed**, and these examples cannot establish alignment accuracy, recall, robustness to singing, melisma, or language coverage. Scores are per word in milliseconds: start-time mean absolute error (MAE), end-time MAE, and the proportion of words within 100 ms on **both** endpoints. Every input must have identical ordered text to the reference before timestamp scoring is allowed.

## Toward a real benchmark

To measure StemWise fairly, obtain audio you are allowed to redistribute/evaluate and independent reference transcripts and manually checked start **and** end timestamps, including repeated words and sustained vowels. Record song identity hashes, language, recording rights, model/runtime commit, GPU/CPU, and decode parameters. Use untouched, held-out recordings; report all attempted songs and content blockers instead of selecting only successful examples. A real model comparison should score full-sequence content correctness and timing separately, and must never use reference timestamps or lyric edits as hidden model input.

This scorer is intentionally dependency-free and reusable with independently generated outputs having the documented schema. The current four cases **must not be reported as model benchmark results**.