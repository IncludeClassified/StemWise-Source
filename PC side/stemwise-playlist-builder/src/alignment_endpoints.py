"""Exact-content endpoint timing, witnesses and recovery; the attempt owns transitions."""
from __future__ import annotations
import re
import math
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from itertools import combinations
from pathlib import Path
from subprocess import TimeoutExpired
from typing import Any, Sequence
import stemwise_engine.worker_protocol as worker_protocol
import stemwise_engine.nemo_nfa_forced_alignment as nemo_nfa
import stemwise_engine.owsm_ctc_forced_alignment as owsm_ctc
from stemwise_engine.model_registry import model_assets
from stemwise_engine.common import WorkerRetirementError
from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline, ProviderStageDeadline
from stemwise_engine.lyrics_alignment import canonical_language
from stemwise_engine.lyrics_normalize import physical_lines
from stemwise_engine.lyric_content import require_workspace_content, require_timing_only
from stemwise_engine.workflow_records import LyricsContentBlocker
from stemwise_engine.alignment_phases import (EndpointAlignmentRequest, EndpointSelectionResult, EndpointServices,
    EndpointTimings, PairedEndpointResult, SegmentPlan, respects_reviewed_start,
    ordered_endpoint_candidate_path as _ordered_endpoint_candidate_path)

WORD_START_AGREEMENT_MS = 300


WORD_END_AGREEMENT_MS = 400


MIN_MMS_WORD_CONFIDENCE = 0.10


MIN_MMS_LINE_CONFIDENCE = 0.10


_WORD_TIMING_RECOVERY_REASONS = frozenset({
    "word-start-disagreement", "word-end-disagreement", "word-interval-disjoint",
    "low-forced-word-support", "missing-independent-word-corroboration",
    "independent-endpoint-consensus-line-only",
})


WAV2VEC2_ROUTE = "wav2vec2-ctc-forced-on-mix"


WAV2VEC2_FAMILY = "language-wav2vec2"


OWSM_CTC_FAMILY = "owsm-ctc"


NEMO_NFA_FAMILY = "nemo-nfa"


ENDPOINT_PUBLISHER_PRIORITY = (
    "mms-fa",
    WAV2VEC2_FAMILY,
    OWSM_CTC_FAMILY,
    NEMO_NFA_FAMILY,
)


UNPROMPTED_WHISPER_FAMILY = "unprompted-faster-whisper"


WORKER_SEGMENT_BOUNDARY_GUARD_MS = 250


ENDPOINT_MMS_ROUTES = (
    "htdemucs-vocals",
    "kimberley-jensen-roformer-vocals",
    "sucial-v2-roformer-dry-vocals",
)


WAV2VEC2_ROUTES_BY_AUDIO_VIEW = {
    "mix": WAV2VEC2_ROUTE,
    **{
        audio_view_id: f"wav2vec2-ctc-forced-on-{audio_view_id}"
        for audio_view_id in ENDPOINT_MMS_ROUTES
    },
}


FREE_STRUCTURE_WITNESS_FAMILIES = (
    "youtube-caption",
    "unprompted-faster-whisper",
)


ROFORMER_ROUTES = model_assets('lyrics_align_pc')['ROFORMER_ROUTES']


ESPEAK_LANGUAGE_ALIASES = {
    "en": "en-us",
    "fr": "fr-fr",
    "no": "nb",
    "zh": "cmn",
}


def _component_phonemizer_languages(
    original: dict[str, Any],
    display_word_lines: list[list[tuple[int, int, str]]],
) -> list[list[str]] | None:
    """Bind each mixed-script display word to one declared component language."""

    from stemwise_engine.spoken_targets import attached
    plan = attached(original)
    if plan is not None:
        sources = [index for index, line in enumerate(physical_lines(original['text'])) if line.strip()]
        languages = plan.language_lines(sources)
        if len(languages) != len(display_word_lines) or any(len(a) != len(b) for a, b in zip(languages, display_word_lines)):
            raise LyricsContentBlocker('pronunciation-support-unavailable')
        return languages

    concrete = canonical_language(original.get("language"))
    if concrete is not None:
        language = ESPEAK_LANGUAGE_ALIASES.get(concrete, concrete)
        return [[language for _ in words] for words in display_word_lines]
    components = list(
        dict.fromkeys(
            language
            for value in original.get("componentLanguages", [])
            if (language := canonical_language(value)) is not None
        )
    )
    if len(components) < 2:
        return None
    from stemwise_engine.model_registry import declared_script_owners
    owners = declared_script_owners(components)
    if owners is None or any(len(languages) != 1 for languages in owners.values()):
        return None
    by_script = {script: ESPEAK_LANGUAGE_ALIASES.get(next(iter(languages)), next(iter(languages)))
                 for script, languages in owners.items()}
    from stemwise_engine.lyrics_qa import _major_script

    result: list[list[str]] = []
    for words in display_word_lines:
        word_scripts = [next((_major_script(character) for character in surface
                              if unicodedata.category(character).startswith('L')), None)
                        for _, _, surface in words]
        line_languages = {
            by_script[script] for script in word_scripts if script in by_script
        }
        if any(script is not None and script not in by_script for script in word_scripts):
            return None
        if any(script is None for script in word_scripts) and len(line_languages) != 1:
            return None
        fallback = next(iter(line_languages), None)
        languages = [by_script.get(script, fallback) for script in word_scripts]
        if any(language is None for language in languages):
            return None
        result.append([str(language) for language in languages])
    return result


def _validate_forced_alignment_segments(
    segments: list[dict[str, int]], line_count: int, duration_ms: int
) -> None:
    required = {
        "segmentIndex",
        "startLineIndex",
        "endLineIndex",
        "windowStartMs",
        "windowEndMs",
    }
    if line_count <= 0 or duration_ms <= 0 or not segments:
        raise ValueError("forced-alignment segment plan is empty")
    next_line = 0
    previous_window_end = -1
    for segment_index, segment in enumerate(segments):
        if (
            not isinstance(segment, dict)
            or set(segment) != required
            or any(type(segment[key]) is not int for key in required)
            or segment["segmentIndex"] != segment_index
            or segment["startLineIndex"] != next_line
            or segment["endLineIndex"] <= segment["startLineIndex"]
            or segment["endLineIndex"] > line_count
            or not 0
            <= segment["windowStartMs"]
            < segment["windowEndMs"]
            <= duration_ms
            or segment["windowStartMs"] < previous_window_end
        ):
            raise ValueError("forced-alignment segment plan is malformed")
        next_line = segment["endLineIndex"]
        previous_window_end = segment["windowEndMs"]
    if next_line != line_count:
        raise ValueError("forced-alignment segments do not cover every line")


def _ordered_verifier_fallback_candidates(
    primary_summary: dict[str, Any],
    primary: tuple[dict[str, Any] | None, ...],
    fallback: tuple[dict[str, Any] | None, ...],
    source_physical_indices: tuple[int, ...],
) -> tuple[dict[str, Any] | None, ...]:
    """Preserve primary ownership only when the combined stream stays ordered."""

    occurrences = primary_summary.get("occurrenceCandidates")
    if (
        not isinstance(occurrences, list)
        or len(occurrences) != len(primary)
        or len(primary) != len(fallback)
        or len(fallback) != len(source_physical_indices)
    ):
        raise ValueError("endpoint verifier fallback inputs disagree")
    if len(set(source_physical_indices)) != len(source_physical_indices):
        raise ValueError("performed lyric occurrences must have distinct source identities")
    output: list[dict[str, Any] | None] = []
    for occurrence, first, second in zip(
        occurrences, primary, fallback
    ):
        decision = occurrence.get("publishDecision")
        if not isinstance(decision, dict):
            raise ValueError("endpoint verifier fallback decision is malformed")
        code = str(decision.get("code", ""))
        use_fallback = (
            decision.get("result") == "unresolved"
            and (
                code in {"line-start-disagreement", "line-end-disagreement", "low-forced-line-support"}
                or code.startswith("missing-")
            )
        ) or (decision.get("result") == "line-only" and code in _WORD_TIMING_RECOVERY_REASONS)
        output.append(second if use_fallback else first)

    def ordered(candidates: Sequence[dict[str, Any] | None]) -> bool:
        present = [candidate for candidate in candidates if candidate is not None]
        return all(
            left["endMs"] <= right["startMs"]
            for left, right in zip(present, present[1:])
        )

    if not ordered(output):
        if not ordered(fallback):
            raise ValueError("fallback endpoint verifier is not globally ordered")
        return fallback
    return tuple(output)


def _free_structure_witness_family(label: str) -> str | None:
    if label in {"youtube-caption:manual", "youtube-caption:automatic"}:
        return "youtube-caption"
    if label.startswith("faster-whisper-precision-unprompted:") and re.fullmatch(
        r"(?:[a-z]{2,3}|auto)", label.split(":", 1)[1]
    ):
        return "unprompted-faster-whisper"
    return None


def _independent_endpoint_inputs(original, mapping, display_word_lines, supported_languages, *, subset=False):
    from stemwise_engine.spoken_targets import attached
    # A single-route worker cannot consume another reviewed language's tags.
    subset = subset or attached(original) is not None
    occurrences = mapping["performedOccurrences"]
    if len(occurrences) != len(display_word_lines):
        raise ValueError("independent endpoint occurrence count disagrees")
    languages = _component_phonemizer_languages(original, display_word_lines)
    if languages is None:
        return {}
    groups = {}
    for index, row in enumerate(languages):
        concrete = {canonical_language(value.split("-", 1)[0]) for value in row}
        if len(concrete) == 1 and next(iter(concrete)) in supported_languages:
            groups.setdefault(next(iter(concrete)), []).append(index)
    return {
        language: (indices, {**mapping, "performedOccurrences": [
            {**occurrences[index], "performedIndex": local_index}
            for local_index, index in enumerate(indices)
        ]}, [display_word_lines[index] for index in indices]) if subset
        else (indices, mapping, display_word_lines)
        for language, indices in groups.items()
    }


def _validate_occurrence_identity(
    source_physical_indices: tuple[int, ...],
    segments: list[dict[str, int]] | None = None,
) -> None:
    line_segments: dict[int, int] = {}
    if segments is not None:
        for segment in segments:
            for index in range(segment["startLineIndex"], segment["endLineIndex"]):
                if index in line_segments:
                    raise ValueError("occurrence identity segments overlap")
                line_segments[index] = segment["segmentIndex"]
        if set(line_segments) != set(range(len(source_physical_indices))):
            raise ValueError("occurrence identity segments do not cover every line")
    if any(type(source) is not int or source < 0 for source in source_physical_indices):
        raise ValueError("performed lyric occurrence has an invalid source identity")
    if len(set(source_physical_indices)) != len(source_physical_indices):
        raise ValueError("performed lyric occurrences must have distinct source identities")


def _verifier_safe_boundaries(
    line_count: int,
    duration_ms: int,
    *family_lines: tuple[dict[str, Any] | None, ...],
) -> dict[int, int]:
    if (
        line_count <= 1
        or duration_ms <= 0
        or not family_lines
        or any(len(lines) != line_count for lines in family_lines)
    ):
        return {}
    boundaries: dict[int, int] = {}
    for boundary in range(1, line_count):
        left_ends: list[int] = []
        right_starts: list[int] = []
        for lines in family_lines:
            left = lines[boundary - 1]
            right = lines[boundary]
            left_end = left.get("endMs") if isinstance(left, dict) else None
            right_start = right.get("startMs") if isinstance(right, dict) else None
            if (
                type(left_end) is not int
                or type(right_start) is not int
                or not 0 <= left_end < right_start <= duration_ms
            ):
                break
            left_ends.append(left_end)
            right_starts.append(right_start)
        if len(left_ends) != len(family_lines):
            continue
        shared_left = max(left_ends)
        shared_right = min(right_starts)
        if shared_right - shared_left < 2 * WORKER_SEGMENT_BOUNDARY_GUARD_MS:
            continue
        cut = (shared_left + shared_right) // 2
        if (
            all(
                cut - left_end >= WORKER_SEGMENT_BOUNDARY_GUARD_MS
                for left_end in left_ends
            )
            and all(
                right_start - cut >= WORKER_SEGMENT_BOUNDARY_GUARD_MS
                for right_start in right_starts
            )
        ):
            boundaries[boundary] = cut
    return boundaries


def _build_bootstrap_segments(
    line_count: int,
    duration_ms: int,
    *family_lines: tuple[dict[str, Any] | None, ...],
    qualified_occurrences: list[dict[str, Any]] | None = None,
) -> list[dict[str, int]] | None:
    """Freeze every fence whose gap is safe in every endpoint family."""

    if (
        line_count <= 1
        or duration_ms <= 0
        or not family_lines
        or any(len(lines) != line_count for lines in family_lines)
    ):
        return None
    available = _verifier_safe_boundaries(
        line_count, duration_ms, *family_lines
    )
    if qualified_occurrences is not None:
        if len(qualified_occurrences) != line_count:
            raise ValueError("bootstrap qualification count differs")
        for boundary, (left, right) in enumerate(zip(qualified_occurrences, qualified_occurrences[1:]), 1):
            supporting = set()
            for occurrence in (left, right):
                group = occurrence.get("supportingEndpointFamilies", [])
                if occurrence["publishDecision"]["result"] != "unresolved" and len(group) >= 2:
                    supporting.update(group)
            if len(supporting) < 2 or not supporting.issubset(ENDPOINT_PUBLISHER_PRIORITY):
                continue
            if any(f not in c["familyCandidates"] for c in (left, right) for f in supporting):
                continue
            # A qualified consensus may exclude the misplaced MMS hypothesis.
            # Every supporting family's gap must still admit the guarded cut.
            gap = _verifier_safe_boundaries(2, duration_ms, *(
                (left["familyCandidates"][f], right["familyCandidates"][f]) for f in sorted(supporting)))
            if 1 in gap:
                available.setdefault(boundary, gap[1])
    if not available:
        return None

    segments: list[dict[str, int]] = []
    start_line = start_ms = 0
    for end_line in sorted(available):
        end_ms = available[end_line]
        segments.append(
            {
                "segmentIndex": len(segments),
                "startLineIndex": start_line,
                "endLineIndex": end_line,
                "windowStartMs": start_ms,
                "windowEndMs": end_ms,
            }
        )
        start_line, start_ms = end_line, end_ms
    segments.append(
        {
            "segmentIndex": len(segments),
            "startLineIndex": start_line,
            "endLineIndex": line_count,
            "windowStartMs": start_ms,
            "windowEndMs": duration_ms,
        }
    )
    try:
        _validate_forced_alignment_segments(segments, line_count, duration_ms)
    except ValueError:
        return None
    return segments


def _merge_short_bootstrap_segment(
    segments: list[dict[str, int]], segment_index: int, *, boundary: str | None = None
) -> list[dict[str, int]]:
    """Remove the failing fence; a short window without an edge prefers the right."""

    if len(segments) < 2:
        raise ValueError("the final Wav2Vec2 segment cannot be widened")
    if not 0 <= segment_index < len(segments):
        raise ValueError("the failed Wav2Vec2 segment index is invalid")
    if boundary not in (None, "start", "end"):
        raise ValueError("the failed Wav2Vec2 boundary is invalid")
    if (boundary == "start" and segment_index == 0
            or boundary == "end" and segment_index == len(segments) - 1):
        raise ValueError("an exterior Wav2Vec2 boundary cannot be widened")
    left_index = (segment_index - 1 if boundary == "start" or segment_index == len(segments) - 1
                  else segment_index)
    left = segments[left_index]
    right = segments[left_index + 1]
    merged = [dict(segment) for segment in segments]
    merged[left_index : left_index + 2] = [
        {
            "segmentIndex": left_index,
            "startLineIndex": left["startLineIndex"],
            "endLineIndex": right["endLineIndex"],
            "windowStartMs": left["windowStartMs"],
            "windowEndMs": right["windowEndMs"],
        }
    ]
    for index, segment in enumerate(merged):
        segment["segmentIndex"] = index
    _validate_forced_alignment_segments(
        merged, merged[-1]["endLineIndex"], merged[-1]["windowEndMs"]
    )
    return merged


def _candidate_audit(
    candidate: dict[str, Any], *, include_confidence: bool = False
) -> dict[str, Any]:
    output = {
        "route": candidate["route"],
        "startMs": candidate["startMs"],
        "endMs": candidate["endMs"],
    }
    if include_confidence or "confidence" in candidate:
        output["confidence"] = candidate["confidence"]
    output.update({key: candidate[key] for key in (
        "endpointSemantics", "ctcLogScore", "acousticTokenCount") if key in candidate})
    words = candidate.get("words")
    if words is not None:
        output["words"] = [
            {
                "occurrenceWordId": word.get("wordId", word.get("occurrenceWordId")),
                **(
                    {
                        "startUtf16": word["startUtf16"],
                        "endUtf16": word["endUtf16"],
                    }
                    if "startUtf16" in word
                    else {}
                ),
                "startMs": word["startMs"],
                "endMs": word["endMs"],
                **{key: word[key] for key in ("confidence", "acousticTokenCount", "ctcLogScore")
                   if key in word},
            }
            for word in words
        ]
    return output


def _valid_endpoint_interval(candidate: dict[str, Any]) -> bool:
    values = (candidate.get("startMs"), candidate.get("endMs"))
    return (all(type(value) in (int, float) and math.isfinite(value) for value in values)
            and 0 <= values[0] < values[1])


def _valid_endpoint_sequence(candidate: dict[str, Any]) -> bool:
    if not _valid_endpoint_interval(candidate):
        return False
    previous_end = candidate["startMs"]
    for word in candidate.get("words") or ():
        if (not isinstance(word, dict) or not _valid_endpoint_interval(word)
                or word["startMs"] < previous_end or word["endMs"] > candidate["endMs"]):
            return False
        previous_end = word["endMs"]
    return True


def _endpoint_agreement_reason(left: dict, right: dict, unit: str) -> str | None:
    if not _valid_endpoint_interval(left) or not _valid_endpoint_interval(right):
        return f"invalid-{unit}-interval"
    if abs(left["startMs"]-right["startMs"]) > WORD_START_AGREEMENT_MS:
        return f"{unit}-start-disagreement"
    if abs(left["endMs"]-right["endMs"]) > WORD_END_AGREEMENT_MS:
        return f"{unit}-end-disagreement"
    if max(left["startMs"], right["startMs"]) >= min(left["endMs"], right["endMs"]):
        return f"{unit}-interval-disjoint"
    return None


def _observed_confidence_supported(record: dict) -> bool:
    value = record.get("confidence")
    return ("confidence" not in record or (type(value) in (int, float)
            and math.isfinite(value) and 0 < value <= 1))


def _mms_word_supported(word: dict) -> bool:
    confidence = word.get("confidence")
    return (type(confidence) in (int, float) and math.isfinite(confidence)
            and confidence >= MIN_MMS_WORD_CONFIDENCE
            and type(word.get("acousticTokenCount")) is int and word["acousticTokenCount"] > 0)


def _endpoint_boundary_supported(candidate: dict, family: str, boundary: str) -> bool:
    """Support for a current startMs/endMs claim, not a model-accuracy flag."""
    if family == OWSM_CTC_FAMILY and boundary == "endMs":
        return False  # Lexical BPE exit does not measure the end of sustained singing.
    words = candidate.get("words")
    if not words:
        return family != "mms-fa" and _observed_confidence_supported(candidate)
    word = words[0 if boundary == "startMs" else -1]
    return _mms_word_supported(word) if family == "mms-fa" else _observed_confidence_supported(word)


def _line_supported(candidate: dict, family: str) -> bool:
    confidence = candidate.get("confidence")
    if family == "mms-fa":
        if (type(confidence) not in (int, float) or not math.isfinite(confidence)
                or confidence < MIN_MMS_LINE_CONFIDENCE):
            return False
    elif not _observed_confidence_supported(candidate):
        return False
    return all(_endpoint_boundary_supported(candidate, family, boundary) for boundary in ("startMs", "endMs"))


def _applicable_endpoint_candidates(occurrence: dict) -> dict[str, dict]:
    families = set(ENDPOINT_PUBLISHER_PRIORITY) | set(occurrence.get("endpointWitnessFamilies", ()))
    return {family: candidate for family, candidate in occurrence["familyCandidates"].items()
            if family in families and _valid_endpoint_sequence(candidate)}


def _supported_endpoint_agreement_reason(left: dict, left_family: str, right: dict,
                                         right_family: str, unit: str) -> str | None:
    supported = {(side, boundary): _endpoint_boundary_supported(candidate, family, boundary)
                 for side, candidate, family in (("left", left, left_family), ("right", right, right_family))
                 for boundary in ("startMs", "endMs")}
    for boundary, limit, label in (("startMs", WORD_START_AGREEMENT_MS, "start"),
                                    ("endMs", WORD_END_AGREEMENT_MS, "end")):
        if (supported["left", boundary] and supported["right", boundary]
                and abs(left[boundary]-right[boundary]) > limit):
            return f"{unit}-{label}-disagreement"
    if (supported["left", "endMs"] and supported["right", "startMs"]
            and right["startMs"] >= left["endMs"]) or (
            supported["right", "endMs"] and supported["left", "startMs"]
            and left["startMs"] >= right["endMs"]):
        return f"{unit}-interval-disjoint"
    return None


def _eligible_endpoint_candidates(occurrence: dict) -> dict[str, dict]:
    return {family: candidate for family, candidate in _applicable_endpoint_candidates(occurrence).items()
            if _line_supported(candidate, family)}


def _word_endpoint_contradiction(publisher: dict, candidate: dict, family: str, *, publisher_family: str) -> bool:
    words, selected = candidate.get("words", ()), publisher.get("words", ())
    if (not words or len(words) != len(selected) or not _valid_endpoint_sequence(candidate)
            or any(not left.get("occurrenceWordId", left.get("wordId"))
                   or left.get("occurrenceWordId", left.get("wordId")) != right.get("occurrenceWordId", right.get("wordId"))
                   for left, right in zip(selected, words))):
        return False
    return any(_supported_endpoint_agreement_reason({**left, "words": [left]}, publisher_family,
               {**right, "words": [right]}, family, "word") is not None
               for left, right in zip(selected, words))


def _complete_word_support(candidate: dict, family: str) -> bool:
    return (bool(candidate.get("words")) and _line_supported(candidate, family)
            and all(_mms_word_supported(word) if family == "mms-fa"
                    else _observed_confidence_supported(word) for word in candidate["words"]))


def _opening_retry_plan(forced, occurrence, owner):
    """Propose a new search edge only for a detached first word in the instrumental intro."""
    if (occurrence.get('publishDecision', {}).get('result') != 'unresolved'
            or occurrence.get('performedIndex') != 0 or owner.get('performedIndex') != 0):
        return None
    line = forced['lines'][0]
    if (occurrence['occurrenceId'] != owner['occurrenceId'] or line['lineId'] != owner['occurrenceId']
            or occurrence['sourcePhysicalIndex'] != owner['sourcePhysicalIndex']
            or line['sourcePhysicalIndex'] != owner['sourcePhysicalIndex']):
        return None
    ids = [word['occurrenceWordId'] for word in owner['words']]
    words = line['words']
    segments = forced.get('resolvedSegments')
    if (len(ids) < 2 or ids != [word['wordId'] for word in words] or not segments
            or segments[0]['startLineIndex'] != 0 or segments[0]['windowStartMs'] != 0):
        return None
    starts = {}
    following = [words[1]['startMs']]
    for family, evidence in _applicable_endpoint_candidates(occurrence).items():
        if family not in (WAV2VEC2_FAMILY, OWSM_CTC_FAMILY, NEMO_NFA_FAMILY):
            continue
        observations = evidence.get('words')
        if not observations:
            continue
        if ids != [word.get('occurrenceWordId') for word in observations]:
            return None
        if not all(_observed_confidence_supported(word) for word in observations[:2]):
            continue
        starts[family] = observations[0]['startMs']
        following.append(observations[1]['startMs'])
    if len(starts) < 2:
        return None
    earliest, latest = min(starts.values()), max(starts.values())
    edge = earliest - WORD_START_AGREEMENT_MS
    if (latest - earliest > WORD_START_AGREEMENT_MS or words[0]['endMs'] >= edge
            or max(following) - min(following) > WORD_START_AGREEMENT_MS
            or latest >= min(following) or max(following) >= segments[0]['windowEndMs']
            or any(not respects_reviewed_start(occurrence, {'startMs': t}, WORD_START_AGREEMENT_MS)
                   for t in starts.values())):
        return None
    return [{**segments[0], 'windowStartMs': edge}, *segments[1:]], starts


def _same_following_words(original, retry):
    if len(original) != len(retry):
        return False
    for index, (before, after) in enumerate(zip(original, retry)):
        if (before['lineId'] != after['lineId'] or before['sourcePhysicalIndex'] != after['sourcePhysicalIndex']
                or len(before['words']) != len(after['words'])):
            return False
        for word_index, (old, new) in enumerate(zip(before['words'], after['words'])):
            if old['wordId'] != new['wordId']:
                return False
            if (index, word_index) != (0, 0) and (old['startMs'], old['endMs']) != (new['startMs'], new['endMs']):
                return False
    return True


def _qualified_word_candidates(occurrence: dict) -> dict[str, dict]:
    """Keep complete numerical observations; weak forced words cannot corroborate."""
    return {family: candidate for family, candidate in _eligible_endpoint_candidates(occurrence).items()
            if _complete_word_support(candidate, family)}


def _upgrade_endpoint_words(before: dict, after: dict, timing: dict) -> tuple[dict, dict] | None:
    """Upgrade the same occurrence without discarding qualified contrary words."""
    if (before["publishDecision"]["result"] != "line-only"
            or before["publishDecision"].get("code") not in _WORD_TIMING_RECOVERY_REASONS
            or before["publishDecision"].get("code") == "qualified-word-contradiction"
            or before.get("qualifiedEndpointContradictions") or before.get("qualifiedWordContradictions")):
        return None
    candidates = _qualified_word_candidates(after)
    publisher_family = after.get("selectedPublisherFamily")
    if publisher_family not in candidates or not respects_reviewed_start(before, timing, WORD_START_AGREEMENT_MS):
        return None
    publisher = candidates[publisher_family]
    previous = {family: candidate for family, candidate in _applicable_endpoint_candidates(before).items()
                if candidate.get("words")}
    incoming = {family: candidate for family, candidate in _applicable_endpoint_candidates(after).items()
                if candidate.get("words")}
    for candidate in previous.values():
        old_ids = [word.get("occurrenceWordId", word.get("wordId")) for word in candidate["words"]]
        new_ids = [word.get("occurrenceWordId", word.get("wordId")) for word in publisher["words"]]
        if old_ids != new_ids:
            return None
    if any(_word_endpoint_contradiction(publisher, candidate, family, publisher_family=publisher_family)
           for group in (previous, incoming) for family, candidate in group.items()):
        return ({key: value for key, value in timing.items() if key != "words"},
            {**before, "familyCandidates": {**before["familyCandidates"], **after["familyCandidates"]},
             "publishDecision": {"result": "line-only", "code": "qualified-word-contradiction"},
             "qualifiedWordContradictions": {"previousCandidates": previous, "incomingCandidates": incoming}})
    words, reason = assess_complete_word_sequence(publisher, candidates, publisher_family=publisher_family)
    if reason is not None:
        return None
    if any(word["startMs"] < timing["startMs"] or word["endMs"] > timing["endMs"] for word in words):
        return None
    return ({"startMs": timing["startMs"], "endMs": timing["endMs"], "words": words},
        {**after, "publishDecision": {"result": "words"},
         "supportingEndpointFamilies": list(dict.fromkeys([
             *after.get("supportingEndpointFamilies", ()), *candidates])),
         "supportingWordEndpointFamilies": list(candidates)})


def _reassess_endpoint_update(before: dict, after: dict, timing: dict) -> tuple[dict, dict] | None:
    """A failed optional attempt preserves authority; an applicable conflict does not."""
    if before["publishDecision"]["result"] == "unresolved":
        return None
    publisher = before["familyCandidates"].get(before.get("selectedPublisherFamily"))
    if publisher is None:
        return None
    candidates = _applicable_endpoint_candidates(after)
    line_conflict = any(_supported_endpoint_agreement_reason(publisher, before['selectedPublisherFamily'],
                        candidate, family, "line") is not None
                        for family, candidate in candidates.items())
    word_conflict = (before["publishDecision"]["result"] == "words"
                     and any(_word_endpoint_contradiction(publisher, candidate, family,
                             publisher_family=before['selectedPublisherFamily'])
                             for family, candidate in candidates.items()))
    if not line_conflict and not word_conflict:
        return None
    merged = {**before, "familyCandidates": {**before["familyCandidates"], **candidates},
        "publishDecision": {"result": "unresolved" if line_conflict else "line-only",
            "code": "qualified-endpoint-contradiction" if line_conflict else "qualified-word-contradiction"}}
    if line_conflict:
        merged["qualifiedEndpointContradictions"] = {"previousPublisherFamily": before['selectedPublisherFamily'],
            "previousPublisher": publisher,
            "incomingCandidates": candidates}
    if word_conflict:
        merged["qualifiedWordContradictions"] = {"previousPublisher": publisher,
            "incomingCandidates": {family: candidate for family, candidate in candidates.items()
                                   if candidate.get("words")}}
    if line_conflict:
        merged.pop("selectedPublisherFamily", None)
    merged.pop("supportingEndpointFamilies", None)
    merged.pop("supportingWordEndpointFamilies", None)
    return {key: value for key, value in timing.items() if key != "words"}, merged


def assess_complete_word_sequence(publisher: dict, corroborators: dict[str, dict], *,
                                  publisher_family: str) -> tuple[list[dict] | None, str | None]:
    """Assess one complete sequence after operation/content/transform eligibility.

    Callers retain their actual producer/frontend/occurrence predicates. This
    structural gate grants no new producer, language or endpoint semantics authority.
    """
    independent = {family: candidate for family, candidate in corroborators.items()
                   if family != publisher_family
                   and not ("qwen" in family.lower() and "qwen" in publisher_family.lower())}
    if not independent:
        return None, "missing-independent-word-corroboration"
    sequences = [(publisher_family, publisher), *independent.items()]
    occurrence_ids = {candidate.get("lineId", candidate.get("occurrenceId"))
                      for _, candidate in sequences
                      if candidate.get("lineId", candidate.get("occurrenceId")) is not None}
    if len(occurrence_ids) > 1:
        return None, "stable-occurrence-id-disagreement"
    if any(not _valid_endpoint_sequence(candidate) for _, candidate in sequences):
        return None, "invalid-word-interval"
    for (left_family, left), (right_family, right) in combinations(sequences, 2):
        reason = _supported_endpoint_agreement_reason(left, left_family, right, right_family, "line")
        if reason is not None:
            return None, reason
    if any(_word_endpoint_contradiction(left, right, right_family, publisher_family=left_family)
           for (left_family, left), (right_family, right) in combinations(sequences, 2)):
        return None, "qualified-word-contradiction"
    qualified = {family: candidate for family, candidate in independent.items()
                 if _complete_word_support(candidate, family)}
    if not qualified:
        if any(candidate.get("words") and _line_supported(candidate, family)
               for family, candidate in independent.items()):
            return None, "low-forced-word-support"
        return None, "missing-independent-word-corroboration"
    sequences = [(publisher_family, publisher), *qualified.items()]
    for family, candidate in sequences:
        if not _line_supported(candidate, family):
            return None, "low-forced-line-support"
    for (_, left), (_, right) in combinations(sequences, 2):
        reason = _endpoint_agreement_reason(left, right, "line")
        if reason:
            return None, reason
    words = publisher.get("words")
    if not words:
        return None, f"incomplete-{publisher_family}-word-map"
    if not any(candidate.get("words") and all("startUtf16" in word and "endUtf16" in word
               for word in candidate["words"]) for _, candidate in sequences):
        return None, "incomplete-display-word-map"
    for family, candidate in sequences:
        other_words = candidate.get("words")
        if other_words is None:
            return None, f"incomplete-{family}-word-map"
        if len(words) != len(other_words):
            return None, "word-count-disagreement"
        previous_end = candidate["startMs"]
        for word in other_words:
            if (not _valid_endpoint_interval(word) or word["startMs"] < previous_end
                    or word["endMs"] > candidate["endMs"]):
                return None, "invalid-word-interval"
            previous_end = word["endMs"]
            if not (_mms_word_supported(word) if family == "mms-fa"
                    else _observed_confidence_supported(word)):
                return None, "low-forced-word-support"
    public_words = []
    seen_ids = set()
    for index, word in enumerate(words):
        units = [candidate["words"][index] for _, candidate in sequences]
        ids = [unit.get("wordId", unit.get("occurrenceWordId")) for unit in units]
        if not ids[0] or ids[0] in seen_ids or any(identity != ids[0] for identity in ids):
            return None, "stable-word-id-disagreement"
        seen_ids.add(ids[0])
        for left, right in combinations(units, 2):
            reason = _endpoint_agreement_reason(left, right, "word")
            if reason:
                return None, reason
        if any(("startUtf16" in unit) != ("endUtf16" in unit) for unit in units):
            return None, "incomplete-display-word-map"
        ranges = [(unit["startUtf16"], unit["endUtf16"]) for unit in units if "startUtf16" in unit]
        if not ranges or any(bounds != ranges[0] for bounds in ranges):
            return None, "incomplete-display-word-map"
        start, end = ranges[0]
        if type(start) is not int or type(end) is not int or not 0 <= start < end:
            return None, "incomplete-display-word-map"
        if public_words and public_words[-1]["endUtf16"] > start:
            return None, "incomplete-display-word-map"
        public_words.append({"startUtf16": start, "endUtf16": end,
                             "startMs": word["startMs"], "endMs": word["endMs"]})
    return public_words, None


def _summarize_endpoint_occurrences(
    occurrences: list[dict[str, Any]],
    *,
    reviewed_lrc_start_evidence_count: int,
) -> dict[str, Any]:
    unresolved: Counter[str] = Counter()
    line_only: Counter[str] = Counter()
    family_counts: Counter[str] = Counter()
    publisher_counts: Counter[str] = Counter()
    word_count = ready_count = 0
    for occurrence in occurrences:
        family_counts.update(occurrence["familyCandidates"].keys())
        decision = occurrence["publishDecision"]
        if decision["result"] == "unresolved":
            unresolved[str(decision["code"])] += 1
        else:
            ready_count += 1
            publisher = occurrence.get("selectedPublisherFamily")
            if publisher not in ENDPOINT_PUBLISHER_PRIORITY:
                raise ValueError("resolved endpoint occurrence has no closed publisher")
            publisher_counts[str(publisher)] += 1
            if decision["result"] == "words":
                word_count += 1
            else:
                line_only[str(decision["code"])] += 1
    line_count = len(occurrences)
    return {
        "resultClass": (
            "line-unresolved"
            if unresolved
            else "committed-words"
            if word_count == line_count
            else "committed-mixed"
            if word_count
            else "committed-lines"
        ),
        "lineCount": line_count,
        "lineReadyCount": ready_count,
        "wordLineCount": word_count,
        "lineOnlyCount": ready_count - word_count,
        "lineOnlyReasons": dict(sorted(line_only.items())),
        "unresolvedLineReasons": dict(sorted(unresolved.items())),
        "reasons": sorted(unresolved),
        "occurrenceCandidates": occurrences,
        "familyCandidateCounts": dict(sorted(family_counts.items())),
        "selectedPublisherFamilyCounts": dict(sorted(publisher_counts.items())),
        "wordStartAgreementMs": WORD_START_AGREEMENT_MS,
        "wordEndAgreementMs": WORD_END_AGREEMENT_MS,
        "minimumMmsLineConfidence": MIN_MMS_LINE_CONFIDENCE,
        "minimumMmsWordConfidence": MIN_MMS_WORD_CONFIDENCE,
        "reviewedLrcStartAgreementMs": WORD_START_AGREEMENT_MS,
        "reviewedLrcStartEvidenceCount": reviewed_lrc_start_evidence_count,
    }


def _refine_public_timings(
    forced: dict[str, Any],
    verifier_lines: tuple[dict[str, Any] | None, ...],
    reviewed_lrc_starts: dict[int, int] | None = None,
    *,
    verifier_family: str = WAV2VEC2_FAMILY,
    free_family_candidates: dict[
        str, tuple[dict[str, Any] | None, ...]
    ] | None = None,
    mms_route: str = "torchaudio-mms-fa-on-htdemucs-vocals",
    allowed_wav2vec2_routes: frozenset[str] | None = None,
    include_unresolved: bool = False,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Publish unchanged MMS timings only after one closed verifier/identity gate."""

    lines = forced["lines"]
    if len(lines) != len(verifier_lines):
        raise ValueError("endpoint families disagree on performed line count")
    free_family_candidates = free_family_candidates or {}
    if any(
        family not in FREE_STRUCTURE_WITNESS_FAMILIES
        or len(candidates) != len(lines)
        for family, candidates in free_family_candidates.items()
    ):
        raise ValueError("endpoint free witness candidate map is invalid")
    if verifier_family not in {WAV2VEC2_FAMILY, UNPROMPTED_WHISPER_FAMILY}:
        raise ValueError("endpoint verifier family is invalid")
    if verifier_family in free_family_candidates:
        raise ValueError("endpoint verifier cannot also own occurrence identity")
    allowed_wav2vec2_routes = allowed_wav2vec2_routes or frozenset(
        (WAV2VEC2_ROUTE,)
    )
    if (
        not allowed_wav2vec2_routes
        or not allowed_wav2vec2_routes.issubset(
            WAV2VEC2_ROUTES_BY_AUDIO_VIEW.values()
        )
    ):
        raise ValueError("endpoint Wav2Vec2 route allowlist is invalid")
    for candidate in verifier_lines:
        if candidate is None:
            continue
        route = candidate.get("route")
        if (
            verifier_family == WAV2VEC2_FAMILY
            and route not in allowed_wav2vec2_routes
        ):
            raise ValueError("endpoint verifier is not the closed Wav2Vec2 family")
        if verifier_family == UNPROMPTED_WHISPER_FAMILY and (
            not isinstance(route, str)
            or _free_structure_witness_family(route) != UNPROMPTED_WHISPER_FAMILY
        ):
            raise ValueError(
                "endpoint verifier is not the closed unprompted Faster-Whisper family"
            )
    reviewed_lrc_starts = reviewed_lrc_starts or {}
    sources = [line["sourcePhysicalIndex"] for line in lines]
    if len(set(sources)) != len(sources):
        raise ValueError("performed lyric occurrences must have distinct source identities")
    output: list[dict[str, Any]] = []
    occurrences: list[dict[str, Any]] = []
    for index, (mms_line, verifier_line) in enumerate(
        zip(lines, verifier_lines)
    ):
        if verifier_line is not None and mms_line["lineId"] != verifier_line.get("lineId"):
            raise ValueError("endpoint families have different stable occurrence IDs")
        reviewed_start = reviewed_lrc_starts.get(mms_line["sourcePhysicalIndex"])
        unresolved_reason = (
            "reviewed-lrc-start-disagreement"
            if reviewed_start is not None
            and abs(mms_line["startMs"] - reviewed_start) > WORD_START_AGREEMENT_MS
            else "low-forced-line-support"
            if (not _line_supported(mms_line, "mms-fa")
                or (verifier_line is not None and not _line_supported(verifier_line, verifier_family)))
            else f"missing-{verifier_family}-boundary"
            if verifier_line is None
            else _endpoint_agreement_reason(mms_line, verifier_line, "line")
        )
        public_line = {"startMs": mms_line["startMs"], "endMs": mms_line["endMs"]}
        line_only_reason: str | None = None
        if unresolved_reason is None:
            public_words, line_only_reason = assess_complete_word_sequence(
                mms_line, {verifier_family: verifier_line}, publisher_family="mms-fa")
            if public_words is not None:
                public_line["words"] = public_words
        decision = {
            "result": (
                "unresolved"
                if unresolved_reason is not None
                else "line-only"
                if line_only_reason is not None
                else "words"
            )
        }
        if unresolved_reason or line_only_reason:
            decision["code"] = unresolved_reason or line_only_reason
        mms_candidate = {
            "route": mms_route,
            "startMs": mms_line["startMs"],
            "endMs": mms_line["endMs"],
            "confidence": mms_line["confidence"],
            "words": [
                {
                    "wordId": word["wordId"],
                    "startMs": word["startMs"],
                    "endMs": word["endMs"],
                    "confidence": word["confidence"],
                    "acousticTokenCount": word["acousticTokenCount"],
                }
                for word in mms_line["words"]
            ],
        }
        family_candidates = {
            "mms-fa": _candidate_audit(mms_candidate, include_confidence=True)
        }
        if verifier_line is not None:
            family_candidates[verifier_family] = _candidate_audit(
                verifier_line, include_confidence="confidence" in verifier_line
            )
        for family, candidates in free_family_candidates.items():
            candidate = candidates[index]
            if candidate is not None:
                family_candidates[family] = _candidate_audit(candidate)
        occurrence: dict[str, Any] = {
            "occurrenceId": mms_line["lineId"],
            "performedIndex": index,
            "sourcePhysicalIndex": mms_line["sourcePhysicalIndex"],
            "familyCandidates": family_candidates,
            "endpointWitnessFamilies": ["mms-fa", verifier_family],
            "publishDecision": decision,
        }
        if reviewed_start is not None:
            occurrence["reviewedLrcStartMs"] = reviewed_start
        if decision["result"] != "unresolved":
            occurrence["selectedPublisherFamily"] = "mms-fa"
        occurrences.append(occurrence)
        output.append(public_line)
    summary = _summarize_endpoint_occurrences(
        occurrences,
        reviewed_lrc_start_evidence_count=sum(source in sources for source in reviewed_lrc_starts),
    )
    return (output if include_unresolved or summary["lineReadyCount"] else []), summary


def _ordered_endpoint_placeholders(
    timings: list[dict[str, Any]], occurrences: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Keep qualified timings fixed; unresolved estimates are planning only."""
    for family in (None, *ENDPOINT_PUBLISHER_PRIORITY):
        proposed = list(timings)
        if family is not None:
            for index, occurrence in enumerate(occurrences):
                if occurrence["publishDecision"]["result"] != "unresolved":
                    continue
                candidate = occurrence["familyCandidates"].get(family)
                if candidate is not None:
                    proposed[index] = {
                        "startMs": candidate["startMs"],
                        "endMs": candidate["endMs"],
                    }
        if all(
            left["endMs"] <= right["startMs"]
            for left, right in zip(proposed, proposed[1:])
        ):
            return proposed
    return timings


def _apply_independent_endpoint_consensus(
    baseline: tuple[list[dict[str, Any]], dict[str, Any]],
    witnesses: dict[str, tuple[dict[str, Any] | None, ...]],
    *,
    include_unresolved: bool = False,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Reassess applicable evidence before filling a complete line placement."""

    timings, baseline_summary = baseline
    occurrences = baseline_summary.get("occurrenceCandidates")
    line_count = baseline_summary.get("lineCount")
    allowed_witnesses = {OWSM_CTC_FAMILY, NEMO_NFA_FAMILY}
    if (
        type(line_count) is not int
        or line_count <= 0
        or len(timings) != line_count
        or not isinstance(occurrences, list)
        or len(occurrences) != line_count
        or not witnesses
        or not set(witnesses).issubset(allowed_witnesses)
        or any(len(values) != line_count for values in witnesses.values())
    ):
        raise ValueError("independent endpoint consensus input is malformed")

    output = list(timings)
    merged_occurrences: list[dict[str, Any]] = []
    filled_indices: set[int] = set()
    ambiguous_count = 0
    contradiction_count = 0
    for index, occurrence in enumerate(occurrences):
        family_candidates = dict(occurrence["familyCandidates"])
        ineligible = {}
        for family, values in witnesses.items():
            candidate = values[index]
            if candidate is not None:
                if (not _valid_endpoint_sequence(candidate)
                        or candidate.get("lineId", candidate.get("occurrenceId", occurrence["occurrenceId"])) != occurrence["occurrenceId"]
                        or candidate.get("sourcePhysicalIndex", occurrence["sourcePhysicalIndex"]) != occurrence["sourcePhysicalIndex"]
                        or candidate.get("performedIndex", index) != index):
                    ineligible[family] = "invalid-or-inapplicable-endpoint"
                    continue
                family_candidates[family] = _candidate_audit(candidate)
        merged = {**occurrence, "familyCandidates": family_candidates}
        if ineligible:
            merged["ineligibleEndpointFamilies"] = ineligible
        decision = occurrence["publishDecision"]
        code = str(decision.get("code", ""))
        if (occurrence.get("qualifiedEndpointContradictions")
                or occurrence.get("qualifiedWordContradictions") or code == "qualified-word-contradiction"):
            merged_occurrences.append(merged)
            continue
        candidates = _eligible_endpoint_candidates(merged)
        applicable = _applicable_endpoint_candidates(merged)
        if any(_supported_endpoint_agreement_reason(left, left_family, right, right_family, "line") is not None
               for (left_family, left), (right_family, right) in combinations(applicable.items(), 2)):
            contradiction_count += 1
            ambiguous_count += 1
            reassessed = _reassess_endpoint_update(occurrence, merged, timings[index])
            if reassessed is not None:
                output[index], merged = reassessed
            else:
                merged["publishDecision"] = {"result": "unresolved", "code": "qualified-endpoint-contradiction"}
                merged.pop("selectedPublisherFamily", None)
                merged.pop("supportingEndpointFamilies", None)
                output[index] = {key: value for key, value in timings[index].items() if key != "words"}
            merged_occurrences.append(merged)
            continue
        if decision["result"] != "unresolved":
            if decision["result"] == "words":
                publisher = family_candidates[occurrence["selectedPublisherFamily"]]
                for family, candidate in applicable.items():
                    if family != occurrence["selectedPublisherFamily"] and _word_endpoint_contradiction(
                            publisher, candidate, family, publisher_family=occurrence['selectedPublisherFamily']):
                        contradiction_count += 1
                        merged["publishDecision"] = {"result": "line-only", "code": "qualified-word-contradiction"}
                        merged["qualifiedWordContradictions"] = {"previousPublisher": publisher,
                            "incomingCandidates": {family: candidate for family, candidate in applicable.items()
                                                   if candidate.get("words")}}
                        merged.pop("supportingWordEndpointFamilies", None)
                        output[index] = {key: value for key, value in timings[index].items() if key != "words"}
                        break
            merged_occurrences.append(merged)
            continue
        if not (
            code
            in {
                "line-start-disagreement",
                "line-end-disagreement",
                "line-interval-disjoint",
                "qualified-endpoint-contradiction",
                "low-forced-line-support",
            }
            or code.startswith("missing-")
        ):
            merged_occurrences.append(merged)
            continue

        candidates = {family: candidate for family, candidate in candidates.items()
            if family in ENDPOINT_PUBLISHER_PRIORITY
            and respects_reviewed_start(occurrence, candidate, WORD_START_AGREEMENT_MS)}
        families = tuple(candidates)
        # ponytail: four pinned families make exhaustive subsets safer than clustering.
        agreeing = [
            frozenset(group)
            for size in range(2, len(families) + 1)
            for group in combinations(families, size)
            if all(
                _endpoint_agreement_reason(candidates[left], candidates[right], "line") is None
                for left, right in combinations(group, 2)
            )
        ]
        maximal = [
            group for group in agreeing if not any(group < other for other in agreeing)
        ]
        if len(maximal) != 1:
            ambiguous_count += 1
            merged_occurrences.append(merged)
            continue
        cluster = maximal[0]
        publisher = next(
            family for family in ENDPOINT_PUBLISHER_PRIORITY if family in cluster
        )
        selected = candidates[publisher]
        output[index] = {
            "startMs": selected["startMs"],
            "endMs": selected["endMs"],
        }
        merged_occurrences.append(
            {
                **merged,
                "publishDecision": {
                    "result": "line-only",
                    "code": "independent-endpoint-consensus-line-only",
                },
                "selectedPublisherFamily": publisher,
                "supportingEndpointFamilies": [
                    family
                    for family in ENDPOINT_PUBLISHER_PRIORITY
                    if family in cluster
                ],
            }
        )
        filled_indices.add(index)

    while True:
        output = _ordered_endpoint_placeholders(output, merged_occurrences)
        conflicting = {
            index
            for index, (left, right) in enumerate(zip(output, output[1:]))
            if left["endMs"] > right["startMs"]
        }
        if conflicting and filled_indices:
            options = []
            for index, occurrence in enumerate(merged_occurrences):
                if index in filled_indices:
                    families = occurrence["supportingEndpointFamilies"]
                elif occurrence["publishDecision"]["result"] == "unresolved":
                    families = [family for family in ENDPOINT_PUBLISHER_PRIORITY
                                if family in occurrence["familyCandidates"]]
                else:
                    options.append([(output[index], None)])
                    continue
                choices = [] if index in filled_indices else [(output[index], None)]
                for family in families:
                    candidate = occurrence["familyCandidates"][family]
                    if not respects_reviewed_start(occurrence, candidate, WORD_START_AGREEMENT_MS):
                        continue
                    choices.append(({"startMs": candidate["startMs"], "endMs": candidate["endMs"]},
                                    family if index in filled_indices else None))
                options.append(choices)
            path = _ordered_endpoint_candidate_path(options)
            if path is not None:
                output = [timing for timing, _ in path]
                for index in filled_indices:
                    merged_occurrences[index]["selectedPublisherFamily"] = path[index][1]
                break
        revert = {
            index
            for boundary in conflicting
            for index in (boundary, boundary + 1)
            if index in filled_indices
        }
        if not revert:
            if conflicting:
                raise ValueError("baseline endpoint timings are not globally ordered")
            break
        for index in revert:
            output[index] = timings[index]
            merged_occurrences[index] = {
                **occurrences[index],
                "familyCandidates": merged_occurrences[index]["familyCandidates"],
            }
            filled_indices.remove(index)

    word_upgrades = 0
    for index, occurrence in enumerate(merged_occurrences):
        applicable = _applicable_endpoint_candidates(occurrence)
        if (not occurrence.get("qualifiedWordContradictions")
                and any(_word_endpoint_contradiction(left, right, right_family, publisher_family=left_family)
                    for (left_family, left), (right_family, right) in combinations(applicable.items(), 2))):
            decision = occurrence["publishDecision"]
            if decision.get("code") != "qualified-endpoint-contradiction":
                decision = {"result": "unresolved" if decision["result"] == "unresolved" else "line-only",
                            "code": "qualified-word-contradiction"}
            occurrence = {**occurrence, "publishDecision": decision,
                "qualifiedWordContradictions": {
                    "previousCandidates": {family: candidate for family, candidate in
                        _applicable_endpoint_candidates(occurrences[index]).items() if candidate.get("words")},
                    "incomingCandidates": {family: candidate for family, candidate in applicable.items()
                                           if candidate.get("words")}}}
            occurrence.pop("supportingWordEndpointFamilies", None)
            output[index] = {key: value for key, value in output[index].items() if key != "words"}
            merged_occurrences[index] = occurrence
            contradiction_count += 1
        for family in ENDPOINT_PUBLISHER_PRIORITY:
            if family not in _qualified_word_candidates(occurrence):
                continue
            upgraded = _upgrade_endpoint_words(occurrence,
                {**occurrence, "selectedPublisherFamily": family}, output[index])
            if upgraded is not None:
                output[index], merged_occurrences[index] = upgraded
                word_upgrades += int(merged_occurrences[index]["publishDecision"]["result"] == "words")
                break

    summary = _summarize_endpoint_occurrences(
        merged_occurrences,
        reviewed_lrc_start_evidence_count=baseline_summary[
            "reviewedLrcStartEvidenceCount"
        ],
    )
    summary.update(
        independentConsensusAttempted=True,
        independentConsensusFilledLineCount=len(filled_indices),
        independentConsensusAmbiguousLineCount=ambiguous_count,
        independentConsensusContradictedLineCount=contradiction_count,
        independentConsensusWordUpgradeCount=word_upgrades,
    )
    return (
        output
        if include_unresolved or summary["lineReadyCount"]
        else []
    ), summary


def _merge_endpoint_passes(
    baseline: tuple[list[dict[str, Any]], dict[str, Any]],
    anchored: tuple[list[dict[str, Any]], dict[str, Any]],
    *,
    stability_family: str | None = None,
    repair_windows: list[dict[str, int]] | None = None,
    include_unresolved: bool = False,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    baseline_timings, baseline_summary = baseline
    anchored_timings, anchored_summary = anchored
    line_count = baseline_summary.get("lineCount")
    baseline_occurrences = baseline_summary.get("occurrenceCandidates")
    anchored_occurrences = anchored_summary.get("occurrenceCandidates")
    if (
        type(line_count) is not int
        or line_count <= 0
        or len(baseline_timings) != line_count
        or len(anchored_timings) != line_count
        or anchored_summary.get("lineCount") != line_count
        or not isinstance(baseline_occurrences, list)
        or not isinstance(anchored_occurrences, list)
        or len(baseline_occurrences) != line_count
        or len(anchored_occurrences) != line_count
    ):
        raise ValueError("endpoint passes disagree on line count")
    windows = {}
    if repair_windows is not None:
        _validate_forced_alignment_segments(repair_windows, line_count, repair_windows[-1]["windowEndMs"])
        windows = {i: s for s in repair_windows for i in range(s["startLineIndex"], s["endLineIndex"])}
    output: list[dict[str, Any]] = []
    occurrences: list[dict[str, Any]] = []
    anchored_fill_count = 0
    for index, (baseline_occurrence, anchored_occurrence) in enumerate(
        zip(baseline_occurrences, anchored_occurrences)
    ):
        if (
            baseline_occurrence["occurrenceId"] != anchored_occurrence["occurrenceId"]
            or baseline_occurrence["sourcePhysicalIndex"]
            != anchored_occurrence["sourcePhysicalIndex"]
        ):
            raise ValueError("endpoint passes disagree on occurrence identity")
        reassessed = _reassess_endpoint_update(baseline_occurrence, anchored_occurrence,
                                              baseline_timings[index])
        upgraded = _upgrade_endpoint_words(baseline_occurrence, anchored_occurrence,
                                           baseline_timings[index])
        if reassessed is not None:
            timing, occurrence = reassessed
            output.append(timing)
            occurrences.append(occurrence)
        elif upgraded is not None:
            timing, occurrence = upgraded
            output.append(timing)
            occurrences.append(occurrence)
            anchored_fill_count += int(occurrence["publishDecision"]["result"] == "words")
        elif (baseline_occurrence["publishDecision"]["result"] != "unresolved"
              or baseline_occurrence.get("qualifiedEndpointContradictions")
              or baseline_occurrence.get("qualifiedWordContradictions")):
            # A replay of the same later estimate cannot erase the dissent that
            # revoked a previously qualified publisher's authority.
            output.append(baseline_timings[index])
            occurrences.append(baseline_occurrence)
        else:
            selected_timing = anchored_timings[index]
            selected_occurrence = anchored_occurrence
            baseline_code = str(
                baseline_occurrence["publishDecision"].get("code", "")
            )
            anchored_ready = (
                anchored_occurrence["publishDecision"]["result"] != "unresolved"
            )
            anchorable = baseline_code in {
                "line-start-disagreement",
                "line-end-disagreement",
                "qualified-endpoint-contradiction",
            } or baseline_code.startswith("missing-")
            movement_safe = True
            contradicted_mms = False
            if stability_family is not None:
                for family in ("mms-fa", stability_family):
                    baseline_candidate = baseline_occurrence[
                        "familyCandidates"
                    ].get(family)
                    anchored_candidate = anchored_occurrence[
                        "familyCandidates"
                    ].get(family)
                    if not isinstance(baseline_candidate, dict) or not isinstance(
                        anchored_candidate, dict
                    ):
                        movement_safe = False
                        break
                    window = windows.get(index)
                    if family == "mms-fa" and window is not None:
                        start, end = window["windowStartMs"], window["windowEndMs"]
                        if (not start <= baseline_candidate["startMs"] < baseline_candidate["endMs"] <= end
                                and start <= anchored_candidate["startMs"] < anchored_candidate["endMs"] <= end
                                and (start == 0 or anchored_candidate["startMs"]-start >= WORKER_SEGMENT_BOUNDARY_GUARD_MS)
                                and (end == repair_windows[-1]["windowEndMs"]
                                     or end-anchored_candidate["endMs"] >= WORKER_SEGMENT_BOUNDARY_GUARD_MS)):
                            # Correct a contradicted occurrence, while the separate
                            # verifier below must retain its existing movement bounds.
                            contradicted_mms = True
                            continue
                    if (
                        abs(
                            anchored_candidate["startMs"]
                            - baseline_candidate["startMs"]
                        )
                        > WORD_START_AGREEMENT_MS
                        or abs(
                            anchored_candidate["endMs"]
                            - baseline_candidate["endMs"]
                        )
                        > WORD_END_AGREEMENT_MS
                    ):
                        movement_safe = False
                        break
            anchorable = anchorable or (baseline_code == "low-forced-line-support" and contradicted_mms)
            if not anchored_ready or not anchorable or not movement_safe:
                selected_timing = baseline_timings[index]
                selected_occurrence = baseline_occurrence
                anchored_code = anchored_occurrence["publishDecision"].get("code")
                retain_candidate = (
                    anchored_code in {"line-start-disagreement", "line-end-disagreement"}
                    and float(anchored_occurrence["familyCandidates"]["mms-fa"].get("confidence", 0))
                        >= MIN_MMS_LINE_CONFIDENCE
                )
                if (not anchored_ready and contradicted_mms and movement_safe
                        and baseline_code in {"line-start-disagreement", "line-end-disagreement",
                                              "low-forced-line-support"}
                        and retain_candidate):
                    # A guarded correction remains useful evidence even when
                    # independent endpoints still disagree. It qualifies nothing.
                    selected_occurrence = {**baseline_occurrence,
                        "familyCandidates": anchored_occurrence["familyCandidates"],
                        "publishDecision": anchored_occurrence["publishDecision"]}
            output.append(selected_timing)
            occurrences.append(selected_occurrence)
            anchored_fill_count += int(
                selected_occurrence["publishDecision"]["result"] != "unresolved"
            )
    output = _ordered_endpoint_placeholders(output, occurrences)
    if any(
        left["endMs"] > right["startMs"]
        for left, right in zip(output, output[1:])
    ):
        raise ValueError("merged endpoint timings are not globally ordered")
    summary = _summarize_endpoint_occurrences(
        occurrences,
        reviewed_lrc_start_evidence_count=baseline_summary[
            "reviewedLrcStartEvidenceCount"
        ],
    )
    summary.update(
        baselinePublishedLineCount=baseline_summary["lineReadyCount"],
        anchoredFilledLineCount=anchored_fill_count,
        bootstrapAttempted=True,
    )
    return (
        output
        if include_unresolved or summary["lineReadyCount"]
        else []
    ), summary


def _merge_endpoint_route_passes(
    current: tuple[list[dict[str, Any]], dict[str, Any]],
    candidate: tuple[list[dict[str, Any]], dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Keep independently qualified lines from later acoustic views."""

    current_timings, current_summary = current
    candidate_timings, candidate_summary = candidate
    current_occurrences = current_summary.get("occurrenceCandidates")
    candidate_occurrences = candidate_summary.get("occurrenceCandidates")
    line_count = current_summary.get("lineCount")
    if (
        type(line_count) is not int
        or line_count <= 0
        or candidate_summary.get("lineCount") != line_count
        or len(current_timings) != line_count
        or len(candidate_timings) != line_count
        or not isinstance(current_occurrences, list)
        or not isinstance(candidate_occurrences, list)
        or len(current_occurrences) != line_count
        or len(candidate_occurrences) != line_count
    ):
        raise ValueError("endpoint routes disagree on line count")

    output = list(current_timings)
    occurrences = list(current_occurrences)
    filled: set[int] = set()
    for index, (before, after) in enumerate(
        zip(current_occurrences, candidate_occurrences)
    ):
        if (
            before["occurrenceId"] != after["occurrenceId"]
            or before["sourcePhysicalIndex"] != after["sourcePhysicalIndex"]
        ):
            raise ValueError("endpoint routes disagree on occurrence identity")
        reassessed = _reassess_endpoint_update(before, after, current_timings[index])
        if reassessed is not None:
            output[index], occurrences[index] = reassessed
            continue
        upgraded = _upgrade_endpoint_words(before, after, current_timings[index])
        if upgraded is not None:
            output[index], occurrences[index] = upgraded
            if occurrences[index]["publishDecision"]["result"] == "words":
                filled.add(index)
            continue
        if (
            before["publishDecision"]["result"] == "unresolved"
            and not before.get("qualifiedEndpointContradictions")
            and not before.get("qualifiedWordContradictions")
            and after["publishDecision"]["result"] != "unresolved"
        ):
            output[index] = candidate_timings[index]
            occurrences[index] = after
            filled.add(index)

    while True:
        output = _ordered_endpoint_placeholders(output, occurrences)
        conflicts = {
            index
            for index, (left, right) in enumerate(zip(output, output[1:]))
            if left["endMs"] > right["startMs"]
        }
        revert = {
            index
            for boundary in conflicts
            for index in (boundary, boundary + 1)
            if index in filled
        }
        if not revert:
            if conflicts:
                raise ValueError("existing endpoint route timings are not globally ordered")
            break
        for index in revert:
            output[index] = current_timings[index]
            occurrences[index] = current_occurrences[index]
            filled.remove(index)

    # Keep a later route's guarded correction for review, even without a fill.
    # Use only surviving qualified neighbors after any publication rollback.
    for index in range(1, line_count - 1):
        before, after = occurrences[index], candidate_occurrences[index]
        if (before["publishDecision"]["result"] != "unresolved"
                or before.get("qualifiedEndpointContradictions")
                or after["publishDecision"]["result"] != "unresolved"
                or after["publishDecision"].get("code") not in {"line-start-disagreement", "line-end-disagreement"}
                or any(occurrences[i]["publishDecision"]["result"] == "unresolved"
                       for i in (index - 1, index + 1))):
            continue
        local = (occurrences[index - 1], after, occurrences[index + 1])
        if any(f not in row["familyCandidates"] for row in (*local, before)
               for f in ("mms-fa", WAV2VEC2_FAMILY)):
            continue
        old_mms, new_mms = before["familyCandidates"]["mms-fa"], after["familyCandidates"]["mms-fa"]
        old_verifier, new_verifier = before["familyCandidates"][WAV2VEC2_FAMILY], after["familyCandidates"][WAV2VEC2_FAMILY]
        if (float(new_mms.get("confidence", 0)) < MIN_MMS_LINE_CONFIDENCE
                or abs(old_verifier["startMs"] - new_verifier["startMs"]) > WORD_START_AGREEMENT_MS
                or abs(old_verifier["endMs"] - new_verifier["endMs"]) > WORD_END_AGREEMENT_MS):
            continue
        # Only interior fences are used; this upper bound is not a song EOF.
        upper = max(c["endMs"] for row in local for c in row["familyCandidates"].values()) + 1
        cuts = []
        for left, right, qualified in ((local[0], after, local[0]), (after, local[2], local[2])):
            supporting = qualified.get("supportingEndpointFamilies", ["mms-fa", WAV2VEC2_FAMILY])
            if (len(supporting) < 2 or not set(supporting).issubset(ENDPOINT_PUBLISHER_PRIORITY)
                    or any(f not in row["familyCandidates"] for row in (left, right) for f in supporting)):
                break
            gap = _verifier_safe_boundaries(2, upper, *(
                (left["familyCandidates"][f], right["familyCandidates"][f]) for f in supporting))
            if 1 not in gap:
                break
            cuts.append(gap[1])
        if len(cuts) != 2:
            continue
        start, end = cuts
        if (not start <= old_mms["startMs"] < old_mms["endMs"] <= end
                and start + WORKER_SEGMENT_BOUNDARY_GUARD_MS <= new_mms["startMs"]
                < new_mms["endMs"] <= end - WORKER_SEGMENT_BOUNDARY_GUARD_MS):
            occurrences[index] = after
    output = _ordered_endpoint_placeholders(output, occurrences)

    summary = _summarize_endpoint_occurrences(
        occurrences,
        reviewed_lrc_start_evidence_count=current_summary[
            "reviewedLrcStartEvidenceCount"
        ],
    )
    summary.update(
        cumulativeRouteMerge=True,
        cumulativeRouteFilledLineCount=len(filled),
    )
    return output, summary


def _require_recoverable_endpoint_failure(error: Exception) -> None:
    if (isinstance(error, (LyricsContentBlocker, WorkerRetirementError, AcousticBudgetExceeded, ProviderCancelled))
            or isinstance(error, ProviderDeadline) and not isinstance(error, ProviderStageDeadline)):
        raise error


def private_vowel_estimates(request, summary, get_pair):
    """Search the selected English sequence after ordinary endpoint adjudication."""
    from stemwise_engine import endpoint_vowel_release as release
    if (summary['resultClass']=='committed-words' or not release.english_track(request.text.original)
            or request.text.lexical_mapping.get('_spokenTargets') is not None or not request.audio.expected_mix_sha256):
        return {}
    rows = summary['occurrenceCandidates']
    guides = release.vocal_word_ends(rows)
    if not guides:
        return {}
    words = []
    for owner, row, display in zip(request.text.lexical_mapping['performedOccurrences'], rows,
                                   request.text.display_word_lines, strict=True):
        if (owner['occurrenceId']!=row['occurrenceId'] or owner['sourcePhysicalIndex']!=row['sourcePhysicalIndex']):
            raise ValueError('Different performed occurrence for vowel estimate')
        locked = row['publishDecision']['result']=='words'
        family = row.get('selectedPublisherFamily') if locked else WAV2VEC2_FAMILY
        values = row['familyCandidates'].get(family) or {}
        if not values.get('words') or (not locked and values.get('route')!=WAV2VEC2_ROUTE):
            words.extend([None]*len(display))
            continue
        for token,w,(_,_,text) in zip(owner['words'],values['words'],display,strict=True):
            if token['occurrenceWordId']!=w['occurrenceWordId']:
                raise ValueError('Different word in vowel estimate')
            words.append({'wordId':w['occurrenceWordId'],'text':text,'startMs':w['startMs'],'endMs':w['endMs'],
                          'timing':'retained' if locked else 'estimated'})
    ctx = request.context
    candidates = release.plan(words,guides,ctx.duration_ms)
    project = Path(ctx.config['projectDir'])
    if not candidates or not (project.parent/'model-cache'/release.MODEL_DIR/'model.onnx').is_file():
        return {}
    vocal, identity, _ = get_pair('htdemucs-vocals')
    if (identity.get('sourceAudioSha256')!=request.audio.expected_mix_sha256
            or identity.get('routeId')!='htdemucs-vocals' or vocal.name!='vocals.wav'):
        raise ValueError('Different recording or stem for vowel estimates')
    estimates, ref = release.estimate(project,ctx.workspace.job_dir(ctx.video_id),ctx.duration_ms,request.audio.expected_mix_sha256,
        (vocal,vocal.with_name('no_vocals.wav'),identity['sha256']),candidates,int(ctx.config['analysisTimeoutMinutes'])*60)
    return {'evidence':ref,'words':{k:{a:b for a,b in r.items() if a!='traces'}
                                    for k,r in estimates.items()}} if estimates else {}


@dataclass(kw_only=True)
class EndpointAlignmentStage:
    """Own endpoint witnesses, route recovery and cumulative timing selection."""
    request: EndpointAlignmentRequest
    services: EndpointServices
    cumulative_endpoint_result: EndpointTimings | None = None
    independent_witnesses: dict[str, tuple[dict[str, Any] | None, ...]] = field(default_factory=dict)
    independent_witness_reused: dict[str, bool] = field(default_factory=dict)
    mix_audio: Path | None = field(init=False)
    mix_identity: dict[str, Any] | None = field(init=False)

    def __post_init__(self):
        admission = require_workspace_content(self.request.context.workspace,
            self.request.context.video_id, self.request.text.sources,
            original=self.request.text.original, audio_sha=self.request.audio.expected_mix_sha256)
        if self.request.evidence.content_admission != admission.to_record():
            raise LyricsContentBlocker("content-binding-differs", binding=admission.binding)
        self.services.progress.state["contentAdmission"] = admission.to_record()
        self.mix_audio = self.request.audio.mix_audio
        self.mix_identity = self.request.audio.mix_identity

    def require_timing_recovery(self, result):
        summary = result[1]
        if summary.get("resultClass") == "committed-words":
            return
        admission = require_workspace_content(self.request.context.workspace,
            self.request.context.video_id, self.request.text.sources,
            original=self.request.text.original, audio_sha=self.request.audio.expected_mix_sha256)
        reasons = tuple(occurrence["publishDecision"].get("code", "")
            for occurrence in summary.get("occurrenceCandidates", ())
            if occurrence["publishDecision"].get("result") == "unresolved")
        if reasons:
            require_timing_only(reasons, admission, occurrences=summary.get('occurrenceCandidates'),
                expected_occurrences=self.request.text.lexical_mapping.get('performedOccurrences'))
        word_gaps = [occurrence for occurrence in summary.get("occurrenceCandidates", ())
                     if occurrence["publishDecision"].get("result") == "line-only"]
        if word_gaps and any(occurrence["publishDecision"].get("code") not in
                _WORD_TIMING_RECOVERY_REASONS | {"qualified-word-contradiction"} for occurrence in word_gaps):
            raise LyricsContentBlocker("failure-not-confirmed-timing-only", binding=admission.binding)
        # Low forced support says nothing about which pronunciation was performed.
        if word_gaps:
            from stemwise_engine.lyric_content import require_pronunciation_support
            from stemwise_engine.spoken_targets import attached
            require_pronunciation_support(self.request.text.original["text"], attached(self.request.text.original))
        from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
        expected = build_lexical_occurrence_mapping(self.request.context.video_id,
            self.request.text.original, self.request.text.sources)["performedOccurrences"]
        actual = summary.get("occurrenceCandidates", ())
        if (self.request.text.lexical_mapping.get("performedOccurrences") != expected
                or len(actual) != len(expected)):
            raise LyricsContentBlocker("content-occurrence-uncertain", binding=admission.binding)
        for index, (occurrence, owner) in enumerate(zip(actual, expected)):
            if any(occurrence.get(key) != owner[key] for key in
                   ("performedIndex", "sourcePhysicalIndex", "occurrenceId")):
                raise LyricsContentBlocker("content-occurrence-uncertain", binding=admission.binding)
            ids = [word["occurrenceWordId"] for word in owner["words"]]
            for candidate in occurrence["familyCandidates"].values():
                words = candidate.get("words")
                if words is not None and [word.get("occurrenceWordId", word.get("wordId")) for word in words] != ids:
                    raise LyricsContentBlocker("content-occurrence-uncertain", binding=admission.binding)

    def route_diagnostic(self, error: Exception, stage: str, route_id: str):
        context = self.request.context
        return worker_protocol.technical_diagnostic_code(error,
            context.workspace.job_dir(context.video_id) / 'model-route.failure.private.txt',
            context={'attemptId': self.services.progress.state.get('attemptId'),
                     'videoId': context.video_id, 'stage': stage, 'route': route_id,
                     'model': route_id, 'device': context.device,
                     'audioSha256': self.request.audio.expected_mix_sha256})

    def repair_anchored(self, route_id, anchored_segments, anchored_verifier_candidates,
                        route_reused=True, wav2vec2_anchored_reused=True):
        """Resolve paired families against the same final occurrence windows."""
        _validate_occurrence_identity(self.request.text.source_occurrences, anchored_segments)
        mms_anchored_reused = True
        paired_anchored = None
        seen_plans: set[SegmentPlan] = set()
        while anchored_verifier_candidates is not None:
            forced_anchored = None
            _validate_occurrence_identity(
                self.request.text.source_occurrences, anchored_segments
            )
            try:
                plan = SegmentPlan.from_segments(anchored_segments)
                if plan in seen_plans:
                    raise worker_protocol.WorkerProcessError(
                        "Anchored endpoint repair repeated an already attempted plan",
                        "endpoint-anchored-plan-no-progress",
                    )
                seen_plans.add(plan)
                forced_anchored, mms_anchored_reused = (
                    self.services.workers.load_mms(
                        config=self.request.context.config,
                        video_id=self.request.context.video_id,
                        original=self.request.text.original,
                        alignment=self.request.text.alignment,
                        occurrence_mapping=self.request.text.lexical_mapping,
                        duration_ms=self.request.context.duration_ms,
                        workspace=self.request.context.workspace,
                        temp_root=self.request.context.temp_root,
                        device=self.request.context.device,
                        timeout_seconds=int(
                            self.request.context.config["analysisTimeoutMinutes"]
                        )
                        * 60,
                        expected_mix_sha256=self.request.audio.expected_mix_sha256,
                        analysis_audio=self.request.audio.analysis_audio,
                        audio_view_id=route_id,
                        get_audio=lambda route_id=route_id: self.request.audio.get_endpoint_audio(
                            route_id
                        ),
                        request_variant="anchored",
                        segments=anchored_segments,
                        request_duration_ms=self.request.context.endpoint_duration_ms,
                        runtime_cache=self.services.runtime_probe_cache,
                    )
                )
                resolved_segments = forced_anchored.get("resolvedSegments", anchored_segments)
                _validate_forced_alignment_segments(resolved_segments, len(self.request.text.sources), self.request.context.endpoint_duration_ms)
                if resolved_segments != anchored_segments:
                    anchored_segments = resolved_segments
                    if self.request.evidence.verifier_family != UNPROMPTED_WHISPER_FAMILY:
                        (anchored_verifier_candidates, wav2vec2_anchored_reused,
                         anchored_segments) = self.load_route_wav2vec2(
                            route_id, request_variant="anchored", segments=anchored_segments,
                        )
                        continue
                _validate_occurrence_identity(
                    self.request.text.source_occurrences, anchored_segments,
                )
                paired_anchored = PairedEndpointResult.bind(
                    forced_anchored, anchored_verifier_candidates, anchored_segments)
                break
            except (LyricsContentBlocker, WorkerRetirementError):
                raise
            except (
                OSError,
                RuntimeError,
                TimeoutError,
                TimeoutExpired,
                ValueError,
            ) as error:
                _require_recoverable_endpoint_failure(error)
                match = (
                    re.fullmatch(
                        r"wav2vec2-segment-(?:too-short:(\d+)|boundary-guard:(\d+):(start|end))",
                        error.code,
                    )
                    if isinstance(error, worker_protocol.WorkerProcessError)
                    else None
                )
                if match is not None:
                    try:
                        widened_segments = _merge_short_bootstrap_segment(
                            anchored_segments,
                            int(match.group(1) or match.group(2)),
                            boundary=match.group(3),
                        )
                    except ValueError:
                        pass
                    else:
                        route_reused = False
                        try:
                            if self.request.evidence.verifier_family == UNPROMPTED_WHISPER_FAMILY:
                                anchored_segments = widened_segments
                            else:
                                (
                                    anchored_verifier_candidates,
                                    wav2vec2_anchored_reused,
                                    anchored_segments,
                                ) = self.load_route_wav2vec2(
                                    route_id,
                                    request_variant="anchored",
                                    segments=widened_segments,
                                )
                        except (LyricsContentBlocker, WorkerRetirementError):
                            raise
                        except (
                            OSError, RuntimeError, TimeoutError,
                            TimeoutExpired, ValueError,
                        ) as verifier_error:
                            _require_recoverable_endpoint_failure(verifier_error)
                            self.services.events.failure(
                                "endpoint-bootstrap",
                                f"{route_id}:anchored-wav2vec2",
                                self.route_diagnostic(verifier_error, 'endpoint-bootstrap', f'{route_id}:anchored-wav2vec2'),
                            )
                            anchored_verifier_candidates = None
                        continue
                self.services.events.failure(
                    "endpoint-bootstrap",
                    f"{route_id}:anchored-mms",
                    self.route_diagnostic(error, 'endpoint-bootstrap', f'{route_id}:anchored-mms'),
                )
                break
        return (paired_anchored, anchored_segments,
                route_reused and wav2vec2_anchored_reused and mms_anchored_reused)

    def load_independent_witness(self,
        family: str,
    ) -> tuple[tuple[dict[str, Any], ...], bool]:
        if family in self.independent_witnesses:
            return (
                self.independent_witnesses[family],
                self.independent_witness_reused[family],
            )
        if self.mix_audio is None or self.mix_identity is None:
            self.mix_audio, self.mix_identity = self.request.audio.canonical_mix()
        common = {
            "config": self.request.context.config,
            "video_id": self.request.context.video_id,
            # These workers bind the original WAV, before resampling.
            "duration_ms": self.mix_identity["stream"]["durationMs"],
            "workspace": self.request.context.workspace,
            "audio": self.mix_audio,
            "audio_identity": self.mix_identity,
            "temp_dir": self.request.context.temp_root,
            "timeout_seconds": int(self.request.context.config["analysisTimeoutMinutes"])
            * 60,
            "before_run": self.services.release_model,
        }
        if family not in {OWSM_CTC_FAMILY, NEMO_NFA_FAMILY}:
            raise ValueError("unsupported independent endpoint family")
        candidates = [None for _ in self.request.text.sources]
        reused = True
        inputs = (self.request.evidence.independent_inputs if family == OWSM_CTC_FAMILY else
                  _independent_endpoint_inputs(self.request.text.original, self.request.text.lexical_mapping,
                      self.request.text.display_word_lines, {"ar"}, subset=True))
        for component, (indices, mapping_input, selected_words) in inputs.items():
            loader = (self.services.workers.load_owsm if family == OWSM_CTC_FAMILY
                      else self.services.workers.load_nfa)
            full_context = family == OWSM_CTC_FAMILY and len(selected_words) == len(self.request.text.sources)
            request_indices = list(range(len(self.request.text.sources))) if full_context else indices
            try:
                component_candidates, component_reused = loader(
                    **common, language=component,
                    sources=[self.request.text.sources[index] for index in request_indices],
                    occurrence_mapping=mapping_input, display_word_lines=selected_words,
                )
                if len(component_candidates) != len(request_indices):
                    raise ValueError("independent endpoint result count disagrees")
            except Exception as error:
                _require_recoverable_endpoint_failure(error)
                route = f'{family}:{component}'
                self.services.events.failure('endpoint-consensus', route,
                    self.route_diagnostic(error, 'endpoint-consensus', route))
                reused = False
                continue
            if full_context:
                component_candidates = tuple(component_candidates[index] for index in indices)
            for index, candidate in zip(indices, component_candidates):
                candidates[index] = candidate
            reused = reused and component_reused
        candidates = tuple(candidates)
        if family == OWSM_CTC_FAMILY:
            self.services.progress.state["pinnedModels"]["owsmCtcCheckpointSha256"] = (
                owsm_ctc.CHECKPOINT_SHA256
            )
        else:
            self.services.progress.state["pinnedModels"]["nemoNfaModelSha256"] = (
                nemo_nfa.MODEL_SHA256
            )
        self.independent_witnesses[family] = candidates
        self.independent_witness_reused[family] = reused
        return candidates, reused

    def apply_independent_consensus(self,
        baseline: tuple[list[dict[str, Any]], dict[str, Any]],
    ) -> tuple[
        tuple[list[dict[str, Any]], dict[str, Any]], bool, bool
    ]:
        if self.independent_witnesses:
            baseline = _apply_independent_endpoint_consensus(
                baseline, self.independent_witnesses, include_unresolved=True)
        self.require_timing_recovery(baseline)
        if (not self.request.evidence.independent_inputs
                or baseline[1]["resultClass"] == "committed-words"):
            return baseline, False, True
        witnesses = {}
        consensus_result = baseline
        consensus_reused = True
        families = [OWSM_CTC_FAMILY]
        if "ar" in self.request.evidence.independent_inputs:
            families.append(NEMO_NFA_FAMILY)
        for family in families:
            if consensus_result[1]["resultClass"] == "committed-words":
                break
            self.require_timing_recovery(consensus_result)
            try:
                candidates, reused = self.load_independent_witness(family)
            except (LyricsContentBlocker, WorkerRetirementError):
                raise
            except Exception as error:
                _require_recoverable_endpoint_failure(error)
                self.services.events.failure("endpoint-consensus", family,
                    self.route_diagnostic(error, 'endpoint-consensus', family))
            else:
                if not any(candidate is not None for candidate in candidates):
                    continue
                witnesses[family] = candidates
                consensus_reused = consensus_reused and reused
                consensus_result = _apply_independent_endpoint_consensus(
                    baseline, witnesses, include_unresolved=True)
        return consensus_result, bool(witnesses), consensus_reused

    def record_vowel_estimates(self, summary):
        from stemwise_engine.lyrics_alignment import lexical_mapping_identity
        state = self.services.progress.state
        state.pop('estimatedVowelReleases',None)
        try:
            released = private_vowel_estimates(self.request,summary,self.request.audio.get_endpoint_audio)
        except (OSError, RuntimeError, ValueError, TimeoutExpired) as error:
            _require_recoverable_endpoint_failure(error)
            self.services.events.attempt('private-vowel-release','htdemucs-vocals','failed',
                code=self.route_diagnostic(error,'private-vowel-release','htdemucs-vocals'))
            return
        if released:
            state['estimatedVowelReleases'] = {'audioSha256':self.request.audio.expected_mix_sha256,
                'canonicalPlainSha256':state['canonicalPlainSha256'],
                'mapping':lexical_mapping_identity(self.request.text.lexical_mapping),'quality':'estimated',**released}

    def load_baseline_mms(self, route_id: str, *, use_timing_seed: bool = False):
        return self.load_mms_plan(route_id, "prior-recording-seed" if use_timing_seed else "baseline",
            self.request.evidence.seeded_mms_segments if use_timing_seed else self.request.evidence.baseline_segments)

    def load_mms_plan(self, route_id, variant, segments):
        return self.services.workers.load_mms(
            config=self.request.context.config,
            video_id=self.request.context.video_id,
            original=self.request.text.original,
            alignment=self.request.text.alignment,
            occurrence_mapping=self.request.text.lexical_mapping,
            duration_ms=self.request.context.duration_ms,
            workspace=self.request.context.workspace,
            temp_root=self.request.context.temp_root,
            device=self.request.context.device,
            timeout_seconds=int(self.request.context.config["analysisTimeoutMinutes"]) * 60,
            expected_mix_sha256=self.request.audio.expected_mix_sha256,
            analysis_audio=self.request.audio.analysis_audio,
            audio_view_id=route_id,
            get_audio=lambda route_id=route_id: self.request.audio.get_endpoint_audio(route_id),
            request_variant=variant,
            segments=segments,
            request_duration_ms=self.request.context.endpoint_duration_ms,
            runtime_cache=self.services.runtime_probe_cache,
        )

    def recover_opening(self, forced, occurrence):
        audio = self.request.audio
        identity = forced.get('provenance', {}).get('audio', {})
        if (not audio.opening_stem_power or not audio.expected_mix_sha256
                or identity.get('sourceAudioSha256') != audio.expected_mix_sha256
                or identity.get('routeId') != 'htdemucs-vocals'):
            return None
        plan = _opening_retry_plan(forced, occurrence, self.request.text.lexical_mapping['performedOccurrences'][0])
        if plan is None:
            return None
        segments, starts = plan
        first = forced['lines'][0]['words'][0]
        power = audio.opening_stem_power(identity, [first['startMs'], *starts.values()])
        if (not power or power[0]['vocalPower'] >= power[0]['backingPower']
                or any(row['vocalPower'] <= row['backingPower'] for row in power[1:])):
            return None
        retry, reused = self.load_mms_plan('htdemucs-vocals', 'vocal-entry', segments)
        if (retry.get('provenance') != forced['provenance']
                or not forced.get('mappingIdentity') or retry.get('mappingIdentity') != forced['mappingIdentity']
                or retry.get('resolvedSegments') != segments
                or not _same_following_words(forced['lines'], retry['lines'])):
            return None
        start = retry['lines'][0]['words'][0]['startMs']
        if (start - segments[0]['windowStartMs'] <= WORKER_SEGMENT_BOUNDARY_GUARD_MS
                or start >= forced['lines'][0]['words'][1]['startMs']
                or any(abs(start - t) > WORD_START_AGREEMENT_MS for t in starts.values())
                or not respects_reviewed_start(occurrence, {'startMs': start}, WORD_START_AGREEMENT_MS)):
            return None
        return retry, reused, {'occurrenceId': occurrence['occurrenceId'], 'wordId': first['wordId'],
            'sourceAudioSha256': audio.expected_mix_sha256, 'vocalSha256': identity['sha256'],
            'previousStartMs': first['startMs'], 'witnessStartsMs': starts, 'stemPower': power,
            'windowStartMs': segments[0]['windowStartMs'], 'startMs': start, 'endMs': None}

    def assess_opening_retry(self, forced, opening, verifier, current):
        recovered = self.recover_opening(forced, opening)
        if recovered is None:
            return None
        measured, reused, entry = recovered
        evidence = self.request.evidence
        candidate = _refine_public_timings(measured, verifier, evidence.reviewed_lrc_starts,
            verifier_family=evidence.verifier_family, free_family_candidates=evidence.free_family_candidates,
            mms_route=evidence.route_names['htdemucs-vocals'], include_unresolved=True,
            allowed_wav2vec2_routes=frozenset((WAV2VEC2_ROUTE, WAV2VEC2_ROUTES_BY_AUDIO_VIEW['htdemucs-vocals'])))
        if self.independent_witnesses:
            candidate = _apply_independent_endpoint_consensus(candidate, self.independent_witnesses, include_unresolved=True)
        timings, summary = current
        if candidate[1]['occurrenceCandidates'][0]['publishDecision']['result'] != 'unresolved':
            merged, assessed = _merge_endpoint_route_passes(current, candidate)
            if (assessed['occurrenceCandidates'][0]['publishDecision']['result'] != 'unresolved'
                    and merged[1:] == timings[1:] and assessed['occurrenceCandidates'][1:] == summary['occurrenceCandidates'][1:]):
                timings, summary = merged, {**summary, **assessed}
        return timings, {**summary, 'openingWordRecovery': entry}, reused

    def load_route_wav2vec2(self,
        route_id: str,
        *,
        request_variant: str,
        segments: list[dict[str, int]],
    ) -> tuple[tuple[dict[str, Any] | None, ...], bool, list[dict[str, int]]]:
        expected_route = WAV2VEC2_ROUTES_BY_AUDIO_VIEW[route_id]
        provenance = self.services.workers.read_audio_provenance(
            self.request.context.workspace,
            self.request.context.video_id,
            self.request.context.duration_ms,
            expected_mix_sha256=self.request.audio.expected_mix_sha256,
        )
        identity = (
            provenance.get("audioViews", {}).get(route_id)
            if isinstance(provenance, dict)
            and isinstance(provenance.get("audioViews"), dict)
            else None
        )
        audio: Path | None = None
        if not isinstance(identity, dict):
            audio, identity, _ = self.request.audio.get_endpoint_audio(route_id)
        request_segments = segments
        restored_audio = False
        while True:
            try:
                forced, reused, model_identity = (
                    self.services.workers.load_wav2vec2(
                        config=self.request.context.config,
                        video_id=self.request.context.video_id,
                        language=self.request.evidence.wav2vec2_language,
                        sources=self.request.text.sources,
                        duration_ms=self.request.context.endpoint_duration_ms,
                        workspace=self.request.context.workspace,
                        audio=audio,
                        audio_identity=identity,
                        audio_view_id=route_id,
                        temp_dir=self.request.context.temp_root,
                        device=self.request.context.device,
                        timeout_seconds=int(self.request.context.config["analysisTimeoutMinutes"])
                        * 60,
                        occurrence_mapping=self.request.text.lexical_mapping,
                        display_word_lines=self.request.text.display_word_lines,
                        acoustic_language_lines=self.request.text.acoustic_language_lines,
                        request_variant=request_variant,
                        segments=request_segments,
                        before_run=self.services.release_model,
                        runtime_cache=self.services.runtime_probe_cache,
                    )
                )
                request_segments = forced.get("resolvedSegments", request_segments)
                _validate_forced_alignment_segments(request_segments, len(self.request.text.sources), self.request.context.endpoint_duration_ms)
                break
            except worker_protocol.MissingAlignmentAudio:
                if restored_audio:
                    raise
                restored_audio = True
                audio, identity, _ = self.request.audio.get_endpoint_audio(route_id)
                if audio is None or not audio.is_file():
                    raise worker_protocol.MissingAlignmentAudio('Restored Wav2Vec2 audio is unavailable')
            except worker_protocol.WorkerProcessError as error:
                match = re.fullmatch(
                    r"wav2vec2-segment-(?:too-short:(\d+)|boundary-guard:(\d+):(start|end))",
                    error.code,
                )
                if match is None:
                    raise
                request_segments = _merge_short_bootstrap_segment(
                    request_segments, int(match.group(1) or match.group(2)),
                    boundary=match.group(3),
                )
        self.services.progress.state["pinnedModels"].update(
            wav2vec2ModelSha256=model_identity["modelSha256"],
            wav2vec2SnapshotManifestSha256=model_identity[
                "snapshotManifestSha256"
            ],
        )
        return (
            self.services.workers.verifier_candidates(
                forced,
                self.request.text.display_word_lines,
                expected_route=expected_route,
                duration_ms=self.request.context.duration_ms,
                request_duration_ms=self.request.context.endpoint_duration_ms,
            ),
            reused,
            request_segments,
        )

    def evaluate_endpoint_route_pass(self,
        route_id: str, *, use_timing_seed: bool = False,
    ) -> tuple[bool, EndpointSelectionResult]:
        if self.cumulative_endpoint_result is not None:
            self.require_timing_recovery(self.cumulative_endpoint_result)
        forced_endpoints, mms_reused = self.load_baseline_mms(route_id, use_timing_seed=use_timing_seed)
        _validate_occurrence_identity(self.request.text.source_occurrences)
        if route_id in ROFORMER_ROUTES:
            self.services.events.roformer(route_id, cache_reused=mms_reused)
        mix_result = _refine_public_timings(
            forced_endpoints,
            self.request.evidence.verifier_candidates,
            self.request.evidence.reviewed_lrc_starts,
            verifier_family=self.request.evidence.verifier_family,
            free_family_candidates=self.request.evidence.free_family_candidates,
            mms_route=self.request.evidence.route_names[route_id],
            include_unresolved=True,
        )
        self.require_timing_recovery(mix_result)
        if use_timing_seed:
            # Hints only constrain this native MMS attempt, never its verifier.
            ((route_timings, route_summary), _, independent_consensus_reused) = self.apply_independent_consensus(mix_result)
            route_summary.update(
                mixVerifierPublishedLineCount=mix_result[1]["lineReadyCount"],
                routeLocalVerifierAttempted=False,
                routeLocalVerifierFilledLineCount=0,
                baselinePublishedLineCount=mix_result[1]["lineReadyCount"],
                anchoredFilledLineCount=0,
                bootstrapAttempted=False,
            )
            route_reused = self.request.evidence.verifier_reused and mms_reused and independent_consensus_reused
            self.services.progress.endpoint_attempts.append({
                "routeId": f"{route_id}:prior-recording-seed",
                "reused": route_reused,
                "endpointEvidence": route_summary,
            })
            route_attempt_index = len(self.services.progress.endpoint_attempts) - 1
            self.services.progress.state.update(
                endpointEvidence=route_summary,
                endpointEvidenceReused=route_reused,
                endpointRouteAttempts=list(self.services.progress.endpoint_attempts),
                selectedEndpointAttemptIndex=route_attempt_index,
            )
            self.services.progress.checkpoint()
            return self._select_endpoint_result(route_id, route_timings, route_summary,
                route_reused, route_attempt_index)
        baseline_result = mix_result
        verifier_for_route = self.request.evidence.verifier_candidates
        route_local_candidates: tuple[
            dict[str, Any] | None, ...
        ] | None = None
        route_local_reused = True
        route_local_attempted = False
        if (
            self.request.evidence.verifier_family == WAV2VEC2_FAMILY
            and (self.services.workers.needs_local_verifier(
                mix_result[1], self.request.text.source_occurrences)
                or any(row["publishDecision"].get("code") in _WORD_TIMING_RECOVERY_REASONS
                       for row in mix_result[1]["occurrenceCandidates"]
                       if row["publishDecision"]["result"] == "line-only"))
        ):
            route_local_attempted = True
            try:
                route_local_candidates, route_local_reused, _ = (
                    self.load_route_wav2vec2(
                        route_id,
                        request_variant="baseline",
                        segments=self.request.evidence.baseline_segments,
                    )
                )
            except (LyricsContentBlocker, WorkerRetirementError, AcousticBudgetExceeded):
                raise
            except (
                OSError,
                RuntimeError,
                TimeoutError,
                TimeoutExpired,
                ValueError,
            ) as error:
                _require_recoverable_endpoint_failure(error)
                self.services.events.failure(
                    "endpoint-route-verifier",
                    route_id,
                    self.route_diagnostic(error, 'endpoint-route-verifier', route_id),
                )
                route_local_reused = False
            else:
                verifier_for_route = _ordered_verifier_fallback_candidates(
                    mix_result[1],
                    self.request.evidence.verifier_candidates,
                    route_local_candidates,
                    self.request.text.source_occurrences,
                )
                local_result = _refine_public_timings(
                    forced_endpoints,
                    verifier_for_route,
                    self.request.evidence.reviewed_lrc_starts,
                    verifier_family=self.request.evidence.verifier_family,
                    free_family_candidates=self.request.evidence.free_family_candidates,
                    mms_route=self.request.evidence.route_names[route_id],
                    allowed_wav2vec2_routes=frozenset(
                        (
                            WAV2VEC2_ROUTE,
                            WAV2VEC2_ROUTES_BY_AUDIO_VIEW[route_id],
                        )
                    ),
                    include_unresolved=True,
                )
                baseline_result = _merge_endpoint_route_passes(mix_result, local_result)
        route_timings, route_summary = baseline_result
        pre_consensus_ready = route_summary["lineReadyCount"]
        route_local_filled = 0
        if route_local_candidates is not None:
            mix_occurrences = mix_result[1]["occurrenceCandidates"]
            route_occurrences = route_summary['occurrenceCandidates']
            route_local_filled = sum(
                before["publishDecision"]["result"] == "unresolved"
                and after["publishDecision"]["result"] != "unresolved"
                for before, after in zip(
                    mix_occurrences, route_occurrences
                )
            )
        (
            (route_timings, route_summary),
            _,
            independent_consensus_reused,
        ) = self.apply_independent_consensus(baseline_result)
        opening_baseline = route_summary["occurrenceCandidates"][0]
        route_summary.update(
            mixVerifierPublishedLineCount=mix_result[1]["lineReadyCount"],
            routeLocalVerifierAttempted=route_local_attempted,
            routeLocalVerifierFilledLineCount=route_local_filled,
            baselinePublishedLineCount=pre_consensus_ready,
            anchoredFilledLineCount=0,
            bootstrapAttempted=False,
        )
        route_reused = (
            self.request.evidence.verifier_reused
            and mms_reused
            and route_local_reused
            and independent_consensus_reused
        )
        self.services.progress.endpoint_attempts.append(
            {
                "routeId": f"{route_id}:baseline",
                "reused": route_reused,
                "endpointEvidence": route_summary,
            }
        )
        route_attempt_index = len(self.services.progress.endpoint_attempts) - 1
        self.services.progress.state.update(
            endpointEvidence=route_summary,
            endpointEvidenceReused=route_reused,
            endpointRouteAttempts=list(self.services.progress.endpoint_attempts),
            selectedEndpointAttemptIndex=route_attempt_index,
        )
        self.services.progress.checkpoint()

        if (
            route_summary["resultClass"] != "committed-words"
            and (route_local_candidates is not None
                 or self.request.evidence.verifier_family == UNPROMPTED_WHISPER_FAMILY
                 or any(row["publishDecision"].get("code") in _WORD_TIMING_RECOVERY_REASONS
                        for row in route_summary["occurrenceCandidates"]
                        if row["publishDecision"]["result"] == "line-only"))
        ):
            unresolved_codes = {
                str(occurrence["publishDecision"].get("code", ""))
                for occurrence in route_summary["occurrenceCandidates"]
                if occurrence["publishDecision"]["result"] != "words"
            }
            self.require_timing_recovery((route_timings, route_summary))
            bootstrap_needed = any(
                code
                in {
                    "line-start-disagreement",
                    "line-end-disagreement",
                    "qualified-endpoint-contradiction",
                    "low-forced-line-support",
                } | _WORD_TIMING_RECOVERY_REASONS
                or code.startswith("missing-")
                for code in unresolved_codes
            )
            anchored_segments = (
                _build_bootstrap_segments(
                    len(self.request.text.sources),
                    self.request.context.endpoint_duration_ms,
                    tuple(forced_endpoints["lines"]),
                    verifier_for_route,
                    qualified_occurrences=route_summary["occurrenceCandidates"],
                )
                if bootstrap_needed
                else None
            )
            if anchored_segments is not None:
                route_summary["bootstrapAttempted"] = True
                self.services.progress.state.update(
                    endpointEvidence=route_summary,
                    endpointRouteAttempts=list(self.services.progress.endpoint_attempts),
                )
                self.services.progress.checkpoint()
                wav2vec2_anchored_reused = False
                try:
                    if self.request.evidence.verifier_family == UNPROMPTED_WHISPER_FAMILY:
                        # The independently decoded mix already fixes these
                        # occurrence windows; do not prompt or re-decode it.
                        anchored_verifier_candidates = verifier_for_route
                        wav2vec2_anchored_reused = self.request.evidence.verifier_reused
                    else:
                        (
                            anchored_verifier_candidates,
                            wav2vec2_anchored_reused,
                            anchored_segments,
                        ) = self.load_route_wav2vec2(
                            route_id,
                            request_variant="anchored",
                            segments=anchored_segments,
                        )
                except (LyricsContentBlocker, WorkerRetirementError):
                    raise
                except (
                    OSError,
                    RuntimeError,
                    TimeoutError,
                    TimeoutExpired,
                    ValueError,
                ) as error:
                    _require_recoverable_endpoint_failure(error)
                    self.services.events.failure(
                        "endpoint-bootstrap",
                        f"{route_id}:anchored-wav2vec2",
                        self.route_diagnostic(error, 'endpoint-bootstrap', f'{route_id}:anchored-wav2vec2'),
                    )
                    anchored_verifier_candidates = None
                (paired_anchored, anchored_segments, route_reused) = self.repair_anchored(
                    route_id, anchored_segments, anchored_verifier_candidates,
                    route_reused, wav2vec2_anchored_reused)
                if paired_anchored is not None:
                    forced_anchored, anchored_verifier_candidates = paired_anchored.require_plan(anchored_segments)
                    anchored_result = _refine_public_timings(
                        forced_anchored,
                        anchored_verifier_candidates,
                        self.request.evidence.reviewed_lrc_starts,
                        verifier_family=self.request.evidence.verifier_family,
                        free_family_candidates=self.request.evidence.free_family_candidates,
                        mms_route=self.request.evidence.route_names[route_id],
                        allowed_wav2vec2_routes=frozenset(
                            (WAV2VEC2_ROUTES_BY_AUDIO_VIEW[route_id],)
                        ),
                        include_unresolved=True,
                    )
                    if self.independent_witnesses:
                        anchored_result = _apply_independent_endpoint_consensus(
                            anchored_result, self.independent_witnesses, include_unresolved=True
                        )
                    route_timings, route_summary = _merge_endpoint_passes(
                        (route_timings, route_summary),
                        anchored_result,
                        stability_family=self.request.evidence.verifier_family,
                        repair_windows=anchored_segments,
                        include_unresolved=True,
                    )
                    route_summary.update(
                        mixVerifierPublishedLineCount=mix_result[1][
                            "lineReadyCount"
                        ],
                        routeLocalVerifierAttempted=route_local_candidates is not None,
                        routeLocalVerifierFilledLineCount=route_local_filled,
                    )
                    self.services.progress.endpoint_attempts.append(
                        {
                            "routeId": f"{route_id}:anchored",
                            "reused": route_reused,
                            "endpointEvidence": route_summary,
                        }
                    )
                    route_attempt_index = len(self.services.progress.endpoint_attempts) - 1
                    self.services.progress.state.update(
                        endpointEvidence=route_summary,
                        endpointEvidenceReused=route_reused,
                        endpointRouteAttempts=list(self.services.progress.endpoint_attempts),
                        selectedEndpointAttemptIndex=route_attempt_index,
                    )
                    self.services.progress.checkpoint()
        prior = self.cumulative_endpoint_result
        if (route_id == 'htdemucs-vocals' and (prior is None or
                prior[1]['occurrenceCandidates'][0]['publishDecision']['result'] == 'unresolved')
                and route_summary["occurrenceCandidates"][0]["publishDecision"]["result"] == 'unresolved'):
            try:
                recovered = self.assess_opening_retry(forced_endpoints, opening_baseline, verifier_for_route,
                    (route_timings, route_summary))
            except (LyricsContentBlocker, WorkerRetirementError, AcousticBudgetExceeded):
                raise
            except (OSError, RuntimeError, TimeoutExpired, ValueError) as error:
                _require_recoverable_endpoint_failure(error)
                self.services.events.attempt('endpoint-vocal-entry', route_id, 'failed',
                    code=self.route_diagnostic(error, 'endpoint-vocal-entry', route_id))
                recovered = None
            if recovered is not None:
                route_timings, route_summary, reused = recovered
                route_reused = route_reused and reused
                self.services.progress.endpoint_attempts.append({'routeId': route_id + ':vocal-entry',
                    'reused': route_reused, 'endpointEvidence': route_summary})
                route_attempt_index = len(self.services.progress.endpoint_attempts) - 1
                self.services.progress.state.update(endpointEvidence=route_summary, endpointEvidenceReused=route_reused,
                    endpointRouteAttempts=list(self.services.progress.endpoint_attempts), selectedEndpointAttemptIndex=route_attempt_index)
                self.services.progress.checkpoint()
        return self._select_endpoint_result(route_id, route_timings, route_summary,
            route_reused, route_attempt_index)

    def _select_endpoint_result(self, route_id, route_timings, route_summary,
                                route_reused, route_attempt_index):
        route_result = (route_timings, route_summary)
        self.cumulative_endpoint_result = (
            route_result
            if self.cumulative_endpoint_result is None
            else _merge_endpoint_route_passes(
                self.cumulative_endpoint_result, route_result
            )
        )
        cumulative_timings, cumulative_summary = self.cumulative_endpoint_result
        if cumulative_summary.get("cumulativeRouteMerge"):
            self.services.progress.endpoint_attempts.append(
                {
                    "routeId": f"{route_id}:cumulative",
                    "reused": route_reused,
                    "endpointEvidence": cumulative_summary,
                }
            )
            route_attempt_index = len(self.services.progress.endpoint_attempts) - 1
            self.services.progress.state.update(
                endpointEvidence=cumulative_summary,
                endpointEvidenceReused=route_reused,
                endpointRouteAttempts=list(self.services.progress.endpoint_attempts),
                selectedEndpointAttemptIndex=route_attempt_index,
            )
            self.services.progress.checkpoint()
        result = EndpointSelectionResult.bind(cumulative_timings, cumulative_summary,
            route_reused, route_attempt_index, duration_ms=self.request.context.duration_ms,
            reviewed_tolerance_ms=WORD_START_AGREEMENT_MS)
        return cumulative_summary["resultClass"] == "committed-words", result

    def evaluate_endpoint_route(self, route_id: str) -> tuple[bool, EndpointSelectionResult]:
        if self.request.evidence.seeded_mms_segments is not None:
            try:
                resolved, result = self.evaluate_endpoint_route_pass(
                    route_id, use_timing_seed=True,
                )
            except (LyricsContentBlocker, WorkerRetirementError):
                raise
            except (worker_protocol.WorkerProcessError, OSError, ValueError, TimeoutError, TimeoutExpired) as error:
                _require_recoverable_endpoint_failure(error)
                self.services.events.attempt(
                    "endpoint-seed", route_id, "failed",
                    code=self.route_diagnostic(error, 'endpoint-seed', route_id),
                )
            else:
                if resolved:
                    return True, result
                self.require_timing_recovery((result.timings, result.summary))
        # A rejected hint never removes the original full-native attempt.
        resolved, result = self.evaluate_endpoint_route_pass(route_id)
        if not resolved:
            self.require_timing_recovery((result.timings, result.summary))
        return resolved, result


def select_free_candidates(original, alignment, evidence_options, transcripts, labels, reused,
                           duration_ms, lexical_mapping):
    from stemwise_engine.lyrics_alignment import whisper_word_candidates
    if not (
        len(transcripts)
        == len(labels)
        == len(reused)
    ):
        raise ValueError("endpoint verifier transcript ledger disagrees")
    compatible_results = {
        label: result
        for label, result, _, _ in evidence_options
        if result.accepted
        and result.source_physical_indices
        == alignment.source_physical_indices
    }
    free_witness_inputs: dict[str, tuple[str, list[Any]]] = {}
    for transcript, label in zip(
        transcripts, labels
    ):
        family = _free_structure_witness_family(label)
        if (
            family is not None
            and family not in free_witness_inputs
            and label in compatible_results
        ):
            free_witness_inputs[family] = (label, transcript)
    free_family_candidates = {
        family: whisper_word_candidates(
            original["text"],
            alignment.source_physical_indices,
            [transcript],
            duration_ms,
            occurrence_mapping=lexical_mapping,
            transcript_labels=[label],
        )
        for family, (label, transcript) in free_witness_inputs.items()
    }
    return free_family_candidates
