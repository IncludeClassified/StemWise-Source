"""Immutable producing inputs, separate from mutable alignment and editorial state."""
from __future__ import annotations
from dataclasses import dataclass
import copy
from pathlib import Path
import re
import os
import time
from contextlib import contextmanager
from typing import TYPE_CHECKING, Any, Sequence, Callable

if TYPE_CHECKING:
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace, AlignmentResult

from stemwise_engine.common import sha256_file


class SongAttemptBlocked(ValueError):
    pass


class SongAttemptLedger:
    """Account for authorized work and preserve exact acceptance across retries."""
    MAX_BYTES = 1024 * 1024
    def __init__(self, path, material_inputs=None, *, clock=time.time):
        from stemwise_engine.common import read_bounded_json, json_bytes
        import hashlib
        self.path, self.clock = Path(path), clock
        if self.path.exists():
            self.record = read_bounded_json(self.path, max_bytes=self.MAX_BYTES, root=self.path.parent)
            if (self.record.get('version') != 1 or not isinstance(self.record.get('actions'), dict)
                    or not isinstance(self.record.get('activeSeconds'), (int, float))):
                raise ValueError('Song attempt ledger is invalid')
        else:
            if material_inputs is None:
                raise ValueError('Song attempt material inputs are unavailable')
            self.record = {'version': 1, 'generation': 1, 'generations': [], 'startedAt': self.clock(),
                'materialSha256': hashlib.sha256(json_bytes(material_inputs)).hexdigest(),
                'activeSeconds': 0., 'recoverySeconds': 0., 'waitSeconds': 0.,
                'activeSince': None, 'recoverySince': None, 'actions': {}}
            self.save()

    def save(self):
        from stemwise_engine.common import atomic_write_bytes, json_bytes
        history = self.record.setdefault('generations', [])
        del history[:-8]
        self.record['lastObservedAt'] = self.clock()
        raw = json_bytes(self.record)
        while len(raw) > self.MAX_BYTES and history:
            del history[0]
            raw = json_bytes(self.record)
        if len(raw) > self.MAX_BYTES:
            raise ValueError('Current song attempt ledger exceeds its readable byte limit')
        atomic_write_bytes(self.path, raw)

    def refresh(self):
        from stemwise_engine.common import read_bounded_json
        self.record = read_bounded_json(self.path, max_bytes=self.MAX_BYTES, root=self.path.parent)

    def elapsed(self, kind='active', *, at=None):
        now = self.clock() if at is None else at
        started = self.record.get(kind+'Since')
        waiting = self.record.get('waitSince')
        excluded = max(0., now-waiting) if waiting is not None else 0.
        return self.record[kind+'Seconds'] + (max(0., now-started-excluded) if started is not None else 0.)

    @property
    def blocking_disposition(self):
        return self.record.get('disposition')

    def restart(self, material_inputs):
        """Called only with the workflow and worker leases after prior workers retire."""
        import hashlib
        from stemwise_engine.common import json_bytes
        self.refresh()
        identity = hashlib.sha256(json_bytes(material_inputs)).hexdigest()
        interrupted = self.record.get('activeSince') is not None or any(
            row['status'] == 'unknown' for row in (
                *self.record['actions'].values(), *self.record.get('primaryActions', {}).values()))
        failed = any(row['status'] == 'failed' for row in self.record['actions'].values())
        if (identity == self.record['materialSha256'] and not interrupted
                and not self.blocking_disposition and not failed):
            return
        previous = {key: self.record.get(key) for key in
            ('generation', 'materialSha256', 'primaryActions', 'disposition')}
        previous['actionCounts'] = {status: sum(row['status'] == status for row in self.record['actions'].values())
                                    for status in ('reserved', 'unknown', 'complete', 'failed')}
        previous['generation'] = previous['generation'] or 1
        previous['retiredAt'] = self.clock()
        previous['status'] = 'abandoned' if interrupted else 'complete'
        self.record.setdefault('generations', []).append(previous)
        since = self.record.get('activeSince')
        last = self.record.get('lastObservedAt', since)
        if since is not None:
            last = min(previous['retiredAt'], max(since, last))
            self.record['unobservedSeconds'] = (self.record.get('unobservedSeconds', 0.)
                                                + max(0., previous['retiredAt'] - last))
            if self.record.get('waitSince') is not None:
                self.record['waitSeconds'] += max(0., last - self.record['waitSince'])
        self.record.update(generation=previous['generation']+1, materialSha256=identity,
            activeSeconds=self.elapsed(at=last), recoverySeconds=self.elapsed('recovery', at=last),
            activeSince=None, recoverySince=None, waitSince=None, primaryActions={}, actions={})
        self.record.pop('disposition', None)
        self.save()

    def check_allowed(self, *, finalization=False):
        self.refresh()
        if self.blocking_disposition and not finalization:
            raise SongAttemptBlocked(self.blocking_disposition['code'])

    def stop(self, code, missing_fact, *, claims=()):
        self.refresh()
        if self.blocking_disposition is not None:
            return
        self.record['disposition'] = {'code': code, 'missingFact': missing_fact,
            'claims': list(claims), 'materialSha256': self.record['materialSha256'],
            'activeSeconds': self.elapsed(), 'recoverySeconds': self.elapsed('recovery'),
            'spentActions': sorted(key for key, action in self.record['actions'].items()
                                   if action['status'] != 'reserved')}
        self.save()

    @contextmanager
    def active(self, *, finalization=False):
        self.check_allowed(finalization=finalization)
        owner = self.record['activeSince'] is None
        if owner:
            self.record['activeSince'] = self.clock()
            self.save()
        previous = os.environ.get('STEMWISE_SONG_LEDGER')
        os.environ['STEMWISE_SONG_LEDGER'] = str(self.path)
        try:
            yield self
        finally:
            import sys
            pending = sys.exc_info()[1]
            if previous is None:
                os.environ.pop('STEMWISE_SONG_LEDGER', None)
            else:
                os.environ['STEMWISE_SONG_LEDGER'] = previous
            if owner:
                try:
                    self.refresh()
                    self.record['activeSeconds'] = self.elapsed()
                    self.record['recoverySeconds'] = self.elapsed('recovery')
                    self.record['activeSince'] = self.record['recoverySince'] = None
                    self.save()
                except BaseException as error:
                    from stemwise_engine.common import raise_with_cleanup_failure
                    raise_with_cleanup_failure(pending, error)

    @contextmanager
    def lease_wait(self):
        self.refresh()
        started = self.clock()
        self.record['waitSince'] = started
        self.save()
        try:
            yield
        finally:
            self.refresh()
            elapsed = max(0., self.clock()-started)
            self.record['waitSeconds'] += elapsed
            # The outer active owner excludes this recorded wait exactly once.
            if self.record['activeSince'] is not None:
                self.record['activeSince'] += elapsed
            if self.record['recoverySince'] is not None:
                self.record['recoverySince'] += elapsed
            self.record['waitSince'] = None
            self.save()

    def reserve(self, actions):
        self.check_allowed()
        self.refresh()
        selected = {action['requestId'] for action in actions}
        self.record['actions'] = {key: row for key, row in self.record['actions'].items()
            if row['status'] != 'reserved' or key in selected}
        for action in actions:
            self.record['actions'].setdefault(action['requestId'], {**action, 'status': 'reserved'})
        self.save()

    def begin(self, request_id):
        self.check_allowed()
        action = self.record['actions'][request_id]
        if action['status'] != 'reserved':
            raise SongAttemptBlocked('recovery-action-already-started')
        action['status'] = 'unknown'
        if self.record['recoverySince'] is None:
            self.record['recoverySince'] = self.clock()
        self.save()  # Acceptance is durable before the model boundary.

    def complete(self, request_id, *, result=None):
        self.refresh()
        self.record['actions'][request_id]['status'] = 'complete'
        if result is not None:
            self.record['actions'][request_id]['result'] = result
        if not any(row['status'] == 'unknown' for row in self.record['actions'].values()):
            self.record['recoverySeconds'] = self.elapsed('recovery')
            self.record['recoverySince'] = None
        self.save()

    def fail(self, request_id, error):
        """Settle only a local worker failure after its process ownership retired."""
        from subprocess import TimeoutExpired
        from stemwise_engine.worker_protocol import WorkerProtocolError
        if not isinstance(error, (WorkerProtocolError, TimeoutExpired)):
            raise ValueError('Recovery failure requires a retired local worker disposition')
        self.refresh()
        action = self.record['actions'][request_id]
        if action['status'] != 'unknown':
            raise SongAttemptBlocked('recovery-action-not-active')
        action.update(status='failed', failure={'exceptionType': type(error).__name__,
            'code': error.code if isinstance(error, WorkerProtocolError) else 'worker-stage-deadline'})
        if not any(row['status'] == 'unknown' for row in self.record['actions'].values()):
            self.record['recoverySeconds'] = self.elapsed('recovery')
            self.record['recoverySince'] = None
        self.save()

    def release_unstarted(self, request_ids):
        self.refresh()
        for identity in request_ids:
            if self.record['actions'].get(identity, {}).get('status') == 'reserved':
                del self.record['actions'][identity]
        self.save()

    @contextmanager
    def primary(self, stage, inputs):
        import hashlib
        from stemwise_engine.common import json_bytes
        self.check_allowed()
        identity = hashlib.sha256(json_bytes(inputs)).hexdigest()
        rows = self.record.setdefault('primaryActions', {})
        previous = rows.get(stage)
        if previous and (previous['requestSha256'] != identity or previous['status'] == 'unknown'):
            self.stop('technical-failure', 'primary-attempt-requires-explicit-new-input-policy')
            raise SongAttemptBlocked('technical-failure')
        rows[stage] = {'requestSha256': identity, 'status': 'unknown'}
        self.save()
        try:
            yield
        except BaseException:
            self.stop('technical-failure', 'primary-work-interrupted:'+stage)
            raise
        else:
            self.refresh()
            self.record['primaryActions'][stage]['status'] = 'complete'
            self.save()


def current_song_ledger():
    value = os.environ.get('STEMWISE_SONG_LEDGER')
    return SongAttemptLedger(Path(value)) if value else None


class RouteOutcomeLedger:
    """One ordered source for route attempts and outstanding technical failures."""
    def __init__(self):
        self.events = []

    def record(self, event):
        identity = {key: value for key, value in event.items() if key != 'sequence'}
        if self.events and identity == {key: value for key, value in self.events[-1].items() if key != 'sequence'}:
            return
        self.events.append(dict(event))

    @property
    def failures(self):
        result = []
        for event in self.events:
            if event['status'] == 'failure':
                failure = {key: event[key] for key in ('stage', 'routeId', 'code')}
                if failure not in result:
                    result.append(failure)
        return result


@dataclass(frozen=True)
class AttemptInputs:
    attempt_id: str
    editorial_sha256: str
    sources_sha256: str
    configuration_sha256: str
    helpers: tuple[tuple[str, str], ...] = ()

    def __post_init__(self):
        if (not isinstance(self.attempt_id, str) or re.fullmatch('[0-9a-f]{32}', self.attempt_id) is None
                or any(not isinstance(value, str) or re.fullmatch('[0-9a-f]{64}', value) is None for value in
                       (self.editorial_sha256, self.sources_sha256, self.configuration_sha256))
                or len({name for name, _ in self.helpers}) != len(self.helpers)
                or any(not isinstance(name, str) or Path(name).name != name
                       or not isinstance(value, str) or re.fullmatch('[0-9a-f]{64}', value) is None
                       for name, value in self.helpers)):
            raise ValueError('Alignment attempt input identity is invalid')

    def to_record(self):
        return {'attemptId': self.attempt_id, 'editorialSha256': self.editorial_sha256,
                'sourcesSha256': self.sources_sha256, 'configurationSha256': self.configuration_sha256,
                'helpers': dict(self.helpers)}

    @classmethod
    def from_record(cls, value):
        if (not isinstance(value, dict) or set(value) != {'attemptId', 'editorialSha256', 'sourcesSha256', 'configurationSha256', 'helpers'}
                or not isinstance(value['helpers'], dict)):
            raise ValueError('Alignment attempt input identity is unavailable')
        return cls(value['attemptId'], value['editorialSha256'], value['sourcesSha256'],
                   value['configurationSha256'], tuple(sorted(value['helpers'].items())))

    @classmethod
    def capture(cls, attempt_id, editorial_sha256, sources_path, configuration_path, source_root):
        from stemwise_engine.provenance import semantic_source_identity
        return cls(attempt_id, editorial_sha256, sha256_file(sources_path), sha256_file(configuration_path),
                   tuple((path.name, semantic_source_identity(path)) for path in sorted(source_root.iterdir())
                         if path.is_file() and path.suffix in {'.py', '.json'}))


@dataclass(frozen=True)
class ContentDispute:
    path: str
    sha256: str
    scope: dict

    @classmethod
    def from_record(cls, value, *, performed_count):
        if (not isinstance(value, dict) or set(value) != {'path', 'sha256', 'scope'}
                or not isinstance(value['path'], str) or not value['path'] or len(value['path']) > 240
                or value['path'] in {'.', '..'} or Path(value['path']).name != value['path']
                or not isinstance(value['sha256'], str) or re.fullmatch('[0-9a-f]{64}', value['sha256']) is None):
            raise ValueError('Content dispute evidence identity is invalid')
        scope = value['scope']
        if (not isinstance(scope, dict) or set(scope) != {'version', 'complete', 'possiblePerformedIndices',
                'targetWordIndices', 'recognizedWordIndices', 'optimalEditCost'}
                or type(scope['version']) is not int or scope['version'] != 1
                or type(scope['complete']) is not bool
                or any(not isinstance(scope[key], list) or any(type(index) is not int or index < 0
                    for index in scope[key]) or scope[key] != sorted(set(scope[key]))
                    for key in ('possiblePerformedIndices', 'targetWordIndices', 'recognizedWordIndices'))
                or any(index >= performed_count for index in scope['possiblePerformedIndices'])
                or (scope['optimalEditCost'] is not None and
                    (type(scope['optimalEditCost']) is not int or scope['optimalEditCost'] < 0))
                or scope['complete'] != (scope['optimalEditCost'] is not None)):
            raise ValueError('Content dispute discrepancy scope is invalid')
        return cls(value['path'], value['sha256'], copy.deepcopy(scope))

    @property
    def evidence(self):
        return {'path': self.path, 'sha256': self.sha256}

    def to_record(self):
        return {**self.evidence, 'scope': copy.deepcopy(self.scope)}


@dataclass(frozen=True)
class RecognitionAdmissionRequest:
    video_id: str
    original_json: bytes
    sources: tuple[int, ...]
    canonical_sha256: str
    audio_sha256: str
    spoken_plan: Any = None


def _reviewed_timing_cache(path, root, audio_sha):
    from stemwise_engine.common import read_bounded_json
    from stemwise_engine.contract import MAX_JSON_BYTES
    from stemwise_engine.lyric_content import _recognized
    from stemwise_engine.workflow_records import ContentEvidenceUnavailable
    try:
        cache = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=root)
        return _recognized(cache, audio_sha, candidate_only=True)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        raise ContentEvidenceUnavailable('content-evidence-invalid') from error


def _has_word_times(words):
    import math
    if not words or isinstance(words, str):
        return False
    previous_end = 0
    for word in words:
        start, end = (word.get('start'), word.get('end')) if isinstance(word, dict) else (word.start, word.end)
        if (type(start) not in (int, float) or type(end) not in (int, float)
                or not previous_end <= start < end or not math.isfinite(end)):
            return False
        previous_end = end
    return True


def timing_witness_eligible(admission, original, sources, transcript, path, root, *, check_candidate=False):
    """The caller has replayed the content admission. Test each timing witness separately."""
    from stemwise_engine import lyric_content
    if check_candidate and admission.adjudication is None and path is not None:
        admission = lyric_content.confirm_content(original, sources, admission.canonical_plain_sha256,
            admission.audio_sha256, evidence_path=path, evidence_root=root)
    if admission.adjudication is None:
        return lyric_content.timing_observation_eligible(admission, original, sources, transcript,
            evidence_path=path, evidence_root=root)
    if not _has_word_times(transcript):
        return False
    if admission.spoken_targets is not None:
        from stemwise_engine import spoken_targets
        plan = spoken_targets.load(root.parents[2]/'jobs'/root.name, root.name, original, sources, admission.audio_sha256)
        if plan is None or plan.reference != admission.spoken_targets:
            raise lyric_content.LyricsContentBlocker('pronunciation-support-unavailable')
        original = {**original, '_spokenTargets': plan}
    observations = [transcript]
    if path is not None:
        words = _reviewed_timing_cache(path, root, admission.audio_sha256)
        if not _has_word_times(words):
            return False
        observations.append(words)
    for observation in observations:
        try:
            lyric_content.require_exact_sequence(original, sources, observation, admission.canonical_plain_sha256,
                admission.audio_sha256)
        except lyric_content.LyricsContentBlocker as error:
            if error.reasons != ('content-sequence-uncertain',):
                raise
            return False
    return True


@dataclass
class RecognitionAdmission:
    request: RecognitionAdmissionRequest
    workspace: 'AlignmentWorkspace'
    state: dict

    def _excluded_observation(self, original, transcript):
        from stemwise_engine.lyrics_alignment import AlignmentResult, AlignmentMetrics
        from stemwise_engine.lyrics_tokenization import display_words
        from stemwise_engine.lyric_content import _recognized_text
        lyric_words = sum(len(display_words(line)) for line in original['text'].splitlines())
        transcript_words = len(display_words(_recognized_text(transcript)))
        return AlignmentResult(False, ('recognition-wording-mismatch',), (), self.request.sources,
            AlignmentMetrics(lyric_words, transcript_words, 0, 0, len(self.request.sources), 0., 0., 0., 0., 0.))

    def admit(self, path):
        import hashlib
        import json
        from stemwise_engine.common import read_bounded_regular_bytes
        from stemwise_engine.contract import MAX_JSON_BYTES
        from stemwise_engine import lyric_content
        from stemwise_engine.workflow_records import ContentEvidenceUnavailable, LyricsContentBlocker
        request, workspace = self.request, self.workspace
        try:
            original = json.loads(request.original_json)
            folder = workspace.job_dir(request.video_id)
            job_dir = workspace.root.parent/'jobs'/request.video_id
            admission = None
            review_path = job_dir/lyric_content.ADJUDICATION_FILE
            if review_path.exists() or review_path.is_symlink():
                try:
                    admission = lyric_content.reviewed_workspace_admission(workspace, request.video_id,
                        original, request.sources, request.canonical_sha256, request.audio_sha256)
                except ContentEvidenceUnavailable as error:
                    # Regenerating a different recognizer cannot restore a missing reviewed primary.
                    raise LyricsContentBlocker('recording-adjudication-stale') from error
            if admission is not None and admission.adjudication is not None:
                if path.resolve() != (folder/admission.evidence_path).resolve():
                    _reviewed_timing_cache(path, folder, request.audio_sha256)
            else:
                admission = lyric_content.confirm_content(original, request.sources, request.canonical_sha256,
                    request.audio_sha256, evidence_path=path, evidence_root=folder, adjudication_job_dir=job_dir)
        except LyricsContentBlocker as error:
            if error.reasons == ('content-sequence-uncertain',):
                raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES,
                    root=workspace.job_dir(request.video_id))
                dispute = {
                    'path': path.relative_to(workspace.job_dir(request.video_id)).as_posix(),
                    'sha256': hashlib.sha256(raw).hexdigest(),
                    'scope': getattr(error, 'discrepancy_scope', None)}
                self.state['contentDispute'] = ContentDispute.from_record(dispute,
                    performed_count=len(request.sources)).to_record()
                workspace.write_state(request.video_id, self.state)
            raise
        self.state.pop('contentDispute', None)
        self.state.update(contentAdmission=admission.to_record(), contentBinding=admission.binding)
        workspace.write_state(request.video_id, self.state)
        return admission

    def align(self, transcript: Sequence[Any], duration_ms: int, align_known_structure: Callable[..., AlignmentResult],
              *, evidence_path: Path | None = None) -> AlignmentResult:
        import json
        from stemwise_engine import lyric_content
        request, workspace = self.request, self.workspace
        original = json.loads(request.original_json)
        if request.spoken_plan is not None:
            original['_spokenTargets'] = request.spoken_plan
        admission = lyric_content.require_workspace_content(workspace, request.video_id, request.sources,
            original=original, audio_sha=request.audio_sha256)
        if not timing_witness_eligible(admission, original, request.sources, transcript,
                evidence_path, workspace.job_dir(request.video_id)):
            return self._excluded_observation(original, transcript)
        result = align_known_structure(request.spoken_plan.matching_text() if request.spoken_plan else original['text'],
                                       request.sources, transcript, duration_ms)
        if not result.accepted:
            lyric_content.require_timing_only(result.reasons, admission)
        return result

    def align_many(self, transcripts: Sequence[Sequence[Any]], evidence_paths: Sequence[Path | None],
                   duration_ms: int, align_known_structure_multi: Callable[..., AlignmentResult]) -> AlignmentResult:
        import json
        from stemwise_engine import lyric_content
        if len(transcripts) != len(evidence_paths):
            raise ValueError('Every merged transcript requires an explicit evidence association')
        request, workspace = self.request, self.workspace
        original = json.loads(request.original_json)
        if request.spoken_plan is not None:
            original['_spokenTargets'] = request.spoken_plan
        current = lyric_content.require_workspace_content(workspace, request.video_id, request.sources,
            original=original, audio_sha=request.audio_sha256)
        eligible = []
        for transcript, path in zip(transcripts, evidence_paths):
            if not timing_witness_eligible(current, original, request.sources, transcript, path,
                    workspace.job_dir(request.video_id), check_candidate=True):
                continue
            eligible.append(transcript)
        admission = lyric_content.require_workspace_content(workspace, request.video_id, request.sources,
            original=original, audio_sha=request.audio_sha256)
        if not eligible:
            return self._excluded_observation(original, ())
        result = align_known_structure_multi(request.spoken_plan.matching_text() if request.spoken_plan else original['text'],
                                             request.sources, eligible, duration_ms)
        if not result.accepted:
            lyric_content.require_timing_only(result.reasons, admission)
        return result

    def source_line_anchors(self, path, duration_ms):
        """Keep exact observed line hints separate from every word-endpoint witness."""
        import hashlib
        import json
        import math
        from stemwise_engine import lyric_content
        from stemwise_engine.common import read_bounded_regular_bytes, parse_strict_json_bytes
        from stemwise_engine.contract import MAX_JSON_BYTES
        from stemwise_engine.lyrics_alignment import AlignmentResult, AlignmentMetrics, QualityGate, _quality_reasons
        from stemwise_engine.lyric_comparison import units
        if path is None or type(duration_ms) is not int or duration_ms <= 0:
            return None
        request, workspace = self.request, self.workspace
        original = json.loads(request.original_json)
        if request.spoken_plan is not None:
            original['_spokenTargets'] = request.spoken_plan
        admission = lyric_content.require_workspace_content(workspace, request.video_id, request.sources,
            original=original, audio_sha=request.audio_sha256)
        root = workspace.job_dir(request.video_id)
        if not (admission.adjudication or lyric_content.is_source_recording_admission(admission, root)):
            return None
        raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=root)
        words = lyric_content._recognized(parse_strict_json_bytes(raw), request.audio_sha256, candidate_only=True)
        if not isinstance(words, list):
            return None
        untimed = {index for index, word in enumerate(words) if word['start'] == word['end']}
        if not _has_word_times([word for index, word in enumerate(words) if index not in untimed]):
            return None
        anchors = lyric_content.exact_line_anchors(original, request.sources, words, untimed_indices=untimed)
        if anchors is None:
            return None
        starts = []
        for anchor in anchors:
            word = words[anchor['rawWordIndex']]
            if (any(type(value) not in (int, float) or not math.isfinite(value) for value in (word['start'], word['end']))
                    or not 0 <= word['start'] < word['end'] <= duration_ms/1000):
                return None
            starts.append(round(word['start']*1000))
        if any(right <= left for left, right in zip(starts, starts[1:])):
            return None
        expected = sum(len(lyric_content._line_units(original, source)) for source in request.sources)
        actual = len(units(lyric_content._recognized_text(words), original.get('language')))
        matched = sum(anchor['matchedUnitCount'] for anchor in anchors)
        metrics = AlignmentMetrics(expected, actual, matched, len(anchors), len(request.sources),
            matched/expected, matched/actual, 1., 100., (starts[-1]-starts[0])/duration_ms)
        reasons = _quality_reasons(metrics, starts_ms=starts, line_count=len(request.sources), gate=QualityGate())
        if reasons:
            return None
        result = AlignmentResult(True, (), tuple(starts), request.sources, metrics)
        evidence = {'method': 'source-bound-unique-exact-line-anchors-v1',
            'recognition': {'path': Path(path).resolve().relative_to(root.resolve()).as_posix(),
                'sha256': hashlib.sha256(raw).hexdigest()}, 'anchors': list(anchors),
            'contentAdmission': admission.to_record()}
        return result, evidence

    def cached(self, path):
        from stemwise_engine.workflow_records import ContentEvidenceUnavailable
        if not path.is_file():
            return False
        try:
            self.admit(path)
        except ContentEvidenceUnavailable:
            return False
        return True


@dataclass(frozen=True)
class RecognitionEvidence:
    label: str
    transcript: tuple
    reused: bool
    path: Path | None = None


class RecognitionEvidenceSet:
    def __init__(self):
        self.records: list[RecognitionEvidence] = []

    def add(self, label, transcript, reused, path=None):
        self.records.append(RecognitionEvidence(label, tuple(transcript), reused, path))

    @property
    def transcripts(self):
        return [list(record.transcript) for record in self.records]

    @property
    def labels(self):
        return [record.label for record in self.records]

    @property
    def reused(self):
        return [record.reused for record in self.records]

    def path_for(self, label):
        return next((record.path for record in reversed(self.records) if record.label == label), None)
