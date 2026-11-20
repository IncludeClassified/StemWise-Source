"""Current workflow outcomes shared by report producers and boundary validators."""
from enum import Enum
from stemwise_engine.workflow_states import JobState


DISPLAY_REVIEW_VERSION = 1
DISPLAY_REVIEW_POLICY = "stemwise.display-only.v1"
ENDPOINT_REVIEW_VERSION = 1
ENDPOINT_REVIEW_POLICY = "stemwise.endpoint-candidates.v1"

class WorkflowOutcome(str, Enum):
    SUCCESS = 'success'
    INTERRUPTED = 'keyboard-interrupt'
    WORKFLOW_ERROR = 'future-song-error'
    DRAFT_UNAVAILABLE = 'recording-evidence-draft-unavailable'
    UNEXPECTED_ERROR = 'unexpected-error'
    AUTHORITY_UNRESOLVED = 'authority-unresolved'
    LYRICS_CONTENT_UNRESOLVED = 'lyrics-content-unresolved'
    LINE_UNRESOLVED = 'line-unresolved'
    RETRYABLE_TECHNICAL = 'retryable-technical'
    SUPERSEDED_RECORDING = 'superseded-recording'
    NOT_SELECTED = 'recording-not-selected'
    CONTENT_RESTRICTION = 'platform-content-restriction'
    ARTWORK_UNAVAILABLE = 'artwork-unavailable'
    REVIEW_STALE = 'review-stale'
    MODEL_IDENTITY_MISMATCH = 'model-identity-mismatch'
    PUBLICATION_CONFLICT = 'publication-conflict'
    BRIDGE_PACKAGE_TOO_LARGE = 'bridge-package-too-large'


OUTCOME_CODES = frozenset(value.value for value in WorkflowOutcome)
WORKFLOW_OPERATION_OUTCOME_CODES = OUTCOME_CODES

ROUTE_STAGES = frozenset({'caption', 'structure', 'endpoint-seed', 'endpoint-verifier',
                         'endpoint', 'endpoint-consensus', 'endpoint-route-verifier', 'endpoint-bootstrap'})
ROUTE_STATUSES = frozenset({'started', 'success', 'unavailable', 'transient-failure', 'failure'})


def route_attempt_event(sequence, stage, route_id, status, *, cache_reused=None, code=None):
    if status == 'failed' and stage == 'endpoint-seed':
        status = 'failure'
    if (type(sequence) is not int or sequence < 0 or stage not in ROUTE_STAGES
            or status not in ROUTE_STATUSES or not isinstance(route_id, str)
            or re.fullmatch(r'[A-Za-z0-9_.:+-]{1,160}', route_id) is None):
        raise ValueError('route event identity is invalid')
    event = {'sequence': sequence, 'stage': stage, 'routeId': route_id, 'status': status}
    if cache_reused is not None or status != 'started':
        if cache_reused is not None and type(cache_reused) is not bool:
            raise ValueError('route event cache identity is invalid')
        event['cacheReused'] = False if cache_reused is None else cache_reused
    if code is not None:
        event['code'] = str(code)
        if getattr(code, 'diagnostics', ()):
            event['privateDiagnostics'] = list(code.diagnostics)
    return event


import re
from dataclasses import dataclass
from typing import Any

PRIVATE_REPORT_VERSION = 2


ENDPOINT_REVIEW_ERROR_CODE_PATTERN = re.compile(
    r"endpoint-review-(?:decision-invalid|decision-changed|metadata-invalid|audio-evidence-invalid|selections-incomplete|"
    r"singing-selection-invalid|ctc-evidence-abstained|"
    r"selection-(?:invalid-\d+|empty-or-reversed-\d+|overlap-\d+-\d+(?:_\d+-\d+)*))"
)


WORKFLOW_OPERATION_ACTIONS = frozenset(
    {"New", "Source", "Artwork", "RecordingEvidence", "Validate", "Finalize", "Retry", "ReplaceAnalysis"}
)


WORKFLOW_OPERATION_ACTION_ORDER = ("New", "Source", "Artwork", "RecordingEvidence", "Validate", "Finalize", "Retry", "ReplaceAnalysis")


WORKFLOW_OPERATION_STATES = frozenset(state.value for state in JobState)


class FutureSongError(ValueError):
    """Text-private, user-actionable future-song failure."""


def structure_witness_record(value):
    """One private witness shape shared by its producer and report projection."""
    fields = {'family', 'route', 'status', 'cacheReused', 'candidateCount', 'passingCandidateCount',
        'selectedSequenceSupported', 'selectedSequenceEveryLineDirect', 'metrics', 'lexicalAuthoritySha256',
        'modelRevision', 'modelSnapshotSha256', 'helperSha256', 'runtime', 'decoder', 'failureCode',
        'languageHint', 'contentAdmission'}
    if not isinstance(value, dict) or not set(value) <= fields:
        raise FutureSongError('structure witness is malformed')
    record = dict(value)
    if 'contentAdmission' in record:
        from stemwise_engine.lyric_content import ContentAdmission
        record['contentAdmission'] = ContentAdmission.from_record(record['contentAdmission']).to_record()
    return record


@dataclass(frozen=True)
class EndpointReviewResult:
    declaration_sha256: str
    review_sha256: str
    source_run_id: str
    source_run_sha256: str
    occurrence_count: int
    policy: str = ENDPOINT_REVIEW_POLICY

    def __post_init__(self):
        if (self.policy != ENDPOINT_REVIEW_POLICY
                or any(not isinstance(value, str) or re.fullmatch('[0-9a-f]{64}', value) is None
                    for value in (self.declaration_sha256, self.review_sha256, self.source_run_sha256))
                or not isinstance(self.source_run_id, str) or re.fullmatch('[0-9a-f]{32}', self.source_run_id) is None
                or type(self.occurrence_count) is not int or self.occurrence_count <= 0):
            raise FutureSongError('reviewed endpoint evidence is malformed')

    def to_record(self):
        return {'policy': self.policy, 'declarationSha256': self.declaration_sha256,
            'reviewSha256': self.review_sha256, 'sourceAlignmentRunId': self.source_run_id,
            'sourceAlignmentRunSha256': self.source_run_sha256, 'reviewedOccurrenceCount': self.occurrence_count}

    @classmethod
    def from_record(cls, value):
        if not isinstance(value, dict) or set(value) != {'policy', 'declarationSha256', 'reviewSha256',
                'sourceAlignmentRunId', 'sourceAlignmentRunSha256', 'reviewedOccurrenceCount'}:
            raise FutureSongError('reviewed endpoint evidence is malformed')
        return cls(value['declarationSha256'], value['reviewSha256'], value['sourceAlignmentRunId'],
            value['sourceAlignmentRunSha256'], value['reviewedOccurrenceCount'], value['policy'])


class BridgePackageTooLarge(FutureSongError):
    """The complete song cannot fit its accepted transfer budget."""

    def __init__(self, message, *, completed=False):
        self.completed = completed
        super().__init__(message)


class WorkflowInputError(FutureSongError):
    """Stable repair instructions attached by the producer, independent of prose."""
    def __init__(self, message, *, reason_code, affected_input, next_action):
        if reason_code not in OUTCOME_CODES:
            raise ValueError('Invalid workflow input failure code')
        self.failure_details = {'reasonCode': reason_code, 'affectedInput': affected_input,
            'nextAction': next_action}
        super().__init__(message)


class ArtworkUnavailableError(WorkflowInputError):
    def __init__(self, message):
        super().__init__(message, reason_code='artwork-unavailable',
            affected_input='selected cover.png/artworkReview',
            next_action='Run Artwork with an already reviewed matching recording, then Validate.')


def workflow_failure_details(error: BaseException) -> dict[str, Any]:
    """Classify current boundary failures without copying private exception messages."""
    from stemwise_engine.provider_operations import ProviderError, ProviderDeadline, ProviderCancelled, provider_failure_cause
    error = provider_failure_cause(error) or error
    if isinstance(error, (ProviderDeadline, ProviderCancelled)):
        return {'reasonCode': 'retryable-technical', 'affectedInput': 'current provider operation',
                'nextAction': ('Provider work was cancelled; resume only on an explicit user request.'
                    if isinstance(error, ProviderCancelled) else
                    'Retry the provider operation after its elapsed-time deadline.')}
    if isinstance(error, ProviderError):
        cause = error.cause
        status = f" HTTP {cause['httpStatus']}" if cause['httpStatus'] is not None else ''
        return {'reasonCode': 'retryable-technical' if cause['retryable'] else 'future-song-error',
            'affectedInput': f"provider response{status} ({cause['exceptionType']})",
            'nextAction': ('Retry the provider operation after its temporary failure clears.' if cause['retryable']
                           else 'Resolve the provider refusal or invalid response before retrying.')}
    if isinstance(error, BridgePackageTooLarge):
        return {'reasonCode': 'bridge-package-too-large', 'affectedInput': 'complete audio-bearing song ZIP',
                'nextAction': ('Manually import the complete ZIP retained in Ready to Import; do not retry completed engine work.'
                    if error.completed else 'Use Retry -ManualImport to finish the full-quality PC ZIP for manual import; do not resubmit this transfer.')}
    if isinstance(error, ContentEvidenceUnavailable):
        return {'reasonCode': error.code, 'affectedInput': 'current retained recording content evidence',
                'nextAction': 'Restore or refresh exact-recording recognition evidence, then retry Validate; retain the current wording and prior packages.'}
    if isinstance(error, FutureSongOutcomeError):
        return {'reasonCode': error.code, 'affectedInput': 'current job/review inputs',
                'nextAction': ('Correct uncertain performed lyric content before timing work.'
                    if isinstance(error, LyricsContentBlocker) else 'Read Task and resolve its current outcome before retrying.')}
    code = ('superseded-recording' if isinstance(error, SupersededRecordingError)
            else endpoint_review_error_code(error))
    if code is None and str(error) in OUTCOME_CODES:
        code = str(error)
    if code is not None:
        return {'reasonCode': code, 'affectedInput': 'current selected source/review',
                'nextAction': 'Read Task and use its current selected source and review inputs.'}
    chain, seen = [], set()
    current = error
    while current is not None and id(current) not in seen and len(chain) < 8:
        seen.add(id(current))
        chain.append(current)
        current = current.__cause__ or current.__context__
    for cause in chain:
        if isinstance(cause, WorkflowInputError):
            return dict(cause.failure_details)
    reason = 'future-song-error' if isinstance(error, FutureSongError) else 'unexpected-error'
    artifact, action = 'current job inputs', 'Read Task and correct its current prerequisites before retrying.'
    result = {'reasonCode': reason, 'affectedInput': artifact, 'nextAction': action}
    if not isinstance(error, FutureSongError) or reason == 'future-song-error':
        trace = []
        for cause in chain:
            frames = []
            cursor = cause.__traceback__
            while cursor is not None:
                frame = cursor.tb_frame
                frames.append({'file': frame.f_code.co_filename.replace('\\', '/').rsplit('/', 1)[-1][:120],
                               'function': frame.f_code.co_name[:120], 'line': cursor.tb_lineno})
                cursor = cursor.tb_next
            trace.append({'type': type(cause).__name__[:120], 'frames': frames[-4:]})
        result['causalTrace'] = trace
    return result


def failure_details_are_safe(value):
    if (not isinstance(value, dict) or set(value) not in (
            {'reasonCode', 'affectedInput', 'nextAction'},
            {'reasonCode', 'affectedInput', 'nextAction', 'causalTrace'})
            or (value.get('reasonCode') not in OUTCOME_CODES and endpoint_review_error_code(value.get('reasonCode')) is None)
            or any(not isinstance(value.get(key), str) or not 1 <= len(value[key]) <= 512
                   or any(ord(char) < 32 for char in value[key])
                   for key in ('affectedInput', 'nextAction'))):
        return False
    trace = value.get('causalTrace', [])
    if not isinstance(trace, list) or len(trace) > 8:
        return False
    for cause in trace:
        if (not isinstance(cause, dict) or set(cause) != {'type', 'frames'}
                or not isinstance(cause['type'], str) or re.fullmatch('[A-Za-z0-9_]{1,120}', cause['type']) is None
                or not isinstance(cause['frames'], list) or len(cause['frames']) > 4):
            return False
        for frame in cause['frames']:
            if (not isinstance(frame, dict) or set(frame) != {'file', 'function', 'line'}
                    or type(frame['line']) is not int or frame['line'] <= 0
                    or any(not isinstance(frame[key], str) or not 1 <= len(frame[key]) <= 120
                           or any(ord(char) < 32 for char in frame[key]) for key in ('file', 'function'))):
                return False
    return True


class SupersededRecordingError(FutureSongError):
    """Historical YouTube audio is no longer the selected recording."""


class EndpointRefusalCode(str, Enum):
    CTC_EVIDENCE_ABSTAINED = 'endpoint-review-ctc-evidence-abstained'


class EndpointReviewSelectionError(FutureSongError):
    """Body-free endpoint-review selection failure safe for the task ledger."""
    def __init__(self, code: EndpointRefusalCode | str):
        code = code.value if isinstance(code, EndpointRefusalCode) else code
        if endpoint_review_error_code(code) is None:
            raise ValueError('invalid endpoint refusal code')
        super().__init__(code)


def endpoint_review_error_code(value: object) -> str | None:
    code = str(value)
    return code if ENDPOINT_REVIEW_ERROR_CODE_PATTERN.fullmatch(code) else None


class ReportSizeLimitExceeded(FutureSongError):
    """A private run was too large to store under the bounded report contract."""


class FutureSongOutcomeError(FutureSongError):
    """A recoverable, text-free workflow outcome with an exact job state."""

    _MESSAGES = {
        ("AUTHORITY_UNRESOLVED", "authority-unresolved"):
            "automatic authority research is complete but material authority remains unresolved",
        ("LINE_UNRESOLVED", "line-unresolved"):
            "lyrics timing or performed structure remains unresolved; approved text was preserved",
        ("NEEDS_LYRICS", "lyrics-content-unresolved"):
            "lyrics content is uncertain; timing stopped and current text was preserved",
        ("RETRYABLE_TECHNICAL", "retryable-technical"):
            "lyrics timing hit a retryable technical failure; completed evidence was preserved",
    }

    def __init__(self, state: str, code: str):
        try:
            message = self._MESSAGES[(state, code)]
        except KeyError as error:
            raise ValueError("unsupported future-song outcome") from error
        self.state = state
        self.code = code
        super().__init__(message)


CONTENT_EVIDENCE_TECHNICAL_REASONS = frozenset({'content-evidence-unavailable',
    'content-evidence-invalid', 'content-evidence-changed'})


class ContentEvidenceUnavailable(FutureSongOutcomeError):
    """Missing/stale private proof requires fresh evidence, never a lyric correction."""
    def __init__(self, reason='content-evidence-unavailable', *, binding=None):
        if reason not in CONTENT_EVIDENCE_TECHNICAL_REASONS:
            raise ValueError('invalid content evidence technical reason')
        self.reasons, self.binding = (reason,), binding
        super().__init__('RETRYABLE_TECHNICAL', 'retryable-technical')


class LyricsContentBlocker(FutureSongOutcomeError):
    """Content uncertainty must never become a timing-recovery request."""

    def __init__(self, *reasons: str, binding: dict[str, Any] | None = None):
        if not reasons:
            reasons = ("content-evidence-unavailable",)
        if len(reasons) > 32 or any(
            not isinstance(reason, str)
            or re.fullmatch(r"[a-z0-9][a-z0-9_.:-]{0,159}", reason) is None
            for reason in reasons
        ):
            raise ValueError("lyrics content blocker reasons are invalid")
        if binding is not None and (
            not isinstance(binding, dict)
            or set(binding) != {"canonicalPlainSha256", "audioSha256", "sourcePhysicalIndices"}
            or any(not isinstance(binding[key], str) or re.fullmatch(r"[0-9a-f]{64}", binding[key]) is None
                   for key in ("canonicalPlainSha256", "audioSha256"))
            or not isinstance(binding["sourcePhysicalIndices"], list)
            or not binding["sourcePhysicalIndices"]
            or any(type(index) is not int or index < 0
                   for index in binding["sourcePhysicalIndices"])
        ):
            raise ValueError("lyrics content blocker binding is invalid")
        self.reasons = tuple(dict.fromkeys(reasons))
        self.binding = ({**binding, "sourcePhysicalIndices": list(binding["sourcePhysicalIndices"])}
                        if binding is not None else None)
        super().__init__("NEEDS_LYRICS", "lyrics-content-unresolved")


def operation_record_is_safe(record: dict[str, Any]) -> bool:
    phase = record.get("phase")
    common_keys = {
        "reportVersion",
        "runId",
        "videoId",
        "operation",
        "operationId",
        "action",
        "phase",
        "status",
        "startedAtUnix",
        "stateBefore",
        "bindings",
    }
    expected_keys = (
        common_keys
        if phase == "start"
        else common_keys | {"completedAtUnix", "stateAfter", "outcomeCode"}
    )
    if phase == 'outcome' and 'failureDetails' in record:
        expected_keys |= {'failureDetails'}
        if record.get('status') != 'failed' or not failure_details_are_safe(record['failureDetails']):
            return False
    if 'privateDiagnostics' in record:
        from stemwise_engine.private_diagnostics import validate_references
        try:
            validate_references(record['privateDiagnostics'])
        except ValueError:
            return False
        if phase != 'outcome' or record.get('status') != 'failed':
            return False
        expected_keys |= {'privateDiagnostics'}
    bindings = record.get("bindings")
    if (
        set(record) != expected_keys
        or record.get("reportVersion") != PRIVATE_REPORT_VERSION
        or record.get("operation") != "workflow-operation"
        or not isinstance(record.get("runId"), str)
        or not isinstance(record.get("operationId"), str)
        or re.fullmatch(r"[0-9a-f]{32}", str(record.get("runId") or "")) is None
        or re.fullmatch(r"[0-9a-f]{32}", str(record.get("operationId") or "")) is None
        or re.fullmatch(r"[A-Za-z0-9_-]{11}", str(record.get("videoId") or "")) is None
        or record.get("action") not in WORKFLOW_OPERATION_ACTIONS
        or record.get("stateBefore") not in WORKFLOW_OPERATION_STATES | {"ABSENT", "UNKNOWN"}
        or type(record.get("startedAtUnix")) is not int
        or record["startedAtUnix"] < 0
        or not isinstance(bindings, dict)
        or set(bindings) != {"configSha256", "policySha256", "engineSourceSha256"}
        or any(
            not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None
            for value in bindings.values()
        )
    ):
        return False
    if phase == "start":
        return record.get("status") == "started"
    if (
        phase != "outcome"
        or record.get("status") not in {"complete", "failed", "interrupted"}
        or record.get("stateAfter") not in WORKFLOW_OPERATION_STATES | {"ABSENT", "UNKNOWN"}
        or (
            record.get("outcomeCode") not in WORKFLOW_OPERATION_OUTCOME_CODES
            and endpoint_review_error_code(record.get("outcomeCode")) is None
        )
        or type(record.get("completedAtUnix")) is not int
        or record["completedAtUnix"] < record["startedAtUnix"]
    ):
        return False
    if record["status"] == "complete":
        return record["outcomeCode"] == "success"
    if record["status"] == "interrupted":
        return record["outcomeCode"] == "keyboard-interrupt"
    return record["outcomeCode"] not in {"success", "keyboard-interrupt"}


@dataclass(frozen=True)
class ReportBindings:
    config_sha256: str
    policy_sha256: str
    engine_source_sha256: str

    def to_record(self):
        return {'configSha256': self.config_sha256, 'policySha256': self.policy_sha256,
                'engineSourceSha256': self.engine_source_sha256}


def engine_source_sha256(source_root):
    """Diagnostic identity of the loaded engine's named Python sources; never a cache key."""
    import hashlib
    from stemwise_engine.common import json_bytes, sha256_file
    sources = {path.name: sha256_file(path) for path in sorted(source_root.glob('*.py'))}
    if not sources:
        raise FutureSongError('workflow engine sources are missing')
    return hashlib.sha256(json_bytes(sources)).hexdigest()


@dataclass(frozen=True)
class OperationStart:
    run_id: str
    video_id: str
    operation_id: str
    action: str
    started_at: int
    state_before: str
    bindings: ReportBindings

    def to_record(self):
        record = {'reportVersion': PRIVATE_REPORT_VERSION, 'runId': self.run_id,
            'videoId': self.video_id, 'operation': 'workflow-operation', 'operationId': self.operation_id,
            'action': self.action, 'phase': 'start', 'status': 'started', 'startedAtUnix': self.started_at,
            'stateBefore': self.state_before, 'bindings': self.bindings.to_record()}
        if not operation_record_is_safe(record):
            raise FutureSongError('workflow operation start is invalid')
        return record

    def finish(self, run_id: str, completed_at: int, state_after: str, outcome: str):
        return OperationOutcome(self, run_id, completed_at, state_after, outcome)


@dataclass(frozen=True)
class OperationOutcome:
    start: OperationStart
    run_id: str
    completed_at: int
    state_after: str
    outcome: str

    def to_record(self):
        record = self.start.to_record()
        status = ('complete' if self.outcome == WorkflowOutcome.SUCCESS.value else 'interrupted'
                  if self.outcome == WorkflowOutcome.INTERRUPTED.value else 'failed')
        record.update(runId=self.run_id, phase='outcome', status=status, completedAtUnix=self.completed_at,
                      stateAfter=self.state_after, outcomeCode=self.outcome)
        if not operation_record_is_safe(record):
            raise FutureSongError('workflow operation outcome is invalid')
        return record
