"""Current job lifecycle, checked at the durable mutation boundary."""
from dataclasses import dataclass
from enum import Enum


class JobState(str, Enum):
    NEW = 'NEW'
    ANALYZING = 'ANALYZING'
    ANALYSIS_FAILED = 'ANALYSIS_FAILED'
    ANALYSIS_REPLACEMENT_PENDING = 'ANALYSIS_REPLACEMENT_PENDING'
    EDITORIAL_SETUP_FAILED = 'EDITORIAL_SETUP_FAILED'
    NEEDS_LYRICS = 'NEEDS_LYRICS'
    WAITING_FOR_EDITORIAL = 'WAITING_FOR_EDITORIAL'
    WAITING_FOR_DISPLAY_REVIEW = 'WAITING_FOR_DISPLAY_REVIEW'
    VALIDATION_FAILED = 'VALIDATION_FAILED'
    ALIGNING_LYRICS = 'ALIGNING_LYRICS'
    VALIDATED = 'VALIDATED'
    AUTHORITY_UNRESOLVED = 'AUTHORITY_UNRESOLVED'
    LINE_UNRESOLVED = 'LINE_UNRESOLVED'
    RETRYABLE_TECHNICAL = 'RETRYABLE_TECHNICAL'
    DELIVERED = 'DELIVERED'


class JobStage(str, Enum):
    ANALYSIS = 'analysis'
    EDITORIAL = 'editorial'
    RECORDING_EVIDENCE = 'recording-evidence'
    ALIGNMENT = 'alignment'
    VALIDATION = 'validation'
    DELIVERY = 'delivery'


class StageOutcome(str, Enum):
    PENDING = 'pending'
    REPLACEMENT_PENDING = 'replacement-pending'
    ACTIVE = 'active'
    FAILED = 'failed'
    BLOCKED = 'blocked'
    COMPLETE = 'complete'


@dataclass(frozen=True)
class StageRule:
    actions: frozenset[str]
    retry_action: str | None
    interrupted_state: JobState | None = None


_EDIT_ACTIONS = frozenset({'Source', 'Validate'})
_RETRY_ACTIONS = _EDIT_ACTIONS | {'Retry'}
# This table owns admission, explicit retry routing and interrupted-stage recovery.
STAGE_RULES = {
    (JobStage.ANALYSIS, StageOutcome.REPLACEMENT_PENDING): StageRule(frozenset({'Retry'}), 'ReplaceAnalysis'),
    (JobStage.ANALYSIS, StageOutcome.PENDING): StageRule(frozenset({'Source', 'Validate'}), None, JobState.ANALYSIS_FAILED),
    (JobStage.ANALYSIS, StageOutcome.ACTIVE): StageRule(frozenset({'Source', 'Validate'}), None, JobState.ANALYSIS_FAILED),
    (JobStage.ANALYSIS, StageOutcome.FAILED): StageRule(_RETRY_ACTIONS, 'New'),
    (JobStage.EDITORIAL, StageOutcome.FAILED): StageRule(_RETRY_ACTIONS, 'New'),
    (JobStage.EDITORIAL, StageOutcome.PENDING): StageRule(_EDIT_ACTIONS | {'Artwork', 'RecordingEvidence'}, None),
    (JobStage.EDITORIAL, StageOutcome.BLOCKED): StageRule(_RETRY_ACTIONS | {'Artwork', 'RecordingEvidence'}, 'Validate'),
    (JobStage.RECORDING_EVIDENCE, StageOutcome.ACTIVE): StageRule(frozenset(), None, JobState.RETRYABLE_TECHNICAL),
    (JobStage.RECORDING_EVIDENCE, StageOutcome.FAILED): StageRule(_RETRY_ACTIONS | {'RecordingEvidence'}, 'RecordingEvidence'),
    (JobStage.RECORDING_EVIDENCE, StageOutcome.BLOCKED): StageRule(_EDIT_ACTIONS | {'RecordingEvidence'}, None),
    (JobStage.ALIGNMENT, StageOutcome.ACTIVE): StageRule(_EDIT_ACTIONS, None, JobState.VALIDATION_FAILED),
    (JobStage.ALIGNMENT, StageOutcome.BLOCKED): StageRule(_RETRY_ACTIONS | {'RecordingEvidence'}, 'Validate'),
    (JobStage.VALIDATION, StageOutcome.FAILED): StageRule(_RETRY_ACTIONS | {'RecordingEvidence'}, 'Validate'),
    (JobStage.VALIDATION, StageOutcome.PENDING): StageRule(_EDIT_ACTIONS, None),
    (JobStage.VALIDATION, StageOutcome.COMPLETE): StageRule(_EDIT_ACTIONS | {'Finalize'}, None),
    (JobStage.DELIVERY, StageOutcome.COMPLETE): StageRule(frozenset({'Source', 'Artwork', 'Validate', 'Finalize', 'ReplaceAnalysis'}), None),
}

_STATE_STAGES = {
    JobState.ANALYSIS_REPLACEMENT_PENDING: (JobStage.ANALYSIS, StageOutcome.REPLACEMENT_PENDING),
    JobState.NEW: (JobStage.ANALYSIS, StageOutcome.PENDING),
    JobState.ANALYZING: (JobStage.ANALYSIS, StageOutcome.ACTIVE),
    JobState.ANALYSIS_FAILED: (JobStage.ANALYSIS, StageOutcome.FAILED),
    JobState.EDITORIAL_SETUP_FAILED: (JobStage.EDITORIAL, StageOutcome.FAILED),
    JobState.NEEDS_LYRICS: (JobStage.EDITORIAL, StageOutcome.BLOCKED),
    JobState.WAITING_FOR_EDITORIAL: (JobStage.EDITORIAL, StageOutcome.PENDING),
    JobState.WAITING_FOR_DISPLAY_REVIEW: (JobStage.VALIDATION, StageOutcome.PENDING),
    JobState.VALIDATION_FAILED: (JobStage.VALIDATION, StageOutcome.FAILED),
    JobState.ALIGNING_LYRICS: (JobStage.ALIGNMENT, StageOutcome.ACTIVE),
    JobState.VALIDATED: (JobStage.VALIDATION, StageOutcome.COMPLETE),
    JobState.AUTHORITY_UNRESOLVED: (JobStage.EDITORIAL, StageOutcome.BLOCKED),
    JobState.LINE_UNRESOLVED: (JobStage.ALIGNMENT, StageOutcome.BLOCKED),
    JobState.RETRYABLE_TECHNICAL: (JobStage.VALIDATION, StageOutcome.FAILED),
    JobState.DELIVERED: (JobStage.DELIVERY, StageOutcome.COMPLETE),
}


@dataclass(frozen=True)
class JobProgress:
    stage: JobStage
    outcome: StageOutcome

    @classmethod
    def current(cls, job):
        state = JobState(job['state'])
        outcome = job.get('outcome') or {}
        evidence = job.get('recordingEvidence')
        evidence = evidence if isinstance(evidence, dict) else {}
        if evidence.get('status') == 'running':
            return cls(JobStage.RECORDING_EVIDENCE, StageOutcome.ACTIVE)
        if outcome.get('sourceStage') == JobStage.RECORDING_EVIDENCE.value:
            if state == JobState.RETRYABLE_TECHNICAL:
                return cls(JobStage.RECORDING_EVIDENCE, StageOutcome.FAILED)
            if state == JobState.NEEDS_LYRICS:
                return cls(JobStage.RECORDING_EVIDENCE, StageOutcome.BLOCKED)
        if (evidence.get('status') == 'failed'
                and state in {JobState.NEEDS_LYRICS, JobState.WAITING_FOR_EDITORIAL, JobState.VALIDATION_FAILED}
                and not outcome):
            return cls(JobStage.RECORDING_EVIDENCE, StageOutcome.FAILED)
        return cls(*_STATE_STAGES[state])

    @property
    def rule(self):
        return STAGE_RULES[(self.stage, self.outcome)]

    def to_record(self):
        return {'stage': self.stage.value, 'outcome': self.outcome.value}

    @property
    def next_action(self):
        if self.rule.interrupted_state is not None or self.stage == JobStage.DELIVERY:
            return 'Status'
        if self.rule.retry_action == 'Validate':
            return 'Validate'
        if self.rule.retry_action is not None:
            return 'Retry'
        return 'Finalize' if 'Finalize' in self.rule.actions else 'Validate'


def save_job(path, job):
    """One durable writer validates transitions and commits their stage/outcome."""
    from stemwise_engine.common import atomic_write_json, read_json, utc_now
    previous = read_json(path) if path.exists() else {}
    validation = previous.get('validation', {})
    capability_owner = validation.get('kind') == 'capability-completion' or (
        validation.get('kind') is None
        and isinstance(validation.get('runId'), str) and validation['runId']
        and validation.get('displayReviewAttemptId') == validation['runId']
        and all(isinstance(validation.get(key), str) and validation[key] for key in
            ('runSha256', 'displayReviewDeclarationSha256', 'displayOriginalSha256'))
        and job.get('capabilityRefresh', {}).get('previousValidation') == validation)
    JobTransition.require(previous.get('state'), job.get('state'),
        artwork_repair=isinstance(previous.get('artworkRepairPending'), dict)
            and isinstance(job.get('artworkRepair'), dict) and 'delivery' not in job,
        capability_refresh=capability_owner
            and (job.get('validation', {}).get('kind') == 'capability-completion'
                or job.get('state') == 'WAITING_FOR_DISPLAY_REVIEW'
                and job.get('capabilityRefresh', {}).get('previousValidation') == previous['validation']
                and job.get('endpointReview', {}).get('result') == 'accepted')
            and isinstance(previous.get('delivery'), dict) and previous['delivery'] == job.get('delivery'),
        analysis_replacement=isinstance(job.get('analysisReplacement'), dict)
            and not previous.get('analysisReplacement')
            and job['analysisReplacement'].get('deliverySha256') == previous.get('delivery', {}).get('sha256')
            and job['analysisReplacement'].get('analysisSha256') == previous.get('validation', {}).get('audioSha256')
            and previous.get('delivery') == job.get('delivery'))
    job['progress'] = JobProgress.current(job).to_record()
    job['updatedAt'] = utc_now()
    atomic_write_json(path, job)


_EDITORIAL = frozenset({JobState.NEEDS_LYRICS, JobState.WAITING_FOR_EDITORIAL,
    JobState.WAITING_FOR_DISPLAY_REVIEW, JobState.VALIDATION_FAILED,
    JobState.AUTHORITY_UNRESOLVED, JobState.LINE_UNRESOLVED, JobState.RETRYABLE_TECHNICAL})
_ANALYSIS = frozenset({JobState.NEW, JobState.ANALYZING, JobState.ANALYSIS_FAILED,
                      JobState.EDITORIAL_SETUP_FAILED, JobState.ANALYSIS_REPLACEMENT_PENDING})


@dataclass(frozen=True)
class JobTransition:
    before: JobState | None
    after: JobState

    @classmethod
    def require(cls, before, after, *, artwork_repair=False, capability_refresh=False, analysis_replacement=False):
        old, new = JobState(before) if before is not None else None, JobState(after)
        allowed = (old is None and new == JobState.NEW or old == new
            or old in _ANALYSIS and new in _ANALYSIS | _EDITORIAL
            or old in _EDITORIAL and new in _EDITORIAL | {JobState.ANALYZING, JobState.ALIGNING_LYRICS, JobState.VALIDATED}
            or old == JobState.ALIGNING_LYRICS and new in _EDITORIAL | {JobState.VALIDATED}
            or old == JobState.VALIDATED and new in {JobState.DELIVERED, JobState.VALIDATION_FAILED,
                JobState.WAITING_FOR_DISPLAY_REVIEW, JobState.ALIGNING_LYRICS,
                JobState.WAITING_FOR_EDITORIAL, JobState.AUTHORITY_UNRESOLVED}
            or artwork_repair and old == JobState.DELIVERED and new == JobState.VALIDATED
            or capability_refresh and old == JobState.DELIVERED
                and new in {JobState.VALIDATED, JobState.WAITING_FOR_DISPLAY_REVIEW}
            or analysis_replacement and old == JobState.DELIVERED and new == JobState.ANALYSIS_REPLACEMENT_PENDING)
        if not allowed:
            raise ValueError(f'Unsupported job transition: {before} -> {after}')
        return cls(old, new)


TERMINAL_STATES = frozenset({JobState.DELIVERED.value})
