"""Align timing evidence to the exact admitted lyric sequence without changing content."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import time
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Literal, Mapping, Sequence

from stemwise_engine.common import (
    atomic_write_bytes,
    atomic_write_json,
    heavy_worker_stage,
    parse_strict_json_bytes,
    read_bounded_regular_bytes,
    run_process_tree,
    validate_generated_path,
    validate_video_id,
)
from stemwise_engine.ctc_worker import execution_deadline
from stemwise_engine.provider_operations import optional_provider_stage, current_budget
from stemwise_engine.alignment_phases import ordered_endpoint_candidate_path as _ordered_endpoint_candidate_path
from stemwise_engine.contract import ContractError, MAX_JSON_BYTES
from stemwise_engine.lyrics_normalize import performance_lines as _physical_lines
from stemwise_engine.lyrics_tokenization import (
    TOKENIZATION_VERSION,
    display_words,
    lexical_authority_sha256,
    lexical_line_tokens,
)
from stemwise_engine.song_enrichment_v2 import validate_lyrics
try:
    from syncalong.align import _dp_align, _word_score
    from syncalong.lyrics import LyricLine, lyrics_prompt, parse_lyrics_text
    from syncalong.textnorm import normalize
    from syncalong.transcribe import WordTimestamp
except ModuleNotFoundError as error:
    if error.name != "syncalong" and not str(error.name).startswith("syncalong."):
        raise

    def _syncalong_required(*_args: Any, **_kwargs: Any) -> Any:
        raise RuntimeError("the dedicated lyrics-alignment runtime is required")

    class _SyncalongType:
        def __init__(self, *_args: Any, **_kwargs: Any) -> None:
            _syncalong_required()

        from_dict = classmethod(_syncalong_required)

    _dp_align = _word_score = lyrics_prompt = parse_lyrics_text = normalize = (
        _syncalong_required
    )
    LyricLine = WordTimestamp = _SyncalongType
ALIGNMENT_VERSION = 12
DEFAULT_MODEL = "large-v3"
DEFAULT_THRESHOLD = 64.0
MIN_SINGLE_LINE_REPEAT_WORDS = 6
LEXICAL_MAPPING_VERSION = 4
TRANSCRIPT_CACHE_VERSION = 3
_LEXICAL_MAPPING_CORE_KEYS = (
    "mappingVersion",
    "tokenizationVersion",
    "videoId",
    "lexicalAuthoritySha256",
    "authorityLines",
    "performedOccurrences",
)


@dataclass(frozen=True)
class QualityGate:
    min_lyric_word_coverage: float = 0.70
    min_direct_line_coverage: float = 0.65
    min_average_word_score: float = 82.0
    min_timed_span_ratio: float = 0.45
    min_transcript_words: int = 8
    strong_line_min_lyric_word_coverage: float = 0.58
    strong_line_min_direct_line_coverage: float = 0.80
    strong_line_min_average_word_score: float = 92.0
    strong_line_min_timed_span_ratio: float = 0.75
    compact_min_lyric_word_coverage: float = 0.72
    compact_min_transcript_word_coverage: float = 0.55
    compact_min_direct_line_coverage: float = 0.70
    compact_min_average_word_score: float = 96.0
    compact_min_timed_span_ratio: float = 0.05
    distributed_min_lyric_word_coverage: float = 0.65
    distributed_min_direct_line_coverage: float = 0.55
    distributed_min_average_word_score: float = 96.0
    distributed_min_timed_span_ratio: float = 0.75
    medium_min_lyric_word_coverage: float = 0.65
    medium_min_direct_line_coverage: float = 0.80
    medium_min_average_word_score: float = 94.0
    medium_min_timed_span_ratio: float = 0.60


@dataclass(frozen=True)
class AlignmentMetrics:
    lyric_word_count: int
    transcript_word_count: int
    matched_word_count: int
    directly_matched_line_count: int
    line_count: int
    lyric_word_coverage: float
    transcript_word_coverage: float
    direct_line_coverage: float
    average_word_score: float
    timed_span_ratio: float


@dataclass(frozen=True)
class AlignmentResult:
    accepted: bool
    reasons: tuple[str, ...]
    starts_ms: tuple[int, ...]
    source_physical_indices: tuple[int, ...]
    metrics: AlignmentMetrics


@dataclass(frozen=True)
class StructureWitnessMetrics:
    candidate_word_count: int
    transcript_word_count: int
    matched_word_count: int
    direct_line_count: int
    supported_line_count: int
    line_count: int
    candidate_word_coverage: float
    transcript_word_coverage: float
    direct_line_coverage: float
    supported_line_coverage: float
    average_word_score: float


@dataclass(frozen=True)
class StructureWitness:
    family: Literal["qwen3-asr"]
    route: Literal["qwen3-asr-unprompted-mix"]
    accepted: bool
    reasons: tuple[str, ...]
    source_physical_indices: tuple[int, ...]
    metrics: StructureWitnessMetrics


def _passes_quality_gate(metrics: AlignmentMetrics, gate: QualityGate) -> bool:
    standard = (
        metrics.lyric_word_coverage >= gate.min_lyric_word_coverage
        and metrics.direct_line_coverage >= gate.min_direct_line_coverage
        and metrics.average_word_score >= gate.min_average_word_score
        and metrics.timed_span_ratio >= gate.min_timed_span_ratio
    )
    strong_line = (
        metrics.lyric_word_coverage >= gate.strong_line_min_lyric_word_coverage
        and metrics.direct_line_coverage >= gate.strong_line_min_direct_line_coverage
        and metrics.average_word_score >= gate.strong_line_min_average_word_score
        and metrics.timed_span_ratio >= gate.strong_line_min_timed_span_ratio
    )
    compact = (
        metrics.lyric_word_coverage >= gate.compact_min_lyric_word_coverage
        and metrics.transcript_word_coverage
        >= gate.compact_min_transcript_word_coverage
        and metrics.direct_line_coverage >= gate.compact_min_direct_line_coverage
        and metrics.average_word_score >= gate.compact_min_average_word_score
        and metrics.timed_span_ratio >= gate.compact_min_timed_span_ratio
    )
    distributed = (
        metrics.lyric_word_coverage >= gate.distributed_min_lyric_word_coverage
        and metrics.direct_line_coverage >= gate.distributed_min_direct_line_coverage
        and metrics.average_word_score >= gate.distributed_min_average_word_score
        and metrics.timed_span_ratio >= gate.distributed_min_timed_span_ratio
    )
    medium = (
        metrics.lyric_word_coverage >= gate.medium_min_lyric_word_coverage
        and metrics.direct_line_coverage >= gate.medium_min_direct_line_coverage
        and metrics.average_word_score >= gate.medium_min_average_word_score
        and metrics.timed_span_ratio >= gate.medium_min_timed_span_ratio
    )
    return standard or strong_line or compact or distributed or medium


def _ratio(numerator: int | float, denominator: int | float) -> float:
    return float(numerator) / float(denominator) if denominator else 0.0


def _sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _sha256_text(text: str) -> str:
    return _sha256_bytes(text.encode("utf-8"))


def load_sidecar(path: Path, *, root: Path) -> tuple[dict[str, Any], bytes]:
    """Load only a strict v2 sidecar."""

    from stemwise_engine.lyrics_intake import require_content_review_clear
    require_content_review_clear(root, path.name.removesuffix('.lyrics.json'))
    raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=root)
    try:
        value = parse_strict_json_bytes(raw)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        raise ValueError(f"{path.name} is not valid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise ValueError(f"{path.name} does not contain a lyrics object")
    validate_lyrics(value)
    return value, raw


def canonical_language(language: str | None) -> str | None:
    if not isinstance(language, str):
        return None
    primary = language.strip().lower().split("-", 1)[0]
    if primary in {"", "mul", "und", "zxx"}:
        return None
    # Whisper accepts ISO-639-1 language codes.  Let it auto-detect uncommon
    # three-letter tags rather than silently selecting a wrong language.
    return primary if re.fullmatch(r"[a-z]{2}", primary) else None


def transcription_languages(original: dict[str, Any]) -> list[str | None]:
    """Try each declared component once, followed by multilingual auto-detection."""

    concrete = canonical_language(original.get("language"))
    if concrete:
        return [concrete]
    components = [
        canonical_language(value)
        for value in original.get("componentLanguages", [])
        if isinstance(value, str)
    ]
    components = [value for value in components if value]
    if not components:
        return [None]
    text = str(original.get("text") or "")

    def script_count(language: str) -> int:
        if language in {"ar", "fa", "ur"}:
            return sum(
                "\u0600" <= character <= "\u06ff"
                or "\u0750" <= character <= "\u077f"
                or "\u08a0" <= character <= "\u08ff"
                for character in text
            )
        if language == "hi":
            return sum("\u0900" <= character <= "\u097f" for character in text)
        if language == "bn":
            return sum("\u0980" <= character <= "\u09ff" for character in text)
        if language == "ru":
            return sum("\u0400" <= character <= "\u04ff" for character in text)
        latin = sum(
            ("A" <= character <= "Z")
            or ("a" <= character <= "z")
            or ("\u00c0" <= character <= "\u024f")
            for character in text
        )
        if language == "tr" and any(character in text.casefold() for character in "çğıöşü"):
            latin += 25
        return latin

    ranked = sorted(
        enumerate(components),
        key=lambda item: (script_count(item[1]), -item[0]),
        reverse=True,
    )
    result: list[str | None] = []
    for _, language in ranked:
        if language not in result:
            result.append(language)
    result.append(None)
    return result


def _plain_original(lyrics: dict[str, Any]) -> dict[str, Any]:
    tracks = lyrics.get("tracks")
    originals = [track for track in tracks or [] if track.get("kind") == "original"]
    if len(originals) != 1:
        raise ValueError("alignment requires exactly one original lyrics track")
    original = originals[0]
    if original.get("format") != "plain" or not isinstance(original.get("text"), str):
        raise ValueError("the original lyrics track is not approved plain text")
    return original


def _mapping_id(
    kind: str,
    video_id: str,
    authority_sha256: str,
    *indices: int,
) -> str:
    identity = "\0".join(
        (kind, video_id, authority_sha256, *(str(index) for index in indices))
    )
    return f"{kind}-{_sha256_text(identity)[:24]}"


def build_lexical_occurrence_mapping(
    video_id: str,
    original: Mapping[str, Any],
    source_physical_indices: Sequence[int],
) -> dict[str, Any]:
    """Build the text-free stable-ID map for one performed line sequence."""

    video_id = validate_video_id(video_id)
    if original.get("format") != "plain" or not isinstance(original.get("text"), str):
        raise ValueError("lexical occurrence mapping requires a plain original track")
    authority_sha256 = lexical_authority_sha256(original)
    physical = _physical_lines(original["text"])
    authority_lines: list[dict[str, Any]] = []
    for source, line in enumerate(physical):
        line_id = _mapping_id("line", video_id, authority_sha256, source)
        authority_lines.append(
            {
                "sourcePhysicalIndex": source,
                "lineId": line_id,
                "wordIds": [
                    _mapping_id("authority-word", video_id, authority_sha256, source, word)
                    for word, _ in enumerate(display_words(line))
                ],
            }
        )

    occurrences: list[dict[str, Any]] = []
    for performed, source in enumerate(source_physical_indices):
        if (
            type(source) is not int
            or source < 0
            or source >= len(authority_lines)
            or not authority_lines[source]["wordIds"]
        ):
            raise ValueError("performed occurrence references an invalid authority line")
        line = authority_lines[source]
        occurrence_id = _mapping_id(
            "occurrence", video_id, authority_sha256, performed, source
        )
        occurrences.append(
            {
                "performedIndex": performed,
                "sourcePhysicalIndex": source,
                "lineId": line["lineId"],
                "occurrenceId": occurrence_id,
                "words": [
                    {
                        "authorityWordId": authority_word_id,
                        "occurrenceWordId": _mapping_id(
                            "occurrence-word",
                            video_id,
                            authority_sha256,
                            performed,
                            source,
                            word,
                        ),
                    }
                    for word, authority_word_id in enumerate(line["wordIds"])
                ],
            }
        )
    if not occurrences:
        raise ValueError("performed occurrence mapping cannot be empty")
    return {
        "mappingVersion": LEXICAL_MAPPING_VERSION,
        "tokenizationVersion": TOKENIZATION_VERSION,
        "videoId": video_id,
        "lexicalAuthoritySha256": authority_sha256,
        "authorityLines": authority_lines,
        "performedOccurrences": occurrences,
    }


def _lexical_mapping_core(mapping: Mapping[str, Any]) -> dict[str, Any]:
    try:
        return {key: copy.deepcopy(mapping[key]) for key in _LEXICAL_MAPPING_CORE_KEYS}
    except (KeyError, TypeError) as error:
        raise ValueError("lexical occurrence mapping is malformed") from error


def build_revision_mapping(video_id, previous_original, current_original,
                           previous_indices, current_indices, *, budget=None):
    """Bind unique unchanged occurrences across revisions without carrying approvals."""
    from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
    from stemwise_engine.lyrics_tokenization import optimal_edit_owners
    from stemwise_engine.provenance import content_assessment_identity
    old = build_lexical_occurrence_mapping(video_id, previous_original, previous_indices)
    new = build_lexical_occurrence_mapping(video_id, current_original, current_indices)
    record = {'version': 1, 'previousMapping': lexical_mapping_identity(old),
        'currentMapping': lexical_mapping_identity(new), 'complete': True, 'regions': [],
        'comparisonPolicy': content_assessment_identity()}
    metadata = ('id', 'language', 'script', 'componentLanguages')
    if any(previous_original.get(key) != current_original.get(key) for key in metadata):
        return record
    old_lines = lexical_line_tokens(previous_original['text'], language=previous_original.get('language'))
    new_lines = lexical_line_tokens(current_original['text'], language=current_original.get('language'))
    expected = [old_lines[index] for index in previous_indices]
    actual = [new_lines[index] for index in current_indices]
    try:
        graph = optimal_edit_owners(expected, actual, budget=budget)
    except AcousticBudgetExceeded:
        record['complete'] = False
        return record
    reverse_counts = [0]*len(actual)
    for source, targets in enumerate(graph['matches']):
        for target in targets:
            reverse_counts[target] += 1
    pairs = {source: next(iter(targets)) for source, targets in enumerate(graph['matches'])
        if len(targets) == 1 and source not in graph['deleted'] | graph['substituted']
        and reverse_counts[next(iter(targets))] == 1
        and next(iter(targets)) not in graph['affectedActual']}
    for source, target in sorted(pairs.items()):
        old_occurrence, new_occurrence = old['performedOccurrences'][source], new['performedOccurrences'][target]
        context_unchanged = all(
            (source+step < 0 and target+step < 0)
            or (source+step >= len(expected) and target+step >= len(actual))
            or pairs.get(source+step) == target+step for step in (-1, 1))
        record['regions'].append({'previousPerformedIndex': source, 'currentPerformedIndex': target,
            'previousOccurrenceId': old_occurrence['occurrenceId'],
            'currentOccurrenceId': new_occurrence['occurrenceId'],
            'wordBindings': [{'previous': before['occurrenceWordId'], 'current': after['occurrenceWordId']}
                for before, after in zip(old_occurrence['words'], new_occurrence['words'])],
            'submittedUnitsUnchanged': True, 'neighborContextUnchanged': context_unchanged,
            'assessmentState': 'requires-reassessment'})
    return record


def lexical_mapping_identity(mapping: Mapping[str, Any]) -> dict[str, Any]:
    """Return the content identity shared by caches, state, and reports."""

    core = _lexical_mapping_core(mapping)
    if (
        core["mappingVersion"] != LEXICAL_MAPPING_VERSION
        or core["tokenizationVersion"] != TOKENIZATION_VERSION
        or re.fullmatch(r"[0-9a-f]{64}", str(core["lexicalAuthoritySha256"])) is None
    ):
        raise ValueError("lexical occurrence mapping identity is invalid")
    raw = json.dumps(
        core,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return {
        "mappingVersion": LEXICAL_MAPPING_VERSION,
        "tokenizationVersion": TOKENIZATION_VERSION,
        "lexicalAuthoritySha256": core["lexicalAuthoritySha256"],
        "mappingSha256": _sha256_bytes(raw),
    }




def deduplicated_lyrics_prompt(text: str, *, max_chars: int = 600) -> str:
    """Build a decoder prompt with repeated approved lines represented once."""

    unique: list[LyricLine] = []
    seen: set[tuple[str, ...]] = set()
    for line in parse_lyrics_text(text):
        if line.is_blank:
            continue
        signature = tuple(line.words)
        if not signature or signature in seen:
            continue
        seen.add(signature)
        unique.append(line)
    return lyrics_prompt(unique, max_chars=max_chars)


def _canonical_stanzas(lines: Sequence[str]) -> list[tuple[int, ...]]:
    stanzas: list[tuple[int, ...]] = []
    current: list[int] = []
    for index, line in enumerate(lines):
        if line.strip():
            current.append(index)
        elif current:
            stanzas.append(tuple(current))
            current = []
    if current:
        stanzas.append(tuple(current))
    return stanzas


def _stanza_candidates(
    lines: Sequence[str], *, excluded_sources: set[int] | None = None,
    partial: bool = False,
) -> list[tuple[int, ...]]:
    stanzas = _canonical_stanzas(lines)
    if partial:
        # ponytail: only two-line interior repeats; extend when a longer real
        # partial-stanza repeat requires it. Whole stanzas remain the first pass.
        stanzas = [stanza[i:i+2] for stanza in stanzas if len(stanza) > 2
                   for i in range(len(stanza)-1)]
    excluded_sources = excluded_sources or set()

    # Identical stanzas are one candidate.  The first canonical occurrence is
    # retained so companion tracks can reuse its exact physical line indices.
    unique: list[tuple[int, ...]] = []
    seen: set[tuple[str, ...]] = set()
    for stanza in stanzas:
        if any(index in excluded_sources for index in stanza):
            continue
        signature = tuple(normalize(lines[index]) for index in stanza)
        word_count = sum(len(value.split()) for value in signature)
        # A long one-line refrain is still a complete stanza and may be
        # performed repeatedly.  Short single lines stay excluded because
        # their ASR evidence is too easy to confuse with nearby phrases.
        if word_count < (MIN_SINGLE_LINE_REPEAT_WORDS if len(stanza) == 1 else 8) or signature in seen:
            continue
        seen.add(signature)
        unique.append(stanza)
    return unique


def _lyric_lines_for_sources(
    canonical_lines: Sequence[str], sources: Sequence[int]
) -> list[LyricLine]:
    result: list[LyricLine] = []
    for index, source in enumerate(sources):
        raw = canonical_lines[source].strip()
        words = normalize(raw).split()
        result.append(LyricLine(index=index, raw=raw, words=words, is_blank=not words))
    return result


@dataclass(frozen=True)
class _Mapping:
    word_mapping: dict[int, int]
    flat_words: tuple[str, ...]
    word_to_line: tuple[int, ...]


def _mapping(
    lyric_lines: Sequence[LyricLine],
    transcript: Sequence[WordTimestamp],
    threshold: float,
) -> _Mapping:
    flat: list[str] = []
    word_to_line: list[int] = []
    for line_index, line in enumerate(lyric_lines):
        for word in line.words:
            flat.append(word)
            word_to_line.append(line_index)
    found = _dp_align(flat, list(transcript), threshold)
    return _Mapping(found, tuple(flat), tuple(word_to_line))


def _passes_structure_quality_gate(
    metrics: StructureWitnessMetrics, gate: QualityGate
) -> bool:
    if metrics.transcript_word_coverage < gate.compact_min_transcript_word_coverage:
        return False
    lexical = (
        (gate.min_lyric_word_coverage, gate.min_direct_line_coverage, gate.min_average_word_score),
        (
            gate.strong_line_min_lyric_word_coverage,
            gate.strong_line_min_direct_line_coverage,
            gate.strong_line_min_average_word_score,
        ),
        (
            gate.distributed_min_lyric_word_coverage,
            gate.distributed_min_direct_line_coverage,
            gate.distributed_min_average_word_score,
        ),
        (
            gate.medium_min_lyric_word_coverage,
            gate.medium_min_direct_line_coverage,
            gate.medium_min_average_word_score,
        ),
    )
    return any(
        metrics.candidate_word_coverage >= word_coverage
        and metrics.supported_line_coverage >= line_coverage
        and metrics.average_word_score >= average_score
        for word_coverage, line_coverage, average_score in lexical
    ) or (
        metrics.candidate_word_coverage >= gate.compact_min_lyric_word_coverage
        and metrics.supported_line_coverage >= gate.compact_min_direct_line_coverage
        and metrics.average_word_score >= gate.compact_min_average_word_score
    )


def score_structure_witness(
    text: str,
    source_physical_indices: Sequence[int],
    transcript_text: str,
    *,
    threshold: float = DEFAULT_THRESHOLD,
    gate: QualityGate = QualityGate(),
) -> StructureWitness:
    """Score one fixed performed sequence against an unprompted transcript."""

    canonical = _physical_lines(text)
    sources = tuple(source_physical_indices)
    if not sources or any(
        isinstance(source, bool)
        or not isinstance(source, int)
        or source < 0
        or source >= len(canonical)
        or not canonical[source].strip()
        for source in sources
    ):
        raise ValueError("structure witness requires valid non-blank source lines")
    if sources != tuple(index for index, line in enumerate(canonical) if line):
        raise ValueError("structure witness requires the exact approved physical-line sequence")

    lines = _lyric_lines_for_sources(canonical, sources)
    transcript_words = normalize(transcript_text).split()
    transcript = [
        WordTimestamp(
            word=word,
            raw=word,
            start=float(index),
            end=float(index + 1),
        )
        for index, word in enumerate(transcript_words)
    ]
    mapped = _mapping(lines, transcript, threshold)
    directly_matched_lines = {
        mapped.word_to_line[lyric_index] for lyric_index in mapped.word_mapping
    }
    # This scorer evaluates one fixed occurrence sequence. Repeated lines are
    # positionally meaningful here; ambiguity is rejected by the caller when
    # more than one complete candidate sequence passes.
    supported_lines = tuple(sorted(directly_matched_lines))
    scores = [
        _word_score(mapped.flat_words[lyric_index], transcript[transcript_index].word)
        for lyric_index, transcript_index in mapped.word_mapping.items()
    ]
    matched = len(mapped.word_mapping)
    metrics = StructureWitnessMetrics(
        candidate_word_count=len(mapped.flat_words),
        transcript_word_count=len(transcript),
        matched_word_count=matched,
        direct_line_count=len(directly_matched_lines),
        supported_line_count=len(supported_lines),
        line_count=len(lines),
        candidate_word_coverage=_ratio(matched, len(mapped.flat_words)),
        transcript_word_coverage=_ratio(
            len(set(mapped.word_mapping.values())), len(transcript)
        ),
        direct_line_coverage=_ratio(len(directly_matched_lines), len(lines)),
        supported_line_coverage=_ratio(len(supported_lines), len(lines)),
        average_word_score=sum(scores) / len(scores) if scores else 0.0,
    )
    passes_quality = _passes_structure_quality_gate(metrics, gate)
    reasons: list[str] = []
    if len(transcript) < min(gate.min_transcript_words, len(mapped.flat_words)):
        reasons.append("too-few-transcript-words")
    if not passes_quality:
        reasons.append("lexical-quality-gate-failed")
    # Global coverage cannot establish a small repeat count. Fully recognized
    # approved units supply a lower bound, including identical physical lines.
    def count_unit(words, unit):
        count, index = 0, 0
        while index <= len(words) - len(unit):
            if tuple(words[index:index + len(unit)]) == unit:
                count += 1
                index += len(unit)
            else:
                index += 1
        return count

    # A complete line can repeat independently of the rest of its stanza.
    # Use the same minimum as a one-line stanza, without creating new proposals.
    single_line_units = [(index,) for index, line in enumerate(canonical)
                         if len(normalize(line).split()) >= MIN_SINGLE_LINE_REPEAT_WORDS]
    for unit_sources in (*_stanza_candidates(canonical),
                         *_stanza_candidates(canonical, partial=True),
                         *single_line_units):
        unit = tuple(word for source in unit_sources
                     for word in normalize(canonical[source]).split())
        if count_unit(transcript_words, unit) > count_unit(mapped.flat_words, unit):
            reasons.append("unaccounted-complete-repetition")
            break
    return StructureWitness(
        family="qwen3-asr",
        route="qwen3-asr-unprompted-mix",
        accepted=not reasons,
        reasons=tuple(reasons),
        source_physical_indices=sources,
        metrics=metrics,
    )


def whisper_word_candidates(
    text: str,
    source_physical_indices: Sequence[int],
    transcripts: Sequence[Sequence[WordTimestamp]],
    duration_ms: int,
    *,
    occurrence_mapping: Mapping[str, Any],
    transcript_labels: Sequence[str],
    threshold: float = DEFAULT_THRESHOLD,
) -> tuple[dict[str, Any] | None, ...]:
    """Map independent Whisper line boundaries and optional complete words.

    Recognition remains timing evidence only: emitted ranges always point into
    the approved display line. Missing middle words omit the whole word array
    while independently mapped first/last words can still verify line bounds.
    """

    if len(transcript_labels) != len(transcripts) or any(
        not isinstance(label, str) or not label for label in transcript_labels
    ):
        raise ValueError("Whisper evidence route labels are invalid")
    occurrences = _lexical_mapping_core(occurrence_mapping)["performedOccurrences"]
    if len(occurrences) != len(source_physical_indices):
        raise ValueError("Whisper evidence does not match the occurrence mapping")
    canonical = _physical_lines(text)
    lines: list[LyricLine] = []
    groups: list[list[tuple[tuple[int, int], tuple[int, ...]]]] = []
    flat_index = 0
    for line_index, source in enumerate(source_physical_indices):
        occurrence = occurrences[line_index]
        if (
            occurrence.get("performedIndex") != line_index
            or occurrence.get("sourcePhysicalIndex") != source
            or not isinstance(occurrence.get("occurrenceId"), str)
            or not isinstance(occurrence.get("words"), list)
        ):
            raise ValueError("Whisper evidence has a stale occurrence mapping")
        raw = canonical[source]
        line_words: list[str] = []
        line_groups: list[tuple[tuple[int, int], tuple[int, ...]]] = []
        for start_utf16, end_utf16, surface in display_words(raw):
            display_range = (start_utf16, end_utf16)
            normalized = tuple(normalize(surface).split())
            if not normalized:
                line_groups = []
                break
            indices = tuple(range(flat_index, flat_index + len(normalized)))
            flat_index += len(normalized)
            line_words.extend(normalized)
            line_groups.append((display_range, indices))
        if not line_groups:
            return tuple(None for _ in source_physical_indices)
        if len(line_groups) != len(occurrence["words"]):
            raise ValueError("Whisper display words do not match the occurrence mapping")
        lines.append(LyricLine(index=line_index, raw=raw, words=line_words, is_blank=False))
        groups.append(line_groups)

    best: tuple[dict[str, Any] | None, ...] | None = None
    best_rank: tuple[int, int, float, float] | None = None
    for route, value in zip(transcript_labels, transcripts):
        if not value:
            continue
        transcript = list(value)
        mapped = _mapping(lines, transcript, threshold)
        candidates: list[dict[str, Any] | None] = []
        route_scores: list[float] = []
        complete_line_count = 0
        for line_index, line_groups in enumerate(groups):
            occurrence = occurrences[line_index]
            words: list[dict[str, Any] | None] = []
            scores: list[float] = []
            for word_index, (display_range, unit_indices) in enumerate(line_groups):
                if any(index not in mapped.word_mapping for index in unit_indices):
                    words.append(None)
                    continue
                transcript_indices = tuple(mapped.word_mapping[index] for index in unit_indices)
                if any(
                    right <= left
                    for left, right in zip(transcript_indices, transcript_indices[1:])
                ):
                    words.append(None)
                    continue
                start_ms = int(round(transcript[transcript_indices[0]].start * 1000.0))
                end_ms = int(round(transcript[transcript_indices[-1]].end * 1000.0))
                if not 0 <= start_ms < end_ms <= duration_ms:
                    words.append(None)
                    continue
                unit_scores = [
                    _word_score(mapped.flat_words[index], transcript[mapped.word_mapping[index]].word)
                    for index in unit_indices
                ]
                scores.extend(unit_scores)
                words.append(
                    {
                        "wordId": occurrence["words"][word_index]["occurrenceWordId"],
                        "startUtf16": display_range[0],
                        "endUtf16": display_range[1],
                        "startMs": start_ms,
                        "endMs": end_ms,
                    }
                )
            if not words or words[0] is None or words[-1] is None:
                candidates.append(None)
                continue
            complete = all(word is not None for word in words)
            candidates.append(
                {
                    "lineId": occurrences[line_index]["occurrenceId"],
                    "route": route,
                    "startMs": words[0]["startMs"],
                    "endMs": words[-1]["endMs"],
                    "words": tuple(words) if complete else None,
                }
            )
            route_scores.extend(scores)
            complete_line_count += int(complete)
        rank = (
            sum(candidate is not None for candidate in candidates),
            complete_line_count,
            min(route_scores) if route_scores else 0.0,
            sum(route_scores) / len(route_scores) if route_scores else 0.0,
        )
        if best_rank is None or rank > best_rank:
            best = tuple(candidates)
            best_rank = rank
    return best or tuple(None for _ in lines)


def _direct_line_timestamps(
    mapping: _Mapping,
    transcript: Sequence[WordTimestamp],
) -> dict[int, float]:
    starts: dict[int, float] = {}
    for lyric_index, transcript_index in sorted(mapping.word_mapping.items()):
        line_index = mapping.word_to_line[lyric_index]
        value = transcript[transcript_index].start
        if line_index not in starts or value < starts[line_index]:
            starts[line_index] = value
    return starts


def _fill_line_timestamps(
    lines: Sequence[LyricLine],
    direct: dict[int, float],
    transcript: Sequence[WordTimestamp],
    duration_ms: int,
) -> list[int]:
    if not direct:
        return []
    duration_seconds = duration_ms / 1000.0
    starts: list[float | None] = [direct.get(index) for index in range(len(lines))]
    anchors = sorted(direct)

    # Interpolate internal gaps by lyric word weight, not by arbitrary line
    # count.  This keeps longer lines from being allocated implausibly little
    # time while preserving the exact matched anchors.
    for left, right in zip(anchors, anchors[1:]):
        if right - left <= 1:
            continue
        weights = [max(1, len(lines[index].words)) for index in range(left, right)]
        total = sum(weights)
        elapsed = direct[right] - direct[left]
        cumulative = 0
        for offset, index in enumerate(range(left + 1, right), start=0):
            cumulative += weights[offset]
            starts[index] = direct[left] + elapsed * cumulative / total

    first = anchors[0]
    if first:
        weights = [max(1, len(lines[index].words)) for index in range(first)]
        estimated = sum(weights) / 2.5
        start_at = max(0.0, direct[first] - estimated)
        total = max(sum(weights), 1)
        cumulative = 0
        for index, weight in enumerate(weights):
            starts[index] = start_at + (direct[first] - start_at) * cumulative / total
            cumulative += weight

    last = anchors[-1]
    if last < len(lines) - 1:
        right_limit = min(duration_seconds - 0.001, max(transcript[-1].end, direct[last] + 0.5))
        if right_limit <= direct[last]:
            right_limit = duration_seconds - 0.001
        weights = [max(1, len(lines[index].words)) for index in range(last + 1, len(lines))]
        total = max(sum(weights), 1)
        cumulative = 0
        for offset, index in enumerate(range(last + 1, len(lines))):
            cumulative += weights[offset]
            starts[index] = direct[last] + (right_limit - direct[last]) * cumulative / total

    milliseconds: list[int] = []
    previous = -1
    for value in starts:
        if value is None:
            return []
        current = max(0, int(round(value * 1000.0)))
        if current <= previous:
            current = previous + 1
        if current >= duration_ms:
            return []
        milliseconds.append(current)
        previous = current
    return milliseconds


def _quality_reasons(
    metrics: AlignmentMetrics,
    *,
    starts_ms: Sequence[int],
    line_count: int,
    gate: QualityGate,
) -> tuple[str, ...]:
    passes_quality_gate = _passes_quality_gate(metrics, gate)
    reasons: list[str] = []
    if metrics.transcript_word_count < min(gate.min_transcript_words, metrics.lyric_word_count):
        reasons.append("too-few-transcript-words")
    if not passes_quality_gate:
        compact_except_transcript = (
            metrics.lyric_word_coverage >= gate.compact_min_lyric_word_coverage
            and metrics.direct_line_coverage >= gate.compact_min_direct_line_coverage
            and metrics.average_word_score >= gate.compact_min_average_word_score
            and metrics.timed_span_ratio >= gate.compact_min_timed_span_ratio
        )
        if (
            compact_except_transcript
            and metrics.transcript_word_coverage
            < gate.compact_min_transcript_word_coverage
        ):
            reasons.append("low-transcript-word-coverage")
        else:
            if metrics.lyric_word_coverage < gate.min_lyric_word_coverage:
                reasons.append("low-lyric-word-coverage")
            if metrics.direct_line_coverage < gate.min_direct_line_coverage:
                reasons.append("low-direct-line-coverage")
            if metrics.average_word_score < gate.min_average_word_score:
                reasons.append("low-average-word-score")
            if metrics.timed_span_ratio < gate.min_timed_span_ratio:
                reasons.append("short-timed-span")
    if len(starts_ms) != line_count:
        reasons.append("could-not-produce-bounded-monotonic-ms")
    return tuple(reasons)


def _align_single_for_sources(
    canonical_lines: Sequence[str],
    initial_sources: Sequence[int],
    transcript: Sequence[WordTimestamp],
    duration_ms: int,
    *,
    threshold: float,
    gate: QualityGate,
) -> AlignmentResult:
    sources = list(initial_sources)
    lines = _lyric_lines_for_sources(canonical_lines, sources)
    mapped = _mapping(lines, transcript, threshold)
    direct = _direct_line_timestamps(mapped, transcript)
    starts_ms = _fill_line_timestamps(lines, direct, transcript, duration_ms) if transcript else []

    matched = len(mapped.word_mapping)
    scores = [
        _word_score(mapped.flat_words[lyric_index], transcript[transcript_index].word)
        for lyric_index, transcript_index in mapped.word_mapping.items()
    ]
    direct_times = sorted(direct.values())
    timed_span_ratio = (
        _ratio(direct_times[-1] - direct_times[0], duration_ms / 1000.0)
        if len(direct_times) >= 2
        else _matched_word_span(mapped, transcript, duration_ms) if len(direct) == 1 and len(lines) == 1 else 0.0
    )
    metrics = AlignmentMetrics(
        lyric_word_count=len(mapped.flat_words),
        transcript_word_count=len(transcript),
        matched_word_count=matched,
        directly_matched_line_count=len(direct),
        line_count=len(lines),
        lyric_word_coverage=_ratio(matched, len(mapped.flat_words)),
        transcript_word_coverage=_ratio(len(set(mapped.word_mapping.values())), len(transcript)),
        direct_line_coverage=_ratio(len(direct), len(lines)),
        average_word_score=sum(scores) / len(scores) if scores else 0.0,
        timed_span_ratio=timed_span_ratio,
    )
    reasons = _quality_reasons(
        metrics, starts_ms=starts_ms, line_count=len(lines), gate=gate
    )
    return AlignmentResult(
        accepted=not reasons,
        reasons=reasons,
        starts_ms=tuple(starts_ms),
        source_physical_indices=tuple(sources),
        metrics=metrics,
    )


def _matched_word_span(mapped, transcript, duration_ms: int) -> float:
    words = [transcript[index] for index in set(mapped.word_mapping.values())]
    if not words:
        return 0.0
    return _ratio(max(word.end for word in words)-min(word.start for word in words), duration_ms/1000.0)


def _primitive_repeated_line_unit(
    canonical_lines: Sequence[str], stanza: Sequence[int]
) -> tuple[int, int] | None:
    """Return (unit line count, written repetitions) for an exact line unit.

    This is intentionally textual and exact after the same normalization used
    by alignment.  It never treats merely similar chorus lines as removable.
    """

    signature = tuple(normalize(canonical_lines[source]) for source in stanza)
    for unit_line_count in range(1, len(stanza) // 2 + 1):
        if len(stanza) % unit_line_count:
            continue
        repeat_count = len(stanza) // unit_line_count
        unit = signature[:unit_line_count]
        if repeat_count < 2 or unit * repeat_count != signature:
            continue
        word_count = sum(len(value.split()) for value in unit)
        minimum_words = 8 if unit_line_count == 1 else 6
        if word_count >= minimum_words:
            return unit_line_count, repeat_count
    return None


def internal_repeat_structure_kind(text: str) -> str:
    """Classify exact internal repeat units without guessing mixed structures.

    ``collapsible`` is deliberately narrow: at least two stanzas contain the
    same exact substantial unit repeated the same number of times, and no
    stanza contains that unit in a different (single or differently repeated)
    form.  Mixed/subset and independent repeat groups are ``unsupported`` so
    callers can fail closed instead of silently applying an all-or-nothing
    rewrite.  Text with no substantial exact internal unit is ``none``.
    """

    canonical_lines = _physical_lines(text)
    stanzas = _canonical_stanzas(canonical_lines)
    repeated: list[tuple[int, tuple[str, ...], int, int]] = []
    stanza_signatures = [
        tuple(normalize(canonical_lines[source]) for source in stanza)
        for stanza in stanzas
    ]
    for stanza_index, stanza in enumerate(stanzas):
        primitive = _primitive_repeated_line_unit(canonical_lines, stanza)
        if primitive is None:
            continue
        unit_line_count, repeat_count = primitive
        repeated.append(
            (
                stanza_index,
                stanza_signatures[stanza_index][:unit_line_count],
                unit_line_count,
                repeat_count,
            )
        )
    if not repeated:
        return "none"

    unit_shapes = {(unit, unit_line_count, repeat_count) for _, unit, unit_line_count, repeat_count in repeated}
    if len(unit_shapes) != 1:
        return "unsupported"
    unit, unit_line_count, repeat_count = next(iter(unit_shapes))
    repeated_indices = {record[0] for record in repeated}
    if len(repeated_indices) < 2:
        return "unsupported"

    # A written single unit (or another repetition count) beside doubled
    # occurrences makes an all-occurrence collapse ambiguous.
    for stanza_index, signature in enumerate(stanza_signatures):
        if stanza_index in repeated_indices:
            continue
        if signature == unit:
            return "unsupported"
        if (
            len(signature) % unit_line_count == 0
            and signature == unit * (len(signature) // unit_line_count)
        ):
            return "unsupported"

    repeated_signatures = {stanza_signatures[index] for index in repeated_indices}
    if repeated_signatures != {unit * repeat_count}:
        return "unsupported"
    return "collapsible"


def align_known_structure(
    text: str,
    source_physical_indices: Sequence[int],
    transcript: Sequence[WordTimestamp],
    duration_ms: int,
    *,
    threshold: float = DEFAULT_THRESHOLD,
    gate: QualityGate = QualityGate(),
) -> AlignmentResult:
    """Evaluate one fixed performed sequence without adding/removing lines.

    This is used only to corroborate a structure proposed elsewhere.  It does
    not run the repeat or omission resolver and therefore cannot manufacture
    agreement by independently changing the supplied sequence.
    """

    canonical_lines = _physical_lines(text)
    sources = list(source_physical_indices)
    if not sources or any(
        type(source) is not int
        or source < 0
        or source >= len(canonical_lines)
        or not canonical_lines[source].strip()
        for source in sources
    ):
        raise ValueError("fixed lyric structure contains an invalid source line")
    return _align_single_for_sources(
        canonical_lines,
        sources,
        transcript,
        duration_ms,
        threshold=threshold,
        gate=gate,
    )


def _align_multi_for_sources(
    canonical_lines: Sequence[str],
    initial_sources: Sequence[int],
    usable: Sequence[Sequence[WordTimestamp]],
    duration_ms: int,
    *,
    threshold: float,
    gate: QualityGate,
) -> AlignmentResult:
    sources = list(initial_sources)
    lines = _lyric_lines_for_sources(canonical_lines, sources)
    mappings = [_mapping(lines, value, threshold) for value in usable]

    best_word_scores: dict[int, float] = {}
    candidates: list[tuple[int, float, float]] = []
    # candidate tuple: (line index, timestamp, weight)
    for transcript, mapped in zip(usable, mappings):
        per_line: dict[int, list[tuple[float, float]]] = {}
        for lyric_index, transcript_index in mapped.word_mapping.items():
            line_index = mapped.word_to_line[lyric_index]
            score = _word_score(mapped.flat_words[lyric_index], transcript[transcript_index].word)
            best_word_scores[lyric_index] = max(best_word_scores.get(lyric_index, 0.0), score)
            per_line.setdefault(line_index, []).append((transcript[transcript_index].start, score))
        for line_index, matches in per_line.items():
            start = min(value[0] for value in matches)
            average = sum(value[1] for value in matches) / len(matches)
            # The large fixed reward maximizes the number of directly anchored
            # lines before using word count/score as a tiebreaker.
            weight = 1_000.0 + len(matches) * 10.0 + average / 100.0
            candidates.append((line_index, start, weight))

    candidates.sort(key=lambda value: (value[0], value[1]))
    scores: list[float] = []
    previous: list[int | None] = []
    for index, (line_index, timestamp, weight) in enumerate(candidates):
        best_score = weight
        best_previous: int | None = None
        for prior_index in range(index):
            prior_line, prior_timestamp, _ = candidates[prior_index]
            if prior_line >= line_index or prior_timestamp >= timestamp:
                continue
            value = scores[prior_index] + weight
            if value > best_score:
                best_score = value
                best_previous = prior_index
        scores.append(best_score)
        previous.append(best_previous)

    direct: dict[int, float] = {}
    if scores:
        cursor: int | None = max(range(len(scores)), key=scores.__getitem__)
        chosen: list[int] = []
        while cursor is not None:
            chosen.append(cursor)
            cursor = previous[cursor]
        for index in reversed(chosen):
            line_index, timestamp, _ = candidates[index]
            direct[line_index] = timestamp

    reference = max(usable, key=len)
    starts_ms = _fill_line_timestamps(lines, direct, reference, duration_ms)
    direct_times = sorted(direct.values())
    timed_span_ratio = (
        _ratio(direct_times[-1] - direct_times[0], duration_ms / 1000.0)
        if len(direct_times) >= 2
        else max((_matched_word_span(mapped, transcript, duration_ms)
                  for mapped, transcript in zip(mappings, usable)), default=0.0)
        if len(direct) == 1 and len(lines) == 1 else 0.0
    )
    flat_word_count = len(mappings[0].flat_words)
    transcript_word_coverage = max(
        (
            _ratio(len(set(mapped.word_mapping.values())), len(transcript))
            for transcript, mapped in zip(usable, mappings)
        ),
        default=0.0,
    )
    metrics = AlignmentMetrics(
        lyric_word_count=flat_word_count,
        transcript_word_count=max(len(value) for value in usable),
        matched_word_count=len(best_word_scores),
        directly_matched_line_count=len(direct),
        line_count=len(lines),
        lyric_word_coverage=_ratio(len(best_word_scores), flat_word_count),
        transcript_word_coverage=transcript_word_coverage,
        direct_line_coverage=_ratio(len(direct), len(lines)),
        average_word_score=(
            sum(best_word_scores.values()) / len(best_word_scores)
            if best_word_scores
            else 0.0
        ),
        timed_span_ratio=timed_span_ratio,
    )
    reasons = _quality_reasons(
        metrics, starts_ms=starts_ms, line_count=len(lines), gate=gate
    )
    return AlignmentResult(
        accepted=not reasons,
        reasons=reasons,
        starts_ms=tuple(starts_ms),
        source_physical_indices=tuple(sources),
        metrics=metrics,
    )


def align_known_structure_multi(
    text: str,
    source_physical_indices: Sequence[int],
    transcripts: Sequence[Sequence[WordTimestamp]],
    duration_ms: int,
    *,
    threshold: float = DEFAULT_THRESHOLD,
    gate: QualityGate = QualityGate(),
) -> AlignmentResult:
    """Merge timing evidence while preserving one fixed performed sequence."""

    usable = [list(value) for value in transcripts if value]
    if len(usable) < 2:
        return align_known_structure(
            text,
            source_physical_indices,
            usable[0] if usable else [],
            duration_ms,
            threshold=threshold,
            gate=gate,
        )
    canonical_lines = _physical_lines(text)
    sources = list(source_physical_indices)
    if not sources or any(
        type(source) is not int
        or source < 0
        or source >= len(canonical_lines)
        or not canonical_lines[source].strip()
        for source in sources
    ):
        raise ValueError("fixed lyric structure contains an invalid source line")
    return _align_multi_for_sources(
        canonical_lines,
        sources,
        usable,
        duration_ms,
        threshold=threshold,
        gate=gate,
    )


def build_synced_sidecar(
    lyrics: dict[str, Any],
    alignment: AlignmentResult,
    duration_ms: int,
    *,
    line_timings: Sequence[dict[str, Any]],
) -> dict[str, Any]:
    if not alignment.accepted:
        raise ValueError("a rejected alignment cannot replace the approved sidecar")
    original = _plain_original(lyrics)
    original_physical = _physical_lines(original["text"])
    if alignment.source_physical_indices != tuple(index for index, line in enumerate(original_physical) if line):
        raise ValueError("publication requires the exact approved physical-line sequence")
    candidate = copy.deepcopy(lyrics)
    candidate_tracks = candidate["tracks"]
    if len(line_timings) != len(alignment.source_physical_indices):
        raise ValueError("endpoint line count does not match the accepted performed sequence")
    if any(
        left["endMs"] > right["startMs"]
        for left, right in zip(line_timings, line_timings[1:])
    ):
        raise ValueError("endpoint line timings overlap")
    has_words = False

    for track in candidate_tracks:
        if track.get("format") != "plain" or not isinstance(track.get("text"), str):
            raise ValueError("all current lyric companion tracks must be plain before projection")
        physical = _physical_lines(track["text"])
        if len(physical) != len(original_physical):
            raise ValueError("companion lyrics do not preserve original physical line count")
        for index in range(len(physical)):
            if bool(physical[index]) != bool(original_physical[index]):
                raise ValueError("companion lyrics do not preserve original blank-line structure")
        lines: list[dict[str, Any]] = []
        for timing, source_index in zip(
            line_timings, alignment.source_physical_indices
        ):
            line: dict[str, Any] = {
                "startMs": timing["startMs"],
                "endMs": timing["endMs"],
                "text": physical[source_index],
            }
            if track.get("kind") == "original" and "words" in timing:
                line["words"] = copy.deepcopy(timing["words"])
                has_words = True
            lines.append(line)
        track.pop("text", None)
        track["format"] = "synced"
        track["lines"] = lines

    if has_words:
        candidate["tokenizationVersion"] = TOKENIZATION_VERSION
    else:
        candidate.pop("tokenizationVersion", None)

    # Completion requires every Original word endpoint and every required role.
    # Retain partial timing with the limitation that actually blocks promotion.
    from stemwise_engine.editorial_admission import complete_original_word_timing
    if not complete_original_word_timing(candidate):
        candidate.update(status='partial', reason='Original-language word timing remains incomplete; '
            'every performed word requires a start and end.')
        validate_lyrics(candidate, duration_ms=duration_ms)
        return candidate
    promoted = copy.deepcopy(candidate)
    promoted["status"] = "complete"
    promoted.pop("reason", None)
    try:
        validate_lyrics(promoted, duration_ms=duration_ms)
        candidate = promoted
    except ContractError:
        candidate["status"] = "partial"
        candidate["reason"] = (
            "Original-language millisecond alignment is complete; one or more "
            "required translation or IPA companion tracks remain unavailable."
        )
        validate_lyrics(candidate, duration_ms=duration_ms)
    return candidate


def build_predicted_sidecar(
    lyrics: dict[str, Any],
    lexical_mapping: Mapping[str, Any],
    forced: Mapping[str, Any],
    duration_ms: int,
    *,
    basis: str,
) -> dict[str, Any]:
    """Project one complete acoustic word path as estimates, without admission.

    The caller must establish current content/occurrence authority and validate
    the observed numerical producer, recording, pronunciation and time mapping.
    """
    from stemwise_engine.ctc_worker import validate_global_results

    original = _plain_original(lyrics)
    if type(duration_ms) is not int or duration_ms <= 0 or not isinstance(basis, str) or not basis.strip():
        raise ValueError("predicted timing requires a positive duration and explicit acoustic basis")
    physical = _physical_lines(original["text"])
    sources = tuple(index for index, line in enumerate(physical) if line)
    expected_mapping = build_lexical_occurrence_mapping(lexical_mapping.get("videoId"), original, sources)
    if _lexical_mapping_core(lexical_mapping) != expected_mapping:
        raise ValueError("predicted timing mapping differs from the complete Original")
    if (not isinstance(forced, Mapping) or forced.get("engine") not in {
            "torchaudio-mms-fa", "wav2vec2-ctc-forced-alignment"}
            or not isinstance(forced.get("lines"), list)):
        raise ValueError("predicted timing requires one complete MMS or Wav2Vec2 result")
    owners = expected_mapping["performedOccurrences"]
    expected = [{"sourcePhysicalIndex": owner["sourcePhysicalIndex"], "lineId": owner["occurrenceId"],
                 "words": [{"wordId": word["occurrenceWordId"]} for word in owner["words"]]}
                for owner in owners]
    validate_global_results(forced["lines"], expected, duration_ms)
    lines = []
    for source, row in zip(sources, forced["lines"]):
        words = row["words"]
        if (type(row.get("lineIndex")) is not int or type(row.get("sourcePhysicalIndex")) is not int
                or row.get("startMs") != words[0]["startMs"] or row.get("endMs") != words[-1]["endMs"]):
            raise ValueError("predicted line bounds differ from the complete acoustic word path")
        lines.append({"text": physical[source], "contentStatus": "admitted", "occurrenceStatus": "supported",
            "timing": {"status": "estimated", "startMs": row["startMs"], "endMs": row["endMs"], "basis": basis},
            "words": [{"startUtf16": start, "endUtf16": end, "timing": {
                "status": "estimated", "startMs": word["startMs"], "endMs": word["endMs"], "basis": basis}}
                for word, (start, end, _) in zip(words, display_words(physical[source]))]})
    candidate = copy.deepcopy(lyrics)
    for track in candidate["tracks"]:
        if track.get("kind") == "original":
            track.pop("text")
            track.update(format="regional", lines=lines)
        elif track.get("format") != "plain" or not isinstance(track.get("text"), str):
            raise ValueError("prediction leaves companion inputs plain and unchanged")
    candidate.update(status="partial", reason="Original word timing is estimated from an acoustic forced-alignment prediction.",
                     tokenizationVersion=TOKENIZATION_VERSION)
    validate_lyrics(candidate, duration_ms=duration_ms)
    return candidate


def reproject_display_only(
    lyrics: dict[str, Any],
    display_original_text: str,
    mapping: Mapping[str, Any],
    duration_ms: int,
    *,
    authority_original_text: str | None = None,
    reviewed_word_languages: Sequence[Sequence[str | None]] | None = None,
) -> dict[str, Any]:
    """Reproject unchanged timing after a verified display-only text edit."""

    if not isinstance(display_original_text, str):
        raise TypeError("display original text must be a string")
    core_mapping = _lexical_mapping_core(mapping)
    raw_occurrences = core_mapping["performedOccurrences"]
    if not isinstance(raw_occurrences, list):
        raise ValueError("lexical occurrence mapping is malformed")
    try:
        sources = [record["sourcePhysicalIndex"] for record in raw_occurrences]
    except (KeyError, TypeError) as error:
        raise ValueError("lexical occurrence mapping is malformed") from error

    candidate = copy.deepcopy(lyrics)
    candidate.pop("lexicalAuthoritySha256", None)
    for track in candidate.get("tracks", []):
        for line in track.get("lines", []):
            for key in ("lineId", "authorityLineId", "occurrenceId"):
                line.pop(key, None)
            for word in line.get("words", []):
                for key in ("wordId", "authorityWordId", "occurrenceWordId"):
                    word.pop(key, None)
    validate_lyrics(candidate, duration_ms=duration_ms)

    originals = [
        track for track in candidate.get("tracks", []) if track.get("kind") == "original"
    ]
    if len(originals) != 1 or originals[0].get("format") != "synced":
        raise ValueError("display reprojection requires one synced original track")
    original = originals[0]
    display_authority = copy.deepcopy(original)
    display_authority.pop("lines", None)
    display_authority["format"] = "plain"
    display_authority["text"] = authority_original_text if authority_original_text is not None else display_original_text
    expected = build_lexical_occurrence_mapping(
        str(mapping.get("videoId") or ""),
        display_authority,
        sources,
    )
    if core_mapping != expected:
        raise ValueError("display edit changed lexical authority, line structure, or order")
    language = str(original.get('language', '')).split('-', 1)[0].lower()
    components = {str(value).split('-', 1)[0].lower() for value in original.get('componentLanguages', [])}
    comparison_languages = sorted(components) if language == 'mul' and components else [language or None]
    authority_physical = _physical_lines(display_authority['text'])
    languages_by_source = {}
    if reviewed_word_languages is not None:
        if len(reviewed_word_languages) != len(sources):
            raise ValueError('reviewed language occurrence count differs')
        declared = {canonical_language(value) for value in (original.get('componentLanguages', [])
            if language == 'mul' else [original.get('language')])}
        for source, languages in zip(sources, reviewed_word_languages):
            if (not isinstance(languages, (list, tuple))
                    or len(languages) != len(display_words(authority_physical[source]))
                    or any((value is not None and not isinstance(value, str))
                        or value not in declared or canonical_language(value) != value for value in languages)):
                raise ValueError('reviewed display-word languages differ from the authority')
            languages = tuple(languages)
            if source in languages_by_source and languages_by_source[source] != languages:
                raise ValueError('repeated authority words have conflicting reviewed languages')
            languages_by_source[source] = languages

    def same_line(left, right, source):
        left_words, right_words = display_words(left), display_words(right)
        if len(left_words) != len(right_words):
            return False
        reviewed = languages_by_source.get(source)
        return all(all(lexical_line_tokens(a[2], language=value) == lexical_line_tokens(b[2], language=value)
                       for value in ([reviewed[index]] if reviewed is not None and reviewed[index] is not None
                                     else comparison_languages))
                   for index, (a, b) in enumerate(zip(left_words, right_words)))

    physical = _physical_lines(display_original_text)
    if authority_original_text is not None and (len(authority_physical) != len(physical) or any(
            not same_line(left, right, source)
            for source, (left, right) in enumerate(zip(authority_physical, physical)))):
        raise ValueError('display edit changed language-specific lexical authority')

    occurrences = expected["performedOccurrences"]
    prior_lines = original["lines"]
    if len(prior_lines) != len(occurrences):
        raise ValueError("synced lines do not match the performed occurrence mapping")
    projected: list[dict[str, Any]] = []
    for prior, occurrence in zip(prior_lines, occurrences):
        text = physical[occurrence["sourcePhysicalIndex"]]
        if not same_line(prior['text'], text, occurrence['sourcePhysicalIndex']):
            raise ValueError("performed occurrence does not match its authority line")
        line: dict[str, Any] = {
            "startMs": prior["startMs"],
            "endMs": prior["endMs"],
            "text": text,
        }
        if "words" in prior:
            ranges = display_words(text)
            if len(ranges) != len(occurrence["words"]):
                raise ValueError("display edit changed a word boundary")
            line["words"] = [
                {
                    "startUtf16": start_utf16,
                    "endUtf16": end_utf16,
                    "startMs": old["startMs"],
                    "endMs": old["endMs"],
                }
                for old, (start_utf16, end_utf16, _) in zip(prior["words"], ranges)
            ]
        projected.append(line)
    original["lines"] = projected
    validate_lyrics(candidate, duration_ms=duration_ms)
    return candidate


def resolve_compute_type(device: str, requested: str | None = None) -> str:
    import ctranslate2
    supported = ctranslate2.get_supported_compute_types(device)
    if requested is not None:
        if requested not in supported:
            raise ValueError(f"Unsupported Whisper compute type {requested!r} for {device}; supported: {sorted(supported)}")
        return requested
    for candidate in (("float16", "float32", "int8") if device == "cuda" else ("float32", "int8", "int16")):
        if candidate in supported:
            return candidate
    raise ValueError(f"No supported Whisper compute type for {device}")


class FasterWhisperEvidence:
    """Reusable faster-whisper model that emits syncalong word evidence."""

    def __init__(
        self,
        model: str,
        snapshot_path: Path,
        *,
        device: str = "cuda",
        compute_type: str | None = None,
        timeout_seconds: int = 10_800,
    ) -> None:
        compute_type = resolve_compute_type(device, compute_type)
        from faster_whisper import WhisperModel

        snapshot_path = snapshot_path.resolve(strict=True)
        if not snapshot_path.is_dir():
            raise ValueError("faster-whisper snapshot path is not a directory")
        if type(timeout_seconds) is not int or timeout_seconds <= 0:
            raise ValueError("Whisper stage deadline must be positive")
        self.timeout_seconds = timeout_seconds
        self.model_name = model
        self.device = device
        self.compute_type = compute_type
        self.snapshot_path = snapshot_path
        with execution_deadline(timeout_seconds):
            self._model = WhisperModel(
                str(snapshot_path),
                device=device,
                compute_type=compute_type,
                local_files_only=True,
            )

    def close(self) -> None:
        if self._model is not None:
            with execution_deadline(30):
                self._model.model.unload_model()
            self._model = None

    def transcribe(
        self,
        audio_path: Path,
        *,
        language: str | None,
        prompt: str | None,
        precision: bool = False,
    ) -> tuple[list[WordTimestamp], dict[str, Any]]:
        if self._model is None:
            raise RuntimeError("Whisper model is retired")
        options: dict[str, Any] = {
            "language": language,
            "initial_prompt": prompt or None,
            "beam_size": 10 if precision else 5,
            "word_timestamps": True,
            "condition_on_previous_text": precision and bool(prompt),
            "vad_filter": False,
            "temperature": 0.0,
        }
        if precision:
            options.update(
                patience=1.5,
                repetition_penalty=1.05,
            )
        with execution_deadline(self.timeout_seconds):
            segments, info = self._model.transcribe(str(audio_path), **options)
            words: list[WordTimestamp] = []
            segment_count = 0
            for segment in segments:
                segment_count += 1
                for item in segment.words or []:
                    raw = (item.word or "").strip()
                    word = normalize(raw)
                    if not word or item.start is None or item.end is None:
                        continue
                    words.append(
                        WordTimestamp(
                            word=word,
                            raw=raw,
                            start=float(item.start),
                            end=float(item.end),
                        )
                    )
            evidence = {
                "detectedLanguage": getattr(info, "language", None),
                "detectedLanguageProbability": getattr(info, "language_probability", None),
                "segmentCount": segment_count,
                "wordCount": len(words),
                "decodingProfile": "precision" if precision else "standard",
            }
            return words, evidence


def transcript_to_json(
    video_id: str,
    words: Sequence[WordTimestamp],
    *,
    model: str,
    language: str | None,
    prompt_sha256: str,
    evidence: dict[str, Any],
    provenance: dict[str, Any],
    cache_identity: dict[str, Any],
) -> dict[str, Any]:
    return {
        "cacheVersion": TRANSCRIPT_CACHE_VERSION,
        "videoId": video_id,
        "model": model,
        "language": language,
        "promptSha256": prompt_sha256,
        "provenance": provenance,
        "cacheIdentity": cache_identity,
        "evidence": evidence,
        "words": [word.to_dict() for word in words],
    }


def transcript_from_json(value: dict[str, Any]) -> list[WordTimestamp]:
    words = value.get("words")
    if not isinstance(words, list):
        raise ValueError("transcript cache has no words array")
    return [WordTimestamp.from_dict(item) for item in words]


def _caption_language_key(
    available: dict[str, Any],
    language: str | None,
    *,
    automatic: bool,
) -> str | None:
    if not available:
        return None
    preferred: list[str] = []
    if language:
        if automatic:
            preferred.extend((f"{language}-orig", language))
        else:
            preferred.extend((language, f"{language}-orig"))
        preferred.extend(
            key for key in available if key.split("-", 1)[0].lower() == language
        )
    elif automatic:
        preferred.extend(key for key in available if key.lower().endswith("-orig"))
    for key in preferred:
        if key in available:
            return key
    return None


def parse_youtube_json3(path: Path) -> list[WordTimestamp]:
    """Parse native/manual JSON3 captions without retaining rolling appends."""

    value = parse_strict_json_bytes(read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=path.parent))
    events = value.get("events") if isinstance(value, dict) else None
    if not isinstance(events, list):
        raise ValueError("YouTube JSON3 caption has no events array")
    words: list[WordTimestamp] = []
    for event in events:
        if not isinstance(event, dict) or event.get("aAppend"):
            # Automatic captions use one-word append updates between complete
            # windows.  Keeping them would duplicate the rolling caption.
            continue
        start_ms = event.get("tStartMs")
        segments = event.get("segs")
        if type(start_ms) is not int or not isinstance(segments, list):
            continue
        duration_ms = event.get("dDurationMs")
        if type(duration_ms) is not int or duration_ms <= 0:
            duration_ms = 2_000
        valid_segments = [item for item in segments if isinstance(item, dict)]
        for index, segment in enumerate(valid_segments):
            raw = str(segment.get("utf8") or "").strip()
            normalized = normalize(raw)
            pieces = normalized.split()
            if not pieces:
                continue
            offset_ms = segment.get("tOffsetMs")
            if type(offset_ms) is not int or offset_ms < 0:
                offset_ms = 0
            next_offset = duration_ms
            for following in valid_segments[index + 1 :]:
                candidate = following.get("tOffsetMs")
                if type(candidate) is int and candidate > offset_ms:
                    next_offset = candidate
                    break
            segment_start = (start_ms + offset_ms) / 1000.0
            segment_end = (start_ms + max(offset_ms + 1, next_offset)) / 1000.0
            step = max((segment_end - segment_start) / len(pieces), 0.001)
            for piece_index, piece in enumerate(pieces):
                piece_start = segment_start + piece_index * step
                piece_end = min(segment_end, piece_start + step)
                words.append(
                    WordTimestamp(
                        word=piece,
                        raw=raw if len(pieces) == 1 else piece,
                        start=piece_start,
                        end=max(piece_start + 0.001, piece_end),
                    )
                )
    words.sort(key=lambda item: (item.start, item.end))
    deduplicated: list[WordTimestamp] = []
    seen: set[tuple[str, int]] = set()
    for word in words:
        signature = (word.word, int(round(word.start * 1000.0)))
        if signature in seen:
            continue
        seen.add(signature)
        deduplicated.append(word)
    return deduplicated


@optional_provider_stage()
def download_youtube_caption_evidence(
    video_id: str,
    temp_dir: Path,
    *,
    language: str | None,
) -> tuple[list[WordTimestamp], dict[str, Any]] | None:
    """Download exact-video native/manual caption timing when available."""

    from stemwise_engine.youtube import extract_youtube_info, canonical_url

    url = canonical_url(video_id)
    base_options: dict[str, Any] = {
        "_stemwise_probe_url": url,
        "skip_download": True,
        "noplaylist": True,
        "retries": 3,
        "socket_timeout": 30,
    }
    info = extract_youtube_info(url, base_options)
    if not isinstance(info, dict) or info.get("id") != video_id:
        raise ValueError('caption-response-identity-mismatch')
    manual = info.get("subtitles") if isinstance(info.get("subtitles"), dict) else {}
    automatic = (
        info.get("automatic_captions")
        if isinstance(info.get("automatic_captions"), dict)
        else {}
    )
    source = "manual"
    key = _caption_language_key(manual, language, automatic=False)
    if key is None:
        source = "automatic"
        key = _caption_language_key(automatic, language, automatic=True)
    if key is None:
        return None

    temp_dir.mkdir(parents=True, exist_ok=True)
    for prior in temp_dir.glob("caption*.json3"):
        prior.unlink(missing_ok=True)
    options = dict(base_options)
    options.update(
        {
            "writesubtitles": source == "manual",
            "writeautomaticsub": source == "automatic",
            "subtitleslangs": [key],
            "subtitlesformat": "json3",
            "outtmpl": str(temp_dir / "caption.%(ext)s"),
        }
    )
    downloaded = extract_youtube_info(url, options, download=True, check_update=False)
    if not isinstance(downloaded, dict) or downloaded.get("id") != video_id:
        raise ValueError('caption-response-identity-mismatch')
    candidates = sorted(temp_dir.glob("caption*.json3"))
    if not candidates:
        return None
    current_budget().remaining()
    words = parse_youtube_json3(candidates[0])
    if not words:
        return None
    return words, {
        "type": "youtube-caption",
        "captionKind": source,
        "captionLanguage": key,
        "wordCount": len(words),
    }


def separate_vocals_demucs(
    audio_path: Path,
    output_dir: Path,
    *,
    project_dir: Path,
    device: str = "cuda",
    timeout_seconds: int = 10_800,
) -> Path:
    """Run the pinned local htdemucs two-stem model and return vocals.wav."""

    from stemwise_engine.runtime_environment import environment_python, environment_lease
    demucs_python = environment_python('main', project_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    with heavy_worker_stage({"projectDir": str(project_dir)}), environment_lease('main', builder=project_dir):
        completed = run_process_tree(
            [
                str(demucs_python),
                "-m",
                "demucs.separate",
                "--two-stems",
                "vocals",
                "--name",
                "htdemucs",
                "--shifts",
                "0",
                "--device",
                device,
                "--out",
                str(output_dir),
                str(audio_path),
            ],
            text=True,
            capture_output=True,
            check=False,
            timeout=timeout_seconds,
        )
    candidates = list(output_dir.glob("htdemucs/*/vocals.wav"))
    if completed.returncode != 0 or not candidates:
        detail = (completed.stderr or completed.stdout or "unknown error").strip()[-500:]
        raise RuntimeError(f"Demucs vocal isolation failed: {detail}")
    vocals = max(candidates, key=lambda path: path.stat().st_size)
    if vocals.stat().st_size <= 44:
        raise RuntimeError("Demucs produced an empty vocals.wav")
    return vocals


class AlignmentWorkspace:
    def __init__(
        self, agent_research_root: Path, *, workspace_root: Path | None = None
    ) -> None:
        self.workspace_root = workspace_root or agent_research_root.parent
        self.root = agent_research_root / "lyrics-alignment"
        self.jobs = self.root / "jobs"
        self.canonical_plain = self.root / "canonical-plain"
        self.reports = self.root / "reports"
        validate_generated_path(
            self.workspace_root,
            self.root,
            workspace_root=self.workspace_root,
        )

    def job_dir(self, video_id: str) -> Path:
        return validate_generated_path(
            self.jobs,
            self.jobs / validate_video_id(video_id),
            workspace_root=self.workspace_root,
        )

    def state_path(self, video_id: str) -> Path:
        return self.job_dir(video_id) / "state.json"

    def transcript_path(
        self,
        video_id: str,
        language: str | None = None,
        evidence_source: str = "mix",
    ) -> Path:
        suffix = f".{language}" if language else ""
        if evidence_source != "mix":
            suffix += f".{evidence_source}"
        return self.job_dir(video_id) / f"transcript{suffix}.private.json"

    def candidate_path(self, video_id: str) -> Path:
        return self.job_dir(video_id) / "candidate.private.lyrics.json"

    def caption_path(self, video_id: str) -> Path:
        return self.job_dir(video_id) / "caption.private.json"

    def mapping_path(self, video_id: str) -> Path:
        return self.job_dir(video_id) / "mapping.private.json"

    def forced_alignment_path(self, video_id: str, variant: str) -> Path:
        if variant not in {
            "baseline",
            "candidate-structure",
        }:
            raise ValueError("unsupported forced-alignment cache variant")
        return self.job_dir(video_id) / f"mms-{variant}.private.json"

    def audio_provenance_path(self, video_id: str) -> Path:
        return self.job_dir(video_id) / "audio-provenance.private.json"

    def reviewed_lrc_path(self, video_id: str) -> Path:
        return self.job_dir(video_id) / "reviewed-lrc-starts.private.json"

    def backup_pointer_path(self, video_id: str) -> Path:
        return validate_generated_path(
            self.canonical_plain,
            self.canonical_plain / f"{validate_video_id(video_id)}.current.json",
            workspace_root=self.workspace_root,
        )

    def current_backup_path(self, video_id: str) -> Path:
        pointer = self.backup_pointer_path(video_id)
        try:
            pointer_raw = read_bounded_regular_bytes(
                pointer, max_bytes=MAX_JSON_BYTES, root=self.canonical_plain
            )
        except ValueError as error:
            raise FileNotFoundError("canonical plain backup pointer is missing") from error
        value = parse_strict_json_bytes(pointer_raw)
        if (
            not isinstance(value, dict)
            or value.get("pointerVersion") != 1
            or value.get("videoId") != video_id
            or not isinstance(value.get("fileName"), str)
            or Path(value["fileName"]).name != value["fileName"]
            or not re.fullmatch(r"[0-9a-f]{64}", str(value.get("sha256") or ""))
        ):
            raise ValueError("canonical plain backup pointer is invalid")
        current = validate_generated_path(
            self.canonical_plain,
            self.canonical_plain / value["fileName"],
            workspace_root=self.workspace_root,
        )
        try:
            current_raw = read_bounded_regular_bytes(
                current, max_bytes=MAX_JSON_BYTES, root=self.canonical_plain
            )
        except ValueError as error:
            raise ValueError("canonical plain backup pointer does not match its file") from error
        if _sha256_bytes(current_raw) != value["sha256"]:
            raise ValueError("canonical plain backup pointer does not match its file")
        return current

    def write_state(self, video_id: str, state: dict[str, Any]) -> None:
        state = dict(state)
        state["alignmentVersion"] = ALIGNMENT_VERSION
        state["updatedAtUnix"] = int(time.time())
        atomic_write_json(
            self.state_path(video_id),
            state,
            root=self.job_dir(video_id),
            workspace_root=self.workspace_root,
        )


def serialize_result(result: AlignmentResult) -> dict[str, Any]:
    return {
        "accepted": result.accepted,
        "reasons": list(result.reasons),
        "metrics": asdict(result.metrics),
        "timedLineCount": len(result.starts_ms),
    }


def _recognition_cache_identity_matches(value: Any, current: dict[str, Any]) -> bool:
    return value == current


def transcript_cache_matches(
    value: dict[str, Any],
    *,
    video_id: str,
    model: str,
    language: str | None,
    prompt_sha256: str,
    provenance: dict[str, Any],
    cache_identity: dict[str, Any],
) -> bool:
    return (
        value.get("cacheVersion") == TRANSCRIPT_CACHE_VERSION
        and value.get("videoId") == video_id
        and value.get("model") == model
        and value.get("language") == language
        and value.get("promptSha256") == prompt_sha256
        and value.get("provenance") == provenance
        and _recognition_cache_identity_matches(value.get("cacheIdentity"), cache_identity)
        and isinstance(value.get("words"), list)
    )


def _recompute_retained_revision(workspace, video_id, revision):
    if not isinstance(revision, dict):
        raise ValueError('Revision continuity is malformed')
    originals = []
    for key in ('previousCanonicalPlainSha256', 'currentCanonicalPlainSha256'):
        digest = revision.get(key)
        if not isinstance(digest, str) or re.fullmatch('[0-9a-f]{64}', digest) is None:
            raise ValueError('Revision canonical binding is invalid')
        raw = read_bounded_regular_bytes(workspace.canonical_plain / f'{video_id}.{digest}.lyrics.json',
            max_bytes=MAX_JSON_BYTES, root=workspace.canonical_plain)
        if _sha256_bytes(raw) != digest:
            raise ValueError('Revision canonical source changed')
        originals.append(_plain_original(parse_strict_json_bytes(raw)))
    sources = [tuple(index for index, units in enumerate(lexical_line_tokens(
        original['text'], language=original.get('language'))) if units) for original in originals]
    expected = build_revision_mapping(video_id, *originals, *sources)
    expected.update({key: revision[key] for key in
        ('previousCanonicalPlainSha256', 'currentCanonicalPlainSha256')})
    return expected, originals, sources


def save_alignment_artifacts(
    workspace: AlignmentWorkspace,
    video_id: str,
    *,
    candidate: dict[str, Any] | None,
    result: AlignmentResult,
    lexical_mapping: Mapping[str, Any] | None,
    public_line_timings: Sequence[dict[str, Any]] | None = None,
) -> dict[str, Any] | None:
    if candidate is not None and lexical_mapping is None:
        atomic_write_json(
            workspace.candidate_path(video_id),
            candidate,
            root=workspace.job_dir(video_id),
            workspace_root=workspace.workspace_root,
        )
    path = workspace.mapping_path(video_id)
    if lexical_mapping is None:
        path.unlink(missing_ok=True)
        return None
    core = _lexical_mapping_core(lexical_mapping)
    if core["videoId"] != validate_video_id(video_id):
        raise ValueError("lexical occurrence mapping belongs to another song")
    identity = lexical_mapping_identity(lexical_mapping)
    mapping = {
        **core,
        "mappingSha256": identity["mappingSha256"],
        "numeric": {
            "sourcePhysicalIndices": list(result.source_physical_indices),
            "startsMs": list(result.starts_ms),
        },
    }
    if public_line_timings is not None:
        mapping["numeric"]["publicLineTimings"] = copy.deepcopy(
            list(public_line_timings)
        )
    revision_path = workspace.job_dir(video_id) / 'revision-map.private.json'
    if revision_path.exists() or revision_path.is_symlink():
        revision = parse_strict_json_bytes(read_bounded_regular_bytes(revision_path,
            max_bytes=MAX_JSON_BYTES, root=workspace.job_dir(video_id)))
        if revision.get('currentMapping') == identity:
            expected_revision, originals, sources = _recompute_retained_revision(workspace, video_id, revision)
            if expected_revision != revision:
                raise ValueError('Revision continuity differs from approved sources')
            mapping['revisionContinuity'] = revision
            state_path = workspace.state_path(video_id)
            if state_path.exists() or state_path.is_symlink():
                state = parse_strict_json_bytes(read_bounded_regular_bytes(state_path,
                    max_bytes=MAX_JSON_BYTES, root=workspace.job_dir(video_id)))
                if state.get('contentAdmission') is not None:
                    from stemwise_engine.lyric_content import require_content
                    admission = require_content(state['contentAdmission'], originals[1], sources[1],
                        revision['currentCanonicalPlainSha256'],
                        state.get('contentBinding', {}).get('audioSha256'), workspace.job_dir(video_id),
                        adjudication_job_dir=workspace.root.parent / 'jobs' / video_id)
                    mapping['revisionContinuity']['reassessedEvidence'] = admission.to_record()
    if candidate is not None:
        atomic_write_json(
            workspace.candidate_path(video_id),
            candidate,
            root=workspace.job_dir(video_id),
            workspace_root=workspace.workspace_root,
        )
    atomic_write_json(
        path,
        mapping,
        root=workspace.job_dir(video_id),
        workspace_root=workspace.workspace_root,
    )
    return {
        **identity,
        "mappingArtifactSha256": _sha256_bytes(path.read_bytes()),
    }


def snapshot_canonical_plain(
    workspace: AlignmentWorkspace,
    video_id: str,
    original_raw: bytes,
) -> tuple[Path, str]:
    video_id = validate_video_id(video_id)
    backup_sha256 = _sha256_bytes(original_raw)
    pointer = workspace.backup_pointer_path(video_id)
    revision = None
    if pointer.exists() or pointer.is_symlink():
        previous_path = workspace.current_backup_path(video_id)
        previous_raw = read_bounded_regular_bytes(previous_path, max_bytes=MAX_JSON_BYTES,
            root=workspace.canonical_plain)
        if previous_raw != original_raw:
            try:
                previous_original = _plain_original(parse_strict_json_bytes(previous_raw))
                current_original = _plain_original(parse_strict_json_bytes(original_raw))
                previous_indices = tuple(index for index, units in enumerate(lexical_line_tokens(
                    previous_original['text'], language=previous_original.get('language'))) if units)
                current_indices = tuple(index for index, units in enumerate(lexical_line_tokens(
                    current_original['text'], language=current_original.get('language'))) if units)
                revision = build_revision_mapping(video_id, previous_original, current_original,
                    previous_indices, current_indices)
            except (ValueError, TypeError, KeyError):
                # Lossless snapshot recovery never grants continuity to malformed authority.
                revision = {'version': 1, 'complete': False, 'regions': [], 'reason': 'authority-unavailable'}
            revision.update(previousCanonicalPlainSha256=_sha256_bytes(previous_raw),
                currentCanonicalPlainSha256=backup_sha256)
        else:
            revision_path = workspace.job_dir(video_id) / 'revision-map.private.json'
            if revision_path.exists() or revision_path.is_symlink():
                retained_revision = parse_strict_json_bytes(read_bounded_regular_bytes(revision_path,
                    max_bytes=MAX_JSON_BYTES, root=workspace.job_dir(video_id)))
                if (not isinstance(retained_revision, dict)
                        or retained_revision.get('currentCanonicalPlainSha256') != backup_sha256):
                    raise ValueError('Revision canonical binding differs from current snapshot')
                expected, _, _ = _recompute_retained_revision(workspace, video_id, retained_revision)
                policy = retained_revision.get('comparisonPolicy')
                if (not isinstance(policy, dict) or not policy
                        or any(not isinstance(key, str) or not isinstance(value, str)
                            or re.fullmatch('[0-9a-f]{64}', value) is None for key, value in policy.items())):
                    raise ValueError('Revision comparison policy is invalid')
                if policy == expected['comparisonPolicy']:
                    if retained_revision != expected:
                        raise ValueError('Revision continuity differs from approved sources')
                else:
                    revision = expected
    backup = validate_generated_path(
        workspace.canonical_plain,
        workspace.canonical_plain / f"{video_id}.{backup_sha256}.lyrics.json",
        workspace_root=workspace.workspace_root,
    )
    if backup.exists() or backup.is_symlink():
        try:
            backup_raw = read_bounded_regular_bytes(
                backup, max_bytes=MAX_JSON_BYTES, root=workspace.canonical_plain
            )
        except ValueError as error:
            raise ValueError("content-addressed canonical backup is unsafe") from error
        if _sha256_bytes(backup_raw) != backup_sha256:
            raise ValueError("content-addressed canonical backup is corrupt")
    else:
        atomic_write_bytes(
            backup,
            original_raw,
            root=workspace.canonical_plain,
            workspace_root=workspace.workspace_root,
        )
    if revision is not None:
        atomic_write_json(workspace.job_dir(video_id) / 'revision-map.private.json', revision,
            root=workspace.job_dir(video_id), workspace_root=workspace.workspace_root)
    atomic_write_json(
        workspace.backup_pointer_path(video_id),
        {
            "pointerVersion": 1,
            "videoId": video_id,
            "fileName": backup.name,
            "sha256": backup_sha256,
        },
        root=workspace.canonical_plain,
        workspace_root=workspace.workspace_root,
    )
    return backup, backup_sha256


def commit_candidate(
    workspace: AlignmentWorkspace,
    video_id: str,
    sidecar_path: Path,
    original_raw: bytes,
    candidate: dict[str, Any],
    *,
    expected_current_raw: bytes | None = None,
) -> tuple[Path, str]:
    owner_root = sidecar_path.parent
    sidecar_path = validate_generated_path(
        owner_root,
        sidecar_path,
        workspace_root=workspace.workspace_root,
    )
    expected = original_raw if expected_current_raw is None else expected_current_raw
    if read_bounded_regular_bytes(sidecar_path, max_bytes=MAX_JSON_BYTES, root=owner_root) != expected:
        raise ValueError('Owner sidecar changed while alignment was running; candidate remains private')
    backup, backup_sha256 = snapshot_canonical_plain(
        workspace, video_id, original_raw
    )
    # Publish the pointer while the approved plain sidecar is still present.
    # If the final synced-sidecar write is interrupted, retrying from the
    # pointer and the still-plain owner input remains coherent.
    from stemwise_engine.worker_publication import publish_owner_file
    token = uuid.uuid4().hex
    staged = sidecar_path.with_name(f'.{sidecar_path.name}.{token}.pending')
    try:
        atomic_write_json(staged, candidate, root=owner_root, workspace_root=workspace.workspace_root)
        publish_owner_file(sidecar_path, staged, expected, max_bytes=MAX_JSON_BYTES)
    finally:
        if not sidecar_path.with_name('.'+sidecar_path.name+'.publication-conflict.json').exists():
            staged.unlink(missing_ok=True)
    return backup, backup_sha256


def inventory_summary(sidecar_dir: Path) -> dict[str, int]:
    summary = {
        "sidecars": 0,
        "plainOriginal": 0,
        "syncedOriginal": 0,
        "regionalOriginal": 0,
        "unavailable": 0,
        "invalid": 0,
    }
    for path in sidecar_dir.glob("*.lyrics.json"):
        summary["sidecars"] += 1
        try:
            lyrics, _ = load_sidecar(path, root=sidecar_dir)
            originals = [track for track in lyrics.get("tracks", []) if track.get("kind") == "original"]
            if lyrics.get("status") == "unavailable" or not originals:
                summary["unavailable"] += 1
            elif originals[0].get("format") == "plain":
                summary["plainOriginal"] += 1
            elif originals[0].get("format") == "synced":
                summary["syncedOriginal"] += 1
            elif originals[0].get("format") == "regional":
                summary["regionalOriginal"] += 1
            else:
                summary["invalid"] += 1
        except Exception:
            summary["invalid"] += 1
    return summary


__all__ = [
    "ALIGNMENT_VERSION",
    "DEFAULT_MODEL",
    "LEXICAL_MAPPING_VERSION",
    "TRANSCRIPT_CACHE_VERSION",
    "AlignmentMetrics",
    "AlignmentResult",
    "AlignmentWorkspace",
    "FasterWhisperEvidence",
    "QualityGate",
    "StructureWitness",
    "StructureWitnessMetrics",
    "align_known_structure",
    "align_known_structure_multi",
    "build_synced_sidecar",
    "build_lexical_occurrence_mapping",
    "build_revision_mapping",
    "canonical_language",
    "commit_candidate",
    "deduplicated_lyrics_prompt",
    "download_youtube_caption_evidence",
    "inventory_summary",
    "internal_repeat_structure_kind",
    "lexical_mapping_identity",
    "load_sidecar",
    "parse_youtube_json3",
    "reproject_display_only",
    "save_alignment_artifacts",
    "score_structure_witness",
    "separate_vocals_demucs",
    "serialize_result",
    "snapshot_canonical_plain",
    "transcript_cache_matches",
    "transcript_from_json",
    "transcript_to_json",
    "transcription_languages",
]
