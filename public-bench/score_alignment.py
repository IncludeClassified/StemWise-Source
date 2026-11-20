"""Validate and score word-level timing predictions with an explicit, synthetic reference.

This is a scoring harness, NOT a recognizer, forced aligner, or model benchmark.
Accepts independently supplied predictions and never silently reorders repeated words.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import statistics


def score(case: dict, tolerance_ms: int = 100) -> dict:
    reference = case["reference"]
    prediction = case["prediction"]
    if not reference:
        raise ValueError("reference words must not be empty")
    for role, words in (("reference", reference), ("prediction", prediction)):
        if not isinstance(words, list):
            raise ValueError(f"{role} must be a list")
        for word in words:
            if not isinstance(word, dict) or not isinstance(word.get("text"), str):
                raise ValueError(f"{role} word missing text")
            if not isinstance(word.get("start_ms"), int) or not isinstance(word.get("end_ms"), int):
                raise ValueError(f"{role} word timing must use integer milliseconds")
            if not 0 <= word["start_ms"] < word["end_ms"] <= case["duration_ms"]:
                raise ValueError(f"{role} word interval out of range")
    content_exact = ([w["text"] for w in reference] == [w["text"] for w in prediction])
    report = {
        "id": case["id"], "language": case["language"],
        "reference_words": len(reference), "prediction_words": len(prediction),
        "content_exact": content_exact, "timing_evaluable": content_exact,
        "timing_tolerance_ms": tolerance_ms,
    }
    if not content_exact:
        report.update(start_mae_ms=None, end_mae_ms=None, both_within_tolerance_fraction=None)
        return report
    start = [abs(r["start_ms"] - p["start_ms"]) for r, p in zip(reference, prediction)]
    end = [abs(r["end_ms"] - p["end_ms"]) for r, p in zip(reference, prediction)]
    report.update(
        start_mae_ms=round(statistics.fmean(start), 3),
        end_mae_ms=round(statistics.fmean(end), 3),
        both_within_tolerance_fraction=round(
            sum(a <= tolerance_ms and b <= tolerance_ms for a, b in zip(start, end)) / len(start), 4
        ),
    )
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=Path(__file__).with_name("synthetic-fixtures.json"))
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    data = json.loads(args.fixture.read_text(encoding="utf-8"))
    if data.get("synthetic") is not True:
        raise ValueError("This public fixture runner requires synthetic=true; use a separate rights-cleared evaluation for real audio")
    cases = data["cases"]
    ids = [c["id"] for c in cases]
    if not cases or len(ids) != len(set(ids)):
        raise ValueError("fixture case IDs must be nonempty and unique")
    results = [score(case) for case in cases]
    if args.self_test:
        by_id = {r["id"]: r for r in results}
        if set(by_id) != {"english-exact", "arabic-shifted", "turkish-exact", "english-content-dispute"}:
            raise AssertionError("expected four distinct probe cases")
        if by_id["english-exact"]["end_mae_ms"] != 0 or by_id["turkish-exact"]["start_mae_ms"] != 0:
            raise AssertionError("perfect synthetic references must score zero error")
        if by_id["arabic-shifted"]["both_within_tolerance_fraction"] >= 1:
            raise AssertionError("shifted endpoint must be detected")
        if by_id["english-content-dispute"]["timing_evaluable"]:
            raise AssertionError("content mismatch must not be hidden by a timestamp score")
    report = {
        "schema": "stemwise.public-timing-scorer.v1",
        "synthetic": True, "real_model_accuracy": None,
        "description": "Deterministic scoring contract only; NOT a measured singing alignment model.",
        "cases": results,
    }
    rendered = json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
