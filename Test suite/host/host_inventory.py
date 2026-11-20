from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import re
import sys


SUITE_ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (SUITE_ROOT / "android/unit/java", SUITE_ROOT / "android/audio-engine/unit/java")
sys.path.insert(0, str(SUITE_ROOT))
from source_test_inventory import source_tests


class InventoryError(ValueError):
    pass


def discover(source_roots=None) -> list[str]:
    if source_roots is None:
        source_roots = SOURCE_ROOTS
    methods: list[str] = []
    for path in sorted(path for root in source_roots for path in root.rglob("*")):
        if path.suffix not in {".kt", ".java"} or not path.is_file():
            continue
        try:
            parsed = source_tests(path)
        except ValueError as exc:
            raise InventoryError(str(exc)) from exc
        methods.extend(f"{owner}#{method}" for owner, names in parsed.items() for method in names)
    duplicates = sorted(value for value, count in Counter(methods).items() if count > 1)
    if duplicates:
        raise InventoryError(f"Duplicate Host test identities: {duplicates}")
    if not methods:
        raise InventoryError("Host test inventory is empty")
    return sorted(methods)


def main() -> int:
    try:
        methods = discover()
        print(json.dumps({"schemaVersion": 1, "status": "PASS", "methods": methods}))
        return 0
    except (InventoryError, OSError, UnicodeError) as exc:
        print(f"HOST INVENTORY REFUSED: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
