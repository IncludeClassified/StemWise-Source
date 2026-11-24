"""Isolated, resumable one-song workflow for future Stemwise enrichment jobs."""

from __future__ import annotations

import base64
from stemwise_engine.workflow_context import report_control_path
from stemwise_engine.runtime_environment import environment_path, environment_python
import stemwise_engine.editorial_review as editorial
import stemwise_engine.workflow_review as workflow_review
import stemwise_engine.source_acquisition as source_acquisition
import stemwise_engine.workflow_diagnostics as workflow_diagnostics
import stemwise_engine.workflow_records as review_contract
import copy
import hashlib
import json
import math
import os
import re
import shutil
import stat
import subprocess
import sys
import time
import traceback
import unicodedata
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from contextlib import contextmanager, nullcontext
from functools import cached_property, partial
from stemwise_engine.workflow_records import (
    ROUTE_STAGES, ROUTE_STATUSES,WorkflowOutcome, OUTCOME_CODES, PRIVATE_REPORT_VERSION,
    WORKFLOW_OPERATION_ACTIONS, WORKFLOW_OPERATION_ACTION_ORDER, WORKFLOW_OPERATION_STATES,
    ENDPOINT_REVIEW_ERROR_CODE_PATTERN, FutureSongError, SupersededRecordingError,
    EndpointReviewSelectionError, ReportSizeLimitExceeded, FutureSongOutcomeError, LyricsContentBlocker,
    ContentEvidenceUnavailable,
    endpoint_review_error_code, operation_record_is_safe, engine_source_sha256)
from stemwise_engine.workflow_review import (ReviewPolicy, ReviewOperations, ReviewCompleted,
    EndpointReviewService, EndpointReviewRequest, DisplayReviewService, DisplayReviewInputs, DisplayReviewAuthorityError,
    ValidatedSnapshotService, ValidatedSnapshotRequest)

from stemwise_engine.common import (
    WorkerRetirementError,
    GENERATED_MANIFEST_NAME,
    atomic_write_bytes,
    atomic_write_json,
    canonical_lyrics_language,
    create_manifested_directory,
    durable_replace,
    diagnostic_code,
    exclusive_process_lock,
    process_lock_descriptor,
    heavy_worker_stage,
    json_bytes,
    load_config,
    manifested_temporary_directory,
    parse_strict_json_bytes,
    preflight_staging_space,
    ProcessLockBusyError,
    read_bounded_json,
    read_bounded_regular_bytes,
    read_json,
    remove_manifested_directory,
    manifested_cleanup_receipt,
    remove_disposable_path,
    resolve_path,
    run_process_tree,
    sha256_file,
    utc_now,
    validate_generated_path,
    validate_video_id,
)
from stemwise_engine.lyrics_intake import ENDPOINT_REFINEMENT_REASON, compile_draft, parse_lrc, read_role_text
from stemwise_engine.lyrics_qa import audit_text
from stemwise_engine.metadata_research import apply_reviewed_metadata, metadata_sha256, validate_public_source_url
from stemwise_engine.contract import MAX_JSON_BYTES, MAX_SINGLE_ZIP_BYTES
from stemwise_engine.packages import create_child_zip, read_child, validate_child_zip
from stemwise_engine.pipeline import StemwisePipeline, _strictly_later_generated_at, require_cleanup_root_disjoint
from stemwise_engine.youtube import canonical_url
from stemwise_engine import youtube


from stemwise_engine.workflow_states import TERMINAL_STATES

VALIDATION_FAILURE_ELIGIBLE_STATES = {
    "WAITING_FOR_EDITORIAL",
    "WAITING_FOR_DISPLAY_REVIEW",
    "VALIDATION_FAILED",
    "ALIGNING_LYRICS",
    "VALIDATED",
    "AUTHORITY_UNRESOLVED",
    "LINE_UNRESOLVED",
    "RETRYABLE_TECHNICAL",
}
ACTIVE_ALIGNMENT_STATES = {
    "starting",
    "queued",
    "caption-check",
    "transcribing",
    "transcribing-roformer-fallback",
    "forced-aligning-repeat-structure",
    "aligning",
    "refining-endpoints",
}




STALE_ALIGNMENT_GRACE_SECONDS = 30
ALIGNMENT_ATTEMPT_ID_PATTERN = re.compile(r"[0-9a-f]{32}")
WORKFLOW_OPERATION_OUTCOME_CODES = OUTCOME_CODES
ROLE_FILES = (
    "lyrics.meta.json",
    "original.txt",
    "original.lrc",
    "translation-en.txt",
    "translation-en.lrc",
    "transliteration-ipa.txt",
    "transliteration-ipa.lrc",
)
_PCM_STEREO_FLOAT32_48K_BYTES_PER_SECOND = 2 * 4 * 48_000
_EXPENSIVE_SONG_PCM_VIEW_BUDGET = 32
_EXPENSIVE_SONG_FIXED_PEAK_BYTES = 512 * 1024 * 1024


def extract_video_id(value: str) -> str:
    """Extract one exact ID from supported YouTube URL forms or a raw ID."""

    from stemwise_engine.common import extract_youtube_video_id
    try:
        return extract_youtube_video_id(value)
    except ValueError as error:
        raise FutureSongError(str(error)) from error


def option_safe_video_id_argument(video_id: str) -> str:
    """Return an argparse-safe option token without changing exact identity."""

    return f"--video-id={validate_video_id(video_id)}"




def validate_synced_companion_timing(lyrics: dict[str, Any]) -> None:
    """Require every companion to share the original occurrence timeline."""

    tracks = lyrics.get("tracks")
    if not isinstance(tracks, list) or not tracks:
        raise FutureSongError("compiled lyrics contain no tracks")
    originals = [track for track in tracks if track.get("kind") == "original"]
    if len(originals) != 1 or originals[0].get("format") != "synced":
        raise FutureSongError("release lyrics require exactly one synced original track")
    original_timeline = [
        (line.get("startMs"), line.get("endMs"))
        for line in originals[0].get("lines", [])
    ]
    if not original_timeline:
        raise FutureSongError("synced original has no timed lines")
    for track in tracks:
        if track.get("format") != "synced":
            raise FutureSongError("every release companion track must be synchronized")
        timeline = [
            (line.get("startMs"), line.get("endMs"))
            for line in track.get("lines", [])
        ]
        if timeline != original_timeline:
            raise FutureSongError(
                "translation/IPA occurrence timing differs from the synced original"
            )


def track_lines(track: dict[str, Any]) -> list[str]:
    text = track.get("text")
    if isinstance(text, str):
        return text.split("\n")
    timed = track.get("lines")
    if isinstance(timed, list):
        result = []
        for item in timed:
            if not isinstance(item, dict) or not isinstance(item.get("text"), str):
                raise FutureSongError("A timed lyrics line has invalid text")
            result.append(item["text"])
        return result
    raise FutureSongError("A lyrics track has no supported text representation")


from stemwise_engine.workflow_records import ArtworkUnavailableError, WorkflowInputError


class FutureSongWorkflow:
    _workflow_operation_record_is_safe = staticmethod(operation_record_is_safe)




    @cached_property
    def report_store(self):
        from stemwise_engine.workflow_reports import WorkflowReports
        return WorkflowReports(workspace_root=self.workspace_root, root=self.root,
            owner=self.owner, reports=self.reports, runtime=self.runtime,
            jobs=getattr(self, 'jobs', self.root/'jobs'), config_path=getattr(self, 'config_path', None),
            policy_dir=getattr(self, 'policy_dir', None), config=getattr(self, 'config', {}),
            policy=getattr(self, 'policy', {}), native=getattr(self, 'source_context', None) is not None)

    @cached_property
    def review_construction(self):
        from stemwise_engine.workflow_review import ReviewConstructionContext, ReviewConstructionService
        return ReviewConstructionService(ReviewConstructionContext(
            root=self.root, workspace_root=self.workspace_root, report_store=self.report_store,
            alignment_state_path=self._alignment_state_path, display_review_attempt_dir=self._display_review_attempt_dir,
            endpoint_review_attempt_dir=self._endpoint_review_attempt_dir, job_dir=self._job_dir,
            owner_sidecar_path=self._owner_sidecar_path, lexical_mapping=self._lexical_mapping,
            validate_alignment_attempt_id=self._validate_alignment_attempt_id,
            completed_analysis_audio=self._completed_analysis_audio, editorial_sha=self._editorial_sha,
            pipeline=self._pipeline, song_policy_matches=self._song_policy_matches))

    def __init__(self, config_path: Path, *, source_context=None, recording=None, verify_media=True):
        self.source_context = source_context
        from stemwise_engine.workflow_context import current_state_root, WorkflowSourceContext
        if source_context is not None and not isinstance(source_context, WorkflowSourceContext):
            raise TypeError('workflow source context must be immutable construction data')
        if source_context is not None and recording is None:
            raise FutureSongError('Native source construction requires its selected recording')
        self.config_path = config_path.resolve()
        self.config = load_config(self.config_path)
        if source_context is not None:
            from stemwise_engine.recording_source import RecordingSource
            self.recording, self.key = recording, source_context.recording_key
            self.reference_ids = source_context.lyric_reference_ids
            self.video_id = self.reference_ids[0]
            self.source = RecordingSource(self.config, recording, verify_media=verify_media)
        else:
            self.source = youtube.YouTubeSource(self.config)
        if not isinstance(self.config.get("futureRootWindows"), str):
            raise FutureSongError("config.json requires futureRootWindows")
        if not isinstance(self.config.get("futureReadyDirWindows"), str):
            raise FutureSongError("config.json requires futureReadyDirWindows")
        if not isinstance(self.config.get("pcSideRootWindows"), str):
            raise FutureSongError("config.json requires pcSideRootWindows")
        self.pc_side_root = resolve_path(
            self.config["pcSideRootWindows"], base=self.config_path.parent
        )
        self.workspace_root = self.pc_side_root.parent
        self.install_root = resolve_path(
            self.config["futureRootWindows"], base=self.config_path.parent
        )
        self.state_name = source_context.state_name if source_context else 'current'
        self.root = source_context.root if source_context else current_state_root(self.install_root)
        self.ready = resolve_path(self.config["futureReadyDirWindows"], base=self.config_path.parent)
        canonical_ready = Path(os.path.abspath(self.workspace_root / "Ready to Import"))
        if Path(os.path.abspath(self.ready)) != canonical_ready:
            raise FutureSongError(
                "futureReadyDirWindows must name the workspace Ready to Import directory"
            )
        self.analysis_temp = (
            Path(self.config["tempDir"]) / "future-song-policy-state" / self.state_name
        )
        self.jobs = self.root / "jobs"
        self.owner = self.root / "owner-input"
        self.runtime = self.root / "runtime"
        self.history = self.runtime / "publication-rollback"
        self.policy_dir = self.install_root / "policy"
        self.reports = self.workspace_root / "Engine telemetry"
        if source_context is not None:
            self.ready = source_context.ready
            self.reports = source_context.reports
            self.analysis_temp = source_context.analysis_temp
        for path in (
            self.config_path,
            self.install_root,
            self.root,
            self.ready,
            self.analysis_temp,
            self.config_path.parent.parent / "model-cache",
        ):
            validate_generated_path(
                self.workspace_root,
                path,
                workspace_root=self.workspace_root,
            )
        try:
            require_cleanup_root_disjoint(
                self.analysis_temp,
                {
                    "future policy state": self.install_root,
                    "Ready to Import": self.ready,
                    "private reports": self.reports,
                    "model cache": self.config_path.parent.parent / "model-cache",
                },
            )
        except ValueError as error:
            raise FutureSongError(str(error)) from error
        self.analysis_temp.mkdir(parents=True, exist_ok=True)
        for directory in (
            self.root,
            self.jobs,
            self.owner,
            self.runtime,
            self.history,
        ):
            validate_generated_path(
                self.root,
                directory,
                workspace_root=self.workspace_root,
            )
            directory.mkdir(parents=True, exist_ok=True)
        validate_generated_path(
            self.workspace_root,
            self.reports,
            workspace_root=self.workspace_root,
        ).mkdir(parents=True, exist_ok=True)
        self.policy = self._validate_policy()

    def _validate_policy(self) -> dict[str, Any]:
        policy_path = self.policy_dir / "lyrics-policy.json"
        try:
            policy_raw = read_bounded_regular_bytes(
                policy_path, max_bytes=MAX_JSON_BYTES, root=self.policy_dir
            )
            policy = parse_strict_json_bytes(policy_raw)
        except (OSError, ValueError) as error:
            raise FutureSongError("future-song policy files are incomplete or linked") from error
        policy = self._effective_policy(policy)
        self.contract_identity = {}
        for name in ('song-enrichment-v2.schema.json', 'installed-enrichment-v2.schema.json'):
            path = self.workspace_root/'StemWise/contracts/enrichment'/name
            try:
                raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=self.workspace_root)
            except (OSError, ValueError) as error:
                raise FutureSongError('Current production contract is missing or linked') from error
            self.contract_identity[name] = hashlib.sha256(raw).hexdigest()
        self.policy_sha256 = self._execution_policy_sha256(policy)
        return policy



    def _runtime_config(self) -> dict[str, Any]:
        value = dict(self.config)
        value["outputDir"] = str(self.runtime)
        value["lyricsInputDir"] = str(self.owner)
        value["tempDir"] = str(self.analysis_temp)
        value["retainSuccessfulAnalysisAudio"] = True
        if getattr(self, 'source_context', None) is not None:
            if self.key != self.source_context.recording_key or self.recording.identity != json.loads(self.source_context.recording_identity_json):
                raise FutureSongError('Native source construction identity changed')
            value.update(localRecordingKey=self.key, recordingInput=self.recording.identity,
                futureReadyDirWindows=str(self.ready))
        return value

    def _process_song(
        self,
        pipeline: StemwisePipeline,
        item: dict[str, Any],
        *,
        force: bool = False,
    ) -> str:
        from stemwise_engine.alignment_attempt import current_song_ledger
        ledger = current_song_ledger()
        with ledger.primary('acquisition-analysis', {'videoId': item['videoId'],
                'configuration': getattr(pipeline, 'config', {})}) if ledger else nullcontext():
            return pipeline.process_song(item, force=True) if force else pipeline.process_song(item)

    def _run_primary_worker(self, stage, inputs, command, **options):
        from stemwise_engine.alignment_attempt import current_song_ledger
        from stemwise_engine.runtime_environment import command_environment
        ledger = current_song_ledger()
        with command_environment(command, options, builder=Path(self.config['projectDir'])) as options, \
                ledger.primary(stage, inputs) if ledger else nullcontext():
            return run_process_tree(command, **options)

    def _preflight_expensive_song_space(
        self, video_id: str, duration_seconds: Any
    ) -> dict[str, int]:
        """Reserve a conservative full-cascade disk peak before analysis starts."""

        video_id = validate_video_id(video_id)
        if (
            type(duration_seconds) not in {int, float}
            or not math.isfinite(float(duration_seconds))
            or float(duration_seconds) <= 0
        ):
            raise FutureSongError(
                "exact positive song duration is required for the disk-space preflight"
            )
        child_limit = self.config.get("childZipMaxBytes", MAX_SINGLE_ZIP_BYTES)
        if type(child_limit) is not int or child_limit <= 0:
            raise FutureSongError("childZipMaxBytes is invalid")
        destination = validate_generated_path(
            self.ready,
            self.ready / f"{video_id}.stemwise.zip",
            workspace_root=self.workspace_root,
        )
        if destination.exists() and not destination.is_file():
            raise FutureSongError("the Ready to Import destination is not a regular file")
        rollback_bytes = destination.stat().st_size if destination.is_file() else 0
        temporary_peak = math.ceil(
            float(duration_seconds)
            * _PCM_STEREO_FLOAT32_48K_BYTES_PER_SECOND
            * _EXPENSIVE_SONG_PCM_VIEW_BUDGET
        ) + _EXPENSIVE_SONG_FIXED_PEAK_BYTES
        required = temporary_peak + 2 * child_limit + rollback_bytes
        available = shutil.disk_usage(self.workspace_root).free
        result = {
            "version": 1,
            "requiredFreeBytes": required,
            "observedFreeBytes": available,
            "temporaryPeakBytes": temporary_peak,
            "candidateAndStageBytes": 2 * child_limit,
            "rollbackBytes": rollback_bytes,
        }
        if available < required:
            raise FutureSongError(
                "insufficient disk space for analysis, all lyric fallbacks, package "
                "staging, and rollback"
            )
        return result


    def _pipeline(self) -> StemwisePipeline:
        return StemwisePipeline(self._runtime_config())

    def _completed_analysis_audio(
        self, pipeline: StemwisePipeline, video_id: str
    ) -> dict[str, Any]:
        """Return the exact PCM and artwork identities authorized for lyrics."""

        video_id = validate_video_id(video_id)
        checkpoint_path = pipeline.checkpoint_path(video_id)
        if not checkpoint_path.is_file():
            raise FutureSongError("completed analysis checkpoint is missing")
        checkpoint = pipeline.load_checkpoint(video_id)
        audio = checkpoint.get("originalAudio") if isinstance(checkpoint, dict) else None
        producer_matches = self.source.checkpoint_matches(pipeline, checkpoint)
        if (
            not isinstance(checkpoint, dict)
            or checkpoint.get("videoId") != video_id
            or checkpoint.get("status") != "complete"
            or checkpoint.get("generatorVersion") != self.config["generatorVersion"]
            or not producer_matches
            or not isinstance(audio, dict)
            or re.fullmatch(r"[0-9a-f]{64}", str(audio.get("sha256") or "")) is None
            or type(audio.get("byteCount")) is not int
            or audio["byteCount"] <= 0
            or type(audio.get("durationMs")) is not int
            or audio["durationMs"] <= 0
        ):
            raise FutureSongError("completed analysis audio identity is invalid or stale")
        document, cover = read_child(pipeline.child_path(video_id), pipeline.config)
        if (
            document.get("source", {}).get("videoId") != video_id
            or cover is None
            or document.get("generator", {}).get("version")
            != self.config["generatorVersion"]
            or document.get("analysis", {}).get("status") == "unavailable"
            or document.get("analysis", {}).get("durationMs") != audio["durationMs"]
        ):
            raise FutureSongError("analysis child does not match its completed PCM identity")
        try:
            retained = pipeline.require_retained_analysis_audio(video_id)
        except (OSError, ValueError) as error:
            raise FutureSongError("completed analysis PCM is not retained or intact") from error
        return {
            **{key: audio[key] for key in ("sha256", "byteCount", "durationMs")},
            "artworkSha256": hashlib.sha256(cover).hexdigest(),
            "path": retained,
        }

    @staticmethod
    def _effective_policy(metadata):
        from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION
        if not isinstance(metadata, dict):
            raise FutureSongError('Future-song policy metadata must be an object')
        policy = {'policyVersion': editorial.POLICY_VERSION,
            'lyrics': {'alignmentVersion': ALIGNMENT_VERSION},
            'delivery': {'reviewEvidenceRetained': True},
            'privateReports': {'workflowOperationLedger': {
                'actions': list(WORKFLOW_OPERATION_ACTION_ORDER), 'outcomeCodes': sorted(OUTCOME_CODES)}}}
        return policy

    @staticmethod
    def _execution_policy_sha256(policy):
        processing = FutureSongWorkflow._effective_policy(policy)
        processing.pop('privateReports')
        return hashlib.sha256(json_bytes(processing)).hexdigest()

    def _policy_binding(self) -> dict[str, Any]:
        raw = read_bounded_regular_bytes(self.policy_dir/'lyrics-policy.json', max_bytes=MAX_JSON_BYTES, root=self.policy_dir)
        if self._execution_policy_sha256(parse_strict_json_bytes(raw)) != self.policy_sha256:
            raise FutureSongError('Active executable policy changed; start a fresh operation')
        for name, expected in self.contract_identity.items():
            path = self.workspace_root/'StemWise/contracts/enrichment'/name
            if hashlib.sha256(read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=self.workspace_root)).hexdigest() != expected:
                raise FutureSongError('Active production contract changed; start a fresh operation')
        return {'lyricsPolicySha256': self.policy_sha256,
            'schemaSha256': self.contract_identity['song-enrichment-v2.schema.json'],
            'installedSchemaSha256': self.contract_identity['installed-enrichment-v2.schema.json']}

    def _song_policy_matches(self, job: dict[str, Any]) -> bool:
        binding = self._policy_binding()
        policy = job.get('policy')
        if policy == binding:
            return True
        # Earlier jobs hashed action availability into unchanged processing authority.
        previous = self._effective_policy({})
        ledger = previous['privateReports']['workflowOperationLedger']
        ledger['outcomeCodes'] = sorted(('success', 'keyboard-interrupt', 'future-song-error',
            'recording-evidence-draft-unavailable', 'unexpected-error', 'authority-unresolved',
            'lyrics-content-unresolved', 'line-unresolved', 'retryable-technical', 'superseded-recording',
            'recording-not-selected', 'platform-content-restriction', 'artwork-unavailable',
            'review-stale', 'model-identity-mismatch', 'publication-conflict', 'bridge-package-too-large'))
        for actions in (('New', 'Source', 'Artwork', 'RecordingEvidence', 'Validate', 'Finalize', 'Retry'),
                        ('New', 'Source', 'Artwork', 'RecordingEvidence', 'Validate', 'Finalize', 'Retry', 'ReplaceAnalysis')):
            ledger['actions'] = list(actions)
            if policy == {**binding, 'lyricsPolicySha256': hashlib.sha256(json_bytes(previous)).hexdigest()}:
                return True
        return False

    def _job_dir(self, video_id: str) -> Path:
        return validate_generated_path(
            self.jobs,
            self.jobs / validate_video_id(video_id),
            workspace_root=self.workspace_root,
        )

    def _job_path(self, video_id: str) -> Path:
        return self._job_dir(video_id) / "job.json"

    def _owner_sidecar_path(self, video_id: str) -> Path:
        return validate_generated_path(
            self.owner,
            self.owner / f"{validate_video_id(video_id)}.lyrics.json",
            workspace_root=self.workspace_root,
        )

    def _require_platform_content_allowed(self, video_id: str) -> None:
        if self._job_path(video_id).exists():
            job = self._load_job(video_id, require_active_policy=False)
            if "platformContentRestriction" in job:
                raise FutureSongError("platform-content-restriction")


    def _load_job(self, video_id: str, *, require_active_policy: bool = True) -> dict[str, Any]:
        path = self._job_path(video_id)
        if not path.is_file():
            raise FutureSongError(f"No future-song job exists for {video_id}")
        job = read_json(path)
        if job.get("videoId") != video_id or job.get("policyVersion") != editorial.POLICY_VERSION:
            raise FutureSongError("job identity or policy version is invalid")
        if require_active_policy:
            if "platformContentRestriction" in job:
                raise FutureSongError("platform-content-restriction")
            if not self._song_policy_matches(job):
                raise FutureSongError('Job does not use the current executable policy contract; retained files were preserved')
        self.source.require_job(video_id, job)
        return job




    def _save_job(self, job: dict[str, Any]) -> None:
        from stemwise_engine.workflow_states import save_job
        save_job(self._job_path(job['videoId']), job)

    def _song_ledger(self, video_id):
        from stemwise_engine.alignment_attempt import SongAttemptLedger
        job = self._load_job(video_id, require_active_policy=False)
        ledger = SongAttemptLedger(self._job_dir(video_id)/'song-attempt.private.json',
            {'videoId': video_id, 'recordingInput': job.get('recordingInput'),
             'source': job.get('url')})
        return ledger

    def _alignment_state_path(self, video_id: str) -> Path:
        video_id = validate_video_id(video_id)
        alignment_jobs = self.root / "lyrics-alignment" / "jobs"
        return validate_generated_path(
            alignment_jobs,
            alignment_jobs / video_id / "state.json",
            workspace_root=self.workspace_root,
        )

    def _reviewed_lrc_evidence_path(self, video_id: str) -> Path:
        return self._alignment_state_path(video_id).with_name(
            "reviewed-lrc-starts.private.json"
        )

    def _display_review_attempt_dir(self, video_id: str, attempt_id: str) -> Path:
        video_id = validate_video_id(video_id)
        attempt_id = self._validate_alignment_attempt_id(attempt_id)
        job_dir = self._job_dir(video_id)
        root = validate_generated_path(
            job_dir,
            job_dir / "display-review",
            workspace_root=self.workspace_root,
        )
        return validate_generated_path(
            root,
            root / attempt_id,
            workspace_root=self.workspace_root,
        )

    def _endpoint_review_attempt_dir(self, video_id: str, attempt_id: str) -> Path:
        video_id = validate_video_id(video_id)
        attempt_id = self._validate_alignment_attempt_id(attempt_id)
        job_dir = self._job_dir(video_id)
        root = validate_generated_path(
            job_dir,
            job_dir / "endpoint-review",
            workspace_root=self.workspace_root,
        )
        return validate_generated_path(
            root,
            root / attempt_id,
            workspace_root=self.workspace_root,
        )

    def _lexical_mapping(self, video_id: str) -> tuple[dict[str, Any], dict[str, Any]]:
        """Load the text-free occurrence map and its exact durable identity."""

        from stemwise_engine.lyrics_alignment import lexical_mapping_identity

        video_id = validate_video_id(video_id)
        path = self._alignment_state_path(video_id).with_name("mapping.private.json")
        mapping = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=path.parent)
        if not isinstance(mapping, dict) or mapping.get("videoId") != video_id:
            raise FutureSongError("lyrics occurrence mapping identity is invalid")
        try:
            identity = lexical_mapping_identity(mapping)
        except (TypeError, ValueError) as error:
            raise FutureSongError("lyrics occurrence mapping identity is invalid") from error
        if mapping.get("mappingSha256") != identity["mappingSha256"]:
            raise FutureSongError("lyrics occurrence mapping content hash is stale")
        return mapping, {
            **identity,
            "mappingArtifactSha256": sha256_file(path),
        }

    def _write_reviewed_lrc_evidence(
        self,
        video_id: str,
        *,
        editorial_sha256: str,
        sidecar_sha256: str,
        evidence: dict[str, Any],
    ) -> None:
        starts = evidence.get("startsMs")
        files = evidence.get("sourceFiles")
        if not isinstance(starts, list) or not isinstance(files, list):
            raise FutureSongError("reviewed LRC evidence is malformed")
        atomic_write_json(
            self._reviewed_lrc_evidence_path(video_id),
            {
                "evidenceVersion": 1,
                "videoId": validate_video_id(video_id),
                "editorialSha256": editorial_sha256,
                "sidecarSha256": sidecar_sha256,
                "sourceFiles": files,
                "starts": [
                    {"sourcePhysicalIndex": index, "startMs": start_ms}
                    for index, start_ms in enumerate(starts)
                ],
            },
        )

    def _alignment_worker_lock_path(self, video_id: str) -> Path:
        video_id = validate_video_id(video_id)
        alignment_jobs = self.root / "lyrics-alignment" / "jobs"
        return validate_generated_path(
            alignment_jobs,
            alignment_jobs / video_id / "worker.lock",
            workspace_root=self.workspace_root,
        )

    @staticmethod
    def _validate_alignment_attempt_id(attempt_id: str) -> str:
        if not isinstance(attempt_id, str) or not ALIGNMENT_ATTEMPT_ID_PATTERN.fullmatch(
            attempt_id
        ):
            raise FutureSongError("alignment attempt identity is invalid")
        return attempt_id

    def _alignment_attempt_temp_dir(self, video_id: str, attempt_id: str) -> Path:
        video_id = validate_video_id(video_id)
        attempt_id = self._validate_alignment_attempt_id(attempt_id)
        future_root = self.analysis_temp / "lyrics-alignment" / "future"
        return validate_generated_path(
            future_root,
            future_root / video_id / attempt_id,
            workspace_root=self.workspace_root,
        )

    def _cleanup_alignment_attempt_temp(self, video_id: str, attempt_id: str) -> int:
        """Remove one exact, identity-marked future-song alignment attempt."""

        video_id = validate_video_id(video_id)
        attempt_id = self._validate_alignment_attempt_id(attempt_id)
        target = self._alignment_attempt_temp_dir(video_id, attempt_id)
        future_root = self.analysis_temp / "lyrics-alignment" / "future"
        try:
            removed = remove_manifested_directory(
                future_root,
                target,
                owner={
                    "kind": "lyrics-alignment-attempt",
                    "videoId": video_id,
                    "attemptId": attempt_id,
                },
                workspace_root=self.workspace_root,
            )
        except ValueError as error:
            raise FutureSongError(str(error)) from error
        return int(removed)

    def _mark_validation_failed_under_worker_lock(
        self, video_id: str, message: str
    ) -> dict[str, Any]:
        """Persist retryable failure while the caller owns the child worker lease."""

        video_id = validate_video_id(video_id)
        job = self._load_job(video_id)
        if job.get("state") == "VALIDATED" and self._validated_snapshot_is_current(job):
            self._write_task(job)
            return job
        attempt_id = job.get("activeAlignmentAttemptId")
        state_path = self._alignment_state_path(video_id)
        if state_path.is_file():
            state = read_json(state_path)
            if (
                state.get("status") in ACTIVE_ALIGNMENT_STATES
                and (attempt_id is None or state.get("attemptId") == attempt_id)
            ):
                state["status"] = "interrupted"
                state["reasons"] = ["worker-not-active"]
                state["updatedAtUnix"] = int(time.time())
                atomic_write_json(state_path, state)
        if isinstance(attempt_id, str):
            self._cleanup_alignment_attempt_temp(video_id, attempt_id)

        if job.get("state") in VALIDATION_FAILURE_ELIGIBLE_STATES:
            if isinstance(attempt_id, str):
                job["lastAlignmentAttemptId"] = attempt_id
            job.pop("activeAlignmentAttemptId", None)
            job["state"] = "VALIDATION_FAILED"
            job["error"] = " ".join(message.split())[:500]
            job.pop("validation", None)
            self._save_job(job)
            self._write_task(job)
        return job

    def _record_validation_failure(self, video_id: str, message: str) -> dict[str, Any]:
        """Persist failure only after proving no alignment child still owns the lease."""

        video_id = validate_video_id(video_id)
        try:
            with exclusive_process_lock(self._alignment_worker_lock_path(video_id)):
                return self._mark_validation_failed_under_worker_lock(video_id, message)
        except ProcessLockBusyError:
            return self._load_job(video_id)

    def _record_validation_outcome(
        self, video_id: str, outcome: FutureSongOutcomeError, *, source_stage: str | None = None
    ) -> dict[str, Any]:
        """Persist an exact recoverable outcome without copying private error text."""

        if source_stage not in (None, 'recording-evidence'):
            raise ValueError('Invalid content blocker source')
        video_id = validate_video_id(video_id)
        with exclusive_process_lock(self._alignment_worker_lock_path(video_id)):
            job = self._load_job(video_id)
            if job.get("state") in TERMINAL_STATES:
                return job
            attempt_id = job.get("activeAlignmentAttemptId")
            if isinstance(attempt_id, str):
                job["lastAlignmentAttemptId"] = attempt_id
            job.pop("activeAlignmentAttemptId", None)
            job["state"] = outcome.state
            job['outcome'] = {'code': outcome.code}
            if source_stage is not None:
                job['outcome']['sourceStage'] = source_stage
            if isinstance(outcome, (LyricsContentBlocker, ContentEvidenceUnavailable)):
                job["outcome"] = {"code": outcome.code, "reasons": list(outcome.reasons)}
                if source_stage is not None:
                    job['outcome']['sourceStage'] = source_stage
                if outcome.binding is not None:
                    job["outcome"]["contentBinding"] = outcome.binding
            job.pop("error", None)
            job.pop("validation", None)
            try:
                if isinstance(outcome, LyricsContentBlocker):
                    job["outcome"]["draftSha256"] = self._recording_draft_sha(job)
            finally:
                self._save_job(job)
            if isinstance(attempt_id, str):
                self._cleanup_alignment_attempt_temp(video_id, attempt_id)
            if outcome.code == "line-unresolved":
                try:
                    blocker = self.review_construction.endpoint_failure_source(job)
                except LyricsContentBlocker as error:
                    job["state"] = error.state
                    job["outcome"] = {"code": error.code, "reasons": list(error.reasons)}
                    if error.binding is not None:
                        job["outcome"]["contentBinding"] = error.binding
                    try:
                        job["outcome"]["draftSha256"] = self._recording_draft_sha(job)
                    finally:
                        self._save_job(job)
                    self._write_task(job)
                    raise
                except FutureSongError:
                    blocker = None
                if isinstance(blocker, dict) and blocker.get("reason") == (
                    "exhausted-endpoint-cascade"
                ):
                    self.review_construction.create_endpoint_review_attempt(job)
                    self._save_job(job)
                    self._write_task(job)
                    return job
            self._write_task(job)
            return job

    def _reconcile_interrupted_validation(self, job: dict[str, Any], *, own_workflow_lock=False) -> dict[str, Any]:
        """Repair interrupted work only after its parent lease is free."""

        from stemwise_engine.workflow_states import JobProgress, JobStage
        if JobProgress.current(job).rule.interrupted_state is None:
            return job
        video_id = validate_video_id(job["videoId"])
        lock_path = self._job_dir(video_id) / "workflow.lock"
        try:
            with (nullcontext() if own_workflow_lock and process_lock_descriptor(lock_path) is not None
                  else exclusive_process_lock(lock_path)):
                current = self._load_job(video_id)
                progress = JobProgress.current(current)
                if progress.rule.interrupted_state is None:
                    return current
                if progress.stage == JobStage.RECORDING_EVIDENCE:
                    current['recordingEvidence'] = {'status': 'failed', 'durationMs': current['recordingEvidence']['durationMs']}
                    current['state'] = progress.rule.interrupted_state.value
                    current['outcome'] = {'code': 'retryable-technical', 'sourceStage': progress.stage.value}
                    self._save_job(current)
                    self._write_task(current)
                    return current
                if progress.stage == JobStage.ANALYSIS:
                    current["state"] = progress.rule.interrupted_state.value
                    current["error"] = "worker-not-active"
                    self._save_job(current)
                    self._write_task(current)
                    return current
                if current.get("state") != "ALIGNING_LYRICS":
                    return current
                with exclusive_process_lock(self._alignment_worker_lock_path(video_id)):
                    current = self._load_job(video_id)
                    if current.get("state") != "ALIGNING_LYRICS":
                        return current
                    timestamps = [self._job_path(video_id).stat().st_mtime]
                    state_path = self._alignment_state_path(video_id)
                    if state_path.is_file():
                        timestamps.append(state_path.stat().st_mtime)
                    if time.time() - max(timestamps) < STALE_ALIGNMENT_GRACE_SECONDS:
                        return current
                    return self._mark_validation_failed_under_worker_lock(
                        video_id,
                        "validation was interrupted before completion; approved editorial text "
                        "was preserved; run Validate to resume",
                    )
        except ProcessLockBusyError:
            # The worker may commit and release the lock between our failed
            # acquisition and this return. Never project the pre-lock snapshot.
            return self._load_job(video_id)

    @staticmethod
    def _windows_path(path: Path) -> str:
        native = str(path.resolve())
        if os.name == "nt":
            return native
        try:
            completed = subprocess.run(
                ["wslpath", "-w", native],
                text=True,
                capture_output=True,
                check=False,
            )
        except FileNotFoundError:
            return native
        return completed.stdout.strip() if completed.returncode == 0 else native






















    @staticmethod
    def _workflow_operation_failure_code(error: BaseException) -> str:
        if isinstance(error, SupersededRecordingError):
            return "superseded-recording"
        if isinstance(error, FutureSongOutcomeError):
            return error.code
        endpoint_code = endpoint_review_error_code(error)
        if isinstance(error, EndpointReviewSelectionError) and endpoint_code:
            return endpoint_code
        from stemwise_engine.workflow_records import workflow_failure_details
        reason = workflow_failure_details(error)['reasonCode']
        if reason != 'future-song-error':
            return reason
        if isinstance(error, FutureSongError):
            if str(error) in {"recording-evidence-draft-unavailable", "recording-not-selected",
                              "platform-content-restriction"}:
                return str(error)
            return "future-song-error"
        return "unexpected-error"




    def _require_selected_audio(self, video_id: str) -> None:
        self.source.require_selected(video_id, self.pc_side_root / 'qobuz-comparison')

    def _run_mutating_operation(self, video_id, action, callback):
        video_id = validate_video_id(video_id)
        lock = self._job_dir(video_id) / 'workflow.lock'
        with (nullcontext() if process_lock_descriptor(lock) is not None else exclusive_process_lock(lock)):
            return self._run_owned_mutating_operation(video_id, action, callback)

    def _run_owned_mutating_operation(self, video_id, action, callback):
        from stemwise_engine.telemetry_retention import publication
        from stemwise_engine.workflow_states import JobProgress
        video_id = validate_video_id(video_id)
        self._require_platform_content_allowed(video_id)
        self._recover_capability_validation(video_id)
        if self._job_path(video_id).is_file():
            job = self._load_job(video_id, require_active_policy=action != 'ReplaceAnalysis')
            from stemwise_engine.job_ownership import inspect_analysis_replacement
            job, replacement_pending = inspect_analysis_replacement(self, job)
            if replacement_pending and action not in {'Retry', 'ReplaceAnalysis'}:
                raise FutureSongError('Unfinished analysis replacement requires Retry before editorial changes')
            ledger = self._song_ledger(video_id)
            if action in {'Retry', 'Validate', 'RecordingEvidence'}:
                with exclusive_process_lock(self._alignment_worker_lock_path(video_id)):
                    job_dir = self._job_dir(video_id)
                    from stemwise_engine.lyric_content import OWNER_VERIFICATION_FILE
                    inputs = [job_dir/'sources.json', job_dir/'agent-review.json', job_dir/OWNER_VERIFICATION_FILE]
                    draft = job_dir/'drafts'
                    if draft.is_dir():
                        inputs.extend(draft/name for name in ROLE_FILES if (draft/name).is_file())
                    from stemwise_engine.spoken_targets import input_snapshot
                    ledger.restart({'recording': job.get('recordingInput', job.get('url')),
                        'analysisReplacement': job.get('analysisReplacement'),
                        'configuration': sha256_file(self.config_path),
                        'spokenTargets': input_snapshot(job_dir),
                        'inputs': {str(path.relative_to(job_dir)): sha256_file(path)
                                   for path in inputs if path.is_file()}})
            completing = (action == 'Validate' or action == 'Finalize' and (
                self._validated_snapshot_is_current(job, allow_finalization_pointer=True)
                or self._validated_snapshot_is_current(job, require_delivery=True,
                    allow_pending_delivery_cleanup=True)))
            if ledger.blocking_disposition and not completing:
                raise FutureSongError(ledger.blocking_disposition['code'])
            if action == 'Retry':
                job = self._reconcile_interrupted_validation(job, own_workflow_lock=True)
            progress = JobProgress.current({'state': 'ANALYSIS_REPLACEMENT_PENDING'} if replacement_pending else job)
            if action not in progress.rule.actions and not (action == 'ReplaceAnalysis' and job.get('analysisReplacement')):
                raise FutureSongError(f'{action} is not permitted during {progress.stage.value}/{progress.outcome.value}')
        if action in {"New", "Validate", "Finalize", "Retry", "ReplaceAnalysis"}:
            self._require_selected_audio(video_id)
        with publication(self.workspace_root, self.report_store.report_root(video_id)):
            if self._job_path(video_id).is_file():
                ledger = self._song_ledger(video_id)
                with ledger.active(finalization=action in {'Validate', 'Finalize'}):
                    return self._run_mutating_operation_under_telemetry(video_id, action, callback)
            return self._run_mutating_operation_under_telemetry(video_id, action, callback)

    def _run_mutating_operation_under_telemetry(
        self,
        video_id: str,
        action: str,
        callback: Callable[[], Any],
    ) -> Any:
        """Write start/outcome records around one top-level mutating action."""

        operation_id = os.environ.get("STEMWISE_LAUNCH_TOKEN")
        if operation_id is None:
            operation_id = uuid.uuid4().hex
        operation_id = self._validate_alignment_attempt_id(operation_id)
        started_at = int(time.time())
        try:
            state_before = self.report_store.workflow_operation_state(video_id)
            bindings = self.report_store.workflow_operation_bindings()
        except Exception:
            print('workflow-operation-report-failed', file=sys.stderr)
            return callback()
        report_failed = False
        def record(**values):
            nonlocal report_failed
            try:
                if values['phase'] == 'outcome':
                    values['state_after'] = self.report_store.workflow_operation_state(video_id)
                self.report_store.commit_workflow_operation_record(video_id, **values)
            except Exception:
                if not report_failed:
                    print('workflow-operation-report-failed', file=sys.stderr)
                report_failed = True
        record(
            operation_id=operation_id,
            action=action,
            phase="start",
            status="started",
            started_at=started_at,
            state_before=state_before,
            bindings=bindings,
        )
        try:
            result = callback()
        except (KeyboardInterrupt, Exception) as error:
            interrupted = isinstance(error, KeyboardInterrupt)
            from stemwise_engine.workflow_records import workflow_failure_details
            from stemwise_engine.provider_operations import provider_failure_cause
            provider_error = None if interrupted else provider_failure_cause(error)
            diagnostics = None
            if provider_error is not None:
                from stemwise_engine.private_diagnostics import retain_error
                try:
                    report_root = self.report_store.ensure_report_identity(video_id)
                    diagnostics = [retain_error(report_root/f'provider-{operation_id}.private.log', error,
                        context={'videoId': video_id, 'operationId': operation_id, 'action': action,
                                 'providerStage': getattr(provider_error, 'provider_stage', 'unknown')})]
                except (OSError, ValueError):
                    print('provider-private-diagnostic-retention-failed', file=sys.stderr)
            record(
                    operation_id=operation_id, action=action, phase="outcome",
                    status="interrupted" if interrupted else "failed", started_at=started_at,
                    completed_at=int(time.time()), state_before=state_before,
                    outcome_code=WorkflowOutcome.INTERRUPTED.value if interrupted else self._workflow_operation_failure_code(error),
                    bindings=bindings,
                    failure_details=None if interrupted else workflow_failure_details(error),
                    private_diagnostics=diagnostics,
                )
            raise
        record(
            operation_id=operation_id,
            action=action,
            phase="outcome",
            status="complete",
            started_at=started_at,
            completed_at=int(time.time()),
            state_before=state_before,
            outcome_code="success",
            bindings=bindings,
        )
        return result










    def sylla_candidate(self, video_id: str, *, shadow: bool = False) -> dict[str, Any]:
        """Retain an explicit private timing observation without changing song authority."""
        if shadow is not True:
            raise FutureSongError('SyllaCandidate is disabled without explicit shadow opt-in')
        from stemwise_engine.workflow_states import JobProgress, JobStage
        from stemwise_engine.sylla_candidate import collect
        video_id = validate_video_id(video_id)
        self._require_selected_audio(video_id)
        self._require_platform_content_allowed(video_id)
        lock = self._job_dir(video_id)/'workflow.lock'
        with nullcontext() if process_lock_descriptor(lock) is not None else exclusive_process_lock(lock):
            job = self._load_job(video_id)
            progress = JobProgress.current(job)
            if (progress.rule.interrupted_state is not None or progress.stage == JobStage.ANALYSIS
                    or job['state'] in {'VALIDATED', 'DELIVERED'} or job.get('analysisReplacement')):
                raise FutureSongError('SyllaCandidate requires an idle unfinished song with admitted content')
            self._require_changed_lyrics_content(job)
            with exclusive_process_lock(self._alignment_worker_lock_path(video_id)):
                return collect(self, job)

    def recording_evidence(self, video_id: str) -> dict[str, Any]:
        """Retain the current attempt outcome without approving editorial content."""
        video_id = validate_video_id(video_id)
        self._require_selected_audio(video_id)
        return self._run_mutating_operation(video_id, 'RecordingEvidence',
                                            lambda: self._run_recording_evidence(video_id))

    def _run_recording_evidence(self, video_id):
        started = None
        def on_start(job):
            nonlocal started
            started = time.monotonic()
            job['recordingEvidence'] = {'status': 'running', 'durationMs': 0}
            self._save_job(job)
        status = 'failed'
        try:
            result = self._recording_evidence_action(video_id, on_start=on_start)
            status = 'complete'
        finally:
            if started is not None:
                pending = sys.exc_info()[1]
                try:
                    job = self._load_job(video_id)
                    job['recordingEvidence'] = {'status': status,
                        'durationMs': max(0, round((time.monotonic() - started) * 1000))}
                    if status == 'complete':
                        job.pop('recordingEvidenceDiagnostics', None)
                    self._save_job(job)
                except Exception as error:
                    from stemwise_engine.common import raise_with_cleanup_failure
                    print('recording-evidence-report-failed', file=sys.stderr)
                    raise_with_cleanup_failure(pending, error)
        if started is not None:
            job = self._load_job(video_id)
            self._write_task(job)
            return {**result, **self.summary(job)}
        return result

    def _propose_audio_lyrics(self, job, *, on_start):
        from stemwise_engine.recording_evidence import stage_audio_candidate
        from stemwise_engine.worker_protocol import require_worker_success
        video_id = job['videoId']
        directory = self._job_dir(video_id)
        audio = self._completed_analysis_audio(self._pipeline(), video_id)
        evidence_root = self._alignment_state_path(video_id).parent
        on_start(job)
        existing = job.get('audioFirstCandidate')
        if existing is not None:
            if (not isinstance(existing, dict) or existing.get('audioSha256') != audio['sha256']
                    or not isinstance(existing.get('candidate'), dict)):
                raise FutureSongError('audio-first-candidate-binding-changed')
            raw = read_bounded_regular_bytes(directory/existing['candidate']['path'],
                max_bytes=MAX_JSON_BYTES, root=directory)
            if hashlib.sha256(raw).hexdigest() != existing['candidate']['sha256']:
                raise FutureSongError('audio-first-candidate-changed')
            reference = existing['recognition']
        else:
            command = [str(environment_python('lyrics', Path(self.config['projectDir']))),
                '-m', 'stemwise_engine.recording_evidence', '--candidate',
                str(self.config_path), str(self.root), video_id, str(audio['path']), audio['sha256']]
            completed = self._run_primary_worker('recording-candidate',
                {'audioSha256': audio['sha256']}, command, capture_output=True, text=True,
                check=False, timeout=int(self.config.get('analysisTimeoutMinutes', 180))*60,
                env=self._alignment_worker_environment())
            require_worker_success(completed, code='recording-candidate-failed',
                diagnostic_path=directory/'recording-candidate.failure.private.txt')
            reference = parse_strict_json_bytes(completed.stdout.encode('utf-8'))['recognition']
        proposal = stage_audio_candidate(directory, evidence_root, video_id, audio, reference)
        if existing is not None and existing != proposal:
            raise FutureSongError('audio-first-candidate-binding-changed')
        if self._completed_analysis_audio(self._pipeline(), video_id)['sha256'] != audio['sha256']:
            raise FutureSongError('audio-first-candidate-recording-changed')
        job['audioFirstCandidate'] = proposal
        if (job.get('state') == 'RETRYABLE_TECHNICAL'
                and (job.get('outcome') or {}).get('sourceStage') == 'recording-evidence'):
            job.pop('outcome')
            job['state'] = 'NEEDS_LYRICS'
        self._save_job(job)
        self._write_task(job)
        return {**self.summary(job), 'reviewRequired': True}

    def _recording_evidence_action(self, video_id: str, *, on_start: Callable[[dict[str, Any]], None]) -> dict[str, Any]:
        """Unprompted recording comparison before authority approval; never publish."""
        self._require_selected_audio(validate_video_id(video_id))
        job = self._load_job(validate_video_id(video_id))
        recovery_only = False
        try:
            self._require_changed_lyrics_content(job, for_recording_evidence=True)
        except LyricsContentBlocker:
            recovery_only = True
        from stemwise_engine.workflow_states import JobProgress
        if 'RecordingEvidence' not in JobProgress.current(job).rule.actions:
            raise FutureSongError("recording evidence requires pending editorial review")
        if job.get("state") == "VALIDATION_FAILED":
            markers = (
                "activeAlignmentAttemptId",
                "lastAlignmentAttemptId",
                "displayReview",
                "endpointReview",
                "validation",
                "delivery",
                "outcome",
            )
            state_path = self._alignment_state_path(video_id)
            if (
                any(marker in job for marker in markers)
                or state_path.exists()
                or state_path.is_symlink()
            ):
                raise FutureSongError("recording evidence requires pending editorial review")
        job_dir = self._job_dir(video_id)
        if not any((job_dir/'drafts'/name).exists() or (job_dir/'drafts'/name).is_symlink()
                   for name in ('original.txt', 'original.lrc')):
            return self._propose_audio_lyrics(job, on_start=on_start)
        try:
            draft = read_role_text(job_dir / 'drafts', 'original')
        except (OSError, ValueError, UnicodeError) as error:
            raise FutureSongError("recording-evidence-draft-unavailable") from error
        indices = [i for i, line in enumerate(draft.splitlines()) if line.strip()]
        if not indices:
            raise FutureSongError("recording-evidence-draft-unavailable")
        self._preflight_drafts(job, roles=frozenset({'original'}))
        inputs = self._review_inputs(job, original_only=True)
        environment = self._alignment_worker_environment()
        diagnostic = job_dir / 'recording-evidence-worker.failure.private.txt'
        audio = self._completed_analysis_audio(self._pipeline(), video_id)
        project = Path(self.config["projectDir"])
        command = [str(environment_python('lyrics', project)), "-m", "stemwise_engine.recording_evidence",
                   *(['--recover-content'] if recovery_only else []),
                   str(self.config_path), str(self.root), video_id, str(audio["path"]), audio["sha256"]]
        completed = None
        content_limited = False
        on_start(job)
        try:
            completed = self._run_primary_worker('recording-evidence',
                {'audioSha256': audio['sha256'], 'reviewInputs': inputs}, command,
                capture_output=True, text=True, check=False,
                timeout=int(self.config.get('analysisTimeoutMinutes', 180))*60,
                env=environment,
            )
            from stemwise_engine.worker_protocol import raise_if_worker_stopped
            raise_if_worker_stopped(completed, code='recording-evidence-worker-failed', diagnostic_path=diagnostic)
            comparison = json.loads(completed.stdout) if completed.stdout.strip() else None
            if isinstance(comparison, dict) and comparison.get('status') == 'content-evidence-unavailable':
                reasons = workflow_diagnostics._diagnostic_codes(comparison.get('reasons'))
                raise ContentEvidenceUnavailable(reasons[0] if len(reasons) == 1 else 'content-evidence-invalid')
            recovered = isinstance(comparison, dict) and comparison.get('status') == 'content-recovery-complete'
            if recovered != recovery_only or recovery_only and (
                    completed.returncode or set(comparison) != {'status', 'recordingInputs', 'recoveryCandidates'}):
                raise FutureSongError('recording diagnostic response is invalid; pending review is unchanged')
            if isinstance(comparison, dict) and (comparison.get("status") == "lyrics-content-unresolved" or recovered):
                if inputs != self._review_inputs(job, original_only=True):
                    raise FutureSongError("recording review inputs changed during recognition")
                if recovered and self._completed_analysis_audio(self._pipeline(), video_id) != audio:
                    raise FutureSongError('recording evidence input identity changed')
                references = replacement = None
                if 'recordingInputs' in comparison:
                    from stemwise_engine.lyric_content import validate_recording_review_inputs
                    references = validate_recording_review_inputs(self._job_dir(video_id), self._alignment_state_path(video_id).parent,
                                                               comparison['recordingInputs'])
                    if references['audioSha256'] != audio['sha256']:
                        raise FutureSongError('recording evidence input identity changed')
                if 'recoveryCandidates' in comparison:
                    from stemwise_engine.workflow_status import validate_recovery_candidates
                    replacement = validate_recovery_candidates(job_dir,
                        self._alignment_state_path(video_id).parent, comparison['recoveryCandidates'],
                        video_id=video_id, audio_sha=audio['sha256'], duration_ms=audio['durationMs'])
                    if recovered and replacement['primaryRecognition'] != references['recognition']:
                        raise FutureSongError('recording diagnostic primary recognition changed')
                if recovered:
                    job = self._load_job(video_id)
                    if inputs != self._review_inputs(job, original_only=True):
                        raise FutureSongError("recording review inputs changed during recognition")
                    if self._completed_analysis_audio(self._pipeline(), video_id) != audio:
                        raise FutureSongError('recording evidence input identity changed')
                if references is not None:
                    job['recordingEvidenceInputs'] = references
                    if not recovered:
                        self._save_job(job)
                if 'contentInvestigation' in comparison:
                    job['contentInvestigation'] = comparison['contentInvestigation']
                    self._save_job(job)
                if replacement is not None:
                    job['recordingRecoveryCandidates'] = replacement
                    previous_failure = job.get('recordingRecoveryFailure')
                    if (isinstance(previous_failure, dict) and all(previous_failure.get(key) == replacement[key]
                            for key in ('audioSha256', 'durationMs', 'draftSha256', 'metadataSha256'))
                            and (previous_failure.get('primaryRecognition') is None
                                or isinstance(previous_failure['primaryRecognition'], dict)
                                and previous_failure['primaryRecognition'].get('sha256') == replacement['primaryRecognition']['sha256'])):
                        job.pop('recordingRecoveryFailure')
                    if not recovered:
                        self._save_job(job)
                if recovered:
                    self._save_job(job)
                if 'recoveryDiagnostic' in comparison:
                    codes = workflow_diagnostics._diagnostic_codes([comparison['recoveryDiagnostic']])
                    comparison['recoveryDiagnostic'] = codes[0] if codes else 'optional-content-recovery-failed'
                    from stemwise_engine.lyrics_intake import read_recording_draft
                    _, draft_sha, _, metadata_sha = read_recording_draft(job_dir)
                    from stemwise_engine.private_diagnostics import validate_references
                    private_diagnostics = comparison.get('recoveryPrivateDiagnostics', [])
                    try:
                        private_diagnostics = validate_references(private_diagnostics) if private_diagnostics else []
                    except ValueError:
                        private_diagnostics = []
                    comparison['recoveryPrivateDiagnostics'] = private_diagnostics
                    job['recordingRecoveryFailure'] = {'formatVersion': 1, 'videoId': video_id,
                        'stage': 'independent-content-recognition', 'status': 'retryable-technical',
                        'code': comparison['recoveryDiagnostic'], 'audioSha256': audio['sha256'],
                        'durationMs': audio['durationMs'], 'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                        'primaryRecognition': comparison.get('recordingInputs', {}).get('recognition'),
                        'privateDiagnostics': private_diagnostics}
                    self._save_job(job)
                if not recovered:
                    job['lyricContentLimitation'] = {'reasons': workflow_diagnostics._diagnostic_codes(comparison.get('reasons')),
                        'scope': comparison.get('discrepancyScope')}
                    self._save_job(job)
                content_limited = True
            if completed.returncode and not content_limited:
                raise FutureSongError(f"recording recognizer failed (exit {completed.returncode}); pending review is unchanged")
            if not isinstance(comparison, dict):
                raise FutureSongError("recording recognizer result is invalid; pending review is unchanged")
        except WorkerRetirementError:
            raise
        except LyricsContentBlocker as error:
            if not recovery_only:
                self._record_validation_outcome(video_id, error, source_stage='recording-evidence')
            raise
        except ContentEvidenceUnavailable as error:
            if not recovery_only:
                self._record_validation_outcome(video_id, error, source_stage='recording-evidence')
            raise
        except Exception as error:
            from stemwise_engine.provider_operations import raise_if_operation_stopped
            raise_if_operation_stopped(error)
            stderr = getattr(completed, 'stderr', None) or getattr(error, 'stderr', None)
            if isinstance(stderr, str):
                stderr = stderr.encode('utf-8', errors='replace')
            cause = traceback.format_exc().encode('utf-8')
            if isinstance(stderr, bytes):
                cause += b'\nChild stderr:\n' + stderr
            from stemwise_engine.private_diagnostics import retain, reference
            try:
                retained, identity = retain(diagnostic, cause)
                if recovery_only:
                    job = self._load_job(video_id)
                job['recordingEvidenceDiagnostics'] = [reference(retained, identity)]
                self._save_job(job)
            except Exception as diagnostic_error:
                from stemwise_engine.common import raise_with_cleanup_failure
                raise_with_cleanup_failure(error, diagnostic_error)
            raise
        if inputs != self._review_inputs(job, original_only=True):
            raise FutureSongError("recording review inputs changed during recognition")
        if recovery_only:
            return {"videoId": video_id, "state": job["state"], "reviewRequired": True,
                    "reviewInputs": inputs, **comparison}
        if (job.get('state') == 'RETRYABLE_TECHNICAL'
                and (job.get('outcome') or {}).get('sourceStage') == 'recording-evidence'):
            job.pop('outcome')
            job['state'] = 'WAITING_FOR_EDITORIAL'
            self._save_job(job)
        if 'contentResolution' in comparison:
            from stemwise_engine.recording_evidence import adjudicate_retained_recording
            resolution = adjudicate_retained_recording(self.config, self.root, video_id, audio)
            if comparison['contentResolution'] != resolution:
                raise FutureSongError('recording adjudication result changed')
            # This decision resolves only this recognizer's pre-editorial blocker.
            # It neither approves lyrics nor supplies subsequent timing authority.
            job = self._load_job(video_id)
            outcome = job.get('outcome')
            if (isinstance(outcome, dict) and outcome.get('code') == 'lyrics-content-unresolved'
                    and outcome.get('sourceStage') == 'recording-evidence'):
                self._require_changed_lyrics_content(job, for_recording_evidence=True)
                job.pop('outcome')
                job['state'] = 'WAITING_FOR_EDITORIAL'
                self._save_job(job)
        if not content_limited:
            from stemwise_engine.lyrics_intake import read_recording_draft
            _, draft_sha, _, metadata_sha = read_recording_draft(job_dir)
            evidence_root = self._alignment_state_path(video_id).parent
            evidence_path = validate_generated_path(evidence_root, Path(comparison['privateTranscript']),
                workspace_root=self.workspace_root)
            raw = read_bounded_regular_bytes(evidence_path, max_bytes=MAX_JSON_BYTES, root=evidence_root)
            if 'sourceRecordingDecision' in comparison:
                from stemwise_engine.source_recording_content import current_admission
                lyrics, _, _ = self._preflight_drafts(job, roles=frozenset({'original'}))
                original = next(track for track in lyrics['tracks'] if track['kind'] == 'original')
                current_admission(comparison['sourceRecordingDecision'], job_dir=job_dir,
                    evidence_root=evidence_root, video_id=video_id, original=original,
                    indices=tuple(i for i, line in enumerate(original['text'].splitlines()) if line.strip()),
                    audio_sha=audio['sha256'], duration_ms=audio['durationMs'])
                if comparison['sourceRecordingDecision'] != {
                        'path': evidence_path.relative_to(evidence_root).as_posix(),
                        'sha256': hashlib.sha256(raw).hexdigest()}:
                    raise FutureSongError('source recording decision reference changed')
            job['recordingContentEvidence'] = {'audioSha256': audio['sha256'], 'draftSha256': draft_sha,
                'metadataSha256': metadata_sha, 'evidence': {'path': evidence_path.relative_to(evidence_root).as_posix(),
                    'sha256': hashlib.sha256(raw).hexdigest()}}
            job.pop('lyricContentLimitation', None)
            outcome = job.get('outcome')
            if ('sourceRecordingDecision' in comparison and isinstance(outcome, dict)
                    and outcome.get('code') == 'lyrics-content-unresolved'
                    and outcome.get('reasons') == ['content-sequence-uncertain']):
                job.pop('outcome')
                job['state'] = 'WAITING_FOR_EDITORIAL'
            self._save_job(job)
        else:
            self._recover_lyrics_stage(job, lambda: self.acquisition(video_id).continue_after_content_rejection(job, {
                'audioSha256': audio['sha256'], 'draftSha256': inputs['draftSha256'],
                'originalMetadataSha256': inputs['originalMetadataSha256'],
                'recognition': comparison.get('recordingInputs', {}).get('recognition'),
                'limitation': job['lyricContentLimitation']}))
        return {
            "videoId": video_id, "state": job["state"], "reviewRequired": True,
            "reviewInputs": self._review_inputs(job, original_only=True), **comparison,
        }

    @staticmethod
    def _has_pending_endpoint_review(job):
        pointer = job.get('endpointReview')
        return isinstance(pointer, dict) and pointer.get('result') != 'accepted'

    def endpoint_evidence(self, video_id: str, *, vocal: bool = False) -> dict[str, Any]:
        """Prepare timing evidence, or persist a content blocker before recovery."""
        self._require_selected_audio(validate_video_id(video_id))
        job = self._load_job(validate_video_id(video_id))
        if not self._has_pending_endpoint_review(job):
            raise FutureSongError("endpoint evidence requires a pending endpoint review")
        if job.get('state') == 'DELIVERED':
            if (job.get('validation', {}).get('kind') != 'capability-completion'
                    or not self._validated_snapshot_is_current(job, require_delivery=True)):
                raise FutureSongError('delivered snapshot is not intact; endpoint evidence was not changed')
            self._completed_analysis_audio(self._pipeline(), video_id)
        elif job.get('state') in TERMINAL_STATES:
            raise FutureSongError("endpoint evidence requires a pending endpoint review")
        try:
            with self._song_ledger(video_id).active():
                return self._validate_endpoint_review(job, evidence_only=True, vocal=vocal)
        except Exception as failure:
            # Keep one bounded private failure, never lyric details in CLI output.
            path = self._alignment_state_path(video_id).with_name("endpoint.failure.private.txt")
            try:
                from stemwise_engine.private_diagnostics import bounded_utf8_tail
                atomic_write_bytes(path, bounded_utf8_tail(
                    traceback.format_exc().encode('utf-8', errors='replace')), root=path.parent)
            except Exception as diagnostic_error:
                from stemwise_engine.common import raise_with_cleanup_failure
                raise_with_cleanup_failure(failure, diagnostic_error)
            raise

    def _recover_endpoint_packet(self, job, args):
        """Reuse verified work, then run the existing finite recovery sequence once."""
        from stemwise_engine.endpoint_dispatch import evidence_adapter
        from importlib import import_module
        from stemwise_engine.alignment_attempt import SongAttemptBlocked
        from stemwise_engine.endpoint_ctc_evidence import require_endpoint_content, needs_timing_recovery
        from stemwise_engine.provenance import endpoint_producer_identity
        from stemwise_engine.common import heavy_worker_stage
        from stemwise_engine.worker_protocol import WorkerProtocolError
        from subprocess import TimeoutExpired
        attempt, declaration, declaration_sha, audio, config, mapping, evidence, canonical = args
        self._completed_analysis_audio(self._pipeline(), job['videoId'])
        admission = require_endpoint_content(declaration, mapping, evidence,
            read_bounded_json(canonical, max_bytes=MAX_JSON_BYTES, root=canonical.parent),
            self._alignment_state_path(job['videoId']).parent)
        names = ('endpoint_ctc_evidence', 'endpoint_repeat_alignment',
                 'endpoint_candidate_prediction', 'endpoint_native_ctc', 'endpoint_owsm_ctc')
        packets = {}
        for name in names:
            module = import_module('stemwise_engine.' + name)
            path = module._directory(attempt)/'manifest.json'
            if not path.is_file():
                continue
            binding = {'method': module.METHOD, 'manifestSha256': sha256_file(path)}
            try:
                manifest = evidence_adapter(module.METHOD).verify(attempt, declaration, declaration_sha,
                    audio, binding, config=config, mapping=mapping, evidence=evidence, canonical=canonical)
            except LyricsContentBlocker:
                raise
            except (OSError, ValueError, KeyError, TypeError):
                continue
            packet = {**binding, 'occurrences': manifest['occurrences'], 'reviewRequired': True}
            packets[name] = packet
            if not needs_timing_recovery(packet, admission):
                return packet
        ledger, packet = self._song_ledger(job['videoId']), None
        for index, name in enumerate(names):
            if packet is not None and not needs_timing_recovery(packet, admission):
                break
            if name in packets:
                packet = packets[name]
                continue
            module = import_module('stemwise_engine.' + name)
            request = {'operation': name, 'declarationSha256': declaration_sha,
                'audioSha256': sha256_file(audio), 'precedingManifestSha256':
                    packet.get('manifestSha256') if packet is not None else None,
                'producerSha256': hashlib.sha256(json_bytes(endpoint_producer_identity())).hexdigest(), 'configurationSha256':
                    hashlib.sha256(json_bytes(config)).hexdigest()}
            action = {'requestId': hashlib.sha256(json_bytes(request)).hexdigest(),
                'request': request, 'regionId': attempt.name, 'kind': 'timing'}
            ledger.reserve([action])
            previous = ledger.record['actions'][action['requestId']]
            if previous['status'] == 'failed':
                if packet is None:
                    raise SongAttemptBlocked('failed-recovery-evidence-unavailable')
                continue
            if (previous['status'] == 'complete' and packet is not None
                    and previous.get('result') == {key: packet[key] for key in ('method', 'manifestSha256')}):
                continue
            if previous['status'] != 'reserved':
                raise SongAttemptBlocked('accepted-recovery-evidence-unavailable')
            ledger.begin(action['requestId'])
            try:
                with heavy_worker_stage(config) if index == 0 else nullcontext():
                    packet = module.write_packet(*args) if index == 0 else module.write_packet(*args, packet)
            except (WorkerProtocolError, TimeoutExpired) as error:
                ledger.fail(action['requestId'], error)
                if packet is None:
                    raise
                continue
            ledger.complete(action['requestId'], result={key: packet[key] for key in ('method', 'manifestSha256')})
        return packet


    @staticmethod
    def _review_operations() -> ReviewOperations:
        return ReviewOperations(read_child, editorial.validate_plain_draft, validate_synced_companion_timing, editorial.valid_reviewer)

    @staticmethod
    def _review_policy() -> ReviewPolicy:
        return ReviewPolicy(editorial.POLICY_VERSION, frozenset(TERMINAL_STATES), review_contract.ENDPOINT_REVIEW_VERSION,
            review_contract.ENDPOINT_REVIEW_POLICY, review_contract.DISPLAY_REVIEW_VERSION, review_contract.DISPLAY_REVIEW_POLICY)

    def _endpoint_review_context(self):
        from stemwise_engine.workflow_review import EndpointReviewContext
        return EndpointReviewContext(
            root=self.root, workspace_root=self.workspace_root, owner=self.owner, runtime=self.runtime,
            ready=self.ready, analysis_temp=self.analysis_temp, config=dict(self.config), report_store=self.report_store,
            alignment_state_path=self._alignment_state_path,
            completed_analysis_audio=self._completed_analysis_audio,
            editorial_sha=self._editorial_sha,
            endpoint_review_attempt_dir=self._endpoint_review_attempt_dir,
            endpoint_review_occurrences=self.review_construction.endpoint_review_occurrences,
            endpoint_review_template=self.review_construction.endpoint_review_template,
            failed_endpoint_diagnostics=self.review_construction.failed_endpoint_diagnostics,
            job_dir=self._job_dir,
            lexical_mapping=self._lexical_mapping,
            owner_sidecar_path=self._owner_sidecar_path,
            pipeline=self._pipeline,
            recover_endpoint_packet=self._recover_endpoint_packet,
            validate_alignment_attempt_id=self._validate_alignment_attempt_id)

    def _snapshot_context(self):
        from stemwise_engine.workflow_review import ValidatedSnapshotContext
        return ValidatedSnapshotContext(
            root=self.root, workspace_root=self.workspace_root, owner=self.owner, runtime=self.runtime,
            ready=self.ready, analysis_temp=self.analysis_temp, config=dict(self.config), report_store=self.report_store,
            alignment_state_path=self._alignment_state_path,
            capability_snapshot_current=self._capability_snapshot_current,
            companion_projection_current=partial(self._companion_projection_current, require_current_original=True),
            completed_analysis_audio=self._completed_analysis_audio,
            display_review_attempt_dir=self._display_review_attempt_dir,
            editorial_sha=self._editorial_sha,
            endpoint_review_attempt_dir=self._endpoint_review_attempt_dir,
            finalization_checkpoint_requirements=self.report_store.finalization_checkpoint_requirements,
            job_dir=self._job_dir,
            lexical_mapping=self._lexical_mapping,
            owner_sidecar_path=self._owner_sidecar_path,
            pipeline=self._pipeline,
            runtime_config=self._runtime_config,
            validate_alignment_attempt_id=self._validate_alignment_attempt_id,
            validated_artwork_repair=self._validated_artwork_repair,
            windows_path=self._windows_path)

    def _validate_endpoint_review(self, job: dict[str, Any], *, evidence_only: bool = False, vocal: bool = False,
                                  analysis_audio=None) -> dict[str, Any]:
        context = self._endpoint_review_context()
        if analysis_audio is not None:
            from dataclasses import replace
            context = replace(context, completed_analysis_audio=lambda *_: analysis_audio)
        try:
            self._require_changed_lyrics_content(job)
            result = EndpointReviewService(context, self._review_operations(), self._review_policy()).validate(
                EndpointReviewRequest(job=job, evidence_only=evidence_only, vocal=vocal))
        except LyricsContentBlocker as error:
            self._record_validation_outcome(job["videoId"], error)
            raise
        if not isinstance(result, ReviewCompleted):
            return result.evidence
        if result.refresh_task:
            self._write_task(result.job)
        if result.publication is not None:
            publication = result.publication
            review_path = self._endpoint_review_attempt_dir(job['videoId'], publication['attemptId'])/'review.json'
            if sha256_file(review_path) != publication['reviewSha256']:
                raise EndpointReviewSelectionError('endpoint-review-decision-changed')
            run_sha = self.report_store.commit_alignment_run(result.job, run_id=publication['attemptId'],
                editorial_sha256=publication['editorialSha256'], state=publication['alignmentState'],
                status='complete', artifacts=publication['alignmentArtifacts'])
            result.job['endpointReview'].update(result='accepted', reviewSha256=publication['reviewSha256'],
                alignmentRunId=publication['attemptId'], alignmentRunSha256=run_sha)
            self.review_construction.create_display_review_attempt(result.job, original_text=publication['originalText'],
                editorial_sha256=publication['editorialSha256'], analysis_audio=publication['analysisAudio'],
                alignment_state=publication['alignmentState'], alignment_run_sha256=run_sha,
                alignment_artifacts=publication['alignmentArtifacts'])
            self._save_job(result.job)
            self._write_task(result.job)
        return self.summary(result.job)


















    def _task_payload(self, job: dict[str, Any], payload: bytes) -> bytes:
        owner_inputs = self._owner_verification_inputs(job)
        if owner_inputs is not None:
            payload += ('\nOwner verification inputs (pending; only explicit owner confirmation approves):\n' +
                json.dumps(owner_inputs, ensure_ascii=False, sort_keys=True) + '\n').encode('utf-8')
        inputs = self._recording_evidence_inputs(job)
        if inputs is not None:
            payload += ('\nRecording evidence inputs (identities only; no content approval):\n' +
                        json.dumps(inputs, ensure_ascii=False, sort_keys=True) + '\n').encode('utf-8')
        from stemwise_engine.workflow_status import recovery_candidates_readback, recovery_failure_readback
        candidates = recovery_candidates_readback(job, self._job_dir(job['videoId']),
            self._alignment_state_path(job['videoId']).parent, self.runtime, self._windows_path)
        if candidates is not None:
            payload += ('\nUnapproved recording disagreement candidates (no content or timing authority):\n' +
                        json.dumps(candidates, ensure_ascii=False, sort_keys=True) + '\n').encode('utf-8')
        failure = recovery_failure_readback(job, self._job_dir(job['videoId']),
            self._alignment_state_path(job['videoId']).parent, self.runtime, self._windows_path)
        if failure is not None:
            payload += ('\nOptional independent recording recovery failure (primary content blocker retained):\n' +
                        json.dumps(failure, ensure_ascii=False, sort_keys=True) + '\n').encode('utf-8')
        if getattr(self, 'source_context', None) is not None:
            heading = (f'Native recording: {self.key}\nAudio: {self.recording.identity["file"]}\n'
                'The YouTube selector is a lyric reference only. Review this recording independently.\n'
                f'Embedded artwork: https://play.qobuz.com/track/{self.recording.identity["trackId"]}\n\n')
            payload = heading.encode('utf-8') + payload
        return payload

    def _recording_evidence_inputs(self, job: dict[str, Any]) -> dict[str, Any] | None:
        inputs = job.get('recordingEvidenceInputs')
        if inputs is None:
            return None
        from stemwise_engine.lyric_content import validate_recording_review_inputs
        try:
            return validate_recording_review_inputs(self._job_dir(job['videoId']),
                self._alignment_state_path(job['videoId']).parent, inputs)
        except (OSError, ValueError, KeyError, TypeError):
            return {'status': 'unavailable'}

    def _write_task(self, job: dict[str, Any]) -> None:
        from stemwise_engine.workflow_tasks import TaskPlan, JobState
        from stemwise_engine.workflow_states import JobProgress
        from stemwise_engine.job_ownership import inspect_analysis_replacement
        job, replacement_pending = inspect_analysis_replacement(self, job)
        if replacement_pending:
            job = {**job, 'state': 'ANALYSIS_REPLACEMENT_PENDING'}
        video_id = job['videoId']
        directory = self._job_dir(video_id)
        requirements = workflow_review.requirements_from_draft(directory/'drafts') or (None, None)
        self._write_companion_packet(job)
        endpoint, display = job.get('endpointReview'), job.get('displayReview')
        plan = TaskPlan(video_id, JobState(job['state']), editorial.POLICY_VERSION,
            self._windows_path(self.workspace_root/'SEND_TO_AGENTS.md'), self._windows_path(directory),
            self._windows_path(self.runtime/'children'/f'{video_id}.stemwise.zip'),
            self._windows_path(self.config_path.parent.parent/'STEMWISE_AGENT.ps1'), *requirements,
            self._windows_path(self._endpoint_review_attempt_dir(video_id, endpoint['attemptId']))
                if self._has_pending_endpoint_review(job) else None,
            self._windows_path(self._display_review_attempt_dir(video_id, display['attemptId']))
                if isinstance(display, dict) else None,
            self.source_context.recording_key if getattr(self, 'source_context', None) else None,
            audio_candidate=(self._windows_path(validate_generated_path(directory,
                directory/job['audioFirstCandidate']['candidate']['path'], workspace_root=self.workspace_root))
                if isinstance(job.get('audioFirstCandidate'), dict) else None),
            progress=JobProgress.current(job), next_action=('Validate'
                if (directory/'song-attempt.private.json').is_file()
                and self._song_ledger(video_id).blocking_disposition
                and job['state'] not in {'VALIDATED', 'DELIVERED', 'ANALYSIS_REPLACEMENT_PENDING'} else None))
        payload = plan.render()
        if job.get('error') == 'bridge-package-too-large':
            payload += (f'\nBridge budget cannot fit this complete song. For owner-authorized manual import only, '
                        f'use Retry {plan.selector} -ManualImport. Keep the complete ZIP in Ready to Import; '
                        'the bridge limit remains enforced.\n').encode('utf-8')
        payload = self._task_payload(job, payload)
        path = directory/'AGENT_TASK.txt'
        if path.is_file() and read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=directory) == payload:
            return
        atomic_write_bytes(path, payload, root=directory, workspace_root=self.workspace_root)


    def _base_job(self, video_id: str, url: str) -> dict[str, Any]:
        job = {
            "formatVersion": 1,
            "policyVersion": editorial.POLICY_VERSION,
            "videoId": video_id,
            "url": canonical_url(video_id),
            "submittedUrl": canonical_url(video_id),
            "state": "NEW",
            "deliveryAllowed": True,
            "qualificationClaimsAllowed": False,
            "createdAt": utc_now(),
            "updatedAt": utc_now(),
            "artworkReview": {"result": "pending"},
            "recordingReviewRequired": True,
            "policy": self._policy_binding(),
        }
        recording = self.source.identity(video_id).get('recording')
        if recording is not None:
            job.update(recordingInput=recording, resultScope='recording-enrichment')
        return job




    def candidate_context(self, video_id):
        return source_acquisition.CandidateContext(self._job_dir(video_id), self.workspace_root, self._windows_path, youtube.fetch_video_info)

    def _song_info(self, video_id: str) -> dict[str, Any]:
        prepared = self._pipeline().prepared_inputs(video_id)
        if prepared is not None:
            return prepared['info']
        return self.source.song_info(video_id)

    def _collect_normal_candidates(self, info, destination, *, video_id):
        return self.source.collect_normal_candidates(info, destination, video_id)

    def acquisition(self, video_id):
        from stemwise_engine.source_acquisition import AcquisitionContext, AcquisitionOperations, SourceAcquisition
        recording = self.source.identity(video_id).get('recording')
        return SourceAcquisition(AcquisitionContext(self.workspace_root, self._job_dir(video_id),
            self.runtime, json_bytes(self._runtime_config()),
            json_bytes(recording) if recording is not None else None),
            AcquisitionOperations(self._save_job, self._write_task, self._pipeline, self._process_song,
                self._completed_analysis_audio, self._song_info, self._preflight_expensive_song_space,
                self._windows_path, self._collect_normal_candidates, self._workflow_operation_failure_code,
                self._recover_acquisition_lyrics, lambda job, source: self._acquisition_operation(job,
                    lambda: self._add_lyric_source_action(video_id, canonical_url(source)))))

    def _acquisition_operation(self, job, callback):
        """Return nested durable job mutations to the acquisition's current owner."""
        try:
            return callback()
        finally:
            pending = sys.exc_info()[1]
            try:
                current = self._load_job(job['videoId'])
                job.clear()
                job.update(current)
            except BaseException as error:
                from stemwise_engine.common import raise_with_cleanup_failure
                raise_with_cleanup_failure(pending, error)

    def _recover_acquisition_lyrics(self, job):
        return self._recover_lyrics_stage(job, lambda: self._run_recording_evidence(job['videoId']))

    def _recover_lyrics_stage(self, job, callback):
        from stemwise_engine.alignment_attempt import SongAttemptBlocked
        from stemwise_engine.provider_operations import raise_if_operation_stopped
        def recover():
            try:
                return callback()
            except (WorkerRetirementError, SongAttemptBlocked):
                raise
            except Exception as error:
                raise_if_operation_stopped(error)
                try:
                    self._record_validation_outcome(job['videoId'],
                        error if isinstance(error, FutureSongOutcomeError) else
                        FutureSongOutcomeError('RETRYABLE_TECHNICAL', 'retryable-technical'),
                        source_stage='recording-evidence')
                except BaseException as reporting_error:
                    from stemwise_engine.common import raise_with_cleanup_failure
                    raise_with_cleanup_failure(error, reporting_error)
                raise
        return self._acquisition_operation(job, recover)

    def new(self, url: str, *, max_file_bytes: int | None = None) -> dict[str, Any]:
        video_id = extract_video_id(url)
        self._require_selected_audio(video_id)
        with exclusive_process_lock(self._job_dir(video_id) / "workflow.lock"):
            from stemwise_engine.job_ownership import require_new_recording
            require_new_recording(self, video_id)
            return self._run_mutating_operation(
                video_id,
                "New",
                lambda: self._new_action(url, max_file_bytes=max_file_bytes),
            )

    def _new_action(self, url: str, *, max_file_bytes: int | None = None) -> dict[str, Any]:
        video_id = extract_video_id(url)
        job_dir = self._job_dir(video_id)
        job_dir.mkdir(parents=True, exist_ok=True)
        existing_job = self._job_path(video_id)
        if existing_job.exists():
            raise FutureSongError(
                f"A job already exists for {video_id}; use status/retry instead"
            )
        unexpected = [
            path.name for path in job_dir.iterdir() if path.name != "workflow.lock"
        ]
        if unexpected:
            raise FutureSongError(
                "an incomplete job directory already exists; inspect it before retrying New"
            )
        job = self._base_job(video_id, url)
        from stemwise_engine.bridge_delivery import bind_budget
        bind_budget(job, max_file_bytes)
        self._save_job(job)
        self.report_store.ensure_report_identity(video_id)
        with self._song_ledger(video_id).active():
            self.acquisition(job['videoId']).run(job)
        self._save_job(job)
        self._write_task(job)
        return self.summary(job)

    def add_lyric_source(self, video_id: str, url: str) -> dict[str, Any]:
        """Stage or repair lyric evidence without changing package or audio identity."""

        video_id = validate_video_id(video_id)
        return self._run_mutating_operation(
            video_id,
            "Source",
            lambda: self._add_lyric_source_action(video_id, url),
        )

    def _add_lyric_source_action(self, video_id: str, url: str) -> dict[str, Any]:
        video_id = validate_video_id(video_id)
        source_video_id = extract_video_id(url)
        job = self._load_job(video_id)
        sources_path = self._job_dir(video_id) / "sources.json"
        if not sources_path.is_file():
            raise FutureSongError("sources.json is missing; finish or retry acquisition first")
        sources = read_json(sources_path)
        evidence = sources.setdefault("lyricEvidenceSources", [])
        if not isinstance(evidence, list):
            raise FutureSongError("sources.json lyricEvidenceSources is invalid")
        existing_record = next(
            (
                record
                for record in evidence
                if isinstance(record, dict) and record.get("videoId") == source_video_id
            ),
            None,
        )
        repair_primary_music = False
        existing_usable = False
        if existing_record is not None:
            draft = self._job_dir(video_id) / "drafts"
            draft_present = (
                (draft / "lyrics.meta.json").is_file()
                and not (draft / "lyrics.meta.json").is_symlink()
                and any(
                    (draft / name).is_file() and not (draft / name).is_symlink()
                    for name in ("original.txt", "original.lrc")
                )
            )
            existing_usable = source_acquisition.source_record_has_usable_evidence(
                existing_record,
                draft_present=draft_present,
                job_root=self._job_dir(video_id).resolve(),
                package_video_id=video_id,
            )
            music_result = (existing_record.get("youtubeMusic") or {}).get("result") or {}
            repair_primary_music = (
                source_video_id == video_id and job["state"] not in TERMINAL_STATES
                and music_result.get("decision") == "skipped-existing"
                and not music_result.get("candidatePath")
            )
            if (existing_usable and not repair_primary_music
                    and source_acquisition.source_record_complete(existing_record, self._job_dir(video_id).resolve())):
                result = self.summary(job)
                result["sourceAction"] = {
                    "result": "already-staged",
                    "videoId": source_video_id,
                    "associationStatus": existing_record.get("associationStatus"),
                }
                return result
        if job["state"] in TERMINAL_STATES:
            raise FutureSongError(
                "A completed job cannot repair or accept lyric-source evidence; "
                "the delivery was not modified"
            )
        from stemwise_engine.workflow_states import JobTransition
        JobTransition.require(job['state'], 'WAITING_FOR_EDITORIAL')
        review_path = self._job_dir(video_id) / "agent-review.json"
        if not review_path.is_file():
            raise FutureSongError("agent-review.json is missing; the job cannot stage a source")
        review = read_json(review_path)
        if source_video_id == video_id:
            candidate_dir = self._job_dir(video_id) / "candidates" / source_video_id
            candidate_dir_existed = candidate_dir.exists() or candidate_dir.is_symlink()
            collected = source_acquisition.collect_source_evidence(self.candidate_context(video_id),
                video_id, source_video_id, relationship="exact-package-id",
                retained_normal=existing_record["normalYouTube"] if repair_primary_music and existing_usable else None,
            )
            if repair_primary_music and existing_usable and not source_acquisition.source_record_has_usable_evidence(
                {**collected, "normalYouTube": None}, draft_present=False,
                job_root=self._job_dir(video_id).resolve(), package_video_id=video_id,
            ):
                result = self.summary(job)
                result["sourceAction"] = {
                    "result": "no-usable-body", "videoId": source_video_id,
                    "usableLyricEvidenceAvailable": True,
                    "youtubeMusicDecision": collected["youtubeMusic"]["result"].get("decision"),
                }
                return result
            if not source_acquisition.source_record_has_usable_evidence(
                collected, draft_present=False, job_root=self._job_dir(video_id).resolve(),
                package_video_id=video_id,
            ):
                if not candidate_dir_existed:
                    remove_disposable_path(self._job_dir(video_id), candidate_dir,
                                           workspace_root=self.workspace_root)
                result = self.summary(job)
                result["sourceAction"] = {
                    "result": "no-usable-body", "videoId": source_video_id,
                    "usableLyricEvidenceAvailable": False,
                    "youtubeMusicDecision": collected["youtubeMusic"]["result"].get("decision"),
                    "normalYouTubeUsableCandidateCount": collected["normalYouTube"].get("usableLyricCandidateCount", 0),
                }
                return result
        else:
            collected = source_acquisition.collect_cross_id_evidence(self.candidate_context(video_id),
                video_id, source_video_id, relationship="agent-selected-lyric-counterpart",
            )
        if existing_record is not None:
            root = self._job_dir(video_id).resolve()
            normal = collected.setdefault('normalYouTube', {})
            records = normal.setdefault('records', [])
            for candidate in (existing_record.get('normalYouTube') or {}).get('records', []):
                if ('path' in candidate and source_acquisition.source_candidate_intact(candidate, root)
                        and not any('path' in fresh
                            and resolve_path(fresh['path'], base=root) == resolve_path(candidate['path'], base=root)
                            and fresh.get('sha256') == candidate.get('sha256') for fresh in records)):
                    records.append(candidate)
            normal['candidateCount'] = sum('sha256' in record for record in records)
            normal['usableLyricCandidateCount'] = sum(
                record.get('lyricUse') == 'potential-body' and 'sha256' in record
                for record in records)
            music = (existing_record.get('youtubeMusic') or {}).get('result') or {}
            if (music.get('candidatePath') and source_acquisition.source_candidate_intact(
                    {'path': music['candidatePath'], 'sha256': music.get('sha256')}, root)):
                collected['youtubeMusic'] = existing_record['youtubeMusic']
        if existing_record is not None and not source_acquisition.source_record_has_usable_evidence(
            collected,
            draft_present=False,
            job_root=self._job_dir(video_id).resolve(),
            package_video_id=video_id,
        ):
            raise FutureSongError(
                "the already-staged source no longer has an intact lyric body and "
                "automatic reacquisition did not repair it"
            )

        # Invalidate the release snapshot before persisting any new source or
        # review decision.  The workflow lock prevents concurrent Finalize, and
        # this ordering remains fail-closed if the process stops between files.
        job["state"] = "WAITING_FOR_EDITORIAL"
        job.pop("validation", None)
        job.pop("error", None)
        job.pop("outcome", None)
        self._save_job(job)

        review["youtubeSurfaceVariantsReviewed"] = False
        review["publicSourceResearchComplete"] = False
        review["authorityOutcome"] = "pending"
        review["sourceDisposition"] = "REPLACE_AFTER_REVIEW"
        review["recordingOrSourceAssociationReviewed"] = False
        recording_review = review.get("recordingReview")
        if isinstance(recording_review, dict):
            for key in ("recordingCompared", "versionSpecificWordingReviewed", "performedOrderReviewed"):
                recording_review[key] = False
        metadata_review = review.get("metadataReview")
        if isinstance(metadata_review, dict):
            metadata_review["recordingIdentityConfirmed"] = False
        evidence_ids = review.get("lyricEvidenceVideoIds")
        if isinstance(evidence_ids, list) and source_video_id not in evidence_ids:
            evidence_ids.append(source_video_id)
        issues = review.get("unresolvedMaterialIssues")
        if not isinstance(issues, list):
            issues = []
        issue = f"Review and classify lyric-source evidence {source_video_id}."
        if issue not in issues:
            issues.append(issue)
        review["unresolvedMaterialIssues"] = issues
        atomic_write_json(review_path, review)

        source_result = "staged"
        if existing_record is None:
            evidence.append(collected)
        else:
            evidence[evidence.index(existing_record)] = collected
            source_result = "restaged"
        sources["generatedAt"] = utc_now()
        source_acquisition.refresh_sources_availability(sources, self._job_dir(video_id) / "drafts")
        atomic_write_json(sources_path, sources)
        self._write_task(job)
        result = self.summary(job)
        result["sourceAction"] = {
            "result": source_result,
            "videoId": source_video_id,
            "associationStatus": "pending-agent-review",
        }
        return result

    def select_artwork(self, video_id: str, url: str) -> dict[str, Any]:
        """Bind inspected artwork from one already-reviewed source upload."""

        video_id = validate_video_id(video_id)
        return self._run_mutating_operation(
            video_id,
            "Artwork",
            lambda: self._select_artwork_action(video_id, url),
        )

    def _select_artwork_action(self, video_id: str, url: str) -> dict[str, Any]:
        video_id = validate_video_id(video_id)
        job = self._recover_artwork_repair(self._load_job(video_id))
        if job.get("state") not in {"WAITING_FOR_EDITORIAL", "NEEDS_LYRICS", "DELIVERED"}:
            raise FutureSongError(
                "Artwork is allowed only before the first alignment or for an intact delivery"
            )
        source_video_id = self._reviewed_artwork_source(job, url)

        pipeline = self._pipeline()
        if job.get("state") == "DELIVERED":
            if not self._validated_snapshot_is_current(job, require_delivery=True):
                raise WorkflowInputError("delivered snapshot is not intact; artwork was not changed", reason_code='publication-conflict',
                    affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
            existing_review = job.get("artworkReview")
            if (
                isinstance(existing_review, dict)
                and existing_review.get("sourceVideoId") == source_video_id
            ):
                self._require_reviewed_artwork(job, pipeline)
                return self.summary(job)
        pipeline.load_checkpoint(video_id)
        child = pipeline.child_path(video_id)
        document, old_cover = read_child(child, self._runtime_config())
        if old_cover is None or document.get("source", {}).get("videoId") != video_id:
            raise ArtworkUnavailableError('current package artwork is missing or misbound')
        source_info = self._artwork_source_info(source_video_id)
        source_duration = source_info.get("duration")
        job_duration = job.get("durationSeconds")
        if source_video_id != video_id:
            if any(
                type(duration) not in (int, float)
                or not math.isfinite(duration)
                or duration <= 0
                for duration in (source_duration, job_duration)
            ):
                raise ArtworkUnavailableError('reviewed artwork source duration is missing or invalid')
            if abs(source_duration - job_duration) > 2.0:
                raise ArtworkUnavailableError('reviewed artwork source duration differs from the package')

        staging_root = validate_generated_path(
            self.runtime,
            self.runtime / "artwork-staging",
            workspace_root=self.workspace_root,
        )
        staging_root.mkdir(parents=True, exist_ok=True)
        owner = {"kind": "artwork-repair-staging", "videoId": video_id}
        old_child = read_bounded_regular_bytes(
            child,
            max_bytes=int(self.config["childZipMaxBytes"]),
            root=pipeline.children_dir,
        )
        previous_child_sha256 = hashlib.sha256(old_child).hexdigest()
        previous_artwork_sha256 = hashlib.sha256(old_cover).hexdigest()
        with manifested_temporary_directory(
            staging_root,
            prefix=f"{video_id}-",
            owner=owner,
            workspace_root=self.workspace_root,
        ) as staging:
            cover_path = staging / "cover.png"
            artwork = self._create_selected_artwork(source_info, source_video_id, cover_path, pipeline)
            updated_document = copy.deepcopy(document)
            updated_document["generatedAt"] = _strictly_later_generated_at(document.get("generatedAt"))
            updated_document["artwork"] = artwork
            candidate = staging / "candidate.stemwise.zip"
            create_child_zip(candidate, updated_document, cover_path, self._runtime_config())
            validate_child_zip(
                candidate,
                self._runtime_config(),
                expected_video_id=video_id,
            )
            artwork_sha256 = sha256_file(cover_path)
            child_sha256 = sha256_file(candidate)
            artwork_review = {
                "result": "accepted",
                "sourceVideoId": source_video_id,
                "sourceUrl": self._artwork_source_url(source_video_id),
                "artworkSha256": artwork_sha256,
                "reviewedAt": utc_now(),
            }

            previous_current_run: dict[str, Any] | None = None
            repair: dict[str, Any] | None = None
            try:
                if job.get("state") == "DELIVERED":
                    previous_current_run = {key: job['delivery'][key] for key in ('runId', 'runSha256')}
                    backup = self._job_dir(video_id) / 'artwork-repair.rollback'
                    job_raw = read_bounded_regular_bytes(self._job_path(video_id), max_bytes=MAX_JSON_BYTES, root=self.jobs)
                    if backup.with_suffix('.rollback.json').exists() or backup.with_suffix('.rollback.zip').exists():
                        raise WorkflowInputError('unfinished artwork rollback must be recovered first', reason_code='publication-conflict',
                            affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
                    atomic_write_bytes(backup.with_suffix('.rollback.zip'), old_child, root=self.jobs, workspace_root=self.workspace_root)
                    atomic_write_bytes(backup.with_suffix('.rollback.json'), job_raw, root=self.jobs, workspace_root=self.workspace_root)
                    job['artworkRepairPending'] = {
                        'jobSha256': hashlib.sha256(job_raw).hexdigest(), 'childSha256': child_sha256,
                        'artworkSha256': artwork_sha256, 'sourceVideoId': source_video_id}
                    self._save_job(job)
                    run_id, run_sha256, repaired_at = self.report_store.commit_artwork_repair_run(
                        job, source_video_id=source_video_id,
                        previous_child_sha256=previous_child_sha256,
                        previous_artwork_sha256=previous_artwork_sha256,
                        child_sha256=child_sha256, artwork_sha256=artwork_sha256)
                    repair = {**self.report_store.artwork_repair_artifacts(job,
                        source_video_id=source_video_id, previous_child_sha256=previous_child_sha256,
                        previous_artwork_sha256=previous_artwork_sha256,
                        child_sha256=child_sha256, artwork_sha256=artwork_sha256),
                        'runId': run_id, 'runSha256': run_sha256, 'repairedAt': repaired_at}
                pipeline.commit_child_edit(video_id, candidate, stage='artwork-review',
                    binding={'jobSha256': sha256_file(self._job_path(video_id)),
                             'artworkReview': artwork_review})
                job["artworkReview"] = artwork_review
                if repair is not None:
                    job.pop('artworkRepairPending')
                    job["artworkRepair"] = repair
                    job["state"] = "VALIDATED"
                    job.pop("delivery", None)
                    job.pop("error", None)
                    job.pop("outcome", None)
                self._save_job(job)
            except Exception:
                if previous_current_run is not None:
                    self._recover_artwork_repair(self._load_job(video_id))
                else:
                    rollback = staging / 'rollback.zip'
                    rollback.write_bytes(old_child)
                    pipeline.commit_child_edit(video_id, rollback, stage='artwork-rollback',
                        binding={'jobSha256': sha256_file(self._job_path(video_id))})
                raise
            if previous_current_run is not None:
                self._recover_artwork_repair(job)
        self._write_task(job)
        return self.summary(job)

    def _recover_artwork_repair(self, job):
        """Recover the old tuple until the repaired job has durably committed."""
        if not hasattr(self, 'jobs'):
            return job
        video_id = validate_video_id(job['videoId'])
        directory = self._job_dir(video_id)
        backup_job = directory / 'artwork-repair.rollback.json'
        backup_child = directory / 'artwork-repair.rollback.zip'
        pending = job.get('artworkRepairPending')
        if 'artworkRepairPending' in job:
            if (not isinstance(pending, dict) or set(pending) !=
                    {'jobSha256', 'childSha256', 'artworkSha256', 'sourceVideoId'}):
                raise WorkflowInputError('artwork rollback binding is invalid', reason_code='publication-conflict',
                    affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
            raw = read_bounded_regular_bytes(backup_job, max_bytes=MAX_JSON_BYTES, root=self.jobs)
            if hashlib.sha256(raw).hexdigest() != pending['jobSha256']:
                raise WorkflowInputError('artwork rollback job changed', reason_code='publication-conflict',
                    affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
            previous = parse_strict_json_bytes(raw)
            if (previous.get('state') != 'DELIVERED' or previous.get('videoId') != video_id
                    or {key: value for key, value in previous.items() if key != 'updatedAt'} !=
                    {key: value for key, value in job.items() if key not in {'updatedAt', 'artworkRepairPending'}}):
                raise WorkflowInputError('artwork rollback belongs to another job', reason_code='publication-conflict',
                    affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
            old_child = read_bounded_regular_bytes(backup_child,
                max_bytes=int(self.config['childZipMaxBytes']), root=self.jobs)
            old_sha = hashlib.sha256(old_child).hexdigest()
            child = self.runtime / 'children' / f'{video_id}.stemwise.zip'
            destination = self.ready / child.name
            if (old_sha != previous.get('artworkRepair', {}).get('childSha256', previous['validation']['childSha256'])
                    or sha256_file(child) not in {old_sha, pending['childSha256']}
                    or sha256_file(destination) != previous['delivery']['sha256']):
                raise WorkflowInputError('artwork rollback package changed', reason_code='publication-conflict',
                    affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
            report_root = self.report_store.report_root(video_id)
            pointer = read_bounded_json(report_control_path(report_root, 'current-run.json'), max_bytes=16384, root=report_root)
            previous_pointer = {key: previous['delivery'][key] for key in ('runId', 'runSha256')}
            if pointer != previous_pointer and not self.report_store.immutable_run_matches(
                    video_id, run_id=pointer.get('runId'), run_sha256=pointer.get('runSha256'),
                    operation='artwork-repair', schema_sha256=previous['policy']['schemaSha256'],
                    require_current_pointer=True,
                    artifacts=self.report_store.artwork_repair_artifacts(previous,
                        source_video_id=pending['sourceVideoId'], previous_child_sha256=old_sha,
                        previous_artwork_sha256=previous.get('artworkRepair', {}).get(
                            'artworkSha256', previous['validation']['artworkSha256']),
                        child_sha256=pending['childSha256'], artwork_sha256=pending['artworkSha256']),
                    checkpoint_requirements={
                        'validationReportSha256': previous['validation']['reportSha256'],
                        'validationRunId': previous['validation']['runId'],
                        'validationRunSha256': previous['validation']['runSha256'],
                        'deliveryRunId': previous['delivery']['runId'],
                        'deliveryRunSha256': previous['delivery']['runSha256']}):
                raise WorkflowInputError('artwork rollback pointer changed', reason_code='publication-conflict',
                    affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
            pipeline = self._pipeline()
            with manifested_temporary_directory(pipeline.work_dir / 'staging', prefix=f'{video_id}-',
                    owner={'kind': 'artwork-rollback', 'videoId': video_id},
                    workspace_root=self.workspace_root) as staging:
                candidate = staging / 'rollback.zip'
                candidate.write_bytes(old_child)
                pipeline.commit_child_edit(video_id, candidate, stage='artwork-rollback',
                    binding={'jobSha256': pending['jobSha256']})
            atomic_write_json(report_control_path(report_root, 'current-run.json'), previous_pointer,
                root=report_root, workspace_root=self.workspace_root)
            if not self._validated_snapshot_is_current(previous, require_delivery=True):
                raise WorkflowInputError('restored artwork delivery failed validation; rollback retained', reason_code='publication-conflict',
                    affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
            atomic_write_bytes(self._job_path(video_id), raw, root=self.jobs, workspace_root=self.workspace_root)
            job = previous
        if backup_job.exists() or backup_child.exists():
            if not self._validated_snapshot_is_current(job, require_delivery=job.get('state') == 'DELIVERED'):
                raise WorkflowInputError('artwork cleanup requires an intact snapshot', reason_code='publication-conflict',
                    affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
            backup_job.unlink(missing_ok=True)
            backup_child.unlink(missing_ok=True)
        return job


    def _reviewed_artwork_source(self, job, url):
        if self.source.owned_artwork_source(url) is not None:
            # Embedded picture identity does not depend on resolving the lyric body.
            root = self._job_dir(job['videoId'])
            sources = read_bounded_json(root / 'sources.json', max_bytes=MAX_JSON_BYTES, root=root)
            return self._artwork_source(url, {}, sources)
        outcome, review, sources = self._validate_authority_outcome(job)
        if outcome != "resolved":
            raise FutureSongError("Artwork requires resolved source/recording review")
        return self._artwork_source(url, review, sources)


    def _artwork_source(self, url, review, sources):
        if self.source.owned_artwork_source(url) is not None:
            source_id = self.source.owned_artwork_source(url)
            self._require_artwork_source_association(review, sources, source_id)
            return source_id
        source_video_id = extract_video_id(url)
        staged_ids = {record.get("videoId") for record in sources.get("lyricEvidenceSources", [])
                      if isinstance(record, dict)}
        if source_video_id not in review["lyricEvidenceVideoIds"] or source_video_id not in staged_ids:
            raise FutureSongError("Artwork source must be an already-staged and reviewed source ID")
        self._require_artwork_source_association(review, sources, source_video_id)
        return source_video_id


    def _artwork_source_url(self, source_id):
        return self.source.artwork_url(source_id)

    def _artwork_source_info(self, source_id):
        return self.source.artwork_info(source_id)

    def _create_selected_artwork(self, info, source_id, destination, pipeline):
        return self.source.create_selected_artwork(info, source_id, destination)

    @staticmethod
    def _automatic_source_research_complete(sources: dict[str, Any]) -> bool:
        from stemwise_engine.source_acquisition import source_surface_checks_complete, counterpart_search_complete
        def researched(record):
            return (source_surface_checks_complete(record, allow_unavailable_automatic_captions=True)
                and record['youtubeMusic']['result']['decision'] in {'acquired', 'not-found'})
        exact = sources.get("exactSurfaceChecks")
        if not researched(exact):
            return False
        evidence = sources.get("lyricEvidenceSources")
        discovery = sources.get('counterpartDiscovery')
        return (isinstance(evidence, list) and bool(evidence)
            and all(researched(record) for record in evidence)
            and (discovery is None or (counterpart_search_complete(discovery)
                and discovery.get('stagingStatus') not in {'in-progress', 'staging-failed'})))


    def _require_reviewed_artwork(
        self, job: dict[str, Any], pipeline: StemwisePipeline
    ) -> None:
        review = job.get("artworkReview")
        expected = {
            "result",
            "sourceVideoId",
            "sourceUrl",
            "artworkSha256",
            "reviewedAt",
        }
        if not isinstance(review, dict) or set(review) != expected:
            raise ArtworkUnavailableError('inspected artwork is not selected; run Artwork first')
        source_video_id = review.get("sourceVideoId")
        source_url = self._artwork_source_url(source_video_id)
        checkpoint = pipeline.load_checkpoint(job["videoId"])
        child = pipeline.child_path(job["videoId"])
        document, cover = read_child(child, self._runtime_config())
        if (
            review.get("result") != "accepted"
            or review.get("sourceUrl") != source_url
            or not isinstance(review.get("reviewedAt"), str)
            or cover is None
            or hashlib.sha256(cover).hexdigest() != review.get("artworkSha256")
            or document.get("artwork", {}).get("source", {}).get("url")
            != source_url
        ):
            raise ArtworkUnavailableError('selected artwork identity is missing or changed')
        if self.source.owns_artwork(source_video_id):
            if (not isinstance(checkpoint, dict) or checkpoint.get('recordingInput') != self.recording.identity
                    or checkpoint.get('childSha256') != sha256_file(child)):
                raise ArtworkUnavailableError('Selected cover differs from the committed artwork result')
        # A thumbnail from another rendition is not approved by merely staging its ID.
        job_dir = self._job_dir(job["videoId"])
        authority_path = job_dir / "agent-review.json"
        if authority_path.is_file():
            authority = read_bounded_json(authority_path, max_bytes=MAX_JSON_BYTES, root=job_dir)
            sources = read_bounded_json(job_dir / "sources.json", max_bytes=MAX_JSON_BYTES, root=job_dir)
            self._require_artwork_source_association(authority, sources, source_video_id)
        elif job.get("recordingReviewRequired"):
            raise ArtworkUnavailableError('artwork source recording review is missing')


    def _require_artwork_source_association(
        self, authority: dict[str, Any], sources: dict[str, Any], source_video_id: str,
    ) -> None:
        if self.source.owns_artwork(source_video_id):
            self.source.require_artwork_source(sources)
            return
        recording = authority.get("recordingReview")
        if not isinstance(recording, dict):
            raise FutureSongError("Artwork requires current recording source review")
        if (
            source_video_id not in authority.get("lyricEvidenceVideoIds", [])
            or source_video_id not in {
                item.get("videoId") for item in sources.get("lyricEvidenceSources", [])
                if isinstance(item, dict)
            }
            or not isinstance(recording, dict)
            or {"videoId": source_video_id, "relation": "exact-recording"}
            not in recording.get("sourceAssociations", [])
        ):
            raise ArtworkUnavailableError('artwork source is not reviewed for the exact recording')

    def _require_recording_review(
        self, job: dict[str, Any], review: dict[str, Any],
        sources: dict[str, Any], analysis_audio: dict[str, Any],
    ) -> None:
        job_dir = self._job_dir(job["videoId"])
        editorial.validate_recording_review(
            review.get("recordingReview"),
            audio_sha256=analysis_audio["sha256"],
            draft_sha256=self._recording_draft_sha(job),
            sources_sha256=sha256_file(job_dir / "sources.json"),
            available_source_ids={record["videoId"] for record in sources["lyricEvidenceSources"]},
        )
        used_ids = {
            item["videoId"] for item in review["recordingReview"]["sourceAssociations"]
            if item["relation"] != "unusable"
        }
        for record in sources["lyricEvidenceSources"]:
            if record["videoId"] not in used_ids:
                continue
            candidates = list((record.get("normalYouTube") or {}).get("records", []))
            music = (record.get("youtubeMusic") or {}).get("result") or {}
            if music.get("candidatePath"):
                candidates.append({"path": music["candidatePath"], "sha256": music.get("sha256")})
            for candidate in candidates:
                if not isinstance(candidate, dict):
                    raise FutureSongError("reviewed source candidate is malformed")
                if "path" not in candidate:
                    continue
                if not isinstance(candidate["path"], str) or not isinstance(candidate.get("sha256"), str):
                    raise FutureSongError("reviewed source candidate is malformed")
                try:
                    path = resolve_path(candidate["path"], base=job_dir)
                    raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=job_dir)
                except (OSError, ValueError, RuntimeError) as error:
                    raise FutureSongError("reviewed source candidate is missing or unsafe") from error
                if hashlib.sha256(raw).hexdigest() != candidate.get("sha256"):
                    raise FutureSongError("reviewed source candidate changed after recording review")

    def _apply_metadata_review(
        self, job: dict[str, Any], review: dict[str, Any],
        sources: dict[str, Any], pipeline: StemwisePipeline,
        *, allow_change: bool = True, admitted_metadata=None,
    ) -> None:
        child = pipeline.child_path(job["videoId"])
        document, cover = read_child(child, self._runtime_config())
        try:
            metadata = apply_reviewed_metadata(
                admitted_metadata if admitted_metadata is not None else document["metadata"], review.get("metadataReview"),
                video_id=job["videoId"],
                sources_sha256=sha256_file(self._job_dir(job["videoId"]) / "sources.json"),
                staged_source_ids={record["videoId"] for record in sources["lyricEvidenceSources"]},
            )
        except (TypeError, ValueError) as error:
            raise FutureSongError("metadata recording review is incomplete or stale") from error
        if metadata == document["metadata"]:
            return
        if not allow_change:
            raise FutureSongError("reviewed metadata changed after alignment")
        if cover is None:
            raise ArtworkUnavailableError("metadata correction requires intact artwork")
        document["metadata"] = metadata
        document["generatedAt"] = _strictly_later_generated_at(document.get("generatedAt"))
        with manifested_temporary_directory(
            pipeline.work_dir / "staging", prefix=f"{job['videoId']}-",
            owner={"kind": "metadata-review-staging", "videoId": job["videoId"]},
            workspace_root=self.workspace_root,
        ) as staging:
            cover_path = staging / "cover.png"
            cover_path.write_bytes(cover)
            candidate = staging / "candidate.zip"
            create_child_zip(candidate, document, cover_path, self._runtime_config())
            pipeline.commit_child_edit(job['videoId'], candidate, stage='metadata-review',
                binding={'review': review.get('metadataReview'),
                         'sourcesSha256': sha256_file(self._job_dir(job['videoId']) / 'sources.json')})

    def _recording_draft_sha(self, job: dict[str, Any]) -> str:
        from stemwise_engine.lyrics_intake import read_recording_draft
        _, original_sha, _, metadata_sha = read_recording_draft(self._job_dir(job['videoId']))
        return hashlib.sha256(json_bytes({'formatVersion': 2,
            'originalInputSha256': original_sha, 'originalMetadataSha256': metadata_sha})).hexdigest()

    def _write_companion_packet(self, job):
        from stemwise_engine.companion_review import companion_review_packet
        from stemwise_engine.editorial_admission import available_lyrics
        from stemwise_engine.lyrics_intake import LyricsInputError
        directory = self._job_dir(job['videoId'])
        checkpoint = self.runtime/'work/checkpoints'/f"{job['videoId']}.json"
        if not checkpoint.is_file() or not (directory/'drafts/lyrics.meta.json').is_file():
            return
        audio = read_bounded_json(checkpoint, max_bytes=MAX_JSON_BYTES, root=self.runtime).get('originalAudio')
        if not isinstance(audio, dict):
            return
        packet = {'formatVersion': 1, 'state': 'original-authority-unavailable',
            'nextAction': 'Establish the current original content and recording before companion review.'}
        try:
            _, capabilities = available_lyrics(self, job, audio)
            if capabilities['content'] == 'admitted':
                original = self._preflight_drafts(job, roles=frozenset({'original'}))[0]['tracks'][0]
                candidates, bindings = {}, {}
                for kind, role in (('translation', 'translationEn'), ('transliteration', 'ipa')):
                    binding = capabilities['companionAdmission'][kind].get('binding')
                    if binding is None:
                        continue
                    compiled = self._preflight_drafts(job, roles=frozenset({'original', role}))[0]
                    candidate = next(track for track in compiled['tracks'] if track['kind'] == kind)
                    if hashlib.sha256(json_bytes(candidate)).hexdigest() != binding['candidateSha256']:
                        raise FutureSongError('companion-input-changed-during-packet')
                    candidates[kind], bindings[kind] = candidate, binding
                packet = companion_review_packet(original, candidates, bindings, video_id=job['videoId'])
                packet['recordingIdentity'] = {key: audio[key] for key in ('sha256', 'byteCount', 'durationMs')}
                packet['contentEvidence'] = {key: capabilities['contentAdmission'][key]
                    for key in ('evidence', 'adjudication', 'spokenTargets', 'ownerVerification')
                    if key in capabilities['contentAdmission']}
                packet['sourcesSha256'] = sha256_file(directory/'sources.json')
                document, _ = read_child(self.runtime/'children'/f"{job['videoId']}.stemwise.zip", self._runtime_config())
                packet['context'] = {'packageMetadata': document['metadata'],
                    'authority': 'Package metadata is context, not independent meaning or pronunciation evidence.'}
        except (FutureSongError, LyricsInputError, FileNotFoundError):
            pass  # Original authority is pending; never manufacture a reviewable original.
        path = directory/'companion-review-inputs.json'
        payload = json_bytes(packet, pretty=True)
        if not path.is_file() or read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=directory) != payload:
            atomic_write_bytes(path, payload, root=directory, workspace_root=self.workspace_root)

    def _companion_projection_current(self, job, lyrics, audio, *, require_current_original=False) -> bool:
        from stemwise_engine.editorial_admission import available_lyrics
        published = [track for track in lyrics['tracks'] if track['kind'] != 'original']
        projection, capabilities = available_lyrics(self, job, audio)
        if capabilities['content'] != 'admitted':
            return False
        if require_current_original:
            from stemwise_engine.editorial_admission import require_completed_lyrics
            require_completed_lyrics(projection, duration_ms=audio['durationMs'], endpoint_review=job.get('endpointReview'))
            if next(track for track in lyrics['tracks'] if track['kind'] == 'original') != next(
                    track for track in projection['tracks'] if track['kind'] == 'original'):
                return False
        admitted = {track['kind']: track for track in projection['tracks'] if track['kind'] != 'original'}
        for track in published:
            candidate = admitted.get(track['kind'])
            if candidate is None or any(track.get(key) != candidate.get(key)
                    for key in ('id', 'language', 'script', 'source', 'basedOnTrackId')):
                return False
            if [line for line in track_lines(track) if line.strip()] != [
                    line for line in track_lines(candidate) if line.strip()]:
                return False
        return capabilities['content'] == 'admitted'

    def _review_inputs(self, job: dict[str, Any], *, original_only: bool = False) -> dict[str, Any]:
        """Read-only fingerprints for the reviewer; never approve on their behalf."""
        job_dir = self._job_dir(job["videoId"])
        checkpoint_path = self.runtime / "work" / "checkpoints" / f"{job['videoId']}.json"
        checkpoint = read_bounded_json(checkpoint_path, max_bytes=MAX_JSON_BYTES, root=self.runtime)
        document, _ = read_child(
            self.runtime / "children" / f"{job['videoId']}.stemwise.zip", self._runtime_config()
        )
        draft_layout = {}
        for name in ROLE_FILES:
            if original_only and name not in {'original.txt', 'original.lrc'}:
                continue
            path = job_dir / "drafts" / name
            if path.suffix != ".txt" or not path.is_file():
                continue
            try:
                raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=job_dir)
                lines = raw.decode("utf-8").splitlines()
            except ValueError as error:
                from stemwise_engine.editorial_admission import _raise_input_io_failure
                _raise_input_io_failure(error)
                draft_layout[name] = {'state': 'invalid', 'reason': (
                    'invalid-utf8' if isinstance(error, UnicodeDecodeError) else 'unsafe-or-unavailable-input')}
                continue
            draft_layout[name] = {
                "nonblankLineCount": sum(bool(line.strip()) for line in lines),
                "blankLineNumbers": [index for index, line in enumerate(lines, 1) if not line.strip()],
            }
        inputs = {
            "audioSha256": checkpoint["originalAudio"]["sha256"],
            "draftSha256": self._recording_draft_sha(job),
            "sourcesSha256": sha256_file(job_dir / "sources.json"),
            "originalMetadataSha256": metadata_sha256(document["metadata"]),
            "plainDraftLayout": draft_layout,
        }
        owner_inputs = self._owner_verification_inputs(job)
        if owner_inputs is not None:
            inputs['ownerVerification'] = owner_inputs
        if not original_only:
            from stemwise_engine.editorial_admission import available_lyrics
            _, capabilities = available_lyrics(self, job, checkpoint['originalAudio'])
            inputs.update(originalContent=capabilities['content'],
                companionAdmission=capabilities.get('companionAdmission', {}))
        return inputs

    def _validate_authority_outcome(
        self, job: dict[str, Any]
    ) -> tuple[str, dict[str, Any], dict[str, Any]]:
        video_id = validate_video_id(job["videoId"])
        job_dir = self._job_dir(video_id)
        review = read_bounded_json(
            job_dir / "agent-review.json", max_bytes=MAX_JSON_BYTES, root=job_dir
        )
        sources = read_bounded_json(
            job_dir / "sources.json", max_bytes=MAX_JSON_BYTES, root=job_dir
        )
        if not isinstance(review, dict) or not isinstance(sources, dict):
            raise FutureSongError("authority inputs are invalid")
        available_ids = {
            record.get("videoId")
            for record in sources.get("lyricEvidenceSources", [])
            if isinstance(record, dict) and isinstance(record.get("videoId"), str)
        }
        if not available_ids:
            raise FutureSongError("sources.json has no lyric evidence source identities")
        outcome = editorial.validate_agent_review(
            review,
            video_id=video_id,
            available_lyric_source_ids=available_ids,
        )
        if outcome != "pending" and not self._automatic_source_research_complete(sources):
            raise FutureSongError("automatic source research is not complete")
        return outcome, review, sources

    def _preflight_drafts(self, job: dict[str, Any], *, roles: frozenset[str] | None = None) -> tuple[dict[str, Any], bool, bool]:
        """Reject malformed supplied inputs before recognition; approval remains separate."""
        video_id = job["videoId"]
        job_dir = self._job_dir(video_id)
        draft = job_dir / "drafts"
        manifest_path = draft / "lyrics.meta.json"
        if not manifest_path.is_file():
            raise FutureSongError("lyrics.meta.json is missing; editorial work is incomplete")
        manifest = read_bounded_json(manifest_path, max_bytes=MAX_JSON_BYTES, root=draft)
        needs_translation, needs_ipa = workflow_review.companion_requirements(manifest)
        from stemwise_engine.lyrics_intake import _ROLES
        for role in (_ROLES if roles is None else roles):
            for name in _ROLES[role]:
                path = draft / name
                if path.is_file() and path.suffix.lower() == ".txt":
                    editorial.validate_plain_draft(path)
        lyrics = compile_draft(
            draft,
            self._runtime_config(),
            {video_id},
            expected_video_id=video_id,
            roles=roles,
        )
        return lyrics, needs_translation, needs_ipa

    def _validate_drafts(
        self,
        job: dict[str, Any],
        *,
        review: dict[str, Any] | None = None,
        sources: dict[str, Any] | None = None,
    ) -> tuple[dict[str, Any], str, dict[str, Any]]:
        video_id = job["videoId"]
        job_dir = self._job_dir(video_id)
        draft = job_dir / "drafts"
        lyrics, _, _ = self._preflight_drafts(job, roles=frozenset({'original'}))
        tracks = {track["kind"]: track for track in lyrics["tracks"]}
        if "original" not in tracks:
            raise FutureSongError("compiled lyrics have no original track")
        original_lines = track_lines(tracks["original"])
        original_audit = audit_text(
            "\n".join(original_lines) + "\n",
            tracks["original"]["language"],
            tracks["original"]["script"],
            component_languages=tracks['original'].get('componentLanguages', ()),
        )
        if review is None:
            review = read_bounded_json(job_dir / 'agent-review.json', max_bytes=MAX_JSON_BYTES, root=job_dir)
        resolutions = review.get('qaResolutions', [])
        recording_sha = (self._completed_analysis_audio(self._pipeline(), video_id)['sha256']
                         if resolutions else None)
        from stemwise_engine.editorial_admission import unresolved_qa_issues
        original_issues = unresolved_qa_issues(original_audit, resolutions, video_id=video_id,
            recording_sha256=recording_sha, track_kind='original')
        if original_issues:
            raise FutureSongError('original lyric QA unresolved: ' + ','.join(sorted({i['code'] for i in original_issues})))
        from stemwise_engine.editorial_admission import available_lyrics
        audio = self._completed_analysis_audio(self._pipeline(), video_id)
        blockers = []
        _, capabilities = available_lyrics(self, job, audio, on_blocker=blockers.append)
        if capabilities['content'] != 'admitted':
            if blockers:
                raise blockers[0]
            raise FutureSongError('original content authority is unavailable')
        if tracks['original']['language'] == 'mul' and not tracks['original'].get('componentLanguages'):
            raise FutureSongError('multilingual component languages are missing')
        admitted = []
        for kind, role in (('translation', 'translationEn'), ('transliteration', 'ipa')):
            decision = capabilities['companionAdmission'][kind]
            if decision['state'] != 'admitted':
                continue
            compiled = self._preflight_drafts(job, roles=frozenset({'original', role}))[0]
            candidate = next(track for track in compiled['tracks'] if track['kind'] == kind)
            if (compiled['tracks'][0] != tracks['original']
                    or hashlib.sha256(json_bytes(candidate)).hexdigest() != decision['binding']['candidateSha256']):
                raise FutureSongError('companion-input-changed-during-admission')
            admitted.append(candidate)
        lyrics['tracks'] = [tracks['original'], *admitted]
        lyrics.update(status='partial', reason=ENDPOINT_REFINEMENT_REASON)
        tracks = {track['kind']: track for track in lyrics['tracks']}
        reviewed_lrc_files: list[str] = []
        reviewed_lrc_starts: list[int] | None = None
        path = draft / 'original.lrc'
        if path.is_file():
            starts = [record["startMs"] for record in parse_lrc(path)]
            if len(starts) != len(original_lines):
                raise WorkflowInputError('original.lrc timing does not cover the reviewed line sequence',
                    reason_code="review-stale", affected_input='original.lrc',
                    next_action="Correct the reviewed LRC line sequence, then Validate.")
            reviewed_lrc_files.append('original.lrc')
            reviewed_lrc_starts = starts
        if review is None:
            review = read_bounded_json(
                job_dir / "agent-review.json", max_bytes=MAX_JSON_BYTES, root=job_dir
            )
        sources_path = job_dir / "sources.json"
        if sources is None:
            sources = read_bounded_json(
                sources_path, max_bytes=MAX_JSON_BYTES, root=job_dir
            )
        availability_before = {
            key: sources.get(key)
            for key in (
                "containsLyrics",
                "containsLyricText",
                "usableLyricEvidenceAvailable",
                "draftLyricsPresent",
                "lyricsAvailability",
                "lyricsAvailabilityIssues",
            )
        }
        source_acquisition.refresh_sources_availability(sources, draft)
        availability_after = {
            key: sources.get(key)
            for key in availability_before
        }
        if availability_after != availability_before and not (
            job.get("recordingReviewRequired") or "recordingReview" in review
            or "metadataReview" in review
        ):
            sources["generatedAt"] = utc_now()
            atomic_write_json(sources_path, sources)
        available_lyric_source_ids = {
            record.get("videoId")
            for record in sources.get("lyricEvidenceSources", [])
            if isinstance(record, dict) and isinstance(record.get("videoId"), str)
        }
        if not available_lyric_source_ids:
            raise FutureSongError("sources.json has no lyric evidence source identities")
        if editorial.validate_agent_review(
            review,
            video_id=video_id,
            available_lyric_source_ids=available_lyric_source_ids,
        ) != "resolved":
            raise FutureSongError("draft validation requires resolved authority")
        return (
            lyrics,
            self._editorial_sha(job),
            {
                "sourceFiles": reviewed_lrc_files,
                "startsMs": reviewed_lrc_starts or [],
            },
        )

    def _editorial_sha(self, job: dict[str, Any], *, include_review: bool = True) -> str:
        """Fingerprint the already-reviewed editorial inputs without compiling them."""

        job_dir = self._job_dir(job["videoId"])
        draft = job_dir / "drafts"
        review_path = job_dir / "agent-review.json"
        if not draft.is_dir() or not review_path.is_file():
            raise FutureSongError("editorial inputs are incomplete")
        composite = hashlib.sha256(b"stemwise-editorial-snapshot\0v2\0")

        def add_file(relative_name: str, path: Path) -> None:
            name_bytes = relative_name.encode("utf-8")
            payload = read_bounded_regular_bytes(
                path, max_bytes=MAX_JSON_BYTES, root=job_dir
            )
            size = len(payload)
            composite.update(len(name_bytes).to_bytes(8, "big"))
            composite.update(name_bytes)
            composite.update(size.to_bytes(8, "big"))
            composite.update(hashlib.sha256(payload).digest())

        for path in sorted((draft/name for name in ROLE_FILES), key=lambda value: value.name):
            if path.is_symlink():
                raise FutureSongError("editorial inputs must not contain symlinks")
            if path.is_file():
                add_file(f"drafts/{path.name}", path)
        from stemwise_engine.spoken_targets import input_snapshot
        composite.update(json_bytes(input_snapshot(job_dir)))
        if include_review:
            add_file("agent-review.json", review_path)
            from stemwise_engine.companion_review import review_evidence_snapshot
            review = read_bounded_json(review_path, max_bytes=MAX_JSON_BYTES, root=job_dir)
            composite.update(json_bytes(review_evidence_snapshot(review, job_dir)))
            from stemwise_engine.lyric_content import ADJUDICATION_FILE
            adjudication = job_dir / ADJUDICATION_FILE
            if adjudication.exists() or adjudication.is_symlink():
                add_file(ADJUDICATION_FILE, adjudication)
        return composite.hexdigest()

    def _require_changed_lyrics_content(self, job: dict[str, Any], *, for_recording_evidence: bool = False) -> None:
        outcome = job.get("outcome")
        if isinstance(outcome, dict) and outcome.get("code") == "lyrics-content-unresolved":
            from stemwise_engine.workflow_records import CONTENT_EVIDENCE_TECHNICAL_REASONS
            reasons = workflow_diagnostics._diagnostic_codes(outcome.get('reasons'))
            if reasons and all(reason in CONTENT_EVIDENCE_TECHNICAL_REASONS for reason in reasons):
                return
            if (not outcome.get("draftSha256")
                    or outcome["draftSha256"] == self._recording_draft_sha(job)):
                if set(reasons) == {'content-sequence-uncertain'} and not for_recording_evidence:
                    from stemwise_engine.lyric_content import source_recording_admission
                    lyrics, _, _ = self._preflight_drafts(job, roles=frozenset({'original'}))
                    original = next(track for track in lyrics['tracks'] if track['kind'] == 'original')
                    audio = self._completed_analysis_audio(self._pipeline(), job['videoId'])
                    admission = source_recording_admission(self._job_dir(job['videoId']),
                        self._alignment_state_path(job['videoId']).parent, job['videoId'], original,
                        tuple(i for i, line in enumerate(original['text'].splitlines()) if line.strip()),
                        self._recording_draft_sha(job), audio['sha256'])
                    if admission is not None:
                        return
                if outcome.get('reasons') == ['pronunciation-support-unavailable']:
                    from stemwise_engine.spoken_targets import load
                    from stemwise_engine.lyrics_normalize import physical_lines
                    lyrics, _, _ = self._preflight_drafts(job, roles=frozenset({'original'}))
                    original = next(track for track in lyrics['tracks'] if track['kind'] == 'original')
                    binding = outcome.get('contentBinding')
                    indices = (binding['sourcePhysicalIndices'] if isinstance(binding, dict)
                               else [i for i, line in enumerate(physical_lines(original['text'])) if line.strip()])
                    audio = self._completed_analysis_audio(self._pipeline(), job['videoId'])
                    if load(self._job_dir(job['videoId']), job['videoId'], original, indices, audio['sha256']) is not None:
                        return  # Independent current recognition still owns content admission.
                if (for_recording_evidence and outcome.get('sourceStage') == 'recording-evidence'
                        and set(reasons) == {'content-sequence-uncertain'}):
                    from stemwise_engine.source_recording_content import selection
                    try:
                        selected = selection(self._job_dir(job['videoId']))
                    except (OSError, ValueError, KeyError, TypeError):
                        pass
                    else:
                        if selected is not None:
                            return  # Only a verified new source/recording decision clears the outcome.
                    from stemwise_engine.recording_evidence import adjudicate_retained_recording
                    audio = self._completed_analysis_audio(self._pipeline(), job['videoId'])
                    adjudicate_retained_recording(self.config, self.root, job['videoId'], audio)
                    return
                raise LyricsContentBlocker(
                    *(workflow_diagnostics._diagnostic_codes(outcome.get("reasons")) or ["content-evidence-unavailable"]),
                    binding=outcome.get("contentBinding"))


    def _validated_artwork_repair(
        self,
        job: dict[str, Any],
        *,
        child_document: dict[str, Any],
        child_sha256: str,
        artwork_sha256: str,
        require_current_pointer: bool,
    ) -> dict[str, Any] | None:
        repair = job.get("artworkRepair")
        if repair is None:
            return None
        expected = {
            "sourceVideoId",
            "previousChildSha256",
            "previousArtworkSha256",
            "validationChildSha256",
            "validationArtworkSha256",
            "childSha256",
            "artworkSha256",
            "runId",
            "runSha256",
            "repairedAt",
        }
        if isinstance(repair, dict) and 'previousDelivery' in repair:
            expected.add('previousDelivery')
        if isinstance(repair, dict) and 'previousRepair' in repair:
            expected.add('previousRepair')
        validation = job.get("validation")
        source = child_document.get("artwork", {}).get("source", {})
        if (
            not isinstance(repair, dict)
            or set(repair) != expected
            or not isinstance(validation, dict)
            or repair.get("validationChildSha256") != validation.get("childSha256")
            or repair.get("validationArtworkSha256") != validation.get("artworkSha256")
            or repair.get("childSha256") != child_sha256
            or repair.get("artworkSha256") != artwork_sha256
            or source.get("url") != self._artwork_source_url(repair.get("sourceVideoId"))
            or not isinstance(repair.get("repairedAt"), str)
        ):
            raise WorkflowInputError("artwork repair binding is invalid", reason_code='publication-conflict',
                affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
        previous = repair.get('previousRepair')
        if previous is None:
            if (repair['previousChildSha256'] != validation.get('childSha256')
                    or repair['previousArtworkSha256'] != validation.get('artworkSha256')):
                raise ArtworkUnavailableError('artwork repair base differs from validation')
        else:
            if (not isinstance(previous, dict) or set(previous) != {'runId', 'runSha256'}
                    or not isinstance(previous['runId'], str)
                    or ALIGNMENT_ATTEMPT_ID_PATTERN.fullmatch(previous['runId']) is None
                    or not isinstance(previous['runSha256'], str)
                    or re.fullmatch('[0-9a-f]{64}', previous['runSha256']) is None):
                raise WorkflowInputError('previous artwork revision binding is invalid', reason_code='publication-conflict',
                    affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
            path = self.report_store.report_root(job['videoId']) / 'runs' / previous['runId'] / 'run.json'
            raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=self.report_store.report_root(job['videoId']))
            prior = parse_strict_json_bytes(raw)
            prior_artifacts = prior.get('artifacts', {}) if isinstance(prior, dict) else {}
            if (not isinstance(prior, dict) or hashlib.sha256(raw).hexdigest() != previous['runSha256']
                    or prior.get('operation') != 'artwork-repair' or prior.get('status') != 'complete'
                    or prior.get('videoId') != job['videoId'] or prior.get('runId') != previous['runId']
                    or any(prior_artifacts.get(key) != repair[key] for key in
                           ('validationChildSha256', 'validationArtworkSha256'))
                    or prior_artifacts.get('childSha256') != repair['previousChildSha256']
                    or prior_artifacts.get('artworkSha256') != repair['previousArtworkSha256']):
                raise WorkflowInputError('previous artwork revision is missing or changed', reason_code='publication-conflict',
                    affected_input='artwork publication/rollback', next_action='Preserve the publication and rollback; resolve exact bindings before Finalize.')
        artifacts = {
            key: repair[key]
            for key in (
                "sourceVideoId",
                "previousChildSha256",
                "previousArtworkSha256",
                "validationChildSha256",
                "validationArtworkSha256",
                "childSha256",
                "artworkSha256",
            )
        }
        if 'previousDelivery' in repair:
            artifacts['previousDelivery'] = repair['previousDelivery']
        if 'previousRepair' in repair:
            artifacts['previousRepair'] = repair['previousRepair']
        schema_sha256 = (job.get("policy") or {}).get("schemaSha256", "")
        if not self.report_store.immutable_run_matches(
            job["videoId"],
            run_id=repair.get("runId"),
            run_sha256=repair.get("runSha256"),
            operation="artwork-repair",
            artifacts=artifacts,
            schema_sha256=schema_sha256,
            require_current_pointer=require_current_pointer,
        ):
            raise ArtworkUnavailableError("artwork repair run is missing or changed")
        return repair

    def _validated_snapshot_is_current(self, job: dict[str, Any], *, require_delivery: bool = False,
            allow_finalization_pointer: bool = False, allow_pending_delivery_cleanup: bool = False,
            delivery_source: Path | None = None) -> bool:
        current = ValidatedSnapshotService(self._snapshot_context(), self._review_operations(), self._review_policy()).validate(
            ValidatedSnapshotRequest(job=job, require_delivery=require_delivery,
                allow_finalization_pointer=allow_finalization_pointer,
                allow_pending_delivery_cleanup=allow_pending_delivery_cleanup, delivery_source=delivery_source))
        if not current:
            return False
        review_path = self._job_dir(job['videoId'])/'agent-review.json'
        try:
            if review_path.is_file():
                review = read_bounded_json(review_path, max_bytes=MAX_JSON_BYTES, root=review_path.parent)
                if isinstance(review.get('metadataReview'), dict) and review['metadataReview'].get('recordingIdentityConfirmed') is True:
                    if job.get('ownerWordPrediction') is not None:
                        self._owner_prediction_reviews(job, self._owner_prediction_audio_identity(job))
                    else:
                        outcome, review, sources = self._validate_authority_outcome(job)
                        if outcome == 'resolved':
                            self._apply_metadata_review(job, review, sources, self._pipeline(), allow_change=False)
        except (OSError, ValueError, TypeError, KeyError, FutureSongError):
            return False
        return True

    def _validated_artwork_lineage(self, job, *, child_document, child_sha256, artwork_sha256):
        repair = self._validated_artwork_repair(job, child_document=child_document,
            child_sha256=child_sha256, artwork_sha256=artwork_sha256, require_current_pointer=False)
        if repair is not None:
            return repair
        archived = job.get('capabilityRefresh', {}).get('previousArtworkRepair')
        if archived is None:
            return None
        root = self.report_store.report_root(job['videoId'])
        path = root/'runs'/archived['runId']/'run.json'
        run = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=root)
        if sha256_file(path) != archived['runSha256']:
            raise ArtworkUnavailableError('archived artwork run changed')
        binding = run['checkpointKey']
        validation_path = root/'runs'/binding['validationRunId']/'run.json'
        validation = read_bounded_json(validation_path, max_bytes=MAX_JSON_BYTES, root=root)
        if not self.report_store.immutable_run_matches(job['videoId'], run_id=binding['validationRunId'],
                run_sha256=binding['validationRunSha256'], operation='capability-completion',
                artifacts=validation['artifacts'], schema_sha256=job['policy']['schemaSha256'], require_current_pointer=False):
            raise ArtworkUnavailableError('archived artwork validation changed')
        # The immutable historical run owns this cover; current child/lyrics have their own capability snapshot.
        owner = {**job, 'validation': validation['artifacts'], 'artworkRepair': archived}
        return self._validated_artwork_repair(owner, child_document=child_document,
            child_sha256=archived['childSha256'], artwork_sha256=artwork_sha256, require_current_pointer=False)

    def _finalization_staging_root(self) -> Path:
        root = validate_generated_path(
            self.runtime,
            self.runtime / "finalization-staging",
            workspace_root=self.workspace_root,
        )
        root.mkdir(parents=True, exist_ok=True)
        return validate_generated_path(
            self.runtime,
            root,
            workspace_root=self.workspace_root,
        )

    def _scavenge_finalization_staging(self, video_id: str) -> Path:
        video_id = validate_video_id(video_id)
        root = self._finalization_staging_root()
        owner = {"kind": "package-finalization-staging", "videoId": video_id}
        paths = set(root.glob(f"{video_id}-*"))
        paths.update(root / receipt.name[1:-len('.cleanup.json')]
            for receipt in root.glob(f'.{video_id}-*.cleanup.json'))
        for path in sorted(paths):
            path = validate_generated_path(
                root, path, workspace_root=self.workspace_root
            )
            manifest = path / GENERATED_MANIFEST_NAME
            receipt = manifested_cleanup_receipt(path)
            if (not manifest.exists() and not manifest.is_symlink()
                    and not receipt.exists() and not receipt.is_symlink()):
                if not path.is_dir() or any(path.iterdir()):
                    raise FutureSongError(
                        "unowned finalization staging directory is not empty"
                    )
                path.rmdir()
                continue
            remove_manifested_directory(
                root,
                path,
                owner=owner,
                workspace_root=self.workspace_root,
            )
        return root

    def _cleanup_completed_endpoint_contexts(self, job: dict[str, Any]) -> None:
        pointer = job.get("endpointReview")
        if (job.get("state") != "DELIVERED" or not isinstance(pointer, dict)
                or self._has_pending_endpoint_review(job)):
            return
        from stemwise_engine.endpoint_ctc_evidence import _clean_scratch, _directory

        attempt = self._endpoint_review_attempt_dir(job["videoId"], pointer["attemptId"])
        _clean_scratch(_directory(attempt), self.workspace_root)
        from stemwise_engine.endpoint_owsm_ctc import _directory as owsm_directory
        _clean_scratch(owsm_directory(attempt), self.workspace_root)

    def _remove_retained_analysis_audio(self, video_id: str) -> None:
        video_id = validate_video_id(video_id)
        job = self._load_job(video_id)
        if self._has_pending_endpoint_review(job):
            return
        if (isinstance(job.get('recordingEvidenceInputs'), dict)
                and job.get('validation', {}).get('lyricsStatus') in {'partial', 'unavailable'}):
            return
        temporary_root = validate_generated_path(
            self.workspace_root,
            self.analysis_temp,
            workspace_root=self.workspace_root,
        )
        temporary = validate_generated_path(
            temporary_root,
            temporary_root / video_id,
            workspace_root=self.workspace_root,
        )
        if temporary.exists() or temporary.is_symlink():
            try:
                remove_manifested_directory(
                    temporary_root,
                    temporary,
                    owner={"kind": "analysis-temporary", "videoId": video_id},
                    workspace_root=self.workspace_root,
                )
            except ValueError as error:
                raise FutureSongError(str(error)) from error

    def _validated_performed_structure_review(
        self,
        job: dict[str, Any],
        review: dict[str, Any],
        sources: dict[str, Any],
        canonical_plain_sha256: str,
    ) -> dict[str, str] | None:
        value = review.get("performedStructureReview")
        if value is None:
            return None
        state_path = self._alignment_state_path(job["videoId"])
        state = read_bounded_json(
            state_path, max_bytes=MAX_JSON_BYTES, root=state_path.parent
        )
        if not isinstance(state, dict):
            raise FutureSongError("performedStructureReview is stale")
        review_binding = {
            "decision": "baseline",
            "sha256": hashlib.sha256(json_bytes(value, pretty=True)).hexdigest(),
        }
        source_path, source_run, source_diagnostics = (
            self.report_store.alignment_failure_snapshot(
                self.report_store.report_root(job["videoId"]),
                job["videoId"],
                value["sourceAlignmentAttemptId"],
            )
        )
        source_summary = source_run.get("summary")
        source_is_valid = (
            sha256_file(source_path) == value["sourceAlignmentRunSha256"]
            and source_run.get("failureCode") == "line-unresolved"
            and isinstance(source_summary, dict)
            and source_summary.get("resultClass") == "line-unresolved"
            and source_summary.get("reasons")
            == ["unsupported-mixed-repeat-structure"]
            and source_diagnostics.get("resultClass") == "line-unresolved"
            and source_diagnostics.get("reasons")
            == ["unsupported-mixed-repeat-structure"]
        )
        source_is_current = (
            state.get("status") == "line-unresolved"
            and state.get("attemptId") == value["sourceAlignmentAttemptId"]
            and job.get("lastAlignmentAttemptId")
            == value["sourceAlignmentAttemptId"]
            and sha256_file(state_path) == value["sourceAlignmentStateSha256"]
        )
        source_is_carried = False
        carried_result_class = {
            "line-unresolved": "line-unresolved",
            "retryable-technical": "retryable-technical",
        }.get(state.get("status"))
        if (
            carried_result_class is not None
            and state.get("attemptId") != value["sourceAlignmentAttemptId"]
        ):
            current_attempt_id = state.get("attemptId")
            if not isinstance(current_attempt_id, str):
                raise FutureSongError("performedStructureReview is stale")
            _, current_run, current_diagnostics = (
                self.report_store.alignment_failure_snapshot(
                    self.report_store.report_root(job["videoId"]),
                    job["videoId"],
                    current_attempt_id,
                )
            )
            current_summary = current_run.get("summary")
            source_is_carried = (
                current_attempt_id == job.get("lastAlignmentAttemptId")
                and state.get("performedStructureReview") == review_binding
                and current_run.get("failureCode") == carried_result_class
                and isinstance(current_summary, dict)
                and current_summary.get("resultClass") == carried_result_class
                and current_summary.get("reasons") == state.get("reasons")
                and current_diagnostics.get("resultClass") == carried_result_class
                and current_diagnostics.get("reasons") == state.get("reasons")
                and current_diagnostics.get("performedStructureReview") == review_binding
            )
        if (
            not source_is_valid
            or not (source_is_current or source_is_carried)
            or state.get("canonicalPlainSha256") != canonical_plain_sha256
        ):
            raise FutureSongError("performedStructureReview is stale")

        records = []
        for source in sources.get("lyricEvidenceSources", []):
            if not isinstance(source, dict) or source.get("videoId") != value["witnessVideoId"]:
                continue
            normal = source.get("normalYouTube")
            for record in normal.get("records", []) if isinstance(normal, dict) else []:
                if (
                    isinstance(record, dict)
                    and record.get("type") == "manual-subtitle"
                    and record.get("language") == value["witnessLanguage"]
                    and record.get("sha256") == value["witnessSha256"]
                ):
                    records.append(record)
        if len(records) != 1 or not isinstance(records[0].get("path"), str):
            raise FutureSongError("performedStructureReview witness is unavailable")
        witness_path = validate_generated_path(
            self._job_dir(job["videoId"]),
            resolve_path(
                records[0]["path"], base=self._job_dir(job["videoId"])
            ),
            workspace_root=self.workspace_root,
        )
        if not witness_path.is_file() or sha256_file(witness_path) != value["witnessSha256"]:
            raise FutureSongError("performedStructureReview witness changed")
        return review_binding

    def _alignment_worker_environment(self) -> dict[str, str]:
        project = Path(self.config["projectDir"])
        environment = os.environ.copy()
        agent_root = project.parent
        environment.update(
            {
                "HF_HOME": str(agent_root / "model-cache" / "huggingface"),
                "TORCH_HOME": str(agent_root / "model-cache" / "torch"),
                "XDG_CACHE_HOME": str(agent_root / "model-cache" / "xdg"),
            }
        )
        site_packages = next(
            iter(sorted((environment_path('lyrics', project) / "lib").glob("python*/site-packages"))),
            None,
        )
        if site_packages is None:
            raise FutureSongError("lyrics timing environment has no site-packages directory")
        cuda_libraries = [
            site_packages / "nvidia" / package / "lib"
            for package in ("cublas", "cudnn", "cuda_nvrtc")
        ]
        if any(not path.is_dir() for path in cuda_libraries):
            raise FutureSongError("lyrics timing CUDA libraries are incomplete")
        existing_library_path = environment.get("LD_LIBRARY_PATH")
        environment["LD_LIBRARY_PATH"] = os.pathsep.join(
            [str(path) for path in cuda_libraries]
            + ([existing_library_path] if existing_library_path else [])
        )
        return environment

    def _align_future_lyrics(
        self,
        video_id: str,
        attempt_id: str,
        expected_mix_sha256: str,
        editorial_sha256: str,
        canonical_plain_sha256: str,
        analysis_audio: Path,
        performed_structure_review: dict[str, str] | None = None,
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        """Time supported reviewed words; content uncertainty blocks without repair."""

        from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION

        project = Path(self.config["projectDir"])
        alignment_python = environment_python('lyrics', project)
        alignment_entry = project / "src" / "lyrics_align_pc.py"
        child = self.runtime / "children" / f"{video_id}.stemwise.zip"
        for required in (alignment_python, alignment_entry, child):
            if not required.is_file():
                raise FutureSongError(f"lyrics timing dependency is missing: {required.name}")

        environment = self._alignment_worker_environment()

        attempt_id = self._validate_alignment_attempt_id(attempt_id)
        state_path = self._alignment_state_path(video_id)
        failure_path = state_path.with_name(f'attempt-{attempt_id}.failure.private.txt')
        failure_path.unlink(missing_ok=True)
        from stemwise_engine.private_diagnostics import preserve_failure
        try:
            completed = self._run_primary_worker('alignment',
                {'audioSha256': expected_mix_sha256, 'editorialSha256': editorial_sha256,
                 'canonicalPlainSha256': canonical_plain_sha256, 'performedStructure': performed_structure_review},
                [
                    str(alignment_python),
                    "-m", "stemwise_engine.lyrics_align_pc",
                    "--config",
                    str(self.config_path),
                    "future-align",
                    option_safe_video_id_argument(video_id),
                    f"--attempt-id={attempt_id}",
                    f"--expected-mix-sha256={expected_mix_sha256}",
                    f"--editorial-sha256={editorial_sha256}",
                    f"--canonical-plain-sha256={canonical_plain_sha256}",
                    f"--analysis-audio={analysis_audio}",
                    f"--future-root={self.root}",
                    f"--analysis-root={self.analysis_temp}",
                    *(
                        [
                            "--reviewed-baseline-structure",
                            "--performed-structure-review-sha256="
                            f"{performed_structure_review['sha256']}",
                        ]
                        if performed_structure_review is not None
                        else []
                    ),
                    "--qwen-structure-witness",
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=None,  # Individual admitted model stages own their execution watchdogs.
                env=environment,
            )
        except Exception as error:
            preserve_failure(failure_path, error, getattr(error, 'stderr', None))
            raise
        from stemwise_engine.worker_protocol import raise_if_worker_stopped
        raise_if_worker_stopped(completed, code='lyrics-alignment-worker-stopped', diagnostic_path=failure_path)
        if completed.returncode != 0:
            status = None
            state = {}
            if state_path.is_file():
                state = read_json(state_path)
                statuses = workflow_diagnostics._diagnostic_codes([state.get("status")])
                status = statuses[0] if statuses else None
                if (
                    status == "starting"
                    and state.get("attemptId") == attempt_id
                    and completed.returncode == 2
                    and "stemwise-lyrics-align: ProcessLockBusyError"
                    in completed.stderr.splitlines()
                ):
                    # The child can be refused before it owns a worker lease.
                    # Preserve the typed refusal, never raw subprocess output.
                    state.update(
                        status="retryable-technical",
                        reasons=["heavy-worker-busy"],
                        updatedAtUnix=int(time.time()),
                    )
                    atomic_write_json(state_path, state)
                    status = "retryable-technical"
            if status == "lyrics-content-unresolved":
                reasons = workflow_diagnostics._diagnostic_codes(state.get("reasons"))
                raise LyricsContentBlocker(*(reasons or ["content-evidence-unavailable"]),
                    binding=state.get("contentBinding"))
            if status == "line-unresolved":
                raise FutureSongOutcomeError(
                    "LINE_UNRESOLVED", "line-unresolved"
                )
            failure = FutureSongError(f'lyrics timing child exited with code {completed.returncode}')
            preserve_failure(failure_path, failure, completed.stderr)
            if status == "retryable-technical":
                raise FutureSongOutcomeError(
                    "RETRYABLE_TECHNICAL", "retryable-technical"
                )
            reason = status or f"process-exit-{completed.returncode}"
            raise FutureSongError(
                "lyrics timing process ended before a terminal result "
                f"({reason}); approved text was preserved"
            )

        state = read_json(state_path)
        if (
            state.get("attemptId") != attempt_id
            or state.get("canonicalPlainSha256") != canonical_plain_sha256
            or state.get("alignmentVersion") != ALIGNMENT_VERSION
        ):
            raise FutureSongError("lyrics timing state is not committed")
        if performed_structure_review is not None and state.get(
            "performedStructureReview"
        ) != performed_structure_review:
            raise FutureSongError("lyrics timing ignored the performed structure review")
        if state.get('status') in {'committed-mixed', 'committed-lines', 'line-unresolved'}:
            raise FutureSongOutcomeError('LINE_UNRESOLVED', 'line-unresolved')
        if state.get('status') != 'committed-words':
            raise FutureSongError('lyrics timing state is not committed')
        lyrics = read_json(self._owner_sidecar_path(video_id))
        from stemwise_engine.song_enrichment_v2 import validate_lyrics
        from stemwise_engine.editorial_admission import complete_original_word_timing
        try:
            validate_lyrics(lyrics, duration_ms=state.get('durationMs'))
        except ValueError as error:
            raise FutureSongError('lyrics timing committed an invalid Original timeline') from error
        if not complete_original_word_timing(lyrics):
            raise FutureSongOutcomeError('LINE_UNRESOLVED', 'line-unresolved')
        return lyrics, state

    def _validate_display_review(self, job: dict[str, Any], *, read_only: bool = False) -> dict[str, Any]:
        workflow = self
        video_id = validate_video_id(job['videoId'])
        pointer = job.get('displayReview')
        if not isinstance(pointer, dict):
            raise FutureSongError('display-review state pointer is missing or invalid')
        attempt_id = self._validate_alignment_attempt_id(pointer.get('attemptId'))
        attempt = self._display_review_attempt_dir(video_id, attempt_id)
        declaration = read_bounded_json(attempt / 'declaration.json', max_bytes=MAX_JSON_BYTES, root=attempt)
        if not isinstance(declaration, dict) or not isinstance(declaration.get('alignment'), dict):
            raise FutureSongError('display-review alignment binding is invalid')
        alignment = declaration['alignment']
        mapping, mapping_identity = self._lexical_mapping(video_id)
        pipeline = self._pipeline()
        self._require_reviewed_artwork(job, pipeline)
        analysis_audio = self._completed_analysis_audio(pipeline, video_id)
        sources_path = self._job_dir(video_id) / 'sources.json'
        authority_path = self._job_dir(video_id) / 'agent-review.json'
        admitted_metadata = None
        if job.get('recordingReviewRequired') or authority_path.is_file():
            authority_review = read_bounded_json(authority_path, max_bytes=MAX_JSON_BYTES, root=authority_path.parent)
            authority_sources = read_bounded_json(sources_path, max_bytes=MAX_JSON_BYTES, root=sources_path.parent)
            self._require_recording_review(job, authority_review, authority_sources, analysis_audio)
            self._apply_metadata_review(job, authority_review, authority_sources, pipeline, allow_change=False)
            admitted_metadata = read_child(pipeline.child_path(video_id), self._runtime_config())[0]['metadata']
        from stemwise_engine.editorial_admission import available_lyrics
        blockers = []
        current_projection, capabilities = available_lyrics(self, job, analysis_audio, on_blocker=blockers.append)
        if capabilities['content'] != 'admitted':
            if blockers:
                raise blockers[0]
            raise FutureSongError('original content authority is unavailable')
        from stemwise_engine.editorial_admission import complete_original_word_timing
        if not complete_original_word_timing(current_projection):
            raise WorkflowInputError('Current numerical producer no longer supports the retained Original word timing',
                reason_code='line-unresolved', affected_input='Original word timing',
                next_action='Retain the prior package and recover timing under the current numerical producer.')
        run_matches = self.report_store.immutable_run_matches(video_id,
            run_id=alignment.get('runId'), run_sha256=alignment.get('runSha256'),
            operation='lyrics-alignment', artifacts=alignment.get('artifacts'),
            schema_sha256=(job.get('policy') or {}).get('schemaSha256', ''), require_current_pointer=False)
        inputs = DisplayReviewInputs(json_bytes(job), attempt, self._editorial_sha(job),
            sha256_file(sources_path), json_bytes(mapping), json_bytes(mapping_identity),
            self._alignment_state_path(video_id), json_bytes({key: analysis_audio[key] for key in ('sha256', 'byteCount', 'durationMs', 'artworkSha256')}), run_matches,
            read_role_text(self._job_dir(video_id) / 'drafts', 'original'), self._owner_sidecar_path(video_id),
            spoken_target_root=self._job_dir(video_id))
        try:
            decision = DisplayReviewService(self._review_operations(), self._review_policy()).validate(inputs)
        except DisplayReviewAuthorityError:
            if not read_only:
                job['displayReview'] = {**pointer, 'result': 'rejected-authority-change',
                    'rejectedEditorialSha256': declaration['editorialSha256']}
                self._save_job(job)
            raise
        projected_raw, display_text = decision.projected_raw, decision.display_text
        projected = json.loads(projected_raw)
        from stemwise_engine.editorial_admission import require_completed_lyrics
        require_completed_lyrics(projected, duration_ms=analysis_audio['durationMs'], endpoint_review=job.get('endpointReview'))
        if not self._companion_projection_current(job, projected, analysis_audio):
            raise FutureSongError('display-review-companion-authority-stale')
        declaration, pointer = json.loads(decision.declaration_json), json.loads(decision.pointer_json)
        alignment_state, mapping_identity = json.loads(decision.alignment_state_json), json.loads(decision.mapping_identity_json)
        alignment, analysis_binding = declaration['alignment'], declaration['analysisAudio']
        sidecar = inputs.sidecar
        if read_only:
            return {"state": job["state"], "displayReviewCheck": "pass"}
        from stemwise_engine.worker_publication import publish_owner_file
        staged = sidecar.with_name(f'.{sidecar.name}.{uuid.uuid4().hex}.pending')
        atomic_write_bytes(staged, projected_raw, root=workflow.owner, workspace_root=workflow.workspace_root)
        publish_owner_file(sidecar, staged, decision.admitted_sidecar_raw, max_bytes=MAX_JSON_BYTES)

        item = {
            "position": 1,
            "sourcePosition": 1,
            "videoId": video_id,
            "url": canonical_url(video_id),
            "title": job.get("title") or video_id,
            "uploader": job.get("uploader") or "Unknown artist",
        }
        outcome = workflow._process_song(pipeline, item)
        if outcome not in {"complete", "skipped"}:
            raise FutureSongError("display-review package refresh failed")
        if job.get('recordingReviewRequired') or authority_path.is_file():
            self._apply_metadata_review(job, authority_review, authority_sources, pipeline,
                admitted_metadata=admitted_metadata)
        packaged_analysis_audio = workflow._completed_analysis_audio(pipeline, video_id)
        if {
            key: packaged_analysis_audio[key]
            for key in ("sha256", "byteCount", "durationMs")
        } != analysis_binding:
            raise FutureSongError("analysis PCM identity changed during display packaging")
        child = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
        document, cover = read_child(child, workflow._runtime_config())
        artwork_sha256 = hashlib.sha256(cover).hexdigest() if cover is not None else None
        if (
            document.get("source", {}).get("videoId") != video_id
            or artwork_sha256 is None
            or packaged_analysis_audio.get("artworkSha256") != artwork_sha256
            or alignment["artifacts"].get("artworkSha256") != artwork_sha256
        ):
            raise ArtworkUnavailableError("packaged artwork changed after lyrics alignment")
        if document.get("lyrics") != projected:
            raise FutureSongError("packaged lyrics differ from the display-review projection")
        analysis = document.get("analysis")
        from stemwise_engine.contract import require_usable_analysis
        try:
            require_usable_analysis(analysis, packaged_analysis_audio['durationMs'])
        except ValueError as error:
            raise FutureSongError("packaged analysis is invalid or unavailable") from error
        if self._editorial_sha(job) != declaration['editorialSha256']:
            raise FutureSongError('Editorial inputs changed during display packaging')

        final_display_sha256 = hashlib.sha256(display_text.encode("utf-8")).hexdigest()
        display_artifacts = {
            "declarationSha256": pointer["declarationSha256"],
            "originalSha256": final_display_sha256,
            "sourcesSha256": declaration["sourcesSha256"],
            "sidecarSha256": sha256_file(sidecar),
            "childSha256": sha256_file(child),
            "artworkSha256": artwork_sha256,
            "mappingArtifactSha256": mapping_identity["mappingArtifactSha256"],
            "alignmentRunSha256": alignment["runSha256"],
        }
        run_sha256, completed_at = workflow.report_store.commit_display_review_run(
            job,
            declaration=declaration,
            declaration_sha256=pointer["declarationSha256"],
            final_display_sha256=final_display_sha256,
            artifacts=display_artifacts,
        )
        generated_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(completed_at))
        report = {
            "formatVersion": 1,
            "generatedAt": generated_at,
            "containsLyrics": projected.get("status") != "unavailable",
            "containsLyricText": False,
            "result": "pass",
            "stage": "display-review",
            "videoId": video_id,
            "policyVersion": editorial.POLICY_VERSION,
            "editorialSha256": declaration["editorialSha256"],
            "sourcesSha256": display_artifacts["sourcesSha256"],
            "sidecarSha256": display_artifacts["sidecarSha256"],
            "childSha256": display_artifacts["childSha256"],
            "artworkSha256": display_artifacts["artworkSha256"],
            "lyricsStatus": projected["status"],
            "trackKinds": [track["kind"] for track in projected["tracks"]],
            "lyricsTiming": workflow_diagnostics._alignment_diagnostics(alignment_state),
            "analysisStatus": analysis["status"],
            "completeToolRunCount": len(analysis["toolRuns"]),
            "retainedAnalysisAudio": analysis_binding,
            "temporaryAudioRetained": True,
            "displayReviewAttemptId": attempt_id,
            "displayReviewDeclarationSha256": pointer["declarationSha256"],
            "displayOriginalSha256": final_display_sha256,
            "mappingSha256": mapping_identity["mappingSha256"],
            "alignmentRunId": alignment["runId"],
            "alignmentRunSha256": alignment["runSha256"],
            "alignmentArtifacts": alignment["artifacts"],
            "displayReviewArtifacts": display_artifacts,
            "runId": attempt_id,
            "runSha256": run_sha256,
        }
        report_root = workflow.report_store.report_root(video_id)
        destination = validate_generated_path(
            report_root,
            report_root / "validation.json",
            workspace_root=workflow.workspace_root,
        )
        atomic_write_json(destination, report)
        job["state"] = "VALIDATED"
        job["displayReview"] = {
            **pointer,
            "result": "accepted-display-only",
            "originalSha256": final_display_sha256,
            "runId": attempt_id,
            "runSha256": run_sha256,
        }
        job["validation"] = {
            "report": workflow._windows_path(destination),
            "reportSha256": sha256_file(destination),
            "editorialSha256": report["editorialSha256"],
            "sourcesSha256": report["sourcesSha256"],
            "sidecarSha256": report["sidecarSha256"],
            "childSha256": report["childSha256"],
            "artworkSha256": report["artworkSha256"],
            "lyricsTiming": report["lyricsTiming"],
            "validatedAt": generated_at,
            "displayReviewAttemptId": attempt_id,
            "displayReviewDeclarationSha256": pointer["declarationSha256"],
            "displayOriginalSha256": final_display_sha256,
            "audioSha256": analysis_binding['sha256'],
            "alignmentRunId": alignment["runId"],
            "alignmentRunSha256": alignment["runSha256"],
            "runId": attempt_id,
            "runSha256": run_sha256,
        }
        job.pop("error", None)
        job.pop("outcome", None)
        workflow._save_job(job)
        workflow._write_task(job)
        return self.summary(job)


    def _is_display_only_validation(self, job: dict[str, Any]) -> bool:
        """Select resources and dispatch together; the display validator still owns admission."""
        display = job.get("displayReview")
        if job.get("state") == "WAITING_FOR_DISPLAY_REVIEW":
            return True
        if job.get("state") not in {"VALIDATION_FAILED", "VALIDATED", "DELIVERED"} or not isinstance(display, dict):
            return False
        if set(display) == {"attemptId", "declarationSha256", "preReviewTextSha256",
                            "alignmentRunId", "alignmentRunSha256"}:
            return True
        return (display.get("result") == "rejected-authority-change"
                and self._editorial_sha(job) == display.get("rejectedEditorialSha256"))

    def _validate_impl(self, video_id: str) -> dict[str, Any]:
        from stemwise_engine.lyrics_alignment import (
            ALIGNMENT_VERSION,
            AlignmentWorkspace,
            snapshot_canonical_plain,
        )

        video_id = validate_video_id(video_id)
        job = self._load_job(video_id)
        self._require_changed_lyrics_content(job)
        if self._is_display_only_validation(job):
            return self._validate_display_review(job)
        endpoint_review = job.get("endpointReview")
        endpoint_pointer_keys = {
            "attemptId",
            "declarationSha256",
            "sourceAlignmentRunId",
            "sourceAlignmentRunSha256",
            "sourceAlignmentStateSha256",
        }
        if job.get("state") in {"LINE_UNRESOLVED", "VALIDATION_FAILED", "VALIDATED"} and (
            isinstance(endpoint_review, dict)
            and endpoint_pointer_keys <= set(endpoint_review)
            and endpoint_review.get("result") != "accepted"
        ):
            return self._validate_endpoint_review(job)
        if job.get("state") == "LINE_UNRESOLVED" and not isinstance(
            endpoint_review, dict
        ):
            try:
                blocker = self.review_construction.endpoint_failure_source(job)
            except LyricsContentBlocker:
                raise
            except FutureSongError:
                blocker = None
            if isinstance(blocker, dict) and blocker.get("reason") == (
                "exhausted-endpoint-cascade"
            ):
                self.review_construction.create_endpoint_review_attempt(job)
                self._save_job(job)
                self._write_task(job)
                return self.summary(job)
        if job.get("state") == "ALIGNING_LYRICS":
            try:
                with exclusive_process_lock(self._alignment_worker_lock_path(video_id)):
                    job = self._load_job(video_id)
                    if job.get("state") == "ALIGNING_LYRICS":
                        job = self._mark_validation_failed_under_worker_lock(
                            video_id,
                            "a previous validation was interrupted; approved editorial text "
                            "was preserved and this Validate invocation is resuming it",
                        )
            except ProcessLockBusyError as error:
                raise FutureSongError(
                    "lyrics timing is still active for this song; poll Status before retrying"
                ) from error
        if job["state"] in {"NEW", "ANALYZING", "ANALYSIS_FAILED", "ANALYSIS_REPLACEMENT_PENDING"}:
            raise FutureSongError(f"job state {job['state']} is not ready for editorial validation")
        authority_outcome, review, sources = self._validate_authority_outcome(job)
        if authority_outcome == "pending":
            return self.summary(job)
        if authority_outcome == "authority-unresolved":
            run_id, run_sha256, report_path = self.report_store.commit_authority_unresolved_run(job, review=review)
            job["state"] = "AUTHORITY_UNRESOLVED"
            job["outcome"] = {
                "code": "authority-unresolved",
                "report": self._windows_path(report_path),
                "reportSha256": sha256_file(report_path),
                "runId": run_id,
                "runSha256": run_sha256,
            }
            job.pop("error", None)
            job.pop("validation", None)
            self._save_job(job)
            self._write_task(job)
            # Source review may resolve this job later; keep its exact completed PCM.
            raise FutureSongOutcomeError(
                "AUTHORITY_UNRESOLVED", "authority-unresolved"
            )
        pipeline = self._pipeline()
        self._require_reviewed_artwork(job, pipeline)
        analysis_audio = self._completed_analysis_audio(pipeline, video_id)
        lyrics, editorial_sha, reviewed_lrc = self._validate_drafts(
            job, review=review, sources=sources
        )
        self._require_recording_review(job, review, sources, analysis_audio)
        self._apply_metadata_review(job, review, sources, pipeline)
        sidecar = self._owner_sidecar_path(video_id)
        atomic_write_json(
            sidecar,
            lyrics,
            root=self.owner,
            workspace_root=self.workspace_root,
        )
        _, canonical_plain_sha256 = snapshot_canonical_plain(
            AlignmentWorkspace(self.root, workspace_root=self.workspace_root),
            video_id,
            read_bounded_regular_bytes(
                sidecar, max_bytes=MAX_JSON_BYTES, root=self.owner
            ),
        )
        performed_structure_review = self._validated_performed_structure_review(
            job,
            review,
            sources,
            canonical_plain_sha256,
        )
        self._write_reviewed_lrc_evidence(
            video_id,
            editorial_sha256=editorial_sha,
            sidecar_sha256=sha256_file(sidecar),
            evidence=reviewed_lrc,
        )
        compiled_originals = [
            track for track in lyrics.get("tracks", []) if track.get("kind") == "original"
        ]
        if len(compiled_originals) != 1:
            raise FutureSongError("compiled lyrics do not contain exactly one original track")
        if compiled_originals[0].get("format") != "plain":
            raise FutureSongError("compiled original must await automatic endpoint refinement")
        original_text = compiled_originals[0].get("text")
        if not isinstance(original_text, str):
            raise FutureSongError("compiled original display authority is invalid")
        alignment_attempt_id = uuid.uuid4().hex
        job["state"] = "ALIGNING_LYRICS"
        job["activeAlignmentAttemptId"] = alignment_attempt_id
        from stemwise_engine.alignment_attempt import AttemptInputs
        job['alignmentAttemptInputs'] = AttemptInputs.capture(alignment_attempt_id, editorial_sha,
            self._job_dir(video_id) / 'sources.json', self.config_path,
            Path(self.config['projectDir']) / 'src').to_record()
        job.pop("displayReview", None)
        job.pop("endpointReview", None)
        job.pop("error", None)
        job.pop("outcome", None)
        self._save_job(job)
        self._write_task(job)
        atomic_write_json(
            self._alignment_state_path(video_id),
            {
                "videoId": video_id,
                "attemptId": alignment_attempt_id,
                "status": "starting",
                "alignmentVersion": ALIGNMENT_VERSION,
                "canonicalPlainSha256": canonical_plain_sha256,
                "updatedAtUnix": int(time.time()),
            },
        )
        lyrics, alignment_state = self._align_future_lyrics(
            video_id,
            alignment_attempt_id,
            analysis_audio["sha256"],
            editorial_sha,
            canonical_plain_sha256,
            analysis_audio["path"],
            performed_structure_review,
        )
        if self._editorial_sha(job) != editorial_sha:
            raise FutureSongError("editorial inputs changed while lyrics were aligned")
        self._require_recording_review(job, review, sources, analysis_audio)
        self._apply_metadata_review(job, review, sources, pipeline, allow_change=False)
        validate_synced_companion_timing(lyrics)
        state_path = self._alignment_state_path(video_id)
        _, mapping_identity = self._lexical_mapping(video_id)
        sources_sha256 = sha256_file(self._job_dir(video_id) / "sources.json")
        sidecar_sha256 = sha256_file(sidecar)
        alignment_artifacts = {
            "sourcesSha256": sources_sha256,
            "sidecarSha256": sidecar_sha256,
            "alignmentCandidateSha256": sha256_file(
                state_path.with_name("candidate.private.lyrics.json")
            ),
            "mappingArtifactSha256": mapping_identity["mappingArtifactSha256"],
            "alignmentStateSha256": sha256_file(state_path),
            "analysisAudioSha256": analysis_audio["sha256"],
            "artworkSha256": analysis_audio["artworkSha256"],
        }
        if alignment_artifacts["alignmentCandidateSha256"] != sidecar_sha256:
            raise FutureSongError("aligned candidate and committed sidecar differ")
        run_sha256 = self.report_store.commit_alignment_run(
            job,
            run_id=alignment_attempt_id,
            editorial_sha256=editorial_sha,
            state=alignment_state,
            status="complete",
            artifacts=alignment_artifacts,
        )
        self.review_construction.create_display_review_attempt(
            job,
            original_text=original_text,
            editorial_sha256=editorial_sha,
            analysis_audio=analysis_audio,
            alignment_state=alignment_state,
            alignment_run_sha256=run_sha256,
            alignment_artifacts=alignment_artifacts,
        )
        self._save_job(job)
        self._write_task(job)
        return self.summary(job)

    def _commit_failed_alignment_attempt(
        self, video_id: str, failure_code: str
    ) -> None:
        failed_job = self._load_job(video_id)
        if isinstance(failed_job.get("displayReview"), dict):
            return
        run_id = failed_job.get("activeAlignmentAttemptId")
        if not isinstance(run_id, str):
            return
        state_path = self._alignment_state_path(video_id)
        state = read_json(state_path) if state_path.is_file() else {}
        if state.get("attemptId") != run_id:
            return
        from stemwise_engine.alignment_attempt import AttemptInputs
        try:
            inputs = AttemptInputs.from_record(failed_job.get('alignmentAttemptInputs'))
        except (TypeError, ValueError):
            # Historical attempts without producing inputs cannot acquire invented provenance.
            return
        if inputs.attempt_id != run_id:
            return
        observed = {}
        try:
            current_editorial = self._editorial_sha(failed_job)
            if current_editorial != inputs.editorial_sha256:
                observed['editorialSha256'] = current_editorial
            current_sources = sha256_file(self._job_dir(video_id) / 'sources.json')
            if current_sources != inputs.sources_sha256:
                observed['sourcesSha256'] = current_sources
        except (OSError, ValueError, FutureSongError):
            observed['currentInputsUnavailable'] = True
        self.report_store.commit_alignment_run(
            failed_job,
            run_id=run_id,
            editorial_sha256=inputs.editorial_sha256,
            state=state,
            status="failed",
            failure_code=failure_code,
            observed_input_changes=observed,
            artifacts={'sourcesSha256': inputs.sources_sha256},
        )

    def validate(self, video_id: str) -> dict[str, Any]:
        video_id = validate_video_id(video_id)
        return self._run_mutating_operation(
            video_id,
            "Validate",
            lambda: self._validate_action(video_id),
        )

    @contextmanager
    def _endpoint_review_audio(self, job):
        retained = self.analysis_temp/job['videoId']
        if (not job.get('capabilityRefresh') or self.source_context is not None
                or retained.exists() or retained.is_symlink()):
            yield None
            return
        from stemwise_engine.original_delivery import delivered_capability_inputs, extract_original
        from stemwise_engine.youtube import decode_analysis_audio, probe_duration_ms
        audio, _, previous, _ = delivered_capability_inputs(self, job)
        with manifested_temporary_directory(self.analysis_temp, prefix='endpoint-publication-',
                owner={'kind': 'endpoint-publication-verification', 'videoId': job['videoId']},
                workspace_root=self.workspace_root) as temporary:
            original, _, _, _ = extract_original(previous, self._runtime_config(), temporary,
                original=job['capabilityRefresh']['previousDelivery']['original'])
            pcm = decode_analysis_audio(original, temporary/'decoded', timeout_seconds=600)
            if (pcm.stat().st_size != audio['byteCount'] or sha256_file(pcm) != audio['sha256']
                    or probe_duration_ms(pcm) != audio['durationMs']):
                raise WorkflowInputError('Published Original cannot reproduce the reviewed analysis PCM',
                    reason_code='review-stale', affected_input='exact endpoint-review analysis PCM',
                    next_action='Restore the exact retained analysis directory from its original or backup. '
                        'If unavailable, keep the existing delivery and explicitly authorize fresh recording '
                        'analysis and endpoint review; the old decision cannot approve changed samples.')
            yield {**audio, 'path': pcm}
            if sha256_file(pcm) != audio['sha256']:
                raise FutureSongError('Endpoint review PCM changed during validation')

    def _validate_action(self, video_id: str) -> dict[str, Any]:
        from stemwise_engine.lyric_content import OWNER_VERIFICATION_FILE
        owner_path = self._job_dir(video_id)/OWNER_VERIFICATION_FILE
        if not owner_path.exists() and not owner_path.is_symlink():
            return self._validate_action_normal(video_id)
        # Retain the actual explicit decision before passive projections read it.
        job = self._load_job(video_id)
        audio = self._owner_prediction_audio_identity(job)
        compiled, original, indices, canonical_sha, _ = self._owner_prediction_inputs(job, audio)
        if job.get('ownerWordPrediction') is not None:
            try:
                self._available_owner_prediction(job, original, audio, validate_reviews=False)
            except (OSError, ValueError, KeyError, TypeError, FutureSongError):
                pass
            else:
                if self._validated_snapshot_is_current(job, require_delivery=job.get('state') == 'DELIVERED'):
                    return self.summary(job)
                if job.get('state') == 'DELIVERED':
                    from stemwise_engine.original_delivery import delivered_capability_inputs
                    job['capabilityRefresh'] = delivered_capability_inputs(self, job)[1]
                return self._validate_available_capabilities(job)
        try:
            result = self._validate_action_normal(video_id)
        except (FutureSongError, ContentEvidenceUnavailable) as error:
            self._raise_if_prediction_stopped(error)
        else:
            if self._load_job(video_id).get('state') in {'VALIDATED', 'DELIVERED'}:
                return result
        # Re-read all authority after the ordinary path; its refusal is not approval.
        return self._validate_owner_prediction(self._load_job(video_id))

    @staticmethod
    def _raise_if_prediction_stopped(error):
        from stemwise_engine.alignment_attempt import SongAttemptBlocked
        from stemwise_engine.provider_operations import raise_if_operation_stopped
        pending, seen = [error], set()
        while pending:
            cause = pending.pop()
            if cause is None or id(cause) in seen:
                continue
            seen.add(id(cause))
            if isinstance(cause, (WorkerRetirementError, SongAttemptBlocked)):
                raise cause
            raise_if_operation_stopped(cause)
            pending.extend((cause.__cause__, cause.__context__))

    def _validate_action_normal(self, video_id: str) -> dict[str, Any]:
        job = self._load_job(video_id)
        endpoint = job.get('endpointReview')
        if (job.get('state') in {'VALIDATED', 'DELIVERED'}
                and isinstance(endpoint, dict) and endpoint.get('result') != 'accepted'):
            attempt = self._endpoint_review_attempt_dir(video_id, endpoint['attemptId'])
            decision = read_bounded_json(attempt/'review.json', max_bytes=MAX_JSON_BYTES, root=attempt)
            if decision.get('outcome') == 'resolved':
                if job.get('state') == 'DELIVERED':
                    from stemwise_engine.original_delivery import delivered_capability_inputs
                    job['capabilityRefresh'] = delivered_capability_inputs(self, job)[1]
                with self._endpoint_review_audio(job) as analysis_audio:
                    self._validate_endpoint_review(job, analysis_audio=analysis_audio)
                return self._validate_available_capabilities(self._load_job(video_id))
        if (job.get('state') == 'WAITING_FOR_DISPLAY_REVIEW' and job.get('capabilityRefresh')
                and isinstance(endpoint, dict) and endpoint.get('result') == 'accepted'
                and job['capabilityRefresh'].get('previousValidation', {}).get('kind') == 'capability-completion'):
            return self._validate_available_capabilities(job)
        if (job.get('state') == 'DELIVERED'
                and self._validated_snapshot_is_current(job, require_delivery=True)):
            self._write_task(job)
            return self.summary(job)
        if (job.get('state') in {'DELIVERED', 'VALIDATED'}
                and (job.get('validation', {}).get('kind') == 'capability-completion'
                    or job.get('displayReview', {}).get('result') == 'accepted-display-only')
                and not self._validated_snapshot_is_current(job)):
            if job.get('state') == 'VALIDATED' and not job.get('capabilityRefresh') and not job.get('artworkRepair'):
                return self._validate_available_capabilities(job)
            from stemwise_engine.original_delivery import delivered_capability_inputs
            from stemwise_engine.editorial_admission import available_lyrics
            audio, refresh, _, published = delivered_capability_inputs(self, job)
            projection, capabilities = available_lyrics(self, job, audio)
            previous_original = next((track for track in published['lyrics']['tracks'] if track['kind'] == 'original'), None)
            current_original = next((track for track in projection['tracks'] if track['kind'] == 'original'), None)
            if (capabilities['content'] not in {'admitted', 'partial'}
                    or previous_original and previous_original.get('format') == 'synced'
                    and (current_original is None or current_original.get('format') not in {'synced', 'regional'})):
                raise FutureSongError('No newly established lyric content is available')
            job['capabilityRefresh'] = refresh
            self._save_job(job)
            return self._validate_available_capabilities(job)
        if self._validated_snapshot_is_current(job) or job.get('state') in TERMINAL_STATES:
            return self._validate_enhancements_action(video_id)
        audio = self._completed_analysis_audio(self._pipeline(), video_id)
        if isinstance(job.get('displayReview'), dict):
            from stemwise_engine.editorial_admission import available_lyrics, complete_original_word_timing
            projection, _ = available_lyrics(self, job, audio)
            if complete_original_word_timing(projection) and projection['status'] != 'complete':
                # Missing companions cannot restart or overwrite proven Original timing.
                return self._validate_available_capabilities(job)
        ledger = self._song_ledger(video_id)
        ledger.record['analysisInputBytes'] = audio['byteCount']
        ledger.save()
        from stemwise_engine.alignment_attempt import SongAttemptBlocked
        primary = None
        try:
            result = self._validate_enhancements_action(video_id)
            if self._load_job(video_id).get('state') == 'VALIDATED':
                return result
        except (FutureSongError, ContentEvidenceUnavailable, SongAttemptBlocked) as error:
            self._raise_if_prediction_stopped(error)
            primary = error
        try:
            return self._validate_available_capabilities(self._load_job(video_id))
        except (FutureSongError, ContentEvidenceUnavailable, SongAttemptBlocked) as error:
            self._raise_if_prediction_stopped(error)
            fallback = error
        # Raising inside the fallback handler would replace the primary error's context.
        if primary is not None:
            raise primary
        raise fallback

    def _capability_input_sha(self, video_id):
        job_dir = self._job_dir(video_id)
        files = [job_dir/'sources.json', job_dir/'agent-review.json']
        draft = job_dir/'drafts'
        if draft.is_dir():
            files.extend(draft/name for name in ROLE_FILES if (draft/name).is_file())
        state = self._alignment_state_path(video_id)
        files.extend([state, state.with_name('candidate.private.lyrics.json')])
        job = self._load_job(video_id)
        from stemwise_engine.lyric_content import OWNER_VERIFICATION_FILE
        files.append(job_dir/OWNER_VERIFICATION_FILE)
        prediction = job.get('ownerWordPrediction')
        if isinstance(prediction, dict):
            prediction_root = self._alignment_state_path(video_id).parent
            for kind in ('candidate', 'mapping', 'forced', 'admission', 'vowelReleaseEvidence'):
                ref = prediction.get(kind)
                if isinstance(ref, dict) and isinstance(ref.get('path'), str):
                    files.append(validate_generated_path(prediction_root,
                        prediction_root/ref['path'], workspace_root=self.workspace_root))
        endpoint = job.get('endpointReview')
        if isinstance(endpoint, dict) and endpoint.get('result') != 'accepted':
            attempt = self._endpoint_review_attempt_dir(video_id, endpoint['attemptId'])
            files.extend((attempt/'declaration.json', attempt/'review.json'))
        identity = {str(path.relative_to(self.root)): sha256_file(path)
            for path in files if path.is_file()}
        from stemwise_engine.spoken_targets import input_snapshot
        identity['spokenTargets'] = input_snapshot(job_dir)
        if prediction is not None:
            identity['ownerWordPrediction'] = prediction
            try:
                from stemwise_engine.lyrics_align_pc import load_owner_word_prediction
                from stemwise_engine.lyrics_alignment import AlignmentWorkspace, _physical_lines
                audio = self._pipeline().load_checkpoint(video_id)['originalAudio']
                compiled, _, _ = self._preflight_drafts(job, roles=frozenset({'original'}))
                original = next(track for track in compiled['tracks'] if track['kind'] == 'original')
                indices = tuple(i for i, line in enumerate(_physical_lines(original['text'])) if line.strip())
                load_owner_word_prediction(Path(self.config['projectDir']),
                    AlignmentWorkspace(self.root, workspace_root=self.workspace_root), video_id, prediction,
                    original=original, indices=indices, canonical_sha=hashlib.sha256(json_bytes(compiled)).hexdigest(),
                    audio_sha=audio['sha256'])
            except (OSError, ValueError, KeyError, TypeError, FutureSongError) as error:
                identity['ownerPredictionCurrent'] = {'state': 'invalid', 'reason': type(error).__name__}
            else:
                identity['ownerPredictionCurrent'] = {'state': 'current'}
        review_path = job_dir/'agent-review.json'
        if review_path.is_file():
            from stemwise_engine.companion_review import review_evidence_snapshot
            try:
                review = read_bounded_json(review_path, max_bytes=MAX_JSON_BYTES, root=job_dir)
            except ValueError:
                identity['companionReviewEvidence'] = {'state': 'invalid-review'}
            else:
                identity['companionReviewEvidence'] = review_evidence_snapshot(review, job_dir)
        return hashlib.sha256(json_bytes(identity)).hexdigest()

    def _owner_verification_inputs(self, job):
        """Expose current input hashes without writing or implying an owner decision."""
        from stemwise_engine.lyrics_intake import read_recording_draft
        from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, lexical_mapping_identity, _physical_lines
        from stemwise_engine.spoken_targets import load
        try:
            compiled, _, _ = self._preflight_drafts(job, roles=frozenset({'original'}))
            original = next(track for track in compiled['tracks'] if track['kind'] == 'original')
            indices = tuple(i for i, line in enumerate(_physical_lines(original['text'])) if line.strip())
            audio = self._pipeline().load_checkpoint(job['videoId'])['originalAudio']
            _, draft_sha, _, metadata_sha = read_recording_draft(self._job_dir(job['videoId']))
            mapping = build_lexical_occurrence_mapping(job['videoId'], original, indices)
            spoken = load(self._job_dir(job['videoId']), job['videoId'], original, indices, audio['sha256'])
            return {'path': 'owner-verified-lyrics.json', 'requiredDecision': 'owner-verified-complete-performed-lyrics',
                'requiredTiming': 'allow-complete-predicted-words', 'template': {
                    'formatVersion': 1, 'videoId': job['videoId'], 'audioSha256': audio['sha256'],
                    'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                    'mappingSha256': lexical_mapping_identity(mapping)['mappingSha256'],
                    'spokenTargets': spoken.reference if spoken else None, 'reviewer': 'REPLACE_WITH_REVIEWER',
                    'completedAt': '', 'decision': 'pending', 'timing': 'pending', 'rationale': ''}}
        except (OSError, ValueError, KeyError, TypeError, FutureSongError, StopIteration):
            return None

    def _owner_prediction_audio_identity(self, job):
        if job.get('capabilityRefresh') or (not (self.analysis_temp/job['videoId']).exists()
                and (job.get('delivery') or job.get('artworkRepair'))):
            from stemwise_engine.original_delivery import delivered_capability_inputs
            return delivered_capability_inputs(self, job)[0]
        return self._completed_analysis_audio(self._pipeline(), job['videoId'])

    def _owner_prediction_inputs(self, job, audio):
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace, _physical_lines
        from stemwise_engine.lyric_content import owner_workspace_admission
        compiled, _, _ = self._preflight_drafts(job, roles=frozenset({'original'}))
        original = next(track for track in compiled['tracks'] if track['kind'] == 'original')
        indices = tuple(i for i, line in enumerate(_physical_lines(original['text'])) if line.strip())
        canonical_sha = hashlib.sha256(json_bytes(compiled)).hexdigest()
        workspace = AlignmentWorkspace(self.root, workspace_root=self.workspace_root)
        admission = owner_workspace_admission(workspace, job['videoId'], original, indices,
            canonical_sha, audio['sha256'])
        if admission is None:
            raise ContentEvidenceUnavailable()
        return compiled, original, indices, canonical_sha, workspace

    @contextmanager
    def _owner_prediction_audio(self, job, audio):
        retained = self.analysis_temp/job['videoId']/'analysis.wav'
        if retained.is_file():
            yield retained, self.analysis_temp
            return
        from stemwise_engine.original_delivery import delivered_capability_inputs, extract_original
        from stemwise_engine.youtube import decode_analysis_audio, probe_duration_ms
        native = self.source_context is not None
        if not native or job.get('delivery') or job.get('artworkRepair'):
            _, refresh, previous, _ = delivered_capability_inputs(self, job)
            job['capabilityRefresh'] = refresh
        with manifested_temporary_directory(self.analysis_temp, prefix='owner-prediction-',
                owner={'kind': 'owner-word-prediction', 'videoId': job['videoId']},
                workspace_root=self.workspace_root) as temporary:
            if native:
                self.source.require_job(job['videoId'], job)
                source = self.recording.audio
                if sha256_file(source) != self.recording.identity['sha256']:
                    raise FutureSongError('Selected native recording changed before owner prediction')
            else:
                source, _, _, _ = extract_original(previous, self._runtime_config(), temporary,
                    original=refresh['previousDelivery']['original'])
            pcm = decode_analysis_audio(source, temporary/job['videoId'], timeout_seconds=600)
            if native and sha256_file(source) != self.recording.identity['sha256']:
                raise FutureSongError('Selected native recording changed during owner prediction decoding')
            if (pcm.stat().st_size != audio['byteCount'] or sha256_file(pcm) != audio['sha256']
                    or probe_duration_ms(pcm) != audio['durationMs']):
                raise WorkflowInputError('Delivered Original cannot reproduce the bound analysis PCM',
                    reason_code='review-stale', affected_input='owner-prediction recording PCM',
                    next_action='Preserve the prior delivery and restore its exact recording analysis.')
            yield pcm, temporary

    def _validate_owner_prediction(self, job):
        from stemwise_engine.lyrics_alignment import snapshot_canonical_plain
        from stemwise_engine.lyrics_align_pc import load_owner_word_prediction
        from stemwise_engine.worker_protocol import require_worker_success
        audio = self._owner_prediction_audio_identity(job)
        compiled, original, indices, canonical_sha, workspace = self._owner_prediction_inputs(job, audio)
        self._owner_prediction_reviews(job, audio, apply_metadata=True)
        snapshot_canonical_plain(workspace, job['videoId'], json_bytes(compiled))
        project = Path(self.config['projectDir'])
        with self._owner_prediction_audio(job, audio) as (pcm, analysis_root):
            command = [str(environment_python('lyrics', project)), '-m', 'stemwise_engine.lyrics_align_pc',
                '--config', str(self.config_path), 'owner-word-prediction',
                option_safe_video_id_argument(job['videoId']), f'--future-root={self.root}',
                f'--analysis-root={analysis_root}', f'--analysis-audio={pcm}',
                f'--expected-mix-sha256={audio["sha256"]}', f'--canonical-plain-sha256={canonical_sha}']
            completed = self._run_primary_worker('owner-word-prediction',
                {'audioSha256': audio['sha256'], 'canonicalPlainSha256': canonical_sha,
                 'ownerVerificationSha256': sha256_file(self._job_dir(job['videoId'])/'owner-verified-lyrics.json')}, command,
                text=True, capture_output=True, check=False, timeout=None,
                env=self._alignment_worker_environment())
            require_worker_success(completed, code='owner-word-prediction-failed',
                diagnostic_path=workspace.job_dir(job['videoId'])/'owner-word-prediction.failure.private.txt')
            response = parse_strict_json_bytes(completed.stdout.encode('utf-8'))
            load_owner_word_prediction(project, workspace, job['videoId'], response,
                original=original, indices=indices, canonical_sha=canonical_sha, audio_sha=audio['sha256'])
        job['ownerWordPrediction'] = response
        job.pop('endpointReview', None)
        job.pop('displayReview', None)
        self._save_job(job)
        return self._validate_available_capabilities(job)

    def _owner_prediction_reviews(self, job, audio, *, apply_metadata=False):
        """Check recording/package authority without treating lyric lookup as a veto."""
        self._require_selected_audio(job['videoId'])
        directory = self._job_dir(job['videoId'])
        review = read_bounded_json(directory/'agent-review.json', max_bytes=MAX_JSON_BYTES, root=directory)
        sources = read_bounded_json(directory/'sources.json', max_bytes=MAX_JSON_BYTES, root=directory)
        if not isinstance(review, dict) or not isinstance(sources, dict) or sources.get('videoId') != job['videoId']:
            raise FutureSongError('owner prediction recording review inputs are invalid')
        pipeline = self._pipeline()
        self._require_reviewed_artwork(job, pipeline)
        if job.get('recordingReviewRequired') or 'recordingReview' in review:
            self._require_recording_review(job, review, sources, audio)
        self._apply_metadata_review(job, review, sources, pipeline, allow_change=apply_metadata)
        return review, sources

    def _owner_prediction_admission(self, job, original, indices, canonical_sha, audio):
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        from stemwise_engine.lyric_content import require_content
        from stemwise_engine.common import parse_strict_json_bytes
        workspace = AlignmentWorkspace(self.root, workspace_root=self.workspace_root)
        root = workspace.job_dir(job['videoId'])
        try:
            response = job['ownerWordPrediction']
            if (not isinstance(response, dict) or response.get('canonicalPlainSha256') != canonical_sha
                    or response.get('audioSha256') != audio['sha256']):
                raise ValueError('owner prediction target binding changed')
            ref = response['admission']
            if (not isinstance(ref, dict) or set(ref) != {'path', 'sha256'}
                    or not isinstance(ref['path'], str) or not isinstance(ref['sha256'], str)):
                raise ValueError('owner prediction admission reference is invalid')
            raw = read_bounded_regular_bytes(root/ref['path'], max_bytes=MAX_JSON_BYTES, root=root)
            if hashlib.sha256(raw).hexdigest() != ref['sha256']:
                raise ValueError('owner prediction admission changed')
            admission = require_content(parse_strict_json_bytes(raw), original, indices, canonical_sha,
                audio['sha256'], root, adjudication_job_dir=self._job_dir(job['videoId']))
            if admission.owner_verification is None:
                raise ValueError('owner prediction has no current owner verification')
            return admission
        except (LyricsContentBlocker, ContentEvidenceUnavailable):
            raise
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise ContentEvidenceUnavailable('content-evidence-changed') from error

    def _available_owner_prediction(self, job, original, audio, *, validate_reviews=True):
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace, _physical_lines
        from stemwise_engine.lyrics_align_pc import load_owner_word_prediction
        compiled, _, _ = self._preflight_drafts(job, roles=frozenset({'original'}))
        canonical_sha = hashlib.sha256(json_bytes(compiled)).hexdigest()
        indices = tuple(i for i, line in enumerate(_physical_lines(original['text'])) if line.strip())
        workspace = AlignmentWorkspace(self.root, workspace_root=self.workspace_root)
        candidate, _, _, _ = load_owner_word_prediction(Path(self.config['projectDir']), workspace,
            job['videoId'], job['ownerWordPrediction'], original=original, indices=indices,
            canonical_sha=canonical_sha, audio_sha=audio['sha256'])
        if validate_reviews:
            self._owner_prediction_reviews(job, audio)
        track = next(track for track in candidate['tracks'] if track['kind'] == 'original')
        return track, {'kind': 'owner-predicted-words', **copy.deepcopy(job['ownerWordPrediction'])}

    def _available_original_timing(self, job, original, audio):
        """Reuse an immutable supported alignment under current original-only authority."""
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        from stemwise_engine.workflow_review import DisplayReviewInputs, DisplayReviewService
        try:
            if job.get('ownerWordPrediction') is not None:
                return self._available_owner_prediction(job, original, audio)
            video_id = job['videoId']
            pointer = job['displayReview']
            attempt = self._display_review_attempt_dir(video_id, pointer['attemptId'])
            state_path = self._alignment_state_path(video_id)
            state = read_bounded_json(state_path, max_bytes=MAX_JSON_BYTES, root=state_path.parent)
            endpoint = state.get('endpointReview')
            if endpoint is not None:
                endpoint_pointer = job['endpointReview']
                endpoint_attempt = self._endpoint_review_attempt_dir(video_id, endpoint_pointer['attemptId'])
                decision_raw = read_bounded_regular_bytes(endpoint_attempt/'review.json', max_bytes=MAX_JSON_BYTES, root=endpoint_attempt)
                if (endpoint_pointer.get('result') != 'accepted'
                        or any(endpoint_pointer.get(key) != endpoint[key] for key in
                            ('declarationSha256', 'reviewSha256', 'sourceAlignmentRunId', 'sourceAlignmentRunSha256'))
                        or sha256_file(endpoint_attempt/'declaration.json') != endpoint['declarationSha256']
                        or hashlib.sha256(decision_raw).hexdigest() != endpoint['reviewSha256']
                        or parse_strict_json_bytes(decision_raw).get('outcome') != 'resolved'):
                    return None
            candidate = state_path.with_name('candidate.private.lyrics.json')
            mapping, mapping_identity = self._lexical_mapping(video_id)
            sources_sha = sha256_file(self._job_dir(video_id)/'sources.json')
            child = self.runtime/'children'/f'{video_id}.stemwise.zip'
            document, cover = read_child(child, self._runtime_config())
            artwork_sha = hashlib.sha256(cover).hexdigest()
            repair = self._validated_artwork_lineage(job, child_document=document,
                child_sha256=sha256_file(child), artwork_sha256=artwork_sha)
            run_path = self.report_store.report_root(video_id)/'runs'/pointer['alignmentRunId']/'run.json'
            run = read_bounded_json(run_path, max_bytes=MAX_JSON_BYTES, root=run_path.parent)
            if repair:
                artwork_sha = run['artifacts']['artworkSha256']
            artifacts = {'sourcesSha256': sources_sha, 'sidecarSha256': sha256_file(candidate),
                'alignmentCandidateSha256': sha256_file(candidate),
                'mappingArtifactSha256': mapping_identity['mappingArtifactSha256'],
                'alignmentStateSha256': sha256_file(state_path), 'analysisAudioSha256': audio['sha256'],
                'artworkSha256': artwork_sha}
            if not self.report_store.immutable_run_matches(video_id, run_id=pointer['alignmentRunId'],
                    run_sha256=pointer['alignmentRunSha256'], operation='lyrics-alignment', artifacts=artifacts,
                    schema_sha256=job['policy']['schemaSha256'], require_current_pointer=False):
                return None
            current_key = self.report_store.alignment_checkpoint_key(job,
                editorial_sha256=self._capability_input_sha(video_id), state=state)
            frozen_key = run['checkpointKey']
            for key in ('helpers', 'models', 'parameters', 'policies', 'audio'):
                current, frozen = copy.deepcopy(current_key.get(key)), copy.deepcopy(frozen_key.get(key))
                if key == 'audio':
                    for value in (current, frozen):
                        if isinstance(value, dict) and isinstance(value.get('analysisOriginal'), dict):
                            value['analysisOriginal'].pop('retained', None)
                if current != frozen:
                    return None
            workspace = AlignmentWorkspace(self.root, workspace_root=self.workspace_root)
            canonical = workspace.current_backup_path(video_id)
            canonical_raw = read_bounded_regular_bytes(canonical, max_bytes=MAX_JSON_BYTES,
                root=workspace.canonical_plain)
            inputs = DisplayReviewInputs(json_bytes(job), attempt, self._capability_input_sha(video_id),
                sources_sha, json_bytes(mapping), json_bytes(mapping_identity), state_path,
                json_bytes({**{key: audio[key] for key in ('sha256', 'byteCount', 'durationMs')},
                    'artworkSha256': artwork_sha}), True, original['text'], candidate,
                current_original_json=json_bytes(original), canonical_plain_raw=canonical_raw,
                spoken_target_root=self._job_dir(video_id))
            decision = DisplayReviewService(self._review_operations(), self._review_policy()).validate(inputs)
            if pointer.get('result') == 'accepted-display-only':
                display_run_path = self.report_store.report_root(video_id)/'runs'/pointer['runId']/'run.json'
                display_run = read_bounded_json(display_run_path, max_bytes=MAX_JSON_BYTES, root=display_run_path.parent)
                display_artifacts = display_run['artifacts']
                expected = {'declarationSha256': pointer['declarationSha256'],
                    'originalSha256': pointer['originalSha256'], 'sourcesSha256': sources_sha,
                    'sidecarSha256': hashlib.sha256(decision.projected_raw).hexdigest(),
                    'childSha256': display_artifacts['childSha256'], 'artworkSha256': artwork_sha,
                    'mappingArtifactSha256': mapping_identity['mappingArtifactSha256'],
                    'alignmentRunSha256': pointer['alignmentRunSha256']}
                if not self.report_store.immutable_run_matches(video_id, run_id=pointer['runId'],
                        run_sha256=pointer['runSha256'], operation='display-review', artifacts=expected,
                        schema_sha256=job['policy']['schemaSha256'], require_current_pointer=False):
                    return None
            synced = next(track for track in json.loads(decision.projected_raw)['tracks'] if track['kind'] == 'original')
            return synced, {'runId': pointer['alignmentRunId'], 'runSha256': pointer['alignmentRunSha256'],
                'candidateSha256': artifacts['alignmentCandidateSha256']}
        except (OSError, ValueError, TypeError, KeyError, FutureSongError, StopIteration):
            return None

    def _capability_validation_paths(self, video_id):
        pipeline = self._pipeline()
        report = self.report_store.report_root(video_id)
        return (pipeline.child_path(video_id), pipeline.checkpoint_path(video_id),
            self._owner_sidecar_path(video_id), self._job_path(video_id),
            report/'validation.json', report_control_path(report, 'current-run.json'))

    def _recover_capability_validation(self, video_id):
        directory = validate_generated_path(self._job_dir(video_id),
            self._job_dir(video_id)/'capability-validation', workspace_root=self.workspace_root)
        owner = {'kind': 'capability-validation', 'videoId': video_id}
        if manifested_cleanup_receipt(directory).exists():
            remove_manifested_directory(self._job_dir(video_id), directory, owner=owner,
                workspace_root=self.workspace_root)
            return
        if directory.exists():
            manifest = read_bounded_json(directory/GENERATED_MANIFEST_NAME, max_bytes=16384, root=directory)
            if manifest != {'manifestVersion': 1, 'owner': owner, 'generatedPaths': ['.']}:
                raise FutureSongError('capability validation transaction owner differs')
            journal = directory/'pending.json'
            if journal.exists():
                record = read_bounded_json(journal, max_bytes=MAX_JSON_BYTES, root=directory)
                paths = self._capability_validation_paths(video_id)
                if (record.get('videoId') != video_id or record.get('phase') not in {'prepared', 'committed'}
                        or len(record.get('previous', ())) != len(paths)):
                    raise FutureSongError('capability validation transaction is invalid')
                if record['phase'] == 'prepared':
                    self._pipeline()._recover_analysis_replacement(video_id)
                    previous = []
                    for index, digest in enumerate(record['previous']):
                        raw = (read_bounded_regular_bytes(directory/str(index),
                            max_bytes=int(self.config['childZipMaxBytes']) if index == 0 else MAX_JSON_BYTES,
                            root=directory) if digest is not None else None)
                        if raw is not None and hashlib.sha256(raw).hexdigest() != digest:
                            raise FutureSongError('capability validation rollback differs')
                        previous.append(raw)
                    for path, raw in zip(paths, previous):
                        if raw is None:
                            path.unlink(missing_ok=True)
                        else:
                            atomic_write_bytes(path, raw, root=path.parent, workspace_root=self.workspace_root)
                    atomic_write_json(journal, {**record, 'phase': 'committed'})
        if directory.exists() or manifested_cleanup_receipt(directory).exists():
            remove_manifested_directory(self._job_dir(video_id), directory, owner=owner,
                workspace_root=self.workspace_root)

    def _validate_available_capabilities(self, job):
        video_id = job['videoId']
        self._recover_capability_validation(video_id)
        directory = self._job_dir(video_id)/'capability-validation'
        create_manifested_directory(directory.parent, directory,
            owner={'kind': 'capability-validation', 'videoId': video_id}, workspace_root=self.workspace_root)
        previous = []
        for index, path in enumerate(self._capability_validation_paths(video_id)):
            raw = (read_bounded_regular_bytes(path,
                   max_bytes=int(self.config['childZipMaxBytes']) if index == 0 else MAX_JSON_BYTES, root=path.parent)
                   if path.exists() else None)
            previous.append(hashlib.sha256(raw).hexdigest() if raw is not None else None)
            if raw is not None:
                atomic_write_bytes(directory/str(index), raw)
        record = {'videoId': video_id, 'phase': 'prepared', 'previous': previous}
        atomic_write_json(directory/'pending.json', record)
        try:
            if job.get('capabilityRefresh') and job.get('artworkRepair'):
                job['capabilityRefresh']['previousArtworkRepair'] = job.pop('artworkRepair')
            result = self._validate_available_capabilities_action(job)
        except BaseException:
            self._recover_capability_validation(video_id)
            raise
        atomic_write_json(directory/'pending.json', {**record, 'phase': 'committed'})
        self._recover_capability_validation(video_id)
        return result

    def _validate_available_capabilities_action(self, job):
        """Require complete Original word timing and admit companion roles independently."""
        from stemwise_engine.editorial_admission import available_lyrics
        from stemwise_engine.contract import require_usable_analysis
        video_id = job['videoId']
        pipeline = self._pipeline()
        if job.get('capabilityRefresh'):
            from stemwise_engine.original_delivery import delivered_capability_inputs
            audio = delivered_capability_inputs(self, job)[0]
        else:
            audio = self._completed_analysis_audio(pipeline, video_id)
        ledger = self._song_ledger(video_id)
        ledger.record['analysisInputBytes'] = audio['byteCount']
        ledger.save()
        input_sha = self._capability_input_sha(video_id)
        review_path = self._job_dir(video_id)/'agent-review.json'
        if review_path.is_file():
            review = read_bounded_json(review_path, max_bytes=MAX_JSON_BYTES, root=review_path.parent)
            if job.get('ownerWordPrediction') is not None:
                self._owner_prediction_reviews(job, audio, apply_metadata=True)
            elif isinstance(review.get('metadataReview'), dict) and review['metadataReview'].get('recordingIdentityConfirmed') is True:
                outcome, review, sources = self._validate_authority_outcome(job)
                if outcome == 'resolved':
                    self._require_recording_review(job, review, sources, audio)
                    self._apply_metadata_review(job, review, sources, pipeline)
        child = pipeline.child_path(video_id)
        document, cover = read_child(child, self._runtime_config())
        if cover is None or hashlib.sha256(cover).hexdigest() != audio['artworkSha256']:
            raise ArtworkUnavailableError('mandatory-artwork-invalid')
        require_usable_analysis(document['analysis'], audio['durationMs'])
        blockers = []
        projection, capabilities = available_lyrics(self, job, audio, on_blocker=blockers.append)
        from stemwise_engine.editorial_admission import require_completed_lyrics
        try:
            require_completed_lyrics(projection, duration_ms=audio['durationMs'], endpoint_review=job.get('endpointReview'))
        except WorkflowInputError as error:
            if blockers:
                raise blockers[0]
            raise
        if job.get('capabilityRefresh'):
            prior = delivered_capability_inputs(self, job)[3]
            prior_original = next((track for track in prior['lyrics']['tracks'] if track['kind'] == 'original'), None)
            current_original = next((track for track in projection['tracks'] if track['kind'] == 'original'), None)
            if prior_original and prior_original.get('format') == 'synced' and (
                    current_original is None or current_original.get('format') not in {'synced', 'regional'}):
                raise FutureSongError('Capability refresh cannot discard supported Original timing')
        if (self._is_display_only_validation(job)
                and job.get('validation', {}).get('kind') == 'capability-completion'
                and capabilities.get('timing') not in {'synced', 'estimated'}):
            raise FutureSongError('Retained display review is stale or changes lyric authority')
        sidecar = self._owner_sidecar_path(video_id)
        projected_raw = json_bytes(projection, pretty=True)
        atomic_write_bytes(sidecar, projected_raw, root=self.owner, workspace_root=self.workspace_root)
        if document.get('lyrics') != projection:
            pipeline._refresh_lyrics_only(video_id, pipeline.load_checkpoint(video_id), projection,
                                           hashlib.sha256(projected_raw).hexdigest())
        published, published_cover = read_child(child, self._runtime_config())
        if (published['lyrics'] != projection or published_cover != cover
                or (delivered_capability_inputs(self, job)[0]['sha256'] if job.get('capabilityRefresh')
                    else self._completed_analysis_audio(pipeline, video_id)['sha256']) != audio['sha256']
                or self._capability_input_sha(video_id) != input_sha):
            raise FutureSongError('capability-package-readback-differs')
        artifacts = {'inputSha256': input_sha, 'sidecarSha256': sha256_file(sidecar),
            'childSha256': sha256_file(child), 'artworkSha256': hashlib.sha256(cover).hexdigest(),
            'audioSha256': audio['sha256'], 'lyricsSha256': hashlib.sha256(json_bytes(projection)).hexdigest()}
        schema_sha = job['policy']['schemaSha256']
        checkpoint_key = {'artifacts': artifacts, 'capabilities': capabilities,
            'policy': job['policy'], 'configurationSha256': sha256_file(self.config_path)}
        checkpoint_raw = json.dumps(checkpoint_key, ensure_ascii=True, allow_nan=False,
            sort_keys=True, separators=(',', ':')).encode('utf-8')
        run_id = hashlib.sha256(checkpoint_raw).hexdigest()[:32]
        record = {'reportVersion': PRIVATE_REPORT_VERSION, 'videoId': video_id, 'runId': run_id,
            'operation': 'capability-completion', 'status': 'complete', 'artifacts': artifacts,
            'checkpointKey': checkpoint_key, 'checkpointKeySha256': hashlib.sha256(checkpoint_raw).hexdigest(),
            'schemaValidation': {'result': 'pass', 'externalFormatVersion': 2, 'schemaSha256': schema_sha}}
        report_root = self.report_store.ensure_report_identity(video_id)
        run_sha = self.report_store.store.commit(report_root, record)
        report = {'kind': 'capability-completion', 'videoId': video_id, 'generatedAt': utc_now(),
            'artifacts': artifacts, 'capabilities': capabilities, 'lyricsStatus': projection['status'],
            'trackKinds': [track['kind'] for track in projection['tracks']],
            'retainedAnalysisAudio': {key: audio[key] for key in ('sha256', 'byteCount', 'durationMs')},
            'runId': run_id, 'runSha256': run_sha}
        report_path = report_root/'validation.json'
        atomic_write_json(report_path, report)
        job['validation'] = {'kind': 'capability-completion', 'report': self._windows_path(report_path),
            'reportSha256': sha256_file(report_path), **artifacts, 'runId': run_id, 'runSha256': run_sha,
            'validatedAt': report['generatedAt'], 'lyricsStatus': projection['status'], 'capabilities': capabilities}
        job['state'] = 'VALIDATED'
        job.pop('error', None)
        job.pop('outcome', None)
        self._save_job(job)
        self._write_task(job)
        return self.summary(job)

    def _capability_snapshot_current(self, job, *, require_delivery=False,
            allow_finalization_pointer=False, allow_pending_delivery_cleanup=False, delivery_source=None):
        from stemwise_engine.editorial_admission import available_lyrics
        from stemwise_engine.workflow_review import finalization_pointer_matches, finalized_delivery_matches
        try:
            video_id = job['videoId']
            validation = job['validation']
            report_path = self.report_store.report_root(video_id)/'validation.json'
            if sha256_file(report_path) != validation['reportSha256']:
                return False
            report = read_bounded_json(report_path, max_bytes=MAX_JSON_BYTES, root=report_path.parent)
            if (validation.get('report') != self._windows_path(report_path)
                    or validation.get('runId') != report.get('runId')
                    or validation.get('runSha256') != report.get('runSha256')
                    or validation.get('validatedAt') != report.get('generatedAt')):
                return False
            audio = report['retainedAnalysisAudio']
            child = self.runtime/'children'/f'{video_id}.stemwise.zip'
            document, cover = read_child(child, self._runtime_config())
            current_child_sha = sha256_file(child)
            current_artwork_sha = hashlib.sha256(cover).hexdigest()
            repair = self._validated_artwork_repair(job, child_document=document,
                child_sha256=current_child_sha, artwork_sha256=current_artwork_sha,
                require_current_pointer=False)
            projection, capabilities = available_lyrics(self, job, audio)
            from stemwise_engine.editorial_admission import require_completed_lyrics
            require_completed_lyrics(projection, duration_ms=audio['durationMs'], endpoint_review=job.get('endpointReview'))
            artifacts = {'inputSha256': self._capability_input_sha(video_id),
                'sidecarSha256': sha256_file(self._owner_sidecar_path(video_id)),
                'childSha256': repair['validationChildSha256'] if repair else current_child_sha,
                'artworkSha256': repair['validationArtworkSha256'] if repair else current_artwork_sha,
                'audioSha256': audio['sha256'], 'lyricsSha256': hashlib.sha256(json_bytes(projection)).hexdigest()}
            if (document['lyrics'] != projection or capabilities != report['capabilities']
                    or report['artifacts'] != artifacts or any(validation.get(key) != value for key, value in artifacts.items())
                    or not self.report_store.immutable_run_matches(video_id, run_id=report['runId'], run_sha256=report['runSha256'],
                        operation='capability-completion', artifacts=artifacts,
                        schema_sha256=job['policy']['schemaSha256'], require_current_pointer=False)):
                return False
            schema_sha = job['policy']['schemaSha256']
            if require_delivery:
                temporary = self.analysis_temp/video_id
                if (job['state'] != 'DELIVERED' or (temporary.exists() or temporary.is_symlink())
                        and not allow_pending_delivery_cleanup
                        and not self._has_pending_endpoint_review(job)
                        and not (isinstance(job.get('recordingEvidenceInputs'), dict)
                            and job.get('validation', {}).get('lyricsStatus') in {'partial', 'unavailable'})):
                    return False
                if (temporary.exists() or temporary.is_symlink()) and not allow_pending_delivery_cleanup:
                    current = self._completed_analysis_audio(self._pipeline(), video_id)
                    if any(current.get(key) != value for key, value in audio.items()):
                        return False
                return finalized_delivery_matches(self._snapshot_context(), job, child, current_child_sha,
                    schema_sha, delivery_source=delivery_source)
            else:
                owner = repair or report
                owner_artifacts = ({key: value for key, value in repair.items()
                    if key not in {'runId', 'runSha256', 'repairedAt'}} if repair else artifacts)
                if not self.report_store.immutable_run_matches(video_id, run_id=owner['runId'],
                        run_sha256=owner['runSha256'], operation='artwork-repair' if repair else 'capability-completion',
                        artifacts=owner_artifacts, schema_sha256=schema_sha, require_current_pointer=True):
                    if (not allow_finalization_pointer or not finalization_pointer_matches(
                            self._snapshot_context(), job, child, current_child_sha, schema_sha)):
                        return False
                if job.get('capabilityRefresh') or repair:
                    from stemwise_engine.original_delivery import delivered_capability_inputs
                    current = delivered_capability_inputs(self, job)[0]
                else:
                    current = self._completed_analysis_audio(self._pipeline(), video_id)
                if {key: current[key] for key in audio} != audio:
                    return False
            return True
        except (OSError, ValueError, KeyError, TypeError, FutureSongError):
            return False

    def _validate_enhancements_action(self, video_id: str) -> dict[str, Any]:
        try:
            job = self._load_job(video_id)
            endpoint = job.get('endpointReview')
            if (job.get('state') == 'VALIDATED' and isinstance(endpoint, dict)
                    and endpoint.get('result') != 'accepted'):
                return self._validate_endpoint_review(job)
            if self._validated_snapshot_is_current(job):
                self._write_task(job)
                return self.summary(job)
            if job.get("state") in TERMINAL_STATES:
                raise FutureSongError(
                    "the completed job's validation snapshot is no longer intact; "
                    "delivery was not modified"
                )
            return self._validate_impl(video_id)
        except WorkerRetirementError as error:
            try:
                self._commit_failed_alignment_attempt(video_id, diagnostic_code(error))
                self._record_validation_failure(video_id, diagnostic_code(error))
            except Exception:
                pass
            raise
        except KeyboardInterrupt as error:
            report_error = None
            try:
                self._commit_failed_alignment_attempt(video_id, "KeyboardInterrupt")
            except (OSError, ValueError) as secondary:
                self._raise_if_prediction_stopped(secondary)
                report_error = secondary
            try:
                self._record_validation_failure(
                    video_id,
                    "validation was interrupted; approved editorial text was preserved; "
                    "run Validate to resume",
                )
            except Exception as secondary:
                self._raise_if_prediction_stopped(secondary)
            if report_error is not None:
                report_error.__cause__ = error.__cause__
                raise error from report_error
            raise
        except FutureSongOutcomeError as error:
            failed_attempt = self._load_job(video_id).get("activeAlignmentAttemptId")
            report_error = None
            try:
                if isinstance(error, LyricsContentBlocker) or error.code in {"line-unresolved", "retryable-technical"}:
                    self._commit_failed_alignment_attempt(video_id, error.code)
            except (OSError, ValueError) as secondary:
                self._raise_if_prediction_stopped(secondary)
                report_error = secondary
            current = self._load_job(video_id)
            stored_outcome = current.get("outcome")
            if isinstance(error, LyricsContentBlocker) or current.get("state") != error.state or (
                not isinstance(stored_outcome, dict)
                or stored_outcome.get("code") != error.code
            ):
                self._record_validation_outcome(video_id, error)
            if report_error is not None:
                report_error.__cause__ = error.__cause__
                raise error from report_error
            if isinstance(error, LyricsContentBlocker):
                if error.reasons == ('content-sequence-uncertain',):
                    current = self._load_job(video_id)
                    inputs = self._review_inputs(current, original_only=True)
                    rejection = {'audioSha256': inputs['audioSha256'], 'draftSha256': inputs['draftSha256'],
                        'originalMetadataSha256': inputs['originalMetadataSha256'],
                        'contentBinding': error.binding, 'reasons': list(error.reasons)}
                    self._recover_lyrics_stage(current, lambda:
                        self.acquisition(video_id).continue_after_content_rejection(current, rejection))
                raise
            current = self._load_job(video_id)
            pointer = current.get("endpointReview")
            if (error.code == "line-unresolved" and isinstance(failed_attempt, str)
                    and isinstance(pointer, dict)
                    and pointer.get("sourceAlignmentRunId") == failed_attempt
                    and "result" not in pointer):
                recovery = self.endpoint_evidence(video_id, vocal=True)
                if recovery.get("automaticallySelected") is True:
                    return self._validate_endpoint_review(self._load_job(video_id))
            raise
        except Exception as error:
            self._raise_if_prediction_stopped(error)
            blocked = self._load_job(video_id)
            if (blocked.get("state") == "AUTHORITY_UNRESOLVED"
                    or (blocked.get("outcome") or {}).get("code") == "lyrics-content-unresolved"):
                # An already durable blocker must survive task/cleanup failures.
                raise
            endpoint_code = endpoint_review_error_code(error)
            safe_message = (
                endpoint_code
                if isinstance(error, EndpointReviewSelectionError) and endpoint_code
                else (
                    "future-song-error"
                    if isinstance(error, FutureSongError)
                    else diagnostic_code(error)
                )
            )
            report_error = None
            try:
                self._commit_failed_alignment_attempt(video_id, diagnostic_code(error))
            except (OSError, ValueError) as secondary:
                self._raise_if_prediction_stopped(secondary)
                report_error = secondary
            try:
                self._record_validation_failure(video_id, safe_message)
            except Exception as secondary:
                self._raise_if_prediction_stopped(secondary)
            if report_error is not None:
                report_error.__cause__ = error.__cause__
                error.__cause__ = report_error
            if isinstance(error, FutureSongError):
                raise
            raise FutureSongError(f"validation failed ({diagnostic_code(error)})") from error

    def finalize(self, video_id: str) -> dict[str, Any]:
        video_id = validate_video_id(video_id)
        return self._run_mutating_operation(
            video_id,
            "Finalize",
            lambda: self._finalize_action(video_id),
        )

    def _finalize_action(self, video_id: str) -> dict[str, Any]:
        from stemwise_engine.delivery_service import DeliveryService
        return DeliveryService(self).run(video_id)



    def _cleanup_completed_rollback(self, job: dict[str, Any]) -> None:
        if job.get("state") != "DELIVERED" or not isinstance(job.get("delivery"), dict):
            return
        deliveries = self.source.complete_delivery(self, job)
        video_id = validate_video_id(job["videoId"])
        destination = self.ready / f"{video_id}.stemwise.zip"
        if sha256_file(destination) != job["delivery"].get("sha256"):
            raise FutureSongError("completed rollback cleanup requires the current delivery")
        from stemwise_engine.original_delivery import cleanup_replacement_rollback
        cleanup_replacement_rollback(self, video_id)
        for item in deliveries:
            if item['videoId'] != video_id:
                cleanup_replacement_rollback(self, item['videoId'])
        if self.source.result_binding(job) is not None and 'nativePublication' in job:
            job.pop('nativePublication')
            self._save_job(job)
        from stemwise_engine.job_ownership import cleanup_analysis_replacement
        cleanup_analysis_replacement(self, job)

    def _result_binding(self, job):
        return self.source.result_binding(job)

    def replace_analysis(self, video_id, *, expected_delivery_sha256, expected_analysis_sha256):
        from stemwise_engine.job_ownership import admit_analysis_replacement
        return self._run_mutating_operation(video_id, 'ReplaceAnalysis', lambda:
            admit_analysis_replacement(self, video_id, expected_delivery_sha256, expected_analysis_sha256))

    def retry(self, video_id: str, *, max_file_bytes: int | None = None,
              manual_import: bool = False) -> dict[str, Any]:
        video_id = validate_video_id(video_id)
        return self._run_mutating_operation(
            video_id,
            "Retry",
            lambda: self._retry_action(video_id, max_file_bytes=max_file_bytes, manual_import=manual_import),
        )

    def _retry_action(self, video_id: str, *, max_file_bytes: int | None = None,
                      manual_import: bool = False) -> dict[str, Any]:
        job = self._load_job(video_id)
        if job.get("state") in TERMINAL_STATES:
            raise FutureSongError("A completed job cannot be retried or reopened")
        from stemwise_engine.bridge_delivery import bind_budget
        bind_budget(job, max_file_bytes)
        if manual_import:
            if job.get('state') != 'ANALYSIS_FAILED' or job.get('error') != 'bridge-package-too-large':
                raise FutureSongError('Manual import retry requires a proven bridge package budget failure')
            job['bridgeManualImport'] = True
        self._save_job(job)
        from stemwise_engine.job_ownership import inspect_analysis_replacement, run_analysis_replacement
        job, replacement_pending = inspect_analysis_replacement(self, job)
        if (replacement_pending or job.get('analysisReplacement') and job['state'] in
                {'ANALYSIS_REPLACEMENT_PENDING', 'ANALYZING', 'ANALYSIS_FAILED'}):
            return run_analysis_replacement(self, job)
        from stemwise_engine.workflow_states import JobProgress
        recovery = JobProgress.current(job).rule.retry_action
        if recovery is None:
            raise WorkflowInputError('Retry requires a failed recoverable stage',
                reason_code='future-song-error', affected_input='current job stage',
                next_action='Read Task and use its current permitted action.')
        if recovery == 'RecordingEvidence':
            return self._run_recording_evidence(video_id)
        retry_state = job.get("state")
        if retry_state == "AUTHORITY_UNRESOLVED":
            authority_outcome, _, _ = self._validate_authority_outcome(job)
            if authority_outcome != "resolved":
                return self._validate_action(video_id)
        if recovery == 'Validate':
            try:
                self._completed_analysis_audio(self._pipeline(), video_id)
            except FutureSongError:
                self.acquisition(job['videoId']).run(job, force_analysis=True)
                self._save_job(job)
                self._write_task(job)
            return self._validate_action(video_id)
        self.acquisition(job['videoId']).run(
            job,
            force_analysis=(
                retry_state == "ANALYSIS_FAILED"
                and job.get("error") != "worker-not-active"
            ),
        )
        self._save_job(job)
        self._write_task(job)
        return self.summary(job)


    def summary(self, job: dict[str, Any], *, verify_delivery: bool = True) -> dict[str, Any]:
        from stemwise_engine.workflow_status import project_job_summary
        from stemwise_engine.job_ownership import inspect_analysis_replacement
        replacement_busy = False
        try:
            job, replacement_pending = inspect_analysis_replacement(self, job)
        except ProcessLockBusyError:
            job = self._load_job(job['videoId'], require_active_policy=False)
            replacement_pending = False
            replacement_busy = True
        review_attempts = {}
        for field, locate in (('endpointReview', self._endpoint_review_attempt_dir),
                              ('displayReview', self._display_review_attempt_dir)):
            review = job.get(field)
            if isinstance(review, dict) and isinstance(review.get('attemptId'), str):
                try:
                    review_attempts[field] = locate(job['videoId'], review['attemptId'])
                except FutureSongError:
                    pass
        result = project_job_summary(job, workspace_root=self.workspace_root, root=self.root,
            runtime=self.runtime, job_dir=self._job_dir(job['videoId']),
            recording_evidence_inputs=self._recording_evidence_inputs(job), review_attempts=review_attempts)
        if job.get('state') in {'VALIDATED', 'DELIVERED'}:
            try:
                from stemwise_engine.editorial_admission import require_completed_lyrics
                document, _ = read_child(self.runtime/'children'/f"{job['videoId']}.stemwise.zip", self._runtime_config())
                require_completed_lyrics(document['lyrics'], duration_ms=document['analysis']['durationMs'],
                    endpoint_review=job.get('endpointReview'))
            except (OSError, ValueError, KeyError, TypeError) as error:
                result.pop('delivery', None)
                result.update(sourceState=job['state'], state='STATUS_ERROR', statusError=(
                    error.failure_details['reasonCode'] if isinstance(error, WorkflowInputError)
                    else 'validation-incomplete-or-stale'))
                if isinstance(error, WorkflowInputError):
                    result['failureDetails'] = dict(error.failure_details)
        if replacement_busy:
            result.update(permittedActions=[], nextAction='Status')
        elif replacement_pending:
            from stemwise_engine.workflow_states import JobProgress
            progress = JobProgress.current({'state': 'ANALYSIS_REPLACEMENT_PENDING'})
            result.update(state='ANALYSIS_REPLACEMENT_PENDING', permittedActions=sorted(progress.rule.actions),
                nextAction=progress.next_action, progress=progress.to_record())
        ledger_path = self._job_dir(job['videoId'])/'song-attempt.private.json'
        if ledger_path.is_file():
            from stemwise_engine.alignment_attempt import SongAttemptLedger
            ledger = SongAttemptLedger(ledger_path)
            result['attemptAccounting'] = {'activeSeconds': ledger.elapsed(),
                'recoverySeconds': ledger.elapsed('recovery'), 'waitSeconds': ledger.record['waitSeconds']}
            if ledger.blocking_disposition:
                result['disposition'] = ledger.blocking_disposition
        if getattr(self, 'source_context', None) is not None:
            deliveries = []
            if (job.get('state') == 'DELIVERED' and job.get('nativePublication') == {
                    'phase': 'pending', 'packageSha256': job.get('delivery', {}).get('sha256')}):
                result.pop('delivery', None)
                result.update(state='DELIVERY_PUBLICATION_PENDING', nextAction='Status' if replacement_busy else 'Finalize',
                    recordingKey=self.key, recordingInput=self.recording.identity,
                    resultScope='recording-enrichment', phoneDeliveryAllowed=False, phoneDeliveries=[])
                return result
            if result['state'] == 'DELIVERED' and verify_delivery:
                from stemwise_engine.native_delivery import current
                try:
                    deliveries = current(self, job)
                except (OSError, ValueError):
                    result.update(state='STATUS_ERROR', statusError='native-phone-delivery-invalid')
                else:
                    if not deliveries:
                        from stemwise_engine.native_delivery import _selected_ids
                        result['state'] = 'DELIVERY_AUDIO_REQUIRED' if _selected_ids(self) else 'DELIVERY_NOT_SELECTED'
                        result.pop('delivery', None)
            result.update(recordingKey=self.key, recordingInput=self.recording.identity,
                         resultScope='recording-enrichment', phoneDeliveryAllowed=bool(deliveries),
                         phoneDeliveries=deliveries)
            if 'delivery' in result:
                result['delivery']['readyToImport'] = bool(deliveries)
            return result

        return result


    def _status_summary(self, job: dict[str, Any], *, verify_delivery: bool = True) -> dict[str, Any]:
        result = self.summary(job) if verify_delivery else self.summary(job, verify_delivery=False)
        if (job.get('state') == 'VALIDATED' and result['state'] != 'STATUS_ERROR'
                and not self._validated_snapshot_is_current(job)):
            result.update(sourceState='VALIDATED', state='STATUS_ERROR', statusError='validation-incomplete-or-stale')
        if job.get('state') == 'DELIVERED' and result['state'] != 'STATUS_ERROR' and not verify_delivery:
            result['deliveryVerification'] = {'status': 'not-rechecked',
                                              'nextAction': 'Finalize' if 'Finalize' in result['permittedActions'] else 'Status',
                                              'scope': 'committed-state-projection'}
            delivery = job.get('delivery')
            try:
                if not isinstance(delivery, dict):
                    raise ValueError('delivery receipt is missing')
                path = validate_generated_path(self.ready,
                    self.ready / f"{job['videoId']}.stemwise.zip", workspace_root=self.workspace_root)
                if not path.is_file() or type(delivery.get('bytes')) is not int or path.stat().st_size != delivery['bytes']:
                    raise ValueError('delivery receipt size differs')
            except (OSError, ValueError):
                result.pop('delivery', None)
                result.update(sourceState='DELIVERED', state='STATUS_ERROR', statusError='delivery-file-unavailable')
            try:
                validation = job['validation']
                if validation.get('kind') == 'capability-completion':
                    current, expected = self._capability_input_sha(job['videoId']), validation['inputSha256']
                else:
                    current, expected = self._editorial_sha(job), validation['editorialSha256']
                if current != expected:
                    raise ValueError('Current lyric authority differs from validation')
            except (OSError, ValueError, KeyError, TypeError):
                result.pop('delivery', None)
                if result['state'] != 'STATUS_ERROR':
                    result.update(sourceState='DELIVERED', state='STATUS_ERROR', statusError='validation-incomplete-or-stale')
        elif job.get("state") == "DELIVERED" and result['state'] != 'STATUS_ERROR' and not self._validated_snapshot_is_current(
            job,
            require_delivery=True,
            allow_pending_delivery_cleanup=True,
        ):
            result.pop("delivery", None)
            result.update(
                sourceState="DELIVERED",
                state="STATUS_ERROR",
                statusError="delivery-integrity-failed",
            )
        if (result.get('statusError') in {'validation-incomplete-or-stale', 'delivery-integrity-failed'}
                and job.get('state') in {'VALIDATED', 'DELIVERED'}):
            # Diagnose stale completion from current proof without approving a new snapshot.
            try:
                from stemwise_engine.editorial_admission import available_lyrics, require_completed_lyrics
                checkpoint = read_bounded_json(self._pipeline().checkpoint_path(job['videoId']),
                    max_bytes=MAX_JSON_BYTES, root=self.runtime)
                blockers = []
                projection, _ = available_lyrics(self, job, checkpoint['originalAudio'], on_blocker=blockers.append)
                if blockers:
                    raise blockers[0]
                require_completed_lyrics(projection, duration_ms=checkpoint['originalAudio']['durationMs'],
                    endpoint_review=job.get('endpointReview'))
            except (WorkflowInputError, FutureSongOutcomeError) as error:
                from stemwise_engine.workflow_records import workflow_failure_details
                details = workflow_failure_details(error)
                result.update(statusError=details['reasonCode'], failureDetails=details)
            except (OSError, ValueError, KeyError, TypeError):
                pass  # The already rejected snapshot remains rejected if diagnosis is unavailable.
        if getattr(self, 'source_context', None) is not None:
            if result['state'] in {'DELIVERED', 'DELIVERY_AUDIO_REQUIRED', 'DELIVERY_NOT_SELECTED'}:
                try:
                    binding = read_bounded_json(self.ready / 'recording.json', max_bytes=MAX_JSON_BYTES, root=self.ready)
                    if binding != self._result_binding(job):
                        raise ValueError('Native result binding differs')
                except (OSError, ValueError):
                    result.pop('delivery', None)
                    result.update(state='STATUS_ERROR', statusError='recording-result-binding-invalid')
                else:
                    if result['state'] == 'DELIVERY_AUDIO_REQUIRED':
                        result['nextAction'] = 'Finalize' if 'Finalize' in result['permittedActions'] else 'Status'
        return result

    def status(self, video_id: str | None, *, verify_delivery: bool = False) -> dict[str, Any]:
        if video_id:
            job = self._load_job(validate_video_id(video_id), require_active_policy=False)
            if "platformContentRestriction" in job:
                return {"videoId": video_id, "state": job.get("state"),
                        "updatedAt": job.get("updatedAt"), "deliveryAllowed": False,
                        "platformContentRestriction": {"code": "platform-content-restriction"}}
            job = self._reconcile_interrupted_validation(job)
            result = self._status_summary(job, verify_delivery=verify_delivery)
            try:
                operation = self.report_store.latest_workflow_operation(video_id)
                if operation is not None:
                    result["latestOperation"] = operation
            except (OSError, ValueError):
                result["operationReportError"] = "workflow-operation-record-invalid"
            if result["state"] not in TERMINAL_STATES | {
                    "NEW", "ANALYZING", "ANALYSIS_FAILED", "ANALYSIS_REPLACEMENT_PENDING", "STATUS_ERROR"}:
                try:
                    result["reviewInputs"] = self._review_inputs(job)
                except (OSError, ValueError, KeyError):
                    result["reviewInputs"] = {"status": "unavailable-until-editorial-setup-completes"}
            owner_inputs = self._owner_verification_inputs(job)
            if owner_inputs is not None:
                result.setdefault('reviewInputs', {})['ownerVerification'] = owner_inputs
            return result
        paths = sorted(self.jobs.glob("*/job.json"))
        summaries: dict[int, dict[str, Any]] = {}
        delivered: list[tuple[int, str, dict[str, Any]]] = []
        for index, path in enumerate(paths):
            try:
                job = self._reconcile_interrupted_validation(read_json(path))
                if job.get("state") == "DELIVERED":
                    delivered.append(
                        (index, validate_video_id(job.get("videoId")), job)
                    )
                else:
                    summaries[index] = self._status_summary(job, verify_delivery=verify_delivery)
            except Exception as error:
                try:
                    error_video_id = validate_video_id(path.parent.name)
                except ValueError:
                    error_video_id = "invalid-id"
                summaries[index] = {
                    "videoId": error_video_id,
                    "state": "STATUS_ERROR",
                    "statusError": diagnostic_code(error),
                }
        if delivered:
            with ThreadPoolExecutor(max_workers=min(8, len(delivered))) as workers:
                pending = [
                    (index, video_id, workers.submit(self._status_summary, job, verify_delivery=verify_delivery))
                    for index, video_id, job in delivered
                ]
                for index, video_id, pending_summary in pending:
                    try:
                        summaries[index] = pending_summary.result()
                    except Exception as error:
                        summaries[index] = {
                            "videoId": video_id,
                            "state": "STATUS_ERROR",
                            "statusError": diagnostic_code(error),
                        }
        jobs = [self._aggregate_status_summary(summaries[index]) for index in range(len(paths))]
        return {
            "jobCount": len(jobs),
            "stateCounts": {
                state: sum(job["state"] == state for job in jobs)
                for state in sorted({job["state"] for job in jobs})
            },
            "jobs": jobs,
        }


    @staticmethod
    def _aggregate_status_summary(summary):
        from stemwise_engine.workflow_status import aggregate_status_summary
        return aggregate_status_summary(summary)
