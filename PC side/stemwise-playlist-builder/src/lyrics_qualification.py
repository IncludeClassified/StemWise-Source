"""Deterministic, text-free qualification for external lyrics format v2."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import subprocess
import shutil
import tempfile
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
from statistics import NormalDist
from typing import Any, Iterable
from stemwise_engine.song_enrichment_v2 import word_start_order_violations

from stemwise_engine.common import (
    parse_strict_json_bytes,
    read_bounded_regular_bytes,
)
from stemwise_engine.contract import (
    MAX_JSON_BYTES, MAX_COVER_BYTES, MAX_COVER_PIXELS_PER_SIDE,
    STRUCTURE_DISTINCT_PASSING_SEQUENCES_ALLOWED,
    STRUCTURE_INDEPENDENT_ROUTE_FAMILIES,
    STRUCTURE_MINIMUM_INDEPENDENT_SUPPORT,
)


FORMAT_VERSION = 2
METRIC_VERSION = "stemwise.lyrics-qualification-metrics.v1"
MAX_INPUT_BYTES = 16 * 1024 * 1024
_DIGEST = frozenset("0123456789abcdef")
_OUTCOMES = (
    "authority-unresolved",
    "lyrics-content-unresolved",
    "committed-words",
    "committed-mixed",
    "committed-lines",
    "line-unresolved",
    "retryable-technical",
)
_MINIMUM_GATES = (
    "lineBoundaryWithinToleranceRate",
    "wordBoundaryWithinToleranceRate",
    "performedOccurrenceAccuracy",
    "wordBearingLineCoverage",
    "safeAbstentionRate",
    "intervalValidityRate",
    "monotonicityRate",
    "utf16ProjectionRate",
    "graphemeProjectionRate",
    "lexicalAuthorityIdentityRate",
    "exactRecordingIdentityRate",
)
_MAXIMUM_GATES = (
    "falsePublicationUpperBound",
    "crossViewBoundarySpreadP95Ms",
    "chunkVsFullP95Ms",
    "rerunP95Ms",
    "canonicalTimelineResidualP95Ms",
    "unresolvedRate",
    "technicalFailureRate",
    "runtimeP95Ms",
    "peakMemoryMaxBytes",
    "diskMaxBytes",
    "retainedMaxBytes",
)


def _expect(value: Any, required: Iterable[str], *, optional: Iterable[str] = ()) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("qualification object is not a mapping")
    expected = set(required)
    allowed = expected | set(optional)
    if set(value) != expected and not (expected <= set(value) <= allowed):
        raise ValueError("qualification object has unexpected fields")
    return value


def _digest(value: Any) -> str:
    if not isinstance(value, str) or len(value) != 64 or any(c not in _DIGEST for c in value):
        raise ValueError("qualification digest is invalid")
    return value


def _number(value: Any, *, minimum: float = 0.0) -> float:
    if type(value) not in (int, float) or not math.isfinite(value) or value < minimum:
        raise ValueError("qualification number is invalid")
    return float(value)


def _integer(value: Any, *, minimum: int = 0) -> int:
    if type(value) is not int or value < minimum:
        raise ValueError("qualification integer is invalid")
    return value


def _boolean(value: Any) -> bool:
    if type(value) is not bool:
        raise ValueError("qualification boolean is invalid")
    return value


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _sha256_file(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        while chunk := source.read(128 * 1024):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def producer_system_identity(*, pc_only: bool = False) -> dict[str, str]:
    """Hash the exact PC/Android production candidate that qualification may release."""

    source = Path(__file__).resolve().parent
    builder = source.parent
    workspace = builder.parents[1]
    app = workspace / "StemWise" / "app"
    policy = builder / "future-songs" / "policy" / "lyrics-policy.json"
    config = builder / "config.json"
    pc_schema = workspace / "StemWise/contracts/enrichment/song-enrichment-v2.schema.json"
    from stemwise_engine.source_inputs import source_files
    files = [path for path in source_files(workspace, pc_only=pc_only)
             if path != policy and not path.relative_to(workspace).as_posix().startswith('Test suite/')
             and path.suffix.lower() != '.md'
             and (not pc_only or path.relative_to(workspace).as_posix().startswith('PC side/')
                  or path.relative_to(workspace).as_posix().startswith('StemWise/contracts/enrichment/'))]
    digest = hashlib.sha256()
    for path in sorted(files, key=lambda item: item.relative_to(workspace).as_posix()):
        if not path.is_file() or path.is_symlink():
            raise ValueError(f'producer system identity input is missing or linked: {path.relative_to(workspace)}')
        relative = path.relative_to(workspace).as_posix()
        size = path.stat().st_size
        digest.update(f"{relative}\0{size}\0".encode("utf-8"))
        with path.open("rb") as handle:
            while chunk := handle.read(128 * 1024):
                digest.update(chunk)
    pc_schema_sha256 = _sha256_file(pc_schema)[0]
    policy_value, _ = _read_json(policy, root=policy.parent)
    from stemwise_engine.future_song import FutureSongWorkflow
    policy_value = FutureSongWorkflow._effective_policy(policy_value)
    return {
        "sourceSha256": digest.hexdigest(),
        "lyricsPolicySemanticsSha256": _canonical_object_sha256(policy_value),
        "generatorConfigSha256": _sha256_file(config)[0],
        "externalSchemaSha256": pc_schema_sha256,
    }


def _canonical_object_sha256(value: Any) -> str:
    raw = json.dumps(
        value,
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return _sha256(raw)


def _device_digest(value: Any) -> str:
    if not isinstance(value, str) or re.fullmatch(r"[0-9A-Fa-f]{64}", value) is None:
        raise ValueError("device qualification digest is invalid")
    return value.lower()


def _read_json(path: Path, *, root: Path | None = None) -> tuple[dict[str, Any], bytes]:
    raw = read_bounded_regular_bytes(path, max_bytes=MAX_INPUT_BYTES, root=root)
    value = parse_strict_json_bytes(raw)
    if not isinstance(value, dict):
        raise ValueError("qualification JSON root is not an object")
    return value, raw


def _member(root: Path, value: Any) -> Path:
    if not isinstance(value, str):
        raise ValueError("qualification member path is invalid")
    relative = Path(value)
    if relative.is_absolute() or not relative.parts or ".." in relative.parts:
        raise ValueError("qualification member path escapes its manifest")
    path = root / relative
    try:
        path.resolve(strict=True).relative_to(root.resolve(strict=True))
    except (OSError, ValueError) as error:
        raise ValueError("qualification member is missing or escapes its manifest") from error
    return path


def _hashed_member(root: Path, value: Any) -> tuple[dict[str, Any], bytes]:
    item = _expect(value, {"path", "sha256"})
    expected = _digest(item["sha256"])
    parsed, raw = _read_json(_member(root, item["path"]), root=root)
    if _sha256(raw) != expected:
        raise ValueError("qualification member hash mismatch")
    return parsed, raw


def _validate_profile(value: Any, *, verify_metric_identity: bool = True, pc_only: bool = False) -> dict[str, Any]:
    profile = _expect(
        value,
        {
            "kind",
            "formatVersion",
            "status",
            "qualificationClaimsAllowed",
            "metricVersion",
            "metricCodeSha256",
            "producerSystem",
            "tokenizationVersion",
            "corpusManifestSha256",
            "annotationProtocolSha256",
            "hardwareProfiles",
            "androidQualification",
            "tolerancesMs",
            "structurePredicates",
            "splitPolicy",
            "strata",
            "confidenceBound",
            "gates",
        },
    )
    if profile["kind"] != "stemwise.lyrics-qualification-profile" or profile["formatVersion"] != FORMAT_VERSION:
        raise ValueError("only lyrics qualification profile v2 is supported")
    if profile["metricVersion"] != METRIC_VERSION:
        raise ValueError("qualification metric version is unsupported")
    metric_sha = _digest(profile['metricCodeSha256'])
    if profile["status"] not in {"unbound", "frozen"}:
        raise ValueError("qualification profile status is invalid")
    claims_allowed = _boolean(profile["qualificationClaimsAllowed"])
    if claims_allowed and profile["status"] != "frozen":
        raise ValueError("an unbound qualification profile cannot allow claims")
    if (verify_metric_identity or profile['status'] == 'frozen' or claims_allowed):
        if metric_sha != _sha256_file(Path(__file__))[0]:
            raise ValueError("qualification metric code hash mismatch")
    if profile["tokenizationVersion"] != "stemwise.display-words.v2":
        raise ValueError("qualification tokenizer version is unsupported")
    producer = _expect(
        profile["producerSystem"],
        {
            "sourceSha256",
            "lyricsPolicySemanticsSha256",
            "generatorConfigSha256",
            "externalSchemaSha256",
        },
    )

    hardware = _expect(profile["hardwareProfiles"], {"pc", "android"})
    android = _expect(
        profile["androidQualification"],
        {
            "sourceSha256",
            "deliverableArtifactSha256",
            "hostQualificationSha256",
            "appBuild",
            "requiredLanes",
        },
    )
    tolerances = _expect(profile["tolerancesMs"], {"lineStart", "lineEnd", "wordStart", "wordEnd"})
    for value in tolerances.values():
        _integer(value, minimum=1)
    predicates = _expect(
        profile["structurePredicates"],
        {"minimumIndependentSupport", "distinctPassingSequencesAllowed", "independentRouteFamilies"},
    )
    _integer(predicates["minimumIndependentSupport"], minimum=1)
    _integer(predicates["distinctPassingSequencesAllowed"], minimum=1)
    families = predicates["independentRouteFamilies"]
    if not isinstance(families, list) or not families or not all(isinstance(item, str) and item for item in families):
        raise ValueError("qualification route families are invalid")
    if predicates != {
        "minimumIndependentSupport": STRUCTURE_MINIMUM_INDEPENDENT_SUPPORT,
        "distinctPassingSequencesAllowed": STRUCTURE_DISTINCT_PASSING_SEQUENCES_ALLOWED,
        "independentRouteFamilies": list(STRUCTURE_INDEPENDENT_ROUTE_FAMILIES),
    }:
        raise ValueError("qualification structure predicates differ from runtime")
    split = _expect(
        profile["splitPolicy"],
        {"separateBy", "values", "scoredSplit", "heldOutMayTuneCandidate"},
    )
    if (
        split["separateBy"]
        != ["recordingSha256", "songVersionFamilySha256", "singerSha256"]
        or split["values"] != ["development", "held-out"]
        or split["scoredSplit"] != "held-out"
        or split["heldOutMayTuneCandidate"] is not False
    ):
        raise ValueError("qualification split policy is invalid")

    strata = _expect(profile["strata"], {"dimensions", "minimumCaseCount", "inventory"})
    if strata["dimensions"] != ["language", "script", "difficulty"]:
        raise ValueError("qualification strata dimensions are invalid")
    if not isinstance(strata["inventory"], list):
        raise ValueError("qualification strata inventory is invalid")
    inventory: set[tuple[str, str, str]] = set()
    for raw in strata["inventory"]:
        stratum = _validate_stratum_shape(raw)
        key = (stratum["language"], stratum["script"], stratum["difficulty"])
        if key in inventory:
            raise ValueError("qualification stratum inventory is duplicated")
        inventory.add(key)
    bound = profile["status"] == "frozen"
    if bound:
        current_producer = producer_system_identity(pc_only=True) if pc_only else producer_system_identity()
        if {name: _digest(value) for name, value in producer.items()} != current_producer:
            raise ValueError("frozen qualification producer system differs from current code")
    elif any(value is not None for value in producer.values()):
        raise ValueError("unbound qualification producer system must be empty")
    for identity in (profile["corpusManifestSha256"], profile["annotationProtocolSha256"], hardware["pc"]):
        if bound:
            _digest(identity)
        elif identity is not None:
            _digest(identity)
    if hardware["android"] is not None:
        _digest(hardware["android"])
    if android['appBuild'] is not None:
        app_build = _expect(android['appBuild'],
            {'applicationId', 'versionCode', 'versionName', 'certificateSha256'})
        if (app_build['applicationId'] != 'app.stemwise.development'
                or not isinstance(app_build['versionName'], str) or not app_build['versionName'].endswith('-development')):
            raise ValueError('Frozen Android app build is invalid')
        _integer(app_build['versionCode'], minimum=1)
        _digest(app_build['certificateSha256'])
    for identity in (
        android["sourceSha256"],
        android["deliverableArtifactSha256"],
        android["hostQualificationSha256"],
    ):
        if identity is not None:
            _digest(identity)
    if not isinstance(android["requiredLanes"], list):
        raise ValueError("Android qualification lane inventory is invalid")
    lane_inventory: set[tuple[str, str | None]] = set()
    for raw in android["requiredLanes"]:
        lane = _expect(raw, {"lane", "target"})
        if not isinstance(lane["lane"], str) or not lane["lane"]:
            raise ValueError("Android qualification lane identity is invalid")
        if lane["target"] is not None and (
            not isinstance(lane["target"], str) or not lane["target"]
        ):
            raise ValueError("Android qualification lane target is invalid")
        key = (lane["lane"], lane["target"])
        if key in lane_inventory:
            raise ValueError("Android qualification lane inventory is duplicated")
        lane_inventory.add(key)
    if claims_allowed and (
        hardware["android"] is None
        or any(
            android[name] is None
            for name in (
                "sourceSha256",
                "deliverableArtifactSha256",
                "hostQualificationSha256",
                "appBuild",
            )
        )
        or not lane_inventory
    ):
        raise ValueError("qualification claims require complete frozen Android evidence")
    if not bound and (
        hardware["android"] is not None
        or any(android[name] is not None for name in (
            "sourceSha256",
            "deliverableArtifactSha256",
            "hostQualificationSha256",
            "appBuild",
        ))
        or lane_inventory
    ):
        raise ValueError("unbound qualification profile contains frozen Android evidence")
    if bound:
        _integer(strata["minimumCaseCount"], minimum=1)
        if not inventory:
            raise ValueError("frozen qualification profile has no strata inventory")
    elif strata["minimumCaseCount"] is not None:
        _integer(strata["minimumCaseCount"], minimum=1)

    confidence = _expect(profile["confidenceBound"], {"method", "alpha"})
    if confidence["method"] != "wilson-one-sided-upper":
        raise ValueError("qualification confidence method is unsupported")
    alpha = _number(confidence["alpha"])
    if not 0.0 < alpha < 1.0:
        raise ValueError("qualification confidence alpha is invalid")

    gates = _expect(profile["gates"], {"applyToEachStratum", "minimum", "maximum"})
    _boolean(gates["applyToEachStratum"])
    minimum = _expect(gates["minimum"], _MINIMUM_GATES)
    maximum = _expect(gates["maximum"], _MAXIMUM_GATES)
    for gate in (*minimum.values(), *maximum.values()):
        if gate is not None:
            _number(gate)
        elif bound:
            raise ValueError("frozen qualification profile has an unbound gate")
    for name in _MINIMUM_GATES:
        if minimum[name] is not None and minimum[name] > 1.0:
            raise ValueError("qualification rate gate exceeds one")
    for name in ("falsePublicationUpperBound", "unresolvedRate", "technicalFailureRate"):
        if maximum[name] is not None and maximum[name] > 1.0:
            raise ValueError("qualification rate gate exceeds one")
    return profile


def qualification_profile_identity(profile_path: Path) -> dict[str, Any]:
    """Validate one profile and return only its production binding fields."""

    value, raw = _read_json(profile_path, root=profile_path.parent)
    profile = _validate_profile(value, verify_metric_identity=False)
    return {
        "sha256": _sha256(raw),
        "status": profile["status"],
        "qualificationClaimsAllowed": profile["qualificationClaimsAllowed"],
    }




def _validate_stratum_shape(value: Any) -> dict[str, str]:
    stratum = _expect(value, {"language", "script", "difficulty"})
    if (
        not isinstance(stratum["language"], str)
        or re.fullmatch(r"[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*", stratum["language"]) is None
        or not isinstance(stratum["script"], str)
        or re.fullmatch(r"[A-Z][a-z]{3}", stratum["script"]) is None
        or not isinstance(stratum["difficulty"], str)
        or not stratum["difficulty"]
    ):
        raise ValueError("qualification stratum is invalid")
    return stratum


def _validate_stratum(value: Any, profile: dict[str, Any]) -> dict[str, str]:
    stratum = _validate_stratum_shape(value)
    if stratum not in profile["strata"]["inventory"]:
        raise ValueError("qualification stratum is not in the frozen inventory")
    return stratum


def _validate_words(value: Any, duration_ms: int) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise ValueError("qualification words are invalid")
    output: list[dict[str, Any]] = []
    for raw in value:
        word = _expect(raw, {"wordIdSha256", "startUtf16", "endUtf16", "startMs", "endMs"})
        _digest(word["wordIdSha256"])
        start_utf16 = _integer(word["startUtf16"])
        end_utf16 = _integer(word["endUtf16"], minimum=1)
        start_ms = _integer(word["startMs"])
        end_ms = _integer(word["endMs"], minimum=1)
        if start_utf16 >= end_utf16 or start_ms >= end_ms or end_ms > duration_ms:
            raise ValueError("qualification word interval is invalid")
        output.append(word)
    if word_start_order_violations(output):
        raise ValueError('qualification word starts are not monotonic')
    return output


def _validate_reference(value: Any, profile: dict[str, Any]) -> dict[str, Any]:
    reference = _expect(
        value,
        {
            "kind",
            "formatVersion",
            "caseIdSha256",
            "lexicalAuthoritySha256",
            "recordingSha256",
            "songVersionFamilySha256",
            "singerSha256",
            "split",
            "durationMs",
            "stratum",
            "occurrences",
        },
    )
    if reference["kind"] != "stemwise.lyrics-qualification-reference" or reference["formatVersion"] != FORMAT_VERSION:
        raise ValueError("only lyrics qualification reference v2 is supported")
    _digest(reference["caseIdSha256"])
    _digest(reference["lexicalAuthoritySha256"])
    _digest(reference["recordingSha256"])
    _digest(reference["songVersionFamilySha256"])
    _digest(reference["singerSha256"])
    if reference["split"] not in profile["splitPolicy"]["values"]:
        raise ValueError("qualification reference split is invalid")
    duration_ms = _integer(reference["durationMs"], minimum=1)
    _validate_stratum(reference["stratum"], profile)
    if not isinstance(reference["occurrences"], list) or not reference["occurrences"]:
        raise ValueError("qualification reference occurrences are invalid")
    seen: set[str] = set()
    previous_start_ms: int | None = None
    for raw in reference["occurrences"]:
        occurrence = _expect(
            raw,
            {"occurrenceIdSha256", "eligibleForWordTiming", "unsafeForWordPublication", "startMs", "endMs", "words"},
        )
        occurrence_id = _digest(occurrence["occurrenceIdSha256"])
        if occurrence_id in seen:
            raise ValueError("qualification occurrence identity is duplicated")
        seen.add(occurrence_id)
        eligible = _boolean(occurrence["eligibleForWordTiming"])
        unsafe = _boolean(occurrence["unsafeForWordPublication"])
        if eligible and unsafe:
            raise ValueError("qualification occurrence cannot be eligible and unsafe")
        start_ms = _integer(occurrence["startMs"])
        end_ms = _integer(occurrence["endMs"], minimum=1)
        if start_ms >= end_ms or end_ms > duration_ms:
            raise ValueError("qualification reference interval is invalid")
        if previous_start_ms is not None and start_ms < previous_start_ms:
            raise ValueError("qualification reference is not monotonic")
        previous_start_ms = start_ms
        words = _validate_words(occurrence["words"], duration_ms)
        if len({word["wordIdSha256"] for word in words}) != len(words):
            raise ValueError("qualification reference word identity is duplicated")
        if any(word["startMs"] < start_ms or word["endMs"] > end_ms for word in words):
            raise ValueError("qualification reference word escapes its line")
        if any(
            left["endUtf16"] > right["startUtf16"]
            for left, right in zip(words, words[1:])
        ):
            raise ValueError("qualification reference display ranges overlap")
    return reference


def _validate_result(
    value: Any, duration_ms: int, producer_system_sha256: str, *, measurement: bool = False
) -> dict[str, Any]:
    result = _expect(
        value,
        {
            "kind",
            "formatVersion",
            "caseIdSha256",
            "lexicalAuthoritySha256",
            "recordingSha256",
            "producerSystemSha256",
            "outcome",
            "occurrences",
            "validation",
            "diagnostics",
            "resources",
        },
    )
    if result["kind"] != "stemwise.lyrics-qualification-result" or result["formatVersion"] != FORMAT_VERSION:
        raise ValueError("only lyrics qualification result v2 is supported")
    _digest(result["caseIdSha256"])
    _digest(result["lexicalAuthoritySha256"])
    _digest(result["recordingSha256"])
    if _digest(result["producerSystemSha256"]) != producer_system_sha256:
        raise ValueError("qualification result producer system differs")
    if result["outcome"] not in _OUTCOMES:
        raise ValueError("qualification outcome is invalid")
    if not isinstance(result["occurrences"], list):
        raise ValueError("qualification result occurrences are invalid")
    published_word_count = 0
    seen_occurrences: set[str] = set()
    for raw in result["occurrences"]:
        occurrence = _expect(raw, {"occurrenceIdSha256", "startMs", "endMs", "words"})
        occurrence_id = _digest(occurrence["occurrenceIdSha256"])
        if occurrence_id in seen_occurrences:
            raise ValueError("qualification result occurrence identity is duplicated")
        seen_occurrences.add(occurrence_id)
        for key in ("startMs", "endMs"):
            if type(occurrence[key]) is not int:
                raise ValueError("qualification result interval is malformed")
        if not isinstance(occurrence["words"], list):
            raise ValueError("qualification result words are invalid")
        seen_words: set[str] = set()
        for raw_word in occurrence["words"]:
            word = _expect(raw_word, {"wordIdSha256", "startUtf16", "endUtf16", "startMs", "endMs"})
            word_id = _digest(word["wordIdSha256"])
            if word_id in seen_words:
                raise ValueError("qualification result word identity is duplicated")
            seen_words.add(word_id)
            if any(type(word[key]) is not int for key in ("startUtf16", "endUtf16", "startMs", "endMs")):
                raise ValueError("qualification result word is malformed")
        published_word_count += len(occurrence["words"])

    occurrences = result["occurrences"]
    word_bearing = sum(bool(item["words"]) for item in occurrences)
    outcome = result["outcome"]
    if outcome in {"authority-unresolved", "lyrics-content-unresolved", "line-unresolved", "retryable-technical"}:
        outcome_valid = not occurrences
    elif outcome == "committed-words":
        outcome_valid = bool(occurrences) and word_bearing == len(occurrences)
    elif outcome == "committed-mixed":
        outcome_valid = 0 < word_bearing < len(occurrences)
    else:
        outcome_valid = bool(occurrences) and word_bearing == 0
    if not outcome_valid:
        raise ValueError("qualification result outcome contradicts its occurrences")

    validation = _expect(
        result["validation"],
        {
            "displayProjectionChecked",
            "displayProjectionValid",
            "graphemeProjectionChecked",
            "graphemeProjectionValid",
        },
    )
    for checked, valid in (
        ("displayProjectionChecked", "displayProjectionValid"),
        ("graphemeProjectionChecked", "graphemeProjectionValid"),
    ):
        _integer(validation[checked])
        _integer(validation[valid])
        if validation[valid] > validation[checked]:
            raise ValueError("qualification projection counts are inconsistent")
        if validation[checked] != published_word_count:
            raise ValueError("qualification projection coverage is incomplete")

    diagnostics = _expect(
        result["diagnostics"],
        {
            "crossViewBoundarySpreadMs",
            "chunkVsFullBoundaryDeltaMs",
            "rerunBoundaryDeltaMs",
            "canonicalTimelineResidualMs",
            "fallbackRoutes",
        },
    )
    for key in (
        "crossViewBoundarySpreadMs",
        "chunkVsFullBoundaryDeltaMs",
        "rerunBoundaryDeltaMs",
        "canonicalTimelineResidualMs",
    ):
        if not isinstance(diagnostics[key], list):
            raise ValueError("qualification diagnostic series is invalid")
        for item in diagnostics[key]:
            _number(item)
    if not isinstance(diagnostics["fallbackRoutes"], list):
        raise ValueError("qualification fallback routes are invalid")
    for raw in diagnostics["fallbackRoutes"]:
        route = _expect(raw, {"routeIdSha256", "count"})
        _digest(route["routeIdSha256"])
        _integer(route["count"], minimum=1)

    resources = _expect(result["resources"], {"runtimeMs", "peakMemoryBytes", "diskBytes", "retainedBytes"},
        optional={'activeMs', 'newInferenceItems', 'peakGpuMemoryBytes'} if measurement else ())
    for item in resources.values():
        _integer(item)
    return result


def _p95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)]


def _summary(values: list[float]) -> dict[str, float | None]:
    return {
        "median": statistics.median(values) if values else None,
        "p95": _p95(values),
    }


def _rate(numerator: int, denominator: int, *, vacuous: bool = False) -> float | None:
    if denominator:
        return numerator / denominator
    return 1.0 if vacuous else None


def _wilson_upper(failures: int, total: int, alpha: float) -> float | None:
    if total == 0:
        return None
    z = NormalDist().inv_cdf(1.0 - alpha)
    proportion = failures / total
    z2 = z * z
    denominator = 1.0 + z2 / total
    center = (proportion + z2 / (2.0 * total)) / denominator
    margin = z * math.sqrt((proportion * (1.0 - proportion) + z2 / (4.0 * total)) / total) / denominator
    return min(1.0, center + margin)


def _new_accumulator() -> dict[str, Any]:
    return {
        "cases": 0,
        "lineStart": [],
        "lineEnd": [],
        "wordStart": [],
        "wordEnd": [],
        "lineWithin": 0,
        "lineBoundaries": 0,
        "wordWithin": 0,
        "wordBoundaries": 0,
        "occurrenceCorrect": 0,
        "occurrenceTotal": 0,
        "wordLines": 0,
        "eligibleWordLines": 0,
        "falsePublished": 0,
        "published": 0,
        "safeAbstained": 0,
        "unsafe": 0,
        "validIntervals": 0,
        "intervals": 0,
        "monotonic": 0,
        "monotonicChecks": 0,
        "utf16Valid": 0,
        "utf16Checked": 0,
        "graphemeValid": 0,
        "graphemeChecked": 0,
        "lexical": 0,
        "identity": 0,
        "crossView": [],
        "chunk": [],
        "rerun": [],
        "residual": [],
        "runtime": [],
        "memory": [],
        "disk": [],
        "retained": [],
        "outcomes": Counter(),
        "routes": Counter(),
    }


def _add_case(accumulator: dict[str, Any], reference: dict[str, Any], result: dict[str, Any], profile: dict[str, Any]) -> None:
    accumulator["cases"] += 1
    refs = reference["occurrences"]
    candidates = result["occurrences"]
    ref_by_id = {item["occurrenceIdSha256"]: item for item in refs}
    candidate_by_id = {item["occurrenceIdSha256"]: item for item in candidates}
    if len(candidate_by_id) != len(candidates):
        raise ValueError("qualification result occurrence identity is duplicated")
    accumulator["occurrenceCorrect"] += sum(
        left["occurrenceIdSha256"] == right["occurrenceIdSha256"]
        for left, right in zip(refs, candidates)
    )
    accumulator["occurrenceTotal"] += max(len(refs), len(candidates))

    previous_start: int | None = None
    for candidate in candidates:
        reversed_starts = word_start_order_violations(candidate['words'])
        accumulator["intervals"] += 1 + len(candidate["words"])
        line_valid = 0 <= candidate["startMs"] < candidate["endMs"] <= reference["durationMs"]
        accumulator["validIntervals"] += line_valid
        accumulator["validIntervals"] += sum(
            index not in reversed_starts
            and
            line_valid
            and 0 <= word["startMs"] < word["endMs"] <= reference["durationMs"]
            and candidate["startMs"] <= word["startMs"]
            and word["endMs"] <= candidate["endMs"]
            for index, word in enumerate(candidate["words"])
        )
        accumulator['monotonicChecks'] += max(0, len(candidate['words']) - 1)
        accumulator['monotonic'] += max(0, len(candidate['words']) - 1) - len(reversed_starts)
        if previous_start is not None:
            accumulator["monotonicChecks"] += 1
            accumulator["monotonic"] += candidate["startMs"] >= previous_start
        previous_start = candidate["startMs"]

    line_start_tolerance = profile["tolerancesMs"]["lineStart"]
    line_end_tolerance = profile["tolerancesMs"]["lineEnd"]
    word_start_tolerance = profile["tolerancesMs"]["wordStart"]
    word_end_tolerance = profile["tolerancesMs"]["wordEnd"]
    derived_utf16_valid = 0
    for candidate in candidates:
        accumulator["published"] += 1
        reference_line = ref_by_id.get(candidate["occurrenceIdSha256"])
        false_publication = not (
            0 <= candidate["startMs"] < candidate["endMs"] <= reference["durationMs"]
        ) or reference_line is None
        false_publication |= bool(word_start_order_violations(candidate['words']))
        if reference_line is None:
            accumulator["lineBoundaries"] += 2
            accumulator["wordBoundaries"] += 2 * len(candidate["words"])
            accumulator["falsePublished"] += 1
            continue
        line_start_error = abs(candidate["startMs"] - reference_line["startMs"])
        line_end_error = abs(candidate["endMs"] - reference_line["endMs"])
        accumulator["lineStart"].append(line_start_error)
        accumulator["lineEnd"].append(line_end_error)
        accumulator["lineBoundaries"] += 2
        accumulator["lineWithin"] += line_start_error <= line_start_tolerance
        accumulator["lineWithin"] += line_end_error <= line_end_tolerance
        false_publication |= line_start_error > line_start_tolerance or line_end_error > line_end_tolerance

        if reference_line["eligibleForWordTiming"]:
            accumulator["eligibleWordLines"] += 1
            accumulator["wordLines"] += bool(candidate["words"])
        if reference_line["unsafeForWordPublication"]:
            accumulator["unsafe"] += 1
            accumulator["safeAbstained"] += not candidate["words"]
            false_publication |= bool(candidate["words"])

        reference_words = {item["wordIdSha256"]: item for item in reference_line["words"]}
        if candidate["words"] and [item["wordIdSha256"] for item in candidate["words"]] != [
            item["wordIdSha256"] for item in reference_line["words"]
        ]:
            false_publication = True
        for word in candidate["words"]:
            accumulator["wordBoundaries"] += 2
            expected = reference_words.get(word["wordIdSha256"])
            if expected is None or (word["startUtf16"], word["endUtf16"]) != (expected["startUtf16"], expected["endUtf16"]):
                false_publication = True
                continue
            derived_utf16_valid += 1
            if not (
                0 <= word["startMs"] < word["endMs"] <= reference["durationMs"]
                and candidate["startMs"] <= word["startMs"]
                and word["endMs"] <= candidate["endMs"]
            ):
                false_publication = True
            start_error = abs(word["startMs"] - expected["startMs"])
            end_error = abs(word["endMs"] - expected["endMs"])
            accumulator["wordStart"].append(start_error)
            accumulator["wordEnd"].append(end_error)
            accumulator["wordWithin"] += start_error <= word_start_tolerance
            accumulator["wordWithin"] += end_error <= word_end_tolerance
            false_publication |= start_error > word_start_tolerance or end_error > word_end_tolerance
        accumulator["falsePublished"] += false_publication

    # Missing unsafe occurrences are safe abstentions, while missing eligible
    # occurrences still lower occurrence accuracy and word-bearing coverage.
    for reference_line in refs:
        if reference_line["occurrenceIdSha256"] in candidate_by_id:
            continue
        if reference_line["eligibleForWordTiming"]:
            accumulator["eligibleWordLines"] += 1
        if reference_line["unsafeForWordPublication"]:
            accumulator["unsafe"] += 1
            accumulator["safeAbstained"] += 1

    validation = result["validation"]
    accumulator["utf16Checked"] += validation["displayProjectionChecked"]
    accumulator["utf16Valid"] += min(
        validation["displayProjectionValid"], derived_utf16_valid
    )
    accumulator["graphemeChecked"] += validation["graphemeProjectionChecked"]
    accumulator["graphemeValid"] += validation["graphemeProjectionValid"]
    accumulator["lexical"] += (
        result["lexicalAuthoritySha256"] == reference["lexicalAuthoritySha256"]
    )
    accumulator["identity"] += result["recordingSha256"] == reference["recordingSha256"]
    diagnostics = result["diagnostics"]
    accumulator["crossView"].extend(diagnostics["crossViewBoundarySpreadMs"])
    accumulator["chunk"].extend(diagnostics["chunkVsFullBoundaryDeltaMs"])
    accumulator["rerun"].extend(diagnostics["rerunBoundaryDeltaMs"])
    accumulator["residual"].extend(diagnostics["canonicalTimelineResidualMs"])
    accumulator["routes"].update({item["routeIdSha256"]: item["count"] for item in diagnostics["fallbackRoutes"]})
    resources = result["resources"]
    accumulator["runtime"].append(resources["runtimeMs"])
    accumulator["memory"].append(resources["peakMemoryBytes"])
    accumulator["disk"].append(resources["diskBytes"])
    accumulator["retained"].append(resources["retainedBytes"])
    accumulator["outcomes"][result["outcome"]] += 1


def _metrics(accumulator: dict[str, Any], alpha: float) -> dict[str, Any]:
    cases = accumulator["cases"]
    outcomes = {key: accumulator["outcomes"].get(key, 0) for key in _OUTCOMES}
    return {
        "caseCount": cases,
        "lineStartErrorMs": _summary(accumulator["lineStart"]),
        "lineEndErrorMs": _summary(accumulator["lineEnd"]),
        "wordStartErrorMs": _summary(accumulator["wordStart"]),
        "wordEndErrorMs": _summary(accumulator["wordEnd"]),
        "lineBoundaryWithinToleranceRate": _rate(accumulator["lineWithin"], accumulator["lineBoundaries"]),
        "wordBoundaryWithinToleranceRate": _rate(accumulator["wordWithin"], accumulator["wordBoundaries"]),
        "performedOccurrenceAccuracy": _rate(accumulator["occurrenceCorrect"], accumulator["occurrenceTotal"]),
        "wordBearingLineCoverage": _rate(accumulator["wordLines"], accumulator["eligibleWordLines"]),
        "falsePublicationRate": _rate(accumulator["falsePublished"], accumulator["published"]),
        "falsePublicationUpperBound": _wilson_upper(accumulator["falsePublished"], accumulator["published"], alpha),
        "safeAbstentionRate": _rate(accumulator["safeAbstained"], accumulator["unsafe"]),
        "intervalValidityRate": _rate(accumulator["validIntervals"], accumulator["intervals"], vacuous=True),
        "monotonicityRate": _rate(accumulator["monotonic"], accumulator["monotonicChecks"], vacuous=True),
        "utf16ProjectionRate": _rate(accumulator["utf16Valid"], accumulator["utf16Checked"], vacuous=True),
        "graphemeProjectionRate": _rate(accumulator["graphemeValid"], accumulator["graphemeChecked"], vacuous=True),
        "lexicalAuthorityIdentityRate": _rate(accumulator["lexical"], cases),
        "exactRecordingIdentityRate": _rate(accumulator["identity"], cases),
        "crossViewBoundarySpreadP95Ms": _p95(accumulator["crossView"]),
        "chunkVsFullP95Ms": _p95(accumulator["chunk"]),
        "rerunP95Ms": _p95(accumulator["rerun"]),
        "canonicalTimelineResidualP95Ms": _p95(accumulator["residual"]),
        "unresolvedRate": _rate(outcomes["authority-unresolved"] + outcomes["lyrics-content-unresolved"] + outcomes["line-unresolved"], cases),
        "technicalFailureRate": _rate(outcomes["retryable-technical"], cases),
        "runtimeP95Ms": _p95(accumulator["runtime"]),
        "runtimeTotalMs": sum(accumulator["runtime"]),
        "peakMemoryMaxBytes": max(accumulator["memory"], default=None),
        "diskMaxBytes": max(accumulator["disk"], default=None),
        "retainedMaxBytes": max(accumulator["retained"], default=None),
        "fallbackRouteUtilization": [
            {"routeIdSha256": route, "count": count}
            for route, count in sorted(accumulator["routes"].items())
        ],
        "outcomes": outcomes,
    }


def _gate_results(scope: str, metrics: dict[str, Any], profile: dict[str, Any]) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    for operator, values in ((">=", profile["gates"]["minimum"]), ("<=", profile["gates"]["maximum"])):
        for name, threshold in values.items():
            actual = metrics[name]
            passed = actual is not None and (actual >= threshold if operator == ">=" else actual <= threshold)
            output.append({"scope": scope, "metric": name, "operator": operator, "threshold": threshold, "actual": actual, "passed": passed})
    return output


def _validate_device_phase_source(report, report_root):
    source_manifest_ref = _expect(
        report["qualificationSourceManifest"], {"path", "sha256"}
    )
    source_manifest_path = _member(report_root, source_manifest_ref["path"])
    source_manifest_raw = read_bounded_regular_bytes(
        source_manifest_path, max_bytes=MAX_INPUT_BYTES, root=report_root
    )
    if _sha256(source_manifest_raw) != _device_digest(source_manifest_ref["sha256"]):
        raise ValueError("Android qualification source manifest hash mismatch")
    source_manifest = parse_strict_json_bytes(source_manifest_raw)
    if not isinstance(source_manifest, list) or not source_manifest:
        raise ValueError("Android qualification source manifest is invalid")
    source_digest = hashlib.sha256()
    previous_path = ""
    for raw_entry in source_manifest:
        entry = _expect(raw_entry, {"path", "bytes", "sha256"})
        path = entry["path"]
        if (
            not isinstance(path, str)
            or not path
            or "\\" in path
            or path.startswith("/")
            or ".." in path.split("/")
            or path <= previous_path
        ):
            raise ValueError("Android qualification source manifest path is invalid")
        previous_path = path
        byte_count = _integer(entry["bytes"])
        digest = _device_digest(entry["sha256"]).upper()
        source_digest.update(f"{path}\0{byte_count}\0{digest}\0".encode("utf-8"))
    if source_digest.hexdigest() != _device_digest(report["qualificationSourceSha256"]):
        raise ValueError("Android qualification source manifest differs from source hash")


def _validate_device_qualification_report(
    value: Any,
    expected_profile_sha256: str,
    expected_android: dict[str, Any],
    report_root: Path,
) -> dict[str, Any]:
    full = _expect(value, {"schemaVersion", "mode", "status", "phases"})
    if full["schemaVersion"] != 3 or full["mode"] != "Full" or full["status"] != "PASS":
        raise ValueError("complete PC and Android qualification did not pass")
    phases = _expect(full["phases"], {"PcSuite", "AndroidSuite"})
    loaded = {}
    for name, binding in phases.items():
        phase, _ = _hashed_member(report_root.parent, binding)
        loaded[name] = (phase, _member(report_root.parent, binding["path"]).parent)
    pc, pc_root = loaded["PcSuite"]
    android, android_root = loaded["AndroidSuite"]
    if (pc.get("schemaVersion") != FORMAT_VERSION or pc.get("mode") != "PcSuite"
            or pc.get("status") != "PASS" or pc.get("failure") is not None or pc.get("failureKind") is not None):
        raise ValueError("complete PC qualification did not pass")
    if (pc.get("qualificationSourceSha256") != android.get("qualificationSourceSha256")
            or pc.get("producerSystemSha256") != android.get("producerSystemSha256")):
        raise ValueError("complete qualification phases have different source or producer")
    _validate_device_phase_report(android, expected_profile_sha256, expected_android, android_root, pc, pc_root)
    return full


def _validate_device_phase_report(
    value: Any,
    expected_profile_sha256: str,
    expected_android: dict[str, Any],
    report_root: Path,
    pc: dict[str, Any],
    pc_root: Path,
) -> dict[str, Any]:
    report = _expect(
        value,
        {
            "schemaVersion",
            "mode",
            "serial",
            "runId",
            "attemptId",
            "started",
            "finished",
            "status",
            "failure",
            "failureKind",
            "lanes",
            "deviceProfile",
            "artifact",
            "artifacts",
            "hostQualification",
            "qualificationSourceSha256",
            "qualificationSourceManifest",
            "deviceInventory",
            "deviceExecutionAccounting",
            "producerSystemSha256",
            "audioSourceSha256",
            "audioSourceEndSha256",
            "audioArtifacts",
            "audioExecutions",
            "audioBuildConfiguration",
            "audioBuildEvidence",
        },
        optional={"cleanupWarnings"},
    )
    _expect(pc, set(report) - {"cleanupWarnings"}, optional={"cleanupWarnings"})
    if report["schemaVersion"] != FORMAT_VERSION or report["mode"] != "Device":
        raise ValueError("only Android qualification report v2 is supported")
    if report["status"] != "PASS" or report["failure"] is not None or report["failureKind"] is not None:
        raise ValueError("Android qualification report did not pass")
    for key in ("serial", "runId", "attemptId", "started", "finished"):
        if not isinstance(report[key], str) or not report[key]:
            raise ValueError("Android qualification report identity is invalid")
    profile = _expect(report["deviceProfile"], {
        "serial", "model", "device", "socModel", "androidBuildFingerprint", "api",
    })
    if (profile["serial"] != report["serial"]
            or any(not isinstance(profile[name], str) or not profile[name].strip()
                   for name in ("serial", "model", "device", "socModel", "androidBuildFingerprint"))
            or re.fullmatch(r"[A-Za-z0-9._:-]+", profile["serial"]) is None):
        raise ValueError("Android qualification device identity is invalid")
    _integer(profile["api"], minimum=28)
    if _canonical_object_sha256(profile) != expected_profile_sha256:
        raise ValueError("Android qualification device profile hash mismatch")
    if _device_digest(report["qualificationSourceSha256"]) != expected_android["sourceSha256"]:
        raise ValueError("Android qualification source hash mismatch")
    _validate_device_phase_source(report, report_root)
    _validate_device_phase_source(pc, pc_root)
    inventory, _ = _hashed_member(report_root, report["deviceInventory"])
    inventory = _expect(
        inventory,
        {
            "deliberateExclusionCount",
            "laneCounts",
            "methodCount",
            "methods",
            "schemaVersion",
            "sourceRoot",
            "status",
            "suiteEntries",
            "suiteEntryCount",
            "unassignedCount",
            "unintendedDuplicateCount",
            "wrapperCount",
            "wrappers",
        },
    )
    if (
        inventory["schemaVersion"] != 1
        or inventory["status"] != "PASS"
        or _integer(inventory["methodCount"], minimum=1)
        != len(inventory["methods"])
        or _integer(inventory["suiteEntryCount"])
        != len(inventory["suiteEntries"])
        or _integer(inventory["wrapperCount"]) != len(inventory["wrappers"])
        or _integer(inventory["unassignedCount"]) != 0
        or _integer(inventory["unintendedDuplicateCount"]) != 0
        or not isinstance(inventory["laneCounts"], dict)
        or not isinstance(inventory["sourceRoot"], str)
        or not inventory["sourceRoot"]
    ):
        raise ValueError("Android qualification device inventory did not pass")
    method_ids = [
        item.get("id") if isinstance(item, dict) else None
        for item in inventory["methods"]
    ]
    if (
        any(not isinstance(value, str) or not value for value in method_ids)
        or len(set(method_ids)) != len(method_ids)
    ):
        raise ValueError("Android qualification device method inventory is invalid")
    accounting, _ = _hashed_member(
        report_root, report["deviceExecutionAccounting"]
    )
    accounting = _expect(
        accounting,
        {
            "schemaVersion",
            "label",
            "status",
            "expectedCount",
            "passingCount",
            "skippedCount",
            "expectedMethods",
            "passingMethods",
            "skippedMethods",
            "missingMethods",
            "unexpectedMethods",
        },
    )
    if (
        accounting["schemaVersion"] != 1
        or accounting["label"] != "device"
        or accounting["status"] != "PASS"
        or accounting["missingMethods"] != []
        or accounting["unexpectedMethods"] != []
        or accounting["expectedMethods"]
        != sorted(set(accounting["expectedMethods"]))
        or accounting["passingMethods"] != accounting["expectedMethods"]
        or any(value not in method_ids for value in accounting["expectedMethods"])
        or _integer(accounting["expectedCount"], minimum=1)
        != len(accounting["expectedMethods"])
        or _integer(accounting["passingCount"], minimum=1)
        != len(accounting["passingMethods"])
        or _integer(accounting["skippedCount"]) != len(accounting["skippedMethods"])
        or any(
            value not in accounting["expectedMethods"]
            for value in accounting["skippedMethods"]
        )
    ):
        raise ValueError("Android qualification execution accounting differs")
    if _device_digest(report["producerSystemSha256"]) != _canonical_object_sha256(
        producer_system_identity()
    ):
        raise ValueError("Android qualification producer system mismatch")
    if not isinstance(report["lanes"], list) or not report["lanes"]:
        raise ValueError("Android qualification report has no device lanes")
    actual_lanes: set[tuple[str, str | None]] = set()
    for raw in report["lanes"]:
        lane = _expect(
            raw,
            {"lane", "target", "started", "finished", "status", "failure", "cleanupFailure", "failureKind"},
        )
        if not isinstance(lane["lane"], str) or not lane["lane"]:
            raise ValueError("Android qualification lane identity is invalid")
        if lane["target"] is not None and (
            not isinstance(lane["target"], str) or not lane["target"]
        ):
            raise ValueError("Android qualification lane target is invalid")
        identity = (lane["lane"], lane["target"])
        if identity in actual_lanes:
            raise ValueError("Android qualification lane is duplicated")
        actual_lanes.add(identity)
        if (lane["status"] != "PASS" or lane["failure"] is not None
                or lane["cleanupFailure"] is not None or lane["failureKind"] is not None):
            raise ValueError("Android qualification report contains a failed lane")
    expected_lanes = {
        (item["lane"], item["target"])
        for item in expected_android["requiredLanes"]
    }
    if actual_lanes != expected_lanes:
        raise ValueError("Android qualification lane inventory mismatch")

    host = _expect(pc["hostQualification"], {"unitTests", "lint"})
    unit = _expect(host["unitTests"], {"counts", "reportLocations"})
    unit_counts = _expect(
        unit["counts"],
        {"suites", "tests", "failures", "errors", "skipped", "methods"},
    )
    _integer(unit_counts["suites"], minimum=1)
    _integer(unit_counts["tests"], minimum=1)
    for name in ("failures", "errors", "skipped"):
        if _integer(unit_counts[name]) != 0:
            raise ValueError("Android qualification host unit tests did not pass cleanly")
    if (
        not isinstance(unit_counts["methods"], list)
        or len(unit_counts["methods"]) != unit_counts["tests"]
        or len(set(unit_counts["methods"])) != len(unit_counts["methods"])
        or not all(isinstance(item, str) and item for item in unit_counts["methods"])
    ):
        raise ValueError("Android qualification host test inventory is invalid")
    lint = _expect(host["lint"], {"counts", "reportLocations"})
    lint_counts = _expect(
        lint["counts"],
        {"issues", "fatal", "errors", "warnings", "hints", "informational"},
    )
    for name, value in lint_counts.items():
        _integer(value)
        if name in {"fatal", "errors"} and value != 0:
            raise ValueError("Android qualification host lint did not pass cleanly")
    for locations in (unit["reportLocations"], lint["reportLocations"]):
        if not isinstance(locations, dict) or not locations or not all(
            isinstance(path, str) and path for path in locations.values()
        ):
            raise ValueError("Android qualification host report locations are invalid")
    unit_locations = _expect(unit["reportLocations"], {"junitXml"})
    junit_root = _member(pc_root, unit_locations["junitXml"])
    junit_files = sorted(junit_root.glob("TEST-*.xml")) if junit_root.is_dir() else []
    measured_unit = {
        "suites": len(junit_files),
        "tests": 0,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "methods": [],
    }
    for path in junit_files:
        raw = read_bounded_regular_bytes(path, max_bytes=MAX_INPUT_BYTES, root=pc_root)
        if b"<!DOCTYPE" in raw.upper():
            raise ValueError("Android qualification JUnit XML contains a DTD")
        root = ET.fromstring(raw)
        if root.tag != "testsuite":
            raise ValueError("Android qualification JUnit root is invalid")
        cases = root.findall(".//testcase")
        counts = {
            name: _integer(int(root.attrib.get(name, "-1")))
            for name in ("tests", "failures", "errors", "skipped")
        }
        if counts["tests"] != len(cases):
            raise ValueError("Android qualification JUnit count differs")
        for name, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
            if counts[name] != sum(case.find(tag) is not None for case in cases):
                raise ValueError("Android qualification JUnit testcase outcomes differ from counts")
        for name, count in counts.items():
            measured_unit[name] += count
        measured_unit["methods"].extend(
            f"{case.attrib.get('classname', '')}#{case.attrib.get('name', '')}"
            for case in cases
        )
    measured_unit["methods"].sort()
    if measured_unit != unit_counts:
        raise ValueError("Android qualification JUnit evidence differs from summary")
    lint_locations = _expect(lint["reportLocations"], {"xml"})
    lint_raw = read_bounded_regular_bytes(
        _member(pc_root, lint_locations["xml"]),
        max_bytes=MAX_INPUT_BYTES,
        root=pc_root,
    )
    if b"<!DOCTYPE" in lint_raw.upper():
        raise ValueError("Android qualification lint XML contains a DTD")
    lint_root = ET.fromstring(lint_raw)
    if lint_root.tag != "issues":
        raise ValueError("Android qualification lint root is invalid")
    measured_lint = {name: 0 for name in lint_counts}
    severity_names = {
        "Fatal": "fatal",
        "Error": "errors",
        "Warning": "warnings",
        "Hint": "hints",
        "Information": "informational",
    }
    for issue in lint_root.findall("issue"):
        name = severity_names.get(issue.attrib.get("severity"))
        if name is None:
            raise ValueError("Android qualification lint severity is invalid")
        measured_lint["issues"] += 1
        measured_lint[name] += 1
    if measured_lint != lint_counts:
        raise ValueError("Android qualification lint evidence differs from summary")
    if _canonical_object_sha256(host) != expected_android["hostQualificationSha256"]:
        raise ValueError("Android qualification host evidence hash mismatch")

    artifact = _expect(report["artifact"], {"fileName", "bytes", "sha256"})
    artifact_sha256 = _device_digest(artifact["sha256"])
    if (
        not isinstance(artifact["fileName"], str)
        or not artifact["fileName"]
        or _integer(artifact["bytes"], minimum=1) < 1
        or artifact_sha256 != expected_android["deliverableArtifactSha256"]
    ):
        raise ValueError("Android qualification deliverable artifact identity mismatch")
    if not isinstance(report["artifacts"], list):
        raise ValueError("Android qualification report artifacts are invalid")
    deliverables = [
        item
        for item in report["artifacts"]
        if isinstance(item, dict) and item.get("role") == "deliverable"
    ]
    if len(deliverables) != 1:
        raise ValueError("Android qualification report must contain one deliverable artifact")
    deliverable = _expect(
        deliverables[0],
        {
            "role",
            "kind",
            "fileName",
            "bytes",
            "sha256",
            "applicationId",
            "versionCode",
            "versionName",
            "certificateSha256",
            "evidencePath",
            "buildConfiguration",
            "buildEvidence",
        },
    )
    if (
        deliverable["kind"] != "application"
        or deliverable["applicationId"] != expected_android['appBuild']['applicationId']
        or deliverable["fileName"] != artifact["fileName"]
        or deliverable["bytes"] != artifact["bytes"]
        or _device_digest(deliverable["sha256"]) != artifact_sha256
        or deliverable["versionCode"] != str(expected_android['appBuild']['versionCode'])
        or deliverable["versionName"] != expected_android['appBuild']['versionName']
        or _device_digest(deliverable["certificateSha256"])
        != expected_android['appBuild']['certificateSha256']
    ):
        raise ValueError("Android qualification deliverable evidence is inconsistent")
    evidence_path = _member(report_root, deliverable["evidencePath"])
    actual_sha256, actual_bytes = _sha256_file(evidence_path)
    if actual_sha256 != artifact_sha256 or actual_bytes != artifact["bytes"]:
        raise ValueError("Android qualification deliverable evidence hash mismatch")
    try:
        with zipfile.ZipFile(evidence_path) as apk:
            names = set(apk.namelist())
            if (
                "AndroidManifest.xml" not in names
                or not any(re.fullmatch(r"classes\d*\.dex", name) for name in names)
                or not any(name.startswith("lib/arm64-v8a/") for name in names)
                or any(
                    name.startswith("lib/")
                    and not name.startswith("lib/arm64-v8a/")
                    for name in names
                )
            ):
                raise ValueError("Android qualification deliverable APK layout is invalid")
    except (OSError, zipfile.BadZipFile) as error:
        raise ValueError("Android qualification deliverable is not an APK") from error
    return report


def _measurement_required_words(value, reference):
    from stemwise_engine.lyrics_tokenization import display_word_ranges
    if not isinstance(value, list) or len(value) != len(reference['occurrences']):
        raise ValueError('measurement requires every revised reference line and word')
    required = {}
    for raw, line in zip(value, reference['occurrences']):
        item = _expect(raw, {'occurrenceIdSha256', 'text', 'words'})
        if item['occurrenceIdSha256'] != line['occurrenceIdSha256'] or not isinstance(item['text'], str):
            raise ValueError('measurement revised line ownership differs')
        words = item['words']
        if not isinstance(words, list) or not words:
            raise ValueError('measurement revised words are missing')
        ranges, ids = [], set()
        for word in words:
            _expect(word, {'wordIdSha256', 'startUtf16', 'endUtf16'})
            identity = _digest(word['wordIdSha256'])
            if identity in ids:
                raise ValueError('measurement revised word ownership is duplicated')
            ids.add(identity)
            ranges.append((_integer(word['startUtf16']), _integer(word['endUtf16'], minimum=1)))
        if ranges != display_word_ranges(item['text']):
            raise ValueError('measurement must include all revised display words')
        by_id = {word['wordIdSha256']: word for word in words}
        for mapped in line['words']:
            owned = by_id.get(mapped['wordIdSha256'])
            if owned is None or any(owned[key] != mapped[key] for key in ('startUtf16', 'endUtf16')):
                raise ValueError('measurement mapped word does not belong to revised text')
        required[line['occurrenceIdSha256']] = item
    return required


def _measurement_export(root, descriptor, reference, result, required):
    if descriptor is None:
        return {'valid': False, 'reason': 'missing-complete-export'}, [], []
    item = _expect(descriptor, {'path', 'sha256'})
    path = _member(root, item['path'])
    if _sha256_file(path)[0] != _digest(item['sha256']):
        raise ValueError('measurement export hash mismatch')
    from stemwise_engine.original_delivery import read_bundle, _decoded_duration
    from stemwise_engine.packages import PackageValidationError
    try:
        document, _, original = read_bundle(path, {'jsonMaxBytes': MAX_JSON_BYTES,
            'pngMaxBytes': MAX_COVER_BYTES, 'artworkMaxPixels': MAX_COVER_PIXELS_PER_SIDE})
        if original['sha256'] != reference['recordingSha256']:
            raise ValueError('measurement export recording differs')
        from stemwise_engine.common import WorkerTemporaryDirectory
        with WorkerTemporaryDirectory(prefix='.measurement-', dir=root) as temporary:
            audio = Path(temporary) / original['name']
            with zipfile.ZipFile(path) as archive, archive.open(original['name']) as reader, audio.open('wb') as writer:
                shutil.copyfileobj(reader, writer, 1024 * 1024)
            if abs(_decoded_duration(audio) - reference['durationMs']) > 1:
                raise ValueError('measurement exported audio time base differs')
        if _sha256_file(path)[0] != item['sha256']:
            raise ValueError('measurement export changed during readback')
    except (ValueError, PackageValidationError, OSError, subprocess.SubprocessError) as error:
        return {'valid': False, 'reason': str(error)}, [], []
    exported = {'valid': True, 'reason': None, 'sha256': item['sha256'],
                'lyricsStatus': document['lyrics']['status']}
    tracks = [track for track in document['lyrics'].get('tracks', []) if track['kind'] == 'original']
    if not tracks:
        return exported, [], []
    track = tracks[0]
    exported['lyricsFormat'] = track['format']
    if track['format'] == 'plain':
        # Playable completion is independent of timing and companion availability.
        return exported, [], []
    try:
        supported, estimated = _measurement_timed_projection(track, reference, result, required)
    except ValueError as error:
        exported['lyricProjectionError'] = str(error)
        exported['unboundTimingClaims'] = sum(1 + len(line.get('words', [])) for line in track['lines'])
        return exported, [], []
    return exported, supported, estimated


def _measurement_timed_projection(track, reference, result, required):
    """Bind scored intervals to the actual validated public representation."""
    published = result['occurrences']
    by_id = {line['occurrenceIdSha256']: line for line in published}
    if (len(track['lines']) != len(required)
            or len(by_id) != len(published) or not set(by_id) <= set(required)
            or list(by_id) != [identity for identity in required if identity in by_id]
            or result['lexicalAuthoritySha256'] != reference['lexicalAuthoritySha256']
            or result['recordingSha256'] != reference['recordingSha256']):
        raise ValueError('measurement export authority or occurrences differ')
    supported, estimated = [], []
    for exported, (occurrence_id, owned) in zip(track['lines'], required.items()):
        if exported['text'] != owned['text']:
            raise ValueError('measurement exported wording differs')
        line_timing = (exported['timing'] if track['format'] == 'regional'
                       else {'status': 'supported', 'startMs': exported['startMs'], 'endMs': exported['endMs']})
        if line_timing['status'] == 'none':
            continue
        observed = by_id.get(occurrence_id)
        if observed is None:
            raise ValueError('measurement scored line is missing')
        if any(line_timing[key] != observed[key] for key in ('startMs', 'endMs')):
            raise ValueError('measurement export differs from the scored line')
        public_words = exported.get('words', [])
        if public_words and len(public_words) != len(owned['words']):
            raise ValueError('measurement word-bearing line is incomplete')
        scored_words = {word['wordIdSha256']: word for word in observed['words']}
        if not set(scored_words) <= {word['wordIdSha256'] for word in owned['words']}:
            raise ValueError('measurement scored word ownership differs')
        selected = {'supported': [], 'estimated': []}
        for word, identity in zip(public_words, owned['words']):
            if any(word[key] != identity[key] for key in ('startUtf16', 'endUtf16')):
                raise ValueError('measurement exported word ownership differs')
            timing = (word['timing'] if track['format'] == 'regional'
                      else {'status': 'supported', 'startMs': word['startMs'], 'endMs': word['endMs']})
            if timing['status'] != 'none':
                scored = scored_words.get(identity['wordIdSha256'])
                if scored is None or any(scored[key] != identity[key] for key in ('startUtf16', 'endUtf16')):
                    raise ValueError('measurement scored word is missing or ownership differs')
                if any(timing[key] != scored[key] for key in ('startMs', 'endMs')):
                    raise ValueError('measurement export differs from the scored word')
                selected[timing['status']].append(scored)
        # A word under an approximate line cannot gain stronger assurance from its DTO.
        if line_timing['status'] == 'estimated':
            selected['estimated'].extend(selected['supported'])
            selected['supported'] = []
        if line_timing['status'] == 'supported':
            supported.append({**observed, 'words': selected['supported']})
        if line_timing['status'] == 'estimated' or selected['estimated']:
            estimated.append({**observed, 'words': selected['estimated']})
    return supported, estimated


def joint_word_metrics(reference, result, required, *, complete_export):
    """Joint onset/offset credit over all revised words, including unscorable ones."""
    total = sum(len(line['words']) for line in required.values())
    mapped = sum(len(line['words']) for line in reference['occurrences'])
    correct = {tolerance: 0 for tolerance in (100, 200, 300, 500)}
    verified, starts, ends, line_correct, supported_line_correct, scored_words = 0, [], [], 0, 0, 0
    published = {line['occurrenceIdSha256']: line for line in result['occurrences']}
    identities_match = (result['recordingSha256'] == reference['recordingSha256']
                        and result['lexicalAuthoritySha256'] == reference['lexicalAuthoritySha256'])
    for expected_line in reference['occurrences']:
        line = published.get(expected_line['occurrenceIdSha256'])
        if line is None or not complete_export or not identities_match:
            continue
        correct_supported_line = (0 <= line['startMs'] < line['endMs'] <= reference['durationMs']
                                 and abs(line['startMs'] - expected_line['startMs']) <= 300
                                 and abs(line['endMs'] - expected_line['endMs']) <= 400)
        supported_line_correct += correct_supported_line
        correct_line = (correct_supported_line
                        and len(line['words']) == len(required[expected_line['occurrenceIdSha256']]['words'])
                        and len(expected_line['words']) == len(line['words']))
        words = {word['wordIdSha256']: word for word in line['words']}
        for expected in expected_line['words']:
            word = words.get(expected['wordIdSha256'])
            if word is None or any(word[key] != expected[key] for key in ('startUtf16', 'endUtf16')):
                correct_line = False
                continue
            start = word['startMs'] - expected['startMs']
            end = word['endMs'] - expected['endMs']
            scored_words += 1
            starts.append(start)
            ends.append(end)
            valid = 0 <= line['startMs'] <= word['startMs'] < word['endMs'] <= line['endMs'] <= reference['durationMs']
            verified += valid and abs(start) <= 300 and abs(end) <= 400
            correct_line = correct_line and valid and abs(start) <= 300 and abs(end) <= 400
            for tolerance in correct:
                correct[tolerance] += valid and abs(start) <= tolerance and abs(end) <= tolerance
        line_correct += correct_line
    return {'allReferenceWords': total, 'mappedReferenceWords': mapped,
        'referenceMappingCoverage': _rate(mapped, total), 'verifiedWords': verified,
        'verifiedWordCoverage': _rate(verified, total), 'mappedOnlyJointAccuracy': _rate(verified, mapped),
        'scoredPublishedWords': scored_words,
        'onsetErrorMs': _summary([abs(value) for value in starts]),
        'offsetErrorMs': _summary([abs(value) for value in ends]),
        'correctSupportedLines': supported_line_correct,
        'correctCompleteLines': line_correct, 'jointAccuracyMs': {
            str(tolerance): _rate(count, mapped) for tolerance, count in correct.items()},
        'signedOnsetBiasMs': statistics.mean(starts) if starts else None,
        'signedOffsetBiasMs': statistics.mean(ends) if ends else None}


def _measurement_report(profile, corpus, pairs, entries, root):
    cases = []
    for entry, (reference, result) in zip(corpus['cases'], pairs):
        required = _measurement_required_words(entry.get('requiredWords'), reference)
        exported, supported, estimated = _measurement_export(root,
            entries[reference['caseIdSha256']].get('assembledExport'), reference, result, required)
        supported_result = {**result, 'occurrences': supported}
        estimated_result = {**result, 'occurrences': estimated}
        metrics = joint_word_metrics(reference, supported_result, required, complete_export=exported['valid'])
        approximate = joint_word_metrics(reference, estimated_result, required, complete_export=exported['valid'])
        total = metrics['allReferenceWords']
        supported_count = sum(len(line['words']) for line in supported)
        estimated_count = sum(len(line['words']) for line in estimated)
        cases.append({'caseIdSha256': reference['caseIdSha256'], 'split': reference['split'],
            'recordingGroupSha256': reference['songVersionFamilySha256'], 'export': exported, **metrics,
            'supportedWordCoverage': _rate(supported_count, total),
            'supportedLineCoverage': _rate(len(supported), len(required)),
            'estimatedWordCoverage': _rate(estimated_count, total),
            'untimedWordCoverage': _rate(total - supported_count - estimated_count, total),
            'incorrectMappedSupportedWords': metrics['scoredPublishedWords'] - metrics['verifiedWords'],
            'unscorableSupportedWords': supported_count - metrics['scoredPublishedWords'],
            'estimatedWordErrors': {key: value for key, value in approximate.items()
                if key in {'jointAccuracyMs', 'onsetErrorMs', 'offsetErrorMs',
                           'signedOnsetBiasMs', 'signedOffsetBiasMs'}},
            'resources': result['resources']})
    held_cases = [case for case in cases if case['split'] == profile['splitPolicy']['scoredSplit']]
    groups = {}
    for case in held_cases:
        groups.setdefault(case['recordingGroupSha256'], []).append(case['verifiedWordCoverage'])
    return {'cases': cases, 'verifiedWordCoverage': statistics.mean(
        statistics.mean(values) for values in groups.values()) if groups else None,
        'playablePackageRate': _rate(sum(case['export']['valid'] for case in cases), len(cases)),
        'heldOutRecordingGroups': len({case['recordingGroupSha256'] for case in held_cases})}


def run_qualification(profile_path: Path, corpus_manifest_path: Path, results_manifest_path: Path, *, pc_only: bool = False) -> dict[str, Any]:
    return _evaluate_lyrics(profile_path, corpus_manifest_path, results_manifest_path, pc_only=pc_only)


def run_measurement(profile_path: Path, corpus_manifest_path: Path, results_manifest_path: Path) -> dict[str, Any]:
    """Measure a frozen PC comparison without enabling production qualification."""
    return _evaluate_lyrics(profile_path, corpus_manifest_path, results_manifest_path,
                            pc_only=True, measurement=True)


def _evaluate_lyrics(profile_path: Path, corpus_manifest_path: Path, results_manifest_path: Path,
                     *, pc_only: bool, measurement: bool = False) -> dict[str, Any]:
    profile_value, profile_raw = _read_json(profile_path)
    profile = _validate_profile(profile_value, pc_only=pc_only)
    if not measurement and profile["status"] != "frozen":
        raise ValueError("qualification profile is unbound")
    if measurement and profile['qualificationClaimsAllowed']:
        raise ValueError('measurement cannot allow qualification claims')
    profile_sha256 = _sha256(profile_raw)

    corpus_value, corpus_raw = _read_json(corpus_manifest_path)
    corpus = _expect(corpus_value, {"kind", "formatVersion", "annotationProtocol", "cases"})
    if corpus["kind"] != "stemwise.lyrics-qualification-corpus" or corpus["formatVersion"] != FORMAT_VERSION:
        raise ValueError("only lyrics qualification corpus v2 is supported")
    corpus_sha256 = _sha256(corpus_raw)
    if not measurement and corpus_sha256 != profile["corpusManifestSha256"]:
        raise ValueError("qualification corpus manifest hash mismatch")
    _, annotation_raw = _hashed_member(corpus_manifest_path.parent, corpus["annotationProtocol"])
    annotation_sha256 = _sha256(annotation_raw)
    if not measurement and annotation_sha256 != profile["annotationProtocolSha256"]:
        raise ValueError("qualification annotation protocol hash mismatch")

    results_value, results_raw = _read_json(results_manifest_path)
    results = _expect(
        results_value,
        {
            "kind",
            "formatVersion",
            "profileSha256",
            "corpusManifestSha256",
            "producerSystem",
            "hardwareProfile",
            "cases",
        },
        optional={"deviceQualificationReport"},
    )
    if results["kind"] != "stemwise.lyrics-qualification-results" or results["formatVersion"] != FORMAT_VERSION:
        raise ValueError("only lyrics qualification results v2 is supported")
    if _digest(results["profileSha256"]) != profile_sha256 or _digest(results["corpusManifestSha256"]) != corpus_sha256:
        raise ValueError("qualification results identity mismatch")
    expected_producer = results['producerSystem'] if measurement else profile['producerSystem']
    if measurement:
        _expect(expected_producer, profile['producerSystem'])
        for identity in expected_producer.values():
            _digest(identity)
    if results["producerSystem"] != expected_producer:
        raise ValueError("qualification results producer system mismatch")
    producer_system_sha256 = _canonical_object_sha256(expected_producer)
    hardware_profile, hardware_raw = _hashed_member(
        results_manifest_path.parent, results["hardwareProfile"]
    )
    hardware_profile = _expect(
        hardware_profile,
        {
            "kind",
            "formatVersion",
            "operatingSystem",
            "cpu",
            "memoryBytes",
            "gpus",
            "runtime",
        },
    )
    if (
        hardware_profile["kind"] != "stemwise.pc-hardware-profile"
        or hardware_profile["formatVersion"] != FORMAT_VERSION
    ):
        raise ValueError("PC qualification hardware profile is invalid")
    operating_system = _expect(
        hardware_profile["operatingSystem"], {"name", "release", "version", "machine"}
    )
    cpu = _expect(hardware_profile["cpu"], {"model", "logicalProcessorCount"})
    runtime = _expect(
        hardware_profile["runtime"],
        {"python", "implementation", "torch", "cuda", "cudnn"},
    )
    if any(
        not isinstance(value, str) or not value
        for value in (*operating_system.values(), cpu["model"], *runtime.values())
    ):
        raise ValueError("PC qualification hardware text identity is invalid")
    _integer(cpu["logicalProcessorCount"], minimum=1)
    _integer(hardware_profile["memoryBytes"], minimum=1)
    if not isinstance(hardware_profile["gpus"], list) or not hardware_profile["gpus"]:
        raise ValueError("PC qualification GPU inventory is invalid")
    for raw_gpu in hardware_profile["gpus"]:
        gpu = _expect(raw_gpu, {"name", "driver", "memoryBytes"})
        if any(not isinstance(gpu[name], str) or not gpu[name] for name in ("name", "driver")):
            raise ValueError("PC qualification GPU identity is invalid")
        _integer(gpu["memoryBytes"], minimum=1)
    hardware_sha256 = _sha256(hardware_raw)
    if not measurement and hardware_sha256 != profile["hardwareProfiles"]["pc"]:
        raise ValueError("qualification hardware profile identity mismatch")

    device_report: dict[str, Any] | None = None
    device_report_sha256: str | None = None
    if "deviceQualificationReport" in results and not pc_only:
        expected_device_profile = profile["hardwareProfiles"]["android"]
        if expected_device_profile is None:
            raise ValueError("device qualification report has no frozen Android device profile")
        device_report, device_report_raw = _hashed_member(
            results_manifest_path.parent,
            results["deviceQualificationReport"],
        )
        _validate_device_qualification_report(
            device_report,
            expected_device_profile,
            profile["androidQualification"],
            _member(results_manifest_path.parent, results["deviceQualificationReport"]["path"]).parent,
        )
        device_report_sha256 = _sha256(device_report_raw)

    if not isinstance(corpus["cases"], list) or not corpus["cases"]:
        raise ValueError("qualification corpus cases are invalid")
    if not isinstance(results["cases"], list) or len(results["cases"]) != len(corpus["cases"]):
        raise ValueError("qualification result cases do not match the corpus")
    result_entries: dict[str, dict[str, Any]] = {}
    for raw in results["cases"]:
        entry = _expect(raw, {"caseIdSha256", "result"},
                        optional={'assembledExport'} if measurement else ())
        case_id = _digest(entry["caseIdSha256"])
        if case_id in result_entries:
            raise ValueError("qualification result case identity is duplicated")
        result_entries[case_id] = entry

    pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
    corpus_ids: set[str] = set()
    split_groups = {
        name: {}
        for name in profile["splitPolicy"]["separateBy"]
    }
    observed_splits: set[str] = set()
    for raw in corpus["cases"]:
        entry = _expect(raw, {"caseIdSha256", "reference"},
                        optional={'requiredWords'} if measurement else ())
        case_id = _digest(entry["caseIdSha256"])
        if case_id in corpus_ids or case_id not in result_entries:
            raise ValueError("qualification corpus case identity is invalid")
        corpus_ids.add(case_id)
        reference_value, _ = _hashed_member(corpus_manifest_path.parent, entry["reference"])
        result_value, _ = _hashed_member(results_manifest_path.parent, result_entries[case_id]["result"])
        reference = _validate_reference(reference_value, profile)
        result = _validate_result(
            result_value, reference["durationMs"], producer_system_sha256, measurement=measurement
        )
        if reference["caseIdSha256"] != case_id or result["caseIdSha256"] != case_id:
            raise ValueError("qualification artifact case identity mismatch")
        split = reference["split"]
        observed_splits.add(split)
        for group_name, membership in split_groups.items():
            group_id = reference[group_name]
            previous = membership.setdefault(group_id, split)
            if previous != split:
                raise ValueError("qualification grouping crosses frozen splits")
        pairs.append((reference, result))
    if set(result_entries) != corpus_ids:
        raise ValueError("qualification results contain an extra case")
    if not measurement and observed_splits != set(profile["splitPolicy"]["values"]):
        raise ValueError("qualification corpus does not contain both frozen splits")

    scored_split = profile["splitPolicy"]["scoredSplit"]
    expected_strata = {
        (item["language"], item["script"], item["difficulty"])
        for item in profile["strata"]["inventory"]
    }
    actual_strata = {
        (
            reference["stratum"]["language"],
            reference["stratum"]["script"],
            reference["stratum"]["difficulty"],
        )
        for reference, _ in pairs
        if reference["split"] == scored_split
    }
    if actual_strata != expected_strata:
        raise ValueError("held-out corpus does not exactly cover the frozen strata inventory")

    if measurement:
        return {'kind': 'stemwise.lyrics-measurement-report', 'formatVersion': 1,
            'qualificationClaimsAllowed': False, 'qualified': False,
            'profileSha256': profile_sha256, 'producerSystemSha256': producer_system_sha256,
            'corpusManifestSha256': corpus_sha256, 'resultsManifestSha256': _sha256(results_raw),
            'annotationProtocolSha256': annotation_sha256, 'hardwareProfileSha256': hardware_sha256,
            **_measurement_report(profile, corpus, pairs, result_entries, results_manifest_path.parent)}

    development_accumulator = _new_accumulator()
    held_out_accumulator = _new_accumulator()
    stratum_accumulators: dict[tuple[str, str, str], dict[str, Any]] = {}
    for reference, result in pairs:
        accumulator = (
            held_out_accumulator
            if reference["split"] == scored_split
            else development_accumulator
        )
        _add_case(accumulator, reference, result, profile)
        if reference["split"] != scored_split:
            continue
        stratum = reference["stratum"]
        key = (stratum["language"], stratum["script"], stratum["difficulty"])
        accumulator = stratum_accumulators.setdefault(key, _new_accumulator())
        _add_case(accumulator, reference, result, profile)

    alpha = profile["confidenceBound"]["alpha"]
    development = _metrics(development_accumulator, alpha)
    held_out = _metrics(held_out_accumulator, alpha)
    gates = [] if measurement else _gate_results("held-out", held_out, profile)
    strata: list[dict[str, Any]] = []
    minimum_cases = profile["strata"]["minimumCaseCount"]
    for key in sorted(stratum_accumulators):
        metrics = _metrics(stratum_accumulators[key], alpha)
        scope = "held-out:stratum:" + "/".join(key)
        if not measurement:
            sample_passed = metrics["caseCount"] >= minimum_cases
            gates.append({"scope": scope, "metric": "caseCount", "operator": ">=", "threshold": minimum_cases, "actual": metrics["caseCount"], "passed": sample_passed})
        if not measurement and profile["gates"]["applyToEachStratum"]:
            gates.extend(_gate_results(scope, metrics, profile))
        strata.append({"language": key[0], "script": key[1], "difficulty": key[2], "metrics": metrics})

    pc_passed = all(item["passed"] for item in gates)
    device_passed = device_report is not None
    return {
        "kind": "stemwise.lyrics-qualification-report",
        "formatVersion": FORMAT_VERSION,
        "metricVersion": METRIC_VERSION,
        "metricCodeSha256": profile["metricCodeSha256"],
        "producerSystemSha256": producer_system_sha256,
        "pcPassed": pc_passed,
        "devicePassed": device_passed,
        "qualified": not pc_only and pc_passed and device_passed and profile["qualificationClaimsAllowed"],
        "profileSha256": profile_sha256,
        "corpusManifestSha256": corpus_sha256,
        "resultsManifestSha256": _sha256(results_raw),
        "annotationProtocolSha256": annotation_sha256,
        "pcHardwareProfileSha256": hardware_sha256,
        "deviceQualificationReportSha256": device_report_sha256,
        "inputArtifacts": {"references": len(pairs), "results": len(pairs), "allHashesVerified": True},
        "development": development,
        "heldOut": {"overall": held_out, "strata": strata},
        "gates": gates,
        "failedGateCount": sum(not item["passed"] for item in gates),
    }




def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Score text-free StemWise lyrics qualification artifacts")
    parser.add_argument("--profile", type=Path)
    parser.add_argument("--corpus-manifest", type=Path)
    parser.add_argument("--results-manifest", type=Path)
    parser.add_argument("--producer-system-identity", action="store_true")
    parser.add_argument("--pc-only", action="store_true", help="Score PC gates without requiring or claiming Android qualification")
    parser.add_argument('--measure', action='store_true',
                        help='Measure a frozen PC case inventory without qualification gates or authority')
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.producer_system_identity:
            identity = producer_system_identity(pc_only=True) if args.pc_only else producer_system_identity()
            print(json.dumps({
                **identity,
                "producerSystemSha256": _canonical_object_sha256(identity),
            }, ensure_ascii=True, sort_keys=True, allow_nan=False))
            return 0
        if None in (args.profile, args.corpus_manifest, args.results_manifest):
            raise ValueError("qualification input paths are required")
        report = (run_measurement(args.profile, args.corpus_manifest, args.results_manifest) if args.measure else
                  run_qualification(args.profile, args.corpus_manifest, args.results_manifest, pc_only=args.pc_only))
        print(json.dumps(report, ensure_ascii=True, sort_keys=True, allow_nan=False))
        passed = True if args.measure else report["pcPassed"] if args.pc_only else report["qualified"]
        return 0 if passed else 1
    except (OSError, ValueError, json.JSONDecodeError):
        print('{"kind":"stemwise.lyrics-qualification-error","formatVersion":2,"code":"qualification-input-invalid"}')
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
