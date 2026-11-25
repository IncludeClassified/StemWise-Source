"""Conservative lexical admission from retained unprompted recognition.

Agreement is supporting evidence, not a phonetic correctness claim. Missing marks,
words or occurrences remain uncertain; a timing algorithm cannot resolve them.
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Sequence

from stemwise_engine.common import parse_strict_json_bytes, read_bounded_regular_bytes, read_bounded_json
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.lyrics_tokenization import lexical_line_tokens, display_words
from stemwise_engine.lyrics_normalize import performed_line_indices, physical_lines
from stemwise_engine.workflow_records import LyricsContentBlocker, ContentEvidenceUnavailable

_SHA = re.compile(r"[0-9a-f]{64}")
ADJUDICATION_FILE = 'recording-evidence-adjudication.json'
OWNER_VERIFICATION_FILE = 'owner-verified-lyrics.json'
_TIMING_REASONS = frozenset({"line-start-disagreement", "line-end-disagreement",
    "short-timed-span", "could-not-produce-bounded-monotonic-ms", "line-interval-disjoint",
    "invalid-line-interval", "qualified-endpoint-contradiction", "qualified-word-contradiction"})


def require_pronunciation_support(text: str, plan=None) -> None:
    """Numerals need an explicit spoken target; timing must not guess one."""
    import unicodedata
    if plan is not None:
        from stemwise_engine.spoken_targets import ReviewedSpokenPlan
        if not isinstance(plan, ReviewedSpokenPlan) or plan.original_text != text:
            raise LyricsContentBlocker('pronunciation-support-unavailable')
        plan.require_current()
        return
    if any(unicodedata.category(character).startswith('N') for character in text):
        raise LyricsContentBlocker('pronunciation-support-unavailable')


def _binding(indices, canonical_sha, audio_sha):
    if (not isinstance(canonical_sha, str) or not _SHA.fullmatch(canonical_sha)
            or not isinstance(audio_sha, str) or not _SHA.fullmatch(audio_sha)
            or not isinstance(indices, (list, tuple)) or not indices
            or any(type(index) is not int or index < 0 for index in indices)):
        raise LyricsContentBlocker("content-binding-unavailable")
    return {"canonicalPlainSha256": canonical_sha, "audioSha256": audio_sha,
            "sourcePhysicalIndices": list(indices)}


def validate_content_admission_record(record: object) -> dict[str, Any]:
    """Validate body-free serialization only; this does not admit any content."""
    required = {"version", "canonicalPlainSha256", "audioSha256", "sourcePhysicalIndices", "evidence"}
    if (not isinstance(record, dict) or not required <= set(record)
            or not set(record) <= required | {'adjudication', 'spokenTargets', 'ownerVerification'}):
        raise LyricsContentBlocker("content-evidence-unavailable")
    _binding(record["sourcePhysicalIndices"], record["canonicalPlainSha256"], record["audioSha256"])
    evidence = record["evidence"]
    if (type(record["version"]) is not int or record["version"] != 1
            or not isinstance(evidence, dict) or set(evidence) != {"path", "sha256"}
            or not isinstance(evidence["path"], str) or not evidence["path"]
            or len(evidence["path"]) > 240 or "\\" in evidence["path"]
            or PurePosixPath(evidence["path"]).is_absolute()
            or any(part in {".", ".."} or ":" in part for part in evidence["path"].split("/"))
            or not isinstance(evidence["sha256"], str) or not _SHA.fullmatch(evidence["sha256"])):
        raise LyricsContentBlocker("content-evidence-invalid")
    if 'adjudication' in record:
        reference = record['adjudication']
        if (not isinstance(reference, dict) or set(reference) != {'path', 'sha256'}
                or reference['path'] != ADJUDICATION_FILE or not isinstance(reference['sha256'], str)
                or not _SHA.fullmatch(reference['sha256'])):
            raise LyricsContentBlocker('recording-adjudication-invalid')
    if 'spokenTargets' in record:
        from stemwise_engine.spoken_targets import PLAN_FILE
        reference = record['spokenTargets']
        if (not isinstance(reference, dict) or set(reference) != {'path', 'sha256'}
                or reference['path'] != PLAN_FILE or not isinstance(reference['sha256'], str)
                or not _SHA.fullmatch(reference['sha256'])):
            raise LyricsContentBlocker('pronunciation-support-unavailable')
    if 'ownerVerification' in record:
        reference = record['ownerVerification']
        if (not isinstance(reference, dict) or set(reference) != {'path', 'sha256'}
                or reference['path'] != OWNER_VERIFICATION_FILE
                or not isinstance(reference['sha256'], str) or not _SHA.fullmatch(reference['sha256'])
                or 'adjudication' in record):
            raise LyricsContentBlocker('content-evidence-invalid')
    return copy.deepcopy(record)


@dataclass(frozen=True)
class ContentAdmission:
    canonical_plain_sha256: str
    audio_sha256: str
    source_physical_indices: tuple[int, ...]
    evidence_path: str
    evidence_sha256: str
    adjudication: dict[str, str] | None = None
    spoken_targets: dict[str, str] | None = None
    owner_verification: dict[str, str] | None = None

    def __post_init__(self):
        validate_content_admission_record(self.to_record())
        object.__setattr__(self, 'adjudication', copy.deepcopy(self.adjudication))
        object.__setattr__(self, 'spoken_targets', copy.deepcopy(self.spoken_targets))
        object.__setattr__(self, 'owner_verification', copy.deepcopy(self.owner_verification))

    @classmethod
    def from_record(cls, record):
        value = validate_content_admission_record(record)
        evidence = value['evidence']
        return cls(value['canonicalPlainSha256'], value['audioSha256'],
            tuple(value['sourcePhysicalIndices']), evidence['path'], evidence['sha256'], value.get('adjudication'),
            value.get('spokenTargets'), value.get('ownerVerification'))

    @property
    def binding(self) -> dict[str, Any]:
        return _binding(self.source_physical_indices, self.canonical_plain_sha256, self.audio_sha256)

    def to_record(self) -> dict[str, Any]:
        return {"version": 1, **self.binding,
                "evidence": {"path": self.evidence_path, "sha256": self.evidence_sha256},
                **({'adjudication': dict(self.adjudication)} if self.adjudication is not None else {}),
                **({'spokenTargets': dict(self.spoken_targets)} if self.spoken_targets is not None else {}),
                **({'ownerVerification': dict(self.owner_verification)} if self.owner_verification is not None else {})}


def owner_verification(job_dir, video_id, original, indices, audio_sha):
    """Recheck an explicit owner decision, never infer it from recognition or timing."""
    from datetime import datetime
    from stemwise_engine.lyrics_intake import read_recording_draft
    from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, lexical_mapping_identity
    from stemwise_engine.spoken_targets import load, needed
    from stemwise_engine.common import canonical_lyrics_language
    job_dir = Path(job_dir)
    path = job_dir / OWNER_VERIFICATION_FILE
    if not path.exists() and not path.is_symlink():
        return None
    try:
        raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=job_dir)
        value = parse_strict_json_bytes(raw)
    except (OSError, ValueError) as error:
        raise LyricsContentBlocker('content-evidence-invalid') from error
    fields = {'formatVersion', 'videoId', 'audioSha256', 'draftSha256', 'metadataSha256',
        'mappingSha256', 'spokenTargets', 'reviewer', 'completedAt', 'decision', 'timing', 'rationale'}
    if (not isinstance(value, dict) or set(value) != fields or type(value['formatVersion']) is not int
            or value['formatVersion'] != 1 or value['videoId'] != video_id
            or value['decision'] != 'owner-verified-complete-performed-lyrics'
            or value['timing'] != 'allow-complete-predicted-words'
            or any(not isinstance(value[key], str) or not _SHA.fullmatch(value[key])
                   for key in ('audioSha256', 'draftSha256', 'metadataSha256', 'mappingSha256'))
            or any(not isinstance(value[key], str) or not value[key].strip()
                   or len(value[key]) > 2000 for key in ('reviewer', 'rationale'))
            or any(c in value['reviewer'] for c in '\r\n')
            or value['reviewer'].strip() == 'REPLACE_WITH_REVIEWER'
            or not isinstance(value['completedAt'], str)
            or re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z', value['completedAt']) is None):
        raise LyricsContentBlocker('content-evidence-invalid')
    try:
        datetime.strptime(value['completedAt'], '%Y-%m-%dT%H:%M:%SZ')
    except ValueError as error:
        raise LyricsContentBlocker('content-evidence-invalid') from error
    try:
        draft, draft_sha, metadata, metadata_sha = read_recording_draft(job_dir)
        expected_indices = performed_line_indices(original['text'])
        mapping = build_lexical_occurrence_mapping(video_id, original, expected_indices)
        if (not expected_indices or tuple(indices) != expected_indices or draft != original['text']
                or canonical_lyrics_language(original.get('language', 'und'))
                    != canonical_lyrics_language(metadata.get('originalLanguage', 'und'))
                or original.get('script') != metadata.get('originalScript')
                or value['audioSha256'] != audio_sha or value['draftSha256'] != draft_sha
                or value['metadataSha256'] != metadata_sha
                or value['mappingSha256'] != lexical_mapping_identity(mapping)['mappingSha256']):
            raise LyricsContentBlocker('content-binding-differs')
        plan = load(job_dir, video_id, original, indices, audio_sha)
        if needed(original) and plan is None:
            raise LyricsContentBlocker('pronunciation-support-unavailable')
        if value['spokenTargets'] != (plan.reference if plan is not None else None):
            raise LyricsContentBlocker('pronunciation-support-unavailable')
    except (LyricsContentBlocker, ContentEvidenceUnavailable):
        raise
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        raise ContentEvidenceUnavailable() from error
    return value, raw


def owner_workspace_admission(workspace, video_id, original, indices, canonical_sha, audio_sha, *, retain=True):
    from stemwise_engine.common import atomic_write_bytes, validate_video_id
    video_id = validate_video_id(video_id)
    _binding(indices, canonical_sha, audio_sha)
    job_dir = workspace.root.parent / 'jobs' / video_id
    verified = owner_verification(job_dir, video_id, original, indices, audio_sha)
    if verified is None:
        return None
    _, raw = verified
    digest = hashlib.sha256(raw).hexdigest()
    evidence = workspace.job_dir(video_id) / f'owner-content-{digest}.private.json'
    if evidence.exists() or evidence.is_symlink():
        if read_bounded_regular_bytes(evidence, max_bytes=MAX_JSON_BYTES, root=evidence.parent) != raw:
            raise ContentEvidenceUnavailable('content-evidence-changed')
    elif retain:
        atomic_write_bytes(evidence, raw, root=evidence.parent, workspace_root=workspace.workspace_root)
    else:
        raise ContentEvidenceUnavailable('content-evidence-unavailable')
    return confirm_content(original, indices, canonical_sha, audio_sha, evidence_path=evidence,
        evidence_root=evidence.parent, adjudication_job_dir=job_dir)


def _surfaces(text: str, language: str | None = None) -> tuple[str, ...]:
    from stemwise_engine.lyric_comparison import units
    return units(text, language)


def _line_units(original, source):
    from stemwise_engine.spoken_targets import attached
    from stemwise_engine.lyric_comparison import project
    plan = attached(original) if isinstance(original, dict) else None
    if plan is None:
        text = original['text'] if isinstance(original, dict) else original
        return _surfaces(physical_lines(text)[source], original.get('language') if isinstance(original, dict) else None)
    plan.require_current()
    if original['text'] != plan.original_text:
        raise LyricsContentBlocker('pronunciation-support-unavailable')
    return project(physical_lines(original['text'])[source], forms=plan.words[source]).units


def exact_line_anchors(original, indices, recognized, *, untimed_indices=frozenset()):
    """Return coarse raw-word owners, never content or word-timing authority.

    One full-sequence edit graph preserves contrary and ambiguous observations.
    A raw word is usable only when all its units have the same unique line owner.
    """
    from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
    from stemwise_engine.lyrics_tokenization import optimal_edit_owners
    from stemwise_engine.lyric_comparison import project, fold_units
    from stemwise_engine.spoken_targets import attached
    if (not isinstance(indices, (tuple, list)) or not indices
            or any(type(source) is not int or source < 0 for source in indices)
            or not isinstance(recognized, (tuple, list)) or not recognized):
        return None
    language = original.get('language') if isinstance(original, dict) else None
    expected, line_owners, actual, raw_ranges = [], [], [], []
    try:
        for performed, source in enumerate(indices):
            line = _line_units(original, source)
            if not line:
                return None
            expected.extend(line)
            line_owners.extend([performed]*len(line))
        raw_units = []
        for word in recognized:
            projection = project(word['raw'] if isinstance(word, dict) else word.raw, language)
            raw_ranges.append((len(actual), len(actual)+len(projection.units)))
            actual.extend(projection.units)
            raw_units.extend(projection.raw_units)
    except (IndexError, KeyError, TypeError, AttributeError, ValueError):
        return None
    plan = attached(original) if isinstance(original, dict) else None
    if plan is not None:
        languages = [owner_language for source in indices for spoken, owner_language in plan.words[source]
            for _ in _surfaces(spoken, owner_language)]
        if len(raw_units) != len(languages):
            return None
        actual = fold_units(raw_units, languages)
    try:
        graph = optimal_edit_owners(expected, actual)
    except AcousticBudgetExceeded:
        return None
    possible = [[] for _ in actual]
    for target, matches in enumerate(graph['matches']):
        for observed in matches:
            possible[observed].append(target)
    exact = {}
    for target, matches in enumerate(graph['matches']):
        if (target not in graph['deleted'] and target not in graph['substituted']
                and len(matches) == 1 and len(possible[matches[0]]) == 1
                and matches[0] not in graph['affectedActual']):
            exact[matches[0]] = target
    anchors = {}
    for raw_index, (start, stop) in enumerate(raw_ranges):
        # All words participate in repeat disambiguation, even without a usable clock.
        if raw_index in untimed_indices:
            continue
        targets = [exact.get(unit) for unit in range(start, stop)]
        if not targets or any(target is None for target in targets):
            continue
        owner = line_owners[targets[0]]
        if (targets != list(range(targets[0], targets[0]+len(targets)))
                or any(line_owners[target] != owner for target in targets)):
            continue
        if owner not in anchors:
            anchors[owner] = {'performedIndex': owner, 'sourcePhysicalIndex': indices[owner],
                'rawWordIndex': raw_index, 'matchedUnitCount': 0}
        anchors[owner]['matchedUnitCount'] += len(targets)
    return tuple(anchors[index] for index in range(len(indices))) if len(anchors) == len(indices) else None


def reference_adaptation(original, recognized):
    """Describe a proposed performed text; never approve words or infer timestamps."""
    from difflib import SequenceMatcher
    from stemwise_engine.lyric_comparison import project
    language = original.get('language')
    lines = physical_lines(original['text'])
    expected, owners, starts, stops, surfaces, surface_sources = [], [], set(), set(), [], []
    word_boundaries = {0: 0}
    for source, line in enumerate(lines):
        projection = project(line, language)
        units = projection.units
        if units:
            starts.add(len(expected))
            for word, (_, _, surface) in enumerate(projection.words):
                word_boundaries[len(expected)+projection.boundaries[word]] = len(surfaces)
                surfaces.append(surface)
                surface_sources.append(source)
            expected.extend(units)
            owners.extend([source]*len(units))
            word_boundaries[len(expected)] = len(surfaces)
            stops.add(len(expected))
    actual = _surfaces(_recognized_text(recognized), language)
    if not expected or not actual or tuple(expected) == actual:
        return None
    def known_repeat(span, start, stop):
        for size in range(1, len(span)+1):
            if len(span) % size:
                continue
            unit = span[:size]
            if unit*(len(span)//size) == span and (
                    expected[max(0, start-size):start] == unit or expected[stop:stop+size] == unit):
                before = expected[max(0, start-size):start] == unit
                anchor = (start-size, start) if before else (stop, stop+size)
                if all(index in word_boundaries for index in anchor):
                    return (*anchor, len(span)//size)
        return None
    changes, edits = [], []
    for operation, start, stop, left, right in SequenceMatcher(None, expected, actual, autojunk=False).get_opcodes():
        if operation == 'equal':
            continue
        kind = 'unresolved-wording'
        ranges = [(start, stop)]
        if operation == 'delete':
            # Repeated boundary words can give the same deletion several source owners.
            begin, end = start, stop
            while end < len(expected) and expected[begin] == expected[end]:
                begin, end = begin+1, end+1
                ranges.append((begin, end))
            begin, end = start, stop
            while begin > 0 and expected[begin-1] == expected[end-1]:
                begin, end = begin-1, end-1
                ranges.append((begin, end))
        repeat = known_repeat(list(actual[left:right]), start, stop) if operation == 'insert' else None
        if repeat is not None and start in word_boundaries and stop in word_boundaries:
            kind = 'repeat-known-phrase'
        elif operation == 'delete' and start in word_boundaries and stop in word_boundaries:
            if known_repeat(expected[start:stop], start, stop):
                kind = 'reduce-known-repeat'
            elif any(begin in starts and end in stops for begin, end in ranges):
                kind = 'omit-reference-lines'
                original_start = start
                start, stop = next((begin, end) for begin, end in sorted(ranges)
                    if begin in starts and end in stops)
                left = right = left+start-original_start
        edits.append((start, stop, repeat))
        changes.append({'kind': kind, 'referenceUnitRange': [start, stop],
            'referenceUnitRangeCandidates': [list(value) for value in sorted(ranges)],
            'recognizedUnitRange': [left, right],
            'affectedSourcePhysicalIndices': sorted({owner for begin, end in ranges
                for owner in (owners[begin:end] if begin < end
                    else owners[max(0, begin-1):min(len(owners), begin+1)])})})
    summary = {'version': 1, 'status': 'unresolved' if any(
        change['kind'] == 'unresolved-wording' for change in changes) else 'review-required',
        'changes': changes, 'publicationAuthority': False, 'timingAuthority': False}
    if summary['status'] == 'unresolved':
        return summary, None
    projected = [(surface, owner, 0) for surface, owner in zip(surfaces, surface_sources)]
    previous, copy_id = len(expected), 0
    for start, stop, repeat in reversed(edits):
        if stop > previous:
            summary['status'] = 'unresolved-layout'
            return summary, None
        previous = start
        replacement = []
        if repeat is not None:
            begin, end, count = repeat
            complete_lines = begin in starts and end in stops
            for _ in range(count):
                copy_id += 1
                replacement.extend((surfaces[index], surface_sources[index], copy_id if complete_lines else 0)
                    for index in range(word_boundaries[begin], word_boundaries[end]))
        projected[word_boundaries[start]:word_boundaries[stop]] = replacement
    if tuple(unit for surface, _, _ in projected for unit in _surfaces(surface, language)) != actual:
        summary['status'] = 'unresolved-layout'
        return summary, None
    result, result_sources, group, key = [], [], [], None
    def finish():
        if group:
            if result_sources:
                previous_source, previous_copy = result_sources[-1]
                if ((key[0] > previous_source and any(not line.strip()
                        for line in lines[previous_source+1:key[0]]))
                        or (key[1] != previous_copy and key[0] > 0 and not lines[key[0]-1].strip())):
                    result.append('')
            source_words = [surface for _, _, surface in display_words(lines[key[0]])]
            result.append(lines[key[0]].strip() if group == source_words else ' '.join(group))
            result_sources.append(key)
    for surface, source, copy_id in projected:
        if key != (source, copy_id):
            finish()
            group, key = [], (source, copy_id)
        group.append(surface)
    finish()
    return summary, '\n'.join(result)+'\n'


def diagnostic_discrepancy_scope(original, indices, recognized, *, budget=None):
    """Retain every possible discrepancy owner under the bounded edit policy."""
    from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
    from stemwise_engine.lyrics_tokenization import optimal_edit_owners
    from stemwise_engine.lyric_comparison import project, fold_units
    from stemwise_engine.spoken_targets import attached
    language = original.get('language') if isinstance(original, dict) else None
    expected, owners, word_owners, languages, word_count = [], [], [], [], 0
    plan = attached(original) if isinstance(original, dict) else None
    if plan is not None:
        plan.require_current()
    for performed, source in enumerate(indices):
        units = _line_units(original, source)
        text = original['text'] if isinstance(original, dict) else original
        projection = project(physical_lines(text)[source], language,
            forms=plan.words[source] if plan is not None else None)
        expected.extend(units)
        owners.extend([performed]*len(units))
        word_owners.extend(word_count+owner for owner in projection.owners)
        languages.extend(projection.languages)
        word_count += len(projection.words)
    actual_projection = project(_recognized_text(recognized), language)
    actual = (fold_units(actual_projection.raw_units, languages) if plan is not None
        and len(actual_projection.raw_units) == len(languages) else actual_projection.units)
    try:
        graph = optimal_edit_owners(expected, actual, budget=budget)
    except AcousticBudgetExceeded:
        return {'version': 1, 'complete': False, 'possiblePerformedIndices': list(range(len(indices))),
            'targetWordIndices': list(range(word_count)), 'recognizedWordIndices': list(range(len(actual_projection.words))),
            'optimalEditCost': None}
    affected = graph['affectedExpected']
    return {'version': 1, 'complete': True,
        'possiblePerformedIndices': sorted({owners[index] for index in affected}),
        'targetWordIndices': sorted({word_owners[index] for index in affected}),
        'recognizedWordIndices': sorted({actual_projection.owners[index] for index in graph['affectedActual']}),
        'optimalEditCost': graph['cost']}


def require_exact_sequence(original, indices: Sequence[int], recognized: str | Sequence[Any],
                           canonical_sha: str | None, audio_sha: str | None) -> None:
    binding = None if canonical_sha is None and audio_sha is None else _binding(indices, canonical_sha, audio_sha)
    if (not isinstance(indices, (list, tuple)) or not indices
            or any(type(index) is not int or index < 0 for index in indices)):
        raise LyricsContentBlocker("content-occurrences-unavailable", binding=binding)
    text = original.get("text") if isinstance(original, dict) else original
    if not isinstance(text, str):
        raise LyricsContentBlocker("content-text-unavailable", binding=binding)
    language = original.get("language") if isinstance(original, dict) else None
    try:
        expected = tuple(word for index in indices for word in _line_units(original, index))
        if any(not _line_units(original, index) for index in indices):
            raise IndexError
    except IndexError:
        raise LyricsContentBlocker("content-occurrences-unavailable", binding=binding) from None
    if isinstance(recognized, str):
        actual = _surfaces(recognized, language)
    else:
        try:
            actual = tuple(word for item in recognized for word in _surfaces(
                item["raw"] if isinstance(item, dict) else item.raw, language))
        except (KeyError, AttributeError, TypeError):
            raise LyricsContentBlocker("content-word-evidence-invalid", binding=binding) from None
    from stemwise_engine.spoken_targets import attached
    plan = attached(original) if isinstance(original, dict) else None
    if plan is not None:
        from stemwise_engine.lyric_comparison import project, fold_units
        languages = [language for source in indices for spoken, language in plan.words[source]
                     for _ in _surfaces(spoken, language)]
        raw = recognized if isinstance(recognized, str) else _recognized_text(recognized)
        raw_units = project(raw).raw_units
        actual = fold_units(raw_units, languages) if len(raw_units) == len(languages) else ()
    if not expected or actual != expected:
        error = LyricsContentBlocker("content-sequence-uncertain", binding=binding)
        error.discrepancy_scope = diagnostic_discrepancy_scope(original, indices, recognized)
        raise error


def recording_review_input_reference(job_dir, evidence_root, audio_sha, recognition):
    """Body-free identities for preparing an explicit review, never approval."""
    from stemwise_engine.lyrics_intake import read_recording_draft
    _, draft_sha, _, metadata_sha = read_recording_draft(job_dir)
    validate_content_admission_record({'version': 1, 'canonicalPlainSha256': draft_sha,
        'audioSha256': audio_sha, 'sourcePhysicalIndices': [0], 'evidence': recognition})
    raw = read_bounded_regular_bytes(evidence_root / recognition['path'],
        max_bytes=16*1024*1024, root=evidence_root)
    if hashlib.sha256(raw).hexdigest() != recognition['sha256']:
        raise ValueError('Retained recording evidence changed')
    return {'audioSha256': audio_sha, 'draftSha256': draft_sha,
            'metadataSha256': metadata_sha, 'recognition': dict(recognition)}


def validate_recording_review_inputs(job_dir, evidence_root, value):
    if not isinstance(value, dict) or set(value) != {
            'audioSha256', 'draftSha256', 'metadataSha256', 'recognition'}:
        raise ValueError('Recording review inputs are invalid')
    current = recording_review_input_reference(job_dir, evidence_root, value['audioSha256'], value['recognition'])
    if current != value:
        raise ValueError('Recording review inputs changed')
    return current



def validate_recording_adjudication_record(value, video_id):
    """An explicit private span decision, never ASR verification or timing authority."""
    import time
    keys = {'formatVersion', 'videoId', 'audioSha256', 'draftSha256', 'metadataSha256',
            'recognition', 'reviewer', 'completedAt', 'spans'}
    if (not isinstance(value, dict) or set(value) != keys or type(value['formatVersion']) is not int
            or value['formatVersion'] != 2 or value['videoId'] != video_id
            or any(not isinstance(value[key], str) or not _SHA.fullmatch(value[key])
                   for key in ('audioSha256', 'draftSha256', 'metadataSha256'))
            or not isinstance(value['reviewer'], str) or not value['reviewer'].strip()
            or len(value['reviewer']) > 200 or any(c in value['reviewer'] for c in '\r\n')
            or value['reviewer'].strip() == 'REPLACE_WITH_REVIEWER'
            or not isinstance(value['completedAt'], str)
            or re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z', value['completedAt']) is None
            or not isinstance(value['spans'], list) or not 1 <= len(value['spans']) <= 128):
        raise LyricsContentBlocker('recording-adjudication-invalid')
    try:
        time.strptime(value['completedAt'], '%Y-%m-%dT%H:%M:%SZ')
        validate_content_admission_record({'version': 1, 'canonicalPlainSha256': value['draftSha256'],
            'audioSha256': value['audioSha256'], 'sourcePhysicalIndices': [0],
            'evidence': value['recognition']})
    except (ValueError, TypeError):
        raise LyricsContentBlocker('recording-adjudication-invalid') from None
    span_keys = {'performedIndex', 'sourcePhysicalIndex', 'startWordIndex', 'endWordIndex',
                 'recognizedStartWordIndex', 'recognizedEndWordIndex', 'startMs', 'endMs',
                 'decision', 'heardText', 'rationale'}
    differences = {'confirmed-asr-omission': (True, False),
                   'confirmed-asr-substitution': (True, True), 'confirmed-asr-insertion': (False, True)}
    for span in value['spans']:
        if (not isinstance(span, dict) or set(span) != span_keys
                or any(type(span[key]) is not int or span[key] < 0 for key in (
                    'performedIndex', 'sourcePhysicalIndex', 'startWordIndex', 'endWordIndex',
                    'recognizedStartWordIndex', 'recognizedEndWordIndex', 'startMs', 'endMs'))
                or span['startWordIndex'] > span['endWordIndex'] or span['startMs'] >= span['endMs']
                or span['recognizedStartWordIndex'] > span['recognizedEndWordIndex']
                or not isinstance(span['decision'], str) or span['decision'] not in differences
                or differences[span['decision']] != (span['startWordIndex'] < span['endWordIndex'],
                    span['recognizedStartWordIndex'] < span['recognizedEndWordIndex'])
                or any(not isinstance(span[key], str) or len(span[key]) > 1000
                       or any(c in span[key] for c in '\r\n') for key in ('heardText', 'rationale'))
                or not span['rationale'].strip()
                or bool(span['heardText'].strip()) != (span['startWordIndex'] < span['endWordIndex'])):
            raise LyricsContentBlocker('recording-adjudication-invalid')
    return copy.deepcopy(value)


def require_recording_adjudication(value, *, video_id, original, indices, transcript,
        audio_sha, duration_ms, draft_sha, metadata_sha, evidence_path, evidence_root):
    """Resolve only exact independently reviewed differences in retained recognition.

    The reviewed audio spans are content evidence and are never returned as timings.
    Every other recognized word, pronunciation mark and occurrence must remain exact.
    """
    value = validate_recording_adjudication_record(value, video_id)
    if (value['audioSha256'] != audio_sha or value['draftSha256'] != draft_sha
            or value['metadataSha256'] != metadata_sha):
        raise LyricsContentBlocker('recording-adjudication-stale')
    try:
        raw = read_bounded_regular_bytes(evidence_path, max_bytes=MAX_JSON_BYTES, root=evidence_root)
    except (OSError, ValueError) as error:
        raise ContentEvidenceUnavailable() from error
    if (Path(evidence_path).resolve().relative_to(Path(evidence_root).resolve()).as_posix()
            != value['recognition']['path'] or hashlib.sha256(raw).hexdigest() != value['recognition']['sha256']):
        raise ContentEvidenceUnavailable('content-evidence-changed')
    language = original.get('language')
    physical = physical_lines(original['text'])
    if (not isinstance(indices, (list, tuple)) or not indices
            or any(type(index) is not int or not 0 <= index < len(physical)
                   or not physical[index].strip() for index in indices)):
        raise LyricsContentBlocker('content-occurrences-unavailable')
    from stemwise_engine.spoken_targets import attached
    plan = attached(original)
    require_pronunciation_support(original['text'], plan)
    lines = [plan.words[index] if plan is not None else
             tuple((text, language) for _, _, text in display_words(physical[index])) for index in indices]
    units = [[_surfaces(text, owner) for text, owner in line] for line in lines]
    expected = tuple(unit for line in units for word in line for unit in word)
    languages = tuple(owner for line in lines for text, owner in line for _ in _surfaces(text, owner))
    actual = tuple(text for _, _, text in display_words(transcript))
    def matches(start, end, recognized):
        from stemwise_engine.lyric_comparison import project, fold_units
        raw_units = tuple(unit for text in recognized for unit in project(text).raw_units)
        return (len(raw_units) == end-start
            and expected[start:end] == fold_units(raw_units, languages[start:end]))
    expected_cursor = actual_cursor = previous_ms = 0
    for span in value['spans']:
        performed = span['performedIndex']
        if (performed >= len(indices) or span['sourcePhysicalIndex'] != indices[performed]
                or span['endWordIndex'] > len(lines[performed])
                or not previous_ms <= span['startMs'] < span['endMs'] <= duration_ms):
            raise LyricsContentBlocker('recording-adjudication-span-invalid')
        offset = sum(len(word) for line in units[:performed] for word in line)
        start = offset + sum(map(len, units[performed][:span['startWordIndex']]))
        end = offset + sum(map(len, units[performed][:span['endWordIndex']]))
        recognized, recognized_end = span['recognizedStartWordIndex'], span['recognizedEndWordIndex']
        if (start < expected_cursor or not actual_cursor <= recognized <= recognized_end <= len(actual)
                or not matches(expected_cursor, start, actual[actual_cursor:recognized])
                or not matches(start, end, tuple(text for _, _, text in display_words(span['heardText'])))
                or matches(start, end, actual[recognized:recognized_end])):
            raise LyricsContentBlocker('recording-adjudication-content-differs')
        expected_cursor, actual_cursor, previous_ms = end, recognized_end, span['endMs']
    if not matches(expected_cursor, len(expected), actual[actual_cursor:]):
        raise LyricsContentBlocker('recording-adjudication-content-differs')
    return {'kind': 'explicit-recording-span-adjudication', 'recognition': value['recognition'],
            'spanCount': len(value['spans']), 'audioSha256': audio_sha, 'draftSha256': draft_sha}


def _recognized(cache: dict[str, Any], audio_sha: str, *, candidate_only=False):
    """Recheck the retained producer closure; never accept an approval boolean."""
    if cache.get('kind') == 'faster-whisper-untimed-lexical':
        from stemwise_engine.whisper_lexical_candidate import recognized
        return recognized(cache, audio_sha, candidate_only=candidate_only)
    if cache.get('kind') == 'nemo-ctc-untimed-lexical':
        from stemwise_engine.nemo_lexical_candidate import recognized
        return recognized(cache, audio_sha, candidate_only=candidate_only)
    provenance = cache.get("provenance")
    if not isinstance(provenance, dict):
        raise ValueError("missing recognition provenance")
    if provenance.get("familyId") == "mms-asr":
        import stemwise_engine.mms_asr as mms_asr
        from stemwise_engine.common import sha256_file
        helpers = provenance.get("helpers")
        names = ("mms_asr.py", "endpoint_ctc_evidence.py", "wav2vec2_forced_alignment.py",
                 "acoustic_inputs.py", "ctc_numerics.py", "ctc_worker.py", "worker_protocol.py",
                 "worker_publication.py", "model_registry.py", "model-route-registry.json")
        if (provenance.get("promptMode") != "withheld" or provenance.get("audioSha256") != audio_sha
                or helpers != {name: semantic_source_identity(Path(__file__).parent / name) for name in names}):
            raise ValueError("recognition producer/audio differs")
        return mms_asr.validate_output(cache.get("workerOutput"), audio_sha,
            provenance["durationMs"], provenance["adapter"], provenance["runtime"])
    if 'modelRevision' in provenance and 'workerOutput' in cache:
        from stemwise_engine.model_registry import model_assets
        from stemwise_engine.qwen3_asr_transcribe import _validated_output, DECODER
        pins = model_assets('lyrics_align_pc')
        if (cache.get('cacheVersion') != 1
                or provenance.get('audio', {}).get('sha256') != audio_sha
                or provenance.get('modelRevision') != pins['QWEN3_ASR_REVISION']
                or provenance.get('modelSnapshotSha256') != pins['QWEN3_ASR_SNAPSHOT_SHA256']
                or provenance.get('decoder') != DECODER
                or provenance.get('helperSha256') != semantic_source_identity(Path(__file__).with_name('qwen3_asr_transcribe.py'))):
            raise ValueError('recognition producer/audio differs')
        output = _validated_output(cache['workerOutput'], {
            'audio': provenance['audio'], 'language': provenance['language'],
            'model': {'revision': pins['QWEN3_ASR_REVISION'], 'snapshotSha256': pins['QWEN3_ASR_SNAPSHOT_SHA256']}})
        if output['runtime'] != provenance.get('runtime', {}).get('runtime'):
            raise ValueError('recognition runtime differs')
        return output['result']['transcription']
    if "words" in cache:
        from stemwise_engine.lyrics_align_pc import _whisper_recognition_cache_identity
        from stemwise_engine.lyrics_alignment import TRANSCRIPT_CACHE_VERSION
        if (cache.get("cacheVersion") != TRANSCRIPT_CACHE_VERSION
                or cache.get("cacheIdentity") != _whisper_recognition_cache_identity()
                or cache.get("promptSha256") != hashlib.sha256(b"").hexdigest()
                or provenance.get("decoder", {}).get("promptMode") != "withheld"
                or provenance.get("audio", {}).get("sha256") != audio_sha):
            raise ValueError("recognition producer/audio differs")
        words = cache["words"]
        previous = 0.0
        if not isinstance(words, list) or not words:
            raise ValueError("missing recognized words")
        for word in words:
            if (not isinstance(word, dict) or not isinstance(word.get("raw"), str)
                    or any(type(word.get(key)) not in (int, float) or not math.isfinite(word[key])
                           for key in ("start", "end"))
                    or not previous <= word["start"] <= word["end"] <= provenance["durationMs"] / 1000 + .001
                    or (not candidate_only and word["start"] == word["end"])):
                raise ValueError("invalid recognized words")
            # Lexical candidates may overlap; timing admission still requires disjoint words.
            previous = word["start"] if candidate_only else word["end"]
        return words
    raise ValueError("unsupported recognition artifact")


def _recognized_text(words):
    if isinstance(words, str):
        return words
    return ' '.join(word['raw'] if isinstance(word, dict) else word.raw for word in words)


def _recognition_family(cache):
    provenance = cache.get('provenance', {})
    if provenance.get('familyId') == 'mms-asr':
        return 'mms-asr'
    if 'modelRevision' in provenance and 'workerOutput' in cache:
        return 'qwen3-asr'
    if 'words' in cache:
        return 'faster-whisper'
    raise ValueError('Unsupported content investigation family')


def _investigation_capable(cache, original):
    from stemwise_engine.lyrics_alignment import canonical_language
    family = _recognition_family(cache)
    provenance = cache['provenance']
    if family == 'mms-asr':
        import stemwise_engine.mms_asr as mms_asr
        return provenance.get('adapter') in mms_asr.adapters_for(original)
    declared = original.get('componentLanguages', []) if original.get('language') == 'mul' else [original.get('language')]
    languages = [canonical_language(language) for language in declared]
    if not languages or any(language is None for language in languages):
        return False
    if family == 'qwen3-asr':
        supported = provenance.get('runtime', {}).get('supportedLanguages', [])
    else:
        from faster_whisper.tokenizer import _LANGUAGE_CODES
        supported = _LANGUAGE_CODES
    return all(language in supported for language in languages)


def investigate_retained_content(original, indices, audio_sha, duration_ms, *, evidence_root,
                                contrary_path, canonical_sha=None):
    """Assess a frozen full-recording, two-family bundle without new acoustic work.

    Recording edges supply independent context; absent narrower anchors never justify
    an inferred crop. The complete target resolves the entire possible discrepancy scope.
    """
    from stemwise_engine.common import atomic_write_json
    from stemwise_engine.provenance import content_assessment_identity
    root = Path(evidence_root)
    if type(duration_ms) is not int or duration_ms <= 0 or not _SHA.fullmatch(audio_sha):
        raise ValueError('Content investigation recording binding is invalid')
    contrary_path = Path(contrary_path)
    contrary_raw = read_bounded_regular_bytes(contrary_path, max_bytes=MAX_JSON_BYTES, root=root)
    contrary = parse_strict_json_bytes(contrary_raw)
    contrary_words = _recognized(contrary, audio_sha)
    if not _investigation_capable(contrary, original):
        return None
    contrary_provenance = contrary['provenance']
    contrary_duration = contrary_provenance.get('durationMs',
        contrary_provenance.get('audio', {}).get('stream', {}).get('durationMs'))
    if contrary_duration != duration_ms:
        return None
    family = _recognition_family(contrary)
    scope = diagnostic_discrepancy_scope(original, indices, contrary_words)
    if not scope['complete'] or not scope['optimalEditCost']:
        return None
    required = (family, 'qwen3-asr' if family != 'qwen3-asr' else 'faster-whisper')
    candidates = []
    patterns = ('native-asr-*-mix.private.json', 'qwen-structure*.private.json', 'transcript*.private.json')
    paths = sorted({path for pattern in patterns for path in root.glob(pattern)})
    skipped = False
    for path in paths:
        try:
            raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=root)
            cache = parse_strict_json_bytes(raw)
            words = _recognized(cache, audio_sha)
            candidate_family = _recognition_family(cache)
            if not _investigation_capable(cache, original):
                continue
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            if not skipped:
                import sys
                print('optional-content-cache-ineligible', file=sys.stderr)
            skipped = True
            continue  # Producer/coverage-ineligible artifacts have no vote.
        provenance = cache['provenance']
        duration = provenance.get('durationMs', provenance.get('audio', {}).get('stream', {}).get('durationMs'))
        if duration != duration_ms:
            continue
        reference = {'path': path.relative_to(root).as_posix(), 'sha256': hashlib.sha256(raw).hexdigest()}
        candidates.append((candidate_family, reference, provenance, words))
    contrary_reference = {'path': contrary_path.resolve().relative_to(root.resolve()).as_posix(),
        'sha256': hashlib.sha256(contrary_raw).hexdigest()}
    # Select by the frozen family/file order, before examining lexical agreement.
    slots = []
    for required_family in required:
        candidate = next((entry for entry in candidates if entry[0] == required_family
            and entry[1] != contrary_reference), None)
        if candidate is None:
            return None
        slots.append(candidate)
    target = tuple(word for index in indices for word in _surfaces(
        physical_lines(original['text'])[index], original.get('language')))
    request = {'version': 1, 'audioSha256': audio_sha, 'canonicalPlainSha256': canonical_sha,
        'canonicalRangeMs': [0, duration_ms], 'contextSourcePhysicalIndices': list(indices),
        'resolvedPerformedIndices': list(range(len(indices))), 'discrepancyScope': scope,
        'comparisonPolicy': 'complete-ordered-lexical-v1',
        'assessmentPolicy': content_assessment_identity(),
        'contextTargetSha256': hashlib.sha256('\0'.join(target).encode('utf-8')).hexdigest(),
        'contraryEvidence': contrary_reference, 'maxNewDecodes': 2, 'spentNewDecodes': 0,
        'slots': [{'familyId': entry[0], 'evidence': entry[1], 'operationIdentity': entry[2]}
            for entry in slots]}
    request_sha = hashlib.sha256(json.dumps(request, sort_keys=True,
        ensure_ascii=False, separators=(',', ':')).encode('utf-8')).hexdigest()
    request_path = root / f'content-investigation-{request_sha}.private.json'
    if request_path.exists():
        if read_bounded_json(request_path, max_bytes=MAX_JSON_BYTES, root=root) != request:
            raise ContentEvidenceUnavailable('content-evidence-changed',
                binding=_binding(indices, canonical_sha, audio_sha) if canonical_sha is not None else None)
    else:
        atomic_write_json(request_path, request, root=root)
    supporting = []
    for candidate_family, reference, provenance, words in slots:
        if _surfaces(_recognized_text(words), original.get('language')) != target:
            return None
        supporting.append(reference)
    superseded = [contrary_reference]
    for candidate_family, reference, provenance, words in candidates:
        if _surfaces(_recognized_text(words), original.get('language')) != target:
            if candidate_family not in required:
                return None
            superseded.append(reference)
    # Re-read every retained observation after assessment before returning authority.
    for reference in supporting + superseded:
        raw = read_bounded_regular_bytes(root / reference['path'], max_bytes=MAX_JSON_BYTES, root=root)
        if hashlib.sha256(raw).hexdigest() != reference['sha256']:
            raise ContentEvidenceUnavailable('content-evidence-changed')
    return {'kind': 'two-family-complete-context', 'disposition': 'diagnostic-content-corroboration',
        'publicationAuthority': False, 'request': {'path': request_path.name,
        'sha256': hashlib.sha256(request_path.read_bytes()).hexdigest()},
        'supportingEvidence': supporting, 'supersededEvidence': [dict(items) for items in
            dict.fromkeys(tuple(sorted(reference.items())) for reference in superseded)],
        'resolvedPerformedIndices': list(range(len(indices))), 'newDecodeCount': 0}


def _require_matching_adjudication(original, indices, words, audio_sha, *, cache,
        evidence_path, evidence_sha, evidence_root, job_dir):
    from stemwise_engine.lyrics_intake import read_recording_draft
    from stemwise_engine.common import canonical_lyrics_language
    job_dir = Path(job_dir)
    draft, draft_sha, metadata, metadata_sha = read_recording_draft(job_dir)
    if (physical_lines(original['text']) != physical_lines(draft)
            or canonical_lyrics_language(original.get('language', 'und'))
                != canonical_lyrics_language(metadata.get('originalLanguage', 'und'))):
        raise LyricsContentBlocker('recording-adjudication-stale')
    raw = read_bounded_regular_bytes(job_dir / ADJUDICATION_FILE, max_bytes=MAX_JSON_BYTES, root=job_dir)
    review = validate_recording_adjudication_record(parse_strict_json_bytes(raw), job_dir.name)
    if review['recognition'] != {'path': Path(evidence_path).resolve().relative_to(
            Path(evidence_root).resolve()).as_posix(), 'sha256': evidence_sha}:
        # This is valid contrary recognition, not a technically broken cache.
        raise LyricsContentBlocker('recording-adjudication-stale')
    duration = cache['provenance'].get('durationMs')
    if duration is None:
        duration = cache['provenance']['audio']['stream']['durationMs']
    if type(duration) is not int or duration <= 0:
        raise ValueError('recognition duration is invalid')
    require_recording_adjudication(review, video_id=job_dir.name, original=original, indices=indices,
        transcript=_recognized_text(words), audio_sha=audio_sha, duration_ms=duration,
        draft_sha=draft_sha, metadata_sha=metadata_sha, evidence_path=evidence_path, evidence_root=evidence_root)
    return {'path': ADJUDICATION_FILE, 'sha256': hashlib.sha256(raw).hexdigest()}


def source_recording_admission(job_dir, evidence_root, video_id, original, indices, canonical_sha, audio_sha):
    """Read the workflow's current source/recording decision, never select a new witness."""
    job_dir, evidence_root = Path(job_dir), Path(evidence_root)
    path = job_dir/'job.json'
    if not path.is_file():
        return None
    job = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=job_dir)
    proof = job.get('recordingContentEvidence')
    if not isinstance(proof, dict):
        return None
    reference = proof.get('evidence')
    if not isinstance(reference, dict):
        return None
    checked = validate_content_admission_record({'version': 1, **_binding(indices, canonical_sha, audio_sha),
        'evidence': reference})
    raw = read_bounded_regular_bytes(evidence_root/checked['evidence']['path'],
        max_bytes=MAX_JSON_BYTES, root=evidence_root)
    if hashlib.sha256(raw).hexdigest() != reference['sha256']:
        raise ContentEvidenceUnavailable('content-evidence-changed')
    value = parse_strict_json_bytes(raw)
    from stemwise_engine.source_recording_content import KIND
    if not isinstance(value, dict) or value.get('kind') != KIND:
        return None
    return confirm_content(original, indices, canonical_sha, audio_sha,
        evidence_path=evidence_root/reference['path'], evidence_root=evidence_root,
        adjudication_job_dir=job_dir)


def source_recording_workspace_admission(workspace, video_id, original, indices, canonical_sha, audio_sha):
    return source_recording_admission(workspace.root.parent/'jobs'/video_id, workspace.job_dir(video_id),
        video_id, original, indices, canonical_sha, audio_sha)


def is_source_recording_admission(admission, evidence_root):
    """Identify an already validated admission; this read alone does not approve it."""
    from stemwise_engine.source_recording_content import KIND
    raw = read_bounded_regular_bytes(Path(evidence_root)/admission.evidence_path,
        max_bytes=MAX_JSON_BYTES, root=evidence_root)
    if hashlib.sha256(raw).hexdigest() != admission.evidence_sha256:
        raise ContentEvidenceUnavailable('content-evidence-changed', binding=admission.binding)
    value = parse_strict_json_bytes(raw)
    return isinstance(value, dict) and value.get('kind') == KIND


def reviewed_workspace_admission(workspace, video_id, original, indices, canonical_sha, audio_sha):
    """Recheck one explicit matching decision; absence grants no additional authority."""
    owner = owner_workspace_admission(workspace, video_id, original, indices, canonical_sha, audio_sha)
    if owner is not None:
        return owner
    source = source_recording_workspace_admission(workspace, video_id, original, indices, canonical_sha, audio_sha)
    if source is not None:
        return source
    job_dir = workspace.root.parent / 'jobs' / video_id
    path = job_dir / ADJUDICATION_FILE
    if not path.exists() and not path.is_symlink():
        return None
    review = validate_recording_adjudication_record(read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=job_dir), video_id)
    admission = confirm_content(original, indices, canonical_sha, audio_sha,
        evidence_path=workspace.job_dir(video_id) / review['recognition']['path'],
        evidence_root=workspace.job_dir(video_id), adjudication_job_dir=job_dir)
    if admission.adjudication is None:
        raise LyricsContentBlocker('recording-adjudication-content-differs')
    return admission


def require_admitted_sequence(admission, original, indices, recognized, *, evidence_path=None, evidence_root,
                              adjudication_job_dir=None):
    """A decision applies only to its original recognizer output, never a new witness."""
    if (admission.adjudication is not None or admission.spoken_targets is not None
            or admission.owner_verification is not None or is_source_recording_admission(admission, evidence_root)):
        admission = require_content(admission.to_record(), original, indices, admission.canonical_plain_sha256,
            admission.audio_sha256, evidence_root, adjudication_job_dir=adjudication_job_dir)
    if admission.owner_verification is not None:
        return
    if admission.spoken_targets is not None:
        from stemwise_engine.spoken_targets import load
        root = Path(adjudication_job_dir) if adjudication_job_dir is not None else Path(evidence_root).parents[2]/'jobs'/Path(evidence_root).name
        plan = load(root, root.name, original, indices, admission.audio_sha256)
        original = {**original, '_spokenTargets': plan}
    try:
        require_exact_sequence(original, indices, recognized, admission.canonical_plain_sha256, admission.audio_sha256)
    except LyricsContentBlocker:
        expected_path = Path(evidence_root) / admission.evidence_path
        if (admission.adjudication is None or evidence_path is None
                or Path(evidence_path).resolve() != expected_path.resolve()):
            raise
        raw = read_bounded_regular_bytes(expected_path, max_bytes=MAX_JSON_BYTES, root=evidence_root)
        if hashlib.sha256(raw).hexdigest() != admission.evidence_sha256:
            raise ContentEvidenceUnavailable('content-evidence-changed', binding=admission.binding)
        retained = _recognized(parse_strict_json_bytes(raw), admission.audio_sha256)
        if _surfaces(_recognized_text(retained), original.get('language')) != _surfaces(
                _recognized_text(recognized), original.get('language')):
            raise LyricsContentBlocker('content-sequence-uncertain', binding=admission.binding)


def timing_observation_eligible(admission, original, indices, recognized, *, evidence_path=None,
                                evidence_root, adjudication_job_dir=None):
    """An outlier cannot veto source-backed content or lend it word timings."""
    try:
        require_admitted_sequence(admission, original, indices, recognized, evidence_path=evidence_path,
            evidence_root=evidence_root, adjudication_job_dir=adjudication_job_dir)
    except LyricsContentBlocker as error:
        if (error.reasons != ('content-sequence-uncertain',)
                or not is_source_recording_admission(admission, evidence_root)):
            raise
        return False
    return True


def confirm_content(original, indices: Sequence[int], canonical_sha: str, audio_sha: str,
                    *, evidence_path: Path, evidence_root: Path, adjudication_job_dir: Path | None = None) -> ContentAdmission:
    binding = _binding(indices, canonical_sha, audio_sha)
    if adjudication_job_dir is None:
        root = Path(evidence_root)
        if root.parent.name == 'jobs' and root.parent.parent.name == 'lyrics-alignment':
            adjudication_job_dir = root.parents[2] / 'jobs' / root.name
    try:
        from stemwise_engine.spoken_targets import load, attached, needed
        plan = (load(adjudication_job_dir, Path(adjudication_job_dir).name, original, indices, audio_sha)
                if adjudication_job_dir is not None else None)
        if plan is None:
            plan = attached(original)
        if plan is None and needed(original):
            raise LyricsContentBlocker('pronunciation-support-unavailable')
        if plan is not None:
            original = {**original, '_spokenTargets': plan}
        raw = read_bounded_regular_bytes(evidence_path, max_bytes=MAX_JSON_BYTES, root=evidence_root)
        cache = parse_strict_json_bytes(raw)
        if isinstance(cache, dict) and cache.get('decision') == 'owner-verified-complete-performed-lyrics':
            verified = (owner_verification(adjudication_job_dir, Path(adjudication_job_dir).name,
                original, indices, audio_sha) if adjudication_job_dir is not None else None)
            if verified is None or raw != verified[1]:
                raise LyricsContentBlocker('content-binding-differs', binding=binding)
            relative = evidence_path.resolve().relative_to(Path(evidence_root).resolve()).as_posix()
            digest = hashlib.sha256(raw).hexdigest()
            return ContentAdmission(canonical_sha, audio_sha, tuple(indices), relative, digest,
                spoken_targets=plan.reference if plan is not None else None,
                owner_verification={'path': OWNER_VERIFICATION_FILE, 'sha256': digest})
        from stemwise_engine import source_recording_content
        if isinstance(cache, dict) and cache.get('kind') == source_recording_content.KIND:
            if adjudication_job_dir is None:
                raise ContentEvidenceUnavailable('content-evidence-unavailable', binding=binding)
            source_recording_content.validate(cache, job_dir=adjudication_job_dir, evidence_root=evidence_root,
                video_id=Path(adjudication_job_dir).name, original=original, indices=indices,
                audio_sha=audio_sha, duration_ms=cache.get('durationMs'))
            relative = evidence_path.resolve().relative_to(Path(evidence_root).resolve()).as_posix()
            return ContentAdmission(canonical_sha, audio_sha, tuple(indices), relative, hashlib.sha256(raw).hexdigest(),
                spoken_targets=plan.reference if plan is not None else None)
        words = _recognized(cache, audio_sha)
        source = (source_recording_admission(adjudication_job_dir, evidence_root,
            Path(adjudication_job_dir).name, original, indices, canonical_sha, audio_sha)
            if adjudication_job_dir is not None else None)
        if source is not None:
            return source
        adjudication = None
        try:
            require_exact_sequence(original, indices, words, canonical_sha, audio_sha)
        except LyricsContentBlocker:
            review_path = Path(adjudication_job_dir) / ADJUDICATION_FILE if adjudication_job_dir is not None else None
            if review_path is None or not (review_path.exists() or review_path.is_symlink()):
                raise
            try:
                adjudication = _require_matching_adjudication(original, indices, words, audio_sha,
                    cache=cache, evidence_path=evidence_path, evidence_sha=hashlib.sha256(raw).hexdigest(),
                    evidence_root=evidence_root, job_dir=adjudication_job_dir)
            except (LyricsContentBlocker, ContentEvidenceUnavailable):
                raise
            except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
                # Broken review inputs cannot make valid contrary ASR eligible for regeneration.
                raise LyricsContentBlocker('recording-adjudication-invalid', binding=binding) from error
        relative = evidence_path.resolve().relative_to(evidence_root.resolve()).as_posix()
        return ContentAdmission(canonical_sha, audio_sha, tuple(indices), relative, hashlib.sha256(raw).hexdigest(), adjudication,
                                plan.reference if plan is not None else None)
    except LyricsContentBlocker:
        raise
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        raise ContentEvidenceUnavailable(binding=binding) from error


def plain_content_excerpt(original, indices, canonical_sha, audio_sha, duration_ms, *,
                          evidence_path, evidence_root):
    """Keep only whole lines with unique exact owners on every optimal edit path.

    This is untimed excerpt authority, never whole-recording occurrence or timing admission.
    """
    from stemwise_engine.lyrics_tokenization import optimal_edit_owners
    binding = _binding(indices, canonical_sha, audio_sha)
    raw = read_bounded_regular_bytes(evidence_path, max_bytes=MAX_JSON_BYTES, root=evidence_root)
    cache = parse_strict_json_bytes(raw)
    recognized = _recognized(cache, audio_sha)
    provenance = cache['provenance']
    if provenance.get('durationMs', provenance.get('audio', {}).get('stream', {}).get('durationMs')) != duration_ms:
        raise ContentEvidenceUnavailable('content-evidence-invalid', binding=binding)
    physical = physical_lines(original['text'])
    expected, owners = [], []
    for source in indices:
        words = _surfaces(physical[source], original.get('language'))
        expected.extend(words)
        owners.extend([source] * len(words))
    actual = _surfaces(_recognized_text(recognized), original.get('language'))
    graph = optimal_edit_owners(expected, actual)
    withheld = {owners[index] for index in graph['affectedExpected']}
    withheld.update(owners[index] for index, matches in enumerate(graph['matches']) if len(matches) != 1)
    for source in indices:
        try:
            require_pronunciation_support(physical[source])
        except LyricsContentBlocker:
            withheld.add(source)
    supported = [source for source in indices if source not in withheld and _surfaces(physical[source], original.get('language'))]
    if not supported:
        return None
    reference = {'path': Path(evidence_path).resolve().relative_to(Path(evidence_root).resolve()).as_posix(),
                 'sha256': hashlib.sha256(raw).hexdigest()}
    return '\n'.join(physical[source] for source in supported), {
        'version': 1, **binding, 'sourcePhysicalIndices': supported, 'evidence': reference}


def require_content(record: object, original, indices: Sequence[int], canonical_sha: str,
                    audio_sha: str, evidence_root: Path, *, adjudication_job_dir: Path | None = None) -> ContentAdmission:
    binding = _binding(indices, canonical_sha, audio_sha)
    validated = validate_content_admission_record(record)
    if any(validated[key] != value for key, value in binding.items()):
        raise LyricsContentBlocker("content-binding-differs", binding=binding)
    admission = confirm_content(original, indices, canonical_sha, audio_sha,
        evidence_path=Path(evidence_root) / validated["evidence"]["path"], evidence_root=Path(evidence_root),
        adjudication_job_dir=adjudication_job_dir)
    if admission.evidence_sha256 != validated["evidence"]["sha256"]:
        raise ContentEvidenceUnavailable("content-evidence-changed", binding=binding)
    if admission.adjudication != validated.get('adjudication'):
        raise LyricsContentBlocker('recording-adjudication-changed', binding=binding)
    if admission.spoken_targets != validated.get('spokenTargets'):
        raise LyricsContentBlocker('pronunciation-support-unavailable', binding=binding)
    if admission.owner_verification != validated.get('ownerVerification'):
        raise LyricsContentBlocker('content-binding-differs', binding=binding)
    return admission


_WEAK_TIMING_REASONS = frozenset({'low-forced-line-support',
    'missing-language-wav2vec2-boundary', 'missing-owsm-ctc-boundary',
    'missing-nemo-nfa-boundary', 'missing-unprompted-faster-whisper-boundary'})


def require_timing_only(reasons: Sequence[str], admission: ContentAdmission, *,
                        occurrences=None, expected_occurrences=None) -> None:
    if not isinstance(admission, ContentAdmission):
        raise LyricsContentBlocker("content-evidence-unavailable")
    if not reasons or any(reason not in _TIMING_REASONS | _WEAK_TIMING_REASONS for reason in reasons):
        raise LyricsContentBlocker("failure-not-confirmed-timing-only", binding=admission.binding)
    if any(reason in _WEAK_TIMING_REASONS for reason in reasons):
        if (not isinstance(occurrences, (list, tuple))
                or not isinstance(expected_occurrences, (list, tuple))
                or len(occurrences) != len(admission.source_physical_indices)
                or len(expected_occurrences) != len(occurrences)):
            raise LyricsContentBlocker('content-occurrence-uncertain', binding=admission.binding)
        for index, (actual, expected, source) in enumerate(zip(occurrences, expected_occurrences,
                                                             admission.source_physical_indices)):
            if (not isinstance(actual, dict) or not isinstance(expected, dict)
                    or actual.get('performedIndex') != index or expected.get('performedIndex') != index
                    or actual.get('sourcePhysicalIndex') != source or expected.get('sourcePhysicalIndex') != source
                    or not isinstance(expected.get('occurrenceId'), str) or not expected['occurrenceId']
                    or actual.get('occurrenceId') != expected['occurrenceId']):
                raise LyricsContentBlocker('content-occurrence-uncertain', binding=admission.binding)


def require_workspace_content(workspace, video_id, sources, *, original=None, audio_sha=None):
    from stemwise_engine.lyrics_alignment import _plain_original
    try:
        state = read_bounded_json(workspace.state_path(video_id), max_bytes=MAX_JSON_BYTES,
                                  root=workspace.job_dir(video_id))
        canonical_sha = state.get("canonicalPlainSha256")
        backup = workspace.current_backup_path(video_id)
        raw = read_bounded_regular_bytes(backup, max_bytes=MAX_JSON_BYTES, root=workspace.canonical_plain)
        if hashlib.sha256(raw).hexdigest() != canonical_sha:
            raise LyricsContentBlocker("content-canonical-differs")
        canonical_original = _plain_original(parse_strict_json_bytes(raw))
        if original is not None and {key: value for key, value in original.items() if key != '_spokenTargets'} != canonical_original:
            raise LyricsContentBlocker("content-canonical-differs")
        admission = require_content(state.get("contentAdmission"), canonical_original, tuple(sources),
            canonical_sha, audio_sha or state.get("contentBinding", {}).get("audioSha256"),
            workspace.job_dir(video_id), adjudication_job_dir=workspace.root.parent / 'jobs' / video_id)
        return admission
    except (LyricsContentBlocker, ContentEvidenceUnavailable):
        raise
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        raise ContentEvidenceUnavailable() from error
