from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
from typing import Any


DEVICE_ROOT = Path(__file__).resolve().parent
SUITE_ROOT = DEVICE_ROOT.parent
SOURCE_ROOT = SUITE_ROOT / "android" / "instrumentation" / "java"
MANIFEST_PATH = DEVICE_ROOT / "device-lanes.json"
sys.path.insert(0, str(SUITE_ROOT))
from source_test_inventory import is_managed_audio_test, is_optional_acoustic_test, source_tests


class InventoryError(ValueError):
    pass


def _fqcn_and_methods(path: Path) -> tuple[str, list[str]] | None:
    try:
        parsed = source_tests(path)
    except ValueError as exc:
        raise InventoryError(str(exc)) from exc
    if len(parsed) > 1:
        raise InventoryError(f"Unsupported multiple instrumentation classes in one source: {path}")
    return next(iter(parsed.items()), None)


def discover_tests(audio_isolation: dict[str, bool] | None = None,
                   optional_acoustics: dict[str, bool] | None = None) -> dict[str, list[str]]:
    discovered: dict[str, list[str]] = {}
    for path in sorted(path for path in SOURCE_ROOT.rglob("*") if path.suffix in {".kt", ".java"}):
        parsed = _fqcn_and_methods(path)
        if parsed is None:
            continue
        class_name, methods = parsed
        if class_name in discovered or len(methods) != len(set(methods)):
            raise InventoryError(f"Duplicate instrumentation class or method {class_name}.")
        discovered[class_name] = methods
        if audio_isolation is not None:
            source = path.read_text(encoding="utf-8")
            for method in methods:
                audio_isolation[f"{class_name}#{method}"] = is_managed_audio_test(class_name, method, source)
                if optional_acoustics is not None:
                    optional_acoustics[f"{class_name}#{method}"] = is_optional_acoustic_test(class_name, method, source)
    return discovered


def _suite_source(wrapper: str) -> tuple[str, list[str]]:
    simple_name = wrapper.rsplit(".", 1)[-1]
    matches = list(SOURCE_ROOT.rglob(f"{simple_name}.kt"))
    if len(matches) != 1:
        raise InventoryError(f"Suite wrapper {wrapper} has {len(matches)} source files.")
    text = matches[0].read_text(encoding="utf-8")
    package_match = re.search(r"(?m)^package\s+([A-Za-z0-9_.]+)", text)
    wrapper_match = re.search(r"(?m)^class\s+([A-Za-z0-9_]+)", text)
    suite_match = re.search(r"@Suite\.SuiteClasses\((.*?)\)\s*class\s+", text, re.DOTALL)
    if package_match is None or wrapper_match is None or suite_match is None:
        raise InventoryError(f"Suite wrapper {wrapper} is malformed.")
    actual_wrapper = f"{package_match.group(1)}.{wrapper_match.group(1)}"
    imports = {
        value.rsplit(".", 1)[-1]: value
        for value in re.findall(r"(?m)^import\s+([A-Za-z0-9_.]+)", text)
    }
    children: list[str] = []
    for simple in re.findall(r"([A-Za-z0-9_]+)::class", suite_match.group(1)):
        child = imports.get(simple)
        if child is None:
            raise InventoryError(f"Suite {wrapper} child {simple} has no explicit import.")
        children.append(child)
    return actual_wrapper, children


def load_manifest() -> dict[str, Any]:
    payload = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    if payload.get("schemaVersion") != 1:
        raise InventoryError("Device lane manifest schemaVersion must be 1.")
    return payload


def build_accounting() -> dict[str, Any]:
    manifest = load_manifest()
    audio_isolation: dict[str, bool] = {}
    optional_acoustics: dict[str, bool] = {}
    tests = discover_tests(audio_isolation, optional_acoustics)
    class_lane: dict[str, str] = {}
    suite_owner: dict[str, str] = {}
    wrappers: list[dict[str, Any]] = []

    for suite in manifest["explicitSuites"]:
        wrapper = suite["wrapper"]
        actual_wrapper, actual_children = _suite_source(wrapper)
        declared_children = suite["children"]
        if actual_wrapper != wrapper or actual_children != declared_children:
            raise InventoryError(
                f"Suite source differs from device-lanes.json for {wrapper}: "
                f"actual={actual_children}, declared={declared_children}"
            )
        wrappers.append(
            {"id": wrapper, "lane": "explicit-suite", "entryCount": len(actual_children)}
        )
        for child in actual_children:
            if child in suite_owner:
                raise InventoryError(
                    f"Unintended suite overlap: {child} belongs to "
                    f"{suite_owner[child]} and {wrapper}."
                )
            suite_owner[child] = wrapper
            class_lane[child] = "explicit-suite"

    lifecycle_methods: dict[str, tuple[str, int]] = {}
    for workflow in manifest["lifecycleWorkflows"]:
        for index, method_id in enumerate(workflow["phases"], start=1):
            if method_id in lifecycle_methods:
                raise InventoryError(f"Lifecycle method appears twice: {method_id}")
            lifecycle_methods[method_id] = (workflow["id"], index)
            class_name = method_id.split("#", 1)[0]
            if class_name in class_lane and class_lane[class_name] != "lifecycle-phase":
                raise InventoryError(f"Lifecycle class also belongs to another lane: {class_name}")
            class_lane[class_name] = "lifecycle-phase"

    fatal = manifest["fatalTwoProcess"]
    fatal_methods = {fatal["phaseA"], fatal["phaseB"]}
    fatal_class = fatal["class"]
    if fatal_class in class_lane:
        raise InventoryError(f"Fatal class also belongs to another lane: {fatal_class}")
    class_lane[fatal_class] = "fatal-two-process"

    fault = manifest.get("nativeProcessFault")
    if not isinstance(fault, dict) or set(fault) != {"class", "selector", "arguments"}:
        raise InventoryError("A dedicated native process-fault action is required")
    fault_class, fault_selector = fault["class"], fault["selector"]
    if (not isinstance(fault_class, str) or not isinstance(fault_selector, str)
            or not fault_selector.startswith(fault_class + "#")
            or fault["arguments"] != {"stemwiseIntentionalNativeFault": "true", "stemwiseDisposableTarget": "true"}):
        raise InventoryError("Native process-fault action must name its sole method and exact guard arguments")
    if fault_class in class_lane:
        raise InventoryError("Native process-fault class overlaps another lane")
    class_lane[fault_class] = "native-process-fault"

    for optional_class in manifest["optionalClasses"]:
        if optional_class in class_lane:
            raise InventoryError(f"Optional class overlaps another lane: {optional_class}")
        class_lane[optional_class] = "optional"

    exclusions = {item["id"]: item for item in manifest["deliberateExclusions"]}
    method_rows: list[dict[str, Any]] = []
    for class_name, methods in sorted(tests.items()):
        lane = class_lane.get(class_name, "ordinary")
        if re.search(r"OwnerData|Lifecycle|Durability|Fatal|ProcessFault", class_name) and class_name not in class_lane:
            raise InventoryError(f"Special-looking class was implicitly assigned ordinary: {class_name}")
        for method in methods:
            method_id = f"{class_name}#{method}"
            row: dict[str, Any] = {"id": method_id, "class": class_name, "lane": lane,
                                  "requiresAudioIsolation": audio_isolation.get(method_id, False),
                                  "optionalAcoustic": optional_acoustics.get(method_id, False)}
            if lane == "explicit-suite":
                row["wrapper"] = suite_owner[class_name]
            if lane == "lifecycle-phase":
                if method_id not in lifecycle_methods:
                    raise InventoryError(f"Lifecycle method has no ordered phase: {method_id}")
                row["workflow"], row["phase"] = lifecycle_methods[method_id]
            if lane == "fatal-two-process" and method_id not in fatal_methods:
                raise InventoryError(f"Fatal method has no external phase: {method_id}")
            if lane == "native-process-fault" and method_id != fault_selector:
                raise InventoryError(f"Native process-fault method has no isolated action: {method_id}")
            if method_id in exclusions:
                row["excluded"] = True
                row["exclusionReason"] = exclusions[method_id]["reason"]
            method_rows.append(row)

    discovered_ids = {row["id"] for row in method_rows}
    missing_special = sorted((set(lifecycle_methods) | fatal_methods | {fault_selector}) - discovered_ids)
    if missing_special:
        raise InventoryError(f"Declared special methods do not exist: {missing_special}")
    missing_classes = sorted(set(class_lane) - set(tests))
    if missing_classes:
        raise InventoryError(f"Declared lane classes do not contain tests: {missing_classes}")
    unknown_exclusions = sorted(set(exclusions) - discovered_ids)
    if unknown_exclusions:
        raise InventoryError(f"Deliberate exclusions do not exist: {unknown_exclusions}")

    ordinary_by_class: dict[str, list[dict[str, Any]]] = {}
    for row in method_rows:
        if row["lane"] == "ordinary":
            ordinary_by_class.setdefault(row["class"], []).append(row)
        elif row.get("excluded"):
            raise InventoryError("Deliberate exclusions are supported only in the ordinary lane.")
    for class_name, rows in ordinary_by_class.items():
        if any(row.get("excluded") for row in rows) and not all(
            row.get("excluded") for row in rows
        ):
            raise InventoryError(
                f"Ordinary exclusions must cover the whole class selector: {class_name}"
            )

    required_ordinary = manifest.get("ordinaryRequiredClasses")
    if not isinstance(required_ordinary, list) or not required_ordinary:
        raise InventoryError("Required ordinary classes are missing")
    for required in required_ordinary:
        rows = ordinary_by_class.get(required, [])
        if not rows or any(row.get("excluded") for row in rows):
            raise InventoryError(f"Required ordinary class is not selected: {required}")

    lane_counts = Counter(row["lane"] for row in method_rows)
    suite_entries = [
        {"wrapper": wrapper, "child": child, "lane": "explicit-suite"}
        for child, wrapper in sorted(suite_owner.items())
    ]
    return {
        "schemaVersion": 1,
        "status": "PASS",
        "sourceRoot": str(SOURCE_ROOT),
        "methodCount": len(method_rows),
        "wrapperCount": len(wrappers),
        "suiteEntryCount": len(suite_entries),
        "laneCounts": dict(sorted(lane_counts.items())),
        "unassignedCount": 0,
        "unintendedDuplicateCount": 0,
        "deliberateExclusionCount": len(exclusions),
        "wrappers": wrappers,
        "suiteEntries": suite_entries,
        "methods": method_rows,
    }


def selectors_for_lane(accounting: dict[str, Any], lane: str) -> list[str]:
    if lane == "ordinary":
        return sorted(
            {
                row["class"]
                for row in accounting["methods"]
                if row["lane"] == lane and not row.get("excluded")
            }
        )
    if lane == "explicit-suite":
        return [row["id"] for row in accounting["wrappers"]]
    return [row["id"] for row in accounting["methods"] if row["lane"] == lane]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate and emit disjoint Device test accounting"
    )
    parser.add_argument(
        "command", choices=("validate", "account", "selectors"), nargs="?", default="validate"
    )
    parser.add_argument(
        "--lane",
        choices=(
            "ordinary",
            "explicit-suite",
            "lifecycle-phase",
            "fatal-two-process",
            "native-process-fault",
            "optional",
        ),
    )
    args = parser.parse_args(argv)
    try:
        accounting = build_accounting()
        if args.command == "selectors":
            if args.lane is None:
                raise InventoryError("selectors requires --lane")
            print(",".join(selectors_for_lane(accounting, args.lane)))
        else:
            print(json.dumps(accounting, indent=2, sort_keys=True))
        return 0
    except (InventoryError, OSError, ValueError, KeyError, TypeError) as exc:
        print(f"DEVICE INVENTORY REFUSED: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
