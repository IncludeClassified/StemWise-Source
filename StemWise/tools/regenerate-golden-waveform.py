#!/usr/bin/env python3
"""Regenerate or verify the deterministic waveform in the packaged golden fixture."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
FIXTURE = (
    REPOSITORY_ROOT
    / "app"
    / "src"
    / "development"
    / "assets"
    / "qa"
    / "enrichment"
    / "full-song-enrichment-v2.json"
)
from stemwise_engine.waveform import waveform_from_samples
import numpy as np


def stereo_pcm(duration_ms: int) -> np.ndarray:
    # Exact binary fractions and an anti-phase first bucket make stereo energy independently checkable.
    frames = np.arange(duration_ms)
    amplitude = (1024 - (frames // 5 * 13) % 896).astype(np.float32) / 1024
    left = np.where(frames % 2 == 0, amplitude, -amplitude)
    right = np.where(frames < duration_ms // 2, -left, left / 2)
    return np.column_stack((left, right)).astype("<f4")


def rendered_fixture() -> str:
    if not FIXTURE.is_file():
        raise FileNotFoundError(f"Golden enrichment fixture is missing: {FIXTURE}")

    document = json.loads(FIXTURE.read_text(encoding="utf-8"))
    duration_ms = int(document["analysis"]["durationMs"])
    document["analysis"]["waveform"] = waveform_from_samples(
        stereo_pcm(duration_ms), 1000, duration_ms=duration_ms
    )
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail without writing when the packaged fixture waveform is out of date",
    )
    args = parser.parse_args()

    expected = rendered_fixture()
    existing = FIXTURE.read_text(encoding="utf-8")
    try:
        fixture_label = FIXTURE.relative_to(REPOSITORY_ROOT)
    except ValueError:
        fixture_label = FIXTURE

    if args.check:
        if json.loads(existing)["analysis"]["waveform"] != json.loads(expected)["analysis"]["waveform"]:
            print(
                f"OUT OF DATE: {fixture_label}; run {Path(__file__).name} to regenerate it",
                file=sys.stderr,
            )
            return 1
        print(f"OK: {fixture_label}")
        return 0

    if existing == expected:
        print(f"UNCHANGED: {fixture_label}")
        return 0

    FIXTURE.write_text(
        expected,
        encoding="utf-8",
        newline="\n",
    )
    print(f"UPDATED: {fixture_label}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
