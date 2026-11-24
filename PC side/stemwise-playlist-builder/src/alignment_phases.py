"""Immutable phase handoffs independent of model packages and CLI state."""
from __future__ import annotations

import copy
from dataclasses import dataclass
from typing import Any, Mapping, MutableMapping, Callable, Sequence, Protocol, TYPE_CHECKING, NamedTuple
from pathlib import Path
import re
from stemwise_engine.ctc_worker import source_duration_ms


class AttemptCacheBindings:
    """Records only cache artifacts returned by this attempt's explicit loader calls."""

    def __init__(self, state, root):
        self.state, self.root = state, Path(root)
        state['cacheBindings'] = {'version': 1, 'records': []}

    def bind(self, family, path, result):
        from stemwise_engine.common import read_bounded_regular_bytes
        from stemwise_engine.contract import MAX_JSON_BYTES
        import hashlib
        path = Path(path)
        # A model/test boundary can return in-memory evidence without publishing a cache.
        if not path.exists():
            return result
        raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=self.root)
        record = {'cacheId': path.relative_to(self.root).as_posix(),
                  'sha256': hashlib.sha256(raw).hexdigest(), 'familyId': family,
                  'use': 'consumed' if result[1 if family == 'wav2vec2' else -1] is True else 'produced'}
        records = self.state['cacheBindings']['records']
        records[:] = [row for row in records if row['cacheId'] != record['cacheId']]
        records.append(record)
        return result

    @staticmethod
    def records(state):
        value = state.get('cacheBindings', {'version': 1, 'records': []})
        if (not isinstance(value, dict) or set(value) != {'version', 'records'}
                or type(value['version']) is not int or value['version'] != 1
                or not isinstance(value['records'], list) or len(value['records']) > 512):
            raise ValueError('attempt cache bindings are malformed')
        seen = set()
        for row in value['records']:
            if (not isinstance(row, dict) or set(row) != {'cacheId', 'sha256', 'familyId', 'use'}
                    or not isinstance(row['cacheId'], str)
                    or re.fullmatch(r'[a-zA-Z0-9_.-]{1,200}\.private\.json', row['cacheId']) is None
                    or row['cacheId'] in seen
                    or not isinstance(row['sha256'], str)
                    or re.fullmatch('[0-9a-f]{64}', row['sha256']) is None
                    or row['familyId'] not in {'whisper-asr', 'mms-asr', 'qwen3-asr',
                        'caption', 'mms-fa', 'wav2vec2', 'owsm-ctc', 'nemo-nfa'}
                    or row['use'] not in {'consumed', 'produced'}):
                raise ValueError('attempt cache binding is invalid')
            seen.add(row['cacheId'])
        return value['records']


def respects_reviewed_start(occurrence, candidate, tolerance_ms):
    reviewed = occurrence.get('reviewedLrcStartMs')
    return reviewed is None or abs(candidate['startMs']-reviewed) <= tolerance_ms


@dataclass(frozen=True)
class EndpointSelectionResult:
    timings: tuple[Mapping[str, Any], ...]
    summary: Mapping[str, Any]
    reused: bool
    attempt_index: int

    @classmethod
    def bind(cls, timings, summary, reused, attempt_index, *, duration_ms, reviewed_tolerance_ms):
        occurrences = summary.get('occurrenceCandidates')
        if not isinstance(occurrences, list) or len(timings) != len(occurrences):
            raise ValueError('endpoint publication has incomplete occurrences')
        previous_end = 0
        for timing, occurrence in zip(timings, occurrences):
            if occurrence['publishDecision']['result'] == 'unresolved':
                continue
            if (not 0 <= timing['startMs'] < timing['endMs'] <= duration_ms
                    or timing['startMs'] < previous_end
                    or not respects_reviewed_start(occurrence, timing, reviewed_tolerance_ms)):
                raise ValueError('endpoint publication violates reviewed timing or timeline')
            previous_end = timing['endMs']
        return cls(tuple(copy.deepcopy(timings)), copy.deepcopy(summary), reused, attempt_index)


@dataclass(frozen=True)
class AudioIdentity:
    sha256: str
    byte_count: int
    sample_rate: int
    channels: int
    frames: int

    def __post_init__(self):
        if (not isinstance(self.sha256, str) or re.fullmatch('[0-9a-f]{64}', self.sha256) is None
                or any(type(value) is not int or value <= 0 for value in
                    (self.byte_count, self.sample_rate, self.channels, self.frames))):
            raise ValueError('Measured alignment audio identity is invalid')

    def to_record(self):
        return {'sha256': self.sha256, 'byteCount': self.byte_count, 'stream': {
            'sampleRate': self.sample_rate, 'channelCount': self.channels, 'frameCount': self.frames,
            'durationMs': source_duration_ms(self.frames, self.sample_rate)}}


@dataclass(frozen=True)
class SegmentPlan:
    segments: tuple[tuple[tuple[str, int], ...], ...]

    @classmethod
    def from_segments(cls, segments):
        if not isinstance(segments, (list, tuple)) or not segments:
            raise ValueError('Segment plan must be a complete nonempty sequence')
        previous_line, previous_window = 0, 0
        fields = {'segmentIndex', 'startLineIndex', 'endLineIndex', 'windowStartMs', 'windowEndMs'}
        for index, segment in enumerate(segments):
            if (not isinstance(segment, dict) or set(segment) != fields
                    or any(type(value) is not int for value in segment.values())
                    or segment['segmentIndex'] != index or segment['startLineIndex'] != previous_line
                    or segment['endLineIndex'] <= previous_line
                    or segment['windowStartMs'] < previous_window
                    or segment['windowEndMs'] <= segment['windowStartMs']):
                raise ValueError('Segment plan has a gap, overlap or malformed boundary')
            previous_line, previous_window = segment['endLineIndex'], segment['windowEndMs']
        return cls(tuple(tuple(sorted(segment.items())) for segment in segments))


@dataclass(frozen=True)
class PairedEndpointResult:
    plan: SegmentPlan
    forced: Mapping[str, Any]
    verifier: tuple[Mapping[str, Any] | None, ...]

    @classmethod
    def bind(cls, forced, verifier, segments):
        if (not isinstance(forced, dict) or not isinstance(forced.get('lines'), (list, tuple))
                or not isinstance(verifier, (list, tuple)) or len(forced['lines']) != len(verifier)):
            raise ValueError('paired endpoint results are incomplete')
        plan = SegmentPlan.from_segments(segments)
        if plan != SegmentPlan.from_segments(forced.get('resolvedSegments', segments)):
            raise ValueError('paired endpoint results use different segment plans')
        return cls(plan, copy.deepcopy(forced), tuple(copy.deepcopy(verifier)))

    def require_plan(self, segments):
        if self.plan != SegmentPlan.from_segments(segments):
            raise ValueError('endpoint segment plan changed after paired verification')
        return self.forced, self.verifier


def ordered_endpoint_candidate_path(
    options: list[list[tuple[dict[str, Any], str | None]]],
) -> list[tuple[dict[str, Any], str | None]] | None:
    """Prefer publisher priority among complete non-overlapping candidate paths."""
    states: list[tuple[tuple[int, ...], list[tuple[dict[str, Any], str | None]]]] = [((), [])]
    for choices in options:
        next_states = []
        for priority, (timing, publisher) in enumerate(choices):
            compatible = [state for state in states
                          if not state[1] or state[1][-1][0]["endMs"] <= timing["startMs"]]
            if compatible:
                rank, path = min(compatible, key=lambda state: state[0])
                next_states.append((rank + ((priority,) if publisher is not None else ()),
                                    path + [(timing, publisher)]))
        if not next_states:
            return None
        states = next_states
    return min(states, key=lambda state: state[0])[1]


if TYPE_CHECKING:
    from stemwise_engine.lyrics_alignment import AlignmentResult, AlignmentWorkspace

Record = dict[str, Any]
EndpointCandidates = tuple[Record | None, ...]
EndpointTimings = tuple[list[Record], Record]


@dataclass(frozen=True, kw_only=True)
class AlignmentContext:
    config: Record
    video_id: str
    workspace: AlignmentWorkspace
    temp_root: Path
    device: str
    duration_ms: int
    endpoint_duration_ms: int

    def __post_init__(self):
        object.__setattr__(self, 'config', copy.deepcopy(self.config))


@dataclass(frozen=True, kw_only=True)
class EndpointText:
    original: Record
    alignment: AlignmentResult
    lexical_mapping: Record
    sources: Sequence[int]
    source_occurrences: tuple[int, ...]
    display_word_lines: list[list[tuple[int, int, str]]]
    acoustic_language_lines: list[list[str]] | None

    def __post_init__(self):
        for name in ('original', 'lexical_mapping', 'display_word_lines', 'acoustic_language_lines'):
            object.__setattr__(self, name, copy.deepcopy(getattr(self, name)))
        object.__setattr__(self, 'sources', tuple(self.sources))


@dataclass(frozen=True, kw_only=True)
class EndpointEvidence:
    baseline_segments: list[dict[str, int]]
    seeded_mms_segments: list[dict[str, int]] | None
    verifier_candidates: EndpointCandidates
    verifier_family: str
    verifier_reused: bool
    wav2vec2_language: str | None
    free_family_candidates: Mapping[str, EndpointCandidates]
    independent_inputs: Mapping[str, tuple[Sequence[int], Record, list[list[tuple[int, int, str]]]]]
    reviewed_lrc_starts: Mapping[int, int]
    route_names: Mapping[str, str]
    content_admission: Record | None = None

    def __post_init__(self):
        for name in ('baseline_segments', 'seeded_mms_segments', 'verifier_candidates',
                     'free_family_candidates', 'independent_inputs', 'reviewed_lrc_starts',
                     'route_names', 'content_admission'):
            object.__setattr__(self, name, copy.deepcopy(getattr(self, name)))


@dataclass(frozen=True, kw_only=True)
class EndpointAudio:
    expected_mix_sha256: str | None
    analysis_audio: Path | None
    canonical_mix: Callable[[], tuple[Path, Record]]
    get_endpoint_audio: Callable[[str], tuple[Path, Record, Record]]
    mix_audio: Path | None = None
    mix_identity: Record | None = None
    opening_stem_power: Callable | None = None


class RouteAttemptSink(Protocol):
    def __call__(self, stage: str, route_id: str, status: str, *,
                 cache_reused: bool | None = None, code: str | None = None) -> None: ...


@dataclass(frozen=True, kw_only=True)
class AlignmentEvents:
    attempt: RouteAttemptSink
    failure: Callable[[str, str, str], None]
    roformer: Callable[..., None]


@dataclass(kw_only=True)
class AlignmentProgress:
    state: MutableMapping[str, Any]
    endpoint_attempts: list[Record]
    checkpoint: Callable[[], None]


@dataclass(frozen=True, kw_only=True)
class EndpointWorkers:
    load_mms: Callable[..., tuple[Record, bool]]
    load_wav2vec2: Callable[..., tuple[Record, bool, Record]]
    load_owsm: Callable[..., tuple[EndpointCandidates, bool]]
    load_nfa: Callable[..., tuple[EndpointCandidates, bool]]
    read_audio_provenance: Callable[..., Record | None]
    verifier_candidates: Callable[..., EndpointCandidates]
    needs_local_verifier: Callable[..., bool]


@dataclass(frozen=True, kw_only=True)
class EndpointServices:
    workers: EndpointWorkers
    events: AlignmentEvents
    progress: AlignmentProgress
    release_model: Callable[[], None]
    runtime_probe_cache: MutableMapping[tuple[Any, ...], Record]


@dataclass(frozen=True, kw_only=True)
class EndpointAlignmentRequest:
    context: AlignmentContext
    text: EndpointText
    evidence: EndpointEvidence
    audio: EndpointAudio


@dataclass(frozen=True, kw_only=True)
class StructureSelectionRequest:
    video_id: str
    original: Record
    duration_ms: int
    repeat_structure_kind: str
    reviewed_baseline_structure: bool
    performed_structure_review_sha256: str | None
    evidence_root: Path | None = None


class StructureSelectionResult(NamedTuple):
    label: str
    alignment: AlignmentResult
    evidence: Record
    reused: bool


@dataclass(frozen=True, kw_only=True)
class AlignmentPublicationRequest:
    workspace: AlignmentWorkspace
    video_id: str
    sidecar_path: Path
    original_raw: bytes
    expected_current_raw: bytes
    candidate: Record | None
    alignment: AlignmentResult
    lexical_mapping: Record | None
    public_line_timings: list[Record] | None
    summary: Record
    endpoint_selection: EndpointSelectionResult | None
    no_commit: bool


@dataclass(frozen=True)
class AlignmentPublicationResult:
    status: str
    counter: str
